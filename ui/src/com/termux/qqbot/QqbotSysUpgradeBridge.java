package com.termux.app;

import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * 「系统升级」桥（页面里的 window.AndroidSysUp）。
 *
 * 分工：真正的升级动作全在 build/opt/qqbot/sysup.sh 里（apt / pip / NapCat 覆盖包），
 * 这个类只做三件事——把版本号读出来给界面、把升级进程拉起来、把它的输出尾巴递回去。
 *
 * 为什么不在 Java 里自己跑 apt：
 *   1) apt、pip 都只活在 proot 的 Ubuntu 里，Termux 侧连 python3 都没有，绕不开 proot；
 *   2) 升级动辄十几分钟，远超 @JavascriptInterface 能阻塞的时间（所有桥方法共用
 *      JavaBridge 这一个线程，堵住它等于整个页面卡死）。所以 start() 只负责「点火」：
 *      nohup + setsid 把脚本甩到独立进程组，run() 立刻返回，进度靠 poll() 轮询日志。
 *
 * 进程组这件事是这套设计的关键：setsid 之后 sysup.sh 自己就是组长（pid == pgid），
 * 脚本把 $$ 写进 sysup.pid，stop() 就能用 kill -TERM -<pid> 把整组带走——
 * 包括 proot 里正在跑的 apt，不会留下半截 dpkg 锁。
 *
 * 输出格式与脚本约定：sysup.sh 只打纯文本行 "id|字段|字段"，退出时补一行
 * "__QQBOT_RC=<码>"。加了新字段就往后加，别改前面的顺序，Java 侧按下标取值。
 */
public class QqbotSysUpgradeBridge {

    /** 脚本在 Termux 前缀里的位置（QqbotActivity.syncScripts 会把 assets 解到这里）。 */
    private static final String SCRIPT_REL = "opt/qqbot/sysup.sh";
    /** 升级脚本的输出文件：$HOME/.qqbot/logs/sysup.log。 */
    private static final String LOG_REL = "logs/sysup.log";
    /** pid 文件：脚本自己写、自己删。 */
    private static final String PID_REL = "sysup.pid";

    /**
     * list() 里的两次脚本调用（version 要查 GitHub，check 要进一次 proot）各给多久。
     * version 最多两次网络请求（各 24 小时缓存），check 只起一次 proot。
     */
    private static final int T_VERSION = 45;
    private static final int T_CHECK = 90;

    /** 进度估算用的预期耗时（秒）：apt 升级最慢，NapCat 只是覆盖一个包。 */
    private static final long EXPECT_UBUNTU = 900L;
    private static final long EXPECT_ASTRBOT = 900L;
    private static final long EXPECT_NAPCAT = 300L;

    private final QqbotActivity act;

    /** 最近一次 start() 的组件 id 与时刻：poll() 靠它们算进度百分比。 */
    private static volatile String curId = "";
    private static volatile long startMs = 0L;

    public QqbotSysUpgradeBridge(QqbotActivity act) {
        this.act = act;
    }

    /* ======================= 对外接口 ======================= */

    /**
     * 版本总览：三个组件各自的当前版本 + 兼容性检查。
     * 返回 {"ok":true,"items":[{id,name,cur,state,note}],"compat":[{id,ok,msg}],"at":毫秒}。
     * state：ok 已是最新 / old 可升级 / unknown 没装或读不到最新版本。
     * 这个方法会同步跑两次脚本（几十秒封顶），页面应当先渲染骨架再调用。
     */
    @JavascriptInterface
    public String list() {
        try {
            File sc = scriptFile();
            if (sc == null || !sc.isFile()) {
                return fail("缺少 sysup.sh：脚本没释放出来，重装一次应用即可").toString();
            }
            String vtext = runScript("version", T_VERSION);
            String ctext = runScript("check", T_CHECK);

            JSONArray items = new JSONArray();
            items.put(verItem("ubuntu", "Ubuntu 系统", vtext));
            items.put(verItem("astrbot", "AstrBot", vtext));
            items.put(verItem("napcat", "NapCat", vtext));

            JSONArray compat = new JSONArray();
            String[] cids = new String[]{"astrbot", "napcat"};
            for (int i = 0; i < cids.length; i++) {
                String[] row = rowOf(ctext, cids[i]);
                if (row == null) continue;
                JSONObject c = new JSONObject();
                c.put("id", cids[i]);
                c.put("ok", row.length > 1 && "1".equals(row[1].trim()));
                c.put("msg", row.length > 2 ? row[2].trim() : "");
                compat.put(c);
            }

            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("items", items);
            out.put("compat", compat);
            out.put("at", System.currentTimeMillis());
            return out.toString();
        } catch (Throwable t) {
            return fail("读取版本信息失败：" + shortOf(t)).toString();
        }
    }

