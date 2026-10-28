package com.termux.app;

import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 「Provider 体检」桥（设置页 #provCard 用，JS 名 AndroidProv）。
 *
 * 一句话：把 cmd_config.json 里配过的每一条 provider 都真发一条最短请求，产出一张
 * 「谁能用、谁偏慢、谁根本不通」的表。
 *
 * 为什么不复用 QqbotModelBridge：那边测的是「当前正在用的那一条」（用户点一下只想确认
 * 机器人现在能不能回话），结果只留一条、超时 12 秒；这里要的是全量清单，必须串行、单条
 * 10 秒封顶、整轮 60 秒内出结果，否则设置页会一直转圈。配置解析与 HTTP 细节按契约从它
 * 抄一份，原文件一个字都不改（它同时被主页模型卡用着，动它风险大）。
 *
 * 轮询协议：start() 立刻返回 running 并用后台线程逐条测；poll() 可以随便调（界面每秒拉
 * 一次），每测完一条就原地更新，用户不用等整轮结束才看见东西。
 */
public class QqbotProviderBridge {

    /** 探针提示词：契约要求就用「OK」，越短越省钱，也不容易撞上内容审查。 */
    private static final String PROMPT = "OK";
    /** 契约：单条 10 秒超时。拆成连接 4 秒 + 读取 6 秒，任一段卡住都不会超过 10 秒。 */
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 6000;
    /**
     * 契约：整轮 60 秒。这里用 55 秒的软预算，留 5 秒给收尾（写日志、改状态），
     * 否则最后一条刚发出去就撞线，界面会停在 running 上。
     */
    private static final long TOTAL_BUDGET_MS = 55000L;
    /** 契约：结果缓存 5 分钟。真发请求要花钱，来回切页面不该反复打接口。 */
    private static final long CACHE_MS = 5L * 60L * 1000L;
    /** 超过 2 秒算「慢」：正常 OpenAI 兼容接口回 8 个 token 一般 300~900ms。 */
    private static final long SLOW_MS = 2000L;
    /** 日志 100KB 轮转，和 model-test.log 同一套规矩（upload-log.sh 会一起带走）。 */
    private static final long LOG_MAX = 100L * 1024L;
    /** provider 多于这个数只测前 32 条：一轮最多 5 分钟，再多就是在烧用户的钱。 */
    private static final int MAX_PROVIDERS = 32;
    private static final int BODY_SNIPPET = 200;

    private final File filesDir;

    /*
     * 一把锁保护下面 5 个字段：后台线程写、JS 线程读，必须串行。
     * gen 是「轮次代号」——线程停不下来，只能让上一轮的结果写不进来（clear() 后重测会用到）。
     */
    private static final Object LOCK = new Object();
    private static volatile boolean running = false;
    private static volatile String state = "idle";
    private static volatile long ts = 0L;
    private static volatile JSONArray items = new JSONArray();
    private static int gen = 0;
    /**
     * 「这张表是只重测一条得来的」标记：单条重测不该续上整轮的 5 分钟缓存，
     * 否则用户点完单条再点「全部体检」会被缓存挡住，看着像没反应。
     */
    private static volatile boolean partial = false;

    public QqbotProviderBridge(QqbotActivity act) {
        this.filesDir = act.filesDirPub();
    }

    /** 给不拿 Activity 的调用方（自检/服务）用，路径口径与 Activity 版一致。 */
    public QqbotProviderBridge(File filesDir) {
        this.filesDir = filesDir;
    }

    /* ======================= 对外接口 ======================= */

