package com.termux.app;

import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

public class QqbotBridge {

    private final QqbotActivity act;

    // 把耗时命令丢到后台线程执行，跑完再 Toast。停止/重启要等 proot 自己退出，
    // 同步等会把 WebView 的 JS 线程卡住十几秒。
    private void runBg(String cmd, String done, int timeoutSec) {
        runBg(cmd, done, null, timeoutSec);
    }

    // fail 是失败时的 toast 文案：BgTask 现在会把退出码透出来，
    // 失败时不能再报「已完成」，否则脚本报错用户永远看不到。
    private void runBg(String cmd, String done, String fail, int timeoutSec) {
        Thread th = new Thread(new BgTask(act, cmd, done, fail, timeoutSec));
        th.setDaemon(true);
        th.start();
    }

    public QqbotBridge(QqbotActivity act) {
        this.act = act;
    }

    /**
     * 页面把自己当前的底色（getComputedStyle(document.body).backgroundColor，
     * 形如 rgb(5, 6, 10)）报上来。原生层拿它当窗口 / WebView 底色：
     * Theme.Material.NoActionBar 的窗口背景是 #303030 的灰，页面是深色，
     * 冷启动、页面重载、后台切回来那一下就会先灰一层 —— 也就是「灰屏」。
     * 只存一个 int 到 prefs，重活没有。
     */
    @JavascriptInterface
    public void pageBg(String css) {
        try {
            act.onPageBg(css);
        } catch (Throwable ignored) {
        }
    }

    /** 手工拼错误 JSON：只把 " 换成 ' 是不够的，异常消息里带反斜杠或换行会让前端的
     *  JSON.parse(Android.state()) 直接抛错，整页落到「读取状态失败」。 */
    private static String errJson(String what, Throwable t) {
        String m = what + "：" + String.valueOf(t);
        StringBuilder b = new StringBuilder("{\"error\":\"");
        for (int i = 0; i < m.length(); i++) {
            char c = m.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else if (c == '\t') b.append("\\t");
            else if (c < 0x20) b.append(' ');
            else b.append(c);
        }
        return b.append("\"}").toString();
    }

    @JavascriptInterface
    public String state() {
        try {
            JSONObject o = new JSONObject();
            o.put("prefix", act.usrDir().getAbsolutePath());
            o.put("home", act.homeDir().getAbsolutePath());
            o.put("deployDone", new File(act.qqbotDir(), "deploy.done").isFile());
            o.put("supervisorAlive", act.pidAlive(new File(act.qqbotDir(), "supervisor.pid")));
            // bots.json 只读一次、只解析一次：astrbotPort / astrbotPortAuto / bots 三个值
            // 都从同一个根对象派生。原来这三处各自 new JSONObject(act.readBots())，
            // 每次 state() 白读 2 遍文件、白解析 2 遍 JSON。
            JSONObject botsRoot = null;
            try { botsRoot = new JSONObject(act.readBots()); } catch (Throwable ignored) {}
            // AstrBot 控制台端口由 bots.json 的 astrbotPort 决定（0 = 自动，回落 6185）。
            // 不能再按 6185 硬编码：用户把端口改到别处后这里会一直探测错的端口，
            // 界面就永远显示离线。astrbotPortAuto 给前端区分「自动」和「用户指定」。
            int ap = act.astrbotPortOf(botsRoot);
            o.put("astrbotPort", ap);
            o.put("astrbotUp", act.portOpen(ap));
            o.put("astrbotPortAuto", act.astrbotPortRawOf(botsRoot) == 0);
            // 组件开关状态：端口关着有两种原因（被用户关掉 / 崩了），界面要能区分，
            // 所以把标记文件本身也报出去。
            o.put("napcatOff", act.svcOff("napcat"));
            o.put("astrbotOff", act.svcOff("astrbot"));
            o.put("lanIp", act.lanIp());
            // 端口一律按「当前使用」的实例推导，多实例时主页显示的才是真正在跑的那一个
            JSONObject bots = (botsRoot != null) ? botsRoot : new JSONObject();
            JSONArray bl = bots.optJSONArray("bots");
            String activeId = bots.optString("active", "");
            int napPort = 6099;
            int onePort = 6199;
            if (bl != null) {
                for (int i = 0; i < bl.length(); i++) {
                    JSONObject b = bl.optJSONObject(i);
                    if (b == null) continue;
                    // up 按这个实例自己的 napcatPort 探测（不是所有实例都当 6099 看）。
                    // 端口字段缺失或为 0（老的/手改的 bots.json，或用户把输入框留空还没保存）
                    // 时按沙盒 run-napcat.sh 的约定回退到 6099/6199，否则会去探测 0 端口、
                    // 界面永远显示离线。回退只用于探测和顶层端口派生，不改上报给前端的实例字段。
                    int bp = b.optInt("napcatPort", 6099);
                    if (bp <= 0) bp = 6099;
                    b.put("up", act.portOpen(bp));
                    if (b.optString("id", "").equals(activeId)) {
                        napPort = bp;
                        int bo = b.optInt("onebotPort", 6199);
                        onePort = (bo <= 0) ? 6199 : bo;
                    }
                }
            }
            o.put("napcatPort", napPort);
            o.put("onebotPort", onePort);
            o.put("napcatUp", act.portOpen(napPort));
            o.put("onebotUp", act.portOpen(onePort));
            o.put("bots", bots);
            // 后台守护（Android 前台服务）状态，「更多 → 后台守护」卡用它渲染。
            // guardOn 看的是开关文件而不是服务是否在跑：用户关掉守护后服务会停，
            // 但界面上的开关必须仍然是「已关闭」而不是自己弹回「已开启」。
            o.put("guardOn", QqbotGuardService.enabled(act));
            o.put("guardWake", QqbotGuardService.wakeEnabled(act));
            // 息屏模式（1.3.3-beta.2）：开关状态 + 此刻是否真的处于「灭屏加强」，
            // 界面按后者显示「灭屏加强中」，用户一眼能确认策略生效了。
            o.put("guardScreen", ScreenMode.isOn(act));
            o.put("screenDark", ScreenMode.boosted(act));
            o.put("guardRunning", QqbotGuardService.guardRunning);
            // 1.3.4-beta.9：「前台常驻」卡用它渲染四行只读状态。
            // fgsSince 是这次前台服务的启动时刻（0 = 服务没跑），界面拿它算「已运行多久」；
            // fgsNoti 查的是通知栏里那条还在不在（被 ROM 清掉就是 false，需要一键重挂）；
            // fgsCh 是渠道有没有被用户关掉（关了系统就不会让服务留在前台）。
            o.put("fgsSince", QqbotGuardService.fgsSince);
            o.put("fgsNoti", QqbotGuardService.runNotiPosted(act));
            o.put("fgsCh", QqbotGuardService.runChannelOk(act));
            o.put("battOk", QqbotGuardService.batteryOk(act));
            o.put("guardNote", QqbotGuardService.lastNote == null ? "" : QqbotGuardService.lastNote);
            // 常驻通知能不能出现：false 时「关闭进程」根本没入口，后台只能靠进应用手动停
            o.put("notiGranted", act.notiGranted());
            // v1.1.30：降载标记（battery / thermal / 空）与紧急下线标记。
            // 都是内存读 + 一次 stat，不改老字段，只增。
            o.put("degrade", BatteryMon.degradeLabel());
            o.put("offline", StateIO.file(act.filesDirPub(), "offline").isFile());
            o.put("appVersion", appVersion());
            // 日志分两处：沙盒内服务写 $HOME/.qqbot/cache/logs（= 沙盒 /qqbot-cache/logs），
            // 宿主侧的部署/守护进程写 $HOME/.qqbot/logs。两处都要列出来。
            JSONArray files = new JSONArray();
            java.util.HashSet<String> seen = new java.util.HashSet<String>();
            File[] dirs = new File[] { act.logsDir(), new File(act.qqbotDir(), "logs") };
            for (int d = 0; d < dirs.length; d++) {
                File[] ls = dirs[d].listFiles();
                if (ls == null) continue;
                for (int i = 0; i < ls.length; i++) {
                    File f = ls[i];
                    if (!f.isFile()) continue;
                    if (seen.contains(f.getName())) continue;
                    seen.add(f.getName());
                    JSONObject e = new JSONObject();
                    e.put("name", f.getName());
                    e.put("size", f.length());
                    e.put("mtime", f.lastModified());
                    files.put(e);
                }
            }
            o.put("logFiles", files);
            return o.toString();
        } catch (Exception e) {
            return errJson("读取失败", e);
        }
    }

