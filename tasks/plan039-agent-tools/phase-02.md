# Phase 02. `agent_delegate` 로 다른 에이전트의 실행을 시작하고 기다리지 않는다

**Execution profile**: deep

## 목표

Chief 가 `agent_delegate(agent_code, task)` 를 부르면 경계를 검사하고, 원래 사용자의 권한으로 다른 에이전트의 실행을 시작해 **제출까지만 기다린 뒤** 실행 번호를 돌려준다.
실행은 Control Plane 이 따로 끝까지 지켜보고 답을 그 실행 줄에 적는다. Chief 는 `agent_status` 로 받는다.

**범위 외**: `agent_stop` 과 turn 중지 연결(phase 03). 화면 변경.

## 컨텍스트

- 실행을 끝까지 돌리는 코드는 이미 있다. `AgentRunner.run(user, conversation, agent, task, parentExecutionId, rootExecutionId, RunSession session, onStarted, onSubmitted, cancelled, instructionAddition)` 이 Memory 를 원래 사용자로 다시 조립하고(`ContextAssembler.assemble(user)`), 대화의 모델 선택을 쓰고, 실행 줄을 만들고, 제출하고, 끝날 때까지 기다리고, 성공, 실패, 중지를 기록한다. `onStarted` 는 실행 줄이 생긴 직후, `onSubmitted` 는 run 번호가 붙은 직후 불린다. `RunSession` 은 Hermes 에 보낼 session 과 실행 줄에 적을 session 을 하나로 묶은 값이고, 흐름의 하위 실행은 `RunSession.fresh()` 를 넘긴다
- 자식을 여는 자리는 `ChildExecutionRunner` 다. 지금은 `requireNotAChild` 로 깊이 1 을 막고 `agents.requireReadable` 로 에이전트를 확인한다. 이 규칙은 `ResearchAndBuildFlow` 가 쓴다. 흐름은 넓히지 않는다
- `AgentService.requireStartable(user, code)` 는 켜지지 않은 에이전트를 `AGENT_DISABLED` 로, 없거나 읽을 수 없는 것을 `AGENT_NOT_FOUND` 로 거절한다
- 흐름은 이미 `Executors.newVirtualThreadPerTaskExecutor()` 로 자식을 나란히 돌린다(`ResearchAndBuildFlow`)
- 기동 정리 `OrphanedExecutionSweeper` 가 서버가 다시 뜰 때 남은 `RUNNING` 을 `FAILED`(`ORPHANED`) 로 바꾼다
- 설정 클래스 본보기: `memory.application.MemoryProposalProperties`(`@ConfigurationProperties(prefix = "assistant.memory.propose")`)

**근거 문서**: `docs/adr/ADR-017-무엇을-할지는-hermes-가-정하고-control-plane-은-경계만-갖는다.md` 의 「`agent_delegate` 는 기다리지 않는다」 와 「도구 넷과 한도」 절, `docs/adr/ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md` 의 「`delegation_key`」, `docs/flow.md` 의 「다른 에이전트에게 맡길 때」 절, `docs/data-schema.md` 의 「agent_execution」 절

## 의도 메모

