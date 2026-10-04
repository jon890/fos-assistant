# Phase 02. 숨기기와 미루기, 항목의 동작

**Execution profile**: standard

## 목표

지금 화면의 항목에 숨기기, 미루기, 되돌리기를 주고, 항목을 열거나 동작할 때 사건을 남긴다.
할 일 제안과 열린 할 일에 받아들이기, 거절, 고치기, 끝냄, 그만둠을 주고 「내 차례」 카드에서 할 일을 직접 더한다.
「에이전트가 제안하고 사람이 받아들인다」 를 화면에서 끝까지 확인한다.

**범위 외**: 판정과 저장(plan79, plan80). 새 대화 화면의 한 줄과 관리자 지표(phase-03). 승인 자체는 그 대화의 승인 카드가 한다. 결과 다시 전달은 그 대화의 알림 줄이 한다.

## 컨텍스트

- phase-01 이 만든 것: `web/src/lib/attention.ts`(타입, `reasonText`, `itemHref`, `originText`, `moreText`, `nowLinkLabel`), `web/src/lib/attention-api.ts`(`fetchAttentionSummary`), `web/src/lib/format.ts` 의 `formatRelative`, `formatFullTime`, `web/src/components/now/` 의 `NowScreen`(서버 부품), `NowCard`, `NowItem`, `web/src/components/shell/now-link.tsx` 의 `NowLink`(`pathname` 이 바뀔 때 `fetchAttentionSummary` 를 다시 부른다), `test/browser/now.spec.ts` 의 `memberOf`, `ownAgent`, `failTurn`, 파일 끝 `afterAll`
- 본문을 받는 서버 라우트는 `web/src/app/api/memories/route.ts` 의 `POST` 처럼 `readJsonBody(request)` 로 읽고 `parsed.ok` 가 거짓이면 `parsed.response` 를 돌려준 뒤 `callControlPlane(path, { method: "POST", body: parsed.body })` 로 넘긴다
- Control Plane 이 204 로 답하는 라우트는 `web/src/app/api/agents/[code]/skills/[name]/enabled/route.ts` 처럼 `result.ok` 가 거짓이면 `errorResponse(result.code, result.message, result.status)`, 참이면 `new NextResponse(null, { status: 204 })` 다
- 경로 변수가 있는 라우트는 `web/src/app/api/memories/[id]/accept/route.ts` 처럼 `context.params` 를 읽고 형식이 틀리면 `errorResponse("VALIDATION_FAILED", …, 400)` 로 막는다. 할 일의 `{id}` 는 UUID 라 `web/src/lib/conversation-id.ts` 의 `isPublicId(value)` 로 본다
- 브라우저 요청 함수는 `web/src/lib/memory-api.ts` 의 `memoryRequest<T>(path, fallback, init)` 와 `MemoryApiResult<T>`(`{ ok: true; data: T } | { ok: false; message: string; code: string | null }`)를 그대로 쓴다. 실패하면 `describeFailure(response)` 의 문구와 응답의 `code` 를, 요청이 닿지 못하면 `fallback` 과 `code: null` 을 돌려준다. 본문 없는 204 는 `data: null` 이다
- 오류 코드의 사람 말 문구는 `web/src/components/error-message.ts` 의 `MESSAGES` 가 갖고 `test/unit/error-message.test.ts` 가 본다
- 메뉴는 `web/src/components/ui/dropdown-menu.tsx`, 고치기 폼은 `dialog.tsx` 와 `input.tsx`, `label.tsx`, `switch.tsx` 를 쓴다. 카드 머리의 메뉴 자리는 `CardAction` 이다
- 동작 뒤에는 `useRouter().refresh()` 로 서버 부품을 다시 읽는다. 숨기기와 미루기는 다시 읽지 않고 그 줄만 바꿔 그린다(「숨겼어요 · 되돌리기」)
- API 계약은 `docs/backend/attention.md` 의 「API」 와 `docs/backend/follow-up.md` 의 「API」 다
  - `POST /api/v1/attention/hide` `{ card, itemKey, stateKey }`, `/snooze` `{ card, itemKey, until }`, `/restore` `{ card, itemKey }`, `/events` `{ itemKey, stateKey, type }`(`OPENED`, `ACTED`). `card` 는 그 항목이 있는 카드의 열쇠다. 제어는 카드마다 따로 걸린다. 넷 모두 204 다
  - `POST /api/v1/follow-ups` `{ title, dueAt?, waiting?, conversationId? }`, `PATCH /api/v1/follow-ups/{id}` `{ title?, dueAt?, waiting? }`, `POST /api/v1/follow-ups/{id}/accept`, `/reject`, `/done`, `/drop`. 할 일 응답(`FollowUpView`)을 돌려준다. `dueAt` 은 시간대가 붙은 ISO-8601 이고, `PATCH` 의 `dueAt: null` 은 기한을 지운다
