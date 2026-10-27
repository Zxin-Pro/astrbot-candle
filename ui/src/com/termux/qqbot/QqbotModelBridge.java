package com.termux.app;

import android.webkit.JavascriptInterface;

import org.json.JSONObject;

/**
 * 「测试模型」桥（页面：assets/qqbot/plugins.html 的「模型」页签）。
 *
 * 只做一件事：让沙盒里那个 python 真发一条请求，把结果原样带回来。
 *
 * 为什么不在 Java 里自己发 HTTP：模型地址、密钥、provider 的合并规则全在
 * cmd_config.json 里，而且 AstrBot 4.29 把连接信息拆到了 provider_sources，
 * 在 Java 里再实现一遍 pickProvider/合并逻辑，迟早会和 AstrBot 自己的行为分叉
 * （最典型：界面说「已连通」，机器人却报 401）。复用 model-test.py 就永远一致。
 *
 * 代价是要过一次 qq_proot，冷启动时 2~4 秒，所以只能异步：start() 起线程，
 * 页面轮询 poll()。桥方法跑在同一个 JavaBridge 线程上，同步等 20 秒会把
 * 整个页面（连 state()/readLog()）一起堵死。
 *
 * d8 不接受匿名内部类 / lambda，本文件不出现。
 */
public class QqbotModelBridge {

    /** 沙盒内 AstrBot 的 venv 和脚本路径（见 common.sh 的 qq_proot 绑定）。 */
    private static final String SANDBOX_PY = "/opt/AstrBot/.venv/bin/python";
    private static final String SANDBOX_SCRIPT = "/qqbot-opt/model-test.py";
    /** 脚本自己 8 秒超时，这里留出 proot 冷启动和不稳定网络的余量。 */
    private static final int TIMEOUT_SEC = 22;

    private final QqbotActivity act;

    private static volatile boolean running = false;
    private static volatile String state = "idle";
    /** 上一次的原始结果（脚本输出的那行 JSON），失败时是 {"error":...}。 */
    private static volatile String lastJson = "";

    public QqbotModelBridge(QqbotActivity act) {
        this.act = act;
    }

    /**
     * 开始测试。正在跑的时候重复点不会起第二个进程。
     * 返回的是当前状态 JSON（和 poll() 同形），这样页面点下去就能立刻判断要不要转圈；
     * 老页面把它当 void 用也没问题（忽略返回值即可）。
     */
    @JavascriptInterface
    public String start() {
        synchronized (QqbotModelBridge.class) {
            if (running) return poll();
            running = true;
            state = "running";
            lastJson = "";
        }
        Thread th = new Thread(new Probe(), "qqbot-model-test");
        th.setDaemon(true);
        th.start();
        return poll();
    }

    /**
     * 当前结果。
     * state: idle（没测过）/ running / ok / fail
     * ok 时带 provider/model/ms/reply，fail 时带 error。
     */
    @JavascriptInterface
    public String poll() {
        JSONObject out = new JSONObject();
        try {
            out.put("state", state);
            String j = lastJson;
            if (j.length() > 0) {
                try {
                    JSONObject r = new JSONObject(j);
                    String[] keys = new String[]{"provider", "model", "ms", "reply", "error"};
                    for (int i = 0; i < keys.length; i++) {
                        if (r.has(keys[i])) out.put(keys[i], r.opt(keys[i]));
                    }
                } catch (Throwable ignored) {
                }
            }
            return out.toString();
        } catch (Throwable t) {
            JSONObject e = new JSONObject();
            try {
                e.put("state", "fail");
                e.put("error", "读取测试状态失败：" + t);
            } catch (Throwable ignored) {
            }
            return e.toString();
        }
    }

    /** 后台线程：source common.sh（拿 qq_proot）→ 跑脚本 → 解析最后一行 JSON。 */
    private final class Probe implements Runnable {
        public void run() {
            String json;
            try {
                String cmd = "source \"$PREFIX/opt/qqbot/common.sh\" >/dev/null 2>&1; "
                        + "qq_proot \"" + SANDBOX_PY + " " + SANDBOX_SCRIPT + "\"";
                String out = act.runShell(cmd, TIMEOUT_SEC);
                json = parse(out);
            } catch (Throwable t) {
                json = fail("跑测试脚本失败：" + shortOf(t));
            }
            synchronized (QqbotModelBridge.class) {
                lastJson = json;
                state = okOf(json) ? "ok" : "fail";
                running = false;
            }
        }
    }

    private static boolean okOf(String json) {
        try {
            return new JSONObject(json).optBoolean("ok", false);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 从输出里找结果。脚本只打印一行 JSON，但外面套了 proot、可能还有 shell 的告警，
     * 所以从最后一行往前找第一个能解析成 JSON 对象、且带 ok 字段的行。
     */
    private static String parse(String out) {
        String s = (out == null) ? "" : out;
        String[] lines = s.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.length() < 2 || line.charAt(0) != '{') continue;
            try {
                JSONObject o = new JSONObject(line);
                if (o.has("ok")) return o.toString();
            } catch (Throwable ignored) {
            }
        }
        return fail(explainOutput(s));
    }

    /** 没有 JSON 时，从输出猜用户该做什么——这句会直接显示在界面上。 */
    private static String explainOutput(String s) {
        if (s.length() == 0) return "没有任何输出：沙盒可能没启动，先把部署跑完";
        String low = s.toLowerCase();
        if (low.indexOf("[timeout]") >= 0 || low.indexOf("timed out") >= 0) {
            return "测试超时（" + TIMEOUT_SEC + " 秒）：网络慢或 AstrBot 没起来，稍后再试";
        }
        if (low.indexOf("common.sh") >= 0 && low.indexOf("no such file") >= 0) {
            return "部署脚本不全（找不到 common.sh）：重新跑一次部署";
        }
        if (low.indexOf("no such file") >= 0 && low.indexOf("python") >= 0) {
            return "沙盒里没有装 AstrBot（找不到它的 python）：先把部署跑完";
        }
        if (low.indexOf("permission denied") >= 0) {
            return "没有执行权限：重新部署一次沙盒";
        }
        if (low.indexOf("bash:") >= 0 && low.indexOf("not found") >= 0) {
            return "沙盒的 bash 起不来：重新部署一次";
        }
        String tail = s.trim();
        if (tail.length() > 200) tail = tail.substring(tail.length() - 200);
        return "测试脚本没给出结果：" + tail.replace("\n", " ");
    }

    private static String fail(String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("error", msg);
        } catch (Throwable ignored) {
        }
        return o.toString();
    }

    private static String shortOf(Throwable t) {
        String m = String.valueOf(t);
        if (m.length() > 180) m = m.substring(0, 180) + "…";
        return m;
    }
}
