# Phase 01. 대시보드 plugin 의 도구 저장에서 local 분기를 없앤다

**Execution profile**: standard

## 목표

정책에 없는 profile 이 실행 공간 도구를 켜는 저장을 언제나 409 로 거절하고, 그 거절에 `reason: unlisted` 를 넣는다. 어떤 저장도 `terminal.backend` 를 local 로 쓰지 않게 한다.

**범위 외**: Control Plane 의 기동 작업과 `reason` 해석(phase 02). 정책의 `isolate_unlisted`(PR2).

## 컨텍스트

**요구사항**: REQ1

**근거 문서**: `docs/features/agent-tools/spec.md`, `docs/features/agent-tools/design.md` 의 「PR1」, `docs/adr/ADR-20261120-no-local-shell.md`

- 분기는 `hermes/plugins/dashboard-profile-api/toolconfig.py` 의 `_check_config_update` 에 있다. `profile not in sandbox["profiles"]` 일 때 `require_sandbox` 나 사진 도구가 없으면 `local_execution = True` 로 두고, 아래에서 `terminal.backend: local` 을 쓰며 `_with_sandbox_approvals(saved, updated, False)` 를 부른다.
- 409 응답은 `hermes/plugins/dashboard-profile-api/sandbox.py` 의 `_sandbox_unavailable()` 이 만든다. 여러 경로(정책 없음, 디렉터리 실패, 플러그인 설치)가 함께 쓴다.
- `require_sandbox` 는 Control Plane 의 기본 도구 쓰기가 보낸다. 옛 Control Plane 과의 호환으로 계속 받는다. 스크립트 스킬 게시의 `skill_root` 검사(`require_sandbox and skills is not None`)는 그대로 둔다.

## 의도 메모

- `reason` 은 정책 밖일 때만 넣는다. Control Plane 은 이 표시가 있을 때만 셸을 끈다. 정책이 잠깐 틀린 409 를 정책 밖으로 오해하면 모든 에이전트의 셸을 끈다.
- 실행 공간 도구를 켜지 않는 저장은 `terminal:` 과 `approvals` 를 건드리지 않는다. 지금도 그렇다.
- 기각: 정책 밖 저장에서 기존 docker 설정을 local 로 되돌리기. local 자체를 없애므로 되돌릴 곳이 없다.

## 작업 항목

### 1. `sandbox.py` 의 `_sandbox_unavailable`

선택 인자 `reason: Optional[str] = None` 을 받는다. 값이 있으면 응답 JSON 에 `"reason": reason` 을 더한다. 다른 호출처는 바꾸지 않는다.

### 2. `toolconfig.py` 의 `_check_config_update`

- `local_execution` 변수와 아래의 `elif local_execution:` 분기를 지운다.
- `profile not in sandbox["profiles"]` 이면 `require_sandbox` 와 사진 도구와 상관없이 `return _sandbox_unavailable("unlisted")` 다.
- `require_sandbox` 를 읽는 주석을 「도구 저장의 판정은 바꾸지 않고 스크립트 스킬 게시의 `skill_root` 검사에만 쓴다. 옛 Control Plane 호환으로 받는다」 로 고친다.
- `_with_sandbox_approvals` import 와 docker 쪽 호출은 그대로 둔다.

### 3. `__init__.py` 의 경로 표와 `hermes/README.md` 의 「셸 실행 공간」, `hermes/plugins/dashboard-profile-api/README.md`

- `__init__.py` 머리 표의 `PUT /api/config` 줄: 「미등록 profile 은 local 이다」 를 「미등록 profile 은 409 `sandbox_unavailable`, `reason: unlisted` 다(ADR-20261120 / no-local-shell)」 로 바꾼다.
- `hermes/README.md` 의 `profiles` 줄: 「빈 객체면 모두 local 로 둔다」 를 「빈 객체면 모든 profile 의 실행 공간 도구 저장을 거절한다」 로 바꾼다. 같은 절에 local 로 두는 설명이 더 있으면 같은 뜻으로 고친다.
- plugin README 에 local 로 두는 설명이 있으면 같은 뜻으로 고친다(`grep -n "local" hermes/plugins/dashboard-profile-api/README.md`).

