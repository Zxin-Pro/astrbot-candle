package com.termux.app;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * 应用内更新的下载内核（纯 Java：只用 java.io / java.net / java.security，一个 android.* 都不碰）。
 *
 * 为什么要自己下：以前面板上的「更新」只是把发布页里 600 MB 的整包链接丢给浏览器，
 * 手机上下不完、断了还得从头再来，所以大量真机还停在 1.1.x。现在改成 App 内下那次发布里的
 * 26 MB 增量包，支持断点续传 + SHA-256 校验 + 进度轮询。
 *
 * 为什么单独一个类：Range 怎么算、sha 怎么比、状态机怎么走、文件名怎么消毒，这些都得能
 * 在普通台式机 JVM 上跑断言（见 out/b3_updater_check.json）。带上 android 依赖就测不了，
 * 所以内核完全不 import android，android 那一层（QqbotUpdater）只做桥和跳转。
 *
 * d8 处理匿名内部类会崩（见 UpdateWorker 的注释），本文件不出现匿名类，也不用 lambda。
 */
public final class QqbotUpdaterCore {

    /** 状态机取值，和 window.AndroidUpdater.poll() 的 state 字段一致。 */
    public static final String S_IDLE = "idle";
    public static final String S_DOWNLOADING = "downloading";
    public static final String S_VERIFYING = "verifying";
    public static final String S_READY = "ready";
    public static final String S_FAIL = "fail";

    private static final int CONNECT_TIMEOUT_MS = 12000;
    private static final int READ_TIMEOUT_MS = 20000;
    private static final int MAX_ATTEMPTS = 3;
    private static final int BUF = 65536;
    /** 进度回写间隔：手机上每 200ms 一次足够顺，也不会把 JS 线程刷爆。 */
    private static final long PROGRESS_MS = 200L;

    private final File dir;

    private volatile String state = S_IDLE;
    private volatile long bytes;
    private volatile long total;
    private volatile String err = "";
    private volatile String name = "";
    private volatile String url = "";
    private volatile String sha = "";
    /** 这次下载有没有真的做过 SHA-256 比对（GitHub 老资产没 digest 时只能放行，前端要提示「未校验」）。 */
    private volatile boolean verified;
    private volatile boolean stopped;
    private volatile Thread worker;
    private volatile InputStream live;

    /** 最近若干次状态迁移，给测试和排查用（真机上也能一眼看出卡在哪一步）。 */
    private final String[] history = new String[32];
    private int historyLen;

    public QqbotUpdaterCore(File dir) {
        this.dir = (dir == null) ? new File(".") : dir;
    }

    /* ---------------- 纯函数部分（可被普通 JVM 直接断言） ---------------- */

    /**
     * Range 头。已经下到 n 字节就从 n 续；没有已下载内容时返回 null（整段下载，不要发 Range）。
     * 这里单独抽出来是因为「续传位置算错」是下载器最常见也最难复现的 bug。
     */
    public static String rangeHeader(long have) {
        if (have <= 0) return null;
        return "bytes=" + have + "-";
    }

    /** 去掉 "sha256:" 前缀并转小写：GitHub 的 asset.digest 长这样 "sha256:abcd…"。 */
    public static String stripSha(String s) {
        String v = (s == null) ? "" : s.trim();
        int i = v.indexOf(':');
        if (i >= 0) v = v.substring(i + 1).trim();
        return v.toLowerCase(java.util.Locale.US);
    }

    /** sha 比对。want 为空表示这条发布没给校验值，只能放行（不拦用户）。 */
    public static boolean shaMatches(String want, String got) {
        String a = stripSha(want);
        if (a.length() == 0) return true;
        return a.equals(stripSha(got));
    }

    /** 进度百分比，0-100；总量未知或已经下超了都夹在区间里。 */
    public static int pct(long bytes, long total) {
        if (total <= 0) return 0;
        long p = bytes * 100L / total;
        if (p < 0) return 0;
        if (p > 100) return 100;
        return (int) p;
    }

