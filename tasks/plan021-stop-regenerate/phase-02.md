# Phase 02. Control Plane 이 마지막 답을 다시 만들고 마지막 질문을 고친 판을 쌓는다

**Execution profile**: deep

## 목표

다시 생성 `POST /api/v1/chat/conversations/{id}/regenerate/stream` 과
수정 `POST /api/v1/chat/messages/stream` 의 `editOfMessageId` 를 만든다.
새 메시지가 이전 메시지를 `chat_message.replaces_message_id` 로 가리키고, 메시지 조회가 그 칸을 싣는다.

**범위 외**: 화면의 단추와 판 넘기기는 phase 03 과 04 가 한다.
마지막 turn 밖의 메시지를 고치는 것과 Hermes session 을 되감는 것은 하지 않는다. ADR-022 가 막았다.
한 번에 받는 경로 `POST /api/v1/chat/messages` 에는 수정을 열지 않는다. 화면은 스트리밍 경로만 쓴다.

## 컨텍스트

phase 01 이 끝나 있어야 한다. `TurnCancellation`, `ChatTurn.cancelled`, `MessageView.status` 가 있다.
`backend/src/main/java/com/bifos/assistant/chat/application/TurnCancellation.java` 가 없으면 `PHASE_BLOCKED: 중지가 아직 없다` 를 출력하고 끝낸다.

사진 첨부가 이미 머지되어 있다. **구현 전에 `AttachmentService` 와 `SendMessageRequest` 의 지금 모양을 읽는다.**
첨부를 메시지에 묶는 메서드와 Hermes 입력에 사진 자리를 덧붙이는 자리가 거기 있다. 그 이름을 이 문서가 지어내지 않는다.

지금 코드의 모양이다.

| 무엇 | 어디 |
| --- | --- |
| 요청 본문 | `ChatDtos.SendMessageRequest(Long conversationId, @NotBlank @Size(max = 8000) String text, String agentCode)` 에 첨부 칸이 더해져 있다 |
| 사용자 메시지 저장 | `ChatService.runTurn` 과 `ResearchAndBuildFlow.run` 이 각자 `messages.save(ChatMessage.fromUser(...))` 를 부른다 |
| 실행 입력 | `ChatService.begin` 이 `new HermesRunCommand(profile, apiBaseUrl, text, context.instructions(), sessionId, provider, model)` 를 만든다 |
| `instructions` | `ContextAssembler.assemble(user)` 가 돌려주는 `AssembledContext.instructions()` |
| 대화 확인 | `ConversationAccess.requireOwn(user, conversationId)` |
| 메시지 목록 | `ChatMessageRepository.findByConversationIdOrderByIdAsc(Long)` |
| 메시지 엔티티 | `chat/domain/ChatMessage.java`. 생성은 `fromUser`, `fromAssistant` 두 정적 메서드뿐이다 |
| 스트림 경로 | `ChatController.stream` 이 가상 스레드에서 `chat.stream(...)` 을 부르고 `SseEmitter` 로 보낸다 |

**근거 문서**: `docs/adr/ADR-022-다시-생성과-수정은-같은-session-에-판으로-쌓는다.md`, `docs/flow.md` 의 「다시 생성과 수정」 과 「실행이 실패할 때」, `docs/code-architecture.md` 의 「대화」 절 아래 「경로」 「메시지 한 줄」, `docs/data-schema.md` 의 「chat_message」

## 의도 메모

- 사용자 메시지의 글을 고치지 않는다. 덧붙이는 한 줄은 `instructions` 끝에만 간다.
  대화를 다시 읽을 때 사람이 쓴 것과 우리가 덧붙인 것이 섞이지 않게 하기 위해서다.
- 다시 생성은 사용자 메시지를 새로 저장하지 않는다. 원래 사용자 메시지의 글로 새 실행을 돌리고 새 답만 저장한다.
- `CONVERSATION_BUSY` 는 그 대화에 도는 turn 이 이 프로세스에 있는지로 판정한다. phase 01 의 `TurnCancellation.open` 이 확인과 등록을 한 번에 하고 그 오류를 던진다.
  **`busy()` 로 먼저 묻고 나중에 `open` 하지 않는다.** 둘 사이에 다시 생성 둘이 함께 들어오면 같은 답을 가리키는 판이 둘 생기고, 화면의 판 접기가 한 줄 사슬이라는 가정이 깨진다.
  실행 줄의 `RUNNING` 으로 판정하지 않는 이유는 phase 01 과 같다. 기동할 때 남은 `RUNNING` 줄에 걸려 영영 못 누르는 일도 막는다.
