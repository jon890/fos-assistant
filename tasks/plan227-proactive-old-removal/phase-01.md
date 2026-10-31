# Phase 01. 제안 카드를 기본으로 켜고 지금 화면이 제안을 보이게 한다

**Execution profile**: deep

## 목표

제안 기능 스위치를 기본으로 켜고, 지금 화면 「내 차례」 의 「먼저 다룰 문제」 자리를 제안 카드로 바꾼다. 옛 경로를 지울 수 있게 새 경로를 먼저 연다.

**범위 외**: 옛 하위 기능 코드 삭제(phase 02), 테이블 DROP.

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261101-proactive-suggestion.md`, `docs/features/proactive.md` 의 「새 설계」, `docs/features/attention.md`

- 지금 「먼저 다룰 문제」 는 `backend/src/main/java/com/bifos/assistant/attention/application/SurfacedProblemCandidates.java` 가 `proactive/surface` 의 판정을 읽어 만든다
- 화면은 `web/src/components/now/now-card.tsx`, `now-item.tsx`, `proactive-report-item.tsx` 다

## 의도 메모

- 제안을 받아들이면 할 일을 만들거나 대화를 연다. 받아들임은 쓰기 승인이 아니다. 외부 쓰기는 승인 카드로만 간다
- 화면 문구와 카드 모양을 바꾸므로 시작 전에 코디네이터에게 화면 계약 변경을 묻는다

## 작업 항목

### 1. 제안 후보

`backend/src/main/java/com/bifos/assistant/attention/application/SuggestionCandidates.java` 를 두고 `SurfacedProblemCandidates` 대신 쓴다.

### 2. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/attention/SuggestionCandidatesTest.java` 에서 확인한다. 만료된 제안은 보이지 않고, 숨긴 제안은 반응 기록대로 빠지며, 받아들인 제안은 할 일 하나만 만든다.

## 검증

```bash
cd backend && ./gradlew test --tests '*SuggestionCandidatesTest' --tests '*WakeWriteBoundaryContractTest'
cd web && npm run test -- now
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/attention/application/SuggestionCandidates.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/application/SurfacedProblemCandidates.java` | 삭제 |
| `backend/src/test/java/com/bifos/assistant/attention/SuggestionCandidatesTest.java` | 신규 |
| `web/src/components/now/now-item.tsx` | 수정 |
| `docs/features/attention.md` | 수정 |
