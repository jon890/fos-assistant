# 대화와 실행 사건

`chat` 패키지가 갖는 대화와 메시지의 경로, 대화의 모델 선택, 화면으로 보내는 사건을 갖는다.
Hermes 사건을 `execution_event` 로 옮겨 적는 규칙과 실행 나무를 잇는 방법, 도구 내용을 가리는 규칙도 이 파일이 갖는다.

## 대화

`chat` 패키지가 대화와 메시지를 갖는다.
한 번의 대화가 지나는 길은 [`backend/packages.md`](packages.md) 의 「한 번의 대화가 지나는 길」 절이 갖는다.
화면 흐름은 [`frontend/shell.md`](../frontend/shell.md), [`frontend/chat.md`](../frontend/chat.md), [`frontend/activity.md`](../frontend/activity.md) 가 나눠 갖는다.

### 경로

| 경로 | 하는 일 |
| --- | --- |
| `GET /api/v1/chat/conversations?cursor=&limit=` | 내 대화 목록의 한 쪽. `{ "items": [...], "nextCursor": "..." }`. 지운 대화는 빠진다. 정렬은 `updatedAt desc, id desc`, `limit` 기본 30 상한 100. `nextCursor` 는 뜻을 알 수 없는 문자열이고 다음 쪽의 `cursor` 로 그대로 넘긴다. 마지막 쪽이면 null 이다. 읽을 수 없는 `cursor` 는 `VALIDATION_FAILED` 다 |
| `GET /api/v1/chat/conversations/{id}` | 대화 한 줄. 첫 쪽에 없는 오래된 대화를 열 때 모델 칸과 에이전트 칸이 쓴다 |
| `PATCH /api/v1/chat/conversations/{id}` | 이름을 바꾼다. 본문 `{ "title": "..." }`. 바뀐 대화 한 줄을 돌려준다 |
| `PUT /api/v1/chat/conversations/{id}/model` | 대화의 모델과 effort 를 바꾼다. 본문 `{ "provider", "model", "reasoningEffort" }`. 셋 다 null 이면 기본값으로 되돌린다. effort 는 `ModelChoice` 가 받는 값(`none`, `low` 부터 `max`)이어야 하고, `none` 은 그 모델의 `disable` 이 `SUPPORTED` 일 때만 받는다. 모델을 비웠으면 에이전트 기본 모델로 판정한다. 아니면 `VALIDATION_FAILED` 다. `none` 일 때만 대화의 에이전트와 그 목록을 읽으므로 목록을 읽지 못하면 `HERMES_UNAVAILABLE`, 에이전트를 쓸 수 없으면 그 오류가 난다. 다른 effort 의 저장은 에이전트 상태와 무관하다. 판정은 저장할 때만 한다. 저장한 뒤 에이전트 기본 모델이나 Hermes 의 지원 값이 바뀌어도 실행은 저장된 `none` 을 그대로 보내고, 실행 때 목록을 읽지 않는다. 바뀐 대화 한 줄을 돌려준다 |
| `GET /api/v1/chat/model-options?agentCode=` | 그 에이전트의 profile 로 고를 수 있는 모델. 요청자가 쓸 수 있는 에이전트만 받는다 |
| `DELETE /api/v1/chat/conversations/{id}` | 목록에서 숨긴다. 204 |
| `GET /api/v1/chat/conversations/{id}/messages` | 메시지 목록. 이전 판도 모두 온다 |
| `POST /api/v1/chat/messages` | 한 번에 받는다 |
| `POST /api/v1/chat/messages/stream` | 사건으로 받는다 |
| `POST /api/v1/chat/conversations/{id}/regenerate/stream` | 마지막 답을 다시 만든다. 본문이 없다 |
| `POST /api/v1/chat/executions/{id}/stop` | 돌고 있는 실행을 멈춘다. 202 와 `{ "status": "stopping" }` |
| `GET /api/v1/chat/conversations/{id}/events` | 대화 단위 SSE. 끝나지 않는다. 요청한 연결이 없는 turn(위임 결과로 열린 자동 turn, 대기 메시지로 연 turn)의 사건과 `system`, `user`, `pending` 사건을 싣는다. 연결 직후 `: connected` 주석 줄을 보내 사건이 없어도 응답 헤더가 바로 나가고, 20초마다 `: ping` 을 보낸다 |
| `GET /api/v1/chat/conversations/{id}/pending` | 대기 줄. `{ "held", "items": [{ "id", "text", "createdAt" }] }`. 없으면 `held` 가 false 이고 `items` 가 빈 목록이다 |
| `POST /api/v1/chat/conversations/{id}/pending` | 대기 메시지를 더한다. 본문 `{ "text": "..." }`. 201 과 대기 줄을 돌려준다. 도는 turn 이 없으면 곧바로 보낸다 |
| `DELETE /api/v1/chat/conversations/{id}/pending/{pendingId}` | 대기 메시지 하나를 취소한다. 204. 이미 보내졌거나 없으면 `PENDING_MESSAGE_NOT_FOUND` |
| `POST /api/v1/chat/conversations/{id}/pending/send` | 멈춰 둔 대기 줄을 풀어 보낸다. 본문이 없다. 202 와 대기 줄을 돌려준다 |
| `GET /api/v1/chat/conversations/{id}/running` | 이 대화에 지금 도는 turn. `{ "running", "executionId", "startedAt" }`. 돌지 않으면 `running` 이 false 이고 나머지는 null |
| `GET /api/v1/chat/conversations/by-number/{number}` | 옛 주소 `/c/{번호}` 를 넘겨 주려고 번호로 대화를 찾는다. `{ "id": "<공개 식별자>" }` |

