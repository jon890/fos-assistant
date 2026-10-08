# 할 일

에이전트가 제안하고 사람이 받아들인 할 일의 상태, MCP 도구, 제안 억제 규칙, API 를 갖는다.
결정은 [ADR-073](../adr/ADR-073-할-일은-에이전트가-제안하고-사람이-받아들인-것만-챙긴다.md) 에 있다.

## 무엇인가

할 일은 사용자가 해야 하거나 끝나기를 기다리는 일 한 줄이다. 코드 이름은 `follow_up` 이고 패키지도 `followup` 이다.
패키지는 층 순서에서 `chat` 위, `proactive` 아래에 둔다. `mcp` 가 제안 도구로 부르고 `attention` 이 읽는다.

| 말 | 뜻 | 헷갈리기 쉬운 것 |
| --- | --- | --- |
| 할 일 | `follow_up` 한 줄 | Hermes 위임의 `task` 인자, `tasks/` 계획서와 다르다 |
| 기다리는 중 | 할 일의 `waiting` 이 참이다. 남이나 에이전트가 끝내기를 기다린다 | 대화 대기열의 **대기 메시지**와 다르다. 대기 메시지는 응답 중에 보낸 사용자 글이다([`turn-control.md`](turn-control.md)) |
| 맡긴 일 | 위임 실행. 지금 화면의 `delegated` 카드 | 할 일은 실행이 아니다. 할 일에서 실행으로 넘어가지 않는다 |

## 상태

| 상태 | 뜻 | 다음으로 갈 수 있는 상태 | 누가 |
| --- | --- | --- | --- |
| `PROPOSED` | 에이전트가 제안했다 | `OPEN`(받아들이기), `REJECTED`(거절) | 사람 |
| `OPEN` | 챙기는 중이다 | `DONE`(끝냄), `DROPPED`(그만둠) | 사람 |
| `DONE` | 끝났다 | 없다 | |
| `DROPPED` | 그만두었다 | 없다 | |
| `REJECTED` | 제안을 거절했다 | 없다 | |

사람이 직접 더한 할 일은 바로 `OPEN` 이다.
`PROPOSED` 와 `OPEN` 은 제목, 기한, 기다리는 중을 고칠 수 있다. 끝난 세 상태는 고치지 못한다.
허용하지 않는 전이는 409 `FOLLOW_UP_STATE_CONFLICT` 다.

## 제안 도구

Control Plane MCP 서버가 `follow_up_propose` 를 둔다.
먼저 살펴보기 트리에서도 이 도구를 받는다([ADR-085](../adr/ADR-085-매일-깨우기는-예약-작업을-다시-쓰고-다섯-칸-보고를-지금-화면에-올린다.md)).
관리자의 쓰기 도구 허용 설정과 관계없이 `PROPOSED` 만 만들고, 사람이 받아들여야 `OPEN` 이 된다.
판정 자리는 [`mcp-caller.md`](mcp-caller.md) 의 「Control Plane MCP」 가 갖는다.

| 입력 | 타입 | 뜻 |
| --- | --- | --- |
| `title` | 문자열, 1자부터 200자 | 할 일 한 줄. 앞뒤 공백을 지운다 |
| `due_at` | 문자열, 선택 | `2026-10-05` 나 `2026-10-05T18:00` 형식. 시간대가 없으면 `Asia/Seoul` 로 읽는다. 날짜만 주면 그날 23:59 다 |
| `waiting` | 참거짓, 선택 | 기다리는 중이면 참 |

도구 정의는 아래와 같다. `tools/list` 에서 Control Plane 도구 가운데 일곱째이고 `memory_remember` 가 그 뒤에 온다.

| 칸 | 값 |
| --- | --- |
| `name` | `follow_up_propose` |
| `description` | 「사용자가 나중에 해야 하거나 끝나기를 기다리는 일을 할 일로 제안한다. 사용자가 받아들여야 챙긴다. 대화에서 분명히 나온 후속 작업만 제안하고, 같은 대화에서 거절한 것은 다시 제안하지 않는다. due_at 은 2026-10-05 나 2026-10-05T18:00 형식이다.」 |
| `inputSchema` | `{ "type": "object", "additionalProperties": false, "properties": { "title": { "type": "string", "minLength": 1, "maxLength": 200 }, "due_at": { "type": "string" }, "waiting": { "type": "boolean" } }, "required": ["title"] }` |

