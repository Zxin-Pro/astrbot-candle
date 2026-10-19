package com.termux.app;

import java.io.File;

/**
 * 后台跑 backup.sh。打包一个几百 MB 的 qqinst 要几十秒到几分钟，
 * 放在 JS 桥线程上会把整个页面卡死，所以必须独立线程。
 * 真正的解析与落盘都在 BackupFlow 里，这里只负责「把活丢过去」。
 */
final class BackupTask implements Runnable {

    private final File filesDir;
    private final String scope;
    private final File out;

    BackupTask(File filesDir, String scope, File out) {
        this.filesDir = filesDir;
        this.scope = scope;
        this.out = out;
    }

    public void run() {
        BackupFlow.runBackupScript(filesDir, scope, out);
    }
}
