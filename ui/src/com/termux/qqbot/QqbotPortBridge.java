package com.termux.app;

import android.os.Process;
import android.os.SystemClock;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 「端口体检」的原生侧：window.AndroidPort（scan / release）。
 *
 * 为什么不用 ss / netstat：这两条命令 Termux 基础包不一定带；就算带了，没有 root
 * 也拿不到「端口是哪个进程占的」。/proc/net/tcp 与 /proc/net/tcp6 是内核直接给的
 * LISTEN 表，每行带「本地端口 / socket 归属 uid / inode」；本应用读自己 uid 的
 * /proc/&lt;pid&gt;/fd 还能把 inode 换回 pid。两条合起来，不 root 也能答出界面要的三件事：
 * 端口在不在听、是不是本应用占的、占它的进程还活着吗。
 *
 * 端口清单从哪来：bots.json（沙盒 $HOME/.qqbot/bots.json），与脚本侧的
 * qq_napcat_port / qq_astrbot_port / qq_onebot_port 同一份数据、同一套回退规则
 * （缺失 / 0 / 非法一律回 6099 / 6185 / 6199）。用户改过端口，这里立刻跟着变。
 * 不读 Prefs：端口从来没有存在 prefs.json 里，凭空读它只会得到错数。
 *
 * 性能：JS 同步调用要等桥线程返回，所以结果带缓存——3 秒内直接复用；更旧的先返回
 * 上一次结果，同时起一个后台线程刷新；只有「从没扫过」或「太旧」才同步扫（内部
 * 硬预算 3 秒，超时就返回已经拿到的部分，绝不挂住页面）。
 *
 * d8 处理匿名内部类会崩：本文件不出现匿名类，后台任务用静态嵌套类。
 */
public class QqbotPortBridge {

    /** 三个固定角色（顺序即界面顺序）。 */
    private static final int PORT_NAPCAT = 6099;
    private static final int PORT_ASTR = 6185;
    private static final int PORT_ONEBOT = 6199;

    /** extra 的范围与条数上限（协议 3.2）。 */
    private static final int EXTRA_MIN = 1024;
    private static final int EXTRA_MAX = 9999;
    private static final int EXTRA_LIMIT = 8;

    /** 单次扫描的硬预算：协议要求 scan() 3 秒内返回。 */
    private static final long BUDGET_MS = 3000L;
    /** 多新的缓存可以直接复用（避免页面每轮轮询都把 /proc 扫一遍）。 */
    private static final long FRESH_MS = 3000L;
    /** 比这还旧的缓存不再拿来糊弄页面，同步重扫一次。 */
    private static final long STALE_MS = 120000L;

    private static final Object CACHE = new Object();
    private static volatile String cacheJson = null;
    private static volatile long cacheAt = 0L;
    /** 后台刷新是否在跑：同时只允许一个，避免轮询把线程堆起来。 */
    private static volatile boolean refreshing = false;

    private final File filesDir;

    /** 页面里的桥用这个（注册时传 Activity，和别的桥一致）。 */
    public QqbotPortBridge(QqbotActivity act) {
        this.filesDir = act.filesDirPub();
    }

    /** 只有 filesDir 的场景（服务 / 自检）。 */
    public QqbotPortBridge(File filesDir) {
        this.filesDir = filesDir;
    }

    /* ------------------------------ 桥入口 ------------------------------ */

    /**
     * 端口体检快照，同步返回：
     * {"ok":true,"items":[{"port":6099,"name":"NapCat WebUI","state":"listen",
     *   "pid":1234,"proc":"node","self":true},...],
     *  "extra":[{"port":8080,"pid":0,"proc":"others","self":false},...]}
     * state：listen = 本应用在听；other = 被别的进程占；free = 空闲。
     * 永远不抛：桥方法一抛，页面拿到的就是 undefined（用户看到「点了没反应」）。
     */
    @JavascriptInterface
    public String scan() {
        try {
            String c = cacheJson;
            long age = SystemClock.elapsedRealtime() - cacheAt;
            if (c != null && age <= FRESH_MS) return c;
            if (c != null && age <= STALE_MS) {
                // 有旧结果：先给页面旧值（立即可用），后台补一份新的，下一轮轮询就对了
                kickRefresh();
                return c;
            }
            String fresh = scanNow(filesDir);
            store(fresh);
            return fresh;
        } catch (Throwable t) {
            return fail("端口检测失败：" + t);
        }
    }

