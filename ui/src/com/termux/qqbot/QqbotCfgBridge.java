package com.termux.app;

import android.os.Environment;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 「配置即代码」那条线的原生侧：window.AndroidCfg（1.3.5-beta.7）。
 *
 * 职责只有四件事，全部围绕 AstrBot 的三份真身文件：
 *   cmd_config.json —— 核心配置（provider / dashboard / 平台 / 人格选择…）
 *   state/prefs.json —— 原生层自己的小偏好（系统提示词 sysPrompt 就住在这里）
 *   bots.json —— 实例配置（bots[] 的 id / napcatPort / onebotPort、顶层 astrbotPort）
 *
 *   1. 备份：三份文件打成一个 JSON 容器写进 state/cfg-bak/cfg-<时间>.json，只留最近 KEEP 份；
 *   2. 校验 / 预览 / 导入：validate 先验结构，importPreview 只读不写盘，importText 才落盘；
 *   3. 恢复：备份包与旧版「裸 cmd_config.json」两种格式都认，恢复前先备份当前、失败回滚；
 *   4. 日志导出：把 .qqbot/logs 与 state/*.json 打成一个 zip 放到「下载」目录。
 *
 * 三条纪律：
 *   - 桥方法全部同步返回 JSON 字符串，**绝不抛异常**（WebView 里抛一次就整页白屏）；
 *   - 任何覆盖写之前先备份，写盘一律「临时文件 + rename」，失败从 .beta7bak 回滚；
 *   - 只碰白名单里的三个文件（state/cmd_config.json、state/prefs.json、bots.json），
 *     路径按等值匹配，不接受 ../、绝对路径或别处的目录 —— QQ 登录数据（data/、napcat）
 *     永远走不到这条路上。
 *
 * d8 处理匿名内部类会崩：本文件不出现匿名类、不用 lambda。
 */
public class QqbotCfgBridge {

    /** 备份保留份数（需求：最近 N 次）。 */
    private static final int KEEP = 5;
    /** 日志包上限：超过就停止追加，避免把 600 MB 的镜像目录卷进来。 */
    private static final long LOG_CAP = 20L * 1024L * 1024L;
    /** 备份目录名（在 state/ 下）。 */
    private static final String BAK_DIR = "cfg-bak";
    /** 「下载」目录下的子目录名。 */
    private static final String EXPORT_DIR = "AstrBot烛版";

    /* ---------------- 备份包（manifest）的格式常量 ---------------- */

    /** 容器格式名：validate 见到它才按新格式解析，没有这个键的一律走旧格式分支。 */
    private static final String FORMAT = "astrbot-candle-cfg";
    /** 容器格式版本：将来改结构就 +1，老版本 app 见到不认识的版本会拒绝。 */
    private static final int FORMAT_VER = 1;
    /** VERSION 文件读不到时写进 manifest 的兜底版本号。 */
    private static final String APP_FALLBACK = "1.3.5-beta.7";
    /** 单文件上限 512 KB：三份配置加起来几百 KB，超过这个数说明不是配置。 */
    private static final long MAX_FILE = 512L * 1024L;
    /** 容器里最多放 6 个文件（当前白名单只有 3 个，留出扩展余量）。 */
    private static final int MAX_FILES = 6;
    /** 整段文本（用户贴进来的备份包）上限 8 MB。 */
    private static final long MAX_TEXT = 8L * 1024L * 1024L;
    /** 每个目标文件写盘前的单份备份后缀（回滚就靠它）。 */
    private static final String BAK_SUFFIX = ".beta7bak";
    /** 预览里每类差异最多列多少条。 */
    private static final int PREVIEW_MAX = 20;

    /** 白名单里的逻辑路径（= 备份包 files 对象的键，也是 .qqbot 下的相对路径）。 */
    private static final String CMD_CFG = "state/cmd_config.json";
    private static final String PREFS = "state/prefs.json";
    private static final String BOTS = "bots.json";
    private static final String[] WHITELIST = new String[] { CMD_CFG, PREFS, BOTS };

    /** cmd_config.json 里认得的核心段：一份裸配置至少要含其中一个才当它是真配置。 */
    private static final String[] CORE = new String[] { "provider", "provider_settings", "dashboard",
            "platform", "platform_settings", "agent_runner", "config_version", "wake_prefix" };

    /** 敏感键名：返回给前端的 JSON 里，命中的值一律换成 ***（落盘保留原值）。 */
    private static final Pattern SENSITIVE = Pattern.compile("(?i)(token|password|secret|apikey|api_key|uin|qq)");

    private final File filesDir;

    /** 上一次打备份包时写入的文件元信息（path/bytes/sha256），backupNow 的返回值要用。 */
    private JSONArray lastFiles = new JSONArray();
    /** 上一次打备份包时跳过的文件（path/reason）。 */
    private JSONArray lastSkipped = new JSONArray();
    /** 上一次打包已写入的字节数（日志导出用，字段而非返回值为的是避免 d8 的装箱问题）。 */
    private long packedSoFar = 0L;

    public QqbotCfgBridge(QqbotActivity act) {
        this.filesDir = act.filesDirPub();
    }

    /** 自检 / 无界面场景用。 */
    public QqbotCfgBridge(File filesDir) {
        this.filesDir = filesDir;
    }

    /* ------------------------------ 状态 ------------------------------ */

    /**
     * 界面进页面时调一次：
     * {ok,cfgExists,size,mtime,ver,backups,lastBackup,name,promptLen,isDefaultPrompt}
     * 外加 1.3.5-beta.7 的 {format,formatVersion,keep,bakDir,app,count,latest}。
     * 老字段一个不动（前端 cfgPaint 仍在读），新字段只增不改。
     */
    @JavascriptInterface
    public String info() {
        try {
            JSONObject o = new JSONObject();
            File cfg = AstrCfg.cfgFile(filesDir);
            o.put("ok", true);
            o.put("cfgExists", cfg.isFile());
            o.put("cfgPath", cfg.getAbsolutePath());
            o.put("name", cfg.getName());
            o.put("size", cfg.isFile() ? cfg.length() : 0L);
            o.put("mtime", cfg.isFile() ? cfg.lastModified() : 0L);
            o.put("ver", cfg.isFile() ? cfgVersion(cfg) : 0);
            ArrayList<File> baks = backups();
            o.put("backups", baks.size());
            if (baks.size() > 0) {
                File b = baks.get(0);
                o.put("lastBackup", b.getName());
                o.put("lastBackupAt", fmt(b.lastModified()));
                o.put("lastBackupSize", b.length());
            }
            o.put("format", FORMAT);
            o.put("formatVersion", FORMAT_VER);
            o.put("keep", KEEP);
            o.put("bakDir", bakDir().getAbsolutePath());
            o.put("app", appVersionOr());
            o.put("count", baks.size());
            o.put("latest", baks.size() > 0 ? baks.get(0).getName() : "");
            String p = SysPrompt.get(filesDir);
            o.put("promptLen", p.length());
            o.put("isDefaultPrompt", SysPrompt.isDefault(filesDir));
            return o.toString();
        } catch (Throwable t) {
            return fail("读取配置状态失败：" + t);
        }
    }

