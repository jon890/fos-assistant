# Phase 04. `agent_delegate` 로 다른 에이전트의 실행을 시작하고 기다리지 않는다

**Execution profile**: deep

## 목표

Chief 가 `agent_delegate(agent_code, task)` 를 부르면 경계를 검사하고, 원래 사용자의 권한으로 다른 에이전트의 실행을 시작해 **제출까지만 기다린 뒤** 실행 번호를 돌려준다.
실행은 Control Plane 이 따로 끝까지 지켜보고 답을 그 실행 줄에 적는다. Chief 는 `agent_status` 로 받는다.

**범위 외**: `agent_stop` 과 turn 중지 연결(phase 05). 화면 변경.

**앞 phase 의 결과**: phase 02 뒤로 위임 서비스는 `McpCaller` 를 받지 않고 요청자(`CurrentUser`)와 origin 실행(`AgentExecution`)을 따로 받는다. `orchestration` 은 `mcp` 를 import 하지 않는다. phase 03 뒤로 `agent_status` 는 같은 대화의 위임 실행을 답한다.

**Hermes 자체 하위 에이전트와의 차이.** Hermes 의 `delegate_task` 하위 에이전트는 결과가 부모 대화로 돌아오지 않는 것이 운영에서 확인된 기존 동작이다(이번 범위 밖). `agent_delegate` 는 결과를 대화 이력에 넣지 않고 실행 줄의 `output_text` 에 적어 두고, Chief 가 같은 turn 이나 같은 대화의 뒤 turn 에서 `agent_status` 로 가져온다. 그래서 turn 이 끝나도 결과를 잃지 않는다

## 컨텍스트

- **옛 토큰 경로는 지워졌다. 이 선행 조건은 충족됐다.** 운영의 모든 토큰이 profile 에 묶였고, `assistant.mcp.legacy-user-tokens` 설정과 `agent_token.user_id` 칸(V35)과 `McpPrincipal.legacyUserId` 가 지워졌다(ADR-037 「이 결정 뒤에 할 일」). 요청자는 `McpCallerResolver.resolve(principal, 도구 이름, 원래 인자의 _fos_ctx)` 하나로 정하고, 그 결과 `McpCaller` 는 `user`, `originExecution`, `context` 를 늘 갖는다(생성자가 빈 값을 거절한다). 구현 전에 `McpPrincipal` 에 `legacyUserId` 가 남아 있으면 이 전제가 깨진 것이므로 `PHASE_BLOCKED: 옛 토큰 경로가 아직 남아 있다` 로 멈춘다
- 실행을 끝까지 돌리는 코드는 이미 있다. `AgentRunner.run(user, conversation, agent, task, parentExecutionId, rootExecutionId, RunSession session, onStarted, onSubmitted, cancelled, instructionAddition)` 이 Memory 를 원래 사용자로 다시 조립하고(`ContextAssembler.assemble(user)`), 대화의 모델 선택을 쓰고, 실행 줄을 만들고, 제출하고, 끝날 때까지 기다리고, 성공, 실패, 중지를 기록한다. `onStarted` 는 실행 줄이 생긴 직후, `onSubmitted` 는 run 번호가 붙은 직후 불린다. `RunSession` 은 Hermes 에 보낼 session 과 실행 줄에 적을 session 을 하나로 묶은 값이고, 흐름의 하위 실행은 `RunSession.fresh()` 를 넘긴다
- 자식을 여는 자리는 `ChildExecutionRunner` 다. 지금은 `requireNotAChild` 로 깊이 1 을 막고 `agents.requireReadable` 로 에이전트를 확인한다. 이 규칙은 `ResearchAndBuildFlow` 가 쓴다. 흐름은 넓히지 않는다
- `AgentService.requireStartable(user, code)` 는 켜지지 않은 에이전트를 `AGENT_DISABLED` 로, 없거나 읽을 수 없는 것을 `AGENT_NOT_FOUND` 로 거절한다
- 흐름은 이미 `Executors.newVirtualThreadPerTaskExecutor()` 로 자식을 나란히 돌린다(`ResearchAndBuildFlow`)
- 기동 정리 `OrphanedExecutionSweeper` 가 서버가 다시 뜰 때 남은 `RUNNING` 을 `FAILED`(`ORPHANED`) 로 바꾼다
- 설정 클래스 본보기: `memory.application.MemoryProposalProperties`(`@ConfigurationProperties(prefix = "assistant.memory.propose")`)

