package com.termux.app;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.File;

/**
 * 为什么需要它：磁贴的 onStartListening/onClick 都在主线程，而 ui.json 是磁盘文件，
 * 主线程读盘违反硬约束；但 Tile 对象又只能在主线程碰。所以拆成两趟：
 * 后台线程读盘把结果塞进静态内存快照，再 Handler.post 回主线程只做 updateTile()。
 * 快照是初值兜底：进程刚起来还没读盘时，磁贴先按「守护是否在跑」显示，不显示假数据。
 * d8 处理匿名内部类会崩，所以必须是具名顶层类。
 */
public class TileSync implements Runnable {

    private final QqbotTileService svc;
    private final boolean applyOnly;

    private static volatile int lastOnline = -1;
    private static volatile int lastTotal = -1;
    private static volatile boolean lastAlive = false;

    public TileSync(QqbotTileService s, boolean apply) {
        svc = s;
        applyOnly = apply;
    }

    static int online() {
        return lastOnline;
    }

    static int total() {
        return lastTotal;
    }

    static boolean alive() {
        return lastAlive;
    }

    static void setCache(boolean alive, int online, int total) {
        lastAlive = alive;
        lastOnline = online;
        lastTotal = total;
    }

    /** 后台线程读一份 ui.json（onStartListening 里调）。 */
    static void startRead(QqbotTileService s) {
        try {
            Thread th = new Thread(new TileSync(s, false), "qqbot-tile-read");
            th.setDaemon(true);
            th.start();
        } catch (Throwable ignored) {
        }
    }

    /** 状态变了（后台线程里改完 guard.off / 拉起沙盒后）回主线程刷磁贴。 */
    static void afterChange(QqbotTileService s) {
        try {
            Handler h = new Handler(Looper.getMainLooper());
            h.post(new TileSync(s, true));
        } catch (Throwable ignored) {
        }
    }

    public void run() {
        if (applyOnly) {
            // 这一段在主线程：只碰 Tile 对象，不做 IO
            svc.updateNow();
            return;
        }
        try {
            File filesDir = svc.getFilesDir();
            String s = StateIO.read(UiState.file(filesDir));
            JSONObject ui = null;
            if (s != null) {
                try {
                    ui = new JSONObject(s);
                } catch (Throwable ignored) {
                }
            }
            if (ui != null) {
                lastOnline = ui.optInt("online", -1);
                lastTotal = ui.optInt("total", -1);
                lastAlive = ui.optBoolean("serviceAlive", QqbotGuardService.guardRunning);
            } else {
                lastOnline = -1;
                lastTotal = -1;
                lastAlive = QqbotGuardService.guardRunning;
            }
        } catch (Throwable ignored) {
        }
        afterChange(svc);
    }
}
