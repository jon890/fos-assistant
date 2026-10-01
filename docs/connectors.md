# 커넥터 연결

사용자는 「연결」 화면에서 외부 서비스의 개인 토큰을 넣어 그 서비스 전용 에이전트를 만든다.
연결은 사용자별 전용 profile 과 비공개 에이전트를 갖는다([ADR-039](adr/ADR-039-외부-서비스-연결은-사용자별-전용-에이전트로-실행한다.md)).
어떤 커넥터가 있고 무엇을 입력받는지는 plugin 의 `connector.json` 이 선언하고, Control Plane 은 서비스 이름과 주소를 모른다([ADR-043](adr/ADR-043-커넥터는-plugin-의-connector-json-으로-선언하고-control-plane-은-범용-흐름만-갖는다.md)).
임의 plugin 을 설치하는 화면은 없다. 연결할 수 있는 커넥터는 운영자가 대시보드 plugin 에 준 목록뿐이다.

## connector.json

plugin 디렉터리 root 에 둔다. 소유는 그 plugin 의 저장소다. 같은 디렉터리의 `.mcp.json` 이 MCP 서버 하나를 정의하고, `connector.json` 은 그 서버를 사람에게 어떻게 연결하는지를 정한다.

```json
{
  "schema": 1,
  "id": "fos-accountbook",
  "title": "가계부",
  "description": "가족 가계부의 수입과 지출을 조회하고 기록합니다.",
  "fields": [
    { "key": "token", "env": "ACCOUNTBOOK_API_TOKEN", "label": "연동 토큰",
      "description": "가계부 설정 화면에서 발급합니다.",
      "secret": true, "required": true, "pattern": "^fab_[A-Za-z0-9_-]{43}$" },
    { "key": "family", "env": "ACCOUNTBOOK_FAMILY_UUID", "label": "가족", "required": false,
      "options": { "tool": "list_families", "items": "families", "value": "uuid",
                   "label": "name", "auto_select_single": true } }
  ],
  "verify": { "tool": "list_families" },
  "toolsets": ["vision"],
  "attachments": true,
  "operator_env": ["ACCOUNTBOOK_API_BASE_URL"],
  "errors": { "ACCOUNTBOOK_UNAUTHORIZED": "credential_rejected",
              "ACCOUNTBOOK_FORBIDDEN": "forbidden",
              "ACCOUNTBOOK_NETWORK": "unavailable",
              "ACCOUNTBOOK_UNAVAILABLE": "unavailable" }
}
```

| 칸 | 뜻 |
| --- | --- |
| `schema` | `1` 이나 `2`. `2` 는 아래 「도구 정책」 의 `tools` 를 선언한다 |
| `id` | 커넥터 번호. `^[a-z0-9][a-z0-9-]{0,63}$`. 운영 목록의 이름과 같아야 한다 |
| `title`, `description` | 화면에 그대로 보인다. 전용 에이전트의 이름은 `title` 이다 |
| `fields[].key` | 칸 번호. 요청의 `values` 와 저장의 키다. `^[a-z][a-z0-9_]{0,31}$`, 커넥터 안에서 유일 |
| `fields[].env` | 이 칸 값을 쓸 profile `.env` 이름. `.mcp.json` 의 서버 env 가 `${이름}` 으로 참조해야 한다 |
| `fields[].secret` | 참이면 화면이 가리고, 저장은 앞 4자만, 응답에 원문을 담지 않는다. 값이 16자 미만이면 앞부분도 저장하지 않는다 |
| `fields[].required` | 거짓이면 비워 둘 수 있다. 비우면 그 env 를 지운다 |
| `fields[].pattern` | 있으면 Control Plane 과 대시보드가 모두 검사한다 |
| `fields[].options` | 선택지 칸. `tool` 을 불러 결과의 `items` 배열에서 `value`, `label` 칸을 꺼낸다. `auto_select_single` 이 참이면 하나뿐일 때 화면이 고른다 |
| `verify.tool` | 등록 전에 후보 값으로 부르는 확인 도구. 성공하면 값이 유효하다고 본다 |
| `toolsets` | 선택. 연결용 에이전트에 켤 내장 toolset 이름 목록이다. 지금은 `vision` 만 받는다. 없으면 빈 목록이다 |
| `attachments` | 선택 boolean. 참이면 연결용 에이전트의 대화가 사진을 받는다. 없으면 거짓이다 |
| `operator_env` | 사용자가 넣지 않고 운영자가 주는 env 이름. 값은 운영 설정이 갖는다. **비밀이 아닌 운영 설정만 둔다.** 값이 profile 설정과 소유 기록에 그대로 복제된다 |
| `operator_secrets` | 운영자가 주는 비밀의 env 이름 목록. 지금은 지원하지 않는다. 비어 있지 않으면 그 커넥터를 카탈로그에 내지 않는다([ADR-046](adr/ADR-046-운영-비밀은-operator-env-와-다른-칸으로-선언하고-자식-mcp-프로세스에만-넣는다.md)) |
| `errors` | 도구 오류 코드를 공통 어휘로 바꾸는 표. 표에 없는 코드는 `unavailable` 이다 |

- `options.tool` 과 `verify.tool` 은 `.mcp.json` 서버의 도구 가운데 `readOnlyHint: true` 인 것만 된다. 대시보드가 도구를 부를 때 `tools/list` 로 확인한다. manifest 를 읽을 때는 도구 이름의 형식만 본다. 카탈로그는 요청마다 읽으므로 읽을 때마다 MCP 서버를 띄우지 않는다
- `.mcp.json` 서버 env 는 `fields[].env` 와 `operator_env` 의 합과 같아야 한다. 하나라도 다르면 그 커넥터를 카탈로그에 내지 않는다
- `toolsets` 가 목록이 아니거나, 이름이 겹치거나, `vision` 밖의 이름이 하나라도 있으면 그 커넥터를 카탈로그에 내지 않는다. 셸, 파일, 기억, 스킬, 위임 도구는 manifest 로 열리지 않는다([ADR-044](adr/ADR-044-커넥터-manifest-는-읽기-전용-이미지-도구만-열-수-있다.md))
- `attachments` 가 참인데 `toolsets` 에 `vision` 이 없으면 그 커넥터를 카탈로그에 내지 않는다. 사진은 파일로 놓이고 에이전트가 이미지 도구로 읽기 때문이다([ADR-020](adr/ADR-020-사진은-공유-디렉터리에-두고-에이전트가-파일로-읽는다.md))
- 도구 결과는 MCP 응답의 첫 텍스트 칸을 JSON 으로 읽는다. `structuredContent` 가 있으면 그것을 먼저 쓴다. 실패는 `isError: true` 와 `{"error": {"code": "..."}}` 다

공통 오류 어휘는 넷이다.

| 어휘 | Control Plane 오류 코드 | HTTP |
| --- | --- | --- |
| `credential_rejected` | `CONNECTOR_CREDENTIAL_REJECTED` | 400 |
| `forbidden` | `CONNECTOR_FORBIDDEN` | 403 |
| `invalid_input` | `VALIDATION_FAILED` | 400 |
| `unavailable` | `CONNECTOR_UNAVAILABLE` | 503 |