    /**
     * 开始升级某个组件。异步：立刻返回，进度用 poll() 看。
     * 返回 {"ok":true,"started":true,"id":...,"state":"run"}；
     * 已经在升级时返回 started=false（不报错，界面提示「正在升级中」即可）。
     */
    @JavascriptInterface
    public String start(String id) {
        String want = (id == null) ? "" : id.trim();
        try {
            if (!("ubuntu".equals(want) || "astrbot".equals(want) || "napcat".equals(want))) {
                return fail("不认识的组件：" + want + "（只能是 ubuntu / astrbot / napcat）").toString();
            }
            File sc = scriptFile();
            if (sc == null || !sc.isFile()) {
                return fail("缺少 sysup.sh：脚本没释放出来，重装一次应用即可").toString();
            }
            synchronized (QqbotSysUpgradeBridge.class) {
                JSONObject out = new JSONObject();
                if (isRunning()) {
                    out.put("ok", true);
                    out.put("started", false);
                    out.put("id", want);
                    out.put("state", "run");
                    out.put("line", "已经在升级中，等它跑完或者先点取消");
                    return out.toString();
                }
                File lg = logFile();
                File dir = lg.getParentFile();
                if (dir != null && !dir.isDirectory()) dir.mkdirs();
                truncate(lg);
                /*
                 * setsid：脚本变成独立进程组的组长，run() 返回时 destroy 的是我们自己的
                 * 子 shell 进程组，不会误杀刚点着的升级。
                 * "< /dev/null" 必须有：否则脚本会继承 run() 的管道，run() 会一直等它读完。
                 */
                String cmd = "nohup setsid bash " + q(sc.getAbsolutePath()) + " upgrade " + want
                        + " >> " + q(lg.getAbsolutePath()) + " 2>&1 < /dev/null & sleep 2; echo ok";
                QqbotShell.run(act.filesDirPub(), cmd, 25);
                curId = want;
                startMs = System.currentTimeMillis();
                out.put("ok", true);
                out.put("started", true);
                out.put("id", want);
                out.put("state", "run");
                out.put("line", "已开始升级：" + want);
                return out.toString();
            }
        } catch (Throwable t) {
            return fail("启动升级失败：" + shortOf(t)).toString();
        }
    }

    /**
     * 进度轮询（建议 2 秒一次）。
     * 返回 {"state":"idle|run|done|err","id":...,"pct":0-100,"line":"最后一行","tail":["…最近 8 行…"]}。
     * state 的判定顺序：有 __QQBOT_RC 就按退出码定 done / err；没有就按 pid 是否还活着定 run；
     * 日志为空且没进程 = idle；其余情况（进程没了也没有退出码）算 err，并说清楚原因。
     */
    @JavascriptInterface
    public String poll() {
        try {
            File lg = logFile();
            String text = readTail(lg, 24000);
            String[] all = text.length() == 0 ? new String[0] : text.split("\n");
            int rc = -1;
            String idTag = "";
            String last = "";
            for (int i = 0; i < all.length; i++) {
                String s = all[i].trim();
                if (s.length() == 0) continue;
                if (s.startsWith("__QQBOT_RC=")) {
                    rc = parseInt(s.substring(11), -1);
                    continue;
                }
                if (s.startsWith("开始升级：")) idTag = s.substring(5).trim();
                last = s;
            }
            boolean running = isRunning();
            String state;
            if (rc >= 0) {
                state = (rc == 0) ? "done" : "err";
                if (rc == 130 || rc == 143) last = "已取消升级";
            } else if (running) {
                state = "run";
            } else if (text.trim().length() == 0) {
                state = "idle";
            } else if (startMs > 0 && System.currentTimeMillis() - startMs < 6000L) {
                // 刚点下去的那两秒脚本还没把 pid 写出来，别急着报错
                state = "run";
            } else {
                state = "err";
                if (last.length() == 0) last = "升级进程已退出，但没留下任何输出";
            }
            String id = curId.length() > 0 ? curId : idTag;

            JSONObject out = new JSONObject();
            out.put("state", state);
            out.put("id", id);
            out.put("pct", pctOf(state, id));
            out.put("line", last);
            out.put("tail", lastLines(text, 8));
            return out.toString();
        } catch (Throwable t) {
            return fail("读取升级进度失败：" + shortOf(t)).toString();
        }
    }

