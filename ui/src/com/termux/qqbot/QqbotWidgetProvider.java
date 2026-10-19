package com.termux.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.Build;
import android.util.Log;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.io.File;

/**
 * 为什么需要它：用户希望不解锁手机也能看到「几个实例在线、最近一次自检是什么时候」，
 * 并且一键启停守护。桌面小组件读 state/ui.json（守护循环每轮写）即可，不需要常驻进程。
 * 有一点必须小心：桌面进程不会跟应用一起被杀，所以 onUpdate 里绝不能读盘/联网——
 * 一律丢后台线程（WidgetRefresh），只把结果通过 RemoteViews 推给桌面。
 * 布局与 id 由 res/layout/widget_status.xml 提供，用 getIdentifier 按名取，
 * 这样资源还没注入时本类照样能编译、运行时也不会崩（只是先不渲染）。
 * d8 处理匿名内部类会崩，本文件不出现匿名类。
 */
public class QqbotWidgetProvider extends AppWidgetProvider {

    /** 小组件「启动/停止」按钮：PendingIntent.getService 打回 QqbotGuardService。 */
    public static final String ACTION_WIDGET_TOGGLE = "com.termux.app.WIDGET_TOGGLE";
    /** 协议文档里写的旧名字，两个字符串都认，免得前端/桌面缓存的旧 Intent 失效。 */
    public static final String ACTION_WIDGET_TOGGLE_LEGACY = "com.termux.qqbot.WIDGET_TOGGLE";

    private static final String TAG = "qqbot-widget";
    private static final int REQ_OPEN = 31;
    private static final int REQ_TOGGLE = 32;
    private static final int REQ_TOGGLE_ACT = 33;

    /** 上一次渲染用的状态签名：状态没变就不重绘，避免每 60 秒把桌面唤醒一次。 */
    private static volatile String lastSig = "";

    public void onUpdate(Context c, AppWidgetManager mgr, int[] ids) {
        refreshAsync(c);
    }

