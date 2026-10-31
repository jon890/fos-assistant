# Phase 01. 제안 테이블과 반응 컬럼을 만들고 제안 저장 경로를 기능 스위치 뒤에 둔다

**Execution profile**: deep

## 목표

깨우기가 낸 제안을 `proactive_suggestion` 한 테이블에 저장하는 경로를 만든다. 스위치가 꺼져 있으면 지금처럼 발견과 문제 후보만 저장한다.

**범위 외**: 매일 루프 설정을 `task` 로 옮기는 일(phase 02), 옛 줄 옮기기, 지금 화면의 제안 카드, 숨김·미루기 경로.

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261101-proactive-suggestion.md` 의 「저장 모델」, `docs/features/proactive.md` 의 「새 설계」, `backend/docs/data-schema.md` 의 「마이그레이션 작성 규칙」

- 깨우기 한 번은 `backend/src/main/java/com/bifos/assistant/proactive/check/application/ProactiveCheckRun.java` 가 맡고, 결과 블록은 `CheckResultParser` 가 읽는다. 결과와 셈은 `ProactiveCheckResults` 가 들고 있다가 답을 저장한 뒤 발견을 저장한다
- 사용자 단위 암호화 본문은 `chat_message` 의 `content_key_id` 방식을 따른다. `backend/src/main/java/com/bifos/assistant/crypto/` 의 key 와 AAD 규칙을 쓴다
- 반응 기록은 `backend/src/main/java/com/bifos/assistant/feedback/domain/FeedbackEvent.java` 이고 `DecisionFeedbackRecorder` 가 넣는다
- 하위 기능의 순서는 `backend/src/test/java/com/bifos/assistant/architecture/SubFeatureOrder.java` 의 `proactive` 목록이 갖는다

## 의도 메모

- 제안 본문(`payload_json`)에 중요도, 위험, 확신 같은 분류 컬럼을 따로 두지 않는다. 모델이 낸 자유 JSON 을 크기만 검사해 암호화해 둔다
- `dedupe_key` 는 서버가 `<origin_execution_id>:<결과 블록 안 항목 순번이나 모델이 준 항목 식별자>` 로 만든다. 모델의 topic 값만으로 날짜를 넘는 중복을 막지 않는다
- 기능 스위치는 `assistant.proactive-suggestion.enabled` 이고 기본값은 거짓이다. 설정 클래스와 검증은 기존 `ProactiveCheckProperties` 의 방식을 따른다

## 작업 항목

### 1. 마이그레이션

파일 이름은 직접 정하지 않는다. 코디네이터 지시서의 `reserve-migration` 명령으로 받은 `RESERVED` 경로에 둔다.
DDL 만 둔다. `proactive_suggestion` 을 ADR 의 컬럼 표대로 만들고, `decision_feedback_event` 에 `card_key`, `until_at`, `event_key` 와 `(user_id, event_key)` UNIQUE 를 더한다.

### 2. 제안 엔티티와 저장

`backend/src/main/java/com/bifos/assistant/proactive/suggestion/` 아래에 `domain/ProactiveSuggestion.java`, `infra/ProactiveSuggestionRepository.java`, `application/ProactiveSuggestionWriter.java` 를 둔다.
`ProactiveSuggestionWriter.save(executionId, items)` 는 실행 줄에서 사용자와 대화를 읽어 정하고, 같은 `dedupe_key` 가 있으면 새 줄을 만들지 않는다.
`ProactiveCheckResults` 는 스위치가 켜져 있을 때만 결과 블록의 제안 배열을 이 writer 로 넘긴다.
`SubFeatureOrder` 의 `proactive` 목록에 `suggestion` 을 `check` 다음에 넣는다.

### 3. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/proactive/ProactiveSuggestionStoreTest.java` 에서 확인한다.

- 스위치를 켜고 같은 실행의 같은 결과를 두 번 넘기면 제안이 하나다
- 다른 사용자의 실행 번호로는 그 사용자의 제안만 생긴다. 요청 본문이 사용자를 정하지 못한다
- 스위치가 꺼져 있으면 제안이 생기지 않고 발견과 문제 후보는 지금과 같다
- 대화를 지우면 그 대화의 제안 줄도 지워진다

## 검증

```bash
cd backend && ./gradlew test --tests '*ProactiveSuggestionStoreTest' --tests '*WakeRunContractTest' --tests '*ArchitectureRules*'
scripts/checks/check-mysql-migration.sh --tests '*ProactiveSuggestionStoreTest'
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/proactive/suggestion/domain/ProactiveSuggestion.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/suggestion/infra/ProactiveSuggestionRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/suggestion/application/ProactiveSuggestionWriter.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/check/application/ProactiveCheckResults.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/feedback/domain/FeedbackEvent.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/architecture/SubFeatureOrder.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/proactive/ProactiveSuggestionStoreTest.java` | 신규 |
| `backend/docs/data-schema.md` | 수정 |
