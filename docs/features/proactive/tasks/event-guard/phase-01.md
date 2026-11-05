# Phase 01. fos-ctx 의 도구 판정 hook 과 설치 상태의 `tool_guard`

**Execution profile**: standard

## 목표

fos-ctx 의 `pre_tool_call` 이 안전 목록 밖의 도구 호출을 Control Plane 의 `tool-guard` 경로에 묻고, 대시보드 설치 상태가 판정 plugin 이 있는지(`tool_guard`) 알린다.

**범위 외**: Control Plane 의 판정(phase 2), 깨우기와 켜기의 확인(phase 3).

## 컨텍스트

**요구사항**: REQ9

**근거 문서**: `docs/features/proactive/spec.md`, `docs/features/proactive/design.md` 의 「사건 실행의 도구 판정」, `docs/adr/ADR-20261105-event-watch.md`

- 질의 모양은 `hermes/plugins/fos-ctx/connector_policy.py` 의 `connector_policy` 와 `_post_json` 을 따른다. 서명 함수는 `context.py` 에 둔다
- 판정 순서는 `hooks.py` 의 `pre_tool_call` 이다. `skill_manage`, 이미지 위임, 첨부 원본, 플러그인 도구, Control Plane MCP 도구를 먼저 가르고 남은 것만 묻는다

## 의도 메모

- 막을 이름 목록은 기각했다. 안전 목록을 두어 새 Hermes 도구가 판정 없이 열리지 않게 한다
- 옛 Control Plane 의 404 만 통과시킨다(rule-66). 그 밖의 실패와 예외는 막는다
- session 이 없는 호출은 `execute_code` 안의 호출이라 통과시킨다

## 작업 항목

### 1. `hermes/plugins/fos-ctx/tool_guard.py`

`SAFE_TOOLS`, `GUARD_VERSION = "v1-tool-guard"`, `guard_url()`(`FOS_CTX_POLICY_URL` 의 마지막 조각을 `tool-guard` 로), `tool_guard(tool_name, session_id, tool_call_id)` 를 둔다. 답이 200 의 `allow` 면 None, 404 면 None, 나머지는 글이 든 `block` 이다.

### 2. `hooks.py`, `context.py`, `__init__.py`, `plugin.yaml`, `README.md`

`pre_tool_call` 의 끝에서 플러그인·Control Plane 도구가 아닌 호출을 `tool_guard` 로 보낸다. 예외는 잡아 막는다. 버전을 1.5.0 으로 올린다.

### 3. 대시보드 설치 상태의 `tool_guard`

`hermes/plugins/dashboard-profile-api/connector_install.py` 의 상태 응답에 `tool_guard` 를 `policy_hook` 과 같은 값으로 더한다.

### 4. 시험

`hermes/tests/test_fos_ctx.py` 에 가짜 HTTP 서버로 묻기와 막기를, `hermes/tests/test_tool_guard_contract.py` 에 Hermes 소스와 도구 목록 대조를 둔다.

## 검증

```bash
python3 -I -m unittest discover -s hermes/tests -p 'test_fos_ctx.py'
python3 -I -m unittest discover -s hermes/tests -p 'test_tool_guard_contract.py'
python3 -I -m unittest discover -s hermes/tests -p 'test_hermes_contract.py'
python3 -I -m unittest discover -s hermes/tests -p 'test_connector_guard_*.py'
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/fos-ctx/tool_guard.py` | 신규 |
| `hermes/plugins/fos-ctx/hooks.py` | 수정 |
| `hermes/plugins/fos-ctx/context.py` | 수정 |
| `hermes/plugins/fos-ctx/__init__.py` | 수정 |
| `hermes/plugins/fos-ctx/plugin.yaml` | 수정 |
| `hermes/plugins/fos-ctx/README.md` | 수정 |
| `hermes/plugins/dashboard-profile-api/connector_install.py` | 수정 |
| `hermes/tests/hermes_contract.py` | 수정 |
| `hermes/tests/test_fos_ctx.py` | 수정 |
| `hermes/tests/test_tool_guard_contract.py` | 신규 |
