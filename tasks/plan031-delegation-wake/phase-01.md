# Phase 01. 전한 결과와 자동 turn 횟수를 저장하고 SYSTEM 메시지를 만든다

**Execution profile**: deep

## 목표

부모 대화를 깨우는 데 필요한 저장 칸과 도메인 값을 만든다.
부모가 끝난 결과를 이미 받은 경우(`agent_status`, `agent_stop`, `agent_delegate` 의 `SUBMIT_FAILED`)를 전한 것으로 적는다.

**범위 외**: 깨우는 판정과 자동 turn 실행(phase 02), 대화 단위 SSE 와 화면(phase 03).

## 컨텍스트

맡긴 자식 실행이 끝나면 Control Plane 이 부모 대화의 다음 turn 을 열어 결과를 전한다. 이 phase 는 그 판정이 읽을 상태만 만든다.
경로는 `backend/src/main/java/com/bifos/assistant/` 기준이다.

- 위임 실행은 `agent_execution` 줄이고 `delegation_key` 가 채워져 있다. 답은 `output_text` 에 있다. 엔티티는 `usage/domain/AgentExecution.java`.
- `chat_message.role` 은 `VARCHAR(20)` 이고 값은 `chat/domain/MessageRole.java`(`USER`, `ASSISTANT`)이다. 스키마는 바꾸지 않고 enum 값만 더한다.
- 대화 엔티티는 `chat/domain/Conversation.java`. 칸 하나만 바꾸는 갱신은 `chat/infra/ConversationRepository.java` 의 `@Modifying @Transactional @Query("update Conversation c set ...")` 메서드로 한다(`fillTitleIfBlank`, `touchSession` 이 선례다). 엔티티를 통째로 저장하지 않는다.
- 마이그레이션은 `backend/src/main/resources/db/migration/` 이고 마지막 번호는 `V36__accountbook_connection.sql` 이다.
- **테스트 DB 는 Flyway 를 쓰지 않는다.** `backend/src/test/resources/application-test.yml` 이 `flyway.enabled: false`, `ddl-auto: create-drop` 이라 엔티티로 스키마를 만든다. 마이그레이션은 `*MigrationTest` 가 Flyway 를 직접 돌려 확인한다. 선례는 `backend/src/test/java/com/bifos/assistant/connector/AccountbookConnectionMigrationTest.java` 다. `backend/AGENTS.md` 의 「엔티티와 마이그레이션은 따로 논다」 절을 읽는다.
- `agent_status` 와 `agent_stop` 의 응답은 `mcp/application/McpToolService.java` 의 `agentStatus`, `agentStop`, `statusOf` 가 만든다. 그 테스트 `backend/src/test/java/com/bifos/assistant/mcp/application/McpToolServiceTest.java:35` 는 `new McpToolService(...)` 로 직접 만든다.
- `agent_delegate` 는 `orchestration/application/AgentDelegationService.java` 의 `delegate` 다. 실행 줄은 생겼는데 제출 전에 끝나면 `settled && !handoff.submitted()` 분기에서 `SUBMIT_FAILED` 를 돌려준다. 부모는 번호 없이 실패만 받는다.

**근거 문서**: `docs/data-schema.md` 의 `conversation`, `chat_message`, `agent_execution` 절,
`docs/flow.md` 의 「다른 에이전트에게 맡길 때」 와 「위임 결과가 도착했을 때」 절,
`docs/adr/ADR-040-위임-결과는-control-plane-이-부모-대화의-다음-turn-을-열어-전한다.md`

## 의도 메모

- 전했는지를 별도 표로 두지 않고 `agent_execution.result_delivered_at` 에 둔다. 한 실행의 결과는 한 번만 전한다.
- `CANCELLED` 를 돌려줄 때도 적는다. 깨우지 않는 상태지만 적어 두면 판정이 단순하다. `RUNNING` 은 적지 않는다.
- `SUBMIT_FAILED` 로 끝난 줄을 적지 않으면, 부모가 번호를 모르는 실패 결과로 자동 turn 이 열린다.

## 작업 항목

### 1. `backend/src/main/resources/db/migration/V37__delegation_wake.sql`

- `ALTER TABLE conversation ADD COLUMN auto_turn_count INT NOT NULL DEFAULT 0`
- `ALTER TABLE agent_execution ADD COLUMN result_delivered_at DATETIME(6) NULL`
- 색인 `idx_agent_execution_conversation_delivered` 를 `agent_execution(conversation_id, result_delivered_at, status)` 에 만든다.

### 2. `chat/domain/MessageRole.java`, `chat/domain/ChatMessage.java`

- `MessageRole` 에 `SYSTEM` 을 더한다.
- `ChatMessage.fromSystem(Long conversationId, String content)` 를 더한다. `senderUserId`, `executionId`, `replacesMessageId` 는 null 이다.
- `grep -rn "MessageRole\." backend/src/main` 으로 role 을 둘로 가정한 분기를 찾아 `SYSTEM` 에서 예외가 나지 않게 한다. 다시 생성의 판정은 phase 02 가 바꾼다.

