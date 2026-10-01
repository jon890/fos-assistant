# Phase 05. 화면에서 응답 중에 보내고 대기 줄을 다룬다

**Execution profile**: deep

## 목표

답이 오는 동안에도 보내기를 누를 수 있게 한다.
보낸 글은 입력창 위 대기 줄에 보이고, 취소하면 입력창으로 돌아온다.
중지한 뒤에는 대기 줄에 「보내기」 가 나온다.

**범위 외**: 대기 메시지에 사진 붙이기, 대기 메시지 편집, Hermes `steer`.

## 컨텍스트

화면 코드는 `web/` 아래다. 규칙은 `web/AGENTS.md` 가 갖는다. 이 phase 에 걸리는 것만 적는다.

- 브라우저는 Control Plane 을 직접 부르지 않는다. `web/src/app/api/` 의 서버 라우트를 거친다. 본보기는 `web/src/app/api/chat/conversations/[conversationId]/running/route.ts`(GET)와 `web/src/app/api/chat/conversations/[conversationId]/model/route.ts`(본문이 있는 요청)다.
- `web/src/components/**` 에서 `fetch` 를 직접 부르면 lint error 다. `web/src/lib/` 의 함수를 거친다. `chat-panel.tsx` 와 `composer.tsx` 의 기존 직접 호출은 기준 파일(`web/eslint-suppressions.json`)에 든 옛 위반이다. 한 줄이라도 더하면 기준을 넘는다.
- 서버 라우트에서 `NextResponse.json({ code, ... })` 와 `request.json()` 을 직접 쓰면 lint error 다. 본문은 `web/src/lib/json-body.ts` 의 `readJsonBody` 로 읽고, Control Plane 응답은 `web/src/lib/control-plane.ts` 의 `forwardControlPlane` 으로 그대로 넘긴다. 본보기 라우트의 직접 호출은 옛 위반이다. `web/eslint-suppressions.json` 에 이름이 없는 라우트를 하나 골라 모양을 따른다.
- 인라인 `style={{` 를 쓰지 않는다. 색과 간격은 `globals.css` 의 토큰과 Tailwind 클래스로 쓴다.
- 문구는 해요체다. 오류는 사용자가 할 일만 알린다.
- 운영 코드에 시험 전용 우회를 만들지 않는다.

지금 구조다.

- `web/src/components/chat-panel.tsx`
  - `draft` 가 입력창의 글이다. `Composer` 에 `value={draft} onChange={setDraft}` 로 준다.
  - `sending` 이 응답 중 판정이다. 보낸 turn, 보는 중인 turn, 자동 turn 이 모두 켠다. `Composer` 에 `running={sending}` 으로 준다.
  - `send(attachmentIds: number[], replacementText?: string): Promise<boolean>` 의 첫 줄이 `sending` 이면 거짓을 돌려준다.
  - `restoreFailedMessage` 가 실패한 글을 `setDraft(text)` 로 되돌린다. `reportRejected(code, message)` 가 `setError(describeError(...))` 로 알린다.
  - 대화 단위 SSE 는 `/api/chat/conversations/${id}/events` 를 `readEventStream` 으로 읽고 사건마다 `runConversationTask(() => applyConversationEvent(id, event))` 를 부른다. 이 창이 보낸 turn 이 도는 동안의 사건은 `conversationTasks` 에 쌓였다가 `finishSentTurn` 이 처리한다.
  - `applyConversationEvent` 는 `system` 을 알림 줄로 더하고, `started` 로 `autoTurn` 을 만들어 `setSending(true)` 를 하고, 나머지를 `applyTurnEvent` 로 넘긴다. 요청한 연결 없이 도는 turn 은 이 길로 그려진다.
  - 대화를 열 때 `/running` 을 이력보다 먼저 묻고, 돌고 있으면 `beginObserving` 으로 보는 중 상태가 된다.
  - 입력창 둘레는 `error` 문단, `observing-notice`, `<Composer>` 순서다.
- `web/src/components/chat/composer.tsx`
  - `onKeyDown` 의 조건에 `|| running` 이 있어 응답 중에는 Enter 가 `trySend()` 를 부르지 않는다.
  - 단추는 `running ? <중지> : <보내기>` 다. 중지가 보내기 자리를 대신한다.
  - `sendDisabled = disabled || value.trim().length === 0 || blocking`.
  - 사진은 응답 중에 잠긴다(`handleFiles`, `removeItem` 의 `if (running) return`, 입력과 단추의 `disabled || running`). 이것은 그대로 둔다.
