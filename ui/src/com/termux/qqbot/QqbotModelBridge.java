package com.termux.app;

import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 「测试模型」桥（主页模型卡片 / 插件页的模型自测都走这里）。
 *
 * 一句话：**在 Java 里直接发一条 OpenAI 兼容的 /chat/completions**，把 HTTP 状态码、
 * 返回体片段和人话原因一起带回界面。
 *
 * 为什么不再走沙盒里的 model-test.py（beta.2 的做法，用户报「模型测不通过」的根因）：
 *   1) 那条路要 source common.sh → qq_proot → venv python，任何一环没起来就只剩
 *      「没有任何输出」——用户拿不到原因，我们也没法从日志里定位；
 *   2) proot 冷启动 2~4 秒，桥方法又跑在 JavaBridge 线程上，只能异步 + 20 秒超时，
 *      超时后连 python 的 traceback 都被截断；
 *   3) 配置本来就在宿主文件系统上（files/home/ubuntu/opt/AstrBot/data/cmd_config.json），
 *      读它不需要进沙盒。
 * 代价是 provider 的选取/合并规则要在 Java 里再实现一遍（见 pickProvider/merge），
 * 必须和 AstrCfg.java:216-244 保持同一顺序，否则会出现「界面说通了、机器人却 401」。
 *
 * start()/poll() 的 JSON 契约不变：state=idle|running|ok|fail、provider、model、ms、reply、error。
 * 每次结果都会追加一行到 files/home/.qqbot/logs/model-test.log（upload-log.sh 会带走它）。
 *
 * d8 不接受 lambda，本文件只用匿名内部类之外的写法（线程用内部类 Probe）。
 */
public class QqbotModelBridge {

    /** 探针消息：越短越好，省 token 也省时间；只验证「通不通」。 */
    private static final String PROMPT = "你好，请回复 OK";
    /** 连接/读取各 12 秒：国内中转站有时首包慢，但再长用户就以为卡死了。 */
    private static final int CONNECT_TIMEOUT_MS = 12000;
    private static final int READ_TIMEOUT_MS = 12000;
    /** 日志轮转阈值：100KB，两代历史足够定位问题，再多上传日志会变慢。 */
    private static final long LOG_MAX = 100L * 1024L;
    private static final int BODY_SNIPPET = 300;

    private final QqbotActivity act;

    private static volatile boolean running = false;
    private static volatile String state = "idle";
    /** 上一次的结果 JSON（含 ok/error/provider/model/ms），poll() 直接摊平返回。 */
    private static volatile String lastJson = "";

    public QqbotModelBridge(QqbotActivity act) {
        this.act = act;
    }

    /* ======================= 对外接口 ======================= */

    /**
     * 开始测试。正在跑的时候重复点不会起第二个请求。
     * 返回当前状态 JSON（和 poll() 同形），页面点下去就能立刻决定要不要转圈。
     */
    @JavascriptInterface
    public String start() {
        synchronized (QqbotModelBridge.class) {
            if (running) return poll();
            running = true;
            state = "running";
            lastJson = "";
        }
        Thread th = new Thread(new Probe(), "qqbot-model-test");
        th.setDaemon(true);
        th.start();
        return poll();
    }

    /**
     * 当前结果。state: idle（没测过）/ running / ok / fail。
     * ok 时带 provider/model/ms/reply；fail 时带 error（HTTP 状态码 + 返回体片段 + 人话）。
     */
    @JavascriptInterface
    public String poll() {
        JSONObject out = new JSONObject();
        try {
            out.put("state", state);
            String j = lastJson;
            if (j.length() > 0) {
                try {
                    JSONObject r = new JSONObject(j);
                    String[] keys = new String[]{"provider", "model", "ms", "reply", "error", "http", "endpoint"};
                    for (int i = 0; i < keys.length; i++) {
                        if (r.has(keys[i])) out.put(keys[i], r.opt(keys[i]));
                    }
                } catch (Throwable ignored) {
                }
            }
            return out.toString();
        } catch (Throwable t) {
            return fail("读取测试状态失败：" + shortOf(t)).toString();
        }
    }

    /* ======================= 后台探测 ======================= */

    private final class Probe implements Runnable {
        public void run() {
            JSONObject r;
            try {
                r = runTest();
            } catch (Throwable t) {
                r = fail("测试出错：" + shortOf(t));
            }
            String json = r.toString();
            synchronized (QqbotModelBridge.class) {
                lastJson = json;
                state = r.optBoolean("ok", false) ? "ok" : "fail";
                running = false;
            }
            writeLog(r);
        }
    }

