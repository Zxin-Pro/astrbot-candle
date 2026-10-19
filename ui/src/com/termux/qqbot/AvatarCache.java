package com.termux.app;

import android.net.Uri;

import java.io.File;

/**
 * 为什么需要它：控制台「实例卡片」要显示 QQ 头像，但 WebView 里 <img src="https://q1.qlogo.cn/..."> 
 * 在离线/被墙时会长时间空着，而且每次打开页面都重新下载。原生把头像缓存成文件后，
 * 前端拿 file:// 路径直接 <img src>，秒开且断网也能看。
 * 桥方法必须 <50ms 返回，所以这里是「有就返回、没有就丢后台下载、本轮返回空路径」：
 * 前端下一轮轮询拿到 cached:true 再显示，绝不在这里等网络。
 * 缓存目录 $HOME/.qqbot/cache/avatars/<uin>.png，按 mtime 做 LRU，最多留 50 个。
 * d8 处理匿名内部类会崩，本文件不出现匿名类。
 */
public final class AvatarCache {

    static final int MAX = 50;
    private static final int MIN_LEN = 5;
    private static final int MAX_LEN = 12;

    private AvatarCache() {}

    static File dir(File filesDir) {
        return new File(new File(QqbotShell.qqbotDir(filesDir), "cache"), "avatars");
    }

    /**
     * 桥方法 avatarPath(uin)：只查缓存，不联网。
     * 命中 → {"ok":true,"path":"file://...","cached":true}；
     * 未命中/参数非法/断网 → {"ok":true,"path":"","cached":false}（下载在后台自己跑，下次轮询就能命中）。
     * 永不抛异常、永不返回 ok:false —— 头像是装饰，不能让它把整张卡片拖成错误态。
     */
    static String avatarPath(File filesDir, String uin) {
        String qq = digits(uin);
        if (qq.length() < MIN_LEN || qq.length() > MAX_LEN) {
            return "{\"ok\":true,\"path\":\"\",\"cached\":false}";
        }
        try {
            File dir = dir(filesDir);
            File f = new File(dir, qq + ".png");
            if (f.isFile() && f.length() > 0) {
                // 命中即刷新 mtime：LRU 淘汰时「最近看过的」最后才被删
                f.setLastModified(System.currentTimeMillis());
                return "{\"ok\":true,\"path\":\"" + esc(Uri.fromFile(f).toString()) + "\",\"cached\":true}";
            }
            Thread th = new Thread(new AvatarFetch(dir, qq), "qqbot-avatar");
            th.setDaemon(true);
            th.start();
        } catch (Throwable ignored) {
        }
        return "{\"ok\":true,\"path\":\"\",\"cached\":false}";
    }

    /** 只留数字：QQ 号就应该是纯数字，顺带挡住 ../ 这类路径穿越。 */
    private static String digits(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < MAX_LEN + 1; i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') b.append(c);
        }
        return b.toString();
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(' ');
            else b.append(c);
        }
        return b.toString();
    }
}