지운 대화와 남의 대화는 모든 경로에서 `CONVERSATION_NOT_FOUND` 다. 둘을 가리지 않는다.

### 대화의 모델 선택

대화 한 줄(`ConversationView`)은 `provider`, `model`, `reasoningEffort` 를 싣는다. 고르지 않았으면 셋 다 null 이다.

`GET /api/v1/chat/model-options` 의 응답이다.

| 칸 | 뜻 |
| --- | --- |
| `defaultProvider`, `defaultModel` | 고르지 않았을 때 도는 값. 에이전트 기본 모델이 있으면 그 값이고 없으면 그 profile 의 값이다. Hermes 가 주지 않으면 null |
| `defaultReasoningEffort` | 에이전트 기본 effort. 정하지 않았으면 null |
| `defaultFromAgent` | 기본 모델을 에이전트 기본값이 정했으면 참 |
| `defaultAvailable` | 기본 모델이 `providers[]` 에 있으면 참. 그룹이 숨겼거나 목록에서 빠졌으면 거짓이고, 화면이 다른 모델을 고르라고 알린다 |
| `providers[]` | `{ "provider", "name", "models": [...], "reasoning": {...} }`. Hermes 가 `authenticated` 를 참으로 준 provider 만 남기고, 그룹이 숨긴 provider 와 모델을 뺀다. 기본 provider 가 맨 앞에 오고, 모델은 Hermes 가 준 차례 그대로다 |
| `providers[].reasoning` | 모델 이름을 열쇠로 한 표. 값은 `{ "support", "disable" }` 이고 각각 `SUPPORTED`, `UNSUPPORTED`, `UNKNOWN` 이다. 모든 모델이 표에 있다 |
| `reasoning.<모델>.support` | Hermes 의 `capabilities.<모델>.reasoning` 이 참이면 `SUPPORTED`, 거짓이면 `UNSUPPORTED`, 칸이 없으면 `UNKNOWN` 이다. 우리가 없는 값을 채우지 않는다. 화면은 `UNSUPPORTED` 인 모델에서 effort 를 고르지 못하게 하고, `UNKNOWN` 인 모델은 고르게 두되 지원 미확인으로 알린다 |
| `reasoning.<모델>.disable` | reasoning 끄기(`none`)를 받는가. Hermes 의 `can_disable_reasoning` 이 참이면 `SUPPORTED`, 거짓이면 `UNSUPPORTED`, 칸이 없으면 `UNKNOWN` 이다. `SUPPORTED` 이고 `support` 가 `UNSUPPORTED` 가 아닌 모델에서만 `none` 을 고를 수 있다 |
| `providers[].reasoningCapable` | 옛 web 호환용이다. 모델 이름을 열쇠로 한 참거짓 표이고, `support` 가 `UNSUPPORTED` 가 아니면 참이다. backend 와 web 이 같은 순간에 배포된다고 확인하지 못해 이번 배포에서 남기고 다음 배포에서 지운다. 새 화면은 `reasoning` 을 읽는다 |
| `reasoningEfforts` | `["low", "medium", "high", "xhigh", "max"]`. 고정이다. `none` 은 여기 없고 모델마다 `disable` 이 정한다. `minimal` 은 지원을 확인할 신호가 없어 어디에도 없다 |

