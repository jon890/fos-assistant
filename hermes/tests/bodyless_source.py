"""본문 없는 요청 진단도 지정한 Hermes 소스를 반드시 검사한다."""

import os
from pathlib import Path


def bodyless_source() -> Path | None:
    """소스가 지정됐으면 경로를 돌려준다. 없는 경로와 옛 소스도 검사에서 실패한다."""
    configured = os.environ.get("HERMES_SOURCE")
    return Path(configured) if configured else None


SKIP_REASON = "HERMES_SOURCE 가 없어 본문 없는 요청 진단의 source 검사를 건너뛴다"