- `PROPOSED` 할 일은 지금 제안 도구(plan80 phase 03) 말고는 만드는 길이 없다. 그 도구는 이 phase 뒤에 들어온다. 그래서 브라우저 검사용 test-support 컨트롤러를 둔다. 본보기는 `backend/src/test/java/com/bifos/assistant/testsupport/ChatTestSupportController.java`(`@RestController`, `@RequestMapping("/api/v1/test-support/chat")`, `@ConditionalOnProperty(name = "assistant.test-support.enabled", havingValue = "true")`, `EntityManager` 로 JPQL)와 그것을 부르는 `test/browser/legacy-conversation-url.spec.ts` 의 `controlPlaneToken()`(`SignJWT` 와 `JWT_SECRET` 으로 서명한 Bearer 토큰, `CONTROL_PLANE_BASE_URL`)이다
- plan80 phase 01 과 02 가 만든 backend: `followup.domain.FollowUp.proposed(Long userId, Long conversationId, Long executionId, String title, String titleKey, Instant dueAt, boolean waiting, Instant now)`, `followup.infra.FollowUpRepository`, `followup.application.FollowUpService.titleKey(String)`, `chat.application.ConversationAccess.requireOwnId(CurrentUser, UUID)`

**근거 문서**: `docs/frontend/now.md` 의 「동작」, 「사용자 제어」, 「시나리오」, `docs/backend/attention.md` 의 「카드의 단추와 승인 경계」, 「API」, `docs/backend/follow-up.md` 의 「상태」, 「API」, `docs/adr/ADR-074-지금-화면은-원래-기록을-읽어-만든-view-이고-정해진-카드-넷만-그린다.md` 의 5번

## 의도 메모

- 제어는 원래 기록을 바꾸지 않는다. 숨긴 승인 대기도 그 대화의 승인 카드에는 그대로 있다
- 미루기 기한은 브라우저가 계산해 절대 시각으로 보낸다. 서버는 상한만 본다. 계산 함수는 순수 함수로 두고 단위 테스트로 본다
- **기한 입력은 `Asia/Seoul` 의 벽시계로 읽는다.** `datetime-local` 입력은 브라우저의 시간대를 따르지 않고, 순수 함수가 +9 시간을 고정으로 더하고 뺀다. 한국은 일광 절약 시간이 없다. 가족이 같은 시간대에 산다는 전제다(`usage.presentation.UsageController` 의 `HOUSEHOLD_ZONE` 과 같다)
- 사건을 남기지 못해도 동작은 그대로 한다. 사건 요청의 실패를 사용자에게 알리지 않는다
- **결과 전달 실패 항목은 「대화 열기」 만 둔다.** 응답에 묶음 번호가 없고, 다시 전달은 대화 화면이 읽는 SSE 를 돌려준다. 다시 전달은 그 대화의 알림 줄 아래 「결과 다시 전달」 이 한다
- 동작이 성공하면 사이드바의 「지금 볼 것」 수도 다시 읽게 사건 하나를 낸다. 경로가 바뀌지 않으므로 `NowLink` 의 `pathname` 효과가 돌지 않기 때문이다

## Blocked 조건

- `backend/src/main/java/com/bifos/assistant/attention/domain/AttentionControlEntry.java` 가 없다 → `PHASE_BLOCKED: plan79 phase-02 가 이 브랜치에 없다`. 경로는 `@RequestMapping` 과 `@PostMapping` 으로 나뉘어 있어 경로 글 전체로 찾지 않는다
- `backend/src/main/java/com/bifos/assistant/followup/presentation/FollowUpController.java` 가 없다 → `PHASE_BLOCKED: plan80 phase-01 이 이 브랜치에 없다`
- `backend/src/main/java/com/bifos/assistant/attention/application/FollowUpAttentionSource.java` 가 없다 → `PHASE_BLOCKED: plan80 phase-02 가 이 브랜치에 없다`. 할 일이 「내 차례」 카드에 보여야 아래 브라우저 검사가 돈다

