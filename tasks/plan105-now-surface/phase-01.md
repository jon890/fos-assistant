# Phase 01. 먼저 다룰 문제 항목과 판정 반응 (backend)

**Execution profile**: deep

## 목표

매일 루프가 낸 `SURFACE`, `ASK_APPROVAL` 판정을 지금 화면 「내 차례」 의 `PROBLEM_SURFACED` 항목으로 내고, 그 판정에 `SURFACED` 와 사용자 반응(`ACCEPTED`, `DISMISSED`)을 판단 피드백 사건으로 남긴다.
반응은 기록만 한다. 할 일, 승인 줄, 실행을 만들지 않는다.

**범위 외**: 웹 화면과 웹 API 경로, 브라우저 시험(phase 02). 평가와 행동 정책의 규칙. 매일 루프의 진입 조건과 시도 기록.

## 컨텍스트

**근거 문서**: `docs/backend/proactive-loop.md` 의 「사용자에게 보이는 것」(「보일 판정은 아래 순서로 고른다」 포함), 「사용자 설정」, 「설정」, 「검증」 절, `docs/backend/attention.md` 의 「후보와 trigger」 표 `PROBLEM_SURFACED` 줄과 출처 표, 「카드의 단추와 승인 경계」, 추가 칸 표의 `problem`, `docs/backend/decision-feedback.md` 의 「기록 지점」 표의 행동 정책 판정 줄, `docs/adr/ADR-20261008-daily-loop.md`, `docs/flow.md` 의 오류 표 `AUTONOMY_DECISION_NOT_FOUND`

기존 코드(경로는 `backend/src/main/java/com/bifos/assistant/` 아래):

- 매일 루프: `proactive/application/ProactiveLoopCoordinator.java`(판정 뒤 시도를 `DECIDED` 로 적는 자리), `proactive/infra/ProactiveLoopRunRepository.java`, `proactive/domain/ProactiveLoopRun.java`(`evaluationId()`, `status()`, `createdAt()`), `proactive/application/ProactiveLoopProperties.java`(record `enabled`, `provider`, `maxRunsPerDay`), `proactive/application/ProactiveLoopSettingService.java`
- 판정 줄: `proactive/domain/AutonomyDecision.java`(`evaluationId`, `candidateId`, `sourceCheckId`, `level`), `proactive/infra/AutonomyDecisionRepository.java`. 후보 글은 `proactive/infra/ProactiveCheckProblemRepository.java` 의 `ProactiveCheckProblem`(`problem`, `actionText`, `problemKey`. 글 칸은 NULL 일 수 있다)
- 반응의 본보기: `proactive/application/CheckFindingReactions.java` 의 `react`, `current`, `surfaced`, `entry`, `proactive/application/model/FindingReaction.java`, `proactive/presentation/CheckFindingController.java`, `proactive/presentation/ProactiveCheckDtos.java` 의 `FindingReactionRequest`
- 판정 피드백 사건의 본보기: `proactive/application/AutonomyPolicyService.java` 의 `recordStartFailure`(conversation, sourceCheck, autonomyDecision 을 채운다)
- 지금 화면 후보의 본보기: `attention/application/ProactiveReportCandidates.java`(proactive 의 읽기 서비스 `ProactiveReportSource` 를 쓰고 `OwnConversations.activeOf` 로 지운 대화를 거르고 `AgentService.byIds` 로 이름을 얻는다), `attention/application/MemoryProposalCandidates.java`(`MODEL_INFERRED`, `nowSignal = false`)
- 후보 레코드와 응답: `attention/application/model/AttentionCandidate.java`(17칸 정식 생성자와 16칸 생성자), `attention/application/model/AttentionItem.java`, `attention/application/AttentionJudge.java` 의 `item(...)`, `attention/presentation/AttentionDtos.java` 의 `ItemView`, `ReportView`
- 숨기기와 미루기 피드백: `attention/application/SuggestionFeedback.java` 의 두 switch(주체 고르기, `withOrigin`)
- 숨기기 까닭 코드: `feedback/application/FeedbackLabeler.ATTENTION_HIDE`
- 시험 본보기: `backend/src/test/java/com/bifos/assistant/proactive/CheckFindingReactionsTest.java`, `backend/src/test/java/com/bifos/assistant/attention/AttentionControlServiceTest.java`, `backend/src/test/java/com/bifos/assistant/proactive/ProactiveLoopTestSupport.java`, `backend/src/test/java/com/bifos/assistant/proactive/ProactiveLoopCoordinatorTest.java`

