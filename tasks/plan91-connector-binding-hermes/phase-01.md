# Phase 01. 대시보드 plugin 의 보관 파일과 바인딩 설치

**Execution profile**: deep

## 목표

연결의 칸 값을 Hermes 쪽 보관 파일에 두고, 그 값을 일반 에이전트의 profile 에 복사해 커넥터를 설치하는 경로를 대시보드 plugin 에 연다.
바인딩 설치는 그 profile 의 Control Plane MCP 와 내장 도구와 `SOUL.md` 를 건드리지 않는다.

**범위 외**: `fos-ctx` 의 판정과 계약 문서(phase 02). Control Plane 이 이 경로를 부르는 일(`plan92-connector-bindings`). 옛 설치(`bind` 칸이 없는 요청)의 동작은 바꾸지 않는다.

## 컨텍스트

**근거 문서**: `docs/adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md`, `docs/backend/connector-install.md` 의 「대시보드 plugin 계약」

- 결정: `docs/adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md`
- 지금 계약: `docs/backend/connector-install.md` 의 「대시보드 plugin 계약」, `docs/backend/connector-tool-policy.md` 의 「도구 호출 판정」 과 「이름 대응」, `hermes/README.md` 의 「커넥터」
- 대시보드 plugin: `hermes/plugins/dashboard-profile-api/__init__.py`
  - 설치 본체 `_connector_config(profile_dir, plugin, enabled)`. 지금은 `platform_toolsets.api_server` 를 `_connector_allowlist(state, servers)` 로 통째로 다시 쓰고 `servers.pop(CONTROL_PLANE_MCP, None)` 로 Control Plane MCP 등록을 지우며 `manifest["persona"]` 를 `SOUL.md` 에 쓴다
  - 요청 처리 `_connector_request(request)`. 본문은 정확히 `{profile, plugin, enabled}` 이고 `MANAGED_MARKER` 가 없는 profile 은 401 이다. GET 의 `configured` 는 profile 단위의 `isolated` 판정에 기댄다
  - 소유 기록 검사 `_connector_state(value)`. 항목 칸은 `server`, `allowlist_added`, `mcp_server` 만 받는다
  - 이름 대응 `_connector_tool_map(state)` 는 `{"v": 1, "servers": {...}}` 를 만든다
  - `_check_config_update(request)` 는 검사만 한다. 실제 쓰기는 Hermes 처리기가 요청 본문의 키로 한다(그 함수의 「처리기의 병합은 본문의 키만 쓴다」 주석). 그래서 plugin 이 목록에 이름을 더해 쓰는 방법은 없고, 빠진 요청을 거절하는 것만 된다
  - `_connector_execute_request`, `_check_env_update`, `_check_env_delete`, `_check_connector_probe`, `_connector_request` 가 관리 표식을 요구한다. `_connector_call_request` 는 profile 을 받지 않는다
  - 커넥터 key 의 env 쓰기와 지우기(`_check_env_update`, `_check_env_delete`)는 지금 카탈로그 manifest 의 칸 key 인지만 본다
  - 칸 검사는 `_connector_fields(declared)` 가 만든 manifest 의 `fields` 를 쓴다. 키, 필수, `pattern` 검사는 `call` 이 이미 한다. 그 검사를 그대로 다시 쓴다
- 시험: `hermes/tests/test_dashboard_profile_api.py`, `hermes/tests/test_connector_call.py`, `hermes/tests/test_connector_execute.py`
- Hermes 의 사실: 떠 있는 profile 에 더한 `mcp_servers` 는 gateway 를 재시작해야 발견된다. 허용 목록 변경은 다음 실행부터다(`docs/hermes/tools-and-skills.md` 의 「변경이 적용되는 시점」). 로컬 스킬은 올린 스킬보다 먼저 선택된다(`hermes/plugins/fos-ctx/__init__.py` 의 `skill_manage` 설명)

## 의도 메모

