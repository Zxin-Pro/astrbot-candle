package com.termux.app;

import android.webkit.JavascriptInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;

/**
 * beta.3「运行健康」的原生侧：window.AndroidHealth。
 *
 * 分工：真正干活的都在脚本里 —— crashfuse.sh（崩溃熔断）、backup.sh（配置快照与恢复）、
 * run-all.sh（定时重启 / 夜间降频，读同一份 schedule.json）。本类只做四件事：
 *   1. status() / restoreList() / crashSummary() 全部是「读几个小 JSON」，同步返回；
 *   2. backupNow() / restore() / clearFuse() / setSchedule() 立刻返回，重活丢后台线程
 *      （QqbotShell.run 是阻塞的，绝不能上桥线程）；
 *   3. 写 schedule.json 走 StateIO（原子写），沙盒里的 run-all.sh 每 30 秒读一次；
 *   4. 出错不抛异常：桥方法抛异常会让页面的 JS 收到 undefined，用户看到的是「点了没反应」。
 *
 * 为什么 JSON 全用手拼、不用 org.json：本类的「读文件 → 拼 JSON」这段要能在普通 JVM 上
 * 单测（out/b3_runtime_check.py），不能依赖 Android 的 org.json 桩。
 *
 * d8 处理匿名内部类会崩：本文件无匿名内部类、无 lambda（HealthTask 是静态嵌套类）。
 */
public class QqbotHealthBridge {

    /** 后台任务名（backup / restore / fuse），空串 = 空闲。 */
    private static volatile String busy = "";
    /** 上一轮后台任务为什么没成（失败原因），成功后清空。 */
    private static volatile String lastErr = "";
    /** 上一轮后台任务的 stdout 尾部（backup 会打 SNAP=…，恢复会打 RESTORED=…）。 */
    private static volatile String lastOut = "";

    /** 各脚本的超时（秒）。备份要遍历插件目录，给足；熔断 + 重启要等 start 起来。 */
    private static final int T_BACKUP = 240;
    private static final int T_RESTORE = 180;
    private static final int T_FUSE = 120;

    private final QqbotActivity act;
    private final File filesDir;

    /** 页面里的桥用这个（注册时传 Activity）。 */
    public QqbotHealthBridge(QqbotActivity act) {
        this.act = act;
        this.filesDir = act.filesDirPub();
    }

    /** 只有 filesDir 的场景（服务 / 自检 / 单测）。 */
    public QqbotHealthBridge(File filesDir) {
        this.act = null;
        this.filesDir = filesDir;
    }

    /* ------------------------------ 桥入口 ------------------------------ */

    /**
     * 运行健康快照，同步返回：
     * {"ok":true,"crash":{...},"backup":{...},"schedule":{...},"busy":"","error":""}
     * 只读文件，不跑 shell —— 页面会隔几秒轮询一次。
     */
    @JavascriptInterface
    public String status() {
        try {
            File sd = stateDir();
            String s = statusJson(sd, backupDir(sd), busy, lastErr);
            if (s.length() == 0) return fail("读取运行状态失败");
            return s;
        } catch (Throwable t) {
            return fail("读取运行状态失败：" + t);
        }
    }

    /** 立刻生成一份手动快照。同步返回（结果看 status().backup），后台跑。 */
    @JavascriptInterface
    public void backupNow() {
        if (busy.length() > 0) return;
        File script = new File(QqbotShell.usrDir(filesDir), "opt/qqbot/backup.sh");
        if (!script.isFile()) {
            lastErr = "备份脚本还没就位（" + script.getAbsolutePath() + "），请先完成部署";
            return;
        }
        busy = "backup";
        lastErr = "";
        lastOut = "";
        start(new HealthTask(filesDir, "bash " + script.getAbsolutePath() + " manual",
                T_BACKUP, "backup"));
    }

    /** 列出可恢复的快照：[{id,at,size,reason,files}]，新的在前。同步返回（只读目录）。 */
    @JavascriptInterface
    public String restoreList() {
        try {
            return restoreListJson(backupDir(stateDir()));
        } catch (Throwable t) {
            return fail("读取备份列表失败：" + t);
        }
    }