## 작업 항목

### 1. 서버 라우트

모두 `readJsonBody` 로 본문을 읽고 `callControlPlane` 으로 넘긴다.

- `web/src/app/api/attention/hide/route.ts`, `snooze/route.ts`, `restore/route.ts`, `events/route.ts`: `POST`. 성공하면 `new NextResponse(null, { status: 204 })`
- `web/src/app/api/follow-ups/route.ts`: `POST`. 성공하면 `NextResponse.json(result.data)`
- `web/src/app/api/follow-ups/[id]/route.ts`: `PATCH`. `{id}` 가 `isPublicId` 가 아니면 `errorResponse("VALIDATION_FAILED", "할 일 식별자가 올바르지 않아요.", 400)`. 성공하면 `NextResponse.json(result.data)`
- `web/src/app/api/follow-ups/[id]/accept/route.ts`, `reject/route.ts`, `done/route.ts`, `drop/route.ts`: `POST`. `{id}` 검사는 위와 같다. 본문이 없다. 성공하면 `NextResponse.json(result.data)`

### 2. `web/src/lib/attention-api.ts`, `web/src/lib/follow-up-api.ts`

- `attention-api.ts` 에 더한다
  - `export const ATTENTION_CHANGED_EVENT = "attention-changed";`
  - `hideItem(card, itemKey, stateKey)`, `snoozeItem(card, itemKey, until)`, `restoreItem(card, itemKey)` → `Promise<MemoryApiResult<null>>`. `memoryRequest` 로 부르고 실패 문구 기본값은 「바꾸지 못했어요. 다시 시도해 주세요.」. 성공하면 `window.dispatchEvent(new Event(ATTENTION_CHANGED_EVENT))`
  - `recordAttentionEvent(itemKey, stateKey, type: "OPENED" | "ACTED"): void`. `fetch` 의 실패를 삼키고 알리지 않는다
  - `card` 의 타입은 `AttentionCardKey` 다
- `follow-up-api.ts` 신규: `createFollowUp(input: { title: string; dueAt: string | null; waiting: boolean })`, `updateFollowUp(id, patch: { title: string; dueAt: string | null; waiting: boolean })`, `acceptFollowUp(id)`, `rejectFollowUp(id)`, `finishFollowUp(id)`(done), `dropFollowUp(id)`. 모두 `memoryRequest` 로 부르고 `Promise<MemoryApiResult<FollowUpView>>` 다. 실패 문구 기본값은 「할 일을 저장하지 못했어요. 다시 시도해 주세요.」. 성공하면 `ATTENTION_CHANGED_EVENT` 를 낸다. `FollowUpView` 타입(`id`, `title`, `status`, `dueAt`, `waiting`, `conversationId`, `proposed`, `createdAt`, `acceptedAt`, `closedAt`)도 이 파일에 둔다
- `updateFollowUp` 은 고치기 폼의 값을 그대로 보낸다. 기한 칸을 비웠으면 `dueAt: null` 을 보내 기한을 지운다
- `web/src/components/shell/now-link.tsx`: `ATTENTION_CHANGED_EVENT` 를 받으면 `fetchAttentionSummary` 를 다시 부른다

### 3. `web/src/lib/attention.ts`

- `snoozeUntil(kind: "tomorrow" | "week", now: Date): string` — `tomorrow` 는 `Asia/Seoul` 기준 다음 날 09:00(UTC 00:00)의 ISO 문자열, `week` 는 `now` 에 7일을 더한 ISO 문자열. +9 시간을 고정으로 쓴다
- `seoulInputToIso(value: string): string | null` — `datetime-local` 의 `YYYY-MM-DDTHH:mm` 을 `Asia/Seoul` 의 그 시각으로 읽어 `toISOString()` 글을 낸다. 빈 글은 `null`, 형식이 틀리면 `null`
- `isoToSeoulInput(iso: string | null): string` — 위의 반대. `null` 이면 빈 글
- `type ItemAction = { kind: "link" | "accept" | "reject" | "edit" | "done" | "drop"; label: string }`
- `itemActions(item: AttentionItem): ItemAction[]` — `docs/frontend/now.md` 의 「동작」 표를 `why.trigger` 로 고른다

