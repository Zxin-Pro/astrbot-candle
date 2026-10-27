package com.termux.app;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Build;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * window.AndroidIsland —— 灵动岛 v4 的 JS 桥（v1.3.4-beta.7）。
 *
 * 用户要的是「安卓原生的灵动岛」：贴挖孔的胶囊、有事件自己弹开、展开像音乐播放器。
 * 绘制那层全在 QqbotIslandCapsule 里，本类只做四件事，一律不自己建窗
 * （窗口归 QqbotGuardService 持有，服务没了窗也必须没）：
 *   ① 告诉界面：这台设备能不能建胶囊（canPromote）、授权了没有（granted）、开关开着没；
 *   ② 开关（set）：写偏好，然后让服务那边同步建/拆胶囊 + 刷新通知兜底；
 *   ③ 带用户去系统设置（openSettings）：优先「显示在其他应用上层」，已授权再去通知设置；
 *   ④ 演示（test）：在摄像头位置挂 5 秒胶囊，让用户点一下就知道长什么样。
 *
 * beta.7 只新增了一个可选方法 notice(json)：界面想「通知上岛」时（部署进度这种只有
 * 前端才知道的事件）可以推一条带标题/副标题/进度的岛上事件。老方法名与返回结构一个没动，
 * 旧界面不调它照样正常工作。
 *
 * 纯逻辑集中在本文件 ISLAND-PURE 标记块里：不碰任何 Android 类，
 * 能被外部回归脚本抠出来在普通 JVM 上跑。改这里必须同步改测试。
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
    /** 演示胶囊活多久（毫秒）：够用户看清楚，又不至于一直挡在摄像头那儿。 */
    private static final long TEST_TTL_MS = 5000L;

    /** 岛消息最短/最长停留：太短看不清，太长像卡住了。 */
    private static final long NOTICE_MIN_MS = 800L;
    private static final long NOTICE_MAX_MS = 10000L;

    private final Activity act;

    public QqbotIslandBridge(Activity act) {
        this.act = act;
    }

    /* ================================================================
     * 纯逻辑：不碰 Android 类，能抠出来在普通 JVM 上跑回归
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
     *   off      —— 用户把开关关了，胶囊和通知都不发；
     *   capsule  —— 胶囊正挂在摄像头位置（真·灵动岛在跑，这是目标状态）；
     *   promoted —— 只能退化成通知兜底（没授权 / 建不了窗 / 服务没跑起来）。
     * 名词沿用契约：promoted 指「通知那条路」，不再特指 Android 16 的芯片。
     */
    static String modeOf(boolean enabled, boolean capsuleShown) {
        if (!enabled) return "off";
        return capsuleShown ? "capsule" : "promoted";
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
            if (c == null) return false;
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
            if (c == null) return;
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
            if (c == null) return false;
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
            if (c == null) return false;
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
            if (act == null) return -1;
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
     * JS 桥方法（契约 §3.1，方法名与返回结构一个都不能改）
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

    /** 「显示在其他应用上层」权限：胶囊就靠它活着。旧界面叫它 granted。 */
    @JavascriptInterface
    public String granted() {
        try {
            return QqbotIslandCapsule.granted(act) ? "true" : "false";
        } catch (Throwable t) {
            return "false";
        }
    }

    /**
     * 现在能不能建胶囊（已授权 + SDK 够 + WindowManager 拿得到）。
     * 方法名沿用契约里的 canPromote，语义已经不是「能不能发实时更新通知」了。
     */
    @JavascriptInterface
    public String canPromote() {
        try {
            return QqbotIslandCapsule.canHostNow(act) ? "true" : "false";
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
                toast("灵动岛开关参数不认识：" + on);
                return;
            }
            if (act == null) return;
            act.runOnUiThread(new UiSet(v.booleanValue()));
        } catch (Throwable t) {
            // 吞掉：桥方法抛异常会在 JS 侧变成一句看不懂的报错
        }
    }

    /**
     * 界面渲染用的快照（契约 §3.1）：
     * {"available":true,"canPromote":true,"sdk":34,"enabled":true,
     *  "code":"run","text":"运行中","battery":80,"mode":"capsule"}
     * 额外给 granted（悬浮窗权限）与 shown（胶囊此刻是否在屏上），
     * whitelist 是省电白名单有没有开（只读，不弹窗）。
     */
    @JavascriptInterface
    public String info() {
        JSONObject o = new JSONObject();
        try {
            boolean en = isEnabled(act);
            boolean host = QqbotIslandCapsule.canHostNow(act);
            boolean shown = QqbotIslandCapsule.isShown();
            o.put("available", notifierOk(act) || host);
            o.put("canPromote", host);
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("enabled", en);
            o.put("code", code);
            o.put("text", labelOf(code));
            o.put("battery", batteryPct());
            o.put("mode", modeOf(en, shown));
            o.put("granted", QqbotIslandCapsule.granted(act));
            o.put("shown", shown);
            // 通知兜底那条路：36+ 且用户允许才会被系统提升成状态栏芯片（不影响胶囊）
            o.put("promoted", canPromoteNow(act));
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
     * 跳到该去的那一页：没授权就先去「显示在其他应用上层」（胶囊靠它），
     * 已经授权了才知道用户想改什么——直接给通知设置（36+ 是实时更新开关页）。
     */
    @JavascriptInterface
    public void openSettings() {
        try {
            if (act == null) return;
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

    /** 演示：在摄像头位置挂 5 秒胶囊（没授权就先带用户去授权页）。 */
    @JavascriptInterface
    public void test() {
        try {
            if (act == null) return;
            act.runOnUiThread(new UiDemo());
        } catch (Throwable t) {
            // 同上，投递失败就算了
        }
    }

    /**
     * 可选新方法：让界面对灵动岛说一句话（部署进度、二维码刷新这类「只有前端知道」的事件）。
     * 入参：{"title":"部署中","sub":"3/9 安装依赖","ms":3000,"p":0.33}
     *   title / sub 可省（省了用当前状态文案）；ms 缺省 3 秒（夹在 0.8~10 秒）；
     *   p 是 0~1 的进度，缺省或越界就退回「按停留时间倒计时」。
     * 返回 {"ok":true} 或 {"ok":false,"err":"中文原因"}。旧界面不调它完全不受影响。
     */
    @JavascriptInterface
    public String notice(String json) {
        try {
            if (json == null || json.trim().length() == 0) {
                return "{\"ok\":false,\"err\":\"没有内容\"}";
            }
            JSONObject o = new JSONObject(json);
            String title = o.optString("title", "");
            String sub = o.optString("sub", "");
            long ms = o.optLong("ms", 3000L);
            if (ms < NOTICE_MIN_MS) ms = NOTICE_MIN_MS;
            if (ms > NOTICE_MAX_MS) ms = NOTICE_MAX_MS;
            double p = o.optDouble("p", -1d);
            float frac = (p < 0d || p > 1d || Double.isNaN(p)) ? -1f : (float) p;
            QqbotIslandCapsule.notice(title, sub, ms, frac);
            return "{\"ok\":true}";
        } catch (org.json.JSONException e) {
            return "{\"ok\":false,\"err\":\"岛消息不是合法的 JSON\"}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"err\":\"岛消息发不出去\"}";
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
            if (act == null) return;
            act.runOnUiThread(new UiToast(act, msg));
        } catch (Throwable ignored) {
        }
    }

    /** 真正干活的：写偏好 → 让服务立刻建/拆胶囊，并重发或撤销通知兜底。 */
    private void applyOn(boolean on) {
        try {
            if (act == null) return;
            setEnabled(act, on);
            // 胶囊与通知是两条路，两边都要同步：胶囊建不起来时，通知至少还在说状态
            QqbotIslandCapsule.sync(act);
            // 服务的「最近状态键」要清掉，否则 postIsland 会认为「没变过」而跳过这次重发
            QqbotGuardService.islandRepost(act);
            if (!on) {
                toast("灵动岛已关闭");
                return;
            }
            if (!QqbotIslandCapsule.granted(act)) {
                toast("已打开：还需要允许「显示在其他应用上层」才会出现胶囊");
                return;
            }
            if (!QqbotIslandCapsule.canHostNow(act)) {
                toast("已打开：这台设备建不了悬浮窗（需要 Android 6 以上）");
                return;
            }
            if (!QqbotGuardService.guardRunning) {
                // 胶囊的载体是前台服务：服务没跑就没人驱动状态，不自动去拉沙盒（那是另一个动作）
                toast("已打开：守护服务运行时会显示胶囊");
                return;
            }
            toast("已打开：胶囊会出现在摄像头位置");
        } catch (Throwable t) {
            toast("设置灵动岛失败：" + t);
        }
    }

    /** 「显示在其他应用上层」的设置页（带 package: 直接落到本应用那一行）。 */
    private Intent overlayIntent() {
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
        i.setData(android.net.Uri.parse("package:" + act.getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    /** 先去要权限，已经给了才轮到通知设置那一页。 */
    private void gotoPermissionSettings() {
        if (act == null) return;
        String pkg = act.getPackageName();
        if (!QqbotIslandCapsule.granted(act)) {
            try {
                act.startActivity(overlayIntent());
                return;
            } catch (Throwable ignored) {
                // 少数 ROM 没有这一页：退回应用详情页，权限在那里也能找到
                try {
                    Intent d = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    d.setData(android.net.Uri.parse("package:" + pkg));
                    d.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    act.startActivity(d);
                    return;
                } catch (Throwable ignored2) {
                    toast("这个系统没有权限设置页，请手动到设置里找「显示在其他应用上层」");
                    return;
                }
            }
        }
        gotoNotificationSettings();
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
        if (act == null) return;
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
            gotoPermissionSettings();
        }
    }

    /** 演示胶囊：没授权先把用户送到授权页，省得他点了没反应以为坏了。 */
    private final class UiDemo implements Runnable {
        public void run() {
            try {
                if (act == null) return;
                if (!QqbotIslandCapsule.canHostNow(act)) {
                    toast("先允许「显示在其他应用上层」，胶囊才能显示");
                    try {
                        act.startActivity(overlayIntent());
                    } catch (Throwable ignored) {
                    }
                    return;
                }
                QqbotIslandCapsule.demo(act, TEST_TTL_MS);
                toast("胶囊会在摄像头位置出现 5 秒");
            } catch (Throwable t) {
                toast("演示胶囊失败：" + t);
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
