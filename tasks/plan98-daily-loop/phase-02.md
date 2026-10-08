# Phase 02. 매일 루프 이음매와 기동 복구, export

**Execution profile**: deep

## 목표

매일 깨우기 살펴보기가 끝나 점검 대화 잠금을 푼 뒤, 동의한 사용자에게만 `ValueEvaluationService.evaluate` 와 `AutonomyPolicyService.decide` 를 한 번 잇는다.
시도마다 `proactive_loop_run` 한 줄을 남기고, 기동 때 끝나지 않은 시도를 닫고, 판단 피드백 export 에 시도를 싣는다.

**범위 외**: 표와 설정 API(phase 01 에서 끝났다). 7일 합성 반복(phase 03). 사용자에게 판정을 보이는 일(다음 PR). 가치 평가와 행동 정책의 규칙은 바꾸지 않는다.

## 컨텍스트

**근거 문서**: `docs/backend/proactive-loop.md` 의 「무엇을 잇는가」, 「언제 부르는가」, 「줄을 남기지 않고 돌아가는 조건」, 「시도 줄을 남기는 순서」, 「평가와 판정」, 「서버가 멈췄을 때」, 「기록과 조회」 절, `docs/backend/decision-feedback.md` 의 「replay 읽기 모델」 표(`records[].situation.loop`, 모양 버전 2), `docs/backend/proactive-check.md` 의 「끝날 때」 절, `docs/adr/ADR-20261008-daily-loop.md`

phase 01 이 만든 것: `ProactiveLoopSetting`, `ProactiveLoopRun`, `LoopRunStatus`, `LoopSkippedReason`, `ProactiveLoopSettingRepository`(`findByUserIdAndAgentId`, 잠그는 `findByUserIdOrderByIdAsc`), `ProactiveLoopRunRepository`(`findBySourceCheckId`, `countByUserIdAndStatusNotAndCreatedAtAfter`, `findByStatus`, `findBySourceCheckIdIn`), `LiveProperties<ProactiveLoopProperties>`(`enabled`, `provider`, `maxRunsPerDay`).

기존 코드:

- `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveCheckService.java` 의 `runCheck(CurrentUser owner, Long conversationId, TurnHandle handle, ProactiveCheckRun run)`. 안쪽 `finally` 에서 `turns.close(handle)` 로 잠금을 푼다. `run.check()` 가 그 살펴보기 줄이다(패키지 공개)
- `ProactiveCheckEnded` 사건(`backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveCheckEnded.java`)과 `ApplicationEventPublisher events` 를 이미 쓴다. `AutonomyPolicyService` 가 `ProactiveCheckService` 를 주입받으므로 `ProactiveCheckService` 가 이음매를 직접 부르면 순환 의존이 된다. 그래서 사건으로 잇는다
- `ValueEvaluationService.evaluate(CurrentUser, Long checkId, String providerId)` 는 `ValueEvaluation` 을 돌려준다. 알 수 없는 provider 는 `ApiException(VALIDATION_FAILED)`
- `AutonomyPolicyService.decide(CurrentUser, Long evaluationId)` 는 판정 줄을 저장하고 `EXECUTE` 면 `startAutonomous` 까지 부른다
- 그 살펴보기의 `ACCEPTED` 후보가 있는지는 기존 `ProactiveCheckProblemRepository.findByCheckIdAndStatusOrderByIdAsc(checkId, ProblemStatus.ACCEPTED)` 가 비었는지로 본다. 저장소에 메서드를 더하지 않는다
- 기동 복구의 본보기: `backend/src/main/java/com/bifos/assistant/proactive/application/ValueEvaluationRecovery.java` (`SmartLifecycle`, 줄마다 실패를 로그로 넘기고 다음 줄로 간다)
- export: `backend/src/main/java/com/bifos/assistant/proactive/application/DecisionFeedbackExporter.java` 의 `situation(ProactiveCheck)`, 모양은 `backend/src/main/java/com/bifos/assistant/proactive/application/model/DecisionFeedbackExport.java`(`VERSION = 1`)
- 시험의 본보기: `backend/src/test/java/com/bifos/assistant/proactive/CareerDailyPilotTest.java` 가 매일 깨우기를 `TaskDispatcher.tick` 으로 돌리고, `backend/src/test/java/com/bifos/assistant/proactive/eval/ProactiveEvalGateTest.java` 가 대역 Hermes 의 결과 블록 버전 3(`problemCandidates`)과 결정적 provider(`fixture-a`, `fixture-unavailable`, `fixture-error`)를 쓴다. 결정적 provider 빈은 `backend/src/test/java/com/bifos/assistant/testsupport/IntegrationTestDoubles.java` 가 등록하며, 판단 기록은 `backend/src/test/resources/proactive-eval/scenarios.json` 의 문제 키로 찾는다. 그 fixture 의 문제 키(`career:deadline-tomorrow` 등)를 그대로 쓰고 fixture 파일은 고치지 않는다