    /**
     * 结束某端口上的占用进程。只允许 self:true —— 也就是 uid 与本应用相同的进程；
     * 别人的进程就算了（没有权限，也不该由这个 App 动手）。
     * 成功：{"ok":true,"pid":1234}；失败：{"ok":false,"err":"..."}。
     */
    @JavascriptInterface
    public String release(String port) {
        try {
            int p = toInt(port);
            if (p <= 0 || p > 65535) return fail("端口不合法：" + (port == null ? "" : port));
            int myUid = Process.myUid();
            long deadline = SystemClock.elapsedRealtime() + BUDGET_MS;
            PortTable table = readProcNet();
            Listen best = pick(table.list, p, myUid);
            if (best == null) return fail("端口没在监听，没有需要结束的进程");
            // uid 是内核写进 /proc/net/tcp 的 socket 归属，比「猜进程」可靠
            if (best.uid != myUid) return fail("不是本应用的进程，结束不了");
            Map<Long, PidName> resolved = resolvePids(onlyInodes(best), deadline);
            PidName pn = resolved.get(Long.valueOf(best.inode));
            if (pn == null) return fail("找不到占用进程（可能属于系统，或被权限挡住）");
            if (pn.pid == Process.myPid()) return fail("占着这个端口的就是本应用自己，不能结束");
            if (!terminate(pn.pid)) return fail("进程没退出（多半是有守护脚本又把它拉起来了）");
            // 端口状态变了，别让下一轮 scan 继续返回旧缓存
            synchronized (CACHE) {
                cacheJson = null;
                cacheAt = 0L;
            }
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("pid", pn.pid);
            o.put("port", p);
            return o.toString();
        } catch (Throwable t) {
            return fail("结束占用失败：" + t);
        }
    }

    /* ------------------------------ 缓存 / 后台刷新 ------------------------------ */

    private static void store(String json) {
        // 只缓存成功的结果：失败多半是瞬时问题（刚开机 /proc 还没挂好），不能固化
        if (json == null || json.indexOf("\"ok\":true") < 0) return;
        synchronized (CACHE) {
            cacheJson = json;
            cacheAt = SystemClock.elapsedRealtime();
        }
    }

    private void kickRefresh() {
        synchronized (CACHE) {
            if (refreshing) return;
            refreshing = true;
        }
        Thread th = new Thread(new ScanTask(filesDir), "qqbot-port-scan");
        th.setDaemon(true);
        th.start();
    }

    /** 后台补扫一次。失败就当没发生：页面上还有上一份结果，比空表好。 */
    private static final class ScanTask implements Runnable {

        private final File filesDir;

        ScanTask(File filesDir) {
            this.filesDir = filesDir;
        }

        public void run() {
            try {
                store(scanNow(filesDir));
            } catch (Throwable ignored) {
            } finally {
                refreshing = false;
            }
        }
    }

    /* ------------------------------ 扫描主体 ------------------------------ */