| `why.trigger` | 단추 |
| --- | --- |
| `EXECUTION_FAILED`, `DELIVERY_FAILED` | `link` 「대화 열기」 |
| `APPROVAL_PENDING` | `link` 「대화에서 보기」 |
| `MEMORY_PROPOSED` | `link` 「기억에서 보기」 |
| `FOLLOW_UP_PROPOSED` | `accept` 「받아들이기」, `reject` 「거절」, `edit` 「고치기」 |
| `FOLLOW_UP_OPEN` | `done` 「끝냄」, `drop` 「그만둠」, `edit` 「고치기」 |
| `DELEGATION_RUNNING`, `DELEGATION_FINISHED` | `link` 「작업 과정 보기」 |
| `CONVERSATION_RECENT`, 그 밖 | 없다. 제목을 누르면 대화가 열린다 |

`link` 단추는 `itemHref(item)` 이 `null` 이면 내지 않는다.

### 4. 부품

- `web/src/components/now/now-item.tsx` 를 `"use client"` 로 바꾼다. `NowCard` 도 `"use client"` 다. `NowScreen` 만 서버 부품으로 남고, 둘에는 응답의 카드와 `readAt` 글만 넘긴다
- `web/src/components/now/now-item-controls.tsx` 신규(`"use client"`): `DropdownMenu` 의 열기 단추 `aria-label` 은 「이 항목 제어」 다. 항목은 「숨기기」, 「내일 아침으로 미루기」, 「일주일 뒤로 미루기」. 누르면 `hideItem` 이나 `snoozeItem(card, itemKey, snoozeUntil(kind, new Date()))` 를 부르고 그 줄을 「숨겼어요 · 되돌리기」 나 「미뤘어요 · 되돌리기」 한 줄로 바꾼다. 「되돌리기」 는 `restoreItem` 뒤 원래 줄로 돌린다. 실패하면 그 줄 아래 `Notice variant="error"` 로 돌려받은 `message`
- `web/src/components/now/follow-up-dialog.tsx` 신규(`"use client"`): 칸은 제목(`Input`, 1자부터 200자, `maxLength={200}`), 기한(`<Input type="datetime-local">`, 선택), 기다리는 중(`Switch`). 새로 더하기와 고치기에 같이 쓴다. 고치기는 항목의 `title` 과 `isoToSeoulInput(followUp.dueAt)`, `followUp.waiting` 으로 미리 채운다. 저장할 때 기한은 `seoulInputToIso(value)` 다. 할 일 동작의 `{id}` 는 `followUp.id` 다. `itemKey` 를 잘라 쓰지 않는다. 저장이 실패하면 대화 상자 안 `Notice variant="error"` 로 `message`
- `web/src/components/now/now-item.tsx`: `itemActions` 의 단추를 그린다. `link` 는 `Link`(`prefetch={false}`) 이다. 제목 링크나 `link` 단추를 누르면 `recordAttentionEvent(itemKey, stateKey, "OPENED")`. 받아들이기, 거절, 끝냄, 그만둠, 고치기 저장이 성공하면 `"ACTED"` 를 남긴 뒤 `router.refresh()`. 좁은 폭에서 단추 줄은 제목과 이유 아래로 내려 `flex flex-wrap gap-2` 로 둔다
- `web/src/components/now/now-card.tsx`: `needs_me` 카드 끝에 「할 일 더하기」 단추. `FollowUpDialog` 를 새로 더하기로 연다. 저장하면 `router.refresh()`
- `web/src/components/error-message.ts` 의 `MESSAGES` 에 더한다
  - `ATTENTION_ITEM_NOT_FOUND`: 「이미 사라진 항목이에요. 화면을 다시 열어 주세요.」
  - `FOLLOW_UP_NOT_FOUND`: 「이미 지워졌거나 없는 할 일이에요.」
  - `FOLLOW_UP_STATE_CONFLICT`: 「이미 처리했거나 같은 할 일이 있어요. 화면을 다시 열어 주세요.」

### 5. 제안 시험 데이터

`backend/src/test/java/com/bifos/assistant/testsupport/FollowUpTestSupportController.java` 신규. `ChatTestSupportController` 와 같은 모양이다.

