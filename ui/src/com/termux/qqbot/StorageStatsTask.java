package com.termux.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/**
 * 功能 54「存储占用」的原生侧：跑一次 shell、把结果整理成前端直接能画的样子。
 *
 * 分工：真正的 du 在沙盒脚本里（build/opt/qqbot/storage.sh stats），它写
 * $QQBOT_HOME/cache/storage.json（键 key/name/path/bytes + top 十条）。
 * 本类只做三件事：
 *   1. 在后台线程同步跑脚本（QqbotShell.run 是阻塞的，绝不能上桥线程）；
 *   2. 把脚本那份 JSON 归一化成原生自己的形状，落 state/storage.json；
 *   3. 提供 stats() / refreshStart() / clean() 三个桥入口的静态实现。
 *
 * 缓存策略：state/storage.json 的有效期 60 秒（前端也是 60 秒一刷）。
 *   - 没缓存 → 立刻回 {"ok":true,"warm":false,…} 并起线程；
 *   - 有缓存但超时 → 立刻回旧数据（stale:true）并起线程重算；
 *   - 15 秒内刚试过（失败也会记 tryAt）就不再起线程，避免脚本坏掉时每点一次开一个。
 *
 * clean 是唯一同步阻塞的入口：要拿 freed 才能回给界面，交给脚本自己的白名单清，
 * 登录数据 / 配置 / 插件 / 数据库一律不碰。
 *
 * d8 处理匿名内部类会崩：本文件无内部类、无 lambda。
 */
public class StorageStatsTask implements Runnable {

    /** 原生自己的缓存（脚本那份在 cache/storage.json，别混）。 */
    static final String FILE = "storage.json";

    /** 缓存有效期（秒）。 */
    static final long TTL = 60L;

    /** 两次「起线程」之间的最小间隔（秒）：脚本坏了也别无限重试。 */
    static final long RETRY_GAP = 15L;

    /** 脚本要 du 五个目录，慢的时候十几秒；给足 120 秒，别在半路把它砍了。 */
    static final int STATS_TIMEOUT_SEC = 120;

    /** 清理要 du 前后两次 + 删文件，比统计更慢一点。 */
    static final int CLEAN_TIMEOUT_SEC = 150;

    /** 脚本承诺不动的东西（原样回给界面，只用于提示）。 */
    private static final String[] KEPT = {"登录数据", "配置", "插件"};

    private static volatile boolean busy;

    private final File filesDir;

    public StorageStatsTask(File filesDir) {
        this.filesDir = filesDir;
    }

    /* ------------------------------ 桥入口 ------------------------------ */

    /**
     * 存储占用。有缓存就给缓存（warm=true）；没有就回空壳并后台开跑。
     * stale=true 表示这份数据超过 60 秒（界面据此提示「点重新统计」）。
     */
    static String stats(File filesDir) {
        try {
            JSONObject st = load(filesDir);
            long now = StateIO.now();
            long ts = (st == null) ? 0L : st.optLong("ts", 0L);
            boolean warm = (ts > 0L);
            boolean stale = !warm || (now - ts) >= TTL;
            if (stale) {
                long tryAt = (st == null) ? 0L : st.optLong("tryAt", 0L);
                if (now - tryAt >= RETRY_GAP) spawn(filesDir);
            }
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("warm", warm);
            o.put("stale", stale);
            o.put("ts", ts);
            o.put("src", warm ? "shell" : "");
            o.put("total", warm ? st.optLong("total", 0L) : 0L);
            o.put("dirs", (st == null) ? new JSONArray() : arr(st, "dirs"));
            o.put("top", (st == null) ? new JSONArray() : arr(st, "top"));
            o.put("lastErr", (st == null) ? "" : st.optString("lastErr", ""));
            return o.toString();
        } catch (Throwable t) {
            return QqbotBridge.failJson("读取存储统计失败：" + t);
        }
    }

    /** 强制重算：只负责起线程，立刻返回。 */
    static String refreshStart(File filesDir) {
        try {
            spawn(filesDir);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("started", true);
            return o.toString();
        } catch (Throwable t) {
            return QqbotBridge.failJson("启动存储统计失败：" + t);
        }
    }