    /**
     * 兼容协议文档里「点 toggle 发广播、由守护服务处理」的写法：
     * 桌面布局如果哪天改用 PendingIntent.getBroadcast，这里把广播转成服务 Intent，
     * 逻辑与按钮完全一致（判断依据仍是 guard.off 标记）。
     */
    public void onReceive(Context c, Intent i) {
        String a = (i == null || i.getAction() == null) ? "" : i.getAction();
        if (ACTION_WIDGET_TOGGLE.equals(a) || ACTION_WIDGET_TOGGLE_LEGACY.equals(a)) {
            try {
                Intent s = new Intent(c, QqbotGuardService.class);
                s.setAction(ACTION_WIDGET_TOGGLE);
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s);
                else c.startService(s);
            } catch (Throwable ignored) {
            }
            return;
        }
        super.onReceive(c, i);
    }

    public void onEnabled(Context c) {
        Log.i(TAG, "widget enabled");
        refreshAsync(c);
    }

    public void onDisabled(Context c) {
        Log.i(TAG, "widget disabled");
    }

    public void onDeleted(Context c, int[] ids) {
        Log.i(TAG, "widget deleted: " + (ids == null ? 0 : ids.length));
    }

    /** 主线程安全：起一个后台线程去读 ui.json 再渲染。onDestroy / 广播里都调它。 */
    static void refreshAsync(Context c) {
        try {
            Thread th = new Thread(new WidgetRefresh(c), "qqbot-widget");
            th.setDaemon(true);
            th.start();
        } catch (Throwable ignored) {
        }
    }

    /** 守护线程每轮调用：只有在线数/服务状态变了才真的重绘（本来就在后台线程，直接读盘）。 */
    static void refreshIfChanged(Context c, int online, int total) {
        String sig = online + "/" + total + "/" + QqbotGuardService.guardRunning;
        if (sig.equals(lastSig)) return;
        // 注意：lastSig 由 renderAll 在「真的推送到桌面之后」才更新，
        // 这样布局还没注入/桌面暂时没有实例时，下一轮还会重试，而不是被一个假签名永久跳过。
        renderAll(c);
    }

    /** 真正渲染：遍历所有 widget id 更新一遍。必须在后台线程调用（会读盘）。 */
    static void renderAll(Context c) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(c);
            if (mgr == null) return;
            ComponentName me = new ComponentName(c, QqbotWidgetProvider.class);
            int[] ids = mgr.getAppWidgetIds(me);
            if (ids == null || ids.length == 0) return;
            RemoteViews v = build(c);
            if (v == null) return;
            for (int i = 0; i < ids.length; i++) {
                try {
                    mgr.updateAppWidget(ids[i], v);
                } catch (Throwable ignored) {
                }
            }
            lastSig = sigOf(c);
        } catch (Throwable ignored) {
        }
    }

    /* ---------- 渲染 ---------- */

    private static RemoteViews build(Context c) {
        try {
            Resources r = c.getResources();
            String pkg = c.getPackageName();
            int layout = r.getIdentifier("widget_status", "layout", pkg);
            if (layout == 0) {
                Log.i(TAG, "res/layout/widget_status.xml 还没注入，跳过渲染");
                return null;
            }
            File filesDir = c.getFilesDir();
            boolean alive = QqbotGuardService.guardRunning;
            JSONObject ui = readUi(filesDir);
            int online = ui == null ? -1 : ui.optInt("online", -1);
            int total = ui == null ? -1 : ui.optInt("total", -1);
            long updated = ui == null ? 0L : ui.optLong("updated", 0L);
            boolean serviceAlive = ui == null ? alive : ui.optBoolean("serviceAlive", alive);
            if (ui == null) {
                // ui.json 还不存在（守护刚装上/刚开启）：用 instances.json 兜个底，
                // 免得桌面卡片在头一分钟一直显示「等待自检」。
                int[] ic = countInstances(filesDir);
                if (ic[1] > 0) {
                    online = ic[0];
                    total = ic[1];
                }
            }

            RemoteViews v = new RemoteViews(pkg, layout);
            int idTitle = r.getIdentifier("w_title", "id", pkg);
            int idState = r.getIdentifier("w_state", "id", pkg);
            int idTime = r.getIdentifier("w_time", "id", pkg);
            int idToggle = r.getIdentifier("w_toggle", "id", pkg);
            int idRoot = r.getIdentifier("w_root", "id", pkg);

            String state;
            if (!alive || !serviceAlive) {
                state = "未运行";
            } else if (online >= 0) {
                state = total >= 0 ? ("在线 " + online + "/" + total) : ("在线 " + online);
            } else {
                state = "运行中";
            }
            String time = updated > 0L ? ("最近更新 " + hhmm(updated)) : "等待自检";

            if (idTitle != 0) v.setTextViewText(idTitle, "烛之控制台");
            /* 「在跑」以沙盒里的实例为准，不看守护开关：守护服务在跑但沙盒被紧急下线时，
               按钮必须显示「启动」而不是「停止」（v1.1.30 实测踩过：界面说停止、点了却是启动）。 */
            boolean running = alive && serviceAlive && online > 0;
            if (idState != 0) {
                v.setTextViewText(idState, state);
                // 在线绿 / 离线灰；不靠颜色单独传达状态（旁边有文字）
                v.setTextColor(idState, running ? Color.parseColor("#16A34A")
                        : Color.parseColor("#94A3B8"));
            }
            if (idTime != 0) v.setTextViewText(idTime, time);
            if (idToggle != 0) v.setTextViewText(idToggle, alive && serviceAlive && online > 0 ? "停止" : "启动");

            int clickTarget = idRoot != 0 ? idRoot : (idState != 0 ? idState : idTitle);
            if (clickTarget != 0) v.setOnClickPendingIntent(clickTarget, openApp(c));
            if (idToggle != 0) {
                /* 统一入口（v1.1.30）：不再按「运行中 / 未运行」在渲染时选路径。
                   小组件的 PendingIntent 是渲染那一刻定死的，卡片没刷新就会指向过期分支；
                   而 targetSdk 28 下由服务 startActivity 会被系统以 BAL 拦掉。
                   所以按钮一律拉起面板并带 toggle=1，由页面读一次真实状态决定。 */
                v.setOnClickPendingIntent(idToggle, toggleIntent(c));
            }
            return v;
        } catch (Throwable t) {
            Log.w(TAG, "build 失败: " + t);
            return null;
        }
    }

    private static JSONObject readUi(File filesDir) {
        String s = StateIO.read(UiState.file(filesDir));
        if (s == null) return null;
        try {
            return new JSONObject(s);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** [在线数, 总数]：从 state/instances.json 里数（只有 ui.json 缺失时才用）。 */
    private static int[] countInstances(File filesDir) {
        int on = 0;
        int tot = 0;
        try {
            String s = StateIO.read(StateIO.file(filesDir, "instances.json"));
            if (s == null) return new int[] { -1, 0 };
            JSONObject o = new JSONObject(s);
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                JSONObject b = o.optJSONObject(it.next());
                if (b == null) continue;
                tot++;
                if (b.optBoolean("alive", false)) on++;
            }
        } catch (Throwable ignored) {
        }
        return new int[] { on, tot };
    }

    private static String sigOf(Context c) {
        JSONObject ui = readUi(c.getFilesDir());
        int on = ui == null ? -1 : ui.optInt("online", -1);
        int tot = ui == null ? -1 : ui.optInt("total", -1);
        return on + "/" + tot + "/" + QqbotGuardService.guardRunning;
    }

    private static String hhmm(long epochSec) {
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US);
            return f.format(new java.util.Date(epochSec * 1000L));
        } catch (Throwable t) {
            return "";
        }
    }

    /* ---------- 点击 ---------- */

    private static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, QqbotActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra("tab", "home");
        return PendingIntent.getActivity(c, REQ_OPEN, i, PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent toggle(Context c) {
        Intent i = new Intent(c, QqbotGuardService.class);
        i.setAction(ACTION_WIDGET_TOGGLE);
        // 桌面点击是「用户发起」，但 Android 8+ 对后台 startService 有硬限制，
        // 用 getForegroundService 最稳（服务 onCreate 里马上 startForeground）。
        if (Build.VERSION.SDK_INT >= 26) {
            return PendingIntent.getForegroundService(c, REQ_TOGGLE, i, PendingIntent.FLAG_IMMUTABLE);
        }
        return PendingIntent.getService(c, REQ_TOGGLE, i, PendingIntent.FLAG_IMMUTABLE);
    }

    /** 「启动 · 停止」按钮：拉起面板并带 toggle=1，由页面判状态（见 build() 里的说明）。 */
    private static PendingIntent toggleIntent(Context c) {
        Intent i = new Intent(c, QqbotActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra("toggle", true);
        return PendingIntent.getActivity(c, REQ_TOGGLE_ACT, i, PendingIntent.FLAG_IMMUTABLE);
    }
}
