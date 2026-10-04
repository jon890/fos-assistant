# 커넥터 연결

사용자는 「연결」 화면에서 외부 서비스의 개인 토큰을 넣어 그 서비스 전용 에이전트를 만든다.
연결은 사용자별 전용 profile 과 비공개 에이전트를 갖는다([ADR-039](adr/ADR-039-외부-서비스-연결은-사용자별-전용-에이전트로-실행한다.md)).
어떤 커넥터가 있고 무엇을 입력받는지는 plugin 의 `connector.json` 이 선언하고, Control Plane 은 서비스 이름과 주소를 모른다([ADR-043](adr/ADR-043-커넥터는-plugin-의-connector-json-으로-선언하고-control-plane-은-범용-흐름만-갖는다.md)).
임의 plugin 을 설치하는 화면은 없다. 연결할 수 있는 커넥터는 운영자가 대시보드 plugin 에 준 목록뿐이다.
이 저장소가 갖는 범용 커넥터는 `hermes/connectors/` 에 있고([ADR-064](adr/ADR-064-범용-커넥터는-이-저장소의-hermes-connectors-에-두고-저장소가-유지보수한다.md)), 만드는 방법은 [커넥터 만들기](connector-authoring.md) 가 갖는다.

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
| `schema` | `1` 이나 `2`. `2` 는 [커넥터 도구 정책](backend/connector-tool-policy.md) 의 「도구 정책」 이 적은 `tools` 를 선언한다 |
| `id` | 커넥터 번호. `^[a-z0-9][a-z0-9-]{0,63}$`. 운영 목록의 이름과 같아야 한다 |
| `title`, `description` | 화면에 그대로 보인다. 전용 에이전트의 이름은 `title` 이다 |
| `fields[].key` | 칸 번호. 요청의 `values` 와 저장의 키다. `^[a-z][a-z0-9_]{0,31}$`, 커넥터 안에서 유일 |
| `fields[].env` | 이 칸 값을 쓸 profile `.env` 이름. `.mcp.json` 의 서버 env 가 `${이름}` 으로 참조해야 한다 |
| `fields[].secret` | 참이면 화면이 가리고 응답에 원문을 담지 않는다. 저장하는 것은 아래 「저장과 비밀값」 이 갖는다 |
| `fields[].required` | 거짓이면 비워 둘 수 있다. 비우면 그 env 를 지운다 |
| `fields[].pattern` | 있으면 Control Plane 과 대시보드가 모두 검사한다 |
| `fields[].options` | 선택지 칸. `tool` 을 불러 결과의 `items` 배열에서 `value`, `label` 칸을 꺼낸다. `auto_select_single` 이 참이면 하나뿐일 때 화면이 고른다 |
| `verify.tool` | 등록 전에 후보 값으로 부르는 확인 도구. 성공하면 값이 유효하다고 본다 |
| `toolsets` | 선택. 연결용 에이전트에 켤 내장 toolset 이름 목록이다. 지금은 `vision` 만 받는다. 없으면 빈 목록이다 |
| `attachments` | 선택 boolean. 참이면 연결용 에이전트의 대화가 사진을 받는다. 없으면 거짓이다 |
| `operator_env` | 사용자가 넣지 않고 운영자가 주는 env 이름. 값은 운영 설정이 갖는다. **비밀이 아닌 운영 설정만 둔다.** 값이 profile 설정과 소유 기록에 그대로 복제된다 |
| `operator_secrets` | 운영자가 주는 비밀의 env 이름 목록. 지금은 지원하지 않는다. 비어 있지 않으면 그 커넥터를 카탈로그에 내지 않는다([ADR-046](adr/ADR-046-운영-비밀은-operator-env-와-다른-칸으로-선언하고-자식-mcp-프로세스에만-넣는다.md)) |
| `errors` | 도구 오류 코드를 공통 어휘로 바꾸는 표. 표에 없는 코드는 `unavailable` 이다. `outcome_unknown` 은 쓰기를 보냈는데 됐는지 모른다는 뜻이다 |