사용자별 호출 제한에 걸린 요청은 공통 어휘가 아니라 `CONNECTOR_RATE_LIMITED`(429) 로 끝난다. 아래 「사용자별 호출 제한」 이 갖는다.

## 도구 정책

`schema: 2` 는 그 MCP 서버의 도구마다 위험도와 승인 방식을 선언한다([ADR-047](adr/ADR-047-커넥터-도구-호출은-profile-plugin-의-hook-이-control-plane-에-물어-판정한다.md)).

```json
{
  "schema": 2,
  "id": "demo-notes",
  "tools": {
    "list_scopes": { "risk": "READ" },
    "write_note":  { "risk": "WRITE", "title": "메모 쓰기" },
    "purge_notes": { "risk": "DESTRUCTIVE", "title": "메모 모두 지우기" }
  },
  "default_tool_policy": "deny"
}
```

| 칸 | 뜻 |
| --- | --- |
| `tools.<이름>` | 키는 MCP 서버의 원래 도구 이름이다. `^[A-Za-z0-9_.-]{1,128}$` |
| `tools.<이름>.risk` | `READ`, `SENSITIVE`, `WRITE`, `DESTRUCTIVE`, `FINANCIAL` 가운데 하나. 필수 |
| `tools.<이름>.approval` | `none`, `required`, `always`. 없으면 그 위험도의 기본값 |
| `tools.<이름>.title` | 승인 카드에 보일 사람 말. 80자까지. 없으면 도구 이름을 보인다 |
| `default_tool_policy` | `tools` 에 없는 도구의 처리. `deny` 만 받는다. 없으면 `deny` 다 |

| 위험도 | 뜻 | 기본 `approval` | 하한 |
| --- | --- | --- | --- |
| `READ` | 외부 상태를 바꾸지 않는 조회 | `none` | 없다 |
| `SENSITIVE` | 상태는 바꾸지 않지만 결과가 민감하거나 데이터를 제3자에게 보이게 한다 | `required` | `required` |
| `WRITE` | 되돌릴 수 있는 쓰기 | `required` | `required` |
| `DESTRUCTIVE` | 되돌리기 어려운 쓰기 | `always` | `always` |
| `FINANCIAL` | 돈이 움직인다 | `always` | `always` |

| `approval` | 뜻 |
| --- | --- |
| `none` | 바로 실행하고 기록만 남긴다 |
| `required` | 호출마다 승인을 받는다. 사용자가 상시 허락을 주면 그 기간에는 바로 실행한다 |
| `always` | 호출마다 승인을 받는다. 상시 허락을 만들 수 없다 |

엄격한 순서는 `none`, `required`, `always` 다.

- `approval` 이 하한보다 느슨하면 그 커넥터를 카탈로그에 내지 않는다. `WRITE` 에 `none` 을 선언하지 못한다
- `verify.tool` 과 `options.tool` 은 `tools` 에 있고 `risk: READ`, `approval: none` 이어야 한다. 아니면 카탈로그에 내지 않는다
- `schema: 2` 인데 `tools` 가 없거나 비었으면 카탈로그에 내지 않는다
- 등록 이름이 겹치는 도구가 둘 이상이면 카탈로그에 내지 않는다. 등록 이름은 아래 「이름 대응」 이 정한다
- `DESTRUCTIVE` 와 `FINANCIAL` 은 선언할 수 있지만 호출은 늘 거절한다. 그 도구는 모델에게 보이지 않는다
- `approval: always` 인 도구는 설치가 서버 정의의 `tools.exclude` 에 넣어 모델에게 보이지 않게 한다
- Control Plane 도 카탈로그를 읽을 때 하한을 한 번 더 본다. 하한보다 느슨한 커넥터는 없는 커넥터로 다룬다
- 위험도는 plugin 을 만든 사람의 판단이다. Control Plane 은 그 판단이 맞는지 확인하지 못한다. 운영자가 고른 plugin 만 목록에 오른다는 전제에 기댄다

`schema: 1` 은 `tools` 를 선언하지 않는다. `verify.tool` 과 `options.tool` 은 `READ` 와 `none` 으로, 그 밖의 도구는 모두 `WRITE` 와 `required` 로 읽는다.
조회 도구도 승인 대상이 되므로 `schema: 2` 로 올리는 것이 그 plugin 의 할 일이다.

### 이름 대응

Hermes 는 MCP 도구를 `mcp__<서버>__<도구>` 로 등록하면서 글자를 바꾸고 긴 이름을 줄인다([`hermes/connector-policy.md`](hermes/connector-policy.md)).
등록 이름에서 원래 이름을 되찾을 수 없으므로, 설치(`PUT /api/connectors` 의 `enabled: true`)가 대응을 그 profile 의 `.fos-connector-tools.json` 에 적는다.

```json
{
  "v": 1,
  "servers": {
    "demo": {
      "connector": "demo-notes",
      "prefix": "mcp__demo__",
      "tools": { "mcp__demo__list_scopes": "list_scopes", "mcp__demo__write_note": "write_note" }
    }
  }
}
```

- `prefix` 는 서버 이름으로 계산한 등록 이름의 앞부분이다. hook 은 이 값으로 그 호출이 어느 커넥터 서버의 것인지만 안다. 원래 도구 이름은 `tools` 에서만 찾는다
- `tools` 는 manifest 가 선언한 도구다. `schema: 1` 은 `verify.tool` 과 `options.tool` 만 든다
- 설정, 소유 기록과 한 묶음으로 쓰고 실패하면 함께 되돌린다. 해제하면 그 서버의 항목을 뺀다
- 이 파일이 있는 profile 이 연결용 profile 이다

### 도구 호출 판정

연결용 profile 의 `fos-ctx` hook 은 MCP 도구 호출마다 Control Plane 에 묻는다.

| hook 이 본 것 | 처리 |
| --- | --- |
| 대응 파일이 없다 | 연결용 profile 이 아니다. 이 절의 처리를 하지 않는다 |
| 대응 파일을 읽지 못한다 | Control Plane MCP 밖의 `mcp__` 도구를 모두 막는다 |
| Control Plane MCP 의 도구 | 지금처럼 `_fos_ctx` 를 붙인다 |
| `execute_code` | 막는다. 실행 맥락 없이 도구를 부르는 경로다 |
| `prefix` 가 맞는 서버가 없는 `mcp__` 도구 | 막는다 |
| `session_id` 나 `tool_call_id` 가 없다 | 막는다 |
| 그 밖의 커넥터 도구 | Control Plane 에 묻고 답대로 한다 |
| 주소가 없다, 3초 안에 답이 없다, 200 이 아니다, 답을 읽지 못한다 | 막는다 |

막을 때는 늘 글이 있는 `block` 을 돌려준다. 예외의 본문을 글에 넣지 않는다.
hook 이 부를 주소는 gateway 프로세스의 환경 변수 `FOS_CTX_POLICY_URL` 이 갖는다.

**`POST /internal/hermes/connector-policy`**

인증은 그 profile 의 MCP 토큰이다(`Authorization: Bearer`).

