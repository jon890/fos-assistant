# Phase 02. 원격 MCP 서버 정의를 검증하고 바인딩에 설치하고 뗀다

**Execution profile**: deep

## 목표

`.mcp.json` 의 서버가 `type: http` 인 플러그인을 카탈로그에 올리고, 바인딩 설치가 profile `config.yaml` 과 소유 기록 `.fos-connectors.json` 에 `url` 과 `headers` 로 된 서버 정의를 쓰고, 상태 조회, probe, 다시 붙이기, 떼기가 그 기록을 읽게 한다.
금융 워크벤치처럼 서버가 MCP 를 직접 여는 플러그인을 Hermes 가 붙게 하기 위해서다.

**범위 외**: 대시보드 plugin 이 원격 서버를 직접 부르는 `call` 과 `execute`. phase 03 이 맡는다. 이 phase 에서 원격 플러그인의 `call` 과 `execute` 는 자식 프로세스를 띄우지 않고 `unavailable` 로 끝나면 된다.

## 컨텍스트

**근거 문서**: `hermes/docs/adr/ADR-20261026-remote-mcp-plugin.md` 의 「결정」 표, `hermes/plugins/dashboard-profile-api/README.md` 의 「원격 MCP 커넥터는 자식 프로세스를 띄우지 않고 Streamable HTTP 로 붙는다」 목록, `docs/features/connector.md` 의 「플러그인 형식과 원격 MCP」

- 운영 목록은 `hermes/plugins/dashboard-profile-api/connector_manifest_io.py` 의 `_connector_roots()` 가 읽는다. 지금 항목 키는 `root`, `command`, `env` 만 받고 `{"root": Path, "command": str | None, "env": dict}` 로 돌려준다. `env` 를 적지 않아도 `{}` 다
- 서버 정의 검증은 `connector_manifest.py` 의 `_load_connector` 가운데 `server` 를 읽는 부분이다. 지금은 `set(server) - {"command", "args", "env"}` 면 거절하고 `definition = {"command": command, "args": args, "env": env, "enabled": True}` 를 만든다. 이 파일은 지금 352줄이다
- `_snapshot_manifest(declared, server, tools, env_names)`(`connector_snapshot_manifest.py`)는 `server["args"]` 를 읽는다. 원격 정의에 부르면 `KeyError` 다
- `_load_connector` 의 반환 dict 는 `operator_env`, `owner_attachments_env`, `owner_output_env`, `owner_browser_env`, `owner_browser_login_url`, `optional_env`, `snapshot`, `execution_guard`(와 `_connector_binding_flags` 의 나머지) 키를 갖는다. `_connector_catalog_response` 와 `connector_run.py` 가 이 키를 읽는다
- 설치된 정의가 지금 manifest 와 같은지는 `connector_manifest.py` 의 `_server_matches(manifest, server)` 가 본다
- 소유 기록은 `hermes/plugins/dashboard-profile-api/connector_state.py` 가 다룬다
  - `_connector_state(value, target)` 는 profile 의 **모든** 항목에서 서버 키가 `{"command", "args", "env", "enabled"}`(와 `tools`)가 아니면 `ValueError` 다. 그래서 원격 기록 하나가 같은 profile 의 다른 커넥터의 다시 붙이기(`connector_binding.py`), 상태 조회와 probe(`connector_install.py`), `execute`(`connector_run.py`)까지 막는다
  - `_connector_server(manifest)` 는 `server["args"]` 와 `server["env"]` 를 복사한다
  - `_entry_field_env(plugin, entry)` 는 manifest 가 없으면 `entry["server"]["env"]` 를 읽는다
  - `_entry_manifest_mismatches(plugin, entry)` 는 `command`, `args`, `env` 를 비교한다
  - `_bind_entry_env(manifest, entry)` 는 떼기가 `.env` 에서 지울 이름이고 기록의 `server["env"]` 에서 `${이름}` 참조를 모은다
