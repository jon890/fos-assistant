# Phase 03. memory_search 의 본문 검색과 관련도 순 커서

**Execution profile**: deep

## 목표

`memory_search` 가 제목과 일반 본문을 키워드 점수로 찾아 관련도 순으로 내고, 다음 페이지를 불투명한 커서로 잇는다.

**범위 외**: 의미 점수(phase 05). 조립(phase 02 에서 끝났다).

## 컨텍스트

**근거 문서**: `docs/features/memory.md` 의 「기억 검색의 구현 계약」(인자, 결과, 후보 SQL, 커서, 검사), `backend/docs/adr/ADR-20261015-memory-hybrid-selection.md` 의 「검색의 페이지」.

- 지금의 길: `mcp/application/McpToolCatalog.java` 의 `memory_search` 정의(`after_id` 인자) → `mcp/presentation/McpController.java` 의 `searchMemory` 인자 검사(`query`, `limit`, `after_id` 만 받는다) → `mcp/presentation/McpDtos.java` 의 `MemorySearchArguments(String query, int limit, Long afterId)` → `mcp/application/McpToolService.searchMemory(McpCaller, String, int, Long)`(결과 `{"items", "nextAfterId"}` 를 `ExternalData.wrap`) → `memory/application/MemoryService.searchFor`(`@Transactional(readOnly = true, propagation = REQUIRES_NEW, timeout = 2)`) → `memory/application/MemoryTitleSearch` → `memory/infra/MemoryQueries.searchFor`(권한 조건 `injectable(..., MemoryRetrieval.SEARCH, ...)` 에 제목 `LIKE`).
- `MemoryService.java` 는 499줄이다. 상한 500줄을 넘기지 않게 새 로직은 새 클래스에 둔다.
- 검사: `memory/MemorySearchTest.java`(H2, 권한 경계, 투영과 timeout 을 `testsupport/MemorySearchSqlProbe.java` 로 확인, 50/500/5,000건 번호 페이지), `memory/MemorySearchMysqlTest.java`(MySQL, 2초 statement timeout, SQL 실패 전파), `mcp/McpMemorySearchToolTest.java`(`OMITTED` 항목 검색 뒤 `memory_read` 가 `MEMORY_READ` 를 남김, 인자 오류, 마지막 페이지의 `nextAfterId`), `mcp/McpMemoryToolTest.java`(schema 의 인자 이름), `RepositoryQueryMysqlTest.java`(`MemoryQueries.searchFor` 를 직접 돌린다), `test/e2e/scenarios/memory.ts`(`nextAfterId` 를 쓴다).

## 의도 메모

- 커서에 권한을 담지 않는다. 페이지마다 그때의 권한으로 다시 질의하므로 서명하지 않는다.
- keyset 커서를 쓰지 않는다. RRF 점수는 다른 항목의 순위에 따라 바뀌어 점수와 번호로 이을 수 없다(ADR 「검색의 페이지」).
- 암호문을 앱으로 가져오지 않는다. 후보 SQL 이 `CASE` 로 일반 본문만 고르고 나머지는 `NULL` 이다. `MemoryService.contentOf` 를 부르지 않는다.
- 검색은 `execution_context_source` 에 줄을 남기지 않는다. 본문을 낸 것이 아니다.

## 작업 항목

### 1. 커서 `memory/application/model/MemorySearchCursor.java` 신규

- `record MemorySearchCursor(String queryFingerprint, int offset)`.
- `static String fingerprintOf(String strippedQuery)`: SHA-256 의 앞 16 hex.
- `String encode()`: `"v1:" + queryFingerprint + ":" + offset` 의 base64url(padding 없음).
- `static Optional<MemorySearchCursor> parse(String raw)`: 해독할 수 없거나, 버전이 `v1` 이 아니거나, offset 이 1 미만이거나 200 이상이면 빈 값이다.
- 상수 `MAX_RANK = 200` 을 둔다. offset + limit 이 200 을 넘으면 200 에서 자르고 `nextCursor` 는 null 이다.

