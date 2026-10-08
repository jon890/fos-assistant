"""커넥터 카드의 `icon` 과 `link` 를 검증한다(ADR-20261008 connector-card).

아이콘은 신뢰하지 않는 plugin 이 준 파일이다. 고쳐 쓰지 않고, 규칙을 하나라도 어기면 거절한다.
화면이 `<img>` 로만 그리는 것이 실제 경계이고, 여기 SVG 검사는 그 앞의 두 번째 선이다.
같은 규칙을 Control Plane 이 다시 본다. 한쪽을 바꾸면 다른 쪽과 `docs/connectors.md` 의 「아이콘과 링크」 도 바꾼다.
"""

from __future__ import annotations

import base64
import pathlib
import urllib.parse

from .connector_schema import (
    ICON_MAX_BYTES,
    ICON_MEDIA_TYPES,
    ICON_PATH_RE,
    LINK_MAX_CHARS,
    PNG_SIGNATURE,
    SVG_FORBIDDEN_RE,
)


# Java 정규식의 `\s` 와 같은 ASCII 공백이다.
_ASCII_SPACE = " \t\r\n\f\v"


def _connector_icon(root: pathlib.Path, declared) -> dict | None:
    """선언한 아이콘 파일을 읽어 `{"media_type", "data"}` 로 돌려준다. 선언이 없으면 None, 틀리면 예외다.

    `data` 는 파일 내용의 base64 다. 예외 글에 경로와 파일 내용을 싣지 않는다. 로그로 나가기 때문이다.
    """
    if declared is None:
        return None
    if not isinstance(declared, str) or not ICON_PATH_RE.fullmatch(declared) or ".." in declared.split("/"):
        raise ValueError("icon 은 plugin 디렉터리 기준 상대 경로다")
    media_type = ICON_MEDIA_TYPES.get(pathlib.PurePosixPath(declared).suffix)
    if media_type is None:
        raise ValueError("icon 은 .svg 나 .png 파일이다")
    path = root / declared
    # `root` 는 링크가 없음을 앞에서 확인했다. 그러므로 푼 경로가 같으면 경로의 어느 조각도 링크가 아니다.
    if path.resolve() != path or not path.is_relative_to(root) or not path.is_file():
        raise ValueError("icon 이 plugin 안의 링크 없는 파일이 아니다")
    with path.open("rb") as handle:
        # 상한보다 한 바이트 더 읽어, 큰 파일을 끝까지 읽지 않고 넘침을 안다.
        data = handle.read(ICON_MAX_BYTES + 1)
    if not data or len(data) > ICON_MAX_BYTES:
        raise ValueError("icon 파일은 1 바이트 이상 %d 바이트 이하다" % ICON_MAX_BYTES)
    if media_type == "image/png" and not data.startswith(PNG_SIGNATURE):
        raise ValueError("icon 의 PNG 서명이 없다")
    if media_type == "image/svg+xml" and not _svg_is_safe(data):
        raise ValueError("icon 의 SVG 가 허용한 형식이 아니다")
    return {"media_type": media_type, "data": base64.b64encode(data).decode("ascii")}


def _svg_starts_with_root(text: str) -> bool:
    """BOM, 공백, XML 선언, 주석을 앞에서부터 건너뛴 뒤 `<svg` 요소가 오는지 본다.

    정규식을 쓰지 않고 한 번 훑는다. `(<!--.*?-->\\s*)*` 같은 식은 주석이 많은 입력에서 되추적으로 시간이 지수적으로 는다.
    """
    position = 1 if text.startswith("\ufeff") else 0
    while True:
        start = position
        while position < len(text) and text[position] in _ASCII_SPACE:
            position += 1
        if text.startswith("<?xml", position):
            end = text.find("?>", position + 5)
            if end < 0:
                return False
            position = end + 2
        elif text.startswith("<!--", position):
            end = text.find("-->", position + 4)
            if end < 0:
                return False
            position = end + 3
        if position == start:
            break
    return (text[position:position + 4].lower() == "<svg"
            and position + 4 < len(text) and text[position + 4] in _ASCII_SPACE + ">/")


def _svg_is_safe(data: bytes) -> bool:
    """엄격한 UTF-8 이고, 맨 앞 요소가 `<svg` 이며, 금지 목록에 걸리는 글이 없으면 참이다."""
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        return False
    return _svg_starts_with_root(text) and SVG_FORBIDDEN_RE.search(text) is None


def _connector_link(declared) -> str | None:
    """선언한 링크를 그대로 돌려준다. 선언이 없으면 None, 틀리면 예외다. 예외 글에 링크를 싣지 않는다."""
    if declared is None:
        return None
    if (not isinstance(declared, str) or len(declared) > LINK_MAX_CHARS
            or any(char.isspace() or ord(char) < 0x20 or 0x7f <= ord(char) <= 0x9f for char in declared)):
        raise ValueError("link 는 공백과 제어 문자가 없는 %d자 이하의 글이다" % LINK_MAX_CHARS)
    try:
        parts = urllib.parse.urlsplit(declared)
        hostname, username, password = parts.hostname, parts.username, parts.password
    except ValueError:
        raise ValueError("link 를 URL 로 읽지 못한다") from None
    # `urlsplit` 은 scheme 을 소문자로 바꾼다. Control Plane 은 원문 그대로 `https` 인지 보므로 원문의 앞부분도 본다.
    if (parts.scheme != "https" or not declared.startswith("https:") or not hostname
            or username is not None or password is not None):
        raise ValueError("link 는 사용자 정보가 없는 https 주소다")
    return declared
