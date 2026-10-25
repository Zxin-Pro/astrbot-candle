#!/usr/bin/env node
/* ext-build.mjs — 把 dev/ext/*.js 拼成一段内联脚本，写进控制台 index.html
 *
 * 为什么内联而不是外链 <script src>：
 *   APK 打包链路是 apktool 解包 → build-ui.ps1 把 ui/assets/qqbot/index.html
 *   拷进 _apkwork/assets/qqbot/ 再 apktool b。只拷这一个文件，旁边多放 .js
 *   不会被带进包里；把复制规则改成整目录能行，但那份 ps1 已经很长，
 *   多一个隐式依赖会让「换个机器重新构建」更容易踩坑。单文件内联最稳。
 *
 * 幂等：index.html 里用
 *   <!-- CE:EXT:BEGIN -->  …  <!-- CE:EXT:END -->
 * 包住生成区，重复跑只替换中间那段。第一次跑时插到 </body> 前。
 *
 * 用法：node dev/ext-build.mjs [--check]
 *   --check 只校验不写回（CI 里用来确认 index.html 与 dev/ext 同步）
 */
import fs from "node:fs";
import path from "node:path";
import url from "node:url";

const ROOT = path.resolve(path.dirname(url.fileURLToPath(import.meta.url)), "..");
const EXT = path.join(ROOT, "dev", "ext");
const HTML = path.join(ROOT, "ui", "assets", "qqbot", "index.html");

const BEGIN = "<!-- CE:EXT:BEGIN -->";
const END = "<!-- CE:EXT:END -->";

const checkOnly = process.argv.includes("--check");

function files() {
  if (!fs.existsSync(EXT)) return [];
  return fs
    .readdirSync(EXT)
    .filter((f) => /^\d+-.*\.js$/.test(f))
    .sort()
    .map((f) => path.join(EXT, f));
}

function build() {
  const fsx = files();
  if (!fsx.length) throw new Error("no modules under dev/ext");
  const parts = [];
  parts.push("/* ===== CE 扩展包（由 dev/ext-build.mjs 生成，勿手工编辑此段）===== */");
  for (const f of fsx) {
    const src = fs.readFileSync(f, "utf8");
    // 内联进 <script> 时，任何一个 "]</script>" 都会提前结束脚本块。
    // 把 </script 变成 <\/script —— JS 字符串语义不变，HTML 解析器不再认它。
    const safe = src.replace(/<\/(script)/gi, "<\\/$1");
    parts.push(`/* ---- ${path.basename(f)} ---- */`);
    parts.push(safe.trimEnd());
  }
  return { body: parts.join("\n\n"), count: fsx.length };
}

function main() {
  const { body, count } = build();
  let html = fs.readFileSync(HTML, "utf8");
  const block = `${BEGIN}\n<script>\n${body}\n</script>\n${END}`;

  const i = html.indexOf(BEGIN);
  const j = html.indexOf(END);
  if (i >= 0 && j > i) {
    html = html.slice(0, i) + block + html.slice(j + END.length);
  } else if (i < 0 && j < 0) {
    const k = html.lastIndexOf("</body>");
    if (k < 0) throw new Error("</body> not found in index.html");
    html = html.slice(0, k) + block + "\n" + html.slice(k);
  } else {
    throw new Error("index.html has a half-written CE:EXT marker block");
  }

  const bytes = Buffer.byteLength(html, "utf8");
  if (checkOnly) {
    const prev = fs.readFileSync(HTML, "utf8");
    if (prev !== html) {
      console.error("STALE: index.html 与 dev/ext 不同步，请跑 node dev/ext-build.mjs");
      process.exit(1);
    }
    console.log(`ok: ${count} modules, ${bytes} bytes`);
    return;
  }
  fs.writeFileSync(HTML, html, "utf8");
  console.log(`wrote ${path.relative(ROOT, HTML)} (${count} modules, ${bytes} bytes)`);
}

main();
