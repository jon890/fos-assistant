# Phase 01. 대시보드 plugin 이 셸 실행 공간 설정을 쓴다

**Execution profile**: deep

## 목표

`PUT /api/config` 의 도구 목록에 `terminal`, `file`, `code_execution` 가운데 하나라도 있으면 그 profile 의 `terminal:` 을 docker 실행 공간 설정으로 통째로 쓴다. 운영 설정이 없으면 409 로 거절한다.

**범위 외**: Control Plane 과 화면(phase 02, 03). 운영 값과 Docker 접근(운영 저장소).

## 컨텍스트

**근거 문서**: `docs/adr/ADR-084-셸과-파일-도구는-사용자별-docker-실행-공간에서만-돈다.md`, `docs/hermes/sandbox.md` 의 「마운트」, `hermes/README.md` 의 「셸 실행 공간」

- 계약: `hermes/README.md` 의 「셸 실행 공간」 과 `PUT /api/config` (도구) 줄. 쓸 YAML 모양이 거기 있다.
- 결정과 까닭: `docs/adr/ADR-084-셸과-파일-도구는-사용자별-docker-실행-공간에서만-돈다.md`
- 검사 지점: `hermes/plugins/dashboard-profile-api/__init__.py` 의 `_check_config_update`. 본문 키 검사(`set(body) != {"profile", "config"}`)와, `request.state.fos_checked_config = (config_path, original, updated)` 로 처리기 전에 파일을 쓰고 실패하면 되돌리는 흐름을 따른다. 지금은 `updated.get("agent") != saved.get("agent")` 일 때만 미리 쓴다.
- Hermes 의 `ConfigUpdate` 모델(`hermes_cli/web_models.py`)은 `config`, `profile` 만 읽고 다른 키를 버린다. 그래서 본문의 `sandbox_owner` 는 Hermes 처리기에 영향이 없다.
- 환경 변수는 기존 `SKILL_ROOT_ENV` 와 같이 모듈 상수로 둔다.

## 의도 메모

- 공유 컨테이너 키를 쓰지 않는다. 첫 profile 의 스킬 마운트만 붙는다(ADR-084 대안 기각).
- 설정이 없을 때 로컬 셸로 두지 않는다. fail closed.
- 도구 목록에 셸·파일 도구가 없으면 `terminal:` 을 건드리지 않는다. 끄는 저장은 실행 공간 설정 없이도 된다.

## 작업 항목

### 1. `hermes/plugins/dashboard-profile-api/__init__.py`

- 상수: `SANDBOX_ENV = "FOS_ASSISTANT_SANDBOX"`, `SANDBOX_TOOLSETS = frozenset({"terminal", "file", "code_execution"})`, `SANDBOX_OWNER_RE = re.compile(r"^[a-z][a-z0-9-]{0,63}$")`.
- `_sandbox_policy() -> Optional[dict]`: 환경 변수 JSON 을 읽어 검증한다. `image`(빈 문자열 아님, 공백과 제어 문자 없음), `workspace_root`(절대 경로, `..` 와 빈 조각 없음, `:` 없음), `network`(선택, `^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$`), `cpu`(선택, 0 초과 8 이하 숫자, 기본 1), `memory_mb`(선택, 256~16384 정수, 기본 1024), `read_only_mounts`(선택, 문자열 목록), `profile_mounts`(선택, profile 이름 → 문자열 목록). 마운트 항목은 `<절대 원본>:<절대 컨테이너 경로>` 둘 조각이고 조각마다 `..` 와 빈 조각이 없으며 컨테이너 경로가 경로 조각 기준으로 `/workspace`, `/root` 이거나 그 아래(`/workspace/`, `/root/` 로 시작)가 아니다. `/rootfs` 는 받는다. 하나라도 틀리면 `None` 과 오류 로그.
- `_sandbox_terminal(policy, profile, owner) -> dict`: `hermes/README.md` 의 YAML 과 같은 dict. 마운트는 `:ro` 를 붙인다. `network` 가 없으면 `docker_extra_args` 는 빈 목록.
- `_check_config_update`: 본문 키를 `{"profile", "config"}` 이거나 거기에 `sandbox_owner` 를 더한 것으로 받는다. `sandbox_owner` 가 있으면 문자열이고 `SANDBOX_OWNER_RE` 에 맞아야 한다(아니면 400). 요청 목록에 `SANDBOX_TOOLSETS` 가 하나라도 있으면 `sandbox_owner` 가 필수(400 「셸 도구에는 sandbox_owner 가 필요하다」), 정책이 없으면 409 와 본문 `{"detail": ..., "code": "sandbox_unavailable"}`. 둘 다 있으면 `updated["terminal"] = _sandbox_terminal(...)` 하고, 미리 쓰는 조건을 `agent` 나 `terminal` 이 바뀔 때로 넓힌다.
- 실행 공간 루트 아래 사용자 디렉터리를 `os.makedirs(exist_ok=True)` 로 만들어 본다. 실패는 경고 로그만 남긴다(Docker 가 원본을 만든다).
- `_rejected`(`detail`, `status_code` 만 받는다)는 그대로 두고, `_sandbox_unavailable()` 을 새로 만든다. 409 와 본문 `{"detail": "실행 공간이 설정되지 않았다", "code": "sandbox_unavailable"}` 를 돌려준다.
- 셸 도구 검사는 기존 `_toolset_rejection` 검사 뒤에 둔다.
- 모듈 docstring 의 「여는 것」 표 `PUT /api/config` 줄을 계약에 맞게 고친다.

