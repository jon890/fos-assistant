# Phase 01. 대화 이름 바꾸기와 지우기, `started` 사건

**Execution profile**: standard

## 목표

Control Plane 에 대화 이름 바꾸기와 지우기를 더하고, 스트리밍 경로가 실행 줄을 만든 직후
`started` 사건으로 대화 번호와 실행 번호를 알리게 한다.
화면이 첫 메시지를 보낸 직후 주소를 `/c/{id}` 로 바꾸고 목록에 넣으려면 이 번호가 먼저 와야 한다.

**범위 외**:
화면은 phase-02 와 phase-03 이 한다.
중지 경로와 `stopped` 사건, `tool` 과 `subagent` 사건의 나눔은 이 plan 의 일이 아니다.

## 컨텍스트

**근거 문서**:
`docs/data-schema.md` 의 「conversation」 절과 「지울 때」 절,
`docs/code-architecture.md` 의 「대화」 절 아래 「경로」 표와 「화면으로 보내는 사건」 표,
`docs/flow.md` 의 「대화 한 번」 시퀀스와 「대화 목록」 절.

### 먼저 지금 모양을 읽는다

이 plan 은 사진 첨부(`docs/code-architecture.md` 의 「사진 첨부」 절,
`docs/adr/ADR-020-사진은-공유-디렉터리에-두고-에이전트가-파일로-읽는다.md`)가 main 에 들어간 뒤에 돈다.
그 변경이 `ChatDtos.SendMessageRequest` 에 첨부 번호 목록을 더하고 `chat_attachment` 표와
첨부 경로를 더한다.
**구현 전에 `ChatDtos.java`, `ChatController.java`, `ChatService.java` 의 지금 모양을 읽고
첨부 흐름을 깨지 않는다.** 첨부 경로도 지운 대화에서는 `CONVERSATION_NOT_FOUND` 여야 한다.
첨부 쪽이 대화를 찾는 메서드를 따로 쓰면 그것도 아래 규칙에 맞춘다.

### 대화를 찾는 자리가 둘이다

`backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` 에서

- `conversationsOf(CurrentUser)` 가 `ConversationRepository.findByUserIdOrderByUpdatedAtDesc` 를 부른다
- `requireOwnConversation(CurrentUser, Long)` 이 `ConversationRepository.findByIdAndUserId` 를 부른다

`history`, `resolveConversation`(이어지는 대화), 스트리밍과 한 번에 받는 경로가 모두 둘째를 지난다.
**지운 대화를 이 두 자리에서 빼면 목록과 조회와 보내기가 함께 막힌다.**

### 흐름으로 도는 turn 의 뿌리 실행

`ResearchAndBuildFlow.run` 은 `AgentRunner.run(...)` 으로 Chief 실행을 돌리고 끝난 뒤에
`chief.execution()` 을 뿌리로 쓴다.
`AgentRunner.run` 안에서 `executions.start(...)` 가 실행 줄을 만들지만 부르는 쪽은 실행이 끝날 때까지 그 번호를 모른다.
`started` 를 Chief 가 끝나기 전에 보내려면 `AgentRunner` 가 실행 줄을 만든 직후 알려야 한다.

### turn 끝의 대화 저장이 지운 것을 되돌린다

`ChatService.finish` 는 `pending.conversation().rememberSession(...)` 뒤 `conversations.save(pending.conversation())` 를 부른다.
`ResearchAndBuildFlow.run` 도 `conversation.rememberSession(chief.sessionId())` 뒤 `conversations.save(conversation)` 를 부른다.
둘 다 요청 시작에 읽은 `Conversation` 이다. `application.yml` 이 `open-in-view: false` 이고 `ChatService` 에 트랜잭션이 없으며
`Conversation` 에 `@Version` 이 없어, 이 `save` 는 merge 로 돌고 **그 사이에 적힌 `deleted_at` 과 바뀐 `title` 을 옛 값으로 되돌린다.**
`docs/code-architecture.md` 「대화」 절의 「turn 이 끝날 때 대화를 통째로 다시 저장하지 않는다」 가 그 규칙이다.

## Blocked 조건

- 시작 전에 main 에서 `scripts/check-public-safe.sh` 가 종료 코드 0 인지 본다.
  0 이 아니면 `PHASE_BLOCKED: check-public-safe 가 main 에서 이미 실패한다` 를 출력하고 끝낸다.
  이 phase 의 검증이 그 스크립트의 0 을 요구하는데, 앞서 들어간 변경의 파일이 걸리면 이 phase 로는 고칠 수 없다

