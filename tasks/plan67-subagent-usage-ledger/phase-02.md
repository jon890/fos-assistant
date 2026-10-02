# Phase 02. 월 합계와 축별 합계에 자식 원장을 더하고 완전성 건수를 낸다

**Execution profile**: deep

## 목표

`GET /api/v1/usage/monthly-cost` 와 `GET /api/v1/usage/breakdown` 이 native 자식의 금액과 토큰을 포함하고,
금액을 확인하지 못한 자식 수를 함께 돌려준다. 합계가 실제보다 작을 때 그 사실이 응답에 드러나야 한다.

**범위 외**: 화면과 e2e(phase 03). 실행 목록과 실행 나무 응답은 바꾸지 않는다.

## 컨텍스트

- phase 01 이 `subagent_usage_job` 줄에 `provider`, `model`, `inputTokens`, `cacheReadTokens`, `cacheWriteTokens`, `outputTokens`, `estimatedCostMicros`, `actualCostMicros`, `status`, `unconfirmedReason` 을 채운다. 금액이 있는 `DONE` 줄만 합계에 더한다
- 지금 합계는 `backend/src/main/java/com/bifos/assistant/usage/presentation/UsageController.java` 가 `AgentExecutionRepository` 의 `sumCostDetailBetween`, `sumByAgentBetween`, `sumByModelBetween`, `sumByDayBetween`, `sumByFingerprintBetween` 을 직접 불러 만든다. 모두 `e.userId`, `e.startedAt >= :from and e.startedAt < :to`, `e.status <> RUNNING` 으로 거른다. `RUNNING` 을 빼는 규칙은 `docs/backend/packages.md` 의 「한 번의 대화가 지나는 길」 끝 문단에 있다
- 응답 모양은 `backend/src/main/java/com/bifos/assistant/usage/presentation/UsageDtos.java` 의 `MonthlyCostView` 와 `BreakdownRow` 다. `BreakdownRow.of(CostByAgent)` 등 넷이 축마다 `key`, `label`, `detail` 을 만든다
- 축 레코드는 `usage/domain` 의 `CostByAgent(agentId, agentCode, agentName, executions, estimatedMicros, actualMicros, inputTokens, outputTokens, avgContextChars, firstSeenAt, lastSeenAt)`, `CostByModel(provider, model, ...)`, `CostByDay(day, ...)`, `CostByFingerprint(fingerprint, ...)` 다. JPQL `select new` 가 쓰므로 칸을 더하지 않는다
- 날짜 축은 `Asia/Seoul` 달력으로 묶는다. 그 시간대는 `UsageController.HOUSEHOLD_ZONE` 이 갖는다
- `application` 은 `presentation` 을 부르지 않는다. 구조 규칙은 `./gradlew archTest` 가 검사한다(`backend/AGENTS.md`)
- 하위 에이전트는 orca 명령을 쓰지 않는다

**근거 문서**: `docs/model-tiers.md` 의 「합계와 완전성」, `docs/adr/ADR-059-native-하위-에이전트-사용량은-재조회-작업-줄을-원장으로-넓혀-합계에-더한다.md`, `docs/backend/packages.md`, `backend/AGENTS.md`

## 의도 메모

- 자식 합계를 DB 에서 축마다 따로 내지 않는다. 한 달치 자식 줄을 한 번 읽어 Java 에서 네 축과 월 합계에 나눠 더한다. 자식 줄은 실행 줄보다 훨씬 적고, 「어느 자식을 세는가」 의 규칙이 질의 하나에 남는다
- 자식은 실행 건수(`executions`, `pricedExecutions`, `unpricedExecutions`, `subscriptionExecutions`)에 세지 않는다
- `agent_delegate` 로 만든 자식은 자기 `agent_execution` 줄로 이미 합계에 들어 있다. 여기서 다시 더하지 않는다

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/usage/domain/SubagentLedgerRow.java`(신규)

한 달치 자식 줄을 읽는 projection record 다.

```java
public record SubagentLedgerRow(
        Long agentId, Instant parentStartedAt, String runtimeFingerprint, String status,
        String provider, String model, Long inputTokens, Long cacheReadTokens, Long cacheWriteTokens,
        Long outputTokens, Long estimatedCostMicros, Long actualCostMicros)
