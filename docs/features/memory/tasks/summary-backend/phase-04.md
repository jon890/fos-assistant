# Phase 04. 기동 이관, 회수 측정, 문서

**Execution profile**: standard

## 목표

배포 때 짧은 개인 기억의 본문을 요약으로 옮기고, 회수 측정이 새 구조에서 돌게 하며 이직·가계부·여행 사례를 더한다. 기능 파일과 ADR, 용어를 이 PR 의 동작에 맞춘다.

**범위 외**: 화면(PR2), 정비 루프(PR3).

## 컨텍스트

**요구사항**: REQ7, REQ8

**근거 문서**: `docs/features/memory/spec.md`, `docs/features/memory/design.md` 의 「이관(PR1)」, 「지침(PR1)」, 「검증 전략」, `docs/features/memory.md` 의 「Memory 회수 측정」

- 기동 이관 틀은 `memory/application/MemoryTextMigration.java` 다. 새 이관은 그 뒤에 돈다(`@Order`)
- 회수 측정은 `src/test/java/com/bifos/assistant/context/eval/` 의 `MemoryRecallEvalTest`, `MemoryEvalFixture`, `MemoryEvalDataset`, `MemoryEvalScoreboard`, 기준 파일과 사례 묶음 `src/test/resources/memory-eval/family-cases.json`
- 사례는 지어낸 값만 쓴다. 공개 저장소다

## 의도 메모

- 이관 대상이 아닌 줄도 빈 글로 채워 다음 기동에 다시 보지 않게 한다
- 회수 측정 기준 파일은 요약 구역의 뜻이 바뀌어 다시 만든다. 이전 값과 새 값을 PR 본문에 나란히 적는다

## 작업 항목

### 1. `MemorySummaryBackfill`

설계 「이관(PR1)」 의 0~3 단계. 줄마다 새 트랜잭션, 쓰기 잠금, revision 번호와 갱신 시각 유지. 풀지 못한 줄은 번호만 로그.

### 2. 회수 측정

- `MemoryEvalFixture` 가 넣은 뒤 요약 이관을 부른다
- `factsOff` 모드는 phase 02 의 seam 으로 예산 0 을 준다
- 사례 세 개(이직, 가계부, 여행)를 더한다. 기억 하나에 짧은 요약과 요약에 없는 상세 사실을 두고, 질문이 그 상세를 요구한다. 기대 항목은 질문 관련 구역(`MEMORY_SEARCH`)에 본문으로 들어간다. 데이터셋에 요약 칸(`summary`)을 받게 하고, 없으면 이관 규칙을 따른다
- 기준 파일을 다시 만들고 게이트를 통과시킨다

### 3. 문서

- `docs/features/memory.md`: 「개인 사실 구역」 을 「요약 구역」 으로 고쳐 쓰고(후보, 한 줄 모양, 예산의 자리, 넘침 기록, 질문 관련 구역과의 관계), 「범위와 조립」 의 예산 설정, 「도구 계약(Memory)」 의 `summary`·`replaces`, 「바로 저장 판정」 의 조건 3·7, 「기억의 대체」 의 채우는 경로, 「대화에 보이는 것」 의 합친 제목, 「Memory 회수 측정」 의 사례와 모드를 고친다
- `docs/adr/ADR-20261008-memory-facts.md`: 대상과 예산을 바꾼 갱신 줄을 머리에 더한다
- `AGENTS.md` 용어 표의 「개인 사실 구역」 을 「요약 구역」(코드 `MEMORY_FACTS`)으로 바꾼다
- 이 계획 폴더(`docs/features/memory/tasks/summary-backend/`)를 지운다

### 4. 테스트

- `MemorySummaryBackfillTest`: 대상 줄은 요약 = 본문, 대상 밖(긴 본문, 제안, 보관, 대체됨, 그룹, 문서)은 빈 글, 두 번 돌아도 같음, 풀지 못한 줄 건너뛰기, 이관 전 개인 사실 구역의 번호 집합을 이관 뒤 요약 구역이 포함

## 검증

```bash
cd backend && HEAVY_SLOTS=2 orch slot heavy -- ./gradlew test --tests '*MemorySummaryBackfill*' --tests '*MemoryRecallEvalTest' --tests '*MemoryEval*'
cd backend && HEAVY_SLOTS=2 orch slot heavy -- ./gradlew test
node --test test/repo/doc-links.test.ts test/repo/doc-files.test.ts
HEAVY_SLOTS=2 orch slot heavy -- scripts/dev/check-local.sh --fast
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/memory/application/MemorySummaryBackfill.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/memory/MemorySummaryBackfillTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryEvalFixture.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryEvalDataset.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryRecallEvalTest.java` | 수정 |
| `backend/src/test/resources/memory-eval/family-cases.json` | 수정 |
| `docs/features/memory.md` | 수정 |
| `docs/adr/ADR-20261008-memory-facts.md` | 수정 |
| `AGENTS.md` | 수정 |
