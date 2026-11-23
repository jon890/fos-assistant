# Phase 02. 화면이 읽기 전용 대화에 입력창 대신 안내를 보이고 작업 화면의 에이전트 칸을 지우며 문서를 맞춘다

**Execution profile**: standard

## 목표

`writable` 이 거짓인 대화는 입력창 대신 「이 대화는 더 이어 쓸 수 없어요. 비서에게 이어서 물어보세요」 와 대화방 링크를 보이고, 다시 생성·다시 시도·결과 다시 전달 단추와 질문 카드의 답 고르기가 없다. 작업 화면에 에이전트 칸이 없다. 기능 문서가 바뀐 동작을 적는다.

**범위 외**: PR3 의 연결과 스킬.

## 컨텍스트

**요구사항**: REQ5, REQ7

**근거 문서**: `docs/features/assistant-hub/spec.md`(REQ5, REQ7, DECISION4), `docs/features/assistant-hub/design.md` 의 「PR2 입구를 비서로 모으기」

- 대화 줄 타입: `web/src/components/shell/conversations-provider.tsx` 의 `Conversation`.
- 대화 화면: `web/src/components/chat/conversation-session-view.tsx`, `message-list.tsx` 의 다시 생성·다시 전달·답 고르기.
- 대화방 주소: `web/src/lib/room.ts`.
- 작업 화면: `web/src/components/task/task-form.tsx`, `web/src/lib/task.ts`.
- 문서: `docs/features/assistant-hub.md`, `docs/features/chat.md`, `docs/features/schedule.md`.

## 작업 항목

### 1. 읽기 전용 대화

- `Conversation` 에 `writable?: boolean` 을 더한다(옛 응답이나 `replace` 줄은 비어 있을 수 있어 비면 쓸 수 있다고 본다).
- 쓸 수 없는 대화는 입력창, 대기 줄, 답장 칩 자리에 안내 한 줄과 대화방 링크를 보인다(새 부품 `read-only-notice.tsx`).
- 메시지 목록에 다시 생성·다시 시도·결과 다시 전달 단추를 그리지 않고 질문 카드의 답 고르기를 막는다.

### 2. 작업 화면

- `task-form.tsx` 의 에이전트 칸과 `agentCode` 보내기를 지운다. `TaskRequest` 타입에서 `agentCode` 를 지운다. 목록과 상세는 작업의 에이전트 이름을 그대로 보인다.

### 3. 문서

- `docs/features/assistant-hub.md`: 「입구를 비서로 모으기」 절에 읽기 전용 대화와 예약 작업 줄을 더하고 요구 줄을 고친다.
- `docs/features/chat.md`, `docs/features/schedule.md`: 읽기 전용 대화와 작업의 에이전트를 지금 동작으로 고친다.
- 끝난 계획은 PR 에서 지운다.

### 4. 테스트

- 새 `web/test/browser/read-only-conversation.spec.ts`: 쇼핑 담당과의 지난 대화를 열면 입력창 대신 안내와 대화방 링크가 보이고 다시 생성 단추가 없다. 비서 대화는 입력창이 있다.
- `web/test/browser/tasks.spec.ts`: 만들기 화면에 에이전트 칸이 없고 만든 작업이 비서로 보인다.
- 비서가 아닌 에이전트와의 대화로 보내던 브라우저 테스트(test-support 지난 대화)를 기본 비서를 두는 방식으로 고친다.

## 검증

```bash
cd web && pnpm lint && pnpm typecheck && pnpm test:node
cd web && pnpm test:browser test/browser/read-only-conversation.spec.ts test/browser/tasks.spec.ts
scripts/dev/check-local.sh --fast
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `docs/features/assistant-hub.md` | 수정 |
| `docs/features/chat.md` | 수정 |
| `docs/features/schedule.md` | 수정 |
| `docs/features/service-concepts/design.md` | 수정 |
| `docs/features/service-concepts/spec.md` | 수정 |
| `web/src/components/chat/conversation-session-view.tsx` | 수정 |
| `web/src/components/chat/message-list.tsx` | 수정 |
| `web/src/components/chat/read-only-notice.tsx` | 신규 |
| `web/src/components/chat/use-conversation-todo.tsx` | 수정 |
| `web/src/components/shell/conversations-provider.tsx` | 수정 |
| `web/src/components/task/task-form.tsx` | 수정 |
| `web/src/lib/task.ts` | 수정 |
| `web/test/browser/agent-introduction.spec.ts` | 수정 |
| `web/test/browser/agent-lifecycle.spec.ts` | 수정 |
| `web/test/browser/fixture-isolation.spec.ts` | 수정 |
| `web/test/browser/fixtures.ts` | 수정 |
| `web/test/browser/helpers.ts` | 수정 |
| `web/test/browser/read-only-conversation.spec.ts` | 신규 |
| `web/test/browser/skill-command.spec.ts` | 수정 |
| `web/test/browser/tasks.spec.ts` | 수정 |
