package com.termux.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/**
 * 为什么需要它：控制台的「实例卡片」（功能 17）要显示每个实例的启动时间、最近掉线时间和 QQ 号，
 * 这三个值只有原生层算得准：启动时间要读本机 pid 文件的 mtime，掉线要靠「上一轮还活着、这一轮没了」
 * 的翻转判断，QQ 号只在 bots.json 的 qq 字段里。沙盒层看不到宿主的 pid 也不会跨轮记状态，
 * 所以由守护循环每 60 秒调一次 update()，把结论写进 state/instances.json 供界面与小组件只读。
 * 只有守护线程写，读方永远不会看到半个 JSON（StateIO 是原子写）。
 * d8 处理匿名内部类会崩，本文件不出现匿名类。
 */
public final class InstancesState {

    private static final String NAME = "instances.json";

    private InstancesState() {}

    /**
     * 守护循环每轮调用。botsRoot 是 bots.json 的根对象（可能为 null），
     * alive 是「实例 id -> 本轮端口是否可连」的快照（来自 QqbotGuardTask 的探测结果，避免重复探端口）。
     */
    static synchronized void update(File filesDir, JSONObject botsRoot, JSONObject alive) {
        try {
            JSONObject cur = load(filesDir);
            JSONObject out = new JSONObject();
            JSONArray arr = (botsRoot == null) ? null : botsRoot.optJSONArray("bots");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject b = arr.optJSONObject(i);
                    if (b == null) continue;
                    String id = b.optString("id", "");
                    if (id.length() == 0) continue;
                    boolean on = alive != null && alive.optBoolean(id, false);
                    JSONObject prev = cur.optJSONObject(id);
                    long started = prev == null ? 0L : prev.optLong("started", 0L);
                    long lastOff = prev == null ? 0L : prev.optLong("lastOffline", 0L);
                    boolean was = prev != null && prev.optBoolean("alive", false);
                    String uin = prev == null ? "" : prev.optString("uin", "");
                    String nick = prev == null ? "" : prev.optString("nick", "");
                    String qq = b.optString("qq", "");
                    if (qq.length() > 0) uin = qq;

                    if (on) {
                        // 只在「第一次看到它活着」或「刚掉线又回来」时重算启动时间：
                        // pid 文件（cache/run/<id>.pid）的 mtime 就是进程创建时刻，比每轮刷新更准。
                        if (!was || started <= 0L) started = pidStart(filesDir, id);
                    } else if (was) {
                        lastOff = StateIO.now(); // 上一轮还活着 -> 这一轮没了：这才是「掉线」
                    }

                    JSONObject o = new JSONObject();
                    o.put("started", started);
                    o.put("lastOffline", lastOff);
                    o.put("uin", uin);
                    o.put("nick", nick);
                    o.put("alive", on); // 多存一个内部字段：下一轮判断「原本活着」要用
                    out.put(id, o);
                }
                /* 写入条件必须放在 arr != null 里面：botsRoot 读失败时 out 也是空的，
                   在外面无条件写会把「读不到」当成「没有实例」，把有用的状态抹掉。 */
                if (!out.toString().equals(cur.toString())) {
                    StateIO.write(StateIO.file(filesDir, NAME), out.toString());
                }
            }
            /* v1.1.35：以前这里是 out.length() > 0 && ...
               删掉最后一个实例时 out 是空的，于是永远不写盘，instances.json 里那条旧记录
               一直留着 —— 界面上就出现一张「删掉了还在」的幽灵实例卡。
               现在只要 bots.json 读到了就照实写（包括空表），读失败（arr == null）才跳过。 */
        } catch (Throwable ignored) {
        }
    }

    /** 桥方法 instancesInfo()：原样带回 instances.json，读不到就给空表（前端照常渲染空卡片）。 */
    static String info(File filesDir) {
        String s = StateIO.read(StateIO.file(filesDir, NAME));
        if (s == null) return "{\"ok\":true,\"instances\":{}}";
        try {
            JSONObject o = new JSONObject(s);
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("instances", o);
            return out.toString();
        } catch (Throwable t) {
            return "{\"ok\":true,\"instances\":{}}";
        }
    }

    private static JSONObject load(File filesDir) {
        String s = StateIO.read(StateIO.file(filesDir, NAME));
        if (s != null) {
            try {
                return new JSONObject(s);
            } catch (Throwable ignored) {
            }
        }
        return new JSONObject();
    }

    /** 进程启动时刻（epoch 秒）：优先用本实例的 pid 文件 mtime，退化到 supervisor.pid，再退化到当前时间。 */
    private static long pidStart(File filesDir, String id) {
        try {
            File run = new File(QqbotShell.qqbotDir(filesDir), "cache/run");
            File f = new File(run, id + ".pid");
            long m = f.lastModified();
            if (m <= 0) m = new File(QqbotShell.qqbotDir(filesDir), "supervisor.pid").lastModified();
            if (m > 0) return m / 1000L;
        } catch (Throwable ignored) {
        }
        return StateIO.now();
    }
}
