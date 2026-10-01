# 저장 모델

## connector_connection

사용자마다 커넥터 하나에 연결 하나를 둔다. 근거는 [ADR-043](adr/ADR-043-커넥터는-plugin-의-connector-json-으로-선언하고-control-plane-은-범용-흐름만-갖는다.md) 이다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | `BIGINT` 기본키 | |
| `user_id` | `BIGINT NOT NULL` | `app_user` 참조 |
| `connector_id` | `VARCHAR(64) NOT NULL` | manifest 의 `id` |
| `agent_id` | `BIGINT NOT NULL`, 유니크 | `agent` 참조. 연결 전용 에이전트 |
| `status` | `VARCHAR(20) NOT NULL` | `DISCONNECTED`, `PENDING`, `READY` |
| `fields` | `VARCHAR(4000) NOT NULL` | JSON 텍스트 `{"values": {key: 값}, "secretPrefixes": {key: 앞 4자}}`. 비밀 칸의 원문과 해시는 넣지 않는다. 앞부분은 값이 16자 이상일 때만 넣는다 |
| `restart_required` | `BOOLEAN NOT NULL` | 공유 gateway 재시작 뒤 반영 완료를 기다린다 |
| `desired_enabled` | `BOOLEAN NOT NULL` | env 와 설치 반영이 모두 성공해 활성화 후보가 되었는가. 등록, 교체, 해제 시작과 반영 실패에서 false. true 여도 실행 확인 전에는 `PENDING` |
| `checked_at` | `DATETIME(6)` | 마지막 확인 시각. 비어도 된다 |
| `undeclared_tools` | `INT NOT NULL` 기본 0 | 마지막 확인에서 MCP 서버가 낸 도구 가운데 manifest 의 `tools` 에 없던 수. `schema: 1` 은 0 이다 |
| `created_at`, `updated_at` | `DATETIME(6) NOT NULL` | |

- `(user_id, connector_id)` 가 유니크다. 한 사람이 같은 커넥터를 둘 연결하지 못한다
- V41 은 그때까지 `READY` 이던 연결을 모두 `PENDING` 으로 내리고 그 연결용 에이전트를 끄고 사진 받기도 내렸다. 그 profile 의 `fos-ctx` 가 옛 판이라 도구 호출이 판정 없이 나가기 때문이다([ADR-048](adr/ADR-048-커넥터-도구-호출은-profile-plugin-의-hook-이-control-plane-에-물어-판정한다.md)). 연결 확인과 gateway 재시작과 관리자 반영 완료로 다시 `READY` 가 된다
- 해제해도 행은 남기고 `fields` 를 `{"values": {}, "secretPrefixes": {}}` 로 비운다. 지우는 경로는 없다
- 칸 값이 비밀이 아닌지는 DB 가 아니라 Control Plane 이 manifest 의 `secret` 으로 판정해 지킨다
- `fields` 를 MySQL `JSON` 타입이 아니라 문자열로 둔다. 칸 안을 SQL 로 찾을 일이 없고, 검사가 쓰는 H2 와 MySQL 의 JSON 리터럴 문법이 달라 이관 SQL 을 한 벌로 쓸 수 없다. 엔티티는 변환기로 record 로 읽는다
- V40 이 모든 행의 `secretPrefixes` 를 비웠다. 그 전에는 앞부분을 8자까지 저장했고 원래 길이를 남기지 않아, 짧은 비밀의 대부분이 저장된 행을 골라낼 수 없었다. `values` 는 그대로 둔다
- 가계부 전용으로 먼저 만든 `accountbook_connection` 은 V38 이 이 표로 옮기고 지웠다. `connector_id` 는 `fos-accountbook`, `token_prefix` 는 `secretPrefixes.token`, `family_uuid` 는 `values.family` 가 됐다

`agent.connector_managed BOOLEAN NOT NULL DEFAULT FALSE` 는 연결 전용 에이전트를 표시한다.
이 값이 참인 에이전트는 일반 설정 편집과 공개, 삭제 경로를 막고 사용자당 에이전트 상한에 세지 않는다.
상태 변화는 [커넥터 연결](connectors.md)이 갖는다.

`agent.connector_attachments BOOLEAN NOT NULL DEFAULT FALSE` 는 그 연결용 에이전트가 사진을 받는지다.
선언은 plugin 의 `connector.json` 에 있다. Control Plane 이 연결 확인과 관리자 반영 완료에서 선언한 toolset 이 켜진 것을 확인했을 때만 참으로 둔다.
연결용이 아닌 에이전트에서는 쓰지 않는다.

MySQL 8.4 에 둔다.
마이그레이션은 `backend/src/main/resources/db/migration/` 이 소유하고 이 문서는 뜻을 적는다.

비밀값은 어느 표에도 넣지 않는다.
AI credential 은 Hermes profile 의 `.env` 에, profile 의 API server key 는 홈서버의 파일에 있다.

## connector_action