**근거 문서**: `docs/adr/ADR-017-무엇을-할지는-hermes-가-정하고-control-plane-은-경계만-갖는다.md` 의 「`agent_delegate` 는 기다리지 않는다」 와 「도구 넷과 한도」 절, `docs/adr/ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md` 의 「`delegation_key`」, `docs/flow.md` 의 「다른 에이전트에게 맡길 때」 절, `docs/data-schema.md` 의 「agent_execution」 절

## 의도 메모

- `task` 가 비었거나 공백뿐이거나 8,000자(대화 메시지 상한과 같다)를 넘으면 인자 오류다
- 인자는 `agent_code`, `task` 둘뿐이다. `_fos_ctx` 외의 다른 키(profile, user, parent, root, api 주소, key)가 오면 인자 오류다. 모델이 준 값으로 profile 이나 사용자를 정하지 않는다
- 부모는 `McpCallerResolver` 가 찾은 `McpCaller.originExecution()` 이다(ADR-037). 하위 에이전트가 부르면 그 session 을 등록한 origin 실행이고 끝난 실행일 수 있다. 최상위 session 이면 토큰의 profile, 서명한 뿌리 session, `RUNNING` 이 맞는 도는 실행이다. `SessionOwnerResolver` 나 `DelegationParentResolver` 를 다시 부르지 않는다. 하위 에이전트 몫의 실행 줄을 만들지 않는다. 자식의 뿌리는 부모의 뿌리(없으면 부모 번호)다. 사용자는 `McpCaller.user()` 다. 위임 서비스는 `McpCaller` 를 받지 않고 `McpToolService` 가 풀어 둔 요청자와 origin 실행을 따로 받는다(phase 02)
- 깊이: 부모에서 `parent_execution_id` 를 따라 올라가 센다(사용자가 부른 실행 0). 새 자식의 깊이가 `assistant.delegation.max-depth`(기본 2)를 넘으면 거절. 따라가는 횟수를 한도로 묶어 순환 데이터에서 멈춘다
- 같은 호출: `delegation_key` 는 `DelegationKey.of(부모 실행의 profileName, rootSessionId, sessionId, toolCallId)` 다(ADR-032 「`delegation_key`」). `rootSessionId`, `sessionId`, `toolCallId` 는 `McpCaller.context()` 에서 읽는다. 키는 `McpToolService` 가 만들어 위임 서비스에 `DelegationKey` 로 넘긴다. `DelegationKey` 는 `usage.domain` 에 있어 `orchestration` 이 `mcp` 를 보지 않는다. 이미 있으면 새로 만들지 않고 그 실행을 돌려준다. 동시에 두 요청이 와서 유일 제약에 걸리면 먼저 저장된 줄을 다시 읽어 돌려준다
- 동시 한도: 같은 뿌리 아래 `delegation_key` 가 있는 `RUNNING` 줄 수가 `assistant.delegation.max-concurrent-children`(기본 4) 이상이면 거절. 세기와 시작 사이 경합은 서비스 안에서 뿌리별로 잠가 막는다(서버 하나 전제, 여러 대가 되면 DB 잠금으로 옮긴다고 주석에 적는다). 서버 전체 한도 `assistant.delegation.max-active`(기본 16)는 세마포어로 둔다
- **뿌리별 잠금의 범위**: 같은 호출 확인과 동시 한도 세기부터 실행 줄 저장(`onStarted`) 또는 줄을 만들기 전의 실패까지만 잠근다. 제출 대기는 잠금 밖에서 한다. 잠금이 제출 대기까지 덮으면 같은 뿌리의 위임이 모두 한 줄로 늘어선다
- 잠금은 요청 스레드가 쥐고 요청 스레드가 푼다(`ReentrantLock` 은 잠근 스레드만 풀 수 있다). 「줄이 생겼다」 와 「요청 스레드가 포기했다」 는 `AtomicReference` 같은 하나의 상태에서 compareAndSet 으로 정한다. 따로 두면 요청 스레드가 「줄 없음」 을 본 직후 줄이 생겨 `cancelled` 확인을 지나 제출될 수 있고, 도구는 `SUBMIT_FAILED` 를 줬는데 run 은 끝까지 돈다
- 전체 한도 세마포어는 기다리지 않는 `tryAcquire` 로 잡고 못 잡으면 `BUSY` 다. 가상 스레드의 `finally` 에서 푼다. 실행 줄 저장이 예외를 던진 경로도 포함한다
- 자식 session 은 `ChildExecutionRunner.delegate` 안에서 `RunSession.fresh()` 로 정한다. 인자로 받지 않는다. 부모의 session 을 쓰지 않는다
- 시작: 가상 스레드 하나에서 `AgentRunner.run` 을 부른다. 요청 스레드는 `onSubmitted`(성공) 또는 실행 실패까지를 `assistant.delegation.submit-timeout`(기본 30초) 동안만 기다린다. 제한 시간이 지나도 실행 줄이 생겼으면 번호와 `RUNNING` 을 돌려주고, 줄도 생기지 않았으면 `SUBMIT_FAILED` 를 돌려준다. 줄도 없이 실패를 돌려줄 때는 `cancelled` 를 참으로 둔다. 그러면 뒤늦게 줄이 생겨도 `AgentRunner.run` 이 제출하지 않고 `CANCELLED` 로 끝내, Chief 가 번호를 모르는 실행이 Hermes 에서 돌지 않는다. 트랜잭션 안에서 Hermes 를 부르지 않는다
- 실행 줄은 `delegation_key` 와 `hermes_session_id` 를 **처음 만들 때** 함께 적는다(`ExecutionRecorder.start` 에 받는 자리를 더한다). 뒤에 붙이면 유일 제약이 경합을 막지 못한다
- **답은 `SUCCEEDED` 와 같은 저장에서 `output_text` 에 적는다.** `AgentRunner.run` 이 끝난 뒤 따로 저장하면 `ExecutionRecorder.complete` 가 `SUCCEEDED` 를 먼저 저장하고, 그 사이 `agent_status` 가 `output` 없는 `SUCCEEDED` 를 준다. 그래서 `delegationKey` 가 있는 실행이면 `AgentRunner` 가 잘라 둔 답을 `complete` 에 함께 넘긴다(`ExecutionRecorder.complete` 에 답을 받는 overload 를 더한다). 길이 상한은 `outputMaxChars`(100,000자)이고 넘으면 자르고 잘렸다는 한 줄을 붙인다. `CANCELLED` 에서 멈춘 자리까지의 답을 남기는 것은 phase 05 가 정한다
- 자식의 답은 `chat_message` 에 넣지 않는다
- 외부 응답: 성공은 `{"execution_id": <번호>, "status": "RUNNING"}`. 실패는 정해 둔 코드와 한국어 한 줄만(`AGENT_UNAVAILABLE`: 없거나 쓸 수 없음, `AGENT_DISABLED`, `DEPTH_EXCEEDED`, `TOO_MANY_CHILDREN`, `BUSY`, `SUBMIT_FAILED`). 없는 에이전트와 쓸 수 없는 에이전트는 같은 코드다. 요청자를 정하지 못한 호출은 서비스에 오지 않고 `McpToolService.invalidContext()` 결과 하나로 답한다
- 부모 실행에 대화가 없으면(`conversationId` 가 null) 실행 줄을 만들지 않고 `SUBMIT_FAILED` 로 거절한다. `AgentRunner.run` 이 대화의 모델 선택을 읽기 때문이다. 운영에서는 생기지 않는 방어용이다. 이유는 서버 로그에만 남긴다
- 유일 제약 위반은 가상 스레드 안의 실행 줄 저장에서 난다. 요청 스레드가 그 실패를 받아 `delegation_key` 로 다시 읽는다(`AgentExecutionRepository.findByDelegationKey`). `delegate` 에 `@Transactional` 을 붙이지 않고, 다시 읽기는 트랜잭션 밖에서 한다. MySQL REPEATABLE READ 에서는 같은 트랜잭션 안에서 다시 읽어도 먼저 저장된 줄이 보이지 않는다. `SubagentSessionRegistrar` 가 본보기다
- `ChildExecutionRunner` 에 위임용 메서드 둘을 더한다. 흐름용 `run` 과 `requireNotAChild` 는 그대로 둔다(흐름은 깊이 1 그대로다). 에이전트 확인(`requireStartable`)은 **요청 스레드**에서 하고, 실행 시작만 가상 스레드에서 한다. 그래야 `AGENT_UNAVAILABLE`, `AGENT_DISABLED` 가 한도 검사보다 먼저, 동기로 온다. 깊이 검사는 부르는 쪽(서비스)이 한다

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/orchestration/application/DelegationProperties.java`

`@ConfigurationProperties(prefix = "assistant.delegation")`. `maxDepth`(2), `maxConcurrentChildren`(4), `maxActive`(16), `submitTimeout`(30s), `outputMaxChars`(100000). `application.yml` 에 기본값을 적는다.

### 2. `ExecutionRecorder.start` 와 `AgentRunner.run`

실행 줄을 만들 때 `hermesSessionId` 와 `delegationKey` 를 함께 적을 수 있게 한다. **기존 모양은 남기고 overload 를 더한다.** `ExecutionRecorder.start` 의 9개 인자는 그대로 두고 `usage.domain.DelegationKey` 를 더 받는 10개 인자를 더한다(`ChatService`, `MemoryProposer`, `MemoryProposerTest` 의 mock 을 바꾸지 않기 위해서다). `AgentRunner.run` 의 11개 인자도 그대로 두고 `DelegationKey` 를 더 받는 12개 인자를 더해, 11개 인자는 null 을 넘긴다(`ResearchAndBuildFlow` 를 바꾸지 않는다). `ExecutionRecorder.complete` 에는 위 「답은 `SUCCEEDED` 와 같은 저장에서」 의 답을 받는 overload 를 더한다. 문자열 session 과 나란히 문자열을 받지 않기 위해서다.

### 3. `ChildExecutionRunner` 에 위임용 메서드

- `Agent startableAgent(CurrentUser user, String agentCode)`: `AgentService.requireStartable` 을 부른다. 요청 스레드에서 쓴다
- `ChildResult delegate(CurrentUser user, Conversation conversation, AgentExecution parent, Agent agent, String task, DelegationKey delegationKey, Consumer<AgentExecution> onStarted, BiConsumer<AgentExecution, String> onSubmitted, BooleanSupplier cancelled)`: session 은 안에서 `RunSession.fresh()` 로 정한다. 뿌리는 `parent.treeRootId()`, 부모는 `parent.id()` 다. 가상 스레드에서 쓴다

### 4. `AgentDelegationService.delegate`

`delegate(CurrentUser user, AgentExecution origin, DelegationKey delegationKey, String agentCode, String task)` 처럼 두고 결과 타입은 `orchestration.application` 에 따로 둔다(번호와 상태, 또는 실패 코드). 위 의도 메모의 순서로: 부모 확인(넘겨받은 origin 실행), 깊이, 에이전트 확인, 같은 호출 확인, 동시 한도, 실행 시작, 제출 대기, 결과 돌려주기. 답은 `AgentRunner` 가 `complete` overload 로 `SUCCEEDED` 와 같은 저장에서 적는다(따로 저장하지 않는다). 부모의 대화는 `ConversationRepository` 로 읽는다(부모의 `conversationId`).

### 5. 문서

위임 시작이 생겼으므로 「아직 없다」 는 문장을 고친다.

- `docs/flow.md` 「다른 에이전트에게 맡길 때」 첫 단락의 「`agent_delegate` 와 `agent_stop` 은 아직 없어 `-32601`」 을 `agent_stop` 만 남게 고친다. 「갈리는 지점」 에 「부모 실행에 대화가 없다 → `SUBMIT_FAILED`」 와 「`task` 가 비었거나 너무 길다 → 인자 오류」, 「전체 한도 → `BUSY`」 행을 더한다
- `docs/code-architecture.md` 「다른 에이전트에게 맡기기」 의 「읽기 도구만 열었다」, `AgentDelegationService` 줄의 「위임 시작은 아직 없다」 를 고치고, `DelegationProperties` 줄에 `outputMaxChars` 를 더한다. 「깊이와 동시 한도」 의 「`ChildExecutionRunner` 가 깊이 1 로 막던 규칙은 이 설정값으로 바뀐다」 를 「흐름은 깊이 1 그대로이고 위임만 이 설정값을 쓴다」 로 고친다
- `docs/hermes/tools-and-skills.md` 「Control Plane MCP」 표의 도구 행에 `agent_delegate` 를 더하고 `agent_stop` 만 아직 없다고 적는다

### 6. MCP 규격과 경로

`McpToolService.tools()` 에 `agent_delegate`(`agent_code`, `task` 문자열, 둘 다 필수) 규격과 설명을 더한다. 설명에는 번호를 돌려받고 `agent_status` 로 결과를 묻는다는 것, `agent_list` 로 쓸 수 있는 `agent_code` 를 안다는 것을 적는다. `McpController.call` 에 경로를 더한다.

### 7. 테스트

`McpAgentToolsTest` 에 더하거나 `AgentDelegationServiceTest` 를 새로 둔다. `StubHermesRunsClient` 로 Hermes 를 대신한다.

- 정상: 번호와 `RUNNING` 이 바로 오고, 스텁이 끝내면 `agent_status` 가 `SUCCEEDED` 와 답을 준다. 자식 줄의 `parent_execution_id`, `root_execution_id`, `user_id`, `hermes_session_id`(`fos-` 로 시작, 부모와 다름), `delegation_key` 가 맞다. `chat_message` 가 늘지 않는다
- 하위 에이전트: 부모 실행 아래 하위 에이전트 session 을 `SubagentSessionRegistrar` 로 등록하고 부모를 `SUCCEEDED` 로 바꾼 뒤, 그 session 으로 서명한 `agent_delegate` 가 만든 자식의 `parent_execution_id` 가 그 부모 실행이다. 하위 에이전트 몫의 실행 줄은 생기지 않는다
- Memory: 자식 실행의 `instructions` 가 원래 사용자로 다시 조립한 값이다(부모 문자열을 넘기지 않는다). 스텁의 `received()` 로 본다
- profile: 자식 요청의 profile 과 주소가 에이전트 바인딩의 값이다. 인자에 profile 이나 사용자를 넣으면 인자 오류다
- 없는 에이전트, 남의 비공개 에이전트는 같은 응답, 꺼진 에이전트는 `AGENT_DISABLED`
- 깊이: 깊이 2 인 부모에서 부르면 `DEPTH_EXCEEDED`. 깊이 1 에서는 된다
- 동시: 같은 뿌리에서 넷이 돌면 다섯째가 `TOO_MANY_CHILDREN`. Memory 제안 같은 위임 아닌 자식은 세지 않는다
- 같은 `tool_call_id` 로 두 번 부르면 실행이 하나만 생기고 같은 번호가 온다. 다른 session 의 같은 `tool_call_id` 는 실행 둘이다
- 제출 실패(스텁이 submit 에서 예외)면 실행 줄이 `FAILED` 로 남고 도구는 `SUBMIT_FAILED`
- 제출 대기: 스텁이 submit 을 붙잡으면(스텁에 붙잡는 방법을 더한다) 제한 시간(테스트에서 짧게 준다) 뒤 번호와 `RUNNING` 이 온다. 붙잡은 것을 풀면 결과가 그 줄에 적힌다
- 답 자르기: `outputMaxChars` 를 넘는 답은 잘리고 잘렸다는 한 줄이 붙는다. `agent_status` 가 `SUCCEEDED` 를 줄 때 `output` 이 늘 함께 있다
- 전체 한도: `maxActive` 를 채우면 `BUSY`
- 인자: `task` 가 비었거나 8,000자를 넘으면 인자 오류
- **비동기 위임 스레드가 테스트보다 오래 살지 않게 한다.** `StubHermesRunsClient` 를 테스트끼리 함께 쓰고 `@BeforeEach` 가 사용자를 지우므로, 각 테스트는 자기가 띄운 실행이 끝날 때까지 기다린 뒤 끝난다. Awaitility 가 의존성에 없어 직접 polling 한다. 동시 한도의 「넷이 돈다」 는 `RUNNING` 줄을 직접 넣어 만든다

- 서명 없는 호출, 다른 profile 의 토큰으로 서명한 호출은 거절
- `agent_delegate` 가 만든 자식 줄의 `conversation_id` 가 부모의 대화 번호다. 같은 대화의 다음 turn 에서 `agent_status` 로 그 실행을 물을 수 있다

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
| `backend/src/main/java/com/bifos/assistant/orchestration/application/DelegationResult.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/hermes/StubHermesRunsClient.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/AgentDelegationServiceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpAgentToolsTest.java` | 수정 |
| `docs/code-architecture.md` | 수정 |
| `docs/flow.md` | 수정 |
| `docs/hermes/tools-and-skills.md` | 수정 |
