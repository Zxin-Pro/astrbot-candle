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
 * 新东西只许追加在末尾（instances[]），旧版本读到多余的键会忽略，旧文件缺这个键按空处理。
 * lastAt / nextAt 是 epoch 秒（与 state/ 下其它文件同口径）。
 *
 * v1.3.5-beta.7 起按实例判定（多开不再是盲区）：bots.json 里每个实例有自己的 napcatPort /
 * onebotPort，逐个实例判「端口活着但 OneBot 掉了」，结论追加到 reconnect.json 的 instances[]；
 * 顶层 state / count 等字段语义不变，作为聚合态——只有「所有在线实例都 unlinked」才置 unlinked
 * 并重启，只要还有一个实例在线就判 unknown 并跳过重启（整组重启会打断那个还活着的实例）。
 *
 * 三道防误判：AstrBot 自己端口不通时一律 unknown（NapCat 连上了也没人收消息，重启没意义）；
 * 日志增量只认 mtime 新鲜、行时间戳不早于本次进程启动、且最近 2048 行没出现过的行；
 * 两个端口都通但日志里有新断连只记「疑似」，不改判 unlinked。
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

    /** bots.json 里的默认端口（与 QqbotGuardTask / QqbotPortBridge 同一口径）。 */
    private static final int DEFAULT_NAPCAT = 6099;
    private static final int DEFAULT_ONEBOT = 6199;
    /** AstrBot 面板端口：顶层 astrbotPort，0 = 自动 → 6185。 */
    private static final int DEFAULT_ASTR = 6185;
    /** 端口探测超时。一轮最多探 1 + 2N 个端口，给短一点，别把守护线程拖住。 */
    private static final int PROBE_MS = 300;
    /** 同一行内容在最近 2048 行里出现过就忽略：重启后日志常把同一句错误再刷一遍。 */
    private static final int SEEN_MAX = 2048;
    /** 单份日志一轮最多看这么多行（LOG_TAIL 里最多可能有几千行，别让一行一行的循环失控）。 */
    private static final int LINE_CAP = 400;
    /** 行时间戳比会话开始时间早这么多毫秒以内还算「这一轮」：容忍日志与系统时钟的小偏差。 */
    private static final long TS_SLACK_MS = 2000L;

    /** AstrBot 自己没起来时的统一说法（前端、通知都直接显示它）。 */
    private static final String ASTR_DOWN = "AstrBot 端口未响应，暂不重启 NapCat";
    /** 一半实例在线时的提示语：整组重启会打断那个还活着的实例。 */
    private static final String MIXED_WHY = "有实例仍在线，已暂停自动重连以免打断其它实例";

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

    /** 每个实例的结论，顺序与 bots.json 一致；落盘为 reconnect.json 的 instances[]。 */
    private static InstState[] insts = new InstState[0];

    /** 本次进程启动时刻（毫秒）：日志里比它还早的行属于上一次运行，不参与判定。 */
    private static long sessionStart;

    /** 最近几次真正发出重启的时刻（窗口判定用）：文件里只留 count，时刻只在内存里。 */
    private static final long[] tryAt = new long[MAX_TRIES + 3];
    private static int tryN;

    private static final String[] slotPath = new String[SLOTS];
    private static final long[] slotOff = new long[SLOTS];
    /** 上一次看到的文件长度：变短说明日志被轮转 / 截断，游标要作废。 */
    private static final long[] slotLen = new long[SLOTS];
    private static int slotNext;

    /** 最近见过的行内容 hash（ring buffer）：同一条错误反复刷不该算「新断连」。 */
    private static final int[] seenHash = new int[SEEN_MAX];
    private static int seenNext;
    private static int seenN;

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
                // 每轮按 bots.json 重新装配实例清单，上一轮的结论按 id 继承（粘滞是按实例算的）。
                JSONObject bots = readBots(files);
                InstState[] list = instancesOf(bots);
                String active = activeIdOf(bots);
                if (StateIO.file(files, "offline").isFile()) {
                    // 用户按过「关闭进程 / 紧急下线」：只记状态，绝不自己把沙盒拉起来。
                    markAll(list, UNKNOWN, "已手动关闭，不做自动重连", now);
                    insts = list;
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
                    markAll(list, UNKNOWN, "沙盒刚开始启动，先观察", now);
                    insts = list;
                    state = UNKNOWN;
                    lastReason = "沙盒刚开始启动，先观察";
                    lastAt = now;
                    nextAt = 0L;
                    save(files);
                    return;
                }

                // ① AstrBot 自检：它自己没起来时不判实例——NapCat 就算连上了也没人收消息，
                //    这时候的重启只会把「AstrBot 没起来」放大成整组重启，还白烧一次额度。
                if (!QqbotShell.portOpen(astrbotPortOf(bots), PROBE_MS)) {
                    markAll(list, UNKNOWN, ASTR_DOWN, now);
                    insts = list;
                    state = UNKNOWN;
                    lastReason = ASTR_DOWN;
                    lastAt = now;
                    nextAt = 0L;
                    save(files);
                    return;
                }

                // ② 逐实例判定：每个实例用自己的 napcatPort / onebotPort；日志证据按文件名里的
                //    id / 端口号归属到实例，认不出归属的才算「公共日志」给所有实例参考。
                LogScan scan = scanAll(files);
                String[][] toks = new String[list.length][];
                for (int i = 0; i < list.length; i++) toks[i] = tokensOf(list[i]);
                boolean[] claimed = claimedLogs(scan.files, toks);
                int linkedN = 0;
                int unlinkedN = 0;
                int online = 0;
                for (int i = 0; i < list.length; i++) {
                    InstState it = list[i];
                    // ⓪ 两个否决项，排在探端口之前：
                    //    · 用户手动关过 NapCat（cache/svc/*.off）——这时候每 2 分钟拉一次，
                    //      等于跟用户对着干；分层看门狗也认这个开关，这里必须一致。
                    //    · 这个实例还没登录（扫码中 / 已退出）——把它判成掉线再整组重启，
                    //      会把正在显示的二维码刷掉，用户永远登不上。
                    String offWhy = offSwitch(files, it.id);
                    if (offWhy != null) {
                        it.state = UNKNOWN;
                        it.reason = offWhy;
                        it.at = now;
                        continue;
                    }
                    if (loginState(files, it.id, active) == 0) {
                        it.state = UNKNOWN;
                        it.reason = "实例还没登录 QQ（扫码中或已退出登录），先不重连";
                        it.at = now;
                        continue;
                    }
                    // 第 0 个实例的端口调用方这一轮已经探过（KeepAlive 传进来的就是它），不重复连。
                    boolean napUp = (i == 0) ? napcatUp : QqbotShell.portOpen(it.napcatPort, PROBE_MS);
                    boolean oneKnown = it.onebotPort > 0;
                    boolean oneUp = oneKnown && QqbotShell.portOpen(it.onebotPort, PROBE_MS);
                    judge(it, napUp, oneUp, oneKnown, sigFor(scan, claimed, toks[i]), now);
                    if (UNKNOWN.equals(it.state)) continue;
                    online++;
                    if (LINKED.equals(it.state)) linkedN++;
                    else unlinkedN++;
                }
                insts = list;

                // ③ 聚合：只有「在线的实例全掉线」才允许动沙盒；只要还有一个在线就跳过重启。
                String next;
                String why;
                if (online <= 0) {
                    next = UNKNOWN;
                    why = "所有实例的 NapCat 端口都没响应，交给沙盒层处理";
                } else if (unlinkedN >= online) {
                    next = UNLINKED;
                    why = aggregateWhy(list, unlinkedN);
                } else if (linkedN >= online) {
                    next = LINKED;
                    why = "所有实例的 NapCat 与 OneBot 端口都在";
                } else {
                    // 有的实例掉线、有的还在：整组重启会把还活着的那个一起打断，宁可不动。
                    next = UNKNOWN;
                    why = MIXED_WHY;
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
                } else if (isUnlinked) {
                    if (!wasUnlinked) {
                        alert(c, "warn", "QQ 长连接已断开",
                                why + "\n正在自动重连（3 秒起，最多 5 次）。");
                    }
                } else {
                    // unknown：要么沙盒没起来，要么「还有实例在线」。后者只在刚从 unlinked
                    // 退回来时提示一次，别每 2 分钟弹一条同样的通知。
                    if (wasUnlinked && linkedN > 0) {
                        alert(c, "info", "已暂停自动重连", MIXED_WHY);
                    }
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
                // 这一轮根本没判：实例状态跟着置 unknown，别让 instances[] 里留着上一轮的 linked。
                markAll(insts, UNKNOWN, lastReason, lastAt);
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
        // 本次进程启动时刻：日志里比它还早的行属于上一次运行，碰都不碰。
        sessionStart = System.currentTimeMillis();
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
            // 旧文件没有 instances 键时按空处理：下一次 check 会照 bots.json 重建。
            insts = loadInstances(o.optJSONArray("instances"));
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
            // 新字段一律追加在末尾：旧版本读到多出来的键会忽略，旧文件缺这个键按空处理。
            o.put("instances", instancesJson());
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
            sleep(SETTLE_MS);
            long deadline = System.currentTimeMillis() + VERIFY_MS - SETTLE_MS;
            while (true) {
                // 多实例：任一实例的 NapCat 端口回来就算沙盒起来了（旧实现只看第一个实例）。
                if (anyNapcatOpen(files)) return true;
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
     * 扫两处日志（沙盒侧 cache/logs、宿主侧 logs）里「新鲜」的那几份，各读一次增量。
     * 每份日志一轮只读一次（游标只推进一次），归属留给 sigFor 按文件名判断。
     */
    private static LogScan scanAll(File files) {
        LogScan out = new LogScan();
        try {
            File qq = QqbotShell.qqbotDir(files);
            File[] dirs = new File[] { new File(qq, "cache/logs"), new File(qq, "logs") };
            long now = System.currentTimeMillis();
            File[] found = new File[SLOTS];
            int n = 0;
            for (int d = 0; d < dirs.length && n < SLOTS; d++) {
                File[] ls = null;
                try {
                    ls = dirs[d].listFiles();
                } catch (Throwable ignored) {
                }
                if (ls == null) continue;
                for (int i = 0; i < ls.length && n < SLOTS; i++) {
                    File f = ls[i];
                    if (f == null || !f.isFile()) continue;
                    String nm = f.getName();
                    if (nm == null || !nm.endsWith(".log")) continue;
                    // 15 分钟没写过的日志不参与判定：NapCat 每次启动都会换新文件，
                    // 旧文件里那条「connection closed」是历史，不是现在。
                    if (now - f.lastModified() > LOG_FRESH_MS) continue;
                    found[n] = f;
                    n++;
                }
            }
            out.files = new File[n];
            out.sigs = new LogSig[n];
            for (int i = 0; i < n; i++) {
                out.files[i] = found[i];
                out.sigs[i] = scanOne(found[i]);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 兼容旧调用（重连线程打重启前再看一眼日志）：只问这一轮整体有没有新断连 / 新恢复。
     * 两处日志给出相反结论时以「断连」为准：AstrBot 到 NapCat 的 ws 是本地回环，
     * 它在，不代表 QQ 那条长连接也在——而后者才是用户真正在乎的。
     */
    private static String[] scanLogs(File files) {
        LogScan ls = scanAll(files);
        for (int i = 0; i < ls.sigs.length; i++) {
            if (ls.sigs[i] != null && ls.sigs[i].drop) return new String[] { "down", ls.sigs[i].why };
        }
        for (int i = 0; i < ls.sigs.length; i++) {
            if (ls.sigs[i] != null && ls.sigs[i].ok) return new String[] { "up", "" };
        }
        return new String[] { "", "" };
    }

    /**
     * 读一份日志的新增部分，逐行判定「最后一条信号」是断连还是恢复。
     * 防历史误判三层：文件 mtime 不在 15 分钟内不收；行首时间戳早于本次会话开始不收；
     * 同样的行（hash）最近 2048 条里出现过就跳过——NapCat 重启后常把旧日志整段重刷一遍。
     */
    private static LogSig scanOne(File f) {
        LogSig sig = new LogSig();
        long len;
        try {
            len = f.length();
            if (System.currentTimeMillis() - f.lastModified() > LOG_FRESH_MS) return sig;
        } catch (Throwable t) {
            return sig;
        }
        if (len <= 0L) return sig;
        int slot = slotOf(f);
        long from = slotOff[slot];
        // 文件被换掉或截断（长度比上次记下的还短）：旧游标作废，从头按「第一次看」处理。
        if (slotLen[slot] > len) from = 0L;
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
        slotLen[slot] = len;
        if (text.length() == 0) return sig;

        int dropAt = -1;
        String dropKey = "";
        int okAt = -1;
        int at = 0;
        int n = text.length();
        while (at < n) {
            int nl = text.indexOf('\n', at);
            int end = (nl < 0) ? n : nl;
            String line = text.substring(at, end);
            at = (nl < 0) ? n : (nl + 1);
            if (line.length() <= 0) continue;
            if (line.length() > LINE_CAP) line = line.substring(0, LINE_CAP);
            // 行首时间戳能解析时，只认本次会话开始之后的：日志文件是追加写，
            // 上一个进程崩溃前那一行也可能落在「15 分钟内」，光看 mtime 挡不住。
            long ts = parseLineTime(line);
            if (ts > 0L && ts < sessionStart - TS_SLACK_MS) continue;
            int h = hashLine(line);
            if (seenRecently(h)) continue;
            noteSeen(h);
            String hit = hitAny(line, DROP_KEYS);
            if (hit != null) {
                dropAt = 1;
                okAt = -1;
                dropKey = hit;
                continue;
            }
            if (hitAny(line, OK_KEYS) != null) {
                okAt = 1;
                dropAt = -1;
            }
        }
        if (dropAt > 0 && dropKey.length() > 0) {
            sig.drop = true;
            sig.why = "日志出现「" + dropKey + "」";
        } else if (okAt > 0) {
            sig.ok = true;
        }
        return sig;
    }

    /** 行里出现的第一个特征词；没有返回 null。 */
    private static String hitAny(String line, String[] keys) {
        for (int i = 0; i < keys.length; i++) {
            String k = keys[i];
            if (k != null && k.length() > 0 && line.indexOf(k) >= 0) return k;
        }
        return null;
    }

    /** 行内容 hash（FNV-1a 32 位）：只用来去重，不要求抗碰撞。 */
    private static int hashLine(String line) {
        int h = 0x811c9dc5;
        for (int i = 0; i < line.length(); i++) {
            h ^= line.charAt(i);
            h *= 0x01000193;
        }
        return h;
    }

    /** 最近 2048 条里是不是出现过同样的行。 */
    private static boolean seenRecently(int h) {
        for (int i = 0; i < seenN; i++) {
            if (seenHash[i] == h) return true;
        }
        return false;
    }

    private static void noteSeen(int h) {
        seenHash[seenNext] = h;
        seenNext = (seenNext + 1) % SEEN_MAX;
        if (seenN < SEEN_MAX) seenN++;
    }

    /**
     * 解析行首时间戳，返回毫秒；解析不出来返回 0（调用方按「时间未知」放过）。
     * 认这几种：2024-05-01 12:00:00 / 2024-05-01T12:00:00 / [12:00:00]（只有时间）。
     */
    private static long parseLineTime(String line) {
        try {
            int i = 0;
            int n = line.length();
            // 跳过前面的 '[' / 空格
            while (i < n && (line.charAt(i) == '[' || line.charAt(i) == ' ')) i++;
            int y = 0;
            int digits = 0;
            while (i < n && digits < 4 && line.charAt(i) >= '0' && line.charAt(i) <= '9') {
                y = y * 10 + (line.charAt(i) - '0');
                i++;
                digits++;
            }
            if (digits == 4 && i < n && (line.charAt(i) == '-' || line.charAt(i) == '/')) {
                int mo = num(line, i + 1, 2);
                int j = i + 1 + 2;
                if (mo <= 0 || j >= n) return 0L;
                j++;
                int da = num(line, j, 2);
                if (da <= 0) return 0L;
                j += 2;
                if (j < n && (line.charAt(j) == 'T' || line.charAt(j) == ' ')) j++;
                int ho = num(line, j, 2);
                int mi = num(line, j + 3, 2);
                int se = num(line, j + 6, 2);
                if (ho < 0 || mi < 0) return 0L;
                if (se < 0) se = 0;
                long days = daysFromCivil(y, mo, da);
                return days * 86400000L + ho * 3600000L + mi * 60000L + se * 1000L;
            }
            return 0L;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /** 从 at 起读两位十进制，越界或非数字返回 -1。 */
    private static int num(String s, int at, int count) {
        if (at < 0 || at + count > s.length()) return -1;
        int v = 0;
        for (int i = 0; i < count; i++) {
            char ch = s.charAt(at + i);
            if (ch < '0' || ch > '9') return -1;
            v = v * 10 + (ch - '0');
        }
        return v;
    }

    /** 公历日期 → 1970-01-01 起的天数（不引 java.time，兼容 API 24）。 */
    private static long daysFromCivil(int y, int m, int d) {
        int yy = (m <= 2) ? (y - 1) : y;
        int era = (yy >= 0 ? yy : yy - 399) / 400;
        int yoe = yy - era * 400;
        int mp = (m + 9) % 12;
        int doy = (153 * mp + 2) / 5 + d - 1;
        int doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return (long) era * 146097L + (long) doe - 719468L;
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
        slotLen[s] = 0L;
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
                long len = f.isFile() ? f.length() : 0L;
                slotOff[i] = len;
                slotLen[i] = len;
            } catch (Throwable ignored) {
                slotOff[i] = 0L;
                slotLen[i] = 0L;
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

    /** 读 bots.json（先 $HOME/.qqbot，再 $HOME/.qqbot/cache）；读不到给空对象。 */
    private static JSONObject readBots(File files) {
        try {
            File qq = QqbotShell.qqbotDir(files);
            String s = StateIO.read(new File(qq, "bots.json"));
            if (s == null || s.trim().length() == 0) {
                s = StateIO.read(new File(new File(qq, "cache"), "bots.json"));
            }
            if (s != null && s.trim().length() > 0) return new JSONObject(s);
        } catch (Throwable ignored) {
        }
        return new JSONObject();
    }

    /** 顶层 astrbotPort；0 或非法值回落 6185（与 QqbotPortBridge.portRoles 同一口径）。 */
    private static int astrbotPortOf(JSONObject bots) {
        try {
            int p = bots.optInt("astrbotPort", 0);
            if (p > 0 && p < 65536) return p;
        } catch (Throwable ignored) {
        }
        return DEFAULT_ASTR;
    }

    /**
     * 与 run-napcat.sh / QqbotActivity.effectiveActive 同口径：bots.json 的 active 必须在列表里
     * 且没被 enable=false 禁用；否则真正占用共享 /opt/QQ 的是第一个启用实例。判断不了给空串。
     */
    private static String activeIdOf(JSONObject bots) {
        try {
            if (bots == null) return "";
            JSONArray bs = bots.optJSONArray("bots");
            if (bs == null || bs.length() == 0) return "";
            String want = trimOf(bots.optString("active", ""));
            if (want.length() > 0) {
                JSONObject hit = null;
                for (int i = 0; i < bs.length(); i++) {
                    JSONObject b = bs.optJSONObject(i);
                    if (b == null) continue;
                    if (want.equals(trimOf(b.optString("id", "")))) {
                        hit = b;
                        break;
                    }
                }
                if (hit != null && enabledOf(hit)) return want;
            }
            for (int i = 0; i < bs.length(); i++) {
                JSONObject b = bs.optJSONObject(i);
                if (b == null) continue;
                String id = trimOf(b.optString("id", ""));
                if (id.length() > 0 && enabledOf(b)) return id;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static String trimOf(String s) {
        if (s == null) return "";
        int a = 0;
        int z = s.length();
        while (a < z && s.charAt(a) <= ' ') a++;
        while (z > a && s.charAt(z - 1) <= ' ') z--;
        return s.substring(a, z);
    }

    private static boolean enabledOf(JSONObject b) {
        try {
            if (b == null) return false;
            if (!b.has("enable")) return true;
            Object v = b.opt("enable");
            if (v instanceof Boolean) return ((Boolean) v).booleanValue();
            String s = String.valueOf(v).trim();
            return !("false".equalsIgnoreCase(s) || "0".equals(s) || "no".equalsIgnoreCase(s));
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 手动关掉的组件：cache/svc/napcat.off（整层）或 cache/svc/<id>.off（单实例）。
     * 返回给人看的原因；没有关闭标记返回 null。命名跟 QqbotLayers.restartService 一致。
     */
    private static String offSwitch(File files, String id) {
        try {
            File dir = new File(QqbotShell.qqbotDir(files), "cache/svc");
            if (new File(dir, "napcat.off").isFile()) {
                return "NapCat 被手动关闭（cache/svc/napcat.off），不自动重连";
            }
            String iid = trimOf(id);
            if (iid.length() > 0 && new File(dir, iid + ".off").isFile()) {
                return "NapCat 被手动关闭（cache/svc/" + iid + ".off），不自动重连";
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 登录态交叉验证：1 已登录 / 0 明确没登录 / -1 判断不了。
     *
     * 判据只用文件，不碰 Activity：NapCat 登录成功后会在
     * <QQ>/resources/app/app_launcher/napcat/config 里按 uin 写 napcat_<uin>.json 与
     * onebot11_<uin>.json；还在扫码时那份文件不存在（或比 cache/qrcode.png 旧）。
     * 这两种情况都不该判掉线、更不该重启：重启会把正在显示的二维码刷掉。
     *
     * 返回 -1 的情况：QQ 目录还没铺好、config 目录不存在、刚启动（.qqbot-start 比 uin 新）。
     * 一律放行 —— 宁可让端口和日志继续判，也不能因为路径漂移把自动重连悄悄关掉。
     */
    private static int loginState(File files, String id, String active) {
        try {
            File ub = new File(QqbotShell.qqbotDir(files), "ubuntu");
            String iid = trimOf(id);
            File qqDir = null;
            if (iid.length() > 0) {
                File inst = new File(new File(new File(ub, "opt/qqbot-inst"), iid), "QQ");
                if (inst.isDirectory()) qqDir = inst;
            }
            if (qqDir == null && iid.length() > 0 && iid.equals(trimOf(active))) {
                File prim = new File(ub, "opt/QQ");
                if (prim.isDirectory()) qqDir = prim;
            }
            if (qqDir == null) return -1;
            File cfgDir = new File(qqDir, "resources/app/app_launcher/napcat/config");
            if (!cfgDir.isDirectory()) return -1;
            long uinAt = newestUinAt(cfgDir);
            if (uinAt <= 0L) return 0;
            // run-napcat.sh 每次启动写 .qqbot-start；比它还旧的 uin 是从 /opt/QQ 继承来的旧配置。
            File start = new File(qqDir, ".qqbot-start");
            if (start.isFile() && uinAt < start.lastModified()) return -1;
            File qr = new File(qqDir, "resources/app/app_launcher/napcat/cache/qrcode.png");
            if (qr.isFile() && qr.lastModified() > uinAt) return 0;
            return 1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** config 目录里最新的 per-uin 配置文件的 mtime；没有合格文件给 0。 */
    private static long newestUinAt(File dir) {
        try {
            File[] fs = dir.listFiles();
            if (fs == null) return 0L;
            long best = 0L;
            for (int i = 0; i < fs.length; i++) {
                File f = fs[i];
                if (f == null || !f.isFile()) continue;
                String n = f.getName();
                if (!n.endsWith(".json")) continue;
                String pre = "";
                if (n.startsWith("napcat_")) pre = "napcat_";
                else if (n.startsWith("onebot11_")) pre = "onebot11_";
                if (pre.length() == 0 || n.length() <= pre.length() + 5) continue;
                if (!allDigits(n.substring(pre.length(), n.length() - 5))) continue;
                long t = f.lastModified();
                if (t > best) best = t;
            }
            return best;
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static boolean allDigits(String s) {
        if (s == null) return false;
        int n = s.length();
        if (n < 5 || n > 12) return false;
        for (int i = 0; i < n; i++) {
            char ch = s.charAt(i);
            if (ch < '0' || ch > '9') return false;
        }
        return true;
    }

    /**
     * 按 bots.json 装配实例清单，逐个继承上一轮同名实例的状态（粘滞要按实例算）。
     * 没有 bots.json 时退化成单实例默认端口，和改造前完全一致。
     */
    private static InstState[] instancesOf(JSONObject bots) {
        InstState[] list = null;
        try {
            JSONArray a = bots.optJSONArray("bots");
            if (a != null && a.length() > 0) {
                list = new InstState[a.length()];
                for (int i = 0; i < a.length(); i++) {
                    JSONObject b = a.optJSONObject(i);
                    InstState it = new InstState();
                    String id = (b == null) ? null : b.optString("id", "");
                    if (id == null || id.trim().length() == 0) id = "bot" + (i + 1);
                    it.id = id;
                    int np = (b == null) ? 0 : b.optInt("napcatPort", 0);
                    int op = (b == null) ? 0 : b.optInt("onebotPort", 0);
                    it.napcatPort = (np > 0 && np < 65536) ? np : DEFAULT_NAPCAT;
                    it.onebotPort = (op > 0 && op < 65536) ? op : DEFAULT_ONEBOT;
                    InstState prev = prevOf(it.id);
                    if (prev != null) {
                        it.state = prev.state;
                        it.reason = prev.reason;
                        it.at = prev.at;
                    }
                    list[i] = it;
                }
            }
        } catch (Throwable ignored) {
            list = null;
        }
        if (list == null || list.length == 0) {
            InstState one = new InstState();
            one.id = "default";
            one.napcatPort = DEFAULT_NAPCAT;
            one.onebotPort = DEFAULT_ONEBOT;
            InstState prev = prevOf(one.id);
            if (prev != null) {
                one.state = prev.state;
                one.reason = prev.reason;
                one.at = prev.at;
            }
            list = new InstState[] { one };
        }
        return list;
    }

    /** 上一轮同名实例的状态；没有返回 null（新出现的实例按 unknown 起算）。 */
    private static InstState prevOf(String id) {
        if (id == null || insts == null) return null;
        for (int i = 0; i < insts.length; i++) {
            InstState it = insts[i];
            if (it == null) continue;
            if (id.equals(it.id)) return it;
        }
        return null;
    }

    /** 任一实例的 napcatPort 能连上：重连线程用它判断「沙盒起来了没有」。 */
    private static boolean anyNapcatOpen(File files) {
        try {
            InstState[] list = instancesOf(readBots(files));
            for (int i = 0; i < list.length; i++) {
                if (list[i] == null) continue;
                if (QqbotShell.portOpen(list[i].napcatPort, 400)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 从状态文件读回实例数组；没有/坏掉给空数组（旧文件没有 instances 键时就是这条路径）。 */
    private static InstState[] loadInstances(JSONArray a) {
        if (a == null || a.length() == 0) return new InstState[0];
        InstState[] out = new InstState[a.length()];
        int n = 0;
        for (int i = 0; i < a.length(); i++) {
            try {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                InstState it = new InstState();
                it.id = o.optString("id", "");
                if (it.id == null || it.id.length() == 0) continue;
                int np = o.optInt("napcatPort", 0);
                int op = o.optInt("onebotPort", 0);
                it.napcatPort = np;
                it.onebotPort = op;
                String st = o.optString("state", "");
                it.state = (LINKED.equals(st) || UNLINKED.equals(st)) ? st : UNKNOWN;
                it.reason = o.optString("reason", "");
                it.at = toMs(o.optLong("at", 0L));
                out[n] = it;
                n++;
            } catch (Throwable ignored) {
            }
        }
        if (n == out.length) return out;
        InstState[] cut = new InstState[n];
        for (int i = 0; i < n; i++) cut[i] = out[i];
        return cut;
    }

    /** 实例数组 → JSON，落到 reconnect.json 的新键 instances 上。 */
    private static JSONArray instancesJson() {
        JSONArray a = new JSONArray();
        if (insts == null) return a;
        for (int i = 0; i < insts.length; i++) {
            InstState it = insts[i];
            if (it == null) continue;
            try {
                JSONObject o = new JSONObject();
                o.put("id", it.id == null ? "" : it.id);
                o.put("napcatPort", it.napcatPort);
                o.put("onebotPort", it.onebotPort);
                o.put("state", it.state == null ? UNKNOWN : it.state);
                o.put("reason", it.reason == null ? "" : it.reason);
                // 与顶层 lastAt / nextAt 同口径：文件里写 epoch 秒，内存里用毫秒。
                o.put("at", it.at / 1000L);
                a.put(o);
            } catch (Throwable ignored) {
            }
        }
        return a;
    }

    /** 把整批实例统一标成某个状态（早退分支用：离线、启动宽限、AstrBot 没起来）。 */
    private static void markAll(InstState[] list, String st, String why, long now) {
        if (list == null) return;
        for (int i = 0; i < list.length; i++) {
            InstState it = list[i];
            if (it == null) continue;
            it.state = st;
            it.reason = why;
            it.at = now;
        }
    }

    /**
     * 单个实例的结论。napcatPort 不通一律 unknown（那是沙盒层的事，这里重启只会空转）；
     * 只有「NapCat 在 + OneBot 不通」才算 unlinked；两个都通但有新的断连日志只记疑似、不改判
     * ——正常启动时 NapCat 也会先打几条 connection closed。
     */
    private static void judge(InstState it, boolean napUp, boolean oneUp, boolean oneKnown,
                              LogSig[] sigs, long now) {
        if (it == null) return;
        boolean drop = false;
        boolean ok = false;
        String dropWhy = "";
        if (sigs != null) {
            for (int i = 0; i < sigs.length; i++) {
                LogSig s = sigs[i];
                if (s == null) continue;
                if (s.drop && !drop) {
                    drop = true;
                    dropWhy = (s.why == null) ? "" : s.why;
                }
                if (s.ok) ok = true;
            }
        }
        String prev = it.state;
        it.at = now;
        if (!napUp) {
            it.state = UNKNOWN;
            it.reason = "NapCat 端口没响应，交给沙盒层";
            return;
        }
        if (oneKnown && !oneUp) {
            it.state = UNLINKED;
            it.reason = (dropWhy.length() > 0)
                    ? ("OneBot 端口没连上；" + dropWhy) : "NapCat 端口在，OneBot 端口没连上";
            return;
        }
        if (drop) {
            if (oneKnown) {
                // 两个端口都通却在打断连日志：多半是重连中的抖动，记疑似但不改判。
                it.state = LINKED;
                it.reason = "端口都通，但" + dropWhy + "（疑似，先不动）";
            } else {
                it.state = UNLINKED;
                it.reason = dropWhy;
            }
            return;
        }
        if (oneUp || ok) {
            it.state = LINKED;
            it.reason = oneKnown ? "NapCat 与 OneBot 端口都在" : "NapCat 端口在，日志已见恢复";
            return;
        }
        if (UNLINKED.equals(prev)) {
            // 断连是「粘」的：没看到恢复证据之前不自己改判成正常。
            it.state = UNLINKED;
            it.reason = "NapCat 端口在，但还没有恢复的证据";
            return;
        }
        it.state = LINKED;
        it.reason = "NapCat 端口在，日志没有断连特征";
    }

    /** 聚合原因：取第一个 unlinked 实例的说明，前面补上「几个都断了」。 */
    private static String aggregateWhy(InstState[] list, int unlinkedN) {
        String head = (list != null && list.length > 1)
                ? (unlinkedN + " 个实例都断了：") : "";
        if (list != null) {
            for (int i = 0; i < list.length; i++) {
                InstState it = list[i];
                if (it == null || !UNLINKED.equals(it.state)) continue;
                if (it.reason != null && it.reason.length() > 0) return head + it.reason;
            }
        }
        return head + "NapCat 端口在，OneBot 端口没连上";
    }

    /** 日志文件名里可以用来认领这份日志的串：实例 id、napcatPort、onebotPort。 */
    private static String[] tokensOf(InstState it) {
        if (it == null) return new String[0];
        String[] out = new String[3];
        int n = 0;
        if (it.id != null && it.id.trim().length() >= 3) {
            out[n] = it.id.trim().toLowerCase();
            n++;
        }
        if (it.napcatPort > 0) {
            out[n] = String.valueOf(it.napcatPort);
            n++;
        }
        if (it.onebotPort > 0) {
            out[n] = String.valueOf(it.onebotPort);
            n++;
        }
        if (n == out.length) return out;
        String[] cut = new String[n];
        for (int i = 0; i < n; i++) cut[i] = out[i];
        return cut;
    }

    /** 文件名（已转小写）里有没有这个实例的标识。 */
    private static boolean hasToken(String name, String[] toks) {
        if (name == null || toks == null) return false;
        for (int i = 0; i < toks.length; i++) {
            String t = toks[i];
            if (t == null || t.length() < 3) continue;
            if (name.indexOf(t) >= 0) return true;
        }
        return false;
    }

    /** 标记哪些日志能被认领（名字里带某个实例的标识）；认不出归属的算公共日志。 */
    private static boolean[] claimedLogs(File[] files, String[][] toks) {
        boolean[] out = new boolean[(files == null) ? 0 : files.length];
        for (int i = 0; i < out.length; i++) {
            try {
                if (files[i] == null) continue;
                String nm = files[i].getName();
                if (nm == null) continue;
                nm = nm.toLowerCase();
                for (int k = 0; k < toks.length; k++) {
                    if (hasToken(nm, toks[k])) {
                        out[i] = true;
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /**
     * 挑出属于这个实例的日志信号：自己的日志优先，其次是没人认领的公共日志；
     * 别的实例的私有日志一律不看（否则 A 掉线会把 B 一起拖进 unlinked）。
     */
    private static LogSig[] sigFor(LogScan scan, boolean[] claimed, String[] toks) {
        if (scan == null || scan.sigs == null || scan.sigs.length == 0) return new LogSig[0];
        LogSig[] out = new LogSig[scan.sigs.length];
        int n = 0;
        for (int i = 0; i < scan.sigs.length; i++) {
            LogSig s = scan.sigs[i];
            if (s == null) continue;
            boolean mine = false;
            try {
                String nm = (scan.files != null && i < scan.files.length && scan.files[i] != null)
                        ? scan.files[i].getName() : null;
                if (nm != null) mine = hasToken(nm.toLowerCase(), toks);
            } catch (Throwable ignored) {
            }
            boolean claimedByOther = (claimed != null && i < claimed.length && claimed[i]) && !mine;
            if (claimedByOther) continue;
            out[n] = s;
            n++;
        }
        if (n == out.length) return out;
        LogSig[] cut = new LogSig[n];
        for (int i = 0; i < n; i++) cut[i] = out[i];
        return cut;
    }

    /** 单个实例的判定结果 + 落盘字段。 */
    private static final class InstState {
        String id;
        int napcatPort;
        int onebotPort;
        String state = UNKNOWN;
        String reason = "";
        long at;
    }

    /** 一份日志这一轮读出来的信号。 */
    private static final class LogSig {
        boolean drop;
        boolean ok;
        String why = "";
    }

    /** 一轮扫到的日志清单与各自信号（下标一一对应）。 */
    private static final class LogScan {
        File[] files = new File[0];
        LogSig[] sigs = new LogSig[0];
    }

    private static void alert(Context c, String lv, String title, String body) {
        try {
            QqbotAlertCenter.raise(c, "offline", lv, title, body);
        } catch (Throwable ignored) {
        }
    }
}
