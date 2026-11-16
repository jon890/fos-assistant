# Phase 01. 입력창 글만 steer 표시를 싣고 대기 칸을 맞춘다

**Execution profile**: standard

## 목표

입력창에 쓴 글만 `POST .../pending` 에 `steer: true` 를 싣는다. 대기 칸은 멈추지 않은 줄에서 말풍선 번호가 붙은 글을 그리지 않고, 「끼우는 중」 글의 취소를 막는다.

**범위 외**: 말풍선(phase 2), 가짜 Hermes(phase 3).

## 컨텍스트

**요구사항**: REQ1, REQ2, REQ4, REQ6. 스펙 DECISION4, DECISION17, DECISION24.

**근거 문서**: `docs/features/chat/design.md` 의 「API」, 「화면」

- 대기 경로는 둘이다. `web/src/components/chat/use-conversation-queue.ts` 의 `submit`(답이 도는 중)과 `use-conversation-send.ts` 의 `CONVERSATION_BUSY` 재시도.
- 입력창의 글만 보낼 때 `submit` 은 `replacementText` 없이 불린다. 질문 카드 답, 넘기기 「다 했어요」, 할 일 패널은 글을 인자로 준다.
- 서버 계약: 요청 `{ text, steer }`, 대기 줄 응답의 글에 `messageId`(없으면 null)와 `steering`.

## 작업 항목

1. `web/src/lib/pending-messages.ts`: `PendingMessage` 에 `messageId?`, `steering?`. `enqueuePending(id, text, steer)` 가 본문에 싣는다. 대기 칸에 보일 글을 고르는 순수 함수 `visiblePendingItems(queue)`.
2. `web/src/app/api/chat/conversations/[conversationId]/pending/route.ts`: `steer` 를 불리언일 때만 넘긴다.
3. `use-pending-queue.ts`: `enqueue(text, steer)`.
4. `use-conversation-queue.ts`: `queueMessage(text, fromDraft, source)`. `source` 가 `"composer"` 일 때만 `steer: true`. `submit` 은 `replacementText === undefined` 일 때 `"composer"`.
5. `use-conversation-send.ts`: `CONVERSATION_BUSY` 재시도가 같은 기준으로 출처를 넘긴다.
6. `pending-queue.tsx`: `visiblePendingItems` 로 그리고, `steering` 글은 「끼우는 중」 과 막힌 취소 단추.

## 검증

```bash
# cwd: web/
pnpm exec tsx --test test/unit/pending-queue-visibility.test.ts
pnpm typecheck && pnpm lint
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/lib/pending-messages.ts` | 수정 |
| `web/src/app/api/chat/conversations/[conversationId]/pending/route.ts` | 수정 |
| `web/src/components/chat/use-pending-queue.ts` | 수정 |
| `web/src/components/chat/use-conversation-queue.ts` | 수정 |
| `web/src/components/chat/use-conversation-send.ts` | 수정 |
| `web/src/components/chat/pending-queue.tsx` | 수정 |
| `web/test/unit/pending-queue-visibility.test.ts` | 신규 |
