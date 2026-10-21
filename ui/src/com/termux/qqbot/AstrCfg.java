package com.termux.app;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * AstrBot 配置的读写层：模型提供商（provider）写进 cmd_config.json，
 * 人格（persona）写进 data_v4.db 的 personas 表。
 *
 * 为什么不走 dashboard 的 HTTP 接口：控制台要登录、要维护 JWT，还得跟着
 * AstrBot 的接口版本走。这两个文件就是它的真身，直接改文件等价于在面板里点保存，
 * 重启后一定生效（写之前沙盒已经停了，不会被运行时覆盖）。
 *
 * d8 处理匿名内部类会崩，本文件不出现匿名类。
 */
public final class AstrCfg {

    /** 写进 provider 列表里的固定 id：重复保存时按它覆盖，不会越长越多。 */
    public static final String PROVIDER_ID = "candle";
    /** 人格 id：AstrBot 的 "default" 是内置常量，不能覆盖，所以自建一个。 */
    public static final String PERSONA_ID = "zhuxin";
    /** 默认中转站地址（worldcodes 的 OpenAI 兼容入口）。 */
    public static final String DEFAULT_BASE = "https://worldcodes.online/v1";
    /** 推荐注册地址，界面上做「烛心推荐用这商」的入口。 */
    public static final String RELAY_REG = "https://worldcodes.online/register?invite_code=CZLT";

    /** 默认人格：手机上跑的小助手，说话要短、要口语，不要列表和标题。 */
    public static final String DEFAULT_PERSONA =
            "你是「烛心」，一个跑在手机上的 AI 助手，通过 QQ 和人聊天。\n"
            + "\n"
            + "说话方式：\n"
            + "- 用中文口语，像熟人聊天，不要书面腔，不要分点，不要小标题\n"
            + "- 一般回一到三句话；对方明确要求详细展开时再展开\n"
            + "- 不堆感叹号，不用「哈哈」「嘿嘿」这类填充词\n"
            + "- 不确定的事直接说不确定，不编造事实、链接、数字\n"
            + "\n"
            + "边界：\n"
            + "- 不假装自己是真人，也不扮演别人的人格\n"
            + "- 不输出违法、色情、暴力的内容\n"
            + "- 医疗、法律、投资类问题只给常识性提醒，并建议找专业人士";

    private AstrCfg() {}

    /* ---------- 路径 ---------- */

    /** $HOME/ubuntu/opt/AstrBot/data/cmd_config.json */
    public static File cfgFile(File filesDir) {
        return new File(QqbotShell.homeDir(filesDir), "ubuntu/opt/AstrBot/data/cmd_config.json");
    }

    /** $HOME/ubuntu/opt/AstrBot/data/data_v4.db */
    public static File dbFile(File filesDir) {
        return new File(QqbotShell.homeDir(filesDir), "ubuntu/opt/AstrBot/data/data_v4.db");
    }

    /* ---------- 读 ---------- */

    /**
     * 当前生效的配置，给界面回填用。
     * 返回值永远是合法 JSON：读不到就给 ok=false，界面照常渲染空表单。
     */
    public static String info(File filesDir) {
        JSONObject out = new JSONObject();
        try {
            File f = cfgFile(filesDir);
            out.put("ok", true);
            out.put("relay", RELAY_REG);
            out.put("providerId", PROVIDER_ID);
            out.put("personaId", PERSONA_ID);
            out.put("defaultBase", DEFAULT_BASE);
            out.put("defaultPersona", DEFAULT_PERSONA);
            out.put("cfgExists", f.isFile());
            String base = "";
            String model = "";
            String key = "";
            if (f.isFile()) {
                JSONObject root = new JSONObject(readText(f));
                JSONObject p = findProvider(root);
                if (p != null) {
                    base = p.optString("api_base", "");
                    model = p.optString("model", "");
                    JSONArray ks = p.optJSONArray("key");
                    if (ks != null && ks.length() > 0) key = ks.optString(0, "");
                }
                JSONObject ar = root.optJSONObject("agent_runner");
                if (ar != null) {
                    JSONObject ac = ar.optJSONObject("config");
                    if (ac != null) {
                        JSONObject pm = ac.optJSONObject("persona");
                        if (pm != null) out.put("personaSel", pm.optString("persona_id", "default"));
                    }
                }
            }
            out.put("base", base);
            out.put("model", model);
            out.put("hasKey", key.length() > 0);
            out.put("keyTail", tailOf(key));
            out.put("persona", personaPrompt(filesDir));
        } catch (Throwable t) {
            try {
                out.put("ok", false);
                out.put("err", String.valueOf(t));
            } catch (Throwable ignored) {}
        }
        return out.toString();
    }