- `web/src/lib/chat-event.ts` 의 `ChatEvent` 가 type 목록을 갖는다.
- `web/src/components/error-message.ts` 의 `MESSAGES` 가 오류 코드를 문구로 바꾼다.
- 브라우저 검사는 `test/browser/` 에 있고 실제 Control Plane 과 가짜 Hermes 를 띄운다. fixture `hermes` 가 `holdNextRun()`, `waitForHeldRun()`, `releaseHeldRun()` 을 준다. 본보기는 `test/browser/stop.spec.ts` 다. 프로젝트는 `mobile` 과 `desktop` 이다.

Control Plane 의 경로와 사건이다(Phase 03 이 만들었다).

| 경로 | 응답 |
| --- | --- |
| `GET /api/v1/chat/conversations/{id}/pending` | 200 `{ "held": boolean, "items": [{ "id": number, "text": string, "createdAt": string }] }` |
| `POST /api/v1/chat/conversations/{id}/pending` 본문 `{ "text" }` | 201 대기 줄. 409 `PENDING_QUEUE_FULL`, 409 `CONVERSATION_BUSY`(흐름이 붙은 에이전트) |
| `DELETE /api/v1/chat/conversations/{id}/pending/{pendingId}` | 204. 404 `PENDING_MESSAGE_NOT_FOUND` |
| `POST /api/v1/chat/conversations/{id}/pending/send` | 202 대기 줄 |

| 사건 `type` | 뜻 | 칸 |
| --- | --- | --- |
| `user` | 대기 메시지를 합쳐 사용자 메시지로 저장했다 | `conversationId`, `messageId`, `text` |
| `pending` | 대기 줄이 바뀌었다 | `conversationId` |

둘 다 대화 단위 SSE 로만 온다. 대기 메시지로 연 turn 의 `started`, `delta`, `done` 도 그 스트림으로 온다.

**근거 문서**: `docs/flow.md` 의 「응답 중에 보낼 때」 절(갈리는 지점 표가 화면 계약이다)과 「실행이 실패할 때」 의 오류 코드 표, `docs/code-architecture.md` 의 「응답 중 대기열」 절, `web/AGENTS.md`

## 의도 메모

- 대기 줄을 화면 상태로 기억하지 않는다. 저장된 것을 다시 읽어 보인다. 다른 창과 맞추는 길이 `pending` 사건 하나로 끝난다.
- 응답 중의 보내기를 `send` 안의 분기로 넣지 않고 대기 경로를 따로 둔다. `send` 는 스트림과 임시 줄과 끊김 처리를 함께 갖고 있어 분기가 섞이면 읽기 어렵다.
- `chat-panel.tsx` 는 이미 1400줄이다. 대기 줄 상태는 훅으로, 그리기는 부품으로 뺀다.
- 구현이 `docs/` 의 계약과 달라져야 하면 고치기 전에 계획을 쓴 쪽에 알린다. 알린 뒤 같은 커밋에서 그 절을 고친다.

## 작업 항목

### 1. 서버 라우트 셋

- `web/src/app/api/chat/conversations/[conversationId]/pending/route.ts`: `GET` 과 `POST`. `POST` 는 Control Plane 의 201 을 그대로 201 로 돌려준다.
- `web/src/app/api/chat/conversations/[conversationId]/pending/[pendingId]/route.ts`: `DELETE`. `pendingId` 가 양의 정수가 아니면 400 `VALIDATION_FAILED`. 성공은 204.
- `web/src/app/api/chat/conversations/[conversationId]/pending/send/route.ts`: `POST`. 본문 없이 넘기고 202 를 돌려준다.

셋 다 `isConversationId` 로 대화 식별자를 먼저 본다.

### 2. `web/src/lib/pending-messages.ts` 신규

