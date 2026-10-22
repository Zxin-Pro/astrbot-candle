package com.termux.app;

import java.io.File;

/**
 * 为什么需要它：紧急下线是「立刻掐掉」的动作，stop-sandbox.sh 要等 proot 自己退
 * （最多 30 秒），放在 WebView 的桥线程里执行会把整个页面卡死。桥方法只负责
 * 写 offline 标记（让守护/网络自愈不再拉起沙盒）并立刻返回，真正的停止交给这里。
 * 只调停止脚本，不删任何配置、登录数据、AstrBot data —— 下线 ≠ 清理。
 * d8 处理匿名内部类会崩，所以必须是具名顶层类。
 */
public class EmergencyStop implements Runnable {

    private final File filesDir;

    public EmergencyStop(File filesDir) {
        this.filesDir = filesDir;
    }

    public void run() {
        try {
            // 标记先落盘：从通知栏点「下线」时没人替我们写这个文件，而它必须在
            // stop 脚本跑的这几秒里就已经存在，否则守护/网络自愈会把沙盒又拉起来。
            StateIO.writeStamp(StateIO.file(filesDir, "offline"));
            File stop = new File(QqbotShell.usrDir(filesDir), "opt/qqbot/stop-sandbox.sh");
            if (!stop.isFile()) return;
            // 超时给到 180 秒：脚本自己要等 proot 退出，掐早了会留下占端口的孤儿进程。
            QqbotShell.run(filesDir, "bash " + stop.getAbsolutePath(), 180);
        } catch (Throwable ignored) {
        }
    }
}
