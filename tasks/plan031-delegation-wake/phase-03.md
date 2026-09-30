# Phase 03. 대화 단위 SSE 와 알림 줄을 화면에 보인다

**Execution profile**: standard

## 목표

열린 대화 창이 자동 turn 을 실시간으로 받는다.
`SYSTEM` 메시지는 회색 알림 줄로, 그 뒤의 답은 보통 답처럼 보인다.

**범위 외**: 자동 turn 을 여는 판정(phase 02). 사용자 turn 을 다른 창에 푸시하는 것(지금처럼 `/running` 폴링을 쓴다).

## 컨텍스트

phase 02 가 만든 `chat/application/ConversationEventHub`(`subscribe(conversationId, consumer)` → 해제 `Runnable`)와 `ChatEvent` 의 `system` 사건을 쓴다.

- 백엔드 SSE 는 `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatEventStreams.java` 가 연다. `SseEmitter(0L)` 와 가상 스레드로 일을 돌리고 20초마다 `: ping` 을 보낸다. 새 엔드포인트는 이 클래스의 방식을 따르되, 일을 돌리는 대신 구독을 걸고 연결이 끝나면 해제한다.
- 컨트롤러는 `chat/presentation/ChatController.java` 다. 대화 경로는 `@PathVariable UUID conversationId` 를 받고 `access.requireOwnId(user, conversationId)` 로 주인 확인과 번호 변환을 한다(`running` 메서드 참고).
- 웹 라우트는 `web/src/app/api/chat/conversations/[conversationId]/` 아래에 있다. `running/route.ts` 가 `isConversationId` 로 주소를 검사하는 방식과 `web/src/app/api/chat/stream/route.ts` 가 SSE 본문을 그대로 넘기는 방식을 합친다.
- 웹 사건 파서는 `web/src/lib/stream.ts` 의 `readEventStream`, 사건 타입은 `web/src/lib/chat-event.ts` 의 `ChatEvent` 다.
- 메시지는 `web/src/lib/message-versions.ts` 의 `foldVersions` 가 `USER` 를 기준으로 turn 으로 접는다. `role` 타입이 `"USER" | "ASSISTANT"` 다. `web/src/components/chat/message-bubble.tsx` 와 `web/src/components/chat-panel.tsx` 도 같은 타입을 쓴다.

**근거 문서**: `docs/flow.md` 의 「위임 결과가 도착했을 때」 절의 「대화 창이 열려 있다」 와 「대화 창이 닫혀 있다」 행,
`docs/code-architecture.md` 의 「위임 결과로 부모 대화를 깨우기」 절 표의 웹 두 행

## 의도 메모

- 대화 단위 SSE 는 자동 turn 만 싣는다. 사용자가 보낸 turn 을 여기에도 실으면 보낸 창이 같은 답을 두 번 그린다.
- 알림 줄은 turn 의 시작점이다. `foldVersions` 가 `SYSTEM` 을 `USER` 처럼 turn 을 여는 줄로 다루되, 사용자 말풍선이 아니라 알림 줄로 그린다. 알림 줄에는 다시 생성과 판 넘기기가 없다.
- 자동 turn 이 도는 동안에는 입력창의 보내기가 `CONVERSATION_BUSY` 로 거절된다. 지금 다른 창이 쓰는 「답하는 중」 표시를 그대로 쓴다.

## 작업 항목

### 1. `ChatController` — `GET /api/v1/chat/conversations/{conversationId}/events`

- `produces = TEXT_EVENT_STREAM_VALUE`. 주인 확인 뒤 `SseEmitter(0L)` 를 만들고 `hub.subscribe(number, event -> emitter.send(...))` 를 건다. 사건 직렬화는 `ChatEventStreams` 와 같은 `data: {json}` 한 줄이다.
- 20초마다 `: ping` 을 보낸다. `onCompletion`, `onTimeout`, `onError` 에서 구독을 해제하고 ping 을 멈춘다.
- 남의 대화나 없는 대화는 기존 경로와 같은 오류(`requireOwnId` 가 던지는 것)다.

### 2. `web/src/app/api/chat/conversations/[conversationId]/events/route.ts` — 그대로 넘김

- `GET`. `isConversationId` 검사 뒤 Control Plane 의 같은 경로를 SSE 로 열고 본문을 그대로 넘긴다. 헤더는 `stream/route.ts` 와 같게 둔다. 브라우저가 끊으면 upstream 도 끊는다(`request.signal` 을 넘긴다).

