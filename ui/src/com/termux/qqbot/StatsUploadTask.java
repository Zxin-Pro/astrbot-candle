package com.termux.app;

import android.os.Build;

import org.json.JSONObject;

import java.io.File;

/**
 * 匿名统计上报：把「这台机器今天用了多少」POST 到 /api/stats，一天最多成功一条。
 *
 * payload 只有这些字段：
 *   {"id":"&lt;installId&gt;","ver":"&lt;versionName&gt;","day":"YYYY-MM-DD",
 *    "onlineSec":N,"msgs":N,"errors":N,"os":"Android &lt;SDK_INT&gt;",
 *    "arch":"&lt;SUPPORTED_ABIS[0]&gt;","events":{"open":N}}
 * 不上送 QQ 号、聊天内容、账号、文件名、IP、设备型号——只到这个粒度。
 *
 * 日期口径：day 是「发送当天」，onlineSec/msgs/errors 取 state/today.json 的今日累计；
 * 若那份快照还停留在昨天（冷启动发生在守护循环第一次 tick 之前），一律按 0 发，
 * 不把昨天的数字挂到今天的日期上。
 *
 * 本类必须是顶层类：d8 处理匿名内部类会崩（见 StateIO 注释），整个类里没有内部类。
 */
public class StatsUploadTask implements Runnable {

    /** 同一时刻只允许一个统计上报在跑。 */
    private static volatile boolean busy = false;

    private final File filesDir;
    private final String ver;

    /** ver 是 versionName（"1.1.30"），调用方已经取好；任务里不再碰 Context。 */
    public StatsUploadTask(File filesDir, String ver) {
        this.filesDir = filesDir;
        this.ver = (ver == null) ? "" : ver;
    }

    @Override
    public void run() {
        if (filesDir == null) return;
        // 第一道闸：开关关着一个字节都不发（连 DNS 都不解析）
        if (!UploadState.statsOn(filesDir)) return;
        synchronized (StatsUploadTask.class) {
            if (busy) return;
            busy = true;
        }
        try {
            upload();
        } catch (Throwable ignored) {
        } finally {
            synchronized (StatsUploadTask.class) {
                busy = false;
            }
        }
    }

    private void upload() {
        String day = UploadState.day();
        // 一天只发一条：今天已经成功过就不再发（冷启动闸门也判过，这里是最后一道）
        if (day.equals(UploadState.lastSentDay(filesDir, UploadState.KIND_STATS))) return;

        String id = UploadState.installId(filesDir);
        JSONObject t = today(filesDir);
        boolean sameDay = day.equals(t.optString("day", ""));
        long onlineSec = sameDay ? t.optLong("onlineSec", 0L) : 0L;
        long msgs = sameDay ? t.optLong("msgs", 0L) : 0L;
        long errors = sameDay ? t.optLong("errors", 0L) : 0L;
        int open = UploadState.openCount(filesDir);

        String body = payload(id, ver, day, onlineSec, msgs, errors, os(), arch(), open);
        UploadState.beginAttempt(filesDir, UploadState.KIND_STATS);
        String reply = AtomNet.post(AtomNet.PATH_STATS, body);
        JSONObject ro = parse(reply);
        boolean ok = (ro != null) && ro.optBoolean("ok", false);
        int code = (ro == null) ? 0 : ro.optInt("code", 0);
        String err = ok ? "" : ((ro == null) ? "上报结果无法解析" : ro.optString("err", ""));
        UploadState.finish(filesDir, UploadState.KIND_STATS, ok, code, err);
    }

    /**
     * 上报体。纯字符串拼装——不碰文件、不碰网络、不碰 Android API，
     * 本地假服务器验证直接调它（这就是「实际发出的 JSON」）。
     */
    static String payload(String id, String ver, String day, long onlineSec, long msgs, long errors,
                          String os, String arch, int open) {
        StringBuilder b = new StringBuilder(256);
        b.append("{\"id\":\"").append(AtomNet.esc(id))
                .append("\",\"ver\":\"").append(AtomNet.esc(ver))
                .append("\",\"day\":\"").append(AtomNet.esc(day))
                .append("\",\"onlineSec\":").append(onlineSec)
                .append(",\"msgs\":").append(msgs)
                .append(",\"errors\":").append(errors)
                .append(",\"os\":\"").append(AtomNet.esc(os))
                .append("\",\"arch\":\"").append(AtomNet.esc(arch))
                .append("\",\"events\":{\"open\":").append(open)
                .append("}}");
        return b.toString();
    }

    /** "Android 34"：只发系统大版本号，不发厂商 ROM / 机型号这种指纹。 */
    static String os() {
        try {
            return "Android " + Build.VERSION.SDK_INT;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 首个 ABI（"arm64-v8a"）。取不到就空串，不编。 */
    static String arch() {
        try {
            String[] abis = Build.SUPPORTED_ABIS;
            if (abis != null && abis.length > 0 && abis[0] != null) return abis[0].trim();
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** state/today.json（守护循环写的今日累计）；没有 / 被写坏都当空对象。 */
    private static JSONObject today(File filesDir) {
        String s = StateIO.read(StateIO.file(filesDir, "today.json"));
        if (s != null && s.trim().length() > 0) {
            try {
                return new JSONObject(s);
            } catch (Throwable ignored) {
            }
        }
        return new JSONObject();
    }

    private static JSONObject parse(String s) {
        if (s == null || s.trim().length() == 0) return null;
        try {
            return new JSONObject(s);
        } catch (Throwable t) {
            return null;
        }
    }
}
