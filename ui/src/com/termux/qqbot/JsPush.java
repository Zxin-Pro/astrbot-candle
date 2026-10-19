package com.termux.app;

import android.webkit.WebView;

/**
 * 主线程把一段 JSON 推给页面里的全局回调函数（window.&lt;fn&gt;）。
 *
 * 为什么不直接 runOnUiThread + 匿名 Runnable：d8 处理匿名内部类会崩，
 * 本项目所有异步任务都是独立的顶层类（见 BgTask / BootSync）。
 *
 * 安全性：fn 只取代码里写死的两个字面量（backupPicked / restorePicked），
 * 不接受任何外部输入，所以不存在把用户内容拼进 JS 的注入面；
 * json 由 JSONObject.toString() 生成，本身就是合法的 JS 表达式。
 * 页面没定义这个函数、或者页面还没加载完，都只是安静地什么都不做。
 */
final class JsPush implements Runnable {

    private final WebView web;
    private final String fn;
    private final String json;

    JsPush(WebView web, String fn, String json) {
        this.web = web;
        this.fn = fn;
        this.json = (json == null || json.length() == 0) ? "null" : json;
    }

    public void run() {
        if (web == null || fn == null || fn.length() == 0) return;
        try {
            String js = "(function(){try{var f=window." + fn + ";"
                    + "if(typeof f==='function')f(" + json + ");}catch(e){}})()";
            web.evaluateJavascript(js, null);
        } catch (Throwable ignored) {
            // 页面正在销毁 / WebView 已释放：推送失败无所谓，前端还有 poll 兜底
        }
    }
}