    /**
     * 按 id 恢复某份快照。脚本会先给「当前配置」再打一份快照当回滚点，中途失败自动回滚，
     * 所以这里不用另做保护。同步返回，后台跑（大目录要几秒）。
     */
    @JavascriptInterface
    public void restore(String id) {
        String sid = (id == null) ? "" : id.trim();
        if (!validId(sid)) {
            lastErr = "备份编号不合法：" + sid;
            return;
        }
        if (busy.length() > 0) return;
        File script = new File(QqbotShell.usrDir(filesDir), "opt/qqbot/backup.sh");
        if (!script.isFile()) {
            lastErr = "备份脚本还没就位（" + script.getAbsolutePath() + "），请先完成部署";
            return;
        }
        busy = "restore";
        lastErr = "";
        lastOut = "";
        start(new HealthTask(filesDir, "bash " + script.getAbsolutePath() + " restore " + sid,
                T_RESTORE, "restore"));
    }

    /**
     * 写定时任务：{"restart":"04:00","night":true,"from":"23:00","to":"07:00"}。
     * restart 空串 = 不定时重启。写进 $HOME/.qqbot/state/schedule.json，沙盒里的
     * run-all.sh 每 30 秒读一次，最多半分钟生效，不用重启沙盒。
     */
    @JavascriptInterface
    public void setSchedule(String json) {
        try {
            String why = scheduleError(json);
            if (why.length() > 0) {
                lastErr = why;
                return;
            }
            File f = new File(stateDir(), "schedule.json");
            if (!StateIO.write(f, json.trim() + "\n")) {
                lastErr = "定时任务写不进去（state 目录不可写）";
                return;
            }
            lastErr = "";
        } catch (Throwable t) {
            lastErr = "设置定时任务失败：" + t;
        }
    }

    /**
     * 解除崩溃熔断，并重启一次沙盒（熔断期间 supervisor 不会自动拉起被熔断的服务，
     * 光清标记不重启的话用户会觉得「点了没反应」）。同步返回，后台跑。
     */
    @JavascriptInterface
    public void clearFuse() {
        if (busy.length() > 0) return;
        File fuse = new File(QqbotShell.usrDir(filesDir), "opt/qqbot/crashfuse.sh");
        File stop = new File(QqbotShell.usrDir(filesDir), "opt/qqbot/stop-sandbox.sh");
        File startSh = new File(QqbotShell.usrDir(filesDir), "opt/qqbot/start-sandbox.sh");
        if (!fuse.isFile()) {
            lastErr = "熔断脚本还没就位（" + fuse.getAbsolutePath() + "），请先完成部署";
            return;
        }
        File qd = QqbotShell.qqbotDir(filesDir);
        String cmd = "bash " + fuse.getAbsolutePath() + " clear all"
                + "; bash " + stop.getAbsolutePath() + " --no-offline"
                + "; mkdir -p " + new File(qd, "logs").getAbsolutePath()
                + "; nohup setsid bash " + startSh.getAbsolutePath() + " --user-start"
                + " >> " + new File(new File(qd, "logs"), "supervisor.log").getAbsolutePath()
                + " 2>&1 < /dev/null & sleep 4; echo ok";
        busy = "fuse";
        lastErr = "";
        lastOut = "";
        start(new HealthTask(filesDir, cmd, T_FUSE, "fuse"));
    }

    /** 人读的熔断摘要（点「崩溃熔断」那一行看详情用）。同步返回，只读几个小文件。 */
    @JavascriptInterface
    public String crashSummary() {
        try {
            String s = crashSummaryText(stateDir());
            if (lastErr.length() > 0) s = s + "\n（后台任务： " + lastErr + "）";
            return s;
        } catch (Throwable t) {
            return "读取熔断状态失败：" + t;
        }
    }

    /* ------------------------------ 后台任务 ------------------------------ */

    private void start(HealthTask t) {
        Thread th = new Thread(t, "qqbot-health-" + t.name);
        th.setDaemon(true);
        th.start();
    }

    /** 跑一次脚本。busy 做互斥，同一时刻只跑一个（备份和恢复撞一起会互相覆盖配置）。 */
    private static final class HealthTask implements Runnable {

        private final File filesDir;
        private final String cmd;
        private final int timeout;
        private final String name;

        HealthTask(File filesDir, String cmd, int timeout, String name) {
            this.filesDir = filesDir;
            this.cmd = cmd;
            this.timeout = timeout;
            this.name = name;
        }

        public void run() {
            String out = "";
            try {
                out = QqbotShell.run(filesDir, cmd, timeout);
                lastOut = lastLine(out);
                if (lastOut.length() > 0 && !lastOut.startsWith("SNAP=")
                        && !lastOut.startsWith("RESTORED=") && !"ok".equals(lastOut)) {
                    lastErr = lastOut;
                }
            } catch (Throwable t) {
                lastErr = "脚本执行失败：" + t;
            } finally {
                busy = "";
            }
        }
    }

