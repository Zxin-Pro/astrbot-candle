package com.termux.app;

import android.content.Context;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 崩溃现场快照。
 *
 * 任一层（沙盒 / NapCat / AstrBot）判定掉线，或守护服务被系统重建时，落一份 JSON：
 *   files/home/.qqbot/crash-scene/<yyyyMMdd-HHmmss>.json
 * 内容：kind、at、最近 200 行相关日志，以及电量 / 温度 / 内存 / 运行时长。
 * 只保留最近 10 份，供 AndroidLayer.crashList / crashGet / crashDel 读取。
 *
 * 说明：
 *  - 不依赖 org.json（纯 JVM 自测时没有实现），JSON 全部手写、手解；
 *  - 不写 lambda / 方法引用 / switch 表达式，兼容老 d8。
 */
public final class QqbotCrashScene {

    public static final int KEEP = 10;
    private static final int TEXT_LINES = 1200;   // 每份日志 200 行 × 最多 6 份，避免互相挤掉
    private static final int SRC_BYTES = 256 * 1024;   // 单个日志最多倒读 256KB
    private static final int TEXT_MAX = 96 * 1024;     // 快照里的日志正文上限，保证 crashGet 读得回来
    private static final String MARK = "guard.live";
    private static final Object LOCK = new Object();

    private QqbotCrashScene() {
    }

    public static File dir(File filesDir) {
        return new File(QqbotShell.qqbotDir(filesDir), "crash-scene");
    }

