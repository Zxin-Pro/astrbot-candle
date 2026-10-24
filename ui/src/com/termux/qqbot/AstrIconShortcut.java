package com.termux.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.util.Base64;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 为什么需要它：安卓不允许应用在运行时改自己的图标（防冒充 / 防钓鱼），
 * 唯一合法的路子是应用自己在桌面上钉一个「快捷方式」—— 快捷方式的图标可以是我们
 * 现生成的 Bitmap，而且 updateShortcuts() 随时能换掉它。
 * 所以「把 QQ 头像变成桌面图标」= 钉一个指向 QqbotActivity 的 pinned shortcut，
 * 登录换号之后静默更新图标，用户桌面上那个图标就跟着换脸。
 *
 * 三个现实限制（都是实测，别当成 bug）：
 *   1) 桌面（launcher）不一定支持 requestPinShortcut；不支持时 isRequestPinShortcutSupported()
 *      就是 false，这时没有任何办法偷偷钉图标，只能如实回错。
 *   2) 钉图标一定会弹一次「是否添加到桌面」的系统确认，应用没法跳过 —— 用户点取消，
 *      系统什么都不会留（requestPinShortcut 本身照样返回 true）。
 *   3) getPinnedShortcuts() 里的记录在用户从桌面删掉之后还会残留一条 isEnabled()=false 的
 *      僵尸条目，所以更新前要先 enableShortcuts()，否则 updateShortcuts() 会白跑。
 *
 * d8 处理匿名内部类会崩，本文件不出现匿名类 / lambda。
 */
public final class AstrIconShortcut {

    /** 固定 id：重复钉就用同一条，桌面上不会越钉越多。 */
    static final String ID = "qqbot-avatar";
    /** 快捷方式底图边长。adaptive icon 的画布是 108dp，4x 屏正好 432px；
     *  系统对太小的 bitmap 会直接放弃更新图标，给足尺寸最保险。 */
    static final int ICON = 432;

    private AstrIconShortcut() {}

    /* ---------- 桥方法 ---------- */

    /**
     * 把当前 QQ 头像钉到桌面（或把已钉的那条的图标换掉）。
     * 成功 {"ok":true,"msg":"已请求添加到桌面" | "桌面图标已更新"}；失败 {"ok":false,"err":"…"}。
     */
    public static String pin(Activity act) {
        try {
            if (!apiOk()) return fail("这台手机的桌面不支持钉图标");
            String info = AstrAvatar.info(act);
            JSONObject o = new JSONObject(info);
            if (!o.optBoolean("ok", false)) {
                String err = o.optString("err", "");
                // 「头像还在下载」和「没有账号」是两回事：前者让人等两秒，后者才该去扫码
                return fail("下载中".equals(err) ? "头像还在下载，等两秒再点一次" : "还没有登录的账号");
            }
            Bitmap icon = iconOf(o.optString("data", ""));
            if (icon == null) return fail("头像图片坏了，重新登录一次试试");
            ShortcutManager sm = manager(act);
            if (sm == null) return fail("这台手机的桌面不支持钉图标");
            if (!sm.isRequestPinShortcutSupported()) return fail("这台手机的桌面不支持钉图标");
            ShortcutInfo sc = shortcut(act, icon);
            if (sc == null) return fail("系统拒绝了快捷方式（图标或参数不合法）");
            ShortcutInfo pinned = findPinned(sm);
            if (pinned != null) {
                // 僵尸条目（用户删过桌面图标）：先恢复启用状态，updateShortcuts 才会生效
                try {
                    if (!pinned.isEnabled()) sm.enableShortcuts(Arrays.asList(ID));
                } catch (Throwable ignored) {
                }
                try {
                    if (sm.updateShortcuts(Arrays.asList(sc))) return ok("桌面图标已更新");
                } catch (Throwable ignored) {
                    // 更新被拒（限流 / 条目已失效）：落到下面重新钉一次
                }
            }
            try {
                // 第二个参数是「钉完之后回调我」的 IntentSender，这里不需要；传 null 就不会被拉起
                if (sm.requestPinShortcut(sc, null)) return ok("已请求添加到桌面");
            } catch (Throwable ignored) {
            }
            return fail("桌面没有响应钉图标请求");
        } catch (Throwable t) {
            return fail("钉图标失败：" + AstrAvatar.oneLine(t));
        }
    }

