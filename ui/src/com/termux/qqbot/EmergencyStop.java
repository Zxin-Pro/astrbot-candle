package com.termux.app;

import java.io.File;

/**
 * 为什么需要它：紧急下线是「立刻掐掉」的动作，stop-sandbox.sh 要等 proot 自己退
 * （最多 30 秒），放在 WebView 的桥线程里执行会把整个页面卡死。桥方法只负责
 * 写 offline 标记（让守护/网络自愈不再拉起沙盒）并立刻返回，真正的停止交给这里。
 * 只调停止脚本，不删任何配置、登录数据、AstrBot data —— 下线 ≠ 清理。
 * d8 处理匿名内部类会崩，所以必须是具名顶层类。
 *
 * 成功判定（v1.2.11）：offline 标记确实落盘 + stop-sandbox.sh 存在 + 脚本退出码为 0，
 * 三者缺一都不算成功。以前整段包在 catch(Throwable ignored) 里，脚本失败也悄无声息，
 * 界面却已经提示「已下线」——那是比不做更坏的事。run() 为了兼容 Runnable 调用点
 * 仍返回 void，真实结果由 tryRun() 给出；失败一律 Log.e("QqbotStop", ...)。
 */
public class EmergencyStop implements Runnable {

    private static final String TAG = "QqbotStop";
    /** QqbotShell.run 只回输出不回退出码，所以让 shell 自己把 rc 打印出来再解析。 */
    private static final String RC_MARK = "QQBOT_STOP_RC=";

    private final File filesDir;

    public EmergencyStop(File filesDir) {
        this.filesDir = filesDir;
    }

    /** Runnable 入口：后台线程没人接返回值，失败只记日志。 */
    public void run() {
        try {
            tryRun();
        } catch (Throwable t) {
            android.util.Log.e(TAG, "紧急下线异常：" + t, t);
        }
    }

    /**
     * 真正执行下线，返回「沙盒是否确实被停掉」。
     * 调用方：GuardKillTask（忽略返回值，后面还有 killProcess 兜底）、
     * QqbotBridge 的后台线程（失败只进 logcat）。
     */
    public boolean tryRun() {
        // 1) 标记先落盘：从通知栏点「下线」时没人替我们写这个文件，而它必须在
        // stop 脚本跑的这几秒里就已经存在，否则守护/网络自愈会把沙盒又拉起来。
        File off = StateIO.file(filesDir, "offline");
        if (!StateIO.writeStamp(off) || !off.isFile()) {
            android.util.Log.e(TAG, "offline 标记写入失败：" + off.getAbsolutePath()
                    + "（守护/网络自愈可能还会拉起沙盒）");
            return false;
        }
        // 2) 脚本必须存在，否则离线标记写了、进程还在跑。
        File stop = new File(QqbotShell.usrDir(filesDir), "opt/qqbot/stop-sandbox.sh");
        if (!stop.isFile()) {
            android.util.Log.e(TAG, "缺少 stop-sandbox.sh：" + stop.getAbsolutePath()
                    + "（offline 已写入，但沙盒进程没有被停掉）");
            return false;
        }
        // 3) 执行并检查退出码。超时给到 180 秒：脚本自己要等 proot 退出，
        // 掐早了会留下占端口的孤儿进程。
        String out = QqbotShell.run(filesDir,
                "bash " + stop.getAbsolutePath() + "; echo " + RC_MARK + "$?", 180);
        int rc = parseRc(out);
        if (rc != 0) {
            android.util.Log.e(TAG, "stop-sandbox.sh 退出码 " + rc + "，下线未完成；输出："
                    + brief(out));
            return false;
        }
        return true;
    }

    /**
     * 「关闭进程」专用：写好 offline 标记后把 stop-sandbox.sh 丢到独立会话里跑，立刻返回。
     *
     * 为什么不能像 tryRun 那样同步等：GuardKillTask 随后就要 killProcess，而这个进程一死，
     * QqbotShell.run 那条管道就读不到了，脚本下一次 echo 吃 SIGPIPE 会提前退出，
     * 「等端口关闭 / 删 pid / 收尾 sweep」全跑不完，6099 / 6185 / 6199 上就留下孤儿
     * supervisor，用户再开 App 直接撞端口占用（v1.2.11 实测就是这么漏的）。
     * setsid + nohup + 重定向到文件之后，脚本与 App 进程彻底脱钩：App 立刻消失，
     * 脚本照样把沙盒收干净。
     */
    public boolean startDetached() {
        File off = StateIO.file(filesDir, "offline");
        if (!StateIO.writeStamp(off) || !off.isFile()) {
            android.util.Log.e(TAG, "offline 标记写入失败：" + off.getAbsolutePath());
            return false;
        }
        File stop = new File(QqbotShell.usrDir(filesDir), "opt/qqbot/stop-sandbox.sh");
        if (!stop.isFile()) {
            android.util.Log.e(TAG, "缺少 stop-sandbox.sh：" + stop.getAbsolutePath());
            return false;
        }
        File logs = new File(QqbotShell.qqbotDir(filesDir), "logs");
        String out = QqbotShell.run(filesDir,
                "mkdir -p " + logs.getAbsolutePath() + "; nohup setsid bash " + stop.getAbsolutePath()
                        + " >> " + logs.getAbsolutePath() + "/stop-kill.log 2>&1 < /dev/null & "
                        + "sleep 1; echo QQBOT_STOP_DETACHED", 20);
        boolean ok = out != null && out.indexOf("QQBOT_STOP_DETACHED") >= 0;
        if (!ok) android.util.Log.e(TAG, "stop-sandbox.sh 脱离启动失败：" + brief(out));
        return ok;
    }

    /** 取最后一行 QQBOT_STOP_RC=n；没有标记（bash 没起来 / 到点被杀）返回 -1。 */
    private static int parseRc(String out) {
        if (out == null) return -1;
        int at = out.lastIndexOf(RC_MARK);
        if (at < 0) return -1;
        int i = at + RC_MARK.length();
        int v = 0;
        boolean any = false;
        while (i < out.length()) {
            char c = out.charAt(i);
            if (c < '0' || c > '9') break;
            v = v * 10 + (c - '0');
            any = true;
            i++;
        }
        return any ? v : -1;
    }

    /** 日志里只留尾部 400 字符：stop 脚本可能刷几十行 proot 退出信息。 */
    private static String brief(String out) {
        if (out == null) return "";
        String s = out.trim();
        if (s.length() > 400) s = s.substring(s.length() - 400);
        return s;
    }
}