- 인자는 `agent_code`, `task` 둘뿐이다. `_fos_ctx` 외의 다른 키(profile, user, parent, root, api 주소, key)가 오면 인자 오류다. 모델이 준 값으로 profile 이나 사용자를 정하지 않는다
- 부모는 `McpCallerResolver` 가 찾은 `McpCaller.parent()` 다(토큰의 profile, 서명한 뿌리 session, `RUNNING` 이 맞는 도는 실행). `DelegationParentResolver` 를 다시 부르지 않는다. 자식의 뿌리는 부모의 뿌리(없으면 부모 번호)다. 사용자는 `McpCaller.user()` 다
- 깊이: 부모에서 `parent_execution_id` 를 따라 올라가 센다(사용자가 부른 실행 0). 새 자식의 깊이가 `assistant.delegation.max-depth`(기본 2)를 넘으면 거절. 따라가는 횟수를 한도로 묶어 순환 데이터에서 멈춘다
- 같은 호출: `delegation_key` 는 `DelegationKey.of(부모 실행의 profileName, rootSessionId, sessionId, toolCallId)` 다(ADR-032 「`delegation_key`」). `rootSessionId`, `sessionId`, `toolCallId` 는 `McpCaller.context()` 에서 읽는다. 이미 있으면 새로 만들지 않고 그 실행을 돌려준다. 동시에 두 요청이 와서 유일 제약에 걸리면 먼저 저장된 줄을 다시 읽어 돌려준다
- 동시 한도: 같은 뿌리 아래 `delegation_key` 가 있는 `RUNNING` 줄 수가 `assistant.delegation.max-concurrent-children`(기본 4) 이상이면 거절. 세기와 시작 사이 경합은 서비스 안에서 뿌리별로 잠가 막는다(서버 하나 전제, 여러 대가 되면 DB 잠금으로 옮긴다고 주석에 적는다). 서버 전체 한도 `assistant.delegation.max-active`(기본 16)는 세마포어로 둔다
- 자식 session 은 `RunSession.fresh()` 로 새로 정해 `AgentRunner.run` 에 넘긴다. 부모의 session 을 쓰지 않는다
- 시작: 가상 스레드 하나에서 `AgentRunner.run` 을 부른다. 요청 스레드는 `onSubmitted`(성공) 또는 실행 실패까지를 `assistant.delegation.submit-timeout`(기본 30초) 동안만 기다린다. 제한 시간이 지나도 실행 줄이 생겼으면 번호와 `RUNNING` 을 돌려주고, 줄도 생기지 않았으면 실패를 돌려준다. 트랜잭션 안에서 Hermes 를 부르지 않는다
- 실행 줄은 `delegation_key` 와 `hermes_session_id` 를 **처음 만들 때** 함께 적는다(`ExecutionRecorder.start` 에 받는 자리를 더한다). 뒤에 붙이면 유일 제약이 경합을 막지 못한다
- 끝나면 `ChildResult.output()` 을 `output_text` 에 적는다. 길이 상한(예: 100,000자)을 두고 넘으면 자르고 잘렸다는 한 줄을 붙인다
- 자식의 답은 `chat_message` 에 넣지 않는다
- 외부 응답: 성공은 `{"execution_id": <번호>, "status": "RUNNING"}`. 실패는 정해 둔 코드와 한국어 한 줄만(`AGENT_UNAVAILABLE`: 없거나 쓸 수 없음, `AGENT_DISABLED`, `DEPTH_EXCEEDED`, `TOO_MANY_CHILDREN`, `BUSY`, `SUBMIT_FAILED`). 없는 에이전트와 쓸 수 없는 에이전트는 같은 코드다. 요청자를 정하지 못한 호출은 서비스에 오지 않고 `McpToolService.invalidContext()` 결과 하나로 답한다
- `ChildExecutionRunner` 에 위임용 메서드를 더한다. 흐름용 `run` 과 `requireNotAChild` 는 그대로 둔다. 위임용은 `requireStartable` 로 에이전트를 확인하고 깊이 검사는 부르는 쪽(서비스)에 맡긴다

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/orchestration/application/DelegationProperties.java`

`@ConfigurationProperties(prefix = "assistant.delegation")`. `maxDepth`(2), `maxConcurrentChildren`(4), `maxActive`(16), `submitTimeout`(30s), `outputMaxChars`(100000). `application.yml` 에 기본값을 적는다.

### 2. `ExecutionRecorder.start` 와 `AgentRunner.run`

실행 줄을 만들 때 `hermesSessionId` 와 `delegationKey` 를 함께 적을 수 있게 한다. 단일 `AgentRunner.run` 에 `DelegationKey delegationKey` 인자를 더하고 기존 호출은 null 을 넘긴다. `ExecutionRecorder.start` 는 `usage.domain.DelegationKey` 를 받아 처음 만들 때 적는다. 문자열 session 과 나란히 문자열을 받지 않기 위해서다.

### 3. `ChildExecutionRunner` 에 위임용 메서드

`delegate(CurrentUser user, Conversation conversation, AgentExecution parent, String agentCode, String task, RunSession session, DelegationKey delegationKey, Consumer<AgentExecution> onStarted, BiConsumer<AgentExecution, String> onSubmitted, BooleanSupplier cancelled)` 처럼 둔다. 에이전트는 `requireStartable` 로 확인한다.

### 4. `AgentDelegationService.delegate`

위 의도 메모의 순서로: 부모 확인(`McpCaller.parent()`), 깊이, 에이전트 확인, 같은 호출 확인, 동시 한도, 실행 시작, 제출 대기, 결과 돌려주기. 끝난 뒤 `output_text` 기록. 부모의 대화는 `ConversationRepository` 로 읽는다(부모의 `conversationId`).

### 5. MCP 규격과 경로

`McpToolService.tools()` 에 `agent_delegate`(`agent_code`, `task` 문자열, 둘 다 필수) 규격과 설명을 더한다. 설명에는 번호를 돌려받고 `agent_status` 로 결과를 묻는다는 것, `agent_list` 로 쓸 수 있는 `agent_code` 를 안다는 것을 적는다. `McpController.call` 에 경로를 더한다.

### 6. 테스트

`McpAgentToolsTest` 에 더하거나 `AgentDelegationServiceTest` 를 새로 둔다. `StubHermesRunsClient` 로 Hermes 를 대신한다.

- 정상: 번호와 `RUNNING` 이 바로 오고, 스텁이 끝내면 `agent_status` 가 `SUCCEEDED` 와 답을 준다. 자식 줄의 `parent_execution_id`, `root_execution_id`, `user_id`, `hermes_session_id`(`fos-` 로 시작, 부모와 다름), `delegation_key` 가 맞다. `chat_message` 가 늘지 않는다
- Memory: 자식 실행의 `instructions` 가 원래 사용자로 다시 조립한 값이다(부모 문자열을 넘기지 않는다). 스텁의 `received()` 로 본다
- profile: 자식 요청의 profile 과 주소가 에이전트 바인딩의 값이다. 인자에 profile 이나 사용자를 넣으면 인자 오류다
- 없는 에이전트, 남의 비공개 에이전트는 같은 응답, 꺼진 에이전트는 `AGENT_DISABLED`
- 깊이: 깊이 2 인 부모에서 부르면 `DEPTH_EXCEEDED`. 깊이 1 에서는 된다
- 동시: 같은 뿌리에서 넷이 돌면 다섯째가 `TOO_MANY_CHILDREN`. Memory 제안 같은 위임 아닌 자식은 세지 않는다
- 같은 `tool_call_id` 로 두 번 부르면 실행이 하나만 생기고 같은 번호가 온다. 다른 session 의 같은 `tool_call_id` 는 실행 둘이다
- 제출 실패(스텁이 submit 에서 예외)면 실행 줄이 `FAILED` 로 남고 도구는 `SUBMIT_FAILED`
- 서명 없는 호출, 다른 profile 의 토큰으로 서명한 호출은 거절

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

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/orchestration/application/DelegationProperties.java` | 신규 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionRecorder.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentRunner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/ChildExecutionRunner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentDelegationService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/infra/AgentExecutionRepository.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/AgentDelegationServiceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpAgentToolsTest.java` | 수정 |
