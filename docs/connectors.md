# 커넥터 연결

**커넥터는 에이전트에게 도구를 쥐어 주는 것이고, 에이전트의 역할을 넓혀 주는 것이다.**
커넥터는 따로 도는 실행 주체가 아니라 에이전트가 쓰는 외부 서비스의 MCP 도구 묶음이다([ADR-083](adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md)).

사용자는 「연결」 화면에서 커넥터마다 계정을 한 번 연결한다. 이것이 **연결**이고 사용자와 커넥터마다 하나다.
그 연결을 자기 에이전트의 상세에서 붙이고 뗀다. 이것이 **바인딩**이고 에이전트와 연결은 다대다다. 화면에서는 「붙이기」 와 「떼기」 라 쓴다.
붙인 에이전트는 그 커넥터의 도구를 같은 turn 에서 직접 부른다. 붙이고 반영하는 순서는 [커넥터 설치](../backend/docs/flow.md) 가 갖는다.
어떤 커넥터가 있고 무엇을 입력받는지는 plugin 의 `connector.json` 이 선언하고, Control Plane 은 서비스 이름과 주소를 모른다([ADR-043](../backend/docs/adr/ADR-043-커넥터는-plugin-의-connector-json-으로-선언하고-control-plane-은-범용-흐름만-갖는다.md)).
임의 plugin 을 설치하는 화면은 없다. 연결할 수 있는 커넥터는 운영자가 대시보드 plugin 에 준 목록뿐이다.
이 저장소가 갖는 범용 커넥터는 `hermes/connectors/` 에 있고([ADR-064](../hermes/docs/adr/ADR-064-범용-커넥터는-이-저장소의-hermes-connectors-에-두고-저장소가-유지보수한다.md)), 만드는 방법은 [커넥터 만들기](../hermes/connectors/README.md) 가 갖는다.

## 언제 에이전트를 나누는가

**기본은 한 에이전트에 연결을 여럿 붙인다.**
역할을 격리하거나 나눠야 할 때만 일반 에이전트를 하나 더 만들어 그쪽에만 붙이고, 다른 에이전트는 `agent_delegate` 로 그 에이전트에 맡긴다.
커넥터를 위한 특별한 에이전트 종류는 만들지 않는다.

1. **터미널이나 파일 계열 도구가 켜진 에이전트에는 외부 글이 들어오는 커넥터(예: 메일)를 붙이지 않기를 권한다.** 실행 공간을 적용하지 않은 profile 에서는 외부 글의 숨은 지시가 모델을 속여 승인 카드를 비켜 갈 수 있다. 실행 공간을 적용한 profile 에서도 읽은 글을 셸이 인터넷으로 보낼 수 있다. 근거는 ADR-083 의 「감당할 것」 이고, 붙이는 화면의 위험 안내도 같은 근거로 띄운다
2. **붙인 커넥터의 도구 정의 때문에 입력이 크게 늘면 나눈다.** 도구 정의는 그 에이전트의 모든 turn 에 실린다. 에이전트 상세의 연결 목록이 보이는 도구 수를 기준으로 삼는다
3. **그룹에 공개할 에이전트는 따로 둔다.** 연결은 비공개 에이전트에만 붙고, 연결이 붙은 에이전트는 그룹에 공개하지 못한다
4. **성격, Memory, 대화 맥락을 따로 두고 싶을 때 나눈다**
5. **나눈다고 비밀값이 격리되지는 않는다.** profile 을 나눠도 파일 접근은 나뉘지 않는다. 실행 공간을 적용하지 않은 profile 의 터미널 도구는 다른 profile 의 `.env` 와 보관 파일에 닿는다. 비밀값을 셸에서 떼어 놓는 것은 [ADR-086](adr/ADR-086-셸과-파일-도구는-사용자별-docker-실행-공간에서만-돈다.md) 의 실행 공간이고, 운영 정책에 등록한 뒤 셸 도구를 다시 저장했거나 운영 일괄 반영을 거친 profile 에만 적용된다

## Control Plane API

