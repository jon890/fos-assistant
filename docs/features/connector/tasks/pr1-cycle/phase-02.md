# Phase 02. 대시보드 plugin 이 그 서버만 껐다 켠다

**Execution profile**: standard

## 목표

바인딩 설치가 `bind.cycle: "off"` 를 받아 설정의 그 서버 항목만 끄고, 최소 시간이 지난 보통 설치가 다시 켜게 한다. 재시작이 필요한 까닭이 정의나 칸 값 변경, 뗀 이름 다시 붙이기뿐이면 `cycle_available` 을 답한다.

**범위 외**: Control Plane(phase 03, 04). gateway 기동 시각 조회(PR2).

## 컨텍스트

**요구사항**: REQ1, REQ2

**근거 문서**: `docs/features/connector/design.md` 의 「대시보드 plugin: 바인딩 설치의 껐다 켜기(PR1)」, `docs/adr/ADR-20261120-connector-auto-apply.md`

- 설치 본체는 `connector_binding.py` 의 `_connector_bind_config(profile_dir, plugin, enabled, vault, values, owner_attachments, owner_output, owner_browser, guard, revision)` 다. `restart` 는 `previous`(설정의 지금 항목)와 새 `server` 가 다르거나 칸 값이 바뀌었거나 이름이 뗀 기록에 있으면 참이다. `plugin_updated` 는 `fos-ctx` 파일이 바뀌면 참이다.
- 설정 항목이 소유 기록과 다르면 `FileExistsError("운영자가 등록하거나 바꾼 MCP 서버가 있다")` 로 409 다(`_recoverable_server` 예외만 통과).
- 소유 기록의 서버 정의는 `enabled is True` 여야 읽힌다(`connector_state.py` 의 `_connector_state`, `connector_remote.py` 의 `_remote_record_ok`). **소유 기록에는 `enabled: false` 를 쓰지 않는다.** 쓰면 그 profile 의 모든 바인딩이 멈춘다.
- 요청 검사는 `connector_install.py` 의 `_connector_request` 에 있다. 지금 `bind` 는 `vault` 와 선택 `guard` 만 받는다.
- 기존 테스트 도구는 `hermes/tests/dashboard_profile_api_support.py` 의 `ProfileApiRouteTest`, `bind_fixture`, `bind`, `alice_config`, `status_of` 다. 정의를 바꿔 `restart_required` 를 만드는 방법은 `dashboard_profile_api_support.py` 의 `declare_owner_output(connector)` 다(쓰는 본보기는 `test_dashboard_profile_api_connector_binding_refresh.py`).
- 떼기 경로에도 `if name in servers and servers[name] != owned["server"]: raise FileExistsError("운영자가 바꾼 MCP 서버가 있다")` 가 있다(지금 289줄). 꺼진 항목이면 여기서 409 가 나므로 함께 고친다.

## 의도 메모

- 끄기 기록을 소유 기록 칸으로 두지 않는 까닭은 위 두 번째 줄이다. 따로 둔 파일은 이전 이미지가 읽지 않는다.
- 최소 시간 120초는 gateway 맞추기 주기(60초) 둘이다. 테스트는 시계를 `mock.patch` 로 바꾼다.

## 작업 항목

### 1. `hermes/plugins/dashboard-profile-api/connector_cycle.py` 신규

- `CONNECTOR_CYCLE = ".fos-connector-cycle.json"`, `CYCLE_MIN_SECONDS = 120`
- `_cycle_marks(raw: bytes | None) -> dict[str, float]`: 없거나 모양이 틀리면 빈 dict(경고 로그에 예외 종류만)
- `_cycle_bytes(marks: dict) -> bytes | None`: 비면 None(파일을 지운다)
- `_server_off(current, recorded, marks, plugin) -> bool`: `current == {**recorded, "enabled": False}` 이고 `plugin in marks`
- `_cycle_elapsed(marks, plugin, now) -> bool`: `now - marks[plugin] >= CYCLE_MIN_SECONDS`

### 2. `connector_binding.py` 의 `_connector_bind_config` 에 `cycle_off: bool = False` 를 더한다