### 4. local 을 전제로 적은 ADR 과 주석

- ADR 본문은 고치지 않고 status 아래에 「대체된 부분(2026-11-20)」 줄을 단다: `hermes/docs/adr/ADR-20261008-execute-code-unattended.md`(「local 로 되돌릴 때 승인 키를 지운다」 경로가 없어진다), `docs/adr/ADR-20261008-default-toolsets.md`(사람의 저장이 local 로 켠다는 전제), `docs/adr/ADR-20261009-skill-package.md`(정책 밖 셸은 local 로 돈다는 전제). 모두 ADR-20261120 / no-local-shell 을 가리킨다.
- `hermes/README.md` 의 「셸 실행 공간」 과 plugin README 의 local 설명, `sandbox.py` 와 `toolconfig.py` 의 local 주석을 새 동작으로 고친다(`grep -n "local" hermes/README.md hermes/plugins/dashboard-profile-api/README.md hermes/plugins/dashboard-profile-api/*.py`).

### 5. `hermes/tests/test_dashboard_profile_api_sandbox_policy.py`

local 을 기대하는 테스트를 거절을 기대하도록 바꾼다.

| 지금 테스트 | 바꾼 기대 |
| --- | --- |
| `test_unlisted_profile_keeps_local_terminal_and_needs_no_sandbox_owner` | 이름을 `test_unlisted_profile_rejects_shell_save_with_reason` 으로. 409, `code` 와 `reason: unlisted`, 설정 파일 바이트 불변, 사용자 디렉터리 없음 |
| `test_require_sandbox_rejects_unlisted_profile_instead_of_local` | `reason: unlisted` 도 본다 |
| `test_unlisted_profile_cannot_enable_media_file_tools_locally` | `reason: unlisted` 도 본다 |
| `test_empty_profiles_policy_keeps_all_profiles_local` | 이름을 `test_empty_profiles_policy_rejects_every_shell_save` 로. 409 와 설정 불변 |
| `test_profile_removed_from_policy_returns_to_local_on_shell_save` | 이름을 `test_profile_removed_from_policy_rejects_shell_save` 로. 409 와 이전 docker 설정 그대로 |
| `test_profile_removed_from_policy_drops_unattended_approval` | 지운다. 정책 밖 저장은 설정을 바꾸지 않는다 |
| `test_unlisted_local_profile_drops_operator_unattended_approval` | 지운다. 같은 까닭 |

새로 하나 더한다: 정책이 없을 때(`FOS_ASSISTANT_SANDBOX` 없음)의 셸 저장 409 본문에 `reason` 필드이 없다.
다른 테스트 파일에서 local 을 기대하는 곳이 있으면 같은 방식으로 고친다(`grep -rn "backend\": \"local\"\|keeps_local\|returns_to_local" hermes/tests`).

## 검증

```bash
python3 -m unittest discover -s hermes/tests -p 'test_dashboard_profile_api_sandbox_policy.py'
python3 scripts/checks/run-hermes-python-tests.py test_dashboard_profile_api_toolconfig.py test_dashboard_profile_api_sandbox_terminal.py test_dashboard_profile_api_image_service.py
python3 scripts/checks/run-hermes-python-tests.py
test -z "$(grep -n local_execution hermes/plugins/dashboard-profile-api/toolconfig.py)"
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/dashboard-profile-api/sandbox.py` | 수정 (`_sandbox_unavailable` 의 `reason`) |
| `hermes/plugins/dashboard-profile-api/toolconfig.py` | 수정 (local 분기 삭제) |
| `hermes/plugins/dashboard-profile-api/__init__.py` | 수정 (경로 표 문구) |
| `hermes/plugins/dashboard-profile-api/README.md` | 수정 (local 설명이 있으면) |
| `hermes/README.md` | 수정 (「셸 실행 공간」) |
| `hermes/tests/test_dashboard_profile_api_sandbox_policy.py` | 수정 |
| `hermes/docs/adr/ADR-20261008-execute-code-unattended.md` | 수정 (대체된 부분 줄) |
| `docs/adr/ADR-20261008-default-toolsets.md` | 수정 (대체된 부분 줄) |
| `docs/adr/ADR-20261009-skill-package.md` | 수정 (대체된 부분 줄) |
