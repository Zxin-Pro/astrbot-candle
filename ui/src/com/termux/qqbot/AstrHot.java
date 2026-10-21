package com.termux.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 热改 AstrBot：直接调本机控制台接口，改完立刻生效，不重启沙盒。
 *
 * 为什么能热：AstrBot 4.x 的控制台在改 provider 时会调用 provider_manager.update_provider()，
 * 它会把新配置写回 cmd_config.json 并重建那一个 provider 实例；人格改的是数据库里的
 * personas 行，persona_manager 改完顺手刷新了内存缓存；消息链路每次取 provider 都现读
 * agent_runner.config.model.provider_id，所以不需要重开进程。
 *
 * 为什么不用网页里的 fetch：控制台接口不带 Access-Control-Allow-Origin，WebView（file://）
 * 里发出去会被 CORS 挡掉，只能在 Java 侧发。
 *
 * 登录口令是 run-astrbot.sh 里写死的初始密码（Astrbot123）。用户若在网页上改过密码，
 * 这里会 401，AstrSaveTask 会退回到「停沙盒 → 写文件 → 启动」的老路，功能不丢。
 *
 * d8 处理匿名内部类会崩，本文件不出现匿名类或 lambda。
 */
public final class AstrHot {

    /** 控制台初始账号，见 build/opt/qqbot/sandbox/run-astrbot.sh。 */
    private static final String USER = "astrbot";
    private static final String PASS = "Astrbot123";

    private AstrHot() {}

    /** 单个 provider 的调用结果。 */
    private static final class Resp {
        int code;
        String body = "";
    }

    /**
     * 把模型与人格写进正在运行的 AstrBot。
     * 成功返回 {"ok":true,"model":...,"persona":...}；
     * 失败返回 {"ok":false,"err":"..."}，由调用方决定是否退回重启。
     */
    public static String apply(int port, String base, String model, String key, String persona) {
        JSONObject out = new JSONObject();
        String root = "http://127.0.0.1:" + port;
        try {
            String token = login(root);
            String b = (base == null || base.trim().length() == 0) ? AstrCfg.DEFAULT_BASE : base.trim();
            while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
            String m = (model == null) ? "" : model.trim();
            if (m.length() == 0) throw new Exception("模型名不能为空");
            String k = (key == null) ? "" : key.trim();

            /* 1) provider：有就原地更新（会重建实例并落盘），没有就新增。 */
            Resp r = send(root + "/api/v1/providers", "GET", null, token);
            must(r, "读取模型配置");
            JSONObject cfg = pickProvider(r.body);
            boolean isNew = (cfg == null);
            if (isNew) {
                cfg = new JSONObject();
                cfg.put("id", AstrCfg.PROVIDER_ID);
                cfg.put("provider", "openai");
                cfg.put("type", "openai_chat_completion");
                cfg.put("provider_type", "chat_completion");
                cfg.put("proxy", "");
                cfg.put("custom_headers", new JSONObject());
            }
            /* 控制台回的这份是「已生效」的完整配置，直接改字段再发回去，
               不会把用户手工加的 custom_headers / extra_body 抹掉。 */
            cfg.put("enable", true);
            cfg.put("api_base", b);
            cfg.put("model", m);
            cfg.put("timeout", 120);
            if (k.length() > 0) {
                JSONArray keys = new JSONArray();
                keys.put(k);
                cfg.put("key", keys);
            } else if (cfg.optJSONArray("key") == null) {
                cfg.put("key", new JSONArray());
            }
            JSONObject pb = new JSONObject();
            pb.put("provider_id", AstrCfg.PROVIDER_ID);
            pb.put("config", cfg);
            if (isNew) {
                r = send(root + "/api/v1/providers", "POST", pb.toString(), token);
                must(r, "新增模型配置");
            } else {
                r = send(root + "/api/v1/providers/by-id", "PUT", pb.toString(), token);
                must(r, "更新模型配置");
            }

            /* 2) 人格：改 system_prompt，persona_manager 会同步内存缓存。 */
            String ps = (persona == null) ? "" : persona.trim();
            if (ps.length() == 0) ps = AstrCfg.DEFAULT_PERSONA;
            JSONObject per = new JSONObject();
            per.put("persona_id", AstrCfg.PERSONA_ID);
            per.put("system_prompt", ps);
            Resp g = send(root + "/api/v1/personas/by-id?persona_id=" + AstrCfg.PERSONA_ID, "GET", null, token);
            if (g.code == 200) {
                r = send(root + "/api/v1/personas/by-id", "PUT", per.toString(), token);
                must(r, "更新人格");
            } else {
                r = send(root + "/api/v1/personas", "POST", per.toString(), token);
                must(r, "新增人格");
            }

            /* 3) 默认模型/人格指向：只在不对的时候才改，避免无谓地重载 pipeline。 */
            boolean retarget = false;
            Resp sc = send(root + "/api/v1/system-config", "GET", null, token);
            must(sc, "读取运行配置");
            JSONObject data = new JSONObject(sc.body).optJSONObject("data");
            JSONObject conf = (data == null) ? null : data.optJSONObject("config");
            if (conf != null) {
                JSONObject ar = conf.optJSONObject("agent_runner");
                if (ar == null) { ar = new JSONObject(); conf.put("agent_runner", ar); }
                if (!ar.has("runner_type")) ar.put("runner_type", "local");
                JSONObject ac = ar.optJSONObject("config");
                if (ac == null) { ac = new JSONObject(); ar.put("config", ac); }
                JSONObject mm = ac.optJSONObject("model");
                if (mm == null) { mm = new JSONObject(); ac.put("model", mm); }
                if (!AstrCfg.PROVIDER_ID.equals(mm.optString("provider_id"))) {
                    mm.put("provider_id", AstrCfg.PROVIDER_ID);
                    retarget = true;
                }
                JSONObject pp = ac.optJSONObject("persona");
                if (pp == null) { pp = new JSONObject(); ac.put("persona", pp); }
                if (!AstrCfg.PERSONA_ID.equals(pp.optString("persona_id"))) {
                    pp.put("persona_id", AstrCfg.PERSONA_ID);
                    retarget = true;
                }
                if (retarget) {
                    JSONObject sb = new JSONObject();
                    sb.put("config", conf);
                    Resp sr = send(root + "/api/v1/system-config", "PUT", sb.toString(), token);
                    must(sr, "切换默认模型");
                }
            }

            out.put("ok", true);
            out.put("model", m);
            out.put("retarget", retarget);
            return out.toString();
        } catch (Throwable t) {
            try {
                out.put("ok", false);
                out.put("err", String.valueOf(t));
            } catch (Throwable ignored) {}
            return out.toString();
        }
    }

