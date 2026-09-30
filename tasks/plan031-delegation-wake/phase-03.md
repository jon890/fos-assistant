# Phase 03. 대화 단위 SSE 와 알림 줄을 화면에 보인다

**Execution profile**: deep

## 목표

열린 대화 창이 자동 turn 을 실시간으로 받는다.
`SYSTEM` 메시지는 회색 알림 줄로, 그 뒤의 답은 보통 답처럼 보인다.

**범위 외**: 자동 turn 을 여는 판정(phase 02). 사용자 turn 을 다른 창에 푸시하는 것. 이것은 지금처럼 `/running` 폴링을 쓴다.

## 컨텍스트

phase 02 가 만든 것을 쓴다.
- `backend/src/main/java/com/bifos/assistant/chat/application/ConversationEventHub.java` 의 `subscribe(conversationId, consumer)` 는 해제용 `Runnable` 을 돌려준다
- `ChatEvent` 의 `system` 사건
- 자동 turn 은 hub 로 `system`, `started`, `delta`, `tool`, `done`, `stopped`, `error` 를 낸다

백엔드(경로는 `backend/src/main/java/com/bifos/assistant/` 기준):
- SSE 는 `chat/presentation/ChatEventStreams.java` 가 연다. `SseEmitter(0L)` 와 가상 스레드로 일을 돌리고 20초마다 `: ping` 을 보낸다.
- **보내기 직전에 `event.forViewer(user)` 를 적용한다**(`chat/presentation/ChatController.java:98-99`, `:108`). 관리자가 아닌 사용자에게 도구 명령 원문을 보내지 않기 위해서다. 근거는 `docs/adr/ADR-038-도구의-명령-원문은-관리자에게만-보내고-사용자에게는-사람-말로-보인다.md` 다.
- 대화 경로는 `@PathVariable UUID conversationId` 를 받고 `ConversationAccess.requireOwnId(user, conversationId)` 로 주인 확인과 번호 변환을 한다(`ChatController.running` 참고).
- `ChatController` 를 `new ChatController(...)` 로 만드는 테스트가 다섯이다. 생성자를 바꾸지 않으려면 새 컨트롤러를 둔다.

웹(`web/src/` 기준):
- 라우트는 `app/api/chat/conversations/[conversationId]/` 아래에 있다. `running/route.ts` 가 `isConversationId` 로 주소를 검사한다. `app/api/chat/stream/route.ts` 가 `requestControlPlane` 으로 SSE 본문을 그대로 넘긴다.
- `lib/control-plane.ts` 의 `requestControlPlane(path, init: { method?, body? })` 는 `signal` 을 받지 않는다.
- 사건 파서는 `lib/stream.ts` 의 `readEventStream`, 사건 타입은 `lib/chat-event.ts` 의 `ChatEvent` 다.
- 메시지는 `lib/message-versions.ts` 의 `foldVersions` 가 `USER` 를 기준으로 turn 으로 접는다. `role` 타입은 `"USER" | "ASSISTANT"` 다. 단위 테스트는 `test/unit/message-versions.test.ts` 에 있다.
- `components/chat/message-list.tsx` 가 `foldVersions` 를 부르고(63행 근처), 보일 줄과 다시 생성 가능 여부(`canRegenerate`, 149행 근처)를 정한다. `components/chat/message-bubble.tsx` 는 줄 하나를 그리고, 앞 줄을 모른다.
- `components/chat-panel.tsx` 는 대화를 열 때 `/running` 을 묻고, 돌고 있으면 `beginObserving` 으로 보는 중 상태가 되어 폴링한다(228행 근처). 보낸 turn 의 사건은 같은 파일에서 그린다.

**근거 문서**: `docs/flow.md` 의 「위임 결과가 도착했을 때」 절의 「대화 창이 열려 있다」 와 「대화 창이 닫혀 있다」 행,
`docs/code-architecture.md` 의 「위임 결과로 부모 대화를 깨우기」 절 표

## 의도 메모