```ts
export type PendingMessage = { id: number; text: string; createdAt: string };
export type PendingQueue = { held: boolean; items: PendingMessage[] };
export type PendingResult<T> = { ok: true; data: T } | { ok: false; code: string; message: string };

export function fetchPendingQueue(conversationId: string, signal?: AbortSignal): Promise<PendingResult<PendingQueue>>;
export function enqueuePending(conversationId: string, text: string): Promise<PendingResult<PendingQueue>>;
export function cancelPending(conversationId: string, pendingId: number): Promise<PendingResult<null>>;
export function sendPending(conversationId: string): Promise<PendingResult<PendingQueue>>;
```

fetch 가 던지면 `{ ok: false, code: "NETWORK", message: "" }` 를 돌려준다. 실패 응답은 본문의 `code` 와 `message` 를 싣는다.

### 3. `web/src/lib/chat-event.ts`

type 목록에 `"user"` 와 `"pending"` 을 더한다.

### 4. `web/src/components/error-message.ts`

- `PENDING_QUEUE_FULL`: 「대기 중인 메시지가 가득 찼어요. 답이 끝난 뒤 보내 주세요.」
- `PENDING_MESSAGE_NOT_FOUND`: 「이미 보낸 메시지예요.」

### 5. `web/src/components/chat/use-pending-queue.ts` 신규

`usePendingQueue(conversationId: string | null)` 가 `{ queue, reload, enqueue, cancel, release }` 를 준다.

- `conversationId` 가 바뀌면 대기 줄을 비우고 다시 읽는다. 늦게 온 응답은 버린다.
- `enqueue`, `release` 는 응답의 대기 줄로 상태를 바꾼다. `cancel` 은 성공하면 그 줄을 빼고 그 글을 돌려준다.
- 실패는 `PendingResult` 를 그대로 돌려 부르는 쪽이 문구를 정하게 한다.

### 6. `web/src/components/chat/pending-queue.tsx` 신규

`PendingQueueView({ queue, onCancel, onRelease, busy })` 다. `queue.items` 가 비면 아무것도 그리지 않는다.

- 뿌리에 `data-testid="pending-queue"`, 줄마다 `data-testid="pending-item"`.
- 줄은 글(길면 한 줄로 자른다)과 취소 단추다. 취소 단추의 `aria-label` 은 「대기 메시지 취소」 다.
- 이 단추는 이름에 「보내기」 를 담는다. 기존 검사는 `getByRole("button", { name: "보내기" })` 를 `exact` 없이 쓰므로, 새 검사는 입력창의 보내기를 `composer-shell` 안으로 한정해 고른다.
- `queue.held` 가 참이면 「중지해서 보내지 않았어요.」 문구와 단추 `data-testid="pending-release"`(글자 「보내기」, `aria-label` 「대기 메시지 보내기」)를 보인다.
- 멈추지 않았으면 「답이 끝나면 보내요.」 문구를 보인다.
- 단추는 `web/src/components/ui/` 의 부품을 쓴다.

### 7. `web/src/components/chat/composer.tsx`

- props 에 `canQueue: boolean` 을 더한다. 응답 중에 보내기를 받을 수 있는지다.
- `onKeyDown` 의 `|| running` 을 `|| (running && !canQueue)` 로 바꾼다.
- 단추: 보내기는 언제나 그린다. `running` 이면 그 옆에 중지를 함께 그린다. 보내기의 `disabled` 는 `sendDisabled || (running && !canQueue)` 다.
- 사진 잠금은 그대로 둔다.

### 8. `web/src/components/chat-panel.tsx`

- `usePendingQueue(conversationId)` 를 쓴다.
- `Composer` 에 `canQueue={conversationId !== null}` 을 준다.
- `Composer` 의 `onSend` 가 부르는 자리에서 경로를 고른다.
  - `sending` 이 참이거나 `queue.held` 가 참이면 대기 경로다. 첨부 번호가 있으면 대기 경로를 쓰지 않고 지금처럼 거짓을 돌려준다.
  - 대기 경로: 글을 `enqueue` 로 보낸다. 성공하면 `setDraft("")` 하고 참을 돌려준다. `queue.held` 였으면 이어서 `release` 를 부른다. 실패하면 글을 그대로 두고 `setError(describeError(code, message))` 를 한 뒤 거짓을 돌려준다. 대기 경로의 `CONVERSATION_BUSY` 는 흐름이 붙은 에이전트라 받지 않는다는 뜻이다. 그때는 「이 대화는 답이 끝난 뒤 보낼 수 있어요.」 를 보인다.
  - 그 밖에는 지금의 `send` 다.