    /**
     * 取消升级：按 sysup.pid 把整个进程组杀掉（含 proot 里的 apt）。
     * 返回 {"ok":true,"stopped":true|false}；没在跑也算成功，只是 stopped=false。
     */
    @JavascriptInterface
    public String stop() {
        try {
            int pid = readPid(pidFile());
            boolean was = pid > 0 && isRunning();
            if (pid > 0) {
                /*
                 * 先 TERM 整组再单独 TERM 一次 pid（极端情况下脚本自己不是组长），
                 * 一秒后还在就 KILL。apt 的 dpkg 锁会随进程消失自动释放。
                 */
                String cmd = "kill -TERM -" + pid + " 2>/dev/null; kill -TERM " + pid
                        + " 2>/dev/null; sleep 1; kill -KILL -" + pid + " 2>/dev/null; "
                        + "kill -KILL " + pid + " 2>/dev/null; echo ok";
                QqbotShell.run(act.filesDirPub(), cmd, 20);
            }
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("stopped", was);
            return out.toString();
        } catch (Throwable t) {
            return fail("取消升级失败：" + shortOf(t)).toString();
        }
    }

    /**
     * 日志尾巴（给「查看日志」用，比 poll() 的 8 行多）。返回 {"ok":true,"tail":[…]}
     * 只读文件，不碰任何进程，可以随便调。
     */
    @JavascriptInterface
    public String log() {
        try {
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("tail", lastLines(readTail(logFile(), 48000), 40));
            return out.toString();
        } catch (Throwable t) {
            return fail("读取升级日志失败：" + shortOf(t)).toString();
        }
    }

    /* ======================= 内部实现 ======================= */

    private File qqDir() {
        return QqbotShell.qqbotDir(act.filesDirPub());
    }

    private File scriptFile() {
        return new File(QqbotShell.usrDir(act.filesDirPub()), SCRIPT_REL);
    }

    private File logFile() {
        return new File(qqDir(), LOG_REL);
    }

    private File pidFile() {
        return new File(qqDir(), PID_REL);
    }

    /** 跑一次脚本子命令。超时值由调用方给，脚本自己不会挂住（都是普通命令）。 */
    private String runScript(String args, int timeoutSec) {
        File sc = scriptFile();
        return QqbotShell.run(act.filesDirPub(), "bash " + q(sc.getAbsolutePath()) + " " + args, timeoutSec);
    }

    /** 路径加双引号：Termux 前缀里理论上可能带空格，花两字节换安心。 */
    private String q(String s) {
        return "\"" + s + "\"";
    }

    private boolean isRunning() {
        try {
            return QqbotShell.pidAlive(pidFile(), "sysup.sh");
        } catch (Throwable t) {
            return false;
        }
    }

