# 저장 모델

Control Plane 의 표와 칸이 무엇을 뜻하는지를 갖는다. 표는 주제별로 아래 절에 나눠 적는다.
MySQL 8.4 에 둔다. 표와 칸, 타입, 색인(index)은 마이그레이션 `backend/src/main/resources/db/migration/` 이 소유하고, 상태 값은 엔티티의 enum 이 갖는다.
이 문서는 코드만으로 알 수 없는 것만 적는다. 칸의 뜻, 유일 제약과 FK 를 두거나 두지 않는 까닭, 지울 때 함께 지워지는 것이다.

비밀값은 어느 표에도 넣지 않는다. 어디에 두는지는 [`docs/code-architecture.md`](../../docs/code-architecture.md) 의 「비밀값을 두는 곳」 이 갖는다.

| 절 | 표 |
| --- | --- |
| 「사용자와 에이전트 표」 | `app_user`, `allowed_person`, `agent`, `model_tier_definition`, `model_tier_group_setting`, `model_hidden`, `toolset_hidden`, `agent_toolset_request`, `agent_token`, `service_token`, `service_token_collection` |
| 「대화 표」 | `conversation`, `chat_message`, `chat_pending_message`, `chat_attachment`, `chat_artifact`, `result_delivery`, `result_delivery_item`, `result_delivery_attempt`, `execution_question` |
| 「실행 표」 | `agent_execution`, `execution_event`, `subagent_usage_job`, `execution_skill_use`, `hermes_session_binding`, `execution_context_source` |
| 「Memory 표」 | `memory`, `memory_revision`, `memory_collection`, `agent_memory_collection`, `agent_memory_collection_change`, `memory_capture` |
| 「커넥터 표」 | `connector_connection`, `agent_connector_binding`, `connector_action`, `connector_tool_grant` |
| 「할 일과 먼저 알리기 표」 | `follow_up`, `attention_control`, `attention_event` |
| 「알림 표」 | `notification` |
| 「판단 피드백 표」 | `decision_feedback_event` |
| 「먼저 살펴보기 표」 | `proactive_check`, `proactive_check_finding`, `proactive_check_problem`, `proactive_value_evaluation`, `proactive_autonomy_decision`, `user_autonomy_preference`, `proactive_loop_setting`, `proactive_loop_run` |
| 「예약 작업 표」 | `task`, `task_trigger`, `task_run` |
| 「사용자 브라우저 표」 | `user_browser` |
| 「암호화 key 표」 | `user_data_key` |

## 본문 칸과 운영 조회

사용자가 쓴 글이나 모델이 만든 글을 담는 칸이다. 저장 시 암호화의 대상이고, 운영 조회 계정이 읽지 않는 칸이다.
근거는 [ADR-20261008 / data-encryption](adr/ADR-20261008-data-encryption.md) 에 있다.

| 표 | 칸 | 암호화 |
| --- | --- | --- |
| `chat_message` | `content` | 함. 옆 칸 `content_key_id`. 이 결정 앞의 줄은 평문이다 |
| `chat_pending_message` | `content` | 아직 |
| `conversation` | `title` | 아직 |
| `agent_execution` | `output_text` | 아직 |
| `execution_event` | `detail` | 아직 |
| `connector_action` | `args_json`, `result_text` | 아직 |
| `notification` | `title`, `body` | 아직 |
| `follow_up` | `title` | 아직 |
| `task` | `title`, `instruction` | 아직 |
| `proactive_check` | `report_json` | 아직 |
| `proactive_check_problem` | `problem`, `related_goal`, `action_text`, `expected_benefit`, `risk`, `change_since_last` | 아직 |
| `proactive_value_evaluation` | `evidence_json` | 아직 |
| `memory`, `memory_revision` | `title`, `content` | 민감 항목의 `content` 만 함([ADR-055](adr/ADR-055-민감-memory-본문은-저장할-때-암호화하고-key-는-환경-변수로-받는다.md)) |

**운영 조회 계정의 계약.** 운영자가 데이터베이스를 살피는 계정에는 위 칸의 SELECT 를 주지 않는다.
MySQL 의 칸 단위 권한(`GRANT SELECT (칸, ...) ON 표`)으로 위 칸을 뺀 칸만 준다.
Control Plane 이 쓰는 애플리케이션 계정과 운영 조회 계정을 나눈다. 애플리케이션 계정의 비밀번호는 운영 조회에 쓰지 않는다.
본문 칸을 읽어야 하는 장애 대응은 따로 둔 계정으로 하고, 그 계정을 쓴 기록을 데이터베이스 밖에 남긴다.
계정과 권한, 기록을 만드는 일은 운영 저장소 `fos-home-infra` 가 맡는다.
**이 표에 칸을 더하면 운영 조회 계정의 권한도 함께 고친다.** 새 표는 처음부터 본문 칸을 뺀 권한으로 준다.

Control Plane 의 API 에는 관리자에게 남의 메시지 본문을 주는 경로가 없다. 다만 관리자의 실행 화면은 비밀값을 가린 도구 원문(`execution_event.detail`)을 보인다([ADR-038](../../docs/adr/ADR-038-도구의-명령-원문은-관리자에게만-보내고-사용자에게는-사람-말로-보인다.md)).

## 마이그레이션 작성 규칙

### 새 버전은 UTC 작성 시각으로 정한다

버전 형식, 숫자 버전 유지, `out-of-order`, 겹칠 때 새 파일만 다시 정하는 규칙은 루트 [`AGENTS.md`](../../AGENTS.md) 의 「Flyway 버전과 ADR 식별자」 가 갖는다.
적용된 파일을 고치지 않는 규칙은 같은 문서의 「용어」 절이 갖는다.
작성 시각은 `date -u +%Y%m%d%H%M%S` 로 얻는다.
서로 의존하는 마이그레이션은 한 PR 안에서 의존하는 파일의 시각을 더 크게 정한다.
다른 PR 의 미적용 스키마에 기대는 SQL 은 만들지 않는다.

`node scripts/check-migration-versions.mjs [기준 ref]` 는 기본으로 `origin/main` 과 비교한다.
새 파일의 시각 형식과 유효한 UTC 날짜, 지금보다 1일 넘게 미래인지 검사하고 모든 파일의 버전 중복을 찾는다.
CI 는 PR 의 main base 와 비교한다. push 는 직전 main, 정기 실행은 현재 main 을 기준으로 삼는다.
기존 마이그레이션 불변 검사와 `scripts/check-mysql-migration.sh` 는 계속 실행한다.
결정 근거는 [ADR-20261007 / numbering-scheme](../../docs/adr/ADR-20261007-numbering-scheme.md) 에 있다.

### MySQL 작성 주의점

H2 로 도는 검사를 모두 통과해도 운영 MySQL 에서 실패할 수 있어 아래 규칙을 둔다.

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

두 정렬 규칙의 칸을 `=`, `<>`, `IN`, 조인 조건으로 비교하면 MySQL 이 오류 1267 로 거절한다. 줄이 하나도 없어도 거절한다.
정렬 규칙을 적지 않고 만든 옛 표는 V58 부터 V65 까지가 `ALTER TABLE ... CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci` 로 맞췄다.
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
- 다시 만드는 동안 그 표에 쓰기가 막힌다. 줄이 2천 개, 크기가 400KB 를 넘지 않는 표는 1초가 걸리지 않는다

### 실제 MySQL 검사를 통과해야 한다

```bash
# cwd: 저장소 root
scripts/check-mysql-migration.sh
```

Docker 로 일회용 MySQL 8.4 를 띄우고 `mysql` 태그가 붙은 검사를 모두 돌린다. CI 의 `backend` job 도 이 스크립트를 돌린다.
검사의 목록은 `backend/src/test` 에서 `@Tag("mysql")` 이 붙은 클래스이고, 무엇을 확인하는지는 각 클래스의 Javadoc 이 갖는다.
마이그레이션뿐 아니라 저장소 쿼리와 잠금도 이 서버에서 실행한다. 저장소 쿼리 검사는 [`backend/AGENTS.md`](../AGENTS.md) 의 「저장소 쿼리는 실제 MySQL 에서도 실행한다」 가 갖는다.
서버를 운영처럼 `--collation-server=utf8mb4_unicode_ci` 로 띄운다. 정렬 규칙을 적지 않은 표가 생기면 그 표만 다른 정렬 규칙이 돼 `MysqlMigrationTest` 가 실패한다.

**줄을 넣거나 고치는 마이그레이션을 쓰면 그 검사를 실제 MySQL 에서도 돌린다.**
H2 용 `*MigrationTest` 가 데이터베이스를 만드는 메서드를 열어 두고, `mysql` 태그를 단 하위 클래스가 `MysqlTestDatabase` 로 바꿔 끼운다.
`SubagentUsageLedgerMysqlMigrationTest` 가 본보기다.

## 모델 단계와 재조회

선택의 우선순위와 초기값은 [모델 단계와 실행 기록](flow.md)이 정한다.
표와 칸은 [`backend/docs/data-schema.md`](data-schema.md), [`backend/docs/data-schema.md`](data-schema.md), [`backend/docs/data-schema.md`](data-schema.md) 의 각 절에 있다.

`conversation.model_selection_mode` 의 null 은 사용자와 그룹 기본값을 따른다는 뜻이다.
단계와 요청값은 실행 시작 시 복사하고 실제 제공사와 모델은 완료 시 갱신한다.
이전 실행의 `reasoning_effort_source` 는 null 로 두고 추정해 채우지 않는다.
재조회 작업의 완료 사건은 기존 `(execution_id, sequence)` 유일 제약을 지키며 같은 자식 완료를 중복 저장하지 않는다.
실행이나 사용자 삭제에 의한 cascade를 추가하지 않는다. 기존 실행 기록과 같은 보존 규칙을 따른다.

## 지울 때

에이전트 행은 지우지 않는다. 사용자가 에이전트를 지우면 `deleted_at` 을 적고 `enabled` 를 내린다.
`profile_managed` 가 참이면 그 Hermes profile 과 올린 스킬 디렉터리를 지우고 그 profile 의 MCP 토큰을 폐기한다.
거짓이면 운영에서 만든 profile 이라 profile 은 남긴다.
대화와 실행 기록과 스킬 호출 이력은 남는다.

대화 줄은 지우지 않는다. 사용자가 지우면 `conversation.deleted_at` 을 적고 목록에서 숨긴다.
같은 트랜잭션에서 두 가지를 함께 지운다.
아직 보내지 않은 대기 메시지(`chat_pending_message`)는 지운 대화에 보낼 곳이 없어 지운다.
판단 피드백 사건(`decision_feedback_event`)은 그 대화의 사건과, 그 사건이 가리키는 제안의 다른 사건까지 지운다. 사용자의 기록이라 대화와 함께 없앤다.
까닭과 예외는 [`backend/docs/flow.md`](flow.md) 의 「보관과 삭제」 가 갖는다.
그 뒤 정리 작업이 메시지, 첨부와 결과물의 파일과 행, 실행 질문 줄, 실행의 답 본문과 사건의 `detail`, Hermes session 을 지우고 `purged_at` 을 적는다.
실행 줄과 사건 줄은 본문 없이 남는다. 사용량 화면은 지운 대화의 실행도 센다. 돈은 이미 나갔다.
무엇을 언제 지우고 무엇을 기다리는지는 [ADR-20261008 / conversation-purge](adr/ADR-20261008-conversation-purge.md) 가 갖는다.

사용자를 지우는 흐름은 아직 없다.

페르소나는 이 데이터베이스에 없다. 본문은 그 profile 의 `SOUL.md` 가 갖는다([ADR-019](adr/ADR-019-페르소나는-hermes-가-갖고-control-plane-은-화면만-준다.md)).

첨부는 보관 기간이 지나면 파일만 지우고 `deleted_at` 을 적는다. 행은 대화를 지울 때 함께 지운다.
결과물(`chat_artifact`)도 같다.
그 자리에 사진이 있었다는 것이 남아야 지난 대화를 읽을 수 있다.
하위 에이전트 session 등록(`hermes_session_binding`)도 지우지 않는다. 실행 기록과 함께 남는다.

Memory 는 줄을 지운다. 지우기 전에 마지막 값을 `memory_revision` 에 `DELETED` 로 남기므로 본문은 그 표에 남는다. 민감 항목의 판은 암호문으로 남는다.
화면의 삭제는 목록과 주입에서 빼는 것이고, 본문을 완전히 없애는 길은 아직 없다.
기억 기록(`memory_capture`)은 항목을 지워도 남는다. 대화는 항목이 없는 기록을 그리지 않는다. 되돌린 기록은 `undone_at` 을 적고 남긴다.
에이전트를 지워도 `agent_memory_collection` 의 줄은 그대로 둔다. 지운 에이전트는 실행되지 않으므로 그 줄을 읽는 자리가 없다.
그 변경 기록(`agent_memory_collection_change`)도 지우지 않는다. 누가 언제 민감 허용을 열었는지가 남아야 한다.
서비스 토큰은 폐기해도 줄이 남는다. 언제까지 쓰였는지가 남아야 한다.

허용 목록에서 빼는 것도 지우지 않고 `enabled` 를 내린다. 그 사람의 서비스 토큰은 모두 폐기한다.
그 사람의 `app_user` 와 실행 기록은 그대로 둔다.
그 사람의 Hermes profile 도 지우지 않는다. 다시 들일 때 그것을 다시 만들지 않아도 된다.

예약 작업은 지우면 `task.state` 를 `ARCHIVED` 로 둔다. 작업과 시각과 발화 기록의 줄은 남는다. 작업이 만든 대화와 그 실행 기록이 이 줄을 가리킨다.

알림(`notification`)은 보관 기간이 지나면 줄을 지운다. 알림은 다른 표의 사실을 알리는 사본이라, 원인이 된 승인 줄과 실행 기록이 남아 있다.

## 사용자와 에이전트 표

사용자와 로그인 허용 목록, 에이전트, 모델 단계와 숨김, 도구 사용 요청, 토큰을 저장하는 표에서 코드만으로 알 수 없는 것을 적는다.
칸과 타입, 색인은 마이그레이션이, 상태 값은 엔티티의 enum 이 갖는다.

### app_user

실제로 들어온 적이 있는 사용자다.

- 그룹의 첫 사용자가 `ADMIN` 이 되고 그 뒤로는 `MEMBER` 다
- `model_default_tier` 가 비면 그룹 기본값을 따른다

### allowed_person

로그인할 수 있는 사람의 목록이다.
**여기 없는 주소는 토큰을 받지 못한다.**

