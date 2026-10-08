# Phase 01. 관리자 화면이 읽는 가치 평가 묶음 API

**Execution profile**: standard

## 목표

관리자가 그 에이전트로 연, 받아들인 문제 후보가 있는 마지막 살펴보기와 그 평가, 판정을 한 번에 읽는 GET 과, 평가 뒤 곧바로 판정하는 POST 를 관리자 경로로 만든다.
운영에서 사람이 살펴보기 한 건의 가치 평가와 행동 정책을 돌리고 다시 읽을 자리가 없어 기록이 0건이다.

**범위 외**: 웹 프록시와 화면(phase 02). 설치 설정의 기본값은 바꾸지 않는다(`assistant.value-evaluation.enabled`, `assistant.autonomy.execution-enabled` 모두 `false` 유지).

## 컨텍스트

**근거 문서**: `docs/backend/value-evaluation.md` 의 「API와 다음 행동 정책의 입력」 과 「관리자 화면이 읽는 묶음」, `docs/backend/autonomy-policy.md` 의 「API」

- 평가: `ValueEvaluationService.evaluate(CurrentUser, Long checkId, String providerId)` 가 `ValueEvaluation` 을 준다. 끝난 살펴보기가 아니면 `VALUE_EVALUATION_STATE_CONFLICT`, 남의 것이거나 점검 대화를 지웠으면 `VALUE_EVALUATION_NOT_FOUND` 다
- 판정: `AutonomyPolicyService.decide(CurrentUser, Long evaluationId)` 가 `List<AutonomyDecision>` 을 준다. 한 번 부를 때 남긴 줄은 모두 같은 `createdAt` 이다(`facts.now()`)
- 에이전트 확인: `ProactiveCheckService.status` 처럼 `AgentService.requireStartable(CurrentUser, String code)` 를 쓴다
- 점검 대화가 남았는지: `ConversationRepository.findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId)`
- 받아들인 후보: `ProactiveCheckProblemRepository.findByCheckIdAndStatusOrderByIdAsc(checkId, ProblemStatus.ACCEPTED)`
- 판정 읽기: `AutonomyDecisionRepository.findByUserIdAndEvaluationIdInOrderByIdAsc(Long userId, Collection<Long> evaluationIds)`
- 관리자 확인: `CurrentUserProvider.requireAdmin()` 이 `ADMIN` 이 아니면 `FORBIDDEN` 을 던진다. 본보기는 `attention/presentation/AttentionAdminController.java`
- 응답 모양은 `presentation/ProactiveCheckDtos.java` 하나에 둔다(`ArchitectureRules.CONTROLLERS_HAVE_NO_NESTED_RECORDS`). `application` 은 타입 하나에 파일 하나다
- 시험은 `@BackendIntegrationTest` 를 쓰고, 기본 설정에서 `HermesDecisionProvider` 는 `enabled=false` 라 Hermes 를 부르지 않고 `FALLBACK / PROVIDER_UNAVAILABLE` 을 낸다. 그 평가의 후보는 행동 정책에서 `IGNORE` 와 `EVALUATION_NOT_USABLE` 을 받는다. 준비 데이터 모양은 `AutonomyPolicyServiceTest` 의 `succeeded`, `candidate` 보조 메서드를 본다

## 의도 메모

- 웹이 평가 POST 와 판정 POST 를 이어 부르는 안은 버렸다. 중간 실패를 화면이 따로 다뤄야 하고 프록시도 둘로 늘어난다
- 평가와 판정은 기존 서비스를 그대로 부른다. 검사와 기록, 실행 한도를 새로 만들지 않는다
- 자동 실행(`AUTONOMY`)이 연 살펴보기는 고르지 않는다. 사람에게 바로 보이지 않는 결과다
- `status = SUCCEEDED` 만으로 고르지 않는다. `ProactiveCheck.skip()` 과 `succeedInvalid()` 도 `SUCCEEDED` 를 남겨, 읽지 않은 보고가 있을 때 매일 깨우기가 만든 후보 0개 줄이 평가할 줄을 가린다. 받아들인 후보가 있는 줄만 고른다
- 경로는 `/api/v1/admin/` 아래에 두고 `requireAdmin()` 을 부른다. 다루는 자료는 관리자 본인의 것뿐이다. 판단 profile 을 부르는 비용을 관리자 화면 밖으로 넓히지 않는다. 기존 `POST /api/v1/proactive-checks/{checkId}/value-evaluations` 는 그대로 둔다
- 응답에 실행 식별자, provider·모델 원문, 판정 입력 원문, 시작한 살펴보기 식별자를 싣지 않는다

## 작업 항목

### 1. `ProactiveCheckRepository`, `ValueEvaluationRepository` 에 조회 메서드

- `ProactiveCheckRepository` 에 `@Query` 메서드 `List<ProactiveCheck> findEvaluable(Long userId, Long agentId, Pageable page)`. JPQL 조건: `c.userId = :userId`, `c.agentId = :agentId`, `c.status = SUCCEEDED`, `c.trigger <> AUTONOMY`, `exists (select p.id from ProactiveCheckProblem p where p.checkId = c.id and p.status = ACCEPTED)`, `exists (select v.id from Conversation v where v.id = c.conversationId and v.userId = :userId and v.deletedAt is null)`, `order by c.id desc`. enum 값은 매개변수로 넘기거나 JPQL 의 완전한 enum 이름으로 쓴다. 호출할 때 `PageRequest.of(0, 1)`
- `Optional<ValueEvaluation> findFirstByUserIdAndCheckIdOrderByIdDesc(Long userId, Long checkId)`
- 각각 한국어 Javadoc 한 줄. `RepositoryQueryMysqlTest` 가 스스로 찾으므로 따로 할 일은 없다