    /**
     * 文件名消毒：只留 [A-Za-z0-9._-]，别的字符一律换成下划线，去掉开头的点和下划线。
     * 名字来自 GitHub 的 asset.name，虽然是我们自己的仓库，也不能让它带出路径分隔符
     * （"../x.apk" 这种会把下载目录指到 files 外面，provider 那边直接 Invalid path）。
     */
    public static String sanitize(String name) {
        String v = (name == null) ? "" : name.trim();
        StringBuilder b = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            b.append(ok ? c : '_');
        }
        String s = b.toString();
        while (s.length() > 0 && (s.charAt(0) == '.' || s.charAt(0) == '_')) s = s.substring(1);
        if (s.length() > 96) s = s.substring(s.length() - 96);
        if (s.length() == 0) s = "update.apk";
        if (s.indexOf('.') < 0) s = s + ".apk";
        return s;
    }

    /** 状态机：哪些迁移是合法的。测试按这条链断言 idle→downloading→verifying→ready。 */
    public static boolean canGo(String from, String to) {
        String f = (from == null) ? S_IDLE : from;
        String t = (to == null) ? S_IDLE : to;
        if (S_IDLE.equals(f)) return S_DOWNLOADING.equals(t);
        if (S_DOWNLOADING.equals(f)) return S_VERIFYING.equals(t) || S_IDLE.equals(t) || S_FAIL.equals(t);
        if (S_VERIFYING.equals(f)) return S_READY.equals(t) || S_FAIL.equals(t);
        if (S_READY.equals(f)) return S_DOWNLOADING.equals(t) || S_IDLE.equals(t);
        if (S_FAIL.equals(f)) return S_DOWNLOADING.equals(t) || S_IDLE.equals(t);
        return false;
    }

    /**
     * 装安装包要用的 content:// 地址。
     * 本包自带的 provider（TermuxOpenReceiver$ContentProvider）只认 /data/data/&lt;包名&gt;/files 开头，
     * 不能直接用 getFilesDir() 给的 /data/user/0/…（多用户下同一处，但校验不认，会抛 Invalid path）。
     */
    public static String contentUri(String pkg, String relPath) {
        String rel = (relPath == null) ? "" : relPath;
        while (rel.startsWith("/")) rel = rel.substring(1);
        return "content://" + pkg + ".files/data/data/" + pkg + "/files/" + rel;
    }

    /** JSON 字符串转义（只处理会破坏 JSON 的字符，中文原样输出）。 */
    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') b.append("\\\"");
            else if (c == '\\') b.append("\\\\");
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else if (c == '\t') b.append("\\t");
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.toString();
    }

    /** poll() 的返回体。字段名按下发的契约固定，前端直接用。 */
    public static String stateJson(String state, long bytes, long total, String err, String name) {
        return stateJson(state, bytes, total, err, name, false);
    }

    /**
     * 同上，多一个 verified：true = 已按 GitHub 的 asset.digest 做过 SHA-256 比对；
     * false = 没校验（老资产没 digest，或还没下完）。UI 侧要在 ready 且 verified=false 时提示「未校验」。
     */
    public static String stateJson(String state, long bytes, long total, String err, String name, boolean verified) {
        StringBuilder b = new StringBuilder(176);
        b.append("{\"state\":\"").append(esc(state)).append("\"");
        b.append(",\"bytes\":").append(bytes);
        b.append(",\"total\":").append(total);
        b.append(",\"pct\":").append(pct(bytes, total));
        b.append(",\"err\":\"").append(esc(err)).append("\"");
        b.append(",\"name\":\"").append(esc(name)).append("\"");
        b.append(",\"verified\":").append(verified);
        b.append("}");
        return b.toString();
    }

    /**
     * 只保留当前这个 .apk，同目录下其它版本的 .apk 全删掉（安装成功后调用）。
     * 用户手机里不该越堆越多 26 MB 的旧安装包；.part 不在此列（续传要用）。
     * @return 删掉的个数
     */
    public static int pruneOtherApks(File dir, File keep) {
        if (dir == null || !dir.isDirectory()) return 0;
        File[] kids = dir.listFiles();
        if (kids == null) return 0;
        String keepPath = (keep == null) ? "" : keep.getAbsolutePath();
        int n = 0;
        for (int i = 0; i < kids.length; i++) {
            File f = kids[i];
            if (f == null || !f.isFile()) continue;
            String nm = f.getName();
            if (!nm.endsWith(".apk")) continue;
            if (f.getAbsolutePath().equals(keepPath)) continue;
            if (f.delete()) n++;
        }
        return n;
    }

    /** 文件的 SHA-256（小写 hex）。下载完用它核对 GitHub 给的 asset.digest。 */
    public static String sha256(File f) throws IOException {
        InputStream in = null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            in = new FileInputStream(f);
            byte[] buf = new byte[BUF];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            byte[] d = md.digest();
            StringBuilder b = new StringBuilder(d.length * 2);
            for (int i = 0; i < d.length; i++) {
                int v = d[i] & 0xff;
                if (v < 16) b.append('0');
                b.append(Integer.toHexString(v));
            }
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("这台机器没有 SHA-256 实现");
        } finally {
            closeQuietly(in);
        }
    }

    /* ---------------- 下载器实例 ---------------- */

    /**
     * 开始（或继续）下载。已经有 .part 就从它的长度续传，不会从头再来。
     * 正在下载 / 正在校验时重复调用直接忽略：前端连点两下不该起两个线程。
     */
    public void start(String url, long size, String sha256, String fileName) {
        if (url == null || url.length() == 0) {
            fail("没有可下载的地址，请先点「检查更新」");
            return;
        }
        Thread cur = worker;
        if (cur != null && cur.isAlive()) return;
        if (!dir.isDirectory() && !dir.mkdirs()) {
            fail("建不了更新目录（存储空间或权限有问题）");
            return;
        }
        String safe = sanitize(fileName);
        File part = new File(dir, safe + ".part");
        this.name = safe;
        this.url = url;
        this.sha = (sha256 == null) ? "" : sha256;
        this.total = (size > 0) ? size : 0L;
        long have = part.isFile() ? part.length() : 0L;
        if (this.total > 0 && have > this.total) {
            part.delete();
            have = 0L;
        }
        this.bytes = have;
        this.err = "";
        this.verified = false;
        this.stopped = false;
        setState(S_DOWNLOADING);
        Thread t = new Thread(new Job(this));
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    /** 取消：停线程、保留 .part（下次 start 从断点续）。 */
    public void cancel() {
        stopped = true;
        InputStream in = live;
        if (in != null) {
            try { in.close(); } catch (Throwable ignored) {}
        }
        Thread t = worker;
        if (t != null) {
            try { t.interrupt(); } catch (Throwable ignored) {}
        }
        err = "";
        setState(S_IDLE);
    }

    /** 前端轮询用。 */
    public String poll() {
        return stateJson(state, bytes, total, err, name, verified);
    }

    public String state() { return state; }
    public long bytes() { return bytes; }
    public long total() { return total; }
    public String err() { return err; }
    public String name() { return name; }

    /** 下载中的 .part（重命名成 .apk 之前都留这个后缀，断了能接着下）。 */
    public File partFile() { return new File(dir, name + ".part"); }

    /** 装的时候用的最终文件名。 */
    public File apkFile() { return new File(dir, name + ".apk"); }

    /** 状态迁移记录，逗号分隔（"downloading,verifying,ready"）。 */
    public String history() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < historyLen; i++) {
            if (i > 0) b.append(',');
            b.append(history[i]);
        }
        return b.toString();
    }

    /* ---------------- 内部实现 ---------------- */

    private void setState(String s) {
        if (s == null) s = S_IDLE;
        synchronized (history) {
            // 同一个状态不重复记（cancel 和 worker 收尾都会走到 idle）
            if (s.equals(state)) return;
            state = s;
            if (historyLen < history.length) {
                history[historyLen++] = s;
            } else {
                for (int i = 1; i < history.length; i++) history[i - 1] = history[i];
                history[history.length - 1] = s;
            }
        }
    }

    private void setBytes(long n) { bytes = n; }

    private void fail(String msg) {
        err = (msg == null) ? "下载失败" : msg;
        setState(S_FAIL);
    }

    private void canceled() {
        err = "";
        setState(S_IDLE);
    }

    /**
     * 真正搬字节的地方：一次尝试。Range 续传 / 服务器不支持 Range 时重下 / 416 丢掉重来，
     * 三种情况都在这里分叉；网络异常往上层抛，由 Job 决定重试几次。
     */
    private void transfer() throws IOException {
        File part = partFile();
        long have = part.isFile() ? part.length() : 0L;
        if (total > 0 && have >= total) {
            setBytes(have);
            return;
        }
        HttpURLConnection c = null;
        InputStream in = null;
        FileOutputStream out = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "AstrBot-Candle");
            // 别让服务器压包：压过的字节数和 Range 对不上，续传会错位
            c.setRequestProperty("Accept-Encoding", "identity");
            String range = rangeHeader(have);
            if (range != null) c.setRequestProperty("Range", range);
            int code = c.getResponseCode();
            boolean append;
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                append = (have > 0);
            } else if (code == HttpURLConnection.HTTP_OK) {
                append = false;                 // 服务器不认 Range：只能从头下
                have = 0L;
                part.delete();
            } else if (code == 416) {
                part.delete();                  // 本地比服务器上的还长：这份 .part 不能用了
                throw new IOException("服务器拒绝了续传范围（416），已丢弃重试");
            } else {
                throw new IOException("HTTP " + code);
            }
            long len = c.getContentLength();
            long start = append ? have : 0L;
            if (total <= 0 && len > 0) total = start + len;
            setBytes(start);
            in = c.getInputStream();
            live = in;
            out = new FileOutputStream(part, append);
            byte[] buf = new byte[BUF];
            long got = start;
            long lastPub = 0L;
            int n;
            while ((n = in.read(buf)) > 0) {
                if (stopped) throw new IOException("已取消");
                out.write(buf, 0, n);
                got += n;
                long now = System.currentTimeMillis();
                if (now - lastPub >= PROGRESS_MS) {
                    lastPub = now;
                    setBytes(got);                  // 每 200ms 回写一次进度
                }
            }
            out.flush();
            setBytes(got);
        } finally {
            live = null;
            closeQuietly(out);
            closeQuietly(in);
            if (c != null) {
                try { c.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }

    /** 下载完的校验：sha 不符就删掉 .part（留着会让人以为「下完了却装不上」）。 */
    private void verify() {
        setState(S_VERIFYING);
        File part = partFile();
        if (!part.isFile() || part.length() <= 0) {
            fail("下载的安装包不见了，请重新下载");
            return;
        }
        String got;
        try {
            got = sha256(part);
        } catch (Throwable t) {
            fail("校验失败：" + t);
            return;
        }
        if (!shaMatches(sha, got)) {
            part.delete();
            fail("安装包校验失败（SHA-256 对不上），已删除，请重新下载");
            return;
        }
        // sha 为空 = 这次没得比，如实标成「未校验」，别让前端以为验过了
        verified = (sha != null && stripSha(sha).length() > 0);
        setBytes(part.length());
        setState(S_READY);
    }

    private static String netError(Throwable t) {
        String m = (t == null) ? "" : String.valueOf(t.getMessage());
        if (m.length() == 0) m = String.valueOf(t);
        return "下载中断：" + m + "（网络恢复后重试会从断点继续）";
    }

    static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (Throwable ignored) {}
    }

    /** 下载线程。具名顶层内部类，理由同 UpdateWorker：d8 处理匿名内部类会崩。 */
    private static final class Job implements Runnable {

        private final QqbotUpdaterCore owner;

        Job(QqbotUpdaterCore o) { owner = o; }

        public void run() {
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                try {
                    owner.transfer();
                    break;
                } catch (Throwable t) {
                    if (owner.stopped) { owner.canceled(); return; }
                    if (attempt >= MAX_ATTEMPTS) { owner.fail(netError(t)); return; }
                    try { Thread.sleep(1200L); } catch (Throwable ignored) {}
                    if (owner.stopped) { owner.canceled(); return; }
                }
            }
            if (owner.stopped) { owner.canceled(); return; }
            owner.verify();
        }
    }
}
