package com.termux.app;

import java.io.File;

/**
 * 后台启动沙盒的线程体（v1.3.3-beta.6）。
 *
 * 为什么需要它：通知栏 / 桌面小组件 / 快捷磁贴点「启动」时，App 很可能完全在后台
 * （甚至锁屏）。旧做法是「把面板叫到前台，让页面里的 JS 去调 Android 桥」，这条路
 * 在后台三重不成立：targetSdk 28 的 BAL 会拦掉服务发起的 Activity、WebView 在后台
 * 被系统降频、用户按 Home 后页面直接不可见，启动流程跟着断掉。
 *
 * 所以启动这一步必须在服务里独立跑完，与界面有没有显示无关。
 * 判定口径与 SandboxStart 一致：先删「紧急下线」标记（用户明确点了启动），
 * 再 start-sandbox.sh --user-start，起来后补看门狗；失败只改通知正文，不重试
 * （下一轮 60 秒自检会兜回来）。
 * d8 处理匿名内部类会崩，所以必须是具名顶层类。
 */
public class GuardStart implements Runnable {

    private final QqbotGuardService svc;

    public GuardStart(QqbotGuardService s) {
        svc = s;
    }

    public void run() {
        File filesDir = svc.getFilesDir();
        try {
            // 用户明确点了「启动」：这一步等价于解除「紧急下线」，不删的话守护循环
            // 与宿主机看门狗都会继续拒绝拉起沙盒（见 start-sandbox.sh 的 USER_START）。
            StateIO.delete(StateIO.file(filesDir, "offline"));
        } catch (Throwable ignored) {
        }
        StringBuilder note = new StringBuilder();
        boolean ok = false;
        try {
            ok = QqbotShell.startSandbox(filesDir, note, true);
        } catch (Throwable t) {
            note.append(t.toString());
        }
        boolean alive = ok;
        if (!alive) {
            try {
                alive = QqbotShell.pidAlive(new File(QqbotShell.qqbotDir(filesDir), "supervisor.pid"), "proot");
            } catch (Throwable ignored) {
            }
        }
        if (alive) {
            try {
                QqbotShell.startWatchdog(filesDir, null);
            } catch (Throwable ignored) {
            }
        }
        String msg = alive
                ? ("已在后台启动沙盒 · " + nowText())
                : ("后台启动失败：" + note);
        android.util.Log.i("QqbotGuard", "ACTION_START 结果：" + msg);
        try {
            svc.setNote(msg);
            svc.refreshNoti();
        } catch (Throwable ignored) {
        }
        try {
            QqbotWidgetProvider.refreshAsync(svc);
        } catch (Throwable ignored) {
        }
    }

    private String nowText() {
        try {
            return new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
        } catch (Throwable t) {
            return "";
        }
    }
}
