# Phase 03. 조립의 한 단계 이웃 확장 (PR 2b)

**Execution profile**: deep

## 목표

개인 사실 구역과 「질문과 관련된 기억」 구역에 본문으로 실린 항목의 `ACTIVE` `RELATED` 이웃을 그 구역의 남은 예산 안에서 본문으로 싣는다(`MEMORY_LINK`). 넘치면 색인에 제목으로 둔다.

**범위 외**: 두 단계 이상 탐색(ADR 「대안 기각」), 먼저 살펴보기 연결(단계 4).

## 컨텍스트

**근거 문서**: ADR 「결정」 의 「조립은 실린 항목에서 한 단계만 이웃을 더한다」, `docs/features/memory.md` 의 「개인 사실 구역」, 「질문과 관련된 기억 구역」, 「답마다 참고한 기억」, 「참여하는 source」.

- 조립: `context/ContextAssembler.assemble`(private) 의 `appendFacts` 와 `searchSection.place` 뒤. `ContextAssembler.java` 는 425줄이라 확장은 새 클래스 `context/MemoryLinkSection.java` 에 둔다
- 이웃 후보는 같은 권한 조건(`MemoryQueries.injectable`)을 지난 색인 후보 가운데 실린 항목과 `ACTIVE` `RELATED` 로 이어진 것뿐이다. 새 쿼리를 만들지 않고 이미 읽은 `indexed` 목록에서 고른다
- 「참고한 기억」: `usage/application/ExecutionMemoryRefs.VIA_BY_SOURCE`, `MemoryUseVia`, `web/src/components/chat/memory-use-list.tsx`, `execution-detail.tsx` 의 `CONTEXT_SOURCE_LABELS`

## 작업 항목

### 1. source 와 선택 규칙

- `ContextSource.MEMORY_LINK`, `ContextSelection.LINK_RELATED = "link-related-v1"`(실린 항목의 이웃, 링크 점수 순)

### 2. `MemoryLinkSection` 신규

- `MemoryLinkSection.place(placedIds, candidates, builder, freshness)`: 이웃마다 그 구역과 같은 한 줄 형식으로 남은 예산 안에서 싣는다. 머리 줄 「연결된 기억」. 민감하거나 암호화한 이웃은 본문을 싣지 않고 색인에 둔다. 이미 실린 항목은 다시 싣지 않는다

### 3. 「참고한 기억」 과 화면 이름

- `MemoryUseVia.LINK`(「연결된 기억」), web 의 이름 표 두 곳, `ops-metrics.sql` 의 본문 source 목록에 `MEMORY_LINK`

### 4. 지시문 지문

- 질문 없는 조립의 지시문 지문은 링크가 없으면 지금과 같아야 한다. 링크가 있으면 개인 사실 구역의 이웃도 실리므로 지문이 바뀐다(문서에 적는다)

### 5. 측정

- 측정: `MemoryEvalFixture` 가 사례 묶음의 `related`(새 필드, 선택)를 `ACTIVE` `RELATED` 로 만든다. `MULTI_SESSION` 사례에 이어진 사실 쌍을 두 개 더하고 사례 묶음 `version` 을 올린다

### 6. 이 phase 를 검증하는 테스트

- `ContextAssemblerLinkTest`: 실린 항목의 이웃이 본문으로 실리고 근거가 `link-related-v1` 이다. 예산을 넘는 이웃은 색인에 남는다. 대체된 이웃, 권한 밖 이웃, 민감 이웃은 본문으로 실리지 않는다. 두 단계 떨어진 항목은 실리지 않는다
- `MemoryUseServiceTest` 에 `via=LINK`, `test/browser/memory-use.spec.ts` 에 「연결된 기억」
- `MemoryRecallEvalTest`: `MULTI_SESSION` recall@5 가 링크 없이보다 높다. 기준 파일을 다시 만든다

## 검증

```bash
cd backend && ./gradlew test --tests '*ContextAssemblerLinkTest' --tests '*ContextAssemblerTest' --tests '*ContextAssemblerQuestionTest' --tests '*MemoryUseServiceTest' --tests '*MemoryRecallEvalTest'
cd web && npm run lint && npm run typecheck && npm run test:browser -- memory-use
node scripts/checks/check-file-length.mjs
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/context/MemoryLinkSection.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/context/ContextAssembler.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/context/ContextSource.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/context/ContextSelection.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/model/MemoryUseVia.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionMemoryRefs.java` | 수정 |
| `web/src/components/chat/memory-use-list.tsx` | 수정 |
| `web/src/components/execution/execution-detail.tsx` | 수정 |
| `web/src/lib/memory-use-api.ts` | 수정 |
| `backend/src/test/resources/memory-eval/ops-metrics.sql` | 수정 |
| `backend/src/test/resources/memory-eval/family-cases.json` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/ContextAssemblerLinkTest.java` | 신규 |
| `test/browser/memory-use.spec.ts` | 수정 |