### 2. `proactive/application/model/EvaluationOverview.java` 신규

`record EvaluationOverview(ProactiveCheck check, int acceptedCandidates, ValueEvaluation evaluation, List<AutonomyDecision> decisions)`.
`check` 와 `evaluation` 은 null 일 수 있다. `static EvaluationOverview none()` 은 셋 다 비운 값이다.

### 3. `proactive/application/ValueEvaluationOverviews.java` 신규

- `EvaluationOverview latest(CurrentUser user, String agentCode)`: `requireStartable` 로 에이전트를 확인하고, 위 1의 `findEvaluable` 로 마지막 살펴보기를 읽는다. 없으면 `none()`. 마지막 평가를 읽고, 있으면 그 평가의 판정 가운데 `createdAt` 이 가장 늦은 줄들만 남긴다
- `EvaluationOverview run(CurrentUser user, Long checkId, String providerId)`: `evaluate` 뒤 `decide` 를 부르고, 그 살펴보기와 받아들인 후보 수, 방금 평가와 판정으로 묶음을 만든다. 살펴보기는 `ProactiveCheckRepository.findByIdAndUserId` 로 읽는다(평가가 이미 확인했다)
- 트랜잭션을 길게 열지 않는다. 평가와 판정이 각자 짧은 트랜잭션을 쓴다

### 4. `ProactiveCheckDtos.java` 응답 모양

- `EvaluationResponse` 에 `DecisionFailure failure` 와 `List<CandidateView> candidates` 를 더한다. `candidates` 는 `evidence().state().candidates()` 에서 만든다
- `CandidateView(Long id, String problemKey, String problem, String actionType, String sideEffect)`
- `CheckSummaryView(Long id, CheckTrigger trigger, Instant finishedAt, int acceptedCandidates)`
- `EvaluationOverviewResponse(CheckSummaryView check, EvaluationResponse evaluation, List<AutonomyDecisionResponse> decisions)` 와 `static from(EvaluationOverview)`

### 5. `proactive/presentation/ValueEvaluationAdminController.java` 신규

두 메서드 모두 맨 앞에서 `currentUser.requireAdmin()` 을 부르고 그 사용자로 서비스를 부른다.

- `GET /api/v1/admin/agents/{code}/value-evaluation` → `EvaluationOverviewResponse`
- `POST /api/v1/admin/proactive-checks/{checkId}/value-evaluation-runs` 본문 `EvaluationRequest` → 201 `EvaluationOverviewResponse`

`AutonomyController` 의 클래스 Javadoc 「화면은 아직 없다」 를 「관리자 영역의 가치 평가 절이 결과를 읽는다」 로 고친다

### 6. `backend/src/test/java/com/bifos/assistant/proactive/ValueEvaluationOverviewTest.java` 신규

`@BackendIntegrationTest`. `MockMvcBuilders.standaloneSetup(new ValueEvaluationAdminController(...))` 와 `GlobalExceptionHandler` 로 HTTP 모양까지 본다(`ProactiveCheckStatusTest` 방식).

- 시험 사용자 번호는 다른 검사와 겹치지 않는 값(`960_001L` 대)을 쓴다. 관리자 `ADMIN` 사용자와 다른 사용자를 둔다
- 고를 살펴보기가 없으면 GET 이 `check`, `evaluation` 이 null 이고 `decisions` 가 빈 배열이다
- 받아들인 후보 하나가 있는 `MANUAL` 살펴보기에 POST 하면 201, `evaluation.outcome = FALLBACK`, `evaluation.failure = PROVIDER_UNAVAILABLE`, `evaluation.candidates[0].problem` 이 준비한 글, `decisions[0].level = IGNORE`, `decisions[0].reasons` 에 `EVALUATION_NOT_USABLE`. 이어 GET 이 같은 평가 식별자와 판정을 준다
- 받아들인 후보가 있는 더 늦은 `AUTONOMY` 살펴보기와, 후보 없이 `skip(CheckSkippedReason.UNREAD_REPORT, ...)` 로 끝난 더 늦은 `SCHEDULED` 살펴보기가 있어도 GET 은 앞의 `MANUAL` 살펴보기를 고른다
- 그 살펴보기의 점검 대화를 지우면(`deletedAt` 설정) GET 의 `check` 가 null 이다
- `MEMBER` 사용자가 두 경로를 부르면 403 `FORBIDDEN`
- 같은 평가를 두 번 판정하면 GET 은 뒤 묶음만 준다
- 다른 사용자의 살펴보기에 POST 하면 404 `VALUE_EVALUATION_NOT_FOUND`
- `@AfterEach` 에서 만든 판정, 평가, 후보, 살펴보기, 대화, 에이전트를 지운다

## 검증

```bash
# cwd: 저장소 root
(cd backend && ./gradlew test --tests 'com.bifos.assistant.proactive.ValueEvaluationOverviewTest' --tests 'com.bifos.assistant.proactive.AutonomyPolicyServiceTest' --tests 'com.bifos.assistant.proactive.ValueEvaluationStoreTest')
(cd backend && ./gradlew archTest checkstyleMain checkstyleTest spotlessCheck)
```

모두 종료 코드 0 이어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/proactive/infra/ProactiveCheckRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/infra/ValueEvaluationRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/model/EvaluationOverview.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/ValueEvaluationOverviews.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/presentation/ProactiveCheckDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/presentation/ValueEvaluationAdminController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/presentation/AutonomyController.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/proactive/ValueEvaluationOverviewTest.java` | 신규 |