```

- `boolean recorded()`: `status` 가 `DONE`
- `boolean priced()`: `recorded()` 이고 `estimatedCostMicros` 가 null 이 아님
- `Long inclusiveInputTokens()`: 셋 중 null 이 아닌 것을 합친다. 셋 다 null 이면 null

### 2. 저장소 질의

- `SubagentUsageJobRepository.findLedgerRows(Long userId, Instant from, Instant to)`: `SubagentUsageJob j` 와 `AgentExecution e` 를 `e.id = j.executionId` 로 잇고 `e.userId = :userId and e.startedAt >= :from and e.startedAt < :to and e.status <> RUNNING` 으로 거른다. `select new ...SubagentLedgerRow(e.agentId, e.startedAt, e.runtimeFingerprint, j.status, j.provider, j.model, j.inputTokens, j.cacheReadTokens, j.cacheWriteTokens, j.outputTokens, j.estimatedCostMicros, j.actualCostMicros)`
- `ExecutionEventRepository.countSessionlessChildren(userId, from, to)`: 같은 실행 조건에서 `eventType = 'SUBAGENT_STARTED' and hermesSessionId is null` 인 사건 수
- `ExecutionEventRepository.countUnscheduledChildren(userId, from, to)`: 같은 실행 조건에서 `eventType = 'SUBAGENT_STARTED' and hermesSessionId is not null` 이고 같은 `profileName` 과 session 의 작업 줄이 없는 자식 수. `count(distinct event.hermesSessionId)` 로 중복 시작 사건을 한 번만 센다

### 3. `usage/application` 의 합계 서비스(신규 셋)

- `backend/src/main/java/com/bifos/assistant/usage/application/MonthlyUsageSummary.java`:
  `record MonthlyUsageSummary(long estimatedMicros, long actualMicros, long pricedExecutions, long unpricedExecutions, long subscriptionExecutions, long pricedSubagents, long pendingSubagents, long unconfirmedSubagents, long unpricedSubagents)`
- `backend/src/main/java/com/bifos/assistant/usage/application/BreakdownLine.java`: `record BreakdownLine<T>(T cost, long subagents)`. `cost` 는 자식 합이 더해진 축 레코드다
- `backend/src/main/java/com/bifos/assistant/usage/application/UsageSummaryService.java`(`@Service`, 읽기 전용 트랜잭션):
  - `MonthlyUsageSummary monthly(Long userId, Instant from, Instant to)`
    - 실행 쪽은 `sumCostDetailBetween` 그대로다
    - `estimatedMicros`, `actualMicros` 에 `priced()` 줄의 금액을 더한다(null 은 0)
    - `pricedSubagents` 는 `priced()` 줄 수, `unpricedSubagents` 는 `recorded()` 이고 `priced()` 가 아닌 줄 수
    - `pendingSubagents` 는 `WAITING` 줄 수에 `countUnscheduledChildren` 을 더한 값
    - `unconfirmedSubagents` 는 `EXPIRED` 줄 수에 `countSessionlessChildren` 을 더한 값
  - `List<BreakdownLine<CostByAgent>> byAgent(...)`, `List<BreakdownLine<CostByModel>> byModel(...)`, `List<BreakdownLine<CostByDay>> byDay(..., ZoneId zone)`, `List<BreakdownLine<CostByFingerprint>> byFingerprint(...)`
    - `recorded()` 줄만 쓴다. 토큰은 `inclusiveInputTokens()` 와 `outputTokens` 를 더하고, 금액은 null 이 아닌 것만 더한다. 축 줄의 금액이 null 이고 더할 금액도 없으면 null 로 둔다(0 으로 바꾸지 않는다)
    - 에이전트 축은 `agentId`, 날짜 축은 `parentStartedAt` 을 `zone` 의 날짜로, 지문 축은 `runtimeFingerprint`(null 이면 건너뜀)로 기존 줄에 더한다. 부모 실행이 같은 달에 있으므로 줄이 이미 있다. 없으면 건너뛴다
    - 모델 축은 `(provider, model)` 이 같은 줄에 더한다. 없으면 `executions = 0`, `avgContextChars = null`, `firstSeenAt` 과 `lastSeenAt` 이 그 자식들의 `parentStartedAt` 최소와 최대인 새 줄을 만든다
    - `subagents` 는 그 줄에 더한 자식 수다
    - 에이전트 축과 모델 축은 자식을 더한 뒤 환산액 내림차순(null 은 0)으로 다시 정렬한다. 같은 금액의 순서는 기존 질의의 둘째 기준(에이전트 축은 `agentId`, 모델 축은 `model`)을 따른다. 날짜 축과 지문 축의 순서는 그대로 둔다

### 4. `UsageController` 와 `UsageDtos`

- `MonthlyCostView` 끝에 `Long pricedSubagents`, `Long pendingSubagents`, `Long unconfirmedSubagents`, `Long unpricedSubagents` 를 더한다. Javadoc 에 뜻을 적는다. `estimatedCostMicros` 와 `actualCostMicros` 는 자식을 포함한 값이다
- `BreakdownRow` 의 `executions` 다음에 `Long subagents` 를 더한다. `of(...)` 넷이 `BreakdownLine` 을 받아 `subagents` 를 채운다
- `thisMonthCost()` 와 `rows(...)` 가 `UsageSummaryService` 를 부른다. 컨트롤러는 달의 경계와 `HOUSEHOLD_ZONE` 만 넘긴다
- `AgentExecutionRepository` 의 `sumCostBetween`, `sumByAgentBetween` Javadoc 중 「실행 줄을 전부 센다 ... 두 번 세어지지 않는다」 에 native 자식은 `UsageSummaryService` 가 원장 줄로 더한다는 한 줄을 더한다. 질의는 바꾸지 않는다

### 5. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/usage/application/SubagentUsageLedgerTest.java`(신규)

