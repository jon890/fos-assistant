# Phase 04. 기동 이관, 새 평가 사례, 문서

**Execution profile**: deep

## 목표

배포 때 짧은 개인 기억의 본문을 요약으로 옮기고, 이직·가계부·여행 평가 사례를 더한다. 기능 파일과 ADR, 용어를 PR1a 의 동작에 맞춘다.

**범위 외**: 합치기(PR1b), 화면(PR2), 정비 루프(PR3).

## 컨텍스트

**요구사항**: REQ7, REQ8

**근거 문서**: `docs/features/memory/spec.md`, `docs/features/memory/design.md` 의 「이관(PR1)」, 「검증 전략」, `docs/features/memory.md` 의 「Memory 회수 측정」

- 기동 이관 틀은 `memory/application/MemoryTextMigration.java`(`ApplicationRunner`)다. 줄마다 새 트랜잭션과 쓰기 잠금으로 옮기는 방식을 따른다
- 회수 측정은 `src/test/java/com/bifos/assistant/context/eval/` 와 사례 묶음 `src/test/resources/memory-eval/family-cases.json` 이다. 사례는 지어낸 값만 쓴다. 공개 저장소다

## 의도 메모

- 이관 대상이 아닌 줄도 빈 글로 채워 다음 기동에 다시 보지 않게 한다
- 이관 테스트는 요약 칸이 `NULL` 인 줄을 만들어야 한다. phase 01 뒤로 팩터리가 빈 글을 적으므로, 저장한 뒤 `JdbcTemplate` 으로 `summary` 를 `NULL` 로 되돌려 「이관 전」 줄을 만든다

## 작업 항목

### 1. `MemorySummaryBackfill`

- `ApplicationRunner`. `MemoryTextMigration` 에 `@Order(100)`, 이 클래스에 `@Order(200)` 을 붙인다
- `summary IS NULL` 인 줄의 번호를 읽는다. 암호화가 켜진 설치면 `data_key_id IS NOT NULL` 인 줄만 본다
- 줄마다 새 트랜잭션에서 쓰기 잠금으로 다시 읽고, 여전히 `NULL` 이면: 대상(`USER`, `MEMORY`, `ACCEPTED`, `SEARCH`, 대체되지 않음, 풀림, 본문 200자 이하)은 요약 = 본문, 아니면 빈 글을 암호화해 적는다. revision 번호와 갱신 시각은 바꾸지 않는다(`Memory.seal` 처럼)
- 풀지 못한 줄은 건너뛰고 번호만 로그에 남긴다. 기동은 잇는다

### 2. 평가 사례

이직, 가계부, 여행 사례 셋을 더한다. 기억 하나에 짧은 요약과 요약에 없는 상세 사실을 두고, 질문이 그 상세를 요구한다. 기대 항목은 질문 관련 구역(`MEMORY_SEARCH`)에 본문으로 든다. 기준 파일을 다시 만들고 게이트를 통과시킨다.

### 3. 문서

- `docs/features/memory.md`: 「개인 사실 구역」 을 「요약 구역」 으로 고쳐 쓴다(후보, 한 줄 모양, 예산의 자리, 넘침 기록, 질문 관련 구역과의 관계). 「범위와 조립」 의 예산 설정, 「도구 계약(Memory)」 의 `summary`, 「바로 저장 판정」 의 조건 3·7, 「Memory 회수 측정」 의 사례와 모드, 관리자 예산 API 를 고친다
- `docs/adr/ADR-20261008-memory-facts.md`: 대상과 예산을 바꾼 갱신 줄을 머리에 더한다
- `AGENTS.md` 용어 표의 「개인 사실 구역」 을 「요약 구역」(코드 `MEMORY_FACTS`)으로 바꾼다
- 이 계획 폴더 `docs/features/memory/tasks/summary-backend/` 를 지운다

### 4. 테스트

`MemorySummaryBackfillTest`: 대상 줄은 요약 = 본문, 대상 밖(긴 본문, 제안, 보관, 대체됨, 그룹, 문서)은 빈 글, 두 번 돌아도 같음, 풀지 못한 줄은 건너뛰고 `NULL` 로 남음. 대상 줄 모두가 이관 뒤 조립의 요약 구역에 든다(예산이 넉넉한 사례).

## 검증

```bash
cd backend && HEAVY_SLOTS=2 orch slot heavy -- ./gradlew test --tests '*MemorySummaryBackfillTest' --tests '*MemoryRecallEvalTest' --tests '*MemoryEncryptionTest'
cd backend && HEAVY_SLOTS=2 orch slot heavy -- ./gradlew test
node --test test/repo/doc-links.test.ts test/repo/doc-files.test.ts
HEAVY_SLOTS=2 orch slot heavy -- scripts/dev/check-local.sh --fast
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/memory/application/MemorySummaryBackfill.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryTextMigration.java` | 수정 (`@Order`) |
| `backend/src/test/java/com/bifos/assistant/memory/MemorySummaryBackfillTest.java` | 신규 |
| `backend/src/test/resources/memory-eval/family-cases.json` | 수정 |
| `backend/src/test/resources/memory-eval/baseline.json` | 수정 |
| `docs/features/memory.md` | 수정 |
| `docs/adr/ADR-20261008-memory-facts.md` | 수정 |
| `AGENTS.md` | 수정 |