- `options.tool` 과 `verify.tool` 은 `.mcp.json` 서버의 도구 가운데 `readOnlyHint: true` 인 것만 된다. 대시보드가 도구를 부를 때 `tools/list` 로 확인한다. manifest 를 읽을 때는 도구 이름의 형식만 본다. 카탈로그는 요청마다 읽으므로 읽을 때마다 MCP 서버를 띄우지 않는다
- `.mcp.json` 서버 env 는 `fields[].env` 와 `operator_env` 의 합과 같아야 한다. 하나라도 다르면 그 커넥터를 카탈로그에 내지 않는다
- `toolsets` 가 목록이 아니거나, 이름이 겹치거나, `vision` 밖의 이름이 하나라도 있으면 그 커넥터를 카탈로그에 내지 않는다. 셸, 파일, 기억, 스킬, 위임 도구는 manifest 로 열리지 않는다([ADR-044](adr/ADR-044-커넥터-manifest-는-읽기-전용-이미지-도구만-열-수-있다.md))
- `attachments` 가 참인데 `toolsets` 에 `vision` 이 없으면 그 커넥터를 카탈로그에 내지 않는다. 사진은 파일로 놓이고 에이전트가 이미지 도구로 읽기 때문이다([ADR-020](adr/ADR-020-사진은-공유-디렉터리에-두고-에이전트가-파일로-읽는다.md))
- 도구 결과는 MCP 응답의 첫 텍스트 칸을 JSON 으로 읽는다. `structuredContent` 가 있으면 그것을 먼저 쓴다. 실패는 `isError: true` 와 `{"error": {"code": "..."}}` 다

공통 오류 어휘는 다섯이다.

| 어휘 | Control Plane 오류 코드 | HTTP |
| --- | --- | --- |
| `credential_rejected` | `CONNECTOR_CREDENTIAL_REJECTED` | 400 |
| `forbidden` | `CONNECTOR_FORBIDDEN` | 403 |
| `invalid_input` | `VALIDATION_FAILED` | 400 |
| `unavailable` | `CONNECTOR_UNAVAILABLE` | 503 |
| `outcome_unknown` | `CONNECTOR_UNAVAILABLE` | 503 |

`outcome_unknown` 은 승인한 호출의 실행 경로에서만 다르게 읽는다. 대시보드가 `{ok: false}` 대신 504 로 답하고 Control Plane 이 그 줄을 `UNKNOWN` 으로 둔다. 선택지와 확인 도구의 호출에서는 `unavailable` 과 같다.

사용자별 호출 제한에 걸린 요청은 공통 어휘가 아니라 `CONNECTOR_RATE_LIMITED`(429) 로 끝난다. [커넥터 도구 정책](backend/connector-tool-policy.md) 의 「사용자별 호출 제한」 이 갖는다.

## Control Plane API

| 경로 | 요청 | 결과 |
| --- | --- | --- |
| `GET /api/v1/connectors` | 없음 | `[{id, title, description, fields[], tools[], myStatus, available}]`. `fields[]` 는 `key, label, description, secret, required, pattern, hasOptions, autoSelectSingle` 만 담는다. `tools[]` 는 `name, title, risk, approval, grant` 이고 `schema: 1` 은 빈 목록이다. `risk` 와 `approval` 은 `READ`, `NONE` 같은 대문자 enum 이름이고 `grant` 는 그 도구에 상시 허락을 줄 수 있는지다 |
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

## 승인

결정은 [ADR-050](adr/ADR-050-커넥터-쓰기는-control-plane-이-승인-줄을-저장하고-승인한-인자로-한-번만-실행한다.md) 에 있다.

승인과 거절은 그 요청이 나온 대화의 승인 카드에서 한다. 실행 결과는 그 대화의 알림 줄과 자동 turn 으로 온다.
`schema: 1` 커넥터의 선언 없는 도구도 승인 줄이 생기고 같은 카드로 승인한다.

### 승인의 불변식

승인 엔진이 지키는 것이다.

