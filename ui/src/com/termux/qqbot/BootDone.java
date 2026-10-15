package com.termux.app;

public class BootDone implements Runnable {

    private final QqbotActivity act;

    public BootDone(QqbotActivity act) {
        this.act = act;
    }

    public void run() {
        act.loadUi();
        // 脚本同步 + 续跑部署都丢到后台线程：这里面有 stop/start 脚本的同步等待，
        // 在 UI 线程上会把启动界面卡住十几秒。
        new Thread(new BootSync(act)).start();
    }
}