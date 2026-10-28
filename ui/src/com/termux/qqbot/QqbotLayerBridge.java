package com.termux.app;

import android.webkit.JavascriptInterface;

import java.io.File;

/**
 * AndroidLayer 桥：分层状态 / 手动重启 / 崩溃快照 / 低电高温保护。
 *
 * web.addJavascriptInterface(new QqbotLayerBridge(this), "AndroidLayer");
 * 所有方法都必须吞异常，返回 {"ok":false,"err":"人话"}，否则 WebView 里直接崩。
 */
public class QqbotLayerBridge {

    private final File filesDir;

    public QqbotLayerBridge(QqbotActivity act) {
        this.filesDir = act.filesDirPub();
    }

    public QqbotLayerBridge(File filesDir) {
        this.filesDir = filesDir;
    }

    @JavascriptInterface
    public String status() {
        try {
            return QqbotLayers.statusJson(filesDir);
        } catch (Throwable t) {
            return fail("读取分层状态失败：" + t);
        }
    }

    @JavascriptInterface
    public String restart(String id) {
        try {
            String sid = id == null ? "" : id.trim();
            if (!QqbotLayers.validId(sid)) {
                return fail("不认识的层：" + sid);
            }
            return QqbotLayers.restartAsync(filesDir, sid);
        } catch (Throwable t) {
            return fail("重启失败：" + t);
        }
    }

    @JavascriptInterface
    public String crashList() {
        try {
            return QqbotCrashScene.listJson(filesDir);
        } catch (Throwable t) {
            return fail("读取崩溃现场列表失败：" + t);
        }
    }

    @JavascriptInterface
    public String crashGet(String id) {
        try {
            return QqbotCrashScene.getJson(filesDir, id == null ? "" : id.trim());
        } catch (Throwable t) {
            return fail("读取崩溃现场失败：" + t);
        }
    }

    @JavascriptInterface
    public String crashDel(String id) {
        try {
            return QqbotCrashScene.delJson(filesDir, id == null ? "" : id.trim());
        } catch (Throwable t) {
            return fail("删除崩溃现场失败：" + t);
        }
    }

    @JavascriptInterface
    public String protect(String json) {
        try {
            return QqbotLayers.saveProtect(filesDir, json);
        } catch (Throwable t) {
            return fail("保存保护设置失败：" + t);
        }
    }

    /** 周报等后台逻辑直接用静态入口，不需要 WebView。 */
    public static String layerStatusJson(File filesDir) {
        try {
            return QqbotLayers.statusJson(filesDir);
        } catch (Throwable t) {
            return "{\"ok\":false,\"err\":\"" + QqbotCrashScene.esc("读取分层状态失败：" + t) + "\"}";
        }
    }

    static String fail(String msg) {
        return "{\"ok\":false,\"err\":\"" + QqbotCrashScene.esc(msg) + "\"}";
    }
}