## 의도 메모

- 행을 지우는 안을 버렸다. `agent_execution.conversation_id` 가 대화를 가리키고 사용량이 거기서 나온다.
- 지운 대화를 `CONVERSATION_NOT_FOUND` 와 다른 코드로 알리는 안을 버렸다.
  남의 대화와 지운 대화를 가리지 않아야 번호를 훑어 알아낼 것이 없다.
- `started` 는 `docs/code-architecture.md` 대로 실행 줄을 만들 때마다 보낸다.
  provider 가 막혀 다음 모델로 넘어가면 새 실행 줄이 생기고 `started` 를 새 번호로 다시 보낸다.
  화면은 마지막으로 받은 번호를 쓴다. 중지가 이 번호로 가기 때문이다. 앞 번호의 실행은 이미 FAILED 다.
  화면이 이 번호를 쓰는 곳은 주소와 목록 갱신이라 대화 번호만 맞으면 된다.
- 사용량 조회는 대화를 거르지 않는다. 지운 대화의 실행도 그대로 센다. 고치지 않는다.
- `Conversation` 에 `@Version` 을 붙여 낙관적 잠금으로 막는 안을 버렸다. 도는 turn 이 끝날 때 충돌 예외가 나
  답을 저장하지 못한다. turn 이 바꾸는 칸이 둘뿐이라 그 둘만 고치는 질의가 맞다.
- `AgentRunner.run` 에 더하는 `onStarted` 는 뒤에 올 중지 작업이 제출 뒤 콜백(`onSubmitted`)을 더할 자리다.
  그 작업이 인자를 하나 더 붙이거나 콜백 묶음 타입으로 바꿀 수 있게, 8인자 판을 부르는 곳을 `ResearchAndBuildFlow` 한 곳으로 둔다.

## 작업 항목

### 1. 마이그레이션

`backend/src/main/resources/db/migration/` 의 마지막 번호 다음 번호로 파일을 하나 만든다.
이름은 `V{다음 번호}__conversation_deleted_at.sql` 이다.

```sql
ALTER TABLE conversation ADD COLUMN deleted_at DATETIME(6) NULL;
```

기존 파일들의 문법(예: `V16__allowed_person.sql`)을 따른다.

### 2. `chat/domain/Conversation.java`

- `@Column(name = "deleted_at") private Instant deletedAt;` 를 더한다
- `public void rename(String title)` : 앞뒤 공백을 떼고(`strip()`) 1자 이상 200자 이하가 아니면
  `ApiException(ErrorCode.VALIDATION_FAILED, ...)` 을 던진다. 통과하면 `title` 을 바꾸고 `updatedAt` 을 지금으로 한다
- `public void delete()` : `deletedAt` 이 비어 있을 때만 지금 시각을 적는다
- `public Instant deletedAt()` 접근자

제목 검증을 엔티티에 두는 이유는 컨트롤러의 `@Size` 가 공백을 떼기 전 길이를 보기 때문이다.

### 3. `chat/infra/ConversationRepository.java`

```java
Optional<Conversation> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);
List<Conversation> findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(Long userId);
```

`ChatService.conversationsOf` 와 `requireOwnConversation` 이 새 두 메서드를 쓰게 바꾼다.
옛 두 메서드를 부르는 곳이 더 없으면 지운다. `grep -rn 'findByIdAndUserId\b\|findByUserIdOrderByUpdatedAtDesc' backend/src` 로 확인한다.

### 4. `ChatService` 에 두 메서드

```java
public Conversation rename(CurrentUser user, Long conversationId, String title)
public void delete(CurrentUser user, Long conversationId)
```

둘 다 `requireOwnConversation` 을 먼저 지나 `CONVERSATION_NOT_FOUND` 를 같은 방식으로 낸다.
`@Transactional` 로 저장한다. 도는 실행이 있어도 지울 수 있다. 실행을 건드리지 않는다.

### 5. turn 끝의 대화 저장을 두 칸만 고치는 질의로 바꾼다

`ConversationRepository` 에 더한다.

```java
@Modifying
@Transactional
@Query("update Conversation c set c.hermesSessionId = coalesce(:sessionId, c.hermesSessionId), c.updatedAt = :now where c.id = :id")
int touchSession(@Param("id") Long id, @Param("sessionId") String sessionId, @Param("now") Instant now);
```

