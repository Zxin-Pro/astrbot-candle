package com.termux.app;

import android.app.job.JobParameters;
import android.app.job.JobService;

/**
 * 第二条「保命线」（v1.3.3-beta.6）：JobScheduler 周期任务。
 *
 * 为什么需要：AlarmManager 那条心跳有两个天花板——① 各家 ROM 会掐后台精确闹钟
 * （尤其「自启动管理」「后台省电」一开）；② Doze 里 setExactAndAllowWhileIdle 每个
 * 应用最多 9 分钟才准投递一次，2 分钟的节奏会被拉长。JobScheduler 是系统自己的调度
 * 器：进程被杀、闹钟被清、Doze 维护窗口，它都还是会按自己的节奏把本 Service 叫起来，
 * 而且 setPersisted(true) 之后开机也自动恢复，不需要额外的开机广播。
 *
 * 职责就两件：把常驻守护服务拉回来 + 跑一次 KeepAlive.rescue 做沙盒体检。
 * 不做任何 UI，不依赖 Activity。
 * d8 处理匿名内部类会崩，所以线程体是具名的 KeepAliveJob。
 */
public class KeepAliveJobService extends JobService {

    /** 周期任务的 id：与 KeepAlive 里的闹钟 REQ(42312) 区分开，避免互相覆盖。 */
    public static final int JOB_ID = 42313;

    public boolean onStartJob(JobParameters p) {
        try {
            // 用户主动关过（offline）时什么都不做：JobScheduler 不能变成「关不掉」的来源。
            if (!KeepAlive.closedByUser(this)) {
                QqbotGuardService.start(this);
                Thread th = new Thread(new KeepAliveJob(this, p), "qqbot-job");
                th.setDaemon(true);
                th.start();
                // 上面把活交给线程了，这里必须 return true 让系统继续替我们持有唤醒，
                // 线程跑完再 jobFinished() 交还；否则任务立刻结束、线程可能被挂起。
                return true;
            }
        } catch (Throwable t) {
            android.util.Log.w("QqbotKeepAlive", "JobScheduler 心跳异常：" + t);
        }
        return false;
    }

    public boolean onStopJob(JobParameters p) {
        // 系统提前打断（比如进入 Doze）：要求重排，别让这条线断掉。
        return true;
    }
}
