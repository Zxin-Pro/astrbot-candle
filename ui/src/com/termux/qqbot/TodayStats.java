package com.termux.app;

import android.net.TrafficStats;
import android.os.Process;

import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 为什么需要它：控制台「今日概览」要显示今天在线多久、收发了多少消息、报错几次、走了多少流量，
 * 但这些数据分别藏在沙盒心跳文件、AstrBot 日志和系统的 uid 流量计数里，JS 侧一条都拿不到。
 * 逐秒轮询会烧电，所以由守护循环每 60 秒调一次 tick()：
 *   1. 在线秒数 —— 心跳文件每 3 秒被 run-all.sh 重写一次，相邻两轮的 mtime 差值就是在线时长；
 *   2. 消息/报错 —— 按「上次读到的字节偏移」增量扫描日志，不重复数、也不把历史日志算进今天；
 *   3. 流量 —— TrafficStats 的 uid 累计值取增量，计数器归零（重启）时自动重设基线。
 * 结果落 state/today.json，桥方法 todayStats() 只读内存快照，保证 <50ms 返回。
 * d8 处理匿名内部类会崩，本文件不出现匿名类。
 */
public final class TodayStats {

    private static final String NAME = "today.json";
    /** 单次最多补扫 2MB：日志暴涨时宁可下轮接着扫，也不能让一轮自检卡住几十秒。 */
    private static final long MAX_READ = 2L * 1024 * 1024;
    /** 心跳超过这么久没更新就认为沙盒没在跑，不许再把这段空档算成在线。 */
    private static final long HB_STALE = 180L;

    /** 一条「发出的消息」：AstrBot respond/stage.py:183 在 INFO 级别每条回复打一行。 */
    private static final String MARK_OUT = "Prepare to send - ";
    /** 一条「收到的消息」：AstrBot aiocqhttp_platform_adapter.py:129，只有 DEBUG 级别才有。 */
    private static final String MARK_IN = "[aiocqhttp] RawMessage";
    private static final String ERR_A = "[ERROR]";
    private static final String ERR_B = "[error]";
    private static final String ERR_C = "Traceback (most recent call last)";

    private static volatile boolean warm = false;
    private static volatile String cached = "";

    private TodayStats() {}

    /** 守护循环每轮调用一次（后台线程）。 */
    static synchronized void tick(File filesDir) {
        try {
            JSONObject o = load(filesDir);
            long now = StateIO.now();
            String day = dayString();
            if (!day.equals(o.optString("day", ""))) {
                // 跨天：计数清零，但日志偏移（offs）保留 —— 否则今天第一轮会把昨天的行再数一遍
                o.put("onlineSec", 0L);
                o.put("msgs", 0L);
                o.put("errors", 0L);
                o.put("rxBytes", 0L);
                o.put("txBytes", 0L);
                o.put("rxBase", 0L);
                o.put("txBase", 0L);
                o.put("lastM", 0L);
                o.put("day", day);
            }
            long onlineSec = countOnline(filesDir, o, now);
            long[] cnt = new long[2];
            JSONObject offs = o.optJSONObject("offs");
            if (offs == null) {
                offs = new JSONObject();
                o.put("offs", offs);
            }
            // AstrBot 的日志目录：沙盒 rootfs 里两处历史路径都试，外加原生捕获的 stdout 日志
            File home = QqbotShell.homeDir(filesDir);
            scanDir(new File(home, "ubuntu/opt/AstrBot/data/logs"), offs, cnt);
            scanDir(new File(home, "ubuntu/root/astrbot/data/logs"), offs, cnt);
            scanDir(new File(home, "ubuntu/root/astrbot/data"), offs, cnt);
            scanFile(new File(QqbotShell.qqbotDir(filesDir), "cache/logs/astrbot.log"), offs, cnt);
            long msgs = o.optLong("msgs", 0L) + cnt[0];
            long errors = o.optLong("errors", 0L) + cnt[1];
            countTraffic(o);
            o.put("onlineSec", onlineSec);
            o.put("msgs", msgs);
            o.put("errors", errors);
            o.put("day", day);
            o.put("updated", now);
            StateIO.write(StateIO.file(filesDir, NAME), o.toString());
            warm = true;
            cached = snapshot(o);
        } catch (Throwable ignored) {
        }
    }

    /** 桥方法 todayStats()：只读内存，没算过就返回 warm:false 的白卷（前端据此显示「统计中」）。 */
    static String todayStats() {
        String s = cached;
        if (warm && s.length() > 0) return s;
        return "{\"ok\":true,\"day\":\"" + dayString()
                + "\",\"onlineSec\":0,\"msgs\":0,\"errors\":0,\"rxBytes\":0,\"txBytes\":0,\"warm\":false}";
    }

    /* ---------- 在线时长 ---------- */

    private static long countOnline(File filesDir, JSONObject o, long now) throws Exception {
        long onlineSec = o.optLong("onlineSec", 0L);
        long hb = heartbeatMtime(filesDir);
        long lastM = o.optLong("lastM", 0L);
        if (hb > 0 && now - hb <= HB_STALE) {
            if (lastM > 0 && hb > lastM) {
                long d = hb - lastM;
                // 协议：只有与上次相差 ≥30 秒才累计。差值太小说明是「踢」出来的额外一轮自检，
                // 这点时间不值得单独记；超过 5 分钟的一律按 5 分钟算（设备睡眠/改时钟的跳变）。
                if (d >= 30L) {
                    if (d > 300L) d = 300L;
                    onlineSec += d;
                }
            }
            o.put("lastM", hb);
        } else {
            // 心跳停了：清零基线，等沙盒下次真正跑起来再记，避免把停机时长算成在线
            o.put("lastM", 0L);
        }
        return onlineSec;
    }

