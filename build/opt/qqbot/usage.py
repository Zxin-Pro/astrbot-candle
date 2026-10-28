#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""AstrBot 模型用量统计（契约 3.1 的 JSON，一行输出）。

为什么有这个文件：Android 侧真正的数据源是 QqbotUsageBridge.java（SQLiteDatabase 只读直读
data_v4.db）。Termux 前缀里没有 python3（见 build/opt/qqbot/common.sh:236、delbot.sh:17），
所以桥不走脚本；这份等价实现留在沙盒里，用来手工核对「桥算出来的数对不对」，
或者在沙盒内排障。两者的口径必须一字不差，改一边就同步改另一边。

口径（逐行对照 AstrBot 4.28.2 的 org dashboard 实现）：
  - 表 provider_stats，每次内部 agent 回复写一行
    （astrbot/core/pipeline/process_stage/method/agent_sub_stages/internal.py:688）。
  - 只取 agent_type='internal'，status 不过滤 —— 面板的 where 只有这两个条件
    （astrbot/dashboard/services/stat_service.py:322-326），成功/失败另外单独数（:377-379）。
  - in = token_input_other + token_input_cached，out = token_output（stat_service.py:359-363、:388）。
    另外单独给 cached（缓存命中量）与 inOther（不含缓存的输入），界面用它们算命中率和自定义单价花费。
  - models 按契约 3.1 用 calls 降序；面板是按 token 量排序（stat_service.py:419-426），这里以契约为准。
  - created_at 是 naive UTC，先补 UTC 再转本地时区，按本地日分桶
    （stat_service.py:298-302、:357-358）。
    直接按 UTC 分日会让「今天」错 8 小时。
  - cost：AstrBot 没有任何价格字段，恒为 -1（1.3.4-beta.10 起界面让用户自己填输入/缓存/输出三个单价，现场算钱）。
  - days 固定 7 条（今天往前 6 天，缺的补 0），today 就是 days 最后一条。

只读：用 sqlite3 的 uri 模式 mode=ro 打开，绝不建库、绝不写 WAL。
任何异常都降级成 {"ok":false,"err":...}，不抛栈。

