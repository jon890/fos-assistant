# Phase 05. `agent_stop` 과 turn 중지를 잇고 e2e 로 전체를 확인한다

**Execution profile**: deep

## 목표

맡긴 실행을 그 실행만 멈추는 `agent_stop` 을 열고, 사용자가 turn 을 중지하면 그 turn 이 도는 동안 맡긴 자식도 함께 멈추게 한다.
가짜 Hermes e2e 로 위임, 상태, 중지, 실행 나무를 한 번에 확인하고 PR 을 연다.

**범위 외**: 그 실행 아래의 실행까지 멈추는 것(`docs/code-architecture.md` 「아직 만들지 않은 것」). 사용자 전체 동시 한도. 큰 화면 변경. Hermes `delegate_task` 가 만든 native 하위 에이전트를 실제로 멈추는 구현(아래 「native 하위 에이전트의 중지 정책」 의 조사가 끝난 뒤 따로 계획한다).

## 컨텍스트

- Hermes 중지는 `HermesRunsClient.stop(apiBaseUrl, profileName, runId)` 이고, 이미 끝난 run 은 404 를 로그만 남기고 넘긴다(`HttpHermesRunsClient.stop`)
- `AgentRunner.run` 은 `cancelled` 가 참이면 `executions.cancel(...)` 로 `CANCELLED` 를 적는다. 끝나기를 기다리던 중 중지된 run 은 Hermes 가 `cancelled` 로 끝낸다
- turn 중지는 `TurnCancellation` 이 갖는다. `trackRun(executionId, apiBaseUrl, profileName, runId)` 로 turn 에 run 을 붙이면 사용자가 중지할 때 `stopRun` 으로 함께 멈춘다. turn 의 핸들은 뿌리 실행 번호로 찾는다(`find(executionId)`)
- phase 04 의 위임은 가상 스레드에서 `AgentRunner.run` 을 돌리고 `cancelled` 를 받는다
- e2e 는 `test/e2e/scenarios/` 에 시나리오 하나씩 두고 `run.ts` 가 차례로 돌린다. 토큰 발급과 MCP 호출은 `test/e2e/scenarios/artifact.ts` 가 본보기다(`/admin/agent-tokens` 로 발급). 가짜 Hermes 는 `holdNextRun()`, `releaseHeldRun()`, `stoppedRuns()` 로 run 을 붙잡고 중지를 기록한다
- 실행 나무는 `GET /api/v1/usage/executions/{id}/tree`(`UsageController`, `ExecutionTreeService.of`)

**근거 문서**: `docs/flow.md` 의 「다른 에이전트에게 맡길 때」 절, `docs/adr/ADR-017-무엇을-할지는-hermes-가-정하고-control-plane-은-경계만-갖는다.md` 의 「도구 넷과 한도」 와 「`ResearchAndBuildFlow` 의 자리」 절, `docs/hermes/delegation.md` 의 「취소가 아래로 내려가지 않는다」 절

## 의도 메모

- `agent_stop` 의 권한은 `agent_status` 와 같다(요청자인 origin 실행의 사용자의 위임 실행이고, origin 실행에 대화가 있으면 같은 대화, 없으면 같은 뿌리). 아니면 없는 실행과 같은 응답. `AgentDelegationService.status` 와 같은 판정 메서드를 쓴다
- 이미 끝난 실행이면 멈추지 않고 그 상태를 돌려준다(오류가 아니다)
- `CANCELLED` 에서 멈춘 자리까지의 답(flow.md 「`CANCELLED` 는 답이 있으면 `output`」): 지금 `ChildResult.failed` 는 답을 버린다. 중지 경로에서 받은 답이 있으면 `CANCELLED` 와 같은 저장에서 `output_text` 에 적는다(phase 04 의 `complete` 와 같은 방식). 받은 답이 없으면 비운다
- 도는 실행이면 위임 서비스가 들고 있는 그 실행의 중지 표시를 켜고, run 번호가 있으면 Hermes 에 중지를 보낸다. `CANCELLED` 가 적히기를 짧게(예: 5초) 기다려 적혔으면 `CANCELLED`, 아니면 `RUNNING` 과 `stop_requested: true` 를 돌려준다. 멈추기와 끝나기가 겹치면 먼저 적힌 상태가 남는다
- 서버가 다시 떠 중지 표시가 없는 도는 실행은 기동 정리가 `ORPHANED` 로 끝낸다. `agent_stop` 이 그 경우에도 Hermes 에 중지를 보내는지는 run 번호가 있으면 보낸다
- turn 중지 연결: 위임 자식의 run 번호가 붙을 때(`onSubmitted`) 그 뿌리 실행의 turn 핸들이 있으면 `TurnCancellation.trackRun(뿌리 실행 번호, ...)` 으로 붙인다. turn 이 끝난 뒤에는 핸들이 없으므로 붙이지 않는다
- 사용자가 뿌리 turn 을 중지하면 자식을 두 갈래로 본다
  - FOS `agent_delegate` 로 시작한 자식: 위 「turn 중지 연결」 대로 멈춘다
  - Hermes native `delegate_task` 자식: 가능하면 멈추거나 권한을 거둔다. 권한은 이미 거둔다. origin 실행이 `CANCELLED` 면 그 자식의 Control Plane MCP 호출(`memory_read`, `artifact_write`, 이 계획의 `agent_*`)이 모두 거절된다(ADR-037, `SessionOwnerResolver`). 이 phase 의 `agent_*` 도 같은 판정을 지나므로 따로 막지 않는다