- `send` 가 `started` 전에 `CONVERSATION_BUSY` 로 거절됐고 첨부가 없으면, 글을 되돌리는 대신 대기 경로로 다시 넣는다. 자동 turn 이 막 열린 순간에 보낸 경우다.
- 취소: `cancel` 이 성공하면 그 글을 입력창에 되돌린다. `draft` 가 비어 있으면 그 글로, 아니면 `draft` 뒤에 줄을 바꿔 붙인다. `PENDING_MESSAGE_NOT_FOUND` 면 되돌리지 않고 `reload` 한다.
- `applyConversationEvent`:
  - `pending`: `reload()`. **`runConversationTask` 에 넣지 않는다.** `readEventStream` 콜백에서 type 이 `pending` 이면 곧바로 `reload()` 하고 끝낸다. 이 창이 보낸 turn 이 도는 동안에는 `runConversationTask` 가 사건을 보류하므로, 거기 넣으면 다른 창이 쌓은 대기 메시지가 turn 이 끝날 때까지 보이지 않는다.
  - `user`: `{ id: event.messageId, role: "USER", content: event.text }` 줄을 더한다. 같은 id 가 있으면 건너뛴다. `system` 처리와 같은 모양이다.
- `<PendingQueueView>` 를 `observing-notice` 와 `<Composer>` 사이에 둔다.
- 「다른 창에서 답하는 중」 일 때도 입력창은 잠기지 않는다. 그 안내 문구는 그대로 둔다.
- 질문 카드의 `onAnswer` 도 `Composer` 의 `onSend` 와 같은 자리에서 경로를 고른다. 질문 카드는 응답 중에도 보이므로 그때 고른 답은 대기 메시지로 들어간다. 추천 질문의 `onPrompt` 는 지금처럼 `send` 를 부른다.

### 9. 이 phase 를 검증하는 `test/browser/chat-queue.spec.ts` 신규

`stop.spec.ts` 의 `beginHeldTurn` 방식으로 turn 을 붙잡는다. 검사 끝에 붙잡은 run 을 남기지 않는다.

| 검사 | 기대 |
| --- | --- |
| 답이 오는 동안 보내면 대기 줄에 쌓이고 답이 끝나면 합쳐 간다 | turn 을 붙잡은 뒤 입력창이 쓸 수 있고 `composer-shell` 안에 「보내기」 와 「중지」 가 함께 보인다. 두 글을 차례로 보내면 `pending-item` 이 둘이고 입력창이 빈다. 푼 뒤 `pending-queue` 가 사라지고, 두 글을 담은 사용자 메시지 하나와 그 답이 보인다. 새로 고쳐도 같다 |
| 대기 메시지를 취소하면 글이 입력창으로 돌아온다 | 한 글을 대기시킨 뒤 「대기 메시지 취소」 를 누르면 `pending-item` 이 0 이고 입력창의 값이 그 글이다. 푼 뒤 그 글의 사용자 메시지가 생기지 않는다 |
| 중지하면 대기 줄이 멈추고 보내기로 이어 보낸다 | 한 글을 대기시키고 「중지」 를 누른다. `stopped-mark` 가 보인 뒤 `pending-release` 가 보이고 새 답이 시작되지 않는다. `pending-release` 를 누르면 그 글의 사용자 메시지와 답이 보인다 |
| Enter 로도 대기시킨다 | turn 을 붙잡은 채 글을 쓰고 Enter 를 누르면 `pending-item` 이 하나다 |
| 대기 줄이 가득 차면 글을 되돌린다 | `**/api/chat/conversations/*/pending` 의 POST 를 `page.route` 로 409 `PENDING_QUEUE_FULL` 로 채운다. 보낸 글이 입력창에 남고 「대기 중인 메시지가 가득 찼어요」 가 보인다 |
| 흐름이 붙은 에이전트라 받지 않으면 까닭을 알린다 | 같은 POST 를 409 `CONVERSATION_BUSY` 로 채운다. 글이 입력창에 남고 「이 대화는 답이 끝난 뒤 보낼 수 있어요」 가 보인다 |
| 다른 창에서 쌓은 대기 메시지가 보인다 | turn 을 붙잡은 뒤 `page.request.post` 로 그 대화의 `/api/chat/conversations/{id}/pending` 에 글을 넣는다. 화면에 `pending-item` 이 하나 보인다 |