### 3. `chat/domain/Conversation.java`, `chat/infra/ConversationRepository.java` — `auto_turn_count`

- `Conversation` 에 `@Column(name = "auto_turn_count", nullable = false) private int autoTurnCount;` 와 읽기 메서드 `autoTurnCount()` 를 더한다.
- `ConversationRepository` 에 둘을 더한다.
  - `int resetAutoTurns(Long id)`: `update Conversation c set c.autoTurnCount = 0 where c.id = :id and c.autoTurnCount <> 0`
  - `int incrementAutoTurns(Long id)`: `update Conversation c set c.autoTurnCount = c.autoTurnCount + 1 where c.id = :id`
- `chat/application/ChatService.java` 의 `saveQuestion` 안 `TurnIntent.Fresh` 분기에서 사용자 메시지를 저장하는 같은 트랜잭션으로 `conversations.resetAutoTurns(conversation.id())` 를 부른다.

### 4. `usage/domain/AgentExecution.java`, `usage/infra/AgentExecutionRepository.java` — `result_delivered_at`

- `AgentExecution` 에 `@Column(name = "result_delivered_at") private Instant resultDeliveredAt;` 와 `resultDeliveredAt()` 을 더한다.
- `AgentExecutionRepository` 에 `@Modifying @Transactional` 쿼리 `int markResultDelivered(Long id, Instant at)` 를 더한다. 조건은 `where e.id = :id and e.resultDeliveredAt is null` 이다.

### 5. `mcp/application/McpToolService.java` — 받은 결과를 적는다

- 생성자에 `AgentExecutionRepository` 를 더한다.
- `agentStatus` 와 `agentStop` 이 돌려주는 실행이 `SUCCEEDED`, `FAILED`, `CANCELLED` 이면 `markResultDelivered(execution.id(), Instant.now())` 를 부른다. 권한 판정(`delegations.status`, `delegations.stop`)을 지나 실행이 있을 때만 부른다.
- `backend/src/test/java/com/bifos/assistant/mcp/application/McpToolServiceTest.java` 의 생성자 호출에 `mock(AgentExecutionRepository.class)` 를 더한다.

### 6. `orchestration/application/AgentDelegationService.java` — `SUBMIT_FAILED` 로 끝난 줄

- `settled && !handoff.submitted()` 분기에서 `rejected(...)` 를 돌려주기 전에 `executions.markResultDelivered(execution.id(), Instant.now())` 를 부른다.

### 7. `McpToolService` 도구 설명

`agent_delegate` 와 `agent_status` 의 `description` 을 바꾼다. 글은 그대로 옮긴다.

- `agent_delegate`: 「다른 에이전트에게 일을 맡기고 실행 번호를 바로 돌려받는다. 끝날 때까지 기다리지 않는다. agent_code 에는 agent_list 로 받은 code 를, task 에는 그 에이전트에게 줄 지시를 넣는다. 맡긴 뒤 남은 일을 계속하고, 할 일이 끝나면 맡긴 일을 알리고 답을 마친다. 맡긴 일이 끝나면 그 결과가 이 대화의 다음 차례에 자동으로 전달된다.」
- `agent_status`: 「다른 에이전트에게 맡긴 실행의 지금 상태와 결과를 읽는다. execution_id 에는 agent_delegate 로 받은 번호를 넣는다. 결과는 끝나면 자동으로 전달되므로 기다리려고 반복해서 부르지 않는다. 사용자가 진행 상황을 물을 때 한 번 부른다.」

### 8. 테스트

- `backend/src/test/java/com/bifos/assistant/chat/DelegationWakeMigrationTest.java`(신규). `AccountbookConnectionMigrationTest` 처럼 Flyway 로 전체 마이그레이션을 적용한 뒤 `conversation.auto_turn_count`(기본값 0), `agent_execution.result_delivered_at`, 색인 `idx_agent_execution_conversation_delivered` 가 있는지 확인한다.
- `backend/src/test/java/com/bifos/assistant/mcp/McpAgentToolsTest.java` 에 더한다.
  - 끝난 위임 실행을 `agent_status` 로 읽으면 `result_delivered_at` 이 채워진다
  - `RUNNING` 을 읽으면 비어 있다
  - 물을 수 없는 실행(`NOT_FOUND`)은 적지 않는다
- `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` 에 더한다. `auto_turn_count` 를 3 으로 둔 대화에 사용자 질문을 보내면 0 이 된다. 3 으로 두는 것은 `JdbcTemplate` 의 update 로 한다.

## 검증

```bash
# cwd: 저장소 root
(cd backend && ./gradlew test)
grep -n "SYSTEM" backend/src/main/java/com/bifos/assistant/chat/domain/MessageRole.java
grep -n "result_delivered_at\|auto_turn_count" backend/src/main/resources/db/migration/V37__delegation_wake.sql
```

`grep` 둘은 각각 한 줄 이상 나와야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V37__delegation_wake.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/MessageRole.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/ChatMessage.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/Conversation.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ConversationRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/AgentExecution.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/infra/AgentExecutionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentDelegationService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/DelegationWakeMigrationTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/mcp/application/McpToolServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpAgentToolsTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` | 수정 |
