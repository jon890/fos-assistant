# Phase 05. 대화 화면의 「결과 다시 전달」

**Execution profile**: standard

## 목표

실패하거나 중지한 결과 전달의 알림 줄 아래에 상태와 「결과 다시 전달」 을 그리고, 누르면 다시 전달 turn 을 보낸 turn 처럼 흘려 그린다.
사용자가 다음 접속에서도 끊긴 결과 정리를 찾아 되돌릴 수 있게 하려는 것이다.

**범위 외**: backend(phase 01 부터 03). 관리자 화면에 시도 기록을 그리는 일은 하지 않는다.

## 컨텍스트

- phase 03 이 만든 것: `POST /api/v1/chat/conversations/{conversationId}/deliveries/{deliveryId}/retry/stream`(SSE. 사건은 `system`, `started`, `delta`, `tool`, `done`, `stopped`, `error`), 이력 API 의 `SYSTEM` 줄에 붙는 `delivery: { id: number, status: "DELIVERING" | "DELIVERED" | "FAILED" | "STOPPED" } | null`, 오류 코드 `DELIVERY_NOT_FOUND`, `DELIVERY_NOT_RETRYABLE`
- 다시 생성이 본보기다
  - 웹 경로 `web/src/app/api/chat/conversations/[conversationId]/regenerate/route.ts`(`isConversationId` 확인, `requestControlPlane`, 실패면 JSON 오류를 그대로, 성공이면 `text/event-stream` 으로 넘긴다)
  - 클라이언트 함수 `web/src/lib/chat-api.ts` 의 `regenerateLatestAnswer`
  - 소비 `web/src/components/chat/conversation-session.tsx` 의 `regenerate()`. 응답이 실패면 `setError(describeError(...))`, 성공이면 `consumeTurnStream` 과 `applyTurnEvent` 로 사건을 그리고, 끝나면 `refresh()` 와 `refreshMessages` 를 부르고, `finally` 에서 `finishSentTurn` 으로 미뤄 둔 대화 사건을 흘린다
- 메시지 타입은 `web/src/components/chat/message-bubble.tsx` 의 `Turn` 이다. `SYSTEM` 줄은 같은 파일의 `turn.role === "SYSTEM"` 분기가 `data-testid="system-message"` 인 `li` 로 글만 그린다. 메시지 동작은 `web/src/components/chat/message-list.tsx` 를 거쳐 세션에서 내려온다
- 대화 단위 SSE 는 `conversation-session.tsx` 의 `applyConversationEvent` 가 그린다. 자동 turn 이 아직 없는 상태(`started` 전)의 `error` 는 지금 대기 줄이 있을 때만 `setError` 를 부르고 이력을 다시 읽지 않는다
- 오류 문구는 `web/src/components/error-message.ts` 의 `MESSAGES` 와 `describeError` 다. 단위 검사는 `test/unit/error-message.test.ts` 다
- 화면 문구 규칙은 `web/AGENTS.md` 의 「화면 문구」(해요체, 사용자가 주어, 오류 코드를 그리지 않는다), 색과 간격은 같은 문서의 「색과 간격은 테마 토큰이 소유한다」 다
- 브라우저 검사의 본보기는 `test/browser/chat-delegation-wake.spec.ts`(`routeEvents`, `routeMessagesWithWake` 로 `page.route` 응답을 꾸민다)와 `test/browser/regenerate.spec.ts`(SSE 본문을 꾸민다)다

**근거 문서**: `docs/frontend/chat.md` 의 「결과 다시 전달」, `docs/flow.md` 의 「실행이 실패할 때」, `docs/adr/ADR-070-결과-전달은-묶음과-시도로-남기고-사용자가-저장된-결과만-다시-전달한다.md`

## 의도 메모

