package com.termux.app;

import org.json.JSONObject;

import java.io.File;

/**
 * 保存 AstrBot 配置：停沙盒 → 写配置与人格 → 重新拉起。
 *
 * 为什么必须先停：AstrBot 退出时会把运行时的配置对象写回 cmd_config.json，
 * 「先写文件再重启」会被它自己的收尾覆盖掉，用户看到的就是「保存了但没生效」。
 *
 * 整个过程 40 秒上下（stop-sandbox.sh 要等 proot 自己退），不能放在桥线程里，
 * 前端用 astrSavePoll() 轮询进度。
 * d8 处理匿名内部类会崩，所以必须是具名顶层类。
 */
public class AstrSaveTask implements Runnable {

    public static final String S_IDLE = "idle";
    public static final String S_STOP = "stopping";
    public static final String S_WRITE = "writing";
    public static final String S_START = "starting";
    public static final String S_DONE = "done";
    public static final String S_ERR = "error";

    private static volatile String state = S_IDLE;
    private static volatile String message = "";
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

    /** 前端轮询：{"state":"...","msg":"...","age":秒}。 */
    public static String poll() {
        JSONObject o = new JSONObject();
        try {
            o.put("state", state);
            o.put("msg", message);
            o.put("age", at <= 0 ? 0 : (System.currentTimeMillis() - at) / 1000);
        } catch (Throwable ignored) {
        }
        return o.toString();
    }

    public static boolean busy() {
        return S_STOP.equals(state) || S_WRITE.equals(state) || S_START.equals(state);
    }

    private static void set(String s, String m) {
        state = s;
        message = (m == null) ? "" : m;
        at = System.currentTimeMillis();
    }

    public void run() {
        File filesDir = act.filesDirPub();
        String usr = act.usrDir().getAbsolutePath();
        String qh = act.qqbotDir().getAbsolutePath();
        try {
            set(S_STOP, "正在停止服务…");
            QqbotShell.run(filesDir, "bash " + usr + "/opt/qqbot/stop-sandbox.sh", 180);

            set(S_WRITE, "正在写入配置…");
            AstrCfg.apply(filesDir, base, model, key, persona);

            set(S_START, "正在启动服务…");
            String cmd = "mkdir -p " + qh + "/logs; nohup setsid bash " + usr
                    + "/opt/qqbot/start-sandbox.sh > " + qh + "/logs/supervisor.log 2>&1 < /dev/null & sleep 4; echo ok";
            QqbotShell.run(filesDir, cmd, 240);

            set(S_DONE, "配置已保存，服务正在启动");
        } catch (Throwable t) {
            set(S_ERR, String.valueOf(t));
            // 写配置失败也要把服务拉回来，不能把用户的机器人一直停在那儿。
            try {
                String cmd = "mkdir -p " + qh + "/logs; nohup setsid bash " + usr
                        + "/opt/qqbot/start-sandbox.sh > " + qh + "/logs/supervisor.log 2>&1 < /dev/null & sleep 4; echo ok";
                QqbotShell.run(filesDir, cmd, 240);
            } catch (Throwable ignored) {
            }
        }
    }
}
