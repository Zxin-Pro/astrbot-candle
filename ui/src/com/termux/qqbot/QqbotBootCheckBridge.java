package com.termux.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * 「自启动体检」桥（JS 名 AndroidBoot，更多页 #bootCard 用）。
 *
 * 手机重启后机器人没自己起来，九成是这三件事里的一件：
 *   1) 系统把「自启动」关了（各家 ROM 自己的一套名单，第三方 App 读不到，只能引导用户去看）；
 *   2) 电池优化没加白名单，息屏一段时间后台被冻结；
 *   3) 开机广播被用户在应用详情里手动关掉了（COMPONENT_ENABLED_STATE_DISABLED）。
 *
 * 前两条系统不给读，所以状态如实写 unknown/warn，只给「点这里去开」的引导；
 * 第三条能真读，读的是 QqbotBootReceiver 这个组件的启用状态。
 *
 * 本类不申请任何新权限，全部走「打开系统设置页让用户自己点」。
 * d8 不接受 lambda，本文件不出现匿名内部类。
 */
public class QqbotBootCheckBridge {

    private final QqbotActivity act;
    private final Context ctx;

    /** 各家 ROM 的自启动页：包名, Activity。按顺序试，第一个能解析的就用。 */
    private static final String[][] AUTOSTART_PAGES = {
            // 小米 / 红米 / POCO
            { "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity" },
            // OPPO / 一加 / realme（新版）
            { "com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity" },
            { "com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity" },
            { "com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity" },
            { "com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity" },
            // vivo / iQOO
            { "com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity" },
            { "com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity" },
            { "com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager" },
            // 华为 / 荣耀
            { "com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity" },
            { "com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity" },
            { "com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity" },
            // 三星
            { "com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity" },
            { "com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity" },
            // 魅族
            { "com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity" },
            { "com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC" },
            // 联想 / 中兴等杂牌：安全中心
            { "com.lenovo.security", "com.lenovo.security.purebackground.PureBackgroundActivity" },
            { "com.zte.heartyservice", "com.zte.heartyservice.setting.ClearAppSettingsActivity" }
    };

    public QqbotBootCheckBridge(QqbotActivity act) {
        this.act = act;
        Context c = act;
        try {
            Context app = act.getApplicationContext();
            if (app != null) c = app;
        } catch (Throwable ignored) {
        }
        this.ctx = c;
    }

    /* ======================= 对外接口 ======================= */

    /**
     * 三项体检：
     *   autostart 自启动（读不到，永远 unknown，只给对应 ROM 的操作路径）
     *   battery   电池优化白名单（能读，ok/warn）
     *   boot      开机广播组件（能读，ok/warn）
     */
    @JavascriptInterface
    public String status() {
        JSONObject out = new JSONObject();
        try {
            String rom = romName();
            JSONArray items = new JSONArray();
            items.put(item("autostart", "自启动", "unknown", autostartHint(rom)));
            items.put(batteryItem());
            items.put(bootItem());
            out.put("ok", true);
            out.put("rom", rom);
            out.put("items", items);
            return out.toString();
        } catch (Throwable t) {
            return fail("读取自启动状态失败：" + brief(t));
        }
    }

    /** 打开对应的系统设置页（autostart 走 ROM 兜底链，全都打不开就退到应用详情页）。 */
    @JavascriptInterface
    public String open(String id) {
        JSONObject out = new JSONObject();
        try {
            String key = (id == null) ? "" : id.trim();
            if (key.length() == 0) return fail("不知道要打开哪一项设置");
            Intent it = null;
            if ("autostart".equals(key)) {
                it = autostartIntent();
                if (it == null) {
                    it = appDetailIntent();
                    if (!launch(it)) return fail("这台手机没开放自启动设置页，连应用详情也打不开：请到手机设置里手动找「自启动」");
                    out.put("ok", true);
                    out.put("msg", "这个系统没有标准自启动页，已打开应用详情；请回设置里手动允许自启动");
                    return out.toString();
                }
            } else if ("battery".equals(key)) {
                it = batteryIntent();
            } else if ("boot".equals(key)) {
                it = appDetailIntent();
            } else {
                return fail("不认识这一项设置：" + key);
            }
            if (!launch(it)) return fail("打不开系统设置页：可能在后台被拦了，回桌面后重试一次");
            out.put("ok", true);
            out.put("msg", "已打开系统设置");
            return out.toString();
        } catch (Throwable t) {
            return fail("打开系统设置失败：" + brief(t));
        }
    }

    /* ======================= 三项体检 ======================= */