    /* ------------------------------ 备份 ------------------------------ */

    /**
     * 立刻备份一次：{ok,name,size,at,count,format,formatVersion,app,files,skipped,msg}。
     * 备份内容从一个 cmd_config.json 升级成「一个 JSON 容器」（见 manifestText），
     * files 里是解析后的对象（不是字符串），checksums 是各段「紧凑序列化字节」的 sha256。
     */
    @JavascriptInterface
    public String backupNow() {
        try {
            if (!AstrCfg.cfgFile(filesDir).isFile()) {
                return fail("还没装好 AstrBot（找不到 cmd_config.json），没有可备份的配置");
            }
            File b = doBackup();
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("name", b.getName());
            o.put("size", b.length());
            o.put("at", fmt(b.lastModified()));
            o.put("count", backups().size());
            o.put("format", FORMAT);
            o.put("formatVersion", FORMAT_VER);
            o.put("app", appVersionOr());
            /* 只有元信息（路径 / 字节数 / 校验和），不含配置内容，也就没有要脱敏的值；
               万一将来往这里塞内容，记得先过 masked()。 */
            o.put("files", lastFiles);
            o.put("skipped", lastSkipped);
            o.put("msg", "已备份到应用私有目录，只保留最近 " + KEEP + " 份");
            return o.toString();
        } catch (Throwable t) {
            return fail("备份失败：" + t);
        }
    }

    /**
     * 备份列表（新 → 旧）：{ok,items:[{name,size,at,ver,legacy,format,formatVersion,app,files}],keep}。
     * ver 对旧格式是 cmd_config.json 的 config_version，对新容器取容器里那份的 config_version。
     */
    @JavascriptInterface
    public String listBackups() {
        try {
            ArrayList<File> list = backups();
            JSONArray arr = new JSONArray();
            for (int i = 0; i < list.size(); i++) {
                File f = list.get(i);
                JSONObject man = readManifest(f);
                JSONObject it = new JSONObject();
                it.put("name", f.getName());
                it.put("size", f.length());
                it.put("at", fmt(f.lastModified()));
                it.put("ver", verOf(f));
                it.put("legacy", man == null);
                it.put("format", man == null ? "" : FORMAT);
                it.put("formatVersion", man == null ? 0 : FORMAT_VER);
                it.put("app", man == null ? "" : man.optString("app", ""));
                it.put("files", (man == null) ? 1 : fileCount(man));
                arr.put(it);
            }
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("items", arr);
            o.put("keep", KEEP);
            o.put("format", FORMAT);
            o.put("formatVersion", FORMAT_VER);
            o.put("bakDir", bakDir().getAbsolutePath());
            return o.toString();
        } catch (Throwable t) {
            return fail("读取备份列表失败：" + t);
        }
    }

    /**
     * 回滚到某个备份：{ok,msg,files,written,legacy,warnings,ver,size}。
     * 新容器：先 backupCurrent() 留一份现在的，再把白名单里的文件逐个原子写回，
     * 任何一个失败就从 .beta7bak 全量回滚；旧格式（裸 cmd_config.json）：按老行为只恢复核心配置。
     * 两种情况都不会碰 .qqbot/data 与 napcat 登录数据。
     */
    @JavascriptInterface
    public String restore(String name) {
        try {
            File src = checkedBackup(name);
            if (src == null) {
                return fail("备份不存在或文件名不合法");
            }
            String s = readText(src, MAX_TEXT);
            if (s == null) {
                return fail("备份文件读不出来：" + src.getName());
            }
            JSONObject v = validateObj(s);
            if (!v.optBoolean("ok")) {
                return failWithProblems("这个备份包校验没通过，没有恢复：" + src.getName() + "；",
                        v.optJSONArray("problems"));
            }
            JSONObject root = new JSONObject(s);
            if (v.optBoolean("legacy")) {
                /* 旧格式：整份内容就是 cmd_config.json。先备份当前，再原子写回核心配置。 */
                File cfg = AstrCfg.cfgFile(filesDir);
                if (cfg.isFile()) {
                    backupCurrent();
                }
                boolean had = cfg.isFile();
                if (had) {
                    copy(cfg, bakOf(cfg));
                }
                try {
                    atomicWrite(cfg, root.toString(2));
                } catch (Throwable t) {
                    if (had) {
                        try { copy(bakOf(cfg), cfg); } catch (Throwable ignored) {}
                    } else {
                        try { cfg.delete(); } catch (Throwable ignored) {}
                    }
                    throw new Exception("写回 cmd_config.json 失败，已回滚：" + msgOf(t));
                }
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("legacy", true);
                o.put("files", single(CMD_CFG));
                o.put("written", 1);
                o.put("warnings", v.optJSONArray("warnings"));
                o.put("msg", "已恢复 " + src.getName() + "（旧格式，只恢复 cmd_config.json），"
                        + "重启 AstrBot 后生效；当前的配置也已备份");
                return o.toString();
            }
            JSONObject body = root.optJSONObject("files");
            ArrayList<String> paths = whitelistedPathsIn(body);
            if (paths.size() == 0) {
                return fail("备份包里没有可恢复的文件");
            }
            JSONObject o = applyBody(body, paths, v.optJSONArray("warnings"), "已恢复 " + src.getName());
            o.put("msg", "已恢复 " + src.getName() + "：" + joinOf(paths)
                    + "，重启 AstrBot 后生效；当前的配置也已备份");
            return o.toString();
        } catch (Throwable t) {
            return fail("恢复失败：" + t);
        }
    }

    /* ------------------------------ 校验 ------------------------------ */

    /**
     * 校验一段备份文本（不写盘）：
     * {ok,legacy,format,formatVersion,app,createdAtText,files:[{path,bytes,sha256Ok,ok}],problems,warnings}。
     *
     * 致命（ok=false）：不是 JSON 对象 / format 不对 / formatVersion 不是 1 /
     *   files 缺失或为空或超过 6 个 / 键不在白名单（含 ../、绝对路径、别的目录）/
     *   某段值不是 JSON 对象 / 单文件超过 512 KB。
     * 非致命（ok 仍为 true，只进 problems + warnings）：checksums 缺失或 sha256 对不上。
     * 没有 format 键的走 legacy 分支：按裸 cmd_config.json 认（要有 AstrBot 的核心段）。
     */
    @JavascriptInterface
    public String validate(String text) {
        try {
            return validateObj(text).toString();
        } catch (Throwable t) {
            return fail("校验失败：" + t);
        }
    }

