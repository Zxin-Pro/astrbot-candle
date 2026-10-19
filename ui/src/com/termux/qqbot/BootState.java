package com.termux.app;

import org.json.JSONObject;

import java.io.File;

/**
 * 为什么需要它：重启后能不能自己补登，用户只能靠「有没有反应」猜。
 * 这里把「上次开机时间 / 上次成功拉起沙盒的时间 / 开机后尝试拉升的次数 / 上次失败原因」
 * 持久化到 $HOME/.qqbot/state/boot.json，界面（bootInfo()）和排障都读同一份。
 * 只是几个数字，读写在毫秒级；但仍然只在后台线程（守护循环 / 广播）里写。
 * d8 处理匿名内部类会崩，本文件不出现匿名类。
 */
final class BootState {

    private static final String NAME = "boot.json";

    private BootState() {}

    static File file(File filesDir) {
        return StateIO.file(filesDir, NAME);
    }

    private static JSONObject load(File filesDir) {
        JSONObject o = null;
        String s = StateIO.read(file(filesDir));
        if (s != null) {
            try { o = new JSONObject(s); } catch (Throwable ignored) { o = null; }
        }
        return (o == null) ? new JSONObject() : o;
    }

    private static void save(File filesDir, JSONObject o) {
        StateIO.write(file(filesDir), o.toString());
    }

    /**
     * 收到开机/覆盖安装广播时记一笔：lastBoot 更新，attempts 累加。
     * 返回累加后的 attempts（失败重试时用来判断要不要再补一刀）。
     */
    static synchronized int noteBoot(File filesDir) {
        JSONObject o = load(filesDir);
        int n = o.optInt("attempts", 0) + 1;
        try {
            o.put("lastBoot", StateIO.now());
            o.put("attempts", n);
            o.put("lastErr", "");
        } catch (Throwable ignored) {
        }
        save(filesDir, o);
        return n;
    }

    /** 沙盒拉起成功/失败都记一笔；成功时刷新 lastStart，失败时留下原因。 */
    static synchronized void noteStart(File filesDir, boolean ok, String err) {
        JSONObject o = load(filesDir);
        try {
            if (ok) {
                o.put("lastStart", StateIO.now());
                o.put("lastErr", "");
            } else {
                String e = (err == null) ? "" : err;
                if (e.length() > 200) e = e.substring(0, 200);
                o.put("lastErr", e);
            }
        } catch (Throwable ignored) {
        }
        save(filesDir, o);
    }

    /** 供桥方法 bootInfo() 直接返回：字段一个都不缺，缺的按 0 / 空串补。 */
    static String bootInfo(File filesDir) {
        JSONObject out = new JSONObject();
        try {
            JSONObject o = load(filesDir);
            out.put("ok", true);
            out.put("lastBoot", o.optLong("lastBoot", 0L));
            out.put("lastStart", o.optLong("lastStart", 0L));
            out.put("attempts", o.optInt("attempts", 0));
            out.put("lastErr", o.optString("lastErr", ""));
        } catch (Throwable t) {
            return "{\"ok\":false,\"err\":\"读取开机记录失败\"}";
        }
        return out.toString();
    }
}