커넥터 도구 호출 하나의 판정과, 승인이 필요했던 호출의 승인 줄이다. 근거는 [ADR-048](adr/ADR-048-커넥터-도구-호출은-profile-plugin-의-hook-이-control-plane-에-물어-판정한다.md) 과 [ADR-049](adr/ADR-049-커넥터-쓰기는-control-plane-이-승인-줄을-저장하고-승인한-인자로-한-번만-실행한다.md) 이다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | `BIGINT` 기본키 | |
| `public_id` | `BINARY(16) NOT NULL`, 유니크 | 화면과 모델에 보이는 승인 요청 번호. 대화의 공개 식별자와 같은 방식이다([ADR-025](adr/ADR-025-대화는-주소에-공개-식별자를-쓰고-번호는-안에만-둔다.md)) |
| `user_id` | `BIGINT NOT NULL` | 연결의 주인 |
| `agent_id` | `BIGINT NOT NULL` | 연결용 에이전트 |
| `connector_id` | `VARCHAR(64) NOT NULL` | |
| `tool_name` | `VARCHAR(128)` | MCP 서버의 원래 도구 이름. 대응 파일에서 찾지 못했거나 등록 이름과 맞는 것을 확인하지 못한 호출은 비운다 |
| `hermes_tool` | `VARCHAR(128) NOT NULL` | hook 이 받은 등록 이름 |
| `risk` | `VARCHAR(16)` | 판정 당시의 위험도. 정책을 읽지 못했거나, 선언이 없었거나, 연결이 준비되지 않아 거절한 호출은 비운다 |
| `approval_mode` | `VARCHAR(16)` | 판정 당시의 승인 방식. 위와 같을 때 비운다 |
| `decision` | `VARCHAR(20) NOT NULL` | `ALLOWED`, `DENIED`, `NEEDS_APPROVAL` |
| `deny_reason` | `VARCHAR(40)` | `DENIED` 일 때만. `POLICY_UNAVAILABLE`, `NOT_READY`, `UNDECLARED`, `RISK_NOT_OPEN`, `ARGS_TOO_LARGE` |
| `passed` | `BOOLEAN NOT NULL` | hook 에 통과로 답했는가 |
| `status` | `VARCHAR(20)` | 승인 줄만. `PENDING`, `EXECUTING`, `SUCCEEDED`, `FAILED`, `UNKNOWN`, `REJECTED`, `EXPIRED` |
| `origin_execution_id` | `BIGINT NOT NULL` | hook 의 session 으로 찾은 실행 |
| `conversation_id` | `BIGINT` | 그 실행의 대화. 결과를 돌려줄 곳이다. 대화 없는 실행이면 비운다 |
| `dedupe_key` | `VARCHAR(64) NOT NULL`, 유니크 | `v1-connector`, profile, 뿌리 session, session, `tool_call_id` 를 줄바꿈으로 이어 SHA-256 한 값. 같은 호출이 다시 와도 줄이 하나다 |
| `args_json` | `MEDIUMTEXT` | 승인 줄만. hook 이 보낸 글자 그대로다. 16KB 까지 |
| `args_sha256` | `VARCHAR(64) NOT NULL` | 인자 글의 SHA-256. 원문을 두지 않는 줄에서도 무엇을 불렀는지 맞춰 볼 수 있다 |
| `expires_at` | `DATETIME(6)` | 승인 줄만. 만든 시각에서 24시간 뒤 |
| `decided_at` | `DATETIME(6)` | 승인, 거절, 만료한 시각 |
| `executed_at` | `DATETIME(6)` | 실행 결과를 적은 시각 |
| `result_text` | `MEDIUMTEXT` | 실행 결과. 위임 답과 같은 상한으로 자른다 |
| `error_code` | `VARCHAR(64)` | 공통 오류 어휘 넷과 `TIMEOUT` |
| `result_delivered_at` | `DATETIME(6)` | 결과나 거절, 만료를 대화에 전한 시각. `agent_execution` 의 같은 이름 칸과 뜻이 같다 |
| `created_at` | `DATETIME(6) NOT NULL` | |

- 허용과 거절도 한 줄씩 남긴다. 사용자 수가 적어 양이 문제가 되지 않는다
- 승인 엔진이 켜지기 전에는 `NEEDS_APPROVAL` 인 줄도 `passed` 가 참이고 `status` 와 `args_json` 이 빈다
- `(conversation_id, status)` 와 `(user_id, created_at)` 에 색인을 둔다
- 외래 키는 `user_id` 와 `agent_id` 에만 둔다. 실행과 대화는 지워져도 이 줄을 남긴다
- 인자 원문은 주인에게만 보인다. 관리자 목록과 로그에는 싣지 않는다

## connector_tool_grant

사용자가 도구 하나에 준 상시 허락이다. 승인 엔진과 함께 들어온다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | `BIGINT` 기본키 | |
| `user_id` | `BIGINT NOT NULL` | |
| `connector_id` | `VARCHAR(64) NOT NULL` | |
| `tool_name` | `VARCHAR(128) NOT NULL` | 원래 도구 이름 |
| `expires_at` | `DATETIME(6) NOT NULL` | 무기한은 없다 |
| `created_at` | `DATETIME(6) NOT NULL` | |
| `revoked_at` | `DATETIME(6)` | 사용자가 거두었거나 연결을 해제한 시각 |

- `revoked_at` 이 비고 `expires_at` 이 지금보다 뒤인 줄만 유효하다
- `approval: always` 인 도구에는 만들지 않는다. 판정할 때도 `always` 는 허락을 보지 않는다
- 같은 도구에 허락을 다시 주면 새 줄을 만든다. 유니크 제약은 없다

## app_user

사용자이다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | BIGINT | |
| `email` | VARCHAR(320) | 유일하다. 로그인한 Google 계정 |
| `display_name` | VARCHAR(100) | 화면에 보일 이름 |
| `group_id` | BIGINT | 사용자가 속한 그룹. 지금은 그룹이 하나뿐이다 |
| `role` | VARCHAR(20) | `ADMIN` 또는 `MEMBER`. 첫 사용자가 `ADMIN` 이 된다 |

## allowed_person

로그인할 수 있는 사람의 목록이다.
**여기 없는 주소는 토큰을 받지 못한다.**

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | BIGINT | |
| `email` | VARCHAR(320) | 유일하다. 로그인에 쓸 Google 계정 |
| `display_name` | VARCHAR(100) | 화면에 보일 이름 |
| `hermes_profile` | VARCHAR(64) | 유일하다. 관리자가 적는다 |
| `enabled` | BOOLEAN | 내리면 들어오지 못한다 |
| `created_at` | DATETIME(6) | |

`app_user` 와 나누어 둔다. 둘이 뜻하는 것이 다르다.

| 표 | 뜻 |
| --- | --- |
| `allowed_person` | 들어와도 된다고 정한 사람 |
| `app_user` | 실제로 들어온 적이 있는 사람 |

**둘을 잇는 것은 `email` 이다.** 외래 키를 두지 않는다.
허용한 시점에는 `app_user` 가 없고, 지운 뒤에도 실행 기록은 `app_user` 를 가리켜야 한다.

`hermes_profile` 이 유일한 이유는 두 사람이 같은 profile 을 쓰면 격리가 깨지기 때문이다.
`agent` 표의 `hermes_profile` 도 유일하므로 같은 제약이 두 곳에 있다.

### 비어 있으면 아무도 들어오지 못한다

이 표가 로그인의 유일한 근거다.
배포할 때 지금 쓰는 주소를 한 번 넣고, 그 뒤 환경 변수를 지운다.
**넣는 절차는 비공개 저장소가 소유한다.** 이 저장소에 주소를 적지 않는다.

## agent

