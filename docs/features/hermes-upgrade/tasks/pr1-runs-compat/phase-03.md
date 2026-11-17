# Phase 03. steer 정리 순서와 잃은 실행 미리 풀기

**Execution profile**: standard

## 목표

종료 사건을 얻지 못한 turn 의 끼운 글을 「전달됐는지 확인 못 함」 으로 되돌리고, 새 실행을 보내기 전에 같은 profile 의 잃었을 수 있는 실행을 해제한다.

**범위 외**: 어댑터의 번역(phase 02), 가짜 Hermes 와 문서(phase 04).

## 컨텍스트

**요구사항**: REQ2, REQ3

**근거 문서**: `docs/features/hermes-upgrade/spec.md` 의 DECISION3, DECISION16, DECISION17, `docs/features/hermes-upgrade/design.md` 의 「잃은 실행 미리 풀기」, DECISION8

- steer 정리: `ChatTurnRunner.settleSteer`(237행 부근)와 `RecoveredRunRecorder.settle`(113행 부근). 지금은 `cancelled` 판정을 먼저 본다. 이 두 경로의 테스트는 `backend/src/test/java/com/bifos/assistant/chat/SteerSettleTest.java` 에 있다.
- 미리 풀기를 부를 자리 셋: `ChatRunEvents.submit`(59행), `AgentRunner`(163행), `RoomContext`(169행). 모두 `HermesRunCommand` 를 들고 `hermes.submit(command)` 를 부른다. `StarterSuggestionService` 는 늘 새 session 으로 보내 막힐 대기열이 없어 부르지 않는다.
- 열쇠는 profile 이다. 실행 줄의 `hermesSessionId` 는 대화의 루트 session 이고 Hermes 에 보낸 session 은 압축 뒤 다를 수 있으며(`RunSession`), 방 요약 줄(`ExecutionRecorder.startDetached`)은 session 과 대화가 비어 있다.
- `ExecutionStatus` 값은 `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED` 다. 실행 줄의 `errorCode`, `finishedAt`, `profileName`, `hermesRunId` 를 쓴다.
- application 은 Hermes 상태 문자열을 보지 않는다. 고르는 조건은 우리 상태와 우리 오류 코드뿐이다.

## 의도 메모

- 미리 풀기를 어댑터의 `submit` 안에 두는 안은 기각했다. 어댑터는 지난 실행 줄을 모른다.
- session 을 열쇠로 쓰는 안은 기각했다(위 컨텍스트). 
- 미리 풀기 실패로 제출을 막지 않는다. 막으면 Hermes 가 잠깐 답하지 않을 때 새 turn 까지 실패한다.

## 작업 항목

### 1. steer 정리 순서

- `ChatTurnRunner.settleSteer`: `result != null && !result.pendingSteerKnown()` 이면 중지 확인 여부와 관계없이 `SteerSettlement.unknown()`. 나머지 순서는 그대로다.
- `RecoveredRunRecorder.settle`: 같은 규칙을 `cancelled` 판정 앞에 둔다.

### 2. `usage/execution/application/LostRunRelease.java`(신규)

- 상수: 고를 오류 코드 `HERMES_UNAVAILABLE`, `HERMES_RUN_TIMEOUT`(`ErrorCode` 의 이름)과 `RestartReconciler` 의 `RECONCILE_TIMEOUT`, `RECONCILE_UNREACHABLE` 문자열. 기간 24시간, 최대 10개.
- `void beforeSubmit(HermesRunCommand command)`: 그 profile 의 `FAILED` 실행 가운데 `hermesRunId` 가 있고 `finishedAt` 이 24시간 안이며 `errorCode` 가 위 넷인 것을 `finishedAt` 내림차순 최대 10개 읽는다. 각각 `hermes.releaseIfLost(command.apiBaseUrl(), command.profileName(), runId)`. `releaseIfLost` 가 예외를 던지면 Hermes 가 닿지 않는 것으로 보고 `log.warn` 한 줄을 남긴 뒤 남은 후보를 건너뛴다(제출 전 지연을 조회 한 번으로 묶는다. session 값과 사용자 글은 로그에 넣지 않는다).
- 저장소에 쿼리 하나를 더한다: `findLostCandidates(profileName, status, errorCodes, since, Pageable)`. JPQL 로 쓴다([rule-51](../../../../code-rules/rule-51.md)). 인덱스가 필요한지 `backend/docs/data-schema.md` 의 실행 테이블 인덱스와 대조하고, 필요하면 만들지 말고 계획 결함으로 보고한다.
- `RECONCILE_*` 상수가 `chat.recovery` 에 있으면 `usage` 가 `chat` 을 참조하게 된다. 의존 방향을 지키려고 같은 문자열을 `LostRunRelease` 에 따로 두고 주석으로 원본을 가리킨다. 패키지 배치는 `backend/docs/code-architecture.md` 를 따른다.

### 3. 부르는 자리 셋

`ChatRunEvents.submit`, `AgentRunner`, `RoomContext` 의 `hermes.submit(command)` 바로 앞에서 `lostRunRelease.beforeSubmit(command)` 를 부른다. 생성자에 `LostRunRelease` 를 더하고, 그 생성자를 직접 부르는 테스트를 함께 고친다.

### 4. 테스트

- `backend/src/test/java/com/bifos/assistant/usage/execution/LostRunReleaseTest.java`(신규, H2 저장소): 고를 오류 코드 넷만 넘기고, 다른 오류 코드·24시간 지난 줄·`hermesRunId` 없는 줄·다른 profile 은 넘기지 않으며, 11개 이상이면 10개만 넘긴다. 첫 `releaseIfLost` 가 예외를 던지면 남은 후보를 부르지 않고 제출은 계속된다. `StubHermesRunsClient.releasedRuns()` 로 본다.
- `SteerSettleTest`: `pendingSteerKnown=false` 결과로 끝난 turn(중지 확인된 turn 포함)과 재기동 정리가 「확인 못 함」 으로 정리되는 경우를 더한다.

## 검증

```bash
cd backend && ./gradlew test --tests '*LostRunReleaseTest' --tests '*SteerSettleTest' --tests '*AgentRunnerSubmitFailureTest' --tests '*AgentRunnerConnectorContextTest' --tests '*UserExecutionLimitBackgroundTest' --tests '*RestartReconcile*'
cd backend && ./gradlew archTest checkstyleMain checkstyleTest
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/usage/execution/application/LostRunRelease.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/execution/infra/AgentExecutionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/application/ChatTurnRunner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/application/ChatRunEvents.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/recovery/application/RecoveredRunRecorder.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentRunner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/room/application/RoomContext.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/execution/LostRunReleaseTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/SteerSettleTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/AgentRunnerSubmitFailureTest.java` | 수정 (생성자) |
| `backend/src/test/java/com/bifos/assistant/orchestration/AgentRunnerConnectorContextTest.java` | 수정 (생성자) |
| `backend/src/test/java/com/bifos/assistant/chat/UserExecutionLimitBackgroundTest.java` | 수정 (생성자) |