    /**
     * 开始一轮体检。正在跑时返回当前状态（不会起第二条线程）；5 分钟内刚测过则直接回上次
     * 的结果——用户只是想看看，没必要再打一遍所有接口；要强制重测先 clear()。
     */
    @JavascriptInterface
    public String start() {
        try {
            List<Row> rows = new ArrayList<Row>();
            JSONArray list = new JSONArray();
            int myGen;
            long runTs;
            String fatal;
            synchronized (LOCK) {
                if (running) return poll();
                long now = System.currentTimeMillis();
                if (!partial && "done".equals(state) && items.length() > 0 && (now - ts) < CACHE_MS) {
                    return poll();
                }
                fatal = buildRows(filesDir, rows, list);
                gen++;
                partial = false;
                myGen = gen;
                runTs = now;
                running = true;
                state = "running";
                ts = now;
                items = list;
            }
            if (fatal.length() > 0) {
                // 配置根本读不出来：已经有一条红行说明原因了，别起线程空转
                synchronized (LOCK) {
                    if (myGen == gen) {
                        state = "done";
                        running = false;
                        ts = System.currentTimeMillis();
                    }
                }
                return poll();
            }
            Thread th = new Thread(new Probe(myGen, runTs, filesDir, rows), "qqbot-prov-check");
            th.setDaemon(true);
            th.start();
            return poll();
        } catch (Throwable t) {
            return fail("准备体检失败：" + shortOf(t)).toString();
        }
    }

    /**
     * 当前状态：idle（没测过）/ running（正在测）/ done（测完了）。
     * items 里每条：id/name/model/base/state(ok|slow|bad|running)/ms/http/err。
     */
    @JavascriptInterface
    public String poll() {
        JSONObject out = new JSONObject();
        try {
            synchronized (LOCK) {
                out.put("state", state);
                out.put("ts", ts);
                // 深拷贝：界面线程拿到后就不再和后台线程共享同一个数组
                out.put("items", new JSONArray(items.toString()));
            }
            return out.toString();
        } catch (Throwable t) {
            return fail("读取体检结果失败：" + shortOf(t)).toString();
        }
    }

    /** 清空结果并让正在跑的那一轮作废（下一次 start() 会真的重测）。 */
    @JavascriptInterface
    public String clear() {
        JSONObject out = new JSONObject();
        try {
            synchronized (LOCK) {
                gen++;
                partial = false;
                running = false;
                state = "idle";
                ts = 0L;
                items = new JSONArray();
            }
            out.put("ok", true);
            return out.toString();
        } catch (Throwable t) {
            return fail("清空体检结果失败：" + shortOf(t)).toString();
        }
    }

    /**
     * 只重测一条（非契约扩展，界面每行的「重测」按钮用；id 取 items 里的 id）。
     *
     * 与 start() 的区别：不动其它条目，它们保留上一次的结果；目标那条先标 running，后台跑完
     * 原地更新，整张表随之变成 done。返回形状与 poll() 一致，界面不用写第二套渲染。
     */
    @JavascriptInterface
    public String startOne(String id) {
        try {
            String want = (id == null) ? "" : id.trim();
            if (want.length() == 0) {
                return fail("没说要重测哪一条：id 是空的").toString();
            }
            List<Row> rows = new ArrayList<Row>();
            JSONArray fresh = new JSONArray();
            JSONArray merged = new JSONArray();
            Row target = null;
            String notFound = "";
            String fatal;
            int targetIndex = -1;
            int myGen = 0;
            long runTs = 0L;
            synchronized (LOCK) {
                fatal = buildRows(filesDir, rows, fresh);
                if (fatal.length() > 0) {
                    // 配置读不出来：整表换成那条说明（buildRows 已经塞好了），别起线程空转
                    gen++;
                    partial = false;
                    running = false;
                    state = "done";
                    ts = System.currentTimeMillis();
                    items = fresh;
                } else {
                    for (int i = 0; i < rows.size(); i++) {
                        if (rows.get(i).id.equals(want)) {
                            target = rows.get(i);
                            targetIndex = i;
                            break;
                        }
                    }
                    if (target == null) {
                        notFound = "没找到 id 是「" + want + "」的 provider：配置可能改过了，先点「全部体检」刷新一遍";
                    } else {
                        for (int i = 0; i < rows.size(); i++) {
                            Row r = rows.get(i);
                            if (i == targetIndex) merged.put(itemOf(r, "running", 0L, 0, ""));
                            else merged.put(carryOver(r, findItem(items, r.id)));
                        }
                        // buildRows 可能还在末尾塞了一条说明（比如 provider 超过 32 条），照抄，别丢
                        for (int i = rows.size(); i < fresh.length(); i++) {
                            JSONObject extra = fresh.optJSONObject(i);
                            if (extra != null) merged.put(extra);
                        }
                        gen++;
                        partial = true;
                        myGen = gen;
                        runTs = System.currentTimeMillis();
                        running = true;
                        state = "running";
                        ts = runTs;
                        items = merged;
                    }
                }
            }
            if (fatal.length() > 0) return poll();
            if (notFound.length() > 0) return fail(notFound).toString();
            Thread th = new Thread(new OneProbe(myGen, runTs, filesDir, target, targetIndex), "qqbot-prov-one");
            th.setDaemon(true);
            th.start();
            return poll();
        } catch (Throwable t) {
            return fail("重测这一条失败：" + shortOf(t)).toString();
        }
    }

