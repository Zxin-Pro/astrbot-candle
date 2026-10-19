package com.termux.app;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 为什么需要它：AvatarCache.avatarPath() 必须 <50ms 返回，不能在那里等网络，
 * 所以真正的下载放到这个一次性后台任务里：抓 q1.qlogo.cn 的 100x100 头像，
 * 先写 .part 再 rename（避免前端拿到半张图），最后按 mtime 做 LRU 淘汰。
 * 断网、超时、非 200、写盘失败都只是「这次没下成」，绝不抛异常、也不留垃圾文件。
 * d8 处理匿名内部类会崩，所以必须是具名顶层类。
 */
public class AvatarFetch implements Runnable {

    private final File dir;
    private final String uin;

    public AvatarFetch(File dir, String uin) {
        this.dir = dir;
        this.uin = uin;
    }

    public void run() {
        File tmp = new File(dir, uin + ".part");
        File dst = new File(dir, uin + ".png");
        HttpURLConnection conn = null;
        InputStream in = null;
        FileOutputStream out = null;
        try {
            if (!dir.isDirectory()) dir.mkdirs();
            URL u = new URL("https://q1.qlogo.cn/g?b=qq&nk=" + uin + "&s=100");
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (qqbot-sandbox)");
            if (conn.getResponseCode() != 200) return;
            in = conn.getInputStream();
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            // 100x100 的头像正常几 KB，超过 512KB 一定是被劫持/返回了网页，直接放弃
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
                if (total > 512L * 1024L) break;
            }
            out.close();
            out = null;
            if (tmp.length() <= 0) return;
            if (!tmp.renameTo(dst)) {
                tmp.delete();
                return;
            }
            trim();
        } catch (Throwable ignored) {
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
            if (tmp.exists()) {
                try {
                    tmp.delete();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** LRU 上限 50：每次选 mtime 最旧的那个删，文件不多，选择排序足够（不能用匿名 Comparator）。 */
    private void trim() {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        int guard = 0;
        while (fs.length > AvatarCache.MAX && guard++ < 200) {
            File oldest = null;
            long t = Long.MAX_VALUE;
            for (int i = 0; i < fs.length; i++) {
                if (fs[i].isDirectory()) continue;
                long m = fs[i].lastModified();
                if (m < t) {
                    t = m;
                    oldest = fs[i];
                }
            }
            if (oldest == null) return;
            if (!oldest.delete()) return; // 删不掉就别死循环
            fs = dir.listFiles();
            if (fs == null) return;
        }
    }
}