- 바인딩 설치는 `hermes/plugins/dashboard-profile-api/connector_binding.py`(395줄) 다. 선택 칸의 빈 값과 주인 env 를 `server["env"]` 에 넣는다. 떼기는 `owned["server"].get("env")` 가 dict 가 아니면 출력 디렉터리를 빈 목록으로 읽는다
- `connector_run.py` 는 `env["PATH"] = os.path.dirname(server["command"])` 를 두 곳(`call` 과 `execute`)에서 한다. `connector_guard.py` 의 `_guard_manifest` 는 `manifest["server"]["env"]` 를 읽는다(53줄)
- hermes 의 `.py` 파일은 400줄이 상한이다(`scripts/checks/check-file-length.mjs`). 그래서 원격 규칙은 새 모듈 `connector_remote.py` 에 둔다
- 바인딩 테스트는 `hermes/tests/test_dashboard_profile_api_connector_binding_install.py` 이고, 도우미는 `hermes/tests/dashboard_profile_api_support.py` 의 `connector_fixture()`, `connector(enabled, **overrides)`, `connector_status()`, `connector_probe(server, profile)` 다. 커넥터 두 개(`DEMO`, `OTHER`)를 같은 profile 에 붙이는 선례가 같은 파일의 `test_binding_two_connectors_adds_their_names_and_keeps_the_profile` 이다
- Hermes 는 `mcp_servers.<서버>.url` 과 `headers` 를 받고 헤더 값의 `${이름}` 을 profile secret scope 로 푼다. profile 틀 `hermes/profile-template/config.yaml.template` 의 Control Plane MCP 등록이 같은 모양이다. 근거는 `hermes/docs/hermes-contract.md` 의 「MCP 프로세스의 환경 값」 이다

## 의도 메모

- **주소**: 운영 목록 항목에 `url` 이 있으면 그것을 쓴다. 이때 `.mcp.json` 의 `url` 은 없어도 되고, 있으면 아래 모양 검사만 통과하면 된다. 운영 목록에 `url` 이 없으면 `.mcp.json` 의 `url` 이 있어야 한다. `.mcp.json` 의 `url` 은 늘 `https://` 만, 운영 목록의 `url` 은 `http://` 나 `https://` 를 받는다. 둘 다 계정 정보, 쿼리, fragment, `$` 가 있으면 거절한다. 운영 목록의 `http://` 는 운영자가 믿은 내부망 구간이라는 판단이고 그 판단은 `fos-home-infra` 가 갖는다
- **헤더**: 값은 `${이름}` 이나 `Bearer ${이름}` 두 모양만 받는다. 리터럴 비밀값을 플러그인 파일에 두는 길과 칸 값을 주소에 넣는 길을 함께 막는다. `.mcp.json` 서버의 `headers` 와 `assistant.json` 의 `headers` 를 합치고, 같은 이름(대소문자 무시)이 둘에 있으면 거절한다
- **칸**: 원격 플러그인의 `fields` 는 모두 필수 칸이고, 그 env 이름 집합이 헤더가 참조하는 이름 집합과 같아야 한다
- **로컬 플러그인**: `assistant.json` 이나 `.mcp.json` 서버가 `headers` 를 선언하면 거절한다. 운영 목록 항목에 `url` 이 있어도 거절한다
- **운영 목록**: 원격 플러그인인데 항목의 `command` 가 있거나 `env` 가 비어 있지 않으면 거절한다. `_connector_roots()` 의 반환 모양은 바꾸지 않고 키 `url` 만 더한다
- 원격에서 `operator_env`, `owner_attachments_env`, `owner_output_env`, `owner_browser_env`, `snapshot`, `execution_guard` 를 선언하면 거절한다. 모두 자식 프로세스 env 로 값을 건네는 선언이다
- 원격 manifest 도 반환 dict 의 칸을 모두 채운다. `operator_env=frozenset()`, `owner_*_env=None`, `owner_browser_login_url=None`, `optional_env=frozenset()`, `snapshot=None`, `execution_guard=None` 이다. `_snapshot_manifest` 는 로컬 갈래에서만 부른다
- 원격 서버 정의의 `tools.exclude` 는 로컬과 같다. `approval: always` 도구를 모델에 등록하지 않는다
- `connector_binding.py` 는 줄을 늘리지 않는다. 원격 갈래가 필요하면 `connector_remote.py` 의 함수 한 줄 호출로 바꾼다

