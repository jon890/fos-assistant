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
| `fields[].secret` | 참이면 화면이 가리고, 저장은 앞 4자만, 응답에 원문을 담지 않는다. 값이 16자 미만이면 앞부분도 저장하지 않는다 |
| `fields[].required` | 거짓이면 비워 둘 수 있다. 비우면 그 env 를 지운다 |
| `fields[].pattern` | 있으면 Control Plane 과 대시보드가 모두 검사한다 |
| `fields[].options` | 선택지 칸. `tool` 을 불러 결과의 `items` 배열에서 `value`, `label` 칸을 꺼낸다. `auto_select_single` 이 참이면 하나뿐일 때 화면이 고른다 |
| `verify.tool` | 등록 전에 후보 값으로 부르는 확인 도구. 성공하면 값이 유효하다고 본다 |
| `operator_env` | 사용자가 넣지 않고 운영자가 주는 env 이름. 값은 운영 설정이 갖는다. **비밀이 아닌 운영 설정만 둔다.** 값이 profile 설정과 소유 기록에 그대로 복제된다 |
| `operator_secrets` | 운영자가 주는 비밀의 env 이름 목록. 지금은 지원하지 않는다. 비어 있지 않으면 그 커넥터를 카탈로그에 내지 않는다([ADR-045](adr/ADR-045-운영-비밀은-operator-env-와-다른-칸으로-선언하고-자식-mcp-프로세스에만-넣는다.md)) |
| `errors` | 도구 오류 코드를 공통 어휘로 바꾸는 표. 표에 없는 코드는 `unavailable` 이다 |

- `options.tool` 과 `verify.tool` 은 `.mcp.json` 서버의 도구 가운데 `readOnlyHint: true` 인 것만 된다. 대시보드가 도구를 부를 때 `tools/list` 로 확인한다. manifest 를 읽을 때는 도구 이름의 형식만 본다. 카탈로그는 요청마다 읽으므로 읽을 때마다 MCP 서버를 띄우지 않는다
- `.mcp.json` 서버 env 는 `fields[].env` 와 `operator_env` 의 합과 같아야 한다. 하나라도 다르면 그 커넥터를 카탈로그에 내지 않는다
- 도구 결과는 MCP 응답의 첫 텍스트 칸을 JSON 으로 읽는다. `structuredContent` 가 있으면 그것을 먼저 쓴다. 실패는 `isError: true` 와 `{"error": {"code": "..."}}` 다

공통 오류 어휘는 넷이다.

| 어휘 | Control Plane 오류 코드 | HTTP |
| --- | --- | --- |
| `credential_rejected` | `CONNECTOR_CREDENTIAL_REJECTED` | 400 |
| `forbidden` | `CONNECTOR_FORBIDDEN` | 403 |
| `invalid_input` | `VALIDATION_FAILED` | 400 |
| `unavailable` | `CONNECTOR_UNAVAILABLE` | 503 |

사용자별 호출 제한에 걸린 요청은 공통 어휘가 아니라 `CONNECTOR_RATE_LIMITED`(429) 로 끝난다. 아래 「사용자별 호출 제한」 이 갖는다.

## Control Plane API

| 경로 | 요청 | 결과 |
| --- | --- | --- |
| `GET /api/v1/connectors` | 없음 | `[{id, title, description, fields[], myStatus, available}]`. `fields[]` 는 `key, label, description, secret, required, pattern, hasOptions, autoSelectSingle` 만 담는다 |
| `GET /api/v1/connections/{id}` | 없음 | 자기 연결 상태 |
| `POST /api/v1/connections/{id}/options/{fieldKey}` | `{values}` | `[{value, label}]`. 아무것도 저장하지 않는다 |
| `POST /api/v1/connections/{id}` | `{values}` | 등록 또는 값 교체. 확인 도구가 통과해야 저장한다 |
| `POST /api/v1/connections/{id}/check` | 없음 | 설치 상태와 MCP probe 재확인 |
| `DELETE /api/v1/connections/{id}` | 없음 | env 제거와 에이전트 비활성화 |
| `GET /api/v1/admin/connections` | 없음 | 같은 그룹의 연결 목록. ADMIN 전용 |
| `POST /api/v1/admin/connections/{id}/{userId}/confirm` | 없음 | 운영 반영 완료 확인. ADMIN 전용 |

