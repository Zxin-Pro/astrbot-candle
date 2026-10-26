package com.termux.app;

import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;
import android.widget.Toast;

/**
 * 为什么需要它：下拉通知栏是最快的入口——用户只想「看一眼状态、顺手启停」，不该先打开应用。
 * 快捷磁贴（TileService，API 24+）就干两件事：显示守护是否在跑、点一下启停。
 * 状态源和小组件一致（QqbotGuardService.guardRunning + state/ui.json），
 * 但 onStartListening/onClick 都在主线程，所以读盘放 TileSync 的后台线程、启动沙盒放 SandboxStart，
 * 只在主线程里碰 Tile 对象本身。失败会回滚开关并 toast，绝不留下「开关说开着、沙盒没起来」。
 * d8 处理匿名内部类会崩，本文件不出现匿名类。
 */
public class QqbotTileService extends TileService {

    private static final String TAG = "qqbot-tile";

    public void onStartListening() {
        super.onStartListening();
        updateNow();
        TileSync.startRead(this);
    }

    public void onTileAdded() {
        super.onTileAdded();
        Log.i(TAG, "tile added");
        updateNow();
        TileSync.startRead(this);
    }

    public void onTileRemoved() {
        super.onTileRemoved();
        Log.i(TAG, "tile removed");
    }

    public void onClick() {
        /* v1.3.3-beta.6：按「需不需要二次确认」拆开，和小组件同一套口径。
           旧写法不管什么状态都只把面板叫到前台（toggle=1），在后台就是「点了没反应」：
           targetSdk 28 下服务自己 startActivity 会被 BAL 拦掉，而磁贴的
           startActivityAndCollapse 一旦赶上锁屏、桌面正在切换，也同样可能什么都不显示。
           现在：
             · 沙盒没在跑 → 直接在服务里启动（不需要界面，锁屏也能起）；
             · 沙盒在跑   → 停是危险操作，仍旧把面板叫到前台弹「紧急下线」确认。
           判据与 QqbotWidgetProvider 渲染用的是同一份：supervisor.pid 是不是活着的 proot。 */
        boolean running = false;
        try {
            running = QqbotShell.pidAlive(new java.io.File(
                    QqbotShell.qqbotDir(getFilesDir()), "supervisor.pid"), "proot");
        } catch (Throwable ignored) {
        }
        if (running) {
            openToggle();
        } else {
            QqbotGuardService.startSandboxInBackground(this);
        }
        QqbotWidgetProvider.refreshAsync(this);
    }

    /**
     * 拉起面板并带 toggle=1。三条路依次退：API 34+ 用 startActivityAndCollapse(PendingIntent)；
     * 老系统用同名 Intent 重载（API 24 起就有，34 起标记过时）；再不行退回服务那条老路。
     */
    private void openToggle() {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(QqbotGuardService.togglePi(this));
                return;
            }
        } catch (Throwable ignored) {
        }
        try {
            Intent i = new Intent(this, QqbotActivity.class);
            i.setAction(Intent.ACTION_VIEW);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            i.putExtra("toggle", true);
            startActivityAndCollapse(i);
            return;
        } catch (Throwable ignored) {
        }
        QqbotGuardService.askToggle(this);
    }

    /** 按内存快照刷磁贴（主线程）。 */
    void updateNow() {
        try {
            Tile t = getQsTile();
            if (t == null) return;
            int online = TileSync.online();
            int total = TileSync.total();
            // 与小组件同口径：以沙盒实例是否在线为准，而不是守护服务是否在跑
            boolean on = online > 0;
            t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            t.setLabel("烛之守护");
            String sub;
            if (!on) {
                sub = "已关闭";
            } else if (online >= 0 && total >= 0) {
                sub = "在线 " + online + "/" + total;
            } else {
                sub = "守护中";
            }
            // setSubtitle 是 API 29 才有的，低版本只保留 label + state，不要越界调用
            if (Build.VERSION.SDK_INT >= 29) t.setSubtitle(sub);
            t.setContentDescription(on ? ("烛之控制台：" + sub) : "烛之控制台：已关闭");
            t.updateTile();
        } catch (Throwable ignored) {
        }
    }

    private void toast(String s) {
        try {
            Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }
}