## 의도 메모

- 이음매는 언제 부를지와 시도 기록만 갖는다. 평가와 판정의 검사, 저장, 실패 처리를 다시 구현하지 않는다
- 사건은 같은 스레드에서 동기로 받는다(`@EventListener`). 살펴보기 turn 을 돌린 백그라운드 스레드가 그대로 평가와 판정을 돈다
- 시도를 다시 부르지 않는다. 실패한 시도는 `FAILED` 로 끝이다
- 하루 상한은 그 사용자의 설정 줄을 모두 쓰기 잠금으로 잡은 같은 트랜잭션에서 센다. 동시에 끝난 두 깨우기가 둘 다 0 을 세지 않게 하기 위해서다
- 상한의 창은 24시간이 아니라 20시간이다. 시도 줄의 시각은 turn 이 끝난 뒤라 날마다 다르다. 24시간이면 어제보다 일찍 끝난 오늘 깨우기가 빠진다
- 시험은 깨운 뒤 시계를 옮기거나 결과를 읽기 전에 `TrackingBackgroundTasks.awaitIdle(Duration)` 으로 기다린다. 루프는 살펴보기 상태가 바뀐 뒤 같은 백그라운드 스레드에서 돌므로, 살펴보기 상태나 대화 잠금만 기다리면 시도 줄이 아직 없거나 `RUNNING` 이다. 이 메서드는 기다리는 동안 새로 뜬 자동 실행 스레드까지 기다린다
- 원천 유일 제약 위반(`DataIntegrityViolationException`)은 이미 맡은 처리가 있다는 뜻이다. 아무것도 하지 않고 돌아간다
- 시도 줄에는 글, 원문, provider 이름을 두지 않는다

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveCheckSettled.java`

record `ProactiveCheckSettled(CurrentUser user, Long checkId)`. Javadoc 에 「살펴보기 turn 의 끝을 정리하고 점검 대화 잠금을 푼 뒤에 낸다」 를 적는다.

### 2. `ProactiveCheckService.runCheck` 에서 사건을 낸다

`turns.close(handle)` 를 부르는 `try/finally` 가 끝난 뒤, `run.check()` 의 번호가 있으면 `events.publishEvent(new ProactiveCheckSettled(owner, run.check().id()))` 를 부른다. 리스너의 예외가 올라와도 로그만 남기도록 `try/catch (RuntimeException)` 로 감싼다. 기존 `finish` 의 순서와 내용은 바꾸지 않는다.

### 3. `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveLoopCoordinator.java`

`@Component`. `@EventListener void settled(ProactiveCheckSettled event)`.

1. `ProactiveCheckRepository.findById(checkId)` 로 줄을 읽는다. 시작 계기가 `SCHEDULED` 가 아니거나 상태가 `SUCCEEDED` 가 아니면 돌아간다
2. `ProactiveLoopProperties.enabled` 가 거짓이면 돌아간다
3. `findByUserIdAndAgentId(check.userId(), check.agentId())` 가 없거나 `enabled` 가 거짓이면 돌아간다
4. `TransactionTemplate` 한 트랜잭션에서 `findByUserIdOrderByIdAsc(userId)` 로 잠근다. 잠근 목록에서 그 에이전트의 줄을 다시 찾아, 없거나 `enabled` 가 거짓이면 시도 줄 없이 돌아간다. 그 뒤 문서의 순서(`SNOOZED`, `NO_CANDIDATE`, `DAILY_LIMIT`, `RUNNING`)로 줄을 만들어 `saveAndFlush` 한다. 하루 상한은 `countByUserIdAndStatusNotAndCreatedAtAfter(userId, SKIPPED, now - 20시간) >= maxRunsPerDay`
5. `DataIntegrityViolationException` 이면 돌아간다. 저장한 줄이 `RUNNING` 이 아니면 돌아간다
6. 트랜잭션 밖에서 `evaluate(user, checkId, provider)` 뒤 `decide(user, evaluation.id())` 를 부른다
7. 끝나면 새 트랜잭션에서 줄을 다시 읽어 `decided(evaluationId, now)`. `ApiException` 이면 `failed(ex.code().name(), 평가 번호나 null, now)`, 그 밖의 `RuntimeException` 이면 `failed("INTERNAL_ERROR", ...)` 로 적고 경고 로그를 남긴다. 로그에 글과 원문을 싣지 않는다. 결과를 적지 못해도 예외를 올리지 않는다

### 3-1. `ValueEvaluationService` 의 Javadoc

`backend/src/main/java/com/bifos/assistant/proactive/application/ValueEvaluationService.java` 의 클래스 Javadoc 「살펴보기와 매일 깨우기에서 자동으로 부르지 않는다」 를 「단추로 연 살펴보기에서는 자동으로 부르지 않는다. 매일 깨우기는 동의한 사용자에게만 `ProactiveLoopCoordinator` 가 한 번 부른다」 로 고친다. 코드는 바꾸지 않는다.

### 4. `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveLoopRecovery.java`

`ValueEvaluationRecovery` 와 같은 `SmartLifecycle` 모양. `findByStatus(RUNNING)` 의 줄마다 새 트랜잭션에서 `failed("INTERRUPTED", null, now)` 로 닫는다. `RUNNING` 줄에는 평가 번호가 없으므로 복구한 줄의 평가 번호는 비어 있다(`docs/backend/proactive-loop.md` 「서버가 멈췄을 때」). 평가와 판정을 다시 부르지 않는다. 한 줄의 실패는 번호와 예외 이름만 로그에 남기고 다음 줄로 간다.

### 5. export 에 시도를 싣는다

- `DecisionFeedbackExport.java`: `VERSION = 2`. 새 record `Loop(Long runId, LoopRunStatus status, LoopSkippedReason skippedReason, String errorCode, Long evaluationId, Instant createdAt, Instant finishedAt)` 를 두고 `Situation` 의 마지막 칸으로 `Loop loop` 을 더한다(시도가 없으면 null)
- `DecisionFeedbackExporter.java`: 상황을 만들 살펴보기 번호로 `findBySourceCheckIdIn` 을 한 번 읽어 `situation(...)` 에 넘긴다

### 6. 이 phase 를 검증하는 시험

설치 설정은 시험 클래스 단위로만 바꿀 수 있다(`@OverrideProperties` 는 클래스에 달고, `@Nested` 는 바깥 클래스의 값을 이어받지 않는다). 그래서 설정 조합마다 클래스를 나눈다.
매일 깨우기는 `ProactiveCheckService.startScheduled(user, agentCode, ignored -> {})` 를 직접 불러 돌리고, `TrackingBackgroundTasks.awaitIdle(Duration)` 으로 루프까지 끝나기를 기다린다. 대역 Hermes 의 답 만들기와 사용자, 에이전트 준비는 `ProactiveEvalGateTest` 의 방식을 따른다. 결과 블록 버전 3 에 `career:deadline-tomorrow` 후보를 담는다.

- `backend/src/test/java/com/bifos/assistant/proactive/ProactiveLoopCoordinatorTest.java` (`@BackendIntegrationTest`, `@LongProactiveCheckTimeouts`, `@OverrideProperties({"assistant.proactive-loop.enabled=true", "assistant.proactive-loop.provider=fixture-a"})`)
  - 동의한 사용자의 `SCHEDULED` 살펴보기 하나에 시도 줄 하나가 `DECIDED` 이고 평가 하나와 후보별 판정 줄이 남는다
  - 같은 `ProactiveCheckSettled` 를 `ApplicationEventPublisher` 로 다시 내도 시도, 평가, 판정 수가 늘지 않는다
  - 설정 줄이 없거나 꺼져 있거나 `MANUAL` 살펴보기면 시도 줄과 평가가 없다
  - 쉬는 중이면 `SKIPPED/SNOOZED`, 대역 답이 `NOTHING_NEW` 면 `SKIPPED/NO_CANDIDATE` 이고 평가가 없다
  - 그 사용자의 `DECIDED` 시도 줄을 19시간 전 시각으로 직접 저장해 두면 `SKIPPED/DAILY_LIMIT` 이고 평가가 없다. 23시간 전 시각이면 `DECIDED` 다(어제보다 일찍 끝난 오늘 깨우기)
  - 판정만 남고 `notification` 줄이 늘지 않는다
- `backend/src/test/java/com/bifos/assistant/proactive/ProactiveLoopDisabledTest.java` (`@BackendIntegrationTest`, `@LongProactiveCheckTimeouts`, 덮어쓰기 없음): 사용자 설정 줄이 켜져 있어도 설치 설정 기본값 `false` 면 시도 줄과 평가가 없다
- `backend/src/test/java/com/bifos/assistant/proactive/ProactiveLoopFallbackTest.java` (`@BackendIntegrationTest`, `@LongProactiveCheckTimeouts`, `@OverrideProperties({"assistant.proactive-loop.enabled=true", "assistant.proactive-loop.provider=fixture-unavailable"})`): 시도는 `DECIDED`, 평가는 `FALLBACK`, 판정은 모두 `IGNORE` 이고 `notification` 줄이 늘지 않는다
- `backend/src/test/java/com/bifos/assistant/proactive/ProactiveLoopUnknownProviderTest.java` (`@BackendIntegrationTest`, `@LongProactiveCheckTimeouts`, `@OverrideProperties({"assistant.proactive-loop.enabled=true", "assistant.proactive-loop.provider=missing-provider"})`): 시도는 `FAILED/VALIDATION_FAILED` 이고 평가가 없다
- `backend/src/test/java/com/bifos/assistant/proactive/ProactiveLoopRecoveryTest.java` (`@BackendIntegrationTest`): `RUNNING` 줄을 직접 저장하고 `recover` 를 부르면 `FAILED/INTERRUPTED`, 평가 번호가 비어 있고 평가 수가 그대로다
- `backend/src/test/java/com/bifos/assistant/proactive/DecisionFeedbackFlowTest.java`: 기존 단언이 `version` 을 보면 2 로 고치고, 시도가 없는 살펴보기의 `situation().loop()` 이 null 인지 하나 더한다

## 검증

```bash
(cd backend && ./gradlew test --tests '*ProactiveLoopCoordinatorTest' --tests '*ProactiveLoopDisabledTest' --tests '*ProactiveLoopFallbackTest' --tests '*ProactiveLoopUnknownProviderTest' --tests '*ProactiveLoopRecoveryTest' --tests '*DecisionFeedbackFlowTest' --tests '*ProactiveEvalGateTest' --tests '*CareerDailyPilotTest' --tests '*ProactiveCheckEndTest')
(cd backend && ./gradlew test --tests 'com.bifos.assistant.architecture.*')
(cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck)
```

새 시험이 모두 통과하고, 기존 루프 평가와 매일 깨우기 시험이 그대로 통과한다(루프 설치 설정의 기본값이 꺼짐이라 기존 시험에서는 시도 줄이 생기지 않는다).

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveCheckSettled.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveCheckService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveLoopCoordinator.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveLoopRecovery.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/ValueEvaluationService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/DecisionFeedbackExporter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/model/DecisionFeedbackExport.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/proactive/ProactiveLoopTestSupport.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/proactive/ProactiveLoopCoordinatorTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/proactive/ProactiveLoopDisabledTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/proactive/ProactiveLoopFallbackTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/proactive/ProactiveLoopUnknownProviderTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/proactive/ProactiveLoopRecoveryTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/proactive/DecisionFeedbackFlowTest.java` | 수정 |
