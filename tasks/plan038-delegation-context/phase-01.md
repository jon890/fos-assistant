# Phase 01. 실행 줄에 Hermes 뿌리 session 을 적고 새 대화의 session 을 Control Plane 이 정한다

**Execution profile**: deep

## 목표

MCP `agent_*` 호출이 들고 오는 뿌리 session 으로 도는 실행을 찾을 수 있게, 실행 줄마다 그 실행이 속한 대화의 **뿌리 session** 을 **제출하기 전에** 적는다.
새 대화는 첫 turn 을 보내기 전에 Control Plane 이 session id 를 `fos-<uuid>` 로 정하고, 같은 값을 대화의 뿌리 session 으로도 적는다. 압축 교체로 Hermes 가 다른 session 을 돌려줘도 뿌리 session 은 바뀌지 않는다.
위임에 쓸 칸 둘(`delegation_key`, `output_text`)도 이 migration 에 함께 더한다.

**범위 외**: `_fos_ctx` 검증과 부모 찾기(phase 02), `agent_*` 도구(plan039), 운영 배포와 실제 왕복 확인(배포하는 쪽이 한다).

## 컨텍스트

- 지금 대화의 session 은 첫 실행이 끝난 뒤 Hermes 가 돌려준 값을 `Conversation.rememberSession` 과 `ConversationRepository.touchSession(id, sessionId, now)` 로 적는다. 둘 다 `updated_at` 을 바꾼다. 새 대화의 첫 turn 은 `session_id` 없이 보내져 Hermes 가 run id 를 session 으로 쓴다
- Hermes v0.21.5 는 본문에 모르는 `session_id` 를 주면 그 id 로 session 을 만들고, 같은 id 로 보낸 다음 run 이 이어진다(실측, `docs/hermes/delegation.md` 「부모 실행을 잇는 방법」). `compression.in_place: false` 이면 run 도중 새 session 으로 바뀌고, 그 새 session 은 `parent_session_id` 로 처음 session 을 가리킨다. hook 은 사슬의 처음 session 으로 서명한다
- turn 을 보내는 자리는 둘이다. 확인한 사실이다.
  - `ChatService.begin`: 보내기, 사진 첨부 turn, 다시 생성이 모두 `runTurn` 을 거쳐 여기로 온다. `HermesRunCommand` 에 `conversation.hermesSessionId()` 를 넣는다
  - `ResearchAndBuildFlow.run` 의 Chief 실행: 흐름이 붙은 에이전트는 세 경우 모두 `runFlow` 를 거쳐 여기로 온다. `runner.run(..., conversation.hermesSessionId(), ...)`
  - 그 밖의 제출은 `MemoryProposer` 와 흐름의 하위 실행(`ChildExecutionRunner` → `AgentRunner.run`)뿐이다
- 실행 줄은 `ExecutionRecorder.start(user, conversation, agent, parentExecutionId, rootExecutionId, context, requested, retryOfExecutionId)` 가 만든다. 이 8인자 메서드를 부르는 곳은 `ChatService.begin` 과 `AgentRunner.run` 둘뿐이다. `AgentRunner.run` 은 받은 `sessionId` 를 `HermesRunCommand` 에만 쓰고 실행 줄에 적지 않는다
- 테스트 DB 는 `application-test.yml` 의 `ddl-auto: create-drop` 으로 엔티티에서 만들어지고 Flyway 는 꺼져 있다. 유일 제약은 엔티티에도 적어야 테스트 DB 에 생긴다(선례 `Memory.java` 의 `@Column(name = "proposal_dedup_key", unique = true, length = 64)`)
- `ChatServiceTest` 의 스텁 `StubHermesRunsClient` 는 테스트가 정한 session 을 돌려준다. 지금 `ChatServiceTest.java:191` 은 첫 turn 의 `command.sessionId()` 가 null 이라고 단언하고, `:276` 은 둘째 turn 이 스텁이 돌려준 `sess-1` 을 보낸다고 단언한다
- migration 은 V27 이 마지막이다

**근거 문서**: `docs/data-schema.md` 의 「conversation」 과 「agent_execution」 절, `docs/adr/ADR-031-mcp-호출의-부모-실행은-profile-플러그인이-서명한-뿌리-session-으로-잇는다.md`

## 의도 메모