    /** 版本号只在 apktool.yml（→ AndroidManifest 的 versionName）里维护一份，前端不再写死。 */
    private String appVersion() {
        try {
            return act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "";
        }
    }
    /**
     * 沙盒里 AstrBot 自身的版本（「更多 → 关于与支持」那一行）。
     * 以前界面写死 v4.28.2，用户重装/升级 AstrBot 之后永远显示旧值。
     * 真值只在 <home>/ubuntu/opt/AstrBot/astrbot/__init__.py 的 __version__ 里，
     * 读不到就回空串，让前端显示「—」，绝不编一个数字。
     */
    @JavascriptInterface
    public String envVers() {
        try {
            JSONObject o = new JSONObject();
            File f = new File(QqbotShell.homeDir(act.filesDirPub()), "ubuntu/opt/AstrBot/astrbot/__init__.py");
            String v = "";
            if (f.isFile()) {
                String t = AstrCfg.readText(f);
                int i = t.indexOf("__version__");
                if (i >= 0) {
                    int eq = t.indexOf('=', i);
                    if (eq > 0) {
                        // 取等号后第一个引号串：__version__ = "4.29.1"
                        int a = -1;
                        int b = -1;
                        for (int k = eq + 1; k < t.length(); k++) {
                            char c = t.charAt(k);
                            if (c == '"' || c == '\'') { a = k + 1; break; }
                            if (c == '\n') break;
                        }
                        if (a > 0) {
                            for (int k = a; k < t.length(); k++) {
                                char c = t.charAt(k);
                                if (c == '"' || c == '\'' || c == '\n') { b = k; break; }
                            }
                        }
                        if (a > 0 && b > a) v = t.substring(a, b).trim();
                    }
                }
            }
            o.put("ok", true);
            o.put("astrbot", v);
            return o.toString();
        } catch (Throwable t) {
            return failJson("读版本失败：" + t);
        }
    }


    @JavascriptInterface
    public String deployInfo() {
        try {
            JSONObject o = new JSONObject();
            o.put("done", act.deployDoneFile().isFile());
            o.put("running", act.isDeployRunning());
            JSONArray steps = new JSONArray();
            String[][] defs = new String[][] {
                {"10-termux", "准备 Termux 工具 (proot / tar / xz / curl)"},
                {"20-rootfs", "下载并解包 Ubuntu 24.04 根文件系统"},
                {"30-apt", "在沙盒内安装运行依赖 (Python / 图形库)"},
                {"40-qq", "安装 Linux QQ"},
                {"50-napcat", "安装 NapCat 并注入 QQ"},
                {"60-astrbot", "安装 AstrBot 与 WebUI"},
                {"65-link", "把 NapCat 与 AstrBot 对接起来"},
                {"70-launcher", "安装 qqbot-* 命令"},
                /* 1.3.4-beta.7：deploy.sh 拆成 9 步做断点续传，末步收尾并拉起服务。
                   这里不补的话界面永远只显示 8 步，用户看不到“已完成”。 */
                {"90-start", "收尾并拉起服务"}
            };
            File stepsDir = new File(act.qqbotDir(), "steps");
            int doneCount = 0;
            for (int i = 0; i < defs.length; i++) {
                JSONObject s = new JSONObject();
                s.put("key", defs[i][0]);
                s.put("label", defs[i][1]);
                boolean d = new File(stepsDir, defs[i][0]).isFile();
                if (d) doneCount++;
                s.put("done", d);
                steps.put(s);
            }
            o.put("steps", steps);
            o.put("doneCount", doneCount);
            o.put("total", defs.length);
            o.put("logTail", act.stripAnsi(act.tail(act.deployLogFile(), 14)));   /* ANSI 颜色码在 WebView 里会变成 [36m 之类的乱码 */
            // 上一次部署留下的失败原因（common.sh 的 qqerr 会写这个文件）。
            // 首页把它直接显示出来，用户就不必自己去日志页翻几百行 apt 输出。
            File errf = new File(act.qqbotDir(), "deploy.error");
            String errTxt = errf.isFile() ? act.stripAnsi(act.tail(errf, 6)).trim() : "";
            if (errTxt.length() > 900) errTxt = errTxt.substring(errTxt.length() - 900);
            o.put("err", errTxt);
            // 部署日志有多少秒没更新了：界面据此判断「是在慢慢装」还是「真的卡住了」。
            long age = 0L;
            File dl = act.deployLogFile();
            if (dl.isFile()) age = (System.currentTimeMillis() - dl.lastModified()) / 1000L;
            o.put("logAge", age);
            return o.toString();
        } catch (Exception e) {
            return errJson("读取失败", e);
        }
    }

    /**
     * 重装进度。reinstall.sh 的输出是一串固定顺序的阶段行（见脚本里的 echo），
     * 这里按「出现到哪一行」折算成百分比，界面据此画进度条。
     *
     * 为什么不直接读日志给用户看：重装全程 1~5 分钟，日志里 90% 是 apt/pip 的输出，
     * 用户要的是「装到哪一步了、还要多久」，不是滚动的安装输出。
     *
     * 判断「还在跑」用日志里最后一对「开始重装 / 重装结束」标记的前后顺序，
     * 不看进程：脚本是 setsid 脱离出去的，按 pid 判断反而容易误判成已结束。
     */
    @JavascriptInterface
    public String reinstallInfo() {
        try {
            JSONObject o = new JSONObject();
            File f = findLog("reinstall.log");
            if (f == null) {
                o.put("running", false);
                o.put("ok", JSONObject.NULL);
                o.put("state", "none");
                return o.toString();
            }
            String txt = act.tail(f, 400);
            String[] lines = txt.split("\n");
            // 阶段定义：key 是脚本里对应那一步的 echo 特征串，label 是给用户看的中文
            String[][] napcatStages = new String[][] {
                {"等待 napcat 退出", "等待旧进程退出"},
                {"删除旧的 NapCat", "清理旧文件"},
                {"重新安装 NapCat", "安装 NapCat 与 QQ"},
                {"重建对接配置", "重建 OneBot 对接"},
                {"结束（", "完成"}
            };
            String[][] astrbotStages = new String[][] {
                {"等待 astrbot 退出", "等待旧进程退出"},
                {"备份 AstrBot 的 data", "备份配置"},
                {"重新安装 AstrBot", "安装 AstrBot 与依赖"},
                {"用户的 data/ 已恢复", "恢复配置"},
                {"重建对接配置", "重建 OneBot 对接"},
                {"结束（", "完成"}
            };
            int lastStart = -1, lastEnd = -1;
            String comp = "";
            for (int i = 0; i < lines.length; i++) {
                String l = lines[i];
                if (l.indexOf("===== 开始重装 ") >= 0) {
                    lastStart = i;
                    int a = l.indexOf("开始重装 ") + 5;
                    int b = l.indexOf('（', a);
                    if (b > a) comp = l.substring(a, b).trim();
                } else if (l.indexOf("===== 重装 ") >= 0 && l.indexOf(" 结束（") >= 0) {
                    lastEnd = i;
                }
            }
            boolean running = lastStart >= 0 && lastStart > lastEnd;
            if (!running && lastEnd < 0) {
                o.put("running", false);
                o.put("ok", JSONObject.NULL);
                o.put("state", "none");
                return o.toString();
            }
            String[][] stages = "astrbot".equals(comp) ? astrbotStages : napcatStages;
            int total = stages.length - 1;         // 不含「完成」这一档
            // 从本段起点开始数，数到第几个阶段特征串出现过。
            // 只数真正的阶段：最后一档「结束（」是结束标记，它一出现就说明整段跑完了，
            // 要是也算进 reached，失败时会被错算成「卡在最后一步」。
            int from = (lastStart >= 0) ? lastStart : 0;
            int reached = 0;
            for (int s = 0; s < total; s++) {
                boolean hit = false;
                for (int i = from; i < lines.length; i++) {
                    if (lines[i].indexOf(stages[s][0]) >= 0) { hit = true; break; }
                }
                if (hit) reached = s + 1;
            }
            /* 「当前在干哪一步」= 第一个还没出现的阶段。
               reached 是「已经出现过的最后一条阶段特征」，它对应的工作已经做完了，
               现在跑的是它后面那一步 —— 直接拿 reached 当提示，会一直说「正在清理旧文件」，
               而实际上早在装 NapCat 了。 */
            // 结束时间 / 退出码：读最后一行「重装 X 结束（时间，退出码 N）」。
            // 必须先解析 rc 再算进度：失败时不能把所有阶段都打勾（以前就是这样，
            // 退出码 1 却显示 100% + 四个绿勾，看起来像成功）。
            String endedAt = "";
            int rc = 0;
            for (int i = lines.length - 1; i >= 0; i--) {
                String l = lines[i];
                int p = l.indexOf(" 结束（");
                if (p < 0) continue;
                int a = p + 4;
                int b = l.indexOf('）', a);
                if (b > a) {
                    String inner = l.substring(a, b);
                    int k = inner.indexOf("退出码 ");
                    if (k >= 0) {
                        try { rc = Integer.parseInt(inner.substring(k + 4).trim()); } catch (Throwable ignored) {}
                        endedAt = inner.substring(0, k).replace("，", "").replace(",", "").trim();
                    } else endedAt = inner.trim();
                }
                break;
            }
            o.put("endedAt", endedAt);
            o.put("rc", rc);
            /* 进度怎么算：reached 表示「某个阶段的特征行出现过了」，它只说明这一步
               *开始*了，不说明做完了。所以分两种：
                 - 成功（rc==0）：全部打勾、100%
                 - 失败（rc!=0）：最后出现的那个阶段就是失败的那一步，它**不算完成**，
                   百分比也只算它前面那些。以前不管成功失败一律 doneCount=total，
                   结果是退出码 1 也显示满格绿条 + 四个绿勾，看着像装好了。 */
            boolean finished = !running && lastEnd >= 0;
            boolean failed = finished && rc != 0;
            int reachedCount = Math.min(reached, total);
            int doneCount = failed ? Math.max(0, reachedCount - 1) : reachedCount;
            if (finished && !failed) doneCount = total;
            int failIdx = failed ? Math.max(0, Math.min(reachedCount, total) - 1) : -1;
            int pct = total > 0 ? Math.round(doneCount * 100f / total) : 0;
            o.put("running", running);
            o.put("comp", comp);
            o.put("stage", doneCount);
            o.put("doneCount", doneCount);
            o.put("total", total);
            o.put("pct", pct);
            o.put("failAt", failIdx);
            String cur = failed
                    ? ("卡在「" + stages[failIdx][1] + "」")
                    : stages[Math.min(reached, stages.length - 1)][1];
            o.put("label", cur);
            // 每一档的展示文案 + 状态，界面直接渲染成小清单
            JSONArray list = new JSONArray();
            for (int s = 0; s < total; s++) {
                JSONObject e = new JSONObject();
                e.put("label", stages[s][1]);
                e.put("done", s < doneCount);
                e.put("failed", s == failIdx);
                list.put(e);
            }
            o.put("steps", list);
            o.put("logAge", (System.currentTimeMillis() - f.lastModified()) / 1000L);
            o.put("ok", running ? JSONObject.NULL : (rc == 0));
            o.put("state", running ? "running" : (rc == 0 ? "done" : "failed"));
            return o.toString();
        } catch (Exception e) {
            return errJson("读取重装进度失败", e);
        }
    }