사용자가 대화를 시작할 때 고르는 실행 단위다.
에이전트 하나가 Hermes profile 하나를 가리키고, 공개 범위가 누가 쓸 수 있는지 정한다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `code` | VARCHAR(64) | 유일하다. 요청과 화면에서 에이전트를 가리킨다 |
| `name` | VARCHAR(100) | 화면에 보일 이름 |
| `hermes_profile` | VARCHAR(64) | 유일하다. 호스트의 key 파일 이름과 같다 |
| `api_base_url` | VARCHAR(255) | 그 profile 의 API server 주소. `/v1` 앞까지 |
| `cost_mode` | VARCHAR(20) | `SUBSCRIPTION` 또는 `API` |
| `credential_scope` | VARCHAR(20) | `SHARED_HOUSEHOLD` 또는 `DEDICATED` |
| `visibility` | VARCHAR(20) | `PRIVATE` 또는 `GROUP`. 기본값이 없다 |
| `owner_user_id` | BIGINT NULL | 주인. 사용자가 만든 에이전트는 만든 사람이다. `PRIVATE` 일 때 필요하고, `GROUP` 으로 공개해도 지우지 않는다 |
| `enabled` | BOOLEAN | 거짓이면 새 실행을 막는다 |
| `profile_managed` | BOOLEAN | 참이면 Control Plane 이 이 에이전트의 profile 을 만들었다. 에이전트를 지울 때 profile 까지 지우는 것은 이 값이 참일 때뿐이다. 기본 거짓 |
| `deleted_at` | DATETIME(6) NULL | 지운 시각. 적히면 목록과 새 대화에서 빠지고 그 에이전트의 대화는 읽기만 된다 |

**에이전트는 모델을 갖지 않는다.** 실행은 대화가 고른 값이나 profile 의 기본값으로 돈다. 막힌 계정을 쉬게 하는 것은 Hermes 가 한다.
예전의 `provider`, `model`, `model_synced_at` 칸과 에이전트별 모델 목록 표 `agent_model_option`, 막힌 provider 를 기억하던 표 `provider_state` 는 V27 이 지웠다.
모델과 effort 는 대화가 고르고, 고르지 않으면 그 profile 의 기본값으로 돈다.
근거는 [ADR-030](adr/ADR-030-모델과-effort-는-대화가-고르고-기본값은-hermes-profile-이-갖는다.md) 에 있다.
공개 범위가 접근 권한을 정하는 이유는
[ADR-007](adr/ADR-007-에이전트가-모델과-도구를-함께-정한다.md)에 있다.

**주인과 공개 범위는 따로다.** 그룹에 공개해도 만든 사람이 계속 관리한다.
이 결정 전에 운영에서 등록한 `GROUP` 에이전트는 주인이 비어 있어 `ADMIN` 만 관리한다.
근거는 [ADR-033](adr/ADR-033-사용자가-에이전트를-만들고-공개해도-만든-사람이-관리한다.md) 에 있다.

한 줄 소개 칸 `tagline` 과 추천 질문 표 `agent_starter_prompt` 는 없앴다.
추천 질문은 데이터베이스에 두지 않고 backend 메모리에만 둔다.
근거는 [ADR-036](adr/ADR-036-추천-질문은-사용자의-대화-이력으로-모델이-만들고-메모리에만-둔다.md) 에 있다.

## conversation

주고받는 하나의 스레드다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `public_id` | BINARY(16) UNIQUE | 화면 주소와 API 에 쓰는 UUID. 만들 때 정하고 바뀌지 않는다 |
| `user_id` | BIGINT | 이 대화의 주인. 다른 사용자는 읽지 못한다 |
| `agent_id` | BIGINT | 대화를 만들 때 정한다. 뒤에 바뀌지 않는다 |
| `hermes_session_id` | VARCHAR(128) NULL | 다음 turn 에 보낼 Hermes session. 새 대화는 첫 turn 을 보내기 전에 Control Plane 이 `fos-<uuid>` 로 정해 적는다. 그 전의 대화는 첫 실행이 돌려준 값이다. 압축 교체로 Hermes 가 다른 session 을 돌려주면 그 값으로 바뀐다. 특정 profile 안의 값이다 |
| `hermes_root_session_id` | VARCHAR(128) NULL | 그 대화의 뿌리 session. 새 대화는 첫 turn 을 보내기 전에 `hermes_session_id` 와 같은 `fos-<uuid>` 를 적고, 압축 교체에도 바뀌지 않는다. MCP `agent_*` 호출이 들고 오는 서명한 뿌리 session 이 이 값이다. 이 칸이 생기기 전의 대화는 비어 있다 |
| `title` | VARCHAR(200) | 첫 메시지의 앞부분. 사진을 먼저 올리려고 만든 대화는 첫 메시지 전까지 비어 있다 |
| `model_provider` | VARCHAR(64) NULL | 이 대화에서 고른 provider. `model` 과 함께 채우거나 함께 비운다 |
| `model` | VARCHAR(128) NULL | 이 대화에서 고른 모델. 비면 그 profile 의 기본 모델로 돈다 |
| `reasoning_effort` | VARCHAR(16) NULL | 이 대화에서 고른 effort. `low`, `medium`, `high`, `xhigh`, `max` 중 하나. 비면 그 profile 의 기본값이다 |
| `updated_at` | DATETIME(6) | 목록 정렬에 쓴다 |
| `deleted_at` | DATETIME(6) NULL | 사용자가 지운 시각. 채워지면 목록과 조회와 보내기에서 없는 대화와 같다 |
| `auto_turn_count` | INT NOT NULL DEFAULT 0 | 마지막 사용자 질문 뒤로 Control Plane 이 위임 결과를 전하려고 연 turn 수. 사용자 질문을 저장할 때 0 으로 돌린다. `assistant.delegation-wake.max-auto-turns`(기본 10)에 닿으면 더 깨우지 않는다 |

`hermes_session_id` 가 특정 profile 안의 값이라, 대화의 에이전트는 중간에 바뀌지 않는다.