- **승인은 처음 요청한 정확한 도구와 인자에만 적용된다.** 줄은 `dedupe_key` 로 호출 하나를 가리키고, `hermes_tool` 과 `args_sha256` 으로 무엇을 어떤 인자로 불렀는지 못 박는다
- **모델이 다시 만든 비슷한 호출은 그 승인으로 실행되지 않는다.** `tool_call_id` 가 다르면 `dedupe_key` 가 달라 새 판정을 받는다. `dedupe_key` 가 같아도 `hermes_tool` 이나 `args_sha256` 이 다르면 앞 줄의 판정과 승인 요청 번호를 돌려주지 않고 막는다
- **승인한 호출은 저장한 인자로 한 번만 실행한다.** 실행하는 쪽은 Control Plane 이고 `args_json` 에 저장한 글 그대로 보낸다. 모델이 승인 뒤에 같은 도구를 다시 불러 실행하는 길은 없다. hook 은 승인된 줄이 있어도 그 호출을 통과시키지 않는다
- 승인 줄의 상태는 한 방향으로만 바뀐다. `PENDING` 을 떠난 줄은 다시 승인하거나 실행하지 못한다
- 승인 줄을 만든 호출이 같은 `dedupe_key` 로 다시 오면 줄의 상태와 연결 상태와 상관없이 같은 승인 요청 번호와 같은 글로 막는다

### 승인 줄과 경로

판정이 「승인 필요」 이면 Control Plane 은 인자를 `connector_action` 에 `PENDING` 으로 저장하고 `block` 을 돌려준다.
글은 승인 요청 번호를 담고, 대화 화면의 승인 카드에서 승인을 받으라는 것과 같은 도구를 다시 부르지 말라는 것과 승인하면 결과가 그 대화로 온다는 것을 말한다. 대화 없이 돈 실행에는 승인받을 화면이 없어 실행되지 않는다고 말한다.

| 상태 | 뜻 | 다음 |
| --- | --- | --- |
| `PENDING` | 사용자의 답을 기다린다 | `EXECUTING`, `REJECTED`, `EXPIRED` |
| `EXECUTING` | 승인했고 실행을 보냈다 | `SUCCEEDED`, `FAILED`, `UNKNOWN` |
| `SUCCEEDED`, `FAILED` | 실행 결과를 받았다 | |
| `UNKNOWN` | 실행을 보냈으나 결과를 모른다. 다시 실행하지 않는다 | |
| `REJECTED` | 사용자가 거절했거나 시스템이 실행하지 않고 끝냈다. 뒤의 것은 `errorCode` 가 `not_executable`, `connection_changed`, `hidden_args` 가운데 하나다 | |
| `EXPIRED` | 24시간 안에 답이 없었다 | |

| 경로 | 요청 | 결과 |
| --- | --- | --- |
| `GET /api/v1/chat/conversations/{conversationId}/connector-actions` | 없음 | 그 대화의 승인 줄. `PENDING` 전부와 끝난 것 가운데 최근 20개 |
| `POST /api/v1/connector-actions/{actionId}/approve` | `{grant}` | 승인하고 실행한 뒤의 줄. `grant` 는 `null`, `HOUR`, `TODAY`, `DAYS_30` |
| `POST /api/v1/connector-actions/{actionId}/reject` | 없음 | 거절한 줄 |
| `GET /api/v1/connector-grants` | 없음 | 내 상시 허락 가운데 유효한 것 |
| `DELETE /api/v1/connector-grants/{grantId}` | 없음 | 그 허락을 거둔다 |

