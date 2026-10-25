(function () {
  "use strict";

  /* ------------------------------------------------------------------
     实例筛选 / 批量（bots，置顶）
     ------------------------------------------------------------------
     多号用户的痛点是「十几个实例卡，找一个号要滑三屏」。主脚本把实例卡渲染进
     #botList，每次 refresh 都可能整体重写 innerHTML，所以这里不接管渲染，
     只在它写完之后给 .bot 加/去 display:none —— 用 MutationObserver 跟着重放，
     不跟主脚本抢 DOM。批量只做「启动」，不做「停止」：一键停掉所有号会让人
     以为软件崩了，那种操作应该是 deliberate 的，不该藏在一个批量按钮里。
     ------------------------------------------------------------------ */

  var KEY_Q = "inst.q";
  var KEY_SORT = "inst.sort";

  function q() { return String(CE.ls(KEY_Q) || ""); }
  function sortMode() { return String(CE.ls(KEY_SORT) || "default"); }

  function list() {
    return document.getElementById("botList");
  }

  function cards() {
    var l = list(), out = [];
    if (!l) return out;
    for (var i = 0; i < l.children.length; i++) {
      var c = l.children[i];
      if (c.classList && c.classList.contains("bot")) out.push(c);
    }
    return out;
  }

  function isOnline(card) {
    if (!card) return false;
    if (card.classList.contains("active")) return true;
    var t = card.textContent || "";
    return /运行中|在线|已登录/.test(t);
  }

  function applyFilter() {
    var kw = q().toLowerCase();
    var cs = cards(), shown = 0, i;
    for (i = 0; i < cs.length; i++) {
      var hit = !kw || (cs[i].textContent || "").toLowerCase().indexOf(kw) >= 0;
      cs[i].style.display = hit ? "" : "none";
      if (hit) shown++;
    }
    /* 主脚本在「没有实例」时塞一个 .empty，过滤后要给它让路 */
    var l = list();
    if (l) {
      for (i = 0; i < l.children.length; i++) {
        if (l.children[i].classList && l.children[i].classList.contains("empty")) {
          l.children[i].style.display = (cs.length && shown === 0) ? "" : "none";
        }
      }
    }
    return { shown: shown, total: cs.length };
  }

  function applySort() {
    var l = list();
    if (!l || sortMode() === "default") return;
    var cs = cards();
    var online = [], offline = [];
    for (var i = 0; i < cs.length; i++) (isOnline(cs[i]) ? online : offline).push(cs[i]);
    var ordered = online.concat(offline);
    for (i = 0; i < ordered.length; i++) l.appendChild(ordered[i]);
  }

  function stats() {
    var cs = cards(), on = 0, i;
    for (i = 0; i < cs.length; i++) if (isOnline(cs[i])) on++;
    return { total: cs.length, on: on };
  }

  /* 主脚本重写 #botList.innerHTML 之后，display:none 会全丢 —— 跟着补一遍。
     用 requestAnimationFrame 合并同一轮里的多次变更，别在 4 秒一次的刷新里连跑十几遍。 */
  function watch() {
    var l = list();
    if (!l || l.__ceWatched) return;
    l.__ceWatched = true;
    if (typeof MutationObserver === "undefined") return;   /* 老内核直接退化成 tick 时补 */
    var pend = false;
    var mo = new MutationObserver(function () {
      if (pend) return;
      pend = true;
      (window.requestAnimationFrame || function (fn) { setTimeout(fn, 16); })(function () {
        pend = false;
        applySort();
        refresh();
      });
    });
    mo.observe(l, { childList: true });
  }

  function body(root) { return root.querySelector("#ceInstBody"); }

  function paint(root) {
    var b = body(root);
    if (!b) return;
    var s = stats();
    var filtered = applyFilter();
    b.innerHTML = '<div class="ce-tags">' +
      '<span class="ce-tag">共 ' + s.total + " 个实例</span>" +
      '<span class="ce-tag" style="background:rgba(22,163,74,.14);color:#16a34a">在线 ' + s.on + "</span>" +
      (q() ? '<span class="ce-tag">筛选出 ' + filtered.shown + " 个</span>" : "") +
      "</div>";
  }

  function refresh() {
    var root = null;
    var mods = document.querySelectorAll('[data-ce-mod="instpro"]');
    if (mods.length) root = mods[0];
    if (root) paint(root);
  }

  CE.reg({
    id: "instpro",
    tab: "bots",
    pos: "top",
    order: 10,
    title: "实例筛选",
    html: function () {
      /* 没有实例时不显示：空列表上放一个筛选框很奇怪 */
      if (!cards().length) return "";
      return '<div class="card"><h2>实例筛选</h2>' +
        '<div class="hint" style="margin-top:0">号多了以后用关键字快速定位；「运行中优先」把在线的排到最上面。</div>' +
        '<div class="field" style="margin-top:8px"><input id="ceInstQ" type="search" ' +
        'placeholder="搜名称 / QQ 号 / 端口" value="' + CE.esc(q()) + '" autocomplete="off"></div>' +
        '<div id="ceInstBody"></div>' +
        '<div class="btns" style="margin-top:8px">' +
        '<button class="small" data-ce-act="sort">' + (sortMode() === "online" ? "排序：运行中优先" : "排序：默认") + "</button>" +
        '<button class="small" data-ce-act="startall">启动全部</button>' +
        "</div></div>";
    },
    mount: function (root) {
      watch();
      applySort();
      paint(root);
      var inp = root.querySelector("#ceInstQ");
      if (!inp) return;
      var timer = null;
      inp.addEventListener("input", function () {
        clearTimeout(timer);
        timer = setTimeout(function () {
          CE.ls(KEY_Q, inp.value);
          paint(root);
        }, 220);
      }, false);
    },
    tick: function (root) {
      watch();
      var inp = root.querySelector("#ceInstQ");
      if (inp && document.activeElement === inp) return;
      /* 主脚本重新渲染过卡片时 input 会被重建，这里把值补回去 */
      if (inp && inp.value !== q()) inp.value = q();
      paint(root);
    },
    on: function (act, arg, root, el) {
      if (act === "sort") {
        var next = sortMode() === "online" ? "default" : "online";
        CE.ls(KEY_SORT, next);
        if (el) el.textContent = next === "online" ? "排序：运行中优先" : "排序：默认";
        applySort();
        CE.toast(next === "online" ? "已按运行状态排序" : "已恢复默认顺序");
        return;
      }
      if (act === "startall") {
        if (el && el.getAttribute("data-sure") !== "1") {
          el.setAttribute("data-sure", "1");
          el.textContent = "再点一次启动全部";
          setTimeout(function () {
            if (!el) return;
            el.setAttribute("data-sure", "0");
            el.textContent = "启动全部";
          }, 3500);
          return;
        }
        if (el) { el.setAttribute("data-sure", "0"); el.textContent = "启动全部"; }
        startAll();
      }
    }
  });

  /* 逐个把 enable === false 的实例打开。toggleBot 是「翻转」语义，
     所以必须先读 state 里的 enable，不能无脑调一遍（会把手动停掉的号又打开、
     把开着的号关掉）。 */
  function startAll() {
    var S = CE.json(CE.call("state"), null) || {};
    var bs = (S.bots && S.bots.length) ? S.bots : [];
    var todo = [], i;
    for (i = 0; i < bs.length; i++) {
      if (bs[i] && bs[i].enable === false) todo.push(bs[i]);
    }
    if (!todo.length) { CE.toast("所有实例都已经在运行"); return; }
    if (!CE.has("toggleBot")) { CE.toast("当前版本不支持批量启动"); return; }
    var n = 0;
    (function step() {
      if (n >= todo.length) {
        CE.toast("已启动 " + todo.length + " 个实例");
        return;
      }
      var b = todo[n++];
      try {
        var r = CE.json(CE.call("toggleBot", b.id), null);
        if (r && r.ok === false) CE.toast((b.label || b.id) + " 启动失败：" + (r.error || r.err || "未知原因"));
      } catch (e) {}
      /* 每个实例都要写一次 bots.json，串行一点，别把主线程一次性堵住 */
      setTimeout(step, 260);
    })();
  }
})();
