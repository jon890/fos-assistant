# Phase 01. 발견 반응을 판단 피드백 사건으로 받는 API

**Execution profile**: standard

## 목표

점검 대화의 「새로 알릴 것」 발견에 사용자가 「받아들임」, 「나중에」, 「관심 없음」 으로 반응하면 `decision_feedback_event` 에 `check_finding:<발견 번호>` 사건으로 남기고, 발견 목록과 지금 반응을 읽는 API 를 낸다.

**범위 외**: 반응을 입력과 되풀이 판정에 쓰는 일(phase 02), 화면(phase 03).

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261008-check-finding-reaction.md`, `docs/backend/proactive-check.md` 의 「발견 반응」 과 진입점 표, `docs/backend/decision-feedback.md` 의 「기록 지점」 과 「살펴보기 발견의 지금 반응」, `docs/backend/schema/feedback.md`.

- 사건 기록은 `backend/src/main/java/com/bifos/assistant/feedback/application/DecisionFeedbackRecorder.java` 의 `record(FeedbackEntry)` 를 쓴다. 트랜잭션 밖에서 부르면 바로 쓴다.
- 사건 읽기는 `FeedbackEventRepository.findByUserIdAndSubjectKeyInOrderByOccurredAtAscIdAsc(Long, Collection<String>)` 를 쓴다.
- 대화 소유 확인은 `chat.application.ConversationAccess.requireOwnId(CurrentUser, UUID)` 다. 남의 대화는 `CONVERSATION_NOT_FOUND` 404 다. 같은 모양의 경로는 `chat.presentation.MemoryCaptureController` 를 본다.
- 살펴보기 줄은 `ProactiveCheck` 의 `userId()`, `conversationId()`, `rootExecutionId()`, `id()` 를 쓴다. 발견은 `ProactiveCheckFinding` 의 `id()`, `checkId()`, `kind()`, `area()`, `topicKey()`(null 가능), `title()` 이다.
- `digest-window` 는 `ProactiveCheckProperties.digestWindow()`(`Duration`)이고 `LiveProperties<ProactiveCheckProperties>.current()` 로 읽는다.
- 본문의 모르는 enum 값은 전역 처리기가 400 으로 바꾸지 않는다(`HttpMessageNotReadableException` 처리기가 없다). 그래서 요청 칸은 문자열로 받고 서비스가 `VALIDATION_FAILED` 로 거절한다.

## 의도 메모

- 지금 반응은 마지막 사용자 사건이다. replay 의 첫 반응 규칙(`FeedbackLabeler`)은 고치지 않는다.
- 같은 단추를 다시 누르면 사건을 남기지 않는다. 두 번 누름이 겹쳐 같은 사건이 둘 남는 것은 받아들인다. 반응 읽기가 마지막 사건만 보기 때문이다.
- 오류 코드를 새로 만들지 않고 `PROACTIVE_CHECK_NOT_FOUND` 를 쓴다.
- 「받아들임」 은 할 일과 Memory 를 만들지 않는다.

## 작업 항목

### 1. `feedback/domain/type/FeedbackSubjectType.java`

`CHECK_FINDING("check_finding")` 을 `CHECK` 다음에 더한다. Javadoc: 살펴보기의 「새로 알릴 것」 발견 하나, 열쇠 `check_finding:<번호>`.

### 2. `proactive/application/model/FindingReaction.java` 신규

저장하지 않는 enum `ACCEPTED`, `POSTPONED`, `DISMISSED`. `FeedbackEventType eventType()` 로 같은 이름의 사건을 준다. `static FindingReaction of(FeedbackEventType)` 은 셋이 아니면 null.
`static FindingReaction parse(String)` 은 null 이거나 모르는 값이면 `ApiException(ErrorCode.VALIDATION_FAILED, "unknown reaction")` 을 던진다.

### 3. `proactive/application/model/CheckFindingView.java` 신규

`record CheckFindingView(Long id, Long checkId, Long executionId, String area, String topicKey, String title, FindingReaction reaction)`. `executionId` 는 그 살펴보기의 `rootExecutionId` 다.

### 4. `proactive/infra/ProactiveCheckFindingRepository.java`

- `List<ProactiveCheckFinding> findByConversationIdAndKindOrderByIdAsc(Long conversationId, FindingKind kind)`
- `List<ProactiveCheckFinding> findByCheckIdAndKind(Long checkId, FindingKind kind)`

### 5. `proactive/application/CheckFindingReactions.java` 신규 (`@Component`)

의존: `ProactiveCheckFindingRepository`, `ProactiveCheckRepository`, `FeedbackEventRepository`, `DecisionFeedbackRecorder`, `ConversationAccess`, `LiveProperties<ProactiveCheckProperties>`, `Clock`.

- `List<CheckFindingView> list(CurrentUser user, UUID conversationId)`: 먼저 `conversations.requireOwnId(user, conversationId)` 로 대화 번호를 얻는다(남의 대화는 `CONVERSATION_NOT_FOUND`). 그 대화의 `NEW` 발견을 번호 순으로 읽고, 요청자의 살펴보기(`Objects.equals(check.userId(), user.id())`, 둘 다 `Long`)가 낸 것만 남겨 지금 반응과 함께 낸다.
- `long dismissWindowDays()`: `digestWindow` 를 하루 단위로 올림한 값. 1 보다 작으면 1.
- `void react(CurrentUser user, Long findingId, FindingReaction reaction)`: 발견이 없거나 `NEW` 가 아니거나 그 살펴보기가 요청자의 것이 아니면(`Objects.equals` 로 견준다) `ApiException(ErrorCode.PROACTIVE_CHECK_NOT_FOUND, "no such check finding")`. 지금 반응과 같으면 아무것도 하지 않는다. 아니면 `FeedbackEntry.of(user.id(), CHECK_FINDING, findingId, reaction.eventType(), USER, clock.instant()).conversation(check.conversationId()).originExecution(check.rootExecutionId()).sourceCheck(check.id())` 를 `record` 한다.
- `Map<Long, FindingReaction> current(Long userId, Collection<Long> findingIds)`: 열쇠 목록으로 사건을 읽고 발견마다 `actor == USER` 인 마지막 `ACCEPTED`, `POSTPONED`, `DISMISSED` 를 준다. 비면 빈 맵. phase 02 도 쓴다.
- `void surfaced(ProactiveCheck check)`: 그 살펴보기의 `NEW` 발견마다 `SURFACED` 를 `SYSTEM` 으로 같은 연결 칸과 함께 남긴다.

### 6. `proactive/application/CheckFeedback.java`

`ended` 가 보고를 보인 살펴보기의 `SURFACED` 를 남긴 직후 `reactions.surfaced(check)` 를 부른다. `AUTONOMY` 는 부르지 않는다. 생성자 의존에 `CheckFindingReactions` 를 더한다.

### 7. `proactive/presentation/CheckFindingController.java` 신규, `ProactiveCheckDtos.java`

- `GET /api/v1/chat/conversations/{conversationId}/check-findings` (`UUID` 경로) → `CheckFindingsResponse(long dismissWindowDays, List<CheckFindingView> findings)`. 소유 확인은 서비스 `list` 가 한다.
- `PUT /api/v1/check-findings/{findingId}/reaction` 본문 `@Valid @RequestBody FindingReactionRequest(@NotBlank String reaction)`, `@ResponseStatus(HttpStatus.NO_CONTENT)` → 204. 본보기는 `ProactiveCheckReportController.open` 이다.
- 두 record 는 `ProactiveCheckDtos` 에 둔다(`ArchitectureRules.CONTROLLERS_HAVE_NO_NESTED_RECORDS`). 클래스 Javadoc 의 「실행 번호…는 싣지 않는다」 에 예외 한 줄을 더한다: 발견 목록의 `executionId` 는 답 메시지에 이미 보이는 실행 번호이고 답 아래 자리를 정하는 데만 쓴다(`ChatDtos.MemoryCaptureView` 와 같다).

### 8. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/proactive/CheckFindingReactionsTest.java` 신규