用法：python3 usage.py [data_v4.db 路径]
"""

import json
import os
import sqlite3
import sys
import traceback
from datetime import datetime, timedelta, timezone

ERR_NO_DATA = "没找到 AstrBot 的用量数据"
DAYS = 7
MAX_MODELS = 6

# 找不到库时依次尝试的位置：沙盒内 cwd 就是 /opt/AstrBot
DB_CANDIDATES = (
    "/opt/AstrBot/data/data_v4.db",
    "data/data_v4.db",
)

SQL = (
    "SELECT created_at, provider_model, token_input_other,"
    " token_input_cached, token_output FROM provider_stats"
    " WHERE agent_type = 'internal' AND created_at >= ?"
)


def fail(note):
    """失败也返回契约形状的 JSON：界面只认 err，note 给排障用。"""
    return {"ok": False, "err": ERR_NO_DATA, "note": str(note)}


def astr_ver():
    """顺手读 AstrBot 的 __version__，失败就说不认识（报错里带上很有用）。"""
    try:
        path = "/opt/AstrBot/astrbot/__init__.py"
        if not os.path.isfile(path):
            return "AstrBot 版本未知"
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            text = f.read(262144)
        i = text.find("__version__")
        if i < 0:
            return "AstrBot 版本未知"
        q1 = text.find('"', i)
        q2 = text.find('"', q1 + 1) if q1 >= 0 else -1
        if q1 < 0 or q2 <= q1:
            return "AstrBot 版本未知"
        return "AstrBot " + text[q1 + 1:q2].strip()
    except Exception:
        return "AstrBot 版本未知"


def parse_utc(s):
    """SQLAlchemy 写的 naive UTC 文本 -> 带时区的 datetime；解析不了返回 None。"""
    if not s:
        return None
    t = str(s).strip().replace("T", " ")
    if t.endswith("Z"):
        t = t[:-1]
    if "." in t:
        head, _, frac = t.partition(".")
        t = head + "." + (frac + "000000")[:6]
    for fmt in ("%Y-%m-%d %H:%M:%S.%f", "%Y-%m-%d %H:%M:%S"):
        try:
            return datetime.strptime(t, fmt).replace(tzinfo=timezone.utc)
        except ValueError:
            continue
    return None


def pick_db(argv):
    if len(argv) > 1 and argv[1].strip():
        return argv[1].strip()
    for c in DB_CANDIDATES:
        if os.path.isfile(c):
            return c
    return DB_CANDIDATES[0]


def open_ro(path):
    """只读打开：uri 的 mode=ro 保证不建文件、不写 WAL；超时短一点，别把界面卡住。"""
    uri = "file:" + path.replace("?", "%3f").replace("#", "%23") + "?mode=ro"
    conn = sqlite3.connect(uri, uri=True, timeout=2.0)
    try:
        conn.execute("PRAGMA busy_timeout=2000")
    except Exception:
        pass
    return conn


def build(db_path):
    today = datetime.now().astimezone().replace(hour=0, minute=0, second=0, microsecond=0)
    week_start = today - timedelta(days=DAYS - 1)
    # 拿一个「足够早」的 UTC 文本当下限；精确分桶交给本地时区做
    bound = week_start.astimezone(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")

    by_day = {}
    by_model = {}
    today_key = today.date()

    conn = open_ro(db_path)
    try:
        cur = conn.execute(SQL, (bound,))
        while True:
            row = cur.fetchone()
            if row is None:
                break
            dt = parse_utc(row[0])
            if dt is None:
                continue
            local = dt.astimezone()
            key = local.date()
            cached = int(row[3] or 0)
            tok_in = int(row[2] or 0) + cached
            tok_out = int(row[4] or 0)
            b = by_day.setdefault(key, [0, 0, 0, 0])
            b[0] += 1
            b[1] += tok_in
            b[2] += tok_out
            b[3] += cached
            if key == today_key:
                model = (row[1] or "").strip() or "未知模型"
                m = by_model.setdefault(model, [0, 0, 0])
                m[0] += 1
                m[1] += tok_in
                m[2] += tok_out
    finally:
        try:
            conn.close()
        except Exception:
            pass

    days = []
    day = week_start
    for _ in range(DAYS):
        b = by_day.get(day.date(), [0, 0, 0, 0])
        days.append({
            "d": day.strftime("%Y-%m-%d"),
            "calls": int(b[0]),
            "in": int(b[1]),
            "out": int(b[2]),
            "cached": int(b[3]),
        })
        day = day + timedelta(days=1)

    last = days[-1]
    total_calls = sum(int(d["calls"]) for d in days)

    names = sorted(by_model.keys(), key=lambda n: (-by_model[n][0], -(by_model[n][1] + by_model[n][2]), n))
    models = []
    for name in names[:MAX_MODELS]:
        m = by_model[name]
        models.append({"m": name, "calls": int(m[0]), "in": int(m[1]), "out": int(m[2])})

    return {
        "ok": True,
        "src": "db",
        "note": "数据来自 AstrBot 本地库" if total_calls > 0 else "AstrBot 本地库还没有调用记录",
        "today": {
            "calls": int(last["calls"]),
            "in": int(last["in"]),
            "out": int(last["out"]),
            "cached": int(last["cached"]),
            "inOther": int(last["in"]) - int(last["cached"]),
            "cost": -1,   # 桥不猜价：单价由界面填，这里恒 -1
        },
        "days": days,
        "models": models,
    }


def main(argv):
    db = pick_db(argv)
    try:
        if not os.path.isfile(db):
            return fail("还没生成 %s，沙盒可能没部署完或者没启动过；%s" % (db, astr_ver()))
        try:
            return build(db)
        except sqlite3.OperationalError as e:
            low = str(e).lower()
            if "no such table" in low:
                why = "库在，但没有 provider_stats 表（AstrBot 版本偏老，或者还没跑过一轮对话）"
            elif "locked" in low or "busy" in low:
                why = "AstrBot 正在写这个库，过一会儿再看"
            elif "unable to open" in low:
                why = "库文件打不开（沙盒可能停在写库的中途，或者文件权限不对）"
            else:
                why = "读库失败：%s" % e
            return fail("%s；%s；%s" % (db, astr_ver(), why))
        except sqlite3.DatabaseError as e:
            return fail("%s；%s；读库失败：%s" % (db, astr_ver(), e))
    except Exception:
        return fail("%s；读库过程出错：%s" % (db, traceback.format_exc(limit=1).strip().replace("\n", " ")))


if __name__ == "__main__":
    try:
        result = main(sys.argv)
    except Exception:
        result = fail("脚本异常")
    # 优先人类可读的中文；万一 stdout 不是 UTF-8（沙盒里是 C.UTF-8，但别处可能是 GBK
    # 控制台），退成 \uXXXX 转义 —— 照样是合法 JSON，宁可难读也不能把整行输出弄丢。
    line = json.dumps(result, ensure_ascii=False, separators=(",", ":")) + "\n"
    try:
        sys.stdout.write(line)
        sys.stdout.flush()
    except (UnicodeEncodeError, ValueError):
        sys.stdout.write(json.dumps(result, ensure_ascii=True, separators=(",", ":")) + "\n")
        sys.stdout.flush()
