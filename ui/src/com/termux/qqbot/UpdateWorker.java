package com.termux.app;

import org.json.JSONArray;
import org.json.JSONObject;

/** 后台线程：逐个候选地址拉 GitHub 的 release 列表，挑出最新的正式版和测试版（预发布），
 *  组装成前端能直接吃的 JSON。
 *
 *  为什么拉列表而不是 latest：GitHub 的 /releases/latest 只返回正式版，测试版根本看不见。
 *  拉一次列表（per_page=20）两个都有了，也不用为 beta 再发一轮请求。
 *
 *  注意：这里必须用独立的具名顶层类，不能写成匿名内部类 —— build-tools 的 d8
 *  处理 JDK 21 编译出的匿名内部类会抛 NullPointerException 直接崩掉整个编译。
 */
final class UpdateWorker implements Runnable {

    private final String current;

    UpdateWorker(String current) { this.current = current; }

    public void run() {
        try {
            for (int i = 0; i < CheckUpdate.CANDIDATES.length; i++) {
                String body = CheckUpdate.fetch(CheckUpdate.CANDIDATES[i], 9000);
                if (body == null || body.length() == 0) continue;
                String json = parse(body, current);
                if (json != null) { CheckUpdate.done(json); return; }
            }
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("current", current);
            o.put("error", "连不上 GitHub（直连和几个镜像都超时了），请确认手机能上网后再试");
            CheckUpdate.done(o.toString());
        } catch (Throwable t) {
            try {
                JSONObject o = new JSONObject();
                o.put("ok", false);
                o.put("error", "检查更新失败：" + t);
                CheckUpdate.done(o.toString());
            } catch (Throwable ignored) {
                CheckUpdate.done("{\"ok\":false,\"error\":\"检查更新失败\"}");
            }
        }
    }

    /** 解析 release 列表；拿不到能用的 tag 返回 null（说明这个地址给的不是我们要的东西）。 */
    private static String parse(String body, String current) {
        try {
            JSONArray arr = null;
            try {
                arr = new JSONArray(body);
            } catch (Throwable notArray) {
                // 有的反代会把「最新版」包成单个对象返回，兜一下底
                JSONObject one = new JSONObject(body);
                if (one.optString("tag_name", "").length() == 0) return null;
                arr = new JSONArray();
                arr.put(one);
            }
            JSONObject stable = null, beta = null;
            String stableTag = "", betaTag = "";
            for (int i = 0; i < arr.length(); i++) {
                JSONObject rel = arr.optJSONObject(i);
                if (rel == null || rel.optBoolean("draft")) continue;
                String tag = rel.optString("tag_name", "");
                if (tag.length() == 0) continue;
                if (rel.optBoolean("prerelease")) {
                    if (beta == null || CheckUpdate.cmpTag(tag, betaTag) > 0) { beta = rel; betaTag = tag; }
                } else {
                    if (stable == null || CheckUpdate.cmpTag(tag, stableTag) > 0) { stable = rel; stableTag = tag; }
                }
            }
            if (stable == null && beta == null) return null;
            // 一条正式版都没有时（新项目刚起步），把 beta 当成「最新」也要能显示，
            // 否则面板会一直报「检查失败」，其实只是还没有正式版。
            JSONObject rel = (stable != null) ? stable : beta;
            String tag = rel.optString("tag_name", "");
            int c = CheckUpdate.cmpTag(tag, current);
            JSONObject o = base(rel, current);
            o.put("ok", true);
            o.put("tag", tag);
            o.put("latest", CheckUpdate.plain(tag));
            o.put("hasUpdate", c > 0);
            o.put("newer", c < 0);
            if (beta != null) o.put("beta", betaJson(beta, current));
            return o.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 正式版 / 测试版共用的字段：安装包直链与发布信息。 */
    private static JSONObject base(JSONObject rel, String current) throws Exception {
        String url = "";
        String apkName = "";
        long size = 0;
        JSONArray assets = rel.optJSONArray("assets");
        if (assets != null) {
            // 优先拿「更新包」（AstrBot-<ver>-<arch>-update.apk，约 25 MB）。
            // 完整包带 600 MB 离线镜像，只适合第一次安装；老用户升级没必要再下一遍。
            String abi = android.os.Build.SUPPORTED_ABIS.length > 0 ? android.os.Build.SUPPORTED_ABIS[0] : "";
            String want = (abi.indexOf("x86") >= 0 || abi.indexOf("amd64") >= 0) ? "amd64" : "arm64";
            String slim = "", slimAny = "", full = "", fullAny = "", slimN = "", slimAnyN = "", fullN = "", fullAnyN = "";
            long slimSize = 0, slimAnySize = 0, fullSize = 0, fullAnySize = 0;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a == null) continue;
                String an = a.optString("name", "");
                if (!an.endsWith(".apk")) continue;
                String u = a.optString("browser_download_url", "");
                long sz = a.optLong("size", 0);
                boolean isSlim = an.indexOf("-update.apk") >= 0;
                boolean mine = an.indexOf(want) >= 0;
                if (isSlim && mine && slim.length() == 0) { slim = u; slimSize = sz; slimN = an; }
                else if (isSlim && slimAny.length() == 0) { slimAny = u; slimAnySize = sz; slimAnyN = an; }
                else if (!isSlim && mine && full.length() == 0) { full = u; fullSize = sz; fullN = an; }
                else if (!isSlim && fullAny.length() == 0) { fullAny = u; fullAnySize = sz; fullAnyN = an; }
            }
            if (slim.length() > 0) { url = slim; size = slimSize; apkName = slimN; }
            else if (slimAny.length() > 0) { url = slimAny; size = slimAnySize; apkName = slimAnyN; }
            else if (full.length() > 0) { url = full; size = fullSize; apkName = fullN; }
            else if (fullAny.length() > 0) { url = fullAny; size = fullAnySize; apkName = fullAnyN; }
        }
        JSONObject o = new JSONObject();
        o.put("current", current);
        o.put("name", rel.optString("name", ""));
        o.put("url", url);
        o.put("apkName", apkName);
        o.put("size", size);
        o.put("publishedAt", rel.optString("published_at", ""));
        o.put("htmlUrl", rel.optString("html_url", ""));
        String notes = rel.optString("body", "");
        if (notes.length() > 4000) notes = notes.substring(0, 4000);
        o.put("notes", notes);
        return o;
    }

    /** 测试版：和正式版同结构，另外带上 tag / version / newer，前端直接判断该不该提示。
     *  newer 是「比当前安装的版本新」——不是比正式版新，所以 beta.2 对 beta.1 用户是 true。 */
    private static JSONObject betaJson(JSONObject rel, String current) throws Exception {
        String tag = rel.optString("tag_name", "");
        JSONObject b = base(rel, current);
        b.put("tag", tag);
        b.put("version", CheckUpdate.plain(tag));
        b.put("newer", CheckUpdate.cmpTag(tag, current) > 0);
        b.put("isBeta", true);
        return b;
    }
}
