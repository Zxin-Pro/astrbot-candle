package com.termux.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/**
 * 功能 56「应用内公告」的原生侧。
 *
 * 一次 GET：<已停用的自建站点>/api/announce?ver=<versionName>（2026-10 起不再发，见 ENABLED）
 *   - 带 X-Qqbot-Atom: 1（服务端缺这个头 403）与 If-None-Match: <上次的 ETag>；
 *   - 5 秒超时（AtomNet.READ_TIMEOUT_GET_MS），失败静默；
 *   - 304 → 内容没变，只把 ts 更新成现在，items 原样保留；
 *   - 200 → 覆盖 items 与 etag；
 *   - 其它 → 保留旧缓存，只记 lastErr / lastAt。
 *
 * 隐私：这是全应用唯一「只读」的出口，URL 里只有版本号，不带任何设备标识，
 * 也不读 StatsId / atom-id。grep 一次就够审计了。
 *
 * 节流：距上次成功刷新不到 5 分钟不发请求（前端也是 5 分钟问一次）；
 * 手动点「刷新」走 refreshStart()，不受节流限制。
 *
 * d8 处理匿名内部类会崩：本文件无内部类、无 lambda。
 */
public class AnnounceTask implements Runnable {

    /**
     * 总开关（2026-10 写死 false）。
     *
     * 自建站点服务端随 VPS 一起停了，现在没有免密钥能收/发公告的地方；GitHub 那边只有
     * 私有仓库 Zxin-Pro/astrbot-errlogs，进去要 token，不能塞进 APK。公告界面本身
     * 已经不调用了，留着这个类只为将来有服务端时改回 true 就能复活：
     * 下面的抓取/缓存/ETag 逻辑一行没删。
     *
     * 关着的时候 ann() / refreshStart() / run() 都在入口返回「空公告」，不建线程、
     * 不解析 DNS、不碰状态文件。
     */
    private static final boolean ENABLED = false;

    /** 缓存：$QQBOT_HOME/state/ann.json（etag / items / ts / lastErr）。 */
    static final String FILE = "ann.json";

    /** 距上次成功刷新不足 5 分钟就不再发请求。 */
    static final long THROTTLE = 5L * 60L;

    /** 失败后最少隔 60 秒才允许再起一次线程（失败不重试，但别把桥线程当解压玩具）。 */
    static final long RETRY_GAP = 60L;

    private static volatile boolean busy;

    private final File filesDir;
    private final String ver;

    public AnnounceTask(File filesDir, String ver) {
        this.filesDir = filesDir;
        this.ver = (ver == null) ? "" : ver;
    }

    /* ------------------------------ 桥入口 ------------------------------ */

    /**
     * 公告列表。有缓存先给缓存；没有就回 {"ok":true,"items":[],"warm":false} 并后台开抓。
     * 字段对齐前端：items[].{id,title,body,level,minVer,until}；until 是 epoch 秒或 0。
     */
    static String ann(File filesDir, String ver) {
        if (!ENABLED) return emptyAnn();
        try {
            JSONObject st = load(filesDir);
            long now = StateIO.now();
            long ts = (st == null) ? 0L : st.optLong("ts", 0L);
            boolean warm = (st != null && st.has("items"));
            boolean fresh = (ts > 0L) && ((now - ts) < THROTTLE);
            if (!fresh) {
                long tryAt = (st == null) ? 0L : st.optLong("tryAt", 0L);
                if ((now - tryAt) >= RETRY_GAP) spawn(filesDir, ver);
            }
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("warm", warm);
            o.put("cached", warm);
            o.put("stale", !fresh);
            o.put("ts", ts);
            o.put("items", (st == null) ? new JSONArray() : arr(st, "items"));
            o.put("ver", (st == null) ? 0 : st.optInt("ver", 0));
            o.put("minVer", (st == null) ? "" : st.optString("minVer", ""));
            o.put("lastErr", (st == null) ? "" : st.optString("lastErr", ""));
            return o.toString();
        } catch (Throwable t) {
            return QqbotBridge.failJson("读取公告失败：" + t);
        }
    }

