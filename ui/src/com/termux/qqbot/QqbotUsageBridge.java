package com.termux.app;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * 「模型用量」的原生侧：window.AndroidUsage（start/poll）。
 *
 * 数据从哪来（逐行核对过 AstrBot 4.28.2 源码，不是猜的）：
 *   - AstrBot 每跑完一次内部 agent 回复（成功或报错都算），就往 data_v4.db 的
 *     provider_stats 表写一行
 *     （astrbot/core/pipeline/process_stage/method/agent_sub_stages/internal.py:688，
 *     字段定义在 astrbot/core/db/po.py:48-69）。
 *   - 行里有 provider_model / token_input_other / token_input_cached / token_output /
 *     status / created_at（naive UTC 文本）、agent_type='internal'。
 *   - 库文件就在宿主侧 $HOME/ubuntu/opt/AstrBot/data/data_v4.db，也就是 AstrCfg.dbFile()；
 *     AstrCfg.java:175 早就在用 SQLiteDatabase.openDatabase(..., OPEN_READONLY) 读同一份库。
 *
 * 统计口径完全照抄 AstrBot 自己的 dashboard（astrbot/dashboard/services/stat_service.py）：
 *   - 只取 agent_type='internal'，status 不过滤 —— 面板的 where 子句只有这两个条件
 *     （stat_service.py:322-326），成功/失败是另外单独数 success 的（:377-379），
 *     所以一次报错的调用也算一次，这里跟着算。
 *   - in = token_input_other + token_input_cached，out = token_output（:359-363、:388）。
 *   - created_at 是 naive UTC，先补 UTC 再转本地时区再按本地日分桶
 *     （stat_service.py:298-302、:357-358）—— 直接按 UTC 分日会让「今天」整体错 8 小时。
 *   - cost：AstrBot 全仓没有任何价格字段，桥不猜价 —— 固定 -1；1.3.4-beta.10 起界面让用户
 *     自己填「输入 / 缓存命中 / 输出」三个单价，用下面的 cached / inOther 现场算粗估花费。
 *   - 唯一的差异：面板的「今日模型」按 token 总量排序（stat_service.py:419-426），
 *     契约 3.1 要求按调用次数降序，这里按契约走（token 量作为第二名并列时的次序）。
 *
 * 为什么不在 Termux 侧跑 python 脚本（契约最初的设想）：
 *   - Termux 前缀里本来就没有 python3 —— 工程自己的注释写着这件事
 *     （build/opt/qqbot/common.sh:236、delbot.sh:17「Termux 侧根本没有 python3」）。
 *     python 只装在 proot 里的 Ubuntu，从桥线程进去得整个沙盒在跑、还得 proot login。
 *   - 同一张表用 SQLiteDatabase 直读是毫秒级、只读、不联网、不依赖沙盒在跑。
 *     工程里已有先例：QqbotModelBridge.java:26 就明确放弃了「走沙盒脚本」那条路。
 *   - build/opt/qqbot/usage.py 保留一份等价的 Python 实现，给沙盒内排障/手动核对用，
 *     桥不依赖它。
 *
 * 缓存：成功产出的 JSON 缓存 60 秒；过期后 poll() 仍旧先回旧数据、同时在后台重算
 * （与 QqbotDiagBridge.diskTop 同一思路：宁可给一分钟前的数字，也不要让卡片空一下）。
 * 读库一律在后台线程（poll 未完成回空串），库只以 OPEN_READONLY 打开，不写、不建 WAL。
 *
 * 全部方法都吞异常、返回 JSON；数据缺失一律降级成 ok:false + 中文原因，绝不抛到 JS。
 * d8 处理匿名内部类会崩：本文件没有匿名内部类、没有 lambda（UsageTask 是静态嵌套类）。
 */
public class QqbotUsageBridge {

    /** 缓存有效期（毫秒）。60 秒内的 poll 不再重算。 */
    private static final long TTL_MS = 60000L;

    /** 柱状图天数：契约要求固定 7 条（今天往前 6 天，共 7 天）。 */
    private static final int DAYS = 7;