| 경로 | 요청 | 결과 |
| --- | --- | --- |
| `GET /api/v1/connectors` | 없음 | `[{id, title, description, icon, link, fields[], tools[], myStatus, available, bindings[], ownerBrowserLoginUrl}]`. `icon` 은 `data:image/svg+xml;base64,...` 나 `data:image/png;base64,...` 의 data URL 이거나 null 이고, `link` 는 `https://` 주소이거나 null 이다. `fields[]` 는 `key, label, description, secret, required, pattern, hasOptions, autoSelectSingle` 만 담는다. `tools[]` 는 `name, title, risk, approval, grant` 이고 `schema: 1` 은 빈 목록이다. `risk` 와 `approval` 은 `READ`, `NONE` 같은 대문자 enum 이름이고 `grant` 는 그 도구에 상시 허락을 줄 수 있는지다. `bindings[]` 는 내 연결이 붙은 에이전트다. `ownerBrowserLoginUrl` 은 manifest 의 `owner_browser_login_url` 이거나 null 이다 |
| `GET /api/v1/connections/{id}` | 없음 | 자기 연결 상태 |
| `POST /api/v1/connections/{id}/options/{fieldKey}` | `{values}` | `[{value, label}]`. 아무것도 저장하지 않는다 |
| `POST /api/v1/connections/{id}` | `{values}` | 등록 또는 값 교체. 확인 도구가 통과해야 보관 파일에 쓰고, 붙은 바인딩마다 설치를 다시 보낸다. 입력 칸이 없는 커넥터는 빈 `values` 다 |
| `POST /api/v1/connections/{id}/check` | 없음 | 연결 확인. 보관 파일의 값으로 확인 도구를 부르고 붙은 바인딩마다 설치와 반영을 맞춘다 |
| `DELETE /api/v1/connections/{id}` | 없음 | 연결 해제. 붙은 바인딩을 모두 떼고 보관 파일을 지운다 |
| `GET /api/v1/agents/{code}/connections` | 없음 | `{connections[], blockedReason}`. 그 에이전트에서 본 내 연결이다. 그 에이전트의 주인만 읽는다 |
| `PUT /api/v1/agents/{code}/connections/{connectorId}` | 없음 | 붙인다. 이미 붙어 있으면 지금 상태를 돌려준다. 결과는 연결 한 줄이다 |
| `DELETE /api/v1/agents/{code}/connections/{connectorId}` | 없음 | 뗀다. 204. 붙어 있지 않으면 아무것도 하지 않는다 |
| `GET /api/v1/admin/connections` | 없음 | 같은 그룹 사용자의 바인딩 목록. ADMIN 전용 |
| `POST /api/v1/admin/agents/{code}/connections/{connectorId}/confirm` | `{restartRequiredSince}` | 바인딩 하나의 반영 완료. 본문은 관리자 목록에서 본 그 바인딩의 값이다. ADMIN 전용 |

