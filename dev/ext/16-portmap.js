/* =====================================================================
   16-portmap.js — 「端口地图」卡片（id=portmap / tab=more / order=22）
   ---------------------------------------------------------------------
   为什么只有 #cePortBody 会被重画：
     ports 子命令是一串 /dev/tcp 试探，毫秒级，跟着内核 8 秒的 tick 每轮
     跑完全没负担。但每次 tick 重建整张卡会把底部按钮销毁重建，用户看到
     的就是按钮「闪一下」，按下去也可能丢失按压态。所以标题、说明、按钮
     固定在 html() 里，tick / 刷新只替换 #cePortBody 的内容。
   为什么 html() 不取数：
     CE.toolJson 内部先让出一帧再执行 Android.exec，是异步回调。html()
     里等数据只会拿到空值，所以先渲染骨架 + 「读取中」，mount / tick 里
     再异步填数据。
   ===================================================================== */
(function () {
  "use strict";

  /* 三个固定端口字段：数组顺序同时决定三格与冲突提示的展示顺序 */
  var FIELDS = [
    { key: "napcatPort", label: "NapCat WebUI", short: "NapCat" },
    { key: "onebotPort", label: "OneBot", short: "OneBot" },
    { key: "astrbotPort", label: "AstrBot 控制台", short: "AstrBot" }
  ];

  var UNAVAIL = "端口信息不可用，请先完成部署";

  var cur = null;      /* 最近一次解析成功的数据，null 表示没有可用数据 */
  var tried = false;   /* 是否已收到过一次回调：区分「读取中」和「读失败」 */
  var busy = false;    /* CE.exec 让出帧的窗口里不重复发命令，避免 tick 叠请求 */

  /* ---------------- 小工具 ---------------- */

  /* 端口统一成正整数：0 / 空 / 非数字 / 负数一律当 0（显示「—」） */
  function num(v) {
    var n = Number(v);
    if (!isFinite(n) || n <= 0) return 0;
    return Math.floor(n);
  }

  /* extras.sh 是手拼 JSON 的，布尔字段可能是 true / 1 / "true" / "1" */
  function flag(v) {
    return v === true || v === 1 || v === "true" || v === "1";
  }

  /* .ce-pre 与复制文本都靠空格对齐，这里手动补尾部空格 */
  function padRight(s, n) {
    s = String(s == null ? "" : s);
    while (s.length < n) s += " ";
    return s;
  }

  /* 结果清洗：脚本返回的东西不可信，端口非法的条目直接丢掉。
     返回 null 表示整份数据不可用，交给上层渲染空态。 */
  function norm(d) {
    if (!d || typeof d !== "object") return null;
    if (!flag(d.ok)) return null;

    var out = {
      napcatPort: num(d.napcatPort),
      onebotPort: num(d.onebotPort),
      astrbotPort: num(d.astrbotPort),
      list: []
    };

    var raw = d.list;
    if (raw && typeof raw === "object" && typeof raw.length === "number") {
      var i, it, p, who;
      for (i = 0; i < raw.length; i++) {
        it = raw[i];
        if (!it || typeof it !== "object") continue;
        p = num(it.port);
        if (!p) continue;
        who = (it.who == null || it.who === "") ? "—" : String(it.who);
        out.list.push({ port: p, who: who, open: flag(it.open) });
      }
    }
    return out;
  }

  /* ---------------- 页面片段 ---------------- */

  /* 冲突检测：三个字段两两比较。端口相同且都不为 0 时两个服务会抢同一个
     端口，后者（或先起来的那个）起不来，必须提示到具体是哪两个撞了。 */
  function conflictHTML(d) {
    var msgs = [], i, j, a, b;
    for (i = 0; i < FIELDS.length; i++) {
      for (j = i + 1; j < FIELDS.length; j++) {
        a = d[FIELDS[i].key];
        b = d[FIELDS[j].key];
        if (a > 0 && a === b) {
          msgs.push("端口冲突：" + CE.esc(FIELDS[i].short) + " 与 " + CE.esc(FIELDS[j].short) +
            " 都配成了 " + CE.esc(a) +
            "，两个服务会抢同一个端口，去「设置 → 端口设置」改一个");
        }
      }
    }
    if (!msgs.length) return "";
    /* 三个都相同时会有三条，塞进同一个提示块里逐行列出 */
    return '<div class="ce-hint ce-bad">' + msgs.join("<br>") + '</div>';
  }

  /* 顶部三格：当前实例的 NapCat / OneBot / AstrBot 端口，0 或缺失写「—」 */
  function gridHTML(d) {
    var out = "", i, p;
    for (i = 0; i < FIELDS.length; i++) {
      p = d[FIELDS[i].key];
      out += '<div class="ce-cell"><b>' + (p > 0 ? CE.esc(p) : "—") + '</b>' +
        '<span>' + CE.esc(FIELDS[i].label) + '</span></div>';
    }
    /* 三格排一行：默认 .ce-grid 是两列，这里只改布局不碰颜色 */
    return '<div class="ce-grid" style="grid-template-columns:repeat(3,1fr)">' + out + '</div>';
  }

  /* 逐行列出探测结果：左状态点、中端口 + 归属、右是否在监听 */
  function rowsHTML(list) {
    if (!list.length) return '<div class="ce-empty">没有可显示的端口</div>';
    var out = "", i, it;
    for (i = 0; i < list.length; i++) {
      it = list[i];
      out += '<div class="ce-row">' +
        '<span class="ce-dot ' + (it.open ? "ok" : "bad") + '"></span>' +
        '<div class="ce-rt"><b>端口 ' + CE.esc(it.port) + '</b>' +
        '<span>' + CE.esc(it.who) + '</span></div>' +
        '<div class="ce-rv ' + (it.open ? "ce-ok" : "ce-bad") + '">' +
        (it.open ? "监听中" : "未监听") + '</div>' +
        '</div>';
    }
    return out;
  }

  /* 底部统计：用 list 自己数，避免 listening 字段缺失时显示成 0 */
  function countHTML(list) {
    var open = 0, i;
    for (i = 0; i < list.length; i++) {
      if (list[i].open) open++;
    }
    return '<div class="ce-hint">共 ' + list.length + ' 个端口，' + open + ' 个在监听</div>';
  }

  function bodyHTML() {
    if (!cur) {
      /* 首次请求还没回来：给加载态，别让卡片闪一下「不可用」 */
      if (!tried) return '<div class="ce-load"><i></i> 正在读取端口信息…</div>';
      return '<div class="ce-empty">' + UNAVAIL + '</div>';
    }
    return conflictHTML(cur) + gridHTML(cur) + rowsHTML(cur.list) + countHTML(cur.list);
  }

  /* 只改 #cePortBody：按钮、标题都留在原位，tick 时不会闪 */
  function setBody(root, html) {
    try {
      var box = root && root.querySelector ? root.querySelector("#cePortBody") : null;
      if (box) box.innerHTML = html;
    } catch (e) {
      /* 渲染失败也不能把内核的 tick 循环带挂 */
    }
  }

  function paint(root) {
    try { setBody(root, bodyHTML()); } catch (e) {}
  }

  /* ---------------- 取数 ---------------- */

  function load(root, manual) {
    if (busy) return;
    busy = true;

    var btn = null;
    if (manual) {
      btn = root && root.querySelector ? root.querySelector('[data-ce-act="refresh"]') : null;
      if (btn) {
        btn.disabled = true;
        btn.textContent = "刷新中…";
      }
      /* 只有当前没数据时才铺 loading；已有表就留着，避免手动刷新整块闪空 */
      if (!cur) setBody(root, '<div class="ce-load"><i></i> 正在读取端口信息…</div>');
    }

    var finished = false;
    function finish(d) {
      if (finished) return;
      finished = true;
      busy = false;
      tried = true;
      try { cur = norm(d); } catch (e) { cur = null; }
      if (btn) {
        btn.disabled = false;
        btn.textContent = "刷新";
      }
      paint(root);
    }

    try {
      CE.toolJson("ports", function (d) {
        try { finish(d); } catch (e) { finish(null); }
      });
    } catch (e) {
      /* 连回调都排不进去（极端情况），别让 busy 永远卡住 */
      finish(null);
    }
  }

  /* ---------------- 复制 ---------------- */

  function copyTable(root) {
    var d = cur;
    if (!d || !d.list.length) {
      CE.toast("还没有端口数据");
      return;
    }
    var lines = [], i, it;
    for (i = 0; i < d.list.length; i++) {
      it = d.list[i];
      /* 端口补到 5 位，短端口也能和 5 位数对齐；每行形如：
         6099  NapCat WebUI  监听中 */
      lines.push(padRight(String(it.port), 5) + " " + it.who + "  " +
        (it.open ? "监听中" : "未监听"));
    }
    var text = lines.join("\n");

    var ok = false;
    try { ok = CE.copy(text); } catch (e) { ok = false; }
    if (ok) {
      CE.toast("端口表已复制");
      /* 老版本桥没有 copy 时展开的文本要收掉，不然面板越积越长 */
      var fb = root && root.querySelector ? root.querySelector("#cePortPre") : null;
      if (fb) fb.innerHTML = "";
      return;
    }
    /* 降级：把纯文本铺出来让用户长按手动复制 */
    var box = root && root.querySelector ? root.querySelector("#cePortPre") : null;
    if (box) box.innerHTML = '<div class="ce-pre">' + CE.esc(text) + '</div>';
    CE.toast("复制失败，已展开端口表，可长按选择");
  }

  /* ---------------- 注册 ---------------- */

  CE.reg({
    id: "portmap",
    tab: "more",
    order: 22,
    title: "端口地图",

    /* 骨架先出「读取中」，数据在 mount / tick 里异步补 */
    html: function () {
      var body = "";
      try { body = bodyHTML(); } catch (e) { body = '<div class="ce-empty">' + UNAVAIL + '</div>'; }
      return '<div class="card">' +
        '<h2>端口地图</h2>' +
        '<div class="hint">NapCat / OneBot / AstrBot 各占哪个端口、有没有在监听，一眼看清</div>' +
        '<div id="cePortBody">' + body + '</div>' +
        '<div class="btns" style="margin-top:10px">' +
          '<button class="small" data-ce-act="refresh">刷新</button>' +
          '<button class="small" data-ce-act="copy">复制端口表</button>' +
        '</div>' +
        '<div id="cePortPre"></div>' +
        '</div>';
    },

    /* 挂载即拉一次，不用等内核那 8 秒 */
    mount: function (root) {
      load(root, false);
    },

    /* ports 是纯 /dev/tcp 探测，很快，每轮 tick 都刷 */
    tick: function (root) {
      load(root, false);
    },

    on: function (act, arg, root) {
      if (act === "refresh") { load(root, true); return; }
      if (act === "copy") { copyTable(root); return; }
    }
  });
})();
