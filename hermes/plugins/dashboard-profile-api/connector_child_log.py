"""승인한 실행과 금융 준비가 띄운 플러그인 자식의 표준 오류에서 진단 줄만 골라 그 profile 의 MCP 표준 오류 로그에 더한다.

Hermes 가 띄운 MCP 서버의 표준 오류는 그 profile 의 `logs/mcp-stderr.log` 에 모인다(hermes-contract.md 「MCP 자식의 표준 오류」).
대시보드가 띄운 자식은 Hermes 를 거치지 않으므로 이 plugin 이 같은 파일에 더한다. 자식의 출력에는 칸 값이나 인자가 섞일 수 있어
원문을 옮기지 않고, 아래 모양에 맞는 줄만 옮긴다. 나머지는 버린다.

- `{"<이름>_diagnostic": {...}}` 한 칸짜리 JSON. 안쪽 값은 null, 낱말(`DIAGNOSTIC_WORD_RE`), 낱말 목록뿐이다
- 실행기(`connector_launcher.py`)의 `connector-launcher: <이유 낱말>` 줄
- 대문자 코드 한 낱말 줄(예: 서버가 뜨지 못했다는 고정 코드)

연결 확인과 선택지 호출(`call`)은 바인딩 주인의 profile 이 없어 지금처럼 버린다.
"""

from __future__ import annotations

import contextlib
import json
import os
import pathlib
import re
import tempfile
from typing import Optional

from .common import logger

# 진단 줄을 더할 파일. profile 디렉터리 아래의 상대 경로다. Hermes 가 같은 파일에 MCP 자식의 표준 오류를 모은다.
CHILD_LOG = pathlib.Path("logs") / "mcp-stderr.log"

DIAGNOSTIC_KEY_RE = re.compile(r"[a-z][a-z0-9_]{0,40}_diagnostic")
DIAGNOSTIC_WORD_RE = re.compile(r"[a-z0-9_.]{1,60}")
LAUNCHER_LINE_RE = re.compile(r"connector-launcher: [A-Za-z_]{1,64}")
CODE_LINE_RE = re.compile(r"[A-Z][A-Z0-9_]{0,63}")

# 한 실행에서 읽는 표준 오류의 바이트, 옮기는 줄 수, 줄 하나의 길이 상한이다.
READ_MAX = 65536
LINES_MAX = 50
LINE_MAX = 1024
LIST_MAX = 10
ENTRY_KEYS_MAX = 10


def _word(value) -> bool:
    return isinstance(value, str) and DIAGNOSTIC_WORD_RE.fullmatch(value) is not None


def _diagnostic_line(line: str) -> bool:
    """옮겨도 되는 줄인가. 맞지 않으면 원문을 보지 않고 버린다."""
    if len(line) > LINE_MAX:
        return False
    if LAUNCHER_LINE_RE.fullmatch(line) or CODE_LINE_RE.fullmatch(line):
        return True
    try:
        parsed = json.loads(line)
    except ValueError:
        return False
    if not isinstance(parsed, dict) or len(parsed) != 1:
        return False
    (key, entry), = parsed.items()
    if not DIAGNOSTIC_KEY_RE.fullmatch(key) or not isinstance(entry, dict) or len(entry) > ENTRY_KEYS_MAX:
        return False
    for name, value in entry.items():
        if not _word(name):
            return False
        if value is None or _word(value):
            continue
        if isinstance(value, list) and len(value) <= LIST_MAX and all(_word(item) for item in value):
            continue
        return False
    return True


def diagnostic_lines(raw: bytes) -> list:
    """표준 오류 바이트에서 옮길 줄을 앞에서부터 `LINES_MAX` 개까지 고른다. 읽는 양은 부르는 쪽이 자른다."""
    text = raw.decode("utf-8", errors="replace")
    picked = []
    for line in text.splitlines():
        line = line.strip()
        if line and _diagnostic_line(line):
            picked.append(line)
            if len(picked) >= LINES_MAX:
                break
    return picked


def _append(profile_dir: pathlib.Path, lines: list) -> None:
    """그 profile 의 로그 디렉터리가 있을 때만 더한다. 디렉터리를 만들지 않고 링크는 따라가지 않는다."""
    logs = profile_dir / CHILD_LOG.parent
    if logs.is_symlink() or not logs.is_dir():
        return
    flags = os.O_WRONLY | os.O_APPEND | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(profile_dir / CHILD_LOG, flags, 0o600)
    with os.fdopen(fd, "a", encoding="utf-8") as out:
        out.write("".join(line + "\n" for line in lines))


@contextlib.contextmanager
def child_errlog(profile_dir: Optional[pathlib.Path]):
    """자식의 표준 오류로 줄 파일을 내준다. 끝나면(시간 초과로 취소돼도) 진단 줄만 그 profile 의 로그에 더한다.

    `profile_dir` 가 None 이면 지금처럼 버린다. 자식이 끝난 뒤에 읽도록 `stdio_client` 바깥에서 연다.
    로그에 더하지 못해도 실행 결과는 바꾸지 않는다. 경로와 원문 없이 예외 종류만 남긴다.
    """
    if profile_dir is None:
        with open(os.devnull, "w", encoding="utf-8") as sink:
            yield sink
        return
    capture = tempfile.TemporaryFile()
    try:
        yield capture
    finally:
        try:
            capture.seek(0)
            lines = diagnostic_lines(capture.read(READ_MAX))
            if lines:
                _append(profile_dir, lines)
        except Exception as error:
            logger.warning("dashboard-profile-api: 플러그인 진단 줄을 남기지 못했다: %s", type(error).__name__)
        finally:
            capture.close()
