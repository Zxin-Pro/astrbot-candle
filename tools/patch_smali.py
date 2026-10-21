# Rewrite the Termux package id / data dir inside decoded smali.
#
# The smali tree is a mix of two kinds of "com.termux" strings:
#   * package-level identifiers that must follow the new applicationId
#     ("com.termux", "/data/data/com.termux/...", "com.termux.files",
#      "com.termux.permission.RUN_COMMAND", "com.termux.RUN_COMMAND", ...)
#   * Java class names used as data ("com.termux.app.TermuxInstaller" for
#     Class.forName, "com.termux.app.TermuxActivity" for setClassName,
#     the GUARD_* broadcast actions). Those classes still live in the
#     com.termux.app package, so rewriting them breaks reflection at runtime
#     (v1.2.2 first attempt: ClassNotFoundException: zhuxin.astrbot.app.TermuxInstaller,
#     bootstrap never unpacked, UI stuck on "还没部署").
import os, re, sys
ROOT = sys.argv[1]
OLD_PKG = "com.termux"
NEW_PKG = "zhuxin.astrbot"
OLD_PATH = "/data/data/com.termux"
NEW_PATH = "/data/data/zhuxin.astrbot"
# keep class references (com.termux.app.*) untouched
PAT = re.compile(r"com\.termux(?!\.app\.)")
files = 0; hits = 0; paths = 0
for base, dirs, names in os.walk(ROOT):
    rel = os.path.relpath(base, ROOT)
    if not any(part.startswith("smali") for part in rel.split(os.sep)):
        continue
    for n in names:
        if not n.endswith(".smali"):
            continue
        p = os.path.join(base, n)
        t = open(p, encoding="utf-8").read()
        if OLD_PKG not in t:
            continue
        c = t.count(OLD_PKG); pc = t.count(OLD_PATH)
        t2 = t.replace(OLD_PATH, NEW_PATH)
        t2 = PAT.sub(NEW_PKG, t2)
        open(p, "w", encoding="utf-8", newline="").write(t2)
        files += 1; hits += c; paths += pc
print("smali files rewritten=%d occurrences=%d path_hits=%d (com.termux.app.* kept)" % (files, hits, paths))
