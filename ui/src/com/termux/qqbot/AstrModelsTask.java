package com.termux.app;

/** 拉模型列表的工作线程；单独成类是为了绕开 d8 对匿名内部类的崩溃。 */
public class AstrModelsTask implements Runnable {

    private final String base;
    private final String key;

    public AstrModelsTask(String base, String key) {
        this.base = base;
        this.key = key;
    }

    public void run() {
        String r;
        try {
            r = AstrModels.fetch(base, key);
        } catch (Throwable t) {
            r = "{\"ok\":false,\"err\":\"" + String.valueOf(t).replace('"', '\'') + "\"}";
        }
        AstrModels.done(r);
    }
}
