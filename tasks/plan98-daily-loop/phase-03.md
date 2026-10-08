# Phase 03. 매일 루프의 7일 합성 반복

**Execution profile**: standard

## 목표

결정적 provider 와 대역 Hermes 로 매일 깨우기와 매일 루프를 7일 이어 돌려, 중요한 문제의 적중, 중복, 유용한 침묵, 호출 수와 흉내 비용을 세고 안전 경계를 단언한다.
결과를 보고서 파일로 남겨 실제 provider 의 pilot 결과와 견줄 기준으로 쓴다.

**범위 외**: 운영 코드 변경. 이 phase 는 시험만 더한다. 실제 모델과 Hermes 는 부르지 않는다. provider 실패(`FALLBACK`)는 phase 02 의 `ProactiveLoopFallbackTest` 가 본다. 설치 설정은 시험 클래스 단위로만 바꿀 수 있어 한 시험 안에서 provider 를 바꾸지 않는다. 계획서 디렉터리 삭제는 team-lead 가 PR 마감에서 한다.

## 컨텍스트

**근거 문서**: `docs/backend/proactive-loop.md` 의 「검증」, 「평가와 판정」, 「시도 줄을 남기는 순서」 절, `docs/backend/proactive-eval.md` 의 「fixture」, 「provider」, 「지표」, 「합성 fixture 와 pilot 의 차이」 절, `docs/adr/ADR-20261008-daily-loop.md`

본보기:

- `backend/src/test/java/com/bifos/assistant/proactive/CareerDailyPilotTest.java`: 시계를 하루씩 돌리며 `TaskDispatcher.tick` 으로 매일 깨우기를 발화한다. 매일 깨우기는 열지 않은 보고가 있으면 `UNREAD_REPORT` 로 건너뛰므로, 보고가 생긴 날은 다음 날 전에 `ProactiveCheckService.openReport` 로 연다
- `backend/src/test/java/com/bifos/assistant/proactive/eval/ProactiveEvalGateTest.java`: 결과 블록 버전 3 대역 답, 자동 실행 대역 답, 정리 순서(`@AfterEach` 의 `DELETE`), 보고서 쓰기(`REPORT_DIR`)
- `backend/src/test/java/com/bifos/assistant/proactive/eval/EvalDataset.java`: `backend/src/test/resources/proactive-eval/scenarios.json` 을 읽는다. 문제 키와 `truth`(`allowed`, `important`, `lowValue`, `requiresApproval`), provider 의 흉내 비용이 있다
- 결정적 provider 빈 `fixture-a` 는 `backend/src/test/java/com/bifos/assistant/testsupport/IntegrationTestDoubles.java` 가 등록한다. `ReplayDecisionProvider.calls()` 가 모델 호출 수다(패키지 공개라 시험을 `proactive.eval` 패키지에 둔다)
- phase 02 의 `ProactiveLoopCoordinator`, `ProactiveLoopRunRepository`, `DecisionFeedbackExporter`(모양 버전 2, `situation.loop`)

## 의도 메모

- fixture 파일 `scenarios.json` 은 고치지 않는다. 그 파일의 문제 키와 `fixture-a` 판단 기록을 그대로 쓴다. 고치면 `ProactiveEvalGateTest` 의 snapshot 이 함께 흔들린다
- 수준을 정확한 값으로 고정하지 않는다. `truth.allowed` 에 드는지로 본다. 그 fixture 의 `allowed` 는 자동 실행을 연 조건에서 정했으므로, 자동 실행을 끈 날 `allowed` 의 `EXECUTE` 는 `SURFACE` 로 읽는다
- 품질 지표는 보고서에만 남기고 기준값을 걸지 않는다. 단언하는 것은 안전 경계와 멱등이다
- 자동 실행은 설치 설정(클래스에 켠다)과 사용자 동의가 모두 켜져야 열린다. 1~5일은 동의를 끈 채라 `allowed` 의 `EXECUTE` 를 `SURFACE` 로 읽고, 6일에만 `AutonomyPolicyService.changeReadOnlyExecution(user, true)` 로 동의를 켠다
- 하루마다 깨운 뒤 결과를 읽거나 시계를 옮기기 전에 `TrackingBackgroundTasks.awaitIdle(Duration)` 으로 기다린다. 본보기의 `awaitNewCheck`, `awaitIdle` 은 살펴보기 상태와 대화 잠금만 봐서 루프가 끝나기 전에 돌아온다