    private static long heartbeatMtime(File filesDir) {
        // 沙盒里是 /qqbot-cache/heartbeat（= $HOME/.qqbot/cache/heartbeat）；
        // 协议文档写的是 state/heartbeat，两个位置都认，取较新的那个。
        long a = new File(QqbotShell.qqbotDir(filesDir), "cache/heartbeat").lastModified();
        long b = new File(StateIO.dir(filesDir), "heartbeat").lastModified();
        long m = Math.max(a, b);
        return m <= 0 ? 0 : m / 1000L;
    }

    /* ---------- 流量 ---------- */

    private static void countTraffic(JSONObject o) throws Exception {
        long rx = -1;
        long tx = -1;
        try {
            rx = TrafficStats.getUidRxBytes(Process.myUid());
            tx = TrafficStats.getUidTxBytes(Process.myUid());
        } catch (Throwable ignored) {
        }
        if (rx > 0) {
            long base = o.optLong("rxBase", 0L);
            if (base <= 0 || rx < base) {
                o.put("rxBase", rx); // 首次统计或设备重启后计数归零：重设基线，不累加
            } else {
                o.put("rxBytes", o.optLong("rxBytes", 0L) + (rx - base));
                o.put("rxBase", rx);
            }
        }
        if (tx > 0) {
            long base = o.optLong("txBase", 0L);
            if (base <= 0 || tx < base) {
                o.put("txBase", tx);
            } else {
                o.put("txBytes", o.optLong("txBytes", 0L) + (tx - base));
                o.put("txBase", tx);
            }
        }
    }

    /* ---------- 日志增量扫描 ---------- */

    private static void scanDir(File dir, JSONObject offs, long[] cnt) {
        try {
            if (dir == null || !dir.isDirectory()) return;
            File[] ls = dir.listFiles();
            if (ls == null) return;
            int n = 0;
            for (int i = 0; i < ls.length && n < 20; i++) {
                String nm = ls[i].getName();
                if (nm == null || !nm.endsWith(".log")) continue; // 只看当轮日志，忽略 .log.1 这类历史备份
                scanFile(ls[i], offs, cnt);
                n++;
            }
        } catch (Throwable ignored) {
        }
    }

    private static void scanFile(File f, JSONObject offs, long[] cnt) {
        if (f == null || !f.isFile()) return;
        RandomAccessFile raf = null;
        try {
            String key = f.getAbsolutePath();
            long size = f.length();
            long off = offs.has(key) ? offs.getLong(key) : -1L;
            if (off < 0) {
                // 第一次见到这个文件：只记基线。这样「今日」是从功能上线那一刻开始算，
                // 不会因为翻到一份三周前的日志就把所有历史消息都算进今天。
                offs.put(key, size);
                return;
            }
            if (off > size) off = 0; // 被轮转或截断
            if (size <= off) return;
            long want = size - off;
            if (want > MAX_READ) want = MAX_READ;
            raf = new RandomAccessFile(f, "r");
            raf.seek(off);
            byte[] buf = new byte[(int) want];
            int n = raf.read(buf);
            if (n <= 0) return;
            int lastNl = -1;
            for (int i = n - 1; i >= 0; i--) {
                if (buf[i] == '\n') {
                    lastNl = i;
                    break;
                }
            }
            int upto = lastNl + 1; // 只统计完整行，半行留到下一轮
            if (upto <= 0) return;
            countLines(new String(buf, 0, upto, "UTF-8"), cnt);
            offs.put(key, off + upto);
        } catch (Throwable ignored) {
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void countLines(String text, long[] cnt) {
        int start = 0;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            if (text.charAt(i) != '\n') continue;
            if (i > start) {
                String line = text.substring(start, i);
                if (line.indexOf(MARK_OUT) >= 0 || line.indexOf(MARK_IN) >= 0) cnt[0]++;
                if (line.indexOf(ERR_A) >= 0 || line.indexOf(ERR_B) >= 0
                        || line.indexOf(ERR_C) >= 0) cnt[1]++;
            }
            start = i + 1;
        }
    }

    /* ---------- 读写与序列化 ---------- */

    private static JSONObject load(File filesDir) {
        String s = StateIO.read(StateIO.file(filesDir, NAME));
        if (s != null) {
            try {
                return new JSONObject(s);
            } catch (Throwable ignored) {
            }
        }
        return new JSONObject();
    }

    private static String snapshot(JSONObject o) {
        try {
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("day", o.optString("day", dayString()));
            out.put("onlineSec", o.optLong("onlineSec", 0L));
            out.put("msgs", o.optLong("msgs", 0L));
            out.put("errors", o.optLong("errors", 0L));
            out.put("rxBytes", o.optLong("rxBytes", 0L));
            out.put("txBytes", o.optLong("txBytes", 0L));
            out.put("warm", true);
            return out.toString();
        } catch (Throwable t) {
            return "{\"ok\":true,\"warm\":false}";
        }
    }

    private static String dayString() {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            return f.format(new Date());
        } catch (Throwable t) {
            return "1970-01-01";
        }
    }
}