`hermes/HermesModelClient` 가 `GET {profile}/api/model/options` 를 부르고, `chat/application/ModelOptionsService` 가 profile 마다 10분 들고 있는다.
10분이 지나 다시 읽다 Hermes 가 답하지 못하면 들고 있던 옛 목록을 돌려준다. 그 profile 의 목록을 한 번도 읽지 못했으면 `HERMES_UNAVAILABLE` 이다.

web 은 입력창 아래의 `chat/model-picker.tsx` 로 고른다.
목록은 창을 열 때만 서버 라우트 `GET /api/chat/model-options` 로 읽는다. 그래서 창을 열기 전 단추는 대화에 적힌 값만 보인다.
고른 값은 `PUT /api/chat/conversations/{id}/model` 로 저장하고, 그 응답의 대화 한 줄로 대화 목록의 그 줄만 바꾼다(`conversations-provider` 의 `replace`).
**저장보다 먼저 나간 목록 다시 읽기의 응답은 버린다.** 새 대화에서 고르면 빈 대화를 만들며 목록을 다시 읽는 요청과 저장이 함께 나가, 늦게 온 목록이 저장한 값을 덮을 수 있기 때문이다.
`replace` 는 한 줄만 바꾸므로, 버린 응답이 있으면 목록을 한 번 더 읽어 다른 줄의 제목과 순서를 맞춘다.
기존 대화는 목록에 그 줄이 오기 전까지 단추를 막는다(`Composer` 의 `modelChoiceUnknown`). 적힌 모델을 모르는 채 저장하면 그 모델을 지우기 때문이다.
목록을 저장하지 않는 까닭은
[ADR-030](../adr/ADR-030-모델과-effort-는-대화가-고르고-기본값은-hermes-profile-이-갖는다.md) 에 있다.
기본 모델과 숨김을 DB 에 두는 까닭은
[ADR-054](../adr/ADR-054-에이전트-기본-모델과-모델-숨김은-control-plane-db-가-갖는다.md) 에 있다.

실행을 보낼 때 `chat/application/ModelTierService` 가 대화의 선택, 단계, 에이전트 기본 모델 차례로 세 값을 정하고 `chat/domain/ModelChoice` 에 담는다.
모델이 비어 있으면 `/v1/runs` 에 `provider`, `model` 을 빼고,
effort 가 비어 있으면 `model_options` 를 뺀다. 비어 있음(미지정)과 `none`(reasoning 끔)은 다른 의도라, `none` 은 `model_options.reasoning.effort` 에 그대로 싣는다. 정한 모델이 숨긴 모델이면 제출하지 않고 `MODEL_HIDDEN` 으로 실패시킨다.
모델이 비어 있고 그룹에 숨김이 있으면 `ModelOptionsService.profileDefaultOf` 가 들고 있는 목록에서 읽은 profile 의 기본 모델로 같은 판정을 한다.
Memory 제안은 원래 실행이 해석한 값을 받아 쓰고 `ExecutionRecorder.startInheriting` 으로 원래 실행의 단계와 effort 출처를 이어받는다.
추천 질문은 대화가 없어 `ModelTierService.detachedChoice` 가 준 에이전트 기본 모델을 싣고 `ExecutionRecorder.startDetached` 가 그 값을 실행 줄에 적는다. 같은 값을 `ModelTierService.requireRunnable` 이 숨김과 견준다.
보내기, 다시 생성, 흐름의 하위 실행이 모두 같은 해석을 쓴다.

