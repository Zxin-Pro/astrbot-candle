package com.termux.app;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Choreographer;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.Interpolator;
import android.widget.Toast;

/**
 * 摄像头挖孔位置的系统级胶囊（v1.3.4-beta.7，灵动岛 v4）。
 *
 * 用户要的是「安卓原生的灵动岛」：收起时是贴着挖孔的一小条，有事件就自己弹开成一张
 * 音乐播放器那样的岛（左图标、中间两行字、右边跳动的均衡器、底部进度条），三秒后收回去。
 * 所以这一版把 beta.5 的「一块黑色贴纸」拆成三层重画：
 *   ① 收起态：宽高按文案自适应的小胶囊，圆角=高/2，1dp 细描边 + 一圈手绘的柔和阴影；
 *   ② 展开态：屏宽 78%、64dp 高，左侧 40dp 圆形图标底（状态色 22% 底 + 状态色图标），
 *      中间标题 13sp / 副标题 11sp，右侧 4 根均衡器竖条（运行中跳动、其余平躺），
 *      底部 2dp 进度条（有倒计时就按剩余时间走，外部给了 p 就按 p 走）；
 *   ③ 过渡：一个 ValueAnimator 同时驱动宽/高/圆角/透明度，弹簧手感（320/26），
 *      展开 520ms、收起 260ms；被打断时从当前进度接着走，不会跳一下。
 *
 * 覆盖层的两条硬约束没变：
 *   - 窗口归 QqbotGuardService 持有，服务 onCreate 建、onDestroy 拆；本类自己不持线程、
 *     不注册广播，服务没了窗也必须没了，否则就是僵尸窗。唯一的常驻回调是绘制用的
 *     Choreographer，展开态才跑，收起/拆窗立刻停。
 *   - 窗口尺寸 = 胶囊尺寸 + 8dp 阴影边。窗口比胶囊大一圈是给阴影留地方（窗口外的像素画不出来），
 *     顺带把点击热区撑到 50dp，满足 44dp 的最小触摸目标。
 *
 * 纯逻辑集中在本文件 ISLAND-PURE 标记块里：只依赖 java.lang，
 * 能被外部回归脚本抠出来在普通 JVM 上跑。改这里必须同步改测试。
 */
public final class QqbotIslandCapsule {

    /* ================================================================
     * 尺寸与颜色（全部 dp，按屏幕密度换算）
     * ================================================================ */

    /** 收起态高度（契约 34dp）：贴着挖孔的一小条。 */
    private static final int H_COLLAPSED_DP = 34;
    /** 展开态高度（契约 64dp）：40dp 图标 + 两行字 + 底部进度条刚放得下。 */
    private static final int H_EXPANDED_DP = 64;
    /** 最小宽度：太窄了文字会贴边。 */
    private static final int MIN_W_DP = 92;
    /** 展开态占屏宽的比例（契约 78%）。 */
    private static final float MAX_W_RATIO = 0.78f;

    /** 没有挖孔时的退化位置：顶边 12dp、水平居中。 */
    private static final int FALLBACK_TOP_DP = 12;

    /* ---- 收起态 ---- */
    private static final int PAD_H_DP = 14;
    private static final int DOT_DP = 6;
    private static final int GAP_DP = 8;
    private static final float TEXT_SP = 12.5f;
    private static final float SUB_SP = 11f;

    /* ---- 展开态 ---- */
    /** 左侧图标底直径（圆形）。 */
    private static final int ICON_DP = 40;
    /** 图标底离左边沿的距离。 */
    private static final int ICON_INSET_DP = 11;
    /** 图标底到文字的间距。 */
    private static final int TEXT_GAP_DP = 12;
    /** 标题字号（契约 13sp / 600）。 */
    private static final float TITLE_SP = 13f;
    /** 均衡器：4 根，条宽、条距、高度区间。 */
    private static final int EQ_COUNT = 4;
    private static final float EQ_W_DP = 2.5f;
    private static final float EQ_GAP_DP = 2.5f;
    private static final float EQ_MIN_DP = 3f;
    private static final float EQ_MAX_DP = 18f;
    /** 均衡器一个完整循环的时间：太慢像呼吸，太快像故障。 */
    private static final long EQ_PERIOD_MS = 1400L;
    /** 底部进度条：2dp 高、离底 6dp、左右各留 12dp。 */
    private static final float BAR_H_DP = 2f;
    private static final float BAR_INSET_DP = 12f;
    private static final float BAR_BOTTOM_DP = 6f;

    /** 窗口四周给阴影留的边：窗口外的像素系统不会画，阴影必须画在窗口里面。 */
    private static final int SHADOW_DP = 8;
    /** 阴影层数：手绘模糊就是一圈圈放大的圆角矩形，层数多了费电、少了能看出台阶。 */
    private static final int SHADOW_STEPS = 6;
    /** 描边宽度（约 1px）：太粗就变成「边框」，灵动岛要的是一道细细的轮廓。 */
    private static final float STROKE_DP = 0.8f;

