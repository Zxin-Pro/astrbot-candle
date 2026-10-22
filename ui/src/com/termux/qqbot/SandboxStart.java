package com.termux.app;

import android.content.ComponentName;
import android.content.Context;
import android.service.quicksettings.TileService;

import java.io.File;

/**
 * 为什么需要它：磁贴和桌面小组件点「启动」时要真的把沙盒拉起来（proot 冷启动十几秒），
 * 这活儿只能在后台线程做；失败了还要把守护开关回滚、并给用户一个提示。
 * 成功则顺带补一次看门狗，再通知磁贴/小组件刷新状态。
 * 它同时承担「用户显式启动 → 清掉 state/offline 标记」这一步：不清的话守护循环看见
 * 下线标记会一直拒绝拉起，用户点十次「开」都没反应。
 * d8 处理匿名内部类会崩，所以必须是具名顶层类。
 */
public class SandboxStart implements Runnable {

    private final Context ctx;
    /** 磁贴入口传自己（用于回主线程弹 Toast）；小组件/服务入口传 null。 */
    private final QqbotTileService tile;
    private final String who;

    public SandboxStart(Context c, QqbotTileService t, String source) {
        ctx = c;
        tile = t;
        who = source == null ? "" : source;
    }

    public void run() {
        File filesDir = ctx.getFilesDir();
        // 用户明确点了「启动」：先把「紧急下线」标记清掉，否则守护循环会一直拒绝拉起。
        try {
            StateIO.delete(StateIO.file(filesDir, "offline"));
        } catch (Throwable ignored) {
        }
        boolean ok = false;
        StringBuilder note = new StringBuilder();
        try {
            ok = QqbotShell.startSandbox(filesDir, note);
        } catch (Throwable t) {
            ok = false;
            note.append(t.toString());
        }
        boolean alive = ok;
        if (!alive) {
            try {
                alive = QqbotShell.pidAlive(new File(QqbotShell.qqbotDir(filesDir), "supervisor.pid"), "proot");
            } catch (Throwable ignored) {
            }
        }
        if (alive) {
            try {
                QqbotShell.startWatchdog(filesDir, null);
            } catch (Throwable ignored) {
            }
        } else {
            // 回滚：沙盒都没起来，不该留下「开关说开着、其实没跑」的假状态
            try {
                QqbotGuardService.setGuard(ctx, false);
            } catch (Throwable ignored) {
            }
        }
        String msg = alive
                ? ("已启动沙盒" + (who.isEmpty() ? "" : "（" + who + "）"))
                : ("启动失败：" + note);
        try {
            QqbotGuardService.lastNote = msg;
        } catch (Throwable ignored) {
        }
        if (tile != null) TileToast.show(tile, msg);
        QqbotWidgetProvider.refreshAsync(ctx);
        // 让磁贴自己走一遍 onStartListening → 主线程刷状态（API 24+）
        try {
            TileService.requestListeningState(ctx, new ComponentName(ctx, QqbotTileService.class));
        } catch (Throwable ignored) {
        }
    }
}
