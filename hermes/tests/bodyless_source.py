"""본문 없는 요청 진단의 source 검사가 돌 Hermes 소스인지 정한다.

`HERMES_SOURCE` 가 계약이 맞춘 계열(`BODYLESS_SOURCE_LINE`)의 표지 파일을 가질 때만 source 검사를 돌린다.
기준 버전이 그 계열보다 낮은 동안 CI 의 기준 버전 검사는 이 묶음을 건너뛴다.
"""

import os
from pathlib import Path

import hermes_contract as contract


def bodyless_source() -> Path | None:
    """계약 계열의 소스면 그 경로를, 소스가 없거나 다른 계열이면 None 을 돌려준다."""
    configured = os.environ.get("HERMES_SOURCE")
    if not configured:
        return None
    root = Path(configured)
    if (root / contract.BODYLESS_SOURCE_LINE[1]).is_file():
        return root
    # 확인 스크립트가 준 경로가 없으면 건너뛰지 않고 실패하게 그 경로를 돌려준다.
    if os.environ.get("HERMES_CONTRACT_REQUIRED") and not root.is_dir():
        return root
    return None


SKIP_REASON = ("HERMES_SOURCE 가 없거나 v" + contract.BODYLESS_SOURCE_LINE[0]
               + " 계열이 아니어서 본문 없는 요청 진단의 source 검사를 건너뛴다")