**대화 경로의 `{id}` 는 대화의 공개 식별자(UUID)다.** 대화 표의 번호가 아니다.
응답에서 대화를 가리키는 칸도 모두 공개 식별자다. 대화 목록의 `id`, 보내기 응답과 사건의 `conversationId`,
실행 목록의 `conversationId` 가 여기 해당한다. 보내기 요청의 `conversationId` 도 공개 식별자를 받는다.
메시지, 첨부, 실행의 번호는 그대로 숫자다.

컨트롤러가 `ConversationAccess.requireOwnId(user, publicId)` 로 주인을 확인하며 번호로 바꾸고,
`application` 안쪽은 지금처럼 번호를 쓴다. 사건과 응답에 싣는 공개 식별자는 `Conversation.publicId()` 에서 읽는다.
UUID 모양이 아닌 `{id}` 는 400 `VALIDATION_FAILED` 다. [`packages.md`](packages.md) 의 형식 오류 규칙이 모든 경로에 걸린다.

`by-number` 경로는 옛 링크가 쓰이지 않게 되면 지운다.
근거는 [ADR-025](../adr/ADR-025-대화는-주소에-공개-식별자를-쓰고-번호는-안에만-둔다.md)에 있다.

**turn 이 끝날 때 대화를 통째로 다시 저장하지 않는다.**
요청 시작에 읽은 `Conversation` 을 끝에서 통째로 저장하면, 그 사이에 사용자가 이름을 바꾸거나 지웠을 때
옛 값으로 덮여 지운 대화가 되살아난다.
turn 이 바꾸는 칸은 `hermes_session_id` 와 `updated_at` 뿐이므로 그 둘만 고치는 질의로 쓴다.
새 대화의 첫 turn 은 시작할 때 `hermes_session_id` 와 `hermes_root_session_id` 를 비어 있을 때만 채우는 질의로 쓴다. 이 질의는 `updated_at` 을 바꾸지 않는다. 바꾸면 실패한 turn 도 대화를 목록 맨 위로 올린다.
남의 실행과 없는 실행은 `EXECUTION_NOT_FOUND` 다.

### 메시지 한 줄

`GET .../messages` 가 주는 한 줄의 칸 가운데 뜻을 따로 적어 둘 것이다.

| 칸 | 뜻 |
| --- | --- |
| `status` | 답을 만든 실행의 상태. `SUCCEEDED`, `FAILED`, `CANCELLED`, `RUNNING` 중 하나. 사용자 메시지는 null |
| `replacesMessageId` | 이 메시지가 새 판으로 대신하는 이전 메시지. 없으면 null |
| `activity` | 작업 과정의 요약. `{ toolCount, subagentCount, durationMs }`. 사건이 없는 답과 사용자 메시지는 null |
| `artifacts` | 그 답의 turn 이 만든 결과물 파일. [`artifact.md`](artifact.md) 의 「메시지 한 줄의 `artifacts`」 절이 모양을 갖는다 |

`activity` 는 답을 만든 실행과 그 아래 자식 실행의 `execution_event` 를 모두 센다.
예전에 provider 가 막혀 다음 모델로 넘어간 turn 은 막힌 시도가 따로 실행 줄을 갖는다. 요약은 답을 만든 실행만 세고 막힌 시도의 사건은 넣지 않는다. 지금은 Control Plane 이 provider 를 넘기지 않아 새 turn 에는 이런 줄이 생기지 않는다.

