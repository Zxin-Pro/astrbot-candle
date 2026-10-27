package com.termux.app;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * window.AndroidIsland —— 「实时更新」（Android 16 Live Updates）的 JS 桥（v1.3.4-beta.4）。
 *
 * 这一版把 beta.3 的悬浮胶囊整份删掉了：那是个画在所有应用上层的悬浮窗，只有长按拖动、
 * 贴边吸附这类自己实现的手感，系统根本不认它是「灵动岛」，用户一眼就看出是假的。
 * 现在改成系统自己的形态——一条 IMPORTANCE_LOW 的常驻通知，在 Android 16 上由系统提升成
 * 状态栏芯片（短文案 + 实时进度），14/15 上就是一条安静的常驻通知。
 *
 * 所以本类不再建窗、不再要 SYSTEM_ALERT_WINDOW：桥只负责三件事——
 *   ① 告诉界面「这台设备能不能看到芯片」「用户有没有允许提升」「开关开着没」；
 *   ② 把用户带到系统设置页（36+ 是「实时更新」开关页，老系统是应用通知设置页）；
 *   ③ 发一条 5 秒就撤的演示通知，让用户点一下就知道长什么样。
 *
 * 真正发通知的是 QqbotGuardService（通知的生命周期必须等于前台服务的生命周期，
 * 否则服务被杀、通知还挂着，就成了一条点不开的僵尸）。
 *
 * 纯逻辑集中在本文件 ISLAND-PURE 标记块里：不碰任何 Android 类，
 * 能被 out/b4_island_check.py 抠出来在普通 JVM 上跑回归。改这里必须同步改测试。
 *
 * 旧包兼容：界面必须 typeof window.AndroidIsland !== "undefined" 才调它。
 * 本类所有方法自己再包一层 try/catch —— 桥方法抛异常在 JS 侧会变成一句看不懂的报错。
 */
public class QqbotIslandBridge {

    /** 与 QqbotNotifyBridge 共用一份偏好（qqbot_notify，键前缀 notify.）。 */
    public static final String PREF = "qqbot_notify";
    /** 开关键：契约里定的 notify.island。 */
    public static final String KEY_ON = "notify.island";
    /** beta.3 的旧键，只为从旧版本升级上来的用户迁移一次。 */
    public static final String KEY_ON_LEGACY = "island.on";

    /** 实时更新渠道与通知 ID（契约：qqbot_live / 42330）。 */
    public static final String CH_LIVE = "qqbot_live";
    public static final int LIVE_ID = 42330;
    /** 演示通知单独一个 ID：不能和真状态条抢同一个槽位，否则点一下「试一下」会把真状态顶掉。 */
    public static final int TEST_ID = 42331;
    /** 演示通知活多久（毫秒）。 */
    private static final long TEST_TTL_MS = 5000L;

    private final Activity act;

    public QqbotIslandBridge(Activity act) {
        this.act = act;
    }

    /* ================================================================
     * 纯逻辑：不碰 Android 类，能抠出来在普通 JVM 上跑回归
     * （见 out/b4_island_check.py）。改这里必须同步改测试。
     * ================================================================ */

    // ==== ISLAND-PURE-BEGIN ====

    /** 状态栏芯片的短文案上限：系统给的位置大约就 7 个字符，超了自己截断，我们提前夹住。 */
    public static final int SHORT_LIMIT = 7;

    /** 状态码 → 通知文案（界面卡片、标题、正文都用它，保证一处口径）。 */
    static String labelOf(String code) {
        if ("run".equals(code)) return "运行中";
        if ("boot".equals(code)) return "启动中";
        if ("qr".equals(code)) return "待扫码";
        return "离线";
    }

    /**
     * 状态栏芯片上的短文案（Android 16 的 setShortCriticalText）。
     * 四种状态各给一个 2~3 字的词：芯片上空间极窄，只写结论不写解释。
     */
    static String shortCritical(String code) {
        return clampShort(labelOf(code));
    }

