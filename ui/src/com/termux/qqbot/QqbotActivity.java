package com.termux.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.util.Base64;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Enumeration;
import java.util.Map;

public class QqbotActivity extends Activity {

    private WebView web;
    private QqbotBridge bridge;

    public File filesDirPub() { return getFilesDir(); }
    public File usrDir() { return new File(getFilesDir(), "usr"); }
    public File homeDir() { return new File(getFilesDir(), "home"); }
    public File qqbotDir() { return new File(homeDir(), ".qqbot"); }
    public File logsDir() { return new File(qqbotDir(), "cache/logs"); }
    public File botsFile() { return new File(qqbotDir(), "bots.json"); }
    // 免责声明是否已同意（"0" = 未同意，其它非空内容 = 已同意）
    public File disclaimerFile() { return new File(qqbotDir(), "disclaimer.ok"); }
    // 下载代理（host 侧；沙盒内通过 /qqbot-cache/proxy.txt 读到同一份内容）
    public File proxyFile() { return new File(qqbotDir(), "proxy.txt"); }
    public Activity activity() { return this; }

    /** 选背景图用的 requestCode（必须 >= 0）。 */
    private static final int REQ_PICK_BG = 7001;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_BG) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        try {
            InputStream in = getContentResolver().openInputStream(data.getData());
            if (in == null) {
                doToast("无法读取该图片");
                return;
            }
            // 先只读尺寸算出采样率，再真正解码。相册原图动辄 4000x3000 / 8MB，
            // 直接存原文件会让页面每次启动都整块读进内存再 Base64 成 1.33 倍字符串，
            // 低配机上是卡启动甚至 OOM。
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try {
                BitmapFactory.decodeStream(in, null, bounds);
            } finally {
                // 解码抛异常时也要关：这些流是 ContentResolver 给的 fd，不关会一路泄漏
                closeQuiet(in);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                doToast("无法读取该图片");
                return;
            }
            int maxSide = 1600;
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= maxSide || bounds.outHeight / (sample * 2) >= maxSide) sample *= 2;
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = sample;
            opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
            InputStream in2 = getContentResolver().openInputStream(data.getData());
            if (in2 == null) {
                doToast("无法读取该图片");
                return;
            }
            Bitmap bm;
            try {
                bm = BitmapFactory.decodeStream(in2, null, opt);
            } finally {
                closeQuiet(in2);
            }
            if (bm == null) {
                doToast("无法读取该图片");
                return;
            }
            // 采样通常已经够小；长边仍超上限就再缩一次，保证体积可控。
            int bw = bm.getWidth();
            int bh = bm.getHeight();
            int longer = (bw > bh) ? bw : bh;
            if (longer > maxSide) {
                float k = (float) maxSide / (float) longer;
                int nw = Math.round(bw * k);
                int nh = Math.round(bh * k);
                if (nw < 1) nw = 1;
                if (nh < 1) nh = 1;
                Bitmap small = Bitmap.createScaledBitmap(bm, nw, nh, true);
                if (small != bm) bm.recycle();
                bm = small;
            }
            File f = bgFile();
            File d = f.getParentFile();
            if (d != null && !d.isDirectory()) d.mkdirs();
            // 统一存成 JPEG：体积小、MIME 稳定，也不会再出现 HEIC/GIF 被错标成 png。
            FileOutputStream out = new FileOutputStream(f);
            try {
                bm.compress(Bitmap.CompressFormat.JPEG, 88, out);
                out.flush();
            } finally {
                closeQuiet(out);
            }
            bm.recycle();
            doToast("背景已更新");
            // 重新加载页面，页面初始化时会重新向 Java 取背景图
            loadUi();
        } catch (Throwable t) {
            doToast("设置背景失败");
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        web.setBackgroundColor(0xFF0F1115);
        web.setWebViewClient(new QqClient());
        bridge = new QqbotBridge(this);
        web.addJavascriptInterface(bridge, "Android");
        setContentView(web);
        // 老设备（安装时 NapCat WebUI token 默认还是 qqbot）先做一次 token 迁移，
        // 必须在触发 bootstrap / 续跑部署之前：部署与守护脚本读的都是 bots.json。
        // 只读一个几 KB 的小文件，绝大多数情况下立刻返回，不阻塞启动。
        try {
            migrateTokens();
        } catch (Throwable ignored) {
        }
        loadUi();
        // 最外层防掉线：常驻前台服务。没有它，应用一退到后台就会被系统当缓存进程
        // 冻结甚至杀掉，沙盒里的 NapCat/AstrBot 会「进程还在但完全没有反应」。
        // 用户关掉守护开关时 apply() 会自动跳过启动。
        try {
            QqbotGuardService.apply(this);
        } catch (Throwable ignored) {
        }
        // 首次启动时先确保 Termux bootstrap 已解包（本 Activity 现在是启动页）。
        // TermuxInstaller 是包级私有类，编译期不可见，故用反射调用。
        try {
            Class<?> c = Class.forName("com.termux.app.TermuxInstaller");
            java.lang.reflect.Method m = c.getDeclaredMethod("setupBootstrapIfNeeded", Activity.class, Runnable.class);
            m.setAccessible(true);
            m.invoke(null, this, new BootDone(this));
        } catch (Throwable t) {
            // bootstrap 已就绪或不可用时忽略，但仍尝试续跑部署（同样丢后台线程）
            new Thread(new BootSync(this)).start();
        }
    }

    public void loadUi() {
        if (web != null) web.loadUrl("file:///android_asset/qqbot/index.html");
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web != null && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    public void doCopy(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("qqbot", text));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
        }
    }

    public void doToast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    public void doOpenTerminal() {
        try {
            Intent i = new Intent();
            i.setClassName(getPackageName(), "com.termux.app.TermuxActivity");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开终端", Toast.LENGTH_SHORT).show();
        }
    }

    /** 应用内打开网页面板（NapCat WebUI / AstrBot 控制台）。 */
    public void doOpenPanel(String url) {
        try {
            Intent i = new Intent();
            i.setClassName(getPackageName(), "com.termux.app.QqbotPanelActivity");
            i.putExtra("url", url == null ? "" : url);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开面板", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 用系统浏览器打开外部链接（官网 / 群链接等）。返回空串 = 已发出跳转，
     * 否则返回给用户看的中文原因。必须跑在主线程上，见 runOnUiSync()。
     */
    public String doOpenUrl(String url) {
        String u = url == null ? "" : url.trim();
        if (u.length() == 0) return "链接为空";
        String low = u.toLowerCase(java.util.Locale.ROOT);
        // 只放行 http/https：javascript: / intent: / file: 这类 scheme 一律挡掉，
        // 它们能把外部页面变成任意本地动作，是典型的 WebView 提权入口。
        if (!(low.startsWith("http://") || low.startsWith("https://"))) return "只支持 http 链接";
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
            // 从 Activity 上下文之外也可能被调用，加上 NEW_TASK 才不会抛
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return "";
        } catch (ActivityNotFoundException e) {
            return "没有找到可用的浏览器";
        } catch (Throwable t) {
            return "无法打开链接";
        }
    }

    /**
     * 用 mqqapi scheme 拉起 QQ 的加群卡片。返回是否已经发出跳转
     * （没装 QQ / 模拟器上 startActivity 会抛，这里按「没成功」返回，由页面提示手动搜索）。
     */
    public boolean doOpenQqGroup(String group) {
        String g = group == null ? "" : group.trim();
        if (g.length() == 0) return false;
        for (int i = 0; i < g.length(); i++) {
            char c = g.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(
                    "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=" + g + "&card_type=group"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 在 UI 线程跑一个 UiTask 并拿回它的结果，供 JS 桥同步返回。
     * 桥方法跑在 WebView 自己的线程上，而 startActivity 必须在主线程；
     * 已经在主线程时直接执行（runOnUiThread 也是这个语义），不会死锁。
     * 最多等 3 秒；等不到（Activity 正在销毁等）返回 null，表示任务压根没跑，
     * 调用方据此报失败而不是误报成功。绝不让页面卡死。
     */
    public String runOnUiSync(UiTask t) {
        if (t == null) return "";
        if (Looper.myLooper() == Looper.getMainLooper()) {
            t.run();
            return t.result();
        }
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        t.setLatch(latch);
        runOnUiThread(t);
        try {
            if (!latch.await(3, java.util.concurrent.TimeUnit.SECONDS)) return null;
        } catch (InterruptedException ignored) {
            return null;
        }
        return t.result();
    }

    /** 按 pid 文件名推断那个进程的 cmdline 里该出现什么字样。 */
    private static String pidHint(File pidFile) {
        String n = (pidFile == null) ? "" : pidFile.getName();
        if (n.startsWith("deploy")) return "deploy.sh";
        if (n.startsWith("supervisor")) return "proot";
        if (n.startsWith("watchdog")) return "watchdog.sh";
        return "";
    }

    public boolean pidAlive(File pidFile) {
        return pidAlive(pidFile, pidHint(pidFile));
    }

    /** 只看 /proc/<pid> 存不存在是不够的：pid 文件是上次没清干净的残留、或者重启后那个号
     *  被别的进程占用了，界面就会显示「运行中」、点启动回 already running、
     *  maybeAutoDeploy 直接 return 什么都不做。所以再核一次 cmdline；cmdline 读不到时不否认。 */
    public boolean pidAlive(File pidFile, String hint) {
        try {
            if (pidFile == null || !pidFile.isFile()) return false;
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(pidFile), "UTF-8"));
            String s = br.readLine();
            br.close();
            if (s == null) return false;
            int pid = Integer.parseInt(s.trim());
            if (!new File("/proc/" + pid).exists()) return false;
            if (hint == null || hint.length() == 0) return true;
            if (!new File("/proc/" + pid + "/cmdline").isFile()) return true;
            return QqbotShell.cmdlineHas(pid, hint);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean portOpen(int port) {
        Socket sock = null;
        try {
            sock = new Socket();
            sock.connect(new InetSocketAddress("127.0.0.1", port), 400);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (sock != null) { try { sock.close(); } catch (Exception ignored) {} }
        }
    }

    /** 取本机局域网 IPv4（优先 wlan 网卡），供另一台设备扫码登录 NapCat 用。 */
    public String lanIp() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            String fallback = "";
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (ni == null || !ni.isUp() || ni.isLoopback()) continue;
                String nm = ni.getName();
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs != null && addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!(a instanceof Inet4Address)) continue;
                    if (a.isLoopbackAddress()) continue;
                    String ip = a.getHostAddress();
                    if (nm != null && nm.startsWith("wlan")) return ip;
                    if (fallback.length() == 0) fallback = ip;
                }
            }
            return fallback;
        } catch (Throwable ignored) {
            return "";
        }
    }

    public String runShell(String cmd, int timeoutSec) {
        return QqbotShell.run(getFilesDir(), cmd, timeoutSec);
    }

    /* 只读文件末尾真正需要的那一段。
       旧实现不管要几行，都先读满 256 KB 再整体 UTF-8 解码 + split：deployInfo() 只要 14 行、
       首页每 2 秒就要一次，却要为此付出一次 256 KB 解码的代价。改成从文件尾往前逐块数换行，
       数够 maxLines+1 个就停，再只读那一小段。 */
    public String tail(File f, int maxLines) {
        if (f == null || !f.isFile() || maxLines <= 0) return "";
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            long len = raf.length();
            if (len <= 0) return "";
            final int BLOCK = 32768;
            final long MAXREAD = 1048576L;   /* 兜底上限：单行超长时不至于把内存吃光 */
            byte[] probe = new byte[BLOCK];
            long start = len;
            long end = len;
            int seen = 0;
            while (end > 0 && seen <= maxLines && (len - end) < MAXREAD) {
                int n = (int) Math.min((long) BLOCK, end);
                long s = end - n;
                raf.seek(s);
                raf.readFully(probe, 0, n);
                for (int i = n - 1; i >= 0; i--) { if (probe[i] == '\n') seen++; }
                start = s;
                end = s;
            }
            byte[] buf = new byte[(int) (len - start)];
            raf.seek(start);
            raf.readFully(buf);
            String text = new String(buf, "UTF-8");
            if (start > 0) {
                int nl = text.indexOf('\n');
                if (nl >= 0) text = text.substring(nl + 1);
            }
            String[] all = text.split("\n", -1);
            // split 带 -1 会保留末尾空元素：日志以 \n 结尾时请求 400 行只回 399 行，
            // maxLines=1 时还会直接返回空串。末尾恰好一个空串时先丢掉它。
            if (all.length > 0 && all[all.length - 1].length() == 0) {
                String[] cut = new String[all.length - 1];
                System.arraycopy(all, 0, cut, 0, cut.length);
                all = cut;
            }
            int from = Math.max(0, all.length - maxLines);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < all.length; i++) {
                if (i > from) sb.append('\n');
                sb.append(all[i]);
            }
            return sb.toString();
        } catch (Exception e) {
            return "ERROR: " + e;
        } finally {
            if (raf != null) { try { raf.close(); } catch (Exception ignored) {} }
        }
    }

    public File deployPidFile() { return new File(qqbotDir(), "deploy.pid"); }
    public File deployDoneFile() { return new File(qqbotDir(), "deploy.done"); }

    // ===== 单组件开关 =====
    // 目录就是沙盒内 /qqbot-cache/svc（cache 被 proot -b 映射进来）：
    // 宿主机侧创建 <name>.off，沙盒内 run-all.sh 的守护循环 3 秒内就会停掉那个服务
    // 且不再拉起，另一个服务不受影响。用普通文件而不是 pid/信号，是因为它必须
    // 跨应用重启、跨容器重启都保留，而且写它不需要开 proot（毫秒级、不会卡 UI）。
    public File svcDir() { return new File(qqbotDir(), "cache/svc"); }

    public File svcFlag(String name) { return new File(svcDir(), name + ".off"); }

    public boolean svcOff(String name) {
        return "napcat".equals(name) || "astrbot".equals(name) ? svcFlag(name).isFile() : false;
    }

    /** off=true 关闭该组件（写标记），false 恢复（删标记）。 */
    public boolean setSvcOff(String name, boolean off) {
        if (!"napcat".equals(name) && !"astrbot".equals(name)) return false;
        try {
            if (off) {
                svcDir().mkdirs();
                // 写盘结果必须如实返回：写不进去（目录建不出来、只读、磁盘满）时控制台要能
                // 提示失败。以前这里无条件 return true，标记没落盘却告诉用户「已记下」，
                // 表现出来就是点了开关、界面说关了、其实什么都没发生。
                return writeFile(svcFlag(name), "off\n") && svcFlag(name).isFile();
            }
            File f = svcFlag(name);
            // 文件本来就不在 = 「打开」这个目标状态已经达成，算成功；在但删不掉才算失败。
            return !f.isFile() || f.delete();
        } catch (Throwable t) {
            return false;
        }
    }
    public File deployLogFile() { return new File(new File(qqbotDir(), "logs"), "deploy.log"); }

    public boolean isDeployRunning() {
        if (deployDoneFile().isFile()) return false;
        return pidAlive(deployPidFile());
    }

    /** 在后台启动（或续跑）完整部署流程。 */
    public String startDeploy() {
        if (deployDoneFile().isFile()) return "already deployed";
        if (pidAlive(deployPidFile())) return "already running";
        File bash = new File(usrDir(), "bin/bash");
        if (!bash.isFile()) return "termux bootstrap not ready";
        File script = new File(usrDir(), "opt/qqbot/deploy.sh");
        if (!script.isFile()) return "deploy.sh missing";
        String qh = qqbotDir().getAbsolutePath();
        // 必须用 setsid：runShell 返回后会 destroy() 掉这个 shell，而 Android 的
        // ProcessImpl.destroyProcess 是按进程组发 SIGTERM 的，同组的后台进程会被连带杀死，
        // 于是部署脚本刚起来就没了（表现为 supervisor/deploy 的 pid 写了却查不到进程）。
        runShell("mkdir -p " + qh + "/logs " + qh + "/steps; "
                + "nohup setsid bash " + usrDir().getAbsolutePath() + "/opt/qqbot/deploy.sh >> "
                + qh + "/logs/deploy.log 2>&1 < /dev/null & "
                + "sleep 2; echo ok", 25);
        return pidAlive(deployPidFile()) ? "started" : "start failed";
    }

    /** 在后台拉起机器人服务（NapCat + AstrBot）。 */
    public String startServices() {
        File bash = new File(usrDir(), "bin/bash");
        if (!bash.isFile()) return "termux bootstrap not ready";
        File pid = new File(qqbotDir(), "supervisor.pid");
        if (pidAlive(pid)) return "already running";
        String qh = qqbotDir().getAbsolutePath();
        // 同 startDeploy：setsid 脱离进程组；pid 由 start-sandbox.sh 自己写
        // （setsid 在调用者是进程组组长时会 fork，$! 指向的就不是真正的 proot）
        runShell("mkdir -p " + qh + "/logs " + qh + "/cache/logs; "
                + "nohup setsid bash " + usrDir().getAbsolutePath() + "/opt/qqbot/start-sandbox.sh > "
                + qh + "/logs/supervisor.log 2>&1 < /dev/null & "
                + "sleep 3; echo ok", 30);
        return pidAlive(pid) ? "started" : "start failed";
    }

    /** 应用启动时：没部署完就续跑部署；部署完了就确保服务在跑。 */
    public void maybeAutoDeploy() {
        try {
            if (!new File(usrDir(), "bin/bash").isFile()) return;
            if (!deployDoneFile().isFile()) {
                if (pidAlive(deployPidFile())) return;
                if (!new File(usrDir(), "opt/qqbot/deploy.sh").isFile()) return;
                startDeploy();
                return;
            }
            if (!pidAlive(new File(qqbotDir(), "supervisor.pid"))) {
                startServices();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 把 APK 里 assets/qqbot-scripts.zip 的部署脚本同步到 $PREFIX。
     *
     *  为什么需要它：Termux 的 bootstrap（含 opt/qqbot 全套脚本）只在 $PREFIX 不存在或为空时
     *  才解包一次（TermuxInstaller.setupBootstrapIfNeeded 的 whenDone.run() 提前返回）。覆盖安装
     *  新版 APK 时 $PREFIX 早就存在，opt/qqbot 下还是上一次安装那份旧脚本 —— 时区同步、看门狗、
     *  多实例守护这些全在脚本里，用户装了新版也拿不到。这里用「APK 的 lastUpdateTime」当版本标记
     *  （每次安装都会变，比 versionCode 可靠），变了就覆盖一次。
     *
     *  返回 true 表示这次确实覆盖了脚本（调用方据此决定要不要重启容器）。
     */
    public boolean syncScripts() {
        StringBuilder rep = new StringBuilder();
        try {
            File usr = usrDir();
            if (!new File(usr, "bin/bash").isFile()) return false;
            long stamp = 0;
            try {
                stamp = getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime;
            } catch (Throwable ignored) {
            }
            File mark = new File(usr, "opt/qqbot/.scripts-ver");
            if (stamp != 0 && mark.isFile()) {
                String have = readSmall(mark);
                if (have != null && have.trim().equals(String.valueOf(stamp))) return false;
            }
            InputStream in = getAssets().open("qqbot-scripts.zip");
            java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(
                    new java.io.BufferedInputStream(in, 65536));
            byte[] buf = new byte[32768];
            int n = 0;
            int bad = 0;
            java.util.zip.ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                // 只接受普通相对路径，杜绝 zip 里的 ../ 逃逸到 $PREFIX 外面
                if (name.startsWith("/") || name.indexOf("..") >= 0) { zin.closeEntry(); continue; }
                File dst = new File(usr, name);
                if (e.isDirectory()) { dst.mkdirs(); zin.closeEntry(); continue; }
                // 每个文件单独 try：一个写不进去绝不能连累后面的脚本。
                // 早先这里是整段一个 try，而设备上有几个脚本属主是 root（早期手动 adb root 推上去
                // 留下的），覆盖它们会抛 EACCES，整个循环直接中断 —— 排在后面的
                // start-sandbox.sh / watchdog.sh / run-all.sh 一个都没更新，版本标记也没写，
                // 表现就是「装了新版但新功能完全没生效」。
                try {
                    File par = dst.getParentFile();
                    if (par != null && !par.isDirectory()) par.mkdirs();
                    byte[] data = readEntryBuf(zin, buf);
                    if (!writeFileRobust(dst, data)) throw new java.io.IOException("write failed");
                    // apk 里的 zip 不带 Unix 权限。临时文件是 0600 创建的，直接改名的结果就是
                    // -rwx--x--x（组和其他用户没有读权限，脚本之间 source 会失败），所以先补上
                    // 可读位再补执行位 —— 统一成 -rwxr-xr-x。targetSdk 28 才允许改权限位。
                    dst.setReadable(true, false);
                    if (name.endsWith(".sh")) dst.setExecutable(true, false);
                    n++;
                } catch (Throwable t) {
                    bad++;
                    rep.append("FAIL ").append(name).append(" -> ").append(t).append((char) 10);
                }
                try { zin.closeEntry(); } catch (Throwable ignored) {
                }
            }
            zin.close();
            File dir = new File(usr, "opt/qqbot");
            if (!dir.isDirectory()) dir.mkdirs();
            // 有任何一个文件没写成功就不写标记：下次启动再补一遍，直到全部同步为止
            if (bad == 0) {
                try {
                    writeFileRobust(mark, String.valueOf(stamp).getBytes("UTF-8"));
                } catch (Throwable ignored) {
                }
            }
            rep.insert(0, "synced=" + n + " failed=" + bad + " stamp=" + stamp + (char) 10);
            try {
                File lg = new File(qqbotDir(), "logs/scripts.log");
                File lgpar = lg.getParentFile();
                if (lgpar != null && !lgpar.isDirectory()) lgpar.mkdirs();
                writeFileRobust(lg, rep.toString().getBytes("UTF-8"));
            } catch (Throwable ignored) {
            }
            return n > 0 && bad == 0;
        } catch (Throwable t) {
            // 老 APK / UI-Only 构建里可能没有这个 asset，静默跳过即可
            return false;
        }
    }

    /** 读完一个 zip 条目的全部字节（先整块读进内存，才好「写临时文件再改名」）。 */
    private byte[] readEntryBuf(java.util.zip.ZipInputStream zin, byte[] buf) throws java.io.IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        int k;
        while ((k = zin.read(buf)) > 0) bo.write(buf, 0, k);
        return bo.toByteArray();
    }

    /** 落盘：先写同目录的临时文件，再 rename 覆盖目标。
     *
     *  直接 new FileOutputStream(目标) 是截断重写，遇到「目标属主是 root、当前应用进程没写
     *  权限」会直接 EACCES；而 rename 只要求父目录可写，等于绕过目标文件自身的权限位。
     *  顺带一个好处：rename 是原子的，正在被执行的脚本永远读不到写了一半的内容。
     */
    private boolean writeFileRobust(File dst, byte[] data) {
        try {
            File tmp = new File(dst.getPath() + ".qqbotnew");
            FileOutputStream fo = new FileOutputStream(tmp);
            fo.write(data);
            fo.close();
            if (tmp.renameTo(dst)) return true;
            // 少数实现不允许 rename 直接覆盖已存在的目标：先删再改名
            if (dst.exists() && dst.delete() && tmp.renameTo(dst)) return true;
            tmp.delete();
        } catch (Throwable ignored) {
        }
        // 兜底：老老实实覆盖写（目标是本应用属主时一定能成功）
        try {
            FileOutputStream fo = new FileOutputStream(dst);
            fo.write(data);
            fo.close();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 读一个很小的文本文件（标记文件用），失败返回 null。 */
    private String readSmall(File f) {
        try {
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            byte[] buf = new byte[(int) Math.min(raf.length(), 256L)];
            raf.readFully(buf);
            raf.close();
            return new String(buf, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    /** 脚本刚被覆盖，容器还在用旧脚本跑：重启一次让新脚本生效。
     *  必须在线程里调用 —— stop-sandbox.sh 要等 proot 自己退（最多 30 秒）。 */
    public void restartServicesIfRunning() {
        try {
            if (!deployDoneFile().isFile()) return;
            if (pidAlive(deployPidFile())) return;
            if (!pidAlive(new File(qqbotDir(), "supervisor.pid"))) return;
            String prefix = usrDir().getAbsolutePath();
            String qh = qqbotDir().getAbsolutePath();
            runShell("bash " + prefix + "/opt/qqbot/stop-sandbox.sh >/dev/null 2>&1; mkdir -p " + qh
                    + "/logs; nohup setsid bash " + prefix + "/opt/qqbot/start-sandbox.sh > " + qh
                    + "/logs/supervisor.log 2>&1 < /dev/null & sleep 4; echo ok", 240);
        } catch (Throwable ignored) {
        }
    }
    public String defaultBots() {
        try {
            JSONObject bot = new JSONObject();
            bot.put("id", "bot1");
            bot.put("name", "主号");
            bot.put("qq", "");
            bot.put("token", "zhuxin");
            bot.put("napcatPort", 6099);
            bot.put("onebotPort", 6199);
            bot.put("enable", true);
            JSONObject root = new JSONObject();
            root.put("active", "bot1");
            // astrbotPort：0 = 自动（见 astrbotPort()），新装默认走自动
            root.put("astrbotPort", 0);
            root.put("bots", new JSONArray().put(bot));
            return root.toString();
        } catch (Exception e) {
            return "{\"active\":\"bot1\",\"bots\":[]}";
        }
    }

    // bots.json 解析失败时 readBots() 只能回落到单实例默认值，用户在这个「假配置」上点保存
    // 就会把真实的多实例配置覆盖掉。所以第一次解析失败时先把原文件备份一份，
    // 并让 writeBots() 拒绝第一次写盘（再点一次保存才真的覆盖）。
    private boolean botsCorrupt = false;
    private boolean botsCorruptAck = false;

    /** 非空字符串表示「刚发现 bots.json 损坏，已备份」，给前端换一条更准确的提示。 */
    public String botsCorruptNote() {
        if (!botsCorrupt || botsCorruptAck) return "";
        return "bots.json 已损坏，原文件备份到了 bots.json.bad；再点一次「保存」就用当前内容覆盖";
    }

    public String readBots() {
        File f = botsFile();
        if (!f.isFile()) return defaultBots();
        try {
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            byte[] buf = new byte[(int) raf.length()];
            raf.readFully(buf);
            raf.close();
            String s = new String(buf, "UTF-8").trim();
            if (s.length() == 0) return defaultBots();
            new JSONObject(s);
            return s;
        } catch (Exception e) {
            noteBotsCorrupt(f);
            return defaultBots();
        }
    }

    private void noteBotsCorrupt(File f) {
        if (botsCorrupt) return;
        botsCorrupt = true;
        try {
            if (f.isFile() && f.length() > 0) copyFile(f, new File(f.getParentFile(), "bots.json.bad"));
        } catch (Throwable ignored) {
        }
    }

    /** 手动逐块复制：不用 Files.readAllBytes —— 那是 API 26 才有的，本应用 minSdk 24。 */
    private static void copyFile(File src, File dst) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(src);
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.flush();
            } finally {
                closeQuiet(out);
            }
        } finally {
            closeQuiet(in);
        }
    }

    /** 静默关掉一个流：这些描述符来自 ContentResolver 与文件系统，漏关会一路泄漏。 */
    private static void closeQuiet(java.io.Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (Throwable ignored) {}
    }

    // 把早期版本遗留的 NapCat WebUI token 从 qqbot 迁到 zhuxin。
    // 只改前缀、下标不动：qqbot -> zhuxin，qqbot2 -> zhuxin2，qqbot3 -> zhuxin3……
    // 没有需要改的就直接返回、不写文件（避免每次启动都刷 mtime）。
    // bots.json 缺失 / 损坏 / 没有 bots 字段时静默退出，绝不抛异常。
    public void migrateTokens() {
        try {
            File f = botsFile();
            if (!f.isFile()) return;
            JSONObject root = new JSONObject(readBots());
            JSONArray arr = root.optJSONArray("bots");
            if (arr == null || arr.length() == 0) return;
            boolean changed = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject b = arr.optJSONObject(i);
                if (b == null) continue;
                String t = b.optString("token", "");
                String nt = migrateToken(t);
                if (nt == null || nt.equals(t)) continue;
                b.put("token", nt);
                changed = true;
            }
            if (!changed) return;
            // writeBots 会自己双写 home + cache 两份
            writeBots(root.toString());
        } catch (Throwable ignored) {
        }
    }

    /** qqbot / qqbot<数字> -> zhuxin / zhuxin<数字>；不需要迁移返回 null。 */
    private String migrateToken(String t) {
        if (t == null) return null;
        if (!t.startsWith("qqbot")) return null;
        String tail = t.substring("qqbot".length());
        // 只认 "qqbot" 或 "qqbot+纯数字"：qqbotx、qqbot-1 这类手工填的一律不动
        for (int i = 0; i < tail.length(); i++) {
            if (tail.charAt(i) < '0' || tail.charAt(i) > '9') return null;
        }
        if (tail.length() > 0 && tail.charAt(0) == '0') return null;   // qqbot0 看着像手工值，不碰
        return "zhuxin" + tail;
    }

    // ---- AstrBot 控制台端口 ----
    // bots.json 顶层的 astrbotPort：0（或缺失/非法）表示「自动」，实际使用 6185。
    // 老的 bots.json 没有这个键，optInt 默认 0 → 自动，和以前的行为一致。

    /** 读 bots.json 里的 astrbotPort 原始值，0 表示「自动」。 */
    public int astrbotPortRaw() {
        try {
            return astrbotPortRawOf(new JSONObject(readBots()));
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 实际使用的 AstrBot 控制台端口：自动（0/缺失）时回落 6185。 */
    public int astrbotPort() {
        int p = astrbotPortRaw();
        return p == 0 ? 6185 : p;
    }

    /* ---- 上面两个的重载版：吃已经解析好的 bots.json 根对象 ----
       state() 每 4 秒跑一次，原来为了拿 astrbotPort / astrbotPortAuto / bots 三个值，
       把同一份 bots.json 读了 3 遍、JSON 也解析了 3 遍。桥层改成只读一次，把根对象传进来复用。
       无参版本保留原语义，别处调用照旧。 */

    /** 从已解析的 bots.json 根对象取 astrbotPort 原始值，0 表示「自动」。 */
    public int astrbotPortRawOf(JSONObject root) {
        if (root == null) return 0;
        int p = root.optInt("astrbotPort", 0);
        return (p > 0 && p < 65536) ? p : 0;
    }

    /** 从已解析的 bots.json 根对象取实际使用的 AstrBot 控制台端口。 */
    public int astrbotPortOf(JSONObject root) {
        int p = astrbotPortRawOf(root);
        return p == 0 ? 6185 : p;
    }

    // 写盘前先规范化：前端传来的 bots 数组来自 state()，每个实例都带着运行时
    // 派生的 up 字段（端口探测结果），原样写回会把过期状态固化到 bots.json，
    // 所以这里只保留持久化字段，丢掉其它键。
    // 另外加一道保护：删除实例只由 delbot.sh 负责，页面的任何保存动作都不会
    // 删实例；若这次写入会让磁盘上已有的实例消失，说明前端拿着过期副本，拒绝。
    public boolean writeBots(String json) {
        try {
            JSONObject in = new JSONObject(json);
            JSONArray arr = in.optJSONArray("bots");
            JSONArray clean = new JSONArray();
            if (arr != null) {
                // 先收集这次要写入的所有「非 0」端口：自动分配时必须避开它们。
                // （只靠边走边加不够——后面实例显式写的端口会被漏掉，可能撞车。）
                java.util.Set<Integer> used = new java.util.HashSet<Integer>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject b = arr.optJSONObject(i);
                    if (b == null) continue;
                    int np0 = b.optInt("napcatPort", 0);
                    if (np0 > 0) used.add(Integer.valueOf(np0));
                    int op0 = b.optInt("onebotPort", 0);
                    if (op0 > 0) used.add(Integer.valueOf(op0));
                }
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject b = arr.optJSONObject(i);
                    if (b == null) continue;
                    JSONObject c = new JSONObject();
                    c.put("id", b.optString("id", ""));
                    c.put("name", b.optString("name", ""));
                    c.put("qq", b.optString("qq", ""));
                    // token 缺失时的默认值必须和 defaultBots() 一致，否则前端漏传
                    // token 保存一次就会被写成旧默认值 qqbot。
                    c.put("token", b.optString("token", "zhuxin"));
                    // napcatPort / onebotPort 不允许留 0 落盘：沙盒 run-napcat.sh 用的是
                    // int(b.get('napcatPort') or 6099)，前端允许输入框留空（= 0 = 自动），
                    // 两个实例都留 0 就会一起回退到 6099/6199 抢同一个 WebUI 端口，
                    // 第二个 NapCat 起不来。所以这里先把 0 解析成真正空闲的端口再写。
                    int np = b.optInt("napcatPort", 0);
                    if (np <= 0) {
                        np = pickFreePort(6099, true, used);
                        if (np <= 0) np = 6099;   // 一个都试不出来就按沙盒约定回退，绝不留 0
                    }
                    used.add(Integer.valueOf(np));
                    int op = b.optInt("onebotPort", 0);
                    if (op <= 0) {
                        op = pickFreePort(np + 100, false, used);
                        if (op <= 0) op = np + 100;
                    }
                    used.add(Integer.valueOf(op));
                    c.put("napcatPort", np);
                    c.put("onebotPort", op);
                    c.put("enable", b.optBoolean("enable", true));
                    clean.put(c);
                }
            }
            if (clean.length() == 0) return false;
            // bots.json 损坏过、用户还没确认过的话先拒绝这一笔：此时界面上显示的是
            // readBots() 回落出来的单实例默认值，直接写下去就把真实配置抹了。
            if (botsCorrupt && !botsCorruptAck) {
                botsCorruptAck = true;
                return false;
            }
            String act = in.optString("active", "");
            // astrbotPort 也是持久化字段，必须跟着一起落盘。默认 0 = 「自动」而不是 6185：
            // 存回来的是「自动」时不能在这里被悄悄固化成 6185。
            int ap = in.optInt("astrbotPort", 0);
            if (ap < 0 || ap > 65535) ap = 0;
            try {
                JSONObject oldRoot = new JSONObject(readBots());
                JSONArray oldArr = oldRoot.optJSONArray("bots");
                if (act.length() == 0) act = oldRoot.optString("active", "");
                if (oldArr != null && oldArr.length() > clean.length()) {
                    for (int i = 0; i < oldArr.length(); i++) {
                        JSONObject ob = oldArr.optJSONObject(i);
                        if (ob == null) continue;
                        String oid = ob.optString("id", "");
                        boolean kept = false;
                        for (int j = 0; j < clean.length(); j++) {
                            JSONObject cb = clean.optJSONObject(j);
                            if (cb != null && oid.equals(cb.optString("id", ""))) {
                                kept = true;
                                break;
                            }
                        }
                        if (!kept) return false;
                    }
                }
            } catch (Throwable ignored) {
            }
            JSONObject out = new JSONObject();
            out.put("active", act);
            out.put("astrbotPort", ap);
            out.put("bots", clean);
            String text = out.toString();
            File d = qqbotDir();
            if (!d.isDirectory()) d.mkdirs();
            writeFile(botsFile(), text);
            File cacheDir = new File(qqbotDir(), "cache");
            if (!cacheDir.isDirectory()) cacheDir.mkdirs();
            writeFile(new File(cacheDir, "bots.json"), text);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // 挑一个空闲端口：从 start 开始，downward=true 就往下数，否则往上数。
    // used 里是本次已经安排出去的端口（同一个端口不能分给两个实例）；portOpen() 探的是
    // 进程真实占用，手机里别的程序占着的端口也一并避开——前端只看 bots.json，看不到别的进程。
    // 最多试 100 个候选（napcat 从 6099 往下正好覆盖 6099..6000），找不到返回 0，
    // 由调用方决定回退值，绝不在这里死循环。writeBots() 与 autoPorts() 共用这一套分配逻辑。
    private int pickFreePort(int start, boolean downward, java.util.Set<Integer> used) {
        for (int i = 0; i < 100; i++) {
            int p = downward ? (start - i) : (start + i);
            if (p <= 0 || p > 65535) break;
            if (used != null && used.contains(Integer.valueOf(p))) continue;
            if (portOpen(p)) continue;
            return p;
        }
        return 0;
    }

    // 自动分配端口：AstrBot 控制台 + 每个实例的 NapCat WebUI / OneBot，并把结果写回
    // bots.json（home + cache 两份），返回一句中文说明给前端 toast。
    // 规则沿用前端 nextFreePorts() 的约定（WebUI 从 6099 往下找，OneBot = WebUI + 100）。
    //
    // 关键：**已经配好的端口要尽量原样保留**。portOpen() 探的是真实占用，而每个实例
    // 自己正在监听的端口当然也是「开着的」；如果一律当成冲突跳过，在健康的系统上点一次
    // 「自动分配空闲端口」就会把 6098/6198/6186 全换成出厂默认的 6099/6199/6185 ——
    // 配置写盘了、容器却还在旧端口上跑，state() 按新端口探测，两个状态灯立刻变红
    // 「离线」，用户以为自己把服务点坏了。所以顺序是：先沿用现值（只要没和本次已经
    // 分配出去的端口撞车），确实装不下时才重新挑。
    public String autoPorts() {
        try {
            JSONObject root = new JSONObject(readBots());
            JSONArray arr = root.optJSONArray("bots");
            // 本次已经分配出去的端口，保证同一个端口不会分给两个实例
            java.util.HashSet<Integer> used = new java.util.HashSet<Integer>();

            // AstrBot 控制台：优先沿用当前值，否则从 6185 起向上找第一个空闲的
            int oldAstr = root.optInt("astrbotPort", 0);
            int astrPort = (oldAstr > 0 && !used.contains(Integer.valueOf(oldAstr)))
                    ? oldAstr : pickFreePort(6185, false, used);
            if (astrPort <= 0) astrPort = 6185;
            // 先占住，后面给实例分 OneBot 端口时自然会避开它
            used.add(Integer.valueOf(astrPort));
            boolean changed = (astrPort != oldAstr);

            StringBuilder sb = new StringBuilder();
            sb.append("已分配端口：AstrBot 控制台 ").append(astrPort);
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject b = arr.optJSONObject(i);
                    if (b == null) continue;
                    int oldNap = b.optInt("napcatPort", 0);
                    int oldOne = b.optInt("onebotPort", 0);
                    int nap;
                    int one;
                    if (oldNap > 0 && oldOne > 0
                            && !used.contains(Integer.valueOf(oldNap))
                            && !used.contains(Integer.valueOf(oldOne))) {
                        // 沿用这个实例现有的两个端口（它们正被它自己的容器占着）
                        nap = oldNap;
                        one = oldOne;
                    } else {
                        // NapCat WebUI：6099 起往下找第一个空闲的（最多 100 个，覆盖 6099..6000）
                        nap = pickFreePort(6099, true, used);
                        if (nap <= 0) nap = 6099;
                        used.add(Integer.valueOf(nap));
                        // OneBot：WebUI + 100 起往上找；AstrBot 控制台已经在 used 里，会自动避开
                        one = pickFreePort(nap + 100, false, used);
                        if (one <= 0) one = nap + 100;
                    }
                    used.add(Integer.valueOf(nap));
                    used.add(Integer.valueOf(one));
                    if (nap != oldNap || one != oldOne) changed = true;
                    b.put("napcatPort", nap);
                    b.put("onebotPort", one);
                    String nm = b.optString("name", "");
                    if (nm.length() == 0) nm = b.optString("id", "实例");
                    sb.append('；').append(nm).append(" WebUI ").append(nap)
                      .append(" / OneBot ").append(one);
                }
            }
            root.put("astrbotPort", astrPort);
            // 交给 writeBots 落盘：它会只保留持久化字段（丢掉 up 等运行时字段）并同时写
            // home + cache 两份，另外还有一道「不许把已有实例写没」的保护。
            if (!writeBots(root.toString())) return "端口分配失败：无法写入 bots.json";
            // 端口一个字都没变时要说清楚，否则用户以为点了没反应
            if (!changed) return sb + "（与当前一致，不需要重启）";
            return sb + "。点「保存并重启服务」生效";
        } catch (Throwable t) {
            return "端口分配失败：" + t;
        }
    }

    // 返回某个 NapCat 实例的登录二维码（NapCat 会把二维码存成 cache/qrcode.png）。
    // 直接读文件并转成 data: URL 交给 WebView，避免 file:// 跨源被拦。
    public String qr(String wantId) {
        try {
            JSONObject bots = new JSONObject(readBots());
            JSONArray arr = bots.optJSONArray("bots");
            String active = bots.optString("active", "");
            if (wantId == null || wantId.length() == 0) wantId = active;
            String foundId = wantId;
            // 与 run-napcat.sh 的 QQ 目录选择保持一致：实例一旦建好自己的
            // /opt/qqbot-inst/<id>/QQ 副本就一直用它；没有副本的实例里，只有
            // 「当前实例」才占用 /opt/QQ。
            File ub = new File(homeDir(), "ubuntu");
            File inst = new File(new File(new File(ub, "opt/qqbot-inst"), wantId + "/QQ"),
                    "resources/app/app_launcher/napcat/cache/qrcode.png");
            File primary = new File(new File(ub, "opt/QQ"),
                    "resources/app/app_launcher/napcat/cache/qrcode.png");
            File found = null;
            if (inst.isFile()) {
                found = inst;
            } else if (wantId.equals(active) || active.length() == 0) {
                found = primary;
            }
            JSONObject o = new JSONObject();
            o.put("id", foundId);
            o.put("active", active);
            if (found == null || !found.isFile() || found.length() <= 0) {
                o.put("ok", false);
                return o.toString();
            }
            RandomAccessFile raf = new RandomAccessFile(found, "r");
            byte[] buf = new byte[(int) raf.length()];
            raf.readFully(buf);
            raf.close();
            o.put("ok", true);
            o.put("mtime", found.lastModified());
            o.put("data", "data:image/png;base64," + Base64.encodeToString(buf, Base64.NO_WRAP));
            return o.toString();
        } catch (Throwable t) {
            return "{\"ok\":false}";
        }
    }

    // ---------------- 首次启动免责声明 ----------------
    // 页面在初始化时问一次 Java「是否同意过」，需要弹窗就调 ackDisclaimer() 落一个标记。
    // 标记内容写 "1"，只有显式写了 "0" 才当作「没同意」（"0" 留给将来做「重置」用）。

    /** 是否已经同意过免责声明：文件存在且内容不是 "0"。 */
    public boolean isDisclaimerAcked() {
        try {
            File f = disclaimerFile();
            if (!f.isFile()) return false;
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            long len = raf.length();
            byte[] buf = new byte[(int) Math.min(len, 64L)];
            raf.readFully(buf);
            raf.close();
            return !"0".equals(new String(buf, "UTF-8").trim());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 记下「已同意」；写盘结果如实返回（写不进去时页面下次启动还会再弹一次）。 */
    public boolean ackDisclaimer() {
        try {
            File d = qqbotDir();
            if (!d.isDirectory()) d.mkdirs();
            if (!writeFile(disclaimerFile(), "1\n") || !disclaimerFile().isFile()) return false;
            // 同 writeBots：cache 目录被 proot -b 映射成沙盒里的 /qqbot-cache，
            // 两份都写，沙盒里的脚本也就能看到这个标记。
            File cacheDir = new File(qqbotDir(), "cache");
            if (!cacheDir.isDirectory()) cacheDir.mkdirs();
            writeFile(new File(cacheDir, "disclaimer.ok"), "1\n");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------- 下载代理 ----------------
    // 用户在设置页填一个代理地址，这里只负责持久化；沙盒里的脚本读 /qqbot-cache/proxy.txt
    // 让它生效。空串 = 不走代理（删除文件），绝不把空串写进文件里。

    /** 读当前代理，没有或读失败一律返回空串（绝不抛异常）。 */
    public String readProxy() {
        try {
            File f = proxyFile();
            if (!f.isFile()) return "";
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            long len = raf.length();
            byte[] buf = new byte[(int) Math.min(len, 4096L)];
            raf.readFully(buf);
            raf.close();
            return new String(buf, "UTF-8").trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 保存代理：只接受 http:// / https:// / socks5:// / socks5h:// 开头（大小写不敏感），
     * 或者 "host:port" 形式（自动补 http://）；其它一律拒绝并不改盘上的值。
     * 传空串表示关掉代理（删除文件）。返回是否按预期落盘。
     */
    public boolean writeProxy(String v) {
        try {
            String s = v == null ? "" : v.trim();
            if (s.length() == 0) {
                // 目标状态 = 没有代理文件：本来就不在也算成功，在但删不掉才算失败
                File f = proxyFile();
                boolean ok = !f.isFile() || f.delete();
                File cf = new File(new File(qqbotDir(), "cache"), "proxy.txt");
                if (cf.isFile()) cf.delete();
                return ok;
            }
            String low = s.toLowerCase(java.util.Locale.ROOT);
            if (!(low.startsWith("http://") || low.startsWith("https://")
                    || low.startsWith("socks5://") || low.startsWith("socks5h://"))) {
                // 没写协议时允许 "127.0.0.1:7890" / "proxy.local:8080" 这种 host:port，补上 http://
                if (s.indexOf("://") >= 0) return false;
                // 严格整串匹配：host 部分只允许字母数字与 . _ -，端口必须全是数字
                if (!java.util.regex.Pattern.matches("[A-Za-z0-9._-]+:[0-9]+", s)) return false;
                s = "http://" + s;
            }
            String text = s + "\n";
            File d = qqbotDir();
            if (!d.isDirectory()) d.mkdirs();
            if (!writeFile(proxyFile(), text) || !proxyFile().isFile()) return false;
            // 同 writeBots：cache 目录 = 沙盒里的 /qqbot-cache，脚本从那里读
            File cacheDir = new File(qqbotDir(), "cache");
            if (!cacheDir.isDirectory()) cacheDir.mkdirs();
            // cache 目录 = 沙盒里的 /qqbot-cache，代理是脚本真正从那里读的：
            // 只检查宿主侧那份，缓存写失败时界面照样报「代理已保存」而沙盒仍走直连。
            if (!writeFile(new File(cacheDir, "proxy.txt"), text)) return false;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------- 自定义背景图 ----------------
    // 用户从系统图库选一张图片，拷成 $QQBOT_HOME/bg.img，页面初始化时通过
    // bgImage() 取回并转成 data: URL 交给 WebView（避免 file:// 跨源被拦）。

    public File bgFile() { return new File(qqbotDir(), "bg.img"); }

    public String bgImage() {
        try {
            File f = bgFile();
            if (!f.isFile() || f.length() <= 0) return "{\"ok\":false}";
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            byte[] buf = new byte[(int) raf.length()];
            raf.readFully(buf);
            raf.close();
            String mime = "image/png";
            if (buf.length > 2 && (buf[0] & 0xFF) == 0xFF && (buf[1] & 0xFF) == 0xD8) {
                mime = "image/jpeg";
            } else if (buf.length > 12 && buf[8] == 'W' && buf[9] == 'E' && buf[10] == 'B' && buf[11] == 'P') {
                mime = "image/webp";
            }
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("data", "data:" + mime + ";base64," + Base64.encodeToString(buf, Base64.NO_WRAP));
            return o.toString();
        } catch (Throwable t) {
            return "{\"ok\":false}";
        }
    }

    /** 打开系统图库选一张背景图（结果在 onActivityResult 里处理）。 */
    public void doPickBg() {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(i, "选择背景图片"), REQ_PICK_BG);
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开图库", Toast.LENGTH_SHORT).show();
        }
    }

    /** 删除自定义背景图，回到预设渐变背景。 */
    public String clearBg() {
        try {
            File f = bgFile();
            if (f.isFile()) f.delete();
            return "{\"ok\":true}";
        } catch (Throwable t) {
            return "{\"ok\":false}";
        }
    }

    /** 写文本文件，返回是否真的写成功（调用方要看结果时别再把失败当成功）。 */
    private boolean writeFile(File f, String text) {
        try {
            RandomAccessFile raf = new RandomAccessFile(f, "rw");
            raf.setLength(0);
            raf.write(text.getBytes("UTF-8"));
            raf.close();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
