package com.termux.app;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 唯一负责往外发 HTTP 的类（v1.1.30 的三个出口：崩溃上报 / 匿名统计 / 公告）。
 *
 * 2026-10 自建站点停用：站点与 VPS 已下线（首页 530、全端口连接超时），三个出口的
 * 服务端都不存在了。处理方式：CrashUploadTask / StatsUploadTask / AnnounceTask
 * 各自写死 ENABLED = false，匿名上报不再默认发送，默认一个字节都不出本机
 * （UploadState 的 crashOn / statsOn 也一直是默认关）。BASE 不再指向那个死域名，
 * 改成 api.github.com 只为让残留下来的调用点不去敲一个「必然超时 8 秒」的地址；
 * GitHub 侧没有免密钥的收件接口，真需要详细日志时走 upload-log.sh 提交到
 * 私有仓库 Zxin-Pro/astrbot-errlogs（要用户自己的 token，不进 APK）。
 *
 * 为什么单开一个类：全应用只有这里能建 socket，审计「到底有哪些数据出了本机」时
 * grep 调用点就够了。别处（CrashUploadTask / StatsUploadTask / AnnounceTask / 桥方法）
 * 一律只组 JSON 或只读结果，不碰网络。
 *
 * 线程归属：本类**不起线程、不排队、不重试**，调用方必须已经在后台线程里。
 * 三个理由：1) 主线程调它会直接 NetworkOnMainThreadException，出事一眼看见；
 * 2) 谁触发了这次请求在代码里是明文，不用追线程池；3) 本类不依赖 org.json，
 * 桌面 JVM 能原样编译验证（本地假服务器走的就是这条路）。
 *
 * 协议约定（服务端缺 X-Qqbot-Atom 头一律 403）：
 *   POST <BASE>/api/crash   /   <BASE>/api/stats
 *        Content-Type: application/json; charset=utf-8
 *   GET  <BASE>/api/announce?ver=1.1.30   （可带 If-None-Match，304 = 没变）
 *   注意：这三条路由只存在于已停用的自建站点服务端，保留在注释里是为了读懂旧协议，
 *   现在没有任何服务的这一侧在监听，调用方一律被 ENABLED = false 短路。
 *   两个口都带 X-Qqbot-Atom: 1；连接超时 5000ms；读超时 POST 8000ms / GET 5000ms；
 *   不跟随重定向；失败不重试（重试由上层决定下次什么时候再来）。
 *
 * d8 处理匿名内部类会崩，本文件不出现匿名类（整个类里也没有任何内部类）。
 */
final class AtomNet {

    /**
     * 上报域名。原先指向自建站点（punycode 直写 ASCII，少一层编码问题），2026-10 站点停用，
     * 指向它只会换来一次 8 秒超时，所以改指 api.github.com：不是新的上报接收端，
     * 只是「别再敲死域名」。GitHub 那边没有免密钥收件口，POST 过去会被 4xx 拒掉，
     * 这是预期结果——真正的详细日志走 upload-log.sh 提到 Zxin-Pro/astrbot-errlogs。
     * 故意留成非 final：本地假服务器验证时改成本机地址，验完必须改回这一行。
     */
    static volatile String BASE = "https://api.github.com";

    /** 两个上报口（POST）。 */
    static final String PATH_CRASH = "/api/crash";
    static final String PATH_STATS = "/api/stats";

    /** 公告口（GET，可带 If-None-Match；304 = 没变）。后面拼 "?ver=<versionName>"。 */
    static final String PATH_ANN = "/api/announce";

    /**
     * 老契约（docs §11 原文）的公告口：不带参数、返回 {"ver","minVer","ann":{…}}。
     * 服务端两版路径只实现了其中一个时，AnnounceTask 会先试 /api/announce，
     * 4xx/5xx 再退到这条，保证前端不管后端改没改都能拿到公告。
     */
    static final String PATH_CONFIG = "/api/app-config.json";

    /** 服务端硬性要求：缺这个头直接 403，值固定 "1"。 */
    static final String HEADER_ATOM = "X-Qqbot-Atom";

    static final int CONNECT_TIMEOUT_MS = 5000;
    static final int READ_TIMEOUT_MS = 8000;

    /**
     * 公告是 GET，正文是几 KB 的 JSON。超时按需求压到 5 秒：
     * 公告可以晚一拍出现，但不能拖住任何人（冷启动时它和别的任务并行跑）。
     */
    static final int READ_TIMEOUT_GET_MS = 5000;

    /** 响应体只读到这个长度：上报口服务端只回 {"ok":true}，多读没意义。 */
    private static final int MAX_REPLY = 4096;

    /** 公告正文比回执大，单独给上限；超了截断，解析失败就当这次没公告。 */
    private static final int MAX_BODY = 65536;

    /** 固定 UA，只表明「哪个客户端」，不含机型/版本之外的任何东西。 */
    private static final String UA = "AstrBotCandle";

    private AtomNet() {
    }

