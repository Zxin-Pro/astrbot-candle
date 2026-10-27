package com.termux.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * 分享桥（window.AndroidShare）：把一段文本、或者最近一次上传的日志交给系统分享面板。
 *
 * 为什么走系统的 ACTION_SEND 而不是自己做界面：分享是纯系统能力，微信/QQ/邮箱都在
 * 分享面板里；自己画一套反而要一个个适配。
 *
 * 文件为什么不用 FileProvider：本包自带的 TermuxOpenReceiver$ContentProvider 已经把
 * files 目录按 content://<包名>.files/<绝对路径> 暴露出来，openFile 会校验路径必须以
 * /data/data/<包名>/files 开头；配上 FLAG_GRANT_READ_URI_PERMISSION 就能把读权限单独
 * 授给接收方，不必新增清单 provider，也不必申请存储权限（和二维码分享同一条通道）。
 *
 * 注意 Uri 里的路径必须是 /data/data/... 而不是 getFilesDir() 给的 /data/user/0/...
 * 否则 provider 会判 Invalid path 直接抛异常——多用户下这两个路径指向同一处，但校验
 * 只认前者。
 *
 * 所有方法都吞异常并把错误用 Toast 说出来：桥跑在 JS 线程上，异常吞掉前端还能继续用，
 * 抛出去只会得到一条 undefined。
 *
 * d8 处理匿名内部类会崩，本文件不出现匿名类。
 */
public class QqbotShare {

    private final Activity act;

    public QqbotShare(Activity act) {
        this.act = act;
    }