    /**
     * 导入前预览：校验结果 + 每个文件与当前文件的差异（顶层键增删、值变更计数），只读不写盘。
     * 返回 {ok,legacy,format,formatVersion,app,createdAtText,files,problems,warnings,
     *       diffs:[{path,exists,added,removed,changed,total,entries:[{key,kind,from,to}],more,bytes}],
     *       changes,truncated,msg}。
     * entries 里最多列 20 条（所有文件合计），值按 SENSITIVE 规则脱敏。
     */
    @JavascriptInterface
    public String importPreview(String text) {
        try {
            String s = (text == null) ? "" : text.trim();
            JSONObject v = validateObj(s);
            JSONObject out = new JSONObject();
            out.put("ok", v.optBoolean("ok"));
            out.put("legacy", v.optBoolean("legacy"));
            out.put("format", v.optString("format", ""));
            out.put("formatVersion", v.optInt("formatVersion", 0));
            out.put("app", v.optString("app", ""));
            out.put("createdAtText", v.optString("createdAtText", ""));
            out.put("files", v.optJSONArray("files"));
            out.put("problems", v.optJSONArray("problems"));
            out.put("warnings", v.optJSONArray("warnings"));
            JSONArray diffs = new JSONArray();
            int budget = PREVIEW_MAX;
            int total = 0;
            int files = 0;
            if (v.optBoolean("ok")) {
                JSONObject root = new JSONObject(s);
                if (v.optBoolean("legacy")) {
                    JSONObject d = diffOf(CMD_CFG, root, budget);
                    budget -= d.optInt("entryCount", 0);
                    total += d.optInt("total", 0);
                    diffs.put(d);
                    files = 1;
                } else {
                    JSONObject body = root.optJSONObject("files");
                    ArrayList<String> paths = whitelistedPathsIn(body);
                    for (int i = 0; i < paths.size(); i++) {
                        JSONObject seg = body.optJSONObject(paths.get(i));
                        if (seg == null) continue;
                        JSONObject d = diffOf(paths.get(i), seg, budget);
                        budget -= d.optInt("entryCount", 0);
                        total += d.optInt("total", 0);
                        diffs.put(d);
                        files++;
                    }
                }
            }
            out.put("diffs", diffs);
            out.put("changes", total);
            out.put("truncated", total > PREVIEW_MAX);
            if (!v.optBoolean("ok")) {
                out.put("msg", "校验没通过：" + firstOf(v.optJSONArray("problems"), "见 problems"));
            } else if (v.optBoolean("legacy")) {
                out.put("msg", "这是旧格式（裸 cmd_config.json），导入只会覆盖核心配置，共 " + total + " 处差异");
            } else {
                out.put("msg", "备份包可用，" + files + " 个文件、共 " + total + " 处差异，点「导入」才写盘");
            }
            return out.toString();
        } catch (Throwable t) {
            return fail("预览失败：" + t);
        }
    }

    /* --------------------------- 导入 / 导出 --------------------------- */

    /** 导出配置到「下载」目录：{ok,path,name,size,msg}。导出前先留一份备份（永远导出原始字节）。 */
    @JavascriptInterface
    public String exportCfg() {
        try {
            File cfg = AstrCfg.cfgFile(filesDir);
            if (!cfg.isFile()) {
                return fail("还没装好 AstrBot（找不到 cmd_config.json）");
            }
            doBackup();
            File dir = exportDir();
            File out = new File(dir, "cmd_config-" + stamp() + ".json");
            copy(cfg, out);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("path", out.getAbsolutePath());
            o.put("name", out.getName());
            o.put("size", out.length());
            o.put("dir", dir.getAbsolutePath());
            o.put("msg", "已导出，可在文件管理器的「下载/" + EXPORT_DIR + "」里找到");
            return o.toString();
        } catch (Throwable t) {
            return fail("导出失败：" + t);
        }
    }

    /**
     * 导入文本：{ok,msg,files,written,ver,size,legacy,warnings,problems}。
     *
     * 顺序固定：先 validate（致命问题直接拒绝，返回 ok:false + problems），
     * 非致命（sha256 对不上 / 没有 checksums）允许继续但记 warning；
     * 写盘前 backupCurrent() 留一份当前的，再给每个目标留 <name>.beta7bak，
     * 「临时文件 + rename」写入；任一文件失败就把已经写过的全从 .beta7bak 还原。
     * 旧格式（裸 cmd_config.json）沿用老行为：只覆盖 cmd_config.json。
     */
    @JavascriptInterface
    public String importText(String text) {
        try {
            String s = (text == null) ? "" : text.trim();
            JSONObject v = validateObj(s);
            if (!v.optBoolean("ok")) {
                JSONObject bad = new JSONObject();
                bad.put("ok", false);
                bad.put("legacy", v.optBoolean("legacy"));
                bad.put("format", v.optString("format", ""));
                bad.put("problems", v.optJSONArray("problems"));
                bad.put("warnings", v.optJSONArray("warnings"));
                bad.put("files", v.optJSONArray("files"));
                bad.put("msg", "校验没通过，没有写入：" + firstOf(v.optJSONArray("problems"), "见 problems"));
                return bad.toString();
            }
            JSONArray warns = v.optJSONArray("warnings");
            JSONObject root = new JSONObject(s);
            if (v.optBoolean("legacy")) {
                return importLegacy(root, warns).toString();
            }
            JSONObject body = root.optJSONObject("files");
            ArrayList<String> paths = whitelistedPathsIn(body);
            if (paths.size() == 0) {
                return fail("备份包里没有可写入的文件");
            }
            JSONObject o = applyBody(body, paths, warns, "已导入");
            o.put("msg", "已导入 " + paths.size() + " 个文件（" + joinOf(paths)
                    + "），重启 AstrBot 后生效；旧配置已自动备份");
            return o.toString();
        } catch (Throwable t) {
            return fail("导入失败：" + t);
        }
    }

    /* ------------------------------ 日志 ------------------------------ */