    /** Top 模型最多 6 条（契约 3.1），只统计「今天」。 */
    private static final int MAX_MODELS = 6;

    /** 失败时给界面的那句话：契约 3.1 指定用这句，细节放 note 里。 */
    private static final String ERR_NO_DATA = "没找到 AstrBot 的用量数据";

    /** 正在后台读库。true 且没有缓存时，poll() 回空串。 */
    private static volatile boolean running = false;

    /** 最近一次 start() 的时刻，用来判断缓存是否过期。 */
    private static volatile long lastTryAt = 0L;

    /** 上一次成功产出的 JSON（空串 = 还没有过成功结果）。 */
    private static volatile String cache = "";

    /** 上一次失败时给界面的 JSON（含 note），没失败过就是空串。 */
    private static volatile String lastFail = "";

    private final File filesDir;

    /** 页面里的桥用这个（注册时传 Activity）。 */
    public QqbotUsageBridge(QqbotActivity act) {
        this.filesDir = (act == null) ? null : act.filesDirPub();
    }

    /** 只有 filesDir 的场景（自检 / 守护服务）。 */
    public QqbotUsageBridge(File filesDir) {
        this.filesDir = filesDir;
    }

    /* ------------------------------ 桥入口 ------------------------------ */

    /**
     * 开始一次采集，立刻返回空串（读库在后台线程，桥线程绝不能等）。
     * 已经在跑就忽略这一次请求，避免重复读库。
     */
    @JavascriptInterface
    public String start() {
        try {
            if (running) return "";
            lastTryAt = System.currentTimeMillis();
            running = true;
            Thread th = new Thread(new UsageTask(filesDir), "qqbot-usage");
            th.setDaemon(true);
            th.start();
        } catch (Throwable t) {
            running = false;
            lastFail = failJsonWithNote(ERR_NO_DATA, "起线程失败：" + t);
        }
        return "";
    }

    /**
     * 取结果：有缓存就直接给（顺手在后台刷新），在跑且没缓存给空串，
     * 一次都没成功过就给 {"ok":false,"err":"没找到 AstrBot 的用量数据","note":"..."}。
     * 毫秒级返回，不碰磁盘。
     */
    @JavascriptInterface
    public String poll() {
        try {
            long now = System.currentTimeMillis();
            String c = cache;
            if (c.length() > 0) {
                // 缓存过期：边回旧数据边重算。卡片上「空一下」比「一分钟前的数字」糟糕得多
                if (!running && (now - lastTryAt) > TTL_MS) start();
                return c;
            }
            if (running) return "";
            /* 一次成功都没有时：从来没被 start 过（界面可能只在 onResume 里取数），就自己开一次；
               上次是失败/空结果且已过 TTL 的，也顺手再试 —— AstrBot 可能刚启动起来，
               卡片不能一直卡在「没找到数据」上等用户杀进程重开。 */
            if (lastTryAt == 0L) {
                start();
                return "";
            }
            if ((now - lastTryAt) > TTL_MS) start();
            if (lastFail.length() > 0) return lastFail;
            return "";
        } catch (Throwable t) {
            return QqbotBridge.failJson("读取模型用量失败：" + t);
        }
    }

    /* ------------------------------ 后台任务 ------------------------------ */

    /** 读一次库、算一次结果。只有一个实例会跑（start() 用 running 互斥）。 */
    private static final class UsageTask implements Runnable {

        private final File filesDir;

        UsageTask(File filesDir) {
            this.filesDir = filesDir;
        }

