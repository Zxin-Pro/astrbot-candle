package com.termux.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Calendar;
import java.util.Iterator;
import java.util.Locale;

/**
 * 分级告警中枢：全应用只有这一个「发告警」的入口。
 *
 * 为什么要有它：以前每个子系统自己发通知（风控一条、掉线一条、空间一条），
 * 用户的感受是一堆长得不一样的卡片，既不知道哪条更要紧，也没法一次关掉某一类。
 * 现在统一收口在这里，按重要级分三条渠道，按类目给开关，还带免打扰、去重升级和周报采样。
 *
 * 三条渠道（重要度只能建一次，用户改过就不能再用代码调高，所以一次定死）：
 *   qqbot_alert_crit  重要告警  IMPORTANCE_HIGH   掉线、风控、崩溃
 *   qqbot_alert_warn  一般提醒  IMPORTANCE_DEFAULT 模型报错、空间紧张
 *   qqbot_alert_info  普通消息  IMPORTANCE_LOW     免打扰攒下来的汇总、测试
 *
 * 偏好落在 state/alerts.json（= files/home/.qqbot/state/alerts.json，沙盒里也能读到），
 * 键与桥 AndroidAlert.get() 的回包一一对应：五类开关 + escalate + quietFrom/quietTo，
 * 外加 mutedUntil（静音到什么时候）与 last（最近告警，给页面列表用）。
 *
 * 免打扰：quietFrom~quietTo 之间（支持跨零点）只有 crit 能当场响，其余攒进 quiet 队列，
 * 由每分钟一次的 tick 在出窗口时合并成一条 info —— 深夜被「空间不足」吵醒是最招人烦的那种。
 *
 * 升级：同一 cat+title 在 30 分钟内第 3 次起自动升 crit，正文前缀「已连续 N 次未恢复」。
 * 用它替代「每 60 秒刷一遍」：真的没恢复才升级，恢复了一次就重新计数。
 *
 * 模型报错：每 60 秒看一眼 AstrBot 最新那份日志的增量部分（记字节偏移，不重复扫），
 * 命中 401 / 429 / timeout / 超时 就发一条 warn；同一个 provider 10 分钟内只报一次。
 *
 * 线程：init() 会起一条 60 秒的守护线程（模型日志 tail + 免打扰汇总 + 周报采样），
 * 进程活着它就活着；所有状态读写都在 LOCK 上串行，桥线程与守护线程不会互相踩。
 *
 * 只写 Java 7 风格的代码：不写方法引用、不写 switch 表达式、不用 var，也不写匿名内部类 ——
 * 这包里的 d8 老工具链处理它们会崩。
 */
public final class QqbotAlertCenter {

    /** 三条渠道的 id：写进系统之后就不能改，改了等于新建一条空渠道。 */
    public static final String CH_CRIT = "qqbot_alert_crit";
    public static final String CH_WARN = "qqbot_alert_warn";
    public static final String CH_INFO = "qqbot_alert_info";

    /** 通知 id 按级别固定：同级后一条覆盖前一条，绝不会堆成一整屏。 */
    private static final int ID_CRIT = 42330;
    private static final int ID_WARN = 42331;
    private static final int ID_INFO = 42332;

    /** 通知里「静音 1 小时」按钮的广播：接收器在 init() 里动态注册（幂等）。 */
    static final String ACTION_MUTE_1H = "com.termux.app.ALERT_MUTE_1H";

    /** 偏好与历史都在这一个文件里，省得开四五个小文件互相打架。 */
    private static final String NAME = "alerts.json";

    /** 最近告警最多留这么多条（页面只显示 5 条，多留一点给排障）。 */
    private static final int LAST_MAX = 12;
    /** 免打扰积压上限：攒爆了就丢最老的，绝不能让它无限长。 */
    private static final int QUIET_MAX = 50;

    /** 升级窗口与阈值（契约：同 cat+title 30 分钟内第 3 次升 crit）。 */
    private static final long ESC_MS = 30L * 60L * 1000L;
    private static final int ESC_N = 3;

    /** 同一 provider 的模型报错 10 分钟内只报一次。 */
    private static final long MODEL_DEDUP_MS = 10L * 60L * 1000L;

    /** 单次最多读这么多日志：日志暴涨时宁可下轮接着读，也不让桥线程卡住。 */
    private static final long MAX_READ = 256L * 1024L;
    /** 第一次见到这份日志时只回看最后这么多字节，不把历史整份算进来。 */
    private static final long FIRST_TAIL = 128L * 1024L;