    /**
     * 清理缓存：同步等脚本跑完（要拿 freed）。只走脚本自己的白名单。
     * 返回 {"ok":true,"freed":N,"kept":[…] ,"log":"<脚本最后一行>"}；失败 {"ok":false,"err":…}。
     */
    static String clean(File filesDir) {
        try {
            File script = script(filesDir);
            if (!script.isFile()) {
                return failClean("清理脚本还没就位（" + script.getAbsolutePath() + "），请先完成部署");
            }
            String out = QqbotShell.run(filesDir, "bash " + script.getAbsolutePath() + " clean", CLEAN_TIMEOUT_SEC);
            String s = (out == null) ? "" : out.trim();
            long freed = -1L;
            String err = "";
            String[] lines = s.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String ln = lines[i].trim();
                if (ln.startsWith("FREED=")) {
                    try {
                        freed = Long.parseLong(ln.substring(6).trim());
                    } catch (Throwable ignored) {
                    }
                } else if (ln.startsWith("ERR=")) {
                    err = ln.substring(4).trim();
                }
            }
            if (freed < 0L) {
                String why = (err.length() > 0) ? err : lastLine(s);
                if (why.length() == 0) why = "清理脚本没有返回结果";
                return failClean(why);
            }
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("freed", freed);
            JSONArray kept = new JSONArray();
            for (int i = 0; i < KEPT.length; i++) kept.put(KEPT[i]);
            o.put("kept", kept);
            o.put("log", lastLine(s));
            o.put("ts", StateIO.now());
            return o.toString();
        } catch (Throwable t) {
            return QqbotBridge.failJson("清理失败：" + t);
        }
    }

    /** 起一个后台线程重算（busy 由任务自己抢，重复调用直接退）。 */
    static void spawn(File filesDir) {
        try {
            Thread th = new Thread(new StorageStatsTask(filesDir), "qqbot-storage");
            th.setDaemon(true);
            th.start();
        } catch (Throwable ignored) {
        }
    }

    /* ------------------------------ 后台任务 ------------------------------ */

    @Override
    public void run() {
        if (filesDir == null) return;
        synchronized (StorageStatsTask.class) {
            if (busy) return;
            busy = true;
        }
        try {
            collect();
        } catch (Throwable ignored) {
        } finally {
            synchronized (StorageStatsTask.class) {
                busy = false;
            }
        }
    }

    /** 跑脚本 → 读脚本写的那份 JSON → 归一化 → 落 state/storage.json。失败只记 lastErr。 */
    private void collect() throws Exception {
        File script = script(filesDir);
        if (!script.isFile()) {
            fail("统计脚本还没就位（" + script.getAbsolutePath() + "）");
            return;
        }
        String out = QqbotShell.run(filesDir, "bash " + script.getAbsolutePath() + " stats", STATS_TIMEOUT_SEC);
        String raw = StateIO.read(scriptOut(filesDir));
        if (raw == null || raw.trim().length() == 0) {
            String hint = lastLine((out == null) ? "" : out.trim());
            fail((hint.length() > 0) ? hint : "统计没有产出结果");
            return;
        }
        JSONObject src;
        try {
            src = new JSONObject(raw);
        } catch (Throwable t) {
            fail("统计结果解析失败：" + t);
            return;
        }
        JSONObject o = normalize(src);
        o.put("tryAt", StateIO.now());
        o.put("lastErr", "");
        StateIO.write(file(filesDir), o.toString());
    }

    /**
     * 把脚本的 {"ts","total","dirs":[{key,name,path,bytes}],"top":[{path,bytes}]}
     * 补两个前端要的东西：top 每条加一个相对路径 name；dirs 原样保留（key 是前端匹配用的）。
     */
    private static JSONObject normalize(JSONObject src) throws Exception {
        JSONObject o = new JSONObject();
        o.put("ts", src.optLong("ts", StateIO.now()));
        o.put("total", src.optLong("total", 0L));
        JSONArray dirs = new JSONArray();
        JSONArray sd = src.optJSONArray("dirs");
        if (sd != null) {
            for (int i = 0; i < sd.length(); i++) {
                JSONObject d = sd.optJSONObject(i);
                if (d == null) continue;
                JSONObject e = new JSONObject();
                e.put("key", d.optString("key", ""));
                e.put("name", d.optString("name", ""));
                e.put("path", d.optString("path", ""));
                e.put("bytes", d.optLong("bytes", 0L));
                dirs.put(e);
            }
        }
        o.put("dirs", dirs);
        JSONArray top = new JSONArray();
        JSONArray st = src.optJSONArray("top");
        if (st != null) {
            for (int i = 0; i < st.length(); i++) {
                JSONObject t = st.optJSONObject(i);
                if (t == null) continue;
                JSONObject e = new JSONObject();
                e.put("path", t.optString("path", ""));
                e.put("name", t.optString("name", t.optString("path", "")));
                e.put("bytes", t.optLong("bytes", 0L));
                top.put(e);
            }
        }
        o.put("top", top);
        o.put("src", "shell");
        return o;
    }

    /** 失败也留痕：保留上一次的好数据，只把 lastErr / tryAt 写进去（界面不弹窗）。 */
    private void fail(String why) {
        try {
            JSONObject st = load(filesDir);
            JSONObject o = (st == null) ? new JSONObject() : st;
            o.put("ts", o.optLong("ts", 0L));
            o.put("total", o.optLong("total", 0L));
            if (!o.has("dirs")) o.put("dirs", new JSONArray());
            if (!o.has("top")) o.put("top", new JSONArray());
            o.put("src", "shell");
            o.put("tryAt", StateIO.now());
            o.put("lastErr", why == null ? "" : why);
            StateIO.write(file(filesDir), o.toString());
        } catch (Throwable ignored) {
        }
    }

    /* ------------------------------ 小工具 ------------------------------ */

    static File file(File filesDir) {
        return StateIO.file(filesDir, FILE);
    }

    /** 脚本本体：$PREFIX/opt/qqbot/storage.sh。 */
    static File script(File filesDir) {
        return new File(QqbotShell.usrDir(filesDir), "opt/qqbot/storage.sh");
    }

    /** 脚本自己写的统计结果：$QQBOT_HOME/cache/storage.json。 */
    static File scriptOut(File filesDir) {
        return new File(new File(QqbotShell.qqbotDir(filesDir), "cache"), "storage.json");
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

    private static JSONArray arr(JSONObject o, String key) {
        JSONArray a = o.optJSONArray(key);
        return (a == null) ? new JSONArray() : a;
    }

    private static String lastLine(String s) {
        if (s == null) return "";
        String[] lines = s.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String ln = lines[i].trim();
            if (ln.length() > 0) return ln;
        }
        return "";
    }

    private static String failClean(String why) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("freed", 0L);
            o.put("err", why);
            return o.toString();
        } catch (Throwable t) {
            return QqbotBridge.failJson("清理失败：" + t);
        }
    }
}
