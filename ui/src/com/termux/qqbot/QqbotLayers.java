package com.termux.app;

import android.content.Context;
import android.os.StatFs;

import java.io.File;
import java.io.FileOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 分层看门狗：沙盒 / NapCat / AstrBot 三层探针 + 分层自动重启。
 *
 * 探针每 15 秒一轮：
 *   L1 沙盒    —— $HOME/.qqbot/supervisor.pid 是否活 + cache/heartbeat 是否新鲜（>90s 视为卡死）
 *   L2 NapCat  —— TCP 127.0.0.1:6199（bots.json 里的 onebotPort）
 *   L3 AstrBot —— TCP 127.0.0.1:6185（bots.json 里的 astrbotPort）；失败再看沙盒心跳与进程
 *
 * 自动重启只动挂掉的那一层：
 *   - napcat / astrbot：走 qq_proot 里的脚本命令，杀掉组件进程组，run-all.sh 三秒内会自己拉起；
 *   - sandbox：走 QqbotGuardService 的老路（stop-sandbox.sh --no-offline + start-sandbox.sh）。
 * 同一层 90 秒内只重启一次，每层连续失败 3 次就停手，等人工处理。
 *
 * 低电 / 高温降载时暂停自动重启（手动重启不受影响），开关落在 state/protect.json。
 */
public final class QqbotLayers {

    public static final int DEFAULT_LOW_PCT = 15;
    public static final int DEFAULT_HOT_C = 42;

    private static final int PERIOD_SEC = 15;
    private static final long COOLDOWN_MS = 90L * 1000L;
    private static final int MAX_AUTO = 3;
    private static final long HEARTBEAT_STALE_SEC = 90L;
    private static final long BOOT_GRACE_MS = 60L * 1000L;
    private static final long PROBE_CACHE_MS = 5000L;
    private static final long SPACE_WARN_MB = 512L;
    private static final long SPACE_ALERT_GAP_MS = 30L * 60L * 1000L;

    private static final Object LOCK = new Object();
    private static final Object RESTART_LOCK = new Object();

    private static final String SANDBOX = "sandbox";
    private static final String NAPCAT = "napcat";
    private static final String ASTRBOT = "astrbot";

    private static final Layer SANDBOX_LAYER = new Layer(SANDBOX, "沙盒", 0);
    private static final Layer NAPCAT_LAYER = new Layer(NAPCAT, "NapCat", 6199);
    private static final Layer ASTRBOT_LAYER = new Layer(ASTRBOT, "AstrBot", 6185);
    private static final Layer[] LAYERS = new Layer[] { SANDBOX_LAYER, NAPCAT_LAYER, ASTRBOT_LAYER };

    private static volatile boolean running = false;
    private static volatile Context appCtx;
    private static volatile Thread worker;
    private static volatile boolean paused = false;
    private static volatile String pauseWhy = "";
    private static volatile long startedAt = 0;
    private static volatile long lastProbe = 0;
    private static volatile long tickNo = 0;
    private static volatile String lastDegrade = "";
    private static volatile long lastSpaceAt = 0;
    private static volatile long lastSpaceAlert = 0;
    private static volatile Persist persist;
    private static volatile File persistDir;
    private static volatile Protect cfgCache;
    private static volatile File cfgDir;
    private static volatile long cfgAt = 0;
    private static volatile String pidNeedle = "";
    private static volatile long pidValue = 0;
    private static volatile long pidAt = 0;

    private QqbotLayers() {
    }

    // ---------------------------------------------------------------- 生命周期

    public static void start(Context c) {
        if (c == null) {
            return;
        }
        Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        synchronized (LOCK) {
            if (running) {
                return;
            }
            appCtx = app;
            running = true;
            startedAt = System.currentTimeMillis();
            paused = false;
            pauseWhy = "";
            lastProbe = 0;
            tickNo = 0;
            lastDegrade = "";
            lastSpaceAt = 0;
            lastSpaceAlert = 0;
            for (int i = 0; i < LAYERS.length; i++) {
                Layer L = LAYERS[i];
                L.state = "unknown";
                L.prevState = "unknown";
                L.hint = "还没探测";
                L.ms = -1;
                L.autoTries = 0;
                L.capNotified = false;
                L.downNotified = false;
                L.pendingDown = false;
                L.lastAutoAt = 0;
                L.lastAlertAt = 0;
            }
            Thread t = new Thread(new Worker(), "qqbot-layers");
            t.setDaemon(true);
            worker = t;
            t.start();
            log(app.getFilesDir(), "看门狗启动，三层探针每 " + PERIOD_SEC + " 秒一轮");
        }
    }

    public static void stop() {
        running = false;
        Thread t = worker;
        if (t != null) {
            try {
                t.interrupt();
            } catch (Throwable ignored) {
            }
        }
    }

    private static final class Worker implements Runnable {
        public void run() {
            try {
                Thread.sleep(2500);
            } catch (InterruptedException e) {
                return;
            }
            while (running) {
                tickNo++;
                try {
                    tick();
                } catch (Throwable t) {
                    log(filesDirOf(), "探针这一轮出错：" + t);
                }
                for (int i = 0; i < PERIOD_SEC && running; i++) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }
    }

