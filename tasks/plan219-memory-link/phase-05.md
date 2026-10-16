# Phase 05. 측정의 대체 링크와 기준 파일

**Execution profile**: standard

## 목표

사례 묶음의 `supersedes` 를 받아들인 `SUPERSEDES` 링크로 만들어 조립하고, 대체 사례의 오기억 노출률이 기준 파일보다 내려간 값으로 기준 파일을 다시 만든다.

**범위 외**: 임베딩이 찾는 후보의 측정(phase 06).

## 컨텍스트

**근거 문서**: ADR 「적용 범위」 의 완료 조건, `docs/features/memory.md` 의 「Memory 회수 측정」, 「사례 묶음」, 「지표」, 「기준 파일」.

- `context/eval/MemoryEvalFixture.seed` 는 지금 `supersedes` 를 쓰지 않는다. `MemoryEvalDataset.MemorySeed.supersedes` 와 `validateSupersedes` 는 이미 있다
- 오기억 노출률: `MemoryEvalScoreboard.metrics` 의 `SUPERSEDED` 와 `DISTRACTOR` 항목 가운데 `INLINE` 비율. 기준 파일 `baseline.json` 은 `factsOn` 의 `falseMemoryExposure` 1.0 이다

## 작업 항목

### 1. fixture 의 대체 링크

- `MemoryEvalFixture.seed` 가 `supersedes` 가 있는 항목마다 `MemoryLink` 를 `ACTIVE`, `origin=HUMAN` 으로 저장한다. `tearDown` 이 링크도 지운다

### 2. `linksOff` 모드

- 모든 모드가 같은 링크를 쓴다. 링크 없는 지금 구조와 견줄 `linksOff` 모드를 `questionOff` 옆에 더한다(fixture 가 링크를 만들지 않는 조립)

### 3. `KNOWLEDGE_UPDATE` 게이트

- 실패 조건에 「`factsOn` 의 `KNOWLEDGE_UPDATE` 오기억 노출률이 `linksOff` 보다 낮지 않으면 실패」 를 더한다

### 4. 기준 파일

- 값이 좋아졌으면 「기준 파일」 표대로 `baseline.json` 을 다시 만들고 앞뒤 값을 커밋 본문에 적는다

### 5. 문서

- `docs/features/memory.md` 의 모드 표와 실패 조건에 `linksOff` 와 새 조건을 적는다

### 6. 이 phase 를 검증하는 테스트

- `MemoryRecallEvalTest` 의 모드 목록 단언에 `linksOff`. 보고서에 `KNOWLEDGE_UPDATE` 의 두 값이 함께 나온다
- 권한 경계 노출은 모든 모드에서 0 이다

## 검증

```bash
cd backend && ./gradlew test --tests '*MemoryRecallEvalTest'
cd backend && ./gradlew test --tests '*MemoryRecallEvalTest' -PmemoryEval.writeBaseline=true
```

기대: 첫 실행에서 `factsOn` 의 오기억 노출률이 기준 파일보다 낮아 기준 파일 검사가 통과하고(낮아지는 것은 위반이 아니다), 두 번째 실행이 새 값을 쓴다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryEvalFixture.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryRecallEvalTest.java` | 수정 |
| `backend/src/test/resources/memory-eval/baseline.json` | 수정 |
| `docs/features/memory.md` | 수정 |
