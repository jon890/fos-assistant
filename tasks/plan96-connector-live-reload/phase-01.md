# Phase 01. 대시보드 plugin 이 재시작 없이 반영되는 설치를 `reload_pending` 으로 알린다

**Execution profile**: deep

## 목표

바인딩 설치(`PUT /api/connectors` 의 `bind`)가 새 MCP 서버를 더하거나 스킬만 바꾸면 `restart_required: false`, `reload_pending: true` 로 답한다.
스킬을 더하거나 뺀 설치는 `skills.disabled` 의 색인 표식을 바꿔 공유 gateway 의 스킬 색인 캐시를 그 profile 만 새로 만들게 한다.
이미 있던 서버의 정의나 그 서버의 `.env` 값이 바뀐 설치는 지금처럼 `restart_required: true` 다.

**범위 외**: backend 가 `reload_pending` 을 읽고 예약 확인을 도는 것은 phase 02 다. 화면과 e2e 대역은 phase 03 이다.
옛 설치(`mode: isolated`)는 바꾸지 않는다. `reload-plugins` 제어 소켓 동사는 쓰지 않는다.

## 컨텍스트

- 설치 함수는 `hermes/plugins/dashboard-profile-api/__init__.py` 의 `_connector_bind_config(profile_dir, plugin, enabled, vault=None, values=None, owner_attachments=None)` 다. 붙이기(`enabled` 참)와 떼기(거짓)를 함께 맡고 `{"changed", "restart_required", "plugin_updated"}` 를 돌려준다. `PUT /api/connectors` 처리기가 그 dict 를 응답에 펼친다
- 붙이기 분기에서 `name = manifest["mcp_server"]` 이고, 쓰기 전 설정은 `saved`, 서버 목록 사본은 `servers = dict(saved.get("mcp_servers") or {})` 다. `.env` 의 쓰기 전 줄은 `env_lines`, 이 커넥터의 칸 env 이름은 `field_env` 다. 스킬은 `desired`(쓸 파일)와 `stale`(지울 파일)로 모인다
- 공유 gateway 는 60초마다 profile 마다 `mcp_servers` 의 이름과 살아 있는 연결을 맞춘다. 새 이름은 연결하고 빠진 이름은 끊는다. 이름만 비교하므로 같은 이름의 정의나 env 값이 바뀐 것은 다시 연결하지 않는다
- 공유 gateway 의 스킬 색인 캐시 키에는 스킬 디렉터리 내용이 없고 `skills.disabled` 가 들어 있다. 없는 스킬 이름 하나를 넣으면 그 profile 의 다음 실행이 색인을 새로 만든다
- `fos-ctx` 는 이름 대응 파일을 호출마다 읽는다(`hermes/plugins/fos-ctx/__init__.py` 의 `read_tool_map`). 대응 파일만 바뀐 설치는 기다릴 것이 없다
- 시험은 `hermes/tests/test_dashboard_profile_api.py` 의 `ProfileApiRouteTest` 가 갖는다. `bind_fixture()`, `bind(plugin, vault)`, `alice_config()`, `status_of()` 보조가 있다. `test_binding_two_connectors_adds_their_names_and_keeps_the_profile` 가 지금 `restart_required` 가 참이라고 단언한다
- Hermes 내부 지점 계약은 `hermes/tests/hermes_contract.py` 와 `hermes/tests/test_hermes_contract.py` 가 갖는다. 소스 확인 시험은 `HERMES_SOURCE` 가 있을 때만 돌고 CI 가 운영 판의 소스로 돌린다. `test_state_flag_is_read` 가 `ast` 로 소스의 문자열 상수를 찾는 본보기다

**근거 문서**: `docs/adr/ADR-20261007-connector-live-reload.md`, `docs/backend/connector-install.md` 의 「바인딩 설치」 표

## 의도 메모