- 승인 줄 응답은 `actionId`, `connectorId`, `toolName`, `title`, `risk`, `status`, `argsJson`, `resultText`, `errorCode`, `createdAt`, `expiresAt`, `grantAllowed`, `hiddenArgs` 를 갖는다. `actionId` 는 공개 식별자(UUID)다
- 응답의 `argsJson` 은 사용자가 읽고 승인하는 글이다. 비밀처럼 보이는 키의 값과 토큰 모양의 글은 `[가림]` 으로 바꿔 낸다([ADR-047](adr/ADR-047-도구-내용은-비밀값과-UUID를-가린-뒤-중계하고-저장한다.md) 의 규칙 가운데 비밀값 부분). UUID 는 가리지 않는다. 무엇을 고치는지 가리키는 값이라 가리면 서로 다른 대상의 요청이 같게 보이고, 이 응답은 주인만 읽는다. 길이로 자르지 않는다. 실행은 저장한 원문으로 한다
- 상시 허락 목록은 지금 선언이 상시 허락을 닫은 도구의 줄을 내지 않는다. 판정이 그 줄을 보지 않아 효력이 없기 때문이다. 그런 줄은 1분마다 도는 정리가 거둔다. 거둔 줄은 선언이 다시 열려도 되살아나지 않는다. 카탈로그를 읽지 못했으면 낸다
- 상시 허락 응답은 `grantId`, `connectorId`, `toolName`, `title`, `expiresAt` 을 갖는다. `title` 은 카탈로그가 선언한 이름이다
- `title` 은 선언에 이름이 없거나 카탈로그를 읽지 못했으면 고정 문구 `이름 없는 동작` 이다. 도구의 원래 이름은 내부 값이라 `title` 과 알림 줄과 모델 입력에 싣지 않는다
- **상시 허락을 닫은 도구의 승인 줄은 인자가 하나도 가려지지 않아야 승인된다**([ADR-065](adr/ADR-065-외부로-나가는-도구는-상시-허락을-닫는-선언을-둔다.md)). 그 도구는 밖으로 나가는 호출이라 사람이 원문을 다 읽어야 한다. 가린 글이 저장한 원문과 다르면 `hiddenArgs` 가 참이다. 화면은 경고를 보이고 「승인」 을 막는다. 그 줄에 승인 요청이 오면 실행하지 않고 `REJECTED`(`errorCode: hidden_args`)로 끝낸 줄을 200 으로 돌려준다. 상시 허락을 줄 수 있는 도구와 `PENDING` 이 아닌 줄은 `hiddenArgs` 가 거짓이다. 선언은 `grantAllowed` 와 같이 줄을 읽을 때의 카탈로그로 본다. 카탈로그를 읽지 못했으면 그 도구가 상시 허락을 닫았는지 알 수 없으므로 거짓이다. 그 줄에 승인 요청이 오면 정책을 판정하지 못해 `not_executable` 로 끝난다
- 화면은 `toolName`, `actionId`, `errorCode` 를 그리지 않는다. 이름은 `title` 로, 인자는 키와 값으로 보인다. web 의 서버 라우트는 `resultText` 를 브라우저로 옮기지 않는다
- 그 줄의 `user_id` 가 로그인 사용자와 다르면 `CONNECTOR_ACTION_NOT_FOUND`(404) 다. 관리자도 같다
- 요청이 왔을 때 이미 `PENDING` 이 아니던 줄의 승인과 거절은 `CONNECTOR_ACTION_NOT_PENDING`(409) 다. 같은 승인을 두 번 눌러도 실행은 한 번이다
- 승인은 받았으나 실행할 수 없어 그 요청이 끝낸 줄은 오류가 아니라 200 과 끝난 줄을 돌려준다. 기다리는 시간이 지났으면 `EXPIRED`, 연결이 `READY` 가 아니거나 정책이 바뀌었으면 `REJECTED` 와 `errorCode: not_executable` 이다. 부른 쪽은 「이미 처리된 요청」(409)과 「승인했지만 실행하지 않은 요청」(200 의 `status`)을 구분한다
- 승인은 행 잠금 아래에서 `EXECUTING` 으로 바꾸고 커밋한 뒤, 트랜잭션 밖에서 대시보드의 실행 경로를 부른다. 연결이 `READY` 가 아니면 실행하지 않고 `REJECTED` 로 둔다. 승인은 그 사용자의 행을 먼저 잠그고 승인 줄을 잠근다. 연결을 다시 등록하거나 해제하는 쪽과 같은 순서다
- 실행 요청이 시간 안에 답하지 않았거나 연결이 끊겼으면 `UNKNOWN` 이다
- `grant` 는 `approval: required` 이고 선언이 상시 허락을 닫지 않은 도구에만 받는다. `always` 이거나, 선언이 `"grant": false` 이거나, `tool_name` 이 빈 줄이면 `VALIDATION_FAILED` 다. 승인 줄의 `grantAllowed` 도 같은 조건으로 낸다. 선언은 승인 줄을 읽을 때의 카탈로그로 보고, 카탈로그를 읽지 못하면 거짓이다([ADR-065](adr/ADR-065-외부로-나가는-도구는-상시-허락을-닫는-선언을-둔다.md)). `TODAY` 는 `Asia/Seoul` 의 그날 끝(다음 날 0시)까지다. 서버의 시간대 설정과 상관없다. 사용량 화면의 달 경계와 같은 고정 시간대다
- 같은 실행에서 같은 도구와 같은 `args_sha256` 의 `PENDING` 이 이미 있으면 새 줄을 만들지 않고 그 번호를 돌려준다
- 사건은 줄을 커밋한 뒤에 낸다. 화면이 사건을 받고 읽었을 때 줄이 있어야 한다
- 대화가 있는 새 승인 줄이면 같은 트랜잭션에서 `APPROVAL_REQUESTED` 알림을 만든다([`backend/notification.md`](backend/notification.md))
- 대화에는 `approval` 사건을 낸다. 사건은 승인 요청 번호만 싣고 줄의 내용을 싣지 않는다. 화면은 그 사건을 받으면 승인 줄을 다시 읽는다. 승인 카드는 이 응답으로만 그린다. 깨우기(`assistant.delegation-wake.enabled`)가 꺼져 있어도 이 사건과 아래의 거절, 만료 알림 줄은 나간다
- 결과가 `SUCCEEDED`, `FAILED`, `UNKNOWN` 이면 그 대화에 알림 줄을 남기고 자동 turn 을 열어 결과를 전한다. 위임 결과와 같은 잠금과 같은 연속 상한을 쓴다([ADR-040](adr/ADR-040-위임-결과는-control-plane-이-부모-대화의-다음-turn-을-열어-전한다.md)). 위임 결과가 함께 있으면 한 turn 에 모아 전한다. 사건은 다음 turn 을 정하는 자리를 지나므로 보낼 대기 메시지가 있으면 그것이 먼저 간다([대기열과 중지](backend/turn-control.md) 의 「응답 중 대기열」)
- 알림 줄은 결과마다 한 줄이고 이름과 상태만 쓴다. 결과 본문은 모델 입력에만 넣고 `<external-data>` 로 감싼다. 입력의 머리줄은 `[동작: <title>, 상태: <상태>]` 이다. 요청 번호와 도구의 원래 이름은 모델이 답에 옮겨 화면에 나오지 않게 입력에 싣지 않는다. `UNKNOWN` 은 본문 대신 다시 실행하지 말고 사용자에게 확인을 부탁하라는 글을 넣는다
- 알림 줄 저장, 줄의 `result_delivered_at`, 자동 turn 수 증가는 한 트랜잭션이다. 연속 상한에 닿아 turn 을 열지 못한 결과는 전하지 않은 채 남고, 사용자가 메시지를 보낸 뒤의 turn 이 닫힐 때 전해진다
- 거절과 만료는 대화의 알림 줄만 남기고 자동 turn 을 열지 않는다. 만료는 `APPROVAL_EXPIRED` 알림도 만든다. 시스템이 실행하지 않고 끝낸 줄(`errorCode` 가 `not_executable`, `connection_changed`, `hidden_args`)은 거절이 아니라 취소했다는 글로 알린다. `hidden_args` 는 가려지는 내용이 있어 취소했다는 것과 에이전트에게 그 부분을 빼거나 다시 쓰게 하라는 것을 말한다. 전했다는 표시와 알림 줄을 새 트랜잭션 하나에 넣고 표시를 먼저 적으므로, 같은 줄의 사건이 겹쳐도 알림 줄은 하나다. 그 대화의 turn 이 도는 동안에는 남기지 않고 turn 이 닫힐 때 남긴다. 도는 turn 이 없는지 보는 것과 저장하는 것 사이에 turn 이 열리지 않도록 turn 잠금을 잡은 채 저장하고, 잠금을 못 잡으면 닫힐 때로 미룬다. 답보다 먼저 알림 줄이 끼면 그 답이 알림 줄에 이어진 자동 turn 의 답으로 읽히기 때문이다
- 만료 정리는 1분마다 돈다. 같은 일정이 승인한 지 5분이 넘도록 `EXECUTING` 인 줄을 `UNKNOWN` 으로 바꾼다. 실행의 시간 제한은 60초라 그보다 오래 남은 줄은 결과를 적지 못한 것이다. 서버가 다시 뜨면 `EXECUTING` 을 모두 `UNKNOWN` 으로 바꾼다
- 연결을 해제하거나 값을 다시 등록하면 그 연결의 `PENDING` 을 모두 `REJECTED`(`errorCode: connection_changed`)로 바꾸고 상시 허락을 거둔다. 다른 계정으로 바꾼 뒤 앞선 계정에 한 승인이 실행되지 않게 한다
- 그 연결에 `EXECUTING` 인 줄이 있으면 해제와 다시 등록을 `CONNECTOR_ACTION_EXECUTING`(409)으로 거절한다. 외부에 아무것도 반영하지 않는다. 실행은 트랜잭션 밖에서 그때의 계정 값으로 돌기 때문에, 그 사이 값을 바꾸면 앞선 계정에 한 승인이 새 계정으로 실행된다. 실행이 끝나거나 기동 정리가 `UNKNOWN` 으로 바꾼 뒤에는 받는다
- 승인할 때 정책을 다시 읽는다. 그 도구가 선언에서 빠졌거나 `DESTRUCTIVE`, `FINANCIAL` 이 됐거나 카탈로그를 읽지 못하면 실행하지 않고 `REJECTED`(`errorCode: not_executable`)로 둔다
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
- 도구가 `errors` 표에서 `outcome_unknown` 인 코드로 실패하면 504 로 답한다. 시간 초과와 같이 실행됐는지 모른다는 뜻이다
- 대시보드는 승인 여부를 다시 확인하지 않는다. Control Plane 이 승인한 줄로만 부른다
- 결과 본문은 Control Plane 으로 돌려주되 로그에 싣지 않는다
- 도구가 오류 없이 끝났는데 구조화 결과도 JSON 텍스트도 없으면 `{ok: true, result: {text: <첫 텍스트 칸의 글, 없으면 빈 글>}}` 로 답한다. 실행된 쓰기를 실패로 기록하지 않기 위해서다. 오류로 끝났는데 읽지 못한 결과는 `{ok: false, error: "unavailable"}` 다
- 커넥터의 도구 하나는 프로세스 안의 상태에 기대지 않아야 한다. 이 경로는 Hermes 가 쥔 MCP 연결이 아니라 새 프로세스에서 돈다