- `FAILED` 는 테두리 단추로, `STOPPED` 는 글자 단추로 그린다. 둘의 동작은 같다. 중지는 다른 말을 하려고 누르는 경우가 많아 되돌릴 길을 두되 실패보다 덜 눈에 띄게 한다
- 누르면 응답을 받기 전에 단추를 막는다. 서버도 대화 잠금과 조건부 update 로 막지만, 화면이 두 번 보내지 않는 것이 먼저다
- `CONVERSATION_BUSY` 와 `USER_BUSY` 는 단추를 남긴다. 나머지 오류는 이력을 다시 읽어 서버의 상태대로 그린다
- 오류 코드와 시도 수는 그리지 않는다

## 작업 항목

### 1. `web/src/app/api/chat/conversations/[conversationId]/deliveries/[deliveryId]/retry/route.ts`

`regenerate/route.ts` 와 같은 모양의 `POST` 다. `deliveryId` 가 `^[1-9][0-9]{0,18}$` 이 아니면 `errorResponse("VALIDATION_FAILED", "다시 전할 결과를 찾지 못했어요.", 400)` 이다.
Control Plane 경로는 `/api/v1/chat/conversations/${conversationId}/deliveries/${deliveryId}/retry/stream` 이다.

### 2. `web/src/lib/chat-api.ts`

`export function retryDelivery(conversationId: string, deliveryId: number): Promise<Response>` 를 `regenerateLatestAnswer` 옆에 둔다. `POST ${conversationPath(conversationId)}/deliveries/${deliveryId}/retry` 다.

### 3. `web/src/components/chat/message-bubble.tsx`

- `Turn` 에 `delivery?: { id: number; status: "DELIVERING" | "DELIVERED" | "FAILED" | "STOPPED" } | null;` 를 더한다. 주석은 「이 알림 줄이 결과 묶음의 마지막 알림 줄이면 그 묶음의 상태」 다
- `SYSTEM` 분기: `delivery.status` 가 `FAILED` 면 글 아래에 「결과를 정리하지 못했어요」 와 테두리 단추 「결과 다시 전달」, `STOPPED` 면 「결과 정리를 중지했어요」 와 글자 단추 「결과 다시 전달」 을 그린다. 상태 글은 `data-testid="delivery-status"`, 단추는 `data-testid="delivery-retry"` 다. 그 밖의 상태와 `delivery` 가 없는 줄은 지금처럼 글만 그린다
- 단추를 누르면 새 prop `onRetryDelivery?: (deliveryId: number) => void` 를 부른다. 새 prop `deliveryRetrying?: boolean` 이 참이면 단추를 막는다. 단추 부품은 이 파일과 `message-list.tsx` 가 이미 쓰는 `Button` 을 쓴다

### 4. `web/src/components/chat/message-list.tsx`

`onRetryDelivery` 와 `deliveryRetrying` 를 받아 `SYSTEM` 줄의 `MessageBubble` 에 넘긴다.

### 5. `web/src/components/chat/conversation-session.tsx`

- `retryDelivery(deliveryId: number)` 를 `regenerate()` 옆에 둔다. 보내는 중이면 아무것도 하지 않는다. 시작하면 `deliveryRetrying` 상태를 켜고 보내는 중으로 둔다
  - 응답이 실패면 `setError(describeError(...))` 로 안내를 보이고, 코드가 `CONVERSATION_BUSY` 나 `USER_BUSY` 가 아니면 `refreshMessages` 를 부른다
  - 성공이면 다시 생성과 같은 소비 경로로 그린다. `system` 사건은 대화 단위 SSE 의 `system` 처리와 같이 `SYSTEM` 줄을 잇는다(같은 `messageId` 가 이미 있으면 잇지 않는다). 답 조각은 임시 줄 `assistant-retry-*` 에 쌓는다
  - `done` 이나 `stopped` 면 임시 줄을 지우고 `refresh()` 와 `refreshMessages` 를 부른다. 스트림 안의 `error` 면 임시 줄을 지우고 `setTurnError(describeError(...))` 로 안내를 보인 뒤 `refreshMessages` 를 부른다
  - 끊긴 스트림과 `finally` 의 정리는 `regenerate()` 의 `settleInterruptedStream` 과 `finishSentTurn` 을 그대로 따른다. 끝나면 `deliveryRetrying` 을 끈다
