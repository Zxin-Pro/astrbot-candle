package com.termux.app;

import org.json.JSONArray;
import org.json.JSONObject;

/** 后台线程：逐个候选地址拉 GitHub 的最新 release，组装成前端能直接吃的 JSON。
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

    /** 解析 GitHub release JSON；拿不到 tag_name 返回 null（说明这个地址给的不是我们要的东西）。 */
    private static String parse(String body, String current) {
        try {
            JSONObject rel = new JSONObject(body);
            String tag = rel.optString("tag_name", "");
            if (tag.length() == 0) return null;
            String latest = tag;
            if (latest.length() > 0 && (latest.charAt(0) == 'v' || latest.charAt(0) == 'V')) latest = latest.substring(1);
            int c = CheckUpdate.cmp(CheckUpdate.ver(tag), CheckUpdate.ver(current));
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
            o.put("ok", true);
            o.put("current", current);
            o.put("latest", latest);
            o.put("tag", tag);
            o.put("hasUpdate", c > 0);
            o.put("newer", c < 0);
            o.put("name", rel.optString("name", ""));
            o.put("url", url);
            o.put("apkName", apkName);
            o.put("size", size);
            o.put("publishedAt", rel.optString("published_at", ""));
            o.put("htmlUrl", rel.optString("html_url", ""));
            String notes = rel.optString("body", "");
            if (notes.length() > 4000) notes = notes.substring(0, 4000);
            o.put("notes", notes);
            return o.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}