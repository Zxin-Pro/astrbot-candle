package com.termux.app;

import android.content.Context;

/** 心跳体检线程的具名载体（工程约定：d8 不接受匿名内部类）。 */
public class KeepAliveTask implements Runnable {

    private final Context ctx;

    public KeepAliveTask(Context c) {
        ctx = c;
    }

    public void run() {
        try {
            KeepAlive.rescue(ctx);
        } catch (Throwable t) {
            android.util.Log.w("QqbotKeepAlive", "心跳任务异常：" + t);
        }
    }
}