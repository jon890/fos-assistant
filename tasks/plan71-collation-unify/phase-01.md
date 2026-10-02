# Phase 01. 표 여덟의 정렬 규칙을 맞추고 실제 MySQL 에서 단언한다

**Execution profile**: standard

## 목표

`utf8mb4_unicode_ci` 로 남은 표 여덟을 `utf8mb4_0900_ai_ci` 로 바꾸는 마이그레이션 여덟을 더하고,
모든 표와 문자열 칸의 정렬 규칙이 하나인지 실제 MySQL 검사가 단언하게 한다.
표마다 정렬 규칙이 다르면 두 표의 문자열 칸을 비교하는 SQL 이 운영에서만 오류 1267 로 실패한다.

**범위 외**: 데이터베이스의 기본 정렬 규칙을 바꾸는 일. 서버 설정은 운영 저장소가 갖는다.
`flyway_schema_history` 는 Flyway 가 만드는 표라 바꾸지 않는다.
이미 적용된 `V57__subagent_usage_backfill.sql` 은 고치지 않는다.

## 컨텍스트

- 마이그레이션은 `backend/src/main/resources/db/migration/` 에 있고 지금 마지막 번호는 V57 이다.
  작업을 시작할 때 `git fetch origin && ls backend/src/main/resources/db/migration backend/src/main/java/db/migration` 으로
  origin/main 의 마지막 번호를 다시 본다. V58 이 이미 쓰였으면 아래 번호를 모두 그만큼 뒤로 민다
- 바꿀 표는 `agent_token`, `chat_pending_message`, `execution_event`, `memory`, `model_hidden`,
  `model_tier_definition`, `model_tier_group_setting`, `subagent_usage_job` 여덟이다.
  이 표들에는 FK 가 없고, 문자열 칸이 든 유일 색인은 아래와 같다

  | 표 | 유일 색인 | 문자열 칸 |
  | --- | --- | --- |
  | `agent_token` | `uk_agent_token_hash` | `token_hash` |
  | `execution_event` | `uk_execution_completed_child` | `completed_child_session_id` |
  | `memory` | `uk_memory_proposal_dedup` | `proposal_dedup_key` |
  | `memory` | `uk_memory_user_document`, `uk_memory_group_document` | `collection`, `document_key` |
  | `memory` | `uk_memory_user_source` | `source_type`, `source_ref` |
  | `model_hidden` | `uk_model_hidden_group_provider_model` | `provider`, `model` |
  | `model_tier_definition` | `uk_model_tier_definition_group_tier` | `tier` |
  | `subagent_usage_job` | `uk_subagent_usage_child` | `child_session_id` |

- H2 2.4 의 MySQL 모드는 `ALTER TABLE t CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci` 를 받고 아무것도 바꾸지 않는다.
  그래서 Java 마이그레이션이 아니라 SQL 파일로 쓴다. H2 로 도는 `*MigrationTest` 가 이 파일들도 끝까지 돌린다
- MySQL 8.4 에서 실측한 것이다
  - 같은 `utf8mb4` 안에서 정렬 규칙만 바꾸면 `varchar`, `char`, `text`, `longtext` 의 타입과 길이가 그대로다
  - `'a'` 와 `'a'` 뒤에 U+1DC4 를 붙인 값은 `utf8mb4_unicode_ci` 에서 다르고 `utf8mb4_0900_ai_ci` 에서 같다.
    이 두 값이 유일 색인에 있으면 `CONVERT TO` 가 오류 1062 로 실패하고 그 표는 옛 정렬 규칙으로 통째로 남는다
- 실제 MySQL 검사는 `scripts/check-mysql-migration.sh` 가 돌린다. 서버를 `--collation-server=utf8mb4_unicode_ci` 로 띄우고
  `mysql` 태그가 붙은 검사를 `./gradlew mysqlMigrationTest` 로 돌린다.
  검사용 데이터베이스는 `backend/src/test/java/com/bifos/assistant/testsupport/MysqlTestDatabase.java` 의 `MysqlTestDatabase.create()` 가 만든다
- Flyway 를 스프링 없이 직접 돌려 `target` 을 정하는 본보기는
  `backend/src/test/java/com/bifos/assistant/usage/SubagentUsageLedgerMigrationTest.java` 의 `migrate(String version)` 이다

**근거 문서**: `docs/backend/schema/README.md` 의 「마이그레이션 작성 규칙」

## 의도 메모