- 상태 응답은 `connectorId`, `status`, `secretPrefixes{key: 앞 4자}`, `values{key: 값}`, `checkedAt`, `bindings[]`, `undeclaredTools` 를 갖는다. 등록 전에는 `DISCONNECTED` 이고 나머지는 비거나 null 이다
- `bindings[]` 의 항목은 `agentCode`, `agentName`, `status`, `restartRequired` 다. `status` 는 바인딩 상태다. 재시작 대기는 연결이 아니라 바인딩마다 있다
- 에이전트의 연결 목록 항목은 `connectorId`, `title`, `connectionStatus`, `bound`, `status`, `restartRequired`, `toolCount`, `skills` 다. `status` 는 붙어 있지 않으면 null 이다. `toolCount` 는 커넥터가 선언한 도구 수이고 `skills` 는 붙이면 그 profile 에 설치되는 스킬 이름이다. 해제한 연결은 목록에서 빠진다. env 이름, 보관 파일 이름, 서버 이름은 담지 않는다
- `blockedReason` 은 붙일 수 없는 까닭이다. 그룹에 공개된 에이전트는 `AGENT_NOT_PRIVATE`, 옛 커넥터 에이전트는 `LEGACY_AGENT` 이고, 붙일 수 있으면 null 이다
- 관리자 목록 항목은 `connectorId`, `userId`, `displayName`, `agentCode`, `status`, `restartRequired`, `restartRequiredSince`, `undeclaredTools` 만 담는다. `status` 는 바인딩 상태다. 다른 사용자의 칸 값과 비밀 앞부분은 넣지 않는다
- 모르는 `id` 는 `CONNECTOR_NOT_FOUND`(404) 다. 운영 목록에서 빠진 커넥터의 기존 연결은 읽기와 해제만 된다
- 목록의 `available` 은 그 커넥터가 지금 카탈로그에 있는지다. 카탈로그에서 빠졌지만 내 연결이 `DISCONNECTED` 가 아닌 커넥터는 `available: false`, 빈 `fields`, 빈 `description`, null `icon` 과 `link` 로 함께 낸다. 이때 `title` 은 커넥터 번호다. 화면이 해제하러 들어갈 길을 남기기 위해서다
- `values` 의 키는 그 커넥터의 `fields[].key` 만 받는다. 모르는 키, 필수 칸 누락, `pattern` 불일치는 `VALIDATION_FAILED` 다. 비밀이 아닌 칸의 값은 500자까지, 비밀 칸의 값은 4096자까지다. 저장할 칸 값 전체가 `fields` 열에 들어가지 않아도 같은 오류다. 외부에 반영하기 전에 검사한다
- 선택지와 확인은 후보 값을 저장하지 않고 응답에 되돌려 담지 않는다
- 외부 설치, 확인, 해제가 실패하면 `CONNECTOR_OPERATION_FAILED`(502) 다
- 카탈로그에 있는 커넥터의 연결 확인에서 보관 파일에 값이 없고 옮겨 올 옛 커넥터 에이전트의 바인딩도 없으면 연결을 `PENDING` 으로 두고 `CONNECTOR_NOT_CONNECTED`(409) 다. 값을 다시 등록해야 한다. 카탈로그에서 빠진 커넥터는 오류 없이 `PENDING` 이다
- 선택지 조회, 등록, 연결 확인은 사용자별 호출 제한을 먼저 지난다. 넘으면 외부를 부르지 않고 `CONNECTOR_RATE_LIMITED`(429) 다
- 연결의 `PENDING` 은 값을 확인하지 못한 상태, `READY` 는 등록이나 연결 확인에서 확인 도구가 통과한 상태다. 연결 상태는 「값이 확인돼 쓸 수 있는가」 만 뜻한다
- 바인딩의 `READY` 는 그 에이전트의 profile 에 바인딩 방식으로 설치가 켜져 있고, 설치가 configured 이며, 정책 hook 이 켜져 있고, MCP probe 에서 도구를 확인했고, 재시작 대기가 아니며, 반영 예정 시각을 기다리는 중이 아닌 상태다. 그 밖에는 `PENDING` 이다. probe 는 공유 gateway 의 실제 실행 확인을 대신하지 않는다
- 커넥터 도구는 연결과 바인딩이 모두 `READY` 일 때만 판정을 통과한다. 바인딩 상태는 에이전트를 켜거나 끄지 않는다. 그 에이전트의 다른 도구는 그대로 돈다

### 붙이기와 떼기

붙이고 떼는 사람은 그 에이전트의 주인이고 자기 연결만 붙인다. 관리자도 남의 에이전트에 붙이거나 떼지 못하고 반영 완료만 누른다. 반영 완료는 재시작 대기인 바인딩과, 반영 예정 확인이 실패해 `PENDING` 으로 남은 바인딩에 쓴다.
읽을 수 없는 에이전트는 `AGENT_NOT_FOUND`(404), 읽을 수 있어도 주인이 아니면 `FORBIDDEN`(403)이다.

| 붙일 때 거절하는 경우 | 오류 |
| --- | --- |
| 그 에이전트가 `PRIVATE` 가 아니다 | `AGENT_CONNECTIONS_REQUIRE_PRIVATE`(409) |
| 옛 커넥터 에이전트다 | `VALIDATION_FAILED`(400) |
| 내 연결이 `READY` 가 아니거나 값이 보관 파일에 없다 | `CONNECTOR_NOT_CONNECTED`(409) |
| 커넥터가 카탈로그에 없다 | `CONNECTOR_NOT_FOUND`(404) |
| 커넥터의 스킬 이름이 그 에이전트의 스킬과 겹친다 | `SKILL_NAME_TAKEN`(409) |
| 커넥터가 `single_binding` 을 선언했고 그 연결이 이미 다른 에이전트에 붙어 있다 | `CONNECTOR_SINGLE_BINDING`(409) |
| 대시보드가 실행 공간이 없다고 거절했다. `sandbox_required` 를 선언한 커넥터인데 그 profile 이 실행 공간 정책에 없는 것, `owner_attachments_env` 를 선언한 커넥터인데 실행 공간 정책이 없거나 주인의 첨부 디렉터리를 확인하지 못한 것이 여기 든다 | `AGENT_SANDBOX_UNAVAILABLE`(409) |
| 대시보드가 그 profile 의 설정이나 이미 붙은 다른 커넥터와 충돌한다고 거절했다. 그 profile 에서 `fos-ctx` 가 꺼져 있는 것도 여기 든다 | `CONNECTOR_BIND_CONFLICT`(409) |
| 그 profile 이 아직 커넥터를 받을 준비가 되지 않았다. 표식이 없다 | `CONNECTOR_PROFILE_NOT_READY`(409) |
| 그 밖의 외부 실패 | `CONNECTOR_OPERATION_FAILED`(502) |