    /** 把 "<id>|a|b|…" 这一行拆开；找不到返回 null。 */
    private String[] rowOf(String text, String id) {
        if (text == null || text.length() == 0) return null;
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String s = lines[i].trim();
            if (s.startsWith(id + "|")) {
                return s.split("\\|", -1);
            }
        }
        return null;
    }

    /** 从 version 的输出里取一个组件：id|cur|note|state。 */
    private JSONObject verItem(String id, String name, String text) throws Exception {
        String[] row = rowOf(text, id);
        String cur = "";
        String note = "";
        String state = "";
        if (row != null) {
            if (row.length > 1) cur = row[1].trim();
            if (row.length > 2) note = row[2].trim();
            if (row.length > 3) state = row[3].trim();
        }
        // 老脚本只给三段时：有版本号就先当「可能有新版」，没版本号就是没装。
        if (state.length() == 0) state = cur.length() == 0 ? "unknown" : "old";
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("cur", cur);
        o.put("state", state);
        o.put("note", note);
        return o;
    }

    /** 百分比：done 直接 100，run 按预期耗时外推并压到 95 以内（永远不满格等最后一步）。 */
    private int pctOf(String state, String id) {
        if ("done".equals(state)) return 100;
        if (!"run".equals(state) || startMs <= 0) return 0;
        long expect = EXPECT_ASTRBOT;
        if ("ubuntu".equals(id)) expect = EXPECT_UBUNTU;
        else if ("napcat".equals(id)) expect = EXPECT_NAPCAT;
        if (expect <= 0) expect = 1;
        long el = (System.currentTimeMillis() - startMs) / 1000L;
        int p = (int) (el * 100L / expect);
        if (p < 1) p = 1;
        if (p > 95) p = 95;
        return p;
    }

    /** 读文件末尾 maxBytes 字节，从第一个换行之后开始（避免半行中文变成乱码）。 */
    private String readTail(File f, int maxBytes) {
        if (f == null || !f.isFile()) return "";
        RandomAccessFile raf = null;
        try {
            long len = f.length();
            long start = len - maxBytes;
            if (start < 0) start = 0;
            raf = new RandomAccessFile(f, "r");
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            raf.readFully(buf);
            String text = new String(buf, "UTF-8");
            if (start > 0) {
                int nl = text.indexOf('\n');
                if (nl >= 0) text = text.substring(nl + 1);
            }
            return text;
        } catch (Throwable t) {
            return "";
        } finally {
            if (raf != null) {
                try { raf.close(); } catch (Throwable ignored) { }
            }
        }
    }

    /** 取最后 n 行非空内容（按原顺序，给 JSON 数组用）。 */
    private JSONArray lastLines(String text, int n) {
        JSONArray arr = new JSONArray();
        if (text == null || text.length() == 0) return arr;
        String[] all = text.split("\n");
        int from = all.length - n;
        if (from < 0) from = 0;
        for (int i = from; i < all.length; i++) {
            String s = all[i].trim();
            if (s.length() == 0) continue;
            // 退出码那行是给机器看的，别混进给人看的日志里
            if (s.startsWith("__QQBOT_RC=")) continue;
            arr.put(s);
        }
        return arr;
    }

    /** 清空日志：每次升级单独一份，poll() 才不会把上一次的结果当成这一次的。 */
    private void truncate(File f) {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "rw");
            raf.setLength(0L);
        } catch (Throwable ignored) {
        } finally {
            if (raf != null) {
                try { raf.close(); } catch (Throwable ignored) { }
            }
        }
    }

    private int readPid(File f) {
        if (f == null || !f.isFile()) return -1;
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            String line = raf.readLine();
            if (line == null) return -1;
            return parseInt(line.trim(), -1);
        } catch (Throwable t) {
            return -1;
        } finally {
            if (raf != null) {
                try { raf.close(); } catch (Throwable ignored) { }
            }
        }
    }

    /** 只认纯数字，别让 pid 文件里的脏字符把 kill 变成「杀 -1」这种灾难。 */
    private int parseInt(String s, int def) {
        if (s == null || s.length() == 0 || s.length() > 12) return def;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return def;
        }
        try {
            return Integer.parseInt(s);
        } catch (Throwable t) {
            return def;
        }
    }

    private String shortOf(Throwable t) {
        if (t == null) return "未知错误";
        String m = t.getMessage();
        if (m == null || m.length() == 0) m = t.getClass().getSimpleName();
        if (m.length() > 200) m = m.substring(0, 200);
        return m;
    }

    private JSONObject fail(String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("err", msg);
        } catch (Throwable ignored) {
        }
        return o;
    }
}
