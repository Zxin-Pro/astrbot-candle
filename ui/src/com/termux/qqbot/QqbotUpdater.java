package com.termux.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 应用内更新桥（window.AndroidUpdater）。
 *
 * 背景：面板上的「更新」以前只是把发布页里 600 MB 整包的链接丢给系统浏览器，手机流量和
 * 浏览器都不一定撑得住，于是大量真机一直停在 1.1.x。这个桥把「查最新版 → 下 26 MB 增量包
 * （断点续传）→ 校验 → 拉起系统安装器」全放在 App 内完成。
 *
 * 五个方法对应前端的五个动作：
 *   check()          同步查一次 GitHub 发布（最多等 15s，结果是缓存成功的，失败可重试）
 *   download()       起后台线程下载，进度用 poll() 读
 *   poll()           读状态机：idle / downloading / verifying / ready / fail
 *   cancel()         停下载但保留 .part，下次接着下
 *   install()        把 .part 改名 .apk 并用 content:// 交给系统安装器
 *   openPermission() 跳到「安装未知应用」授权页
 *
 * 下载内核（Range/sha/状态机）在 QqbotUpdaterCore 里，那个类不 import android，方便在
 * 台式机 JVM 上直接跑断言。
 *
 * 注意：d8 处理匿名内部类会崩（见 UpdateWorker 的注释），本文件不出现匿名类，也不用 lambda。
 */
public class QqbotUpdater {

    /** GitHub 仓库接口。反代只是为了国内直连超时时还有条路，和 CheckUpdate 用的是同一批。 */
    static final String API = "https://api.github.com/repos/Zxin-Pro/astrbot-candle";
    static final String[] MIRRORS = {
        "",
        "https://ghfast.top/",
        "https://gh-proxy.com/",
        "https://ghproxy.net/"
    };

    /** check() 最多等这么久。前端是轮询调用的，这里同步等不会把界面卡死（JS 在桥线程上）。 */
    private static final long CHECK_WAIT_MS = 15000L;
    /** 后台线程自己用的总预算，比 CHECK_WAIT_MS 少一点，免得刚超时它才出结果。 */
    private static final long CHECK_BUDGET_MS = 14000L;

    private final Activity act;
    private final QqbotUpdaterCore core;

    /** 最近一次 check 的结果（只缓存成功的那份，失败允许马上重试）。 */
    private volatile String chkOk = null;
    private volatile String chkFail = null;
    private volatile CountDownLatch chkLatch = null;
    private final Object chkLock = new Object();

    /** check 出来后要下哪个包：download() 不再请求网络。 */
    private volatile String dlUrl = "";
    private volatile long dlSize = 0L;
    private volatile String dlSha = "";
    private volatile String dlName = "";

    public QqbotUpdater(Activity act) {
        this.act = act;
        this.core = new QqbotUpdaterCore(new File(act.getFilesDir(), "updates"));
    }

    /* ---------------- 桥方法 ---------------- */

