#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""AstrBot 模型连通性测试。

为什么单独一个脚本：模型能不能用，只有「真的发一条请求」才知道。Java 侧如果自己做
HTTP，就得把命令行、python 环境、密钥、超时全都复制一遍；直接跑这个脚本，复用
AstrBot 自己的 venv 和 cmd_config.json，测试结果和机器人实际用的配置永远一致。

用法（在沙盒内执行，由 App 通过 qq_proot 调起）：
    /opt/AstrBot/.venv/bin/python /qqbot-opt/model-test.py
    /opt/AstrBot/.venv/bin/python /qqbot-opt/model-test.py --list
    /opt/AstrBot/.venv/bin/python /qqbot-opt/model-test.py --config /path/cmd_config.json

输出（单行 JSON，Java 侧按最后一行解析）：
    {"ok":true,"provider":"candle","model":"deepseek-chat","ms":812,"reply":"OK"}
    {"ok":false,"error":"..."}

只依赖标准库：这个脚本要在 AstrBot 的 venv 里跑，也可能被别处的 python3 跑，
不该因为缺 requests 而失败。
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

# 沙盒内 AstrBot 的数据目录（= 宿主的 <files>/home/ubuntu/opt/AstrBot/data）。
DEFAULT_CONFIG = "/opt/AstrBot/data/cmd_config.json"
PROMPT = "你好，请回复 OK"
HTTP_TIMEOUT = 8.0


def read_json(path):
    """读 JSON 文件；带 BOM 的也认（手改过的配置偶发）。"""
    with open(path, "rb") as f:
        raw = f.read()
    text = raw.decode("utf-8", "replace")
    if text.startswith("\ufeff"):
        text = text[1:]
    return json.loads(text)


def first_key(obj):
    """provider 的 key 在新结构里是数组（支持多把轮询），老结构是字符串。"""
    if not isinstance(obj, dict):
        return ""
    k = obj.get("key")
    if isinstance(k, list) and k:
        v = k[0]
        return v if isinstance(v, str) else ""
    if isinstance(k, str):
        return k
    return ""


def find_source(root, sid):
    for s in root.get("provider_sources") or []:
        if isinstance(s, dict) and s.get("id") == sid:
            return s
    return None


def pick_provider(root):
    """挑「当前实际在用」的那条 provider。

    顺序和 App 的 AstrCfg.pickProvider 保持一致：
      1. agent_runner.config.model.provider_id 指向的那条（用户在网页控制台换过 provider）
      2. id 为 candle 的那条（本 App 自己写的）
      3. 第一条启用、且带 provider_source_id 的那条
      4. 第一条启用的
    """
    providers = [p for p in (root.get("provider") or []) if isinstance(p, dict)]
    if not providers:
        return None
    want = ""
    ar = root.get("agent_runner")
    if isinstance(ar, dict):
        ac = ar.get("config")
        if isinstance(ac, dict):
            mm = ac.get("model")
            if isinstance(mm, dict):
                want = mm.get("provider_id") or ""
    if want:
        for p in providers:
            if p.get("id") == want:
                return p
    for p in providers:
        if p.get("id") == "candle":
            return p
    first_on = None
    for p in providers:
        if p.get("enable") is False:
            continue
        if first_on is None:
            first_on = p
        if (p.get("provider_source_id") or "").strip():
            return p
    return first_on


def connection_of(root, provider):
    """拼出「地址 + 密钥」。

    AstrBot 4.29（config_version 5）把连接信息拆到 provider_sources[]，provider 上只留
    provider_source_id；早期版本是扁平的 api_base/key。运行时 manager.get_merged_provider_config()
    是 {**source, **provider} —— provider 的扁平字段会盖掉 source，这里必须照同样的顺序合并，
    否则同一个文件两种读法会得出不同答案。
    """
    base = ""
    key = ""
    sid = (provider.get("provider_source_id") or "").strip()
    if sid:
        src = find_source(root, sid)
        if src:
            base = (src.get("api_base") or "").strip()
            key = first_key(src).strip()
    flat_base = (provider.get("api_base") or "").strip()
    flat_key = first_key(provider).strip()
    if flat_base:
        base = flat_base
    if flat_key:
        key = flat_key
    return base, key


def endpoint_of(base):
    """把 api_base 拼成 chat/completions 的完整地址。

    用户可能填 https://x/v1、https://x/v1/、甚至直接填 .../chat/completions，
    三种都得能用；带查询串的（中转站常见 ?key=...）不动它。
    """
    b = (base or "").strip()
    while b.endswith("/"):
        b = b[: len(b) - 1]
    if not b:
        return ""
    if b.endswith("/chat/completions"):
        return b
    return b + "/chat/completions"


def mask(k):
    """密钥只回尾号：这个输出会进日志、也会显示在界面上。"""
    if not k:
        return ""
    if len(k) <= 8:
        return "*" * len(k)
    return k[:3] + "*" * 6 + k[-4:]


def list_providers(cfg_path):
    """--list：把配置里所有 provider 列出来，用来核对「测的到底是哪一条」。"""
    root = read_json(cfg_path)
    out = []
    for p in root.get("provider") or []:
        if not isinstance(p, dict):
            continue
        base, key = connection_of(root, p)
        out.append(
            {
                "id": p.get("id") or "",
                "model": p.get("model") or "",
                "enable": p.get("enable") is not False,
                "base": base,
                "key": mask(key),
            }
        )
    for row in out:
        print(
            "[{on}] {id}  model={model}  base={base}  key={key}".format(
                on="on " if row["enable"] else "off",
                id=row["id"],
                model=row["model"],
                base=row["base"],
                key=row["key"],
            )
        )
    if not out:
        print("（cmd_config.json 里还没有任何 provider）")