- 값의 원본을 Hermes profile 로 두지 않는다. 에이전트가 없는 profile 은 gateway 의 profile 감시와 이름 예약에 끼어든다(ADR-083 의 대안 기각)
- 한 profile 에 옛 설치와 바인딩 설치를 섞지 않는다. 옛 설치는 Control Plane MCP 를 지운 profile 이라 섞으면 두 규칙이 서로를 깬다. 섞으려는 요청은 409 다
- 바인딩 설치의 `restart_required` 는 바뀐 것이 있으면 늘 참이다. 떠 있는 profile 에 서버를 더하는 것이라 재시작 없이는 도구가 보이지 않는다
- 떼기는 서버 이름을 도구 목록에서 빼므로 다음 실행부터 막힌다. 떠 있는 MCP 프로세스는 남지만 모델에 보이지 않는다. 그래서 떼기의 `restart_required` 는 거짓이다
- 도구 목록 보존은 거절로 강제한다. Control Plane 의 도구 저장이 붙은 커넥터 서버 이름을 함께 보내고(`plan92-connector-bindings`), plugin 은 그 이름이 빠진 요청을 409 로 거절한다. 조용히 지워지는 길을 남기지 않는다
- 바인딩 profile 의 대응 파일에는 소유 기록의 모든 서버를 싣는다. manifest 를 읽지 못한 서버도 도구 없이 싣는다. 대응에 없는 서버를 hook 이 통과시키므로, 실리지 않은 커넥터 서버가 있으면 그 서버의 도구가 판정 없이 나간다

## 작업 항목

### 1. 보관 파일

`hermes/plugins/dashboard-profile-api/__init__.py` 에 더한다.

- 상수 `CONNECTOR_VAULT_DIR = "connector-vault"`(대시보드의 HERMES_HOME 아래, 권한 700), `VAULT_ID_RE = re.compile(r"^c[1-9][0-9]{0,18}$")`
- 파일은 `<HERMES_HOME>/connector-vault/<vault>.json`, 권한 600, `_atomic_private_write` 로 쓴다. 본문은 `{"v": 1, "connector": "<id>", "values": {"<field key>": "<값>"}}`
- `PUT /api/connector-vault`: 본문 `{vault, connector, values}`. `connector` 는 운영 목록의 커넥터, `values` 는 그 manifest 의 칸 검사(키, 필수, 형식, 한 줄)를 지나야 한다. 같은 `vault` 의 파일이 다른 `connector` 면 409. 답은 `{"ok": true}`
- `DELETE /api/connector-vault`: 본문 `{vault}`. 없으면 `{"changed": false}`, 지우면 `{"changed": true}`
- `POST /api/connector-vault/import`: 본문 `{vault, connector, profile}`. 그 profile 은 관리 표식이 있고 소유 기록에 그 커넥터가 있어야 한다. profile `.env` 에서 manifest 의 `fields[].env` 값만 `_env_value` 로 읽어 칸 키로 바꿔 쓴다. 빈 값의 선택 칸은 넣지 않는다. 필수 칸이 비면 400
- 셋 다 토큰 요청만 받는다. 값과 경로를 로그와 응답에 싣지 않는다. 경로 표는 모듈 docstring 의 경로 표에 한 줄씩 더한다
- `POST /api/connectors/<id>/call` 은 본문의 `values` 대신 `vault` 를 받을 수 있다. 둘 중 정확히 하나다. `vault` 면 그 파일의 `connector` 가 `<id>` 와 같아야 하고 그 값으로 지금과 같이 부른다

### 2. 바인딩 설치와 떼기

`PUT /api/connectors` 본문에 선택 칸 `bind: {"vault": "<vault>"}` 를 더한다. 칸이 없으면 지금 동작 그대로다.

