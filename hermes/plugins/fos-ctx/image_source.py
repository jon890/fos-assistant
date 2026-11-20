"""그림을 고칠 바탕과 참고 사진을 Control Plane 에서 받아 `image_generate` 의 인자를 사본 경로로 바꾼다.

계약의 정본은 fos-assistant `docs/adr/ADR-20261120-image-source.md` 와 `hermes/plugins/fos-ctx/README.md` 의
「그림을 고칠 사진을 받는다」 다. 모델은 `image_url` 과 `reference_image_urls` 에 `attachment:<첨부 번호>` 나
`generated:<제목>` 만 적는다. 실행 공간의 경로를 그대로 두면 그 사용자의 다른 대화의 사진까지 읽을 수 있어서다.
"""

from __future__ import annotations

import json
import os
import pathlib
import secrets
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

from .context import _read_token, build_context, logger
from .tool_guard import guard_url

TOOL = "image_generate"
SOURCE_PATH = "image-source"
# Control Plane 의 `ImageSourceRef` 가 같은 머리를 읽는다. `ArtifactService.IMAGE_RULES` 가 모델에게 같은 글자로 안내한다.
ATTACHMENT_PREFIX = "attachment:"
GENERATED_PREFIX = "generated:"
# Hermes 의 openai-codex 이미지 plugin 이 받는 입력 장수(`_MAX_REFERENCE_IMAGES`)다. 넘는 것을 Hermes 는 말없이 버린다.
# 이 저장소가 정한 값이 아니다. `hermes/tests/test_generated_image_contract.py` 가 Hermes 를 올릴 때 다시 본다.
MAX_SOURCES = 16
# 한 호출의 모든 가리킴을 받는 데 쓰는 시간이다. Hermes 의 callback 제한(기본 30초) 안에서 도구 판정과 함께 끝내야 한다.
FETCH_BUDGET = 15.0
# Control Plane 이 주는 원본의 상한(사진 20MiB, 만든 그림 올리기 상한 20MiB)이다. 넘으면 받지 않는다.
MAX_BYTES = 20 * 1024 * 1024
# 사본을 두는 자리다. Hermes 홈의 `cache/` 아래라 실행 공간이 있는 profile 도 호스트에서 읽는다.
COPY_PARTS = ("cache", "fos-image-sources")
# 결과 hook 이 돌지 못해 남은 사본을 지우는 나이다.
STALE_SECONDS = 3600
# 결과 hook 이 사본을 지울 때까지 기억하는 호출 수의 상한이다. 넘으면 오래된 것부터 잊고 사본은 나이로 지운다.
MAX_CALLS = 256
# 막는 글에 옮겨 적는 값의 길이다. 모델이 적은 긴 경로를 그대로 되돌려 주지 않는다.
MAX_ECHO_CHARS = 80
EXTENSIONS = {"image/png": ".png", "image/jpeg": ".jpg", "image/gif": ".gif", "image/webp": ".webp"}

NOT_REFERENCE = "바탕과 참고 사진에는 attachment:<첨부 번호> 나 generated:<그림 제목> 만 쓴다. 파일 경로와 주소는 쓰지 않는다"
TOO_MANY = "한 번에 쓸 수 있는 사진은 %d장까지다. 사용자에게 줄여 달라고 한다" % MAX_SOURCES
UNKNOWN = "이 대화에 보낸 사진이나 이 대화에서 만든 그림이 아니다"
GONE = "지웠거나 보관 기간이 지나 쓸 수 없다"
FAILED = "사진을 받지 못했다. 잠시 뒤 다시 부탁하라고 안내한다"
OLD_SERVER = "이 서버는 아직 사진을 바탕으로 그림을 고칠 수 없다"
NO_CONTEXT = "이 호출에서는 사진을 받을 수 없다. 대화에서 직접 image_generate 를 부른다"
HEAD = "fos-ctx: 바탕이나 참고로 가리킨 사진을 쓸 수 없다."
TAIL = "그림을 만들지 않았다. 사용자에게 어느 사진을 왜 쓸 수 없는지 알린다."

_copies: dict = {}
_copies_lock = threading.Lock()


def wants_sources(args) -> bool:
    """바탕이나 참고 사진을 적은 호출인가. 두 칸 가운데 하나라도 비지 않았으면 참이다."""
    if not isinstance(args, dict):
        return False
    return any(args.get(key) not in (None, "", []) for key in ("image_url", "reference_image_urls"))


def message(problems) -> str:
    """막을 때 모델이 받는 글이다. 문제마다 한 줄이다."""
    return "\n".join([HEAD, *("- " + line for line in problems), TAIL])


def _echo(value) -> str:
    text = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False)
    return text if len(text) <= MAX_ECHO_CHARS else text[:MAX_ECHO_CHARS] + "…"


def _is_reference(value) -> bool:
    if not isinstance(value, str):
        return False
    text = value.strip()
    if text.startswith(ATTACHMENT_PREFIX):
        return text[len(ATTACHMENT_PREFIX):].strip().isdigit()
    return text.startswith(GENERATED_PREFIX) and bool(text[len(GENERATED_PREFIX):].strip())


