# Phase 01. agent_delegate 가 정한 시간까지 결과를 기다린다

**Execution profile**: deep

## 목표

대화 turn 의 루트 실행이 `agent_delegate` 를 부르면 Control Plane 이 그 도구 호출 안에서 결과를 기다려, 상한 안에 끝난 결과를 도구 결과로 돌려주고 자동 답이 열리지 않게 한다.

**범위 외**: 맡긴 실행의 사건 기록과 중계(phase 02), 화면(phase 03), e2e(phase 04).

## 컨텍스트

**요구사항**: REQ1, REQ2

**근거 문서**: `docs/features/assistant-hub/spec.md`(REQ1, REQ2, DECISION1, DECISION11, DECISION13, DECISION14, DECISION19, DECISION20), `docs/features/assistant-hub/design.md` 의 「PR1 `agent_delegate` 의 기다리기」 와 「실패와 일관성」, `backend/docs/adr/ADR-20261121-delegate-wait.md`

- 지금 `McpToolService.delegate`(`backend/src/main/java/com/bifos/assistant/mcp/application/McpToolService.java`)는 `AgentDelegationService.delegate` 의 `DelegationResult.started(id, RUNNING)` 를 `{"execution_id","status"}` JSON 으로 곧바로 돌려준다.
- 끝난 결과의 모양과 전달 표시는 이미 `agent_status` 가 쓴다: `McpToolService.statusOf(AgentExecution)` 와 `markDeliveredIfFinished(AgentExecution)`. 같은 메서드를 쓴다.
- 기다리는 부품은 `RunningDelegation.awaitEnded(Duration)` 이고 `AgentDelegationService` 안의 `runs.find(executionId)`(`DelegationRuns`)로 얻는다. 먼저 살펴보기 전용 기다림 `status(user, origin, id, wait)` 이 같은 꼴이다.
- 루트 turn 의 중지 확정은 `TurnCancellation.isStopConfirmed(Long executionId)`(루트 실행 번호)로 읽는다. 새 `DelegationWaiter` 가 주입받는다.
- 먼저 살펴보기 트리 판정은 `ProactiveCheckGuard.isCheckTree(origin)` 이다.

## 의도 메모

