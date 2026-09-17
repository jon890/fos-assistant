# Phase 02. 실행을 시작할 때 기록하고 끝날 때 갱신한다

**Execution profile**: deep

## 목표

`agent_execution` 한 줄을 실행이 끝난 뒤가 아니라 시작할 때 `RUNNING` 으로 만들고,
끝나면 같은 줄을 갱신한다.
부모와 뿌리 실행을 가리키는 칸을 함께 둔다.

**범위 외**:
여러 에이전트를 잇는 실행은 plan011 이 만든다. 이 phase 는 칸과 상태 전이만 준비한다.
`execution_event` 는 plan010 이 만든다.
`context_chars` 를 채우는 것은 plan009 가 한다. 칸만 여기서 만든다.

## 컨텍스트

지금 `ExecutionRecorder` 는 `recordSuccess` 와 `recordFailure` 둘뿐이고,
둘 다 실행이 끝나는 자리에서 `executions.save(...)` 로 새 줄을 만든다.
돌고 있는 실행은 어디에도 없다.

실측한 것이 있다. 포지션 추천 하나가 입력 346만 토큰에 15분 넘게 걸렸다.
그 15분 동안 사용량 화면에는 아무것도 보이지 않고,
서버가 그 사이 재시작되면 그 실행은 토큰을 쓰고도 기록이 남지 않는다.

`AgentExecution` 은 지금 Builder 로만 만들어지고 값을 바꾸는 메서드가 없다.
이 phase 가 상태 전이 메서드를 더한다.

**주의할 자리가 있다.**
`AgentExecutionRepository.sumCostBetween` 이
`estimatedCostMicros is null` 인 줄을 「가격을 찾지 못한 실행」 으로 센다.
`RUNNING` 인 줄은 금액이 비어 있으므로 그 수에 섞인다.
`test/e2e/scenarios/usage-cost.ts` 가 `monthly.unpricedExecutions === 0` 을 검사하고 있어,
조건을 더하지 않으면 그 테스트가 깨진다.

**근거 문서**: `docs/adr/ADR-011-실행은-시작할-때-기록하고-끝날-때-갱신한다.md`,
`docs/data-schema.md` 의 「agent_execution」 절, `docs/flow.md` 의 「대화 한 번」 절

## 의도 메모

- 별도의 `running_execution` 표를 두는 안을 버렸다.
  끝날 때 옮겨 적어야 하고, 옮기는 도중에 죽으면 두 곳 어디에도 없는 실행이 생긴다.
- `CANCELLED` 는 상태값과 마이그레이션에만 둔다. 지금은 실행을 취소하는 경로가 없다.
  만들지 않는다. plan011 이 실패한 것을 멈출 때 쓴다.
- `root_execution_id` 를 뿌리 자신에게는 비워 둔다.
  자기 `id` 를 자기에게 적으려면 저장한 뒤 다시 써야 하고, 그만한 값이 없다.
  뿌리를 찾을 때 `root_execution_id` 가 비어 있으면 그 줄이 뿌리다.

## 작업 항목

### 1. `backend/src/main/resources/db/migration/V6__execution_lifecycle.sql` 신규

```sql
ALTER TABLE agent_execution
    ADD COLUMN parent_execution_id BIGINT NULL,
    ADD COLUMN root_execution_id BIGINT NULL,
    ADD COLUMN context_chars BIGINT NULL,
    ADD COLUMN actual_cost_micros BIGINT NULL,
    MODIFY COLUMN finished_at DATETIME(6) NULL,
    MODIFY COLUMN latency_ms BIGINT NULL;

CREATE INDEX idx_agent_execution_root ON agent_execution (root_execution_id);
CREATE INDEX idx_agent_execution_parent ON agent_execution (parent_execution_id);
CREATE INDEX idx_agent_execution_status ON agent_execution (status);
```

`status` 는 이미 `VARCHAR(20)` 이라 `RUNNING` 과 `CANCELLED` 가 그대로 들어간다.

### 2. `usage/domain/ExecutionStatus.java` 에 상태를 더한다

```java
public enum ExecutionStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
```

### 3. `usage/domain/AgentExecution.java` 에 칸과 상태 전이를 더한다

칸을 더한다.

| 필드 | 타입 | 비고 |
| --- | --- | --- |
| `parentExecutionId` | `Long` | |
| `rootExecutionId` | `Long` | |
| `contextChars` | `Long` | plan009 가 채운다 |
| `actualCostMicros` | `Long` | 종량 경로의 실제 청구액. 채우는 쪽은 만들어졌다 |

`finishedAt` 을 `nullable = true` 로, `latencyMs` 를 `long` 에서 `Long` 으로 바꾼다.
`Builder.timing(...)` 이 `finishedAt` 에서 `latencyMs` 를 계산하고 있는데,
시작할 때는 끝난 시각이 없으므로 시작만 받는 방법을 더한다.

```java
public Builder startedAt(Instant startedAt)
```

상태 전이 메서드를 더한다. 이 셋 말고 값을 바꾸는 길을 열지 않는다.

```java
/** 실행을 제출한 직후 Hermes 가 준 run 번호를 적는다. */
public void attachRunId(String hermesRunId)

/** 끝난 시각과 토큰과 금액을 채우고 SUCCEEDED 로 옮긴다. */
public void markSucceeded(
        String provider, String model, TokenUsage usage, EstimatedCost cost, Instant finishedAt)

/** 끝난 시각과 오류 코드를 채우고 FAILED 로 옮긴다. */
public void markFailed(String errorCode, Instant finishedAt)
```