    /* ------------------------------ 路径 ------------------------------ */

    private File stateDir() {
        return StateIO.dir(filesDir);
    }

    private static File backupDir(File stateDir) {
        File qd = stateDir.getParentFile();
        if (qd == null) return new File(stateDir, "backup");
        return new File(qd, "backup");
    }

    /* --------------------------- 纯函数（可单测） --------------------------- */

    /**
     * 拼 status() 的 JSON。只读文件，不碰 Android API —— out/b3_runtime_check.py 在普通
     * JVM 上直接调它，喂一份假的 state/backup 目录就能验证全部字段。
     */
    static String statusJson(File stateDir, File backupDir, String busyS, String errS) {
        try {
            String fuse = readFile(new File(stateDir, "crash-fuse.json"));
            boolean fused = fuse.indexOf("\"fused\":true") >= 0;
            long cnt = maxNum(fuse, "count");
            String lastAt = lastStr(fuse, "lastAt");
            String summary = fused ? strField(fuse, "summary") : "";

            String last = readFile(new File(stateDir, "backup-last.json"));
            String bAt = strField(last, "at");
            String bFile = strField(last, "file");
            int bCnt = countBackups(backupDir);

            String sched = readFile(new File(stateDir, "schedule.json"));

            StringBuilder b = new StringBuilder(512);
            b.append("{\"ok\":true,\"crash\":{\"fused\":").append(fused ? "true" : "false")
                    .append(",\"count\":").append(cnt)
                    .append(",\"lastAt\":\"").append(esc(lastAt)).append('"')
                    .append(",\"summary\":\"").append(esc(summary)).append("\"}")
                    .append(",\"backup\":{\"lastAt\":\"").append(esc(bAt)).append('"')
                    .append(",\"file\":\"").append(esc(bFile)).append('"')
                    .append(",\"count\":").append(bCnt).append('}')
                    .append(",\"schedule\":").append(scheduleJson(sched))
                    .append(",\"busy\":\"").append(esc(busyS == null ? "" : busyS)).append('"')
                    .append(",\"error\":\"").append(esc(errS == null ? "" : errS)).append("\"}");
            return b.toString();
        } catch (Throwable t) {
            return fail("拼运行状态失败：" + t);
        }
    }

    /** 拼 restoreList()：新的在前；没有 manifest.json 的半截目录不算一份。 */
    static String restoreListJson(File backupDir) {
        try {
            StringBuilder b = new StringBuilder("[");
            String[] names = sortedNames(backupDir);
            int n = 0;
            for (int i = 0; i < names.length; i++) {
                File d = new File(backupDir, names[i]);
                File mf = new File(d, "manifest.json");
                if (!mf.isFile()) continue;
                String js = readFile(mf);
                if (n > 0) b.append(',');
                n++;
                b.append("{\"id\":\"").append(esc(names[i])).append('"')
                        .append(",\"at\":\"").append(esc(strField(js, "at"))).append('"')
                        .append(",\"size\":").append(numField(js, "bytes"))
                        .append(",\"reason\":\"").append(esc(strField(js, "reason"))).append('"')
                        .append(",\"files\":").append(numField(js, "files")).append('}');
            }
            b.append(']');
            return b.toString();
        } catch (Throwable t) {
            return fail("拼备份列表失败：" + t);
        }
    }

    /** 人读的熔断摘要。写清楚「谁、什么时间、崩了几次」，不堆 JSON。 */
    static String crashSummaryText(File stateDir) {
        try {
            String js = readFile(new File(stateDir, "crash-fuse.json"));
            if (js.length() == 0) {
                return "没有任何崩溃熔断记录：服务最近没有连续崩溃过。";
            }
            boolean fused = js.indexOf("\"fused\":true") >= 0;
            long cnt = maxNum(js, "count");
            String at = lastStr(js, "lastAt");
            StringBuilder b = new StringBuilder(256);
            if (fused) {
                b.append("已熔断：5 分钟内崩溃 ").append(maxNum(js, "threshold"))
                        .append(" 次的服务已经停用自动重启").append('\n')
                        .append("最后崩溃： ").append(at.length() > 0 ? at : "（没有时间戳）")
                        .append('\n').append("现场摘要：").append(strField(js, "summary"))
                        .append('\n').append("处理：点「清除熔断」会解开并重启一次；")
                        .append("同一进程反复崩溃通常是配置写坏了或登录态失效，先看一眼日志再重启。");
            } else {
                b.append("没有服务处于熔断状态（窗口 300 秒内最多 ").append(cnt)
                        .append(" 次异常退出）");
                if (at.length() > 0) b.append("，最后一次崩溃 ").append(at);
                b.append('。');
            }
            return b.toString();
        } catch (Throwable t) {
            return "读取熔断状态失败：" + t;
        }
    }

