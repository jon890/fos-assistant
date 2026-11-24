# Phase 02. Control Plane 이 도구 기록을 받아 오류 요약을 적는다

**Execution profile**: standard

## 목표

`/internal/hermes/run-trace` 로 plugin 의 도구 기록을 받아, 그 실행의 가림 범위로 오류 글을 가리고 `TOOL_COMPLETED` 줄에 걸린 시간(빈 때만), 오류 종류, 오류 요약을 적는다.

**범위 외**: 짝이 없는 기록의 새 줄(PR2), 모델 호출 기록(PR3).

## 컨텍스트

**요구사항**: REQ2, REQ6

**근거 문서**: `docs/features/run-trace/design.md` 의 「session 을 실행으로 푼다」, 「짝짓기 장부」, 「plugin 에서 Control Plane 으로」, `hermes/plugins/fos-ctx/README.md` 의 「도구 판정」

- 인증과 본문 읽기는 `SubagentSessionController`, 서명 확인은 `SubagentRegistration.verify` 와 `ToolGuard` 경로를 따른다. 경로 허용은 `AgentTokenAuthenticationFilter` 의 목록에 더한다.
- 실행 풀기는 `EventToolGuard.decide` 와 같은 순서(등록된 session 의 origin, 아니면 루트 session 의 `RUNNING` 실행 하나)다. PR1 은 등록된 session 과 `tool_call_id` 없는 기록을 버린다.
- 가림 범위는 `ChildRunEvents.detailScope` 와 같다: 실행 에이전트의 `connectorToolPrefixes` 와 `ExecutionConnectorCalls.calledBefore(treeRootId, 도구 시작 시각)`.

## 의도 메모

- 새 사건 줄을 넣지 않는다. 실행마다 메모리에 있는 사건 번호와 겹친다(설계 DECISION2).
- 오류 글 원문, 토큰, 서명은 로그에 남기지 않는다. 거절은 이유만 로그에 남긴다.

## 작업 항목

### 1. `usage/execution/application/ToolTraceLedger.java` (신규)

`(executionId, toolCallId)` 열쇠의 기다리는 기록 표와 저장된 줄 표. `claim`, `saved`, `offer` 를 한 잠금 안에서 돌린다. 10분 만료, 표마다 5,000개 상한. `ToolTrace(Long durationMs, String errorType, String errorSummary)` 기록.

### 2. `ExecutionEventRecorder` 와 저장 자리

`record` 가 `TOOL_COMPLETED` 를 만들 때 `ledger.claim` 으로 칸을 채운다. `ChatRunEvents.store`, `ChildRunEvents.store` 가 저장 직후 `ExecutionEventRecorder.saved(event)` 를 부르고, 늦게 온 기록이 있으면 `ExecutionEventRepository` 의 줄 갱신(걸린 시간은 비었을 때만)을 부른다.

### 3. `orchestration/application/RunTraceIntake.java` (신규)

기록마다 실행을 풀고, 오류 글을 `ToolDetailRedactor.redactError` 로 가리고, 오류 종류를 64자로 자른 뒤 `ledger.offer`. 저장된 줄이 있으면 그 줄을 고친다. 기록 하나의 실패는 그 기록에서 멈춘다.

### 4. `mcp/presentation/RunTraceController.java`, `mcp/application/dto/RunTraceBatch.java` (신규), `AgentTokenAuthenticationFilter`

본문 `{"v":1,"records":[...]}` 를 읽고 기록마다 서명(`v1-run-trace`, kind, 루트, session, tool_call_id)을 확인한다. 100개를 넘으면 앞 100개만 받는다. 202 `{"accepted": n}`.

### 5. 테스트

- `ToolTraceLedgerTest`: 세 도착 순서에서 한 줄에 모인다. 만료와 상한.
- `RunTraceEndpointTest`(MockMvc, H2): 비밀값과 UUID 가 든 오류 글이 가려져 200자 안으로 저장된다. 플러그인 도구와 플러그인 호출 뒤 도구는 오류 종류만 남는다. 틀린 서명, 남의 profile, 도는 실행이 없는 session 은 저장되지 않는다. 표식 글이 저장값과 로그에 없다.

## 검증

```bash
cd backend && HEAVY_SLOTS=2 orch slot heavy -- ./gradlew test --tests '*ToolTraceLedgerTest' --tests '*RunTraceIntakeTest' --tests '*RunTraceEndpointTest' --tests '*ChatRunEventsTest' --tests '*ChildRunEventsTest' --tests '*AgentTokenFilterWiringTest'
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/usage/execution/application/ToolTraceLedger.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/execution/application/dto/ToolTrace.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/execution/application/ExecutionEventRecorder.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/execution/infra/ExecutionEventRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/application/ChatRunEvents.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/ChildRunEvents.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/RunTraceIntake.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/RunTraceController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/dto/RunTraceBatch.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/AgentTokenAuthenticationFilter.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/execution/application/ToolTraceLedgerTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/mcp/RunTraceEndpointTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/orchestration/application/RunTraceIntakeTest.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/runtime/domain/WithheldToolContent.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/execution/application/ToolTraceWriter.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/execution/infra/RunningExecutionRepository.java` | 신규 (그 시각에 돌던 실행) |
| `backend/src/main/java/com/bifos/assistant/shared/auth/ControlPlaneJwtFilter.java` | 수정 (경로) |
| `backend/docs/data-schema.md` | 수정 |
| `docs/features/execution.md` | 수정 |
| `docs/features/chat.md` | 수정 |