**`agent_id` 에 FK 를 두지 않는다.** 칸도 NULL 을 받는다(V4 가 칸을 더하며 그렇게 만들었다).
에이전트를 지우는 것은 `deleted_at` 을 적는 것이라 정상 경로에서는 행이 사라지지 않는다.
그래도 행이 없는 대화가 운영에서 나왔고, 그 대화를 읽는 경로는 행이 없어도 실패하지 않게 고쳤다
([`code-architecture.md`](code-architecture.md) 의 「에이전트 만들기와 지우기」 절).
FK 를 더하려면 이미 행이 없는 대화를 먼저 정리해야 하고, 그 정리는 대화 이력을 지우거나 가짜 에이전트 행을 만드는 일이 된다.
행이 사라진 원인을 찾은 뒤 다시 판단한다.

**`id` 는 Control Plane 밖으로 나가지 않는다.** 화면과 API 는 대화를 `public_id` 로만 가리킨다.
다른 표는 지금처럼 `id` 로 대화를 참조한다.
새 대화는 UUID v7 을 받고, 마이그레이션 전에 있던 대화는 임의 값(v4)을 받았다.
근거는 [ADR-025](adr/ADR-025-대화는-주소에-공개-식별자를-쓰고-번호는-안에만-둔다.md)에 있다.

`title` 은 사용자가 고칠 수 있다. 앞뒤 공백을 떼고 1자에서 200자까지 받는다.

`model_provider`, `model`, `reasoning_effort` 는 대화의 주인이 언제든 바꾼다. 바꾼 값은 그 뒤의 보내기, 다시 생성, Memory 제안, 흐름의 하위 실행에 쓰인다.
새 대화는 셋 다 비어 있다. 고를 수 있는 모델 목록은 저장하지 않는다.

작업 영역(workspace)은 제거됐다.
근거는 [ADR-010](adr/ADR-010-작업-영역을-제거하고-에이전트가-그-자리를-갖는다.md)에 있다.

## chat_message

대화 안의 한 줄이다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `conversation_id` | BIGINT | |
| `role` | VARCHAR(20) | `USER`, `ASSISTANT`, `SYSTEM`. `SYSTEM` 은 위임 결과가 도착했다는 알림 줄이다 |
| `content` | LONGTEXT | |
| `sender_user_id` | BIGINT NULL | 이 줄을 쓴 사람. `ASSISTANT` 와 `SYSTEM` 은 비어 있다 |
| `execution_id` | BIGINT NULL | 이 답을 만든 실행. `USER` 와 `SYSTEM` 은 비어 있다 |
| `replaces_message_id` | BIGINT NULL | 이 메시지가 새 판으로 대신하는 이전 메시지. 같은 대화, 같은 `role` 이다 |

`content` 를 `LONGTEXT` 로 못 박는다.
길이를 주지 않은 `@Lob` 문자열을 Hibernate 가 MySQL 에서 `tinytext` 로 기대해 기동이 실패한다.

`replaces_message_id` 는 다시 생성이 채운다. 다시 생성한 답은 이전 답을 가리킨다.
예전에는 수정한 사용자 메시지가 고치기 전 메시지를 가리켰다. 수정을 없앴지만 그 줄은 남아 있고 화면이 계속 넘겨 볼 수 있다.
근거는 [ADR-024](adr/ADR-024-메시지-수정을-없애고-중지한-뒤-다시-보낸다.md) 에 있다.
**이전 판을 지우지 않는다.** 화면이 넘겨 볼 수 있어야 하고, 그 판을 만든 실행이 그것을 가리킨다.
근거는 [ADR-022](adr/ADR-022-다시-생성과-수정은-같은-session-에-판으로-쌓는다.md) 에 있다.

중지한 실행의 답도 한 줄로 남는다.
근거는 [ADR-021](adr/ADR-021-중지한-답은-멈춘-자리까지-남긴다.md) 에 있다.

`SYSTEM` 줄은 Control Plane 이 부모 대화를 깨울 때 한 줄 남긴다. 본문은 어느 에이전트의 결과가 도착했는지 알리는 짧은 글이고, 자식의 답 전문은 넣지 않는다.
다시 생성의 대상이 아니다. 근거는 [ADR-040](adr/ADR-040-위임-결과는-control-plane-이-부모-대화의-다음-turn-을-열어-전한다.md) 에 있다.

`sender_user_id` 는 화면이 보낸 사람 이름을 보이기 위한 것이다.
대화는 여전히 주인 한 사람의 것이고, 여러 사람이 같은 대화를 읽고 쓰는 것은 아직 만들지 않았다.

## agent_execution

에이전트가 한 번 답한 기록이다.
대부분 대화에 속해 대화 하나에 여러 줄이 달리고, 한 줄이 곧 메시지 하나다.
추천 질문을 만드는 실행은 대화 없이 한 줄만 생긴다.

