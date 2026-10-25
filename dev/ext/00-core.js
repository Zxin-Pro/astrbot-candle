/* =====================================================================
   CE — Candle Extensions（控制台扩展内核，1.3.3-beta.4）
   ---------------------------------------------------------------------
   为什么单开一层：index.html 已经 1.1 万行，新功能再往主脚本里塞会互相踩变量。
   这里用最小内核把「扩展卡片」隔离出来：
     · 每个模块只管自己的根节点（root），不碰外面的 DOM；
     · 事件走一层委托（data-ce-act），不用 onclick 内联；
     · 取数优先复用已有的 window.Android 桥（state / todayStats / exec …），
       桥缺方法时模块自己退化成空态，绝不抛到全局。
   ES5 写法：应用内 WebView 可能是 Android 7 的旧内核，不用箭头函数 / 模板串。
   ===================================================================== */
(function () {
  if (window.CE && window.CE.v === "1.3.3-beta.4") return;

  var MODS = [];
  var MOUNTED = {};      /* id -> 已渲染过 */
  var SLOTS = {};        /* tab -> 容器节点 */
  var CURRENT = "home";
  var TICK_MS = 8000;
  var busy = {};

  /* ---------------- 基础工具 ---------------- */
  function esc(s) {
    return String(s == null ? "" : s)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;")
      .replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  }
  function kb(n) {
    n = Number(n) || 0;
    if (n < 1024) return n + " KB";
    var mb = n / 1024;
    if (mb < 1024) return (mb < 10 ? mb.toFixed(1) : Math.round(mb)) + " MB";
    var gb = mb / 1024;
    return (gb < 10 ? gb.toFixed(2) : Math.round(gb)) + " GB";
  }
  function dur(sec) {
    sec = Math.max(0, Number(sec) || 0);
    var d = Math.floor(sec / 86400), h = Math.floor((sec % 86400) / 3600);
    var m = Math.floor((sec % 3600) / 60), s = Math.floor(sec % 60);
    if (d > 0) return d + " 天 " + h + " 小时";
    if (h > 0) return h + " 小时 " + m + " 分";
    if (m > 0) return m + " 分 " + s + " 秒";
    return s + " 秒";
  }
  function clock(ts) {
    ts = Number(ts) || 0;
    if (!ts) return "—";
    var d = new Date(ts < 1e12 ? ts * 1000 : ts);
    function p(n) { return n < 10 ? "0" + n : "" + n; }
    return p(d.getHours()) + ":" + p(d.getMinutes());
  }
  function pct(a, b) {
    b = Number(b) || 0; a = Number(a) || 0;
    if (b <= 0) return 0;
    return Math.max(0, Math.min(100, Math.round(a * 100 / b)));
  }
  function json(raw, def) {
    if (raw == null || raw === "") return def === undefined ? null : def;
    if (typeof raw === "object") return raw;
    try { return JSON.parse(String(raw)); } catch (e) { return def === undefined ? null : def; }
  }
  function has(name) {
    try {
      return typeof Android !== "undefined" && !!Android && typeof Android[name] === "function";
    } catch (e) { return false; }
  }
  function call(name, a, b) {
    if (!has(name)) return "";
    try { return Android[name](a, b); } catch (e) { return ""; }
  }
  function toast(msg) {
    if (has("toast")) { try { Android.toast(String(msg)); return; } catch (e) {} }
    flash(String(msg));
  }
  function copy(text) {
    if (has("copy")) { try { Android.copy(String(text)); return true; } catch (e) {} }
    return false;
  }
  var flashBox = null, flashT = null;
  function flash(msg) {
    try {
      if (!flashBox) {
        flashBox = document.createElement("div");
        flashBox.id = "ceFlash";
        flashBox.setAttribute("role", "status");
        document.body.appendChild(flashBox);
      }
      flashBox.textContent = msg;
      flashBox.className = "on";
      clearTimeout(flashT);
      flashT = setTimeout(function () { flashBox.className = ""; }, 2200);
    } catch (e) {}
  }

  /* 命令执行：Android.exec 是同步的（最长 60 秒），会卡住 JS 线程。
     所以先让出一帧把"执行中"画出来，再调。所有扩展模块一律走这里。 */
  function exec(cmd, cb) {
    if (!has("exec")) { if (cb) cb("", false); return; }
    setTimeout(function () {
      var out = "";
      try { out = String(Android.exec(String(cmd)) || ""); } catch (e) { out = ""; }
      if (cb) cb(out, true);
    }, 40);
  }
  function tool(sub) {
    return 'bash "$PREFIX/opt/qqbot/extras.sh" ' + sub + ' 2>/dev/null';
  }
  /* 跑一条 extras.sh 子命令并把 stdout 解析成对象 */
  function toolJson(sub, cb) {
    exec(tool(sub), function (out) {
      var line = "", lines = String(out || "").split("\n");
      for (var i = lines.length - 1; i >= 0; i--) {
        var t = lines[i].trim();
        if (t.charAt(0) === "{") { line = t; break; }
      }
      cb(json(line, null));
    });
  }

  /* ---------------- 样式（只用主样式表的配色令牌，深浅色自动跟随） ---------------- */
  var CSS = [
    "#ceFlash{position:fixed;left:50%;bottom:88px;transform:translateX(-50%) translateY(14px);",
    "max-width:78%;padding:10px 16px;border-radius:14px;background:rgba(15,23,42,.92);color:#fff;",
    "font-size:13px;line-height:1.45;opacity:0;pointer-events:none;transition:opacity .2s,transform .2s;",
    "z-index:9999;box-shadow:0 12px 30px rgba(0,0,0,.28);word-break:break-all}",
    "#ceFlash.on{opacity:1;transform:translateX(-50%) translateY(0)}",
    ".ce-grid{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-top:10px}",
    ".ce-cell{background:var(--cell,rgba(15,23,42,.04));border-radius:12px;padding:9px 11px}",
    ".ce-cell b{display:block;font-size:16px;line-height:1.25;font-variant-numeric:tabular-nums;color:var(--v)}",
    ".ce-cell span{display:block;font-size:11px;color:var(--txt-3);margin-top:2px}",
    ".ce-row{display:flex;align-items:center;gap:8px;padding:9px 0;border-top:1px solid var(--line)}",
    ".ce-row:first-child{border-top:0}",
    ".ce-row .ce-rt{flex:1;min-width:0}",
    ".ce-row .ce-rt b{display:block;font-size:13px;font-weight:600;color:var(--h2);word-break:break-all}",
    ".ce-row .ce-rt span{display:block;font-size:11px;color:var(--txt-3);margin-top:2px;word-break:break-all}",
    ".ce-row .ce-rv{font-size:12px;color:var(--txt-2);font-variant-numeric:tabular-nums;white-space:nowrap}",
    ".ce-dot{width:8px;height:8px;border-radius:50%;flex:none;background:var(--dot-idle,#94a3b8)}",
    ".ce-dot.ok{background:#16a34a;box-shadow:0 0 0 3px rgba(22,163,74,.15)}",
    ".ce-dot.bad{background:#dc2626;box-shadow:0 0 0 3px rgba(220,38,38,.15)}",
    ".ce-dot.warn{background:#d97706;box-shadow:0 0 0 3px rgba(217,119,6,.15)}",
    ".ce-bar{height:6px;border-radius:99px;background:var(--line);overflow:hidden;margin-top:6px}",
    ".ce-bar i{display:block;height:100%;border-radius:99px;background:var(--acc,#2563eb);transition:width .4s}",
    ".ce-bar i.warn{background:#d97706}.ce-bar i.bad{background:#dc2626}",
    ".ce-tags{display:flex;flex-wrap:wrap;gap:6px;margin-top:8px}",
    ".ce-tag{font-size:11px;padding:3px 9px;border-radius:99px;background:var(--line);color:var(--txt-2)}",
    ".ce-pre{margin-top:8px;max-height:240px;overflow:auto;background:var(--pre,rgba(15,23,42,.05));",
    "border-radius:10px;padding:9px 11px;font-family:var(--mono);font-size:11px;line-height:1.55;",
    "white-space:pre-wrap;word-break:break-all;color:var(--txt-2)}",
    ".ce-mono{font-family:var(--mono);font-size:11px}",
    ".ce-hint{font-size:11px;color:var(--txt-3);line-height:1.5;margin-top:6px}",
    ".ce-ok{color:#16a34a}.ce-bad{color:#dc2626}.ce-warn{color:#d97706}",
    ".ce-svg{width:100%;height:auto;display:block;margin-top:8px}",
    ".ce-svg .axis{stroke:var(--line);stroke-width:1}",
    ".ce-svg .grid{stroke:var(--line);stroke-width:1;stroke-dasharray:3 4;opacity:.7}",
    ".ce-svg .ln{fill:none;stroke:var(--acc,#2563eb);stroke-width:2;stroke-linejoin:round;stroke-linecap:round}",
    ".ce-svg .ln2{fill:none;stroke:#dc2626;stroke-width:1.6;stroke-dasharray:4 3}",
    ".ce-svg .pt{fill:var(--acc,#2563eb)}",
    ".ce-svg text{fill:var(--txt-3);font-size:9px;font-family:var(--font)}",
    ".ce-svg .val{fill:var(--txt-2);font-size:10px;font-weight:600}",
    ".ce-empty{padding:14px 4px;text-align:center;font-size:12px;color:var(--txt-3)}",
    ".ce-pre mark{background:rgba(37,99,235,.22);color:inherit;border-radius:3px;padding:0 1px}",
    ".ce-kv{display:flex;justify-content:space-between;gap:10px;font-size:12px;padding:5px 0;color:var(--txt-2)}",
    ".ce-kv b{font-weight:600;color:var(--h2);font-variant-numeric:tabular-nums;text-align:right;word-break:break-all}",
    ".ce-load{display:flex;align-items:center;gap:8px;font-size:12px;color:var(--txt-3);padding:10px 2px}",
    ".ce-load i{width:14px;height:14px;border:2px solid var(--line);border-top-color:var(--acc,#2563eb);",
    "border-radius:50%;animation:ceSpin .8s linear infinite;display:block}",
    "@keyframes ceSpin{to{transform:rotate(360deg)}}"
  ].join("");

  function injectCSS() {
    if (document.getElementById("ceCSS")) return;
    var st = document.createElement("style");
    st.id = "ceCSS";
    st.appendChild(document.createTextNode(CSS));
    (document.head || document.documentElement).appendChild(st);
  }

  /* ---------------- 模块注册 / 挂载 ---------------- */
  function reg(mod) {
    if (!mod || !mod.id) return;
    for (var i = 0; i < MODS.length; i++) if (MODS[i].id === mod.id) return;
    mod.tab = mod.tab || "more";
    mod.order = typeof mod.order === "number" ? mod.order : 50;
    MODS.push(mod);
    MODS.sort(function (a, b) { return (a.order || 50) - (b.order || 50); });
  }

  function slot(tab, pos) {
    var key = tab + "#" + (pos || "end");
    if (SLOTS[key]) return SLOTS[key];
    var sec = document.getElementById("tab-" + tab);
    if (!sec) return null;
    var box = document.createElement("div");
    box.className = "ce-slot";
    box.setAttribute("data-ce-tab", tab);
    if (pos === "top" && sec.firstChild) sec.insertBefore(box, sec.firstChild);
    else sec.appendChild(box);
    SLOTS[key] = box;
    return box;
  }

  function wrap(mod) {
    var d = document.createElement("div");
    d.className = "ce-mod";
    d.setAttribute("data-ce-mod", mod.id);
    d.setAttribute("data-ce-order", String(mod.order || 50));
    return d;
  }

  function render(mod, force) {
    var box = slot(mod.tab, mod.pos);
    if (!box) return;
    var root = box.querySelector('[data-ce-mod="' + mod.id + '"]');
    if (!root) {
      root = wrap(mod);
      /* 按 order 插到合适位置：找到第一个 order 比它大的已存在模块，插在它前面 */
      var after = null, kids = box.children;
      for (var i = 0; i < kids.length; i++) {
        var mid = kids[i].getAttribute("data-ce-mod");
        var mm = find(mid);
        if (mm && (mm.order || 50) > (mod.order || 50)) { after = kids[i]; break; }
      }
      if (after) box.insertBefore(root, after); else box.appendChild(root);
    }
    if (MOUNTED[mod.id] && !force) return;
    var html = "";
    try { html = mod.html ? String(mod.html() || "") : ""; } catch (e) { html = ""; }
    if (!html) { root.parentNode && root.parentNode.removeChild(root); return; }
    root.innerHTML = html;
    MOUNTED[mod.id] = true;
    if (mod.mount) { try { mod.mount(root); } catch (e) {} }
  }

  function find(id) {
    for (var i = 0; i < MODS.length; i++) if (MODS[i].id === id) return MODS[i];
    return null;
  }

  function renderTab(tab) {
    CURRENT = tab;
    for (var i = 0; i < MODS.length; i++) if (MODS[i].tab === tab) render(MODS[i], false);
    tickTab(tab);
  }

  function tickTab(tab) {
    for (var i = 0; i < MODS.length; i++) {
      var m = MODS[i];
      if (m.tab !== tab) continue;
      /* 没挂上的模块每轮再试一次：像「上手检查」这种会把自己整张卡收起来的模块，
         状态变了（部署完成 / 权限给了）就得能重新长回来，不能一次空串就永久消失。 */
      if (!MOUNTED[m.id]) { render(m, true); continue; }
      var box = SLOTS[tab + "#" + (m.pos || "end")];
      if (!box) continue;
      var root = box.querySelector('[data-ce-mod="' + m.id + '"]');
      if (!root || !m.tick) continue;
      try { m.tick(root); } catch (e) {}
    }
  }

  /* ---------------- 事件委托 ---------------- */
  function rootOf(node) {
    while (node && node !== document) {
      if (node.getAttribute && node.getAttribute("data-ce-mod")) return node;
      node = node.parentNode;
    }
    return null;
  }

  document.addEventListener("click", function (ev) {
    var t = ev.target;
    while (t && t !== document && !(t.getAttribute && t.getAttribute("data-ce-act"))) t = t.parentNode;
    if (!t || t === document || !t.getAttribute) return;
    var act = t.getAttribute("data-ce-act");
    if (!act) return;
    var root = rootOf(t);
    if (!root) return;
    var mod = find(root.getAttribute("data-ce-mod"));
    if (!mod || !mod.on) return;
    var arg = t.getAttribute("data-ce-arg");
    ev.preventDefault();
    try { mod.on(act, arg, root, t); } catch (e) { toast("操作失败：" + e.message); }
  }, false);

  /* ---------------- 定时刷新：只刷当前页，页面不可见时整轮跳过 ---------------- */
  setInterval(function () {
    if (document.hidden) return;
    tickTab(CURRENT);
  }, TICK_MS);

  /* 主脚本切页时调我们（showTab 里已经有一处 extOnTab 调用，这里再挂一次监听，
     两条路径都覆盖：新版 showTab 会派发 ce:tab 事件，旧版只走 extOnTab 钩子）。 */
  document.addEventListener("ce:tab", function (e) {
    if (e && e.detail) renderTab(String(e.detail));
  }, false);

  /* ---------------- 对外接口 ---------------- */
  window.CE = {
    v: "1.3.3-beta.4",
    reg: reg,
    tab: renderTab,
    tick: tickTab,
    get: find,
    has: has,
    call: call,
    exec: exec,
    tool: tool,
    toolJson: toolJson,
    toast: toast,
    copy: copy,
    esc: esc,
    kb: kb,
    dur: dur,
    clock: clock,
    pct: pct,
    json: json,
    busy: busy,
    /* 记一条本地偏好（扩展模块自己的小状态都放这里，别污染主脚本的 S） */
    ls: function (k, v) {
      try {
        if (v === undefined) {
          var raw = localStorage.getItem("ce_" + k);
          return raw == null ? null : json(raw, raw);
        }
        localStorage.setItem("ce_" + k, JSON.stringify(v));
      } catch (e) {}
      return null;
    }
  };

  injectCSS();

  /* 首次启动：等主脚本把 4 秒一轮的 refresh 排上，再挂第一屏 */
  function boot() {
    injectCSS();
    renderTab("home");
  }
  if (document.readyState === "complete" || document.readyState === "interactive") {
    setTimeout(boot, 600);
  } else {
    document.addEventListener("DOMContentLoaded", function () { setTimeout(boot, 600); }, false);
  }
})();
