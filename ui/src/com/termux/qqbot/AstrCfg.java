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
    /** 默认中转站地址（worldcodes 的 OpenAI 兼容入口；1.3.5-beta.2 起换成 la 子域）。 */
    public static final String DEFAULT_BASE = "https://la.worldcodes.online/v1";
    /** 推荐注册地址，界面上做「烛心推荐用这家」的入口。 */
    public static final String RELAY_REG = "https://worldcodes.online/register?invite_code=CZLT";
    /** 控制台初始账号 / 密码，见 build/opt/qqbot/sandbox/run-astrbot.sh 的 ASTRBOT_DASHBOARD_INITIAL_PASSWORD。 */
    public static final String DEFAULT_USER = "astrbot";
    public static final String DEFAULT_PASS = "Astrbot123";
    /** md5("Astrbot123")：cmd_config.json 的 dashboard.password 就是密码的 MD5，
        用它比对就能知道用户有没有改过密码（改过就别再显示默认密码，否则会误导）。 */
    public static final String DEFAULT_PASS_MD5 = "51e5572f83abe165783942404477e6bf";

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
            out.put("personaSel", "default");
            String base = "";
            String model = "";
            String key = "";
            String picked = "";
            String srcId = "";
            String srcHost = "";
            if (f.isFile()) {
                JSONObject root = new JSONObject(readText(f));
                JSONObject p = pickProvider(root);
                if (p != null) {
                    picked = p.optString("id", "");
                    model = p.optString("model", "");
                    /*
                     * AstrBot 4.29（config_version 5）把 provider 拆成两段：provider[] 只留
                     * id/model/provider_source_id 这类「指向」，连接信息全挪到 provider_sources[]。
                     * 早期版本是本文件直接写的扁平 api_base/key。只读扁平字段的话，用户配好的
                     * 密钥和地址永远读出来是空的 —— 界面就一直显示「还没配置模型」，
                     * 这不是保存失败，是读错了地方。两代结构都要认。
                     */
                    srcId = p.optString("provider_source_id", "");
                    JSONObject src = (srcId.length() == 0) ? null : findSource(root, srcId);
                    if (src != null) {
                        base = src.optString("api_base", "");
                        key = firstKey(src);
                    }
                    /*
                     * 这里必须和 AstrBot 运行时的合并顺序一致：core/provider/manager.py
                     * 的 get_merged_provider_config() 是 {**provider_source, **provider}，
                     * provider 上的扁平字段会盖掉 source。老版本写的是扁平结构，
                     * 所以「source 有值也要再用 provider 的非空值覆盖一次」，否则
                     * 同一个文件两种读法会给出不同答案。
                     */
                    String pFlatBase = p.optString("api_base", "");
                    String pFlatKey = firstKey(p);
                    if (pFlatBase.length() > 0) base = pFlatBase;
                    if (pFlatKey.length() > 0) key = pFlatKey;
                }
                JSONObject dash = root.optJSONObject("dashboard");
                String du = (dash == null) ? DEFAULT_USER : dash.optString("username", DEFAULT_USER);
                boolean passDefault = (dash == null)
                        || DEFAULT_PASS_MD5.equalsIgnoreCase(dash.optString("password", ""));
                out.put("dashUser", du.length() == 0 ? DEFAULT_USER : du);
                out.put("dashPassDefault", passDefault);
                out.put("dashPassChangeRequired",
                        dash != null && dash.optBoolean("password_change_required", false));
                JSONObject ar = root.optJSONObject("agent_runner");
                if (ar != null) {
                    JSONObject ac = ar.optJSONObject("config");
                    if (ac != null) {
                        JSONObject pm = ac.optJSONObject("persona");
                        if (pm != null) out.put("personaSel", pm.optString("persona_id", "default"));
                    }
                }
            } else {
                out.put("dashUser", DEFAULT_USER);
                out.put("dashPassDefault", true);
                out.put("dashPassChangeRequired", false);
            }
            out.put("base", base);
            out.put("model", model);
            out.put("pickedId", picked);
            out.put("sourceId", srcId);
            out.put("srcHost", hostOf(base));
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

    /** 按 id 在 provider[] 里找一条（不限定 candle）。 */
    private static JSONObject findProviderById(JSONObject root, String id) {
        JSONArray arr = root.optJSONArray("provider");
        if (arr == null) return null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && id.equals(o.optString("id"))) return o;
        }
        return null;
    }

    /**
     * 界面上该显示哪一个 provider：优先我们自己写的 candle；找不到就退到第一条启用的。
     * 用户可能是在 AstrBot 网页控制台里配的，id 就不是 candle —— 那种情况也必须如实显示，
     * 不能因为「不是我们写的那条」就报「还没配置模型」。
     */
    private static JSONObject pickProvider(JSONObject root) {
        /*
         * 先看「当前实际在用哪一条」：agent_runner.config.model.provider_id。
         * 用户在网页控制台里换过 provider 时，卡片要跟着变，不能一直显示 candle。
         */
        JSONObject ar = root.optJSONObject("agent_runner");
        if (ar != null) {
            JSONObject ac = ar.optJSONObject("config");
            JSONObject mm = (ac == null) ? null : ac.optJSONObject("model");
            String want = (mm == null) ? "" : mm.optString("provider_id", "");
            if (want.length() > 0) {
                JSONObject hit = findProviderById(root, want);
                if (hit != null) return hit;
            }
        }
        JSONObject p = findProvider(root);
        if (p != null) return p;
        JSONArray arr = root.optJSONArray("provider");
        if (arr == null) return null;
        JSONObject first = null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (!o.optBoolean("enable", true)) continue;
            if (first == null) first = o;
            if (o.optString("provider_source_id", "").length() > 0) return o;
        }
        return first;
    }

    /** 在 provider_sources[] 里按 id 找连接信息那一条。 */
    private static JSONObject findSource(JSONObject root, String sid) {
        JSONArray arr = root.optJSONArray("provider_sources");
        if (arr == null) return null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && sid.equals(o.optString("id"))) return o;
        }
        return null;
    }

    /**
     * 只取「当前生效的密钥」。
     *
     * 为什么要单独一个方法：界面上密钥框是密码型输入，真实密钥从不回填（只显示尾号），
     * 所以用户点「获取列表」时那个框通常是空的。以前这里直接把空串传给中转站，
     * 请求就不带 Authorization 头，中转站回 401，用户看到「密钥不对或没填」——
     * 可他明明早就保存过密钥（每次保存只改模型名也会把这条路径走一遍）。
     * 现在空串一律回落到配置文件里那把，行为跟「留空表示不修改」的提示一致。
     */
    public static String storedKey(File filesDir) {
        try {
            File f = cfgFile(filesDir);
            if (!f.isFile()) return "";
            JSONObject root = new JSONObject(readText(f));
            JSONObject p = pickProvider(root);
            if (p == null) return "";
            String key = "";
            String srcId = p.optString("provider_source_id", "");
            JSONObject src = (srcId.length() == 0) ? null : findSource(root, srcId);
            if (src != null) key = firstKey(src);
            String pFlatKey = firstKey(p);
            if (pFlatKey.length() > 0) key = pFlatKey;
            return (key == null) ? "" : key.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** key 在新结构里是数组（支持多把轮询），老结构也可能是字符串。 */
    private static String firstKey(JSONObject o) {
        JSONArray ks = o.optJSONArray("key");
        if (ks != null && ks.length() > 0) return ks.optString(0, "");
        String sk = o.optString("key", "");
        return sk;
    }

    /** 从 api_base 里取主机名，给「中转站」那一行做短标签用。 */
    static String hostOf(String base) {
        try {
            String b = (base == null) ? "" : base.trim();
            int i = b.indexOf("://");
            if (i >= 0) b = b.substring(i + 3);
            int j = b.indexOf('/');
            if (j >= 0) b = b.substring(0, j);
            return b;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 密码只以哈希形式存在，改密后要能判断出来，所以这里只需要 MD5。 */
    static String md5(String s) {
        try {
            java.security.MessageDigest d = java.security.MessageDigest.getInstance("MD5");
            byte[] b = d.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < b.length; i++) {
                int v = b[i] & 0xff;
                if (v < 16) sb.append('0');
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
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
            isNew = true;
        }
        p.put("enable", true);
        p.put("model", m);
        String k = (key == null) ? "" : key.trim();

        /*
         * 两代结构都要写对：
         *  - AstrBot 4.29（config_version 5）已经有 provider_sources[]：连接信息必须写进
         *    对应的 source，provider 里只留 provider_source_id。只写扁平的 api_base/key 的话，
         *    AstrBot 启动时会把它们迁移成一条新 source，但下次我们自己再读又读不到 —— 表现为
         *    「明明保存成功了，再打开还是没配置」。
         *  - 老版本没有 provider_sources：照旧把扁平字段写在 provider 上。
         */
        JSONArray sources = root.optJSONArray("provider_sources");
        if (sources != null) {
            String sid = p.optString("provider_source_id", "");
            if (sid.length() == 0) sid = PROVIDER_ID + "_source";
            p.put("provider_source_id", sid);
            JSONObject src = findSource(root, sid);
            if (src == null) {
                src = new JSONObject();
                src.put("id", sid);
                src.put("provider", "openai");
                src.put("type", "openai_chat_completion");
                src.put("provider_type", "chat_completion");
                src.put("proxy", "");
                src.put("custom_headers", new JSONObject());
                sources.put(src);
            }
            src.put("api_base", b);
            if (!src.has("timeout")) src.put("timeout", 120);
            if (k.length() > 0) {
                JSONArray keys = new JSONArray();
                keys.put(k);
                src.put("key", keys);
            } else if (firstKey(src).length() == 0) {
                src.put("key", new JSONArray());
            }
            root.put("provider_sources", sources);
            if (!p.has("modalities")) p.put("modalities", new JSONArray());
            if (!p.has("custom_extra_body")) p.put("custom_extra_body", new JSONObject());
        } else {
            p.put("provider", "openai");
            p.put("type", "openai_chat_completion");
            p.put("provider_type", "chat_completion");
            p.put("api_base", b);
            p.put("timeout", 120);
            if (!p.has("proxy")) p.put("proxy", "");
            if (!p.has("custom_headers")) p.put("custom_headers", new JSONObject());
            if (k.length() > 0) {
                JSONArray keys = new JSONArray();
                keys.put(k);
                p.put("key", keys);
            } else if (p.optJSONArray("key") == null) {
                p.put("key", new JSONArray());
            }
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
        // 同 StateIO：先试原子覆盖式 rename，只有在文件系统不支持时才先删后改。
        // 原写法每次都在「删掉旧配置、还没换上新的」之间留一个空窗，此时被系统杀掉
        // 就会留下一个没有 cmd_config.json 的 AstrBot，启动直接回到出厂配置。
        if (tmp.renameTo(f)) return;
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
