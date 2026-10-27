package com.termux.app;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

/**
 * 界面上「通知」开关的桥（window.AndroidNotify）。
 *
 * 这四个开关只落在 SharedPreferences 里，真正发通知的是 QqbotGuardService
 * 的守护线程：每 45 秒探一次端口，只有「在线 ↔ 掉线」跃迁、或者二维码重新生成
 * 时才发一条，详见 QqbotGuardService.statusTick()。
 *
 * 为什么开关不放 bots.json：bots.json 是沙盒里的脚本也在读的配置文件，
 * 手机本地的通知偏好跟沙盒无关，塞进去只会让两边格式互相牵制；prefs 写起来
 * 也不会因为他处同时在改 bots.json 而互相覆盖。
 *
 * 所有方法都吞异常：桥运行在 WebView 的 JS 线程上，抛出去会让
 * Java -> JS 的返回值变成 null，前端 JSON.parse(null) 直接报错整页白屏。
 *
 * d8 处理匿名内部类会崩，本文件不出现匿名类。
 */
public class QqbotNotifyBridge {

    private static final String PREF = "qqbot_notify";
    private static final String CH = "qqbot_status";

    private final Activity act;

    public QqbotNotifyBridge(Activity act) {
        this.act = act;
    }

    /**
     * 返回 {"ok":true,"master":true,"online":true,"offline":true,"qr":true,
     * "channel":"qqbot_status","sys":true,"importance":3}
     *
     * sys 是系统级开关（设置里的「允许通知」），importance 是 qqbot_status
     * 渠道的当前重要度（-1 = 渠道还没建，0 = 用户把渠道关了）。
     * 前端按这两个值决定要不要提示「去系统设置里打开通知」。
     */
    @JavascriptInterface
    public String config() {
        JSONObject o = new JSONObject();
        try {
            SharedPreferences sp = act.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            o.put("ok", true);
            o.put("master", sp.getBoolean("notify.master", true));
            o.put("online", sp.getBoolean("notify.online", true));
            o.put("offline", sp.getBoolean("notify.offline", true));
            o.put("qr", sp.getBoolean("notify.qr", true));
            o.put("channel", CH);
            boolean sys = true;
            int imp = -1;
            NotificationManager nm = (NotificationManager) act.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                try {
                    sys = nm.areNotificationsEnabled();
                } catch (Throwable ignored) {
                }
                if (Build.VERSION.SDK_INT >= 26) {
                    try {
                        NotificationChannel c = nm.getNotificationChannel(CH);
                        if (c != null) imp = c.getImportance();
                    } catch (Throwable ignored) {
                    }
                }
            }
            o.put("sys", sys);
            o.put("importance", imp);
        } catch (Throwable t) {
            try {
                o.put("ok", false);
                o.put("err", shortErr(t));
            } catch (Throwable ignored) {
            }
        }
        return o.toString();
    }

    /** set("master"|"online"|"offline"|"qr", on)：非法 kind 直接忽略，不抛。 */
    @JavascriptInterface
    public void set(String kind, boolean on) {
        try {
            String k = norm(kind);
            if (k == null) return;
            act.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putBoolean("notify." + k, on).apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 试发一条（忽略开关），给界面上「试一下」按钮用。
     * kind = online|offline|qr，其它值按 online 处理。
     */
    @JavascriptInterface
    public void test(String kind) {
        try {
            String k = norm(kind);
            // master 不是通知类型；空串/非法值统一按「已上线」示例发，避免误发一条「掉线了」
            if (k == null || "master".equals(k)) k = "online";
            QqbotGuardService.postStatus(act, k, true);
        } catch (Throwable ignored) {
        }
    }

    /** 白名单化 kind：只认四个开关名，其余返回 null。 */
    private static String norm(String kind) {
        if (kind == null) return null;
        String k = kind.trim().toLowerCase();
        if ("master".equals(k) || "online".equals(k) || "offline".equals(k) || "qr".equals(k)) return k;
        return null;
    }

    /** 异常消息里可能带引号/换行，落到 JSON 里会坏，只留类名。 */
    private static String shortErr(Throwable t) {
        if (t == null) return "";
        String n = t.getClass().getSimpleName();
        return n == null || n.length() == 0 ? "error" : n;
    }
}
