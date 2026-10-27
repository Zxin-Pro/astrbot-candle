package com.termux.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * 摄像头挖孔位置的系统级胶囊（v1.3.4-beta.5）。
 *
 * 用户说的是「系统级交互，在摄像头那边的那种」——iPhone 灵动岛、HyperOS 焦点通知胶囊那种：
 * 一条贴着前置摄像头挖孔的黑色圆角胶囊，画在所有应用上层，随状态变化换圆点颜色和短文案。
 *
 * beta.4 走的是 Android 16 的「实时更新」通知（由系统提升成状态栏芯片），那套只在 36 上有效，
 * 而且用户根本看不见「岛」。这一版回到覆盖层：
 *   ① 一个 View 直接 WindowManager.addView，type=TYPE_APPLICATION_OVERLAY
 *      （26 以下没有这个类型，退回 TYPE_PHONE）；
 *   ② 水平中心对准 DisplayCutout.getBoundingRects() 给的挖孔矩形中心，竖直方向让胶囊
 *      中心压在挖孔中心上，于是看起来像从摄像头里长出来的；没有挖孔就退化到顶边 12dp 居中；
 *   ③ 32/34dp 高、圆角 = 高度/2、纯黑底、白字、左侧 6dp 语义色圆点；
 *   ④ 点一下回应用、长按 700ms 后可以上下微调（offset 存偏好），不做贴边吸附。
 *
 * 生命周期完全由 QqbotGuardService 驱动：服务 onCreate 建窗、每轮状态检查刷新、
 * onDestroy/停止时 removeViewImmediate。本类自己不持线程、不注册广播，
 * 被服务调一次就同步一次状态——服务没了，窗也必须没了，不然就是僵尸窗。
 *
 * 纯逻辑集中在本文件 ISLAND-PURE 标记块里：只依赖 java.lang，
 * 能被 out/b134b5_island_check.py 抠出来在普通 JVM 上跑回归。改这里必须同步改测试。
 */
public final class QqbotIslandCapsule {

    /* ================================================================
     * 尺寸与颜色（全部 dp，按屏幕密度换算）
     * ================================================================ */

    /** 收起态高度（契约 34dp）。 */
    private static final int H_COLLAPSED_DP = 34;
    /** 展开态高度（契约 56dp），多给一行运行时长。 */
    private static final int H_EXPANDED_DP = 56;
    /** 最小宽度：太窄了文字会贴边。 */
    private static final int MIN_W_DP = 92;
    /** 最大宽度不超过屏宽的 60%（契约）。 */
    private static final float MAX_W_RATIO = 0.6f;

    /** 没有挖孔时的退化位置：顶边 12dp、水平居中。 */
    private static final int FALLBACK_TOP_DP = 12;

    private static final int PAD_H_DP = 14;
    private static final int DOT_DP = 6;
    private static final int GAP_DP = 8;
    private static final float TEXT_SP = 12.5f;
    private static final float SUB_SP = 10f;

    /** 长按多久进入拖动（契约 700ms）。 */
    private static final int LONG_PRESS_MS = 700;
    /** 拖动时手指位移超过这个值就不再算点击。 */
    private static final int TOUCH_SLOP_DP = 6;
    /** 展开后自动收起的时间。 */
    private static final long EXPAND_MS = 2200L;

    /** 竖向微调偏移（单位 dp，相对挖孔对齐位置）。 */
    public static final String KEY_OFFSET = "island.offsetY";

    /** 黑底白字：胶囊必须是深色的，浅色主题下也一样（状态栏永远是深底）。 */
    private static final int BG_COLOR = 0xFF0B0B0C;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int SUB_COLOR = 0xB3FFFFFF;

    /* ================================================================
     * 纯逻辑：不碰 Android 类，能抠出来在普通 JVM 上跑回归
     * （见 out/b134b5_island_check.py）。改这里必须同步同步改测试。
     * ================================================================ */

    // ==== ISLAND-PURE-BEGIN ====

    /** 展不展开就这两个高度，别再从布局里算——覆盖层没有 res，量不出来。 */
    static int heightDp(boolean expanded) {
        return expanded ? H_EXPANDED_DP : H_COLLAPSED_DP;
    }

