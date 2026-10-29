package com.termux.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Environment;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 换机迁移：AndroidMigrate。
 *
 * 打包和恢复都在手机本地完成，不依赖沙盒里的 python。包内用两个根目录：
 * qqbot/...   到 files/home/.qqbot/...
 * astrbot/... 到 files/home/ubuntu/opt/AstrBot/data/...
 *
 * 文件选择器有一个现实限制：QqbotActivity 只把 7001/7002/7003 三个结果码
 * 转发给旧功能，没有迁移专用的结果转发。为了不改 Activity，pick() 仍然拉起
 * 系统文件选择器，但选择结果不会回到这里；staged() 会兜底扫描下载目录和
 * $HOME/.qqbot/migrate/incoming，找到最新的 qqbot-migrate*.zip。
 */
public final class QqbotMigrateBridge {

    private static final int REQ_PICK_ZIP = 7004;
    private static final String FORMAT = "qqbot-migrate-v1";
    private static final long MAX_ENTRY = 1024L * 1024L * 1024L;
    private static final long MAX_TOTAL = 4L * 1024L * 1024L * 1024L;

    private static volatile boolean BUILDING = false;

    private final QqbotActivity act;

    public QqbotMigrateBridge(QqbotActivity act) {
        this.act = act;
    }

    /* ---------------- 八个桥方法 ---------------- */

    @JavascriptInterface
    public String info() {
        try {
            return infoJson(act.filesDirPub()).toString();
        } catch (Throwable t) {
            return fail("读取迁移包失败：" + human(t));
        }
    }

    @JavascriptInterface
    public String build(String mode) {
        File files = null;
        String m = "full".equals(mode) ? "full" : "config";
        try {
            files = act.filesDirPub();
            if (BUILDING) {
                return fail("已经在打包了，等这次完成再点");
            }
            File qq = QqbotShell.qqbotDir(files);
            File astr = astrbotData(files);
            if (!new File(qq, "bots.json").isFile() && !astr.isDirectory()) {
                return fail("还没找到配置，先把机器人启动一次再来打包");
            }
            BUILDING = true;
            writeState(files, "run", 1, "正在准备文件…", null, m);
            Thread th = new Thread(new BuildTask(files, m), "qqbot-migrate-build");
            th.setDaemon(true);
            th.start();
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("started", true);
            return o.toString();
        } catch (Throwable t) {
            BUILDING = false;
            if (files != null) {
                writeState(files, "err", 0, "打包没开始：" + human(t), null, m);
            }
            return fail("打包没开始：" + human(t));
        }
    }

    @JavascriptInterface
    public String poll() {
        try {
            File files = act.filesDirPub();
            File f = StateIO.file(files, "migrate.json");
            String raw = StateIO.read(f);
            JSONObject o = new JSONObject();
            if (raw == null || raw.length() == 0) {
                o.put("state", BUILDING ? "run" : "idle");
                o.put("pct", 0);
                o.put("msg", BUILDING ? "正在打包…" : "空闲");
                return o.toString();
            }
            JSONObject st = new JSONObject(raw);
            String state = st.optString("state", "idle");
            int pct = st.optInt("pct", 0);
            String msg = st.optString("msg", "");
            if ("run".equals(state)) {
                int owner = st.optInt("owner", 0);
                if (!BUILDING || (owner != 0 && owner != android.os.Process.myPid())) {
                    state = "err";
                    pct = 0;
                    msg = "上次打包中断了，请重新打包";
                    st.put("state", state);
                    st.put("pct", pct);
                    st.put("msg", msg);
                    StateIO.write(f, st.toString());
                }
            }
            if (!"idle".equals(state) && !"run".equals(state) && !"done".equals(state) && !"err".equals(state)) {
                state = "idle";
            }
            o.put("state", state);
            o.put("pct", clamp(pct, 0, 100));
            o.put("msg", msg);
            if ("done".equals(state)) {
                JSONObject it = st.optJSONObject("item");
                if (it != null) {
                    JSONObject pi = new JSONObject();
                    pi.put("id", it.optString("id", ""));
                    pi.put("name", it.optString("name", ""));
                    pi.put("size", it.optLong("size", 0));
                    pi.put("mode", it.optString("mode", ""));
                    o.put("item", pi);
                }
            }
            return o.toString();
        } catch (Throwable t) {
            return fail("读取打包进度失败：" + human(t));
        }
    }

