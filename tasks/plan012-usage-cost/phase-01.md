# Phase 01. 실제 청구액과 환산액을 나눈다

**Execution profile**: standard

## 목표

금액 칸을 둘로 나눠 공개 API 가격으로 환산한 값과 실제로 청구되는 값을 따로 적는다.
화면이 둘을 구분해 보인다.

**범위 외**:
실행 당시 상태를 남기는 것은 phase-02 가 한다.
축별 분석 화면은 phase-03 이 만든다.
provider 의 청구 자료를 읽어 오는 경로는 만들지 않는다.

## 컨텍스트

plan008 의 phase-02 가 `actual_cost_micros` 칸을 이미 만들어 두었다.
이 phase 는 그 칸을 채우고 쓰는 쪽을 만든다.

지금은 `estimated_cost_micros` 하나뿐이라
환산한 값인지 실제로 빠져나가는 값인지 화면에서 구분되지 않는다.

실측이 그 차이를 크게 만들었다.
포지션 추천 한 번이 입력 3,460,816 토큰에 환산 14.15달러였다.
그 실행은 `openai-codex` 구독 경로로 돌아 실제 청구는 그 금액이 아니다.
같은 실행을 `openrouter` 로 옮기면 그 금액이 실제로 빠져나간다.

| 모델 | 입력 100만당 | 출력 100만당 | 어디서 |
| --- | --- | --- | --- |
| `gpt-5.6-sol` | 4달러 | 20달러 | `openai-codex`. 지금 쓰는 것 |
| `gpt-6-astra` | 10달러 | 50달러 | `openrouter` |

`agent` 표에 `cost_mode` 가 이미 있다. `SUBSCRIPTION` 과 `API` 다.

**근거 문서**: `docs/adr/ADR-014-실제-청구액과-환산액을-나눠-적는다.md`,
`docs/adr/ADR-004-구독제에서도-api-가격으로-환산해-보인다.md`,
`docs/data-schema.md` 의 「agent_execution」 절

## 의도 메모

- 구독 경로에서 실제 청구액을 0 으로 채우지 않는다. 비워 둔다.
  0 은 공짜라는 뜻으로 읽힌다. 기존 규칙이 그렇다.
- `estimated_cost_micros` 의 뜻을 바꾸지 않는다.
  그 칸은 계속 「공개 API 가격으로 환산하면 얼마인가」 다.
  이미 쌓인 기록의 뜻이 바뀌면 지난달 합계를 다시 읽을 수 없다.
- 실제 청구액도 지금은 공개 가격으로 계산한 값이다.
  provider 의 청구 자료를 읽는 경로가 없기 때문이다. 그 사실을 화면에 적는다.

## 작업 항목

### 1. `CostEstimator` 가 둘을 낸다

`usage/application/CostEstimator.java` 다.

지금 `estimate(provider, model, usage)` 가 `EstimatedCost` 하나를 낸다.
`cost_mode` 를 받아 둘을 내도록 바꾼다.

```java
/**
 * 환산액과 실제 청구액을 함께 낸다.
 *
 * <p>구독 경로는 실제 청구액이 비어 있다. 그 금액이 실제로 빠져나가지 않기 때문이다.
 */
public ExecutionCost estimate(String provider, String model, TokenUsage usage, CostMode costMode);
```

```java
public record ExecutionCost(
        Long estimatedMicros,
        Long actualMicros,
        String currency,
        String pricingVersion) {

    public static ExecutionCost unknown();
}
```

채우는 규칙이다.

| `cost_mode` | `estimated_cost_micros` | `actual_cost_micros` |
| --- | --- | --- |
| `SUBSCRIPTION` | 공개 가격으로 환산 | 비움 |
| `API` | 공개 가격으로 환산 | 같은 값 |

가격을 찾지 못하면 둘 다 비운다.

### 2. `ExecutionRecorder` 가 둘을 적는다

`complete` 에서 `costs.estimate(...)` 를 부르는 자리에 `agent.costMode()` 를 넘긴다.
`AgentExecution.markSucceeded` 가 `actualCostMicros` 도 받도록 바꾼다.

### 3. 조회가 둘을 나눠 합친다

`usage/infra/AgentExecutionRepository.java` 의 `sumCostBetween` 이 지금 환산액만 합친다.
둘을 함께 합치도록 바꾼다.

```java
public record MonthlyCost(
        Long estimatedMicros,
        Long actualMicros,
        Long pricedExecutions,
        Long unpricedExecutions,
        Long subscriptionExecutions) {
}
```

`subscriptionExecutions` 는 실제 청구액이 없는 실행 수다.
화면이 「이만큼은 구독으로 돌았다」 를 말할 수 있어야 한다.

`RUNNING` 을 빼는 조건은 plan008 의 phase-02 가 이미 넣었다. 그대로 둔다.

### 4. 화면이 둘을 구분해 보인다

`/usage` 의 월 합계 자리다.

```
이번 달
  실제로 나간 돈    $0.00
  API 로 돌렸다면   $14.15

  실행 12건 중 12건이 구독 경로다.
  두 금액 모두 공개 가격표로 계산한 것이고 청구서를 읽은 것이 아니다.
```

**실제 청구액이 0 일 때 그 자리를 비우지 않고 0 으로 보인다.**
구독 경로에서 실제로 추가로 나간 돈이 없다는 것은 사실이다.
칸이 비어 있는 것과 금액이 0 인 것은 다르다.

다만 실행 하나하나의 `actual_cost_micros` 는 비어 있다.
목록의 그 칸은 「구독」 으로 적고 금액을 쓰지 않는다.

### 5. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/usage/CostEstimatorTest.java` 를 고친다.

- **정상 경로**: `API` 경로는 환산액과 실제 청구액이 같다
- **구독 경로**: 환산액은 있고 실제 청구액은 비어 있다
- **이 phase 가 다루는 실패**: 가격표에 없는 모델은 둘 다 비어 있다.
  0 으로 채워지지 않는다

`backend/src/test/java/com/bifos/assistant/usage/UsageCostRecordingTest.java` 에 더한다.

- 구독 경로 실행 둘과 API 경로 실행 하나가 있을 때
  월 합계의 `actualMicros` 가 API 경로 하나의 금액과 같다
- `subscriptionExecutions` 가 2 다

`test/e2e/scenarios/usage-cost.ts` 에 더한다.

- 월 합계 응답이 두 금액을 모두 낸다

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
node test/e2e/run.ts
cd web && pnpm typecheck && pnpm build
cd web && pnpm test:browser
```

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests '*CostEstimatorTest*'
```

이미 쌓인 기록의 `estimated_cost_micros` 가 그대로인지 확인한다.
마이그레이션이 그 값을 건드리지 않아야 한다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/usage/application/CostEstimator.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/EstimatedCost.java` | 수정 또는 대체 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/MonthlyCost.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/AgentExecution.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionRecorder.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/infra/AgentExecutionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/presentation/UsageController.java` | 수정 |
| `web/src/components/usage/monthly-summary.tsx` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/CostEstimatorTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/UsageCostRecordingTest.java` | 수정 |
| `test/e2e/scenarios/usage-cost.ts` | 수정 |
