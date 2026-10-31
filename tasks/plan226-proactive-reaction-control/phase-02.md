# Phase 02. 옛 제어 현재값과 attention 사건을 반응 기록으로 옮긴다

**Execution profile**: standard

## 목표

`attention_control` 의 살아 있는 현재값과 `attention_event` 의 노출·열기 사건을 반응 기록으로 옮겨, 두 테이블을 읽는 곳을 없앤다.

**범위 외**: 테이블 DROP(마지막 plan).

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261101-proactive-suggestion.md` 의 「옮기는 순서」, `docs/features/attention.md`

- 사건 쓰기는 `backend/src/main/java/com/bifos/assistant/attention/application/AttentionEventWriter.java`, 지표는 `AttentionMetricsService`, 정리는 `AttentionEventCleaner` 다

## 의도 메모

- 옛 현재값 한 줄은 처음 제어 사건 하나가 된다. `event_key` 는 `legacy:attention_control:<번호>` 다
- 중요도 분류 값은 옮기지 않는다. 노출과 열기만 옮긴다

## 작업 항목

### 1. 옮기기와 읽는 곳 바꾸기

DML 마이그레이션을 `reserve-migration` 으로 받은 경로에 둔다. `AttentionEventWriter` 와 `AttentionMetricsService` 가 반응 기록을 쓰고 읽게 한다.

### 2. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/attention/AttentionReactionControlTest.java` 에 옮기기 경우를 더한다. 옮긴 숨김이 그대로 숨기고, 옮긴 미루기가 그 시각에 풀린다.

## 검증

```bash
cd backend && ./gradlew test --tests '*AttentionReactionControlTest' --tests '*AttentionMetricsServiceTest'
scripts/checks/check-mysql-migration.sh --tests '*AttentionReactionControlTest'
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/attention/application/AttentionEventWriter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/attention/application/AttentionMetricsService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/attention/application/AttentionEventCleaner.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/attention/AttentionReactionControlTest.java` | 수정 |
