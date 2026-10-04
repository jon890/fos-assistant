# Phase 03. 에이전트가 할 일을 제안하는 MCP 도구

**Execution profile**: deep

## 목표

에이전트가 대화 중에 Control Plane MCP 도구 `follow_up_propose` 로 할 일을 `PROPOSED` 로 남기게 한다.
같은 제안이 되풀이되지 않게 네 억제 규칙을 둔다. #158 의 「생각에서 동작으로」 루프의 첫 단계다.
이 phase 로 plan80 이 끝나므로 할 일 문서의 「아직 구현 전」 표시를 지운다.

**범위 외**: 제안을 화면에 보이는 일(plan81). 먼저 알리기 판정(phase 02). 제안을 실행으로 넘기는 일(이 plan 의 범위 밖).

## Blocked 조건

- `web/src/lib/follow-up-api.ts` 가 없다 → `PHASE_BLOCKED: 할 일을 받아들일 지금 화면(plan81 phase 02)이 이 브랜치에 없다` 출력 후 종료. 받아들일 곳이 없는 제안이 대화마다 쌓이기 때문이다
- `backend/src/main/java/com/bifos/assistant/attention/application/FollowUpAttentionSource.java` 가 없다 → `PHASE_BLOCKED: phase 02 가 이 브랜치에 없다` 출력 후 종료. 제안이 지금 화면에 보이려면 판정이 먼저 있어야 한다

## 컨텍스트

