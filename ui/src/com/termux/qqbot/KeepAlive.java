package com.termux.app;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 后台保活心跳（1.3.3-beta.1 起）。
 *
 * 为什么需要：前台服务能挡掉一部分回收，但挡不住厂商 ROM 的「后台高耗电」冻结、
 * Android 12+ 的 phantom process killer，以及缓存进程整组冻结。被冻结时进程还在、
 * 端口还开着，但 CPU 一秒钟都拿不到，表现就是「看着在运行，其实没运行，点回软件才继续」。
 *
 * 做法：AlarmManager 每 2 分钟发一次精确广播（setExactAndAllowWhileIdle）。每次投递
 * 系统会给本应用 10 分钟「临时白名单」，2 分钟一次就相当于整段时间都在白名单里，
 * 冻结与 Doze 都绕过去了；广播本身还会把服务拉起来、把沙盒兜回来。
 *
 * targetSdk 是 28，所以 setExactAndAllowWhileIdle 不需要 SCHEDULE_EXACT_ALARM 权限，
 * 也不会被 Android 12+ 的精确闹钟限制拦下。
 */
public class KeepAlive {

    public static final String ACTION = "com.termux.app.KEEPALIVE";
    private static final int REQ = 42312;
    /** 2 分钟：小于系统给的 10 分钟临时白名单窗口，白名单才能首尾相接。 */
    private static final long EVERY_MS = 120000L;
    private static final String PREF = "qqbot_keepalive";
    private static final String K_FAIL = "probeFail";
    private static final String K_LAST = "lastRestart";
    /** 连续几次 HTTP 探测没回才认定「被冻结」，3 次约 6 分钟。 */
    private static final int FAIL_MAX = 3;
    /** 两次「冻结重启」之间至少隔 10 分钟，避免沙盒自己起得慢时被反复重启。 */
    private static final long RESTART_GAP_MS = 600000L;

    private KeepAlive() {
    }