- 대시보드가 거절한 세 경우(`CONNECTOR_BIND_CONFLICT`, `AGENT_SANDBOX_UNAVAILABLE`, `CONNECTOR_PROFILE_NOT_READY`)와 `CONNECTOR_SINGLE_BINDING` 은 대시보드가 아무것도 바꾸지 않았으므로 바인딩 행도 남지 않는다. 그 밖의 외부 실패는 바인딩을 `PENDING` 으로 남긴다. 대시보드가 반쯤 반영했을 수 있어 다음 연결 확인이 설치를 다시 보낸다
- 붙인 바인딩은 늘 `PENDING` 이다. 뗀 서버 기록에 없는 새 이름의 붙이기는 재시작을 기다리지 않는다. 공유 gateway 의 MCP 설정 맞추기 주기가 연결하고, Control Plane 이 150초 뒤 스스로 반영을 확인해 `READY` 로 둔다([ADR-20261007 / connector-live-reload](adr/ADR-20261007-connector-live-reload.md)). 대개 몇 분 안에 쓸 수 있다
- 대시보드가 재시작이 필요하다고 답한 붙이기만 재시작 대기가 된다. 뗀 서버 기록에 남은 이름을 다시 붙이는 것도 여기 해당한다. 관리자가 공유 gateway 를 재시작하고 반영 완료를 누르면 `READY` 가 된다
- 붙이기는 `skills` toolset 을 켜지 않는다. 그 에이전트의 도구는 주인이 정한다([ADR-029](../backend/docs/adr/ADR-029-에이전트-도구는-control-plane-이-등급으로-판정하고-hermes-설정-api-로-쓴다.md)). `skills` 가 꺼진 에이전트는 커넥터의 지침을 읽지 못하고, 화면이 그것을 안내한다
- 떼기는 그 에이전트의 실행이 판정한 `PENDING` 승인 줄을 `REJECTED`(`errorCode: connection_changed`)로 끝낸다. 상시 허락은 사용자와 커넥터에 묶여 같은 연결을 붙인 다른 에이전트에도 걸리므로 그대로 둔다. 그 에이전트의 승인 줄이 `EXECUTING` 이면 `CONNECTOR_ACTION_EXECUTING`(409)으로 거절한다
- 떼기는 도구 목록에서 서버 이름을 빼므로 재시작을 기다리지 않고 다음 실행부터 막힌다. 떼기 전에 시작한 실행이 그 도구를 불러도 판정이 막는다
- 옛 커넥터 에이전트에서는 떼지 않는다. `VALIDATION_FAILED` 로 거절하고, 그 에이전트를 지우면 바인딩이 함께 떨어진다

**연결이 붙은 에이전트는 비공개로 남는다.** 남이 주인의 계정으로 외부 서비스를 쓰지 못하게 하기 위해서다.

| 바인딩이 하나라도 있는 에이전트에 | 결과 |
| --- | --- |
| 주인이나 관리자가 그룹 공개로 바꾼다 | `AGENT_CONNECTIONS_REQUIRE_PRIVATE`(409) |
| 관리자가 주인을 바꾼다 | `AGENT_HAS_CONNECTIONS`(409) |
| 주인이나 관리자가 지운다 | 바인딩을 모두 뗀 뒤 지운다. 떼지 못하면 지우지 않고 그 오류를 돌려준다 |

