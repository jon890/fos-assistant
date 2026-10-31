# Phase 01. 옛 발견, 문제 후보, 제안 중인 할 일을 새 제안으로 옮긴다

**Execution profile**: deep

## 목표

옛 줄을 변환 없이 지우지 않도록, 발견과 문제 후보와 `follow_up` 의 `PROPOSED` 줄을 `proactive_suggestion` 으로 옮기고 기존 링크와 반응이 새 제안을 가리키게 한다.

**범위 외**: 숨김·미루기 경로(다음 plan), 옛 테이블 DROP.

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261101-proactive-suggestion.md` 의 「옮기는 순서」, `backend/docs/data-schema.md` 의 「마이그레이션 작성 규칙」, `docs/features/proactive.md` 의 「발견 반응」

- 옛 줄은 `backend/src/main/java/com/bifos/assistant/proactive/check/domain/ProactiveCheckFinding.java`, `ProactiveCheckProblem.java`, `backend/src/main/java/com/bifos/assistant/followup/domain/FollowUp.java` 다
- 반응은 `decision_feedback_event` 의 `subject_type`, `subject_key` 로 그 줄을 가리킨다
- 이 plan 은 `memory-stage3` 작업이 머지된 뒤 시작한다. 그 작업이 `decision_feedback_event` 의 사건 종류를 고친다

## 의도 메모

- 이미 `OPEN` 이거나 끝난 할 일은 옮기지 않는다. 사용자가 받아들인 할 일은 제안보다 오래 산다
- 분류 원문(kind, reason, confidence, sideEffect 등)은 제안 본문의 `legacy` 자유 필드로만 둔다. 그 값으로 지금 실행을 판정하지 않는다
- 반응의 대상이 모호하면 추정해 붙이지 않고 옛 식별자로 남긴다
- 본문 암호화가 필요해 SQL 하나로 옮기지 못하면 기동 뒤 한 번 도는 backfill 작업으로 옮기고, 옮긴 줄 수를 로그에 남긴다

## 작업 항목

### 1. 옮기기 작업

`backend/src/main/java/com/bifos/assistant/proactive/suggestion/application/LegacySuggestionBackfill.java` 가 사용자마다 옛 줄을 읽어 제안을 만든다. `dedupe_key` 는 `legacy:<옛 테이블>:<옛 번호>` 다. 다시 돌아도 줄이 늘지 않는다.

### 2. 호환 읽기

옛 공개 ID 로 들어온 반응 요청과 지금 화면 링크가 옮긴 제안을 찾게 한다. 옛 컨트롤러의 응답 모양은 바꾸지 않는다.

### 3. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/proactive/LegacySuggestionBackfillTest.java` 에서 확인한다.

- 제안마다 사용자, 대화, 실행이 옛 줄과 같다
- 옛 반응 사건이 새 제안을 가리킨다
- `OPEN` 할 일과 같은 제목의 제안 중인 할 일은 열린 할 일을 하나 더 만들지 않는다
- 대화를 지우면 옮긴 제안의 본문도 남지 않는다
- 두 번 돌려도 제안 수가 같다

## 검증

```bash
cd backend && ./gradlew test --tests '*LegacySuggestionBackfillTest' --tests '*CheckFindingReactionsTest'
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/proactive/suggestion/application/LegacySuggestionBackfill.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/proactive/LegacySuggestionBackfillTest.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/check/application/CheckFindingReactions.java` | 수정 |
| `backend/docs/data-schema.md` | 수정 |
