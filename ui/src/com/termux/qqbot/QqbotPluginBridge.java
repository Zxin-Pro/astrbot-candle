package com.termux.app;

import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 插件管理桥（页面：assets/qqbot/plugins.html）。
 *
 * 三件事：列已装插件、拉市场列表、装/停/卸/重载 + 改配置。
 *
 * 为什么装插件不走 AstrBot 的 HTTP 接口：那套接口全要控制台登录态（JWT），而控制台
 * 密码在新版是 pbkdf2 存储，App 侧只有 run-astrbot.sh 写的初始口令，用户改过就登不进去。
 * 所以这里分两条路：
 *   1) 控制台活着且能登录：启停/卸载/重载直接走 /api/plugin/*，改完立刻生效；
 *   2) 登录不上或没在跑：列插件退化成扫 data/plugins，安装退化成自己下 zip 解压，
 *      启停/重载则明确告诉用户「要它跑起来才能改」，不假装成功。
 *
 * 目录口径（和 AstrCfg 一致，别自己拼路径）：
 *   data     = <files>/home/ubuntu/opt/AstrBot/data
 *   plugins  = data/plugins        （沙盒内 /opt/AstrBot/data/plugins）
 *   config   = data/config         （插件配置 <root_dir_name>_config.json）
 *
 * d8 不接受匿名内部类 / lambda，本文件不出现。
 */
public class QqbotPluginBridge {

    /** 市场注册表：AstrBot 自己用的那两份，见 plugin_service.py:1064-1069。 */
    private static final String[] REGISTRY_URLS = new String[]{
            "https://api.soulter.top/astrbot/plugins",
            "https://github.com/AstrBotDevs/AstrBot_Plugins_Collection/raw/refs/heads/main/plugin_cache_original.json"
    };

    private final QqbotActivity act;

    /** 控制台登录态：10 分钟内复用，401 时丢一次重登。 */
    private static volatile String token = "";
    private static volatile long tokenAt = 0L;

    /** 市场：state=idle|running|ok|fail；plugins 是归一化后的数组字符串。 */
    private static volatile String marketState = "idle";
    private static volatile String marketPlugins = "[]";
    private static volatile String marketErr = "";

    /** 安装/启停/卸载/写配置共用一个任务槽，前端轮询 taskPoll()。 */
    private static volatile String taskState = "idle";
    private static volatile String taskAction = "";
    private static volatile String taskMsg = "";

    private static final Object MARKET_LOCK = new Object();
    private static final Object TASK_LOCK = new Object();

    public QqbotPluginBridge(QqbotActivity act) {
        this.act = act;
    }

    /* ======================= 路径 ======================= */

    private File dataDir() {
        return AstrCfg.cfgFile(act.filesDirPub()).getParentFile();
    }

    private File pluginsDir() {
        return new File(dataDir(), "plugins");
    }

    private File configDir() {
        return new File(dataDir(), "config");
    }

    /** 我们自己的市场缓存：AstrBot 的 data/plugins.json 只在它跑过之后才有。 */
    private File marketCache() {
        return new File(act.qqbotDir(), "plugins-market.json");
    }

    private int port() {
        return act.astrbotPort();
    }

    private String root() {
        return "http://127.0.0.1:" + port();
    }

    /* ======================= 控制台 HTTP ======================= */

    private String token() throws Exception {
        long now = System.currentTimeMillis();
        String t = token;
        if (t.length() > 0 && now - tokenAt < 600000L) return t;
        String nt = AstrHot.login(root());
        token = nt;
        tokenAt = now;
        return nt;
    }

    /** 带登录态的调用；401 丢掉旧令牌重登一次（用户可能刚改过密码后重启过控制台）。 */
    private AstrHot.Resp authed(String path, String method, String body) throws Exception {
        String t = token();
        AstrHot.Resp r = AstrHot.send(root() + path, method, body, t);
        if (r.code == 401 || r.code == 403) {
            token = "";
            t = token();
            r = AstrHot.send(root() + path, method, body, t);
        }
        return r;
    }

