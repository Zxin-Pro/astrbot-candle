package com.termux.app;

import android.app.NotificationManager;
import android.content.Context;

import org.json.JSONObject;

import java.io.File;

/**
 * 为什么需要它：QQ 被风控（要验证 / 被踢下线）时，NapCat 进程往往还在、端口也还通，
 * 守护循环只查 pid 完全看不出来，只会一遍遍「重启」把风控撞得更死。风控的判定在沙盒层
 * （risk-words.txt + 巡检脚本），原生层只做三件事：读出它写的 risk.json、命中的那一刻
 * 发一条通知、用户点「我知道了」时清掉标记让守护继续跑。解析失败一律当「无风控」，
 * 宁可漏报也不许因为一个坏文件把自动重启永久锁死。
 * d8 处理匿名内部类会崩，本文件不出现匿名类。
 */
public final class RiskReader {

    /** 与守护通知分开的 id，互不覆盖。 */
    public static final int NOTI_ID = 42311;
    public static final String CHANNEL_ID = "qqbot_risk";

    private static final String NAME = "risk.json";
    private static final String NOTIFIED = "risk.notified";

    /** 同一次风控只通知一次的去重键（instance|since|word）。 */
    private static volatile String lastKey = "";

    private RiskReader() {}

    static File riskFile(File filesDir) {
        return StateIO.file(filesDir, NAME);
    }

    /** 解析 risk.json；不存在 / 坏文件 / 非对象 一律返回 null（= 无风控）。 */
    private static JSONObject parse(File filesDir) {
        String s = StateIO.read(riskFile(filesDir));
        if (s == null) return null;
        try {
            JSONObject o = new JSONObject(s);
            if (!o.optBoolean("risk", false)) return null;
            return o;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 守护循环用：真有风控才停止自动重启。 */
    static boolean paused(File filesDir) {
        return parse(filesDir) != null;
    }

    /**
     * 桥方法 riskInfo()：有风控就原样带回沙盒层写的字段，没有就 {"ok":true,"risk":false}。
     * 只读一个几百字节的文件，毫秒级返回。
     */
    public static String riskInfo(File filesDir) {
        JSONObject out = new JSONObject();
        try {
            JSONObject r = parse(filesDir);
            out.put("ok", true);
            if (r == null) {
                out.put("risk", false);
                return out.toString();
            }
            out.put("risk", true);
            out.put("instance", r.optString("instance", ""));
            out.put("word", r.optString("word", ""));
            out.put("line", r.optString("line", ""));
            out.put("since", r.optLong("since", 0L));
            out.put("file", r.optString("file", ""));
        } catch (Throwable t) {
            return "{\"ok\":false,\"err\":\"读取风控状态失败\"}";
        }
        return out.toString();
    }

    /**
     * 桥方法 riskClear()（UI 的「我知道了」）：删 risk.json 与所有 state/risk-*.flag，
     * 并撤掉那条通知、清掉去重键，让下次命中还能再提醒一次。
     */
    public static String clear(Context c, File filesDir) {
        try {
            StateIO.delete(riskFile(filesDir));
            File dir = StateIO.dir(filesDir);
            File[] ls = dir.listFiles();
            int n = 0;
            if (ls != null) {
                for (int i = 0; i < ls.length; i++) {
                    String nm = ls[i].getName();
                    if (nm != null && nm.startsWith("risk-") && nm.endsWith(".flag")) {
                        if (StateIO.delete(ls[i])) n++;
                    }
                }
            }
            StateIO.delete(StateIO.file(filesDir, NOTIFIED));
            lastKey = "";
            try {
                // 旧的 qqbot_risk 渠道通知（1.3.5 之前发出的）还得撤，用户手机上可能还挂着
                NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.cancel(NOTI_ID);
            } catch (Throwable ignored) {
            }
            // 现在真正显示的是 QqbotAlertCenter 的 crit 通知，一并撤掉，否则点「我知道了」后它还杵在那
            try {
                QqbotAlertCenter.dismissRisk(c);
            } catch (Throwable ignored) {
            }
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("cleared", n);
            return o.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"err\":\"清除风控标记失败\"}";
        }
    }

    /**
     * 守护循环每轮调用一次：真有风控且这一条还没通知过，就发一条通知。
     * 通知只在这里发（单一入口），避免重启循环把用户刷屏。
     */
    static synchronized void notifyIfNew(QqbotGuardService svc) {
        if (svc == null) return;
        File filesDir = svc.getFilesDir();
        JSONObject r = parse(filesDir);
        if (r == null) {
            // 风控解除后清空去重键：同一个实例下次再命中要能再提醒
            if (lastKey.length() > 0) {
                lastKey = "";
                StateIO.delete(StateIO.file(filesDir, NOTIFIED));
            }
            return;
        }
        String inst = r.optString("instance", "");
        long since = r.optLong("since", 0L);
        String key = inst + "|" + since + "|" + r.optString("word", "");
        if (lastKey.length() == 0) lastKey = StateIO.read(StateIO.file(filesDir, NOTIFIED));
        if (key.equals(lastKey)) return;
        lastKey = key;
        StateIO.write(StateIO.file(filesDir, NOTIFIED), key);
        post(svc, inst, r.optString("word", ""));
    }

    /**
     * 通知出口：从 1.3.5 起风控提醒不再自己发通知，统一交给 QqbotAlertCenter 的 crit 渠道。
     * 为什么改：用户要求告警只有一个总开关，否则「五类告警」的静音/免打扰管不到风控这一条，
     * 半夜照样弹窗。这里只负责写清「发生了什么 + 该怎么办」两句人话，剩下的分渠道、升级、
     * 免打扰、静音按钮都由 QqbotAlertCenter 统一处理。
     */
    private static void post(QqbotGuardService svc, String inst, String word) {
        try {
            // 标题照协议：「实例 X 可能需要验证」；拿不到实例号时不留多余空格
            String title = (inst == null || inst.length() == 0)
                    ? "实例可能需要验证"
                    : ("实例 " + inst + " 可能需要验证");
            String why = (word == null || word.length() == 0) ? "" : ("（" + word + "）");
            // 处置建议写进正文：这条属于 crit，用户半夜被叫醒总得知道下一步点什么
            String text = "已暂停自动重启" + why
                    + "。建议：先在 QQ 里手动过验证，再点通知里的「重连」；"
                    + "不确定发生了什么就先点「看日志」，也可以「静音 1 小时」等天亮处理。";
            QqbotAlertCenter.raise(svc, "risk", "crit", title, text);
        } catch (Throwable ignored) {
        }
    }
}
