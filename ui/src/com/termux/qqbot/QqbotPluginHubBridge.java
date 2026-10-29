package com.termux.app;

import android.content.Context;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 「插件市场」的原生侧：window.AndroidPlugin。
 *
 * 为什么解析全放在 Java：Termux 前缀里没有 python3（common.sh 里写死了这条约束），
 * metadata.yaml、_conf_schema.json、插件注册表只能用 org.json 加手写行解析来读；
 * shell 只做它擅长的事（git clone、删目录、挪目录），见 build/opt/qqbot/pluginhub.sh。
 *
 * 为什么启停不靠目录改名：AstrBot 启动扫描只跳过 .plugin-install-* 这类临时目录，
 * 把 x 改成 x.disabled 照样会被 import。真正生效的开关是 data/shared_preferences.json
 * 里的 inactivated_plugins（star_manager.py:1902 关、1978 开），所以 enable() 改的是它。
 *
 * 为什么重载先走面板接口：POST /api/v1/plugins/{id}/reload 是热重载，秒回；
 * 只有面板没起来、或者用户改过控制台密码时才退回「停沙盒 → 再启动」，代价十几秒。
 *
 * 为什么安装要后台线程加 poll()：clone 一个插件几十兆，同步跑会把 WebView 卡死；
 * 进度从脚本的 [NN] 行里取，poll() 只读内存，永远秒回。
 *
 * d8 处理匿名内部类会崩，本文件不出现匿名类；语言级别虽然是 11，仍不用匿名函数、var 这些新语法。
 */
public class QqbotPluginHubBridge {

    /** 装插件可能要走几十兆，给足 10 分钟。 */
    private static final int INSTALL_TIMEOUT_SEC = 600;
    /** 市场列表缓存 6 小时，force=true 才强刷。 */
    private static final long MARKET_TTL_MS = 6L * 60L * 60L * 1000L;
    /** 注册表有 2800 多条，全塞给前端会把 WebView 卡住，只发前 400 条。 */
    private static final int MARKET_MAX = 400;
    /** poll 的 tail 留最近 40 行。 */
    private static final int TAIL_MAX = 40;
    /** 默认注册表地址，与 AstrBot 自己的 plugin_service.py 一致。 */
    private static final String DEFAULT_REGISTRY_URL = "https://api.soulter.top/astrbot/plugins";

    private final QqbotActivity act;
    private final File filesDir;
    private final Context ctx;

    /* 安装进度是给 poll() 读的：桥对象只有一个，静态字段最省事，也免得 JS 拿错实例。 */
    private static volatile String runState = "idle";
    private static volatile int runPct = 0;
    private static volatile String runMsg = "";
    private static final ArrayList<String> TAIL = new ArrayList<String>();

    /* 市场缓存：内存一份（体积大，不落盘到 prefs），时间戳用来判断过期。 */
    private static JSONArray marketItems = null;
    private static long marketAt = 0L;
    private static volatile String marketSrc = "bundled";

    public QqbotPluginHubBridge(QqbotActivity act) {
        this.act = act;
        this.filesDir = (act == null) ? null : act.filesDirPub();
        this.ctx = act;
    }

    public QqbotPluginHubBridge(File filesDir) {
        this.act = null;
        this.filesDir = filesDir;
        this.ctx = null;
    }

    /* ============================== 列表 ============================== */

    @JavascriptInterface
    public String list() {
        try {
            File dir = pluginsDir();
            JSONArray items = new JSONArray();
            JSONArray off = inactivated();
            File[] fs = dir.listFiles();
            String note = "";
            if (fs == null) {
                note = "插件目录还没生成，先让 AstrBot 启动一次";
            } else {
                Arrays.sort(fs, new ByFile());
                for (int i = 0; i < fs.length; i++) {
                    File d = fs[i];
                    if (d == null || !d.isDirectory()) continue;
                    String name = d.getName();
                    // .plugin-install-* 是安装中的临时目录，AstrBot 自己也不扫，界面上不能显示
                    if (name.length() == 0 || name.startsWith(".")) continue;
                    JSONObject it = new JSONObject();
                    it.put("id", name);
                    readMeta(d, it);
                    it.put("on", !isOff(off, name));
                    it.put("hasConf", new File(d, "_conf_schema.json").isFile());
                    items.put(it);
                }
            }
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("dir", dir.getAbsolutePath());
            o.put("items", items);
            o.put("note", note);
            return o.toString();
        } catch (Throwable t) {
            return fail("列插件失败：" + str(t));
        }
    }

