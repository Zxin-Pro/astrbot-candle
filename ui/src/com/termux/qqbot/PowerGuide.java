package com.termux.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.File;

/**
 * 为什么需要它：国产 ROM 的「省电策略 / 自启动管理」会把常驻前台服务一起掐掉，
 * 用户看到的现象是「放了半天没动，QQ 掉线了」，但系统设置的入口每家都不一样、
 * 名字还天天改，让用户自己找几乎不可能。这里按厂商依次尝试已知的设置页，
 * 打不开就逐个往下退，最后退到应用详情页，保证「点了总有点反应」。
 * 同一版本只自动提示一次（power.json 记 ver），免得每次开应用都弹。
 * startActivity 必须在主线程，所以跳转由 UiTask.POWER 在 UI 线程执行。
 * d8 处理匿名内部类会崩，本文件不出现匿名类。
 */
public final class PowerGuide {

    private static final String NAME = "power.json";

    private PowerGuide() {}

    /** 厂商名原样给前端（Xiaomi / HUAWEI / OPPO…），判断一律用小写。 */
    static String manufacturer() {
        try {
            String m = Build.MANUFACTURER;
            return (m == null) ? "" : m;
        } catch (Throwable t) {
            return "";
        }
    }

    static String hintOf(String manu) {
        String m = (manu == null) ? "" : manu.toLowerCase(java.util.Locale.ROOT);
        if (m.indexOf("xiaomi") >= 0 || m.indexOf("redmi") >= 0 || m.indexOf("poco") >= 0) {
            return "MIUI 需要在「省电策略」里设为无限制，并允许自启动";
        }
        if (m.indexOf("huawei") >= 0) {
            return "EMUI 需要在「应用启动管理」里改为手动管理，并允许后台活动";
        }
        if (m.indexOf("honor") >= 0) {
            return "MagicOS 需要在「应用启动管理」里允许后台活动";
        }
        if (m.indexOf("oppo") >= 0 || m.indexOf("realme") >= 0) {
            return "ColorOS 需要在「耗电管理 / 应用速冻」里允许后台运行";
        }
        if (m.indexOf("vivo") >= 0 || m.indexOf("iqoo") >= 0) {
            return "OriginOS 需要在「后台高耗电」里允许后台运行";
        }
        if (m.indexOf("oneplus") >= 0) {
            return "OxygenOS 需要在「电池优化」里设为不优化";
        }
        if (m.indexOf("samsung") >= 0) {
            return "One UI 需要在「电池 → 未监视的应用」里设为不优化";
        }
        if (m.indexOf("meizu") >= 0) {
            return "Flyme 需要在「应用管理 → 权限 → 后台运行」里允许";
        }
        return "请在系统设置的「电池优化 / 自启动管理」里允许本应用后台运行";
    }