    /** 圆角 = 高度 / 2，胶囊两端正好是半圆。 */
    static int radiusPx(int heightPx) {
        if (heightPx <= 0) return 0;
        return heightPx / 2;
    }

    /**
     * 文字宽度估算：中日韩全角算 2 个单位，其余算 1 个。
     * 「运行中」= 6 个单位，「待扫码」= 6，「离线」= 4。
     */
    static int textUnits(String s) {
        if (s == null) return 0;
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch <= 0x20) continue;
            boolean wide = (ch >= 0x1100 && ch <= 0x115F)
                    || (ch >= 0x2E80 && ch <= 0xA4CF)
                    || (ch >= 0xAC00 && ch <= 0xD7A3)
                    || (ch >= 0xF900 && ch <= 0xFAFF)
                    || (ch >= 0xFE30 && ch <= 0xFE4F)
                    || (ch >= 0xFF00 && ch <= 0xFF60)
                    || (ch >= 0xFFE0 && ch <= 0xFFE6);
            n += wide ? 2 : 1;
        }
        return n;
    }

    /**
     * 胶囊宽度（dp）：内容决定，最小 92dp，最大不超过屏宽的 60%。
     * 每个文字单位按 7dp 估（12.5sp 中文大约就是这个宽度），再加上左右内边距、圆点和间隙。
     */
    static int widthDp(int units, int screenWdp) {
        int content = PAD_H_DP * 2 + DOT_DP + GAP_DP + units * 7;
        int max = (int) Math.floor(screenWdp * MAX_W_RATIO);
        if (max < MIN_W_DP) max = MIN_W_DP;
        if (content < MIN_W_DP) content = MIN_W_DP;
        if (content > max) content = max;
        return content;
    }

    /**
     * 竖直对齐：胶囊中心压在挖孔矩形的竖直中心上，于是「像从摄像头里长出来」。
     * 挖孔矩形无效（cutBottom <= cutTop，部分 ROM 会给空矩形）就退回 fallbackTop。
     * 返回值可能为负（挖孔贴着屏顶），由 clampTop 兜底。
     */
    static int cutoutTop(int cutTop, int cutBottom, int capsuleH, int fallbackTop) {
        if (cutBottom <= cutTop || capsuleH <= 0) return fallbackTop;
        int centerY = (cutTop + cutBottom) / 2;
        return centerY - capsuleH / 2;
    }

    /** 窗口坐标不能是负数（负值会被系统当成「超出屏幕」直接不画）。 */
    static int clampTop(int top, int offset, int screenH) {
        int v = top + offset;
        if (v < 0) return 0;
        int limit = screenH > 0 ? screenH / 3 : 0;
        if (limit > 0 && v > limit) return limit;
        return v;
    }

    /**
     * 水平位置：中心对准挖孔矩形的水平中心；没有挖孔就屏幕居中。
     * 返回的是窗口左边沿（相对屏幕左边），调用方再转成 CENTER_HORIZONTAL 下的 x 偏移。
     */
    static int leftOf(int cutLeft, int cutRight, int screenW, int capsuleW) {
        int cx = (cutRight > cutLeft) ? (cutLeft + cutRight) / 2 : screenW / 2;
        int left = cx - capsuleW / 2;
        if (left < 0) return 0;
        int maxLeft = screenW - capsuleW;
        if (maxLeft < 0) return 0;
        if (left > maxLeft) return maxLeft;
        return left;
    }

    /** 竖向微调的合法范围（dp 偏移，正数往下）：0 ~ 屏高/3，避免把胶囊拖到屏幕外。 */
    static int clampOffsetDp(int offsetDp, int screenHdp) {
        int limit = screenHdp > 0 ? screenHdp / 3 : 0;
        if (offsetDp < 0) return 0;
        if (offsetDp > limit) return limit;
        return offsetDp;
    }

    /** 圆点颜色：运行中绿（--ok）、待扫码黄（--warn）、启动中蓝（--accent）、掉线红（--bad）。 */
    static int dotColor(String code) {
        if ("run".equals(code)) return 0xFF00C853;
        if ("qr".equals(code)) return 0xFFFFC107;
        if ("boot".equals(code)) return 0xFF3C96CA;
        return 0xFFF44336;
    }

    /**
     * 展开态的第二行：运行中给时长，其余给「现在该干什么」。
     * 时长文案由调用方算好传进来，纯逻辑不碰时间格式化。
     */
    static String secondLine(String code, String durText) {
        if ("run".equals(code)) return "已运行 " + (durText == null ? "" : durText);
        if ("boot".equals(code)) return "正在启动沙盒";
        if ("qr".equals(code)) return "点开扫一扫";
        return "正在重连";
    }

    /**
     * 这台设备能不能建胶囊：23+ 才有 canDrawOverlays 这套权限模型，
     * 还得用户真的授权了、WindowManager 也拿得到（极少数 ROM 会返回 null）。
     */
    static boolean canHost(int sdkInt, boolean overlayGranted, boolean wmOk) {
        return sdkInt >= 23 && overlayGranted && wmOk;
    }

    /**
     * 现在该不该有胶囊：开关开着且能建。开关关了就必须拆窗——
     * 覆盖层的可见性只认这一个判断，别在别处再各判一遍。
     */
    static boolean shouldShow(boolean enabled, int sdkInt, boolean overlayGranted, boolean wmOk) {
        return enabled && canHost(sdkInt, overlayGranted, wmOk);
    }

    // ==== ISLAND-PURE-END ====

    /* ================================================================
     * 运行期状态（全静态：一个进程只该有一个胶囊）
     * ================================================================ */

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static WindowManager wm;
    private static View root;
    private static WindowManager.LayoutParams lp;
    private static Context app;
    /** 当前状态码，服务每轮写一次。 */
    private static volatile String code = "down";
    private static volatile long upSince = 0L;
    /** 挖孔对齐后的基准顶边（不含用户微调），重算几何时更新。 */
    private static int baseTopPx = 0;
    /** 上次写进 lp.x 的屏幕半宽偏移，供拖动与旋转时复用。 */
    private static int baseXOff = 0;
    private static boolean expanded = false;
    /** 演示模式的截止时刻（0 = 不在演示）。 */
    private static long demoUntil = 0L;
    private static Runnable collapseTask;

    private QqbotIslandCapsule() {
    }

    /* ================================================================
     * 对外（服务与桥调用）
     * ================================================================ */

    /** 用户有没有「显示在其他应用上层」的权限。23 以下这权限装机即给，不再判断。 */
    public static boolean granted(Context c) {
        if (Build.VERSION.SDK_INT < 23) return true;
        try {
            return Settings.canDrawOverlays(c);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 现在能不能建窗（权限 + WindowManager 都到位）。 */
    public static boolean canHostNow(Context c) {
        try {
            Object svc = c.getSystemService(Context.WINDOW_SERVICE);
            return canHost(Build.VERSION.SDK_INT, granted(c), svc instanceof WindowManager);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 胶囊此刻是不是真的挂在屏幕上。 */
    public static boolean isShown() {
        return root != null;
    }

    public static String currentCode() {
        return code;
    }

    /**
     * 服务启动/开关变化时调一次：该有窗就建、不该有就拆。
     * 幂等——已经挂着就只刷新一次内容，不会重复 addView。
     */
    public static void sync(Context c) {
        try {
            final Context a = c.getApplicationContext();
            app = a;
            if (!QqbotIslandBridge.isEnabled(a)) {
                hide();
                return;
            }
            if (!canHostNow(a)) {
                // 没授权就不建窗：只留偏好，等用户去设置里放开
                hide();
                return;
            }
            UI.post(new SyncTask(a));
        } catch (Throwable ignored) {
        }
    }

    /** 状态变了就刷新文案与几何（不重建窗口）。 */
    public static void update(String st, long since) {
        String next = (st == null || st.length() == 0) ? "down" : st;
        boolean changed = !next.equals(code);
        code = next;
        upSince = since;
        if (changed) flashPending = true;
        UI.post(new PaintTask());
    }

    /**
     * 拆窗：服务停止/被关/权限被撤销时都必须走到这里。
     * 已经在主线程（onDestroy 就是主线程）就立刻 removeViewImmediate——
     * 服务正在被销毁时，post 出去的 Runnable 可能根本来不及执行，那就会留一枚僵尸胶囊。
     */
    public static void hide() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            detach();
        } else {
            UI.post(new DetachTask());
        }
    }

    /**
     * 演示：不管开关、直接挂 5 秒胶囊给用户看长什么样。
     * 演示结束后按真实状态收尾（服务在跑就回到真状态，否则拆掉）。
     */
    public static void demo(Context c, long ttlMs) {
        try {
            final Context a = c.getApplicationContext();
            app = a;
            if (!canHostNow(a)) {
                toast(a, "还不允许「显示在其他应用上层」，去设置里开一下");
                return;
            }
            demoUntil = System.currentTimeMillis() + Math.max(1000L, ttlMs);
            UI.post(new DemoStart(a, demoUntil));
        } catch (Throwable ignored) {
        }
    }

    /* ================================================================
     * 建窗 / 拆窗
     * ================================================================ */

    private static void attach(Context c) {
        if (root != null) return;
        if (!canHostNow(c)) return;
        try {
            wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        } catch (Throwable t) {
            wm = null;
        }
        if (wm == null) return;

        WindowManager.LayoutParams p = new WindowManager.LayoutParams();
        // 26 起才有 TYPE_APPLICATION_OVERLAY；再往下只能退回老的 TYPE_PHONE
        p.type = (Build.VERSION.SDK_INT >= 26)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        p.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        p.format = PixelFormat.TRANSLUCENT;
        p.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        // 挖孔区域默认是不给普通窗口用的；ALWAYS 才允许窗口盖到摄像头那一块
        if (Build.VERSION.SDK_INT >= 30) {
            p.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else if (Build.VERSION.SDK_INT >= 28) {
            // ALWAYS 是 API 30 才有的取值，28/29 上设它可能被系统拒，只能退到 SHORT_EDGES
            p.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        p.x = 0;
        p.y = dp(FALLBACK_TOP_DP);
        p.width = dp(MIN_W_DP);
        p.height = dp(heightDp(false));

        lp = p;
        CapsuleView v = new CapsuleView(c);
        root = v;
        try {
            wm.addView(v, p);
        } catch (Throwable t) {
            // 权限被别的 ROM 拦了、或者窗口已经被系统回收：都别把服务带崩
            root = null;
            wm = null;
            return;
        }
        // 挂上去才知道真实 insets：先按 fallback 建，post 一次再对准挖孔
        UI.post(new AlignTask());
        v.setOnApplyWindowInsetsListener(new InsetsHook());
    }

    private static void detach() {
        View v = root;
        root = null;
        collapseTask = null;
        expanded = false;
        if (v == null) return;
        try {
            if (wm != null) wm.removeViewImmediate(v);
        } catch (Throwable ignored) {
        }
    }

    /* ================================================================
     * 定位：挖孔矩形 → 窗口坐标
     * ================================================================ */

    /** 取挖孔矩形（多挖孔时取最上面那个）；取不到返回 null。 */
    private static Rect cutoutRect() {
        if (Build.VERSION.SDK_INT < 28) return null;
        try {
            DisplayCutout dc = currentCutout();
            if (dc == null) return null;
            java.util.List<Rect> rects = dc.getBoundingRects();
            if (rects == null || rects.isEmpty()) return null;
            Rect best = null;
            for (int i = 0; i < rects.size(); i++) {
                Rect r = rects.get(i);
                if (r == null || r.width() <= 0 || r.height() <= 0) continue;
                if (best == null || r.top < best.top) best = r;
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 从我们自己的窗口读 WindowInsets（服务没有 Activity，只能靠已挂上的 View 拿）。 */
    private static DisplayCutout currentCutout() {
        try {
            View v = root;
            if (v == null) return null;
            WindowInsets in = v.getRootWindowInsets();
            if (in == null) return null;
            return in.getDisplayCutout();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 按当前状态重算宽高与位置，只在真的变了时才 updateViewLayout（每次调用都是一次跨进程事务）。 */
    private static void applyGeometry() {
        if (root == null || wm == null || lp == null || app == null) return;
        try {
            int[] wh = screenSize();
            int screenW = wh[0];
            int screenH = wh[1];
            if (screenW <= 0 || screenH <= 0) return;
            int screenWdp = px2dp(screenW);

            String label = QqbotIslandBridge.labelOf(code);
            int wPx = dp(widthDp(textUnits(label), screenWdp));
            int hPx = dp(heightDp(expanded));

            Rect cut = cutoutRect();
            int top;
            int xOff;
            if (cut != null) {
                int fallback = dp(FALLBACK_TOP_DP);
                top = cutoutTop(cut.top, cut.bottom, hPx, fallback);
                baseTopPx = top;
                int left = leftOf(cut.left, cut.right, screenW, wPx);
                baseXOff = left - screenW / 2;
                xOff = baseXOff;
            } else {
                baseTopPx = dp(FALLBACK_TOP_DP);
                baseXOff = 0;
                xOff = 0;
                top = baseTopPx;
            }
            int y = clampTop(top, loadOffsetPx(), screenH);

            if (lp.width == wPx && lp.height == hPx && lp.x == xOff && lp.y == y) {
                root.invalidate();
                return;
            }
            lp.width = wPx;
            lp.height = hPx;
            lp.x = xOff;
            lp.y = y;
            wm.updateViewLayout(root, lp);
            root.invalidate();
        } catch (Throwable ignored) {
        }
    }

    /* ================================================================
     * 展开 / 收起
     * ================================================================ */

    private static void setExpanded(boolean on) {
        if (expanded == on) return;
        expanded = on;
        applyGeometry();
    }

    private static void expandBriefly() {
        setExpanded(true);
        if (collapseTask != null) UI.removeCallbacks(collapseTask);
        collapseTask = new CollapseTask();
        UI.postDelayed(collapseTask, EXPAND_MS);
    }

    /* ================================================================
     * 微调偏移（dp，存 qqbot_notify）
     * ================================================================ */

    private static int loadOffsetPx() {
        try {
            SharedPreferences sp = app.getSharedPreferences(QqbotIslandBridge.PREF, Context.MODE_PRIVATE);
            int dpv = clampOffsetDp(sp.getInt(KEY_OFFSET, 0), px2dp(screenSize()[1]));
            return dp(dpv);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void saveOffsetPx(int offsetPx) {
        try {
            int dpv = clampOffsetDp(px2dp(offsetPx), px2dp(screenSize()[1]));
            app.getSharedPreferences(QqbotIslandBridge.PREF, Context.MODE_PRIVATE)
                    .edit().putInt(KEY_OFFSET, dpv).apply();
        } catch (Throwable ignored) {
        }
    }

    /* ================================================================
     * 工具
     * ================================================================ */

    private static int[] screenSize() {
        int[] out = new int[]{0, 0};
        try {
            if (wm != null) {
                android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
                wm.getDefaultDisplay().getRealMetrics(dm);
                out[0] = dm.widthPixels;
                out[1] = dm.heightPixels;
            }
        } catch (Throwable ignored) {
        }
        if (out[0] <= 0 && app != null) {
            try {
                android.util.DisplayMetrics dm2 = app.getResources().getDisplayMetrics();
                out[0] = dm2.widthPixels;
                out[1] = dm2.heightPixels;
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static int dp(float v) {
        try {
            return Math.round(v * app.getResources().getDisplayMetrics().density);
        } catch (Throwable t) {
            return Math.round(v * 3f);
        }
    }

    private static int px2dp(int px) {
        try {
            float d = app.getResources().getDisplayMetrics().density;
            if (d <= 0f) return px;
            return Math.round(px / d);
        } catch (Throwable t) {
            return px;
        }
    }

    private static float sp(float v) {
        try {
            return v * app.getResources().getDisplayMetrics().scaledDensity;
        } catch (Throwable t) {
            return v * 3f;
        }
    }

    private static void toast(Context c, String msg) {
        try {
            Toast.makeText(c, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    /** 点胶囊回应用：已经在栈里就提到最前，不重建 Activity。 */
    private static void openApp() {
        try {
            Intent i = new Intent(app, QqbotActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            app.startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    /* ================================================================
     * 自绘胶囊（不 inflate 布局：覆盖层没有 res，量宽高必须自己说了算）
     * ================================================================ */

    /** 状态变了就展开一下再收回：纯逻辑侧只置个标记，动画靠系统时钟，不另起线程。 */
    private static volatile boolean flashPending = false;
    /** 递进中的长按任务，收指/滑开时必须能撤掉它，否则手指早抬起来了还会进入拖动。 */
    private static volatile LongPress pressTask;

    private static final class CapsuleView extends View {

        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rf = new RectF();

        private int downRawY;
        private int startLpY;
        private long downMs;
        private boolean dragging;
        private boolean moved;

        CapsuleView(Context c) {
            super(c);
            bgPaint.setStyle(Paint.Style.FILL);
            bgPaint.setColor(BG_COLOR);
            dotPaint.setStyle(Paint.Style.FILL);
            dotPaint.setColor(dotColor(code));
            textPaint.setColor(TEXT_COLOR);
            textPaint.setTextSize(sp(TEXT_SP));
            textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
            textPaint.setTextAlign(Paint.Align.LEFT);
            subPaint.setColor(SUB_COLOR);
            subPaint.setTextSize(sp(SUB_SP));
            subPaint.setTextAlign(Paint.Align.LEFT);
        }

        @Override
        protected void onDraw(Canvas cv) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            float r = radiusPx(h);
            rf.set(0f, 0f, w, h);
            cv.drawRoundRect(rf, r, r, bgPaint);

            dotPaint.setColor(dotColor(code));
            float dot = dp(DOT_DP);
            float dotX = dp(PAD_H_DP) + dot / 2f;
            float dotY = h / 2f;
            float textX = dp(PAD_H_DP) + dot + dp(GAP_DP);

            if (!expanded) {
                cv.drawCircle(dotX, dotY, dot / 2f, dotPaint);
                Paint.FontMetrics fm = textPaint.getFontMetrics();
                float base = h / 2f - (fm.ascent + fm.descent) / 2f;
                cv.drawText(QqbotIslandBridge.labelOf(code), textX, base, textPaint);
                return;
            }

            // 展开态：上行状态、下行补充信息，圆点压在第一行上
            float lineH = h * 0.5f;
            dotY = lineH / 2f + dp(1);
            cv.drawCircle(dotX, dotY, dot / 2f, dotPaint);
            Paint.FontMetrics fm1 = textPaint.getFontMetrics();
            float base1 = lineH / 2f - (fm1.ascent + fm1.descent) / 2f;
            cv.drawText(QqbotIslandBridge.labelOf(code), textX, base1, textPaint);
            Paint.FontMetrics fm2 = subPaint.getFontMetrics();
            float base2 = lineH + lineH / 2f - (fm2.ascent + fm2.descent) / 2f;
            cv.drawText(secondLine(code, QqbotIslandBridge.durText(upSince > 0
                    ? System.currentTimeMillis() - upSince : 0L)), textX, base2, subPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawY = (int) e.getRawY();
                    startLpY = lp != null ? lp.y : 0;
                    downMs = android.os.SystemClock.elapsedRealtime();
                    dragging = false;
                    moved = false;
                    cancelPress();
                    pressTask = new LongPress(this);
                    UI.postDelayed(pressTask, LONG_PRESS_MS);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int dy = (int) e.getRawY() - downRawY;
                    if (!dragging) {
                        if (Math.abs(dy) > dp(TOUCH_SLOP_DP)) {
                            moved = true;
                            cancelPress();
                        }
                        return true;
                    }
                    if (lp == null || wm == null) return true;
                    int[] wh = screenSize();
                    int target = clampTop(startLpY, dy, wh[1]);
                    if (target != lp.y) {
                        lp.y = target;
                        try {
                            wm.updateViewLayout(this, lp);
                        } catch (Throwable ignored) {
                        }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    cancelPress();
                    long cost = android.os.SystemClock.elapsedRealtime() - downMs;
                    boolean wasDragging = dragging;
                    dragging = false;
                    if (wasDragging && lp != null) {
                        // 拖动结束把偏移落盘：下次建窗直接用这个位置
                        saveOffsetPx(lp.y - baseTopPx);
                    } else if (e.getActionMasked() == MotionEvent.ACTION_UP
                            && !moved && cost < LONG_PRESS_MS) {
                        openApp();
                    }
                    return true;
                }
                default:
                    return true;
            }
        }

        /** 撤掉还没到点的长按：手指抬起或滑开时都必须断开，否则会「凭空」进入拖动。 */
        private void cancelPress() {
            LongPress t = pressTask;
            pressTask = null;
            if (t != null) UI.removeCallbacks(t);
        }

        /** 长按到点：进入拖动。展开态要收回（高度变了要重算几何），基准跟着重算，免得一拖就跳。 */
        void beginDrag() {
            dragging = true;
            expanded = false;
            applyGeometry();
            if (lp != null) startLpY = lp.y;
        }

        /** 状态变了：闪一下展开态，两秒后自己收回。 */
        void flash() {
            expandBriefly();
        }
    }

    /* ================================================================
     * 具名内部类（d8 不吃匿名类）
     * ================================================================ */

    private static final class SyncTask implements Runnable {
        private final Context ctx;

        SyncTask(Context c) {
            ctx = c;
        }

        public void run() {
            if (root == null) {
                attach(ctx);
            }
            applyGeometry();
            if (root != null && root instanceof CapsuleView) {
                ((CapsuleView) root).invalidate();
            }
        }
    }

    private static final class PaintTask implements Runnable {
        public void run() {
            if (root == null) {
                flashPending = false;
                return;
            }
            applyGeometry();
            root.invalidate();
            if (flashPending) {
                flashPending = false;
                if (root instanceof CapsuleView) ((CapsuleView) root).flash();
            }
        }
    }

    private static final class DetachTask implements Runnable {
        public void run() {
            detach();
        }
    }

    private static final class AlignTask implements Runnable {
        public void run() {
            applyGeometry();
        }
    }

    private static final class CollapseTask implements Runnable {
        public void run() {
            collapseTask = null;
            setExpanded(false);
            if (root != null) root.invalidate();
        }
    }

    private static final class DemoStart implements Runnable {
        private final Context ctx;
        private final long until;

        DemoStart(Context c, long u) {
            ctx = c;
            until = u;
        }

        public void run() {
            if (root == null) attach(ctx);
            if (root == null) {
                demoUntil = 0L;
                return;
            }
            code = "run";
            upSince = System.currentTimeMillis() - 3L * 60L * 1000L;
            applyGeometry();
            if (root != null) {
                root.invalidate();
                if (root instanceof CapsuleView) ((CapsuleView) root).flash();
            }
            UI.postDelayed(new DemoEnd(ctx), Math.max(1000L, until - System.currentTimeMillis()));
        }
    }

    private static final class DemoEnd implements Runnable {
        private final Context ctx;

        DemoEnd(Context c) {
            ctx = c;
        }

        public void run() {
            if (System.currentTimeMillis() < demoUntil) return;
            demoUntil = 0L;
            if (QqbotIslandBridge.isEnabled(ctx) && QqbotGuardService.guardRunning) {
                code = QqbotIslandBridge.currentCode();
                upSince = QqbotIslandBridge.currentUpSince();
                if (root == null) attach(ctx);
                applyGeometry();
                if (root != null) root.invalidate();
            } else {
                detach();
            }
        }
    }

    private static final class InsetsHook implements View.OnApplyWindowInsetsListener {
        public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
            applyGeometry();
            return in;
        }
    }

    private static final class LongPress implements Runnable {
        private final CapsuleView target;

        LongPress(CapsuleView v) {
            target = v;
        }

        public void run() {
            if (target == null) return;
            if (root != target || lp == null) return;
            target.beginDrag();
        }
    }
}