`DecisionFeedbackFlowTest` 처럼 실제 DB 를 쓰는 통합 시험으로 만든다. 살펴보기 줄과 `NEW`, `REFERENCE` 발견을 직접 저장해 준비한다.

- 「관심 없음」 을 누르면 `check_finding:<번호>` `DISMISSED` 사건이 `USER`, 점검 대화, 살펴보기 번호와 함께 남고 목록의 반응이 `DISMISSED` 다
- 같은 단추를 다시 누르면 사건이 늘지 않고, 「나중에」 로 바꾸면 지금 반응이 `POSTPONED` 다
- 남의 발견과 `REFERENCE` 발견은 `PROACTIVE_CHECK_NOT_FOUND`, 모르는 값은 `VALIDATION_FAILED` 다
- `list` 는 남의 대화 공개 식별자를 `CONVERSATION_NOT_FOUND` 로 거절하고 `REFERENCE` 발견을 싣지 않는다
- 사용자 번호가 127 보다 큰 경우에도 주인 판정이 맞는다(`Long` 값 비교)
- `surfaced` 가 `NEW` 발견마다 `SURFACED` 를 남긴다

`DecisionFeedbackFlowTest` 는 고치지 않는다. 발견 줄을 만드는 시험이 없어 사건 수가 바뀌지 않는다. 검증 명령으로 회귀만 본다.

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.proactive.CheckFindingReactionsTest' --tests 'com.bifos.assistant.proactive.DecisionFeedbackFlowTest' --tests 'com.bifos.assistant.architecture.*'
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck
```

기대값: 모두 통과.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/feedback/domain/type/FeedbackSubjectType.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/model/FindingReaction.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/model/CheckFindingView.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/infra/ProactiveCheckFindingRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/CheckFindingReactions.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/CheckFeedback.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/presentation/CheckFindingController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/presentation/ProactiveCheckDtos.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/proactive/CheckFindingReactionsTest.java` | 신규 |
