/* 由原生侧在 evaluateJavaScript 之前注入：只放配置，逻辑在 AndroidShim.js。
 * 真实 App 里这行由 Swift 拼出来（端口是运行时选的），开发期 mock 服务也注入同样的形状。 */
window.__ASTRIOS__ = window.__ASTRIOS__ || { base: "http://127.0.0.1:17870", methods: [] };
