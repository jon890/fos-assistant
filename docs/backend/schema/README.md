# 저장 모델

Control Plane 의 표와 칸이 무엇을 뜻하는지를 갖는다. 표는 주제별로 아래 파일에 나눠 적는다.
MySQL 8.4 에 둔다. 마이그레이션은 `backend/src/main/resources/db/migration/` 이 소유하고 이 문서는 뜻을 적는다.
DB 색인(index)은 마이그레이션이 갖는다. 이 문서는 표마다 칸과 유일 제약과 FK 만 적는다.

비밀값은 어느 표에도 넣지 않는다.
AI credential 은 Hermes profile 의 `.env` 에, profile 의 API server key 는 홈서버의 파일에 있다.

| 파일 | 표 |
| --- | --- |
| [`users-agents.md`](users-agents.md) | `app_user`, `allowed_person`, `agent`, `model_tier_definition`, `model_tier_group_setting`, `model_hidden`, `agent_token`, `service_token`, `service_token_collection` |
| [`chat.md`](chat.md) | `conversation`, `chat_message`, `chat_pending_message`, `chat_attachment`, `chat_artifact`, `result_delivery`, `result_delivery_item`, `result_delivery_attempt` |
| [`execution.md`](execution.md) | `agent_execution`, `execution_event`, `subagent_usage_job`, `execution_skill_use`, `hermes_session_binding`, `execution_context_source` |
| [`memory.md`](memory.md) | `memory`, `memory_revision`, `memory_collection`, `agent_memory_collection` |
| [`connector.md`](connector.md) | `connector_connection`, `connector_action`, `connector_tool_grant` |
| [`attention.md`](attention.md) | `follow_up`, `attention_control`, `attention_event` |
| [`notification.md`](notification.md) | `notification` |
| [`proactive.md`](proactive.md) | `proactive_check`, `proactive_check_finding` |
| [`task.md`](task.md) | `task`, `task_trigger`, `task_run` |

## 마이그레이션 작성 규칙

운영 MySQL 에서 마이그레이션 하나가 오류 1267(Illegal mix of collations)로 실패해 서비스가 내려간 적이 있다.
H2 로 도는 검사는 모두 통과한 상태였다. 아래 규칙은 그 일에서 나왔다.

### DDL 과 DML 을 한 파일에 섞지 않는다

MySQL 의 DDL 은 되돌려지지 않는다.
`ALTER TABLE` 뒤의 `INSERT` 가 실패하면 칸은 더해진 채 남고 Flyway 에는 실패한 줄이 남는다.
그 상태에서는 이전 판의 애플리케이션도 뜨지 못한다.

칸과 표와 색인을 바꾸는 파일과 줄을 넣고 고치는 파일을 번호를 달리해 나눈다.
`V56__subagent_usage_ledger.sql` 과 `V57__subagent_usage_backfill.sql` 이 본보기다.
나누면 DML 이 실패해도 앞의 DDL 파일은 성공한 것으로 남아, 실패한 줄 하나만 고쳐 다시 올릴 수 있다.

### 모든 표의 정렬 규칙은 `utf8mb4_0900_ai_ci` 하나다

**문자열 칸은 모두 `utf8mb4_0900_ai_ci` 다.** Flyway 가 스스로 만드는 `flyway_schema_history` 만 예외다.

운영 서버의 기본 정렬 규칙은 `utf8mb4_unicode_ci` 다.
MySQL 8.4 의 `utf8mb4` 기본 정렬 규칙은 `utf8mb4_0900_ai_ci` 다.
그래서 `CREATE TABLE` 에 `DEFAULT CHARSET = utf8mb4` 를 적은 표는 `utf8mb4_0900_ai_ci` 가 되고,
적지 않은 표는 서버 기본값인 `utf8mb4_unicode_ci` 가 된다.
나중에 `ALTER TABLE` 로 더한 칸은 그 표의 정렬 규칙을 따른다.

적지 않고 만든 표가 여덟 있었다.
`agent_token`, `chat_pending_message`, `execution_event`, `memory`, `model_hidden`,
`model_tier_definition`, `model_tier_group_setting`, `subagent_usage_job`.
두 정렬 규칙의 칸을 `=`, `<>`, `IN`, 조인 조건으로 비교하면 MySQL 이 오류 1267 로 거절한다. 줄이 하나도 없어도 거절한다.
V58 부터 V65 까지가 이 여덟 표를 `ALTER TABLE ... CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci` 로 맞췄다.
H2 의 MySQL 모드도 이 문장을 받는다.

