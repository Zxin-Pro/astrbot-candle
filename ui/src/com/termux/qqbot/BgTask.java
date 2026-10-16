package com.termux.app;

/**
 * 在后台线程跑一段 shell，跑完用 Toast 提示。
 * 删除实例、重新对接这类操作要开 proot 沙盒，单次要十几秒；直接放在
 * WebView 的 JS 桥线程里做会把页面冻住，所以丢到后台线程去。
 * 注意：d8 处理匿名内部类会崩，所以这里必须是具名顶层类。
 */
public class BgTask implements Runnable {

    private final QqbotActivity act;
    private final String cmd;
    private final String done;
    private final String fail;
    private final int timeoutSec;

    public BgTask(QqbotActivity act, String cmd, String done) {
        this(act, cmd, done, null, 300);
    }

    public BgTask(QqbotActivity act, String cmd, String done, String fail, int timeoutSec) {
        this.act = act;
        this.cmd = cmd;
        this.done = done;
        this.fail = fail;
        this.timeoutSec = timeoutSec > 0 ? timeoutSec : 300;
    }

    public void run() {
        String out = "";
        try {
            // 末尾补一句 echo：runShell 把 stderr 并进了 stdout，但拿不到退出码，
            // 原来不管脚本成没成都 toast「已完成」，delbot.sh / link.sh 报错用户永远看不见。
            out = act.runShell(cmd + "\necho __QQBOT_RC=$?__\n", timeoutSec);
        } catch (Throwable t) {
            out = String.valueOf(t);
        }
        boolean timedOut = out != null && out.indexOf("[timeout]") >= 0;
        boolean ok = !timedOut && rcOf(out) == 0;
        String msg;
        if (ok) {
            msg = (done == null || done.length() == 0) ? "已完成" : done;
        } else {
            String head = (fail == null || fail.length() == 0) ? "操作失败" : fail;
            String tail = tailOf(out);
            msg = head + "：" + tail;
        }
        try {
            act.activity().runOnUiThread(new UiTask(act, UiTask.TOAST, msg));
        } catch (Throwable ignored) {
        }
    }

    /** 取输出里最后一个 __QQBOT_RC= 后面的数字；找不到返回 -1。 */
    private static int rcOf(String s) {
        if (s == null) return -1;
        int i = s.lastIndexOf("__QQBOT_RC=");
        if (i < 0) return -1;
        int j = i + "__QQBOT_RC=".length();
        int k = j;
        while (k < s.length() && s.charAt(k) >= '0' && s.charAt(k) <= '9') k++;
        if (k == j) return -1;
        try { return Integer.parseInt(s.substring(j, k)); } catch (Throwable t) { return -1; }
    }

    /** 失败时给 toast 补一句现场：最后一行非空输出（跳过我们自己 echo 的那行）。 */
    private static String tailOf(String s) {
        if (s == null) return "";
        if (s.indexOf("[timeout]") >= 0) return "命令超时没返回";
        String[] parts = s.split("\n");
        for (int i = parts.length - 1; i >= 0; i--) {
            String t = parts[i].trim();
            if (t.length() == 0) continue;
            if (t.indexOf("__QQBOT_RC=") >= 0) continue;
            if (t.length() > 90) t = t.substring(0, 90) + "...";
            return t;
        }
        return "没有输出";
    }
}