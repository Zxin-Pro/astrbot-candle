#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""收 AstrBot 烛版的错误报告（手机端点「上传错误日志」时 POST 过来）。

为什么要有：真机上的失败原因只有用户手机上能看到，而模拟器复现不出来。
把部署日志、目录体检、机型信息一起收上来，作者才知道该改哪。

只做一件事：收下、按时间存成文件、回一个编号。不做任何页面渲染。
"""
import os
import re
import json
import time
import http.server
import socketserver

HOST = "127.0.0.1"
PORT = 8799
ROOT = "/data/astrbot-errlogs"
MAX_BYTES = 4 * 1024 * 1024
RID_RE = re.compile(r"^[A-Za-z0-9_.-]{1,64}$")


class Handler(http.server.BaseHTTPRequestHandler):
    server_version = "qqbot-errdump/1"
    protocol_version = "HTTP/1.1"

    def _json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        try:
            n = len([x for x in os.listdir(ROOT) if x.endswith(".txt")])
        except OSError:
            n = 0
        self._json(200, {"ok": True, "reports": n})

    def do_POST(self):
        if self.headers.get("X-Qqbot-Report") != "1":
            self._json(403, {"ok": False, "error": "missing header"})
            return
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            n = 0
        if n <= 0 or n > MAX_BYTES:
            self._json(413, {"ok": False, "error": "bad size %d" % n})
            return
        body = self.rfile.read(n)
        q = self.path.split("?", 1)[1] if "?" in self.path else ""
        rid = ""
        ver = ""
        for part in q.split("&"):
            if part.startswith("rid="):
                rid = part[4:]
            elif part.startswith("ver="):
                ver = part[4:]
        if not RID_RE.match(rid or ""):
            rid = time.strftime("%Y%m%d-%H%M%S") + "-anon"
        try:
            os.makedirs(ROOT, exist_ok=True)
            head = ("# rid=%s ver=%s ip=%s at=%s bytes=%d\n"
                    % (rid, ver, self.client_address[0],
                       time.strftime("%Y-%m-%d %H:%M:%S"), len(body)))
            tmp = os.path.join(ROOT, rid + ".part")
            with open(tmp, "wb") as f:
                f.write(head.encode("utf-8"))
                f.write(body)
            os.replace(tmp, os.path.join(ROOT, rid + ".txt"))
        except OSError as e:
            self._json(500, {"ok": False, "error": str(e)})
            return
        self._json(200, {"ok": True, "id": rid})

    def log_message(self, fmt, *args):
        pass


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    os.makedirs(ROOT, exist_ok=True)
    Server((HOST, PORT), Handler).serve_forever()