| 요청 칸 | 값 |
| --- | --- |
| `v` | `1` |
| `root_session_id`, `session_id`, `tool_call_id` | `_fos_ctx` 와 같은 뜻이다([`hermes/delegation.md`](hermes/delegation.md)) |
| `hermes_tool` | hook 이 받은 등록 이름 |
| `tool` | 대응 파일에서 찾은 원래 도구 이름. 없으면 `null` |
| `args_json` | 도구 인자를 hook 이 직렬화한 JSON 글. 키를 정렬하고 공백을 넣지 않는다 |
| `sig` | 아래 서명의 소문자 16진수 |

서명은 `_fos_ctx` 와 같은 key 의 HMAC-SHA256 이다.
서명할 글은 `v1-connector-policy`, `hermes_tool`, `root_session_id`, `session_id`, `tool_call_id`, `args_json` 의 UTF-8 바이트를 SHA-256 한 소문자 16진수를 이 순서로 줄바꿈 하나로 이은 것이다.
인자를 글로 보내고 그 글을 서명하므로 Python 과 Java 의 JSON 직렬화가 달라도 검증이 맞는다.

| 응답 칸 | 값 |
| --- | --- |
| `decision` | `allow` 나 `block` |
| `message` | `block` 일 때 모델에게 보일 글. 비지 않는다 |
| `action_id` | 승인 요청 번호. 승인 요청을 만들었을 때만 있다 |

토큰이나 서명이 틀리면 403 이고 hook 은 막는다.

Control Plane 의 판정 순서다.

1. 토큰으로 profile 을 알고 서명을 확인한다
2. session 으로 origin 실행과 사용자와 대화를 찾는다. `_fos_ctx` 와 같은 방법이다. 찾지 못하면 막고 줄을 남기지 않는다
3. 그 실행의 에이전트가 가진 연결을 찾는다. 연결이 없거나 주인이 다르면 막고 줄을 남기지 않는다
4. 카탈로그에서 도구 정책을 읽는다. 카탈로그는 60초 동안 메모리에 둔다
5. 아래 표로 판정하고 `connector_action` 에 한 줄을 남긴다

| 조건(위에서부터) | 판정 | `deny_reason` |
| --- | --- | --- |
| 카탈로그를 읽지 못했다 | 거절 | `POLICY_UNAVAILABLE` |
| 카탈로그에 그 커넥터가 없다 | 거절 | `POLICY_UNAVAILABLE` |
| 연결이 `READY` 가 아니다 | 거절 | `NOT_READY` |
| `schema: 2` 인데 `tool` 이 없거나 `tools` 에 없다 | 거절 | `UNDECLARED` |
| 위험도가 `DESTRUCTIVE` 나 `FINANCIAL` 이다 | 거절 | `RISK_NOT_OPEN` |
| `args_json` 이 16KB 를 넘는다 | 거절 | `ARGS_TOO_LARGE` |
| `approval` 이 `none` 이다 | 허용 | |
| `approval` 이 `required` 이고 유효한 상시 허락이 있다 | 허용 | |
| 그 밖 | 승인 필요 | |

- 판정은 Hermes 와 DB 를 모르는 함수 하나가 한다. 모델의 인자와 서버의 `readOnlyHint` 는 판정에 들어가지 않는다
- Control Plane 은 hook 이 보낸 `tool` 을 그대로 믿지 않는다. 카탈로그의 `mcp_server` 와 `tool` 로 등록 이름을 다시 계산해 `hermes_tool` 과 다르면 `tool` 이 없는 호출로 읽는다. `tool` 이 도구 이름 형식(`^[A-Za-z0-9_.-]{1,128}$`)이 아닌 요청은 서명이 틀린 요청처럼 403 으로 거절한다
- 같은 호출이 다시 오면 처음 판정을 그대로 돌려준다. 같은 호출인지는 profile, 뿌리 session, session, `tool_call_id` 로 만든 `dedupe_key` 로 안다
- `schema: 1` 에서 `tool` 이 없는 호출은 `WRITE` 와 `required` 로 판정하고 `tool_name` 을 비운 채 `hermes_tool` 만 남긴다

### hook 이 켜져 있는지

`GET /api/connectors?profile=<p>` 는 `policy_hook` 을 함께 낸다. 아래가 모두 맞을 때만 참이다.

- 그 profile 설정의 `plugins.enabled` 에 `fos-ctx` 가 있고 `plugins.disabled` 에 없다
- `plugins.entries.fos-ctx.allow_tool_override` 가 `false` 다
- 그 profile 의 `plugins/fos-ctx/` 파일이 대시보드 묶음의 것과 바이트까지 같다
- `.fos-connector-tools.json` 이 지금 설치된 커넥터와 manifest 로 계산한 것과 같다

설치는 그 profile 의 `fos-ctx` 를 묶음의 판으로 바꾼다. 파일이 바뀌었으면 `plugin_updated: true` 로 답한다. 떠 있는 gateway 가 옛 코드를 쥐고 있을 수 있기 때문이다.
선택 칸의 `PUT /api/env` 와 `DELETE /api/env` 도 설치를 다시 쓴다. 그때 `fos-ctx` 가 바뀌었으면 그 응답의 `restart_required` 가 참이다.
Control Plane 은 `plugin_updated` 가 참인 연결을 재시작 대기로 둔다. 관리자가 공유 gateway 를 재시작하고 반영 완료를 누르면 풀린다. 설치된 연결의 `restart_required` 는 늘 참이라 이 신호로 쓰지 못한다.
서버 정의의 `tools.exclude` 가 manifest 로 계산한 것과 다를 때도 `policy_hook` 은 거짓이다. 소유 기록과 지금 manifest 의 같음 판정은 `tools` 를 보지 않는다. 옛 기록을 가진 연결이 끊기지 않고, 다시 보낸 설치가 덮어쓴다.
연결 확인과 관리자 반영 완료는 설치를 다시 보낸 뒤에 `policy_hook` 을 읽는다. 옛 판의 `fos-ctx` 를 가진 연결은 연결 확인 한 번으로 새 판이 되고 재시작 대기가 된다.
Control Plane 은 `policy_hook` 이 참이 아니면 그 연결을 `READY` 로 두지 않는다. 옛 대시보드 plugin 은 이 칸을 내지 않고, 없는 칸은 거짓으로 읽는다.
이 확인은 확인한 시점의 파일만 본다. 그 뒤 누가 설정을 바꾸면 다음 연결 확인 때 안다.

### 선언하지 않은 도구

연결 확인과 관리자 반영 완료는 MCP probe 가 낸 도구 이름에서 `schema: 2` manifest 의 `tools` 에 없는 것을 센다.
그 수를 `connector_connection.undeclared_tools` 에 적고 연결 상태와 관리자 목록에 `undeclaredTools` 로 낸다.
선언하지 않은 도구가 있어도 `READY` 는 된다. 그 도구의 호출만 거절된다. `schema: 1` 은 세지 않는다.

## Control Plane API

