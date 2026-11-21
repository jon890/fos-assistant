"""비서가 만든 그림을 Control Plane 에 올리고 모델에게 가는 결과에서 경로를 뺀다.

계약의 정본은 fos-assistant `docs/adr/ADR-20261119-generated-image.md` 의 「결정」 과
`hermes/plugins/fos-ctx/README.md` 의 「만든 그림을 올린다」 다. Hermes 의 `image_generate` 는 그림을 그 profile 의 캐시 폴더에 쓰고
모델에게 호스트 경로를 돌려준다. 그 경로가 답에 적히면 홈서버의 디렉터리 구조가 화면에 보인다.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import pathlib
import secrets
import sqlite3
import threading
import urllib.error
import urllib.parse
import urllib.request

from .context import CTX_VERSION, _read_token, logger, root_session, signing_key
from .tool_guard import guard_url
from .next_step import ask_user

TOOL = "image_generate"
VERSION = "v1-generated-image"
UPLOAD_PATH = "generated-image"
# 모델이 지시문 첫 줄에 적는 제목 줄의 머리다. Control Plane 의 `ArtifactService.IMAGE_RULES` 가 같은 글자로 안내한다.
TITLE_PREFIX = "제목:"
# Hermes 의 callback 제한(기본 30초)보다 짧게 둔다. 넘기면 Hermes 가 그 profile 의 hook 을 60초 억제한다.
UPLOAD_TIMEOUT = 15.0
# Control Plane 의 `assistant.artifact.generated-image-max-bytes` 기본값과 같다. 넘는 그림은 보내지 않는다.
MAX_BYTES = 20 * 1024 * 1024
# 그 profile 의 Hermes 홈 아래에서 `image_generate` 가 그림을 쓰는 폴더다. 이 밖의 경로는 읽지도 지우지도 않는다.
CACHE_PARTS = ("cache", "generated", "images")
# 뗀 제목을 결과 hook 이 꺼낼 때까지 두는 항목 수의 상한이다. 넘으면 오래된 것부터 버린다.
MAX_TITLES = 256
# Control Plane 이 받는 제목과 지시문의 길이 상한(500자, 32,000자)보다 짧게 자른다. 넘겨 보내면 그림까지 거절된다.
MAX_TITLE_CHARS = 200
MAX_PROMPT_CHARS = 30_000

SAVED_MESSAGE = "그림을 대화에 저장했다. 사용자의 화면에 자동으로 보인다. 답에 파일 경로나 MEDIA: 를 쓰지 않는다."
FAILED_MESSAGE = ask_user("그림을 만들었지만 대화에 저장하지 못했다. 사용자에게 그림을 보여 주지 못했다고 알리고 "
                          "잠시 뒤 다시 부탁하라고 안내한다.")

_titles: dict = {}
_titles_lock = threading.Lock()


def sign_generated_image(key: str, root_session_id: str, session_id: str, tool_call_id: str,
                         image_sha256: str, title: str, prompt: str, source_links=None, found_links=None) -> str:
    """그림 올리기의 서명을 소문자 16진수로 돌려준다. key 는 `_fos_ctx` 와 같다. 제목과 지시문은 글 그대로가 아니라 SHA-256 을 넣는다.

    참고한 링크가 있을 때만 주소를 줄바꿈으로 이은 글의 SHA-256 을 여덟째 줄로 더한다. 없으면 지금 일곱 줄 그대로다
    (fos-assistant ADR-20261120 / link-image-source). 그 가운데 비서가 찾은 주소가 있으면 같은 방법으로 아홉째 줄을 더한다
    (fos-assistant ADR-20261121 / found-link-image-source). 찾은 주소는 참고한 링크의 부분이라 링크 없이 오지 않는다.
    """
    lines = [
        VERSION, root_session_id, session_id, tool_call_id, image_sha256,
        hashlib.sha256(title.encode("utf-8")).hexdigest(), hashlib.sha256(prompt.encode("utf-8")).hexdigest(),
    ]
    if source_links:
        lines.append(hashlib.sha256("\n".join(source_links).encode("utf-8")).hexdigest())
        if found_links:
            lines.append(hashlib.sha256("\n".join(found_links).encode("utf-8")).hexdigest())
    message = "\n".join(lines)
    return hmac.new(key.encode("utf-8"), message.encode("utf-8"), hashlib.sha256).hexdigest()


def split_title(prompt):
    """지시문 첫 줄의 제목 줄을 뗀다. `(제목, 나머지 지시문)` 이고 제목 줄이 없으면 `("", 받은 글)` 이다."""
    if not isinstance(prompt, str):
        return "", ""
    first, separator, rest = prompt.lstrip().partition("\n")
    if not first.strip().startswith(TITLE_PREFIX) or not separator or not rest.strip():
        return "", prompt
    return first.strip()[len(TITLE_PREFIX):].strip(), rest.lstrip("\n")


def strip_title(args, session_id, tool_call_id):
    """`image_generate` 호출의 지시문에서 제목 줄을 뗀 `modify` 를 돌려준다. 뗄 것이 없으면 None 이다.

    떼지 않으면 이미지 서비스가 제목 글자를 그림에 그릴 수 있다. 뗀 제목은 결과 hook 이 꺼낼 때까지 프로세스 메모리에 둔다.
    """
    if not isinstance(args, dict):
        return None
    title, rest = split_title(args.get("prompt"))
    if not title:
        return None
    if session_id and tool_call_id:
        with _titles_lock:
            while len(_titles) >= MAX_TITLES:
                _titles.pop(next(iter(_titles)))
            _titles[(session_id, tool_call_id)] = title
    return {"action": "modify", "args": {"prompt": rest}}


def _cached_image(image):
    """결과의 경로가 이 profile 의 이미지 캐시 폴더 아래 일반 파일이면 링크를 푼 실제 경로를, 아니면 None 을 돌려준다."""
    if not isinstance(image, str) or not image.startswith("/"):
        return None
    from hermes_constants import get_hermes_home

    cache = pathlib.Path(get_hermes_home()).joinpath(*CACHE_PARTS).resolve()
    real = pathlib.Path(image).resolve()
    if cache not in real.parents or not real.is_file():
        return None
    return real


def _multipart(meta: dict, image: bytes):
    """`meta`(JSON)와 `image`(바이트) 두 조각의 multipart 본문과 Content-Type 을 만든다. 표준 라이브러리만 쓴다."""
    boundary = "fos" + secrets.token_hex(16)
    head = ("--%s\r\nContent-Disposition: form-data; name=\"meta\"\r\n"
            "Content-Type: application/json; charset=utf-8\r\n\r\n" % boundary).encode("utf-8")
    middle = ("\r\n--%s\r\nContent-Disposition: form-data; name=\"image\"; filename=\"image\"\r\n"
              "Content-Type: application/octet-stream\r\n\r\n" % boundary).encode("utf-8")
    tail = ("\r\n--%s--\r\n" % boundary).encode("utf-8")
    body = head + json.dumps(meta, ensure_ascii=False).encode("utf-8") + middle + image + tail
    return body, "multipart/form-data; boundary=" + boundary


def upload_url() -> str:
    """올릴 주소다. 플러그인 정책 주소의 마지막 조각을 바꾼 것이고, 없거나 http(s) 가 아니면 빈 글이다."""
    guard = guard_url()
    return urllib.parse.urljoin(guard, UPLOAD_PATH) if guard else ""


def _post(url: str, body: bytes, content_type: str, token: str):
    """한 번 POST 하고 `(상태 코드, 읽은 JSON)` 을 돌려준다. 본문이 JSON 이 아니면 둘째가 None 이다."""
    request = urllib.request.Request(
        url, data=body, method="POST",
        headers={"Content-Type": content_type, "Authorization": "Bearer " + token},
    )
    try:
        with urllib.request.urlopen(request, timeout=UPLOAD_TIMEOUT) as response:
            status, raw = response.status, response.read()
    except urllib.error.HTTPError as exc:
        exc.close()
        return exc.code, None
    try:
        return status, json.loads(raw.decode("utf-8"))
    except ValueError:
        return status, None


def _failed() -> str:
    return json.dumps({"success": False, "error": FAILED_MESSAGE}, ensure_ascii=False)


def publish(args, result, session_id, tool_call_id, source_links=None, found_links=None):
    """`image_generate` 의 성공 결과에서 그림을 올리고 모델이 받을 글을 돌려준다. 바꾸지 않을 결과면 None 이다.

    None 은 실패한 결과, 원격 주소, 캐시 폴더 밖 경로, 올리기 경로가 없는 옛 Control Plane(404)이다. 그 밖의 실패는 모두 경로 없는
    오류 글로 바꾼다. 예외를 던지면 Hermes 가 원래 결과(경로)를 모델에게 주므로 부르는 쪽이 모든 예외를 잡아 `_failed` 로 바꾼다.
    """
    with _titles_lock:
        remembered = _titles.pop((session_id, tool_call_id), "")
    try:
        parsed = json.loads(result) if isinstance(result, str) else None
    except ValueError:
        return None
    if not isinstance(parsed, dict) or not parsed.get("success"):
        return None
    path = _cached_image(parsed.get("host_image") or parsed.get("image"))
    if path is None:
        return None
    if not session_id or not tool_call_id:
        return _failed()
    url, token = upload_url(), _read_token()
    if not url or not token:
        logger.warning("fos-ctx: 만든 그림을 올릴 주소나 토큰이 없다")
        return _failed()
    if path.stat().st_size > MAX_BYTES:
        logger.warning("fos-ctx: 만든 그림이 올리기 상한을 넘는다")
        return _failed()
    image = path.read_bytes()
    # hook 이 받은 인자에 제목 줄이 남아 있으면 그것을 쓴다. Hermes 가 고친 인자를 주면 기억해 둔 제목을 쓴다.
    title, prompt = split_title(args.get("prompt") if isinstance(args, dict) else None)
    title = (title or remembered)[:MAX_TITLE_CHARS]
    prompt = prompt[:MAX_PROMPT_CHARS]
    try:
        root = root_session(session_id)
    except sqlite3.Error as exc:
        logger.warning("fos-ctx: state.db 에서 루트 session 을 찾지 못했다: %s", type(exc).__name__)
        root = session_id
    # 찾은 주소는 참고한 링크 안에 있는 것만 보낸다. Control Plane 이 링크 밖의 찾은 주소를 거절해 그림까지 잃지 않게 한다.
    found = [link for link in (found_links or []) if link in (source_links or [])]
    meta = {
        "v": CTX_VERSION, "root_session_id": root, "session_id": session_id, "tool_call_id": tool_call_id,
        "title": title, "prompt": prompt,
        "sig": sign_generated_image(signing_key(token), root, session_id, tool_call_id,
                                    hashlib.sha256(image).hexdigest(), title, prompt, source_links, found),
    }
    if source_links:
        meta["source_links"] = list(source_links)
    if found:
        meta["found_links"] = found
    body, content_type = _multipart(meta, image)
    status, answer = _post(url, body, content_type, token)
    if status == 404:
        # 이 경로가 없는 옛 Control Plane 이다. 플러그인을 먼저 배포한 동안 지금과 같은 동작을 남긴다(rule-66).
        return None
    if status != 200 or not isinstance(answer, dict):
        logger.warning("fos-ctx: 만든 그림 올리기가 받아지지 않았다: HTTP %d", status)
        return _failed()
    try:
        os.unlink(path)
    except OSError as exc:
        logger.warning("fos-ctx: 올린 그림의 캐시 파일을 지우지 못했다: %s", type(exc).__name__)
    saved = answer.get("title")
    return json.dumps(
        {"success": True, "saved": True, "title": saved if isinstance(saved, str) else title, "message": SAVED_MESSAGE},
        ensure_ascii=False)