    /* ======================= 后台体检线程 ======================= */

    private static final class Probe implements Runnable {

        private final int gen0;
        private final long runTs;
        private final File filesDir;
        private final List<Row> rows;

        Probe(int gen0, long runTs, File filesDir, List<Row> rows) {
            this.gen0 = gen0;
            this.runTs = runTs;
            this.filesDir = filesDir;
            this.rows = new ArrayList<Row>(rows);
        }

        public void run() {
            long start = System.currentTimeMillis();
            int ok = 0;
            int slow = 0;
            int bad = 0;
            try {
                for (int i = 0; i < rows.size(); i++) {
                    if (stale(gen0)) return;
                    Row r = rows.get(i);
                    long left = TOTAL_BUDGET_MS - (System.currentTimeMillis() - start);
                    JSONObject res;
                    if (left < 1500L) {
                        // 预算用完了：剩下的如实说「没测」，别假装通过
                        res = result("bad", 0L, 0, "整轮超过 60 秒，这一条没来得及测（provider 太多，先删掉不用的）");
                    } else {
                        res = probeOne(r, (int) Math.min((long) (CONNECT_TIMEOUT_MS + READ_TIMEOUT_MS), left));
                    }
                    String st = res.optString("state", "bad");
                    if ("ok".equals(st)) ok++;
                    else if ("slow".equals(st)) slow++;
                    else bad++;
                    update(gen0, r.index, r, res);
                    writeLog(filesDir, logLine(runTs, r, res));
                }
                writeLog(filesDir, "[provider-check] " + now() + " run=" + runTs + " done ok=" + ok
                        + " slow=" + slow + " bad=" + bad + " total=" + rows.size()
                        + " ms=" + (System.currentTimeMillis() - start));
            } catch (Throwable t) {
                writeLog(filesDir, "[provider-check] " + now() + " run=" + runTs
                        + " crash=" + one(shortOf(t)));
            } finally {
                finish(gen0);
            }
        }
    }

    /** 只测一条的后台线程：跑完原地写回该行，整张表直接变 done（其它行是上一轮的结果）。 */
    private static final class OneProbe implements Runnable {

        private final int gen0;
        private final long runTs;
        private final File filesDir;
        private final Row row;
        private final int index;

        OneProbe(int gen0, long runTs, File filesDir, Row row, int index) {
            this.gen0 = gen0;
            this.runTs = runTs;
            this.filesDir = filesDir;
            this.row = row;
            this.index = index;
        }

        public void run() {
            try {
                if (stale(gen0)) return;
                writeLog(filesDir, "[provider-check] " + now() + " run=" + runTs
                        + " single=1 id=" + one(row.id) + " name=" + one(row.name));
                JSONObject res = probeOne(row, CONNECT_TIMEOUT_MS + READ_TIMEOUT_MS);
                update(gen0, index, row, res);
                writeLog(filesDir, logLine(runTs, row, res));
            } catch (Throwable t) {
                writeLog(filesDir, "[provider-check] " + now() + " run=" + runTs
                        + " single=1 id=" + one(row.id) + " crash=" + one(shortOf(t)));
            } finally {
                finish(gen0);
            }
        }
    }

