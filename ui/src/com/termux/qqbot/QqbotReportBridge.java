package com.termux.app;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * beta.3 顶栏「上传错误报告」：window.AndroidReport。
 *
 * 真正干活的是沙盒里的 build/opt/qqbot/upload-log.sh（部署后
 * $PREFIX/opt/qqbot/upload-log.sh）。它会收集部署日志、机型、系统信息和组件日志，
 * 把 bots.json 的 token 打码后 POST 到作者的服务，正常要跑 30 到 120 秒。
 *
 * 所以 start() 只负责落留言和起后台线程，立刻返回空串；页面用 poll() 取进度。
 * 状态落在 state/report.json，App 重启后还能看到上一次的编号/结果。
 *
 * 注意：d8 处理匿名内部类会崩，本文件只用具名嵌套类，不用 lambda。
 */
public class QqbotReportBridge {

    /** 同一次进程里最多只跑一个上传。 */
    private static volatile boolean running = false;
    private static volatile long startedAt = 0L;

    private static final Object STATE_LOCK = new Object();
    private static final int TIMEOUT_SEC = 180;
    private static final int MAX_NOTE_CHARS = 500;
    private static final int MAX_NOTE_BYTES = 2000;

    private static final Pattern RID = Pattern.compile("编号\\s*([0-9][0-9-]*)");
    private static final Pattern ANSI = Pattern.compile("\\u001B\\[[0-9;]*[A-Za-z]");

    private final QqbotActivity act;

    public QqbotReportBridge(QqbotActivity act) {
        this.act = act;
    }

    /* ------------------------------ 桥入口 ------------------------------ */

    /**
     * 开始上传。note 是用户选填的留言，可为空。
     * 立即返回空串（前端不靠返回值判断，统一用 poll()）；异常才回失败 JSON。
     */
    @JavascriptInterface
    public String start(String note) {
        File files = null;
        try {
            files = act.filesDirPub();
            synchronized (STATE_LOCK) {
                if (running) {
                    return "";
                }
                running = true;
                startedAt = System.currentTimeMillis();
                writeDevNote(files, note);
                File script = script(files);
                if (!script.isFile()) {
                    running = false;
                    writeState(files, "err", "", "上传脚本还没就位（" + script.getAbsolutePath() + "），请先完成部署", 0L);
                    return "";
                }
                writeState(files, "running", "", "正在收集现场，大约要半分钟…", 0L);
                Thread th = new Thread(new ReportTask(files, script, appVersion()), "qqbot-report-upload");
                th.setDaemon(true);
                th.start();
                return "";
            }
        } catch (Throwable t) {
            running = false;
            try {
                if (files != null) {
                    writeState(files, "err", "", "上传没开始：" + human(t), 0L);
                }
            } catch (Throwable ignored) {
            }
            return QqbotBridge.failJson("上传没开始：" + human(t));
        }
    }

    /** 读上传状态：{ok,state,msg,id,size,at}。 */
    @JavascriptInterface
    public String poll() {
        try {
            File files = act.filesDirPub();
            JSONObject st = readState(files);
            String state = st == null ? "idle" : st.optString("state", "idle");
            String msg = st == null ? "" : st.optString("msg", "");
            String id = st == null ? "" : st.optString("id", "");
            long size = st == null ? 0L : st.optLong("size", 0L);
            long at = st == null ? 0L : st.optLong("at", 0L);

            if ("running".equals(state) && !running) {
                // 进程被系统杀掉后，report.json 可能停在 running；别让界面一直转。
                state = "err";
                msg = "上次上传中断了，请重新上传";
                writeState(files, state, "", msg, size);
            } else if (!"idle".equals(state) && !"running".equals(state)
                    && !"done".equals(state) && !"err".equals(state)) {
                state = "idle";
                msg = "";
                id = "";
                size = 0L;
                at = 0L;
            }

            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("state", state);
            o.put("msg", msg);
            o.put("id", id);
            o.put("size", size);
            o.put("at", at);
            return o.toString();
        } catch (Throwable t) {
            return QqbotBridge.failJson("读取上传状态失败：" + human(t));
        }
    }

