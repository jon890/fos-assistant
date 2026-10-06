# Phase 01. 문제 후보의 계약, 검사, 저장 모델

**Execution profile**: standard

## 목표

결과 블록 버전 3의 `problemCandidates` 를 읽고, 후보마다 `ACCEPTED` 와 `DROPPED` 를 결정적으로 정하는 순수 함수와 저장 모델을 만든다.
살펴보기 turn 에 잇는 일은 phase 02 가 한다.

**범위 외**: `ProactiveCheckRun` 과 `ProactiveCheckService` 의 변경, 지시와 입력 글, E2E 시나리오(phase 02).

## 컨텍스트

**근거 문서**: `docs/backend/proactive-check.md` 의 「결과 계약」 과 「문제 후보」 절, `docs/backend/schema/proactive.md` 의 `proactive_check_problem` 절,
`docs/adr/ADR-093-문제-찾기는-살펴보기-결과의-문제-후보로-받고-control-plane-이-근거와-중복을-결정적으로-검사한다.md`.

따를 기존 패턴:

- 블록 읽기: `backend/src/main/java/com/bifos/assistant/proactive/application/CheckResultParser.java` 의 `finding`, `next`, `text`, `texts`
- 결정적 검사: `backend/src/main/java/com/bifos/assistant/proactive/application/FindingJudgement.java`
- 엔티티와 잘라 넣기: `backend/src/main/java/com/bifos/assistant/proactive/domain/ProactiveCheckFinding.java`
- JSON 칸: `backend/src/main/java/com/bifos/assistant/proactive/domain/CheckReportJsonConverter.java`
- 할 일 제목 열쇠: `FollowUpService.titleKey`, `FollowUpRepository.findByUserIdAndTitleKeyAndOpenMarker`, `FollowUp.OPEN_MARKER`

패키지 순서상 `proactive`(12)는 `followup`(11)을 쓸 수 있다. `followup.infra` 를 직접 쓰지 말고 `FollowUpService` 에 메서드를 더한다.

## 의도 메모

