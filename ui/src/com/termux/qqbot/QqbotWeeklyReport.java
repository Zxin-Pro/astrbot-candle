package com.termux.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 运行周报：最近 7 天的可用率 / 消息量 / 平均响应 / 掉线分钟 / 模型调用与 token。
 *
 * 为什么单独一个类而不是塞进 QqbotDailyReport：早报是「今天怎么样」，周报是「这一周怎么样」，
 * 数据来源也不同 —— 早报只读当天现成的 today.json 与 notify.json，周报要自己攒历史
 * （TodayStats 跨天会清零，AstrBot 的库只记调用不记在线时长），还要按周日 20:00 排闹钟。
 * 硬塞在一起只会让两个功能互相拖累。
 *
 * 数据从哪来：
 *   可用率 / 掉线分钟 / 消息量  <- state/week.json，由 QqbotAlertCenter 的 60 秒 tick 每分钟采样一次
 *                                  （心跳新鲜 = 在线，TodayStats 的今日消息数增量 = 当分钟消息量）。
 *                                  App 没运行的那些分钟没有样本，就按「没算到」处理，不硬凑。
 *   调用次数 / 输入输出 token   <- AstrBot 的 data_v4.db 的 provider_stats 表（只读打开）。
 *                                  口径与面板一致：in = token_input_other + token_input_cached、
 *                                  out = token_output，只算 agent_type='internal'，
 *                                  平均响应 = 只对 end_time > start_time 的记录取 (end-start)*1000 的均值。
 *   花费                        <- AstrBot 全仓没有价格字段，拿不到就给 -1（不是 0，0 会被当成免费）。
 *
 * 闹钟用 setInexactRepeating（与早报同一套写法）：周报差几分钟无所谓，非精确闹钟不需要
 * SCHEDULE_EXACT_ALARM 权限，也不会被系统按「精确闹钟滥用」限流。
 *
 * 只写 Java 7 风格的代码：不写方法引用、不写 switch 表达式、不用 var，也不写匿名内部类 ——
 * d8 老工具链处理它们会崩。
 */
public class QqbotWeeklyReport extends BroadcastReceiver {

    /** 显式 Intent 只会进本类；action 留着判一次，防止以后被别处复用。 */
    static final String ACTION = "com.termux.app.WEEKLY_REPORT";

    /** 闹钟与通知的固定 id：PendingIntent 必须一直用同一组 requestCode，否则 cancel 不掉旧的。 */
    private static final int REQ_ALARM = 4714;
    private static final int REQ_OPEN = 34;
    private static final int NOTI_ID = 42412;

    private static final String CH_ID = "qqbot_weekly";
    private static final String CH_NAME = "运行周报";

    /** 采样历史：state/week.json。只留最近 9 天，够 7 天窗口 + 一天的容错。 */
    private static final String NAME = "week.json";
    private static final int KEEP_DAYS = 9;

    private static final long DAY_MS = 86400000L;
    private static final Object LOCK = new Object();

    /** 心跳超过这么久没更新就认为「不在线」（脚本每 30-60 秒写一次，留 4 分钟余量给省电模式）。 */
    private static final long HB_STALE_MS = 240000L;

    private final Context ctx;

    /** 系统反射实例化接收器时走这个：没有 Context，只可能是 onReceive。 */
    public QqbotWeeklyReport() {
        this.ctx = null;
    }

    /** 界面注入用（与其它桥一致）。 */
    public QqbotWeeklyReport(Context ctx) {
        this.ctx = ctx;
    }

    // ------------------------------------------------------------ 闹钟

