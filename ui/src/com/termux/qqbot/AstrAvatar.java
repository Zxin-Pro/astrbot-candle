package com.termux.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;

/**
 * 为什么需要它：控制台要把「当前登录的 QQ 头像」当成自己的门面（先做成桌面快捷方式的
 * 图标，以后还能用在别的原生地方）。页面上显示头像走的是 AvatarCache 的 file:// 路径，
 * 那条路只缓存 100x100、而且要先进实例目录；这里给桥方法一份能直接喂 Bitmap 的
 * 320px PNG（base64 data URI），落盘在 files/avatar/，12 小时内不再联网。
 *
 * 桥方法必须立刻返回：它跑在 WebView 的 JavaBridge 线程上，同步请求会把界面卡住。
 * 所以「没缓存 / 缓存过期」时本轮回 {"ok":false,"err":"下载中"}，真正的下载丢给
 * AstrAvatarTask，前端下一次 refresh 再问就命中（有旧图时直接给旧图，不闪空）。
 *
 * 缓存：files/avatar/avatar_<uin>.png + avatar_<uin>.stamp（写毫秒时间戳）。
 * 用 stamp 而不是只靠 png 的 mtime：拷备份恢复、某些文件系统不更新 mtime 时，
 * 光看 mtime 会把一年前的头像当成今天的。
 *
 * d8 处理匿名内部类会崩，本文件不出现匿名类 / lambda。
 */
public final class AstrAvatar {

    /** 缓存目录（相对 filesDir）。和 AvatarCache 的 home/.qqbot 缓存分开放，免得互相 LRU 淘汰。 */
    static final String DIR = "avatar";
    /** 缓存有效期 12 小时。 */
    static final long TTL_MS = 12L * 60L * 60L * 1000L;
    /** 落盘的最长边。 */
    static final int BIG = 320;
    /** data URI 超过 300KB 时再缩一次的目标。 */
    static final int SMALL = 192;
    /** data URI 上限（base64 会把字节数放大 4/3，所以文件本身约 225KB 就顶到了）。 */
    static final int MAX_URI = 300 * 1024;
    /** QQ 号最长 12 位（最短 5 位，见 isUin）。 */
    static final int MAX_UIN_LEN = 12;
    /** 前缀必须和 QqbotActivity.qr() 一致，前端拿到的都是同一种 data URI。 */
    static final String PREFIX = "data:image/png;base64,";

    private AstrAvatar() {}

    /* ---------- 路径 ---------- */

    static File dir(Activity act) { return new File(act.getFilesDir(), DIR); }

    static File pngFile(Activity act, String uin) { return new File(dir(act), "avatar_" + uin + ".png"); }

    static File stampFile(Activity act, String uin) { return new File(dir(act), "avatar_" + uin + ".stamp"); }

    /* ---------- 对外的桥方法 ---------- */

    /**
     * 当前头像：{"ok":true,"uin":"123456","data":"data:image/png;base64,...","mtime":1690000000}；
     * 拿不到 {"ok":false,"err":"…"}。任何情况下都不抛异常、不在调用线程上联网。
     */
    public static String info(Activity act) {
        try {
            String uin = findUin(act);
            if (uin.length() == 0) return fail("还没有登录的账号");
            File png = pngFile(act, uin);
            File stamp = stampFile(act, uin);
            boolean has = isPng(png);
            long mt = has ? png.lastModified() : 0L;
            boolean fresh = has && !isStale(png, stamp);
            // 过期或者根本没有：先把后台下载推起来（kick 内部限流，前端 4 秒一次 refresh
            // 不会把同一个域名打烂），本轮继续往下走 —— 有旧图就给旧图。
            if (!fresh) kick(act, uin);
            String memo = memoOf(uin, mt);
            if (memo != null) return memo;
            if (has) {
                String json = read(uin, png);
                if (json != null) return json;
                return fail("头像读不出来");
            }
            return fail("下载中");
        } catch (Throwable t) {
            return fail("读取头像失败：" + oneLine(t));
        }
    }

    /**
     * 后台重下。同一个 uin 不并发起两个线程（连点「钉图标」会叠一堆下载）；
     * 上次刚失败过就等 60 秒再试，否则断网时每 4 秒一次的 refresh 会一直重开线程。
     */
    static void kick(Activity act, String uin) {
        try {
            synchronized (LOCK) {
                long now = System.currentTimeMillis();
                if (uin.equals(busyUin)) return;
                if (uin.equals(lastUin) && now - lastAt < 60000L) return;
                busyUin = uin;
                lastUin = uin;
                lastAt = now;
            }
            Thread t = new Thread(new AstrAvatarTask(dir(act), uin), "qqbot-avatar2");
            t.setDaemon(true);
            t.start();
        } catch (Throwable ignored) {
            // 起不了线程就下次 refresh 再说（顺手把占位解开，否则这个 uin 今生都不会再重试）
            done(uin);
        }
    }

