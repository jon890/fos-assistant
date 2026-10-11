"""신뢰된 agent 바인딩의 디렉터리에 본문 없는 요청 진단만 쓴다."""

import json
import os
from pathlib import Path
import secrets
from typing import Callable, Literal, TypedDict

from .request_counts import CountData, count_request

DestinationResolver = Callable[[object], str | None]
Reason = Literal["preflight", "non_retryable_client_error", "max_retries_exhausted", "unknown"]
ErrorKind = Literal["none", "timeout", "connection", "permission", "value", "type", "io", "other"]
_REASONS = ("preflight", "non_retryable_client_error", "max_retries_exhausted")


class Diagnostic(TypedDict):
    reason: Reason
    error: ErrorKind
    counts: CountData


def _error_kind(error) -> ErrorKind:
    if error is None:
        return "none"
    # SDK 예외의 property와 원문을 읽지 않는다. 임의 하위 타입은 other다.
    categories: tuple[tuple[type[Exception], ErrorKind], ...] = (
        (TimeoutError, "timeout"), (ConnectionError, "connection"), (PermissionError, "permission"),
        (ValueError, "value"), (TypeError, "type"), (OSError, "io"))
    for exception_type, category in categories:
        if type(error) is exception_type:
            return category
    return "other"


def _open_directory(directory):
    if (os.name != "posix" or not hasattr(os, "O_NOFOLLOW") or not hasattr(os, "O_DIRECTORY")
            or os.open not in os.supports_dir_fd or os.unlink not in os.supports_dir_fd):
        raise OSError("bodyless_unsupported")
    if type(directory) is not str or not directory.startswith("/") or len(directory) > 4096:
        raise ValueError("bodyless_destination")
    components = directory.split("/")[1:]
    if not components or len(components) > 128 or any(part in ("", ".", "..") for part in components):
        raise ValueError("bodyless_destination")
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC
    current = os.open("/", flags)
    try:
        for component in components:
            child = os.open(component, flags, dir_fd=current)
            os.close(current)
            current = child
        return current
    except BaseException:
        os.close(current)
        raise


def _write(directory, diagnostic) -> Path:
    # 직렬화 대상은 새로 만든 고정 enum과 숫자뿐이다.
    encoded = json.dumps(diagnostic, ensure_ascii=True, separators=(",", ":")).encode("ascii")
    parent = _open_directory(directory)
    leaf = ""
    created = False
    fd = None
    try:
        leaf = "request_diagnostic_" + secrets.token_hex(16) + ".json"
        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_CLOEXEC
        fd = os.open(leaf, flags, 0o600, dir_fd=parent)
        created = True
        stream = os.fdopen(fd, "wb")
        fd = None  # 성공한 fdopen이 stream에 FD 소유를 넘긴다.
        with stream:
            os.fchmod(stream.fileno(), 0o600)
            stream.write(encoded)
            stream.flush()
            os.fsync(stream.fileno())
        return Path(directory) / leaf
    except BaseException:
        if created:
            try:
                os.unlink(leaf, dir_fd=parent)
            except OSError:
                pass
        raise
    finally:
        try:
            if fd is not None:
                os.close(fd)
        finally:
            os.close(parent)


def make_writer(resolver: DestinationResolver):
    """설치 helper만 호출한다. resolver는 private bootstrap이 검증한 바인딩을 읽는다."""
    def dump_api_request_debug(agent, api_kwargs, *, reason, error=None) -> Path | None:
        try:
            directory = resolver(agent)
            if directory is None:
                return None
            diagnostic: Diagnostic = {
                "reason": reason if type(reason) is str and reason in _REASONS else "unknown",
                "error": _error_kind(error),
                "counts": count_request(api_kwargs),
            }
            return _write(directory, diagnostic)
        except Exception:
            # 오류 원문, agent 속성, stdout 옵션과 기존 writer에 접근하지 않는다.
            return None
    return dump_api_request_debug
