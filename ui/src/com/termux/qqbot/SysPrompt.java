package com.termux.app;

import java.io.File;

/**
 * 「系统层级提示词」的持久化层（1.3.6-beta.1）。
 *
 * 为什么还要单独存一份：人格提示词的真身在 data_v4.db 的 personas 表里，
 * 但那份库在沙盒跑起来以后会被 AstrBot 独占（SQLite 写锁），桥线程改不动。
 * 所以界面上的「保存」先落到 state/prefs.json（跨层可见、原子写），
 * 再尽力同步进数据库：同步成功就是立刻生效，失败也不丢用户输入，
 * 下一次冷启动 / 重装播种时 AstrCfg 会拿这里的值当默认值写进库。
 *
 * 空串 = 没改过，一律回落到 AstrCfg.DEFAULT_PERSONA（「烛心」出厂人格）。
 */
final class SysPrompt {

    /** prefs.json 里的键名。改这里等于改前端契约，别动。 */
    static final String KEY = "sysPrompt";

    /** 太长的提示词模型侧会被截断，界面侧也拦一下，8000 字够写一大段设定了。 */
    static final int MAX = 8000;

    private SysPrompt() {}

    /** 用户改过的值；没改过 / 读失败都返回空串（调用方自己回落默认值）。 */
    static String stored(File filesDir) {
        try {
            String s = Prefs.load(filesDir).optString(KEY, "");
            return (s == null) ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 当前生效的提示词：改过就用改过的，否则出厂默认。 */
    static String get(File filesDir) {
        String s = stored(filesDir).trim();
        return (s.length() == 0) ? AstrCfg.DEFAULT_PERSONA : s;
    }

    /** 是否还是出厂值。 */
    static boolean isDefault(File filesDir) {
        return stored(filesDir).trim().length() == 0;
    }

    /** 保存；返回 false 表示没落盘。空串等价于恢复默认。 */
    static boolean set(File filesDir, String text) {
        try {
            String s = (text == null) ? "" : text.trim();
            if (s.length() > MAX) s = s.substring(0, MAX);
            return Prefs.put(filesDir, KEY, s);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 恢复默认：把键写成空串（JSON 里留个空值比删键更好排查）。 */
    static boolean reset(File filesDir) {
        return set(filesDir, "");
    }

    /**
     * 尽力把提示词同步进 personas 表。
     * 沙盒在跑时 AstrBot 会占着库，这里多半会失败 —— 失败不算错误，
     * 返回值只用于界面提示「已保存，重启后生效」还是「立即生效」。
     */
    static boolean pushToDb(File filesDir, String text) {
        try {
            AstrCfg.upsertPersona(filesDir, text);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}