`app_user` 와 나누어 둔다. 둘이 뜻하는 것이 다르다.

| 표 | 뜻 |
| --- | --- |
| `allowed_person` | 들어와도 된다고 정한 사람 |
| `app_user` | 실제로 들어온 적이 있는 사람 |

**둘을 잇는 것은 `email` 이다.** 외래 키를 두지 않는다.
허용한 시점에는 `app_user` 가 없고, 지운 뒤에도 실행 기록은 `app_user` 를 가리켜야 한다.

`hermes_profile` 이 유일한 이유는 두 사람이 같은 profile 을 쓰면 격리가 깨지기 때문이다.
`agent` 표의 `hermes_profile` 도 유일하므로 같은 제약이 두 곳에 있다.

`last_login_at` 은 웹 로그인 성공을 마지막으로 기록한 서버 시각이다. 로그인 판정과 일반 요청은 갱신하지 않고, 이 칸이 생기기 전의 로그인은 복원하지 않는다.

#### 비어 있으면 아무도 들어오지 못한다

이 표가 로그인의 유일한 근거다.
**넣는 절차는 비공개 저장소가 소유한다.** 이 저장소에 주소를 적지 않는다.

### agent

사용자가 대화를 시작할 때 고르는 실행 단위다.
에이전트 하나가 Hermes profile 하나를 가리키고, 공개 범위가 누가 쓸 수 있는지 정한다.
공개 범위가 접근 권한을 정하는 까닭은 [ADR-007](adr/ADR-007-에이전트가-모델과-도구를-함께-정한다.md) 에 있다.

- `hermes_profile` 은 호스트의 key 파일 이름과 같다
- `api_base_url` 은 그 profile 의 API server 주소이고 `/v1` 앞까지다. 공유 listener 에서도 profile 마다 접두가 다르고 profile 마다 다른 노드를 가리킬 수 있어야 해서, 설정 `hermes.base-url` 하나가 아니라 에이전트마다 둔다
- `visibility` 에는 기본값이 없다. 만들 때 늘 정한다
- `owner_user_id` 는 주인이다. `PRIVATE` 일 때 필요하고, `GROUP` 으로 공개해도 지우지 않는다
- `profile_managed` 가 참이면 Control Plane 이 이 에이전트의 profile 을 만들었다. 에이전트를 지울 때 profile 까지 지우는 것은 이 값이 참일 때뿐이다
- `deleted_at` 이 적히면 목록과 새 대화에서 빠지고 그 에이전트의 대화는 읽기만 된다
- `flow` 는 이 에이전트를 묶어 둔 다중 에이전트 흐름의 이름이다. 비어 있으면 Hermes 를 한 번 부른다
- `connector_managed` 가 참이면 바인딩이 생기기 전에 커넥터를 연결할 때 만든 옛 커넥터 에이전트다. 지금은 새로 참이 되지 않고, 남은 에이전트는 사용자가 옮긴 뒤 지운다([ADR-083](../../docs/adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md))
- `connector_attachments` 가 참이면 그 옛 커넥터 에이전트가 사진을 받는다. 커넥터가 선언한 toolset 이 실제로 켜진 것을 확인했을 때만 참이다
- `proactive_check_writes_allowed` 는 「먼저 살펴보기에 쓰기 도구 허용」 이다. 관리자만 바꾼다. 켜면 그 에이전트의 먼저 살펴보기가 쓰기 toolset 과 결과물 쓰기를 쓰고 커넥터 쓰기를 승인 카드로 보낸다([ADR-082](../../docs/adr/ADR-082-먼저-살펴보기의-쓰기-도구는-관리자가-에이전트마다-켜고-커넥터-쓰기는-승인-카드로-보낸다.md))
- `default_model_provider` 와 `default_model` 은 함께 채우거나 함께 비운다. `default_reasoning_effort` 는 모델 없이 혼자 둘 수 있다

**모델과 effort 는 대화가 고르고, 고르지 않으면 에이전트 기본 모델로 돈다.** 기본 모델 세 칸이 모두 비면 그 profile 의 값으로 돈다. 막힌 계정을 쉬게 하는 것은 Hermes 가 한다.
근거는 [ADR-030](adr/ADR-030-모델과-effort-는-대화가-고르고-기본값은-hermes-profile-이-갖는다.md) 과 [ADR-054](adr/ADR-054-에이전트-기본-모델과-모델-숨김은-control-plane-db-가-갖는다.md) 에 있다.

**주인과 공개 범위는 따로다.** 그룹에 공개해도 만든 사람이 계속 관리한다.
이 결정 전에 운영에서 등록한 `GROUP` 에이전트는 주인이 비어 있어 `ADMIN` 만 관리한다.
근거는 [ADR-033](adr/ADR-033-사용자가-에이전트를-만들고-공개해도-만든-사람이-관리한다.md) 에 있다.

추천 질문은 데이터베이스에 두지 않고 backend 메모리에만 둔다. 없앤 칸과 표는 [ADR-036](adr/ADR-036-추천-질문은-사용자의-대화-이력으로-모델이-만들고-메모리에만-둔다.md) 이 갖는다.

### model_tier_definition

그룹이 정한 모델 단계의 mapping 이다. 선택의 우선순위와 초기값은 [모델 단계와 실행 기록](flow.md) 이 정한다.

- `provider` 가 비면 요청한 에이전트의 기본 provider 로 해석한다
- `model` 이 비면 그 단계는 에이전트 기본 모델로 돈다
- `(group_id, tier)` 가 유일하다

### model_tier_group_setting

그룹의 기본 모델 단계다. `default_tier` 가 비면 그룹 기본 단계가 없다.

### model_hidden

그룹이 숨긴 provider 와 모델이다. 숨긴 것만 적는다.

- `model` 이 빈 문자열이면 그 provider 전체를 숨긴 것이다. NULL 은 유일 제약이 겹침을 막지 못해 쓰지 않는다
- `(group_id, provider, model)` 이 유일하다

### toolset_hidden

그룹이 일반 에이전트 도구 화면에서 숨긴 toolset 이다. 행이 없으면 모두 보인다.
활성 도구는 Hermes profile 이 갖고 이 표는 활성 상태를 저장하거나 바꾸지 않는다([ADR-20261008 / tool-catalog-visibility](../../docs/adr/ADR-20261008-tool-catalog-visibility.md)).

- `(group_id, name)` 이 유일하다. FK 는 없다. 새 설치에서 숨김 행을 만들지 않는다

### agent_toolset_request

에이전트 주인의 관리자 등급 도구 사용 요청과 결정 이력이다.
도구의 활성 여부는 Hermes 가 갖고 이 표는 요청 결과를 기록한다. 근거는 [ADR-20261009 / tool-request-flow](../../docs/adr/ADR-20261009-tool-request-flow.md) 다.

- `group_id` 와 `requester_user_id` 는 요청 당시의 그룹과 주인이다. 그 뒤 주인이 바뀌어도 고치지 않는다
- `decided_by_user_id` 는 결정한 관리자다. 요청자가 취소하면 비운다
- `reason` 은 거절 사유 한 줄이거나 조건이 바뀌어 만료된 사유다
- **대기 요청을 하나만 두려고 `pending_slot` 을 쓴다.** 대기 중에만 1 이고 끝나면 NULL 이다. `(group_id, agent_id, requester_user_id, toolset, pending_slot)` 이 유일하므로 대기 요청은 겹치지 못하고, 끝난 요청의 NULL 은 서로 겹쳐도 저장되어 이력이 남는다. CHECK 제약이 상태와 슬롯을 함께 검사한다
- `agent_id` 는 `agent` 를, 요청자와 결정한 관리자는 `app_user` 를 FK 로 가리킨다. 에이전트를 지워도 행과 요청 이력을 남긴다

### agent_token

Hermes 가 Control Plane 의 MCP 도구를 부를 때 쓰는 장기 토큰이다.
토큰 원문은 발급 응답에서 한 번만 내고 데이터베이스에는 SHA-256 해시만 저장한다.

**토큰은 어느 profile 이 부르는지만 증명한다. 사용자를 정하지 않는다.**
요청자는 서명한 `_fos_ctx` 로 찾은 origin 실행의 `user_id` 다.
근거는 [ADR-032](adr/ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md) 에 있다.

- 폐기되지 않은 토큰은 `profile_name` 이 늘 채워져 있다. 비어 있는 줄은 profile 을 묶기 전에 폐기된 옛 토큰뿐이고, 인증에서 거절한다. 그 이력을 남기려고 NULL 을 받는다
- 한 profile 에 토큰이 여럿일 수 있다. 바꿔 끼우는 동안 옛 토큰과 새 토큰이 함께 쓰인다. 그래서 `profile_name` 에 유일 제약을 두지 않는다
- 토큰 하나는 profile 하나에만 묶인다. 한 번 묶은 profile 은 바꾸지 않는다. 다른 profile 에 쓰려면 새로 발급한다
- 폐기해도 행을 지우지 않고 `revoked_at` 을 적는다

### service_token

다른 서비스가 사용자의 Memory 문서를 읽을 때 쓰는 토큰이다. 근거는 [ADR-056](adr/ADR-056-다른-서비스는-사용자에-묶인-서비스-토큰으로-문서를-읽기만-한다.md) 에 있다.
원문은 발급 응답에서 한 번만 내고 데이터베이스에는 SHA-256 해시만 저장한다.

**`agent_token` 과 달리 사용자 한 사람에 묶인다.** 그 사용자 본인만 발급하고 폐기한다. 관리자도 다른 사용자의 토큰을 발급하거나 폐기하지 못한다.

- `expires_at` 은 늘 있다. 발급할 때 정하고 고치지 않는다. 받을 수 있는 범위는 `ServiceTokenService` 가 갖는다
- `last_used_at` 은 거절한 요청에서는 적지 않는다
- 폐기해도 행을 지우지 않고 `revoked_at` 을 적는다

허용 목록에서 사용자를 끄면 그 사용자의 폐기되지 않은 토큰에 모두 `revoked_at` 을 적는다. 다시 켜도 지우지 않는다.
인증할 때도 주인이 지금 허용 목록에 켜져 있는지 본다. 폐기가 한 번 실패해도 끈 사용자의 토큰이 통하지 않게 하기 위해서다.

### service_token_collection

서비스 토큰이 받는 collection 하나가 한 줄이다. `agent_memory_collection` 과 같은 뜻이고 같은 세 조건으로 판정한다.
`allow_sensitive` 가 참이면 그 collection 의 `SENSITIVE` 문서까지 읽는다.

## 대화 표

대화와 메시지, 대기 메시지, 사진 첨부, 결과물, 결과 전달 기록, 실행과 질문의 연결을 저장하는 표 아홉을 다룬다.
칸과 타입, 색인은 마이그레이션과 각 표의 엔티티가 갖는다. 이 문서는 코드만으로 알 수 없는 칸의 뜻, 유일 제약과 FK 를 두지 않는 까닭, 지울 때 함께 지워지는 것을 적는다.
이 표들을 읽고 쓰는 경로는 [`backend/docs/flow.md`](flow.md) 와 그 옆의 문서들이 갖는다.

### conversation

주고받는 하나의 스레드다. 엔티티는 `Conversation` 이다.

| 칸 | 뜻 |
| --- | --- |
| `agent_id` | 대화를 만들 때 정한다. 뒤에 바뀌지 않는다 |
| `hermes_session_id` | 다음 turn 에 보낼 Hermes session. 새 대화는 첫 turn 을 보내기 전에 Control Plane 이 `fos-<uuid>` 로 정해 적는다. 그 전의 대화는 첫 실행이 돌려준 값이다. 압축 교체로 Hermes 가 다른 session 을 돌려주면 그 값으로 바뀐다. 특정 profile 안의 값이다 |
| `hermes_root_session_id` | 그 대화의 루트 session. 새 대화는 첫 turn 을 보내기 전에 `hermes_session_id` 와 같은 `fos-<uuid>` 를 적고, 압축 교체에도 바뀌지 않는다. MCP `agent_*` 호출이 들고 오는 서명한 루트 session 이 이 값이다. 이 칸이 생기기 전의 대화는 비어 있다 |
| `title` | 첫 메시지의 앞부분. 사진을 먼저 올리려고 만든 대화는 첫 메시지 전까지 비어 있다 |
| `model_provider`, `model` | 이 대화에서 직접 고른 provider 와 모델. 함께 채우거나 함께 비운다. 비면 어느 모델로 도는지는 [모델 단계와 실행 기록](flow.md) 의 「모델 선택」 이 정한다 |
| `reasoning_effort` | 이 대화에서 고른 effort. `none` 은 reasoning 끄기이고 비어 있음(미지정)과 다르다 |
| `model_selection_mode` | 비어 있으면 사용자와 그룹 기본값을 따른다 |
| `deleted_at` | 사용자가 지운 시각. 채워지면 목록과 조회와 보내기에서 없는 대화와 같다 |
| `purged_at` | 지운 대화의 본문을 정리 작업이 실제로 지운 시각. 이때 `title` 과 두 session 칸을 비운다. 채워진 대화에는 메시지를 저장하지 않는다. 근거는 [ADR-20261008 / conversation-purge](adr/ADR-20261008-conversation-purge.md) |
| `hidden_at` | 목록에서만 뺀 시각. 예약 작업이 「보고할 것 없음」 으로 끝난 `NEW_PER_RUN` 대화에 적는다. 조회와 보내기는 그대로 되고, 사용자가 질문을 보내면 비운다([`backend/docs/flow.md`](flow.md) 의 「보고할 것 없음」) |
| `auto_turn_count` | 마지막 사용자 질문 뒤로 Control Plane 이 위임 결과를 전하려고 연 turn 수. 사용자 질문을 저장할 때 0 으로 돌린다. `assistant.delegation-wake.max-auto-turns` 에 닿으면 더 깨우지 않는다 |
| `purpose` | `CHAT` 은 보통 대화, `CHECK` 는 먼저 살펴보기의 점검 대화다. 만들 때 정하고 바뀌지 않는다. 사용자와 에이전트마다 지우지 않은 점검 대화 가운데 `id` 가 가장 큰 것을 쓴다([`backend/docs/flow.md`](flow.md)) |
| `task_id` | 이 대화를 만든 예약 작업. 사용자가 연 대화는 비어 있다. 외래 키를 두지 않는다. 뜻은 [`backend/docs/data-schema.md`](data-schema.md) 의 「conversation 에 더하는 칸」 |

`hermes_session_id` 가 특정 profile 안의 값이라, 대화의 에이전트는 중간에 바뀌지 않는다.