- 끄기 기록 파일을 `originals` 와 심볼릭 링크 검사, 원자적 쓰기 묶음(`targets`)에 넣는다.
- 붙이기에서 `off = owned is not None and name in servers and _server_off(servers[name], owned["server"], marks, plugin)` 를 구한다. `off` 면 「운영자가 바꾼 서버」 검사를 통과시키고 `previous` 는 소유 기록의 서버로 본다.
- 소유 기록 `state[plugin]["server"]` 는 늘 켜진 새 정의다.
- `cycle_off` 이거나 (`off` 이고 최소 시간 전)이면: 설정에는 `{**server, "enabled": False}` 를 쓰고, `off` 가 아니었으면 `marks[plugin] = time.time()` 을 적는다. 뗀 기록에서 그 이름을 지운다. 답은 `restart_required: False`, `reload_pending: False`, `cycle_off: True`, `cycle_available: False` 와 `plugin_updated`.
- `off` 이고 최소 시간이 지났고 `cycle_off` 가 아니면: 설정에 켜진 정의를 쓰고 `marks.pop(plugin)`. 이 설치의 `restart` 는 거짓이다. 답은 지금 규칙의 `reload_pending`(`plugin_updated` 면 거짓).
- 그 밖에는 지금 그대로이고 답에 `cycle_available: restart and not plugin_updated`, `cycle_off: False` 를 더한다.
- 떼기도 `_server_off(servers[name], owned["server"], marks, plugin)` 면 「운영자가 바꾼 서버」 검사를 통과시키고 그 항목을 지운다. 그리고 `marks.pop(plugin, None)`.
- 바뀐 것이 없어 일찍 돌려주는 두 자리도 `cycle_off`(꺼진 채면 참)와 `cycle_available: False` 를 함께 답한다.
- 이 파일은 400줄 상한이다. 판정은 `connector_cycle.py` 에 두고 여기에는 부르는 줄만 둔다.

### 3. `connector_install.py` 의 `_connector_request`

`bind` 키 검사를 `set(bind) - {"guard", "cycle"} == {"vault"}` 로 넓히고 `cycle` 이 있으면 값이 `"off"` 여야 한다(아니면 400). `_connector_bind_config(..., cycle_off=bind.get("cycle") == "off")` 로 넘긴다.

### 4. `hermes/plugins/dashboard-profile-api/README.md` 의 플러그인 절

`restart_required`, `reload_pending` 을 설명하는 문단 뒤에 `bind.cycle`, 끄기 기록, 최소 시간, `cycle_available`, `cycle_off` 의 뜻을 짧게 더한다. 근거는 ADR-20261120 / connector-auto-apply 링크로 둔다.

### 5. 테스트 도구와 `hermes/tests/test_dashboard_profile_api_connector_binding_cycle.py` 신규

`dashboard_profile_api_support.py` 의 `bind()` 에 `cycle=None` 인자를 더해, 값이 있으면 `body["bind"]["cycle"] = cycle` 로 넣는다. 시계는 `mock.patch.object(<connector_cycle 모듈>.time, "time", ...)` 처럼 `connector_cycle` 이 부르는 `time.time` 을 바꾼다. `_connector_bind_config` 가 끈 시각을 적을 때도 `connector_cycle` 의 시계 함수(`_cycle_now()`)를 부르게 해 패치 자리를 하나로 둔다.

- 정의를 바꾼 다시 설치가 `restart_required: True`, `cycle_available: True` 를 답한다
- `fos-ctx` 파일이 바뀌면 `cycle_available: False` 다
- 끄기: 설정 항목만 `enabled: False` 이고 소유 기록은 켜진 정의, 끄기 기록에 시각이 있다. 같은 profile 의 다른 바인딩(`other-notes`)의 소유 기록과 설정 항목, 다른 profile 파일이 바이트 그대로다. 그 뒤 `other-notes` 의 다시 설치와 `status_of` 가 성공한다(REQ2)
- 120초 전 보통 설치: 꺼진 채 새 정의를 쓰고 `cycle_off: True`
- 120초 뒤 보통 설치: 켜고 끄기 기록이 비며 `restart_required: False`, `reload_pending: True`
- 끄기 기록 없이 `enabled` 만 다르면 409
- 꺼진 동안 떼기가 200 이고, 설정의 서버 항목과 끄기 기록에서 그 플러그인이 지워진다
- `bind.cycle` 이 `"off"` 가 아니면 400

## 검증

```bash
python3 -m unittest discover -s hermes/tests -p 'test_dashboard_profile_api_connector_binding_cycle.py'
python3 -m unittest discover -s hermes/tests -p 'test_dashboard_profile_api_connector_binding*.py'
python3 -m unittest discover -s hermes/tests
node scripts/checks/check-file-length.mjs
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/dashboard-profile-api/connector_cycle.py` | 신규 |
| `hermes/plugins/dashboard-profile-api/connector_binding.py` | 수정 (껐다 켜기) |
| `hermes/plugins/dashboard-profile-api/connector_install.py` | 수정 (`bind.cycle` 받기) |
| `hermes/plugins/dashboard-profile-api/README.md` | 수정 (껐다 켜기 계약) |
| `hermes/tests/test_dashboard_profile_api_connector_binding_cycle.py` | 신규 |
| `hermes/tests/dashboard_profile_api_support.py` | 수정 (`bind` 의 `cycle` 인자) |