    /**
     * 导出运行日志：{ok,path,size,files,msg}。
     * 只收文本：.qqbot/logs/ 下的日志 + state/ 下的 JSON（含自检、体检、重连状态），
     * 超过 LOG_CAP 就停手并在 msg 里说明，不把镜像和数据库卷进来。
     */
    @JavascriptInterface
    public String exportLogs() {
        ZipOutputStream zos = null;
        InputStream in = null;
        try {
            File dir = exportDir();
            File out = new File(dir, "logs-" + stamp() + ".zip");
            FileOutputStream fos = new FileOutputStream(out);
            zos = new ZipOutputStream(fos);
            int n = 0;
            n += addTree(zos, new File(QqbotShell.qqbotDir(filesDir), "logs"), "logs/");
            JSONObject meta = new JSONObject();
            meta.put("version", appVersion());
            meta.put("at", fmt(System.currentTimeMillis()));
            meta.put("cfg", AstrCfg.cfgFile(filesDir).getAbsolutePath());
            meta.put("cfgExists", AstrCfg.cfgFile(filesDir).isFile());
            meta.put("promptLen", SysPrompt.get(filesDir).length());
            zipText(zos, "meta.json", meta.toString(2));
            addSmallJson(zos, QqbotShell.stateDir(filesDir));
            zos.finish();
            zos.close();
            zos = null;
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("path", out.getAbsolutePath());
            o.put("name", out.getName());
            o.put("size", out.length());
            o.put("files", n);
            o.put("truncated", packedSoFar >= LOG_CAP);
            o.put("msg", packedSoFar >= LOG_CAP
                    ? "日志已导出（超过 20 MB 后停止追加）"
                    : "日志已导出，可在「下载/" + EXPORT_DIR + "」里找到，直接把这个 zip 发出去即可");
            return o.toString();
        } catch (Throwable t) {
            return fail("导出日志失败：" + t);
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            try { if (zos != null) zos.close(); } catch (Throwable ignored) {}
        }
    }

    /* --------------------------- 系统提示词 --------------------------- */

