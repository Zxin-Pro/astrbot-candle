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
 * v1.1.30 起它同时是三个「自救」子系统的宿主：网络切换自愈（NetworkWatch）、
 * 电量/温度降载（BatteryMon）、风控暂停（RiskReader），并对外提供常驻通知卡片
 * （重启 / 看日志 / 扫码三个 Action）。这些子系统都跟着服务的 onCreate/onDestroy
 * 注册与注销，服务没了它们也没有意义。
 * d8 处理匿名内部类会崩，本文件不出现匿名类。
 */
public class QqbotGuardService extends Service {

    public static final String CHANNEL_ID = "qqbot_guard";
    public static final int NOTI_ID = 42310;

    /** 通知栏「重启」：PendingIntent.getService 打回本服务（协议里也写作 com.termux.qqbot.ACTION_RESTART）。 */
    public static final String ACTION_RESTART = "com.termux.app.GUARD_RESTART";
    public static final String ACTION_RESTART_LEGACY = "com.termux.qqbot.ACTION_RESTART";
    public static final String ACTION_WAKE = "com.termux.app.GUARD_WAKE";
    /** 通知栏「下线」（折叠菜单里的第 4 个）：等价于 emergencyStop，但要自己写 offline 标记。 */
    public static final String ACTION_STOP = "com.termux.app.GUARD_STOP";
    /** 通知栏「关闭进程」：停沙盒 + 收掉守护服务自己，一键把后台关干净。 */
    public static final String ACTION_KILL = "com.termux.app.GUARD_KILL";
    /** 桌面小组件「启动/停止」：PendingIntent.getService 打回本服务（协议文档里的旧名字也认）。 */
    public static final String ACTION_WIDGET_TOGGLE = "com.termux.app.WIDGET_TOGGLE";
    public static final String ACTION_WIDGET_TOGGLE_LEGACY = "com.termux.qqbot.WIDGET_TOGGLE";

    /** 供界面查询：守护是否在跑、最近一次动作。 */
    public static volatile boolean guardRunning = false;
    public static volatile String lastNote = "";

    private PowerManager.WakeLock lock;
    private QqbotGuardTask task;
    private Thread thread;

    /* 通知节流：同一条文案 30 秒内不重复 notify()，换文案立刻发（协议：30 秒或状态变化时更新）。 */
    private String lastNotiText = "";
    private long lastNotiMs = 0L;

    /* ---------- 静态入口 ---------- */

