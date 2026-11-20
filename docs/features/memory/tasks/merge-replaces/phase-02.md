# Phase 02. 대화의 기록 줄과 문서

**Execution profile**: standard

## 목표

대화의 「기억했어요」 줄과 제안 카드가 「n개를 합쳤어요」 와 옛 제목을 보이고, 기능 파일과 스키마 문서가 합치기를 적는다.

**범위 외**: `/memory` 화면(PR2).

## 컨텍스트

**요구사항**: REQ6

**근거 문서**: `docs/features/memory/spec.md`(REQ6, DECISION24), `docs/features/memory/design.md`(「대화의 기록 줄(PR1)」)

- 기록 줄은 `web/src/components/chat/memory-capture-list.tsx` 이고 응답 타입은 `web/src/lib/memory-capture-api.ts` 다. 모델이 쓴 글이라 평문으로만 그린다(ADR-009).
- 브라우저 테스트는 `web/test/browser/memory-capture.spec.ts` 가 API 를 가로채 기록을 꾸민다.

## 작업 항목

### 1. `web/src/lib/memory-capture-api.ts`

`MemoryCapture.replacedTitles?: string[]`.

### 2. `web/src/components/chat/memory-capture-list.tsx`

`replacedTitles` 가 비지 않으면 줄과 카드에 「n개를 합쳤어요」(제안이면 「받아들이면 n개를 합쳐요」)와 제목 목록(`data-testid="memory-capture-replaced"`)을 보인다.

### 3. `web/test/browser/memory-capture.spec.ts`

저장 줄과 제안 카드에 합친 제목이 보이는 사례.

### 4. 문서

- `docs/features/memory.md`: 「기억의 대체」 의 「채우는 경로가 없다」 를 합치기로 고치고, 도구 계약에 `replaces`, 「대화에 보이는 것」 에 합친 제목
- `backend/docs/data-schema.md`: 「memory_capture」 에 `replaced_memory_ids`
- `docs/features/memory/design.md`: 대신할 번호 판정 자리를 `MemoryReplacements` 로 고친다
- 계획서 폴더 `docs/features/memory/tasks/merge-replaces/` 를 이 PR 에서 지운다

## 검증

```bash
cd web && npx tsc --noEmit
cd web && npx playwright test test/browser/memory-capture.spec.ts
scripts/dev/check-local.sh --fast
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/lib/memory-capture-api.ts` | 수정 |
| `web/src/components/chat/memory-capture-list.tsx` | 수정 |
| `web/test/browser/memory-capture.spec.ts` | 수정 |
| `docs/features/memory.md` | 수정 |
| `backend/docs/data-schema.md` | 수정 |
| `docs/features/memory/design.md` | 수정 |