- 상태 응답은 `connectorId`, `status`, `secretPrefixes{key: 앞 4자}`, `values{key: 값}`, `checkedAt`, `agentCode`, `restartRequired` 를 갖는다. 등록 전에는 `DISCONNECTED` 이고 나머지는 비거나 null 이다
- 관리자 목록 항목은 `connectorId`, `userId`, `displayName`, `status`, `agentCode`, `restartRequired` 만 담는다. 다른 사용자의 칸 값과 비밀 앞부분은 넣지 않는다
- 모르는 `id` 는 `CONNECTOR_NOT_FOUND`(404) 다. 운영 목록에서 빠진 커넥터의 기존 연결은 읽기와 해제만 된다
- 목록의 `available` 은 그 커넥터가 지금 카탈로그에 있는지다. 카탈로그에서 빠졌지만 내 연결이 `DISCONNECTED` 가 아닌 커넥터는 `available: false`, 빈 `fields`, 빈 `description` 으로 함께 낸다. 이때 `title` 은 전용 에이전트의 이름이다. 화면이 해제하러 들어갈 길을 남기기 위해서다
- `values` 의 키는 그 커넥터의 `fields[].key` 만 받는다. 모르는 키, 필수 칸 누락, `pattern` 불일치는 `VALIDATION_FAILED` 다. 비밀이 아닌 칸의 값은 500자까지, 비밀 칸의 값은 4096자까지다. 저장할 칸 값 전체가 `fields` 열에 들어가지 않아도 같은 오류다. 외부에 반영하기 전에 검사한다
- 선택지와 확인은 후보 값을 저장하지 않고 응답에 되돌려 담지 않는다
- 외부 설치, 확인, 해제가 실패하면 `CONNECTOR_OPERATION_FAILED`(502) 다
- 선택지 조회, 등록, 연결 확인은 사용자별 호출 제한을 먼저 지난다. 넘으면 외부를 부르지 않고 `CONNECTOR_RATE_LIMITED`(429) 다
- `PENDING` 은 값을 등록했으나 실행 준비가 끝나지 않은 상태, `READY` 는 설치가 켜져 있고 재시작이 필요 없으며 MCP probe 에서 도구를 확인한 상태다. probe 는 공유 gateway 의 실제 실행 확인을 대신하지 않는다

## 대시보드 plugin 계약

대시보드 plugin(`hermes/plugins/dashboard-profile-api`) 이 여는 커넥터 경로다. 인증은 다른 경로와 같은 서비스 토큰이다.

| 경로 | 요청 | 성공 |
| --- | --- | --- |
| `GET /api/connectors/catalog` | 없음 | `[{id, title, description, fields[], verify, mcp_server}]`. 운영 목록에 있고 검증을 통과한 manifest 만. `fields[]` 는 manifest 의 칸 그대로(`env`, `options` 포함)이고 `verify` 는 `{tool}` 이다. `operator_env` 의 이름과 값, `errors` 는 담지 않는다 |
| `POST /api/connectors/{id}/call` | `{tool, values}` | `{ok: true, result}` 또는 `{ok: false, error: <공통 어휘>}` |
| `GET /api/connectors?profile=<p>` | query `profile` | `{profile, connectors: [{plugin, enabled, configured}]}` |
| `PUT /api/connectors` | `{profile, plugin, enabled}` | `{profile, plugin, enabled, changed, restart_required}`. 설치는 서버 등록과 함께 그 profile 의 API 도구 목록을 다시 쓴다 |
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
- 설치는 API 도구 목록(`platform_toolsets.api_server`)을 그 profile 에 설치한 커넥터의 MCP 서버 이름만으로 다시 쓴다. Control Plane MCP 와 내장 도구는 목록에서 빠진다. 마지막 커넥터를 끄면 목록은 `no_mcp` 하나다. 목록을 비우면 Hermes 가 등록된 MCP 서버를 모두 통과시키기 때문이다([ADR-044](adr/ADR-044-커넥터-에이전트는-자기-mcp-서버만-받고-memory-와-control-plane-도구를-받지-않는다.md))
- `GET /api/connectors` 의 `configured` 는 서버 정의가 소유 기록과 같고 API 도구 목록이 설치한 커넥터의 서버 이름과 정확히 같을 때만 참이다. Control Plane MCP 가 목록에 남은 옛 모양은 `configured: false` 다
- 설치와 제거는 쓰기 전에 `config.yaml` 과 소유 기록을 `connector-backups/` 에 떠 둔다. profile `.env` 는 떠 두지 않는다. 쓸 때마다 그 디렉터리에 남아 있는 `.env` 사본을 지운다
- 운영자는 대시보드 프로세스의 환경 변수로 커넥터 목록을 준다. 자세한 모양은 [`code-architecture.md`](code-architecture.md) 의 「Hermes 쪽 코드」 절이 갖는다

## 설치와 실패 처리