    /** 真正的扫描。独立成静态方法，后台线程与同步调用走同一条路。 */
    static String scanNow(File filesDir) {
        try {
            long deadline = SystemClock.elapsedRealtime() + BUDGET_MS;
            int myUid = Process.myUid();
            LinkedHashMap<Integer, String> roles = portRoles(filesDir);
            PortTable table = readProcNet();

            // 先把要报的 extra 选出来（端口升序、去重、最多 8 条），再一次性反查 pid
            TreeMap<Integer, Listen> extra = new TreeMap<Integer, Listen>();
            for (int i = 0; i < table.list.size(); i++) {
                Listen l = table.list.get(i);
                if (l.port < EXTRA_MIN || l.port > EXTRA_MAX) continue;
                if (roles.containsKey(Integer.valueOf(l.port))) continue;
                Listen old = extra.get(Integer.valueOf(l.port));
                // 同一个端口可能同时有 IPv4 / IPv6 两条监听；本应用的 uid 优先，
                // 这样界面上的「结束占用」按钮不会因为挑到另一条而消失
                if (old == null || (old.uid != myUid && l.uid == myUid)) {
                    extra.put(Integer.valueOf(l.port), l);
                }
            }

            Set<Long> need = new HashSet<Long>();
            for (Map.Entry<Integer, String> e : roles.entrySet()) {
                Listen l = pick(table.list, e.getKey().intValue(), myUid);
                if (l != null && l.uid == myUid) need.add(Long.valueOf(l.inode));
            }
            int n = 0;
            for (Map.Entry<Integer, Listen> e : extra.entrySet()) {
                if (n >= EXTRA_LIMIT) break;
                Listen l = e.getValue();
                if (l.uid == myUid) need.add(Long.valueOf(l.inode));
                n++;
            }
            Map<Long, PidName> resolved = resolvePids(need, deadline);

            JSONArray items = new JSONArray();
            for (Map.Entry<Integer, String> e : roles.entrySet()) {
                int port = e.getKey().intValue();
                Listen l = pick(table.list, port, myUid);
                JSONObject o = new JSONObject();
                o.put("port", port);
                o.put("name", e.getValue());
                if (l == null) {
                    // /proc 表没读到（SELinux / 魔改 ROM）时退到 socket 试连：
                    // 拿不到 pid 与归属，但至少「在不在听」是对的
                    boolean open = !table.ok && QqbotShell.portOpen(port, 400);
                    o.put("state", open ? "listen" : "free");
                    o.put("pid", 0);
                    o.put("proc", "");
                    o.put("self", false);
                } else if (l.uid == myUid) {
                    PidName pn = resolved.get(Long.valueOf(l.inode));
                    o.put("state", "listen");
                    o.put("pid", pn == null ? 0 : pn.pid);
                    o.put("proc", pn == null ? "" : pn.name);
                    o.put("self", true);
                } else {
                    o.put("state", "other");
                    o.put("pid", 0);
                    o.put("proc", "others");
                    o.put("self", false);
                }
                items.put(o);
            }

            JSONArray extraOut = new JSONArray();
            n = 0;
            for (Map.Entry<Integer, Listen> e : extra.entrySet()) {
                if (n >= EXTRA_LIMIT) break;
                Listen l = e.getValue();
                boolean mine = l.uid == myUid;
                PidName pn = mine ? resolved.get(Long.valueOf(l.inode)) : null;
                JSONObject o = new JSONObject();
                o.put("port", e.getKey().intValue());
                o.put("pid", pn == null ? 0 : pn.pid);
                o.put("proc", mine ? (pn == null ? "" : pn.name) : "others");
                o.put("self", mine);
                extraOut.put(o);
                n++;
            }

            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("items", items);
            out.put("extra", extraOut);
            return out.toString();
        } catch (Throwable t) {
            return fail("端口检测失败：" + t);
        }
    }

    /* ------------------------------ 端口清单 ------------------------------ */

    /**
     * 要报的端口 → 名字。与脚本侧同一套规则：
     * 顶层 astrbotPort（0 = 自动 → 6185）；当前实例（active，取不到用第一个）的
     * napcatPort（默认 6099）与 onebotPort（默认 6199）。
     */
    private static LinkedHashMap<Integer, String> portRoles(File filesDir) {
        JSONObject root = readBots(filesDir);
        int astr = 0;
        int nap = 0;
        int one = 0;
        if (root != null) {
            int a = root.optInt("astrbotPort", 0);
            if (a > 0 && a < 65536) astr = a;
            JSONObject cur = activeInstance(root);
            if (cur != null) {
                int p = cur.optInt("napcatPort", 0);
                if (p > 0 && p < 65536) nap = p;
                int q = cur.optInt("onebotPort", 0);
                if (q > 0 && q < 65536) one = q;
            }
        }
        LinkedHashMap<Integer, String> m = new LinkedHashMap<Integer, String>();
        putRole(m, nap == 0 ? PORT_NAPCAT : nap, "NapCat WebUI");
        putRole(m, astr == 0 ? PORT_ASTR : astr, "AstrBot 面板");
        putRole(m, one == 0 ? PORT_ONEBOT : one, "OneBot WS");
        return m;
    }

    /** 两个角色撞到同一个端口时保留先来的名字，别让「AstrBot 面板」盖掉 6099。 */
    private static void putRole(LinkedHashMap<Integer, String> m, int port, String name) {
        Integer k = Integer.valueOf(port);
        if (m.containsKey(k)) return;
        m.put(k, name);
    }

    private static JSONObject readBots(File filesDir) {
        try {
            File f = new File(QqbotShell.qqbotDir(filesDir), "bots.json");
            if (!f.isFile()) return null;
            String s = StateIO.read(f);
            if (s == null) return null;
            s = s.trim();
            // 坏文件（空 / 不是对象）当没有：端口体检不该被一份坏配置拖死
            if (s.length() == 0 || s.charAt(0) != '{') return null;
            return new JSONObject(s);
        } catch (Throwable t) {
            return null;
        }
    }

