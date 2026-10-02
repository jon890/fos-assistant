# Phase 01. 자식 사용량을 재조회 작업 줄에 원장으로 적는다

**Execution profile**: deep

## 목표

Hermes `delegate_task` 가 만든 native 자식의 provider, 모델, 토큰, 환산 금액을 `subagent_usage_job` 한 줄에 한 번만 적는다.
합계에 더할 값이 한 자리에 있어야 다음 phase 가 중복 없이 더할 수 있다.

**범위 외**: 합계 질의와 API 응답(phase 02), 화면과 e2e(phase 03). 자식의 provider 를 Hermes 에서 읽는 새 경로(이슈 #110).

## 컨텍스트

- 재조회는 `backend/src/main/java/com/bifos/assistant/usage/application/SubagentUsageReconciler.java` 가 한다.
  `reconcile()` 이 5초마다 `discover(now)` 로 작업 줄을 만들고 `poll(jobId)` 로 session 을 조회한다. 조회 간격, 동시 조회 4개, 발견 batch 20개는 바꾸지 않는다
- 작업 줄 엔티티는 `backend/src/main/java/com/bifos/assistant/usage/domain/SubagentUsageJob.java` 다. 상태는 문자열 `WAITING`, `DONE`, `EXPIRED` 이고 `(execution_id, child_session_id)` 가 유일하다
- 지금 `discover` 는 완료 사건이 이미 있는 자식을 건너뛰고(`ExecutionEventRepository.findUnscheduledChildren` 의 `not exists ... 'SUBAGENT_COMPLETED'` 절), `poll` 은 완료 사건이 있으면 조회 없이 `done()` 한다. 이번에 둘 다 바꾼다
- session 조회는 `backend/src/main/java/com/bifos/assistant/hermes/HttpHermesRunsClient.java` 의 `readSubagentUsage` 가 하고 `backend/src/main/java/com/bifos/assistant/hermes/dto/SubagentSessionUsage.java` 를 돌려준다. 지금 provider 를 읽지 않는다. 같은 파일의 `readSessionRuntime` 이 `provider`, 없으면 `billing_provider` 를 읽는 선례다
- 환산은 `backend/src/main/java/com/bifos/assistant/usage/application/CostEstimator.java` 의 `estimate(String provider, String model, TokenUsage usage, CostMode costMode)` 가 한다. `TokenUsage(inputTokens, cachedInputTokens, outputTokens, totalTokens)` 의 입력은 cache 를 포함한 값이고, cache 토큰은 cache read 단가로 센다. provider 가 null 이거나 가격표에 없으면 `ExecutionCost.unknown()` 을 돌려준다
- 운영 스키마는 Flyway 가 만들고 테스트 스키마는 엔티티가 만든다. 칸의 타입과 길이를 마이그레이션과 엔티티에서 글자까지 맞춘다. 마이그레이션은 `backend/src/test/java/com/bifos/assistant/chat/DelegationWakeMigrationTest.java` 처럼 H2 `MODE=MySQL` 에서 Flyway 를 돌려 따로 검사한다
- `agent` 표의 칸: `hermes_profile`, `api_base_url`(VARCHAR(255)), `deleted_at`. `agent_execution` 의 칸: `agent_id`, `profile_name`, `hermes_session_id`, `finished_at`, `cost_mode`
- 하위 에이전트는 orca 명령을 쓰지 않는다

**근거 문서**: `docs/adr/ADR-059-native-하위-에이전트-사용량은-재조회-작업-줄을-원장으로-넓혀-합계에-더한다.md`, `docs/model-tiers.md` 의 「비동기 자식 사용량」 과 그 아래 「원장 줄에 적는 것」, `docs/backend/schema/execution.md` 의 「subagent_usage_job」, `docs/hermes/delegation.md` 의 「자식 session 으로 결과와 토큰을 보완한다」, `backend/AGENTS.md`

## 의도 메모

- native 자식마다 `agent_execution` 줄을 만들지 않는다. `execution_event` 에 금액 칸을 두지도 않는다. 까닭은 ADR-059 에 있다
- provider 를 부모 실행이나 모델 이름에서 추정하지 않는다. session 응답에 없으면 비운다
- 완료 사건(`SUBAGENT_COMPLETED`)은 표시용으로 그대로 둔다. 사건이 없을 때만 새로 만드는 지금 동작을 유지한다
- 표와 클래스 이름을 바꾸지 않는다. 같은 저장소에서 다른 작업이 `usage` 패키지를 함께 고친다
- 마이그레이션 번호는 `origin/main` 의 최신 다음 번호다. 작업을 시작할 때 `git fetch origin` 뒤 `git ls-tree --name-only origin/main backend/src/main/resources/db/migration/` 로 V55 가 비어 있는지 다시 본다. 이미 쓰였으면 다음 번호로 옮기고 테스트 이름의 번호도 맞춘다

## 작업 항목

### 1. `backend/src/main/resources/db/migration/V55__subagent_usage_ledger.sql`

`subagent_usage_job` 에 칸을 더한다. 모두 NULL 허용이다.

| 칸 | 타입 |
| --- | --- |
| `provider` | VARCHAR(64) |
| `model` | VARCHAR(128) |
| `input_tokens`, `cache_read_tokens`, `cache_write_tokens`, `output_tokens` | BIGINT |
| `estimated_cost_micros`, `actual_cost_micros` | BIGINT |
| `cost_currency` | CHAR(3) |
| `pricing_version` | VARCHAR(32) |
| `recorded_at` | DATETIME(6) |

같은 파일에서 지난 자식을 다시 조회 대기로 넣는다.

1. `status = 'DONE'` 인 줄을 `status = 'WAITING'`, `next_attempt_at = created_at`, `expires_at = TIMESTAMPADD(HOUR, 24, CURRENT_TIMESTAMP(6))`, `attempts = 0`, `backoff_attempts = 0` 으로 바꾼다. 칸을 더한 뒤이므로 이 줄들은 사용량 칸이 비어 있다
2. `execution_event` 의 `event_type = 'SUBAGENT_STARTED'` 이고 `hermes_session_id IS NOT NULL` 이며 부모 `agent_execution.finished_at IS NOT NULL` 인 자식 중, 같은 `profile_name` 과 `child_session_id` 의 작업 줄이 없는 것을 넣는다. 한 `(execution_id, hermes_session_id)` 에 한 줄만 넣는다(시작 사건이 중복돼도). 같은 profile 의 같은 session 이 여러 실행에 있으면 `execution_id` 가 가장 작은 것만 넣는다
   - `parent_session_id` 는 부모의 `hermes_session_id`, `profile_name` 은 부모의 `profile_name`, `created_at` 과 `next_attempt_at` 은 그 시작 사건의 가장 이른 `occurred_at`, `expires_at` 은 위와 같은 식이다
   - 부모의 에이전트가 없거나 `deleted_at IS NOT NULL` 이면 `status = 'EXPIRED'`, `unconfirmed_reason = 'AGENT_MISSING'`, `api_base_url = ''` 이다
   - 에이전트의 `hermes_profile` 이 부모의 `profile_name` 과 다르면 `status = 'EXPIRED'`, `unconfirmed_reason = 'PROFILE_CHANGED'` 다
   - 그 밖에는 `status = 'WAITING'` 이고 `api_base_url` 은 에이전트의 값이다

문은 MySQL 과 H2 `MODE=MySQL` 에서 모두 돌아야 한다. `backend/src/main/resources/db/migration/V44__subagent_usage_jobs.sql` 의 파생 표 쓰는 방식을 선례로 삼는다.

### 2. `SubagentUsageJob`

위 칸을 필드로 더한다. `@Column` 의 이름과 길이를 마이그레이션과 맞춘다(`cost_currency` 는 `columnDefinition = "CHAR(3)"` 처럼 `AgentExecution` 의 같은 칸을 따른다).

메서드를 더한다.

```java
public void record(SubagentSessionUsage usage, ExecutionCost cost, String reason, Instant now)
```

- `provider`(공백이면 null), `model`, `inputTokens`, `cacheReadTokens`, `cacheWriteTokens`, `outputTokens` 를 usage 에서 옮긴다
- `estimatedCostMicros`, `actualCostMicros`, `costCurrency`, `pricingVersion` 을 cost 에서 옮긴다
- `recordedAt = now`, `status = "DONE"`, `unconfirmedReason = reason` 이다

`done()` 은 쓰는 곳이 없어지면 지운다.

### 3. `SubagentSessionUsage` 와 `HttpHermesRunsClient.readSubagentUsage`

- record 에 `String provider` 를 `model` 다음 칸으로 더한다. 기존 10칸 생성자는 provider 를 null 로 넘기는 보조 생성자로 남긴다. 기존 테스트 호출을 그대로 둔다
- `readSubagentUsage` 가 중첩 `session` 의 `provider` 를 읽고, 없으면 `billing_provider` 를 읽는다. 둘 다 없으면 null 이다

### 4. `ExecutionEventRepository.findUnscheduledChildren` 과 `SubagentUsageJobRepository`

- `findUnscheduledChildren` 에서 `'SUBAGENT_COMPLETED'` 가 있는 자식을 빼는 `not exists` 절을 없앤다. 완료 사건이 있는 자식도 작업 줄을 만든다
- 같은 질의의 작업 줄 `not exists` 를 실행이 아니라 profile 로 견준다: `job.profileName = parent.profileName and job.childSessionId = event.hermesSessionId`. 같은 profile 의 같은 자식 session 줄이 다른 실행 아래 있어도 새 줄을 만들지 않는다
- `SubagentUsageJobRepository` 에 `boolean existsByProfileNameAndChildSessionId(String profileName, String childSessionId)` 를 더하고, `discover` 의 `existsByExecutionIdAndChildSessionId` 호출을 이것으로 바꾼다. 쓰는 곳이 없어진 메서드는 지운다

### 5. `SubagentUsageReconciler.poll`

`CostEstimator` 를 생성자 둘 모두에 주입한다. `poll` 의 트랜잭션 안 판정을 아래 순서로 바꾼다.

1. `isFinalChild(current, usage)` 이면
   - 그 자식의 `SUBAGENT_COMPLETED` 사건이 없을 때만 지금처럼 사건을 저장한다
   - 금액을 정한다

     | 조건 | 금액 | `unconfirmedReason` |
     | --- | --- | --- |
     | `usage.provider()` 가 null 이거나 공백 | `ExecutionCost.unknown()` | `PROVIDER_UNKNOWN` |
     | `usage.inclusiveInputTokens()` 나 `usage.outputTokens()` 가 null | `ExecutionCost.unknown()` | `USAGE_UNKNOWN` |
     | 환산 결과가 `isKnown()` 이 아님 | `ExecutionCost.unknown()` | `PRICE_UNKNOWN` |
     | 그 밖 | 환산 결과 | null |

     환산은 `estimator.estimate(usage.provider(), usage.model(), new TokenUsage(usage.inclusiveInputTokens(), usage.cacheReadTokens(), usage.outputTokens(), null), parent.costMode())` 다
   - `current.record(usage, cost, reason, now)` 를 부른다
2. 아니고 `current.expired(now)` 이면 `current.expire()`
3. 그 밖에는 `current.retry(now)`

완료 사건이 있다는 이유로 조회 없이 끝내던 분기를 없앤다. 클래스 Javadoc 에 이 줄이 원장이라는 것과 ADR-059 를 적는다.

### 6. 이 phase 를 검증하는 테스트

- `backend/src/test/java/com/bifos/assistant/usage/SubagentUsageLedgerMigrationTest.java`(신규): Flyway 를 `target("54")` 까지 돌린 H2 에 부모 실행, 에이전트, 시작 사건, 작업 줄을 넣고 끝까지 올린다
  - `DONE` 이던 줄이 `WAITING` 이 되고 `next_attempt_at` 이 `created_at` 과 같다
  - 작업 줄이 없던 자식(시작 사건이 둘 중복)이 `WAITING` 한 줄로 들어온다
  - 에이전트가 지워진 부모의 자식은 `EXPIRED` 와 `AGENT_MISSING` 이다
  - `WAITING` 과 `EXPIRED` 이던 줄은 그대로다
  - 새 칸이 모두 있다
- `backend/src/test/java/com/bifos/assistant/usage/application/SubagentUsageReconcilerTest.java`: `fixtures()` 에 `CostEstimator` 를 넣는다(가짜 `PriceCatalog` 로 만든 실제 `CostEstimator` 나 mock). 아래를 더하고, 「완료 사건이 있으면 조회 없이 끝낸다」 는 기존 검사는 새 동작에 맞게 고친다
  - provider 와 가격이 있는 종료 자식: 줄이 `DONE`, 토큰 넷과 금액과 가격표 버전이 적히고 `unconfirmedReason` 이 null. 부모가 `SUBSCRIPTION` 이면 `actualCostMicros` 가 null, `API` 면 환산액과 같다
  - provider 가 없는 종료 자식: `DONE`, 금액 null, `PROVIDER_UNKNOWN`. 부모의 provider 가 채워지지 않는다
  - 가격표에 없는 모델: `DONE`, 금액 null, `PRICE_UNKNOWN`
  - 완료 사건이 이미 있는 자식: 사건을 새로 저장하지 않고 줄에는 사용량을 적는다
  - 같은 줄을 `poll` 로 두 번 부르면 두 번째는 아무것도 바꾸지 않는다
- `backend/src/test/java/com/bifos/assistant/usage/SubagentUsageJobTest.java`: `record` 가 칸을 옮기고 `DONE` 으로 바꾸는지 본다
- `backend/src/test/java/com/bifos/assistant/hermes/HermesRuntimeReadTest.java`: 자식 session 응답의 `billing_provider` 를 읽고, 없으면 provider 가 null 인지 본다

## 검증

```bash
cd backend && ./gradlew test --tests '*SubagentUsageLedgerMigrationTest' --tests '*SubagentUsageReconcilerTest' --tests '*SubagentUsageJobTest' --tests '*HermesRuntimeReadTest'
cd backend && ./gradlew test
scripts/quality.sh check
scripts/check-public-safe.sh
```

모두 종료 코드 0 이다. `./gradlew test` 는 `backend/src/test` 전체를 돌려 위 네 테스트를 포함한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V55__subagent_usage_ledger.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/SubagentUsageJob.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/dto/SubagentSessionUsage.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HttpHermesRunsClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/infra/ExecutionEventRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/infra/SubagentUsageJobRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/SubagentUsageReconciler.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/SubagentUsageLedgerMigrationTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/usage/application/SubagentUsageReconcilerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/SubagentUsageJobTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/HermesRuntimeReadTest.java` | 수정 |