    /** 控制台是否活着（400ms 探测，别把页面卡在 6s 的连接超时上）。 */
    private boolean consoleUp() {
        try {
            return act.portOpen(port());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 解析 {"status":"ok|error|warning","message":…,"data":…}，失败抛带人话的异常。 */
    private static JSONObject serviceResult(AstrHot.Resp r, String what) throws Exception {
        String body = (r.body == null) ? "" : r.body;
        String msg = "";
        try {
            JSONObject o = new JSONObject(body);
            msg = o.optString("message", "");
            if (r.code >= 200 && r.code < 300) {
                String st = o.optString("status", "ok");
                if (!"error".equals(st)) return o;
            }
        } catch (Throwable ignored) {
        }
        if (msg.length() == 0) {
            msg = body.length() > 160 ? body.substring(0, 160) : body;
        }
        throw new Exception(what + "失败（HTTP " + r.code + "）" + (msg.length() > 0 ? "：" + msg : ""));
    }

    /* ======================= 已装插件 ======================= */

    /**
     * 已装插件列表。
     * 控制台活着优先问它（enabled 是真实的运行态）；否则扫盘，enabled 一律 true 并带 note，
     * 因为「磁盘上有这个插件」和「AstrBot 把它启用了」是两件事，扫盘时不能替它下结论。
     */
    @JavascriptInterface
    public String installed() {
        JSONObject out = new JSONObject();
        try {
            JSONArray arr = new JSONArray();
            boolean fromConsole = false;
            if (consoleUp()) {
                try {
                    AstrHot.Resp r = authed("/api/plugin/get", "GET", null);
                    if (r.code == 200) {
                        JSONObject o = new JSONObject(r.body);
                        JSONArray d = o.optJSONArray("data");
                        if (d == null) {
                            JSONObject data = o.optJSONObject("data");
                            d = (data == null) ? null : data.optJSONArray("plugins");
                        }
                        if (d != null) {
                            for (int i = 0; i < d.length(); i++) {
                                JSONObject p = d.optJSONObject(i);
                                if (p == null) continue;
                                arr.put(pluginRow(p.optString("name", ""), p.optString("version", ""),
                                        p.optString("desc", ""), p.optString("author", ""),
                                        p.optBoolean("activated", true),
                                        p.optString("root_dir_name", "")));
                            }
                            fromConsole = true;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            if (!fromConsole) {
                File dir = pluginsDir();
                File[] subs = dir.listFiles();
                if (subs != null) {
                    for (int i = 0; i < subs.length; i++) {
                        File sub = subs[i];
                        if (sub == null || !sub.isDirectory()) continue;
                        String dn = sub.getName();
                        if (dn.startsWith(".") || dn.startsWith("__")) continue;
                        JSONObject meta = readMetadata(new File(sub, "metadata.yaml"));
                        arr.put(pluginRow(firstNonEmpty(meta.optString("name", ""), dn),
                                meta.optString("version", ""),
                                firstNonEmpty(meta.optString("desc", ""), meta.optString("description", "")),
                                meta.optString("author", ""), true, dn));
                    }
                }
                out.put("note", "AstrBot 没在运行，启停状态未知");
            }
            out.put("ok", true);
            out.put("plugins", arr);
            out.put("dir", "/opt/AstrBot/data/plugins");
            return out.toString();
        } catch (Throwable t) {
            return errJson("读取插件列表失败", t);
        }
    }

    private JSONObject pluginRow(String name, String version, String desc, String author,
                                 boolean enabled, String dirName) throws Exception {
        JSONObject row = new JSONObject();
        row.put("name", name);
        row.put("version", version);
        row.put("desc", desc);
        row.put("author", author);
        row.put("enabled", enabled);
        row.put("dir", dirName);
        row.put("hasConfig", configFile(dirName).isFile());
        /*
         * beta.3 起详情页要用到这三项：仓库地址（拉 README）、有没有自带 WebUI
         * （决定「打开 WebUI」按钮显不显示）、有哪些页面。全从磁盘读，不额外发请求。
         */
        File dir = (dirName.length() == 0) ? null : new File(pluginsDir(), dirName);
        JSONObject meta = (dir == null) ? new JSONObject() : readMetadata(new File(dir, "metadata.yaml"));
        List<String> pages = (dir == null) ? new ArrayList<String>() : pageNames(dir);
        row.put("repo", meta.optString("repo", ""));
        row.put("hasWebui", pages.size() > 0);
        row.put("pages", new JSONArray(pages));
        synchronized (ENABLED_CACHE) {
            ENABLED_CACHE.put(name, Boolean.valueOf(enabled));
        }
        return row;
    }

    /** 插件配置文件：data/config/<root_dir_name>_config.json（star_manager.py:1164-1169）。 */
    private File configFile(String dirName) {
        return new File(configDir(), dirName + "_config.json");
    }

    /**
     * metadata.yaml 的最小解析：只认顶层的 key: value。
     * 不引 YAML 库的理由——本文件只需要 name/desc/author/version/repo 这五个标量，
     * 而插件元数据里真正嵌套的只有 i18n，跳过缩进行就够；缺库比多解析错更安全。
     */
    private static JSONObject readMetadata(File f) {
        JSONObject o = new JSONObject();
        if (f == null || !f.isFile()) return o;
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            String text = readAll(in);
            String[] lines = text.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (line.length() == 0) continue;
                char c0 = line.charAt(0);
                if (c0 == ' ' || c0 == '\t' || c0 == '-' || c0 == '#') continue;
                int p = line.indexOf(':');
                if (p <= 0) continue;
                String k = line.substring(0, p).trim();
                String v = line.substring(p + 1).trim();
                if (v.length() >= 2) {
                    char a = v.charAt(0);
                    char b = v.charAt(v.length() - 1);
                    if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) {
                        v = v.substring(1, v.length() - 1);
                    }
                }
                if (k.length() == 0 || v.length() == 0) continue;
                if (o.has(k)) continue;
                o.put(k, v);
            }
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(in);
        }
        return o;
    }

    /* ======================= 市场 ======================= */

    /**
     * 拉市场列表（后台线程）。force=true 忽略缓存重新拉。
     * 拉不到但有旧缓存时照常返回数据，只在 error 里说明「可能不是最新的」——
     * 用户打开这一屏是为了装插件，不是看错误页。
     */
    @JavascriptInterface
    public void market(final boolean force) {
        synchronized (MARKET_LOCK) {
            if ("running".equals(marketState)) return;
            marketState = "running";
            marketErr = "";
        }
        Thread th = new Thread(new MarketTask(force), "qqbot-plugin-market");
        th.setDaemon(true);
        th.start();
    }

    @JavascriptInterface
    public String marketPoll() {
        JSONObject out = new JSONObject();
        try {
            out.put("state", marketState);
            out.put("plugins", new JSONArray(marketPlugins));
            out.put("error", marketErr);
            return out.toString();
        } catch (Throwable t) {
            return errJson("读取市场状态失败", t);
        }
    }

    /** 后台线程：取注册表 → 归一化 → 落缓存。 */
    private final class MarketTask implements Runnable {
        private final boolean force;

        MarketTask(boolean force) {
            this.force = force;
        }

        public void run() {
            String body = "";
            String err = "";
            try {
                String astrbotCache = readText(new File(dataDir(), "plugins.json"));
                if (!force && astrbotCache.length() > 0) {
                    body = astrbotCache;
                } else {
                    body = fetchRegistry();
                    if (body.length() == 0) {
                        String mine = readText(marketCache());
                        if (mine.length() > 0) {
                            body = mine;
                            err = "网络不通，显示的是上次缓存的列表";
                        } else if (astrbotCache.length() > 0) {
                            body = astrbotCache;
                            err = "网络不通，显示的是 AstrBot 缓存的列表";
                        }
                    } else {
                        writeText(marketCache(), body);
                    }
                }
                if (body.length() == 0) {
                    synchronized (MARKET_LOCK) {
                        marketState = "fail";
                        marketErr = "拿不到插件市场列表：检查手机网络后重试（市场在国内偶尔会慢）";
                    }
                    return;
                }
                JSONArray normalized = normalizeRegistry(body);
                synchronized (MARKET_LOCK) {
                    marketPlugins = normalized.toString();
                    marketState = "ok";
                    marketErr = err;
                }
            } catch (Throwable t) {
                synchronized (MARKET_LOCK) {
                    marketState = "fail";
                    marketErr = "读取市场列表失败：" + shortOf(t);
                }
            }
        }
    }

    /** 依次试两份注册表；哪份 200 且非空就用哪份。 */
    private static String fetchRegistry() {
        for (int i = 0; i < REGISTRY_URLS.length; i++) {
            String s = httpGet(REGISTRY_URLS[i], 15000);
            if (s.length() == 0) continue;
            String trimmed = s.trim();
            if (trimmed.length() > 2) return trimmed;
        }
        return "";
    }

    /**
     * 归一化成前端要的形状：id/name/desc/author/version/stars/repo/download_url/pinned/installed。
     *
     * 注册表历史上有三种形状，都得认：{id: {...}} 的字典、{"plugins":[...]}、裸数组。
     * id 取 market_plugin_id，没有就退回 repo、再退回 name —— 和 dashboard 的
     * getMarketPluginKey（useExtensionPage.js:694-700）保持同一套优先级，否则同一个插件
     * 在两边会显示成两条。
     */
    private JSONArray normalizeRegistry(String body) throws Exception {
        List<JSONObject> list = new ArrayList<JSONObject>();
        JSONObject obj = null;
        JSONArray arr = null;
        try {
            obj = new JSONObject(body);
        } catch (Throwable ignored) {
            obj = null;
        }
        if (obj != null) {
            arr = obj.optJSONArray("plugins");
            if (arr == null) {
                JSONArray keys = obj.names();
                if (keys != null) {
                    for (int i = 0; i < keys.length(); i++) {
                        String k = keys.optString(i, "");
                        Object v = obj.opt(k);
                        if (!(v instanceof JSONObject)) continue;
                        JSONObject e = new JSONObject(((JSONObject) v).toString());
                        if (str(e, "market_plugin_id").length() == 0 && str(e, "id").length() == 0) {
                            e.put("market_plugin_id", k);
                        }
                        list.add(e);
                    }
                }
            }
        } else {
            arr = new JSONArray(body);
        }
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject e = arr.optJSONObject(i);
                if (e != null) list.add(e);
            }
        }
        Collections.sort(list, new ByStars());

        Set<String> have = installedDirs();
        JSONArray out = new JSONArray();
        for (int i = 0; i < list.size(); i++) {
            JSONObject e = list.get(i);
            String name = firstNonEmpty(str(e, "name"), str(e, "display_name"), str(e, "id"));
            String repo = str(e, "repo");
            String id = firstNonEmpty(str(e, "market_plugin_id"), repo, name);
            if (name.length() == 0 || id.length() == 0) continue;
            JSONObject row = new JSONObject();
            row.put("id", id);
            row.put("name", name);
            row.put("desc", firstNonEmpty(str(e, "desc"), str(e, "short_desc"), str(e, "description")));
            row.put("author", firstNonEmpty(str(e, "author"), ownerOf(repo)));
            row.put("version", str(e, "version"));
            row.put("stars", e.optInt("stars", 0));
            row.put("repo", repo);
            row.put("download_url", str(e, "download_url"));
            row.put("pinned", e.optBoolean("pinned", false));
            row.put("installed", have.contains(name) || have.contains(dirNameOf(repo, name)));
            out.put(row);
        }
        return out;
    }

    /** 装了的用目录名集合来表示：目录名就是 root_dir_name，别拿 metadata 里的 name 猜。 */
    private Set<String> installedDirs() {
        Set<String> set = new HashSet<String>();
        File[] subs = pluginsDir().listFiles();
        if (subs == null) return set;
        for (int i = 0; i < subs.length; i++) {
            File f = subs[i];
            if (f == null || !f.isDirectory()) continue;
            set.add(f.getName());
            JSONObject meta = readMetadata(new File(f, "metadata.yaml"));
            String n = meta.optString("name", "");
            if (n.length() > 0) set.add(n);
        }
        return set;
    }

    /** 置顶的排前面，其次按星数。没有 lambda，只能用 Comparator 实现类。 */
    private static final class ByStars implements Comparator<JSONObject> {
        public int compare(JSONObject a, JSONObject b) {
            boolean pa = a.optBoolean("pinned", false);
            boolean pb = b.optBoolean("pinned", false);
            if (pa != pb) return pa ? -1 : 1;
            int sa = a.optInt("stars", 0);
            int sb = b.optInt("stars", 0);
            if (sa != sb) return sa > sb ? -1 : 1;
            return 0;
        }
    }

    /* ======================= 安装 ======================= */

    /**
     * 装一个市场插件：下载 zip → 解压 → 落到 data/plugins/<目录名>/。
     * 不调 AstrBot 的 /api/plugin/install：那条路要登录态；自己装完再让它 reload 效果一样，
     * 而且解压路径完全可控（不会把插件多套一层目录）。
     */
    @JavascriptInterface
    public void install(final String id) {
        if (!beginTask("install")) return;
        Thread th = new Thread(new Runnable() {
            public void run() {
                String msg = "";
                boolean ok = false;
                try {
                    JSONObject entry = findMarket(id);
                    if (entry == null) throw new Exception("市场列表里找不到这个插件，先刷新一下市场");
                    String name = entry.optString("name", "");
                    String repo = entry.optString("repo", "");
                    String url = entry.optString("download_url", "");
                    if (url.length() == 0) url = archiveUrl(repo);
                    if (url.length() == 0) throw new Exception("这个插件没有下载地址（可能只给了仓库名）");
                    File zip = new File(marketCache().getParentFile(), "install.zip");
                    deleteTree(zip);
                    download(url, zip);
                    if (!zip.isFile() || zip.length() < 64) throw new Exception("下载下来的不是压缩包，可能被中转站拦了");
                    String dirName = dirNameOf(repo, name);
                    File target = new File(pluginsDir(), dirName);
                    if (target.exists()) throw new Exception("已经装过同名插件了（目录 data/plugins/" + dirName + "）");
                    File tmp = new File(marketCache().getParentFile(), "unzip");
                    deleteTree(tmp);
                    if (!tmp.mkdirs()) throw new Exception("解压目录建不出来");
                    String top = unzip(zip, tmp);
                    File src = top.length() > 0 ? new File(tmp, top) : tmp;
                    if (!new File(src, "metadata.yaml").isFile()) {
                        throw new Exception("这个压缩包里没有 metadata.yaml，不像是 AstrBot 插件");
                    }
                    if (!pluginsDir().isDirectory() && !pluginsDir().mkdirs()) {
                        throw new Exception("插件目录建不出来（沙盒还没部署好？）");
                    }
                    if (!src.renameTo(target)) {
                        copyTree(src, target);
                        deleteTree(src);
                    }
                    deleteTree(tmp);
                    deleteTree(zip);
                    ok = true;
                    msg = "已装好 " + name;
                    String reloaded = tryReload(clientName(target));
                    if (reloaded.length() > 0) msg = msg + "，" + reloaded;
                } catch (Throwable t) {
                    msg = friendlyInstallErr(t);
                }
                endTask(ok, msg);
            }
        }, "qqbot-plugin-install");
        th.setDaemon(true);
        th.start();
    }

    /** 市场行里按 id 找；找不到再按 repo/name 兜一层（老缓存可能没 id）。 */
    private JSONObject findMarket(String id) {
        try {
            JSONArray arr = new JSONArray(marketPlugins);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject e = arr.optJSONObject(i);
                if (e == null) continue;
                if (id.equals(e.optString("id", ""))) return e;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject e = arr.optJSONObject(i);
                if (e == null) continue;
                if (id.equals(e.optString("repo", "")) || id.equals(e.optString("name", ""))) return e;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 插件名（AstrBot 里的 name）→ 目录名。装在 data/plugins/<dir> 下时，AstrBot 用目录名当
     * root_dir_name，插件自己的 name 由 metadata 决定，两者可以不同。
     * 优先用仓库名的末段（github 的 zip 根目录就是它），没有再用插件名。
     */
    private static String dirNameOf(String repo, String name) {
        String or = ownerRepoOf(repo);
        String base = "";
        if (or.length() > 0) {
            int p = or.lastIndexOf('/');
            if (p >= 0 && p + 1 < or.length()) base = or.substring(p + 1);
        }
        if (base.length() == 0) base = name;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.') {
                sb.append(c);
            } else {
                sb.append('-');
            }
        }
        String s = sb.toString();
        while (s.startsWith("-")) s = s.substring(1);
        while (s.endsWith("-")) s = s.substring(0, s.length() - 1);
        if (s.length() == 0) s = "plugin";
        return s;
    }

    /** "owner/repo"（支持 https://github.com/owner/repo、带 .git、带 /tree/xxx）。 */
    private static String ownerRepoOf(String repo) {
        String s = (repo == null) ? "" : repo.trim();
        if (s.length() == 0) return "";
        int i = s.indexOf("github.com/");
        if (i >= 0) s = s.substring(i + "github.com/".length());
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        int t = s.indexOf("/tree/");
        if (t >= 0) s = s.substring(0, t);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".git")) s = s.substring(0, s.length() - 4);
        String[] parts = s.split("/");
        if (parts.length < 2) return "";
        String owner = parts[0].trim();
        String name = parts[1].trim();
        if (owner.length() == 0 || name.length() == 0) return "";
        return owner + "/" + name;
    }

    private static String ownerOf(String repo) {
        String or = ownerRepoOf(repo);
        int p = or.indexOf('/');
        return p > 0 ? or.substring(0, p) : "";
    }

    private static String archiveUrl(String repo) {
        String or = ownerRepoOf(repo);
        if (or.length() == 0) return "";
        return "https://github.com/" + or + "/archive/refs/heads/main.zip";
    }

    /* ======================= 启停 / 卸载 / 重载 ======================= */

    /** 启停。必须在控制台活着时做：状态存在 AstrBot 的 DB 里，停机改文件没用。 */
    @JavascriptInterface
    public void toggle(final String name, final boolean on) {
        if (!beginTask("toggle")) return;
        Thread th = new Thread(new Runnable() {
            public void run() {
                boolean ok = false;
                String msg = "";
                try {
                    if (name == null || name.trim().length() == 0) throw new Exception("插件名是空的");
                    if (!consoleUp()) throw new Exception("AstrBot 没在运行：启停要先把它跑起来，改完立刻生效");
                    JSONObject body = new JSONObject();
                    body.put("name", name.trim());
                    AstrHot.Resp r = authed(on ? "/api/plugin/on" : "/api/plugin/off", "POST", body.toString());
                    JSONObject o = serviceResult(r, on ? "启用插件" : "停用插件");
                    ok = true;
                    msg = firstNonEmpty(o.optString("message", ""), on ? "已启用" : "已停用");
                } catch (Throwable t) {
                    msg = friendlyInstallErr(t);
                }
                endTask(ok, msg);
            }
        }, "qqbot-plugin-toggle");
        th.setDaemon(true);
        th.start();
    }

    /**
     * 卸载。控制台活着走接口（会顺带清掉安装来源记录）；不在跑就直接删目录，
     * 但不动 data/config 下的配置——用户重装同一插件时配置还在，不丢设置。
     */
    @JavascriptInterface
    public void uninstall(final String name) {
        if (!beginTask("uninstall")) return;
        Thread th = new Thread(new Runnable() {
            public void run() {
                boolean ok = false;
                String msg = "";
                try {
                    String n = (name == null) ? "" : name.trim();
                    if (n.length() == 0) throw new Exception("插件名是空的");
                    if (consoleUp()) {
                        try {
                            JSONObject body = new JSONObject();
                            body.put("name", n);
                            body.put("delete_config", false);
                            body.put("delete_data", false);
                            AstrHot.Resp r = authed("/api/plugin/uninstall", "POST", body.toString());
                            JSONObject o = serviceResult(r, "卸载插件");
                            ok = true;
                            msg = firstNonEmpty(o.optString("message", ""), "已卸载");
                        } catch (Throwable t) {
                            if (token.length() > 0 && consoleUp()) throw t;
                        }
                    }
                    if (!ok) {
                        File dir = findPluginDir(n);
                        if (dir == null) throw new Exception("找不到插件目录（可能已经删掉了）");
                        deleteTree(dir);
                        ok = true;
                        msg = "已删除目录 data/plugins/" + dir.getName() + "，重启 AstrBot 后生效";
                    }
                } catch (Throwable t) {
                    msg = friendlyInstallErr(t);
                }
                endTask(ok, msg);
            }
        }, "qqbot-plugin-uninstall");
        th.setDaemon(true);
        th.start();
    }

    /** 重载：装完/改完配置后让插件重新加载，不重启整个 AstrBot。 */
    @JavascriptInterface
    public void reloadPlugin(final String name) {
        if (!beginTask("reload")) return;
        Thread th = new Thread(new Runnable() {
            public void run() {
                boolean ok = false;
                String msg = "";
                try {
                    if (!consoleUp()) throw new Exception("AstrBot 没在运行：重载要它跑起来才有意义，重启后会自动加载");
                    JSONObject body = new JSONObject();
                    String n = (name == null) ? "" : name.trim();
                    if (n.length() > 0) body.put("name", n);
                    AstrHot.Resp r = authed("/api/plugin/reload", "POST", body.toString());
                    JSONObject o = serviceResult(r, "重载插件");
                    ok = true;
                    msg = firstNonEmpty(o.optString("message", ""), "已重载");
                } catch (Throwable t) {
                    msg = friendlyInstallErr(t);
                }
                endTask(ok, msg);
            }
        }, "qqbot-plugin-reload");
        th.setDaemon(true);
        th.start();
    }

    @JavascriptInterface
    public String taskPoll() {
        JSONObject out = new JSONObject();
        try {
            out.put("state", taskState);
            out.put("action", taskAction);
            out.put("msg", taskMsg);
            return out.toString();
        } catch (Throwable t) {
            return errJson("读取任务状态失败", t);
        }
    }

    private boolean beginTask(String action) {
        synchronized (TASK_LOCK) {
            if ("running".equals(taskState)) {
                taskState = "fail";
                taskAction = action;
                taskMsg = "上一个操作还没做完，等它结束再试";
                return false;
            }
            taskState = "running";
            taskAction = action;
            taskMsg = "";
            return true;
        }
    }

    private void endTask(boolean ok, String msg) {
        synchronized (TASK_LOCK) {
            taskState = ok ? "ok" : "fail";
            taskMsg = (msg == null) ? "" : msg;
        }
    }

    /** 只给 install 用：装完试着让 AstrBot 载入，失败不算安装失败。 */
    private String tryReload(String name) {
        try {
            if (!consoleUp()) return "重启 AstrBot 后生效";
            JSONObject body = new JSONObject();
            AstrHot.Resp r = authed("/api/plugin/reload", "POST", body.toString());
            if (r.code >= 200 && r.code < 300) return "已重载";
        } catch (Throwable ignored) {
        }
        return "重启 AstrBot 后生效";
    }

    /** 插件名或目录名 → 目录。 */
    private File findPluginDir(String name) {
        File[] subs = pluginsDir().listFiles();
        if (subs == null) return null;
        for (int i = 0; i < subs.length; i++) {
            File f = subs[i];
            if (f == null || !f.isDirectory()) continue;
            if (name.equals(f.getName())) return f;
        }
        for (int i = 0; i < subs.length; i++) {
            File f = subs[i];
            if (f == null || !f.isDirectory()) continue;
            JSONObject meta = readMetadata(new File(f, "metadata.yaml"));
            if (name.equals(meta.optString("name", ""))) return f;
        }
        return null;
    }

    /* ======================= 配置文件 ======================= */

    /**
     * 读插件配置。返回沙盒内的路径（给界面显示用，方便用户去文件管理器对照），
     * 但真正读的是宿主路径——两者是同一个文件。
     */
    @JavascriptInterface
    public String readConfig(String name) {
        JSONObject out = new JSONObject();
        try {
            File dir = findPluginDir(name);
            if (dir == null) {
                out.put("ok", false);
                out.put("error", "找不到这个插件（可能刚被删掉，刷新一下）");
                return out.toString();
            }
            File f = configFile(dir.getName());
            out.put("ok", true);
            out.put("path", "/opt/AstrBot/data/config/" + dir.getName() + "_config.json");
            out.put("text", f.isFile() ? readText(f) : "");
            out.put("exists", f.isFile());
            return out.toString();
        } catch (Throwable t) {
            return errJson("读取插件配置失败", t);
        }
    }

    /**
     * 写插件配置。先校验 JSON——插件加载时会直接 json.load 这个文件，写进去半截非法内容
     * 会让整个插件加载失败，比拒绝保存糟糕得多。原子写（tmp + rename），写一半断电也不会
     * 留下损坏文件。
     */
    @JavascriptInterface
    public void writeConfig(final String name, final String text) {
        if (!beginTask("config")) return;
        Thread th = new Thread(new Runnable() {
            public void run() {
                boolean ok = false;
                String msg = "";
                try {
                    String n = (name == null) ? "" : name.trim();
                    File dir = findPluginDir(n);
                    if (dir == null) throw new Exception("找不到这个插件（可能刚被删掉，刷新一下）");
                    String body = (text == null) ? "" : text.trim();
                    if (body.length() == 0) throw new Exception("配置是空的：至少要有 {} 两个大括号");
                    String pretty;
                    try {
                        pretty = new JSONObject(body).toString(2);
                    } catch (Throwable je) {
                        throw new Exception("JSON 格式不对：" + shortOf(je));
                    }
                    if (!configDir().isDirectory() && !configDir().mkdirs()) {
                        throw new Exception("配置目录建不出来（沙盒还没部署好？）");
                    }
                    File f = configFile(dir.getName());
                    writeTextAtomic(f, pretty);
                    ok = true;
                    msg = "已保存到 data/config/" + dir.getName() + "_config.json";
                    String r = reloadQuiet(dir.getName());
                    if (r.length() > 0) msg = msg + "，" + r;
                } catch (Throwable t) {
                    msg = friendlyInstallErr(t);
                }
                endTask(ok, msg);
            }
        }, "qqbot-plugin-config");
        th.setDaemon(true);
        th.start();
    }

    private String reloadQuiet(String dirName) {
        try {
            if (!consoleUp()) return "重启 AstrBot 后生效";
            JSONObject body = new JSONObject();
            AstrHot.Resp r = authed("/api/plugin/reload", "POST", body.toString());
            if (r.code >= 200 && r.code < 300) return "已重载";
        } catch (Throwable ignored) {
        }
        return "重启 AstrBot 后生效";
    }

    /** 内置 WebUI 的地址。token 留空：JWT 不适合塞进 iframe 地址，让用户在页面里登录。 */
    @JavascriptInterface
    public String dashboardUrl() {
        JSONObject out = new JSONObject();
        try {
            out.put("ok", true);
            out.put("url", root() + "/");
            out.put("token", "");
            out.put("up", consoleUp());
            return out.toString();
        } catch (Throwable t) {
            return errJson("读取控制台地址失败", t);
        }
    }

    /**
     * 用系统浏览器打开控制台（WebUI 页签的「新窗口打开」）。
     *
     * 契约之外的补充方法：iframe 在 WebView 里偶尔会因为 cookie / 键盘 / 新版
     * 控制台的前端特性卡住，留一个「出去用浏览器开」的出口。页面用
     * typeof AndroidPlugins.openExternal === "function" 判断，没有就不显示这个按钮，
     * 所以即使这里没注册也不会白屏。
     */
    @JavascriptInterface
    public String openExternal(String url) {
        try {
            String u = url == null ? "" : url.trim();
            if (u.length() == 0) return "链接为空";
            String low = u.toLowerCase(java.util.Locale.ROOT);
            // 和 QqbotBridge.openUrl 一样先在桥上挡 scheme：javascript:/intent:/file:
            // 都不能进 Intent，Activity 里还会再校验一次。
            if (!(low.startsWith("http://") || low.startsWith("https://"))) return "只支持 http 链接";
            String r = act.runOnUiSync(new UiTask(act, UiTask.OPENURL, u));
            if (r == null) return "无法打开链接";
            return (r.length() == 0) ? "ok" : r;
        } catch (Throwable t) {
            return "无法打开链接";
        }
    }

    /* ======================= 详情：信息 / README / WebUI / 更新 ======================= */

    /** name → 是否启用。installed() 每轮都会填，info() 直接查，避免在 JS 线程上再发一次 HTTP。 */
    private static final java.util.HashMap<String, Boolean> ENABLED_CACHE =
            new java.util.HashMap<String, Boolean>();

    /** README 缓存：key = repo + "|" + name，值是已经算好的结果 JSON。 */
    private static final java.util.HashMap<String, String> README_CACHE =
            new java.util.HashMap<String, String>();

    /** README 只试这几个分支/文件名：覆盖 99% 的仓库，剩下的交给 GitHub API 问默认分支。 */
    private static final String[] README_BRANCHES = new String[]{"main", "master"};
    private static final String[] README_NAMES =
            new String[]{"README.md", "readme.md", "README.MD", "README.markdown", "README.txt"};

    /**
     * 详情页顶部那块信息。同步方法：只读磁盘、不发请求，点进详情立刻能画。
     * enabled 取自 installed() 的缓存（控制台活着时是真实运行态；否则当作 true，靠 note 说明）。
     */
    @JavascriptInterface
    public String info(String name) {
        JSONObject out = new JSONObject();
        try {
            String n = (name == null) ? "" : name.trim();
            File dir = findPluginDir(n);
            if (dir == null) {
                out.put("ok", false);
                out.put("err", "找不到这个插件（可能刚被删掉，回列表刷新一下）");
                return out.toString();
            }
            JSONObject meta = readMetadata(new File(dir, "metadata.yaml"));
            List<String> pages = pageNames(dir);
            String pname = firstNonEmpty(meta.optString("name", ""), dir.getName());
            boolean enabled = true;
            synchronized (ENABLED_CACHE) {
                Boolean b = ENABLED_CACHE.get(pname);
                if (b != null) enabled = b.booleanValue();
            }
            out.put("ok", true);
            out.put("name", pname);
            out.put("dir", dir.getName());
            out.put("version", meta.optString("version", ""));
            out.put("author", meta.optString("author", ""));
            out.put("desc", firstNonEmpty(meta.optString("desc", ""), meta.optString("description", "")));
            out.put("repo", meta.optString("repo", ""));
            out.put("enabled", enabled);
            out.put("hasConfig", configFile(dir.getName()).isFile());
            out.put("hasWebui", pages.size() > 0);
            out.put("pages", new JSONArray(pages));
            out.put("configPath", "/opt/AstrBot/data/config/" + dir.getName() + "_config.json");
            return out.toString();
        } catch (Throwable t) {
            return errJson("读取插件信息失败", t);
        }
    }

    /**
     * 插件的 WebUI 地址。
     *
     * AstrBot 4.x 的插件页面不是独立站点，而是 dashboard（Vue SPA）里的一个路由：
     * dashboard/src/router/MainRoutes.ts:101 → "/plugin-page/:pluginName/:pageName"，
     * 页面文件在 data/plugins/<目录>/pages/<页面>/index.html（plugin_page_service.py:25,508）。
     * 所以这里只能给出「控制台端口 + 路由」，进浏览器后还要用控制台账号登录一次——
     * 这句话放在 hint 里，别让用户以为点了没反应。
     *
     * 端口读 cmd_config.json 的 dashboard.port（AstrBot 默认 6185），读不到退 bots.json 口径。
     */
    @JavascriptInterface
    public String webui(String name) {
        JSONObject out = new JSONObject();
        try {
            String n = (name == null) ? "" : name.trim();
            File dir = findPluginDir(n);
            if (dir == null) {
                out.put("ok", false);
                out.put("err", "找不到这个插件（可能刚被删掉，回列表刷新一下）");
                return out.toString();
            }
            List<String> pages = pageNames(dir);
            if (pages.size() == 0) {
                out.put("ok", false);
                out.put("err", "这个插件没有自带 WebUI（data/plugins/" + dir.getName() + "/pages/ 里没有页面）");
                return out.toString();
            }
            JSONObject meta = readMetadata(new File(dir, "metadata.yaml"));
            String pname = firstNonEmpty(meta.optString("name", ""), dir.getName());
            int p = dashboardPort();
            String base = "http://127.0.0.1:" + p;
            out.put("ok", true);
            out.put("port", p);
            out.put("page", pages.get(0));
            out.put("root", base + "/");
            out.put("url", base + "/plugin-page/" + urlEnc(pname) + "/" + urlEnc(pages.get(0)));
            out.put("hint", "WebUI 在控制台里：第一次打开要登录控制台账号（默认 astrbot / Astrbot123）");
            out.put("up", consoleUp());
            return out.toString();
        } catch (Throwable t) {
            return errJson("读取 WebUI 地址失败", t);
        }
    }

    /**
     * README。三种来源依次试：本机插件目录里的 README.* → 仓库 raw（main/master）→ GitHub API 问默认分支。
     *
     * 为什么异步：github raw 在国内经常要 10 秒才失败，同步等会把 JS 线程（还有同时轮询的
     * taskPoll/installed）一起卡住。所以第一次调用先回 state=loading，页面每 400ms 再调一次
     * 拿最终结果——结果缓存在内存里，重复调用不会重复发请求。
     */
    @JavascriptInterface
    public String readme(final String name, final String repo) {
        try {
            String n = (name == null) ? "" : name.trim();
            String r = (repo == null) ? "" : repo.trim();
            String key = r + "|" + n;
            synchronized (README_CACHE) {
                String hit = README_CACHE.get(key);
                if (hit != null) return hit;
            }
            JSONObject local = localReadme(n);
            if (local != null) {
                String s = local.toString();
                synchronized (README_CACHE) {
                    README_CACHE.put(key, s);
                }
                return s;
            }
            if (r.length() == 0) {
                JSONObject o = readmeFailQuiet("这个插件没有 README.md，metadata.yaml 里也没写仓库地址，看不到说明");
                String s = o.toString();
                synchronized (README_CACHE) {
                    README_CACHE.put(key, s);
                }
                return s;
            }
            JSONObject loading = new JSONObject();
            loading.put("ok", true);
            loading.put("state", "loading");
            loading.put("md", "");
            String s = loading.toString();
            synchronized (README_CACHE) {
                README_CACHE.put(key, s);
            }
            Thread th = new Thread(new ReadmeTask(key, r), "qqbot-plugin-readme");
            th.setDaemon(true);
            th.start();
            return s;
        } catch (Throwable t) {
            return errJson("读取 README 失败", t);
        }
    }

    /** 后台线程：从 GitHub 拉 README，结果写回缓存（页面下一次 readme() 就拿到 ok/fail）。 */
    private final class ReadmeTask implements Runnable {
        private final String key;
        private final String repo;

        ReadmeTask(String key, String repo) {
            this.key = key;
            this.repo = repo;
        }

        public void run() {
            JSONObject res;
            try {
                res = fetchReadme(repo);
            } catch (Throwable t) {
                res = readmeFailQuiet("拉 README 失败：" + shortOf(t));
            }
            synchronized (README_CACHE) {
                README_CACHE.put(key, res.toString());
            }
        }
    }

    private JSONObject fetchReadme(String repo) throws Exception {
        String or = ownerRepoOf(repo);
        if (or.length() == 0) return readmeFailQuiet("仓库地址看不懂：" + repo + "（要形如 owner/repo）");
        for (int b = 0; b < README_BRANCHES.length; b++) {
            for (int n = 0; n < README_NAMES.length; n++) {
                String url = "https://raw.githubusercontent.com/" + or + "/"
                        + README_BRANCHES[b] + "/" + README_NAMES[n];
                String md = httpGet(url, 12000);
                if (isReadmeBody(md)) {
                    JSONObject o = readmeOkQuiet(capReadme(md));
                    o.put("source", "github/" + README_BRANCHES[b]);
                    return o;
                }
            }
        }
        /*
         * 默认分支既不是 main 也不是 master 的仓库：问一次 GitHub API 要 default_branch。
         * 不登录也能用（限速 60 次/小时），只在这条兜底路径上才发。
         */
        String meta = httpGet("https://api.github.com/repos/" + or, 12000);
        if (meta != null && meta.length() > 0) {
            try {
                String br = new JSONObject(meta).optString("default_branch", "");
                if (br.length() > 0) {
                    for (int n = 0; n < README_NAMES.length; n++) {
                        String md = httpGet("https://raw.githubusercontent.com/" + or + "/"
                                + br + "/" + README_NAMES[n], 12000);
                        if (isReadmeBody(md)) {
                            JSONObject o = readmeOkQuiet(capReadme(md));
                            o.put("source", "github/" + br);
                            return o;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return readmeFailQuiet("这个仓库里没找到 README.md（" + or + "），可能作者没写说明");
    }

    /** httpGet 失败会给空串，404 会给一段 "404: Not Found" 文本，两种都不能当正文。 */
    private static boolean isReadmeBody(String md) {
        if (md == null) return false;
        String t = md.trim();
        if (t.length() == 0) return false;
        if (t.startsWith("404:") || t.startsWith("{\"message\":\"Not Found\"")) return false;
        return true;
    }

    private static String capReadme(String md) {
        if (md.length() <= 400000) return md;
        return md.substring(0, 400000) + "\n\n…（README 太长，后面的截掉了）";
    }

    /** 本机 README：插件目录里第一个 README.*（大小写都认）。读不到返回 null。 */
    private JSONObject localReadme(String name) {
        try {
            File dir = findPluginDir(name);
            if (dir == null) return null;
            File[] files = dir.listFiles();
            if (files == null) return null;
            for (int i = 0; i < files.length; i++) {
                File f = files[i];
                if (f == null || !f.isFile()) continue;
                String fn = f.getName().toLowerCase(java.util.Locale.ROOT);
                if (!fn.startsWith("readme")) continue;
                if (!(fn.endsWith(".md") || fn.endsWith(".markdown") || fn.endsWith(".txt"))) continue;
                String md = readText(f);
                if (md.trim().length() == 0) continue;
                JSONObject o = readmeOkQuiet(capReadme(md));
                o.put("source", "local");
                return o;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static JSONObject readmeOkQuiet(String md) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("state", "ok");
            o.put("md", md);
        } catch (Throwable ignored) {
        }
        return o;
    }

    private static JSONObject readmeFailQuiet(String err) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("state", "fail");
            o.put("md", "");
            o.put("err", err);
        } catch (Throwable ignored) {
        }
        return o;
    }

    /** 插件自带的 WebUI 页面：data/plugins/<目录>/pages/<页面>/index.html（plugin_page_service.py:25,508-538）。 */
    private static List<String> pageNames(File dir) {
        List<String> out = new ArrayList<String>();
        try {
            File[] subs = new File(dir, "pages").listFiles();
            if (subs == null) return out;
            for (int i = 0; i < subs.length; i++) {
                File sub = subs[i];
                if (sub == null || !sub.isDirectory()) continue;
                if (sub.getName().startsWith(".")) continue;
                if (!new File(sub, "index.html").isFile()) continue;
                out.add(sub.getName());
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 控制台端口：cmd_config.json 的 dashboard.port，读不到退 bots.json 的口径。 */
    private int dashboardPort() {
        try {
            File cfg = AstrCfg.cfgFile(act.filesDirPub());
            if (cfg.isFile()) {
                JSONObject root = new JSONObject(AstrCfg.readText(cfg));
                JSONObject dash = root.optJSONObject("dashboard");
                if (dash != null) {
                    int p = dash.optInt("port", 0);
                    if (p > 0) return p;
                }
            }
        } catch (Throwable ignored) {
        }
        return port();
    }

    private static String urlEnc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Throwable t) {
            return s;
        }
    }

    /**
     * 更新插件：按 metadata.yaml 里的 repo（没有就按市场列表里的 download_url）重新下 zip，
     * 解压校验后整目录替换，配置（data/config/<目录>_config.json）原样保留。
     *
     * 替换顺序是「旧目录先改名 → 新的放进去 → 删旧的」，中间任何一步失败都把旧的改回来：
     * 用户点更新不该把一个能用的插件换成半截目录。
     */
    @JavascriptInterface
    public void update(final String name) {
        if (!beginTask("update")) return;
        Thread th = new Thread(new Runnable() {
            public void run() {
                boolean ok = false;
                String msg = "";
                try {
                    String n = (name == null) ? "" : name.trim();
                    File dir = findPluginDir(n);
                    if (dir == null) throw new Exception("找不到这个插件（可能刚被删掉，回列表刷新一下）");
                    JSONObject meta = readMetadata(new File(dir, "metadata.yaml"));
                    String pname = firstNonEmpty(meta.optString("name", ""), dir.getName());
                    JSONObject entry = findMarket(pname);
                    String repo = firstNonEmpty(meta.optString("repo", ""),
                            (entry == null) ? "" : entry.optString("repo", ""));
                    String url = (entry == null) ? "" : entry.optString("download_url", "");
                    if (url.length() == 0) url = archiveUrl(repo);
                    if (url.length() == 0) {
                        throw new Exception("这个插件的 metadata.yaml 里没写 repo，也没在市场里找到下载地址，没法自动更新");
                    }
                    File tmpZip = new File(marketCache().getParentFile(), "update.zip");
                    deleteTree(tmpZip);
                    download(url, tmpZip);
                    if (!tmpZip.isFile() || tmpZip.length() < 64) {
                        throw new Exception("下载下来的不是压缩包，可能被中转站拦了");
                    }
                    File tmp = new File(marketCache().getParentFile(), "update-unzip");
                    deleteTree(tmp);
                    if (!tmp.mkdirs()) throw new Exception("解压目录建不出来");
                    String top = unzip(tmpZip, tmp);
                    File src = top.length() > 0 ? new File(tmp, top) : tmp;
                    if (!new File(src, "metadata.yaml").isFile()) {
                        throw new Exception("压缩包里没有 metadata.yaml，不像是 AstrBot 插件");
                    }
                    File old = new File(marketCache().getParentFile(), "update-old");
                    deleteTree(old);
                    if (!dir.renameTo(old)) {
                        throw new Exception("旧版本目录挪不动（AstrBot 可能正占用它）：先重启 AstrBot 再试");
                    }
                    boolean moved = false;
                    try {
                        moved = src.renameTo(dir);
                        if (!moved) {
                            copyTree(src, dir);
                            deleteTree(src);
                            moved = true;
                        }
                    } catch (Throwable mt) {
                        moved = false;
                    }
                    if (!moved) {
                        old.renameTo(dir);
                        throw new Exception("新版本没放进插件目录，已经还原成旧版本");
                    }
                    deleteTree(old);
                    deleteTree(tmp);
                    deleteTree(tmpZip);
                    ok = true;
                    msg = "已更新 " + pname;
                    String r = tryReload(clientName(dir));
                    if (r.length() > 0) msg = msg + "，" + r;
                } catch (Throwable t) {
                    msg = friendlyInstallErr(t);
                }
                endTask(ok, msg);
            }
        }, "qqbot-plugin-update");
        th.setDaemon(true);
        th.start();
    }

    /* ======================= 工具 ======================= */

    private static String str(JSONObject o, String k) {
        if (o == null) return "";
        String s = o.optString(k, "");
        return (s == null) ? "" : s.trim();
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && a.trim().length() > 0) return a.trim();
        return (b == null) ? "" : b.trim();
    }

    private static String firstNonEmpty(String a, String b, String c) {
        String s = firstNonEmpty(a, b);
        return (s.length() > 0) ? s : firstNonEmpty(c, "");
    }

    private static String shortOf(Throwable t) {
        String m = String.valueOf(t);
        if (m.length() > 180) m = m.substring(0, 180) + "…";
        return m;
    }

    /** 把底层异常翻成「下一步做什么」，别把 java 的栈丢给用户看。 */
    private static String friendlyInstallErr(Throwable t) {
        String m = String.valueOf(t);
        String low = m.toLowerCase();
        if (low.indexOf("unknownhost") >= 0 || low.indexOf("getaddrinfo") >= 0) {
            return "解析不到下载地址：检查手机网络后重试";
        }
        if (low.indexOf("timeout") >= 0 || low.indexOf("timed out") >= 0) {
            return "下载超时了：网络慢，换个网络再试（GitHub 国内经常慢）";
        }
        if (low.indexOf("404") >= 0) return "仓库地址不对或文件被删了（HTTP 404）：换一个插件，或去 WebUI 里装";
        if (low.indexOf("401") >= 0 || low.indexOf("403") >= 0) {
            return "控制台不让登录了（HTTP " + (low.indexOf("401") >= 0 ? "401" : "403")
                    + "）：你改过控制台密码，插件启停请到 WebUI 里操作";
        }
        if (m.startsWith("java.lang.Exception: ")) return m.substring("java.lang.Exception: ".length());
        return m;
    }

    private static String errJson(String what, Throwable t) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("error", what + "：" + shortOf(t));
        } catch (Throwable ignored) {
        }
        return o.toString();
    }

    /** 简单 GET；失败返回空串，由调用方决定下一句提示。 */
    private static String httpGet(String url, int timeoutMs) {
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(timeoutMs);
            c.setInstanceFollowRedirects(true);
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", "AstrBotCandle/plugins");
            c.setRequestProperty("Accept", "application/json, text/plain, */*");
            if (c.getResponseCode() != 200) return "";
            in = c.getInputStream();
            return readAll(in);
        } catch (Throwable t) {
            return "";
        } finally {
            closeQuietly(in);
            try {
                if (c != null) c.disconnect();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 下载到文件（跟随跳转；github 的 archive 会 302 到 codeload）。 */
    private static void download(String url, File dest) throws Exception {
        String cur = url;
        for (int hop = 0; hop < 4; hop++) {
            HttpURLConnection c = null;
            InputStream in = null;
            OutputStream out = null;
            try {
                c = (HttpURLConnection) new URL(cur).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(30000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", "AstrBotCandle/plugins");
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 307 || code == 308) {
                    String loc = c.getHeaderField("Location");
                    if (loc != null && loc.length() > 0) {
                        cur = loc;
                        continue;
                    }
                }
                if (code != 200) throw new Exception("下载失败（HTTP " + code + "）");
                in = c.getInputStream();
                out = new FileOutputStream(dest);
                byte[] buf = new byte[65536];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                    if (total > 40L * 1024 * 1024) throw new Exception("压缩包超过 40MB，中止");
                }
                out.flush();
                return;
            } finally {
                closeQuietly(out);
                closeQuietly(in);
                try {
                    if (c != null) c.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
        throw new Exception("下载跳转太多次了");
    }

    /**
     * 解压到 tmp，返回「根目录名」（GitHub 的 zip 都有个 owner-repo-branch/ 顶层目录）。
     * 顶层目录不唯一时返回空串，调用方把 tmp 本身当插件目录。
     * 逐项校验路径，别让 ../ 把文件写到沙盒外。
     */
    private static String unzip(File zip, File tmp) throws Exception {
        String tmppath = tmp.getCanonicalPath();
        Set<String> tops = new HashSet<String>();
        boolean any = false;
        ZipInputStream zin = null;
        try {
            zin = new ZipInputStream(new FileInputStream(zip), java.nio.charset.StandardCharsets.UTF_8);
            ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                if (name == null) continue;
                name = name.replace('\\', '/');
                while (name.startsWith("./")) name = name.substring(2);
                if (name.startsWith("/")) continue;
                if (name.startsWith("__MACOSX")) continue;
                int slash = name.indexOf('/');
                if (slash <= 0) {
                    if (!e.isDirectory()) tops.add("_" + name);
                    continue;
                }
                String top = name.substring(0, slash);
                tops.add(top);
                String rest = name.substring(slash + 1);
                if (rest.length() == 0) continue;
                if (rest.indexOf("..") >= 0) continue;
                File out = new File(tmp, top + "/" + rest);
                if (!out.getCanonicalPath().startsWith(tmppath)) continue;
                if (e.isDirectory()) {
                    if (!out.mkdirs() && !out.isDirectory()) throw new Exception("建目录失败：" + rest);
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                    throw new Exception("建目录失败：" + rest);
                }
                OutputStream os = null;
                try {
                    os = new FileOutputStream(out);
                    int n;
                    while ((n = zin.read(buf)) > 0) os.write(buf, 0, n);
                    os.flush();
                    any = true;
                } finally {
                    closeQuietly(os);
                }
            }
        } finally {
            closeQuietly(zin);
        }
        if (!any) throw new Exception("压缩包是空的");
        if (tops.size() == 1) {
            String only = tops.iterator().next();
            if (!only.startsWith("_")) return only;
        }
        return "";
    }

    /** 递归复制（renameTo 跨文件系统会失败，比如临时目录被清过）。 */
    private static void copyTree(File src, File dst) throws Exception {
        if (src.isDirectory()) {
            if (!dst.isDirectory() && !dst.mkdirs() && !dst.isDirectory()) {
                throw new Exception("建目录失败：" + dst.getName());
            }
            File[] kids = src.listFiles();
            if (kids != null) {
                for (int i = 0; i < kids.length; i++) copyTree(kids[i], new File(dst, kids[i].getName()));
            }
            return;
        }
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } finally {
            closeQuietly(out);
            closeQuietly(in);
        }
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (int i = 0; i < kids.length; i++) deleteTree(kids[i]);
            }
        }
        try {
            f.delete();
        } catch (Throwable ignored) {
        }
    }

    /** 目录名 → 插件名：优先 metadata 里的 name，读不到就用目录名。 */
    private String clientName(File dir) {
        JSONObject meta = readMetadata(new File(dir, "metadata.yaml"));
        String n = meta.optString("name", "");
        return (n.length() > 0) ? n : dir.getName();
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return new String(bo.toByteArray(), "UTF-8");
    }

    private static String readText(File f) {
        if (f == null || !f.isFile()) return "";
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            return readAll(in);
        } catch (Throwable t) {
            return "";
        } finally {
            closeQuietly(in);
        }
    }

    /** tmp + rename：写到一半被杀进程也不会留下半个 JSON。 */
    private static void writeTextAtomic(File f, String text) throws Exception {
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new Exception("建目录失败：" + parent.getName());
        }
        File tmp = new File(f.getAbsolutePath() + ".tmp");
        OutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            out.write(text.getBytes("UTF-8"));
            out.flush();
        } finally {
            closeQuietly(out);
        }
        if (f.exists() && !f.delete()) throw new Exception("覆盖旧文件失败：" + f.getName());
        if (!tmp.renameTo(f)) throw new Exception("替换文件失败：" + f.getName());
    }

    private static void writeText(File f, String text) {
        OutputStream out = null;
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            out = new FileOutputStream(f);
            out.write(text.getBytes("UTF-8"));
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(out);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }
}