    /**
     * 把最近一次生成的报告文件交给系统分享面板。
     * 和 QqbotShare.log() 同一条通道：复制到 files/share，再用 Termux 自带的
     * content provider 生成 content://... 的 Uri，不新增 FileProvider。
     */
    @JavascriptInterface
    public String share() {
        try {
            File files = act.filesDirPub();
            File src = new File(QqbotShell.qqbotDir(files), "cache/errreport.txt");
            if (!src.isFile() || src.length() <= 0L) {
                return QqbotBridge.failJson("还没有报告文件");
            }
            File dir = new File(files, "share");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return QqbotBridge.failJson("分享失败：建不了缓存目录");
            }
            File out = new File(dir, "qqbot-report-" + stamp() + ".txt");
            if (!copy(src, out)) {
                return QqbotBridge.failJson("分享失败：报告复制不出来");
            }
            prune(dir);
            ensureExternalAppsAllowed(files);

            String pkg = act.getPackageName();
            Uri uri = Uri.parse("content://" + pkg + ".files/data/data/" + pkg + "/files/share/" + out.getName());
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, "QQ 机器人错误报告");
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (countReceivers(send) == 0) {
                return QqbotBridge.failJson("这台手机没有能接收分享的应用（微信 / QQ 之类），先装一个再分享");
            }
            Intent chooser = Intent.createChooser(send, "分享错误报告");
            chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, new ComponentName[] {
                    new ComponentName(pkg, "com.termux.filepicker.TermuxFileReceiverActivity") });
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);

            CountDownLatch latch = new CountDownLatch(1);
            String[] err = new String[1];
            act.runOnUiThread(new ShareStart(act, chooser, err, latch));
            try {
                if (!latch.await(4L, TimeUnit.SECONDS)) {
                    return QqbotBridge.failJson("分享面板没弹出来，稍后再试");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return QqbotBridge.failJson("分享被打断了");
            }
            if (err[0] != null && err[0].length() > 0) {
                return QqbotBridge.failJson("分享失败：" + err[0]);
            }
            JSONObject o = new JSONObject();
            o.put("ok", true);
            return o.toString();
        } catch (Throwable t) {
            return QqbotBridge.failJson("分享失败：" + human(t));
        }
    }

    /* ------------------------------ 后台任务 ------------------------------ */

    private static final class ReportTask implements Runnable {
        private final File files;
        private final File script;
        private final String ver;

        ReportTask(File files, File script, String ver) {
            this.files = files;
            this.script = script;
            this.ver = ver;
        }

        public void run() {
            String out = "";
            String err = "";
            try {
                out = QqbotShell.run(files, "bash " + quote(script.getAbsolutePath()) + " " + quote(ver), TIMEOUT_SEC);
            } catch (Throwable t) {
                err = "收集失败：" + human(t);
            }
            try {
                finish(files, out, err);
            } finally {
                running = false;
            }
        }
    }

    /**
     * 把一次 qqbot-shell 的输出收成最终状态。
     * 正常输出末尾有一行「已上传，编号 20261029-...（...）」；
     * 超时由 QqbotShell.run 追加 [timeout]，单独给一句人话。
     */
    private static void finish(File files, String out, String err) {
        String raw = out == null ? "" : out;
        boolean timeout = containsLine(raw, "[timeout]");
        long size = reportSize(files);
        String tail = lastLine(raw);
        if (err != null && err.length() > 0) {
            writeState(files, "err", "", err, size);
            return;
        }
        if (timeout) {
            writeState(files, "err", "", "收集超时，检查网络后再试一次", size);
            return;
        }
        Matcher m = RID.matcher(raw);
        if (m.find()) {
            String rid = m.group(1);
            String msg = tail.length() == 0 ? "上传完成" : tail;
            writeState(files, "done", rid, msg, size);
            return;
        }
        String msg = tail.length() == 0 ? "上传没有回音（可能网络不通），稍后再试一次" : tail;
        writeState(files, "err", "", msg, size);
    }

    private static void writeState(File files, String state, String id, String msg, long size) {
        try {
            JSONObject o = new JSONObject();
            o.put("state", state);
            o.put("id", id == null ? "" : id);
            o.put("msg", msg == null ? "" : msg);
            o.put("at", System.currentTimeMillis());
            o.put("size", size);
            StateIO.write(files, "report.json", o.toString());
        } catch (Throwable ignored) {
        }
    }

    private static JSONObject readState(File files) {
        try {
            String s = StateIO.read(files, "report.json");
            if (s == null || s.trim().length() == 0) {
                return null;
            }
            return new JSONObject(s);
        } catch (Throwable t) {
            return null;
        }
    }

    /* ------------------------------ 留言 ------------------------------ */

    /**
     * 留言写到 files/home/devnote.txt；同时镜像到脚本真正读取的
     * files/home/.qqbot/devnote.txt（upload-log.sh 里 NOTE=$QQBOT_HOME/devnote.txt）。
     * 两个都写，避免脚本版本差异导致留言丢失。
     */
    private static void writeDevNote(File files, String note) {
        String s = note == null ? "" : note.replace("\r", "").trim();
        if (s.length() > MAX_NOTE_CHARS) {
            s = s.substring(0, MAX_NOTE_CHARS);
        }
        while (s.length() > 0 && utf8Len(s) > MAX_NOTE_BYTES) {
            int cut = s.length() - 1;
            if (cut > 0 && Character.isHighSurrogate(s.charAt(cut - 1))) {
                cut--;
            }
            s = s.substring(0, cut);
        }
        File home = new File(QqbotShell.homeDir(files), "devnote.txt");
        File qq = new File(QqbotShell.qqbotDir(files), "devnote.txt");
        if (s.length() == 0) {
            try { home.delete(); } catch (Throwable ignored) { }
            try { qq.delete(); } catch (Throwable ignored) { }
            return;
        }
        writeText(home, s);
        writeText(qq, s);
    }

    private static int utf8Len(String s) {
        try {
            return s.getBytes("UTF-8").length;
        } catch (Throwable t) {
            return s.length() * 3;
        }
    }

    private static void writeText(File f, String s) {
        FileOutputStream out = null;
        OutputStreamWriter w = null;
        try {
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory()) {
                dir.mkdirs();
            }
            out = new FileOutputStream(f);
            w = new OutputStreamWriter(out, "UTF-8");
            w.write(s);
            w.flush();
        } catch (Throwable ignored) {
        } finally {
            if (w != null) { try { w.close(); } catch (Throwable ignored) { } }
            if (out != null) { try { out.close(); } catch (Throwable ignored) { } }
        }
    }

    /* ------------------------------ 分享工具 ------------------------------ */

    private static final class ShareStart implements Runnable {
        private final QqbotActivity act;
        private final Intent intent;
        private final String[] err;
        private final CountDownLatch latch;

        ShareStart(QqbotActivity act, Intent intent, String[] err, CountDownLatch latch) {
            this.act = act;
            this.intent = intent;
            this.err = err;
            this.latch = latch;
        }

        public void run() {
            try {
                act.startActivity(intent);
            } catch (Throwable t) {
                err[0] = human(t);
                try {
                    Toast.makeText(act, "打不开分享面板：" + human(t), Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {
                }
            } finally {
                latch.countDown();
            }
        }
    }

    private int countReceivers(Intent send) {
        int others = 0;
        try {
            List<ResolveInfo> list = act.getPackageManager().queryIntentActivities(send, 0);
            for (int i = 0; list != null && i < list.size(); i++) {
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

    private static boolean copy(File src, File dst) {
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
            out.close();
            out = null;
            return dst.isFile() && dst.length() > 0L;
        } catch (Throwable t) {
            return false;
        } finally {
            if (in != null) { try { in.close(); } catch (Throwable ignored) { } }
            if (out != null) { try { out.close(); } catch (Throwable ignored) { } }
        }
    }

    private static void prune(File dir) {
        try {
            File[] all = dir.listFiles();
            if (all == null) {
                return;
            }
            int n = 0;
            for (int i = 0; i < all.length; i++) {
                if (all[i] != null && all[i].getName().startsWith("qqbot-report-")) {
                    n++;
                }
            }
            if (n <= 5) {
                return;
            }
            File[] mine = new File[n];
            int k = 0;
            for (int i = 0; i < all.length; i++) {
                if (all[i] != null && all[i].getName().startsWith("qqbot-report-")) {
                    mine[k++] = all[i];
                }
            }
            java.util.Arrays.sort(mine, new ByMtimeDesc());
            for (int i = 5; i < mine.length; i++) {
                try { mine[i].delete(); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) {
        }
    }

    private static final class ByMtimeDesc implements java.util.Comparator<File> {
        public int compare(File a, File b) {
            long x = a == null ? 0L : a.lastModified();
            long y = b == null ? 0L : b.lastModified();
            if (x == y) return 0;
            return x < y ? 1 : -1;
        }
    }

    /**
     * Termux 自带的 provider 默认不对外；和 QqbotShare 一样确保 termux.properties 里有
     * allow-external-apps=true。已有 true 就不重复写；已有 false 则追加一行 true。
     */
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
                    if (n <= 0) break;
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
            if (in != null) { try { in.close(); } catch (Throwable ignored) { } }
            if (out != null) { try { out.close(); } catch (Throwable ignored) { } }
        }
    }

    /* ------------------------------ 小工具 ------------------------------ */

    static File script(File filesDir) {
        return new File(QqbotShell.usrDir(filesDir), "opt/qqbot/upload-log.sh");
    }

    private String appVersion() {
        try {
            String v = act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
            return (v == null || v.length() == 0) ? "unknown" : v;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static long reportSize(File files) {
        try {
            File f = new File(QqbotShell.qqbotDir(files), "cache/errreport.txt");
            return f.isFile() ? f.length() : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static boolean containsLine(String s, String line) {
        if (s == null || line == null) {
            return false;
        }
        String[] lines = s.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            if (line.equals(lines[i].trim())) {
                return true;
            }
        }
        return false;
    }

    private static String lastLine(String s) {
        String t = s == null ? "" : s.replace("[timeout]", "").replace("\r", "");
        String[] lines = t.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = clean(lines[i]);
            if (line.length() > 0) {
                return line;
            }
        }
        return "";
    }

    private static String clean(String s) {
        if (s == null) {
            return "";
        }
        String t = ANSI.matcher(s).replaceAll("");
        StringBuilder b = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\t') {
                b.append(' ');
            } else if (c >= 32 && c != 127) {
                b.append(c);
            }
        }
        String out = b.toString().trim();
        if (out.length() > 300) {
            out = out.substring(0, 300);
        }
        return out;
    }

    private static String quote(String s) {
        String t = s == null ? "" : s;
        return "'" + t.replace("'", "'\\''") + "'";
    }

    private static String stamp() {
        try {
            return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        } catch (Throwable t) {
            return String.valueOf(System.currentTimeMillis());
        }
    }

    static String human(Throwable t) {
        try {
            String m = t == null ? "" : t.getMessage();
            if (m != null && m.length() > 0) {
                return m;
            }
            return t == null ? "未知错误" : t.getClass().getSimpleName();
        } catch (Throwable ignored) {
            return "未知错误";
        }
    }
}
