#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""iOS 侧一致性自检：没有 swiftc 也能压住最致命的一类错——分派表和前端对不上。

检查项：
  1. QqbotBridge.java 的 @JavascriptInterface 方法名 与 BridgeRouter.specs 完全一致
     （少一个 = 前端 brHas 探不到，多一个 = 死代码）
  2. alwaysOn 里的名字都在 specs 里，且都不是 unsupported（否则「没连宿主也要挂」自相矛盾）
  3. specs 里 local 的方法，LocalBridge 的 switch 或 StatsBridge 必须能处理
  4. index.html 里 Android.xxx( 的每个名字都在 specs 里（前端写错名字/我们漏移植）
  5. 每个 Swift 文件的花括号 / 圆括号配平（跳过注释与字符串）
  6. CONTRACT.md 列出的方法名也都在 specs 里
用法：python ios/tools/check_bridge.py
退出码 0 = 全过。
"""
import io
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
IOS = os.path.dirname(HERE)
ROOT = os.path.dirname(IOS)
SRC = os.path.join(IOS, "AstrBotIOS")
JAVA = os.path.join(ROOT, "ui", "src", "com", "termux", "qqbot", "QqbotBridge.java")
HTML = os.path.join(ROOT, "ui", "assets", "qqbot", "index.html")
ROUTER = os.path.join(SRC, "Bridge", "BridgeRouter.swift")
LOCAL = os.path.join(SRC, "Bridge", "LocalBridge.swift")
STATS = os.path.join(SRC, "Bridge", "StatsBridge.swift")

ok = 0
bad = 0


def check(title, passed, detail=""):
    global ok, bad
    if passed:
        ok += 1
        print("[PASS] " + title + (" —— " + detail if detail else ""))
    else:
        bad += 1
        print("[FAIL] " + title + (" —— " + detail if detail else ""))


def read(path):
    with io.open(path, "r", encoding="utf-8", errors="replace") as fh:
        return fh.read()


def java_methods():
    src = read(JAVA)
    out = set()
    for m in re.finditer(r"@JavascriptInterface\s+", src):
        tail = src[m.end():m.end() + 400]
        hit = re.match(r"(?:public\s+)?(?:final\s+)?[\w<>\[\], .?]+?\s+(\w+)\s*\(", tail)
        if hit:
            out.add(hit.group(1))
    return out


def router_specs():
    src = read(ROUTER)
    start = src.find("specs = [")
    end = src.find("\n    }", start)
    block = src[start:end if end > 0 else len(src)]
    specs = {}
    for m in re.finditer(r'^\s{12}"(\w+)":\s+([LRX])\(', block, re.M):
        specs[m.group(1)] = m.group(2)
    return specs


def always_on():
    src = read(ROUTER)
    start = src.find("alwaysOn: Set<String> = [")
    end = src.find("]", start)
    return set(re.findall(r'"(\w+)"', src[start:end]))


def handled_local():
    names = set()
    for path in (LOCAL, STATS):
        src = read(path)
        for m in re.finditer(r'case "([^"]+)"', src):
            names.add(m.group(1))
        # case "a", "b": 这种一行多个
        for m in re.finditer(r'case ((?:"[^"]+"\s*,\s*)+"[^"]+")', src):
            for inner in re.findall(r'"([^"]+)"', m.group(1)):
                names.add(inner)
        for m in re.finditer(r'"([a-zA-Z]+)"\s*:', src):
            pass
    return names


def html_calls():
    src = read(HTML)
    direct = set(re.findall(r"Android\.([A-Za-z_$][\w$]*)\s*\(", src))
    bracketed = set(re.findall(r'Android\[\s*"([A-Za-z_$][\w$]*)"\s*\]', src))
    return direct, bracketed


def balance(path):
    """粗略词法：跳过 // /* */ 和 "..." '...' 以及三引号多行字符串，数括号。"""
    src = read(path)
    i = 0
    depth_curly = 0
    depth_paren = 0
    depth_sq = 0
    n = len(src)
    line = 1
    worst = None
    while i < n:
        c = src[i]
        if c == "\n":
            line += 1
            i += 1
            continue
        if src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
            continue
        if src.startswith("/*", i):
            j = src.find("*/", i + 2)
            seg = src[i:n if j < 0 else j + 2]
            line += seg.count("\n")
            i = n if j < 0 else j + 2
            continue
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            seg = src[i:n if j < 0 else j + 3]
            line += seg.count("\n")
            i = n if j < 0 else j + 3
            continue
        if c == '"':
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == '"' or src[j] == "\n":
                    break
                j += 1
            line += src[i:j].count("\n")
            i = j + 1
            continue
        if c == "{":
            depth_curly += 1
        elif c == "}":
            depth_curly -= 1
            if depth_curly < 0 and worst is None:
                worst = ("多余的 }", line)
        elif c == "(":
            depth_paren += 1
        elif c == ")":
            depth_paren -= 1
        elif c == "[":
            depth_sq += 1
        elif c == "]":
            depth_sq -= 1
        i += 1
    return depth_curly, depth_paren, depth_sq, worst


def main():
    print("=" * 72)
    print("iOS 桥一致性自检")
    print("=" * 72)

    jm = java_methods()
    specs = router_specs()
    print("Java 方法 %d 个 / 分派表 %d 项" % (len(jm), len(specs)))

    check("Java 的 @JavascriptInterface 方法都能在分派表里找到",
          not (jm - set(specs)), "缺：" + ", ".join(sorted(jm - set(specs))) if jm - set(specs) else "无缺失")
    check("分派表里没有 Java 侧不存在的方法",
          not (set(specs) - jm), "多：" + ", ".join(sorted(set(specs) - jm)) if set(specs) - jm else "无多余")

    ao = always_on()
    check("alwaysOn 的方法都在分派表里", not (ao - set(specs)),
          "缺：" + ", ".join(sorted(ao - set(specs))) if ao - set(specs) else "%d 个" % len(ao))
    bad_ao = [n for n in ao if specs.get(n) == "X"]
    check("alwaysOn 里没有 unsupported（否则永远挂不上）", not bad_ao, ", ".join(bad_ao) or "无")

    local_names = set(n for n, k in specs.items() if k == "L")
    handled = handled_local()
    missing = sorted(n for n in local_names if n not in handled)
    check("local 方法都有实现（LocalBridge / StatsBridge）", not missing,
          "未实现：" + ", ".join(missing) if missing else "%d 个 local 全覆盖" % len(local_names))

    direct, bracketed = html_calls()
    # 页面里有些调用是 try/catch 兜底的僵尸分支（例如 Android.appVer()，Java 侧本来就没这个
    # 方法），这类名字在 Android 上同样探不到，不算我们的锅；真正要拦的是「Java 有、我们漏了」。
    phantom = sorted(n for n in direct if n not in specs and n in jm)
    dead = sorted(n for n in direct if n not in specs and n not in jm)
    check("index.html 里 Android.xxx( 的调用名都在分派表里", not phantom,
          "漏移植：" + ", ".join(phantom) if phantom else "%d 个调用名" % len(direct))
    if dead:
        print("      （另有 %d 个页面僵尸分支，Java 侧也不存在，忽略：%s）"
              % (len(dead), ", ".join(dead)))
    print("      （另有 Android[动态名字] 写法 %d 处，靠 V/JSON 运行时决定，静态无法枚举）" % len(bracketed))

    shard = sorted(n for n in ("appVer",) if n in specs)
    check("没有把 appVer 当成桥方法（它在 state() 的字段里）", not shard, ", ".join(shard) or "无")

    files = []
    for base, _, names in os.walk(SRC):
        for f in names:
            if f.endswith(".swift"):
                files.append(os.path.join(base, f))
    files.sort()
    broken = []
    for path in files:
        dc, dp, ds, worst = balance(path)
        if dc or dp or ds or worst:
            broken.append("%s curly=%d paren=%d sq=%d %s" % (os.path.basename(path), dc, dp, ds, worst or ""))
    check("所有 Swift 文件括号配平（跳过注释与字符串）", not broken,
          "; ".join(broken) if broken else "%d 个文件" % len(files))

    contract = os.path.join(IOS, "CONTRACT.md")
    if os.path.exists(contract):
        names = set(re.findall(r"\b(\w+)\(", read(contract)))
        known = set(specs) | {"GET", "POST", "state", "if", "for", "print", "func", "return", "json"}
        phantom = sorted(n for n in names if n in jm and n not in specs)
        check("CONTRACT.md 提到的桥方法都在分派表里", not phantom, ", ".join(phantom) or "无")

    print("-" * 72)
    print("检查项 %d，PASS %d，FAIL %d" % (ok + bad, ok, bad))
    return 0 if bad == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