**이 줄은 실행이 끝난 뒤가 아니라 시작할 때 만들어진다.**
근거는 [ADR-011](adr/ADR-011-실행은-시작할-때-기록하고-끝날-때-갱신한다.md)에 있다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `user_id` | BIGINT | 누가 물었는가 |
| `conversation_id` | BIGINT NULL | 비어 있으면 대화 밖에서 돈 실행이다. 지금은 추천 질문을 만드는 실행뿐이다 |
| `agent_id` | BIGINT | 어느 에이전트의 실행이었는가 |
| `parent_execution_id` | BIGINT NULL | 이 실행을 부른 실행. 사용자가 부른 것이면 비어 있다 |
| `root_execution_id` | BIGINT NULL | 이 실행이 속한 나무의 뿌리. 뿌리 자신은 비어 있다 |
| `profile_name` | VARCHAR(64) | |
| `hermes_run_id` | VARCHAR(128) NULL | 실행을 제출한 직후에 적는다 |
| `hermes_session_id` | VARCHAR(128) NULL | 이 실행이 속한 Hermes session. 대화 turn 은 그 대화의 뿌리 session 이고, 뿌리가 없는 옛 대화는 보낸 session 이다. 압축 교체 뒤에는 보낸 session 과 다를 수 있다. 흐름의 하위 실행과 위임한 자식은 Control Plane 이 정한 `fos-<uuid>` 다. 제출하기 전에 적는다. 최상위 session 의 MCP 호출과 최상위 자식의 등록이 서명한 뿌리 session 과 `profile_name` 으로 도는 실행을 찾을 때 쓴다([ADR-032](adr/ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md)). 하위 에이전트 session 은 이 칸이 아니라 `hermes_session_binding` 으로 찾는다. 이 칸이 생기기 전의 실행과 Memory 제안, 추천 질문을 만드는 실행은 비어 있다 |
| `delegation_key` | VARCHAR(64) NULL, 유일 | `agent_delegate` 로 만든 실행만 채운다. `v1`, 부모 실행의 `profile_name`, 뿌리 session, 그 호출의 session, `tool_call_id` 를 줄바꿈으로 이은 글의 SHA-256 소문자 16진수다. 같은 호출이 다시 와도 실행을 하나만 만든다. `agent_status` 와 `agent_stop` 은 이 칸이 있는 실행만 답한다. 정의는 [ADR-032](adr/ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md) 에 있다 |
| `output_text` | MEDIUMTEXT NULL | `agent_delegate` 로 만든 실행이 끝났을 때의 답. `agent_status` 와 `agent_stop` 이 `SUCCEEDED` 와 `CANCELLED` 에서 돌려준다. 끝난 상태와 같은 저장에서 적는다. `assistant.delegation.output-max-chars`(기본 100,000자)를 넘으면 자르고 잘렸다는 한 줄을 붙인다. 다른 실행은 채우지 않는다(대화 답은 `chat_message` 가 갖는다) |
| `result_delivered_at` | DATETIME(6) NULL | 위임 실행의 끝난 결과를 부모에게 전한 시각. 부모가 `agent_status` 나 `agent_stop` 으로 끝난 상태를 받았거나, Control Plane 이 부모 대화를 깨운 turn 에 넣었을 때 적는다. `agent_delegate` 가 줄을 만든 뒤 제출 전에 끝나 `SUBMIT_FAILED` 를 돌려줄 때도 적는다. 부모가 번호를 모르는 결과를 다시 전하지 않기 위해서다. 이 칸이 생기기 전에 끝난 위임 실행은 마이그레이션이 `finished_at`(없으면 그때 시각)으로 채워 깨우지 않는다. 그때 `RUNNING` 이던 줄은 비워 두며, 기동 정리가 `FAILED` 로 적은 뒤 전한다. 비어 있고 `SUCCEEDED` 나 `FAILED` 인 위임 실행이 깨울 대상이다([ADR-040](adr/ADR-040-위임-결과는-control-plane-이-부모-대화의-다음-turn-을-열어-전한다.md)) |
| `provider`, `model` | VARCHAR | 실제로 돈 provider 와 모델. Hermes 의 session 이 답한 값이고, 읽지 못하면 요청한 값이다. 기본값으로 보냈고 둘 다 읽지 못하면 비어 있다 |
| `reasoning_effort` | VARCHAR(16) NULL | 이 실행에 요청한 effort. 기본값으로 보냈으면 비어 있다. 이 칸이 생기기 전의 실행도 비어 있다 |
| `status` | VARCHAR(20) | `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED` |
| `error_code` | VARCHAR(64) NULL | |
| `input_tokens`, `cached_input_tokens`, `output_tokens`, `total_tokens` | BIGINT NULL | provider 가 알려준 것만 채운다 |
| `context_chars` | BIGINT NULL | 이 실행의 공통 답변 지침과 Memory 문맥의 글자 수. Memory가 없어도 공통 지침은 센다. 뒤에 붙는 묻는 형식 안내와 다시 생성 지시는 세지 않는다 |
| `runtime_fingerprint` | VARCHAR(64) NULL | 실행 당시 Hermes 의 고정 프롬프트 구성을 가리키는 지문. 그 값을 주는 HTTP 경로가 아직 없어 지금은 항상 비어 있고, 그동안 사용량 화면의 지문 축은 빈 목록을 돌려준다 |
| `instructions_hash` | VARCHAR(64) NULL | 공통 답변 지침과 Memory 문맥의 SHA-256 앞 16바이트를 16진수로 적은 값. 본문은 개인 Memory를 담을 수 있어 저장하지 않는다. 뒤에 붙는 묻는 형식 안내와 다시 생성 지시는 제외한다. Memory가 없어도 공통 지침의 지문을 기록한다 |
| `latency_ms` | BIGINT NULL | 끝나지 않은 실행은 비어 있다 |
| `estimated_cost_micros` | BIGINT NULL | 공개 API 가격으로 환산한 금액. 통화 단위의 100만분의 1 |
| `actual_cost_micros` | BIGINT NULL | 실제로 청구되는 금액. 구독 경로는 비어 있다 |
| `cost_currency` | CHAR(3) NULL | |
| `pricing_version` | VARCHAR(32) NULL | 이 금액을 계산한 가격표 |
| `started_at` | DATETIME(6) | |
| `finished_at` | DATETIME(6) NULL | 끝나지 않은 실행은 비어 있다 |

토큰 수는 실행 한 번의 **합계**다.
실행 안에서 LLM 호출이 여러 번 일어나고 그 내역은 오지 않는다.

자식 실행의 토큰은 부모의 합계에 들어 있지 않다.
Hermes Agent v0.21.0 배포본으로 측정했고 근거는
[ADR-016](adr/ADR-016-다중-에이전트-조율은-control-plane이-맡는다.md)의 「자식 토큰 실측」 절에 있다.
그래서 부모와 자식을 더한 합계는 실행 나무의 줄을 더해서 만든다.
어느 줄도 두 번 세지 않는다.

`CANCELLED` 는 중지한 turn 과 `agent_stop` 으로 멈춘 위임 실행에 쓴다.

`hermes_session_id` 와 `status` 에 함께 색인을 둔다. 최상위 session 의 MCP 호출과 최상위 자식의 등록마다 서명한 뿌리 session 으로 도는 실행을 찾기 때문이다.
그 session 을 가진 도는 실행이 둘 이상이면 어느 쪽도 부모로 쓰지 않고 거절한다. 대화 하나에는 도는 turn 이 하나뿐이라 보통 생기지 않는다.

금액을 0 으로 채우지 않는다.
0 은 공짜라는 뜻으로 읽히기 때문이다.
환산액과 실제 청구액을 나눈 근거는
[ADR-014](adr/ADR-014-실제-청구액과-환산액을-나눠-적는다.md)에 있다.

### 끝나지 않은 실행

`RUNNING` 인 줄은 사용량 목록에는 「도는 중」으로 보인다.
끝나지 않아 소요 시간과 금액은 비워 둔다.
월 비용 합계에서는 뺀다.
빼지 않으면 「가격을 찾지 못한 실행」 으로 세어져,
아직 안 끝난 것과 가격을 모르는 것이 한 숫자에 섞인다.

