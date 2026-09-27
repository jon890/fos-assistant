# Phase 01. Control Plane 이 대화를 공개 식별자로 주고받는다

**Execution profile**: deep

## 목표

대화 표에 UUID 공개 식별자 `public_id` 를 더하고, Control Plane API 가 대화를 그 식별자로만 주고받게 한다.
대화 번호는 Control Plane 안에서만 쓴다. 주소로 대화를 맞히거나 셀 수 없게 하려는 것이다.

**범위 외**: web 화면과 서버 라우트(phase-02). 메시지, 첨부, 실행의 번호는 그대로 숫자다.

**이 phase 만 main 에 머지하지 않는다.** API 가 바뀌어 web 이 깨진다. phase-02 까지 같은 브랜치에 쌓고 한 PR 로 머지한다.

## 컨텍스트

**근거 문서**:
- `docs/adr/ADR-025-대화는-주소에-공개-식별자를-쓰고-번호는-안에만-둔다.md`
- `docs/data-schema.md` 「conversation」 의 `public_id` 줄과 그 아래 문단
- `docs/code-architecture.md` 「대화」 의 「경로」 표와 그 아래 문단

계획을 쓸 때의 코드다. 시작할 때 다시 연다.

| 위치 | 지금 모양 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/chat/domain/Conversation.java` | `@Id @GeneratedValue(strategy = IDENTITY) Long id`. 칸마다 `@Column`. `startedBy(userId, title, agentId)` 로 만든다. 읽기 메서드는 `id()`, `userId()` 처럼 레코드 모양이다 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ConversationRepository.java` | `JpaRepository<Conversation, Long>`. `findByIdAndUserIdAndDeletedAtIsNull(Long, Long)` |
| `backend/src/main/java/com/bifos/assistant/chat/application/ConversationAccess.java` | `requireOwn(CurrentUser, Long)` 하나. 없거나 남의 것이거나 지운 대화에 `CONVERSATION_NOT_FOUND` |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatController.java` | 대화 경로 `regenerate/stream`, `PATCH`, `DELETE`, `messages` 가 `@PathVariable Long conversationId`. `send` 와 `stream` 은 본문의 `conversationId` 를 서비스에 넘긴다. `stream` 은 가상 스레드 안에서 서비스를 부르고 `ApiException` 을 SSE `error` 사건으로 바꾼다. `viewOf(Conversation)` 가 `ConversationView` 를 만든다 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/AttachmentController.java` | `@RequestMapping("/api/v1/chat/conversations/{conversationId}/attachments")`. 세 메서드가 `@PathVariable Long conversationId` |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatDtos.java` | `SendMessageRequest(Long conversationId, ...)`, `StartConversationResponse(Long conversationId)`, `SendMessageResponse(Long conversationId, Long executionId, String assistantText)`, `ConversationView(Long id, String title, String agentCode, String agentName, Instant updatedAt)` |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatEvent.java` | `Long conversationId` 칸. `started(Long, Long)`, `done(Long, Long, Long)`, `stopped(Long, Long, Long)` |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatTurn.java` | `record ChatTurn(Long conversationId, Long executionId, String assistantText, Long messageId, boolean cancelled)` |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | `ChatEvent.started(conversation.id(), ...)` 세 곳, `ChatEvent.done/stopped(turn.conversationId(), ...)` 세 곳 |
| `backend/src/main/java/com/bifos/assistant/usage/presentation/UsageDtos.java` | `ExecutionView(Long executionId, Long conversationId, ...)`. `from(AgentExecution, Agent, boolean)` 가 `execution.conversationId()` 를 싣는다 |
| `backend/src/main/java/com/bifos/assistant/usage/presentation/UsageController.java` | `myExecutions` 가 `ExecutionView.from` 으로 목록을 만든다 |
| `backend/src/main/java/com/bifos/assistant/shared/error/GlobalExceptionHandler.java` | `ApiException`, `MethodArgumentNotValidException`, `MaxUploadSizeExceededException`, `Exception` 처리기. 경로 변수 형식 오류를 따로 받지 않는다 |
| `backend/src/main/resources/db/migration/` | 마지막이 `V22__agent_starter.sql`. MySQL 8.4 문법 |
| `test/e2e/harness.ts` | `call(context, path, options)` 가 Control Plane 을 직접 부른다 |

**테스트는 엔티티로 스키마를 만들고 운영은 Flyway 가 만든 스키마를 검증한다.** 둘이 어긋나도 테스트가 통과한다.
`backend/AGENTS.md` 「엔티티와 마이그레이션은 따로 논다」 를 읽는다.

## 의도 메모

- **PK 는 바꾸지 않는다.** 다른 표의 외래 키가 모두 대화 번호다. 공개 식별자는 칸 하나를 더한다
- **저장은 `BINARY(16)` 이다.** 문자열 36자보다 작고, Hibernate 가 UUID 를 MySQL 에서 기본으로 이렇게 둔다
- **새 대화는 UUID v7 이다.** Hibernate 7 의 `@UuidGenerator(style = UuidGenerator.Style.VERSION_7)` 을 쓴다. 새 의존성을 넣지 않는다
- **이미 있는 대화는 마이그레이션이 v4 임의 값을 채운다.** MySQL 에 v7 함수가 없다. 주소에 쓰는 데는 버전이 상관없다. `UUID()` 는 v1 이라 DB 호스트의 MAC 주소와 시각이 들어가므로 쓰지 않는다
- **번호로 바꾸는 자리는 컨트롤러 하나다.** `application` 서비스의 시그니처는 `Long conversationId` 그대로 둔다.
  서비스가 다시 `requireOwn` 을 불러 질의가 하나 늘지만, 바뀌는 파일이 컨트롤러로 모인다
- **`stream` 경로는 스트림 안에서 바꾼다.** 지금 없는 대화는 SSE `error` 사건으로 알린다. 스트림을 열기 전에 바꾸면 404 JSON 이 되어 web 이 받는 모양이 달라진다.
  `regenerate/stream` 은 지금도 스트림을 열기 전에 주인을 확인하므로 그 자리에서 바꾼다
- `by-number` 경로는 옛 링크를 넘겨 주는 데만 쓴다. 주인이 아니면 다른 경로와 같은 `CONVERSATION_NOT_FOUND` 다

## Blocked 조건

- `backend/src/main/resources/db/migration/V23__*.sql` 이 이미 있다 → 다음 빈 번호를 쓰고 이 문서의 `V23` 을 모두 그 번호로 읽는다. 멈추지 않는다

## 작업 항목

### 1. 마이그레이션 `V23__conversation_public_id.sql`

맨 위에 한국어 주석 두 줄로 까닭을 적는다. 기존 마이그레이션 파일들처럼.

```sql
ALTER TABLE conversation ADD COLUMN public_id BINARY(16) NULL;

