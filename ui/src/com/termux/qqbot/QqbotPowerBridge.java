package com.termux.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

/**
 * 「充电高性能」桥（JS 名 AndroidPower，更多页 #powerCard 用）。
 *
 * 说清楚这到底做了什么（不吹牛）：
 *   插着电 + 用户打开开关时，本类持有两个锁——
 *     1) PARTIAL_WAKE_LOCK：CPU 不睡，后台线程不被挂起；
 *     2) WifiLock(HIGH_PERF)：WiFi 芯片不省电降频，长连接不容易抖。
 *   同时让守护（QqbotGuardService.statusTick）每 15 秒跑一轮，掉线能更快被发现。
 *
 *   它**不会**改 CPU 频率、不会超频、不会绕过系统的温控——那些要 root，本类不碰。
 *   拔电 / 充满拔线 / 用户关开关 → 立刻释放两个锁，回到普通模式。
 *
 * 注意：清单里目前没有 ACCESS_WIFI_STATE，WifiLock 有可能被系统拒。
 * 拿不到就只保 CPU，状态里如实说明，不假装成功。
 *
 * d8 不接受 lambda，本文件不出现匿名内部类。
 */
public class QqbotPowerBridge {

    private static final String PREF = "qqbot_power";
    private static final String KEY_ON = "on";

    private static final String TAG_WAKE = "qqbot:power";
    private static final String TAG_WIFI = "qqbot:power:wifi";

    /** 高性能时守护心跳间隔（普通模式 60 秒一次，耗不了多少电）。 */
    private static final long BEAT_ACTIVE_MS = 15000L;
    private static final long BEAT_IDLE_MS = 60000L;

    private static final Object LOCK = new Object();

    private static volatile boolean on = false;
    private static volatile boolean active = false;
    private static volatile long since = 0L;
    private static volatile boolean charging = false;
    private static volatile int level = -1;
    private static volatile double temp = 0d;
    private static volatile boolean wifiDenied = false;

    private static volatile Context appCtx = null;
    private static volatile boolean recvOn = false;
    private static volatile boolean beatOn = false;
    private static volatile Thread beatThread = null;

    private static PowerManager.WakeLock wake = null;
    private static WifiManager.WifiLock wifi = null;

    private final QqbotActivity act;

    public QqbotPowerBridge(QqbotActivity act) {
        this.act = act;
        Context c = act;
        try {
            Context app = act.getApplicationContext();
            if (app != null) c = app;
        } catch (Throwable ignored) {
        }
        init(c);
    }

    public QqbotPowerBridge(Context c) {
        this.act = null;
        init(c);
    }

    private void init(Context c) {
        try {
            if (c == null) return;
            appCtx = c;
            SharedPreferences sp = prefs(c);
            on = sp != null && sp.getBoolean(KEY_ON, false);
            readBattery(c);
            ensureReceiver(c);
            ensureBeat();
            if (on && charging) apply(c);
        } catch (Throwable ignored) {
        }
    }

    /* ======================= 对外接口 ======================= */

    /** 当前电量和充电情况 + 一句能不能开的提示。 */
    @JavascriptInterface
    public String get() {
        JSONObject out = new JSONObject();
        try {
            Context c = ctx();
            if (c == null) return fail("还没初始化好，回上一页再进来");
            readBattery(c);
            out.put("ok", true);
            out.put("on", on);
            out.put("charging", charging);
            out.put("level", level);
            out.put("temp", round1(temp));
            out.put("hint", hintForGet());
            return out.toString();
        } catch (Throwable t) {
            return fail("读电池状态失败：" + brief(t));
        }
    }

    /** 开 / 关。{"on":true} 开，{"on":false} 关。 */
    @JavascriptInterface
    public String set(String json) {
        JSONObject out = new JSONObject();
        try {
            Context c = ctx();
            if (c == null) return fail("还没初始化好，回上一页再进来");
            String raw = (json == null || json.trim().length() == 0) ? "{}" : json;
            JSONObject in = new JSONObject(raw);
            if (!in.has("on")) return fail("没说清楚是开还是关（缺 on 字段）");
            boolean want = in.optBoolean("on", false);
            on = want;
            SharedPreferences sp = prefs(c);
            if (sp != null) sp.edit().putBoolean(KEY_ON, want).apply();
            readBattery(c);
            ensureReceiver(c);
            ensureBeat();
            if (want && charging) {
                apply(c);
            } else {
                release();
            }
            out.put("ok", true);
            out.put("on", on);
            if (!want) {
                out.put("msg", "已关闭：回到普通模式，不再额外耗电");
            } else if (charging) {
                out.put("msg", "充电时会保持高性能");
            } else {
                out.put("msg", "已记下：插上电就开始保持高性能");
            }
            return out.toString();
        } catch (Throwable t) {
            return fail("设置高性能失败：" + brief(t));
        }
    }

