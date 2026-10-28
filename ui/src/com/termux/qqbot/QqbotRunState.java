package com.termux.app;

/**
 * 运行状态（v1.3.4-beta.9 起）：前台常驻通知与应用界面共用的一份「现在是什么状态」。
 *
 * 为什么从 QqbotIslandBridge 里搬出来：那一版（beta.4~beta.8）这套纯逻辑挂在灵动岛桥上，
 * 桥没了两样东西就都散了。运行状态本身跟「怎么显示」无关——通知栏用它写标题，
 * 界面用它画那几行状态，所以它应该是一个不依赖 Android 的纯工具类，谁要谁读。
 *
 * 这里一个 Android 类都不 import：整块逻辑能抠出来在普通 JVM 上跑回归
 * （见 out/b139_fgs_check.py 的 bootPctOf / durText 样本）。
 */
public final class QqbotRunState {

    /** 统一的话题口径：状态码只有这四个，别处一律不要自己拼字符串。 */
    public static final String RUN = "run";
    public static final String BOOT = "boot";
    public static final String QR = "qr";
    public static final String DOWN = "down";

    private QqbotRunState() {
    }

    /* ================================================================
     * 状态快照（服务线程写、界面线程读，所以是 volatile）
     * ================================================================ */

    /** 当前状态码：run / boot / qr / down。 */
    private static volatile String code = DOWN;

    /** 本次上线的起点（毫秒），0 = 没在跑。 */
    private static volatile long upSince = 0L;

    /** 服务在一轮状态检查里算完结论后调一次，只更新内存，不发通知。 */
    public static void note(String st, long since) {
        code = (st == null || st.length() == 0) ? DOWN : st;
        upSince = since;
    }

    public static String currentCode() {
        return code;
    }

    public static long currentUpSince() {
        return upSince;
    }

    /* ================================================================
     * 纯函数：文案与状态判定
     * ================================================================ */

    /** 状态码 → 一句话状态（通知标题、界面状态行都用它，保证一处口径）。 */
    public static String labelOf(String code) {
        if (RUN.equals(code)) return "运行中";
        if (BOOT.equals(code)) return "启动中";
        if (QR.equals(code)) return "待扫码";
        return "离线";
    }

    /** 通知标题：标题跟状态走，免得标题写「运行中」、正文却在讲掉线。 */
    public static String notiTitle(String code) {
        return "AstrBot " + labelOf(code);
    }

    /** 通知正文：一句话说清楚现在该干什么。 */
    public static String hintOf(String code) {
        if (RUN.equals(code)) return "服务在后台常驻，消息通道正常";
        if (BOOT.equals(code)) return "沙盒正在启动，稍等一会儿";
        if (QR.equals(code)) return "QQ 登录失效了，点开应用扫一次码";
        return "和 QQ 的连接断了，正在自动恢复";
    }

    /**
     * 四种状态的判定。参数都是探测结果，顺序就是优先级：
     *   端口通了 → 运行中（哪怕沙盒刚启动完、bootstrap 标记还没落盘）；
     *   没通但沙盒还没部署好 → 启动中（冷启动时端口本来就该是关的，别报「离线」吓人）；
     *   没通且挂着待扫的码 → 待扫码（码会一直挂着，直到真的上线才清掉）；
     *   其余 → 离线。
     */
    public static String stateOf(boolean up, boolean booting, boolean qrPending) {
        if (up) return RUN;
        if (booting) return BOOT;
        if (qrPending) return QR;
        return DOWN;
    }

    /**
     * 「待扫码」要粘住：NapCat 出新码那一刻置位，之后一直显示，直到真的上线为止。
     * 只按「mtime 变了」显示会一闪就没了——用户还没反应过来二维码已经不在屏幕上了。
     */
    public static boolean qrKeep(boolean prev, boolean up, boolean qrEvent) {
        if (up) return false;
        if (qrEvent) return true;
        return prev;
    }

    /** 运行起点：上线那一刻记一次，掉线清零；服务重启后接着上次的算，时长不会从头再来。 */
    public static long upSinceOf(long prev, boolean up, long now) {
        if (!up) return 0L;
        return prev > 0L ? prev : now;
    }

    /** 运行时长文案：「刚刚 / 12 分钟 / 1 小时 3 分钟 / 2 天 4 小时」。 */
    public static String durText(long ms) {
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
}