    private static File filesDirOf() {
        Context c = appCtx;
        return c == null ? new File(".") : c.getFilesDir();
    }

    private static void tick() {
        Context c = appCtx;
        if (c == null) {
            return;
        }
        File filesDir = c.getFilesDir();
        String why = pauseReason(filesDir);
        paused = why.length() > 0;
        pauseWhy = why;
        synchronized (LOCK) {
            probeAll(filesDir);
            lastProbe = System.currentTimeMillis();
        }
        long now = System.currentTimeMillis();
        boolean grace = now - startedAt < BOOT_GRACE_MS;
        for (int i = 0; i < LAYERS.length; i++) {
            Layer L = LAYERS[i];
            if ("down".equals(L.state)) {
                // 刚开机的前 60 秒先不报警（沙盒往往还在起），但也不能漏掉：
                // 先记一笔 pendingDown，宽限期一过仍然 down 就补一次现场 + 告警。
                if (!"down".equals(L.prevState)) {
                    if (grace) {
                        L.pendingDown = true;
                    } else {
                        onBecameDown(filesDir, L, now);
                        L.pendingDown = false;
                    }
                } else if (L.pendingDown && !grace) {
                    onBecameDown(filesDir, L, now);
                    L.pendingDown = false;
                }
                if (!paused && !grace) {
                    maybeRestart(filesDir, L, now);
                }
            } else if ("ok".equals(L.state)) {
                if ("down".equals(L.prevState)) {
                    log(filesDir, L.name + " 已恢复：" + L.hint);
                }
                L.pendingDown = false;
                L.autoTries = 0;
                L.capNotified = false;
                L.downNotified = false;
            } else {
                L.pendingDown = false;
            }
            L.prevState = L.state;
        }
        checkDegrade(filesDir, now);
        checkSpace(filesDir, now);
        if (tickNo % 4 == 1) {                      // 每分钟一条，别把日志写爆
            log(filesDir, summary() + (paused ? "（自动重启暂停：" + pauseWhy + "）" : ""));
        }
    }

    // ---------------------------------------------------------------- 探针

    private static void probeAll(File filesDir) {
        boolean deployed = QqbotShell.bootstrapReady(filesDir);
        File qq = QqbotShell.qqbotDir(filesDir);
        if (!deployed) {
            set(SANDBOX_LAYER, "unknown", "Termux 组件还没装完", 0, -1);
            set(NAPCAT_LAYER, "unknown", "Termux 组件还没装完", 0, -1);
            set(ASTRBOT_LAYER, "unknown", "Termux 组件还没装完", 0, -1);
            return;
        }
        File hb = new File(qq, "cache/heartbeat");
        long hbAge = ageSec(hb);
        if (new File(qq, "state/offline").isFile()) {
            set(SANDBOX_LAYER, "unknown", "已手动关闭（离线休息中）", 0, -1);
            set(NAPCAT_LAYER, "unknown", "沙盒已关闭，暂时探测不了", 0, -1);
            set(ASTRBOT_LAYER, "unknown", "沙盒已关闭，暂时探测不了", 0, -1);
            return;
        }
        File sup = new File(qq, "supervisor.pid");
        boolean supAlive = QqbotShell.pidAlive(sup, "proot");
        long supAgeMin = pidAgeMin(sup);
        if (!supAlive) {
            set(SANDBOX_LAYER, "down", "沙盒守护进程不在了", 0, -1);
        } else if (hbAge < 0) {
            if (supAgeMin * 60L < 180L) {
                set(SANDBOX_LAYER, "unknown", "沙盒启动中（还没写出心跳）", supAgeMin, -1);
            } else {
                set(SANDBOX_LAYER, "down", "心跳文件不见了", supAgeMin, -1);
            }
        } else if (hbAge > HEARTBEAT_STALE_SEC) {
            set(SANDBOX_LAYER, "down", "沙盒 " + hbAge + " 秒没有心跳（可能卡死）", supAgeMin, -1);
        } else {
            set(SANDBOX_LAYER, "ok", "心跳正常（" + hbAge + " 秒前）", supAgeMin, -1);
        }
        if (!"ok".equals(SANDBOX_LAYER.state)) {
            String hint = "沙盒没在运行，暂时探测不了";
            if ("unknown".equals(SANDBOX_LAYER.state)) {
                hint = "沙盒还没就绪，暂时探测不了";
            }
            set(NAPCAT_LAYER, "unknown", hint, 0, -1);
            set(ASTRBOT_LAYER, "unknown", hint, 0, -1);
            return;
        }
        probeNapcat(qq, hbAge);
        probeAstrbot(qq, hbAge);
    }