| 칸 | 세는 것 |
| --- | --- |
| `toolCount` | `TOOL_STARTED` 수와 `TOOL_COMPLETED` 수 가운데 큰 값. 한쪽이 빠져 와도 줄지 않게 한다 |
| `subagentCount` | `SUBAGENT_STARTED` 사건 수와 자식 실행 수를 더한 값. Memory 제안 실행은 세지 않는다 |
| `durationMs` | 뿌리가 시작한 때부터 나무에서 가장 늦게 끝난 실행이 끝난 때까지. 흐름은 Chief 가 끝난 뒤에도 돈다 |

흐름으로 돈 답은 하위 에이전트 사건 없이 자식 실행만 남는다. 자식 실행을 더하지 않으면 요약이 0 이 되고 블록이 사라진다.
한 하위 에이전트가 사건과 자식 실행 둘 다 남기면 두 번 센다.
그 겹침은 [`frontend/activity.md`](../frontend/activity.md) 의 「실행 하나를 다시 볼 때」 절에서 나무가 이미 받아들인 것과 같다.
대화 하나를 열 때 질의가 답 수만큼 늘지 않도록, 실행 번호 목록으로 한 번에 센다.

### 화면으로 보내는 사건

스트리밍 경로가 보내는 `ChatEvent` 의 `type` 이다.
화면은 이 값만 안다. Hermes 의 원래 사건 이름을 읽지 않는다.

| `type` | 언제 | 싣는 칸 |
| --- | --- | --- |
| `started` | 실행 줄을 만든 직후 | `conversationId`, `executionId` |
| `delta` | 답 조각 | `text` |
| `tool` | 도구가 시작되거나 끝났다 | `toolName`, `detail`, `phase`, `durationMs`, `failed`. `detail` 은 아래 「도구 `detail` 을 싣는 대상」 을 따른다 |
| `subagent` | 하위 에이전트가 시작되거나 끝났다 | `subagentId`, `goal`, `model`, `phase`, `inputTokens`, `outputTokens`, `durationMs`, `failed` |
| `step` | 흐름의 단계가 시작되거나 끝났다 | `stepName`, `stepState` |
| `reset` | 지금까지 흘린 조각을 지우라. provider 를 넘기던 때만 보냈고 지금은 보내지 않는다. 화면은 아직 받는 쪽을 갖고 있다 | |
| `done` | 끝나서 저장했다 | `conversationId`, `messageId`, `executionId` |
| `stopped` | 중지로 끝나서 저장했다 | `conversationId`, `messageId`, `executionId`. 남긴 답이 없으면 `messageId` 가 null |
| `error` | 실패했다 | `code`, `message` |
| `system` | 알림 줄을 저장했다. 대화 단위 SSE 로만 간다 | `conversationId`, `messageId`, `text` |
| `user` | 대기 메시지를 합쳐 사용자 메시지로 저장했다. 대화 단위 SSE 로만 간다 | `conversationId`, `messageId`, `text` |
| `pending` | 대기 줄이 바뀌었다. 화면이 대기 줄을 다시 읽는다. 대화 단위 SSE 로만 간다 | `conversationId` |
| `approval` | 그 대화의 승인 줄이 생겼거나 상태가 바뀌었다. 화면이 승인 줄을 다시 읽는다. 줄의 내용은 싣지 않는다. 대화 단위 SSE 로만 간다 | `conversationId`, `detail`(승인 요청 번호) |

`phase` 는 `started` 와 `completed` 둘이다.
`subagent` 의 칸은 Hermes 가 실어 보낼 때만 찬다. `goal` 이 비어 오면 Hermes 의 `preview` 를 그 자리에 싣는다.

**`started` 는 흐름으로 도는 turn 에서도 뿌리 실행의 번호를 싣는다.**
중지는 뿌리 번호로 보내고 Control Plane 이 그 아래를 찾아 멈춘다.
화면은 마지막으로 받은 `started` 의 번호를 쓴다.

