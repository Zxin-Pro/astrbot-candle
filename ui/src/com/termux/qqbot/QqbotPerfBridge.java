package com.termux.app;

import android.app.Activity;
import android.os.Debug;
import android.os.Process;
import android.os.SystemClock;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;

/**
 * 性能面板桥（window.AndroidPerf.sample()）。
 *
 * 为什么不用 ActivityManager.getProcessMemoryInfo：那接口在 API 28 之后对第三方应用
 * 只返回一个「够用 / 不够用」的粗粒度结果，拿不到真实 PSS；Debug.getMemoryInfo 读的是
 * 自己进程的 /proc 数据，任何版本都准，也不需要权限。
 *
 * CPU 占用率不能直接读某个「瞬时值」——Linux 只给累计 CPU 时间，所以只能两次采样求差：
 *   cpuPct = (Δ本进程CPU毫秒 / Δ墙钟毫秒 / 核数) * 100
 * 除以核数是刻意的：要不一个 8 核手机上跑满两个线程就会显示 200%，用户看不懂。
 *
 * sample() 在 WebView 的 JS 桥线程上跑，会阻塞页面的同步回调，所以：
 *   - 常规路径完全不睡（用上一次采样的时间点做差），面板 2 秒刷一次，正好对齐；
 *   - 只有当两次调用离得太近（<150ms，比如用户连点「采样一次」）才睡 180ms 补窗口，
 *     否则分母趋近 0，百分比会乱跳成 1000%。
 *
 * 本文件不出现匿名类 / 内部类，也不用简写语法。
 */
public class QqbotPerfBridge {

    private static final double MB = 1024.0 * 1024.0;
    /** 类加载时刻 ≈ 进程启动时刻（桥在 Activity.onCreate 里注册，够用）。 */
    private static final long T0 = SystemClock.elapsedRealtime();
    private static long lastCpuMs = -1L;
    private static long lastWallMs = -1L;

    private final Activity act;

    public QqbotPerfBridge(Activity act) {
        this.act = act;
    }

    /**
     * sample() 返回 {"ok":true,"memMb":210,"memMaxMb":512,"memPct":41,
     *               "cpuPct":12.5,"threads":48,"upMin":95,"pssMb":180}
     * 任何一项读不到就给 0，绝不因此整体失败；整体失败才返回 {"ok":false,"err":"人话"}。
     */
    @JavascriptInterface
    public String sample() {
        try {
            Runtime rt = Runtime.getRuntime();
            long used = rt.totalMemory() - rt.freeMemory();
            long max = rt.maxMemory();
            int memMb = (int) Math.round(used / MB);
            int memMaxMb = (int) Math.round(max / MB);
            int memPct = (max > 0L) ? (int) Math.round(used * 100.0 / max) : 0;

            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("memMb", memMb);
            o.put("memMaxMb", memMaxMb);
            o.put("memPct", memPct);
            o.put("cpuPct", round1(cpuPct()));
            o.put("threads", threads());
            o.put("upMin", upMin());
            o.put("pssMb", pssMb());
            return o.toString();
        } catch (Throwable t) {
            return fail("性能采样失败：" + oneLine(t));
        }
    }

    /* ---------- CPU ---------- */

    /** 两次采样求差得到的本进程 CPU 占用率（已按核数归一，上限 100）。 */
    static synchronized double cpuPct() {
        try {
            long wall = SystemClock.elapsedRealtime();
            long cpu = Process.getElapsedCpuTime();
            int cores = Runtime.getRuntime().availableProcessors();
            if (cores <= 0) cores = 1;
            if (lastWallMs < 0L || (wall - lastWallMs) < 150L) {
                // 第一次调用或两次贴太近：自己补一段窗口，否则分母太小会算出天文数字。
                long w0 = wall;
                long c0 = cpu;
                try {
                    Thread.sleep(180L);
                } catch (Throwable ignored) {
                }
                long w1 = SystemClock.elapsedRealtime();
                long c1 = Process.getElapsedCpuTime();
                lastWallMs = w1;
                lastCpuMs = c1;
                if (w1 <= w0) return 0.0;
                return clamp(c1 - c0, w1 - w0, cores);
            }
            long dw = wall - lastWallMs;
            long dc = cpu - lastCpuMs;
            lastWallMs = wall;
            lastCpuMs = cpu;
            return clamp(dc, dw, cores);
        } catch (Throwable t) {
            return 0.0;
        }
    }

    private static double clamp(long cpuMs, long wallMs, int cores) {
        if (wallMs <= 0L) return 0.0;
        double p = (cpuMs * 100.0) / ((double) wallMs * (double) cores);
        if (p < 0.0) return 0.0;
        // 采样抖动偶尔会算出 103% 这种数（系统把子进程 CPU 也算进来了），剪到 100。
        if (p > 100.0) return 100.0;
        return p;
    }

    /* ---------- 线程 / 内存 / 运行时长 ---------- */

    /** 线程数优先读 /proc/self/status（含所有线程组），读不到才退 Thread.activeCount()。 */
    static int threads() {
        try {
            String s = readProc("/proc/self/status", 16 * 1024);
            int k = s.indexOf("Threads:");
            if (k >= 0) {
                int e = k + 8;
                int nl = s.indexOf('\n', e);
                String num = (nl > e) ? s.substring(e, nl) : s.substring(e);
                return Integer.parseInt(num.trim());
            }
        } catch (Throwable ignored) {
        }
        try {
            return Thread.activeCount();
        } catch (Throwable t) {
            return 0;
        }
    }

    static int pssMb() {
        try {
            Debug.MemoryInfo mi = new Debug.MemoryInfo();
            Debug.getMemoryInfo(mi);
            long kb = mi.getTotalPss();
            if (kb > 0L) return (int) Math.round(kb / 1024.0);
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /**
     * 进程已运行分钟数：/proc/self/stat 第 22 个字段是启动时刻（开机以来的 tick），
     * 减 /proc/uptime 就是进程年龄。tick 频率安卓固定 100（sysconf(_SC_CLK_TCK)），
     * 不用 NDK 也能算。全都读不到才退类加载时刻。
     */
    static int upMin() {
        try {
            String stat = readProc("/proc/self/stat", 8 * 1024);
            // comm 字段在括号里且可能含空格/右括号，所以从最后一个 ')' 之后切
            int k = stat.lastIndexOf(')');
            if (k > 0) {
                String rest = stat.substring(k + 1).trim();
                String[] f = rest.split(" ");
                // rest 从第 3 个字段（state）开始，starttime 是第 22 个 → 下标 19
                if (f.length > 19) {
                    long ticks = Long.parseLong(f[19].trim());
                    String up = readProc("/proc/uptime", 256);
                    int sp = up.indexOf(' ');
                    String head = (sp > 0) ? up.substring(0, sp) : up;
                    double sec = Double.parseDouble(head.trim());
                    double start = ticks / 100.0;
                    double mins = (sec - start) / 60.0;
                    if (mins >= 0.0) return (int) mins;
                }
            }
        } catch (Throwable ignored) {
        }
        return (int) Math.max(0L, (SystemClock.elapsedRealtime() - T0) / 60000L);
    }

    /* ---------- 小工具 ---------- */

    private static String readProc(String path, int cap) {
        FileInputStream in = null;
        BufferedReader br = null;
        StringBuilder sb = new StringBuilder(cap);
        try {
            in = new FileInputStream(path);
            br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
                if (sb.length() >= cap) break;
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Throwable ignored) {
                }
            }
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
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
            return "{\"ok\":false,\"err\":\"性能采样失败\"}";
        }
    }
}
