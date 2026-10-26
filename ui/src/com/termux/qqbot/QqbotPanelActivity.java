package com.termux.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 应用内的网页面板：直接打开 NapCat WebUI / AstrBot 控制台。
 * 单独用 WebView 是为了让用户在手机屏幕上看到 NapCat 的登录二维码，
 * 再用另一台设备扫码；同时避免跳出本应用。
 */
public class QqbotPanelActivity extends Activity {

    private WebView web;
    private String url = "about:blank";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            Intent it = getIntent();
            if (it != null) {
                String u = it.getStringExtra("url");
                if (u != null && u.length() > 0) url = u;
            }
        } catch (Throwable ignored) {
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0F1115);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF171A21);
        bar.setPadding(dp(12), dp(10), dp(12), dp(10));

        TextView back = new TextView(this);
        back.setText("← 返回");
        back.setTextColor(0xFF7FB2FF);
        back.setTextSize(15f);
        back.setPadding(dp(4), dp(6), dp(18), dp(6));
        back.setOnClickListener(new PanelBack(this, PanelBack.BACK));
        bar.addView(back);

        TextView reload = new TextView(this);
        reload.setText("刷新");
        reload.setTextColor(0xFF7FB2FF);
        reload.setTextSize(15f);
        reload.setPadding(dp(4), dp(6), dp(18), dp(6));
        reload.setOnClickListener(new PanelBack(this, PanelBack.RELOAD));
        bar.addView(reload);

        TextView title = new TextView(this);
        title.setText(url);
        title.setTextColor(0xFF9AA4B2);
        title.setTextSize(11f);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        web.setWebViewClient(new QqClient());
        // 同 QqbotActivity：安卓 12+ 的 overscroll stretch 会把整块 WebView 表面拉伸，
        // NapCat / AstrBot 自己的固定头尾也会跟着变形；滚动条同样是 View 画的，CSS 关不掉。
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setBackgroundColor(0xFF0F1115);
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        web.loadUrl(url);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    public void doBack() {
        finish();
    }

    public void doReload() {
        if (web != null) web.reload();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web != null && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