### 3. 웹 타입과 접기

- `web/src/lib/chat-event.ts` 의 `type` 에 `"system"` 을 더하고 `content`, `messageId` 칸을 받는다.
- `role` 타입을 `"USER" | "ASSISTANT" | "SYSTEM"` 으로 넓힌다(`message-versions.ts`, `message-bubble.tsx`, `chat-panel.tsx` 의 `Turn`).
- `foldVersions`: `USER` 와 `SYSTEM` 을 모두 turn 을 여는 줄로 다룬다. `SYSTEM` 은 판 사슬을 만들지 않는다(`replacesMessageId` 가 없다). 반환 타입에 여는 줄이 알림인지 알 수 있는 값을 둔다.
- `chat-panel.tsx` 의 `answerAfterLastQuestion` 과 `lastQuestionIsNew` 는 계속 `USER` 만 질문으로 본다.

### 4. `web/src/components/chat-panel.tsx` — 구독

- 대화가 열려 있는 동안(`conversationId` 가 있을 때) `/api/chat/conversations/{id}/events` 를 `fetch` 로 열고 `readEventStream` 으로 읽는다. 대화가 바뀌거나 화면이 닫히면 `AbortController` 로 끊는다. 끊긴 뒤에는 5초 뒤 다시 연다.
- `system` 사건: 알림 줄을 목록 끝에 더한다.
- `started`, `delta`, `tool`, `done`, `stopped`, `error`: 지금 보낸 turn 을 그리는 코드와 같은 방식으로 새 답을 그린다. 같은 처리를 함수로 꺼내 두 곳이 함께 쓰게 한다. `done` 뒤에는 지금처럼 메시지를 다시 읽어 저장된 번호로 맞춘다.

### 5. `web/src/components/chat/message-bubble.tsx` — 알림 줄

- `SYSTEM` 은 가운데 정렬의 작은 회색 글 한 줄로 그린다. 복사, 다시 생성, 판 넘기기 버튼이 없다. 기존 디자인 토큰(`text-muted-foreground` 등)을 쓴다.
- 자동 turn 의 답은 보통 답과 같이 그리되 다시 생성 버튼을 숨긴다(앞 줄이 `SYSTEM` 인 답).

### 6. 테스트

- 백엔드: `ChatController` 의 새 경로를 `backend/src/test/java/com/bifos/assistant/chat/` 에 더한다. 주인이 구독하면 `hub.publish` 한 `system` 사건을 받고, 남의 대화는 거절된다.
- 브라우저: `test/browser/chat-delegation-wake.spec.ts`(신규). 기존 `test/browser/chat.spec.ts` 의 준비와 가짜 서버를 따른다.
  - 대화를 연 채로 대화 단위 SSE 에 `system` 과 답 사건이 오면 알림 줄과 답이 보인다
  - 저장된 메시지에 `SYSTEM` 이 있으면 알림 줄로 보이고, 그 뒤 답에는 다시 생성 버튼이 없다
  - 모바일과 데스크톱 두 폭에서 알림 줄이 가로로 넘치지 않는다
- `web/src/lib/message-versions.ts` 의 접기 규칙에 단위 테스트가 있으면(`grep -rln "foldVersions" test web`) 그 옆에 `SYSTEM` 경우를 더한다.

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test && cd ..
cd web && pnpm test:browser -- chat-delegation-wake && cd ..
scripts/check-local.sh
grep -rn "\"SYSTEM\"" web/src/lib/message-versions.ts web/src/components/chat/message-bubble.tsx
```

`check-local.sh` 는 「모두 통과했다」 로 끝나야 한다. `grep` 은 두 파일에서 모두 나와야 한다.
모두 통과하면 `tasks/plan031-delegation-wake/index.json` 의 `status` 를 `completed` 로 바꾼다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatController.java` | 수정 |
| `web/src/app/api/chat/conversations/[conversationId]/events/route.ts` | 신규 |
| `web/src/lib/chat-event.ts` | 수정 |
| `web/src/lib/message-versions.ts` | 수정 |
| `web/src/components/chat-panel.tsx` | 수정 |
| `web/src/components/chat/message-bubble.tsx` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/` 아래 컨트롤러 테스트 | 수정 |
| `test/browser/chat-delegation-wake.spec.ts` | 신규 |