    /** 各类告警的中文名（标题为空时兜底，也用于测试告警文案）。 */
    private static final String[] CATS = { "offline", "risk", "space", "crash", "model" };

    /** 模型报错的判定关键词：宁可少报，也不能把普通日志当故障。 */
    private static final String[] ERR_KEYS = {
        "Error code: 401", "error code: 401", "401 Unauthorized", "Unauthorized",
        "Error code: 429", "error code: 429", "429 Too Many Requests", "rate limit", "RateLimit",
        "Request timed out", "Request timeout", "timed out", "Timeout", "超时"
    };

    private static final Object LOCK = new Object();

    /** init() 必须幂等：守护服务每次 onCreate 都会调，开机自启还会再来一次。 */
    private static volatile boolean inited = false;
    private static volatile boolean ticking = false;
    private static volatile BroadcastReceiver muteRcv = null;

    /** 配置里的 provider/model 名单缓存：每轮都读一次 cmd_config.json 太浪费。 */
    private static volatile String[] providerNames = null;
    private static volatile long providerNamesAt = 0L;

    private QqbotAlertCenter() {
    }

    // ------------------------------------------------------------ 对外入口

    /**
     * 守护服务 onCreate 调一次：建渠道、注册「静音 1 小时」接收器、补排周报闹钟、起 tick 线程。
     * 重复调用完全无害（inited/ticking 都是幂等开关）。
     */
    static void init(Context c) {
        try {
            if (c == null) return;
            Context app = appOf(c);
            ensureChannels(app);
            ensureMuteReceiver(app);
            // 服务重启 / 开机之后 AlarmManager 里是空的，周报闹钟要在这里补排；
            // reschedule 自己会吞异常，扛不住也不会把守护服务带崩。
            try { QqbotWeeklyReport.reschedule(app); } catch (Throwable ignored) {}
            inited = true;
            startTicker(app);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 统一告警入口（core 在掉线 / 崩溃 / 空间不足 / 低电高温时调）。
     *
     * cat ∈ offline|risk|space|crash|model；lv ∈ crit|warn|info。
     * 任何一步失败都只吞掉，绝不让告警把调用方（守护循环）搞崩。
     */
    static void raise(Context c, String cat, String lv, String title, String body) {
        try {
            if (c == null) return;
            deliver(appOf(c), cat, lv, title, body, false);
        } catch (Throwable ignored) {
        }
    }

    /** 桥 test() 用：无视开关 / 免打扰 / 静音，强制发一条，方便用户确认权限给没给。 */
    /**
     * 撤掉风控那条 crit 通知：RiskReader 的「我知道了」用它。
     * 为什么按 id 撤而不是按 cat 找历史：crit 渠道同一时刻只留最新一条，撤 id 最省事也最不容易错删。
     */
    static void dismissRisk(Context c) {
        try {
            NotificationManager nm = (NotificationManager) appOf(c).getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(ID_CRIT);
        } catch (Throwable ignored) {
        }
    }

    static void raiseTest(Context c, String cat) {
        try {
            if (c == null) return;
            String k = normCat(cat);
            deliver(appOf(c), k, "warn", "测试告警：" + catName(k),
                    "这是一条测试告警，说明告警通道是通的。真出事时会按级别走不同渠道。", true);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ 核心投递

    /**
     * 一条告警从「调用」走到「通知栏」的全过程：开关、静音、升级、免打扰、发通知、记历史。
     * force=true 时跳过前三步（测试用），其余照旧。
     */
    private static void deliver(Context c, String cat, String lv, String title, String body, boolean force) {
        String k = normCat(cat);
        String l = normLv(lv);
        String t = trim(title);
        String b = trim(body);
        if (t.length() == 0) t = catName(k);
        long now = System.currentTimeMillis();
        int n = 1;
        boolean quiet = false;

        synchronized (LOCK) {
            JSONObject st = state(c);
            JSONObject prefs = prefsOf(st);
            if (!force) {
                if (!prefs.optBoolean(k, true)) return;
                long muted = st.optLong("mutedUntil", 0L);
                // 静音期只挡非 crit：用户想静的是「啪一下」，不是「崩了也不说」
                if (muted > now && !"crit".equals(l)) return;
            }
            if (!force && prefs.optBoolean("escalate", true)) {
                n = bumpHist(st, k, t, now);
                if (n >= ESC_N) {
                    l = "crit";
                    String pre = "已连续 " + n + " 次未恢复";
                    b = (b.length() == 0) ? pre : (pre + "\n" + b);
                }
            }
            if (!force && !"crit".equals(l) && inQuiet(prefs, now)) {
                quiet = true;
                pushQuiet(st, k, l, t, b, now);
            } else {
                pushLast(st, k, l, t, b, now, n);
            }
            save(c, st);
        }
        if (quiet) return;
        notifyOne(c, k, l, t, b);
    }

    /** 真正发通知：按级别选渠道；渠道被用户关掉就静默放弃（他自己关的，不报错）。 */
    private static void notifyOne(Context c, String cat, String lv, String title, String body) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            ensureChannels(c);
            String ch = channelOf(lv);
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel nc = nm.getNotificationChannel(ch);
                if (nc != null && nc.getImportance() == NotificationManager.IMPORTANCE_NONE) return;
            }
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                b = new Notification.Builder(c, ch);
            } else {
                b = new Notification.Builder(c);
                b.setPriority(priorityOf(lv));
            }
            b.setContentTitle(title);
            b.setContentText(oneLine(body));
            b.setStyle(new Notification.BigTextStyle().bigText(body));
            b.setSmallIcon(smallIcon(c));
            b.setAutoCancel(true);
            b.setShowWhen(true);
            b.setWhen(System.currentTimeMillis());
            b.setCategory("crit".equals(lv) ? Notification.CATEGORY_ALARM : Notification.CATEGORY_STATUS);
            PendingIntent open = openIntent(c);
            if (open != null) b.setContentIntent(open);
            addAction(b, "重连", restartIntent(c));
            addAction(b, "看日志", logsIntent(c));
            addAction(b, "静音 1 小时", muteIntent(c));
            nm.notify(idOf(lv), b.build());
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ 通知里的三个按钮

    /**
     * 「重连」：直接打回守护服务的 ACTION_RESTART（与通知栏那条常驻卡的「重启」同一入口），
     * 不经过界面 —— targetSdk 28 下后台 startActivity 会被系统拦掉，服务里做事才稳。
     */
    private static PendingIntent restartIntent(Context c) {
        try {
            Intent i = new Intent(c, QqbotGuardService.class);
            i.setAction(QqbotGuardService.ACTION_RESTART);
            return PendingIntent.getService(c, 51, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 「看日志」：打开面板并切到日志页（QqbotActivity 认得 extra tab=logs）。 */
    private static PendingIntent logsIntent(Context c) {
        try {
            Intent i = new Intent(c, QqbotActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            i.putExtra("tab", "logs");
            return PendingIntent.getActivity(c, 52, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 「静音 1 小时」：广播回自己（接收器在 init 里注册），不需要界面在场。 */
    private static PendingIntent muteIntent(Context c) {
        try {
            Intent i = new Intent(c, QqbotAlertCenter.class);
            i.setAction(ACTION_MUTE_1H);
            i.setPackage(c.getPackageName());
            return PendingIntent.getBroadcast(c, 53, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void addAction(Notification.Builder b, String label, PendingIntent pi) {
        try {
            if (pi != null) b.addAction(0, label, pi);
        } catch (Throwable ignored) {
        }
    }

    private static PendingIntent openIntent(Context c) {
        try {
            Intent i = new Intent(c, QqbotActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            i.putExtra("tab", "more");
            return PendingIntent.getActivity(c, 50, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } catch (Throwable t) {
            return null;
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

    // ------------------------------------------------------------ 渠道

    private static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            mkChannel(nm, CH_CRIT, "重要告警", NotificationManager.IMPORTANCE_HIGH,
                    "掉线、被风控、崩溃这类必须马上处理的事", Notification.VISIBILITY_PUBLIC);
            mkChannel(nm, CH_WARN, "一般提醒", NotificationManager.IMPORTANCE_DEFAULT,
                    "模型报错、磁盘紧张这类需要看一眼的事", Notification.VISIBILITY_PRIVATE);
            mkChannel(nm, CH_INFO, "普通消息", NotificationManager.IMPORTANCE_LOW,
                    "夜间告警汇总、测试消息，不响铃只留在通知栏", Notification.VISIBILITY_PRIVATE);
        } catch (Throwable ignored) {
        }
    }

    /** 渠道已存在就一个字都不改：用户可能在系统里调过重要度/提示音，重建会冲掉他的设置。 */
    private static void mkChannel(NotificationManager nm, String id, String name, int imp,
            String desc, int vis) {
        try {
            if (nm.getNotificationChannel(id) != null) return;
            NotificationChannel ch = new NotificationChannel(id, name, imp);
            ch.setDescription(desc);
            ch.setShowBadge(imp >= NotificationManager.IMPORTANCE_DEFAULT);
            ch.setLockscreenVisibility(vis);
            nm.createNotificationChannel(ch);
        } catch (Throwable ignored) {
        }
    }

    private static String channelOf(String lv) {
        if ("crit".equals(lv)) return CH_CRIT;
        if ("info".equals(lv)) return CH_INFO;
        return CH_WARN;
    }

    private static int idOf(String lv) {
        if ("crit".equals(lv)) return ID_CRIT;
        if ("info".equals(lv)) return ID_INFO;
        return ID_WARN;
    }

    private static int priorityOf(String lv) {
        if ("crit".equals(lv)) return Notification.PRIORITY_HIGH;
        if ("info".equals(lv)) return Notification.PRIORITY_LOW;
        return Notification.PRIORITY_DEFAULT;
    }

    // ------------------------------------------------------------ 静音接收器（幂等）

    /** 系统通知栏里的「静音 1 小时」按压：动态注册，服务活着才有效（正好也只需要这时候有效）。 */
    private static void ensureMuteReceiver(Context c) {
        if (muteRcv != null) return;
        synchronized (LOCK) {
            if (muteRcv != null) return;
            try {
                MuteReceiver r = new MuteReceiver();
                IntentFilter f = new IntentFilter(ACTION_MUTE_1H);
                if (Build.VERSION.SDK_INT >= 33) {
                    c.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    c.registerReceiver(r, f);
                }
                muteRcv = r;
            } catch (Throwable ignored) {
                muteRcv = null;
            }
        }
    }

    /** 单独成类（不用匿名内部类）：d8 对匿名类不友好，这在守护进程里跑，崩不起。 */
    private static final class MuteReceiver extends BroadcastReceiver {
        public void onReceive(Context context, Intent intent) {
            try {
                if (context == null || intent == null) return;
                if (!ACTION_MUTE_1H.equals(intent.getAction())) return;
                muteFor(appOf(context), 60);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 静音 minutes 分钟；<=0 表示立刻解除。 */
    static String muteFor(Context c, int minutes) {
        try {
            if (c == null) return fail("拿不到上下文，静音没生效");
            long until = 0L;
            if (minutes > 0) until = System.currentTimeMillis() + minutes * 60000L;
            synchronized (LOCK) {
                JSONObject st = state(c);
                st.put("mutedUntil", until);
                save(c, st);
            }
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("mutedUntil", until);
            return o.toString();
        } catch (Throwable t) {
            return fail("静音失败：" + t);
        }
    }

    // ------------------------------------------------------------ 偏好读写（桥用）

    /** 给 AndroidAlert.get()：{ok,mutedUntil,prefs,last}。 */
    static String prefsJson(Context c) {
        try {
            if (c == null) return fail("告警桥还没接到界面");
            synchronized (LOCK) {
                JSONObject st = state(c);
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("mutedUntil", st.optLong("mutedUntil", 0L));
                o.put("prefs", prefsOf(st));
                JSONArray last = st.optJSONArray("last");
                o.put("last", last == null ? new JSONArray() : last);
                return o.toString();
            }
        } catch (Throwable t) {
            return fail("读告警设置失败：" + t);
        }
    }

    /**
     * 给 AndroidAlert.set()：只改传进来的那几个键，其它保持原样。
     * 未知键忽略（前端版本比原生新时不该整条失败），值非法才报错。
     */
    static String prefsUpdate(Context c, String json) {
        try {
            if (c == null) return fail("告警桥还没接到界面");
            if (json == null || json.trim().length() == 0) return fail("没有收到设置内容");
            JSONObject in = new JSONObject(json);
            synchronized (LOCK) {
                JSONObject st = state(c);
                JSONObject p = prefsOf(st);
                for (int i = 0; i < CATS.length; i++) {
                    String k = CATS[i];
                    if (in.has(k)) p.put(k, in.optBoolean(k, true));
                }
                if (in.has("escalate")) p.put("escalate", in.optBoolean("escalate", true));
                if (in.has("quietFrom")) {
                    String v = hhmm(in.optString("quietFrom", ""));
                    if (v == null) return fail("免打扰开始时间要写成 HH:mm，比如 23:00");
                    p.put("quietFrom", v);
                }
                if (in.has("quietTo")) {
                    String v = hhmm(in.optString("quietTo", ""));
                    if (v == null) return fail("免打扰结束时间要写成 HH:mm，比如 07:00");
                    p.put("quietTo", v);
                }
                st.put("prefs", p);
                save(c, st);
            }
            return "{\"ok\":true}";
        } catch (Throwable t) {
            return fail("保存告警设置失败：" + t);
        }
    }

    // ------------------------------------------------------------ 状态文件

    private static File fileOf(Context c) {
        return StateIO.file(c.getFilesDir(), NAME);
    }

    /** 读 alerts.json；坏文件 / 不存在一律当空对象，绝不让一个坏 JSON 把告警通道搞哑。 */
    private static JSONObject state(Context c) {
        try {
            String s = StateIO.read(fileOf(c));
            if (s != null && s.trim().length() > 0) {
                JSONObject o = new JSONObject(s);
                if (o != null) return o;
            }
        } catch (Throwable ignored) {
        }
        return new JSONObject();
    }

    private static boolean save(Context c, JSONObject st) {
        try {
            return StateIO.write(fileOf(c), st.toString());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 偏好对象：缺的键一律按默认值补上，前端就不用自己兜底。 */
    private static JSONObject prefsOf(JSONObject st) {
        JSONObject p = null;
        try {
            p = st.optJSONObject("prefs");
        } catch (Throwable ignored) {
        }
        if (p == null) p = new JSONObject();
        try {
            for (int i = 0; i < CATS.length; i++) {
                if (!p.has(CATS[i])) p.put(CATS[i], true);
            }
            if (!p.has("escalate")) p.put("escalate", true);
            if (!p.has("quietFrom")) p.put("quietFrom", "23:00");
            if (!p.has("quietTo")) p.put("quietTo", "07:00");
        } catch (Throwable ignored) {
        }
        return p;
    }

    private static void pushLast(JSONObject st, String cat, String lv, String title, String body,
            long now, int n) {
        try {
            JSONArray old = st.optJSONArray("last");
            JSONArray out = new JSONArray();
            JSONObject one = new JSONObject();
            one.put("t", now);
            one.put("lv", lv);
            one.put("cat", cat);
            one.put("title", title);
            one.put("body", body);
            one.put("n", n);
            out.put(one);
            if (old != null) {
                for (int i = 0; i < old.length() && out.length() < LAST_MAX; i++) {
                    JSONObject o = old.optJSONObject(i);
                    if (o != null) out.put(o);
                }
            }
            st.put("last", out);
        } catch (Throwable ignored) {
        }
    }

    private static void pushQuiet(JSONObject st, String cat, String lv, String title, String body,
            long now) {
        try {
            JSONArray old = st.optJSONArray("quiet");
            JSONArray out = new JSONArray();
            JSONObject one = new JSONObject();
            one.put("t", now);
            one.put("lv", lv);
            one.put("cat", cat);
            one.put("title", title);
            one.put("body", body);
            out.put(one);
            if (old != null) {
                for (int i = 0; i < old.length() && out.length() < QUIET_MAX; i++) {
                    JSONObject o = old.optJSONObject(i);
                    if (o != null) out.put(o);
                }
            }
            st.put("quiet", out);
        } catch (Throwable ignored) {
        }
    }

    /** 记一次并返回 30 分钟内第几次；同一个 cat+title 的老记录会被清掉。 */
    private static int bumpHist(JSONObject st, String cat, String title, long now) {
        try {
            JSONObject hist = st.optJSONObject("hist");
            if (hist == null) {
                hist = new JSONObject();
                st.put("hist", hist);
            }
            String key = cat + "|" + title;
            JSONArray arr = hist.optJSONArray(key);
            JSONArray keep = new JSONArray();
            int n = 0;
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    long t = arr.optLong(i, 0L);
                    if (now - t <= ESC_MS && keep.length() < ESC_N + 6) {
                        keep.put(t);
                        n++;
                    }
                }
            }
            keep.put(now);
            n++;
            hist.put(key, keep);
            pruneHist(hist, now);
            return n;
        } catch (Throwable t) {
            return 1;
        }
    }

    /** 升级表不能无限长：超过 1 小时没人再提的键直接删掉。 */
    private static void pruneHist(JSONObject hist, long now) {
        try {
            Iterator<String> it = hist.keys();
            String[] keys = new String[16];
            int n = 0;
            while (it != null && it.hasNext() && n < keys.length) {
                keys[n] = it.next();
                n++;
            }
            for (int i = 0; i < n; i++) {
                String k = keys[i];
                if (k == null) continue;
                JSONArray arr = hist.optJSONArray(k);
                if (arr == null || arr.length() == 0) {
                    hist.remove(k);
                    continue;
                }
                long newest = arr.optLong(arr.length() - 1, 0L);
                if (now - newest > ESC_MS * 2L) hist.remove(k);
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ 免打扰

    /** 现在是否落在免打扰区间（跨零点的写法也认）。起止相同视为没开。 */
    private static boolean inQuiet(JSONObject prefs, long now) {
        try {
            int from = mins(prefs.optString("quietFrom", "23:00"));
            int to = mins(prefs.optString("quietTo", "07:00"));
            if (from < 0 || to < 0 || from == to) return false;
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(now);
            int cur = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
            if (from < to) return cur >= from && cur < to;
            return cur >= from || cur < to;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 出了免打扰窗口就把攒下来的合并成一条 info；攒的量一次说清，别刷屏。
     * 整段吞异常：这里在 tick 线程里跑，攒的只是「提醒」，不能因为它把采样线程带停。
     */
    private static void flushQuiet(Context c) {
        String title = "";
        String body = "";
        try {
            synchronized (LOCK) {
                JSONObject st = state(c);
                JSONArray q = st.optJSONArray("quiet");
                if (q == null || q.length() == 0) return;
                JSONObject prefs = prefsOf(st);
                long now = System.currentTimeMillis();
                if (inQuiet(prefs, now)) return;
                int n = q.length();
                StringBuilder sb = new StringBuilder();
                int shown = 0;
                for (int i = 0; i < q.length() && shown < 6; i++) {
                    JSONObject one = q.optJSONObject(i);
                    if (one == null) continue;
                    sb.append("· ").append(one.optString("title", "")).append("\n");
                    shown++;
                }
                if (n > shown) sb.append("…还有 ").append(n - shown).append(" 条");
                title = "夜间 " + n + " 条告警汇总";
                body = sb.toString();
                st.put("quiet", new JSONArray());
                pushLast(st, "digest", "info", title, body, now, n);
                save(c, st);
            }
            notifyOne(c, "digest", "info", title, body);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ 60 秒守护线程

    private static void startTicker(Context c) {
        if (ticking) return;
        synchronized (LOCK) {
            if (ticking) return;
            ticking = true;
        }
        try {
            Thread th = new Thread(new Ticker(c), "qqbot-alert-tick");
            th.setDaemon(true);
            th.start();
        } catch (Throwable t) {
            ticking = false;
        }
    }

    /** 一分钟一轮：模型日志 tail、免打扰汇总、周报采样。任一步抛异常都不影响后面几步。 */
    private static final class Ticker implements Runnable {
        private final Context ctx;

        Ticker(Context c) {
            this.ctx = c;
        }

        public void run() {
            while (true) {
                try {
                    tick(ctx);
                } catch (Throwable ignored) {
                }
                try {
                    Thread.sleep(60000L);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private static void tick(Context c) {
        try { QqbotWeeklyReport.sample(c); } catch (Throwable ignored) {}
        try { watchModelLog(c); } catch (Throwable ignored) {}
        try { flushQuiet(c); } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------ 模型报错日志

    /**
     * 看一眼最新那份日志的增量：AstrBot 的 LLM 失败会在日志里留
     * 「Error occurred while processing agent: Error code: 401/429」或 timeout 字样，
     * 这里只读新追加的部分（记账字节偏移），不会每小时把几十 MB 日志重扫一遍。
     */
    private static void watchModelLog(Context c) {
        try {
            File dir = new File(QqbotShell.homeDir(c.getFilesDir()), "ubuntu/opt/AstrBot/data/logs");
            File log = newestLog(dir);
            if (log == null) return;
            long len = log.length();
            long from;
            String prevPath;
            synchronized (LOCK) {
                JSONObject st = state(c);
                JSONObject m = st.optJSONObject("model");
                prevPath = (m == null) ? "" : m.optString("file", "");
                long off = (m == null) ? 0L : m.optLong("off", 0L);
                boolean same = log.getAbsolutePath().equals(prevPath);
                // 换文件/被截断：从末尾 max(0, len-FIRST_TAIL) 起，只回看一小段，
                // 否则第一次运行会把整份历史日志里的每一次超时都报出来。
                if (!same || off > len || off < 0L) {
                    from = (len > FIRST_TAIL) ? (len - FIRST_TAIL) : 0L;
                } else {
                    from = off;
                }
            }
            if (from >= len) {
                rememberLog(c, log, len);
                return;
            }
            String text = readRange(log, from, len);
            if (text.length() == 0) {
                rememberLog(c, log, len);
                return;
            }
            // 只处理完整行：半行留到下一轮，绝不把一行的前半截当结论
            int nl = text.lastIndexOf('\n');
            if (nl < 0) return;
            String whole = text.substring(0, nl + 1);
            long used = whole.getBytes("UTF-8").length;
            String[] hit = modelHit(whole, c);
            rememberLog(c, log, from + used);
            if (hit == null) return;
            String prov = hit[0];
            String why = hit[1];
            if (!mayPost(c, prov)) return;
            raise(c, "model", "warn", "模型接口异常：" + prov,
                    why + "\n最近 10 分钟同类问题只提醒一次。\n处理建议：检查 API Key / 余额 / 网络，"
                    + "或在面板「模型」里换一个可用的 provider。");
        } catch (Throwable ignored) {
        }
    }

    /** 记下读到哪了：只改 model 这一段，别覆盖别人刚写的偏好。 */
    private static void rememberLog(Context c, File log, long off) {
        synchronized (LOCK) {
            try {
                JSONObject st = state(c);
                JSONObject m = st.optJSONObject("model");
                if (m == null) m = new JSONObject();
                m.put("file", log.getAbsolutePath());
                m.put("off", off);
                st.put("model", m);
                save(c, st);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 同一个 provider 10 分钟内只报一次；返回 true 表示这次可以发。 */
    private static boolean mayPost(Context c, String prov) {
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            try {
                JSONObject st = state(c);
                JSONObject seen = st.optJSONObject("modelSeen");
                if (seen == null) seen = new JSONObject();
                long lastAt = seen.optLong(prov, 0L);
                if (lastAt > 0L && now - lastAt < MODEL_DEDUP_MS) return false;
                seen.put(prov, now);
                pruneSeen(seen, now);
                st.put("modelSeen", seen);
                save(c, st);
                return true;
            } catch (Throwable t) {
                return false;
            }
        }
    }

    private static void pruneSeen(JSONObject seen, long now) {
        try {
            Iterator<String> it = seen.keys();
            String[] keys = new String[16];
            int n = 0;
            while (it != null && it.hasNext() && n < keys.length) {
                keys[n] = it.next();
                n++;
            }
            for (int i = 0; i < n; i++) {
                String k = keys[i];
                if (k != null && now - seen.optLong(k, 0L) > MODEL_DEDUP_MS * 3L) seen.remove(k);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 目录里 mtime 最新的 .log。AstrBot 每次启动都会换一个新文件，所以只认最新那份。 */
    private static File newestLog(File dir) {
        try {
            if (dir == null || !dir.isDirectory()) return null;
            File[] ls = dir.listFiles();
            if (ls == null) return null;
            File best = null;
            long bestAt = 0L;
            for (int i = 0; i < ls.length; i++) {
                File f = ls[i];
                if (f == null || !f.isFile()) continue;
                String nm = f.getName();
                if (nm == null || !nm.endsWith(".log")) continue;
                long at = f.lastModified();
                if (at > bestAt) {
                    bestAt = at;
                    best = f;
                }
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 在增量文本里找第一条命中；返回 {provider, 人话原因}，没有就 null。 */
    private static String[] modelHit(String text, Context c) {
        try {
            int from = 0;
            int n = text.length();
            for (int i = 0; i <= n; i++) {
                if (i < n && text.charAt(i) != '\n') continue;
                if (i > from) {
                    String line = text.substring(from, i);
                    String key = hitKey(line);
                    if (key != null) {
                        return new String[] { providerOf(line, c), reasonOf(key) };
                    }
                }
                from = i + 1;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String hitKey(String line) {
        try {
            for (int i = 0; i < ERR_KEYS.length; i++) {
                if (line.indexOf(ERR_KEYS[i]) >= 0) return ERR_KEYS[i];
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 命中词翻成人话：用户看到的应该是「密钥过期」，不是「401」。 */
    private static String reasonOf(String key) {
        if (key.indexOf("401") >= 0 || key.indexOf("Unauthorized") >= 0) {
            return "鉴权失败（401）：API Key 可能填错、过期或余额不足。";
        }
        if (key.indexOf("429") >= 0 || key.toLowerCase(Locale.US).indexOf("rate") >= 0) {
            return "请求被限流（429）：调用太频繁或额度用尽。";
        }
        return "请求超时：网络或模型服务响应太慢。";
    }

    /**
     * 从日志行里认出是哪个 provider：先看配置里的 id / type / model 名有没有出现在行里，
     * 认不出来就统称「模型接口」—— 宁可归并成一条，也不要为了一句日志去猜。
     */
    private static String providerOf(String line, Context c) {
        try {
            String[] names = names(c);
            for (int i = 0; i < names.length; i++) {
                String nm = names[i];
                if (nm != null && nm.length() >= 4 && line.indexOf(nm) >= 0) return nm;
            }
        } catch (Throwable ignored) {
        }
        return "模型接口";
    }

    /** provider 名单（cmd_config.json 里的 id/type/model），5 分钟缓存一次。 */
    private static String[] names(Context c) {
        long now = System.currentTimeMillis();
        String[] cached = providerNames;
        if (cached != null && now - providerNamesAt < 300000L) return cached;
        String[] out = new String[0];
        try {
            String s = AstrCfg.readText(AstrCfg.cfgFile(c.getFilesDir()));
            if (s != null && s.length() > 0) {
                JSONObject root = new JSONObject(s);
                String[] buf = new String[24];
                int n = 0;
                n = collect(root.optJSONArray("provider"), buf, n);
                n = collect(root.optJSONArray("provider_sources"), buf, n);
                out = new String[n];
                for (int i = 0; i < n; i++) out[i] = buf[i];
            }
        } catch (Throwable ignored) {
        }
        providerNames = out;
        providerNamesAt = now;
        return out;
    }

    private static int collect(JSONArray arr, String[] buf, int n) {
        if (arr == null) return n;
        try {
            for (int i = 0; i < arr.length() && n < buf.length; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                n = putName(buf, n, o.optString("id", ""));
                n = putName(buf, n, o.optString("type", ""));
                JSONObject mc = o.optJSONObject("model_config");
                if (mc != null) n = putName(buf, n, mc.optString("model", ""));
                n = putName(buf, n, o.optString("model", ""));
            }
        } catch (Throwable ignored) {
        }
        return n;
    }

    private static int putName(String[] buf, int n, String v) {
        if (v == null || v.length() < 4 || n >= buf.length) return n;
        for (int i = 0; i < n; i++) if (v.equals(buf[i])) return n;
        buf[n] = v;
        return n + 1;
    }

    /** 读日志的一段：失败一律返回空串，绝不让 tail 把 tick 线程打穿。 */
    private static String readRange(File f, long from, long to) {
        RandomAccessFile raf = null;
        try {
            long len = to - from;
            if (len <= 0L) return "";
            if (len > MAX_READ) len = MAX_READ;
            raf = new RandomAccessFile(f, "r");
            raf.seek(from);
            byte[] buf = new byte[(int) len];
            int n = raf.read(buf);
            if (n <= 0) return "";
            return new String(buf, 0, n, "UTF-8");
        } catch (Throwable t) {
            return "";
        } finally {
            if (raf != null) { try { raf.close(); } catch (Throwable ignored) {} }
        }
    }

    // ------------------------------------------------------------ 小工具

    private static Context appOf(Context c) {
        try {
            Context a = c.getApplicationContext();
            return a == null ? c : a;
        } catch (Throwable t) {
            return c;
        }
    }

    private static String normCat(String cat) {
        String s = trim(cat).toLowerCase(Locale.US);
        for (int i = 0; i < CATS.length; i++) if (CATS[i].equals(s)) return CATS[i];
        return (s.length() == 0) ? "offline" : s;
    }

    private static String normLv(String lv) {
        String s = trim(lv).toLowerCase(Locale.US);
        if ("crit".equals(s) || "info".equals(s)) return s;
        return "warn";
    }

    private static String catName(String cat) {
        if ("offline".equals(cat)) return "掉线";
        if ("risk".equals(cat)) return "风控";
        if ("space".equals(cat)) return "空间不足";
        if ("crash".equals(cat)) return "崩溃";
        if ("model".equals(cat)) return "模型报错";
        if ("digest".equals(cat)) return "汇总";
        return "提醒";
    }

    private static String trim(String s) {
        return (s == null) ? "" : s.trim();
    }

    /** 收起状态只显示一行：正文里的换行会被系统丢掉，先自己截一下更可控。 */
    private static String oneLine(String s) {
        String t = trim(s);
        int i = t.indexOf('\n');
        if (i >= 0) t = t.substring(0, i);
        if (t.length() > 120) t = t.substring(0, 120) + "…";
        return t;
    }

    /** "23:00" 换算成 1380 分钟；非法返回 -1。 */
    private static int mins(String hm) {
        try {
            String s = trim(hm);
            int colon = s.indexOf(':');
            if (colon <= 0) return -1;
            int h = Integer.parseInt(s.substring(0, colon).trim());
            int m = Integer.parseInt(s.substring(colon + 1).trim());
            if (h < 0 || h > 23 || m < 0 || m > 59) return -1;
            return h * 60 + m;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 规范化成 HH:mm（"9:5" 补成 "09:05"）；非法返回 null。 */
    private static String hhmm(String s) {
        int v = mins(s);
        if (v < 0) return null;
        return pad2(v / 60) + ":" + pad2(v % 60);
    }

    private static String pad2(int v) {
        return (v < 10) ? ("0" + v) : String.valueOf(v);
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
