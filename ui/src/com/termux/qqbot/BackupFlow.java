package com.termux.app;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONObject;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * 功能 50「一键备份 / 恢复」的原生侧。
 *
 * 分工：打包、校验、停服、重启全在沙盒脚本里（build/opt/qqbot/backup.sh、restore.sh），
 * 原生层只做四件事：
 *   1. 起后台线程跑脚本（QqbotShell.run 是同步阻塞的，放桥线程上手机会卡成砖）；
 *   2. 按行解析脚本输出里的 SHA256= / OK / ERR=（stdout 与 stderr 被
 *      QqbotShell 合并成一份，所以必须认前缀，不能假设整份输出就是一行）；
 *   3. 落 state/backup.json、state/restore.json，供轮询与「上次备份」面板读；
 *   4. 用系统文件选择器把产物存到用户挑的位置 / 把用户挑的包拷回缓存。
 *
 * 为什么是 start + poll 两段式：桥方法是同步的，一次备份可能几十秒，
 * 前端拿 started:true 后每 500ms 轮询 backupPoll() 即可，详见 docs/接口约定。
 *
 * 时间戳一律 epoch 秒（StateIO.now()）。
 */
final class BackupFlow {

    /** state/backup.json：最近一次备份（ok/state/ts/scope/sha/bytes/file/saved）。 */
    static final String F_BACKUP = "backup.json";
    /** state/restore.json：最近一次恢复 / 待确认的选包结果。 */
    static final String F_RESTORE = "restore.json";
    /** 脚本自己写的进度文件（0/25/50/75/90/done/error）。 */
    private static final String PCT = "backup.pct";

    /** 同一时刻只允许一个重量级任务：备份、恢复、选完文件的拷入/拷出都算。 */
    private static final Object LOCK = new Object();
    /** "" / "backup" / "restore" / "copy-in" / "copy-out"。 */
    private static volatile String running = "";

    /** 恢复选文件时记住前端给的范围：onActivityResult 回来时拿不到原参数，只能靠它。 */
    private static volatile String pickScope = "all";

    /** 目标目录大小的内存缓存（后台算，60 秒内直接复用）。 */
    private static volatile long srcBytes = 0L;
    private static volatile boolean srcKnown = false;
    private static volatile long srcTs = 0L;
    private static volatile boolean srcBusy = false;
    /** 统计超时被打断 → 数字偏小，前端显示「约」而不是精确值。 */
    private static volatile boolean srcPartial = false;

    private BackupFlow() {
    }

    /* ======================= 路径 / 工具 ======================= */

    /** 系统「另存为」里的默认文件名：astrbot-backup-20261019-1641.tar.gz。 */
    static String suggestName() {
        return "astrbot-backup-" + stamp("yyyyMMdd-HHmm") + ".tar.gz";
    }

    private static String stamp(String fmt) {
        return new SimpleDateFormat(fmt, Locale.ROOT).format(new Date());
    }

    /** 只允许脚本认得的三个范围，顺便把任意字符串挡在命令行之外。 */
    private static String normScope(String scope) {
        String s = (scope == null) ? "" : scope.trim().toLowerCase(Locale.ROOT);
        if (s.length() == 0) return "all";
        if ("all".equals(s) || "config".equals(s) || "plugins".equals(s)) return s;
        return null;
    }

    /** 备份产物的临时目录：$HOME/.qqbot/cache/tmp（沙盒里就是 /qqbot-cache/tmp）。 */
    static File tmpDir(File filesDir) {
        return new File(new File(QqbotShell.qqbotDir(filesDir), "cache"), "tmp");
    }

    /** 用户选的备份包拷回来的地方。 */
    static File restoreDir(File filesDir) {
        return new File(new File(QqbotShell.qqbotDir(filesDir), "cache"), "restore");
    }

    private static File pctFile(File filesDir) {
        return StateIO.file(filesDir, PCT);
    }

    private static File script(File filesDir, String name) {
        return new File(new File(filesDir, "usr"), "opt/qqbot/" + name);
    }

