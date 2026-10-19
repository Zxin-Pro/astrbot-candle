package com.termux.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/**
 * 功能 58「崩溃聚合」的原生侧：采集、开关、以及 v1.1.30 起的真实上报（走 CrashUploadTask）。
 *
 * 真正的聚合逻辑在沙盒脚本里（crash-agg.sh → common.sh 的 qq_crash_agg），
 * 原生层只负责四件事：
 *   1. 把脚本写的 counts 文件读成前端顺手的形状（items + classes 两种都给）；
 *   2. 维护「要不要上报」这个开关（落 state/prefs.json，默认关）；
 *   3. 开关开着时由 CrashUploadTask 把聚合类逐条 POST 到 /api/crash（每天最多成功一次）；
 *   4. 一键清空计数。
 *
 * 开关关着时本类一个字节都不往外发；网络代码全在 AtomNet / CrashUploadTask 里，
 * 本类只在 queue() 里起一个后台线程，自己不持有任何 socket。
 *
 * 注意：脚本实际写的是 cache/crash-counts.json（不是 state/），
 * 老布局 state/crash-counts.json 仍兼容读取；两处都没有就返回空数组，
 * 而不是报错——没崩过本来就是正常状态。
 */
final class CrashBoard {

    /** 新布局优先，老布局兜底。 */
    private static final String[] COUNT_FILES = {
            "cache/crash-counts.json",
            "state/crash-counts.json",
    };

    private CrashBoard() {
    }

    /** 脚本写的那个位置（清空时按新布局删，顺手把老布局也删掉）。 */
    static File countsFile(File filesDir) {
        File cache = new File(QqbotShell.qqbotDir(filesDir), "cache");
        return new File(cache, "crash-counts.json");
    }

