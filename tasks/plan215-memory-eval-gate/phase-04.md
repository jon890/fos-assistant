# Phase 04. 기억 운영 집계 쿼리

**Execution profile**: standard

## 목표

운영 데이터베이스에서 읽기만 하는 기억 지표 쿼리 아홉 개를 SQL 파일 하나로 두고, 합성 줄로 값을 확인하는 테스트를 H2 와 실제 MySQL 에서 돌린다.
사용률, 정정률, 미사용 항목, 제안 수락률 같은 사용 신호를 새 컬럼 없이 지금 기록에서 낸다.

**범위 외**: 쿼리를 운영에서 돌리는 명령과 접속 정보. 운영 저장소가 갖고 이 저장소에 적지 않는다. 화면.

## 컨텍스트

- 지표의 이름과 뜻, 읽는 컬럼은 `docs/features/memory.md` 의 「운영 집계」 표가 갖는다. 이 phase 는 그 표를 SQL 로 옮긴다.
- 읽는 테이블과 컬럼(정의를 확인한 것):
  - `execution_context_source(execution_id, position, source, source_ref, body_mode, freshness, created_at)` 와 phase 03 의 `selector`. Memory 의 `source_ref` 는 `memory:<번호>` 다
  - `agent_execution(id, conversation_id, parent_execution_id, started_at, context_chars, context_omitted_items)`. 대화 루트 실행은 `parent_execution_id IS NULL AND conversation_id IS NOT NULL` 이다
  - `execution_event(execution_id, event_type, tool_name, occurred_at)`. `memory_read` 의 시작은 `event_type = 'TOOL_STARTED'` 이고 `tool_name` 이 `memory_read` 로 끝난다(MCP 접두사가 붙을 수 있다)
  - `memory(id, status, retrieval, entry_type, created_at)`, `memory_revision(memory_id, revision, changed_at)`, `memory_capture(memory_id, kind, created_at, undone_at)`
  - `chat_message(id, role, execution_id, created_at, replaces_message_id)`. 다시 생성한 답이 이전 답을 `replaces_message_id` 로 가리킨다
- 제목과 본문 컬럼(`memory.title`, `memory.content`, `chat_message.content`, `memory_revision.title`, `memory_revision.content`)은 어느 쿼리도 읽지 않는다.
- SQL 은 MySQL 과 H2 에 함께 있는 함수만 쓴다(`backend/AGENTS.md` 의 「엔티티와 마이그레이션은 따로 논다」). 기간은 `TIMESTAMPADD(DAY, -28, CURRENT_TIMESTAMP)` 로 쓰고, 참조 비교는 `CONCAT('memory:', m.id)` 로 한다.
- 실제 MySQL 에서 같은 테스트를 돌리는 본보기는 `backend/src/test/java/com/bifos/assistant/memory/MemorySearchMysqlTest.java` 다. H2 테스트를 상속하고 `@Tag("mysql")` 과 `MysqlTestDatabase` 로 데이터 소스만 바꾼다. 저장소 인터페이스 밖의 쿼리라 `RepositoryQueryMysqlTest` 가 찾지 못하므로 이 테스트가 그 몫을 한다.
- 실행 줄과 문맥 참조를 만드는 테스트 본보기는 `backend/src/test/java/com/bifos/assistant/usage/ExecutionContextSourceTest.java` 다.

**근거 문서**: `backend/docs/adr/ADR-20261015-memory-eval-gate.md`, `docs/features/memory.md` 의 「운영 집계」

## 의도 메모

- 비율은 SQL 에서 나누지 않고 분자와 분모를 따로 낸다. DB 마다 나눗셈의 자릿수가 달라 테스트가 흔들리고, 읽는 쪽이 0 분모를 직접 다룬다.
- 쿼리마다 인자가 없다. 운영 저장소가 파일을 그대로 흘려 넣을 수 있게 기간을 SQL 안에 둔다.
- 다시 생성 비율은 관찰 비교다. 쿼리 머리 주석에 「인과가 아니다」 를 적는다.

## 작업 항목

### 1. 새 `backend/src/test/resources/memory-eval/ops-metrics.sql`

맨 위에 「읽기만 한다, 제목과 본문을 읽지 않는다, 기간은 최근 28일, 접속과 실행은 운영 저장소」 를 주석으로 적는다. 쿼리마다 `-- name: <이름>` 줄 다음에 한 문장으로 쓰고 `;` 로 끝낸다. 결과 컬럼 이름은 아래와 같다.

