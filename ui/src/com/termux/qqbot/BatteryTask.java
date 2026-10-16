package com.termux.app;

/**
 * 在主线程打开「请求忽略电池优化」页面。
 * requestIgnoreBattery() 是从 WebView 的 JS 桥线程调进来的，startActivity 必须在主线程发，
 * 否则是未定义行为、异常还会被吞掉，界面只显示「这台设备打不开电池优化设置」。
 * 少数 ROM 没有申请页，这里退回电池优化总列表让用户手动找到本应用。
 * 注意：d8 处理匿名内部类会崩，所以必须是具名顶层类。
 */
public class BatteryTask implements Runnable {

    private final android.app.Activity act;
    private final String pkg;

    public BatteryTask(android.app.Activity act, String pkg) {
        this.act = act;
        this.pkg = pkg;
    }

    public void run() {
        try {
            android.content.Intent i = new android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(android.net.Uri.parse("package:" + pkg));
            act.startActivity(i);
            return;
        } catch (Throwable ignored) {
        }
        try {
            act.startActivity(new android.content.Intent(
                    android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            toast("请在本页把「AstrBot烛版」设为不优化");
        } catch (Throwable t) {
            toast("这台设备打不开电池优化设置，请到系统设置里手动允许后台运行");
        }
    }

    private void toast(String s) {
        try {
            android.widget.Toast.makeText(act, s, android.widget.Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }
}