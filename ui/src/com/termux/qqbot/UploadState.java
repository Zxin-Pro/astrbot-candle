package com.termux.app;

import android.content.Context;
import android.content.pm.PackageInfo;

import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/**
 * 上报的账本：什么时候试过、哪天成功过、上次失败为什么——全落在 state/upload.json。
 *
 * 为什么单开一个类：崩溃上报和匿名统计的记账规则一模一样（每天最多成功一次 +
 * 距上次尝试满 6 小时才重试 + 失败只记不提示），两个 Task 各抄一份迟早漂；
 * 这里一处判定，三处触发（冷启动 / 守护 6 小时巡检 / 用户手点「立即上报」）共用。
 *
 * state/upload.json（键缺省一律当「没发生过」，不用 null）：
 *   {"crashDay":"2026-10-19",   // 崩溃上报最后一次「成功」的日期
 *    "crashTryAt":1760900000,   // 崩溃上报最后一次「尝试」的 epoch 秒
 *    "statsDay":"2026-10-19",   // 匿名统计最后一次「成功」的日期
 *    "statsTryAt":1760900000,   // 匿名统计最后一次「尝试」的 epoch 秒
 *    "lastAt":1760900000, "lastCode":200, "lastErr":"", "lastKind":"crash"}
 *
 * 另外两个小文件：
 *   state/atom-id     统计开关关着时崩溃上报用的随机 UUID（和 stats-id 完全无关）
 *   state/stats-open  冷启动累计次数，作为 events.open 发出去
 *
 * 开关语义（硬要求）：crashReport=false / stats 关着时，连 Prefs 之外的东西都不读，
 * 更不解析 DNS——所有 spawn 入口第一行都是开关判断。
 */
final class UploadState {

    private static final String FILE = "upload.json";

    /** 统计关着时崩溃上报的匿名 ID，和 state/stats-id 是两份互不相干的东西。 */
    private static final String ATOM_ID = "atom-id";

    /** 冷启动计数器。 */
    private static final String OPEN = "stats-open";

    /** 与 StatsId.KEY 同一个键（state/prefs.json 的 stats）。 */
    private static final String KEY_STATS = "stats";

    /** 重试间隔：距上次「尝试」满 6 小时才再试，避免断网时每分钟白试一次。 */
    static final long PERIOD = 6L * 3600L;

    static final String KIND_CRASH = "crash";
    static final String KIND_STATS = "stats";

    /** 读-改-写要串行：守护线程的巡检和桥线程的手动上报可能同时进来。 */
    private static final Object LOCK = new Object();

    private UploadState() {
    }

    static File file(File filesDir) {
        return StateIO.file(filesDir, FILE);
    }