    /** 长按多久进入拖动（契约 700ms）。 */
    private static final int LONG_PRESS_MS = 700;
    /** 拖动时手指位移超过这个值就不再算点击。 */
    private static final int TOUCH_SLOP_DP = 6;
    /** 上下滑多少算「收起」：太小会跟点击打架，太大要滑很远。 */
    private static final int SWIPE_DP = 12;
    /** 有事件时展开多久再收回（契约 3 秒）。 */
    private static final long EXPAND_MS = 3000L;
    /** 用户手点时展开多久：比事件长一点，够看清也够点第二下开应用。 */
    private static final long TAP_EXPAND_MS = 5000L;
    /** 展开动画时长（契约 ≤600ms）。 */
    private static final long DUR_EXPAND_MS = 520L;
    /** 收起动画时长（契约 260ms）。 */
    private static final long DUR_COLLAPSE_MS = 260L;
    /** 弹簧参数（契约 stiffness≈320 / damping≈26）。 */
    private static final float SPRING_STIFFNESS = 320f;
    private static final float SPRING_DAMPING = 26f;

    /** 竖向微调偏移（单位 dp，相对挖孔对齐位置）。 */
    public static final String KEY_OFFSET = "island.offsetY";

    /** 黑底白字：胶囊必须是深色的，浅色主题下也一样（状态栏永远是深底）。 */
    private static final int BG_COLOR = 0xFF0B0B0C;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int SUB_COLOR = 0xB3FFFFFF;
    /** 描边：白色 14%，压在黑底上是一道若隐若现的轮廓。 */
    private static final int STROKE_COLOR = 0x24FFFFFF;
    /** 图标底的透明度（契约：状态色 22%）。 */
    private static final float ICON_BG_ALPHA = 0.22f;

    /* ================================================================
     * 纯逻辑：不碰 Android 类，能被抠出来在普通 JVM 上跑回归
     * ================================================================ */

    // ==== ISLAND-PURE-BEGIN ====

    /** 展开/收起就这两个高度，别再从布局里算——覆盖层没有 res，量不出来。 */
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
     * 收起态胶囊宽度（dp）：内容决定，最小 92dp，最大不超过屏宽的 78%。
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