- 모델에게 `agent_status` 의 `wait_seconds` 를 부르게 하는 안은 기각했다(설계 DECISION1). 기다림은 도구 안에서 정해진 동작이다.
- Hermes 는 우리 MCP 도구를 하나씩 부르므로 같은 답의 두 맡김은 차례로 기다린다. 예산은 루트별로 첫 기다림 시각부터 센다(스펙 DECISION20).
- 중지가 확정됐으면 전달 표시를 적지 않는다. 적으면 결과를 받을 모델이 이미 멈춰 결과가 어디에도 전해지지 않는다(설계 「중지」).

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/orchestration/config/DelegationProperties.java`

- 마지막 칸 `@DefaultValue("2m") Duration delegateWaitMax` 를 더한다. Javadoc: 「대화 turn 의 `agent_delegate` 가 결과를 기다리는 한 답 안의 상한. 0 이면 기다리지 않는다(ADR-20261121 / delegate-wait)」.
- 생성자 검사는 null 과 음수만 막는다(`requireNonNegative` 를 새로 둔다). 0 은 허용한다.
- 이 record 를 직접 만드는 테스트(`grep -rn "new DelegationProperties(" backend/src`)의 인자를 함께 고친다.

### 2. `backend/src/main/resources/application.yml`, 테스트 설정, e2e 설정

- `application.yml` 의 `assistant.delegation` 아래 `status-wait-max` 다음에 `delegate-wait-max: 2m` 과 한 줄 주석(대화 turn 의 맡김을 기다리는 한 답 안의 상한, 0 이면 기다리지 않는다)을 둔다.
- `backend/src/test/resources/application-test.yml` 에 `assistant.delegation.delegate-wait-max: 0s` 를 둔다. 맡긴 뒤 바로 `RUNNING` 을 기대하는 기존 통합 테스트를 지금 동작으로 두기 위해서다.
- `test/e2e/run.ts` 의 백엔드 환경 변수에 `ASSISTANT_DELEGATION_DELEGATE_WAIT_MAX: "0s"` 를 더한다. e2e 는 백엔드 하나로 모든 시나리오를 돌리고, `test/e2e/scenarios/chat-queue.ts` 와 `delegation.ts` 가 결과 자동 답(`SYSTEM` 줄과 자동 답)을 기대한다. 기다림의 동작은 아래 8 의 backend 통합 테스트가 본다.

### 3. 새 `backend/src/main/java/com/bifos/assistant/orchestration/application/DelegationWaiter.java`

`@Component`. 기다리기와 예산을 모두 이 클래스에 둔다. `AgentDelegationService.java`(482줄), `McpToolService.java`(479줄)가 파일 길이 상한 500줄(`scripts/checks/check-file-length.mjs`)에 가까워 그 두 파일에는 호출 몇 줄만 더한다.

- 의존: `AgentDelegationService`, `TurnCancellation`, `ProactiveCheckGuard`, `LiveProperties<DelegationProperties>`, `AgentExecutionRepository`, `Clock`.
- 예산: `ConcurrentHashMap<Long, Instant>` 에 루트 실행 번호마다 첫 기다림 시각을 둔다. `remaining(rootId, max)` 는 `max` 가 0 이하이면 0, 처음이면 지금을 적고, `시작 + max - 지금` 을 돌려주되 음수면 0. 부를 때 `시작 + max * 2` 가 지난 항목을 지운다(설계 「예산」).
- `DelegationWait await(AgentExecution origin, Long executionId)`. 새 record `backend/src/main/java/com/bifos/assistant/orchestration/application/dto/DelegationWait.java` 는 `(AgentExecution execution, boolean deliverable)` 이고 `execution` 은 다시 읽은 줄(없으면 null)이다.
  1. 대상이 아니면 기다리지 않고 다시 읽어 `deliverable=false` 로 돌려준다. 대상은 `origin.parentExecutionId() == null` 이고 `!checkGuard.isCheckTree(origin)` 이다(스펙 DECISION11).
  2. 남은 예산이 0 이면 같은 결과다.
  3. `delegations.running(executionId)` 가 null 이면(이 서버가 돌리지 않음) 기다리지 않는다.
  4. 남은 시간 안에서 `delegation.awaitEnded(min(1초, 남은 시간))` 를 되풀이한다. 매번 `turns.isStopConfirmed(origin.treeRootId())` 가 참이면 멈춘다.
  5. 줄을 다시 읽는다. `RUNNING` 이 아니고 중지가 확정되지 않았으면 `deliverable=true`.
- 트랜잭션을 걸지 않는다(`AgentDelegationService.status(..., wait)` 와 같은 까닭).

### 4. `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentDelegationService.java`

패키지 안 메서드 `RunningDelegation running(Long executionId) { return runs.find(executionId); }` 하나만 더한다.

### 5. `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolService.java`

`delegate` 에서 `delegated.accepted()` 뒤:

- 새 의존 `DelegationWaiter waiter`. `DelegationWait waited = waiter.await(origin, delegated.executionId())`.
- `waited.deliverable()` 이면 `markDeliveredIfFinished(waited.execution())` 를 부르고 `statusOf(waited.execution())` 을 JSON 으로 돌려준다.
- 아니면 지금처럼 `execution_id`, `status`(다시 읽은 상태, 끝났으면 그 상태)에 `note` 를 넣는다. `RUNNING` 일 때만 `note` 는 「아직 끝나지 않았다. 결과는 끝나면 이 대화의 다음 차례에 자동으로 전달된다. 이번 답은 누구에게 무엇을 맡겼는지 한 줄로 끝내고 결과를 짐작해 쓰지 않는다.」 다.
- 다시 읽은 줄이 없으면 지금 응답 그대로다.

### 6. `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolCatalog.java`

`agent_delegate` 설명을 바꾼다. 「다른 에이전트에게 일을 맡긴다. 대화에서는 정한 시간(최대 N초)까지 결과를 기다려 output 으로 함께 돌려주므로, 결과가 오면 그것으로 답한다. status 가 RUNNING 이면 note 를 따른다. agent_code 에는 …(지금 문장 그대로)」. N 은 `delegateWaitMax().toSeconds()` 다. 0 이면 지금 설명을 그대로 둔다.

### 7. 문서

- `docs/features/agent-skill.md` 의 「다른 에이전트에게 맡기기」 목록 첫 줄(`agent_delegate` 는 제출까지만 기다린다)을 대화 turn 의 루트가 맡기면 상한까지 기다린다로 고치고 ADR-20261121 / delegate-wait 를 링크한다. 「위임 결과가 도착했을 때」 표 머리에 「상한 안에 끝나 도구가 돌려준 결과는 전한 것으로 적혀 깨우지 않는다」 한 줄을 더한다.

### 8. 테스트

- 새 `backend/src/test/java/com/bifos/assistant/orchestration/application/DelegationWaiterTest.java`(대역): 가짜 시계로 첫 호출이 상한 전체, 30초 뒤 같은 루트가 남은 시간, 상한을 지나면 0, `max` 0 이면 기다리지 않음, 오래된 항목이 지워진다. 중지 표시가 서면 1초 안에 풀리고 `deliverable` 이 거짓이다. 먼저 살펴보기 트리와 부모가 있는 origin 은 기다리지 않는다.
- `backend/src/test/java/com/bifos/assistant/orchestration/DelegationPropertiesTest.java`: 0 은 받고 음수는 기동 실패다.
- `backend/src/test/java/com/bifos/assistant/mcp/McpAgentToolsTest.java`: `OverridableLiveProperties`(`backend/src/test/java/com/bifos/assistant/testsupport/`)로 `delegateWaitMax` 를 몇 초로 바꾼 사례를 더한다. 가짜 Hermes 자식이 곧 끝나면 결과에 `output` 이 있고 그 실행의 `result_delivered_at` 이 적혔다. 그 대화의 turn 이 닫힌 뒤 결과 알림 줄(`SYSTEM`)이 생기지 않는다(깨우기는 전한 결과를 열지 않는다, `DelegationWakeServiceTest` 가 이미 보는 규칙). 자식이 상한을 넘기면 `RUNNING` 과 `note` 이고 `result_delivered_at` 이 비었다. 먼저 살펴보기 트리와 손자 위임은 기다리지 않는다. 루트 turn 의 중지가 확정되면 기다림이 곧 풀리고 `result_delivered_at` 이 비었다. 기존 사례는 `0s` 로 지금처럼 통과한다.
- `backend/src/test/java/com/bifos/assistant/mcp/application/McpToolCatalogTest.java`: 상한이 0 이 아니면 설명에 초 값이 들고, 0 이면 지금 설명이다.

## 검증

```bash
cd backend && ./gradlew test --tests '*DelegationWaiterTest' --tests '*DelegationPropertiesTest' --tests '*McpAgentToolsTest' --tests '*McpToolCatalogTest' --tests '*AgentDelegationServiceTest' --tests '*AgentDelegationServiceRaceTest' --tests '*AgentRunnerSubmitFailureTest' --tests '*AgentRunnerConnectorContextTest' --tests '*DelegationOutputTest'
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck
node scripts/checks/check-file-length.mjs
node --test test/repo/doc-links.test.ts test/repo/doc-files.test.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/orchestration/config/DelegationProperties.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/resources/application-test.yml` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/DelegationWaiter.java` | 신규 |
| `test/e2e/run.ts` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/dto/DelegationWait.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentDelegationService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolCatalog.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/application/DelegationWaiterTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/orchestration/DelegationPropertiesTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpAgentToolsTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/application/McpToolCatalogTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/AgentRunnerSubmitFailureTest.java` | 수정 (설정 인자) |
| `backend/src/test/java/com/bifos/assistant/orchestration/AgentDelegationServiceRaceTest.java` | 수정 (설정 인자) |
| `backend/src/test/java/com/bifos/assistant/orchestration/AgentRunnerConnectorContextTest.java` | 수정 (설정 인자) |
| `backend/src/test/java/com/bifos/assistant/orchestration/DelegationOutputTest.java` | 수정 (설정 인자) |
| `docs/features/agent-skill.md` | 수정 |
