package com.termux.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;

/**
 * 最外层防掉线：一个常驻前台服务。
 *
 * 为什么必须有它：Android 把「没有前台服务的应用」当缓存进程，随时可以杀掉，
 * 而且从 Android 11 起会对缓存进程整组冻结（cached app freezer）。沙盒里的
 * proot/NapCat/AstrBot 虽然被 init 收养、能活过父进程，但一旦整组被冻结，
 * 进程还在、端口还在，却完全不再收发数据——表现就是「AstrBot 莫名其妙掉线」。
 * 前台服务让本应用始终是「前台服务进程」，不会被杀也不会被冻结。
 *
 * 它还持有 PARTIAL_WAKE_LOCK：息屏后 CPU 不再进深度睡眠，NapCat 才能继续
 * 维持 QQ 长连接。可以在「更多 → 后台守护」里关掉这个唤醒。
 *
 * 内部由 QqbotGuardTask 每 60 秒自检一次：沙盒没了就拉起来，看门狗没了就补上。
 * d8 处理匿名内部类会崩，本文件不出现匿名类。
 */
public class QqbotGuardService extends Service {

    public static final String CHANNEL_ID = "qqbot_guard";
    public static final int NOTI_ID = 42310;

    /** 供界面查询：守护是否在跑、最近一次动作。 */
    public static volatile boolean guardRunning = false;
    public static volatile String lastNote = "";

    private PowerManager.WakeLock lock;
    private QqbotGuardTask task;
    private Thread thread;

    /* ---------- 静态入口 ---------- */

    public static void start(Context c) {
        try {
            Intent i = new Intent(c, QqbotGuardService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable ignored) {
        }
    }

    public static void stop(Context c) {
        try {
            c.stopService(new Intent(c, QqbotGuardService.class));
        } catch (Throwable ignored) {
        }
    }

    /** 守护总开关文件：存在即表示用户关掉了守护（默认开）。 */
    public static File offFlag(Context c) {
        return new File(new File(new File(c.getFilesDir(), "home"), ".qqbot"), "guard.off");
    }

    /** 常驻唤醒开关文件：存在即表示用户关掉了唤醒（默认开）。 */
    public static File wakeOffFlag(Context c) {
        return new File(new File(new File(c.getFilesDir(), "home"), ".qqbot"), "wake.off");
    }

    /** 按当前开关状态决定起还是停，启动/保存设置后都调它。 */
    public static void apply(Context c) {
        if (offFlag(c).isFile()) stop(c);
        else start(c);
    }

    public static boolean enabled(Context c) {
        return !offFlag(c).isFile();
    }

    public static boolean wakeEnabled(Context c) {
        return !wakeOffFlag(c).isFile();
    }

    /** 切换守护总开关：写/删标记文件后按开关状态起停服务。返回一句给前端 toast 的话。 */
    public static String setGuard(Context c, boolean on) {
        mark(offFlag(c), !on);
        apply(c);
        return on ? "已开启常驻守护" : "已关闭常驻守护";
    }

    /** 切换「息屏保持运行」：写/删标记文件后带 ACTION 再叫一次服务，就地生效。 */
    public static String setWake(Context c, boolean on) {
        mark(wakeOffFlag(c), !on);
        Intent i = new Intent(c, QqbotGuardService.class);
        i.setAction("com.termux.app.GUARD_WAKE");
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable ignored) {
        }
        return on ? "已开启息屏保持运行" : "已关闭息屏保持运行";
    }

    /** 开关文件就是「存在＝关闭」的标记，创建/删除它即可。 */
    private static void mark(File f, boolean present) {
        try {
            if (present) {
                File p = f.getParentFile();
                if (p != null) p.mkdirs();
                new java.io.FileOutputStream(f).close();
            } else {
                f.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 申请加入电池优化白名单。不申请的话 Doze 会在息屏一段时间后掐掉后台网络，
     * 表现同样是「沙盒还在跑，但 QQ 掉线了」。
     */
    public static String requestIgnoreBattery(android.app.Activity a) {
        try {
            PowerManager pm = (PowerManager) a.getSystemService(Context.POWER_SERVICE);
            String pkg = a.getPackageName();
            if (pm != null && pm.isIgnoringBatteryOptimizations(pkg)) return "已经在电池优化白名单里，无需重复申请";
            Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(android.net.Uri.parse("package:" + pkg));
            a.startActivity(i);
            return "已打开申请页面，请点「允许」";
        } catch (Throwable t) {
            // 少数 ROM 没有这个系统页面，退回电池优化总列表让用户手动找到本应用。
            try {
                a.startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                return "请在本页把「AstrBot烛版」设为不优化";
            } catch (Throwable t2) {
                return "这台设备打不开电池优化设置，请到系统设置里手动允许后台运行";
            }
        }
    }

    /* ---------- 生命周期 ---------- */

    public IBinder onBind(Intent i) { return null; }

    public void onCreate() {
        super.onCreate();
        guardRunning = true;
        try {
            startForeground(NOTI_ID, buildNoti("正在检查沙盒…"));
        } catch (Throwable ignored) {
        }
        acquireWake();
        task = new QqbotGuardTask(this);
        thread = new Thread(task, "qqbot-guard");
        thread.setDaemon(true);
        thread.start();
    }

    public int onStartCommand(Intent i, int flags, int startId) {
        // 界面上切换「常驻唤醒」时带着 ACTION 再叫一次服务，就地生效。
        if (i != null && "com.termux.app.GUARD_WAKE".equals(i.getAction())) {
            if (wakeEnabled(this)) acquireWake(); else releaseWake();
        }
        // 被系统回收后自动重来；就算重来时沙盒好好的，任务循环也只是一直空转。
        return START_STICKY;
    }

    public void onDestroy() {
        guardRunning = false;
        if (task != null) task.stop();
        releaseWake();
        super.onDestroy();
    }

    /* ---------- 唤醒锁 ---------- */

    private void acquireWake() {
        try {
            if (!wakeEnabled(this)) return;
            if (lock != null && lock.isHeld()) return;
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "qqbot:sandbox");
            lock.setReferenceCounted(false);
            lock.acquire();
        } catch (Throwable ignored) {
        }
    }

    private void releaseWake() {
        try {
            if (lock != null && lock.isHeld()) lock.release();
        } catch (Throwable ignored) {
        }
        lock = null;
    }

    /** 界面上切换「常驻唤醒」后立刻生效，不用等服务重启。 */
    public static void applyWake(Context c) {
        try {
            Intent i = new Intent(c, QqbotGuardService.class);
            i.setAction("com.termux.app.GUARD_WAKE");
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable ignored) {
        }
    }


    /* ---------- 通知 ---------- */

    public void setNote(String text) {
        lastNote = text;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTI_ID, buildNoti(text));
        } catch (Throwable ignored) {
        }
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "后台守护", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setDescription("保持 QQ 机器人沙盒常驻所需的常驻通知");
            nm.createNotificationChannel(ch);
        } catch (Throwable ignored) {
        }
    }

    public Notification buildNoti(String text) {
        ensureChannel();
        Intent i = new Intent(this, QqbotActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CHANNEL_ID);
        else b = new Notification.Builder(this);
        b.setContentTitle("AstrBot烛版正在后台守护");
        b.setContentText(text == null || text.length() == 0 ? "沙盒运行中" : text);
        b.setOngoing(true);
        b.setOnlyAlertOnce(true);
        b.setShowWhen(false);
        b.setContentIntent(pi);
        try {
            b.setSmallIcon(getApplicationInfo().icon);
        } catch (Throwable ignored) {
        }
        return b.build();
    }
}
