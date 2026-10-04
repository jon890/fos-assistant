# Phase 02. 숨기기와 미루기, 항목의 동작

**Execution profile**: standard

## 목표

지금 화면의 항목에 숨기기, 미루기, 되돌리기를 주고, 항목을 열거나 동작할 때 사건을 남긴다.
할 일 제안과 열린 할 일에 받아들이기, 거절, 고치기, 끝냄, 그만둠을 주고 「내 차례」 카드에서 할 일을 직접 더한다.

**범위 외**: 판정과 저장(plan79, plan80). 새 대화 화면의 한 줄과 관리자 지표(phase-03). 승인 자체는 그 대화의 승인 카드가 한다.

## 컨텍스트

- phase-01 이 `web/src/lib/attention.ts`(타입, `reasonText`), `web/src/lib/attention-api.ts`, `web/src/components/now/` 의 `NowScreen`, `NowCard`, `NowItem` 을 만들었다
- 본문을 받는 서버 라우트는 `web/src/app/api/memories/route.ts` 의 `POST` 처럼 `readJsonBody(request)` 로 읽고 `parsed.ok` 가 거짓이면 `parsed.response` 를 돌려준 뒤 `callControlPlane(path, { method: "POST", body: parsed.body })` 로 넘긴다
- 경로 변수가 있는 라우트는 `web/src/app/api/memories/[id]/accept/route.ts` 처럼 `context.params` 를 읽고 형식이 틀리면 `errorResponse("VALIDATION_FAILED", …, 400)` 로 막는다. 할 일의 `{id}` 는 UUID 라 `web/src/lib/conversation-id.ts` 의 `isConversationId` 와 같은 UUID 형식 검사를 쓴다
- 브라우저 요청 함수의 모양은 `web/src/lib/memory-api.ts` 의 `memoryRequest<T>(path, fallback, init)` 다. 실패하면 `describeFailure(response)` 의 문구를 돌려준다
- 메뉴는 `web/src/components/ui/dropdown-menu.tsx`, 고치기 폼은 `dialog.tsx` 와 `input.tsx`, `label.tsx`, `switch.tsx` 를 쓴다. 카드 머리의 메뉴 자리는 `CardAction` 이다
- 동작 뒤에는 `useRouter().refresh()` 로 서버 부품을 다시 읽는다. 숨기기와 미루기는 다시 읽지 않고 그 줄만 바꿔 그린다(「숨겼어요 · 되돌리기」)
- API 계약은 `docs/backend/attention.md` 의 「API」 와 `docs/backend/follow-up.md` 의 「API」 다
  - `POST /api/v1/attention/hide` `{ card, itemKey, stateKey }`, `/snooze` `{ card, itemKey, until }`, `/restore` `{ card, itemKey }`, `/events` `{ itemKey, stateKey, type }`(`OPENED`, `ACTED`). `card` 는 그 항목이 있는 카드의 열쇠다. 제어는 카드마다 따로 걸린다
  - `POST /api/v1/follow-ups` `{ title, dueAt?, waiting?, conversationId? }`, `PATCH /api/v1/follow-ups/{id}` `{ title?, dueAt?, waiting? }`, `POST /api/v1/follow-ups/{id}/accept`, `/reject`, `/done`, `/drop`

**근거 문서**: `docs/frontend/now.md` 의 「동작」, 「사용자 제어」, 「시나리오」, `docs/backend/attention.md` 의 「카드의 단추와 승인 경계」, 「API」, `docs/backend/follow-up.md` 의 「상태」, 「API」, `docs/adr/ADR-074-지금-화면은-원래-기록을-읽어-만든-view-이고-정해진-카드-넷만-그린다.md` 의 5번

## 의도 메모

