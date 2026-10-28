package com.termux.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
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
    // 通知栏「看日志 / 扫码」带进来的 tab（logs / bots），页面加载完由 applyPendingTab 消费
    private String pendingTab = "";

    /* 通知栏「下线」/小组件「停止」/磁贴「停止」都不再直接停服，而是把页面拉到前台的
       「紧急下线」二次确认；确认动作在页面里调 Android.emergencyStop()。
       标记放在 Activity 上，页面加载完由 applyPendingStop() 消费（同 pendingTab 的套路）。 */
    private volatile boolean pendingStop = false;

    /* 小组件 / 磁贴的「启动 · 停止」按钮统一走这里：入口拿不准当前状态（小组件的
       PendingIntent 是渲染时定死的），所以只把面板叫到前台并带 toggle=1，
       由页面读一次 Android.state() 自己决定弹确认还是直接拉起。 */
    private volatile boolean pendingToggle = false;

    /* 桌面头像图标只在本次 Activity 生命周期里刷一次：onResume 会因为切后台、
       键盘弹出之类的原因反复触发，每次都去读头像 + 问系统要 pinned 列表纯属浪费。 */
    private boolean iconRefreshTried = false;

    public File filesDirPub() { return getFilesDir(); }
    public File usrDir() { return new File(getFilesDir(), "usr"); }
    public File homeDir() { return new File(getFilesDir(), "home"); }
    public File qqbotDir() { return new File(homeDir(), ".qqbot"); }

    /* 页面底色：窗口背景要跟它一致，否则冷启动 / 页面重载的第一帧会先露出
       Theme.Material.NoActionBar 的 windowBackground —— 那是 #FF303030 的灰，
       页面本来是深色 #05060A，一闪就是用户说的「灰屏」。页面每次换主题 / 换底色
       都会通过 Android.pageBg() 把 getComputedStyle(body).backgroundColor 报上来，
       这里存成 prefs，下一次开窗口就直接用它。 */
    public int pageBgColor() {
        int dflt = 0xFF05060A;
        try {
            android.content.res.Configuration c = getResources().getConfiguration();
            if ((c.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    != android.content.res.Configuration.UI_MODE_NIGHT_YES) {
                dflt = 0xFFF3F6FC;
            }
        } catch (Throwable ignored) {
        }
        try {
            return getSharedPreferences("qqbot", 0).getInt("pageBg", dflt);
        } catch (Throwable t) {
            return dflt;
        }
    }

    /** 把窗口和 WebView 的底色刷成页面底色（窗口那层就是「灰屏」露出来的地方）。 */
    public void applyPageBg(int color) {
        try {
            getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(color));
        } catch (Throwable ignored) {
        }
        try {
            if (web != null) web.setBackgroundColor(color);
        } catch (Throwable ignored) {
        }
    }

    /** 页面报上来的 CSS 颜色（#RRGGBB 或 rgb(r,g,b)）；解析不了就忽略。 */
    public void onPageBg(String css) {
        int c = parseCssColor(css);
        if (c == 0) return;
        try {
            android.content.SharedPreferences sp = getSharedPreferences("qqbot", 0);
            if (sp.getInt("pageBg", 0) != c) sp.edit().putInt("pageBg", c).apply();
        } catch (Throwable ignored) {
        }
        applyPageBg(c);
    }

    /** getComputedStyle 给的是 rgb(r, g, b)，Color.parseColor 只认 #form。 */
    private static int parseCssColor(String s) {
        if (s == null) return 0;
        s = s.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.length() == 0) return 0;
        try {
            if (s.startsWith("#")) return android.graphics.Color.parseColor(s);
            if (s.startsWith("rgb")) {
                int a = s.indexOf('(');
                int b = s.indexOf(')', a + 1);
                if (a < 0 || b < 0) return 0;
                String[] px = s.substring(a + 1, b).split(",");
                if (px.length < 3) return 0;
                int r = Math.round(Float.parseFloat(px[0].trim()));
                int g = Math.round(Float.parseFloat(px[1].trim()));
                int bl = Math.round(Float.parseFloat(px[2].trim()));
                int al = 255;
                if (px.length >= 4) al = Math.round(Float.parseFloat(px[3].trim()) * 255f);
                return (al << 24) | (r << 16) | (g << 8) | bl;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }
    public File logsDir() { return new File(qqbotDir(), "cache/logs"); }
    public File botsFile() { return new File(qqbotDir(), "bots.json"); }
    // 免责声明是否已同意（"0" = 未同意，其它非空内容 = 已同意）
    public File disclaimerFile() { return new File(qqbotDir(), "disclaimer.ok"); }
    // 下载代理（host 侧；沙盒内通过 /qqbot-cache/proxy.txt 读到同一份内容）
    public File proxyFile() { return new File(qqbotDir(), "proxy.txt"); }
    public Activity activity() { return this; }

    /** 选背景图用的 requestCode（必须 >= 0）。 */
    private static final int REQ_PICK_BG = 7001;
    /** 功能 50：备份「另存为」的落点（ACTION_CREATE_DOCUMENT）。 */
    private static final int REQ_BACKUP_SAVE = 7002;
    /** 功能 50：恢复时选备份包（ACTION_OPEN_DOCUMENT）。 */
    private static final int REQ_RESTORE_OPEN = 7003;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // 备份/恢复的结果交给 BackupFlow：它只起后台线程并推 window.backupPicked /
        // window.restorePicked，和下面的背景图处理互不干扰，所以必须先分流。
        if (requestCode == REQ_BACKUP_SAVE) {
            BackupFlow.onSaveResult(this, resultCode, data);
            return;
        }
        if (requestCode == REQ_RESTORE_OPEN) {
            BackupFlow.onRestoreResult(this, resultCode, data);
            return;
        }
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
        // 先把窗口底色换成页面底色，再走 super：系统画的第一帧就是页面自己的颜色，
        // 不再是 Theme.Material.NoActionBar 默认的 #303030 灰。
        applyPageBg(pageBgColor());
        super.onCreate(savedInstanceState);
        // Android 13+ 通知权限：不申请的话常驻通知（守护卡片）不会出现，
        // 用户也就看不到「重启 / 扫码 / 关闭进程」。targetSdk 28 时系统不会自动弹，只能自己请求。
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7301);
            }
        } catch (Throwable ignored) {
        }
        // WebView 远程调试：只在 .qqbot/.webdebug 存在时打开（adb 里 touch 一下就开，删掉就关）。
        // 平时默认关闭，避免发行包里长期暴露页面给任何连着 USB 调试的机器；
        // 排查界面问题（元素坐标、点击落点、JS 异常）时没有它基本只能靠截图猜。
        try {
            if (new File(qqbotDir(), ".webdebug").isFile()) WebView.setWebContentsDebuggingEnabled(true);
        } catch (Throwable ignored) {}
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        // —— 关掉安卓 12+ 的 overscroll 拉伸 ——
        // 安卓 12 起（targetSdk 31+ / 部分厂商 ROM 更早）滚动到边界会出现「拉伸回弹」效果：
        // 它由 View 的 EdgeEffect 直接拉伸 WebView 这一整块**表面**，被拉长的是原生视图，
        // 页面里 position:fixed 的背景层（#bgLayer）和底栏 nav 也一起被拉长/漂移，
        // 用户看到的就是「背景和底部条跟着滑动被拉伸」。这发生在合成阶段，CSS 管不到
        // （::-webkit-scrollbar / overscroll-behavior 都拦不住），只能在原生这层关：
        // setOverScrollMode(NEVER) 关掉边缘效果，滚动条也一起关（WebView 自带的滚动条
        // 是 View 画的，CSS 的 ::-webkit-scrollbar{display:none} 在真机上无效）。
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setBackgroundColor(pageBgColor());
        // 带上 this：通知栏「看日志 / 扫码」会带 tab extra 唤起本页，页面加载完要自动切过去
        web.setWebViewClient(new QqClient(this));
        bridge = new QqbotBridge(this);
        web.addJavascriptInterface(bridge, "Android");
        // 1.3.4-beta.2 起新增一组旁路桥（状态通知 / 系统分享 / 插件市场 / 模型测试 / 诊断），
        // 拆成独立类是为了不把 QqbotBridge 撑到不可维护，注册顺序与老桥无关。
        web.addJavascriptInterface(new QqbotNotifyBridge(this), "AndroidNotify");
        web.addJavascriptInterface(new QqbotShare(this), "AndroidShare");
        web.addJavascriptInterface(new QqbotModelBridge(this), "AndroidModel");
        web.addJavascriptInterface(new QqbotDiagBridge(this), "AndroidDiag");
        // 1.3.4-beta.3：应用内更新下载器 / 运行健康（熔断·备份·定时重启）。
        // 都是独立类，注册在这里，页面上按 window.AndroidUpdater|AndroidHealth 用。
        // 1.3.4-beta.9：AndroidIsland 已下线（灵动岛整套删除，改由前台常驻通知保活）。
        web.addJavascriptInterface(new QqbotUpdater(this), "AndroidUpdater");
        web.addJavascriptInterface(new QqbotHealthBridge(this), "AndroidHealth");
        // 1.3.4-beta.7：模型用量（只读 data_v4.db）/ 端口检测 / 每日早报。
        // 插件页已下线（底栏不再有入口），AndroidPlugins 桥一并移除。
        web.addJavascriptInterface(new QqbotUsageBridge(this), "AndroidUsage");
        web.addJavascriptInterface(new QqbotPortBridge(this), "AndroidPort");
        web.addJavascriptInterface(new QqbotDailyReport(this), "AndroidDaily");
        setContentView(web);
        // 通知栏点进来的 tab 要在第一次 loadUi 之前就记下来（singleTask 重入走 onNewIntent）
        pendingTab = tabOfIntent(getIntent());
        pendingStop = stopOfIntent(getIntent());
        pendingToggle = toggleOfIntent(getIntent());
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
        // 但 state/offline 还在时不能拉：那说明用户刚按过通知栏「关闭进程」或界面上的
        // 「紧急下线」，这次打开只是想把界面翻出来看一眼。服务一旦被拉起，
        // QqbotGuardTask 的 60 秒自检马上就会把沙盒重新启动，「彻底关掉」就白按了。
        // 标记只由用户显式「启动」时清除（SandboxStart / start-sandbox.sh），这里绝不能删。
        //
        // 但通知栏「关闭进程」是另一回事：它只是「现在别在后台呆着」，用户重开应用就该照常跑起来
        // （原话：「关闭之后 再进软件不需要再点什么启动 他自己启动」）。那种关闭会留下
        // state/closed 便条，这里见到就先把它和 offline 标记一起清掉，下面的守护与应用内自启动
        // 就会正常把沙盒拉起来，用户不用再点「启动服务」。
        // 唯一的小缝：如果用户在这 1~2 秒内立刻重开，stop-sandbox.sh 可能还在收尾，会把刚起来的
        // 沙盒再掐一次 —— 看门狗 40 秒内会重新拉起，不用手点。
        try {
            File killNote = StateIO.file(getFilesDir(), "closed");
            if (killNote.isFile()) {
                StateIO.delete(killNote);
                StateIO.delete(StateIO.file(getFilesDir(), "offline"));
            }
        } catch (Throwable ignored) {
        }
        boolean closedByUser = false;
        try {
            closedByUser = StateIO.file(getFilesDir(), "offline").isFile();
        } catch (Throwable ignored) {
        }
        if (!closedByUser) {
            try {
                QqbotGuardService.apply(this);
            } catch (Throwable ignored) {
            }
        }
        // v1.1.30：崩溃 / 匿名统计的真实上报（冷启动链路）。
        // 两个开关都关着时这里只读一次 prefs 就返回：不写计数、不解析 DNS、一个字节都不发；
        // 开着也只读两个几百字节的小文件 + 起后台线程，主线程照样零网络。
        try {
            UploadState.coldStart(this);
        } catch (Throwable ignored) {
        }
        // 先建好 /data/data/<pkg>/u -> files/usr 之类的短路径别名：
        // bootstrap 里的 249 个 ELF 把前缀原地改写成 /data/data/<pkg>/u（文件长度不变），
        // 没有这两个符号链接，bin/bash 的 RUNPATH 会悬空。必须早于 bootstrap 解包。
        try {
            PrefixAlias.ensure(this);
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
            // bootstrap 已就绪或不可用时忽略，但仍尝试续跑部署（同样丢后台线程）。
            // 打一条日志：静默吞掉过一次，结果包名改造后 bootstrap 根本没解包，
            // 界面上只看到「还没部署」，logcat 里一条线索都没有。
            android.util.Log.e("QqbotBoot", "setupBootstrapIfNeeded failed", t);
            new Thread(new BootSync(this)).start();
        }
    }

    public void loadUi() {
        if (web != null) web.loadUrl("file:///android_asset/qqbot/index.html");
    }

    /**
     * 从 Intent 里取 tab（通知栏「看日志」「扫码」：extra tab=logs / tab=bot）。
     * 页面里的标签按钮只有 home/bots/logs/more 四种，所以「bot」映射到「bots」，
     * 其它值一律丢掉——这个值最终会拼进 JS，必须白名单过滤，不能原样透传。
     */
    private static String tabOfIntent(Intent i) {
        if (i == null) return "";
        String t;
        try {
            t = i.getStringExtra("tab");
        } catch (Throwable e) {
            return "";
        }
        if (t == null) return "";
        t = t.trim().toLowerCase(java.util.Locale.ROOT);
        if ("bot".equals(t)) return "bots";
        if ("home".equals(t) || "bots".equals(t) || "logs".equals(t) || "more".equals(t)) return t;
        return "";
    }

    /**
     * 页面加载完（或被 onNewIntent 唤起）时把待处理的 tab 切过去。
     * 直接点页面上的 data-tab 按钮，不改 index.html：标签切换逻辑只有页面自己知道，
     * 从外面调用 click() 等价于用户手点，不会和页面内部状态打架。
     */
    public void applyPendingTab(WebView w) {
        String tab = pendingTab;
        if (w == null || tab == null || tab.length() == 0) return;
        pendingTab = "";
        try {
            String js = "(function(){try{var b=document.querySelector('[data-tab=\"" + tab
                    + "\"]');if(b)b.click();}catch(e){}})()";
            w.evaluateJavascript(js, null);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        // singleTask：从通知栏再点进来不会走 onCreate，只能在这里接新的 tab
        setIntent(i);
        pendingTab = tabOfIntent(i);
        pendingStop = pendingStop || stopOfIntent(i);
        pendingToggle = pendingToggle || toggleOfIntent(i);
        if (web != null) applyPendingTab(web);
        if (web != null) applyPendingStop(web);
        if (web != null) applyPendingToggle(web);
    }

    /**
     * 每次回到前台顺手把桌面上的头像快捷方式刷新一下（登录换号之后桌面图标要跟着换脸）。
     * 只做一次，且丢后台线程：读头像文件、ShortcutManager.getPinnedShortcuts() 都是阻塞调用，
     * 放主线程会在冷启动时掉帧。整段包在 try/catch(Throwable) 里 —— 图标是装饰，
     * 任何失败都不允许影响主流程。
     */
    @Override
    protected void onResume() {
        super.onResume();
        // 切回前台立刻要一帧：WebView 的硬件表面在后台会被丢掉，不主动 invalidate
        // 就得等它自己排到下一轮，肉眼就是「先空一块再出来」。
        try {
            if (web != null) {
                web.resumeTimers();
                web.invalidate();
            }
        } catch (Throwable ignored) {
        }
        applyPageBg(pageBgColor());
        if (iconRefreshTried) return;
        iconRefreshTried = true;
        try {
            new Thread(new IconRefreshTask(this)).start();
        } catch (Throwable ignored) {
        }
    }

    /** 刷桌面头像图标的线程体；构造时用的 Activity 只用来取系统服务，不长期持有。 */
    private static final class IconRefreshTask implements Runnable {
        private final QqbotActivity act;

        IconRefreshTask(QqbotActivity act) {
            this.act = act;
        }

        public void run() {
            try {
                AstrIconShortcut.refresh(act);
            } catch (Throwable ignored) {
                // 系统不支持 / 用户没钉过 / 被限流：全都静默
            }
        }
    }

    /**
     * 是不是「启停切换」的启动：小组件 / 磁贴带 extra toggle=1 打开本页。
     */
    private static boolean toggleOfIntent(Intent i) {
        if (i == null) return false;
        try {
            return i.getBooleanExtra("toggle", false);
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 是不是「请用户确认紧急下线」的启动：通知栏「下线」、小组件「停止」、磁贴「停止」
     * 都用 ACTION_VIEW 打开本页并带 extra confirm_stop=1。
     */
    private static boolean stopOfIntent(Intent i) {
        if (i == null) return false;
        try {
            return i.getBooleanExtra("confirm_stop", false);
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 页面加载完（或被唤起）后弹「紧急下线」确认。页面没接住就重试两次：
     * WebView 还没 finished 时 evaluateJavascript 会被丢弃，一次没弹出来就等于没入口。
     * 重试用具名类 AskStop 而不是匿名 Runnable —— d8 处理匿名内部类会崩（见 QqClient 注释）。
     */
    public void applyPendingStop(WebView w) {
        if (w == null || !pendingStop) return;
        pendingStop = false;
        try {
            w.postDelayed(new AskStop(w, 3), 250L);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 页面加载完（或被唤起）后处理「启停切换」：页面自己读状态决定弹确认还是启动。
     */
    public void applyPendingToggle(WebView w) {
        if (w == null || !pendingToggle) return;
        pendingToggle = false;
        try {
            w.postDelayed(new AskToggle(w, 3), 250L);
        } catch (Throwable ignored) {
        }
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

    /**
     * 打开网页面板（NapCat WebUI / AstrBot 控制台）。
     *
     * v1.1.28 起改用**系统默认浏览器**打开，不再塞进应用内的 QqbotPanelActivity：
     * 内置 WebView 里登 AstrBot 控制台经常卡在登录页、也不方便另开窗口对照，
     * 用系统浏览器还能正常保存密码、换标签页、投屏到别的设备。
     * 只放行本机地址（127.0.0.1 / localhost），避免页面内容把用户导向外部站点。
     */
    public void doOpenPanel(String url) {
        String u = url == null ? "" : url.trim();
        String low = u.toLowerCase(java.util.Locale.ROOT);
        if (!(low.startsWith("http://127.0.0.1:") || low.startsWith("http://localhost:"))) {
            Toast.makeText(this, "只支持本机地址", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
            // 可能是从非 Activity 上下文调进来的，带 NEW_TASK 才不会抛
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "没有找到可用的浏览器", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show();
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

    /* 去掉日志里的 ANSI 颜色码。apt / pip / NapCat 都会写颜色，直接塞进 toast
       会变成一堆 [0;32m 之类的乱码，用户根本看不出是哪一步出了什么问题。 */
    public String stripAnsi(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == 27 && i + 1 < n && s.charAt(i + 1) == '[') {
                i += 2;
                while (i < n) {
                    char d = s.charAt(i);
                    i++;
                    if (d >= '@' && d <= '~') break;   // CSI 的终结字符
                }
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    /* 取日志最后一行，用来在操作失败时告诉用户「到底为什么失败」。
       部署脚本在第 1 步开跑之前就退出时（真机 arm64 上 proot 前置自检那回），
       pid 文件不会写、进程也查不到，旧代码只回一句 start failed，
       用户看到的 toast 是「部署：start failed」，完全看不出原因。 */
    public String lastLogLine(File f) {
        try {
            String s = stripAnsi(tail(f, 1)).trim();
            if (s.length() > 200) s = s.substring(0, 200) + "…";
            return s;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 服务日志（supervisor.log），启动失败时用它给原因。 */
    public File supervisorLogFile() { return new File(new File(qqbotDir(), "logs"), "supervisor.log"); }

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
        if (deployDoneFile().isFile()) return "已经部署完成，不用重新开始";
        if (pidAlive(deployPidFile())) return "部署正在跑，稍等一下";
        File bash = new File(usrDir(), "bin/bash");
        if (!bash.isFile()) return "Termux 基础环境还没解包好，退出重进一次应用再试";
        File script = new File(usrDir(), "opt/qqbot/deploy.sh");
        if (!script.isFile()) return "找不到部署脚本，覆盖安装一次应用可以恢复";
        String qh = qqbotDir().getAbsolutePath();
        // 内置离线包必须先落盘：部署脚本读不到 APK 的 assets。这里只是踢一下后台线程，
        // 真正「等它拷完」由步骤 2 自己轮询 .bundle-ok 完成。
        try { kickBundleCopy(); } catch (Throwable ignored) {}
        // 必须用 setsid：runShell 返回后会 destroy() 掉这个 shell，而 Android 的
        // ProcessImpl.destroyProcess 是按进程组发 SIGTERM 的，同组的后台进程会被连带杀死，
        // 于是部署脚本刚起来就没了（表现为 supervisor/deploy 的 pid 写了却查不到进程）。
        runShell("mkdir -p " + qh + "/logs " + qh + "/steps; "
                + "nohup setsid bash " + usrDir().getAbsolutePath() + "/opt/qqbot/deploy.sh >> "
                + qh + "/logs/deploy.log 2>&1 < /dev/null & "
                + "sleep 2; echo ok", 25);
        if (pidAlive(deployPidFile())) return "已开始部署";
        // 没活下来：把部署日志的最后一行原样带出去（去色、截断），
        // 否则界面只能显示一句没头没尾的「启动失败」，用户没法反馈、我也没法定位。
        String why = lastLogLine(deployLogFile());
        return "启动失败" + (why.length() > 0 ? "：" + why : "：部署脚本没有写出日志，重进应用再试一次");
    }

    /** 在后台拉起机器人服务（NapCat + AstrBot）。 */
    public String startServices() {
        File bash = new File(usrDir(), "bin/bash");
        if (!bash.isFile()) return "Termux 基础环境还没解包好，退出重进一次应用再试";
        // 先补解析器再拉 proot：内置镜像里可能压根没有 /etc/resolv.conf，那样沙盒起来
        // 也是「QQ 能收发、AI 不回话」的半死状态（详见 ensureSandboxDns）。
        try { ensureSandboxDns(); } catch (Throwable ignored) {}
        File pid = new File(qqbotDir(), "supervisor.pid");
        if (pidAlive(pid)) return "服务已经在跑了";
        String qh = qqbotDir().getAbsolutePath();
        // 同 startDeploy：setsid 脱离进程组；pid 由 start-sandbox.sh 自己写
        // （setsid 在调用者是进程组组长时会 fork，$! 指向的就不是真正的 proot）
        runShell("mkdir -p " + qh + "/logs " + qh + "/cache/logs; "
                + "nohup setsid bash " + usrDir().getAbsolutePath() + "/opt/qqbot/start-sandbox.sh > "
                + qh + "/logs/supervisor.log 2>&1 < /dev/null & "
                + "sleep 3; echo ok", 30);
        if (pidAlive(pid)) return "服务已启动";
        String why = lastLogLine(supervisorLogFile());
        return "启动失败" + (why.length() > 0 ? "：" + why : "：服务没有写出日志，重进应用再试一次");
    }

    /** 应用启动时：没部署完就续跑部署；部署完了就确保服务在跑。 */
    public void maybeAutoDeploy() {
        try {
            if (!new File(usrDir(), "bin/bash").isFile()) return;
            // 用户刚用「关闭进程 / 紧急下线」关过（state/offline 还在）：本次打开只显示界面，
            // 既不续跑部署也不拉起沙盒。少了这一句，「关闭进程」之后一开 App，沙盒就又被
            // startServices() 拉回来了。标记只由用户显式「启动」清除，这里不碰它。
            if (StateIO.file(getFilesDir(), "offline").isFile()) return;
            if (!deployDoneFile().isFile()) {
                // 部署要跑起来之前，先让内置离线包在后台就位（没有内置包的 APK 这里是空操作）。
                // 不放在部署脚本里做：脚本在 Termux 里跑，读不到 APK 的 assets。
                try { kickBundleCopy(); } catch (Throwable ignored) {}
                if (pidAlive(deployPidFile())) return;
                if (!new File(usrDir(), "opt/qqbot/deploy.sh").isFile()) return;
                startDeploy();
                return;
            }
            // 部署完了：604 MB 的镜像已经解压成 rootfs，留一份在 home 里纯占空间，
            // 每次启动顺手清掉（没有就什么也不做）。
            try { dropBundle(); } catch (Throwable ignored) {}
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

    /* ===================== 内置离线包（1.2.0） ===================== */

    /** 内置离线包在 assets 里的名字。构建脚本按目标架构往里塞不同的包。 */
    public static final String BUNDLE_ASSET = "qqbot-bundle.tar.xz";

    /** APK 里有没有带内置离线包。 */
    public boolean hasBundle() {
        InputStream in = null;
        try {
            in = getAssets().open(BUNDLE_ASSET);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 这是不含内置镜像的「更新包」（构建时塞了空标记资产 assets/qqbot-update-pkg）。 */
    public boolean isUpdatePackage() {
        InputStream in = null;
        try {
            in = getAssets().open("qqbot-update-pkg");
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public File bundleFile() { return new File(qqbotDir(), "bundle.tar.xz"); }

    public File bundleOkFile() { return new File(qqbotDir(), ".bundle-ok"); }

    public File bundlePendingFile() { return new File(qqbotDir(), ".bundle-pending"); }

    private String bundleStamp() {
        try {
            return String.valueOf(getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime);
        } catch (Throwable t) {
            return "0";
        }
    }

    /** 内置离线包的标称大小（字节），构建时写进 opt/qqbot/BUNDLE_SIZE；没有就返回 0。
     *
     *  为什么不用 assets.openFd().getLength()：构建脚本可能把 .tar.xz 以 deflate 存进 APK，
     *  那时 openFd 会抛 FileNotFoundException，而界面只想知道「大概多大」，读一个小文本更稳。
     */
    public long bundleAssetSize() {
        String s = readSmall(new File(new File(usrDir(), "opt/qqbot"), "BUNDLE_SIZE"));
        if (s == null) return 0L;
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 内置离线包的架构标记（amd64 / arm64）；装了个没带包的版本时返回空串。 */
    public String bundleArch() {
        String s = readSmall(new File(new File(usrDir(), "opt/qqbot"), "BUNDLE_ARCH"));
        return s == null ? "" : s.trim();
    }

    /** 内置包是不是已经在磁盘上放好了（可以交给 tar 解压）。 */
    public boolean bundleReady() {
        File b = bundleFile();
        if (!b.isFile() || b.length() <= 0) return false;
        String have = readSmall(bundleOkFile());
        return have != null && have.trim().equals(bundleStamp());
    }

    /** 把内置离线包从 assets 拷到 $HOME/.qqbot/bundle.tar.xz。
     *
     *  为什么必须拷出来：部署脚本在 Termux 里跑，读不到 APK 里的 assets，而 tar 只认路径。
     *  代价是多占一份磁盘（约 650 MB），解压完由 deploy 步骤立刻删掉，不是永久占用。
     *
     *  返回 null 表示成功；否则是给界面看的失败原因。
     */
    public String ensureBundle() {
        if (!hasBundle()) return null;
        if (bundleReady()) return null;
        File dst = bundleFile();
        File part = new File(qqbotDir(), "bundle.tar.xz.part");
        File qq = qqbotDir();
        if (!qq.isDirectory() && !qq.mkdirs()) return "工作目录建不出来";
        InputStream in = null;
        OutputStream out = null;
        try {
            if (!writeFileRobust(bundlePendingFile(), bundleStamp().getBytes("UTF-8"))) {
                // 没有这个标记，20-rootfs 不会等内置包，会直接走 600 MB 在线下载。
                return "内置包待拷标记写入失败";
            }
            in = new BufferedInputStream(getAssets().open(BUNDLE_ASSET), 1 << 20);
            out = new BufferedOutputStream(new FileOutputStream(part), 1 << 20);
            byte[] buf = new byte[1 << 20];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            out.flush();
            out.close();
            out = null;
            if (!part.renameTo(dst)) {
                if (dst.exists()) dst.delete();
                if (!part.renameTo(dst)) {
                    part.delete();
                    return "内置包写入失败";
                }
            }
            if (!writeFileRobust(bundleOkFile(), bundleStamp().getBytes("UTF-8"))) {
                // 标记写不进去，20-rootfs 就永远认不到这份包，下次启动还会重拷 600 MB。
                // 如实返回，让日志里留下原因。
                return "内置包标记写入失败";
            }
            return null;
        } catch (Throwable t) {
            try {
                if (out != null) out.close();
            } catch (Throwable ignored) {
            }
            part.delete();
            try {
                writeFileRobust(new File(qqbotDir(), "logs/bundle.log"),
                        ("bundle copy failed: " + t + (char) 10).getBytes("UTF-8"));
            } catch (Throwable ignored) {
            }
            return "内置包释放失败：" + t;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 后台线程里释放内置包。拷贝 650 MB 期间界面必须还能动。 */
    public void kickBundleCopy() {
        // 沙盒已经装好了就一个字节都不拷：内置包只对「首次部署」有用，
        // 拷出来白占 604 MB，而且解压出来的 2.2 GB 会跟现有沙盒打架。
        if (deployDoneFile().isFile()) {
            dropBundle();
            return;
        }
        // 精简包里没有内置镜像：这里只能清「上一份完整包留下的拷贝状态」，
        // 绝不能像以前那样把 bundle.tar.xz / .bundle-ok 一起删掉 —— 1.2.1 起镜像是
        // 部署脚本自己从官网下载的，删了就等于让用户每开一次应用丢 600 MB 下载进度。
        // .bundle-pending 只由 Java 的拷贝线程创建，删掉它是安全的（20-rootfs 会因此不再傻等）。
        if (!hasBundle()) {
            try { bundlePendingFile().delete(); } catch (Throwable ignored) {}
            return;
        }
        if (bundleReady()) return;
        final File flag = new File(qqbotDir(), ".bundle-copying");
        // 这个标记只表示「有线程正在拷」，但线程随进程死亡时不会自己清掉。
        // 上一次拷贝被系统杀掉后，残留的标记会让之后每次启动都直接 return，
        // 内置包永远拷不出来，部署只能干等 600 秒再退回联网。超过 10 分钟的标记
        // 一定是死掉的（600 MB 在手机上不会拷这么久），清掉重试。
        if (flag.isFile() && System.currentTimeMillis() - flag.lastModified() > 600000L) {
            try { flag.delete(); } catch (Throwable ignored) {}
        }
        if (flag.isFile()) return;
        try {
            writeFileRobust(flag, bundleStamp().getBytes("UTF-8"));
        } catch (Throwable ignored) {
        }
        Thread th = new Thread(new BundleCopyTask(this, flag), "qqbot-bundle-copy");
        th.setDaemon(true);
        th.start();
    }

    /** 清掉内置包相关的所有落盘文件（拷贝中 / 拷贝完 / 待拷标记）。 */
    private void dropBundle() {
        try { bundleFile().delete(); } catch (Throwable ignored) {}
        try { new File(qqbotDir(), "bundle.tar.xz.part").delete(); } catch (Throwable ignored) {}
        try { bundleOkFile().delete(); } catch (Throwable ignored) {}
        try { bundlePendingFile().delete(); } catch (Throwable ignored) {}
        // 拷贝线程的互斥标记也要一起清，否则下一轮 kickBundleCopy 会以为还在拷。
        try { new File(qqbotDir(), ".bundle-copying").delete(); } catch (Throwable ignored) {}
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
            // 带线程 id：多个后台线程同时写不同/相同文件时不会抢同一个临时文件。
            File tmp = new File(dst.getPath() + ".qqbotnew." + Thread.currentThread().getId());
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

    /** 读一个几 KB 的文本文件（package.json 这类），超过 max 就只读前 max 字节。 */
    private String readText(File f, int max) {
        try {
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            byte[] buf = new byte[(int) Math.min(raf.length(), (long) max)];
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
            try { ensureSandboxDns(); } catch (Throwable ignored) {}
            if (!pidAlive(new File(qqbotDir(), "supervisor.pid"))) return;
            String prefix = usrDir().getAbsolutePath();
            String qh = qqbotDir().getAbsolutePath();
            runShell("bash " + prefix + "/opt/qqbot/stop-sandbox.sh >/dev/null 2>&1; mkdir -p " + qh
                    + "/logs; nohup setsid bash " + prefix + "/opt/qqbot/start-sandbox.sh > " + qh
                    + "/logs/supervisor.log 2>&1 < /dev/null & sleep 4; echo ok", 240);
        } catch (Throwable ignored) {
        }
    }
    /** 覆盖安装新版后自检：内置镜像里那份 QQ 没有注入过 NapCat（缺 loadNapCat.js，
     *  package.json 的 main 还指向 application.asar），而「解压内置镜像」这条路会把
     *  50-napcat 直接标记完成，注入步骤永远不会再跑 —— 表现出来就是 QQ 起得来、6099
     *  一直没人听。这里在启动时纯做文件修补，只碰两个小文件，幂等。
     *
     *  和 sandbox/run-napcat.sh 的 ensure_napcat_injection 是同一件事的两层保险：
     *  脚本那层管「每次启动 QQ 之前」，这里管「新版本刚装上、旧沙盒还在跑」，
     *  修完由调用方重启一次容器，用户不用点任何按钮。
     *
     *  多实例的 QQ 装在 opt/qqbot-inst/&lt;id&gt;/QQ，主号走 opt/QQ，两处都看。
     *  返回 true 表示这次确实改过文件（调用方据此重启容器）。
     */
    public boolean repairNapcatInjection() {
        try {
            if (!deployDoneFile().isFile()) return false;
            File ub = new File(homeDir(), "ubuntu");
            java.util.List<File> apps = new java.util.ArrayList<File>();
            apps.add(new File(ub, "opt/QQ/resources/app"));
            File[] subs = new File(ub, "opt/qqbot-inst").listFiles();
            if (subs != null) {
                for (File s : subs) apps.add(new File(s, "QQ/resources/app"));
            }
            boolean changed = false;
            for (File app : apps) {
                if (!app.isDirectory()) continue;
                if (!new File(app, "app_launcher/napcat/napcat.mjs").isFile()) continue;
                File stub = new File(app, "loadNapCat.js");
                if (stub.length() == 0) {
                    // 和 run-napcat.sh 里 heredoc 写的内容逐字节一致
                    String js = "const path = require('node:path');\n"
                            + "const { pathToFileURL } = require('node:url');\n"
                            + "import(pathToFileURL(path.join(__dirname, 'app_launcher/napcat/napcat.mjs')).href);\n";
                    if (writeFileRobust(stub, js.getBytes("UTF-8")) && stub.length() > 0) changed = true;
                }
                File pj = new File(app, "package.json");
                String text = readText(pj, 262144);
                if (text == null) continue;
                if (text.contains("\"./loadNapCat.js\"")) continue;   // 已经注入过，别每次开应用都重写
                String patched = text.replaceFirst("(\"main\"\\s*:\\s*)\"[^\"]*\"", "$1\"./loadNapCat.js\"");
                if (patched.equals(text)) continue;
                if (writeFileRobust(pj, patched.getBytes("UTF-8"))) changed = true;
            }
            return changed;
        } catch (Throwable t) {
            return false;
        }
    }


    /** 沙盒里没有可用解析器时，AstrBot 所有域名请求都会挂（日志里的
     *  「Temporary failure in name resolution」），表现为：大模型不回话、插件市场打不开、
     *  更新检查失败，而 QQ / NapCat 照常收发 —— 它用自己的 IP 列表，不查 DNS。
     *
     *  1.3.2 的全量包内置镜像在打包时漏了 /etc/resolv.conf（/etc/hosts、/etc/nsswitch.conf
     *  也一起丢了），而 20-rootfs.sh 走「解压内置包」那条路时假定这些文件已经在镜像里，
     *  于是全新安装（全量包）的机器一进沙盒就没有解析器：glibc 去连 127.0.0.1:53，全挂。
     *  覆盖安装（25 MB update 包）走在线 ubuntu-base 分支，会写 DNS，所以看不出来。
     *
     *  这里在每次启动时把「系统当前网络用的 DNS」写进 rootfs。写的是真实的运营商 /
     *  路由器 DNS，不是写死的公共 DNS，这样内网 DNS、公司分流、IPv6-only 网络都能用；
     *  读不到系统 DNS 才用公共 DNS 兜底。内容没变就不写，不反复动 rootfs。
     *
     *  返回 true 表示这次确实改过文件。
     */
    public boolean ensureSandboxDns() {
        try {
            if (!deployDoneFile().isFile()) return false;
            File etc = new File(new File(homeDir(), "ubuntu"), "etc");
            if (!etc.isDirectory()) return false;
            /*
             * 系统 DNS 打头，后面再挂两个公共解析器兜底：
             * 系统给的是「当前这张网」的解析器（路由器 / 运营商 / VPN 下发），换网之后
             * 旧值会立刻失效 —— 典型场景是关掉 Wi-Fi 改用移动数据，resolv.conf 里还留着
             * 家里路由器的 192.168.x.1，每次解析都要先等它超时，用户看到的就是「什么都慢」。
             * glibc 按顺序试，前一个超时才轮到下一个，所以最后补一行 options 把单个
             * 服务器的等待压到 2 秒，别让一次解析卡到 10 秒以上。
             */
            java.util.List<String> all = new java.util.ArrayList<String>(systemDnsServers());
            for (String p : new String[]{"223.5.5.5", "119.29.29.29", "114.114.114.114"}) {
                if (!all.contains(p)) all.add(p);
            }
            StringBuilder ns = new StringBuilder();
            for (String d : all) ns.append("nameserver ").append(d).append('\n');
            ns.append("options timeout:2 attempts:2\n");
            boolean changed = writeIfDifferent(new File(etc, "resolv.conf"), ns.toString());
            // hosts / nsswitch 缺了不致命（glibc 会退回 files dns），但每次解析 localhost
            // 都要去问一次 DNS，慢且在某些网络下会卡住，顺手补上。
            String hosts = "127.0.0.1\tlocalhost\n::1\tlocalhost ip6-localhost ip6-loopback\n";
            if (writeIfDifferent(new File(etc, "hosts"), hosts)) changed = true;
            String nss = "passwd:         files\ngroup:          files\nshadow:         files\n"
                    + "hosts:          files dns myhostname\nnetworks:       files\n"
                    + "protocols:      files\nservices:       files\n";
            if (writeIfDifferent(new File(etc, "nsswitch.conf"), nss)) changed = true;
            return changed;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 系统当前网络用的 DNS：过滤回环 / 链路本地，IPv4 优先。 */
    private java.util.List<String> systemDnsServers() {
        java.util.List<String> v4 = new java.util.ArrayList<String>();
        java.util.List<String> v6 = new java.util.ArrayList<String>();
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm == null ? null : cm.getActiveNetwork();
            LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
            if (lp != null) {
                for (InetAddress a : lp.getDnsServers()) {
                    if (a == null || a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    String h = a.getHostAddress();
                    if (h == null || h.length() == 0) continue;
                    if (a instanceof Inet4Address) { if (!v4.contains(h)) v4.add(h); }
                    else { if (!v6.contains(h)) v6.add(h); }
                }
            }
        } catch (Throwable ignored) {
        }
        if (!v4.isEmpty()) return v4;
        return v6;   // 只有 v6 的网络才用 v6 的解析器
    }

    /** 内容不同才写，返回是否写过。 */
    private boolean writeIfDifferent(File f, String text) {
        String old = readText(f, 65536);
        if (text.equals(old)) return false;
        return writeFile(f, text) && f.length() > 0;
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
            long len = raf.length();
            // 正常 bots.json 只有几 KB。异常巨大的文件不能按 length() 直接分配，
            // 否则一次 OOM 会把整个 JavaBridge 线程带走（catch 只接 Exception，接不住 Error）。
            if (len > 8L * 1024 * 1024) {
                raf.close();
                noteBotsCorrupt(f);
                return defaultBots();
            }
            byte[] buf = new byte[(int) len];
            raf.readFully(buf);
            raf.close();
            String s = new String(buf, "UTF-8").trim();
            // 0 字节不是「全新安装」：全新安装时文件根本不在这里（上面已 return）。
            // 它只可能是上一次写盘写到一半被杀/磁盘满留下的残骸，必须按损坏处理并备份，
            // 否则界面会显示默认单实例，用户一点保存就把真实配置覆盖掉了。
            if (s.length() == 0) { noteBotsCorrupt(f); return defaultBots(); }
            new JSONObject(s);
            return s;
        } catch (Exception e) {
            noteBotsCorrupt(f);
            return defaultBots();
        }
    }

    /**
     * 把登录后的 QQ 号读回来，给 bots.json 自动填上。
     *
     * NapCat 登录成功后会在自己那份 config/ 目录里按账号建一个 napcat_<uin>.json，
     * 文件名里的那串数字就是 QQ 号。用户反馈「扫完码登录了，主页的 QQ 号还是空的」，
     * 根因就是以前只让用户手填。这里只读目录、不碰网络，也不改任何文件；
     * 写回 bots.json 由前端调 saveBots() 完成（要复用它的实例丢失保护）。
     */
    public String detectQq() {
        JSONObject out = new JSONObject();
        try {
            JSONArray found = new JSONArray();
            String raw = readBots();
            JSONObject root = new JSONObject(raw);
            String active = root.optString("active", "");
            JSONArray bs = root.optJSONArray("bots");
            if (bs != null) {
                for (int i = 0; i < bs.length(); i++) {
                    JSONObject b = bs.optJSONObject(i);
                    if (b == null) continue;
                    String id = b.optString("id", "");
                    if (id.length() == 0) continue;
                    String qq = scanNapcatUin(id, id.equals(active));
                    if (qq.length() == 0) continue;
                    JSONObject f = new JSONObject();
                    f.put("id", id);
                    f.put("qq", qq);
                    found.put(f);
                }
            }
            out.put("ok", true);
            out.put("found", found);
        } catch (Throwable t) {
            try {
                out.put("ok", false);
                out.put("err", String.valueOf(t));
            } catch (Throwable ignored) {}
        }
        return out.toString();
    }

    /**
     * 实例自己的 QQ 目录优先；只有 bots.json 里的 active 实例才会用共享的 opt/QQ。
     *
     * 根目录是 home/ubuntu，不是 files/usr：files/usr 是 Termux 的前缀（只有
     * bin/etc/lib/opt/qqbot），QQ 与实例目录都装在 home/ubuntu 下面。
     * 这跟同一个类里 qr() 取二维码的路径必须一致，否则「扫完码读不到 QQ 号」。
     */
    private String scanNapcatUin(String id, boolean active) {
        File ub = new File(homeDir(), "ubuntu");
        String q = scanNapcatUinDir(new File(ub,
                "opt/qqbot-inst/" + id + "/QQ/resources/app/app_launcher/napcat/config"));
        if (q.length() > 0) return q;
        if (!active) return "";
        return scanNapcatUinDir(new File(ub,
                "opt/QQ/resources/app/app_launcher/napcat/config"));
    }

    /** 在 config 目录里找 napcat_<数字>.json，取最新改过的那份（换号登录后会留下多份）。 */
    private static String scanNapcatUinDir(File dir) {
        try {
            if (dir == null || !dir.isDirectory()) return "";
            File[] fs = dir.listFiles();
            if (fs == null) return "";
            String best = "";
            long bestAt = -1;
            for (int i = 0; i < fs.length; i++) {
                File f = fs[i];
                if (f == null || !f.isFile()) continue;
                String n = f.getName();
                /* 登录成功后 NapCat 会写两份 per-uin 文件：napcat_<uin>.json 与
                   onebot11_<uin>.json。前者偶尔会因为 selfInfo.uin 还没填好而退化回
                   不带 uin 的名字，所以后者也认上作兜底。 */
                String pre = "";
                if (n.startsWith("napcat_")) pre = "napcat_";
                else if (n.startsWith("onebot11_")) pre = "onebot11_";
                if (pre.length() == 0 || !n.endsWith(".json")) continue;
                String num = n.substring(pre.length(), n.length() - 5);
                if (!isQqDigits(num)) continue;
                long t = f.lastModified();
                if (t > bestAt) { bestAt = t; best = num; }
            }
            return best;
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean isQqDigits(String s) {
        if (s == null) return false;
        int n = s.length();
        if (n < 5 || n > 12) return false;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
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
    public synchronized boolean writeBots(String json) {
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
            // 两份副本都必须确认落盘：home 下的是权威数据，cache 下的是沙盒脚本
            // （run-napcat.sh / delbot.sh）读的那份。以前两个返回值都被丢掉、无条件
            // return true，磁盘满或文件被占用时界面照样提示「已保存」，用户重启后
            // 才发现改了半天的实例配置没生效，甚至被旧内容覆盖。
            boolean homeOk = writeFile(botsFile(), text);
            File cacheDir = new File(qqbotDir(), "cache");
            if (!cacheDir.isDirectory()) cacheDir.mkdirs();
            boolean cacheOk = writeFile(new File(cacheDir, "bots.json"), text);
            return homeOk && cacheOk;
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
    /** 实例 id 会拼进文件路径：只放行字母数字、下划线、连字符和点，并禁掉 .. */
    private static boolean safeBotId(String id) {
        if (id == null || id.length() == 0 || id.length() > 64 || id.indexOf("..") >= 0) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.')) return false;
        }
        return true;
    }

    /** 找某个实例的登录二维码文件：返回 null 表示没有可用的图。 */
    private File qrFind(String wantId) {
        try {
            JSONObject bots = new JSONObject(readBots());
            String active = bots.optString("active", "");
            if (wantId == null || wantId.length() == 0) wantId = active;
            // WebView 里传进来的 id 不可信：直接拼进 /opt/qqbot-inst/<id>/QQ 会被
            // "../.." 穿越到任意目录读文件（再 base64 回页面）。这里按白名单挡掉。
            if (!safeBotId(wantId)) return null;
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
            // 二维码是几百 KB 的 PNG；超过 4 MB 只可能是文件坏了或是被替换的别的东西，
            // 不能直接按 length() 分配数组（大文件会把 JavaBridge 线程 OOM 掉）。
            if (found == null || !found.isFile() || found.length() <= 0
                    || found.length() > 4L * 1024 * 1024) return null;
            return found;
        } catch (Throwable t) {
            return null;
        }
    }

    public String qr(String wantId) {
        try {
            JSONObject bots = new JSONObject(readBots());
            String active = bots.optString("active", "");
            String foundId = (wantId == null || wantId.length() == 0) ? active : wantId;
            File found = qrFind(foundId);
            JSONObject o = new JSONObject();
            o.put("id", foundId);
            o.put("active", active);
            if (found == null) {
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

    // 把二维码图片交给系统的分享面板（能发到微信 / QQ，或者发到另一台手机去扫）。
    // 不用 FileProvider：本包自带的 TermuxOpenReceiver$ContentProvider 已经把 files
    // 目录按 content://<包名>.files/<绝对路径> 暴露出来（openFile 会校验路径必须以
    // /data/data/<包名>/files 开头），配上 FLAG_GRANT_READ_URI_PERMISSION 就能把读权限
    // 单独授给接收方 —— 不必新增清单 provider，也不必申请存储权限。
    /**
     * Termux 自带的 content provider 默认不对外：openFile() 会先查
     * ~/.termux/termux.properties 里的 allow-external-apps，不是 true 就报
     * 「requires `allow-external-apps` property to be set to `true`」。
     * 分享二维码走的就是这条通道，所以先确保这一行在。用户已经写过这一项
     * （哪怕写的是 false）就原样保留，不覆盖。
     */
    private void ensureExternalAppsAllowed() {
        try {
            File dir = new File(homeDir(), ".termux");
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            File f = new File(dir, "termux.properties");
            String cur = "";
            if (f.isFile()) {
                // 不是我们认识的配置文件（异常大）就别碰
                if (f.length() > 64L * 1024) return;
                RandomAccessFile raf = new RandomAccessFile(f, "r");
                byte[] buf = new byte[(int) raf.length()];
                raf.readFully(buf);
                raf.close();
                cur = new String(buf, "UTF-8");
                if (cur.indexOf("allow-external-apps") >= 0) return;
            }
            String add = (cur.length() == 0 || cur.endsWith("\n")) ? "" : "\n";
            writeFile(f, cur + add + "allow-external-apps=true\n");
        } catch (Throwable ignored) {
        }
    }

    public String qrShare(String wantId) {
        try {
            ensureExternalAppsAllowed();
            JSONObject bots = new JSONObject(readBots());
            String active = bots.optString("active", "");
            String foundId = (wantId == null || wantId.length() == 0) ? active : wantId;
            File f = qrFind(foundId);
            JSONObject o = new JSONObject();
            if (f == null) {
                o.put("ok", false);
                o.put("err", "还没有二维码可以分享，等二维码出来再试");
                return o.toString();
            }
            // 路径必须写成 /data/data/<包名>/files/...：TermuxOpenReceiver$ContentProvider
            // 的 openFile() 是按这个前缀校验的，而 getFilesDir() 在多用户设备上返回的是
            // /data/user/0/<包名>/files（同一个目录的另一个写法）——直接用它会被判成
            // 「Invalid path」。(/data/data 在老系统上是实体目录，在新系统上是指向
            // /data/user/0 的符号链接，两种写法指向同一份文件。)
            String filesBase = "/data/data/" + getPackageName() + "/files";
            String absPath = filesBase + f.getAbsolutePath().substring(getFilesDir().getAbsolutePath().length());
            Uri uri = Uri.parse("content://" + getPackageName() + ".files" + absPath);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("image/png");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, "QQ 登录二维码");
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            // 除了本包自己的「接收分享」页，一个能收图片的 App 都没有时，系统会直接把
            // 自己的接收页弹出来（模拟器上实测），看起来像是「点了变成别的东西」。
            // 所以先数一遍候选人：没有外部 App 就老实说没有，别硬弹。
            java.util.List<android.content.pm.ResolveInfo> cands =
                    getPackageManager().queryIntentActivities(send, 0);
            int others = 0;
            for (int i = 0; cands != null && i < cands.size(); i++) {
                android.content.pm.ResolveInfo ri = cands.get(i);
                if (ri == null || ri.activityInfo == null) continue;
                if (getPackageName().equals(ri.activityInfo.packageName)) continue;
                others++;
            }
            if (others == 0) {
                o.put("ok", false);
                o.put("err", "这台手机没有能接收图片的应用（微信 / QQ 之类），先装一个再分享");
                return o.toString();
            }
            Intent chooser = Intent.createChooser(send, "分享二维码图片");
            // 也不要把自己的接收页混进候选里：它只会把二维码再存一份到 files 目录
            chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, new android.content.ComponentName[]{
                    new android.content.ComponentName(getPackageName(),
                            "com.termux.filepicker.TermuxFileReceiverActivity")});
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(chooser);
            o.put("ok", true);
            return o.toString();
        } catch (Throwable t) {
            JSONObject o = new JSONObject();
            try {
                o.put("ok", false);
                o.put("err", "分享失败：" + t);
            } catch (Throwable ignored) { }
            return o.toString();
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

    /**
     * 通知栏权限是否可用。这条权限不是「关掉通知」，而是让那条**常驻通知**能出现：
     * 通知上有「重启 / 扫码 / 关闭进程」，没有它就只剩进应用里点停止，后台也就关不干净。
     * 13+ 看 POST_NOTIFICATIONS，13 以下看系统里本应用通知有没有被关。
     */
    public boolean notiGranted() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
            android.app.NotificationManager nm = (android.app.NotificationManager)
                    getSystemService(Context.NOTIFICATION_SERVICE);
            return nm == null || nm.areNotificationsEnabled();
        } catch (Throwable t) {
            // 判断不了就别吓用户：当作有权限，通知真发不出来时守护自己会写 lastNote。
            return true;
        }
    }

    /** 引导开通知权限：13+ 第一次弹系统框（并记住问过），拒过或低版本就跳本应用的通知设置页。 */
    public void doNotiAsk() {
        try {
            if (notiGranted()) {
                doToast("通知权限已经有了，常驻通知上的「关闭进程」可以用");
                return;
            }
            boolean asked = getSharedPreferences("qqbot", 0).getBoolean("notiAsked", false);
            if (android.os.Build.VERSION.SDK_INT >= 33 && !asked
                    && !shouldShowRequestPermissionRationale("android.permission.POST_NOTIFICATIONS")) {
                getSharedPreferences("qqbot", 0).edit().putBoolean("notiAsked", true).apply();
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7301);
                return;
            }
            // 系统不会再弹框（用户拒过 / 系统记成「不再询问」），只能把他送到通知设置页
            Intent i = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra("android.provider.extra.APP_PACKAGE", getPackageName());
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            doToast("打不开通知设置，请到系统设置 → 应用 → 烛之控制台 → 通知里手动允许");
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

    // ---------------- 功能 50：备份 / 恢复的选文件 ----------------
    // 两个 Intent 都必须在 Activity 主线程拉起（桥方法跑在 WebView 的线程上），
    // 所以统一走 runOnUiSync(new UiTask(...))；返回空串表示 Intent 已经发出去了，
    // 真正的结果会晚一步回到 onActivityResult，再由 BackupFlow 起后台线程处理。
    // 备份用 CREATE_DOCUMENT 而不是 createChooser：包一层 chooser 会丢掉
    // 「新建文件」的语义，部分 ROM 上直接退化成「选已有文件」。

    /** 「另存为」：让用户挑备份包存到哪儿（结果在 onActivityResult 里流式拷贝）。 */
    public String doBackupSave() {
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/gzip");
            i.putExtra(Intent.EXTRA_TITLE, BackupFlow.suggestName());
            startActivityForResult(i, REQ_BACKUP_SAVE);
            return "";
        } catch (Throwable t) {
            return "这台手机没有能存文件的程序";
        }
    }

    /** 选备份包：不少文件管理器把 .tar.gz 报成 octet-stream，所以 MIME 列一组。 */
    public String doRestoreOpen() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "application/gzip", "application/x-gzip", "application/x-tar", "application/octet-stream"});
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, REQ_RESTORE_OPEN);
            return "";
        } catch (Throwable t) {
            return "这台手机没有能选文件的程序";
        }
    }

    /**
     * 从任意线程把结果推给页面里的全局回调（目前只有 backupPicked / restorePicked）。
     * 用 evaluateJavascript 而不是轮询，是为了让「选完文件」这种异步事件立刻回到界面；
     * 万一页面没监听这个函数，JsPush 里会安静跳过，前端还有 poll 兜底。
     */
    public void pushJs(String fn, String json) {
        try {
            runOnUiThread(new JsPush(web, fn, json));
        } catch (Throwable ignored) {
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
            // 先写同目录临时文件再改名，不能直接 RandomAccessFile.setLength(0) 截断：
            // 截断之后如果写一半被系统杀掉 / 磁盘满，原文件就永久变成 0 字节。
            // bots.json 正好是这种「截断即丢配置」的文件（readBots 只能回落默认值）。
            File d = f.getParentFile();
            if (d != null && !d.isDirectory()) d.mkdirs();
            return writeFileRobust(f, text.getBytes("UTF-8"));
        } catch (Exception ignored) {
            return false;
        }
    }
}
