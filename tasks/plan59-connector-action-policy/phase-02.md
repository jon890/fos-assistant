# Phase 02. 설치가 이름 대응 파일을 쓰고 hook 상태를 답한다

**Execution profile**: deep

## 목표

커넥터 설치가 그 profile 에 이름 대응 파일을 쓰고, `approval: always` 인 도구를 모델에게서 빼고, `fos-ctx` 를 묶음의 판으로 맞춘다.
설치 조회가 `policy_hook` 을 답한다. hook 이 꺼졌거나 옛 판인 profile 을 Control Plane 이 알아볼 수 있어야 하기 때문이다.

**범위 외**: hook 이 대응 파일을 읽는 것(phase 05), Control Plane 이 `policy_hook` 을 읽는 것(phase 03).

## 컨텍스트

- 고칠 파일은 `hermes/plugins/dashboard-profile-api/__init__.py` 다
- `_connector_config(profile_dir, plugin, enabled)` 가 설치와 제거를 한다. 바꿀 파일의 원본을 `originals` 에, 새 값을 `values` 에 모아 `_atomic_private_write` 로 쓰고 실패하면 쓴 것을 되돌린다. 지금 다루는 파일은 `config.yaml`, `.fos-connectors.json`(`CONNECTOR_STATE`), `.env`, `SOUL.md` 다
- 소유 기록의 항목은 `{"server", "allowlist_added", "mcp_server"}` 이고 `_connector_state` 가 `server` 의 키를 정확히 `{"command", "args", "env", "enabled"}` 로 검사한다. `_server_matches(manifest, server)` 가 기록과 지금 manifest 의 실행 정의가 같은지 본다
- `_connector_request` 의 `GET` 분기가 `{"profile", "connectors": [...]}` 를 답한다
- `PROFILE_PLUGIN_DIR = PLUGIN_DIR / "profile-plugins"` 는 설치 묶음에만 있다. `hermes/bundle.sh` 가 `hermes/plugins/fos-ctx/` 를 그 아래로 복사한다. `_copy_profile_plugin(name, profile_dir)` 은 새 profile 에만 쓰고 이미 있으면 `FileExistsError` 다
- profile 틀(`hermes/profile-template/config.yaml.template`)은 `plugins.enabled: [fos-ctx]`, `plugins.disabled: []`, `plugins.entries.fos-ctx.allow_tool_override: false` 를 쓴다
- 테스트는 `hermes/tests/test_dashboard_profile_api.py` 의 `ProfileApiRouteTest` 다. `bundle.sh` 로 만든 묶음의 `__init__.py` 를 읽으므로 `PROFILE_PLUGIN_DIR` 이 있다. 헬퍼는 `make_profile`, `connector_fixture()`, `connector(enabled=True, **overrides)`, `connector_status()`, `alice_config()` 다
- phase 01 이 manifest dict 에 `schema`, `tools`(도구 정책 dict), `call_tools` 를 넣었고 `_hermes_tool_name(server, tool)` 을 만들었다

**근거 문서**: `docs/connectors.md` 의 「이름 대응」, 「hook 이 켜져 있는지」, `docs/hermes/connector-policy.md` 의 「도구를 모델에게서 빼는 설정」, `docs/adr/ADR-049-커넥터-도구-호출은-profile-plugin-의-hook-이-control-plane-에-물어-판정한다.md`

## 의도 메모

- 대응 파일은 `values` 묶음 안에서 쓴다. 설정만 바뀌고 대응 파일이 안 바뀐 상태가 남으면 hook 이 새 도구를 모두 막는다
- 옛 판이 남긴 소유 기록(`server` 에 `tools` 키 없음)을 그대로 인정한다. 이미 설치한 연결이 끊기지 않아야 한다(ADR-041)
- `fos-ctx` 파일을 바꿨으면 `restart_required: true` 로 답한다. 떠 있는 gateway 가 새 코드를 읽는지는 아직 확인하지 못했다
- 묶음에 `profile-plugins/fos-ctx` 가 없는 환경(저장소에서 바로 읽은 plugin)에서는 `fos-ctx` 를 건드리지 않고 `policy_hook` 을 거짓으로 답한다. 설치는 실패시키지 않는다

## 작업 항목

### 1. 대응 파일

상수 `CONNECTOR_TOOL_MAP = ".fos-connector-tools.json"` 을 더한다.

`_connector_tool_map(state: dict) -> dict` 를 더한다. 소유 기록의 커넥터 가운데 운영 목록에 있고 manifest 를 읽을 수 있는 것으로 아래를 만든다.

