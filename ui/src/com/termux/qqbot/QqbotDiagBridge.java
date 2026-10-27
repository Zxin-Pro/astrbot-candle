package com.termux.app;

import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/**
 * beta.2「脚本侧诊断」的原生侧：window.AndroidDiag。
 *
 * 分工：真正的探测在沙盒脚本 build/opt/qqbot/diagnose.sh 里（netcheck / kernelcheck /
 * disktop / bundle / health），它把结果写成 $QQBOT_HOME/cache/diag-<net|kernel|disk|bundle|health>.json。
 * 本类只做四件事：
 *   1. start() 在后台线程跑脚本（QqbotShell.run 是阻塞的，绝不能上桥线程）；
 *   2. poll() 只读那份缓存 JSON，外加「是不是正在跑」的内存状态，毫秒级返回；
 *   3. diskTop() 读缓存，超过 60 秒就边回旧数据边重算；
 *   4. health() 完全用 File 读，不跑 shell —— 它会被界面高频调用。
 *
 * 缓存键：net / kernel / disk / bundle / health
 * 脚本子命令：netcheck / kernelcheck / disktop / bundle / health
 *
 * d8 处理匿名内部类会崩：本文件无匿名内部类、无 lambda（DiagTask 是静态嵌套类）。
 */
public class QqbotDiagBridge {

    /** 正在跑的体检项（缓存键），空串 = 空闲。 */
    private static volatile String running = "";
    /** 这一轮 start() 的时间戳（毫秒），用来判断缓存是不是这次的产出。 */
    private static volatile long runningAt = 0L;
    /** 上一轮为什么没产出（超时/脚本缺失），poll() 在失败态里带上它。 */
    private static volatile String lastErr = "";

    /** 各类体检的 shell 超时（秒）。net/kernel 要 DNS + proot 探针，disk 要 du，bundle 要打包。 */
    private static final int T_NET = 40;
    private static final int T_KERNEL = 50;
    private static final int T_DISK = 120;
    private static final int T_BUNDLE = 180;

    /** diskTop 的缓存有效期（秒）。 */
    private static final long DISK_TTL = 60L;

    /** diskTop 默认/上限条数。 */
    private static final int TOP_DEFAULT = 10;
    private static final int TOP_MAX = 30;

    private final QqbotActivity act;
    private final File filesDir;

    /** 页面里的桥用这个（注册时传 Activity）。 */
    public QqbotDiagBridge(QqbotActivity act) {
        this.act = act;
        this.filesDir = act.filesDirPub();
    }

    /** 只有 filesDir 的场景（自检/测试/服务）：拿不到版本号就传空串给脚本。 */
    public QqbotDiagBridge(File filesDir) {
        this.act = null;
        this.filesDir = filesDir;
    }

    /* ------------------------------ 桥入口 ------------------------------ */

    /**
     * 开始一次体检。what = net | kernel | bundle | disk。
     * 立刻返回（不阻塞桥线程）；结果由 poll() 取。重复点同一个项会被忽略。
     */
    @JavascriptInterface
    public void start(String what) {
        String w = key(what);
        if (w.length() == 0) {
            lastErr = "不认识的体检项：" + what;
            return;
        }
        if (running.length() > 0) return;
        File script = script(filesDir);
        if (!script.isFile()) {
            lastErr = "诊断脚本还没就位（" + script.getAbsolutePath() + "），请先完成部署";
            return;
        }
        running = w;
        runningAt = System.currentTimeMillis();
        lastErr = "";
        Thread th = new Thread(new DiagTask(filesDir, w, TOP_DEFAULT, appVersion()), "qqbot-diag-" + w);
        th.setDaemon(true);
        th.start();
    }