붙이기, 떼기, 관리자 반영 완료, 공개 범위 변경, 관리자 수정, 지우기가 같은 에이전트에 동시에 와도 한쪽이 다른 쪽의 커밋을 보고 판정한다. 잠금의 차례는 [커넥터 설치](../backend/docs/flow.md) 의 「설치와 실패 처리」 가 갖는다.

### 관리자 반영 완료

재시작 대기인 바인딩에만 필요하다. 값 교체처럼 이미 있던 서버가 바뀐 설치와 `fos-ctx` 갱신이 그렇다. 재시작 없이 반영되는 붙이기는 Control Plane 이 스스로 확인한다. 그 확인이 실패해 `PENDING` 으로 남은 바인딩도 관리자가 반영 완료로 다시 확인할 수 있다.
관리자는 재시작 대기 바인딩이면 공유 gateway 를 재시작한 뒤, 그 밖의 `PENDING` 바인딩이면 바로 관리자 목록에서 반영 완료를 누른다.

- 잠근 뒤 그 에이전트의 주인이 바뀌었으면 `AGENT_BUSY`(409)다
- 그다음 지금 주인이 관리자와 같은 그룹이어야 한다. 아니면 `AGENT_NOT_FOUND`(404)다. 다른 그룹의 에이전트가 있는지 드러내지 않는다
- 바인딩의 재시작 대기 시각이 본문의 `restartRequiredSince` 보다 늦거나 본문이 비었으면 `CONNECTOR_RESTART_AGAIN`(409)으로 거절한다. 관리자가 목록을 본 뒤에 다시 설치된 바인딩이라 재시작한 gateway 가 아직 보지 못했을 수 있기 때문이다. 화면은 성공하든 거절되든 목록을 다시 읽어 바뀐 값을 받는다
- 바인딩에 재시작 대기 시각이 없으면 본문을 보지 않는다. 재시작이 필요 없던 바인딩의 다시 확인이 이 경우다
- 받으면 설치를 한 번 다시 보내 반영됐는지 본다. 그래도 `READY` 가 되지 않으면 `CONNECTOR_OPERATION_FAILED`(502)다

## 승인

