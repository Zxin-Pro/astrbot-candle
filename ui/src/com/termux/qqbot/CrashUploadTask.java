package com.termux.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Locale;

/**
 * 崩溃上报：把 CrashBoard 的聚合类（每个「签名 + 模块」一条）逐条 POST 到 /api/crash。
 *
 * 一次最多 20 条：真崩了 200 个类也不该在后台连着发 200 次请求；中途失败就停，
 * 不重试（AtomNet 本身也不重试），下次冷启动或 6 小时后巡检再说。
 *
 * payload 只有这 6 个字段，多一个都不许有：
 *   {"id":"&lt;installId&gt;","ver":"&lt;versionName&gt;","sig":"&lt;sig&gt;",
 *    "module":"&lt;module|component&gt;","signal":&lt;int&gt;,"count":&lt;int&gt;}
 * 严禁上送 IP、时间戳、pid、日志正文、QQ 号、token、机型之外的任何东西——
 * 「哪台设备崩的」由服务端按 id 归并，原生层不再额外给可识别信息。
 *
 * 本类必须是顶层类：d8 处理匿名内部类会崩（见 StateIO 注释），整个类里没有内部类。
 */
public class CrashUploadTask implements Runnable {

    /**
     * 总开关（2026-10 写死 false）。
     *
     * 原因两条：1) 自建站点服务端随 VPS 一起停了，现在没有免密钥能收上报的地方——
     * GitHub 那边只有私有仓库 Zxin-Pro/astrbot-errlogs，进去要 token，不能塞进 APK；
     * 2) 匿名上报本来就默认关闭（UploadState.crashOn 默认 false），没有「默认开」的立场去
     * 硬找一个收件端。日后服务端恢复，把这里改回 true 即可，下面的上报逻辑一行没删。
     */
    private static final boolean ENABLED = false;

    /** 一次最多发几条。 */
    static final int MAX_ITEMS = 20;

    /** 同一时刻只允许一个崩溃上报在跑（冷启动和守护巡检可能撞车）。 */
    private static volatile boolean busy = false;

    private final File filesDir;
    private final String ver;

    /** ver 是 versionName（"1.1.30"），调用方已经取好；任务里不再碰 Context。 */
    public CrashUploadTask(File filesDir, String ver) {
        this.filesDir = filesDir;
        this.ver = (ver == null) ? "" : ver;
    }

    @Override
    public void run() {
        if (!ENABLED) return;
        if (filesDir == null) return;
        // 第一道闸：开关关着一个字节都不发（连 DNS 都不解析）。
        // 上层大概率已经判过，这里留最后一道，防止将来新增触发点忘了判。
        if (!UploadState.crashOn(filesDir)) return;
        synchronized (CrashUploadTask.class) {
            if (busy) return;
            busy = true;
        }
        try {
            upload();
        } catch (Throwable ignored) {
        } finally {
            synchronized (CrashUploadTask.class) {
                busy = false;
            }
        }
    }

