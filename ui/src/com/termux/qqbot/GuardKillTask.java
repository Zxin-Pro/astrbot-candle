package com.termux.app;

/**
 * 通知栏「关闭进程」：把沙盒停掉，然后连守护服务自己一起收掉。
 *
 * 和「紧急下线」的区别：那个只停沙盒、守护还在，用户下次点开面板还能看见状态；
 * 这个是把整套后台都关掉——用户明确说了「进程守护有点变态」，得留一条能一键
 * 关干净的路。offline 标记由 EmergencyStop 写，所以 watchdog 与网络自愈都不会
 * 再把沙盒拉起来。
 * d8 处理匿名内部类会崩，所以必须是具名顶层类。
 */
public class GuardKillTask implements Runnable {

    private final QqbotGuardService svc;

    public GuardKillTask(QqbotGuardService svc) {
        this.svc = svc;
    }

    public void run() {
        try {
            new EmergencyStop(svc.getFilesDir()).run();
        } catch (Throwable ignored) {
        }
        try {
            svc.stopQuiet();
        } catch (Throwable ignored) {
        }
    }
}
