package com.termux.app;

/** 拉官网版本号的工作线程；单独成类是为了绕开 d8 对匿名内部类的崩溃。 */
public class SiteVerTask implements Runnable {

    public void run() {
        String r;
        try {
            r = SiteVer.fetch();
        } catch (Throwable t) {
            r = "{\"ok\":false,\"err\":\"" + String.valueOf(t).replace('"', '\'') + "\"}";
        }
        SiteVer.done(r);
    }
}
