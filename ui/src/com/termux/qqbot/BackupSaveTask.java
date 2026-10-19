package com.termux.app;

import android.net.Uri;

import java.io.File;

/**
 * 备份产物「另存为」：把缓存里的 tar.gz 流式拷到用户在系统文件选择器里挑的 Uri。
 * 几百 MB 的流不能读进内存，也不能占着桥线程，所以单独一个线程。
 */
final class BackupSaveTask implements Runnable {

    private final QqbotActivity act;
    private final Uri uri;
    private final File src;

    BackupSaveTask(QqbotActivity act, Uri uri, File src) {
        this.act = act;
        this.uri = uri;
        this.src = src;
    }

    public void run() {
        BackupFlow.saveToUri(act, uri, src);
    }
}