처음 등록하면 `AgentLifecycleService.createConnectorAgent` 로 안전한 profile 과 비공개 에이전트를 만든다. 이름은 manifest 의 `title` 이다.
연결용 에이전트는 사용자가 지울 수 없으므로 사용자당 에이전트 상한을 거치지 않고 상한 계산에서도 빠진다.
연결용 에이전트의 공개 범위, 주인, 도구, 성격과 스킬은 일반 편집 경로로 바꾸지 못한다. 연결 화면에서 등록과 확인, 해제만 한다.
성격과 지침은 따로 넣지 않는다. 대시보드 plugin 은 plugin 의 스킬 디렉터리가 온전한지만 검증하고 `SOUL.md` 를 쓰지 않는다. 에이전트는 MCP 서버가 내는 도구 이름과 설명으로 일한다.

등록 순서다.

1. 로그인 사용자 확인과 `values` 검사
2. `call(verify.tool, values)` 가 통과해야 한다. 실패는 공통 어휘의 오류 코드로 끝나고 아무것도 저장하지 않는다. 이 호출은 DB 트랜잭션 밖에서 한다. 최대 10초가 걸려 그동안 DB 연결을 쥐지 않기 위해서다
3. 사용자 행 잠금, 전용 에이전트 바인딩(처음이면 생성), 에이전트 비활성화, `desired_enabled=false`, `PENDING` 저장
4. 칸마다 `PUT /api/env`. 비운 선택 칸은 `DELETE /api/env`
5. `PUT /api/connectors` 로 설치. 설치가 API 도구 목록을 그 커넥터의 MCP 서버 이름만으로 다시 쓴다. 새 profile 의 틀이 켜 둔 Control Plane MCP 와 내장 도구 `delegation` 이 이때 빠진다
6. 모두 성공하면 `desired_enabled=true`, 칸 값과 비밀 앞부분 저장. 상태는 여전히 `PENDING` 이다

연결 확인과 관리자 반영 완료는 설치의 enabled 와 configured, MCP probe 의 도구, 켜진 내장 도구 없음을 모두 보고 `READY` 로 바꾼다.
설치가 켜져 있는데 `configured` 가 거짓이거나 켜진 내장 도구가 보이면 설치를 한 번 다시 써서(`PUT /api/connectors`, `enabled: true`) 목록을 맞춘다. 칸 값은 profile `.env` 에 그대로 있어 다시 입력받지 않는다.
다시 쓴 응답이 `restart_required` 이면 재시작 대기로 남기고, 아니면 다시 읽어 판정한다. 이전 판이 설치한 연결은 이 경로로 새 목록이 된다.

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

## 저장과 비밀값

`connector_connection` 은 사용자, 커넥터, 에이전트 바인딩, 상태, 칸 값, 마지막 확인 시각, 재시작 필요 여부, 활성화 후보 여부를 저장한다([`data-schema.md`](data-schema.md)).
비밀 칸의 원문과 해시는 저장하지 않는다. 값이 16자 이상일 때만 앞 4자를 `fields.secretPrefixes` 에 둔다.
16자 미만인 값은 앞부분이 원문의 큰 부분이라 `secretPrefixes` 에 넣지 않는다. 화면은 앞부분이 없는 비밀 칸을 「입력됨」 으로만 보인다.
V39 이전에 저장한 앞부분은 원래 길이를 알 수 없어 마이그레이션이 모두 비웠다.
브라우저는 등록을 제출한 직후 비밀 칸 입력을 비우고 다시 표시하지 않는다. 선택지를 고르는 동안은 작성 중인 입력을 쓰고, 조회가 실패해도 입력을 비운다.
요청 record 의 문자열 표현, 외부 오류, 로그와 응답에 비밀 원문을 남기지 않는다.
해제한 뒤에는 profile 디렉터리의 어느 파일에도 칸 값의 원문이 남지 않는다. 설정 백업이 `.env` 를 담지 않기 때문이다.

## 커넥터 에이전트의 경계

커넥터 에이전트는 외부 서비스의 글을 읽는 worker 다. 그 글이 모델을 속여도 닿는 범위를 그 커넥터의 MCP 도구로 한정한다([ADR-044](adr/ADR-044-커넥터-에이전트는-자기-mcp-서버만-받고-memory-와-control-plane-도구를-받지-않는다.md)).

| 무엇 | 어떻게 |
| --- | --- |
| 도구 | profile 의 API 도구 목록에 자기 커넥터의 MCP 서버 이름만 둔다. 대시보드 plugin 의 설치가 쓴다 |
| Memory | 직접 연 대화와 위임받은 실행 모두에서 Memory 문맥을 조립하지 않는다. 실행 줄의 문맥 길이는 0 이고 지문은 비어 있다 |
| Control Plane MCP 호출 | origin 실행의 에이전트가 커넥터 에이전트이면 요청자를 정하지 않고 거절한다. 응답은 서명이 틀린 호출과 같다 |
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
