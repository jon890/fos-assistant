"""도구 호출 하나마다 걸린 시간과 실패한 도구의 오류를 Control Plane 에 보낸다(fos-assistant docs/features/run-trace/design.md).

`post_tool_call` hook 은 기록을 메모리 대기열에 넣고 바로 돌아간다. 데몬 스레드 하나가 0.5초 모아 보내고, 실패하면 버린다.
기록은 관측용이라 실행을 늦추거나 실패시키지 않는다(스펙 run-trace REQ7).

Hermes 는 profile 여럿을 한 프로세스에서 돌린다. 보내는 스레드는 profile 맥락을 물려받지 못해 토큰과 state.db 를 바로 읽지 못한다.
그래서 hook 안에서 토큰과 state.db 경로를 기록에 담고, 보내는 스레드는 토큰마다 묶어 보낸다.

도구 인자, 결과, 미들웨어 이력은 보내지 않는다. 실패한 도구의 오류 글만 보내고 Control Plane 이 가린다(스펙 run-trace REQ6).
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import queue
import sqlite3
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

from .connector_policy import POLICY_URL_ENV
from .context import _read_token, _state_db_path, logger, root_session, signing_key

# 기록 경로의 마지막 조각이다. `FOS_CTX_POLICY_URL` 의 마지막 조각을 이것으로 바꿔 같은 Control Plane 에 보낸다.
TRACE_PATH = "run-trace"
TRACE_VERSION = "v1-run-trace"
TOOL_KIND = "tool"
# 대기열의 크기다. 가득 차면 새 기록을 버린다.
QUEUE_LIMIT = 1000
# 한 요청에 넣는 기록의 수다. Control Plane 도 이만큼만 읽는다.
BATCH_LIMIT = 100
# 한 요청에 넣는 오류 글의 UTF-8 바이트 합이다. 프록시의 본문 상한에 걸려 묶음을 통째로 잃지 않게 한다.
BATCH_ERROR_BYTES = 256 * 1024
# 보내기 전에 기록을 모으는 시간(초)이다.
FLUSH_DELAY = 0.5
SEND_TIMEOUT = 3.0
# 오류 글로 보내는 길이다. Control Plane 가림 규칙의 입력 상한보다 한 글자 길다. 넘은 글은 Control Plane 이 통째로 가린다.
ERROR_MESSAGE_LIMIT = 65_537
ERROR_TYPE_LIMIT = 200
# 같은 실패를 로그에 남기는 간격(초)이다. Control Plane 이 멈춰도 로그가 기록 수만큼 쌓이지 않게 한다.
LOG_INTERVAL = 60.0

_queue: queue.Queue = queue.Queue(maxsize=QUEUE_LIMIT)
_worker_lock = threading.Lock()
_worker: threading.Thread | None = None
_worker_pid: int | None = None
_log_lock = threading.Lock()
_last_logged: dict[str, float] = {}
_suppressed: dict[str, int] = {}


def trace_url() -> str:
    """기록을 보낼 주소다. 플러그인 정책 주소가 없거나 http(s) 가 아니면 빈 글이다."""
    policy = os.environ.get(POLICY_URL_ENV, "").strip().rstrip("/")
    if not policy:
        return ""
    parsed = urllib.parse.urlsplit(policy)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        return ""
    return urllib.parse.urljoin(policy, TRACE_PATH)


def sign_trace(key: str, kind: str, root_session_id: str, session_id: str, tool_call_id: str) -> str:
    """실행 기록의 서명을 소문자 16진수로 돌려준다. key 는 `_fos_ctx` 와 같다."""
    message = "\n".join([TRACE_VERSION, kind, root_session_id, session_id, tool_call_id])
    return hmac.new(key.encode("utf-8"), message.encode("utf-8"), hashlib.sha256).hexdigest()


def build_tool_record(tool_name, session_id, tool_call_id, duration_ms, status, error_type, error_message,
                      ended_at: float) -> dict:
    """hook 인자에서 보낼 칸만 뽑는다. 루트 session 과 서명은 보내는 스레드가 채운다."""
    status = status if isinstance(status, str) and status else "ok"
    failed = status != "ok"
    duration = duration_ms if isinstance(duration_ms, (int, float)) and not isinstance(duration_ms, bool) else None
    message = error_message if failed and isinstance(error_message, str) and error_message.strip() else None
    kind = error_type if failed and isinstance(error_type, str) and error_type.strip() else None
    return {
        "kind": TOOL_KIND,
        "session_id": session_id,
        "tool_call_id": tool_call_id if isinstance(tool_call_id, str) else "",
        "tool_name": tool_name if isinstance(tool_name, str) else None,
        # Hermes 는 잴 수 없을 때도 0 을 넘긴다. 0 은 모름으로 보낸다.
        "duration_ms": int(duration) if duration is not None and duration > 0 else None,
        "status": status,
        "error_type": kind[:ERROR_TYPE_LIMIT] if kind else None,
        "error_message": message[:ERROR_MESSAGE_LIMIT] if message else None,
        "ended_at": ended_at,
    }


def post_tool_call(tool_name=None, session_id="", tool_call_id="", duration_ms=0, status=None, error_type=None,
                   error_message=None, **_):
    """기록을 대기열에 넣고 바로 돌아간다. 무엇이 잘못돼도 로그만 남긴다. 인자와 결과는 읽지 않는다."""
    try:
        if not isinstance(session_id, str) or not session_id:
            return
        url = trace_url()
        if not url:
            return
        token = _read_token()
        if not token:
            return
        record = build_tool_record(tool_name, session_id, tool_call_id, duration_ms, status, error_type,
                                   error_message, time.time())
        _ensure_worker()
        _queue.put_nowait((url, token, str(_state_db_path()), record))
    except queue.Full:
        _log_once("queue_full", "fos-ctx: 실행 기록 대기열이 가득 차 기록을 버렸다")
    except Exception as exc:  # noqa: BLE001 - hook 은 도구 루프를 막지 않는다
        _log_once("hook:" + type(exc).__name__, "fos-ctx: 실행 기록을 만들지 못했다: %s" % type(exc).__name__)


def _ensure_worker():
    """보내는 스레드를 띄운다. 프로세스가 갈라졌으면(fork) 그 프로세스에서 다시 띄운다."""
    global _worker, _worker_pid
    with _worker_lock:
        if _worker is not None and _worker.is_alive() and _worker_pid == os.getpid():
            return
        _worker_pid = os.getpid()
        _worker = threading.Thread(target=_run, name="fos-ctx-run-trace", daemon=True)
        _worker.start()


def _run():
    while True:
        items = [_queue.get()]
        deadline = time.monotonic() + FLUSH_DELAY
        while len(items) < QUEUE_LIMIT:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            try:
                items.append(_queue.get(timeout=remaining))
            except queue.Empty:
                break
        try:
            _send_all(items)
        except Exception as exc:  # noqa: BLE001 - 스레드가 죽으면 다음 기록부터 다시 띄운다
            _log_once("send:" + type(exc).__name__, "fos-ctx: 실행 기록을 보내지 못했다: %s" % type(exc).__name__)


def _send_all(items):
    groups: dict[tuple[str, str], list[tuple[str, dict]]] = {}
    for url, token, db_path, record in items:
        groups.setdefault((url, token), []).append((db_path, record))
    for (url, token), entries in groups.items():
        key = signing_key(token)
        roots: dict[tuple[str, str], str] = {}
        signed = [_signed(key, db_path, record, roots) for db_path, record in entries]
        for batch in _batches(signed):
            outcome = _post(url, token, {"v": 1, "records": batch})
            if outcome is not None:
                _log_once("post:" + outcome, "fos-ctx: 실행 기록 %d개를 버렸다: %s" % (len(batch), outcome))


def _signed(key: str, db_path: str, record: dict, roots: dict) -> dict:
    session = record["session_id"]
    cache_key = (db_path, session)
    if cache_key not in roots:
        try:
            roots[cache_key] = root_session(session, db_path=db_path)
        except sqlite3.Error:
            # 최상위 run 이면 맞는 값이고, 하위 에이전트라면 Control Plane 이 실행을 찾지 못해 버린다.
            roots[cache_key] = session
    root = roots[cache_key]
    return {**record, "root_session_id": root,
            "sig": sign_trace(key, record["kind"], root, session, record["tool_call_id"])}


def _batches(records):
    batch, chars = [], 0
    for record in records:
        size = len((record.get("error_message") or "").encode("utf-8"))
        if batch and (len(batch) >= BATCH_LIMIT or chars + size > BATCH_ERROR_BYTES):
            yield batch
            batch, chars = [], 0
        batch.append(record)
        chars += size
    if batch:
        yield batch


def _post(url: str, token: str, body: dict):
    """한 번만 보낸다. 성공이면 None, 실패면 원인의 종류다. 토큰과 본문은 로그에 남기지 않는다."""
    request = urllib.request.Request(
        url, data=json.dumps(body, ensure_ascii=False).encode("utf-8"), method="POST",
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + token},
    )
    try:
        with urllib.request.urlopen(request, timeout=SEND_TIMEOUT) as response:
            return None if 200 <= response.status < 300 else "HTTP %d" % response.status
    except urllib.error.HTTPError as exc:
        exc.close()
        return "HTTP %d" % exc.code
    except (urllib.error.URLError, OSError) as exc:
        return type(exc).__name__


def _log_once(key: str, message: str):
    """같은 실패는 `LOG_INTERVAL` 에 한 번만 남기고, 그 사이 거른 수를 다음 줄에 붙인다."""
    now = time.monotonic()
    with _log_lock:
        last = _last_logged.get(key)
        if last is not None and now - last < LOG_INTERVAL:
            _suppressed[key] = _suppressed.get(key, 0) + 1
            return
        _last_logged[key] = now
        skipped = _suppressed.pop(key, 0)
    logger.warning(message + (" (그 사이 %d번 더)" % skipped if skipped else ""))