        public void run() {
            String json = "";
            String fail = "";
            try {
                File db = usageDb(filesDir);
                if (db == null || !db.isFile()) {
                    fail = failJsonWithNote(ERR_NO_DATA,
                            "还没生成 " + relDb(filesDir) + "，沙盒可能没部署完或者没启动过；"
                            + astrVer(filesDir));
                } else {
                    SQLiteDatabase d = null;
                    try {
                        d = SQLiteDatabase.openDatabase(db.getAbsolutePath(), null,
                                SQLiteDatabase.OPEN_READONLY);
                        json = buildJson(d);
                    } catch (Throwable t) {
                        fail = dbFailJson(filesDir, t);
                    } finally {
                        try { if (d != null) d.close(); } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable t) {
                fail = failJsonWithNote(ERR_NO_DATA, "读库过程出错：" + t);
            }
            /* 先发布结果再落 running：反过来的话，中间那一瞬 poll() 会看到
               「没在跑、也没缓存」，把上一轮的失败原因当成这一轮的结果回给界面。 */
            if (json.length() > 0) {
                cache = json;
                lastFail = "";
            } else {
                lastFail = (fail.length() > 0) ? fail : failJsonWithNote(ERR_NO_DATA, relDb(filesDir));
            }
            running = false;
        }
    }

    /* ------------------------------ 聚合 ------------------------------ */

    /**
     * 查 provider_stats 并拼成契约 3.1 的 JSON。
     *
     * 只查最近 7 天：SQL 里先用一个「本地 7 天前 = 某个 UTC 时刻」的文本下限缩小范围，
     * 精确分桶在 Java 里按本地时区分（库里的 created_at 是 naive UTC，直接拿 SQLite 的
     * date() 分桶会把晚上 8 点之后的调用算到第二天）。
     */
    private static String buildJson(SQLiteDatabase db) throws Exception {
        Calendar now = Calendar.getInstance();
        Calendar todayStart = (Calendar) now.clone();
        todayStart.set(Calendar.HOUR_OF_DAY, 0);
        todayStart.set(Calendar.MINUTE, 0);
        todayStart.set(Calendar.SECOND, 0);
        todayStart.set(Calendar.MILLISECOND, 0);
        Calendar weekStart = (Calendar) todayStart.clone();
        weekStart.add(Calendar.DAY_OF_YEAR, -(DAYS - 1));

        SimpleDateFormat localDay = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        SimpleDateFormat utcStamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        utcStamp.setTimeZone(TimeZone.getTimeZone("UTC"));

        String bound = utcStamp.format(weekStart.getTime());

        /* 键是本地日期 yyyy-MM-dd，值是 {calls, in, out, cached}。 */
        Map<String, long[]> byDay = new HashMap<String, long[]>();
        /* 今日 Top 模型：键是模型名，值同上 {calls, in, out}。 */
        Map<String, long[]> byModel = new HashMap<String, long[]>();

        String todayKey = localDay.format(todayStart.getTime());
        Cursor cur = null;
        try {
            /* 条件与 dashboard 一致：只有 agent_type 与时间下限，status 不过滤
               （stat_service.py:322-326）。 */
            cur = db.rawQuery(
                    "SELECT created_at, provider_model, token_input_other,"
                    + " token_input_cached, token_output FROM provider_stats"
                    + " WHERE agent_type = 'internal' AND created_at >= ?",
                    new String[] { bound });
            while (cur.moveToNext()) {
                long ms = parseUtc(cur.getString(0));
                if (ms < 0L) continue;
                long cached = cur.getLong(3);
                long in = cur.getLong(2) + cached;
                long out = cur.getLong(4);
                String key = localDay.format(new Date(ms));
                long[] b = byDay.get(key);
                if (b == null) {
                    b = new long[] { 0L, 0L, 0L, 0L };
                    byDay.put(key, b);
                }
                b[0] = b[0] + 1L;
                b[1] = b[1] + in;
                b[2] = b[2] + out;
                b[3] = b[3] + cached;
                if (key.equals(todayKey)) {
                    String model = cur.getString(1);
                    if (model == null || model.trim().length() == 0) model = "未知模型";
                    long[] m = byModel.get(model);
                    if (m == null) {
                        m = new long[] { 0L, 0L, 0L };
                        byModel.put(model, m);
                    }
                    m[0] = m[0] + 1L;
                    m[1] = m[1] + in;
                    m[2] = m[2] + out;
                }
            }
        } finally {
            try { if (cur != null) cur.close(); } catch (Throwable ignored) {}
        }

        long totalCalls = 0L;
        JSONArray days = new JSONArray();
        JSONObject lastDay = null;
        Calendar walk = (Calendar) weekStart.clone();
        for (int i = 0; i < DAYS; i++) {
            String k = localDay.format(walk.getTime());
            long[] b = byDay.get(k);
            if (b == null) b = new long[] { 0L, 0L, 0L, 0L };
            JSONObject d = new JSONObject();
            d.put("d", k);
            d.put("calls", b[0]);
            d.put("in", b[1]);
            d.put("out", b[2]);
            d.put("cached", b[3]);
            days.put(d);
            lastDay = d;
            totalCalls = totalCalls + b[0];
            walk.add(Calendar.DAY_OF_YEAR, 1);
        }

        JSONObject jo = new JSONObject();
        jo.put("ok", true);
        jo.put("src", "db");
        jo.put("note", totalCalls > 0L ? "数据来自 AstrBot 本地库" : "AstrBot 本地库还没有调用记录");
        /* today 与 days 的最后一条必须是同一份数据：直接取 days 末条，不再另算一遍，
           免得两套算法在跨零点/夏令时上给出不一样的数。 */
        JSONObject today = new JSONObject();
        today.put("calls", lastDay.optLong("calls", 0L));
        today.put("in", lastDay.optLong("in", 0L));
        today.put("out", lastDay.optLong("out", 0L));
        /* 1.3.4-beta.10：把「缓存命中」单独拆出来，界面才能算命中率、也才能区分
           输入价与缓存命中价；in 仍是 other + cached，老界面只读 in/cost 不受影响。 */
        long todayCached = lastDay.optLong("cached", 0L);
        today.put("cached", todayCached);
        today.put("inOther", Math.max(0L, lastDay.optLong("in", 0L) - todayCached));
        today.put("cost", -1);
        jo.put("today", today);
        jo.put("days", days);

        JSONArray models = new JSONArray();
        List<String> names = new ArrayList<String>(byModel.keySet());
        /* 选择排序：模型通常只有几条，而且这样不必引入 Comparator 匿名类（d8 对它不友好）。 */
        for (int i = 0; i < names.size(); i++) {
            int best = i;
            for (int j = i + 1; j < names.size(); j++) {
                if (better(byModel, names.get(j), names.get(best))) best = j;
            }
            if (best != i) {
                String tmp = names.get(i);
                names.set(i, names.get(best));
                names.set(best, tmp);
            }
        }
        int n = (names.size() > MAX_MODELS) ? MAX_MODELS : names.size();
        for (int i = 0; i < n; i++) {
            String name = names.get(i);
            long[] m = byModel.get(name);
            JSONObject mo = new JSONObject();
            mo.put("m", name);
            mo.put("calls", m[0]);
            mo.put("in", m[1]);
            mo.put("out", m[2]);
            models.put(mo);
        }
        jo.put("models", models);
        return jo.toString();
    }

    /** a 该排在 b 前面吗：先比 token 量（与 AstrBot 面板一致），再比调用次数，最后按名字定序。 */
    private static boolean better(Map<String, long[]> m, String a, String b) {
        long[] x = m.get(a);
        long[] y = m.get(b);
        long xs = x[1] + x[2];
        long ys = y[1] + y[2];
        if (xs != ys) return xs > ys;
        if (x[0] != y[0]) return x[0] > y[0];
        return a.compareTo(b) < 0;
    }

    /* ------------------------------ 小工具 ------------------------------ */

    /**
     * 库文件：正常就是 AstrCfg.dbFile()；老安装（root/astrbot 那套目录）也认。
     * 都不在时回标准路径，让报错里能看到「它本该在哪」。
     */
    static File usageDb(File filesDir) {
        if (filesDir == null) return null;
        File main = AstrCfg.dbFile(filesDir);
        if (main.isFile()) return main;
        File alt = new File(QqbotShell.homeDir(filesDir), "ubuntu/root/astrbot/data/data_v4.db");
        if (alt.isFile()) return alt;
        return main;
    }

    /** 报错里用的相对路径（能少暴露一长串 /data/data/<pkg>/files）。 */
    private static String relDb(File filesDir) {
        if (filesDir == null) return "data_v4.db";
        return "home/ubuntu/opt/AstrBot/data/data_v4.db";
    }

    /**
     * 库打不开 / 表不存在这类失败：err 用契约那句，note 带上库路径 + AstrBot 版本 + 真实异常，
     * 用户截图给开发者时能一眼看出是哪一种。
     */
    private static String dbFailJson(File filesDir, Throwable t) {
        String raw = String.valueOf(t);
        String low = raw.toLowerCase(Locale.US);
        String why;
        if (low.indexOf("no such table") >= 0) {
            why = "库在，但没有 provider_stats 表（AstrBot 版本偏老，或者还没跑过一轮对话）";
        } else if (low.indexOf("locked") >= 0 || low.indexOf("busy") >= 0) {
            why = "AstrBot 正在写这个库，过一会儿再看";
        } else if (low.indexOf("unable to open") >= 0 || low.indexOf("cannot open") >= 0) {
            why = "库文件打不开（沙盒可能停在写库的中途，或者文件权限不对）";
        } else {
            why = "读库失败：" + raw;
        }
        return failJsonWithNote(ERR_NO_DATA, relDb(filesDir) + "；" + astrVer(filesDir) + "；" + why);
    }

    /** 自己拼失败 JSON：failJson 只有 ok/err，这里要多带一个 note 方便排障。 */
    private static String failJsonWithNote(String err, String note) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("err", err == null ? ERR_NO_DATA : err);
            o.put("note", note == null ? "" : note);
            return o.toString();
        } catch (Throwable t) {
            return QqbotBridge.failJson(err);
        }
    }

