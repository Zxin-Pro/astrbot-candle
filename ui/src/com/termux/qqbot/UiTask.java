package com.termux.app;

public class UiTask implements Runnable {

    public static final int COPY = 1;
    public static final int TOAST = 2;
    public static final int TERMINAL = 3;
    public static final int PANEL = 4;
    public static final int PICKBG = 5;
    public static final int OPENURL = 6;
    public static final int QQGROUP = 7;
    public static final int POWER = 8;
    // 功能 50：备份另存为 / 恢复选包（都只是拉起系统文件选择器，结果异步回 onActivityResult）
    public static final int BACKUP_SAVE = 9;
    public static final int RESTORE_OPEN = 10;
    // 通知栏权限引导（Android 13+ POST_NOTIFICATIONS）：只能主线程弹系统框
    public static final int NOTI = 11;

    private final QqbotActivity act;
    private final int kind;
    private final String arg;

    // 任务执行结果（"ok" / 中文错误文案）。只有需要把结果同步带回 JS 的任务
    // （OPENURL / QQGROUP）才会写它；因为是在主线程写、桥线程读，声明成 volatile。
    private volatile String result = "";
    // 调用方（runOnUiSync）用来等这个任务跑完；不需要同步结果的任务为 null。
    private java.util.concurrent.CountDownLatch latch;

    public UiTask(QqbotActivity act, int kind, String arg) {
        this.act = act;
        this.kind = kind;
        this.arg = arg;
    }

    public void setLatch(java.util.concurrent.CountDownLatch l) { this.latch = l; }

    public String result() { return result; }

    public void run() {
        try {
            if (kind == COPY) {
                act.doCopy(arg);
            } else if (kind == TOAST) {
                act.doToast(arg);
            } else if (kind == TERMINAL) {
                act.doOpenTerminal();
            } else if (kind == PANEL) {
                act.doOpenPanel(arg);
            } else if (kind == PICKBG) {
                act.doPickBg();
            } else if (kind == OPENURL) {
                String r = act.doOpenUrl(arg);
                result = (r == null) ? "" : r;
            } else if (kind == QQGROUP) {
                result = act.doOpenQqGroup(arg) ? "ok" : "fail";
            } else if (kind == POWER) {
                // 厂商省电设置页：逐个候选试，全打不开就退应用详情页（见 PowerGuide）
                result = PowerGuide.jumpNow(act);
            } else if (kind == BACKUP_SAVE) {
                // 只拉起「另存为」；空串 = Intent 已发出，真正的拷贝在 onActivityResult 之后
                String r = act.doBackupSave();
                result = (r == null) ? "" : r;
            } else if (kind == NOTI) {
                act.doNotiAsk();
            } else if (kind == RESTORE_OPEN) {
                String r = act.doRestoreOpen();
                result = (r == null) ? "" : r;
            }
        } finally {
            // 无论成功失败都要放行等待者，否则桥线程要白等满 3 秒
            if (latch != null) latch.countDown();
        }
    }
}