#### 도구 `detail` 을 싣는 대상

**도구의 명령 원문은 `ADMIN` 역할에게만 보낸다.** 근거는 [ADR-038](../adr/ADR-038-도구의-명령-원문은-관리자에게만-보내고-사용자에게는-사람-말로-보인다.md) 에 있다.
저장은 그대로 하고 응답을 만들 때 뺀다.

| 받는 사람 | 도구 | `detail` |
| --- | --- | --- |
| `ADMIN` 역할 | 모든 도구 | 저장된 값 |
| `MEMBER` 역할 | `web_search`, `vision_analyze` | 저장된 값 |
| `MEMBER` 역할 | 그 밖의 도구 | `null` |

두 경로가 같은 판정을 쓴다. `usage/application` 의 `ToolDetailPolicy` 가 판정을 갖는다.

| 경로 | 판정하는 자리 |
| --- | --- |
| 대화 스트림의 `tool` 사건 | `ChatController` 가 `ChatService` 에 넘기는 사건 소비자 |
| `GET /api/v1/usage/executions/{id}/tree` 의 `TOOL_STARTED`, `TOOL_COMPLETED` 사건 | `ExecutionTreeService` 가 `ExecutionEventView` 를 만들 때 |

도구 사건이 아닌 사건의 `detail` 은 모두에게 싣는다. 하위 에이전트의 목표, 실패 코드, 넘어간 모델 이름이다.
판정은 도구 이름 전체로 한다. `mcp__{서버}__web_search` 처럼 다른 MCP 서버가 같은 이름을 붙인 도구는 공개하지 않는다.

## 실행 사건

Hermes 가 스트림으로 보내는 사건을 우리 이름으로 옮겨 `execution_event` 에 적는다.
화면은 우리 이름만 읽고 Hermes 의 원래 이름을 알지 않는다.

- 옮겨 적는 자리가 한 곳이다. Hermes 가 이름을 바꾸면 그 한 곳만 고친다.
- 받은 payload 를 통째로 넣지 않고 필요한 칸만 고른다.
  도구가 읽어 온 문서 전체가 사건에 실려 오는 것을 그대로 저장하지 않기 위해서다.
- 모르는 사건은 버리고 버렸다는 사실만 로그로 남긴다.

화면에 흘리는 것과 사건을 저장하는 것이 같은 스트림을 두 가지로 쓴다.
저장하는 답은 스트림이 아니라 실행 결과에서 가져온다.

근거는 [`adr/ADR-013-실행-사건은-우리-모델로-정규화해-저장한다.md`](../adr/ADR-013-실행-사건은-우리-모델로-정규화해-저장한다.md) 에 있다.

### 실행의 시작과 끝은 Hermes 사건을 기다리지 않는다

`RUN_STARTED` 와 `RUN_COMPLETED` 와 `RUN_FAILED` 는 Control Plane 이 직접 적는다.
Hermes 도 `run.completed` 를 보내지만 그것을 옮겨 적지 않는다.

- 스트림을 열지 않는 경로에도 실행의 시작과 끝이 남아야 한다.
  옮겨 적는 쪽에만 두면 그 경로가 비고, 양쪽에 두면 스트리밍 경로만 두 줄이 된다.
- 우리가 적는 쪽이 `errorCode` 를 알고 있어 담을 것이 더 많다.

옮겨 적는 것은 도구와 하위 에이전트 사건뿐이다.

**사건 저장이 실패해도 중계와 대화는 그대로 이어진다.**
사건은 관측용이고 그것 때문에 답이 끊기면 안 된다.
옮기지 못한 사건과 저장에 실패한 사건은 순서를 소비하지 않아, 번호가 1부터 빈틈없이 이어진다.

### 나무는 메모리에서 잇는다

실행 하나를 그 사건과 자식 실행까지 묶어 낼 때 데이터베이스에서 재귀로 만들지 않는다.
`root_execution_id` 로 한 번에 읽어 메모리에서 잇는다.
한 실행의 자식 수가 많아질 일이 없고, 재귀 질의는 읽기 어렵다.