- `ChatService.finish` 의 `rememberSession` 과 `conversations.save(pending.conversation())` 를
  `conversations.touchSession(pending.conversation().id(), 빈 문자열이면 null 로 바꾼 sessionId, Instant.now())` 로 바꾼다
- `ResearchAndBuildFlow.run` 의 `conversation.rememberSession(chief.sessionId())` 와 `conversations.save(conversation)` 도 같게 바꾼다
- 같은 turn 안에서 뒤에 `pending.conversation().hermesSessionId()` 를 다시 읽는 곳이 있으면 메모리의 엔티티에도
  `rememberSession` 을 불러 두되 `save` 는 하지 않는다. `grep -n 'hermesSessionId()' backend/src/main` 으로 확인한다
- `resolveConversation` 이 새 대화를 만드는 `conversations.save(Conversation.startedBy(...))` 는 그대로 둔다. 새 행이라 덮을 것이 없다
- `grep -rn 'conversations.save' backend/src/main` 결과가 새 대화를 만드는 자리와 `rename`, `delete` 만 남아야 한다

`Conversation.rememberSession` 이 빈 session 을 무시하는 규칙을 질의의 `coalesce` 와 부르는 쪽의 null 변환이 대신한다.

### 6. `chat/presentation/ChatController.java` 와 `ChatDtos.java`

- `ChatDtos` 에 `public record RenameConversationRequest(@NotNull String title) {}`
- `@PatchMapping("/conversations/{conversationId}")` : `rename` 을 부르고
  `ConversationView` 한 줄을 돌려준다. 만드는 법은 지금 `conversations()` 의 람다와 같다.
  같은 코드를 두 번 쓰지 않도록 `private ConversationView viewOf(Conversation)` 로 뺀다
- `@DeleteMapping("/conversations/{conversationId}")` : `delete` 를 부르고
  `ResponseEntity.noContent().build()` 를 돌려준다

### 7. `ChatEvent.started`

`chat/application/ChatEvent.java` 에 팩터리를 더한다.

```java
/** 이 turn 의 실행 줄이 만들어졌다. 흐름이면 뿌리 실행의 번호다. */
public static ChatEvent started(Long conversationId, Long executionId)
```

`type` 은 `"started"` 이고 `conversationId` 와 `executionId` 칸만 채운다.

### 8. `started` 를 보내는 자리

- **흐름이 아닌 turn**: `ChatService.runTurn` 의 반복에서 시도마다
  `begin(...)` 직후, `streaming` 이 참일 때만 `onEvent.accept(ChatEvent.started(conversation.id(), pending.execution().id()))`.
  `submit` 보다 앞이다. 제출이 실패해도 화면은 대화 번호를 받아야 목록에 넣을 수 있다
- **흐름이 아닌 turn 의 모델 없음 경로**: `runTurn` 은 쓸 수 있는 모델이 없으면 반복에 들어가기 전에
  `noModelAvailable(user, routed, snapshot)` 을 던진다. 그 메서드도 실행 줄을 만든다.
  `noModelAvailable` 에 `Consumer<ChatEvent> onEvent` 와 `boolean streaming` 을 더해, `executions.start(...)` 직후
  `streaming` 이면 `started` 를 보낸다. 흐름 쪽은 아래 `onStarted` 가 모델 없음 갈래에서도 불리므로 두 경로가 같아진다
- **흐름 turn**: `orchestration/application/AgentRunner.java` 에 인자 하나를 더한 `run` 을 만든다.

  ```java
  public Run run(CurrentUser user, Conversation conversation, Agent agent, String task,
          Long parentExecutionId, Long rootExecutionId, String sessionId,
          Consumer<AgentExecution> onStarted)
  ```

  실행 줄을 만든 직후(모델이 없어 바로 실패하는 갈래 포함) `onStarted.accept(execution)` 을 부른다.
  기존 7인자 `run` 은 `execution -> {}` 을 넘기는 위임으로 남긴다. `ChildExecutionRunner` 는 고치지 않는다.
  `ResearchAndBuildFlow.run` 의 Chief 호출이 새 `run` 을 쓰고
  `execution -> onEvent.accept(ChatEvent.started(conversation.id(), execution.id()))` 를 넘긴다.
  한 번에 받는 경로에서 `onEvent` 는 아무것도 하지 않으므로 경로를 따로 구분하지 않는다

### 9. 이 phase 를 검증하는 테스트