    /** 单条：能测就发，不能测（缺地址/密钥/模型名、类型不支持）就直接给人话原因。 */
    private static JSONObject probeOne(Row r, int budgetMs) {
        try {
            if (r.problem.length() > 0) return result("bad", 0L, 0, r.problem);
            String endpoint = endpointOf(r.base, r.anthropic);
            JSONObject p = post(endpoint, r.key, r.model, r.anthropic, budgetMs);
            boolean ok = p.optBoolean("ok", false);
            long ms = p.optLong("ms", 0L);
            int http = p.optInt("http", 0);
            String err = p.optString("err", "");
            if (!ok) return result("bad", ms, http, err);
            if (ms > SLOW_MS) return result("slow", ms, http, "");
            return result("ok", ms, http, "");
        } catch (Throwable t) {
            return result("bad", 0L, 0, humanForThrowable(t));
        }
    }

    /** POST 一条最小 chat 请求。所有分支都返回结构化结果，不抛异常给上层。 */
    private static JSONObject post(String endpoint, String key, String model, boolean anthropic, int budgetMs) {
        HttpURLConnection c = null;
        long t0 = System.currentTimeMillis();
        JSONObject r = new JSONObject();
        int connect = budgetMs / 2;
        if (connect < 1500) connect = 1500;
        int read = budgetMs - connect;
        if (read < 1500) read = 1500;
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            JSONArray msgs = new JSONArray();
            JSONObject m = new JSONObject();
            m.put("role", "user");
            m.put("content", PROMPT);
            msgs.put(m);
            body.put("messages", msgs);
            body.put("max_tokens", 8);
            body.put("stream", false);

            URL u = new URL(endpoint);
            c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(connect);
            c.setReadTimeout(read);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json");
            // 有些中转站按 UA 拦脚本，跟 AstrBot 自己保持一致
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
            r.put("ms", ms);
            r.put("http", code);
            if (code >= 200 && code < 300) {
                String bad = bodyProblem(text, anthropic);
                if (bad.length() > 0) {
                    r.put("ok", false);
                    r.put("err", bad);
                } else {
                    r.put("ok", true);
                }
            } else {
                r.put("ok", false);
                r.put("err", humanFor(code, text));
            }
            return r;
        } catch (Throwable t) {
            long ms = System.currentTimeMillis() - t0;
            try {
                r.put("ok", false);
                r.put("ms", ms);
                r.put("http", 0);
                r.put("err", humanForThrowable(t));
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

    /**
     * 200 也要再看一眼内容：不少中转站在额度用完时回 200 + {"error":...}，
     * 只认状态码会把它当「能用」，用户配好机器人却发现不回复。
     * 返回空串表示看起来是正常的对话响应。
     */
    private static String bodyProblem(String text, boolean anthropic) {
        if (text == null || text.trim().length() == 0) {
            return "HTTP 200 但接口没返回内容（可能被网关吞了，先确认是不是 OpenAI 兼容接口）";
        }
        try {
            JSONObject o = new JSONObject(text);
            Object err = o.opt("error");
            if (err != null && !JSONObject.NULL.equals(err)) {
                String msg = "";
                if (err instanceof JSONObject) msg = ((JSONObject) err).optString("message", "");
                else msg = String.valueOf(err);
                return "接口回了 200，但内容是错误：" + snippet(msg.length() > 0 ? msg : text);
            }
            if (anthropic) {
                JSONArray arr = o.optJSONArray("content");
                if (arr != null && arr.length() > 0) return "";
            }
            JSONArray ch = o.optJSONArray("choices");
            if (ch != null && ch.length() > 0) return "";
            return "HTTP 200 但不是对话响应（没有 choices）：可能 Base URL 指错了接口；返回：" + snippet(text);
        } catch (Throwable t) {
            return "HTTP 200 但返回体不是 JSON：这个地址可能不是 OpenAI 兼容接口；返回：" + snippet(text);
        }
    }

    /* ======================= 配置解析（口径与 AstrCfg / QqbotModelBridge 一致） ======================= */

    /** 一行体检对象：index 是它在 items 里的下标，problem 非空表示根本发不出去。 */
    private static final class Row {
        int index;
        String id = "";
        String name = "";
        String model = "";
        String base = "";
        String key = "";
        String type = "";
        String problem = "";
        boolean anthropic = false;
    }

    /**
     * 读 cmd_config.json，把每一条 provider 摊成一行。
     * 返回非空字符串表示整份配置不可用（已经往 list 里塞了一条红行说明原因）。
     */
    private static String buildRows(File filesDir, List<Row> rows, JSONArray list) {
        try {
            File cfg = AstrCfg.cfgFile(filesDir);
            if (cfg == null || !cfg.isFile()) {
                addBroken(list, "找不到 cmd_config.json（" + (cfg == null ? "?" : cfg.getAbsolutePath())
                        + "）：AstrBot 还没部署好，先把主页上的部署跑完");
                return "config-missing";
            }
            JSONObject root;
            try {
                root = new JSONObject(AstrCfg.readText(cfg));
            } catch (Throwable t) {
                addBroken(list, "cmd_config.json 读不出来或不是合法 JSON：" + shortOf(t));
                return "config-broken";
            }
            JSONArray arr = root.optJSONArray("provider");
            if (arr == null || arr.length() == 0) {
                addBroken(list, "配置里还没有 provider：先到「模型与人格」里填接口地址、密钥和模型名");
                return "no-provider";
            }
            int n = 0;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.optJSONObject(i);
                if (p == null) continue;
                if (n >= MAX_PROVIDERS) {
                    addBroken(list, "provider 超过 " + MAX_PROVIDERS + " 条，后面的没测");
                    break;
                }
                Row r = new Row();
                r.id = p.optString("id", "");
                if (r.id.length() == 0) r.id = "provider-" + (i + 1);
                r.name = p.optString("name", "");
                if (r.name.length() == 0) r.name = r.id;
                r.model = p.optString("model", "");
                /*
                 * 合并口径和 AstrBot 的 get_merged_provider_config 一致：{**source, **provider}，
                 * 即 provider 上的扁平字段（老版本写法）覆盖 source 里的连接信息。
                 */
                JSONObject src = findSource(root, p.optString("provider_source_id", ""));
                r.base = firstNonEmpty(p.optString("api_base", ""),
                        (src == null) ? "" : src.optString("api_base", ""));
                r.key = firstNonEmpty(firstKey(p), (src == null) ? "" : firstKey(src));
                r.type = firstNonEmpty(p.optString("type", ""),
                        firstNonEmpty((src == null) ? "" : src.optString("type", ""),
                                p.optString("api_type", "")));
                String low = r.type.toLowerCase(Locale.ROOT);
                r.anthropic = low.indexOf("anthropic") >= 0 || low.indexOf("claude") >= 0;
                boolean unsupported = low.indexOf("gemini") >= 0 || low.indexOf("google") >= 0
                        || low.indexOf("vertex") >= 0;
                if (r.base.length() == 0) r.problem = "没填接口地址（Base URL）：到「模型与人格」里补上";
                else if (r.key.length() == 0) r.problem = "没填密钥（API Key）：到「模型与人格」里补上并保存";
                else if (r.model.length() == 0) r.problem = "没填模型名：到「模型与人格」里选一个";
                else if (unsupported) {
                    r.problem = "这是 " + r.type + " 类型：App 内只会发 OpenAI 兼容的 /chat/completions，"
                            + "请到 AstrBot 控制台里测它";
                }
                r.index = list.length();
                rows.add(r);
                list.put(itemOf(r, "running", 0L, 0, ""));
                n++;
            }
            return "";
        } catch (Throwable t) {
            addBroken(list, "读配置出错：" + shortOf(t));
            return "config-error";
        }
    }

    private static void addBroken(JSONArray list, String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", "config");
            o.put("name", "配置");
            o.put("model", "");
            o.put("base", "");
            o.put("state", "bad");
            o.put("ms", 0L);
            o.put("http", 0);
            o.put("err", msg);
            list.put(o);
        } catch (Throwable ignored) {
        }
    }