    /** AstrAvatarTask 收尾：解除「正在下」的占位。 */
    static void done(String uin) {
        synchronized (LOCK) {
            if (uin != null && uin.equals(busyUin)) busyUin = "";
        }
    }

    /* ---------- 内存缓存（uin + 文件 mtime 不变就不重复读盘 / 重复 base64） ---------- */

    private static final Object LOCK = new Object();
    private static String busyUin = "";
    private static String lastUin = "";
    private static long lastAt = 0L;
    private static String memoUin = "";
    private static long memoMtime = -1L;
    private static String memoJson = "";

    private static String memoOf(String uin, long mt) {
        synchronized (LOCK) {
            if (memoJson.length() == 0) return null;
            if (!uin.equals(memoUin)) return null;
            if (mt <= 0 || mt != memoMtime) return null;
            return memoJson;
        }
    }

    private static void memoPut(String uin, long mt, String json) {
        synchronized (LOCK) {
            memoUin = uin;
            memoMtime = mt;
            memoJson = json;
        }
    }

    /* ---------- 读 ---------- */

    /** 读盘 + 拼 data URI；太大就先缩到 192 再回写小图。失败返回 null。 */
    static String read(String uin, File png) {
        try {
            byte[] raw = readAll(png);
            if (raw == null || raw.length < 8) return null;
            String uri = null;
            if (uriLen(raw) > MAX_URI) {
                // 320px 的 PNG 一般几十 KB；真碰上噪点图会超 300KB，缩一次并回写，
                // 免得之后每次 refresh 都白缩一遍。
                uri = shrink(png, raw);
            }
            if (uri == null) uri = uriOf(raw);
            if (uri == null) return null;
            long mt = png.lastModified();
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("uin", uin);
            o.put("data", uri);
            // mtime 用「秒」而不是毫秒：只当变化令牌用（前端拿去拼缓存键），和文档示例一致
            o.put("mtime", mt / 1000L);
            String json = o.toString();
            memoPut(uin, mt, json);
            return json;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 缩到 SMALL 并回写；返回新的 data URI（回写失败也照样给缩过的那份）。 */
    static String shrink(File png, byte[] raw) {
        Bitmap bm = null;
        try {
            bm = BitmapFactory.decodeByteArray(raw, 0, raw.length);
            if (bm == null) return null;
            Bitmap sm = fit(bm, SMALL);
            String uri = uriOfPng(sm);
            boolean same = (sm == bm);
            if (!same) sm.recycle();
            bm.recycle();
            bm = null;
            if (uri == null) return null;
            byte[] small = bytesOf(uri);
            if (small != null) saveBytes(png, small);
            return uri;
        } catch (Throwable t) {
            return null;
        } finally {
            if (bm != null) { try { bm.recycle(); } catch (Throwable ignored) {} }
        }
    }

    /** 缓存还算新鲜吗（12 小时内）。时钟被往回调过（mtime 在未来）也当新鲜，否则会一直重下。 */
    static boolean isStale(File png, File stamp) {
        long t = 0L;
        try {
            long s = readStamp(stamp);
            if (s > t) t = s;
            long m = png.lastModified();
            if (m > t) t = m;
        } catch (Throwable ignored) {
        }
        if (t <= 0) return true;
        long age = System.currentTimeMillis() - t;
        return age >= TTL_MS;
    }

    static long readStamp(File f) {
        try {
            if (f == null || !f.isFile() || f.length() <= 0 || f.length() > 64) return 0L;
            byte[] b = readAll(f);
            if (b == null) return 0L;
            return Long.parseLong(new String(b, "UTF-8").trim());
        } catch (Throwable t) {
            return 0L;
        }
    }

    static void writeStamp(File f, long ms) {
        try {
            saveBytes(f, String.valueOf(ms).getBytes("UTF-8"));
        } catch (Throwable ignored) {
        }
    }

    static boolean isPng(File f) {
        try {
            return f != null && f.isFile() && f.length() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 读整个文件。不用 Files.readAllBytes —— 那是 API 26 才有的，本应用 minSdk 24。 */
    static byte[] readAll(File f) {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            long len = raf.length();
            if (len <= 0 || len > 4L * 1024L * 1024L) return null;
            byte[] b = new byte[(int) len];
            raf.readFully(b);
            return b;
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (raf != null) raf.close(); } catch (Throwable ignored) {}
        }
    }

    /* ---------- 写 ---------- */

    /** 原子写：先写 <name>.part 再 rename，避免界面/桌面读到半张图。 */
    static boolean saveBytes(File dst, byte[] data) {
        if (dst == null || data == null || data.length == 0) return false;
        File dir = dst.getParentFile();
        File tmp = new File(dir, dst.getName() + ".part");
        FileOutputStream out = null;
        try {
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return false;
            out = new FileOutputStream(tmp);
            out.write(data);
            out.flush();
            out.close();
            out = null;
            if (tmp.length() <= 0) return false;
            if (!tmp.renameTo(dst)) {
                tmp.delete();
                return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignored) {}
            try { if (tmp.exists()) tmp.delete(); } catch (Throwable ignored) {}
        }
    }

    static boolean savePng(File dst, Bitmap bm) {
        try {
            String uri = uriOfPng(bm);
            byte[] raw = bytesOf(uri);
            if (raw == null) return false;
            return saveBytes(dst, raw);
        } catch (Throwable t) {
            return false;
        }
    }

    /* ---------- 图片小工具 ---------- */

    /** 等比缩到最长边 <= max；本来就够小就原样返回（由调用方决定要不要 recycle）。 */
    static Bitmap fit(Bitmap src, int max) {
        if (src == null) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) return src;
        if (w <= max && h <= max) return src;
        int nw;
        int nh;
        if (w >= h) {
            nw = max;
            nh = Math.max(1, Math.round((float) h * max / w));
        } else {
            nh = max;
            nw = Math.max(1, Math.round((float) w * max / h));
        }
        Bitmap out = Bitmap.createScaledBitmap(src, nw, nh, true);
        return (out == null) ? src : out;
    }

    static String uriOf(byte[] raw) {
        try {
            if (raw == null || raw.length == 0) return null;
            return PREFIX + Base64.encodeToString(raw, Base64.NO_WRAP);
        } catch (Throwable t) {
            return null;
        }
    }

    /** data URI 的长度（不真拼字符串，省一次 300KB 的分配）。 */
    static int uriLen(byte[] raw) {
        if (raw == null) return 0;
        return PREFIX.length() + 4 * ((raw.length + 2) / 3);
    }

    static String uriOfPng(Bitmap bm) {
        if (bm == null) return null;
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try {
            if (!bm.compress(Bitmap.CompressFormat.PNG, 100, bo)) return null;
            return uriOf(bo.toByteArray());
        } catch (Throwable t) {
            return null;
        }
    }

    static byte[] bytesOf(String uri) {
        if (uri == null) return null;
        int k = uri.indexOf(',');
        if (k < 0 || k + 1 >= uri.length()) return null;
        try {
            return Base64.decode(uri.substring(k + 1), Base64.DEFAULT);
        } catch (Throwable t) {
            return null;
        }
    }

    /* ---------- uin 发现 ---------- */

    /**
     * 当前登录的 QQ 号，找不到返回 ""。顺序：
     *   a) bots.json 里 active 那只 bot 的 qq（老版本写的是 uin）字段；
     *   b) NapCat 登录后按账号写的 napcat_&lt;uin&gt;.json，文件名里的数字就是 QQ 号；
     *   c) 都没有 → ""。
     * 全程只读小文件和目录，不联网、不改任何东西。
     */
    static String findUin(Activity act) {
        QqbotActivity q = (act instanceof QqbotActivity) ? (QqbotActivity) act : null;
        if (q == null) return "";
        JSONObject root = null;
        try {
            root = new JSONObject(q.readBots());
        } catch (Throwable ignored) {
            // bots.json 坏了就只走 (b) 的目录扫描
        }
        String uin = "";
        try {
            JSONObject pick = null;
            String active = (root == null) ? "" : root.optString("active", "");
            JSONArray bs = (root == null) ? null : root.optJSONArray("bots");
            for (int i = 0; bs != null && i < bs.length(); i++) {
                JSONObject b = bs.optJSONObject(i);
                if (b == null) continue;
                if (pick == null) pick = b;
                if (active.length() > 0 && active.equals(b.optString("id", ""))) {
                    pick = b;
                    break;
                }
            }
            if (pick != null) {
                uin = qqOf(pick.optString("qq", ""));
                if (uin.length() == 0) uin = qqOf(pick.optString("uin", ""));
            }
        } catch (Throwable ignored) {
        }
        if (uin.length() > 0) return uin;
        try {
            File ub = new File(q.homeDir(), "ubuntu");
            uin = scanInstances(ub, root);
            if (uin.length() == 0) uin = scanDir(new File(ub, "opt/QQ/config"));
            if (uin.length() == 0) {
                uin = scanDir(new File(ub, "opt/QQ/resources/app/app_launcher/napcat/config"));
            }
        } catch (Throwable ignored) {
        }
        return uin;
    }

    /** 逐个实例找 config 目录，active 那台优先（用户嘴里的「当前登录」就是它）。 */
    static String scanInstances(File ub, JSONObject root) {
        try {
            if (ub == null || !ub.isDirectory()) return "";
            String active = (root == null) ? "" : root.optString("active", "");
            String u = scanInstance(ub, active);
            if (u.length() > 0) return u;
            JSONArray bs = (root == null) ? null : root.optJSONArray("bots");
            for (int i = 0; bs != null && i < bs.length(); i++) {
                JSONObject b = bs.optJSONObject(i);
                if (b == null) continue;
                String id = b.optString("id", "");
                if (id.length() == 0 || id.equals(active)) continue;
                u = scanInstance(ub, id);
                if (u.length() > 0) return u;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    static String scanInstance(File ub, String id) {
        if (id == null || id.length() == 0) return "";
        // id 来自用户可编辑的 bots.json：带 /  或 .. 的一律不信，别让它拼出任意路径
        if (id.indexOf('/') >= 0 || id.indexOf('\\') >= 0 || id.indexOf("..") >= 0) return "";
        String inst = "opt/qqbot-inst/" + id;
        String u = scanDir(new File(ub, inst + "/config"));
        if (u.length() == 0) {
            // NapCat 真实位置（和 QqbotActivity.scanNapcatUin 一致）：<QQ>/resources/app/app_launcher/napcat/config
            u = scanDir(new File(ub, inst + "/QQ/resources/app/app_launcher/napcat/config"));
        }
        return u;
    }

    /** 在一个 config 目录里找 napcat_&lt;数字&gt;.json，取最新改过的那份（换号登录会留下多份）。 */
    static String scanDir(File dir) {
        try {
            if (dir == null || !dir.isDirectory()) return "";
            File[] fs = dir.listFiles();
            if (fs == null) return "";
            String best = "";
            long bestAt = -1L;
            for (int i = 0; i < fs.length; i++) {
                File f = fs[i];
                if (f == null || !f.isFile()) continue;
                String n = f.getName();
                /* 登录成功后 NapCat 会写两份 per-uin 文件：napcat_<uin>.json 与 onebot11_<uin>.json。
                   前者偶尔会因为 selfInfo.uin 还没填好而退化回不带 uin 的名字，所以后者也认上作兜底。 */
                String pre = "";
                if (n.startsWith("napcat_")) pre = "napcat_";
                else if (n.startsWith("onebot11_")) pre = "onebot11_";
                if (pre.length() == 0 || !n.endsWith(".json")) continue;
                if (n.length() <= pre.length() + 5) continue;
                String num = n.substring(pre.length(), n.length() - 5);
                if (!isUin(num)) continue;
                long t = f.lastModified();
                if (t > bestAt) {
                    bestAt = t;
                    best = num;
                }
            }
            return best;
        } catch (Throwable t) {
            return "";
        }
    }

    /** QQ 号必须是 5~12 位纯数字：顺带挡住空值和路径穿越。 */
    static String qqOf(String s) {
        if (s == null) return "";
        String t = s.trim();
        return isUin(t) ? t : "";
    }

    static boolean isUin(String s) {
        if (s == null) return false;
        int n = s.length();
        if (n < 5 || n > MAX_UIN_LEN) return false;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /* ---------- 杂项 ---------- */

    private static String fail(String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("err", msg);
            return o.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"err\":\"头像不可用\"}";
        }
    }

    /** 异常信息里可能有换行，压成一行再拼进 JSON（JSONObject.put 会自己处理引号）。 */
    static String oneLine(Throwable t) {
        String s = String.valueOf(t);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < 200; i++) {
            char c = s.charAt(i);
            b.append((c == '\n' || c == '\r' || c == '\t') ? ' ' : c);
        }
        return b.toString();
    }
}
