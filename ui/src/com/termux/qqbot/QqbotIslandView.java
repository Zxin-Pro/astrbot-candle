package com.termux.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 灵动岛（悬浮胶囊）：贴在其他应用上层的一条小胶囊，一眼能看到「运行中 / 启动中 / 离线 / 待扫码」。
 *
 * 为什么用悬浮窗而不是只发通知：用户的原话是「让软件一直保持前台运行」——通知栏在
 * 各个 ROM 上都可能被折叠、被折叠进「通知历史」、被下拉一次就划掉；悬浮窗是画在屏幕上的，
 * App 退到后台、锁屏解锁后都还在，这才真的叫「一直看得见」。
 *
 * 承载进程：由 QqbotGuardService（常驻前台服务）在 onCreate/onDestroy 里挂上/摘掉，
 * 服务不在就没人画——这也顺带保证「用户关掉守护，胶囊跟着消失」，不会留下一个点不开的幽灵。
 *
 * 权限：SYSTEM_ALERT_WINDOW（设置里叫「显示在其他应用的上层」）。没授权时只记偏好、
 * 不弹窗、不崩——Android 6 起这个权限没有运行时申请入口，只能把用户带到设置页，
 * 由界面的引导按钮（window.AndroidIsland.request）来做。
 *
 * 交互：按住拖动，松手贴到左/右边缘；轻点一下回到主界面。触摸事件都自己吃掉
 * （FLAG_NOT_FOCUSABLE + FLAG_NOT_TOUCH_MODAL），不会挡住底下的应用。
 *
 * d8 处理匿名内部类会崩，本文件不出现匿名类；窗口操作全部回主线程。
 */
public final class QqbotIslandView {

    /** 与 QqbotNotifyBridge 共用一份偏好（通知开关、灵动岛开关都在这里）。 */
    public static final String PREF = "qqbot_notify";
    /** 灵动岛开关：默认关，用户明确打开才挂窗口。 */
    public static final String KEY_ON = "island.on";

    private static final String TAG = "QqbotIsland";

    /* 尺寸（单位 dp）：高 32 = 圆角 16 的两倍，正好是标准的胶囊比例。 */
    private static final int H_DP = 32;
    private static final int MIN_W_DP = 90;
    private static final int MAX_W_DP = 110;
    /** 吸附到屏幕边缘时留的缝，贴着边框会顶到系统手势区。 */
    private static final int EDGE_DP = 10;
    /** 初始位置：左上，避开状态栏和刘海。 */
    private static final int START_X_DP = 24;
    private static final int START_Y_DP = 64;
    /** 手指位移超过这个数才算「拖动」，否则抬手按「点击」处理。 */
    private static final int TOUCH_SLOP_DP = 6;

    private static final Handler UI = new Handler(Looper.getMainLooper());

    /* 窗口三件套：root 视图、布局参数、窗口管理器。只有主线程读写。 */
    private static WindowManager wm;
    private static WindowManager.LayoutParams lp;
    private static View root;
    private static View dot;
    private static TextView label;

    /** 当前状态码：run / boot / down / qr。JS 线程也会读，所以是 volatile。 */
    private static volatile String code = "down";

    private QqbotIslandView() {
    }

    /* ================================================================
     * 纯逻辑：不碰 Android 类，能抠出来在普通 JVM 上跑回归
     * （见 out/b3_island_check.py）。改这里必须同步改测试。
     * ================================================================ */

    // ==== ISLAND-PURE-BEGIN ====

    /** 状态码 → 胶囊文案（都控制在 2~4 个字，宽度才塞得进 110dp）。 */
    static String labelOf(String code) {
        if ("run".equals(code)) return "运行中";
        if ("boot".equals(code)) return "启动中";
        if ("qr".equals(code)) return "待扫码";
        return "离线";
    }

    /** Android 16 的「灵动岛」短文案只有两档：在跑 / 没在跑。 */
    static String shortCritical(String code) {
        return "run".equals(code) ? "运行中" : "离线";
    }

    /** 通知标题：运行中时正好是「AstrBot 运行中」，其余状态跟着换，免得标题和正文打架。 */
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

    /** 胶囊宽度（dp）：38dp 是左右内边距 + 状态点 + 点与字之间的缝，文字按字宽累加后夹在 90~110。 */
    static int widthDp(String label) {
        int w = 38 + textUnits(label);
        if (w < MIN_W_DP) w = MIN_W_DP;
        if (w > MAX_W_DP) w = MAX_W_DP;
        return w;
    }

    /** 文字占宽：汉字/全角按 15，ASCII 按 8（12.5sp 下的近似值，宁可留白也别截字）。 */
    static int textUnits(String s) {
        if (s == null) return 0;
        int u = 0;
        for (int i = 0; i < s.length(); i++) {
            u += (s.charAt(i) < 0x80) ? 8 : 15;
        }
        return u;
    }

    /** 这台设备能不能挂悬浮窗（与「用户开没开」「授权没授权」无关）。 */
    static boolean canHost(int sdkInt, boolean wmOk) {
        return wmOk && sdkInt >= 26;
    }