    /** bots.json 的「当前实例」：先按 active 找 id，找不到退数组第一个。 */
    private static JSONObject activeInstance(JSONObject root) {
        if (root == null) return null;
        JSONArray arr = root.optJSONArray("bots");
        if (arr == null || arr.length() == 0) return null;
        String act = root.optString("active", "");
        JSONObject first = null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject b = arr.optJSONObject(i);
            if (b == null) continue;
            if (first == null) first = b;
            if (act.length() > 0 && act.equals(b.optString("id", ""))) return b;
        }
        return first;
    }

    /* ------------------------------ /proc 解析 ------------------------------ */

    /**
     * 读 /proc/net/tcp 与 tcp6 的 LISTEN 行。两个文件都要读：只监听 IPv6 的进程
     * （node 默认 dual-stack 就会）在 tcp 里根本看不到。
     */
    private static PortTable readProcNet() {
        PortTable t = new PortTable();
        readProcNetOne("/proc/net/tcp", t);
        readProcNetOne("/proc/net/tcp6", t);
        return t;
    }

    private static void readProcNetOne(String path, PortTable t) {
        BufferedReader br = null;
        try {
            File f = new File(path);
            if (!f.canRead()) return;
            br = new BufferedReader(new FileReader(f), 8192);
            String line;
            boolean first = true;
            while ((line = br.readLine()) != null) {
                if (first) {
                    first = false;
                    t.ok = true;   // 能读到第一行（表头）就算这张表可用
                    continue;
                }
                // 列序：0 sl / 1 local_address / 2 rem_address / 3 st / 4 tx:rx /
                //       5 tr:tm->when / 6 retrnsmt / 7 uid / 8 timeout / 9 inode
                String[] c = line.trim().split("\\s+");
                if (c.length < 10) continue;
                if (!"0A".equals(c[3])) continue;   // 0A = TCP_LISTEN
                int colon = c[1].lastIndexOf(':');
                if (colon <= 0) continue;
                int port = hexInt(c[1].substring(colon + 1));
                if (port <= 0 || port > 65535) continue;
                int uid = (int) num(c[7], -1L);
                long inode = num(c[9], 0L);
                t.list.add(new Listen(port, uid, inode));
            }
        } catch (Throwable ignored) {
            // 读不到就留着 t.ok = false，让调用方退到 socket 试连
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 同一端口取一条：优先本应用 uid 的那条（上面说过，双栈会重复）。 */
    private static Listen pick(List<Listen> list, int port, int myUid) {
        Listen found = null;
        for (int i = 0; i < list.size(); i++) {
            Listen l = list.get(i);
            if (l.port != port) continue;
            if (l.uid == myUid) return l;
            if (found == null) found = l;
        }
        return found;
    }

    private static Set<Long> onlyInodes(Listen l) {
        Set<Long> s = new HashSet<Long>();
        if (l != null) s.add(Long.valueOf(l.inode));
        return s;
    }

    /**
     * inode → pid。只扫能 list /proc/&lt;pid&gt;/fd 的进程：别的应用的 fd 目录直接
     * 被内核挡掉（list() 返回 null），省掉了读 uid 的开销，也天然只覆盖本应用的进程。
     * 到预算时间就收手，已经找到的先返回。
     */
    private static Map<Long, PidName> resolvePids(Set<Long> need, long deadline) {
        Map<Long, PidName> out = new HashMap<Long, PidName>();
        if (need == null || need.isEmpty()) return out;
        String[] kids = new File("/proc").list();
        if (kids == null) return out;
        int left = need.size();
        for (int i = 0; i < kids.length && left > 0; i++) {
            if (SystemClock.elapsedRealtime() > deadline) break;
            String k = kids[i];
            if (k == null || k.length() == 0 || !digits(k)) continue;
            File fdDir = new File("/proc/" + k + "/fd");
            String[] fds = fdDir.list();
            if (fds == null) continue;
            for (int j = 0; j < fds.length; j++) {
                String target = readLink(new File(fdDir, fds[j]));
                // fd 指向 socket 时是 "socket:[12345]"，中括号里就是 inode
                if (target == null || target.length() < 10) continue;
                if (!target.startsWith("socket:[")) continue;
                long ino = num(target.substring(8, target.length() - 1), -1L);
                if (ino < 0) continue;
                Long key = Long.valueOf(ino);
                if (!need.contains(key) || out.containsKey(key)) continue;
                int pid = (int) num(k, -1L);
                if (pid <= 0) continue;
                out.put(key, new PidName(pid, procName(k)));
                left--;
                if (left <= 0) break;
            }
        }
        return out;
    }

    private static String readLink(File f) {
        try {
            return android.system.Os.readlink(f.getAbsolutePath());
        } catch (Throwable t) {
            return null;
        }
    }

    /** 进程名：先看 comm（短、无参数），读不到再退回 cmdline 的第一个词。 */
    private static String procName(String pid) {
        String s = readPlain("/proc/" + pid + "/comm", 64);
        if (s != null) {
            s = s.trim();
            if (s.length() > 0) return s.length() > 32 ? s.substring(0, 32) : s;
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream("/proc/" + pid + "/cmdline");
            byte[] buf = new byte[256];
            int n = in.read(buf);
            if (n <= 0) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n && buf[i] != 0; i++) {
                int c = buf[i] & 0xFF;
                if (c < 0x20 || c == 0x7F) continue;
                sb.append((char) c);
            }
            String p = sb.toString();
            int slash = p.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < p.length()) p = p.substring(slash + 1);
            if (p.length() > 32) p = p.substring(0, 32);
            return p;
        } catch (Throwable t) {
            return "";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String readPlain(String path, int max) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(path);
            byte[] buf = new byte[max];
            int n = in.read(buf);
            if (n <= 0) return null;
            return new String(buf, 0, n, "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /* ------------------------------ 结束进程 ------------------------------ */

    /**
     * 先 SIGTERM，给 800ms 收尾；还不走就 SIGKILL。不用 Process.killProcess：
     * 那个接口只能杀本进程，别的进程要 sendSignal。
     */
    private static boolean terminate(int pid) {
        if (!alive(pid)) return true;
        try {
            Process.sendSignal(pid, 15);
        } catch (Throwable ignored) {
        }
        if (waitGone(pid, 8)) return true;
        try {
            Process.sendSignal(pid, 9);
        } catch (Throwable ignored) {
        }
        return waitGone(pid, 6);
    }

    private static boolean waitGone(int pid, int rounds) {
        for (int i = 0; i < rounds; i++) {
            try {
                Thread.sleep(100L);
            } catch (Throwable ignored) {
            }
            if (!alive(pid)) return true;
        }
        return !alive(pid);
    }

    /** /proc/&lt;pid&gt; 还在并且不是僵尸（Z）才算活着：杀了但没被回收也算结束。 */
    private static boolean alive(int pid) {
        File d = new File("/proc/" + pid);
        if (!d.exists()) return false;
        String stat = readPlain("/proc/" + pid + "/stat", 512);
        if (stat != null) {
            int rp = stat.lastIndexOf(')');
            if (rp >= 0 && rp + 2 < stat.length() && stat.charAt(rp + 2) == 'Z') return false;
        }
        return true;
    }

    /* ------------------------------ 小工具 ------------------------------ */

    private static boolean digits(String s) {
        if (s == null || s.length() == 0) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static int hexInt(String s) {
        try {
            return (int) Long.parseLong(s, 16);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static long num(String s, long def) {
        if (s == null) return def;
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    /** 入参是字符串（协议 3.2 定的），也兼容 JS 传数字时被拼成 "6099" 的情况。 */
    private static int toInt(String s) {
        return (int) num(s, -1L);
    }

    private static String fail(String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("err", msg == null ? "" : msg);
            return o.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"err\":\"端口检测失败\"}";
        }
    }

    /* ------------------------------ 数据类型 ------------------------------ */

    /** /proc/net/tcp(6) 里的一条 LISTEN。 */
    private static final class Listen {

        final int port;
        final int uid;
        final long inode;

        Listen(int port, int uid, long inode) {
            this.port = port;
            this.uid = uid;
            this.inode = inode;
        }
    }

    /** 一次 readProcNet 的结果。ok=false 表示两个文件都没读到（要退到 socket 试连）。 */
    private static final class PortTable {

        final List<Listen> list = new ArrayList<Listen>();
        boolean ok = false;
    }

    /** 占着某个 inode 的进程。 */
    private static final class PidName {

        final int pid;
        final String name;

        PidName(int pid, String name) {
            this.pid = pid;
            this.name = name == null ? "" : name;
        }
    }
}