- 판 넘기기의 단위가 둘이라 저장도 둘이다. 다시 생성은 답이 답을, 수정은 사용자 메시지가 사용자 메시지를 가리킨다. 고친 메시지 뒤의 새 답은 아무것도 가리키지 않는다.
- 흐름으로 도는 에이전트에서도 다시 생성과 수정이 된다. `ResearchAndBuildFlow.run` 이 사용자 메시지를 직접 저장하므로 저장하는 자리를 인자로 받게 고친다.

## 작업 항목

### 1. `chat_message.replaces_message_id` 마이그레이션

`backend/src/main/resources/db/migration/` 의 마지막 번호 다음 번호로 파일을 만든다. 이름 끝은 `__message_replaces.sql` 이다.

```sql
ALTER TABLE chat_message ADD COLUMN replaces_message_id BIGINT NULL;
ALTER TABLE chat_message ADD CONSTRAINT fk_chat_message_replaces
    FOREIGN KEY (replaces_message_id) REFERENCES chat_message (id);
```

`ChatMessage` 에 `@Column(name = "replaces_message_id") private Long replacesMessageId;` 와 읽는 메서드를 더한다.
정적 메서드 둘을 더한다. `regeneratedAnswer(Long conversationId, String content, Long executionId, Long replacesMessageId)` 와
`editedFromUser(Long conversationId, Long senderUserId, String content, Long replacesMessageId)` 다.

### 2. `ChatService` 에 turn 의 뜻을 나타내는 값을 둔다

`chat/application/TurnIntent.java` 를 새로 만든다.

```java
/** 이 turn 이 새 질문인지, 마지막 답의 다시 생성인지, 마지막 질문의 수정인지. */
public sealed interface TurnIntent {
    record Fresh() implements TurnIntent {}
    record Regenerate(ChatMessage previousAnswer, ChatMessage question) implements TurnIntent {}
    record Edit(ChatMessage previousQuestion) implements TurnIntent {}
}
```

`instructions` 끝에 덧붙일 한 줄을 `ChatService` 의 상수로 둔다. 문구는 아래 그대로 쓴다.

| 뜻 | 덧붙일 한 줄 |
| --- | --- |
| 다시 생성 | `사용자가 바로 앞 질문에 대한 답을 다시 받기를 원한다. 앞의 답을 되풀이하지 말고 새로 답한다.` |
| 수정 | `사용자가 바로 앞 질문을 아래 글로 고쳤다. 고치기 전 질문과 그 답은 무시하고 고친 질문에 답한다.` |

`begin` 이 `HermesRunCommand` 를 만들 때 `Fresh` 가 아니면 `context.instructions()` 끝에 빈 줄 하나와 이 줄을 붙인다.
`instructions` 가 null 이면 이 줄 하나만 넣는다. `context_chars` 와 `instructions_hash` 는 덧붙이기 전 값을 그대로 적는다.

### 3. `ChatService.regenerate` 를 만든다

```java
public void regenerate(CurrentUser user, Long conversationId, Consumer<ChatEvent> onEvent)
```

1. `ConversationAccess.requireOwn` 으로 대화를 확인한다. 지운 대화이면 거기서 `CONVERSATION_NOT_FOUND` 가 난다.
2. `turns.open(user.id(), conversationId)` 로 turn 을 등록한다. 도는 turn 이 있으면 여기서 `CONVERSATION_BUSY` 가 난다.
   아래 검사에서 거절하면 `finally` 에서 `close` 한다.
3. 메시지 목록에서 **판으로 대신된 것을 뺀 마지막 메시지**를 찾는다. 다른 메시지의 `replacesMessageId` 로 가리켜진 메시지가 대신된 것이다.
   - `ASSISTANT` 이면 그것이 이전 답이다. 그 답 바로 앞의 대신되지 않은 `USER` 메시지가 질문이다.
   - **`USER` 이면 답을 받지 못한 turn 이다.** 실패했거나 남긴 답 없이 멈춘 turn 이 그렇다. 그 메시지가 질문이고 이전 답은 없다.
     화면은 이 경우를 「다시 시도」 로 보인다. `docs/flow.md` 「다시 생성과 수정」 이 이 경우를 연다.
   - 메시지가 하나도 없으면 `MESSAGE_NOT_LATEST` 를 던진다.