결정은 [ADR-050](../backend/docs/adr/ADR-050-커넥터-쓰기는-control-plane-이-승인-줄을-저장하고-승인한-인자로-한-번만-실행한다.md) 에 있다.

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
- 응답의 `argsJson` 은 사용자가 읽고 승인하는 글이다. 비밀처럼 보이는 키의 값과 토큰 모양의 글은 `[가림]` 으로 바꿔 낸다([ADR-047](../backend/docs/adr/ADR-047-도구-내용은-비밀값과-UUID를-가린-뒤-중계하고-저장한다.md) 의 규칙 가운데 비밀값 부분). UUID 는 가리지 않는다. 무엇을 고치는지 가리키는 값이라 가리면 서로 다른 대상의 요청이 같게 보이고, 이 응답은 주인만 읽는다. 길이로 자르지 않는다. 실행은 저장한 원문으로 한다
- 커넥터가 `identifiers` 로 선언한 맨 위 인자는 값이 문자열이거나 문자열 배열이고 문자열마다 `^[A-Za-z0-9_-]{1,256}$` 일 때 32자 이상의 덩어리를 가리는 규칙에서 빠진다([ADR-089](adr/ADR-089-커넥터는-식별자-인자를-선언하고-승인-카드는-그-값을-길이로-가리지-않는다.md)). 알려진 비밀 접두사(`sk-`, `ghp_` 와 같은 무리, `github_pat_`, `xox?-`)로 시작하는 값과 비밀 키 이름의 칸은 그래도 가린다. 중첩된 칸과 모양이 다른 값은 지금처럼 가린다. 선언은 줄을 읽을 때와 승인할 때의 카탈로그로 보고, 카탈로그를 읽지 못했으면 선언이 없는 것으로 본다
- 상시 허락 목록은 지금 선언이 상시 허락을 닫은 도구의 줄을 내지 않는다. 판정이 그 줄을 보지 않아 효력이 없기 때문이다. 그런 줄은 1분마다 도는 정리가 거둔다. 거둔 줄은 선언이 다시 열려도 되살아나지 않는다. 카탈로그를 읽지 못했으면 낸다
- 상시 허락 응답은 `grantId`, `connectorId`, `toolName`, `title`, `expiresAt` 을 갖는다. `title` 은 카탈로그가 선언한 이름이다
- `title` 은 선언에 이름이 없거나 카탈로그를 읽지 못했으면 고정 문구 `이름 없는 동작` 이다. 도구의 원래 이름은 내부 값이라 `title` 과 알림 줄과 모델 입력에 싣지 않는다
- **상시 허락을 닫은 도구의 승인 줄은 인자가 하나도 가려지지 않아야 승인된다**([ADR-065](adr/ADR-065-외부로-나가는-도구는-상시-허락을-닫는-선언을-둔다.md)). 그 도구는 밖으로 나가는 호출이라 사람이 원문을 다 읽어야 한다. 가린 글이 저장한 원문과 다르면 `hiddenArgs` 가 참이다. 화면은 가려진 맨 위 칸의 이름과, 거절한 뒤 에이전트에게 그 부분을 빼게 하거나 식별자라면 관리자에게 알리라는 안내를 보이고 「승인」 을 막는다. 그 줄에 승인 요청이 오면 실행하지 않고 `REJECTED`(`errorCode: hidden_args`)로 끝낸 줄을 200 으로 돌려준다. 상시 허락을 줄 수 있는 도구와 `PENDING` 이 아닌 줄은 `hiddenArgs` 가 거짓이다. 선언은 `grantAllowed` 와 같이 줄을 읽을 때의 카탈로그로 본다. 카탈로그를 읽지 못했으면 그 도구가 상시 허락을 닫았는지 알 수 없으므로 거짓이다. 그 줄에 승인 요청이 오면 정책을 판정하지 못해 `not_executable` 로 끝난다
- 화면은 `toolName`, `actionId`, `errorCode` 를 그리지 않는다. 이름은 `title` 로, 인자는 키와 값으로 보인다. web 의 서버 라우트는 `resultText` 를 브라우저로 옮기지 않는다
- 그 줄의 `user_id` 가 로그인 사용자와 다르면 `CONNECTOR_ACTION_NOT_FOUND`(404) 다. 관리자도 같다
- 요청이 왔을 때 이미 `PENDING` 이 아니던 줄의 승인과 거절은 `CONNECTOR_ACTION_NOT_PENDING`(409) 다. 같은 승인을 두 번 눌러도 실행은 한 번이다
- 승인은 받았으나 실행할 수 없어 그 요청이 끝낸 줄은 오류가 아니라 200 과 끝난 줄을 돌려준다. 기다리는 시간이 지났으면 `EXPIRED`, 연결이나 그 줄의 에이전트에 붙은 바인딩이 `READY` 가 아니거나, 그 에이전트에서 연결을 뗐거나, 정책이 바뀌었으면 `REJECTED` 와 `errorCode: not_executable` 이다. 부른 쪽은 「이미 처리된 요청」(409)과 「승인했지만 실행하지 않은 요청」(200 의 `status`)을 구분한다
- 승인은 행 잠금 아래에서 `EXECUTING` 으로 바꾸고 커밋한 뒤, 트랜잭션 밖에서 대시보드의 실행 경로를 부른다. 연결과 그 줄의 에이전트에 붙은 바인딩이 모두 `READY` 가 아니면 실행하지 않고 `REJECTED` 로 둔다. 승인은 그 사용자의 행을 먼저 잠그고 승인 줄을 잠근다. 연결을 다시 등록하거나 해제하는 쪽, 붙이고 떼는 쪽과 같은 순서다. 그래서 승인이 본 바인딩은 커밋할 때까지 떼어지지 않는다
- 실행 요청이 시간 안에 답하지 않았거나 연결이 끊겼으면 `UNKNOWN` 이다
- `grant` 는 `approval: required` 이고 선언이 상시 허락을 닫지 않은 도구에만 받는다. `always` 이거나, 선언이 `"grant": false` 이거나, `tool_name` 이 빈 줄이면 `VALIDATION_FAILED` 다. 승인 줄의 `grantAllowed` 도 같은 조건으로 낸다. 선언은 승인 줄을 읽을 때의 카탈로그로 보고, 카탈로그를 읽지 못하면 거짓이다([ADR-065](adr/ADR-065-외부로-나가는-도구는-상시-허락을-닫는-선언을-둔다.md)). `TODAY` 는 `Asia/Seoul` 의 그날 끝(다음 날 0시)까지다. 서버의 시간대 설정과 상관없다. 사용량 화면의 달 경계와 같은 고정 시간대다
- 같은 실행에서 같은 도구와 같은 `args_sha256` 의 `PENDING` 이 이미 있으면 새 줄을 만들지 않고 그 번호를 돌려준다
- 사건은 줄을 커밋한 뒤에 낸다. 화면이 사건을 받고 읽었을 때 줄이 있어야 한다
- 대화가 있는 새 승인 줄이면 같은 트랜잭션에서 `APPROVAL_REQUESTED` 알림을 만든다([`backend/docs/flow.md`](../backend/docs/flow.md))
- 대화에는 `approval` 사건을 낸다. 사건은 승인 요청 번호만 싣고 줄의 내용을 싣지 않는다. 화면은 그 사건을 받으면 승인 줄을 다시 읽는다. 승인 카드는 이 응답으로만 그린다. 깨우기(`assistant.delegation-wake.enabled`)가 꺼져 있어도 이 사건과 아래의 거절, 만료 알림 줄은 나간다
- 결과가 `SUCCEEDED`, `FAILED`, `UNKNOWN` 이면 그 대화에 알림 줄을 남기고 자동 turn 을 열어 결과를 전한다. 위임 결과와 같은 잠금과 같은 연속 상한을 쓴다([ADR-040](../backend/docs/adr/ADR-040-위임-결과는-control-plane-이-부모-대화의-다음-turn-을-열어-전한다.md)). 위임 결과가 함께 있으면 한 turn 에 모아 전한다. 사건은 다음 turn 을 정하는 자리를 지나므로 보낼 대기 메시지가 있으면 그것이 먼저 간다([대기열과 중지](../backend/docs/flow.md) 의 「응답 중 대기열」)
- 알림 줄은 결과마다 한 줄이고 이름과 상태만 쓴다. 결과 본문은 모델 입력에만 넣고 `<external-data>` 로 감싼다. `FAILED` 는 본문을 싣지 않는다. 저장한 오류 계약이 있으면 머리줄에 `오류 코드` 와 `세부` 를 더하고 다음 줄에 `복구: <안내>` 를 붙인다(「오류 복구 계약」). 입력의 머리줄은 `[출처: 승인한 동작, 동작: <title>, 상태: <상태>, 끝난 시각: <시각>]` 이고, 오래된 결과에는 신선도와 안내 한 줄이 붙는다. 형식은 [`backend/docs/flow.md`](../backend/docs/flow.md) 의 「Hermes 에 넘기는 형식」 이 갖는다. 요청 번호와 도구의 원래 이름은 모델이 답에 옮겨 화면에 나오지 않게 입력에 싣지 않는다. `UNKNOWN` 은 본문 대신 다시 실행하지 말고 사용자에게 확인을 부탁하라는 글을 넣는다
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
| `{profile, hermes_tool, args}` | `{ok: true, result}` 또는 `{ok: false, error: <공통 어휘>, code?, recovery?, details?}` |

