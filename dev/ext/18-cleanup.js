/* =====================================================================
   18-cleanup.js — 「存储清理」卡片（id=cleanup / tab=more / order=26）
   ---------------------------------------------------------------------
   为什么每个清理都要点两次：
     clean 子命令是真删（rm -rf 缓存目录），误触一次就是一次不可逆的删除。
     WebView 里的原生 confirm 对话框在部分国产 ROM / 老内核上不弹或拿不到返回值，
     所以不用它，改成按钮自己变「再点确认」，3.5 秒内再点才真正执行。
   为什么点完先把按钮换成 .ce-load：
     CE.exec 底层是 Android.exec，同步阻塞 JS 线程（pycache / apt 要跑几秒）。
     不先把 loading 画进 DOM 就调用，用户看到的是整页「卡死」、毫无反馈。
     所以顺序是：置灰按钮 → 换成 loading → setTimeout 50ms 让浏览器完成一次
     绘制 → 再发命令；回调里再把按钮换回来。
   为什么只做这四类：
     旧日志、下载缓存、__pycache__、apt 索引都能自动重建；配置、bots.json、
     登录态不碰。卡片说明里写明这一点，用户才敢点「全部清理」。
   ===================================================================== */
(function () {
  "use strict";

  var UNAVAIL = "清理脚本不可用，请先完成部署";
  var FAILED = "清理失败";
  var CONFIRM_TEXT = "再点确认";
  var CONFIRM_MS = 3500;   /* 二次确认的有效窗口 */

  /* name 是行标题，desc 是副说明，btn 是右侧按钮文案 */
  var ITEMS = [
    { key: "logs", name: "旧日志", desc: "超过 1 天的 .log", btn: "清理旧日志" },
    { key: "cache", name: "下载缓存", desc: "安装包与临时解包目录", btn: "清理下载缓存" },
    { key: "pycache", name: "Python 缓存", desc: "__pycache__ 与 pip 缓存，会慢一点", btn: "清理 Python 缓存" },
    { key: "apt", name: "apt 索引", desc: "/var/lib/apt/lists", btn: "清理 apt 索引" }
  ];

  /* data-ce-arg 是 DOM 属性，可能被调试面板改掉；只放行固定几个值，
     避免任意字符串被拼进 shell 命令。 */
  var ALLOWED = { logs: 1, cache: 1, pycache: 1, apt: 1, all: 1 };

  /* 二次确认的现场：同一个时刻只允许一个按钮处于「再点确认」状态 */
  var pendingKey = null;
  var pendingEl = null;
  var pendingLabel = "";
  var pendingTimer = 0;

  /* 清理请求在途：CE.exec 让出帧的窗口里用户还能点到别的按钮，挡住重复请求 */
  var running = false;
  /* 顶部结果提示的自动清空计时器 */
  var hintTimer = 0;

  function labelOf(target) {
    if (target === "all") return "全部清理";
    var i;
    for (i = 0; i < ITEMS.length; i++) {
      if (ITEMS[i].key === target) return ITEMS[i].btn;
    }
    return "清理";
  }

  /* 数字字段清洗：extras.sh 理论上只会给数字，但手拼 JSON 出了偏差时
     宁可当失败，也不要显示「已释放 NaN KB」。返回 -1 表示字段不可用。 */
  function num(v) {
    var n = Number(v);
    if (!isFinite(n) || n < 0) return -1;
    return Math.floor(n);
  }

  /* 输出可能带杂行（bash 警告、proot 提示），从后往前找最后一行以 { 开头的，
     和 CE.toolJson 的取法保持一致。 */
  function parseOut(raw) {
    var lines = String(raw == null ? "" : raw).split("\n");
    var i, t;
    for (i = lines.length - 1; i >= 0; i--) {
      t = lines[i].trim();
      if (t.charAt(0) === "{") return CE.json(t, null);
    }
    return null;
  }

  /* ---------------- 页面片段 ---------------- */

  function rowsHTML() {
    var out = "", i, it;
    for (i = 0; i < ITEMS.length; i++) {
      it = ITEMS[i];
      out += '<div class="ce-row">' +
        '<div class="ce-rt"><b>' + CE.esc(it.name) + '</b>' +
        '<span>' + CE.esc(it.desc) + '</span></div>' +
        '<button class="small" data-ce-act="clean" data-ce-arg="' + CE.esc(it.key) + '">' +
        CE.esc(it.btn) + '</button>' +
        '</div>';
    }
    return out;
  }

  /* 顶部提示：成功绿、失败红，4 秒后自己消失，不长期占着卡片高度 */
  function showHint(root, cls, text) {
    var box = root.querySelector("#ceCleanHint");
    if (!box) return;
    box.innerHTML = '<div class="ce-hint ' + cls + '">' + CE.esc(text) + '</div>';
    if (hintTimer) clearTimeout(hintTimer);
    hintTimer = setTimeout(function () {
      hintTimer = 0;
      var b = root.querySelector("#ceCleanHint");
      if (b) b.innerHTML = "";
    }, 4000);
  }

  /* 开始新一轮清理前先把上一条结果收掉，避免旧的「已释放…」被误读成本次结果 */
  function clearHint(root) {
    if (hintTimer) {
      clearTimeout(hintTimer);
      hintTimer = 0;
    }
    var box = root.querySelector("#ceCleanHint");
    if (box) box.innerHTML = "";
  }

  /* ---------------- 二次确认 ---------------- */

  function cancelConfirm() {
    if (pendingTimer) {
      clearTimeout(pendingTimer);
      pendingTimer = 0;
    }
    var node = pendingEl, label = pendingLabel;
    pendingKey = null;
    pendingEl = null;
    pendingLabel = "";
    /* 节点可能已被重建（切页 / 强制重渲染），判空后再改，改不到也不报错 */
    if (node) {
      try { node.textContent = label; } catch (e) {}
    }
  }

  function armConfirm(target, el) {
    if (!el) return;
    pendingKey = target;
    pendingEl = el;
    pendingLabel = labelOf(target);
    try { el.textContent = CONFIRM_TEXT; } catch (e) {}
    pendingTimer = setTimeout(function () {
      pendingTimer = 0;
      var node = pendingEl, label = pendingLabel;
      pendingKey = null;
      pendingEl = null;
      pendingLabel = "";
      try { if (node) node.textContent = label; } catch (e) {}
    }, CONFIRM_MS);
  }

  /* ---------------- 真正执行清理 ---------------- */

  function run(target, root, btn) {
    if (running) return;
    running = true;

    try { clearHint(root); } catch (e0) {}

    var label = labelOf(target);
    var cmd = "";
    try {
      cmd = CE.tool("clean " + target);
    } catch (eT) {
      /* 理论上不会发生；真出了岔子也按内核同样的格式拼一条，别让按钮卡死 */
      cmd = 'bash "$PREFIX/opt/qqbot/extras.sh" clean ' + target + " 2>/dev/null";
    }
    var load = null;
    var done = false;

    /* 把按钮原地换成 .ce-load（行按钮换在行右侧，全部清理换在 .btns 里） */
    function swapInLoad() {
      var box = document.createElement("div");
      box.className = "ce-load";
      box.innerHTML = "<i></i> 清理中…";
      if (btn && btn.parentNode) {
        btn.parentNode.replaceChild(box, btn);
        return box;
      }
      return null;
    }

    /* 无论成功失败都必须走这里：恢复按钮，否则下一次点它就是个死按钮 */
    function finish(cls, text) {
      if (done) return;
      done = true;
      running = false;
      if (btn) {
        try {
          btn.disabled = false;
          btn.textContent = label;
          if (load && load.parentNode) load.parentNode.replaceChild(btn, load);
        } catch (e) {}
      }
      try { showHint(root, cls, text); } catch (e2) {}
    }

    try {
      if (btn) btn.disabled = true;
      load = swapInLoad();
    } catch (e) {
      /* 连 loading 都画不出来也别放弃执行：命令本身是安全的幂等删除 */
      load = null;
    }

    /* 50ms 是留给浏览器把 loading 真正画到屏幕上的，不能省 */
    setTimeout(function () {
      try {
        CE.exec(cmd, function (out, ok) {
          var data = null;
          try { data = parseOut(out); } catch (e) { data = null; }
          try {
            /* ok=false 表示桥里根本没有 exec，命令压根没跑 */
            if (!ok) { finish("ce-bad", UNAVAIL); return; }
            /* 解析失败 / ok 不为 true：多半是 extras.sh 没部署或版本不匹配 */
            if (!data || typeof data !== "object" || data.ok !== true) {
              finish("ce-bad", UNAVAIL);
              return;
            }
            var freed = num(data.freedKB);
            var before = num(data.beforeKB);
            var after = num(data.afterKB);
            if (freed < 0 || before < 0 || after < 0) {
              finish("ce-bad", FAILED);
              return;
            }
            finish("ce-ok", "已释放 " + CE.kb(freed) +
              "（前 " + CE.kb(before) + " → 后 " + CE.kb(after) + "）");
          } catch (e2) {
            finish("ce-bad", FAILED);
          }
        });
      } catch (e3) {
        finish("ce-bad", FAILED);
      }
    }, 50);
  }

  CE.reg({
    id: "cleanup",
    tab: "more",
    order: 26,
    title: "存储清理",

    /* 纯静态骨架：不在这里跑任何命令，进页面不会卡、更不会自动删东西 */
    html: function () {
      var body = "";
      try { body = rowsHTML(); } catch (e) { body = '<div class="ce-empty">清理项加载失败</div>'; }
      return '<div class="card">' +
        '<h2>存储清理</h2>' +
        '<div class="hint">只删能重建的东西：旧日志、下载缓存、Python 缓存、apt 索引。配置和登录态不动。</div>' +
        '<div id="ceCleanHint" aria-live="polite"></div>' +
        '<div id="ceCleanRows">' + body + '</div>' +
        '<div class="btns">' +
          '<button class="small primary" data-ce-act="clean" data-ce-arg="all">全部清理</button>' +
        '</div>' +
        '</div>';
    },

    /* 模块被重建时把上一轮的计时器和状态全部归零，避免老按钮的定时器乱改新 DOM */
    mount: function () {
      cancelConfirm();
      running = false;
      if (hintTimer) {
        clearTimeout(hintTimer);
        hintTimer = 0;
      }
    },

    on: function (act, arg, root, el) {
      if (act !== "clean") return;
      var target = arg == null ? "" : String(arg);
      /* 用 hasOwnProperty 而不是直接取 ALLOWED[target]：后者的原型链上
         有 toString / constructor，会被当成合法目标放进来 */
      if (!Object.prototype.hasOwnProperty.call(ALLOWED, target)) return;
      if (running) {
        CE.toast("正在清理，请稍候");
        return;
      }
      /* 同一个按钮第二次点：窗口内才执行；因为 pendingKey 已随超时清空，
         超时后再点只会重新进入「再点确认」，不会误删。 */
      if (pendingKey === target) {
        cancelConfirm();
        run(target, root, el);
        return;
      }
      /* 点了别的清理项：撤销上一个的确认态，只保留最后点的那个 */
      cancelConfirm();
      armConfirm(target, el);
    }
  });
})();
