# Phase 02. 다음 살펴보기 입력을 제안 줄과 보고로 바꾼다

**Execution profile**: standard

## 목표

입력의 「최근에 알린 발견」, 「최근에 받아들인 문제 후보」 절을 「최근 제안」 과 「최근 보고에서 알린 발견」 으로 바꾼다.

**범위 외**: 판정(phase 01), 반응 API 삭제(phase 03).

## 컨텍스트

**요구사항**: REQ2

**근거 문서**: `docs/features/proactive/spec.md`, `docs/features/proactive/design.md` 의 「입력」

- 입력 조립은 `proactive/check/application/ProactiveCheckInput.java` 의 `buildInput` 이다
- 제안 줄은 `follow_up` 에서 `dedupe_key` 가 있는 줄이다(`FollowUp`, `FollowUpRepository`)
- 반응 사건 열쇠는 `FeedbackSubjectType.FOLLOW_UP.key(publicId)` 이고 `ReactionLedger` 가 읽는다
- 모델이 쓴 글은 `shared.util.ExternalData.wrap` 으로 감싼다

## 의도 메모

- 상한은 `ProactiveCheckProperties` 의 `digestWindow`, `digestMaxItems` 를 그대로 쓴다(스펙 DECISION6)
- 반응은 사용자 사건 가운데 받아들임, 거절, 내림, 미룸, 되돌림, 고침, 끝냄만 읽는다(설계 DECISION5)

## 작업 항목

### 1. `followup`

- `FollowUpRepository` 에 그 대화의 제안 줄을 `since` 뒤 만든 것만, 만료한 `PROPOSED` 를 빼고 최근 것부터 읽는 JPQL 조회를 더한다
- `FollowUpSuggestions.recent(Long userId, Long conversationId, Instant since, Instant now, int limit)` 가 `followup.application.dto.RecentSuggestion(UUID publicId, String title, FollowUpStatus status)` 목록을 낸다

### 2. `feedback/application/ReactionLedger.java`

- `lastUserReactions(Long userId, Collection<String> subjectKeys, Collection<FeedbackEventType> types)` 가 열쇠마다 마지막 사용자 사건 종류를 낸다

### 3. `ProactiveCheckInput`

- 두 절을 바꾸고, `ProactiveCheckRun` 의 `NO_RECENT_*` 문장과 `ProactiveCheckInstructions.COMMON_RULES` 의 두 줄을 맞춘다

### 4. 테스트

- `ProactiveCheckTurnTest` 의 입력 단언을 새 절로 옮긴다. 거절한 제안은 실리고, 만료한 제안과 다른 대화의 제안은 실리지 않고, 제목이 `<external-data>` 안에 있고, 상한을 넘지 않는다
- `RepositoryQueryMysqlTest` 에 새 조회를 더한다

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.proactive.*' --tests 'com.bifos.assistant.followup.*'
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/followup/infra/FollowUpRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/followup/application/FollowUpSuggestions.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/followup/application/dto/RecentSuggestion.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/feedback/application/ReactionLedger.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/check/application/ProactiveCheckInput.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/check/application/ProactiveCheckRun.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/check/application/ProactiveCheckInstructions.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/proactive/ProactiveCheckTurnTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/RepositoryQueryMysqlTest.java` | 수정 |