`markSucceeded` 와 `markFailed` 가 `latencyMs` 를 `finishedAt` 과 `startedAt` 의 차로 계산한다.

### 4. `usage/application/ExecutionRecorder.java` 를 시작과 끝으로 나눈다

지금의 `recordSuccess` 와 `recordFailure` 를 아래로 바꾼다.

```java
/** 실행을 RUNNING 으로 만들어 돌려준다. 부모가 없으면 parent 와 root 는 null 이다. */
public AgentExecution start(
        CurrentUser user, Conversation conversation, Agent agent,
        Long parentExecutionId, Long rootExecutionId)

/** 제출 직후 run 번호를 붙인다. */
public void attachRunId(AgentExecution execution, String hermesRunId)

/** 끝난 실행을 SUCCEEDED 로 갱신한다. */
public AgentExecution complete(AgentExecution execution, Agent agent, HermesRunResult result)

/** 끝난 실행을 FAILED 로 갱신한다. */
public AgentExecution fail(AgentExecution execution, String errorCode)
```

`modelOf` 와 `firstNonBlank` 는 그대로 쓴다.
`complete` 에서 `costs.estimate(...)` 를 부르는 것도 지금과 같다.

### 5. `chat/application/ChatService.java` 가 새 자리에서 부른다

- `prepare` 가 `executions.start(...)` 를 불러 `PendingTurn` 에 `AgentExecution` 을 담는다.
  부모가 없으므로 `parentExecutionId` 와 `rootExecutionId` 는 `null` 이다.
- 스트리밍과 한 번에 받는 경로 모두 `submit` 으로 `runId` 를 받은 직후
  `executions.attachRunId(...)` 를 부르고, 그다음 `awaitCompletion` 으로 완료를 기다린다.
  실행 중에도 Hermes 실행과 연결할 수 있도록 제출과 완료 대기를 한 호출로 묶지 않는다.
- `finish` 가 `executions.complete(...)` 를 부른다.
- 실패하는 모든 자리가 `executions.fail(...)` 을 부른다.
  지금 `recordFailure` 를 부르는 네 자리다.

### 6. 사용량 조회에서 `RUNNING` 을 뺀다

`usage/infra/AgentExecutionRepository.java` 다.

- `sumCostBetween` 의 `where` 에 `and e.status <> com.bifos.assistant.usage.domain.ExecutionStatus.RUNNING` 을 더한다.
- `findByUserIdOrderByIdDesc` 는 그대로 둔다. 목록에는 돌고 있는 실행이 보여야 한다.

`usage/presentation/UsageController.java` 의 `ExecutionView.from` 이
`execution.latencyMs()` 를 primitive 로 읽고 있다.
`Long` 으로 바뀌므로 `ExecutionView` 의 `latencyMs` 도 `Long` 으로 바꾼다.

### 7. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/usage/ExecutionLifecycleTest.java` 를 새로 만든다.

- `start` 가 만든 줄이 `RUNNING` 이고 `finishedAt` 과 `latencyMs` 가 비어 있다
- `start` 에 `parentExecutionId` 와 `rootExecutionId` 를 주면 같은 줄에 두 값이 저장된다
- `complete` 뒤에 같은 `id` 의 줄이 `SUCCEEDED` 이고 토큰과 금액이 채워진다.
  **새 줄이 생기지 않는 것을 행 수로 확인한다**
- `fail` 뒤에 같은 줄이 `FAILED` 이고 `errorCode` 가 적힌다
- `attachRunId` 뒤에 `hermesRunId` 가 적힌다

`backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` 에 더한다.

- 대화 한 번이 실행 줄을 **하나만** 만든다
- Hermes 가 실패를 돌려주면 그 줄이 `FAILED` 로 갱신되고 새 줄이 생기지 않는다

`backend/src/test/java/com/bifos/assistant/usage/UsageCostRecordingTest.java` 의
`recordSuccess` 와 `recordFailure` 호출도 새 lifecycle API 로 바꾼다.

`test/e2e/scenarios/usage-cost.ts` 에 더한다.

- `RUNNING` 인 실행이 있어도 `monthly.unpricedExecutions` 가 그것을 세지 않는다
- 현재 가짜 Hermes 는 제출 직후 완료되므로, `test/e2e/fake-hermes.ts` 와 하네스에
  특정 실행을 완료 전 상태로 유지하고 테스트가 해제할 수 있는 제어를 더한다

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
node test/e2e/run.ts
cd web && pnpm typecheck && pnpm build
```

새 줄이 생기지 않는 것을 직접 본다.

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests '*ExecutionLifecycleTest*'
```

마이그레이션과 엔티티가 어긋나지 않는지 확인한다.
`CLAUDE.md` 가 적었듯 테스트는 엔티티로 스키마를 만들고 운영은 Flyway 가 만든 것을 검증하므로,
둘이 어긋나도 테스트는 통과한다. 배포 로그에서 `Schema validation` 을 확인한다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V6__execution_lifecycle.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/ExecutionStatus.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/AgentExecution.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionRecorder.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/infra/AgentExecutionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/presentation/UsageController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/ExecutionLifecycleTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/UsageCostRecordingTest.java` | 수정 |
| `test/e2e/scenarios/usage-cost.ts` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/e2e/harness.ts` | 수정 |