    /** 落一份快照，成功返回 id（yyyyMMdd-HHmmss），失败返回空串。 */
    public static String capture(File filesDir, String kind, String title, String why) {
        if (filesDir == null) {
            return "";
        }
        synchronized (LOCK) {
            try {
                File d = dir(filesDir);
                if (!d.isDirectory() && !d.mkdirs()) {
                    return "";
                }
                String id = "";
                File f = null;
                for (int tryNo = 0; tryNo < 3; tryNo++) {   // 同一秒内多次触发：等下一秒，文件名 / JSON id 始终一致
                    id = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
                    f = new File(d, id + ".json");
                    if (!f.exists()) {
                        break;
                    }
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                if (f == null) {
                    return "";
                }
                String text = collect(filesDir);
                long at = System.currentTimeMillis();
                int batt = -1;
                double temp = 0;
                try {
                    batt = BatteryMon.batteryLevel();
                    temp = Math.round(BatteryMon.tempC() * 10f) / 10.0;
                } catch (Throwable ignored) {
                }
                StringBuilder sb = new StringBuilder(text.length() + 512);
                sb.append("{\"id\":\"").append(id).append("\"")
                  .append(",\"kind\":\"").append(esc(kind)).append("\"")
                  .append(",\"title\":\"").append(esc(title)).append("\"")
                  .append(",\"why\":\"").append(esc(why)).append("\"")
                  .append(",\"at\":").append(at)
                  .append(",\"batt\":").append(batt)
                  .append(",\"temp\":").append(temp)
                  .append(",\"memMb\":").append(memMb())
                  .append(",\"upMin\":").append(upMinutes(filesDir))
                  .append(",\"text\":\"").append(esc(text)).append("\"}");
                if (!StateIO.write(f, sb.toString())) {
                    return "";
                }
                prune(d);
                return id;
            } catch (Throwable t) {
                return "";
            }
        }
    }

    // ---------------------------------------------------------------- 采集

    private static String collect(File filesDir) {
        StringBuilder sb = new StringBuilder(16384);
        File qq = QqbotShell.qqbotDir(filesDir);
        addLog(sb, new File(qq, "logs/watchdog.log"), "logs/watchdog.log", 200);
        addLog(sb, new File(qq, "logs/guard.log"), "logs/guard.log", 200);
        addAstrbot(sb, filesDir);
        String all = sb.toString();
        String lines = lastLines(all, TEXT_LINES);
        if (lines.length() > TEXT_MAX) {
            lines = lines.substring(lines.length() - TEXT_MAX);
            int nl = lines.indexOf('\n');   // 截断后从下一行开始，别把半行日志塞进快照
            if (nl >= 0 && nl + 1 < lines.length()) {
                lines = lines.substring(nl + 1);
            }
        }
        return lines;
    }

    private static void addAstrbot(StringBuilder sb, File filesDir) {
        File home = QqbotShell.homeDir(filesDir);
        File[] dirs = new File[] {
                new File(home, "ubuntu/opt/AstrBot/data/logs"),
                new File(home, "ubuntu/root/astrbot/data/logs")
        };
        for (int i = 0; i < dirs.length; i++) {
            File[] fs = dirs[i].listFiles();
            if (fs == null) {
                continue;
            }
            File a = null;
            File b = null;
            for (int k = 0; k < fs.length; k++) {
                File f = fs[k];
                if (f == null || !f.isFile() || !f.getName().endsWith(".log")) {
                    continue;
                }
                if (a == null || f.lastModified() > a.lastModified()) {
                    b = a;
                    a = f;
                } else if (b == null || f.lastModified() > b.lastModified()) {
                    b = f;
                }
            }
            if (a != null) {
                addLog(sb, a, rel(home, a), 200);
            }
            if (b != null) {
                addLog(sb, b, rel(home, b), 200);
            }
        }
        addLog(sb, new File(qqOf(filesDir), "cache/logs/astrbot.log"), "cache/logs/astrbot.log", 200);
    }

    private static File qqOf(File filesDir) {
        return QqbotShell.qqbotDir(filesDir);
    }

    private static String rel(File base, File f) {
        try {
            String b = base.getAbsolutePath();
            String p = f.getAbsolutePath();
            if (p.startsWith(b)) {
                String r = p.substring(b.length());
                if (r.startsWith(File.separator)) {
                    r = r.substring(1);
                }
                return r;
            }
            return p;
        } catch (Throwable t) {
            return f.getName();
        }
    }

    private static void addLog(StringBuilder sb, File f, String label, int maxLines) {
        String t = tail(f, SRC_BYTES);
        if (t == null) {
            return;
        }
        t = lastLines(t, maxLines).trim();
        if (t.length() == 0) {
            return;
        }
        sb.append("===== ").append(label).append(" =====\n");
        sb.append(t).append('\n');
    }

    static String lastLines(String text, int max) {
        if (text == null || text.length() == 0) {
            return "";
        }
        int n = 0;
        for (int i = text.length() - 1; i >= 0; i--) {
            if (text.charAt(i) == '\n') {
                n++;
                if (n >= max) {
                    return text.substring(i + 1);
                }
            }
        }
        return text;
    }

    /** 倒读文件尾部（最多 maxBytes），返回 UTF-8 文本。 */
    static String tail(File f, int maxBytes) {
        if (f == null || !f.isFile() || f.length() <= 0) {
            return null;
        }
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            long len = raf.length();
            int take = (int) Math.min(len, (long) maxBytes);
            byte[] buf = new byte[take];
            raf.seek(len - take);
            raf.readFully(buf);
            String s = new String(buf, "UTF-8");
            if (len > take) {
                int nl = s.indexOf('\n');       // 丢掉可能被截断的首行
                if (nl >= 0 && nl + 1 < s.length()) {
                    s = s.substring(nl + 1);
                }
            }
            return s;
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

    // ---------------------------------------------------------------- 环境

    static long memMb() {
        long total = 0;
        long avail = 0;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader("/proc/meminfo"));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("MemTotal:")) {
                    total = kbOf(line);
                } else if (line.startsWith("MemAvailable:")) {
                    avail = kbOf(line);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Throwable ignored) {
                }
            }
        }
        if (total <= 0) {
            return 0;
        }
        return Math.max(0L, (total - avail) / 1024L);
    }

    private static long kbOf(String line) {
        try {
            int i = line.indexOf(':');
            if (i < 0) {
                return 0;
            }
            String rest = line.substring(i + 1).trim();
            int sp = rest.indexOf(' ');
            if (sp > 0) {
                rest = rest.substring(0, sp);
            }
            return Long.parseLong(rest);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 沙盒运行时长（分钟）：supervisor.pid 的进程年龄；取不到返回 0。 */
    static long upMinutes(File filesDir) {
        try {
            File pidFile = new File(QqbotShell.qqbotDir(filesDir), "supervisor.pid");
            String txt = StateIO.read(pidFile);
            if (txt == null) {
                return 0;
            }
            String[] parts = txt.trim().split("\\s+");
            int pid = Integer.parseInt(parts[0]);
            long age = procAgeSec(pid);
            if (age < 0) {
                return 0;
            }
            return age / 60L;
        } catch (Throwable t) {
            return 0;
        }
    }

    static long procAgeSec(int pid) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader("/proc/uptime"));
            String up = br.readLine();
            br.close();
            br = null;
            double upSec = Double.parseDouble(up.trim().split("\\s+")[0]);
            String stat = readSmall(new File("/proc/" + pid + "/stat"));
            if (stat == null) {
                return -1;
            }
            int p = stat.lastIndexOf(')');
            if (p < 0) {
                return -1;
            }
            String rest = stat.substring(p + 1).trim();
            String[] f = rest.split("\\s+");
            if (f.length < 20) {
                return -1;
            }
            double start = Double.parseDouble(f[19]) / 100.0;
            long age = (long) (upSec - start);
            return age > 0 ? age : 0;
        } catch (Throwable t) {
            return -1;
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String readSmall(File f) {
        RandomAccessFile raf = null;
        try {
            if (!f.isFile()) {
                return null;
            }
            raf = new RandomAccessFile(f, "r");
            int take = (int) Math.min(raf.length(), 64L * 1024L);
            byte[] buf = new byte[take];
            raf.readFully(buf);
            return new String(buf, "UTF-8");
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

    // ---------------------------------------------------------------- 列表 / 读取 / 删除

    public static String listJson(File filesDir) {
        try {
            List<File> all = listFiles(filesDir);
            StringBuilder sb = new StringBuilder(1024);
            sb.append("{\"ok\":true,\"items\":[");
            for (int i = 0; i < all.size() && i < KEEP; i++) {
                File f = all.get(i);
                String raw = StateIO.read(f);
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("{\"id\":\"").append(esc(str(raw, "id"))).append("\"")
                  .append(",\"at\":").append(num(raw, "at"))
                  .append(",\"kind\":\"").append(esc(str(raw, "kind"))).append("\"")
                  .append(",\"title\":\"").append(esc(str(raw, "title"))).append("\"")
                  .append(",\"size\":").append(f.length())
                  .append('}');
            }
            sb.append("]}");
            return sb.toString();
        } catch (Throwable t) {
            return fail("读取崩溃快照列表失败：" + t);
        }
    }

    public static String getJson(File filesDir, String id) {
        try {
            if (!validId(id)) {
                return fail("快照编号不对");
            }
            File f = new File(dir(filesDir), id + ".json");
            String raw = StateIO.read(f);
            if (raw == null) {
                return fail("找不到这份快照");
            }
            StringBuilder sb = new StringBuilder(raw.length() + 256);
            sb.append("{\"ok\":true")
              .append(",\"id\":\"").append(esc(str(raw, "id"))).append("\"")
              .append(",\"at\":").append(num(raw, "at"))
              .append(",\"kind\":\"").append(esc(str(raw, "kind"))).append("\"")
              .append(",\"title\":\"").append(esc(str(raw, "title"))).append("\"")
              .append(",\"why\":\"").append(esc(str(raw, "why"))).append("\"")
              .append(",\"text\":\"").append(esc(str(raw, "text"))).append("\"")
              .append(",\"env\":{\"batt\":").append(num(raw, "batt"))
              .append(",\"temp\":").append(numDouble(raw, "temp"))
              .append(",\"memMb\":").append(num(raw, "memMb"))
              .append(",\"upMin\":").append(num(raw, "upMin"))
              .append("}}");
            return sb.toString();
        } catch (Throwable t) {
            return fail("读取快照失败：" + t);
        }
    }

    public static String delJson(File filesDir, String id) {
        try {
            if (!validId(id)) {
                return fail("快照编号不对");
            }
            File f = new File(dir(filesDir), id + ".json");
            if (f.isFile() && !f.delete()) {
                return fail("删除失败，可能文件被占用");
            }
            return "{\"ok\":true}";
        } catch (Throwable t) {
            return fail("删除快照失败：" + t);
        }
    }

    private static List<File> listFiles(File filesDir) {
        List<File> all = new ArrayList<File>();
        File[] fs = dir(filesDir).listFiles();
        if (fs == null) {
            return all;
        }
        for (int i = 0; i < fs.length; i++) {
            File f = fs[i];
            if (f != null && f.isFile() && f.getName().endsWith(".json")) {
                all.add(f);
            }
        }
        // 文件名带时间戳，倒序 = 最新在前（手写插入排序，避免 Comparator/lambda）
        for (int i = 1; i < all.size(); i++) {
            File v = all.get(i);
            int j = i - 1;
            while (j >= 0 && all.get(j).getName().compareTo(v.getName()) < 0) {
                all.set(j + 1, all.get(j));
                j--;
            }
            all.set(j + 1, v);
        }
        return all;
    }

    static void prune(File d) {
        List<File> all = new ArrayList<File>();
        File[] fs = d.listFiles();
        if (fs == null) {
            return;
        }
        for (int i = 0; i < fs.length; i++) {
            File f = fs[i];
            if (f != null && f.isFile() && f.getName().endsWith(".json")) {
                all.add(f);
            }
        }
        for (int i = 1; i < all.size(); i++) {
            File v = all.get(i);
            int j = i - 1;
            while (j >= 0 && all.get(j).getName().compareTo(v.getName()) < 0) {
                all.set(j + 1, all.get(j));
                j--;
            }
            all.set(j + 1, v);
        }
        for (int i = KEEP; i < all.size(); i++) {
            try {
                all.get(i).delete();
            } catch (Throwable ignored) {
            }
        }
    }

    static boolean validId(String id) {
        if (id == null || id.length() != 15 || id.charAt(8) != '-') {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            if (i == 8) {
                continue;
            }
            char c = id.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- 服务重建检测

    /** QqbotGuardService.onCreate 调用：判断上次守护进程是否没有正常退出。 */
    public static void onServiceCreate(Context c) {
        try {
            if (c == null) {
                return;
            }
            final File filesDir = c.getFilesDir();
            final Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
            Thread t = new Thread(new BootCheck(app, filesDir), "qqbot-crash-boot");
            t.setDaemon(true);
            t.start();
        } catch (Throwable ignored) {
        }
    }

    /** QqbotGuardService.onDestroy 调用：正常退出就抹掉存活标记。 */
    public static void onServiceDestroy(File filesDir) {
        try {
            StateIO.delete(StateIO.file(filesDir, MARK));
        } catch (Throwable ignored) {
        }
    }

    private static final class BootCheck implements Runnable {
        private final Context app;
        private final File filesDir;

        BootCheck(Context app, File filesDir) {
            this.app = app;
            this.filesDir = filesDir;
        }

        public void run() {
            try {
                File m = StateIO.file(filesDir, MARK);
                String old = StateIO.read(m);
                long nowEl = SystemClock.elapsedRealtime();
                if (old != null) {
                    long oldEl = num(old, "elapsed");
                    long pid = num(old, "pid");
                    boolean sameBoot = oldEl <= 0 || nowEl >= oldEl;
                    boolean alive = pid > 0 && pid <= 2147483647L && procAlive((int) pid);
                    if (sameBoot && !alive) {
                        String cid = capture(filesDir, "service", "守护服务被系统重建",
                                "上次守护进程 pid=" + pid + " 没有正常退出（可能是被系统杀掉的）");
                        try {
                            String body = cid.length() > 0
                                    ? "上次守护进程没有正常退出，现场已存档：" + cid
                                    : "上次守护进程没有正常退出，现场存档失败";
                            QqbotAlertCenter.raise(app, "crash", "crit", "守护服务被系统重建", body);
                        } catch (Throwable ignored) {
                        }
                    }
                }
                StringBuilder sb = new StringBuilder(128);
                sb.append("{\"pid\":").append(android.os.Process.myPid())
                  .append(",\"at\":").append(System.currentTimeMillis())
                  .append(",\"elapsed\":").append(nowEl).append('}');
                StateIO.write(m, sb.toString());
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean procAlive(int pid) {
        try {
            File f = new File("/proc/" + pid + "/cmdline");
            if (!f.isFile()) {
                return false;
            }
            String c = readSmall(f);
            if (c == null) {
                return false;
            }
            return c.indexOf("com.termux") >= 0 || c.indexOf("qqbot") >= 0;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- JSON 小工具

    static String fail(String msg) {
        return "{\"ok\":false,\"err\":\"" + esc(msg) + "\"}";
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                sb.append("\\\"");
            } else if (c == '\\') {
                sb.append("\\\\");
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c < 0x20) {
                String h = Integer.toHexString(c);
                sb.append("\\u");
                for (int k = h.length(); k < 4; k++) {
                    sb.append('0');
                }
                sb.append(h);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static String unesc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            i++;
            char e = s.charAt(i);
            if (e == 'n') {
                sb.append('\n');
            } else if (e == 'r') {
                sb.append('\r');
            } else if (e == 't') {
                sb.append('\t');
            } else if (e == '"') {
                sb.append('"');
            } else if (e == '\\') {
                sb.append('\\');
            } else if (e == '/') {
                sb.append('/');
            } else if (e == 'u' && i + 4 < s.length()) {
                try {
                    sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                    i += 4;
                } catch (Throwable t) {
                    sb.append("\\u");
                }
            } else {
                sb.append(e);
            }
        }
        return sb.toString();
    }

    static String str(String json, String key) {
        if (json == null || key == null) {
            return "";
        }
        String pat = "\"" + key + "\"";
        int i = json.indexOf(pat);
        if (i < 0) {
            return "";
        }
        int c = json.indexOf(':', i + pat.length());
        if (c < 0) {
            return "";
        }
        int q = c + 1;
        while (q < json.length() && json.charAt(q) != '"') {
            if (json.charAt(q) == ',' || json.charAt(q) == '}') {
                return "";
            }
            q++;
        }
        if (q >= json.length()) {
            return "";
        }
        int e = q + 1;
        while (e < json.length()) {
            char ch = json.charAt(e);
            if (ch == '\\') {
                e += 2;
                continue;
            }
            if (ch == '"') {
                break;
            }
            e++;
        }
        if (e >= json.length()) {
            return "";
        }
        return unesc(json.substring(q + 1, e));
    }

    static long num(String json, String key) {
        long[] r = new long[1];
        if (!numAt(json, key, r)) {
            return 0L;
        }
        return r[0];
    }

    static boolean bool(String json, String key, boolean def) {
        if (json == null || key == null) {
            return def;
        }
        String pat = "\"" + key + "\"";
        int i = json.indexOf(pat);
        if (i < 0) {
            return def;
        }
        int c = json.indexOf(':', i + pat.length());
        if (c < 0) {
            return def;
        }
        int j = c + 1;
        while (j < json.length() && Character.isWhitespace(json.charAt(j))) {
            j++;
        }
        if (json.startsWith("true", j)) {
            return true;
        }
        if (json.startsWith("false", j)) {
            return false;
        }
        return def;
    }

    /** 把 key 对应的数字写进 out[0]，返回是否找到。 */
    static double numDouble(String json, String key) {
        if (json == null || key == null) {
            return 0;
        }
        String pat = "\"" + key + "\"";
        int i = json.indexOf(pat);
        if (i < 0) {
            return 0;
        }
        int c = json.indexOf(':', i + pat.length());
        if (c < 0) {
            return 0;
        }
        int j = c + 1;
        while (j < json.length() && Character.isWhitespace(json.charAt(j))) {
            j++;
        }
        int start = j;
        if (j < json.length() && (json.charAt(j) == '-' || json.charAt(j) == '+')) {
            j++;
        }
        while (j < json.length()) {
            char ch = json.charAt(j);
            if (Character.isDigit(ch) || ch == '.' || ch == 'e' || ch == 'E' || ch == '+' || ch == '-') {
                j++;
            } else {
                break;
            }
        }
        try {
            return Double.parseDouble(json.substring(start, j));
        } catch (Throwable t) {
            return 0;
        }
    }

    static boolean numAt(String json, String key, long[] out) {
        if (json == null || key == null || out == null || out.length < 1) {
            return false;
        }
        String pat = "\"" + key + "\"";
        int i = json.indexOf(pat);
        if (i < 0) {
            return false;
        }
        int c = json.indexOf(':', i + pat.length());
        if (c < 0) {
            return false;
        }
        int j = c + 1;
        while (j < json.length() && Character.isWhitespace(json.charAt(j))) {
            j++;
        }
        int start = j;
        if (j < json.length() && (json.charAt(j) == '-' || json.charAt(j) == '+')) {
            j++;
        }
        int digitsAt = j;
        while (j < json.length() && Character.isDigit(json.charAt(j))) {
            j++;
        }
        if (j == digitsAt) {
            return false;
        }
        try {
            out[0] = Long.parseLong(json.substring(start, j));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