- 제어는 원래 기록을 바꾸지 않는다. 숨긴 승인 대기도 그 대화의 승인 카드에는 그대로 있다
- 미루기 기한은 브라우저가 계산해 절대 시각으로 보낸다. 서버는 상한만 본다. 계산 함수는 순수 함수로 두고 단위 테스트로 본다
- 사건을 남기지 못해도 동작은 그대로 한다. 사건 요청의 실패를 사용자에게 알리지 않는다
- 「결과 다시 전하기」 단추는 #162 가 web 에 만든 호출 함수가 있을 때만 그린다. `git grep -n "DELIVERY_FAILED\|redeliver\|re-deliver" web/src/lib` 로 찾고, 없으면 그 항목은 이유와 「대화 열기」 만 그린다. 이 phase 가 #162 의 서버 라우트를 새로 만들지 않는다

## Blocked 조건

- `backend/src/main/java/com/bifos/assistant/attention/domain/AttentionControlEntry.java` 가 없다 → `PHASE_BLOCKED: plan79 phase-02 가 main 에 없다`. 경로는 `@RequestMapping` 과 `@PostMapping` 으로 나뉘어 있어 경로 글 전체로 찾지 않는다
- `backend/src/main/java/com/bifos/assistant/followup/presentation/FollowUpController.java` 가 없다 → `PHASE_BLOCKED: plan80 phase-01 이 main 에 없다`
- `backend/src/main/java/com/bifos/assistant/attention/application/FollowUpAttentionSource.java` 가 없다 → `PHASE_BLOCKED: plan80 phase-02 가 main 에 없다`. 할 일이 「내 차례」 카드에 보여야 아래 브라우저 검사가 돈다

## 작업 항목

### 1. 서버 라우트

모두 `readJsonBody` 로 본문을 읽고 `callControlPlane` 으로 넘긴다. 결과는 `NextResponse.json(result.data)` 나 `errorResponse` 다. Control Plane 이 204 로 답하면 `new NextResponse(null, { status: 204 })` 를 돌려준다.

- `web/src/app/api/attention/hide/route.ts`, `snooze/route.ts`, `restore/route.ts`, `events/route.ts`: `POST`
- `web/src/app/api/follow-ups/route.ts`: `POST`
- `web/src/app/api/follow-ups/[id]/route.ts`: `PATCH`
- `web/src/app/api/follow-ups/[id]/accept/route.ts`, `reject/route.ts`, `done/route.ts`, `drop/route.ts`: `POST`. `{id}` 가 UUID 가 아니면 400

### 2. `web/src/lib/attention-api.ts`, `web/src/lib/follow-up-api.ts`

- `attention-api.ts` 에 `hideItem(card, itemKey, stateKey)`, `snoozeItem(card, itemKey, until)`, `restoreItem(card, itemKey)`, `recordAttentionEvent(itemKey, stateKey, type)` 를 더한다. `card` 는 `AttentionCardKey` 다
- `follow-up-api.ts` 신규: `createFollowUp`, `updateFollowUp`, `acceptFollowUp`, `rejectFollowUp`, `finishFollowUp`(done), `dropFollowUp`. 결과 모양은 `memory-api.ts` 의 `MemoryApiResult<T>` 와 같게 `{ ok: true, data } | { ok: false, message }`

### 3. `web/src/lib/attention.ts`

- `snoozeUntil(kind: "tomorrow" | "week", now: Date): string` — `tomorrow` 는 `Asia/Seoul` 기준 다음 날 09:00(UTC 00:00)의 ISO 문자열, `week` 는 `now` 에 7일을 더한 ISO 문자열. 한국은 일광 절약 시간이 없어 +9 시간을 고정으로 쓴다
- `itemActions(item): ItemAction[]` — `docs/frontend/now.md` 의 「동작」 표를 `trigger` 로 고른다. 단추의 이름과 종류(`link`, `accept`, `reject`, `edit`, `done`, `drop`)를 돌려준다

### 4. 부품

