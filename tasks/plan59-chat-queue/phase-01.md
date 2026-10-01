# Phase 01. 대기 메시지 저장 모델

**Execution profile**: fast

## 목표

대기 메시지를 담는 표 `chat_pending_message` 와 그 엔티티, 저장소를 만든다.
뒤 phase 의 서비스가 이 저장소로 대기 행을 읽고 지운다.

**범위 외**: 대기 행을 쓰는 서비스와 경로(Phase 03), turn 종료 리스너(Phase 02).

## 컨텍스트

- 엔티티는 `backend/src/main/java/com/bifos/assistant/chat/domain/` 에, 저장소는 `backend/src/main/java/com/bifos/assistant/chat/infra/` 에 둔다.
- 엔티티 접근자는 Lombok `@Getter` 와 `@Accessors(fluent = true)` 로 만든다. 손으로 쓴 접근자는 Checkstyle `entityHandwrittenAccessor` 에 걸린다. 본보기는 `backend/src/main/java/com/bifos/assistant/connector/domain/ConnectorConnection.java` 다.
- `Instant.now()` 를 직접 부르지 않는다(`ArchitectureRules.NO_DIRECT_INSTANT_NOW`). 만든 시각은 팩터리 인자로 받는다.
- 저장소에 `@Transactional` 을 붙이지 않는다(`ArchitectureRules.TRANSACTIONAL_ONLY_IN_APPLICATION`). 트랜잭션은 부르는 서비스가 연다. 기존 `ConversationRepository` 의 `@Transactional` 은 기준 파일에 든 옛 위반이라 따라 하지 않는다.
- 문자열 본문은 `columnDefinition = "LONGTEXT"` 로 못 박는다. 본보기는 `ChatMessage.content` 다.
- 마이그레이션은 MySQL 과 H2 MySQL 모드에 함께 있는 문법만 쓴다. 지금 최신은 `V39__connector_attachments.sql` 이고 V40 은 다른 PR 이 쓴다. 이 phase 는 V41 을 쓴다. PR 을 열기 전과 머지 직전에 기준 브랜치의 최신 번호를 다시 보고 그 다음 번호로 옮긴다. 옮기는 것은 계획을 실행하는 쪽이 한다.

**근거 문서**: `docs/data-schema.md` 의 「chat_pending_message」 절, `docs/adr/ADR-047-응답-중에-보낸-메시지는-control-plane-이-쌓아-두고-다음-turn-으로-합쳐-보낸다.md`, `backend/AGENTS.md` 의 「엔티티와 마이그레이션은 따로 논다」

## 의도 메모

- 보낸 행에 `sent_at` 을 적어 남기는 안은 버렸다. 보낸 글은 `chat_message` 에 남고, 남긴 행은 「보낼 것이 있는가」 조회마다 걸러야 한다.
- 멈춤을 `conversation` 표의 칸으로 두는 안은 버렸다. 다른 작업이 같은 표를 고치고 있고, 멈춤은 대기 행이 있을 때만 뜻이 있다.
- 외래 키 제약은 두지 않는다. 이 저장소의 다른 표(`chat_attachment`, `chat_artifact`)와 같다.

## 작업 항목

### 1. `backend/src/main/resources/db/migration/V41__chat_pending_message.sql`

```sql
-- turn 이 도는 동안 사용자가 보낸 메시지를 보내기 전까지 둔다(ADR-047).
CREATE TABLE chat_pending_message (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    content LONGTEXT NOT NULL,
    held BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL
);

CREATE INDEX idx_chat_pending_message_conversation ON chat_pending_message (conversation_id, id);
```

### 2. `backend/src/main/java/com/bifos/assistant/chat/domain/ChatPendingMessage.java`

