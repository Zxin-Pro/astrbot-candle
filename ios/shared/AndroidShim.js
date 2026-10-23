/* AstrBot iOS 版桥接垫片 —— 把 window.Android 的同步调用转成对回环服务的同步 XHR。
 *
 * 为什么要同步 XHR：index.html 里全是 `var r = JSON.parse(Android.state())` 这种同步取值，
 * 一共 200+ 处调用点，改异步等于重写整个控制台。WKWebView 的 postMessage 是异步的，
 * 所以这里用同源回环 HTTP + `xhr.open(..., false)` 保住同步语义，前端一个字节都不用改。
 *
 * 配置由原生侧在 documentStart 注入：
 *   window.__ASTRIOS__ = { base: "http://127.0.0.1:17870", methods: ["state", "action", ...] }
 * 只挂 methods 里列出的方法：这样 `typeof Android.xxx === "function"` 的能力探测仍然准确，
 * 宿主没连上时前端会走「这个安装包还不支持…」的降级分支，而不是弹一堆红字。
 */
(function () {
  "use strict";
  var C = window.__ASTRIOS__ || {};
  var base = String(C.base || "http://127.0.0.1:17870");
  var methods = Array.isArray(C.methods) ? C.methods : [];

  function call(name, args) {
    var xhr = new XMLHttpRequest();
    try {
      xhr.open("POST", base + "/__bridge", false); // false = 同步，这是整个方案的关键
    } catch (e) {
      return "";
    }
    try {
      xhr.setRequestHeader("Content-Type", "text/plain;charset=UTF-8");
      xhr.send(JSON.stringify({ m: name, a: args }));
    } catch (e) {
      return "";
    }
    if (xhr.status !== 200) return "";
    var t = xhr.responseText;
    return typeof t === "string" ? t : "";
  }

  var A = {};
  for (var i = 0; i < methods.length; i++) {
    (function (name) {
      A[name] = function () {
        var args = [];
        for (var k = 0; k < arguments.length; k++) {
          var v = arguments[k];
          // 桥参数只有 String / int / boolean 三种，undefined 会变成 null，一律压成字符串
          args.push(v === undefined ? "" : v);
        }
        return call(name, args);
      };
    })(methods[i]);
  }

  window.Android = A;
  window.__ASTRIOS_BRIDGE__ = { base: base, methods: methods.slice(), call: call };
})();