- `web/src/components/now/now-item-controls.tsx` 신규(`"use client"`): `DropdownMenu` 로 숨기기, 미루기 ▸ 내일 아침, 일주일 뒤. 누르면 `hideItem` 이나 `snoozeItem` 을 부르고 그 줄을 「숨겼어요 · 되돌리기」 나 「미뤘어요 · 되돌리기」 한 줄로 바꾼다. 「되돌리기」 는 `restoreItem` 뒤 원래 줄로 돌린다. 실패하면 그 줄 아래 `Notice variant="error"` 로 돌려받은 문구
- `web/src/components/now/follow-up-dialog.tsx` 신규(`"use client"`): 제목(1자부터 200자), 기한(날짜와 시각, 선택), 기다리는 중(`Switch`). 새로 더하기와 고치기에 같이 쓴다. 고치기는 항목의 `title` 과 `followUp.dueAt`, `followUp.waiting` 으로 미리 채운다. 할 일 동작의 `{id}` 는 `followUp.id` 다. `itemKey` 를 잘라 쓰지 않는다
- `web/src/components/now/now-item.tsx`: `itemActions` 의 단추를 그린다. 제목 링크를 누르면 `recordAttentionEvent(…, "OPENED")`, 받아들이기와 끝냄 같은 동작이 성공하면 `"ACTED"` 를 남긴 뒤 `router.refresh()`
- `web/src/components/now/now-card.tsx`: `needs_me` 카드 끝에 「할 일 더하기」 단추. `FollowUpDialog` 를 연다

### 5. `test/unit/attention.test.ts`

| 입력 | 기대 |
| --- | --- |
| `snoozeUntil("tomorrow", new Date("2026-10-04T14:30:00Z"))`(서울 23:30) | `"2026-10-05T00:00:00.000Z"` |
| `snoozeUntil("tomorrow", new Date("2026-10-04T16:30:00Z"))`(서울 다음 날 01:30) | `"2026-10-06T00:00:00.000Z"` |
| `snoozeUntil("week", new Date("2026-10-04T01:00:00Z"))` | `"2026-10-11T01:00:00.000Z"` |
| `itemActions` 에 `FOLLOW_UP_PROPOSED`, `FOLLOW_UP_OPEN`, `APPROVAL_PENDING`, `CONVERSATION_RECENT` | 「동작」 표의 단추 |

### 6. `test/browser/now.spec.ts`

phase-01 의 사용자 구성을 그대로 쓴다.

| 검사 | 기대 |
| --- | --- |
| 실패한 turn 을 숨긴다 | 그 줄이 「숨겼어요 · 되돌리기」 로 바뀐다. 다시 연 `/now` 의 실패 카드에 없고 `now-count` 가 1 줄었다. 같은 대화가 이어서 하기 카드에는 보인다 |
| 같은 대화에 실패를 하나 더 만든다(`hermes.busy()`) | 상태가 바뀌어 그 대화가 실패 카드에 다시 보인다 |
| 할 일을 「내일 아침」 으로 미루고 바로 「되돌리기」 | 다시 연 `/now` 에 그 할 일이 그대로 있다 |
| 「할 일 더하기」 로 「장보기 예약 확인」 을 기한 3시간 뒤로 더한다 | `now-card-needs_me` 에 「기한이 다가왔어요」 와 「지금」 배지로 보인다 |
| 그 할 일의 「끝냄」 | 다시 읽은 화면에서 사라진다 |
| `mobile` | 메뉴와 단추가 화면 밖으로 넘치지 않는다(`scrollWidth` 가 390 이하) |

## 검증

```bash
# cwd: 저장소 root
node --test 'test/unit/**/*.test.ts'
! grep -rn 'style={{' web/src/

# cwd: web/
pnpm lint
pnpm format:check
pnpm typecheck
AUTH_SECRET=build-time-placeholder ASSISTANT_JWT_SECRET=build-time-placeholder CONTROL_PLANE_BASE_URL=http://build-time-placeholder AUTH_GOOGLE_ID=build-time-placeholder AUTH_GOOGLE_SECRET=build-time-placeholder pnpm build
pnpm test:browser ../test/browser/now.spec.ts
```

기대: 모두 종료 코드 0. `test/unit/attention.test.ts` 의 미루기 기한과 동작 표 검사가 통과한다.

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
| `test/unit/attention.test.ts` | 수정 |
| `test/browser/now.spec.ts` | 수정 |