    static JSONObject readState(File filesDir, String name) {
        String s = StateIO.read(StateIO.file(filesDir, name));
        if (s == null || s.trim().length() == 0) return null;
        try {
            return new JSONObject(s);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeState(File filesDir, String name, JSONObject o) {
        StateIO.write(StateIO.file(filesDir, name), o.toString());
    }

    private static String busyText(String r) {
        if ("backup".equals(r)) return "正在备份，请稍候";
        if ("restore".equals(r)) return "正在恢复，请稍候";
        if ("copy-in".equals(r)) return "正在读取备份文件，请稍候";
        if ("copy-out".equals(r)) return "正在保存备份文件，请稍候";
        return "已有任务在跑，请稍候";
    }

    /* ======================= 备份 ======================= */

    /** 起一轮备份（scope = all / config / plugins），立刻返回 started。 */
    static String startBackup(QqbotActivity act, String scope) {
        try {
            File filesDir = act.filesDirPub();
            String sc = normScope(scope);
            if (sc == null) return QqbotBridge.failJson("备份范围只能是 all / config / plugins");
            File sh = script(filesDir, "backup.sh");
            if (!sh.isFile()) return QqbotBridge.failJson("缺少 backup.sh，请先完成部署");
            String name = suggestName();
            synchronized (LOCK) {
                if (running.length() > 0) return QqbotBridge.failJson(busyText(running));
                File dir = tmpDir(filesDir);
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    return QqbotBridge.failJson("创建缓存目录失败：" + dir.getAbsolutePath());
                }
                File out = new File(dir, name);
                // 上一轮可能是恢复流程里的快照留下的 done，不清掉前端会以为本轮已经完成
                StateIO.delete(pctFile(filesDir));
                running = "backup";
                JSONObject st = new JSONObject();
                st.put("ok", true);
                st.put("state", "running");
                st.put("ts", StateIO.now());
                st.put("scope", sc);
                st.put("name", name);
                st.put("saved", false);
                writeState(filesDir, F_BACKUP, st);
                Thread th = new Thread(new BackupTask(filesDir, sc, out), "qqbot-backup");
                th.setDaemon(true);
                th.start();
            }
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("started", true);
            o.put("scope", sc);
            o.put("name", name);
            return o.toString();
        } catch (Throwable t) {
            return QqbotBridge.failJson("启动备份失败：" + t);
        }
    }

    /**
     * 把上一步的产物「另存为」到用户选的位置。
     * 这里只负责拉起系统文件选择器（必须在 Activity 主线程），
     * 真正的拷贝在 onActivityResult → BackupSaveTask 里做。
     */
    static String pickBackup(QqbotActivity act) {
        try {
            File filesDir = act.filesDirPub();
            synchronized (LOCK) {
                if (running.length() > 0) return QqbotBridge.failJson(busyText(running));
            }
            JSONObject st = readState(filesDir, F_BACKUP);
            if (sourceOf(st) == null) {
                return QqbotBridge.failJson("还没有可保存的备份文件，请先点「立即备份」");
            }
            String r = act.runOnUiSync(new UiTask(act, UiTask.BACKUP_SAVE, ""));
            if (r == null) return QqbotBridge.failJson("无法打开系统文件选择器");
            if (r.length() > 0) return QqbotBridge.failJson(r);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("started", true);
            o.put("name", st.optString("name", ""));
            return o.toString();
        } catch (Throwable t) {
            return QqbotBridge.failJson("打开文件选择器失败：" + t);
        }
    }

    /** backup.json 里记的那份产物，还在才算数（用户可能手动清过缓存）。 */
    private static File sourceOf(JSONObject st) {
        if (st == null || !st.optBoolean("ok", false)) return null;
        String p = st.optString("file", "");
        if (p.length() == 0) return null;
        File f = new File(p);
        if (!f.isFile() || f.length() <= 0) return null;
        return f;
    }

    /** onActivityResult：用户挑好落点了（或取消了）。主线程调用，只做判断和起线程。 */
    static void onSaveResult(QqbotActivity act, int resultCode, Intent data) {
        try {
            File filesDir = act.filesDirPub();
            if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
                act.pushJs("backupPicked", cancelJson());
                return;
            }
            Uri uri = data.getData();
            JSONObject st = readState(filesDir, F_BACKUP);
            File src = sourceOf(st);
            if (src == null) {
                act.pushJs("backupPicked", QqbotBridge.failJson("备份文件已经不在缓存里了，请重新备份"));
                return;
            }
            synchronized (LOCK) {
                if (running.length() > 0) {
                    act.pushJs("backupPicked", QqbotBridge.failJson(busyText(running)));
                    return;
                }
                running = "copy-out";
            }
            Thread th = new Thread(new BackupSaveTask(act, uri, src), "qqbot-backup-save");
            th.setDaemon(true);
            th.start();
        } catch (Throwable t) {
            act.pushJs("backupPicked", QqbotBridge.failJson("保存失败：" + t));
        }
    }