| 경로 | 요청 | 결과 |
| --- | --- | --- |
| `GET /api/v1/connectors` | 없음 | `[{id, title, description, fields[], tools[], myStatus, available}]`. `fields[]` 는 `key, label, description, secret, required, pattern, hasOptions, autoSelectSingle` 만 담는다. `tools[]` 는 `name, title, risk, approval` 이고 `schema: 1` 은 빈 목록이다 |
| `GET /api/v1/connections/{id}` | 없음 | 자기 연결 상태 |
| `POST /api/v1/connections/{id}/options/{fieldKey}` | `{values}` | `[{value, label}]`. 아무것도 저장하지 않는다 |
| `POST /api/v1/connections/{id}` | `{values}` | 등록 또는 값 교체. 확인 도구가 통과해야 저장한다 |
| `POST /api/v1/connections/{id}/check` | 없음 | 설치 상태와 MCP probe 재확인 |
| `DELETE /api/v1/connections/{id}` | 없음 | env 제거와 에이전트 비활성화 |
| `GET /api/v1/admin/connections` | 없음 | 같은 그룹의 연결 목록. ADMIN 전용 |
| `POST /api/v1/admin/connections/{id}/{userId}/confirm` | 없음 | 운영 반영 완료 확인. ADMIN 전용 |

- 상태 응답은 `connectorId`, `status`, `secretPrefixes{key: 앞 4자}`, `values{key: 값}`, `checkedAt`, `agentCode`, `restartRequired`, `undeclaredTools` 를 갖는다. 등록 전에는 `DISCONNECTED` 이고 나머지는 비거나 null 이다
- 관리자 목록 항목은 `connectorId`, `userId`, `displayName`, `status`, `agentCode`, `restartRequired`, `undeclaredTools` 만 담는다. 다른 사용자의 칸 값과 비밀 앞부분은 넣지 않는다
- 모르는 `id` 는 `CONNECTOR_NOT_FOUND`(404) 다. 운영 목록에서 빠진 커넥터의 기존 연결은 읽기와 해제만 된다
- 목록의 `available` 은 그 커넥터가 지금 카탈로그에 있는지다. 카탈로그에서 빠졌지만 내 연결이 `DISCONNECTED` 가 아닌 커넥터는 `available: false`, 빈 `fields`, 빈 `description` 으로 함께 낸다. 이때 `title` 은 전용 에이전트의 이름이다. 화면이 해제하러 들어갈 길을 남기기 위해서다
- `values` 의 키는 그 커넥터의 `fields[].key` 만 받는다. 모르는 키, 필수 칸 누락, `pattern` 불일치는 `VALIDATION_FAILED` 다. 비밀이 아닌 칸의 값은 500자까지, 비밀 칸의 값은 4096자까지다. 저장할 칸 값 전체가 `fields` 열에 들어가지 않아도 같은 오류다. 외부에 반영하기 전에 검사한다
- 선택지와 확인은 후보 값을 저장하지 않고 응답에 되돌려 담지 않는다
- 외부 설치, 확인, 해제가 실패하면 `CONNECTOR_OPERATION_FAILED`(502) 다
- 선택지 조회, 등록, 연결 확인은 사용자별 호출 제한을 먼저 지난다. 넘으면 외부를 부르지 않고 `CONNECTOR_RATE_LIMITED`(429) 다
- `PENDING` 은 값을 등록했으나 실행 준비가 끝나지 않은 상태, `READY` 는 설치가 켜져 있고 재시작이 필요 없으며 정책 hook 이 켜져 있고 MCP probe 에서 도구를 확인한 상태다. probe 는 공유 gateway 의 실제 실행 확인을 대신하지 않는다

## 대시보드 plugin 계약

대시보드 plugin(`hermes/plugins/dashboard-profile-api`) 이 여는 커넥터 경로다. 인증은 다른 경로와 같은 서비스 토큰이다.

| 경로 | 요청 | 성공 |
| --- | --- | --- |
| `GET /api/connectors/catalog` | 없음 | `[{id, schema, title, description, fields[], verify, mcp_server, toolsets, attachments, tools}]`. 운영 목록에 있고 검증을 통과한 manifest 만. `fields[]` 는 manifest 의 칸 그대로(`env`, `options` 포함)이고 `verify` 는 `{tool}` 이다. `toolsets` 와 `attachments` 는 manifest 에 없으면 빈 목록과 거짓이다. 옛 대시보드 plugin 은 두 칸을 내지 않고, Control Plane 은 없는 칸을 같은 기본값으로 읽는다. `tools` 는 `{<이름>: {risk, approval, title}}` 이고 `approval` 은 기본값을 채운 값이다. `schema: 1` 은 `verify.tool` 과 `options.tool` 만 `READ` 로 담는다. `schema` 가 없는 응답은 `1` 로 읽는다. `operator_env` 의 이름과 값, `errors` 는 담지 않는다 |
| `POST /api/connectors/{id}/call` | `{tool, values}` | `{ok: true, result}` 또는 `{ok: false, error: <공통 어휘>}` |
| `GET /api/connectors?profile=<p>` | query `profile` | `{profile, policy_hook, connectors: [{plugin, enabled, configured}]}` |
| `PUT /api/connectors` | `{profile, plugin, enabled}` | `{profile, plugin, enabled, changed, restart_required, plugin_updated}`. 설치는 서버 등록과 함께 그 profile 의 API 도구 목록과 `SOUL.md` 를 다시 쓴다 |
| `PUT /api/env`, `DELETE /api/env` | `{profile, key, value?}` | 커넥터 key 는 `{profile, key, restart_required}` |
| `POST /api/mcp/servers/{server}/test?profile=<p>` | 없음 | `{ok, tools: [{name}]}`. 그 profile 에 설치된 커넥터의 서버만 |

