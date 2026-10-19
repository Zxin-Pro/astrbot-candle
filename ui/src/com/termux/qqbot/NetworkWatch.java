package com.termux.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import org.json.JSONObject;

import java.io.File;

/**
 * 为什么需要它：手机切 WiFi/移动数据、进出电梯、锁屏省电断网之后，前台服务还活着、
 * 沙盒进程也还在，但 QQ 长连接早已断开，NapCat 不会自己重连、守护循环只查 pid 完全看不出来。
 * 这里监听系统默认网络的变化，只在「无网→有网」或默认网络切换时触发一次恢复
 * （30 秒 debounce，防止信号抖动把沙盒反复拉起来），其余判断全部交给守护流程。
 * 用 registerDefaultNetworkCallback（API 24+，minSdk 就是 24），不需要新权限。
 * d8 处理匿名内部类会崩，所以本类自己就是 NetworkCallback，不出现匿名类。
 */
public class NetworkWatch extends ConnectivityManager.NetworkCallback {

    /** 同一轮网络抖动只恢复一次。 */
    private static final long DEBOUNCE_MS = 30000L;

    private static final String NAME = "net.json";

    /* ---- 供 netInfo() 读的静态快照（桥线程只读这些，不碰系统服务，保证 <50ms） ---- */
    private static volatile boolean online = false;
    private static volatile String type = "none";
    private static volatile long lastSwitch = 0L;   // epoch 秒
    private static volatile int recoveries = 0;

    /** 上一次真正触发恢复的时刻（毫秒），debounce 用。 */
    private static volatile long lastTriggerMs = 0L;
    private static volatile boolean running = false;
    private static NetworkWatch inst;

    /**
     * 当前默认网络是否可用。守护线程判定「现在能不能试一次上报」用它：
     * 这是 registerDefaultNetworkCallback 维护的实时快照，不额外探测、不解析 DNS。
     * 没启动过 NetworkWatch（守护服务没跑）时保守返回 false——这一轮就别发。
     */
    static boolean isOnline() {
        return online;
    }

    private final QqbotGuardService svc;

    private NetworkWatch(QqbotGuardService s) {
        svc = s;
    }

    /* ---------- 生命周期：由 QqbotGuardService 的 onCreate/onDestroy 驱动 ---------- */

    public static synchronized void start(QqbotGuardService s) {
        if (running || s == null) return;
        try {
            loadSnapshot(s.getFilesDir());
            ConnectivityManager cm = (ConnectivityManager) s.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            inst = new NetworkWatch(s);
            cm.registerDefaultNetworkCallback(inst);
            running = true;
            // 注册的瞬间系统会立刻补一次 onAvailable（当前本来就有默认网络），那并不是
            // 「无网→有网」，不该触发恢复（开机时守护循环 3 秒后自己会拉沙盒）。
            // 按「刚刚触发过」记一笔，等于把注册后的头 30 秒也纳入 debounce。
            lastTriggerMs = System.currentTimeMillis();
            // 注册回调只会在「变化」时回来，所以先自己取一次当前状态，否则刚开机时
            // netInfo() 会一直报 online=false / type=none。
            try {
                Network n = cm.getActiveNetwork();
                NetworkCapabilities caps = (n == null) ? null : cm.getNetworkCapabilities(n);
                if (caps != null) {
                    online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                    type = typeOf(caps);
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            running = false;
            inst = null;
        }
    }

    public static synchronized void stop() {
        NetworkWatch w = inst;
        inst = null;
        running = false;
        if (w == null) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) w.svc.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) cm.unregisterNetworkCallback(w);
        } catch (Throwable ignored) {
        }
    }

    public static boolean running() { return running; }

    /* ---------- NetworkCallback：回调运行在系统 binder 线程，绝不做重活 ---------- */

    @Override
    public void onAvailable(Network network) {
        online = true;
        lastSwitch = StateIO.now();
        // 默认网络「重新出现」= 无网→有网，或者 WiFi↔移动数据切换。两者都值得自愈一次。
        maybeRecover();
    }

    @Override
    public void onLost(Network network) {
        online = false;
        // 没网了就不要还报 wifi/mobile，否则界面会显示「WiFi 正常」而其实整机断网
        type = "none";
        lastSwitch = StateIO.now();
    }

    @Override
    public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
        if (caps == null) return;
        boolean net = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        String t = typeOf(caps);
        boolean changed = (net != online) || !(t.equals(type));
        online = net;
        if (!t.equals(type)) type = t;
        // 有网但是网络类型变了（WiFi→4G）时系统通常先 onLost 再 onAvailable；
        // 少数 ROM 只回调这里，所以补一次「刚连上」的恢复判断，同样受 debounce 约束。
        if (net && changed) maybeRecover();
    }

    /** 30 秒 debounce：抖动期间只恢复一次；恢复动作整体丢到后台线程。 */
    private void maybeRecover() {
        long now = System.currentTimeMillis();
        long since = now - lastTriggerMs;
        if (lastTriggerMs > 0L && since < DEBOUNCE_MS) return;
        lastTriggerMs = now;
        recoveries++;
        try {
            Thread th = new Thread(new NetRecover(svc, svc.getFilesDir(), recoveries));
            th.setDaemon(true);
            th.start();
        } catch (Throwable ignored) {
        }
    }

    /* ---------- 持久化 + 查询 ---------- */

    private static File file(File filesDir) {
        return StateIO.file(filesDir, NAME);
    }

    private static void loadSnapshot(File filesDir) {
        String s = StateIO.read(file(filesDir));
        if (s == null) return;
        try {
            JSONObject o = new JSONObject(s);
            recoveries = o.optInt("recoveries", 0);
            long ls = o.optLong("lastSwitch", 0L);
            if (ls > 0L && ls > lastSwitch) lastSwitch = ls;
        } catch (Throwable ignored) {
        }
    }

    /** 由 NetRecover 在后台线程调用，避免在系统回调线程里做磁盘 IO。 */
    static void persist(File filesDir) {
        try {
            JSONObject o = new JSONObject();
            o.put("lastSwitch", lastSwitch);
            o.put("recoveries", recoveries);
            StateIO.write(file(filesDir), o.toString());
        } catch (Throwable ignored) {
        }
    }

    static String typeOf(NetworkCapabilities caps) {
        if (caps == null) return "none";
        try {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "mobile";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "wifi";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "wifi";
        } catch (Throwable ignored) {
        }
        return "none";
    }

    /** 桥方法 netInfo() 的唯一数据源（纯内存，毫秒级）。 */
    static String infoJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("online", online);
            o.put("type", type);
            o.put("lastSwitch", lastSwitch);
            o.put("recoveries", recoveries);
        } catch (Throwable t) {
            return "{\"ok\":false,\"err\":\"读取网络状态失败\"}";
        }
        return o.toString();
    }
}
