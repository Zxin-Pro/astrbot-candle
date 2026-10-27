package com.termux.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.StatFs;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;

/**
 * 每日早报：JS 名 AndroidDaily，方法 get() / set(String)；另给原生层一个 static reschedule(Context)。
 *
 * 为什么桥和闹钟接收器写在一个类里：早报就两件事 —— 界面改设置、闹钟到点发通知。
 * 后者必须有个能在 manifest 里登记的 BroadcastReceiver，而前者要一个能被 addJavascriptInterface
 * 拿住的对象。分成两个文件没有任何好处，反而多一个文件要维护归属，所以合成一个类，
 * 无参构造给系统反射用（ctx 为 null，只跑 onReceive），带 Context 的构造给界面用。
 *
 * 为什么不自己造通知渠道、而用工程里现成的写法：渠道名一旦进系统就删不掉了，
 * 必须跟「后台守护 / 机器人状态」那一套保持同一风格（平台 Notification.Builder、
 * ic_stat_qqbot 小图标、点击落到 App 页面），否则「更多 → 通知卡」里会出现两个长得不一样的开关。
 *
 * 为什么设置存 state/prefs.json 而不是 SharedPreferences：沙盒里的脚本也要知道早报开没开，
 * 而 SharedPreferences 落在 $HOME 之外，脚本读不到（与 Prefs.java 的注释同一理由）。
 *
 * d8 处理匿名内部类会崩，本文件不出现匿名类，也没有 lambda / stream（Java 7）。
 */
public class QqbotDailyReport extends BroadcastReceiver {

    /** 显式 Intent 本来只会进本类；action 留着判一次，防止以后被别的 filter 复用。 */
    static final String ACTION = "com.termux.app.DAILY_REPORT";

    /** 闹钟与通知的固定 id：同一个 PendingIntent 必须一直用同一组 requestCode，否则 cancel 不掉旧的。 */
    private static final int REQ_ALARM = 4713;
    private static final int REQ_OPEN = 33;
    private static final int NOTI_ID = 42402;

    private static final String CH_ID = "qqbot_daily";
    private static final String CH_NAME = "每日早报";

    /** prefs.json 里的键：开关 / 时间 / 上次真正发出去的时刻。 */
    private static final String P_ON = "daily.on";
    private static final String P_TIME = "daily.time";
    private static final String P_LAST = "daily.last";

    private static final String DEF_TIME = "08:00";
    private static final String FMT = "yyyy-MM-dd HH:mm";

    /** 「机器人状态」那套通知开关的文件名与键（QqbotGuardService 里同名，这里只读）。 */
    private static final String NOTI_PREF = "qqbot_notify";
    private static final String NOTI_MASTER = "notify.master";
    private static final String NOTI_DAILY = "notify.daily";

    private static final long DAY_MS = 86400000L;

    private final Context ctx;

    /** 系统反射实例化接收器时走这个：没有 Context，只可能是 onReceive。 */
    public QqbotDailyReport() {
        this.ctx = null;
    }

    /** 界面注入用：Activity 传 this 即可（与其它桥一致）。 */
    public QqbotDailyReport(Context ctx) {
        this.ctx = ctx;
    }

    // ---------------------------------------------------------------- 桥方法

    /**
     * 读早报设置：{"ok":true,"on":true,"time":"08:00","last":"2026-10-27 08:00","next":"2026-10-28 08:00"}。
     * 未知值一律给空串（不是 null），界面不用再判 undefined。
     */
    @JavascriptInterface
    public String get() {
        try {
            Context c = ctx;
            if (c == null) return fail("早报桥还没接到界面");
            File fd = c.getFilesDir();
            String time = timeOf(fd);
            boolean on = Prefs.getBool(fd, P_ON, false);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("on", on);
            o.put("time", time);
            o.put("last", Prefs.load(fd).optString(P_LAST, ""));
            // 关掉时不预测「下次」：给一个时间却不会响，比空串更容易误导用户
            o.put("next", on ? fmt(nextTrigger(time)) : "");
            return o.toString();
        } catch (Throwable t) {
            return fail("读早报设置失败：" + t);
        }
    }