층 규칙: `attention` 은 다른 패키지의 `infra` 를 import 하지 않는다(`docs/backend/attention.md` 「패키지」). 판정, 후보, 피드백 사건은 `proactive.application` 의 읽기 서비스 안에서 읽는다.

## 의도 메모

- 판정 줄은 다시 판정할 때마다 새로 생긴다. 관리자 화면과 판정 API 의 판정은 보이지 않으므로 `proactive_loop_run.evaluation_id` 로 루프의 평가를 고르고, 평가와 후보마다 가장 먼저 남긴 판정(가장 작은 `id`)만 루프의 판정으로 본다
- 같은 문제 키는 가장 최근 판정 하나를 먼저 고른 뒤 반응을 본다. 반응을 먼저 빼면 새 판정을 거절한 뒤 옛 판정이 되살아난다
- `surfaceWindow` 는 `LiveProperties<ProactiveLoopProperties>` 로 읽는다. 실행 중에 쓰는 설정 record 를 직접 주입받지 못하게 하는 구조 규칙이 있다
- `SurfacedProblemCandidates.read` 는 `SurfacedProblems` 의 예외를 잡아 로그를 남기고 빈 목록을 낸다. `AttentionService` 는 소스 예외마다 그 소스의 카드 전체를 `UNAVAILABLE` 로 만드는데, 이 소스의 실패로 승인 대기와 할 일까지 가리지 않기 위해서다
- 지금 반응은 그 판정의 마지막 사용자 `ACCEPTED`, `DISMISSED` 다. 숨기기(까닭 `ATTENTION_HIDE` 인 `DISMISSED`)는 지금 반응이 아니다. 숨기기는 지금 화면의 제어가 따로 가린다
- `SURFACED` 는 `SURFACE`, `ASK_APPROVAL` 판정에만 남긴다. `EXECUTE` 판정의 사건이 하나라고 단언하는 `AutonomyPolicyServiceTest` 를 깨지 않는다
- 새 후보 소스가 예외를 던지면 같은 카드(`NEEDS_ME`)의 다른 항목까지 `UNAVAILABLE` 이 된다. 읽기 서비스는 한 줄의 글이 비어도 그 줄만 빼고 나머지를 낸다
- 이유 문구는 고정 글이다. 문제 글은 `title`, 행동 글은 `problem.action` 으로만 싣는다

## 작업 항목

### 1. 설정과 오류 코드

- `proactive/application/ProactiveLoopProperties.java`: `@DefaultValue("7d") Duration surfaceWindow` 칸을 더하고 생성자에서 0 이하면 `IllegalArgumentException`
- `shared/error/ErrorCode.java`: `AUTONOMY_DECISION_NOT_FOUND(HttpStatus.NOT_FOUND)`
- `proactive/application/ProactiveLoopSettingService.java`: 설치 설정이 꺼져 있어도 이미 켠 줄을 켠 채 두는 요청(`enabled = true` 이고 저장된 줄도 `enabled = true`)은 받는다. 꺼진 줄을 켜는 요청만 `PROACTIVE_LOOP_UNAVAILABLE`

### 2. 반응 값과 읽기 모델

- `proactive/application/model/DecisionReaction.java`: enum `ACCEPTED`, `DISMISSED`. `FeedbackEventType` 짝(`eventType()`), `of(FeedbackEventType)`, 모르는 글이면 `ApiException(VALIDATION_FAILED)` 를 내는 `parse(String)`. `FindingReaction` 과 같은 모양
- `proactive/application/model/SurfacedProblem.java`: record `(Long decisionId, AutonomyLevel level, Long checkId, Long agentId, Long conversationId, String problem, String action, Instant at)`. `at` 은 판정의 `createdAt`

