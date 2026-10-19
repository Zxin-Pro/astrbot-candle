package com.termux.app;

import android.content.Context;

/**
 * 为什么需要它：QqbotWidgetProvider.onUpdate / onDestroy 都在主线程，
 * 而渲染小组件要读 state/ui.json（磁盘 IO），违反「主线程不做 IO」的硬约束。
 * 这个一次性后台任务把渲染挪出主线程；它自己不抛异常（renderAll 内部已兜住）。
 * d8 处理匿名内部类会崩，所以必须是具名顶层类。
 */
public class WidgetRefresh implements Runnable {

    private final Context ctx;

    public WidgetRefresh(Context c) {
        ctx = c.getApplicationContext() == null ? c : c.getApplicationContext();
    }

    public void run() {
        QqbotWidgetProvider.renderAll(ctx);
    }
}
