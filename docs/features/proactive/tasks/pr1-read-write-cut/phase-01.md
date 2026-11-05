# Phase 01. 보고로 되풀이를 판정하고 발견·문제 후보를 저장하지 않는다

**Execution profile**: standard

## 목표

되풀이 판정의 재료를 `proactive_check.report_json` 으로 옮기고, 결과 블록의 발견과 문제 후보를 테이블에 쓰지 않는다.

**범위 외**: 입력 절 바꾸기(phase 02), 반응 단추와 API, `source_check_id`(phase 03), 문서(phase 04).

## 컨텍스트

**요구사항**: REQ1, REQ4, REQ7

**근거 문서**: `docs/features/proactive/spec.md`, `docs/features/proactive/design.md` 의 「보고에 담는 것」, 「되풀이 판정」, 「지우는 것」

- 결과 처리는 `backend/src/main/java/com/bifos/assistant/proactive/check/application/ProactiveCheckResults.java` 의 `answer` 와 `saveFindings` 다. 줄 저장은 `ProactiveCheckRun.record`, 순서는 `ProactiveCheckService.finish` 다
- 판정은 `FindingJudgement`(ADR-081 순서 1~8)와 `ProblemJudgement`(ADR-093)다
- Java 마이그레이션 본보기는 `backend/src/main/java/db/migration/V20261102074304__ToolsetHiddenToGroupSetting.java` 다
- 두 엔티티와 저장소는 `LegacySuggestionBackfill` 이 읽으므로 남긴다

## 의도 메모

- `CheckReport` 의 새 칸은 화면 응답(`AttentionReport`, `ReportView`)에 옮기지 않는다
- Jackson 3 의 기본값은 모르는 칸을 무시하고 없는 칸을 `null` 로 읽는다. 새 칸은 compact constructor 에서 `null` 을 빈 목록으로 바꾼다
- 자동 실행(`AUTONOMY`) 값은 남긴다(설계 DECISION3)

## 작업 항목

### 1. `proactive/check/domain/CheckReport.java` 와 새 `ReportedFinding.java`

- `CheckReport` 에 `List<ReportedFinding> announced`, `List<String> acceptedProblemKeys` 를 더하고, 다섯 칸만 받는 생성자를 남긴다
- `ReportedFinding(String topicKey, String sourceUrl, String title)` record 를 같은 패키지에 둔다

### 2. `CheckReportFactory.create`

- 「새로 알릴 것」 발견 가운데 주제 키가 있는 것으로 `announced` 를, 받아들인 문제 후보의 정규화 키로 `acceptedProblemKeys` 를 채운다. 문제 후보 목록(`List<JudgedProblem>`)을 인자로 받는다

### 3. `ProactiveCheckResults`

- `announcedSince` 와 `acceptedProblemKeysSince` 가 `ProactiveCheckRepository.findByConversationIdAndReportIsNotNullAndStartedAtAfterOrderByIdDesc` 로 읽은 보고에서 키를 만든다
- `FindingJudgement.judge` 의 「관심 없음」 인자와 판정 순서 8 을 지운다
- `pendingFindings`, `pendingProblems` 와 저장을 지우고, `saveFindings` 를 제안만 저장하는 `saveSuggestions` 로 바꾼다. `ProactiveCheckRun.saveFindings`, `ProactiveCheckService.finish` 의 호출 이름도 맞춘다
- 결과 저장 스위치 검사를 지운다. `ProactiveSuggestionProperties`, `LivePropertiesConfig` 의 등록, `application.yml` 의 `proactive-suggestion` 키를 지운다
- `ProactiveCheck` 의 `newFindings`, `referenceFindings`, 트리 토큰 세 칸과 `ProactiveCheckRun.treeTokens` 를 지운다. 답에 그릴 셈은 `ProactiveCheckResults` 가 들고 있다
- `silent()` 와 자동 실행 분기를 지운다(`ProactiveCheckRun.silent`, `CheckTurn` 기본값은 그대로)

### 4. `backend/src/main/java/db/migration/V<UTC 시각>__ProactiveReportAnnouncedBackfill.java`

- `report_json` 이 있는 `proactive_check` 마다 그 살펴보기의 `NEW` 발견(주제 키가 빈 것 제외)과 `ACCEPTED` 문제 후보의 `problem_key` 를 읽어 두 칸을 쓴다. 이미 두 칸이 있으면 건너뛴다

### 5. 테스트

- `FindingJudgementTest`: 순서 8 사례를 지운다
- `ProactiveCheckTurnTest`, `CareerDailyPilotTest`, `ProactiveCheckRepositoryTest`, `CheckReportFactoryTest`: 발견·문제 후보 저장소 단언을 보고의 두 칸과 「두 테이블에 줄이 없다」 로 옮긴다. 같은 주제만 다시 나온 예약 살펴보기가 보고 없이 끝나고 다음 예약이 `UNREAD_REPORT` 로 건너뛰지 않는 사례, 받아들인 문제 키가 `DUPLICATE` 로 걸리는 사례를 둔다
- `ProactiveSuggestionDisabledTest` 를 지운다
- 새 `ProactiveReportAnnouncedBackfillTest`: 옛 표의 줄로 두 칸을 채우고 다시 돌려도 같다

## 검증

```bash
cd backend && ./gradlew compileJava compileTestJava
cd backend && ./gradlew test --tests 'com.bifos.assistant.proactive.*'
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/proactive/check/domain/CheckReport.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/check/domain/ReportedFinding.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/check/domain/ProactiveCheck.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/check/application/CheckReportFactory.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/check/application/FindingJudgement.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/check/application/ProactiveCheckResults.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/check/application/ProactiveCheckRun.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/check/application/ProactiveCheckService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/check/infra/ProactiveCheckRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/check/config/ProactiveSuggestionProperties.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/LivePropertiesConfig.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/main/java/db/migration/V<UTC 시각>__ProactiveReportAnnouncedBackfill.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/proactive/*` | 수정 |