    /** 现在到底有没有在保持高性能。 */
    @JavascriptInterface
    public String state() {
        JSONObject out = new JSONObject();
        try {
            Context c = ctx();
            if (c != null) readBattery(c);
            out.put("ok", true);
            out.put("on", on);
            out.put("active", active);
            out.put("since", since);
            String msg = msgForState();
            // 守护里有个「息屏保持运行」开关：用户关掉它时，这里只按充电场景临时持锁，
            // 状态里说清楚，免得用户以为我们在偷偷让它常驻。
            try {
                if (active && c != null && !QqbotGuardService.wakeEnabled(c)) {
                    msg = msg + "（守护里的「息屏保持运行」是关的，这里只按充电临时持锁）";
                }
            } catch (Throwable ignored) {
            }
            out.put("msg", msg);
            return out.toString();
        } catch (Throwable t) {
            return fail("读高性能状态失败：" + brief(t));
        }
    }

    /* ======================= 给 lead 接线的静态口 ======================= */

    /**
     * 守护 / 开机起来时踢一下：把开关状态、充电监听、心跳线程准备好。
     * 没有它也能用（用户点开更多页会走到构造器），但早一点调，插着电开机就直接进高性能。
     */
    public static void warm(Context c) {
        try {
            if (c == null) return;
            Context app = c.getApplicationContext();
            if (app != null) appCtx = app; else appCtx = c;
            SharedPreferences sp = prefs(appCtx);
            on = sp != null && sp.getBoolean(KEY_ON, false);
            readBattery(appCtx);
            ensureReceiver(appCtx);
            ensureBeat();
            if (on && charging) apply(appCtx);
        } catch (Throwable ignored) {
        }
    }

    /** 守护里想知道「现在是不是高性能模式」就调这个（true 时才建议把采样调快）。 */
    public static boolean boostActive(Context c) {
        return active;
    }

    /* ======================= 锁 ======================= */

