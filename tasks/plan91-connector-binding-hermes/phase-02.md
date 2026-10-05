# Phase 02. fos-ctx 의 바인딩 profile 판정과 대시보드 plugin 계약 문서

**Execution profile**: standard

## 목표

`fos-ctx` 가 바인딩 profile(이름 대응 파일의 `isolated` 가 거짓)에서는 커넥터 서버의 도구만 Control Plane 에 묻고, 나머지 도구는 커넥터가 없는 profile 과 같게 둔다.
phase 01 이 연 대시보드 경로와 이 판정을 계약 문서에 적는다.

**범위 외**: Control Plane 이 이 경로를 부르는 일과 판정의 연결 고르기(`plan92-connector-bindings`).

## 컨텍스트

**근거 문서**: `docs/adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md`, `docs/backend/connector-tool-policy.md` 의 「이름 대응」, `docs/hermes/connector-policy.md`

- 결정: `docs/adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md` 의 「판정」
- `hermes/plugins/fos-ctx/__init__.py`
  - `read_tool_map(home=None)` 은 대응 파일의 `servers` 를 돌려주고, 파일이 없으면 None 이다. `v` 가 1 이 아니면 `ValueError`
  - `pre_tool_call(tool_name, args, session_id, tool_call_id, **_)` 은 대응 파일이 있으면 `execute_code` 를 `CODE_EXECUTION_MESSAGE` 로 막고, 대응의 어느 서버와도 맞지 않는 `mcp__` 도구를 `connector_policy` 의 `UNKNOWN_SERVER_MESSAGE` 로 막는다. 대응 파일을 읽지 못하면 `mcp__` 와 `execute_code` 를 모두 막는다
  - `connector_policy` 는 session 이나 `tool_call_id` 가 없으면 `CONTEXT_BLOCK_MESSAGE` 로 막는다. `execute_code` 안의 도구 호출은 hook 에 `task_id` 만 넘어온다(`docs/hermes/connector-policy.md` 의 「hook 이 받는 것」)
  - 모듈 docstring 의 「커넥터 정책」 절이 이 동작의 설명이다
- 대응 파일을 쓰는 쪽: phase 01 이 바인딩 설치에서 `{"v": 1, "isolated": false, "servers": {...}}` 를 쓴다
- 계약 문서
  - `docs/backend/connector-install.md` 의 「대시보드 plugin 계약」
  - `docs/backend/connector-tool-policy.md` 의 「이름 대응」 과 「도구 호출 판정」
  - `hermes/README.md` 의 경로 표와 「커넥터」
  - `docs/hermes/fos-ctx.md`
- 시험: `hermes/tests/test_fos_ctx.py`

## 의도 메모

- 바인딩 profile 에는 Control Plane MCP 와 운영자가 넣은 다른 MCP 서버가 있을 수 있다. 대응에 없는 `mcp__` 도구를 막으면 그 서버들이 모두 막힌다. 그래서 커넥터 서버 접두사에 맞는 도구만 판정한다
- `execute_code` 를 막지 않아도 그 안에서 부른 커넥터 도구는 session 이 없어 막힌다. 관리자가 켠 코드 실행을 커넥터 때문에 끄지 않는다
- 칸이 없으면 옛 설치로 읽는다. 옛 profile 의 동작은 한 글자도 바뀌지 않는다
- 대응에 없는 `mcp__` 도구를 통과시키는 것은 대응 파일에 그 profile 의 모든 커넥터 서버가 실려 있다는 phase 01 의 약속에 기댄다. 터미널이나 파일 도구를 가진 에이전트는 이 파일과 `fos-ctx` 설정을 고칠 수 있다. 이 위험은 ADR-083 의 「감당할 것」 에 있고 #190 이 다룬다
- 이 phase 의 문서는 Hermes 쪽 계약만 적는다. Control Plane 의 흐름과 API 는 `plan92-connector-bindings` 가 고친다

## 작업 항목

### 1. `hermes/plugins/fos-ctx/__init__.py`

- `read_tool_map` 이 `(servers, isolated)` 를 돌려준다. 파일이 없으면 `(None, True)`. `isolated` 칸이 없으면 참, 있으면 boolean 이어야 하고 아니면 `ValueError`
- `pre_tool_call`
  - 대응 파일을 읽지 못하면 지금처럼 막는다
  - `isolated` 가 참이면 지금 동작 그대로다
  - `isolated` 가 거짓이면: 대응의 서버와 맞는 `mcp__` 도구는 `connector_policy` 로 묻는다. 맞지 않는 Control Plane MCP 도구는 `_fos_ctx` 를 붙인다. 그 밖의 도구(`execute_code`, 다른 MCP 서버의 도구, 내장 도구)는 None 을 돌려준다
- 모듈 docstring 의 「커넥터 정책」 절에 두 방식을 적는다. 「연결용 profile」 이라는 말을 「옛 설치 profile」 과 「바인딩 profile」 로 나눈다

### 2. 계약 문서

- `docs/backend/connector-install.md` 의 「대시보드 plugin 계약」 에 보관 파일 세 경로, `call` 의 `vault`, `PUT /api/connectors` 의 `bind`, `GET` 의 `mode`, 바인딩 설치가 쓰고 지우는 것과 받는 profile 의 조건, 표식 판정, `PUT /api/config` 가 바인딩 서버 이름이 빠진 요청을 거절한다는 것을 적는다. 옛 설치 문장은 「옛 설치」 로 이름 붙여 남긴다. 같은 파일의 「설치와 실패 처리」 와 「커넥터 에이전트의 경계」 는 `plan92-connector-bindings` 가 고치므로 이 phase 에서 건드리지 않는다
- `docs/backend/connector-tool-policy.md` 의 「이름 대응」 에 `isolated` 칸과 두 방식의 판정 차이를 적는다
- `hermes/README.md` 의 경로 표에 보관 파일 경로 셋을 더하고 「커넥터」 에 보관 파일과 커넥터 표식을 적는다
- `docs/hermes/fos-ctx.md` 에 바인딩 profile 의 판정을 적는다
- 운영 값(디렉터리의 실제 경로, 컨테이너 이름)은 적지 않는다. 보관 파일은 「대시보드의 HERMES_HOME 아래 `connector-vault/`」 로만 적는다

### 3. 시험: `hermes/tests/test_fos_ctx.py`

- `isolated: false` 대응에서 대응에 없는 `mcp__` 도구, `execute_code`, 내장 도구가 통과하고, 대응 서버의 도구는 정책을 묻는다
- `isolated: false` 대응에서 Control Plane MCP 도구가 `_fos_ctx` 를 받는다
- 칸이 없으면 지금처럼 대응에 없는 `mcp__` 도구와 `execute_code` 를 막는다
- `isolated` 가 boolean 이 아니면 `mcp__` 와 `execute_code` 를 모두 막는다

## 검증

```bash
python3 -m unittest discover -s hermes/tests
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
```

모두 종료 코드 0 이어야 한다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `hermes/plugins/fos-ctx/__init__.py` | 수정 |
| `hermes/tests/test_fos_ctx.py` | 수정 |
| `hermes/README.md` | 수정 |
| `docs/hermes/fos-ctx.md` | 수정 |
| `docs/backend/connector-install.md` | 수정 |
| `docs/backend/connector-tool-policy.md` | 수정 |