- `call` 은 `tool` 이 그 커넥터의 `options.tool` 이나 `verify.tool` 일 때만 받는다. `values` 를 메모리에서 env 로 넘겨 MCP 서버를 한 번 띄우고, `initialize` 와 `tools/call` 한 번 뒤 닫는다. 디스크에 쓰지 않는다
- `call` 의 자식 프로세스가 받는 env 는 칸 값, 운영 목록의 `env`, 그리고 MCP SDK 가 늘 더하는 기본 env(`HOME`, `LOGNAME`, `PATH`, `SHELL`, `TERM`, `USER`)뿐이다. 대시보드 프로세스의 다른 env(서비스 토큰, 다른 커넥터의 값)는 넘어가지 않는다
- `call` 은 자식을 띄우기 전에 `mcp` SDK 가 지원 범위인지 본다. 범위 밖이면 부르지 않고 `unavailable` 이다. 아래 「MCP SDK 계약」 이 갖는다
- `call` 의 시간 제한은 10초, 동시 실행은 대시보드 프로세스 전체에서 4개다. 시간을 넘기면 자식 프로세스를 끝내고 `unavailable` 이다. 이미 4개가 돌고 있으면 기다리지 않고 `unavailable` 이다
- 커넥터 key 의 `PUT /api/env` 와 `DELETE /api/env` 는 관리 표식이 있는 profile 에만 된다. 허용 key 는 카탈로그 manifest 의 `fields[].env` 다. `operator_env` 는 사용자 요청으로 쓰지 못한다. 그 이름의 `PUT` 과 `DELETE` 는 성공으로 답하되 아무것도 쓰지 않고 `restart_required` 는 false 다. 한 배포 동안 옛 Control Plane 이 그 이름을 쓰려 하기 때문이다([ADR-041](adr/ADR-041-hermes-에-설치하는-plugin-과-profile-틀은-이-저장소가-소유한다.md))
- Control Plane 은 카탈로그의 `fields[].env` 로 `PUT /api/env` 의 key 를 정하고 `verify.tool` 로 확인 도구를 부른다. `env` 이름은 Control Plane 의 응답에 담지 않는다
- 운영 목록에서 빠진 커넥터도 그 profile 에 소유 기록이 남아 있으면 `PUT /api/connectors` 의 `enabled: false` 를 받는다. 이때 대시보드가 그 기록의 서버 env 가 참조하던 key 를 profile `.env` 에서 지운다. `GET /api/connectors` 는 그 기록을 `configured: false` 로 낸다
- 운영 목록에도 없고 소유 기록도 없는 plugin 의 `enabled: false` 는 끌 것이 없으므로 `changed: false` 로 성공한다. `enabled: true` 는 거절한다. `GET /api/connectors` 는 그런 plugin 을 목록에 넣지 않고, Control Plane 은 목록에 없는 것을 설치 안 됨(`enabled: false`, `configured: false`)으로 읽는다. 카탈로그에서 빠진 연결의 해제와 반영 완료가 끝까지 가게 하기 위해서다
- 설치는 API 도구 목록(`platform_toolsets.api_server`)을 그 profile 에 설치한 커넥터의 MCP 서버 이름에 그 커넥터들의 manifest 가 선언한 `toolsets` 를 더한 것으로 통째로 다시 쓴다. 서버 이름이 먼저이고 겹친 이름은 한 번만 둔다. 운영 목록에서 빠져 manifest 를 읽을 수 없는 커넥터의 `toolsets` 는 더하지 않는다. Control Plane MCP 와 선언하지 않은 내장 도구는 목록에서 빠지고, `mcp_servers` 의 Control Plane MCP 등록도 지운다. 그 profile 의 MCP 토큰과 `fos-ctx` plugin 은 그대로 둔다. 마지막 커넥터를 끄면 목록은 `no_mcp` 하나다. 목록을 비우면 Hermes 가 등록된 MCP 서버를 모두 통과시키기 때문이다([ADR-045](adr/ADR-045-커넥터-에이전트는-자기-mcp-서버만-받고-memory-와-control-plane-도구를-받지-않는다.md)). 선언으로 열 수 있는 내장 도구는 읽기 전용 이미지 도구뿐이다([ADR-044](adr/ADR-044-커넥터-manifest-는-읽기-전용-이미지-도구만-열-수-있다.md))
- `GET /api/connectors` 의 `configured` 는 서버 정의가 소유 기록과 같고 API 도구 목록이 설치가 쓰는 목록(설치한 커넥터의 서버 이름에 선언한 `toolsets` 를 더한 것)과 정확히 같고 Control Plane MCP 등록이 없을 때만 참이다. Control Plane MCP 나 선언하지 않은 내장 도구가 목록에 남은 옛 모양은 `configured: false` 다
- 설치와 제거는 쓰기 전에 `config.yaml`, 소유 기록, `SOUL.md` 를 `connector-backups/` 에 떠 둔다. profile `.env` 는 떠 두지 않는다. 쓸 때마다 그 디렉터리에 남아 있는 `.env` 사본을 지운다
- 운영자는 대시보드 프로세스의 환경 변수로 커넥터 목록을 준다. 자세한 모양은 [`code-architecture.md`](code-architecture.md) 의 「Hermes 쪽 코드」 절이 갖는다

## 설치와 실패 처리

처음 등록하면 `AgentLifecycleService.createConnectorAgent` 로 안전한 profile 과 비공개 에이전트를 만든다. 이름은 manifest 의 `title` 이다.
연결용 에이전트는 사용자가 지울 수 없으므로 사용자당 에이전트 상한을 거치지 않고 상한 계산에서도 빠진다.
연결용 에이전트의 공개 범위, 주인, 도구, 성격과 스킬은 일반 편집 경로로 바꾸지 못한다. 연결 화면에서 등록과 확인, 해제만 한다.
성격과 지침은 대시보드 plugin 이 설치할 때 plugin 의 스킬 본문을 그 profile 의 `SOUL.md` 에 쓴다. 자세한 것은 아래 「지침」 이 갖는다.

등록 순서다.

1. 로그인 사용자 확인과 `values` 검사
2. `call(verify.tool, values)` 가 통과해야 한다. 실패는 공통 어휘의 오류 코드로 끝나고 아무것도 저장하지 않는다. 이 호출은 DB 트랜잭션 밖에서 한다. 최대 10초가 걸려 그동안 DB 연결을 쥐지 않기 위해서다
3. 사용자 행 잠금, 전용 에이전트 바인딩(처음이면 생성), 에이전트 비활성화, `desired_enabled=false`, `PENDING` 저장
4. 칸마다 `PUT /api/env`. 비운 선택 칸은 `DELETE /api/env`
5. `PUT /api/connectors` 로 설치. 설치가 API 도구 목록을 그 커넥터의 MCP 서버 이름에 manifest 의 `toolsets` 를 더한 것으로 다시 쓰고 `mcp_servers` 의 Control Plane MCP 등록을 지운다. 새 profile 의 틀이 켜 둔 Control Plane MCP 와 내장 도구 `delegation` 이 이때 빠진다
6. 모두 성공하면 `desired_enabled=true`, 칸 값과 비밀 앞부분 저장. 상태는 여전히 `PENDING` 이고 사진은 아직 받지 않는다

Control Plane 은 도구 목록을 쓰지 않는다. `PUT /api/config` 를 등록, 연결 확인, 관리자 반영 완료 어디에서도 부르지 않는다.

연결 확인과 관리자 반영 완료는 MCP probe 앞에서 설치를 한 번 다시 보낸다(`PUT /api/connectors`, `enabled: true`). 다시 보낸 설치가 지침과 도구 목록을 함께 맞춘다. 칸 값은 profile `.env` 에 그대로 있어 다시 입력받지 않는다.
그 뒤 설치의 enabled 와 configured, `policy_hook`, MCP probe 의 도구, 켜진 내장 도구가 manifest 의 `toolsets` 와 같은지를 모두 보고 `READY` 로 바꾼다.
선언 밖의 내장 도구가 남아도, 선언한 도구가 켜지지 않아도, 다시 보낸 뒤 읽은 설치가 `configured` 가 아니어도 `PENDING` 이다.
이전 판이 설치한 연결은 연결 확인이나 관리자 반영 완료 한 번으로 새 목록이 된다.
설치가 꺼져 있거나 카탈로그에서 빠진 연결에는 설치를 다시 보내지 않는다. 재시작 대기인 연결은 연결 확인에서 다시 보내지 않고 관리자 반영 완료에서 다시 보낸다.
`mcp_servers` 의 Control Plane MCP 등록 제거는 이미 떠 있는 gateway 에 재시작 전까지 남을 수 있다. 그동안에도 도구 목록이 그 서버를 막고 Control Plane 이 커넥터 에이전트의 호출을 거절한다.
Control Plane 도 카탈로그를 읽을 때 `toolsets` 를 한 번 더 본다. `vision` 밖의 이름을 선언했거나 `vision` 없이 `attachments` 가 참인 커넥터는 없는 커넥터로 다룬다.

