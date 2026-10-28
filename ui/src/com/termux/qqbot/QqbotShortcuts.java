package com.termux.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.res.Resources;
import android.graphics.drawable.Icon;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

/**
 * 桌面图标长按菜单（安卓「动态快捷方式」）。
 *
 * 为什么用动态快捷方式而不是静态 shortcuts.xml：静态的必须在清单里登记一份 xml 资源，
 * 而且内容写死；本应用的入口状态每天都在变（运行中 / 未部署 / 没登录），动态的可以在
 * 每次启动时按当前情况重新下发，也不需要用户确认。
 *
 * 三条硬约束（都是实测结论，别当 bug 改）：
 *   1) 快捷方式的 Intent 由桌面用 startActivity 发出，指向 Service 会直接
 *      ActivityNotFoundException。所以五个入口全部落 QqbotActivity，靠 extra 让它自己转
 *      （toggle / confirm_stop / tab 都是 QqbotActivity 现在就已经支持的协议）。
 *   2) 图标不能用 R.xxx：本工程没有生成 R.java（资源在 ui/assets/android-res 里，
 *      构建时才拼进 APK）。写 R 直接编译不过，只能运行时按名字 getIdentifier 找，
 *      找不到再退系统内置图标。
 *   3) setDynamicShortcuts() 有数量上限（getMaxShortcutCountPerActivity），超了会抛
 *      IllegalArgumentException，所以下发前先按上限裁剪。
 *
 * 本文件不出现匿名类（d8 处理匿名内部类会崩），也不用简写语法。
 */
public final class QqbotShortcuts {

    /** 五个入口的 id。改 id 等于让用户桌面上已拖出来的旧快捷方式失效，别乱改。 */
    static final String ACT_START = "qqbot-act-start";
    static final String ACT_LOGS = "qqbot-act-logs";
    static final String ACT_QR = "qqbot-act-qr";
    static final String ACT_STATUS = "qqbot-act-status";
    static final String ACT_STOP = "qqbot-act-stop";

    /** 系统最低给 4 条（部分厂商 ROM），先按 5 条构造，超限时从尾部裁。 */
    static final int FALLBACK_MAX = 5;

    private QqbotShortcuts() {
    }

    /* ---------- 对外唯一入口 ---------- */

    /**
     * 在 QqbotActivity.onCreate 里调一次（幂等，重复调用只是重新下发同一批 id）。
     *
     * 整段包在 try/catch(Throwable) 里：快捷方式属于「有更好、没有也能用」的装饰，
     * 系统限流、launcher 阉割、厂商 ROM 不支持……任何失败都不许影响应用启动。
     */
    public static void install(Activity act) {
        try {
            if (act == null) return;
            // ShortcutManager 是 API 25 起的，本应用 minSdk 24，24 上直接放弃。
            if (Build.VERSION.SDK_INT < 25) return;
            ShortcutManager sm = manager(act);
            if (sm == null) return;
            List<ShortcutInfo> list = build(act);
            if (list == null || list.isEmpty()) return;
            int max = maxCount(sm);
            while (list.size() > max && list.size() > 1) {
                list.remove(list.size() - 1);
            }
            sm.setDynamicShortcuts(list);
        } catch (Throwable ignored) {
            // 静默：桌面长按菜单少几项，用户不会因此用不了机器人。
        }
    }

    /* ---------- 构造 ---------- */

    private static List<ShortcutInfo> build(Activity act) {
        List<ShortcutInfo> out = new ArrayList<ShortcutInfo>();
        Icon ic = appIcon(act);
        add(out, act, ACT_START, "启停", "启动 / 停止机器人", startIntent(act), ic);
        add(out, act, ACT_LOGS, "看日志", "打开日志页", tabIntent(act, "logs"), ic);
        add(out, act, ACT_QR, "扫码", "扫码登录 QQ", tabIntent(act, "qr"), ic);
        add(out, act, ACT_STATUS, "状态", "运行状态与性能", tabIntent(act, "more"), ic);
        add(out, act, ACT_STOP, "停止", "紧急下线（需确认）", stopIntent(act), ic);
        return out;
    }

    private static void add(List<ShortcutInfo> out, Activity act, String id,
                            String label, String longLabel, Intent i, Icon ic) {
        try {
            ShortcutInfo.Builder b = new ShortcutInfo.Builder(act, id);
            b.setShortLabel(label);
            // 长按菜单第一行显示 shortLabel，展开后显示 longLabel；两个都非空最保险。
            b.setLongLabel(longLabel);
            b.setIntent(i);
            if (ic != null) b.setIcon(ic);
            ShortcutInfo s = b.build();
            if (s != null) out.add(s);
        } catch (Throwable ignored) {
            // 单条构造失败（比如 label 被系统判非法）不能拖垮其它四条
        }
    }

    /** 主页 + toggle：QqbotActivity 已有的「启停切换」协议（小组件 / 磁贴同款）。 */
    private static Intent startIntent(Activity act) {
        Intent i = base(act);
        i.putExtra("toggle", true);
        return i;
    }

    /** 指定标签页：tab 走 QqbotActivity.tabOfIntent 的白名单过滤，非法值只会落到首页。 */
    private static Intent tabIntent(Activity act, String tab) {
        Intent i = base(act);
        i.putExtra("tab", tab);
        return i;
    }

    /** 紧急下线走「确认弹窗」而不是直接杀服务：误触的代价太大（见 confirm_stop 协议）。 */
    private static Intent stopIntent(Activity act) {
        Intent i = base(act);
        i.putExtra("confirm_stop", true);
        return i;
    }

    private static Intent base(Activity act) {
        Intent i = new Intent(act, QqbotActivity.class);
        i.setAction(Intent.ACTION_VIEW);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra("tab", "home");
        return i;
    }

    /* ---------- 系统侧 ---------- */

    static ShortcutManager manager(Activity act) {
        try {
            Object o = act.getSystemService(Activity.SHORTCUT_SERVICE);
            if (o instanceof ShortcutManager) return (ShortcutManager) o;
        } catch (Throwable ignored) {
        }
        return null;
    }

    static int maxCount(ShortcutManager sm) {
        try {
            int n = sm.getMaxShortcutCountPerActivity();
            if (n > 0) return n;
        } catch (Throwable ignored) {
        }
        return FALLBACK_MAX;
    }

    /**
     * 应用图标：优先 mipmap/ic_launcher（清单里 android:icon 用的就是它），
     * 退不到就找通知图标，最后退系统图标 —— 三档都失败就返回 null，让系统用默认图。
     */
    static Icon appIcon(Activity act) {
        try {
            String pkg = act.getPackageName();
            Resources r = act.getResources();
            int id = r.getIdentifier("ic_launcher", "mipmap", pkg);
            if (id <= 0) id = r.getIdentifier("ic_launcher", "drawable", pkg);
            if (id <= 0) id = r.getIdentifier("ic_stat_qqbot", "drawable", pkg);
            if (id > 0) return Icon.createWithResource(act, id);
        } catch (Throwable ignored) {
        }
        try {
            return Icon.createWithResource(act, android.R.drawable.stat_notify_error);
        } catch (Throwable ignored) {
        }
        return null;
    }
}