    /**
     * 重排周报闹钟：先撤旧的，再按「下一个周日 20:00」排。
     *
     * 为什么公开成 static：守护服务每次 onCreate、开机之后 AlarmManager 都是空的，
     * 这两处要能直接把自己的 Context 递进来补排，而不该 new 一个接收器对象。
     */
    public static void reschedule(Context ctx) {
        try {
            if (ctx == null) return;
            Context c = appOf(ctx);
            PendingIntent pi = pending(c);
            if (pi == null) return;
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) {
                pi.cancel();
                return;
            }
            am.cancel(pi);
            am.setInexactRepeating(AlarmManager.RTC_WAKEUP, nextTrigger(),
                    AlarmManager.INTERVAL_DAY * 7L, pi);
        } catch (Throwable ignored) {
            // 这方法会在开机、服务拉起这些「一抛就掉进程」的地方被调，吞掉比崩掉安全
        }
    }

    private static PendingIntent pending(Context c) {
        try {
            Intent i = new Intent(c, QqbotWeeklyReport.class);
            i.setAction(ACTION);
            return PendingIntent.getBroadcast(c, REQ_ALARM, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 下一个周日 20:00（毫秒）：今天就是周日但已过 20 点，顺延一周。 */
    private static long nextTrigger() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 20);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY);
        long at = cal.getTimeInMillis();
        if (at <= System.currentTimeMillis()) at += DAY_MS * 7L;
        return at;
    }

    /** 闹钟到点：发一条周报。重复闹钟自己会再排下一次，这里只补一次 reschedule 防 ROM 丢闹钟。 */
    public void onReceive(Context context, Intent intent) {
        try {
            if (context == null || intent == null) return;
            if (!ACTION.equals(intent.getAction())) return;
            Context c = appOf(context);
            weeklyPush(c);
            reschedule(c);
        } catch (Throwable ignored) {
            // 接收器抛异常会被系统记成崩溃，周报这种锦上添花的东西不值得
        }
    }

    // ------------------------------------------------------------ 采样

    /**
     * 一分钟采一次（QqbotAlertCenter 的 tick 调）：
     *   在线就 up += 间隔、掉线就 off += 间隔；消息量按 today.json 的增量累加。
     *
     * 为什么用「上次采样到现在」的间隔而不是死写 60 秒：守护线程可能因为省电被挂起几分钟，
     * 醒来后按真实间隔记才准；但间隔超过 5 分钟就按 1 分钟算 —— 手机睡了半小时，
     * 不能说这半小时全掉线，也不能虚报成全在线。
     */
    static void sample(Context c) {
        try {
            if (c == null) return;
            File fd = c.getFilesDir();
            synchronized (LOCK) {
                JSONObject root = readJson(fd, NAME);
                if (root == null) root = new JSONObject();
                JSONObject days = root.optJSONObject("days");
                if (days == null) {
                    days = new JSONObject();
                    root.put("days", days);
                }
                long now = System.currentTimeMillis();
                String key = dayKey(now);
                JSONObject d = days.optJSONObject(key);
                if (d == null) {
                    d = new JSONObject();
                    d.put("up", 0L);
                    d.put("off", 0L);
                    d.put("msgs", 0L);
                    d.put("msgLast", 0L);
                    days.put(key, d);
                }
                long prev = root.optLong("at", 0L);
                long gap = (prev > 0L) ? (now - prev) : 60000L;
                if (gap < 0L || gap > 300000L) gap = 60000L;
                boolean up = heartbeatFresh(c.getFilesDir(), now);
                if (up) d.put("up", d.optLong("up", 0L) + gap / 1000L);
                else d.put("off", d.optLong("off", 0L) + gap / 1000L);
                long cur = todayMsgs();
                long last = d.optLong("msgLast", 0L);
                if (cur >= last) {
                    d.put("msgs", d.optLong("msgs", 0L) + (cur - last));
                }
                d.put("msgLast", cur);
                root.put("at", now);
                prune(days, now);
                writeJson(fd, NAME, root);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 太老的采样留着只会让文件变胖，9 天前的删掉。 */
    private static void prune(JSONObject days, long now) {
        try {
            long cut = startOfDay(now) - (KEEP_DAYS - 1L) * DAY_MS;
            Iterator<String> it = days.keys();
            String[] keys = new String[32];
            int n = 0;
            while (it != null && it.hasNext() && n < keys.length) {
                keys[n] = it.next();
                n++;
            }
            for (int i = 0; i < n; i++) {
                String k = keys[i];
                if (k == null) continue;
                long at = parseDay(k);
                if (at > 0L && at < cut) days.remove(k);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 账号心跳：沙盒脚本每轮都会刷新 cache/heartbeat（或 state/heartbeat），两个都看，取最新的。 */
    private static boolean heartbeatFresh(File filesDir, long now) {
        try {
            File a = new File(QqbotShell.qqbotDir(filesDir), "cache/heartbeat");
            File b = StateIO.file(filesDir, "heartbeat");
            long at = 0L;
            if (a.isFile()) at = Math.max(at, a.lastModified());
            if (b.isFile()) at = Math.max(at, b.lastModified());
            return at > 0L && (now - at) <= HB_STALE_MS;
        } catch (Throwable t) {
            return false;
        }
    }

    /** TodayStats 的今日消息数；没 warm 或读不到就给 0。 */
    private static long todayMsgs() {
        try {
            JSONObject o = new JSONObject(TodayStats.todayStats());
            if (!o.optBoolean("warm", false)) return 0L;
            long v = o.optLong("msgs", 0L);
            return v < 0L ? 0L : v;
        } catch (Throwable t) {
            return 0L;
        }
    }

    // ------------------------------------------------------------ 桥 / 通知

    /**
     * 给 AndroidAlert.weekly()：最近 7 天（含今天）的汇总。
     * 任何一段数据取不到都给 0（token/调用）或按已有样本算（可用率），绝不抛异常、绝不返回 null。
     */
    static String weeklyJson(Context c) {
        JSONObject o = new JSONObject();
        try {
            if (c == null) {
                o.put("ok", false);
                o.put("err", "拿不到上下文，读不了周报");
                return o.toString();
            }
            File fd = c.getFilesDir();
            long now = System.currentTimeMillis();
            long[] starts = new long[7];
            String[] keys = new String[7];
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(startOfDay(now));
            cal.add(Calendar.DAY_OF_YEAR, -6);
            for (int i = 0; i < 7; i++) {
                starts[i] = cal.getTimeInMillis();
                keys[i] = dayKey(starts[i]);
                cal.add(Calendar.DAY_OF_YEAR, 1);
            }

            JSONObject root = readJson(fd, NAME);
            JSONObject daysObj = (root == null) ? null : root.optJSONObject("days");
            long upSec = 0L;
            long offSec = 0L;
            long msgs = 0L;
            JSONArray days = new JSONArray();
            for (int i = 0; i < 7; i++) {
                JSONObject d = (daysObj == null) ? null : daysObj.optJSONObject(keys[i]);
                long up = (d == null) ? 0L : d.optLong("up", 0L);
                long off = (d == null) ? 0L : d.optLong("off", 0L);
                long mg = (d == null) ? 0L : d.optLong("msgs", 0L);
                upSec += up;
                offSec += off;
                msgs += mg;
                JSONObject one = new JSONObject();
                one.put("d", label(keys[i]));
                one.put("msgs", mg);
                one.put("offline", off / 60L);
                days.put(one);
            }

            double uptime;
            if (upSec + offSec > 0L) {
                uptime = round1(100.0 * upSec / (double) (upSec + offSec));
            } else {
                // 一点样本都没有（刚装、或守护还没跑够一分钟）：只能按「心跳现在新不新鲜」给个 100/0
                uptime = heartbeatFresh(fd, now) ? 100.0 : 0.0;
            }

            long[] u = usage(fd, starts[0]);

            o.put("ok", true);
            o.put("from", keys[0]);
            o.put("to", keys[6]);
            o.put("uptimePct", uptime);
            o.put("msgs", msgs);
            o.put("avgMs", u[3]);
            o.put("offlineMin", offSec / 60L);
            o.put("calls", u[0]);
            o.put("in", u[1]);
            o.put("out", u[2]);
            o.put("cost", -1);
            o.put("days", days);
            return o.toString();
        } catch (Throwable t) {
            try {
                o.put("ok", false);
                o.put("err", "生成周报失败：" + t);
            } catch (Throwable ignored) {
            }
            return o.toString();
        }
    }

    /**
     * 推一条周报通知（每周日 20:00 的闹钟与界面的「立即推送」按钮共用）。
     * 返回 {"ok":true} 或 {"ok":false,"err":...}，桥直接透传给前端。
     */
    static String weeklyPush(Context c) {
        JSONObject o = new JSONObject();
        try {
            if (c == null) {
                o.put("ok", false);
                o.put("err", "拿不到上下文，发不了周报");
                return o.toString();
            }
            Context app = appOf(c);
            JSONObject w = new JSONObject(weeklyJson(app));
            if (!w.optBoolean("ok", false)) return w.toString();
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                o.put("ok", false);
                o.put("err", "系统通知服务不可用");
                return o.toString();
            }
            ensureChannel(app);
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = nm.getNotificationChannel(CH_ID);
                // 用户自己在系统设置里关了这个渠道：不发，但也不报错
                if (ch != null && ch.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                    o.put("ok", true);
                    return o.toString();
                }
            }
            nm.notify(NOTI_ID, build(app, w));
            o.put("ok", true);
            return o.toString();
        } catch (Throwable t) {
            try {
                o.put("ok", false);
                o.put("err", "推送周报失败：" + t);
            } catch (Throwable ignored) {
            }
            return o.toString();
        }
    }

    private static Notification build(Context c, JSONObject w) {
        String[] lines = new String[] {
            "可用率 " + fmt1(w.optDouble("uptimePct", 0.0)) + "%",
            "消息 " + w.optLong("msgs", 0L) + " 条 · 平均响应 " + secText(w.optLong("avgMs", 0L)),
            "掉线 " + w.optLong("offlineMin", 0L) + " 分钟",
            "模型调用 " + w.optLong("calls", 0L) + " 次 · 输入 " + w.optLong("in", 0L) + " / 输出 " + w.optLong("out", 0L) + " tokens"
        };
        Notification.Builder nb;
        if (Build.VERSION.SDK_INT >= 26) {
            nb = new Notification.Builder(c, CH_ID);
        } else {
            nb = new Notification.Builder(c);
            nb.setPriority(Notification.PRIORITY_DEFAULT);
        }
        nb.setContentTitle("QQ 机器人周报");
        nb.setContentText(lines[0] + " · " + lines[1]);
        nb.setSmallIcon(smallIcon(c));
        nb.setAutoCancel(true);
        nb.setShowWhen(true);
        nb.setWhen(System.currentTimeMillis());
        Notification.InboxStyle style = new Notification.InboxStyle();
        style.setBigContentTitle("QQ 机器人周报");
        style.setSummaryText(w.optString("from", "") + " ~ " + w.optString("to", ""));
        for (int i = 0; i < lines.length; i++) style.addLine(lines[i]);
        nb.setStyle(style);
        PendingIntent open = openIntent(c);
        if (open != null) nb.setContentIntent(open);
        return nb.build();
    }

    /** 点开落到「更多」页：告警与周报的卡片都在那一页。 */
    private static PendingIntent openIntent(Context c) {
        try {
            Intent i = new Intent(c, QqbotActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            i.putExtra("tab", "more");
            return PendingIntent.getActivity(c, REQ_OPEN, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            // 已存在就一个字都不改：用户可能在系统里调过重要性，重建会把它冲掉
            if (nm.getNotificationChannel(CH_ID) != null) return;
            NotificationChannel ch = new NotificationChannel(CH_ID, CH_NAME, NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("每周日晚上推一次：可用率、消息量、平均响应、掉线分钟、模型调用与 token");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        } catch (Throwable ignored) {
        }
    }

    private static int smallIcon(Context c) {
        try {
            int id = c.getResources().getIdentifier("ic_stat_qqbot", "drawable", c.getPackageName());
            if (id != 0) return id;
            int app = c.getApplicationInfo().icon;
            if (app != 0) return app;
        } catch (Throwable ignored) {
        }
        return android.R.drawable.stat_notify_error;
    }

    // ------------------------------------------------------------ 模型用量（只读库）

    /**
     * 读 provider_stats 拿最近 7 天的调用次数、输入/输出 token、平均响应。
     * 返回 {calls, in, out, avgMs}；库不在 / 表没建 / 读失败一律全 0 —— 周报不能因为
     * 用户还没聊过天就不发。
     *
     * 口径与面板一致（astrbot/dashboard/services/stat_service.py）：
     *   in = token_input_other + token_input_cached，out = token_output，只看 agent_type='internal'，
     *   created_at 存的是「无时区的 UTC」，所以下限用 UTC 文本比较（与 QqbotUsageBridge 同一做法）。
     */
    private static long[] usage(File filesDir, long fromMs) {
        long[] out = new long[] { 0L, 0L, 0L, 0L };
        SQLiteDatabase db = null;
        Cursor cur = null;
        try {
            File f = AstrCfg.dbFile(filesDir);
            if (f == null || !f.isFile()) return out;
            SimpleDateFormat utc = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            utc.setTimeZone(TimeZone.getTimeZone("UTC"));
            String bound = utc.format(new Date(fromMs));
            db = SQLiteDatabase.openDatabase(f.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            cur = db.rawQuery("SELECT token_input_other, token_input_cached, token_output,"
                    + " start_time, end_time FROM provider_stats"
                    + " WHERE agent_type = 'internal' AND created_at >= ?", new String[] { bound });
            long calls = 0L;
            long in = 0L;
            long to = 0L;
            long durSum = 0L;
            long durN = 0L;
            while (cur.moveToNext()) {
                calls++;
                in += cur.getLong(0) + cur.getLong(1);
                to += cur.getLong(2);
                double s = cur.getDouble(3);
                double e = cur.getDouble(4);
                if (s > 0.0 && e > s) {
                    durSum += Math.round((e - s) * 1000.0);
                    durN++;
                }
            }
            out[0] = calls;
            out[1] = in;
            out[2] = to;
            out[3] = (durN > 0L) ? (durSum / durN) : 0L;
            return out;
        } catch (Throwable t) {
            return out;
        } finally {
            if (cur != null) { try { cur.close(); } catch (Throwable ignored) {} }
            if (db != null) { try { db.close(); } catch (Throwable ignored) {} }
        }
    }

    // ------------------------------------------------------------ 小工具

    private static JSONObject readJson(File filesDir, String name) {
        try {
            String s = StateIO.read(filesDir, name);
            if (s == null || s.trim().length() == 0) return null;
            return new JSONObject(s);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeJson(File filesDir, String name, JSONObject o) {
        try {
            StateIO.write(StateIO.file(filesDir, name), o.toString());
        } catch (Throwable ignored) {
        }
    }

    private static long startOfDay(long ms) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(ms);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private static String dayKey(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(ms));
    }

    /** "2026-10-22" 取成 "10-22"：柱状图下面只要这段，年份在这一屏里没有信息量。 */
    private static String label(String key) {
        if (key == null) return "";
        return key.length() >= 10 ? key.substring(5) : key;
    }

    private static long parseDay(String key) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            f.setLenient(false);
            return f.parse(key).getTime();
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 一位小数，自己算不用 String.format：后者受 Locale 影响，某些系统上会输出逗号。 */
    private static double round1(double v) {
        if (v < 0.0) v = 0.0;
        if (v > 100.0) v = 100.0;
        long tenth = Math.round(v * 10.0);
        return tenth / 10.0;
    }

    private static String fmt1(double v) {
        long tenth = Math.round(v * 10.0);
        return (tenth / 10L) + "." + (tenth % 10L);
    }

    private static String secText(long ms) {
        if (ms <= 0L) return "暂无数据";
        if (ms < 1000L) return ms + " ms";
        long tenth = Math.round(ms / 100.0);
        return (tenth / 10L) + "." + (tenth % 10L) + " 秒";
    }

    private static Context appOf(Context c) {
        try {
            Context a = c.getApplicationContext();
            return a == null ? c : a;
        } catch (Throwable t) {
            return c;
        }
    }
}
