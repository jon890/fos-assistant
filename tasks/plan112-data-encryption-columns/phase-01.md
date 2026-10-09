# Phase 01. 대기 메시지와 위임 답 본문을 데이터 key 로 암호화한다

**Execution profile**: standard

## 목표

`chat_pending_message.content` 와 `agent_execution.output_text` 를 `chat_message.content` 와 같은 방식으로 저장할 때 암호화한다.
둘 다 사용자가 쓴 글이거나 모델이 만든 답이고, 표에 주인 칸(`user_id`)이 있다.

**범위 외**: 대화 제목과 예약 작업(phase 02), 이미 있는 평문 줄 옮기기(phase 03).
알림, 할 일, 승인 기록, 실행 사건, 먼저 살펴보기 결과, 일반 Memory 본문은 이 계획에서 하지 않는다.

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261008-data-encryption.md`, `docs/backend/schema/README.md` 의 「본문 칸과 운영 조회」, `docs/backend/schema/chat.md` 의 「chat_message」, `docs/backend/schema/crypto.md`

- 따를 본보기는 메시지다.
  - 엔티티: `backend/src/main/java/com/bifos/assistant/chat/domain/ChatMessage.java` 의 `storedContent`, `contentKeyId`, `content()`, `detachPlainForSealing()`, `seal(...)`, `attachOpener(...)`
  - 쓰기: `backend/src/main/java/com/bifos/assistant/chat/infra/ChatMessageWritesImpl.java` 의 `persistOrMerge`. 빈 글로 넣고(`persist`, IDENTITY 라 바로 insert) 같은 트랜잭션에서 암호문으로 고친다
  - 읽기: `backend/src/main/java/com/bifos/assistant/chat/infra/ChatMessageLoadListener.java` 가 Hibernate `POST_LOAD` 에서 복호화 도구를 붙이고, `ChatMessageContents.open` 이 처음 꺼낼 때 푼다
  - 암호화 port 는 `backend/src/main/java/com/bifos/assistant/crypto/domain/TextCipher.java` 다. `seal(ownerUserId, aad, plain)` 이 `Optional<SealedText>` 를, `open(keyId, ownerUserId, aad, sealed)` 가 `Optional<String>` 을 준다. 암호화가 꺼져 있으면 `seal` 이 빈 값이고 평문으로 저장한다
- 대기 메시지
  - 엔티티 `backend/src/main/java/com/bifos/assistant/chat/domain/ChatPendingMessage.java`. 본문 칸은 `content`(`LONGTEXT NOT NULL`), 주인은 `userId`, 대화는 `conversationId`. `queued(...)` 가 만들고, `merged(rows)` 와 `mergedLength(rows, text)` 가 본문을 읽는다
  - 저장은 `backend/src/main/java/com/bifos/assistant/chat/application/PendingMessageService.java` 의 `pendingMessages.save(ChatPendingMessage.queued(...))` 한 곳이다
  - 그 밖의 읽기는 `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatDtos.java` 의 대기 줄 응답이다
- 위임 답
  - 엔티티 `backend/src/main/java/com/bifos/assistant/usage/domain/AgentExecution.java` 의 `outputText`(`MEDIUMTEXT NULL`), 주인은 `userId`. 쓰기는 `recordOutput(String)` 하나이고 `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionRecorder.java` 의 두 자리(`execution.recordOutput(outputText)`)가 부른다
  - 읽기는 `backend/src/main/java/com/bifos/assistant/chat/application/ChatDeliveryInput.java` 의 `result.outputText()` 와 `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolService.java` 의 `execution.outputText()` 두 곳이다
  - 실행 줄은 시작할 때 이미 저장돼 번호가 있다. 그래서 `recordOutput` 때 바로 암호화할 수 있다
  - 지운 대화의 정리(`AgentExecutionRepository.clearOutputsOf`)는 칸을 null 로 비운다. 이때 옆 칸도 null 로 비운다

## 의도 메모

- AAD 는 표와 줄 번호와 주인을 담는다. 대기 메시지는 `chat_pending_message:<id>:conversation:<conversation_id>:user:<user_id>`, 위임 답은 `agent_execution:<id>:output:user:<user_id>` 다. 다른 줄로 옮긴 암호문은 풀리지 않는다
- 위임 답은 `usage` 패키지라 `chat` 의 리스너에 넣지 않는다. 읽는 곳이 둘뿐이라 `usage.application` 에 `ExecutionOutputs.open(AgentExecution)` 을 두고 두 곳이 그것을 부른다. 엔티티의 `outputText()` 는 저장된 글을 그대로 낸다는 것을 Javadoc 에 적는다
- 풀지 못하면 `ChatMessage.UNREADABLE_CONTENT` 와 같은 글을 쓴다. 위임 답은 그 글 대신 null 로 보지 않는다. null 이면 `agent_status` 가 답이 없다고 판정한다
- 대기 메시지는 합쳐 보낸 뒤 지운다. 수명이 짧지만 보내기 전에 백업에 남을 수 있어 같이 한다

## 작업 항목

### 1. 마이그레이션

`backend/src/main/resources/db/migration/V<UTC 시각>__pending_and_output_content_key.sql` 하나에 두 칸을 더한다. 시각은 `date -u +%Y%m%d%H%M%S` 로 얻는다.

```sql
ALTER TABLE chat_pending_message ADD COLUMN content_key_id BIGINT NULL;
ALTER TABLE agent_execution ADD COLUMN output_key_id BIGINT NULL;
```

외래 키를 두지 않는다(`docs/backend/schema/crypto.md`).

### 2. 대기 메시지

- `ChatPendingMessage` 의 `content` 필드를 `ChatMessage` 처럼 `storedContent` 로 바꾸고(`@Column(name = "content")`, `@Getter`), `contentKeyId`, `@Transient plain`, `@Transient MessageContentOpener` 를 둔다. 엔티티 접근자는 Lombok 으로 만든다(Checkstyle `entityHandwrittenAccessor`)
- 본문을 내는 `content()` 는 `ChatMessage.content()` 와 같은 규칙으로 푼다. `merged` 와 `mergedLength` 는 `content()` 를 쓴다
- 복호화 도구의 형식은 `MessageContentOpener` 가 `ChatMessage` 를 받으므로, 대기 메시지용 함수형 인터페이스 `PendingContentOpener` 를 `chat.domain` 에 더한다
- `ChatMessageContents` 에 대기 메시지의 `seal` 과 `open` 을 더하고, `ChatMessageLoadListener.onPostLoad` 가 `ChatPendingMessage` 에도 도구를 붙인다
- `PendingMessageService` 는 저장을 `pendingMessages.save(...)` 대신 새 `chat.infra` 쓰기 도구로 바꾼다. 빈 글로 `saveAndFlush` 한 뒤 번호로 암호화해 다시 저장한다. 그 메서드는 이미 열린 트랜잭션 안에서 돈다

### 3. 위임 답

- `AgentExecution` 에 `outputKeyId`(`@Column(name = "output_key_id")`, `@Getter`) 와 `recordSealedOutput(String sealed, Long keyId)` 를 더한다. 평문을 적는 `recordOutput(String)` 은 그대로 둔다. 시험 여러 곳(`git grep -n "recordOutput(" backend/src/test`)이 평문 답을 준비하는 데 쓰고, 그 줄은 옛 평문 줄과 같게 읽힌다
- `ExecutionRecorder` 의 두 자리는 `TextCipher.seal(execution.userId(), aad, outputText)` 가 값을 주면 `recordSealedOutput` 을, 빈 값이면 지금처럼 `recordOutput` 을 부른다. `outputText` 가 null 이면 암호화하지 않는다
- `usage.application.ExecutionOutputs` 를 더한다. `String open(AgentExecution)` 은 `outputKeyId` 가 null 이면 `outputText()` 를, 아니면 푼 글이나 `ChatMessage.UNREADABLE_CONTENT` 와 같은 글(`usage` 가 `chat` 을 import 하지 않게 같은 글을 상수로 둔다)을 낸다
- `ChatDeliveryInput` 과 `McpToolService` 의 두 읽기를 `ExecutionOutputs.open` 으로 바꾼다. `ChatDeliveryInput` 이 받는 `result` 의 타입이 실행 엔티티가 아니면 그 값을 만드는 자리에서 푼다
- `AgentExecutionRepository.clearOutputsOf` 의 JPQL 에 `e.outputKeyId = null` 을 더한다

### 4. 시험

- `backend/src/test/java/com/bifos/assistant/chat/PendingMessageEncryptionTest.java`(신규, `@BackendIntegrationTest`)
  - 대기 줄을 넣으면 데이터베이스의 `content` 가 `v1.` 로 시작하고 평문 표식을 담지 않으며 `content_key_id` 가 있다
  - 합친 글(`ChatPendingMessage.merged`)은 평문이다
  - 두 대기 줄의 `content` 를 맞바꾸면 둘 다 읽을 수 없는 글이다
- `backend/src/test/java/com/bifos/assistant/usage/ExecutionOutputEncryptionTest.java`(신규, `@BackendIntegrationTest`)
  - `ExecutionRecorder` 가 답을 적은 뒤 `output_text` 가 암호문이고 `ExecutionOutputs.open` 이 평문을 낸다
  - `output_key_id` 가 null 인 옛 줄은 평문으로 읽힌다
  - `clearOutputsOf` 뒤 두 칸이 모두 null 이다

## 검증

```bash
cd backend && ./gradlew test --tests '*PendingMessageEncryptionTest' --tests '*ExecutionOutputEncryptionTest' --tests '*ConversationPurgerTest' --tests '*ChatMessageEncryptionTest'
cd backend && ./gradlew test
cd backend && ./gradlew qualityCheck
scripts/check-mysql-migration.sh
```

넷 다 실패 없이 끝난다. 마지막 명령은 Docker 가 있어야 돈다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V*__pending_and_output_content_key.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/ChatPendingMessage.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/PendingContentOpener.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ChatMessageContents.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ChatMessageLoadListener.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ChatPendingMessageWrites.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/PendingMessageService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatDeliveryInput.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/AgentExecution.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionRecorder.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionOutputs.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/infra/AgentExecutionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/PendingMessageEncryptionTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/usage/ExecutionOutputEncryptionTest.java` | 신규 |
| `docs/backend/schema/chat.md` | 수정 |
| `docs/backend/schema/execution.md` | 수정 |
| `docs/backend/schema/README.md` | 수정 |
