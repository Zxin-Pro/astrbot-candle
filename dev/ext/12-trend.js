(function () {
  "use strict";

  /* ------------------------------------------------------------------
     7 日趋势（home）
     ------------------------------------------------------------------
     为什么要有：主页只有「今日」四个数字，用户问得最多的是「这两天是不是变差了」。
     桥里没有历史接口（TodayStats 只存当天快照），所以历史只能由前端自己攒：
     每次 tick 把 todayStats() 落一份到 localStorage，按天去重，最多留 30 天。
     新装的第一天图上只有一个点，这是正常的 —— 文案里要说清楚，别让用户以为坏了。
     ------------------------------------------------------------------ */

  var KEY = "trend.days";
  var MAX = 30;

  function today() {
    var d = new Date();
    function p(n) { return n < 10 ? "0" + n : "" + n; }
    return d.getFullYear() + "-" + p(d.getMonth() + 1) + "-" + p(d.getDate());
  }

  function load() {
    var o = CE.ls(KEY);
    if (!o || typeof o !== "object") return {};
    return o;
  }

  function save(o) {
    /* 只保留最近 MAX 天：按 key 排序后砍掉多余的，免得 localStorage 越攒越大 */
    var ks = [];
    for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ks.push(k);
    ks.sort();
    while (ks.length > MAX) { delete o[ks.shift()]; }
    CE.ls(KEY, o);
  }

  /* 今天这一格：只在数值有意义时更新，避免沙盒没跑时把 0 覆盖上去
     （离线期间 todayStats 依旧返回当天累计值，所以这里其实一直是真值；
      真要防的是「桥还没就绪返回空对象」—— 那种情况 optNum 会给 -1） */
  function absorb() {
    var raw = CE.call("todayStats");
    var j = CE.json(raw, null);
    if (!j || j.ok !== true) return load();
    var o = load();
    var d = j.day || today();
    var cell = o[d] || {};
    if (typeof j.msgs === "number") cell.msgs = j.msgs;
    if (typeof j.errors === "number") cell.errors = j.errors;
    if (typeof j.onlineSec === "number") cell.onlineSec = j.onlineSec;
    cell.ts = Date.now();
    o[d] = cell;
    save(o);
    return o;
  }

  function last7(o) {
    var out = [];
    var now = new Date();
    for (var i = 6; i >= 0; i--) {
      var d = new Date(now.getTime() - i * 86400000);
      function p(n) { return n < 10 ? "0" + n : "" + n; }
      var k = d.getFullYear() + "-" + p(d.getMonth() + 1) + "-" + p(d.getDate());
      out.push({ key: k, label: (d.getMonth() + 1) + "/" + d.getDate(), v: o[k] || null });
    }
    return out;
  }

  /* 迷你折线：两条线（消息 / 异常）+ 面积点。手写 SVG，不引任何图表库 —— 
     包里没有 CDN，也不该为了一个小图去加 30 KB 的依赖。 */
  function chart(rows) {
    var W = 320, H = 96, PL = 6, PR = 6, PT = 10, PB = 22;
    var iw = W - PL - PR, ih = H - PT - PB;
    var maxM = 1, maxE = 1, i, v;
    for (i = 0; i < rows.length; i++) {
      v = rows[i].v;
      if (!v) continue;
      if ((v.msgs || 0) > maxM) maxM = v.msgs;
      if ((v.errors || 0) > maxE) maxE = v.errors;
    }
    function x(i) { return PL + (rows.length <= 1 ? iw / 2 : iw * i / (rows.length - 1)); }
    function yM(n) { return PT + ih - ih * (Math.max(0, n) / maxM); }

    var s = '<svg class="ce-svg" viewBox="0 0 ' + W + " " + H + '" preserveAspectRatio="none" role="img" aria-label="最近 7 天消息趋势">';
    /* 三条横网格：基线 / 中值 / 顶 */
    for (i = 0; i <= 2; i++) {
      var gy = PT + ih * i / 2;
      s += '<line class="grid" x1="' + PL + '" y1="' + gy.toFixed(1) + '" x2="' + (W - PR) + '" y2="' + gy.toFixed(1) + '"/>';
    }
    /* 消息折线 */
    var dpath = "", started = false, pts = [];
    for (i = 0; i < rows.length; i++) {
      v = rows[i].v;
      if (!v) { started = false; continue; }
      var px = x(i), py = yM(v.msgs || 0);
      dpath += (started ? " L" : " M") + px.toFixed(1) + " " + py.toFixed(1);
      started = true;
      pts.push([px, py, v.msgs || 0]);
    }
    if (dpath) s += '<path class="ln" d="' + dpath + '"/>';
    for (i = 0; i < pts.length; i++) {
      s += '<circle class="pt" cx="' + pts[i][0].toFixed(1) + '" cy="' + pts[i][1].toFixed(1) + '" r="2.6"/>';
    }
    /* 异常数：用短竖条压在基线上，不跟消息共用 y 轴（量级差太多，共用会看不见） */
    for (i = 0; i < rows.length; i++) {
      v = rows[i].v;
      if (!v || !v.errors) continue;
      var bh = Math.min(ih * 0.5, 4 + (v.errors / maxE) * ih * 0.4);
      var bx = x(i) - 3;
      s += '<rect x="' + bx.toFixed(1) + '" y="' + (PT + ih - bh).toFixed(1) +
        '" width="6" height="' + bh.toFixed(1) + '" rx="2" fill="#dc2626" opacity=".55"/>';
    }
    /* x 轴标签：7 个全写会挤，隔一个写一个；最后一天一定写 */
    for (i = 0; i < rows.length; i++) {
      if (i % 2 === 1 && i !== rows.length - 1) continue;
      s += '<text x="' + x(i).toFixed(1) + '" y="' + (H - 6) + '" text-anchor="middle">' + CE.esc(rows[i].label) + "</text>";
    }
    s += "</svg>";

    /* 图例 + 峰值 */
    var peak = 0, peakDay = "";
    for (i = 0; i < rows.length; i++) {
      v = rows[i].v;
      if (v && (v.msgs || 0) >= peak) { peak = v.msgs || 0; peakDay = rows[i].label; }
    }
    s += '<div class="ce-tags"><span class="ce-tag">消息</span>' +
      '<span class="ce-tag" style="background:rgba(220,38,38,.12);color:#dc2626">异常</span>' +
      (peak > 0 ? '<span class="ce-tag">峰值 ' + CE.esc(String(peak)) + " · " + CE.esc(peakDay) + "</span>" : "") +
      "</div>";
    return s;
  }

  function summary(rows) {
    var n = 0, msgs = 0, errs = 0, online = 0, i, v;
    for (i = 0; i < rows.length; i++) {
      v = rows[i].v;
      if (!v) continue;
      n++;
      msgs += v.msgs || 0;
      errs += v.errors || 0;
      online += v.onlineSec || 0;
    }
    if (!n) return '<div class="ce-empty">还没有历史数据。每天打开一次应用就会自动记一天，攒够 7 天这里就是完整的一张趋势图。</div>';
    var avg = Math.round(msgs / n);
    return '<div class="ce-grid">' +
      '<div class="ce-cell"><b>' + CE.esc(String(n)) + ' 天</b><span>有数据的天数</span></div>' +
      '<div class="ce-cell"><b>' + CE.esc(String(msgs)) + '</b><span>累计消息</span></div>' +
      '<div class="ce-cell"><b>' + CE.esc(String(avg)) + '</b><span>日均消息</span></div>' +
      '<div class="ce-cell"><b class="' + (errs > 0 ? "ce-warn" : "") + '">' + CE.esc(String(errs)) + '</b><span>累计异常</span></div>' +
      '</div>' +
      '<div class="ce-hint">累计在线 ' + CE.esc(CE.dur(online)) + '</div>';
  }

  function paint(root) {
    var body = root.querySelector("#ceTrendBody");
    if (!body) return;
    var o = absorb();
    var rows = last7(o);
    body.innerHTML = summary(rows) + (rows.some(function (r) { return !!r.v; }) ? chart(rows) : "");
  }

  CE.reg({
    id: "trend",
    tab: "home",
    order: 34,
    title: "7 日趋势",
    html: function () {
      return '<div class="card"><h2>7 日趋势</h2>' +
        '<div class="hint" style="margin-top:0">每天打开应用会自动记一条，只存在本机。</div>' +
        '<div id="ceTrendBody"></div>' +
        '<div class="btns" style="margin-top:8px">' +
        '<button class="small" data-ce-act="clear">清空历史</button></div></div>';
    },
    mount: function (root) { paint(root); },
    tick: function (root) { paint(root); },
    on: function (act, arg, root) {
      if (act === "clear") {
        if (CE.ls("trend.cleared") === 1) {
          CE.ls(KEY, {});
          CE.ls("trend.cleared", 0);
          CE.toast("历史已清空");
          paint(root);
        } else {
          /* 二次确认：WebView 里 confirm() 不可靠，改成"再点一次" */
          CE.ls("trend.cleared", 1);
          CE.toast("再点一次就清空（3 秒内有效）");
          setTimeout(function () { CE.ls("trend.cleared", 0); }, 3000);
        }
      }
    }
  });
})();