    /** 后台：产物 → 用户选的 Uri，成功后删掉临时产物并标 saved。 */
    static void saveToUri(QqbotActivity act, Uri uri, File src) {
        File filesDir = act.filesDirPub();
        String name = "";
        String sha = "";
        long bytes = 0L;
        String err = "";
        try {
            name = displayName(act.getContentResolver(), uri);
            if (name.length() == 0) name = src.getName();
            OutputStream out = act.getContentResolver().openOutputStream(uri);
            if (out == null) throw new java.io.IOException("系统没有给可写的输出流");
            InputStream in = new FileInputStream(src);
            long[] cnt = new long[1];
            try {
                sha = copy(in, out, cnt);
                bytes = cnt[0];
                out.flush();
            } finally {
                closeQuiet(in);
                closeQuiet(out);
            }
            if (bytes <= 0) throw new java.io.IOException("写出去的是空文件");
        } catch (Throwable t) {
            err = "写入失败：" + t;
        }
        try {
            if (err.length() == 0) {
                StateIO.delete(src);
                JSONObject st = readState(filesDir, F_BACKUP);
                JSONObject o = (st == null) ? new JSONObject() : st;
                o.put("ok", true);
                o.put("state", "done");
                o.put("saved", true);
                o.put("savedTs", StateIO.now());
                o.put("savedName", name);
                o.put("name", name);
                o.put("sha", sha);
                o.put("bytes", bytes);
                o.put("file", "");
                o.put("err", "");
                writeState(filesDir, F_BACKUP, o);

                JSONObject p = new JSONObject();
                p.put("ok", true);
                p.put("saved", true);
                p.put("name", name);
                p.put("size", bytes);
                p.put("sha256", sha);
                act.pushJs("backupPicked", p.toString());
            } else {
                JSONObject p = new JSONObject();
                p.put("ok", false);
                p.put("err", err);
                act.pushJs("backupPicked", p.toString());
            }
        } catch (Throwable ignored) {
            // 推不过去也还有 poll 兜底
        } finally {
            synchronized (LOCK) {
                running = "";
            }
        }
    }

    /** 后台：真正跑 backup.sh，解析 SHA256= / ERR= 并落 state/backup.json。 */
    static void runBackupScript(File filesDir, String scope, File out) {
        String sha = "";
        String err = "";
        long bytes = 0L;
        try {
            File sh = script(filesDir, "backup.sh");
            String cmd = "bash \"" + sh.getAbsolutePath() + "\" " + scope + " \"" + out.getAbsolutePath() + "\"";
            String raw = QqbotShell.run(filesDir, cmd, 600);
            sha = valueOf(raw, "SHA256=");
            if (out.isFile() && out.length() > 0) {
                bytes = out.length();
                // 大概率脚本已经打了 SHA256=；万一没打（被 kill / 版本旧），自己补算一份
                if (sha.length() == 0) sha = sha256Of(out);
            } else {
                err = reasonOf(raw, "备份没有产出文件");
            }
        } catch (Throwable t) {
            err = "备份脚本没能跑起来：" + t;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("ts", StateIO.now());
            o.put("scope", scope);
            o.put("name", out.getName());
            if (err.length() == 0 && bytes > 0) {
                o.put("ok", true);
                o.put("state", "done");
                o.put("sha", sha);
                o.put("bytes", bytes);
                o.put("file", out.getAbsolutePath());
                o.put("saved", false);
                o.put("err", "");
            } else {
                o.put("ok", false);
                o.put("state", "error");
                o.put("bytes", bytes);
                o.put("file", "");
                o.put("saved", false);
                o.put("err", (err.length() == 0) ? "备份失败" : shorten(err));
                StateIO.delete(out);
            }
            writeState(filesDir, F_BACKUP, o);
        } catch (Throwable ignored) {
        } finally {
            synchronized (LOCK) {
                running = "";
            }
        }
    }