기동할 때 `RUNNING` 으로 남아 있는 줄은 `FAILED` 로 바꾸고
`error_code` 를 `ORPHANED` 로 적는다.
이 Control Plane 은 한 대만 도므로 기동 시점에 돌고 있는 실행이 없다.
이렇게 끝난 줄은 사용량 목록에서 「중간에 끊김」으로 보인다.

## memory

에이전트가 실행할 때 `instructions` 로 받는 사실 하나가 한 줄이다.
긴 글 하나가 아니라 사실 하나가 한 행이다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `scope` | VARCHAR(20) | `USER` 또는 `GROUP`. 기본값이 없다 |
| `owner_user_id` | BIGINT NULL | `USER` 일 때 필요하다. 그 사람만 본다 |
| `group_id` | BIGINT NULL | `GROUP` 일 때 필요하다 |
| `title` | VARCHAR(200) | 색인에 실을 제목 한 줄 |
| `content` | TEXT | 사실 한 줄 |
| `always_inject` | BOOLEAN | 본문을 매 실행에 실을지 정한다. 기본값은 `FALSE` |
| `status` | VARCHAR(20) | `PROPOSED` 또는 `ACCEPTED` 또는 `REJECTED` |
| `proposed_by_execution_id` | BIGINT NULL | 이 항목을 제안한 실행. 사람이 직접 적었으면 비어 있다 |
| `proposal_dedup_key` | VARCHAR(64) NULL | 제안한 사용자·제목·본문의 해시. 직접 등록한 항목은 비어 있다 |
| `accepted_by_user_id` | BIGINT NULL | 누가 받아들였는가 |
| `accepted_at` | DATETIME(6) NULL | |
| `created_at`, `updated_at` | DATETIME(6) | |

**`ACCEPTED` 인 항목만 주입한다.**
`PROPOSED` 는 사람이 아직 보지 않은 것이고, 에이전트가 그것을 사실로 쓰면 안 된다.

주입할 때 요청자의 `USER` 항목과 요청자가 속한 그룹의 `GROUP` 항목만 고른다.
다른 사용자의 `USER` 항목은 고르는 단계에서 빠지므로 Hermes 로 나가는 문자열에 들어가지 않는다.
`proposal_dedup_key`에는 유일 제약이 있어 같은 제안이 동시에 들어와도 두 행이 생기지 않는다.
근거는 [ADR-003](adr/ADR-003-memory-권한은-주입으로-강제한다.md)과
[ADR-012](adr/ADR-012-memory-는-사람이-승인한-것만-남는다.md)에 있다.

## chat_attachment

대화에 올린 사진 한 장이 한 행이다. 본문은 파일로 두고 여기에는 그 사진을 가리키는 것만 둔다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | BIGINT | |
| `conversation_id` | BIGINT | 어느 대화에 올렸는가. 디렉터리 이름이기도 하다 |
| `message_id` | BIGINT NULL | 함께 보낸 메시지. 아직 보내지 않았으면 비어 있다 |
| `uploaded_by_user_id` | BIGINT | 올린 사람 |
| `original_name` | VARCHAR(255) | 올릴 때의 파일 이름. 화면이 보인다 |
| `stored_name` | VARCHAR(255) NULL | 디스크에 둔 이름. `{id}.{확장자}` 다. 번호를 받은 직후 같은 트랜잭션에서 채우므로 커밋된 행에는 언제나 있다 |
| `content_type` | VARCHAR(100) | |
| `byte_size` | BIGINT | |
| `expires_at` | DATETIME(6) | 이 시각이 지나면 파일을 지운다 |
| `deleted_at` | DATETIME(6) NULL | 파일을 실제로 지운 시각. 비어 있으면 아직 있다 |
| `created_at` | DATETIME(6) | |

**행을 지우지 않는다.** 파일을 지우고 `deleted_at` 만 적는다.
그래야 지난 대화를 열었을 때 그 자리에 사진이 있었다는 것이 남고,
화면이 「보관 기간이 지나 볼 수 없습니다」를 보일 수 있다.

`deleted_at` 이 비어 있는지가 볼 수 있는지를 정한다. `expires_at` 은 언제 지울지만 정한다.
둘로 판정하면 지우는 일이 늦었을 때 화면과 디스크가 어긋난다.

`message_id` 가 비어 있는 행은 올렸지만 보내지 않은 것이다.
그 행도 `expires_at` 이 지나면 함께 지운다.

한 사용자가 남의 대화의 첨부를 읽지 못한다.
`conversation.user_id` 가 그 경계를 갖고, 첨부는 그 대화를 통해서만 닿는다.

근거는 [ADR-020](adr/ADR-020-사진은-공유-디렉터리에-두고-에이전트가-파일로-읽는다.md) 에 있다.

## agent_token

Hermes 가 Control Plane 의 MCP 도구를 부를 때 쓰는 장기 토큰이다.
토큰 원문은 발급 응답에서 한 번만 내고 데이터베이스에는 SHA-256 해시만 저장한다.

**토큰은 어느 profile 이 부르는지만 증명한다. 사용자를 정하지 않는다.**
요청자는 서명한 `_fos_ctx` 로 찾은 origin 실행의 `user_id` 다.
근거는 [ADR-032](adr/ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md) 에 있다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `profile_name` | VARCHAR(64) NULL | 이 토큰을 쓰는 Hermes profile. 폐기되지 않은 토큰은 늘 채워져 있다. 비어 있는 줄은 profile 을 묶기 전에 폐기된 옛 토큰뿐이고, 인증에서 거절한다 |
| `token_hash` | VARCHAR(64) | 토큰 원문의 SHA-256 해시. 원문은 저장하지 않는다 |
| `label` | VARCHAR(100) | 관리자가 토큰 용도를 구분하는 이름 |
| `created_at` | DATETIME(6) | 발급 시각 |
| `last_used_at` | DATETIME(6) NULL | 마지막 MCP 요청 시각 |
| `revoked_at` | DATETIME(6) NULL | 폐기 시각. 행은 삭제하지 않는다 |

한 profile 에 토큰이 여럿일 수 있다. 바꿔 끼우는 동안 옛 토큰과 새 토큰이 함께 쓰인다. 그래서 `profile_name` 에 유일 제약을 두지 않는다.
토큰 하나는 profile 하나에만 묶인다. 한 번 묶은 profile 은 바꾸지 않는다. 다른 profile 에 쓰려면 새로 발급한다.