### 3. `hermes/tests/test_connectors_contract.py`

저장소의 범용 커넥터(`hermes/connectors/*/skills/**/SKILL.md` 와 `SKILL.md` 가 있는 곳 전부)의 앞머리에 `required_environment_variables`, `required_credential_files`, `setup.collect_secrets`, `prerequisites.env_vars` 가 없음을 단언하는 시험 하나를 더한다(ADR-084 「감당할 것」).

### 2. `hermes/tests/test_dashboard_profile_api.py`

기존 `PUT /api/config` 시험 묶음(`test_toolset_update_restores_config_when_handler_fails` 주변)의 준비 함수를 그대로 쓴다.

- 먼저 기존 시험을 고친다. `code_execution_body` 를 쓰는 시험 넷(`test_toolset_update_lifts_disabled_names_only_for_api`, `..._keeps_memory_disabled`, `..._refuses_to_open_listed_platform`, `..._restores_config_when_handler_fails`)이 계속 원래 동작을 검사하도록 `code_execution_body` 에 `sandbox_owner` 를 넣고, `setUp` 에서 기존 `skill_env` 와 같은 방식(`mock.patch.dict`)으로 `FOS_ASSISTANT_SANDBOX` 를 넣는다. 「정책 없음」 시험은 그 변수를 명시적으로 지운다.

- 정책이 있고 `terminal` 을 켜면 저장된 `terminal:` 이 기대 dict 와 같다(`backend: docker`, `/workspace` 볼륨이 `<root>/<owner>:/workspace`, 읽기 전용 마운트에 `:ro`, `docker_forward_env: []`, `env_passthrough: []`, `credential_files: []`, `network` 가 있으면 `--network=<이름>`).
- `profile_mounts` 는 그 profile 에만 붙는다.
- 정책 환경 변수가 없으면 `file` 을 켜는 요청이 409 이고 본문 `code` 가 `sandbox_unavailable`, 설정 파일이 바뀌지 않는다.
- 정책이 틀리면(상대 경로, `..`, `/workspace` 로 가는 마운트) 409.
- 셸 도구를 켜는데 `sandbox_owner` 가 없거나 형식이 틀리면 400.
- 셸 도구가 없는 저장은 정책이 없어도 200 이고 기존 `terminal:` 이 그대로다.
- 처리기가 실패하면 `terminal:` 도 원래대로 되돌아간다.

## 검증

```bash
# cwd: 저장소 root
python3 -m unittest discover -s hermes/tests -p test_dashboard_profile_api.py
python3 -m unittest discover -s hermes/tests
scripts/check-public-safe.sh
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/dashboard-profile-api/__init__.py` | 수정 |
| `hermes/tests/test_dashboard_profile_api.py` | 수정 |
| `hermes/tests/test_connectors_contract.py` | 수정 |
