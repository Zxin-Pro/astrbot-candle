package com.termux.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 反馈包桥（window.AndroidFeed.build() / share(id)）。
 *
 * 为什么要有它：用户报障时最需要的是「设备 + 日志 + 崩溃现场 + 配置」四样，
 * 而聊天里让用户一个个找文件是不现实的。所以本地打一个 zip，用户直接分享给客服。
 *
 * 四条设计红线（任何改动都要守住）：
 *   1) 只打包，不联网络。反馈包里可能有日志原文，绝不上传，只交给系统分享面板由用户自己发。
 *   2) 配置必须脱敏后才进包：cmd_config.json 里有 token / 密码哈希 / cookie。先在内存里
 *      解析成 JSON，把敏感键的值换成 ***，再写进 zip；解析失败就干脆不带，
 *      不能退化成「把原文贴进去」。
 *   3) 目录只许写 files/home/.qqbot/feedback，并且必须留在 files/ 下面 ——
 *      分享用的 content:// URI 由本包 TermuxOpenReceiver$ContentProvider 提供，
 *      它只认 /data/data/<包名>/files 开头的路径。
 *   4) 最多留 10 个包：反馈包动辄几百 KB，不回收会把用户存储吃满。
 *
 * 桥跑在 WebView 的 JS 线程上，所以每条路都整段 try/catch：
 * 抛到 JS 只会得到一条 undefined，用户什么也看不到。
 *
 * 本文件不出现匿名类（d8 处理匿名内部类会崩），也不用简写语法。
 */
public class QqbotFeedbackBridge {

    /** 单个日志只带尾部这么多：完整日志常有几十 MB，塞进去没人收得动。 */
    static final int LOG_TAIL = 200 * 1024;
    /** 最多带几份日志（按修改时间取最新）。 */
    static final int MAX_LOGS = 8;
    /** 崩溃现场最多带几条。 */
    static final int MAX_CRASH = 3;
    /** 崩溃文件读取上限。 */
    static final int CRASH_CAP = 200 * 1024;
    /** 配置文件读取上限：超过就当它不正常，不带。 */
    static final int CFG_CAP = 512 * 1024;
    /** 本地最多保留几个反馈包。 */
    static final int KEEP = 10;

    /** 键名里带这些片段的，值一律打码。 */
    private static final String[] SECRET_HINTS = {
            "token", "password", "passwd", "secret", "apikey", "api_key", "api-key",
            "cookie", "session", "credential", "authorization", "private_key"
    };

    private final Activity act;

    public QqbotFeedbackBridge(Activity act) {
        this.act = act;
    }

    /* ---------- 对外桥方法 ---------- */

