package com.termux.app;

import java.io.File;

/**
 * 为什么需要它：NetworkWatch 的回调跑在系统 binder 线程上，而「拉起沙盒」要跑
 * start-sandbox.sh（内部有 sleep、要等 proot 起来），绝不能在这个线程里同步做，
 * 否则会拖住系统网络回调、ANR 风险直接上升。这里把它整体挪到独立线程。
 * 顺序按协议固定：先 startSandbox（shell 内部会先等 NapCat 端口可连再启 AstrBot），
 * 沙盒起来了再补看门狗；拉起失败不重试——下一个 60 秒的自检循环会兜住。
 * d8 处理匿名内部类会崩，所以必须是具名顶层类。
 */
public class NetRecover implements Runnable {

    private final QqbotGuardService svc;
    private final File filesDir;
    private final int seq;

    public NetRecover(QqbotGuardService s, File filesDir, int seq) {
        this.svc = s;
        this.filesDir = filesDir;
        this.seq = seq;
    }

    public void run() {
        try {
            note("网络已切换，正在自愈…");
            // 紧急下线 / 风控暂停期间绝不自动拉起：这两件事都是用户或风控明确要求的「别动」，
            // 网络刚好转就重开沙盒，等于把用户的下线操作和风控判断全废掉。
            if (StateIO.file(filesDir, "offline").isFile()) {
                note("网络已恢复，但处于紧急下线状态，未自动拉起");
                return;
            }
            if (RiskReader.paused(filesDir)) {
                note("网络已恢复，但风控暂停中，未自动拉起");
                return;
            }
            if (!QqbotShell.bootstrapReady(filesDir)) {
                note("网络已恢复，但 Termux 环境未就绪");
                return;
            }
            StringBuilder n = new StringBuilder();
            boolean ok = QqbotShell.startSandbox(filesDir, n);
            // 沙盒本来就在跑时 startSandbox 会返回 false（「沙盒已在运行」），
            // 但看门狗可能已经挂了，所以按 pid 判断，别漏了 startWatchdog。
            File pid = new File(QqbotShell.qqbotDir(filesDir), "supervisor.pid");
            if (ok || QqbotShell.pidAlive(pid, "proot")) QqbotShell.startWatchdog(filesDir, null);
            note("网络自愈 #" + seq + " · " + n.toString());
        } catch (Throwable t) {
            note("网络自愈异常：" + t.getClass().getSimpleName());
        } finally {
            // 计数/时间戳落盘放在这里：既离开回调线程，也保证异常路径同样记账
            try { NetworkWatch.persist(filesDir); } catch (Throwable ignored) {}
        }
    }

    private void note(String s) {
        try {
            if (svc != null) svc.setNote(s);
        } catch (Throwable ignored) {
        }
    }
}