- 대화 단위 SSE 는 자동 turn 만 싣는다. 사용자가 보낸 turn 까지 실으면 보낸 창이 같은 답을 두 번 그린다.
- 대화를 열 때 이미 돌던 turn 은 기존 보는 중 상태가 맡는다. `delta`, `tool`, `error` 등 조각 사건에는 실행 번호가 없고(`ChatEvent` 의 팩토리가 `executionId` 를 null 로 둔다), turn 도중에 열면 `started` 는 이미 지나갔다. 그래서 사건의 순서로 고른다.
  - 보는 중 상태가 켜진 동안 `started` 를 받기 전에 오는 조각 사건(`delta`, `tool`, `subagent`, `step`, `switched`, `reset`, `error`)은 버린다
  - `started` 의 번호가 보는 중인 번호와 같으면 그 turn 의 `done` 이나 `stopped` 까지 버린다
  - 다른 번호의 `started` 부터는 받는다
  - `system` 은 항상 받는다
  - 보는 중 상태가 끝나면 지금처럼 메시지를 다시 읽는다
- 알림 줄은 turn 의 시작점이다. `foldVersions` 는 `SYSTEM` 을 `USER` 처럼 turn 을 여는 줄로 다루되, 판 사슬을 만들지 않는다.

## 작업 항목

### 1. `chat/presentation/ConversationEventController.java`(신규) — `GET /api/v1/chat/conversations/{conversationId}/events`

- `@RestController`, `@RequestMapping("/api/v1/chat")`. `ChatController` 와 같은 인증 방식(`CurrentUserProvider`)을 쓴다.
- `produces = TEXT_EVENT_STREAM_VALUE`. `requireOwnId` 로 주인 확인 뒤 `SseEmitter(0L)` 를 만든다.
- `hub.subscribe(number, event -> send(emitter, event.forViewer(user)))` 를 건다. 직렬화는 `ChatEventStreams` 와 같은 `data: {json}` 한 줄이다. 그 직렬화 코드를 두 곳이 함께 쓰게 꺼낼 수 있으면 꺼낸다.
- 20초마다 `: ping` 을 보낸다. `onCompletion`, `onTimeout`, `onError` 에서 구독을 해제하고 ping 을 멈춘다.

### 2. `web/src/lib/control-plane.ts`, `web/src/app/api/chat/conversations/[conversationId]/events/route.ts`(신규)

- `requestControlPlane` 의 `init` 에 선택 칸 `signal?: AbortSignal` 을 더해 `fetch` 에 넘긴다. 기존 호출은 바뀌지 않는다.
- 새 라우트 `GET`: `isConversationId` 검사 뒤 `requestControlPlane(path, { signal: request.signal })` 로 열고, 본문을 그대로 넘긴다. 헤더와 오류 처리는 `stream/route.ts` 와 같다.

### 3. 타입과 접기

- `web/src/lib/chat-event.ts`: `type` 에 `"system"` 을 더하고 `content`, `messageId` 칸을 받는다.
- `role` 타입을 `"USER" | "ASSISTANT" | "SYSTEM"` 으로 넓힌다: `lib/message-versions.ts`, `components/chat/message-bubble.tsx`, `components/chat-panel.tsx` 의 `Turn`.
- `lib/message-versions.ts` 의 `foldVersions`: `USER` 와 `SYSTEM` 을 모두 turn 을 여는 줄로 다룬다. `SYSTEM` 은 판 사슬을 만들지 않는다. 반환 타입의 여는 줄에서 알림인지 알 수 있게 한다(여는 줄의 `role` 로 충분하면 그대로 둔다).
- `components/chat-panel.tsx` 의 `answerAfterLastQuestion` 과 `lastQuestionIsNew` 는 계속 `USER` 만 질문으로 본다.

### 4. `components/chat/message-list.tsx`, `components/chat/message-bubble.tsx` — 알림 줄