    /** 备份进度：running 时读 backup.pct，结束按 state 给 100。 */
    static String pollBackup(File filesDir) {
        try {
            JSONObject st = readState(filesDir, F_BACKUP);
            boolean busy = "backup".equals(running);
            String state = stateOf(st, busy);
            String err = (st == null) ? "" : st.optString("err", "");
            if ("error".equals(state) && err.length() == 0) err = "上次任务被系统中断了，请重试";
            File f = sourceOf(st);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("state", state);
            o.put("pct", pctOf(filesDir, state));
            o.put("name", (st == null) ? "" : st.optString("name", ""));
            o.put("file", (st == null) ? "" : st.optString("file", ""));
            o.put("size", (st == null) ? 0L : st.optLong("bytes", 0L));
            o.put("sha256", (st == null) ? "" : st.optString("sha", ""));
            o.put("scope", (st == null) ? "" : st.optString("scope", ""));
            o.put("saved", st != null && st.optBoolean("saved", false));
            o.put("product", f != null);
            o.put("err", err);
            o.put("ts", (st == null) ? 0L : st.optLong("ts", 0L));
            return o.toString();
        } catch (Throwable t) {
            return QqbotBridge.failJson("读取备份进度失败：" + t);
        }
    }

    /* ======================= 恢复 ======================= */

    /** 起「选备份包」：拉起系统文件选择器，结果走 onActivityResult。 */
    static String pickRestore(QqbotActivity act, String scope) {
        try {
            File filesDir = act.filesDirPub();
            String sc = normScope(scope);
            if (sc == null) return QqbotBridge.failJson("恢复范围只能是 all / config / plugins");
            synchronized (LOCK) {
                if (running.length() > 0) return QqbotBridge.failJson(busyText(running));
            }
            pickScope = sc;
            String r = act.runOnUiSync(new UiTask(act, UiTask.RESTORE_OPEN, ""));
            if (r == null) return QqbotBridge.failJson("无法打开系统文件选择器");
            if (r.length() > 0) return QqbotBridge.failJson(r);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("started", true);
            o.put("scope", sc);
            return o.toString();
        } catch (Throwable t) {
            return QqbotBridge.failJson("打开文件选择器失败：" + t);
        }
    }

    /** Activity 拉起 Intent 时用来取范围（onActivityResult 拿不到原参数）。 */
    static String restoreScope() {
        String s = pickScope;
        return (s == null || s.length() == 0) ? "all" : s;
    }