def explain(err, code=0, body=""):
    """把底层错误翻成「下一步该做什么」的一句话。"""
    s = str(err or "")
    low = s.lower()
    if code == 401 or code == 403:
        return "密钥不对或没有权限（HTTP %d）：到「模型与人格」里重新填一次模型密钥" % code
    if code == 404:
        return "接口地址不对（HTTP 404）：中转站地址一般要带 /v1，检查「模型与人格」里的地址"
    if code == 429:
        return "请求太频繁或额度用完了（HTTP 429）：稍等一会儿再测，或换一把密钥"
    if code >= 500:
        return "中转站自己出错了（HTTP %d）：不是手机的问题，过一会儿再试" % code
    if code:
        tail = (body or "").strip().replace("\n", " ")
        if len(tail) > 160:
            tail = tail[:160] + "…"
        return "请求被拒绝（HTTP %d）%s" % (code, ("：" + tail) if tail else "")
    if "timed out" in low or "timeout" in low:
        return "等了 %d 秒没回话：检查手机网络，或换个中转站地址" % int(HTTP_TIMEOUT)
    if "getaddrinfo" in low or "name or service not known" in low or "nodename" in low:
        return "解析不到中转站域名：检查网络，或把地址换成能打开的"
    if "connection refused" in low or "connection reset" in low or "network is unreachable" in low:
        return "连不上中转站：检查网络（或代理）后重试"
    if "ssl" in low or "certificate" in low:
        return "证书校验失败：中转站地址可能填错，或系统时间不对"
    return "测试失败：" + (s[:160] if s else "未知原因")


def run(cfg_path):
    root = read_json(cfg_path)
    p = pick_provider(root)
    if p is None:
        return {"ok": False, "error": "还没有配置模型：先到「模型与人格」里填模型和密钥"}
    pid = (p.get("id") or "").strip()
    model = (p.get("model") or "").strip()
    if not model:
        return {"ok": False, "error": "这条模型配置没有填模型名：到「模型与人格」里补一个，比如 deepseek-chat"}
    base, key = connection_of(root, p)
    url = endpoint_of(base)
    if not url:
        return {"ok": False, "error": "没有填中转站地址：到「模型与人格」里填上 https://…/v1"}
    if not key:
        return {"ok": False, "error": "没有填模型密钥：到「模型与人格」里重新保存一次密钥"}

    body = json.dumps(
        {
            "model": model,
            "messages": [{"role": "user", "content": PROMPT}],
            "stream": False,
            "max_tokens": 64,
        }
    ).encode("utf-8")
    req = urllib.request.Request(url, data=body, method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Authorization", "Bearer " + key)
    req.add_header("User-Agent", "AstrBotCandle/model-test")

    t0 = time.time()
    try:
        resp = urllib.request.urlopen(req, timeout=HTTP_TIMEOUT)
        raw = resp.read()
        code = resp.status
    except urllib.error.HTTPError as e:
        detail = ""
        try:
            detail = e.read().decode("utf-8", "replace")
        except Exception:
            detail = ""
        return {"ok": False, "provider": pid, "model": model, "error": explain(e, e.code, detail)}
    except urllib.error.URLError as e:
        return {"ok": False, "provider": pid, "model": model, "error": explain(e.reason)}
    except Exception as e:  # 超时、连不上、证书问题都到这里
        return {"ok": False, "provider": pid, "model": model, "error": explain(e)}
    ms = int((time.time() - t0) * 1000)

    try:
        data = json.loads(raw.decode("utf-8", "replace"))
    except Exception:
        return {
            "ok": False,
            "provider": pid,
            "model": model,
            "error": "返回的不是 JSON（HTTP %d）：地址可能指向了网页，确认它带 /v1" % code,
        }

    reply = ""
    try:
        choices = data.get("choices") or []
        if choices:
            msg = choices[0].get("message") or {}
            reply = (msg.get("content") or "").strip()
            if not reply:
                reply = (choices[0].get("text") or "").strip()
    except Exception:
        reply = ""
    if not reply:
        err = data.get("error")
        if isinstance(err, dict):
            err = err.get("message") or ""
        return {
            "ok": False,
            "provider": pid,
            "model": model,
            "ms": ms,
            "error": "回了空内容：" + (str(err)[:140] if err else "中转站没给回复内容"),
        }
    return {
        "ok": True,
        "provider": pid,
        "model": model,
        "ms": ms,
        "reply": reply[:200],
    }


def main(argv):
    ap = argparse.ArgumentParser(add_help=True, description="AstrBot 模型连通性测试")
    ap.add_argument("--config", default=DEFAULT_CONFIG, help="cmd_config.json 路径")
    ap.add_argument("--list", action="store_true", help="只列出 provider，不发请求")
    args = ap.parse_args(argv)

    cfg = args.config
    if not os.path.isfile(cfg):
        print(
            json.dumps(
                {"ok": False, "error": "还没装好 AstrBot（找不到 " + cfg + "）：先把沙盒部署完"},
                ensure_ascii=False,
            )
        )
        return 1
    try:
        if args.list:
            list_providers(cfg)
            return 0
        out = run(cfg)
    except Exception as e:
        out = {"ok": False, "error": "读配置失败：" + str(e)[:160]}
    print(json.dumps(out, ensure_ascii=False))
    return 0 if out.get("ok") else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
