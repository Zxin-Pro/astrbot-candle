package com.termux.app;

import android.os.Process;

/**
 * 通知栏「关闭进程」的计时兜底：从按下按钮算起 5 秒，无条件结束本应用进程。
 *
 * 为什么需要它：GuardKillTask 的正常路径是「写 offline → 跑 stop-sandbox.sh →
 * 停前台服务 → killProcess」，而 stop-sandbox.sh 要等 proot 自己退（先 TERM、赖着
 * 才 KILL），EmergencyStop 给它的超时是 180 秒。用户按了「关闭进程」却盯着通知栏
 * 没反应，只会以为按钮坏了。所以按下按钮的第一步就是把这个线程挂上去：到点不管
 * 沙盒停没停，进程都必须消失，通知栏和界面立刻归零。
 *
 * 和 GuardKillTask 的分工：那个是正常路径（通常一两秒内就跑完并自己 killProcess，
 * 本线程随进程一起消失）；本类是异常路径的保险丝，只做一件事——到点杀进程。
 * 线程必须具名且 setDaemon(true)（调用方负责），d8 处理匿名内部类会崩，故为具名顶层类。
 */
public class KillWatchdog implements Runnable {

    /** 兜底时限（毫秒）：按钮按下后最多这么久，进程必须结束。 */
    public static final long LIMIT_MS = 5000L;

    public void run() {
        try {
            Thread.sleep(LIMIT_MS);
        } catch (InterruptedException e) {
            // 被打断也照杀不误：本线程没有「取消」语义，职责只有到点杀进程这一条
        }
        try {
            Process.killProcess(Process.myPid());
        } catch (Throwable ignored) {
        }
    }
}
