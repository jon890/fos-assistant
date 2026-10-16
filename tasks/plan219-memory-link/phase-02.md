# Phase 02. 「대체되지 않음」 권한 조건

**Execution profile**: deep

## 목표

`ACTIVE` 인 `SUPERSEDES` 줄이 가리키는 항목을 조립의 모든 층과 `memory_search`, `memory_read`, 임베딩 대상에서 뺀다. 링크를 끊으면 다음 조립부터 다시 실린다.

**범위 외**: 링크를 만드는 길(phase 01, 03), 화면(phase 04).

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261015-memory-link.md` 의 「결정」(대체는 링크로만 판정)과 「구현 순서」, `docs/features/memory.md` 의 「기억 사이의 연결」.

- 한 곳의 조건: `memory/infra/MemoryQueries.injectable`(Criteria). 이 조건을 `MemoryService.injectableFor`(항상 층, 색인과 개인 사실 구역, 질문 구역의 후보)와 `memory/infra/MemorySearchCandidates.candidatesFor`(`memory_search`)가 함께 쓴다
- 엔티티 단위 판정: `MemoryService.readableByTool` 은 `bodyFor`(`memory_read`)와 `chat/application/MemoryUseService` 가 쓴다
- 임베딩 대상: `memory/infra/MemoryEmbeddingRepository` 의 `findEmbeddableSources`, `findEmbeddableSourcesByIds`, `findStaleMemoryIds`
- 저장소 밖 쿼리는 `RepositoryQueryMysqlTest` 의 `executesMemorySpecifications` 에 직접 더한다(`backend/AGENTS.md`)
- `MemoryService.java` 는 481줄이다. 판정이 늘면 그 조회를 새 클래스에 둔다

## 의도 메모

- `memory` 에 「대체됨」 상태를 두지 않는다(ADR 「대안 기각」). 조건은 `NOT EXISTS (select 1 from MemoryLink l where l.toMemoryId = m.id and l.type = SUPERSEDES and l.status = ACTIVE)` 하나다
- `/memory` 의 주인 목록(`listedFor`)에서는 빼지 않는다. 「대체됨」 으로 보이고 되돌릴 수 있어야 한다
- 「참고한 기억」 은 `readableByTool` 을 쓰므로 대체된 항목이 빠진다. 지금 읽을 수 없는 항목을 빼는 것과 같은 뜻이다

## 작업 항목

### 1. `MemoryQueries.injectable` 의 대체되지 않음 조건

- `MemoryQueries.injectable` 의 `selected` 에 위 `NOT EXISTS` 를 더한다. `Subquery<Long>` 과 `Root<MemoryLink>` 로 쓴다. 주석에 「대체되지 않은 항목」 을 적는다

### 2. `memory_read` 의 엔티티 단위 판정

- `MemoryService.readableByTool` 가 `memoryLinks.existsByToMemoryIdAndTypeAndStatus(id, SUPERSEDES, ACTIVE)` 이면 거짓을 낸다. 줄 수가 넘으면 판정을 `memory/application/MemoryReadability`(신규, package-private)로 옮긴다

### 3. 임베딩 대상 쿼리

- `MemoryEmbeddingRepository` 의 세 쿼리에 같은 `not exists` 를 더한다. 대체된 항목의 벡터는 다음 주기의 정리가 지운다

### 4. 실제 MySQL 쿼리 검사

- `RepositoryQueryMysqlTest` 의 Specification 목록이 새 조건으로도 실제 MySQL 에서 돈다(목록은 그대로, 조건이 그 안에 들어간다). `MemorySearchCandidates` 호출도 이미 들어 있다

### 5. 이 phase 를 검증하는 테스트

- `backend/src/test/java/com/bifos/assistant/context/ContextAssemblerSupersedeTest.java` 신규: 옛 항목과 새 항목이 둘 다 개인 사실 구역에 실리던 자료에서, `ACTIVE` 대체 줄을 만들면 옛 항목이 개인 사실 구역, 질문 구역, 색인, 항상 층 어디에도 없다. `PROPOSED` 줄은 아무것도 바꾸지 않는다. 줄을 끊으면 다시 실린다
- `MemorySearchTest` 에 대체된 항목이 제목과 본문이 맞아도 0건인 경우(벡터를 넣은 경우 포함)
- `McpMemorySearchToolTest` 에 대체된 항목의 `memory_read` 가 「읽을 수 없습니다」 인 경우
- `MemoryEmbeddingRefresherTest` 에 대체된 항목은 줄이 생기지 않고, 이미 있던 줄은 다음 주기에 지워지는 경우
- `MemoryServiceTest` 의 `/memory` 목록에는 대체된 항목이 남는 경우

## 검증

```bash
cd backend && ./gradlew test --tests '*ContextAssemblerSupersedeTest' --tests '*MemorySearchTest' --tests '*McpMemorySearchToolTest' --tests '*MemoryEmbeddingRefresherTest' --tests '*MemoryServiceTest' --tests '*ContextAssemblerTest'
cd backend && ./gradlew test --tests '*MemoryRecallEvalTest'
scripts/checks/check-mysql-migration.sh --tests '*RepositoryQueryMysqlTest' --tests '*MemorySearchMysqlTest'
node scripts/checks/check-file-length.mjs
```

기대: 모두 통과. `MemoryRecallEvalTest` 의 값은 링크가 없어 기준 파일과 같다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/memory/infra/MemoryQueries.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/infra/MemoryEmbeddingRepository.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/ContextAssemblerSupersedeTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/memory/MemorySearchTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpMemorySearchToolTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/embedding/MemoryEmbeddingRefresherTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/MemoryServiceTest.java` | 수정 |
