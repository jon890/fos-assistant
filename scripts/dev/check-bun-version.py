#!/usr/bin/env python3
"""로컬 bun 버전이 CI 가 고정한 `bun-version` 과 다르면 경고 한 줄을 낸다.

CI 의 값은 `.github/workflows/ci.yml` 이 정본이다. 이 스크립트는 값을 갖지 않고 읽기만 한다.
버전이 달라도 종료 코드는 0 이다. 검사를 멈추지 않고 알리기만 하려는 것이다.

사용법: python3 scripts/dev/check-bun-version.py [ci.yml 경로]
"""
import pathlib
import re
import subprocess
import sys

REPO = pathlib.Path(__file__).resolve().parents[2]


def pinned_versions(workflow: pathlib.Path) -> list[str]:
    found: list[str] = []
    for match in re.finditer(r"^\s*bun-version:\s*['\"]?([^'\"\s#]+)", workflow.read_text(encoding="utf-8"), re.M):
        if match.group(1) not in found:
            found.append(match.group(1))
    return found


def main() -> int:
    workflow = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else REPO / ".github/workflows/ci.yml"
    try:
        wanted = pinned_versions(workflow)
    except OSError:
        print(f"알림: {workflow} 를 읽지 못해 bun 버전 비교를 건너뛴다.")
        return 0
    if not wanted:
        print(f"알림: {workflow.name} 에서 bun-version 을 찾지 못해 bun 버전 비교를 건너뛴다.")
        return 0
    try:
        local = subprocess.run(["bun", "--version"], capture_output=True, text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        print("알림: 로컬 bun 버전을 구하지 못해 비교를 건너뛴다.")
        return 0
    if wanted == [local]:
        return 0
    expected = " 과 ".join(wanted)
    print(f"알림: 로컬 bun {local} 이 CI 의 bun {expected} 과 다르다. "
          f"손대지 않은 금융 왕복 검사가 실패할 수 있다. 맞추려면 `bun upgrade --version {wanted[0]}` "
          f"(mise 면 `mise use -g bun@{wanted[0]}`) 를 쓴다.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
