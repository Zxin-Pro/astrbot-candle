package com.termux.app;

import android.content.Context;

/**
 * 为什么需要它：API 31+ 在后台起前台服务会抛 ForegroundServiceStartNotAllowedException。
 * BOOT_COMPLETED 本身在豁免名单里，但个别 ROM（以及「快启动」类自定义广播）不保证，
 * 一旦抛了，开机自愈就彻底失败、只能等用户手动点开应用。这里在收到广播失败后
 * 隔 1.2 秒再补一刀（仍处在开机豁免窗口内），还不行就安静放弃，交给下次打开应用。
 * d8 处理匿名内部类会崩，所以必须是具名顶层类。
 */
public class BootRetry implements Runnable {

    private final Context ctx;

    public BootRetry(Context c) {
        ctx = c;
    }

    public void run() {
        try {
            Thread.sleep(1200L);
        } catch (InterruptedException ignored) {
            return;
        }
        // 这 1.2 秒里用户可能刚按了通知栏「关闭进程」（会写 state/offline）：
        // 标记在就不要再补这一刀，否则刚关掉的守护又回来了。
        try {
            if (StateIO.file(ctx.getFilesDir(), "offline").isFile()) return;
        } catch (Throwable ignored) {
        }
        try {
            QqbotGuardService.apply(ctx);
        } catch (Throwable ignored) {
        }
    }
}
