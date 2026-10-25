/* =====================================================================
   15-netdiag.js — 「网络诊断」卡片（tab=more，order=20）
   ---------------------------------------------------------------------
   为什么做成「按钮触发 + 结果面板」：
     extras.sh 的 netdiag 子命令里有一条 curl（最长 6 秒），而 CE.exec 是
     同步阻塞 JS 线程的。如果 html()/mount/tick 里自动跑，用户一进「更多」
     页就整页卡住 6 秒，非常像"死机"。所以只在用户主动点按钮时才跑，
     并且先把 loading 写进 DOM、把按钮置灰，再发命令。
   结果会存一份 CE.ls 缓存，下次进页面先显示上次的，点按钮才重跑。
   ===================================================================== */
(function () {
  "use strict";

  /* 最近一次成功的结果（内存里也留一份，复制时不用再去读缓存） */
  var cur = null;
  /* 正在诊断的标志：防止用户连点两下把两条 curl 排进队列 */
  var running = false;

  var EMPTY_TEXT = "还没诊断过，点上面的按钮开始";
  var UNAVAIL_TEXT = "诊断脚本不可用，请先完成部署或重新安装本版本";

  function pad2(n) { return n < 10 ? "0" + n : "" + n; }

  /* 报告里要写成完整日期时间，光有时分容易分不清是哪天的结果 */
  function fmtFull(ts) {
    ts = Number(ts) || 0;
    if (!ts) return "—";
    var d = new Date(ts < 1e12 ? ts * 1000 : ts);
    return d.getFullYear() + "-" + pad2(d.getMonth() + 1) + "-" + pad2(d.getDate()) +
      " " + pad2(d.getHours()) + ":" + pad2(d.getMinutes());
  }

  /* 结果条目清洗：脚本/缓存都不可信，逐条校验类型，脏条目直接丢掉。
     返回 null 表示"根本没有可用数据"，交给上层渲染空态。 */
  function normItems(arr) {
    if (!arr || typeof arr !== "object" || !(arr instanceof Array) || arr.length === 0) {
      return null;
    }
    var out = [], i, it, name, ok;
    for (i = 0; i < arr.length; i++) {
      it = arr[i];
      if (!it || typeof it !== "object") continue;
      name = it.name == null ? "" : String(it.name);
      if (!name) continue;
      /* extras.sh 是手拼 JSON 的，ok 既可能是 true 也可能被写成 1 / "true" */
      ok = it.ok === true || it.ok === 1 || it.ok === "true" || it.ok === "1";
      out.push({
        name: name,
        ok: ok,
        detail: it.detail == null ? "" : String(it.detail)
      });
    }
    return out.length ? out : null;
  }

  /* 读缓存：CE.ls 解析失败时会原样返回字符串，所以先判类型再判字段 */
  function readCache() {
    var raw = null;
    try { raw = CE.ls("netdiag.last"); } catch (e) { return null; }
    if (!raw || typeof raw !== "object") return null;
    if (raw instanceof Array) return null;
    var ts = Number(raw.ts);
    if (!ts || ts <= 0) return null;
    var items = normItems(raw.items);
    if (!items) return null;
    return { ts: ts, items: items };
  }

  function rowsHTML(items) {
    var out = "", i, it, okc;
    for (i = 0; i < items.length; i++) {
      it = items[i];
      okc = it.ok ? "ok" : "bad";
      out += '<div class="ce-row">' +
        '<span class="ce-dot ' + okc + '"></span>' +
        '<div class="ce-rt"><b>' + CE.esc(it.name) + '</b>' +
        '<span>' + CE.esc(it.detail) + '</span></div>' +
        '<div class="ce-rv ' + (it.ok ? "ce-ok" : "ce-bad") + '">' +
        (it.ok ? "通" : "断") + '</div>' +
        '</div>';
    }
    return out;
  }

  /* 顶部结论行：全通给一句安心话，有失败就把失败项的名字串起来，一眼看到卡在哪步 */
  function verdictHTML(items) {
    var bad = [], i;
    for (i = 0; i < items.length; i++) {
      if (!items[i].ok) bad.push(items[i].name);
    }
    if (!bad.length) {
      return '<div class="ce-hint ce-ok">' + items.length + ' 项全通，网络没问题</div>';
    }
    return '<div class="ce-hint ce-bad">' + bad.length + ' 项不通：' + CE.esc(bad.join("、")) + '</div>';
  }

  /* isLast=true 表示这是从缓存还原的旧结果，把"上次诊断"标出来免得用户误当成本次 */
  function resultHTML(ts, items, isLast) {
    var head = isLast ? "上次诊断：" : "诊断完成：";
    return verdictHTML(items) +
      '<div class="ce-hint">' + head + CE.esc(CE.clock(ts)) + '</div>' +
      rowsHTML(items) +
      '<div class="btns">' +
        '<button class="small" data-ce-act="copy">复制报告</button>' +
        '<button class="small" data-ce-act="recheck">重新诊断</button>' +
      '</div>' +
      /* 复制失败时把纯文本摊在这里，让用户长按手动取 */
      '<div id="ceNdPre"></div>';
  }

  function buildBody() {
    var c = readCache();
    if (c) {
      cur = c;
      return resultHTML(c.ts, c.items, true);
    }
    return '<div class="ce-empty">' + EMPTY_TEXT + '</div>';
  }

  function setBody(root, html) {
    var box = root.querySelector("#ceNdBody");
    if (box) box.innerHTML = html;
  }

  function run(root) {
    if (running) return;
    var body = root.querySelector("#ceNdBody");
    if (!body) return;
    running = true;

    var btn = root.querySelector('[data-ce-act="run"]');
    if (btn) {
      btn.disabled = true;
      btn.textContent = "诊断中…";
    }
    /* 先画 loading 再执行：CE.toolJson 只让出 40ms，够渲染但不能省掉这一步 */
    body.innerHTML = '<div class="ce-load"><i></i> 正在诊断，最多 20 秒…</div>';

    var done = false;
    function finish(data, errText) {
      if (done) return;
      done = true;
      running = false;
      var b = root.querySelector("#ceNdBody");
      var bt = root.querySelector('[data-ce-act="run"]');
      if (bt) {
        bt.disabled = false;
        bt.textContent = "开始诊断";
      }
      if (!b) return;
      if (errText) {
        b.innerHTML = '<div class="ce-empty">' + CE.esc(errText) + '</div>';
        return;
      }
      /* data 为 null：extras.sh 不在了 / Android.exec 不可用 / JSON 解析失败 */
      if (!data || typeof data !== "object") {
        b.innerHTML = '<div class="ce-empty">' + UNAVAIL_TEXT + '</div>';
        return;
      }
      var items = normItems(data.items);
      if (!items) {
        b.innerHTML = '<div class="ce-empty">' + UNAVAIL_TEXT + '</div>';
        return;
      }
      var ts = Number(data.ts) || Math.floor(new Date().getTime() / 1000);
      var payload = { ts: ts, items: items };
      cur = payload;
      try { CE.ls("netdiag.last", payload); } catch (e) { /* 缓存写失败不影响展示 */ }
      b.innerHTML = resultHTML(ts, items, false);
    }

    try {
      CE.toolJson("netdiag", function (data) {
        try { finish(data, ""); } catch (e) { finish(null, "渲染诊断结果失败：" + e.message); }
      });
    } catch (e) {
      /* 连让出帧的机会都没有（极端情况），也别把按钮永远锁死 */
      finish(null, "诊断调用失败：" + e.message);
    }
  }

  function copyReport(root) {
    var d = cur;
    if (!d) {
      try { d = readCache(); } catch (e) { d = null; }
    }
    if (!d) {
      CE.toast("还没有诊断结果");
      return;
    }
    var lines = [], i, it;
    for (i = 0; i < d.items.length; i++) {
      it = d.items[i];
      lines.push((it.ok ? "[通]" : "[断]") + " " + it.name + " — " + it.detail);
    }
    var text = "网络诊断报告 " + fmtFull(d.ts) + "\n" + lines.join("\n");

    var okc = false;
    try { okc = CE.copy(text); } catch (e) { okc = false; }
    if (okc) {
      CE.toast("诊断报告已复制");
      /* 上次复制失败留下的展开始文本要收掉，不然面板会越积越长 */
      var fb = root.querySelector("#ceNdPre");
      if (fb) fb.innerHTML = "";
      return;
    }
    /* 降级：桥里没有 copy（老版本），把文本铺出来让用户长按手动复制 */
    var box = root.querySelector("#ceNdPre");
    if (box) box.innerHTML = '<div class="ce-pre">' + CE.esc(text) + '</div>';
    CE.toast("复制失败，已展开报告，可长按选择");
  }

  CE.reg({
    id: "netdiag",
    tab: "more",
    order: 20,
    title: "网络诊断",

    /* html() 只渲染缓存结果或空态，绝不自动跑诊断（curl 会卡 6 秒） */
    html: function () {
      var body = "";
      try {
        body = buildBody();
      } catch (e) {
        body = '<div class="ce-empty">' + EMPTY_TEXT + '</div>';
      }
      return '<div class="card">' +
        '<h2>网络诊断</h2>' +
        '<div class="hint">点一次把本机端口、DNS、外网全查一遍，卡在哪一步一眼看到</div>' +
        '<div class="btns">' +
          '<button class="small primary" data-ce-act="run">开始诊断</button>' +
        '</div>' +
        '<div id="ceNdBody">' + body + '</div>' +
      '</div>';
    },

    /* mount 里同样什么都不做：进页面就卡住是这次明确要避免的体验 */
    mount: function () {
      running = false;
    },

    on: function (act, arg, root) {
      if (act === "run" || act === "recheck") { run(root); return; }
      if (act === "copy") { copyReport(root); return; }
    }
  });
})();