- **새 표에는 `ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci` 를 적는다.**
  적지 않으면 그 표가 `utf8mb4_unicode_ci` 로 생긴다. H2 도 이 구절을 받는다.
  검사: `test/unit/migration-collation.test.ts`. V57 까지의 파일은 이미 적용돼 검사에서 뺀다
- 정렬 규칙이 하나로 남는지는 `MysqlMigrationTest` 가 실제 MySQL 에서 단언한다
- 문자열 비교는 대소문자와 악센트를 구분하지 않는다. 끝의 공백은 구분한다.
  구분해야 하는 식별자 비교는 양쪽을 `CAST(... AS BINARY)` 로 감싼다.
  `V57__subagent_usage_backfill.sql` 이 profile 이름과 session 번호를 그렇게 비교한다
- `COLLATE` 구절로 비교식의 정렬 규칙을 바꾸지 않는다. H2 가 받지 않아 H2 로 도는 검사가 실패한다
- 숫자 키로 조인할 수 있으면 문자열 비교를 쓰지 않는다

#### 정렬 규칙을 바꾸는 마이그레이션

- **표마다 파일을 나눈다.** `CONVERT TO` 는 표를 다시 만드는 DDL 이고 되돌려지지 않는다.
  한 문장은 통째로 되거나 통째로 안 되므로, 나누면 실패한 표 하나만 옛 정렬 규칙으로 남고 앞의 표는 성공으로 기록된다
- **유일 색인이 걸린 문자열 칸은 새 정렬 규칙에서 같아지는 값이 있는지 운영에서 먼저 센다.**
  `utf8mb4_unicode_ci` 에서 다르던 두 값이 `utf8mb4_0900_ai_ci` 에서 같아질 수 있다.
  나중에 유니코드에 들어온 결합 문자가 붙은 글자가 그렇다. 그런 줄이 있으면 그 표의 문장이 오류 1062 로 실패한다.
  세는 방법은 운영 저장소가 갖는다
- 다시 만드는 동안 그 표에 쓰기가 막힌다. 2026-10-02 에 여덟 표의 줄은 모두 합쳐 2천 개가 안 됐고
  가장 큰 `execution_event` 가 400KB 가 안 됐다. 같은 크기의 표를 바꾸는 데 1초가 걸리지 않는다

### 실제 MySQL 검사를 통과해야 한다

```bash
# cwd: 저장소 root
scripts/check-mysql-migration.sh
```

Docker 로 일회용 MySQL 8.4 를 띄우고 `mysql` 태그가 붙은 검사를 돌린다. CI 의 `backend` job 도 이 스크립트를 돌린다.
마이그레이션뿐 아니라 저장소 쿼리도 이 서버에서 실행한다. 그 검사는 [`backend/AGENTS.md`](../../../backend/AGENTS.md) 의 「저장소 쿼리는 실제 MySQL 에서도 실행한다」 가 갖는다.
서버를 운영처럼 `--collation-server=utf8mb4_unicode_ci` 로 띄운다. 정렬 규칙을 적지 않은 표가 생기면 그 표만 다른 정렬 규칙이 돼 검사가 실패한다.

| 검사 | 확인하는 것 |
| --- | --- |
| `MysqlMigrationTest` | 빈 데이터베이스에서 Flyway 가 처음부터 끝까지 적용되고 Hibernate 의 `ddl-auto: validate` 가 통과한다. 서버 기본 정렬 규칙이 운영과 같고, 모든 표와 문자열 칸의 정렬 규칙이 `utf8mb4_0900_ai_ci` 하나다 |
| `CollationUnifyMysqlMigrationTest` | 줄이 있는 여덟 표가 V58 부터 V65 까지를 지나며 줄과 칸 타입과 유일 색인을 그대로 둔 채 정렬 규칙만 바뀐다. 새 정렬 규칙에서 겹치는 줄이 있으면 그 표에서 멈추고 그 표는 그대로 남는다 |
| `SubagentUsageLedgerMysqlMigrationTest` | 줄이 있는 상태에서 V55 부터 끝까지 적용되고 결과가 H2 와 같다 |
| `MemorySourceUniqueMysqlMigrationTest` | 줄이 있는 `memory` 표에 V55 의 유일 색인이 만들어지고 결과가 H2 와 같다 |

