package com.termux.app;

/**
 * 在后台线程跑一段 shell，跑完用 Toast 提示。
 * 删除实例、重新对接这类操作要开 proot 沙盒，单次要十几秒；直接放在
 * WebView 的 JS 桥线程里做会把页面冻住，所以丢到后台线程去。
 * 注意：d8 处理匿名内部类会崩，所以这里必须是具名顶层类。
 */
public class BgTask implements Runnable {

    private final QqbotActivity act;
    private final String cmd;
    private final String done;

    public BgTask(QqbotActivity act, String cmd, String done) {
        this.act = act;
        this.cmd = cmd;
        this.done = done;
    }

    public void run() {
        try {
            act.runShell(cmd, 300);
        } catch (Throwable ignored) {
        }
        if (done != null && done.length() > 0) {
            try {
                act.activity().runOnUiThread(new UiTask(act, UiTask.TOAST, done));
            } catch (Throwable ignored) {
            }
        }
    }
}
