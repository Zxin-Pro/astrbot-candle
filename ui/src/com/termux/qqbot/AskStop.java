package com.termux.app;

import android.webkit.WebView;

/**
 * 让页面弹「紧急下线」确认框的一次性任务（v1.1.30 功能 9）。
 *
 * 为什么单独一个类：d8 处理匿名内部类会崩（见 QqClient 里的说明），所以只能用具名类。
 * 为什么重试：WebView 还没 onPageFinished 时 evaluateJavascript 会被直接丢弃，
 * 通知栏/小组件点进来正好赶上这种时刻，弹不出来就等于没有二次确认。
 */
public class AskStop implements Runnable {

    private final WebView web;
    private final int left;

    public AskStop(WebView w, int left) {
        this.web = w;
        this.left = left;
    }

    public void run() {
        if (web == null) return;
        try {
            web.evaluateJavascript("(function(){try{if(window.askStop)window.askStop();}catch(e){}})()", null);
        } catch (Throwable ignored) {
        }
        if (left > 1) {
            try {
                web.postDelayed(new AskStop(web, left - 1), 800L);
            } catch (Throwable ignored) {
            }
        }
    }
}