    /**
     * 取体检结果：{ok,what,state,title,text,items,ts}。
     * state = running（正在跑）/ ok / fail / idle（还没跑过）。
     */
    @JavascriptInterface
    public String poll(String what) {
        String w = key(what);
        try {
            if (w.length() == 0) return stateJson("fail", "不认识的体检项", "只支持 net / kernel / disk / bundle。", new JSONArray(), "");
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("what", w);
            if (w.equals(running)) {
                o.put("state", "running");
                o.put("title", titleOf(w));
                o.put("text", "正在探测，几秒后自动出结果。");
                o.put("items", new JSONArray());
                o.put("ts", runningAt / 1000L);
                return o.toString();
            }
            File f = cacheFile(filesDir, w);
            JSONObject j = readJson(f);
            if (j == null) {
                if (runningAt > 0L) {
                    // 跑完了却没有产物：脚本超时 / 被杀 / 写文件失败
                    String why = (lastErr.length() > 0) ? lastErr : "体检没有产出结果（可能超时）";
                    return stateJson("fail", "这次体检没跑完", why + "，再点一次试试；一直这样请把诊断包发给开发者。", new JSONArray(), "");
                }
                return stateJson("idle", "还没体检过", "点一下开始，几秒出结果。", new JSONArray(), "");
            }
            long mt = f.lastModified();
            if (runningAt > 0L && mt > 0L && mt < runningAt) {
                String why = (lastErr.length() > 0) ? lastErr : "体检没有产出结果（可能超时）";
                return stateJson("fail", "这次体检没跑完", why + "，再点一次试试；一直这样请把诊断包发给开发者。", new JSONArray(), "");
            }
            o.put("state", j.optString("state", "ok"));
            o.put("title", j.optString("title", ""));
            o.put("text", j.optString("text", ""));
            o.put("items", (j.optJSONArray("items") == null) ? new JSONArray() : j.optJSONArray("items"));
            o.put("ts", j.optLong("ts", 0L));
            if (j.has("hint")) o.put("hint", j.optString("hint", ""));
            return o.toString();
        } catch (Throwable t) {
            return QqbotBridge.failJson("读取体检结果失败：" + t);
        }
    }

    /**
     * 占用最大的文件：{ok, items:[{path,size,kind}], total, free, stale}。
     * 有 60 秒内的缓存就直接回；缓存过期先回旧数据（stale:true）再后台重算；
     * 一次都没有时同步跑一次（用户刚点开页面，等这一下比看到空列表好）。
     */
    @JavascriptInterface
    public String diskTop(int n) {
        int top = (n <= 0) ? TOP_DEFAULT : n;
        if (top > TOP_MAX) top = TOP_MAX;
        try {
            File f = cacheFile(filesDir, "disk");
            JSONObject j = readJson(f);
            long now = System.currentTimeMillis();
            long ts = (j == null) ? 0L : j.optLong("ts", 0L) * 1000L;
            if (j != null && (now - ts) < DISK_TTL * 1000L) return diskJson(j, top, false);
            if (j != null) {
                // 旧数据先用着，别让界面空一下
                start("disk");
                return diskJson(j, top, true);
            }
            if (running.length() == 0) {
                File script = script(filesDir);
                if (!script.isFile()) return QqbotBridge.failJson("诊断脚本还没就位（" + script.getAbsolutePath() + "），请先完成部署");
                QqbotShell.run(filesDir, "bash " + script.getAbsolutePath() + " disktop " + top, 25);
                j = readJson(f);
                if (j == null) return QqbotBridge.failJson("算不出占用：磁盘扫描没有产出结果，稍后再试一次");
                return diskJson(j, top, false);
            }
            return stateJson("running", "正在算占用", "几秒后刷新一下就好。", new JSONArray(), "");
        } catch (Throwable t) {
            return QqbotBridge.failJson("读取占用失败：" + t);
        }
    }

    /**
     * 崩溃记录器 / 部署状态：{ok, crash:{so,soBytes,buildLogBytes,records}, crashDir, doctorAt}。
     * 只读几个 File，不跑 shell。
     */
    @JavascriptInterface
    public String health() {
        try {
            File qq = QqbotShell.qqbotDir(filesDir);
            File cache = new File(qq, "cache");
            File so = new File(cache, "crashwatch.so");
            File cwdir = new File(cache, "crashes");
            File cwlog = new File(cwdir, "cw-build.log");
            File doc = new File(qq, "doctor.txt");
            int records = 0;
            String[] names = cwdir.list();
            if (names != null) {
                for (int i = 0; i < names.length; i++) {
                    String nm = names[i];
                    if (nm.startsWith("crash-") && nm.endsWith(".log")) records++;
                }
            }
            JSONObject crash = new JSONObject();
            crash.put("so", so.isFile());
            crash.put("soBytes", so.isFile() ? so.length() : 0L);
            crash.put("buildLogBytes", cwlog.isFile() ? cwlog.length() : 0L);
            crash.put("records", records);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("crash", crash);
            o.put("crashDir", cwdir.getAbsolutePath());
            o.put("doctorAt", doc.isFile() ? doc.lastModified() / 1000L : 0L);
            return o.toString();
        } catch (Throwable t) {
            return QqbotBridge.failJson("读取崩溃记录器状态失败：" + t);
        }
    }

    /* ------------------------------ 后台任务 ------------------------------ */

    /** 跑一次脚本。只有一个实例会跑（start() 用 running 互斥）。 */
    private static final class DiagTask implements Runnable {

        private final File filesDir;
        private final String what;
        private final int n;
        private final String ver;