    /**
     * 写早报设置，入参 {"on":true,"time":"08:00"}（两个键都可省，省了就不动）。
     * 改完立刻重排闹钟 —— 时间改了而旧闹钟还在，是最容易出的那种「改了不生效」。
     */
    @JavascriptInterface
    public String set(String json) {
        try {
            Context c = ctx;
            if (c == null) return fail("早报桥还没接到界面");
            if (json == null || json.trim().length() == 0) return fail("没有收到设置内容");
            JSONObject in = new JSONObject(json);
            File fd = c.getFilesDir();

            if (in.has("time")) {
                String hm = normalize(in.optString("time", ""));
                if (hm == null) return fail("时间要写成 HH:mm，比如 08:00");
                if (!Prefs.put(fd, P_TIME, hm)) return fail("存时间失败，磁盘可能满了");
            }
            if (in.has("on")) {
                if (!Prefs.put(fd, P_ON, in.optBoolean("on", true))) return fail("存开关失败，磁盘可能满了");
            }
            reschedule(c);
            return "{\"ok\":true}";
        } catch (Throwable t) {
            return fail("保存早报设置失败：" + t);
        }
    }

    // ---------------------------------------------------------------- 闹钟

    /**
     * 按当前设置重排闹钟：先撤旧的，再按新时间排。
     *
     * 为什么公开成 static：开机（QqbotBootReceiver）、服务启动（QqbotGuardService）后闹钟全丢，
     * 这两处要能直接把自己的 Context 递进来重排，而它们不该 new 一个桥对象。
     * 关掉开关时只撤不排 —— 不能留一个「已经关了但明天还会响」的闹钟。
     *
     * 为什么用 setInexactRepeating 而不是 setExact：早报差几分钟无所谓，非精确闹钟不需要
     * SCHEDULE_EXACT_ALARM 权限，也不会被系统按「精确闹钟滥用」限流。
     */
    public static void reschedule(Context ctx) {
        try {
            if (ctx == null) return;
            Context c = appOf(ctx);
            PendingIntent pi = pending(c);
            if (pi == null) return;
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            File fd = c.getFilesDir();
            if (am == null) {
                pi.cancel();
                return;
            }
            am.cancel(pi);
            if (!Prefs.getBool(fd, P_ON, false)) {
                pi.cancel();
                return;
            }
            am.setInexactRepeating(AlarmManager.RTC_WAKEUP, nextTrigger(timeOf(fd)), AlarmManager.INTERVAL_DAY, pi);
        } catch (Throwable ignored) {
            // 这方法会在开机、服务拉起这些「一抛就掉进程」的地方被调，吞掉比崩掉安全
        }
    }

