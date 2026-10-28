package com.termux.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;

import org.json.JSONArray;
import org.json.JSONObject;

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

    /** 前台常驻通知的 id：这条就是前台服务的载体（v1.3.4-beta.9 起不再另发一条状态条）。 */
    public static final int NOTI_ID = 42310;

    /**
     * 状态通知（上线 / 掉线 / 需要重新扫码）走独立渠道 qqbot_status（IMPORTANCE_HIGH）：
     * 常驻通知是 IMPORTANCE_LOW 的静音渠道（它挂着就代表「服务还在」，不该响），
     * 而掉线必须能响一声、能上锁屏、能弹出提醒。两个渠道互不影响：
     * 用户在系统里把状态通知关掉，常驻条照旧存在。
     */
    public static final String CH_STATUS = "qqbot_status";
    public static final int STATUS_ID = 42320;

    /**
     * 前台常驻渠道（v1.3.4-beta.9）：一条 IMPORTANCE_LOW 的常驻通知，既是
     * startForeground 的载体，也是状态条（标题=当前状态、正文=该干什么、右侧=已运行时长）。
     *
     * 为什么不用旧的 qqbot_guard（IMPORTANCE_MIN）：
     *   ①MIN 渠道上的常驻通知在 HyperOS / ColorOS 上会被压进「静默折叠」，下拉看不到进度，
     *     用户以为服务没在跑；LOW 无声、无震动、不上浮，但会正常显示；
     *   ②渠道重要度一旦被用户改过就不能用代码调高，只能换新渠道 id（见 dropLegacyChannels）。
     * 也不再复用 qqbot_status：那条是「上线 / 掉线 / 扫码」的事件提醒（要响），
     * 混在一起会出现「被划掉就再也收不到掉线提醒」。
     */
    public static final String CH_RUN = "qqbot_run";
    /** beta.9 之前的两个渠道：MIN 的常驻卡 + 灵动岛用的 LIVE。服务启动时顺手删掉，别让用户通知设置里留死渠道。 */
    private static final String CH_OLD_GUARD = "qqbot_guard";
    private static final String CH_OLD_LIVE = "qqbot_live";

    /** 通知栏「重启」：PendingIntent.getService 打回本服务（协议里也写作 com.termux.qqbot.ACTION_RESTART）。 */
    public static final String ACTION_RESTART = "com.termux.app.GUARD_RESTART";
    public static final String ACTION_RESTART_LEGACY = "com.termux.qqbot.ACTION_RESTART";
    public static final String ACTION_WAKE = "com.termux.app.GUARD_WAKE";
    /** 「重新挂上前台服务」：界面上一键把常驻通知 + 前台身份重新钉回去（v1.3.4-beta.9）。 */
    public static final String ACTION_REPAIR = "com.termux.app.GUARD_REPAIR";
    /** 「下线」（通知栏 / 小组件 / 磁贴都走它）：等价于 emergencyStop，但要自己写 offline 标记。 */
    public static final String ACTION_STOP = "com.termux.app.GUARD_STOP";
    /** 通知栏「关闭进程」：停沙盒 + 收掉守护服务自己，一键把后台关干净。 */
    public static final String ACTION_KILL = "com.termux.app.GUARD_KILL";
    /** 桌面小组件「启动/停止」：PendingIntent.getService 打回本服务（协议文档里的旧名字也认）。 */
    public static final String ACTION_WIDGET_TOGGLE = "com.termux.app.WIDGET_TOGGLE";
    public static final String ACTION_WIDGET_TOGGLE_LEGACY = "com.termux.qqbot.WIDGET_TOGGLE";
    /**
     * 【后台启动入口】不经过界面，直接在服务里把沙盒拉起来（v1.3.3-beta.6）。
     *
     * 为什么必须有一条「只走服务」的启动路径：通知栏 Action / 桌面小组件 / 快捷磁贴
     * 原来的做法都是「把面板叫到前台，由页面读一次状态再决定要不要启动」。这条路
     * 在后台根本不成立——targetSdk 28 下服务自己 startActivity 会被 BAL
     * （Background activity launch blocked）拦掉；就算勉强显示出来，用户按 Home
     * 之后页面也立刻不可见，启动流程跟着一起断。用户的原话是「后台启动要正常启动，
     * 所有运行和前台运行一样，不管你用什么方式」，所以启动这一步必须在服务里做完，
     * 界面只作为可选的附加显示。
     */
    public static final String ACTION_START = "com.termux.app.GUARD_START";
    public static final String ACTION_START_LEGACY = "com.termux.qqbot.ACTION_START";

    /** 供界面查询：守护是否在跑、最近一次动作。 */
    public static volatile boolean guardRunning = false;
    public static volatile String lastNote = "";

    private PowerManager.WakeLock lock;
    private QqbotGuardTask task;
    private Thread thread;
    /** 上线/掉线/扫码 的检查线程（见 StatusWatch），跟服务同生共死。 */
    private StatusWatch statusWatch;
    private Thread statusThread;

    /* 通知节流：同一条文案 30 秒内不重复 notify()，换文案立刻发（协议：30 秒或状态变化时更新）。 */
    private String lastNotiText = "";
    private long lastNotiMs = 0L;

    /*
     * 常驻通知的状态键（状态码 + 进度档位）：只有状态真的变了才 notify()。
     * 45 秒一轮、每轮都 notify 的话，系统会把这个渠道判成「频繁更新」并静音，
     * 国内 ROM 还会直接折叠成一条灰条——用户最需要看到的那条反而最不显眼。
     */
    private static volatile String lastRunKey = "";

    /**
     * 前台服务这次启动的时刻（ms）：界面拿它算「已运行多久」，0 = 服务没在跑。
     * 放在服务里而不是写盘，是因为它天然属于「这一次进程的生命周期」。
     */
    public static volatile long fgsSince = 0L;

    /*
     * 启动进度（Android 16 实时更新用）：boot 从 0 推到 92，run 直接 100。
     * bootStart 是本进程第一次见到 "boot" 的时刻，不写盘——重启之后从 0 再来一遍，
     * 比接着上次的 80% 更符合「这是这次启动」的直觉。<=0 表示没在启动。
     */
    private static volatile long bootStartMs = 0L;

    /** 进度心跳（只在启动期间自转，见 Pump）。主线程 Handler，服务一停就自然停。 */
    private static final android.os.Handler PUMP =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private static volatile boolean pumping = false;

    /* ---------- 静态入口 ---------- */

    /** 返回是否真的把启动请求发出去了：API 31+ 的后台启动限制会在这里抛异常。 */
    public static boolean start(Context c) {
        try {
            Intent i = new Intent(c, QqbotGuardService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
            return true;
        } catch (Throwable t) {
            // API 31+ 在后台起前台服务会抛 ForegroundServiceStartNotAllowedException。
            // 原来这里静默返回 false，Activity / apply 拿不到任何线索，永远补不了日志。
            android.util.Log.e("QqbotGuard", "startForegroundService failed: " + t, t);
            return false;
        }
    }

    /**
     * 后台启动沙盒（不需要界面、不需要 BAL 豁免）。
     * 返回「启动请求是否真的发出去了」，与 start() 同口径。
     */
    public static boolean startSandboxInBackground(Context c) {
        try {
            Intent i = new Intent(c, QqbotGuardService.class);
            i.setAction(ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
            return true;
        } catch (Throwable t) {
            android.util.Log.e("QqbotGuard", "startSandboxInBackground failed: " + t, t);
            return false;
        }
    }

    /**
     * 后台启动沙盒的 PendingIntent（通知栏 / 小组件 / 磁贴共用）。
     * 用 getService 而不是 getActivity：系统代发服务启动不受 BAL 限制，
     * 用户按完按钮就能锁屏走人，沙盒照样起得来。
     */
    public static PendingIntent startPi(Context c) {
        Intent i = new Intent(c, QqbotGuardService.class);
        i.setAction(ACTION_START);
        return PendingIntent.getService(c, 16, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
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

    /**
     * 按当前开关状态决定起还是停，启动/保存设置后都调它。
     * 签名保持 void（调用点太多），但启动请求失败必须留下 Log.w：
     * start() 吞掉 API 31+ 的后台启动异常后，Activity 的 try/catch 根本抓不到，
     * 只剩这条日志能解释「守护明明开了却没在跑」。
     */
    public static void apply(Context c) {
        if (!applyEx(c)) {
            android.util.Log.w("QqbotGuard", "apply: 守护服务启动请求未发出，后台守护不会运行"
                    + "（API 31+ 后台启动限制 / 电池优化？）");
        }
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
        // 用户按过通知栏的「关闭进程」（或紧急下线）会留下 state/offline 标记：
        // 这时开机广播、覆盖安装、打开 App 都不该把守护悄悄拉回来（v1.2.11）。
        try {
            if (StateIO.file(c.getFilesDir(), "offline").isFile()) {
                android.util.Log.i("QqbotGuard", "state/offline exists: user closed it, skip apply");
                return true;
            }
        } catch (Throwable t) {
            android.util.Log.e("QqbotGuard", "read offline flag failed", t);
        }
        return start(c);
    }

    /**
     * 这次启动是不是「该保持关闭」的：offline（紧急下线 / 关闭进程）或 guard.off
     * （用户关掉了常驻守护）任一存在。专供「系统把服务粘性重启回来」时判断用。
     */
    private boolean keepDead() {
        try {
            if (offFlag(this).isFile()) return true;
        } catch (Throwable ignored) {
        }
        try {
            return StateIO.file(getFilesDir(), "offline").isFile();
        } catch (Throwable t) {
            return false;
        }
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
        // 服务可能先于 Activity 起来（开机自启 / 通知栏动作），别名也得在这建一份
        try {
            PrefixAlias.ensure(this);
        } catch (Throwable ignored) {
        }
        guardRunning = true;
        fgsSince = System.currentTimeMillis();
        // 状态先按「启动中」占位：常驻通知的标题/正文直接读 QqbotRunState，
        // 这样第一条通知一出来就是「AstrBot 启动中 · 沙盒正在启动」，而不是一句空话。
        try { QqbotRunState.note("boot", 0L); } catch (Throwable ignored) {}
        // 前台通知必须最先挂上：API 26+ 的 startForegroundService 只给 5 秒宽限，
        // 中间插任何读盘/等锁都可能超时（RemoteServiceException）。
        try {
            startForeground(NOTI_ID, buildNoti(null));
        } catch (Throwable ignored) {
        }
        // v1.3.3-beta.6：粘性重启也要认「用户已经关掉了」。
        // 改成 START_STICKY 之后（见 onStartCommand 的说明），进程被系统收掉时系统会
        // 把服务重新拉起并给一个 null Intent；如果用户在此之前按过通知栏「关闭进程」
        // 或界面上的「紧急下线」，这次重启就必须立刻退出，否则「关不干净」的老毛病会
        // 原样复现。判断口径与 apply()/开机广播完全一致：offline 或 guard.off 任一存在。
        if (keepDead()) {
            android.util.Log.i("QqbotGuard", "粘性重启但用户已关闭（offline/guard.off），立即收掉服务");
            stopQuiet();
            return;
        }
        // 排上 2 分钟一次的保活心跳：服务被系统收掉、或整组被缓存冻结时，
        // 由广播把进程重新叫醒并把沙盒兜回来（KeepAlive 里有完整解释）。
        // arm() 里同时会登记一条 JobScheduler 周期任务（KeepAlive.scheduleJob），
        // 闹钟被 ROM 掐掉/精确闹钟退化成不准时，还有系统级的那条命兜底。
        try { KeepAlive.arm(this); } catch (Throwable ignored) {}
        // 1.3.4-beta.7：每日早报的闹钟在重启、清数据后会全丢，服务起来就重排一次。
        // reschedule 内部自带开关与异常吞掉，开关关着时不会批到任何东西。
        try { QqbotDailyReport.reschedule(this); } catch (Throwable ignored) {}
        // 息屏模式：监听 SCREEN_ON / SCREEN_OFF（清单里静态注册收不到这两个广播，
        // 只能跟着服务动态注册）。服务在 → 监听就在；服务停 → onDestroy 里注销。
        try { ScreenMode.start(this); } catch (Throwable ignored) {}
        acquireWake();
        // 两个子系统都挂在服务上：服务在它们才有意义，服务一停立刻注销，不留泄漏的监听。
        try { NetworkWatch.start(this); } catch (Throwable ignored) {}
        try { BatteryMon.start(this); } catch (Throwable ignored) {}
        task = new QqbotGuardTask(this);
        thread = new Thread(task, "qqbot-guard");
        thread.setDaemon(true);
        thread.start();
        // 状态检查是另一条线程：自检循环遇到风控 / 部署中 / 回退会提前 return，
        // 那些时候端口状态反而最需要盯（见 StatusWatch 的注释）。
        try {
            statusWatch = new StatusWatch(getApplicationContext());
            statusThread = new Thread(statusWatch, "qqbot-status");
            statusThread.setDaemon(true);
            statusThread.start();
        } catch (Throwable ignored) {
        }
        // 启动进度条：startForeground 那条已经带上了「启动中 + 进度」，这里把心跳挂起来，
        // 用户点完「启动服务」就能看着百分比往前走，而不是干等 45 秒一轮的状态检查。
        try { postRunNoti(this, "boot", 0L); } catch (Throwable ignored) {}
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
            /* 通知栏「关闭进程」：用户的诉求是「别管我了」，所以不再弹二次确认。
               GuardKillTask 会先写 offline 标记（心跳/网络自愈/watchdog 见到它都不再
               拉起），停沙盒、收起本服务，最后 killProcess 把整个应用进程结束掉。
               5 秒兜底见 KillWatchdog：stop-sandbox.sh 卡住也不会让按钮看起来没反应。 */
            try {
                Thread th = new Thread(new GuardKillTask(this), "qqbot-kill");
                th.setDaemon(true);
                th.start();
            } catch (Throwable ignored) {
            }
            return START_NOT_STICKY;
        } else if (ACTION_START.equals(a) || ACTION_START_LEGACY.equals(a)) {
            /* 【v1.3.3-beta.6 后台启动】不经过界面，直接在服务里把沙盒拉起来。
               通知栏 / 小组件 / 磁贴 / 任何第三方入口都可以用 startPi() 或
               startSandboxInBackground() 打到这里，App 完全在后台（甚至锁屏）也能启动。 */
            startSandboxThread();
        } else if (ACTION_WIDGET_TOGGLE.equals(a) || ACTION_WIDGET_TOGGLE_LEGACY.equals(a)) {
            /* 小组件/磁贴的启停按钮。
               v1.3.3-beta.6 之前在「在跑 → 停 / 没跑 → 启」之间只做一件事：把面板叫到前台
               并带 toggle=1，由页面读一次真实状态再决定。那条路在后台是断的——targetSdk 28
               下服务 startActivity 会被 BAL 拦掉，用户点完按钮什么都没发生。
               现在按「需不需要二次确认」拆开：
                 · 沙盒没在跑 → 直接起（启动不需要确认，后台也能做）；
                 · 沙盒在跑   → 停是危险操作，仍然请页面弹「紧急下线」确认（页面起不来
                   就什么都不做，绝不静默停止）。
               判据与 QqbotTileService / 小组件渲染同一份：supervisor.pid 是不是活着的 proot。 */
            boolean running = QqbotShell.pidAlive(new File(QqbotShell.qqbotDir(getFilesDir()), "supervisor.pid"), "proot");
            if (running) {
                askToggle(getApplicationContext());
            } else {
                startSandboxThread();
            }
            QqbotWidgetProvider.refreshAsync(this);
        }
        /* v1.3.3-beta.6：这里由 START_NOT_STICKY 改成 START_STICKY。
           旧注释担心的是「进程被杀后系统把服务重新拉起来，刚被『关闭进程』关掉的软件
           过几秒就自己回来了」。那个担心本身没错，但用 START_NOT_STICKY 堵是堵错了地方：
           它同时也让「系统/ROM 因为内存、缓存冻结、厂商省电策略收掉进程」这种最常见的
           后台掉线永远不会自愈——沙盒里的 proot 是 App 的子进程，进程一没，Android 12+
           的 phantom process killer 会把整棵沙盒子进程一起清掉，表现就是「放着不动就死」，
           而用户说的正是「后台运行要和前台一模一样」。
           现在改成：既让系统把服务拉回来（START_STICKY），又在 onCreate 里用 keepDead()
           认出「用户真的关掉了」并立刻 stopQuiet()——两条语义都保住了，而且
           「关闭进程」那条路径还有三重保险：GuardKillTask 先写 offline 标记、再
           stopSelf()、最后 killProcess；被 kill 之前服务已经自己停了，粘性机制根本
           不会触发；万一触发，onCreate 的 keepDead() 又会立刻收掉它。 */
        if (ACTION_REPAIR.equals(a)) {
            // 「重新挂上前台服务」：把常驻通知重新 startForeground 一次。
            // 服务还在跑时重复 startForeground 是合法的（系统只换通知）；服务已被杀干净时，
            // startService 会重新拉起 onCreate，走的是同一条路，正好把它整个救回来。
            try {
                ensureChannel();
                startForeground(NOTI_ID, buildNoti(null));
            } catch (Throwable ignored) {
            }
            fgsSince = System.currentTimeMillis();
            try { lastRunKey = ""; } catch (Throwable ignored) {}
            try { postRunNoti(this, QqbotRunState.currentCode(), QqbotRunState.currentUpSince()); } catch (Throwable ignored) {}
            return START_STICKY;
        }
        return START_STICKY;
    }

    /** 起一个后台线程真正把沙盒拉起来（ACTION_START / 小组件启动按钮共用）。 */
    private void startSandboxThread() {
        try {
            Thread th = new Thread(new GuardStart(this), "qqbot-start");
            th.setDaemon(true);
            th.start();
        } catch (Throwable t) {
            android.util.Log.e("QqbotGuard", "起启动线程失败：" + t, t);
        }
    }

    /**
     * 从最近任务划掉（onTaskRemoved）。
     *
     * 为什么需要：划掉之后 Activity 没了，但服务本身不会被系统停（stopWithTask=false）。
     * 可很多 ROM 会把「整个任务组」一起清掉，进程一走，沙盒的子进程就会被 Android 12+
     * 的 phantom process killer 连带清掉。这里做三件事，保证「划掉 ≠ 停摆」：
     * ① 重排心跳闹钟（这一刻起算，避免划掉时刚好错过一次投递）；
     * ② 再 start 一次服务自己（进程若被 stopWithTask 类策略收掉，能立刻回来）；
     * ③ 丢一个后台线程做一次沙盒体检（KeepAlive.rescue：进程/端口两路判活 + 自愈）。
     */
    public void onTaskRemoved(Intent rootIntent) {
        android.util.Log.i("QqbotGuard", "任务被移出最近任务：重排心跳 + 立刻体检，后台不停摆");
        try { KeepAlive.arm(this); } catch (Throwable ignored) {}
        try { start(getApplicationContext()); } catch (Throwable ignored) {}
        try {
            Thread th = new Thread(new KeepAliveTask(getApplicationContext()), "qqbot-taskremoved");
            th.setDaemon(true);
            th.start();
        } catch (Throwable ignored) {}
        super.onTaskRemoved(rootIntent);
    }

    public void onDestroy() {
        guardRunning = false;
        if (task != null) task.stop();
        if (statusWatch != null) statusWatch.stop();
        try { NetworkWatch.stop(); } catch (Throwable ignored) {}
        try { BatteryMon.stop(this); } catch (Throwable ignored) {}
        try { ScreenMode.stop(this); } catch (Throwable ignored) {}
        releaseWake();
        // 前台服务停了就必须把常驻卡片收掉，否则通知栏会留一条点不开的僵尸通知。
        try { stopForeground(true); } catch (Throwable ignored) {}
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTI_ID);
        } catch (Throwable ignored) {}
        // 服务没了，界面上的「前台服务」状态也得跟着变；留着 fgsSince 会让卡片一直显示「运行中」。
        try { lastRunKey = ""; } catch (Throwable ignored) {}
        fgsSince = 0L;
        // 进度心跳跟着服务一起停；bootStart 清零，下次启动重新从 0 计。
        pumping = false;
        bootStartMs = 0L;
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
        // 这条路是「关进程 / 紧急下线」的收尾，走的是后台线程：cancel / stopSelf
        // 都可以在任意线程调，不用额外切线程。
        try { lastRunKey = ""; } catch (Throwable ignored) {}
        fgsSince = 0L;
        // 进度心跳跟着服务一起停；bootStart 清零，下次启动重新从 0 计。
        pumping = false;
        bootStartMs = 0L;
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
        ensureRunChannel(this);
    }

    /**
     * 常驻通知（前台服务的载体）。标题跟状态走、正文说该干什么、右侧运行计时器；
     * 具体构件全在 buildRunNoti 里，这里只负责把「当前状态」取出来填进去。
     *
     * 参数 text 非空 = 临时文案（自检过程里那些「正在检查沙盒…」之类），空 = 走状态文案。
     */
    public Notification buildNoti(String text) {
        return buildRunNoti(this, QqbotRunState.currentCode(), QqbotRunState.currentUpSince(), text);
    }

    /* ================= 上线 / 掉线 / 需要重新扫码（v1.3.4-beta.2） ================= */

    /**
     * 这三种提醒共用 qqbot_status 渠道和同一个通知 id：
     * 同一个 id 的好处是「掉线了」被「已上线」直接替换，通知栏不会堆一串过期状态；
     * 扫码提醒也是状态的一种，扫码成功之后自然被「已上线」顶掉。
     */
    private static final String PREF_NOTIFY = "qqbot_notify";

    /** 一次跃迁检查的间隔（秒）。比守护自检的 60 秒快一点，掉线能在一分半内报出来。 */
    private static final int STATUS_PERIOD = 45;

    /** 掉线要连续这么久才算数：proot 重启一般 30~60 秒，抖动期间别连发假警报。 */
    private static final long OFFLINE_DWELL_MS = 120000L;

    /** 状态快照：$HOME/.qqbot/state/notify.json，记上一次的结论与掉线起点。 */
    private static final String NOTIFY_STATE = "notify.json";

    /** 三个 kind 共用一个检查锁：服务线程和桥上的 test() 可能同时进来。 */
    private static final Object STATUS_LOCK = new Object();

    /**
     * 读开关：master 关掉就全部不发，各 kind 再各有一票；默认全开。
     * prefs 读失败按「开」处理——宁可多提醒一次，也别因为一个读异常的偶发问题
     * 把「掉线了」这种关键提醒静音掉。
     */
    public static boolean statusOn(Context c, String kind) {
        try {
            SharedPreferences sp = c.getSharedPreferences(PREF_NOTIFY, Context.MODE_PRIVATE);
            if (!sp.getBoolean("notify.master", true)) return false;
            return sp.getBoolean("notify." + kind, true);
        } catch (Throwable t) {
            return true;
        }
    }

    /** 建状态渠道（幂等）。IMPORTANCE_HIGH = 有声音、能悬浮、能上锁屏。 */
    private static void ensureStatusChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (nm.getNotificationChannel(CH_STATUS) != null) return;
            NotificationChannel ch = new NotificationChannel(CH_STATUS, "机器人状态", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("机器人上线、掉线、需要重新扫码时提醒");
            ch.setShowBadge(true);
            ch.enableVibration(true);
            // 锁屏上也要能看见正文：掉线是一条「现在就得处理」的通知
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 三选一的通知内容。全部用平台 API（Notification.Builder），minSdk 24 可用，
     * 不引入 androidx。
     */
    public static Notification buildStatusNoti(Context c, String kind) {
        String title;
        String text;
        if ("online".equals(kind)) {
            title = "机器人已上线";
            text = "QQ 和 AstrBot 都连上了，消息通道已恢复";
        } else if ("qr".equals(kind)) {
            title = "需要重新扫码";
            text = "QQ 登录状态失效了，点开应用扫一下码就能恢复";
        } else {
            title = "机器人掉线了";
            text = "和 QQ 的连接断了，正在自动恢复；一直不回来就点开看看";
        }
        // 点通知就进主界面：和常驻卡片同一个落地页（tab=bot），用户不用再自己找
        Intent main = new Intent(c, QqbotActivity.class);
        main.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        main.putExtra("tab", "bot");
        PendingIntent pi = PendingIntent.getActivity(c, 31, main, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(c, CH_STATUS);
        else b = new Notification.Builder(c);
        b.setContentTitle(title);
        b.setContentText(text);
        b.setAutoCancel(true);
        b.setOnlyAlertOnce(false);
        b.setShowWhen(true);
        b.setWhen(System.currentTimeMillis());
        b.setContentIntent(pi);
        if (Build.VERSION.SDK_INT < 26) {
            // 24/25 没有渠道，声音与震动只能在 Builder 上给
            b.setPriority(Notification.PRIORITY_HIGH);
            b.setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE);
        }
        // 小图标必须是纯白剪影：彩色启动图标在 8+ 上会被渲染成色块（同常驻卡片）
        try {
            int res = c.getResources().getIdentifier("ic_stat_qqbot", "drawable", c.getPackageName());
            b.setSmallIcon(res != 0 ? res : c.getApplicationInfo().icon);
        } catch (Throwable t) {
            try {
                b.setSmallIcon(android.R.drawable.stat_notify_error);
            } catch (Throwable ignored) {
            }
        }
        return b.build();
    }

    /**
     * 发一条状态通知。force=true 时忽略开关与渠道重要度，给界面上「试一下」用。
     * 渠道被用户在系统里关掉（IMPORTANCE_NONE）时直接返回：notify() 本身不抛，
     * 但白白走一遍构建没有意义；这里也顺手把「渠道被关」这种正常情况挡在异常之外。
     */
    public static void postStatus(Context c, String kind, boolean force) {
        if (c == null) return;
        try {
            if (!force && !statusOn(c, kind)) return;
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            ensureStatusChannel(c);
            if (!force && Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = nm.getNotificationChannel(CH_STATUS);
                if (ch != null && ch.getImportance() == NotificationManager.IMPORTANCE_NONE) return;
            }
            nm.notify(STATUS_ID, buildStatusNoti(c, kind));
        } catch (Throwable ignored) {
        }
    }

    /* ================= 前台常驻通知（v1.3.4-beta.9） ================= */

    /**
     * 建前台常驻渠道（幂等）。
     * IMPORTANCE_LOW = 无声、无震动、不上浮，但会正常显示在通知栏里：
     * 这条通知就是「服务还活着」的凭据，压到 MIN 会被国产 ROM 折叠成灰条，反而看不见。
     */
    private static void ensureRunChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            dropLegacyChannels(nm);
            if (nm.getNotificationChannel(CH_RUN) != null) return;
            NotificationChannel ch = new NotificationChannel(CH_RUN, "前台运行", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("让机器人在后台保持前台运行；这条通知就是服务的载体");
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 删掉 beta.9 之前的两个渠道。渠道一旦建过就改不了重要度（用户设置优先），
     * 留着旧 id 只会让「通知设置」里多出两条永远不来消息的死渠道。
     */
    private static void dropLegacyChannels(NotificationManager nm) {
        if (Build.VERSION.SDK_INT < 26 || nm == null) return;
        try {
            if (nm.getNotificationChannel(CH_OLD_GUARD) != null) nm.deleteNotificationChannel(CH_OLD_GUARD);
        } catch (Throwable ignored) {
        }
        try {
            if (nm.getNotificationChannel(CH_OLD_LIVE) != null) nm.deleteNotificationChannel(CH_OLD_LIVE);
        } catch (Throwable ignored) {
        }
    }

    /** 渠道还在不在、有没有被用户关掉：界面「通知渠道」那一行读的就是它。 */
    public static boolean runChannelOk(Context c) {
        try {
            if (Build.VERSION.SDK_INT < 26) return true;
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            NotificationChannel ch = nm.getNotificationChannel(CH_RUN);
            return ch != null && ch.getImportance() != NotificationManager.IMPORTANCE_NONE;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 电池优化白名单。不在白名单里，Doze 会把后台网络掐掉，QQ 长连接就断——
     * 这不是「可能」而是必然，所以状态要能被界面读到（Android.stats 的 battOk）。
     * 注意：这里只查询，不弹任何系统弹窗；引导由界面上的文案完成。
     */
    public static boolean batteryOk(Context c) {
        try {
            if (Build.VERSION.SDK_INT < 23) return true;
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return false;
            return pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    /* ================= 启动进度 ================= */

    /**
     * 启动进度（0~100）。纯函数：只吃「已经启动多久」，能脱离 Android 单独跑回归
     * （out/b139_fgs_check.py 会把这段源码抠出来编到普通 JVM 上跑）。
     *
     * 为什么是 60 秒线性 + 封顶 92：
     *   ①冷启动实测 40~60 秒，按 60 秒铺满，进度条才不会一秒跳完；
     *   ②永远不自己走到 100 —— 真正的 100 只由「端口真的通了」那次状态跃迁给，
     *     否则用户会盯着一条满格却还在转的通知，反而更焦虑。
     */
    static int bootPctOf(long elapsedMs) {
        if (elapsedMs <= 0L) return 0;
        if (elapsedMs >= 60000L) return 92;
        int p = (int) (elapsedMs * 92L / 60000L);
        return p < 0 ? 0 : p;
    }

    /**
     * 当前该显示的进度：启动中按启动起点算；运行中给 100；
     * 待扫码 / 离线返回 -1 = 这条通知根本不该有进度条。
     */
    static int pctOf(String code, long now) {
        if ("run".equals(code)) return 100;
        if (!"boot".equals(code)) return -1;
        long start = bootStartMs;
        if (start <= 0L) return 0;
        return bootPctOf(now - start);
    }

    /**
     * 启动期间的进度心跳：只在「启动中」自转，每 2 秒重发一次通知。
     *
     * 为什么不复用 StatusWatch：那条线程一轮要做两次端口探测 + 读盘（约 1 秒），
     * 拉到 2 秒一轮等于一直在探测；进度条只需要时间，不需要探测。
     * 这里每 2 秒一次也只会在「进度涨了 4% 以上」时真的发（见 postRunNoti 的状态键），
     * 实际约 2.6 秒一次，不会触发系统的「频繁更新」限流。
     */
    private static final class Pump implements Runnable {
        private final Context ctx;

        Pump(Context c) {
            ctx = c;
        }

        public void run() {
            try {
                if (!guardRunning) {
                    pumping = false;
                    return;
                }
                String code = QqbotRunState.currentCode();
                if (!"boot".equals(code)) {
                    pumping = false;
                    return;
                }
                // 十分钟还没起来就别再刷了：真有病的时候，一条 92% 的通知也已经说明问题，
                // 每 2 秒 notify 一次只会白耗电（StatusWatch 仍然每 45 秒照常盯状态）。
                if (bootStartMs > 0L && System.currentTimeMillis() - bootStartMs > 600000L) {
                    pumping = false;
                    return;
                }
                postRunNoti(ctx, code, QqbotRunState.currentUpSince());
                PUMP.postDelayed(this, 2000L);
            } catch (Throwable t) {
                pumping = false;
            }
        }
    }

    /** 开一次进度心跳（幂等）。bootStart 也在这里打点：第一次见到 boot 才算起点。 */
    private static void startPump(Context c) {
        if (c == null) return;
        if (bootStartMs <= 0L) bootStartMs = System.currentTimeMillis();
        if (pumping) return;
        pumping = true;
        try {
            PUMP.postDelayed(new Pump(c.getApplicationContext()), 2000L);
        } catch (Throwable t) {
            pumping = false;
        }
    }

    /* ================= 常驻通知 ================= */

    /**
     * 常驻通知本体：这条就是前台服务的载体，也是应用唯一一条「服务状态」条。
     *
     * 标题跟状态走（AstrBot 运行中 / 启动中 / 待扫码 / 离线），正文说明现在该干什么；
     * 运行中右侧挂系统计时器（setUsesChronometer，系统自己每秒走字，我们不必周期性 notify），
     * 启动中挂确定进度条（0→92%）。两种都不需要额外线程。
     */
    public static Notification buildRunNoti(Context c, String code, long upSince, String text) {
        ensureRunChannel(c);
        Intent main = new Intent(c, QqbotActivity.class);
        main.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 32, main, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(c, CH_RUN);
        else b = new Notification.Builder(c);
        String hint = (text != null && text.length() > 0) ? text : QqbotRunState.hintOf(code);
        b.setContentTitle(QqbotRunState.notiTitle(code));
        b.setContentText(hint);
        b.setContentIntent(pi);
        // 常驻三件套：划不掉、改内容不响、时间戳位置留给运行计时器。
        b.setOngoing(true);
        b.setOnlyAlertOnce(true);
        b.setShowWhen(false);
        // 前台服务语义标签：CATEGORY_SERVICE 让系统把它归进「正在运行的服务」；
        // 锁屏可见是因为这条通知就是用户判断「机器人还在不在」的唯一凭据。
        b.setCategory(Notification.CATEGORY_SERVICE);
        b.setVisibility(Notification.VISIBILITY_PUBLIC);
        int pct = pctOf(code, System.currentTimeMillis());
        if ("run".equals(code) && upSince > 0L) {
            b.setWhen(upSince);
            b.setShowWhen(true);
            b.setUsesChronometer(true);
            b.setSubText("已运行 " + QqbotRunState.durText(System.currentTimeMillis() - upSince));
        } else if ("boot".equals(code)) {
            // 确定进度条。不用 setProgress(0,0,true) 那根来回走的不确定细线（用户嫌它难看）。
            b.setProgress(100, pct < 0 ? 0 : pct, false);
            b.setSubText(pct + "%");
        }
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW);
        // API 31+：前台服务通知默认会被系统压后十秒再显示（它以为你在批量发），
        // FOREGROUND_SERVICE_IMMEDIATE 要求立刻显示。本包用 android-34 编译，常量直接可用。
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
            } catch (Throwable ignored) {
            }
        }
        // 通知栏小图标必须是纯白剪影，不能用启动图标：启动图标是彩色 / 自适应图标，
        // Android 8+ 会把它渲染成一团色块或直接裁掉，部分 ROM 上还会因为
        // adaptive-icon 不是合法的 notification small icon 而整条通知不显示。
        // 清单里已经带了专门画的 res/drawable/ic_stat_qqbot.xml，优先用它。
        int icon;
        try {
            int res = c.getResources().getIdentifier("ic_stat_qqbot", "drawable", c.getPackageName());
            icon = (res != 0) ? res : c.getApplicationInfo().icon;
        } catch (Throwable t) {
            icon = android.R.drawable.stat_notify_error;
        }
        try {
            b.setSmallIcon(icon);
        } catch (Throwable ignored) {
        }
        // Android 的标准通知模板只渲染前三个动作（第 4 个会被直接丢掉，没有溢出菜单），
        // 所以按优先级只留三个：重启 / 扫码 / 关闭进程。「看日志」在应用内『日志』页，点正文即到。
        try {
            Intent r = new Intent(c, QqbotGuardService.class);
            r.setAction(ACTION_RESTART);
            PendingIntent pr = PendingIntent.getService(c, 11, r, PendingIntent.FLAG_IMMUTABLE);
            b.addAction(icon, "重启", pr);
        } catch (Throwable ignored) {
        }
        try {
            Intent s = new Intent(c, QqbotActivity.class);
            s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            s.putExtra("tab", "bot");
            PendingIntent ps = PendingIntent.getActivity(c, 13, s, PendingIntent.FLAG_IMMUTABLE);
            b.addAction(icon, "扫码", ps);
        } catch (Throwable ignored) {
        }
        try {
            Intent k = new Intent(c, QqbotGuardService.class);
            k.setAction(ACTION_KILL);
            PendingIntent pk = PendingIntent.getService(c, 14, k, PendingIntent.FLAG_IMMUTABLE);
            b.addAction(icon, "关闭进程", pk);
        } catch (Throwable ignored) {
        }
        return b.build();
    }

    /**
     * 刷新常驻通知。三种提前返回都是刻意的：
     *   ①状态没变、通知也还在 → 不重发（45 秒一轮无脑 notify 会被系统判成频繁更新）；
     *   ②渠道被用户关掉（IMPORTANCE_NONE）→ 不发、也不更新状态键，用户重新打开后能自然恢复；
     *   ③通知不在通知栏里 → 让服务自己重新 startForeground 把前台身份钉回去。
     * 第③条就是「保活」的关键：国产 ROM 清掉通知后，服务会掉出前台、几秒内被杀，
     * 这一步把它自己拽回来，用户不用手动重启。
     */
    static void postRunNoti(Context c, String code, long upSince) {
        if (c == null) return;
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            int pct = pctOf(code, System.currentTimeMillis());
            // 状态键带进度档位：启动中每涨 4% 算一次「变了」，45 秒一轮的老节流才不会把进度条冻住。
            String key = (code == null ? "" : code) + (pct < 0 ? "" : "#" + (pct / 4));
            boolean here = stillPosted(nm);
            if (key.equals(lastRunKey) && here) return;
            ensureRunChannel(c);
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = nm.getNotificationChannel(CH_RUN);
                if (ch != null && ch.getImportance() == NotificationManager.IMPORTANCE_NONE) return;
            }
            if (here) {
                nm.notify(NOTI_ID, buildRunNoti(c, code, upSince, null));
            } else {
                // 通知被清掉了（或通道被关过又开）：让服务自己重新 startForeground。
                Intent rp = new Intent(c, QqbotGuardService.class);
                rp.setAction(ACTION_REPAIR);
                try {
                    c.startService(rp);
                } catch (Throwable ignored) {
                }
            }
            lastRunKey = key;
            // 启动中把进度心跳挂上；一旦状态跃迁出去，心跳自己会停（见 Pump.run）。
            if ("boot".equals(code)) startPump(c);
            else if (bootStartMs > 0L) bootStartMs = 0L;
        } catch (Throwable ignored) {
        }
    }

    /** 那条常驻通知还在通知栏里吗？API 23 以下没有查询接口，只能信状态键。 */
    private static boolean stillPosted(NotificationManager nm) {
        try {
            if (Build.VERSION.SDK_INT < 23) return true;
            android.service.notification.StatusBarNotification[] arr = nm.getActiveNotifications();
            if (arr == null) return false;
            for (int i = 0; i < arr.length; i++) {
                if (arr[i] != null && arr[i].getId() == NOTI_ID) return true;
            }
            return false;
        } catch (Throwable t) {
            // 查不到就当成「还在」：宁可漏补一次，也别变成每 45 秒 notify 一次
            return true;
        }
    }

    /** 常驻通知此刻在不在通知栏里（界面「常驻通知」那一行读的就是它）。 */
    public static boolean runNotiPosted(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            return stillPosted(nm);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 界面上的「重新挂上前台服务」：一条 startService 打回本服务，
     * 由 onStartCommand 的 ACTION_REPAIR 分支重新 startForeground。
     *
     * 为什么不在界面里直接 startForeground：那是 Service 的实例方法，界面拿不到实例；
     * 而且真正的修复动作应该由服务自己完成——服务若已被系统杀干净，startService 会
     * 重新拉起 onCreate，同一条路正好把它整个救回来。
     */
    public static String repair(Context c) {
        if (c == null) return "拿不到上下文，重挂失败";
        try {
            Intent i = new Intent(c, QqbotGuardService.class);
            i.setAction(ACTION_REPAIR);
            c.startService(i);
            return "已重新挂上前台服务";
        } catch (Throwable t) {
            return "重挂失败：" + t.getClass().getSimpleName();
        }
    }

    /**
     * 算运行状态、落盘、然后把常驻通知刷一遍。
     * 界面（Android.stats）与通知栏读的是同一份结论，免得出现「界面说运行中、通知栏说离线」。
     * 落盘字段：state（状态码）、qrFlag（待扫码粘性位）、upSince（本次上线起点）、battery。
     */
    private static String runRefresh(Context c, JSONObject out, JSONObject prev,
            boolean up, boolean booting, boolean qrEvent, long now) {
        boolean prevQr = prev != null && prev.optBoolean("qrFlag", false);
        long prevUp = (prev == null) ? 0L : prev.optLong("upSince", 0L);
        boolean qrFlag = QqbotRunState.qrKeep(prevQr, up, qrEvent);
        long upSince = QqbotRunState.upSinceOf(prevUp, up, now);
        String code = QqbotRunState.stateOf(up, booting, qrFlag);
        try {
            out.put("state", code);
            out.put("qrFlag", qrFlag);
            out.put("upSince", upSince);
            out.put("battery", batteryOk(c));
        } catch (Throwable ignored) {
        }
        // 两处要同步：内存里的状态（stats() 读它）、通知栏那条常驻通知
        try { QqbotRunState.note(code, upSince); } catch (Throwable ignored) {}
        postRunNoti(c, code, upSince);
        return code;
    }

    /**
     * 一次跃迁检查。必须跑在后台线程（要连 socket、要读盘），由 StatusWatch 每 45 秒调一次。
     *
     * 判定口径：
     *   上线 = NapCat 端口通 且 AstrBot 端口通（NapCat 挂着但 AstrBot 没起来，一样是离线）；
     *   掉线 = 连续 OFFLINE_DWELL_MS 都不满足「上线」，且不处于「用户主动下线 / 部署中」；
     *   扫码 = cache/qrcode.png 的 mtime 变了（NapCat 每次出新码都会重写这个文件）。
     * 只在跃迁的那一次发通知：结论记在 notify.json 里，重启服务也接着上次的结论算，
     * 不会因为服务重启就把同一件事再报一遍。
     */
    public static void statusTick(Context c) {
        if (c == null) return;
        synchronized (STATUS_LOCK) {
            try {
                statusTickLocked(c);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void statusTickLocked(Context c) {
        File files = c.getFilesDir();
        File qq = QqbotShell.qqbotDir(files);
        JSONObject root = readBotsRoot(qq);
        boolean napUp = QqbotShell.portOpen(napcatPortOf(root), 300);
        boolean astUp = QqbotShell.portOpen(astrbotPortOf(root), 300);
        boolean up = napUp && astUp;
        long qrMt = qrMtime(files, root);
        // 「重启中」的两种情形：用户按了紧急下线 / 沙盒还没部署完。
        // 这时候端口本来就该是关的，报掉线只会打扰用户，所以只静默记状态。
        // booting 单独留一份：灵动岛要区分「启动中」和「离线」，两者对用户是两种心情。
        boolean booting = !QqbotShell.bootstrapReady(files);
        boolean quiet = StateIO.file(files, "offline").isFile() || booting;

        JSONObject prev = null;
        String raw = StateIO.read(files, NOTIFY_STATE);
        if (raw != null) {
            try {
                prev = new JSONObject(raw);
            } catch (Throwable ignored) {
            }
        }

        JSONObject out = new JSONObject();
        try {
            out.put("nap", napUp);
            out.put("ast", astUp);
            out.put("qr", qrMt);
        } catch (Throwable ignored) {
        }

        if (prev == null) {
            // 服务刚起来：这一刻的端口状态只能当基线，不能当「跃迁」。
            // 否则每次开机、每次服务被系统拉起来都会先报一遍掉线。
            putSt(out, up ? "up" : "down", 0L);
            // 灵动岛也要打基线：少了这一步，第一次启动后胶囊会一直停在「启动中」，
            // 直到下一次跃迁（最长 45 秒后）才更新。
            runRefresh(c, out, null, up, booting, qrMt > 0, System.currentTimeMillis());
            StateIO.write(files, NOTIFY_STATE, out.toString());
            return;
        }

        String pst = prev.optString("st", "down");
        long since = prev.optLong("since", 0L);
        long prevQr = prev.optLong("qr", 0L);
        long now = System.currentTimeMillis();

        // 跃迁判定全在 decide() 里：它是纯函数，能脱离 Android 单独跑（见 out/b134b2_native_check.py）
        String[] d = decide(pst, since, up, quiet, now, prevQr, qrMt);
        // beta.4：用户在扫码弹窗里选了「不再提示（本次运行）」，这一轮就不再提扫码。
        // 过滤放在 decide() 外面，状态机本身一行不动（它另有 13 条回归）。
        // 只砍 "qr" 这一项：同一轮里可能还叠着掉线（"offline,qr"），掉线必须照发。
        String kinds = filterKinds(d[2], QqbotSession.qrSkipped());
        if (kinds.length() > 0) {
            int from = 0;
            while (from < kinds.length()) {
                int at = kinds.indexOf(',', from);
                if (at < 0) at = kinds.length();
                postStatus(c, kinds.substring(from, at), false);
                from = at + 1;
            }
        }
        putSt(out, d[0], Long.parseLong(d[1]));

        // 灵动岛：状态码在同一轮里算出来，45 秒才落一次盘，不额外起线程、不额外读日志。
        // qrEvent = 这一轮新出了二维码（mtime 变了），交给 qrKeep 决定要不要粘住。
        runRefresh(c, out, prev, up, booting, qrMt > 0 && qrMt != prevQr, now);

        StateIO.write(files, NOTIFY_STATE, out.toString());
    }

    /**
     * 纯决策函数：给定上一轮结论和本轮探测结果，算这轮该发什么、新的状态是什么。
     * 返回值 { 新状态, 新起点(ms), 要发的通知(空串=不发, 多个用逗号连) }。
     *
     * 抽成独立函数是因为整块逻辑里只有它有分支，而且不碰 Android / 磁盘 / socket，
     * 可以原样抠出来在普通 JVM 上跑回归（tests 见 out/b134b2_native_check.py）。
     */
    static String[] decide(String pst, long since, boolean up, boolean quiet, long now, long prevQr, long qrMt) {
        String st;
        String kind = "";
        if (up) {
            // 真正的「掉线 → 上线」才发；抖动期间 pst 一直是 up，不会多报
            if (!"up".equals(pst) && !quiet) kind = "online";
            st = "up";
            since = 0L;
        } else if ("up".equals(pst)) {
            if (since <= 0L) {
                // 刚掉下来：先起表，不马上报。KeepAlive / 自检把沙盒拉回来时端口会重新亮，
                // 这一格里就不会留下一条假警报。起表时间必须落盘，否则下一轮还是 since<=0，
                // 表永远起不来，掉线通知也就永远不会发。
                st = "up";
                since = now;
            } else if (now - since >= OFFLINE_DWELL_MS) {
                if (!quiet) kind = "offline";
                st = "down";
                since = 0L;
            } else {
                st = "up";
            }
        } else {
            st = "down";
            since = 0L;
        }
        // 二维码：NapCat 每次生成新码都会重写 qrcode.png，mtime 变了就说明
        // 「该去扫码」或者「上一次的码已经过期被换新」，各提醒一次。
        // 它和上线/掉线可以同一轮同时命中（刚掉线就出新码），所以允许叠加。
        if (qrMt > 0 && qrMt != prevQr && !quiet) {
            kind = kind.length() == 0 ? "qr" : (kind + ",qr");
        }
        return new String[] { st, String.valueOf(since), kind };
    }

    /**
     * 按会话开关过滤本轮要发的通知类型（beta.4）。
     *
     * "qr" → ""（不发）；"offline,qr" → "offline"；"qr,offline" → "offline"；
     * 其余（online / offline / ""）原样。纯函数、不碰 Android，单测见 out/b4_notify_check.py。
     *
     * 为什么不在 decide() 里判：decide() 是状态机（13 条回归钉着），而「本次运行不再提示扫码」
     * 是纯粹的投递策略——同一个状态该不该发，取决于用户刚点过什么，不该污染状态机。
     */
    static String filterKinds(String kinds, boolean qrSkip) {
        if (kinds == null || kinds.length() == 0) return "";
        if (!qrSkip) return kinds;
        StringBuilder b = new StringBuilder(kinds.length());
        int from = 0;
        while (from <= kinds.length()) {
            int at = kinds.indexOf(',', from);
            if (at < 0) at = kinds.length();
            String one = kinds.substring(from, at).trim();
            if (one.length() > 0 && !"qr".equals(one)) {
                if (b.length() > 0) b.append(',');
                b.append(one);
            }
            from = at + 1;
        }
        return b.toString();
    }

    private static void putSt(JSONObject o, String st, long since) {
        try {
            o.put("st", st);
            o.put("since", since);
            o.put("ts", System.currentTimeMillis());
        } catch (Throwable ignored) {
        }
    }

    /** 读 bots.json：先 home 后 cache，与界面同一口径；读不到返回 null（按默认端口探）。 */
    private static JSONObject readBotsRoot(File qq) {
        String s = StateIO.read(new File(qq, "bots.json"));
        if (s == null) s = StateIO.read(new File(new File(qq, "cache"), "bots.json"));
        if (s == null) return null;
        try {
            return new JSONObject(s);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 第一个实例的 napcatPort（非法/缺失回落 6099）。 */
    private static int napcatPortOf(JSONObject root) {
        try {
            if (root != null) {
                JSONArray arr = root.optJSONArray("bots");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject b = arr.optJSONObject(i);
                        if (b == null) continue;
                        int p = b.optInt("napcatPort", 0);
                        if (p > 0 && p < 65536) return p;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return 6099;
    }

    /** AstrBot 控制台端口：顶层写的就用它，0/非法回落 6185（与 astrbotPort() 一致）。 */
    private static int astrbotPortOf(JSONObject root) {
        try {
            if (root != null) {
                int p = root.optInt("astrbotPort", 0);
                if (p > 0 && p < 65536) return p;
            }
        } catch (Throwable ignored) {
        }
        return 6185;
    }

    /**
     * 二维码文件的最近修改时间（0 = 没有）。
     * 路径和界面的 qrFind() 一致：主实例在 $HOME/opt/QQ/...，多实例在
     * $HOME/opt/qqbot-inst/<id>/QQ/...；两处都可能存在，取最新的那个。
     */
    private static long qrMtime(File files, JSONObject root) {
        long best = 0L;
        try {
            File ub = new File(QqbotShell.homeDir(files), "ubuntu");
            best = newer(best, new File(ub, "opt/QQ/resources/app/app_launcher/napcat/cache/qrcode.png"));
            if (root != null) {
                JSONArray arr = root.optJSONArray("bots");
                if (arr != null) {
                    for (int i = 0; i < arr.length() && i < 8; i++) {
                        JSONObject b = arr.optJSONObject(i);
                        if (b == null) continue;
                        String id = safeId(b.optString("id", ""));
                        if (id.length() == 0) continue;
                        best = newer(best, new File(ub, "opt/qqbot-inst/" + id
                                + "/QQ/resources/app/app_launcher/napcat/cache/qrcode.png"));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return best;
    }

    private static long newer(long cur, File f) {
        try {
            if (f.isFile() && f.length() > 0) {
                long m = f.lastModified();
                return m > cur ? m : cur;
            }
        } catch (Throwable ignored) {
        }
        return cur;
    }

    /** 实例 id 白名单：只留字母数字和 _-，挡住 bots.json 里混进 ../ 之类的路径。 */
    private static String safeId(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < 32; i++) {
            char ch = s.charAt(i);
            if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '_' || ch == '-') {
                b.append(ch);
            }
        }
        return b.toString();
    }

    /**
     * 状态检查线程：服务一开就跟着跑，20 秒后首次取样，之后每 45 秒一轮。
     *
     * 为什么单独一条线程，而不是塞进 QqbotGuardTask 的每轮收尾：自检循环遇到
     * 「紧急下线 / 风控暂停 / 部署中 / 回退等待」会提前 return，而这些时候恰恰
     * 最容易需要「掉线了」这条提醒；两种关心的事情不同，命也就别绑在一起。
     */
    public static final class StatusWatch implements Runnable {
        private final Context ctx;
        private volatile boolean run = true;

        StatusWatch(Context c) {
            ctx = c;
        }

        public void stop() {
            run = false;
        }

        public void run() {
            // 服务常被开机广播拉起，这时沙盒往往还在起，先等二十秒再取样
            sleep(20);
            while (run) {
                statusTick(ctx);
                sleep(STATUS_PERIOD);
            }
        }

        private void sleep(int sec) {
            for (int i = 0; i < sec && run; i++) {
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }
}
