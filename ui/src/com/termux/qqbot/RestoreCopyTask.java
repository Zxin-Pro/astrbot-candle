package com.termux.app;

import android.net.Uri;

/**
 * 恢复选文件后的第一步：把用户选的 Uri 流式拷进 cache/restore/，
 * 一边拷一边算 SHA-256（后面交给 restore.sh 校验用，脚本只认 SHA256=）。
 * 这一步必须后台跑：备份包动辄几百 MB，拷一次要好几秒。
 */
final class RestoreCopyTask implements Runnable {

    private final QqbotActivity act;
    private final Uri uri;
    private final String scope;

    RestoreCopyTask(QqbotActivity act, Uri uri, String scope) {
        this.act = act;
        this.uri = uri;
        this.scope = scope;
    }

    public void run() {
        BackupFlow.copyIn(act, uri, scope);
    }
}