    /** 按厂商排好的跳转候选；最后一项永远是应用详情页，保证一定打得开。 */
    private static Intent[] candidates(Activity act) {
        String pkg = act.getPackageName();
        String m = manufacturer().toLowerCase(java.util.Locale.ROOT);
        java.util.ArrayList<Intent> list = new java.util.ArrayList<Intent>();
        if (m.indexOf("xiaomi") >= 0 || m.indexOf("redmi") >= 0 || m.indexOf("poco") >= 0) {
            Intent i = comp("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity");
            i.putExtra("package_name", pkg);
            i.putExtra("package_label", appLabel(act, pkg));
            list.add(i);
            list.add(comp("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"));
            list.add(comp("com.miui.securitycenter", "com.miui.powercenter.PowerSettings"));
        } else if (m.indexOf("huawei") >= 0) {
            list.add(comp("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"));
            list.add(comp("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"));
            list.add(comp("com.huawei.systemmanager", "com.huawei.systemmanager.power.ui.HwPowerManagerActivity"));
        } else if (m.indexOf("honor") >= 0) {
            list.add(comp("com.hihonor.systemmanager", "com.hihonor.systemmanager.optimize.process.ProtectActivity"));
            list.add(comp("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"));
            list.add(comp("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"));
        } else if (m.indexOf("oppo") >= 0 || m.indexOf("realme") >= 0) {
            list.add(comp("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"));
            list.add(comp("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"));
            list.add(comp("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"));
        } else if (m.indexOf("vivo") >= 0 || m.indexOf("iqoo") >= 0) {
            list.add(comp("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"));
            list.add(comp("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"));
            list.add(comp("com.vivo.abe", "com.vivo.abe.ui.appsales.AppSalesActivity"));
        } else if (m.indexOf("oneplus") >= 0) {
            list.add(comp("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"));
            list.add(comp("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"));
        } else if (m.indexOf("samsung") >= 0) {
            list.add(comp("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"));
            list.add(comp("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"));
            list.add(comp("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"));
        } else if (m.indexOf("meizu") >= 0) {
            list.add(comp("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"));
            list.add(comp("com.meizu.safe", "com.meizu.safe.permission.PermissionMainActivity"));
        }
        // 兜底：应用详情页。没有哪家 ROM 会缺这个页面。
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + pkg));
            list.add(i);
        } catch (Throwable ignored) {
        }
        Intent[] arr = new Intent[list.size()];
        return list.toArray(arr);
    }

    private static Intent comp(String pkg, String cls) {
        Intent i = new Intent();
        i.setComponent(new ComponentName(pkg, cls));
        // 从 Service / 桥线程起 Activity 时必须带 NEW_TASK，否则直接抛
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    private static String appLabel(Activity act, String pkg) {
        try {
            return String.valueOf(act.getPackageManager().getApplicationLabel(
                    act.getPackageManager().getApplicationInfo(pkg, 0)));
        } catch (Throwable t) {
            return "AstrBot烛版";
        }
    }

    /**
     * 在 UI 线程（UiTask.POWER）执行：逐个候选试 startActivity，第一个不抛的就当成功。
     * 返回空串 = 已跳转；否则返回给用户看的中文原因。
     */
    public static String jumpNow(Activity act) {
        if (act == null) return "界面已关闭";
        Intent[] list = candidates(act);
        for (int i = 0; i < list.length; i++) {
            try {
                act.startActivity(list[i]);
                return "";
            } catch (Throwable ignored) {
                // ActivityNotFound / SecurityException / ROM 自己抛的空指针，一律试下一个
            }
        }
        return "这台设备打不开省电设置，请到系统设置里手动允许后台运行";
    }

    /* ---------- power.json：同一版本只自动提示一次 ---------- */

    private static File file(File filesDir) {
        return StateIO.file(filesDir, NAME);
    }

    /** 记下「这个版本已经提示（或用户已经点过）」；ver 变了就重新允许提示。 */
    static void markShown(File filesDir, String ver) {
        try {
            JSONObject o = new JSONObject();
            o.put("ver", ver == null ? "" : ver);
            o.put("manufacturer", manufacturer());
            o.put("shown", StateIO.now());
            // 协议里这个字段叫 powerHinted（同一版本是否已提示过），两个都写，读方各取所需
            o.put("powerHinted", true);
            StateIO.write(file(filesDir), o.toString());
        } catch (Throwable ignored) {
        }
    }

    private static boolean shownFor(File filesDir, String ver) {
        String s = StateIO.read(file(filesDir));
        if (s == null) return false;
        try {
            JSONObject o = new JSONObject(s);
            String v = o.optString("ver", "");
            return v.length() > 0 && v.equals(ver == null ? "" : ver);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 桥方法 powerState()：前端据此决定是否自动弹「把省电策略设成无限制」。 */
    public static String powerState(File filesDir, String ver) {
        try {
            JSONObject o = new JSONObject();
            String manu = manufacturer();
            o.put("ok", true);
            o.put("manufacturer", manu);
            o.put("hint", hintOf(manu));
            o.put("shown", shownFor(filesDir, ver));
            o.put("ver", ver == null ? "" : ver);
            return o.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"err\":\"读取省电引导状态失败\"}";
        }
    }

    /** 桥方法 powerAck()：前端弹过提示（用户关掉了横幅）也要记一笔，避免反复打扰。 */
    public static String ack(File filesDir, String ver) {
        markShown(filesDir, ver);
        try {
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("shown", true);
            return o.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"err\":\"记录提示状态失败\"}";
        }
    }

    /**
     * 桥方法 powerGuide()：用户在提示条上点了「去设置」。
     * 跳转必须回主线程，走 runOnUiSync 拿到真实结果（startActivity 本身是毫秒级）。
     */
    public static String powerGuide(QqbotActivity act, String ver) {
        try {
            String manu = manufacturer();
            String r = act.runOnUiSync(new UiTask(act, UiTask.POWER, manu));
            if (r == null) {
                // 3 秒内没跑完：多半是 Activity 正在销毁，别误报成功
                JSONObject o = new JSONObject();
                o.put("ok", false);
                o.put("err", "界面暂时无法跳转，请稍后重试");
                return o.toString();
            }
            markShown(act.filesDirPub(), ver);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("jumped", r.length() == 0);
            o.put("manufacturer", manu);
            if (r.length() > 0) o.put("err", r);
            return o.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"err\":\"跳转省电设置失败\"}";
        }
    }
}