### 2. 후보 질의 `memory/infra/MemorySearchCandidates.java` 신규

- `List<MemorySearchCandidate> candidatesFor(Long userId, Long groupId, Collection<String> collections, Collection<String> sensitiveCollections)`.
- `EntityManager` 의 Criteria 에 공개 메서드 `MemoryQueries.injectable(userId, groupId, MemoryRetrieval.SEARCH, collections, sensitiveCollections)` 의 `Specification.toPredicate` 를 건다. 조건을 복사하지 않는다.
- SELECT 는 `id`, `title`, `CASE WHEN content_key_id IS NULL AND sensitivity = 'NORMAL' THEN content ELSE NULL END`, `revision`, `updated_at` 다. 정렬은 `id`. count 와 owner 와 `content_key_id` 컬럼 자체는 고르지 않는다.
- `memory/application/model/MemorySearchCandidate.java` 신규: `record MemorySearchCandidate(Long id, String title, String plainContent, int revision, Instant updatedAt)`. `toString` 은 `id` 만 낸다.
- `MemoryQueries.searchFor` 와 `MemoryTitleSearch` 는 쓰는 곳이 없어지면 지운다.

### 3. 검색 `memory/application/MemoryRelevanceSearch.java` 신규, `MemoryService.searchFor` 수정

- `MemorySearchPage searchFor(CurrentUser user, MemoryAccess access, String query, int limit, MemorySearchCursor cursor)`. `access.isEmpty()` 면 빈 페이지다.
- 후보를 `RelevanceCandidate(id, title, plainContent)` 로 바꿔 `KeywordScorer` 로 점수를 매기고, `[offset, min(offset + limit, MAX_RANK))` 를 자른다. 더 남아 있으면 `nextCursor` 는 `new MemorySearchCursor(fp, offset + limit).encode()` 다.
- `MemorySearchPage` 를 `record MemorySearchPage(List<MemorySearchItem> items, String nextCursor, String selector)` 로 바꾼다. selector 는 이 phase 에서 늘 `search-keyword-v1` 이다(문자열 상수는 `memory` 쪽에 두고, `ContextSelection.SEARCH_KEYWORD` 가 같은 값인지 phase 의 테스트가 단언한다. `memory` 는 `context` 를 import 하지 못한다).
- `MemoryService.searchFor` 는 시그니처를 `(CurrentUser, MemoryAccess, String, int, MemorySearchCursor)` 로 바꾸고 `MemoryRelevanceSearch` 에 맡긴다. 트랜잭션 annotation 은 그대로다.

### 4. MCP 경계

- `McpToolCatalog`: `after_id` 를 `cursor`(`{"type":"string","minLength":1,"maxLength":200}`)로 바꾼다. 설명은 「색인에 없는 기억도 현재 실행의 권한으로 제목과 본문에서 찾아 관련도 순으로 낸다. 결과의 id로 memory_read를 불러 본문을 읽는다. nextCursor가 있으면 cursor에 넣어 다음 페이지를 읽는다.」 다.
- `McpController.searchMemory`: 받는 키를 `query`, `limit`, `cursor` 로 바꾼다. `cursor` 가 문자열이 아니거나 null 이거나 `MemorySearchCursor.parse` 가 빈 값이거나 지문이 `fingerprintOf(strippedQuery)` 와 다르면 `invalidParams`(-32602)다.
- `McpDtos.MemorySearchArguments(String query, int limit, MemorySearchCursor cursor)`. 커서가 없으면 `new MemorySearchCursor(fp, 0)` 이다. `toString` 은 지금처럼 질문을 가린다.
- `McpToolService.searchMemory`: 결과를 `{"items": [...], "nextCursor": String|null}` 로 낸다. 로그에 실행 번호와 결과 개수, `selector` 를 남긴다.

### 5. 문서와 다른 모듈