- `reload-plugins` 로 `fos-ctx` 를 다시 읽히지 않는다. 다시 읽는 동안 hook 목록이 비어 그 profile 의 커넥터 호출이 판정 없이 나갈 수 있다. `plugin_updated` 는 지금 그대로 둔다
- 값 교체를 `enabled: false` 로 한 주기 끄고 되돌리는 방식은 이번에 하지 않는다
- 표식은 `time.time_ns()` 로 매번 새 값을 쓴다. 같은 스킬 상태로 돌아왔을 때 옛 캐시 항목을 다시 쓰지 않게 하려는 것이다
- 운영자가 `skills.disabled` 에 넣은 다른 이름은 건드리지 않는다

## 작업 항목

### 1. `hermes/plugins/dashboard-profile-api/__init__.py`

- 모듈 상수 `SKILL_INDEX_MARKER_PREFIX = "fos-skill-index-"` 를 둔다. 실제 스킬 이름과 겹치지 않게 앞머리로만 판정한다. `git grep -n "fos-skill-index"` 로 겹치는 값이 없는지 확인한다
- `_connector_bind_config` 의 붙이기 분기:
  - 쓰기 전 서버 정의 `previous = servers.get(name)` 를 바꾸기 전에 잡는다
  - `.env` 에서 `field_env` 에 든 이름의 쓰기 전 줄과 쓴 뒤 줄을 견줘 `env_changed` 를 구한다
  - `restart = previous is not None and (previous != server or env_changed)`
- 떼기 분기의 `restart` 는 거짓이다
- 스킬 파일이 바뀌면(`desired` 가운데 원래 바이트와 다른 것이 있거나 `stale` 이 비어 있지 않으면) `updated` 의 `skills.disabled` 에서 `SKILL_INDEX_MARKER_PREFIX` 로 시작하는 항목을 빼고 새 표식 하나를 더한다. `skills` 나 `disabled` 가 없으면 만든다
  - `disabled` 가 문자열이면 Hermes 가 읽는 것처럼(`agent/skill_utils.py` 의 `parse_config_string_list`) 목록으로 바꿔 쓴다. `[` 로 시작하면 `ast.literal_eval` 로 목록 리터럴을 읽고, 아니면 그 문자열 하나를 이름 하나로 둔다. 쉼표로 나누지 않는다
  - 그 밖의 형태(사전, 숫자 등)면 붙이기는 `FileExistsError` 로 거절한다. 처리기가 409 로 답하고 backend 는 `CONNECTOR_BIND_CONFLICT` 로 끝낸다. 떼기는 표식을 쓰지 않고 그대로 뗀다. 운영자가 설정을 바꿔도 떼야 `.env` 에 비밀이 남지 않는다는 원칙(같은 함수 docstring)을 지킨다
  - 이 쓰기는 지금의 `config_path` 대상에 들어가 같은 묶음으로 되돌려진다
- 돌려주는 dict 에 `reload_pending` 을 더한다. 바뀐 것이 있고 `restart` 와 `plugin_updated` 가 모두 거짓이면 참이다. 바뀐 것이 없으면 모든 칸이 거짓이다. 붙이기의 `restart_required` 는 지금의 `enabled` 대신 `restart` 다. `restart_required` 를 `plugin_updated` 와 OR 하지 않는다. `plugin_updated` 가 참이면 `reload_pending` 은 거짓이고, backend 가 그것만으로 재시작 대기로 둔다
- 함수 docstring 의 「떠 있는 profile 에 더한 서버는 gateway 를 다시 띄워야 보이므로…」 문단을 새 판정으로 고친다

### 2. `hermes/tests/test_dashboard_profile_api.py`