**줄을 넣거나 고치는 마이그레이션을 쓰면 그 검사를 실제 MySQL 에서도 돌린다.**
H2 용 `*MigrationTest` 가 데이터베이스를 만드는 메서드를 열어 두고, `mysql` 태그를 단 하위 클래스가 `MysqlTestDatabase` 로 바꿔 끼운다.
`SubagentUsageLedgerMysqlMigrationTest` 가 본보기다.

## 모델 단계와 재조회

선택의 우선순위와 초기값은 [모델 단계와 실행 기록](../../model-tiers.md)이 정한다.
표와 칸은 [`users-agents.md`](users-agents.md), [`chat.md`](chat.md), [`execution.md`](execution.md) 의 각 절에 있다.
비밀값은 그 표들에 저장하지 않는다.

`conversation.model_selection_mode` 의 null 은 사용자와 그룹 기본값을 따른다는 뜻이다.
단계와 요청값은 실행 시작 시 복사하고 실제 제공사와 모델은 완료 시 갱신한다.
이전 실행의 `reasoning_effort_source` 는 null 로 두고 추정해 채우지 않는다.
끝난 실행의 재조회는 `finished_at` 색인을 쓴다.
재조회 작업의 완료 사건은 기존 `(execution_id, sequence)` 유일 제약을 지키며 같은 자식 완료를 중복 저장하지 않는다.
실행이나 사용자 삭제에 의한 cascade를 추가하지 않는다. 기존 실행 기록과 같은 보존 규칙을 따른다.

## 지울 때

에이전트 행은 지우지 않는다. 사용자가 에이전트를 지우면 `deleted_at` 을 적고 `enabled` 를 내린다.
`profile_managed` 가 참이면 그 Hermes profile 과 올린 스킬 디렉터리를 지우고 그 profile 의 MCP 토큰을 폐기한다.
거짓이면 운영에서 만든 profile 이라 profile 은 남긴다.
대화와 실행 기록과 스킬 호출 이력은 남는다.

대화도 지우지 않는다. 사용자가 지우면 `conversation.deleted_at` 을 적고 목록에서 숨긴다.
메시지와 실행 기록과 Hermes session 은 그대로 둔다.
아직 보내지 않은 대기 메시지(`chat_pending_message`)는 함께 지운다. 지운 대화에는 보낼 곳이 없다.
사용량 화면은 지운 대화의 실행도 센다. 돈은 이미 나갔다.
실행 기록이 에이전트와 대화를 가리키고 있고, 기록은 남아야 한다.

사용자를 지우는 흐름은 아직 없다.

페르소나는 이 데이터베이스에 없다. 본문은 그 profile 의 `SOUL.md` 가 갖는다.
근거는 [ADR-019](../../adr/ADR-019-페르소나는-hermes-가-갖고-control-plane-은-화면만-준다.md) 에 있다.

첨부도 행을 지우지 않는다. 파일만 지우고 `deleted_at` 을 적는다.
결과물(`chat_artifact`)도 같다.
하위 에이전트 session 등록(`hermes_session_binding`)도 지우지 않는다. 실행 기록과 함께 남는다.
그 자리에 사진이 있었다는 것이 남아야 지난 대화를 읽을 수 있다.

Memory 는 줄을 지운다. 지우기 전에 마지막 값을 `memory_revision` 에 `DELETED` 로 남기므로 본문은 그 표에 남는다. 민감 항목의 판은 암호문으로 남는다.
화면의 삭제는 목록과 주입에서 빼는 것이고, 본문을 완전히 없애는 길은 아직 없다.
에이전트를 지워도 `agent_memory_collection` 의 줄은 그대로 둔다. 지운 에이전트는 실행되지 않으므로 그 줄을 읽는 자리가 없다.
서비스 토큰은 폐기해도 줄이 남는다. 언제까지 쓰였는지가 남아야 한다.

허용 목록에서 빼는 것도 지우지 않고 `enabled` 를 내린다. 그 사람의 서비스 토큰은 모두 폐기한다.
그 사람의 `app_user` 와 실행 기록은 그대로 둔다.
그 사람의 Hermes profile 도 지우지 않는다. 다시 들일 때 그것을 다시 만들지 않아도 된다.

예약 작업은 지우면 `task.state` 를 `ARCHIVED` 로 둔다. 작업과 시각과 발화 기록의 줄은 남는다. 작업이 만든 대화와 그 실행 기록이 이 줄을 가리킨다.

알림(`notification`)은 보관 기간이 지나면 줄을 지운다. 알림은 다른 표의 사실을 알리는 사본이라, 원인이 된 승인 줄과 실행 기록이 남아 있다.