    /**
     * 「空公告」：关掉上报后 ann() / refreshStart() 统一返回这一份。
     * 键和顺序照抄 ann() 里 st == null 那条路径，items 是空数组，前端拿到当「没公告」，
     * 不会因为缺字段而报错；stale=true 表示「没刷新过」。
     */
    static String emptyAnn() {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("warm", false);
            o.put("cached", false);
            o.put("stale", true);
            o.put("ts", 0L);
            o.put("items", new JSONArray());
            o.put("ver", 0);
            o.put("minVer", "");
            o.put("lastErr", "");
            return o.toString();
        } catch (Throwable t) {
            // JSONObject 理论上不会抛；兜底给一份字面量，形状与上面逐字节一致
            return "{\"ok\":true,\"warm\":false,\"cached\":false,\"stale\":true,"
                    + "\"ts\":0,\"items\":[],\"ver\":0,\"minVer\":\"\",\"lastErr\":\"\"}";
        }
    }

    /** 强制刷新（用户点了「重新检查」）：只起线程，立刻返回。 */
    static String refreshStart(File filesDir, String ver) {
        if (!ENABLED) return emptyAnn();
        try {
            spawn(filesDir, ver);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("started", true);
            return o.toString();
        } catch (Throwable t) {
            return QqbotBridge.failJson("刷新公告失败：" + t);
        }
    }

    static void spawn(File filesDir, String ver) {
        try {
            Thread th = new Thread(new AnnounceTask(filesDir, ver), "qqbot-ann");
            th.setDaemon(true);
            th.start();
        } catch (Throwable ignored) {
        }
    }

    /* ------------------------------ 后台任务 ------------------------------ */

    @Override
    public void run() {
        if (!ENABLED) return;
        if (filesDir == null) return;
        synchronized (AnnounceTask.class) {
            if (busy) return;
            busy = true;
        }
        try {
            fetch();
        } catch (Throwable ignored) {
        } finally {
            synchronized (AnnounceTask.class) {
                busy = false;
            }
        }
    }

    private void fetch() throws Exception {
        JSONObject old = load(filesDir);
        String etag = (old == null) ? "" : old.optString("etag", "");
        long now = StateIO.now();
        JSONObject mark = (old == null) ? new JSONObject() : old;
        mark.put("tryAt", now);
        save(mark);

        AtomReply r = AtomNet.get(AtomNet.PATH_ANN + "?ver=" + urlEsc(ver), etag);

        // 服务端要是还没上线 /api/announce（老契约 docs §11 只有 /api/app-config.json），
        // 4xx/5xx 时退到老路径再试一次；两次都失败才记 lastErr。连不上（code==0）不重试，省一次 DNS。
        if (!r.ok && !r.notModified() && r.code >= 400) {
            AtomReply old2 = AtomNet.get(AtomNet.PATH_CONFIG, etag);
            if (old2.ok || old2.notModified()) r = old2;
        }

        if (r.notModified()) {
            // 304：公告没变。保留 items/etag，只把时间戳刷新一下，下次 5 分钟内不再问。
            mark.put("ts", StateIO.now());
            mark.put("lastErr", "");
            if (!mark.has("items")) mark.put("items", new JSONArray());
            save(mark);
            return;
        }
        if (!r.ok) {
            String why = (r.code == 0) ? r.err : ("HTTP " + r.code + (r.err.length() > 0 ? " " + r.err : ""));
            fail(mark, why);
            return;
        }
        JSONObject body = parse(r.body);
        if (body == null) {
            fail(mark, "公告返回不是 JSON");
            return;
        }
        JSONObject o = new JSONObject();
        o.put("etag", (r.etag.length() > 0) ? r.etag : etag);
        o.put("items", items(body));
        o.put("ts", StateIO.now());
        o.put("tryAt", now);
        o.put("lastAt", StateIO.now());
        o.put("lastErr", "");
        o.put("ver", body.optInt("ver", 0));
        o.put("minVer", body.optString("minVer", ""));
        save(o);
    }

    /**
     * 服务端两种形状都认：
     *   {"ver":1,"minVer":"1.1.0","ann":{...},"flags":{...}}   （老契约，单条）
     *   {"items":[{...},{...}]}                                 （多条的版本）
     * 归一成 items[]，前端只认这个。
     */
    private static JSONArray items(JSONObject body) throws Exception {
        JSONArray out = new JSONArray();
        String minVer = body.optString("minVer", "");
        JSONArray arr = body.optJSONArray("items");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.optJSONObject(i);
                if (it == null) continue;
                JSONObject e = one(it, minVer);
                if (e != null) out.put(e);
            }
            return out;
        }
        JSONObject ann = body.optJSONObject("ann");
        if (ann != null) {
            JSONObject e = one(ann, minVer);
            if (e != null) out.put(e);
            return out;
        }
        JSONObject e = one(body, minVer);
        if (e != null) out.put(e);
        return out;
    }

    /** 单条公告：字段缺就填空串，整条全空就当没这条（正文叫 text 或 body 都行）。 */
    private static JSONObject one(JSONObject it, String minVer) throws Exception {
        String id = it.optString("id", "");
        String title = it.optString("title", "");
        String text = it.optString("body", it.optString("text", ""));
        if (id.length() == 0 && title.length() == 0 && text.length() == 0) return null;
        JSONObject e = new JSONObject();
        e.put("id", id);
        e.put("title", title);
        e.put("body", text);
        e.put("level", "warn".equals(it.optString("level", "info")) ? "warn" : "info");
        e.put("minVer", it.optString("minVer", minVer));
        e.put("until", until(it.opt("until")));
        return e;
    }

    /**
     * 过期时间：服务端可能给 epoch 秒，也可能给 "YYYY-MM-DD"（老契约文档里是 epoch）。
     * 统一转成 epoch 秒；日期字符串按当天 23:59:59 算；都认不出给 0（= 永不过期，前端会一直显示）。
     */
    private static long until(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number) return ((Number) v).longValue();
        String s = String.valueOf(v).trim();
        if (s.length() == 0) return 0L;
        boolean digits = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                digits = false;
                break;
            }
        }
        if (digits) {
            try {
                return Long.parseLong(s);
            } catch (Throwable ignored) {
                return 0L;
            }
        }
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT);
            f.setLenient(false);
            java.util.Date d = f.parse(s);
            if (d == null) return 0L;
            return (d.getTime() / 1000L) + 86399L;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /* ------------------------------ 小工具 ------------------------------ */

    static File file(File filesDir) {
        return StateIO.file(filesDir, FILE);
    }

    private static JSONObject load(File filesDir) {
        try {
            String s = StateIO.read(file(filesDir));
            if (s == null || s.trim().length() == 0) return null;
            return new JSONObject(s);
        } catch (Throwable t) {
            return null;
        }
    }

    private void save(JSONObject o) {
        StateIO.write(file(filesDir), o.toString());
    }

    private static JSONArray arr(JSONObject o, String key) {
        JSONArray a = o.optJSONArray(key);
        return (a == null) ? new JSONArray() : a;
    }

    /** 失败只留痕：保留旧 items，界面继续显示上一次的公告，不弹任何提示。 */
    private void fail(JSONObject o, String why) {
        try {
            o.put("ts", o.optLong("ts", 0L));
            if (!o.has("items")) o.put("items", new JSONArray());
            o.put("lastAt", StateIO.now());
            o.put("lastErr", (why == null) ? "" : why);
            save(o);
        } catch (Throwable ignored) {
        }
    }

    private static JSONObject parse(String s) {
        try {
            if (s == null || s.trim().length() == 0) return null;
            return new JSONObject(s);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String urlEsc(String s) {
        try {
            return java.net.URLEncoder.encode((s == null) ? "" : s, "UTF-8");
        } catch (Throwable ignored) {
            return "";
        }
    }
}