    /** 返回是否真的把启动请求发出去了：API 31+ 的后台启动限制会在这里抛异常。 */
    public static boolean start(Context c) {
        try {
            Intent i = new Intent(c, QqbotGuardService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
            return true;
        } catch (Throwable t) {
            return false;
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
        applyEx(c);
    }

    /**
     * 与 apply 相同，但返回「启动请求是否发出去了」。开机广播用它判断要不要补试一次：
     * API 31+ 在后台起前台服务会抛 ForegroundServiceStartNotAllowedException，
     * start() 把异常吞了的话调用方永远不知道失败，补试就永远不会发生。
     */
    public static boolean applyEx(Context c) {
        if (offFlag(c).isFile()) {
            stop(c);
            return true; // 用户自己关了守护，不算失败，不必重试
        }
        return start(c);
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
        sendAction(c, ACTION_WAKE);
        return on ? "已开启息屏保持运行" : "已关闭息屏保持运行";
    }

    /**
     * 立刻让守护循环跑一次重启（紧急下线后恢复、通知栏「重启」都用它）。
     * 守护关着时什么都不做——否则用户关掉的守护会被自己偷偷打开。
     */
    public static void kick(Context c) {
        if (!enabled(c)) return;
        sendAction(c, ACTION_RESTART);
    }

    private static void sendAction(Context c, String action) {
        try {
            Intent i = new Intent(c, QqbotGuardService.class);
            i.setAction(action);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 把面板拉到前台弹「紧急下线」确认（功能 9：通知栏 / 小组件 / 磁贴三处入口都要二次确认）。
     * 用 ACTION_VIEW + confirm_stop 这个 extra，singleTask 的页面收到 onNewIntent 就会弹框。
     */
    public static void askStopConfirm(Context c) {
        try {
            Intent i = new Intent(c, QqbotActivity.class);
            i.setAction(Intent.ACTION_VIEW);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            i.putExtra("confirm_stop", true);
            c.startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    /** 实例方法版：服务内部调用，省一次 getApplicationContext()。 */
    private void askStopConfirm() {
        askStopConfirm(getApplicationContext());
    }

    /**
     * 把面板拉到前台处理「启停切换」（小组件 / 磁贴的启动·停止按钮，功能 12 / 13）。
     * 入口不判状态，页面读一次真实状态再决定弹确认还是直接启动，见 AskToggle。
     */
    public static void askToggle(Context c) {
        try {
            Intent i = new Intent(c, QqbotActivity.class);
            i.setAction(Intent.ACTION_VIEW);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            i.putExtra("toggle", true);
            c.startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    /** 磁贴用：带 toggle=1 的 PendingIntent（startActivityAndCollapse 的 API 34+ 重载）。 */
    public static PendingIntent togglePi(Context c) {
        Intent i = new Intent(c, QqbotActivity.class);
        i.setAction(Intent.ACTION_VIEW);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra("toggle", true);
        return PendingIntent.getActivity(c, 15, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * 弹「紧急下线」确认的 PendingIntent（功能 9，四处入口共用）。
     * 为什么不用服务里 startActivity：targetSdk 28 在后台起 Activity 会被系统
     * BAL（Background activity launch blocked）拦掉，logcat 里就是
     * 「Background activity launch blocked! … targetSdk: 28」。改成通知 Action /
     * 磁贴 / 小组件直接持有这个 PendingIntent，由系统代发，就不受 BAL 限制。
     */
    public static PendingIntent stopConfirmPi(Context c) {
        Intent i = new Intent(c, QqbotActivity.class);
        i.setAction(Intent.ACTION_VIEW);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra("confirm_stop", true);
        return PendingIntent.getActivity(c, 14, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
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
            // 这个方法是从 WebView 的 JS 桥线程调进来的，而 startActivity 必须在主线程发，
            // 否则是未定义行为，异常还会被下面的 catch 吞成「这台设备打不开电池优化设置」。
            // 少数 ROM 没有这个系统页面，BatteryTask 会退回电池优化总列表。
            a.runOnUiThread(new BatteryTask(a, pkg));
            return "已打开申请页面，请点「允许」";
        } catch (Throwable t) {
            return "申请失败：" + t;
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
        // 两个子系统都挂在服务上：服务在它们才有意义，服务一停立刻注销，不留泄漏的监听。
        try { NetworkWatch.start(this); } catch (Throwable ignored) {}
        try { BatteryMon.start(this); } catch (Throwable ignored) {}
        task = new QqbotGuardTask(this);
        thread = new Thread(task, "qqbot-guard");
        thread.setDaemon(true);
        thread.start();
    }

    public int onStartCommand(Intent i, int flags, int startId) {
        String a = (i == null || i.getAction() == null) ? "" : i.getAction();
        if (ACTION_WAKE.equals(a)) {
            // 界面上切换「常驻唤醒」时带着 ACTION 再叫一次服务，就地生效。
            if (wakeEnabled(this)) acquireWake(); else releaseWake();
        } else if (ACTION_RESTART.equals(a) || ACTION_RESTART_LEGACY.equals(a)) {
            // 通知栏「重启」：停+起要等 proot 自己退，必须丢后台线程
            try {
                Thread th = new Thread(new GuardRestart(this), "qqbot-restart");
                th.setDaemon(true);
                th.start();
            } catch (Throwable ignored) {
            }
        } else if (ACTION_STOP.equals(a)) {
            /* 通知栏「下线」：v1.1.30 功能 9 要求三处入口二次确认，所以这里不再直接停服，
               改成把面板拉到前台弹「紧急下线」确认；真正的 stop-sandbox.sh 在用户点确认后
               由页面的 Android.emergencyStop() 执行。 */
            askStopConfirm();
        } else if (ACTION_KILL.equals(a)) {
            /* 通知栏「关闭进程」：用户的诉求是「别管我了」，所以不再弹二次确认，
               直接停沙盒并把本服务收掉。EmergencyStop 会先写 offline 标记，
               心跳/网络自愈/watchdog 见到它都不会再拉起。 */
            try {
                Thread th = new Thread(new GuardKillTask(this), "qqbot-kill");
                th.setDaemon(true);
                th.start();
            } catch (Throwable ignored) {
            }
            return START_NOT_STICKY;
        } else if (ACTION_WIDGET_TOGGLE.equals(a) || ACTION_WIDGET_TOGGLE_LEGACY.equals(a)) {
            /* 老版本小组件/磁贴的 PendingIntent 还会打到这里（新版本一律直接拉起面板并带
               toggle=1）。服务这边不判状态、也不自己 startActivity —— 服务在后台起
               Activity 会被 targetSdk 28 的 BAL 拦掉。只把意图转交给页面，由页面读一次
               真实状态决定「弹下线确认」还是「启动沙盒」。 */
            askToggle(getApplicationContext());
            QqbotWidgetProvider.refreshAsync(this);
            return START_STICKY;
        }
        // 被系统回收后自动重来；就算重来时沙盒好好的，任务循环也只是一直空转。
        return START_STICKY;
    }

    public void onDestroy() {
        guardRunning = false;
        if (task != null) task.stop();
        try { NetworkWatch.stop(); } catch (Throwable ignored) {}
        try { BatteryMon.stop(this); } catch (Throwable ignored) {}
        releaseWake();
        // 前台服务停了就必须把常驻卡片收掉，否则通知栏会留一条点不开的僵尸通知。
        try { stopForeground(true); } catch (Throwable ignored) {}
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTI_ID);
        } catch (Throwable ignored) {}
        // ui.json 里的 serviceAlive 要落成 false，但 onDestroy 在主线程，写盘丢后台线程。
        try {
            Thread th = new Thread(new StateFlush(this, UiState.online(), UiState.total(), false), "qqbot-uiflush");
            th.setDaemon(true);
            th.start();
        } catch (Throwable ignored) {}
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

    /** 彻底收掉：撤通知 + 停前台 + stopSelf。给 GuardKillTask 用（后台线程调用安全）。 */
    void stopQuiet() {
        guardRunning = false;
        try { stopForeground(true); } catch (Throwable ignored) {}
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTI_ID);
        } catch (Throwable ignored) {}
        try { stopSelf(); } catch (Throwable ignored) {}
    }

    /** 界面上切换「常驻唤醒」后立刻生效，不用等服务重启。 */
    public static void applyWake(Context c) {
        sendAction(c, ACTION_WAKE);
    }

    /* ---------- 通知 ---------- */

    /**
     * 更新常驻通知正文。节流：文案没变且距上次 notify 不到 30 秒就跳过，
     * 但 lastNote 永远更新（界面读的是 lastNote，不是通知）。
     */
    public void setNote(String text) {
        lastNote = text;
        try {
            long now = System.currentTimeMillis();
            String t = (text == null) ? "" : text;
            boolean changed = !t.equals(lastNotiText);
            if (!changed && (now - lastNotiMs) < 30000L) return;
            lastNotiText = t;
            lastNotiMs = now;
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTI_ID, buildNoti(t));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 强制刷新一次常驻通知（不受 30 秒节流限制）。
     * 在线实例数变了的时候要立刻看到，否则标题会滞后整整一个自检周期。
     */
    public void refreshNoti() {
        try {
            lastNotiText = lastNote == null ? "" : lastNote;
            lastNotiMs = System.currentTimeMillis();
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTI_ID, buildNoti(lastNotiText));
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

    /**
     * 常驻卡片：标题带在线实例数（state/ui.json 的 online，读不到用「?」），
     * 正文是当前自检结论，右侧三个 Action：重启 / 扫码 / 关闭进程。
     * 全部用平台 API（Notification.Builder.addAction），minSdk 24 可用，不引入 androidx。
     */
    public Notification buildNoti(String text) {
        ensureChannel();
        Intent main = new Intent(this, QqbotActivity.class);
        main.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, main, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CHANNEL_ID);
        else b = new Notification.Builder(this);
        int online = UiState.online();
        String n = (online < 0) ? "?" : String.valueOf(online);
        b.setContentTitle("烛之控制台 · " + n + " 个实例在线");
        b.setContentText(text == null || text.length() == 0 ? "NapCat ✓ AstrBot ✓" : text);
        b.setOngoing(true);
        b.setOnlyAlertOnce(true);
        b.setShowWhen(false);
        b.setContentIntent(pi);
        // 通知栏小图标必须是纯白剪影，不能用启动图标：启动图标是彩色 / 自适应图标，
        // Android 8+ 会把它渲染成一团色块或直接裁掉，部分 ROM 上还会因为
        // adaptive-icon 不是合法的 notification small icon 而整条通知不显示。
        // 清单里已经带了专门画的 res/drawable/ic_stat_qqbot.xml，优先用它。
        int icon;
        try {
            int res = getResources().getIdentifier("ic_stat_qqbot", "drawable", getPackageName());
            icon = (res != 0) ? res : getApplicationInfo().icon;
        } catch (Throwable t) {
            icon = android.R.drawable.stat_notify_sync;
        }
        try {
            b.setSmallIcon(icon);
        } catch (Throwable ignored) {
        }
        // Action 1 重启：打回本服务（服务已在跑，startService 一定成功）
        try {
            Intent r = new Intent(this, QqbotGuardService.class);
            r.setAction(ACTION_RESTART);
            PendingIntent pr = PendingIntent.getService(this, 11, r, PendingIntent.FLAG_IMMUTABLE);
            b.addAction(icon, "重启", pr);
        } catch (Throwable ignored) {
        }
        // Action 2 扫码：带 tab extra 唤起 Activity（singleTask，会走 onNewIntent）
        // 「看日志」不进通知栏：Android 的标准通知模板只渲染前三个动作（第 4 个会被直接丢掉），
        // 而这个模板没有溢出菜单，所以必须按优先级只留三个。日志在应用内「日志」页，点通知正文即到。
        try {
            Intent s = new Intent(this, QqbotActivity.class);
            s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            s.putExtra("tab", "bot");
            PendingIntent ps = PendingIntent.getActivity(this, 13, s, PendingIntent.FLAG_IMMUTABLE);
            b.addAction(icon, "扫码", ps);
        } catch (Throwable ignored) {
        }
        // Action 3「关闭进程」：Android 只渲染前三个动作，第 4 个会被静默丢弃（v1.2.0 实测），
        // 所以要占住第三个位置；这是低频且危险的操作，放最右边。
        // 打回本服务（服务在跑，getService 一定能送达），由 GuardKillTask 停沙盒后自我收尾。
        try {
            Intent k = new Intent(this, QqbotGuardService.class);
            k.setAction(ACTION_KILL);
            PendingIntent pk = PendingIntent.getService(this, 14, k, PendingIntent.FLAG_IMMUTABLE);
            b.addAction(icon, "关闭进程", pk);
        } catch (Throwable ignored) {
        }
        return b.build();
    }
}