    /** onActivityResult：用户选了备份包（或取消了）。 */
    static void onRestoreResult(QqbotActivity act, int resultCode, Intent data) {
        try {
            if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
                act.pushJs("restorePicked", cancelJson());
                return;
            }
            synchronized (LOCK) {
                if (running.length() > 0) {
                    act.pushJs("restorePicked", QqbotBridge.failJson(busyText(running)));
                    return;
                }
                running = "copy-in";
            }
            Thread th = new Thread(new RestoreCopyTask(act, data.getData(), restoreScope()), "qqbot-restore-copy");
            th.setDaemon(true);
            th.start();
        } catch (Throwable t) {
            act.pushJs("restorePicked", QqbotBridge.failJson("读取备份文件失败：" + t));
        }
    }

    /** 后台：用户选的 Uri → cache/restore/，边拷边算 SHA-256。 */
    static void copyIn(QqbotActivity act, Uri uri, String scope) {
        File filesDir = act.filesDirPub();
        String name = "";
        String sha = "";
        long bytes = 0L;
        String err = "";
        File dest = null;
        try {
            String sc = normScope(scope);
            scope = (sc == null) ? "all" : sc;
            ContentResolver cr = act.getContentResolver();
            name = safeName(displayName(cr, uri));
            File dir = restoreDir(filesDir);
            if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("创建缓存目录失败");
            cleanDir(dir);
            dest = new File(dir, name);
            InputStream in = cr.openInputStream(uri);
            if (in == null) throw new java.io.IOException("系统没有给可读的输入流");
            OutputStream out = new FileOutputStream(dest);
            long[] cnt = new long[1];
            try {
                sha = copy(in, out, cnt);
                bytes = cnt[0];
                out.flush();
            } finally {
                closeQuiet(in);
                closeQuiet(out);
            }
            if (bytes <= 0) throw new java.io.IOException("选中的文件是空的");
        } catch (Throwable t) {
            err = "读取备份文件失败：" + t;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("ts", StateIO.now());
            o.put("scope", (scope == null) ? "all" : scope);
            o.put("name", name);
            if (err.length() == 0 && dest != null) {
                o.put("ok", true);
                o.put("state", "picked");
                o.put("picked", true);
                o.put("needConfirm", true);
                o.put("file", dest.getAbsolutePath());
                o.put("sha", sha);
                o.put("bytes", bytes);
                o.put("err", "");
                writeState(filesDir, F_RESTORE, o);

                JSONObject p = new JSONObject();
                p.put("ok", true);
                p.put("scope", (scope == null) ? "all" : scope);
                p.put("name", name);
                p.put("size", bytes);
                p.put("sha256", sha);
                p.put("needConfirm", true);
                act.pushJs("restorePicked", p.toString());
            } else {
                o.put("ok", false);
                o.put("state", "error");
                o.put("picked", false);
                o.put("needConfirm", false);
                o.put("err", shorten((err.length() == 0) ? "读取备份文件失败" : err));
                writeState(filesDir, F_RESTORE, o);
                act.pushJs("restorePicked", QqbotBridge.failJson((err.length() == 0) ? "读取备份文件失败" : err));
            }
        } catch (Throwable ignored) {
        } finally {
            synchronized (LOCK) {
                running = "";
            }
        }
    }

    /**
     * 二次确认后真正开跑：restore.sh 自己会停服、解包、按原状态重启，
     * 原生层不再预停一次（预停会让脚本记成「本来就没在跑」，恢复完服务起不来）。
     */
    static String applyRestore(QqbotActivity act) {
        try {
            File filesDir = act.filesDirPub();
            JSONObject st = readState(filesDir, F_RESTORE);
            if (st == null || !st.optBoolean("picked", false)) {
                return QqbotBridge.failJson("还没有选择要恢复的备份文件");
            }
            String scope = normScope(st.optString("scope", "all"));
            if (scope == null) scope = "all";
            String p = st.optString("file", "");
            File src = new File(p);
            if (p.length() == 0 || !src.isFile() || src.length() <= 0) {
                return QqbotBridge.failJson("选中的备份文件已经不在了，请重新选择");
            }
            File sh = script(filesDir, "restore.sh");
            if (!sh.isFile()) return QqbotBridge.failJson("缺少 restore.sh，请先完成部署");
            String sha = st.optString("sha", "");
            synchronized (LOCK) {
                if (running.length() > 0) return QqbotBridge.failJson(busyText(running));
                StateIO.delete(pctFile(filesDir));
                running = "restore";
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("state", "running");
                o.put("ts", StateIO.now());
                o.put("scope", scope);
                o.put("file", src.getAbsolutePath());
                o.put("name", st.optString("name", src.getName()));
                o.put("sha", sha);
                o.put("bytes", src.length());
                // 已经开跑：把 picked 落掉，避免用户连点两次恢复
                o.put("picked", false);
                o.put("needConfirm", false);
                o.put("err", "");
                writeState(filesDir, F_RESTORE, o);
                Thread th = new Thread(new RestoreTask(filesDir, scope, src, sha), "qqbot-restore");
                th.setDaemon(true);
                th.start();
            }
            JSONObject r = new JSONObject();
            r.put("ok", true);
            r.put("started", true);
            r.put("scope", scope);
            return r.toString();
        } catch (Throwable t) {
            return QqbotBridge.failJson("启动恢复失败：" + t);
        }
    }

    /** 后台：真正跑 restore.sh，认 "OK" 一行或 "ERR=" 一行。 */
    static void runRestoreScript(File filesDir, String scope, File src, String sha) {
        boolean ok = false;
        String err = "";
        try {
            File sh = script(filesDir, "restore.sh");
            String cmd = "bash \"" + sh.getAbsolutePath() + "\" " + scope + " \"" + src.getAbsolutePath() + "\"";
            // 给了 SHA256= 脚本才校验；没有就不传（脚本按「无校验」走）
            if (sha != null && sha.length() > 0) cmd = cmd + " SHA256=" + sha;
            String raw = QqbotShell.run(filesDir, cmd, 900);
            ok = hasLine(raw, "OK");
            if (!ok) err = reasonOf(raw, "恢复失败");
        } catch (Throwable t) {
            err = "恢复脚本没能跑起来：" + t;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("ts", StateIO.now());
            o.put("scope", scope);
            o.put("name", src.getName());
            if (ok) {
                o.put("ok", true);
                o.put("state", "done");
                o.put("file", "");
                o.put("sha", "");
                o.put("bytes", 0L);
                o.put("picked", false);
                o.put("needConfirm", false);
                o.put("err", "");
                // 恢复成功就把缓存里的副本删掉：几百 MB 没必要一直占着，原件还在用户手里
                StateIO.delete(src);
            } else {
                o.put("ok", false);
                o.put("state", "error");
                // 失败就留着副本，用户改完问题可以直接重试，不用再选一次文件
                o.put("file", src.getAbsolutePath());
                o.put("sha", (sha == null) ? "" : sha);
                o.put("bytes", src.length());
                o.put("picked", true);
                o.put("needConfirm", false);
                o.put("err", shorten(err));
            }
            writeState(filesDir, F_RESTORE, o);
        } catch (Throwable ignored) {
        } finally {
            synchronized (LOCK) {
                running = "";
            }
        }
    }

    static String pollRestore(File filesDir) {
        try {
            JSONObject st = readState(filesDir, F_RESTORE);
            boolean busy = "restore".equals(running) || "copy-in".equals(running);
            String state = stateOf(st, busy);
            String err = (st == null) ? "" : st.optString("err", "");
            if ("error".equals(state) && err.length() == 0) err = "上次恢复被系统中断了，请重试";
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("state", state);
            o.put("pct", pctOf(filesDir, state));
            o.put("name", (st == null) ? "" : st.optString("name", ""));
            o.put("size", (st == null) ? 0L : st.optLong("bytes", 0L));
            o.put("sha256", (st == null) ? "" : st.optString("sha", ""));
            o.put("scope", (st == null) ? "all" : st.optString("scope", "all"));
            o.put("needConfirm", st != null && st.optBoolean("needConfirm", false));
            o.put("picked", st != null && st.optBoolean("picked", false));
            o.put("err", err);
            o.put("ts", (st == null) ? 0L : st.optLong("ts", 0L));
            return o.toString();
        } catch (Throwable t) {
            return QqbotBridge.failJson("读取恢复进度失败：" + t);
        }
    }

    /* ======================= 面板汇总 ======================= */

    /**
     * 上一次备份 + 上一次恢复 + 目标目录大小。
     * 目标大小走内存缓存（后台线程算），这里只取现成的数，整个方法不动磁盘上的大树。
     */
    static String info(File filesDir) {
        try {
            JSONObject last = readState(filesDir, F_BACKUP);
            JSONObject rest = readState(filesDir, F_RESTORE);
            long bytes = srcSize(filesDir);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("last", (last == null) ? new JSONObject() : last);
            o.put("restoreLast", (rest == null) ? new JSONObject() : rest);
            o.put("backupState", stateOf(last, "backup".equals(running)));
            o.put("restoreState", stateOf(rest, "restore".equals(running) || "copy-in".equals(running)));
            o.put("pct", pctOf(filesDir, stateOf(last, "backup".equals(running))));
            o.put("srcBytes", bytes);
            o.put("srcKnown", srcKnown);
            o.put("srcTs", srcTs);
            o.put("busy", running);
            o.put("ts", StateIO.now());
            return o.toString();
        } catch (Throwable t) {
            return QqbotBridge.failJson("读取备份信息失败：" + t);
        }
    }

    /** 取缓存的大小；超过 60 秒就丢一个后台线程重算（不阻塞调用方）。 */
    static long srcSize(File filesDir) {
        long now = StateIO.now();
        if (!srcBusy && (srcTs == 0L || now - srcTs > 60L)) {
            srcBusy = true;
            try {
                Thread th = new Thread(new SrcSizeTask(filesDir), "qqbot-src-size");
                th.setDaemon(true);
                th.start();
            } catch (Throwable t) {
                srcBusy = false;
            }
        }
        return srcBytes;
    }

    /** 后台：统计「备份会打到哪些目录」。规则和 common.sh 的 qq_astrbot_data_dir() 对齐。 */
    static void computeSrcBytes(File filesDir) {
        long total = 0L;
        try {
            srcPartial = false;
            File rootfs = new File(QqbotShell.homeDir(filesDir), "ubuntu");
            File a = new File(rootfs, "opt/AstrBot/data");
            File b = new File(rootfs, "root/astrbot/data");
            // b 在、a 不在时才用 b；两个都在以 a 为准，避免同一份数据算两遍
            File data = (b.isDirectory() && !a.isDirectory()) ? b : a;
            long deadline = System.currentTimeMillis() + 4000L;
            total += walk(data, deadline);
            total += walk(new File(rootfs, "root/qqinst"), deadline);
            File bots = new File(QqbotShell.qqbotDir(filesDir), "bots.json");
            if (bots.isFile()) total += bots.length();
            srcBytes = total;
            srcKnown = !srcPartial;
            srcTs = StateIO.now();
        } catch (Throwable t) {
            srcKnown = false;
        } finally {
            srcBusy = false;
        }
    }

    /** 有界遍历：超过 deadline 就停，数字标记为「约」（srcPartial）。 */
    private static long walk(File root, long deadline) {
        long total = 0L;
        if (root == null || !root.exists()) return 0L;
        ArrayList<File> stack = new ArrayList<File>();
        stack.add(root);
        while (!stack.isEmpty()) {
            if (System.currentTimeMillis() > deadline) {
                srcPartial = true;
                break;
            }
            File cur = stack.remove(stack.size() - 1);
            if (cur.isFile()) {
                total += cur.length();
                continue;
            }
            File[] kids = cur.listFiles();
            if (kids == null) continue;
            for (int i = 0; i < kids.length; i++) stack.add(kids[i]);
        }
        return total;
    }

    /* ======================= 公共小工具 ======================= */

    private static String cancelJson() {
        return "{\"ok\":false,\"err\":\"cancelled\"}";
    }

    /**
     * 由「内存里的任务状态 + state 文件里的结果」推出对外 state。
     * 进程被系统杀掉时文件会永远停在 running，按 ts 判个陈旧，别让界面一直转圈。
     */
    private static String stateOf(JSONObject st, boolean busy) {
        if (busy) return "running";
        if (st == null) return "idle";
        String s = st.optString("state", "");
        if (s.length() == 0) s = st.optBoolean("ok", false) ? "done" : "error";
        if ("running".equals(s) && StateIO.now() - st.optLong("ts", 0L) > 1800L) return "error";
        return s;
    }

    /** 只有 running 才读脚本写的 pct；结束的一律 100。 */
    private static int pctOf(File filesDir, String state) {
        if ("done".equals(state) || "error".equals(state)) return 100;
        if (!"running".equals(state)) return 0;
        String s = StateIO.read(pctFile(filesDir));
        if (s == null) return 0;
        s = s.trim();
        if ("done".equals(s) || "error".equals(s)) return 100;
        try {
            int v = Integer.parseInt(s);
            if (v < 0) v = 0;
            if (v > 100) v = 100;
            return v;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 在合并后的输出里倒着找 "PREFIX值" 那一行；只取第一个词，后面可能是说明文字。 */
    private static String valueOf(String raw, String prefix) {
        if (raw == null) return "";
        String[] lines = raw.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String s = lines[i].trim();
            if (!s.startsWith(prefix)) continue;
            String v = s.substring(prefix.length()).trim();
            int sp = v.indexOf(' ');
            if (sp > 0) v = v.substring(0, sp);
            return v;
        }
        return "";
    }

    /** 脚本成功时只会单独打一行 OK（stderr 的日志都是 "[restore] …" 前缀）。 */
    private static boolean hasLine(String raw, String want) {
        if (raw == null) return false;
        String[] lines = raw.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (want.equals(lines[i].trim())) return true;
        }
        return false;
    }

    /** 失败原因：优先脚本的 ERR=，其次超时标记，最后退到最后一个非空行。 */
    private static String reasonOf(String raw, String def) {
        String e = valueOf(raw, "ERR=");
        if (e.length() > 0) return e;
        if (raw != null) {
            String[] lines = raw.split("\n");
            boolean timeout = false;
            String last = "";
            for (int i = 0; i < lines.length; i++) {
                String s = lines[i].trim();
                if (s.length() == 0) continue;
                last = s;
                if (s.indexOf("[timeout]") >= 0) timeout = true;
            }
            if (timeout) return "脚本超时被中止";
            if (last.length() > 0) return shorten(last);
        }
        return def;
    }

    private static String shorten(String s) {
        String v = (s == null) ? "" : s.trim();
        if (v.length() > 200) return v.substring(0, 200) + "…";
        return v;
    }

    /** 边拷边算 SHA-256（十六进制小写）。 */
    private static String copy(InputStream in, OutputStream out, long[] cnt) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[65536];
        long total = 0L;
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            md.update(buf, 0, n);
            total += n;
        }
        if (cnt != null && cnt.length > 0) cnt[0] = total;
        return hex(md.digest());
    }

    static String sha256Of(File f) {
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return hex(md.digest());
        } catch (Throwable t) {
            return "";
        } finally {
            closeQuiet(in);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (int i = 0; i < b.length; i++) {
            int v = b[i] & 0xFF;
            if (v < 16) s.append('0');
            s.append(Integer.toHexString(v));
        }
        return s.toString();
    }

    /** 取系统文件选择器里显示的文件名；拿不到就退到 Uri 末段。 */
    private static String displayName(ContentResolver cr, Uri uri) {
        Cursor c = null;
        try {
            c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String s = c.getString(idx);
                    if (s != null && s.length() > 0) return s;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
        String p = uri.getLastPathSegment();
        return (p == null) ? "" : p;
    }

    /** 用户选的文件名可能带路径分隔符或 ..，落盘前只留文件名本体。 */
    private static String safeName(String n) {
        String s = (n == null) ? "" : n.trim();
        int slash = s.lastIndexOf('/');
        int bslash = s.lastIndexOf('\\');
        if (bslash > slash) slash = bslash;
        if (slash >= 0) s = s.substring(slash + 1);
        if (s.length() == 0 || ".".equals(s) || "..".equals(s)) return "restore.tar.gz";
        if (s.length() > 80) s = s.substring(0, 80);
        return s;
    }

    /** 清掉 cache/restore 里上一轮留下的旧包（同一个目录，避免越攒越多）。 */
    private static void cleanDir(File dir) {
        try {
            File[] kids = dir.listFiles();
            if (kids == null) return;
            for (int i = 0; i < kids.length; i++) {
                if (kids[i].isFile()) kids[i].delete();
            }
        } catch (Throwable ignored) {
        }
    }

    static void closeQuiet(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }
}