-- 이미 있는 대화에 v4 임의 값을 채운다. RANDOM_BYTES 는 줄마다 새로 만든다.
UPDATE conversation SET public_id = UNHEX(CONCAT(
    HEX(RANDOM_BYTES(6)),
    '4', SUBSTR(HEX(RANDOM_BYTES(2)), 2),
    HEX((ASCII(RANDOM_BYTES(1)) & 0x3F) | 0x80),
    HEX(RANDOM_BYTES(7))
)) WHERE public_id IS NULL;

ALTER TABLE conversation MODIFY public_id BINARY(16) NOT NULL;
ALTER TABLE conversation ADD UNIQUE KEY uk_conversation_public_id (public_id);
```

16진수 글자 수는 12, 4, 2, 14 로 합이 32 다. 셋째 조각이 variant 비트(`10xx`)를, 둘째 조각의 `4` 가 version 을 정한다.

### 2. `Conversation` 에 `publicId`

```java
@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
@JdbcTypeCode(SqlTypes.BINARY)
@Column(name = "public_id", nullable = false, updatable = false, unique = true, columnDefinition = "BINARY(16)")
private UUID publicId;
```

읽기 메서드 `publicId()` 를 더한다. `startedBy` 는 그대로다. 값은 넣을 때 Hibernate 가 채운다.
`@JdbcTypeCode(SqlTypes.BINARY)` 는 테스트의 H2 와 운영의 MySQL 이 같은 바이트 16개로 저장하게 한다.

### 3. `ConversationRepository` 와 `ConversationAccess`

- `ConversationRepository` 에 `Optional<Conversation> findByPublicIdAndUserIdAndDeletedAtIsNull(UUID publicId, Long userId)`
- `ConversationAccess` 에 `Conversation requireOwn(CurrentUser user, UUID publicId)` 와 `Long requireOwnId(CurrentUser user, UUID publicId)`.
  없으면 기존 `requireOwn(CurrentUser, Long)` 과 같은 `CONVERSATION_NOT_FOUND` 와 같은 메시지를 던진다
- 대화 번호에서 공개 식별자를 얻어야 하는 자리(사건, 보내기 응답)는 이미 `Conversation` 을 들고 있다. 들고 있지 않으면 `ConversationRepository.findById` 로 읽는다

### 4. 사건과 turn 결과

- `ChatEvent.conversationId` 의 타입을 `UUID` 로 바꾼다. `started`, `done`, `stopped` 의 첫 인자도 `UUID` 다
- `ChatTurn` 에 `UUID conversationPublicId` 를 더한다. `ChatTurn` 을 만드는 자리마다 대화의 `publicId()` 를 넣는다.
  `ChatService` 두 곳과 `orchestration/application/ResearchAndBuildFlow.java` 두 곳이다
- `ChatService` 의 `ChatEvent.started(conversation.id(), ...)` 는 `conversation.publicId()` 로, `done/stopped(turn.conversationId(), ...)` 는 `turn.conversationPublicId()` 로 바꾼다
- 사건 JSON 의 칸 이름 `conversationId` 는 그대로다. 값만 UUID 문자열이 된다

### 5. 컨트롤러와 DTO

`ChatDtos`:
- `SendMessageRequest.conversationId` → `UUID` (없으면 새 대화)
- `StartConversationResponse(UUID conversationId)`, `SendMessageResponse(UUID conversationId, Long executionId, String assistantText)`
- `ConversationView(UUID id, ...)` 나머지 칸은 그대로
- 새 `ConversationRefView(UUID id)`

`ChatController`:
- 대화 경로 네 개(`regenerate/stream`, `PATCH`, `DELETE`, `messages`)를 `@PathVariable UUID conversationId` 로 받고 `access.requireOwnId(user, conversationId)` 로 번호를 얻어 서비스에 넘긴다.
  `regenerate/stream` 은 지금 `chat.requireConversation` 을 부르는 자리를 이것으로 바꾼다
- `send`: 본문 `conversationId` 가 null 이 아니면 `requireOwnId` 로 바꿔 넘긴다. 응답은 `turn.conversationPublicId()`
- `stream`: 스트림에 넘기는 람다 안에서 바꾼다. 의도 메모를 본다
- `start`: `chat.startEmpty(...).publicId()`
- `viewOf`: `conversation.publicId()`
- 새 경로 `@GetMapping("/conversations/by-number/{number}")` → `ConversationRefView`. `access.requireOwn(user, number).publicId()`

`AttachmentController`: 세 메서드를 `@PathVariable UUID conversationId` 로 받고 `requireOwnId` 로 번호를 얻어 `AttachmentService` 에 넘긴다.

컨트롤러가 `ConversationAccess` 를 주입받는다. `chat.application` 에 있으므로 `presentation → application` 방향이 지켜진다.

### 6. 실행 목록의 대화

`ExecutionView.conversationId` 를 `UUID` 로 바꾼다.
`UsageController.myExecutions` 가 목록의 대화 번호를 모아 `ConversationRepository.findAllById` 로 한 번에 읽고 번호에서 `publicId` 로 가는 `Map` 을 만든다.
`ExecutionView.from` 에 그 값을 인자로 받게 한다. 대화 번호가 null 인 실행이면 null 이다.
실행마다 한 번씩 읽지 않는다.

### 7. 경로 변수 형식 오류

`GlobalExceptionHandler` 에 `MethodArgumentTypeMismatchException` 처리기를 더한다.
400 과 `ErrorCode.VALIDATION_FAILED` 를 기존 `MethodArgumentNotValidException` 처리기와 같은 모양으로 돌려준다.
지금은 `Exception` 처리기로 떨어진다.

### 8. 테스트 지원 코드와 기존 검사

- `backend/src/test/java` 에서 대화 경로를 MockMvc 로 부르거나 응답의 `conversationId` 와 `id` 를 숫자로 읽는 검사를 공개 식별자로 고친다.
  `grep -rln "conversations/\|conversationId" backend/src/test/java` 가 대상을 낸다. 서비스를 직접 부르는 검사는 서비스 시그니처가 그대로라 바뀌지 않는다
- `test/e2e/scenarios/` 에서 대화를 가리키는 값이 문자열이 된다. `conversation.id`, `conversationId` 를 숫자로 다루는 곳(`Number(...)`, 산술, 숫자 비교)을 고친다

### 9. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/chat/` 에 `ConversationPublicIdTest` 를 둔다.

