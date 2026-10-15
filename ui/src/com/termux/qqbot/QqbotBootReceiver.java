package com.termux.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机 / 应用更新后自动把守护服务拉起来，这样不打开应用机器人也能继续跑。
 * 注意：这里只能起前台服务（后台应用直接 startService 在 Android 8+ 会抛
 * IllegalStateException），QqbotGuardService.start 里已经按版本号做了区分。
 */
public class QqbotBootReceiver extends BroadcastReceiver {

    public void onReceive(Context c, Intent i) {
        String a = (i == null || i.getAction() == null) ? "" : i.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(a)
                && !"com.termux.app.QUICKBOOT".equals(a)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        try {
            QqbotGuardService.apply(c);
        } catch (Throwable ignored) {
        }
    }
}