### 지침

설치(`PUT /api/connectors` 의 `enabled: true`)는 plugin 의 스킬 디렉터리마다 `<스킬>/SKILL.md` 를 이름 순으로 읽어 앞머리(frontmatter)를 떼고 이어 붙인 본문을 그 profile 의 `SOUL.md` 에 쓴다.
`skills` toolset 은 열지 않는다. 그 toolset 은 스킬을 고치는 도구까지 열기 때문이다([ADR-039](adr/ADR-039-외부-서비스-연결은-사용자별-전용-에이전트로-실행한다.md)).

- `SKILL.md` 밖의 파일은 읽지 않는다. 스킬 디렉터리 바로 아래의 항목이 하나라도 심볼릭 링크이거나 `SKILL.md` 가 심볼릭 링크이면 그 커넥터를 카탈로그에 내지 않는다. 스킬과 관계없는 파일의 링크도 해당한다. 링크가 plugin 밖의 파일을 가리키면 그 내용이 지침으로 들어가기 때문이다
- 앞머리가 닫히지 않은 `SKILL.md` 가 있어도 그 커넥터를 카탈로그에 내지 않는다
- 합친 본문은 8,000자까지다. Control Plane 의 성격 본문 상한과 같다. 넘으면 그 커넥터를 카탈로그에 내지 않는다
- 스킬이 하나도 없으면 `SOUL.md` 를 바꾸지 않는다
- 대시보드는 연결용 profile 과 다른 관리 profile 을 구분하지 못한다. Control Plane 이 연결용 에이전트의 profile 에만 설치를 보낸다
- 관리 표식이 있는 profile 에, 그 커넥터의 소유 기록과 한 묶음으로만 쓴다. 쓰다가 실패하면 설정, 소유 기록과 함께 되돌린다. 다른 profile 의 `SOUL.md` 는 건드리지 않는다
- 해제는 `SOUL.md` 를 지우지 않는다. 에이전트가 꺼지고, 다시 등록하면 다시 쓴다
- 본문은 카탈로그 응답과 로그에 싣지 않는다

plugin 을 새 판으로 바꾼 뒤 이미 설치된 연결의 지침은 다시 등록, 연결 확인, 관리자 반영 완료에서 갱신된다.
연결 확인과 관리자 반영 완료는 MCP probe 앞에서 같은 설치 요청을 한 번 더 보낸다. 같은 값이면 아무것도 바뀌지 않는다.
설치된 연결의 설치 요청은 늘 `restart_required: true` 로 답하므로 이때는 그 값을 쓰지 않는다. 실행 정의가 소유 기록과 다르면 요청이 실패해 `PENDING` 으로 남는다.

### 사진과 이미지 도구

연결용 에이전트는 기본으로 사진을 받지 않는다. manifest 의 `attachments` 가 참이고 연결이 `READY` 로 확인됐을 때만 받는다.
Control Plane 은 연결 확인과 관리자 반영 완료에서 선언한 toolset 이 실제로 켜진 것을 본 뒤에만 `agent.connector_attachments` 를 참으로 두고, `Agent.acceptsAttachments()` 가 그 열을 본다.
등록 직후, 선언한 toolset 이 켜지지 않았을 때, 확인 중 외부 호출이 실패했을 때는 거짓이다. 사진 단추는 있는데 이미지 도구가 없는 상태를 만들지 않기 위해서다.
화면의 사진 단추와 메시지 전송의 첨부 판정이 모두 그 메서드 하나를 부르므로 같은 값을 본다. 서비스 이름으로 나누는 곳은 없다.

이미 연결된 에이전트는 다시 등록, 연결 확인, 관리자 반영 완료 가운데 어느 것에서든 지금 manifest 의 `toolsets` 를 받는다. `attachments` 는 연결 확인과 관리자 반영 완료가 `READY` 로 판정할 때 받는다.
plugin 이 두 칸을 새로 선언했으면 사용자가 연결 화면에서 연결 확인을 한 번 누르면 된다.
`platform_toolsets.api_server` 는 다음 실행부터 적용되므로 공유 gateway 를 재시작하지 않는다([`hermes/tools-and-skills.md`](hermes/tools-and-skills.md)).
연결이 `PENDING` 이나 `DISCONNECTED` 가 될 때마다 사진을 받지 않는 것으로 되돌린다. 등록, 등록과 해제의 실패, 연결 확인의 실패가 모두 해당한다.
카탈로그에서 빠진 커넥터의 연결은 그 순간에 바뀌지 않는다. 연결 확인이나 관리자 반영 완료가 불릴 때 `PENDING` 이 되고 그때 에이전트가 꺼지며 사진도 받지 않는다.

**배포한 뒤 확인할 것이다. 아직 확인하지 못했다.**

- 대시보드 plugin 을 올린 뒤 카탈로그에 커넥터가 그대로 있는지. 스킬 본문 검증이 새로 생겨, 전에는 나오던 커넥터가 빠질 수 있다
- Control Plane 을 옛 판으로 되돌렸다가 다시 올렸으면 사진을 받는 연결을 한 번 연결 확인한다. 옛 판은 `vision` 을 선언 밖의 도구로 보고 목록에서 뺀다
- 떠 있는 공유 gateway 가 바뀐 `SOUL.md` 를 재시작 없이 다음 실행부터 읽는지. 읽지 않으면 지침 갱신에도 재시작과 관리자 반영 완료가 필요하다
- `file` toolset 없이 `vision` 만 켠 에이전트에서 `vision_analyze` 가 실행 입력에 적힌 사진 경로를 읽는지. 읽지 못하면 사진 단추는 보이지만 에이전트가 사진을 보지 못한다

같은 사용자의 등록, 확인과 해제는 사용자 행 잠금으로 순서대로 처리한다.
`desired_enabled` 는 이번 등록의 env 와 설치 단계가 모두 성공해 활성화 후보가 되었는지를 뜻한다. 등록, 교체, 해제를 시작할 때 false 로 두고 모든 외부 반영이 성공한 뒤에만 true 로 둔다. false 인 연결은 확인이나 관리자 반영 완료로 `READY` 가 되지 않는다.
외부 호출이 실패하면 비활성화와 `PENDING` 을 커밋하고 `CONNECTOR_OPERATION_FAILED` 를 돌려준다. 이전 값으로 실행할 수 있는 활성 상태로 되돌리지 않는다.
DB 커밋 자체가 실패하면 이미 반영한 env 나 설치는 되돌리지 못한다. 다시 등록하거나 해제해 상태를 맞춘다.

