package com.termux.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 从 OpenAI 兼容的中转站拉模型列表（GET {base}/models，Bearer 鉴权）。
 *
 * 用户只贴一把密钥，模型名不该让他手填——拉一次列表让他点选就行。
 * 桥方法跑在 JavaBridge 线程上，同步请求会把界面卡住，所以这里只起线程，
 * 前端 poll() 轮询。
 * d8 处理匿名内部类会崩，本文件不出现匿名类。
 */
public final class AstrModels {

    private static volatile String result = "";
    private static volatile boolean running = false;

    private AstrModels() {}

    public static synchronized void start(String base, String key) {
        if (running) return;
        running = true;
        result = "";
        Thread t = new Thread(new AstrModelsTask(base, key), "qqbot-models");
        t.setDaemon(true);
        t.start();
    }

    public static String poll() { return result; }

    static void done(String json) {
        result = json;
        running = false;
    }

    /** 拉列表。任何异常都变成 {"ok":false,"err":...}，前端据此提示。 */
    static String fetch(String base, String key) {
        JSONObject out = new JSONObject();
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            String b = (base == null || base.trim().length() == 0) ? AstrCfg.DEFAULT_BASE : base.trim();
            while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
            URL u = new URL(b + "/models");
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(true);
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", "AstrBotCandle");
            c.setRequestProperty("Accept", "application/json");
            String k = (key == null) ? "" : key.trim();
            if (k.length() > 0) c.setRequestProperty("Authorization", "Bearer " + k);
            int code = c.getResponseCode();
            if (code != 200) {
                out.put("ok", false);
                /*
                 * 401 / 403 的提示要分开写：以前一律「密钥不对或没填」，可那条路上最常见的情况
                 * 恰恰是「密钥早就保存过、只是输入框留空」——这句话把用户引到错的方向
                 * （以为要重新贴密钥，甚至以为插件坏了）。现在按「到底有没有密钥」分两句。
                 */
                String hint = "";
                if (code == 401 || code == 403) {
                    hint = (k.length() == 0)
                        ? "（这台手机上还没有保存密钥，先贴一把再点这里）"
                        : "（密钥被中转站拒了：换过 / 删过密钥的话，重新贴一把再试）";
                }
                out.put("err", "中转站返回 HTTP " + code + hint);
                return out.toString();
            }
            in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            String body = new String(bo.toByteArray(), "UTF-8");
            JSONArray names = parse(body);
            out.put("ok", true);
            out.put("models", names);
            out.put("count", names.length());
            return out.toString();
        } catch (Throwable t) {
            try {
                out.put("ok", false);
                out.put("err", "连不上中转站：" + String.valueOf(t));
            } catch (Throwable ignored) {}
            return out.toString();
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            try { if (c != null) c.disconnect(); } catch (Throwable ignored) {}
        }
    }

    /** 兼容两种返回：{"data":[{"id":...}]} 与直接的 ["model-a","model-b"]。 */
    static JSONArray parse(String body) throws Exception {
        JSONArray names = new JSONArray();
        String s = (body == null) ? "" : body.trim();
        if (s.length() == 0) return names;
        if (s.charAt(0) == '[') {
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                if (arr.opt(i) instanceof String) {
                    names.put(arr.optString(i));
                } else {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) {
                        String id = o.optString("id", o.optString("name", ""));
                        if (id.length() > 0) names.put(id);
                    }
                }
            }
            return names;
        }
        JSONObject root = new JSONObject(s);
        JSONArray arr = root.optJSONArray("data");
        if (arr == null) arr = root.optJSONArray("models");
        if (arr == null) return names;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            String id = (o == null) ? "" : o.optString("id", o.optString("name", ""));
            if (id.length() > 0) names.put(id);
        }
        return names;
    }
}
