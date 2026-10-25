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
    // 地址从 /releases/latest 换成了 /releases?per_page=20：正式版和测试版（beta）
    // 在同一个响应里，多查一次 beta 不用再发一轮请求。
    static final String[] CANDIDATES = {
        "https://api.github.com/repos/Zxin-Pro/astrbot-candle/releases?per_page=20",
        "https://ghfast.top/https://api.github.com/repos/Zxin-Pro/astrbot-candle/releases?per_page=20",
        "https://gh-proxy.com/https://api.github.com/repos/Zxin-Pro/astrbot-candle/releases?per_page=20",
        "https://ghproxy.net/https://api.github.com/repos/Zxin-Pro/astrbot-candle/releases?per_page=20"
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
        String v = plain(s);
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

    /** 带预发布后缀的版本比较：a 比 b 新返回正数。
     *  规则贴 semver：先比基线数字；基线相同则正式版 > 预发布；两个都是预发布时
     *  逐段比后缀（beta.2 > beta.1）。
     *  为什么要它：只看数字段的话 1.3.2-beta.1 和 1.3.2 一样大，正在跑 beta 的用户
     *  等正式版发出来会被判成「没有更新」，只能手动去 GitHub 下。 */
    static int cmpTag(String a, String b) {
        String x = plain(a), y = plain(b);
        int xi = x.indexOf('-'), yi = y.indexOf('-');
        String xb = (xi >= 0) ? x.substring(0, xi) : x;
        String yb = (yi >= 0) ? y.substring(0, yi) : y;
        int c = cmp(ver(xb), ver(yb));
        if (c != 0) return c;
        boolean xp = xi >= 0, yp = yi >= 0;
        if (xp && !yp) return -1;   // 预发布比同号正式版旧
        if (!xp && yp) return 1;
        if (!xp) return 0;
        return cmpPre(x.substring(xi + 1), y.substring(yi + 1));
    }

    /** 去掉首尾空白和开头的 v。 */
    static String plain(String s) {
        String v = (s == null) ? "" : s.trim();
        if (v.length() > 0 && (v.charAt(0) == 'v' || v.charAt(0) == 'V')) v = v.substring(1);
        return v;
    }

    /** 比较 beta.1 / rc.2 / alpha 这类后缀，按 . 和 - 分段逐段比。 */
    static int cmpPre(String a, String b) {
        String[] xs = a.split("[.-]");
        String[] ys = b.split("[.-]");
        int n = Math.min(xs.length, ys.length);
        for (int i = 0; i < n; i++) {
            int c = cmpIdent(xs[i], ys[i]);
            if (c != 0) return c;
        }
        return xs.length - ys.length;
    }

    /** 后缀里的一段：都是数字按数值比；数字段比字母段旧（semver 规则）；其余按字符串比。 */
    static int cmpIdent(String a, String b) {
        boolean na = isNum(a), nb = isNum(b);
        if (na && nb) {
            try { return Integer.compare(Integer.parseInt(a), Integer.parseInt(b)); }
            catch (Throwable ignored) { return a.compareTo(b); }
        }
        if (na != nb) return na ? -1 : 1;
        return a.compareTo(b);
    }

    static boolean isNum(String s) {
        if (s.length() == 0) return false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < '0' || ch > '9') return false;
        }
        return true;
    }
}
