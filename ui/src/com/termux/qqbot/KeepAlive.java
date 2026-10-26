package com.termux.app;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 后台保活心跳（1.3.3-beta.1 起）。
 *
 * 为什么需要：前台服务能挡掉一部分回收，但挡不住厂商 ROM 的「后台高耗电」冻结、
 * Android 12+ 的 phantom process killer，以及缓存进程整组冻结。被冻结时进程还在、
 * 端口还开着，但 CPU 一秒钟都拿不到，表现就是「看着在运行，其实没运行，点回软件才继续」。
 *
 * 做法：AlarmManager 每 2 分钟发一次精确广播（setExactAndAllowWhileIdle）。每次投递
 * 系统会给本应用 10 分钟「临时白名单」，2 分钟一次就相当于整段时间都在白名单里，
 * 冻结与 Doze 都绕过去了；广播本身还会把服务拉起来、把沙盒兜回来。
 *
 * targetSdk 是 28，所以 setExactAndAllowWhileIdle 不需要 SCHEDULE_EXACT_ALARM 权限，
 * 也不会被 Android 12+ 的精确闹钟限制拦下。
 */
public class KeepAlive {

    public static final String ACTION = "com.termux.app.KEEPALIVE";
    private static final int REQ = 42312;
    /** 2 分钟：小于系统给的 10 分钟临时白名单窗口，白名单才能首尾相接。 */
    private static final long EVERY_MS = 120000L;
    private static final String PREF = "qqbot_keepalive";
    private static final String K_FAIL = "probeFail";
    private static final String K_LAST = "lastRestart";
    /** 连续几次 HTTP 探测没回才认定「被冻结」，3 次约 6 分钟。 */
    private static final int FAIL_MAX = 3;
    /** 两次「冻结重启」之间至少隔 10 分钟，避免沙盒自己起得慢时被反复重启。 */
    private static final long RESTART_GAP_MS = 600000L;
    /** 息屏模式（灭屏加强）：心跳 1 分钟一次，1 次不应答就准备重启，两次自愈间隔 5 分钟。 */
    private static final long EVERY_OFF_MS = 60000L;
    private static final int FAIL_MAX_OFF = 2;
    private static final long RESTART_GAP_OFF_MS = 300000L;

    private KeepAlive() {
    }

