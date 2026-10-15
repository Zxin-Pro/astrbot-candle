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
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            long deadline = System.currentTimeMillis() + (timeoutSec * 1000L);
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
                if (System.currentTimeMillis() > deadline) {
                    sb.append("[timeout]\n");
                    break;
                }
            }
            br.close();
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
