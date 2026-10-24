package com.termux.app;

import org.json.JSONObject;

import java.io.File;

/**
 * 界面上显示的「账号 / 密码 / Token」一律从真实配置文件读，不再写死。
 *
 * 为什么要单独一层：
 *  - AstrBot 控制台的账号密码在 cmd_config.json 的 dashboard 段（username 明文、
 *    password 是 md5，4.29 起还多一份 pbkdf2_password）。**哈希不可逆**，所以「密码是
 *    不是默认值」只能靠猜，最准的办法是拿默认密码真去登录一次：200 就说明还是默认
 *    密码，401 就说明用户改过 —— 改过就绝不能把默认密码摆在界面上让人拿去试。
 *  - NapCat 的 WebUI token 真正生效的值在它自己的 webui.json（run-napcat.sh 每次启动
 *    用 bots.json 的值重写它，所以两边一致；但 NapCat 也可能自己改写，以它的文件为准）。
 *  - 这些都只是读，不写任何配置。
 *
 * d8 处理匿名内部类会崩，本文件不出现匿名类或 lambda。
 */
public final class AstrAccount {

    /** 登录探针结果的缓存时长：控制台起来后每轮刷新都发一次登录请求太浪费，90 秒够新鲜。 */
    private static final long CACHE_MS = 90000L;

    private static long probeAt = 0L;
    private static String probeKey = "";
    private static String probePass = "";

    private AstrAccount() {}

    /**
     * 返回 {"ok":true,...}：
     *  user        控制台账号（dashboard.username）
     *  pass        能用的明文密码；改过 / 无法确认时是空串
     *  passDefault 是否仍是部署时的默认密码
     *  passChecked 这个结论是不是「真登录验过」（控制台没起来时只是按 md5 推的）
     *  port        控制台端口
     *  napToken / napPort / napSrc  NapCat WebUI 的真实 token 与端口、来源文件
     */
    public static String info(QqbotActivity act) {
        JSONObject out = new JSONObject();
        try {
            File files = act.filesDirPub();
            JSONObject bots = null;
            try { bots = new JSONObject(act.readBots()); } catch (Throwable t) { bots = null; }
            String activeId = (bots == null) ? "" : bots.optString("active", "");
            JSONObject ab = activeBot(bots, activeId);
            int port = act.astrbotPortOf(bots);
            boolean up = false;
            try { up = act.portOpen(port); } catch (Throwable ignored) {}

            /* ---- AstrBot 控制台账号 / 密码 ---- */
            String user = AstrCfg.DEFAULT_USER;
            boolean mdDefault = true;
            try {
                JSONObject ai = new JSONObject(AstrCfg.info(files));
                String du = ai.optString("dashUser", AstrCfg.DEFAULT_USER);
                if (du.length() > 0) user = du;
                mdDefault = ai.optBoolean("dashPassDefault", true);
            } catch (Throwable ignored) {}

            String pass = "";
            boolean checked = false;
            if (up) {
                String key = port + "|" + user;
                long now = System.currentTimeMillis();
                if (probeKey.equals(key) && (now - probeAt) < CACHE_MS) {
                    pass = probePass;
                    checked = true;
                } else {
                    int code = loginCode(port, user, AstrCfg.DEFAULT_PASS);
                    /*
                     * 只有明确的 401/403 才能断定「密码被改过」。429（登录限流）、503（控制台
                     * 还在起）这类只是「这次问不出来」，此时退回 md5 判断，别把默认密码藏起来 ——
                     * 藏了用户就只能去猜，反而更容易卡住。
                     */
                    if (code == 200) pass = AstrCfg.DEFAULT_PASS;
                    else if (code == 401 || code == 403) pass = "";
                    else pass = mdDefault ? AstrCfg.DEFAULT_PASS : "";
                    checked = true;
                    probeKey = key;
                    probePass = pass;
                    probeAt = now;
                }
            } else {
                /* 控制台没起来时没法验，只能按 md5 推：仍是默认值就照实显示，否则留空。 */
                pass = mdDefault ? AstrCfg.DEFAULT_PASS : "";
            }
            boolean isDefault = checked ? (pass.length() > 0) : mdDefault;

            out.put("user", user);
            out.put("pass", pass);
            out.put("passDefault", isDefault);
            out.put("passChecked", checked);
            out.put("port", port);

            /* ---- NapCat WebUI token / 端口：以 NapCat 自己的 webui.json 为准 ---- */
            String napTok = "";
            int napPort = 0;
            String napSrc = "";
            File[] cands = webuiCands(files, activeId);
            for (int i = 0; i < cands.length; i++) {
                JSONObject w = readJson(cands[i]);
                if (w == null) continue;
                String tk = w.optString("token", "");
                if (tk.length() == 0) continue;
                napTok = tk;
                napPort = w.optInt("port", 0);
                napSrc = cands[i].getAbsolutePath();
                break;
            }
            /* webui.json 还没生成（没启动过 / 刚清过数据）时退回 bots.json：那份是启动脚本的输入。 */
            if (napTok.length() == 0 && ab != null) {
                napTok = ab.optString("token", "");
                if (napTok.length() > 0) napSrc = "bots.json";
            }
            if (napPort <= 0 && ab != null) napPort = ab.optInt("napcatPort", 0);
            if (napPort <= 0) napPort = 6099;
            out.put("napToken", napTok);
            out.put("napPort", napPort);
            out.put("napSrc", napSrc);

            out.put("ok", true);
        } catch (Throwable t) {
            try {
                out.put("ok", false);
                out.put("err", String.valueOf(t));
            } catch (Throwable ignored) {}
        }
        return out.toString();
    }