    private static void probeNapcat(File qq, long hbAge) {
        if (new File(qq, "cache/svc/napcat.off").isFile()) {
            set(NAPCAT_LAYER, "unknown", "已手动关闭（组件开关）", 0, -1);
            return;
        }
        int port = portFromBots(qq, "onebotPort", 6199);
        NAPCAT_LAYER.port = port;
        int ms = tcpMs(port, 450);
        if (ms >= 0) {
            long pid = findPid("run-napcat.sh");
            set(NAPCAT_LAYER, "ok", "OneBot 端口 " + port + " 正常（" + ms + " ms）", pid > 0 ? pidAgeMin(pid) : 0, ms);
            return;
        }
        long pid = findPid("run-napcat.sh");
        String hint = "OneBot 端口 " + port + " 没响应";
        if (pid > 0) {
            hint += "（进程还在，可能卡死）";
        } else {
            hint += "（进程已经退出）";
        }
        set(NAPCAT_LAYER, "down", hint, pid > 0 ? pidAgeMin(pid) : 0, -1);
    }

    private static void probeAstrbot(File qq, long hbAge) {
        if (new File(qq, "cache/svc/astrbot.off").isFile()) {
            set(ASTRBOT_LAYER, "unknown", "已手动关闭（组件开关）", 0, -1);
            return;
        }
        int port = portFromBots(qq, "astrbotPort", 6185);
        ASTRBOT_LAYER.port = port;
        int ms = tcpMs(port, 450);
        if (ms >= 0) {
            long pid = findPid("/opt/AstrBot");
            set(ASTRBOT_LAYER, "ok", "控制台端口 " + port + " 正常（" + ms + " ms）", pid > 0 ? pidAgeMin(pid) : 0, ms);
            return;
        }
        long pid = findPid("/opt/AstrBot");
        boolean hbOk = hbAge >= 0 && hbAge <= HEARTBEAT_STALE_SEC;
        String hint = "控制台端口 " + port + " 没响应";
        if (pid > 0) {
            hint += "（AstrBot 进程还在，可能卡死）";
        } else {
            hint += "（AstrBot 进程已经退出）";
        }
        hint += hbOk ? "；沙盒心跳正常" : "；沙盒心跳也停了";
        set(ASTRBOT_LAYER, "down", hint, pid > 0 ? pidAgeMin(pid) : 0, -1);
    }

    private static void set(Layer L, String state, String hint, long upMin, int ms) {
        L.state = state;
        L.hint = hint;
        L.upMin = upMin > 0 ? upMin : 0;
        L.ms = ms;
    }