    @JavascriptInterface
    public String share(String id) {
        try {
            File files = act.filesDirPub();
            File src = resolve(files, id);
            if (src == null || !src.isFile()) {
                return fail("找不到这个迁移包");
            }
            if (!isMigrateName(src.getName())) {
                return fail("这个文件不能分享");
            }
            File dir = new File(files, "share");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return fail("分享目录建不出来");
            }
            File out = new File(dir, src.getName());
            copyAtomic(src, out);
            pruneShare(dir);
            ensureExternalAppsAllowed(files);

            String pkg = act.getPackageName();
            Uri uri = Uri.parse("content://" + pkg + ".files/data/data/" + pkg + "/files/share/" + out.getName());
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("application/zip");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, "QQ 机器人迁移包");
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return startShare(send, "分享迁移包");
        } catch (Throwable t) {
            return fail("分享失败：" + human(t));
        }
    }

    @JavascriptInterface
    public String del(String id) {
        try {
            File files = act.filesDirPub();
            File f = resolve(files, id);
            if (f == null || !f.isFile()) {
                return fail("找不到这个迁移包");
            }
            if (!isMigrateName(f.getName())) {
                return fail("这个文件不能删");
            }
            if (!f.delete()) {
                return fail("删不掉，可能还有别的程序在用");
            }
            File stage = StateIO.file(files, "migrate-stage.json");
            String cached = StateIO.read(stage);
            if (cached != null && cached.indexOf(f.getAbsolutePath()) >= 0) {
                StateIO.delete(stage);
            }
            JSONObject o = new JSONObject();
            o.put("ok", true);
            return o.toString();
        } catch (Throwable t) {
            return fail("删除失败：" + human(t));
        }
    }

    @JavascriptInterface
    public String pick() {
        try {
            File files = act.filesDirPub();
            File incoming = new File(migrateDir(files), "incoming");
            if (!incoming.isDirectory()) {
                incoming.mkdirs();
            }
            JSONObject st = new JSONObject();
            st.put("at", System.currentTimeMillis());
            st.put("req", REQ_PICK_ZIP);
            StateIO.write(StateIO.file(files, "migrate-pick.json"), st.toString());

            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                    "application/zip", "application/x-zip-compressed", "application/octet-stream" });
            i.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (i.resolveActivity(act.getPackageManager()) == null) {
                return fail("这台手机没有文件选择器");
            }
            CountDownLatch latch = new CountDownLatch(1);
            String[] err = new String[1];
            act.runOnUiThread(new PickStart(act, i, err, latch));
            if (!latch.await(4, TimeUnit.SECONDS)) {
                return fail("文件选择器没打开，稍后再试");
            }
            if (err[0] != null) {
                return fail(err[0]);
            }
            JSONObject o = new JSONObject();
            o.put("ok", true);
            return o.toString();
        } catch (Throwable t) {
            return fail("打不开文件选择器：" + human(t));
        }
    }

    @JavascriptInterface
    public String staged() {
        try {
            return stagedJson(act.filesDirPub()).toString();
        } catch (Throwable t) {
            return notReady().toString();
        }
    }

    @JavascriptInterface
    public String apply() {
        File files;
        File src;
        try {
            files = act.filesDirPub();
            StageInfo si = findStage(files);
            if (si == null) {
                clearStage(files);
                return fail("还没选迁移包，先选一个 zip");
            }
            src = si.file;
        } catch (Throwable t) {
            return fail("找不到迁移包：" + human(t));
        }

        File backup = null;
        boolean wasRunning = false;
        boolean watchCreated = false;
        File watchFile = null;
        try {
            File qq = QqbotShell.qqbotDir(files);
            watchFile = new File(new File(new File(qq, "cache"), "svc"), "watchdog.off");
            wasRunning = QqbotShell.pidAlive(files, "proot");
            if (wasRunning) {
                if (!watchFile.exists()) {
                    File svc = watchFile.getParentFile();
                    if (svc != null && !svc.isDirectory()) {
                        svc.mkdirs();
                    }
                    if (!StateIO.write(watchFile, "migrate-" + System.currentTimeMillis())) {
                        throw new IOException("关不掉看门狗");
                    }
                    watchCreated = true;
                }
                stopSandbox(files);
            }
            backup = createBackup(files);
            int n = restoreZip(files, src);
            clearStage(files);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("backup", backup.getName());
            o.put("msg", "已恢复 " + n + " 个文件，重启 App 后生效");
            return o.toString();
        } catch (Throwable t) {
            String extra = backup == null ? "" : "（恢复前备份：" + backup.getName() + "）";
            return fail("恢复失败：" + human(t) + extra);
        } finally {
            if (watchCreated && watchFile != null) {
                StateIO.delete(watchFile);
                watchCreated = false;
            }
            if (wasRunning) {
                try {
                    StringBuilder note = new StringBuilder();
                    boolean ok = QqbotShell.startSandbox(files, note, false);
                    if (ok) {
                        try {
                            QqbotShell.startWatchdog(files, new StringBuilder());
                        } catch (Throwable ignored) {
                        }
                    } else {
                        toast("已经恢复，但服务没起来，回首页点启动");
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /* ---------------- 打包 ---------------- */

    private static final class BuildTask implements Runnable {
        private final File files;
        private final String mode;

        BuildTask(File files, String mode) {
            this.files = files;
            this.mode = mode;
        }

        public void run() {
            try {
                CollectResult cr = collect(files, mode);
                if (cr.items.isEmpty()) {
                    throw new IOException("没找到可以打包的配置");
                }
                writeState(files, "run", 2, "正在压缩配置…", null, mode);
                File out = uniqueFile(migrateDir(files), "qqbot-migrate-" + stamp() + ".zip");
                createZip(cr.items, cr.preview, out, mode, new BuildProgress(files, mode));
                pruneMigrates(files);
                JSONObject item = itemJson(out);
                writeState(files, "done", 100, "打包完成", item, mode);
            } catch (Throwable t) {
                writeState(files, "err", 0, "打包失败：" + human(t), null, mode);
            } finally {
                BUILDING = false;
            }
        }
    }

    private static final class BuildProgress implements Progress {
        private final File files;
        private final String mode;
        private long lastAt = 0L;
        private int lastPct = 0;

        BuildProgress(File files, String mode) {
            this.files = files;
            this.mode = mode;
        }

        public void on(long done, long total) {
            int pct;
            if (total <= 0L) {
                pct = 92;
            } else {
                pct = 5 + (int) (done * 90L / total);
                pct = clamp(pct, 5, 95);
            }
            long now = System.currentTimeMillis();
            if (pct == lastPct) {
                return;
            }
            if (now - lastAt < 350L && pct < 95) {
                return;
            }
            lastAt = now;
            lastPct = pct;
            writeState(files, "run", pct, "正在压缩配置…", null, mode);
        }
    }

    private static void createZip(List<Item> items, JSONArray preview, File out, String mode, Progress cb) throws Exception {
        File parent = out.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("建不了迁移目录");
        }
        File tmp = new File(out.getAbsolutePath() + ".part");
        if (tmp.exists() && !tmp.delete()) {
            throw new IOException("旧的临时文件删不掉");
        }
        ZipOutputStream zos = null;
        try {
            FileOutputStream fos = new FileOutputStream(tmp);
            zos = new ZipOutputStream(new BufferedOutputStream(fos, 65536));
            zos.setLevel(9);

            JSONObject man = new JSONObject();
            man.put("format", FORMAT);
            man.put("version", 1);
            man.put("app", "qqbot");
            man.put("mode", mode);
            man.put("createdAt", System.currentTimeMillis());
            man.put("files", items.size());
            man.put("preview", preview == null ? new JSONArray() : preview);

            JSONArray entries = new JSONArray();
            long total = 0L;
            for (int i = 0; i < items.size(); i++) {
                Item it = items.get(i);
                JSONObject e = new JSONObject();
                e.put("p", it.zipPath);
                e.put("size", it.size);
                entries.put(e);
                total += it.size;
            }
            man.put("entries", entries);
            byte[] mb = man.toString().getBytes("UTF-8");
            ZipEntry me = new ZipEntry("manifest.json");
            me.setTime(System.currentTimeMillis());
            zos.putNextEntry(me);
            zos.write(mb);
            zos.closeEntry();

            if (cb != null) {
                cb.on(0L, total);
            }
            long done = 0L;
            for (int i = 0; i < items.size(); i++) {
                Item it = items.get(i);
                ZipEntry ze = new ZipEntry(it.zipPath);
                ze.setTime(it.src.lastModified());
                zos.putNextEntry(ze);
                InputStream in = null;
                try {
                    in = new FileInputStream(it.src);
                    copyStream(in, zos, it.size);
                } finally {
                    if (in != null) {
                        in.close();
                    }
                }
                zos.closeEntry();
                done += it.size;
                if (cb != null) {
                    cb.on(done, total);
                }
            }
            zos.finish();
            zos.close();
            zos = null;

            if (out.exists() && !out.delete()) {
                throw new IOException("旧迁移包删不掉");
            }
            if (!tmp.renameTo(out)) {
                throw new IOException("迁移包落盘失败");
            }
        } finally {
            if (zos != null) {
                try {
                    zos.close();
                } catch (Throwable ignored) {
                }
            }
            if (tmp.exists()) {
                tmp.delete();
            }
        }
    }

    private static CollectResult collect(File files, String mode) {
        CollectResult cr = new CollectResult();
        File qq = QqbotShell.qqbotDir(files);
        addFile(cr, new File(qq, "bots.json"), "qqbot/bots.json");
        addFile(cr, new File(qq, "proxy.txt"), "qqbot/proxy.txt");
        addFile(cr, new File(qq, "state/protect.json"), "qqbot/state/protect.json");
        addFile(cr, new File(qq, "cache/bots.json"), "qqbot/cache/bots.json");
        addFile(cr, new File(qq, "cache/qq-env.txt"), "qqbot/cache/qq-env.txt");
        addFile(cr, new File(qq, "cache/qq-args.txt"), "qqbot/cache/qq-args.txt");
        addDir(cr, new File(qq, "cache/bots.d"), "qqbot/cache/bots.d");
        addDir(cr, new File(qq, "config"), "qqbot/config");

        File astr = astrbotData(files);
        addFile(cr, new File(astr, "cmd_config.json"), "astrbot/cmd_config.json");
        addDir(cr, new File(astr, "config"), "astrbot/config");
        addFile(cr, new File(astr, "shared_preferences.json"), "astrbot/shared_preferences.json");
        addDir(cr, new File(astr, "personas"), "astrbot/personas");
        addPluginConfigs(cr, astr);
        if ("full".equals(mode)) {
            addFile(cr, new File(astr, "data_v4.db"), "astrbot/data_v4.db");
            addFile(cr, new File(astr, "data_v4.db-wal"), "astrbot/data_v4.db-wal");
            addFile(cr, new File(astr, "data_v4.db-shm"), "astrbot/data_v4.db-shm");
        }

        cr.preview = new JSONArray();
        for (int i = 0; i < cr.items.size() && cr.preview.length() < 8; i++) {
            cr.preview.put(displayName(cr.items.get(i).zipPath));
        }
        return cr;
    }

    private static void addPluginConfigs(CollectResult cr, File astr) {
        File plugins = new File(astr, "plugins");
        if (!plugins.isDirectory()) {
            return;
        }
        File[] list = plugins.listFiles();
        if (list == null) {
            return;
        }
        Arrays.sort(list, new ByName());
        for (int i = 0; i < list.length; i++) {
            File dir = list[i];
            if (dir == null || !dir.isDirectory()) {
                continue;
            }
            addFile(cr, new File(dir, "config.json"), "astrbot/plugins/" + dir.getName() + "/config.json");
            addFile(cr, new File(dir, "conf.json"), "astrbot/plugins/" + dir.getName() + "/conf.json");
        }
    }

    private static void addFile(CollectResult cr, File src, String zipPath) {
        if (src == null || !src.isFile() || src.length() < 0L) {
            return;
        }
        cr.items.add(new Item(src, zipPath));
    }

    private static void addDir(CollectResult cr, File dir, String zipPrefix) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        File[] list = dir.listFiles();
        if (list == null) {
            return;
        }
        Arrays.sort(list, new ByName());
        for (int i = 0; i < list.length; i++) {
            File f = list[i];
            if (f == null) {
                continue;
            }
            String p = zipPrefix + "/" + f.getName();
            if (f.isDirectory()) {
                addDir(cr, f, p);
            } else if (f.isFile()) {
                addFile(cr, f, p);
            }
        }
    }

    private static final class CollectResult {
        final ArrayList<Item> items = new ArrayList<Item>();
        JSONArray preview = new JSONArray();
    }

    private static final class Item {
        final File src;
        final String zipPath;
        final long size;

        Item(File src, String zipPath) {
            this.src = src;
            this.zipPath = zipPath;
            this.size = src.length();
        }
    }

    private interface Progress {
        void on(long done, long total);
    }

    /* ---------------- 恢复 ---------------- */

    private static int restoreZip(File files, File zip) throws Exception {
        JSONObject man = readManifest(zip);
        if (man == null) {
            throw new IOException("这个 zip 不是迁移包");
        }
        String mode = man.optString("mode", "");
        if (!"full".equals(mode) && !"config".equals(mode)) {
            throw new IOException("这个包不能用来恢复");
        }
        File tmp = new File(migrateDir(files), "tmp-" + stamp() + "-" + android.os.Process.myPid());
        deleteTree(tmp);
        if (!tmp.mkdirs()) {
            throw new IOException("建不了临时目录");
        }
        int count = 0;
        long total = 0L;
        ZipFile zf = null;
        try {
            zf = new ZipFile(zip);
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if ("manifest.json".equals(name)) {
                    continue;
                }
                if (e.isDirectory()) {
                    continue;
                }
                String root;
                String rel;
                if (name.startsWith("qqbot/")) {
                    root = "qqbot";
                    rel = name.substring(6);
                } else if (name.startsWith("astrbot/")) {
                    root = "astrbot";
                    rel = name.substring(8);
                } else {
                    throw new IOException("包里有不认识的路径：" + name);
                }
                if (!safeRel(rel)) {
                    throw new IOException("包里有危险路径：" + name);
                }
                File rootDir = new File(tmp, root);
                File dst = new File(rootDir, rel);
                ensureInside(rootDir, dst);
                File parent = dst.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("建不了目录：" + rel);
                }
                InputStream in = null;
                OutputStream out = null;
                long got = 0L;
                try {
                    in = zf.getInputStream(e);
                    out = new FileOutputStream(dst);
                    got = copyStream(in, out, MAX_ENTRY);
                    out.flush();
                } finally {
                    if (in != null) {
                        in.close();
                    }
                    if (out != null) {
                        out.close();
                    }
                }
                total += got;
                if (total > MAX_TOTAL) {
                    throw new IOException("迁移包太大了");
                }
                count++;
            }
        } finally {
            if (zf != null) {
                zf.close();
            }
        }
        if (count <= 0) {
            throw new IOException("迁移包里没有文件");
        }

        int copied = 0;
        File qsrc = new File(tmp, "qqbot");
        if (qsrc.isDirectory()) {
            copied += copyTree(qsrc, QqbotShell.qqbotDir(files));
        }
        File asrc = new File(tmp, "astrbot");
        if (asrc.isDirectory()) {
            copied += copyTree(asrc, astrbotData(files));
        }
        deleteTree(tmp);
        return copied;
    }

    private static File createBackup(File files) throws Exception {
        CollectResult cr = collect(files, "full");
        if (cr.items.isEmpty()) {
            throw new IOException("没找到可以备份的配置");
        }
        File out = uniqueFile(migrateDir(files), "backup-" + stamp() + ".zip");
        createZip(cr.items, cr.preview, out, "backup", null);
        pruneBackups(files);
        return out;
    }

    private static int copyTree(File srcRoot, File dstRoot) throws Exception {
        if (!srcRoot.isDirectory()) {
            return 0;
        }
        if (!dstRoot.isDirectory() && !dstRoot.mkdirs()) {
            throw new IOException("目标目录建不出来：" + dstRoot.getAbsolutePath());
        }
        int n = 0;
        File[] list = srcRoot.listFiles();
        if (list == null) {
            return 0;
        }
        Arrays.sort(list, new ByName());
        for (int i = 0; i < list.length; i++) {
            File s = list[i];
            if (s == null) {
                continue;
            }
            File d = new File(dstRoot, s.getName());
            if (s.isDirectory()) {
                n += copyTree(s, d);
            } else if (s.isFile()) {
                if ("data_v4.db".equals(s.getName())) {
                    deleteDbSidecars(d);
                }
                copyAtomic(s, d);
                n++;
            }
        }
        return n;
    }

    private static void deleteDbSidecars(File db) throws IOException {
        File parent = db.getParentFile();
        if (parent == null) {
            return;
        }
        File[] olds = new File[] {
                new File(parent, db.getName() + "-wal"),
                new File(parent, db.getName() + "-shm") };
        for (int i = 0; i < olds.length; i++) {
            File f = olds[i];
            if (f.exists() && !f.delete()) {
                throw new IOException("旧数据库缓存删不掉：" + f.getName());
            }
        }
    }

    /* ---------------- 快照和状态 ---------------- */

    private static final class StageInfo {
        File file;
        String name;
        long size;
        int files;
        JSONArray preview;
        long at;
        String mode;
    }

    private static final class ZipScan {
        int files;
        JSONArray preview = new JSONArray();
        boolean valid = true;
    }

    private static JSONObject infoJson(File files) throws Exception {
        File dir = migrateDir(files);
        JSONArray items = new JSONArray();
        File[] list = dir.listFiles();
        ArrayList<File> zips = new ArrayList<File>();
        if (list != null) {
            for (int i = 0; i < list.length; i++) {
                File f = list[i];
                if (f != null && f.isFile() && isMigrateName(f.getName())) {
                    zips.add(f);
                }
            }
        }
        Collections.sort(zips, new ByMtimeDesc());
        for (int i = 0; i < zips.size(); i++) {
            items.put(itemJson(zips.get(i)));
        }
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("dir", dir.getAbsolutePath());
        o.put("items", items);
        o.put("staged", stagedJson(files));
        return o;
    }

    private static JSONObject itemJson(File f) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", idOf(f.getName()));
            o.put("name", f.getName());
            o.put("size", f.length());
            o.put("at", f.lastModified());
            JSONObject m = readManifest(f);
            o.put("mode", m == null ? "unknown" : m.optString("mode", "unknown"));
        } catch (Throwable t) {
            try {
                o.put("id", idOf(f.getName()));
                o.put("name", f.getName());
                o.put("size", f.length());
                o.put("at", f.lastModified());
                o.put("mode", "unknown");
            } catch (Throwable ignored) {
            }
        }
        return o;
    }

    private static JSONObject stagedJson(File files) {
        try {
            StageInfo si = findStage(files);
            if (si == null) {
                return notReady();
            }
            cacheStage(files, si);
            JSONObject o = new JSONObject();
            o.put("ready", true);
            o.put("name", si.name);
            o.put("size", si.size);
            o.put("files", si.files);
            o.put("preview", si.preview);
            o.put("at", si.at);
            return o;
        } catch (Throwable t) {
            return notReady();
        }
    }

    private static JSONObject notReady() {
        JSONObject o = new JSONObject();
        try {
            o.put("ready", false);
        } catch (Throwable ignored) {
        }
        return o;
    }

    private static StageInfo findStage(File files) {
        ArrayList<File> cands = new ArrayList<File>();
        ArrayList<String> seen = new ArrayList<String>();
        addCandidateDir(cands, seen, new File("/sdcard/Download"));
        try {
            File ext = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (ext != null) {
                addCandidateDir(cands, seen, ext);
            }
        } catch (Throwable ignored) {
        }
        addCandidateDir(cands, seen, new File("/storage/emulated/0/Download"));
        addCandidateDir(cands, seen, new File(migrateDir(files), "incoming"));
        addCandidateDir(cands, seen, new File(new File(QqbotShell.qqbotDir(files), "cache"), "restore"));
        Collections.sort(cands, new ByMtimeDesc());
        for (int i = 0; i < cands.size(); i++) {
            StageInfo si = stageInfo(cands.get(i));
            if (si != null) {
                return si;
            }
        }
        return stageInfoFromCache(files);
    }

    private static void addCandidateDir(ArrayList<File> out, ArrayList<String> seen, File dir) {
        try {
            if (dir == null || !dir.isDirectory()) {
                return;
            }
            File[] list = dir.listFiles();
            if (list == null) {
                return;
            }
            for (int i = 0; i < list.length; i++) {
                File f = list[i];
                if (f == null || !f.isFile() || !isMigrateName(f.getName())) {
                    continue;
                }
                String key = f.getCanonicalPath();
                if (seen.indexOf(key) >= 0) {
                    continue;
                }
                seen.add(key);
                out.add(f);
            }
        } catch (Throwable ignored) {
        }
    }

    private static StageInfo stageInfoFromCache(File files) {
        try {
            String raw = StateIO.read(StateIO.file(files, "migrate-stage.json"));
            if (raw == null) {
                return null;
            }
            JSONObject o = new JSONObject(raw);
            String p = o.optString("file", "");
            if (p.length() == 0) {
                return null;
            }
            File f = new File(p);
            if (!f.isFile()) {
                return null;
            }
            long size = o.optLong("size", -1L);
            if (size >= 0L && size != f.length()) {
                return null;
            }
            return stageInfo(f);
        } catch (Throwable t) {
            return null;
        }
    }

    private static StageInfo stageInfo(File f) {
        if (f == null || !f.isFile() || !isMigrateName(f.getName())) {
            return null;
        }
        try {
            JSONObject m = readManifest(f);
            if (m == null) {
                return null;
            }
            String mode = m.optString("mode", "");
            if (!"full".equals(mode) && !"config".equals(mode)) {
                return null;
            }
            ZipScan scan = scanZip(f);
            if (!scan.valid || scan.files <= 0) {
                return null;
            }
            StageInfo si = new StageInfo();
            si.file = f;
            si.name = f.getName();
            si.size = f.length();
            si.files = scan.files;
            si.at = f.lastModified();
            si.mode = mode;
            JSONArray pv = cleanPreview(m.optJSONArray("preview"));
            si.preview = pv.length() > 0 ? pv : scan.preview;
            return si;
        } catch (Throwable t) {
            return null;
        }
    }

    private static ZipScan scanZip(File f) throws Exception {
        ZipScan scan = new ZipScan();
        ZipFile zf = null;
        try {
            zf = new ZipFile(f);
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if ("manifest.json".equals(name)) {
                    continue;
                }
                if (e.isDirectory()) {
                    continue;
                }
                if (!name.startsWith("qqbot/") && !name.startsWith("astrbot/")) {
                    scan.valid = false;
                    continue;
                }
                String rel = zipRel(name);
                if (rel == null) {
                    scan.valid = false;
                    continue;
                }
                scan.files++;
                if (scan.preview.length() < 8) {
                    scan.preview.put(displayName(name));
                }
            }
        } finally {
            if (zf != null) {
                zf.close();
            }
        }
        return scan;
    }

    private static void cacheStage(File files, StageInfo si) {
        try {
            JSONObject o = new JSONObject();
            o.put("file", si.file.getAbsolutePath());
            o.put("name", si.name);
            o.put("size", si.size);
            o.put("files", si.files);
            o.put("preview", si.preview);
            o.put("at", si.at);
            o.put("mode", si.mode);
            StateIO.write(StateIO.file(files, "migrate-stage.json"), o.toString());
        } catch (Throwable ignored) {
        }
    }

    private static void clearStage(File files) {
        StateIO.delete(StateIO.file(files, "migrate-stage.json"));
    }

    private static JSONArray cleanPreview(JSONArray in) {
        JSONArray out = new JSONArray();
        if (in == null) {
            return out;
        }
        for (int i = 0; i < in.length() && out.length() < 8; i++) {
            Object v = in.opt(i);
            if (v instanceof String) {
                String s = (String) v;
                if (s.length() > 0 && s.length() <= 160) {
                    out.put(s);
                }
            }
        }
        return out;
    }

    private static JSONObject readManifest(File zip) throws Exception {
        ZipFile zf = null;
        try {
            zf = new ZipFile(zip);
            ZipEntry e = zf.getEntry("manifest.json");
            if (e == null || e.isDirectory()) {
                return null;
            }
            InputStream in = zf.getInputStream(e);
            String s;
            try {
                s = readAll(in, 512 * 1024);
            } finally {
                in.close();
            }
            JSONObject o = new JSONObject(s);
            if (!FORMAT.equals(o.optString("format", ""))) {
                return null;
            }
            return o;
        } finally {
            if (zf != null) {
                zf.close();
            }
        }
    }

    /* ---------------- 分享 ---------------- */

    private String startShare(Intent send, String label) {
        int others = countReceivers(send);
        if (others == 0) {
            return fail("这台手机没有能接收分享的应用（微信 / QQ 之类），先装一个再分享");
        }
        CountDownLatch latch = new CountDownLatch(1);
        String[] err = new String[1];
        act.runOnUiThread(new ShareStart(act, send, label, err, latch));
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                return fail("分享面板没弹出来，稍后再试");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fail("分享被打断了");
        }
        if (err[0] != null) {
            return fail(err[0]);
        }
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
        } catch (Throwable ignored) {
        }
        return o.toString();
    }

    private int countReceivers(Intent send) {
        int others = 0;
        try {
            List<ResolveInfo> list = act.getPackageManager().queryIntentActivities(send, 0);
            for (int i = 0; i < list.size(); i++) {
                ResolveInfo ri = list.get(i);
                if (ri == null || ri.activityInfo == null) {
                    continue;
                }
                if (act.getPackageName().equals(ri.activityInfo.packageName)) {
                    continue;
                }
                others++;
            }
        } catch (Throwable ignored) {
        }
        return others;
    }

    private static final class ShareStart implements Runnable {
        private final QqbotActivity act;
        private final Intent send;
        private final String label;
        private final String[] err;
        private final CountDownLatch latch;

        ShareStart(QqbotActivity act, Intent send, String label, String[] err, CountDownLatch latch) {
            this.act = act;
            this.send = send;
            this.label = label;
            this.err = err;
            this.latch = latch;
        }

        public void run() {
            try {
                Intent chooser = Intent.createChooser(send, label);
                chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, new ComponentName[] {
                        new ComponentName(act.getPackageName(), "com.termux.filepicker.TermuxFileReceiverActivity") });
                chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(chooser);
            } catch (Throwable t) {
                err[0] = "打不开分享面板：" + human(t);
            } finally {
                latch.countDown();
            }
        }
    }

    private static final class PickStart implements Runnable {
        private final QqbotActivity act;
        private final Intent intent;
        private final String[] err;
        private final CountDownLatch latch;

        PickStart(QqbotActivity act, Intent intent, String[] err, CountDownLatch latch) {
            this.act = act;
            this.intent = intent;
            this.err = err;
            this.latch = latch;
        }

        public void run() {
            try {
                if (act.isFinishing()) {
                    err[0] = "页面已经关了";
                    return;
                }
                act.startActivityForResult(intent, REQ_PICK_ZIP);
            } catch (Throwable t) {
                err[0] = "文件选择器打不开：" + human(t);
            } finally {
                latch.countDown();
            }
        }
    }

    private static final class ToastTask implements Runnable {
        private final QqbotActivity act;
        private final String msg;

        ToastTask(QqbotActivity act, String msg) {
            this.act = act;
            this.msg = msg;
        }

        public void run() {
            try {
                Toast.makeText(act, msg, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        }
    }

    private void toast(String msg) {
        try {
            act.runOnUiThread(new ToastTask(act, msg));
        } catch (Throwable ignored) {
        }
    }

    private void ensureExternalAppsAllowed(File files) {
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            File dir = new File(QqbotShell.homeDir(files), ".termux");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return;
            }
            File f = new File(dir, "termux.properties");
            String cur = "";
            if (f.isFile()) {
                if (f.length() > 64L * 1024L) {
                    return;
                }
                in = new FileInputStream(f);
                byte[] buf = new byte[(int) f.length()];
                int got = 0;
                while (got < buf.length) {
                    int n = in.read(buf, got, buf.length - got);
                    if (n <= 0) {
                        break;
                    }
                    got += n;
                }
                in.close();
                in = null;
                cur = new String(buf, 0, got, "UTF-8");
                if (cur.indexOf("allow-external-apps=true") >= 0) {
                    return;
                }
            }
            String add = (cur.length() == 0 || cur.endsWith("\n")) ? "" : "\n";
            out = new FileOutputStream(f);
            out.write((cur + add + "allow-external-apps=true\n").getBytes("UTF-8"));
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void pruneShare(File dir) {
        File[] list = dir.listFiles();
        if (list == null) {
            return;
        }
        ArrayList<File> fs = new ArrayList<File>();
        for (int i = 0; i < list.length; i++) {
            File f = list[i];
            if (f != null && f.isFile() && f.getName().endsWith(".zip")) {
                fs.add(f);
            }
        }
        Collections.sort(fs, new ByMtimeDesc());
        for (int i = 5; i < fs.size(); i++) {
            fs.get(i).delete();
        }
    }

    /* ---------------- 文件工具 ---------------- */

    private static File resolve(File files, String id) {
        try {
            if (id == null) {
                return null;
            }
            String s = id.trim();
            if (s.length() == 0 || s.indexOf('/') >= 0 || s.indexOf('\\') >= 0 || s.indexOf("..") >= 0) {
                return null;
            }
            if (!s.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                s = s + ".zip";
            }
            File dir = migrateDir(files);
            File f = new File(dir, s);
            String root = dir.getCanonicalPath();
            String path = f.getCanonicalPath();
            if (!root.equals(path) && !path.startsWith(root + File.separator)) {
                return null;
            }
            return f.isFile() ? f : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isMigrateName(String name) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith("qqbot-migrate") && n.endsWith(".zip");
    }

    private static String idOf(String name) {
        if (name == null) {
            return "";
        }
        if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            return name.substring(0, name.length() - 4);
        }
        return name;
    }

    private static String displayName(String zipPath) {
        if (zipPath == null) {
            return "";
        }
        if (zipPath.startsWith("qqbot/")) {
            return zipPath.substring(6);
        }
        if (zipPath.startsWith("astrbot/")) {
            return "AstrBot/" + zipPath.substring(8);
        }
        return zipPath;
    }

    private static String zipRel(String name) {
        if (name == null) {
            return null;
        }
        String rel;
        if (name.startsWith("qqbot/")) {
            rel = name.substring(6);
        } else if (name.startsWith("astrbot/")) {
            rel = name.substring(8);
        } else {
            return null;
        }
        return safeRel(rel) ? rel : null;
    }

    private static boolean safeRel(String rel) {
        if (rel == null || rel.length() == 0 || rel.startsWith("/") || rel.indexOf('\\') >= 0) {
            return false;
        }
        String[] parts = rel.split("/");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.length() == 0 || ".".equals(p) || "..".equals(p)) {
                return false;
            }
        }
        return true;
    }

    private static void ensureInside(File root, File dst) throws IOException {
        String r = root.getCanonicalPath();
        String d = dst.getCanonicalPath();
        if (!r.equals(d) && !d.startsWith(r + File.separator)) {
            throw new IOException("包里有危险路径");
        }
    }

    private static File astrbotData(File files) {
        File a = new File(files, "home/ubuntu/opt/AstrBot/data");
        if (a.isDirectory()) {
            return a;
        }
        File b = new File(files, "home/ubuntu/root/astrbot/data");
        if (b.isDirectory()) {
            return b;
        }
        return a;
    }

    private static File migrateDir(File files) {
        File d = new File(QqbotShell.qqbotDir(files), "migrate");
        if (!d.isDirectory()) {
            d.mkdirs();
        }
        return d;
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) {
            return f;
        }
        String base = name;
        if (base.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            base = base.substring(0, base.length() - 4);
        }
        for (int i = 1; i < 100; i++) {
            File n = new File(dir, base + "-" + i + ".zip");
            if (!n.exists()) {
                return n;
            }
        }
        return new File(dir, base + "-" + System.currentTimeMillis() + ".zip");
    }

    private static void pruneMigrates(File files) {
        File dir = migrateDir(files);
        File[] list = dir.listFiles();
        if (list == null) {
            return;
        }
        ArrayList<File> fs = new ArrayList<File>();
        for (int i = 0; i < list.length; i++) {
            File f = list[i];
            if (f != null && f.isFile() && isMigrateName(f.getName())) {
                fs.add(f);
            }
        }
        Collections.sort(fs, new ByMtimeDesc());
        for (int i = 10; i < fs.size(); i++) {
            fs.get(i).delete();
        }
    }

    private static void pruneBackups(File files) {
        File dir = migrateDir(files);
        File[] list = dir.listFiles();
        if (list == null) {
            return;
        }
        ArrayList<File> fs = new ArrayList<File>();
        for (int i = 0; i < list.length; i++) {
            File f = list[i];
            if (f != null && f.isFile() && f.getName().startsWith("backup-") && f.getName().endsWith(".zip")) {
                fs.add(f);
            }
        }
        Collections.sort(fs, new ByMtimeDesc());
        for (int i = 5; i < fs.size(); i++) {
            fs.get(i).delete();
        }
    }

    private static void copyAtomic(File src, File dst) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("目录建不出来：" + dst.getAbsolutePath());
        }
        File tmp = new File(parent, dst.getName() + ".part-" + android.os.Process.myPid() + "-" + System.nanoTime());
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(tmp);
            copyStream(in, out, MAX_ENTRY);
            out.flush();
            out.getFD().sync();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
        if (dst.exists() && !dst.delete()) {
            tmp.delete();
            throw new IOException("旧文件删不掉：" + dst.getName());
        }
        if (!tmp.renameTo(dst)) {
            tmp.delete();
            throw new IOException("文件落盘失败：" + dst.getName());
        }
    }

    private static long copyStream(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buf = new byte[65536];
        long total = 0L;
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (n == 0) {
                continue;
            }
            total += n;
            if (limit > 0L && total > limit) {
                throw new IOException("单个文件太大");
            }
            out.write(buf, 0, n);
        }
        return total;
    }

    private static String readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (n == 0) {
                continue;
            }
            total += n;
            if (total > max) {
                throw new IOException("清单文件太大");
            }
            bo.write(buf, 0, n);
        }
        return bo.toString("UTF-8");
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            File[] list = f.listFiles();
            if (list != null) {
                for (int i = 0; i < list.length; i++) {
                    deleteTree(list[i]);
                }
            }
        }
        f.delete();
    }

    private static void writeState(File files, String state, int pct, String msg, JSONObject item, String mode) {
        try {
            JSONObject o = new JSONObject();
            o.put("state", state);
            o.put("pct", clamp(pct, 0, 100));
            o.put("msg", msg);
            o.put("mode", mode);
            o.put("owner", android.os.Process.myPid());
            o.put("at", System.currentTimeMillis());
            if (item != null) {
                o.put("item", item);
            }
            StateIO.write(StateIO.file(files, "migrate.json"), o.toString());
        } catch (Throwable ignored) {
        }
    }

    private static String stamp() {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT);
        f.setTimeZone(TimeZone.getDefault());
        return f.format(new Date());
    }

    private static int clamp(int v, int lo, int hi) {
        if (v < lo) {
            return lo;
        }
        if (v > hi) {
            return hi;
        }
        return v;
    }

    private static String fail(String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("err", oneLine(msg));
            return o.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"err\":\"操作失败\"}";
        }
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "操作失败";
        }
        String v = s.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
        if (v.length() > 240) {
            v = v.substring(0, 240) + "…";
        }
        return v;
    }

    private static String human(Throwable t) {
        if (t == null) {
            return "未知错误";
        }
        String m = t.getMessage();
        if (m == null || m.length() == 0) {
            m = t.getClass().getSimpleName();
        }
        return oneLine(m);
    }

    private static String shq(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static void stopSandbox(File files) throws Exception {
        File sh = new File(new File(QqbotShell.usrDir(files), "opt/qqbot"), "stop-sandbox.sh");
        if (!sh.isFile()) {
            throw new IOException("找不到停止脚本");
        }
        String out = QqbotShell.run(files, "bash " + shq(sh.getAbsolutePath()) + " --no-offline", 180);
        if (out != null && out.indexOf("[timeout]") >= 0) {
            throw new IOException("停止服务超时");
        }
        if (QqbotShell.pidAlive(files, "proot")) {
            throw new IOException("服务没有停下来");
        }
    }

    private static final class ByName implements Comparator<File> {
        public int compare(File a, File b) {
            String x = a == null ? "" : a.getName();
            String y = b == null ? "" : b.getName();
            return x.compareTo(y);
        }
    }

    private static final class ByMtimeDesc implements Comparator<File> {
        public int compare(File a, File b) {
            long x = a == null ? 0L : a.lastModified();
            long y = b == null ? 0L : b.lastModified();
            if (x < y) {
                return 1;
            }
            if (x > y) {
                return -1;
            }
            String nx = a == null ? "" : a.getName();
            String ny = b == null ? "" : b.getName();
            return ny.compareTo(nx);
        }
    }
}