- `applyConversationEvent` 에서 자동 turn 이 아직 없는 상태의 `error` 를 받으면 지금 하는 일에 더해 `refreshMessages` 를 부른다. `started` 전에 실패한 자동 turn 의 단추가 새로 고치지 않아도 보이게 하려는 것이다
- `MessageList` 에 `onRetryDelivery={retryDelivery}` 와 `deliveryRetrying` 을 넘긴다

### 6. `web/src/components/error-message.ts`

`MESSAGES` 에 둘을 더한다.

- `DELIVERY_NOT_FOUND`: 「다시 전할 결과를 찾지 못했어요.」
- `DELIVERY_NOT_RETRYABLE`: 「지금은 이 결과를 다시 전할 수 없어요.」

### 7. 이 phase 를 검증하는 `test/unit/error-message.test.ts`

두 코드가 위 문구를 돌려주는지 단언을 더한다. 기존 단언은 고치지 않는다.

### 8. 이 phase 를 검증하는 `test/browser/chat-delivery-retry.spec.ts`

`chat-delegation-wake.spec.ts` 처럼 `page.route` 로 이력과 대화 단위 SSE 와 다시 전달 경로를 꾸민다. 결과 글은 지어낸 글이다.

| 검사 | 꾸민 응답 | 기대 |
| --- | --- | --- |
| 실패한 전달 | 마지막 `SYSTEM` 줄에 `delivery: { id: 7, status: "FAILED" }` | 그 줄 아래 「결과를 정리하지 못했어요」 와 「결과 다시 전달」. 다른 `SYSTEM` 줄에는 없음 |
| 중지한 전달 | `status: "STOPPED"` | 「결과 정리를 중지했어요」 와 같은 이름의 단추 |
| 상태가 없거나 끝난 전달 | `delivery` 없음, `DELIVERED`, `DELIVERING` | 상태 글과 단추가 없음 |
| 누르면 다시 전달한다 | 다시 전달 경로가 `system`, `started`, `delta`, `done` 을 보냄. 그 뒤 이력은 새 `SYSTEM` 줄(`DELIVERED`)과 `ASSISTANT` 줄을 더함 | 요청 경로가 `/api/chat/conversations/{id}/deliveries/7/retry`. 누른 직후 단추가 막힘. 답 글이 보이고 단추가 사라짐. 다시 전달 요청은 한 번 |
| 한도에 닿았다 | 다시 전달 경로가 409 `USER_BUSY` | 한도 안내가 보이고 단추가 다시 눌림 |
| 대화를 연 채 자동 turn 이 `started` 전에 실패했다 | 대화 단위 SSE 가 `system` 다음 `error`. 그 뒤 이력의 그 줄이 `FAILED` | 새로 고치지 않아도 단추가 보임 |

## 검증

```bash
# cwd: web/
pnpm lint
pnpm format:check
pnpm typecheck
pnpm test:browser ../test/browser/chat-delivery-retry.spec.ts
pnpm test:browser ../test/browser/chat-delegation-wake.spec.ts
pnpm test:browser ../test/browser/regenerate.spec.ts
```

```bash
# cwd: 저장소 root
node --test 'test/unit/**/*.test.ts'
scripts/quality.sh check
```

기대: 모두 종료 코드 0. 로컬에서는 고친 화면과 관련된 spec 만 돌린다. 전체 브라우저 검사는 PR 의 CI(`browser-mobile`, `browser-desktop`)가 머지 전 확인이다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/app/api/chat/conversations/[conversationId]/deliveries/[deliveryId]/retry/route.ts` | 신규 |
| `web/src/lib/chat-api.ts` | 수정 |
| `web/src/components/chat/message-bubble.tsx` | 수정 |
| `web/src/components/chat/message-list.tsx` | 수정 |
| `web/src/components/chat/conversation-session.tsx` | 수정 |
| `web/src/components/error-message.ts` | 수정 |
| `test/unit/error-message.test.ts` | 수정 |
| `test/browser/chat-delivery-retry.spec.ts` | 신규 |