해제는 칸마다 `DELETE /api/env` 뒤 설치를 끈다. 운영 목록에서 빠진 커넥터는 Control Plane 이 env 이름을 알 수 없으므로 설치만 끄고, env 는 대시보드가 소유 기록으로 지운다. 이때는 재시작 대기로 둔다. 에이전트와 연결 행은 이력을 위해 남기고 칸 값과 비밀 앞부분을 비운다.
이미 떠 있는 MCP 프로세스는 env 파일이 바뀌어도 옛 값을 쓰고, gateway 는 처음 발견한 도구 목록을 계속 쓴다. 그래서 설치된 연결의 값 교체, env 삭제와 해제는 `restart_required` 를 돌려받고, 관리자가 공유 gateway 를 재시작한 뒤 반영 완료를 누를 때까지 재시작 대기로 남는다.
profile 하나의 MCP 만 다시 붙이는 공식 경로는 없다. 대화의 `/reload-mcp` 는 API server 경로에서 명령으로 처리되지 않고, 웹 대화창은 `/` 로 시작하는 입력을 스킬 호출로 읽는다(2026-10-01 운영 확인). MCP 자식 프로세스만 끝내도 도구 목록은 바뀌지 않는다.
처음 설치는 새 profile 의 자동 MCP 발견을 쓰므로 재시작이 필요 없다. 공유 gateway 재시작은 사용자 요청에서 실행하지 않는다.
저장된 대기 값과 각 env, 설치 응답의 `restart_required` 는 논리 OR 로 누적한다. 도중 호출이 실패해도 앞선 true 를 보존한다.
토큰 폐기는 사용자가 그 서비스에서 한다. 폐기하면 다음 요청부터 거절되므로 재시작을 기다리지 않고 외부 접근을 막을 수 있다.

## 승인

**이 절의 동작은 승인 엔진이 들어올 때 켜진다.**
지금은 판정이 「승인 필요」 여도 호출을 통과시키고 `connector_action` 에 `NEEDS_APPROVAL` 과 `passed: true` 로 기록만 남긴다.
결정은 [ADR-048](adr/ADR-048-커넥터-쓰기는-control-plane-이-승인-줄을-저장하고-승인한-인자로-한-번만-실행한다.md) 에 있다.

판정이 「승인 필요」 이면 Control Plane 은 인자를 `connector_action` 에 `PENDING` 으로 저장하고 `block` 을 돌려준다.
글은 승인 요청 번호를 담고, 사용자의 승인을 기다리고 있으니 같은 도구를 다시 부르지 말라고 말한다.

| 상태 | 뜻 | 다음 |
| --- | --- | --- |
| `PENDING` | 사용자의 답을 기다린다 | `EXECUTING`, `REJECTED`, `EXPIRED` |
| `EXECUTING` | 승인했고 실행을 보냈다 | `SUCCEEDED`, `FAILED`, `UNKNOWN` |
| `SUCCEEDED`, `FAILED` | 실행 결과를 받았다 | |
| `UNKNOWN` | 실행을 보냈으나 결과를 모른다. 다시 실행하지 않는다 | |
| `REJECTED` | 사용자가 거절했거나 연결이 해제됐다 | |
| `EXPIRED` | 24시간 안에 답이 없었다 | |

| 경로 | 요청 | 결과 |
| --- | --- | --- |
| `GET /api/v1/chat/conversations/{conversationId}/connector-actions` | 없음 | 그 대화의 승인 줄. `PENDING` 전부와 끝난 것 가운데 최근 20개 |
| `POST /api/v1/connector-actions/{actionId}/approve` | `{grant}` | 승인하고 실행한 뒤의 줄. `grant` 는 `null`, `HOUR`, `TODAY`, `DAYS_30` |
| `POST /api/v1/connector-actions/{actionId}/reject` | 없음 | 거절한 줄 |
| `GET /api/v1/connector-grants` | 없음 | 내 상시 허락 가운데 유효한 것 |
| `DELETE /api/v1/connector-grants/{grantId}` | 없음 | 그 허락을 거둔다 |

- 승인 줄 응답은 `actionId`, `connectorId`, `toolName`, `title`, `risk`, `status`, `argsJson`, `resultText`, `errorCode`, `createdAt`, `expiresAt`, `grantAllowed` 를 갖는다. `actionId` 는 공개 식별자(UUID)다
- 그 줄의 `user_id` 가 로그인 사용자와 다르면 `CONNECTOR_ACTION_NOT_FOUND`(404) 다. 관리자도 같다
- `PENDING` 이 아닌 줄의 승인과 거절은 `CONNECTOR_ACTION_NOT_PENDING`(409) 다. 같은 승인을 두 번 눌러도 실행은 한 번이다
- 승인은 행 잠금 아래에서 `EXECUTING` 으로 바꾸고 커밋한 뒤, 트랜잭션 밖에서 대시보드의 실행 경로를 부른다. 연결이 `READY` 가 아니면 실행하지 않고 `REJECTED` 로 둔다
- 실행 요청이 시간 안에 답하지 않았거나 연결이 끊겼으면 `UNKNOWN` 이다
- `grant` 는 `approval: required` 인 도구에만 받는다. `always` 이거나 `tool_name` 이 빈 줄이면 `VALIDATION_FAILED` 다. `TODAY` 는 서버 시간대의 그날 끝까지다
- 같은 실행에서 같은 도구와 같은 `args_sha256` 의 `PENDING` 이 이미 있으면 새 줄을 만들지 않고 그 번호를 돌려준다
- 사건은 줄을 커밋한 뒤에 낸다. 화면이 사건을 받고 읽었을 때 줄이 있어야 한다
- 대화에는 `approval` 사건을 낸다. 화면은 그 사건을 받으면 승인 줄을 다시 읽는다. 승인 카드는 이 응답으로만 그린다
- 결과가 `SUCCEEDED`, `FAILED`, `UNKNOWN` 이면 그 대화에 알림 줄을 남기고 자동 turn 을 열어 결과를 전한다. 위임 결과와 같은 잠금과 같은 연속 상한을 쓴다([ADR-040](adr/ADR-040-위임-결과는-control-plane-이-부모-대화의-다음-turn-을-열어-전한다.md))
- 거절과 만료는 알림 줄만 남긴다. 자동 turn 을 열지 않는다
- 만료 정리는 1분마다 돈다. 서버가 다시 뜨면 `EXECUTING` 을 `UNKNOWN` 으로 바꾼다
- 연결을 해제하거나 값을 다시 등록하면 그 연결의 `PENDING` 을 모두 `REJECTED` 로 바꾸고 상시 허락을 거둔다. 다른 계정으로 바꾼 뒤 앞선 계정에 한 승인이 실행되지 않게 한다
- 승인할 때 정책을 다시 읽는다. 그 도구가 선언에서 빠졌거나 `DESTRUCTIVE`, `FINANCIAL` 이 됐거나 카탈로그를 읽지 못하면 실행하지 않고 `REJECTED` 로 둔다
- 같은 인자의 `PENDING` 이 있어 새 줄을 만들지 않은 호출은 줄이 따로 남지 않는다
- 사용자가 turn 을 중지해도 `PENDING` 은 남는다

**`POST /api/connectors/{id}/execute`** 는 대시보드 plugin 의 실행 경로다.

| 요청 | 성공 |
| --- | --- |
| `{profile, hermes_tool, args}` | `{ok: true, result}` 또는 `{ok: false, error: <공통 어휘>}` |

