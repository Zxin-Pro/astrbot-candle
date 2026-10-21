package com.termux.app;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * 为什么需要它：v1.1.30 起原生层要往 $HOME/.qqbot/state/ 里写一批小状态文件
 * （net.json / boot.json / power.json / degrade / offline …），读写的坑完全一样——
 * 目录可能不存在、读到半截文件、异常把桥线程打穿。这里收敛成一处，
 * 统一「先写 .tmp 再改名」的原子写，读失败一律当没有（绝不抛）。
 * d8 处理匿名内部类会崩，本文件不出现匿名类。
 */
final class StateIO {

    /** state 目录：$HOME/.qqbot/state（= 沙盒里的 /qqbot-cache/state）。 */
    static File dir(File filesDir) {
        return QqbotShell.stateDir(filesDir);
    }

    static File file(File filesDir, String name) {
        return new File(dir(filesDir), name);
    }

    static long now() {
        return System.currentTimeMillis() / 1000L;
    }

    /** 读一个小文本文件；不存在 / 读失败 / 超大（>256KB）一律返回 null。 */
    static String read(File f) {
        if (f == null || !f.isFile()) return null;
        FileInputStream in = null;
        try {
            long len = f.length();
            // 状态文件都是几百字节；读到奇怪的巨物说明被写坏了，当没有处理，
            // 免得把 30MB 的日志当 JSON 往内存里灌。
            if (len <= 0 || len > 262144L) return null;
            in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream((int) len);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) { try { in.close(); } catch (Throwable ignored) {} }
        }
    }

    static String read(File filesDir, String name) {
        return read(file(filesDir, name));
    }

    /** 原子写：同目录 .tmp 写完再 rename，避免守护线程读到半截内容。
     *  加 synchronized：同一个 state 文件会被守护线程、网络监听、UI 三处同时写，
     *  固定 .tmp 名会让两个线程抢同一个临时文件（一个刚写一半被另一个截断），
     *  再 rename 上去就是一份损坏的 JSON。串行化后成本可以忽略（都是几百字节）。 */
    static synchronized boolean write(File f, String text) {
        if (f == null) return false;
        FileOutputStream out = null;
        try {
            File d = f.getParentFile();
            if (d != null && !d.isDirectory()) d.mkdirs();
            File tmp = new File(f.getAbsolutePath() + ".tmp." + Thread.currentThread().getId());
            out = new FileOutputStream(tmp);
            out.write((text == null ? "" : text).getBytes("UTF-8"));
            out.flush();
            out.close();
            out = null;
            // POSIX 的 rename 可以直接原子覆盖目标；先 delete 会在「删完、还没改名」
            // 的瞬间留下一个不存在的目标文件，进程恰好被杀就把状态丢了。
            // 只有个别文件系统不支持覆盖式 rename 时才退回「先删再改」。
            if (tmp.renameTo(f)) return true;
            if (f.exists() && f.delete()) return tmp.renameTo(f);
            return false;
        } catch (Throwable t) {
            return false;
        } finally {
            if (out != null) { try { out.close(); } catch (Throwable ignored) {} }
        }
    }

    static boolean write(File filesDir, String name, String text) {
        return write(file(filesDir, name), text);
    }

    static boolean delete(File f) {
        try {
            return f != null && f.delete();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 只写一个 epoch 秒，用于 offline / degrade 这类单值标记文件。 */
    static boolean writeStamp(File f) {
        return write(f, String.valueOf(now()));
    }
}