- `@RestController`, `@RequestMapping("/api/v1/test-support/follow-ups")`, `@RequiredArgsConstructor`, `@ConditionalOnProperty(name = "assistant.test-support.enabled", havingValue = "true")`
- `@PostMapping("/proposed")` 의 본문 `ProposedRequest(UUID conversationId, String title)`. `CurrentUserProvider.require()` 가 주인이다. `ConversationAccess.requireOwnId(user, conversationId)` 로 대화 번호를 얻고, `EntityManager` 의 `select max(e.id) from AgentExecution e where e.conversationId = :id` 로 제안한 실행을 정한다. `FollowUpRepository.saveAndFlush(FollowUp.proposed(user.id(), 대화 번호, 실행 번호, title.strip(), FollowUpService.titleKey(title), null, false, clock.instant()))` 로 저장하고 `ProposedFollowUp(UUID id)` 를 돌려준다
- 운영 코드는 고치지 않는다

### 6. 문서

- `docs/frontend/now.md` 「동작」 표의 결과 전달 실패 줄은 이미 「대화 열기」 로 바뀌어 있다. 다시 고치지 않는다
- `docs/frontend/now.md` 「동작」 표 아래 「「내 차례」 카드 끝에 …」 문단 끝에 「기한은 `Asia/Seoul` 의 날짜와 시각으로 넣는다. 「고치기」 에서 기한을 비우면 기한을 지운다.」 를 더한다
- `docs/frontend/now.md` 「항목 한 줄」 표의 제어 줄을 「항목 오른쪽의 `DropdownMenu`(「이 항목 제어」). 숨기기, 내일 아침으로 미루기, 일주일 뒤로 미루기」 로 바꾼다

### 7. `test/unit/attention.test.ts`

| 입력 | 기대 |
| --- | --- |
| `snoozeUntil("tomorrow", new Date("2026-10-04T14:30:00Z"))`(서울 23:30) | `"2026-10-05T00:00:00.000Z"` |
| `snoozeUntil("tomorrow", new Date("2026-10-04T16:30:00Z"))`(서울 다음 날 01:30) | `"2026-10-06T00:00:00.000Z"` |
| `snoozeUntil("week", new Date("2026-10-04T01:00:00Z"))` | `"2026-10-11T01:00:00.000Z"` |
| `seoulInputToIso("2026-10-05T18:00")` | `"2026-10-05T09:00:00.000Z"` |
| `seoulInputToIso("")`, `seoulInputToIso("내일")` | `null`, `null` |
| `isoToSeoulInput("2026-10-05T09:00:00Z")`, `isoToSeoulInput(null)` | `"2026-10-05T18:00"`, `""` |
| `itemActions` 에 `FOLLOW_UP_PROPOSED`, `FOLLOW_UP_OPEN`, `APPROVAL_PENDING`, `DELIVERY_FAILED`, `CONVERSATION_RECENT` | 「동작」 표의 단추. `DELIVERY_FAILED` 는 `link` 「대화 열기」 하나다 |
| `itemActions` 에 `conversationId` 가 `null` 인 `APPROVAL_PENDING` | 빈 목록 |

`test/unit/error-message.test.ts` 에 `describeError("FOLLOW_UP_STATE_CONFLICT", …)` 가 위 문구인지 보는 줄을 더한다.

### 8. `test/browser/now.spec.ts`

phase-01 의 사용자 구성과 도우미를 그대로 쓴다. 도우미를 더한다.

- `proposeFollowUp(email, conversationId, title)`: `legacy-conversation-url.spec.ts` 의 `controlPlaneToken()` 과 같은 방법으로 그 사용자의 메일로 서명한 토큰을 만들어 `${CONTROL_PLANE_BASE_URL}/api/v1/test-support/follow-ups/proposed` 를 부른다. 200 과 `{ id }` 를 본다
- 기한 입력값은 `isoToSeoulInput(new Date(Date.now() + 오프셋).toISOString())` 로 만든다(`../../web/src/lib/attention.ts`)

아래 순서대로 돈다. 앞 줄이 만든 데이터를 뒤 줄이 쓴다.

