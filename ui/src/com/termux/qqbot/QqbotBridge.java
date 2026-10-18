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
            o.put("guardRunning", QqbotGuardService.guardRunning);
            o.put("guardNote", QqbotGuardService.lastNote == null ? "" : QqbotGuardService.lastNote);
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
                {"70-launcher", "安装 qqbot-* 命令"}
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
            o.put("logTail", act.tail(act.deployLogFile(), 14));
            // 上一次部署留下的失败原因（common.sh 的 qqerr 会写这个文件）。
            // 首页把它直接显示出来，用户就不必自己去日志页翻几百行 apt 输出。
            File errf = new File(act.qqbotDir(), "deploy.error");
            String errTxt = errf.isFile() ? act.tail(errf, 6).trim() : "";
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
            // 从本段起点开始数，数到第几个阶段特征串出现过。
            // 「结束」那一档不算进度（它本身就是 100%），所以分母减一。
            int from = (lastStart >= 0) ? lastStart : 0;
            int reached = 0;
            for (int s = 0; s < stages.length; s++) {
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
            String cur = stages[Math.min(reached, stages.length - 1)][1];
            int total = stages.length - 1;         // 不含「完成」这一档
            int doneCount = Math.min(reached, total);
            if (!running && lastEnd >= 0) { doneCount = total; }
            int pct = total > 0 ? Math.round(doneCount * 100f / total) : 0;
            o.put("running", running);
            o.put("comp", comp);
            o.put("stage", doneCount);
            o.put("doneCount", doneCount);
            o.put("total", total);
            o.put("pct", pct);
            o.put("label", cur);
            // 每一档的展示文案 + 是否已过，界面直接渲染成小清单
            JSONArray list = new JSONArray();
            for (int s = 0; s < total; s++) {
                JSONObject e = new JSONObject();
                e.put("label", stages[s][1]);
                e.put("done", s < doneCount);
                list.put(e);
            }
            o.put("steps", list);
            o.put("logAge", (System.currentTimeMillis() - f.lastModified()) / 1000L);
            // 结束时间 / 退出码：读最后一行「重装 X 结束（时间，退出码 N）」
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
            act.runShell("mkdir -p " + qh + "/logs; nohup setsid bash " + prefix + "/opt/qqbot/start-sandbox.sh > "
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
                    + "nohup setsid bash " + prefix + "/opt/qqbot/start-sandbox.sh > " + qh
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
            String t = act.tail(act.deployLogFile(), 200);
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
                    + "nohup setsid bash " + prefix + "/opt/qqbot/start-sandbox.sh > " + qh
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
            return "bad guard name";
        }
        if ("battery".equals(what)) {
            return QqbotGuardService.requestIgnoreBattery(act);
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
}