    static int tcpMs(int port, int timeoutMs) {
        if (port <= 0 || port > 65535) {
            return -1;
        }
        Socket s = new Socket();
        try {
            long t0 = System.currentTimeMillis();
            s.connect(new InetSocketAddress("127.0.0.1", port), timeoutMs);
            long d = System.currentTimeMillis() - t0;
            return (int) Math.max(0L, Math.min(d, 9999L));
        } catch (Throwable t) {
            return -1;
        } finally {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    static int portFromBots(File qq, String key, int def) {
        File[] roots = new File[] { qq, new File(qq, "cache") };
        for (int r = 0; r < roots.length; r++) {
            String t = StateIO.read(new File(roots[r], "bots.json"));
            if (t == null) {
                continue;
            }
            int i = t.indexOf("\"" + key + "\"");
            if (i < 0) {
                continue;
            }
            int c = t.indexOf(':', i + key.length() + 2);
            if (c < 0) {
                continue;
            }
            int j = c + 1;
            while (j < t.length() && Character.isWhitespace(t.charAt(j))) {
                j++;
            }
            int start = j;
            while (j < t.length() && Character.isDigit(t.charAt(j))) {
                j++;
            }
            if (j > start) {
                try {
                    int v = Integer.parseInt(t.substring(start, j));
                    if (v > 0 && v <= 65535) {
                        return v;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return def;
    }

    private static long ageSec(File f) {
        try {
            if (f == null || !f.isFile()) {
                return -1;
            }
            long m = f.lastModified();
            if (m <= 0) {
                return -1;
            }
            return Math.max(0L, (System.currentTimeMillis() - m) / 1000L);
        } catch (Throwable t) {
            return -1;
        }
    }

    static long pidAgeMin(File pidFile) {
        try {
            String t = StateIO.read(pidFile);
            if (t == null) {
                return 0;
            }
            String[] parts = t.trim().split("\\s+");
            int pid = Integer.parseInt(parts[0]);
            return pidAgeMin(pid);
        } catch (Throwable t) {
            return 0;
        }
    }

    static long pidAgeMin(long pid) {
        long sec = QqbotCrashScene.procAgeSec((int) pid);
        if (sec <= 0) {
            return 0;
        }
        return sec / 60L;
    }

    /** 在 /proc 里按 cmdline 找进程（只看当前命名空间，命中第一个）。 */
    static long findPid(String needle) {
        long now = System.currentTimeMillis();
        if (needle != null && needle.equals(pidNeedle) && now - pidAt < 6000L) {
            return pidValue;
        }
        long v = scanPid(needle);
        pidNeedle = needle == null ? "" : needle;
        pidValue = v;
        pidAt = now;
        return v;
    }

    private static long scanPid(String needle) {
        if (needle == null || needle.length() == 0) {
            return 0;
        }
        try {
            File proc = new File("/proc");
            File[] ds = proc.listFiles();
            if (ds == null) {
                return 0;
            }
            int self = android.os.Process.myPid();
            for (int i = 0; i < ds.length; i++) {
                String name = ds[i].getName();
                if (!isDigits(name)) {
                    continue;
                }
                int pid;
                try {
                    pid = Integer.parseInt(name);
                } catch (Throwable t) {
                    continue;
                }
                if (pid == self) {
                    continue;
                }
                String cmd = readCmdline(pid);
                if (cmd != null && cmd.indexOf(needle) >= 0) {
                    return pid;
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static boolean isDigits(String s) {
        if (s == null || s.length() == 0) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    private static String readCmdline(int pid) {
        java.io.RandomAccessFile raf = null;
        try {
            File f = new File("/proc/" + pid + "/cmdline");
            if (!f.isFile()) {
                return null;
            }
            raf = new java.io.RandomAccessFile(f, "r");
            int take = (int) Math.min(raf.length(), 4096L);
            if (take <= 0) {
                return null;
            }
            byte[] buf = new byte[take];
            raf.readFully(buf);
            for (int i = 0; i < take; i++) {
                if (buf[i] == 0) {
                    buf[i] = ' ';
                }
            }
            return new String(buf, "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ---------------------------------------------------------------- 掉线处理

    private static void onBecameDown(File filesDir, Layer L, long now) {
        L.downNotified = true;
        L.lastAlertAt = now;
        String title = L.name + " 掉线";
        String why = L.hint;
        String cid = QqbotCrashScene.capture(filesDir, L.id, title, why);
        if (cid.length() > 0) {
            log(filesDir, L.name + " 掉线，现场已存档：" + cid);
        } else {
            log(filesDir, L.name + " 掉线（现场存档失败）：" + why);
        }
        String tail = paused ? "；自动重启当前已暂停（降载或看门狗关闭）" : "，看门狗会尝试自动重启";
        raise("offline", "crit", title, why + tail);
    }

    private static void maybeRestart(File filesDir, Layer L, long now) {
        if (L.autoTries >= MAX_AUTO) {
            if (!L.capNotified) {
                L.capNotified = true;
                raise("offline", "crit", L.name + " 自动重启多次仍失败",
                        "已经连续试了 " + MAX_AUTO + " 次，先停手，请手动看一眼");
                log(filesDir, L.name + " 达到自动重启上限，停止自动重启");
            }
            return;
        }
        if (now - L.lastAutoAt < COOLDOWN_MS) {
            return;
        }
        L.lastAutoAt = now;
        L.autoTries++;
        log(filesDir, "自动重启 " + L.name + "（第 " + L.autoTries + " 次）：" + L.hint);
        StringBuilder out = new StringBuilder();
        boolean ok = restartNow(filesDir, L.id, false, out);
        log(filesDir, "自动重启 " + L.name + (ok ? " 已发出" : " 失败") + "：" + oneLine(out.toString()));
        if (ok) {
            L.restarts++;
            markRestart(filesDir, L.id, "看门狗自动重启");
        }
    }

    // ---------------------------------------------------------------- 重启

    static boolean restartNow(File filesDir, String id, boolean manual, StringBuilder out) {
        synchronized (RESTART_LOCK) {
            try {
                if (SANDBOX.equals(id)) {
                    return restartSandbox(filesDir, manual, out);
                }
                return restartService(filesDir, id, manual, out);
            } catch (Throwable t) {
                out.append("重启出错：").append(t);
                return false;
            }
        }
    }

    private static boolean restartSandbox(File filesDir, boolean manual, StringBuilder out) {
        File qq = QqbotShell.qqbotDir(filesDir);
        if (!manual) {
            if (new File(qq, "state/offline").isFile()) {
                out.append("用户已关闭（offline），跳过");
                return false;
            }
            if (new File(qq, "state/guard.off").isFile()) {
                out.append("守护已暂停（guard.off），跳过");
                return false;
            }
            if (new File(qq, "cache/svc/watchdog.off").isFile()) {
                out.append("看门狗已关闭（watchdog.off），跳过");
                return false;
            }
        } else {
            StateIO.delete(new File(qq, "state/offline"));
            StateIO.delete(new File(qq, "cache/svc/watchdog.off"));
        }
        File stop = new File(QqbotShell.usrDir(filesDir), "opt/qqbot/stop-sandbox.sh");
        if (stop.isFile()) {
            String r = QqbotShell.run(filesDir, "bash '" + stop.getAbsolutePath() + "' --no-offline", 150);
            out.append("stop=").append(oneLine(r)).append(' ');
        }
        boolean started = QqbotShell.startSandbox(filesDir, new StringBuilder(), manual);
        if (!started) {
            out.append("start-sandbox.sh 没起来；");
        }
        try {
            Thread.sleep(3000);
        } catch (InterruptedException ignored) {
        }
        boolean alive = QqbotShell.pidAlive(new File(qq, "supervisor.pid"), "proot");
        try {
            QqbotShell.startWatchdog(filesDir, new StringBuilder());
        } catch (Throwable ignored) {
        }
        out.append(alive ? "supervisor 已在运行" : "supervisor 还没起来");
        return alive;
    }

    private static boolean restartService(File filesDir, String id, boolean manual, StringBuilder out) {
        File qq = QqbotShell.qqbotDir(filesDir);
        if (!QqbotShell.pidAlive(new File(qq, "supervisor.pid"), "proot")) {
            out.append("沙盒没在运行，先不单独重启组件");
            if (manual) {
                return restartSandbox(filesDir, true, out);
            }
            return false;
        }
        File off = new File(new File(qq, "cache/svc"), id + ".off");
        if (off.isFile()) {
            if (!manual) {
                out.append("组件被手动关闭（").append(id).append(".off），不自动重启");
                return false;
            }
            StateIO.delete(off);
            out.append("已清除 ").append(id).append(".off；");
        }
        File helper = new File(qq, "cache/restart-svc.sh");
        if (!ensureHelper(helper)) {
            out.append("重启脚本写不进去");
            return false;
        }
        String inner = "bash /qqbot-cache/restart-svc.sh " + id;
        String cmd = ". \"$PREFIX/opt/qqbot/common.sh\"; qq_proot \"" + inner + "\"";
        String r = QqbotShell.run(filesDir, cmd, 90);
        out.append(oneLine(r));
        if (killedCount(r) <= 0) {
            return false;
        }
        try {
            Thread.sleep(4000);            // run-all.sh 3 秒一轮，等它把进程拉起来
        } catch (InterruptedException ignored) {
        }
        return true;
    }

    /** 从 helper 的 KILLED=n 里取杀掉的进程数；没找到或解析失败返回 -1。 */
    private static int killedCount(String s) {
        if (s == null) {
            return -1;
        }
        int i = s.lastIndexOf("KILLED=");
        if (i < 0) {
            return -1;
        }
        int j = i + 7;
        while (j < s.length() && Character.isWhitespace(s.charAt(j))) {
            j++;
        }
        int start = j;
        while (j < s.length() && Character.isDigit(s.charAt(j))) {
            j++;
        }
        if (j <= start) {
            return -1;
        }
        try {
            return Integer.parseInt(s.substring(start, j));
        } catch (Throwable t) {
            return -1;
        }
    }

    private static boolean ensureHelper(File f) {
        try {
            if (f.isFile() && f.length() > 80L) {
                String old = StateIO.read(f);
                if (old != null && old.indexOf("needles=") >= 0) {
                    return true;
                }
            }
            File d = f.getParentFile();
            if (d != null && !d.isDirectory() && !d.mkdirs()) {
                return false;
            }
            StringBuilder sb = new StringBuilder(1200);
            sb.append("#!/bin/bash\n");
            sb.append("# 看门狗用：杀掉单个组件进程组，run-all.sh 会自己拉起（参数 napcat|astrbot）\n");
            sb.append("id=\"$1\"\n");
            sb.append("case \"$id\" in\n");
            sb.append("  napcat) needles=\"/opt/QQ /opt/qqbot-inst/\" ;;\n");
            sb.append("  astrbot) needles=\"/opt/AstrBot\" ;;\n");
            sb.append("  *) echo KILLED=0; exit 0 ;;\n");
            sb.append("esac\n");
            sb.append("k=0\n");
            sb.append("skip=\" $$\"\n");
            sb.append("p=$$\n");
            sb.append("while [ -n \"$p\" ] && [ \"$p\" != \"1\" ]; do\n");
            sb.append("  skip=\"$skip $p\"\n");
            sb.append("  st=$(cat \"/proc/$p/stat\" 2>/dev/null)\n");
            sb.append("  rest=$(printf '%s' \"$st\" | cut -d')' -f2-)\n");
            sb.append("  set -- $rest\n");
            sb.append("  p=\"$2\"\n");
            sb.append("done\n");
            sb.append("for d in /proc/[0-9]*; do\n");
            sb.append("  pid=$(basename \"$d\")\n");
            sb.append("  case \"$skip \" in *\" $pid \"*) continue ;; esac\n");
            sb.append("  hit=0\n");
            sb.append("  for needle in $needles; do\n");
            sb.append("    grep -a -q -- \"$needle\" \"$d/cmdline\" 2>/dev/null && hit=1\n");
            sb.append("  done\n");
            sb.append("  [ \"$hit\" = 1 ] || continue\n");
            sb.append("  kill -TERM -\"$pid\" 2>/dev/null || kill -TERM \"$pid\" 2>/dev/null\n");
            sb.append("  k=$((k+1))\n");
            sb.append("done\n");
            sb.append("echo KILLED=$k\n");
            FileOutputStream out = null;
            try {
                out = new FileOutputStream(f, false);
                out.write(sb.toString().getBytes("UTF-8"));
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
            return f.isFile();
        } catch (Throwable t) {
            return false;
        }
    }

    /** AndroidLayer.restart 用：立刻返回，后台线程去干。 */
    public static String restartAsync(File filesDir, String id) {
        try {
            if (filesDir == null) {
                return fail("缺少文件目录");
            }
            if (!validId(id)) {
                return fail("不认识的层：" + id);
            }
            Thread t = new Thread(new RestartTask(filesDir, id), "qqbot-layer-restart");
            t.setDaemon(true);
            t.start();
            String name = layerName(id);
            return "{\"ok\":true,\"id\":\"" + id + "\",\"msg\":\"" + QqbotCrashScene.esc("已重启 " + name) + "\"}";
        } catch (Throwable t) {
            return fail("重启失败：" + t);
        }
    }

    private static final class RestartTask implements Runnable {
        private final File filesDir;
        private final String id;

        RestartTask(File filesDir, String id) {
            this.filesDir = filesDir;
            this.id = id;
        }

        public void run() {
            StringBuilder out = new StringBuilder();
            boolean ok = restartNow(filesDir, id, true, out);
            Layer L = layer(id);
            if (ok) {
                if (L != null) {
                    L.restarts++;
                    L.autoTries = 0;
                    L.capNotified = false;
                }
                markRestart(filesDir, id, "手动重启");
                log(filesDir, "手动重启 " + id + " 成功：" + oneLine(out.toString()));
            } else {
                log(filesDir, "手动重启 " + id + " 失败：" + oneLine(out.toString()));
            }
        }
    }

    private static String layerName(String id) {
        Layer L = layer(id);
        return L == null ? id : L.name;
    }

    private static Layer layer(String id) {
        for (int i = 0; i < LAYERS.length; i++) {
            if (LAYERS[i].id.equals(id)) {
                return LAYERS[i];
            }
        }
        return null;
    }

    public static boolean validId(String id) {
        return SANDBOX.equals(id) || NAPCAT.equals(id) || ASTRBOT.equals(id);
    }

    private static void markRestart(File filesDir, String id, String why) {
        Persist p = ensurePersist(filesDir);
        p.lastAt = System.currentTimeMillis();
        p.lastId = id;
        p.lastWhy = why;
        savePersist(filesDir);
    }

    // ---------------------------------------------------------------- 状态 JSON

    public static String statusJson(File filesDir) {
        if (filesDir == null) {
            return fail("缺少文件目录");
        }
        synchronized (LOCK) {
            try {
                long now = System.currentTimeMillis();
                if (now - lastProbe > PROBE_CACHE_MS) {
                    probeAll(filesDir);
                    lastProbe = now;
                }
                Persist p = ensurePersist(filesDir);
                boolean auto = running && pauseReason(filesDir).length() == 0;
                StringBuilder sb = new StringBuilder(1024);
                sb.append("{\"ok\":true,\"auto\":").append(auto ? "true" : "false");
                sb.append(",\"items\":[");
                for (int i = 0; i < LAYERS.length; i++) {
                    Layer L = LAYERS[i];
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append("{\"id\":\"").append(L.id).append('"')
                      .append(",\"name\":\"").append(L.name).append('"')
                      .append(",\"state\":\"").append(L.state).append('"')
                      .append(",\"hint\":\"").append(QqbotCrashScene.esc(L.hint)).append('"')
                      .append(",\"upMin\":").append(L.upMin)
                      .append(",\"restarts\":").append(L.restarts);
                    if (L.port > 0) {
                        sb.append(",\"port\":").append(L.port);
                    }
                    if (L.ms >= 0) {
                        sb.append(",\"ms\":").append(L.ms);
                    }
                    sb.append('}');
                }
                sb.append("],\"lastRestart\":{\"at\":").append(p.lastAt)
                  .append(",\"id\":\"").append(QqbotCrashScene.esc(p.lastId)).append('"')
                  .append(",\"why\":\"").append(QqbotCrashScene.esc(p.lastWhy)).append("\"}");
                sb.append(",\"protect\":").append(protectJson(filesDir));
                sb.append('}');
                return sb.toString();
            } catch (Throwable t) {
                return fail("读取分层状态失败：" + t);
            }
        }
    }

    static String protectJson(File filesDir) {
        Protect p = cfg(filesDir);
        int level = -1;
        double temp = 0;
        try {
            level = BatteryMon.batteryLevel();
            temp = Math.round(BatteryMon.tempC() * 10f) / 10.0;
        } catch (Throwable ignored) {
        }
        String deg = degradeLabel(filesDir);
        StringBuilder sb = new StringBuilder(160);
        sb.append("{\"degrade\":\"").append(deg).append('"')
          .append(",\"level\":").append(level)
          .append(",\"temp\":").append(temp)
          .append(",\"lowPct\":").append(p.lowPct)
          .append(",\"hotC\":").append(p.hotC)
          .append(",\"pause\":").append(p.pause ? "true" : "false")
          .append('}');
        return sb.toString();
    }

    /** 保护阈值（BatteryMon 用）：{lowPct, hotC}。 */
    public static int[] thresholds(File filesDir) {
        Protect p = cfg(filesDir);
        return new int[] { p.lowPct, p.hotC };
    }

    public static String saveProtect(File filesDir, String json) {
        try {
            if (filesDir == null) {
                return fail("缺少文件目录");
            }
            Protect old = cfg(filesDir);
            Protect p = new Protect();
            p.lowPct = old.lowPct;
            p.hotC = old.hotC;
            p.pause = old.pause;
            long[] v = new long[1];
            String s = json == null ? "" : json;
            if (QqbotCrashScene.numAt(s, "lowPct", v)) {
                p.lowPct = (int) clamp(v[0], 5L, 50L);
            }
            if (QqbotCrashScene.numAt(s, "hotC", v)) {
                p.hotC = (int) clamp(v[0], 35L, 60L);
            }
            p.pause = QqbotCrashScene.bool(s, "pause", p.pause);
            StringBuilder sb = new StringBuilder(96);
            sb.append("{\"lowPct\":").append(p.lowPct)
              .append(",\"hotC\":").append(p.hotC)
              .append(",\"pause\":").append(p.pause ? "true" : "false")
              .append('}');
            if (!StateIO.write(filesDir, "protect.json", sb.toString())) {
                return fail("保存失败，存储可能满了");
            }
            cfgCache = p;
            cfgDir = filesDir;
            cfgAt = System.currentTimeMillis();
            log(filesDir, "保护设置已更新：低电 " + p.lowPct + "% / 高温 " + p.hotC + "℃ / 降载暂停自动重启=" + p.pause);
            return "{\"ok\":true}";
        } catch (Throwable t) {
            return fail("保存保护设置失败：" + t);
        }
    }

    private static long clamp(long v, long lo, long hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ---------------------------------------------------------------- 降载 / 空间

    private static void checkDegrade(File filesDir, long now) {
        String d = degradeLabel(filesDir);
        if (d.equals(lastDegrade)) {
            return;
        }
        lastDegrade = d;
        if (d.length() == 0) {
            log(filesDir, "降载结束，恢复正常");
            return;
        }
        boolean thermal = "thermal".equals(d);
        String title = thermal ? "温度过高，已降载" : "电量过低，已降载";
        String body = thermal
                ? "机身温度超过保护线，先暂停自动重启；手动重启还能用"
                : "电量低于保护线，先暂停自动重启；插上电会自动恢复";
        raise("risk", "warn", title, body);
        log(filesDir, "进入降载：" + d);
    }

    private static void checkSpace(File filesDir, long now) {
        if (now - lastSpaceAt < 60L * 1000L) {
            return;
        }
        lastSpaceAt = now;
        long mb = freeMb(filesDir);
        if (mb < 0 || mb >= SPACE_WARN_MB) {
            return;
        }
        if (now - lastSpaceAlert < SPACE_ALERT_GAP_MS) {
            return;
        }
        lastSpaceAlert = now;
        raise("space", mb < 200 ? "crit" : "warn", "手机存储空间不足",
                "只剩 " + mb + " MB，沙盒和日志可能写不进去");
        log(filesDir, "空间不足：剩余 " + mb + " MB");
    }

    static long freeMb(File filesDir) {
        try {
            StatFs sf = new StatFs(filesDir.getAbsolutePath());
            return sf.getAvailableBytes() / 1048576L;
        } catch (Throwable t) {
            return -1;
        }
    }

    // ---------------------------------------------------------------- 保护设置

    private static final class Protect {
        int lowPct = DEFAULT_LOW_PCT;
        int hotC = DEFAULT_HOT_C;
        boolean pause = true;
    }

    private static Protect cfg(File filesDir) {
        long now = System.currentTimeMillis();
        Protect p = cfgCache;
        if (p != null && cfgDir != null && cfgDir.equals(filesDir) && now - cfgAt < 3000L) {
            return p;
        }
        Protect np = new Protect();
        try {
            String t = StateIO.read(filesDir, "protect.json");
            if (t != null) {
                long[] v = new long[1];
                if (QqbotCrashScene.numAt(t, "lowPct", v)) {
                    np.lowPct = (int) clamp(v[0], 5L, 50L);
                }
                if (QqbotCrashScene.numAt(t, "hotC", v)) {
                    np.hotC = (int) clamp(v[0], 35L, 60L);
                }
                np.pause = QqbotCrashScene.bool(t, "pause", true);
            }
        } catch (Throwable ignored) {
        }
        cfgCache = np;
        cfgDir = filesDir;
        cfgAt = now;
        return np;
    }

    static String degradeLabel(File filesDir) {
        try {
            String t = StateIO.read(filesDir, "degrade");
            if (t == null) {
                return "";
            }
            return t.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 自动重启暂停原因；空串表示可以自动重启。 */
    static String pauseReason(File filesDir) {
        try {
            File qq = QqbotShell.qqbotDir(filesDir);
            if (new File(qq, "state/offline").isFile()) {
                return "用户已关闭（offline）";
            }
            if (new File(qq, "state/guard.off").isFile()) {
                return "守护已暂停（guard.off）";
            }
            if (new File(qq, "cache/svc/watchdog.off").isFile()) {
                return "看门狗已关闭（watchdog.off）";
            }
            Protect p = cfg(filesDir);
            String d = degradeLabel(filesDir);
            if (p.pause && d.length() > 0) {
                return "thermal".equals(d) ? "温度过高，自动重启已暂停" : "电量过低，自动重启已暂停";
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    // ---------------------------------------------------------------- 持久化 / 日志 / 小工具

    private static final class Persist {
        long lastAt = 0;
        String lastId = "";
        String lastWhy = "";
        int[] restarts = new int[3];
    }

    private static synchronized Persist ensurePersist(File filesDir) {
        if (persist != null && persistDir != null && persistDir.equals(filesDir)) {
            return persist;
        }
        Persist p = new Persist();
        try {
            String t = StateIO.read(filesDir, "layers.json");
            if (t != null) {
                long[] v = new long[1];
                if (QqbotCrashScene.numAt(t, "at", v)) {
                    p.lastAt = v[0];
                }
                p.lastId = QqbotCrashScene.str(t, "id");
                p.lastWhy = QqbotCrashScene.str(t, "why");
                if (QqbotCrashScene.numAt(t, "sandbox", v)) {
                    p.restarts[0] = (int) v[0];
                }
                if (QqbotCrashScene.numAt(t, "napcat", v)) {
                    p.restarts[1] = (int) v[0];
                }
                if (QqbotCrashScene.numAt(t, "astrbot", v)) {
                    p.restarts[2] = (int) v[0];
                }
            }
        } catch (Throwable ignored) {
        }
        for (int i = 0; i < LAYERS.length; i++) {
            LAYERS[i].restarts = p.restarts[i];
        }
        persist = p;
        persistDir = filesDir;
        return p;
    }

    private static void savePersist(File filesDir) {
        try {
            Persist p = ensurePersist(filesDir);
            for (int i = 0; i < LAYERS.length; i++) {
                p.restarts[i] = LAYERS[i].restarts;
            }
            StringBuilder sb = new StringBuilder(192);
            sb.append("{\"lastRestart\":{\"at\":").append(p.lastAt)
              .append(",\"id\":\"").append(QqbotCrashScene.esc(p.lastId)).append('"')
              .append(",\"why\":\"").append(QqbotCrashScene.esc(p.lastWhy)).append("\"}")
              .append(",\"restarts\":{\"sandbox\":").append(p.restarts[0])
              .append(",\"napcat\":").append(p.restarts[1])
              .append(",\"astrbot\":").append(p.restarts[2])
              .append("}}");
            StateIO.write(filesDir, "layers.json", sb.toString());
        } catch (Throwable ignored) {
        }
    }

    private static String summary() {
        StringBuilder sb = new StringBuilder(96);
        for (int i = 0; i < LAYERS.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(LAYERS[i].id).append('=').append(LAYERS[i].state);
        }
        return sb.toString();
    }

    private static void raise(String cat, String lv, String title, String body) {
        try {
            QqbotAlertCenter.raise(appCtx, cat, lv, title, body);
        } catch (Throwable ignored) {
        }
    }

    static synchronized void log(File filesDir, String line) {
        try {
            if (filesDir == null) {
                return;
            }
            File d = new File(QqbotShell.qqbotDir(filesDir), "logs");
            if (!d.isDirectory() && !d.mkdirs()) {
                return;
            }
            File f = new File(d, "guard.log");
            if (f.length() > 1024L * 1024L) {
                String t = QqbotCrashScene.tail(f, 300 * 1024);
                StateIO.write(f, "（日志超过 1MB，已只留尾部）\n" + (t == null ? "" : t));
            }
            String ts = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
            FileOutputStream out = null;
            try {
                out = new FileOutputStream(f, true);
                out.write(("[" + ts + "] " + line + "\n").getBytes("UTF-8"));
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\r', ' ').replace('\n', ' ').trim();
        if (t.length() > 240) {
            t = t.substring(0, 240) + "…";
        }
        return t;
    }

    static String fail(String msg) {
        return "{\"ok\":false,\"err\":\"" + QqbotCrashScene.esc(msg) + "\"}";
    }

    /** 三层状态，纯数据类。 */
    public static final class Layer {
        public final String id;
        public final String name;
        public volatile String state = "unknown";
        public volatile String prevState = "unknown";
        public volatile String hint = "还没探测";
        public volatile long upMin = 0;
        public volatile int ms = -1;
        public volatile int port = 0;
        public volatile int restarts = 0;
        long lastAutoAt = 0;
        long lastAlertAt = 0;
        int autoTries = 0;
        boolean capNotified = false;
        boolean downNotified = false;
        boolean pendingDown = false;

        Layer(String id, String name, int port) {
            this.id = id;
            this.name = name;
            this.port = port;
        }
    }
}
