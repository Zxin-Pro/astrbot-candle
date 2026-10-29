package com.termux.app;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

/**
 * 「桌面小组件」桥（JS 名 AndroidWidget，更多页 #widgetCard 用）。
 *
 * 一句话：把「添加小部件 / 立刻刷新 / 现在有没有加上」这三件事从网页里做掉，
 * 用户不用去桌面上翻小部件列表找「烛之控制台」。
 *
 * 注意：本类只负责「请求桌面添加」和「查有没有实例」；真正画卡片的是
 * QqbotWidgetProvider（它读 state/ui.json，在后台线程渲染 RemoteViews）。
 * 如果安装包的清单里没有这个 receiver（1.3.0 起被 AV 规则整段删掉过），
 * 桌面根本找不到这个小部件——status() 会如实报 pinned=false，pin() 也会失败。
 *
 * d8 不接受 lambda，本文件不出现匿名内部类。
 */
public class QqbotWidgetBridge {

    private final QqbotActivity act;
    private final Context ctx;

    public QqbotWidgetBridge(QqbotActivity act) {
        this.act = act;
        Context c = act;
        try {
            Context app = act.getApplicationContext();
            if (app != null) c = app;
        } catch (Throwable ignored) {
        }
        this.ctx = c;
    }

    /* ======================= 对外接口 ======================= */

    /**
     * 让桌面弹「是否添加」确认框（安卓 8 起才有这个能力，且要桌面支持）。
     * 不支持时给一句人话，让用户长按桌面手动加，而不是什么都不发生。
     */
    @JavascriptInterface
    public String pin() {
        JSONObject out = new JSONObject();
        try {
            if (Build.VERSION.SDK_INT < 26) {
                return fail("这个安卓版本不支持一键添加：长按桌面空白处 → 小部件 → 找到「烛之控制台」拖到桌面");
            }
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            if (mgr == null) {
                return fail("系统的小组件服务没起来，重启手机再试");
            }
            if (!mgr.isRequestPinAppWidgetSupported()) {
                return fail("这个桌面不支持一键添加：长按桌面空白处 → 小部件 → 找到「烛之控制台」拖到桌面");
            }
            ComponentName me = new ComponentName(ctx, QqbotWidgetProvider.class);
            boolean asked = mgr.requestPinAppWidget(me, null, null);
            if (!asked) {
                return fail("桌面没接受添加请求：长按桌面空白处手动添加一次");
            }
            out.put("ok", true);
            out.put("msg", "已请求添加到桌面");
            return out.toString();
        } catch (Throwable t) {
            return fail("添加到桌面失败：" + brief(t));
        }
    }

    /** 立刻重画一次（守护每轮还会自己推，这里给用户一个「我现在就要看到最新」的按钮）。 */
    @JavascriptInterface
    public String refresh() {
        JSONObject out = new JSONObject();
        try {
            int n = pinnedCount();
            // 读盘 + 推送放后台线程，主线（JS 桥线程）不留住
            QqbotWidgetProvider.refreshAsync(ctx);
            out.put("ok", true);
            out.put("msg", n > 0 ? "已刷新" : "桌面上还没有小部件：先点「添加到桌面」");
            return out.toString();
        } catch (Throwable t) {
            return fail("刷新小部件失败：" + brief(t));
        }
    }

    /** supported=这台设备/桌面能不能一键添加；pinned=桌面上现在有没有这个小组件。 */
    @JavascriptInterface
    public String status() {
        JSONObject out = new JSONObject();
        try {
            boolean supported = false;
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
                    supported = mgr != null && mgr.isRequestPinAppWidgetSupported();
                }
            } catch (Throwable ignored) {
            }
            out.put("ok", true);
            out.put("supported", supported);
            out.put("pinned", pinnedCount() > 0);
            return out.toString();
        } catch (Throwable t) {
            return fail("读取小部件状态失败：" + brief(t));
        }
    }

    /* ======================= 内部 ======================= */

    private int pinnedCount() {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            if (mgr == null) return 0;
            ComponentName me = new ComponentName(ctx, QqbotWidgetProvider.class);
            int[] ids = mgr.getAppWidgetIds(me);
            return ids == null ? 0 : ids.length;
        } catch (Throwable t) {
            return 0;
        }
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
