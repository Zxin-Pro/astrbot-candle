package com.termux.app;

import android.view.View;

/** 面板页顶部按钮的点击处理（避免匿名内部类：d8 处理匿名类会崩溃）。 */
public class PanelBack implements View.OnClickListener {

    public static final int BACK = 1;
    public static final int RELOAD = 2;

    private final QqbotPanelActivity act;
    private final int kind;

    public PanelBack(QqbotPanelActivity act, int kind) {
        this.act = act;
        this.kind = kind;
    }

    public void onClick(View v) {
        if (kind == BACK) {
            act.doBack();
        } else {
            act.doReload();
        }
    }
}