- 한 파일에 여덟 문장을 넣지 않는다. DDL 은 되돌려지지 않아, 중간에 실패하면 Flyway 가 그 파일 전체를 실패로 적고 앞의 표만 바뀐 채 남는다.
  나누면 실패한 표 앞까지가 성공으로 기록된다
- 마이그레이션 안에서 겹치는 줄을 미리 세지 않는다. `CONVERT TO` 한 문장이 통째로 실패해 그 표가 그대로 남으므로 같은 보호가 이미 있다.
  운영의 겹침은 배포 전에 세었고 0건이었다
- 비교식에 `COLLATE` 를 쓰는 SQL 을 `src/main` 에 넣지 않는다. H2 가 받지 않는다. `mysql` 태그 검사 안에서는 써도 된다
- 검사가 서버 기본 정렬 규칙을 함께 단언한다. 서버가 `utf8mb4_0900_ai_ci` 로 뜨면 마이그레이션이 없어도 정렬 규칙이 하나라 검사가 뜻 없이 통과한다

## 작업 항목

### 1. 마이그레이션 여덟

`backend/src/main/resources/db/migration/` 에 아래 파일을 만든다. 파일마다 설명 주석 한두 줄과 문장 하나만 둔다.

| 파일 | 문장 |
| --- | --- |
| `V58__agent_token_collation.sql` | `ALTER TABLE agent_token CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;` |
| `V59__chat_pending_message_collation.sql` | 같은 문장, 표는 `chat_pending_message` |
| `V60__execution_event_collation.sql` | 같은 문장, 표는 `execution_event` |
| `V61__memory_collation.sql` | 같은 문장, 표는 `memory` |
| `V62__model_hidden_collation.sql` | 같은 문장, 표는 `model_hidden` |
| `V63__model_tier_definition_collation.sql` | 같은 문장, 표는 `model_tier_definition` |
| `V64__model_tier_group_setting_collation.sql` | 같은 문장, 표는 `model_tier_group_setting` |
| `V65__subagent_usage_job_collation.sql` | 같은 문장, 표는 `subagent_usage_job` |

주석은 한국어로 쓰고 까닭은 `docs/backend/schema/README.md` 의 「마이그레이션 작성 규칙」 을 가리킨다.

### 2. `backend/src/test/java/com/bifos/assistant/MysqlMigrationTest.java`

`reproducesMixedCollations` 를 지우고 아래 검사로 바꾼다. 상수 `SERVER_COLLATION` 과 `DEFAULT_COLLATION` 은 그대로 쓴다.

`@DisplayName("모든 표와 문자열 칸의 정렬 규칙이 하나다")`

- `SELECT @@collation_server` 가 `utf8mb4_unicode_ci` 다. 실패 설명: 서버 기본 정렬 규칙이 운영과 다르면 이 검사가 마이그레이션을 확인하지 못한다
- `information_schema.columns` 에서 `table_schema = DATABASE()`, `collation_name IS NOT NULL`, `table_name <> 'flyway_schema_history'` 인 줄 가운데
  `collation_name` 이 `utf8mb4_0900_ai_ci` 가 아닌 것의 `table_name.column_name` 목록이 비어 있다. 목록으로 단언해 실패할 때 어느 칸인지 보이게 한다
- `information_schema.tables` 에서 같은 조건(`table_type = 'BASE TABLE'`)으로 `table_collation` 이 다른 표의 목록이 비어 있다

쓰지 않게 된 `collation(String, String)` 도우미는 지운다. 클래스 Javadoc 은 그대로 둔다.

### 3. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/CollationUnifyMysqlMigrationTest.java` (신규)

`@Tag("mysql")` 를 달고 스프링 없이 Flyway 를 직접 돌린다. 검사마다 `MysqlTestDatabase.create()` 로 새 데이터베이스를 만들고 `target("57")` 까지 올린 뒤 줄을 넣는다.
그다음 `target("65")` 로 올린다. 끝 번호를 적어 두는 까닭은 뒤에 생기는 마이그레이션이 이 검사의 줄에 걸리지 않게 하려는 것이다.
넣는 줄의 칸은 각 표를 만든 마이그레이션과 그 뒤의 `ALTER` 를 읽고 `NOT NULL` 칸을 모두 채운다.

