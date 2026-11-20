"""바인딩 설치가 그 서버만 껐다 켜는 데 쓰는 끄기 기록이다(ADR-20261120 connector-auto-apply).

꺼진 상태는 설정의 서버 항목에만 `enabled: false` 로 둔다. 소유 기록의 서버 정의는 `enabled` 가 참이어야 읽히므로 바꾸지 않는다.
끈 시각은 소유 기록 밖의 끄기 기록 파일에 둔다. 이전 이미지는 이 파일을 읽지 않는다.
"""

from __future__ import annotations

import json
import logging
import time

logger = logging.getLogger(__name__)

CONNECTOR_CYCLE = ".fos-connector-cycle.json"
# gateway 의 MCP 설정 맞추기 주기(60초) 둘이다. 끈 뒤 이만큼 지나야 gateway 가 옛 연결을 끊었다고 본다.
CYCLE_MIN_SECONDS = 120


def _cycle_now() -> float:
    """끄기 기록의 시계다. 테스트가 이 함수 하나를 바꾼다."""
    return time.time()


def _cycle_marks(raw: bytes | None) -> dict:
    """끄기 기록 `{"<플러그인 id>": <끈 epoch 초>}` 를 읽는다. 없거나 모양이 틀리면 빈 기록이다."""
    if not raw:
        return {}
    try:
        marks = json.loads(raw)
    except ValueError as error:
        logger.warning("dashboard-profile-api: 끄기 기록을 읽지 못해 비운다: %s", type(error).__name__)
        return {}
    if not isinstance(marks, dict) or any(
            not isinstance(key, str) or isinstance(value, bool) or not isinstance(value, (int, float))
            for key, value in marks.items()):
        logger.warning("dashboard-profile-api: 끄기 기록의 모양이 틀려 비운다")
        return {}
    return dict(marks)


def _cycle_bytes(marks: dict) -> bytes | None:
    """비면 파일을 지운다."""
    return (json.dumps(marks, sort_keys=True) + "\n").encode() if marks else None


def _server_off(current, recorded, marks: dict, plugin: str) -> bool:
    """설정 항목이 소유 기록과 `enabled` 만 다르고 끄기 기록에 그 플러그인이 있는가. 이때만 이 plugin 이 끈 것으로 본다."""
    return (isinstance(current, dict) and isinstance(recorded, dict) and plugin in marks
            and current == {**recorded, "enabled": False})


def _cycle_elapsed(marks: dict, plugin: str) -> bool:
    """끈 뒤 최소 시간이 지났는가."""
    return _cycle_now() - marks[plugin] >= CYCLE_MIN_SECONDS