**JUnit**: `backend/src/test/java/com/bifos/assistant/chat/ConversationManageTest.java` 신규.
`ChatServiceTest` 의 준비 방식(`@SpringBootTest`, `@ActiveProfiles("test")`, `StubHermesRunsClient`)을 따른다.

- 이름을 바꾸면 목록의 제목이 바뀐다. 앞뒤 공백을 뗀다
- 공백만 있는 이름과 201자 이름은 `VALIDATION_FAILED`
- 지우면 `conversationsOf` 에서 빠지고 `history` 와 이어 보내기가 `CONVERSATION_NOT_FOUND`
- 남의 대화를 바꾸거나 지우면 `CONVERSATION_NOT_FOUND`
- 지운 대화의 `agent_execution` 줄은 남는다
- `stream` 으로 보내면 받은 사건의 첫 `type` 이 `started` 이고, 마지막 `started` 의 `executionId` 가 `done` 의 것과 같다
- 첫 provider 가 막혀 다음 모델로 넘어가는 turn 에서는 `started` 가 둘이고 번호가 서로 다르다
- 모델이 하나도 없는 에이전트로 `stream` 하면 `started` 다음에 `error` 가 오고, 그 `executionId` 의 실행 줄이 FAILED 다
- **실행이 도는 동안 지워도 지운 채다.** `StubHermesRunsClient.beforeAwait(Runnable)` 로 `awaitCompletion` 앞에서
  `chat.delete(user, conversationId)` 를 부르고 turn 을 끝낸다. 끝난 뒤 `conversationsOf` 에 그 대화가 없고
  `ConversationRepository.findById` 로 읽은 행의 `deletedAt` 이 채워져 있으며 `hermesSessionId` 는 새 값이다
- **실행이 도는 동안 바꾼 이름이 남는다.** 같은 방법으로 `rename` 을 부르고 끝난 뒤 제목이 바뀐 이름이다

흐름 turn 의 `started` 는 `backend/src/test/java/com/bifos/assistant/orchestration/ResearchAndBuildFlowTest.java`
에 두 경우를 더한다.

- 받은 사건 가운데 `started` 가 하나이고 그 번호가 뿌리 실행이다
- Chief 가 도는 동안 대화를 지우면 흐름이 끝난 뒤에도 지운 채다. 이 파일의 `stub().willAnswer` 로
  Chief 입력(`CHIEF_MARK`)을 받는 자리에서 지우기를 부른다

**e2e**: `test/e2e/scenarios/conversation-manage.ts` 신규. `conversation-history.ts` 의 모양
(`call`, `expectStatus`, `step`, `expect`)을 따르고 `test/e2e/run.ts` 의 `SCENARIOS` 에서
`conversationHistoryScenario` 바로 뒤에 넣는다. import 도 같은 자리에 둔다.

- `PATCH /chat/conversations/{id}` 가 200 과 바뀐 제목을 준다
- `DELETE /chat/conversations/{id}` 가 204. 그 뒤 목록에 없고 `messages` 가 404, 그 대화로 보내면 404
- `context.tokens.kid` 로 `dad` 의 대화를 지우면 404
- 스트리밍 경로(`/chat/messages/stream`)의 첫 사건이 `started` 이고 대화 번호가 `done` 의 것과 같다.
  사건을 읽는 법은 `test/e2e/scenarios/streaming.ts` 의 `events` 함수를 따른다

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
node test/e2e/run.ts
scripts/check-public-safe.sh
```

모두 종료 코드 0 이어야 한다. `node test/e2e/run.ts` 는 `gradlew test` 뒤에 돌린다.

```bash
# cwd: 저장소 root
grep -rn 'findByUserIdOrderByUpdatedAtDesc' backend/src/main
grep -rn 'conversations.save' backend/src/main
```

앞의 것은 아무것도 나오지 않아야 한다.
뒤의 것은 새 대화를 만드는 자리와 `rename`, `delete` 만 나와야 한다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V{다음 번호}__conversation_deleted_at.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/Conversation.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ConversationRepository.java` | 수정. `touchSession` 포함 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatEvent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentRunner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/ResearchAndBuildFlow.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ConversationManageTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/orchestration/ResearchAndBuildFlowTest.java` | 수정 |
| `test/e2e/scenarios/conversation-manage.ts` | 신규 |
| `test/e2e/run.ts` | 수정 |

끝나면 `tasks/plan019-app-shell/index.json` 의 이 phase 를 `completed` 로 바꾸고 `current_phase` 를 2로 올린다.