## 저장과 비밀값

`connector_connection` 은 사용자, 커넥터, 에이전트 바인딩, 상태, 칸 값, 마지막 확인 시각, 재시작 필요 여부, 활성화 후보 여부, 선언하지 않은 도구 수를 저장한다([`backend/schema/connector.md`](backend/schema/connector.md)).
비밀 칸은 원문과 해시를 저장하지 않고, 값이 충분히 길 때만 앞부분을 남긴다. 길이 기준과 저장 칸은 [`backend/schema/connector.md`](backend/schema/connector.md) 의 「connector_connection」 이 갖는다.
화면은 연결된 상태에서 앞부분이 없는 필수 비밀 칸을 「입력됨」 으로만 보인다. 앞부분이 없는 선택 비밀 칸은 입력 여부를 응답으로 알 수 없어 보이지 않는다.
브라우저는 등록을 제출한 직후 비밀 칸 입력을 비우고 다시 표시하지 않는다. 선택지를 고르는 동안은 작성 중인 입력을 쓰고, 조회가 실패해도 입력을 비운다.
요청 record 의 문자열 표현, 외부 오류, 로그와 응답에 비밀 원문을 남기지 않는다.
`connector_action` 은 도구 호출마다의 판정과 승인 줄을 저장한다. 인자 원문은 승인 줄에만 두고 주인에게만 보인다. 관리자 목록과 로그에는 싣지 않는다.
해제한 뒤에는 profile 디렉터리의 어느 파일에도 칸 값의 원문이 남지 않는다. 설정 백업이 `.env` 를 담지 않기 때문이다.
