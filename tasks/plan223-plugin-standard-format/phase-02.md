# Phase 02. 원격 MCP 서버 정의를 검증하고 바인딩에 설치하고 뗀다

**Execution profile**: deep

## 목표

`.mcp.json` 의 서버가 `type: http` 인 플러그인을 카탈로그에 올리고, 바인딩 설치가 profile `config.yaml` 에 `url` 과 `headers` 로 된 서버 정의를 쓰고, 떼기가 헤더가 참조하던 `.env` key 를 지우게 한다.
금융 워크벤치처럼 서버가 MCP 를 직접 여는 플러그인을 Hermes 가 붙게 하기 위해서다.

**범위 외**: 대시보드 plugin 이 원격 서버를 직접 부르는 `call` 과 `execute`. phase 03 이 맡는다. 이 phase 에서 원격 플러그인의 `call` 과 `execute` 는 `unavailable` 로 끝나면 된다(자식 프로세스를 띄우지 않는다).

## 컨텍스트

**근거 문서**: `hermes/docs/adr/ADR-20261026-remote-mcp-plugin.md` 의 「결정」 표, `hermes/plugins/dashboard-profile-api/README.md` 의 「원격 MCP 커넥터는 자식 프로세스를 띄우지 않고 Streamable HTTP 로 붙는다」 목록, `docs/features/connector.md` 의 「플러그인 형식과 원격 MCP」

- 운영 목록은 `hermes/plugins/dashboard-profile-api/connector_manifest_io.py` 의 `_connector_roots()` 가 읽는다. 지금 항목 키는 `root`, `command`, `env` 만 받고 `{"root": Path, "command", "env"}` 로 돌려준다
- 서버 정의 검증은 `connector_manifest.py` 의 `_load_connector` 가운데 `server` 를 읽는 부분이다. 지금은 `set(server) - {"command", "args", "env"}` 면 거절하고 `definition = {"command": command, "args": args, "env": env, "enabled": True}` 를 만든다
- 설치된 정의가 지금 manifest 와 같은지는 같은 파일의 `_server_matches(manifest, server)` 가 본다. `server["command"]`, `server["args"]`, `server["env"]` 를 읽는다
- 떼기가 지울 `.env` 이름은 `hermes/plugins/dashboard-profile-api/connector_state.py` 의 `_bind_entry_env(manifest, entry)` 가 정한다. 지금은 기록의 `server["env"]` 에서 `${이름}` 참조만 모은다
- 바인딩 설치는 `hermes/plugins/dashboard-profile-api/connector_binding.py` 다. 선택 칸의 빈 값과 주인 env 를 `server["env"]` 에 넣는다(약 204~240줄). 떼기는 `owned["server"].get("env")` 에서 출력 디렉터리를 찾는다
- 실행 경로 `hermes/plugins/dashboard-profile-api/connector_run.py` 는 `env["PATH"] = os.path.dirname(server["command"])` 를 두 곳(`call` 과 `execute`)에서 한다. 원격 서버에는 `command` 가 없다
- `connector_guard.py` 의 `_guard_manifest` 는 `manifest["server"]["env"]` 를 읽는다(53줄)
- Hermes 는 `mcp_servers.<서버>.url` 과 `headers` 를 받고 헤더 값의 `${이름}` 을 profile secret scope 로 푼다. profile 틀 `hermes/profile-template/config.yaml.template` 의 Control Plane MCP 등록이 같은 모양이다. 근거는 `hermes/docs/hermes-contract.md` 의 「MCP 프로세스의 환경 값」 이다

## 의도 메모

- 주소는 사용자 입력에서 오지 않는다. `.mcp.json` 의 `url` 은 `https://` 만, 운영 목록의 `url` 은 `http://` 나 `https://` 를 받는다. 계정 정보, 쿼리, fragment, `$` 가 있으면 거절한다. 운영 목록의 `http://` 는 운영자가 믿은 내부망 구간이라는 판단이고 그 판단은 `fos-home-infra` 가 갖는다
- 헤더 값은 `${이름}` 이나 `Bearer ${이름}` 두 모양만 받는다. 리터럴 비밀값을 플러그인 파일에 두는 길과, 칸 값을 주소에 넣는 길을 함께 막는다
- 원격 플러그인의 `fields` 는 모두 필수 칸이고, 그 env 이름 집합이 헤더가 참조하는 이름 집합과 같아야 한다. 헤더에 쓰지 않는 칸은 값이 갈 곳이 없다
- 원격에서 `operator_env`, `owner_attachments_env`, `owner_output_env`, `owner_browser_env`, `snapshot`, `execution_guard` 를 거절한다. 모두 자식 프로세스 env 로 값을 건네는 선언이다
- 운영 목록 항목이 원격 플러그인인데 `command` 나 `env` 를 가지면 그 플러그인을 카탈로그에서 뺀다. 로컬 플러그인인데 `url` 을 가져도 뺀다. 운영자가 틀린 값을 넣은 것을 조용히 무시하지 않는다
- 원격 서버 정의의 `tools.exclude` 는 로컬과 같다. `approval: always` 도구를 모델에 등록하지 않는다

## 작업 항목

### 1. `hermes/plugins/dashboard-profile-api/connector_manifest_io.py`