- 그 판정은 origin 실행과 그 뿌리 실행(`root_execution_id`)만 본다. `agent_stop` 으로 위임 자식 하나만 멈추면 그 아래의 실행에서 만든 native 하위 에이전트는 origin 도 뿌리도 `CANCELLED` 가 아니라 막히지 않는다. 이 phase 에서 `agent_stop` 을 구현할 때 origin 에서 뿌리까지의 사슬을 볼지 다시 정하고 ADR-037 을 고친다
- `ResearchAndBuildFlow` 는 기능을 더하지 않는다. 클래스 Javadoc 에 「새 흐름을 더하지 않는다. 지우는 조건은 ADR-017 「`ResearchAndBuildFlow` 의 자리」」 한 단락을 더한다
- 화면: 실행 나무와 작업 과정이 위임 자식을 이미 그리는지 e2e 의 나무 조회로 확인한다. 그리지 못하면 최소한만 고친다

### native 하위 에이전트의 중지 정책

native 자식을 실제로 멈추는 것은 이 phase 에서 구현하지 않는다. 아래 둘을 조사한 뒤 따로 계획한다.
조사는 phase 로 만들지 않고 orchestration 으로 워커에 맡기며, 결론은 `docs/hermes/delegation.md` 「취소가 아래로 내려가지 않는다」 절에 남긴다.

- `subagent_stop` hook: 언제 불리고 무엇을 받는가. 자식을 멈추는 수단인가, 끝났다는 알림인가
- Hermes 비동기 위임 제어: background 로 떨어져 나간 `delegate_task` 자식을 부모 session 이나 자식 session 으로 멈추는 API 가 있는가. 부모 run 의 중지(`POST /v1/runs/{run_id}/stop`)가 자식에 닿는가

## 작업 항목

### 1. `AgentDelegationService.stop` 과 중지 표시

위임 실행마다 중지 표시(`AtomicBoolean`)와 run 참조를 들고, 끝나면 지운다. `stop(CurrentUser user, AgentExecution origin, Long executionId)`. `McpCaller` 를 받지 않는다(phase 02, `orchestration` 은 `mcp` 를 import 하지 않는다). 권한 판정은 phase 03 이 둔 `status` 와 같은 메서드를 쓴다.

### 2. turn 중지 연결

위 의도 메모대로 `onSubmitted` 에서 뿌리의 turn 핸들에 run 을 붙인다.

### 3. MCP 규격과 경로

`McpToolService.tools()` 에 `agent_stop`(`execution_id` 정수) 규격과 설명, `McpController.call` 에 경로.

### 4. backend 테스트

- 도는 위임 실행을 `agent_stop` 하면 Hermes 에 중지가 가고(`StubHermesRunsClient` 의 중지 기록) 실행이 `CANCELLED` 로 남는다
- 끝난 실행에 `agent_stop` 은 끝난 상태를 그대로 준다
- 남의 실행, 다른 대화의 실행은 없는 실행과 같은 응답
- 같은 대화의 앞 turn 에서 맡긴 실행(다른 뿌리)은 멈춘다
- 사용자가 turn 을 중지하면 그 turn 이 도는 동안 맡긴 자식도 멈춘다

