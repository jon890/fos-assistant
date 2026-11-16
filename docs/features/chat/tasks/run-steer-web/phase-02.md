# Phase 02. 답 위 steer 말풍선과 세 상태, 자식 안내, 다시 생성 숨기기

**Execution profile**: standard

## 목표

대화 단위 SSE 의 `steer` 사건과 이력 `MessageView.steer` 로 그 실행의 답 바로 위에 사용자 말풍선과 상태 글을 그린다. 자식 종류에 맞는 안내 한 줄을 붙이고, 답 앞 질문이 steer 말풍선이면 다시 생성 단추를 숨긴다.

**범위 외**: 대기 경로(phase 1), 가짜 Hermes(phase 3).

## 컨텍스트

**요구사항**: REQ1, REQ3, REQ4, REQ5, REQ7. 스펙 DECISION5, DECISION16, DECISION19, DECISION25.

**근거 문서**: `docs/features/chat/design.md` 의 「API」 표(상태 글과 안내 줄), 「화면」

- 이력은 행 번호 순서다. steer 행은 그 turn 의 답 행보다 먼저 저장된다. `foldVersions` 는 지금 모든 `USER` 를 turn 을 여는 줄로 보므로 steer 행이 질문과 답을 가른다.
- 다시 생성은 서버가 마지막 답 앞의 가장 가까운 `USER` 행을 질문으로 고른다(`ChatTurnRouting.previousQuestion`). 그 행이 steer 면 `MESSAGE_NOT_LATEST`.
- 도는 동안 보낸 질문과 흐르는 답은 문자열 id 의 임시 줄이다. `MessageList` 는 저장된 줄(숫자 id)을 접은 뒤 임시 줄을 뒤에 붙인다.
- 대화 단위 SSE 의 사건은 이 창이 보낸 turn 이 도는 동안 `runConversationTask` 가 보류한다. steer 사건은 그 turn 중에 보여야 하므로 보류하지 않는다.

## 작업 항목

1. `web/src/lib/chat-event.ts`: `steer` 사건과 `steer` 칸. `message-types.ts` 의 `Turn.steer`.
2. `web/src/lib/steer.ts`(신규): 상태 글과 안내 줄 표, `applySteerEvent(turns, event)`. 이미 있는 말풍선(숫자 id 나 `steer-<번호>`)이면 상태만 바꾸고, 그 실행의 저장된 답이 있으면 그 앞에, 흐르는 답이 있으면 그 앞에 `steer-<번호>` 로, 둘 다 없으면 끝에 넣는다.
3. `web/src/lib/message-versions.ts`: steer 행은 turn 을 열지 않고 그 turn 의 `steers` 로 간다. 답 판 사슬마다 마지막 행 번호(`latestId`)를 함께 준다.
4. `web/src/components/chat/visible-turns.ts`(신규): `MessageList` 의 보일 줄 계산을 옮기고, turn 안에서 steer 와 답을 행 번호로 섞는다. steer 뒤의 답은 `afterSteer`.
5. `message-list.tsx`: `afterSteer` 면 다시 생성 단추가 없다. 마지막 줄이 steer 면 「답을 받지 못했어요」 를 그리지 않는다.
6. `steer-status.tsx`(신규)와 `message-bubble.tsx`: steer 말풍선 아래 상태 글과 안내 줄.
7. `use-conversation-effects.ts`: `steer` 사건을 보류하지 않고 `applySteerEvent` 로 반영한다.

## 검증

```bash
# cwd: web/
pnpm exec tsx --test test/unit/steer.test.ts test/unit/message-versions.test.ts
pnpm typecheck && pnpm lint && pnpm format:check
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/lib/chat-event.ts`, `web/src/components/chat/message-types.ts` | 수정 |
| `web/src/lib/steer.ts` | 신규 |
| `web/src/lib/message-versions.ts` | 수정 |
| `web/src/components/chat/visible-turns.ts`, `steer-status.tsx` | 신규 |
| `web/src/components/chat/message-list.tsx`, `message-bubble.tsx`, `use-conversation-effects.ts` | 수정 |
| `web/test/unit/steer.test.ts`, `message-versions.test.ts` | 신규·수정 |