- session 을 정하는 규칙은 `ConversationSessions` 한 곳에 둔다. 대화 session 이 비어 있으면 `fos-` 뒤에 UUID 를 붙여 만들고, 대화의 `hermes_session_id` 와 `hermes_root_session_id` 에 함께 저장한 뒤 그 값을 보낸다. 이미 있으면 그대로 쓴다
- 이 결정 전의 대화(뿌리 칸이 비어 있는 대화)는 Hermes 가 정한 값을 그대로 쓰고 뿌리 칸을 채우지 않는다. 그 대화의 실행 줄에는 보낸 값을 적는다. ADR-031 「감당할 것」 대로 그 대화는 압축 교체 뒤에 위임할 수 없다
- Memory 제안(`MemoryProposer`)과 흐름의 하위 실행은 session 을 정하지 않는다. 그 실행은 `agent_*` 의 부모가 되지 않는다. 하위 실행 줄의 `hermes_session_id` 는 받은 `sessionId` 그대로(지금은 null)다
- Hermes 가 끝난 뒤 돌려준 session 이 보낸 값과 다르면(압축 교체) 지금처럼 `touchSession` 으로 대화의 `hermes_session_id` 만 새 값으로 바꾼다. `hermes_root_session_id` 는 바꾸지 않는다. 다음 turn 은 새 session 을 보내고, 실행 줄에는 뿌리 값을 적는다
- turn 을 시작할 때의 저장은 `updated_at` 을 바꾸지 않는다. 바꾸면 실패한 turn 도 대화를 목록 맨 위로 올린다. 끝날 때의 `touchSession` 은 그대로 둔다

## 작업 항목

### 1. `backend/src/main/resources/db/migration/V28__execution_session_and_delegation.sql`

MySQL 과 H2 의 MySQL 모드에서 모두 도는 문법만 쓴다(칸마다 따로 `ALTER`).

`conversation` 에 칸 하나를 더한다.

- `hermes_root_session_id VARCHAR(128) NULL`

`agent_execution` 에 칸 셋과 색인 둘을 더한다.

- `hermes_session_id VARCHAR(128) NULL`
- `delegation_key VARCHAR(64) NULL` 과 유일 색인 `uk_agent_execution_delegation_key`
- `output_text MEDIUMTEXT NULL`
- 색인 `idx_agent_execution_session_status (hermes_session_id, status)`

### 2. `AgentExecution` 엔티티와 빌더

`backend/src/main/java/com/bifos/assistant/usage/domain/AgentExecution.java` 에 세 칸과 접근자를 더한다.

- `@Column(name = "hermes_session_id", length = 128)`
- `@Column(name = "delegation_key", unique = true, length = 64)`. 테스트 DB 에도 유일 제약이 생기게 엔티티에 적는다
- `@Column(name = "output_text", columnDefinition = "MEDIUMTEXT")`(backend/AGENTS.md 「엔티티와 마이그레이션은 따로 논다」)

빌더에 `hermesSessionId(String)`, `delegationKey(String)` 을 더한다. 끝난 답을 적는 `recordOutput(String)` 을 더한다.

### 3. 대화의 뿌리 session 칸

`backend/src/main/java/com/bifos/assistant/chat/domain/Conversation.java` 에 `hermes_root_session_id` 칸과 접근자 `hermesRootSessionId()` 를 더한다.
`updatedAt` 을 바꾸지 않고 두 칸을 함께 적는 도메인 메서드 하나를 더한다(예: `assignNewSession(String)`). 기존 `rememberSession` 은 바꾸지 않는다.
실행 줄에 적을 값을 돌려주는 메서드 `executionSessionId()` 를 더한다. 뿌리 칸이 있으면 뿌리, 없으면 `hermesSessionId()` 다.

`backend/src/main/java/com/bifos/assistant/chat/infra/ConversationRepository.java` 에 비어 있을 때만 채우는 조건부 갱신을 더한다. `updatedAt` 은 건드리지 않는다(`chooseModelIfActive` 처럼).

```
update Conversation c set c.hermesSessionId = :sessionId, c.hermesRootSessionId = :sessionId
 where c.id = :id and c.hermesSessionId is null
```

### 4. session 을 정하는 자리

`backend/src/main/java/com/bifos/assistant/chat/application/ConversationSessions.java` 에 `ensure(Conversation)` 을 둔다.

- 대화의 `hermesSessionId()` 가 있으면 그대로 돌려준다
- 비어 있으면 `"fos-" + UUID.randomUUID()` 를 만들어 3의 조건부 갱신을 부른다
  - 1건이면 만든 값을 대화 객체에 적고 돌려준다
  - 0건이면 다른 turn 이 먼저 정한 것이다. 저장된 대화를 다시 읽어 두 칸을 대화 객체에 적고 저장된 값을 돌려준다

`touchSession` 과 `rememberSession` 을 이 저장에 쓰지 않는다.

