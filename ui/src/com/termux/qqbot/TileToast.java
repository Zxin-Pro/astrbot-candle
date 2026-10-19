package com.termux.app;

import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * 为什么需要它：磁贴的启动/停止是在后台线程算出来的结论，但 Toast 必须在主线程弹，
 * 而且 TileService 不是 Activity（没有 runOnUiThread），所以统一走这个一次性任务。
 * 它只做一件事、且失败也不抛（磁贴提示不该反过来把进程带崩）。
 * d8 处理匿名内部类会崩，所以必须是具名顶层类。
 */
public class TileToast implements Runnable {

    private final QqbotTileService svc;
    private final String msg;

    public TileToast(QqbotTileService s, String m) {
        svc = s;
        msg = m == null ? "" : m;
    }

    /** 任意线程调用；内部切回主线程。 */
    static void show(QqbotTileService s, String m) {
        try {
            Handler h = new Handler(Looper.getMainLooper());
            h.post(new TileToast(s, m));
        } catch (Throwable ignored) {
        }
    }

    public void run() {
        try {
            Toast.makeText(svc, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }
}