- 받는 profile: `MANAGED_MARKER` 나 새 상수 `CONNECTOR_HOST_MARKER = ".fos-connector-host"` 가 있는 profile. 커넥터 표식은 운영자가 사람이 만든 profile 에 두는 파일이고 plugin 은 쓰지 않는다
- 표식 판정은 요청의 칸이 아니라 대상의 방식으로 한다. `GET /api/connectors` 는 두 표식 가운데 하나를 받는다. `PUT` 의 설치는 `bind` 칸이 있으면 두 표식 가운데 하나, 없으면 `MANAGED_MARKER` 만 받는다. `PUT` 의 떼기(`enabled: false`)는 소유 기록의 그 항목이 `bind` 면 두 표식 가운데 하나, 아니면 `MANAGED_MARKER` 만 받는다
- 바인딩 설치는 `platform_toolsets.api_server` 목록이 있고 그 안에 `CONTROL_PLANE_MCP` 가 있는 profile 만 받는다. 아니면 409 다. 목록이 없는 profile 에 서버 이름 하나만 든 목록을 만들면 내장 도구와 Control Plane MCP 가 모두 닫히고, MCP 이름이 하나도 없던 목록에 이름을 더하면 운영자의 다른 MCP 서버가 막히기 때문이다(`_connector_allowlist` 의 `no_mcp` 설명)
- 소유 기록 항목에 `mode`(`"bind"`), `vault`, `skills`(설치한 스킬 디렉터리 이름 목록)를 더한다. `_connector_state` 가 이 칸을 받게 고친다. `mode` 가 없으면 `"isolated"` 로 읽는다
- 소유 기록에 `isolated` 항목이 있는 profile 에 바인딩을 보내거나, `bind` 항목이 있는 profile 에 옛 설치를 보내면 `FileExistsError`(409)
- 설치(`enabled: true`, `bind` 있음)는 한 묶음으로 쓴다. 실패하면 지금처럼 이 요청이 쓴 파일만 되돌린다
  - 보관 파일을 읽는다. 없거나 `connector` 가 다르면 400
  - `.env`: manifest 의 `fields[].env` 마다 보관 값을 쓰고, 보관 파일에 없는 선택 칸의 key 는 지운다. 그 key 가 이미 `.env` 에 있는데 이 커넥터의 소유 기록이 없으면 `FileExistsError`. `BASE_ENV_KEYS` 와 겹치는 이름도 같다. 다른 바인딩 커넥터가 같은 env 이름을 쓰면 `FileExistsError`
  - `mcp_servers[<서버>]` 에 `_connector_server(manifest)` 를 둔다. 이름 충돌 규칙은 지금과 같다
  - `platform_toolsets.api_server` 에 서버 이름을 더한다. 이미 있는 이름은 그대로 두고 지우지 않는다. `no_mcp` 가 있으면 뺀다. manifest 의 `toolsets` 는 더하지 않는다
  - `CONTROL_PLANE_MCP` 등록을 지우지 않는다
  - 스킬: manifest 를 읽을 때 plugin 의 스킬 디렉터리 목록을 함께 얻는다(지금 `_connector_persona` 가 읽는 디렉터리와 같다). 각 디렉터리의 `SKILL.md` 와 `references/`, `templates/` 아래 텍스트 파일을 `<profile>/skills/<스킬 이름>/` 로 복사한다. 올린 스킬과 같은 제한(파일 20개, 파일마다 10만 자)을 넘는 스킬을 가진 커넥터는 카탈로그에 내지 않는다. 그 디렉터리가 이미 있고 이 커넥터의 소유 기록의 `skills` 에 없으면 `FileExistsError`. 심볼릭 링크 규칙은 지금 persona 와 같다
  - `SOUL.md` 는 읽지도 쓰지도 않는다
  - 이름 대응 파일은 `{"v": 1, "isolated": false, "servers": {...}}` 로 쓴다. `servers` 에는 소유 기록의 모든 서버를 싣는다. 운영 목록에서 빠졌거나 manifest 를 읽지 못한 커넥터의 서버는 `connector` 와 `prefix` 를 두고 `tools` 를 빈 객체로 싣는다. Control Plane 은 그 서버의 도구를 선언 없는 도구로 막는다. 옛 설치의 대응 파일은 지금처럼 `isolated` 칸 없이 쓴다. `fos-ctx` 가 이 칸을 읽는 일은 phase 02 다. 그 전의 `fos-ctx` 는 모르는 칸을 무시하고 옛 방식으로 판정하므로 더 막을 뿐 덜 막지 않는다
  - `fos-ctx` 파일을 묶음에 든 버전으로 맞추는 것은 지금과 같다
  - 답의 `restart_required` 는 `changed` 와 같다. `plugin_updated` 는 지금과 같다