    private static void apply(Context c) {
        synchronized (LOCK) {
            if (active) return;
            try {
                PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
                if (pm == null) return;
                PowerManager.WakeLock w = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG_WAKE);
                w.setReferenceCounted(false);
                w.acquire();
                wake = w;
            } catch (Throwable t) {
                // CPU 锁都拿不到就别装了，如实失败
                releaseLocked();
                return;
            }
            wifiDenied = false;
            try {
                WifiManager wm = (WifiManager) c.getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    WifiManager.WifiLock wl = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, TAG_WIFI);
                    wl.setReferenceCounted(false);
                    wl.acquire();
                    wifi = wl;
                } else {
                    wifiDenied = true;
                }
            } catch (Throwable t) {
                // 没有 ACCESS_WIFI_STATE 或系统不给：只保 CPU，状态里说明
                wifiDenied = true;
                wifi = null;
            }
            active = true;
            since = System.currentTimeMillis();
            wakeBeat();
        }
    }

    private static void release() {
        synchronized (LOCK) {
            releaseLocked();
        }
    }

    private static void releaseLocked() {
        try {
            if (wifi != null && wifi.isHeld()) wifi.release();
        } catch (Throwable ignored) {
        }
        wifi = null;
        try {
            if (wake != null && wake.isHeld()) wake.release();
        } catch (Throwable ignored) {
        }
        wake = null;
        active = false;
        since = 0L;
    }

    /* ======================= 电池 / 充电监听 ======================= */

    /** 读一次当前电量：拿系统的粘性广播（BATTERY_CHANGED），读不到就保留上一次的值。 */
    private static void readBattery(Context c) {
        if (c == null) return;
        try {
            Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null) return;
            parseBattery(b);
        } catch (Throwable ignored) {
        }
    }

    /** 从一条电池广播里取：是否插着电、电量百分比、温度。 */
    private static void parseBattery(Intent b) {
        if (b == null) return;
        try {
            int plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            charging = plugged != 0;
            int lv = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int sc = b.getIntExtra(BatteryManager.EXTRA_SCALE, 0);
            if (lv >= 0 && sc > 0) {
                level = Math.round(lv * 100f / sc);
            } else if (lv >= 0) {
                level = lv;
            }
            int t = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
            temp = t / 10.0;
        } catch (Throwable ignored) {
        }
    }

    private static void ensureReceiver(Context c) {
        synchronized (LOCK) {
            if (recvOn || c == null) return;
            try {
                IntentFilter f = new IntentFilter();
                f.addAction(Intent.ACTION_POWER_CONNECTED);
                f.addAction(Intent.ACTION_POWER_DISCONNECTED);
                f.addAction(Intent.ACTION_BATTERY_CHANGED);
                PowerRecv r = new PowerRecv();
                if (Build.VERSION.SDK_INT >= 33) {
                    c.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    c.registerReceiver(r, f);
                }
                recvOn = true;
            } catch (Throwable t) {
                recvOn = false;
            }
        }
    }

    /** 插电 / 拔电 / 电量变化：插上就上锁，拔了立刻放。 */
    private static final class PowerRecv extends BroadcastReceiver {
        public void onReceive(Context c, Intent i) {
            try {
                Context base = (c == null) ? appCtx : c;
                // 电池广播本身就带着电量和插电状态，直接用，不用再查一次
                parseBattery(i);
                String a = (i == null || i.getAction() == null) ? "" : i.getAction();
                if (Intent.ACTION_POWER_DISCONNECTED.equals(a)) {
                    release();
                    return;
                }
                if (on && charging) {
                    if (base != null) apply(base);
                } else {
                    release();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /* ======================= 心跳 ======================= */

    /** 高性能期间把守护的巡检调快（statusTick 是幂等的，只在状态跃迁时发通知）。 */
    private static void ensureBeat() {
        if (beatOn) return;
        beatOn = true;
        try {
            Thread th = new Thread(new Beat(), "qqbot-power-beat");
            th.setDaemon(true);
            beatThread = th;
            th.start();
        } catch (Throwable t) {
            beatOn = false;
        }
    }

    private static final class Beat implements Runnable {
        public void run() {
            while (true) {
                boolean now = active;
                try {
                    if (now && appCtx != null) QqbotGuardService.statusTick(appCtx);
                } catch (Throwable ignored) {
                }
                try {
                    Thread.sleep(now ? BEAT_ACTIVE_MS : BEAT_IDLE_MS);
                } catch (InterruptedException ie) {
                    // 刚开/刚关：马上重新判断一次，别干等 60 秒
                    continue;
                } catch (Throwable t) {
                    return;
                }
            }
        }
    }

    /* ======================= 小工具 ======================= */

    /** 状态刚变（开/关/插电）：把心跳线程叫醒，立刻按新节奏跑。 */
    private static void wakeBeat() {
        try {
            Thread th = beatThread;
            if (th != null) th.interrupt();
        } catch (Throwable ignored) {
        }
    }

    private static Context ctx() {
        return appCtx;
    }

    private static SharedPreferences prefs(Context c) {
        if (c == null) return null;
        try {
            return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String hintForGet() {
        if (charging) {
            return on ? "插着电，已经在保持高性能" : "插着电，可以开";
        }
        return on ? "现在没插电：插上电才会保持高性能" : "现在没插电，插上电再开更合适";
    }

    private static String msgForState() {
        if (active) {
            return wifiDenied ? "正在保持高性能（这个系统不让锁 WiFi，只保住了 CPU）" : "正在保持高性能";
        }
        if (on) {
            return charging ? "插上电了，正在拉起高性能…" : "开着，等插电";
        }
        return "没开，当前是普通模式";
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static String fail(String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("err", msg);
        } catch (Throwable ignored) {
        }
        return o.toString();
    }

    private static String brief(Throwable t) {
        String s = (t == null) ? "" : String.valueOf(t);
        if (s.length() > 160) s = s.substring(0, 160) + "…";
        return s;
    }
}
