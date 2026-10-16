package com.termux.app;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.Map;

/**
 * Termux 侧 shell 执行器。
 * 原先这段代码长在 QqbotActivity.runShell 里；前台守护服务（QqbotGuardService）
 * 也要在应用界面之外拉沙盒，所以抽成静态方法复用，行为与原来逐字保持一致。
 * d8 处理匿名内部类会崩，本文件不出现匿名类。
 */
public class QqbotShell {

    public static File usrDir(File filesDir) { return new File(filesDir, "usr"); }

    public static File homeDir(File filesDir) { return new File(filesDir, "home"); }

    public static File qqbotDir(File filesDir) { return new File(homeDir(filesDir), ".qqbot"); }

    public static String bashPath(File filesDir) {
        return usrDir(filesDir).getAbsolutePath() + "/bin/bash";
    }

    public static boolean bootstrapReady(File filesDir) {
        return new File(bashPath(filesDir)).isFile();
    }

    /** pid 文件里的进程还活着，并且 cmdline 里含 hint（hint 为空则只判存活）。 */
    public static boolean pidAlive(File pidFile, String hint) {
        try {
            if (pidFile == null || !pidFile.isFile()) return false;
            BufferedReader br = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(pidFile), "UTF-8"));
            String s = br.readLine();
            br.close();
            if (s == null) return false;
            int pid = Integer.parseInt(s.trim());
            File p = new File("/proc/" + pid);
            if (!p.exists()) return false;
            if (hint == null || hint.length() == 0) return true;
            return cmdlineHas(pid, hint);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean cmdlineHas(int pid, String hint) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream("/proc/" + pid + "/cmdline");
            byte[] buf = new byte[4096];
            int n = in.read(buf);
            if (n <= 0) return false;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) {
                char c = (char) (buf[i] & 0xFF);
                sb.append(c == 0 ? ' ' : c);
            }
            return sb.toString().indexOf(hint) >= 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (in != null) { try { in.close(); } catch (Exception ignored) {} }
        }
    }

    /** 与 QqbotActivity.runShell 同源：同样的环境变量、同样的超时、同样的收尾。 */
    public static String run(File filesDir, String cmd, int timeoutSec) {
        StringBuilder sb = new StringBuilder();
        Process p = null;
        StreamPump pump = null;
        Thread th = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(usrDir(filesDir).getAbsolutePath() + "/bin/bash", "-c", cmd);
            Map<String, String> env = pb.environment();
            env.clear();
            env.put("PREFIX", usrDir(filesDir).getAbsolutePath());
            env.put("HOME", homeDir(filesDir).getAbsolutePath());
            // 不要设置 LD_LIBRARY_PATH：在带 ARM 翻译的 x86_64 设备（MuMu 等）上，
            // 动态链接器会到 $PREFIX/lib 里找宿主架构的库，命中 aarch64 的 libtermux-exec.so
            // 后 bash 启动阶段直接 SIGSEGV（实测 exit 139）。
            env.put("PATH", usrDir(filesDir).getAbsolutePath() + "/bin");
            env.put("TMPDIR", usrDir(filesDir).getAbsolutePath() + "/tmp");
            env.put("TERM", "xterm-256color");
            env.put("LANG", "C.UTF-8");
            env.put("SHELL", bashPath(filesDir));
            env.put("ANDROID_ROOT", "/system");
            env.put("ANDROID_DATA", "/data");
            env.put("EXTERNAL_STORAGE", "/sdcard");
            env.put("TERMUX_VERSION", "0.118.3");
            env.put("COLORTERM", "truecolor");
            // 让 profile.d 里的自动部署脚本不要在我们的子 shell 里再次触发
            env.put("QQBOT_SANDBOX_ENTERED", "1");
            pb.redirectErrorStream(true);
            pb.directory(homeDir(filesDir));
            p = pb.start();
            /* 读输出必须放在独立线程里。旧写法是「读到一行之后再比 deadline」，
               只要命令不产出任何输出（sleep 300、等 stdin 的 cat…）就永远读不到第一行，
               超时形同虚设；而所有 @JavascriptInterface 调用都排在同一个 JavaBridge 线程上，
               一次卡死会连带把 state()/readLog()/action() 全堵死，页面永久停在旧状态，
               只能杀进程。这里改成主线程只做「等退出 / 到点就杀」，输出交给 StreamPump。 */
            pump = new StreamPump(p.getInputStream());
            th = new Thread(pump, "qqbot-shell-out");
            th.setDaemon(true);
            th.start();
            long deadline = System.currentTimeMillis() + (Math.max(1, timeoutSec) * 1000L);
            boolean exited = false;
            /* 「到点」与「有没有杀干净」必须分开记：destroy() 通常能在几十毫秒内杀掉命令，
               一旦 exited 被置回 true，下面就不能再用 exited 判断超时，
               否则一条不产出任何输出的命令会返回空串，用户看到框里只有回显、没有任何说明。 */
            boolean timedOut = false;
            while (System.currentTimeMillis() < deadline) {
                try { p.exitValue(); exited = true; break; }
                catch (IllegalThreadStateException stillRunning) { }
                try { Thread.sleep(80); } catch (InterruptedException ie) { break; }
            }
            if (!exited) {
                timedOut = true;
                try { p.destroy(); } catch (Exception ignored) {}
                /* 给 SIGTERM 一点时间落地，还没死就再补一次。
                   不用 destroyForcibly()：它是 API 26 才有的方法，minSdk 24 上会 NoSuchMethodError。 */
                for (int i = 0; i < 10 && !exited; i++) {
                    try { p.exitValue(); exited = true; }
                    catch (IllegalThreadStateException stillRunning) { }
                    try { Thread.sleep(100); } catch (InterruptedException ie) { break; }
                }
            }
            if (th != null) { try { th.join(800); } catch (InterruptedException ie) { } }
            sb.append(pump.text());
            if (timedOut) sb.append("[timeout]\n");
        } catch (Exception e) {
            sb.append("ERROR: ").append(e.toString()).append('\n');
        } finally {
            if (p != null) { try { p.destroy(); } catch (Exception ignored) {} }
        }
        return sb.toString();
    }

    /* ---------- 与应用无关的动作：守护服务与界面都调用这里 ---------- */

    /** 拉起沙盒（NapCat + AstrBot）。已在跑就什么都不做。 */
    public static boolean startSandbox(File filesDir, StringBuilder note) {
        if (!bootstrapReady(filesDir)) { if (note != null) note.append("Termux 环境未就绪"); return false; }
        File qq = qqbotDir(filesDir);
        File pid = new File(qq, "supervisor.pid");
        if (pidAlive(pid, "proot")) { if (note != null) note.append("沙盒已在运行"); return false; }
        File script = new File(usrDir(filesDir), "opt/qqbot/start-sandbox.sh");
        if (!script.isFile()) { if (note != null) note.append("缺少 start-sandbox.sh"); return false; }
        String qh = qq.getAbsolutePath();
        // setsid 脱离进程组：run 返回后会 destroy()，而 destroyProcess 是按进程组发
        // SIGTERM 的，不脱离就会把刚拉起来的沙盒一起带走。
        run(filesDir, "mkdir -p " + qh + "/logs " + qh + "/cache/logs; "
                + "nohup setsid bash " + usrDir(filesDir).getAbsolutePath() + "/opt/qqbot/start-sandbox.sh >> "
                + qh + "/logs/supervisor.log 2>&1 < /dev/null & "
                + "sleep 3; echo ok", 30);
        boolean ok = pidAlive(pid, "proot");
        if (note != null) note.append(ok ? "已重新拉起沙盒" : "拉起沙盒失败");
        return ok;
    }

    /** 拉起 Termux 侧的看门狗（它负责沙盒整体被回收后的重启）。 */
    public static boolean startWatchdog(File filesDir, StringBuilder note) {
        File qq = qqbotDir(filesDir);
        File pid = new File(qq, "watchdog.pid");
        if (pidAlive(pid, "watchdog.sh")) return false;
        File script = new File(usrDir(filesDir), "opt/qqbot/watchdog.sh");
        if (!script.isFile()) { if (note != null) note.append("缺少 watchdog.sh"); return false; }
        run(filesDir, "mkdir -p " + qq.getAbsolutePath() + "/logs; "
                + "nohup setsid bash " + usrDir(filesDir).getAbsolutePath() + "/opt/qqbot/watchdog.sh >> "
                + qq.getAbsolutePath() + "/logs/watchdog.log 2>&1 < /dev/null & "
                + "sleep 2; echo ok", 20);
        if (note != null) note.append("已启动看门狗");
        return true;
    }
}