`ChatService.begin` 은 명령을 만들기 전에 `ensure` 를 부르고 그 값을 `HermesRunCommand` 에 넣는다.
`ResearchAndBuildFlow.run` 은 Chief 실행 전에 `ensure` 를 부르고 그 값을 `runner.run` 에 넘긴다.

### 5. 실행 줄에 session 적기

`ExecutionRecorder.start` 의 8인자 메서드에 `hermesSessionId` 인자를 더한다(오버로드를 만들지 않는다). 부르는 곳 둘을 고친다.

- `ChatService.begin` 은 `conversation.executionSessionId()` 를 넘긴다(4의 `ensure` 뒤)
- `AgentRunner.run` 은 실행 줄에 적을 값을 따로 받는다. Chief 는 `conversation.executionSessionId()`, 하위 실행은 받은 `sessionId` 그대로(비면 비운다)다. 인자를 하나 더하든 부르는 쪽이 정하게 하든, Hermes 에 보내는 값과 실행 줄에 적는 값이 다를 수 있다는 것이 코드에서 드러나게 한다

실행 줄은 제출 전에 만들어지므로 값은 제출하기 전에 적힌다.

### 6. 테스트

`ChatServiceTest` 는 스텁이 받은 `session_id` 를 되돌리도록 설정하는 경우(`willAnswer(command -> ... command.sessionId() ...)`)와 다른 값을 돌려주는 경우를 구분해 쓴다.

- 새 대화의 첫 turn 이 `fos-` 로 시작하는 `session_id` 로 Hermes 에 가고, 같은 값이 대화의 두 칸과 그 실행 줄에 적히는지. `ChatServiceTest.java:191` 의 null 단언을 `startsWith("fos-")` 로 바꾼다
- 실행 줄의 `hermes_session_id` 가 **제출하는 순간에** 이미 적혀 있는지. 스텁의 `submit` 안(`willAnswer`)에서 저장소로 실행 줄을 읽어 단언한다
- 둘째 turn 이 같은 session 을 보내고 새 id 를 만들지 않는지(스텁이 받은 값을 되돌리는 경우)
- 압축 교체: 스텁이 첫 turn 에 다른 값을 돌려주면 둘째 turn 은 그 값을 보내고, 대화의 뿌리 칸과 둘째 실행 줄의 `hermes_session_id` 는 첫 `fos-` 값 그대로인지(`:276` 의 기존 단언을 살린다)
- 이미 Hermes 가 정한 session 이 있고 뿌리 칸이 빈 대화는 그 값을 그대로 보내고, 실행 줄에도 그 값을 적고, 뿌리 칸을 채우지 않는지
- `ConversationSessionTest`: 두 turn 이 동시에 같은 새 대화의 session 을 정하려 하면 하나만 저장되고, 진 쪽은 저장된 값을 다시 읽어 같은 값을 돌려주는지. 진 쪽은 먼저 저장된 값이 있는 상태에서 `hermesSessionId` 가 빈 옛 대화 객체로 `ensure` 를 불러 재현한다
- `ConversationSessionTest`: session 을 정해도 대화의 `updated_at` 이 바뀌지 않는지
- 흐름의 Chief 실행 줄에 대화의 뿌리 session 이 적히고, 하위 실행 줄은 비어 있는지(`ResearchAndBuildFlowTest`)
- V28 을 H2 에서 끝까지 올려 칸과 유일 제약이 생기는지(`*MigrationTest` 본보기를 따른다)

### 7. docs

- `docs/data-schema.md` 「conversation」 표에 `hermes_root_session_id` 줄을 더한다. 새 대화는 첫 turn 전에 `hermes_session_id` 와 같은 `fos-<uuid>` 를 적고 압축 교체에도 바뀌지 않는다, 이 칸이 생기기 전의 대화는 비어 있다는 것을 적는다
- 같은 문서 「agent_execution」 의 `hermes_session_id` 설명을 고친다. 대화 turn 은 그 대화의 뿌리 session(없으면 보낸 session)이고, 흐름의 하위 실행도 비어 있다는 것을 더한다

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
운영에서 새 대화의 두 turn 이 같은 `fos-` session 으로 이어지는지는 배포한 뒤 실제 왕복으로 확인한다. 방법은 `fos-home-infra` 가 갖는다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V28__execution_session_and_delegation.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/AgentExecution.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionRecorder.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/Conversation.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ConversationRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ConversationSessions.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentRunner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/ResearchAndBuildFlow.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ConversationSessionTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/orchestration/ResearchAndBuildFlowTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/ExecutionDelegationColumnsMigrationTest.java` | 신규 |
| `docs/data-schema.md` | 수정 |
