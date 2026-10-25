(function () {
  "use strict";

  /* ------------------------------------------------------------------
     日志分析条（logs，置顶）
     ------------------------------------------------------------------
     为什么另开一块而不是改现有 <pre>：主脚本每 4 秒用 setPre() 整体覆盖
     #logNap / #logAstr，任何"就地过滤"都会在下一秒被冲掉（还会闪）。
     所以这里只做加法：自己用 Android.readLog() 取一份，过滤后画在自己的面板里，
     原来的实时日志一个字节都不动。用户切页离开时这块也在，互不干扰。
     ------------------------------------------------------------------ */

  var MAXLINES = 400;

  var LEVELS = [
    { k: "all", n: "全部" },
    { k: "err", n: "只看错误" },
    { k: "warn", n: "只看警告" },
    { k: "msg", n: "消息收发" }
  ];

  function st(k, d) {
    var v = CE.ls("log." + k);
    return v == null ? d : v;
  }

  function napName() {
    /* 主脚本把当前实例的 NapCat 日志名写在 #duoNapName 里（qq-<id>.log 之类）。
       取不到就退回 qq.log —— 老版本只有这一个文件名。 */
    var n = "qq.log";
    try {
      var e = document.getElementById("duoNapName");
      if (e && e.textContent && /\S/.test(e.textContent)) n = e.textContent.trim();
    } catch (e2) {}
    return n;
  }

  function classify(line) {
    var s = line.toLowerCase();
    if (/\b(error|err|traceback|exception|failed|critical|fatal)\b/.test(s)) return "err";
    if (/\b(warn|warning|deprecat)/.test(s)) return "warn";
    /* 「消息收发」没有统一格式，用几个最常见的痕迹兜一下：
       NapCat 打 [msg]/收到/发送，AstrBot 打 event / on_message / reply */
    if (/\[msg\]|收到|发送|\bon_message\b|\[\w+\]:\s*\S+\s*->|\breply\b/.test(line)) return "msg";
    return "info";
  }

  function filter(text, level, kw) {
    var lines = String(text || "").split("\n");
    var out = [], i, k = (kw || "").toLowerCase();
    for (i = 0; i < lines.length; i++) {
      var ln = lines[i];
      if (!ln && out.length === 0) continue;
      if (level !== "all" && classify(ln) !== level) continue;
      if (k && ln.toLowerCase().indexOf(k) < 0) continue;
      out.push(ln);
    }
    if (out.length > MAXLINES) out = out.slice(out.length - MAXLINES);
    return out;
  }

  function stat(text) {
    var lines = String(text || "").split("\n");
    var c = { err: 0, warn: 0, msg: 0, total: 0 };
    for (var i = 0; i < lines.length; i++) {
      if (!lines[i]) continue;
      c.total++;
      var t = classify(lines[i]);
      if (t === "err") c.err++;
      else if (t === "warn") c.warn++;
      else if (t === "msg") c.msg++;
    }
    return c;
  }

  function hl(line, kw) {
    var safe = CE.esc(line);
    if (!kw) return safe;
    /* 高亮只处理 ASCII 关键字大小写不敏感；中文直接整体替换也可以，
       但用正则要转义，所以统一走 split/join（关键字本身不参与正则） */
    var lk = line.toLowerCase(), k = kw.toLowerCase(), out = "", i = 0, p;
    while ((p = lk.indexOf(k, i)) >= 0) {
      out += CE.esc(line.slice(i, p)) + "<mark>" + CE.esc(line.slice(p, p + kw.length)) + "</mark>";
      i = p + kw.length;
    }
    out += CE.esc(line.slice(i));
    return out;
  }

  var NAMES = [
    { id: "nap", label: "NapCat" },
    { id: "astr", label: "AstrBot" }
  ];

  function readOne(id) {
    var name = id === "nap" ? napName() : "astrbot.log";
    if (!CE.has("readLog")) return { name: name, text: "" };
    var t = "";
    try { t = String(CE.call("readLog", name, 600) || ""); } catch (e) { t = ""; }
    return { name: name, text: t };
  }

  function paint(root) {
    var box = root.querySelector("#ceLogBody");
    if (!box) return;
    var level = st("level", "all");
    var kw = String(st("kw", "") || "");
    var src = st("src", "astr");
    var one = readOne(src);
    var c = stat(one.text);
    var rows = filter(one.text, level, kw);

    /* 顶部：计数 + 当前文件 */
    var out = '<div class="ce-tags">' +
      '<span class="ce-tag">' + CE.esc(one.name) + "</span>" +
      '<span class="ce-tag">共 ' + c.total + " 行</span>" +
      '<span class="ce-tag" style="background:rgba(220,38,38,.12);color:#dc2626">错误 ' + c.err + "</span>" +
      '<span class="ce-tag" style="background:rgba(217,119,6,.14);color:#d97706">警告 ' + c.warn + "</span>" +
      '<span class="ce-tag">消息 ' + c.msg + "</span></div>";

    /* 源与级别：两排小按钮 */
    out += '<div class="btns" style="margin-top:8px">';
    for (var i = 0; i < NAMES.length; i++) {
      out += '<button class="small' + (src === NAMES[i].id ? " primary" : "") +
        '" data-ce-act="src" data-ce-arg="' + NAMES[i].id + '">' + NAMES[i].label + "</button>";
    }
    out += "</div><div class=\"btns\" style=\"margin-top:6px\">";
    for (i = 0; i < LEVELS.length; i++) {
      out += '<button class="small' + (level === LEVELS[i].k ? " primary" : "") +
        '" data-ce-act="level" data-ce-arg="' + LEVELS[i].k + '">' + LEVELS[i].n + "</button>";
    }
    out += "</div>";

    /* 关键字 */
    out += '<div class="field" style="margin-top:8px"><input id="ceLogKw" type="search" ' +
      'placeholder="过滤关键字（例如 10001 / timeout / 群）" value="' + CE.esc(kw) + '" autocomplete="off"></div>';

    /* 结果 */
    if (!rows.length) {
      out += '<div class="ce-empty">' + (one.text ? "当前筛选条件下没有匹配的行" : "日志还是空的（组件可能没在跑）") + "</div>";
    } else {
      var body = [];
      for (i = 0; i < rows.length; i++) {
        var cls = classify(rows[i]);
        if (cls === "err") body.push('<span class="ce-bad">' + hl(rows[i], kw) + "</span>");
        else if (cls === "warn") body.push('<span class="ce-warn">' + hl(rows[i], kw) + "</span>");
        else body.push(hl(rows[i], kw));
      }
      out += '<div class="ce-pre" id="ceLogView">' + body.join("\n") + "</div>";
      out += '<div class="ce-hint">显示最近 ' + rows.length + " 行（最多 " + MAXLINES + " 行）</div>";
    }

    out += '<div class="btns" style="margin-top:8px">' +
      '<button class="small" data-ce-act="copy">复制当前结果</button>' +
      '<button class="small" data-ce-act="reload">重新读取</button>' +
      "</div>";

    box.innerHTML = out;

    /* 输入框：重新渲染会把焦点丢掉，这里补回来（只在用户正在输入时） */
    var inp = box.querySelector("#ceLogKw");
    if (inp && root.__kwFocus) {
      inp.focus();
      try { inp.setSelectionRange(inp.value.length, inp.value.length); } catch (e3) {}
    }
  }

  CE.reg({
    id: "logtools",
    tab: "logs",
    pos: "top",
    order: 10,
    title: "日志分析",
    html: function () {
      return '<div class="card"><h2>日志分析</h2>' +
        '<div class="hint" style="margin-top:0">按级别和关键字筛最近 600 行，不打断上面的实时日志。</div>' +
        '<div id="ceLogBody"></div></div>';
    },
    mount: function (root) {
      paint(root);
      /* 输入用防抖：每敲一个字就重读一次日志会卡，300ms 足够 */
      var box = root.querySelector("#ceLogBody");
      if (!box) return;
      var timer = null;
      box.addEventListener("input", function (ev) {
        var t = ev.target;
        if (!t || t.id !== "ceLogKw") return;
        root.__kwFocus = true;
        clearTimeout(timer);
        timer = setTimeout(function () {
          CE.ls("log.kw", t.value);
          root.__kwFocus = false;
          paint(root);
        }, 300);
      }, false);
    },
    tick: function (root) {
      /* 只在用户停手时刷新：正在输入就把这一轮跳过，免得光标乱跳 */
      var inp = root.querySelector("#ceLogKw");
      if (inp && document.activeElement === inp) return;
      paint(root);
    },
    on: function (act, arg, root) {
      if (act === "src") { CE.ls("log.src", arg); paint(root); }
      else if (act === "level") { CE.ls("log.level", arg); paint(root); }
      else if (act === "reload") { paint(root); CE.toast("已重新读取"); }
      else if (act === "copy") {
        var view = root.querySelector("#ceLogView");
        if (!view) { CE.toast("没有可复制的内容"); return; }
        if (CE.copy(view.textContent)) CE.toast("已复制 " + view.textContent.split("\n").length + " 行");
        else CE.toast("复制失败：剪贴板不可用");
      }
    }
  });
})();
