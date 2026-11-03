# Phase 01. 위임 답 본문을 데이터 key 로 암호화한다

**Execution profile**: standard

## 목표

`agent_execution.output_text` 를 `chat_message.content` 와 같은 방식으로 저장할 때 암호화한다.
모델이 만든 답이고, 표에 주인 칸(`user_id`)이 있다.

대기 메시지는 이 계획에서 뺐다. `chat_pending_message` 테이블을 지웠고 대기 메시지는 `conversation.pending_messages` JSON 칸이 갖는다(ADR-20261102 / table-reduce-r2). 그 칸을 암호화할지는 따로 정한다.

**범위 외**: 대화 제목과 예약 작업(phase 02), 이미 있는 평문 줄 옮기기(phase 03).
알림, 할 일, 승인 기록, 실행 사건, 먼저 살펴보기 결과, 일반 Memory 본문은 이 계획에서 하지 않는다.

## 컨텍스트

**근거 문서**: `backend/docs/adr/ADR-20261008-data-encryption.md`, `backend/docs/data-schema.md` 의 「본문 칸과 운영 조회」, `backend/docs/data-schema.md` 의 「chat_message」, `backend/docs/data-schema.md`

- 따를 본보기는 메시지다.
  - 엔티티: `backend/src/main/java/com/bifos/assistant/chat/conversation/domain/ChatMessage.java` 의 `storedContent`, `contentKeyId`, `content()`, `detachPlainForSealing()`, `seal(...)`, `attachOpener(...)`
  - 쓰기: `backend/src/main/java/com/bifos/assistant/chat/conversation/infra/ChatMessageWritesImpl.java` 의 `persistOrMerge`. 빈 글로 넣고(`persist`, IDENTITY 라 바로 insert) 같은 트랜잭션에서 암호문으로 고친다
  - 읽기: `backend/src/main/java/com/bifos/assistant/chat/conversation/infra/ChatMessageLoadListener.java` 가 Hibernate `POST_LOAD` 에서 복호화 도구를 붙이고, `ChatMessageContents.open` 이 처음 꺼낼 때 푼다
  - 암호화 port 는 `backend/src/main/java/com/bifos/assistant/crypto/domain/TextCipher.java` 다. `seal(ownerUserId, aad, plain)` 이 `Optional<SealedText>` 를, `open(keyId, ownerUserId, aad, sealed)` 가 `Optional<String>` 을 준다. 암호화가 꺼져 있으면 `seal` 이 빈 값이고 평문으로 저장한다
- 위임 답
  - 엔티티 `backend/src/main/java/com/bifos/assistant/usage/execution/domain/AgentExecution.java` 의 `outputText`(`MEDIUMTEXT NULL`), 주인은 `userId`. 쓰기는 `recordOutput(String)` 하나이고 `backend/src/main/java/com/bifos/assistant/usage/execution/application/ExecutionCompletionRecorder.java` 의 두 자리(`execution.recordOutput(outputText)`)가 부른다
  - 읽기는 `backend/src/main/java/com/bifos/assistant/chat/turn/application/ChatDeliveryInput.java` 의 `result.outputText()` 와 `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolService.java` 의 `execution.outputText()` 두 곳이다
  - 실행 줄은 시작할 때 이미 저장돼 번호가 있다. 그래서 `recordOutput` 때 바로 암호화할 수 있다
  - 지운 대화의 정리(`AgentExecutionRepository.clearOutputsOf`)는 칸을 null 로 비운다. 이때 옆 칸도 null 로 비운다

## 의도 메모

- AAD 는 표와 줄 번호와 주인을 담는다. 위임 답은 `agent_execution:<id>:output:user:<user_id>` 다. 다른 줄로 옮긴 암호문은 풀리지 않는다
- 위임 답은 `usage` 패키지라 `chat` 의 리스너에 넣지 않는다. 읽는 곳이 둘뿐이라 `usage.execution.application` 에 `ExecutionOutputs.open(AgentExecution)` 을 두고 두 곳이 그것을 부른다. 엔티티의 `outputText()` 는 저장된 글을 그대로 낸다는 것을 Javadoc 에 적는다
- 풀지 못하면 `ChatMessage.UNREADABLE_CONTENT` 와 같은 글을 쓴다. 위임 답은 그 글 대신 null 로 보지 않는다. null 이면 `agent_status` 가 답이 없다고 판정한다

## 작업 항목

### 1. 마이그레이션

`backend/src/main/resources/db/migration/V<UTC 시각>__output_key.sql` 하나에 칸을 더한다. 시각은 `date -u +%Y%m%d%H%M%S` 로 얻는다.

```sql
ALTER TABLE agent_execution ADD COLUMN output_key_id BIGINT NULL;
```

외래 키를 두지 않는다(`backend/docs/data-schema.md`).

### 2. 위임 답

- `AgentExecution` 에 `outputKeyId`(`@Column(name = "output_key_id")`, `@Getter`) 와 `recordSealedOutput(String sealed, Long keyId)` 를 더한다. 평문을 적는 `recordOutput(String)` 은 그대로 둔다. 시험 여러 곳(`git grep -n "recordOutput(" backend/src/test`)이 평문 답을 준비하는 데 쓰고, 그 줄은 옛 평문 줄과 같게 읽힌다
- `ExecutionCompletionRecorder` 의 두 자리는 `TextCipher.seal(execution.userId(), aad, outputText)` 가 값을 주면 `recordSealedOutput` 을, 빈 값이면 지금처럼 `recordOutput` 을 부른다. `outputText` 가 null 이면 암호화하지 않는다
- `usage.execution.application.ExecutionOutputs` 를 더한다. `String open(AgentExecution)` 은 `outputKeyId` 가 null 이면 `outputText()` 를, 아니면 푼 글이나 `ChatMessage.UNREADABLE_CONTENT` 와 같은 글(`usage` 가 `chat` 을 import 하지 않게 같은 글을 상수로 둔다)을 낸다
- `ChatDeliveryInput` 과 `McpToolService` 의 두 읽기를 `ExecutionOutputs.open` 으로 바꾼다. `ChatDeliveryInput` 이 받는 `result` 의 타입이 실행 엔티티가 아니면 그 값을 만드는 자리에서 푼다
- `AgentExecutionRepository.clearOutputsOf` 의 JPQL 에 `e.outputKeyId = null` 을 더한다

### 3. 시험

- `backend/src/test/java/com/bifos/assistant/usage/ExecutionOutputEncryptionTest.java`(신규, `@BackendIntegrationTest`)
  - `ExecutionRecorder` 가 `ExecutionCompletionRecorder` 에 위임해 답을 적은 뒤 `output_text` 가 암호문이고 `ExecutionOutputs.open` 이 평문을 낸다
  - `output_key_id` 가 null 인 옛 줄은 평문으로 읽힌다
  - `clearOutputsOf` 뒤 두 칸이 모두 null 이다

## 검증

```bash
cd backend && ./gradlew test --tests '*ExecutionOutputEncryptionTest' --tests '*ConversationPurgerTest' --tests '*ChatMessageEncryptionTest'
cd backend && ./gradlew test
cd backend && ./gradlew qualityCheck
scripts/checks/check-mysql-migration.sh
```

넷 다 실패 없이 끝난다. 마지막 명령은 Docker 가 있어야 돈다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V*__output_key.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/execution/domain/AgentExecution.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/execution/application/ExecutionCompletionRecorder.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/execution/application/ExecutionOutputs.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/execution/infra/AgentExecutionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/application/ChatDeliveryInput.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/ExecutionOutputEncryptionTest.java` | 신규 |
| `backend/docs/data-schema.md` | 수정 |
