# Phase 01. 확인 기록 줄을 종류로 나누고 PR1 정리가 평가 줄을 건드리지 않게 한다

**Execution profile**: standard

## 목표

`quality_run.result_json` 에 배포 뒤 확인(`DEPLOY_CHECK`)과 평가(`EVAL`)가 서로 다른 모양을 담을 수 있게 하고, PR1 의 재기동 처리·하루 정리·기억 지우기가 평가 줄과 평가 대화를 건드리지 않게 한다.

**범위 외**: 평가 실행기, 묶음 파일, 평가 API 는 뒤 phase 다.

## 컨텍스트

이 계획은 PR1 실행기 PR(#850 「배포 뒤 확인을 시작하고 기능별로 돌리는 실행기를 더한다」)이 main 에 머지된 뒤 시작한다. 아래 PR1 식별자는 그 PR 의 코드다.

- `quality/domain/QualityRun.java` 의 `results` 는 `@Convert(converter = FeatureResultsConverter.class)` 로 `List<FeatureResult>` 에 묶여 있다. JPA 변환기는 `kind` 를 보지 못한다. `record()` 는 `itemCount` 를 결과 수로 덮는다.
- `DeployCheckRuns.interruptRunning()` 은 `QualityRunRepository.findByStatus(RUNNING)` 로 종류를 가리지 않고 끝내며 배포 확인 실패 알림을 보낸다.
- `DeployCheckCleaner.sweepExpired()` 는 `findByStatusAndStartedAtBefore(FAILED, …)` 의 줄을 모두 `FeatureResult` 로 읽는다.
- `DeployCheckCleaner.forgetNewMemories(evaluation, since)` 는 확인 시작 뒤 생긴 평가 사용자의 기억을 모두 지운다. 함께 도는 평가 문항의 기억까지 지운다.
- 기억이 어느 대화에서 생기거나 고쳐졌는지는 `memory_capture` 가 갖는다(`MemoryCaptureService.capturesOf(user, conversationId)`, `undo(user, captureId)`, `MemoryCaptureKind` 의 `CREATED`, `UPDATED`).

**요구사항**: REQ17

**근거 문서**: `docs/features/agent-quality/design.md` 의 「저장: `quality_run` 의 `EVAL`」, 「비우기」, 결정 DECISION11

## 의도 메모

- 엔티티 칸은 JSON 문자열(`@JdbcTypeCode(SqlTypes.JSON)` 의 `String`)로 바꾸고, 배포 뒤 확인 결과는 `FeatureResultsConverter` 를 JPA 변환기가 아닌 순수 codec(정적 `read`, `write`. 지금처럼 안의 정적 `JsonMapper`)으로 바꿔 `results()`, `record()`, `dropLinks()` 안에서 쓴다. design 「저장: `quality_run` 의 `EVAL`」 이 이 배치를 정한다. 호출부의 메서드 모양은 그대로 둔다. 기존 줄의 JSON 은 바뀌지 않는다(마이그레이션 없음).
- 평가 결과 모양은 `EvalResult` 로 두고 `QualityRun` 에 `evalResult()`, `recordEval(EvalResult)` 를 더한다. `kind` 가 맞지 않으면 `IllegalStateException`.
- 테이블과 칸은 늘리지 않는다.

## Blocked 조건

- `backend/src/main/java/com/bifos/assistant/quality/application/DeployCheckRunner.java` 가 없다(PR1 실행기 미머지) → `PHASE_BLOCKED: PR1 실행기 미머지`

## 작업 항목

### 1. `QualityRun` 과 `FeatureResultsConverter`

- `results` 칸을 `String resultJson` 으로 바꾼다. `results()` 는 `DEPLOY_CHECK` 에서만 codec 으로 읽는다.
- `EVAL` 에서는 `item_count` 를 `recordEval` 이 덮지 않는다. `start` 에 `itemCount` 를 받는 생성 메서드(`startEval`)를 더한다.

### 2. `quality/domain/EvalResult.java`

record `EvalResult(String tier, List<BundleResult> bundles, List<ItemResult> items, boolean cleaned)`. `ItemResult` 는 `bundle`, `question`, `status`(`PASSED`, `FAILED`, `TIMED_OUT`, `SKIPPED`), `reasonCode`, `elapsedMs`, `conversationId`(문항 시작 때 적는 내부 번호, 비우기용), `conversationPublicId`, `executionIds`, `keep`, `failures`(고정 까닭 코드 목록). 답 본문은 담지 않는다. 같은 파일에 정적 `JsonMapper` 를 쓰는 codec(`read`, `write`)을 둔다. Jackson 3 만 쓴다(rule-14). `status` 에 `BUSY` 는 두지 않는다. 한도 거절은 `FAILED` 와 까닭 `BUSY` 다.

### 2-1. `QualityRunKind` 에 `EVAL`

`quality/domain/type/QualityRunKind.java` 에 `EVAL` 을 더한다. `backend/docs/data-schema.md` 의 `quality_run` 절의 `kind` 값 설명을 고친다. 이미 적용된 마이그레이션 파일은 고치지 않는다(주석도 체크섬에 든다).

### 3. `QualityRunRepository`

`findByKindAndStatus(kind, status)`, `findByKindAndStatusAndStartedAtBefore(kind, status, before)` 를 더하고 PR1 호출부를 바꾼다.

### 4. `DeployCheckRuns.interruptRunning`, `DeployCheckCleaner.sweepExpired`

`DEPLOY_CHECK` 줄만 본다.

### 5. `DeployCheckCleaner.forgetNewMemories` 를 대화 단위로

`forgetCaptured(evaluation, conversationId)` 로 바꾼다. 그 대화의 capture 가 `CREATED` 나 `PROPOSED` 면 그 기억을 지우고, `UPDATED` 면 `undo` 로 되돌린다. 되돌리기는 늦게 생긴 capture 부터 한다. 순서가 틀리면 `undo` 가 `MEMORY_REVISION_CONFLICT` 를 던진다. 지금 `forgetNewMemories` 가 제안(`proposalsOf`)까지 지우던 동작을 `PROPOSED` 로 잇는다. `DeployCheckRunner` 는 기능이 끝날 때 그 기능의 대화마다 부른다. 준비 때 넣은 기억(`DeployCheckItems.MEMORY_TITLE`)은 고쳐졌으면 되돌아온다.

### 6. 테스트

- `QualityRunTest`: `DEPLOY_CHECK` 의 `record`, `dropLinks` 가 그대로이고, `EVAL` 의 `recordEval` 이 `itemCount` 를 덮지 않으며 다른 종류 메서드는 예외다.
- `DeployCheckCleanerTest`(신규 통합): 대화 A 의 capture 로 생긴 기억은 지워지고 대화 B 의 기억은 남는다. 준비 기억을 고친 capture 는 되돌아온다. `EVAL` 인 `FAILED` 줄이 있어도 `sweepExpired` 가 예외 없이 `DEPLOY_CHECK` 줄만 정리한다.
- `DeployCheckApiTest`: 재기동 처리가 `RUNNING` 인 `EVAL` 줄을 바꾸지 않는다(줄을 직접 저장해 본다). 확인 한 번 뒤 기능 대화로 생긴 기억은 지워지고, 그 전에 다른 대화로 생긴 평가 사용자 기억은 남는다(`DeployCheckRunner` 의 연결을 되돌리면 실패한다).

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.quality.domain.QualityRunTest' --tests 'com.bifos.assistant.quality.application.DeployCheckCleanerTest' --tests 'com.bifos.assistant.admin.quality.DeployCheckApiTest' --tests 'com.bifos.assistant.quality.application.DeployCheckJudgeTest'
cd backend && ./gradlew archTest checkstyleMain checkstyleTest
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/quality/domain/QualityRun.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/quality/domain/FeatureResultsConverter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/quality/domain/EvalResult.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/quality/domain/type/QualityRunKind.java` | 수정 |
| `backend/docs/data-schema.md` | 수정 |
| `backend/src/main/java/com/bifos/assistant/quality/infra/QualityRunRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/quality/application/DeployCheckRuns.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/quality/application/DeployCheckCleaner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/quality/application/DeployCheckRunner.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/quality/domain/QualityRunTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/quality/application/DeployCheckCleanerTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/admin/quality/DeployCheckApiTest.java` | 수정 |