    /** 硬夹到 SHORT_LIMIT 个字符：宁可截断，也不要被系统自己截到半个词。 */
    static String clampShort(String s) {
        if (s == null) return "离线";
        if (s.length() <= SHORT_LIMIT) return s;
        return s.substring(0, SHORT_LIMIT);
    }

    /** 通知标题：标题跟状态走，免得标题写「运行中」、正文却在讲掉线。 */
    static String notiTitle(String code) {
        return "AstrBot " + labelOf(code);
    }

    /** 通知正文：一句话说清楚现在该干什么。 */
    static String islandHint(String code) {
        if ("run".equals(code)) return "服务在后台常驻，消息通道正常";
        if ("boot".equals(code)) return "沙盒正在启动，稍等一会儿";
        if ("qr".equals(code)) return "QQ 登录失效了，点开应用扫一次码";
        return "和 QQ 的连接断了，正在自动恢复";
    }

    /**
     * 四种状态的判定。参数都是探测结果，顺序就是优先级：
     *   端口通了 → 运行中（哪怕沙盒刚启动完、bootstrap 标记还没落盘）；
     *   没通但沙盒还没部署好 → 启动中（冷启动时端口本来就该是关的，别报「离线」吓人）；
     *   没通且挂着待扫的码 → 待扫码（码会一直挂着，直到真的上线才清掉）；
     *   其余 → 离线。
     */
    static String stateOf(boolean up, boolean booting, boolean qrPending) {
        if (up) return "run";
        if (booting) return "boot";
        if (qrPending) return "qr";
        return "down";
    }

    /**
     * 「待扫码」要粘住：NapCat 出新码那一刻置位，之后一直显示，直到真的上线为止。
     * 只按「mtime 变了」显示会一闪就没了——用户还没反应过来二维码已经不在屏幕上了。
     */
    static boolean qrKeep(boolean prev, boolean up, boolean qrEvent) {
        if (up) return false;
        if (qrEvent) return true;
        return prev;
    }

    /** 运行起点：上线那一刻记一次，掉线清零；服务重启后接着上次的算，时长不会从头再来。 */
    static long upSinceOf(long prev, boolean up, long now) {
        if (!up) return 0L;
        return prev > 0L ? prev : now;
    }

    /** 运行时长文案：「刚刚 / 12 分钟 / 1 小时 3 分钟 / 2 天 4 小时」。 */
    static String durText(long ms) {
        if (ms < 60000L) return "刚刚";
        long m = ms / 60000L;
        long d = m / 1440L;
        m = m % 1440L;
        long h = m / 60L;
        m = m % 60L;
        StringBuilder b = new StringBuilder();
        if (d > 0) b.append(d).append(" 天 ");
        if (h > 0) b.append(h).append(" 小时 ");
        if (m > 0) b.append(m).append(" 分钟");
        return b.toString().trim();
    }

    /**
     * 这台设备能不能「被提升成芯片」：只有 Android 16（API 36）起才有这套机制，
     * 再叠加用户是否在系统设置里允许了本应用发实时更新。
     */
    static boolean promotes(int sdkInt, boolean allowedByUser) {
        return sdkInt >= 36 && allowedByUser;
    }

    /**
     * 三态口径（界面卡片直接照着显示）：
     *   off      —— 用户把开关关了，什么都不发；
     *   promoted —— 36+ 且系统允许，会出现状态栏芯片；
     *   plain    —— 开关开着但只能发普通常驻通知（老系统 / 用户关了实时更新 / ROM 不支持）。
     */
    static String modeOf(boolean enabled, boolean canPromote) {
        if (!enabled) return "off";
        return canPromote ? "promoted" : "plain";
    }

    /**
     * 开关的默认值：36+ 默认开（用户要的就是这个形态，系统会自己把它变成芯片），
     * 36 以下默认关（老系统上它就是一条普通通知，没理由默认给用户多塞一条）。
     */
    static boolean defaultOn(int sdkInt) {
        return sdkInt >= 36;
    }

