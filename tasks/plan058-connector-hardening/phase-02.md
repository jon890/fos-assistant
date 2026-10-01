# Phase 02. 설치가 도구 목록을 커넥터 서버만으로 쓰고, 백업에 비밀 원문을 남기지 않고, 운영 비밀 칸을 거절한다

**Execution profile**: deep

## 목표

대시보드 plugin 쪽의 경계 셋을 고친다. 커넥터 profile 의 API 도구 목록에서 Control Plane MCP 를 빼고, 설정 백업에 profile `.env` 를 넣지 않고, manifest 의 `operator_secrets` 를 지원하지 않는 칸으로 거절한다.

**범위 외**: Control Plane 의 등록 순서와 Memory 주입은 phase 03 이 고친다. 이미 운영에 쌓인 백업의 정리는 배포 절차다.

## 컨텍스트

- 대상은 `hermes/plugins/dashboard-profile-api/__init__.py` 의 `_load_connector`, `_connector_config`, `_connector_request`(GET 분기), `_check_connector_probe` 다. 고치기 전에 지금 모양을 읽는다
- 지금 `_connector_config` 는 `CONTROL_PLANE_MCP not in allowed` 이면 설치를 거절하고, 설치하면 목록 끝에 서버 이름을 덧붙인다. 그래서 커넥터 profile 의 목록은 `["fos-assistant", <mcp_server>]` 다
- 지금 `_connector_config` 는 쓰기 전에 `config.yaml`, `.fos-connectors.json`, `.env` 를 `connector-backups/<시각>/` 에 떠 둔다. `.env` 에는 사용자의 비밀 원문이 있고 해제 뒤에도 남는다
- Hermes 는 API 도구 목록에 등록된 MCP 서버 이름이 하나도 없으면 등록된 MCP 서버를 모두 통과시킨다. 전부 막는 값은 `no_mcp` 다(`docs/hermes/tools-and-skills.md` 의 「API server 에서 MCP 도구를 여는 범위」)
- 소유 기록의 `allowlist_added` 는 `_connector_state` 가 필수로 읽는다. 옛 기록을 읽어야 하므로 키는 남긴다
- 한 배포 동안 옛 Control Plane 이 이 plugin 을 부른다. 옛 Control Plane 은 설치 전에 `PUT /api/config` 로 `["fos-assistant"]` 를 쓰고, 연결 확인에서 `["fos-assistant", <mcp_server>]` 를 쓸 수 있다. `PUT /api/config` 경로(`_check_config_update`)는 고치지 않는다

**근거 문서**: `docs/connectors.md` 의 「connector.json」 과 「대시보드 plugin 계약」 과 「저장과 비밀값」, `docs/adr/ADR-044-커넥터-에이전트는-자기-mcp-서버만-받고-memory-와-control-plane-도구를-받지-않는다.md`, `docs/adr/ADR-045-운영-비밀은-operator-env-와-다른-칸으로-선언하고-자식-mcp-프로세스에만-넣는다.md`

## 의도 메모

- `PUT /api/config` 에 커넥터 profile 예외를 두지 않는다. 일반 에이전트의 검사가 약해진다. 설치가 서버 등록과 목록을 한 번에 쓴다
- 백업을 통째로 없애지 않는다. `config.yaml` 과 소유 기록의 백업은 운영자가 손으로 되돌릴 때 쓴다. `.env` 만 뺀다. 같은 요청 안의 되돌리기는 메모리의 `originals` 로 하므로 `.env` 백업이 없어도 된다
- `operator_secrets` 를 조용히 무시하지 않는다. 무시하면 비밀이 필요한 커넥터가 확인은 실패하고 까닭이 안 보인다

## 작업 항목

### 1. `_load_connector` 가 `operator_secrets` 를 거절한다

- `declared.get("operator_secrets", [])` 를 읽는다. 목록이 아니거나 문자열이 아닌 항목이 있으면 `ValueError("operator_secrets 는 env 이름 목록이다")`
- 비어 있지 않으면 `ValueError("operator_secrets 는 아직 지원하지 않는다")`. `_connector_manifest` 가 이 문구를 경고 로그에 그대로 남기고 그 커넥터를 카탈로그에서 뺀다
- 빈 목록과 키 없음은 통과한다

### 2. `_connector_config` 가 API 도구 목록을 커넥터 서버만으로 쓴다

- `CONTROL_PLANE_MCP not in allowed or "memory" in allowed or "no_mcp" in allowed` 검사를 지운다
- 설치(`enabled` 참): 소유 기록을 갱신한 뒤 목록을 `[entry["mcp_server"] for entry in state.values()]` 로 통째로 바꾼다. 옛 기록에 `mcp_server` 가 없는 항목은 설정에서 같은 서버 정의를 찾은 이름을 쓴다(지금 운영 목록에서 빠진 커넥터를 찾는 방식과 같다). 새 기록의 `allowlist_added` 는 `True` 로 쓴다
- 제거: 소유 기록에서 뺀 뒤 남은 기록의 서버 이름으로 목록을 쓴다. 남은 기록이 없으면 `["no_mcp"]` 다
- 서버 정의와 소유 기록이 같아도 목록이 다르면 바뀐 것이다. 지금의 `originals` 와 `values` 비교가 그대로 판정하고, 이때 응답은 `changed: true`, `restart_required: bool(owned)` 다
- 목록을 헬퍼 `_connector_allowlist(state: dict, servers: dict) -> list` 로 계산해 설치, 제거, 아래 `configured` 판정이 같은 함수를 쓴다