    /** 读 cmd_config.json → 选 provider → 发一条最小请求。 */
    private JSONObject runTest() throws Exception {
        File cfg = AstrCfg.cfgFile(act.filesDirPub());
        if (cfg == null || !cfg.isFile()) {
            return fail("找不到 cmd_config.json（" + (cfg == null ? "?" : cfg.getAbsolutePath())
                    + "）：AstrBot 还没部署好，先把主页上的部署跑完");
        }
        JSONObject root;
        try {
            root = new JSONObject(AstrCfg.readText(cfg));
        } catch (Throwable t) {
            return fail("cmd_config.json 读不出来或不是合法 JSON：" + shortOf(t));
        }
        JSONObject provider = pickProvider(root);
        if (provider == null) {
            return fail("还没配置模型：先到「模型与人格」里填接口地址、密钥和模型名");
        }
        String pid = provider.optString("id", "");
        String model = provider.optString("model", "");
        String srcId = provider.optString("provider_source_id", "");
        JSONObject src = (srcId.length() == 0) ? null : findSource(root, srcId);

        /*
         * 合并口径和 AstrBot 的 core/provider/manager.py get_merged_provider_config 一致：
         * {**source, **provider}，即 provider 上的扁平字段（老版本写法）覆盖 source。
         */
        String base = firstNonEmpty(provider.optString("api_base", ""),
                (src == null) ? "" : src.optString("api_base", ""));
        String key = firstNonEmpty(firstKey(provider), (src == null) ? "" : firstKey(src));
        String type = firstNonEmpty(provider.optString("type", ""),
                firstNonEmpty((src == null) ? "" : src.optString("type", ""),
                        provider.optString("api_type", "")));

        if (base.length() == 0) {
            return fail("这个 provider（" + pid + "）没有接口地址：到「模型与人格」里把 Base URL 填上");
        }
        if (key.length() == 0) {
            return fail("这个 provider（" + pid + "）没有密钥：到「模型与人格」里把 API Key 填上并保存");
        }
        if (model.length() == 0) {
            return fail("这个 provider（" + pid + "）没有模型名：到「模型与人格」里选一个模型");
        }
        String lowType = type.toLowerCase(Locale.ROOT);
        if (lowType.indexOf("gemini") >= 0 || lowType.indexOf("google") >= 0
                || lowType.indexOf("vertex") >= 0) {
            return fail("这个 provider 是 " + type + " 类型：" + "App 里的自测只支持 OpenAI 兼容接口，"
                    + "请到 AstrBot 控制台里测试它");
        }
        boolean anthropic = lowType.indexOf("anthropic") >= 0 || lowType.indexOf("claude") >= 0;
        String endpoint = endpointOf(base, anthropic);
        JSONObject res = post(endpoint, key, model, anthropic);
        res.put("provider", pid);
        res.put("model", model);
        res.put("endpoint", endpoint);
        return res;
    }