    /**
     * 开关的最终取值：新键有值就认新键；只有旧键（beta.3 的 island.on）就迁移；
     * 两个都没有才用默认值。参数是「有没有 / 值多少」，所以是纯的。
     */
    static boolean resolveOn(boolean hasNew, boolean newVal,
            boolean hasLegacy, boolean legacyVal, int sdkInt) {
        if (hasNew) return newVal;
        if (hasLegacy) return legacyVal;
        return defaultOn(sdkInt);
    }

    // ==== ISLAND-PURE-END ====

    /* ================================================================
     * 开关偏好
     * ================================================================ */

    /** 开关当前值（含旧键迁移；读失败按默认值处理，绝不抛给 JS）。 */
    public static boolean isEnabled(Context c) {
        try {
            SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            return resolveOn(sp.contains(KEY_ON), sp.getBoolean(KEY_ON, false),
                    sp.contains(KEY_ON_LEGACY), sp.getBoolean(KEY_ON_LEGACY, false),
                    Build.VERSION.SDK_INT);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setEnabled(Context c, boolean on) {
        try {
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply();
        } catch (Throwable ignored) {
        }
    }

    /* ================================================================
     * 状态快照（服务每 45 秒算一次，写在这里给 info() 读）
     * ================================================================ */

    /** 当前状态码：run / boot / qr / down。服务线程写、JS 线程读，所以是 volatile。 */
    private static volatile String code = "down";
    /** 本次上线的起点（毫秒），0 = 没在跑。 */
    private static volatile long upSince = 0L;

    /** 服务在一轮状态检查里算完结论后调一次，只更新内存，不发通知。 */
    static void note(String st, long since) {
        code = (st == null || st.length() == 0) ? "down" : st;
        upSince = since;
    }

    public static String currentCode() {
        return code;
    }

    public static long currentUpSince() {
        return upSince;
    }

    /* ================================================================
     * 能力与权限（全部反射，编译期只认 android-34）
     * ================================================================ */

    /** 有没有通知服务：有就能发（Android 4.0+ 全都有）。 */
    private static boolean notifierOk(Context c) {
        try {
            return c.getSystemService(Context.NOTIFICATION_SERVICE) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 用户是否允许本应用发「实时更新」。
     * NotificationManager#canPostPromotedNotifications 是 API 36 才有的方法，
     * targetSdk 28 下编译期看不到，只能反射；任何失败一律按「不允许」处理。
     */
    public static boolean canPromoteNow(Context c) {
        if (Build.VERSION.SDK_INT < 36) return false;
        try {
            Object nm = c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            java.lang.reflect.Method m = NotificationManager.class
                    .getMethod("canPostPromotedNotifications");
            Object r = m.invoke(nm);
            return r instanceof Boolean && ((Boolean) r).booleanValue();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 电池电量百分比（0~100，-1 = 读不到）。优先用服务里已经采过的值，省一次广播。 */
    private int batteryPct() {
        try {
            int lv = BatteryMon.batteryLevel();
            if (lv >= 0) return lv;
        } catch (Throwable ignored) {
        }
        try {
            Intent i = act.registerReceiver(null,
                    new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return -1;
            int raw = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (raw < 0 || scale <= 0) return -1;
            return Math.round(raw * 100f / scale);
        } catch (Throwable t) {
            return -1;
        }
    }

    /* ================================================================
     * JS 桥方法（契约 §3.1）
     * ================================================================ */

    /** 这个版本能不能用实时更新：能发通知就算能用（36 以下只是不上芯片）。 */
    @JavascriptInterface
    public String available() {
        try {
            return notifierOk(act) ? "true" : "false";
        } catch (Throwable t) {
            return "false";
        }
    }

    /** 兼容 beta.3 的旧界面：那时候叫「有没有悬浮窗权限」，现在等同 canPromote()。 */
    @JavascriptInterface
    public String granted() {
        return canPromote();
    }

    /** 现在能不能真的上状态栏芯片（36+ 且用户允许）。 */
    @JavascriptInterface
    public String canPromote() {
        try {
            return canPromoteNow(act) ? "true" : "false";
        } catch (Throwable t) {
            return "false";
        }
    }

    /** 系统版本号，界面用它区分「芯片版」和「通知版」文案。 */
    @JavascriptInterface
    public String sdk() {
        try {
            return String.valueOf(Build.VERSION.SDK_INT);
        } catch (Throwable t) {
            return "0";
        }
    }

    /** 开关当前值。 */
    @JavascriptInterface
    public String enabled() {
        try {
            return isEnabled(act) ? "true" : "false";
        } catch (Throwable t) {
            return "false";
        }
    }

    /**
     * 开关。只写偏好 + 让服务立刻重发/撤销那条通知，不动沙盒、不拉服务、不建窗。
     * 服务没在跑的时候开关照样存下来：下次服务起来会按新值发。
     */
    @JavascriptInterface
    public void set(String on) {
        try {
            Boolean v = parseOn(on);
            if (v == null) {
                toast("实时更新开关参数不认识：" + on);
                return;
            }
            act.runOnUiThread(new UiSet(v.booleanValue()));
        } catch (Throwable t) {
            // 吞掉：桥方法抛异常会在 JS 侧变成一句看不懂的报错
        }
    }

    /**
     * 界面渲染用的快照（契约 §3.1）：
     * {"available":true,"canPromote":false,"sdk":36,"enabled":true,
     *  "code":"run","text":"运行中","battery":80,"mode":"promoted"}
     * 额外给 granted（= canPromote，兼容旧界面）与 whitelist（省电白名单有没有开）。
     */
    @JavascriptInterface
    public String info() {
        JSONObject o = new JSONObject();
        try {
            boolean en = isEnabled(act);
            boolean cp = canPromoteNow(act);
            o.put("available", notifierOk(act));
            o.put("canPromote", cp);
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("enabled", en);
            o.put("code", code);
            o.put("text", labelOf(code));
            o.put("battery", batteryPct());
            o.put("mode", modeOf(en, cp));
            // 兼容与补充：旧界面读 granted；whitelist 是省电白名单（只读，不弹窗）
            o.put("granted", cp);
            o.put("whitelist", QqbotGuardService.batteryOk(act));
        } catch (Throwable t) {
            try {
                return "{\"available\":false,\"canPromote\":false,\"sdk\":0,\"enabled\":false,"
                        + "\"code\":\"down\",\"text\":\"离线\",\"battery\":-1,\"mode\":\"off\"}";
            } catch (Throwable ignored) {
                return "{}";
            }
        }
        return o.toString();
    }

    /**
     * 跳到系统的「实时更新」开关页（36+）；老系统只能跳应用通知设置页。
     * 用户在这里能单独关掉本应用的实时更新，而不必把整条通知关掉。
     */
    @JavascriptInterface
    public void openSettings() {
        try {
            act.runOnUiThread(new UiOpen());
        } catch (Throwable t) {
            // Activity 已经没了，不再处理
        }
    }

    /** beta.3 的方法名，保留成别名，免得旧界面调用时报「方法不存在」。 */
    @JavascriptInterface
    public void request() {
        openSettings();
    }

    /** 发一条 5 秒后自动撤掉的演示通知：用户点一下就知道「实时更新」长什么样。 */
    @JavascriptInterface
    public void test() {
        try {
            act.runOnUiThread(new UiTest(act));
        } catch (Throwable t) {
            // 同上，投递失败就算了
        }
    }

    /* ---------------- 内部 ---------------- */

    /**
     * 宽松解析开关值：true/1/on/yes/开 都算开，false/0/off/no/关 都算关。
     * 认不出来的返回 null——宁可提示一句，也别把「没听懂」当成「关掉」。
     */
    static Boolean parseOn(String s) {
        if (s == null) return null;
        String v = s.trim().toLowerCase();
        if (v.length() == 0) return null;
        if ("true".equals(v) || "1".equals(v) || "on".equals(v) || "yes".equals(v) || "开".equals(v)) {
            return Boolean.TRUE;
        }
        if ("false".equals(v) || "0".equals(v) || "off".equals(v) || "no".equals(v) || "关".equals(v)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private void toast(String msg) {
        try {
            act.runOnUiThread(new UiToast(act, msg));
        } catch (Throwable ignored) {
        }
    }

    /** 真正干活的：写偏好 → 让服务立刻按新值重发或撤销。 */
    private void applyOn(boolean on) {
        try {
            setEnabled(act, on);
            // 服务的「最近状态键」要清掉，否则 postIsland 会认为「没变过」而跳过这次重发
            QqbotGuardService.islandRepost(act);
            if (!on) {
                toast("实时更新已关闭");
                return;
            }
            if (!QqbotGuardService.guardRunning) {
                // 通知的载体是前台服务：服务没跑就没有状态可发，不自动去拉沙盒（那是另一个动作）
                toast("已打开：守护服务运行时会显示运行状态");
                return;
            }
            if (canPromoteNow(act)) {
                toast("已打开：Android 16 会把它显示在状态栏上");
            } else if (Build.VERSION.SDK_INT >= 36) {
                toast("已打开：还没允许实时更新，可以去设置里开");
            } else {
                toast("已打开：这台设备只显示为通知栏常驻条");
            }
        } catch (Throwable t) {
            toast("设置实时更新失败：" + t);
        }
    }

    /** 36+ 找系统那个「实时更新」开关页的 action 名（编译期看不到，只能反射读常量）。 */
    private static String promotionAction() {
        try {
            Object v = Settings.class.getField("ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS").get(null);
            if (v instanceof String) return (String) v;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void gotoNotificationSettings() {
        String pkg = act.getPackageName();
        if (Build.VERSION.SDK_INT >= 36) {
            String act36 = promotionAction();
            if (act36 != null) {
                try {
                    Intent i = new Intent(act36);
                    i.setData(android.net.Uri.parse("package:" + pkg));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    act.startActivity(i);
                    return;
                } catch (Throwable ignored) {
                    // 落到下面的应用通知设置页
                }
            }
        }
        try {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, pkg);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Throwable t) {
            // 少数 ROM 连这个也改过名：退回本应用的详情页
            try {
                Intent i2 = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                i2.setData(android.net.Uri.parse("package:" + pkg));
                i2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(i2);
            } catch (Throwable t2) {
                toast("这个系统没有通知设置页，请手动到设置里找本应用的通知");
            }
        }
    }

    /* ---------------- 具名内部类（d8 不吃匿名类） ---------------- */

    private final class UiSet implements Runnable {
        private final boolean on;

        UiSet(boolean on) {
            this.on = on;
        }

        public void run() {
            applyOn(on);
        }
    }

    private final class UiOpen implements Runnable {
        public void run() {
            gotoNotificationSettings();
        }
    }

    private static final class UiTest implements Runnable {
        private final Context ctx;

        UiTest(Context c) {
            ctx = c;
        }

        public void run() {
            try {
                Notification n = QqbotGuardService.buildIslandNoti(ctx, "run",
                        System.currentTimeMillis());
                NotificationManager nm = (NotificationManager)
                        ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm == null) return;
                nm.notify(TEST_ID, n);
                // 5 秒后自己撤掉：演示通知不该在通知栏里长期占着位置
                new Handler(Looper.getMainLooper()).postDelayed(new TestCancel(ctx), TEST_TTL_MS);
            } catch (Throwable t) {
                try {
                    Toast.makeText(ctx, "演示通知发不出来：" + t, Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static final class TestCancel implements Runnable {
        private final Context ctx;

        TestCancel(Context c) {
            ctx = c;
        }

        public void run() {
            try {
                NotificationManager nm = (NotificationManager)
                        ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.cancel(TEST_ID);
            } catch (Throwable ignored) {
            }
        }
    }

    private static final class UiToast implements Runnable {
        private final Context ctx;
        private final String msg;

        UiToast(Context c, String m) {
            ctx = c;
            msg = m;
        }

        public void run() {
            try {
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        }
    }
}
