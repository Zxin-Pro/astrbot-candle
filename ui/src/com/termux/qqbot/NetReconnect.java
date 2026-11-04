package com.termux.app;

import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * 为什么需要它（v1.3.5-beta.6）：NapCat 进程活着、端口也还监听着，QQ 那条长连接却可能
 * 早就断了——扫码超时、被踢下线、切网后 TCP 半开、心跳卡住，全都长这样。这时候看门狗
 * 看到的一切都是「正常」：pid 在、端口通、服务在，唯一不正常的是消息发不出去。用户的
 * 感受是「机器人显示在线，但一句话都不回」。
 *
 * 所以这里补一层「连接健康」判定，只在已有节拍（KeepAlive 的 2 分钟体检）上顺带做一次，
 * 不新增任何唤醒、不新增对外的网络请求：
 *   ① 端口：QqbotShell.portOpen 探一次 NapCat 的 HTTP/WS 端口（本地 connect，毫秒级）；
 *   ② 日志：只看「上次读过之后新追加的那一段」，找断连特征（ECONNRESET / connection closed /
 *      心跳超时 …），并且要求断连出现得比「已连上」更靠后，否则当没看见。
 *   端口通 + 日志最后一条信号是断连 → unlinked；端口通且没有断连信号 → linked；
 *   端口不通 → unknown（那是「沙盒没起来」，归分层看门狗管，不跟它抢着重启）。
 *
 * 重连复用现有入口：往守护服务打 ACTION_RESTART（也就是 GuardRestart 里那条 stop → start
 * 的老路），不另写一套拉起逻辑。退避 3s → 10s → 30s → 60s → 60s，10 分钟内最多 5 次；
 * 到上限就停手并发一条「请手动处理」——没人看着的后台无限重启是最费电的那种 bug。
 *
 * 状态落 $HOME/.qqbot/state/reconnect.json（StateIO 原子写），这是给前端读的唯一契约，
 * 字段固定为：state / count / lastAt / lastReason / nextAt / givenUp，名字不许改；
 * lastAt / nextAt 是 epoch 秒（与 state/ 下其它文件同口径）。
 *
 * 只在跃迁时发通知（linked→unlinked、unlinked→linked、以及放弃的那一次）：每 2 分钟
 * 探测一次就弹一条通知，用户会把整个渠道关掉，等于没有告警。
 *
 * 线程：只被 KeepAlive.rescue 的后台线程调用；真正干重活（等退避、重启后验证）的是
 * 一条具名线程 qqbot-reconnect，同一时刻最多一条。d8 处理匿名内部类会崩，所以这里
 * 既没有匿名类也没有 lambda。
 */
public final class NetReconnect {

    /** 状态文件名：StateIO.file() 会落到 $HOME/.qqbot/state/ 下。 */
    private static final String NAME = "reconnect.json";

    static final String LINKED = "linked";
    static final String UNLINKED = "unlinked";
    static final String UNKNOWN = "unknown";

    /**
     * 退避表：3s → 10s → 30s → 60s → 60s。
     * 第一次要快（多数断连一次重启就好，用户盯着屏幕等），后面拉开距离，
     * 免得沙盒自己起得慢（proot + NapCat 冷启动常常 40 秒以上）时被反复打断。
     */
    private static final long[] BACKOFF_MS = { 3000L, 10000L, 30000L, 60000L, 60000L };

    /** 10 分钟内最多 5 次：与退避表长度一致，超过就停手。 */
    private static final int MAX_TRIES = 5;
    private static final long WINDOW_MS = 10L * 60L * 1000L;

    /** 发完重启后先等这么久再验：stop-sandbox 要等 proot 自己退，startSandbox 里还有 sleep 3。 */
    private static final long SETTLE_MS = 45000L;
    /**
     * 从「发出重启」到「这次算失败」的上限。给得比一次正常冷启动宽：
     * 万一停沙盒慢了点，也宁可多等十几秒，不要在上一条重启还在跑的时候再补一刀。
     */
    private static final long VERIFY_MS = 120000L;
    private static final long VERIFY_STEP_MS = 15000L;

    /** 沙盒刚起来的前 2 分钟不下断连结论：那会儿日志里本来就会有一串连接失败。 */
    private static final long START_GRACE_MS = 120000L;

