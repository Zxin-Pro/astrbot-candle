package com.termux.app;

import org.json.JSONObject;

import java.io.File;

/**
 * 原生层自己维护的小偏好：state/prefs.json（崩溃上报开关、匿名统计开关…）。
 *
 * 为什么不用 SharedPreferences：沙盒里的脚本也要读同一个开关，而
 * files/<包名>_preferences.xml 在 $HOME 之外，脚本根本看不到；state/ 目录
 * 本来就是宿主与沙盒之间唯一共享的那块地方。
 *
 * 读写都走 StateIO（先写 .tmp 再 rename 的原子写），整份文件几百字节、
 * 毫秒级返回，可以在桥线程里同步读写。
 */
final class Prefs {

    private static final String NAME = "prefs.json";

    /** 读-改-写要串行：两个开关同时点，不锁会把对方刚写的键覆盖掉。 */
    private static final Object LOCK = new Object();

    private Prefs() {
    }

    static File file(File filesDir) {
        return StateIO.file(filesDir, NAME);
    }

    /** 读整份 prefs.json；不存在 / 被写坏都当空对象，绝不抛。 */
    static JSONObject load(File filesDir) {
        String s = StateIO.read(file(filesDir));
        if (s != null && s.trim().length() > 0) {
            try {
                return new JSONObject(s);
            } catch (Throwable ignored) {
            }
        }
        return new JSONObject();
    }

    static boolean getBool(File filesDir, String key, boolean def) {
        synchronized (LOCK) {
            return load(filesDir).optBoolean(key, def);
        }
    }

    static long getLong(File filesDir, String key, long def) {
        synchronized (LOCK) {
            return load(filesDir).optLong(key, def);
        }
    }

    /** 只改一个键：读-改-写，返回是否真的落盘。 */
    static boolean put(File filesDir, String key, Object value) {
        synchronized (LOCK) {
            JSONObject o = load(filesDir);
            try {
                o.put(key, value);
            } catch (Throwable t) {
                return false;
            }
            return StateIO.write(file(filesDir), o.toString());
        }
    }
}