## 작업 항목

### 1. `hermes/plugins/dashboard-profile-api/connector_manifest_io.py`

`_connector_roots()` 가 항목 키 `url`(문자열, 선택)을 받는다. 문자열이 아니면 그 항목만 버린다. 돌려주는 dict 에 `"url": url`(없으면 `None`)을 더한다. 모양 검사는 `connector_remote.py` 가 한다. 로그에 값을 싣지 않는다.

### 2. `hermes/plugins/dashboard-profile-api/connector_schema.py`

- `REMOTE_HEADER_VALUE_RE`: `^(Bearer )?\$\{(<ENV_NAME_RE 와 같은 글자>)\}$`
- `REMOTE_HEADER_NAME_RE`: HTTP 토큰 글자
- `REMOTE_FORBIDDEN_KEYS`: 위 「의도 메모」 의 원격 금지 선언 이름 튜플

### 3. `hermes/plugins/dashboard-profile-api/connector_remote.py`(신규)

- `_remote_server(entry, server, declared, fields) -> dict`: 위 「의도 메모」 의 주소, 헤더, 칸, 운영 목록, 금지 선언 규칙을 검사하고 `{"url", "headers", "enabled": True}` 를 돌려준다. 틀리면 `ValueError` 와 직접 쓴 사유다. 사유에 주소와 헤더 값을 넣지 않는다
- `_remote_server_matches(expected, recorded) -> bool`: `url` 과 `headers` 가 같은지 본다(`tools` 는 비교하지 않는다)
- `_remote_record_ok(server) -> bool`: 소유 기록의 원격 정의 모양 검사. 키가 `{"url", "headers", "enabled"}`(와 `tools`)이고, `headers` 가 문자열 dict 이고, `enabled` 가 참이다
- `_remote_header_env(server) -> set`: 기록의 `headers` 값에서 `REMOTE_HEADER_VALUE_RE` 로 `${이름}` 을 모은다
- `_is_remote(server) -> bool`: 기록이나 manifest 의 서버 정의가 `url` 을 가지면 참

### 4. `hermes/plugins/dashboard-profile-api/connector_manifest.py`

- 서버 정의를 읽는 부분을 `type` 으로 나눈다. `type` 이 없거나 `stdio` 면 지금 규칙, `http` 면 `_remote_server`, 그 밖이면 거절한다
- 로컬 갈래는 운영 목록 `url` 과 `headers` 선언을 거절한다
- manifest dict 에 키 `transport`(값 `stdio` 나 `http`)를 더한다. 카탈로그 응답에는 싣지 않는다
- 원격 manifest 의 칸은 「의도 메모」 대로 채운다
- `_server_matches` 는 `transport` 가 `http` 면 `_remote_server_matches` 를 쓴다

### 5. `hermes/plugins/dashboard-profile-api/connector_state.py`

- `_connector_state` 의 서버 모양 검사를 나눈다. `_is_remote(server)` 면 `_remote_record_ok`, 아니면 지금 검사다
- `_connector_server` 는 원격 정의면 `{**server, "headers": dict(server["headers"])}` 와 `tools` 사본이다
- `_entry_field_env` 는 manifest 가 없고 기록이 원격이면 `_remote_header_env` 다
- `_entry_manifest_mismatches` 는 원격이면 `url`, `headers` 를 비교한다
- `_bind_entry_env` 는 기록이 원격이면 `_remote_header_env` 를 모은다