- `test_profile_with_only_the_connector_marker_takes_bindings_only` 의 처음 붙이기 단언(지금 `restart_required` 와 `plugin_updated` 가 참)을 `restart_required` 거짓, `plugin_updated` 참, `reload_pending` 거짓으로 바꾼다
- `test_binding_two_connectors_adds_their_names_and_keeps_the_profile` 의 첫 붙이기 단언을 `restart_required` 거짓, `reload_pending` 참으로 바꾼다. 그 시험의 `skills.disabled` 에 표식 하나만 있는지 본다
- 새 시험(이름은 영문 동사로 시작):
  - 같은 커넥터를 다른 보관 값으로 다시 붙이면 `restart_required` 참, `reload_pending` 거짓
  - 같은 값으로 다시 붙이면 모든 칸이 거짓이고 `config.yaml` 이 그대로다
  - 떼면 `restart_required` 거짓, `reload_pending` 참이고, 표식이 새 값으로 바뀌며 운영자가 넣어 둔 다른 `skills.disabled` 이름은 남는다
  - `skills.disabled` 가 사전이면 붙이기가 409 로 거절되고 파일이 바뀌지 않는다. 같은 상태에서 떼기는 200 이고 `.env` 의 칸 값이 지워진다
  - `skills.disabled` 가 목록 리터럴 문자열(`"['a', 'b']"`)이면 그 이름들과 표식이 목록으로 남는다
  - `skills.disabled` 가 이름 하나 문자열(`"a,b"`)이면 그 문자열 하나와 표식이 목록으로 남는다

### 3. `hermes/tests/hermes_contract.py` 와 `hermes/tests/test_hermes_contract.py`

- `hermes_contract.py` 에 `LIVE_RELOAD` 선언을 둔다. 확인할 소스 지점은 셋이다
  - `gateway/run.py` 에 문자열 상수 `"MCP config reconcile"` 이 있다
  - `gateway/run_profile_reconcile.py` 에 함수 `_mcp_config_reconciler` 가 있고 그 안에서 `reconcile_mcp_servers_with_config` 를 부른다
  - `agent/prompt_builder.py` 의 `_build_skills_system_prompt_inner` 가 `get_disabled_skill_names` 를 부르고, 그 결과를 담은 이름 `disabled` 가 `cache_key` 할당의 값 안에 나온다
- `test_hermes_contract.py` 의 `HermesSourceTest` 에 시험 하나를 더한다. 실패 메시지는 「Hermes 를 올리면 재시작 없는 반영이 깨질 수 있다. ADR-20261007 connector-live-reload 를 다시 본다」 는 뜻을 담는다

### 4. 문서

- `hermes/README.md`: `PUT /api/connectors` 행의 응답에 `reload_pending` 을 더한다. 「설치의 `restart_required`」, 「떼기의 `restart_required`」 행을 새 판정으로 고친다
- `docs/backend/connector-install.md`: 「바인딩 설치」 표의 「답의 `restart_required`」 행을 새 판정과 `reload_pending` 으로 고치고, 스킬 행에 표식을 적는다. 「떼어도 gateway 의 스킬 색인은 재시작 전까지 그 스킬 이름을 남긴다」 줄을 지운다
- `docs/hermes/tools-and-skills.md`: 「profile 생성과 Control Plane MCP」 의 `/reload-mcp` 문단에 MCP 설정 맞추기 주기와 스킬 색인 표식을 더하고 ADR 을 가리킨다
- `docs/hermes/mcp-profile-credentials.md`: 「설정 저장과 gateway 연결 확인」 에 새 이름은 맞추기 주기에 연결되고 같은 이름의 값 교체는 그렇지 않다는 것을 적는다
- `docs/hermes/upgrades.md`: 「대시보드 plugin 이 기대는 내부 지점」 절에 「MCP 설정 맞추기와 스킬 색인 캐시 키」 를 더하고 계약 시험을 가리킨다

## 검증

```bash
# cwd: 저장소 root
python3 -m unittest discover -s hermes/tests
scripts/check-hermes-contract.sh v2026.9.24
scripts/check-public-safe.sh
```

- 모두 종료 코드 0. `check-hermes-contract.sh` 는 공개 상류 저장소의 그 태그를 받아 `HermesSourceTest` 를 돌린다

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/dashboard-profile-api/__init__.py` | 수정 |
| `hermes/tests/test_dashboard_profile_api.py` | 수정 |
| `hermes/tests/hermes_contract.py` | 수정 |
| `hermes/tests/test_hermes_contract.py` | 수정 |
| `hermes/README.md` | 수정 |
| `docs/backend/connector-install.md` | 수정 |
| `docs/hermes/tools-and-skills.md` | 수정 |
| `docs/hermes/mcp-profile-credentials.md` | 수정 |
| `docs/hermes/upgrades.md` | 수정 |
