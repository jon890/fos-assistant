# Phase 03. 실행 기록에 기억의 선택 근거를 남긴다

**Execution profile**: deep

## 목표

`execution_context_source` 에 고른 규칙의 이름과 버전(`selector`), 같은 `source` 안의 순위(`selection_rank`), 키워드와 의미와 합친 점수 컬럼을 더한다.
지금 조립은 점수가 없으므로 규칙 이름과 순위만 채운다. 질문 기반 선별이 들어오면 같은 컬럼에 점수를 채워, 운영 지표의 변화를 규칙 변화에 잇는다.

**범위 외**: 사용 신호(읽힘, 정정, 단추)는 이 테이블에 두지 않는다. 운영 집계 쿼리(phase 04). 실행 트리 화면(`web/`)은 바꾸지 않는다.

## 컨텍스트

- 테이블은 `backend/src/main/resources/db/migration/V70__execution_context_source.sql` 이 만들었다. 적용된 파일은 고치지 않고 새 마이그레이션으로 컬럼을 더한다. 새 파일 이름 규칙은 `backend/docs/data-schema.md` 의 「마이그레이션 작성 규칙」 이다.
- 엔티티는 `backend/src/main/java/com/bifos/assistant/usage/domain/ExecutionContextSource.java` 이고 넣기만 한다(`isNew` 가 늘 참). 문자열 길이를 마이그레이션과 글자까지 맞춘다.
- 저장은 `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionContextSourceWriter.java` 의 `write`(조립한 항목을 한 번에)와 `append`(`memory_read` 가 본문을 내 준 항목)다.
- 실행 기록에 넘기는 값은 `usage.application.ContextSourceRef` 이고 `usage` 는 `context` 를 import 하지 못한다(ADR-068). 문맥 묶음의 항목을 이 값으로 옮기는 곳은 `chat.application.ContextSourceRefs.of` 하나다.
- `ContextSourceRef` 의 4인자 생성은 `usage/application/ExecutionTreeService.java`, `mcp/application/McpToolService.java`, `usage/ExecutionContextSourceTest.java`, `mcp/McpMemorySearchToolTest.java` 가 쓴다. 4인자 생성자를 남겨 이 호출부를 바꾸지 않는다.
- 문맥 항목은 `context.ContextItem` record 이고, 12인자 생성은 `ContextAssembler`, `chat/application/ChatDeliveryInput.java`, `connector/application/ConnectorActionResultSource.java`, 테스트 `chat/AutoTurnResultTest.java`, `context/ContextAssemblerTest.java` 가 쓴다. 12인자 생성자를 남겨 이 호출부를 바꾸지 않는다.
- 조립은 `ContextAssembler.assemble(CurrentUser, MemoryAccess)` 가 `ContextBudgetWriter.build()` 로 `AssembledContext` 를 만든다. 묶음 항목의 순서가 지시문에 실린 순서이고 빠진 항목은 `OMITTED` 로 제자리에 있다.
- MySQL 의 `RANK` 는 예약어라 컬럼 이름을 `rank` 로 두지 않는다.

**근거 문서**: `backend/docs/adr/ADR-20261015-memory-eval-gate.md`, `backend/docs/data-schema.md` 의 「execution_context_source」, `docs/features/memory.md` 의 「로그와 저장」

## 의도 메모

- 지시문 글과 `instructions_hash` 는 바뀌지 않아야 한다. 순위는 묶음 항목에만 붙고 글로 옮기지 않는다.
- 실행 트리 응답(`ExecutionTreeService`)은 4인자 생성자를 그대로 써서 새 값을 싣지 않는다. 응답 JSON 에 null 컬럼이 늘 뿐 웹 타입(`web/src/components/execution/execution-tree.tsx`)은 그대로 읽는다.
- 규칙 이름에 버전을 붙인다. 같은 이름의 규칙을 바꾸면 버전을 올려, 운영 집계가 앞뒤를 가른다.

## 작업 항목

### 1. 마이그레이션

새 `backend/src/main/resources/db/migration/V20261015130000__execution_context_source_selection.sql`. 머리 주석에 무엇을 위한 컬럼인지와 ADR 식별자(ADR-20261015 / memory-eval-gate)를 적는다. 컬럼마다 `ALTER TABLE ... ADD COLUMN` 한 문장씩이다.

| 컬럼 | 타입 |
| --- | --- |
| `selector` | `VARCHAR(32) NULL` |
| `selection_rank` | `INT NULL` |
| `keyword_score` | `DOUBLE NULL` |
| `semantic_score` | `DOUBLE NULL` |
| `selection_score` | `DOUBLE NULL` |

버전 시각이 main 의 다른 파일과 겹치면 `date -u +%Y%m%d%H%M%S` 로 다시 정하고 이 phase 의 파일 이름을 함께 고친다.

### 2. `usage` 의 값과 엔티티와 writer