    private void upload() {
        JSONArray arr = CrashBoard.classes(filesDir);
        int n = Math.min(arr.length(), MAX_ITEMS);
        if (n <= 0) return;   // 没崩过：不发空请求，也不占掉「今天已上报」的名额

        String id = UploadState.installId(filesDir);
        int sent = 0;
        boolean begun = false;
        int code = 0;
        String err = "";
        for (int i = 0; i < n; i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c == null) continue;
            String sig = c.optString("sig", "").trim();
            if (sig.length() == 0) continue;   // 没签名服务端归并不了，跳过
            // 脚本里叫 component，契约里叫 module；两个名字都认，缺的补 unknown
            String module = c.optString("component", c.optString("module", "")).trim();
            if (module.length() == 0) module = "unknown";
            int signal = sigNum(c.optString("signal", ""));
            int count = c.optInt("count", 0);
            if (count <= 0) continue;

            if (!begun) {
                // 真的开始发了才记「尝试过」：全是脏数据时不该白占掉 6 小时的重试窗口
                UploadState.beginAttempt(filesDir, UploadState.KIND_CRASH);
                begun = true;
            }
            String reply = AtomNet.post(AtomNet.PATH_CRASH,
                    payload(id, ver, sig, module, signal, count));
            JSONObject ro = parse(reply);
            boolean ok = (ro != null) && ro.optBoolean("ok", false);
            code = (ro == null) ? 0 : ro.optInt("code", 0);
            if (ok) {
                sent++;
                continue;
            }
            err = (ro == null) ? "上报结果无法解析" : ro.optString("err", "");
            break;   // 失败就停：不重试，也不把 20 条挨个超时一遍
        }
        if (!begun) return;   // 一条有效数据都没有：什么都没发，不记账
        UploadState.finish(filesDir, UploadState.KIND_CRASH, sent > 0, code, err);
    }

    /**
     * 单条上报体。纯字符串拼装——不碰文件、不碰网络、不碰 Android API，
     * 本地假服务器验证直接调它（这就是「实际发出的 JSON」）。
     */
    static String payload(String id, String ver, String sig, String module, int signal, int count) {
        StringBuilder b = new StringBuilder(192);
        b.append("{\"id\":\"").append(AtomNet.esc(id))
                .append("\",\"ver\":\"").append(AtomNet.esc(ver))
                .append("\",\"sig\":\"").append(AtomNet.esc(sig))
                .append("\",\"module\":\"").append(AtomNet.esc(module))
                .append("\",\"signal\":").append(signal)
                .append(",\"count\":").append(count)
                .append('}');
        return b.toString();
    }

    /**
     * 脚本里的 signal 是字符串（"SIGSEGV"），上报体要 int（POSIX 信号号），服务端才好按数值归档。
     * 认不出的记 0（未知），不猜。本机 crashInfo() 返回的仍然是原字符串，前端显示不受影响。
     */
    static int sigNum(String s) {
        String t = (s == null) ? "" : s.trim().toUpperCase(Locale.ROOT);
        if (t.length() == 0) return 0;
        if (t.startsWith("SIG")) t = t.substring(3);
        try {
            return Integer.parseInt(t);   // 脚本直接给了数字
        } catch (Throwable ignored) {
        }
        if (t.equals("HUP")) return 1;
        if (t.equals("INT")) return 2;
        if (t.equals("QUIT")) return 3;
        if (t.equals("ILL")) return 4;
        if (t.equals("TRAP")) return 5;
        if (t.equals("ABRT") || t.equals("IOT")) return 6;
        if (t.equals("BUS")) return 7;
        if (t.equals("FPE")) return 8;
        if (t.equals("KILL")) return 9;
        if (t.equals("USR1")) return 10;
        if (t.equals("SEGV")) return 11;
        if (t.equals("USR2")) return 12;
        if (t.equals("PIPE")) return 13;
        if (t.equals("ALRM")) return 14;
        if (t.equals("TERM")) return 15;
        if (t.equals("STKFLT")) return 16;
        if (t.equals("CHLD") || t.equals("CLD")) return 17;
        if (t.equals("CONT")) return 18;
        if (t.equals("STOP")) return 19;
        if (t.equals("TSTP")) return 20;
        if (t.equals("TTIN")) return 21;
        if (t.equals("TTOU")) return 22;
        if (t.equals("URG")) return 23;
        if (t.equals("XCPU")) return 24;
        if (t.equals("XFSZ")) return 25;
        if (t.equals("VTALRM")) return 26;
        if (t.equals("PROF")) return 27;
        if (t.equals("WINCH")) return 28;
        if (t.equals("IO") || t.equals("POLL")) return 29;
        if (t.equals("PWR")) return 30;
        if (t.equals("SYS")) return 31;
        return 0;
    }

    private static JSONObject parse(String s) {
        if (s == null || s.trim().length() == 0) return null;
        try {
            return new JSONObject(s);
        } catch (Throwable t) {
            return null;
        }
    }
}
