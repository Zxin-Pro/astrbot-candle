package com.termux.app;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** 检查更新：网络 + 版本比对的公共部分。
 *
 *  为什么不直接在 @JavascriptInterface 方法里同步请求：桥方法跑在 WebView 的
 *  JavaBridge 线程上，而页面 JS 是单线程的，一次同步调用会把整个界面卡住几秒到
 *  几十秒（国内直连 api.github.com 经常超时，四个候选地址串起来能到 30 秒）。
 *  所以这里只负责起线程和存结果，前端用 poll() 轮询，页面全程不卡。
 */
public final class CheckUpdate {

    private CheckUpdate() {}

    // 依次尝试：官方 API 优先，然后几个常用反代。手机在国内直连 api.github.com
    // 经常超时，没有备选地址这个按钮就会一直转圈。
    static final String[] CANDIDATES = {
        "https://api.github.com/repos/Zxin-Pro/astrbot-candle/releases/latest",
        "https://ghfast.top/https://api.github.com/repos/Zxin-Pro/astrbot-candle/releases/latest",
        "https://gh-proxy.com/https://api.github.com/repos/Zxin-Pro/astrbot-candle/releases/latest",
        "https://ghproxy.net/https://api.github.com/repos/Zxin-Pro/astrbot-candle/releases/latest"
    };

    private static volatile String result = "";
    private static volatile boolean running = false;

    /** 起一次检查。已经在跑就直接忽略，避免用户连点起一堆线程。
     *  current 是当前安装的版本号（不带 v），用来判断有没有更新。 */
    public static synchronized void start(String current) {
        if (running) return;
        running = true;
        result = "";
        Thread t = new Thread(new UpdateWorker(current));
        t.setDaemon(true);
        t.start();
    }

    /** 前端轮询：空串表示还没出结果。 */
    public static String poll() { return result; }

    static void done(String json) {
        result = json;
        running = false;
    }

    /** 读一个 URL 的全部文本；非 200 或任何异常都返回 null，让调用方去试下一个地址。 */
    static String fetch(String url, int timeoutMs) {
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "AstrBotCandle");
            c.setRequestProperty("Accept", "application/vnd.github+json");
            if (c.getResponseCode() != 200) return null;
            in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            try { if (c != null) c.disconnect(); } catch (Throwable ignored) {}
        }
    }

    /** 把 v1.0.4 / 1.0.4 / 1.0.4-beta 拆成数字段，最多取 4 段、短的补 0。 */
    static int[] ver(String s) {
        String v = (s == null) ? "" : s.trim();
        if (v.length() > 0 && (v.charAt(0) == 'v' || v.charAt(0) == 'V')) v = v.substring(1);
        int cut = v.length();
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (ch != '.' && (ch < '0' || ch > '9')) { cut = i; break; }
        }
        v = v.substring(0, cut);
        String[] parts = v.split("\\.");
        int[] out = new int[] { 0, 0, 0, 0 };
        for (int i = 0; i < parts.length && i < 4; i++) {
            try { out[i] = Integer.parseInt(parts[i]); } catch (Throwable ignored) { out[i] = 0; }
        }
        return out;
    }

    /** a 比 b 新返回正数，相同返回 0。 */
    static int cmp(int[] a, int[] b) {
        for (int i = 0; i < 4; i++) {
            if (a[i] != b[i]) return a[i] - b[i];
        }
        return 0;
    }
}