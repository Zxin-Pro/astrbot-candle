package com.termux.app;

import android.os.Process;

/**
 * 通知栏「关闭进程」：把沙盒停掉，然后连守护服务、连整个应用进程一起收掉。
 *
 * 和「紧急下线」的区别：那个只停沙盒、守护还在，用户下次点开面板还能看见状态；
 * 这个是把整套后台都关掉——用户原话是「通知栏那个关闭进程是要把这个软件彻底关掉，
 * 不是单独一个按钮」，所以流程最后一步必须是 killProcess。
 * offline 标记由 EmergencyStop 写，所以 watchdog 与网络自愈都不会再把沙盒拉起来。
 *
 * 这是唯一一个会结束应用进程的入口：通知栏「关闭进程」→ ACTION_KILL → 本类。
 * 别处（下线 / 停止 / 磁贴 / 小组件）都只停沙盒或停服务，绝不动 killProcess。
 * d8 处理匿名内部类会崩，所以必须是具名顶层类（兜底线程也一样，见 KillWatchdog）。
 */
public class GuardKillTask implements Runnable {

    private final QqbotGuardService svc;

    public GuardKillTask(QqbotGuardService svc) {
        this.svc = svc;
    }

    public void run() {
        // 第一件事就是挂兜底线程：stop-sandbox.sh 要等 proot 自己退，最坏能拖满
        // EmergencyStop 给的 180 秒，用户按了按钮半天没反应只会以为按钮坏了。
        // KillWatchdog 到点无条件杀进程，保证按钮按下后 5 秒内进程一定结束
        // （stop-sandbox.sh 已改成脱离启动，进程先死也不影响它把沙盒收干净）。
        try {
            Thread w = new Thread(new KillWatchdog(), "qqbot-kill-watchdog");
            w.setDaemon(true);
            w.start();
        } catch (Throwable ignored) {
        }
        // 1.0) 先写一张「这次是通知栏关的」便条（state/closed）。下次进入应用时
        //      QqbotActivity 见到它就把 offline 标记一并清掉，沙盒照常自动启动 ——
        //      用户要的是「关掉之后重开 App 不用再点『启动服务』」。
        //      界面上自己点的「紧急下线」不写这张便条，那个是用户明确要求别再自己起来。
        try {
            StateIO.writeStamp(StateIO.file(svc.getFilesDir(), "closed"));
        } catch (Throwable ignored) {
        }
        // 1) 停沙盒：写 offline 标记，并把 stop-sandbox.sh 丢到独立会话里跑。
        //    不能用 EmergencyStop.run() 同步等 —— 下面就要 killProcess，进程一死管道断开，
        //    脚本收尾会被 SIGPIPE 打断，留下占端口的孤儿 supervisor（见 startDetached 注释）。
        try {
            new EmergencyStop(svc.getFilesDir()).startDetached();
        } catch (Throwable ignored) {
        }
        // 1.5) 撤掉保活心跳：用户要的是彻底关掉，不能两分钟后被闹钟叫醒又活过来。
        try {
            KeepAlive.disarm(svc);
        } catch (Throwable ignored) {
        }
        // 2) 收起守护服务：撤常驻通知 + 停前台 + stopSelf，别在通知栏留僵尸卡片
        try {
            svc.stopQuiet();
        } catch (Throwable ignored) {
        }
        // 2.5) 进程马上要被 SIGKILL，onDestroy 未必来得及执行：先同步把 state/ui.json 的
        //      serviceAlive 落成 false 并重绘桌面小组件，否则桌面卡片会一直显示「运行中」。
        try {
            new StateFlush(svc, UiState.online(), UiState.total(), false).run();
        } catch (Throwable ignored) {
        }
        // 3) 最后结束整个应用进程。只停服务不杀进程的话，WebView、桥线程以及沙盒留下
        //    的子进程还会挂在后台，「关闭进程」就变成了「关掉一个按钮」。
        //    这是全工程唯一一处 killProcess。
        try {
            Process.killProcess(Process.myPid());
        } catch (Throwable ignored) {
        }
    }
}
