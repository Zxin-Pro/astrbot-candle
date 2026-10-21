package com.termux.app;

// 注意：这个包声明必须是 com.termux.app —— 和 QqbotActivity / QqbotShell 同包，
// 否则调用处找不到符号。源文件放在 qqbot/ 目录下只是历史原因（其它 qqbot 类也一样）。

import android.content.Context;

import java.io.File;

/**
 * 给 bootstrap 建两个"短路径"别名：
 *
 * <pre>
 *   /data/data/&lt;pkg&gt;/u  -&gt;  /data/data/&lt;pkg&gt;/files/usr
 *   /data/data/&lt;pkg&gt;/h  -&gt;  /data/data/&lt;pkg&gt;/files/home
 * </pre>
 *
 * 为什么需要：bootstrap 里那 249 个 ELF（bin/bash、bin/dpkg、lib/libapt-pkg.so……）
 * 编译期把前缀写死成 /data/data/com.termux/files/usr，改包名后必须换成新前缀。
 * 但 ELF 里的字符串只能在"不改变文件长度"的前提下原地改写（改了长度就得重建
 * 节头/程序头，那些二进制在真机上会直接 exec 失败），而 zhuxin.astrbot 比
 * com.termux 长 4 字节，写不进去。
 *
 * 所以改用别名：把 /data/data/&lt;pkg&gt;/files/usr 记成 /data/data/&lt;pkg&gt;/u，
 * 每一处替换都变短，剩余字节用 NUL 填满即可，ELF 结构一个字节都不用动
 * （tools/reloc_bootstrap.py 的 ELF_MAP 就是这么写的）。
 */
public final class PrefixAlias {

    private PrefixAlias() {
    }

    /** 幂等：每次都调用也不会出问题。任何失败都静默 —— 最坏情况是回落到 LD_LIBRARY_PATH。 */
    public static void ensure(Context ctx) {
        try {
            ensure(ctx.getFilesDir());
        } catch (Throwable ignored) {
        }
    }

    /** 只拿到 filesDir 的地方（例如 QqbotShell）用这个重载。 */
    public static void ensure(File files) {
        try {
            File parent = files.getParentFile();
            if (parent == null) return;
            link(parent, "u", new File(files, "usr"));
            link(parent, "h", new File(files, "home"));
        } catch (Throwable ignored) {
            // 老设备 / 只读目录 / SELinux 都可能拦下来，不影响启动流程
        }
    }

    private static void link(File dir, String name, File target) {
        try {
            File l = new File(dir, name);
            String want = target.getAbsolutePath();
            if (isSymlink(l)) {
                try {
                    String cur = android.system.Os.readlink(l.getAbsolutePath());
                    if (want.equals(cur) || cur.equals("./" + name)) return;
                } catch (Throwable ignored) {
                }
                l.delete();
            } else if (l.exists()) {
                // 真目录/真文件，不是我们建的，绝不能删
                return;
            }
            try {
                android.system.Os.symlink(want, l.getAbsolutePath());
            } catch (Throwable t) {
                // 再兜一层：有的 ROM 只放行 shell 的 ln
                Process p = Runtime.getRuntime().exec(new String[]{"ln", "-s", want, l.getAbsolutePath()});
                p.waitFor();
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isSymlink(File f) {
        try {
            File parent = f.getParentFile();
            if (parent == null) return false;
            File canon = new File(parent.getCanonicalFile(), f.getName());
            return !canon.getCanonicalFile().equals(canon.getAbsoluteFile());
        } catch (Throwable t) {
            return false;
        }
    }
}