### 3. `proactive/application/SurfacedProblems.java`

`@Service`. 매일 루프 판정의 읽기와 반응, `SURFACED` 기록을 맡는다.

- `List<SurfacedProblem> openOf(Long userId, Instant now)`
  1. `proactive_loop_run` 에서 그 사용자의 `DECIDED` 이고 `created_at` 이 `now - surfaceWindow` 뒤인 줄의 평가 번호를 모은다. 필요한 저장소 메서드를 `ProactiveLoopRunRepository` 에 더한다
  2. 그 평가들의 판정을 모두 읽어 `(evaluationId, candidateId)` 마다 `id` 가 가장 작은 줄 하나만 남긴다. 필요한 저장소 메서드를 `AutonomyDecisionRepository` 에 더한다
  3. 그 가운데 `SURFACE`, `ASK_APPROVAL` 만 남긴다
  4. 판정의 `candidateId` 로 `proactive_check_problem` 을, `sourceCheckId` 로 `proactive_check` 를 읽어 에이전트와 점검 대화를 채운다. 후보 줄이나 살펴보기 줄이 없거나 문제 글이 비었으면 그 줄만 뺀다
  5. 같은 `problemKey` 는 `createdAt` 이 가장 늦은(같으면 `id` 가 큰) 판정 하나만 남긴다
  6. 남은 판정 가운데 지금 반응(`current`)이 있는 것을 빼고, 늦은 것부터 정렬한다
  - 1~3 의 「루프의 판정인가」 판정은 `react` 와 함께 쓰도록 private 메서드 하나로 둔다
- `Map<Long, DecisionReaction> current(Long userId, Collection<Long> decisionIds)`: `FeedbackEventRepository.findByUserIdAndSubjectKeyInOrderByOccurredAtAscIdAsc` 로 `autonomy_decision:<번호>` 사건을 읽어 마지막 `USER` 의 `ACCEPTED` 나 `DISMISSED` 를 낸다. `reasonCode` 가 `FeedbackLabeler.ATTENTION_HIDE` 인 `DISMISSED` 는 건너뛴다
- `void react(CurrentUser user, Long decisionId, DecisionReaction reaction)`: 판정이 요청자의 것이고, 수준이 `SURFACE` 나 `ASK_APPROVAL` 이고, 그 평가가 요청자의 매일 루프 시도(`DECIDED`)의 평가이고, 그 평가와 후보의 가장 이른 판정이 아니면 `ApiException(AUTONOMY_DECISION_NOT_FOUND)`. `surfaceWindow` 는 보지 않는다. 지금 반응과 같으면 아무것도 남기지 않는다. 아니면 `USER` 사건을 남긴다
- `void surfaced(AutonomyDecision decision)`: `SURFACE`, `ASK_APPROVAL` 이면 `SYSTEM` 의 `SURFACED` 를 남긴다. 아니면 아무것도 하지 않는다
- `Optional<FeedbackEntry> withOrigin(Long userId, Long decisionId, FeedbackEntry base)`: 판정이 요청자의 것이면 원천 점검 대화(`conversation`), 원천 살펴보기(`sourceCheck`), 판정(`autonomyDecision`)을 채워 돌려준다. 숨기기와 미루기 사건에 쓴다
- 사건은 모두 `DecisionFeedbackRecorder.record` 와 `FeedbackEntry.of(userId, FeedbackSubjectType.AUTONOMY_DECISION, decisionId, ...)` 로 남기고 `.conversation(...)`, `.sourceCheck(...)`, `.autonomyDecision(...)` 을 채운다. 글은 싣지 않는다

### 4. 매일 루프가 `SURFACED` 를 남긴다

