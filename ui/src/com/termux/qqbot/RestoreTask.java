package com.termux.app;

import java.io.File;

/**
 * 后台跑 restore.sh。恢复会停服、解包、再按原状态拉起服务，最长可能几分钟，
 * 所以和备份一样必须离开 JS 桥线程。
 *
 * 注意：停服/重启是脚本自己做的（restore.sh 里 stop-sandbox.sh --no-offline），
 * 原生层再预停一次反而会把 WAS_RUNNING 记成 0，恢复完服务就不会自动回来。
 */
final class RestoreTask implements Runnable {

    private final File filesDir;
    private final String scope;
    private final File src;
    private final String sha;

    RestoreTask(File filesDir, String scope, File src, String sha) {
        this.filesDir = filesDir;
        this.scope = scope;
        this.src = src;
        this.sha = (sha == null) ? "" : sha;
    }

    public void run() {
        BackupFlow.runRestoreScript(filesDir, scope, src, sha);
    }
}
