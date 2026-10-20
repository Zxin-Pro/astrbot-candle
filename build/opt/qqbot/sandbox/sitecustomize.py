# -*- coding: utf-8 -*-
"""AstrBot 在 proot 沙盒里的已知噪声兜底。

问题（v1.1.32 定位）
    proot 沙盒里，asyncio 的 accept 循环偶尔会拿不到连接：
        OSError: [Errno 38] Function not implemented      （ENOSYS）
        Exception in callback BaseSelectorEventLoop._accept_connection(...)

    现场实测：一次 6 分钟的运行里刷了 12965 条，astrbot.log 被撑到 8.5 MB，
    「上传错误日志」跟着把整份报告顶到 534 KB 截断线附近。更要命的是控制台
    日志页全是红字，用户会以为 AstrBot 坏了 —— 实际上控制台、OneBot 通道、
    插件全部工作正常（同一时刻 6185 的登录接口返回 200）。

    这个问题不是必现的：最小复现（proot 里起 asyncio server，宿主侧连进去）
    accept 完全正常，所以不能靠改 proot 参数解决；能做的就是让它别再污染日志。

做法
    只拦「accept 回调里的 ENOSYS」这一种，记一条汇总行，其余异常原样交给
    asyncio 的默认处理器。不吞 KeyError / 插件报错 / 端口占用这些真正要看的东西。

    为什么用 sitecustomize：Python 启动时会自动 import 它（site 模块负责），
    而 run-astrbot.sh 只要把本目录塞进 PYTHONPATH 就能生效 —— 不用改 AstrBot
    源码（升级会被覆盖），也不用改 venv（重装会重建）。
"""

import asyncio
import errno
import sys
import time

# 每 5 分钟最多打一行汇总，避免它自己又变成刷屏源
_SUPPRESS_INTERVAL = 300.0
_state = {"n": 0, "at": 0.0, "patched": False}


def _is_accept_enosys(ctx):
    """只认「accept 回调 + ENOSYS」这一种。

    context['message'] 形如 "Exception in callback ..._accept_connection(...)"，
    context['exception'] 是那个 OSError。
    """
    exc = ctx.get("exception") if isinstance(ctx, dict) else None
    if not isinstance(exc, OSError):
        return False
    if getattr(exc, "errno", None) != errno.ENOSYS:
        return False
    msg = str((ctx or {}).get("message") or "")
    low = msg.lower()
    return ("accept" in low) or ("transport" in low)


def _install():
    if _state["patched"]:
        return
    try:
        from asyncio import base_events  # noqa: WPS433 (延迟导入，避免影响非 asyncio 场景)
    except Exception:
        return

    original = base_events.BaseEventLoop.call_exception_handler

    def call_exception_handler(self, context):  # noqa: N802 (要保持原名签名)
        try:
            if _is_accept_enosys(context):
                _state["n"] += 1
                now = time.time()
                # 第一次必打：用户与作者都要知道「有过这件事」，但不该看到上万条
                if _state["n"] == 1 or (now - _state["at"]) >= _SUPPRESS_INTERVAL:
                    _state["at"] = now
                    try:
                        sys.stderr.write(
                            "[astrbot-guard] accept() 返回 ENOSYS（proot 已知问题），"
                            "已折叠此后的同类报错，累计 %d 次\n" % _state["n"]
                        )
                        sys.stderr.flush()
                    except Exception:
                        pass
                return
        except Exception:
            # 兜底代码自己不许把异常处理的链路打断
            pass
        return original(self, context)

    base_events.BaseEventLoop.call_exception_handler = call_exception_handler
    _state["patched"] = True


_install()
