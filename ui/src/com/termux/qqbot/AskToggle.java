package com.termux.app;

import android.webkit.WebView;

/**
 * 让页面处理「启停切换」意图的一次性任务（v1.1.30 功能 9 / 12 / 13）。
 *
 * 为什么统一走页面：小组件、磁贴、通知栏这些入口拿不到最新状态（小组件的 PendingIntent
 * 在渲染时就定死了），而 targetSdk 28 下服务里 startActivity 会被系统以
 * "Background activity launch blocked" 直接拦掉。所以入口只负责把面板叫到前台并带
 * toggle=1，由页面读一次真实状态再决定「弹下线确认」还是「直接启动」。
 *
 * 与 AskStop 同样用具名类（d8 处理匿名内部类会崩）并重试（页面还没 finished 时
 * evaluateJavascript 会被丢弃）。
 */
public class AskToggle implements Runnable {

    private final WebView web;
    private final int left;

    public AskToggle(WebView w, int left) {
        this.web = w;
        this.left = left;
    }

    public void run() {
        if (web == null) return;
        try {
            web.evaluateJavascript("(function(){try{if(window.askToggle)window.askToggle();}catch(e){}})()", null);
        } catch (Throwable ignored) {
        }
        if (left > 1) {
            try {
                web.postDelayed(new AskToggle(web, left - 1), 800L);
            } catch (Throwable ignored) {
            }
        }
    }
}