    /**
     * check()：查最新发布，返回
     * {"ok":true,"ver":"1.3.4-beta.3","url":"…","size":26201922,"sha":"<64位hex>","notes":"…"}
     * 失败 {"ok":false,"err":"…"}。
     *
     * 为什么同步等：契约要求 check 直接给结果，前端不用先 poll 再拿。网络在后台线程跑，
     * 这里只等 latch，最长 15s；等不到就返回超时，后台那次会接着跑，下次调用直接拿走。
     */
    @JavascriptInterface
    public String check() {
        try {
            if (chkOk != null) return chkOk;
            CountDownLatch latch;
            boolean spawn = false;
            synchronized (chkLock) {
                if (chkLatch == null) {
                    chkLatch = new CountDownLatch(1);
                    chkFail = null;
                    spawn = true;
                }
                latch = chkLatch;
            }
            if (spawn) {
                Thread t = new Thread(new CheckJob(this));
                t.setDaemon(true);
                t.start();
            }
            try {
                latch.await(CHECK_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
            }
            if (chkOk != null) return chkOk;
            if (chkFail != null) {
                String f = chkFail;
                synchronized (chkLock) {
                    chkLatch = null;
                }
                return f;
            }
            return "{\"ok\":false,\"err\":\"检查更新超时了（网络太慢），稍后再试一次\"}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"err\":\"" + QqbotUpdaterCore.esc("检查更新失败：" + t) + "\"}";
        }
    }

    /** download()：用 check() 拿到的 url / size / sha 起下载。已在下载就忽略。 */
    @JavascriptInterface
    public void download() {
        try {
            if (dlUrl.length() == 0) {
                toast("先点「检查更新」，拿到新版本再下载");
                return;
            }
            core.start(dlUrl, dlSize, dlSha, dlName);
        } catch (Throwable t) {
            toast("下载启动失败：" + t);
        }
    }

    /** poll()：{"state":"…","bytes":N,"total":N,"pct":0-100,"err":"…","name":"…"}。 */
    @JavascriptInterface
    public String poll() {
        try {
            return core.poll();
        } catch (Throwable t) {
            return QqbotUpdaterCore.stateJson(QqbotUpdaterCore.S_FAIL, 0, 0, "读取下载状态失败：" + t, "");
        }
    }

    /** cancel()：停线程、保留 .part，下次 download() 从断点续。 */
    @JavascriptInterface
    public void cancel() {
        try {
            core.cancel();
        } catch (Throwable ignored) {
        }
    }

    /**
     * install()：把 .part 改名 .apk，再用 content:// 交给系统安装器。
     *
     * 两个坑：
     *  1) Uri 的路径必须是 /data/data/<包名>/files 开头。getFilesDir() 在真机上给的是
     *     /data/user/0/…，provider 会判 Invalid path —— 所以这里用 QqbotUpdaterCore.contentUri 拼。
     *  2) 系统安装器是另一个进程，读这个 Uri 要真的能读到：除了 FLAG_GRANT_READ_URI_PERMISSION，
     *     还得让 provider 那边 openFile 放行（allow-external-apps=true，和分享日志同一条通道）。
     */
    @JavascriptInterface
    public void install() {
        try {
            if (!QqbotUpdaterCore.S_READY.equals(core.state())) {
                toast("安装包还没下载完");
                return;
            }
            // 未知来源安装权限：SDK 26 起是「每个应用单独开关」，没开就先把用户送过去
            if (Build.VERSION.SDK_INT >= 26) {
                boolean allowed = true;
                try {
                    allowed = act.getPackageManager().canRequestPackageInstalls();
                } catch (Throwable ignored) {
                }
                if (!allowed) {
                    toast("请先允许安装未知应用");
                    openPermission();
                    return;
                }
            }
            File part = core.partFile();
            File apk = core.apkFile();
            if (!part.isFile() || part.length() <= 0) {
                if (apk.isFile() && apk.length() > 0) {
                    part = apk;                       // 上次已经改好名了，直接用
                } else {
                    toast("安装包不见了，请重新下载");
                    return;
                }
            }
            if (!part.equals(apk)) {
                if (apk.exists() && !apk.delete()) {
                    toast("旧安装包删不掉，请清理存储后重试");
                    return;
                }
                if (!part.renameTo(apk)) {
                    if (!copyFile(part, apk)) {
                        toast("安装包改名失败，请重试");
                        return;
                    }
                    part.delete();
                }
            }
            // 只留当前这个包：手机上不该越堆越多 26 MB 的旧安装包
            QqbotUpdaterCore.pruneOtherApks(apk.getParentFile(), apk);
            ensureExternalAppsAllowed(act.getFilesDir());
            String pkg = act.getPackageName();
            String uriText = QqbotUpdaterCore.contentUri(pkg, "updates/" + apk.getName());
            Intent it = new Intent(Intent.ACTION_VIEW);
            it.setDataAndType(Uri.parse(uriText), "application/vnd.android.package-archive");
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            act.runOnUiThread(new UiStart(act, it, "打开安装器失败"));
        } catch (SecurityException se) {
            // REQUEST_INSTALL_PACKAGES 被剥掉时也会走到这里
            toast("请先允许安装未知应用");
        } catch (ActivityNotFoundException anf) {
            toast("系统没有安装器组件，请用文件管理器手动安装");
        } catch (Throwable t) {
            toast("安装失败：" + t);
        }
    }

    /** openPermission()：跳到本应用的「安装未知应用」开关页。 */
    @JavascriptInterface
    public void openPermission() {
        try {
            if (Build.VERSION.SDK_INT < 26) {
                toast("这个安卓版本不需要单独授权，直接安装即可");
                return;
            }
            Intent it = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
            it.setData(Uri.parse("package:" + act.getPackageName()));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.runOnUiThread(new UiStart(act, it, "打不开授权页，请到系统设置里手动允许本应用安装应用"));
        } catch (Throwable t) {
            toast("打不开授权页，请到系统设置里手动允许本应用安装应用");
        }
    }

    /* ---------------- 内部实现 ---------------- */

    /** 后台线程拿到结果后回填。成功才缓存，失败允许下一次 check() 立刻重试。 */
    void done(String json) {
        // fetchAndPick 理论上总给一段 JSON；真出了岔子也得让 check() 拿到失败，不能永远卡在超时上
        if (json == null || json.length() == 0) {
            json = "{\"ok\":false,\"err\":\"检查更新失败，请稍后再试\"}";
        }
        if (json.indexOf("\"ok\":true") >= 0) chkOk = json;
        else chkFail = json;
        CountDownLatch l;
        synchronized (chkLock) {
            l = chkLatch;
        }
        if (l != null) l.countDown();
    }

    /** 解析一次发布响应（数组或单个对象都吃），挑出要下的资产并记下来。 */
    void applyCheck(String json) {
        try {
            String url = "", sha = "", notes = "", tag = "", apkName = "";
            long size = 0L;
            JSONObject o = new JSONObject(json);
            url = o.optString("url", "");
            sha = o.optString("sha", "");
            size = o.optLong("size", 0L);
            tag = o.optString("ver", "");
            apkName = o.optString("name", "");
            notes = o.optString("notes", "");
            dlUrl = url;
            dlSha = sha;
            dlSize = size;
            dlName = (apkName.length() > 0) ? apkName : ("AstrBot-" + tag + "-arm64-update.apk");
        } catch (Throwable ignored) {
        }
    }

    /**
     * 拉 GitHub 的发布列表并挑最新的一条。
     *
     * 为什么用 /releases?per_page=20 而不是 /releases/latest：latest 只返回正式版，
     * 而现在给用户的是 1.3.4-beta.3 这种预发布，打 latest 只会拿到上一版正式版，等于没更新。
     * 列表拿不到时再退回 latest（老仓库 / 反代只认 latest 的情况）。
     */
    static String fetchAndPick(QqbotUpdater owner) {
        long deadline = System.currentTimeMillis() + CHECK_BUDGET_MS;
        // 每个镜像失败的原因都攒下来：check() 失败时把原话透给用户，否则「连不上」无法排查
        StringBuilder why = new StringBuilder();
        for (int i = 0; i < MIRRORS.length; i++) {
            long left = deadline - System.currentTimeMillis();
            if (left < 2000L) {
                why.append(why.length() > 0 ? "；" : "").append("时间不够了，后面的镜像没试");
                break;
            }
            int per = (int) Math.min(9000L, left / (MIRRORS.length - i));
            if (per < 2000) per = 2000;
            String base = MIRRORS[i] + API;
            String host = hostOf(MIRRORS[i]);
            StringBuilder w1 = new StringBuilder();
            String body = httpText(base + "/releases?per_page=20", per, w1);
            String picked = (body == null) ? null : pickRelease(body, owner);
            if (picked != null) return picked;
            why.append(why.length() > 0 ? "；" : "").append(host).append("：")
               .append(body == null ? w1.toString() : "列表里没有带 apk 的版本");
            long left2 = deadline - System.currentTimeMillis();
            if (left2 < 2000L) continue;
            int per2 = (int) Math.min(6000L, left2);
            if (per2 < 2000) per2 = 2000;
            StringBuilder w2 = new StringBuilder();
            String body2 = httpText(base + "/releases/latest", per2, w2);
            String picked2 = (body2 == null) ? null : pickRelease(body2, owner);
            if (picked2 != null) return picked2;
            why.append(" / latest：").append(body2 == null ? w2.toString() : "没有带 apk 的版本");
        }
        String detail = why.toString();
        if (detail.length() > 220) detail = detail.substring(detail.length() - 220);
        String msg = "连不上 GitHub（直连和几个镜像都没成功），请确认手机能上网后再试";
        if (detail.length() > 0) msg = msg + "（" + detail + "）";
        return "{\"ok\":false,\"err\":\"" + QqbotUpdaterCore.esc(msg) + "\"}";
    }

    /** 镜像地址取个短名，报错文案里用（https://ghfast.top/ → ghfast.top，直连 → api.github.com）。 */
    static String hostOf(String mirror) {
        if (mirror == null || mirror.length() == 0) return "直连 api.github.com";
        String s = mirror;
        int k = s.indexOf("://");
        if (k >= 0) s = s.substring(k + 3);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return (s.length() == 0) ? "直连 api.github.com" : s;
    }

    /** 异常压成一行短说明：SocketTimeoutException: connect timed out 这种，够排查就行。 */
    static String brief(Throwable t) {
        if (t == null) return "未知错误";
        String m = String.valueOf(t.getMessage());
        String s = t.getClass().getSimpleName();
        if (m != null && m.length() > 0 && !"null".equals(m)) s = s + "：" + m;
        if (s.length() > 90) s = s.substring(0, 90);
        return s;
    }

    /** 从一段发布 JSON 里挑出「最新且带 apk 资产」的那一条，组装成 check() 的返回体。 */
    static String pickRelease(String body, QqbotUpdater owner) {
        if (body == null) return null;
        try {
            JSONArray arr = null;
            String t = body.trim();
            if (t.startsWith("[")) {
                arr = new JSONArray(t);
            } else {
                JSONObject one = new JSONObject(t);
                arr = new JSONArray();
                arr.put(one);
            }
            JSONObject best = null;
            String bestTag = "";
            for (int i = 0; i < arr.length(); i++) {
                JSONObject rel = arr.optJSONObject(i);
                if (rel == null || rel.optBoolean("draft")) continue;
                String tag = rel.optString("tag_name", "");
                if (tag.length() == 0) continue;
                if (pickAsset(rel.optJSONArray("assets")) == null) continue;
                if (best == null || CheckUpdate.cmpTag(tag, bestTag) > 0) {
                    best = rel;
                    bestTag = tag;
                }
            }
            if (best == null) return null;
            JSONObject asset = pickAsset(best.optJSONArray("assets"));
            if (asset == null) return null;
            String url = asset.optString("browser_download_url", "");
            if (url.length() == 0) return null;
            String notes = best.optString("body", "");
            if (notes.length() > 4000) notes = notes.substring(0, 4000);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("ver", CheckUpdate.plain(bestTag));
            o.put("tag", bestTag);
            o.put("url", url);
            o.put("size", asset.optLong("size", 0L));
            // GitHub 的 asset.digest 形如 "sha256:abcd…"；老资产可能没有，那就不带校验
            o.put("sha", QqbotUpdaterCore.stripSha(asset.optString("digest", "")));
            o.put("name", asset.optString("name", ""));
            o.put("notes", notes);
            o.put("prerelease", best.optBoolean("prerelease"));
            o.put("publishedAt", best.optString("published_at", ""));
            String json = o.toString();
            if (owner != null) owner.applyCheck(json);
            return json;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 资产优先级：先找 …arm64-update.apk（26 MB 增量包），再退 …arm64.apk（整包），
     * 都没有就随便挑一个 update 包 / apk。整包带 600 MB 离线镜像，老用户升级没必要再下一遍。
     */
    static JSONObject pickAsset(JSONArray assets) {
        if (assets == null) return null;
        JSONObject slim = null, full = null, anyUpdate = null, anyApk = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null) continue;
            String n = a.optString("name", "");
            String u = a.optString("browser_download_url", "");
            if (n.length() == 0 || u.length() == 0) continue;
            if (slim == null && n.indexOf("arm64-update.apk") >= 0) { slim = a; continue; }
            if (full == null && n.indexOf("arm64.apk") >= 0) { full = a; continue; }
            if (anyUpdate == null && n.indexOf("-update.apk") >= 0) { anyUpdate = a; continue; }
            if (anyApk == null && n.endsWith(".apk")) { anyApk = a; }
        }
        if (slim != null) return slim;
        if (full != null) return full;
        if (anyUpdate != null) return anyUpdate;
        return anyApk;
    }

    /** 读一个 URL 的全部文本；非 200 或任何异常返回 null，让调用方去试下一个地址。 */
    static String httpText(String url, int timeoutMs) {
        return httpText(url, timeoutMs, null);
    }

    /**
     * 同上，多一个 why：失败时把「到底哪儿不行」（HTTP 403 / 超时 / 证书错误 / 连不上）
     * 写进去。check() 失败时要把原话透给用户，光说「连不上 GitHub」没法排查。
     */
    static String httpText(String url, int timeoutMs, StringBuilder why) {
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "AstrBot-Candle");
            c.setRequestProperty("Accept", "application/vnd.github+json");
            int code = c.getResponseCode();
            if (code != 200) {
                if (why != null) why.append("HTTP ").append(code);
                return null;
            }
            in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            if (why != null) why.append(brief(t));
            return null;
        } finally {
            QqbotUpdaterCore.closeQuietly(in);
            if (c != null) {
                try { c.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Termux 自带的 provider 默认不对外：openFile 会先查 ~/.termux/termux.properties 里的
     * allow-external-apps，不是 true 就报 requires allow-external-apps property。
     * 系统安装器是外部进程，同样受这条限制，所以装之前先写进去（和分享日志的做法一致）。
     * 已经有这一项（哪怕写的是 false）就原样保留，不覆盖用户的设置。
     */
    private static void ensureExternalAppsAllowed(File files) {
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            File dir = new File(QqbotShell.homeDir(files), ".termux");
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            File f = new File(dir, "termux.properties");
            String cur = "";
            if (f.isFile()) {
                if (f.length() > 64L * 1024) return;
                in = new FileInputStream(f);
                byte[] buf = new byte[(int) f.length()];
                int got = 0;
                while (got < buf.length) {
                    int n = in.read(buf, got, buf.length - got);
                    if (n <= 0) break;
                    got += n;
                }
                in.close();
                in = null;
                cur = new String(buf, 0, got, "UTF-8");
                if (cur.indexOf("allow-external-apps") >= 0) return;
            }
            String add = (cur.length() == 0 || cur.endsWith("\n")) ? "" : "\n";
            out = new FileOutputStream(f);
            out.write((cur + add + "allow-external-apps=true\n").getBytes("UTF-8"));
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            QqbotUpdaterCore.closeQuietly(in);
            QqbotUpdaterCore.closeQuietly(out);
        }
    }

    /** renameTo 在同分区内几乎总能成；个别机型返回 false，留一份字节复制兜底。 */
    private static boolean copyFile(File src, File dst) {
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            out.close();
            out = null;
            return dst.isFile() && dst.length() > 0;
        } catch (Throwable t) {
            return false;
        } finally {
            QqbotUpdaterCore.closeQuietly(in);
            QqbotUpdaterCore.closeQuietly(out);
        }
    }

    private void toast(String msg) {
        try {
            act.runOnUiThread(new UiToast(act, msg));
        } catch (Throwable ignored) {
        }
    }

    /** 后台线程：查一次发布并把结果交给 done()。 */
    private static final class CheckJob implements Runnable {

        private final QqbotUpdater owner;

        CheckJob(QqbotUpdater o) { owner = o; }

        public void run() {
            String json;
            try {
                json = fetchAndPick(owner);
            } catch (Throwable t) {
                json = "{\"ok\":false,\"err\":\"" + QqbotUpdaterCore.esc("检查更新失败：" + t) + "\"}";
            }
            owner.done(json);
        }
    }

    /** 具名 Runnable：d8 处理匿名内部类会崩，这两个小任务只能用嵌套类。 */
    private static final class UiStart implements Runnable {
        private final Activity act;
        private final Intent intent;
        private final String errText;

        UiStart(Activity a, Intent i, String e) {
            act = a;
            intent = i;
            errText = e;
        }

        public void run() {
            try {
                act.startActivity(intent);
            } catch (Throwable t) {
                try {
                    Toast.makeText(act, errText + "：" + t, Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static final class UiToast implements Runnable {
        private final Activity act;
        private final String msg;

        UiToast(Activity a, String m) {
            act = a;
            msg = m;
        }

        public void run() {
            try {
                Toast.makeText(act, msg, Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
        }
    }
}