### 3. `_connector_config` 의 백업에서 `.env` 를 뺀다

- 백업 반복문이 `env_path` 를 건너뛴다
- 백업을 쓴 뒤 `profile_dir / "connector-backups"` 아래의 `*/.env` 를 모두 지운다. 이전 판이 남긴 사본을 다음 쓰기에서 지우기 위해서다. 지우지 못하면 `logger.warning` 한 줄을 남기고 설치는 계속한다. 경로를 로그에 싣지 않는다

### 4. `configured` 와 probe 가 새 목록을 본다

- `_connector_request` 의 GET 분기: `configured` 는 지금 조건에 더해 `config["platform_toolsets"]["api_server"]` 가 `_connector_allowlist(state, servers)` 와 같을 때만 참이다
- `_check_connector_probe`: 지금의 `server not in ... api_server` 검사는 그대로 둔다

### 5. 모듈 docstring 과 주석

- 파일 머리의 「만든 자리에서 설정 틀을 쓴다」 는 고치지 않는다. 커넥터 설치가 목록을 다시 쓴다는 한 줄을 `_connector_config` 의 docstring 에 적는다

### 6. 이 phase 를 검증하는 `hermes/tests/test_dashboard_profile_api.py`, `hermes/tests/test_connector_manifest.py`

`test_dashboard_profile_api.py`:

- `test_connector_installs_idempotently_and_preserves_profile_secrets` 를 고친다. 설치 뒤 `platform_toolsets.api_server == ["demo"]` 이고 `mcp_servers` 에는 `fos-assistant` 가 그대로 등록돼 있음을 본다. 백업 디렉터리에 `.env` 파일이 없음을 본다
- 새 검사 `test_connector_uninstall_leaves_no_secret_on_disk`: 칸 env 에 비밀 `demo_secret_value_0123456789` 를 `PUT /api/env` 로 쓰고 설치한 뒤, `DELETE /api/env` 와 `PUT /api/connectors` `enabled: false` 로 해제한다. `self.root / "alice"` 아래 모든 파일을 `rglob("*")` 로 읽어 그 원문이 어느 파일에도 없음을 본다. 목록은 `["no_mcp"]` 다
- 새 검사 `test_connector_write_removes_env_copies_left_by_older_version`: `connector-backups/1/.env` 를 비밀 원문으로 미리 만들어 두고 설치하면 그 파일이 없어짐을 본다
- 새 검사 `test_connector_install_rewrites_old_allowlist`: 목록이 `["fos-assistant", "demo"]` 이고 소유 기록이 있는 profile 에서 `GET /api/connectors` 가 `configured: false` 를, `PUT enabled: true` 가 `changed: true, restart_required: true` 를 돌려주고 목록이 `["demo"]` 가 됨을 본다
- 새 검사 `test_connector_install_works_without_control_plane_mcp_in_allowlist`: 목록이 `["delegation"]` 인 관리 profile 에 설치가 성공하고 목록이 `["demo"]` 가 됨을 본다
- 기존 검사 가운데 목록에 `fos-assistant` 를 기대하는 커넥터 검사(348, 379, 396 줄 부근의 `mcp_servers` 단언은 서버 등록이라 그대로다)와 `allowlist_added` 기대값을 새 동작에 맞춘다. 목록 개수나 모양을 상수로 단언하는 검사를 `grep -n "api_server" hermes/tests/test_dashboard_profile_api.py` 로 찾아 커넥터 설치 뒤의 것만 고친다

`test_connector_manifest.py`:

- 새 검사 `test_operator_secrets_are_rejected_as_unsupported`: `connector.json` 에 `"operator_secrets": ["DEMO_SERVICE_KEY"]` 를 넣으면 카탈로그에 그 커넥터가 없고 경고 로그에 `operator_secrets 는 아직 지원하지 않는다` 가 있음을 본다. `"operator_secrets": []` 는 카탈로그에 나온다. `"operator_secrets": "X"` 는 빠진다

## 검증

```bash
# cwd: 저장소 root
python3 -m unittest discover -s hermes/tests
grep -n "operator_secrets" hermes/plugins/dashboard-profile-api/__init__.py
! grep -n "Control Plane MCP 를 허용한 API 도구 목록이 필요하다" hermes/plugins/dashboard-profile-api/__init__.py
scripts/check-public-safe.sh
```

모두 종료 코드 0 이어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/dashboard-profile-api/__init__.py` | 수정 |
| `hermes/tests/test_dashboard_profile_api.py` | 수정 |
| `hermes/tests/test_connector_manifest.py` | 수정 |