4. 질문의 글과 질문에 묶인 첨부로 새 turn 을 돈다. 사용자 메시지를 저장하지 않는다.
   첨부를 Hermes 입력에 다시 덧붙이는 방법은 사진 첨부가 만든 자리를 그대로 쓴다.
5. 끝나면 새 답을 저장한다. 이전 답이 있으면 `regeneratedAnswer(..., previousAnswer.id())`, 없으면 `fromAssistant` 다.
   멈췄으면 phase 01 의 `cancel` 이 같은 규칙으로 저장한다.
6. 실패하면 새 답을 만들지 않는다. 이전 판이 그대로 남는다.

`instructions` 에 덧붙이는 한 줄은 이전 답이 있을 때만 넣는다. 답이 없던 turn 을 다시 시도할 때는 되풀이하지 말라고 할 답이 없다.

`runTurn` 은 지금 사용자 메시지를 첫 줄에서 저장한다. 저장을 부르는 쪽으로 옮기고 `runTurn` 은 `TurnIntent` 를 받아 답을 저장할 때 쓴다.
`finish` 와 phase 01 의 `cancel` 이 `ChatMessage.fromAssistant` 대신 intent 에 맞는 정적 메서드를 고른다.

### 4. 수정을 `stream` 에 연다

`SendMessageRequest` 에 `Long editOfMessageId` 를 더한다. 없으면 null 이다.
`ChatService.stream` 에 인자를 하나 더하고 `ChatController.stream` 이 넘긴다.

`editOfMessageId` 가 있으면 아래를 한다.

1. `conversationId` 가 없으면 `VALIDATION_FAILED` 다. 새 대화에는 고칠 메시지가 없다.
2. `turns.open` 으로 등록한다. 도는 turn 이 있으면 여기서 `CONVERSATION_BUSY` 다. 아래 검사에서 거절하면 `close` 한다.
3. 대신되지 않은 `USER` 메시지 가운데 마지막 것이 `editOfMessageId` 가 아니면 `MESSAGE_NOT_LATEST` 다.
   그 뒤에 답이 있든 없든 받는다. 답을 받지 못한 turn 의 질문도 고칠 수 있다.
4. 그 메시지에 첨부가 묶여 있거나 요청이 첨부를 함께 보냈으면 `VALIDATION_FAILED` 다.
5. `editedFromUser(conversationId, user.id(), text, editOfMessageId)` 로 새 사용자 메시지를 저장하고 `TurnIntent.Edit` 로 turn 을 돈다.
   새 답은 `fromAssistant` 로 저장한다. 아무것도 가리키지 않는다.

`ErrorCode` 에 `MESSAGE_NOT_LATEST(HttpStatus.CONFLICT)` 를 더한다. `CONVERSATION_BUSY` 와 `TurnCancellation` 의 대화 번호 칸은 phase 01 이 이미 만들었다.

`runTurn` 은 phase 01 에서 스스로 `open` 한다. 다시 생성과 수정은 위에서 이미 `open` 했으므로 그 handle 을 `runTurn` 에 넘기고 `runTurn` 은 다시 `open` 하지 않는다.
보통 보내기는 지금처럼 `runTurn` 이 연다. handle 을 인자로 받는 판과 스스로 여는 판 가운데 어느 쪽으로 나눌지는 phase 01 이 남긴 모양을 보고 정한다. 한 turn 이 두 번 `open` 하면 스스로 `CONVERSATION_BUSY` 에 걸린다.

흐름으로 도는 에이전트는 `ResearchAndBuildFlow.run` 이 사용자 메시지를 직접 저장한다.
`Flow.run` 의 서명에 `TurnIntent` 를 더하고 저장과 답 저장을 intent 에 맞게 고른다.
다시 생성이면 사용자 메시지를 저장하지 않는다. `answer` 가 새 답을 저장할 때 `regeneratedAnswer` 를 쓴다.

### 5. `ChatController` 에 경로를 더하고 `MessageView` 에 칸을 더한다

```java
@PostMapping(path = "/conversations/{conversationId}/regenerate/stream",
        produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter regenerate(@PathVariable Long conversationId)
```

몸통은 `stream` 과 같은 모양이다. 가상 스레드, `AtomicBoolean clientConnected`, 오류를 `ChatEvent.error` 로 보내는 것까지 같다.
두 메서드에서 되풀이되는 부분을 private 메서드 하나로 모은다.

`MessageView` 에 `Long replacesMessageId` 를 더하고 `messages` 가 `ChatMessage` 의 값을 싣는다.

### 6. web 서버 라우트를 더한다