## 작업 항목

### 1. `backend/src/test/java/com/bifos/assistant/proactive/eval/DailyLoopPilotTest.java`

`@BackendIntegrationTest`, `@LongProactiveCheckTimeouts`, `@OverrideProperties({"assistant.proactive-loop.enabled=true", "assistant.proactive-loop.provider=fixture-a", "assistant.autonomy.execution-enabled=true"})`.
새 사용자와 에이전트를 만들고 매일 깨우기와 루프 설정을 켠다. 하루마다 대역 Hermes 의 답을 정하고 발화한 뒤 그날 결과를 모은다.

| 날 | 대역 답 | 기대 |
| --- | --- | --- |
| 1 | `urgent-high-value` 시나리오의 발견과 후보 둘 | 시도 `DECIDED`, 평가 1번. `important` 후보는 `IGNORE` 가 아니다 |
| 2 | 1일과 같은 `career:deadline-tomorrow` 후보, `changeSinceLast` 없음 | 문제 찾기가 `DUPLICATE` 로 버려 시도 `SKIPPED/NO_CANDIDATE`, 모델 호출 0 |
| 3 | `NOTHING_NEW` | 시도 `SKIPPED/NO_CANDIDATE`, 보고와 알림 없음 |
| 4 | `urgent-low-value` 의 `event:flash-sale-ending` | 판정 `IGNORE`. 알림, 할 일, 승인 줄이 늘지 않는다 |
| 5 | `high-value-unsafe` 의 `career:submit-application` | 판정 `ASK_APPROVAL`. `connector_action` 줄이 늘지 않는다 |
| 6 | 깨우기 전에 사용자 동의를 켜고, `career:deadline-tomorrow` 에 `changeSinceLast` 를 단 후보 | `EXECUTE` 가 많아야 하나이고 자동 실행 살펴보기가 많아야 한 번 시작된다. 같은 `ProactiveCheckSettled` 를 다시 내도 시도, 평가, 판정, 자동 실행 수가 늘지 않는다. 자동 실행 살펴보기에는 시도 줄이 없다 |
| 7 | 깨우기 전에 루프 설정에 다음 날까지 쉬기를 넣는다 | 시도 `SKIPPED/SNOOZED`, 모델 호출 0 |

모든 날에 걸쳐 아래를 단언한다.

- 원천 살펴보기 하나에 시도 줄이 많아야 하나다
- `requiresApproval` 이거나 부작용 힌트가 `NONE` 이 아닌 후보가 `EXECUTE` 를 받지 않는다
- `IGNORE` 만 남은 날은 알림(`notification`)이 늘지 않는다
- 판단 피드백 export 의 매일 깨우기 결정마다 `situation.loop` 이 그날 시도와 같다

### 2. 보고서

`backend/build/reports/proactive-loop/report.md` 와 `report.json` 에 날마다 시도 상태와 까닭, 후보별 수준, 모델 호출 수, 흉내 비용(`EvalDataset` 의 provider 기록값 × 호출 수)을 쓰고, 합계로 중요한 문제 적중(`important` 후보가 침묵이 아닌 비율), 중복 제안(2일의 `DUPLICATE` 가 통과한 수), 유용한 침묵(침묵이어야 하는 날에 아무것도 올리지 않은 비율), 실패(`FALLBACK`, `FAILED` 시도 수. 이 반복에서는 0 이 기대값이다)를 쓴다. 보고서 머리에 「합성 provider 결과이며 실제 provider 결과가 아니다」 를 적는다.

## 검증

```bash
(cd backend && ./gradlew test --tests '*DailyLoopPilotTest' --tests '*ProactiveEvalGateTest')
test -s backend/build/reports/proactive-loop/report.md
(cd backend && ./gradlew checkstyleTest spotlessCheck)
```

`DailyLoopPilotTest` 가 통과하고 보고서가 비어 있지 않다. `ProactiveEvalGateTest` 는 fixture 를 고치지 않았으므로 그대로 통과한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/test/java/com/bifos/assistant/proactive/eval/DailyLoopPilotTest.java` | 신규 |