    private JSONObject batteryItem() throws Exception {
        boolean ignoring = false;
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null && Build.VERSION.SDK_INT >= 23) {
                ignoring = pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
            }
        } catch (Throwable ignored) {
        }
        String hint = ignoring
                ? "已经在电池优化白名单里，息屏久了也不会被系统冻住"
                : "还没加白名单：息屏一会儿系统就可能把后台冻死，机器人会掉线，点「打开设置」允许一下";
        return item("battery", "电池优化", ignoring ? "ok" : "warn", hint);
    }

    private JSONObject bootItem() throws Exception {
        boolean enabled = true;
        boolean known = false;
        try {
            PackageManager pm = ctx.getPackageManager();
            if (pm != null) {
                int st = pm.getComponentEnabledSetting(new ComponentName(ctx, QqbotBootReceiver.class));
                known = true;
                enabled = (st == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                        || st == PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
            }
        } catch (Throwable ignored) {
        }
        String hint;
        String state;
        if (!known) {
            state = "unknown";
            hint = "读不到开机广播的状态，去应用详情页看一眼「自启动/开机启动」有没有被关";
        } else if (enabled) {
            state = "ok";
            hint = "开机广播是开着的：重启手机后会自动把机器人拉起来（有些系统还得在自启动名单里再允许一次）";
        } else {
            state = "warn";
            hint = "开机广播被关掉了：重启手机不会自己起来，点「打开设置」，在应用详情底部点「启用」";
        }
        return item("boot", "开机唤醒", state, hint);
    }

    private String autostartHint(String rom) {
        if ("MIUI".equals(rom)) return "小米/红米：手机管家 → 应用管理 → 权限 → 自启动，把「烛之控制台」打开";
        if ("ColorOS".equals(rom)) return "OPPO/一加/realme：设置 → 应用 → 自启动管理，允许「烛之控制台」自启动";
        if ("OriginOS".equals(rom)) return "vivo/iQOO：i 管家 → 应用管理 → 权限管理 → 自启动，允许「烛之控制台」";
        if ("EMUI".equals(rom)) return "华为：手机管家 → 应用启动管理 → 找到「烛之控制台」，改成手动管理并把三项都打开";
        if ("MagicOS".equals(rom)) return "荣耀：手机管家 → 应用启动管理，允许「烛之控制台」自启动";
        if ("One UI".equals(rom)) return "三星：设置 → 电池 → 后台使用限制，把「烛之控制台」移出「休眠应用」";
        if ("Flyme".equals(rom)) return "魅族：手机管家 → 权限管理 → 自启动，允许「烛之控制台」";
        return "各家系统都不给第三方读自启动状态：点「打开设置」看一眼有没有允许「烛之控制台」自启动";
    }

    /* ======================= 打开设置页 ======================= */

    private Intent autostartIntent() {
        try {
            PackageManager pm = ctx.getPackageManager();
            if (pm == null) return null;
            for (int i = 0; i < AUTOSTART_PAGES.length; i++) {
                String[] one = AUTOSTART_PAGES[i];
                Intent probe = new Intent();
                probe.setComponent(new ComponentName(one[0], one[1]));
                if (pm.resolveActivity(probe, 0) != null) return probe;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Intent batteryIntent() {
        try {
            Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            PackageManager pm = ctx.getPackageManager();
            if (pm != null && pm.resolveActivity(i, 0) != null) return i;
        } catch (Throwable ignored) {
        }
        return appDetailIntent();
    }

    private Intent appDetailIntent() {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        i.setData(Uri.parse("package:" + ctx.getPackageName()));
        return i;
    }

    /** 统一在 UI 线程起 Activity（要 NEW_TASK，本类没有 Activity 栈）。 */
    private boolean launch(Intent it) {
        if (it == null) return false;
        try {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (act != null) {
                act.runOnUiThread(new Launch(act, it));
                return true;
            }
            ctx.startActivity(it);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static final class Launch implements Runnable {
        private final Context c;
        private final Intent it;

        Launch(Context c, Intent it) {
            this.c = c;
            this.it = it;
        }

        public void run() {
            try {
                c.startActivity(it);
            } catch (Throwable ignored) {
            }
        }
    }

    /* ======================= 小工具 ======================= */

    private JSONObject item(String id, String name, String state, String hint) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("state", state);
        o.put("hint", hint);
        return o;
    }

    /** 认一下这是哪家的系统（只做展示，选设置页时靠 AUTOSTART_PAGES 逐个试）。 */
    private String romName() {
        try {
            String m = low(Build.MANUFACTURER);
            String b = low(Build.BRAND);
            if (has(m, "xiaomi") || has(b, "xiaomi") || has(m, "redmi") || has(b, "redmi") || has(m, "poco")) return "MIUI";
            if (has(m, "oppo") || has(b, "oppo") || has(m, "oneplus") || has(b, "oneplus") || has(m, "realme") || has(b, "realme")) return "ColorOS";
            if (has(m, "vivo") || has(b, "vivo") || has(m, "iqoo") || has(b, "iqoo")) return "OriginOS";
            if (has(m, "huawei") || has(b, "huawei")) return "EMUI";
            if (has(m, "honor") || has(b, "honor")) return "MagicOS";
            if (has(m, "samsung") || has(b, "samsung")) return "One UI";
            if (has(m, "meizu") || has(b, "meizu")) return "Flyme";
            String raw = Build.MANUFACTURER;
            if (raw != null && raw.trim().length() > 0) return raw.trim();
        } catch (Throwable ignored) {
        }
        return "安卓";
    }

    private static String low(String s) {
        return (s == null) ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static boolean has(String s, String key) {
        return s != null && s.indexOf(key) >= 0;
    }

    private static String fail(String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("err", msg);
        } catch (Throwable ignored) {
        }
        return o.toString();
    }

    private static String brief(Throwable t) {
        String s = (t == null) ? "" : String.valueOf(t);
        if (s.length() > 160) s = s.substring(0, 160) + "…";
        return s;
    }
}