- `web/src/app/api/chat/conversations/[conversationId]/regenerate/route.ts` 를 만든다. `POST` 만 받는다.
  `web/src/app/api/chat/stream/route.ts` 가 upstream 스트림을 그대로 흘려 보내는 방식을 따른다.
- `web/src/app/api/chat/stream/route.ts` 가 `editOfMessageId` 를 받아 그대로 넘긴다. 숫자가 아니면 넘기지 않는다.
- `web/src/components/error-message.ts` 에 두 문구를 더한다.
  `MESSAGE_NOT_LATEST` 는 「그 사이 대화가 바뀌었다. 최신 대화를 다시 불러왔다.」, `CONVERSATION_BUSY` 는 「아직 답을 만드는 중이다. 끝난 뒤에 다시 누른다.」

### 7. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/chat/ChatRegenerateTest.java` 를 새로 만든다. `ChatStopTest` 와 같은 설정이다.

| 경우 | 기대 |
| --- | --- |
| 마지막 답을 다시 생성한다 | 새 답이 이전 답을 가리킨다. 사용자 메시지 수는 그대로다. `StubHermesRunsClient.received()` 의 마지막 `input` 은 원래 질문과 같고 `instructions` 끝이 다시 생성의 한 줄이다 |
| 다시 생성이 실패한다 | 답 메시지가 늘지 않는다 |
| 마지막 메시지가 답 없는 사용자 메시지인 대화. 앞 turn 이 실패했다 | 새 답이 생기고 `replacesMessageId` 가 null. 사용자 메시지 수는 그대로다. `instructions` 에 다시 생성의 한 줄이 없다 |
| 메시지가 하나도 없는 대화 | `MESSAGE_NOT_LATEST` |
| 도는 turn 이 있는 대화 | `CONVERSATION_BUSY`. 사용자 메시지도 실행 줄도 늘지 않는다 |
| 같은 대화에 다시 생성 둘을 함께 부른다. 대역의 `beforeAwait` 에서 첫 turn 을 붙잡고 둘째를 부른다 | 하나는 `CONVERSATION_BUSY`. 이전 답을 가리키는 판이 하나뿐이다 |
| 남의 대화 | `CONVERSATION_NOT_FOUND` |
| 마지막 사용자 메시지를 고친다 | 새 사용자 메시지가 이전 것을 가리키고 새 답은 아무것도 가리키지 않는다. `input` 은 고친 글이고 `instructions` 끝이 수정의 한 줄이다 |
| 앞의 사용자 메시지를 고친다 | `MESSAGE_NOT_LATEST` |
| 첨부가 달린 메시지를 고친다 | `VALIDATION_FAILED` |
| 다시 생성한 뒤 또 다시 생성한다 | 셋째 답이 둘째 답을 가리킨다 |

`test/e2e/scenarios/regenerate.ts` 를 새로 만들고 `test/e2e/run.ts` 의 `SCENARIOS` 에서 phase 01 이 넣은 중지 시나리오 뒤에 둔다.
다시 생성 스트림의 `done`, 메시지 조회의 `replacesMessageId`, 수정한 뒤의 메시지 순서, 남의 대화에 대한 404 를 본다.
`fakeHermes.lastSubmittedInstructions()` 로 덧붙인 한 줄을 본다.

## 검증

```bash
# cwd: backend/
./gradlew test --tests '*ChatRegenerateTest' --tests '*ChatStopTest'
./gradlew test
```

```bash
# cwd: 저장소 root
node test/e2e/run.ts
scripts/check-public-safe.sh
grep -rn 'replaces_message_id' backend/src/main/resources/db/migration
```

마지막 줄이 새 마이그레이션 한 파일만 내야 한다.

```bash
# cwd: web/
pnpm typecheck
```

끝나면 `tasks/plan021-stop-regenerate/index.json` 의 이 phase 를 `completed` 로 바꾸고 `current_phase` 를 3으로 올린다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V{다음}__message_replaces.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/ChatMessage.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/TurnIntent.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/TurnCancellation.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/Flow.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/ResearchAndBuildFlow.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatRegenerateTest.java` | 신규 |
| `web/src/app/api/chat/conversations/[conversationId]/regenerate/route.ts` | 신규 |
| `web/src/app/api/chat/stream/route.ts` | 수정 |
| `web/src/components/error-message.ts` | 수정 |
| `test/e2e/scenarios/regenerate.ts` | 신규 |
| `test/e2e/run.ts` | 수정 |