**토큰에는 사용자 칸이 없다.** 전에는 사용자 기준으로 발급한 옛 토큰이 `user_id` 를 가졌다.
V29 가 `profile_name` 을 더했고, 운영의 옛 토큰을 모두 profile 에 묶은 뒤 V35 가 `user_id` 칸을 지웠다.
V35 는 칸을 지우기 전에 `profile_name` 이 빈 폐기 안 된 줄을 센다. 한 줄이라도 있으면 아무것도 바꾸지 않고 실패한다.
그 토큰이 누구의 것이었는지 모르는 채로 인증만 막히는 일을 배포 전에 드러내기 위해서다.
폐기된 옛 토큰은 이력으로 남기므로 `profile_name` 은 NULL 을 그대로 받는다.

## hermes_session_binding

Hermes `delegate_task` 가 만든 하위 에이전트 session 이 어느 FOS 실행에서 시작됐는지 적는다.
profile 플러그인이 `subagent_start` hook 에서 등록한다. 근거는 [ADR-037](adr/ADR-037-hermes-하위-에이전트-session-의-주인은-만들-때-등록한-줄로-정한다.md) 에 있다.

**한 번 적은 줄은 바꾸지 않는다.** 같은 대화의 다음 turn 이 시작돼도 그 하위 에이전트는 처음 origin 실행에 속한다.
대화 session 은 여러 turn 이 이어 쓰므로 여기 적지 않는다. 최상위 session 은 `agent_execution.hermes_session_id` 로 찾는다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | BIGINT | |
| `profile_name` | VARCHAR(64) | 등록한 토큰이 증명한 profile |
| `session_id` | VARCHAR(128) | 하위 에이전트 session. Hermes 가 정한 값이다 |
| `user_id` | BIGINT | origin 실행의 `user_id`. MCP 호출의 요청자다 |
| `origin_execution_id` | BIGINT | 이 session 을 낳은 FOS 실행. 끝난 실행이어도 된다. 이 실행이나 그 뿌리 실행이 `CANCELLED` 면 MCP 호출을 거절한다 |
| `root_session_id` | VARCHAR(128) | 서명한 `parent_root_session_id`. MCP 호출의 서명한 뿌리와 다르면 거절한다 |
| `parent_session_id` | VARCHAR(128) | 이 session 을 만든 session. 최상위 session 이거나 다른 하위 에이전트 session 이다 |
| `created_at` | DATETIME(6) | 등록 시각 |

| 제약 | 까닭 |
| --- | --- |
| `UNIQUE (profile_name, session_id)` | session id 가 profile 사이에서 유일하다고 보장하지 않는다. 한 profile 안에서 한 session 은 한 origin 에만 속한다 |

외래 키는 두지 않는다. `agent_execution` 도 사용자와 대화에 외래 키를 두지 않고, 실행 줄과 사용자는 지우지 않는다. 등록은 서버가 방금 읽은 실행에서 origin 과 사용자를 옮겨 적으므로 없는 실행을 가리키지 않는다.

같은 `(profile_name, session_id)` 가 같은 부모와 뿌리로, 또는 같은 origin 으로 다시 오면 새 줄을 만들지 않고 성공으로 답한다.
다른 origin 이면 거절하고 덮어쓰지 않는다. 동시에 두 요청이 와서 유일 제약에 걸리면 먼저 저장된 줄을 다시 읽어 같은 규칙으로 판정한다.

하위 에이전트의 하위 에이전트는 부모 등록의 `origin_execution_id` 와 `user_id` 를 그대로 잇는다. 하위 에이전트 몫의 `agent_execution` 줄은 만들지 않는다. 하위 에이전트는 지금처럼 `execution_event` 의 `SUBAGENT_STARTED`, `SUBAGENT_COMPLETED` 로 보인다.

Control Plane 이 다시 뜨면 도는 실행은 `ORPHANED` 로 끝나지만 등록 줄은 그대로다. 이미 등록한 하위 에이전트는 계속 요청자를 찾는다. 다시 뜬 뒤 그 부모 run 이 새로 만든 최상위 자식은 도는 부모가 없어 등록되지 않는다.

## execution_event

실행 하나가 도는 동안 일어난 일을 우리 이름으로 옮겨 적은 것이다.
Hermes 가 보낸 원래 payload 를 통째로 넣지 않는다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `execution_id` | BIGINT | 어느 실행의 사건인가 |
| `sequence` | INT | 그 실행 안에서의 순서. 1부터 센다 |
| `event_type` | VARCHAR(40) | 아래 표의 값 중 하나 |
| `tool_name` | VARCHAR(128) NULL | 도구 사건일 때 채운다 |
| `subagent_name` | VARCHAR(128) NULL | 하위 에이전트 사건일 때 채운다 |
| `hermes_session_id` | VARCHAR(128) NULL | 하위 에이전트가 따로 session 을 가지면 적는다 |
| `duration_ms` | BIGINT NULL | 끝난 사건에만 있다 |
| `failed` | BOOLEAN NULL | 완료 사건의 실패 여부. Hermes 가 알려주지 않으면 비운다 |
| `detail` | VARCHAR(500) NULL | 화면에 한 줄로 보일 만큼만. 하위 에이전트 사건이면 그 목표. 도구 사건의 값은 모두 저장하되 응답에는 ADR-038 이 정한 사람에게만 싣는다 |
| `model` | VARCHAR(128) NULL | 하위 에이전트가 돈 모델. 하위 에이전트 사건에만 있다 |
| `input_tokens`, `output_tokens` | BIGINT NULL | 하위 에이전트가 쓴 토큰. `SUBAGENT_COMPLETED` 에만 있다 |
| `occurred_at` | DATETIME(6) | |

`execution_id` 와 `sequence` 를 함께 유일하게 둔다.

**`subagent_name` 은 Hermes 가 이름을 보낼 때만 채운다.**
이름이 없는 사건의 `preview` 에서 이름처럼 보이는 글자를 뽑아 채우지 않는다.
그것이 실제 이름인지 우리가 만든 것인지 구분할 수 없기 때문이다.