### 5. e2e `test/e2e/scenarios/delegation.ts`

가짜 Hermes 를 쓴다. 테스트가 profile 플러그인 역할을 하며 `test/e2e/mcp-context.ts` 의 `signedCallContext` 로 `_fos_ctx` 를 계약대로 서명해 `/mcp` 를 직접 부른다.

1. `{ profileName, label }` 로 토큰을 발급하고, 대화 turn 하나를 붙잡아 도는 뿌리 실행과 그 `fos-` session 을 만든다
2. `agent_list` 가 쓸 수 있는 에이전트만 준다
3. `agent_delegate` 가 바로 번호와 `RUNNING` 을 준다. 자식 run 을 붙잡아 두고 `agent_status` 가 `RUNNING` 이다
4. 자식을 끝내면 `agent_status` 가 `SUCCEEDED` 와 답을 준다
5. 다시 위임하고 `agent_stop` 하면 가짜 Hermes 의 `stoppedRuns()` 에 그 run 이 있고 상태가 `CANCELLED` 다
6. 서명을 바꾼 호출, 다른 profile 의 토큰, 없는 에이전트, 같은 `tool_call_id` 두 번(실행 하나)
7. `GET /api/v1/usage/executions/{뿌리}/tree` 에 자식 둘이 뿌리 아래로 보이고 각자 토큰과 모델이 따로 적혀 있다

`run.ts` 에 시나리오를 더한다.

### 6. `ResearchAndBuildFlow` 의 Javadoc 과 PR

- Javadoc 한 단락을 더한다
- PR 본문과 보고서는 아래 여덟 절 형식으로 쓴다: 1. 기존 구조 분석(위임이 왜 불완전했는지) 2. 구현한 구조(최종 호출 흐름 ASCII 그림) 3. 주요 변경 파일(왜 바꿨는지) 4. 보안 경계(user, agent, memory, profile, credential, execution 권한을 각각 어떻게 지켰는지) 5. 실패 처리(Hermes 불가, 실패한 run, 중지 경합, 동시 위임 등) 6. 테스트(실제로 돌린 것과 결과) 7. 남은 제한(이번 범위 밖으로 둔 것) 8. Runtime 추상화 전에 풀어야 할 문제
- 운영에서 동작하려면 profile 플러그인이 있어야 한다는 것을 PR 본문 앞에 적는다(배치는 `fos-home-infra`)
- `docs/code-architecture.md` 「아직 만들지 않은 것」 에서 「MCP `agent_delegate` 와 `agent_stop`, 그것을 처리하는 `AgentDelegationService` 의 위임 시작과 중지, `DelegationProperties`」 줄을 뺀다. 읽기 도구 `agent_list`, `agent_status` 는 먼저 열려 그 줄에 없다
- `docs/hermes/tools-and-skills.md` 「Control Plane MCP」 표의 도구 행에서 `agent_*` 가 아직 없다는 문장을 빼고 여섯 도구를 모두 적는다
- `docs/flow.md` 「다른 에이전트에게 맡길 때」 의 「갈리는 지점」 의 서명 거절과 부모 없음 행이 「MCP 호출의 요청자를 정할 때」 의 같은 도구 결과(`McpToolService.invalidContext()`, `isError: true`)를 가리키게 맞춘다

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

구현하며 계약이 바뀌었으면 ADR-017, ADR-031, ADR-037 과 `docs/` 를 함께 고친다. 계획서 삭제는 이 phase 가 하지 않는다. build-with-teams 의 마감 단계가 PR 의 마지막 커밋으로 지운다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentDelegationService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/ResearchAndBuildFlow.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/AgentDelegationServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpAgentToolsTest.java` | 수정 |
| `test/e2e/scenarios/delegation.ts` | 신규 |
| `test/e2e/run.ts` | 수정 |
| `docs/code-architecture.md` | 수정 |
| `docs/flow.md` | 수정 |
| `docs/hermes/tools-and-skills.md` | 수정 |
| `docs/adr/ADR-037-hermes-하위-에이전트-session-의-주인은-만들-때-등록한-줄로-정한다.md` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentRunner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionRecorder.java` | 수정 |