- 떼기(`enabled: false`)에서 그 항목의 `mode` 가 `bind` 이면: 그 서버 정의와 `api_server` 의 그 이름, 그 커넥터의 `fields[].env` key, 소유 기록의 `skills` 디렉터리를 지운다. 다른 이름은 그대로다. 남은 커넥터가 없으면 대응 파일을 지운다. 답의 `restart_required` 는 거짓이다. 스킬 파일은 지워지지만 gateway 의 스킬 색인은 재시작 전까지 그 이름을 남긴다(`docs/hermes/tools-and-skills.md` 의 「변경이 적용되는 시점」). 모델이 그 스킬을 읽으려 하면 파일이 없어 실패할 뿐이다
- `GET /api/connectors?profile=`: 커넥터마다 `mode` 를 더한다. 바인딩 항목의 `configured` 는 서버 정의가 소유 기록과 같고, 서버 이름이 `api_server` 에 있고, 소유 기록의 스킬 파일이 plugin 의 본문과 같을 때 참이다. Control Plane MCP 등록이 있어도 된다. 옛 항목의 판정은 지금과 같다
- `_policy_hook_active` 는 두 방식 모두에 그대로 쓴다. 대응 파일 견주기는 `isolated` 칸을 포함한 바이트로 한다
- `_check_connector_probe`, `_connector_execute_request` 는 관리 표식 대신 「관리 표식이나 커넥터 표식」 을 받는다. 커넥터 표식만 있는 profile 에서는 소유 기록의 그 항목이 `bind` 여야 한다
- `_check_env_update`, `_check_env_delete` 는 지금처럼 `MANAGED_MARKER` 만 받는다. 바인딩의 env 는 설치 묶음이 쓰고 지우므로 Control Plane 이 이 경로를 바인딩에 쓰지 않는다

### 3. 도구 목록 보존

`_check_config_update` 에서 그 profile 의 소유 기록에 `bind` 항목이 있으면, 요청의 `api_server` 목록에 그 항목들의 서버 이름이 모두 있어야 한다. 하나라도 빠지면 409 와 「연결된 커넥터의 도구 이름이 빠졌다」 로 거절한다.
그 서버 이름은 지금처럼 `mcp_names` 에 있어 아는 이름으로 통과한다. 옛 설치 profile 과 소유 기록이 없는 profile 은 지금과 같다.
함수 docstring 에 Control Plane 이 붙은 커넥터 서버 이름을 함께 보낸다는 계약을 적는다.

### 4. 카탈로그

`GET /api/connectors/catalog` 의 커넥터마다 `skills`(스킬 이름 목록, 이름 순)를 더한다. 본문은 싣지 않는다.

### 5. 시험

- `hermes/tests/test_dashboard_profile_api.py`
  - 바인딩 설치: Control Plane MCP 와 `terminal` 이 있는 profile 에 커넥터 둘을 차례로 붙이면 `api_server` 에 두 서버 이름이 더해지고 나머지가 그대로이며 `SOUL.md` 가 바뀌지 않고 스킬 파일이 생긴다. 답의 `restart_required` 가 참이다
  - 떼기: 하나를 떼면 그 이름과 env 와 스킬만 빠지고 다른 바인딩이 남는다. `restart_required` 가 거짓이다
  - 실패: 소유 기록 없는 `.env` key 와 겹치는 env, 이미 있는 스킬 디렉터리, 옛 설치 profile 에 바인딩, 표식이 없는 profile 이 각각 409 나 401 이고 파일이 하나도 바뀌지 않는다
  - `PUT /api/config` 가 바인딩 서버 이름이 빠진 목록을 보내면 409 이고 설정이 바뀌지 않는다. 이름을 함께 보내면 지금처럼 쓴다
  - 바인딩 설치: `api_server` 목록이 없거나 `CONTROL_PLANE_MCP` 가 없는 profile 은 409
  - 대응 파일: 운영 목록에서 빠진 바인딩 커넥터가 있어도 그 서버가 빈 `tools` 로 실린다
  - 커넥터 표식만 있는 profile 에서 `GET`, 바인딩 설치, 떼기가 되고 옛 설치는 401
  - 보관 파일: 쓰고 지우고 옮기기. 형식 오류와 필수 칸 누락이 400 이고 응답에 값이 없다
  - 카탈로그의 `skills`
- `hermes/tests/test_connector_call.py`: `vault` 로 부르는 `call` 과 `values` 와 `vault` 를 함께 보낸 요청의 거절

## 검증

```bash
python3 -m unittest discover -s hermes/tests
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
```

모두 종료 코드 0 이어야 한다. `test/unit/connector-neutral.test.ts` 가 `hermes/plugins` 에 서비스 이름이 없음을 본다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `hermes/plugins/dashboard-profile-api/__init__.py` | 수정 |
| `hermes/tests/test_dashboard_profile_api.py` | 수정 |
| `hermes/tests/test_connector_call.py` | 수정 |