    /* ============================== 市场 ============================== */

    @JavascriptInterface
    public String market(boolean force) {
        try {
            JSONArray base = pickMarketItems(force);
            JSONArray out = new JSONArray();
            for (int i = 0; i < base.length(); i++) {
                JSONObject e = base.optJSONObject(i);
                if (e == null) continue;
                JSONObject o = new JSONObject();
                String id = e.optString("id", "");
                o.put("id", id);
                o.put("name", e.optString("name", id));
                o.put("desc", e.optString("desc", ""));
                o.put("repo", e.optString("repo", ""));
                o.put("tags", e.optJSONArray("tags") == null ? new JSONArray() : e.optJSONArray("tags"));
                o.put("installed", new File(pluginsDir(), id).isDirectory());
                out.put(o);
            }
            JSONObject r = new JSONObject();
            r.put("ok", true);
            r.put("src", marketSrc);
            r.put("at", marketAt / 1000L);
            r.put("items", out);
            return r.toString();
        } catch (Throwable t) {
            return fail("取插件市场失败：" + str(t));
        }
    }

    /** 市场列表：先看内存缓存，过期才去读本地注册表 / 联网。 */
    private JSONArray pickMarketItems(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && marketItems != null && (now - marketAt) < MARKET_TTL_MS) return marketItems;
        // market() 是同步桥方法：不点刷新时只读 AstrBot 自己的本地缓存（毫秒级），
        // 联网拉注册表放到后台线程，否则第一次打开市场页会卡十几秒。
        JSONArray remote = force ? readRegistry() : readRegistryLocal();
        if (remote != null && remote.length() > 0) {
            marketItems = remote;
            marketAt = now;
            marketSrc = "remote";
            return marketItems;
        }
        // 刷新失败：有旧数据就先用旧的，总比空列表强
        if (marketItems != null) return marketItems;
        marketItems = bundledItems();
        marketAt = now;
        marketSrc = "bundled";
        if (!force) prefetch();
        return marketItems;
    }

    /** 后台把完整注册表拉回来，下次进市场页就是全量列表；失败就算了，内置清单照样能用。 */
    private void prefetch() {
        Thread t = new Thread(new PrefetchTask(), "qqbot-plugmarket");
        t.setDaemon(true);
        t.start();
    }

    /** 只读本地缓存，不联网。 */
    private JSONArray readRegistryLocal() {
        try {
            File local = new File(dataDir(), "plugins.json");
            if (local.isFile()) {
                JSONArray a = parseRegistry(readText(local, 8 * 1024 * 1024));
                if (a != null && a.length() > 0) return a;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 后台补全市场列表（读网络），成功后替换内存缓存。 */
    private final class PrefetchTask implements Runnable {
        public void run() {
            try {
                JSONArray remote = readRegistry();
                if (remote != null && remote.length() > 0) {
                    marketItems = remote;
                    marketAt = System.currentTimeMillis();
                    marketSrc = "remote";
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 读插件注册表。先读 AstrBot 自己缓存的 data/plugins.json（离线可用、格式最准），
     * 再退回联网拉官方注册表。两边都拿不到就返回 null，由调用方降级到内置列表。
     */
    private JSONArray readRegistry() {
        try {
            File local = new File(dataDir(), "plugins.json");
            if (local.isFile()) {
                JSONArray a = parseRegistry(readText(local, 8 * 1024 * 1024));
                if (a != null && a.length() > 0) return a;
            }
        } catch (Throwable ignored) {
        }
        String url = DEFAULT_REGISTRY_URL;
        try {
            JSONObject root = bundledRoot();
            String u = (root == null) ? "" : root.optString("url", "");
            if (u.startsWith("http://") || u.startsWith("https://")) url = u;
        } catch (Throwable ignored) {
        }
        try {
            String body = fetch(url, 12000);
            JSONArray a = parseRegistry(body);
            if (a != null && a.length() > 0) return a;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 注册表解析：官方格式是 {"author/plugin_name":{...}}，也可能外面套一层
     * {"data":{...},"timestamp":...}（AstrBot 的 plugins.json 缓存就是后者）。
     * 顶层 $meta 要跳过。
     */
    private static JSONArray parseRegistry(String text) throws Exception {
        if (text == null || text.trim().length() == 0) return null;
        JSONObject root = null;
        try {
            root = new JSONObject(text);
        } catch (Throwable t) {
            return null;
        }
        JSONObject reg = root;
        JSONObject data = root.optJSONObject("data");
        if (data != null) reg = data;
        else if (root.optJSONArray("items") != null) return root.optJSONArray("items");

        ArrayList<JSONObject> list = new ArrayList<JSONObject>();
        java.util.Iterator<String> keys = reg.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            if (k == null || k.startsWith("$")) continue;
            JSONObject e = reg.optJSONObject(k);
            if (e == null) continue;
            String id = e.optString("name", "");
            if (id.length() == 0) {
                int p = k.lastIndexOf('/');
                id = (p >= 0) ? k.substring(p + 1) : k;
            }
            String repo = e.optString("repo", "");
            if (repo.length() == 0) repo = e.optString("repository", "");
            JSONObject it = new JSONObject();
            it.put("id", id);
            String dn = e.optString("display_name", "");
            it.put("name", dn.length() > 0 ? dn : id);
            String desc = e.optString("desc", "");
            if (desc.length() > 140) desc = desc.substring(0, 140) + "…";
            it.put("desc", desc);
            it.put("repo", repo);
            JSONArray tags = new JSONArray();
            String cat = e.optString("category", "");
            if (cat.length() > 0) tags.put(cat);
            it.put("tags", tags);
            it.put("stars", e.optInt("stars", 0));
            list.add(it);
        }
        // 星多的排前面，界面第一屏才有东西看
        JSONObject[] arr = list.toArray(new JSONObject[0]);
        for (int i = 0; i < arr.length; i++) {
            int best = i;
            for (int j = i + 1; j < arr.length; j++) {
                if (arr[j].optInt("stars", 0) > arr[best].optInt("stars", 0)) best = j;
            }
            if (best != i) {
                JSONObject tmp = arr[i];
                arr[i] = arr[best];
                arr[best] = tmp;
            }
        }
        JSONArray out = new JSONArray();
        int n = Math.min(arr.length, MARKET_MAX);
        for (int i = 0; i < n; i++) out.put(arr[i]);
        return out;
    }

    private JSONObject bundledRoot() {
        String s = readAsset("qqbot/plugins-index.json");
        if (s == null) return null;
        try {
            return new JSONObject(s);
        } catch (Throwable t) {
            return null;
        }
    }

    private JSONArray bundledItems() {
        JSONObject root = bundledRoot();
        JSONArray a = (root == null) ? null : root.optJSONArray("items");
        return (a == null) ? new JSONArray() : a;
    }

    private String readAsset(String name) {
        if (ctx == null) return null;
        InputStream in = null;
        try {
            in = ctx.getAssets().open(name);
            return readAll(in);
        } catch (Throwable t) {
            return null;
        } finally {
            closeQuiet(in);
        }
    }

    /* ============================== 安装 ============================== */

    @JavascriptInterface
    public String install(String id) {
        try {
            String pid = safeId(id);
            if (pid.length() == 0) return fail("插件名不合法");
            if (isRunning()) return fail("上一个插件还在装，等它装完再说");
            File sh = script();
            if (!sh.isFile()) return fail("这个安装包还不支持（升级后可用），没找到插件脚本");
            String repo = repoOf(pid);
            if (repo.length() == 0) return fail("没找到这个插件的下载地址，先刷新插件市场");
            synchronized (TAIL) {
                TAIL.clear();
            }
            runPct = 0;
            runMsg = "准备下载 " + pid;
            runState = "run";
            Thread t = new Thread(new InstallTask(filesDir, pid, repo), "qqbot-pluginstall");
            t.setDaemon(true);
            t.start();
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("started", true);
            return o.toString();
        } catch (Throwable t) {
            runState = "err";
            runMsg = "启动安装失败：" + str(t);
            return fail(runMsg);
        }
    }

    @JavascriptInterface
    public String poll() {
        try {
            JSONObject o = new JSONObject();
            o.put("state", runState);
            o.put("pct", runPct);
            o.put("msg", runMsg);
            o.put("tail", tailArray());
            return o.toString();
        } catch (Throwable t) {
            return fail("读安装进度失败：" + str(t));
        }
    }

    /** 从市场缓存 / 内置清单里找 repo；都找不到才返回空串。 */
    private String repoOf(String id) {
        // 只用已经拿到的列表（市场页刚刷过的那份），不在这里联网：
        // install() 是同步桥方法，一联网页面就卡住，刷新是市场页自己干的事。
        JSONArray base = marketItems;
        if (base == null) base = bundledItems();
        for (int i = 0; i < base.length(); i++) {
            JSONObject e = base.optJSONObject(i);
            if (e == null) continue;
            if (id.equals(e.optString("id", ""))) {
                String r = e.optString("repo", "");
                if (r.length() > 0) return r;
            }
        }
        return "";
    }

    /* ============================== 删除 / 启停 ============================== */

    @JavascriptInterface
    public String remove(String id) {
        try {
            String pid = safeId(id);
            if (pid.length() == 0) return fail("插件名不合法");
            File sh = script();
            if (!sh.isFile()) return fail("这个安装包还不支持（升级后可用），没找到插件脚本");
            String out = QqbotShell.run(filesDir, "bash " + q(sh.getAbsolutePath()) + " remove " + q(pid), 60);
            JSONObject r = lastJson(out);
            if (r == null) return fail("删除没回结果，看看日志");
            if (!r.optBoolean("ok", false)) return fail(r.optString("err", "删除失败"));
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("msg", "已删除");
            return o.toString();
        } catch (Throwable t) {
            return fail("删除失败：" + str(t));
        }
    }

    @JavascriptInterface
    public String enable(String id, boolean on) {
        try {
            String pid = safeId(id);
            if (pid.length() == 0) return fail("插件名不合法");
            File dir = new File(pluginsDir(), pid);
            if (!dir.isDirectory()) return fail("没找到这个插件，可能已经删掉了");
            File prefs = new File(dataDir(), "shared_preferences.json");
            JSONObject root = null;
            if (prefs.isFile()) {
                try {
                    root = new JSONObject(readText(prefs, 4 * 1024 * 1024));
                } catch (Throwable ignored) {
                }
            }
            if (root == null) root = new JSONObject();
            JSONArray old = root.optJSONArray("inactivated_plugins");
            JSONArray out = new JSONArray();
            boolean had = false;
            for (int i = 0; old != null && i < old.length(); i++) {
                String s = old.optString(i, "");
                if (matchModule(s, pid)) {
                    had = true;
                    continue;
                }
                out.put(s);
            }
            if (!on && !had) out.put(modulePath(dir));
            root.put("inactivated_plugins", out);
            if (!writeText(prefs, root.toString())) return fail("写不进 shared_preferences.json，可能没权限");
            // 改完马上热重载，不重载的话要等下次 AstrBot 启动才生效
            reloadQuiet(pid);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("msg", on ? "已启用" : "已停用");
            return o.toString();
        } catch (Throwable t) {
            return fail("启停失败：" + str(t));
        }
    }

    /* ============================== 重载 ============================== */

    @JavascriptInterface
    public String reload(String id) {
        try {
            String pid = safeId(id);
            if (pid.length() == 0) return fail("插件名不合法");
            if (panelReload(pid)) {
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("msg", "插件已重载");
                return o.toString();
            }
            // 面板调不通（没起来 / 密码被改过）：退回重启沙盒，十几秒后插件生效
            startRestart();
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("msg", "已重启 AstrBot 让插件生效");
            return o.toString();
        } catch (Throwable t) {
            return fail("重载失败：" + str(t));
        }
    }

    /** 热重载：只对本机控制台，登录拿 JWT 后 POST。失败返回 false，不抛给上层。 */
    private boolean panelReload(String id) {
        try {
            String root = "http://127.0.0.1:" + astrPort();
            String token = AstrHot.login(root);
            AstrHot.Resp r = AstrHot.send(root + "/api/v1/plugins/" + id + "/reload", "POST", "{}", token);
            return r.code >= 200 && r.code < 300;
        } catch (Throwable t) {
            return false;
        }
    }

    private void reloadQuiet(String id) {
        Thread t = new Thread(new ReloadTask(id), "qqbot-plugreload");
        t.setDaemon(true);
        t.start();
    }

    /** 后台重启沙盒：与 AstrSaveTask 同一套路（先 stop，确认 proot 没了再 start）。 */
    private void startRestart() {
        Thread t = new Thread(new RestartTask(filesDir), "qqbot-plugrestart");
        t.setDaemon(true);
        t.start();
    }

    /* ============================== 配置 ============================== */

    @JavascriptInterface
    public String conf(String id) {
        try {
            String pid = safeId(id);
            if (pid.length() == 0) return fail("插件名不合法");
            File dir = new File(pluginsDir(), pid);
            if (!dir.isDirectory()) return fail("没找到这个插件，可能已经删掉了");
            File schemaFile = new File(dir, "_conf_schema.json");
            if (!schemaFile.isFile()) return fail("这个插件没有可视化配置");
            JSONObject schema = null;
            try {
                schema = new JSONObject(readText(schemaFile, 1024 * 1024));
            } catch (Throwable t) {
                return fail("这个插件的配置说明读不出来，可能文件坏了");
            }
            File confFile = confFile(dir, pid);
            JSONObject conf = new JSONObject();
            if (confFile.isFile()) {
                try {
                    conf = new JSONObject(readText(confFile, 1024 * 1024));
                } catch (Throwable ignored) {
                }
            }
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("schema", schema);
            o.put("conf", conf);
            o.put("path", confFile.getAbsolutePath());
            return o.toString();
        } catch (Throwable t) {
            return fail("读插件配置失败：" + str(t));
        }
    }

    @JavascriptInterface
    public String save(String id, String json) {
        try {
            String pid = safeId(id);
            if (pid.length() == 0) return fail("插件名不合法");
            File dir = new File(pluginsDir(), pid);
            if (!dir.isDirectory()) return fail("没找到这个插件，可能已经删掉了");
            JSONObject obj = null;
            try {
                obj = new JSONObject(json == null ? "" : json);
            } catch (Throwable t) {
                return fail("配置内容不是合法的 JSON，没保存");
            }
            File confFile = confFile(dir, pid);
            if (!writeText(confFile, obj.toString())) return fail("写不进配置文件，可能没权限");
            reloadQuiet(pid);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("msg", "已保存并重载");
            return o.toString();
        } catch (Throwable t) {
            return fail("保存失败：" + str(t));
        }
    }

    /**
     * 配置文件到底在哪：AstrBot 4.x 把插件配置放在 data/config/&lt;目录名&gt;_config.json
     * （star_manager.py:1156），老插件和自己写 config.json 的插件放在插件目录里，两种都认。
     */
    private File confFile(File dir, String id) {
        File a = new File(new File(dataDir(), "config"), id + "_config.json");
        if (a.isFile()) return a;
        File b = new File(dir, "config.json");
        if (b.isFile()) return b;
        File c = new File(dir, "conf.json");
        if (c.isFile()) return c;
        return a;
    }

    /* ============================== 日志 ============================== */

    @JavascriptInterface
    public String log() {
        try {
            JSONArray tail = new JSONArray();
            List<String> lines = tailFile(new File(QqbotShell.qqbotDir(filesDir), "cache/plugins-install.log"), 40);
            if (lines.isEmpty()) {
                File newest = newestAstrLog();
                if (newest != null) lines = tailFile(newest, 40);
            }
            for (int i = 0; i < lines.size(); i++) tail.put(lines.get(i));
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("tail", tail);
            return o.toString();
        } catch (Throwable t) {
            return fail("读日志失败：" + str(t));
        }
    }

    /* ============================== 路径 ============================== */

    /** AstrBot 根目录：优先 /opt/AstrBot，老镜像在 /root/astrbot。 */
    static File astrRoot(File filesDir) {
        File home = QqbotShell.homeDir(filesDir);
        File a = new File(home, "ubuntu/opt/AstrBot");
        if (a.isDirectory()) return a;
        File b = new File(home, "ubuntu/root/astrbot");
        if (b.isDirectory()) return b;
        return a;
    }

    private File dataDir() {
        return new File(astrRoot(filesDir), "data");
    }

    private File pluginsDir() {
        return new File(dataDir(), "plugins");
    }

    private File script() {
        return new File(QqbotShell.usrDir(filesDir), "opt/qqbot/pluginhub.sh");
    }

    /** 控制台端口，与 bots.json 同一套规则：缺失 / 0 一律 6185。 */
    private int astrPort() {
        try {
            JSONObject root = new JSONObject(readText(new File(QqbotShell.qqbotDir(filesDir), "bots.json"), 512 * 1024));
            int p = root.optInt("astrbotPort", 0);
            if (p > 0 && p < 65536) return p;
        } catch (Throwable ignored) {
        }
        return 6185;
    }

    /** proot 里看到的插件模块路径，格式与 star_manager 里 module_path 一致。 */
    private static String modulePath(File dir) {
        String id = dir.getName();
        String mod = "main";
        if (!new File(dir, "main.py").isFile() && new File(dir, id + ".py").isFile()) mod = id;
        return "/opt/AstrBot/data/plugins/" + id + "/" + mod;
    }

    private static boolean matchModule(String s, String id) {
        if (s == null) return false;
        String p = s.replace('\\', '/');
        if (p.endsWith("/" + id + "/main") || p.endsWith("/" + id + "/" + id)) return true;
        return p.endsWith("/" + id);
    }

    /* ============================== 插件元信息 ============================== */

    private static void readMeta(File dir, JSONObject it) throws Exception {
        JSONObject meta = null;
        File pj = new File(dir, "plugin.json");
        File mj = new File(dir, "metadata.json");
        if (pj.isFile()) meta = parseJson(readText(pj, 128 * 1024));
        if (meta == null && mj.isFile()) meta = parseJson(readText(mj, 128 * 1024));
        if (meta == null) {
            File yml = new File(dir, "metadata.yaml");
            if (yml.isFile()) meta = parseYaml(readText(yml, 128 * 1024));
        }
        String id = dir.getName();
        String name = id;
        String ver = "";
        String desc = "";
        String author = "";
        if (meta != null) {
            String n = meta.optString("name", "");
            if (n.length() > 0) name = n;
            ver = meta.optString("version", "");
            desc = meta.optString("desc", "");
            if (desc.length() == 0) desc = meta.optString("description", "");
            author = meta.optString("author", "");
        }
        if (name.length() == 0) name = id;
        if (ver.length() == 0) ver = "未知";
        if (desc.length() > 90) desc = desc.substring(0, 90) + "…";
        it.put("name", name);
        it.put("ver", ver);
        it.put("desc", desc);
        it.put("author", author);
    }

    private static JSONObject parseJson(String s) {
        if (s == null) return null;
        try {
            return new JSONObject(s);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * metadata.yaml 只是「键: 值」的平铺表单，没有嵌套，不值得为它引一个 YAML 库
     * （引了要改 build.gradle，违反「不新增依赖」）。多行字符串、列表一律取第一行。
     */
    private static JSONObject parseYaml(String s) {
        JSONObject o = new JSONObject();
        if (s == null) return o;
        String[] lines = s.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.length() == 0) continue;
            char c = line.charAt(0);
            if (c == ' ' || c == '\t' || c == '#') continue;
            int p = line.indexOf(':');
            if (p <= 0) continue;
            String k = line.substring(0, p).trim();
            String v = line.substring(p + 1).trim();
            if (v.endsWith("\"")) v = v.substring(0, v.length() - 1);
            if (v.startsWith("\"")) v = v.substring(1);
            try {
                o.put(k, v);
            } catch (Throwable ignored) {
            }
        }
        return o;
    }

    /* ============================== 启停状态 ============================== */

    private JSONArray inactivated() {
        try {
            File f = new File(dataDir(), "shared_preferences.json");
            if (!f.isFile()) return new JSONArray();
            JSONObject root = new JSONObject(readText(f, 4 * 1024 * 1024));
            JSONArray a = root.optJSONArray("inactivated_plugins");
            return (a == null) ? new JSONArray() : a;
        } catch (Throwable t) {
            return new JSONArray();
        }
    }

    private static boolean isOff(JSONArray off, String id) {
        for (int i = 0; off != null && i < off.length(); i++) {
            if (matchModule(off.optString(i, ""), id)) return true;
        }
        return false;
    }

    /* ============================== 小工具 ============================== */

    private static String safeId(String s) {
        if (s == null) return "";
        String v = s.trim();
        if (v.length() == 0 || v.length() > 96) return "";
        if (v.equals(".") || v.equals("..")) return "";
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.';
            if (!ok) return "";
        }
        return v;
    }

    private static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String str(Throwable t) {
        if (t == null) return "";
        String m = t.getMessage();
        return (m == null || m.length() == 0) ? t.toString() : m;
    }

    private static String fail(String m) {
        return QqbotBridge.failJson(m);
    }

    private static JSONArray tailArray() {
        JSONArray a = new JSONArray();
        synchronized (TAIL) {
            for (int i = 0; i < TAIL.size(); i++) a.put(TAIL.get(i));
        }
        return a;
    }

    private static void pushTail(String line) {
        if (line == null) return;
        String s = line.trim();
        if (s.length() == 0) return;
        if (s.length() > 300) s = s.substring(0, 300);
        synchronized (TAIL) {
            TAIL.add(s);
            while (TAIL.size() > TAIL_MAX) TAIL.remove(0);
        }
    }

    private static boolean isRunning() {
        return "run".equals(runState);
    }

    private static String readText(File f, int max) {
        if (f == null || !f.isFile()) return "";
        FileInputStream in = null;
        try {
            long len = f.length();
            in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int got = 0;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                got += n;
                if (got >= max) break;
            }
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        } finally {
            closeQuiet(in);
        }
    }

    private static boolean writeText(File f, String s) {
        FileOutputStream out = null;
        try {
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            // 先写临时文件再改名：中途被杀也不会留下半截配置，AstrBot 读不到就崩
            File tmp = new File(f.getAbsolutePath() + ".tmp");
            out = new FileOutputStream(tmp);
            out.write(s.getBytes("UTF-8"));
            out.flush();
            closeQuiet(out);
            out = null;
            if (f.isFile() && !f.delete()) return false;
            return tmp.renameTo(f);
        } catch (Throwable t) {
            return false;
        } finally {
            closeQuiet(out);
        }
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private static void closeQuiet(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }

    /** 只对官方注册表用：本机控制台以外唯一的联网点，超时短、失败就当没有。 */
    private static String fetch(String url, int timeoutMs) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(timeoutMs);
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", "AstrBotCandle");
            c.setRequestProperty("Accept", "application/json");
            InputStream in = (c.getResponseCode() >= 400) ? c.getErrorStream() : c.getInputStream();
            try {
                return readAll(in);
            } finally {
                closeQuiet(in);
            }
        } finally {
            try {
                if (c != null) c.disconnect();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 从一段输出里找最后一行 JSON（脚本只打一行结果，前面都是进度）。 */
    private static JSONObject lastJson(String out) {
        if (out == null) return null;
        String[] lines = out.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String s = lines[i].trim();
            if (s.length() == 0 || s.charAt(0) != '{') continue;
            JSONObject o = parseJson(s);
            if (o != null) return o;
        }
        return null;
    }

    /** 读文件末尾 n 行；日志可能几百 K，只读最后 64K。 */
    private static List<String> tailFile(File f, int n) {
        ArrayList<String> out = new ArrayList<String>();
        if (f == null || !f.isFile()) return out;
        java.io.RandomAccessFile raf = null;
        try {
            raf = new java.io.RandomAccessFile(f, "r");
            long len = raf.length();
            int want = 64 * 1024;
            long from = len - want;
            if (from < 0) from = 0;
            raf.seek(from);
            byte[] buf = new byte[(int) (len - from)];
            raf.readFully(buf);
            String s = new String(buf, "UTF-8");
            String[] lines = s.split("\n");
            int start = Math.max(0, lines.length - n);
            for (int i = start; i < lines.length; i++) {
                String line = lines[i].replace("\r", "").trim();
                if (line.length() > 0) out.add(line);
            }
        } catch (Throwable t) {
        } finally {
            closeQuiet(raf);
        }
        return out;
    }

    private File newestAstrLog() {
        try {
            File dir = new File(dataDir(), "logs");
            File[] fs = dir.listFiles();
            if (fs == null) return null;
            File best = null;
            for (int i = 0; i < fs.length; i++) {
                File f = fs[i];
                if (f == null || !f.isFile() || !f.getName().endsWith(".log")) continue;
                if (best == null || f.lastModified() > best.lastModified()) best = f;
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    /* ============================== 后台任务 ============================== */

    /** 文件名排序，保证列表稳定（AstrBot 目录顺序跟文件系统有关，不能直接用）。 */
    private static final class ByFile implements Comparator<File> {
        public int compare(File a, File b) {
            String x = (a == null) ? "" : a.getName();
            String y = (b == null) ? "" : b.getName();
            return x.compareToIgnoreCase(y);
        }
    }

    /** 装插件：流式读脚本输出，把 [NN] 行翻译成进度，结果写回静态字段给 poll()。 */
    private static final class InstallTask implements Runnable {
        private final File filesDir;
        private final String id;
        private final String repo;

        InstallTask(File filesDir, String id, String repo) {
            this.filesDir = filesDir;
            this.id = id;
            this.repo = repo;
        }

        public void run() {
            Process p = null;
            try {
                File usr = QqbotShell.usrDir(filesDir);
                File bash = new File(usr, "bin/bash");
                File script = new File(usr, "opt/qqbot/pluginhub.sh");
                if (!bash.isFile()) throw new Exception("沙盒还没装好，先完成部署");
                File home = QqbotShell.homeDir(filesDir);
                ProcessBuilder pb = new ProcessBuilder(bash.getAbsolutePath(), "-c",
                        "bash " + q(script.getAbsolutePath()) + " clone " + q(id) + " " + q(repo));
                pb.directory(home);
                pb.redirectErrorStream(true);
                Map<String, String> env = pb.environment();
                env.clear();
                // 与 QqbotShell.run 同一套环境：沙盒里的脚本默认 PATH 只有 usr/bin
                env.put("PREFIX", usr.getAbsolutePath());
                env.put("HOME", home.getAbsolutePath());
                env.put("PATH", new File(usr, "bin").getAbsolutePath());
                env.put("TMPDIR", new File(usr, "tmp").getAbsolutePath());
                env.put("TERM", "xterm-256color");
                env.put("LANG", "C.UTF-8");
                env.put("SHELL", bash.getAbsolutePath());
                env.put("QQBOT_SANDBOX_ENTERED", "1");
                p = pb.start();

                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                long deadline = System.currentTimeMillis() + INSTALL_TIMEOUT_SEC * 1000L;
                String line;
                boolean okEnd = false;
                while (true) {
                    line = r.readLine();
                    if (line == null) break;
                    pushTail(line);
                    appendLog(home, line);
                    String t = line.trim();
                    if (t.startsWith("{")) {
                        JSONObject o = parseJson(t);
                        if (o != null) {
                            okEnd = o.optBoolean("ok", false);
                            if (!okEnd) runMsg = o.optString("err", "安装失败");
                        }
                    } else if (t.startsWith("[")) {
                        int p2 = t.indexOf(']');
                        if (p2 > 1) {
                            try {
                                int v = Integer.parseInt(t.substring(1, p2).trim());
                                if (v >= 0 && v <= 100) runPct = v;
                            } catch (Throwable ignored) {
                            }
                            String m = t.substring(p2 + 1).trim();
                            if (m.length() > 0) runMsg = m;
                        }
                    }
                    if (System.currentTimeMillis() > deadline) throw new Exception("装太久了，先停下");
                }
                int rc = p.waitFor();
                if (okEnd && rc == 0) {
                    runPct = 100;
                    runMsg = "装好了，点重载让它生效";
                    runState = "done";
                } else {
                    runState = "err";
                    if (runMsg.length() == 0) runMsg = "下载失败，看看日志";
                }
            } catch (Throwable t) {
                runState = "err";
                runMsg = "安装失败：" + str(t);
                pushTail(runMsg);
            } finally {
                if (p != null) {
                    try {
                        p.destroy();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        private void appendLog(File home, String line) {
            FileOutputStream out = null;
            try {
                File f = new File(QqbotShell.qqbotDir(filesDir), "cache/plugins-install.log");
                File dir = f.getParentFile();
                if (dir != null && !dir.isDirectory()) dir.mkdirs();
                out = new FileOutputStream(f, true);
                out.write((line + "\n").getBytes("UTF-8"));
            } catch (Throwable ignored) {
            } finally {
                closeQuiet(out);
            }
        }
    }

    /** 后台热重载：save()/enable() 里顺手调，失败了也不影响「已保存」这个结论。 */
    private final class ReloadTask implements Runnable {
        private final String id;

        ReloadTask(String id) {
            this.id = id;
        }

        public void run() {
            panelReload(id);
        }
    }

    /** 重启 AstrBot：先停沙盒，等 proot 真没了再起，否则新进程会抢不到端口。 */
    private static final class RestartTask implements Runnable {
        private final File filesDir;

        RestartTask(File filesDir) {
            this.filesDir = filesDir;
        }

        public void run() {
            try {
                File usr = QqbotShell.usrDir(filesDir);
                File home = QqbotShell.homeDir(filesDir);
                File qq = QqbotShell.qqbotDir(filesDir);
                File stop = new File(usr, "opt/qqbot/stop-sandbox.sh");
                File start = new File(usr, "opt/qqbot/start-sandbox.sh");
                if (!stop.isFile() || !start.isFile()) return;
                QqbotShell.run(filesDir, "bash " + q(stop.getAbsolutePath()), 180);
                File pid = new File(qq, "supervisor.pid");
                for (int i = 0; i < 20 && QqbotShell.pidAlive(pid, "proot"); i++) {
                    try {
                        Thread.sleep(500);
                    } catch (Throwable ignored) {
                    }
                }
                String cmd = "mkdir -p " + q(new File(qq, "logs").getAbsolutePath())
                        + "; nohup setsid bash " + q(start.getAbsolutePath())
                        + " > " + q(new File(qq, "logs/supervisor.log").getAbsolutePath())
                        + " 2>&1 < /dev/null & sleep 4; echo ok";
                QqbotShell.run(filesDir, cmd, 240);
            } catch (Throwable ignored) {
            }
        }
    }
}
