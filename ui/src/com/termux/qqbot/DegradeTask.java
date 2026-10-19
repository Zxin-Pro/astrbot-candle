package com.termux.app;

import java.io.File;

/**
 * 为什么需要它：BatteryMon 的回调跑在主线程（动态广播默认在主线程分发），
 * 写文件 + 改通知都不该压在那里；而且「写 degrade 文件」和「通知正文改写」是同一件事，
 * 拆到独立线程里一次做完，主线程只更新几个 volatile 字段。
 * 降载只做两件事：写/删 state/degrade（沙盒层自己读它降载）和换一条通知正文，
 * 不杀进程、不碰数据，所以随时可以回滚。
 * d8 处理匿名内部类会崩，所以必须是具名顶层类。
 */
public class DegradeTask implements Runnable {

    private final QqbotGuardService svc;
    private final String label;
    private final int level;
    private final float tempC;

    public DegradeTask(QqbotGuardService s, String label, int level, float tempC) {
        this.svc = s;
        this.label = (label == null) ? "" : label;
        this.level = level;
        this.tempC = tempC;
    }

    public void run() {
        try {
            File filesDir = svc.getFilesDir();
            // 内容就一个词：battery / thermal。沙盒层按它降载，没有这个文件就是满功率。
            File f = StateIO.file(filesDir, "degrade");
            if (label.length() == 0) {
                StateIO.delete(f);
                svc.setNote("已恢复正常功率");
            } else {
                StateIO.write(f, label);
                String why = "thermal".equals(label) ? "温度过高" : "电量过低";
                String extra = "thermal".equals(label) ? ("当前 " + round1(tempC) + "℃") : ("当前 " + level + "%");
                svc.setNote(why + "，已降载（" + extra + "）");
            }
        } catch (Throwable ignored) {
        }
    }

    private static String round1(float f) {
        return String.valueOf(Math.round(f * 10f) / 10.0);
    }
}