- `test/e2e/scenarios/memory.ts` 의 `nextAfterId` 와 `after_id` 를 `nextCursor` 와 `cursor` 로 바꾼다.
- `hermes/plugins/fos-ctx/README.md` 와 `docs/features/memory.md`, `docs/features/mcp.md` 는 계획 커밋에서 이미 새 계약을 적었다. 구현이 문서와 다르면 문서가 아니라 구현을 맞춘다. 맞출 수 없으면 `PHASE_BLOCKED` 로 멈춘다.

### 6. 이 phase 를 검증하는 테스트

- `MemorySearchTest`:
  - `excludesEveryUnreadableBoundary` 의 각 경계 항목에 제목과 본문 모두에 질문 낱말을 넣고 0건임을 확인한다. 민감 허용이 있으면 민감 항목은 제목 낱말로만 찾히고, 본문에만 있는 낱말로는 찾히지 않는다.
  - `measuresSyntheticRecallAndPages` 를 고친다. 본문에만 있는 낱말로 그 항목을 찾는다(지금은 빈 결과를 단언한다). 같은 점수의 항목 200건을 커서로 끝까지 읽어 누락과 중복이 0건이고 마지막 `nextCursor` 가 null 이다. 250건이면 200건에서 끝난다.
  - `selectsOnlySearchFieldsWithinIndependentTimeout` 와 `MemorySearchSqlProbe` 를 새 SELECT 에 맞춘다. SELECT 하나, 컬럼 다섯, 본문은 `case` 안에서만 나오고 `content_key_id` 는 그 조건에서만 나온다, count 와 owner 가 없다, JDBC timeout 1..2, readOnly.
  - `usesCurrentRowsBetweenPages` 는 커서로 바꿔 유지한다.
- `MemorySearchMysqlTest` 는 위를 상속하므로 MySQL 에서도 같은 단언이 돈다. 2초 timeout 과 SQL 실패 전파 검사는 그대로 통과해야 한다.
- `McpMemorySearchToolTest`: 잘못된 커서, 다른 질문의 커서, 숫자 커서, `after_id` 인자가 모두 -32602 다. 마지막 페이지의 `nextCursor` 는 null 이다. `OMITTED` 항목의 검색 뒤 `memory_read` 의 `MEMORY_READ` 검사는 그대로다.
- `McpMemoryToolTest`: schema 의 인자 이름이 `query`, `limit`, `cursor` 다.
- `RepositoryQueryMysqlTest`: `MemoryQueries.searchFor` 를 부르던 자리를 `MemorySearchCandidates.candidatesFor` 로 바꾼다.
- `backend/src/test/java/com/bifos/assistant/memory/MemorySearchCursorTest.java` 신규: 왕복, 버전과 offset 경계, 깨진 base64, 지문 길이.

## 검증

```bash
cd backend && ./gradlew test --tests '*MemorySearchTest' --tests '*MemorySearchCursorTest' --tests '*McpMemorySearchToolTest' --tests '*McpMemoryToolTest'
cd backend && ./gradlew test
cd backend && ./gradlew archTest spotlessCheck
scripts/checks/check-mysql-migration.sh
git grep -n "after_id\|nextAfterId\|MemoryTitleSearch" -- backend/src/main hermes test/e2e docs
node scripts/checks/check-file-length.mjs
```

기대: 테스트와 검사가 모두 통과한다. `check-mysql-migration.sh` 는 `MemorySearchMysqlTest` 와 `RepositoryQueryMysqlTest` 를 포함한 mysql 태그 검사를 돈다. `git grep` 은 아무것도 내지 않는다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/memory/application/model/MemorySearchCursor.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/model/MemorySearchCandidate.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/model/MemorySearchPage.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryRelevanceSearch.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryTitleSearch.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/infra/MemorySearchCandidates.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/infra/MemoryQueries.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolCatalog.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpDtos.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/MemorySearchTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/MemorySearchMysqlTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/MemorySearchCursorTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/testsupport/MemorySearchSqlProbe.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpMemorySearchToolTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryToolTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/RepositoryQueryMysqlTest.java` | 수정 |
| `test/e2e/scenarios/memory.ts` | 수정 |