어느 실행 번호를 주든 그 실행이 속한 나무의 뿌리부터 낸다.
화면이 자식 실행에서 들어와도 전체를 보게 하기 위해서다.

**순환이 생길 수 있는 자리가 둘이고 대응이 다르다.**
잘못 적힌 `parent_execution_id` 하나로 응답이 끝나지 않을 수 있다.

| 어디 | 무엇을 하나 | 어디에 알리나 |
| --- | --- | --- |
| 뿌리를 찾아 올라가는 길 | 여덟 번에서 멈추고 마지막으로 닿은 실행을 뿌리로 삼는다 | 나무의 `truncated` |
| 자식을 붙여 내려가는 길 | 이미 붙인 실행을 다시 붙이지 않고 여덟째 깊이에서 멈춘다 | 그 노드의 `truncated` |

**`truncated` 가 두 곳에 있고 뜻이 다르다.**
나무의 것은 「이 나무 어딘가를 잘랐다」 이고 노드의 것은 「이 노드 아래를 잘랐다」 이다.
화면이 줄을 그리는 근거는 노드의 값이다. 나무의 값은 잘린 자리를 가리키지 않는다.

뿌리에서 닿지 않는 실행은 나무에 넣지 않고 로그로 남긴다. 데이터가 어긋난 것이다.
상한에서 일부러 자른 가지는 그 로그에서 뺀다. 원인이 달라서다.

남의 실행은 없는 것과 같은 오류로 응답한다.
뿌리를 찾아 올라간 뒤에도 주인을 다시 확인한다.

## 모델 단계와 자식 기록

`chat`이 그룹 단계 정의, 사용자와 그룹 기본값, 대화의 선택, 그룹의 모델 숨김을 소유한다.
에이전트 기본 모델의 값은 `agent` 표에 있고, 그 검증과 해석은 `chat` 이 한다.
실행을 시작할 때 선택을 해석하고 `usage`에 선택 스냅샷을 넘긴다.
`usage`는 부모 종료 뒤 자식 session의 최종 사용량을 별도 작업으로 보완한다.
`hermes`는 session 조회와 최소한의 profile 기본값 조회를 소유한다.
선택과 재조회 조건, 실패 처리 계약은 [모델 단계와 실행 기록](../model-tiers.md)이 정한다.

```mermaid
flowchart TD
    C[대화의 선택 확인] --> M{선택 모드}
    M -->|TIER| T[그룹 단계 정의 읽기]
    M -->|CUSTOM| A[직접 고른 값 읽기]
    M -->|DEFAULT| P[profile 기본값]
    M -->|미선택| U{내 기본 단계}
    U -->|있음| T
    U -->|없음| G{그룹 기본 단계}
    G -->|있음| T
    G -->|없음| P
    T --> R[실행에 선택 스냅샷 기록]
    A --> R
    P --> R
    R --> E[Hermes 실행]
    E --> F[실제 제공사와 모델 기록]
    F --> S{미완료 자식 사건}
    S -->|있음| J[별도 재조회 작업 저장]
    J --> Q{자식 session 종료 확인}
    Q -->|종료| D[중복 없이 사용량 기록]
    Q -->|미완료 또는 조회 실패| B{24시간 지남}
    B -->|아니오| W[간격을 늘려 재조회]
    W --> Q
    B -->|예| X[사용량 미확인으로 남김]
```

## 도구 내용 가리기

`tool.` 사건의 `preview`, `detail`, `result` 중 처음 있는 값을 설명으로 읽는다.
문자열과 JSON 객체, 배열을 모두 받는다.
`HermesRunEventStream`은 이 값을 가린 뒤 `RunEvent.detail`에 넣는다.
SSE와 실행 기록은 같은 가린 값을 쓰며, 대화의 작업 과정도 이 실행 기록을 조회한다.
관리자도 가리기 전 원문을 받지 않는다.
근거는 [ADR-047](../adr/ADR-047-도구-내용은-비밀값과-UUID를-가린-뒤-중계하고-저장한다.md)에 있다.

