/* =====================================================================
   10-sysmon.js — 主页「资源监控」卡（id=sysmon / tab=home / order=30）
   ---------------------------------------------------------------------
   数据只有两条只读来源，都是 extras.sh 的子命令，单位统一是 KB：
     · sysinfo —— 读 /proc/loadavg、/proc/meminfo、df，几十毫秒，跟着
       内核 8 秒一轮的 tick 全量刷新；
     · procs   —— 扫一圈 /proc/<pid>/status 再排序，慢一档，所以用模块内的
       计数器压到每 3 次 tick 一次；用户展开「进程 TOP」时再补刷一次，
       免得边看边开时读到的是 8 秒前的旧表。
   拿不到数据（null 或 ok !== true）时整块退化成一句空态文案：
   tick 是内核统一循环里调的，模块一抛异常就会把同页其它模块一起打断，
   所以这里每个入口都包了 try/catch。
   ===================================================================== */
(function () {
  "use strict";

  var PROC_EVERY = 3;      /* procs 的刷新节流：每 N 次 tick 才跑一次 */

  var sys = null;          /* 最近一次 sysinfo 结果，null 表示没拿到 */
  var prc = null;          /* 最近一次 procs 结果 */
  var tried = false;       /* sysinfo 回过一次没有（用来区分「读取中」和「读失败」） */
  var prcDone = false;     /* procs 回过一次没有 */
  var lastTs = 0;          /* 最近一次取数时刻（秒），失败时标题右侧也要有东西显示 */
  var tickN = 0;           /* tick 计数器，给 procs 节流用 */
  var busySys = false;     /* CE.exec 是「让出一帧再执行」，这个窗口里别重复发命令 */
  var busyProcs = false;
  var open = false;        /* 进程 TOP 展开态，持久化在 CE.ls("sysmon.open") */

  /* ---------------- 格式化小工具 ---------------- */

  /* 剩余量按需求写成「x.x GB」；不足 100 MB 时退回 CE.kb，
     否则快满的存储会显示成没有信息量的 "0.0 GB" */
  function freeGb(k) {
    k = Number(k) || 0;
    if (k < 102400) return CE.kb(k);
    return (k / 1048576).toFixed(1) + " GB";
  }

  /* .ce-pre 是等宽字体，靠空格对齐比多列布局省事，窄屏也不会挤成两行 */
  function padRight(s, n) {
    s = String(s == null ? "" : s);
    while (s.length < n) s += " ";
    return s;
  }

  /* 阈值是产品定死的，不走主题：>92% 判危，>80% 判警 */
  function barCls(p) {
    if (p > 92) return "bad";
    if (p > 80) return "warn";
    return "";
  }

  /* margin-top 归零：三条条子要和上面三格指标严格同列对齐，
     统一由外层网格的 gap 控制间距，否则每条的基线会错开 6px */
  function bar(p) {
    p = Math.max(0, Math.min(100, Math.round(Number(p) || 0)));
    return '<div class="ce-bar" style="margin-top:0"><i class="' + barCls(p) + '" style="width:' +
      p + '%"></i></div>';
  }

  /* 三格指标 + 三条进度条都走同一套 3 等分网格，条子就正好落在各自那格下面 */
  var COLS = "grid-template-columns:repeat(3,1fr)";

  /* diskTotal 读不到时按需求留一条 0 宽度的空条，第三列不断行 */
  function zeroBar() {
    return '<div class="ce-bar" style="margin-top:0"><i style="width:0%"></i></div>';
  }

  /* 一格指标：<b> 是值（.ce-cell b 已内置 tabular-nums，不用再定宽） */
  function cell(val, label) {
    return '<div class="ce-cell"><b>' + CE.esc(val) + '</b><span>' + CE.esc(label) + '</span></div>';
  }

  /* procs 的 list 可能缺失 / 为空 / ok=false，统一收敛成数组 */
  function plist(d) {
    if (!d || d.ok !== true || !d.list || !d.list.length) return [];
    return d.list;
  }

  /* ---------------- 页面片段 ---------------- */

  function headHtml() {
    /* 主样式表里 .card h2 在有的主题是 flex、有的主题是 block，
       所以把排版写死在 h2 上（只写布局，不写字面颜色），
       时间用 margin-left:auto 顶到右侧；浅色主题的 h2::before 装饰条仍然在最前。 */
    var ts = CE.clock(sys && sys.ts ? sys.ts : lastTs);
    return '<h2 style="display:flex;align-items:baseline;gap:8px">资源监控' +
      '<span class="ce-mono" data-ce-ts style="margin-left:auto">' + CE.esc(ts) + '</span></h2>';
  }

  function procsHtml() {
    var list = plist(prc), n = list.length;
    var row = '<div class="ce-row" data-ce-act="toggle-procs" role="button" aria-expanded="' +
      (open ? "true" : "false") + '">' +
      '<div class="ce-rt"><b>进程 TOP</b><span>点这一行展开 / 收起</span></div>' +
      '<div class="ce-rv">' + (n ? n + " 个 " : "") + (open ? "▴" : "▾") + '</div></div>';
    if (!open) return row;
    /* 展开时才拼这 6 行：默认折叠，别让内存在每次 tick 里做无用渲染 */
    var lines = [], i, it;
    for (i = 0; i < n && i < 6; i++) {
      it = list[i] || {};
      lines.push(CE.esc(padRight(it.name || "?", 14) + CE.kb(it.rssKB)));
    }
    return row + '<div class="ce-pre">' + (lines.length ? lines.join("\n") : "—") + '</div>';
  }

  function hintHtml() {
    if (!prcDone) return '<div class="ce-hint">进程表读取中…</div>';
    if (!plist(prc).length) return '<div class="ce-hint">沙盒没在跑，进程列表为空</div>';
    return '<div class="ce-hint">只统计沙盒相关进程（python / napcat / qq / proot），' +
      '每 ' + PROC_EVERY + ' 轮刷新更新一次</div>';
  }

  function footHtml() {
    return '<div class="btns" style="margin-top:10px">' +
      '<button class="small" data-ce-act="refresh">刷新</button></div>';
  }

  function bodyHtml() {
    if (!sys || sys.ok !== true) {
      /* 首次请求还没回来：先给加载态，别让卡片闪一下「读取失败」 */
      if (!tried) return '<div class="ce-load"><i></i>读取资源数据…</div>';
      /* 多半是 extras.sh 还没部署，文案要顺带告诉用户下一步做什么 */
      return '<div class="ce-empty">资源数据读取失败：需要先完成部署' +
        '（脚本 extras.sh 还没就位）</div>' + footHtml();
    }

    var cores = Math.round(Number(sys.cores) || 0) || 1;
    var load1 = Number(sys.load1) || 0;
    var memTotal = Number(sys.memTotal) || 0;
    var memAvail = Number(sys.memAvail) || 0;
    var dTotal = Number(sys.diskTotal) || 0;
    var dUsed = Number(sys.diskUsed) || 0;
    var dFree = Number(sys.diskFree) || 0;

    var cpuP = CE.pct(load1, cores);                                  /* load1 相对核数 */
    var memP = memTotal > 0 ? CE.pct(memTotal - memAvail, memTotal) : 0;
    /* 磁盘占用按需求用 used/(used+free)：部分机型 df 的 total 含预留块，会被算低 */
    var diskP = (dUsed + dFree) > 0 ? CE.pct(dUsed, dUsed + dFree) : 0;
    /* diskTotal 为 0 说明 df 没读到（或分区不可见），这时显示「—」、进度条宽度 0 */
    var diskOk = dTotal > 0;

    return '<div class="ce-grid" style="' + COLS + '">' +
        cell(load1.toFixed(2), "CPU 负载 · " + cores + " 核") +
        cell(memTotal > 0 ? memP + "%" : "—",
          memTotal > 0 ? "内存 · 剩余 " + freeGb(memAvail) : "内存 · 无数据") +
        cell(diskOk ? diskP + "%" : "—",
          diskOk ? "存储 · 剩余 " + freeGb(dFree) : "存储 · 无数据") +
      '</div>' +
      '<div class="ce-bars" style="display:grid;' + COLS + ';gap:8px;margin-top:7px">' +
        bar(cpuP) + bar(memP) + (diskOk ? bar(diskP) : zeroBar()) +
      '</div>' +
      procsHtml() + hintHtml() + footHtml();
  }

  /* 只重画 #ceSysBody，标题右侧的时间单独更新：
     整卡 re-render 会把展开态、滚动位置一起冲掉，没必要。 */
  function paint(root) {
    try {
      if (!root || !root.querySelector) return;
      var tsEl = root.querySelector("[data-ce-ts]");
      if (tsEl) tsEl.textContent = CE.clock(sys && sys.ts ? sys.ts : lastTs);
      var body = root.querySelector("#ceSysBody");
      if (body) body.innerHTML = bodyHtml();
    } catch (e) {
      /* 渲染失败也不能把内核的 tick 循环带挂 */
    }
  }

  /* ---------------- 取数 ---------------- */

  function loadSys(root) {
    if (busySys) return;
    busySys = true;
    try {
      CE.toolJson("sysinfo", function (d) {
        busySys = false;
        tried = true;
        lastTs = Math.floor(Date.now() / 1000);
        sys = (d && d.ok === true) ? d : null;
        paint(root);
      });
    } catch (e) {
      busySys = false;
      tried = true;
      sys = null;
      paint(root);
    }
  }

  function loadProcs(root) {
    if (busyProcs) return;
    busyProcs = true;
    try {
      CE.toolJson("procs", function (d) {
        busyProcs = false;
        prcDone = true;
        prc = (d && d.ok === true) ? d : null;
        paint(root);
      });
    } catch (e) {
      busyProcs = false;
      prcDone = true;
      prc = null;
      paint(root);
    }
  }

  /* ---------------- 注册 ---------------- */

  CE.reg({
    id: "sysmon",
    tab: "home",
    order: 30,
    title: "资源监控",

    html: function () {
      /* 骨架先按「读取中」出，mount 里立刻发请求，正常情况下 100ms 内出数 */
      return '<div class="card">' + headHtml() +
        '<div id="ceSysBody">' + bodyHtml() + '</div></div>';
    },

    mount: function (root) {
      try { open = CE.ls("sysmon.open") === true; } catch (e) { open = false; }
      /* 挂载即拉一次，不等内核那 8 秒；随后 tick 里的 busy 标记会拦掉重复请求 */
      loadSys(root);
      loadProcs(root);
    },

    tick: function (root) {
      loadSys(root);
      tickN++;
      /* procs 慢一档：每 PROC_EVERY 轮一次；展开时跟着每轮刷，用户看到的就是新数 */
      if (open || tickN % PROC_EVERY === 0) loadProcs(root);
    },

    on: function (act, arg, root) {
      if (act === "refresh") {
        loadSys(root);
        loadProcs(root);
        return;
      }
      if (act === "toggle-procs") {
        open = !open;
        try { CE.ls("sysmon.open", open); } catch (e) {}
        paint(root);                 /* 先切展开态，别等命令回来才动 */
        if (open) loadProcs(root);   /* 展开时补一次最新数据 */
      }
    }
  });
})();