`_connector_roots()` 가 항목 키 `url`(문자열, 선택)을 받는다. 돌려주는 dict 에 `"url": url or None` 을 더한다. 모양 검사(절대 주소, 계정 정보 없음)는 manifest 쪽에서 한다. 로그에 값을 싣지 않는다.

### 2. `hermes/plugins/dashboard-profile-api/connector_schema.py`

- `REMOTE_HEADER_VALUE_RE`(`^(Bearer )?\$\{([A-Z][A-Z0-9_]*)\}$` 처럼 env 이름 규칙 `ENV_NAME_RE` 와 같은 글자), `REMOTE_HEADER_NAME_RE`(HTTP 토큰 글자) 를 둔다
- 원격에서 받지 않는 선언 이름 목록 `REMOTE_FORBIDDEN_KEYS` 를 둔다

### 3. `hermes/plugins/dashboard-profile-api/connector_manifest.py`

- 서버 정의를 읽는 부분을 `type` 으로 나눈다. `type` 이 없거나 `stdio` 면 지금 규칙, `http` 면 `_remote_server(root, entry, server, declared, fields)`, 그 밖이면 거절한다
- `_remote_server` 는 위 「의도 메모」 의 규칙을 검사하고 `{"url": <운영 목록 url 이나 정의 url>, "headers": {<이름>: <값 원문>}, "enabled": True}` 를 만든다. 헤더는 `.mcp.json` 서버의 `headers` 와 `assistant.json` 의 `headers` 를 합치고 겹치면 거절한다
- 로컬 서버이고 운영 목록 항목에 `url` 이 있으면 거절한다. 원격 서버이고 `entry["command"]` 나 `entry["env"]` 가 있으면 거절한다
- manifest dict 에 키 `transport`(값 `stdio` 나 `http`)를 더한다. 카탈로그 응답(`_connector_catalog_response`)에는 싣지 않는다. Control Plane 이 전송을 알 필요가 없다
- `_server_matches` 는 `transport` 가 `http` 면 `url` 과 `headers` 가 지금 정의와 같은지만 본다
- `_connector_env_keys()` 는 그대로다. 원격 칸도 `fields` 에 있다

### 4. `hermes/plugins/dashboard-profile-api/connector_state.py`

`_bind_entry_env` 가 기록의 `server["headers"]` 값에서도 `REMOTE_HEADER_VALUE_RE` 로 `${이름}` 을 모은다. 기록에 `env` 가 없어도 동작한다.

### 5. `hermes/plugins/dashboard-profile-api/connector_binding.py`, `connector_guard.py`, `connector_run.py`

- 바인딩 설치는 `transport` 가 `http` 면 `server["env"]` 를 건드리지 않는다. 원격 플러그인에는 선택 칸과 주인 env 가 없다
- 떼기의 출력 디렉터리 찾기는 `env` 가 없는 기록에서 빈 목록이다(지금 코드가 dict 가 아니면 빈 목록으로 읽는지 확인하고, 아니면 고친다)
- `_guard_manifest` 는 `manifest["server"].get("env", {})` 로 읽는다
- `connector_run.py` 의 `call` 과 `execute` 는 `transport` 가 `http` 면 이 phase 에서는 자식 프로세스를 띄우지 않고 `unavailable` 로 끝낸다. phase 03 이 이 갈래를 채운다

### 6. 이 phase 를 검증하는 테스트

- `hermes/tests/test_connector_roots.py`: `url` 이 든 항목이 읽히고, `url` 이 문자열이 아니면 그 항목만 버려진다
- `hermes/tests/test_connector_manifest.py`: 원격 정의의 표 테스트. 정상(정의 `url`, 운영 목록 `url` 이 있으면 그것), 거절(`http://` 정의 주소, 계정 정보, 쿼리, `$`, 리터럴 헤더 값, 선택 칸 참조, 헤더 겹침, 칸과 참조 불일치, `REMOTE_FORBIDDEN_KEYS` 선언 하나씩, 원격인데 운영 목록에 `command`, 로컬인데 `url`, `type: sse`)
- `hermes/tests/test_dashboard_profile_api_connector_binding_install.py`: 원격 플러그인을 붙이면 `config.yaml` 의 서버가 `{"url", "headers", "enabled"}` 와 `tools.exclude` 이고, `.env` 에 칸 값이 쓰이고, 이름 대응 파일에 그 서버가 실린다. 떼면 그 `.env` key 가 지워진다. 같은 manifest 로 다시 붙이면 `changed` 가 거짓이다
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
| `hermes/plugins/dashboard-profile-api/connector_manifest.py` | 수정 |
| `hermes/plugins/dashboard-profile-api/connector_state.py` | 수정 |
| `hermes/plugins/dashboard-profile-api/connector_binding.py` | 수정 |
| `hermes/plugins/dashboard-profile-api/connector_guard.py` | 수정 |
| `hermes/plugins/dashboard-profile-api/connector_run.py` | 수정 |
| `hermes/tests/dashboard_profile_api_support.py` | 수정 |
| `hermes/tests/test_connector_roots.py` | 수정 |
| `hermes/tests/test_connector_manifest.py` | 수정 |
| `hermes/tests/test_dashboard_profile_api_connector_binding_install.py` | 수정 |
