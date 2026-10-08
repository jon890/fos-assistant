# Phase 02. manifest 의 `owner_output_env` 와 바인딩 설치

**Execution profile**: standard

## 목표

`connector.json` 이 `owner_output_env` 를 선언하면 바인딩 설치가 그 profile 의 커넥터 출력 디렉터리를 서버 정의 env 에 넣고, 떼면 그 디렉터리를 지운다.

**범위 외**: 실행 공간 마운트(phase 01). 실제 커넥터(fos-agents 가계부)의 파일 출력.

## 컨텍스트

- 본보기는 `owner_attachments_env` 다. manifest 검증은 `connector_manifest.py`, 바인딩 쓰기는 `connector_binding.py` 의 `_connector_bind_config`, 요청 처리는 `connector_install.py`, 확인 도구와 승인 실행의 env 는 `connector_run.py` 다.
- 정규식은 `connector_schema.py` 에 둔다(`OWNER_ATTACHMENTS_ENV_RE`, `OWNER_ATTACHMENTS_VALUE_RE` 옆).
- phase 01 의 `_sandbox_connector_output_profile_directory`, `_sandbox_prepare_connector_output` 을 쓴다.

**근거 문서**: `docs/adr/ADR-20261008-connector-output-files.md`, `docs/connectors.md` 의 「connector.json」, `docs/backend/connector-install.md` 의 「바인딩 설치」, `hermes/README.md` 의 `PUT /api/connectors`, `call`, `execute`

## 의도 메모

- 값이 없을 때 붙이기를 막지 않는다. 정책, 키, `sandbox_owner` 가 없거나 디렉터리를 만들지 못하면 빈 값으로 붙인다. 커넥터가 파일 출력만 거절한다.
- 경로는 정책 루트와 `sandbox_owner` 와 profile 과 커넥터 id 로만 만든다. 요청 본문의 다른 칸과 manifest 는 경로를 정하지 못한다.
- 떼기의 삭제는 설치한 값이 지금 정책 루트 아래의 그 모양일 때만 한다. 링크가 섞였으면 지우지 않는다.

## 작업 항목

### 1. `connector_schema.py`

`OWNER_OUTPUT_VALUE_RE = re.compile(r"^/[^$\0\r\n]*/users/[0-9a-f]{64}/[^/$\0\r\n]+/[^/$\0\r\n]+$")`. env 이름은 `OWNER_ATTACHMENTS_ENV_RE` 를 함께 쓴다.

### 2. `connector_manifest.py`

`owner_output_env` 를 읽어 `owner_attachments_env` 와 같은 검사를 하고 `owner_attachments_env` 와도 겹치지 않게 한다. 서버 env 합 검사에 더하고 오류 글을 「fields 와 operator_env, owner_attachments_env, owner_output_env 의 합」 으로 바꾼다. `env` 에 빈 값으로 넣고, 반환 dict 에 `owner_output_env` 를 싣는다.

### 3. `connector_install.py`

바인딩 설치에서 manifest 가 선언했으면 `owner is not None` 이고 `_sandbox_policy()` 가 키를 가질 때 `<profile 디렉터리>/<커넥터 id>` 를 `_sandbox_prepare_connector_output` 뒤에 `os.makedirs(mode=0o700, exist_ok=True)` 로 만들고 resolve 를 확인한다. 실패하면 경고 로그(예외 종류만)를 남기고 빈 값이다. `_connector_bind_config` 에 `owner_output` 인자로 넘긴다.

### 4. `connector_binding.py`

- `_connector_bind_config(..., owner_output: str | None = None)`: 붙일 때 선언했으면 `owner_output` 이 `OWNER_OUTPUT_VALUE_RE` 에 맞으면 그 값, 아니면 `""` 를 서버 env 에 넣는다.
- 뗄 때 설치 기록(`owned["server"]["env"]`)의 그 값이 정규식에 맞고 지금 정책의 `connector_output_root` 아래이며 resolve 가 같으면 설정 쓰기가 끝난 뒤 `shutil.rmtree` 로 지운다. 실패는 경고 로그만 남긴다.

### 5. `connector_run.py`

확인 도구와 선택지(`call`), 승인 실행(`execute`)의 자식 env 에 `owner_output_env` 를 빈 값으로 준다.

### 6. 시험

- `hermes/tests/test_dashboard_profile_api_connector_binding_output.py`(신규): 정책 키가 있으면 서버 env 에 `<root>/users/<sha>/alice/demo` 가 들어가고 디렉터리가 생기며 `.env` 에 쓰지 않는다. 키가 없거나 `sandbox_owner` 가 없으면 빈 값으로 붙는다. 떼면 그 디렉터리가 지워지고 다른 profile 의 디렉터리는 남는다.
- `hermes/tests/test_connector_manifest.py`: `owner_output_env` 가 다른 env 이름과 겹치거나 형식이 틀리거나 서버 env 에 없으면 manifest 를 거절한다.
- `hermes/tests/test_connector_call.py`, `hermes/tests/test_connector_execute.py`: 선언한 커넥터의 자식 env 에 그 이름이 빈 값이다.
- `hermes/tests/dashboard_profile_api_support.py`: `declare_owner_output(connector, server="demo", name="DEMO_OUTPUT_DIR")` 도우미.

## 검증

```bash
cd hermes/tests && python3 -m unittest test_dashboard_profile_api_connector_binding_output test_connector_manifest test_connector_call test_connector_execute test_dashboard_profile_api_connector_binding_attachments test_dashboard_profile_api_connector_binding_install test_connectors_contract
```

기대값: 모두 통과

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/dashboard-profile-api/connector_schema.py` | 수정 |
| `hermes/plugins/dashboard-profile-api/connector_manifest.py` | 수정 |
| `hermes/plugins/dashboard-profile-api/connector_install.py` | 수정 |
| `hermes/plugins/dashboard-profile-api/connector_binding.py` | 수정 |
| `hermes/plugins/dashboard-profile-api/connector_run.py` | 수정 |
| `hermes/tests/dashboard_profile_api_support.py` | 수정 |
| `hermes/tests/test_dashboard_profile_api_connector_binding_output.py` | 신규 |
| `hermes/tests/test_connector_manifest.py` | 수정 |
| `hermes/tests/test_connector_call.py` | 수정 |
| `hermes/tests/test_connector_execute.py` | 수정 |
