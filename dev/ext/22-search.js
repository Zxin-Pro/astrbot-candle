(function () {
  "use strict";

  /* ------------------------------------------------------------------
     功能搜索（more）
     ------------------------------------------------------------------
     为什么要有：设置页 20 多张卡片 + 更多页 10 多张，想找「端口设置」得翻半天。
     索引不手写：直接扫 DOM 里所有 [data-act] 元素，取「离它最近的标题」（.lkt / h2 /
     .ce-rt b），再补一张同义词表。这样以后主脚本加了新按钮，搜索自动覆盖，
     不需要再来改这个文件 —— 手写索引表迟早会跟界面脱节。
     ------------------------------------------------------------------ */

  var TAB_NAME = {
    home: "主页", bots: "机器人", logs: "日志", more: "更多", settings: "设置"
  };

  /* 同义词：用户不会记得按钮叫「组件重装」，他想搜「重装」「修复」「装一下」 */
  var SYN = [
    ["重启", "restartnow gorestart askrestart svc"],
    ["日志", "showlogs readLog uploadlog copylog clearlog slim reload"],
    ["备份", "bkstart bkexport bkrestore"],
    ["恢复", "bkrestore bkrestoreask"],
    ["更新", "checkupdate upddl updnotes"],
    ["二维码", "showqr qrrefresh qrreload qrshare"],
    ["扫码", "showqr qrrefresh"],
    ["端口", "portssave portsauto"],
    ["代理", "saveproxy testproxy clearproxy"],
    ["流量", "statdetail"],
    ["网络", "lantoggle lan"],
    ["省电", "battery powgo powack"],
    ["守护", "guardset svc svcallon"],
    ["下线", "stopnow askstop"],
    ["卸载", "rmall delbot delask"],
    ["删实例", "delbot delask"],
    ["主题", "theme pickbg clearbg bgpreset glass accent"],
    ["背景", "pickbg clearbg bgpreset"],
    ["模型", "astrconfig astrsave astrfetch"],
    ["人格", "astrconfig"],
    ["统计", "statstoggle statdetail statsreset"],
    ["清理", "stgclean stgrefresh"],
    ["存储", "stgrefresh stats"],
    ["崩溃", "crashopen crashclear"],
    ["自检", "doctor"],
    ["诊断", "doctor"],
    ["上传", "uploadlog uploadlogs"],
    ["终端", "terminal"],
    ["权限", "notiask"],
    ["通知", "notiask"],
    ["群", "openqq"],
    ["官网", "openurl copyurl"],
    ["重装", "reinstall"],
    ["部署", "deploy resetdeploy stopdeploy fixdpkg"],
    ["修复", "fixdpkg doctor reinstall"],
    ["密码", "alcopypass copyuser"],
    ["令牌", "copytoken copytok"]
  ];

  var IDX = null;

  function closestTitle(node) {
    var n = node, depth = 0;
    while (n && depth < 5) {
      if (n.classList) {
        if (n.classList.contains("lkrow")) {
          var t = n.querySelector(".lkt");
          if (t && t.textContent.trim()) return t.textContent.trim();
        }
        if (n.tagName === "DIV" && n.classList.contains("card")) {
          var h = n.querySelector("h2");
          if (h && h.textContent.trim()) return h.textContent.trim();
        }
      }
      n = n.parentNode; depth++;
    }
    return "";
  }

  function build() {
    var out = [], seen = {};
    var secs = document.querySelectorAll("section.tab");
    for (var i = 0; i < secs.length; i++) {
      var sec = secs[i];
      var tab = (sec.id || "").replace(/^tab-/, "");
      if (!TAB_NAME[tab]) continue;
      var nodes = sec.querySelectorAll("[data-act]");
      for (var k = 0; k < nodes.length; k++) {
        var b = nodes[k];
        var act = b.getAttribute("data-act") || "";
        if (!act) continue;
        var own = (b.textContent || "").replace(/\s+/g, " ").trim();
        /* 「复制」「打开」这种按钮自身文字没有辨识度，改用所在行的标题 */
        if (!own || own.length <= 3) {
          var ct = closestTitle(b);
          if (ct) own = ct + " · " + own;
        }
        var title = closestTitle(b) || own || act;
        var key = tab + "|" + act + "|" + title;
        if (seen[key]) continue;
        seen[key] = 1;
        out.push({ tab: tab, act: act, text: (title + " " + own + " " + act).toLowerCase(), title: title, own: own, node: b });
      }
    }
    /* 同名按钮留第一个就够了：设置页里「复制」有十几个，全列出来反而没法选 */
    return out;
  }

  function syn(act) {
    for (var i = 0; i < SYN.length; i++) {
      if (SYN[i][1].indexOf(act) >= 0) return SYN[i][0];
    }
    return "";
  }

  function query(q, limit) {
    if (!IDX) IDX = build();
    q = String(q || "").trim().toLowerCase();
    if (q.length < 1) return [];
    var hits = [], i, e, score;
    for (i = 0; i < IDX.length; i++) {
      e = IDX[i];
      score = 0;
      if (e.act.toLowerCase() === q) score = 100;
      else if (e.title.toLowerCase().indexOf(q) === 0) score = 80;
      else if (e.title.toLowerCase().indexOf(q) >= 0) score = 60;
      else if (e.text.indexOf(q) >= 0) score = 40;
      else {
        var s = syn(e.act);
        if (s && s.indexOf(q) >= 0) score = 30;
      }
      if (score > 0) hits.push({ e: e, s: score });
    }
    hits.sort(function (a, b) { return b.s - a.s; });
    var out = [];
    for (i = 0; i < hits.length && out.length < (limit || 12); i++) {
      /* 同一张卡片只留一条，否则「备份与恢复」会占满整屏 */
      var dup = false;
      for (var j = 0; j < out.length; j++) {
        if (out[j].o.title === hits[i].e.title && out[j].o.tab === hits[i].e.tab) { dup = true; break; }
      }
      if (!dup) out.push({ o: hits[i].e, score: hits[i].s });
    }
    return out;
  }

  function go(item) {
    try {
      if (item.tab !== "settings" && window.showTab) window.showTab(item.tab);
      else if (window.showTab) window.showTab(item.tab);
    } catch (e) {}
    setTimeout(function () {
      var n = item.node;
      if (!n || !n.scrollIntoView) return;
      try { n.scrollIntoView({ block: "center", behavior: "smooth" }); } catch (e2) { try { n.scrollIntoView(); } catch (e3) {} }
      /* 闪一下让人知道落在哪：只加 class，不动样式表，1.2 秒后自己消失 */
      var old = n.style.boxShadow;
      n.style.boxShadow = "0 0 0 2px #2563eb";
      n.style.borderRadius = "8px";
      setTimeout(function () { n.style.boxShadow = old || ""; }, 1200);
    }, 260);
  }

  function paintList(root, q) {
    var box = root.querySelector("#ceSearchList");
    if (!box) return;
    if (!q) {
      box.innerHTML = "";
      return;
    }
    var hits = query(q, 12);
    if (!hits.length) {
      box.innerHTML = '<div class="ce-empty">没有找到「' + CE.esc(q) + '」相关的功能</div>';
      return;
    }
    var out = "";
    for (var i = 0; i < hits.length; i++) {
      var e = hits[i].o;
      out += '<div class="ce-row"><div class="ce-rt"><b>' + CE.esc(e.title) +
        '</b><span>' + CE.esc(TAB_NAME[e.tab] || e.tab) + " · " + CE.esc(e.own || e.act) + "</span></div>" +
        '<button class="small" data-ce-act="go" data-ce-arg="' + i + '">去</button></div>';
    }
    box.innerHTML = out;
    box.__hits = hits;
  }

  CE.reg({
    id: "search",
    tab: "more",
    order: 10,
    title: "功能搜索",
    html: function () {
      return '<div class="card"><h2>功能搜索</h2>' +
        '<div class="hint" style="margin-top:0">输入关键字直接跳到对应设置，省得一层层翻。</div>' +
        '<div class="field" style="margin-top:8px"><input id="ceSearchIn" type="search" ' +
        'placeholder="例如：端口 / 备份 / 重启 / 扫码" autocomplete="off"></div>' +
        '<div id="ceSearchList"></div></div>';
    },
    mount: function (root) {
      var inp = root.querySelector("#ceSearchIn");
      if (!inp) return;
      inp.addEventListener("input", function () {
        paintList(root, inp.value);
      }, false);
    },
    tick: function (root) {
      /* 首次进「更多」页时这页的 DOM 已经在了，索引可以晚一点建；
         主脚本可能重绘实例卡（data-act 节点被换掉），所以每次 tick 重建一次，
         12 张卡几百个节点，重建开销可以忽略。 */
      IDX = null;
    },
    on: function (act, arg, root) {
      if (act === "go") {
        var box = root.querySelector("#ceSearchList");
        var hits = box && box.__hits;
        var i = parseInt(arg, 10);
        if (hits && hits[i]) go(hits[i].o);
      }
    }
  });
})();
