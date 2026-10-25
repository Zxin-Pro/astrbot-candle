(function () {
  "use strict";

  /* ------------------------------------------------------------------
     上手检查清单（home）
     ------------------------------------------------------------------
     新装用户最常见的三个问题，全都不是 bug：
       ① 没给通知权限 → 常驻通知上的「关闭进程」没了，以为软件坏了；
       ② 没加省电白名单 → 息屏十分钟进程被系统杀掉，以为断线；
       ③ 部署没跑完就点重启。
     这张卡只在「还有事情没做」时出现，全部打勾后 html() 返回空串，
     内核会把整张卡从 DOM 里摘掉 —— 老用户永远看不到它。
     ------------------------------------------------------------------ */

  var ITEMS = [
    { id: "deploy", t: "完成沙盒部署", d: "NapCat / AstrBot 要装进手机里的 Linux 沙盒，首次约 20–60 分钟。", todo: "去部署", act: "deploy" },
    { id: "inst", t: "添加一个 QQ 实例", d: "每个实例就是一个要登录的 QQ 号，端口互相独立。", todo: "去添加", act: "addbot" },
    { id: "login", t: "扫码登录 QQ", d: "登录后 NapCat 才能收发消息，二维码在「日志」页。", todo: "去扫码", act: "showqr" },
    { id: "noti", t: "开启通知栏权限", d: "没有通知权限，后台被系统收走时你会毫无察觉。", todo: "去开启", act: "notiask" },
    { id: "power", t: "关闭电池优化（加白名单）", d: "国产系统的省电策略会在息屏后冻结应用，机器人就掉线了。", todo: "去设置", act: "power" },
    { id: "guard", t: "打开后台守护", d: "守护进程会在容器或组件挂掉时自动拉起来。", todo: "去打开", act: "guard" }
  ];

  function probe() {
    var S = CE.json(CE.call("state"), null) || {};
    var pw = CE.json(CE.call("powerState"), null) || {};
    var bots = (S.bots && S.bots.length) ? S.bots : [];
    var logged = false;
    for (var i = 0; i < bots.length; i++) {
      if (bots[i] && bots[i].uin) { logged = true; break; }
    }
    var res = {};
    res.deploy = S.ok === true && S.deployDone === true;
    res.inst = bots.length > 0;
    res.login = logged;
    res.noti = S.notiGranted === true;
    /* powerState 的字段在各版本间变过：只要明确是「已确认/已加白」才算完成，
       字段缺失按「未完成」处理 —— 宁可多提示一次，也别让用户以为已经加过了。 */
    res.power = !!(pw && (pw.ok === true && (pw.acked === true || pw.shown === true || pw.whitelisted === true)));
    res.guard = S.guardOn === true;
    return res;
  }

  function rows(res) {
    var done = 0, total = ITEMS.length, out = "", pending = [];
    for (var i = 0; i < ITEMS.length; i++) {
      var it = ITEMS[i], ok = !!res[it.id];
      if (ok) done++; else pending.push(it.t);
      out += '<div class="ce-row"><span class="ce-dot ' + (ok ? "ok" : "warn") + '"></span>' +
        '<div class="ce-rt"><b>' + CE.esc(it.t) + "</b><span>" + CE.esc(it.d) + "</span></div>" +
        (ok ? '<span class="ce-rv ce-ok">已完成</span>'
          : '<button class="small" data-ce-act="go" data-ce-arg="' + it.id + '">' + CE.esc(it.todo) + "</button>") +
        "</div>";
    }
    return { html: out, done: done, total: total, pending: pending };
  }

  function paint(root) {
    var body = root.querySelector("#ceChkBody");
    if (!body) return;
    var r = rows(probe());
    var head = r.done === r.total
      ? '<div class="ce-hint ce-ok">✔ 全部就绪，这张卡下次就不出现了</div>'
      : '<div class="ce-hint">还有 ' + (r.total - r.done) + " 项没做：" + CE.esc(r.pending.slice(0, 3).join("、")) +
        (r.pending.length > 3 ? " 等" : "") + "</div>";
    body.innerHTML = '<div class="ce-bar"><i style="width:' + CE.pct(r.done, r.total) + '%"></i></div>' + head + r.html;
  }

  function fire(id) {
    if (id === "power") {
      var j = CE.json(CE.call("powerGuide"), null);
      if (j && j.ok === false && j.err) CE.toast(j.err);
      else CE.toast("已尝试打开电池设置");
      return;
    }
    if (id === "guard") {
      if (CE.has("action")) CE.call("action", JSON.stringify({ act: "guardset", arg: "1" }));
      CE.toast("已请求打开后台守护");
      return;
    }
    var it = null;
    for (var i = 0; i < ITEMS.length; i++) if (ITEMS[i].id === id) { it = ITEMS[i]; break; }
    if (it && CE.has("action")) CE.call("action", JSON.stringify({ act: it.act }));
  }

  CE.reg({
    id: "checklist",
    tab: "home",
    order: 36,
    title: "上手检查",
    html: function () {
      var r = rows(probe());
      /* 全做完就不占地方了：返回空串，内核会摘掉这张卡 */
      if (r.done === r.total) return "";
      return '<div class="card"><h2>上手检查</h2>' +
        '<div class="hint" style="margin-top:0">按顺序做完这 ' + r.total + " 项，机器人就能稳定在线了。</div>" +
        '<div id="ceChkBody"></div></div>';
    },
    mount: function (root) { paint(root); },
    tick: function (root) { paint(root); },
    on: function (act, arg) {
      if (act === "go") fire(arg);
    }
  });
})();