    /** 闹钟到点：发一条通知，然后什么都不用做（repeating 闹钟自己会再排下一次）。 */
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (context == null || intent == null) return;
            if (!ACTION.equals(intent.getAction())) return;
            Context c = appOf(context);
            if (!Prefs.getBool(c.getFilesDir(), P_ON, false)) return;
            post(c);
        } catch (Throwable ignored) {
            // 接收器抛异常会被系统记成 ANR/崩溃，早报这种锦上添花的东西不值得
        }
    }

    private static PendingIntent pending(Context c) {
        try {
            Intent i = new Intent(c, QqbotDailyReport.class);
            i.setAction(ACTION);
            // FLAG_IMMUTABLE：targetSdk 28 上系统要求显式声明可变性；这里没人往里塞东西
            return PendingIntent.getBroadcast(c, REQ_ALARM, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 通知

    private static void post(Context c) {
        try {
            if (!notifyAllowed(c)) return;
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            ensureChannel(c);
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = nm.getNotificationChannel(CH_ID);
                // 用户在系统设置里把这个渠道关了：不发，也不报错（他自己关的）
                if (ch != null && ch.getImportance() == NotificationManager.IMPORTANCE_NONE) return;
            }
            Body b = body(c);
            nm.notify(NOTI_ID, build(c, b));
            // 只有真发出去了才记 last：被开关/渠道挡住的那次不算「今天报过」
            Prefs.put(c.getFilesDir(), P_LAST, nowText());
        } catch (Throwable ignored) {
        }
    }

    private static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            // 已存在就一个字都不改：用户可能在系统里调过重要性/提示音，重建会把它冲掉
            if (nm.getNotificationChannel(CH_ID) != null) return;
            NotificationChannel ch = new NotificationChannel(CH_ID, CH_NAME, NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("每天早上报一次：运行时长、今天收了多少条消息、有没有掉过线、存储还剩多少");
            ch.setShowBadge(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        } catch (Throwable ignored) {
        }
    }

    private static Notification build(Context c, Body b) {
        Notification.Builder nb;
        if (Build.VERSION.SDK_INT >= 26) {
            nb = new Notification.Builder(c, CH_ID);
        } else {
            nb = new Notification.Builder(c);
            nb.setPriority(Notification.PRIORITY_DEFAULT);
        }
        nb.setContentTitle("QQ 机器人早报");
        nb.setContentText(b.head);
        nb.setSmallIcon(smallIcon(c));
        nb.setAutoCancel(true);
        nb.setShowWhen(true);
        nb.setWhen(System.currentTimeMillis());

        Notification.InboxStyle style = new Notification.InboxStyle();
        style.setBigContentTitle("QQ 机器人早报");
        style.setSummaryText(timeOf(c.getFilesDir()));
        for (int i = 0; i < b.lines.length; i++) {
            style.addLine(b.lines[i]);
        }
        nb.setStyle(style);

        PendingIntent open = openIntent(c);
        if (open != null) nb.setContentIntent(open);
        return nb.build();
    }

    /** 点通知回到 App 的「更多」页：早报开关就在那一页的通知卡上，少跳一次。 */
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

    /** 小图标跟状态通知同一个：ic_stat_qqbot 缺失时退到应用图标，再退到系统图标。 */
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

    // ---------------------------------------------------------------- 正文

    /** 通知正文：head 给收起状态（一行），lines 给展开状态（InboxStyle 最多显示 5 行，这里给 4 行）。 */
    private static final class Body {
        String head = "";
        String[] lines = new String[0];
    }

    private static Body body(Context c) {
        Body b = new Body();
        try {
            File fd = c.getFilesDir();
            long now = System.currentTimeMillis();

            String runText;
            JSONObject n = readJson(fd, "notify.json");
            String st = n == null ? "" : n.optString("st", "");
            long upSince = n == null ? 0L : n.optLong("upSince", 0L);
            if ("down".equals(st)) {
                runText = "现在掉线中";
            } else if (upSince > 0L && upSince <= now) {
                runText = "已连续 " + durText(now - upSince);
            } else if (n == null) {
                runText = "守护没在跑（没读到状态）";
            } else {
                runText = "在跑，时长待算";
            }

            String msgText;
            long msgs = -1L;
            boolean warm = false;
            try {
                JSONObject ts = new JSONObject(TodayStats.todayStats());
                warm = ts.optBoolean("warm", false);
                msgs = ts.optLong("msgs", -1L);
            } catch (Throwable ignored) {
            }
            if (warm && msgs >= 0L) {
                msgText = "今天 " + msgs + " 条";
            } else {
                msgText = "今天还没统计到";
            }

            String offText;
            long off = lastOffline(fd);
            if (off <= 0L) {
                offText = "记录里还没有掉线";
            } else if (off * 1000L >= startOfToday()) {
                offText = "今天掉过（最近 " + clock(off * 1000L) + "）";
            } else {
                offText = "今天没掉线";
            }

            b.lines = new String[] {
                "运行：" + runText,
                "消息：" + msgText,
                "掉线：" + offText,
                "空间：剩余 " + freeText(fd)
            };
            b.head = runText + " · " + msgText;
            return b;
        } catch (Throwable t) {
            b.lines = new String[] { "早报生成失败，点开 App 看详情" };
            b.head = "早报生成失败";
            return b;
        }
    }

    /**
     * 最近一次掉线（epoch 秒）：instances.json 是守护循环每 60 秒刷一次的扁平对象
     * {实例id: {started, lastOffline, ...}}，取所有实例里最大的 lastOffline。
     * 为什么不用 notify.json 的 st/since：那两个是「当前状态 + 掉线起表」，
     * 掉线判定要连续 2 分钟才坐实，取不到「今天有没有掉过」这种历史事实。
     */
    private static long lastOffline(File fd) {
        try {
            JSONObject root = readJson(fd, "instances.json");
            if (root == null) return 0L;
            long max = 0L;
            Iterator<String> it = root.keys();
            while (it != null && it.hasNext()) {
                JSONObject one = root.optJSONObject(it.next());
                if (one == null) continue;
                long t = one.optLong("lastOffline", 0L);
                if (t > max) max = t;
            }
            return max;
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static JSONObject readJson(File fd, String name) {
        try {
            String s = StateIO.read(fd, name);
            if (s == null || s.trim().length() == 0) return null;
            return new JSONObject(s);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 剩余空间：StatFs 比脚本 du 快得多，也不受 busybox 是否装了的限制。 */
    private static String freeText(File dir) {
        try {
            StatFs sf = new StatFs(dir.getAbsolutePath());
            long free = sf.getAvailableBytes();
            if (free <= 0L) return "读不到";
            long gb = 1024L * 1024L * 1024L;
            if (free >= gb) return oneDecimal(free, gb) + " GB";
            return (free / (1024L * 1024L)) + " MB";
        } catch (Throwable t) {
            return "读不到";
        }
    }

    /** 一位小数，自己算不用 String.format：后者受 Locale 影响，某些系统上会输出逗号。 */
    private static String oneDecimal(long v, long unit) {
        long tenth = v * 10L / unit;
        return (tenth / 10L) + "." + (tenth % 10L);
    }

    private static String durText(long ms) {
        if (ms < 0L) ms = 0L;
        long min = ms / 60000L;
        if (min < 1L) return "不到 1 分钟";
        long d = min / 1440L;
        long h = (min % 1440L) / 60L;
        long m = min % 60L;
        if (d > 0L) return d + " 天 " + h + " 小时";
        if (h > 0L) return h + " 小时 " + m + " 分";
        return m + " 分钟";
    }

    // ---------------------------------------------------------------- 时间

    /** 设置里的时间，非法/没设置都给默认 08:00：早报不能因为一个坏字段就不响。 */
    private static String timeOf(File fd) {
        try {
            String hm = normalize(Prefs.load(fd).optString(P_TIME, ""));
            return hm == null ? DEF_TIME : hm;
        } catch (Throwable t) {
            return DEF_TIME;
        }
    }

    /** 校验并规范化成 HH:mm（"9:5" 也算对，回 "09:05"）；非法返回 null。 */
    private static String normalize(String s) {
        if (s == null) return null;
        String t = s.trim();
        int colon = t.indexOf(':');
        if (colon <= 0 || colon >= t.length() - 1) return null;
        String hh = t.substring(0, colon).trim();
        String mm = t.substring(colon + 1).trim();
        if (!digits(hh) || !digits(mm)) return null;
        int h;
        int m;
        try {
            h = Integer.parseInt(hh);
            m = Integer.parseInt(mm);
        } catch (Throwable x) {
            return null;
        }
        if (h < 0 || h > 23 || m < 0 || m > 59) return null;
        return pad2(h) + ":" + pad2(m);
    }

    /** 下一次 HH:mm 的触发时刻（毫秒）：今天的点已过就顺延到明天。 */
    private static long nextTrigger(String hm) {
        int h = 8;
        int m = 0;
        try {
            int colon = hm == null ? -1 : hm.indexOf(':');
            if (colon > 0) {
                h = Integer.parseInt(hm.substring(0, colon));
                m = Integer.parseInt(hm.substring(colon + 1));
            }
        } catch (Throwable ignored) {
        }
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, h);
        cal.set(Calendar.MINUTE, m);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long at = cal.getTimeInMillis();
        if (at <= System.currentTimeMillis()) at += DAY_MS;
        return at;
    }

    private static long startOfToday() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private static String fmt(long ms) {
        return new SimpleDateFormat(FMT, Locale.US).format(new Date(ms));
    }

    private static String nowText() {
        return fmt(System.currentTimeMillis());
    }

    private static String clock(long ms) {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(ms));
    }

    private static String pad2(int v) {
        return v < 10 ? ("0" + v) : String.valueOf(v);
    }

    private static boolean digits(String s) {
        if (s == null || s.length() == 0) return false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < '0' || ch > '9') return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- 杂项

    /** 跟「机器人状态」共用一组通知开关：总开关关了、或单独的早报开关关了，就都不发。 */
    private static boolean notifyAllowed(Context c) {
        try {
            SharedPreferences sp = c.getSharedPreferences(NOTI_PREF, Context.MODE_PRIVATE);
            if (!sp.getBoolean(NOTI_MASTER, true)) return false;
            return sp.getBoolean(NOTI_DAILY, true);
        } catch (Throwable t) {
            return true; // 读不到开关时按「开着」算：用户没关过，别让早报静默消失
        }
    }

    private static Context appOf(Context c) {
        try {
            Context a = c.getApplicationContext();
            return a == null ? c : a;
        } catch (Throwable t) {
            return c;
        }
    }

    private static String fail(String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("err", msg == null ? "" : msg);
            return o.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"err\":\"未知错误\"}";
        }
    }
}