    /** POST 一条最小 chat 请求。所有分支都返回结构化 JSON，不抛异常给上层。 */
    private JSONObject post(String endpoint, String key, String model, boolean anthropic) {
        HttpURLConnection c = null;
        long t0 = System.currentTimeMillis();
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            JSONArray msgs = new JSONArray();
            JSONObject m = new JSONObject();
            m.put("role", "user");
            m.put("content", PROMPT);
            msgs.put(m);
            body.put("messages", msgs);
            body.put("max_tokens", 16);
            body.put("stream", false);

            URL u = new URL(endpoint);
            c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json");
            // 有些中转站按 UA 拦浏览器/脚本，AstrBot 自己用的是这个，保持一致
            c.setRequestProperty("User-Agent", "AstrBotCandle");
            if (anthropic) {
                c.setRequestProperty("x-api-key", key);
                c.setRequestProperty("anthropic-version", "2023-06-01");
            } else {
                c.setRequestProperty("Authorization", "Bearer " + key);
            }
            byte[] payload = body.toString().getBytes("UTF-8");
            c.setFixedLengthStreamingMode(payload.length);
            OutputStream os = c.getOutputStream();
            try {
                os.write(payload);
                os.flush();
            } finally {
                closeQuietly(os);
            }
            int code = c.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            String text = (in == null) ? "" : readAll(in);
            long ms = System.currentTimeMillis() - t0;

            JSONObject r = new JSONObject();
            r.put("ok", code >= 200 && code < 300);
            r.put("ms", ms);
            r.put("http", code);
            if (code >= 200 && code < 300) {
                String reply = replyOf(text, anthropic);
                if (reply.length() == 0) {
                    r.put("ok", false);
                    r.put("error", "HTTP " + code + " · 接口回了内容但取不到回复；返回：" + snippet(text));
                } else {
                    r.put("reply", reply);
                }
            } else {
                r.put("error", humanFor(code, text));
            }
            return r;
        } catch (Throwable t) {
            long ms = System.currentTimeMillis() - t0;
            JSONObject r = new JSONObject();
            try {
                r.put("ok", false);
                r.put("ms", ms);
                r.put("http", 0);
                r.put("error", humanForThrowable(t));
            } catch (Throwable ignored) {
            }
            return r;
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 从 200 的返回体里取回复文本：OpenAI（choices[0].message.content）/ Anthropic（content[0].text）。 */
    private static String replyOf(String text, boolean anthropic) {
        try {
            JSONObject o = new JSONObject(text);
            if (anthropic) {
                JSONArray arr = o.optJSONArray("content");
                if (arr != null) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject part = arr.optJSONObject(i);
                        if (part != null) sb.append(part.optString("text", ""));
                    }
                    String s = sb.toString().trim();
                    if (s.length() > 0) return s;
                }
            }
            JSONArray ch = o.optJSONArray("choices");
            if (ch != null && ch.length() > 0) {
                JSONObject c0 = ch.optJSONObject(0);
                if (c0 != null) {
                    JSONObject msg = c0.optJSONObject("message");
                    String s = (msg == null) ? "" : msg.optString("content", "");
                    if (s.length() == 0) s = c0.optString("text", "");
                    s = s.trim();
                    if (s.length() > 0) return s;
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /* ======================= 配置读取（与 AstrCfg 同序） ======================= */

    /**
     * 选「当前实际在用」的 provider。
     * 顺序必须与 AstrCfg.pickProvider（AstrCfg.java:216-244）一致，否则界面测通、机器人却用另一条：
     *   ① agent_runner.config.model.provider_id 指定的那条
     *   ② id=candle（App 自己写的那条）
     *   ③ provider[] 里第一条 enable 且带 provider_source_id 的
     *   ④ 第一条 enable 的
     */
    private static JSONObject pickProvider(JSONObject root) {
        JSONObject ar = root.optJSONObject("agent_runner");
        if (ar != null) {
            JSONObject ac = ar.optJSONObject("config");
            JSONObject mm = (ac == null) ? null : ac.optJSONObject("model");
            String want = (mm == null) ? "" : mm.optString("provider_id", "");
            if (want.length() > 0) {
                JSONObject hit = findProviderById(root, want);
                if (hit != null) return hit;
            }
        }
        JSONObject p = findProviderById(root, AstrCfg.PROVIDER_ID);
        if (p != null) return p;
        JSONArray arr = root.optJSONArray("provider");
        if (arr == null) return null;
        JSONObject first = null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (!o.optBoolean("enable", true)) continue;
            if (first == null) first = o;
            if (o.optString("provider_source_id", "").length() > 0) return o;
        }
        return first;
    }

    private static JSONObject findProviderById(JSONObject root, String id) {
        JSONArray arr = root.optJSONArray("provider");
        if (arr == null || id == null || id.length() == 0) return null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && id.equals(o.optString("id", ""))) return o;
        }
        return null;
    }

