"""그림을 고칠 바탕과 참고 사진을 Control Plane 에서 받아 `image_generate` 의 인자를 사본 경로로 바꾼다.

사용자가 대화에 보낸 링크는 `link:<주소>` 로 가리키고, Control Plane 이 대화에 있는 주소인지 확인한 뒤 받는다
(fos-assistant `docs/adr/ADR-20261120-link-image-source.md`). 받은 주소는 그림 올리기의 `source_links` 로 남긴다.

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
from .next_step import alternative, ask_user

TOOL = "image_generate"
SOURCE_PATH = "image-source"
# Control Plane 의 `ImageSourceRef` 가 같은 머리를 읽는다. `ArtifactService.IMAGE_RULES` 가 모델에게 같은 글자로 안내한다.
ATTACHMENT_PREFIX = "attachment:"
GENERATED_PREFIX = "generated:"
LINK_PREFIX = "link:"
# 받기 경로가 링크 가리킴의 받은 주소를 돌려주는 응답 머리다. Control Plane 의 `ImageSourceController.LINK_HEADER` 와 같다.
LINK_HEADER = "X-Image-Source-Link"
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

# 문제 줄마다 끝 줄(`next_step.py`)이 붙는다. 글 전체는 마지막 문제의 끝 줄로 끝난다.
NOT_REFERENCE = alternative("바탕과 참고 사진에는 attachment:<첨부 번호>, generated:<그림 제목>, link:<사용자가 보낸 주소> 만 쓴다. "
                            "파일 경로와 맨 주소는 쓰지 않는다", "attachment:, generated:, link: 가리킴으로 고쳐 다시 부른다")
TOO_MANY = alternative("한 번에 쓸 수 있는 사진은 %d장까지다" % MAX_SOURCES, "사진을 %d장 이하로 줄여 다시 부른다" % MAX_SOURCES)
UNKNOWN = alternative("이 대화에 보낸 사진, 링크나 이 대화에서 만든 그림이 아니다",
                      "이 대화의 첨부, 사용자가 보낸 링크, 만든 그림 가운데 맞는 가리킴을 찾아 다시 부른다")
GONE = ask_user("지웠거나 보관 기간이 지나 쓸 수 없다")
# 만든 그림이 지워졌을 때다. 같은 인물의 다음 장면이면 이 그림을 빼고 인물 줄만으로 다시 그리게 한다(fos-assistant 스펙 generated-image REQ25).
GONE_GENERATED = alternative("지웠거나 보관 기간이 지나 쓸 수 없다",
                             "같은 인물의 다음 장면을 그리던 중이면 이 그림을 빼고 인물 줄만으로 image_generate 를 다시 부르고, "
                             "앞 그림을 참고하지 못했다고 답에 적는다. 그런 장면이 아니면 사용자에게 바로 알린다")
LINK_REASONS = {
    "IMAGE_SOURCE_LINK_BLOCKED": ask_user("열 수 없는 주소다"),
    "IMAGE_SOURCE_LINK_NO_IMAGE": alternative("이 링크에서 쓸 수 있는 사진을 찾지 못했다", "다른 방법이 없으니 해 본 것을 알리고 사진을 직접 올려 달라고 묻는다"),
    "IMAGE_SOURCE_LINK_TOO_LARGE": alternative("사진이 너무 크다", "다른 방법이 없으니 해 본 것을 알리고 더 작은 사진을 올려 달라고 묻는다"),
    "IMAGE_SOURCE_LINK_FAILED": alternative("링크를 열지 못했다. 쇼핑몰이 막았을 수 있다", "다른 방법이 없으니 해 본 것을 알리고 사진을 직접 올려 달라고 묻는다"),
}
FAILED = alternative("사진을 받지 못했다", "다른 방법이 없으니 잠시 뒤 다시 해 달라고 알린다")
# 링크를 쓰지 못했을 때 그 줄의 끝 줄 앞에 넣는다. 같은 인물의 다음 장면은 앞 그림만으로 다시 그린다(fos-assistant 스펙 generated-image DECISION52).
LINK_NEXT_SCENE = ("같은 인물의 다음 장면을 그리던 중이고 앞 그림(generated:)은 쓸 수 있으면 이 링크를 빼고 앞 그림만 참고로 "
                   "image_generate 를 다시 부르고, 링크 사진을 쓰지 못했다고 답에 적는다. 그런 장면이 아니면 아래 줄을 따른다")
OLD_SERVER = ask_user("이 서버는 아직 사진을 바탕으로 그림을 고칠 수 없다")
NO_CONTEXT = alternative("이 호출에서는 사진을 받을 수 없다", "대화에서 직접 image_generate 를 부른다")
HEAD = "fos-ctx: 바탕이나 참고로 가리킨 사진을 쓸 수 없어 그림을 만들지 않았다. 줄마다의 다음 방법이나 바로 알림을 따른다."

_copies: dict = {}
# 호출마다 받은 링크 주소다. 사본과 같은 키로 기억하고 함께 잊는다.
_links: dict = {}
_copies_lock = threading.Lock()


def wants_sources(args) -> bool:
    """바탕이나 참고 사진을 적은 호출인가. 두 칸 가운데 하나라도 비지 않았으면 참이다."""
    if not isinstance(args, dict):
        return False
    return any(args.get(key) not in (None, "", []) for key in ("image_url", "reference_image_urls"))


def with_next_scene(problem: str) -> str:
    """링크 문제 글의 끝 줄 앞에 다음 장면 안내를 넣는다. 글은 여전히 끝 줄로 끝난다."""
    body, end = problem.rsplit("\n", 1)
    return body + ". " + LINK_NEXT_SCENE + "\n" + end


def message(problems) -> str:
    """막을 때 모델이 받는 글이다. 문제마다 한 줄이다."""
    # 끝 줄이 붙어 여러 줄인 문제 글은 둘째 줄부터 들여 쓴다
    return "\n".join([HEAD, *("- " + line.replace("\n", "\n  ") for line in problems)])


def _echo(value) -> str:
    text = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False)
    return text if len(text) <= MAX_ECHO_CHARS else text[:MAX_ECHO_CHARS] + "…"


def _is_reference(value) -> bool:
    if not isinstance(value, str):
        return False
    text = value.strip()
    if text.startswith(ATTACHMENT_PREFIX):
        return text[len(ATTACHMENT_PREFIX):].strip().isdigit()
    if text.startswith(LINK_PREFIX):
        return bool(text[len(LINK_PREFIX):].strip())
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
    """가리킴 하나를 받는다. `(바이트, 형식, 문제, 받은 링크)` 이고 받았으면 문제가 None 이다. 404 면 문제가 `OLD_SERVER` 다.

    받은 링크는 링크 가리킴일 때 Control Plane 이 응답 머리로 돌려준 주소다. 그 밖에는 None 이다.
    """
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        return None, None, FAILED, None
    body = json.dumps({"_fos_ctx": ctx, "source": source}, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        url, data=body, method="POST",
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + token},
    )
    try:
        with urllib.request.urlopen(request, timeout=remaining) as response:
            content_type = (response.headers.get("Content-Type") or "").split(";")[0].strip().lower()
            link = response.headers.get(LINK_HEADER)
            raw = response.read(MAX_BYTES + 1)
    except urllib.error.HTTPError as exc:
        try:
            status, answer = exc.code, exc.read(4096)
        finally:
            exc.close()
        if status == 404:
            return None, None, OLD_SERVER, None
        try:
            code = json.loads(answer.decode("utf-8")).get("code")
        except (ValueError, AttributeError):
            code = None
        if code == "IMAGE_SOURCE_UNKNOWN":
            return None, None, UNKNOWN, None
        if code == "IMAGE_SOURCE_GONE":
            return None, None, GONE_GENERATED if source.startswith(GENERATED_PREFIX) else GONE, None
        if code in LINK_REASONS:
            return None, None, LINK_REASONS[code], None
        logger.warning("fos-ctx: 그림 바탕 사진을 받지 못했다: HTTP %d", status)
        return None, None, FAILED, None
    if content_type not in EXTENSIONS or not raw or len(raw) > MAX_BYTES:
        logger.warning("fos-ctx: 그림 바탕 사진의 답이 쓸 수 없는 모양이다")
        return None, None, FAILED, None
    if source.startswith(LINK_PREFIX) and not (isinstance(link, str) and link.startswith("https://")):
        logger.warning("fos-ctx: 링크 사진의 답에 받은 주소가 없다")
        return None, None, FAILED, None
    return raw, content_type, None, (link if source.startswith(LINK_PREFIX) else None)


def _remember(session_id: str, tool_call_id: str, paths, links=()) -> None:
    with _copies_lock:
        while len(_copies) >= MAX_CALLS:
            oldest = next(iter(_copies))
            _copies.pop(oldest)
            _links.pop(oldest, None)
        _copies[(session_id, tool_call_id)] = list(paths)
        if links:
            _links[(session_id, tool_call_id)] = list(links)


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
        _links.pop((session_id or "", tool_call_id or ""), None)
    _remove(paths)


def links_for(session_id, tool_call_id) -> list:
    """그 호출이 받은 링크 주소를 쓴 순서대로 돌려준다. 결과 hook 이 사본을 지우기 전에 읽는다. 없으면 빈 목록이다."""
    with _copies_lock:
        return list(_links.get((session_id or "", tool_call_id or ""), []))


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
    copies, written, links = {}, [], []
    try:
        problems = _fetch_all(url, token, ctx, ([base] if base else []) + refs, folder, deadline, copies, written, links)
    except BaseException:
        # 쓰기가 실패해도 이미 쓴 사본을 남기지 않는다. 막는 글은 부르는 쪽이 만든다.
        _remove(written)
        raise
    if problems is OLD_SERVER:
        _remove(written)
        return message([OLD_SERVER])
    if problems:
        _remove(written)
        return message(problems)
    _remember(session_id, tool_call_id, written, links)
    changed = {"reference_image_urls": [copies[value.strip()] for value in refs]}
    if base:
        changed["image_url"] = copies[base.strip()]
    elif "image_url" in args:
        changed["image_url"] = None
    return changed


def _fetch_all(url, token, ctx, sources, folder, deadline, copies, written, links):
    """가리킴을 차례로 받아 사본을 쓴다. 문제 목록을 돌려주고, 옛 Control Plane 이면 `OLD_SERVER` 를 돌려준다.

    링크 가리킴의 받은 주소는 `links` 에 쓴 순서대로 더한다.
    """
    problems = []
    for source in sources:
        key = source.strip()
        if key in copies:
            continue
        raw, content_type, problem, link = _fetch(url, token, ctx, key, deadline)
        if problem == OLD_SERVER:
            return OLD_SERVER
        if problem:
            problems.append(key + ": " + (with_next_scene(problem) if key.startswith(LINK_PREFIX) else problem))
            copies[key] = None
            continue
        path = folder / (secrets.token_hex(16) + EXTENSIONS[content_type])
        written.append(str(path))
        path.write_bytes(raw)
        copies[key] = str(path)
        if link:
            links.append(link)
    return problems