def _sources(args):
    """`(바탕, 참고 목록, 문제 목록)` 이다. 참고는 글 하나도 받는다. Hermes 의 `normalize_reference_images` 와 같다."""
    base = args.get("image_url")
    refs = args.get("reference_image_urls")
    if isinstance(refs, str):
        refs = [refs]
    problems = []
    if base in (None, ""):
        base = None
    elif not _is_reference(base):
        problems.append(_echo(base) + ": " + NOT_REFERENCE)
    if refs in (None, ""):
        refs = []
    if not isinstance(refs, list):
        problems.append(_echo(refs) + ": " + NOT_REFERENCE)
        refs = []
    for value in refs:
        if not _is_reference(value):
            problems.append(_echo(value) + ": " + NOT_REFERENCE)
    if (1 if base else 0) + len(refs) > MAX_SOURCES:
        problems.append(TOO_MANY)
    return base, refs, problems


def _copy_dir() -> pathlib.Path:
    from hermes_constants import get_hermes_home

    return pathlib.Path(get_hermes_home()).joinpath(*COPY_PARTS)


def _sweep(folder: pathlib.Path) -> None:
    """결과 hook 이 돌지 못해 남은 오래된 사본을 지운다. 지우지 못해도 받기는 이어 간다."""
    limit = time.time() - STALE_SECONDS
    try:
        entries = list(folder.iterdir())
    except OSError:
        return
    for entry in entries:
        try:
            if entry.is_file() and entry.stat().st_mtime < limit:
                entry.unlink()
        except OSError:
            continue


def _fetch(url: str, token: str, ctx: dict, source: str, deadline: float):
    """가리킴 하나를 받는다. `(바이트, 형식, 문제)` 이고 받았으면 문제가 None 이다. 404 면 문제가 `OLD_SERVER` 다."""
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        return None, None, FAILED
    body = json.dumps({"_fos_ctx": ctx, "source": source}, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        url, data=body, method="POST",
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + token},
    )
    try:
        with urllib.request.urlopen(request, timeout=remaining) as response:
            content_type = (response.headers.get("Content-Type") or "").split(";")[0].strip().lower()
            raw = response.read(MAX_BYTES + 1)
    except urllib.error.HTTPError as exc:
        try:
            status, answer = exc.code, exc.read(4096)
        finally:
            exc.close()
        if status == 404:
            return None, None, OLD_SERVER
        try:
            code = json.loads(answer.decode("utf-8")).get("code")
        except (ValueError, AttributeError):
            code = None
        if code == "IMAGE_SOURCE_UNKNOWN":
            return None, None, UNKNOWN
        if code == "IMAGE_SOURCE_GONE":
            return None, None, GONE
        logger.warning("fos-ctx: 그림 바탕 사진을 받지 못했다: HTTP %d", status)
        return None, None, FAILED
    if content_type not in EXTENSIONS or not raw or len(raw) > MAX_BYTES:
        logger.warning("fos-ctx: 그림 바탕 사진의 답이 쓸 수 없는 모양이다")
        return None, None, FAILED
    return raw, content_type, None


def _remember(session_id: str, tool_call_id: str, paths) -> None:
    with _copies_lock:
        while len(_copies) >= MAX_CALLS:
            _copies.pop(next(iter(_copies)))
        _copies[(session_id, tool_call_id)] = list(paths)


def _remove(paths) -> None:
    for path in paths:
        try:
            os.unlink(path)
        except OSError:
            continue


def release(session_id, tool_call_id) -> None:
    """그 호출이 받은 사본을 지운다. 결과 hook 이 부른다. 받은 것이 없으면 아무것도 하지 않는다."""
    with _copies_lock:
        paths = _copies.pop((session_id or "", tool_call_id or ""), [])
    _remove(paths)


def prepare(args, session_id: str, tool_call_id: str):
    """가리킴을 받아 `modify` 에 넣을 인자를 돌려준다. 쓸 수 없으면 막는 글(str)이다. 받을 것이 없으면 None 이다.

    하나라도 받지 못하면 받은 사본을 지우고 막는다(스펙 generated-image DECISION32). 예외는 부르는 쪽이 잡아 막는다.
    """
    if not wants_sources(args):
        return None
    base, refs, problems = _sources(args)
    if problems:
        return message(problems)
    if not session_id or not tool_call_id:
        return message([NO_CONTEXT])
    ctx = build_context(TOOL, session_id, tool_call_id)
    url = urllib.parse.urljoin(guard_url(), SOURCE_PATH) if guard_url() else ""
    token = _read_token()
    if not ctx or not url or not token:
        logger.warning("fos-ctx: 그림 바탕 사진을 받을 맥락이나 주소, 토큰이 없다")
        return message([FAILED])
    folder = _copy_dir()
    folder.mkdir(mode=0o700, parents=True, exist_ok=True)
    _sweep(folder)
    deadline = time.monotonic() + FETCH_BUDGET
    copies, written = {}, []
    for source in ([base] if base else []) + refs:
        key = source.strip()
        if key in copies:
            continue
        raw, content_type, problem = _fetch(url, token, ctx, key, deadline)
        if problem == OLD_SERVER:
            _remove(written)
            return message([OLD_SERVER])
        if problem:
            problems.append(key + ": " + problem)
            copies[key] = None
            continue
        path = folder / (secrets.token_hex(16) + EXTENSIONS[content_type])
        path.write_bytes(raw)
        written.append(str(path))
        copies[key] = str(path)
    if problems:
        _remove(written)
        return message(problems)
    _remember(session_id, tool_call_id, written)
    changed = {"reference_image_urls": [copies[value.strip()] for value in refs]}
    if base:
        changed["image_url"] = copies[base.strip()]
    elif "image_url" in args:
        changed["image_url"] = None
    return changed
