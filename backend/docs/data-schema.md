# 저장 모델

Control Plane 의 표에서 칸 이름과 타입으로 알 수 없는 칸의 뜻을 갖는다. 표는 주제별로 아래 절에 나눠 적는다.
MySQL 8.4 에 둔다. 표와 칸, 타입, 색인(index)은 마이그레이션 `backend/src/main/resources/db/migration/` 이 소유하고, 상태 값은 엔티티의 enum 이 갖는다.
이 문서는 비었을 때의 뜻, 유일 제약과 FK 를 두거나 두지 않는 까닭, 지울 때 함께 지워지는 것처럼 코드만으로 알 수 없는 것만 적는다.

비밀값은 어느 표에도 넣지 않는다. 어디에 두는지는 [`docs/code-architecture.md`](../../docs/code-architecture.md) 의 「비밀값을 두는 곳」 이 갖는다.

| 절 | 표 |
| --- | --- |
| 「사용자와 에이전트 표」 | `app_user`, `allowed_person`, `agent`, `model_tier_definition`, `model_tier_group_setting`, `model_hidden`, `toolset_hidden`, `agent_toolset_request`, `agent_token`, `service_token`, `service_token_collection` |
| 「대화 표」 | `conversation`, `chat_message`, `chat_pending_message`, `chat_attachment`, `chat_artifact`, `result_delivery`, `result_delivery_item`, `result_delivery_attempt`, `execution_question` |
| 「실행 표」 | `agent_execution`, `execution_event`, `subagent_usage_job`, `execution_skill_use`, `hermes_session_binding`, `execution_context_source` |
| 「Memory 표」 | `memory`, `memory_revision`, `memory_collection`, `agent_memory_collection`, `agent_memory_collection_change`, `memory_capture` |
| 「커넥터 표」 | `connector_connection`, `agent_connector_binding`, `connector_action`, `connector_action_execution`, `connector_tool_grant` |
| 「할 일과 먼저 알리기 표」 | `follow_up`, `attention_control`, `attention_event` |
| 「알림 표」 | `notification` |
| 「판단 피드백 표」 | `decision_feedback_event` |
| 「먼저 살펴보기 표」 | `proactive_check`, `proactive_check_finding`, `proactive_check_problem`, `proactive_value_evaluation`, `proactive_autonomy_decision`, `user_autonomy_preference`, `proactive_loop_setting`, `proactive_loop_run` |
| 「예약 작업 표」 | `task`, `task_trigger`, `task_run` |
| 「사용자 브라우저 표」 | `user_browser` |
| 「암호화 key 표」 | `user_data_key` |

## 첨부 삭제 요청

`chat_attachment.deletion_requested_at`이 비어 있으면 미요청이고 값이 있으면 접근을 차단한다.
최초 요청 시각은 실패, 재시작과 재요청에서 유지한다.
`deleted_at`은 원본과 사본의 실제 삭제 완료 시각이다.
칸과 재시도 색인은 `V20261010004638__attachment_deletion_request.sql`이 갖는다.