```json
{"v": 1, "servers": {"<mcp_server>": {"connector": "<id>", "prefix": "mcp__<정규화한 서버>__", "tools": {"<등록 이름>": "<원래 이름>"}}}}
```

- `prefix` 는 `_hermes_tool_name(mcp_server, "")` 의 결과다
- `tools` 는 manifest 의 `tools`(도구 정책 dict) 키마다 `_hermes_tool_name(mcp_server, 이름)` 을 계산한 것이다
- 직렬화는 `json.dumps(값, sort_keys=True, ensure_ascii=False) + "\n"` 로 고정한다. `policy_hook` 판정이 바이트로 견준다

`_connector_config` 가 다루는 파일에 `profile_dir / CONNECTOR_TOOL_MAP` 을 더한다(`originals`, 심볼릭 링크 검사, `values`).
설치와 제거 모두 바뀐 뒤의 `state` 로 `_connector_tool_map(state)` 을 계산해 `values` 에 넣는다. 제거한 뒤 서버가 하나도 없어도 `{"v": 1, "servers": {}}` 를 쓴다.

### 2. `approval: always` 도구를 서버 정의에서 뺀다

`_load_connector` 가 만드는 `manifest["server"]` 에, `approval` 이 `always` 인 도구가 하나라도 있으면 `"tools": {"exclude": [<이름 정렬>]}` 을 더한다. 없으면 키를 넣지 않는다.

- `_connector_server(manifest)` 의 사본에 `tools` 가 있으면 `{"exclude": list(...)}` 로 깊게 복사한다
- `_connector_state` 의 `server` 키 검사를 「`{"command", "args", "env", "enabled"}` 와 같거나 거기에 `tools` 를 더한 것」 으로 바꾼다. `tools` 는 `{"exclude": [문자열...]}` 모양만 받는다
- `_server_matches` 는 `server.get("tools")` 와 `expected.get("tools")` 가 같은지도 본다

### 3. `fos-ctx` 를 묶음의 판으로 맞춘다

`_profile_plugin_files(name) -> dict[str, bytes] | None` 을 더한다. `PROFILE_PLUGIN_DIR / name` 의 `plugin.yaml` 과 `__init__.py` 의 바이트다. 디렉터리나 두 파일 가운데 하나가 없으면 None 이다.

`_connector_config` 의 설치 분기(`enabled`)에서 `_profile_plugin_files("fos-ctx")` 가 None 이 아니면 `profile_dir / "plugins" / "fos-ctx" / <파일>` 을 `originals` 와 `values` 에 더한다.

- 그 경로나 `plugins/fos-ctx` 디렉터리가 심볼릭 링크이면 `ValueError`
- 디렉터리가 없으면 만든다(`0o755`). 쓴 파일은 `0o644` 로 둔다. `_atomic_private_write` 는 `0o600` 으로 만들므로 쓴 뒤 `os.chmod` 한다
- plugin 파일의 원본과 새 값이 하나라도 다르면 돌려주는 `restart_required` 를 True 로 한다. 기존 규칙(`bool(owned)`)과 논리 OR 다
- 「바뀐 것이 없다」 판정(`all(originals[path] == value ...)`)에 plugin 파일과 대응 파일도 들어간다

### 4. `policy_hook`

`_policy_hook_active(profile_dir, config: dict, state: dict) -> bool` 을 더한다. 아래가 모두 맞을 때만 True 이고, 읽다가 예외가 나면 False 다.

- `config["plugins"]["enabled"]` 목록에 `fos-ctx` 가 있고 `config["plugins"].get("disabled")` 목록에 없다
- `config["plugins"]["entries"]["fos-ctx"]["allow_tool_override"] is False`
- `_profile_plugin_files("fos-ctx")` 가 None 이 아니고 그 profile 의 두 파일과 바이트가 같다
- `profile_dir / CONNECTOR_TOOL_MAP` 의 바이트가 `_connector_tool_map(state)` 를 직렬화한 것과 같다

`_connector_request` 의 `GET` 응답에 `"policy_hook": _policy_hook_active(...)` 를 더한다.

### 5. 이 phase 를 검증하는 `hermes/tests/test_dashboard_profile_api.py`