| 이름 | 결과 컬럼 | 정의 |
| --- | --- | --- |
| `layer_items` | `source`, `body_mode`, `selector`, `items` | 기간 안 `execution_context_source` 의 `MEMORY_` 로 시작하는 줄 수를 세 컬럼으로 묶는다 |
| `index_read_rate` | `indexed_items`, `read_items` | 기간 안 `MEMORY_INDEX` 이고 `TITLE_ONLY` 인 (실행, 참조) 쌍의 수와, 그 가운데 같은 실행에 같은 참조의 `MEMORY_READ` 줄이 있는 수 |
| `memory_read_call_rate` | `root_executions`, `with_memory_read` | 기간 안에 시작한 대화 루트 실행 수와, 그 가운데 `memory_read` 의 `TOOL_STARTED` 사건이 있는 수 |
| `inline_correction_rate` | `inline_items`, `corrected_items` | 기간 안 `MEMORY_ALWAYS`, `MEMORY_FACTS`, `MEMORY_READ` 의 `INLINE` 줄에 나온 서로 다른 참조 수와, 그 가운데 처음 나온 시각 뒤에 `memory_revision.changed_at` 이 있는 수 |
| `never_loaded_items` | `eligible_items`, `never_loaded` | 기간 시작 전에 만들었고 `status = 'ACCEPTED'`, `retrieval <> 'ARCHIVE'`, `entry_type <> 'SOURCE'` 인 항목 수와, 그 가운데 기간 안 `execution_context_source` 에 한 번도 나오지 않은 수 |
| `proposal_outcomes` | `status`, `proposals` | 기간 안 `memory_capture.kind = 'PROPOSED'` 를 항목의 지금 `memory.status` 로 묶는다. 항목이 없으면 `DELETED` |
| `undo_rate` | `captures`, `undone` | 기간 안 `kind` 가 `CREATED`, `UPDATED` 인 기록 수와 그 가운데 `undone_at` 이 있는 수 |
| `regenerate_by_memory` | `with_memory`, `answers`, `regenerated` | 기간 안 `role = 'ASSISTANT'` 이고 `execution_id` 가 있는 답을, 그 실행에 `MEMORY_` 로 시작하는 `INLINE` 줄이 있는지(1, 0)로 묶어 답 수와 다른 답이 `replaces_message_id` 로 가리키는 답 수를 센다 |
| `context_size` | `executions`, `avg_context_chars`, `max_context_chars`, `omitted_items` | 기간 안 대화 루트 실행의 수, `context_chars` 평균과 최댓값, `context_omitted_items` 합 |

### 2. 문서의 표기

`docs/features/memory.md` 의 「운영 집계」 에서 `ops-metrics.sql` 경로가 있는 줄 끝의 `ref-ignore` 주석을 지운다. 이제 그 경로에 파일이 있다.

### 3. 새 `backend/src/test/java/com/bifos/assistant/context/eval/MemoryOpsMetricsSqlTest.java`

- `@BackendIntegrationTest` 다. 파일을 클래스패스 `/memory-eval/ops-metrics.sql` 로 읽어 `-- name:` 으로 나누는 작은 도우미를 테스트 안에 둔다.
- 지표마다 그 지표가 세는 줄과 세지 않는 줄(기간 밖 29일 전 줄, 다른 `source`, 제안이 아닌 기록 등)을 `JdbcTemplate` 이나 저장소로 넣고, `queryForList` 결과의 분자와 분모가 기대값과 같은지 본다. 시각은 `TestClock` 이 아니라 데이터베이스의 `CURRENT_TIMESTAMP` 기준이므로 넣는 줄의 시각을 지금으로부터 상대값으로 정한다.
- 파일의 이름 목록이 위 아홉 개와 정확히 같은지 본다.
- 파일에 `title`, `content` 라는 낱말이 컬럼으로 나오지 않는지 정규식 `\b(title|content)\b` 로 본다(주석 줄은 빼고 본다).

### 4. 새 `backend/src/test/java/com/bifos/assistant/context/eval/MemoryOpsMetricsSqlMysqlTest.java`

`MemoryOpsMetricsSqlTest` 를 상속하고 `@Tag("mysql")` 과 `@DynamicPropertySource` 로 `MysqlTestDatabase` 를 쓴다. `MemorySearchMysqlTest` 의 속성 다섯 개를 그대로 둔다.

## 검증

```bash
cd backend && ./gradlew test --tests '*MemoryOpsMetricsSqlTest'
scripts/checks/check-mysql-migration.sh --tests '*MemoryOpsMetricsSqlMysqlTest'
cd backend && ./gradlew checkstyleTest spotlessCheck
node --test test/unit/doc-references.test.ts test/unit/doc-code-references.test.ts
```

기대값: 두 테스트가 통과한다. MySQL 검사는 Docker 가 있어야 돈다.
포맷이 어긋나면 기능 커밋 뒤에 `./gradlew spotlessApply` 결과를 따로 커밋한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/test/resources/memory-eval/ops-metrics.sql` | 신규 |
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryOpsMetricsSqlTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryOpsMetricsSqlMysqlTest.java` | 신규 |
| `docs/features/memory.md` | 수정 |
