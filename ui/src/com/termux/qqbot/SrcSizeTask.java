package com.termux.app;

import java.io.File;

/**
 * 后台统计「目标目录有多大」（备份面板上那句「约 xxx MB」）。
 * 递归遍历几千个文件要几十到几百毫秒，放在 backupInfo() 里同步跑会破掉
 * 「读取类桥方法 &lt;50ms」的约定，所以后台算、内存缓存 60 秒。
 */
final class SrcSizeTask implements Runnable {

    private final File filesDir;

    SrcSizeTask(File filesDir) {
        this.filesDir = filesDir;
    }

    public void run() {
        BackupFlow.computeSrcBytes(filesDir);
    }
}
