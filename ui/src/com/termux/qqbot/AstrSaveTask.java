package com.termux.app;

import org.json.JSONObject;

import java.io.File;

/**
 * 保存 AstrBot 配置：优先「热改」，改不动才退回「停沙盒 → 写文件 → 重新拉起」。
 *
 * 热改（AstrHot）：调本机控制台的 provider / persona 接口。控制台内部会重建那一个 provider
 * 实例并把配置写回 cmd_config.json，人格写数据库后同步刷新内存缓存，所以消息链路下一次
 * 调用就是新模型，不需要停沙盒。1 秒上下完成。
 *
 * 兜底（老路）：控制台没起来、口令被改过、接口版本对不上时，仍然走
 * 停沙盒 → AstrCfg.apply 写文件 → 重新拉起，约 40 秒。之所以要先停：AstrBot 退出时会把
 * 运行时配置对象写回 cmd_config.json，「先写文件再重启」会被它自己的收尾覆盖。
 *
 * 前端用 astrSavePoll() 轮询进度；d8 处理匿名内部类会崩，所以必须是具名顶层类。
 */
public class AstrSaveTask implements Runnable {

    public static final String S_IDLE = "idle";
    public static final String S_HOT = "applying";
    public static final String S_STOP = "stopping";
    public static final String S_WRITE = "writing";
    public static final String S_START = "starting";
    public static final String S_DONE = "done";
    public static final String S_ERR = "error";

    private static volatile String state = S_IDLE;
    private static volatile String message = "";
    private static volatile boolean hot = false;
    private static volatile long at = 0L;

    private final QqbotActivity act;
    private final String base;
    private final String model;
    private final String key;
    private final String persona;

    public AstrSaveTask(QqbotActivity act, String base, String model, String key, String persona) {
        this.act = act;
        this.base = base;
        this.model = model;
        this.key = key;
        this.persona = persona;
    }

    /** 前端轮询：{"state":"...","msg":"...","age":秒,"hot":是否热改}。 */
    public static String poll() {
        JSONObject o = new JSONObject();
        try {
            o.put("state", state);
            o.put("msg", message);
            o.put("hot", hot);
            o.put("age", at <= 0 ? 0 : (System.currentTimeMillis() - at) / 1000);
        } catch (Throwable ignored) {
        }
        return o.toString();
    }

    public static boolean busy() {
        return S_HOT.equals(state) || S_STOP.equals(state) || S_WRITE.equals(state) || S_START.equals(state);
    }

    private static void set(String s, String m) {
        state = s;
        message = (m == null) ? "" : m;
        at = System.currentTimeMillis();
    }

    public void run() {
        String why = tryHot();
        if (why == null) {
            hot = true;
            set(S_DONE, "已生效，无需重启");
            return;
        }
        hot = false;
        legacy(why);
    }

    /** 热改；成功回 null，失败回原因（给前端看）。 */
    private String tryHot() {
        try {
            int port = act.astrbotPort();
            if (port <= 0) return "控制台端口未知";
            set(S_HOT, "正在写入并热加载…");
            JSONObject r = new JSONObject(AstrHot.apply(port, base, model, key, persona));
            if (r.optBoolean("ok", false)) return null;
            return r.optString("err", "未知原因");
        } catch (Throwable t) {
            return String.valueOf(t);
        }
    }

    private void legacy(String why) {
        File filesDir = act.filesDirPub();
        String usr = act.usrDir().getAbsolutePath();
        String qh = act.qqbotDir().getAbsolutePath();
        String restart = "mkdir -p " + qh + "/logs; nohup setsid bash " + usr
                + "/opt/qqbot/start-sandbox.sh > " + qh + "/logs/supervisor.log 2>&1 < /dev/null & sleep 4; echo ok";
        try {
            set(S_STOP, "热改没成功（" + why + "），改用重启方式保存：正在停止服务…");
            QqbotShell.run(filesDir, "bash " + usr + "/opt/qqbot/stop-sandbox.sh", 180);
            /* 停不下来就别写文件。运行中的 AstrBot 退出时会把内存里的旧配置写回
               cmd_config.json，先写后启动等于白写；更糟的是界面会显示「已保存」，
               用户以为改成了、实际还是旧的。宁可报错让他重试。 */
            if (QqbotShell.pidAlive(new File(qh, "supervisor.pid"), "proot")) {
                throw new Exception("服务没有停下来（stop-sandbox.sh 超时），这次没有改配置，请稍后再试");
            }

            set(S_WRITE, "正在写入配置…");
            AstrCfg.apply(filesDir, base, model, key, persona);

            set(S_START, "正在启动服务…");
            QqbotShell.run(filesDir, restart, 240);

            set(S_DONE, "配置已保存，服务正在启动");
        } catch (Throwable t) {
            set(S_ERR, String.valueOf(t));
            // 写配置失败也要把服务拉回来，不能把用户的机器人一直停在那儿。
            try {
                QqbotShell.run(filesDir, restart, 240);
            } catch (Throwable ignored) {
            }
        }
    }
}