숨긴 대화(`hidden_at`)는 목록 색인의 칸에 없어 읽은 뒤 거른다. 한 쪽을 읽을 때 그 사이에 든 숨긴 대화만큼 줄을 더 읽는다.
숨긴 대화는 「보고할 것 없음」 으로 끝난 예약 작업만 만들고, 하루에 만드는 수는 사용자당 하루 발화 상한(`assistant.task.max-runs-per-day`)을 넘지 않아 색인에 더하지 않는다. 다른 경로가 대화를 숨기게 되면 색인에 `hidden_at` 을 더한다.

**`agent_id` 에 FK 를 두지 않는다.** 칸도 NULL 을 받는다(V4 가 칸을 더하며 그렇게 만들었다).
에이전트를 지우는 것은 `deleted_at` 을 적는 것이라 정상 경로에서는 행이 사라지지 않는다.
그래도 행이 없는 대화가 운영에서 나왔고, 그 대화를 읽는 경로는 행이 없어도 실패하지 않게 고쳤다
([`backend/docs/flow.md`](flow.md) 의 「에이전트 만들기와 지우기」 절).
FK 를 더하려면 이미 행이 없는 대화를 먼저 정리해야 하고, 그 정리는 대화 이력을 지우거나 가짜 에이전트 행을 만드는 일이 된다.
행이 사라진 원인을 찾은 뒤 다시 판단한다.

**`id` 는 Control Plane 밖으로 나가지 않는다.** 화면과 API 는 대화를 `public_id` 로만 가리킨다.
근거는 [ADR-025](../../docs/adr/ADR-025-대화는-주소에-공개-식별자를-쓰고-번호는-안에만-둔다.md)에 있다.

### chat_message

대화 안의 한 줄이다. 엔티티는 `ChatMessage` 다.

| 칸 | 뜻 |
| --- | --- |
| `role` | `SYSTEM` 은 위임 결과가 도착했다는 알림 줄이다. 사용자가 결과를 다시 전달할 때도 한 줄 남긴다. 자식의 답 전문은 넣지 않고, 다시 생성의 대상이 아니다([ADR-040](adr/ADR-040-위임-결과는-control-plane-이-부모-대화의-다음-turn-을-열어-전한다.md)) |
| `content` | 본문. `content_key_id` 가 있으면 대화 주인의 데이터 key 로 암호화한 `v1.<IV>.<암호문과 태그>` 다 |
| `content_key_id` | 본문을 암호화한 데이터 key(`user_data_key.id`). 비어 있으면 `content` 는 평문이다. 외래 키를 두지 않는다 |
| `sender_user_id` | 이 줄을 쓴 사람. 화면이 보낸 사람 이름을 보이기 위한 것이다. `ASSISTANT` 와 `SYSTEM` 은 비어 있다 |
| `execution_id` | 이 답을 만든 실행. `USER` 와 `SYSTEM` 은 비어 있다 |
| `replaces_message_id` | 이 메시지가 새 판으로 대신하는 이전 메시지. 같은 대화, 같은 `role` 이다. 다시 생성이 채운다 |

`content` 를 `LONGTEXT` 로 못 박는다.
길이를 주지 않은 `@Lob` 문자열을 Hibernate 가 MySQL 에서 `tinytext` 로 기대해 기동이 실패한다.

**본문은 저장할 때 암호화한다.** KEK 설정이 있으면 새 메시지를 빈 글로 넣고 같은 트랜잭션에서 암호문으로 고친다.
AAD 는 `chat_message:<id>:conversation:<conversation_id>:user:<대화 주인>` 이라 암호문을 다른 줄로 옮기거나 대화 주인을 바꾸면 풀리지 않는다.
풀지 못한 본문은 「읽을 수 없는 메시지입니다.」 로 낸다. 본문은 `ChatMessage.content()` 로만 꺼낸다. 근거와 위협 모델은 [ADR-20261008 / data-encryption](adr/ADR-20261008-data-encryption.md) 에 있다.

예전에는 수정한 사용자 메시지가 고치기 전 메시지를 가리켰다. 수정을 없앴지만 그 줄은 남아 있고 화면이 계속 넘겨 볼 수 있다([ADR-024](../../docs/adr/ADR-024-메시지-수정을-없애고-중지한-뒤-다시-보낸다.md)).
**이전 판을 지우지 않는다.** 화면이 넘겨 볼 수 있어야 하고, 그 판을 만든 실행이 그것을 가리킨다. 대화를 지우면 정리 작업이 판까지 모두 지운다.
근거는 [ADR-022](../../docs/adr/ADR-022-다시-생성과-수정은-같은-session-에-판으로-쌓는다.md) 에 있다.

대화는 여전히 주인 한 사람의 것이고, 여러 사람이 같은 대화를 읽고 쓰는 것은 아직 만들지 않았다.

### chat_pending_message

turn 이 도는 동안 사용자가 보낸 메시지 하나가 한 행이다. 보내지기 전까지만 있다. 엔티티는 `ChatPendingMessage` 다.

| 칸 | 뜻 |
| --- | --- |
| `id` | 쌓인 순서다. 합칠 때 이 순서로 잇는다 |
| `user_id` | 이 글을 보낸 사용자. 합친 `USER` 행의 `sender_user_id` 가 된다 |
| `held` | 멈춰 두었다. 앞 turn 을 중지했거나 보내려다 저장 전에 실패했다 |

**보낸 행은 지운다.** 대기 행을 지우는 것과 합친 글을 `chat_message` 의 `USER` 행으로 저장하는 것이 한 트랜잭션이다.
보낸 글은 `chat_message` 에 남으므로 여기에 이력을 두지 않는다.
취소한 행도 지운다.

개수와 길이의 상한은 표의 제약이 아니라 `PendingMessageService` 가 더할 때 본다.

**한 행이라도 `held` 가 참이면 그 대화의 대기 행을 모두 보내지 않는다.**
사용자가 「보내기」 를 누르면 그 대화의 `held` 를 모두 내린다.

근거는 [ADR-048](../../docs/adr/ADR-048-응답-중에-보낸-메시지는-control-plane-이-쌓아-두고-다음-turn-으로-합쳐-보낸다.md) 에 있다.

### chat_attachment

대화에 올린 사진 한 장이 한 행이다. 본문은 파일로 두고 여기에는 그 사진을 가리키는 것만 둔다. 엔티티는 `ChatAttachment` 다.

| 칸 | 뜻 |
| --- | --- |
| `conversation_id` | 어느 대화에 올렸는가. 디렉터리 이름이기도 하다 |
| `message_id` | 함께 보낸 메시지. 아직 보내지 않았으면 비어 있다 |
| `position` | 같은 메시지에 붙인 사진의 고른 순서. 0부터 센다. 기존에 메시지에 묶인 행은 첨부 번호 순서로 채웠다 |
| `stored_name` | 디스크에 둔 이름. `{id}.{확장자}` 다. 번호를 받은 직후 같은 트랜잭션에서 채우므로 커밋된 행에는 언제나 있다 |
| `expires_at` | 이 시각이 지나면 파일을 지운다 |
| `deleted_at` | 파일을 실제로 지운 시각. 비어 있으면 아직 있다 |

**보관 기간으로는 행을 지우지 않는다.** 파일을 지우고 `deleted_at` 만 적는다.
그래야 지난 대화를 열었을 때 그 자리에 사진이 있었다는 것이 남고,
화면이 「보관 기간이 지나 볼 수 없습니다」를 보일 수 있다.

`deleted_at` 이 비어 있는지가 볼 수 있는지를 정한다. `expires_at` 은 언제 지울지만 정한다.
둘로 판정하면 지우는 일이 늦었을 때 화면과 디스크가 어긋난다.

사용자가 대화를 지우면 정리 작업이 그 대화의 첨부 파일과 행을 함께 지운다. 지운 대화는 다시 열 수 없어 자리를 남길 까닭이 없다.

`message_id` 가 비어 있는 행은 올렸지만 보내지 않은 것이다.
그 행도 `expires_at` 이 지나면 함께 지운다.

같은 메시지의 사진은 `position` 오름차순으로 화면, Hermes 입력, 사용자가 보는 사진 순번에 함께 쓴다.
지운 사진도 그 자리를 차지한다.

근거는 [ADR-020](adr/ADR-020-사진은-공유-디렉터리에-두고-에이전트가-파일로-읽는다.md) 에 있다.

### chat_artifact

에이전트가 turn 안에 대화의 결과물 폴더에 만들거나 고친 HTML 파일 하나가 한 행이다.
본문은 파일로 두고 여기에는 그 파일을 가리키는 것만 둔다. 엔티티는 `ChatArtifact` 다.

| 칸 | 뜻 |
| --- | --- |
| `conversation_id` | 어느 대화의 폴더인가. 폴더 이름이기도 하다 |
| `message_id` | 이 파일을 만든 turn 의 답 메시지 |
| `path` | 대화 폴더 안의 상대 경로. `/` 로 나눈다 |
| `byte_size` | 찾았을 때의 크기 |
| `deleted_at` | 보관 기간이 지나 파일을 지운 시각. 지운 뒤 적지 못한 행은 다음 정리가 파일이 없다고 확인한 시각이다 |

`(message_id, path)` 에 유일 제약이 있다. 같은 파일을 다음 turn 이 다시 고치면 그 turn 의 답에 새 행이 생긴다.
화면은 답마다 그 답의 행을 보인다. 파일은 하나이므로 옛 답에서 열어도 지금 내용이 보인다.

**보관 기간으로는 행을 지우지 않는다.** 첨부와 같다. 사용자가 대화를 지우면 정리 작업이 폴더와 행을 함께 지운다. 파일이 지워지면 `deleted_at` 을 적어 화면이 「보관 기간이 지나 볼 수 없습니다」를 보인다.
근거는 [ADR-027](../../docs/adr/ADR-027-에이전트가-만든-html-은-대화별-폴더에-두고-스크립트-없이-보인다.md) 에 있다.
지운 뒤 표시에 실패한 행을 다시 맞추는 규칙은 [`backend/docs/flow.md`](flow.md) 의 「지운 표시를 다시 맞추기」 가 갖는다.

### result_delivery

자동 turn 하나가 부모 대화에 넘긴 결과들의 묶음이다. 그 turn 이 알림 줄을 저장할 때 생긴다. 엔티티는 `ResultDelivery` 다.
결정은 [ADR-075](../../docs/adr/ADR-075-결과-전달은-묶음과-시도로-남기고-사용자가-저장된-결과만-다시-전달한다.md), 상태가 바뀌는 흐름은 [`backend/docs/flow.md`](flow.md) 의 「결과 전달이 끝나지 않았을 때」 에 있다.

| 칸 | 뜻 |
| --- | --- |
| `id` | 화면과 다시 전달 경로가 쓰는 번호다. 대화 주인만 그 대화의 묶음을 부른다 |
| `status` | 마지막 시도의 끝과 같다 |
| `attempt_count` | 지금까지 만든 시도 수. 다시 전달할 때 하나 늘린다 |

**다시 전달은 `status` 를 `FAILED` 나 `STOPPED` 에서 `DELIVERING` 으로 바꾸는 조건부 update 로 시작한다.** 바뀐 줄이 없으면 시작하지 않는다. 한 묶음에 도는 시도가 둘이 되지 않는 근거다.
묶음과 시도의 상태는 같은 트랜잭션에서 바꾼다.

외래 키를 두지 않는다. `connector_action` 과 같이 대화가 지워져도 기록을 남긴다. 지운 대화의 묶음은 다시 전달하지 못한다.

### result_delivery_item

묶음에 든 결과 하나가 한 행이다. 엔티티는 `ResultDeliveryItem` 이다.

| 칸 | 뜻 |
| --- | --- |
| `id` | 넣은 순서다. 다시 전달할 때 이 순서로 입력을 만든다 |
| `source`, `result_key` | 결과를 낸 쪽과 그쪽의 결과 이름. 위임 결과(`DELEGATION`)는 `agent_execution.id`, 승인한 커넥터 호출의 결과(`CONNECTOR_ACTION`)는 `connector_action.public_id` 의 UUID 글이다 |

`(source, result_key)` 에 유일 제약이 있다. 한 결과는 한 묶음에만 든다. 같은 결과를 두 자동 turn 이 함께 넘기려 하면 뒤의 트랜잭션이 알림 줄까지 함께 되돌아간다.

결과 본문은 여기 두지 않는다. 다시 전달할 때 실행 줄의 `output_text` 와 승인 줄의 `result_text` 를 다시 읽는다.

### result_delivery_attempt

묶음을 부모에 넘긴 한 번이 한 행이다. 첫 시도는 묶음과 함께, 다음 시도는 다시 전달할 때 생긴다. 엔티티는 `ResultDeliveryAttempt` 다.

| 칸 | 뜻 |
| --- | --- |
| `execution_id` | 이 시도의 부모 turn 실행 줄. 실행 줄을 만들면 채운다. 그 전에 실패했거나 내려갔으면 비어 있다 |
| `notice_message_id` | 이 시도가 저장한 마지막 `SYSTEM` 줄. 화면은 묶음의 마지막 시도의 이 줄 아래에 상태와 버튼을 그린다 |
| `error_code` | `FAILED` 의 원인. 부모 turn 의 오류 코드이거나, 실행 줄이 생기기 전에 내려간 `INTERRUPTED` 다 |

`(delivery_id, attempt_no)` 에 유일 제약이 있다. 색인은 `(status, started_at)` 과 `(execution_id)` 다.
기동할 때 앞 프로세스가 남긴 `RUNNING` 시도를 `started_at` 으로 고르고, 기동 정리가 실행 줄을 적을 때 `execution_id` 로 찾는다.

`execution_id` 와 `notice_message_id` 에는 외래 키를 두지 않는다. 그 줄이 지워져도 시도의 끝은 남는다.

### execution_question

사람이 보낸 대화 turn 의 실행과 그 질문 메시지를 잇는다. 한 실행이 한 줄이다. 표는 `V20261007015950__memory_capture.sql` 이 만들고 엔티티는 `ExecutionQuestion` 이다.
`memory_remember` 가 바로 저장할 수 있는 실행인지 이 줄로 판정한다([ADR-20261007 / memory-remember](../../docs/adr/ADR-20261007-memory-remember.md)).

| 칸 | 뜻 |
| --- | --- |
| `execution_id` | 기본 키. turn 의 루트 실행 |
| `message_id` | 그 turn 의 질문(`USER` 메시지). 새 질문이면 방금 저장한 메시지, 다시 생성이면 이미 있던 질문 |

