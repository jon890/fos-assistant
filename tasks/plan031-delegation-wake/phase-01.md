# Phase 01. 전한 결과와 자동 turn 횟수를 저장하고 SYSTEM 메시지를 만든다

**Execution profile**: standard

## 목표

부모 대화를 깨우는 데 필요한 저장 칸과 도메인 값을 만든다.
`agent_status` 와 `agent_stop` 이 끝난 결과를 돌려주면 그 결과를 전한 것으로 적는다.

**범위 외**: 깨우는 판정과 자동 turn 실행(phase 02), 대화 단위 SSE 와 화면(phase 03).

## 컨텍스트

맡긴 자식 실행이 끝나면 Control Plane 이 부모 대화의 다음 turn 을 열어 결과를 전한다.
이 phase 는 그 판정이 읽을 상태만 만든다.

- 위임 실행은 `agent_execution` 줄이고 `delegation_key` 가 채워져 있다. 답은 `output_text` 에 있다.
- `chat_message.role` 은 `VARCHAR(20)` 이고 값은 `MessageRole` enum(`USER`, `ASSISTANT`)이다. 스키마를 바꾸지 않고 enum 값만 더한다.
- 마이그레이션은 `backend/src/main/resources/db/migration/` 에 있고 마지막 번호는 `V36__accountbook_connection.sql` 이다. 백엔드는 `ddl-auto: validate` 로 돈다. 엔티티와 칸 이름, 타입이 정확히 맞아야 한다.

**근거 문서**: `docs/data-schema.md` 의 `conversation`, `chat_message`, `agent_execution` 절,
`docs/flow.md` 의 「다른 에이전트에게 맡길 때」 와 「위임 결과가 도착했을 때」 절,
`docs/adr/ADR-040-위임-결과는-control-plane-이-부모-대화의-다음-turn-을-열어-전한다.md`

## 의도 메모

- 전했는지를 별도 표로 두지 않고 `agent_execution.result_delivered_at` 에 둔다. 한 실행의 결과는 한 번만 전하고, 조회가 실행 줄 하나로 끝난다.
- `CANCELLED` 를 돌려줄 때도 `result_delivered_at` 을 적는다. 깨우지 않는 상태지만 적어 두면 판정이 단순하다.
- `RUNNING` 을 돌려줄 때는 적지 않는다.

## 작업 항목

### 1. `backend/src/main/resources/db/migration/V37__delegation_wake.sql` — 칸 둘

- `conversation.auto_turn_count INT NOT NULL DEFAULT 0`
- `agent_execution.result_delivered_at DATETIME(6) NULL`
- 깨울 대상을 찾는 조회에 쓸 색인: `agent_execution(conversation_id, result_delivered_at, status)`. 기존 색인 이름 규칙을 앞 마이그레이션에서 확인해 따른다.

### 2. `backend/src/main/java/com/bifos/assistant/chat/domain/MessageRole.java`, `ChatMessage.java` — SYSTEM

- `MessageRole` 에 `SYSTEM` 을 더한다.
- `ChatMessage.fromSystem(Long conversationId, String content)` 를 더한다. `senderUserId`, `executionId`, `replacesMessageId` 는 null 이다.
- `ChatMessage` 를 읽는 쪽에서 role 이 둘뿐이라고 가정하는 분기를 찾아(`grep -rn "MessageRole\." backend/src/main`) `SYSTEM` 에서 깨지지 않게 한다. 다시 생성(`ChatService.regenerate`)은 마지막 `USER` 를 찾으므로 그대로 둔다.
- `MessageView` 등 응답 DTO 가 role 을 문자열로 내보내면 `SYSTEM` 도 그대로 나간다. 웹 타입은 phase 03 이 맞춘다.

### 3. `Conversation` 엔티티 — `autoTurnCount`

- 칸 `auto_turn_count` 에 대응하는 `int autoTurnCount` 와 `incrementAutoTurns()`, `resetAutoTurns()`, `autoTurnCount()` 를 더한다.
- `ChatService.saveQuestion` 의 `TurnIntent.Fresh` 분기(사용자 질문을 저장하는 자리)에서 같은 트랜잭션으로 `resetAutoTurns()` 를 부르고 저장한다.

### 4. `AgentExecution` 엔티티와 `McpToolService` — `result_delivered_at`

- `AgentExecution` 에 `Instant resultDeliveredAt` 와 `markResultDelivered(Instant at)`(이미 있으면 바꾸지 않음), `resultDeliveredAt()` 을 더한다.
- `McpToolService.agentStatus` 와 `agentStop` 이 `SUCCEEDED`, `FAILED`, `CANCELLED` 를 돌려주면 그 실행에 `markResultDelivered` 를 적고 저장한다. 저장은 `AgentExecutionRepository` 로 하고, 조회 권한 판정(`delegations.status`, `delegations.stop`)을 지난 뒤에만 한다.

### 5. `McpToolService` 도구 설명 — 기다리지 않게 쓴다

`agent_delegate` 와 `agent_status` 의 `description` 을 바꾼다. 글은 그대로 옮긴다.

- `agent_delegate`: 「다른 에이전트에게 일을 맡기고 실행 번호를 바로 돌려받는다. 끝날 때까지 기다리지 않는다. agent_code 에는 agent_list 로 받은 code 를, task 에는 그 에이전트에게 줄 지시를 넣는다. 맡긴 뒤 남은 일을 계속하고, 할 일이 끝나면 맡긴 일을 알리고 답을 마친다. 맡긴 일이 끝나면 그 결과가 이 대화의 다음 차례에 자동으로 전달된다.」
- `agent_status`: 「다른 에이전트에게 맡긴 실행의 지금 상태와 결과를 읽는다. execution_id 에는 agent_delegate 로 받은 번호를 넣는다. 결과는 끝나면 자동으로 전달되므로 기다리려고 반복해서 부르지 않는다. 사용자가 진행 상황을 물을 때 한 번 부른다.」

### 6. 테스트

- `backend/src/test/java/com/bifos/assistant/mcp/` 의 기존 `agent_status` 테스트 옆에 더한다(`grep -rln "agentStatus" backend/src/test` 로 찾는다).
  - 끝난 위임 실행을 `agent_status` 로 읽으면 `result_delivered_at` 이 채워진다
  - `RUNNING` 을 읽으면 비어 있다
  - 물을 수 없는 실행(`NOT_FOUND`)은 적지 않는다
- `ChatService` 로 사용자 질문을 보내면 `auto_turn_count` 가 0 이 된다. 미리 3 으로 저장해 두고 확인한다.
- 마이그레이션은 기존 스키마 검증 테스트(`grep -rln "Migration" backend/src/test`)가 기동으로 확인한다.

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
grep -n "SYSTEM" backend/src/main/java/com/bifos/assistant/chat/domain/MessageRole.java
grep -n "result_delivered_at\|auto_turn_count" backend/src/main/resources/db/migration/V37__delegation_wake.sql
```

`grep` 둘은 각각 한 줄 이상 나와야 한다.
머지 전에는 저장소 root 에서 `scripts/check-local.sh` 가 모두 통과해야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V37__delegation_wake.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/MessageRole.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/ChatMessage.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/Conversation.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/AgentExecution.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/` 아래 기존 테스트 | 수정 |