- 그 profile 에 관리 표식이 있고 그 커넥터의 소유 기록이 있어야 한다
- 커넥터 MCP 서버를 자식으로 한 번 띄워 `tools/list` 를 읽고, 등록 이름이 `hermes_tool` 과 같은 도구가 정확히 하나일 때 그 도구를 `args` 로 부른다. `schema: 2` 는 그 도구가 `tools` 에 있어야 한다
- 자식의 env 는 그 profile `.env` 에서 manifest 의 `fields[].env` 만 꺼내고 운영 목록의 `env` 를 더한다. 나머지 값은 넘기지 않는다
- 시간 제한은 60초, 동시 실행은 `call` 과 같은 한도를 함께 쓴다
- 대시보드는 승인 여부를 다시 확인하지 않는다. Control Plane 이 승인한 줄로만 부른다
- 결과 본문은 Control Plane 으로 돌려주되 로그에 싣지 않는다
- 커넥터의 도구 하나는 프로세스 안의 상태에 기대지 않아야 한다. 이 경로는 Hermes 가 쥔 MCP 연결이 아니라 새 프로세스에서 돈다

## 저장과 비밀값

`connector_connection` 은 사용자, 커넥터, 에이전트 바인딩, 상태, 칸 값, 마지막 확인 시각, 재시작 필요 여부, 활성화 후보 여부, 선언하지 않은 도구 수를 저장한다([`data-schema.md`](data-schema.md)).
비밀 칸의 원문과 해시는 저장하지 않는다. 값이 16자 이상일 때만 앞 4자를 `fields.secretPrefixes` 에 둔다.
16자 미만인 값은 앞부분이 원문의 큰 부분이라 `secretPrefixes` 에 넣지 않는다. 화면은 연결된 상태에서 앞부분이 없는 필수 비밀 칸을 「입력됨」 으로만 보인다. 앞부분이 없는 선택 비밀 칸은 입력 여부를 응답으로 알 수 없어 보이지 않는다.
V40 이전에 저장한 앞부분은 원래 길이를 알 수 없어 마이그레이션이 모두 비웠다.
브라우저는 등록을 제출한 직후 비밀 칸 입력을 비우고 다시 표시하지 않는다. 선택지를 고르는 동안은 작성 중인 입력을 쓰고, 조회가 실패해도 입력을 비운다.
요청 record 의 문자열 표현, 외부 오류, 로그와 응답에 비밀 원문을 남기지 않는다.
`connector_action` 은 도구 호출마다의 판정과 승인 줄을 저장한다. 인자 원문은 승인 줄에만 두고 주인에게만 보인다. 관리자 목록과 로그에는 싣지 않는다.
해제한 뒤에는 profile 디렉터리의 어느 파일에도 칸 값의 원문이 남지 않는다. 설정 백업이 `.env` 를 담지 않기 때문이다.

## 커넥터 에이전트의 경계

커넥터 에이전트는 외부 서비스의 글을 읽는 worker 다. 그 글이 모델을 속여도 닿는 범위를 그 커넥터의 MCP 도구로 한정한다([ADR-045](adr/ADR-045-커넥터-에이전트는-자기-mcp-서버만-받고-memory-와-control-plane-도구를-받지-않는다.md)).

| 무엇 | 어떻게 |
| --- | --- |
| 도구 | profile 의 API 도구 목록에 자기 커넥터의 MCP 서버 이름과 manifest 가 선언한 읽기 전용 이미지 도구(`vision`)만 두고 Control Plane MCP 의 서버 등록을 지운다. 대시보드 plugin 의 설치가 쓴다 |
| Memory | 직접 연 대화와 위임받은 실행 모두에서 Memory 문맥을 조립하지 않는다. 실행 줄의 문맥 길이는 0 이고 지문은 비어 있다 |
| Control Plane MCP 호출 | origin 실행의 에이전트가 커넥터 에이전트이면 도구 호출의 요청자를 정하지 않고 거절한다. 응답은 서명이 틀린 호출과 같다. 그 profile 의 MCP 토큰은 유효한 채로 둔다 |
| 위임 결과 | Control Plane 이 실행 줄의 답을 부모 대화의 다음 turn 으로 전한다([ADR-040](adr/ADR-040-위임-결과는-control-plane-이-부모-대화의-다음-turn-을-열어-전한다.md)). worker 의 MCP 호출을 쓰지 않는다 |

부르는 쪽 에이전트가 필요한 맥락을 `agent_delegate` 의 `task` 에 담는다. worker 는 결과물을 쓰지 못하고 다른 에이전트에게 맡기지 못한다.

## 사용자별 호출 제한

선택지 조회(`options`), 등록(`POST /api/v1/connections/{id}`), 연결 확인(`check`)은 MCP 서버를 자식 프로세스로 띄운다.
연결이 없는 사용자도 임의 값으로 부를 수 있어 사용자마다 제한한다.

| 제한 | 기본값 | 설정 |
| --- | --- | --- |
| 한 사용자의 동시 호출 | 1 | `assistant.connector.max-concurrent-calls` |
| 한 사용자의 60초 동안 호출 | 10 | `assistant.connector.calls-per-minute` |

- 넘으면 기다리지 않고 `CONNECTOR_RATE_LIMITED`(429) 로 거절한다. 거절한 요청은 외부를 부르지 않고 횟수에 넣지 않는다
- 횟수는 받아들인 호출의 시작 시각으로 센다. 60초가 지난 시각은 버린다
- 해제, 읽기, 카탈로그, 관리자 경로는 제한하지 않는다. 해제는 언제나 되어야 한다
- 상태는 JVM 메모리에 둔다. **Control Plane 이 한 대라는 전제다.** 여러 대로 늘리면 사용자마다 대수만큼 더 받는다. 재시작하면 횟수가 비워진다
- 일반 에이전트 실행의 한도와 별개다. 대시보드 plugin 의 전역 동시 4개도 그대로다

## MCP SDK 계약

대시보드 plugin 의 `call` 은 공식 `mcp` Python SDK 로 커넥터 서버를 부른다. plugin 은 SDK 를 스스로 설치하지 않고 Hermes 가 설치한 판을 쓴다.

- **지원 범위는 `mcp>=2.0,<3` 이다.** 검사는 `mcp==2.0.0` 으로 돈다
- plugin 이 기대는 이름은 `mcp.ClientSession`, `mcp.StdioServerParameters`, `mcp.client.stdio.stdio_client`, `ToolAnnotations.read_only_hint`, `CallToolResult.structured_content`, `CallToolResult.is_error`, `CallToolResult.content` 다
- 1.x 는 이 속성을 `readOnlyHint`, `structuredContent`, `isError` 로 둔다. 1.x 에서 `is_error` 를 기본값으로 읽으면 도구 오류가 성공으로 읽힌다. 그래서 plugin 은 속성을 직접 읽고, 이름이 없으면 실패한다
- plugin 은 올라올 때와 `call` 마다 SDK 판과 위 속성을 확인한다. 범위 밖이거나 속성이 없으면 자식을 띄우지 않고 `unavailable` 로 답하며, 판과 까닭을 운영 로그 한 줄로 남긴다
- `call` 이 예외로 실패하면 묶음 예외(`ExceptionGroup`)를 풀어 가장 안쪽 예외의 종류와 SDK 판을 로그에 남긴다. 예외 본문과 칸 값은 남기지 않는다
- Hermes 는 `mcp` 를 정확한 판 하나로 고정하므로 판은 Hermes 이미지를 올릴 때만 바뀐다. 올릴 때 확인할 것은 [버전 변경과 실측](hermes/upgrades.md) 에 있다
