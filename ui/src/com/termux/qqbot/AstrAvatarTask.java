package com.termux.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 为什么需要它：AstrAvatar.info() 跑在 JavaBridge 线程上（WebView 在等它返回），
 * 不能在那里等网络，所以真正的下载放到这个一次性后台任务里：抓 q4.qlogo.cn 的 640px 头像，
 * 解码 → 缩到最长边 320 → 压 PNG → 先写 .part 再 rename，最后写一个毫秒时间戳的 stamp。
 *
 * 断网、超时、非 200、返回的不是图片、写盘失败都只是「这次没下成」：绝不抛异常、
 * 不留垃圾文件，旧缓存原样留着（前端照样能看到上一张头像）。
 *
 * 只持有目录和 uin，不持有 Activity —— 下载中途 Activity 被销毁也不会泄漏。
 * d8 处理匿名内部类会崩，所以必须是具名顶层类。
 */
public class AstrAvatarTask implements Runnable {

    private final File dir;
    private final String uin;

    public AstrAvatarTask(File dir, String uin) {
        this.dir = dir;
        this.uin = uin;
    }

    public void run() {
        try {
            fetch();
        } finally {
            AstrAvatar.done(uin);
        }
    }

    private void fetch() {
        File png = new File(dir, "avatar_" + uin + ".png");
        File stamp = new File(dir, "avatar_" + uin + ".stamp");
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            // s=640 是 qlogo 支持的最大档之一，缩到 320 后比 s=100 清楚得多
            URL u = new URL("https://q4.qlogo.cn/g?b=qq&nk=" + uin + "&s=640");
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setInstanceFollowRedirects(true);
            // 直接 GET：qlogo 对 HEAD 时有时无地回 403，量也不大，没必要先探一刀
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() != 200) return;
            in = conn.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            long total = 0L;
            // 640px 的头像正常几十 KB。超过 4MB 一定是被劫持成网页/广告了，直接放弃
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                total += n;
                if (total > 4L * 1024L * 1024L) return;
            }
            byte[] raw = bo.toByteArray();
            if (raw.length == 0) return;
            Bitmap bm = BitmapFactory.decodeByteArray(raw, 0, raw.length);
            if (bm == null) return;
            Bitmap scaled = AstrAvatar.fit(bm, AstrAvatar.BIG);
            boolean saved = AstrAvatar.savePng(png, scaled);
            if (scaled != bm) {
                try { scaled.recycle(); } catch (Throwable ignored) {}
            }
            try { bm.recycle(); } catch (Throwable ignored) {}
            if (!saved) return;
            // stamp 只在图上真的换了之后写：下载失败时 mtime 不变，下次 refresh 照样会重试
            AstrAvatar.writeStamp(stamp, System.currentTimeMillis());
        } catch (Throwable ignored) {
            // 下载失败只是「这次没换成」，旧图还在
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            try { if (conn != null) conn.disconnect(); } catch (Throwable ignored) {}
        }
    }
}
