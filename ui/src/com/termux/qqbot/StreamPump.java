package com.termux.app;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * 把子进程的 stdout 全读进内存。
 * 单独成一个具名类而不是匿名内部类：d8 处理匿名内部类会崩
 * （NullPointerException: Cannot invoke "String.length()" because "<parameter1>" is null）。
 */
class StreamPump implements Runnable {

    private static final int MAX_CHARS = 4000000;

    private final InputStream in;
    private final StringBuilder sb = new StringBuilder();
    private volatile boolean done = false;

    StreamPump(InputStream in) { this.in = in; }

    boolean isDone() { return done; }

    String text() { synchronized (this) { return sb.toString(); } }

    public void run() {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                synchronized (this) {
                    if (sb.length() < MAX_CHARS) sb.append(line).append('\n');
                }
            }
        } catch (Throwable ignored) {
        } finally {
            done = true;
            if (br != null) { try { br.close(); } catch (Exception ignored) {} }
        }
    }
}