- 새 질문과 다시 생성의 실행에만 남긴다. 예약 작업, 먼저 살펴보기, 맡긴 일의 결과를 전하는 turn, 맡겨서 도는 실행은 줄이 없다
- 외래 키를 걸지 않는다. 읽는 쪽(`TurnQuestions`)은 메시지가 없거나, `USER` 가 아니거나, 그 실행의 사용자가 보낸 것이 아니면 없는 줄로 본다
- 줄을 남기지 못해도 turn 은 잇는다. 그 실행의 `memory_remember` 는 제안으로만 남는다
- 질문 줄이 없는 루트 실행이 대화에 하나라도 있으면 그 대화의 `memory_remember` 는 그 뒤로 늘 제안으로 남는다. 줄을 남기지 못한 실행과 이 표가 생기기 전의 실행도 센다. 판정은 [`backend/docs/flow.md`](flow.md) 의 「바로 저장 판정」 5 와 [ADR-20261008 / memory-remember-guard](../../docs/adr/ADR-20261008-memory-remember-guard.md) 가 갖는다

## 실행 표

에이전트 실행 한 번의 기록과 그 실행에 딸린 사건, 자식 사용량 재조회 작업, 스킬 호출, 하위 에이전트 session 등록, 실행 문맥 출처를 저장하는 표를 다룬다.
칸과 타입, 색인은 마이그레이션과 각 표의 엔티티가 갖는다. 이 문서는 코드만으로 알 수 없는 칸의 뜻, 유일 제약과 FK 를 두지 않는 까닭, 지울 때 함께 지워지는 것을 적는다.

### agent_execution

에이전트가 한 번 답한 기록이다.
대부분 대화에 속해 대화 하나에 여러 줄이 달리고, 한 줄이 곧 메시지 하나다.
추천 질문을 만드는 실행과 가치 평가의 시스템 판단 실행은 대화 없이 한 줄만 생긴다. 엔티티는 `AgentExecution` 이다.

**이 줄은 실행이 끝난 뒤가 아니라 시작할 때 만들어진다.**
근거는 [ADR-011](adr/ADR-011-실행은-시작할-때-기록하고-끝날-때-갱신한다.md)에 있다.

| 칸 | 뜻 |
| --- | --- |
| `conversation_id` | 비어 있으면 대화 밖에서 돈 실행이다. 추천 질문을 만드는 실행과 가치 평가의 시스템 판단 실행(`ExecutionRecorder.startSystem`)이 그렇다 |
| `agent_id` | 어느 에이전트의 실행이었는가. 칸은 NULL 을 받는다(V4). 시스템 판단 실행은 사용자 에이전트가 아니라 설치 설정의 시스템 profile 로 돌아 비어 있다 |
| `parent_execution_id` | 이 실행을 부른 실행. 사용자가 부른 것이면 비어 있다 |
| `root_execution_id` | 이 실행이 속한 트리의 루트. 루트 자신은 비어 있다 |
| `retry_of_execution_id` | 같은 turn 을 다른 모델로 다시 시도한 실행이 가리키는 직전 실행. 지금은 채우는 경로가 없어 새 실행은 늘 비어 있다 |
| `hermes_run_id` | 실행을 제출한 직후에 적는다 |
| `hermes_session_id` | 이 실행이 속한 Hermes session. 대화 turn 은 그 대화의 루트 session 이고, 루트가 없는 옛 대화는 보낸 session 이다. 압축 교체 뒤에는 보낸 session 과 다를 수 있다. 흐름의 하위 실행과 위임한 자식은 Control Plane 이 정한 `fos-<uuid>` 다. 제출하기 전에 적는다. 최상위 session 의 MCP 호출과 최상위 자식의 등록이 서명한 루트 session 과 `profile_name` 으로 도는 실행을 찾을 때 쓴다([ADR-032](adr/ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md)). 하위 에이전트 session 은 이 칸이 아니라 `hermes_session_binding` 으로 찾는다. 이 칸이 생기기 전의 실행과 Memory 제안, 추천 질문을 만드는 실행은 비어 있다 |
| `delegation_key` | `agent_delegate` 로 만든 실행만 채운다. 유일하다. 같은 호출이 다시 와도 실행을 하나만 만든다. `agent_status` 와 `agent_stop` 은 이 칸이 있는 실행만 답한다. 값을 만드는 규칙은 [ADR-032](adr/ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md) 에 있다 |
| `output_text` | `agent_delegate` 로 만든 실행이 끝났을 때의 답. `agent_status` 와 `agent_stop` 이 `SUCCEEDED` 와 `CANCELLED` 에서 돌려준다. 끝난 상태와 같은 저장에서 적는다. `assistant.delegation.output-max-chars` 를 넘으면 자르고 잘렸다는 한 줄을 붙인다. 다른 실행은 채우지 않는다(대화 답은 `chat_message` 가 갖는다) |
| `result_delivered_at` | 위임 실행의 끝난 결과를 부모에게 전한 시각. 부모가 `agent_status` 나 `agent_stop` 으로 끝난 상태를 받았거나, Control Plane 이 부모 대화를 깨운 turn 에 넣었을 때 적는다. `agent_delegate` 가 줄을 만든 뒤 제출 전에 끝나 `SUBMIT_FAILED` 를 돌려줄 때도 적는다. 부모가 번호를 모르는 결과를 다시 전하지 않기 위해서다. 이 칸이 생기기 전에 끝난 위임 실행은 마이그레이션이 `finished_at`(없으면 그때 시각)으로 채워 깨우지 않는다. 그때 `RUNNING` 이던 줄은 비워 두며, 기동 정리가 끝난 상태로 적은 뒤 전한다([`backend/docs/flow.md`](flow.md) 의 「기동할 때 남은 실행 정리」). 비어 있고 `SUCCEEDED` 나 `FAILED` 인 위임 실행이 깨울 대상이다([ADR-040](adr/ADR-040-위임-결과는-control-plane-이-부모-대화의-다음-turn-을-열어-전한다.md)). 먼저 살펴보기가 끝나면 그 트리의 위임 줄에 도는 중이어도 적는다([`backend/docs/flow.md`](flow.md) 의 「끝날 때」). 저장소의 조건부 update 로만 채우고 엔티티 저장에서는 빠진다(`updatable = false`) |
| `provider`, `model` | 실제로 돈 provider 와 모델. Hermes 의 session 이 답한 값이고, 읽지 못하면 요청한 값이다. 기본값으로 보냈고 둘 다 읽지 못하면 비어 있다 |
| `reasoning_effort` | 이 실행에 요청한 effort. 기본값으로 보냈으면 비어 있다. 이 칸이 생기기 전의 실행도 비어 있다 |
| `reasoning_defaults_checked_at` | `reasoning_effort`가 비어 있고 profile 기본값을 정상 응답으로 읽어 확인한 시각. 응답에 effort가 없어도 적어 같은 실행을 다시 조회하지 않는다. 조회 실패면 비워 다시 시도한다 |
| `reasoning_effort_source` | `reasoning_effort` 의 출처. 이 칸이 생기기 전의 실행은 비어 있고 추정해 채우지 않는다 |
| `model_tier` | 실행을 시작할 때 해석한 모델 단계. 단계 없이 돈 실행은 비어 있다 |
| `cost_mode` | 실행 당시 에이전트의 과금 방식 |
| `event_observation` | 사건 관측 범위. 아래 「사건 관측 범위」 가 갖는다. 실행 성공과 자식 비용 확정 여부와는 별개다 |
| `input_tokens`, `cached_input_tokens`, `output_tokens`, `total_tokens` | provider 가 알려준 것만 채운다 |
| `context_chars` | 이 실행의 공통 답변 지침과 Memory 문맥의 글자 수. Memory가 없어도 공통 지침은 센다. 뒤에 붙는 묻는 형식 안내와 다시 생성 지시는 세지 않는다 |
| `context_omitted_items` | 자리가 없어 이 실행의 문맥에서 빠진 Memory 항목 수. 이 칸이 생기기 전의 실행과 문맥을 조립하지 않은 실행은 비어 있다 |
| `runtime_fingerprint` | 실행 당시 Hermes 의 고정 프롬프트 구성을 가리키는 지문. 그 값을 주는 HTTP 경로가 아직 없어 지금은 항상 비어 있고, 그동안 사용량 화면의 지문 축은 빈 목록을 돌려준다 |
| `instructions_hash` | 공통 답변 지침과 Memory 문맥의 SHA-256 앞 16바이트를 16진수로 적은 값. 본문은 개인 Memory를 담을 수 있어 저장하지 않는다. 뒤에 붙는 묻는 형식 안내와 다시 생성 지시는 제외한다. Memory가 없어도 공통 지침의 지문을 기록한다 |
| `estimated_cost_micros` | 공개 API 가격으로 환산한 금액. 통화 단위의 100만분의 1 |
| `actual_cost_micros` | 실제로 청구되는 금액. 구독 경로는 비어 있다 |
| `pricing_version` | 이 금액을 계산한 가격표 |
| `request_received_at`, `submitted_at`, `first_delta_at` | `finished_at` 과 함께 실행 구간을 표시한다. 첫 assistant delta 의 본문은 저장하지 않는다 |

토큰 수는 실행 한 번의 **합계**다.
실행 안에서 LLM 호출이 여러 번 일어나고 그 내역은 오지 않는다.

자식 실행의 토큰은 부모의 합계에 들어 있지 않다.
근거는 [`hermes/docs/hermes-contract.md`](../../hermes/docs/hermes-contract.md) 의 「자식 session 으로 결과와 토큰을 보완한다」 절에 있다.
그래서 부모와 자식을 더한 합계는 실행 트리의 줄을 더해서 만든다.
실행 줄이 없는 native 자식은 `subagent_usage_job` 줄을 더한다.
어느 줄도 두 번 세지 않는다.

`CANCELLED` 는 중지한 turn 과 `agent_stop` 으로 멈춘 위임 실행에 쓴다.

`hermes_session_id` 와 `status` 에 함께 색인을 둔다. 최상위 session 의 MCP 호출과 최상위 자식의 등록마다 서명한 루트 session 으로 도는 실행을 찾기 때문이다.
그 session 을 가진 도는 실행이 둘 이상이면 어느 쪽도 부모로 쓰지 않고 거절한다. 대화 하나에는 도는 turn 이 하나뿐이라 보통 생기지 않는다.

금액을 0 으로 채우지 않는다.
0 은 공짜라는 뜻으로 읽히기 때문이다.
환산액과 실제 청구액을 나눈 근거는
[ADR-014](adr/ADR-014-실제-청구액과-환산액을-나눠-적는다.md)에 있다.

#### 사건 관측 범위

새 실행은 Hermes에 제출할 때 `INCOMPLETE`로 적는다. 대화의 사건 수집 경로는 제출 직후 `OBSERVING`으로 바꾸며, 종료 사건을 받고 스트림이 정상적으로 닫히면 `OBSERVED`로 적는다. 종료 사건 없는 조기 EOF, 읽기·사건 저장 실패, 대기 상한이나 중지 유예 종료는 `INCOMPLETE`로 남긴다. 실패 표시를 뒤늦은 스트림 종료로 지우지 않는다. 기동 복구는 `OBSERVING`으로 남은 줄을 `INCOMPLETE`로 바꾸고 답과 확인한 사용량을 보존한다. 사건을 수집하지 않는 Flow 단계와 FOS 위임 등은 `INCOMPLETE`로 남는다. Hermes에 제출하지 않는 합성 루트는 `UNKNOWN`이다.

월 합계의 `observationIncompleteExecutions`는 같은 사용자와 시작 시각 구간의 끝난 `INCOMPLETE` 실행만 센다. 관리자에게만 보내며, 자식 건수나 비용에 더하지 않는다. 마이그레이션 이전의 `UNKNOWN` 실행은 경고 수에서 제외한다. 관측 여부를 추정하지 않고, 과거 실행이 배포 직후 경고 수를 채우지 않게 하기 위해서다. 화면은 과거 실행의 관측 범위를 확인하지 않았다는 안내를 유지한다.

#### 끝나지 않은 실행

`RUNNING` 인 줄은 사용량 목록에는 「도는 중」으로 보인다.
끝나지 않아 소요 시간과 금액은 비워 둔다.
월 비용 합계에서는 뺀다.
빼지 않으면 「가격을 찾지 못한 실행」 으로 세어져,
아직 안 끝난 것과 가격을 모르는 것이 한 숫자에 섞인다.

기동할 때 `RUNNING` 으로 남아 있는 줄은 Hermes 에 물어 정한다.
절차는 [`backend/docs/flow.md`](flow.md) 의 「기동할 때 남은 실행 정리」 가 갖는다.
그 경로가 실패로 적을 때 쓰는 `error_code` 넷(`ORPHANED`, `REMOTE_RUN_LOST`, `RECONCILE_TIMEOUT`, `RECONCILE_UNREACHABLE`)의 뜻은 [ADR-061](adr/ADR-061-재기동-때-남은-실행은-hermes-에-물어-정하고-도는-실행에는-다시-붙는다.md) 의 결정이 갖는다.
넷 모두 사용량 목록에서 「중간에 중단됨」 으로 보인다.

### execution_event

실행 하나가 도는 동안 일어난 일을 우리 이름으로 옮겨 적은 것이다.
Hermes 가 보낸 원래 payload 를 통째로 넣지 않는다. 엔티티는 `ExecutionEvent` 이고, 사건 종류는 `ExecutionEventType` 이 갖는다.

| 칸 | 뜻 |
| --- | --- |
| `sequence` | 그 실행 안에서의 순서. 1부터 센다. `execution_id` 와 함께 유일하다 |
| `tool_name` | 도구 사건일 때 채운다. 붙은 커넥터 서버의 도구는 Hermes 등록 이름 `mcp__<서버>__<도구>` 다 |
| `subagent_name` | 하위 에이전트 사건일 때 채운다 |
| `hermes_session_id` | 하위 에이전트가 따로 session 을 가지면 적는다 |
| `failed` | 완료 사건의 실패 여부. Hermes 가 알려주지 않으면 비운다 |
| `detail` | 하위 에이전트 사건이면 그 목표. 도구 사건은 ADR-047에 따라 비밀값과 UUID를 가린 뒤 저장하고, 응답에는 ADR-038이 정한 사람에게만 싣는다. 옛 커넥터 에이전트의 도구 내용과, 다른 에이전트에 붙은 커넥터 서버의 도구 내용은 전체를 가린다 |
| `model` | 하위 에이전트가 돈 모델. 하위 에이전트 사건에만 있다 |
| `input_tokens`, `output_tokens` | 하위 에이전트가 쓴 토큰. `SUBAGENT_COMPLETED` 에만 있다 |

