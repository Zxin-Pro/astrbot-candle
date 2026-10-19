package com.termux.app;

import android.content.Context;

import java.io.File;

/**
 * 为什么需要它：服务被销毁时要把 state/ui.json 里的 serviceAlive 改成 false，
 * 否则桌面小组件/磁贴会一直以为守护还活着（第二批要靠这个字段决定「启」还是「停」）。
 * 但 onDestroy 跑在主线程，不能在那里写磁盘，所以挪到这个一次性的后台任务里；
 * 写完顺带把小组件重绘一次——否则用户关掉守护后，桌面卡片还停在「在线 1/1」。
 * d8 处理匿名内部类会崩，所以必须是具名顶层类。
 */
public class StateFlush implements Runnable {

    private final Context ctx;
    private final File filesDir;
    private final int online;
    private final int total;
    private final boolean alive;

    public StateFlush(Context ctx, int online, int total, boolean alive) {
        this.ctx = ctx;
        this.filesDir = ctx.getFilesDir();
        this.online = online;
        this.total = total;
        this.alive = alive;
    }

    public void run() {
        try {
            // 内存快照还没算过（-1）时以磁盘上那份为准：服务停掉不代表沙盒停了
            // （用户关了守护、看门狗仍然活着），把 online 抹成 0 会让小组件显示假数据。
            int on = online;
            if (on < 0) on = UiState.readOnline(filesDir);
            if (on < 0) on = 0;
            int tot = total;
            if (tot < 0) tot = 0;
            UiState.write(filesDir, on, tot, alive);
        } catch (Throwable ignored) {
        }
        // 状态落盘之后再刷小组件：本方法已经在后台线程，直接渲染即可
        try {
            QqbotWidgetProvider.renderAll(ctx);
        } catch (Throwable ignored) {
        }
    }
}