| 대상 | 처리 |
| --- | --- |
| JSON 비밀 키 | 중첩 객체와 배열에서도 값 전체를 `[가림]`으로 바꾼다. 키의 대소문자, `_`, `-` 차이는 무시한다 |
| 비밀 키 이름 | `token`, `secret`, `password`, `passwd`, `api_key`, `apikey`, `authorization`, `cookie`, `credential`, `credentials`, `private_key`, `access_key`, `client_secret`과 `token`, `secret`, `password`, `privatekey`로 끝나는 키 |
| 일반 문장의 비밀 할당 | `key=value`와 `key: value`에서 위 비밀 키의 값을 가린다. 따옴표 안의 공백도 값에 포함한다 |
| 인증 헤더 | 일반 문장의 `Authorization`과 `Cookie`는 인증 방식과 세미콜론으로 나뉜 값도 포함해 줄 끝까지 가린다 |
| 토큰 모양 | `Bearer` 인증값, `alg`를 가진 JSON 헤더와 base64url 세 구간으로 된 JWT, `sk-`, `ghp_`, `gho_`, `ghu_`, `ghs_`, `ghr_`, `github_pat_`, `xox`로 시작해 `-`로 끝나는 계열 접두의 값을 가린다 |
| 긴 인코딩 모양 | 32자 이상의 hex와 base64/base64url 덩어리를 가린다 |
| UUID | 표준 8-4-4-4-12 형태를 `[항목 N]`으로 바꾼다. 같은 실행 스트림 안에서 같은 UUID의 대소문자를 통일해 시작과 완료 사건에 같은 번호를 쓴다. 번호표는 스트림이 끝나면 버린다 |
| 연결용 에이전트 | `connectorManaged`가 참이면 내용 전체를 `[연결 도구 내용 가림]`으로 바꾼다. 짧은 manifest 비밀값도 노출하지 않는다 |
| 길이와 손상 | 가린 뒤 500자를 넘으면 끝에 `…`를 붙여 자른다. 입력이 65,536자를 넘으면 전체를 가린다. JSON으로 시작하지만 파싱할 수 없는 설명도 전체를 가린다 |

원문을 저장하거나 파싱 오류의 원문과 예외를 로그에 남기지 않는다.
가리기는 되돌릴 수 없고, 원문이 필요하면 Hermes에서 조사한다.
도구 이름, 성공 여부, 걸린 시간은 유지한다.
일반 도구의 금액, 날짜, 짧은 식별자와 일반 문장도 위 규칙에 걸리지 않으면 유지한다.
답 본문과 하위 에이전트 목표는 이 계약의 대상이 아니다.

`skill_view` 도구의 `tool.started` 사건은 가리기 전에 `preview` 에서 스킬 이름을 따로 꺼낸다.
32자를 넘는 스킬 이름은 토큰 모양이라 도구 내용에서 가려지기 때문이다.
꺼낸 이름은 Hermes 스킬 이름 규칙에 맞을 때만 스킬 사용 기록으로 넘기고, 도구 내용에는 싣지 않는다.
이름이 `...` 로 끝나면 길이 상한에서 잘린 것으로 보고 넘기지 않는다([도구와 스킬](../hermes/skills.md)).
연결용 에이전트의 실행에서는 꺼내지 않는다.

V41은 이미 저장된 `TOOL_STARTED`, `TOOL_COMPLETED`의 `detail`에도 같은 규칙을 적용한다.
UUID 번호표는 실행마다 새로 만들고 사건 순서대로 읽는다.
연결용 에이전트는 실행의 에이전트 번호나 profile로 판별한다.
다른 사건과 도구 이름, 성공 여부, 걸린 시간은 바꾸지 않는다.
기존 원문을 복원할 수 없으므로 배포 전에 데이터베이스를 백업해야 한다.
