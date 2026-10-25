package com.termux.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 保活心跳的落点（2 分钟一次）。收到广播做三件事：续下一次心跳、把守护服务拉起来、
 * 丢一个后台线程做沙盒体检。
 *
 * 为什么不用 goAsync：暂停沙盒最多要等 proot 退出（最长 120 秒），远超过广播接收器的
 * 处理时限，硬等会触发 ANR。服务已经在同一进程里，广播返回后进程不会立刻被回收，
 * 体检线程可以放心跑完。
 * d8 处理匿名内部类会崩，所以线程体写成具名的 KeepAliveTask。
 */
public class KeepAliveReceiver extends BroadcastReceiver {

    public void onReceive(Context c, Intent i) {
        String a = (i == null || i.getAction() == null) ? "" : i.getAction();
        if (a.length() > 0 && !KeepAlive.ACTION.equals(a)) return;
        // 用户主动关掉（关闭进程 / 紧急下线）：不续期、不拉起，心跳链到此为止。
        if (KeepAlive.closedByUser(c)) return;
        KeepAlive.arm(c);
        try {
            QqbotGuardService.start(c);
        } catch (Throwable ignored) {
        }
        try {
            Thread t = new Thread(new KeepAliveTask(c.getApplicationContext()), "qqbot-keepalive");
            t.setDaemon(true);
            t.start();
        } catch (Throwable ignored) {
        }
    }
}