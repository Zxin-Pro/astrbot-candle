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
            // 覆盖安装后 $PREFIX 里还是旧脚本，先同步再谈别的
            if (act.syncScripts()) act.restartServicesIfRunning();
        } catch (Throwable ignored) {
        }
        try {
            act.maybeAutoDeploy();
        } catch (Throwable ignored) {
        }
    }
}