# Phase 07. proactive 이동

**Execution profile**: standard

## 목표

`proactive` 를 여섯 하위 기능으로 나눈다. `check` 가 가장 아래이고 `surface` 와 `feedback` 이 위다.

**범위 외**: 이동 표에 없는 클래스와, 이동으로 새로 생기는 코드 변경(승격 외).

## 컨텍스트

- 공통 절차는 `move-procedure.md` 다. 순서 1부터 10까지 모두 따른다.
- 결정은 `backend/docs/adr/ADR-20261015-package-layout.md` 다. 역할 패키지의 뜻은 그 「결정」 표가, 하위 기능의 순서와 뜻은 「하위 기능」 표가 갖는다.
- 표의 「옛 패키지」 는 이 PR 시작 시점의 위치다. 앞선 이동 PR 이 같은 클래스의 위치를 바꾸지 않았다는 전제다.
- 표의 † 는 `application` 직하에 남는 public 비빈 클래스다. 기준 파일(`APPLICATION_ROOT_HOLDS_ONLY_BEANS`)에 얼린 채로 둔다.
- 추정 줄 수는 앞선 이동이 끝난 상태를 가정한다.

## 이동 PR 7-1: proactive.check

- 옮기는 클래스 57개. 추정 변경은 운영 코드 약 540줄이다(상한 1500).

### 이동 표

| 새 패키지 | 옛 패키지 | 클래스 |
| --- | --- | --- |
| `proactive.check.application` | `proactive.application` | CheckAnswerRenderer, CheckFeedback, CheckFindingReactions, CheckReportFactory, CheckReportReadMarker, CheckResultParser, FindingJudgement†, ProactiveCheckAnswerGuard, ProactiveCheckGuard, ProactiveCheckInput, ProactiveCheckInstructions, ProactiveCheckLimits, ProactiveCheckReadiness, ProactiveCheckRecovery, ProactiveCheckResults, ProactiveCheckRun†, ProactiveCheckService, ProactiveReportSource, ProblemJudgement† |
| `proactive.check.application.dto` | `proactive.application` | ProactiveCheckEnded, ProactiveCheckSettled |
| `proactive.check.application.dto` | `proactive.application.model` | AnnouncedKey, CheckBlocker, CheckBlockerCode, CheckFindingView, CheckReadiness, CheckResultBlock, CheckResultRead, CheckStatusView, DecisionReaction, FindingReaction, JudgedFinding, JudgedProblem, UnreadCheckReport |
| `proactive.check.application.port` | `proactive.application` | CheckNotificationPolicy, CheckReportApprovalSource |
| `proactive.check.config` | `proactive.application` | ProactiveCheckProperties |
| `proactive.check.domain` | `proactive.domain` | CheckReport, CheckReportJsonConverter, ProactiveCheck, ProactiveCheckFinding, ProactiveCheckProblem, ProblemEvidence, ProblemEvidenceJsonConverter |
| `proactive.check.domain.type` | `proactive.domain.type` | CheckInvalidReason, CheckOutcome, CheckSkippedReason, CheckStatus, CheckTrigger, FindingKind, FindingReason, ProblemDropReason, ProblemStatus |
| `proactive.check.infra` | `proactive.infra` | ProactiveCheckFindingRepository, ProactiveCheckProblemRepository, ProactiveCheckRepository |
| `proactive.check.presentation` | `proactive.presentation` | ProactiveCheckReportController |

### 승격 (package-private → public)

없다. 컴파일 오류로 더 나오면 `move-procedure.md` 의 순서 3 을 따른다.

### 문자열 전체 이름

없다.

### 규칙 파일의 import

`architecture` 테스트가 import 하는 클래스 가운데 이 PR 이 옮기는 것: `CheckInvalidReason`, `CheckOutcome`, `CheckStatus`, `CheckTrigger`, `FindingKind`, `FindingReason`, `ProactiveCheckProperties`.

### 기준 파일에 더하는 줄

없다. 이 PR 이 새로 만드는 위반은 없어야 한다.

이 PR 이 기준 파일에서 지우는 줄(앞 PR 이 더한 것): `ProactiveSchedule`→`CheckBlocker`.

### 이 PR 의 메모

- `ProactiveCheckInput`, `ProactiveCheckLimits`, `ProactiveCheckResults`, `ProactiveCheckRun` 은 서로 부르는 package-private 도우미 묶음이다. 모두 `proactive.check.application` 에 함께 둔다. `ProactiveCheckRun` 은 public 비빈이라 기준 파일에 남는다.
- `FindingJudgement`, `ProblemJudgement` 는 결과 계약의 검사에 쓰는 정책 클래스라 `check` 에 둔다. public 비빈으로 남는다.
- `task` 를 옮긴 PR 이 더한 임시 줄(`ProactiveSchedule` 이 `CheckBlocker` 를 쓴다)이 이 PR 에서 사라진다.

## 이동 PR 7-2: proactive.evaluation, autonomy, loop, surface, feedback

- 옮기는 클래스 64개. 추정 변경은 운영 코드 약 480줄이다(상한 1500).

### 이동 표

