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
