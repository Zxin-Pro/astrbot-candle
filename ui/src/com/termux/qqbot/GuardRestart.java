package com.termux.app;

import java.io.File;

/**
 * 为什么需要它：通知栏的「重启」按钮不能直接在主线程里跑 stop/start 脚本
 * （停沙盒最多要等 proot 自己退 30 秒）。这里把它挪到独立线程，
 * 顺序和界面上的「重启」一致：先 stop-sandbox.sh，再 startSandbox，最后补看门狗。
 * 失败只改通知正文，不重试——下一个 60 秒自检会把沙盒兜回来。
 * d8 处理匿名内部类会崩，所以必须是具名顶层类。
 */
public class GuardRestart implements Runnable {

    private final QqbotGuardService svc;

    public GuardRestart(QqbotGuardService s) {
        svc = s;
    }

    public void run() {
        try {
            File filesDir = svc.getFilesDir();
            // 用户按过「关闭进程 / 紧急下线」时 state/offline 还在：这时点通知栏「重启」
            // 也不该把沙盒拉起来（与 NetRecover / QqbotGuardTask 同口径）。
            if (StateIO.file(filesDir, "offline").isFile()) {
                note("已关闭，不重启");
                return;
            }
            note("正在重启沙盒…");
            File stop = new File(QqbotShell.usrDir(filesDir), "opt/qqbot/stop-sandbox.sh");
            if (stop.isFile()) {
                QqbotShell.run(filesDir, "bash " + stop.getAbsolutePath(), 180);
            }
            StringBuilder n = new StringBuilder();
            // 通知栏上的「重启」是用户点的，算显式启动
            boolean ok = QqbotShell.startSandbox(filesDir, n, true);
            File pid = new File(QqbotShell.qqbotDir(filesDir), "supervisor.pid");
            boolean alive = QqbotShell.pidAlive(pid, "proot");
            if (ok || alive) QqbotShell.startWatchdog(filesDir, n);
            if (ok) note("已重启沙盒 · " + nowText());
            else if (alive) note("沙盒已在运行 · " + nowText());
            else note("重启失败：" + n.toString());
        } catch (Throwable t) {
            note("重启异常：" + t.getClass().getSimpleName());
        }
    }

    private void note(String s) {
        try { svc.setNote(s); } catch (Throwable ignored) {}
    }

    private String nowText() {
        try {
            return new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
        } catch (Throwable t) {
            return "";
        }
    }
}
