package com.termux.app;

/** 应用启动后的后台收尾：同步部署脚本 -> 必要时重启容器 -> 续跑部署/拉起服务。
 *
 *  整件事必须离开 UI 线程：stop-sandbox.sh 最多等 proot 自己退 30 秒，start-sandbox.sh 里
 *  还有 sleep 4，续跑部署更是要等 2 秒以上。以前这些都在 BootDone.run()（TermuxInstaller
 *  的 whenDone 一律回调在 UI 线程）里同步执行，启动瞬间界面会僵住十几秒，看起来像卡死。
 */
public class BootSync implements Runnable {

    private final QqbotActivity act;

    public BootSync(QqbotActivity act) {
        this.act = act;
    }

    public void run() {
        try {
            // 覆盖安装后 $PREFIX 里还是旧脚本，先同步再谈别的。
            // 但脚本同步完不能无条件重启容器：state/offline 还在说明用户主动「关闭进程 /
            // 紧急下线」过，重启就是把沙盒又拉起来。同步本身是纯文件操作，照做无妨；
            // 标记只由用户显式「启动」清除（SandboxStart / start-sandbox.sh），这里不碰。
            boolean synced = act.syncScripts();
            // 判断放在同步之后：同步这一两秒里用户完全可能刚按了「关闭进程」。
            if (synced && !closedByUser()) act.restartServicesIfRunning();
        } catch (Throwable ignored) {
        }
        try {
            act.maybeAutoDeploy();
        } catch (Throwable ignored) {
        }
    }

    /** state/offline 还在 = 用户主动关过，任何自动拉起都要跳过。 */
    private boolean closedByUser() {
        try {
            return StateIO.file(act.filesDirPub(), "offline").isFile();
        } catch (Throwable ignored) {
            return false;
        }
    }
}