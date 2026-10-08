# Phase 01. 셸 저장이 docker terminal 과 함께 approvals.unattended_mode 를 쓰고 local 에서 지운다

**Execution profile**: standard

## 목표

대시보드 plugin 이 profile 의 `terminal:` 을 docker 실행 공간으로 쓸 때 `approvals.unattended_mode: approve` 를 같은 설정 쓰기에 넣고, `backend: local` 을 쓸 때 그 키를 지운다.
API 경로의 `execute_code` 가 docker 실행 공간에서만 승인 없이 돌게 하기 위해서다.

**범위 외**: Hermes 계약 시험은 phase 02 가 맡는다. 운영 profile 반영은 원격 검증 목록에 있다.

## 컨텍스트

- `hermes/plugins/dashboard-profile-api/toolconfig.py` 의 셸 계열 도구 저장 검사가 `updated["terminal"]` 을 두 곳에서 쓴다.
  `if sandbox is not None:` 분기에서 `_sandbox_terminal(sandbox, profile, owner, prepared)` 를, `elif local_execution:` 분기에서 local `terminal` 을 쓴다.
  그 뒤 `if updated.get("agent") != saved.get("agent") or updated.get("terminal") != saved.get("terminal"):` 일 때만 `request.state.fos_checked_config` 를 둬 처리기 앞에서 설정을 쓴다(`routes.py` 의 `_write_checked_config`).
- `hermes/plugins/dashboard-profile-api/connector_isolated.py` 의 `_connector_config` 가 사진 도구 옛 설치에서 같은 일을 한다. `if sandbox_terminal is not None:` 와 `elif local_execution:` 분기다. 이 함수는 바뀐 `updated` 를 통째로 YAML 로 쓴다.
- 실행 공간 설정 helper 는 `hermes/plugins/dashboard-profile-api/sandbox.py` 가 갖는다(`_sandbox_terminal`).
- Hermes 는 `approvals.unattended_mode` 를 판정할 때마다 그 profile 설정에서 읽고, 값 `approve` 를 승인으로 본다.

**근거 문서**: `docs/adr/ADR-20261008-execute-code-unattended.md`, `hermes/README.md` 의 「셸 실행 공간」, `docs/hermes/sandbox.md` 의 「`execute_code` 의 승인 판정」

## 의도 메모

- 값은 `terminal.backend` 와 같은 쓰기에서 움직인다. 운영 명령으로 따로 넣는 안은 local 로 돌아간 profile 에 `approve` 가 남을 수 있어 기각했다. local profile 의 `execute_code` 는 Hermes 컨테이너에서 돌아 다른 profile 의 `.env` 에 닿는다.
- `approvals` 의 다른 키(`mode`, `deny`, `timeout`, `cron_mode` 등)는 건드리지 않는다. 운영자가 정한 승인 정책이다.
- local 분기는 운영자가 직접 넣은 `unattended_mode` 도 지운다. 지운 뒤 `approvals` 가 비면 `approvals` 키 자체를 지운다.
- `approvals` 가 객체가 아니면 `terminal` 과 같은 방식으로 `ValueError` 를 던진다. 셸 저장은 기존 예외 처리로 500 이 된다.
- terminal 이 그대로여도 approvals 가 바뀌면 설정을 써야 한다. 이미 docker 인 profile 을 다시 저장해 값을 넣는 것이 운영 반영 방법이다.

## 작업 항목

### 1. `hermes/plugins/dashboard-profile-api/sandbox.py` 에 helper 를 더한다

```python
# docker 실행 공간에서만 API 경로의 execute_code 를 승인 없이 돌린다(ADR-20261008 / execute-code-unattended).
UNATTENDED_APPROVAL_KEY = "unattended_mode"


def _with_sandbox_approvals(saved: dict, updated: dict, docker: bool) -> dict:
    """terminal 을 쓰는 같은 쓰기에서 `approvals.unattended_mode` 를 맞춘 설정을 돌려준다."""
```

- `saved.get("approvals") or {}` 가 dict 가 아니면 `ValueError("approvals 설정이 객체가 아니다")`.
- `UNATTENDED_APPROVAL_KEY` 를 뺀 나머지 키를 순서대로 복사하고, `docker` 면 `"approve"` 를 넣는다.
- 결과가 비면 `updated` 에서 `approvals` 를 뺀 dict 를, 아니면 `approvals` 를 바꾼 dict 를 새로 만들어 돌려준다. 인자를 바꾸지 않는다.

