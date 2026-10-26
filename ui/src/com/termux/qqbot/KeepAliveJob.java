package com.termux.app;

import android.app.job.JobParameters;

/**
 * JobScheduler 心跳的线程体（具名顶层类，d8 不接受匿名内部类）。
 * 跑完必须回调 jobFinished，把 onStartJob 里 return true 借来的时间还回去。
 */
public class KeepAliveJob implements Runnable {

    private final KeepAliveJobService svc;
    private final JobParameters params;

    public KeepAliveJob(KeepAliveJobService s, JobParameters p) {
        svc = s;
        params = p;
    }

    public void run() {
        try {
            KeepAlive.rescue(svc);
        } catch (Throwable t) {
            android.util.Log.w("QqbotKeepAlive", "JobScheduler 体检异常：" + t);
        } finally {
            try {
                svc.jobFinished(params, false);
            } catch (Throwable ignored) {
            }
        }
    }
}