- 도구의 입력, 응답 글, 억제 규칙은 `docs/backend/follow-up.md` 의 「제안 도구」 와 「제안 억제」 가 갖는다. 응답 글은 그 표의 글을 그대로 쓴다. 이 phase 가 그 절에 도구 계약 전부를 옮겨 적는다(작업 항목 4). 결과 문장의 「할 일 후보」 를 이 도구로 이어 붙이려는 다른 작업(#165)이 그 문서만 보고 맞출 수 있게 하려는 것이다
- phase 01 이 만든 것 위에 얹는다. `followup.domain.FollowUp`(`proposed(Long userId, Long conversationId, Long executionId, String title, String titleKey, Instant dueAt, boolean waiting, Instant now)`), `followup.infra.FollowUpRepository`, `followup.application.FollowUpService`(`static String titleKey(String)`, 생성자에서 만든 `TransactionTemplate`)
- 저장만 트랜잭션 안에서 하고 유일 제약 충돌은 밖에서 처리하는 본보기: `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorPolicyService.java` 225-245줄. `transactions.execute(status -> repository.saveAndFlush(…))` 를 `try` 로 감싸 `DataIntegrityViolationException` 을 밖에서 잡는다. 메서드에 `@Transactional` 을 붙이지 않는다. 붙이면 예외를 잡아도 트랜잭션이 rollback-only 로 남아 커밋할 때 `UnexpectedRollbackException` 이 난다
- MCP 경계
  - `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpController.java` 의 `handlers`(`Map.of(MEMORY_READ, this::readMemory, …, AGENT_STOP, this::agentStop)`)가 도구 이름과 처리를 묶는다. 도구 이름 상수(`MEMORY_READ` 등)와 처리 메서드(`readMemory`, `agentDelegate`)가 본보기다. `_fos_ctx` 를 뗀 인자의 모양이 틀리면 `invalidParams(id, INVALID_ARGUMENTS)` 로 JSON-RPC `-32602` 를 낸다
  - `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolService.java` 의 `tools()` 가 `List.of(memory_read, artifact_write, agent_list, agent_delegate, agent_status, agent_stop)` 순서로 도구 목록을 낸다. 도구 결과는 private `result(String, boolean)` 로 만든다. 이 클래스는 `@RequiredArgsConstructor` 이고 필드는 `memories`, `artifacts`, `delegations`, `deliveryWriter`, `agents`, `clock` 차례다
  - 요청자는 `McpCallerResolver.resolve(McpPrincipal, String, JsonNode)` 가 정한다. 결과 `McpCaller(CurrentUser user, AgentExecution originExecution, McpCallContext context)` 의 `user()` 가 주인이고 `originExecution().conversationId()` 가 대화다. origin 실행의 에이전트가 커넥터 에이전트면 resolver 가 이미 거절한다(ADR-045)
  - 인자 record 는 `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpDtos.java` 에 둔다(`MemoryReadArguments` 옆)
- 도구 수를 세거나 도구마다 도는 기존 테스트가 넷이다. 모두 이 phase 에서 고친다
  - `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryToolTest.java` 154줄이 `tools/list` 를 `hasSize(6)` 으로 보고, 155-166줄이 0번부터 5번까지 이름을 차례로 본다
  - `backend/src/test/java/com/bifos/assistant/mcp/application/McpToolServiceTest.java` 40-46줄이 `new McpToolService(memories, artifacts, mock(AgentDelegationService.class), mock(ExecutionDeliveryWriter.class), mock(AgentRepository.class), Clock.systemUTC())` 로 인자 여섯을 준다
  - `backend/src/test/java/com/bifos/assistant/mcp/McpCallerInvariantTest.java` 134-145줄의 `toolArguments()` 가 도구마다 인자를 하나씩 둔다. 실패하지는 않지만 새 도구가 빠지면 요청자 불변식이 새 도구에 걸리지 않는다
  - `backend/src/test/java/com/bifos/assistant/mcp/McpAgentToolsTest.java` 603줄 근처의 `@ParameterizedTest` `@DisplayName("연결용 에이전트의 실행에서 온 호출은 도구 여섯 모두 서명이 틀린 호출과 같은 거절이다")` 와 `@ValueSource(strings = {"memory_read", "artifact_write", "agent_list", "agent_status", "agent_delegate", "agent_stop"})`
- 대화 화면의 도구 줄 문구는 `web/src/lib/tool-label.ts` 의 `LABELS` 가 갖는다. 표에 없는 MCP 도구는 「연결된 서비스를 썼어요」 로 보인다. 단위 테스트는 `test/unit/tool-label.test.ts` 다
- Hermes 쪽
  - `hermes/plugins/fos-ctx/__init__.py` 의 `REQUIRED_TOOLS = frozenset({"memory_read", "artifact_write"})` 가 서명하지 못하면 막는 도구다. 여기에 `follow_up_propose` 를 넣는다
  - `hermes/tests/test_fos_ctx.py` 의 `test_memory_and_artifact_tools_block_without_signature` 가 `for tool in ("memory_read", "artifact_write")` 로 그 목록을 하나씩 검사한다
- e2e 대역
  - `test/e2e/fake-hermes.ts` 는 `MEMORY_READ_PROBE` 로 시작하는 입력을 받으면 `readMemoryViaMcp` 로 서명한 `_fos_ctx` 를 붙여 `/mcp` 를 부르고 도구 결과 글을 답으로 돌려준다. MCP 주소와 토큰은 `setMemoryReadMcp(endpoint, token)` 으로 받는다
  - `test/e2e/scenarios/mcp-principal.ts` 가 에이전트 등록, `/admin/agent-tokens` 로 토큰 발급, 정리까지의 본보기다. 그 profile 은 `test/e2e/run.ts` 의 `writeProfileKeys` 목록과 `startFakeHermes` 의 두 표에 들어 있다
- 백엔드 MCP 테스트의 본보기는 `McpMemoryToolTest` 와 서명 도우미 `backend/src/test/java/com/bifos/assistant/mcp/McpCallSigner.java` 다
- 시계를 옮기는 테스트의 본보기는 `backend/src/test/java/com/bifos/assistant/task/TaskServiceTest.java` 의 `@Import(TaskServiceTest.FixedClock.class)` 와 389줄의 `TestClock`(`set(Instant)`) 이다

**근거 문서**: `docs/backend/follow-up.md` 의 「제안 도구」, 「제안 억제」, `docs/adr/ADR-073-할-일은-에이전트가-제안하고-사람이-받아들인-것만-챙긴다.md`, `docs/backend/mcp-caller.md` 의 「Control Plane MCP」, `docs/hermes/fos-ctx.md`

## 의도 메모

- 사용자와 대화를 인자로 받지 않는다. 모델이 바꿀 수 있는 값으로 주인을 정하지 않는다(ADR-032)
- 모양이 틀린 인자(모르는 키, 타입 틀림, `title` 없음)는 다른 도구처럼 JSON-RPC `-32602` 다. 모양은 맞는데 값이 틀린 것(빈 제목, 200자 넘음, 읽지 못하는 날짜)은 도구 결과 `isError: true` 와 한 줄 글이다. 모델이 고쳐 다시 부를 수 있게 하려는 것이다
- **선택 인자의 `null` 은 없는 것으로 본다.** 모델은 쓰지 않는 선택 인자에 `"due_at": null`, `"waiting": null` 을 자주 보낸다. 그것을 `-32602` 로 막으면 같은 호출이 되풀이된다. `title` 의 `null` 은 필수 칸이 빠진 것이라 `-32602` 다
- 대화 하나에 열린 제안 3개, 실행 하나에 2개 규칙은 세고 저장하는 사이에 잠금을 두지 않는다. 같은 대화에서 위임 자식이 나란히 제안하면 상한을 한두 개 넘을 수 있다. 같은 제목의 중복은 `uk_follow_up_open_title` 이 막으므로 받아들인다
- `due_at` 이 날짜만이면 그날 23시 59분(`Asia/Seoul`)으로 읽는다. 「그날까지」 라는 뜻이기 때문이다

## 작업 항목

### 1. `FollowUpService.propose`

`backend/src/main/java/com/bifos/assistant/followup/application/FollowUpService.java` 에 `FollowUpProposalOutcome propose(CurrentUser owner, Long conversationId, Long executionId, String title, Instant dueAt, boolean waiting)` 를 더한다. `@Transactional` 을 붙이지 않는다.
결과는 `backend/src/main/java/com/bifos/assistant/followup/application/model/FollowUpProposalOutcome.java` enum(`CREATED`, `DUPLICATE`, `DECLINED_BEFORE`, `TOO_MANY_PROPOSALS`, `TOO_MANY_IN_RUN`)이다.

`TransactionTemplate` 안에서 아래 순서로 본다.

1. 같은 사용자의 열린 줄(`findByUserIdAndTitleKeyAndOpenMarker(owner.id(), titleKey, 1)`)이 있다 → `DUPLICATE`. 새 줄을 만들지 않는다
2. 같은 대화에서 `closed_at` 이 30일 안인 `REJECTED` 줄에 같은 `titleKey` 가 있다 → `DECLINED_BEFORE`
3. 같은 대화의 `PROPOSED` 가 3개 이상이다 → `TOO_MANY_PROPOSALS`
4. 같은 `proposed_by_execution_id` 의 줄이 2개 이상이다 → `TOO_MANY_IN_RUN`
5. 아니면 `FollowUp.proposed(...)` 를 `saveAndFlush` 하고 `CREATED`

저장이 `uk_follow_up_open_title` 에 걸리면(`DataIntegrityViolationException`) 트랜잭션 밖에서 잡아 `DUPLICATE` 를 돌려준다.
저장소 메서드를 더한다: `existsByConversationIdAndTitleKeyAndStatusAndClosedAtAfter(Long conversationId, String titleKey, FollowUpStatus status, Instant closedAfter)`, `countByConversationIdAndStatus(Long conversationId, FollowUpStatus status)`, `countByProposedByExecutionId(Long executionId)`.
30일은 상수 `REJECTED_COOLDOWN = Duration.ofDays(30)`, 3 과 2 는 `MAX_OPEN_PROPOSALS_PER_CONVERSATION`, `MAX_PROPOSALS_PER_EXECUTION` 상수로 둔다. 설정으로 빼지 않는다.
로그는 `log.info("follow-up proposal userId={} executionId={} outcome={}", ...)` 로 번호와 결과만 남긴다.

### 2. MCP 도구

`McpToolService.tools()` 의 `List.of(...)` 끝(`agent_stop` 뒤)에 도구 하나를 더한다. 7번째(0부터 6번)가 된다.

- `name`: `follow_up_propose`
- `description`: 「사용자가 나중에 해야 하거나 끝나기를 기다리는 일을 할 일로 제안한다. 사용자가 받아들여야 챙긴다. 대화에서 분명히 나온 후속 작업만 제안하고, 같은 대화에서 거절한 것은 다시 제안하지 않는다. due_at 은 2026-10-05 나 2026-10-05T18:00 형식이다.」
- `inputSchema`: `type: object`, `additionalProperties: false`, `properties`: `title`(`type: string`, `minLength` 1, `maxLength` 200), `due_at`(`type: string`), `waiting`(`type: boolean`), `required`: `["title"]`

`McpToolService` 에 필드 `private final FollowUpService followUps;` 를 `agents` 와 `clock` 사이에 더한다. `@RequiredArgsConstructor` 의 인자는 `memories, artifacts, delegations, deliveryWriter, agents, followUps, clock` 차례가 된다.
`McpToolService.proposeFollowUp(McpCaller caller, FollowUpProposeArguments arguments)` 를 더한다.

- `caller.originExecution().conversationId()` 가 비면 `result("대화 밖의 실행에서는 할 일을 제안할 수 없다.", true)`
- 제목을 `strip()` 해 1자부터 200자가 아니면 `result("title 은 1자부터 200자까지다.", true)`
- `due_at` 을 아래 차례로 읽는다. 첫 번째로 읽히는 것을 쓴다
  - `LocalDate.parse`(`2026-10-05`) → 그날 23:59 `Asia/Seoul`
  - `OffsetDateTime.parse`(`2026-10-05T18:00+09:00`, `2026-10-05T09:00:00Z`) → 그 시각
  - `LocalDateTime.parse`(`2026-10-05T18:00`, `2026-10-05T18:00:00`) → `Asia/Seoul` 의 그 시각
  - 셋 다 못 읽으면 `result("due_at 은 2026-10-05 나 2026-10-05T18:00 형식이다.", true)`
- `FollowUpService.propose(caller.user(), conversationId, caller.originExecution().id(), title, dueAt, waiting)` 의 결과를 `docs/backend/follow-up.md` 「제안 도구」 표의 글로 바꾼다. `CREATED` 와 `DUPLICATE` 는 `isError` 거짓, 나머지는 참이다. `TOO_MANY_IN_RUN` 은 `TOO_MANY_PROPOSALS` 와 같은 글을 쓴다

`McpDtos` 에 `FollowUpProposeArguments(String title, String dueAt, Boolean waiting)` 를 더한다.
`McpController` 에 상수 `FOLLOW_UP_PROPOSE = "follow_up_propose"` 와 처리 `proposeFollowUp` 을 `handlers` 에 더한다.

- 키는 `title`, `due_at`, `waiting` 셋만 받는다. 그 밖의 키가 하나라도 있으면 `invalidParams`
- `title` 이 없거나 `null` 이거나 문자열이 아니면 `invalidParams`
- `due_at` 이 없거나 `null` 이면 기한 없음이다. 있으면 문자열이어야 하고 아니면 `invalidParams`
- `waiting` 이 없거나 `null` 이면 거짓이다. 있으면 참거짓이어야 하고 아니면 `invalidParams`

### 3. 기존 도구 테스트와 화면 문구

- `McpMemoryToolTest`: `hasSize(6)` 을 `hasSize(7)` 로 고치고, `get(5)` 단언 다음에 `get(6)` 의 `name` 이 `follow_up_propose` 인지 보는 줄을 더한다
- `McpToolServiceTest`: 생성자 호출의 `mock(AgentRepository.class)` 와 `Clock.systemUTC()` 사이에 `mock(FollowUpService.class)` 를 넣는다
- `McpCallerInvariantTest.toolArguments()`: `arguments.put("follow_up_propose", json.createObjectNode().put("title", "할 일 검사 7391"));` 를 끝에 더한다
- `McpAgentToolsTest`: `@ValueSource` 에 `"follow_up_propose"` 를 더하고 `@DisplayName` 의 「도구 여섯」 을 「도구 일곱」 으로 고친다
- `web/src/lib/tool-label.ts` 의 `LABELS` 에 `follow_up_propose: { running: "할 일을 제안하고 있어요", done: "할 일을 제안했어요" }` 를 더한다
- `test/unit/tool-label.test.ts` 에 `toolLabel("mcp__fos_assistant__follow_up_propose", true)` 가 「할 일을 제안하고 있어요」, `false` 가 「할 일을 제안했어요」 인지 보는 줄을 더한다

### 4. fos-ctx

- `hermes/plugins/fos-ctx/__init__.py`: `REQUIRED_TOOLS = frozenset({"memory_read", "artifact_write", "follow_up_propose"})`. 모듈 머리 설명의 「`agent_*` 와 `memory_read`, `artifact_write` 는 서명하지 못하면 막는다.」 를 「`agent_*` 와 `memory_read`, `artifact_write`, `follow_up_propose` 는 서명하지 못하면 막는다.」 로 고친다
- `hermes/tests/test_fos_ctx.py`: `test_memory_and_artifact_tools_block_without_signature` 의 `for tool in ("memory_read", "artifact_write")` 에 `"follow_up_propose"` 를 더한다

### 5. 문서

- `docs/backend/follow-up.md` 「제안 도구」: 입력 표 아래, 「- **주인과 대화는 …**」 목록 앞에 아래를 더한다. 이 절이 도구 계약 전부를 갖는다

  > 도구 정의는 아래와 같다. `tools/list` 에서 Control Plane 도구 가운데 마지막이다.
  >
  > | 칸 | 값 |
  > | --- | --- |
  > | `name` | `follow_up_propose` |
  > | `description` | 「사용자가 나중에 해야 하거나 끝나기를 기다리는 일을 할 일로 제안한다. 사용자가 받아들여야 챙긴다. 대화에서 분명히 나온 후속 작업만 제안하고, 같은 대화에서 거절한 것은 다시 제안하지 않는다. due_at 은 2026-10-05 나 2026-10-05T18:00 형식이다.」 |
  > | `inputSchema` | `{ "type": "object", "additionalProperties": false, "properties": { "title": { "type": "string", "minLength": 1, "maxLength": 200 }, "due_at": { "type": "string" }, "waiting": { "type": "boolean" } }, "required": ["title"] }` |
  >
  > `due_at` 은 `2026-10-05`, `2026-10-05T18:00`, `2026-10-05T18:00:00`, 시간대가 붙은 ISO-8601(`2026-10-05T18:00+09:00`, `2026-10-05T09:00:00Z`)을 받는다. 시간대가 없으면 `Asia/Seoul` 이다.
  > `due_at` 과 `waiting` 의 `null` 은 없는 것으로 본다. `title` 의 `null` 은 빠진 것이다.
  > 모르는 키, `title` 이 없거나 문자열이 아닌 것, `due_at` 이 문자열이 아니거나 `waiting` 이 참거짓이 아닌 것은 JSON-RPC `-32602` 다.
  > 결과는 MCP 도구 결과 `{ "content": [{ "type": "text", "text": "<아래 표의 글>" }], "isError": <참거짓> }` 하나다.

- `docs/backend/follow-up.md` 「제안 도구」 의 「인자의 모양이 틀리면 …」 문단은 위 내용과 겹치므로 지운다
- `docs/backend/mcp-caller.md`: 「Control Plane MCP」 표의 `도구` 줄(187줄)과 59줄의 도구 나열에 `follow_up_propose` 를 더한다. 「도구마다의 인자와 결과」 표에 `| \`follow_up_propose\` | [\`follow-up.md\`](follow-up.md) 의 「제안 도구」 |` 줄을 더한다
- `docs/hermes/fos-ctx.md`: 56줄(「**hook 이 끼우지 못한 호출도 서버에 도착한다.**」 문단)과 97줄(플러그인에 요구하는 동작 문단)의 도구 나열에 `follow_up_propose` 를 더한다
- `docs/hermes/delegation.md`: 367줄 표의 「자식의 Control Plane MCP 호출(`memory_read`, `artifact_write`, `agent_list`, `agent_delegate`, `agent_status`, `agent_stop`)」 에 `follow_up_propose` 를 더한다
- `docs/backend/packages.md`: 「패키지와 책임」 표의 `mcp` 줄을 「Memory 본문 조회, 결과물 쓰기와 할 일 제안 도구의 인자 검사, 장기 토큰 인증과 profile 묶기, 요청자 판정」 으로 고친다
- `hermes/README.md`: 서명 표의 첫 줄을 「`agent_*`, `memory_read`, `artifact_write`, `follow_up_propose`」 로 고친다

**「아직 구현 전」 표시를 지운다.**

- `docs/adr/ADR-073-할-일은-에이전트가-제안하고-사람이-받아들인-것만-챙긴다.md` 의 `status` 줄을 `` - **status**: `accepted` `` 로 둔다
- `docs/adr/INDEX.md` 의 ADR-073 줄 상태 「Accepted. 제안 도구와 제안 억제는 아직 구현 전이다」 를 `Accepted` 로 고친다
- `docs/backend/follow-up.md` 머리의 「**제안 도구(`follow_up_propose`)와 제안 억제는 아직 구현 전이다.** 구현한 PR 이 이 단락을 지운다.」 와 그 다음 줄 「상태와 API 는 구현돼 있다.」 를 함께 지운다
- `docs/flow.md` 「할 일을 제안할 때」 의 「**아직 구현 전이다.** 구현한 PR 이 이 줄을 지운다.」 를 지우고 「계약은 …」 문장만 남긴다
- `docs/code-architecture.md` 「아직 만들지 않은 것」 의 할 일 줄을 지운다. 「할 일의 제안 도구 `follow_up_propose`」 로 시작해 「지금은 사람이 직접 더한 할 일과 API 만 있다」 로 끝나는 줄이다
- `docs/README.md` 의 `backend/follow-up.md` 줄 끝 「제안 도구와 제안 억제는 아직 구현 전이다」 문장을 통째로 지운다
- `docs/backend/packages.md` 「패키지와 책임」 표의 `followup` 줄에서 「(제안 도구는 아직 구현 전이다. [`follow-up.md`](follow-up.md))」 를 「([`follow-up.md`](follow-up.md))」 로 고친다

### 6. e2e 대역

`test/e2e/fake-hermes.ts`

- `export const FOLLOW_UP_PROPOSE_PROBE = "MCP 할 일 제안 검사";` 를 더한다. 입력이 `${FOLLOW_UP_PROPOSE_PROBE} ` 로 시작하면 그 뒤의 JSON 을 도구 인자로 읽어 `follow_up_propose` 를 부르고, 도구 결과 글을 답으로 돌려준다
- `readMemoryViaMcp` 의 서명과 호출 부분을 도구 이름과 인자를 받는 `callControlPlaneToolViaMcp(name, args, sessionId, rootSessionId)` 로 빼고 `readMemoryViaMcp` 는 그것을 부르게 한다. MCP 주소와 토큰은 지금의 `memoryReadMcp` 를 그대로 쓴다

### 7. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/followup/FollowUpProposalTest.java`(`@SpringBootTest`, `@ActiveProfiles("test")`, `@Import(FollowUpProposalTest.FixedClock.class)`. `TaskServiceTest` 의 `TestClock` 과 같은 모양을 이 파일 안에 둔다)

| 입력 | 기대 |
| --- | --- |
| 아빠의 대화와 실행으로 제목 `할 일 검사 7391` 을 `propose` | `CREATED`, `PROPOSED`, `proposed_by_execution_id` 가 그 실행 |
| 같은 제목을 다른 실행에서 다시 | `DUPLICATE`, 줄 수 그대로 |
| 그 줄을 `reject` 뒤 같은 대화에서 다시 | `DECLINED_BEFORE` |
| `TestClock.set` 으로 31일 뒤로 옮겨 다시 | `CREATED` |
| 같은 대화에 다른 제목 셋을 세 실행에서 제안한 뒤 넷째 | `TOO_MANY_PROPOSALS` |
| 한 실행에서 다른 제목 셋 | 셋째가 `TOO_MANY_IN_RUN` |

`backend/src/test/java/com/bifos/assistant/mcp/McpFollowUpToolTest.java`(`McpMemoryToolTest` 의 준비를 따른다)

| 입력 | 기대 |
| --- | --- |
| `tools/list` | 7번째 도구가 `follow_up_propose` 이고 `required` 가 `["title"]`, `additionalProperties` 가 거짓 |
| 서명한 호출, `title` 과 `due_at: "2026-10-05"` | 「할 일로 제안했다」 글, 저장된 `due_at` 이 `2026-10-05T14:59:00Z` |
| 서명한 호출, `due_at: "2026-10-05T18:00:00"` | 저장된 `due_at` 이 `2026-10-05T09:00:00Z` |
| 서명한 호출, `due_at: null`, `waiting: null` | 「할 일로 제안했다」 글, `due_at` 이 비고 `waiting` 이 거짓 |
| `_fos_ctx` 없이 | `McpToolService.invalidContext()` 와 같은 결과. 줄이 생기지 않는다 |
| 모르는 키 `user_id` 를 넣음 | JSON-RPC `-32602` |
| `title` 이 없음 | JSON-RPC `-32602` |
| `title` 이 공백뿐 | `isError` 참, 줄이 생기지 않는다 |
| `due_at` 이 `"다음 주"` | `isError` 참, 「due_at 은」 으로 시작하는 글 |
| 대화가 없는 origin 실행으로 서명 | `isError` 참, 「대화 밖의 실행」 글 |

`test/e2e/scenarios/follow-up-mcp.ts` 를 새로 만든다. profile 이름 상수 `FOLLOW_UP_MCP_PROFILE = "follow-up-mcp"` 를 내보내고, `test/e2e/run.ts` 의 `writeProfileKeys` 목록과 `startFakeHermes` 의 두 표에 `MCP_PRINCIPAL_PROFILE` 처럼 넣는다. 시나리오는 `SCENARIOS` 에서 `mcpPrincipalScenario` 뒤에 둔다.

| 단계 | 기대 |
| --- | --- |
| 관리자가 그 profile 로 에이전트를 등록하고 토큰을 발급해 `setMemoryReadMcp` 로 준다 | 200 |
| 아빠가 `FOLLOW_UP_PROPOSE_PROBE {"title":"할 일 검사 7391"}` 를 보낸다 | 답이 「할 일로 제안했다」 로 시작한다. `GET /follow-ups` 에 `PROPOSED`, `proposed: true`, 그 대화의 `conversationId` |
| 같은 대화에서 같은 입력 | 답이 「같은 할 일이 이미 있다」 로 시작한다 |
| 그 할 일을 `reject` 하고 같은 대화에서 같은 입력 | 답이 「사용자가 이 할 일을 거절했다」 로 시작한다 |
| 아이의 `GET /follow-ups` | 아빠의 줄이 없다 |
| 정리 | 토큰 폐기, 에이전트 끄기. `mcp-principal.ts` 의 `finally` 와 같다 |

## 검증

```bash
# cwd: backend/
./gradlew test --tests 'com.bifos.assistant.followup.*' --tests 'com.bifos.assistant.mcp.*'
./gradlew test
./gradlew checkstyleMain checkstyleTest
./gradlew spotlessCheck
```

```bash
# cwd: 저장소 root
python3 -m unittest discover -s hermes/tests
node --test test/unit/tool-label.test.ts test/unit/doc-references.test.ts
scripts/check-mysql-migration.sh
node test/e2e/run.ts
scripts/check-public-safe.sh
scripts/quality.sh check
git grep -q "follow_up_propose" -- hermes/plugins/fos-ctx/__init__.py
git grep -q "follow_up_propose" -- backend/src/main/java/com/bifos/assistant/mcp/application/McpToolService.java
git grep -q "follow_up_propose" -- docs/backend/mcp-caller.md
git grep -q "follow_up_propose" -- hermes/README.md
git grep -q "follow_up_propose" -- docs/hermes/delegation.md
! git grep -n "아직 구현 전" -- docs/backend/follow-up.md docs/adr/ADR-073-할-일은-에이전트가-제안하고-사람이-받아들인-것만-챙긴다.md
! git grep -n "ADR-073.*아직 구현 전" -- docs/adr/INDEX.md
! git grep -n "아직 구현 전" -- docs/code-architecture.md docs/backend/packages.md | grep -i "follow"
! git grep -n "follow-up.md.*아직 구현 전" -- docs/README.md
```

```bash
# cwd: web/
pnpm lint
pnpm typecheck
```

기대값: 명령이 모두 종료 코드 0 이다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/followup/application/FollowUpService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/followup/application/model/FollowUpProposalOutcome.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/followup/infra/FollowUpRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpDtos.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/followup/FollowUpProposalTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpFollowUpToolTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryToolTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/application/McpToolServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpCallerInvariantTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpAgentToolsTest.java` | 수정 |
| `web/src/lib/tool-label.ts` | 수정 |
| `test/unit/tool-label.test.ts` | 수정 |
| `hermes/plugins/fos-ctx/__init__.py` | 수정 |
| `hermes/tests/test_fos_ctx.py` | 수정 |
| `hermes/README.md` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/e2e/scenarios/follow-up-mcp.ts` | 신규 |
| `test/e2e/run.ts` | 수정 |
| `docs/backend/follow-up.md` | 수정 |
| `docs/backend/mcp-caller.md` | 수정 |
| `docs/backend/packages.md` | 수정 |
| `docs/hermes/fos-ctx.md` | 수정 |
| `docs/hermes/delegation.md` | 수정 |
| `docs/adr/ADR-073-할-일은-에이전트가-제안하고-사람이-받아들인-것만-챙긴다.md` | 수정 |
| `docs/adr/INDEX.md` | 수정 |
| `docs/flow.md` | 수정 |
| `docs/code-architecture.md` | 수정 |
| `docs/README.md` | 수정 |