- `@Entity`, `@Table(name = "chat_pending_message")`, `@Getter`, `@Accessors(fluent = true)`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`.
- 칸: `Long id`(IDENTITY), `Long conversationId`(`conversation_id`), `Long userId`(`user_id`), `String content`(`columnDefinition = "LONGTEXT"`), `boolean held`, `Instant createdAt`(`created_at`). 모두 `nullable = false`.
- `public static ChatPendingMessage queued(Long conversationId, Long userId, String content, boolean held, Instant createdAt)`.
- `public static final String SEPARATOR = "\n\n";`
- `public static String merged(List<ChatPendingMessage> rows)`: `content` 를 받은 순서대로 `SEPARATOR` 로 잇는다.
- `public static int mergedLength(List<ChatPendingMessage> rows, String added)`: `rows` 를 이은 길이에 `added` 를 더한 길이다. `rows` 가 비어 있지 않으면 `SEPARATOR` 길이를 한 번 더한다. `added` 가 null 이면 `rows` 만 센다.

### 3. `backend/src/main/java/com/bifos/assistant/chat/infra/ChatPendingMessageRepository.java`

`JpaRepository<ChatPendingMessage, Long>` 를 잇는다. `@Transactional` 을 붙이지 않는다.

| 메서드 | 뜻 |
| --- | --- |
| `List<ChatPendingMessage> findByConversationIdOrderByIdAsc(Long conversationId)` | 쌓인 순서로 읽는다 |
| `@Modifying @Query("delete from ChatPendingMessage p where p.id in :ids") int deleteAllByIdIn(@Param("ids") List<Long> ids)` | 지운 행 수를 돌려준다. 빈 목록으로 부르지 않는다 |
| `@Modifying @Query("delete from ChatPendingMessage p where p.id = :id and p.conversationId = :conversationId") int deleteOne(@Param("id") Long id, @Param("conversationId") Long conversationId)` | 취소. 지운 행 수 |
| `@Modifying @Query("delete from ChatPendingMessage p where p.conversationId = :conversationId") int deleteAllOf(@Param("conversationId") Long conversationId)` | 대화를 지울 때 |
| `@Modifying @Query("update ChatPendingMessage p set p.held = :held where p.conversationId = :conversationId and p.held <> :held") int markHeld(@Param("conversationId") Long conversationId, @Param("held") boolean held)` | 대화의 멈춤을 한 번에 세우거나 내린다. 바뀐 행 수 |
| `@Query("select distinct p.conversationId from ChatPendingMessage p where p.conversationId not in (select h.conversationId from ChatPendingMessage h where h.held = true)") List<Long> findConversationsReadyToSend()` | 멈춘 행이 하나도 없는 대기 줄을 가진 대화 |

`@Modifying` 에는 `flushAutomatically = true, clearAutomatically = true` 를 준다.

### 4. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/chat/ChatPendingMessageMigrationTest.java`

구조는 `backend/src/test/java/com/bifos/assistant/chat/DelegationWakeMigrationTest.java` 를 따른다. Spring 문맥 없이 H2 MySQL 모드에 Flyway 를 끝까지 올린다.

- 정상: `chat_pending_message` 에 여섯 칸이 있고 `held` 의 기본값이 거짓이며 모든 칸이 NOT NULL 이다.
- 정상: 색인 `idx_chat_pending_message_conversation` 의 칸 순서가 `conversation_id`, `id` 다.
- 실패 입력: `content` 가 `NULL` 인 `INSERT` 가 예외로 거절된다.

### 5. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/chat/ChatPendingMessageRepositoryTest.java`

`@SpringBootTest`, `@ActiveProfiles("test")`, `@Import(ChatServiceTest.StubRuntime.class)` 로 띄운다. 저장소 호출은 `TransactionTemplate` 안에서 한다. `@BeforeEach` 와 `@AfterEach` 에서 `deleteAll()` 로 비운다. 검사들이 H2 를 함께 써서, 남은 대기 행은 뒤 phase 가 만드는 기동 확인이 다른 검사 문맥에서 turn 으로 보낸다.

- 정상: 두 대화에 행을 넣으면 `findByConversationIdOrderByIdAsc` 가 그 대화의 행만 넣은 순서로 준다.
- 정상: `markHeld(id, true)` 뒤 `findConversationsReadyToSend()` 에 그 대화가 없고 다른 대화는 있다. `markHeld(id, false)` 뒤 다시 있다.
- 실패 입력: `deleteOne` 에 다른 대화 번호를 주면 0 을 돌려주고 행이 남는다.
- 정상: `ChatPendingMessage.merged` 가 두 글을 빈 줄 하나로 잇고, `mergedLength` 가 그 길이와 같다.

## 검증

```bash
cd backend && ./gradlew test --tests '*ChatPendingMessageMigrationTest' --tests '*ChatPendingMessageRepositoryTest'
cd backend && ./gradlew archTest checkstyleMain checkstyleTest
```

둘 다 `BUILD SUCCESSFUL` 이어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V41__chat_pending_message.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/ChatPendingMessage.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ChatPendingMessageRepository.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatPendingMessageMigrationTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatPendingMessageRepositoryTest.java` | 신규 |