- `message-bubble.tsx`: `role` 이 `SYSTEM` 이면 가운데 정렬의 작은 회색 글 한 줄로 그린다. 복사, 다시 생성, 판 넘기기 버튼이 없다. 기존 토큰(`text-muted-foreground` 등)을 쓰고, 긴 글은 줄바꿈해 가로로 넘치지 않게 한다.
- `message-list.tsx`: 여는 줄이 `SYSTEM` 인 turn 의 답은 `canRegenerate` 를 거짓으로 둔다.

### 5. `components/chat-panel.tsx` — 구독

- 대화 번호가 있는 동안 `/api/chat/conversations/{id}/events` 를 `fetch` 로 열고 `readEventStream` 으로 읽는다. 대화가 바뀌거나 화면이 닫히면 `AbortController` 로 끊는다. 서버가 끊으면 5초 뒤 다시 연다.
- `system`: 알림 줄을 목록 끝에 더한다.
- `started`, `delta`, `tool`, `subagent`, `step`, `switched`, `reset`, `done`, `stopped`, `error`: 지금 보낸 turn 을 그리는 코드와 같은 방식으로 새 답을 그린다. 그 처리를 함수로 꺼내 두 곳이 함께 쓴다. `done` 뒤에는 지금처럼 메시지를 다시 읽어 저장된 번호로 맞춘다.
- 보는 중 상태(`beginObserving`)가 켜져 있으면 의도 메모의 순서 규칙으로 사건을 버린다.

### 6. 테스트

- `backend/src/test/java/com/bifos/assistant/chat/ConversationEventControllerTest.java`(신규)
  - 주인이 구독하면 `hub.publish` 한 `system` 사건을 받는다
  - 남의 대화는 기존 경로와 같은 오류다
  - MEMBER 사용자는 `tool` 사건의 명령 원문(`detail`)을 받지 않고, ADMIN 은 받는다. 기존 `ToolDetailStreamTest.java` 의 단언 방식을 따른다
- `test/unit/message-versions.test.ts` 에 더한다. `USER, ASSISTANT, SYSTEM, ASSISTANT` 순서면 turn 이 둘로 접히고, 둘째 turn 의 여는 줄이 `SYSTEM` 이다
- `test/browser/chat-delegation-wake.spec.ts`(신규). `test/browser/chat.spec.ts` 의 준비와 가짜 서버를 따른다.
  - 대화를 연 채로 대화 단위 SSE 에 `system` 과 답 사건이 오면 알림 줄과 답이 보인다
  - 저장된 메시지에 `SYSTEM` 이 있으면 알림 줄로 보이고, 그 뒤 답에는 다시 생성 버튼이 없다
  - 모바일과 데스크톱 두 폭에서 알림 줄이 가로로 넘치지 않는다
  - `page.route` 로 events 응답을 한 번에 채워 주면 연결이 닫혀 5초 뒤 다시 연결한다. 두 번째 요청부터는 사건 없이 붙잡아 두어 같은 사건을 두 번 받지 않게 한다

## 검증

```bash
# cwd: 저장소 root
(cd backend && ./gradlew test)
node --test 'test/unit/**/*.test.ts'
(cd web && pnpm typecheck)
(cd web && pnpm test:browser -- chat-delegation-wake)
grep -rn "\"SYSTEM\"" web/src/lib/message-versions.ts web/src/components/chat/message-bubble.tsx
```

`grep` 은 두 파일에서 모두 나와야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ConversationEventController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatEventStreams.java` | 수정 |
| `web/src/lib/control-plane.ts` | 수정 |
| `web/src/app/api/chat/conversations/[conversationId]/events/route.ts` | 신규 |
| `web/src/lib/chat-event.ts` | 수정 |
| `web/src/lib/message-versions.ts` | 수정 |
| `web/src/components/chat-panel.tsx` | 수정 |
| `web/src/components/chat/message-list.tsx` | 수정 |
| `web/src/components/chat/message-bubble.tsx` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ConversationEventControllerTest.java` | 신규 |
| `test/unit/message-versions.test.ts` | 수정 |
| `test/browser/chat-delegation-wake.spec.ts` | 신규 |
