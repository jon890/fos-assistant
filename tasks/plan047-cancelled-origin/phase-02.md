# Phase 02. 중지한 turn 의 하위 에이전트 거절을 가짜 Hermes e2e 로 고정한다

**Execution profile**: standard

## 목표

실제 대화 turn 을 중지한 뒤 그 turn 에서 등록한 하위 에이전트의 `memory_read` 가 거절되는 것을 e2e 로 한 번 확인한다.
backend 검사는 상태를 SQL 로 바꾸지만, 이 검사는 사용자의 중지 요청이 `CANCELLED` 를 적고 판정이 그것을 읽는 전체 길을 지난다.

**범위 외**: backend 판정 변경(phase 01). 가짜 Hermes 의 동작 변경. 하위 에이전트를 실제로 멈추는 것.

## 컨텍스트

- 시나리오는 `test/e2e/scenarios/native-delegation-mcp.ts` 의 `nativeDelegationScenario` 다. GROUP 에이전트 `native-delegation` 과 profile `native-delegation-group` 을 만들고, 토큰을 발급해 `context.hermes.setMemoryReadMcp(...)` 로 가짜 Hermes 에 준다. `try` 안의 단계 뒤 `finally` 가 Memory, 토큰, 에이전트를 정리한다
- 가짜 Hermes(`test/e2e/fake-hermes.ts`)
  - 입력이 `SUBAGENT_MEMORY_PROBE` 로 시작하면 run 을 받을 때 `registerSubagent(submitted.session_id)` 로 자식 `native-<uuid>` 를 등록하고 `subagentRegistrations()` 에 `{ childSessionId, rootSessionId, status }` 를 남긴다. 등록은 `holdNextRun` 여부와 무관하게 run 을 받는 순간 한다
  - `holdNextRun()` 뒤 제출된 run 은 `running` 으로 붙잡힌다. `waitForHeldRun()` 이 제출을 기다린다. 중지 경로(`POST /p/{profile}/v1/runs/{run_id}/stop`)가 그 run 을 `cancelled` 로 바꾼다
  - `readMemoryAsSubagent(childSessionId, memoryId)` 가 등록한 뿌리로 서명해 `memory_read` 를 부르고 도구 결과 글을 돌려준다
- 중지 본보기는 `test/e2e/scenarios/stop.ts` 다. `/chat/messages/stream` 을 열고 `web/src/lib/stream.ts` 의 `readEventStream` 으로 `started` 사건의 `executionId` 를 받은 뒤 `POST /chat/executions/{executionId}/stop`(202, `{ status: "stopping" }`) 을 보내고 스트림이 끝나기를 기다린다. 끝난 실행 상태는 `/usage/executions?limit=50` 으로 본다
- 거절 문구는 이 시나리오의 `INVALID_CONTEXT` 상수(「호출 맥락을 확인할 수 없습니다」)다

**근거 문서**: `docs/flow.md` 「MCP 호출의 요청자를 정할 때」 의 「갈리는 지점」, `docs/adr/ADR-037-hermes-하위-에이전트-session-의-주인은-만들-때-등록한-줄로-정한다.md`

## 의도 메모

- 새 시나리오 파일을 만들지 않고 이 시나리오의 `try` 끝에 단계를 더한다. 에이전트, 토큰, Memory 준비와 정리를 다시 쓰지 않기 위해서다
- 중지 전에 같은 자식이 읽을 수 있음을 먼저 확인한다. 그래야 중지 뒤의 거절이 등록 실패가 아니라 취소 때문임을 안다
- 가짜 Hermes 는 바꾸지 않는다. 필요한 기능(`holdNextRun`, 등록, 중지)이 이미 있다
- 스트림 도우미는 `stop.ts` 에서 가져오지 않고 이 시나리오 안에 필요한 만큼만 둔다. `stop.ts` 는 내보내는 것이 시나리오 하나다

## 작업 항목

### 1. `test/e2e/scenarios/native-delegation-mcp.ts` 에 단계를 더한다

「등록하지 않은 자식 session 의 호출은 거절된다」 단계 뒤, `try` 안에 둔다.

1. `step("아빠가 turn 을 중지하면 그 turn 에서 등록한 자식의 호출이 거절된다")`
2. `context.hermes.holdNextRun()` 뒤 아빠 토큰으로 `/chat/messages/stream` 에 `{ text: SUBAGENT_MEMORY_PROBE, agentCode: AGENT_CODE }` 를 연다
3. `waitForHeldRun()` 과 `started` 사건을 5초 안에 받는다. `subagentRegistrations()` 에서 이 스트림 전에 없던 줄 하나를 찾아 `status === 201` 인지 본다
4. 중지 전에 `readMemoryAsSubagent(자식, dadMemory.id)` 가 「아빠 위임 검사 본문」 을 준다
5. `POST /chat/executions/{executionId}/stop` 이 202 이고, 스트림이 5초 안에 끝나고, `/usage/executions?limit=50` 에서 그 실행이 `CANCELLED` 다
6. 같은 자식의 `readMemoryAsSubagent` 결과가 `INVALID_CONTEXT` 로 시작하고 본문 글자가 없다
7. 앞 단계에서 `SUCCEEDED` 로 끝난 turn 의 자식(`dadChild`)은 여전히 본문을 읽는다

스트림을 열고 `started` 를 기다리는 부분은 `stop.ts` 의 `stream`, `within` 을 본떠 이 파일 안의 작은 함수로 둔다. 실패하면 `fail` 로 무엇을 기다렸는지 적는다.

## 검증

```bash
cd backend && ./gradlew test
node test/e2e/run.ts
```

기대: `부모가 끝난 뒤의 하위 에이전트 MCP` 시나리오가 새 단계를 포함해 통과하고 다른 시나리오도 통과한다. `gradlew test` 를 먼저 돌려야 앞선 e2e 가 남긴 데이터에 걸리지 않는다(`AGENTS.md` 「확인」).

## 변경 파일

| 파일 | 변경 |
|---|---|
| `test/e2e/scenarios/native-delegation-mcp.ts` | 수정 |
