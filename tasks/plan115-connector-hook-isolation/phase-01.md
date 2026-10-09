# Phase 01. 서버 정의의 `tools` 어긋남을 그 커넥터 항목에 가둔다

**Execution profile**: deep

## 목표

대시보드 plugin 의 `GET /api/connectors` 가 커넥터 하나의 manifest 변경으로 생긴 어긋남(서버 정의의 `tools`, 이름 대응 파일의 그 서버 항목의 `tools`)을 profile 단위 `policy_hook` 이 아니라 그 커넥터 항목의 `configured` 로 답하게 한다.
커넥터 하나의 manifest 가 바뀌어도 같은 profile 에 붙은 다른 바인딩이 `READY` 가 될 수 있어야 한다.
`policy_hook` 이 거짓이면 어느 조건에서 거짓인지 조건 이름만 경고 로그에 남긴다.

**범위 외**: Control Plane 의 판정과 오류 코드(phase 02), 어긋난 바인딩 다시 설치(phase 03). `hermes/connectors/naver-blog/` 와 그 스킬은 다른 작업이 고치는 중이라 건드리지 않는다.

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261009-connector-install-drift.md`, `docs/backend/connector-tool-policy.md` 의 「hook 이 켜져 있는지」, `docs/backend/connector-install.md` 의 「옛 설치」 와 「바인딩 설치」 의 `configured` 조건

- `hermes/plugins/dashboard-profile-api/connector_status.py` 의 `_policy_hook_active(profile_dir, config, state)` 가 끝의 `for plugin, entry in state.items()` 고리에서 서버 정의의 `tools` 를 manifest 의 `server.tools` 와 견주고 다르면 거짓을 낸다. 이 고리를 뺀다
- 같은 함수가 `(profile_dir / CONNECTOR_TOOL_MAP).read_bytes() != _tool_map_bytes(_connector_tool_map(state, detached))` 로 이름 대응 파일 전체를 바이트로 견준다. 도구를 하나 더해도 profile 전체가 거짓이 된다. 이것을 모양 비교와 서버별 `tools` 비교로 나눈다(작업 항목 1)
- `fos-ctx` hook(`hermes/plugins/fos-ctx/connector_policy.py` 의 `_connector_server`)은 대응의 `tools` 에 이름이 없어도 `prefix` 로 서버를 잡아 Control Plane 에 묻는다. 그래서 서버별 `tools` 가 낡아도 판정을 건너뛰지 않는다. 서버가 대응에서 빠지면 바인딩 profile 에서는 판정 없이 나가므로 서버 목록과 접두사는 profile 단위로 계속 본다
- `hermes/plugins/dashboard-profile-api/__init__.py` 가 `_policy_hook_active` 를 `connector_install` 에서 다시 내보낸다. `connector_install.py` 의 그 import 는 쓰이지 않게 돼도 지우지 않는다. 지우면 plugin 을 불러오지 못한다
- `hermes/plugins/dashboard-profile-api/connector_install.py` 의 GET 처리(`if request.method.upper() == "GET":` 아래)가 커넥터마다 `configured` 를 정한다. 옛 설치 항목은 `configured = isolated`, 바인딩 항목은 `allowed`, `_entry_matches_manifest`, `_bind_skills_installed` 를 본다. 두 갈래 모두에 `entry["server"].get("tools") == manifest["server"].get("tools")` 를 더한다. 그 앞의 조건이 이미 `servers.get(manifest["mcp_server"]) == entry["server"]` 를 확인하므로 기록의 값으로 견주면 된다
- 로그는 `connector_install.py` 의 `logger` 를 쓴다. 기존 줄의 모양은 `"dashboard-profile-api: connector 요청 실패 단계=%s id=%s exception=%s"` 다. profile 이름, 경로, 파일 내용, 환경 변수는 남기지 않는다
- 시험 도우미는 `hermes/tests/dashboard_profile_api_support.py` 다. 커넥터 둘(`DEMO`, `OTHER`)을 붙이는 본보기는 `hermes/tests/test_dashboard_profile_api_connector_binding_install.py` 의 첫 시험이다. manifest 의 도구 선언을 바꾸는 도우미는 `declare_tools(root, tools)` 다

## 의도 메모

- `_server_matches` 와 `_entry_matches_manifest` 에 `tools` 비교를 넣지 않는다. 넣으면 실행과 probe, 떼기가 그 항목을 manifest 와 다른 것으로 다뤄 옛 기록을 가진 연결이 끊긴다. 상태 조회의 `configured` 만 바꾼다
- 어긋난 서버의 `approval: always` 도구가 재시작 전까지 gateway 에 남는 것은 받아들인 경계다. ADR 의 「보안 경계」 를 본다

## 작업 항목

### 1. `connector_status.py`

- `_tool_map_drift(profile_dir, state) -> set[str] | None` 을 만든다. 뗀 서버 기록을 읽고(`_detached_servers`) `_connector_tool_map(state, detached)` 로 계산한 대응과 파일의 대응을 견준다. `v`, `isolated`(없으면 참으로 읽는 기존 규칙 그대로 둘 다 같은 키 집합인지), 서버 이름 집합, 서버마다의 `connector` 와 `prefix` 가 모두 같으면 `tools` 가 다른 서버 이름의 집합을 돌려준다. 하나라도 다르거나 파일이 링크이거나 읽지 못하면 `None` 이다
- `_policy_hook_failure(profile_dir, config, state) -> str | None` 을 만든다. 거짓인 첫 조건 이름을 돌려주고 모두 통과하면 `None` 이다. 이름은 `plugin_config`(설정이 `fos-ctx` 를 켜지 않음), `plugin_bundle`(묶음을 읽지 못함), `plugin_files`(설치한 파일이 묶음과 다름), `detached`(뗀 서버 기록이 링크), `tool_map`(`_tool_map_drift` 가 `None`), `unreadable`(읽다가 예외)이다
- `_policy_hook_active` 는 `_policy_hook_failure(...) is None` 을 돌려준다. `tools` 고리와 그 때문에만 쓰던 import(`_connector_roots`, `_entry_mode`, `BIND_MODE`, `_entry_matches_manifest`, `_connector_manifest` 가운데 다른 함수가 쓰지 않는 것)를 지운다
- 독스트링의 조건 설명이 문서와 맞게 한다

### 2. `connector_install.py`

- GET 에서 `_tool_map_drift(profile_dir, state)` 를 한 번 구한다. `configured` 두 갈래에 `entry["server"].get("tools") == manifest["server"].get("tools")` 와 「`drift` 가 `None` 이 아니면 그 커넥터의 서버 이름(`manifest["mcp_server"]`)이 `drift` 에 없다」 를 더한다. `drift` 가 `None` 이면 `policy_hook` 이 거짓이므로 이 조건으로 `configured` 를 바꾸지 않는다. 바인딩 갈래의 주석에 그 항목만 거짓이고 `policy_hook` 은 보지 않는다고 적는다
- `policy_hook` 을 `_policy_hook_failure` 로 구하고, `None` 이 아니면 `logger.warning("dashboard-profile-api: policy_hook 거짓 조건=%s", failure)` 를 남긴다. 응답의 `policy_hook` 은 `failure is None` 이다

### 3. 시험 `hermes/tests/test_dashboard_profile_api_connector_binding_install.py`

- 새 시험(`tools.exclude` 만 어긋남): 붙이기 전에 `declare_tools(demo 의 root, {"list_scopes": {"risk": "READ"}, "purge": {"risk": "WRITE"}})` 로 둔다. `DEMO` 와 `OTHER` 를 같은 profile 에 붙인 뒤 `purge` 만 `{"risk": "DESTRUCTIVE"}` 로 올린다. 도구 이름이 같아 이름 대응은 그대로이고 서버 정의의 `tools.exclude` 만 어긋난다. `GET /api/connectors` 가 `DEMO` 는 `configured: false`, `OTHER` 는 `configured: true`, `policy_hook: true` 로 답하는지 본다. `DEMO` 를 다시 붙이면 `DEMO` 도 `configured: true` 이고 응답의 `restart_required` 가 참인지 본다
- 새 시험(도구 추가): `DEMO` 와 `OTHER` 를 붙인 뒤 `DEMO` manifest 에 READ 도구 하나를 더한다. 이름 대응의 `demo` 서버 `tools` 만 어긋나므로 `DEMO` 는 `configured: false`, `OTHER` 는 `configured: true`, `policy_hook: true` 다
- 새 시험(구조 어긋남): 이름 대응 파일에서 `other` 서버 항목을 지우면 `policy_hook: false` 이고 `조건=tool_map` 경고를 남긴다
- 새 시험: `plugins.disabled` 에 `fos-ctx` 를 넣은 profile 의 GET 이 `policy_hook: false` 이고 `assertLogs` 로 `조건=plugin_config` 경고 한 줄을 남기는지 본다. 로그에 profile 이름이 없는지도 본다

### 4. 기존 시험

- `hermes/tests/test_dashboard_profile_api_connector_install_plugin.py` 의 `test_tools_that_always_need_approval_are_excluded_from_the_server_definition` 는 서버 정의의 `tools` 가 낡은 상태에서 `configured: True` 를 기대한다. 바뀐 판정에 맞춰 `configured: False` 로 고친다. `policy_hook` 기대값은 실행해 확인하고 바뀐 판정(서버별 `tools` 차이는 profile 단위로 보지 않음)에 맞춘다
- `hermes/tests/test_dashboard_profile_api_connector_install_legacy.py` 의 `test_legacy_ownership_record_meets_a_manifest_that_excludes_tools` 처럼 `tools` 어긋남에 `policy_hook: false` 를 기대하던 시험은 바뀐 판정에 맞춘다. 실행해 보고 각 기대값이 위 표의 판정과 맞는지 확인한다

## 검증

```bash
python3 -m unittest discover -s hermes/tests
bash scripts/check-hermes-contract.sh
```

둘 다 종료 코드 0 이어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/dashboard-profile-api/connector_status.py` | 수정 |
| `hermes/plugins/dashboard-profile-api/connector_install.py` | 수정 |
| `hermes/tests/test_dashboard_profile_api_connector_binding_install.py` | 수정 |
| `hermes/tests/test_dashboard_profile_api_connector_install_legacy.py` | 수정 |
| `hermes/tests/test_dashboard_profile_api_connector_install_plugin.py` | 수정 |