    /** 登录拿 JWT。口令不对/控制台没起来都会抛异常，交给上层走兜底。 */
    static String login(String root) throws Exception {
        JSONObject body = new JSONObject();
        body.put("username", USER);
        body.put("password", PASS);
        Resp r = send(root + "/api/auth/login", "POST", body.toString(), null);
        if (r.code != 200) throw new Exception("控制台登录失败（HTTP " + r.code + "，密码可能被改过）");
        JSONObject o = new JSONObject(r.body);
        JSONObject d = o.optJSONObject("data");
        String token = (d == null) ? "" : d.optString("token", "");
        if (token.length() == 0) throw new Exception("控制台登录没拿到令牌");
        return token;
    }

    /** 从 /api/v1/providers 的返回里找我们那一个（id=candle）。 */
    static JSONObject pickProvider(String body) {
        try {
            JSONObject o = new JSONObject(body);
            JSONObject d = o.optJSONObject("data");
            JSONArray arr = (d == null) ? null : d.optJSONArray("providers");
            if (arr == null) return null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.optJSONObject(i);
                if (p != null && AstrCfg.PROVIDER_ID.equals(p.optString("id"))) return p;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void must(Resp r, String what) throws Exception {
        if (r.code >= 200 && r.code < 300) return;
        String s = (r.body == null) ? "" : r.body;
        if (s.length() > 160) s = s.substring(0, 160);
        throw new Exception(what + "失败（HTTP " + r.code + "）" + (s.length() > 0 ? "：" + s : ""));
    }

    /** 一次 HTTP 调用；只对本机 127.0.0.1 用，所以超时给得比较松（冷启动时首包会慢）。 */
    static Resp send(String url, String method, String body, String token) throws Exception {
        HttpURLConnection c = null;
        Resp out = new Resp();
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(25000);
            c.setRequestMethod(method);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "AstrBotCandle");
            c.setRequestProperty("Accept", "application/json");
            if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                OutputStream os = c.getOutputStream();
                try {
                    os.write(body.getBytes("UTF-8"));
                    os.flush();
                } finally {
                    try { os.close(); } catch (Throwable ignored) {}
                }
            }
            out.code = c.getResponseCode();
            InputStream in = (out.code >= 400) ? c.getErrorStream() : c.getInputStream();
            out.body = readAll(in);
            return out;
        } finally {
            try { if (c != null) c.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
        }
    }
}