| 새 패키지 | 옛 패키지 | 클래스 |
| --- | --- | --- |
| `proactive.autonomy.application` | `proactive.application` | AutonomyPolicyService, AutonomyPolicy†, ValueEvaluationOverviews |
| `proactive.autonomy.application.dto` | `proactive.application.model` | AutonomyVerdict, EvaluationOverview |
| `proactive.autonomy.config` | `proactive.application` | AutonomyProperties |
| `proactive.autonomy.domain` | `proactive.domain` | AutonomyDecision, AutonomyInputs, AutonomyInputsJsonConverter, AutonomyPreference, AutonomyReasonsJsonConverter |
| `proactive.autonomy.domain.type` | `proactive.domain.type` | AutonomyExecutionStatus, AutonomyLevel, AutonomyReason |
| `proactive.autonomy.infra` | `proactive.infra` | AutonomyDecisionRepository, AutonomyPreferenceRepository |
| `proactive.evaluation.application` | `proactive.application` | HermesDecisionProvider, ValueEvaluationRecovery, ValueEvaluationService, ValueEvaluationStore, ValueEvaluator |
| `proactive.evaluation.application.dto` | `proactive.application.model` | DecisionRequest, DecisionResponse |
| `proactive.evaluation.application.port` | `proactive.application` | DecisionProvider |
| `proactive.evaluation.config` | `proactive.application` | ValueEvaluationProperties |
| `proactive.evaluation.domain` | `proactive.domain` | AxisJudgement, CandidateJudgement, DecisionCandidate, DecisionEvidence, DecisionEvidenceJsonConverter, DecisionProviderInfo, DecisionQuestion, DecisionResult, DecisionState, ValueEvaluation |
| `proactive.evaluation.domain.type` | `proactive.domain.type` | DecisionAxis, DecisionConfidence, DecisionFailure, DecisionLevel, DecisionOutcome |
| `proactive.evaluation.infra` | `proactive.infra` | ValueEvaluationRepository |
| `proactive.feedback.application` | `proactive.application` | DecisionFeedbackExporter |
| `proactive.feedback.application.dto` | `proactive.application.model` | DecisionFeedbackExport |
| `proactive.feedback.presentation` | `proactive.presentation` | DecisionFeedbackController |
| `proactive.loop.application` | `proactive.application` | ProactiveAgentPurge, ProactiveLoopCoordinator, ProactiveLoopRecovery, ProactiveLoopSettingService |
| `proactive.loop.application.dto` | `proactive.application.model` | LoopSettingView |
| `proactive.loop.config` | `proactive.application` | ProactiveLoopProperties |
| `proactive.loop.domain` | `proactive.domain` | ProactiveLoopRun, ProactiveLoopSetting |
| `proactive.loop.domain.type` | `proactive.domain.type` | LoopRunStatus, LoopSkippedReason |
| `proactive.loop.infra` | `proactive.infra` | ProactiveLoopRunRepository, ProactiveLoopSettingRepository |
| `proactive.surface.application` | `proactive.application` | SurfacedProblems |
| `proactive.surface.application.dto` | `proactive.application.model` | SurfacedProblem |
| `proactive.surface.presentation` | `proactive.presentation` | AutonomyController, CheckFindingController, ProactiveCheckController, ProactiveCheckDtos, ProactiveLoopController, ValueEvaluationController |

### 승격 (package-private → public)

없다. 컴파일 오류로 더 나오면 `move-procedure.md` 의 순서 3 을 따른다.

### 문자열 전체 이름

없다.

### 규칙 파일의 import

`architecture` 테스트가 import 하는 클래스 가운데 이 PR 이 옮기는 것: `AutonomyExecutionStatus`, `AutonomyLevel`, `AutonomyProperties`, `ProactiveLoopProperties`.

### 기준 파일에 더하는 줄

`SUB_FEATURES_FOLLOW_ORDER` 에 더한다. 이 줄들은 이동이 끝난 뒤에도 남는다.

| 기능 | 아래 하위 기능 | 쓰는 클래스 | 위 하위 기능 | 쓰이는 클래스 |
| --- | --- | --- | --- | --- |
| `proactive` | `loop` | `ProactiveLoopCoordinator` | `surface` | `SurfacedProblems` |


### 이 PR 의 메모

- `ValueEvaluationAdminController` 는 phase 03 이 이미 `admin.proactive` 로 옮겼다. 그 컨트롤러가 부르는 `ValueEvaluationOverviews` 는 `proactive.autonomy` 에 있다(`AutonomyDecision` 을 함께 읽기 때문이다).
- `ProactiveLoopCoordinator` 가 `SurfacedProblems`(`surface`)를 쓴다. 이 한 줄은 기준 파일에 더한다.
- `ProactiveAgentPurge` 는 `loop` 에 둔다. `check` 와 `loop` 의 표를 함께 지우는 정리 참여자라 `loop` 가 위에 있어야 한다.
- `surface` 는 `ProactiveCheckDtos` 와 그것을 쓰는 컨트롤러(`CheckFindingController`, `ValueEvaluationController`, `ProactiveLoopController`, `AutonomyController`, `ProactiveCheckController`)를 갖는다. 컨트롤러가 `check`, `evaluation`, `loop`, `autonomy` 의 서비스를 위에서 부른다.