    /** 本地日期 "YYYY-MM-DD"（与 StatsId.day() 同一个格式，前端/服务端口径一致）。 */
    static String day() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
    }

    /** 读整份 upload.json；没有 / 被写坏都当空对象，绝不抛。 */
    static JSONObject load(File filesDir) {
        String s = StateIO.read(file(filesDir));
        if (s != null && s.trim().length() > 0) {
            try {
                return new JSONObject(s);
            } catch (Throwable ignored) {
            }
        }
        return new JSONObject();
    }

    private static void save(File filesDir, JSONObject o) {
        try {
            StateIO.write(file(filesDir), o.toString());
        } catch (Throwable ignored) {
        }
    }

    /* ---------------- 开关 ---------------- */

    static boolean crashOn(File filesDir) {
        return Prefs.getBool(filesDir, "crashReport", false);
    }

    static boolean statsOn(File filesDir) {
        return Prefs.getBool(filesDir, KEY_STATS, false);
    }

    /* ---------------- 安装 ID ---------------- */

    /**
     * 上报用的安装 ID：统计开着就用 state/stats-id（前端看到的也是这个 ID，两边能对上）；
     * 统计关着（对外就是空串）就退回 state/atom-id 的随机 UUID——崩溃去重需要「同一台设备
     * 一个稳定 ID」，但不能借此把两个开关串起来。
     */
    static String installId(File filesDir) {
        String id = StatsId.enabled(filesDir) ? StatsId.read(filesDir) : "";
        if (id.length() == 0) id = atomId(filesDir);
        return id;
    }

    /** state/atom-id：和统计 ID 无关的匿名 UUID；没有 / 被写坏就现生成一个。 */
    static String atomId(File filesDir) {
        synchronized (LOCK) {
            String s = StateIO.read(StateIO.file(filesDir, ATOM_ID));
            if (s != null) {
                s = s.trim();
                if (s.length() >= 32 && s.length() <= 40) return s;
            }
            String id = UUID.randomUUID().toString();
            StateIO.write(StateIO.file(filesDir, ATOM_ID), id);
            return id;
        }
    }

    /* ---------------- 启动计数 ---------------- */

    /** 冷启动 +1，返回加完的值。只在统计开关开着时调用：关着的人不该被写任何计数。 */
    static int bumpOpen(File filesDir) {
        synchronized (LOCK) {
            int n = openCount(filesDir) + 1;
            StateIO.write(StateIO.file(filesDir, OPEN), String.valueOf(n));
            return n;
        }
    }

    static int openCount(File filesDir) {
        String s = StateIO.read(StateIO.file(filesDir, OPEN));
        if (s == null) return 0;
        try {
            return Math.max(0, Integer.parseInt(s.trim()));
        } catch (Throwable t) {
            return 0;
        }
    }

    /* ---------------- 记账 ---------------- */

    /**
     * 记「这次尝试开始了」：&lt;kind&gt;TryAt = now。
     * 6 小时重试的计时起点是「尝试」而不是「成功」——否则断网时会变成每分钟白试一次。
     */
    static void beginAttempt(File filesDir, String kind) {
        synchronized (LOCK) {
            JSONObject o = load(filesDir);
            try {
                o.put(kind + "TryAt", StateIO.now());
            } catch (Throwable ignored) {
            }
            save(filesDir, o);
        }
    }

    /** 记结果。成功写 &lt;kind&gt;Day=今天（当天不再发）；失败只写 lastErr/lastAt/lastCode。 */
    static void finish(File filesDir, String kind, boolean ok, int code, String err) {
        synchronized (LOCK) {
            JSONObject o = load(filesDir);
            try {
                o.put("lastAt", StateIO.now());
                o.put("lastCode", code);
                o.put("lastErr", ok ? "" : (err == null ? "" : err));
                o.put("lastKind", kind);
                if (ok) o.put(kind + "Day", day());
            } catch (Throwable ignored) {
            }
            save(filesDir, o);
        }
    }

    /** 某类上报最后一次成功的日期（"YYYY-MM-DD"；从没成功过是空串）。 */
    static String lastSentDay(File filesDir, String kind) {
        return load(filesDir).optString(kind + "Day", "");
    }

    static long tryAt(File filesDir, String kind) {
        return load(filesDir).optLong(kind + "TryAt", 0L);
    }

    /* ---------------- 闸门 ---------------- */

    /** 冷启动的闸门：开关开着 + 今天还没成功过。 */
    static boolean crashDue(File filesDir) {
        return crashOn(filesDir) && !day().equals(lastSentDay(filesDir, KIND_CRASH));
    }

    static boolean statsDue(File filesDir) {
        return statsOn(filesDir) && !day().equals(lastSentDay(filesDir, KIND_STATS));
    }

    /**
     * 6 小时巡检的闸门：在「今天还没成功过」之上，再要求距上次尝试满 6 小时。
     * 从没试过（TryAt=0）时自然算到期。
     */
    static boolean retryDue(File filesDir, String kind, boolean due) {
        if (!due) return false;
        long since = StateIO.now() - tryAt(filesDir, kind);
        // since < 0 说明系统时间被往回拨过，别把上报永久卡死
        return since >= PERIOD || since < 0;
    }

    /* ---------------- 触发 ---------------- */

    /** 起后台线程跑崩溃上报。开关关着一个线程都不起（不解析 DNS，零字节外发）。 */
    static void spawnCrash(File filesDir, String ver) {
        if (filesDir == null || !crashOn(filesDir)) return;
        try {
            Thread t = new Thread(new CrashUploadTask(filesDir, ver), "qqbot-crash-upload");
            t.setDaemon(true);
            t.start();
        } catch (Throwable ignored) {
        }
    }

    /** 起后台线程跑匿名统计上报。开关关着一个线程都不起。 */
    static void spawnStats(File filesDir, String ver) {
        if (filesDir == null || !statsOn(filesDir)) return;
        try {
            Thread t = new Thread(new StatsUploadTask(filesDir, ver), "qqbot-stats-upload");
            t.setDaemon(true);
            t.start();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 冷启动入口（QqbotActivity.onCreate 调）。
     * 主线程只读 prefs.json / upload.json 两个几百字节的小文件（毫秒级），网络全在后台线程。
     * 两个开关都关着时：只读一次 prefs 就返回——不写计数、不解析 DNS、一个字节都不发。
     */
    static void coldStart(Context c) {
        if (c == null) return;
        try {
            File filesDir = c.getFilesDir();
            boolean cr = crashOn(filesDir);
            boolean st = statsOn(filesDir);
            if (!cr && !st) return;
            // events.open = 「统计开着期间的冷启动次数」，不是安装时长
            if (st) bumpOpen(filesDir);
            String ver = null;
            if (cr && crashDue(filesDir)) {
                ver = version(c);
                spawnCrash(filesDir, ver);
            }
            if (st && statsDue(filesDir)) {
                spawnStats(filesDir, ver == null ? version(c) : ver);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 守护循环每轮收尾调（已经在守护线程里）：距上次尝试满 6 小时、开关还开着、有网，
     * 才再试一次；同样守「每天最多成功一次」。
     */
    static void periodic(Context c) {
        if (c == null) return;
        try {
            File filesDir = c.getFilesDir();
            boolean cr = retryDue(filesDir, KIND_CRASH, crashDue(filesDir));
            boolean st = retryDue(filesDir, KIND_STATS, statsDue(filesDir));
            if (!cr && !st) return;
            // 有网才试：断网时连 DNS 都不该解析（NetworkWatch 的快照，守护服务里实时更新）
            if (!NetworkWatch.isOnline()) return;
            String ver = null;
            if (cr) {
                ver = version(c);
                spawnCrash(filesDir, ver);
            }
            if (st) {
                spawnStats(filesDir, ver == null ? version(c) : ver);
            }
        } catch (Throwable ignored) {
        }
    }

    /** versionName（"1.1.30"）。读不到就空串——宁可上报里缺版本，也不要为了它把流程卡住。 */
    static String version(Context c) {
        if (c == null) return "";
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            if (pi == null || pi.versionName == null) return "";
            return pi.versionName.trim();
        } catch (Throwable t) {
            return "";
        }
    }
}
