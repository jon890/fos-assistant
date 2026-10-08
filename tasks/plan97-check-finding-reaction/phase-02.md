# Phase 02. 반응을 다음 살펴보기의 입력과 되풀이 판정에 싣는다

**Execution profile**: standard

## 목표

최근에 알린 발견 목록에 지금 반응을 싣고, 지금 반응이 「관심 없음」 인 주제 키를 `digest-window` 동안 `REPEATED` 로 내린다.

**범위 외**: 반응 API(phase 01), 화면(phase 03).

## 컨텍스트

**근거 문서**: `docs/backend/proactive-check.md` 의 「살펴보기가 읽는 맥락」, 「Control Plane 지시」, 「검사」(순서 8), `docs/adr/ADR-20261008-check-finding-reaction.md`.

- phase 01 의 `CheckFindingReactions.current(Long userId, Collection<Long> findingIds)` 가 발견마다 지금 반응을 준다.
- `proactive/application/ProactiveCheckRun.java` 의 `record Deps(...)` 가 입력과 결과 조립의 의존이다. `ProactiveCheckService` 끝의 `new ProactiveCheckRun.Deps(...)` 가 만든다.
- `ProactiveCheckInput.recentFindings(Instant)` 가 최근 발견 줄을 만들고, `ProactiveCheckResults.announcedSince(Instant)` 가 되풀이 판정의 묶음을 만든다. 두 곳 모두 같은 점검 대화의 `NEW` 발견을 `digest-window` 로 읽는다.
- `FindingJudgement.judge(Finding, Instant, Instant, Set<AnnouncedKey>)` 가 검사 순서대로 첫 까닭을 준다. 시험의 부르는 곳: `FindingJudgementTest`, `CheckAnswerRendererTest`, `ProblemJudgementTest`, `CheckReportFactoryTest`.
- 지시 글은 `ProactiveCheckInstructions.COMMON_RULES` 다.

## 의도 메모

- 「관심 없음」 은 원문 주소와 `changeSinceLast` 를 보지 않는다. 사용자가 다시 보고 싶으면 다른 단추로 바꾼다.
- 기간이 지나면 다시 알린다. 그래서 같은 `digest-window` 로 읽는다.
- 반응 줄의 글은 Control Plane 이 정한 낱말이지만, 같은 줄의 제목이 모델 글이라 지금처럼 `<external-data>` 안에 둔다.

## 작업 항목

### 1. `proactive/application/FindingJudgement.java`

`judge(Finding finding, Instant checkStartedAt, Instant now, Set<AnnouncedKey> alreadyAnnounced, Set<String> dismissedTopics)` 로 바꾼다. `isRepeated` 는 지금 조건이거나, `topicKey` 가 비지 않고 `dismissedTopics.contains(topicKey)` 일 때 참이다. Javadoc 에 순서 8 을 적는다. 시험의 부르는 곳은 `Set.of()` 를 더한다.

### 2. `proactive/application/ProactiveCheckRun.java`, `ProactiveCheckService.java`

`Deps` 에 `CheckFindingReactions reactions` 를 더하고 서비스가 넘긴다.

### 3. `proactive/application/ProactiveCheckResults.java`

`announcedSince` 가 읽은 발견으로 `dismissedTopics` 도 만든다. `deps.reactions().current(owner.id(), ids)` 가 `DISMISSED` 인 발견의 비지 않은 `topicKey` 다. 발견 목록 읽기는 한 번만 한다.

### 4. `proactive/application/ProactiveCheckInput.java`

줄 끝에 ` · 반응 ` 과 `받아들임`, `나중에`, `관심 없음`, `없음` 가운데 하나를 붙인다. 한 번의 `current` 호출로 읽는다.

### 5. `proactive/application/ProactiveCheckInstructions.java`

`COMMON_RULES` 의 「최근에 알린 발견을 같은 근거로…」 줄 아래에 `- 최근에 알린 발견의 반응이 「관심 없음」 이면 그 주제를 다시 조사하거나 알리지 않는다.` 를 더한다.

### 6. 이 phase 를 검증하는 시험

- `FindingJudgementTest`: 「관심 없음」 주제와 같은 `topicKey` 는 원문 주소가 달라도, `changeSinceLast` 가 있어도 `REPEATED` 다. 다른 주제는 `NEW` 다.
- `CheckFindingReactionsTest` 에 통합 사례 하나를 더한다: 「관심 없음」 반응을 남긴 뒤 같은 점검 대화의 다음 살펴보기 입력에 `반응 관심 없음` 이 실리고, 같은 주제의 다른 원문 발견이 `REFERENCE`, `REPEATED` 로 저장된다. 다음 살펴보기는 `ProactiveCheckTurnTest` 가 대역 Hermes 로 살펴보기를 돌리는 방식을 따른다. 그 방식이 이 클래스에 맞지 않으면 사례를 `ProactiveCheckTurnTest` 에 둔다.
- `test/e2e/scenarios/proactive-check.ts`: 「맥락 반영」 단계 뒤에 단계 하나를 더한다. `GET /chat/conversations/{id}/check-findings` 에서 `WEB_ONLY` 의 마지막 발견을 찾아 `PUT /check-findings/{id}/reaction` 으로 `DISMISSED` 를 보내 204, 다른 사용자(`context.tokens.kid`)의 같은 요청은 404. 그 뒤 `WEB_ONLY.topicKey` 와 다른 주소, `changeSinceLast` 를 가진 발견으로 살펴보기를 돌리면 답에 `- {제목}: 이미 알린 것이에요` 가 있고, 입력에 `· 반응 관심 없음` 이 있다. `/decision-feedback/export` 의 `subjects` 에 그 열쇠가 `DECLINED` 로 있다.

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.proactive.*'
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck
```

기대값: backend 시험 통과. e2e 시나리오는 push 전 `scripts/check-local.sh` 의 e2e 단계와 PR 의 CI 가 돌린다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/proactive/application/FindingJudgement.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveCheckRun.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveCheckService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveCheckResults.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveCheckInput.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveCheckInstructions.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/proactive/FindingJudgementTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/proactive/CheckAnswerRendererTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/proactive/ProblemJudgementTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/proactive/CheckReportFactoryTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/proactive/CheckFindingReactionsTest.java` | 수정 |
| `test/e2e/scenarios/proactive-check.ts` | 수정 |