- Control Plane 은 승인 줄의 에이전트(판정한 실행의 에이전트)에 붙은 바인딩의 profile 로 보낸다([ADR-083](adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md))
- 그 profile 에 그 커넥터의 소유 기록이 있어야 한다. 관리 표식이 있거나, 커넥터 표식만 있으면 그 항목이 바인딩 설치여야 한다
- 커넥터 MCP 서버를 자식으로 한 번 띄워 `tools/list` 를 읽고, 등록 이름이 `hermes_tool` 과 같은 도구가 정확히 하나일 때 그 도구를 `args` 로 부른다. `schema: 2` 는 그 도구가 `tools` 에 있어야 한다
- 자식의 env 는 그 profile `.env` 에서 manifest 의 `fields[].env` 만 꺼내고 운영 목록의 `env` 를 더한다. `owner_attachments_env` 를 선언한 커넥터는 설치한 서버 정의의 그 값을 더한다. `owner_output_env` 는 빈 값이다. 승인한 쓰기는 파일을 내지 않는다. `owner_browser_env` 를 선언한 커넥터는 설치한 서버 정의의 중계 주소를 더하고, 그 값이 중계 주소 모양이 아니면 빈 값이다. 나머지 값은 넘기지 않는다
- 시간 제한은 60초, 동시 실행은 `call` 과 같은 한도를 함께 쓴다
- 도구가 `errors` 표에 있는 코드로 실패하면 `code` 에 그 코드를 싣는다. 객체 항목이면 `recovery` 와 `details` 도 싣는다(「오류 복구 계약」). Control Plane 은 같은 규칙으로 다시 검증해 `FAILED` 줄의 `result_text` 에 `{"kind": "connector_error", "code", "details", "recovery"}` 로 저장한다
- 도구가 `errors` 표에서 `outcome_unknown` 인 코드로 실패하면 504 로 답한다. 시간 초과와 같이 실행됐는지 모른다는 뜻이다
- 대시보드는 승인 여부를 다시 확인하지 않는다. Control Plane 이 승인한 줄로만 부른다
- 결과 본문은 Control Plane 으로 돌려주되 로그에 싣지 않는다
- 도구가 오류 없이 끝났는데 구조화 결과도 JSON 텍스트도 없으면 `{ok: true, result: {text: <첫 텍스트 칸의 글, 없으면 빈 글>}}` 로 답한다. 실행된 쓰기를 실패로 기록하지 않기 위해서다. 오류로 끝났는데 읽지 못한 결과는 `{ok: false, error: "unavailable"}` 다
- 커넥터의 도구 하나는 프로세스 안의 상태에 기대지 않아야 한다. 이 경로는 Hermes 가 쥔 MCP 연결이 아니라 새 프로세스에서 돈다

