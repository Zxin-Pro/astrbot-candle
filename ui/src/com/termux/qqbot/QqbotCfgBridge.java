package com.termux.app;

import android.os.Environment;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 「配置即代码」那条线的原生侧：window.AndroidCfg（1.3.5-beta.6）。
 *
 * 职责只有四件事，全部围绕 AstrBot 的两份真身文件：
 *   data/cmd_config.json  —— 核心配置（provider / dashboard / 平台 / 人格选择…）
 *   data/data_v4.db       —— 人格与历史（这里只碰 personas.system_prompt，交给 AstrCfg）
 *
 *   1. 备份：把 cmd_config.json 复制进 state/cfg-bak/，只留最近 KEEP 份；
 *   2. 导入 / 导出：导出到手机「下载」目录，导入接受粘贴的 JSON（先校验再落盘）；
 *   3. 系统提示词：读写 SysPrompt（state/prefs.json 兜底 + 尽力同步 data_v4.db）；
 *   4. 日志导出：把 .qqbot/logs 与 state/*.json 打成一个 zip 放到「下载」目录。
 *
 * 三条纪律：
 *   - 桥方法全部同步返回 JSON 字符串，**绝不抛异常**（WebView 里抛一次就整页白屏）；
 *   - 任何覆盖写之前先备份，备份最坏就是多占一份几百 KB；
 *   - 所有文件名都走白名单校验，restore 不许出现路径分隔符（防目录穿越）。
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

    private final File filesDir;

    public QqbotCfgBridge(QqbotActivity act) {
        this.filesDir = act.filesDirPub();
    }

    /** 自检 / 无界面场景用。 */
    public QqbotCfgBridge(File filesDir) {
        this.filesDir = filesDir;
    }

    /* ------------------------------ 状态 ------------------------------ */

    /**
     * 界面进页面时调一次：{ok,cfgExists,size,mtime,ver,backups,lastBackup,name,promptLen,isDefaultPrompt}。
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
            String p = SysPrompt.get(filesDir);
            o.put("promptLen", p.length());
            o.put("isDefaultPrompt", SysPrompt.isDefault(filesDir));
            return o.toString();
        } catch (Throwable t) {
            return fail("读取配置状态失败：" + t);
        }
    }

    /* ------------------------------ 备份 ------------------------------ */

    /** 立刻备份一次：{ok,name,size,at,count}。 */
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
            o.put("msg", "已备份到应用私有目录，只保留最近 " + KEEP + " 份");
            return o.toString();
        } catch (Throwable t) {
            return fail("备份失败：" + t);
        }
    }

    /** 备份列表（新 → 旧）：{ok,items:[{name,size,at,ver}]}。 */
    @JavascriptInterface
    public String listBackups() {
        try {
            ArrayList<File> list = backups();
            JSONArray arr = new JSONArray();
            for (int i = 0; i < list.size(); i++) {
                File f = list.get(i);
                JSONObject it = new JSONObject();
                it.put("name", f.getName());
                it.put("size", f.length());
                it.put("at", fmt(f.lastModified()));
                it.put("ver", cfgVersion(f));
                arr.put(it);
            }
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("items", arr);
            o.put("keep", KEEP);
            return o.toString();
        } catch (Throwable t) {
            return fail("读取备份列表失败：" + t);
        }
    }

    /** 回滚到某个备份：{ok,msg}。回滚前会先把当前配置再备份一份，避免越退越糟。 */
    @JavascriptInterface
    public String restore(String name) {
        try {
            File src = checkedBackup(name);
            if (src == null) {
                return fail("备份不存在或文件名不合法");
            }
            File cfg = AstrCfg.cfgFile(filesDir);
            if (cfg.isFile()) {
                doBackup();
            }
            copy(src, cfg);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("msg", "已恢复 " + src.getName() + "，重启 AstrBot 后生效（当前的配置也已备份）");
            return o.toString();
        } catch (Throwable t) {
            return fail("恢复失败：" + t);
        }
    }

    /* --------------------------- 导入 / 导出 --------------------------- */

    /** 导出配置到「下载」目录：{ok,path,name,size,msg}。导出前先留一份备份。 */
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
     * 导入 JSON 文本：{ok,msg,ver,size}。
     * 校验口径（宁可拒绝也不写坏）：必须是 JSON 对象、非空、且至少含一个 AstrBot 核心段，
     * 免得用户粘了一篇 markdown 进来把配置写成一行乱码。
     */
    @JavascriptInterface
    public String importText(String text) {
        try {
            String s = (text == null) ? "" : text.trim();
            if (s.length() == 0) {
                return fail("内容为空，先粘贴一份 cmd_config.json");
            }
            if (s.length() > 2 * 1024 * 1024) {
                return fail("内容超过 2 MB，不像是 AstrBot 的配置");
            }
            JSONObject root;
            try {
                root = new JSONObject(s);
            } catch (Throwable bad) {
                return fail("不是合法 JSON：" + bad.getMessage());
            }
            if (root.length() == 0) {
                return fail("JSON 是空的，没东西可导入");
            }
            String[] need = new String[] { "provider", "provider_settings", "dashboard", "platform",
                    "platform_settings", "agent_runner", "config_version", "wake_prefix" };
            boolean hit = false;
            for (int i = 0; i < need.length; i++) {
                if (root.has(need[i])) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                return fail("这份 JSON 里没有 AstrBot 的配置段（provider / dashboard / platform …），已拒绝写入");
            }
            File cfg = AstrCfg.cfgFile(filesDir);
            if (!cfg.getParentFile().isDirectory() && !cfg.getParentFile().mkdirs()) {
                return fail("配置目录不可写：" + cfg.getParent());
            }
            if (cfg.isFile()) {
                doBackup();
            }
            writeText(cfg, root.toString(2));
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("size", cfg.length());
            o.put("ver", root.optInt("config_version", 0));
            o.put("msg", "已导入（覆盖 cmd_config.json），重启 AstrBot 后生效；旧配置已自动备份");
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

    /* ------------------------------ 内部 ------------------------------ */

    /** 上一次打包已写入的字节数（日志导出用，字段而非返回值为的是避免 d8 的装箱问题）。 */
    private long packedSoFar = 0L;

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

    private ArrayList<File> backups() {
        ArrayList<File> out = new ArrayList<File>();
        File dir = new File(QqbotShell.stateDir(filesDir), BAK_DIR);
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

    /** 备份当前配置并返回刚生成的文件；顺手把超出 KEEP 的老备份删掉。 */
    private File doBackup() throws Exception {
        File cfg = AstrCfg.cfgFile(filesDir);
        File dir = new File(QqbotShell.stateDir(filesDir), BAK_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new Exception("建不了备份目录：" + dir);
        }
        File out = new File(dir, "cfg-" + stamp() + ".json");
        copy(cfg, out);
        ArrayList<File> list = backups();
        for (int i = KEEP; i < list.size(); i++) {
            try { list.get(i).delete(); } catch (Throwable ignored) {}
        }
        return out;
    }

    /** 名字白名单：cfg-YYYYMMDD-HHMMSS.json，且必须落在备份目录里。 */
    private File checkedBackup(String name) {
        String n = (name == null) ? "" : name.trim();
        if (!n.matches("cfg-[0-9]{8}-[0-9]{6}\\.json")) {
            return null;
        }
        File f = new File(new File(QqbotShell.stateDir(filesDir), BAK_DIR), n);
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

    private static void writeText(File f, String text) throws Exception {
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new Exception("建不了目录：" + parent);
        }
        File tmp = new File(f.getAbsolutePath() + ".tmp");
        OutputStream out = new FileOutputStream(tmp);
        try {
            byte[] b = text.getBytes("UTF-8");
            out.write(b, 0, b.length);
            out.flush();
        } finally {
            try { out.close(); } catch (Throwable ignored) {}
        }
        if (f.exists() && !f.delete()) {
            throw new Exception("旧文件删不掉：" + f);
        }
        if (!tmp.renameTo(f)) {
            throw new Exception("重命名失败：" + tmp);
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

    private String appVersion() {
        try {
            File f = new File(QqbotShell.qqbotDir(filesDir), "opt/qqbot/VERSION");
            String s = StateIO.read(f);
            if (s != null && s.trim().length() > 0) return s.trim();
        } catch (Throwable ignored) {}
        return "";
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