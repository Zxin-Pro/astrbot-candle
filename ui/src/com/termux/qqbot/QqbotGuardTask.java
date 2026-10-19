package com.termux.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/**
 * 守护服务的自检循环：启动后 3 秒先自检一次，之后每 60 秒一轮。
 *
 * 为什么首轮要提前：原来循环上来就 sleep 60 秒，开机广播把服务拉起来之后，
 * 用户在 1 分钟内打开应用会看到「沙盒没在跑」，其实只是自检还没轮到第一轮。
 * 每轮结束会把「几个实例在线」写进 state/ui.json（小组件/磁贴/通知标题共用），
 * 顺带看一眼风控标记：有风控就暂停自动重启，并把提醒通知发出去（只发一次）。
 * 抽成独立的具名顶层类是因为 d8 处理匿名内部类会崩。
 */
public class QqbotGuardTask implements Runnable {

    private static final int PERIOD = 60;
    private static final int FIRST_DELAY = 3;

    private final QqbotGuardService svc;
    private final File filesDir;
    private volatile boolean run = true;
    private int failStreak = 0;
    private long backoffUntil = 0L;
    /** 本轮 bots.json 根对象 + 「实例 id -> 端口可连」快照：afterTick 交给 InstancesState 复用，避免再探一遍端口。 */
    private JSONObject botsRoot = null;
    private JSONObject aliveMap = null;

    public QqbotGuardTask(QqbotGuardService s) {
        svc = s;
        filesDir = s.getFilesDir();
    }

    public void stop() { run = false; }

    public void run() {
        svc.setNote("守护已就绪");
        // 首轮 3 秒：给开机后的系统（存储挂载、广播风暴）一点喘息，又不至于让用户干等一分钟
        sleepSeconds(FIRST_DELAY);
        while (run) {
            try {
                tick();
            } catch (Throwable t) {
                try { svc.setNote("自检异常：" + t.getClass().getSimpleName()); } catch (Throwable ignored) {}
            }
            sleepSeconds(PERIOD);
        }
    }

    private void sleepSeconds(int n) {
        for (int i = 0; i < n && run; i++) {
            try { Thread.sleep(1000L); } catch (InterruptedException e) { return; }
        }
    }

    /** 一轮自检 + 收尾（收尾必须跑，即使用 early return 也要把 ui.json 更新掉）。 */
    private void tick() {
        try {
            tickInner();
        } finally {
            afterTick();
        }
    }

    private void tickInner() {
        // 紧急下线优先于一切：用户按了「下线」就绝不能被自检循环偷偷拉回来。
        if (StateIO.file(filesDir, "offline").isFile()) {
            svc.setNote("已紧急下线，自动重启已暂停");
            return;
        }
        // 风控期间同样不重启：越重启撞得越狠，等用户在面板点「我知道了」清标记。
        if (RiskReader.paused(filesDir)) {
            svc.setNote("检测到风控，已暂停自动重启");
            return;
        }
        if (!QqbotShell.bootstrapReady(filesDir)) {
            svc.setNote("等待首次部署（Termux 环境还未解包）");
            return;
        }
        File qq = QqbotShell.qqbotDir(filesDir);
        File deployDone = new File(qq, "deploy.done");
        if (!deployDone.isFile()) {
            File dp = new File(qq, "deploy.pid");
            if (!QqbotShell.pidAlive(dp, null)) {
                File script = new File(QqbotShell.usrDir(filesDir), "opt/qqbot/deploy.sh");
                if (script.isFile()) {
                    // 部署没跑完就把部署续上（部署脚本自己按步骤标记续跑）
                    QqbotShell.run(filesDir, "mkdir -p " + qq.getAbsolutePath() + "/logs " + qq.getAbsolutePath() + "/steps; "
                            + "nohup setsid bash " + QqbotShell.usrDir(filesDir).getAbsolutePath() + "/opt/qqbot/deploy.sh >> "
                            + qq.getAbsolutePath() + "/logs/deploy.log 2>&1 < /dev/null & "
                            + "sleep 2; echo ok", 20);
                    svc.setNote("部署未完成，已续跑部署");
                    return;
                }
            }
            svc.setNote("首次部署进行中…");
            return;
        }
        if (System.currentTimeMillis() < backoffUntil) {
            svc.setNote("沙盒重启失败，稍后重试（第 " + failStreak + " 次）");
            return;
        }
        File pid = new File(qq, "supervisor.pid");
        if (!QqbotShell.pidAlive(pid, "proot")) {
            StringBuilder note = new StringBuilder();
            boolean ok = QqbotShell.startSandbox(filesDir, note);
            if (ok) {
                failStreak = 0;
                svc.setNote(note.toString());
            } else {
                failStreak++;
                // 连续 3 次失败就先歇 3 分钟：反复冷启动只会把电量烧光，问题也不会自己好
                if (failStreak >= 3) backoffUntil = System.currentTimeMillis() + 180000L;
                svc.setNote(note.toString());
            }
            return;
        }
        failStreak = 0;
        QqbotShell.startWatchdog(filesDir, null);
        String dg = BatteryMon.degradeLabel();
        String tail = dg.length() == 0 ? "" : (" · " + ("thermal".equals(dg) ? "温度过高降载中" : "电量过低降载中"));
        svc.setNote("沙盒运行中 · 最近自检 " + nowText() + tail);
    }