**`subagent_name`은 이름, `subagent_id`, `goal` 순서로 채운다.**
이름이 없는 사건의 `preview` 에서 이름처럼 보이는 글자를 뽑아 채우지 않는다.
그것이 실제 이름인지 우리가 만든 것인지 구분할 수 없기 때문이다.

**`hermes_session_id`, `model`, 토큰은 SSE 또는 종료된 자식 session 조회에서 확인한 값이다.**
[`hermes/docs/hermes-contract.md`](../../hermes/docs/hermes-contract.md) 의 「자식 토큰을 SSE 로 받을 수 있다」 절이
`subagent.start` 와 `subagent.complete` 에 오는 칸을 적는다.
`child_session_id` 를 `hermes_session_id` 에, `goal` 을 `detail` 에 옮긴다.
싣지 않는 버전에서는 이 칸들이 비고 `detail` 에 `preview` 가 들어간다.

부모가 끝난 뒤에도 완료 사건이 없으면 [모델 단계와 실행 기록](flow.md)의 재조회로 보완한다.
`completed_child_session_id`는 자식 완료 사건의 중복 저장을 막는다.
`(execution_id, completed_child_session_id)`가 유일하며 다른 종류의 사건은 이 칸을 비운다.
이 칸이 생기기 전의 중복 완료 사건은 최신 한 줄에만 키가 있고 나머지 줄은 비어 있다.

이 토큰은 화면이 하위 에이전트가 무엇을 썼는지 보이는 데만 쓴다.
사용량 합계에 더하지 않는다. native 자식의 합계는 아래 `subagent_usage_job` 줄의 값으로 낸다.

`PROVIDER_SWITCHED` 는 옛 실행에만 남은 값이다. 앞 provider 가 막혀 다음 모델로 다시 시도했다는 뜻이고, 지금은 이 값을 적는 경로가 없다.

### subagent_usage_job

native 자식 한 명의 사용량 원장 줄이자, 그 사용량을 session 에서 조회하는 작업이다.
부모 실행이 끝나면 session 이 있는 시작 사건마다 한 줄이 생긴다.
재조회 규칙과 합계에 더하는 규칙은 [모델 단계와 실행 기록](flow.md) 의 「비동기 자식 사용량」 이 정한다.
근거는 [ADR-062](adr/ADR-062-native-하위-에이전트-사용량은-재조회-작업-줄을-원장으로-넓혀-합계에-더한다.md) 에 있다. 엔티티는 `SubagentUsageJob` 이다.

| 칸 | 뜻 |
| --- | --- |
| `execution_id` | 부모 실행 |
| `child_session_id` | 조회할 자식 session |
| `unconfirmed_reason` | 사용량이나 금액을 확인하지 못한 까닭. `EXPIRED` 줄과 금액 없는 `DONE` 줄에 적는다 |
| `expires_at` | 조회 기한. 부모 실행이 끝난 뒤부터 센다 |
| `provider` | 자식이 돈 provider. session 응답이 주지 않으면 대시보드 plugin 에서 읽고([ADR-067](adr/ADR-067-native-하위-에이전트의-provider-는-대시보드-plugin-이-session-저장소에서-읽어-준다.md)), 거기서도 읽지 못하면 비운다. 부모의 값으로 채우지 않는다 |
| `input_tokens` | cache 를 뺀 일반 입력 토큰 |
| `cache_read_tokens`, `cache_write_tokens` | cache 에서 읽은 입력과 cache 에 쓴 입력 |
| `estimated_cost_micros` | 공개 API 가격으로 환산한 금액. 확인하지 못하면 비운다 |
| `actual_cost_micros` | 부모 실행의 `cost_mode` 가 `API` 일 때만 환산액과 같은 값 |

`(execution_id, child_session_id)` 가 유일하다.
사용자, 에이전트, 달은 부모 실행에서 얻는다. 같은 값을 여기 다시 적지 않는다.
부모 실행의 토큰 칸과 달리 입력을 셋으로 나눠 적는다. 합계에 더할 때는 셋을 합쳐 부모의 `input_tokens` 와 같은 뜻으로 맞춘다.

### execution_skill_use

실행 하나에서 스킬 하나가 쓰인 것이 한 행이다. 스킬 호출 이력의 원천이다. 엔티티는 `ExecutionSkillUse` 다.

| 칸 | 뜻 |
| --- | --- |
| `source` | `COMMAND` 는 사용자가 `/이름` 으로 불렀다. `MODEL` 은 모델이 스스로 `skill_view` 로 읽었다 |

`(execution_id, skill_name, source)` 에 유일 제약이 있다. 한 실행에서 모델이 같은 스킬을 여러 번 읽어도 한 행이다.
호출 횟수는 실행 수로 센다. 같은 실행에 `COMMAND` 와 `MODEL` 이 함께 있어도 1회다.
사용자, 에이전트, 대화는 `agent_execution` 과 이어 얻는다. 같은 값을 여기 다시 적지 않는다.

**스킬을 지워도 행은 남는다.** 이름으로 남아 지난 호출을 읽을 수 있다.
`MODEL` 행은 실행 사건에 스킬 이름이 실려 올 때만 생긴다.

누가 이 이력을 어디까지 보는지와 근거는 [ADR-034](adr/ADR-034-올린-스킬은-control-plane-이-버전-디렉터리에-쓰고-hermes-는-읽기만-한다.md) 에 있다.

### hermes_session_binding

Hermes `delegate_task` 가 만든 하위 에이전트 session 이 어느 FOS 실행에서 시작됐는지 적는다.
엔티티는 `HermesSessionBinding` 이다. profile 플러그인이 `subagent_start` hook 에서 등록한다. 근거는 [ADR-037](adr/ADR-037-hermes-하위-에이전트-session-의-주인은-만들-때-등록한-줄로-정한다.md) 에 있다.

**한 번 적은 줄은 바꾸지 않는다.** 같은 대화의 다음 turn 이 시작돼도 그 하위 에이전트는 처음 origin 실행에 속한다.
대화 session 은 여러 turn 이 이어 쓰므로 여기 적지 않는다. 최상위 session 은 `agent_execution.hermes_session_id` 로 찾는다.

| 칸 | 뜻 |
| --- | --- |
| `profile_name` | 등록한 토큰이 증명한 profile |
| `session_id` | 하위 에이전트 session. Hermes 가 정한 값이다 |
| `user_id` | origin 실행의 `user_id`. MCP 호출의 요청자다 |
| `origin_execution_id` | 이 session 을 낳은 FOS 실행. 끝난 실행이어도 된다. 이 실행이나 그 루트 실행이 `CANCELLED` 면 MCP 호출을 거절한다 |
| `root_session_id` | 서명한 `parent_root_session_id`. MCP 호출의 서명한 루트와 다르면 거절한다 |
| `parent_session_id` | 이 session 을 만든 session. 최상위 session 이거나 다른 하위 에이전트 session 이다 |

| 제약 | 까닭 |
| --- | --- |
| `UNIQUE (profile_name, session_id)` | session id 가 profile 사이에서 유일하다고 보장하지 않는다. 한 profile 안에서 한 session 은 한 origin 에만 속한다 |

외래 키는 두지 않는다. `agent_execution` 도 사용자와 대화에 외래 키를 두지 않고, 실행 줄과 사용자는 지우지 않는다. 등록은 서버가 방금 읽은 실행에서 origin 과 사용자를 옮겨 적으므로 없는 실행을 가리키지 않는다.

