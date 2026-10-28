package com.termux.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

import org.json.JSONObject;

import java.io.File;

/**
 * 为什么需要它：电量见底或发热严重时还满功率跑 NapCat + AstrBot，轻则被系统杀掉，
 * 重则手机烫手、充电越来越慢。这里用动态广播 ACTION_BATTERY_CHANGED（不需要新权限，
 * 也不用清单声明）盯住电量与温度，超过阈值就在 $HOME/.qqbot/state/degrade 里写一行
 * battery/thermal，沙盒层自己读这个文件降载；原生侧只在通知里提示一句。
 * 带回差（<15% 进、>25% 出；>42℃ 进、<38℃ 出），避免在阈值附近反复切换。
 * 状态不变就不写文件、不重复通知；降载期间绝不删除任何用户数据。
 * d8 处理匿名内部类会崩，本类自己就是 BroadcastReceiver，不出现匿名类。
 */
public class BatteryMon extends BroadcastReceiver {

    /** 退出回差：比进入门槛高 10 个百分点 / 低 4℃。门槛本身由 QqbotLayers 的 protect 配置提供。 */
    private static final int LOW_PCT_OUT_DELTA = 10;
    private static final float HOT_C_OUT_DELTA = 4f;

    private static final String NAME = "degrade";

    private static volatile int level = -1;       // 0-100，-1 = 未知
    private static volatile float tempC = 0f;     // 摄氏度，0 = 未知
    private static volatile String degrade = "";  // "" | battery | thermal
    private static volatile long since = 0L;      // 进入降载的 epoch 秒
    private static volatile boolean registered = false;
    private static BatteryMon inst;

    private final QqbotGuardService svc;

    private BatteryMon(QqbotGuardService s) {
        svc = s;
    }

    /* ---------- 生命周期：与 QqbotGuardService 同生共死 ---------- */

    public static synchronized void start(QqbotGuardService s) {
        if (registered || s == null) return;
        try {
            BatteryMon r = new BatteryMon(s);
            IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            // ACTION_BATTERY_CHANGED 是粘性广播：registerReceiver 会把「当前值」直接返回，
            // 不用等第一次变化就已经有电量了。
            Intent sticky = s.registerReceiver(r, f);
            inst = r;
            registered = true;
            readExisting(s.getFilesDir());
            if (sticky != null) sample(s, sticky);
        } catch (Throwable t) {
            registered = false;
            inst = null;
        }
    }

    public static synchronized void stop(Context c) {
        BatteryMon r = inst;
        inst = null;
        registered = false;
        if (r == null || c == null) return;
        try {
            c.unregisterReceiver(r);
        } catch (Throwable ignored) {
        }
    }

    public static boolean running() { return registered; }

    @Override
    public void onReceive(Context c, Intent i) {
        try {
            // 实时回调里拿不到 start() 传进来的 svc，从静态实例上取；否则状态变了
            // 也没人负责往 state/degrade 写文件（注册时那条粘性广播只用一次）。
            BatteryMon r = inst;
            sample(r == null ? null : r.svc, i);
        } catch (Throwable ignored) {
        }
    }

    /* ---------- 采样 + 回差判断 ---------- */

    private static void sample(QqbotGuardService s, Intent i) {
        if (i == null) return;
        int raw = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (raw >= 0 && scale > 0) {
            int pct = raw * 100 / scale;
            if (pct < 0) pct = 0;
            if (pct > 100) pct = 100;
            level = pct;
        }
        int t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        if (t != Integer.MIN_VALUE) tempC = t / 10f;
        evaluate(s);
    }

    /** 回差判断：状态没变就什么都不做（不写文件、不通知）。 */
    private static void evaluate(QqbotGuardService s) {
        // 门槛来自看门狗的 protect 配置（默认 15% / 42℃，可在界面里改），
        // 桥保存后会立刻生效，不用重启服务。
        int lowIn = 15;
        float hotIn = 42f;
        try {
            int[] th = QqbotLayers.thresholds(s == null ? null : s.getFilesDir());
            if (th != null && th.length >= 2) {
                lowIn = th[0];
                hotIn = th[1];
            }
        } catch (Throwable ignored) {
        }
        boolean lowBat = level >= 0 && level < lowIn;
        boolean hot = tempC > hotIn;
        String cur = degrade;
        String want;
        if (cur.length() == 0) {
            want = hot ? "thermal" : (lowBat ? "battery" : "");
        } else {
            // 退出条件：电量回到 (进入线 + 10)% 以上、温度降到 (进入线 - 4)℃ 以下（未知的项算「没问题」）。
            boolean batOk = (level < 0) || level > (lowIn + LOW_PCT_OUT_DELTA);
            boolean tempOk = (tempC <= 0f) || tempC < (hotIn - HOT_C_OUT_DELTA);
            if (batOk && tempOk) want = "";
            else if (hot) want = "thermal";
            else if (lowBat) want = "battery";
            else want = cur; // 处在回差带里，维持现状
        }
        if (want.equals(cur)) return;
        degrade = want;
        since = want.length() == 0 ? 0L : StateIO.now();
        if (s != null) {
            try {
                Thread th = new Thread(new DegradeTask(s, want, level, tempC));
                th.setDaemon(true);
                th.start();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 上次进程退出时如果还在降载，把标记恢复回来，免得界面上显示「未降载」。 */
    private static void readExisting(File filesDir) {
        File f = StateIO.file(filesDir, NAME);
        if (!f.isFile()) return;
        String s = StateIO.read(f);
        if (s == null) return;
        String t = s.trim();
        if (!"battery".equals(t) && !"thermal".equals(t)) return;
        degrade = t;
        long m = f.lastModified() / 1000L;
        since = m > 0 ? m : StateIO.now();
    }

    /* ---------- 给桥 / 通知用的快照 ---------- */

    public static String degradeLabel() { return degrade; }

    public static int batteryLevel() { return level; }

    public static float tempC() { return tempC; }

    /** 通知文案用：没降载返回空串。 */
    static String noteText() {
        String d = degrade;
        if (d.length() == 0) return "";
        if ("thermal".equals(d)) return "温度过高，已降载";
        return "电量过低，已降载";
    }

    /** 桥方法 powerInfo()。 */
    public static String powerInfo() {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("level", level);
            // JSON 里给一位小数的摄氏度；设备不报温度时给 0，前端显示「—」
            o.put("tempC", Math.round(tempC * 10f) / 10.0);
            o.put("degrade", degrade);
            o.put("since", since);
            return o.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"err\":\"读取电量状态失败\"}";
        }
    }
}