    /** 备份目录名合法（只允许字母数字与 ._-，绝不允许 / 和 ..）。 */
    static boolean validId(String id) {
        if (id == null || id.length() == 0 || id.length() > 64) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            if (!ok) return false;
        }
        return !id.equals(".") && !id.equals("..");
    }

    /** 校验 setSchedule 的入参，返回空串 = 没问题，否则是给用户看的中文原因。 */
    static String scheduleError(String json) {
        if (json == null || json.trim().length() == 0) return "定时任务不能是空的";
        String js = minify(json.trim());
        if (js.charAt(0) != '{') return "定时任务格式不对（要一个 JSON 对象）";
        String restart = js.indexOf("\"restart\"") >= 0 ? strField(js, "restart") : "";
        // restart 允许空串（= 关闭定时重启），非空必须是 HH:MM
        if (js.indexOf("\"restart\"") >= 0 && restart.length() > 0 && !isHm(restart)) {
            return "重启时间要写成 HH:MM（24 小时制），例如 04:00；不想定时重启就留空";
        }
        boolean hasNight = js.indexOf("\"night\"") >= 0;
        if (hasNight) {
            String from = strField(js, "from");
            String to = strField(js, "to");
            if (from.length() > 0 && !isHm(from)) return "夜间开始时间要写成 HH:MM，例如 23:00";
            if (to.length() > 0 && !isHm(to)) return "夜间结束时间要写成 HH:MM，例如 07:00";
            if ((from.length() > 0) != (to.length() > 0)) return "夜间时段要把开始和结束都填上";
        }
        return "";
    }

    private static boolean isHm(String s) {
        if (s.length() != 5) return false;
        if (s.charAt(2) != ':') return false;
        int h = num2(s, 0);
        int m = num2(s, 3);
        return h >= 0 && h <= 23 && m >= 0 && m <= 59;
    }

    private static int num2(String s, int off) {
        char a = s.charAt(off);
        char b = s.charAt(off + 1);
        if (a < '0' || a > '9' || b < '0' || b > '9') return -1;
        return (a - '0') * 10 + (b - '0');
    }

    /** schedule 段的 JSON：兼容 {"night":true,...} 与 {"night":{"on":true,...}} 两种写法。 */
    static String scheduleJson(String js) {
        js = minify(js == null ? "" : js);
        String restart = js.indexOf("\"restart\"") >= 0 ? strField(js, "restart") : "";
        boolean on;
        if (js.indexOf("\"night\":{") >= 0) {
            on = js.indexOf("\"on\":true") >= 0;
        } else {
            on = js.indexOf("\"night\":true") >= 0;
        }
        String from = strField(js, "from");
        String to = strField(js, "to");
        if (on && (from.length() == 0 || to.length() == 0)) {
            from = from.length() > 0 ? from : "23:00";
            to = to.length() > 0 ? to : "07:00";
        }
        StringBuilder b = new StringBuilder(96);
        b.append("{\"restartAt\":\"").append(esc(restart)).append('"')
                .append(",\"night\":{\"on\":").append(on ? "true" : "false")
                .append(",\"from\":\"").append(esc(from)).append('"')
                .append(",\"to\":\"").append(esc(to)).append("\"}}");
        return b.toString();
    }

    /* ------------------------------ 小工具 ------------------------------ */

    private static String fail(String msg) {
        return "{\"ok\":false,\"error\":\"" + esc(msg) + "\"}";
    }

    /**
     * 去掉 JSON 字符串外面的空白。
     * 为什么需要：取值函数（strField / numField / lastStr）是按 {"key":value} 的紧凑写法找的，
     * 而我们自己的脚本写 JSON 时确实不带空格 —— 但只要有人手改过 schedule.json，或者别的进程
     * 用 pretty 格式写进来，「夜间时段」就会被当成没设置。压一遍比让每个取值函数都容忍空格省事。
     * 字符串内部（含转义）的空白原样保留。
     */
    static String minify(String js) {
        if (js == null || js.length() == 0) return "";
        StringBuilder b = new StringBuilder(js.length());
        boolean inStr = false;
        boolean esc = false;
        for (int i = 0; i < js.length(); i++) {
            char c = js.charAt(i);
            if (inStr) {
                b.append(c);
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') {
                inStr = true;
                b.append(c);
                continue;
            }
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') continue;
            b.append(c);
        }
        return b.toString();
    }

    /** 读整个小文件；读不到返回空串（缺文件在界面上就是「还没设置」，不是错误）。 */
    private static String readFile(File f) {
        try {
            if (f == null || !f.isFile() || f.length() > 262144L) return "";
            String s = StateIO.read(f);
            return (s == null) ? "" : minify(s);
        } catch (Throwable t) {
            return "";
        }
    }

    /** 数备份目录里有几份完整快照（有 manifest.json 才算）。 */
    private static int countBackups(File backupDir) {
        String[] names = sortedNames(backupDir);
        int n = 0;
        for (int i = 0; i < names.length; i++) {
            if (new File(new File(backupDir, names[i]), "manifest.json").isFile()) n++;
        }
        return n;
    }

    /** 目录名倒序（名字就是时间戳，倒序 = 新的在前）。目录不在就返回空数组。 */
    private static String[] sortedNames(File dir) {
        String[] names = (dir == null || !dir.isDirectory()) ? new String[0] : dir.list();
        if (names == null) return new String[0];
        java.util.Arrays.sort(names);
        for (int i = 0, j = names.length - 1; i < j; i++, j--) {
            String t = names[i];
            names[i] = names[j];
            names[j] = t;
        }
        return names;
    }

    /** 取 "key":"值" 里的值（已反转义）。找不到返回空串。 */
    static String strField(String js, String key) {
        if (js == null || js.length() == 0) return "";
        String pat = "\"" + key + "\":\"";
        int i = js.indexOf(pat);
        if (i < 0) return "";
        int start = i + pat.length();
        StringBuilder b = new StringBuilder(64);
        for (int k = start; k < js.length(); k++) {
            char c = js.charAt(k);
            if (c == '\\' && k + 1 < js.length()) {
                char n = js.charAt(k + 1);
                if (n == 'n') b.append('\n');
                else if (n == 'r') b.append('\r');
                else if (n == 't') b.append('\t');
                else b.append(n);
                k++;
                continue;
            }
            if (c == '"') return b.toString();
            b.append(c);
        }
        return b.toString();
    }

    /** 取 "key":123 里的数。找不到返回 0。 */
    static long numField(String js, String key) {
        if (js == null || js.length() == 0) return 0L;
        String pat = "\"" + key + "\":";
        int i = js.indexOf(pat);
        if (i < 0) return 0L;
        int k = i + pat.length();
        while (k < js.length() && js.charAt(k) == ' ') k++;
        long v = 0L;
        boolean any = false;
        while (k < js.length()) {
            char c = js.charAt(k);
            if (c < '0' || c > '9') break;
            v = v * 10 + (c - '0');
            any = true;
            k++;
        }
        return any ? v : 0L;
    }

    /** 同一份 JSON 里每个进程各有一个 count，取最大的那个（状态行要报最坏情况）。 */
    static long maxNum(String js, String key) {
        if (js == null || js.length() == 0) return 0L;
        String pat = "\"" + key + "\":";
        long best = 0L;
        int k = 0;
        while (true) {
            int i = js.indexOf(pat, k);
            if (i < 0) return best;
            long v = numField(js.substring(i), key);
            if (v > best) best = v;
            k = i + pat.length();
        }
    }

    /** 同一份 JSON 里每个进程各有一个 lastAt，取字典序最大的（ISO 时间戳，越晚越大）。 */
    static String lastStr(String js, String key) {
        if (js == null || js.length() == 0) return "";
        String pat = "\"" + key + "\":\"";
        String best = "";
        int k = 0;
        while (true) {
            int i = js.indexOf(pat, k);
            if (i < 0) return best;
            String v = strField(js.substring(i), key);
            if (v.compareTo(best) > 0) best = v;
            k = i + pat.length();
        }
    }

    /** JSON 字符串转义：反斜杠、引号、换行、制表符，其余控制字符变空格。 */
    static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') b.append("\\\"");
            else if (c == '\\') b.append("\\\\");
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else if (c == '\t') b.append("\\t");
            else if (c < 0x20) b.append(' ');
            else b.append(c);
        }
        return b.toString();
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
}