같은 등록이 다시 오거나 다른 origin 으로 올 때의 응답은 [`hermes/plugins/fos-ctx/README.md`](../../hermes/plugins/fos-ctx/README.md#하위-에이전트-session-등록-계약) 의 「하위 에이전트 session 등록 계약」 이 갖는다. 동시에 두 요청이 와서 유일 제약에 걸리면 먼저 저장된 줄을 다시 읽어 같은 규칙으로 판정한다.

하위 에이전트의 하위 에이전트는 부모 등록의 `origin_execution_id` 와 `user_id` 를 그대로 잇는다. 하위 에이전트 몫의 `agent_execution` 줄은 만들지 않는다. 하위 에이전트는 지금처럼 `execution_event` 의 `SUBAGENT_STARTED`, `SUBAGENT_COMPLETED` 로 보인다.

Control Plane 이 다시 떠도 등록 줄은 그대로다. 이미 등록한 하위 에이전트는 계속 요청자를 찾는다. 기동 정리가 다시 붙은 실행은 `RUNNING` 으로 남아 있어 그 run 이 새로 만든 최상위 자식도 등록된다. 실패로 적힌 실행의 run 이 새로 만든 최상위 자식은 도는 부모가 없어 등록되지 않는다.

### execution_context_source

실행 하나에 실은 문맥 항목의 참조다. 어느 답에 어느 기록이 들어갔는지 나중에 찾으려고 남긴다.
조립한 항목은 실행 줄을 만들 때 한 번에 적고, `memory_read` 가 본문을 내 준 항목은 실행 중에 `MEMORY_READ` 줄로 그 뒤에 덧붙인다([`backend/docs/flow.md`](flow.md) 의 「본문을 읽으면 남는 기록」).
제목과 본문은 남기지 않는다. 항목의 뜻은 [`backend/docs/flow.md`](flow.md) 가 갖는다. 표는 `V70__execution_context_source.sql` 이 만들고 엔티티는 `ExecutionContextSource` 다.

| 칸 | 뜻 |
| --- | --- |
| `position` | 그 실행의 문맥 안에서의 순서. 0 부터. 덧붙이는 줄은 그 실행의 마지막 순서 다음 값이다 |
| `source_ref` | 항목의 `ref`. `memory:<번호>` 처럼 원래 기록을 가리킨다 |
| `body_mode` | 본문을 실었는지. `OMITTED` 의 뜻은 아래에 있다 |

기본 키는 `(execution_id, position)` 이다.
`OMITTED` 줄은 자리가 없어 빠진 Memory 항목이거나 결과를 알 수 없어 본문을 싣지 않은 승인 결과다. `source` 가 `MEMORY_` 로 시작하는 `OMITTED` 줄의 수는 `agent_execution.context_omitted_items` 와 같다.
실행 줄을 지우지 않으므로 이 줄도 지우지 않는다.

## Memory 표

Memory 항목과 그 판, 그룹의 collection 목록, 에이전트가 받는 collection 과 그 변경 기록을 저장하는 표에서 마이그레이션만 읽어서는 알 수 없는 칸의 뜻과 제약의 까닭을 갖는다.
칸과 타입, 색인은 마이그레이션(`V7__memory.sql`, `V47__memory_v2.sql` 과 그 뒤의 Memory 마이그레이션)이 갖고, 값의 목록은 각 enum 이 갖는다.
실행에 실을 항목을 고르는 규칙은 [`backend/docs/flow.md`](flow.md) 가 갖는다.

### memory

사용자와 그룹에 대해 에이전트가 알아야 할 것 하나가 한 줄이다.
종류가 `MEMORY` 인 줄은 사실 하나이고, `DOCUMENT` 는 이름으로 찾는 긴 글 하나, `SOURCE` 는 출처로 남긴 원문 하나다.
지금 있는 화면과 제안이 만드는 줄은 모두 `core` collection 의 `MEMORY` 다. 문서 API 가 만드는 줄은 `USER` 범위의 `DOCUMENT` 이고 `ACCEPTED` 다.

- `scope` 가 `USER` 면 `owner_user_id` 가, `GROUP` 이면 `group_id` 가 주인이다. `scope` 에는 기본값이 없다
- `content` 는 민감 항목이면 암호문이다. `content_key_id` 가 비어 있으면 평문이다. `SENSITIVE` 인 줄은 key 가 있을 때 기동하며 채우므로, key 가 없으면 평문으로 남은 줄이 비어 있을 수 있다
- `retrieval` 의 뜻은 `MemoryRetrieval` 이 갖는다. `SENSITIVE` 는 `ALWAYS` 로 둘 수 없다
- `always_inject` 는 `retrieval` 로 옮긴 옛 칸이다. `retrieval` 이 `ALWAYS` 일 때만 참으로 적고 읽지 않는다. 한 배포 뒤에 지우기로 했지만 지우는 마이그레이션은 아직 없다
- `revision` 은 지금 값의 판 번호다. 1 에서 시작하고 본문이나 `retrieval` 이나 `sensitivity` 를 고칠 때마다 1 씩 는다. 승인과 거절은 올리지 않는다
- `source_type` 과 `source_ref` 는 출처다. 사람이 직접 적었으면 비어 있다. 다른 항목이면 `source_ref` 가 `memory:<번호>` 다. 예전에 기존 개인 지식에서 들인 줄은 `source_type` 이 `brain` 이고 `source_ref` 가 `<namespace>/<저장소 안의 경로>` 다. 지금 코드에는 줄을 들이는 경로가 없다
- `proposed_by_execution_id` 는 이 항목을 제안하거나 `memory_remember` 로 바로 저장한 실행이다. 사람이 직접 적었으면 비어 있다
- `proposal_dedup_key` 는 제안하거나 바로 저장한 사용자, 제목, 본문의 해시다. 직접 등록한 항목과 민감 항목은 비어 있다

**`ACCEPTED` 인 항목만 주입한다.**
`PROPOSED` 는 사람이 아직 보지 않은 것이고, 에이전트가 그것을 사실로 쓰면 안 된다.

주입할 때 요청자의 `USER` 항목과 요청자가 속한 그룹의 `GROUP` 항목 가운데, 그 실행의 에이전트가 받는 `collection` 의 항목만 고른다.
`SENSITIVE` 항목은 그 `collection` 에서 민감 항목을 허용받은 에이전트에만 고른다.
`SOURCE` 와 `ARCHIVE` 는 고르지 않는다.
다른 사용자의 `USER` 항목과 받지 않는 `collection` 의 항목은 고르는 단계에서 빠지므로 Hermes 로 나가는 문자열에 들어가지 않는다.

| 유일 제약 | 칸 | 막는 것 |
| --- | --- | --- |
| `uk_memory_proposal_dedup` | `proposal_dedup_key` | 같은 제안이 동시에 들어와 두 행이 되는 것 |
| `uk_memory_user_document` | `owner_user_id`, `collection`, `document_key` | 한 사용자의 한 `collection` 에 같은 이름의 문서가 둘 생기는 것 |
| `uk_memory_group_document` | `group_id`, `collection`, `document_key` | 한 그룹의 한 `collection` 에 같은 이름의 문서가 둘 생기는 것 |
| `uk_memory_user_source` | `owner_user_id`, `source_type`, `source_ref` | 한 사용자의 같은 출처가 두 줄이 되는 것. 출처가 없는 줄은 `source_ref` 가 비어 걸리지 않는다 |

주인 칸이 범위에 따라 달라 문서 제약을 둘로 둔다. 비어 있는 칸은 유일 검사에 들지 않으므로 `document_key` 가 없는 줄은 걸리지 않는다.

#### 옛 판으로 되돌렸다가 다시 올릴 때

V47 이전 판으로 되돌린 동안 옛 코드가 쓴 줄은 다시 올리기 전에 맞춘다.
`always_inject` 와 `retrieval` 이 어긋난 줄은 `always_inject` 를 따라 `retrieval` 을 `ALWAYS` 나 `SEARCH` 로 고친다.
`agent_memory_collection` 에 줄이 없는 에이전트에는 `core` 를 넣는다. 옛 커넥터 에이전트는 뺀다.
되돌린 동안 고치거나 지운 Memory 는 `memory_revision` 에 판이 남지 않는다.

근거는 [ADR-003](adr/ADR-003-memory-권한은-주입으로-강제한다.md), [ADR-012](adr/ADR-012-memory-는-사람이-승인한-것만-남는다.md),
[ADR-052](adr/ADR-052-memory-는-collection-종류-꺼내는-방식-민감도-판-출처를-가진다.md),
[ADR-053](adr/ADR-053-에이전트는-허용된-collection-의-memory-만-받는다.md),
[ADR-058](../../docs/adr/archive/ADR-058-기존-개인-지식-저장소는-주인이-검토한-묶음을-화면에서-올려-들여온다.md) 에 있다.

### memory_revision

지금 값에서 물러난 판 하나가 한 줄이다. 고치면 고치기 전의 값을, 지우면 마지막 값을 남긴다.
지금 값은 `memory` 에 있고 이 표에는 없다.

- `memory_id` 는 지운 항목의 판도 남으므로 `memory` 에 없는 번호일 수 있다. 그래서 외래 키를 걸지 않는다
- `scope`, `owner_user_id`, `group_id` 는 그때의 범위와 주인이다. 지운 뒤에도 누가 볼 수 있는지 이 칸으로 정한다
- `entry_type`, `document_key` 는 그때의 종류와 문서 이름이다. 지운 문서의 판을 이름으로 찾는다
- `status` 는 그때의 승인 상태다. 지운 항목이 제안이었는지 받아들인 항목이었는지 남는다
- `content` 는 그때 민감 항목이었으면 암호문이다. 일반 항목이었어도 그 항목이 뒤에 민감 항목이 되면 암호문으로 바뀐다. 그래서 판이 암호문인지는 `sensitivity` 가 아니라 `content_key_id` 로 본다
- `changed_by_user_id` 는 이 판을 물러나게 한 사용자다. `reason` 은 지금 있는 화면이 적지 않는다

한 항목을 두 번 고치고 지우면 줄이 셋이다. 판 1 과 2 가 `UPDATED` 로, 판 3 이 `DELETED` 로 남는다.
승인 전인 제안을 지워도 같은 방식으로 남는다.
출처 칸은 판에 남기지 않는다. 고칠 때 바뀌지 않는 값이다.
고치거나 지울 때 그 항목을 쓰기 잠금으로 읽는다. 두 요청이 같은 판 번호로 판을 남기려 하지 않게 한 번에 하나씩 돈다.

### memory_collection

그룹이 쓰는 collection 하나가 한 줄이다. 화면의 탭과 에이전트 접근 설정이 고를 목록이다.
누가 읽는지는 이 표가 정하지 않는다.

`collection_key` 는 `memory.collection` 에 적는 key 다.

그룹마다 `MemoryCollection.DEFAULT_KEYS` 로 시작한다.
마이그레이션이 사용자가 있는 그룹에 넣고, 그 뒤에 생긴 그룹은 목록을 처음 읽을 때 넣는다.
목록을 읽는 `MemoryCollectionService.collectionsOf` 는 `GET /api/v1/memory-collections` 와 관리자의 `/api/v1/admin/agents/{code}/memory-collections` 가 부른다. 판을 읽는 `MemoryService.revisionsOf` 는 아직 부르는 API 가 없다. 화면과 API 를 넓힐 때 연결한다.

### agent_memory_collection

에이전트가 받는 collection 하나가 한 줄이다. 근거는 [ADR-053](adr/ADR-053-에이전트는-허용된-collection-의-memory-만-받는다.md) 에 있다.

`allow_sensitive` 가 참이면 이 collection 의 `SENSITIVE` 항목까지 받는다.

**줄이 하나도 없는 에이전트는 Memory 를 받지 않는다.**
에이전트를 처음 저장할 때 `core` 한 줄을 민감 허용 없이 넣는다. 옛 커넥터 에이전트에는 넣지 않고, 줄이 있어도 옛 커넥터 에이전트는 받지 않는다.
그 뒤에는 `ADMIN` 이 관리자 영역에서 줄을 더하고 빼고 민감 허용을 바꾼다. 바꿀 때마다 `agent_memory_collection_change` 에 한 줄을 남긴다.
`created_at` 은 그 collection 을 붙인 시각이다. 민감 허용만 바꾸면 그대로 둔다.

### agent_memory_collection_change

`agent_memory_collection` 의 줄 하나가 바뀐 것이 한 줄이다. 지우지 않는다. 근거는 [ADR-20261008 / agent-memory-grants-admin](../../docs/adr/ADR-20261008-agent-memory-grants-admin.md) 에 있다.

- `allow_sensitive` 는 `GRANTED` 와 `SENSITIVE_CHANGED` 면 바꾼 뒤의 값이고, `REVOKED` 면 떼기 전의 값이다
- `changed_by_user_id` 는 바꾼 `ADMIN` 이다. 외래 키를 걸지 않는다
- `(agent_id, id)` 색인은 관리 화면의 최근 변경을 읽는 데 쓴다

한 번의 저장이 여러 collection 을 바꾸면 줄도 여럿이고 `changed_at` 이 같다.
바뀌지 않은 collection 은 남기지 않는다. 마이그레이션이 넣은 `core` 와 새 에이전트에 넣는 `core` 도 남기지 않는다. 사람이 바꾼 것만 남긴다.

### memory_capture

에이전트가 `memory_remember` 로 남긴 기록 한 줄이다. 대화의 답 아래에 「기억했어요」 와 제안 카드를 그리고, 되돌리기가 무엇을 되돌릴지 정한다.
도구와 API 는 [`backend/docs/flow.md`](flow.md) 의 「에이전트가 기억을 남기는 길」 이 갖는다.

- `memory_id` 는 만들거나 고친 항목이다. 되돌리거나 사람이 지우면 `memory` 에 없는 번호가 되므로 외래 키를 걸지 않는다
- `user_id` 는 origin 실행의 사용자이고, `execution_id` 는 기록을 남긴 origin 실행이다. 한 실행의 상한을 `execution_id` 로 센다
- `conversation_id` 는 그 실행의 대화다. 대화 밖의 실행이면 비어 있다. 대화의 기록 목록은 `(conversation_id, id)` 색인으로 읽는다
- `base_revision` 은 `CREATED` 면 저장한 때의 판 번호, `UPDATED` 면 고치기 전의 판 번호이고 `PROPOSED` 면 비어 있다
- `previous_status` 는 기존 제안을 받아들인 `CREATED` 만 `PROPOSED` 이고 나머지는 비어 있다
- `undone_at` 은 사람이 되돌린 시각이다. 되돌린 기록은 대화에 그리지 않는다

- 같은 사실을 같은 실행에서 두 번 남겨도 중복 키가 같은 항목을 하나로 둔다. 기록은 저장하거나 고친 경우에만 남는다
- `CREATED` 를 되돌릴 때 항목의 판이 `base_revision` 이 아니면, `UPDATED` 를 되돌릴 때 `base_revision + 1` 이 아니면 그 뒤에 사람이 다시 고친 것이다. 되돌리지 않는다
- 이미 있던 제안을 바로 저장 조건에서 받아들인 기록도 `CREATED` 다. `previous_status` 에 `PROPOSED` 를 남기고 되돌리면 항목을 보존하며 승인 정보도 지워 제안 상태로 돌린다

## 커넥터 표

커넥터 연결과 바인딩, 도구 호출의 판정과 승인, 상시 허락을 저장하는 표 넷에서 코드만으로 알 수 없는 것을 적는다.
칸과 타입, 색인은 마이그레이션이, 상태 값은 엔티티의 enum 이 갖는다.
상태가 바뀌는 조건과 API 는 [커넥터 연결](../../docs/prd.md) 이 갖는다.

### connector_connection

사용자마다 커넥터 하나에 연결 하나를 둔다. 근거는 [ADR-043](adr/ADR-043-커넥터는-plugin-의-connector-json-으로-선언하고-control-plane-은-범용-흐름만-갖는다.md) 이다.

- `fields` 는 JSON 텍스트 `{"values": {key: 값}, "secretPrefixes": {key: 앞 4자}}` 다. 비밀 칸의 원문과 해시는 넣지 않는다. 앞부분은 값이 16자 이상일 때만 넣는다. 16자 미만인 값은 앞 4자가 원문의 큰 부분이기 때문이다
- `undeclared_tools` 는 마지막 확인에서 MCP 서버가 낸 도구 가운데 manifest 의 `tools` 에 없던 수다. `schema: 1` 은 0 이다
- `vault_stored` 는 칸 값을 대시보드 plugin 의 보관 파일에 둔 적이 있는가다. 옛 연결은 연결 확인이 옛 profile 의 값을 보관 파일로 옮길 때 참이 된다
- `(user_id, connector_id)` 가 유니크다. 한 사람이 같은 커넥터를 둘 연결하지 못한다
- `agent_id`, `restart_required`, `desired_enabled` 는 연결을 에이전트에 붙이는 바인딩([ADR-083](../../docs/adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md))이 생기며 쓰지 않는 칸이 됐다. 이전 이미지로 되돌릴 때를 위해 남겨 두고, 칸을 지우는 마이그레이션은 옛 커넥터 에이전트를 정리할 때 둔다. `agent_id` 의 유일 제약과 FK 는 남아 있고 비어 있는 값은 유일 제약에 걸리지 않는다
- 엔티티는 이 세 칸을 매핑하지 않는다. 새 행에서 `agent_id` 는 비고 두 boolean 칸은 기본값 거짓으로 저장된다. 옛 행의 값은 고치지 않아 그대로 남는다
- 해제해도 행은 남기고 `fields` 를 `{"values": {}, "secretPrefixes": {}}` 로 비운다. 지우는 경로는 없다
- 칸 값이 비밀이 아닌지는 DB 가 아니라 Control Plane 이 manifest 의 `secret` 으로 판정해 지킨다
- `fields` 를 MySQL `JSON` 타입이 아니라 문자열로 둔다. 칸 안을 SQL 로 찾을 일이 없고, 검사가 쓰는 H2 와 MySQL 의 JSON 리터럴 문법이 달라 이관 SQL 을 한 벌로 쓸 수 없다. 엔티티는 변환기로 record 로 읽는다
- V40 이전에 저장한 행은 앞부분의 원래 길이를 알 수 없어 `secretPrefixes` 가 비어 있다

`agent.connector_managed` 는 바인딩이 생기기 전에 연결마다 만든 옛 커넥터 에이전트를 표시한다. 지금은 새로 참이 되지 않는다.
이 값이 참인 에이전트는 일반 설정 편집과 공개 범위 변경을 막고 사용자당 에이전트 상한에 세지 않는다. 지우기는 받는다. 사용자가 새 방식으로 옮긴 뒤 그 에이전트를 지울 길이 이것뿐이다.
남아 있는 동안의 규칙은 [커넥터 설치](flow.md) 의 「옛 커넥터 에이전트」 가 갖는다.

`agent.connector_attachments` 는 그 옛 커넥터 에이전트가 사진을 받는지다.
선언은 plugin 의 `connector.json` 에 있다. Control Plane 이 연결 확인과 관리자 반영 완료에서 선언한 toolset 이 켜진 것을 확인했을 때만 참으로 둔다.
옛 커넥터 에이전트가 아닌 에이전트에서는 쓰지 않는다.

### agent_connector_binding

에이전트에 연결을 붙인 것이다. 에이전트와 연결은 다대다다. 근거는 [ADR-083](../../docs/adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md) 이다.
연결의 `status` 는 「값이 확인돼 쓸 수 있는가」 이고, 바인딩의 `status` 는 「그 에이전트의 profile 에 설치되고 반영됐는가」 다.

- `mcp_server` 는 붙일 때 manifest 가 선언한 MCP 서버 이름이다. 비밀이 아니다. 실행마다 카탈로그를 읽지 않고, 운영자가 카탈로그에서 커넥터를 빼도 그 profile 에 설치된 서버 이름을 잃지 않게 둔다. 마이그레이션이 만든 옛 바인딩은 비어 있고 연결 확인과 관리자 반영 완료가 채운다
- `restart_required` 는 공유 gateway 재시작 뒤 반영 완료를 기다리는가다. 재시작은 profile 마다 필요하므로 연결이 아니라 여기에 둔다
- `restart_required_since` 는 재시작이 필요해진 가장 늦은 설치 시각이다. 관리자가 재시작한 뒤에 다시 설치가 있었으면 반영 완료가 대기를 풀지 않게 하려고 둔다
- `apply_due_at` 은 반영 예정 시각이다. 재시작 없이 공유 gateway 의 MCP 설정 맞추기 주기가 반영할 설치(`reload_pending`)를 보냈을 때 적는다. 이 시각이 지나면 Control Plane 이 반영 맞추기를 스스로 한 번 돌리고, 돌리기 전에 비운다. `READY` 가 되면 비운다([ADR-20261007 / connector-live-reload](../../docs/adr/ADR-20261007-connector-live-reload.md))
- `desired_enabled` 는 그 profile 에 설치가 성공해 반영 후보가 되었는가다. 설치를 시작하면 거짓이다. 참이어도 반영 확인 전에는 `PENDING` 이다
- `(agent_id, connection_id)` 가 유니크다(`uk_agent_connector_binding`). 한 에이전트에 같은 연결을 둘 붙이지 못한다
- `agent_id` 는 `agent` 를, `connection_id` 는 `connector_connection` 을 FK 로 가리킨다
- 떼면 행을 지운다. 떼기는 재시작을 기다리지 않아 남길 상태가 없다. 이력은 `connector_action` 이 갖는다
- 해제되지 않은 옛 연결에는 V80 이 그 연결 전용 에이전트와의 바인딩을 만들었다. 그 바인딩의 `restart_required_since` 는 연결의 `updated_at` 이다
- 옛 커넥터 에이전트(`agent.connector_managed` 가 참)의 바인딩만 그 에이전트를 켜고 끈다. `READY` 가 되면 켜고, `PENDING` 이 되면 끄고 사진 받기를 내린다. 다른 에이전트의 바인딩은 에이전트를 건드리지 않는다

### connector_action

커넥터 도구 호출 하나의 판정과, 승인이 필요했던 호출의 승인 줄이다. 근거는 [ADR-049](adr/ADR-049-커넥터-도구-호출은-profile-plugin-의-hook-이-control-plane-에-물어-판정한다.md) 과 [ADR-050](adr/ADR-050-커넥터-쓰기는-control-plane-이-승인-줄을-저장하고-승인한-인자로-한-번만-실행한다.md) 이다.

- `public_id` 는 화면과 모델에 보이는 승인 요청 번호다. 대화의 공개 식별자와 같은 방식이다([ADR-025](../../docs/adr/ADR-025-대화는-주소에-공개-식별자를-쓰고-번호는-안에만-둔다.md))
- `agent_id` 는 판정한 실행의 에이전트다. 그 에이전트에 붙은 바인딩으로 판정했고, 승인하면 그 에이전트의 profile 에서 실행한다. 바인딩 전에 남은 옛 줄은 옛 커넥터 에이전트를 가리킨다
- `tool_name` 은 MCP 서버의 원래 도구 이름이고 `hermes_tool` 은 hook 이 받은 등록 이름이다. 대응 파일에서 찾지 못했거나 등록 이름과 맞는 것을 확인하지 못한 호출은 `tool_name` 을 비운다
- `risk` 와 `approval_mode` 는 판정 당시의 값이다. 정책을 읽지 못했거나, 선언이 없었거나, 연결이 준비되지 않아 거절한 호출은 비운다
- `passed` 는 hook 에 통과로 답했는가다. `decision` 이 `ALLOWED` 일 때만 참이다
- `status`, `args_json`, `expires_at` 은 승인 줄에만 있다. `args_json` 은 hook 이 보낸 글자 그대로다
- `args_sha256` 은 인자 글의 SHA-256 이다. 원문을 두지 않는 줄에서도 무엇을 불렀는지 맞춰 볼 수 있다
- `dedupe_key` 는 `v1-connector`, profile, 루트 session, session, `tool_call_id` 를 줄바꿈으로 이어 SHA-256 한 값이고 유니크다. 같은 호출이 다시 와도 줄이 하나다
- `conversation_id` 는 결과를 돌려줄 대화다. 대화 없는 실행이면 비운다
- `result_text` 의 `FAILED` 줄은 커넥터가 선언한 오류 계약이 있을 때만 `{"kind": "connector_error", "code", "details", "recovery"}` 를 담고 없으면 null 이다([ADR-092](../../docs/adr/ADR-092-승인한-실행의-실패는-커넥터가-선언한-오류-코드와-복구-어휘와-정수-세부만-에이전트까지-전한다.md))
- 사용자가 거절한 것이 아니라 시스템이 실행하지 않고 끝낸 `REJECTED` 줄은 `error_code` 로 까닭을 남긴다. 값과 뜻은 `ConnectorAction` 의 상수가 갖는다. 승인할 때 다시 판정해 실행할 수 없는 줄, 연결을 해제하거나 다시 등록했거나 그 에이전트에서 연결을 뗀 줄, 사람이 다 읽지 못한 인자를 가진 줄이 여기 든다
- `result_delivered_at` 은 결과나 거절, 만료를 대화에 전한 시각이다. `agent_execution` 의 같은 이름 칸과 뜻이 같다
- 허용과 거절도 한 줄씩 남긴다. 사용자 수가 적어 양이 문제가 되지 않는다
- `NEEDS_APPROVAL` 인 줄은 `passed` 가 거짓이고 `status` 가 `PENDING` 으로 시작하며 `args_json` 과 `expires_at` 을 갖는다. 승인 엔진이 켜지기 전에 남은 `NEEDS_APPROVAL` 줄은 `status` 와 `args_json` 이 비어 있어 승인 줄로 다루지 않는다
- 외래 키는 `user_id` 와 `agent_id` 에만 둔다. 실행과 대화는 지워져도 이 줄을 남긴다
- 인자 원문은 주인에게만 보인다. 관리자 목록과 로그에는 싣지 않는다

### connector_tool_grant

사용자가 도구 하나에 준 상시 허락이다. 승인하면서 기간을 골라 준다. 근거는 [ADR-050](adr/ADR-050-커넥터-쓰기는-control-plane-이-승인-줄을-저장하고-승인한-인자로-한-번만-실행한다.md) 이다.

- `expires_at` 은 비지 않는다. 무기한 허락은 없다
- `revoked_at` 은 사용자가 거두었거나 연결을 해제한 시각이다. 선언이 상시 허락을 닫아 정리가 거둔 시각도 이 칸이다
- `revoked_at` 이 비고 `expires_at` 이 지금보다 뒤인 줄만 유효하다
- `approval: always` 인 도구와 선언이 `"grant": false` 인 도구에는 만들지 않는다. 판정할 때도 그 도구는 허락을 보지 않고, 선언이 닫은 도구(또는 `approval` 이 `required` 가 아니게 된 도구)에 남은 줄은 주기 정리가 `revoked_at` 으로 거둔다([ADR-065](../../docs/adr/ADR-065-외부로-나가는-도구는-상시-허락을-닫는-선언을-둔다.md))
- 같은 도구에 허락을 다시 주면 새 줄을 만든다. 유니크 제약은 없다

## 사용자 브라우저 표

사용자마다 하나씩 두는 브라우저의 표에서 코드만으로 알 수 없는 것을 적는다.
칸과 타입, 제약은 `V20261007051418__user_browser.sql` 이 갖는다.
상태 전이와 API 는 [`backend/docs/flow.md`](flow.md) 가 갖고, 결정은 [ADR-20261007 / user-browser](../../docs/adr/ADR-20261007-user-browser.md) 가 갖는다.

### user_browser

브라우저 하나다. 사용자 하나에 한 줄이다.

- `profile_key` 는 프로필 디렉터리 이름이다. `SHA-256("u" + user_id)` 의 소문자 16진수다. 첨부 디렉터리 키와 같은 계산이지만 루트가 다르다
- `last_active_at` 은 화면 입력이나 중계 통신이 마지막으로 있던 시각이다. 1분에 한 번까지만 쓴다
- `version` 은 낙관적 잠금이다. 전이가 한 번에 하나씩만 일어난다
- `user_id` 에 외래 키를 둔다. 사용자 줄이 지워지면 이 줄도 지워진다
- 쿠키, 저장소, 열린 주소, 화면 프레임은 이 표에 없다. 로그인 세션은 프로필 디렉터리에만 남는다

## 할 일과 먼저 알리기 표

할 일, 사용자의 숨기기와 미루기, 먼저 알리기의 지표 사건을 저장하는 표에서 마이그레이션만 읽어서는 알 수 없는 칸의 뜻과 제약의 까닭을 갖는다.
칸과 타입, 색인은 `V71__attention_control_event.sql` 과 `V72__follow_up.sql` 이 갖는다.
뜻과 판정은 [`backend/docs/flow.md`](flow.md) 와 [`backend/docs/flow.md`](flow.md) 가 갖는다.

### follow_up

- `conversation_id` 는 연결한 대화다. 에이전트의 제안은 그 실행의 대화이고, 직접 더할 때 고르지 않았으면 비어 있다.
- `proposed_by_execution_id` 는 제안한 실행이다. 사람이 직접 더했으면 비어 있다.
- `title_key` 는 정규화한 제목의 SHA-256 16진수다. 같은 할 일인지 이 칸으로 본다.
- `accepted_at` 은 받아들이거나 직접 더한 시각이고, `closed_at` 은 끝난 상태가 된 시각이다.

**`uk_follow_up_open_title`(`user_id`, `title_key`, `open_marker`)은 같은 할 일이 열린 채 둘이 되는 것을 막는다.**
`open_marker` 는 `PROPOSED` 와 `OPEN` 이면 1 이고 끝난 상태면 비어 있다. 유일 제약은 비어 있는 값을 서로 다르다고 보므로 끝난 줄은 걸리지 않는다. 이 칸은 유일 제약에만 쓴다.

외래 키는 사용자(`fk_follow_up_user`)에만 둔다. 대화와 실행이 지워져도 줄은 남는다. 대화는 숨기기만 하기 때문이다.

### attention_control

한 사용자의 한 카드의 한 `itemKey` 에 한 줄이다. 새 제어는 그 줄을 고친다.
**유일 제약 `uk_attention_control_item` 에 `card_key` 를 넣어 카드마다 제어를 나눈다.** 같은 대화가 두 카드에 나와도 제어가 서로 덮어쓰지 않는다.
`card_key` 는 API 의 소문자 카드 열쇠를 대문자로 쓴 값이고, 값의 목록은 `CardKey` 가 갖는다.
`state_key` 는 `HIDE` 일 때 숨긴 상태다. 판정의 `stateKey` 가 이것과 다르면 다시 보인다. `until_at` 은 `SNOOZE` 의 기한이다.

### attention_event

**유일 제약 `uk_attention_event_once`(`user_id`, `item_key`, `state_key`, `event_type`, `attention`)는 화면을 열 때마다 같은 사건이 쌓이는 것을 막는다.**
판정 칸이 제약에 들어 있어, 같은 상태에서 `LATER` 가 `NOW` 로 바뀌면 `NOW` 의 `SHOWN` 이 따로 남는다.
`stale` 은 그때 출처의 신선도가 `STALE` 이었다는 뜻이다.

`attention` 은 아래를 차례로 보고 처음 맞는 것을 쓴다.

1. 지금 응답에 그 항목이 있으면 그 판정이다. `HIDDEN`, `SNOOZED` 는 요청의 카드 안에서만 찾는다.
2. 없으면 그 요청자가 그 항목으로 남긴 가장 최근 `SHOWN` 의 판정이다. `OPENED`, `ACTED` 는 `stateKey` 까지 같은 `SHOWN` 만 보고, `HIDDEN`, `SNOOZED` 는 상태와 상관없이 본다.
3. 그것도 없고 숨겼거나 미뤄 억제 전 후보에만 있으면 `SUPPRESSED` 다.

그래서 한 번 보였다가 숨긴 항목의 사건은 `SUPPRESSED` 가 아니라 그때 보인 `NOW` 나 `LATER` 로 남는다.

`created_at` 의 색인은 보관 기간이 지난 줄을 지울 때 쓴다.
제목과 본문을 담는 칸이 없다.

## 알림 표

사용자에게 대화 밖에서 알리는 줄을 저장하는 표에서 코드만으로 알 수 없는 것을 적는다.
칸과 타입, 길이는 마이그레이션이, 종류와 갈 곳의 값은 엔티티의 enum 이 갖는다.
언제 만들고 화면이 어떻게 받는지는 [`backend/docs/flow.md`](flow.md) 가 갖는다.

### notification

알림 하나다. 근거는 [ADR-070](../../docs/adr/ADR-070-알림은-control-plane-의-notification-표가-원장이고-웹은-사용자-단위-SSE-로-받는다.md) 이다.

- `id` 는 Control Plane 밖으로 나가지 않는다. 화면과 API 는 `public_id` 를 쓴다. 대화의 공개 식별자와 같은 방식이다([ADR-025](../../docs/adr/ADR-025-대화는-주소에-공개-식별자를-쓰고-번호는-안에만-둔다.md))
- `body` 는 사람이 읽는 짧은 본문이고 빈 문자열을 받는다. 도구 인자 원문, 비밀값, 모델 답 전문을 넣지 않는다
- `target_type` 과 `target_public_id` 는 함께 채우거나 함께 비운다. 갈 곳이 없으면 둘 다 비운다. 목록 화면을 가리키는 `ADMIN_CONNECTIONS` 만 `target_type` 을 채우고 `target_public_id` 를 비운다
- `read_at` 이 비면 읽지 않은 알림이다
- `user_id` 에만 외래 키를 둔다. 갈 곳은 지워져도 알림을 남긴다
- 보관 기간이 지난 줄은 읽었는지와 상관없이 지운다. 원인이 된 승인 줄과 실행 기록은 따로 남는다

## 예약 작업 표

예약 작업과 그 시각, 발화 한 번을 저장하는 표 셋에서 마이그레이션만 읽어서는 알 수 없는 칸의 뜻과 제약의 까닭을 갖는다.
칸과 타입은 `V68__task.sql`, `V77__ProactiveScheduleTask`, `V20261008104800__task_model_tier.sql` 이 갖고, 값의 목록은 각 enum 이 갖는다.
언제 발화하고 어떻게 시작하는지는 [`backend/docs/flow.md`](flow.md) 가 갖는다.

### task

예약 작업 하나다. 근거는 [ADR-076](adr/ADR-076-예약-작업은-control-plane-이-갖고-발화한-실행은-대화-turn-경로로-돈다.md) 이다.

- `owner_user_id` 는 누구의 권한으로 도는가이고 바뀌지 않는다. `agent_id` 는 주인이 고칠 수 있다
- `kind` 는 `TURN` 이 보통 예약 turn, `CHECK` 가 매일 깨우기다. `CHECK` 는 `instruction` 과 `model_tier` 를 비운다
- `conversation_id` 는 `SINGLE` 일 때 결과를 쌓는 대화다. 첫 발화가 만들기 전에는 비어 있고, `NEW_PER_RUN` 은 늘 비어 있다
- `model_tier` 가 비면 발화가 대화의 모델 선택을 건드리지 않는다
- `archived_at` 은 `state` 가 `ARCHIVED` 일 때만 찬다

**`check_owner_user_id`, `check_agent_id` 생성 열은 `CHECK` 일 때만 주인과 에이전트 번호를 채운다.** 두 칸의 유일 제약으로 깨우기 설정 하나를 강제한다. `TURN` 줄은 두 칸이 비어 있어 걸리지 않는다.

- 외래 키는 `owner_user_id` 에만 둔다. 에이전트와 대화는 지워도 행이 남는 표라 걸지 않는다
- 지우면 `ARCHIVED` 로 둔다. 줄은 남는다. 발화 기록과 대화가 이 줄을 가리킨다

`CHECK` 는 켜고 끄기를 `ACTIVE`, `PAUSED` 로 저장하며 같은 설정 줄을 다시 쓴다.
대화는 점검 대화를 쓰므로 `conversation_mode`, `conversation_id` 로 대화를 만들지 않는다.
일반 예약 작업 API 는 `TURN` 만 읽고 바꾼다.

### task_trigger

작업의 시각이다. 지금은 작업 하나에 하나다. 근거는 [ADR-077](adr/ADR-077-발화는-trigger-와-예정-시각의-유일-제약으로-한-번만-만들고-놓친-발화는-작업마다-정한다.md) 이다.
`fire_at` 과 `next_fire_at` 은 UTC 이고, 한 번 발화한 `ONCE` 는 `next_fire_at` 이 비어 있다. `last_fired_at` 은 마지막으로 처리한 예정 시각이다.

- `task_id` 의 유니크는 작업 하나에 시각 하나라는 지금의 규칙이다. 다른 종류의 trigger 를 더하는 단계가 이 제약을 바꾼다
- 시각을 고치면 같은 줄을 고친다. 발화 기록의 유일 제약이 이 줄의 번호를 쓰므로 줄을 새로 만들지 않는다

### task_run

발화 한 번이다.

- `owner_user_id` 는 그 발화 때의 작업 주인이다. 하루 발화 수를 조인 없이 세려고 따로 둔다
- `reason` 은 `SKIPPED` 와 `FAILED` 의 까닭이다. `SUCCEEDED` 는 비어 있거나, 답이 `[SILENT]` 뿐이면 `NOTHING_TO_REPORT` 다. 값의 목록은 `TaskRunReason` 이 갖는다
- `conversation_id` 는 결과를 남긴 대화다. 시작 전에 끝난 줄은 비어 있을 수 있다
- `execution_id` 는 이 발화의 루트 `agent_execution` 이고, turn 이 끝난 모양을 받았을 때만 찬다
- `proactive_check_id` 는 `CHECK` 발화가 연 점검 줄이다. 저장과 발화 연결을 한 트랜잭션으로 끝낸다. 다음 tick 과 기동 복구가 점검 결과를 동기화한다

- `(trigger_id, scheduled_for)` 가 유니크다. 같은 예정 시각의 발화는 서버를 다시 띄워도 하나다
- 외래 키는 `task_id` 와 `trigger_id` 에 둔다. 작업과 시각은 지우지 않으므로 걸어도 된다
- `agent_execution` 은 바꾸지 않는다. 이 줄의 `execution_id` 가 가리키고, 그 아래 위임과 승인은 실행 트리로 이어진다

### conversation 에 더하는 칸

`conversation.task_id` 는 이 대화를 만든 예약 작업이고, 사용자가 연 대화는 비어 있다.
근거는 [ADR-078](../../docs/adr/ADR-078-예약-작업의-결과는-실행마다-새-대화가-기본이고-목록은-작업으로-묶는다.md) 이다. 외래 키는 두지 않는다. `conversation.agent_id` 와 같은 까닭이다.

## 먼저 살펴보기 표

먼저 살펴보기 한 번과 그 발견, 문제 후보, 가치 평가, 행동 정책의 판정과 사용자 선호, 매일 루프의 설정과 시도를 저장하는 표 여덟이다.
칸과 타입, FK, 색인은 마이그레이션이 갖는다. 이 문서는 칸 표로 알 수 없는 까닭과 지울 때의 연쇄를 갖는다.
이 표들을 읽고 쓰는 경로는 [`backend/docs/flow.md`](flow.md) 가 갖는다.
점검 대화는 [`backend/docs/data-schema.md`](data-schema.md) 의 `conversation.purpose` 로 가린다.

### proactive_check

살펴보기 한 번이다. 시작할 때 만들고 끝날 때 갱신한다. 실행별 토큰과 금액은 `agent_execution` 이 갖는다.
깨우기 예산을 측정할 트리 토큰 합계만 여기에도 남긴다.
살펴보기 한 번의 비용은 `root_execution_id` 로 그 트리의 실행 줄을 합쳐 얻는다([`backend/docs/flow.md`](flow.md) 의 「비용과 효과」).

`root_execution_id` 는 유일하다. 살펴보기 트리를 가리는 기준이라 실행 줄 하나가 두 살펴보기에 속하지 않게 한다. 실행 줄을 만들기 전에 실패하면 비어 있다.
`writes_allowed` 는 시작할 때 그 에이전트의 「먼저 살펴보기에 쓰기 도구 허용」 값을 옮겨 적는다. 도중에 관리자가 바꿔도 그 살펴보기의 경계는 옮겨 적은 값이 정한다(ADR-082).
`report_opened_at` 은 요청자가 「보고 열기」 를 누르거나, 점검 대화를 처음 읽거나, 그 대화에 메시지를 보낸 때 채운다.

색인은 마지막 살펴보기 읽기, session 을 바꿀지 세기, 열지 않은 보고 찾기에 쓴다.

`trigger` 는 MySQL 의 예약어라 칸 이름을 `trigger_type` 으로 둔다.

### proactive_check_finding

결과 블록의 발견 하나다. 다음 살펴보기가 최근에 알린 것을 입력에 싣는 데 쓰고, 「지금」 화면과 할 일로 이을 때 원래 기록이 된다.
살펴보기 줄을 지우면 함께 지운다(`ON DELETE CASCADE`).
`conversation_id` 는 입력에 실을 발견을 대화로 읽으려고 둔다. `proactive_check.conversation_id` 와 같다.

발견의 이유, 사실, 추정, 다음 행동은 대화 메시지에만 있고 이 표에 두지 않는다.
같은 글을 두 곳에 두면 사용자가 대화를 지워도 한쪽에 남는다.

### proactive_check_problem

결과 블록 버전 3의 문제 후보 하나다([ADR-093](adr/ADR-093-문제-찾기는-살펴보기-결과의-문제-후보로-받고-control-plane-이-근거와-중복을-결정적으로-검사한다.md)).
받아들인 것과 버린 것을 모두 남긴다. 다음 살펴보기의 중복 판정과 입력에 쓰고, 우선순위를 정하는 다음 단계가 읽는다.
칸의 뜻과 검사는 [`backend/docs/flow.md`](flow.md) 의 「문제 후보」 가 갖는다.
살펴보기 줄을 지우면 함께 지운다(`ON DELETE CASCADE`).

후보의 글은 모델이 쓴 글이고 대화에 그리지 않는다. 다섯 칸 보고(`report_json`)처럼 검사한 모양을 그대로 남긴다.
원문 본문과 커넥터 응답은 남기지 않는다.

### `proactive_value_evaluation`

가치 평가 시도 하나다. [가치 평가](flow.md)가 입력과 결과, replay 계약을 갖는다.
후보가 나온 살펴보기나 요청 사용자를 지우면 평가도 함께 지운다. 전체 개인 문맥과 원시 모델 응답을 저장하지 않는다.

### `proactive_autonomy_decision`

행동 정책의 판정 하나다. [행동 정책](flow.md)이 수준과 까닭, 실행 키 계약을 갖는다.

`execution_key` 는 유일하다. 원천 살펴보기 하나에서 자동 실행이 한 번만 열리게 하는 장치다. `EXECUTE` 만 채운다.
`candidate_id` 는 FK 가 없다. 평가 스냅샷의 후보 식별자라 지금의 후보 줄이 지워져도 판정이 남는다.
사용자, 평가, 원천 살펴보기를 지우면 함께 지운다. 시작한 살펴보기(`execution_check_id`)를 지우면 그 칸만 비운다(`ON DELETE SET NULL`).
`inputs_json` 에는 판정에 쓴 값만 두고 축 설명과 후보 글은 두지 않는다.

### `user_autonomy_preference`

줄이 없으면 모두 꺼짐이다. 사용자를 지우면 함께 지운다.
다른 종류의 자동 실행을 열 때 칸을 더한다. 한 칸이 여러 종류의 허락을 뜻하지 않게 한다.

### `proactive_loop_setting`

사용자가 에이전트마다 매일 루프를 켠 설정이다. [매일 루프](flow.md)의 「사용자 설정」 이 뜻을 갖는다.
줄이 없으면 꺼짐이다. 사용자나 에이전트를 지우면 함께 지운다.

`(user_id, agent_id)` 는 유일하다. 하루 상한을 셀 때 그 사용자의 줄을 모두 쓰기 잠금으로 읽는다.

### `proactive_loop_run`

매일 깨우기 살펴보기 하나를 잇는 시도다. 상태와 순서는 [매일 루프](flow.md)가 갖는다.

`source_check_id` 는 유일하다. 살펴보기 하나에 시도 하나라서, 서버가 도중에 멈춰도 같은 원천에서 평가와 판정이 두 번 생기지 않는다.
사용자나 원천 살펴보기를 지우면 함께 지운다. 이 시도가 만든 평가를 지우면 `evaluation_id` 만 비운다(`ON DELETE SET NULL`). 기동 때 닫은 줄도 비어 있다.
글과 원문, provider 이름은 두지 않는다. provider 는 평가의 `evidence_json` 이 갖는다.

## 판단 피드백 표

판단 피드백 사건을 저장하는 표다. 칸과 타입, FK, 색인은 `V20261007044901__decision_feedback_event.sql` 이 갖는다.
사건의 뜻, 기록 지점, 반응 읽기, 보관은 [`backend/docs/flow.md`](flow.md) 가 갖는다.

### decision_feedback_event

제안 하나의 사건 한 줄이다. 덧붙이기만 하고 고치지 않는다.
`subject_key` 가 같은 제안의 사건을 잇는 열쇠다. 지금 화면의 `itemKey` 와 같은 모양이다.

사용자를 지우면 함께 지운다(`ON DELETE CASCADE`). 다른 사용자는 관리자여도 읽지 못한다.
대화를 지우면 서비스가 `conversation_id` 로 그 대화의 사건을 지운다.
실행, 살펴보기, 행동 정책 판정을 지우면 그 번호 칸만 비운다(`ON DELETE SET NULL`).

제목, 본문, 인자, 결과 글, 모델이 쓴 글을 담는 칸이 없다. 바뀐 칸은 이름만 남기고 값은 담지 않는다.
색인은 읽기 모델, 대화 삭제, 보관 기간 정리가 쓴다.

## 암호화 key 표

암호화 key 를 저장하는 표에서 코드만으로 알 수 없는 것을 적는다. 칸과 타입, 색인은 마이그레이션이 갖는다.

### user_data_key

사용자 한 명의 데이터 key(DEK)를 KEK 로 감싼 것이다. 사용자마다 한 줄이다.
원문 key 는 저장하지 않는다. KEK 는 데이터베이스 밖의 서버 파일에 있고, 이 표에는 어느 KEK 로 감쌌는지만 적는다.
근거와 위협 모델, 감쌀 때의 AAD 는 [ADR-20261008 / data-encryption](adr/ADR-20261008-data-encryption.md) 에 있다.

- `id` 는 본문 칸 옆의 `*_key_id` 가 가리키는 번호다
- `kek_id` 는 감싼 KEK 의 id 다. KEK 를 바꾸면 기동 작업이 새 id 로 다시 감싸고 `rewrapped_at` 을 적는다
- `user_id` 가 유일하다. 사용자 한 명의 본문은 key 하나로 암호화한다. 같은 사용자의 첫 저장 둘이 겹치면 한쪽이 이 제약에 걸려 다시 읽는다
- `user_id` 에 외래 키를 두지 않는다. `conversation` 과 `agent_execution` 도 사용자에 외래 키가 없어, 메시지 저장이 이 표 때문에 실패하지 않게 한다

**이 줄을 지우면 그 사용자의 암호문은 누구도 풀지 못한다.** 사용자를 지우는 흐름이 생기면 이 줄을 지워 본문을 없앤다.
본문 칸 쪽은 이 표에 외래 키를 두지 않는다. 이 줄을 지우는 길을 막지 않기 위해서다.

## 옛 문서 경로

이미 적용된 마이그레이션의 주석은 Flyway 체크섬에 들어 고칠 수 없다. 그 주석이 가리키는 옛 경로가 어디로 갔는지 둔다.

| 옛 경로 | 지금 자리 |
| --- | --- |
| `docs/backend/schema/README.md` | 이 파일의 머리와 「마이그레이션 작성 규칙」 | <!-- ref-ignore: 적용된 마이그레이션 주석이 가리키는 옛 경로다 -->
| `docs/backend/schema/attention.md` | 이 파일 「할 일과 먼저 알리기 표」 | <!-- ref-ignore: 적용된 마이그레이션 주석이 가리키는 옛 경로다 -->
| `docs/backend/schema/browser.md` | 이 파일 「사용자 브라우저 표」 | <!-- ref-ignore: 적용된 마이그레이션 주석이 가리키는 옛 경로다 -->
| `docs/backend/schema/chat.md` | 이 파일 「대화 표」 | <!-- ref-ignore: 적용된 마이그레이션 주석이 가리키는 옛 경로다 -->
| `docs/backend/schema/execution.md` | 이 파일 「실행 표」 | <!-- ref-ignore: 적용된 마이그레이션 주석이 가리키는 옛 경로다 -->
| `docs/backend/schema/feedback.md` | 이 파일 「판단 피드백 표」 | <!-- ref-ignore: 적용된 마이그레이션 주석이 가리키는 옛 경로다 -->
| `docs/backend/schema/notification.md` | 이 파일 「알림 표」 | <!-- ref-ignore: 적용된 마이그레이션 주석이 가리키는 옛 경로다 -->
| `docs/backend/schema/proactive.md` | 이 파일 「먼저 살펴보기 표」 | <!-- ref-ignore: 적용된 마이그레이션 주석이 가리키는 옛 경로다 -->
| `docs/backend/schema/task.md` | 이 파일 「예약 작업 표」 | <!-- ref-ignore: 적용된 마이그레이션 주석이 가리키는 옛 경로다 -->
| `docs/backend/schema/users-agents.md` | 이 파일 「사용자와 에이전트 표」 | <!-- ref-ignore: 적용된 마이그레이션 주석이 가리키는 옛 경로다 -->
| `docs/backend/user-browser.md` | [`backend/docs/flow.md`](flow.md) 「사용자 브라우저」 | <!-- ref-ignore: 적용된 마이그레이션 주석이 가리키는 옛 경로다 -->
