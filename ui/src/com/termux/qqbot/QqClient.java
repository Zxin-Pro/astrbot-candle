package com.termux.app;

import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * 内置 WebView 的客户端。v1.1.30 起多一件事：页面加载完把「通知栏/磁贴带进来的 tab」
 * 切过去（extra tab=logs|bot）。放在 onPageFinished 里是因为 WebView 还没加载完时
 * evaluateJavascript 会直接丢掉，标签页永远切不过去。
 * d8 处理匿名内部类会崩，所以这里必须是具名顶层类（不重写任何东西时也不能省）。
 */
public class QqClient extends WebViewClient {

    private final QqbotActivity qa;

    /** 面板页（QqbotPanelActivity）用：不需要切标签 */
    public QqClient() {
        this(null);
    }

    public QqClient(QqbotActivity a) {
        qa = a;
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        if (qa == null) return;
        try {
            qa.applyPendingTab(view);
        } catch (Throwable ignored) {
        }
        try {
            qa.applyPendingStop(view);
        } catch (Throwable ignored) {
        }
        try {
            qa.applyPendingToggle(view);
        } catch (Throwable ignored) {
        }
    }
}