`proactive/application/ProactiveLoopCoordinator.java`: `decide` 가 돌려준 판정마다 `SurfacedProblems.surfaced(...)` 를 부른다. 시도를 `DECIDED` 로 적은 뒤, 평가와 판정을 감싼 `try/catch` 밖에서 부르고 판정마다 자기 `try/catch (RuntimeException)` 로 감싸 로그만 남긴다. 그래야 `surfaced` 의 실패가 시도를 `FAILED` 로 덮어쓰지 않는다.
`feedback/domain/type/FeedbackSubjectType.java` 의 `AUTONOMY_DECISION` 주석을 「행동 정책 판정. 자동 실행을 시작하지 못한 것과 매일 루프가 보인 판정의 반응」 뜻으로 고친다.

### 5. 반응 API

- `proactive/presentation/ProactiveCheckDtos.java`: `DecisionReactionRequest(@NotBlank String reaction)`
- `proactive/presentation/AutonomyController.java`: `@PutMapping("/api/v1/autonomy-decisions/{id}/reaction")`, 204. `DecisionReaction.parse(body.reaction())` 뒤 `SurfacedProblems.react`

### 6. 지금 화면 항목

- `attention/domain/type/AttentionTrigger.java`: `PROBLEM_SURFACED` 를 `PROACTIVE_REPORT_TRIGGER` 앞에 더한다
- `attention/application/model/AttentionProblem.java`: record `(Long decisionId, String level, String action)`
- `AttentionCandidate`, `AttentionItem` 에 마지막 칸 `AttentionProblem problem` 을 더한다. 기존 16칸 생성자는 `problem = null` 로 둔다. 17칸 정식 생성자를 부르던 `ProactiveReportCandidates` 와 `backend/src/test/java/com/bifos/assistant/attention/AttentionJudgeTest.java` 를 함께 고친다
- `AttentionJudge.item(...)` 이 `problem` 을 옮겨 담는다. `AttentionDtos.ItemView` 에 `ProblemView problem`(`decisionId`, `level`, `action`)을 더한다
- `attention/application/SurfacedProblemCandidates.java`: `@Component`, `cards() = Set.of(CardKey.NEEDS_ME)`. `SurfacedProblems.openOf(user.id(), now)` 를 읽고(예외는 잡아 로그를 남기고 빈 목록), 에이전트를 찾지 못한 줄은 빼고, `OwnConversations.activeOf` 로 지운 점검 대화를 거르고, `AgentService.byIds` 로 에이전트 이름을 채운다. 후보는 `itemKey = "autonomy_decision:" + decisionId`, `stateKey = AttentionCandidates.stateKey(PROBLEM_SURFACED, decisionId.toString())`, `resolved = false`, `nowSignal = false`, `signals = List.of()`, `confidence = MODEL_INFERRED`, `title = problem`, `conversationId` 는 점검 대화의 공개 식별자, `at = SurfacedProblem.at()`, `sources = List.of(new AttentionSourceRef("AUTONOMY_DECISION", itemKey, at))`, `problem = new AttentionProblem(decisionId, level.name(), action)`
- `attention/application/SuggestionFeedback.java`: 주체 switch 에 `PROBLEM_SURFACED -> FeedbackSubjectType.AUTONOMY_DECISION`, `withOrigin` 에 `AUTONOMY_DECISION -> surfacedProblems.withOrigin(user.id(), Long.valueOf(id), base)` 를 더한다

### 7. 이 phase 를 검증하는 시험