    /** 单次最多读这么多日志；日志暴涨时宁可漏一轮，也不把守护线程卡住。 */
    private static final long LOG_TAIL = 8192L;
    /** 15 分钟没被写过的日志不看：那不是「现在的断连」，是上个世纪的现场。 */
    private static final long LOG_FRESH_MS = 15L * 60L * 1000L;
    /** 最多同时记 4 份日志「读到哪了」的游标，超了就按轮转淘汰最老的一条。 */
    private static final int SLOTS = 4;

    /**
     * 断连特征。宁可少认几个，也不能把普通日志当故障：认错了就是一次无谓的沙盒重启。
     * 「心跳超时」是守护脚本自己的说法，「connection closed」是 ws 客户端最常见的吐法。
     */
    private static final String[] DROP_KEYS = {
            "ECONNRESET", "ECONNREFUSED", "ETIMEDOUT",
            "connection closed", "Connection closed", "Connection reset", "connection reset",
            "WebSocket closed", "websocket closed", "socket closed", "socket hang up",
            "心跳超时", "心跳丢失", "连接断开", "断开连接", "登录失效", "账号被踢"
    };

    /** 恢复特征：只有出现得比断连更晚，才算「又连上了」。 */
    private static final String[] OK_KEYS = {
            "WebSocket connected", "websocket connected", "connection established",
            "登录成功", "连接成功", "已连接", "重连成功", "扫码成功", "登录完成"
    };

    /** 状态机、计数器、日志游标共用一把锁：探测在 KeepAlive 线程，重试在 qqbot-reconnect 线程。 */
    private static final Object LOCK = new Object();

    private static volatile Thread worker;
    private static volatile boolean loaded;
    private static volatile String state = UNKNOWN;
    private static volatile int count;
    private static volatile long lastAt;
    private static volatile long nextAt;
    private static volatile String lastReason = "";
    private static volatile boolean givenUp;

    /** 最近几次真正发出重启的时刻（窗口判定用）：文件里只留 count，时刻只在内存里。 */
    private static final long[] tryAt = new long[MAX_TRIES + 3];
    private static int tryN;

    private static final String[] slotPath = new String[SLOTS];
    private static final long[] slotOff = new long[SLOTS];
    private static int slotNext;

    private NetReconnect() {
    }

    // ------------------------------------------------------------ 对外入口

