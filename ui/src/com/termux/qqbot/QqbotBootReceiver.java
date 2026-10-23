package com.termux.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机 / 应用更新后自动把守护服务拉起来，这样不打开应用机器人也能继续跑。
 * 注意：这里只能起前台服务（后台应用直接 startService 在 Android 8+ 会抛
 * IllegalStateException），QqbotGuardService.start 里已经按版本号做了区分。
 *
 * v1.1.30 起多做两件事：①把这次开机记进 state/boot.json（bootInfo() 要显示）；
 * ②API 31+ 起前台服务有「后台启动限制」，BOOT_COMPLETED 本身在豁免名单里，
 * 但个别 ROM 的「快启动」广播和覆盖安装广播会抛 ForegroundServiceStartNotAllowedException，
 * 抛了就隔 1.2 秒补试一次（仍在开机豁免窗口内），再失败就安静放弃，等用户打开应用。
 *
 * v1.2.11：先看 state/offline 标记。用户按过通知栏「关闭进程」或界面上的「紧急下线」
 * 就会留下它，那是明确的「别再自己起来」——本次开机 / 覆盖安装只记一条日志，不拉起守护。
 * 标记在这里绝不能删：只有用户显式「启动」时才由 SandboxStart / start-sandbox.sh 清掉。
 */
public class QqbotBootReceiver extends BroadcastReceiver {

    public void onReceive(Context c, Intent i) {
        String a = (i == null || i.getAction() == null) ? "" : i.getAction();
        boolean isBoot = Intent.ACTION_BOOT_COMPLETED.equals(a)
                || "android.intent.action.QUICKBOOT_POWERON".equals(a)
                || "com.termux.app.QUICKBOOT".equals(a);
        if (!isBoot && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        // 只有真开机才更新「上次开机时间」；覆盖安装记成开机时间会让界面显示错乱。
        if (isBoot) {
            try {
                BootState.noteBoot(c.getFilesDir());
            } catch (Throwable ignored) {
            }
        }
        // 用户主动关闭过（state/offline 还在）：本次开机 / 覆盖安装不拉起守护服务，
        // 也不删标记，只留一条日志方便排障。否则「关闭进程」之后一重启又被拉起来。
        boolean closedByUser = false;
        try {
            closedByUser = StateIO.file(c.getFilesDir(), "offline").isFile();
        } catch (Throwable ignored) {
        }
        if (closedByUser) {
            android.util.Log.i("QqbotBoot", "state/offline 存在：用户已主动关闭，本次开机不拉起守护");
            return;
        }
        boolean ok;
        try {
            ok = QqbotGuardService.applyEx(c);
        } catch (Throwable t) {
            ok = false;
        }
        if (ok) return;
        try {
            Thread th = new Thread(new BootRetry(c), "qqbot-bootretry");
            th.setDaemon(true);
            th.start();
        } catch (Throwable ignored) {
        }
    }
}