    /**
     * 每轮收尾：写 ui.json（小组件/磁贴/通知标题的数据源）+ 风控通知（去重，只发一次）
     * + 今日概览（在线秒数/消息/报错/流量）+ 实例卡片状态（启动/掉线时间）。
     * 这些都不影响「这一轮救没救活沙盒」，所以放在 finally 里跑，early return 也要更新。
     */
    private void afterTick() {
        int online = 0;
        int total = 0;
        try {
            int[] c = counts();
            online = c[0];
            total = c[1];
            // 在线数变了就立刻刷一次标题，不然要等下一个 60 秒周期
            if (UiState.write(filesDir, c[0], c[1])) svc.refreshNoti();
        } catch (Throwable ignored) {
        }
        try {
            TodayStats.tick(filesDir);
        } catch (Throwable ignored) {
        }
        try {
            InstancesState.update(filesDir, botsRoot, aliveMap);
        } catch (Throwable ignored) {
        }
        try {
            // 本方法本来就在守护线程里，直接渲染；状态没变时 refreshIfChanged 会自己跳过
            QqbotWidgetProvider.refreshIfChanged(svc, online, total);
        } catch (Throwable ignored) {
        }
        try {
            RiskReader.notifyIfNew(svc);
        } catch (Throwable ignored) {
        }
        try {
            // 6 小时巡检：距上次尝试够久、开关还开着、有网，才补一次崩溃/统计上报。
            // 本方法已经在守护线程里，periodic() 只负责起线程；关着开关时它连 DNS 都不解析。
            UploadState.periodic(svc);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 数一数几个实例在线：按 bots.json 里每个实例的 napcatPort 探一次（很快，250ms 超时）。
     * bots.json 缺失/损坏时退回「沙盒活着就算 1 个」，免得通知里一直显示 0 个在线。
     */
    private int[] counts() {
        int online = 0;
        int total = 0;
        botsRoot = null;
        aliveMap = new JSONObject();
        // 宿主机有两份 bots.json（home 与 cache，QqbotActivity.writeBots 同时写），
        // 沙盒读的是 cache 那份；这里先读 home，缺失再回落 cache，行为与界面一致。
        File bots = new File(QqbotShell.qqbotDir(filesDir), "bots.json");
        String s = StateIO.read(bots);
        if (s == null) s = StateIO.read(new File(new File(QqbotShell.qqbotDir(filesDir), "cache"), "bots.json"));
        if (s != null) {
            try {
                JSONObject o = new JSONObject(s);
                JSONArray arr = o.optJSONArray("bots");
                if (arr != null) {
                    botsRoot = o;
                    total = arr.length();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject b = arr.optJSONObject(i);
                        if (b == null) continue;
                        String id = b.optString("id", "");
                        int np = b.optInt("napcatPort", 6099);
                        if (np <= 0) np = 6099;
                        boolean on = QqbotShell.portOpen(np, 250);
                        if (on) online++;
                        if (id.length() > 0) aliveMap.put(id, on);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (total <= 0) {
            total = 1;
            online = QqbotShell.pidAlive(new File(QqbotShell.qqbotDir(filesDir), "supervisor.pid"), "proot") ? 1 : 0;
        }
        return new int[] { online, total };
    }

    private String nowText() {
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm:ss");
            return f.format(new java.util.Date());
        } catch (Throwable t) {
            return "";
        }
    }
}