| 무엇을 한다 | 기대 |
| --- | --- |
| `schema: 1` fixture 를 설치 | `.fos-connector-tools.json` 이 `{"v": 1, "servers": {"demo": {"connector": "demo-notes", "prefix": "mcp__demo__", "tools": {"mcp__demo__list_scopes": "list_scopes"}}}}` |
| `schema: 2`(`list_scopes`, `env_view`, `write_note`)로 고쳐 설치 | `tools` 에 등록 이름 셋 |
| 설치한 뒤 `connector_status()` | `policy_hook` 이 참 |
| profile 의 `plugins/fos-ctx/__init__.py` 에 한 줄을 덧붙인 뒤 조회 | `policy_hook` 이 거짓 |
| 그 상태에서 다시 설치 | 파일이 묶음의 것으로 돌아오고 응답의 `restart_required` 가 참, 조회의 `policy_hook` 이 참 |
| `config.yaml` 의 `plugins.enabled` 에서 `fos-ctx` 를 뺀 뒤 조회 | `policy_hook` 이 거짓 |
| `allow_tool_override` 를 true 로 바꾼 뒤 조회 | `policy_hook` 이 거짓 |
| 대응 파일을 지운 뒤 조회 | `policy_hook` 이 거짓 |
| `purge: {risk: DESTRUCTIVE}` 를 선언하고 설치 | `config.yaml` 의 `mcp_servers.demo.tools.exclude` 가 `["purge"]`, 소유 기록의 `server` 에도 같은 값 |
| `always` 도구가 없는 manifest 를 설치 | 서버 정의에 `tools` 키가 없다 |
| 옛 판 소유 기록(`server` 에 `tools` 없음)이 있는 profile 을 조회 | 기존처럼 `configured` 가 참 |
| 제거 | 대응 파일이 `{"v": 1, "servers": {}}`, `SOUL.md` 는 그대로 |
| 설치 중 `config.yaml` 쓰기가 실패하게 만든다(기존 되돌리기 테스트의 방식) | 대응 파일과 plugin 파일도 원래대로 |

같은 값으로 다시 설치하면 `changed: false` 인 기존 단언이 깨지지 않는지 본다.

### 6. `hermes/README.md` 와 `docs/` 대조

`hermes/README.md` 의 커넥터 절에 대응 파일과 `policy_hook` 을 각각 한 줄로 더한다.
구현하다 `docs/connectors.md` 의 「이름 대응」 이나 「hook 이 켜져 있는지」 와 달라진 것이 있으면 멈추고 보고한다. 문서를 임의로 고치지 않는다.

## 검토 반영

**이 절이 위의 내용과 다르면 이 절을 따른다.**

- 이 파일의 설치 코드는 그 뒤 바뀌었다. `_connector_config` 가 API 도구 목록을 `_connector_allowlist(state, servers)` 로 통째로 다시 쓰고 Control Plane MCP 등록을 지운다. 지금 코드를 읽고 그 위에 더한다
- `_server_matches` 는 `tools` 를 견주지 않는다. 옛 소유 기록(`server` 에 `tools` 없음)을 가진 연결이 `always` 도구를 선언한 manifest 를 만나도 조회, 재설치, 해제가 그대로 되어야 한다. 재설치가 설정과 소유 기록의 서버 정의를 지금 manifest 의 것(`tools.exclude` 포함)으로 덮어쓴다
- `_policy_hook_active` 는 조건을 하나 더 본다. 설치된 커넥터마다 `config["mcp_servers"][<서버>].get("tools")` 가 `manifest["server"].get("tools")` 와 같다
- `fos-ctx` 파일이 바뀐 것을 `restart_required` 에 더하지 않는다. `PUT /api/connectors` 응답에 `"plugin_updated": <bool>` 을 따로 낸다. 제거 응답과 바뀐 것이 없는 응답은 false 다. `restart_required` 의 기존 규칙은 그대로 둔다
- 되돌리기 테스트는 `values` 에서 **마지막에 쓰는 파일**의 쓰기를 실패시킨다. 맨 먼저 쓰는 파일을 실패시키면 되돌릴 것이 없어 되돌리기를 지워도 통과한다
- 테스트 표의 「다시 설치 … `restart_required` 가 참」 은 「`plugin_updated` 가 참」 으로 읽는다. 같은 값으로 한 번 더 설치하면 `plugin_updated` 가 거짓이다
- 테스트를 더한다: 옛 소유 기록과 `purge: {risk: DESTRUCTIVE}` 를 선언한 manifest 에서 (1) 조회가 200 이고 `policy_hook` 이 거짓, (2) 재설치 뒤 `tools.exclude` 가 쓰이고 `policy_hook` 이 참, (3) 해제가 200

## 검증

```bash
# cwd: 저장소 root
python3 -m unittest discover -s hermes/tests
python3 -m unittest discover -s hermes/tests -p 'test_dashboard_profile_api.py'
scripts/check-public-safe.sh
```

- 셋 다 종료 코드 0

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/dashboard-profile-api/__init__.py` | 수정 |
| `hermes/tests/test_dashboard_profile_api.py` | 수정 |
| `hermes/README.md` | 수정 |