    /** 最终要不要把胶囊画出来：能力 + 授权 + 用户开关，三个都得真。 */
    static boolean shouldShow(boolean enabled, boolean granted, int sdkInt) {
        return enabled && granted && sdkInt >= 26;
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

    /** 状态点颜色：绿=运行中、琥珀=启动中、蓝=待扫码、红=离线。 */
    static int dotColor(String code) {
        if ("run".equals(code)) return 0xFF37D67A;
        if ("boot".equals(code)) return 0xFFF2B84B;
        if ("qr".equals(code)) return 0xFF4C9AFF;
        return 0xFFFF5C5C;
    }

    // ==== ISLAND-PURE-END ====

    /* ================================================================
     * 偏好与对外状态（桥和界面都从这里读）
     * ================================================================ */

    /** 用户开关（默认关）。 */
    public static boolean isEnabled(Context c) {
        try {
            SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            return sp.getBoolean(KEY_ON, false);
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

    /** 悬浮窗权限：Android 6 起没有运行时申请入口，只能读设置项。 */
    public static boolean granted(Context c) {
        if (Build.VERSION.SDK_INT < 23) return true;
        try {
            return Settings.canDrawOverlays(c);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 能力检测：SDK 够不够（TYPE_APPLICATION_OVERLAY 要 26）+ 有没有 WindowManager。 */
    public static boolean canHostNow(Context c) {
        boolean wmOk = false;
        try {
            wmOk = c.getSystemService(Context.WINDOW_SERVICE) != null;
        } catch (Throwable ignored) {
        }
        return canHost(Build.VERSION.SDK_INT, wmOk);
    }

    /** 现在该不该有胶囊：能力 + 授权 + 开关。 */
    public static boolean availableNow(Context c) {
        return shouldShow(isEnabled(c), granted(c), Build.VERSION.SDK_INT) && canHostNow(c);
    }

    /** 当前状态码（桥的 info() 用；还没探测过就是 down）。 */
    public static String currentCode() {
        return code;
    }

    /** 当前状态文案。 */
    public static String currentLabel() {
        return labelOf(code);
    }

    /* ================================================================
     * 挂上 / 摘掉 / 更新（全部回主线程）
     * ================================================================ */

    /** 服务起来时调一次：满足条件就挂上，不满足就（确保）摘掉。 */
    public static void sync(Context c) {
        try {
            final Context app = c.getApplicationContext();
            UI.post(new SyncTask(app == null ? c : app));
        } catch (Throwable t) {
            Log.w(TAG, "sync 失败：" + t);
        }
    }

    /** 更新状态文案与颜色（状态变了才刷，45 秒一轮不会闪）。 */
    public static void update(Context c, String st) {
        code = (st == null) ? "down" : st;
        try {
            UI.post(new PaintTask());
        } catch (Throwable ignored) {
        }
    }

    /** 服务停了就摘掉，不留僵尸窗口。可以从任意线程调。 */
    public static void hide() {
        try {
            UI.post(new DetachTask());
        } catch (Throwable ignored) {
        }
    }

    private static void attach(Context c) {
        if (root != null) return;
        if (!availableNow(c)) return;
        try {
            WindowManager w = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            if (w == null) return;
            View v = build(c);
            int type = (Build.VERSION.SDK_INT >= 26)
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                    dp(widthDp(labelOf(code))), dp(H_DP), type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            p.gravity = Gravity.TOP | Gravity.START;
            p.x = dp(START_X_DP);
            p.y = dp(START_Y_DP);
            p.setTitle("QqbotIsland");
            w.addView(v, p);
            v.setOnTouchListener(new Drag());
            wm = w;
            lp = p;
            root = v;
        } catch (Throwable t) {
            // SecurityException（权限被撤）/ BadTokenException（窗口类型不对）都在这里，
            // 胶囊挂不上不影响守护服务本身，记一条日志就走。
            Log.w(TAG, "挂不上悬浮胶囊：" + t);
            wm = null;
            lp = null;
            root = null;
        }
    }

    private static void detach() {
        try {
            if (root != null && wm != null) wm.removeView(root);
        } catch (Throwable ignored) {
        }
        root = null;
        lp = null;
        wm = null;
        dot = null;
        label = null;
    }

    /** 组装胶囊：深色半透明圆角底 + 状态点 + 一行字。全部代码构建，不依赖布局文件。 */
    private static View build(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), 0, dp(12), 0);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(16));
        bg.setColor(0xE61B1F24);
        bg.setStroke(dp(1), 0x33FFFFFF);
        row.setBackground(bg);
        try {
            row.setElevation(dp(6));
        } catch (Throwable ignored) {
        }

        dot = new View(c);
        dot.setBackground(dotDrawable(dotColor(code)));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dlp.rightMargin = dp(6);
        row.addView(dot, dlp);

        label = new TextView(c);
        label.setText(labelOf(code));
        label.setTextColor(0xFFEDF1F7);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setIncludeFontPadding(false);
        row.addView(label);
        return row;
    }

    private static GradientDrawable dotDrawable(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    /** 状态变了：换色、换字、按新字宽重算胶囊宽度。 */
    private static void paint() {
        if (root == null) return;
        String text = labelOf(code);
        try {
            if (dot != null) dot.setBackground(dotDrawable(dotColor(code)));
            if (label != null) label.setText(text);
            if (lp != null && wm != null) {
                int w = dp(widthDp(text));
                if (w != lp.width) {
                    lp.width = w;
                    wm.updateViewLayout(root, lp);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void openApp(Context c) {
        try {
            Intent i = new Intent(c, QqbotActivity.class);
            // REORDER_TO_FRONT：应用已经在最近任务里就把它挪到最前，而不是再开一个实例。
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            c.startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "点胶囊回应用失败：" + t);
        }
    }

    /* ---------------- 拖动与吸附 ---------------- */

    private static void snap(View v) {
        if (lp == null || wm == null) return;
        int sw = screenW();
        int margin = dp(EDGE_DP);
        int target = (lp.x + lp.width / 2 < sw / 2) ? margin : (sw - lp.width - margin);
        if (target < 0) target = 0;
        smoothX(v, lp.x, target);
    }

    /** 贴边做一个 180ms 的减速动画：直接跳过去会显得「闪一下」，拖到位才自然。 */
    private static void smoothX(View v, int from, int to) {
        if (from == to) return;
        try {
            android.animation.ValueAnimator a =
                    android.animation.ValueAnimator.ofInt(from, to);
            a.setDuration(180L);
            a.setInterpolator(new android.view.animation.DecelerateInterpolator());
            a.addUpdateListener(new SnapMove());
            a.start();
        } catch (Throwable t) {
            if (lp != null && wm != null) {
                lp.x = to;
                try {
                    wm.updateViewLayout(v, lp);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static int clampX(int x, int w) {
        int sw = screenW();
        if (x < 0) return 0;
        if (x + w > sw) return Math.max(0, sw - w);
        return x;
    }

    private static int clampY(int y, int h) {
        int sh = screenH();
        if (y < 0) return 0;
        if (y + h > sh) return Math.max(0, sh - h);
        return y;
    }

    private static int screenW() {
        try {
            return Resources.getSystem().getDisplayMetrics().widthPixels;
        } catch (Throwable t) {
            return 1080;
        }
    }

    private static int screenH() {
        try {
            return Resources.getSystem().getDisplayMetrics().heightPixels;
        } catch (Throwable t) {
            return 1920;
        }
    }

    private static int dp(float v) {
        try {
            return Math.round(v * Resources.getSystem().getDisplayMetrics().density);
        } catch (Throwable t) {
            return Math.round(v * 3f);
        }
    }

    /* ---------------- 具名内部类（d8 不吃匿名类） ---------------- */

    private static final class SyncTask implements Runnable {
        private final Context ctx;

        SyncTask(Context c) {
            ctx = c;
        }

        public void run() {
            if (availableNow(ctx)) attach(ctx);
            else detach();
        }
    }

    private static final class PaintTask implements Runnable {
        public void run() {
            paint();
        }
    }

    private static final class DetachTask implements Runnable {
        public void run() {
            detach();
        }
    }

    private static final class SnapMove
            implements android.animation.ValueAnimator.AnimatorUpdateListener {
        public void onAnimationUpdate(android.animation.ValueAnimator a) {
            if (lp == null || root == null || wm == null) return;
            Object v = a.getAnimatedValue();
            if (!(v instanceof Integer)) return;
            lp.x = ((Integer) v).intValue();
            try {
                wm.updateViewLayout(root, lp);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 按下记起点、移动跟手、抬手分「点击」和「吸附」两种结局。 */
    private static final class Drag implements View.OnTouchListener {
        private int downRawX;
        private int downRawY;
        private int startX;
        private int startY;
        private long downMs;
        private boolean moved;

        public boolean onTouch(View v, MotionEvent e) {
            if (lp == null || wm == null) return false;
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                downRawX = (int) e.getRawX();
                downRawY = (int) e.getRawY();
                startX = lp.x;
                startY = lp.y;
                downMs = System.currentTimeMillis();
                moved = false;
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                int dx = (int) e.getRawX() - downRawX;
                int dy = (int) e.getRawY() - downRawY;
                int slop = dp(TOUCH_SLOP_DP);
                if (!moved && (Math.abs(dx) > slop || Math.abs(dy) > slop)) moved = true;
                if (!moved) return true;
                lp.x = clampX(startX + dx, lp.width);
                lp.y = clampY(startY + dy, lp.height);
                try {
                    wm.updateViewLayout(v, lp);
                } catch (Throwable ignored) {
                }
                return true;
            }
            if (action == MotionEvent.ACTION_UP) {
                if (!moved && System.currentTimeMillis() - downMs < 450L) {
                    openApp(v.getContext());
                    return true;
                }
                snap(v);
                return true;
            }
            if (action == MotionEvent.ACTION_CANCEL) {
                snap(v);
                return true;
            }
            return false;
        }
    }
}