    /** 用户主动关过（关闭进程 / 紧急下线）就不再自己醒来。 */
    public static boolean closedByUser(Context c) {
        try {
            return StateIO.file(c.getFilesDir(), "offline").isFile();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 排下一次心跳。每次收到广播 / 服务启动时都调一次，链就不断。 */
    public static void arm(Context c) {
        try {
            if (closedByUser(c) || !QqbotGuardService.enabled(c)) {
                disarm(c);
                return;
            }
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(c, KeepAliveReceiver.class);
            i.setAction(ACTION);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(c, REQ, i, flags);
            if (pi == null) return;
            long every = everyMs(c);
            long at = System.currentTimeMillis() + every;
            // 用户关掉「息屏保持运行」时用非唤醒闹钟：屏幕不亮、设备睡着就等它自己醒，
            // 只保证进程被系统收掉后还能被拉回来，不强行耗电。
            // 例外是息屏模式（灭屏加强）：它存在的意义就是「黑屏期间也要真在跑」，
            // 非唤醒闹钟在深度睡眠里根本不会投递，所以这时强制用唤醒闹钟。
            boolean wake = ScreenMode.boosted(c) || QqbotGuardService.wakeEnabled(c);
            int type = wake ? AlarmManager.RTC_WAKEUP : AlarmManager.RTC;
            if (wake && Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(type, at, pi);
            } else {
                am.setExact(type, at, pi);
            }
            // 第二条命：JobScheduler 周期任务。闹钟被 ROM 掐掉、精确闹钟在 Doze 里被
            // 限流到 9 分钟一次时，这条由系统自己调度的线还在（见 KeepAliveJobService）。
            scheduleJob(c);
        } catch (Throwable t) {
            // 各家 ROM 对精确闹钟的实现有差异，失败就退回普通闹钟，绝不因此崩掉服务。
            try {
                AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
                Intent i = new Intent(c, KeepAliveReceiver.class);
                i.setAction(ACTION);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
                PendingIntent pi = PendingIntent.getBroadcast(c, REQ, i, flags);
                if (am != null && pi != null) {
                    am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + everyMs(c), pi);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 心跳间隔：息屏模式的黑屏期间 1 分钟，平时 2 分钟。 */
    private static long everyMs(Context c) {
        try {
            return ScreenMode.boosted(c) ? EVERY_OFF_MS : EVERY_MS;
        } catch (Throwable t) {
            return EVERY_MS;
        }
    }

    /** 用户点了「关闭进程」或关掉常驻守护：把心跳撤掉，别让手机继续被叫醒。 */
    public static void disarm(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(c, KeepAliveReceiver.class);
            i.setAction(ACTION);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(c, REQ, i, flags);
            if (pi != null) {
                am.cancel(pi);
                pi.cancel();
            }
        } catch (Throwable ignored) {
        }
        cancelJob(c);
    }

    /**
     * 登记 JobScheduler 周期任务（幂等：已经排过就不动，避免每次都把周期重置、
     * 让任务永远等不到第一次触发）。
     * 15 分钟是 JobInfo.setPeriodic 的下限；它只兜「整条链已经断了」的极端场景，
     * 正常节奏仍是 2 分钟一次的闹钟。
     */
    static void scheduleJob(android.content.Context c) {
        if (c == null) return;
        try {
            if (Build.VERSION.SDK_INT < 21) return;
            android.app.job.JobScheduler js =
                    (android.app.job.JobScheduler) c.getSystemService(android.content.Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            if (Build.VERSION.SDK_INT >= 24 && js.getPendingJob(KeepAliveJobService.JOB_ID) != null) return;
            android.app.job.JobInfo.Builder b = new android.app.job.JobInfo.Builder(
                    KeepAliveJobService.JOB_ID,
                    new android.content.ComponentName(c, KeepAliveJobService.class));
            b.setPeriodic(15L * 60L * 1000L);
            // setPersisted 需要清单里的 RECEIVE_BOOT_COMPLETED（已有），重启后任务自动恢复。
            b.setPersisted(true);
            // 不要求联网、不要求充电、不要求 idle：这些条件只会让它更少被调度。
            b.setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_NONE);
            b.setRequiresCharging(false);
            b.setRequiresDeviceIdle(false);
            int rc = js.schedule(b.build());
            // 0 = RESULT_SUCCESS。这里必须看返回值：JobScheduler 失败时不抛异常，
            // 只回一个错误码（比如 RESULT_FAILURE=1），不查的话「第二条命」等于没排上，
            // 而且日志里一条线索都没有。
            if (rc != android.app.job.JobScheduler.RESULT_SUCCESS) {
                android.util.Log.w("QqbotKeepAlive", "登记 JobScheduler 心跳被拒，rc=" + rc);
            } else {
                android.util.Log.i("QqbotKeepAlive", "已登记 JobScheduler 心跳（15 分钟一次）");
            }
        } catch (Throwable t) {
            android.util.Log.w("QqbotKeepAlive", "登记 JobScheduler 心跳失败：" + t);
        }
    }

    /** 撤掉 JobScheduler 周期任务（用户「关闭进程 / 紧急下线 / 关掉守护」时）。 */
    static void cancelJob(android.content.Context c) {
        if (c == null) return;
        try {
            if (Build.VERSION.SDK_INT < 21) return;
            android.app.job.JobScheduler js =
                    (android.app.job.JobScheduler) c.getSystemService(android.content.Context.JOB_SCHEDULER_SERVICE);
            if (js != null) js.cancel(KeepAliveJobService.JOB_ID);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 心跳里的体检 + 自救（在主线程外调用）：
     * ① 沙盒进程没了 → 直接拉起；
     * ② 进程在、但 6099 的 WebUI 连续 3 次不应答（多半被整组冻结）→ 重启沙盒。
     * 只在应用处于后台时做 ②：用户正开着面板时，守护循环每 60 秒本来就会兜底，
     * 不该让它把正在冷启动的沙盒又打断一次。
     * 息屏模式（ScreenMode）开着且此刻黑屏时，② 放宽为「不要求后台」，阈值改成
     * 2 次 / 自愈间隔 5 分钟：用户看不见面板，只能靠这一层兜回来。
     */
    public static void rescue(Context c) {
        try {
            File filesDir = c.getFilesDir();
            if (closedByUser(c) || !QqbotGuardService.enabled(c)) return;
            if (!QqbotShell.bootstrapReady(filesDir)) return;
            File qq = QqbotShell.qqbotDir(filesDir);
            if (!new File(qq, "deploy.done").isFile()) return;
            // 正在部署 / 解压镜像时别插队：那会儿沙盒本来就还没起，去重启只会打断部署。
            if (QqbotShell.pidAlive(new File(qq, "deploy.pid"), null)) return;
            File pid = new File(qq, "supervisor.pid");
            if (!QqbotShell.pidAlive(pid, "proot")) {
                StringBuilder n = new StringBuilder();
                boolean ok = QqbotShell.startSandbox(filesDir, n);
                android.util.Log.i("QqbotKeepAlive", "心跳发现沙盒没在跑：" + (ok ? "已拉起" : "拉起失败 " + n));
                return;
            }
            int port = botPort(c);
            if (port <= 0) return;
            // 息屏模式下不再要求「应用处于后台」：黑屏那一刻 Activity 可能还是 RESUMED，
            // 但用户根本看不见面板，卡住的沙盒得照常救回来。阈值也一并收紧。
            boolean boost = ScreenMode.boosted(c);
            if ((boost || background(c)) && !httpAlive(port)) {
                int failMax = boost ? FAIL_MAX_OFF : FAIL_MAX;
                long gap = boost ? RESTART_GAP_OFF_MS : RESTART_GAP_MS;
                SharedPreferences p = pref(c);
                int fail = p.getInt(K_FAIL, 0) + 1;
                long now = System.currentTimeMillis();
                long last = p.getLong(K_LAST, 0L);
                if (fail >= failMax && now - last > gap) {
                    p.edit().putInt(K_FAIL, 0).putLong(K_LAST, now).apply();
                    android.util.Log.w("QqbotKeepAlive", "沙盒进程在、WebUI 连续 " + fail + " 次没应答，按冻结处理并重启沙盒");
                    File stop = new File(QqbotShell.usrDir(filesDir), "opt/qqbot/stop-sandbox.sh");
                    if (stop.isFile()) QqbotShell.run(filesDir, "bash " + stop.getAbsolutePath(), 120);
                    StringBuilder n = new StringBuilder();
                    QqbotShell.startSandbox(filesDir, n);
                    QqbotShell.startWatchdog(filesDir, null);
                } else {
                    p.edit().putInt(K_FAIL, fail).apply();
                }
                return;
            }
            pref(c).edit().putInt(K_FAIL, 0).apply();
            QqbotShell.startWatchdog(filesDir, null);
        } catch (Throwable t) {
            android.util.Log.w("QqbotKeepAlive", "心跳自检异常：" + t);
        }
    }

    private static SharedPreferences pref(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 应用当前是否在后台（IMPORTANCE_VISIBLE 及以下才算用户看得见）。 */
    private static boolean background(Context c) {
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return true;
            int pid = android.os.Process.myPid();
            java.util.List<ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
            if (list == null) return true;
            for (int k = 0; k < list.size(); k++) {
                ActivityManager.RunningAppProcessInfo r = list.get(k);
                if (r != null && r.pid == pid) {
                    return r.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
                }
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    /**
     * 真的发一个 HTTP 请求：被冻结的进程虽然 socket 还挂着，但连接会被内核排进 backlog，
     * 读不到任何响应；能读到首字节才算活着。
     */
    private static boolean httpAlive(int port) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", port), 1500);
            s.setSoTimeout(3000);
            OutputStream o = s.getOutputStream();
            o.write("GET / HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n".getBytes("UTF-8"));
            o.flush();
            InputStream in = s.getInputStream();
            return in.read() >= 0;
        } catch (Throwable t) {
            return false;
        } finally {
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    /** 读 bots.json 第一个实例的 NapCat 端口；读不到就用默认 6099。 */
    private static int botPort(Context c) {
        try {
            File qq = QqbotShell.qqbotDir(c.getFilesDir());
            String s = StateIO.read(new File(qq, "bots.json"));
            if (s == null) s = StateIO.read(new File(new File(qq, "cache"), "bots.json"));
            if (s == null) return 6099;
            JSONObject o = new JSONObject(s);
            JSONArray a = o.optJSONArray("bots");
            if (a == null || a.length() == 0) return 6099;
            JSONObject b = a.optJSONObject(0);
            if (b == null) return 6099;
            int p = b.optInt("napcatPort", 6099);
            return p > 0 ? p : 6099;
        } catch (Throwable t) {
            return 6099;
        }
    }
}