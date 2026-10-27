package com.termux.app;

/**
 * beta.4：只活「本次运行」的会话状态。
 *
 * 为什么单独开一个类、而且故意什么都不存：二维码弹窗底部的「不再提示（本次运行）」
 * 用户明确说的是「本次运行」——下次打开 App 还得照旧弹（扫码是登录的必要步骤，
 * 永久静音会让用户第二天打开发现机器人掉线却不知道为什么）。
 * 所以这里只有一个 static volatile 字段：进程重启 = 归零，
 * 不写 SharedPreferences、不写 state/、不写 bots.json。
 *
 * 唯一的使用者是 QqbotGuardService.statusTickLocked() 里发通知前的过滤：
 * 用户已经说了「这次别提醒扫码」，就别在通知栏里再提一遍。
 *
 * d8 处理匿名内部类会崩，本文件不出现匿名类。
 */
public final class QqbotSession {

    private QqbotSession() {
    }

    /** 本次运行不再提示扫码（默认 false；进程重启即失效）。 */
    private static volatile boolean qrSkip = false;

    public static void setQrSkip(boolean on) {
        qrSkip = on;
    }

    public static boolean qrSkipped() {
        return qrSkip;
    }
}