### 6. `hermes/plugins/dashboard-profile-api/connector_binding.py`, `connector_guard.py`, `connector_run.py`

- 바인딩 설치는 `transport` 가 `http` 면 `server["env"]` 를 건드리지 않는다. 원격 플러그인에는 선택 칸과 주인 env 가 없다. 줄 수를 늘리지 않는다
- `_guard_manifest` 는 `manifest["server"].get("env", {})` 로 읽는다
- `connector_run.py` 의 `call` 과 `execute` 는 `manifest["transport"] == "http"` 면 자식 프로세스를 띄우지 않고 `unavailable` 로 끝낸다. phase 03 이 이 갈래를 채운다

### 7. 이 phase 를 검증하는 테스트

- `hermes/tests/test_connector_roots.py`: `url` 이 든 항목이 읽히고, `url` 이 문자열이 아니면 그 항목만 버려진다
- `hermes/tests/test_connector_manifest.py`(`ConnectorGateCase` 사본을 원격 정의로 고친다): 정상(`.mcp.json` `url`, 운영 목록 `url` 이 있으면 그것, 운영 목록 `url` 만 있고 `.mcp.json` 에 `url` 이 없음). 거절(운영 목록 `url` 없이 `http://` 정의 주소, 운영 목록 `url` 이 있어도 `.mcp.json` 쪽이 `http://`, 계정 정보, 쿼리, `$`, 리터럴 헤더 값, 선택 칸 참조, 헤더 겹침, 칸과 참조 불일치, `REMOTE_FORBIDDEN_KEYS` 선언 하나씩, 원격인데 운영 목록 `command` 나 비지 않은 `env`, 로컬인데 운영 목록 `url`, 로컬인데 `headers`, `type: sse`)
- `hermes/tests/test_dashboard_profile_api_connector_binding_install.py`:
  - 원격 플러그인을 붙이면 `config.yaml` 의 서버가 `{"url", "headers", "enabled"}` 와 `tools.exclude` 이고, `.env` 에 칸 값이 쓰이고, 이름 대응 파일에 그 서버가 실린다
  - 같은 manifest 로 다시 붙이면 `changed` 가 거짓이다. 떼면 그 `.env` key 가 지워진다
  - 원격과 로컬을 같은 profile 에 붙인 뒤 상태 조회가 200 이고 두 항목이 `configured`, 원격 서버의 probe 가 통과하고, 로컬을 다시 붙이기가 성공한다
- 도우미 `hermes/tests/dashboard_profile_api_support.py` 에 원격 플러그인 디렉터리를 만드는 함수를 더한다

## 검증

```bash
python3 -m unittest discover -s hermes/tests -p 'test_connector_roots.py'
python3 -m unittest discover -s hermes/tests -p 'test_connector_manifest.py'
python3 -m unittest discover -s hermes/tests -p 'test_dashboard_profile_api_connector_binding_install.py'
python3 -m unittest discover -s hermes/tests
node scripts/checks/check-file-length.mjs
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/dashboard-profile-api/connector_manifest_io.py` | 수정 |
| `hermes/plugins/dashboard-profile-api/connector_schema.py` | 수정 |
| `hermes/plugins/dashboard-profile-api/connector_remote.py` | 신규 |
| `hermes/plugins/dashboard-profile-api/connector_manifest.py` | 수정 |
| `hermes/plugins/dashboard-profile-api/connector_state.py` | 수정 |
| `hermes/plugins/dashboard-profile-api/connector_guard.py` | 수정 |
| `hermes/plugins/dashboard-profile-api/connector_run.py` | 수정 |
| `hermes/tests/dashboard_profile_api_support.py` | 수정 |
| `hermes/tests/test_connector_roots.py` | 수정 |
| `hermes/tests/test_connector_manifest.py` | 수정 |
| `hermes/tests/test_dashboard_profile_api_connector_binding_install.py` | 수정 |