### 2. `hermes/plugins/dashboard-profile-api/toolconfig.py` 가 helper 를 부른다

- docker 분기에서 `updated["terminal"] = _sandbox_terminal(...)` 바로 뒤에 `updated = _with_sandbox_approvals(saved, updated, True)`.
- local 분기에서 `updated["terminal"] = terminal` 바로 뒤에 `updated = _with_sandbox_approvals(saved, updated, False)`.
- `fos_checked_config` 를 두는 조건에 `or updated.get("approvals") != saved.get("approvals")` 를 더한다. 바로 위 주석에 approvals 를 함께 적는다.
- `from .sandbox import` 목록에 `_with_sandbox_approvals` 를 더한다.

### 3. `hermes/plugins/dashboard-profile-api/connector_isolated.py` 가 helper 를 부른다

- `if sandbox_terminal is not None:` 분기에서 terminal 을 쓴 뒤 `updated = _with_sandbox_approvals(saved, updated, True)`.
- `elif local_execution:` 분기에서 terminal 을 쓴 뒤 `updated = _with_sandbox_approvals(saved, updated, False)`.
- import 를 더한다.

### 4. 이 phase 를 검증하는 시험

`hermes/tests/test_dashboard_profile_api_sandbox_terminal.py` 에 더한다.

- `test_shell_toolset_writes_unattended_approval_and_keeps_other_approvals`: 저장 전 설정에 `approvals: {mode: manual, deny: ["*curl*"]}` 를 두고 셸 도구를 저장한다. 저장된 `approvals` 가 `{mode: manual, deny: ["*curl*"], unattended_mode: approve}` 다.
- `test_resaving_an_unchanged_sandbox_terminal_writes_the_missing_approval`: 한 번 저장한 뒤 파일에서 `approvals` 만 지우고 같은 요청을 다시 보낸다. `approvals.unattended_mode` 가 `approve` 로 돌아온다.
- `test_vision_connector_install_writes_unattended_approval`: 기존 `test_vision_connector_uses_the_trusted_owners_sandbox_terminal` 과 같은 준비로 사진 도구 커넥터를 설치하면 `alice_config()["approvals"] == {"unattended_mode": "approve"}` 다.
- `test_non_object_approvals_rejects_the_shell_save`: `approvals: "approve"` 인 설정에 셸 도구를 저장하면 500 이고 파일 바이트가 그대로다.

`hermes/tests/test_dashboard_profile_api_sandbox_policy.py` 에 더한다.

- `test_profile_removed_from_policy_drops_unattended_approval`: `save_sandbox_key()` 뒤 정책에서 profile 을 빼고 셸 저장을 하면 `approvals` 키가 없다. `approvals` 에 `mode: manual` 이 함께 있었으면 그 키만 남는다(subTest 둘).
- `test_unlisted_local_profile_drops_operator_unattended_approval`: 정책에 없는 local profile 설정에 `approvals: {unattended_mode: approve, timeout: 60}` 을 두고 셸 저장을 하면 `{timeout: 60}` 만 남는다.

기존 시험이 저장된 설정 전체를 dict 로 비교하다 깨지면 그 기대값에 `approvals` 를 더한다. 기대값을 바꾼 시험은 변경 파일 표의 같은 파일 안에 있다.

## 검증

```bash
python3 -m unittest discover -s hermes/tests -p 'test_dashboard_profile_api_sandbox_*.py' -v
python3 -m unittest discover -s hermes/tests
scripts/quality.sh check
```

기대값: 세 명령 모두 종료 코드 0. 위 여섯 시험이 첫 명령의 출력에 `ok` 로 나온다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/dashboard-profile-api/sandbox.py` | 수정 |
| `hermes/plugins/dashboard-profile-api/toolconfig.py` | 수정 |
| `hermes/plugins/dashboard-profile-api/connector_isolated.py` | 수정 |
| `hermes/tests/test_dashboard_profile_api_sandbox_terminal.py` | 수정 |
| `hermes/tests/test_dashboard_profile_api_sandbox_policy.py` | 수정 |
