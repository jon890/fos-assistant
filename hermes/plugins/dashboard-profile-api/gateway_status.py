"""공유 gateway 의 기동 시각을 답한다(ADR-20261120 / connector-auto-apply).

Control Plane 이 재시작 대기 바인딩을 스스로 반영 맞출 때, 대기가 생긴 뒤 gateway 가 다시 떴는지 이 시각으로 본다.
언제 재시작이 끝났다고 볼지는 Control Plane 이 정하고 이 plugin 은 시각만 읽는다.

gateway pid 는 대시보드 자신의 gateway 상태 화면과 같이 `get_running_pid(pid_path, cleanup_stale=False)` 로 읽는다.
인자 없이 부르면 낡은 pid 파일을 지우는 정리를 할 수 있어 쓰지 않는다.
시작 시각은 Hermes 함수가 아니라 리눅스 `/proc` 로 구한다. 정밀도는 1초다.
"""

from __future__ import annotations

import os
import pathlib
from typing import Optional

GATEWAY_PATH = "/api/gateway"
GATEWAY_PID_FILE = "gateway.pid"
PROC_ROOT = pathlib.Path("/proc")
# `/proc/<pid>/stat` 에서 comm 칸 뒤의 칸 순서다. 셋째 칸(state)이 0 이고 시작 tick(22번째 칸)이 19 다.
START_TICKS_INDEX = 19


def _gateway_pid() -> Optional[int]:
    from gateway.status import get_running_pid
    from hermes_constants import get_default_hermes_root

    return get_running_pid(pathlib.Path(get_default_hermes_root()) / GATEWAY_PID_FILE, cleanup_stale=False)


def _boot_time(proc_root: pathlib.Path) -> int:
    for line in (proc_root / "stat").read_text(encoding="ascii").splitlines():
        if line.startswith("btime "):
            return int(line.split()[1])
    raise ValueError("btime 이 없다")


def _start_ticks(proc_root: pathlib.Path, pid: int) -> int:
    raw = (proc_root / str(pid) / "stat").read_text(encoding="utf-8", errors="replace")
    # comm 칸은 괄호 안에 공백과 괄호를 담을 수 있다. 마지막 `)` 뒤부터 나눈다.
    return int(raw[raw.rindex(")") + 1:].split()[START_TICKS_INDEX])


def _gateway_started_at() -> Optional[int]:
    """공유 gateway 의 기동 시각(epoch 초)이다. pid 가 없거나 읽지 못하면 None 이다. 예외 본문과 경로는 남기지 않는다."""
    proc_root = PROC_ROOT
    try:
        pid = _gateway_pid()
        if not isinstance(pid, int) or isinstance(pid, bool) or pid <= 0:
            return None
        ticks = os.sysconf("SC_CLK_TCK")
        started = _boot_time(proc_root) + _start_ticks(proc_root, pid) // ticks
        return started if started > 0 else None
    except Exception:
        return None


def _gateway_response():
    from starlette.responses import JSONResponse

    return JSONResponse({"started_at": _gateway_started_at()}, status_code=200)
