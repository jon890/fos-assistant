from __future__ import annotations


# docker 실행 공간에서만 API 경로의 execute_code 를 승인 없이 돌린다(ADR-20261008 / execute-code-unattended).
# 도구 저장은 local 을 쓰지 않으므로(ADR-20261120 / no-local-shell) 이 값은 docker `terminal:` 을 쓰는 같은 쓰기에서만 넣는다.
UNATTENDED_APPROVAL_KEY = "unattended_mode"


def _with_sandbox_approvals(saved: dict, updated: dict) -> dict:
    """docker `terminal:` 을 쓰는 같은 쓰기에서 `approvals.unattended_mode: approve` 를 넣은 설정을 돌려준다.

    `approvals` 의 다른 키는 운영자가 정한 승인 정책이라 순서까지 그대로 둔다. 인자는 바꾸지 않고 새 설정을 돌려준다.
    """
    previous = saved.get("approvals")
    # 키가 없거나 값이 비었을 때(None)만 빈 객체로 본다. `false`, `[]` 같은 값을 덮어쓰지 않는다.
    if previous is None:
        previous = {}
    elif not isinstance(previous, dict):
        raise ValueError("approvals 설정이 객체가 아니다")
    approvals = {key: value for key, value in previous.items() if key != UNATTENDED_APPROVAL_KEY}
    approvals[UNATTENDED_APPROVAL_KEY] = "approve"
    return {**updated, "approvals": approvals}