| 검사 | 기대 |
| --- | --- |
| `failTurn` 으로 실패한 turn 을 만들고 그 항목을 「이 항목 제어」 의 「숨기기」 로 숨긴다 | 그 줄이 「숨겼어요 · 되돌리기」 로 바뀐다. 다시 연 `/now` 의 실패 카드에 없다. 숨기기 전에 사이드바 범위 안의 `now-count` 가 「1」 인지 먼저 단언한다. 이 사용자의 `NOW` 항목은 이 실패 하나다. 숨긴 뒤 다시 연 화면에서는 `now-count` 가 0 개다. 같은 대화가 이어서 하기 카드에는 보인다 |
| 같은 대화에 실패를 하나 더 만든다(`failTurn` 에 그 `conversationId`) | 상태가 바뀌어 그 대화가 실패 카드에 다시 보인다 |
| 「할 일 더하기」 로 「장보기 예약 확인」 을 기한 3시간 뒤로 더한다 | `now-card-needs_me` 에 「기한이 다가왔어요」 와 「지금」 배지로 보이고 출처에 「직접 더함」 이 있다 |
| 그 할 일을 「내일 아침으로 미루기」 하고 바로 「되돌리기」 | 다시 연 `/now` 에 그 할 일이 그대로 있다 |
| 그 할 일의 「끝냄」 | 다시 읽은 화면에서 사라진다 |
| `proposeFollowUp` 으로 실패한 대화에 「학교 상담 신청서 내기」 를 제안한다. 그 전과 뒤의 `GET /api/attention/summary` 를 견준다 | 그 할 일이 「에이전트가 할 일로 제안했어요」 로 보이고 출처에 「대화에서」 가 있다. `data-attention` 이 `LATER` 이고 `nowCount` 가 늘지 않는다 |
| 그 제안을 「고치기」 로 열어 기한을 3일 뒤로 넣고 저장한 뒤 다시 「고치기」 를 연다 | 기한 칸의 값이 넣은 값과 같다 |
| 그 제안의 「받아들이기」 | 다시 읽은 화면에서 그 할 일이 「챙기고 있는 할 일이에요」 로 보이고 단추가 「끝냄」, 「그만둠」, 「고치기」 다 |
| `proposeFollowUp` 으로 「주말 장보기 목록 공유」 를 제안하고 「거절」 | 다시 읽은 화면에서 사라진다 |
| `mobile` | 메뉴와 단추가 화면 밖으로 넘치지 않는다(`scrollWidth` 가 390 이하) |

## 검증

```bash
# cwd: 저장소 root
node --test 'test/unit/**/*.test.ts'
! grep -rn 'style={{' web/src/
```

```bash
# cwd: backend/
./gradlew compileTestJava checkstyleTest
```

```bash
# cwd: web/
pnpm lint
pnpm format:check
pnpm typecheck
AUTH_SECRET=build-time-placeholder ASSISTANT_JWT_SECRET=build-time-placeholder CONTROL_PLANE_BASE_URL=http://build-time-placeholder AUTH_GOOGLE_ID=build-time-placeholder AUTH_GOOGLE_SECRET=build-time-placeholder pnpm build
pnpm test:browser ../test/browser/now.spec.ts
```

기대: 모두 종료 코드 0. `test/unit/attention.test.ts` 의 미루기 기한, 기한 변환, 동작 표 검사와 `test/unit/error-message.test.ts` 가 통과한다.
`pnpm test:browser` 가 띄우는 Control Plane 이 `FollowUpTestSupportController` 를 함께 올린다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/app/api/attention/hide/route.ts` | 신규 |
| `web/src/app/api/attention/snooze/route.ts` | 신규 |
| `web/src/app/api/attention/restore/route.ts` | 신규 |
| `web/src/app/api/attention/events/route.ts` | 신규 |
| `web/src/app/api/follow-ups/route.ts` | 신규 |
| `web/src/app/api/follow-ups/[id]/route.ts` | 신규 |
| `web/src/app/api/follow-ups/[id]/accept/route.ts` | 신규 |
| `web/src/app/api/follow-ups/[id]/reject/route.ts` | 신규 |
| `web/src/app/api/follow-ups/[id]/done/route.ts` | 신규 |
| `web/src/app/api/follow-ups/[id]/drop/route.ts` | 신규 |
| `web/src/lib/attention-api.ts` | 수정 |
| `web/src/lib/follow-up-api.ts` | 신규 |
| `web/src/lib/attention.ts` | 수정 |
| `web/src/components/now/now-item-controls.tsx` | 신규 |
| `web/src/components/now/follow-up-dialog.tsx` | 신규 |
| `web/src/components/now/now-item.tsx` | 수정 |
| `web/src/components/now/now-card.tsx` | 수정 |
| `web/src/components/shell/now-link.tsx` | 수정 |
| `web/src/components/error-message.ts` | 수정 |
| `backend/src/test/java/com/bifos/assistant/testsupport/FollowUpTestSupportController.java` | 신규 |
| `test/unit/attention.test.ts` | 수정 |
| `test/unit/error-message.test.ts` | 수정 |
| `test/browser/now.spec.ts` | 수정 |
| `docs/frontend/now.md` | 수정 |