`backend/src/test/java/com/bifos/assistant/usage/UsageCostRecordingTest.java` 처럼 `@SpringBootTest` 에 표본 가격표(`/pricing/models-dev-sample.json`)를 물리고 `HermesRunsClient` 를 `@MockitoBean` 으로 둔다. 실제 저장소와 실제 `SubagentUsageReconciler` 빈의 `discover(now)`, `poll(jobId)` 를 부른다(같은 패키지라 부를 수 있다). 테스트 profile 은 주기 실행을 끈다(`assistant.usage.reconcile-cron: "-"`).

아래를 각각 검사한다. 금액 기대값은 표본 가격표의 단가로 손으로 계산해 상수로 적는다.

| 경우 | 기대 |
| --- | --- |
| 부모와 자식이 다른 provider 와 모델로 돌았다 | 월 합계가 부모 금액과 자식 금액의 합이다. 모델 축에 자식의 `(provider, model)` 줄이 `executions = 0`, `subagents = 1` 로 따로 있다. 에이전트 축과 날짜 축의 줄은 부모 줄에 자식 금액과 토큰이 더해지고 `subagents = 1` 이다 |
| 자식 cache read 가 있다 | 자식 금액이 cache read 단가로 환산된다(일반 입력 단가를 일괄 적용한 값과 다르다) |
| 지연 완료: 첫 조회는 `endedAt` 이 null, 둘째 조회에서 끝났다 | 첫 조회 뒤 `pendingSubagents = 1` 이고 합계는 부모만이다. 둘째 뒤 `pricedSubagents = 1`, `pendingSubagents = 0` 이다 |
| 재기동: `WAITING` 줄이 저장된 채 `discover` 를 다시 부르고 `poll` 한다 | 줄이 하나뿐이고 합계에 한 번만 더해진다 |
| 재조회: 끝난 줄에 `poll` 을 다시 부른다 | 합계가 그대로다 |
| 중복 사건: 시작 사건 둘, SSE 완료 사건 하나가 있고 session 조회도 끝났다 | 줄 하나, 완료 사건 하나, 합계에 한 번 |
| provider 미확인 | `unpricedSubagents = 1`, 금액은 부모만. 줄의 provider 가 null 이다 |
| 기한 만료(부모 `finishedAt` 이 25시간 전) | `unconfirmedSubagents = 1` |
| session 없는 시작 사건 | `unconfirmedSubagents = 1` |
| 부모가 `RUNNING` | 그 자식은 어느 건수에도 없다 |
| `agent_delegate` 자식(부모를 가리키는 `agent_execution` 줄)과 native 자식이 함께 있다 | 합계가 부모, FOS 자식, native 자식 셋의 합이다. `pricedExecutions = 2`, `pricedSubagents = 1` |
| 다른 사용자의 자식 | 내 합계와 건수에 없다 |

## 검증

```bash
cd backend && ./gradlew test --tests '*SubagentUsageLedgerTest' --tests '*UsageCostRecordingTest'
cd backend && ./gradlew test
scripts/quality.sh check
scripts/check-public-safe.sh
```

모두 종료 코드 0 이다. `./gradlew test` 는 구조 규칙 검사를 포함한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/usage/domain/SubagentLedgerRow.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/application/MonthlyUsageSummary.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/application/BreakdownLine.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/application/UsageSummaryService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/infra/SubagentUsageJobRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/infra/ExecutionEventRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/infra/AgentExecutionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/presentation/UsageController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/presentation/UsageDtos.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/application/SubagentUsageLedgerTest.java` | 신규 |