사전 트랜잭션은 요청을 커밋하고 파일 작업은 트랜잭션 밖에서 수행한다.
파일 작업 성공 뒤 별도 트랜잭션에서 완료 시각을 기록한다.
파일이 이미 없으면 멱등 성공이며 파일 삭제 뒤 완료 기록 전 종료도 복구한다.
실패는 요청 상태와 완료 시각의 빈 값을 보존한다.
첨부 cleaner는 삭제 미완료이고 요청이 있거나 만료된 후보를 기동과 기존 주기에 다시 처리한다.
사용자 잠금 뒤 대상 상태를 재검사하며 한 건의 실패가 나머지를 되돌리지 않는다.
기존 보관 기간은 늘리지 않는다.
원본 조회와 삭제 잠금의 흐름은 [사진 첨부](../../docs/features/attachment.md#삭제-요청과-접근-차단)가 갖는다.

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
| `connector_action_execution` | `execution_args_json`, `summary_json`, `scope_json` | 함. 세 칸이 같은 `content_key_id`를 사용하며 key가 없던 줄은 평문이다 |
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
관리자의 실행 화면이 가린 도구 원문(`execution_event.detail`)을 보이는 예외는 [ADR-038](../../docs/adr/ADR-038-도구의-명령-원문은-관리자에게만-보내고-사용자에게는-사람-말로-보인다.md) 이 갖는다.

## 마이그레이션 작성 규칙

H2 로 도는 검사를 모두 통과해도 운영 MySQL 에서 실패할 수 있어 아래 규칙을 둔다.

### 새 버전은 UTC 작성 시각으로 정한다

새 마이그레이션은 UTC 작성 시각으로 `V<YYYYMMDDHHMMSS>__<설명>.sql` 을 만든다. 작성 시각은 `date -u +%Y%m%d%H%M%S` 로 얻는다.
이미 main 에 있는 숫자 버전은 그대로 두며, 합칠 때 다음 숫자로 옮기지 않는다.
같은 시각이 겹치면 아직 적용하지 않은 새 파일의 시각을 다시 정한다.
운영과 테스트는 `spring.flyway.out-of-order=true` 로 낮은 시각 버전이 나중에 머지되어도 적용한다.
서로 의존하는 마이그레이션은 한 PR 에 두고, 의존하는 파일의 시각을 더 크게 정한다. 다른 PR 의 미적용 스키마에 기대는 SQL 은 만들지 않는다.
적용된 파일을 고치지 않는 규칙은 루트 [`AGENTS.md`](../../AGENTS.md) 의 「용어」 절이 갖는다.
검사는 `node scripts/check-migration-versions.mjs [기준 ref]` 가 하고, 무엇을 어느 기준과 비교하는지는 그 스크립트와 CI 가 갖는다. 결정 근거는 [ADR-20261007 / numbering-scheme](../../docs/adr/ADR-20261007-numbering-scheme.md) 에 있다.

### DDL 과 DML 을 한 파일에 섞지 않는다

MySQL 의 DDL 은 되돌려지지 않는다.
`ALTER TABLE` 뒤의 `INSERT` 가 실패하면 칸은 더해진 채 남고 Flyway 에는 실패한 줄이 남아, 이전 버전의 애플리케이션도 뜨지 못한다.
칸과 표와 색인을 바꾸는 파일과 줄을 넣고 고치는 파일을 나눈다. `V56__subagent_usage_ledger.sql` 과 `V57__subagent_usage_backfill.sql` 이 본보기다.
나누면 DML 이 실패해도 앞의 DDL 파일은 성공한 것으로 남아, 실패한 줄 하나만 고쳐 다시 올릴 수 있다.

### 모든 표의 정렬 규칙은 `utf8mb4_0900_ai_ci` 하나다

**문자열 칸은 모두 `utf8mb4_0900_ai_ci` 다.** Flyway 가 스스로 만드는 `flyway_schema_history` 만 예외다.
운영 서버의 기본 정렬 규칙은 `utf8mb4_unicode_ci` 라, `CREATE TABLE` 에 정렬 규칙을 적지 않은 표는 서버 기본값을 따른다.
두 정렬 규칙의 칸을 `=`, `<>`, `IN`, 조인 조건으로 비교하면 MySQL 이 오류 1267 로 거절한다. 줄이 하나도 없어도 거절한다.
정렬 규칙을 적지 않고 만든 옛 표는 V58 부터 V65 까지가 맞췄다.

- **새 표에는 `ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci` 를 적는다.** 검사는 `test/unit/migration-collation.test.ts` 와 실제 MySQL 의 `MysqlMigrationTest` 가 한다
- 문자열 비교는 대소문자와 악센트를 구분하지 않고 끝의 공백은 구분한다. 구분해야 하는 식별자 비교는 양쪽을 `CAST(... AS BINARY)` 로 감싼다
- `COLLATE` 구절로 비교식의 정렬 규칙을 바꾸지 않는다. H2 가 받지 않아 H2 로 도는 검사가 실패한다
- 숫자 키로 조인할 수 있으면 문자열 비교를 쓰지 않는다
- 정렬 규칙을 바꾸는 마이그레이션은 **표마다 파일을 나눈다.** `CONVERT TO` 는 표를 다시 만드는 DDL 이고 되돌려지지 않는다. 나누면 실패한 표 하나만 옛 정렬 규칙으로 남는다
- **유일 색인이 걸린 문자열 칸은 새 정렬 규칙에서 같아지는 값이 있는지 운영에서 먼저 센다.** 그런 줄이 있으면 그 표의 문장이 오류 1062 로 실패한다. 세는 방법은 운영 저장소가 갖는다. 다시 만드는 동안 그 표에 쓰기가 막힌다

### 실제 MySQL 검사를 통과해야 한다

```bash
# cwd: 저장소 root
scripts/check-mysql-migration.sh
```

Docker 로 일회용 MySQL 8.4 를 운영처럼 `--collation-server=utf8mb4_unicode_ci` 로 띄우고 `@Tag("mysql")` 이 붙은 검사를 모두 돌린다. CI 의 `backend` job 도 이 스크립트를 돌린다.
저장소 쿼리 검사는 [`backend/AGENTS.md`](../AGENTS.md) 의 「저장소 쿼리는 실제 MySQL 에서도 실행한다」 가 갖는다.
**줄을 넣거나 고치는 마이그레이션을 쓰면 그 검사를 실제 MySQL 에서도 돌린다.**
H2 용 `*MigrationTest` 가 데이터베이스를 만드는 메서드를 열어 두고, `mysql` 태그를 단 하위 클래스가 `MysqlTestDatabase` 로 바꿔 끼운다. `SubagentUsageLedgerMysqlMigrationTest` 가 본보기다.

## 지울 때

사용자가 에이전트를 지우면 `deleted_at` 을 적고 `enabled` 를 내린다. 행은 7일 동안 남는다.
`profile_managed` 가 참이면 그 Hermes profile 과 올린 스킬 디렉터리를 지우고 그 profile 의 MCP 토큰을 폐기한다.
거짓이면 운영에서 만든 profile 이라 profile 은 남긴다.
`profile_managed` 와 상관없이 그 에이전트에 붙은 바인딩은 뗀다.
지운 지 `assistant.agents.purge-after` 가 지나면 정리 작업이 에이전트 행과 그 에이전트의 설정, 권한, 살펴보기 기록을 지우고 커넥터 줄의 `agent_id` 는 비운다.
대화와 실행 기록과 스킬 호출 이력, 예약 작업은 남고 「지운 에이전트」 로 보인다. 무엇을 지우고 무엇을 기다리는지는 [ADR-20261009 / agent-purge](adr/ADR-20261009-agent-purge.md) 가 갖는다.

대화 줄은 지우지 않는다. 사용자가 지우면 `conversation.deleted_at` 을 적고 목록에서 숨긴다.
같은 트랜잭션에서 두 가지를 함께 지운다.
아직 보내지 않은 대기 메시지(`chat_pending_message`)는 지운 대화에 보낼 곳이 없어 지운다.
판단 피드백 사건(`decision_feedback_event`)은 그 대화의 사건과, 그 사건이 가리키는 제안의 다른 사건까지 지운다. 사용자의 기록이라 대화와 함께 없앤다.
까닭과 예외는 [`docs/features/proactive.md`](../../docs/features/proactive.md) 의 「보관과 삭제」 가 갖는다.
그 뒤 정리 작업이 메시지, 첨부와 결과물의 파일과 행, 실행 질문 줄, 실행의 답 본문과 사건의 `detail`, Hermes session 을 지우고 `purged_at` 을 적는다.
실행 줄과 사건 줄은 본문 없이 남는다. 사용량 화면은 지운 대화의 실행도 센다. 돈은 이미 나갔다.
무엇을 언제 지우고 무엇을 기다리는지는 [ADR-20261008 / conversation-purge](adr/ADR-20261008-conversation-purge.md) 가 갖는다.

사용자를 지우는 흐름은 아직 없다.
페르소나는 이 데이터베이스에 없다. 본문은 그 profile 의 `SOUL.md` 가 갖는다([ADR-019](adr/ADR-019-페르소나는-hermes-가-갖고-control-plane-은-화면만-준다.md)).

첨부는 보관 기간이 지나면 파일만 지우고 `deleted_at` 을 적는다. 행은 대화를 지운 뒤 정리 작업이 지운다.
결과물(`chat_artifact`)도 같다.
그 자리에 사진이 있었다는 것이 남아야 지난 대화를 읽을 수 있다.
하위 에이전트 session 등록(`hermes_session_binding`)도 지우지 않는다. 실행 기록과 함께 남는다.

Memory 는 줄을 지운다. 지우기 전에 마지막 값을 `memory_revision` 에 `DELETED` 로 남기므로 본문은 그 표에 남는다. 민감 항목의 판은 암호문으로 남는다.
화면의 삭제는 목록과 주입에서 빼는 것이고, 본문을 완전히 없애는 길은 아직 없다.
기억 기록(`memory_capture`)은 항목을 지워도 남는다. 대화는 항목이 없는 기록을 그리지 않는다. 되돌린 기록은 `undone_at` 을 적고 남긴다.
에이전트를 지워도 `agent_memory_collection` 의 줄은 에이전트 행과 함께 7일 동안 남는다. 지운 에이전트는 실행되지 않으므로 그 줄을 읽는 자리가 없다.
그 변경 기록(`agent_memory_collection_change`)도 그동안 남는다. 누가 언제 민감 허용을 열었는지가 남아야 한다. 에이전트 행을 정리할 때 둘을 함께 지운다.
서비스 토큰은 폐기해도 줄이 남는다. 언제까지 쓰였는지가 남아야 한다.

허용 목록에서 빼는 것도 지우지 않고 `enabled` 를 내린다. 그 사람의 서비스 토큰은 모두 폐기하고 브라우저는 멈춘다.
그 사람의 `app_user` 와 실행 기록은 그대로 둔다.
그 사람의 Hermes profile 도 지우지 않는다. 다시 들일 때 그것을 다시 만들지 않아도 된다.

예약 작업은 지우면 `task.state` 를 `ARCHIVED` 로 둔다. 작업과 시각과 발화 기록의 줄은 남는다. 작업이 만든 대화와 그 실행 기록이 이 줄을 가리킨다.

알림(`notification`)은 보관 기간이 지나면 줄을 지운다. 알림은 다른 표의 사실을 알리는 사본이라, 원인이 된 승인 줄과 실행 기록이 남아 있다.

## 사용자와 에이전트 표

### app_user

실제로 들어온 적이 있는 사용자다. 그룹의 첫 사용자가 `ADMIN` 이 되고 그 뒤로는 `MEMBER` 다. `model_default_tier` 가 비면 그룹 기본값을 따른다.

### allowed_person

로그인할 수 있는 사람의 목록이다. **여기 없는 주소는 토큰을 받지 못한다.**
`app_user` 와 나누어 둔다. `allowed_person` 은 들어와도 된다고 정한 사람이고, `app_user` 는 실제로 들어온 적이 있는 사람이다.
**둘을 잇는 것은 `email` 이다.** 외래 키를 두지 않는다. 허용한 시점에는 `app_user` 가 없고, 지운 뒤에도 실행 기록은 `app_user` 를 가리켜야 한다.
`hermes_profile` 이 유일한 이유는 두 사람이 같은 profile 을 쓰면 격리가 깨지기 때문이다. `agent` 표의 `hermes_profile` 도 유일하므로 같은 제약이 두 곳에 있다.
`last_login_at` 은 웹 로그인 성공만 적는다. 이 칸이 생기기 전의 로그인은 비어 있다.

#### 비어 있으면 아무도 들어오지 못한다

이 표가 로그인의 유일한 근거다. **넣는 절차는 비공개 저장소가 소유한다.** 이 저장소에 주소를 적지 않는다.

### agent

사용자가 대화를 시작할 때 고르는 실행 단위다. 에이전트 하나가 Hermes profile 하나를 가리키고, 공개 범위가 누가 쓸 수 있는지 정한다([ADR-007](adr/ADR-007-에이전트가-모델과-도구를-함께-정한다.md)).

- `hermes_profile` 은 호스트의 key 파일 이름과 같다
- `api_base_url` 을 설정 하나가 아니라 에이전트마다 둔다. 공유 listener 에서도 profile 마다 접두가 다르고, profile 마다 다른 노드를 가리킬 수 있어야 하기 때문이다
- `profile_managed` 가 참이면 Control Plane 이 그 profile 을 만들었고, 에이전트를 지울 때 profile 까지 지운다
- `flow` 가 비어 있으면 Hermes 를 한 번 부른다
- `connector_managed` 는 바인딩이 생기기 전의 옛 커넥터 에이전트를 표시하고, 지금은 새로 참이 되지 않는다. 규칙은 [커넥터 연결](../../docs/features/connector.md) 의 「옛 커넥터 에이전트」 가 갖는다
- `connector_attachments` 는 그 옛 커넥터 에이전트가 사진을 받는지다. 커넥터가 선언한 toolset 이 켜진 것을 확인했을 때만 참이다
- `default_model_provider` 와 `default_model` 은 함께 채우거나 함께 비운다. `default_reasoning_effort` 는 혼자 둘 수 있다

**모델과 effort 는 대화가 고르고, 고르지 않으면 에이전트 기본 모델로 돈다.** 기본 모델 세 칸이 모두 비면 그 profile 의 값으로 돈다. 막힌 계정을 쉬게 하는 것은 Hermes 가 한다([ADR-030](adr/ADR-030-모델과-effort-는-대화가-고르고-기본값은-hermes-profile-이-갖는다.md), [ADR-054](adr/ADR-054-에이전트-기본-모델과-모델-숨김은-control-plane-db-가-갖는다.md)).
**주인과 공개 범위는 따로다.** 그룹에 공개해도 만든 사람(`owner_user_id`)이 계속 관리하고, `PRIVATE` 에서는 주인이 꼭 있어야 한다. 이 결정 전에 운영에서 등록한 `GROUP` 에이전트는 주인이 비어 있어 `ADMIN` 만 관리한다([ADR-033](adr/ADR-033-사용자가-에이전트를-만들고-공개해도-만든-사람이-관리한다.md)).
추천 질문은 데이터베이스에 두지 않고 backend 메모리에만 둔다([ADR-036](adr/ADR-036-추천-질문은-사용자의-대화-이력으로-모델이-만들고-메모리에만-둔다.md)).

### model_tier_definition

그룹이 정한 모델 단계의 mapping 이다. 선택 순서는 [모델 단계와 실행 기록](../../docs/features/model-usage.md) 이 정한다.
`provider` 가 비면 요청한 에이전트의 기본 provider 로, `model` 이 비면 에이전트 기본 모델로 돈다.

### model_tier_group_setting

그룹의 기본 모델 단계다. `default_tier` 가 비면 그룹 기본 단계가 없다.

### model_hidden

그룹이 숨긴 provider 와 모델만 적는다. `model` 이 빈 문자열이면 그 provider 전체를 숨긴 것이다. NULL 은 유일 제약이 겹침을 막지 못해 쓰지 않는다.

### toolset_hidden

그룹이 일반 에이전트 도구 화면에서 숨긴 toolset 이다. 행이 없으면 모두 보인다.
활성 도구는 Hermes profile 이 갖고 이 표는 활성 상태를 저장하거나 바꾸지 않는다([ADR-20261008 / tool-catalog-visibility](../../docs/adr/ADR-20261008-tool-catalog-visibility.md)).

### agent_toolset_request

에이전트 주인의 관리자 등급 도구 사용 요청과 결정 이력이다([ADR-20261009 / tool-request-flow](../../docs/adr/ADR-20261009-tool-request-flow.md)). 에이전트를 지워도 행이 남고, 지운 에이전트를 정리할 때 함께 지운다.
`group_id` 와 `requester_user_id` 는 요청 당시의 값이고, 그 뒤 주인이 바뀌어도 고치지 않는다. `decided_by_user_id` 는 요청자가 취소하면 비어 있다.
**대기 요청을 하나만 두려고 `pending_slot` 을 쓴다.** 대기 중에만 1 이고 끝나면 NULL 이다. 유일 제약에 이 칸이 들어 있어 대기 요청은 겹치지 못하고, 끝난 요청의 NULL 은 서로 겹쳐도 저장되어 이력이 남는다.

### agent_token

Hermes 가 Control Plane 의 MCP 도구를 부를 때 쓰는 장기 토큰이다. 원문은 발급 응답에서 한 번만 내고 해시만 저장한다.
**토큰은 어느 profile 이 부르는지만 증명한다. 사용자를 정하지 않는다.** 요청자는 서명한 `_fos_ctx` 로 찾은 origin 실행의 `user_id` 다([ADR-032](adr/ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md)).
`profile_name` 이 빈 줄은 profile 을 묶기 전에 폐기된 옛 토큰뿐이고 인증에서 거절한다. 바꿔 끼우는 동안 옛 토큰과 새 토큰이 함께 쓰여 `profile_name` 에 유일 제약을 두지 않는다.
한 번 묶은 profile 은 바꾸지 않는다. 다른 profile 에 쓰려면 새로 발급한다.

### service_token

다른 서비스가 사용자의 Memory 문서를 읽을 때 쓰는 토큰이다([ADR-056](adr/ADR-056-다른-서비스는-사용자에-묶인-서비스-토큰으로-문서를-읽기만-한다.md)). 원문은 발급 응답에서 한 번만 내고 해시만 저장한다.
**`agent_token` 과 달리 사용자 한 사람에 묶인다.** 그 사용자 본인만 발급하고 폐기한다. 관리자도 다른 사용자의 토큰을 발급하거나 폐기하지 못한다.
`expires_at` 은 발급할 때 정하고 고치지 않는다. `last_used_at` 은 거절한 요청에서는 적지 않는다.
인증할 때도 주인이 지금 허용 목록에 켜져 있는지 본다. 허용 목록에서 끌 때의 폐기가 한 번 실패해도 끈 사용자의 토큰이 통하지 않게 하기 위해서다.

### service_token_collection

서비스 토큰이 받는 collection 하나가 한 줄이다. `agent_memory_collection` 과 같은 뜻이고 같은 세 조건으로 판정한다.

## 대화 표

### conversation

- `hermes_session_id` 는 다음 turn 에 보낼 Hermes session 이고 특정 profile 안의 값이다. 그래서 대화의 에이전트는 중간에 바뀌지 않는다. 새 대화는 첫 turn 을 보내기 전에 Control Plane 이 `fos-<uuid>` 로 정하고, 압축 교체로 Hermes 가 다른 session 을 돌려주면 그 값으로 바뀐다
- `hermes_root_session_id` 는 처음의 session 이고 압축 교체에도 바뀌지 않는다. MCP `agent_*` 호출이 들고 오는 서명한 루트 session 이 이 값이다. 이 칸이 생기기 전의 대화는 비어 있다
- `model_provider` 와 `model` 은 함께 채우거나 함께 비운다. `reasoning_effort` 의 `none` 은 reasoning 끄기이고 비어 있음(미지정)과 다르다. `model_selection_mode` 가 비면 사용자와 그룹 기본값을 따른다
- `purged_at` 은 정리 작업이 본문을 실제로 지운 시각이다. 이때 `title` 과 두 session 칸을 비우고, 그 뒤로는 메시지를 저장하지 않는다
- `hidden_at` 은 목록에서만 뺀 시각이다. 예약 작업이 「보고할 것 없음」 으로 끝난 `NEW_PER_RUN` 대화에 적고, 사용자가 질문을 보내면 비운다([`docs/features/schedule.md`](../../docs/features/schedule.md)). 목록 색인에 이 칸이 없어 읽은 뒤 거른다. 이런 대화는 하루 발화 상한(`assistant.task.max-runs-per-day`)을 넘지 않아 색인에 더하지 않았고, 다른 경로가 대화를 숨기게 되면 더한다
- `purpose` 의 `CHECK` 는 먼저 살펴보기의 점검 대화다. 사용자와 에이전트마다 지우지 않은 점검 대화 가운데 `id` 가 가장 큰 것을 쓴다
- `task_id` 는 이 대화를 만든 예약 작업이다([ADR-078](../../docs/adr/ADR-078-예약-작업의-결과는-실행마다-새-대화가-기본이고-목록은-작업으로-묶는다.md)). `agent_id` 와 같은 까닭으로 외래 키를 두지 않는다

**`agent_id` 에 FK 를 두지 않는다.** 칸도 NULL 을 받는다. 지운 에이전트는 정리 작업이 행을 지우고 그 에이전트의 대화는 남는다([ADR-20261009 / agent-purge](adr/ADR-20261009-agent-purge.md)).
그 대화를 읽는 경로는 행이 없어도 실패하지 않는다([`docs/features/agent-skill.md`](../../docs/features/agent-skill.md) 의 「에이전트 만들기와 지우기」 절). 정리 작업 전에도 행이 없는 대화가 운영에서 나온 적이 있다.
**`id` 는 Control Plane 밖으로 나가지 않는다.** 화면과 API 는 대화를 `public_id` 로만 가리킨다([ADR-025](../../docs/adr/ADR-025-대화는-주소에-공개-식별자를-쓰고-번호는-안에만-둔다.md)).

### chat_message

- `role` 의 `SYSTEM` 은 위임 결과가 도착했다는 알림 줄이다. 자식의 답 전문은 넣지 않고, 다시 생성의 대상이 아니다([ADR-040](adr/ADR-040-위임-결과는-control-plane-이-부모-대화의-다음-turn-을-열어-전한다.md))
- `content_key_id` 가 비어 있으면 `content` 는 평문이다. 외래 키를 두지 않는다
- `sender_user_id` 는 `ASSISTANT` 와 `SYSTEM` 에서, `execution_id` 는 `USER` 와 `SYSTEM` 에서 비어 있다
- `content` 를 `LONGTEXT` 로 못 박는다. 길이를 주지 않은 `@Lob` 문자열을 Hibernate 가 MySQL 에서 `tinytext` 로 기대해 기동이 실패한다

**본문은 저장할 때 암호화한다.** KEK 설정이 있으면 새 메시지를 빈 글로 넣고 같은 트랜잭션에서 암호문으로 고친다.
AAD 에 줄 번호와 대화, 대화 주인이 들어가 암호문을 다른 줄로 옮기거나 대화 주인을 바꾸면 풀리지 않는다. 본문은 `ChatMessage.content()` 로만 꺼낸다.
**이전 판(`replaces_message_id` 가 가리키는 줄)을 지우지 않는다.** 화면이 넘겨 볼 수 있어야 하고, 그 판을 만든 실행이 그것을 가리킨다([ADR-022](../../docs/adr/ADR-022-다시-생성과-수정은-같은-session-에-판으로-쌓는다.md)).
예전에 수정한 사용자 메시지도 고치기 전 메시지를 가리킨 채 남아 있다([ADR-024](../../docs/adr/ADR-024-메시지-수정을-없애고-중지한-뒤-다시-보낸다.md)).

### chat_pending_message

turn 이 도는 동안 사용자가 보낸 메시지 하나가 한 행이고, 보내지기 전까지만 있다([ADR-048](../../docs/adr/ADR-048-응답-중에-보낸-메시지는-control-plane-이-쌓아-두고-다음-turn-으로-합쳐-보낸다.md)).
**보낸 행은 지운다.** 대기 행을 지우는 것과 합친 글을 `chat_message` 의 `USER` 행으로 저장하는 것이 한 트랜잭션이다. 보낸 글은 `chat_message` 에 남으므로 여기에 이력을 두지 않는다. 취소한 행도 지운다.
합칠 때는 `id` 순서로 잇는다. 개수와 길이의 상한은 표의 제약이 아니라 `PendingMessageService` 가 본다.
**한 행이라도 `held` 가 참이면 그 대화의 대기 행을 모두 보내지 않는다.** 앞 turn 을 중지했거나 보내려다 저장 전에 실패하면 참이 되고, 사용자가 「보내기」 를 누르면 그 대화의 `held` 를 모두 내린다.

### chat_attachment

대화에 올린 사진 한 장이 한 행이다. 본문은 파일로 두고 여기에는 그 사진을 가리키는 것만 둔다([ADR-020](adr/ADR-020-사진은-공유-디렉터리에-두고-에이전트가-파일로-읽는다.md)).

`message_id` 가 비어 있으면 올렸지만 보내지 않은 것이고, 그 행도 `expires_at` 이 지나면 함께 지운다.
`position` 은 같은 메시지에 붙인 사진의 고른 순서다. 화면, Hermes 입력, 사용자가 보는 사진 순번에 함께 쓰고, 지운 사진도 그 자리를 차지한다.
**보관 기간으로는 행을 지우지 않는다.** 파일을 지우고 `deleted_at` 만 적는다. 그래야 지난 대화를 열었을 때 화면이 「보관 기간이 지나 볼 수 없습니다」를 보일 수 있다.
사용자가 대화를 지우면 정리 작업이 첨부 파일과 행을 함께 지운다. 지운 대화는 다시 열 수 없어 자리를 남길 까닭이 없다.
`deleted_at` 이 비어 있는지가 볼 수 있는지를 정하고, `expires_at` 은 언제 지울지만 정한다. 둘로 판정하면 지우는 일이 늦었을 때 화면과 디스크가 어긋난다.

### chat_artifact

에이전트가 turn 안에 대화의 결과물 폴더에 만들거나 고친 HTML 파일 하나가 한 행이다([ADR-027](../../docs/adr/ADR-027-에이전트가-만든-html-은-대화별-폴더에-두고-스크립트-없이-보인다.md)).
`(message_id, path)` 에 유일 제약이 있다. 같은 파일을 다음 turn 이 다시 고치면 그 turn 의 답에 새 행이 생긴다. 파일은 하나이므로 옛 답에서 열어도 지금 내용이 보인다.
보관 기간과 대화 삭제의 규칙은 첨부와 같다. 지운 표시를 다시 맞추는 규칙은 [`docs/features/attachment.md`](../../docs/features/attachment.md) 의 「지운 표시를 다시 맞추기」 가 갖는다.

### result_delivery

자동 turn 하나가 부모 대화에 넘긴 결과들의 묶음이다. 그 turn 이 알림 줄을 저장할 때 생긴다.
결정은 [ADR-075](../../docs/adr/ADR-075-결과-전달은-묶음과-시도로-남기고-사용자가-저장된-결과만-다시-전달한다.md), 상태가 바뀌는 흐름은 [`docs/features/agent-skill.md`](../../docs/features/agent-skill.md) 의 「결과 전달이 끝나지 않았을 때」 에 있다.
`status` 는 마지막 시도의 끝과 같고, 묶음과 시도의 상태는 같은 트랜잭션에서 바꾼다.
**다시 전달은 `status` 를 `FAILED` 나 `STOPPED` 에서 `DELIVERING` 으로 바꾸는 조건부 update 로 시작한다.** 바뀐 줄이 없으면 시작하지 않는다. 한 묶음에 도는 시도가 둘이 되지 않는 근거다.
대화와 실행, 메시지에는 외래 키를 두지 않는다. `connector_action` 과 같이 대화가 지워져도 기록을 남기고, 지운 대화의 묶음은 다시 전달하지 못한다.
묶음 안의 항목과 시도만 `delivery_id` 의 외래 키로 묶음을 가리킨다.

### result_delivery_item

묶음에 든 결과 하나가 한 행이다. 다시 전달할 때 `id` 순서로 입력을 만든다.
`result_key` 는 위임 결과(`DELEGATION`)면 `agent_execution.id`, 승인한 커넥터 호출의 결과(`CONNECTOR_ACTION`)면 `connector_action.public_id` 다.
`(source, result_key)` 에 유일 제약이 있어 한 결과는 한 묶음에만 든다. 같은 결과를 두 자동 turn 이 함께 넘기려 하면 뒤의 트랜잭션이 알림 줄까지 함께 되돌아간다.
결과 본문은 여기 두지 않는다. 다시 전달할 때 실행 줄의 `output_text` 와 승인 줄의 `result_text` 를 다시 읽는다.

### result_delivery_attempt

묶음을 부모에 넘긴 한 번이 한 행이다.
`execution_id` 는 실행 줄을 만들기 전에 실패했거나 내려갔으면 비어 있고, 내려갔을 때 `error_code` 는 `INTERRUPTED` 다.
`notice_message_id` 는 이 시도가 저장한 마지막 `SYSTEM` 줄이다. 화면은 묶음의 마지막 시도의 이 줄 아래에 상태와 버튼을 그린다.
`execution_id` 와 `notice_message_id` 에는 외래 키를 두지 않는다. 그 줄이 지워져도 시도의 끝은 남는다.

### execution_question

사람이 보낸 대화 turn 의 실행과 그 질문 메시지를 잇는다. 한 실행이 한 줄이다.
`memory_remember` 가 바로 저장할 수 있는 실행인지 이 줄로 판정한다([ADR-20261007 / memory-remember](../../docs/adr/ADR-20261007-memory-remember.md)).

- 새 질문과 다시 생성의 실행에만 남긴다. 예약 작업, 먼저 살펴보기, 맡긴 일의 결과를 전하는 turn, 맡겨서 도는 실행은 줄이 없다
- 외래 키를 걸지 않는다. 읽는 쪽(`TurnQuestions`)은 메시지가 없거나, `USER` 가 아니거나, 그 실행의 사용자가 보낸 것이 아니면 없는 줄로 본다
- 줄을 남기지 못해도 turn 은 잇는다. 그 실행의 `memory_remember` 는 제안으로만 남는다
- 질문 줄이 없는 루트 실행이 대화에 하나라도 있으면 그 대화의 `memory_remember` 는 그 뒤로 늘 제안으로 남는다. 판정은 [`docs/features/memory.md`](../../docs/features/memory.md) 의 「바로 저장 판정」 과 [ADR-20261008 / memory-remember-guard](../../docs/adr/ADR-20261008-memory-remember-guard.md) 가 갖는다

## 실행 표

### agent_execution

에이전트가 한 번 답한 기록이다. **이 줄은 실행이 끝난 뒤가 아니라 시작할 때 만들어진다**([ADR-011](adr/ADR-011-실행은-시작할-때-기록하고-끝날-때-갱신한다.md)).
모델 단계와 요청값은 시작할 때 옮겨 적고, 실제 provider 와 모델은 끝날 때 갱신한다.

- `conversation_id` 가 비면 대화 밖에서 돈 실행이다. 추천 질문을 만드는 실행과 가치 평가의 시스템 판단 실행(`ExecutionRecorder.startSystem`)이 그렇다. 시스템 판단 실행은 시스템 profile 로 돌아 `agent_id` 도 비어 있다
- `retry_of_execution_id` 는 지금 채우는 경로가 없어 늘 비어 있다
- `hermes_session_id` 는 대화 turn 이면 그 대화의 루트 session 이고, 흐름의 하위 실행과 위임한 자식이면 Control Plane 이 정한 `fos-<uuid>` 다. 하위 에이전트 session 은 이 칸이 아니라 `hermes_session_binding` 으로 찾는다
- `delegation_key` 는 `agent_delegate` 로 만든 실행만 채우고 유일하다. 같은 호출이 다시 와도 실행을 하나만 만든다. `output_text` 도 이 실행만 채우고, 대화 답은 `chat_message` 가 갖는다
- `provider` 와 `model` 은 Hermes 의 session 이 답한 값이고, 읽지 못하면 요청한 값이다. 기본값으로 보냈고 둘 다 읽지 못하면 비어 있다
- `reasoning_effort` 가 비면 기본값으로 보낸 것이다. `reasoning_defaults_checked_at` 은 그때 profile 기본값을 정상 응답으로 읽은 시각이고, 조회가 실패하면 비워 다시 시도한다. `reasoning_effort_source` 는 이 칸이 생기기 전의 실행에서 비어 있고 추정해 채우지 않는다
- `instructions_hash` 는 공통 답변 지침과 Memory 문맥의 지문이다. 본문은 개인 Memory 를 담을 수 있어 저장하지 않는다. `runtime_fingerprint` 는 그 값을 주는 Hermes 경로가 아직 없어 늘 비어 있다
- 토큰 칸은 provider 가 알려준 것만 채우고, `actual_cost_micros` 는 구독 경로에서 비어 있다. 금액을 0 으로 채우지 않는다. 0 은 공짜라는 뜻으로 읽히기 때문이다([ADR-014](adr/ADR-014-실제-청구액과-환산액을-나눠-적는다.md))

**`result_delivered_at` 은 위임 실행의 끝난 결과를 부모에게 전한 시각이다.** 비어 있고 `SUCCEEDED` 나 `FAILED` 인 위임 실행이 부모 대화를 깨울 대상이다([ADR-040](adr/ADR-040-위임-결과는-control-plane-이-부모-대화의-다음-turn-을-열어-전한다.md)).
부모가 번호를 모르는 결과를 다시 전하지 않으려고, 제출 전에 끝나 `SUBMIT_FAILED` 를 돌려줄 때도 적는다. 먼저 살펴보기가 끝나면 그 트리의 위임 줄에 도는 중이어도 적는다([`docs/features/proactive.md`](../../docs/features/proactive.md) 의 「끝날 때」).
저장소의 조건부 update 로만 채우고 엔티티 저장에서는 빠진다(`updatable = false`).

토큰 수는 실행 한 번의 **합계**다. 자식 실행의 토큰은 부모의 합계에 들어 있지 않다([`hermes/docs/hermes-contract.md`](../../hermes/docs/hermes-contract.md) 의 「자식 session 으로 결과와 토큰을 보완한다」).
그래서 부모와 자식을 더한 합계는 실행 트리의 줄과, 실행 줄이 없는 native 자식의 `subagent_usage_job` 줄을 더해 만든다. 어느 줄도 두 번 세지 않는다.
`hermes_session_id` 와 `status` 에 함께 색인을 둔다. 최상위 session 의 MCP 호출과 최상위 자식의 등록마다 서명한 루트 session 과 `profile_name` 으로 도는 실행을 찾기 때문이다([ADR-032](adr/ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md)).
그 session 을 가진 도는 실행이 둘 이상이면 어느 쪽도 부모로 쓰지 않고 거절한다. 대화 하나에는 도는 turn 이 하나뿐이라 보통 생기지 않는다.

#### 사건 관측 범위

`event_observation` 은 이 실행의 사건을 끝까지 받았는지다. 실행 성공과 자식 비용 확정 여부와는 별개다.

| 경우 | 값 |
| --- | --- |
| Hermes 에 제출하지 않는 합성 루트 | `UNKNOWN` |
| 사건을 수집하지 않는 흐름 단계와 FOS 위임 | `INCOMPLETE` 로 남는다 |
| 종료 사건을 받고 스트림이 정상으로 닫혔다 | `OBSERVED` |
| 종료 사건 없는 조기 EOF, 읽기나 사건 저장 실패, 대기 상한이나 중지 유예 종료 | `INCOMPLETE`. 뒤늦은 스트림 종료로 지우지 않는다 |
| 기동할 때 `OBSERVING` 으로 남았다 | `INCOMPLETE` 로 바꾸고 답과 확인한 사용량은 보존한다 |

월 합계는 끝난 `INCOMPLETE` 실행만 경고 수로 세고 관리자에게만 보낸다. 자식 건수나 비용에 더하지 않는다.
마이그레이션 이전의 `UNKNOWN` 실행은 경고 수에서 뺀다. 관측 여부를 추정하지 않고, 과거 실행이 배포 직후 경고 수를 채우지 않게 하기 위해서다.

#### 끝나지 않은 실행

`RUNNING` 인 줄은 사용량 목록에 「도는 중」으로 보이고 월 비용 합계에서는 뺀다. 빼지 않으면 「가격을 찾지 못한 실행」 으로 세어져, 아직 안 끝난 것과 가격을 모르는 것이 한 숫자에 섞인다.
기동할 때 `RUNNING` 으로 남은 줄은 Hermes 에 물어 정한다. 절차는 [`docs/features/chat.md`](../../docs/features/chat.md) 의 「기동할 때 남은 실행 정리」 가 갖는다.
그 경로가 실패로 적을 때 쓰는 `error_code` 의 뜻은 [ADR-061](adr/ADR-061-재기동-때-남은-실행은-hermes-에-물어-정하고-도는-실행에는-다시-붙는다.md) 이 갖고, 모두 사용량 목록에서 「중간에 중단됨」 으로 보인다.

### execution_event

실행 하나가 도는 동안 일어난 일을 우리 이름으로 옮겨 적은 것이다. Hermes 가 보낸 원래 payload 를 통째로 넣지 않는다.
`tool_name` 은 붙은 커넥터 서버의 도구면 Hermes 등록 이름 `mcp__<서버>__<도구>` 다.
`detail` 은 도구 사건이면 비밀값과 UUID 를 가린 뒤 저장하고(ADR-047), 응답에는 ADR-038 이 정한 사람에게만 싣는다. 옛 커넥터 에이전트의 도구 내용과, 다른 에이전트에 붙은 커넥터 서버의 도구 내용은 전체를 가린다.
**`subagent_name`은 이름, `subagent_id`, `goal` 순서로 채운다.** 이름이 없는 사건의 `preview` 에서 이름처럼 보이는 글자를 뽑아 채우지 않는다. 그것이 실제 이름인지 우리가 만든 것인지 구분할 수 없기 때문이다.
**`hermes_session_id`, `model`, 토큰은 SSE 또는 종료된 자식 session 조회에서 확인한 값이다.** 어느 칸이 오는지는 [`hermes/docs/hermes-contract.md`](../../hermes/docs/hermes-contract.md) 의 「자식 토큰을 SSE 로 받을 수 있다」 가 갖는다. 싣지 않는 버전에서는 이 칸들이 비고 `detail` 에 `preview` 가 들어간다.
이 토큰은 화면이 하위 에이전트가 무엇을 썼는지 보이는 데만 쓰고 사용량 합계에 더하지 않는다.
부모가 끝난 뒤에도 완료 사건이 없으면 재조회로 보완한다. `(execution_id, completed_child_session_id)` 가 유일해 같은 자식 완료를 두 번 저장하지 않는다. 이 칸이 생기기 전의 중복 완료 사건은 최신 한 줄에만 키가 있다.
`PROVIDER_SWITCHED` 는 옛 실행에만 남은 값이고, 지금은 이 값을 적는 경로가 없다.

### subagent_usage_job

native 자식 한 명의 사용량 원장 줄이자, 그 사용량을 session 에서 조회하는 작업이다([ADR-062](adr/ADR-062-native-하위-에이전트-사용량은-재조회-작업-줄을-원장으로-넓혀-합계에-더한다.md)).
재조회와 합계 규칙은 [모델 단계와 실행 기록](../../docs/features/model-usage.md) 의 「비동기 자식 사용량」 이 정한다.

- `(execution_id, child_session_id)` 가 유일하다. 사용자, 에이전트, 달은 부모 실행에서 얻고 여기 다시 적지 않는다
- `provider` 는 session 응답이 주지 않으면 대시보드 plugin 에서 읽고([ADR-067](adr/ADR-067-native-하위-에이전트의-provider-는-대시보드-plugin-이-session-저장소에서-읽어-준다.md)), 거기서도 읽지 못하면 비운다. 부모의 값으로 채우지 않는다
- 입력 토큰은 일반 입력, cache 읽기, cache 쓰기의 셋으로 나눠 적는다. 합계에 더할 때는 셋을 합쳐 부모의 `input_tokens` 와 같은 뜻으로 맞춘다
- `actual_cost_micros` 는 부모 실행의 `cost_mode` 가 `API` 일 때만 환산액과 같은 값이다

### execution_skill_use

실행 하나에서 스킬 하나가 쓰인 것이 한 행이고, 스킬 호출 이력의 원천이다.
`(execution_id, skill_name, source)` 에 유일 제약이 있어 한 실행에서 모델이 같은 스킬을 여러 번 읽어도 한 행이다. 호출 횟수는 실행 수로 센다.
`MODEL` 행은 실행 사건에 스킬 이름이 실려 올 때만 생긴다.
**스킬을 지워도 행은 남는다.** 이름으로 남아 지난 호출을 읽을 수 있다. 누가 어디까지 보는지는 [ADR-034](adr/ADR-034-올린-스킬은-control-plane-이-버전-디렉터리에-쓰고-hermes-는-읽기만-한다.md) 에 있다.

### hermes_session_binding

Hermes `delegate_task` 가 만든 하위 에이전트 session 이 어느 FOS 실행에서 시작됐는지 적는다. profile 플러그인이 `subagent_start` hook 에서 등록한다([ADR-037](adr/ADR-037-hermes-하위-에이전트-session-의-주인은-만들-때-등록한-줄로-정한다.md)).
**한 번 적은 줄은 바꾸지 않는다.** 같은 대화의 다음 turn 이 시작돼도 그 하위 에이전트는 처음 origin 실행에 속한다. 최상위 session 은 `agent_execution.hermes_session_id` 로 찾는다.

- `user_id` 는 origin 실행의 `user_id` 이고 MCP 호출의 요청자다. `origin_execution_id` 는 끝난 실행이어도 된다. 이 실행이나 그 루트 실행이 `CANCELLED` 면 MCP 호출을 거절한다. `root_session_id` 가 MCP 호출의 서명한 루트와 달라도 거절한다
- `(profile_name, session_id)` 가 유일하다. session id 가 profile 사이에서 유일하다고 보장하지 않기 때문이다
- 하위 에이전트의 하위 에이전트는 부모 등록의 `origin_execution_id` 와 `user_id` 를 그대로 잇는다. 하위 에이전트 몫의 `agent_execution` 줄은 만들지 않는다
- 외래 키는 두지 않는다. 실행 줄과 사용자는 지우지 않고, 등록은 서버가 방금 읽은 실행에서 origin 과 사용자를 옮겨 적으므로 없는 실행을 가리키지 않는다

같은 등록이 다시 오거나 다른 origin 으로 올 때의 응답은 [`hermes/plugins/fos-ctx/README.md`](../../hermes/plugins/fos-ctx/README.md#하위-에이전트-session-등록-계약) 의 「하위 에이전트 session 등록 계약」 이 갖는다. 동시에 두 요청이 와서 유일 제약에 걸리면 먼저 저장된 줄을 다시 읽어 같은 규칙으로 판정한다.
Control Plane 이 다시 떠도 등록 줄은 그대로라, 이미 등록한 하위 에이전트는 계속 요청자를 찾는다. 기동 정리가 다시 붙은 실행의 run 이 새로 만든 최상위 자식도 등록되지만, 실패로 적힌 실행의 run 이 새로 만든 최상위 자식은 도는 부모가 없어 등록되지 않는다.

### execution_context_source

실행 하나에 실은 문맥 항목의 참조다. 어느 답에 어느 기록이 들어갔는지 나중에 찾으려고 남기고, 제목과 본문은 남기지 않는다.
조립한 항목은 실행 줄을 만들 때 한 번에 적고, `memory_read` 가 본문을 내 준 항목은 실행 중에 `MEMORY_READ` 줄로 덧붙인다([`docs/features/memory.md`](../../docs/features/memory.md) 의 「본문을 읽으면 남는 기록」).
`OMITTED` 줄은 자리가 없어 빠진 Memory 항목이거나 결과를 알 수 없어 본문을 싣지 않은 승인 결과다. `source` 가 `MEMORY_` 로 시작하는 `OMITTED` 줄의 수는 `agent_execution.context_omitted_items` 와 같다.
실행 줄을 지우지 않으므로 이 줄도 지우지 않는다.

## Memory 표

### memory

사용자와 그룹에 대해 에이전트가 알아야 할 것 하나가 한 줄이다.
지금 있는 화면과 제안이 만드는 줄은 모두 `core` collection 의 `MEMORY` 다. 문서 API 가 만드는 줄은 `USER` 범위의 `DOCUMENT` 이고 `ACCEPTED` 다.

- `scope` 가 `USER` 면 `owner_user_id` 가, `GROUP` 이면 `group_id` 가 주인이다. `scope` 에는 기본값이 없다
- `content_key_id` 가 비어 있으면 `content` 는 평문이다. `SENSITIVE` 인 줄은 key 가 있을 때 기동하며 암호화하므로, key 가 없으면 평문으로 남은 줄이 있을 수 있다. `SENSITIVE` 는 `retrieval` 을 `ALWAYS` 로 둘 수 없다
- `always_inject` 는 `retrieval` 로 옮긴 옛 칸이다. `retrieval` 이 `ALWAYS` 일 때만 참으로 적고 읽지 않는다. 지우는 마이그레이션은 아직 없다
- `source_type` 과 `source_ref` 는 사람이 직접 적었으면 비어 있다. `source_type` 이 `brain` 인 줄은 예전에 기존 개인 지식에서 들인 것이고, 지금 코드에는 줄을 들이는 경로가 없다
- `proposal_dedup_key` 는 제안하거나 바로 저장한 사용자, 제목, 본문의 해시다. 직접 등록한 항목과 민감 항목은 비어 있다

**`ACCEPTED` 인 항목만 주입한다.** `PROPOSED` 는 사람이 아직 보지 않은 것이고, 에이전트가 그것을 사실로 쓰면 안 된다.
주입할 때 요청자의 `USER` 항목과 요청자가 속한 그룹의 `GROUP` 항목 가운데, 그 실행의 에이전트가 받는 `collection` 의 항목만 고른다.
`SENSITIVE` 항목은 그 `collection` 에서 민감 항목을 허용받은 에이전트에만 고른다. `SOURCE` 와 `ARCHIVE` 는 고르지 않는다.
다른 사용자의 `USER` 항목과 받지 않는 `collection` 의 항목은 고르는 단계에서 빠지므로 Hermes 로 나가는 문자열에 들어가지 않는다.

| 유일 제약 | 막는 것 |
| --- | --- |
| `uk_memory_proposal_dedup` | 같은 제안이 동시에 들어와 두 행이 되는 것 |
| `uk_memory_user_document` | 한 사용자의 한 `collection` 에 같은 이름의 문서가 둘 생기는 것 |
| `uk_memory_group_document` | 한 그룹의 한 `collection` 에 같은 이름의 문서가 둘 생기는 것 |
| `uk_memory_user_source` | 한 사용자의 같은 출처가 두 줄이 되는 것. 출처가 없는 줄은 `source_ref` 가 비어 걸리지 않는다 |

주인 칸이 범위에 따라 달라 문서 제약을 둘로 둔다. `document_key` 가 없는 줄은 유일 검사에 들지 않는다.

#### 옛 판으로 되돌렸다가 다시 올릴 때

V47 이전 버전으로 되돌린 동안 옛 코드가 쓴 줄은 다시 올리기 전에 맞춘다.
`always_inject` 와 `retrieval` 이 어긋난 줄은 `always_inject` 를 따라 `retrieval` 을 `ALWAYS` 나 `SEARCH` 로 고친다.
`agent_memory_collection` 에 줄이 없는 에이전트에는 `core` 를 넣는다. 옛 커넥터 에이전트는 뺀다.
되돌린 동안 고치거나 지운 Memory 는 `memory_revision` 에 판이 남지 않는다.

근거는 [ADR-003](adr/ADR-003-memory-권한은-주입으로-강제한다.md), [ADR-012](adr/ADR-012-memory-는-사람이-승인한-것만-남는다.md),
[ADR-052](adr/ADR-052-memory-는-collection-종류-꺼내는-방식-민감도-판-출처를-가진다.md),
[ADR-053](adr/ADR-053-에이전트는-허용된-collection-의-memory-만-받는다.md),
[ADR-058](../../docs/adr/archive/ADR-058-기존-개인-지식-저장소는-주인이-검토한-묶음을-화면에서-올려-들여온다.md) 에 있다.

### memory_revision

지금 값에서 물러난 판 하나가 한 줄이다. 고치면 고치기 전의 값을, 지우면 마지막 값을 남긴다.

- `memory_id` 는 지운 항목의 판도 남으므로 `memory` 에 없는 번호일 수 있다. 그래서 외래 키를 걸지 않는다
- `scope`, `owner_user_id`, `group_id`, `entry_type`, `document_key`, `status` 는 그때의 값이다. 지운 뒤에도 누가 볼 수 있는지 이 칸으로 정하고, 지운 문서의 판을 이름으로 찾는다
- 판이 암호문인지는 `sensitivity` 가 아니라 `content_key_id` 로 본다. 일반 항목이었던 판도 그 항목이 뒤에 민감 항목이 되면 암호문으로 바뀐다
- 출처 칸은 판에 남기지 않는다. 고칠 때 바뀌지 않는 값이다
- 고치거나 지울 때 그 항목을 쓰기 잠금으로 읽는다. 두 요청이 같은 판 번호로 판을 남기려 하지 않게 한 번에 하나씩 돈다

### memory_collection

그룹이 쓰는 collection 하나가 한 줄이다. 화면의 탭과 에이전트 접근 설정이 고를 목록이고, 누가 읽는지는 이 표가 정하지 않는다.
그룹마다 `MemoryCollection.DEFAULT_KEYS` 로 시작한다. 마이그레이션이 사용자가 있는 그룹에 넣고, 그 뒤에 생긴 그룹은 목록을 처음 읽을 때 넣는다.

### agent_memory_collection

에이전트가 받는 collection 하나가 한 줄이다([ADR-053](adr/ADR-053-에이전트는-허용된-collection-의-memory-만-받는다.md)).
**줄이 하나도 없는 에이전트는 Memory 를 받지 않는다.**
에이전트를 처음 저장할 때 `core` 한 줄을 민감 허용 없이 넣는다. 옛 커넥터 에이전트에는 넣지 않고, 줄이 있어도 옛 커넥터 에이전트는 받지 않는다.
그 뒤에는 `ADMIN` 이 관리자 영역에서 줄을 더하고 빼고 민감 허용을 바꾼다. `created_at` 은 그 collection 을 붙인 시각이고, 민감 허용만 바꾸면 그대로 둔다.

### agent_memory_collection_change

`agent_memory_collection` 의 줄 하나가 바뀐 것이 한 줄이다. 에이전트 행이 있는 동안 지우지 않고, 지운 에이전트를 정리할 때 함께 지운다([ADR-20261008 / agent-memory-grants-admin](../../docs/adr/ADR-20261008-agent-memory-grants-admin.md)).
`allow_sensitive` 는 `GRANTED` 와 `SENSITIVE_CHANGED` 면 바꾼 뒤의 값이고, `REVOKED` 면 떼기 전의 값이다. `changed_by_user_id` 에는 외래 키를 걸지 않는다.
한 번의 저장이 여러 collection 을 바꾸면 줄도 여럿이고 `changed_at` 이 같다.
사람이 바꾼 것만 남긴다. 바뀌지 않은 collection, 마이그레이션이 넣은 `core`, 새 에이전트에 넣는 `core` 는 남기지 않는다.

### memory_capture

에이전트가 `memory_remember` 로 남긴 기록 한 줄이다. 대화의 답 아래에 「기억했어요」 와 제안 카드를 그리고, 되돌리기가 무엇을 되돌릴지 정한다.
도구와 API 는 [`docs/features/memory.md`](../../docs/features/memory.md) 의 「에이전트가 기억을 남기는 길」 이 갖는다.

- `memory_id` 는 되돌리거나 사람이 지우면 `memory` 에 없는 번호가 되므로 외래 키를 걸지 않는다
- `execution_id` 는 기록을 남긴 origin 실행이고, 한 실행의 상한을 이 칸으로 센다
- `base_revision` 은 `CREATED` 면 저장한 때의 판 번호, `UPDATED` 면 고치기 전의 판 번호이고 `PROPOSED` 면 비어 있다
- `previous_status` 는 기존 제안을 받아들인 `CREATED` 만 `PROPOSED` 이고 나머지는 비어 있다

같은 사실을 같은 실행에서 두 번 남겨도 중복 키가 같은 항목을 하나로 둔다. 기록은 저장하거나 고친 경우에만 남는다.
`CREATED` 를 되돌릴 때 항목의 판이 `base_revision` 이 아니면, `UPDATED` 를 되돌릴 때 `base_revision + 1` 이 아니면 그 뒤에 사람이 다시 고친 것이라 되돌리지 않는다.
`previous_status` 가 `PROPOSED` 인 기록을 되돌리면 항목을 보존하며 승인 정보도 지워 제안 상태로 돌린다.

## 커넥터 표

### connector_connection

사용자마다 커넥터 하나에 연결 하나를 둔다. 상태가 바뀌는 조건과 API 는 [커넥터 연결](../../docs/features/connector.md) 이 갖는다([ADR-043](adr/ADR-043-커넥터는-plugin-의-connector-json-으로-선언하고-control-plane-은-범용-흐름만-갖는다.md)). `(user_id, connector_id)` 가 유일하다.

- `fields` 는 JSON 텍스트 `{"values": {key: 값}, "secretPrefixes": {key: 앞 4자}}` 다. 비밀 칸의 원문과 해시는 넣지 않는다. 비밀이 아닌지는 DB 가 아니라 Control Plane 이 manifest 의 `secret` 으로 판정해 지킨다
- 앞부분은 값이 16자 이상일 때만 넣는다. 16자 미만인 값은 앞 4자가 원문의 큰 부분이기 때문이다. V40 이전에 저장한 행은 원래 길이를 알 수 없어 `secretPrefixes` 가 비어 있다
- `fields` 를 MySQL `JSON` 타입이 아니라 문자열로 둔다. 칸 안을 SQL 로 찾을 일이 없고, 검사가 쓰는 H2 와 MySQL 의 JSON 리터럴 문법이 달라 이관 SQL 을 한 벌로 쓸 수 없다
- `undeclared_tools` 는 마지막 확인에서 MCP 서버가 낸 도구 가운데 manifest 의 `tools` 에 없던 수다. `vault_stored` 는 칸 값을 대시보드 plugin 의 보관 파일에 둔 적이 있는가다
- 해제해도 행은 남기고 `fields` 를 빈 `values` 와 빈 `secretPrefixes` 로 비운다. 지우는 경로는 없다

`agent_id`, `restart_required`, `desired_enabled` 는 바인딩([ADR-083](../../docs/adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md))이 생기며 쓰지 않는 칸이 됐다.
이전 이미지로 되돌릴 때를 위해 남겨 두고, 칸을 지우는 마이그레이션은 옛 커넥터 에이전트를 정리할 때 둔다. 엔티티는 두 boolean 칸을 매핑하지 않고 `agent_id` 는 읽기 전용(`legacyAgentId`)으로만 매핑해, 새 행에서 `agent_id` 는 비고 두 boolean 칸은 거짓이다. 옛 값은 지운 에이전트를 정리할 때만 비운다. `agent_id` 의 유일 제약과 FK 는 남아 있고, 비어 있는 값은 유일 제약에 걸리지 않는다.

### agent_connector_binding

에이전트에 연결을 붙인 것이고, 에이전트와 연결은 다대다다([ADR-083](../../docs/adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md)). `(agent_id, connection_id)` 가 유일하다.
연결의 `status` 는 「값이 확인돼 쓸 수 있는가」 이고, 바인딩의 `status` 는 「그 에이전트의 profile 에 설치되고 반영됐는가」 다. 바인딩의 `desired_enabled` 가 참이어도 반영 확인 전에는 `PENDING` 이다.

- `mcp_server` 는 붙일 때 manifest 가 선언한 MCP 서버 이름이다. 운영자가 카탈로그에서 커넥터를 빼도 그 profile 에 설치된 서버 이름을 잃지 않게 둔다. 마이그레이션이 만든 옛 바인딩은 비어 있고 연결 확인과 관리자 반영 완료가 채운다
- `restart_required` 는 재시작이 profile 마다 필요하므로 연결이 아니라 여기에 둔다. `restart_required_since` 는 재시작이 필요해진 가장 늦은 설치 시각이고, 관리자가 재시작한 뒤에 다시 설치가 있었으면 반영 완료가 대기를 풀지 않게 하려고 둔다
- `apply_due_at` 은 재시작 없이 MCP 설정 맞추기 주기가 반영할 설치를 보냈을 때의 반영 예정 시각이다. 지나면 Control Plane 이 이 칸을 비우고 반영 맞추기를 스스로 한 번 돌린다([ADR-20261007 / connector-live-reload](../../docs/adr/ADR-20261007-connector-live-reload.md))
- 떼면 행을 지운다. 떼기는 재시작을 기다리지 않아 남길 상태가 없고, 이력은 `connector_action` 이 갖는다. 옛 커넥터 에이전트의 바인딩만 그 에이전트를 켜고 끈다

### connector_action

커넥터 도구 호출 하나의 판정과, 승인이 필요했던 호출의 승인 줄이다([ADR-049](adr/ADR-049-커넥터-도구-호출은-profile-plugin-의-hook-이-control-plane-에-물어-판정한다.md), [ADR-050](adr/ADR-050-커넥터-쓰기는-control-plane-이-승인-줄을-저장하고-승인한-인자로-한-번만-실행한다.md)).
허용과 거절도 한 줄씩 남긴다. 사용자 수가 적어 양이 문제가 되지 않는다.

- `agent_id` 는 판정한 실행의 에이전트이고, 승인하면 그 에이전트의 profile 에서 실행한다. 바인딩 전에 남은 옛 줄은 옛 커넥터 에이전트를 가리킨다. 지운 에이전트를 정리하면 비우고, 비어 있는 줄은 승인해도 실행하지 않는다
- `tool_name` 은 MCP 서버의 원래 도구 이름이고 `hermes_tool` 은 hook 이 받은 등록 이름이다. 등록 이름과 맞는 것을 확인하지 못한 호출은 `tool_name` 을 비운다
- `risk` 와 `approval_mode` 는 판정 당시의 값이다. 정책을 읽지 못했거나, 선언이 없었거나, 연결이 준비되지 않아 거절한 호출은 비어 있다
- `args_sha256` 은 원문을 두지 않는 줄에서도 무엇을 불렀는지 맞춰 보려고 둔다. 승인 엔진이 켜지기 전에 남은 `NEEDS_APPROVAL` 줄은 `status` 와 `args_json` 이 비어 있어 승인 줄로 다루지 않는다
- `dedupe_key` 는 `v1-connector`, profile, 루트 session, session, `tool_call_id` 를 줄바꿈으로 이어 SHA-256 한 값이고 유일하다. 같은 호출이 다시 와도 줄이 하나다
- `result_text` 의 `FAILED` 줄은 커넥터가 선언한 오류 계약이 있을 때만 `{"kind": "connector_error", "code", "details", "recovery"}` 를 담고 없으면 null 이다([ADR-092](../../docs/adr/ADR-092-승인한-실행의-실패는-커넥터가-선언한-오류-코드와-복구-어휘와-정수-세부만-에이전트까지-전한다.md))
- 시스템이 실행하지 않고 끝낸 `REJECTED` 줄은 `error_code` 로 까닭을 남긴다. 값과 뜻은 `ConnectorAction` 의 상수가 갖는다
- 외래 키는 `user_id` 와 `agent_id` 에만 둔다. 실행과 대화는 지워져도 이 줄을 남긴다. 에이전트 행을 정리해도 `agent_id` 만 비운다. 외부 서비스에 무엇을 쓰려 했고 누가 승인했는지의 이력이기 때문이다
- 인자 원문은 주인에게만 보인다. 관리자 목록과 로그에는 싣지 않는다

### connector_action_execution

승인 줄 하나에 속한 금융 실행 내용이다.
저장과 검증 모델만 구현했으며 현재 금융 호출은 계속 차단한다.
인증과 권한 소비, 준비 API 및 주문 실행은 후속 구현이 맡는다.
JSON 검증 계약은 [커넥터 도구 정책과 승인](../../docs/features/connector-policy.md)의 「금융 실행 내용의 저장과 검증」이 갖는다.

| 컬럼 | 타입과 제약 |
| --- | --- |
| `action_id` | BIGINT NOT NULL, PK 및 `connector_action` FK, ON DELETE CASCADE |
| `connection_id`, `binding_id` | BIGINT NOT NULL, FK가 없는 이력 번호 |
| `connection_updated_at`, `binding_updated_at` | DATETIME(6) NOT NULL, 준비 당시 각 행의 `updated_at` |
| `execution_args_json`, `summary_json`, `scope_json` | MEDIUMTEXT NOT NULL, 원문 또는 암호문 |
| `content_key_id` | BIGINT NULL, `user_data_key` 식별자, FK 없음 |
| `execution_args_sha256`, `scope_sha256` | VARCHAR(64) NOT NULL, 암호화 전 UTF-8 원문의 SHA-256 |
| `request_key` | VARCHAR(64) NOT NULL, 중복 의도 조회용 일반 인덱스 |
| `supersedes_unknown_action_id` | BIGINT NULL UNIQUE, 자기 참조 FK가 없는 이력 식별자 |
| `protocol` | VARCHAR(32) NOT NULL |
| `ticket_id` | BINARY(16) NULL UNIQUE, 권한 원문과 구분한 UUID 식별자 |
| `ticket_expires_at`, `consumed_at` | DATETIME(6) NULL |
| `created_at` | DATETIME(6) NOT NULL |

`ConnectorExecutionSnapshot.capture`가 연결의 공개 칸과 실행 args, 표시 값을 검증한 뒤 저장할 내용을 만든다.
연결과 바인딩의 소유자 및 에이전트가 승인 줄과 같아야 한다.
기존 `connector_action.args_json`과 `args_sha256`은 원래 요청의 중복 판정용으로 남긴다.
실행 원문과 표시 값, scope 및 두 revision은 저장한 뒤 바꾸지 않는다.
권한 발급·소비 시각과 ticket 식별자, 새 요청의 선조 식별자는 현재 저장 함수가 채우지 않는다.

세 본문은 기존 `TextCipher`로 각각 암호화하며 동일한 keyId여야 저장한다.
AAD는 `connector_action_execution:<action_id>:column:<컬럼 이름>:user:<소유자 번호>`다.
암호화가 꺼져 있으면 평문과 NULL keyId를 저장한다.
읽기는 저장된 keyId로 정하므로 NULL이면 현재 설정과 관계없이 평문을 읽고,
비NULL이면 현재 암호화가 꺼져 있어도 복호화를 거친다.
암복호화 실패, keyId 불일치와 원문 해시 불일치는 저장·읽기를 거절한다.
본문과 key 값, 권한 원문은 로그와 예외 메시지에 남기지 않는다.

action을 지우면 실행 내용도 지운다.
연결·바인딩과 이전 UNKNOWN의 번호는 당시 이력 값이며 해당 행 삭제에 따라 실행 내용을 지우지 않는다.
`supersedes_unknown_action_id`는 명시적 새 요청 경로를 구현하기 전까지 NULL로 둔다.
근거는 [ADR-20261010 / financial-execution-guard](../../docs/adr/ADR-20261010-financial-execution-guard.md)다.

### connector_tool_grant

사용자가 도구 하나에 준 상시 허락이다. 승인하면서 기간을 골라 준다([ADR-050](adr/ADR-050-커넥터-쓰기는-control-plane-이-승인-줄을-저장하고-승인한-인자로-한-번만-실행한다.md)).
`expires_at` 은 비지 않는다. 무기한 허락은 없다. `revoked_at` 이 비고 `expires_at` 이 지금보다 뒤인 줄만 유효하다.
`revoked_at` 은 사용자가 거두었거나, 연결을 해제했거나, 선언이 상시 허락을 닫아 정리가 거둔 시각이다.
상시 허락을 닫은 도구에는 만들지 않고, 판정할 때도 허락을 보지 않는다. 그 도구에 남은 줄은 주기 정리가 거둔다([ADR-065](../../docs/adr/ADR-065-외부로-나가는-도구는-상시-허락을-닫는-선언을-둔다.md)).
같은 도구에 허락을 다시 주면 새 줄을 만든다. 유일 제약은 없다.

## 사용자 브라우저 표

### user_browser

브라우저 하나다. 사용자 하나에 한 줄이다. 상태 전이는 [`docs/features/user-browser.md`](../../docs/features/user-browser.md) 가, 결정은 [ADR-20261007 / user-browser](../../docs/adr/ADR-20261007-user-browser.md) 가 갖는다.
`profile_key` 는 프로필 디렉터리 이름이다. 첨부 디렉터리 키와 같은 계산이지만 루트가 다르다.
`version` 은 낙관적 잠금이라 전이가 한 번에 하나씩만 일어난다.
`user_id` 에 외래 키를 둔다. 사용자 줄이 지워지면 이 줄도 지워진다.
쿠키, 저장소, 열린 주소, 화면 프레임은 이 표에 없다. 로그인 세션은 프로필 디렉터리에만 남는다.

## 할 일과 먼저 알리기 표

### follow_up

뜻과 판정은 [`docs/features/attention.md`](../../docs/features/attention.md) 가 갖는다.
`conversation_id` 는 에이전트의 제안이면 그 실행의 대화이고, 직접 더할 때 고르지 않았으면 비어 있다. `title_key` 는 정규화한 제목의 해시이고, 같은 할 일인지 이 칸으로 본다.
**`uk_follow_up_open_title`(`user_id`, `title_key`, `open_marker`)은 같은 할 일이 열린 채 둘이 되는 것을 막는다.**
`open_marker` 는 `PROPOSED` 와 `OPEN` 이면 1 이고 끝난 상태면 비어 있다. 유일 제약은 비어 있는 값을 서로 다르다고 보므로 끝난 줄은 걸리지 않는다. 이 칸은 유일 제약에만 쓴다.
외래 키는 사용자에만 둔다. 대화와 실행이 지워져도 줄은 남는다. 대화는 숨기기만 하기 때문이다.

### attention_control

한 사용자의 한 카드의 한 `itemKey` 에 한 줄이다. 새 제어는 그 줄을 고친다.
**유일 제약 `uk_attention_control_item` 에 `card_key` 를 넣어 카드마다 제어를 나눈다.** 같은 대화가 두 카드에 나와도 제어가 서로 덮어쓰지 않는다.
`card_key` 는 API 의 소문자 카드 열쇠를 대문자로 쓴 값이다. `state_key` 는 `HIDE` 일 때 숨긴 상태이고, 판정의 `stateKey` 가 이것과 다르면 다시 보인다.

### attention_event

**유일 제약 `uk_attention_event_once`(`user_id`, `item_key`, `state_key`, `event_type`, `attention`)는 화면을 열 때마다 같은 사건이 쌓이는 것을 막는다.**
판정 칸이 제약에 들어 있어, 같은 상태에서 `LATER` 가 `NOW` 로 바뀌면 `NOW` 의 `SHOWN` 이 따로 남는다.
`stale` 은 그때 출처의 신선도가 `STALE` 이었다는 뜻이다. 제목과 본문을 담는 칸이 없다.

`attention` 은 아래를 차례로 보고 처음 맞는 것을 쓴다.

1. 지금 응답에 그 항목이 있으면 그 판정이다. `HIDDEN`, `SNOOZED` 는 요청의 카드 안에서만 찾는다.
2. 없으면 그 요청자가 그 항목으로 남긴 가장 최근 `SHOWN` 의 판정이다. `OPENED`, `ACTED` 는 `stateKey` 까지 같은 `SHOWN` 만 보고, `HIDDEN`, `SNOOZED` 는 상태와 상관없이 본다.
3. 그것도 없고 숨겼거나 미뤄 억제 전 후보에만 있으면 `SUPPRESSED` 다.

그래서 한 번 보였다가 숨긴 항목의 사건은 `SUPPRESSED` 가 아니라 그때 보인 `NOW` 나 `LATER` 로 남는다.

## 알림 표

### notification

알림 하나다([ADR-070](../../docs/adr/ADR-070-알림은-control-plane-의-notification-표가-원장이고-웹은-사용자-단위-SSE-로-받는다.md)).
`id` 는 Control Plane 밖으로 나가지 않는다. 화면과 API 는 `public_id` 를 쓴다.
`body` 는 빈 문자열을 받는다. 도구 인자 원문, 비밀값, 모델 답 전문을 넣지 않는다.
`target_type` 과 `target_public_id` 는 함께 채우거나 함께 비운다. 목록 화면을 가리키는 `ADMIN_CONNECTIONS` 만 `target_type` 을 채우고 `target_public_id` 를 비운다.
`user_id` 에만 외래 키를 둔다. 갈 곳은 지워져도 알림을 남긴다. 보관 기간이 지난 줄은 읽었는지와 상관없이 지운다.

## 예약 작업 표

### task

예약 작업 하나다. 언제 발화하고 어떻게 시작하는지는 [`docs/features/schedule.md`](../../docs/features/schedule.md) 가 갖는다([ADR-076](adr/ADR-076-예약-작업은-control-plane-이-갖고-발화한-실행은-대화-turn-경로로-돈다.md)).

- `owner_user_id` 는 누구의 권한으로 도는가이고 바뀌지 않는다. `agent_id` 는 주인이 고칠 수 있다
- `kind` 가 `CHECK` 인 줄은 매일 깨우기이고 `instruction` 과 `model_tier` 를 비운다
- `conversation_id` 는 `SINGLE` 일 때 결과를 쌓는 대화다. 첫 발화가 만들기 전에는 비어 있고, `NEW_PER_RUN` 은 늘 비어 있다
- `model_tier` 가 비면 발화가 대화의 모델 선택을 건드리지 않는다
- 외래 키는 `owner_user_id` 에만 둔다. 에이전트와 대화는 지워도 행이 남는 표라 걸지 않는다

**`check_owner_user_id`, `check_agent_id` 생성 열은 `CHECK` 일 때만 주인과 에이전트 번호를 채운다.** 두 칸의 유일 제약으로 깨우기 설정 하나를 강제한다. `TURN` 줄은 두 칸이 비어 있어 걸리지 않는다.
`CHECK` 는 같은 설정 줄을 다시 쓰고, 점검 대화를 쓰므로 `conversation_mode`, `conversation_id` 로 대화를 만들지 않는다.

### task_trigger

작업의 시각이다([ADR-077](adr/ADR-077-발화는-trigger-와-예정-시각의-유일-제약으로-한-번만-만들고-놓친-발화는-작업마다-정한다.md)).
`fire_at` 과 `next_fire_at` 은 UTC 이고, 한 번 발화한 `ONCE` 는 `next_fire_at` 이 비어 있다. `last_fired_at` 은 마지막으로 처리한 예정 시각이다.
`task_id` 의 유일 제약은 작업 하나에 시각 하나라는 지금의 규칙이다. 다른 종류의 trigger 를 더하는 단계가 이 제약을 바꾼다.
시각을 고치면 같은 줄을 고친다. 발화 기록의 유일 제약이 이 줄의 번호를 쓰므로 줄을 새로 만들지 않는다.

### task_run

발화 한 번이다. `(trigger_id, scheduled_for)` 가 유일해, 같은 예정 시각의 발화는 서버를 다시 띄워도 하나다.

- `owner_user_id` 는 그 발화 때의 작업 주인이다. 하루 발화 수를 조인 없이 세려고 따로 둔다
- `reason` 은 `SUCCEEDED` 면 비어 있거나, 답이 `[SILENT]` 뿐이면 `NOTHING_TO_REPORT` 다
- `execution_id` 는 turn 이 끝난 모양을 받았을 때만 찬다
- `proactive_check_id` 는 `CHECK` 발화가 연 점검 줄이다. 저장과 발화 연결을 한 트랜잭션으로 끝낸다
- 외래 키는 `task_id` 와 `trigger_id` 에 둔다. 작업과 시각은 지우지 않으므로 걸어도 된다

## 먼저 살펴보기 표

### proactive_check

살펴보기 한 번이다. 실행별 토큰과 금액은 `agent_execution` 이 갖고, 깨우기 예산을 측정할 트리 토큰 합계만 여기에도 남긴다.

- `root_execution_id` 는 유일하다. 살펴보기 트리를 나누는 기준이라 실행 줄 하나가 두 살펴보기에 속하지 않게 한다. 실행 줄을 만들기 전에 실패하면 비어 있다
- `writes_allowed` 는 시작할 때 그 에이전트의 `proactive_check_writes_allowed` 를 옮겨 적는다. 도중에 관리자가 바꿔도 그 살펴보기의 경계는 옮겨 적은 값이 정한다(ADR-082)
- `report_opened_at` 은 요청자가 「보고 열기」 를 누르거나, 점검 대화를 처음 읽거나, 그 대화에 메시지를 보낸 때 채운다
- 지운 에이전트를 정리할 때 그 에이전트의 줄을 지운다. 딸린 줄은 FK 의 `ON DELETE CASCADE` 와 `ON DELETE SET NULL` 이 지우거나 비우고, 비용은 `agent_execution` 에 남는다
- `trigger` 는 MySQL 의 예약어라 칸 이름을 `trigger_type` 으로 둔다

### proactive_check_finding

결과 블록의 발견 하나다. 다음 살펴보기의 입력과, 「지금」 화면과 할 일로 이을 때의 원래 기록이 된다. 살펴보기 줄을 지우면 함께 지운다.
발견의 이유, 사실, 추정, 다음 행동은 대화 메시지에만 있고 이 표에 두지 않는다. 같은 글을 두 곳에 두면 사용자가 대화를 지워도 한쪽에 남는다.

### proactive_check_problem

결과 블록 버전 3의 문제 후보 하나다([ADR-093](adr/ADR-093-문제-찾기는-살펴보기-결과의-문제-후보로-받고-control-plane-이-근거와-중복을-결정적으로-검사한다.md)). 받아들인 것과 버린 것을 모두 남긴다.
칸의 뜻과 검사는 [`docs/features/proactive.md`](../../docs/features/proactive.md) 의 「문제 후보」 가 갖는다. 살펴보기 줄을 지우면 함께 지운다. 원문 본문과 커넥터 응답은 남기지 않는다.

### `proactive_value_evaluation`

가치 평가 시도 하나다. [가치 평가](../../docs/features/proactive.md)가 입력과 결과, replay 계약을 갖는다.
후보가 나온 살펴보기나 요청 사용자를 지우면 평가도 함께 지운다. 전체 개인 문맥과 원시 모델 응답을 저장하지 않는다.

### `proactive_autonomy_decision`

행동 정책의 판정 하나다. [행동 정책](../../docs/features/proactive.md)이 수준과 까닭, 실행 키 계약을 갖는다.
`execution_key` 는 유일하다. 원천 살펴보기 하나에서 자동 실행이 한 번만 열리게 하는 장치이고, `EXECUTE` 만 채운다.
`candidate_id` 는 FK 가 없다. 평가 스냅샷의 후보 식별자라 지금의 후보 줄이 지워져도 판정이 남는다.
사용자, 평가, 원천 살펴보기를 지우면 함께 지운다. 시작한 살펴보기(`execution_check_id`)를 지우면 그 칸만 비운다. `inputs_json` 에는 판정에 쓴 값만 두고 축 설명과 후보 글은 두지 않는다.

### `user_autonomy_preference`

줄이 없으면 모두 꺼짐이다. 사용자를 지우면 함께 지운다.
다른 종류의 자동 실행을 열 때 칸을 더한다. 한 칸이 여러 종류의 허락을 뜻하지 않게 한다.

### `proactive_loop_setting`

사용자가 에이전트마다 매일 루프를 켠 설정이다. [매일 루프](../../docs/features/proactive.md)의 「사용자 설정」 이 뜻을 갖는다.
줄이 없으면 꺼짐이다. 사용자나 에이전트를 지우면 함께 지운다.
`(user_id, agent_id)` 는 유일하다. 하루 상한을 셀 때 그 사용자의 줄을 모두 쓰기 잠금으로 읽는다.

### `proactive_loop_run`

매일 깨우기 살펴보기 하나를 잇는 시도다. 상태와 순서는 [매일 루프](../../docs/features/proactive.md)가 갖는다.
`source_check_id` 는 유일하다. 살펴보기 하나에 시도 하나라서, 서버가 도중에 멈춰도 같은 원천에서 평가와 판정이 두 번 생기지 않는다.
사용자나 원천 살펴보기를 지우면 함께 지운다. 이 시도가 만든 평가를 지우면 `evaluation_id` 만 비운다. 기동 때 닫은 줄도 비어 있다.
글과 원문, provider 이름은 두지 않는다. provider 는 평가의 `evidence_json` 이 갖는다.

## 판단 피드백 표

### decision_feedback_event

제안 하나의 사건 한 줄이다. 덧붙이기만 하고 고치지 않는다. 사건의 뜻과 보관은 [`docs/features/proactive.md`](../../docs/features/proactive.md) 가 갖는다. `subject_key` 가 같은 제안의 사건을 잇는 열쇠이고, 지금 화면의 `itemKey` 와 같은 모양이다.
사용자를 지우면 함께 지운다. 다른 사용자는 관리자여도 읽지 못한다.
`conversation_id` 의 외래 키도 `ON DELETE CASCADE` 지만 대화 줄은 지우지 않으므로, 대화를 지우면 서비스가 `conversation_id` 로 그 대화의 사건을 지운다.
실행, 살펴보기, 행동 정책 판정을 지우면 그 번호 칸만 비운다(`ON DELETE SET NULL`).
제목, 본문, 인자, 결과 글, 모델이 쓴 글을 담는 칸이 없다. 바뀐 칸은 이름만 남기고 값은 담지 않는다.

## 암호화 key 표

### user_data_key

사용자 한 명의 데이터 key(DEK)를 KEK 로 감싼 것이다. 사용자마다 한 줄이다.
원문 key 는 저장하지 않는다. KEK 는 데이터베이스 밖의 서버 파일에 있고, 이 표에는 어느 KEK 로 감쌌는지만 적는다.
근거와 위협 모델, 감쌀 때의 AAD 는 [ADR-20261008 / data-encryption](adr/ADR-20261008-data-encryption.md) 에 있다.

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
| `docs/backend/user-browser.md` | [`docs/features/user-browser.md`](../../docs/features/user-browser.md) 「사용자 브라우저」 | <!-- ref-ignore: 적용된 마이그레이션 주석이 가리키는 옛 경로다 -->