    /**
     * POST 一段 JSON，返回 {"ok":bool,"code":int,"err":"..."}：
     *   ok=true  → 2xx；code 是 HTTP 状态码；err 为空串；
     *   ok=false → code=0 表示根本没连上（DNS / 超时 / 证书 / 断网），err 是原因；
     *              code&gt;0 表示连上了但服务端不开心（403、500…），err 带一小段响应。
     * 本方法从不抛异常：上报失败只值得记一笔，不值得把守护线程打穿。
     *
     * 调用方必须在后台线程（见类注释）。
     */
    static String post(String path, String json) {
        return send("POST", path, json, null, READ_TIMEOUT_MS, MAX_REPLY).json();
    }

    /**
     * GET 一个接口（目前只有公告），可带 If-None-Match。
     * 结果原样装进 AtomReply：调用方自己看 code（304 / 200 / 其它）、body、etag。
     * 本方法同样从不抛异常，调用方必须在后台线程。
     */
    static AtomReply get(String path, String etag) {
        return send("GET", path, null, etag, READ_TIMEOUT_GET_MS, MAX_BODY);
    }

    /**
     * 真正干活的那个：发一次请求，结果装进 AtomReply。
     * method 只有 "GET" / "POST" 两种；body 为 null 表示不发请求体（GET）。
     * 从不抛异常——调用方多半在后台线程，抛出去只会静默杀掉线程。
     */
    private static AtomReply send(String method, String path, String body, String etag,
                                  int readMs, int maxReply) {
        HttpURLConnection c = null;
        OutputStream out = null;
        try {
            byte[] raw = (body == null) ? null : body.getBytes("UTF-8");
            URL u = new URL(BASE + path);
            // https 时 openConnection 实际返回 HttpsURLConnection，它本来就是
            // HttpURLConnection 的子类，这里统一按父类用，不额外 import javax.net.ssl。
            c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(readMs);
            // 3xx 一律当失败：不跟去别的主机，也不替服务端做跳转决策
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty(HEADER_ATOM, "1");
            if (etag != null && etag.length() > 0) c.setRequestProperty("If-None-Match", etag);

            if (raw != null) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(raw.length);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                out = c.getOutputStream();
                out.write(raw);
                out.flush();
                out.close();
                out = null;
            }

            int code = c.getResponseCode();
            String tag = c.getHeaderField("ETag");
            // 304 没有正文：先认出来，别去碰输入流（不同实现对 304 的 stream 行为不一致）
            if (code == 304) return new AtomReply(false, 304, "", tag, "");
            boolean ok = (code >= 200 && code < 300);
            InputStream in = null;
            try {
                in = ok ? c.getInputStream() : c.getErrorStream();
                String reply = (in == null) ? "" : read(in, maxReply);
                if (ok) return new AtomReply(true, code, reply, tag, "");
                return new AtomReply(false, code, "", tag, brief(reply));
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            return new AtomReply(false, 0, "", "", String.valueOf(t));
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 手拼结果 JSON：不引入 org.json，本类才能脱离 android.jar 在桌面 JVM 上直接跑。
     * 只有 ok/code/err 三个键，err 来自异常或响应片段，必须转义。
     */
    static String result(boolean ok, int code, String err) {
        StringBuilder b = new StringBuilder(64);
        b.append("{\"ok\":").append(ok)
                .append(",\"code\":").append(code)
                .append(",\"err\":\"").append(esc(err))
                .append("\"}");
        return b.toString();
    }

    /**
     * JSON 字符串转义。上报体里的字段全是短字符串（versionName / 崩溃签名 / 模块名 / ABI），
     * 为两个引号去引入一个解析库不划算；全应用的上报体都走这一份转义，
     * 不要在各 Task 里再抄一遍（QqbotBridge.failJson 那份是桥专用的，别混用）。
     */
    static String esc(String s) {
        if (s == null) return "";
        int n = s.length();
        StringBuilder b = new StringBuilder(n + 16);
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    b.append("\\\"");
                    break;
                case '\\':
                    b.append("\\\\");
                    break;
                case '\n':
                    b.append("\\n");
                    break;
                case '\r':
                    b.append("\\r");
                    break;
                case '\t':
                    b.append("\\t");
                    break;
                case '\b':
                    b.append("\\b");
                    break;
                case '\f':
                    b.append("\\f");
                    break;
                default:
                    if (c < 0x20 || c == 0x7f) {
                        b.append("\\u");
                        String h = Integer.toHexString(c);
                        for (int k = h.length(); k < 4; k++) b.append('0');
                        b.append(h);
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.toString();
    }

    /** 失败时只留一小段响应：够看出 403 还是 500，又不会把响应体整篇塞进 state/upload.json。 */
    private static String brief(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(64);
        int n = s.length();
        for (int i = 0; i < n && b.length() < 160; i++) {
            char ch = s.charAt(i);
            if (ch == '\n' || ch == '\r' || ch == '\t') ch = ' ';
            b.append(ch);
        }
        return b.toString().trim();
    }

    /** 有界读：最多 max 字节，UTF-8。读失败当空串。 */
    private static String read(InputStream in, int max) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(256);
            byte[] buf = new byte[1024];
            int total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                if (total + n > max) {
                    bo.write(buf, 0, max - total);
                    break;
                }
                bo.write(buf, 0, n);
                total += n;
            }
            return bo.toString("UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }
}