| 검사 | 입력 | 기대 |
| --- | --- | --- |
| 줄이 있는 표 여덟의 정렬 규칙만 바뀐다 | 표 여덟에 줄을 하나 이상 넣는다. `agent_token.token_hash` 는 대소문자가 섞인 값 하나, `memory.content` 와 `chat_pending_message.content` 는 한글 본문 | 올리기 전 표 여덟의 `table_collation` 이 모두 `utf8mb4_unicode_ci` 다. 올린 뒤 표 여덟의 `table_collation` 과 문자열 칸의 `collation_name` 이 모두 `utf8mb4_0900_ai_ci` 다. 표마다 줄 수, 넣은 본문 값, `information_schema.columns` 의 `(column_name, column_type, is_nullable)`, `information_schema.statistics` 의 `(index_name, seq_in_index, column_name, non_unique)` 가 올리기 전과 같다 |
| 새 정렬 규칙에서 겹치는 줄이 있으면 그 표에서 멈춘다 | `model_hidden` 에 같은 `group_id`, 같은 `model` 로 `provider` 가 `'a'` 인 줄과 `'a'` 뒤에 `᷄` 를 붙인 줄을 넣는다 | `migrate()` 가 `FlywayException` 을 던진다. `model_hidden` 의 `table_collation` 은 `utf8mb4_unicode_ci` 그대로고 두 줄이 남아 있다. V58 부터 V61 까지의 표 넷은 `utf8mb4_0900_ai_ci` 다. V63 부터의 표 셋은 `utf8mb4_unicode_ci` 다. `flyway_schema_history` 에서 `success = 1` 인 가장 큰 번호가 61 이다 |

두 번째 검사의 값은 JDBC 접속이 `utf8mb4` 로 보내야 한다. 값이 `?` 로 바뀌어 들어가면 넣는 단계에서 유일 색인에 걸린다. 그때는 접속 URL 에 `characterEncoding=UTF-8` 을 더한다.

### 4. 주석 둘

- `scripts/check-mysql-migration.sh` 머리 주석의 「적은 표와 적지 않은 표의 정렬 규칙이 갈린다」 를 지금 사실로 고친다.
  서버 기본값을 운영처럼 주므로 정렬 규칙을 적지 않은 새 표가 생기면 그 표만 달라지고 `MysqlMigrationTest` 가 잡는다
- `test/unit/migration-collation.test.ts` 의 `LAST_UNCHECKED_VERSION` 주석에서 「그 표들의 정렬 규칙은 따로 맞춘다」 를
  「그 표들의 정렬 규칙은 V58 부터 V65 까지가 맞췄다」 로 고친다. 값 57 은 그대로 둔다

## 검증

```bash
# cwd: 저장소 root
# 실제 MySQL. Docker 가 있어야 한다. 새 검사 둘과 고친 MysqlMigrationTest 가 여기서 돈다
scripts/check-mysql-migration.sh

# H2 로 도는 검사 전체. 새 SQL 파일을 H2 가 받는지 *MigrationTest 가 확인한다
(cd backend && ./gradlew test)

node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
scripts/quality.sh check
```

- `scripts/check-mysql-migration.sh` 의 종료 코드가 0 이고 `backend/build/test-results/mysqlMigrationTest/` 에
  `TEST-com.bifos.assistant.CollationUnifyMysqlMigrationTest.xml` 이 있으며 `tests="2" failures="0" errors="0" skipped="0"` 이다
- 마이그레이션 여덟을 빼고 `scripts/check-mysql-migration.sh` 를 돌리면 `MysqlMigrationTest` 가 실패한다. 한 번 확인하고 되돌린다

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V58__agent_token_collation.sql` | 신규 |
| `backend/src/main/resources/db/migration/V59__chat_pending_message_collation.sql` | 신규 |
| `backend/src/main/resources/db/migration/V60__execution_event_collation.sql` | 신규 |
| `backend/src/main/resources/db/migration/V61__memory_collation.sql` | 신규 |
| `backend/src/main/resources/db/migration/V62__model_hidden_collation.sql` | 신규 |
| `backend/src/main/resources/db/migration/V63__model_tier_definition_collation.sql` | 신규 |
| `backend/src/main/resources/db/migration/V64__model_tier_group_setting_collation.sql` | 신규 |
| `backend/src/main/resources/db/migration/V65__subagent_usage_job_collation.sql` | 신규 |
| `backend/src/test/java/com/bifos/assistant/MysqlMigrationTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/CollationUnifyMysqlMigrationTest.java` | 신규 |
| `scripts/check-mysql-migration.sh` | 수정 |
| `test/unit/migration-collation.test.ts` | 수정 |
