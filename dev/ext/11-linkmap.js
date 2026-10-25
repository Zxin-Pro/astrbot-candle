/* =====================================================================
   11-linkmap — 链路状态（home / order 32）
   ---------------------------------------------------------------------
   为什么做成一条链：用户问「机器人不回消息」，真正想知道的是断在哪一环。
   五段严格按依赖顺序排：沙盒容器 → NapCat → OneBot 端口 → AstrBot → QQ 账号。
   前一段确定断了，后一段的真实状态就没有参考价值（NapCat 没起来，OneBot 自然也
   连不上），所以统一标成灰点「上游没通」，保证一眼只看到一个真正的断点。

   取数只在 tick 里调一次 state()：它会读宿主文件，主界面 4 秒一轮已经在调，
   模块重复调只会白白增加卡顿。首次 html() 只画骨架，真正的数据由 mount 后的
   第一轮 tick 填进来。
   ===================================================================== */
(function () {
  "use strict";

  var BODY = "ceLinkBody";
  var LAST = null;      /* 最近一次成功解析的 state，按钮处理复用它，避免再读文件 */
  var armUntil = 0;     /* 容器重启的二次确认截止时间（毫秒），只放内存，不写本地存储 */
  var armTimer = null;

  /* ---------------- 小工具 ---------------- */

  /* 桥里的布尔值可能是 true / 1 / "1" / "true"，统一收口，避免各处写法不一致 */
  function on(v) {
    return v === true || v === 1 || v === "1" || v === "true";
  }

  function blank(v) {
    return v === undefined || v === null || v === "";
  }

  function nowMs() {
    return (new Date()).getTime();
  }

  function portText(p) {
    return p ? String(p) : "—";
  }

  /* state() 只调这一处；它返回空串或明确失败时给 null，调用方统一走空态。
     注意真机部分版本的 state() 成功时并不带 ok 字段（只有出错才带 ok:false），
     所以「没有 ok 但结构像 state」的返回也放行，否则整卡在真机上会永远空着。 */
  function readState() {
    var raw = "";
    try { raw = CE.call("state"); } catch (e) { raw = ""; }
    if (blank(raw)) return null;
    var st = null;
    try { st = CE.json(raw, null); } catch (e) { st = null; }
    if (!st || typeof st !== "object") return null;
    if (st.ok === false || st.ok === 0 || st.ok === "0" || st.ok === "false") return null;
    if (st.ok !== true && st.deployDone === undefined && st.supervisorAlive === undefined) return null;
    return st;
  }

  /* state().bots 在真机上是 {active:"bot1", bots:[…]}，契约里写的是直接一个数组；
     两种都兼容，取不到就给空数组，后面按「没有实例」降级。 */
  function botList(st) {
    var raw = st && st.bots;
    if (!raw || typeof raw !== "object") return [];
    if (typeof raw.length === "number") return raw;
    if (raw.bots && typeof raw.bots.length === "number") return raw.bots;
    return [];
  }

  function activeId(st) {
    var raw = st && st.bots;
    var v = "";
    if (raw && typeof raw === "object" && !blank(raw.active)) v = raw.active;
    else if (st && !blank(st.active)) v = st.active;
    return blank(v) ? "" : String(v);
  }

  /* 「当前实例」：优先 bots.active 指向的那个，其次带 active 标记的，再其次第一个 up 的 */
  function pickBot(st) {
    var list = botList(st);
    var aid = activeId(st);
    var i, b;
    if (!list.length) return null;
    if (aid) {
      for (i = 0; i < list.length; i++) {
        b = list[i];
        if (b && String(b.id || "") === aid) return b;
      }
    }
    for (i = 0; i < list.length; i++) if (list[i] && list[i].active) return list[i];
    for (i = 0; i < list.length; i++) if (list[i] && on(list[i].up)) return list[i];
    return list[0] || null;
  }

  /* 契约字段是 uin，真机 bots.json 用的是 qq；uin 明确为空时按「未登录」处理，
     只有 uin 整个字段不存在（老数据）才退回 qq */
  function qqOf(bot) {
    if (!bot) return "";
    var v = bot.uin;
    if (v === undefined || v === null) v = bot.qq;
    return (v === undefined || v === null) ? "" : String(v);
  }

  function nameOf(bot) {
    if (!bot) return "—";
    if (!blank(bot.label)) return String(bot.label);
    if (!blank(bot.name)) return String(bot.name);
    if (!blank(bot.id)) return String(bot.id);
    return "默认实例";
  }

  /* 端口以 state 顶层为准（它按当前实例推导过），缺失再退回实例自己的字段 */
  function portOf(st, key) {
    var n = parseInt(st ? st[key] : "", 10) || 0;
    if (n <= 0) {
      var b = pickBot(st);
      if (b) n = parseInt(b[key], 10) || 0;
    }
    return n > 0 ? String(n) : "";
  }

  function push(list, id, name, cls, text, fix, btn) {
    list.push({ id: id, name: name, cls: cls, text: text, fix: fix, btn: btn });
  }

  /* ---------------- 五段状态 ---------------- */

  function buildSegs(st) {
    var bot = pickBot(st);
    var uin = qqOf(bot);
    var nap = portText(portOf(st, "napcatPort"));
    var ob = portText(portOf(st, "onebotPort"));
    var ast = portText(portOf(st, "astrbotPort"));
    var segs = [];

    /* 1. 沙盒容器：整条链的根，NapCat / AstrBot 都跑在它里面 */
    if (blank(st.supervisorAlive)) {
      push(segs, "container", "沙盒容器", "warn",
        "读不到容器状态，可能刚启动",
        "状态没上报，点「重启」可以重新拉起沙盒（会短暂中断消息）", "重启");
    } else if (on(st.supervisorAlive)) {
      push(segs, "container", "沙盒容器", "ok",
        "容器在运行", "", "重启");
    } else {
      push(segs, "container", "沙盒容器", "bad",
        "容器没在运行，消息进程全停了",
        "沙盒守护进程没在跑，点「重启」把容器拉起来（会短暂中断消息）", "重启");
    }

    /* 2. NapCat：端口在听且用户没有手动关闭才算好 */
    if (on(st.napcatOff)) {
      push(segs, "napcat", "NapCat", "bad",
        "NapCat 已被手动关闭",
        "NapCat 被关掉了，去「更多 → 组件开关」重新打开，或点「打开 WebUI」确认状态", "打开 WebUI");
    } else if (blank(st.napcatUp)) {
      push(segs, "napcat", "NapCat", "warn",
        "读不到 NapCat 端口状态",
        "点「打开 WebUI」看看 NapCat 是否还在启动", "打开 WebUI");
    } else if (!on(st.napcatUp)) {
      push(segs, "napcat", "NapCat", "bad",
        "端口 " + nap + " 没在监听，NapCat 没起来",
        "NapCat 没起来，点「打开 WebUI」看日志或重启 NapCat", "打开 WebUI");
    } else {
      push(segs, "napcat", "NapCat", "ok",
        "端口 " + nap + " 在监听", "", "打开 WebUI");
    }

    /* 3. OneBot 端口：AstrBot 连 NapCat 的通道 */
    if (blank(st.onebotUp)) {
      push(segs, "onebot", "OneBot 端口", "warn",
        "读不到 OneBot 端口状态",
        "去「更多 → 端口设置」检查端口是否和其它实例撞了", "检查");
    } else if (!on(st.onebotUp)) {
      push(segs, "onebot", "OneBot 端口", "bad",
        "端口 " + ob + " 没监听，AstrBot 连不上 NapCat",
        "OneBot 端口没监听：去「更多 → 端口设置」检查是否和其它实例撞了", "检查");
    } else {
      push(segs, "onebot", "OneBot 端口", "ok",
        "端口 " + ob + " 在监听", "", "检查");
    }

    /* 4. AstrBot 控制台：端口在听且用户没有手动关闭 */
    if (on(st.astrbotOff)) {
      push(segs, "astrbot", "AstrBot 控制台", "bad",
        "AstrBot 已被手动关闭",
        "AstrBot 被关掉了，去「更多 → 组件开关」重新打开，或点「打开控制台」确认状态", "打开控制台");
    } else if (blank(st.astrbotUp)) {
      push(segs, "astrbot", "AstrBot 控制台", "warn",
        "读不到 AstrBot 状态",
        "点「打开控制台」看看 AstrBot 是否还在启动", "打开控制台");
    } else if (!on(st.astrbotUp)) {
      push(segs, "astrbot", "AstrBot 控制台", "bad",
        "控制台端口 " + ast + " 没响应",
        "AstrBot 没起来，点「打开控制台」看启动日志", "打开控制台");
    } else {
      push(segs, "astrbot", "AstrBot 控制台", "ok",
        "控制台端口 " + ast + " 正常响应", "", "打开控制台");
    }

    /* 5. QQ 账号：当前实例有号且在线，才算整条链真的通了 */
    if (!bot) {
      push(segs, "qq", "QQ 账号", "bad",
        "未登录，还没有机器人实例",
        "去「机器人」页新增一个实例，填 QQ 号和 Token 后会自动登录", "扫码登录");
    } else if (!uin) {
      push(segs, "qq", "QQ 账号", "bad",
        "未登录，还没绑定 QQ 号",
        "QQ 未登录，点「扫码登录」用手机 QQ 扫码", "扫码登录");
    } else if (blank(bot.up)) {
      push(segs, "qq", "QQ 账号", "warn",
        "读不到账号在线状态",
        "点「扫码登录」可以重新调出二维码", "扫码登录");
    } else if (!on(bot.up)) {
      push(segs, "qq", "QQ 账号", "bad",
        "账号 " + uin + " 掉线了",
        "QQ 掉线了，点「扫码登录」用手机 QQ 重新扫码", "扫码登录");
    } else {
      push(segs, "qq", "QQ 账号", "ok",
        "账号 " + uin + " 在线", "", "扫码登录");
    }

    return segs;
  }

  /* 上游确定断了以后，下游统一灰点：避免 NapCat 挂了时 OneBot/AstrBot 跟着一片红，
     用户分不清到底该先修哪个。灰点不是"未知"，而是"现在判断没有意义"。 */
  function settle(segs) {
    var blocked = false;
    var i;
    for (i = 0; i < segs.length; i++) {
      if (blocked) {
        segs[i].cls = "";
        segs[i].text = "上游没通，先修好上面那段";
      }
      if (segs[i].cls === "bad") blocked = true;
    }
  }

  /* ---------------- 渲染 ---------------- */

  function hintHtml(segs) {
    var i, badAt = -1, firstWarn = -1, allOk = true;
    for (i = 0; i < segs.length; i++) {
      if (segs[i].cls !== "ok") allOk = false;
      if (badAt < 0 && segs[i].cls === "bad") badAt = i;
      if (firstWarn < 0 && segs[i].cls === "warn") firstWarn = i;
    }
    if (allOk) {
      return '<div class="ce-hint ce-ok">✔ 链路完整，消息可以正常收发</div>';
    }
    if (badAt >= 0) {
      var from = badAt === 0 ? "手机" : segs[badAt - 1].name;
      return '<div class="ce-hint ce-bad">断在：' + CE.esc(from) + ' → ' +
        CE.esc(segs[badAt].name) + ' 之间 · ' + CE.esc(segs[badAt].fix) + '</div>';
    }
    /* 没有红点、但有读不到的状态：别谎报"正常"，明确告诉用户还在等数据 */
    var who = firstWarn >= 0 ? segs[firstWarn].name : "部分组件";
    return '<div class="ce-hint ce-warn">' + CE.esc("链路状态不完整：" + who + " 状态读不到，稍后会自动重试") + '</div>';
  }

  function rowsHtml(segs) {
    var h = "<div>";
    var now = nowMs();
    var i, s, armed, cls, label;
    for (i = 0; i < segs.length; i++) {
      s = segs[i];
      /* 二次确认状态跨 tick 保留：8 秒刷新时按钮不能偷偷变回「重启」 */
      armed = s.id === "container" && armUntil > now;
      cls = armed ? "small primary" : "small";
      label = armed ? "确认重启？" : s.btn;
      h += '<div class="ce-row">' +
        '<span class="ce-dot' + (s.cls ? " " + s.cls : "") + '"></span>' +
        '<div class="ce-rt"><b>' + CE.esc(s.name) + '</b><span>' + CE.esc(s.text) + '</span></div>' +
        '<div class="ce-rv"><button class="' + cls + '" data-ce-act="fix" data-ce-arg="' +
        CE.esc(s.id) + '">' + CE.esc(label) + '</button></div>' +
        '</div>';
    }
    return h + "</div>";
  }

  function footHtml(st) {
    var bot = pickBot(st);
    var inst = bot ? nameOf(bot) : "—";
    var uin = qqOf(bot) || "未登录";
    return '<div class="ce-hint ce-mono">实例 ' + CE.esc(inst) +
      ' · QQ ' + CE.esc(uin) +
      ' · napcat ' + CE.esc(portText(portOf(st, "napcatPort"))) +
      ' · onebot ' + CE.esc(portText(portOf(st, "onebotPort"))) +
      ' · astrbot ' + CE.esc(portText(portOf(st, "astrbotPort"))) + '</div>';
  }

  function bodyHtml(st) {
    var segs = buildSegs(st);
    settle(segs);
    return hintHtml(segs) + rowsHtml(segs) + footHtml(st);
  }

  function deployHtml() {
    return '<div class="ce-empty">还没完成部署，链路待建立</div>' +
      '<div class="btns"><button class="small primary" data-ce-act="deploy">去部署</button></div>';
  }

  /* ---------------- 注册 ---------------- */

  CE.reg({
    id: "linkmap",
    tab: "home",
    order: 32,
    title: "链路状态",

    html: function () {
      return '<div class="card"><h2>链路状态</h2>' +
        '<div id="' + BODY + '"><div class="ce-load"><i></i>正在检查链路…</div></div>' +
        '</div>';
    },

    tick: function (root) {
      var box = null;
      try { box = root.querySelector("#" + BODY); } catch (e) { box = null; }
      if (!box) return;

      /* 整轮只读一次 state —— 它会去读宿主文件，不能在这里重复调 */
      var st = readState();
      LAST = st;

      if (!st) {
        box.innerHTML = '<div class="ce-empty">读不到运行状态，稍后自动重试</div>';
        return;
      }
      if (!st.deployDone) {
        box.innerHTML = deployHtml();
        return;
      }
      try {
        box.innerHTML = bodyHtml(st);
      } catch (e) {
        box.innerHTML = '<div class="ce-empty">链路状态渲染失败，稍后自动重试</div>';
      }
    },

    on: function (act, arg, root, el) {
      try {
        if (act === "deploy") {
          CE.call("action", JSON.stringify({ act: "deploy" }));
          return;
        }
        if (act !== "fix") return;

        var st = LAST || readState();
        if (!st) { CE.toast("还没读到运行状态，稍后再试"); return; }

        /* 重启沙盒会掐断正在跑的消息，必须两步确认；WebView 里 window.confirm
           被系统禁用，所以用「按钮文字变更 + 3 秒窗口」这套纯前端确认。 */
        if (arg === "container") {
          var now = nowMs();
          if (armUntil && now <= armUntil) {
            armUntil = 0;
            if (armTimer) { clearTimeout(armTimer); armTimer = null; }
            if (el) el.textContent = "重启";
            CE.toast("正在重启沙盒…");
            CE.call("action", JSON.stringify({ act: "restartnow" }));
            return;
          }
          armUntil = now + 3000;
          if (el) el.textContent = "确认重启？";
          if (armTimer) clearTimeout(armTimer);
          armTimer = setTimeout(function () {
            armUntil = 0;
            armTimer = null;
            try {
              var b = root.querySelector('[data-ce-act="fix"][data-ce-arg="container"]');
              if (b) b.textContent = "重启";
            } catch (e2) {}
          }, 3100);
          return;
        }

        if (arg === "napcat") {
          var napPort = portOf(st, "napcatPort");
          if (!napPort) { CE.toast("还没读到 NapCat 端口，稍后再试"); return; }
          CE.call("openPanel", "http://127.0.0.1:" + napPort + "/webui/");
          return;
        }

        if (arg === "onebot") {
          var obPort = portOf(st, "onebotPort");
          CE.toast("OneBot 端口 " + (obPort || "?") +
            " 没监听：去「更多 → 端口设置」检查是否和其它实例撞了");
          return;
        }

        if (arg === "astrbot") {
          var astPort = portOf(st, "astrbotPort");
          if (!astPort) { CE.toast("还没读到 AstrBot 端口，稍后再试"); return; }
          CE.call("openPanel", "http://127.0.0.1:" + astPort + "/");
          return;
        }

        if (arg === "qq") {
          CE.call("action", JSON.stringify({ act: "showqr" }));
          return;
        }
      } catch (e) {
        CE.toast("操作失败：" + (e && e.message ? e.message : "未知错误"));
      }
    }
  });
})();