- `backend/src/test/java/com/bifos/assistant/proactive/SurfacedProblemsTest.java` (`@BackendIntegrationTest`, `@LongProactiveCheckTimeouts`, `@OverrideProperties({"assistant.proactive-loop.enabled=true", "assistant.proactive-loop.provider=fixture-a"})`). 매일 루프는 `ProactiveLoopTestSupport` 의 방식으로 돌리거나, 시도와 판정 줄을 직접 저장한다
  - 매일 루프의 `SURFACE` 판정이 `openOf` 에 나오고 `SURFACED` 사건이 하나 남는다. `IGNORE` 판정에는 사건이 없다
  - 시도 줄이 없는 평가의 판정은 나오지 않는다
  - 같은 평가를 `AutonomyPolicyService.decide` 로 다시 판정해 생긴 줄은 나오지 않고, 그 줄에 반응하면 `AUTONOMY_DECISION_NOT_FOUND` 다. 처음 판정은 그대로 나온다
  - 같은 문제 키의 옛 시도 판정과 새 시도 판정이 있으면 새 것만 나오고, 새 것에 「관심 없음」 을 남기면 옛 것도 나오지 않는다
  - 후보 줄의 문제 글이 빈 판정이 섞여 있어도 나머지 판정은 나온다
  - `ACCEPTED` 반응 뒤 그 판정이 빠지고, 같은 반응을 다시 보내도 사건이 늘지 않는다. 까닭이 `ATTENTION_HIDE` 인 `DISMISSED` 사건만 있는 판정은 그대로 나온다
  - 남의 판정, `IGNORE` 판정, 없는 번호에 반응하면 `AUTONOMY_DECISION_NOT_FOUND`
  - `surfaceWindow` 보다 오래된 시도의 판정은 나오지 않는다
- `backend/src/test/java/com/bifos/assistant/attention/SurfacedProblemCandidatesTest.java` (`@BackendIntegrationTest`, 위와 같은 덮어쓰기): `AttentionService` 응답의 `needs_me` 카드에 `PROBLEM_SURFACED` 항목이 `LATER` 로 나오고 카드 `nowCount` 가 늘지 않는다. `problem.action` 과 `title` 이 후보 글과 같다. 할 일 제안 하나와 문제 글이 빈 후보의 판정 하나가 함께 있어도 `needs_me` 가 `OK` 이고 할 일 제안과 나머지 판정 항목이 남는다. 시험은 컨텍스트를 나누는 `@MockitoSpyBean` 을 쓰지 못하므로 소스 전체 예외는 단언하지 않고 줄 단위 건너뛰기로 확인한다. 지금 화면에서 숨기면 `autonomy_decision:<번호>` 에 `DISMISSED`/`ATTENTION_HIDE` 사건이 원천 살펴보기와 함께 남고, 미루면 `POSTPONED` 가 남는다
- `backend/src/test/java/com/bifos/assistant/proactive/ProactiveLoopSettingDisabledTest.java`: 이미 켠 줄을 직접 저장해 두고 설치가 꺼진 채 `enabled = true` 와 쉬기를 보내면 받는다. 꺼진 줄을 켜면 `PROACTIVE_LOOP_UNAVAILABLE`
- `backend/src/test/java/com/bifos/assistant/attention/AttentionJudgeTest.java`: 바뀐 생성자에 맞춘다

## 검증

```bash
(cd backend && ./gradlew test --tests '*SurfacedProblemsTest' --tests '*SurfacedProblemCandidatesTest' --tests '*ProactiveLoopSettingDisabledTest' --tests '*ProactiveLoopSettingTest' --tests '*ProactiveLoopCoordinatorTest' --tests '*AutonomyPolicyServiceTest' --tests 'com.bifos.assistant.attention.*' --tests '*DecisionFeedbackFlowTest' --tests 'com.bifos.assistant.architecture.*')
(cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck)
```

새 시험이 통과하고 attention 패키지 시험과 `AutonomyPolicyServiceTest` 가 그대로 통과한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveLoopProperties.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveLoopSettingService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/model/DecisionReaction.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/model/SurfacedProblem.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/SurfacedProblems.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveLoopCoordinator.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/infra/ProactiveLoopRunRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/infra/AutonomyDecisionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/feedback/domain/type/FeedbackSubjectType.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/presentation/ProactiveCheckDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/presentation/AutonomyController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/attention/domain/type/AttentionTrigger.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/attention/application/model/AttentionProblem.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/application/model/AttentionCandidate.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/attention/application/model/AttentionItem.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/attention/application/AttentionJudge.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/attention/presentation/AttentionDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/attention/application/SurfacedProblemCandidates.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/application/SuggestionFeedback.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/attention/application/ProactiveReportCandidates.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/proactive/SurfacedProblemsTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/attention/SurfacedProblemCandidatesTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/proactive/ProactiveLoopSettingDisabledTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/attention/AttentionJudgeTest.java` | 수정 |
