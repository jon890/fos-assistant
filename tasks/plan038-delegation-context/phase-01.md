# Phase 01. 실행 줄에 Hermes session 을 적고 새 대화의 session 을 Control Plane 이 정한다

**Execution profile**: standard

## 목표

MCP `agent_*` 호출이 들고 오는 뿌리 session 으로 도는 실행을 찾을 수 있게, 실행 줄마다 그 실행을 보낸 Hermes session 을 **제출하기 전에** 적는다.
새 대화는 첫 turn 을 보내기 전에 Control Plane 이 session id 를 `fos-<uuid>` 로 정한다. 위임에 쓸 칸 둘(`delegation_key`, `output_text`)도 이 migration 에 함께 더한다.

**범위 외**: `_fos_ctx` 검증과 부모 찾기(phase 02), `agent_*` 도구(plan039).

## 컨텍스트

- 지금 대화의 session 은 첫 실행이 끝난 뒤 Hermes 가 돌려준 값을 `Conversation.rememberSession` 과 `ConversationRepository.touchSession(id, sessionId, now)` 로 적는다. 새 대화의 첫 turn 은 `session_id` 없이 보내져 Hermes 가 run id 를 session 으로 쓴다
- Hermes v0.21.5 는 본문에 모르는 `session_id` 를 주면 그 id 로 session 을 만들고, 같은 id 로 보낸 다음 run 이 이어진다(실측, `docs/hermes/delegation.md` 「부모 실행을 잇는 방법」)
- turn 을 보내는 자리는 둘이다. `ChatService.begin`(일반 turn, `HermesRunCommand` 에 `conversation.hermesSessionId()` 를 넣는다)과 `ResearchAndBuildFlow.run` 의 Chief 실행(`runner.run(..., conversation.hermesSessionId(), ...)`)
- 실행 줄은 `ExecutionRecorder.start(user, conversation, agent, parentExecutionId, rootExecutionId, context, requested, retryOfExecutionId)` 가 만든다. `AgentRunner.run` 은 받은 `sessionId` 를 `HermesRunCommand` 에만 쓰고 실행 줄에 적지 않는다
- migration 은 V27 이 마지막이다

**근거 문서**: `docs/data-schema.md` 의 「agent_execution」 절, `docs/adr/ADR-031-mcp-호출의-부모-실행은-profile-플러그인이-서명한-뿌리-session-으로-잇는다.md`

## 의도 메모

- session 을 정하는 규칙은 한 곳에 둔다. 대화 session 이 비어 있으면 `fos-` 뒤에 UUID 를 붙여 만들고, 대화에 적고 저장한 뒤 그 값을 보낸다. 이미 있으면 그대로 쓴다
- 이 결정 전의 대화는 Hermes 가 정한 값을 그대로 쓴다. 바꾸지 않는다
- Memory 제안(`MemoryProposer`)과 흐름의 하위 실행은 session 을 정하지 않는다. 그 실행은 `agent_*` 의 부모가 되지 않는다
- Hermes 가 끝난 뒤 돌려준 session 이 보낸 값과 다르면(압축 교체) 지금처럼 대화에 새 값을 적는다. 실행 줄의 `hermes_session_id` 는 보낸 값 그대로 둔다. 서버는 뿌리 session 으로 찾으므로 교체가 일어나도 처음 값이 맞는다

## 작업 항목

### 1. `backend/src/main/resources/db/migration/V28__execution_session_and_delegation.sql`

`agent_execution` 에 칸 셋과 색인 둘을 더한다. MySQL 과 H2 의 MySQL 모드에서 모두 도는 문법만 쓴다(칸마다 따로 `ALTER`).

- `hermes_session_id VARCHAR(128) NULL`
- `delegation_key VARCHAR(64) NULL` 과 유일 색인 `uk_agent_execution_delegation_key`
- `output_text MEDIUMTEXT NULL`
- 색인 `idx_agent_execution_session_status (hermes_session_id, status)`

### 2. `AgentExecution` 엔티티와 빌더

`backend/src/main/java/com/bifos/assistant/usage/domain/AgentExecution.java` 에 세 칸과 접근자를 더한다. `output_text` 는 `columnDefinition = "MEDIUMTEXT"` 로 못 박는다(backend/AGENTS.md 「엔티티와 마이그레이션은 따로 논다」). 빌더에 `hermesSessionId(String)`, `delegationKey(String)` 을 더한다. 끝난 답을 적는 `recordOutput(String)` 을 더한다.

### 3. session 을 정하는 자리

`chat.application` 에 대화 session 을 확보하는 메서드 하나를 둔다(예: `ConversationSessions.ensure(Conversation)`). 비어 있으면 `"fos-" + UUID.randomUUID()` 를 만들어 `conversation.rememberSession` 과 `ConversationRepository.touchSession` 으로 저장하고 그 값을 돌려준다.
`ChatService.begin` 과 `ResearchAndBuildFlow.run` 의 Chief 실행이 이 값을 보낸다. 다시 생성과 첨부 turn 이 같은 경로(`begin`)를 쓰는지 확인하고, 다른 경로가 있으면 같이 맞춘다.

### 4. 실행 줄에 session 적기

`ExecutionRecorder.start` 에 `hermesSessionId` 를 받는 오버로드를 더하거나 기존 8인자 메서드에 인자를 더한다. 기존 호출이 많으면 오버로드로 두고, `ChatService.begin` 과 `AgentRunner.run` 은 보낸 session 을 넘긴다. `AgentRunner.run` 은 받은 `sessionId` 를 그대로 적는다(비면 비운다).

### 5. 테스트

- 새 대화의 첫 turn 이 `fos-` 로 시작하는 `session_id` 로 Hermes 에 가고, 같은 값이 대화와 그 실행 줄에 적히는지(`ChatServiceTest` 또는 새 테스트, `StubHermesRunsClient.received()` 로 본다)
- 둘째 turn 이 같은 session 을 보내고 새 id 를 만들지 않는지
- 이미 Hermes 가 정한 session 이 있는 대화는 그 값을 그대로 보내는지
- 흐름의 Chief 실행 줄에도 session 이 적히는지(`ResearchAndBuildFlowTest`)
- V28 을 H2 에서 끝까지 올려 칸과 유일 제약이 생기는지(`*MigrationTest` 본보기를 따른다)

## 검증

AGENTS.md 「확인」 절을 적힌 순서대로 돌린다.

```bash
cd backend && ./gradlew test
cd web && pnpm typecheck && pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
```

e2e 의 가짜 Hermes 는 본문의 `session_id` 를 그대로 session 으로 쓴다(`test/e2e/fake-hermes.ts` 의 `/v1/runs` 처리, `submitted.session_id ?? ...`). 새 대화의 `fos-` 값이 그대로 이어지는지 기존 e2e 가 통과하는 것으로 본다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V28__execution_session_and_delegation.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/AgentExecution.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionRecorder.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ConversationSessions.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentRunner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/ResearchAndBuildFlow.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ConversationSessionTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/orchestration/ResearchAndBuildFlowTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/ExecutionDelegationColumnsMigrationTest.java` | 신규 |
