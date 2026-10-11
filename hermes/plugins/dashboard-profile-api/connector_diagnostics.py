"""고정 SDK source의 compile 진단만 본문 없이 남긴다."""

from __future__ import annotations

from contextlib import contextmanager
from contextvars import ContextVar
import hashlib
import importlib
import importlib.metadata
import json
import logging
from pathlib import Path
import threading
from types import SimpleNamespace

POLICY = "fos-snapshot-diagnostics-v1:mcp-2.0.0"
MESSAGE = "mcp diagnostic body omitted"
_STATE_KEY = "_fos_snapshot_diagnostics_state"
_LOCK_KEY = "_fos_snapshot_diagnostics_lock"
_MODULES = (("mcp.client.stdio", "mcp.client.stdio"),
            ("mcp.client.session", "client"),
            ("mcp.shared.jsonrpc_dispatcher", "mcp.shared.jsonrpc_dispatcher"))
_MANIFEST = Path(__file__).with_name("connector_diagnostics_source.json")


class _BodylessFilter(logging.Filter):
    policy = POLICY

    def __init__(self, active):
        super().__init__()
        self.active = active

    def filter(self, record):
        if self.active.get():
            record.msg = MESSAGE
            record.args = ()
            record.exc_info = None
            record.exc_text = None
            record.stack_info = None
            record.__dict__.pop("message", None)
        return True


def _source_loggers():
    loggers = []
    for module_name, name in _MODULES:
        module = importlib.import_module(module_name)
        logger = logging.getLogger(name)
        if module.logger is not logger or logger.name != name:
            raise RuntimeError
        loggers.append(logger)
    return tuple(loggers)


def _validate_source():
    if importlib.metadata.version("mcp") != "2.0.0":
        raise RuntimeError
    root = Path(importlib.import_module("mcp").__file__).resolve().parent
    expected = json.loads(_MANIFEST.read_bytes())
    actual = {file.relative_to(root).as_posix(): hashlib.sha256(file.read_bytes()).hexdigest()
              for file in root.rglob("*.py")}
    if len(expected) != 122 or actual != expected:
        raise RuntimeError
    for module_name, _ in _MODULES:
        module = importlib.import_module(module_name)
        file = Path(module.__file__).resolve()
        relative = file.relative_to(root).as_posix()
        if relative != module_name.removeprefix("mcp.").replace(".", "/") + ".py":
            raise RuntimeError
        if hashlib.sha256(file.read_bytes()).hexdigest() != expected[relative]:
            raise RuntimeError


def ensure_snapshot_diagnostics():
    """reload를 넘어 같은 상태를 쓰며, 보호가 빠졌으면 실행을 거절한다."""
    try:
        # Logger 객체는 plugin unload 뒤에도 남는다. setdefault는 최초 lock만 보관한다.
        anchor = logging.getLogger("mcp.client.stdio")
        lock = anchor.__dict__.setdefault(_LOCK_KEY, threading.Lock())
        with lock:
            loggers = _source_loggers()
            state = anchor.__dict__.get(_STATE_KEY)
            if state is None:
                # 앞선 filter는 원문을 볼 수 있으므로 최초 설치도 거절한다.
                if any(logger.filters for logger in loggers):
                    raise RuntimeError
                _validate_source()
                active = ContextVar("fos_snapshot_diagnostics", default=False)
                guard = _BodylessFilter(active)
                state = SimpleNamespace(policy=POLICY, active=active, guard=guard, loggers=loggers)
                for logger in loggers:
                    logger.addFilter(guard)
                anchor.__dict__[_STATE_KEY] = state
            if (state.policy != POLICY or state.guard.policy != POLICY
                    or state.guard.active is not state.active or state.loggers != loggers
                    or any(not logger.filters or logger.filters[0] is not state.guard
                           or sum(item is state.guard for item in logger.filters) != 1
                           or any(item is not state.guard and hasattr(item, "policy")
                                  for item in logger.filters) for logger in loggers)):
                raise RuntimeError
            return state
    except Exception:
        raise RuntimeError("snapshot diagnostics unavailable") from None


@contextmanager
def snapshot_diagnostics_scope():
    """SDK context와 그 자식 task가 끝날 때까지 보호하고 호출자의 상태를 되돌린다."""
    state = ensure_snapshot_diagnostics()
    token = state.active.set(True)
    try:
        yield state
    finally:
        state.active.reset(token)
