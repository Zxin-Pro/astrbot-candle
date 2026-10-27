package com.termux.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * window.AndroidIsland —— 灵动岛的 JS 桥（v1.3.4-beta.3）。
 *
 * 归界面的部分只有三件：能不能挂、有没有权限、开关。真正画胶囊的是
 * QqbotIslandView，由 QqbotGuardService 在 onCreate / onDestroy 里挂上、摘掉——
 * 也就是说：胶囊的生命周期 = 守护服务的生命周期，这样才叫「一直保持前台运行」。
 *
 * 旧包兼容：界面必须 typeof window.AndroidIsland !== "undefined" 才调，
 * 本类所有方法自己再包一层 try/catch，任何一个失败都只 Toast，不抛给 JS。
 */
public class QqbotIslandBridge {

    private final Activity act;

    public QqbotIslandBridge(Activity act) {
        this.act = act;
    }

    /** 这台设备能不能挂悬浮胶囊（SDK>=26 且 WindowManager 可用）。不表示已授权、也不表示已开启。 */
    @JavascriptInterface
    public String available() {
        try {
            return QqbotIslandView.canHostNow(act) ? "true" : "false";
        } catch (Throwable t) {
            return "false";
        }
    }

    /** 有没有「显示在其他应用的上层」权限。Android 6 以下恒为 true。 */
    @JavascriptInterface
    public String granted() {
        try {
            return QqbotIslandView.granted(act) ? "true" : "false";
        } catch (Throwable t) {
            return "false";
        }
    }

    /** 把用户带到系统设置页去授权（这个权限没有运行时申请入口，只能跳设置）。 */
    @JavascriptInterface
    public void request() {
        try {
            act.runOnUiThread(new UiRequest());
        } catch (Throwable t) {
            // 连投递都失败说明 Activity 已经不在了，不再处理
        }
    }

    /**
     * 开关。on=true 时若还没授权，只记偏好、不挂窗口、并提示去授权——
     * 用户从设置页回来时界面重新读 granted() 就会看到 true，胶囊自然就出来了。
     */
    @JavascriptInterface
    public void set(String on) {
        try {
            Boolean v = parseOn(on);
            if (v == null) {
                toast("灵动岛开关参数不认识：" + on);
                return;
            }
            act.runOnUiThread(new UiSet(v.booleanValue()));
        } catch (Throwable t) {
            // 吞掉：桥方法抛异常会在 JS 侧变成一句看不懂的报错
        }
    }

    /** 供界面渲染用的快照：开关 / 授权 / 当前文案 / 电池白名单 / 能力。 */
    @JavascriptInterface
    public String info() {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("enabled", QqbotIslandView.isEnabled(act));
            o.put("granted", QqbotIslandView.granted(act));
            o.put("text", QqbotIslandView.currentLabel());
            o.put("code", QqbotIslandView.currentCode());
            o.put("battery", QqbotGuardService.batteryOk(act));
            o.put("available", QqbotIslandView.canHostNow(act));
            o.put("shown", QqbotIslandView.availableNow(act));
        } catch (Throwable t) {
            try {
                return "{\"ok\":false}";
            } catch (Throwable ignored) {
                return "{}";
            }
        }
        return o.toString();
    }

    /* ---------------- 内部 ---------------- */

    /**
     * 宽松解析开关值：true/1/on/yes/开 都算开，false/0/off/no/关 都算关。
     * 认不出来的返回 null——宁可提示一句，也别把「没听懂」当成「关掉」。
     */
    static Boolean parseOn(String s) {
        if (s == null) return null;
        String v = s.trim().toLowerCase();
        if (v.length() == 0) return null;
        if ("true".equals(v) || "1".equals(v) || "on".equals(v) || "yes".equals(v) || "开".equals(v)) {
            return Boolean.TRUE;
        }
        if ("false".equals(v) || "0".equals(v) || "off".equals(v) || "no".equals(v) || "关".equals(v)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private void toast(String msg) {
        try {
            act.runOnUiThread(new UiToast(act, msg));
        } catch (Throwable ignored) {
        }
    }

    private void openSettings() {
        String pkg = act.getPackageName();
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
        i.setData(Uri.parse("package:" + pkg));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            act.startActivity(i);
        } catch (ActivityNotFoundException e) {
            // 少数 ROM 没有带 package: 的版本，退回列表页让用户自己找
            try {
                act.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable t) {
                toast("这个系统没有悬浮窗设置页，请手动到设置里找「显示在其他应用的上层」");
            }
        } catch (Throwable t) {
            toast("打不开悬浮窗设置页：" + t);
        }
    }

    private void applyOn(boolean on) {
        try {
            QqbotIslandView.setEnabled(act, on);
            if (!on) {
                // 关掉立刻摘窗口：等服务下一轮 onCreate 再摘就太晚了，用户会觉得开关没生效
                QqbotIslandView.hide();
                toast("灵动岛已关闭");
                return;
            }
            if (!QqbotIslandView.granted(act)) {
                toast("还要授权「显示在其他应用的上层」才能显示灵动岛");
                openSettings();
                return;
            }
            if (!QqbotIslandView.canHostNow(act)) {
                toast("这台设备不支持悬浮窗（需要 Android 8.0 以上）");
                return;
            }
            // 胶囊的载体是守护服务：服务没在跑的话窗口没人维持，顺手把它拉起来。
            if (!QqbotGuardService.guardRunning) {
                try {
                    QqbotGuardService.start(act);
                } catch (Throwable ignored) {
                }
            }
            QqbotIslandView.sync(act);
            toast("灵动岛已开启");
        } catch (Throwable t) {
            toast("设置灵动岛失败：" + t);
        }
    }

    /* ---------------- 具名内部类（d8 不吃匿名类） ---------------- */

    private final class UiRequest implements Runnable {
        public void run() {
            openSettings();
        }
    }

    private final class UiSet implements Runnable {
        private final boolean on;

        UiSet(boolean on) {
            this.on = on;
        }

        public void run() {
            applyOn(on);
        }
    }

    private static final class UiToast implements Runnable {
        private final Context ctx;
        private final String msg;

        UiToast(Context c, String m) {
            ctx = c;
            msg = m;
        }

        public void run() {
            try {
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        }
    }
}
