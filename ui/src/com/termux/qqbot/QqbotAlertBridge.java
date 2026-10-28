package com.termux.app;

import android.content.Context;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

/**
 * 告警设置的桥（window.AndroidAlert）：get / set / mute / test / weekly / weeklyPush。
 *
 * 为什么这么薄：真正的状态机（分渠道、免打扰、升级、模型日志 tail、周报采样）全在
 * QqbotAlertCenter 与 QqbotWeeklyReport 里，那边还要被守护服务直接调（raise/init），
 * 桥这一层只做「转成 JSON 字符串 + 兜异常」，多一行逻辑都可能两边跑偏。
 *
 * 为什么构造时顺手调一次 QqbotAlertCenter.init：用户可能只是打开 App 看一眼（守护服务没起来），
 * 这时也得有渠道、也得能「试一下」。init 是幂等的，多调几次没有任何副作用。
 *
 * 所有方法都吞异常：桥跑在 WebView 的 JS 线程上，异常抛出去会让 Java 到 JS 的返回值变成 null，
 * 前端 JSON.parse(null) 直接报错，整页白屏。
 * 不写方法引用、不写匿名内部类（d8 对它们不友好）。
 */
public class QqbotAlertBridge {

    private final Context ctx;

    /** 界面注入用：QqbotActivity 传 this 即可（与其它桥一致）。 */
    public QqbotAlertBridge(QqbotActivity act) {
        this.ctx = act;
        try {
            if (act != null) QqbotAlertCenter.init(act);
        } catch (Throwable ignored) {
        }
    }

    /** 备用构造：允许直接塞一个 Context（测试或将来在服务里 new）。 */
    public QqbotAlertBridge(Context c) {
        this.ctx = c;
        try {
            if (c != null) QqbotAlertCenter.init(c);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 读告警设置：
     * {"ok":true,"mutedUntil":0,"prefs":{五类开关+escalate+quietFrom+quietTo},"last":[...]}
     * last 里最新的一条在最前面，最多 12 条（页面自己截 5 条）。
     */
    @JavascriptInterface
    public String get() {
        try {
            return QqbotAlertCenter.prefsJson(ctx);
        } catch (Throwable t) {
            return fail("读告警设置失败：" + t);
        }
    }

    /** 部分更新：只认传进来的键（五类开关 / escalate / quietFrom / quietTo）。 */
    @JavascriptInterface
    public String set(String json) {
        try {
            return QqbotAlertCenter.prefsUpdate(ctx, json);
        } catch (Throwable t) {
            return fail("保存告警设置失败：" + t);
        }
    }

    /** 静音 minutes 分钟（通知里的按钮是 60）；<=0 表示立刻解除，返回 {"ok":true,"mutedUntil":0}。 */
    @JavascriptInterface
    public String mute(int minutes) {
        try {
            return QqbotAlertCenter.muteFor(ctx, minutes);
        } catch (Throwable t) {
            return fail("静音失败：" + t);
        }
    }

    /** 发一条测试告警（无视开关与免打扰，方便用户确认通知权限）；cat 决定标题文案。 */
    @JavascriptInterface
    public String test(String cat) {
        try {
            QqbotAlertCenter.raiseTest(ctx, cat);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            return o.toString();
        } catch (Throwable t) {
            return fail("发测试告警失败：" + t);
        }
    }

    /** 最近 7 天的运行周报（不推送，只取数据）。 */
    @JavascriptInterface
    public String weekly() {
        try {
            return QqbotWeeklyReport.weeklyJson(ctx);
        } catch (Throwable t) {
            return fail("读周报失败：" + t);
        }
    }

    /** 立刻推一条周报通知（界面的「现在推送」按钮）。 */
    @JavascriptInterface
    public String weeklyPush() {
        try {
            return QqbotWeeklyReport.weeklyPush(ctx);
        } catch (Throwable t) {
            return fail("推送周报失败：" + t);
        }
    }

    private static String fail(String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("err", msg == null ? "" : msg);
            return o.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"err\":\"未知错误\"}";
        }
    }
}
