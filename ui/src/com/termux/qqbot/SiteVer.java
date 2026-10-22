package com.termux.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 读 GitHub 上的最新版本号（Zxin-Pro/astrbot-candle 的 releases/latest）。
 *
 * 为什么单独做一个：这个请求走网络、国内直连 api.github.com 常常要十几秒，不能让
 * WebView 的桥线程同步等，所以照 CheckUpdate 的老样子起一条后台线程写结果、前端
 * poll() 轮询，页面全程不卡。
 * 返回的 JSON 固定为 {ok, updated, site, apk}：
 *   site    = tag_name 去掉前缀 v/V，例如 1.1.34；
 *   apk     = assets 里名字以 .apk 结尾且带 arm64 的资产文件名；
 *   updated = published_at（2026-10-22T05:36:16Z）按 GMT+8 转成 YYYY-MM-DD HH:mm:ss。
 * 失败给 {ok:false, err:"..."}。
 * d8 处理匿名内部类会崩，本文件不出现匿名类。
 */
public final class SiteVer {

    static final String URL = "https://api.github.com/repos/Zxin-Pro/astrbot-candle/releases/latest";

    private static volatile String result = "";
    private static volatile boolean running = false;

    private SiteVer() {}

    public static synchronized void start() {
        if (running) return;
        running = true;
        result = "";
        Thread t = new Thread(new SiteVerTask(), "qqbot-sitever");
        t.setDaemon(true);
        t.start();
    }

    public static String poll() { return result; }

    static void done(String json) {
        result = json;
        running = false;
    }

    /** 取一个字符串字段：Android 的 JSONObject.NULL 会被 optString 变成 "null"，这里统一当空串。 */
    static String txt(JSONObject o, String key) {
        String s = o.optString(key, "");
        if (s == null || "null".equals(s)) return "";
        return s.trim();
    }

    /** 2026-10-22T05:36:16Z → 2026-10-22 13:36:16（GMT+8）；解析不了就退化成 YYYY-MM-DD。 */
    static String when(String iso) {
        String s = (iso == null) ? "" : iso.trim();
        if (s.length() == 0) return "";
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            in.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = in.parse(s);
            SimpleDateFormat out = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            out.setTimeZone(TimeZone.getTimeZone("GMT+8"));
            return out.format(d);
        } catch (Throwable t) {
            if (s.length() >= 10) return s.substring(0, 10);
            return s;
        }
    }

    /** GitHub releases/latest 的 JSON → {ok, updated, site, apk}；解析不动就给 {ok:false, err}。 */
    static String build(String body) {
        JSONObject out = new JSONObject();
        try {
            JSONObject root = new JSONObject(body);

            String ver = txt(root, "tag_name");
            if (ver.length() > 0 && (ver.charAt(0) == 'v' || ver.charAt(0) == 'V')) ver = ver.substring(1);
            if (ver.length() == 0) {
                out.put("ok", false);
                out.put("err", "GitHub 的 release 里没有版本号（tag_name 为空）");
                return out.toString();
            }

            String apk = "";
            JSONArray assets = root.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) continue;
                    String an = txt(a, "name");
                    if (an.length() == 0) continue;
                    String low = an.toLowerCase(Locale.US);
                    if (!low.endsWith(".apk")) continue;
                    if (low.indexOf("arm64") < 0) continue;
                    apk = an;
                    break;
                }
            }

            out.put("ok", true);
            out.put("updated", when(txt(root, "published_at")));
            out.put("site", ver);
            out.put("apk", apk);
            return out.toString();
        } catch (Throwable t) {
            JSONObject bad = new JSONObject();
            try {
                bad.put("ok", false);
                bad.put("err", "解析 GitHub 返回失败：" + String.valueOf(t));
            } catch (Throwable ignored) {}
            return bad.toString();
        }
    }

    static String fetch() {
        JSONObject out = new JSONObject();
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            c = (HttpURLConnection) new URL(URL).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "AstrBotCandle");
            c.setRequestProperty("Accept", "application/vnd.github+json");
            int code = c.getResponseCode();
            if (code != 200) {
                out.put("ok", false);
                out.put("err", "GitHub 返回 " + code);
                return out.toString();
            }
            in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return build(new String(bo.toByteArray(), "UTF-8"));
        } catch (Throwable t) {
            try {
                out.put("ok", false);
                out.put("err", "连不上 GitHub：" + String.valueOf(t));
            } catch (Throwable ignored) {}
            return out.toString();
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            try { if (c != null) c.disconnect(); } catch (Throwable ignored) {}
        }
    }
}