    private static JSONObject findSource(JSONObject root, String sid) {
        JSONArray arr = root.optJSONArray("provider_sources");
        if (arr == null) return null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && sid.equals(o.optString("id", ""))) return o;
        }
        return null;
    }

    /** key 在新结构里是数组（多把轮询，元素可能是字符串或 {key:...}），老结构是字符串。 */
    private static String firstKey(JSONObject o) {
        if (o == null) return "";
        JSONArray ks = o.optJSONArray("key");
        if (ks != null && ks.length() > 0) {
            Object v = ks.opt(0);
            if (v instanceof JSONObject) return ((JSONObject) v).optString("key", "");
            return ks.optString(0, "");
        }
        return o.optString("key", "");
    }

    /** 接口地址 → 完整 endpoint。已经写了 /chat/completions 的地址原样用（用户常直接粘全路径）。 */
    private static String endpointOf(String base, boolean anthropic) {
        String b = (base == null) ? "" : base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.length() == 0) return "";
        String tail = anthropic ? "/messages" : "/chat/completions";
        if (b.endsWith(tail)) return b;
        return b + tail;
    }

    /* ======================= 错误 → 人话 ======================= */

    private static String humanFor(int code, String text) {
        String why;
        if (code == 401) why = "密钥无效或还没生效（改完密钥要保存，必要时重启 AstrBot）";
        else if (code == 403) why = "没有权限，或这个接口按地区/IP 限制了";
        else if (code == 404) why = "接口地址或模型名不对（检查 Base URL 结尾，和模型名拼写）";
        else if (code == 429) why = "被限流了，或余额不足/额度用完了";
        else if (code == 400) why = "请求被拒绝：多半是模型名不对，或这个接口不认这条请求格式";
        else if (code >= 500) why = "对方服务器出错（不是你的配置问题，稍后再试）";
        else why = "接口返回了意料之外的错误";
        // 401 而且没回内容时，再给一句下一步，别让用户对着空返回发呆。
        String next = (code == 401 && isEmptyBody(text))
                ? "；先在控制台确认这家密钥能用，再点这里重试" : "";
        return "HTTP " + code + " · " + why + next + "；返回：" + snippet(text);
    }

    private static String humanForThrowable(Throwable t) {
        String n = t.getClass().getSimpleName();
        String msg = String.valueOf(t.getMessage());
        String why;
        if (n.indexOf("UnknownHost") >= 0) why = "域名解析失败：检查手机网络，或 Base URL 的域名拼错了";
        else if (n.indexOf("SocketTimeout") >= 0) why = "超时（" + (CONNECT_TIMEOUT_MS / 1000)
                + " 秒没响应）：网络不通，或这个接口地址在国内打不开";
        else if (n.indexOf("Connect") >= 0) why = "连不上这个地址：域名/端口不对，或对方没开";
        else if (n.indexOf("SSL") >= 0 || n.indexOf("Cert") >= 0) why = "证书校验失败：地址被劫持，或对方证书过期";
        else why = "请求没发出去";
        return why + "（" + n + (msg.length() > 0 ? "：" + msg : "") + "）";
    }

    /** 返回体是不是空的（全是空白也算空）。 */
    private static boolean isEmptyBody(String text) {
        return text == null || text.trim().length() == 0;
    }

    private static String snippet(String text) {
        String s = (text == null) ? "" : text.replace("\r", " ").replace("\n", " ").trim();
        if (s.length() == 0) {
            // 有的网关（JDK 的 HttpURLConnection 对 401/407 也会）吞掉错误体，别只回一句「空」。
            return "（服务端没有返回内容；通常是密钥无效、未生效或额度已用完，也可能是接口要求别的鉴权方式）";
        }
        if (s.length() > BODY_SNIPPET) s = s.substring(0, BODY_SNIPPET) + "…";
        return s;
    }

    /* ======================= 日志 ======================= */

    /**
     * 每次结果追加一行。上传日志时 build/opt/qqbot/upload-log.sh 会把这段带走，
     * 用户以后再报「模型测不通过」，我们直接能看到 HTTP 码和返回体。
     * 行首固定 [model-test]，grep 得到。
     */
    private void writeLog(JSONObject r) {
        try {
            File dir = new File(act.qqbotDir(), "logs");
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            File f = new File(dir, "model-test.log");
            rotate(f);
            String line = "[model-test] " + now() + " state=" + (r.optBoolean("ok", false) ? "ok" : "fail")
                    + " provider=" + one(r.optString("provider", ""))
                    + " model=" + one(r.optString("model", ""))
                    + " ms=" + r.optLong("ms", 0L)
                    + " http=" + r.optInt("http", 0)
                    + " err=" + one(r.optString("error", ""))
                    + "\n";
            FileOutputStream out = new FileOutputStream(f, true);
            try {
                out.write(line.getBytes("UTF-8"));
                out.flush();
            } finally {
                closeQuietly(out);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 100KB 就轮转一次：保留一代历史，别把用户存储写满。 */
    private static void rotate(File f) {
        try {
            if (!f.isFile() || f.length() < LOG_MAX) return;
            File old = new File(f.getParentFile(), f.getName() + ".1");
            if (old.isFile()) old.delete();
            f.renameTo(old);
        } catch (Throwable ignored) {
        }
    }

    private static String now() {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date());
        } catch (Throwable t) {
            return "";
        }
    }

    /** 日志是一行一条：把换行和多余空白压掉，避免撑坏 grep。 */
    private static String one(String s) {
        String v = (s == null) ? "" : s.replace("\r", " ").replace("\n", " ").trim();
        if (v.length() > BODY_SNIPPET) v = v.substring(0, BODY_SNIPPET) + "…";
        return v;
    }

    /* ======================= 工具 ======================= */

    private static JSONObject fail(String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("error", msg);
        } catch (Throwable ignored) {
        }
        return o;
    }

    private static String readAll(InputStream in) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        } finally {
            closeQuietly(in);
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) return;
        try {
            in.close();
        } catch (Throwable ignored) {
        }
    }

    private static void closeQuietly(OutputStream out) {
        if (out == null) return;
        try {
            out.close();
        } catch (Throwable ignored) {
        }
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && a.trim().length() > 0) return a.trim();
        if (b != null && b.trim().length() > 0) return b.trim();
        return "";
    }

    private static String shortOf(Throwable t) {
        String m = String.valueOf(t);
        if (m.length() > 180) m = m.substring(0, 180) + "…";
        return m;
    }
}
