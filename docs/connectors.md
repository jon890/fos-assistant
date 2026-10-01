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
  "operator_env": ["ACCOUNTBOOK_API_BASE_URL"],
  "errors": { "ACCOUNTBOOK_UNAUTHORIZED": "credential_rejected",
              "ACCOUNTBOOK_FORBIDDEN": "forbidden",
              "ACCOUNTBOOK_NETWORK": "unavailable",
              "ACCOUNTBOOK_UNAVAILABLE": "unavailable" }
}
```

| 칸 | 뜻 |
| --- | --- |
| `schema` | 지금은 `1` 만 받는다 |
| `id` | 커넥터 번호. `^[a-z0-9][a-z0-9-]{0,63}$`. 운영 목록의 이름과 같아야 한다 |
| `title`, `description` | 화면에 그대로 보인다. 전용 에이전트의 이름은 `title` 이다 |
| `fields[].key` | 칸 번호. 요청의 `values` 와 저장의 키다. `^[a-z][a-z0-9_]{0,31}$`, 커넥터 안에서 유일 |
| `fields[].env` | 이 칸 값을 쓸 profile `.env` 이름. `.mcp.json` 의 서버 env 가 `${이름}` 으로 참조해야 한다 |
| `fields[].secret` | 참이면 화면이 가리고, 저장은 앞 8자만, 응답에 원문을 담지 않는다 |
| `fields[].required` | 거짓이면 비워 둘 수 있다. 비우면 그 env 를 지운다 |
| `fields[].pattern` | 있으면 Control Plane 과 대시보드가 모두 검사한다 |
| `fields[].options` | 선택지 칸. `tool` 을 불러 결과의 `items` 배열에서 `value`, `label` 칸을 꺼낸다. `auto_select_single` 이 참이면 하나뿐일 때 화면이 고른다 |
| `verify.tool` | 등록 전에 후보 값으로 부르는 확인 도구. 성공하면 값이 유효하다고 본다 |
| `operator_env` | 사용자가 넣지 않고 운영자가 주는 env 이름. 값은 운영 설정이 갖는다 |
| `errors` | 도구 오류 코드를 공통 어휘로 바꾸는 표. 표에 없는 코드는 `unavailable` 이다 |

- `options.tool` 과 `verify.tool` 은 `.mcp.json` 서버의 도구 가운데 `readOnlyHint: true` 인 것만 된다. 대시보드가 읽을 때와 부를 때 둘 다 본다
- `.mcp.json` 서버 env 는 `fields[].env` 와 `operator_env` 의 합과 같아야 한다. 하나라도 다르면 그 커넥터를 카탈로그에 내지 않는다
- 도구 결과는 MCP 응답의 첫 텍스트 칸을 JSON 으로 읽는다. `structuredContent` 가 있으면 그것을 먼저 쓴다. 실패는 `isError: true` 와 `{"error": {"code": "..."}}` 다

공통 오류 어휘는 넷이다.

| 어휘 | Control Plane 오류 코드 | HTTP |
| --- | --- | --- |
| `credential_rejected` | `CONNECTOR_CREDENTIAL_REJECTED` | 400 |
| `forbidden` | `CONNECTOR_FORBIDDEN` | 403 |
| `invalid_input` | `VALIDATION_FAILED` | 400 |
| `unavailable` | `CONNECTOR_UNAVAILABLE` | 503 |

## Control Plane API

| 경로 | 요청 | 결과 |
| --- | --- | --- |
| `GET /api/v1/connectors` | 없음 | `[{id, title, description, fields[], myStatus}]`. `fields[]` 는 `key, label, description, secret, required, pattern, hasOptions, autoSelectSingle` 만 담는다 |
| `GET /api/v1/connections/{id}` | 없음 | 자기 연결 상태 |
| `POST /api/v1/connections/{id}/options/{fieldKey}` | `{values}` | `[{value, label}]`. 아무것도 저장하지 않는다 |
| `POST /api/v1/connections/{id}` | `{values}` | 등록 또는 값 교체. 확인 도구가 통과해야 저장한다 |
| `POST /api/v1/connections/{id}/check` | 없음 | 설치 상태와 MCP probe 재확인 |
| `DELETE /api/v1/connections/{id}` | 없음 | env 제거와 에이전트 비활성화 |
| `GET /api/v1/admin/connections` | 없음 | 같은 그룹의 연결 목록. ADMIN 전용 |
| `POST /api/v1/admin/connections/{id}/{userId}/confirm` | 없음 | 운영 반영 완료 확인. ADMIN 전용 |

- 상태 응답은 `connectorId`, `status`, `secretPrefixes{key: 앞 8자}`, `values{key: 값}`, `checkedAt`, `agentCode`, `restartRequired` 를 갖는다. 등록 전에는 `DISCONNECTED` 이고 나머지는 비거나 null 이다
- 관리자 목록 항목은 `connectorId`, `userId`, `displayName`, `status`, `agentCode`, `restartRequired` 만 담는다. 다른 사용자의 칸 값과 비밀 앞부분은 넣지 않는다
- 모르는 `id` 는 `CONNECTOR_NOT_FOUND`(404) 다. 운영 목록에서 빠진 커넥터의 기존 연결은 읽기와 해제만 된다
- `values` 의 키는 그 커넥터의 `fields[].key` 만 받는다. 모르는 키, 필수 칸 누락, `pattern` 불일치는 `VALIDATION_FAILED` 다
- 선택지와 확인은 후보 값을 저장하지 않고 응답에 되돌려 담지 않는다
- 외부 설치, 확인, 해제가 실패하면 `CONNECTOR_OPERATION_FAILED`(502) 다
- `PENDING` 은 값을 등록했으나 실행 준비가 끝나지 않은 상태, `READY` 는 설치가 켜져 있고 재시작이 필요 없으며 MCP probe 에서 도구를 확인한 상태다. probe 는 공유 gateway 의 실제 실행 확인을 대신하지 않는다

## 대시보드 plugin 계약

대시보드 plugin(`hermes/plugins/dashboard-profile-api`) 이 여는 커넥터 경로다. 인증은 다른 경로와 같은 서비스 토큰이다.

| 경로 | 요청 | 성공 |
| --- | --- | --- |
| `GET /api/connectors/catalog` | 없음 | `[{id, title, description, fields[], mcp_server}]`. 운영 목록에 있고 검증을 통과한 manifest 만 |
| `POST /api/connectors/{id}/call` | `{tool, values}` | `{ok: true, result}` 또는 `{ok: false, error: <공통 어휘>}` |
| `GET /api/connectors?profile=<p>` | query `profile` | `{profile, connectors: [{plugin, enabled, configured}]}` |
| `PUT /api/connectors` | `{profile, plugin, enabled}` | `{profile, plugin, enabled, changed, restart_required}` |
| `PUT /api/env`, `DELETE /api/env` | `{profile, key, value?}` | 커넥터 key 는 `{profile, key, restart_required}` |
| `POST /api/mcp/servers/{server}/test?profile=<p>` | 없음 | `{ok, tools: [{name}]}`. 그 profile 에 설치된 커넥터의 서버만 |

- `call` 은 `tool` 이 그 커넥터의 `options.tool` 이나 `verify.tool` 일 때만 받는다. `values` 를 메모리에서 env 로 넘겨 MCP 서버를 한 번 띄우고, `initialize` 와 `tools/call` 한 번 뒤 닫는다. 디스크에 쓰지 않는다
- `call` 의 시간 제한은 10초, 동시 실행은 대시보드 프로세스 전체에서 4개다. 넘으면 `unavailable` 이다
- 커넥터 key 의 `PUT /api/env` 와 `DELETE /api/env` 는 관리 표식이 있는 profile 에만 된다. 허용 key 는 카탈로그 manifest 의 `fields[].env` 다. `operator_env` 는 사용자 요청으로 쓰지 못한다
- 운영자는 대시보드 프로세스의 환경 변수로 커넥터 목록을 준다. 자세한 모양은 [`code-architecture.md`](code-architecture.md) 의 「Hermes 쪽 코드」 절이 갖는다

## 설치와 실패 처리

처음 등록하면 `AgentLifecycleService.createConnectorAgent` 로 안전한 profile 과 비공개 에이전트를 만든다. 이름은 manifest 의 `title` 이다.
연결용 에이전트는 사용자가 지울 수 없으므로 사용자당 에이전트 상한을 거치지 않고 상한 계산에서도 빠진다.
연결용 에이전트의 공개 범위, 주인, 도구, 성격과 스킬은 일반 편집 경로로 바꾸지 못한다. 연결 화면에서 등록과 확인, 해제만 한다.
성격과 지침은 대시보드 plugin 이 설치할 때 plugin 의 스킬 본문을 persona 에 넣는다.

등록 순서다.

1. 로그인 사용자 확인과 `values` 검사
2. `call(verify.tool, values)` 가 통과해야 한다. 실패는 공통 어휘의 오류 코드로 끝나고 아무것도 저장하지 않는다
3. 사용자 행 잠금, 전용 에이전트 바인딩(처음이면 생성), 에이전트 비활성화, `desired_enabled=false`, `PENDING` 저장
4. 칸마다 `PUT /api/env`. 비운 선택 칸은 `DELETE /api/env`
5. API 도구 목록을 `["fos-assistant"]` 로 다시 쓴다(`PUT /api/config`). 새 profile 의 틀은 내장 도구 `delegation` 을 켜 두기 때문이다
6. `PUT /api/connectors` 로 설치. 설치가 도구 목록에 그 커넥터의 MCP 서버를 덧붙인다
7. 모두 성공하면 `desired_enabled=true`, 칸 값과 비밀 앞부분 저장. 상태는 여전히 `PENDING` 이다

연결 확인과 관리자 반영 완료는 설치의 enabled 와 configured, MCP probe 의 도구, 켜진 내장 도구 없음을 모두 보고 `READY` 로 바꾼다.
켜진 내장 도구가 보이면 목록을 `["fos-assistant", <mcp_server>]` 로 다시 쓰고 다시 읽어 판정한다.

같은 사용자의 등록, 확인과 해제는 사용자 행 잠금으로 순서대로 처리한다.
`desired_enabled` 는 이번 등록의 env 와 설치 단계가 모두 성공해 활성화 후보가 되었는지를 뜻한다. 등록, 교체, 해제를 시작할 때 false 로 두고 모든 외부 반영이 성공한 뒤에만 true 로 둔다. false 인 연결은 확인이나 관리자 반영 완료로 `READY` 가 되지 않는다.
외부 호출이 실패하면 비활성화와 `PENDING` 을 커밋하고 `CONNECTOR_OPERATION_FAILED` 를 돌려준다. 이전 값으로 실행할 수 있는 활성 상태로 되돌리지 않는다.
DB 커밋 자체가 실패하면 이미 반영한 env 나 설치는 되돌리지 못한다. 다시 등록하거나 해제해 상태를 맞춘다.

해제는 칸마다 `DELETE /api/env` 뒤 설치를 끈다. 에이전트와 연결 행은 이력을 위해 남기고 칸 값과 비밀 앞부분을 비운다.
이미 떠 있는 MCP 프로세스는 env 파일이 바뀌어도 옛 값을 쓰고, gateway 는 처음 발견한 도구 목록을 계속 쓴다. 그래서 설치된 연결의 값 교체, env 삭제와 해제는 `restart_required` 를 돌려받고, 관리자가 공유 gateway 를 재시작한 뒤 반영 완료를 누를 때까지 재시작 대기로 남는다.
profile 하나의 MCP 만 다시 붙이는 공식 경로는 없다. 대화의 `/reload-mcp` 는 API server 경로에서 명령으로 처리되지 않고, 웹 대화창은 `/` 로 시작하는 입력을 스킬 호출로 읽는다(2026-10-01 운영 확인). MCP 자식 프로세스만 끝내도 도구 목록은 바뀌지 않는다.
처음 설치는 새 profile 의 자동 MCP 발견을 쓰므로 재시작이 필요 없다. 공유 gateway 재시작은 사용자 요청에서 실행하지 않는다.
저장된 대기 값과 각 env, 설치 응답의 `restart_required` 는 논리 OR 로 누적한다. 도중 호출이 실패해도 앞선 true 를 보존한다.
토큰 폐기는 사용자가 그 서비스에서 한다. 폐기하면 다음 요청부터 거절되므로 재시작을 기다리지 않고 외부 접근을 막을 수 있다.

## 저장과 비밀값

`connector_connection` 은 사용자, 커넥터, 에이전트 바인딩, 상태, 칸 값, 마지막 확인 시각, 재시작 필요 여부, 활성화 후보 여부를 저장한다([`data-schema.md`](data-schema.md)).
비밀 칸의 원문과 해시는 저장하지 않는다. 앞 8자만 `fields.secretPrefixes` 에 둔다.
브라우저는 등록을 제출한 직후 비밀 칸 입력을 비우고 다시 표시하지 않는다. 선택지를 고르는 동안은 작성 중인 입력을 쓰고, 조회가 실패해도 입력을 비운다.
요청 record 의 문자열 표현, 외부 오류, 로그와 응답에 비밀 원문을 남기지 않는다.