    /**
     * build() 返回 {"ok":true,"id":"fb-20261028-2215","path":"...","size":123456,
     *             "items":["设备信息","日志 4 个","崩溃快照 2 条","配置（已脱敏）"]}
     * 任何单项读不到都不会让整包失败：少一样就少一样，包里会写明。
     */
    @JavascriptInterface
    public String build() {
        ZipOutputStream zos = null;
        File zip = null;
        try {
            File files = act.getFilesDir();
            File qq = QqbotShell.qqbotDir(files);
            File dir = new File(qq, "feedback");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return fail("建不了反馈包目录（存储满了？）");
            }

            String id = "fb-" + stamp("yyyyMMdd-HHmm");
            zip = new File(dir, id + ".zip");
            zos = new ZipOutputStream(new FileOutputStream(zip));
            // 6 是速度和体积的折中：日志是文本，压得动，但别为了省几 KB 卡住界面。
            zos.setLevel(6);

            JSONArray items = new JSONArray();

            try {
                putText(zos, "device.json", deviceJson().toString(2));
                items.put("设备信息");
            } catch (Throwable t) {
                items.put("设备信息（读取失败）");
            }

            int nlog = putLogs(zos, new File(qq, "logs"));
            items.put("日志 " + nlog + " 个");

            int ncrash = putCrashes(zos, new File(new File(qq, "cache"), "crashes"));
            items.put("崩溃快照 " + ncrash + " 条");

            boolean cfg = false;
            try {
                cfg = putConfig(zos, files);
            } catch (Throwable ignored) {
            }
            items.put(cfg ? "配置（已脱敏）" : "配置（未找到）");

            zos.finish();
            zos.close();
            zos = null;
            prune(dir);

            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("id", id);
            o.put("path", zip.getAbsolutePath());
            o.put("size", zip.length());
            o.put("items", items);
            return o.toString();
        } catch (Throwable t) {
            return fail("生成反馈包失败：" + oneLine(t));
        } finally {
            if (zos != null) {
                try {
                    zos.close();
                } catch (Throwable ignored) {
                }
            }
            // 半途失败留下的残包必须删掉，否则「分享最近一次」会分到半个 zip
            if (zip != null && !zip.isFile()) {
                try {
                    zip.delete();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * share(String id) 返回 {"ok":true}。id 可以是 build() 返回的 id（带不带 .zip 都行），
     * 也可以是空 / "latest"：那就分享最新那个包。
     *
     * 走系统分享面板而不是自己发：反馈包该去哪（微信 / QQ / 邮箱）只有用户知道。
     */
    @JavascriptInterface
    public String share(String id) {
        try {
            File files = act.getFilesDir();
            File dir = new File(QqbotShell.qqbotDir(files), "feedback");
            File z = resolve(dir, id);
            if (z == null || !z.isFile() || z.length() <= 0L) {
                return fail("还没有反馈包，先点「生成反馈包」");
            }
            ensureExternalAppsAllowed(files);
            String pkg = act.getPackageName();
            // provider 只认 /data/data/<包名>/files 开头，不能写成 getFilesDir() 的 /data/user/0/...
            Uri uri = Uri.parse("content://" + pkg + ".files/data/data/" + pkg
                    + "/files/home/.qqbot/feedback/" + z.getName());
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("application/zip");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, "QQ 机器人反馈包 " + z.getName());
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (!openChooser(send, "分享反馈包")) {
                return fail("这台手机没有能接收分享的应用（微信 / QQ 之类），先装一个");
            }
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("id", z.getName());
            return o.toString();
        } catch (Throwable t) {
            return fail("分享失败：" + oneLine(t));
        }
    }

    /* ---------- 打包内容 ---------- */

    private JSONObject deviceJson() {
        JSONObject o = new JSONObject();
        put(o, "brand", Build.BRAND);
        put(o, "manufacturer", Build.MANUFACTURER);
        put(o, "model", Build.MODEL);
        put(o, "device", Build.DEVICE);
        put(o, "android", Build.VERSION.RELEASE);
        try {
            o.put("sdk", Build.VERSION.SDK_INT);
        } catch (Throwable ignored) {
        }
        put(o, "abi", abi());
        try {
            o.put("pkg", act.getPackageName());
            o.put("app", appVersion());
            DisplayMetrics dm = act.getResources().getDisplayMetrics();
            o.put("w", dm.widthPixels);
            o.put("h", dm.heightPixels);
            o.put("density", (double) dm.density);
            File files = act.getFilesDir();
            StatFs st = new StatFs(files.getAbsolutePath());
            o.put("freeMb", (long) (st.getAvailableBytes() / (1024L * 1024L)));
            o.put("totalMb", (long) (st.getTotalBytes() / (1024L * 1024L)));
            o.put("uptimeMin", (long) (SystemClock.elapsedRealtime() / 60000L));
            o.put("batt", battPct());
            o.put("locale", Locale.getDefault().toString());
        } catch (Throwable ignored) {
        }
        return o;
    }

    private static void put(JSONObject o, String k, String v) {
        try {
            o.put(k, v == null ? "" : v);
        } catch (Throwable ignored) {
        }
    }

    private static String abi() {
        try {
            String[] a = Build.SUPPORTED_ABIS;
            if (a != null && a.length > 0 && a[0] != null) return a[0];
        } catch (Throwable ignored) {
        }
        return "";
    }

    private String appVersion() {
        try {
            PackageInfo pi = act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
            if (pi == null) return "?";
            String v = (pi.versionName == null) ? "?" : pi.versionName;
            return v + " (" + pi.versionCode + ")";
        } catch (Throwable t) {
            return "?";
        }
    }

    private int battPct() {
        try {
            IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent b = act.registerReceiver(null, f);
            if (b == null) return -1;
            int lv = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int sc = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (lv < 0 || sc <= 0) return -1;
            return (int) Math.round(lv * 100.0 / sc);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 日志：取 logs 目录里最新的 MAX_LOGS 个 *.log，每个只带尾部 LOG_TAIL。 */
    private static int putLogs(ZipOutputStream zos, File dir) {
        int n = 0;
        try {
            File[] all = dir.listFiles();
            if (all == null || all.length == 0) return 0;
            File[] sorted = new File[all.length];
            System.arraycopy(all, 0, sorted, 0, all.length);
            Arrays.sort(sorted, new ByMtimeDesc());
            for (int i = 0; i < sorted.length && n < MAX_LOGS; i++) {
                File f = sorted[i];
                if (f == null || !f.isFile()) continue;
                if (!f.getName().toLowerCase(Locale.ROOT).endsWith(".log")) continue;
                byte[] tail = tailOf(f, LOG_TAIL);
                if (tail == null || tail.length == 0) continue;
                try {
                    putBytes(zos, "logs/" + f.getName(), tail);
                    n++;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return n;
    }

    /** 崩溃现场：cache/crashes 下带 crash 字样的最新 MAX_CRASH 个。 */
    private static int putCrashes(ZipOutputStream zos, File dir) {
        int n = 0;
        try {
            File[] all = dir.listFiles();
            if (all == null || all.length == 0) return 0;
            File[] sorted = new File[all.length];
            System.arraycopy(all, 0, sorted, 0, all.length);
            Arrays.sort(sorted, new ByMtimeDesc());
            for (int i = 0; i < sorted.length && n < MAX_CRASH; i++) {
                File f = sorted[i];
                if (f == null || !f.isFile()) continue;
                String nm = f.getName().toLowerCase(Locale.ROOT);
                if (nm.indexOf("crash") < 0) continue;
                if (!nm.endsWith(".log") && !nm.endsWith(".txt") && !nm.endsWith(".json")) continue;
                byte[] body = headOf(f, CRASH_CAP);
                if (body == null || body.length == 0) continue;
                try {
                    putBytes(zos, "crashes/" + f.getName(), body);
                    n++;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return n;
    }

    /** 配置：解析成 JSON → 打码 → 写成 config.json。解析失败宁可不带。 */
    private static boolean putConfig(ZipOutputStream zos, File files) {
        try {
            File cfg = cfgPath(files);
            if (cfg == null || !cfg.isFile() || cfg.length() <= 0L) return false;
            if (cfg.length() > CFG_CAP) return false;
            String raw = readText(cfg, CFG_CAP);
            if (raw == null || raw.trim().length() == 0) return false;
            JSONObject o;
            try {
                o = new JSONObject(raw);
            } catch (Throwable t) {
                // 不是合法 JSON（脚本正在写 / 版本格式变了）就不要带：
                // 带原文等于把 token 一起打包出去，比起少一项严重得多。
                return false;
            }
            maskObj(o);
            putText(zos, "config.json", o.toString(2));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static File cfgPath(File files) {
        try {
            File a = AstrCfg.cfgFile(files);
            if (a != null && a.isFile()) return a;
        } catch (Throwable ignored) {
        }
        try {
            // 备用布局：AstrBot 装在 /root/astrbot/data 时配置在这里
            File b = new File(QqbotShell.homeDir(files), "ubuntu/root/astrbot/data/cmd_config.json");
            if (b.isFile()) return b;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /* ---------- 脱敏 ---------- */

    private static void maskObj(JSONObject o) {
        try {
            JSONArray ks = o.names();
            if (ks == null) return;
            for (int i = 0; i < ks.length(); i++) {
                String k = ks.optString(i, "");
                if (k == null || k.length() == 0) continue;
                Object v = o.opt(k);
                if (v instanceof JSONObject) {
                    maskObj((JSONObject) v);
                } else if (v instanceof JSONArray) {
                    maskArr((JSONArray) v);
                } else if (isSecretKey(k)) {
                    try {
                        o.put(k, "***");
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void maskArr(JSONArray a) {
        try {
            for (int i = 0; i < a.length(); i++) {
                Object v = a.opt(i);
                if (v instanceof JSONObject) {
                    maskObj((JSONObject) v);
                } else if (v instanceof JSONArray) {
                    maskArr((JSONArray) v);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isSecretKey(String k) {
        if (k == null) return false;
        String low = k.toLowerCase(Locale.ROOT);
        if ("key".equals(low) || low.endsWith("_key") || low.endsWith("-key")) return true;
        for (int i = 0; i < SECRET_HINTS.length; i++) {
            if (low.indexOf(SECRET_HINTS[i]) >= 0) return true;
        }
        return false;
    }

    /* ---------- zip 小工具 ---------- */

    private static void putText(ZipOutputStream zos, String name, String text) throws Exception {
        if (text == null) return;
        putBytes(zos, name, text.getBytes("UTF-8"));
    }

    private static void putBytes(ZipOutputStream zos, String name, byte[] b) throws Exception {
        if (b == null) return;
        ZipEntry e = new ZipEntry(name);
        e.setTime(System.currentTimeMillis());
        zos.putNextEntry(e);
        zos.write(b, 0, b.length);
        zos.closeEntry();
    }

    private static byte[] tailOf(File f, int cap) {
        RandomAccessFile raf = null;
        try {
            long len = f.length();
            if (len <= 0L) return null;
            long from = (len > (long) cap) ? (len - (long) cap) : 0L;
            int n = (int) (len - from);
            if (n <= 0) return null;
            byte[] buf = new byte[n];
            raf = new RandomAccessFile(f, "r");
            raf.seek(from);
            raf.readFully(buf);
            return buf;
        } catch (Throwable t) {
            return null;
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static byte[] headOf(File f, int cap) {
        FileInputStream in = null;
        try {
            long len = f.length();
            if (len <= 0L) return null;
            int n = (int) Math.min(len, (long) cap);
            byte[] buf = new byte[n];
            in = new FileInputStream(f);
            int got = 0;
            while (got < n) {
                int r = in.read(buf, got, n - got);
                if (r <= 0) break;
                got += r;
            }
            if (got <= 0) return null;
            if (got == n) return buf;
            byte[] trimmed = new byte[got];
            System.arraycopy(buf, 0, trimmed, 0, got);
            return trimmed;
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

    private static String readText(File f, int cap) {
        FileInputStream in = null;
        try {
            long len = f.length();
            if (len <= 0L) return "";
            int n = (int) Math.min(len, (long) cap);
            byte[] buf = new byte[n];
            in = new FileInputStream(f);
            int got = 0;
            while (got < n) {
                int r = in.read(buf, got, n - got);
                if (r <= 0) break;
                got += r;
            }
            return new String(buf, 0, got, "UTF-8");
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

    /* ---------- 分享 ---------- */

    private static File resolve(File dir, String id) {
        String want = (id == null) ? "" : id.trim();
        try {
            if (want.length() == 0 || "latest".equalsIgnoreCase(want) || "last".equalsIgnoreCase(want)) {
                File[] all = dir.listFiles();
                if (all == null) return null;
                File best = null;
                for (int i = 0; i < all.length; i++) {
                    File f = all[i];
                    if (f == null || !f.isFile()) continue;
                    if (!f.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) continue;
                    if (best == null || f.lastModified() > best.lastModified()) best = f;
                }
                return best;
            }
            // id 是从页面传进来的不可信输入：只许当文件名用，不许带路径
            if (want.indexOf('/') >= 0 || want.indexOf('\\') >= 0 || want.indexOf("..") >= 0) {
                return null;
            }
            File f = new File(dir, want.toLowerCase(Locale.ROOT).endsWith(".zip") ? want : (want + ".zip"));
            return f.isFile() ? f : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 单次分享最多 10 个：包多了就把最旧的删掉，和包内策略一致。 */
    private static void prune(File dir) {
        try {
            File[] all = dir.listFiles();
            if (all == null || all.length <= KEEP) return;
            File[] sorted = new File[all.length];
            System.arraycopy(all, 0, sorted, 0, all.length);
            Arrays.sort(sorted, new ByMtimeDesc());
            for (int i = KEEP; i < sorted.length; i++) {
                File f = sorted[i];
                if (f == null || !f.isFile()) continue;
                try {
                    f.delete();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean openChooser(Intent send, String label) {
        int others = 0;
        try {
            List<ResolveInfo> list = act.getPackageManager().queryIntentActivities(send, 0);
            for (int i = 0; i < list.size(); i++) {
                ResolveInfo ri = list.get(i);
                if (ri == null || ri.activityInfo == null) continue;
                if (act.getPackageName().equals(ri.activityInfo.packageName)) continue;
                others++;
            }
        } catch (Throwable ignored) {
        }
        if (others == 0) return false;
        Intent chooser = Intent.createChooser(send, label);
        chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, new ComponentName[] {
                new ComponentName(act.getPackageName(),
                        "com.termux.filepicker.TermuxFileReceiverActivity") });
        // chooser 自己也要带读权限与 NEW_TASK：接收方拿到的是 chooser 转发出去的 Intent
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        act.runOnUiThread(new UiStart(act, chooser));
        return true;
    }

    /**
     * Termux 自带 provider 默认不给外部应用读：openFile 先查
     * ~/.termux/termux.properties 的 allow-external-apps，不是 true 就拒绝。
     * 已经有这一项（哪怕写 false）就原样保留，不覆盖用户的设置。
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
                if (f.length() > 64L * 1024L) return;
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

    /* ---------- 杂项 ---------- */

    private static String stamp(String fmt) {
        try {
            return new SimpleDateFormat(fmt, Locale.US).format(new Date());
        } catch (Throwable t) {
            return String.valueOf(System.currentTimeMillis());
        }
    }

    private static String oneLine(Throwable t) {
        if (t == null) return "未知错误";
        String m = t.getMessage();
        String s = t.getClass().getSimpleName() + (m == null ? "" : (" " + m));
        s = s.replace('\n', ' ').replace('\r', ' ');
        if (s.length() > 120) s = s.substring(0, 120);
        return s;
    }

    private static String fail(String err) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("err", err);
            return o.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"err\":\"反馈包操作失败\"}";
        }
    }

    /** 按修改时间倒序（新的在前）。具名 Comparator：不用简写语法。 */
    private static final class ByMtimeDesc implements Comparator<File> {
        public int compare(File a, File b) {
            long x = (a == null) ? 0L : a.lastModified();
            long y = (b == null) ? 0L : b.lastModified();
            if (x == y) return 0;
            return (x < y) ? 1 : -1;
        }
    }

    /** 具名 Runnable：d8 处理匿名内部类会崩，起分享面板只能用嵌套类。 */
    private static final class UiStart implements Runnable {
        private final Activity act;
        private final Intent intent;

        UiStart(Activity a, Intent i) {
            act = a;
            intent = i;
        }

        public void run() {
            try {
                act.startActivity(intent);
            } catch (Throwable t) {
                try {
                    Toast.makeText(act, "打不开分享面板：" + t, Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
