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
- **기존 타입과 메서드를 지우거나 이름을 바꾸지 않는다. 더하기만 한다.**
  같은 파일을 다른 작업이 동시에 고치고 있어, 이름을 바꾸면 머지할 때 충돌 범위가 커진다.
  `EstimatedCost`, `MonthlyCost`, `sumCostBetween`, 기존 `estimate`, 기존 `markSucceeded` 를 그대로 둔다.

## 작업 항목

### 1. `CostEstimator` 가 둘을 낸다

`usage/application/CostEstimator.java` 다.

지금 `estimate(provider, model, usage)` 가 `EstimatedCost` 하나를 낸다.
**그 메서드를 그대로 두고** `cost_mode` 를 받는 메서드를 하나 더한다.

```java
/**
 * 환산액과 실제 청구액을 함께 낸다.
 *
 * <p>구독 경로는 실제 청구액이 비어 있다. 그 금액이 실제로 빠져나가지 않기 때문이다.
 */
public ExecutionCost estimate(String provider, String model, TokenUsage usage, CostMode costMode);
```

`usage/domain/ExecutionCost.java` 를 새로 만든다.
`EstimatedCost.java` 는 지우지 않고 그대로 둔다.

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

`complete` 에서 `costs.estimate(...)` 를 부르는 자리에 `agent.costMode()` 를 넘기고
새 메서드를 부르도록 바꾼다.

`AgentExecution` 에 `ExecutionCost` 를 받는 `markSucceeded` 오버로드를 하나 더한다.
**기존 5인자 `markSucceeded(provider, model, usage, EstimatedCost, finishedAt)` 는 남긴다.**
`Builder.cost(EstimatedCost)` 도 남기고 `Builder.cost(ExecutionCost)` 를 더한다.

### 3. 조회가 둘을 나눠 합친다

`usage/infra/AgentExecutionRepository.java` 의 `sumCostBetween` 은 환산액만 합친다.
**그 메서드와 `MonthlyCost` 를 그대로 두고** 둘을 함께 합치는 메서드와 record 를 더한다.

```java
/** 환산액과 실제 청구액을 함께 합친다. RUNNING 은 빠진다. */
MonthlyCostDetail sumCostDetailBetween(Long userId, Instant from, Instant to);
```

`usage/domain/MonthlyCostDetail.java` 를 새로 만든다.

```java
public record MonthlyCostDetail(
        Long estimatedMicros,
        Long actualMicros,
        Long pricedExecutions,
        Long unpricedExecutions,
        Long subscriptionExecutions) {
}
```

`subscriptionExecutions` 는 실제 청구액이 없는 실행 수다.
화면이 「이만큼은 구독으로 돌았다」 를 말할 수 있어야 한다.
`pricedExecutions` 와 `unpricedExecutions` 는 기존 `MonthlyCost` 와 같은 뜻이다.
환산액이 비어 있는지로 센다.

`RUNNING` 을 빼는 조건은 plan008 의 phase-02 가 이미 넣었다. 새 메서드도 같은 조건을 쓴다.

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
목록의 그 칸은 **끝난 구독 경로 실행에만** 「구독」 으로 적고 금액을 쓰지 않는다.
`RUNNING` 인 실행은 그 칸을 빈 문자열로 둔다. 끝나지 않은 실행의 금액을 비우는 기존 규칙과 같다.
`test/browser/usage.spec.ts` 가 그 빈 칸을 이미 단언하고 있다.

월 합계 응답의 필드 이름을 아래로 확정한다.
**기존 이름을 바꾸지 않고 둘을 더한다.** `test/e2e/scenarios/usage-cost.ts` 와
`web/src/components/usage/monthly-summary.tsx` 가 기존 이름에 묶여 있다.

| 필드 | 상태 | 뜻 |
| --- | --- | --- |
| `month`, `currency` | 그대로 | |
| `estimatedCostMicros` | 그대로 | 공개 API 가격으로 환산한 합계 |
| `pricedExecutions`, `unpricedExecutions` | 그대로 | |
| `actualCostMicros` | 더한다 | 실제 청구액 합계 |
| `subscriptionExecutions` | 더한다 | 실제 청구액이 비어 있는 실행 수 |

`ExecutionView` 에도 `actualCostMicros` 를 더한다. 기존 필드는 그대로 둔다.

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

- 월 합계 응답이 `actualCostMicros` 와 `subscriptionExecutions` 를 함께 낸다
- **e2e 의 실행은 전부 `DAD_BINDING` 의 `SUBSCRIPTION` 이다.**
  그래서 `actualCostMicros` 가 0 이고 `subscriptionExecutions` 가 실행 수와 같은 것을 단언한다.
  `API` 경로가 실제로 금액을 채우는 것은 위 `UsageCostRecordingTest` 가 본다.
  e2e 에 `API` 경로 바인딩을 새로 만들지 않는다. 이 phase 의 범위 밖이다.

`test/browser/usage.spec.ts` 를 고친다.

- 46번째 줄 근처의 `이번 달 환산 합계` 단언을 새 화면 문구로 바꾼다
- `RUNNING` 실행의 금액 칸이 빈 문자열인 단언은 그대로 둔다

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
cd web && pnpm typecheck
cd web && pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
scripts/check-public-safe.sh
```

`AGENTS.md` 의 「확인」 절이 정한 순서다. 그 순서대로 돌린다.
`pnpm build` 가 요구하는 자리표시자 환경 변수는 `web/AGENTS.md` 에 있다.

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
| `backend/src/main/java/com/bifos/assistant/usage/domain/ExecutionCost.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/MonthlyCostDetail.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/AgentExecution.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionRecorder.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/infra/AgentExecutionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/presentation/UsageController.java` | 수정 |
| `web/src/components/usage/monthly-summary.tsx` | 수정 |
| `web/src/components/usage/execution-list.tsx` | 수정 |
| `web/src/components/usage/execution-table.tsx` | 수정 |
| `web/src/components/usage/execution-card.tsx` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/CostEstimatorTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/UsageCostRecordingTest.java` | 수정 |
| `test/e2e/scenarios/usage-cost.ts` | 수정 |
| `test/browser/usage.spec.ts` | 수정 |