| 경우 | 기대 |
| --- | --- |
| 새 대화를 만든다 | 응답 `conversationId` 가 UUID 이고 `version()` 이 7 |
| 목록 | `id` 가 만든 대화의 공개 식별자와 같다 |
| 공개 식별자로 메시지 목록, 이름 바꾸기, 지우기 | 지금과 같은 결과 |
| 남의 대화의 공개 식별자 | `CONVERSATION_NOT_FOUND` |
| 모양이 틀린 식별자(`abc`) | 400 `VALIDATION_FAILED` |
| `by-number/{내 대화 번호}` | 그 대화의 공개 식별자 |
| `by-number/{남의 대화 번호}`, 지운 대화 번호 | `CONVERSATION_NOT_FOUND` |
| 스트리밍 보내기의 `started` 와 `done` | `conversationId` 가 같은 공개 식별자 |

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
node test/e2e/run.ts
grep -rn "Long conversationId" backend/src/main/java/com/bifos/assistant/chat/presentation backend/src/main/java/com/bifos/assistant/usage/presentation
```

앞의 둘이 통과해야 한다. 셋째는 아무것도 내지 않아야 한다. `gradlew` 의 위치는 `backend/AGENTS.md` 를 본다.

마이그레이션은 테스트가 돌리지 않는다. docker 가 있으면 `mysql:8.4` 컨테이너에 V1 부터 V23 까지 적용해 대화 두 줄에 서로 다른 16바이트 값이 들어가는지 본다.
docker 가 없으면 그 사실을 phase 보고에 적는다. 배포할 때 Flyway 성공과 `Schema validation` 을 확인하는 것으로 갈음한다.

끝나면 `tasks/plan028-conversation-public-id/index.json` 의 이 phase 를 `completed` 로, `current_phase` 를 2 로 바꾼다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V23__conversation_public_id.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/Conversation.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ConversationRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ConversationAccess.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatEvent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatTurn.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/ResearchAndBuildFlow.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/AttachmentController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/presentation/UsageDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/presentation/UsageController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/GlobalExceptionHandler.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ConversationPublicIdTest.java` | 신규 |
| `backend/src/test/java/` 의 대화 경로 검사 | 수정 |
| `test/e2e/scenarios/` 의 대화 번호를 숫자로 다루는 시나리오 | 수정 |
