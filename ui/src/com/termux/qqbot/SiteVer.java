package com.termux.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 读官网的版本号（xn--15t839a.xyz 的 /data/astrbot.json）。
 *
 * 为什么单独做一个：GitHub 的 release 在国内经常连不上，而官网的 JSON 是站点构建时
 * 生成的静态文件，带 UA 就能拿到，比 api.github.com 稳得多。
 * JSON 里下载页首卡的标题形如「AstrBot烛版 1.1.34（最新版）」，从中取版本号即可。
 * d8 处理匿名内部类会崩，本文件不出现匿名类。
 */
public final class SiteVer {

    static final String URL = "https://xn--15t839a.xyz/data/astrbot.json";
    private static final Pattern VER = Pattern.compile("(\\d+(?:\\.\\d+){1,3})");

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

    static String fetch() {
        JSONObject out = new JSONObject();
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            c = (HttpURLConnection) new URL(URL).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (AstrBotCandle)");
            c.setRequestProperty("Accept", "application/json");
            if (c.getResponseCode() != 200) {
                out.put("ok", false);
                out.put("err", "官网返回 " + c.getResponseCode());
                return out.toString();
            }
            in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            String body = new String(bo.toByteArray(), "UTF-8");
            JSONObject root = new JSONObject(body);
            out.put("ok", true);
            out.put("updated", root.optString("updated", ""));
            String ver = "";
            String apk = "";
            JSONArray tabs = root.optJSONArray("tabs");
            if (tabs != null) {
                for (int i = 0; i < tabs.length() && ver.length() == 0; i++) {
                    JSONObject tab = tabs.optJSONObject(i);
                    if (tab == null) continue;
                    JSONArray cards = tab.optJSONArray("cards");
                    if (cards == null) continue;
                    for (int j = 0; j < cards.length(); j++) {
                        JSONObject card = cards.optJSONObject(j);
                        if (card == null) continue;
                        String title = card.optString("title", "");
                        Matcher m = VER.matcher(title);
                        if (m.find() && title.indexOf("烛版") >= 0) {
                            ver = m.group(1);
                            String desc = card.optString("desc", "");
                            int q = desc.indexOf("AstrBot-");
                            if (q >= 0) {
                                int e = desc.indexOf(".apk", q);
                                if (e > q) apk = desc.substring(q, e + 4);
                            }
                            break;
                        }
                    }
                }
            }
            if (ver.length() == 0) {
                out.put("ok", false);
                out.put("err", "官网页面里没找到版本号");
                return out.toString();
            }
            out.put("site", ver);
            out.put("apk", apk);
            return out.toString();
        } catch (Throwable t) {
            try {
                out.put("ok", false);
                out.put("err", "连不上官网：" + String.valueOf(t));
            } catch (Throwable ignored) {}
            return out.toString();
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            try { if (c != null) c.disconnect(); } catch (Throwable ignored) {}
        }
    }
}