    /**
     * 一次连接健康判定。只在 KeepAlive.rescue 的后台线程里调，napcatUp 是调用方这一轮
     * 已经探到的端口结论（探过就传，别让同一个端口一轮被连两次）。
     * 任何异常都吞掉：状态文件坏、日志读不到，都只让这一轮没有结论，绝不能把看门狗带崩。
     */
    static void check(Context c, boolean napcatUp) {
        if (c == null) return;
        try {
            File files = c.getFilesDir();
            synchronized (LOCK) {
                ensureLoaded(files);
                long now = System.currentTimeMillis();
                if (StateIO.file(files, "offline").isFile()) {
                    // 用户按过「关闭进程 / 紧急下线」：只记状态，绝不自己把沙盒拉起来。
                    state = UNKNOWN;
                    lastReason = "已手动关闭，不做自动重连";
                    lastAt = now;
                    nextAt = 0L;
                    save(files);
                    return;
                }
                long pidAt = pidMtime(files);
                if (pidAt > 0L && now - pidAt < START_GRACE_MS) {
                    // 沙盒刚被拉起：这会儿日志里的连接失败是正常的启动过程，判成断连就会自己打自己。
                    state = UNKNOWN;
                    lastReason = "沙盒刚开始启动，先观察";
                    lastAt = now;
                    nextAt = 0L;
                    save(files);
                    return;
                }

                String next;
                String why;
                if (!napcatUp) {
                    next = UNKNOWN;
                    why = "NapCat 端口没响应";
                } else {
                    String[] hit = scanLogs(files);
                    if ("down".equals(hit[0])) {
                        next = UNLINKED;
                        why = hit[1];
                    } else if ("up".equals(hit[0])) {
                        next = LINKED;
                        why = "端口通，日志显示已连上";
                    } else if (UNLINKED.equals(state)) {
                        // 断连是「粘」的：没看到恢复证据之前不自己改判成正常，否则一次静默断线
                        // 会在下一轮被「没有新日志」抹平，用户永远等不到重连。
                        next = UNLINKED;
                        why = "端口通，但还没有恢复的证据";
                    } else {
                        next = LINKED;
                        why = "端口通，日志没有断连特征";
                    }
                }

                boolean wasUnlinked = UNLINKED.equals(state);
                boolean isLinked = LINKED.equals(next);
                boolean isUnlinked = UNLINKED.equals(next);

                if (isLinked) {
                    if (wasUnlinked) {
                        // 只有 unlinked→linked 才通知：这条是「重连成功」的唯一出口。
                        alert(c, "info", "QQ 长连接已恢复", "消息收发应该已经正常了。");
                    }
                    count = 0;
                    givenUp = false;
                    clearTries();
                    nextAt = 0L;
                } else if (isUnlinked && !wasUnlinked) {
                    alert(c, "warn", "QQ 长连接已断开",
                            why + "\n正在自动重连（3 秒起，最多 5 次）。");
                } else if (!isUnlinked) {
                    nextAt = 0L;
                }

                state = next;
                // 停手之后每 2 分钟还会照常判定一次，但那句「请手动处理」别被例行结论覆盖掉：
                // 那是用户唯一能看到的交代。
                lastReason = (givenUp && isUnlinked)
                        ? "已自动重连 " + MAX_TRIES + " 次仍失败，请手动处理" : why;
                lastAt = now;
                save(files);

                if (isUnlinked && !givenUp) {
                    if (count >= MAX_TRIES) {
                        // 次数已经用满，但上一次进程没走到「停手」就退出了：这里补上，
                        // 否则前端会一直看到 unlinked + 满次数，却等不到那句「请手动处理」。
                        giveUp(c, files);
                    } else {
                        startWorker(c, files);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 这一轮根本探不了（沙盒没在跑 / 正在部署 / 端口没应答）：记 unknown，不计数也不重启。
     * 这些情况归分层看门狗（QqbotLayers）和 KeepAlive 的冻结处理，这里跟它们抢只会互相打架。
     */
    static void unavailable(Context c, String why) {
        if (c == null) return;
        try {
            File files = c.getFilesDir();
            synchronized (LOCK) {
                ensureLoaded(files);
                state = UNKNOWN;
                lastReason = (why == null || why.length() == 0) ? "暂时判定不了" : why;
                lastAt = System.currentTimeMillis();
                nextAt = 0L;
                save(files);
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ 状态文件

    /** 读一次磁盘上的状态；进程重启后靠它接着上次的次数算，避免「一重启就能再试 5 次」。 */
    private static void ensureLoaded(File files) {
        if (loaded) return;
        loaded = true;
        String s = StateIO.read(StateIO.file(files, NAME));
        if (s == null) return;
        try {
            JSONObject o = new JSONObject(s);
            String st = o.optString("state", UNKNOWN);
            if (!LINKED.equals(st) && !UNLINKED.equals(st) && !UNKNOWN.equals(st)) st = UNKNOWN;
            state = st;
            int n = o.optInt("count", 0);
            count = (n < 0) ? 0 : (n > MAX_TRIES ? MAX_TRIES : n);
            lastAt = toMs(o.optLong("lastAt", 0L));
            nextAt = toMs(o.optLong("nextAt", 0L));
            lastReason = o.optString("lastReason", "");
            givenUp = o.optBoolean("givenUp", false);
            // 进程重启后拿不到历史尝试时刻：把已经记下的次数按「刚刚发生」摆进 10 分钟窗口。
            // 宁可这一轮少试几次，也不能让一次进程重启把「5 次上限」抹掉。
            tryN = 0;
            long now = System.currentTimeMillis();
            for (int i = 0; i < count && tryN < tryAt.length; i++) tryAt[tryN++] = now;
        } catch (Throwable ignored) {
        }
    }

    /** 原子落盘；写失败静默忽略（前端读不到只会显示上一次的结论，不影响重连本身）。 */
    private static void save(File files) {
        try {
            JSONObject o = new JSONObject();
            o.put("state", state);
            o.put("count", count);
            // 时间戳按仓库约定写成 epoch 秒（与 StateIO.now() / state 下其它文件同口径），内存里用毫秒。
            o.put("lastAt", lastAt / 1000L);
            o.put("lastReason", lastReason == null ? "" : lastReason);
            o.put("nextAt", nextAt / 1000L);
            o.put("givenUp", givenUp);
            StateIO.write(StateIO.file(files, NAME), o.toString());
        } catch (Throwable ignored) {
        }
    }

    /** 文件里是 epoch 秒，内存里统一毫秒；顺手容忍一下万一写成毫秒的旧文件。 */
    private static long toMs(long v) {
        if (v <= 0L) return 0L;
        return (v > 100000000000L) ? v : v * 1000L;
    }

    private static long pidMtime(File files) {
        try {
            File pid = new File(QqbotShell.qqbotDir(files), "supervisor.pid");
            return pid.isFile() ? pid.lastModified() : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    // ------------------------------------------------------------ 重连线程

    private static void startWorker(Context c, File files) {
        Thread t = worker;
        if (t != null && t.isAlive()) return;
        try {
            Thread th = new Thread(new ReconnectRun(c, files), "qqbot-reconnect");
            th.setDaemon(true);
            worker = th;
            th.start();
        } catch (Throwable ignored) {
            worker = null;
        }
    }

    /**
     * 一次断连的完整重试过程：退避 → 打 ACTION_RESTART → 等端口回来 → 不行就下一轮。
     * 整条线跑在独立线程里，所以 3s / 10s 这种短退避才真的有意义（不能被 2 分钟节拍拉长），
     * 也不占用 KeepAlive 那条心跳线程。
     */
    private static final class ReconnectRun implements Runnable {

        private final Context ctx;
        private final File files;

        ReconnectRun(Context c, File f) {
            ctx = c;
            files = f;
        }

        public void run() {
            try {
                while (true) {
                    long wait;
                    int n;
                    synchronized (LOCK) {
                        ensureLoaded(files);
                        if (givenUp) return;
                        if (!eligible()) return;
                        long now = System.currentTimeMillis();
                        if (count >= MAX_TRIES || recentTries(now) >= MAX_TRIES) {
                            giveUp(ctx, files);
                            return;
                        }
                        n = count + 1;
                        wait = BACKOFF_MS[count < BACKOFF_MS.length ? count : BACKOFF_MS.length - 1];
                        lastReason = "准备第 " + n + " 次自动重连";
                        lastAt = now;
                        nextAt = now + wait;
                        save(files);
                    }
                    if (!sleep(wait)) return;

                    // 打之前再看一眼新日志：不少「connection closed」几秒后自己就重连上了，
                    // 这时候还去整组重启沙盒（断 40 秒）纯属添乱。
                    synchronized (LOCK) {
                        if ("up".equals(scanLogs(files)[0])) {
                            if (UNLINKED.equals(state)) {
                                state = LINKED;
                                lastAt = System.currentTimeMillis();
                                lastReason = "日志显示连接已自行恢复，这次不重启";
                                nextAt = 0L;
                                count = 0;
                                givenUp = false;
                                clearTries();
                                save(files);
                                alert(ctx, "info", "QQ 长连接已恢复", "这次是它自己连回来的，没有重启沙盒。");
                            }
                            return;
                        }
                    }

                    synchronized (LOCK) {
                        if (givenUp) return;
                        if (!eligible()) return;
                        count++;
                        noteTry(System.currentTimeMillis());
                        lastAt = System.currentTimeMillis();
                        lastReason = "第 " + count + " 次自动重连已发出";
                        nextAt = 0L;
                        save(files);
                    }
                    // startService 是跨进程调用，放锁外面发，别让状态锁等 binder
                    fire();

                    if (verify()) {
                        synchronized (LOCK) {
                            state = LINKED;
                            lastAt = System.currentTimeMillis();
                            lastReason = "自动重连后端口回来了";
                            nextAt = 0L;
                            // 次数不在这里清零：等下一个节拍真的看到「通且没有断连特征」再清，
                            // 否则一次「端口闪了一下」就会把 5 次额度重置，变成慢性重启循环。
                            resyncLogs();
                            save(files);
                        }
                        return;
                    }
                }
            } catch (Throwable t) {
                // 线程自己崩掉不能把状态卡在「正在重连」：记一笔就走，下一个节拍会重新判断。
                try {
                    synchronized (LOCK) {
                        lastAt = System.currentTimeMillis();
                        lastReason = "自动重连异常：" + t.getClass().getSimpleName();
                        nextAt = 0L;
                        save(files);
                    }
                } catch (Throwable ignored) {
                }
            } finally {
                worker = null;
            }
        }

        /** 还能不能重连：用户没关、风控没暂停、环境还在，而且连接确实还没回来。 */
        private boolean eligible() {
            try {
                // 退避期间连接自己恢复了（用户扫了码 / 网络抖回来）：别再补一刀重启，
                // 一次无谓的全量重启要几十秒，用户会看到「刚恢复又掉线」。
                if (LINKED.equals(state)) return false;
                if (StateIO.file(files, "offline").isFile()) return false;
                if (RiskReader.paused(files)) return false;
                return QqbotShell.bootstrapReady(files);
            } catch (Throwable t) {
                return false;
            }
        }

        /** 复用守护服务现成的重启入口，绝不另写一套拉起逻辑。 */
        private void fire() {
            try {
                Intent i = new Intent(ctx, QqbotGuardService.class);
                i.setAction(QqbotGuardService.ACTION_RESTART);
                ctx.startService(i);
            } catch (Throwable t) {
                lastReason = "打不开重启入口：" + t.getClass().getSimpleName();
            }
        }

        /** 等端口回来；最长 VERIFY_MS，绝不无限等（没人看着的线程比死循环还难查）。 */
        private boolean verify() {
            int port = napcatPort(files);
            if (port <= 0) return false;
            sleep(SETTLE_MS);
            long deadline = System.currentTimeMillis() + VERIFY_MS - SETTLE_MS;
            while (true) {
                if (QqbotShell.portOpen(port, 400)) return true;
                if (System.currentTimeMillis() >= deadline) return false;
                if (!sleep(VERIFY_STEP_MS)) return false;
            }
        }

    }

    /**
     * 到上限就停手：写死状态、发一条需要人来看的通知，然后什么都不做。
     * 后台无限重启是最费电的那种 bug，宁可停在「断了」让用户知道。
     * 放在外层是因为两个地方都要用：重试线程（跑到第 5 次）与 check（进程重启后补记停手）。
     * 调用方都已在 LOCK 里，这里不再重复加锁。
     */
    private static void giveUp(Context c, File files) {
        givenUp = true;
        state = UNLINKED;
        lastAt = System.currentTimeMillis();
        nextAt = 0L;
        lastReason = "已自动重连 " + MAX_TRIES + " 次仍失败，请手动处理";
        save(files);
        alert(c, "crit", "自动重连已达上限", lastReason);
    }

    private static boolean sleep(long ms) {
        try {
            Thread.sleep(Math.max(1L, ms));
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }

    private static void noteTry(long now) {
        if (tryN >= tryAt.length) {
            for (int i = 1; i < tryN; i++) tryAt[i - 1] = tryAt[i];
            tryN = tryAt.length - 1;
        }
        tryAt[tryN] = now;
        tryN++;
    }

    private static void clearTries() {
        tryN = 0;
    }

    /** 10 分钟窗口内已经发过几次重启。 */
    private static int recentTries(long now) {
        int n = 0;
        for (int i = 0; i < tryN; i++) {
            if (now - tryAt[i] <= WINDOW_MS) n++;
        }
        return n;
    }

    // ------------------------------------------------------------ 日志里的断连特征

    /**
     * 扫两处日志（沙盒侧 cache/logs、宿主侧 logs）的「新增部分」，返回 {结论, 原因}。
     * 结论：down / up / ""（这一轮没有新信号）。只读增量，所以 2 分钟一轮也不会拖慢守护线程。
     */
    private static String[] scanLogs(File files) {
        boolean okSeen = false;
        String dropWhy = "";
        try {
            File qq = QqbotShell.qqbotDir(files);
            File[] dirs = new File[] { new File(qq, "cache/logs"), new File(qq, "logs") };
            long now = System.currentTimeMillis();
            int used = 0;
            for (int d = 0; d < dirs.length && used < SLOTS; d++) {
                File[] ls = null;
                try {
                    ls = dirs[d].listFiles();
                } catch (Throwable ignored) {
                }
                if (ls == null) continue;
                for (int i = 0; i < ls.length && used < SLOTS; i++) {
                    File f = ls[i];
                    if (f == null || !f.isFile()) continue;
                    String nm = f.getName();
                    if (nm == null || !nm.endsWith(".log")) continue;
                    // 15 分钟没写过的日志不参与判定：NapCat 每次启动都会换新文件，
                    // 旧文件里那条「connection closed」是历史，不是现在。
                    if (now - f.lastModified() > LOG_FRESH_MS) continue;
                    used++;
                    String[] one = scanOne(f);
                    if ("down".equals(one[0])) {
                        if (dropWhy.length() == 0) dropWhy = one[1];
                    } else if ("up".equals(one[0])) {
                        okSeen = true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // 两处日志给出相反结论时以「断连」为准：AstrBot 到 NapCat 的 ws 是本地回环，
        // 它在，不代表 QQ 那条长连接也在——而后者才是用户真正在乎的。
        if (dropWhy.length() > 0) return new String[] { "down", dropWhy };
        if (okSeen) return new String[] { "up", "" };
        return new String[] { "", "" };
    }

    /** 读一份日志的新增部分，找「最后一条信号」是断连还是恢复。 */
    private static String[] scanOne(File f) {
        String[] none = new String[] { "", "" };
        long len;
        try {
            len = f.length();
        } catch (Throwable t) {
            return none;
        }
        if (len <= 0L) return none;
        int slot = slotOf(f);
        long from = slotOff[slot];
        if (from < 0L || from > len) from = 0L;
        if (from == 0L) {
            // 第一次看这份日志：只回看最后一小段，别把昨天那次掉线的特征当成现在的。
            from = (len > LOG_TAIL) ? (len - LOG_TAIL) : 0L;
        }
        long to = len;
        if (to - from > LOG_TAIL) to = from + LOG_TAIL;
        String text = readRange(f, from, to);
        // 游标只推进到最后一个完整行：半行留到下一轮，免得把一条被截断的日志当成结论。
        long consumed = to;
        try {
            int nl = text.lastIndexOf('\n');
            if (nl >= 0) {
                consumed = from + text.substring(0, nl + 1).getBytes("UTF-8").length;
            } else if (to - from < LOG_TAIL) {
                consumed = from;
            }
        } catch (Throwable ignored) {
        }
        slotOff[slot] = (consumed < from) ? from : consumed;
        if (text.length() == 0) return none;

        int dropAt = -1;
        String dropKey = "";
        for (int i = 0; i < DROP_KEYS.length; i++) {
            int at = text.lastIndexOf(DROP_KEYS[i]);
            if (at > dropAt) {
                dropAt = at;
                dropKey = DROP_KEYS[i];
            }
        }
        int okAt = -1;
        for (int i = 0; i < OK_KEYS.length; i++) {
            int at = text.lastIndexOf(OK_KEYS[i]);
            if (at > okAt) okAt = at;
        }
        if (dropAt >= 0 && dropAt > okAt) {
            return new String[] { "down", "日志出现「" + dropKey + "」" };
        }
        if (okAt >= 0 && okAt > dropAt) {
            return new String[] { "up", "" };
        }
        return none;
    }

    private static int slotOf(File f) {
        String p = f.getAbsolutePath();
        for (int i = 0; i < SLOTS; i++) {
            if (p.equals(slotPath[i])) return i;
        }
        int s = slotNext;
        slotNext = (slotNext + 1) % SLOTS;
        slotPath[s] = p;
        slotOff[s] = 0L;
        return s;
    }

    /**
     * 重连成功后把游标推到各日志末尾：关停那一刻写下的「connection closed」属于上一次，
     * 不推过去的话下一轮会把它当成新断连，白白再重启一次。
     */
    private static void resyncLogs() {
        for (int i = 0; i < SLOTS; i++) {
            String p = slotPath[i];
            if (p == null) continue;
            try {
                File f = new File(p);
                slotOff[i] = f.isFile() ? f.length() : 0L;
            } catch (Throwable ignored) {
                slotOff[i] = 0L;
            }
        }
    }

    private static String readRange(File f, long from, long to) {
        RandomAccessFile raf = null;
        try {
            long len = to - from;
            if (len <= 0L) return "";
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

    /** 第一个实例的 napcatPort；读不到回落 6099（与 QqbotGuardService / KeepAlive 同一口径）。 */
    private static int napcatPort(File files) {
        try {
            File qq = QqbotShell.qqbotDir(files);
            String s = StateIO.read(new File(qq, "bots.json"));
            if (s == null) s = StateIO.read(new File(new File(qq, "cache"), "bots.json"));
            if (s != null) {
                JSONObject o = new JSONObject(s);
                JSONArray a = o.optJSONArray("bots");
                if (a != null) {
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject b = a.optJSONObject(i);
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

    private static void alert(Context c, String lv, String title, String body) {
        try {
            QqbotAlertCenter.raise(c, "offline", lv, title, body);
        } catch (Throwable ignored) {
        }
    }
}