## 저장과 비밀값

`connector_connection` 은 사용자, 커넥터, 상태, 칸 값, 마지막 확인 시각, 값을 보관 파일에 두었는지, 선언하지 않은 도구 수를 저장한다.
에이전트에 붙인 것은 `agent_connector_binding` 이 바인딩마다 상태와 재시작 대기를 저장한다([`backend/docs/data-schema.md`](../backend/docs/data-schema.md)).
비밀 칸은 원문과 해시를 저장하지 않고, 값이 충분히 길 때만 앞부분을 남긴다. 길이 기준과 저장 칸은 [`backend/docs/data-schema.md`](../backend/docs/data-schema.md) 의 「connector_connection」 이 갖는다.
화면은 연결된 상태에서 앞부분이 없는 필수 비밀 칸을 「입력됨」 으로만 보인다. 앞부분이 없는 선택 비밀 칸은 입력 여부를 응답으로 알 수 없어 보이지 않는다.
브라우저는 등록을 제출한 직후 비밀 칸 입력을 비우고 다시 표시하지 않는다. 선택지를 고르는 동안은 작성 중인 입력을 쓰고, 조회가 실패해도 입력을 비운다.
요청 record 의 문자열 표현, 외부 오류, 로그와 응답에 비밀 원문을 남기지 않는다.
`connector_action` 은 도구 호출마다의 판정과 승인 줄을 저장한다. 인자 원문은 승인 줄에만 두고 주인에게만 보인다. 관리자 목록과 로그에는 싣지 않는다.

**칸 값의 원문은 Hermes 쪽 두 곳에만 있다.**

| 어디 | 무엇 | 언제 지워지는가 |
| --- | --- | --- |
| 보관 파일 | 연결마다 하나인 원본. 대시보드 plugin 이 둔다 | 연결을 해제할 때 |
| 붙인 에이전트 profile 의 `.env` | 붙일 때 보관 파일에서 복사한 값. 서버 정의의 `env` 는 `${이름}` 참조만 갖고 그 profile 의 secret scope 에서 풀린다 | 그 에이전트에서 뗄 때. 연결 해제는 붙은 바인딩을 모두 떼므로 함께 지워진다 |

값을 바꾸면 보관 파일을 다시 쓰고 붙은 profile 마다 복사본을 다시 쓴다. 떠 있는 MCP 프로세스는 옛 값을 쥐고 있어 그 바인딩들이 재시작 대기가 된다.
설정 백업은 `.env` 와 보관 파일을 담지 않는다. 그래서 떼거나 해제한 뒤에는 그 profile 디렉터리의 어느 파일에도 칸 값의 원문이 남지 않는다.
옛 커넥터 에이전트는 남아 있는 동안 자기 profile 의 `.env` 에 값을 갖는다. 연결 확인이 그 값을 보관 파일로 옮기고, 그 에이전트를 지우면 그 profile 이 거둬진다.