    private static JSONObject findSource(JSONObject root, String sid) {
        if (sid == null || sid.length() == 0) return null;
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
        try {
            JSONArray ks = o.optJSONArray("key");
            if (ks != null && ks.length() > 0) {
                Object v = ks.opt(0);
                if (v instanceof JSONObject) return ((JSONObject) v).optString("key", "");
                return ks.optString(0, "");
            }
        } catch (Throwable ignored) {
        }
        return o.optString("key", "");
    }

    /** 接口地址 → 完整 endpoint；用户常直接粘带 /chat/completions 的全路径，那种就原样用。 */
    private static String endpointOf(String base, boolean anthropic) {
        String b = (base == null) ? "" : base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.length() == 0) return "";
        String tail = anthropic ? "/messages" : "/chat/completions";
        if (b.endsWith(tail)) return b;
        return b + tail;
    }

    /* ======================= 状态写回 ======================= */

    private static void update(int gen0, int index, Row r, JSONObject res) {
        synchronized (LOCK) {
            if (gen0 != gen) return;
            try {
                items.put(index, itemOf(r, res.optString("state", "bad"), res.optLong("ms", 0L),
                        res.optInt("http", 0), res.optString("err", "")));
            } catch (Throwable ignored) {
            }
        }
    }

    private static void finish(int gen0) {
        synchronized (LOCK) {
            if (gen0 != gen) return;
            running = false;
            state = "done";
            ts = System.currentTimeMillis();
        }
    }

    private static boolean stale(int gen0) {
        synchronized (LOCK) {
            return gen0 != gen;
        }
    }

    private static JSONObject result(String state, long ms, int http, String err) {
        JSONObject o = new JSONObject();
        try {
            o.put("state", state);
            o.put("ms", ms);
            o.put("http", http);
            o.put("err", err);
        } catch (Throwable ignored) {
        }
        return o;
    }

    private static JSONObject itemOf(Row r, String state, long ms, int http, String err) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", r.id);
            o.put("name", r.name);
            o.put("model", r.model);
            o.put("base", r.base);
            o.put("state", state);
            o.put("ms", ms);
            o.put("http", http);
            o.put("err", err);
        } catch (Throwable ignored) {
        }
        return o;
    }

    /** 从上一轮 items 里按 id 取回那条结果；没有就返回 null。 */
    private static JSONObject findItem(JSONArray list, String id) {
        if (list == null || id == null || id.length() == 0) return null;
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o != null && id.equals(o.optString("id", ""))) return o;
        }
        return null;
    }

    /**
     * 没重测的条目：有上次结果就原样沿用（名称/模型/地址按当前配置刷新，配置改过也不会显示旧名）；
     * 从来没测过、或上一轮被这次重测打断的，别一直挂着「正在测」，如实说明要跑整轮。
     */
    private static JSONObject carryOver(Row r, JSONObject old) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", r.id);
            o.put("name", r.name);
            o.put("model", r.model);
            o.put("base", r.base);
            String st = (old == null) ? "" : old.optString("state", "");
            if (st.length() == 0 || "running".equals(st)) {
                o.put("state", "bad");
                o.put("ms", 0L);
                o.put("http", 0);
                o.put("err", (old == null) ? "还没测过：点「全部体检」跑一遍" : "上一轮没测完：点「全部体检」重跑一遍");
            } else {
                o.put("state", st);
                o.put("ms", old.optLong("ms", 0L));
                o.put("http", old.optInt("http", 0));
                o.put("err", old.optString("err", ""));
            }
        } catch (Throwable ignored) {
        }
        return o;
    }

    /* ======================= 错误 → 人话 ======================= */

    private static String humanFor(int code, String text) {
        String why;
        if (code == 401) why = "密钥无效或被拒（401）：检查 API Key，改完要保存";
        else if (code == 403) why = "没有权限（403）：这把密钥被限制，或这个接口按地区/IP 拦了";
        else if (code == 404) why = "接口地址或模型名不对（404）：检查 Base URL 结尾和模型名拼写";
        else if (code == 429) why = "额度用完或被限流（429）：充值，或过一会儿再试";
        else if (code == 400) why = "请求被拒（400）：多半是模型名不对，或这个接口不认这种请求格式";
        else if (code >= 500) why = "对方服务器出错（" + code + "）：不是你的配置问题，稍后再试";
        else why = "接口返回了意料之外的错误（" + code + "）";
        return why + "；返回：" + snippet(text);
    }

    private static String humanForThrowable(Throwable t) {
        String n = (t == null) ? "" : t.getClass().getSimpleName();
        String msg = (t == null) ? "" : String.valueOf(t.getMessage());
        String why;
        if (n.indexOf("UnknownHost") >= 0) why = "域名解析失败：检查手机网络，或 Base URL 的域名拼错了";
        else if (n.indexOf("SocketTimeout") >= 0) why = "超时（10 秒内没响应）：网络不通，或这个地址在国内打不开";
        else if (n.indexOf("Connect") >= 0) why = "连不上这个地址：域名/端口不对，或对方没开";
        else if (n.indexOf("SSL") >= 0 || n.indexOf("Cert") >= 0) why = "证书校验失败：地址被劫持，或对方证书过期";
        else why = "请求没发出去";
        return why + "（" + n + (msg.length() > 0 ? "：" + msg : "") + "）";
    }

    private static String snippet(String text) {
        String s = (text == null) ? "" : text.replace("\r", " ").replace("\n", " ").trim();
        if (s.length() == 0) return "（服务端没有返回内容）";
        if (s.length() > BODY_SNIPPET) s = s.substring(0, BODY_SNIPPET) + "…";
        return s;
    }

    /* ======================= 日志 ======================= */

    /** 每条一行，追加到 files/home/.qqbot/logs/provider-check.log；upload-log.sh 会带走它。 */
    private static void writeLog(File filesDir, String line) {
        try {
            File dir = new File(QqbotShell.qqbotDir(filesDir), "logs");
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            File f = new File(dir, "provider-check.log");
            rotate(f);
            FileOutputStream out = new FileOutputStream(f, true);
            try {
                out.write((line + "\n").getBytes("UTF-8"));
                out.flush();
            } finally {
                closeQuietly(out);
            }
        } catch (Throwable ignored) {
        }
    }

    private static String logLine(long runTs, Row r, JSONObject res) {
        return "[provider-check] " + now() + " run=" + runTs
                + " id=" + one(r.id) + " name=" + one(r.name) + " model=" + one(r.model)
                + " base=" + one(r.base) + " state=" + res.optString("state", "")
                + " ms=" + res.optLong("ms", 0L) + " http=" + res.optInt("http", 0)
                + " err=" + one(res.optString("err", ""));
    }

    /** 100KB 就轮转一次：保留一代历史（.1），别把用户存储写满。 */
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

    /** 日志一行一条：把换行和多余空白压掉，避免撑坏 grep。 */
    private static String one(String s) {
        String v = (s == null) ? "" : s.replace("\r", " ").replace("\n", " ").trim();
        if (v.length() > BODY_SNIPPET) v = v.substring(0, BODY_SNIPPET) + "…";
        return v;
    }

    /* ======================= 小工具 ======================= */

    private static JSONObject fail(String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("err", msg);
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
        String m = (t == null) ? "" : String.valueOf(t);
        if (m.length() > 180) m = m.substring(0, 180) + "…";
        return m;
    }
}
