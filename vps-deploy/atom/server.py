#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""烛版的「应用内公告 + 崩溃聚合 + 匿名统计」服务端（功能 56 / 58 / 59）。

和 qqbot-errdump 是一路的做法：python3 标准库、没有第三方依赖、只监听 127.0.0.1，
由 nginx 从 443 反代出去（域名 https://xn--15t839a.xyz）。不渲染任何页面，只吐 JSON。

四个口子：
    GET  /api/announce?ver=x.y.z   应用内公告（带 ETag，支持 If-None-Match → 304）
    POST /api/crash                崩溃聚合上报（要头 X-Qqbot-Atom: 1）
    POST /api/stats                匿名日统计（要头 X-Qqbot-Atom: 1）
    GET  /api/atom-health          运维计数

数据全在 /opt/qqbot-atom/ 下（这个目录是 service 的 ReadWritePaths，别往别处写）：
    announce.json   公告源。人可以直接手改，服务每次请求现读，改完立刻生效，不用重启。
    crash.jsonl     崩溃聚合，一行一条 JSON。
    stats.jsonl     匿名统计，一行一条 JSON。

隐私：crash.jsonl / stats.jsonl 里不存原始 IP，只存 ip 的 sha1 前 12 位；
客户端上传的 id 是匿名安装 id，不是账号。
"""
import hashlib
import http.server
import json
import os
import re
import socketserver
import sys
import threading
import time
import urllib.parse

HOST = "127.0.0.1"
# 下面两个允许用环境变量顶掉，纯粹是为了能在本机拿临时目录跑一遍自测。
# systemd 里不设这两个变量，线上就是 /opt/qqbot-atom + 8800。
PORT = int(os.environ.get("QQBOT_ATOM_PORT") or 8800)
DATA_DIR = os.environ.get("QQBOT_ATOM_DIR") or "/opt/qqbot-atom"
ANN_PATH = os.path.join(DATA_DIR, "announce.json")
CRASH_PATH = os.path.join(DATA_DIR, "crash.jsonl")
STATS_PATH = os.path.join(DATA_DIR, "stats.jsonl")

MAX_BODY = 64 * 1024            # 64KB，和 nginx 里的 client_max_body_size 64k 对齐
HEADER_NAME = "X-Qqbot-Atom"    # 和 errdump 的 X-Qqbot-Report 一样，挡掉随手扫的爬虫
HEADER_VALUE = "1"

RL_WINDOW = 60.0                # 限流窗口（秒）
RL_MAX = 20                     # 窗口内每个 IP 最多 20 次 POST

# /api/app-config.json 里的功能开关（约定第 11 节）。这个版本四个都开。
FLAGS = {"widget": True, "backup": True, "stats": True, "crashboard": True}

# ---------------------------------------------------------------------------
# 首次启动时自动铺的示例公告
# ---------------------------------------------------------------------------
DEFAULT_ANN = {
    "items": [
        {
            "id": "v1130-release",
            "title": "烛版 1.1.30 已发布",
            "body": "这一版修好了 NAPI 模块在部分机型上启动即崩的问题，并新增了应用内公告与崩溃上报。"
                    "升级后可以在「关于」里确认版本号已经是 1.1.30。",
            "level": "info",
            "minVer": "1.1.30",
            "until": "",
        }
    ]
}

# 白名单字符集：宁可拒收，也不让奇形怪状的串进到日志文件里
RE_ID = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
RE_VER = re.compile(r"^[0-9A-Za-z._-]{1,32}$")
RE_SIG = re.compile(r"^[0-9a-fA-F]{8,40}$")
RE_MODULE = re.compile(r"^[A-Za-z0-9_.:/-]{1,64}$")
RE_DAY = re.compile(r"^\d{4}-\d{2}-\d{2}$")
RE_OS = re.compile(r"^[A-Za-z0-9 ._+-]{0,32}$")
RE_ARCH = re.compile(r"^[A-Za-z0-9_.-]{0,16}$")
RE_EVENT = re.compile(r"^[a-z][a-z0-9_]{0,31}$")

# 崩溃要按 sig+ver 合并、统计要按 id+day+ver 覆盖，两者都是「读-改-写」，
# ThreadingTCPServer 是多线程的，这里串行化一下，别让两个请求同时重写同一个文件。
STORE_LOCK = threading.Lock()


# ---------------------------------------------------------------------------
# 小工具
# ---------------------------------------------------------------------------
def _sha12(text):
    """IP 的 sha1 前 12 位。只留这个，原始 IP 不落盘。"""
    return hashlib.sha1(text.encode("utf-8", "replace")).hexdigest()[:12]


def _int(value, lo, hi, name):
    # JSON 里的 11 是 int；有些客户端会写成 11.0，整数浮点也放过
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError("%s 必须是整数" % name)
    if isinstance(value, float):
        if not value.is_integer():
            raise ValueError("%s 必须是整数" % name)
        value = int(value)
    if value < lo or value > hi:
        raise ValueError("%s 超出范围" % name)
    return value


def _str(value, regex, name):
    if not isinstance(value, str):
        raise ValueError("%s 必须是字符串" % name)
    if not regex.match(value):
        raise ValueError("%s 格式不合法" % name)
    return value


def _first(obj, *names):
    """按顺序取第一个存在且不是 None 的字段。

    同一个意思在仓库里有两个名字（docs/接口约定-1.1.30.md 叫 component，
    需求书写的是 module），这里两个都收，谁都别改 —— 先让数据能落盘。
    """
    for name in names:
        if obj.get(name) is not None:
            return obj.get(name)
    raise ValueError("缺少字段 %s" % "/".join(names))


def _epoch_of_day(text):
    """"2026-10-19" → 那天 23:59:59 的 epoch 秒；空串 → 0（永不过期）。"""
    if not text:
        return 0
    try:
        t = time.strptime(text, "%Y-%m-%d")
    except Exception:
        return 0
    return int(time.mktime((t.tm_year, t.tm_mon, t.tm_mday, 23, 59, 59, 0, 0, -1)))


def _ver_tuple(text):
    """"1.1.30" → (1, 1, 30)。取前四段，够比大小了。"""
    parts = [p for p in re.split(r"[^0-9]+", text or "") if p]
    return tuple(int(p) for p in parts[:4])


def _count_lines(path):
    try:
        n = 0
        with open(path, "rb") as f:
            for _ in f:
                n += 1
        return n
    except OSError:
        return 0


def _load_jsonl(path):
    """读回来。坏行（写一半、手改坏）直接跳过，不让一行脏数据毁掉整份。"""
    rows = []
    try:
        with open(path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    obj = json.loads(line)
                except Exception:
                    continue
                if isinstance(obj, dict):
                    rows.append(obj)
    except FileNotFoundError:
        pass
    return rows


def _save_jsonl(path, rows):
    """整份重写：先写 .tmp 再 os.replace。断电不会留半截文件。"""
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        for row in rows:
            f.write(json.dumps(row, ensure_ascii=False,
                               separators=(",", ":")) + "\n")
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


def _safe_int(value, default=0):
    if isinstance(value, bool):
        return default
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def merge_crash(record):
    """按 sig + ver 合并：同一类崩溃只留一行，count 累加。

    看板要的是「这一类崩了几次」，不是「报了几次」——重复行会把统计冲稀。
    每类只占一行，量很小，整份重写最省心。返回 "merged" / "new"。
    """
    ts = record["ts"]
    with STORE_LOCK:
        rows = _load_jsonl(CRASH_PATH)
        for row in rows:
            if row.get("sig") != record["sig"] or row.get("ver") != record["ver"]:
                continue
            row["count"] = _safe_int(row.get("count")) + record["count"]
            row["received_at"] = record["received_at"]
            row["ip"] = record["ip"]          # 最近一次上报的来源哈希
            if not row.get("id"):
                row["id"] = record["id"]
            if record.get("module"):
                row["module"] = record["module"]
            if record.get("signal") is not None:
                row["signal"] = record["signal"]
            if record.get("device"):
                row["device"] = record["device"]
            lo = min(ts, _safe_int(row.get("first"), ts),
                     _safe_int(row.get("ts"), ts))
            hi = max(ts, _safe_int(row.get("last"), ts))
            row["ts"] = lo
            row["first"] = lo
            row["last"] = hi
            _save_jsonl(CRASH_PATH, rows)
            return "merged"

        lo, hi = ts, ts
        if record.get("first") is not None:
            lo = min(lo, _safe_int(record["first"], ts))
        if record.get("last") is not None:
            hi = max(hi, _safe_int(record["last"], ts))
        record["ts"] = lo
        record["first"] = lo
        record["last"] = hi
        rows.append(record)
        _save_jsonl(CRASH_PATH, rows)
        return "new"


def upsert_stats(record):
    """同 id + day + ver 一天只留一条：后到的覆盖先到的。

    否则同一台机器一天报十几次，日活/在线时长全被算重。
    返回 "updated" / "new"。
    """
    with STORE_LOCK:
        rows = _load_jsonl(STATS_PATH)
        for i, row in enumerate(rows):
            if (row.get("id") == record["id"]
                    and row.get("day") == record["day"]
                    and row.get("ver") == record["ver"]):
                rows[i] = record
                _save_jsonl(STATS_PATH, rows)
                return "updated"
        rows.append(record)
        _save_jsonl(STATS_PATH, rows)
        return "new"


def ensure_default_announce():
    """announce.json 不存在就铺一份示例（原子写，别留半截文件）。"""
    if os.path.exists(ANN_PATH):
        return
    try:
        os.makedirs(DATA_DIR, exist_ok=True)
        tmp = ANN_PATH + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(DEFAULT_ANN, f, ensure_ascii=False, indent=2)
            f.write("\n")
        os.replace(tmp, ANN_PATH)
    except OSError as exc:
        sys.stderr.write("铺 announce.json 失败：%s\n" % exc)


def load_announce():
    """每次请求现读，手改文件立刻生效。

    文件写坏了（手抖删个逗号）就退回内置的那份，绝不让 /api/announce 直接挂掉 ——
    公告挂了会让客户端每次启动都报错，代价比"看到一条旧公告"大得多。
    """
    try:
        with open(ANN_PATH, "rb") as f:
            raw = f.read()
        obj = json.loads(raw.decode("utf-8"))
        if isinstance(obj, dict):
            items = obj.get("items")
        elif isinstance(obj, list):
            items = obj
        else:
            items = None
        if not isinstance(items, list):
            raise ValueError("announce.json 里没有 items 数组")
    except FileNotFoundError:
        ensure_default_announce()
        items = DEFAULT_ANN["items"]
    except Exception as exc:  # 语法错误 / 编码错误 / 结构错误
        sys.stderr.write("announce.json 读取失败，退回内置公告：%s\n" % exc)
        items = DEFAULT_ANN["items"]

    out = []
    for it in items:
        if not isinstance(it, dict):
            continue
        out.append({
            "id": str(it.get("id") or "")[:64],
            "title": str(it.get("title") or "")[:200],
            "body": str(it.get("body") or "")[:2000],
            "level": "warn" if it.get("level") == "warn" else "info",
            "minVer": str(it.get("minVer") or "")[:32],
            "until": str(it.get("until") or "")[:32],
        })
    return out


def filter_items(items, ver):
    """按客户端版本与有效期筛。

    minVer 为空 → 所有版本都能看到；有 minVer → 只给 >= 它的版本看。
    until 为空 → 永不过期；否则按 YYYY-MM-DD 当天为止（含当天）。
    ver 没传或写脏了 → 不按版本筛，让客户端自己判断，总比拿不到公告强。
    """
    today = time.strftime("%Y-%m-%d")
    cur = None
    if ver:
        try:
            cur = _ver_tuple(ver)
        except Exception:
            cur = None

    out = []
    for it in items:
        need = it.get("minVer") or ""
        if cur is not None and need:
            try:
                if cur < _ver_tuple(need):
                    continue
            except Exception:
                pass
        until = it.get("until") or ""
        if until and until < today:
            continue
        out.append(it)
    return out


class RateLimiter(object):
    """按 IP 的滑动窗口限流。内存字典，重启即清空 —— 这个量级不需要落盘。"""

    def __init__(self, window, limit):
        self.window = window
        self.limit = limit
        self.hits = {}
        self.lock = threading.Lock()

    def allow(self, key):
        now = time.time()
        cutoff = now - self.window
        with self.lock:
            bucket = [t for t in self.hits.get(key, ()) if t > cutoff]
            if len(bucket) >= self.limit:
                self.hits[key] = bucket
                return False
            bucket.append(now)
            self.hits[key] = bucket
            if len(self.hits) > 4096:  # 顺手清掉过期的桶，防止字典无限涨
                for k in [k for k, v in self.hits.items() if not v or v[-1] <= cutoff]:
                    self.hits.pop(k, None)
            return True


RATE = RateLimiter(RL_WINDOW, RL_MAX)


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------
class Handler(http.server.BaseHTTPRequestHandler):
    server_version = "qqbot-atom/1"
    protocol_version = "HTTP/1.1"

    # ---------- 回包 ----------
    def _send(self, code, body, ctype, extra=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("X-Content-Type-Options", "nosniff")
        for key, value in (extra or ()):
            self.send_header(key, value)
        self.end_headers()
        if body:
            self.wfile.write(body)

    def _json(self, code, obj, extra=None):
        body = json.dumps(obj, ensure_ascii=False,
                          separators=(",", ":")).encode("utf-8")
        self._send(code, body, "application/json; charset=utf-8", extra)

    def _not_modified(self, etag):
        # 304 按 RFC 就是没有 body，不写 Content-Length，keep-alive 照样成立
        self.send_response(304)
        self.send_header("ETag", etag)
        self.send_header("Cache-Control", "public, max-age=60")
        self.end_headers()

    def _fail(self, code, exc):
        """任何异常都兜成一个 JSON 回包，绝不给客户端 500 空 body。"""
        try:
            msg = "%s: %s" % (type(exc).__name__, exc)
            self._json(code, {"ok": False, "error": msg[:300]})
        except Exception:
            pass

    # ---------- 请求体 ----------
    def _read_body(self):
        """读并校验 Content-Length。返回 bytes；已经回过错就返回 None。"""
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except (TypeError, ValueError):
            n = -1
        if n <= 0:
            self.close_connection = True
            self._json(400, {"ok": False, "error": "缺少 Content-Length"})
            return None
        if n > MAX_BODY:
            # body 没读，这条连接没法再复用，直接断
            self.close_connection = True
            self._json(413, {"ok": False, "error": "body 超过 64KB"})
            return None
        data = self.rfile.read(n)
        if len(data) != n:
            self.close_connection = True
            self._json(400, {"ok": False, "error": "body 不完整"})
            return None
        return data

    def _client_ip(self):
        # 请求经 nginx 反代进来，真实 IP 由 nginx 的 X-Real-IP 带过来
        # （nginx 自己已经从 CF-Connecting-IP 还原过，见 nginx.conf 的 set_real_ip_from）
        ip = self.headers.get("X-Real-IP") or self.client_address[0] or ""
        return ip.strip()[:64]

    # ---------- 路由 ----------
    def do_GET(self):
        try:
            path, _, query = self.path.partition("?")
            if path == "/api/announce":
                return self._handle_announce(query)
            if path == "/api/app-config.json":
                return self._handle_app_config()
            if path == "/api/atom-health":
                return self._handle_health()
            return self._json(404, {"ok": False, "error": "not found"})
        except Exception as exc:
            return self._fail(500, exc)

    def do_POST(self):
        try:
            path = self.path.split("?", 1)[0]
            if path not in ("/api/crash", "/api/stats"):
                return self._json(404, {"ok": False, "error": "not found"})
            if self.headers.get(HEADER_NAME) != HEADER_VALUE:
                return self._json(403, {"ok": False, "error": "missing header"})
            ip = self._client_ip()
            if not RATE.allow(ip):
                return self._json(429, {"ok": False, "error": "too many requests"})
            data = self._read_body()
            if data is None:
                return None
            try:
                obj = json.loads(data.decode("utf-8"))
            except Exception:
                return self._json(400, {"ok": False, "error": "body 不是合法 JSON"})
            if not isinstance(obj, dict):
                return self._json(400, {"ok": False, "error": "body 必须是 JSON 对象"})

            if path == "/api/crash":
                return self._handle_crash(obj, ip)
            return self._handle_stats(obj, ip)
        except Exception as exc:
            return self._fail(500, exc)

    # ---------- GET /api/announce ----------
    def _handle_announce(self, query):
        ver = ""
        for part in query.split("&"):
            if part.startswith("ver="):
                ver = part[4:]
        ver = urllib.parse.unquote(ver).strip()[:32]
        if ver and not RE_VER.match(ver):
            ver = ""  # 参数脏了就当没传，不因为参数问题让客户端拿不到公告

        items = filter_items(load_announce(), ver)
        body = json.dumps({"ok": True, "items": items}, ensure_ascii=False,
                          separators=(",", ":")).encode("utf-8")
        etag = '"%s"' % hashlib.sha1(body).hexdigest()

        if self._etag_match(self.headers.get("If-None-Match"), etag):
            return self._not_modified(etag)
        self._send(200, body, "application/json; charset=utf-8",
                   [("ETag", etag), ("Cache-Control", "public, max-age=60")])

    @staticmethod
    def _etag_match(inm, etag):
        if not inm:
            return False
        inm = inm.strip()
        if inm == "*":
            return True
        want = etag.strip('"')
        for part in inm.split(","):
            token = part.strip()
            if token.startswith("W/"):
                token = token[2:]
            if token.strip('"') == want:
                return True
        return False

    # ---------- GET /api/app-config.json ----------
    def _handle_app_config(self):
        """docs/接口约定-1.1.30.md 第 11 节的那个形状。

        和 /api/announce 读同一份 announce.json，只是把第一条公告摊成
        {"ver","minVer","ann":{id,text,level,until},"flags":{...}}。
        两条路并存：App 端照约定写、站点或别的客户端照需求书写，都能取到。
        """
        items = load_announce()
        ann = items[0] if items else None
        if ann is None:
            ann_json = None
            min_ver = "1.1.0"
        else:
            text = ann["body"]
            if ann["title"]:
                text = (ann["title"] + "：" + text) if text else ann["title"]
            ann_json = {
                "id": ann["id"],
                "text": text,
                "level": ann["level"],
                "until": _epoch_of_day(ann["until"]),
            }
            min_ver = ann["minVer"] or "1.1.0"

        obj = {
            "ver": 1,
            "minVer": min_ver,
            "ann": ann_json,
            "flags": FLAGS,
        }
        body = json.dumps(obj, ensure_ascii=False,
                          separators=(",", ":")).encode("utf-8")
        etag = '"%s"' % hashlib.sha1(body).hexdigest()
        if self._etag_match(self.headers.get("If-None-Match"), etag):
            return self._not_modified(etag)
        self._send(200, body, "application/json; charset=utf-8",
                   [("ETag", etag), ("Cache-Control", "public, max-age=60")])

    # ---------- GET /api/atom-health ----------
    def _handle_health(self):
        self._json(200, {
            "ok": True,
            "crash": _count_lines(CRASH_PATH),
            "stats": _count_lines(STATS_PATH),
            "announce": len(load_announce()),
        })

    # ---------- POST /api/crash ----------
    def _handle_crash(self, obj, ip):
        # 没给 ts 时补一个。优先用客户端给的 last（"最后一次发生在什么时候"），
        # 实在没有才用服务器当前时间 —— 直接用 now 会把 last 抬到今天，
        # 契约里那次「上个月崩的」就被算成今天了。
        ts_fallback = obj.get("last")
        if ts_fallback is None:
            ts_fallback = int(time.time())
        try:
            record = {
                "id": _str(obj.get("id"), RE_ID, "id"),
                "ver": _str(obj.get("ver"), RE_VER, "ver"),
                "sig": _str(obj.get("sig"), RE_SIG, "sig"),
                # 需求书叫 module，docs/接口约定-1.1.30.md 叫 component，两个都收
                "module": _str(_first(obj, "module", "component"), RE_MODULE,
                               "module/component"),
                "signal": _int(obj.get("signal"), -128, 255, "signal"),
                "count": _int(obj.get("count", 1), 1, 1000000, "count"),
                "ts": _int(obj.get("ts", ts_fallback), 0, 4102444800, "ts"),
            }
            # 约定里还有 device / first / last，有就带上，没有不影响
            device = obj.get("device")
            if device is not None:
                record["device"] = _str(device, RE_ARCH, "device")
            for key in ("first", "last"):
                if obj.get(key) is not None:
                    record[key] = _int(obj.get(key), 0, 4102444800, key)
        except ValueError as exc:
            self.close_connection = True
            return self._json(400, {"ok": False, "error": str(exc)})

        # received_at 用 epoch 秒，和 ts 同一把尺子，按小时分桶直接除 3600
        record["received_at"] = int(time.time())
        record["ip"] = _sha12(ip)  # 只留 sha1 前 12 位，原始 IP 不落盘
        try:
            merge_crash(record)
        except OSError as exc:
            return self._json(500, {"ok": False, "error": "写入失败: %s" % exc})
        self._json(200, {"ok": True})

    # ---------- POST /api/stats ----------
    def _handle_stats(self, obj, ip):
        try:
            record = {
                "id": _str(obj.get("id"), RE_ID, "id"),
                "ver": _str(obj.get("ver"), RE_VER, "ver"),
                "day": _str(obj.get("day"), RE_DAY, "day"),
                # 两个形状都收：需求书给 onlineSec/msgs/errors，约定给 events 映射。
                # 缺的按 0 落，别因为客户端少报一项就把整条丢了。
                "onlineSec": _int(obj.get("onlineSec", 0), 0, 172800, "onlineSec"),
                "msgs": _int(obj.get("msgs", 0), 0, 100000000, "msgs"),
                "errors": _int(obj.get("errors", 0), 0, 1000000, "errors"),
                "os": _str(obj.get("os", ""), RE_OS, "os"),
                "arch": _str(obj.get("arch", ""), RE_ARCH, "arch"),
            }
            events = obj.get("events")
            if events is not None:
                if not isinstance(events, dict):
                    raise ValueError("events 必须是 JSON 对象")
                if len(events) > 32:
                    raise ValueError("events 条目太多")
                bucket = {}
                for name, times in events.items():
                    if not isinstance(name, str) or not RE_EVENT.match(name):
                        raise ValueError("events 里有非法事件名")
                    bucket[name] = _int(times, 0, 100000000, "events." + name)
                if bucket:
                    record["events"] = bucket
        except ValueError as exc:
            self.close_connection = True
            return self._json(400, {"ok": False, "error": str(exc)})

        record["received_at"] = int(time.time())
        record["ip"] = _sha12(ip)
        try:
            upsert_stats(record)
        except OSError as exc:
            return self._json(500, {"ok": False, "error": "写入失败: %s" % exc})
        self._json(200, {"ok": True})

    def log_message(self, fmt, *args):
        # 和 errdump 一样不吵日志。真要排查就去看 /opt/qqbot-atom/*.jsonl
        pass


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    os.makedirs(DATA_DIR, exist_ok=True)
    ensure_default_announce()
    sys.stderr.write("qqbot-atom 监听 %s:%d，数据目录 %s\n" % (HOST, PORT, DATA_DIR))
    sys.stderr.flush()
    Server((HOST, PORT), Handler).serve_forever()
