package com.termux.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PowerManager;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 息屏模式（1.3.3-beta.2 起）：屏幕一灭就把保活加到最严，亮屏再退回普通节奏。
 *
 * 为什么单独做这一层：屏幕亮着的时候用户能看见界面，沙盒卡住会立刻发现；屏幕一灭，
 * 系统进入 Doze / 缓存进程冻结，NapCat 的 QQ 长连接最先掉，而用户完全不知道。
 * 所以「灭屏」这个时刻需要更强的策略：心跳从 2 分钟缩到 1 分钟、掉线阈值从 3 次降到 2 次、
 * 两次自愈的最小间隔从 10 分钟降到 5 分钟，并在灭屏瞬间立刻体检一次。
 *
 * 屏幕开关的广播从 Android 8 起不允许在清单里静态注册（系统只发给动态注册的接收者），
 * 而动态接收者必须挂在一个活着的组件上。这里跟着常驻前台服务 QqbotGuardService 走：
 * 服务在就有监听，服务停就注销，不额外占用常驻内存。
 *
 * 开关文件 $HOME/.qqbot/screenmode.off（存在＝用户关掉了），默认开。
 * d8 处理匿名内部类会崩，本文件不出现匿名类。
 */
public class ScreenMode extends BroadcastReceiver {

    /** 开关文件：存在即表示用户关掉了息屏模式（默认开）。 */
    public static File offFlag(Context c) {
        return new File(new File(new File(c.getFilesDir(), "home"), ".qqbot"), "screenmode.off");
    }

    public static boolean isOn(Context c) {
        try {
            return c != null && !offFlag(c).isFile();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 屏幕现在是否为熄屏状态。PowerManager.isInteractive() 在 API 20+ 可用（minSdk 24）。 */
    public static boolean screenDark(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm != null && !pm.isInteractive();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 当前是否处于「灭屏加强」：息屏模式开着，并且此刻确实黑屏。KeepAlive 按它换节奏。 */
    public static boolean boosted(Context c) {
        return isOn(c) && screenDark(c);
    }

    /** 切换开关：写/删标记文件后立刻对齐一次节奏（不用等下一次灭屏）。 */
    public static String setOn(Context c, boolean on) {
        mark(offFlag(c), !on);
        apply(c);
        return on ? "已开启息屏模式：熄屏后每分钟体检一次" : "已关闭息屏模式：熄屏后按普通节奏";
    }

    /**
     * 按当前屏幕 / 开关状态对齐一次：重排心跳（灭屏 1 分钟、亮屏 2 分钟）；
     * 灭屏时再立刻体检一回，别等到下一个闹钟才发现沙盒已经凉了。
     */
    public static void apply(Context c) {
        if (c == null) return;
        try {
            KeepAlive.arm(c);
        } catch (Throwable ignored) {
        }
        if (boosted(c)) {
            try {
                Thread th = new Thread(new KeepAliveTask(c), "qqbot-screen");
                th.setDaemon(true);
                th.start();
            } catch (Throwable ignored) {
            }
            android.util.Log.i("QqbotScreen", "屏幕熄灭：心跳切到 1 分钟，立刻体检一次沙盒");
        }
    }

    /* ---------- 生命周期：跟着常驻守护服务走 ---------- */

    private static ScreenMode inst;
    private static boolean registered = false;

    public static synchronized void start(Context c) {
        if (registered || c == null) return;
        try {
            ScreenMode r = new ScreenMode();
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_SCREEN_OFF);
            c.registerReceiver(r, f);
            inst = r;
            registered = true;
            // 服务起来时可能本来就是黑屏（开机自启 / 被闹钟拉回来），先对齐一次再等广播。
            apply(c);
        } catch (Throwable t) {
            registered = false;
            inst = null;
        }
    }

    public static synchronized void stop(Context c) {
        ScreenMode r = inst;
        inst = null;
        registered = false;
        if (r == null || c == null) return;
        try {
            c.unregisterReceiver(r);
        } catch (Throwable ignored) {
        }
    }

    public static boolean running() {
        return registered;
    }

    @Override
    public void onReceive(Context c, Intent i) {
        try {
            String a = (i == null) ? null : i.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                if (isOn(c)) apply(c);
                else KeepAlive.arm(c);
            } else if (Intent.ACTION_SCREEN_ON.equals(a)) {
                // 亮屏：把心跳退回去，同时不再是「黑屏」——面板这时候才可能被用户看到。
                KeepAlive.arm(c);
                android.util.Log.i("QqbotScreen", "屏幕点亮：心跳恢复 2 分钟");
            }
        } catch (Throwable ignored) {
        }
    }

    /** 开关文件就是「存在＝关闭」的标记，创建/删除它即可。 */
    private static void mark(File f, boolean present) {
        try {
            if (present) {
                File p = f.getParentFile();
                if (p != null) p.mkdirs();
                new FileOutputStream(f).close();
            } else {
                f.delete();
            }
        } catch (Throwable ignored) {
        }
    }
}