    /** 读人格提示词；还没写过就返回默认值。 */
    public static String personaPrompt(File filesDir) {
        File db = dbFile(filesDir);
        if (!db.isFile()) return DEFAULT_PERSONA;
        SQLiteDatabase d = null;
        Cursor c = null;
        try {
            d = SQLiteDatabase.openDatabase(db.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            c = d.rawQuery("SELECT system_prompt FROM personas WHERE persona_id = ?",
                    new String[] { PERSONA_ID });
            if (c.moveToFirst()) {
                String s = c.getString(0);
                if (s != null && s.trim().length() > 0) return s;
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (c != null) c.close(); } catch (Throwable ignored) {}
            try { if (d != null) d.close(); } catch (Throwable ignored) {}
        }
        return DEFAULT_PERSONA;
    }

    private static JSONObject findProvider(JSONObject root) {
        JSONArray arr = root.optJSONArray("provider");
        if (arr == null) return null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && PROVIDER_ID.equals(o.optString("id"))) return o;
        }
        return null;
    }

    /* ---------- 写 ---------- */

    /**
     * 把模型与人格写进沙盒。
     * 调用前必须先停掉沙盒——运行中的 AstrBot 退出时可能把内存里的旧配置写回去。
     * key 传空串表示「沿用已保存的那把」，避免用户只改人格时把密钥抹掉。
     */
    public static void apply(File filesDir, String base, String model, String key, String persona)
            throws Exception {
        File cfg = cfgFile(filesDir);
        if (!cfg.isFile()) throw new Exception("还没装好 AstrBot（找不到 cmd_config.json）");
        String b = (base == null || base.trim().length() == 0) ? DEFAULT_BASE : base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        String m = (model == null) ? "" : model.trim();
        if (m.length() == 0) throw new Exception("模型名不能为空");

        JSONObject root = new JSONObject(readText(cfg));
        JSONArray arr = root.optJSONArray("provider");
        if (arr == null) arr = new JSONArray();
        JSONObject p = findProvider(root);
        boolean isNew = false;
        if (p == null) {
            p = new JSONObject();
            p.put("id", PROVIDER_ID);
            p.put("provider", "openai");
            p.put("type", "openai_chat_completion");
            p.put("provider_type", "chat_completion");
            isNew = true;
        }
        p.put("enable", true);
        p.put("api_base", b);
        p.put("model", m);
        p.put("timeout", 120);
        if (!p.has("proxy")) p.put("proxy", "");
        if (!p.has("custom_headers")) p.put("custom_headers", new JSONObject());
        String k = (key == null) ? "" : key.trim();
        if (k.length() > 0) {
            JSONArray keys = new JSONArray();
            keys.put(k);
            p.put("key", keys);
        } else if (p.optJSONArray("key") == null) {
            p.put("key", new JSONArray());
        }
        if (isNew) arr.put(p);
        root.put("provider", arr);

        JSONObject ar = root.optJSONObject("agent_runner");
        if (ar == null) ar = new JSONObject();
        if (!ar.has("runner_type")) ar.put("runner_type", "local");
        JSONObject ac = ar.optJSONObject("config");
        if (ac == null) ac = new JSONObject();
        JSONObject mm = ac.optJSONObject("model");
        if (mm == null) mm = new JSONObject();
        mm.put("provider_id", PROVIDER_ID);
        if (!mm.has("fallback_provider_ids")) mm.put("fallback_provider_ids", new JSONArray());
        if (!mm.has("request_max_retries")) mm.put("request_max_retries", 5);
        ac.put("model", mm);
        JSONObject pp = ac.optJSONObject("persona");
        if (pp == null) pp = new JSONObject();
        pp.put("persona_id", PERSONA_ID);
        if (!pp.has("safety_mode")) pp.put("safety_mode", true);
        if (!pp.has("safety_mode_strategy")) pp.put("safety_mode_strategy", "system_prompt");
        ac.put("persona", pp);
        ar.put("config", ac);
        root.put("agent_runner", ar);

        writeText(cfg, root.toString(2));

        String ps = (persona == null) ? "" : persona.trim();
        if (ps.length() == 0) ps = DEFAULT_PERSONA;
        upsertPersona(filesDir, ps);
    }

    /** 人格写库：有就改，没有就插。tools/skills 留 NULL = 允许使用全部工具。 */
    static void upsertPersona(File filesDir, String prompt) throws Exception {
        File db = dbFile(filesDir);
        if (!db.isFile()) throw new Exception("还没装好 AstrBot（找不到 data_v4.db）");
        String now = utc();
        SQLiteDatabase d = null;
        try {
            d = SQLiteDatabase.openDatabase(db.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
            ContentValues cv = new ContentValues();
            cv.put("system_prompt", prompt);
            cv.put("updated_at", now);
            int n = d.update("personas", cv, "persona_id = ?", new String[] { PERSONA_ID });
            if (n == 0) {
                ContentValues in = new ContentValues();
                in.put("created_at", now);
                in.put("updated_at", now);
                in.put("persona_id", PERSONA_ID);
                in.put("system_prompt", prompt);
                in.put("sort_order", 0);
                d.insertOrThrow("personas", null, in);
            }
        } finally {
            try { if (d != null) d.close(); } catch (Throwable ignored) {}
        }
    }

    /* ---------- 小工具 ---------- */

    /** AstrBot 的 TimestampMixin 存的是 UTC 的 "YYYY-MM-DD HH:MM:SS.SSSSSS"。 */
    static String utc() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSSSS", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    static String tailOf(String k) {
        if (k == null || k.length() < 6) return "";
        return k.substring(k.length() - 4);
    }

    static String readText(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            String s = new String(bo.toByteArray(), "UTF-8");
            // 有些版本的配置文件带 BOM，JSONObject 见到 \uFEFF 会直接抛错。
            if (s.length() > 0 && s.charAt(0) == '\uFEFF') s = s.substring(1);
            return s;
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
        }
    }

    /** 先写临时文件再改名：中途失败不会留下半个 JSON 把 AstrBot 卡死。 */
    static void writeText(File f, String s) throws Exception {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(s.getBytes("UTF-8"));
            out.flush();
            out.getFD().sync();
        } finally {
            try { out.close(); } catch (Throwable ignored) {}
        }
        if (f.exists() && !f.delete()) {
            try { tmp.delete(); } catch (Throwable ignored) {}
            throw new Exception("配置文件被占用，写不进去");
        }
        if (!tmp.renameTo(f)) {
            try { tmp.delete(); } catch (Throwable ignored) {}
            throw new Exception("配置文件改名失败");
        }
    }
}
