# Phase 04. 기능 문서와 ADR 을 맞춘다

**Execution profile**: standard

## 목표

지금 동작 문서와 ADR 이 phase 01~03 의 동작과 맞게 한다.

**범위 외**: PR2 의 DROP 서술. PR2 가 쓴다.

## 컨텍스트

**요구사항**: REQ1, REQ2, REQ3, REQ7

**근거 문서**: `docs/features/proactive/design.md` 의 「문서와 ADR」, `docs/adr/INDEX.md` 의 보관 규칙

## 작업 항목

### 1. 기능 문서

- `docs/features/proactive.md` 의 「발견 반응」 절을 지우고 「흐름」, 「Control Plane 지시」, 「끝날 때」, 「검사」, 「문제 후보」, 「제안 저장」, 「옛 발견과 문제 후보 옮기기」, 「판단 피드백」 을 고친다
- `docs/features/attention.md` 에서 발견 반응을 가리키는 줄을 고친다
- `backend/docs/data-schema.md` 의 두 테이블, `proactive_check`, `decision_feedback_event` 서술을 고친다

### 2. ADR

- `docs/adr/ADR-20261008-check-finding-reaction.md` 를 `superseded` 로 바꿔 `docs/adr/archive/` 로 옮기고 목록과 링크를 고친다
- ADR-081, ADR-093, ADR-20261101 / proactive-suggestion, ADR-20261007 / decision-feedback 을 고친다

## 검증

```bash
node --test test/unit/adr-index.test.ts test/unit/doc-files.test.ts test/unit/doc-code-references.test.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `docs/features/proactive.md` | 수정 |
| `docs/features/attention.md` | 수정 |
| `backend/docs/data-schema.md` | 수정 |
| `docs/adr/INDEX.md` | 수정 |
| `docs/adr/ADR-20261008-check-finding-reaction.md` | 삭제 |
| `docs/adr/archive/ADR-20261008-check-finding-reaction.md` | 신규 |
| `backend/docs/adr/ADR-081-살펴보기-결과는-답-끝의-구조화-블록으로-받고-control-plane-이-검사해-그린다.md` | 수정 |
| `backend/docs/adr/ADR-093-문제-찾기는-살펴보기-결과의-문제-후보로-받고-control-plane-이-근거와-중복을-결정적으로-검사한다.md` | 수정 |
| `docs/adr/ADR-20261101-proactive-suggestion.md` | 수정 |
| `backend/docs/adr/ADR-20261007-decision-feedback.md` | 수정 |