    private static JSONObject readCounts(File filesDir) {
        File qq = QqbotShell.qqbotDir(filesDir);
        for (int i = 0; i < COUNT_FILES.length; i++) {
            String s = StateIO.read(new File(qq, COUNT_FILES[i]));
            if (s == null || s.trim().length() == 0) continue;
            try {
                return new JSONObject(s);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * 脚本写的原始聚合类（classes 数组），给 CrashUploadTask 逐条上报用。
     * 直接给原始 JSONObject：上报只要 sig/component/signal/count 四个字段，
     * 没必要先经过 info() 的「给前端看的形状」再解析回来。没有就返回空数组。
     */
    static JSONArray classes(File filesDir) {
        try {
            JSONObject src = readCounts(filesDir);
            JSONArray a = (src == null) ? null : src.optJSONArray("classes");
            return (a == null) ? new JSONArray() : a;
        } catch (Throwable t) {
            return new JSONArray();
        }
    }

    /**
     * 崩溃统计：{"ok":true,"items":[{sig,module,signal,count,last}],"total":N, ...}。
     * 另外把脚本原始字段（component / first / ver / device / samples）也带回去，
     * 前端想显示「哪台设备崩的」不用再找原生要一次。
     */
    static String info(File filesDir) {
        try {
            JSONObject src = readCounts(filesDir);
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("on", Prefs.getBool(filesDir, "crashReport", false));
            out.put("ts", (src == null) ? 0L : src.optLong("ts", 0L));

            JSONArray classes = new JSONArray();
            JSONArray items = new JSONArray();
            long total = 0L;
            JSONArray in = (src == null) ? null : src.optJSONArray("classes");
            int n = (in == null) ? 0 : in.length();
            for (int i = 0; i < n; i++) {
                JSONObject c = in.optJSONObject(i);
                if (c == null) continue;
                String sig = c.optString("sig", "");
                // 脚本里叫 component，契约里叫 module；两个名字都给，前端改哪边都不会漏
                String module = c.optString("component", c.optString("module", ""));
                String signal = c.optString("signal", "");
                int cnt = c.optInt("count", 0);
                long first = c.optLong("first", 0L);
                long last = c.optLong("last", 0L);

                JSONObject e = new JSONObject();
                e.put("sig", sig);
                e.put("component", module);
                e.put("module", module);
                e.put("signal", signal);
                e.put("count", cnt);
                e.put("first", first);
                e.put("last", last);
                e.put("ver", c.optString("ver", ""));
                e.put("device", c.optString("device", ""));
                JSONArray samples = c.optJSONArray("samples");
                if (samples != null) e.put("samples", samples);
                classes.put(e);

                JSONObject it = new JSONObject();
                it.put("sig", sig);
                it.put("module", module);
                it.put("signal", signal);
                it.put("count", cnt);
                it.put("last", last);
                items.put(it);

                total += cnt;
            }
            out.put("classes", classes);
            out.put("items", items);
            out.put("total", total);
            out.put("kinds", n);
            return out.toString();
        } catch (Throwable t) {
            return QqbotBridge.failJson("读取崩溃统计失败：" + t);
        }
    }

    /**
     * 开关：只落盘，不触发上传。
     * 「打开」不等于「立刻发」——真正发请求的时机在 UploadState 的三处触发点
     * （冷启动 / 守护 6 小时巡检 / 用户手点 crashUploadNow），且每天最多成功一次。
     */
    static String setReport(File filesDir, boolean on) {
        try {
            Prefs.put(filesDir, "crashReport", on);
            if (on) Prefs.put(filesDir, "crashReportTs", StateIO.now());
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("on", on);
            o.put("enabled", on);
            return o.toString();
        } catch (Throwable t) {
            return QqbotBridge.failJson("保存崩溃上报开关失败：" + t);
        }
    }

    /**
     * 当前状态。原有字段（ok/on/enabled/ts）一个不动，v1.1.30 追加四个只读字段：
     * atomOn（原生层已接入真实上报）、lastSentDay（最后一次成功上报的日期）、
     * lastErr / lastAt（最后一次失败的原文与时刻，成功时 err 为空串）。
     */
    static String state(File filesDir) {
        try {
            boolean on = Prefs.getBool(filesDir, "crashReport", false);
            JSONObject up = UploadState.load(filesDir);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("on", on);
            o.put("enabled", on);
            o.put("ts", Prefs.getLong(filesDir, "crashReportTs", 0L));
            o.put("atomOn", true);
            o.put("lastSentDay", up.optString("crashDay", ""));
            o.put("lastErr", up.optString("lastErr", ""));
            o.put("lastAt", up.optLong("lastAt", 0L));
            return o.toString();
        } catch (Throwable t) {
            return QqbotBridge.failJson("读取崩溃上报开关失败：" + t);
        }
    }

    /**
     * 「现在上报一次」：用户在面板上手动点的那个按钮。
     * 开关关着 → 原样返回 {"ok":false,"err":"…","queued":false}，不解析 DNS。
     * 开关开着 → 起一个后台线程真发（CrashUploadTask），本方法立刻回 queued:true，
     * 绝不阻塞桥线程；成功与否由 CrashUploadTask 落到 state/upload.json
     * （成功时把 crashDay 记成今天，lastErr/lastAt 记下失败原因，界面不弹提示）。
     */
    static String queue(File filesDir, String ver) {
        try {
            boolean on = Prefs.getBool(filesDir, "crashReport", false);
            if (!on) {
                JSONObject o = new JSONObject();
                o.put("ok", false);
                o.put("err", "崩溃上报开关是关的");
                o.put("queued", false);
                return o.toString();
            }
            long now = StateIO.now();
            Prefs.put(filesDir, "crashUploadQueued", now);
            UploadState.spawnCrash(filesDir, ver);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("queued", true);
            o.put("ts", now);
            return o.toString();
        } catch (Throwable t) {
            return QqbotBridge.failJson("登记上报失败：" + t);
        }
    }

    /** 清空计数：两个布局都删，返回删掉了几个文件。 */
    static String clear(File filesDir) {
        try {
            int n = 0;
            File qq = QqbotShell.qqbotDir(filesDir);
            for (int i = 0; i < COUNT_FILES.length; i++) {
                if (StateIO.delete(new File(qq, COUNT_FILES[i]))) n++;
            }
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("cleared", n);
            return o.toString();
        } catch (Throwable t) {
            return QqbotBridge.failJson("清空崩溃统计失败：" + t);
        }
    }
}