        DiagTask(File filesDir, String what, int n, String ver) {
            this.filesDir = filesDir;
            this.what = what;
            this.n = n;
            this.ver = ver;
        }

        public void run() {
            String out = "";
            try {
                File script = script(filesDir);
                String cmd;
                int timeout;
                if ("net".equals(what)) {
                    cmd = "bash " + script.getAbsolutePath() + " netcheck";
                    timeout = T_NET;
                } else if ("kernel".equals(what)) {
                    cmd = "bash " + script.getAbsolutePath() + " kernelcheck";
                    timeout = T_KERNEL;
                } else if ("bundle".equals(what)) {
                    cmd = "bash " + script.getAbsolutePath() + " bundle " + ver;
                    timeout = T_BUNDLE;
                } else {
                    cmd = "bash " + script.getAbsolutePath() + " disktop " + n;
                    timeout = T_DISK;
                }
                out = QqbotShell.run(filesDir, cmd, timeout);
            } catch (Throwable t) {
                lastErr = "体检脚本执行失败：" + t;
            } finally {
                running = "";
                if (lastErr.length() == 0) {
                    String tail = lastLine(out);
                    if (tail.length() > 0 && tail.charAt(0) == '{') lastErr = "";
                    else if (tail.length() > 0) lastErr = tail;
                }
            }
        }
    }

    /* ------------------------------ 小工具 ------------------------------ */

    private static final String[] KEY = {"net", "kernel", "bundle", "disk"};

    /** 在跑的标题：bundle 是打包，其余都叫体检。 */
    private static String titleOf(String w) {
        if ("bundle".equals(w)) return "打包中…";
        return "体检中…";
    }

    /** 把页面传来的名字归一成缓存键（net / kernel / bundle / disk），不认识的给空串。 */
    private static String key(String what) {
        String s = (what == null) ? "" : what.trim().toLowerCase();
        for (int i = 0; i < KEY.length; i++) if (KEY[i].equals(s)) return KEY[i];
        if ("network".equals(s)) return "net";
        if ("cpu".equals(s)) return "kernel";
        if ("storage".equals(s)) return "disk";
        if ("pack".equals(s)) return "bundle";
        return "";
    }

    static File script(File filesDir) {
        return new File(QqbotShell.usrDir(filesDir), "opt/qqbot/diagnose.sh");
    }

    static File cacheFile(File filesDir, String w) {
        return new File(new File(QqbotShell.qqbotDir(filesDir), "cache"), "diag-" + w + ".json");
    }

    private static JSONObject readJson(File f) {
        try {
            if (f == null || !f.isFile()) return null;
            String s = StateIO.read(f);
            if (s == null || s.trim().length() == 0) return null;
            return new JSONObject(s);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String stateJson(String state, String title, String text, JSONArray items, String what) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", true);
            if (what != null && what.length() > 0) o.put("what", what);
            o.put("state", state);
            o.put("title", title);
            o.put("text", text);
            o.put("items", items);
            return o.toString();
        } catch (Throwable t) {
            return QqbotBridge.failJson("拼结果失败：" + t);
        }
    }

    /** diskTop 的返回：只挑 items 里 kind=file 的前 top 条，外加合计与剩余空间。 */
    private static String diskJson(JSONObject j, int top, boolean stale) {
        try {
            JSONArray out = new JSONArray();
            JSONArray src = j.optJSONArray("items");
            if (src != null) {
                int picked = 0;
                for (int i = 0; i < src.length() && picked < top; i++) {
                    JSONObject it = src.optJSONObject(i);
                    if (it == null) continue;
                    if (!"file".equals(it.optString("kind", "file"))) continue;
                    JSONObject e = new JSONObject();
                    e.put("path", it.optString("path", ""));
                    e.put("size", it.optLong("size", 0L));
                    e.put("kind", "file");
                    out.put(e);
                    picked++;
                }
            }
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("items", out);
            o.put("total", j.optLong("total", 0L));
            o.put("free", j.optLong("free", 0L));
            o.put("stale", stale);
            o.put("ts", j.optLong("ts", 0L));
            return o.toString();
        } catch (Throwable t) {
            return QqbotBridge.failJson("拼占用结果失败：" + t);
        }
    }

    private static String lastLine(String s) {
        if (s == null) return "";
        String[] lines = s.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String ln = lines[i].trim();
            if (ln.length() > 0) return ln;
        }
        return "";
    }

    /** 版本号只给 bundle 包的 state.txt 用；拿不到就传空串，脚本会写 (无)。 */
    private String appVersion() {
        if (act == null) return "";
        try {
            return act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "";
        }
    }
}