`due_at` 은 `2026-10-05`, `2026-10-05T18:00`, `2026-10-05T18:00:00`, 시간대가 붙은 ISO-8601(`2026-10-05T18:00+09:00`, `2026-10-05T09:00:00Z`)을 받는다. 시간대가 없으면 `Asia/Seoul` 이다.
`due_at` 을 UTC 로 바꾼 연도가 1부터 9999 밖이면 읽지 못한 것으로 본다. 사람이 쓰는 API 와 같은 범위다.
`due_at` 과 `waiting` 의 `null` 은 없는 것으로 본다. `title` 의 `null` 은 빠진 것이다.
모르는 키, `title` 이 없거나 문자열이 아닌 것, `due_at` 이 문자열이 아니거나 `waiting` 이 참거짓이 아닌 것은 JSON-RPC `-32602` 다.
결과는 MCP 도구 결과 `{ "content": [{ "type": "text", "text": "<아래 표의 글>" }], "isError": <참거짓> }` 하나다.

- **주인과 대화는 호출의 origin 실행에서 정한다.** `McpCallerResolver` 가 찾은 origin 실행의 `user_id` 가 주인이고 `conversation_id` 가 대화다. 인자로 받지 않는다
- 대화가 없는 실행(추천 질문 같은 것)에서 부르면 거절한다. 글은 「대화 밖의 실행에서는 할 일을 제안할 수 없다.」 다
- 옛 커넥터 에이전트는 Control Plane MCP 도구를 받지 못한다. 지금 `McpCallerResolver` 의 거절이 그대로 막는다(ADR-045). 연결을 붙인 일반 에이전트는 이 도구를 부를 수 있다
- **fos-ctx 가 이 도구를 서명 필수로 안다.** `hermes/plugins/fos-ctx/hooks.py` 의 `REQUIRED_TOOLS` 에 넣는다
- **실행 사건에 이 도구의 인자와 결과를 남기지 않는다.** `HermesRunEventStream` 이 시작 사건(`tool.started` 의 `preview`)과 끝 사건의 `detail` 을 비운다. 인자에 할 일 제목이 실려, 남기면 관리자가 실행 기록에서 남의 할 일 제목을 읽는다

응답은 도구 결과 글 하나다.

| 결과 | `isError` | 글 |
| --- | --- | --- |
| 새 제안 | 거짓 | 「할 일로 제안했다. 사용자가 지금 화면에서 받아들이면 챙긴다.」 |
| 같은 할 일이 이미 있다 | 거짓 | 「같은 할 일이 이미 있다. 새로 만들지 않았다.」 |
| 이 대화에서 거절한 적이 있다 | 참 | 「사용자가 이 할 일을 거절했다. 다시 제안하지 않는다.」 |
| 열린 제안이 많다. 한 대화의 3개나 한 실행의 2개에 닿았다 | 참 | 「이 대화에 받아들이기를 기다리는 제안이 많다. 사용자가 정한 뒤에 제안한다.」 |
| 값 오류. 앞뒤 공백을 지운 제목이 비었거나 200자를 넘는다 | 참 | 「title 은 1자부터 200자까지다.」 |
| 값 오류. `due_at` 을 읽지 못한다 | 참 | 「due_at 은 2026-10-05 나 2026-10-05T18:00 형식이다.」 |

## 제안 억제

| 규칙 | 판정 |
| --- | --- |
| 같은 사용자의 `PROPOSED` 나 `OPEN` 에 같은 `title_key` 가 있다 | 새 줄을 만들지 않는다 |
| 같은 대화에서 30일 안에 `REJECTED` 된 같은 `title_key` 가 있다 | 거절한다 |
| 같은 대화의 `PROPOSED` 가 3개다 | 거절한다. 점검 대화는 최근 7일 안에 만든 제안만 센다 |
| 한 실행에서 이미 2개를 제안했다 | 거절한다 |

「한 실행」 은 호출의 origin 실행이다. 그 실행이 부른 하위 에이전트의 호출도 그 실행의 수에 함께 센다.
두 상한은 세는 것과 저장하는 것 사이에 잠금을 두지 않는다. 같은 대화에서 나란히 제안하면 상한을 한두 개 넘을 수 있다.
같은 제목은 아래 유일 제약이 하나만 남긴다.