- 신선도는 모델 칸을 받지 않는다. 근거 발견의 `checkedAt` 가운데 가장 이른 것을 쓴다.
- 우선순위 점수, 실행 비용 칸은 두지 않는다(#214 의 몫).
- `title_key` 정확 일치만으로 할 일 중복을 판정하는 한계는 ADR-093 「감당할 것」 에 적혀 있다.
- 마이그레이션 번호는 지금 `V82` 로 둔다. 머지 직전 main 의 다음 번호로 옮긴다. 테스트는 번호 대신 설명(`proactive check problem`)으로 마이그레이션을 찾는다.

## 작업 항목

### 1. `CheckResultBlock` 에 후보 레코드를 더한다

`backend/src/main/java/com/bifos/assistant/proactive/application/model/CheckResultBlock.java`

- 끝 칸으로 `List<ProblemCandidate> problemCandidates` 를 더한다.
- `record ProblemCandidate(String problemKey, String problem, String relatedGoal, List<String> evidence, Next proposedAction, String confidence, String expectedBenefit, String sideEffect, String risk, String changeSinceLast)`.

### 2. `CheckResultParser` 가 버전 3을 읽는다

- `version` 1, 2, 3을 받는다. 다른 값은 지금처럼 `BAD_VERSION`.
- `report` 는 버전 2와 3에서 읽는다.
- `problemCandidates` 는 버전 3에서만 읽고, 1과 2는 빈 목록이다. 3개까지, 객체가 아닌 원소는 건너뛴다.
- 상한: `problemKey` 120, `problem` 300, `relatedGoal` 200, `evidence` 5개 각 120, `proposedAction.text` 200, `expectedBenefit` 300, `risk` 200, `changeSinceLast` 300, `confidence`·`sideEffect`·`proposedAction.type` 은 `SHORT_FIELD_MAX`.
- 클래스 Javadoc 의 「1이나 2」 를 「1, 2, 3」 으로 고친다.
- 이 블록을 만드는 다른 곳(`new CheckResultBlock(`)을 `git grep` 으로 찾아 새 칸을 채운다. 테스트도 같다.

### 3. 후보 검사의 타입

`backend/src/main/java/com/bifos/assistant/proactive/domain/type/` 에 신규 enum 셋:

- `ProblemStatus { ACCEPTED, DROPPED }`
- `ProblemDropReason { INCOMPLETE, NO_GOAL, NO_EVIDENCE, DUPLICATE, EXISTING_FOLLOW_UP }`
- 값 목록 `ACTION`, `QUESTION`, `LOW`, `MEDIUM`, `HIGH`, `NONE`, `INTERNAL`, `EXTERNAL` 은 `ProblemJudgement` 안의 상수 `Set<String>` 으로 둔다. 저장 칸은 글이다(정해진 값 밖도 잘라 남긴다).

`backend/src/main/java/com/bifos/assistant/proactive/application/model/` 에 신규:

- `JudgedProblem(CheckResultBlock.ProblemCandidate candidate, String problemKey, ProblemStatus status, ProblemDropReason reason, List<ProblemEvidence> evidence, Instant evidenceCheckedAt)`. `ProblemEvidence` 는 6의 domain 레코드다. `problemKey` 는 정규화한 키(앞뒤 공백 제거, `Locale.ROOT` 소문자, 없으면 `""`).

### 4. `ProblemJudgement`

`backend/src/main/java/com/bifos/assistant/proactive/application/ProblemJudgement.java` 신규. `FindingJudgement` 처럼 상태 없는 final 클래스.

```java
public static List<JudgedProblem> judge(
        List<CheckResultBlock.ProblemCandidate> candidates,
        List<JudgedFinding> findings,
        Set<String> acceptedProblemKeys,
        Predicate<String> openFollowUpTitle)
```

- `findings` 가 비면 빈 목록을 돌려준다(후보를 검사하지도 남기지도 않는다).
- 근거로 쓸 수 있는 발견: `kind == NEW`, 또는 `kind == REFERENCE` 이고 `reason == REPEATED`. 그 발견의 `finding().topicKey()` 로 찾는다. 같은 주제 키가 여럿이면 앞의 것.
- 후보마다 순서대로 검사하고 처음 걸린 까닭 하나를 남긴다. 순서와 조건은 `docs/backend/proactive-check.md` 「후보 검사」 표와 같다.
  `DUPLICATE` 의 블록 안 판정은 앞서 `ACCEPTED` 된 후보의 키만 센다. `acceptedProblemKeys` 는 부르는 쪽이 정규화해 넘긴 이전 키다. 블록 안 중복은 `changeSinceLast` 가 있어도 `DUPLICATE` 다.
- `EXISTING_FOLLOW_UP` 은 `openFollowUpTitle.test(proposedAction.text)` 가 참일 때다.
- `ACCEPTED` 의 `evidence` 는 찾은 발견마다 `ProblemEvidence(topicKey, judged.sourceUrl(), judged.checkedAt())`, `evidenceCheckedAt` 은 그 `checkedAt` 의 최솟값이다.
  `DROPPED` 도 찾은 근거가 있으면 같은 방식으로 채운다(없으면 빈 목록과 null).

### 5. `FollowUpService.hasOpenWithTitle`

`backend/src/main/java/com/bifos/assistant/followup/application/FollowUpService.java` 에 더한다.

```java
@Transactional(readOnly = true)
public boolean hasOpenWithTitle(Long userId, String title)
```

제목이 null 이거나 비면 거짓. 아니면 `titleKey(title.strip())` 로 `findByUserIdAndTitleKeyAndOpenMarker(userId, key, FollowUp.OPEN_MARKER).isPresent()`.

### 6. 저장 모델

- `backend/src/main/resources/db/migration/V82__proactive_check_problem.sql` 신규. 칸과 색인은 `docs/backend/schema/proactive.md` 의 `proactive_check_problem` 표와 같다.
  `evidence_json JSON NOT NULL`, FK `fk_proactive_check_problem_check` 는 `proactive_check(id) ON DELETE CASCADE`, 색인 `idx_proactive_check_problem_conversation_status_created (conversation_id, status, created_at)`.
- `backend/src/main/java/com/bifos/assistant/proactive/domain/ProactiveCheckProblem.java` 신규 엔티티. `ProactiveCheckFinding` 처럼 정적 팩터리가 칸 길이로 잘라 넣는다.
  domain 이 application.model 을 import 하지 않도록 팩터리 인자는 원시 값으로 받는다: `of(Long checkId, Long conversationId, ProblemStatus status, ProblemDropReason reason, String problemKey, String problem, String relatedGoal, String actionType, String actionText, String confidence, String expectedBenefit, String sideEffect, String risk, String changeSinceLast, List<ProblemEvidence> evidence, Instant evidenceCheckedAt, Instant now)`.
- `backend/src/main/java/com/bifos/assistant/proactive/domain/ProblemEvidence.java` 신규 레코드 `(String topicKey, String sourceUrl, Instant checkedAt)` 와 `ProblemEvidenceJsonConverter.java`(목록을 JSON 배열로).
- `backend/src/main/java/com/bifos/assistant/proactive/infra/ProactiveCheckProblemRepository.java` 신규:
  `List<ProactiveCheckProblem> findByConversationIdAndStatusAndCreatedAtAfterOrderByIdDesc(Long conversationId, ProblemStatus status, Instant after, Pageable page)` 와
  `List<ProactiveCheckProblem> findByConversationIdAndStatusAndCreatedAtAfter(Long conversationId, ProblemStatus status, Instant after)`.

### 7. 이 phase 를 검증하는 테스트

- `backend/src/test/java/com/bifos/assistant/proactive/ProblemJudgementTest.java` 신규. fixture 다섯과 근거 규칙:
  급한 문제(포지션 마감, `HIGH`, 근거 `NEW`) → `ACCEPTED` 와 `evidenceCheckedAt`;
  나중에 중요한 문제(공부, `MEDIUM`, 근거가 `REPEATED` 참고) → `ACCEPTED`;
  목표 없는 관찰(`relatedGoal` 없음) → `NO_GOAL`;
  중복(이전 키, 블록 안 같은 키, `changeSinceLast` 가 있으면 이전 키여도 `ACCEPTED`) → `DUPLICATE`;
  할 일 없음(발견이 없는 블록) → 빈 목록;
  근거 규칙(`CLOSED` 나 `NO_SOURCE` 발견만 가리킴, 없는 주제 키) → `NO_EVIDENCE`;
  열린 할 일과 같은 행동 → `EXISTING_FOLLOW_UP`; 값 밖의 `confidence` → `INCOMPLETE`.
  값은 합성(`example.com`, 가상 회사)이다.
- `backend/src/test/java/com/bifos/assistant/proactive/CheckResultParserTest.java` 에 더한다: 버전 3의 후보와 상한, 버전 2의 `problemCandidates` 는 읽지 않음, 버전 4는 `BAD_VERSION`.
- `backend/src/test/java/com/bifos/assistant/proactive/ProactiveCheckProblemMigrationTest.java` 신규. `ProactiveCheckWritesMigrationTest` 처럼 H2 에 마이그레이션을 끝까지 적용하고, 살펴보기 줄을 지우면 후보 줄도 지워지는지 본다.

## 검증

```bash
# cwd: 저장소 root
(cd backend && ./gradlew test --tests '*ProblemJudgementTest' --tests '*CheckResultParserTest' --tests '*ProactiveCheckProblemMigrationTest')
(cd backend && ./gradlew test)
scripts/check-mysql-migration.sh
scripts/quality.sh check
```

- 모두 종료 코드 0

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/proactive/application/model/CheckResultBlock.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/CheckResultParser.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/ProblemJudgement.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/model/JudgedProblem.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/domain/type/ProblemStatus.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/domain/type/ProblemDropReason.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/domain/ProblemEvidence.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/domain/ProblemEvidenceJsonConverter.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/domain/ProactiveCheckProblem.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/infra/ProactiveCheckProblemRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/followup/application/FollowUpService.java` | 수정 |
| `backend/src/main/resources/db/migration/V82__proactive_check_problem.sql` | 신규 |
| `backend/src/test/java/com/bifos/assistant/proactive/ProblemJudgementTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/proactive/CheckResultParserTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/proactive/ProactiveCheckProblemMigrationTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/proactive/**/*.java` | 수정 |