    /** webui.json 的两处候选位置：实例自己的那份优先，其次是共用 QQ 目录（活动实例常用这个）。 */
    private static File[] webuiCands(File files, String activeId) {
        File home = QqbotShell.homeDir(files);
        File base = new File(home, "ubuntu/opt/QQ/resources/app/app_launcher/napcat/config/webui.json");
        if (activeId == null || activeId.length() == 0 || !safeId(activeId)) {
            return new File[]{ base };
        }
        File inst = new File(home, "ubuntu/opt/qqbot-inst/" + activeId
                + "/QQ/resources/app/app_launcher/napcat/config/webui.json");
        return new File[]{ inst, base };
    }

    /** 实例 id 只允许字母数字和下划线：拼路径前先挡掉 ../ 之类。 */
    private static boolean safeId(String s) {
        if (s.length() > 32) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean okCh = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_';
            if (!okCh) return false;
        }
        return true;
    }

    /** bots.json 里 active 指定的那个实例；找不到就取第一个。 */
    private static JSONObject activeBot(JSONObject bots, String activeId) {
        if (bots == null) return null;
        try {
            org.json.JSONArray arr = bots.optJSONArray("bots");
            if (arr == null) return null;
            JSONObject first = null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject b = arr.optJSONObject(i);
                if (b == null) continue;
                if (first == null) first = b;
                if (activeId.length() > 0 && activeId.equals(b.optString("id", ""))) return b;
            }
            return first;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读一个小 JSON 文件（配置文件都很小）；读不到就 null。 */
    private static JSONObject readJson(File f) {
        try {
            if (f == null || !f.isFile()) return null;
            long len = f.length();
            if (len <= 0 || len > 1024L * 1024L) return null;
            String s = AstrCfg.readText(f);
            if (s == null) return null;
            return new JSONObject(s);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 拿默认密码真去登录一次，返回 HTTP 码（200 = 仍是默认密码，401 = 改过，0 = 问不出来）。 */
    private static int loginCode(int port, String user, String pass) {
        try {
            JSONObject b = new JSONObject();
            b.put("username", user);
            b.put("password", pass);
            AstrHot.Resp r = AstrHot.send("http://127.0.0.1:" + port + "/api/auth/login",
                    "POST", b.toString(), null);
            return r.code;
        } catch (Throwable t) {
            return 0;
        }
    }
}