점검 대화에서 7일 넘게 처리하지 않은 제안은 상한에서만 뺀다.
제안 줄은 그대로이며 「지금 볼 것」 의 「내 차례」 에서 받아들이거나 거절한다.
보통 대화는 만든 시각과 관계없이 모든 열린 제안을 센다. 같은 제목과 거절 이력의 억제는 점검 대화에도 그대로 적용한다.

`title_key` 는 제목을 NFC 로 맞추고, 앞뒤 공백을 지우고, 연속 공백을 하나로 줄이고, 소문자로 바꾼 글의 SHA-256 16진수다.
같은 제안이 동시에 두 번 오면 `(user_id, title_key, open_marker)` 유일 제약이 하나만 남긴다([`schema/attention.md`](schema/attention.md) 의 「follow_up」).

## API

웹 JWT 로 부르고 주인만 읽고 쓴다. 남의 할 일이거나 없으면 404 `FOLLOW_UP_NOT_FOUND` 다.
경로의 `{id}` 는 공개 식별자(UUID)다.

| 경로 | 하는 일 |
| --- | --- |
| `GET /api/v1/follow-ups` | `PROPOSED` 와 `OPEN` 을 만든 순서로 낸다 |
| `POST /api/v1/follow-ups` | 본문 `{ title, dueAt?, waiting?, conversationId? }`. 바로 `OPEN` 으로 만든다. `conversationId` 는 요청자의 대화여야 한다. 같은 `title_key` 의 열린 줄이 있으면 새 줄을 만들지 않는다. 그 줄이 `PROPOSED` 면 사람이 받아들인 것으로 보고 `OPEN` 으로 바꿔 돌려주고, `OPEN` 이면 그대로 돌려준다 |
| `PATCH /api/v1/follow-ups/{id}` | 본문 `{ title?, dueAt?, waiting? }`. 본문에 없는 칸은 그대로 둔다. `dueAt` 을 `null` 로 보내면 기한을 지운다. 바꾼 제목의 `title_key` 가 같은 사용자의 다른 열린 할 일과 같으면 409 `FOLLOW_UP_STATE_CONFLICT` 다 |
| `POST /api/v1/follow-ups/{id}/accept` | `PROPOSED` 를 `OPEN` 으로. `accepted_at` 을 적는다 |
| `POST /api/v1/follow-ups/{id}/reject` | `PROPOSED` 를 `REJECTED` 로 |
| `POST /api/v1/follow-ups/{id}/done` | `OPEN` 을 `DONE` 으로 |
| `POST /api/v1/follow-ups/{id}/drop` | `OPEN` 을 `DROPPED` 로 |

`title` 이 비었거나 200자를 넘으면 400 `VALIDATION_FAILED` 다. 앞뒤 공백을 지운 길이로 센다.
`dueAt` 은 시간대가 붙은 ISO-8601 시각(`2026-10-05T09:00:00Z`)이다. 읽지 못하면 400 `VALIDATION_FAILED` 다.
`dueAt` 을 UTC 로 바꾼 연도가 1부터 9999 밖이면 400 `VALIDATION_FAILED` 다.
본문의 `conversationId` 가 UUID 가 아니면 400 `VALIDATION_FAILED` 다.
`conversationId` 가 요청자의 대화가 아니거나 지운 대화면 404 `CONVERSATION_NOT_FOUND` 다. `PATCH` 의 `title` 과 `waiting` 은 키가 없거나 `null` 이면 그대로 둔다.

응답의 칸은 `id`, `title`, `status`, `dueAt`, `waiting`, `conversationId`(대화 공개 식별자), `proposed`(에이전트가 제안했는지), `createdAt`, `acceptedAt`, `closedAt` 이다.

## 지키는 것

- **제목을 로그, 실행 사건, 알림 줄에 남기지 않는다.** 로그에는 사용자 번호, 할 일 번호, 실행 번호, 결과만 남긴다
- 제목은 모델이 쓴 글일 수 있다. 화면은 평문으로 그린다(ADR-009)
- 할 일을 대화의 `instructions` 에 싣지 않는다([`context-bundle.md`](context-bundle.md))
- 할 일에서 Hermes 실행이나 커넥터 호출을 시작하지 않는다