- `ContextSourceRef` 에 `String selector`, `Integer selectionRank`, `Double keywordScore`, `Double semanticScore`, `Double selectionScore` 를 더하고, 지금의 4인자 생성자를 남겨 새 값을 null 로 채운다. Javadoc 에 「사용 신호는 담지 않는다」 를 적는다.
- `ExecutionContextSource` 에 같은 다섯 필드와 `@Column` 을 더한다(`selector` 는 `length = 32`). 지금의 7인자 `of` 를 남기고 새 값을 받는 `of` 를 더한다.
- `ExecutionContextSourceWriter.write` 가 새 값을 함께 저장한다. `append` 는 그대로 둔다(`MEMORY_READ` 줄은 비어 있다).

### 3. `context` 의 선택 근거

- 새 `backend/src/main/java/com/bifos/assistant/context/ContextSelection.java`: `record ContextSelection(String selector, int rank, Double keywordScore, Double semanticScore, Double score)`. 규칙 이름 상수 셋과 그 뜻을 Javadoc 에 둔다. 이 Javadoc 이 `selector` 값 목록의 단일 소스다(`backend/docs/data-schema.md` 가 가리킨다).
  - `always-id-v1`: 항상 층. 범위별로 번호 순
  - `facts-recent-v1`: 개인 사실 구역. 최근에 고친 순으로 고르고 번호 순으로 싣는다
  - `index-id-v1`: 색인 층. 번호 순
- `ContextItem` 에 마지막 컴포넌트 `ContextSelection selection` 을 더하고 지금의 12인자 생성자를 남겨 null 로 채운다. `withBodyMode` 가 `selection` 을 잃지 않게 고치고 `withSelection(ContextSelection)` 을 더한다. `toString` 은 그대로 `source` 와 `ref` 만 낸다.
- `ContextAssembler.assemble(CurrentUser, MemoryAccess)` 가 `build()` 뒤에 묶음 항목을 돌며 `MEMORY_ALWAYS`, `MEMORY_FACTS`, `MEMORY_INDEX` 항목에 `source` 별로 1 부터 순위를 매기고 위 규칙 이름을 붙인다. `OMITTED` 항목도 순위를 받는다. `instructions`, `chars`, `omittedMemoryIds` 는 그대로 새 `AssembledContext` 로 옮긴다.
- `chat.application.ContextSourceRefs.of(List<ContextItem>)` 가 `selection` 이 있으면 다섯 값을 옮기고 없으면 null 로 둔다.

### 4. 이 phase 를 검증하는 테스트

- `backend/src/test/java/com/bifos/assistant/context/ContextAssemblerTest.java`: 항상 층 둘, 개인 사실 둘, 색인 셋(그중 하나는 예산에서 빠짐)인 조립에서 각 항목의 `selection` 이 위 규칙 이름과 `source` 별 순위 1, 2, 3 을 갖고 점수는 null 이다. 같은 입력의 `instructionsHash()` 가 이 phase 전과 같은 글에서 나온 값과 같다(지시문 글을 직접 비교한다).
- `backend/src/test/java/com/bifos/assistant/usage/ExecutionContextSourceTest.java`: 새 값을 실은 `ContextSourceRef` 로 `write` 하면 다섯 컬럼이 저장되고, 4인자 값과 `append` 한 `MEMORY_READ` 줄은 다섯 컬럼이 null 이다.
- 새 `backend/src/test/java/com/bifos/assistant/usage/ExecutionContextSourceSelectionMigrationTest.java`: `ExecutionDelegationColumnsMigrationTest` 처럼 H2 MySQL 모드에 Flyway 로 모든 마이그레이션을 적용하고, 다섯 컬럼이 있고 null 을 받는지 본다.

## 검증

```bash
cd backend && ./gradlew test --tests '*ContextAssemblerTest' --tests '*ExecutionContextSourceTest' --tests '*ExecutionContextSourceSelectionMigrationTest' --tests '*MemoryRecallEvalTest' --tests '*McpMemorySearchToolTest' --tests '*ExecutionTreeServiceTest'
scripts/checks/check-mysql-migration.sh --tests '*MysqlMigrationTest' --tests '*RepositoryQueryMysqlTest'
node scripts/checks/check-migration-versions.mjs origin/main
cd backend && ./gradlew archTest checkstyleMain checkstyleTest spotlessCheck
```

기대값: 모두 통과한다. MySQL 검사는 Docker 가 있어야 돈다. 엔티티와 Flyway 스키마가 어긋나면 `MysqlMigrationTest` 가 `ddl-auto: validate` 로 실패한다.
포맷이 어긋나면 기능 커밋 뒤에 `./gradlew spotlessApply` 결과를 따로 커밋한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V20261015130000__execution_context_source_selection.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ContextSourceRef.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/ExecutionContextSource.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionContextSourceWriter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/context/ContextSelection.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/context/ContextItem.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/context/ContextAssembler.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ContextSourceRefs.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/ContextAssemblerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/ExecutionContextSourceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/ExecutionContextSourceSelectionMigrationTest.java` | 신규 |