    @JavascriptInterface
    public String readLog(String name, int lines) {
        if (name == null) return "";
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.contains("..")) return "";
        File f = new File(act.logsDir(), name);
        if (!f.isFile()) {
            File alt = new File(act.qqbotDir(), "logs/" + name);
            if (alt.isFile()) f = alt;
        }
        return act.tail(f, lines <= 0 ? 400 : lines);
    }

    // 找一个日志文件的真实路径（.qqbot/cache/logs 优先，其次 .qqbot/logs），名字非法返回 null
    private File findLog(String name) {
        if (name == null) return null;
        name = name.trim();
        if (name.length() == 0) return null;
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.contains("..")) return null;
        File f = new File(act.logsDir(), name);
        if (f.isFile()) return f;
        File alt = new File(act.qqbotDir(), "logs/" + name);
        if (alt.isFile()) return alt;
        return null;
    }

    // 清空日志内容：文件留着，正在写它的进程握着同一个 fd，会继续往里记新日志
    @JavascriptInterface
    public String clearLog(String name) {
        File f = findLog(name);
        if (f == null) return "日志文件不存在";
        long n = f.length();
        try {
            new java.io.FileOutputStream(f, false).close();
            return "已清空 " + f.getName() + "（" + n + " 字节）";
        } catch (Exception e) {
            return "清空失败: " + String.valueOf(e);
        }
    }

    // 删除日志文件本身：进程还开着旧 fd，要等实例重启后才会重新生成同名日志
    @JavascriptInterface
    public String rmLog(String name) {
        File f = findLog(name);
        if (f == null) return "日志文件不存在";
        long n = f.length();
        if (f.delete()) return "已删除 " + f.getName() + "（" + n + " 字节）";
        return "删除失败，可能没有权限";
    }

    // 某个实例的登录二维码，返回 {"ok":true,"data":"data:image/png;base64,..."} 或 {"ok":false}
    @JavascriptInterface
    public String qr(String id) {
        return act.qr(id);
    }

    // 把某个实例的登录二维码图片用系统分享面板发出去（发到微信 / QQ / 另一台手机去扫）
    @JavascriptInterface
    public String qrShare(String id) {
        return act.qrShare(id);
    }

    // 当前登录 QQ 的头像：{"ok":true,"uin":"…","data":"data:image/png;base64,…","mtime":…}
    // 没有缓存时立刻返回 {"ok":false,"err":"下载中"} 并在后台线程去下，前端下一轮 refresh 再问
    @JavascriptInterface
    public String avatarInfo() {
        return AstrAvatar.info(act);
    }

    // 把当前头像钉成桌面图标：{"ok":true,"msg":"…"} 或 {"ok":false,"err":"…"}
    @JavascriptInterface
    public String avatarIconPin() {
        return AstrIconShortcut.pin(act);
    }

    // 开关某个实例的容器（只翻转 enable；沙盒里的守护进程 5 秒内会启停它）
    @JavascriptInterface
    public String toggleBot(String id) {
        try {
            JSONObject root = new JSONObject(act.readBots());
            JSONArray arr = root.optJSONArray("bots");
            boolean found = false;
            boolean now = false;
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject b = arr.optJSONObject(i);
                if (b == null) continue;
                if (!id.equals(b.optString("id", ""))) continue;
                now = !b.optBoolean("enable", true);
                b.put("enable", now);
                found = true;
            }
            JSONObject o = new JSONObject();
            if (!found) {
                o.put("ok", false);
                o.put("error", "找不到实例 " + id);
                return o.toString();
            }
            if (!act.writeBots(root.toString())) {
                // 原来无条件 ok=true：写盘被拒时前端照样本地翻转开关、提示已启动，
                // 4 秒后被轮询又翻回去，看起来像开关坏了。
                o.put("ok", false);
                o.put("error", "写入失败，改动没有落盘");
                return o.toString();
            }
            o.put("ok", true);
            o.put("id", id);
            o.put("enable", now);
            return o.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"写入失败\"}";
        }
    }

    // 删除某个实例（连它的 QQ 登录数据与配置一起删）。
    // delbot.sh 会改 bots.json、停容器、删数据、重写对接配置，要开 proot 沙盒（十几秒），
    // 所以丢到后台线程跑，立刻返回一句话，跑完再 Toast 一次。
    @JavascriptInterface
    public String removeBot(String id) {
        if (id == null || id.length() == 0) return "实例 id 为空";
        // id 要拼进 bash 命令行，还会变成 /opt/qqbot-inst/<id> 与 qq-<id>.log 的路径，
        // 所以只放行字母数字、下划线、连字符和点，并禁掉 ".."：杜绝参数拆分与路径穿越。
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.';
            if (!safe) return "实例 id 含非法字符，已拒绝删除：" + id;
        }
        if (id.indexOf("..") >= 0) return "实例 id 不合法，已拒绝删除：" + id;
        File s = new File(act.usrDir(), "opt/qqbot/delbot.sh");
        if (!s.isFile()) return "delbot.sh 缺失，请先完成部署";
        Thread th = new Thread(new BgTask(act,
                "bash " + act.usrDir().getAbsolutePath() + "/opt/qqbot/delbot.sh " + id,
                "实例 " + id + " 已删除", "删除实例 " + id + " 失败", 300));
        th.setDaemon(true);
        th.start();
        return "正在删除实例 " + id + "…";
    }

    // ---- 自定义背景图 ----
    @JavascriptInterface
    public String bgImage() {
        return act.bgImage();
    }

    @JavascriptInterface
    public void pickBg() {
        act.activity().runOnUiThread(new UiTask(act, UiTask.PICKBG, ""));
    }

    @JavascriptInterface
    public String clearBg() {
        return act.clearBg();
    }

    @JavascriptInterface
    public String action(String what) {
        if (what == null) return "bad action";
        String home = act.homeDir().getAbsolutePath();
        String prefix = act.usrDir().getAbsolutePath();
        String qh = act.qqbotDir().getAbsolutePath();
        if ("start".equals(what)) {
            File pid = new File(act.qqbotDir(), "supervisor.pid");
            if (act.pidAlive(pid)) return "服务已经在跑了";
            // setsid 脱离应用 shell 的进程组（runShell 结束时会按进程组 destroy 该 shell），
            // pid 由 start-sandbox.sh 自己写
            // --user-start：这是界面上的「启动」，要解除「紧急下线」标记
            act.runShell("mkdir -p " + qh + "/logs; nohup setsid bash " + prefix + "/opt/qqbot/start-sandbox.sh --user-start > "
                    + qh + "/logs/supervisor.log 2>&1 < /dev/null & sleep 3; echo ok", 30);
            if (act.pidAlive(pid)) return "服务已启动";
            // 失败时把 supervisor.log 的最后一行带出去，别只回一句 start failed
            String why = act.lastLogLine(act.supervisorLogFile());
            return "启动失败" + (why.length() > 0 ? "：" + why : "：服务没有写出日志，重进应用再试一次");
        }
        if ("stop".equals(what)) {
            // 停止必须交给 stop-sandbox.sh：直接 kill -9 proot 会让它来不及清理
            // --kill-on-exit  genealogy 里的子进程，留下孤儿占着端口并与日志刷 ENOSYS。
            // 该脚本会等 proot 自己退（最多 30 秒），所以不能同步等，丢后台线程。
            runBg("bash " + prefix + "/opt/qqbot/stop-sandbox.sh", "服务已停止", "停止服务失败", 180);
            return "stopping";
        }
        if ("restart".equals(what)) {
            runBg("bash " + prefix + "/opt/qqbot/stop-sandbox.sh; mkdir -p " + qh + "/logs; "
                    + "nohup setsid bash " + prefix + "/opt/qqbot/start-sandbox.sh --user-start > " + qh
                    + "/logs/supervisor.log 2>&1 < /dev/null & sleep 4; echo ok",
                    "服务已重启", "重启服务失败", 240);
            return "restarting";
        }
        if ("deploy".equals(what)) {
            return act.startDeploy();
        }
        if ("copylog".equals(what)) {
            // 一键把部署日志复制出来。用户反馈「卡在第三步」时，让他念一遍 apt 的输出
            // 是不现实的；复制到剪贴板粘给我，才可能定位到真正的原因。
            String t = act.stripAnsi(act.tail(act.deployLogFile(), 200));
            if (t == null) t = "";
            int n = t.length() == 0 ? 0 : t.split("\n").length;
            copy(t);
            return "已复制部署日志（" + n + " 行）";
        }
        if ("stopdeploy".equals(what)) {
            String pid = "";
            try {
                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(new java.io.FileInputStream(act.deployPidFile()), "UTF-8"));
                pid = br.readLine();
                br.close();
            } catch (Exception ignored) {
            }
            if (pid != null && pid.trim().length() > 0) {
                // deploy.sh 是用 setsid 起来的，它是自己会话/进程组的组长，
                // 所以要 kill 负 PID 连带清掉它下面的步骤脚本
                String p = pid.trim();
                act.runShell("kill -TERM -" + p + " 2>/dev/null; kill -TERM " + p + " 2>/dev/null; sleep 1; "
                        + "kill -KILL -" + p + " 2>/dev/null; kill -KILL " + p + " 2>/dev/null; echo stopped", 20);
            }
            act.runShell("rm -f " + qh + "/deploy.pid; echo ok", 10);
            return "stopped";
        }
        if ("fixdpkg".equals(what)) {
            // 第 3 步（30-apt）最常见的卡死原因是：上次部署被打断，dpkg 留下半配置的包，
            // 之后每一次 apt-get install 都会在开头硬失败（用户重开应用也只会一遍遍撞墙）。
            // 这个入口让用户不用重新部署、不用重装 APK 就能就地修好。
            // 必须丢后台线程：proot 冷启动 + dpkg --configure -a 可能要几十秒，
            // 同步跑会把 WebView 的 JS 线程卡住，页面看起来像死了。
            runBg("bash " + prefix + "/opt/qqbot/fix-sandbox.sh",
                    "沙盒依赖检查完成，回主页点「继续部署」", "修复失败", 900);
            return "已提交修复，几十秒后回主页点「继续部署」";
        }
        if ("linkbg".equals(what)) {
            // 主页冷启动时自动对接用的轻量入口：只跑 link.sh（幂等——重写各实例的 OneBot
            // 配置与 AstrBot 的 aiocqhttp 平台列表），**不**像 relink 那样再停服务、拉起来。
            // 前端以前是直接 Android.exec(...) 同步跑 link.sh 的，而那个桥调用会阻塞到
            // 超时（几十秒），整段时间 WebView 的 JS 线程卡死、页面点不动，所以这里必须
            // 和 relink 一样丢到后台线程，立刻返回一句话。
            runBg("bash " + prefix + "/opt/qqbot/link.sh >/dev/null 2>&1; echo ok",
                    "已自动对接 NapCat 与 AstrBot", "自动对接失败", 300);
            return "已提交自动对接";
        }
        if ("relink".equals(what)) {
            // 重新生成各实例的 OneBot 配置与 AstrBot 的 aiocqhttp 平台列表，并重启沙盒让配置立即生效。
            // link.sh 要开 proot 沙盒（先停掉 AstrBot 等它退出、改配置、再由 run-all.sh 拉起来），
            // 整套下来十几秒起步。以前这里是同步调用，会把 WebView 的 JS 线程整整卡住这么久，
            // 期间页面滚动、点击全部无响应，看起来就像「点了没反应」，所以和 stop / restart 一样
            // 丢到后台线程，立刻返回一句话。随后再走一遍 stop + start，确保配置一定生效。
            runBg("bash " + prefix + "/opt/qqbot/link.sh >/dev/null 2>&1; "
                    + "bash " + prefix + "/opt/qqbot/stop-sandbox.sh; mkdir -p " + qh + "/logs; "
                    + "nohup setsid bash " + prefix + "/opt/qqbot/start-sandbox.sh --user-start > " + qh
                    + "/logs/supervisor.log 2>&1 < /dev/null & sleep 4; echo ok",
                    "已对接并重启服务", "对接并重启失败", 300);
            return "已提交对接，正在重启服务…";
        }
        if ("ports:auto".equals(what)) {
            // 自动挑一套没被占用、彼此也不冲突的端口（AstrBot 控制台 + 每个实例的
            // WebUI/OneBot）并写盘，返回一句中文说明给前端 toast
            return act.autoPorts();
        }
        if ("reinstall:napcat".equals(what) || "reinstall:astrbot".equals(what)) {
            // 单组件重装：脚本要进 proot 重装 QQ/NapCat 或 AstrBot，几分钟起步，
            // 所以和 deploy/start 一样只负责把沙盒外的入口脚本拉起来，立刻返回。
            String comp = what.substring("reinstall:".length());
            String label = "napcat".equals(comp) ? "NapCat" : "AstrBot";
            File script = new File(act.usrDir(), "opt/qqbot/reinstall.sh");
            if (!script.isFile()) return "重装脚本缺失，请先重新部署";
            // 日志写在 cache/logs（= 沙盒里的 /qqbot-cache/logs）下：日志页只枚举那一个
            // 目录，写到 $HOME/.qqbot/logs 用户就永远看不到重装进度。
            String logDir = act.logsDir().getAbsolutePath();
            // setsid 同 startDeploy：runShell 返回后 Android 会按进程组 destroy 掉这个 shell，
            // 不脱离进程组的话重装脚本刚起来就被连带杀掉（pid 写了却查不到进程）。
            act.runShell("mkdir -p " + logDir + "; "
                    + "nohup setsid bash " + script.getAbsolutePath() + " " + comp
                    + " >> " + logDir + "/reinstall.log 2>&1 < /dev/null & "
                    + "sleep 2; echo ok", 120);
            return "已开始重装 " + label + "，进度见日志页（约几分钟）";
        }
        if (what.startsWith("svc:")) {
            // svc:napcat:off / svc:napcat:on / svc:astrbot:off / svc:astrbot:on
            String[] pr = what.split(":");
            if (pr.length != 3) return "bad svc action";
            String name = pr[1];
            boolean off = "off".equals(pr[2]);
            if (!"napcat".equals(name) && !"astrbot".equals(name)) return "bad svc name";
            if (!act.setSvcOff(name, off)) return "开关写入失败";
            String label = "napcat".equals(name) ? "NapCat" : "AstrBot";
            if (!act.pidAlive(new File(act.qqbotDir(), "supervisor.pid"))) {
                // 容器没跑时守护循环不在，标记不会立刻生效，但已经存下来了
                return (off ? "已记下：关闭 " : "已记下：启动 ") + label + "（容器未运行，下次启动生效）";
            }
            // 守护循环 3 秒一轮，给界面一个立即的反馈，真实状态等下一轮 refresh 纠正
            return off ? ("正在关闭 " + label + "…") : ("正在启动 " + label + "…");
        }
        if (what.startsWith("guard:")) {
            // guard:guard:on|off / guard:wake:on|off
            String[] pr = what.split(":");
            if (pr.length != 3) return "bad guard action";
            boolean on = "on".equals(pr[2]);
            if ("guard".equals(pr[1])) return QqbotGuardService.setGuard(act, on);
            if ("wake".equals(pr[1])) return QqbotGuardService.setWake(act, on);
            if ("screen".equals(pr[1])) return ScreenMode.setOn(act, on);
            return "bad guard name";
        }
        if ("battery".equals(what)) {
            return QqbotGuardService.requestIgnoreBattery(act);
        }
        if ("fgs:fix".equals(what)) {
            // 「重新挂上前台服务」：把常驻通知 + 前台身份重新钉回去。
            // 通知被 ROM 清掉、渠道被用户关掉后又打开、服务被系统降级，都靠这一个动作救回来。
            return QqbotGuardService.repair(act);
        }
        if ("resetdeploy".equals(what)) {
            act.runShell("rm -f " + qh + "/deploy.done " + qh + "/deploy.pid; echo ok", 10);
            return "reset";
        }
        return "unknown action";
    }

    @JavascriptInterface
    public String saveBots(String json) {
        // writeBots 会在「这次保存会让已有实例消失」或「实例列表为空」时拒绝
        // 写盘（删除实例只走 delbot.sh），所以这里必须把结果透出去而不是无条件 ok。
        if (act.writeBots(json)) return "已保存";
        String note = act.botsCorruptNote();
        if (note.length() > 0) return note;
        return "保存被忽略：这次改动会丢失已有实例，请刷新页面重试";
    }

    @JavascriptInterface
    public String loadBots() {
        return act.readBots();
    }

    /** 扫沙盒里 NapCat 写下的 napcat_<uin>.json，把登录后的 QQ 号回给界面自动填。 */
    @JavascriptInterface
    public String qqDetect() {
        try {
            return act.detectQq();
        } catch (Throwable t) {
            return errJson("读取 QQ 号失败", t);
        }
    }

    // ---- 首次启动免责声明 ----
    // 只回 "1"/"0"；标记文件读不了（权限错等）时按「没同意过」处理，页面还会再弹一次，
    // 比默默当作已同意安全。绝不把异常抛到 JS 侧。
    @JavascriptInterface
    public String disclaimerSeen() {
        try {
            return act.isDisclaimerAcked() ? "1" : "0";
        } catch (Throwable t) {
            return "0";
        }
    }

    @JavascriptInterface
    public String disclaimerAck() {
        try {
            return act.ackDisclaimer() ? "ok" : "fail";
        } catch (Throwable t) {
            return "fail";
        }
    }

    // ---- 下载代理 ----
    // proxyGet 永远返回字符串（没有就是空串）；proxySet 由 writeProxy 校验，
    // 非法输入返回 "fail" 且不改盘上的值。
    @JavascriptInterface
    public String proxyGet() {
        try {
            return act.readProxy();
        } catch (Throwable t) {
            return "";
        }
    }

    @JavascriptInterface
    public String proxySet(String v) {
        try {
            return act.writeProxy(v) ? "ok" : "fail";
        } catch (Throwable t) {
            return "fail";
        }
    }

    @JavascriptInterface
    public void copy(String text) {
        act.activity().runOnUiThread(new UiTask(act, UiTask.COPY, text == null ? "" : text));
    }

    @JavascriptInterface
    public void toast(String msg) {
        act.activity().runOnUiThread(new UiTask(act, UiTask.TOAST, msg == null ? "" : msg));
    }

    @JavascriptInterface
    public void openPanel(String url) {
        String u = url == null ? "" : url.trim();
        // 只允许本机地址，避免被网页内容导向外部站点
        if (!(u.startsWith("http://127.0.0.1:") || u.startsWith("http://localhost:"))) return;
        act.activity().runOnUiThread(new UiTask(act, UiTask.PANEL, u));
    }

    // ---- 打开外部链接 / 加入 QQ 群 ----
    // 两个方法都必须把结果同步带回 JS（前端要照着提示走不同分支），所以不在这里
    // 直接 startActivity，而是交给 runOnUiSync 在主线程执行并等结果。
    @JavascriptInterface
    public String openUrl(String url) {
        try {
            String u = url == null ? "" : url.trim();
            if (u.length() == 0) return "链接为空";
            String low = u.toLowerCase(java.util.Locale.ROOT);
            // 先在桥里挡掉非 http/https（javascript:、intent:、file: 等），这类输入
            // 根本不会走到 Intent；真正发 Intent 前 Activity 里还会再校验一次。
            if (!(low.startsWith("http://") || low.startsWith("https://"))) return "只支持 http 链接";
            String r = act.runOnUiSync(new UiTask(act, UiTask.OPENURL, u));
            if (r == null) return "无法打开链接";   // 任务没跑起来，Intent 也没发出
            return (r.length() == 0) ? "ok" : r;
        } catch (Throwable t) {
            return "无法打开链接";
        }
    }

    // 拉起 QQ 加群卡片；没装 QQ / 模拟器上失败返回 "fail"，前端据此提示手动搜索群号
    @JavascriptInterface
    public String openQqGroup(String group) {
        try {
            String g = group == null ? "" : group.trim();
            if (g.length() == 0) return "fail";
            for (int i = 0; i < g.length(); i++) {
                char c = g.charAt(i);
                if (c < '0' || c > '9') return "fail";
            }
            String r = act.runOnUiSync(new UiTask(act, UiTask.QQGROUP, g));
            return "ok".equals(r) ? "ok" : "fail";
        } catch (Throwable t) {
            return "fail";
        }
    }

    // ---- 通知栏权限 ----
    /** {"ok":true,"granted":bool,"sdk":33}；granted=false 表示常驻通知发不出来。 */
    @JavascriptInterface
    public String notiState() {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("granted", act.notiGranted());
            o.put("sdk", android.os.Build.VERSION.SDK_INT);
            return o.toString();
        } catch (Throwable t) {
            return errJson("读通知权限", t);
        }
    }

    /** 去开通知权限：13+ 先弹系统框，拒过就跳通知设置页。 */
    @JavascriptInterface
    public void notiAsk() {
        act.activity().runOnUiThread(new UiTask(act, UiTask.NOTI, ""));
    }

    @JavascriptInterface
    public void openTerminal() {
        act.activity().runOnUiThread(new UiTask(act, UiTask.TERMINAL, ""));
    }

    // ---- 检查更新 ----
    // 拆成 start + poll 两步：桥方法是同步的，一次网络请求会把 WebView 的 JS 线程
    // 卡住十几秒（国内直连 api.github.com 经常超时）。start 立刻返回，前端每 500ms
    // 轮询一次 poll，页面全程不卡。
    @JavascriptInterface
    public void checkUpdateStart() {
        CheckUpdate.start(appVersion());
    }

    /** 空串 = 还在查；非空 = 一段 JSON（ok / current / latest / hasUpdate / url / size ...） */
    @JavascriptInterface
    public String checkUpdatePoll() {
        return CheckUpdate.poll();
    }

    /* ---------- AstrBot 配置 / 中转站 / 官网版本 ---------- */

    /** 当前 AstrBot 的模型与人格配置（给界面回填）。 */
    @JavascriptInterface
    public String astrInfo() {
        try {
            return AstrCfg.info(act.filesDirPub());
        } catch (Throwable t) {
            return errJson("读取 AstrBot 配置失败", t);
        }
    }

    /**
     * 「访问地址」卡上的账号 / 密码 / Token，全部按真实配置文件现读（m11049）。
     * 密码用「默认密码真登录一次」来判别：改过密码就返回空串，前端据此显示「你改过密码」。
     */
    @JavascriptInterface
    public String creds() {
        try {
            return AstrAccount.info(act);
        } catch (Throwable t) {
            return errJson("读取账号信息失败", t);
        }
    }

    /** 保存配置：停沙盒 → 写文件 → 重新拉起，进度用 astrSavePoll() 轮。 */
    @JavascriptInterface
    public String astrSave(String base, String model, String key, String persona) {
        try {
            if (AstrSaveTask.busy()) return "{\"ok\":false,\"err\":\"正在应用上一次改动，请稍等\"}";
            Thread th = new Thread(new AstrSaveTask(act, base, model, key, persona), "qqbot-astrsave");
            th.setDaemon(true);
            th.start();
            return "{\"ok\":true}";
        } catch (Throwable t) {
            return errJson("保存失败", t);
        }
    }

    @JavascriptInterface
    public String astrSavePoll() {
        return AstrSaveTask.poll();
    }

    /** 用密钥拉中转站的模型列表。 */
    @JavascriptInterface
    public void astrModelsStart(String base, String key) {
        /*
         * 密钥框是密码型输入，真实密钥从不回填（只显示尾号），所以用户点「获取列表」时
         * 那个框绝大多数时候是空的。空串不能直接丢给中转站 —— 没有 Authorization 头，
         * 对方一律回 401，界面就报「密钥不对或没填」，可他早就保存过了。
         * 空串一律回落到配置文件里那把（与「留空表示不修改」的提示保持一致）。
         */
        String k = (key == null) ? "" : key.trim();
        if (k.length() == 0) {
            try { k = AstrCfg.storedKey(act.filesDirPub()); } catch (Throwable ignored) { k = ""; }
        }
        AstrModels.start(base, k);
    }

    @JavascriptInterface
    public String astrModelsPoll() {
        return AstrModels.poll();
    }

    /** 官网版本号（GitHub 连不上时的备选来源）。 */
    @JavascriptInterface
    public void siteVerStart() {
        SiteVer.start();
    }

    @JavascriptInterface
    public String siteVerPoll() {
        return SiteVer.poll();
    }

    @JavascriptInterface
    public String exec(String cmd) {
        return act.runShell(cmd, 60);
    }

    /**
     * 把这次失败的现场打包上传给作者（只有用户点了「上传错误日志」才会走到这里）。
     *
     * 为什么不在后台跑：上传的是几十 KB 文本，正常 2~10 秒就回来了，同步返回能让界面
     * 直接把「已上传，编号 xxxx」显示出来；超时给 55 秒，比脚本里 curl 的 45 秒长一点，
     * 免得 curl 还没超时 shell 就先被砍掉、用户只看到一句空结果。
     */
    @JavascriptInterface
    public String uploadLog() {
        return runUploadLog();
    }

    /**
     * 带「想给开发者的话」的上传（beta.10 起两个上传入口都会先问一句）。
     *
     * note 先落盘成沙盒里的 $HOME/.qqbot/devnote.txt（= act.qqbotDir()/devnote.txt），
     * upload-log.sh 会在脱敏之前把它贴到整份报告的最前面；脱敏是对合并后的整份文件跑的，
     * 所以留言里写到的账号、手机号一样会被打码。留言为空就把旧文件删掉，
     * 免得上一轮写的附言被悄悄带进这次报告。写文件失败不阻断上传：
     * 附言是可选的，日志本身更重要。
     */
    @JavascriptInterface
    public String uploadLogNote(String note) {
        writeDevNote(note);
        return runUploadLog();
    }

    /** 把附言写进沙盒；空串 = 删掉旧附言。任何异常都静默，上传不靠它成不成。 */
    private void writeDevNote(String note) {
        File f = new File(act.qqbotDir(), "devnote.txt");
        String s = note == null ? "" : note.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (s.length() > 1000) s = s.substring(0, 1000);
        try {
            if (s.length() == 0) {
                if (f.exists()) f.delete();
                return;
            }
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(f), "UTF-8");
            try {
                w.write(s);
                w.write("\n");
            } finally {
                w.close();
            }
        } catch (Throwable ignored) {
            /* 写不进去就算了，日志照传 */
        }
    }

    /** uploadLog()/uploadLogNote() 共用的那段：脚本存在性检查 + 同步跑 shell。 */
    private String runUploadLog() {
        String script = act.usrDir() + "/opt/qqbot/upload-log.sh";
        File f = new File(script);
        if (!f.exists()) return "上传失败：这台手机上的脚本还没就位（" + script + "），请先完成部署";
        String out = act.runShell("bash " + script + " " + appVersion(), 55);
        String s = out == null ? "" : out.trim();
        if (s.length() == 0) return "上传没有回音（可能网络不通），稍后再试一次";
        return s;
    }

    /**
     * 运行环境自检（设置页「检查运行环境」按钮）。
     *
     * 必须放后台跑：脚本要在沙盒里真起三次 Xvfb，加起来经常 30 秒以上，同步等会把
     * WebView 的 JS 线程卡死。结果由脚本自己写进 ~/.qqbot/doctor.txt，前端轮询 doctorText()。
     */
    @JavascriptInterface
    public void doctorStart() {
        String script = act.usrDir() + "/opt/qqbot/doctor.sh";
        File f = new File(script);
        if (!f.exists()) {
            act.doToast("自检脚本还没就位，请先完成部署");
            return;
        }
        runBg("bash " + script + " >/dev/null 2>&1", "环境自检已结束", "环境自检没跑完", 240);
    }

    /** 自检结果（doctor.txt 末 200 行）；空串 = 还没跑过。 */
    @JavascriptInterface
    public String doctorText() {
        File f = new File(act.qqbotDir(), "doctor.txt");
        if (!f.exists()) return "";
        return act.tail(f, 200);
    }

    /* ================= v1.1.30 第一批：自愈 / 风控 / 省电 / 降载 / 下线 ================= */
    /* 这一批的方法全部返回 JSON（成功 {"ok":true,...}，失败 {"ok":false,"err":"中文原因"}），
       并且全部只读内存或几百字节的小文件，毫秒级返回；要起进程/写脚本的一律丢后台线程。 */

    /** 失败 JSON：和 errJson 一样做转义，但字段按 v1.1.30 的约定用 ok/err。
     *  改成包内可见：BackupFlow / CrashBoard / StatsId 也要拼同一种失败 JSON，
     *  复制一份转义逻辑迟早会漏掉某个控制字符。 */
    static String failJson(String m) {
        String s = (m == null) ? "" : m;
        StringBuilder b = new StringBuilder("{\"ok\":false,\"err\":\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else if (c == '\t') b.append("\\t");
            else if (c < 0x20) b.append(' ');
            else b.append(c);
        }
        return b.append("\"}").toString();
    }

    /** 网络状态：纯内存快照，由 NetworkWatch 的系统回调维护。 */
    @JavascriptInterface
    public String netInfo() {
        return NetworkWatch.infoJson();
    }

    /** 开机/启动记录：$HOME/.qqbot/state/boot.json。 */
    @JavascriptInterface
    public String bootInfo() {
        return BootState.bootInfo(act.filesDirPub());
    }

    /** 风控：沙盒层写的 state/risk.json 原样带回，没有就 {"ok":true,"risk":false}。 */
    @JavascriptInterface
    public String riskInfo() {
        return RiskReader.riskInfo(act.filesDirPub());
    }

    /** 「我知道了」：删 risk.json 与所有 risk-*.flag，撤销通知，恢复自动重启。 */
    @JavascriptInterface
    public String riskClear() {
        String r = RiskReader.clear(act, act.filesDirPub());
        // 风控暂停期间沙盒可能已经被停掉；用户清完标记就立刻补一轮，不用等 60 秒自检
        try {
            if (!act.pidAlive(new File(act.qqbotDir(), "supervisor.pid"))) QqbotGuardService.kick(act);
        } catch (Throwable ignored) {
        }
        return r;
    }

    /** 厂商省电引导状态（manufacturer / hint / shown），前端据此决定要不要自动提示。 */
    @JavascriptInterface
    public String powerState() {
        return PowerGuide.powerState(act.filesDirPub(), appVersion());
    }

    /** 用户点了「去设置」：按厂商逐个试跳转，全失败退应用详情页。 */
    @JavascriptInterface
    public String powerGuide() {
        return PowerGuide.powerGuide(act, appVersion());
    }

    /** 前端弹过提示（用户关掉横幅）也记一笔，避免同一版本反复打扰。 */
    @JavascriptInterface
    public String powerAck() {
        return PowerGuide.ack(act.filesDirPub(), appVersion());
    }

    /** 电量/温度/降载状态（BatteryMon 的内存快照）。 */
    @JavascriptInterface
    public String powerInfo() {
        return BatteryMon.powerInfo();
    }

    /**
     * 紧急下线：先写 state/offline（守护循环与网络自愈看到它就不再拉起沙盒），
     * 再丢后台线程跑 stop-sandbox.sh（脚本要等 proot 自己退，同步跑会把 JS 线程卡死）。
     * 只停进程，绝不删配置/登录数据/AstrBot data。
     *
     * 写标记失败绝不能回 ok:true —— 前端拿到 ok 就会提示「已下线」，而守护下一轮自检
     * 照样把沙盒拉起来。返回 ok 之前必须确认标记真的落盘。
     */
    @JavascriptInterface
    public String emergencyStop() {
        try {
            File filesDir = act.filesDirPub();
            // 标记必须最先落盘：哪怕下面的脚本不存在，也不能让守护把沙盒重新拉起来
            File off = StateIO.file(filesDir, "offline");
            if (!StateIO.writeStamp(off) || !off.isFile()) {
                android.util.Log.e("QqbotStop", "写 offline 标记失败：" + off.getAbsolutePath());
                return failJson("写 offline 标记失败，守护可能还会拉起沙盒");
            }
            File stop = new File(new File(filesDir, "usr"), "opt/qqbot/stop-sandbox.sh");
            if (!stop.isFile()) {
                android.util.Log.e("QqbotStop", "缺少 stop-sandbox.sh：" + stop.getAbsolutePath());
                return failJson("缺少 stop-sandbox.sh，请先完成部署");
            }
            Thread th = new Thread(new EmergencyStop(filesDir), "qqbot-emergency-stop");
            th.setDaemon(true);
            th.start();
            return "{\"ok\":true}";
        } catch (Throwable t) {
            return failJson("下线失败：" + t);
        }
    }

    /**
     * 恢复：删 offline 标记，按守护开关拉起服务，并立刻踢一轮自检。
     * 删标记和拉起守护的结果都要反映到返回值里，不再无论成败都回 ok:true。
     * apply() 是 void（调用点太多），这里改用同实现的 applyEx() 拿启动请求的真实结果。
     */
    @JavascriptInterface
    public String emergencyResume() {
        try {
            File filesDir = act.filesDirPub();
            File off = StateIO.file(filesDir, "offline");
            StateIO.delete(off);
            if (off.isFile()) {
                android.util.Log.e("QqbotStop", "删除 offline 标记失败：" + off.getAbsolutePath());
                return failJson("删除 offline 标记失败，守护仍不会拉起沙盒");
            }
            // 用户自己关掉守护时 applyEx 也回 true（只是停服，不算失败）
            if (!QqbotGuardService.applyEx(act)) {
                android.util.Log.e("QqbotStop", "恢复失败：守护服务启动请求未发出");
                return failJson("守护服务未启动（系统可能限制了后台启动），沙盒不会自动拉起，请打开面板重试");
            }
            // 守护开着就立刻跑一次重启，不用等下一个 60 秒自检周期（尽力而为，失败无返回值）
            QqbotGuardService.kick(act);
            return "{\"ok\":true}";
        } catch (Throwable t) {
            return failJson("恢复失败：" + t);
        }
    }

    /* ================= v1.1.30 第二批：今日概览 / 实例卡片 ================= */
    /* 这三个同样只读内存或几百字节的小文件；真正要扫日志/联网的活儿都由守护循环的后台线程干。 */

    /**
     * 今日概览：day/onlineSec/msgs/errors/rxBytes/txBytes + warm。
     * warm:false 表示守护循环还没跑完第一轮统计，前端应显示「统计中」而不是一排 0。
     */
    @JavascriptInterface
    public String todayStats() {
        return TodayStats.todayStats();
    }

    /** 实例卡片数据：state/instances.json 原样带回，读不到给空表（前端照常渲染空卡片）。 */
    @JavascriptInterface
    public String instancesInfo() {
        return InstancesState.info(act.filesDirPub());
    }

    /** 头像：只查本地缓存，未命中就丢后台下载，本轮返回空路径（前端下次轮询自然命中）。 */
    @JavascriptInterface
    public String avatarPath(String uin) {
        return AvatarCache.avatarPath(act.filesDirPub(), uin);
    }

    /** 内置离线包状态（1.2.0）：安装包带没带离线包、释放进度、目标架构。
     *
     *  只探一个 asset 加读两个小文件，不做磁盘扫描 —— 这个方法会被 4 秒一次的刷新调用。
     */
    @JavascriptInterface
    public String bundleInfo() {
        try {
            if (!act.hasBundle()) {
                // 1.2.1 起安装包默认不带镜像（瘦包）：这不是「没提供」，而是「首次部署时
                // 从官网下载」。界面按 slim 分支描述，别让用户以为缺文件。
                return "{\"ok\":true,\"asset\":false,\"slim\":true}";
            }
            java.io.File b = act.bundleFile();
            long have = b.isFile() ? b.length() : 0L;
            StringBuilder sb = new StringBuilder(160);
            sb.append("{\"ok\":true,\"asset\":true");
            sb.append(",\"total\":").append(act.bundleAssetSize());
            sb.append(",\"size\":").append(have);
            sb.append(",\"ready\":").append(act.bundleReady());
            sb.append(",\"pending\":").append(act.bundlePendingFile().isFile());
            sb.append(",\"done\":").append(act.deployDoneFile().isFile());
            sb.append(",\"arch\":\"").append(act.bundleArch()).append("\"");
            sb.append("}");
            return sb.toString();
        } catch (Throwable t) {
            return failJson("内置包状态读取失败：" + t);
        }
    }

    /* ================= v1.1.30 第三批：备份 / 恢复（功能 50） ================= */
    /* 这一批一律 start + poll：备份要打包几百 MB、恢复要停服解包再重启，
       同步等会把 WebView 的 JS 线程卡死几十秒。全部结果落 state/backup.json、
       state/restore.json，poll 只读内存和这两个几百字节的小文件。 */

    /** 立即整机备份（scope=all）：后台跑 backup.sh，进度看 backupPoll()。 */
    @JavascriptInterface
    public String backupStart() {
        return BackupFlow.startBackup(act, "all");
    }

    /** 指定范围备份：scope = all / config / plugins（其余值直接拒绝）。 */
    @JavascriptInterface
    public String backupStartScope(String scope) {
        return BackupFlow.startBackup(act, scope);
    }

    /** 备份进度：state(running/done/error/idle)、pct、name、size、sha256、product、err。 */
    @JavascriptInterface
    public String backupPoll() {
        return BackupFlow.pollBackup(act.filesDirPub());
    }

    /**
     * 把备份产物「另存为」到用户挑的位置（ACTION_CREATE_DOCUMENT）。
     * 用户选完落点后由 onActivityResult 起后台线程拷贝，结果推 window.backupPicked(json)。
     */
    @JavascriptInterface
    public String backupPick() {
        return BackupFlow.pickBackup(act);
    }

    /** 面板数据：{"ok":true,"last":{…},"restoreLast":{…}} + 目标目录大小 srcBytes。 */
    @JavascriptInterface
    public String backupInfo() {
        return BackupFlow.info(act.filesDirPub());
    }

    /**
     * 选一个备份包准备恢复（ACTION_OPEN_DOCUMENT）。选完只把文件拷进缓存并校验，
     * 不直接开跑：结果推 window.restorePicked(json)（带 needConfirm:true），
     * 前端弹二次确认后再调 restoreApply()。
     */
    @JavascriptInterface
    public String restorePick() {
        return BackupFlow.pickRestore(act, "all");
    }

    /** 同上，但要按范围恢复：scope = all / config / plugins。 */
    @JavascriptInterface
    public String restorePickScope(String scope) {
        return BackupFlow.pickRestore(act, scope);
    }

    /** 二次确认后真的开始恢复：后台跑 restore.sh（停服/解包/重启都在脚本里）。 */
    @JavascriptInterface
    public String restoreApply() {
        return BackupFlow.applyRestore(act);
    }

    /** 恢复进度：state(running/picked/done/error/idle)、pct、needConfirm、err。 */
    @JavascriptInterface
    public String restorePoll() {
        return BackupFlow.pollRestore(act.filesDirPub());
    }

    /* ================= v1.1.30 第三批：崩溃聚合（功能 58） ================= */
    /* 采集在沙盒脚本里（crash-agg.sh），这里只读脚本写的 counts 文件、维护开关、清空。
       v1.1.30 起原生层会真的上报：开关默认关；关着时一个字节都不发、连 DNS 都不解析；
       开着时由 CrashUploadTask 把聚合类逐条 POST 到 /api/crash（每天最多成功一次）。 */

    /** 崩溃统计：{"ok":true,"items":[{sig,module,signal,count,last}],"total":N}；没崩过给空数组。 */
    @JavascriptInterface
    public String crashInfo() {
        return CrashBoard.info(act.filesDirPub());
    }

    /** 契约（docs §9）里的名字，和 crashInfo 同一个实现，前端用哪个都行。 */
    @JavascriptInterface
    public String crashBoard() {
        return CrashBoard.info(act.filesDirPub());
    }

    /** 崩溃上报开关：只落盘，打开时不立刻发请求；默认 false。真正发请求的时机是冷启动 / 6 小时巡检 / 手动点「立即上报」。 */
    @JavascriptInterface
    public String crashReportOn(boolean on) {
        return CrashBoard.setReport(act.filesDirPub(), on);
    }

    /** 契约里的名字，等价 crashReportOn。 */
    @JavascriptInterface
    public String crashUploadSet(boolean on) {
        return CrashBoard.setReport(act.filesDirPub(), on);
    }

    @JavascriptInterface
    public String crashReportState() {
        return CrashBoard.state(act.filesDirPub());
    }

    /**
     * 手动「立即上报」：开关开着就起后台线程真发一次，并立刻回 {"ok":true,"queued":true}；
     * 本方法自身不做网络 IO、不等结果，桥线程不会被卡住。
     * 开关关着回 {"ok":false,"err":"崩溃上报开关是关的","queued":false}，且不解析 DNS。
     */
    @JavascriptInterface
    public String crashUploadNow() {
        return CrashBoard.queue(act.filesDirPub(), appVersion());
    }

    /** 清空崩溃计数：两个布局的 counts 文件都删。 */
    @JavascriptInterface
    public String crashClear() {
        return CrashBoard.clear(act.filesDirPub());
    }

    /* ================= v1.1.30 第三批：匿名统计（功能 59） ================= */
    /* 只维护「开关 + 一个随机安装 ID」，默认关、关时 id 为空串。
       v1.1.30 起原生层会真的上报：开关开着时 StatsUploadTask 每天 POST 一条今日概览到
       /api/stats（events.open 是启动累计次数）；关着一个字节都不发、连 DNS 都不解析。 */

    /** 开关：打开时没有 ID 就生成一个 UUID 落 state/stats-id，返回 {"ok":true,"id":"…","on":true}。 */
    @JavascriptInterface
    public String statsOn(boolean on) {
        return StatsId.on(act.filesDirPub(), on);
    }

    /** 契约里的名字，等价 statsOn。 */
    @JavascriptInterface
    public String statsSet(boolean on) {
        return StatsId.on(act.filesDirPub(), on);
    }

    /** 当前状态：{"ok":true,"id":"…","on":bool,"atomOn":true,"lastSentDay":"…","lastErr":"…","lastAt":N}；未开启时 id 为空串。 */
    @JavascriptInterface
    public String statsId() {
        return StatsId.id(act.filesDirPub());
    }

    /** 契约里的名字，等价 statsId。 */
    @JavascriptInterface
    public String statsState() {
        return StatsId.id(act.filesDirPub());
    }

    /** 重新生成安装 ID（旧 ID 作废），返回新 id；开关状态不变。 */
    @JavascriptInterface
    public String statsReset() {
        return StatsId.reset(act.filesDirPub());
    }

    /** 事件流水：原生层不落事件表，只回空数组 + 今日在线秒数。 */
    @JavascriptInterface
    public String statsEvents() {
        return StatsId.events(act.filesDirPub());
    }

    /* ================= v1.1.30 第四批：存储占用（功能 54） ================= */
    /* 数字全部来自沙盒脚本 $PREFIX/opt/qqbot/storage.sh（它自己 du 五个固定目录，
       写 $QQBOT_HOME/cache/storage.json）。原生只负责「在后台线程跑脚本 + 归一化 + 缓存」，
       绝不在桥线程上做任何文件遍历。 */

    /**
     * 存储占用：{"ok":true,"warm":bool,"stale":bool,"ts":N,"total":N,
     *            "dirs":[{"key","name","path","bytes"}],"top":[{"path","name","bytes"}]}。
     * 第一次调用没有缓存时立刻回 warm:false（dirs/top 为空）并在后台起 StorageStatsTask；
     * 缓存超过 60 秒会边回旧数据（stale:true）边重算。本方法不阻塞。
     */
    @JavascriptInterface
    public String storageStats() {
        return StorageStatsTask.stats(act.filesDirPub());
    }

    /** 契约里的别名（前端 storageStats / storageInfo 都认）。 */
    @JavascriptInterface
    public String storageInfo() {
        return StorageStatsTask.stats(act.filesDirPub());
    }

    /** 强制重算：只起线程，立刻回 {"ok":true,"started":true}。 */
    @JavascriptInterface
    public String storageRefreshStart() {
        return StorageStatsTask.refreshStart(act.filesDirPub());
    }

    /** 契约里的别名（storageRefreshStart / storageRefresh 都认）。 */
    @JavascriptInterface
    public String storageRefresh() {
        return StorageStatsTask.refreshStart(act.filesDirPub());
    }

    /**
     * 清理缓存：同步等脚本（要 du 前后两次，慢的十几秒），返回
     * {"ok":true,"freed":N,"kept":["登录数据","配置","插件"],"log":"<脚本最后一行>"}；
     * 失败 {"ok":false,"err":"…"}。只走脚本自己的白名单，
     * 登录数据 / 配置 / 插件 / 实例目录 / bots.json / rootfs 一律不动。
     */
    @JavascriptInterface
    public String storageClean() {
        return StorageStatsTask.clean(act.filesDirPub());
    }

    /* ================= v1.1.30 第四批：公告（功能 56） ================= */
    /* 全应用唯一「只读」的出口：GET /api/announce?ver=<versionName>，
       带 If-None-Match；URL 里只有版本号，不带任何设备标识。5 秒超时、失败静默、
       结果缓存到 state/ann.json（etag + items），距上次成功刷新不足 5 分钟不发请求。 */

    /**
     * 公告：{"ok":true,"warm":bool,"cached":bool,"stale":bool,"ts":N,
     *        "items":[{"id","title","body","level","minVer","until"}]}。
     * 没缓存时立刻回 items:[] 并在后台起 AnnounceTask；本方法不阻塞。
     */
    @JavascriptInterface
    public String ann() {
        return AnnounceTask.ann(act.filesDirPub(), appVersion());
    }

    /** 契约里的别名（前端 ann / announcement 都认）。 */
    @JavascriptInterface
    public String announcement() {
        return AnnounceTask.ann(act.filesDirPub(), appVersion());
    }

    /** 强制刷新公告（用户手动点）：只起线程，立刻回 {"ok":true,"started":true}。 */
    @JavascriptInterface
    public String annRefreshStart() {
        return AnnounceTask.refreshStart(act.filesDirPub(), appVersion());
    }

    /** 契约里的别名（annRefreshStart / annRefresh 都认）。 */
    @JavascriptInterface
    public String annRefresh() {
        return AnnounceTask.refreshStart(act.filesDirPub(), appVersion());
    }
}
