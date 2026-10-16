package com.termux.app;

import android.content.Context;

import java.io.File;

/**
 * 守护服务的自检循环：每 60 秒看一次沙盒与看门狗。
 * 抽成独立的具名顶层类是因为 d8 处理匿名内部类会崩。
 */
public class QqbotGuardTask implements Runnable {

    private static final int PERIOD = 60;

    private final QqbotGuardService svc;
    private final File filesDir;
    private volatile boolean run = true;
    private int failStreak = 0;
    private long backoffUntil = 0L;

    public QqbotGuardTask(QqbotGuardService s) {
        svc = s;
        filesDir = s.getFilesDir();
    }

    public void stop() { run = false; }

    public void run() {
        svc.setNote("守护已就绪");
        while (run) {
            try {
                tick();
            } catch (Throwable t) {
                try { svc.setNote("自检异常：" + t.getClass().getSimpleName()); } catch (Throwable ignored) {}
            }
            sleepSeconds(PERIOD);
        }
    }

    private void sleepSeconds(int n) {
        for (int i = 0; i < n && run; i++) {
            try { Thread.sleep(1000L); } catch (InterruptedException e) { return; }
        }
    }

    private void tick() {
        if (!QqbotShell.bootstrapReady(filesDir)) {
            svc.setNote("等待首次部署（Termux 环境还未解包）");
            return;
        }
        File qq = QqbotShell.qqbotDir(filesDir);
        File deployDone = new File(qq, "deploy.done");
        if (!deployDone.isFile()) {
            File dp = new File(qq, "deploy.pid");
            if (!QqbotShell.pidAlive(dp, null)) {
                File script = new File(QqbotShell.usrDir(filesDir), "opt/qqbot/deploy.sh");
                if (script.isFile()) {
                    // 部署没跑完就把部署续上（部署脚本自己按步骤标记续跑）
                    QqbotShell.run(filesDir, "mkdir -p " + qq.getAbsolutePath() + "/logs " + qq.getAbsolutePath() + "/steps; "
                            + "nohup setsid bash " + QqbotShell.usrDir(filesDir).getAbsolutePath() + "/opt/qqbot/deploy.sh >> "
                            + qq.getAbsolutePath() + "/logs/deploy.log 2>&1 < /dev/null & "
                            + "sleep 2; echo ok", 20);
                    svc.setNote("部署未完成，已续跑部署");
                    return;
                }
            }
            svc.setNote("首次部署进行中…");
            return;
        }
        if (System.currentTimeMillis() < backoffUntil) {
            svc.setNote("沙盒重启失败，稍后重试（第 " + failStreak + " 次）");
            return;
        }
        File pid = new File(qq, "supervisor.pid");
        if (!QqbotShell.pidAlive(pid, "proot")) {
            StringBuilder note = new StringBuilder();
            boolean ok = QqbotShell.startSandbox(filesDir, note);
            if (ok) {
                failStreak = 0;
                svc.setNote(note.toString());
            } else {
                failStreak++;
                if (failStreak >= 3) backoffUntil = System.currentTimeMillis() + 180000L;
                svc.setNote(note.toString());
            }
            return;
        }
        failStreak = 0;
        QqbotShell.startWatchdog(filesDir, null);
        svc.setNote("沙盒运行中 · 最近自检 " + nowText());
    }

    private String nowText() {
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm:ss");
            return f.format(new java.util.Date());
        } catch (Throwable t) {
            return "";
        }
    }
}
