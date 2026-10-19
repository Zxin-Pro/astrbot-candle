package com.termux.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/**
 * 功能 59「匿名统计」的原生侧：开关 + 一个随机安装 ID（上报本身在 StatsUploadTask）。
 *
 * 存两样东西：
 *   - state/prefs.json 里的 stats 布尔（默认 false，和崩溃上报同一个文件）；
 *   - state/stats-id 里的一行 uuid4。
 * 关闭时 id 一律按空串对外，避免「关了还带着 id」这种说不清的状态；
 * 重置只换 ID，不改开关（用户点「重新生成」时开关状态不该被顺手改掉）。
 *
 * 为什么这里不做采集：事件清单来自服务端下发的配置，原生层凭空造一份只会两边对不上；
 * 本类只把「这个安装」的身份和开关交出去。v1.1.30 起，开关开着时由 StatsUploadTask
 * 每天 POST 一条今日概览到 /api/stats（开关关着一个字节都不发，连 DNS 都不解析）。
 */
final class StatsId {

    private static final String FILE = "stats-id";
    private static final String KEY = "stats";

    private StatsId() {
    }

    static File file(File filesDir) {
        return StateIO.file(filesDir, FILE);
    }

    static boolean enabled(File filesDir) {
        return Prefs.getBool(filesDir, KEY, false);
    }

    /** 读已存的 uuid；没有 / 长度不对（被写坏）都当没有，返回空串。 */
    static String read(File filesDir) {
        String s = StateIO.read(file(filesDir));
        if (s == null) return "";
        s = s.trim();
        if (s.length() < 32 || s.length() > 40) return "";
        return s;
    }

    private static String newId(File filesDir) {
        String id = UUID.randomUUID().toString();
        StateIO.write(file(filesDir), id);
        return id;
    }

    private static String day() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
    }

    /** 开关：开到没 ID 时顺手生成一个；关掉时不动已有 ID（下次开还是同一个安装）。 */
    static String on(File filesDir, boolean on) {
        try {
            JSONObject o = new JSONObject();
            if (!on) {
                Prefs.put(filesDir, KEY, false);
                o.put("ok", true);
                o.put("on", false);
                o.put("enabled", false);
                o.put("id", "");
                o.put("installId", "");
                return o.toString();
            }
            String id = read(filesDir);
            if (id.length() == 0) id = newId(filesDir);
            Prefs.put(filesDir, KEY, true);
            Prefs.put(filesDir, "statsTs", StateIO.now());
            o.put("ok", true);
            o.put("on", true);
            o.put("enabled", true);
            o.put("id", id);
            o.put("installId", id);
            o.put("day", day());
            return o.toString();
        } catch (Throwable t) {
            return QqbotBridge.failJson("保存匿名统计开关失败：" + t);
        }
    }

    /**
     * 当前状态；没开启时 id 为空串（前端据此显示「未开启」而不是一个幽灵 ID）。
     * 原有字段一个不动，v1.1.30 追加四个只读字段：atomOn / lastSentDay / lastErr / lastAt，
     * 数据来自 state/upload.json（由 StatsUploadTask 记账）。
     */
    static String id(File filesDir) {
        try {
            boolean on = enabled(filesDir);
            String id = on ? read(filesDir) : "";
            JSONObject up = UploadState.load(filesDir);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("on", on);
            o.put("enabled", on);
            o.put("id", id);
            o.put("installId", id);
            o.put("day", day());
            o.put("ts", Prefs.getLong(filesDir, "statsTs", 0L));
            o.put("atomOn", true);
            o.put("lastSentDay", up.optString("statsDay", ""));
            o.put("lastErr", up.optString("lastErr", ""));
            o.put("lastAt", up.optLong("lastAt", 0L));
            return o.toString();
        } catch (Throwable t) {
            return QqbotBridge.failJson("读取匿名统计状态失败：" + t);
        }
    }

    /** 重新生成安装 ID（旧 ID 直接作废，没有历史可迁）。 */
    static String reset(File filesDir) {
        try {
            String id = newId(filesDir);
            Prefs.put(filesDir, "statsTs", StateIO.now());
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("id", id);
            o.put("installId", id);
            o.put("on", enabled(filesDir));
            o.put("enabled", enabled(filesDir));
            return o.toString();
        } catch (Throwable t) {
            return QqbotBridge.failJson("重置安装 ID 失败：" + t);
        }
    }

    /**
     * 事件流水：原生层不落本地事件表（采集在别处），只回空数组占位，
     * 外加今日在线秒数（守护循环写在 state/today.json 里，前端已有这份数据）。
     */
    static String events(File filesDir) {
        try {
            long sec = 0L;
            String s = StateIO.read(StateIO.file(filesDir, "today.json"));
            if (s != null && s.trim().length() > 0) {
                try {
                    sec = new JSONObject(s).optLong("onlineSec", 0L);
                } catch (Throwable ignored) {
                }
            }
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("on", enabled(filesDir));
            o.put("events", new JSONArray());
            o.put("todaySec", sec);
            o.put("day", day());
            return o.toString();
        } catch (Throwable t) {
            return QqbotBridge.failJson("读取统计事件失败：" + t);
        }
    }
}
