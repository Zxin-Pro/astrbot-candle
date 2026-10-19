package com.termux.app;

import org.json.JSONObject;

import java.io.File;

/**
 * 为什么需要它：桌面小组件 / 快捷磁贴（第二批）和通知栏标题都要知道「几个实例在线」，
 * 但它们分别在别的进程边界之外、也不能去解析 bots.json + 逐个探端口。所以由守护循环
 * 每轮把结论写进 $HOME/.qqbot/state/ui.json，其余读方只读这一份小文件。
 * 只有守护线程写它，读方永远不会看到半个 JSON（StateIO 是原子写）。
 * 同时在内存里留一份 lastOnline：通知标题在主线程构建，绝不能为了一个数字去读磁盘。
 * d8 处理匿名内部类会崩，本文件不出现匿名类。
 */
final class UiState {

    private static final String NAME = "ui.json";

    /** 内存快照（-1 = 还没算过）。通知标题用它，避免主线程做 IO。 */
    private static volatile int lastOnline = -1;
    private static volatile int lastTotal = -1;

    private UiState() {}

    static File file(File filesDir) {
        return StateIO.file(filesDir, NAME);
    }

    /** 守护线程每轮调用；返回在线数是否变了（变了要让通知标题立刻刷新）。 */
    static boolean write(File filesDir, int online, int total) {
        return write(filesDir, online, total, QqbotGuardService.guardRunning);
    }

    static boolean write(File filesDir, int online, int total, boolean serviceAlive) {
        boolean changed = online != lastOnline;
        lastOnline = online;
        lastTotal = total;
        try {
            JSONObject o = new JSONObject();
            o.put("online", online);
            o.put("total", total);
            o.put("serviceAlive", serviceAlive);
            o.put("updated", StateIO.now());
            StateIO.write(file(filesDir), o.toString());
        } catch (Throwable ignored) {
        }
        return changed;
    }

    /** 内存里的在线实例数（-1 = 未知，通知里显示「?」）。 */
    static int online() {
        return lastOnline;
    }

    static int total() {
        return lastTotal;
    }

    /** 从磁盘读一份（只在后台线程调用；给第二批的小组件/磁贴这类读方预留）。 */
    static int readOnline(File filesDir) {
        String s = StateIO.read(file(filesDir));
        if (s == null) return -1;
        try {
            JSONObject o = new JSONObject(s);
            if (!o.has("online")) return -1;
            return o.optInt("online", -1);
        } catch (Throwable t) {
            return -1;
        }
    }
}