    /** 展开态宽度（dp）：契约要的「约占屏宽 78%」，窄屏兜底不小于最小宽度。 */
    static int expandedWidthDp(int screenWdp) {
        if (screenWdp <= 0) return MIN_W_DP;
        int w = (int) Math.floor(screenWdp * MAX_W_RATIO + 0.5f);
        return w < MIN_W_DP ? MIN_W_DP : w;
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
     * 水平位置：以某个锚点为中心摆一个 width 宽的窗口，并夹在屏幕里。
     * 展开时锚点会从挖孔中心渐变到屏幕中心，所以这里只认「中心点」。
     */
    static int leftFor(int anchorCx, int screenW, int width) {
        int left = anchorCx - width / 2;
        if (left < 0) return 0;
        int maxLeft = screenW - width;
        if (maxLeft < 0) return 0;
        if (left > maxLeft) return maxLeft;
        return left;
    }

    /**
     * 挖孔矩形 → 窗口左边沿（旧接口，回归脚本仍在调）：
     * 中心对准挖孔矩形的水平中心；没有挖孔就屏幕居中。
     */
    static int leftOf(int cutLeft, int cutRight, int screenW, int capsuleW) {
        int cx = (cutRight > cutLeft) ? (cutLeft + cutRight) / 2 : screenW / 2;
        return leftFor(cx, screenW, capsuleW);
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

    /** 0~1 夹紧：动画被打断时进度可能短暂越界（弹簧有回弹），画之前一律夹一次。 */
    static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    /** 线性插值（整数版）：宽高都按同一个进度走，才不会「宽到了高没到」。 */
    static int lerpInt(int a, int b, float t) {
        float f = clamp01(t);
        return Math.round(a + (b - a) * f);
    }

    /**
     * 弹簧位移阶跃响应（0→1），t 单位秒。
     * ζ = damping / (2√k)，ζ<1 会有一点点过冲——展开时那 3% 的回弹就是「手感」的来源。
     * 用解析解而不是插值表：换参数不用重新标定，也方便在 JVM 上单测。
     */
    static float springAt(float t, float stiffness, float damping) {
        if (t <= 0f) return 0f;
        float omega = (float) Math.sqrt(stiffness);
        if (omega <= 0f) return 1f;
        float zeta = damping / (2f * omega);
        if (zeta >= 1f) return 1f;
        float wd = omega * (float) Math.sqrt(1f - zeta * zeta);
        float decay = (float) Math.exp(-zeta * omega * t);
        return 1f - decay * ((float) Math.cos(wd * t)
                + (zeta * omega / wd) * (float) Math.sin(wd * t));
    }

    /**
     * 均衡器第 i 根条的高度系数（0~1）：按相位算，四根条各错开一点，
     * 两根正弦叠加，看起来才像真的在跳而不是整齐地呼吸。
     */
    static float eqFactor(float phase, int i) {
        // 两条正弦都必须是 1 个周期的整数倍，否则循环接缝处会「跳一下」
        double p = (phase + i * 0.23) * 2.0 * Math.PI;
        double v = Math.sin(p) * 0.62 + Math.sin(p * 3.0 + 1.1) * 0.38;
        float f = (float) ((v + 1.0) / 2.0);
        return clamp01(f);
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
    /** 屏幕尺寸缓存：动画每帧都要用，别每帧去问 Display。 */
    private static int scrW = 0;
    private static int scrH = 0;

    /** 逻辑展开状态（目标态）；真正的画面由 progress 决定。 */
    private static boolean expanded = false;
    /** 0=完全收起，1=完全展开。宽/高/圆角/透明度全部由它推出来，打断才能平滑。 */
    private static float progress = 0f;
    private static ValueAnimator anim;
    /** 动画代号：打断时旧动画的 onAnimationEnd 要认出来「已经不是我了」。 */
    private static int animSeq = 0;

    /** 自动收回的截止时刻（0 = 不自动收）。 */
    private static long deadline = 0L;
    /** 这一次展开一共给了多少毫秒，进度条按剩余比例走。 */
    private static long deadlineTotal = 1L;
    private static Runnable collapseTask;

    /** 事件带上来的内容（没有就用状态文案）。 */
    private static volatile String noticeTitle = null;
    private static volatile String noticeSub = null;
    /** 外部给的进度（0~1；<0 表示没有，走倒计时）。 */
    private static volatile float noticeFrac = -1f;

    /** 演示模式的截止时刻（0 = 不在演示）。 */
    private static long demoUntil = 0L;

    /** 均衡器/进度条的相位（0~1 循环），由 Choreographer 每帧推进。 */
    private static float eqPhase = 0f;
    private static long eqStartMs = 0L;
    private static boolean framePosted = false;

    private QqbotIslandCapsule() {
    }

    /* ================================================================
     * 对外（服务与桥调用）
     * ================================================================ */

    /** 用户有没有「显示在其他应用上层」的权限。23 以下这权限装机即给，不再判断。 */
    public static boolean granted(Context c) {
        if (Build.VERSION.SDK_INT < 23) return true;
        try {
            if (c == null) return false;
            return Settings.canDrawOverlays(c);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 现在能不能建窗（权限 + WindowManager 都到位）。 */
    public static boolean canHostNow(Context c) {
        try {
            if (c == null) return false;
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
            if (c == null) return;
            final Context a = c.getApplicationContext();
            if (a == null) return;
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

    /**
     * 状态变了就刷新文案与几何（不重建窗口）。
     * 状态码一变就是一次「事件」：展开三秒把内容讲清楚再收回去，不是只闪一下。
     */
    public static void update(String st, long since) {
        String next = (st == null || st.length() == 0) ? "down" : st;
        boolean changed = !next.equals(code);
        code = next;
        upSince = since;
        if (changed) flashPending = true;
        UI.post(new PaintTask());
    }

    /**
     * 外部推一条「上岛通知」（部署进度这类只有界面才知道的事件）。
     * title/sub 为空就退回状态文案；frac 给 0~1 时底部进度条按它走，给负数走倒计时。
     * 只影响画面，不碰状态机——真正的状态仍然以服务的 update 为准。
     */
    public static void notice(String title, String sub, long ms, float frac) {
        try {
            String t = (title == null || title.length() == 0)
                    ? QqbotIslandBridge.labelOf(code) : title;
            noticeTitle = t;
            noticeSub = (sub == null || sub.length() == 0) ? null : sub;
            noticeFrac = (frac < 0f || frac > 1f) ? -1f : frac;
            flashPending = false;
            UI.post(new NoticeTask(ms));
        } catch (Throwable ignored) {
        }
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
            if (c == null) return;
            final Context a = c.getApplicationContext();
            if (a == null) return;
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
        scrW = 0;
        scrH = 0;
        refreshScreen();

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
        p.width = dp(MIN_W_DP) + dp(SHADOW_DP) * 2;
        p.height = dp(heightDp(false)) + dp(SHADOW_DP) * 2;

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

    /** 拆窗并把所有会自己跑的东西停掉：动画、帧回调、倒计时。少停一样就是内存/电量泄漏。 */
    private static void detach() {
        View v = root;
        root = null;
        cancelCollapse();
        stopFrames();
        animSeq++;
        if (anim != null) {
            try {
                anim.cancel();
            } catch (Throwable ignored) {
            }
            anim = null;
        }
        progress = 0f;
        expanded = false;
        deadline = 0L;
        noticeTitle = null;
        noticeSub = null;
        noticeFrac = -1f;
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

    /**
     * 按当前 progress 重算窗口几何。
     * 宽/高/圆角/位置全部来自同一个进度，所以动画里它们天生同步；
     * 只有和上一帧真的不一样才 updateViewLayout（每次都是一次跨进程事务）。
     */
    private static void applyGeometry() {
        if (root == null || wm == null || lp == null || app == null) return;
        try {
            int[] wh = screenSize();
            int screenW = wh[0];
            int screenH = wh[1];
            if (screenW <= 0 || screenH <= 0) return;

            float t = clamp01(progress);
            int screenWdp = px2dp(screenW);
            String label = QqbotIslandBridge.labelOf(code);
            int wCollapsed = dp(widthDp(textUnits(label), screenWdp));
            int wExpanded = dp(expandedWidthDp(screenWdp));
            int hCollapsed = dp(heightDp(false));
            int hExpanded = dp(heightDp(true));

            int margin = dp(SHADOW_DP);
            int capW = lerpInt(wCollapsed, wExpanded, t);
            int capH = lerpInt(hCollapsed, hExpanded, t);
            int winW = capW + margin * 2;
            int winH = capH + margin * 2;

            Rect cut = cutoutRect();
            int cx = screenW / 2;
            int cy = dp(FALLBACK_TOP_DP) + winH / 2;
            if (cut != null) {
                int cutCx = (cut.left + cut.right) / 2;
                int cutCy = (cut.top + cut.bottom) / 2;
                // 收起时贴着挖孔；展开时往屏幕中心滑一点，宽岛贴边会很难看
                cx = lerpInt(cutCx, screenW / 2, t);
                cy = cutCy;
            }
            int left = leftFor(cx, screenW, winW);
            int baseTop = cy - winH / 2;
            baseTopPx = baseTop;
            int y = clampTop(baseTop, loadOffsetPx(), screenH);
            // CENTER_HORIZONTAL 下 lp.x 是相对屏幕中线的偏移
            int xOff = left - (screenW - winW) / 2;

            if (lp.width == winW && lp.height == winH && lp.x == xOff && lp.y == y) {
                root.invalidate();
                return;
            }
            lp.width = winW;
            lp.height = winH;
            lp.x = xOff;
            lp.y = y;
            wm.updateViewLayout(root, lp);
            root.invalidate();
        } catch (Throwable ignored) {
            // 权限刚被撤销 / 窗口已被系统回收：下一轮 sync 会走拆窗，这里绝不能把服务带崩
        }
    }

    /* ================================================================
     * 展开 / 收起（弹簧动画）
     * ================================================================ */

    /**
     * 往目标进度走。
     * 被打断（收起一半又要展开）时从当前 progress 起步，所以只会「换个方向继续」，
     * 不会跳回起点——这就是「中途打断要能平滑接上」的实现方式。
     */
    private static void animateTo(float target, long durMs) {
        // 先把代号推进去再 cancel：cancel() 会同步触发旧动画的 onAnimationEnd，
        // 代号要是还没换，旧动画就会被当成「当前这一发」收尾，画面当场跳一下
        final int seq = ++animSeq;
        if (anim != null) {
            try {
                anim.cancel();
            } catch (Throwable ignored) {
            }
            anim = null;
        }
        float to = target > 1f ? 1f : (target < 0f ? 0f : target);
        if (Math.abs(progress - to) < 0.004f) {
            progress = to;
            finishAnim(seq, to);
            return;
        }
        ValueAnimator a = ValueAnimator.ofFloat(progress, to);
        a.setDuration(durMs < 16L ? 16L : durMs);
        a.setInterpolator(new Spring(durMs / 1000f, to > progress));
        a.addUpdateListener(new Tick());
        a.addListener(new End(seq));
        anim = a;
        try {
            a.start();
        } catch (Throwable t) {
            progress = to;
            finishAnim(seq, to);
        }
    }

    /** 展开并定时收回。ms 是停留时间，进度条按它倒计时。 */
    private static void expandFor(long ms) {
        cancelCollapse();
        expanded = true;
        deadlineTotal = Math.max(500L, ms);
        deadline = System.currentTimeMillis() + deadlineTotal;
        startFrames();
        animateTo(1f, DUR_EXPAND_MS);
        collapseTask = new CollapseTask();
        UI.postDelayed(collapseTask, deadlineTotal);
    }

    private static void collapseNow() {
        expanded = false;
        // 手点收起时要撤掉还在排队的自动收回，别让它 3 秒后再「收」一次
        cancelCollapse();
        animateTo(0f, DUR_COLLAPSE_MS);
    }

    private static void cancelCollapse() {
        if (collapseTask != null) {
            UI.removeCallbacks(collapseTask);
            collapseTask = null;
        }
        deadline = 0L;
    }

    /** 动画落定后的收尾：该清的事件内容清掉，几何再对一次（弹簧末尾是小数，要取整对齐）。 */
    private static void finishAnim(int seq, float to) {
        if (seq != animSeq) return;
        anim = null;
        progress = to <= 0.5f ? 0f : 1f;
        if (progress <= 0f) {
            stopFrames();
            deadline = 0L;
            noticeTitle = null;
            noticeSub = null;
            noticeFrac = -1f;
        }
        applyGeometry();
        if (root != null) root.invalidate();
    }

    /* ================================================================
     * 帧驱动：均衡器与进度条（Choreographer，展开时才跑）
     * ================================================================ */

    private static void startFrames() {
        if (!framePosted) {
            // 相位钟只在「起跑」时归零，续帧不能重置，否则只会画第 0 帧
            eqStartMs = SystemClock.uptimeMillis();
        }
        postFrame();
    }

    /** 只管把下一帧排上（相位钟不动）。 */
    private static void postFrame() {
        if (framePosted) return;
        if (Looper.myLooper() != Looper.getMainLooper()) return;
        try {
            framePosted = true;
            Choreographer.getInstance().postFrameCallback(new Frames());
        } catch (Throwable t) {
            framePosted = false;
        }
    }

    private static void stopFrames() {
        framePosted = false;
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

    /** 屏幕尺寸：缓存一次，屏幕转了就 refreshScreen 重来。 */
    private static int[] screenSize() {
        if (scrW > 0 && scrH > 0) return new int[]{scrW, scrH};
        refreshScreen();
        return new int[]{scrW, scrH};
    }

    private static void refreshScreen() {
        try {
            if (wm != null) {
                android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
                wm.getDefaultDisplay().getRealMetrics(dm);
                scrW = dm.widthPixels;
                scrH = dm.heightPixels;
            }
        } catch (Throwable ignored) {
        }
        if (scrW <= 0 && app != null) {
            try {
                android.util.DisplayMetrics dm2 = app.getResources().getDisplayMetrics();
                scrW = dm2.widthPixels;
                scrH = dm2.heightPixels;
            } catch (Throwable ignored) {
            }
        }
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
            if (app == null) return;
            Intent i = new Intent(app, QqbotActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            app.startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    /* ================================================================
     * 自绘胶囊（不 inflate 布局：覆盖层没有 res，量宽高必须自己说了算）
     * ================================================================ */

    /**
     * 底部进度条的取值（0~1）：外部推了 p 就用 p（部署进度这类），
     * 否则按「还有多久收回」倒计时——比假装有个播放进度诚实，也真的在动。
     */
    private static float barFraction() {
        float f = noticeFrac;
        if (f >= 0f) return clamp01(f);
        long total = deadlineTotal;
        long left = deadline - System.currentTimeMillis();
        if (total <= 0L) return 1f;
        if (left <= 0L) return 0f;
        return clamp01((float) left / (float) total);
    }

    /** 状态变了就展开一下再收回：纯逻辑侧只置个标记，动画靠系统时钟，不另起线程。 */
    private static volatile boolean flashPending = false;
    /** 递进中的长按任务，收指/滑开时必须能撤掉它，否则手指早抬起来了还会进入拖动。 */
    private static volatile LongPress pressTask;

    private static final class CapsuleView extends View {

        private final float den = getResources().getDisplayMetrics().density;

        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iconBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint eqPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final RectF rf = new RectF();
        private final RectF iconRf = new RectF();
        private final RectF arcRf = new RectF();
        private final Path triPath = new Path();

        private int downRawY;
        private int startLpY;
        private long downMs;
        private boolean dragging;
        private boolean moved;
        private boolean swiped;

        CapsuleView(Context c) {
            super(c);
            bgPaint.setStyle(Paint.Style.FILL);
            bgPaint.setColor(BG_COLOR);

            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(Math.max(1f, px(STROKE_DP)));
            strokePaint.setColor(STROKE_COLOR);

            shadowPaint.setStyle(Paint.Style.FILL);
            shadowPaint.setColor(0xFF000000);

            dotPaint.setStyle(Paint.Style.FILL);
            dotPaint.setColor(dotColor(code));

            textPaint.setColor(TEXT_COLOR);
            textPaint.setTextSize(sp(TEXT_SP));
            textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
            textPaint.setTextAlign(Paint.Align.LEFT);

            titlePaint.setColor(TEXT_COLOR);
            titlePaint.setTextSize(sp(TITLE_SP));
            // 600 在系统字体里没有对应常量，BOLD 最接近；用户要的是「标题比正文重」
            titlePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            titlePaint.setTextAlign(Paint.Align.LEFT);

            subPaint.setColor(SUB_COLOR);
            subPaint.setTextSize(sp(SUB_SP));
            subPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
            subPaint.setTextAlign(Paint.Align.LEFT);

            iconBgPaint.setStyle(Paint.Style.FILL);
            glyphPaint.setStyle(Paint.Style.FILL);
            glyphPaint.setStrokeCap(Paint.Cap.ROUND);
            eqPaint.setStyle(Paint.Style.FILL);
            trackPaint.setStyle(Paint.Style.FILL);
            barPaint.setStyle(Paint.Style.FILL);
        }

        /** dp → px，绘制里高频调用，别再走静态的 getResources。 */
        private float px(float dpv) {
            return dpv * den;
        }

        /** 透明度权重：p 是展开进度，a 是这一层自己的权重。 */
        private int alphaOf(float a) {
            float v = a;
            if (v < 0f) v = 0f;
            if (v > 1f) v = 1f;
            return Math.round(255f * v);
        }

        @Override
        protected void onDraw(Canvas cv) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            float margin = px(SHADOW_DP);
            float l = margin;
            float t = margin;
            float r = w - margin;
            float b = h - margin;
            if (r - l < 4f || b - t < 4f) {
                // 窗口还没被系统量好（第一帧常见）：先按整个窗口画，别画到负数里去
                l = 0f;
                t = 0f;
                r = w;
                b = h;
            }
            float rad = Math.min((b - t) / 2f, (r - l) / 2f);
            rf.set(l, t, r, b);

            drawShadow(cv, l, t, r, b, rad);

            bgPaint.setColor(BG_COLOR);
            cv.drawRoundRect(rf, rad, rad, bgPaint);
            strokePaint.setAlpha(255);
            cv.drawRoundRect(rf, rad, rad, strokePaint);

            float p = clamp01(progress);
            if (p < 0.985f) {
                drawCollapsed(cv, l, t, r, b, clamp01(1f - p * 1.45f));
            }
            if (p > 0.015f) {
                // 展开内容晚一点淡入：宽度还没长开就画字，会被省略号糊一脸
                drawExpanded(cv, l, t, r, b, clamp01((p - 0.35f) / 0.5f));
            }
        }

        /**
         * 手绘柔和阴影：一圈圈放大的圆角矩形，越往外越淡。
         * 为什么不 setShadowLayer：那要走软件图层，动画每帧重绘会掉帧；
         * 这里 6 次 drawRoundRect 在硬件层上是白送的。
         */
        private void drawShadow(Canvas cv, float l, float t, float r, float b, float rad) {
            float step = px(1.2f);
            for (int i = SHADOW_STEPS; i >= 1; i--) {
                float grow = i * step;
                int alpha = Math.round(20f * (1f - (i - 1f) / SHADOW_STEPS));
                if (alpha <= 0) continue;
                shadowPaint.setAlpha(alpha);
                rf.set(l - grow, t - grow + px(0.8f), r + grow, b + grow + px(0.8f));
                cv.drawRoundRect(rf, rad + grow, rad + grow, shadowPaint);
            }
            rf.set(l, t, r, b);
        }

        /** 收起态：一颗状态色圆点 + 一句短文案，居中对齐，干干净净。 */
        private void drawCollapsed(Canvas cv, float l, float t, float r, float b, float a) {
            if (a <= 0.01f) return;
            float h = b - t;
            float dot = px(DOT_DP);
            float cx = l + px(PAD_H_DP) + dot / 2f;
            float cy = t + h / 2f;

            dotPaint.setColor(dotColor(code));
            dotPaint.setAlpha(alphaOf(a));
            cv.drawCircle(cx, cy, dot / 2f, dotPaint);

            textPaint.setAlpha(alphaOf(a));
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float base = cy - (fm.ascent + fm.descent) / 2f;
            float tx = cx + dot / 2f + px(GAP_DP);
            float maxW = r - px(10f) - tx;
            cv.drawText(fit(textPaint, QqbotIslandBridge.labelOf(code), maxW), tx, base, textPaint);
        }

        /** 展开态：左图标、中间两行字、右边均衡器、底部进度条。 */
        private void drawExpanded(Canvas cv, float l, float t, float r, float b, float a) {
            if (a <= 0.01f) return;
            float h = b - t;
            int sc = dotColor(code);

            /* ---- 左侧圆形图标底 + 状态图标 ---- */
            float icon = px(ICON_DP);
            float ix = l + px(ICON_INSET_DP);
            float iy = t + (h - icon) / 2f;
            iconRf.set(ix, iy, ix + icon, iy + icon);
            iconBgPaint.setColor(sc);
            iconBgPaint.setAlpha(Math.round(255f * ICON_BG_ALPHA * a));
            cv.drawRoundRect(iconRf, icon / 2f, icon / 2f, iconBgPaint);
            drawGlyph(cv, ix + icon / 2f, iy + icon / 2f, sc, a);

            /* ---- 右侧均衡器 ---- */
            float eqW = px(EQ_W_DP);
            float eqGap = px(EQ_GAP_DP);
            float eqBlock = EQ_COUNT * eqW + (EQ_COUNT - 1) * eqGap;
            float eqLeft = r - px(14f) - eqBlock;
            drawEqualizer(cv, eqLeft, t + h / 2f, eqW, eqGap, sc, a);

            /* ---- 中间两行字 ---- */
            float tx = ix + icon + px(TEXT_GAP_DP);
            float maxW = eqLeft - px(8f) - tx;
            String title = noticeTitle != null ? noticeTitle : QqbotIslandBridge.labelOf(code);
            String sub = noticeSub != null ? noticeSub : secondLine(code,
                    QqbotIslandBridge.durText(upSince > 0
                            ? System.currentTimeMillis() - upSince : 0L));

            titlePaint.setAlpha(alphaOf(a));
            Paint.FontMetrics tfm = titlePaint.getFontMetrics();
            Paint.FontMetrics sfm = subPaint.getFontMetrics();
            // 两行作为一块整体垂直居中；系统字号调大到碰撞时再往上让，绝不压到底部进度条
            float blockH = (tfm.descent - tfm.ascent) + px(2f) + (sfm.descent - sfm.ascent);
            float barTopY = b - px(BAR_BOTTOM_DP) - px(BAR_H_DP);
            float blockTop = t + h * 0.5f - blockH / 2f;
            if (blockTop + blockH > barTopY - px(2f)) blockTop = barTopY - px(2f) - blockH;
            if (blockTop < t + px(3f)) blockTop = t + px(3f);
            float titleBase = blockTop - tfm.ascent;
            cv.drawText(fit(titlePaint, title, maxW), tx, titleBase, titlePaint);

            subPaint.setAlpha(alphaOf(a * 0.85f));
            float subBase = titleBase + tfm.descent + px(2f) - sfm.ascent;
            cv.drawText(fit(subPaint, sub, maxW), tx, subBase, subPaint);

            /* ---- 底部 2dp 进度条 ---- */
            drawBar(cv, l, t, r, b, sc, a);
        }

        /** 均衡器：4 根，运行中按相位跳，其余状态下平躺成 3dp 的小墩子。 */
        private void drawEqualizer(Canvas cv, float left, float centerY,
                float barW, float gap, int sc, float a) {
            float minH = px(EQ_MIN_DP);
            float maxH = px(EQ_MAX_DP);
            boolean playing = "run".equals(code);
            float baseY = centerY + maxH / 2f;
            for (int i = 0; i < EQ_COUNT; i++) {
                float f = playing ? eqFactor(eqPhase, i) : 0f;
                float bh = minH + (maxH - minH) * f;
                float x = left + i * (barW + gap);
                if (playing) {
                    eqPaint.setColor(sc);
                    eqPaint.setAlpha(alphaOf(a * 0.9f));
                } else {
                    eqPaint.setColor(TEXT_COLOR);
                    eqPaint.setAlpha(alphaOf(a * 0.38f));
                }
                cv.drawRoundRect(x, baseY - bh, x + barW, baseY,
                        barW / 2f, barW / 2f, eqPaint);
            }
        }

        /** 底部进度条：外部给了 p 就用 p，否则按「还有多久收回」倒计时。 */
        private void drawBar(Canvas cv, float l, float t, float r, float b, int sc, float a) {
            float bh = px(BAR_H_DP);
            float y = b - px(BAR_BOTTOM_DP) - bh;
            float x0 = l + px(BAR_INSET_DP);
            float x1 = r - px(BAR_INSET_DP);
            if (x1 <= x0) return;

            trackPaint.setColor(TEXT_COLOR);
            trackPaint.setAlpha(alphaOf(a * 0.14f));
            cv.drawRoundRect(x0, y, x1, y + bh, bh / 2f, bh / 2f, trackPaint);

            float frac = barFraction();
            if (frac <= 0f) return;
            barPaint.setColor(sc);
            barPaint.setAlpha(alphaOf(a * 0.85f));
            float xEnd = x0 + (x1 - x0) * frac;
            if (xEnd < x0 + bh) xEnd = x0 + bh;
            cv.drawRoundRect(x0, y, xEnd, y + bh, bh / 2f, bh / 2f, barPaint);
        }

        /** 状态图标：全是 Canvas 手绘，工程没引图标库。 */
        private void drawGlyph(Canvas cv, float cx, float cy, int color, float a) {
            float s = px(9f);
            glyphPaint.setColor(color);
            glyphPaint.setAlpha(alphaOf(a));
            String c = code;
            if ("run".equals(c)) {
                // 运行中：播放三角，正好对上「听歌上岛」那个意象
                glyphPaint.setStyle(Paint.Style.FILL);
                triPath.reset();
                triPath.moveTo(cx - s * 0.45f, cy - s * 0.72f);
                triPath.lineTo(cx + s * 0.78f, cy);
                triPath.lineTo(cx - s * 0.45f, cy + s * 0.72f);
                triPath.close();
                cv.drawPath(triPath, glyphPaint);
            } else if ("boot".equals(c)) {
                // 启动中：缺一个缺口的圆圈，像在转
                glyphPaint.setStyle(Paint.Style.STROKE);
                glyphPaint.setStrokeWidth(Math.max(1f, px(1.8f)));
                arcRf.set(cx - s, cy - s, cx + s, cy + s);
                cv.drawArc(arcRf, -80f, 280f, false, glyphPaint);
                glyphPaint.setStyle(Paint.Style.FILL);
            } else if ("qr".equals(c)) {
                // 待扫码：三个定位角，一眼能认出是二维码
                glyphPaint.setStyle(Paint.Style.FILL);
                float q = s * 0.42f;
                float o = s * 0.62f;
                cv.drawRoundRect(cx - o, cy - o, cx - o + q, cy - o + q, q / 3f, q / 3f, glyphPaint);
                cv.drawRoundRect(cx + o - q, cy - o, cx + o, cy - o + q, q / 3f, q / 3f, glyphPaint);
                cv.drawRoundRect(cx - o, cy + o - q, cx - o + q, cy + o, q / 3f, q / 3f, glyphPaint);
                cv.drawRect(cx + o * 0.25f, cy + o * 0.15f, cx + o * 0.55f, cy + o * 0.45f, glyphPaint);
            } else {
                // 离线：感叹号，比叉号更像「出问题了」
                glyphPaint.setStyle(Paint.Style.FILL);
                float w2 = s * 0.17f;
                cv.drawRoundRect(cx - w2, cy - s * 0.85f, cx + w2, cy + s * 0.1f,
                        w2, w2, glyphPaint);
                cv.drawCircle(cx, cy + s * 0.6f, w2 * 1.15f, glyphPaint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawY = (int) e.getRawY();
                    startLpY = lp != null ? lp.y : 0;
                    downMs = SystemClock.elapsedRealtime();
                    dragging = false;
                    moved = false;
                    swiped = false;
                    cancelPress();
                    pressTask = new LongPress(this);
                    UI.postDelayed(pressTask, LONG_PRESS_MS);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int dy = (int) e.getRawY() - downRawY;
                    if (!dragging) {
                        if (Math.abs(dy) > dp(SWIPE_DP) && !swiped) {
                            // 上下滑 = 收起（长按之前的大幅位移都按滑动手势处理）
                            swiped = true;
                            moved = true;
                            cancelPress();
                            if (expanded || progress > 0.02f) collapseNow();
                        } else if (Math.abs(dy) > dp(TOUCH_SLOP_DP)) {
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
                    long cost = SystemClock.elapsedRealtime() - downMs;
                    boolean wasDragging = dragging;
                    dragging = false;
                    if (wasDragging && lp != null) {
                        // 拖动结束把偏移落盘：下次建窗直接用这个位置
                        saveOffsetPx(lp.y - baseTopPx);
                    } else if (e.getActionMasked() == MotionEvent.ACTION_UP
                            && !moved && !swiped && cost < LONG_PRESS_MS) {
                        onTap();
                    }
                    return true;
                }
                default:
                    return true;
            }
        }

        /** 点击：收起态点开看详情，已经展开再点就进应用（灵动岛的常规两段式）。 */
        private void onTap() {
            if (progress > 0.35f) {
                collapseNow();
                openApp();
            } else {
                expandFor(TAP_EXPAND_MS);
            }
        }

        /** 撤掉还没到点的长按：手指抬起或滑开时都必须断开，否则会「凭空」进入拖动。 */
        private void cancelPress() {
            LongPress t = pressTask;
            pressTask = null;
            if (t != null) UI.removeCallbacks(t);
        }

        /**
         * 长按到点：进入拖动。展开态先收回（高度变了要重算几何），
         * 基准跟着重算，免得一拖就跳。
         */
        void beginDrag() {
            dragging = true;
            cancelCollapse();
            expanded = false;
            animSeq++;
            if (anim != null) {
                try {
                    anim.cancel();
                } catch (Throwable ignored) {
                }
                anim = null;
            }
            progress = 0f;
            stopFrames();
            applyGeometry();
            if (lp != null) startLpY = lp.y;
        }

        /** 有事件：带着内容展开一段时间再收回（默认三秒）。 */
        void flash() {
            flash(EXPAND_MS);
        }

        void flash(long ms) {
            expandFor(ms < 500L ? 500L : ms);
        }

        /** 文本超宽就截断加省略号：覆盖层没有 TextView，自己算。 */
        private String fit(Paint p, String s, float maxW) {
            if (s == null) return "";
            if (maxW <= 0f) return "";
            if (p.measureText(s) <= maxW) return s;
            String ell = "…";
            int n = s.length();
            while (n > 0 && p.measureText(s.substring(0, n) + ell) > maxW) {
                n--;
            }
            if (n <= 0) return "";
            return s.substring(0, n) + ell;
        }
    }

    /* ================================================================
     * 具名内部类（d8 不吃匿名类）
     * ================================================================ */

    /** 弹簧插值器：把线性时间轴映射成弹簧曲线，过冲只允许在「展开」方向出现。 */
    private static final class Spring implements Interpolator {
        private final float seconds;
        private final boolean overshoot;

        Spring(float s, boolean allowOvershoot) {
            seconds = s <= 0f ? 0.26f : s;
            overshoot = allowOvershoot;
        }

        public float getInterpolation(float input) {
            float v = springAt(input * seconds, SPRING_STIFFNESS, SPRING_DAMPING);
            if (v < 0f) v = 0f;
            if (!overshoot && v > 1f) v = 1f;
            return v;
        }
    }

    /** 每帧把动画值写回 progress，再同步一次几何——宽/高/圆角/透明度都从它推出来。 */
    private static final class Tick implements ValueAnimator.AnimatorUpdateListener {
        public void onAnimationUpdate(ValueAnimator a) {
            Object v = a.getAnimatedValue();
            if (v instanceof Float) {
                progress = ((Float) v).floatValue();
            }
            if (progress < 0f) progress = 0f;
            if (progress > 1.02f) progress = 1.02f;
            applyGeometry();
            if (root != null) root.invalidate();
        }
    }

    /** 动画结束：只有「当前这一发」才收尾，被新动画打断的旧动画老老实实让位。 */
    private static final class End implements Animator.AnimatorListener {
        private final int seq;

        End(int s) {
            seq = s;
        }

        public void onAnimationStart(Animator a) {
        }

        public void onAnimationRepeat(Animator a) {
        }

        public void onAnimationCancel(Animator a) {
        }

        public void onAnimationEnd(Animator a) {
            finishAnim(seq, progress >= 0.5f ? 1f : 0f);
        }
    }

    /** 绘制帧回调：展开时一直跑，进度条的倒计时和均衡器都靠它推进。 */
    private static final class Frames implements Choreographer.FrameCallback {
        public void doFrame(long frameTimeNanos) {
            framePosted = false;
            if (root == null || progress <= 0.02f) {
                // 已经收起/拆窗：不再续帧，否则就是一个永远跑的回调
                return;
            }
            eqPhase = ((SystemClock.uptimeMillis() - eqStartMs) % EQ_PERIOD_MS)
                    / (float) EQ_PERIOD_MS;
            if (deadline > 0L && System.currentTimeMillis() > deadline) {
                // 倒计时到点还没收到 CollapseTask（主线程被占过）：这里兜一手
                collapseNow();
                return;
            }
            root.invalidate();
            postFrame();
        }
    }

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
            // 权限被系统撤销时窗口还在屏上：这里收一次网，别等下一轮 45 秒
            if (app != null && !granted(app)) {
                detach();
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

    private static final class NoticeTask implements Runnable {
        private final long ms;

        NoticeTask(long m) {
            ms = m;
        }

        public void run() {
            if (root == null) return;
            if (root instanceof CapsuleView) ((CapsuleView) root).flash(ms);
        }
    }

    private static final class DetachTask implements Runnable {
        public void run() {
            detach();
        }
    }

    private static final class AlignTask implements Runnable {
        public void run() {
            scrW = 0;
            scrH = 0;
            applyGeometry();
        }
    }

    private static final class CollapseTask implements Runnable {
        public void run() {
            collapseTask = null;
            collapseNow();
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
            scrW = 0;
            scrH = 0;
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