    /** text(title, text)：纯文本分享（邀请链接、配置片段、报错说明都走这里）。 */
    @JavascriptInterface
    public void text(String title, String text) {
        try {
            String body = (text == null) ? "" : text;
            if (body.trim().length() == 0) {
                toast("没有可以分享的内容");
                return;
            }
            // Binder 事务上限 1MB，超长文本会让 startActivity 直接抛 TransactionTooLargeException。
            // 留足余量截断，并告诉接收方这是截过的。
            if (body.length() > 100000) {
                body = body.substring(0, 100000) + "\n……（内容太长，已截断）";
            }
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, title == null ? "" : title);
            send.putExtra(Intent.EXTRA_TEXT, body);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            openChooser(send, "分享文本");
        } catch (Throwable t) {
            toast("分享失败：" + t);
        }
    }

    /**
     * log()：分享最近一次「上传日志」打出来的那份报告。
     * 文件由沙盒里的 upload-log.sh 生成在 $HOME/.qqbot/cache/errreport.txt，
     * 宿主侧路径就是 files/home/.qqbot/cache/errreport.txt。
     * 复制一份到 files/share/ 再分享，不直接把 cache 目录暴露出去：
     * cache 里的文件随时会被脚本改写，分享出去的应该是此刻的快照。
     */
    @JavascriptInterface
    public void log() {
        try {
            File files = act.getFilesDir();
            File src = new File(QqbotShell.qqbotDir(files), "cache/errreport.txt");
            if (!src.isFile() || src.length() <= 0) {
                toast("还没有可分享的日志，先在「日志」页上传一次");
                return;
            }
            File dir = new File(files, "share");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                toast("分享失败：建不了缓存目录");
                return;
            }
            File out = new File(dir, "qqbot-log-" + stamp() + ".txt");
            if (!copy(src, out)) {
                toast("分享失败：日志复制不出来");
                return;
            }
            prune(dir);
            // provider 的 openFile 只认 /data/data/<包名>/files 开头的路径
            ensureExternalAppsAllowed(files);
            String pkg = act.getPackageName();
            Uri uri = Uri.parse("content://" + pkg + ".files/data/data/" + pkg + "/files/share/" + out.getName());
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, "QQ 机器人日志");
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            openChooser(send, "分享日志文件");
        } catch (Throwable t) {
            toast("分享日志失败：" + t);
        }
    }

    /* ---------------- 内部实现 ---------------- */

    /**
     * 起分享面板。先数一下有多少个外部应用能接：一个都没有时 startActivity 会抛
     * ActivityNotFoundException，用户只看到「闪一下」，不如直接说清楚。
     * 同时排除本包的接收页，免得用户把文件又存回自己的 files 目录。
     */
    private void openChooser(Intent send, String label) {
        int others = 0;
        try {
            List<ResolveInfo> list = act.getPackageManager().queryIntentActivities(send, 0);
            for (int i = 0; i < list.size(); i++) {
                ResolveInfo ri = list.get(i);
                if (ri == null || ri.activityInfo == null) continue;
                if (act.getPackageName().equals(ri.activityInfo.packageName)) continue;
                others++;
            }
        } catch (Throwable ignored) {
        }
        if (others == 0) {
            toast("这台手机没有能接收分享的应用（微信 / QQ 之类），先装一个再分享");
            return;
        }
        Intent chooser = Intent.createChooser(send, label);
        chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, new ComponentName[] {
                new ComponentName(act.getPackageName(), "com.termux.filepicker.TermuxFileReceiverActivity") });
        // chooser 自己也要带读权限与 NEW_TASK：真正的接收方拿到的是 chooser 转发出去的
        // Intent，少了 grant 会报 "Permission Denial"。
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        act.runOnUiThread(new UiStart(act, chooser));
    }

    /**
     * Termux 自带的 provider 默认不对外：openFile 会先查
     * ~/.termux/termux.properties 里的 allow-external-apps，不是 true 就报
     * "requires allow-external-apps property to be set to true"。
     * 已经有这一项（哪怕写的是 false）就原样保留，不覆盖用户的设置。
     */
    private void ensureExternalAppsAllowed(File files) {
        FileOutputStream out = null;
        FileInputStream in = null;
        try {
            File dir = new File(QqbotShell.homeDir(files), ".termux");
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            File f = new File(dir, "termux.properties");
            String cur = "";
            if (f.isFile()) {
                if (f.length() > 64L * 1024) return;
                in = new FileInputStream(f);
                byte[] buf = new byte[(int) f.length()];
                int got = 0;
                while (got < buf.length) {
                    int n = in.read(buf, got, buf.length - got);
                    if (n <= 0) break;
                    got += n;
                }
                in.close();
                in = null;
                cur = new String(buf, 0, got, "UTF-8");
                if (cur.indexOf("allow-external-apps") >= 0) return;
            }
            String add = (cur.length() == 0 || cur.endsWith("\n")) ? "" : "\n";
            out = new FileOutputStream(f);
            out.write((cur + add + "allow-external-apps=true\n").getBytes("UTF-8"));
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            if (in != null) { try { in.close(); } catch (Throwable ignored) {} }
            if (out != null) { try { out.close(); } catch (Throwable ignored) {} }
        }
    }

    /** 复制日志（几百 KB 到 1.5MB）：调用的 JS 线程上直接做，复制完再上主线程弹分享。 */
    private static boolean copy(File src, File dst) {
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            out.close();
            out = null;
            return dst.isFile() && dst.length() > 0;
        } catch (Throwable t) {
            return false;
        } finally {
            if (in != null) { try { in.close(); } catch (Throwable ignored) {} }
            if (out != null) { try { out.close(); } catch (Throwable ignored) {} }
        }
    }

    /** share 目录只留最近 5 份快照，不然每分享一次就积一份，一年后能攒出上百 MB。 */
    private static void prune(File dir) {
        try {
            File[] all = dir.listFiles();
            if (all == null || all.length <= 5) return;
            java.util.Arrays.sort(all, new ByMtimeDesc());
            for (int i = 5; i < all.length; i++) {
                try {
                    all[i].delete();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static String stamp() {
        try {
            return new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        } catch (Throwable t) {
            return String.valueOf(System.currentTimeMillis());
        }
    }

    private void toast(String msg) {
        try {
            act.runOnUiThread(new UiToast(act, msg));
        } catch (Throwable ignored) {
        }
    }

    /** 按修改时间倒序（新的在前）。具名 Comparator，理由同下面的 Runnable。 */
    private static final class ByMtimeDesc implements java.util.Comparator<File> {
        public int compare(File a, File b) {
            long x = (a == null) ? 0L : a.lastModified();
            long y = (b == null) ? 0L : b.lastModified();
            if (x == y) return 0;
            return x < y ? 1 : -1;
        }
    }

    /** 具名 Runnable：d8 处理匿名内部类会崩，这两个小任务只能用嵌套类。 */
    private static final class UiStart implements Runnable {
        private final Activity act;
        private final Intent intent;

        UiStart(Activity a, Intent i) {
            act = a;
            intent = i;
        }

        public void run() {
            try {
                act.startActivity(intent);
            } catch (Throwable t) {
                try {
                    Toast.makeText(act, "打不开分享面板：" + t, Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static final class UiToast implements Runnable {
        private final Activity act;
        private final String msg;

        UiToast(Activity a, String m) {
            act = a;
            msg = m;
        }

        public void run() {
            try {
                Toast.makeText(act, msg, Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
        }
    }
}