    /**
     * 静默刷新桌面图标：没钉过、没登录、桌面不支持、系统限流……全都直接 return。
     * 供 QqbotActivity 在 onResume 的后台线程里调用一次，失败绝不打扰用户。
     */
    public static void refresh(Activity act) {
        try {
            if (!apiOk()) return;
            ShortcutManager sm = manager(act);
            if (sm == null) return;
            ShortcutInfo pinned = findPinned(sm);
            if (pinned == null) return; // 用户没钉过，别去碰系统
            String info = AstrAvatar.info(act);
            JSONObject o = new JSONObject(info);
            if (!o.optBoolean("ok", false)) return;
            Bitmap bm = iconOf(o.optString("data", ""));
            if (bm == null) return;
            ShortcutInfo sc = shortcut(act, bm);
            if (sc == null || !pinned.isEnabled()) return;
            sm.updateShortcuts(Arrays.asList(sc));
        } catch (Throwable ignored) {
            // 图标是装饰，刷新失败不能影响启动
        }
    }

    /* ---------- 系统侧 ---------- */

    /** 整套 ShortcutManager.getPinnedShortcuts() 是 API 25 起的，本应用 minSdk 24。 */
    static boolean apiOk() {
        return Build.VERSION.SDK_INT >= 25;
    }

    static ShortcutManager manager(Activity act) {
        try {
            return (ShortcutManager) act.getSystemService(Activity.SHORTCUT_SERVICE);
        } catch (Throwable t) {
            return null;
        }
    }

    static ShortcutInfo findPinned(ShortcutManager sm) {
        try {
            List<ShortcutInfo> list = sm.getPinnedShortcuts();
            if (list == null) return null;
            for (int i = 0; i < list.size(); i++) {
                ShortcutInfo s = list.get(i);
                if (s != null && ID.equals(s.getId())) return s;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    static ShortcutInfo shortcut(Activity act, Bitmap bm) {
        try {
            Intent i = new Intent(act, QqbotActivity.class);
            i.setAction(Intent.ACTION_VIEW);
            return new ShortcutInfo.Builder(act, ID)
                    .setShortLabel("AstrBot")
                    .setIntent(i)
                    .setIcon(icon(bm))
                    .build();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Icon.createWithAdaptiveBitmap() 是 API 26 才有的（minSdk 24），24/25 上会
     * NoSuchMethodError，所以必须自己分叉退成普通 bitmap 图标。
     * 注意：系统会把 adaptive bitmap 当成 108dp 的整块画布来裁，四周约 17% 会被
     * 桌面形状（圆 / 圆角方）切掉 —— 头像贴满 432 就够看，不必再留白。
     */
    static Icon icon(Bitmap bm) {
        try {
            if (Build.VERSION.SDK_INT >= 26) return Icon.createWithAdaptiveBitmap(bm);
        } catch (Throwable ignored) {
        }
        return Icon.createWithBitmap(bm);
    }

    /* ---------- 图片 ---------- */

    /** 把 AstrAvatar 给的 data URI 还原成 ICON 见方的图标底图（头像居中）。 */
    static Bitmap iconOf(String uri) {
        Bitmap bm = null;
        try {
            byte[] raw = bytesOf(uri);
            if (raw == null) return null;
            bm = BitmapFactory.decodeByteArray(raw, 0, raw.length);
            if (bm == null) return null;
            Bitmap sq = square(bm, ICON);
            return sq;
        } catch (Throwable t) {
            return null;
        } finally {
            if (bm != null) {
                try { bm.recycle(); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * 缩到最长边 <= size 再贴到 size 见方的画布中央（非正方形的头像留透明边）。
     * 返回值保证不等于入参：调用方可以安全地把它交给 ShortcutInfo。
     */
    static Bitmap square(Bitmap src, int size) {
        if (src == null || size <= 0) return null;
        Bitmap scaled = AstrAvatar.fit(src, size);
        int w = (scaled == null) ? 0 : scaled.getWidth();
        int h = (scaled == null) ? 0 : scaled.getHeight();
        if (w <= 0 || h <= 0) return null;
        if (w == size && h == size) {
            // 已经正好是正方形：但入参马上会被 recycle，必须自己拷一份
            Bitmap copy = scaled.copy(Bitmap.Config.ARGB_8888, false);
            if (scaled != src) scaled.recycle();
            return copy;
        }
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        int left = (size - w) / 2;
        int top = (size - h) / 2;
        c.drawBitmap(scaled, new Rect(0, 0, w, h), new Rect(left, top, left + w, top + h),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        // fit() 只在确实缩过时才返回新对象：这时中间图已经画完，立刻回收
        if (scaled != src) scaled.recycle();
        return out;
    }

    static byte[] bytesOf(String uri) {
        if (uri == null) return null;
        int k = uri.indexOf(',');
        if (k < 0 || k + 1 >= uri.length()) return null;
        try {
            return Base64.decode(uri.substring(k + 1), Base64.DEFAULT);
        } catch (Throwable t) {
            return null;
        }
    }

    /* ---------- JSON ---------- */

    static String ok(String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("msg", msg);
            return o.toString();
        } catch (Throwable t) {
            return "{\"ok\":true}";
        }
    }

    static String fail(String err) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("err", err);
            return o.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"err\":\"钉图标失败\"}";
        }
    }
}