**`hermes_session_id` 와 `model` 과 토큰은 Hermes 가 실어 보낼 때만 채운다.**
[`hermes/delegation.md`](hermes/delegation.md) 의 「자식 토큰을 SSE 로 받을 수 있다」 절이
`subagent.start` 와 `subagent.complete` 에 오는 칸을 적는다.
`child_session_id` 를 `hermes_session_id` 에, `goal` 을 `detail` 에 옮긴다.
싣지 않는 버전에서는 이 칸들이 비고 `detail` 에 `preview` 가 들어간다.

이 토큰은 화면이 하위 에이전트가 무엇을 썼는지 보이는 데만 쓴다.
사용량 합계에 더하지 않는다. 합계는 여전히 `agent_execution` 한 줄씩의 값이다.

| `event_type` | 언제 |
| --- | --- |
| `RUN_STARTED` | 실행이 시작됐다 |
| `RUN_COMPLETED` | 실행이 끝났다 |
| `RUN_FAILED` | 실행이 실패했다 |
| `RUN_CANCELLED` | 사용자가 중지했다 |
| `TOOL_STARTED` | 도구를 부르기 시작했다 |
| `TOOL_COMPLETED` | 도구 호출이 끝났다 |
| `SUBAGENT_STARTED` | 하위 에이전트가 시작됐다 |
| `SUBAGENT_COMPLETED` | 하위 에이전트가 끝났다 |

우리가 모르는 사건은 저장하지 않고 버린다. 버렸다는 사실만 로그로 남긴다.
근거는 [ADR-013](adr/ADR-013-실행-사건은-우리-모델로-정규화해-저장한다.md)에 있다.

## execution_skill_use

실행 하나에서 스킬 하나가 쓰인 것이 한 행이다. 스킬 호출 이력의 원천이다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | BIGINT | |
| `execution_id` | BIGINT | 어느 실행에서 썼나 |
| `skill_name` | VARCHAR(64) | 스킬 이름 |
| `source` | VARCHAR(20) | `COMMAND` 는 사용자가 `/이름` 으로 불렀다. `MODEL` 은 모델이 스스로 `skill_view` 로 읽었다 |
| `occurred_at` | DATETIME(6) | |

`(execution_id, skill_name, source)` 에 유일 제약이 있다. 한 실행에서 모델이 같은 스킬을 여러 번 읽어도 한 행이다.
호출 횟수는 실행 수로 센다. 같은 실행에 `COMMAND` 와 `MODEL` 이 함께 있어도 1회다.
사용자, 에이전트, 대화는 `agent_execution` 과 이어 얻는다. 같은 값을 여기 다시 적지 않는다.

**스킬을 지워도 행은 남는다.** 이름으로 남아 지난 호출을 읽을 수 있다.
`MODEL` 행은 실행 사건에 스킬 이름이 실려 올 때만 생긴다.

스킬 본문과 참고 파일은 이 데이터베이스에 없다. Hermes 가 읽는 공유 디렉터리에만 있다.
누가 이 이력을 어디까지 보는지와 근거는 [ADR-034](adr/ADR-034-올린-스킬은-control-plane-이-버전-디렉터리에-쓰고-hermes-는-읽기만-한다.md) 에 있다.

## chat_artifact

에이전트가 turn 안에 대화의 결과물 폴더에 만들거나 고친 HTML 파일 하나가 한 행이다.
본문은 파일로 두고 여기에는 그 파일을 가리키는 것만 둔다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | BIGINT | |
| `conversation_id` | BIGINT | 어느 대화의 폴더인가. 폴더 이름이기도 하다 |
| `message_id` | BIGINT | 이 파일을 만든 turn 의 답 메시지 |
| `path` | VARCHAR(500) | 대화 폴더 안의 상대 경로. `/` 로 나눈다 |
| `byte_size` | BIGINT | 찾았을 때의 크기 |
| `created_at` | DATETIME(6) | |
| `deleted_at` | DATETIME(6) NULL | 보관 기간이 지나 파일을 지운 시각 |

`(message_id, path)` 에 유일 제약이 있다. 같은 파일을 다음 turn 이 다시 고치면 그 turn 의 답에 새 행이 생긴다.
화면은 답마다 그 답의 행을 보인다. 파일은 하나이므로 옛 답에서 열어도 지금 내용이 보인다.

**행을 지우지 않는다.** 첨부와 같다. 파일이 지워지면 `deleted_at` 을 적어 화면이 「보관 기간이 지나 볼 수 없습니다」를 보인다.
근거는 [ADR-027](adr/ADR-027-에이전트가-만든-html-은-대화별-폴더에-두고-스크립트-없이-보인다.md) 에 있다.

## 지울 때

에이전트 행은 지우지 않는다. 사용자가 에이전트를 지우면 `deleted_at` 을 적고 `enabled` 를 내린다.
`profile_managed` 가 참이면 그 Hermes profile 과 올린 스킬 디렉터리를 지우고 그 profile 의 MCP 토큰을 폐기한다.
거짓이면 운영에서 만든 profile 이라 profile 은 남긴다.
대화와 실행 기록과 스킬 호출 이력은 남는다.

대화도 지우지 않는다. 사용자가 지우면 `conversation.deleted_at` 을 적고 목록에서 숨긴다.
메시지와 실행 기록과 Hermes session 은 그대로 둔다.
사용량 화면은 지운 대화의 실행도 센다. 돈은 이미 나갔다.
실행 기록이 에이전트와 대화를 가리키고 있고, 기록은 남아야 한다.

사용자를 지우는 흐름은 아직 없다.

페르소나는 이 데이터베이스에 없다. 본문은 그 profile 의 `SOUL.md` 가 갖는다.
근거는 [ADR-019](adr/ADR-019-페르소나는-hermes-가-갖고-control-plane-은-화면만-준다.md) 에 있다.

첨부도 행을 지우지 않는다. 파일만 지우고 `deleted_at` 을 적는다.
결과물(`chat_artifact`)도 같다.
하위 에이전트 session 등록(`hermes_session_binding`)도 지우지 않는다. 실행 기록과 함께 남는다.
그 자리에 사진이 있었다는 것이 남아야 지난 대화를 읽을 수 있다.

허용 목록에서 빼는 것도 지우지 않고 `enabled` 를 내린다.
그 사람의 `app_user` 와 실행 기록은 그대로 둔다.
그 사람의 Hermes profile 도 지우지 않는다. 다시 들일 때 그것을 다시 만들지 않아도 된다.