    /**
     * 顺手读一下 AstrBot 自己的版本号（astrbot/__init__.py 里的 __version__），
     * 只读一个几 KB 的文件，出错就回「版本未知」，绝不影响主流程。
     */
    static String astrVer(File filesDir) {
        if (filesDir == null) return "AstrBot 版本未知";
        try {
            File f = new File(QqbotShell.homeDir(filesDir), "ubuntu/opt/AstrBot/astrbot/__init__.py");
            if (!f.isFile()) return "AstrBot 版本未知";
            if (f.length() > 262144L) return "AstrBot 版本未知";
            String s = StateIO.read(f);
            if (s == null) return "AstrBot 版本未知";
            int i = s.indexOf("__version__");
            if (i < 0) return "AstrBot 版本未知";
            int q1 = s.indexOf('"', i);
            int q2 = (q1 < 0) ? -1 : s.indexOf('"', q1 + 1);
            if (q1 < 0 || q2 <= q1) return "AstrBot 版本未知";
            String v = s.substring(q1 + 1, q2).trim();
            if (v.length() == 0) return "AstrBot 版本未知";
            return "AstrBot " + v;
        } catch (Throwable t) {
            return "AstrBot 版本未知";
        }
    }

    /** created_at 是 SQLAlchemy 写进去的 naive UTC 文本（YYYY-MM-DD HH:MM:SS[.ffffff]），转毫秒。 */
    static long parseUtc(String s) {
        if (s == null) return -1L;
        String t = s.trim();
        if (t.length() == 0) return -1L;
        t = t.replace('T', ' ');
        if (t.endsWith("Z")) t = t.substring(0, t.length() - 1);
        int dot = t.indexOf('.');
        if (dot > 0) {
            /* 库里有微秒（.123456），而 SimpleDateFormat 的 S 是毫秒 —— 截到 3 位再解析，
               否则 ".123456" 会被当成 123456 毫秒，日期直接跑偏十几分钟。 */
            int end = dot + 4;
            if (end > t.length()) end = t.length();
            t = t.substring(0, end);
        }
        String pat = (t.indexOf('.') > 0) ? "yyyy-MM-dd HH:mm:ss.SSS" : "yyyy-MM-dd HH:mm:ss";
        try {
            SimpleDateFormat f = new SimpleDateFormat(pat, Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            f.setLenient(false);
            Date d = f.parse(t);
            if (d != null) return d.getTime();
        } catch (Throwable ignored) {
        }
        return -1L;
    }
}
