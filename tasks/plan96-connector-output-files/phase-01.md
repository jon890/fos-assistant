# Phase 01. 실행 공간 정책의 `connector_output_root` 와 읽기 전용 마운트

**Execution profile**: standard

## 목표

실행 공간 정책에 선택 키 `connector_output_root` 를 받고, 키가 있으면 셸 저장이 그 profile 의 출력 디렉터리를 같은 경로에 읽기 전용으로 붙인다.
커넥터가 쓴 파일을 `execute_code` 스크립트가 그 경로 그대로 읽게 하기 위해서다.

**범위 외**: manifest 의 `owner_output_env`, 바인딩 설치의 env 값, 떼기의 디렉터리 삭제(phase 02).

## 컨텍스트

- 정책 검증은 `hermes/plugins/dashboard-profile-api/sandbox.py` 의 `_sandbox_policy` 가 한다. 최상위 키 목록은 `SANDBOX_POLICY_KEYS` 다.
- 셸 설정은 같은 파일의 `_sandbox_terminal(policy, profile, owner, attachment_snapshot)` 이 만든다. 지문은 `docker_shared_container_key` 를 뺀 `terminal` 의 정렬 JSON 이다.
- 셸 설정을 쓰기 전에 사용자 workspace 를 만드는 곳은 두 군데다. `toolconfig.py` 의 `os.makedirs(_sandbox_workspace(sandbox, owner), exist_ok=True)` 와 `connector_install.py` 의 같은 줄.
- 경로 겹침 검사는 `_sandbox_paths_overlap`, 사용자 키는 `_sandbox_attachment_key(owner)`(SHA-256 16진수)다.

**근거 문서**: `docs/adr/ADR-20261008-connector-output-files.md`, `hermes/README.md` 의 「셸 실행 공간」, `docs/hermes/sandbox.md` 의 「마운트」

## 의도 메모

- 키가 없으면 `terminal` 이 지금과 바이트 단위로 같아야 한다. 지문이 바뀌면 운영 컨테이너가 모두 새로 생긴다.
- 원본과 대상이 같은 경로다. Hermes 컨테이너에서 커넥터가 쓴 경로를 실행 공간이 그대로 연다.
- profile 단위로 붙인다. 같은 사용자의 다른 에이전트는 보지 못한다.

## 작업 항목

### 1. `hermes/plugins/dashboard-profile-api/sandbox.py`

- `SANDBOX_POLICY_KEYS` 에 `connector_output_root` 를 더한다.
- `_sandbox_policy` 는 키가 있으면 `_sandbox_path_ok` 를 보고, `workspace_root`, `attachment_root`, `attachment_agent_root`, `/workspace`, `/root` 와 `_sandbox_paths_overlap` 이면 `invalid("connector_output_root")` 다.
  공통과 profile 별 `read_only_mounts` 의 원본이나 대상이 이 루트와 겹쳐도 정책 전체를 거절한다. 반환 dict 에 `connector_output_root`(없으면 `None`)를 싣는다.
- `_sandbox_connector_output_profile_directory(policy, profile, owner) -> str | None`: 키가 없으면 `None`, 있으면 `<root>/users/<_sandbox_attachment_key(owner)>/<profile>`.
- `_sandbox_prepare_connector_output(policy, profile, owner) -> None`: 위 디렉터리를 `os.makedirs(..., mode=0o700, exist_ok=True)` 로 만들고 `pathlib.Path(d).resolve() != pathlib.Path(d)` 이면 `SandboxAttachmentError` 를 던진다. 키가 없으면 아무것도 하지 않는다.
- `_sandbox_terminal` 은 디렉터리가 있으면 `docker_volumes` 의 첨부 마운트 바로 뒤에 `"%s:%s:ro" % (d, d)` 를 넣는다.

### 2. `toolconfig.py`, `connector_install.py`

workspace 를 만드는 두 줄 바로 뒤에 `_sandbox_prepare_connector_output(sandbox, profile, owner)` 를 부른다. 실패(`OSError`)는 지금 workspace 실패와 같이 `_sandbox_unavailable()` 409 다.

### 3. 시험

- `hermes/tests/test_dashboard_profile_api_sandbox_policy.py`: 겹치는 루트와 마운트 사례를 `test_invalid_sandbox_policy_is_unavailable` 의 `cases` 에 더한다.
- `hermes/tests/test_dashboard_profile_api_sandbox_terminal.py`: 키가 있으면 셸 저장의 `docker_volumes` 에 그 profile 디렉터리가 같은 경로 `:ro` 로 들어가고 디렉터리가 생긴다. 키가 없으면 `terminal` 이 지금과 같다(기존 기대값 그대로).
- `hermes/tests/dashboard_profile_api_support.py`: 시험용 출력 루트(`self.connector_output_root`)를 임시 디렉터리에 둔다. 기본 정책에는 넣지 않는다.

## 검증

```bash
cd hermes/tests && python3 -m unittest test_dashboard_profile_api_sandbox_policy test_dashboard_profile_api_sandbox_terminal test_dashboard_profile_api_toolconfig test_dashboard_profile_api_sandbox_attachments
```

기대값: 모두 통과

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/dashboard-profile-api/sandbox.py` | 수정 |
| `hermes/plugins/dashboard-profile-api/toolconfig.py` | 수정 |
| `hermes/plugins/dashboard-profile-api/connector_install.py` | 수정 |
| `hermes/tests/dashboard_profile_api_support.py` | 수정 |
| `hermes/tests/test_dashboard_profile_api_sandbox_policy.py` | 수정 |
| `hermes/tests/test_dashboard_profile_api_sandbox_terminal.py` | 수정 |