    /** 用户主动关过（关闭进程 / 紧急下线）就不再自己醒来。 */
    public static boolean closedByUser(Context c) {
        try {
            return StateIO.file(c.getFilesDir(), "offline").isFile();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 排下一次心跳。每次收到广播 / 服务启动时都调一次，链就不断。 */
    public static void arm(Context c) {
        try {
            if (closedByUser(c) || !QqbotGuardService.enabled(c)) {
                disarm(c);
                return;
            }
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(c, KeepAliveReceiver.class);
            i.setAction(ACTION);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(c, REQ, i, flags);
            if (pi == null) return;
            long at = System.currentTimeMillis() + EVERY_MS;
            // 用户关掉「息屏保持运行」时用非唤醒闹钟：屏幕不亮、设备睡着就等它自己醒，
            // 只保证进程被系统收掉后还能被拉回来，不强行耗电。
            boolean wake = QqbotGuardService.wakeEnabled(c);
            int type = wake ? AlarmManager.RTC_WAKEUP : AlarmManager.RTC;
            if (wake && Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(type, at, pi);
            } else {
                am.setExact(type, at, pi);
            }
        } catch (Throwable t) {
            // 各家 ROM 对精确闹钟的实现有差异，失败就退回普通闹钟，绝不因此崩掉服务。
            try {
                AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
                Intent i = new Intent(c, KeepAliveReceiver.class);
                i.setAction(ACTION);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
                PendingIntent pi = PendingIntent.getBroadcast(c, REQ, i, flags);
                if (am != null && pi != null) {
                    am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + EVERY_MS, pi);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 用户点了「关闭进程」或关掉常驻守护：把心跳撤掉，别让手机继续被叫醒。 */
    public static void disarm(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(c, KeepAliveReceiver.class);
            i.setAction(ACTION);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(c, REQ, i, flags);
            if (pi != null) {
                am.cancel(pi);
                pi.cancel();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 心跳里的体检 + 自救（在主线程外调用）：
     * ① 沙盒进程没了 → 直接拉起；
     * ② 进程在、但 6099 的 WebUI 连续 3 次不应答（多半被整组冻结）→ 重启沙盒。
     * 只在应用处于后台时做 ②：用户正开着面板时，守护循环每 60 秒本来就会兜底，
     * 不该让它把正在冷启动的沙盒又打断一次。
     */
    public static void rescue(Context c) {
        try {
            File filesDir = c.getFilesDir();
            if (closedByUser(c) || !QqbotGuardService.enabled(c)) return;
            if (!QqbotShell.bootstrapReady(filesDir)) return;
            File qq = QqbotShell.qqbotDir(filesDir);
            if (!new File(qq, "deploy.done").isFile()) return;
            File pid = new File(qq, "supervisor.pid");
            if (!QqbotShell.pidAlive(pid, "proot")) {
                StringBuilder n = new StringBuilder();
                boolean ok = QqbotShell.startSandbox(filesDir, n);
                android.util.Log.i("QqbotKeepAlive", "心跳发现沙盒没在跑：" + (ok ? "已拉起" : "拉起失败 " + n));
                return;
            }
            int port = botPort(c);
            if (port <= 0) return;
            if (background(c) && !httpAlive(port)) {
                SharedPreferences p = pref(c);
                int fail = p.getInt(K_FAIL, 0) + 1;
                long now = System.currentTimeMillis();
                long last = p.getLong(K_LAST, 0L);
                if (fail >= FAIL_MAX && now - last > RESTART_GAP_MS) {
                    p.edit().putInt(K_FAIL, 0).putLong(K_LAST, now).apply();
                    android.util.Log.w("QqbotKeepAlive", "沙盒进程在、WebUI 连续 " + fail + " 次没应答，按冻结处理并重启沙盒");
                    File stop = new File(QqbotShell.usrDir(filesDir), "opt/qqbot/stop-sandbox.sh");
                    if (stop.isFile()) QqbotShell.run(filesDir, "bash " + stop.getAbsolutePath(), 120);
                    StringBuilder n = new StringBuilder();
                    QqbotShell.startSandbox(filesDir, n);
                    QqbotShell.startWatchdog(filesDir, null);
                } else {
                    p.edit().putInt(K_FAIL, fail).apply();
                }
                return;
            }
            pref(c).edit().putInt(K_FAIL, 0).apply();
            QqbotShell.startWatchdog(filesDir, null);
        } catch (Throwable t) {
            android.util.Log.w("QqbotKeepAlive", "心跳自检异常：" + t);
        }
    }

    private static SharedPreferences pref(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 应用当前是否在后台（IMPORTANCE_VISIBLE 及以下才算用户看得见）。 */
    private static boolean background(Context c) {
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return true;
            int pid = android.os.Process.myPid();
            java.util.List<ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
            if (list == null) return true;
            for (int k = 0; k < list.size(); k++) {
                ActivityManager.RunningAppProcessInfo r = list.get(k);
                if (r != null && r.pid == pid) {
                    return r.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
                }
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    /**
     * 真的发一个 HTTP 请求：被冻结的进程虽然 socket 还挂着，但连接会被内核排进 backlog，
     * 读不到任何响应；能读到首字节才算活着。
     */
    private static boolean httpAlive(int port) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", port), 1500);
            s.setSoTimeout(3000);
            OutputStream o = s.getOutputStream();
            o.write("GET / HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n".getBytes("UTF-8"));
            o.flush();
            InputStream in = s.getInputStream();
            return in.read() >= 0;
        } catch (Throwable t) {
            return false;
        } finally {
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    /** 读 bots.json 第一个实例的 NapCat 端口；读不到就用默认 6099。 */
    private static int botPort(Context c) {
        try {
            File qq = QqbotShell.qqbotDir(c.getFilesDir());
            String s = StateIO.read(new File(qq, "bots.json"));
            if (s == null) s = StateIO.read(new File(new File(qq, "cache"), "bots.json"));
            if (s == null) return 6099;
            JSONObject o = new JSONObject(s);
            JSONArray a = o.optJSONArray("bots");
            if (a == null || a.length() == 0) return 6099;
            JSONObject b = a.optJSONObject(0);
            if (b == null) return 6099;
            int p = b.optInt("napcatPort", 6099);
            return p > 0 ? p : 6099;
        } catch (Throwable t) {
            return 6099;
        }
    }
}