### 10. 응답 중에 「보내기」 가 없다고 단언하는 기존 검사

`git grep -n "보내기" -- test/browser` 로 찾는다. 쓰임은 둘이다. 응답 중에 「보내기」 가 0개라는 단언과, 「보내기」 가 보이는 것을 turn 이 끝났다는 기다림으로 쓰는 것이다. 둘 다 「중지」 의 유무로 바꾼다. 아래는 확인한 자리다. 다른 파일에 같은 쓰임이 있으면 함께 고치고 「변경 파일」 에 더한다.

- `test/browser/stop.spec.ts`: 「보내기」 가 0개라는 단언을 「중지」 가 보인다는 단언으로 남기고, 끝난 뒤의 판정을 「중지」 가 0개인 것으로 바꾼다.
- `test/browser/chat-delegation-wake.spec.ts`: 자동 turn 이 도는 동안 「보내기」 가 0개라는 단언 셋(236, 240, 337줄 근처)과, 끝난 뒤 「보내기」 가 보인다는 단언(119, 243, 340줄 근처)을 「중지」 의 유무로 바꾼다. 검사 이름의 「보내기를 막는다」 도 고친다.
- `test/browser/observe-running.spec.ts`: 132줄 근처의 0개 단언과, 49, 137, 171, 234, 286, 318, 338줄 근처의 기다림.
- `test/browser/chat-attachment.spec.ts` 203줄 근처, `test/browser/chat.spec.ts` 171줄 근처의 같은 쓰임.
- `test/browser/ask-card.spec.ts`: 「답이 끝나 보내기 단추가 돌아온 뒤에 입력한다」 는 기다림이 이제 곧바로 통과한다. 「중지」 가 0개가 될 때까지 기다리게 바꾼다.

### 11. `docs/code-architecture.md`

「web 화면 구조」 절의 디렉터리 설명에 이 phase 가 더한 파일이 빠져 있으면 한 줄씩 더한다. 「응답 중 대기열」 절의 웹 줄과 파일 이름이 실제와 같은지 본다.

## 검증

```bash
cd web && pnpm typecheck && pnpm lint && pnpm format:check
cd web && pnpm test:browser
git grep -n 'style={{' -- web/src
```

- 첫 줄은 오류 없이 끝나야 한다. lint 의 기준 파일(`web/eslint-suppressions.json`)에 줄을 더하지 않는다.
- 브라우저 검사는 한 기계에서 한 번에 하나만 돈다. 다른 브라우저 검사가 돌고 있으면 끝난 뒤에 돌린다.
- 둘째 줄은 브라우저 검사 전체다. `mobile` 과 `desktop` 두 프로젝트에서 모두 통과해야 한다. 응답 중의 단추 구성이 바뀌어 여러 검사의 전제가 달라지므로 일부만 돌리지 않는다.
- 마지막 줄은 아무것도 내지 않아야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/app/api/chat/conversations/[conversationId]/pending/route.ts` | 신규 |
| `web/src/app/api/chat/conversations/[conversationId]/pending/[pendingId]/route.ts` | 신규 |
| `web/src/app/api/chat/conversations/[conversationId]/pending/send/route.ts` | 신규 |
| `web/src/lib/pending-messages.ts` | 신규 |
| `web/src/lib/chat-event.ts` | 수정 |
| `web/src/components/error-message.ts` | 수정 |
| `web/src/components/chat/use-pending-queue.ts` | 신규 |
| `web/src/components/chat/pending-queue.tsx` | 신규 |
| `web/src/components/chat/composer.tsx` | 수정 |
| `web/src/components/chat-panel.tsx` | 수정 |
| `test/browser/chat-queue.spec.ts` | 신규 |
| `test/browser/stop.spec.ts` | 수정 |
| `test/browser/chat-delegation-wake.spec.ts` | 수정 |
| `test/browser/ask-card.spec.ts` | 수정 |
| `test/browser/observe-running.spec.ts` | 수정 |
| `test/browser/chat-attachment.spec.ts` | 수정 |
| `test/browser/chat.spec.ts` | 수정 |
| `docs/code-architecture.md` | 수정 |
