package com.termux.app;

/**
 * 后台线程里把内置离线包从 APK assets 拷到 $HOME/.qqbot/bundle.tar.xz。
 *
 * 注意：d8 处理匿名内部类会崩，所以这里必须是具名顶层类
 * （QqbotActivity 里 new Runnable(){...} 会在 d8 阶段触发
 *  NullPointerException: Cannot invoke "String.length()"）。
 *
 * 拷 604 MB 要几十秒，必须离开 UI 线程；部署脚本那边靠轮询 .bundle-ok 等它完成。
 */
public class BundleCopyTask implements Runnable {

    private final QqbotActivity act;
    private final java.io.File flag;

    public BundleCopyTask(QqbotActivity act, java.io.File flag) {
        this.act = act;
        this.flag = flag;
    }

    public void run() {
        try {
            act.ensureBundle();
        } catch (Throwable ignored) {
        } finally {
            try {
                if (flag != null) flag.delete();
            } catch (Throwable ignored) {
            }
        }
    }
}
