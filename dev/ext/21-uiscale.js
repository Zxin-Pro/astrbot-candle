(function () {
  "use strict";

  /* ------------------------------------------------------------------
     界面缩放 + 紧凑模式（settings）
     ------------------------------------------------------------------
     为什么用 zoom 而不是改 font-size：主样式表里字号全是 px（一千多处），
     没有 rem 基准，改根字号一点效果都没有。zoom 是 WebView 原生支持的整页缩放，
     一行就能让「字太小看不清」的用户用上，副作用是 1:1 像素背景图会被一起缩放
     —— 这一点在文案里如实说明，不做隐藏。
     两个偏好都存 localStorage，和主题一样只影响本机。
     ------------------------------------------------------------------ */

  var SIZES = [
    { v: "0.94", n: "紧凑" },
    { v: "1", n: "标准" },
    { v: "1.08", n: "大" },
    { v: "1.16", n: "特大" }
  ];

  function cur() {
    var z = CE.ls("ui.zoom");
    return z == null ? "1" : String(z);
  }
  function compact() {
    return CE.ls("ui.compact") === 1;
  }

  /* 注入/更新样式表。放在 head 末尾，优先级压过主样式表。 */
  function apply() {
    var z = cur(), cp = compact();
    var st = document.getElementById("ceUIScale");
    if (!st) {
      st = document.createElement("style");
      st.id = "ceUIScale";
      (document.head || document.documentElement).appendChild(st);
    }
    var css = "";
    if (z !== "1") {
      /* zoom 只作用在内容区，避开固定在视口上的东西（toast/浮层），
         否则它们会跟着缩放跑到屏幕外。 */
      css += "body{zoom:" + z + "}";
    }
    if (cp) {
      css += ".card{padding:12px 13px;margin:9px 0;border-radius:16px}";
      css += ".card h2{font-size:14px;margin-bottom:2px}";
      css += ".hint{font-size:11px;line-height:1.45}";
      css += ".tab{padding-top:4px}";
      css += ".btns{margin-top:6px}";
      css += ".lkrow{padding:8px 0}";
    }
    st.textContent = css;
  }

  function paint(root) {
    var body = root.querySelector("#ceUiBody");
    if (!body) return;
    var z = cur(), out = "";
    for (var i = 0; i < SIZES.length; i++) {
      out += '<button data-ce-act="zoom" data-ce-arg="' + SIZES[i].v + '" aria-pressed="' +
        (SIZES[i].v === z ? "true" : "false") + '">' + SIZES[i].n + "</button>";
    }
    body.innerHTML = '<div class="seg" role="group" aria-label="界面缩放">' + out + "</div>" +
      '<div class="hint">当前 ' + Math.round(parseFloat(z) * 100) + "%" +
      (z !== "1" ? "（不是标准比例时，本机背景图会跟着缩放）" : "") + "</div>";
  }

  CE.reg({
    id: "uiscale",
    tab: "settings",
    order: 10,
    title: "界面缩放",
    html: function () {
      /* 进来先把已保存的偏好应用一遍：应用重载后 localStorage 还在，
         但注入的 <style> 已经没了，不重放的话用户会以为设置丢了。 */
      apply();
      return '<div class="card"><h2>界面缩放</h2>' +
        '<div id="ceUiBody"></div>' +
        '<div class="ce-row" style="margin-top:6px">' +
        '<div class="ce-rt"><b>紧凑模式</b><span>卡片间距和留白收紧，一屏多放两行</span></div>' +
        '<button class="small" data-ce-act="compact" aria-pressed="' + (compact() ? "true" : "false") + '">' +
        (compact() ? "已开启" : "开启") + "</button></div></div>";
    },
    mount: function (root) { apply(); paint(root); },
    tick: function (root) { paint(root); },
    on: function (act, arg, root, el) {
      if (act === "zoom") {
        CE.ls("ui.zoom", arg);
        apply();
        paint(root);
        CE.toast("界面缩放 " + Math.round(parseFloat(arg) * 100) + "%");
      } else if (act === "compact") {
        var on = !compact();
        CE.ls("ui.compact", on ? 1 : 0);
        apply();
        if (el) {
          el.textContent = on ? "已开启" : "开启";
          el.setAttribute("aria-pressed", on ? "true" : "false");
        }
        CE.toast(on ? "紧凑模式已开启" : "紧凑模式已关闭");
      }
    }
  });
})();