    /** {ok,text,isDefault,defLen,max}。 */
    @JavascriptInterface
    public String getPrompt() {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("text", SysPrompt.get(filesDir));
            o.put("isDefault", SysPrompt.isDefault(filesDir));
            o.put("defLen", AstrCfg.DEFAULT_PERSONA.length());
            o.put("max", SysPrompt.MAX);
            return o.toString();
        } catch (Throwable t) {
            return fail("读取系统提示词失败：" + t);
        }
    }

    /** 保存提示词：{ok,msg,len,applied}。applied=true 表示已同步进 data_v4.db（立刻生效）。 */
    @JavascriptInterface
    public String setPrompt(String text) {
        try {
            String s = (text == null) ? "" : text.trim();
            if (s.length() == 0) {
                return fail("提示词不能为空；要恢复出厂设置请点「恢复默认」");
            }
            if (s.length() > SysPrompt.MAX) {
                return fail("太长了（上限 " + SysPrompt.MAX + " 字），先精简一下");
            }
            if (!SysPrompt.set(filesDir, s)) {
                return fail("写不进 state/prefs.json，检查一下存储空间");
            }
            boolean applied = SysPrompt.pushToDb(filesDir, s);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("len", s.length());
            o.put("applied", applied);
            o.put("msg", applied
                    ? "已保存，人格提示词已同步进数据库"
                    : "已保存（沙盒运行中数据库被占用），下次重启 AstrBot 后生效");
            return o.toString();
        } catch (Throwable t) {
            return fail("保存系统提示词失败：" + t);
        }
    }

    /** 恢复出厂的「烛心」人格：{ok,msg,applied}。 */
    @JavascriptInterface
    public String resetPrompt() {
        try {
            SysPrompt.reset(filesDir);
            boolean applied = SysPrompt.pushToDb(filesDir, AstrCfg.DEFAULT_PERSONA);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("applied", applied);
            o.put("msg", applied ? "已恢复默认提示词" : "已恢复默认（数据库被占用，重启 AstrBot 后生效）");
            return o.toString();
        } catch (Throwable t) {
            return fail("恢复默认失败：" + t);
        }
    }

    /* ======================= 内部：备份包（manifest） ======================= */

    /**
     * 打一个备份包写进 cfg-bak/cfg-<时间>.json，顺手删掉超出 KEEP 的老备份。
     *
     * 包结构：
     * {
     *   "format":"astrbot-candle-cfg", "formatVersion":1, "app":"1.3.5-beta.7",
     *   "createdAt": 1700000000, "createdAtText":"2026-11-04 16:29:11",
     *   "files": { "state/cmd_config.json": {...}, "state/prefs.json": {...}, "bots.json": {...} },
     *   "checksums": { "state/cmd_config.json":"<sha256 hex>", ... },
     *   "skipped": [ { "path":"...", "reason":"文件不存在" } ]
     * }
     * files 里放的是解析后的 JSON 对象（不是字符串），所以能结构校验。
     */
    private File doBackup() throws Exception {
        File dir = bakDir();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new Exception("建不了备份目录：" + dir);
        }
        String text = manifestText();
        File out = new File(dir, "cfg-" + stamp() + ".json");
        atomicWrite(out, text);
        pruneBackups();
        return out;
    }

    /** 现有单份备份的入口（恢复 / 导入前调）：失败不阻断主流程，返回 null。 */
    private File backupCurrent() {
        try {
            return doBackup();
        } catch (Throwable t) {
            return null;
        }
    }

    private String manifestText() throws Exception {
        JSONObject files = new JSONObject();
        JSONObject sums = new JSONObject();
        JSONArray metas = new JSONArray();
        JSONArray skips = new JSONArray();
        int n = 0;
        for (int i = 0; i < WHITELIST.length; i++) {
            String path = WHITELIST[i];
            File f = existingFile(path);
            if (f == null) {
                skips.put(skipOf(path, "文件不存在"));
                continue;
            }
            long len = f.length();
            if (len > MAX_FILE) {
                skips.put(skipOf(path, "超过单文件 512 KB 上限（" + len + " B）"));
                continue;
            }
            String s = readText(f, MAX_FILE);
            if (s == null) {
                skips.put(skipOf(path, "读不出来"));
                continue;
            }
            JSONObject obj = null;
            try {
                obj = new JSONObject(s);
            } catch (Throwable bad) {
                obj = null;
            }
            if (obj == null) {
                skips.put(skipOf(path, "不是合法 JSON 对象"));
                continue;
            }
            if (n >= MAX_FILES) {
                skips.put(skipOf(path, "超过 " + MAX_FILES + " 个文件的上限"));
                continue;
            }
            files.put(path, obj);
            String canon = obj.toString();
            byte[] bytes = canon.getBytes("UTF-8");
            sums.put(path, sha256(bytes));
            JSONObject meta = new JSONObject();
            meta.put("path", path);
            meta.put("bytes", bytes.length);
            meta.put("sha256", sums.optString(path, ""));
            metas.put(meta);
            n++;
        }
        if (n == 0) {
            throw new Exception("没有可备份的配置文件");
        }
        long now = System.currentTimeMillis();
        JSONObject root = new JSONObject();
        root.put("format", FORMAT);
        root.put("formatVersion", FORMAT_VER);
        root.put("app", appVersionOr());
        root.put("createdAt", now / 1000L);
        root.put("createdAtText", fmtSec(now));
        root.put("files", files);
        root.put("checksums", sums);
        root.put("skipped", skips);
        lastFiles = metas;
        lastSkipped = skips;
        return root.toString(2);
    }

    private static JSONObject skipOf(String path, String reason) throws Exception {
        JSONObject o = new JSONObject();
        o.put("path", path);
        o.put("reason", reason);
        return o;
    }

    /** 文件是不是一个合法的（新格式）备份包；不是就返回 null，调用方按旧格式处理。 */
    private static JSONObject readManifest(File f) {
        String s = readText(f, MAX_TEXT);
        if (s == null) {
            return null;
        }
        try {
            JSONObject o = new JSONObject(s);
            if (!FORMAT.equals(o.optString("format", ""))) return null;
            if (o.optJSONObject("files") == null) return null;
            return o;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int fileCount(JSONObject man) {
        JSONObject body = (man == null) ? null : man.optJSONObject("files");
        return (body == null) ? 0 : body.length();
    }

    /** 备份包里 cmd_config 段的 config_version；旧格式就是裸配置自己的。 */
    private static int verOf(File f) {
        String s = readText(f, MAX_TEXT);
        if (s == null) {
            return 0;
        }
        try {
            JSONObject root = new JSONObject(s);
            JSONObject body = root.optJSONObject("files");
            if (FORMAT.equals(root.optString("format", "")) && body != null) {
                JSONObject cc = body.optJSONObject(CMD_CFG);
                return (cc == null) ? 0 : cc.optInt("config_version", 0);
            }
            return root.optInt("config_version", 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    private void pruneBackups() {
        ArrayList<File> list = backups();
        for (int i = KEEP; i < list.size(); i++) {
            try {
                list.get(i).delete();
            } catch (Throwable ignored) {
            }
        }
    }

    /* ======================= 内部：校验 ======================= */

    private JSONObject validateObj(String text) throws Exception {
        JSONArray problems = new JSONArray();
        JSONArray warns = new JSONArray();
        JSONArray files = new JSONArray();
        JSONObject out = new JSONObject();
        boolean fatal = false;
        boolean legacy = false;
        String format = "";
        int fv = 0;
        String app = "";
        String createdAtText = "";
        String s = (text == null) ? "" : text.trim();
        JSONObject root = null;
        if (s.length() == 0) {
            fatal = true;
            addProblem(problems, "内容为空，先粘贴一份备份包或 cmd_config.json");
        } else if (s.length() > MAX_TEXT) {
            fatal = true;
            addProblem(problems, "内容超过 8 MB，不像是本应用导出的配置备份包");
        } else {
            try {
                root = new JSONObject(s);
            } catch (Throwable bad) {
                fatal = true;
                addProblem(problems, "不是合法 JSON：" + msgOf(bad));
            }
        }
        if (root != null) {
            if (root.has("format")) {
                format = root.optString("format", "");
                fv = root.optInt("formatVersion", 0);
                app = root.optString("app", "");
                createdAtText = root.optString("createdAtText", "");
                if (!FORMAT.equals(format)) {
                    fatal = true;
                    addProblem(problems, "format 不是 " + FORMAT + "（读到：" + format + "）");
                }
                if (fv != FORMAT_VER) {
                    fatal = true;
                    addProblem(problems, "不支持的 formatVersion：" + fv + "（本版本只认 " + FORMAT_VER + "）");
                }
                JSONObject body = root.optJSONObject("files");
                if (body == null) {
                    fatal = true;
                    addProblem(problems, "缺少 files 对象，备份包里没有文件清单");
                } else if (body.length() == 0) {
                    fatal = true;
                    addProblem(problems, "files 是空的，没有可恢复的文件");
                } else if (body.length() > MAX_FILES) {
                    fatal = true;
                    addProblem(problems, "文件数 " + body.length() + " 超过上限 " + MAX_FILES);
                }
                JSONObject sums = root.optJSONObject("checksums");
                if (body != null) {
                    ArrayList<String> keys = sortedKeys(body);
                    for (int i = 0; i < keys.size(); i++) {
                        String path = keys.get(i);
                        boolean allowed = isAllowed(path);
                        if (!allowed) {
                            fatal = true;
                            addProblem(problems, "拒绝白名单之外的文件：" + path
                                    + "（只接受 state/cmd_config.json、state/prefs.json、bots.json；"
                                    + "不接受 ../、绝对路径或别的目录）");
                        }
                        Object raw = body.opt(path);
                        boolean isObj = raw instanceof JSONObject;
                        if (!isObj) {
                            fatal = true;
                            addProblem(problems, "文件内容必须是 JSON 对象：" + path);
                        }
                        String canon = isObj ? ((JSONObject) raw).toString() : "";
                        long bytes = isObj ? (long) canon.getBytes("UTF-8").length : 0L;
                        if (isObj && bytes > MAX_FILE) {
                            fatal = true;
                            addProblem(problems, path + " 超过单文件 512 KB 上限（" + bytes + " B）");
                        }
                        boolean shaOk = false;
                        if (isObj) {
                            String want = (sums == null) ? "" : sums.optString(path, "");
                            if (want.length() == 0) {
                                warns.put(path + " 没有 checksums，完整性未校验");
                                addProblem(problems, path + " 没有 checksums，完整性未校验（仍可导入）");
                            } else if (sha256(canon.getBytes("UTF-8")).equalsIgnoreCase(want)) {
                                shaOk = true;
                            } else {
                                warns.put(path + " 的 sha256 与 checksums 不一致");
                                addProblem(problems, path + " 的 sha256 与 checksums 不一致"
                                        + "（备份包可能被改过，仍可导入）");
                            }
                        }
                        JSONObject item = new JSONObject();
                        item.put("path", path);
                        item.put("bytes", bytes);
                        item.put("sha256Ok", shaOk);
                        item.put("ok", allowed && isObj);
                        files.put(item);
                    }
                }
            } else {
                legacy = true;
                if (root.length() == 0) {
                    fatal = true;
                    addProblem(problems, "JSON 是空的，没东西可导入");
                } else if (!hasCoreSection(root)) {
                    fatal = true;
                    addProblem(problems, "这份 JSON 里没有 AstrBot 的配置段"
                            + "（provider / dashboard / platform …），已拒绝写入");
                } else {
                    JSONObject item = new JSONObject();
                    item.put("path", CMD_CFG);
                    item.put("bytes", (long) s.getBytes("UTF-8").length);
                    item.put("sha256Ok", true);
                    item.put("ok", true);
                    files.put(item);
                    warns.put("识别为旧格式（裸 cmd_config.json），只会恢复核心配置");
                }
            }
        }
        out.put("ok", !fatal);
        out.put("legacy", legacy);
        out.put("format", legacy ? "" : format);
        out.put("formatVersion", fv);
        out.put("app", app);
        out.put("createdAtText", createdAtText);
        out.put("files", files);
        out.put("problems", problems);
        out.put("warnings", warns);
        return out;
    }

    /** 一个文件与当前磁盘上那份的差异（顶层键增删 + 值变更计数），不写盘。 */
    private JSONObject diffOf(String path, JSONObject inc, int budget) throws Exception {
        JSONObject out = new JSONObject();
        File f = existingFile(path);
        out.put("path", path);
        out.put("exists", f != null && f.isFile());
        JSONObject cur = null;
        if (f != null && f.isFile()) {
            String s = readText(f, MAX_FILE);
            if (s != null) {
                try {
                    cur = new JSONObject(s);
                } catch (Throwable bad) {
                    cur = null;
                }
            }
        }
        if (cur == null) {
            cur = new JSONObject();
        }
        JSONArray added = new JSONArray();
        JSONArray removed = new JSONArray();
        JSONArray entries = new JSONArray();
        int changed = 0;
        int used = 0;
        int cap = (budget < 0) ? 0 : budget;
        ArrayList<String> incKeys = sortedKeys(inc);
        ArrayList<String> curKeys = sortedKeys(cur);
        for (int i = 0; i < incKeys.size(); i++) {
            String k = incKeys.get(i);
            if (!cur.has(k)) {
                added.put(k);
                if (used < cap) {
                    entries.put(entryOf(k, "add", null, inc.opt(k)));
                    used++;
                }
            } else if (!sameValue(cur.opt(k), inc.opt(k))) {
                changed++;
                if (used < cap) {
                    entries.put(entryOf(k, "change", cur.opt(k), inc.opt(k)));
                    used++;
                }
            }
        }
        for (int i = 0; i < curKeys.size(); i++) {
            String k = curKeys.get(i);
            if (!inc.has(k)) {
                removed.put(k);
                if (used < cap) {
                    entries.put(entryOf(k, "del", cur.opt(k), null));
                    used++;
                }
            }
        }
        int total = added.length() + removed.length() + changed;
        out.put("added", added);
        out.put("removed", removed);
        out.put("changed", changed);
        out.put("total", total);
        out.put("entries", entries);
        out.put("entryCount", used);
        out.put("more", Math.max(0, total - used));
        out.put("bytes", (long) inc.toString().getBytes("UTF-8").length);
        return out;
    }

    private static JSONObject entryOf(String key, String kind, Object from, Object to) throws Exception {
        JSONObject e = new JSONObject();
        e.put("key", key);
        e.put("kind", kind);
        if (from != null) {
            e.put("from", maskedValue(from, key));
        }
        if (to != null) {
            e.put("to", maskedValue(to, key));
        }
        return e;
    }

    /* ======================= 内部：脱敏 ======================= */

    /**
     * 只作用于「返回给前端的字符串」：键名命中 SENSITIVE 的值换成 ***。
     * 落盘的备份包、导出的文件都保留原值 —— 那里要能真的恢复回去。
     */
    private static JSONObject masked(JSONObject o) {
        JSONObject out = new JSONObject();
        ArrayList<String> keys = sortedKeys(o);
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(i);
            try {
                out.put(k, maskedValue(o.opt(k), k));
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static JSONArray maskedArray(JSONArray arr) {
        JSONArray out = new JSONArray();
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            try {
                out.put(maskedValue(arr.opt(i), null));
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static Object maskedValue(Object v, String key) {
        if (key != null && SENSITIVE.matcher(key).find()) {
            return "***";
        }
        if (v == null || v == JSONObject.NULL) {
            return "";
        }
        if (v instanceof JSONObject) {
            return masked((JSONObject) v);
        }
        if (v instanceof JSONArray) {
            return maskedArray((JSONArray) v);
        }
        if (v instanceof String) {
            return shorten((String) v);
        }
        return v;
    }

    private static String shorten(String s) {
        if (s == null) {
            return "";
        }
        if (s.length() <= 120) {
            return s;
        }
        return s.substring(0, 120) + "…（共 " + s.length() + " 字）";
    }

    /* ======================= 内部：写盘与回滚 ======================= */

    /**
     * 把备份包里的文件逐个原子写进目标位置：
     * 先 backupCurrent() 留一份当前的，再给每个目标留 <name>.beta7bak，
     * 然后「临时文件 + rename」写入；任何一个写失败就把已经写过的全部从 .beta7bak 还原
     * （原本不存在的直接删掉）。只服务白名单里的三个文件。
     */
    private JSONObject applyBody(JSONObject body, ArrayList<String> paths, JSONArray warns, String head)
            throws Exception {
        backupCurrent();
        ArrayList<File> written = new ArrayList<File>();
        ArrayList<Boolean> existed = new ArrayList<Boolean>();
        int ver = 0;
        long size = 0L;
        for (int i = 0; i < paths.size(); i++) {
            String path = paths.get(i);
            File dst = writableTarget(path);
            JSONObject val = (dst == null) ? null : body.optJSONObject(path);
            if (dst == null || val == null) {
                rollback(written, existed);
                throw new Exception("备份项不可写：" + path);
            }
            File parent = dst.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                rollback(written, existed);
                throw new Exception("建不了目录：" + parent);
            }
            boolean had = dst.isFile();
            if (had) {
                copy(dst, bakOf(dst));
            }
            /* 先记账再写：写一半失败时这次的目标也要能回滚。 */
            written.add(dst);
            existed.add(Boolean.valueOf(had));
            try {
                atomicWrite(dst, val.toString(2));
            } catch (Throwable t) {
                rollback(written, existed);
                throw new Exception("写入 " + path + " 失败，已回滚：" + msgOf(t));
            }
            if (CMD_CFG.equals(path)) {
                ver = val.optInt("config_version", 0);
            }
            size += dst.length();
        }
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("legacy", false);
        o.put("files", arrOf(paths));
        o.put("written", paths.size());
        o.put("ver", ver);
        o.put("size", size);
        o.put("warnings", warns);
        o.put("msg", head + "：" + joinOf(paths) + "；重启 AstrBot 后生效，导入前的配置已备份到 cfg-bak");
        return o;
    }

    /** 旧格式导入：只覆盖 cmd_config.json，与 beta.6 的老行为一致。 */
    private JSONObject importLegacy(JSONObject root, JSONArray warns) throws Exception {
        File cfg = AstrCfg.cfgFile(filesDir);
        File parent = cfg.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new Exception("配置目录不可写：" + parent);
        }
        if (cfg.isFile()) {
            backupCurrent();
        }
        atomicWrite(cfg, root.toString(2));
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("legacy", true);
        o.put("files", single(CMD_CFG));
        o.put("written", 1);
        o.put("size", cfg.length());
        o.put("ver", root.optInt("config_version", 0));
        o.put("warnings", warns);
        o.put("msg", "已按旧格式导入（只覆盖 cmd_config.json），重启 AstrBot 后生效；旧配置已自动备份");
        return o;
    }

    private void rollback(ArrayList<File> written, ArrayList<Boolean> existed) {
        for (int i = written.size() - 1; i >= 0; i--) {
            File f = written.get(i);
            boolean had = existed.get(i).booleanValue();
            try {
                File bak = bakOf(f);
                if (had) {
                    if (bak.isFile()) {
                        copy(bak, f);
                    }
                } else if (f.isFile()) {
                    f.delete();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static File bakOf(File f) {
        return new File(f.getAbsolutePath() + BAK_SUFFIX);
    }

    /** 临时文件 + rename；同目录、不同后缀，避免和 AstrCfg / StateIO 抢同一个 .tmp。 */
    private static void atomicWrite(File f, String text) throws Exception {
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new Exception("建不了目录：" + parent);
        }
        File tmp = new File(parent, f.getName() + ".beta7tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(text.getBytes("UTF-8"));
            out.flush();
            out.getFD().sync();
        } finally {
            try { out.close(); } catch (Throwable ignored) {}
        }
        if (tmp.renameTo(f)) {
            return;
        }
        if (f.exists() && !f.delete()) {
            try { tmp.delete(); } catch (Throwable ignored) {}
            throw new Exception("旧文件删不掉：" + f);
        }
        if (!tmp.renameTo(f)) {
            try { tmp.delete(); } catch (Throwable ignored) {}
            throw new Exception("重命名失败：" + tmp);
        }
    }

    /* ======================= 内部：路径与白名单 ======================= */

    private File bakDir() {
        return new File(QqbotShell.stateDir(filesDir), BAK_DIR);
    }

    /**
     * 逻辑路径 → 真实文件（写盘用）。
     *
     * state/cmd_config.json：老版本沙盒里核心配置其实住在 AstrBot 的 data/ 下
     * （$HOME/ubuntu/opt/AstrBot/data/cmd_config.json，见 AstrCfg.cfgFile），
     * 所以先看 .qqbot/state/cmd_config.json 在不在（将来若搬过来就自动跟着走），
     * 不在就落到 AstrCfg 的真身路径上。备份包里的键名保持不变，恢复也就回到正确的地方。
     */
    private File writableTarget(String path) {
        if (CMD_CFG.equals(path)) {
            File lit = new File(QqbotShell.stateDir(filesDir), "cmd_config.json");
            if (lit.isFile()) {
                return lit;
            }
            return AstrCfg.cfgFile(filesDir);
        }
        if (PREFS.equals(path)) {
            return Prefs.file(filesDir);
        }
        if (BOTS.equals(path)) {
            return new File(QqbotShell.qqbotDir(filesDir), "bots.json");
        }
        return null;
    }

    private File existingFile(String path) {
        File f = writableTarget(path);
        return (f != null && f.isFile()) ? f : null;
    }

    private static boolean isAllowed(String path) {
        if (path == null) {
            return false;
        }
        for (int i = 0; i < WHITELIST.length; i++) {
            if (WHITELIST[i].equals(path)) {
                return true;
            }
        }
        return false;
    }

    private static ArrayList<String> whitelistedPathsIn(JSONObject body) {
        ArrayList<String> out = new ArrayList<String>();
        if (body == null) {
            return out;
        }
        for (int i = 0; i < WHITELIST.length; i++) {
            String path = WHITELIST[i];
            if (body.optJSONObject(path) != null) {
                out.add(path);
            }
        }
        return out;
    }

    /* ======================= 内部：小工具 ======================= */

    private static String fail(String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("msg", msg);
            return o.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"msg\":\"internal error\"}";
        }
    }

    private static String failWithProblems(String prefix, JSONArray problems) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("problems", problems);
            o.put("msg", prefix + firstOf(problems, "备份包校验没通过"));
            return o.toString();
        } catch (Throwable t) {
            return fail(prefix);
        }
    }

    private static void addProblem(JSONArray arr, String msg) {
        try {
            arr.put(msg);
        } catch (Throwable ignored) {
        }
    }

    private static String firstOf(JSONArray arr, String def) {
        if (arr == null || arr.length() == 0) {
            return def;
        }
        String s = arr.optString(0, def);
        return (s == null || s.length() == 0) ? def : s;
    }

    private static String msgOf(Throwable t) {
        if (t == null) {
            return "";
        }
        String m = t.getMessage();
        return (m == null || m.length() == 0) ? String.valueOf(t) : m;
    }

    private static JSONArray single(String s) throws Exception {
        JSONArray a = new JSONArray();
        a.put(s);
        return a;
    }

    private static JSONArray arrOf(ArrayList<String> list) throws Exception {
        JSONArray a = new JSONArray();
        for (int i = 0; i < list.size(); i++) {
            a.put(list.get(i));
        }
        return a;
    }

    private static String joinOf(ArrayList<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(" / ");
            }
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    private static ArrayList<String> sortedKeys(JSONObject o) {
        ArrayList<String> out = new ArrayList<String>();
        if (o == null) {
            return out;
        }
        try {
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (k != null) {
                    out.add(k);
                }
            }
            Collections.sort(out);
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static boolean sameValue(Object a, Object b) {
        Object x = (a == null) ? JSONObject.NULL : a;
        Object y = (b == null) ? JSONObject.NULL : b;
        if (x == JSONObject.NULL || y == JSONObject.NULL) {
            return x == y;
        }
        if (x instanceof JSONObject && y instanceof JSONObject) {
            return x.toString().equals(y.toString());
        }
        if (x instanceof JSONArray && y instanceof JSONArray) {
            return x.toString().equals(y.toString());
        }
        if (x instanceof JSONObject || y instanceof JSONObject) {
            return false;
        }
        if (x instanceof JSONArray || y instanceof JSONArray) {
            return false;
        }
        if (x instanceof Number && y instanceof Number) {
            return ((Number) x).doubleValue() == ((Number) y).doubleValue();
        }
        return String.valueOf(x).equals(String.valueOf(y));
    }

    private static boolean hasCoreSection(JSONObject root) {
        for (int i = 0; i < CORE.length; i++) {
            if (root.has(CORE[i])) {
                return true;
            }
        }
        return false;
    }

    private static String sha256(byte[] b) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            byte[] r = d.digest(b);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < r.length; i++) {
                int v = r[i] & 0xff;
                if (v < 16) {
                    sb.append('0');
                }
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 读一个文本文件；不存在 / 读失败 / 空 / 超过 cap 一律返回 null（绝不抛）。 */
    private static String readText(File f, long cap) {
        if (f == null || !f.isFile()) {
            return null;
        }
        FileInputStream in = null;
        try {
            long len = f.length();
            if (len <= 0 || len > cap) {
                return null;
            }
            in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream((int) Math.min(len, 65536L));
            byte[] buf = new byte[8192];
            int n;
            long total = 0L;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > cap) {
                    return null;
                }
                bo.write(buf, 0, n);
            }
            String s = new String(bo.toByteArray(), "UTF-8");
            if (s.length() > 0 && s.charAt(0) == '\uFEFF') {
                s = s.substring(1);
            }
            return s;
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
    }

    /* ======================= 内部：沿用原来的实现 ======================= */

    private ArrayList<File> backups() {
        ArrayList<File> out = new ArrayList<File>();
        File dir = bakDir();
        File[] list = dir.listFiles();
        if (list == null) {
            return out;
        }
        for (int i = 0; i < list.length; i++) {
            File f = list[i];
            if (f != null && f.isFile() && f.getName().startsWith("cfg-")
                    && f.getName().endsWith(".json")) {
                out.add(f);
            }
        }
        out.sort(BY_NAME_DESC);
        return out;
    }

    /** 名字白名单：cfg-YYYYMMDD-HHMMSS.json，且必须落在备份目录里。 */
    private File checkedBackup(String name) {
        String n = (name == null) ? "" : name.trim();
        if (!n.matches("cfg-[0-9]{8}-[0-9]{6}\\.json")) {
            return null;
        }
        File f = new File(bakDir(), n);
        return f.isFile() ? f : null;
    }

    /** 「下载/AstrBot烛版」；建不出来就回落到应用私有 exports/，保证功能可用。 */
    private File exportDir() {
        ArrayList<File> cands = new ArrayList<File>();
        try {
            File ext = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (ext != null) cands.add(new File(ext, EXPORT_DIR));
        } catch (Throwable ignored) {}
        cands.add(new File("/sdcard/Download", EXPORT_DIR));
        cands.add(new File("/storage/emulated/0/Download", EXPORT_DIR));
        for (int i = 0; i < cands.size(); i++) {
            File d = cands.get(i);
            try {
                if (d.isDirectory() || d.mkdirs()) {
                    File probe = new File(d, ".w");
                    if (probe.createNewFile() || probe.isFile()) {
                        try { probe.delete(); } catch (Throwable ignored) {}
                        return d;
                    }
                }
            } catch (Throwable ignored) {}
        }
        File fallback = new File(filesDir, "exports");
        if (!fallback.isDirectory()) fallback.mkdirs();
        return fallback;
    }

    private int addTree(ZipOutputStream zos, File dir, String prefix) {
        File[] list = dir.listFiles();
        if (list == null) return 0;
        int n = 0;
        for (int i = 0; i < list.length; i++) {
            File f = list[i];
            if (f == null || !f.isFile()) continue;
            if (packedSoFar >= LOG_CAP) break;
            if (f.length() > 8L * 1024L * 1024L) continue;      // 单个超大文件跳过（多半是镜像碎片）
            try {
                zipFile(zos, f, prefix + f.getName());
                n++;
            } catch (Throwable ignored) {}
        }
        return n;
    }

    /** state 下的小 JSON（告警 / 重连 / 自检结果），只收 256 KB 以内的。 */
    private void addSmallJson(ZipOutputStream zos, File stateDir) {
        File[] list = stateDir.listFiles();
        if (list == null) return;
        for (int i = 0; i < list.length; i++) {
            File f = list[i];
            if (f == null || !f.isFile() || !f.getName().endsWith(".json")) continue;
            if (f.length() > 256L * 1024L) continue;
            if (packedSoFar >= LOG_CAP) return;
            try {
                zipFile(zos, f, "state/" + f.getName());
            } catch (Throwable ignored) {}
        }
    }

    private void zipFile(ZipOutputStream zos, File f, String entryName) throws Exception {
        InputStream in = new FileInputStream(f);
        try {
            ZipEntry e = new ZipEntry(entryName);
            e.setTime(f.lastModified());
            zos.putNextEntry(e);
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) {
                zos.write(buf, 0, r);
                packedSoFar += r;
            }
            zos.closeEntry();
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
        }
    }

    private void zipText(ZipOutputStream zos, String entryName, String text) throws Exception {
        ZipEntry e = new ZipEntry(entryName);
        e.setTime(System.currentTimeMillis());
        zos.putNextEntry(e);
        byte[] b = text.getBytes("UTF-8");
        zos.write(b, 0, b.length);
        zos.closeEntry();
        packedSoFar += b.length;
    }

    private static void copy(File src, File dst) throws Exception {
        File parent = dst.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new Exception("建不了目录：" + parent);
        }
        InputStream in = new FileInputStream(src);
        OutputStream out = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
            }
            out.flush();
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
            try { out.close(); } catch (Throwable ignored) {}
        }
    }

    /** cmd_config.json 里的 config_version（老版本没有就是 0）。 */
    private static int cfgVersion(File f) {
        try {
            String s = StateIO.read(f);
            if (s == null) return 0;
            return new JSONObject(s).optInt("config_version", 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
    }

    private static String fmt(long ms) {
        if (ms <= 0L) return "";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(ms));
    }

    /** manifest 的 createdAtText：秒级，YYYY-MM-DD HH:MM:SS。 */
    private static String fmtSec(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(ms));
    }

    private String appVersion() {
        try {
            File f = new File(QqbotShell.qqbotDir(filesDir), "opt/qqbot/VERSION");
            String s = StateIO.read(f);
            if (s != null && s.trim().length() > 0) return s.trim();
        } catch (Throwable ignored) {}
        return "";
    }

    /** manifest 里的 app：读不到 VERSION 就用兜底常量，别让字段空着。 */
    private String appVersionOr() {
        String v = appVersion();
        return (v == null || v.length() == 0) ? APP_FALLBACK : v;
    }

    /** 备份按文件名倒序 = 新的在前（时间戳定宽，字典序即时间序）。 */
    private static final Comparator<File> BY_NAME_DESC = new ByNameDesc();

    private static final class ByNameDesc implements Comparator<File> {
        public int compare(File a, File b) {
            String x = (a == null) ? "" : a.getName();
            String y = (b == null) ? "" : b.getName();
            return y.compareTo(x);
        }
    }
}
