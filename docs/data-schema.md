# 저장 모델

MySQL 8.4 에 둔다.
마이그레이션은 `backend/src/main/resources/db/migration/` 이 소유하고 이 문서는 뜻을 적는다.

비밀값은 어느 표에도 넣지 않는다.
AI credential 은 Hermes profile 의 `.env` 에, profile 의 API server key 는 홈서버의 파일에 있다.

## app_user

가족 구성원이다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | BIGINT | |
| `email` | VARCHAR(320) | 유일하다. 로그인한 Google 계정 |
| `display_name` | VARCHAR(100) | 화면에 보일 이름 |
| `family_id` | BIGINT | 지금은 한 가구뿐이다 |
| `role` | VARCHAR(20) | `ADMIN` 또는 `MEMBER`. 첫 사용자가 `ADMIN` 이 된다 |

## agent

사용자가 대화를 시작할 때 고르는 실행 단위다.
에이전트 하나가 Hermes profile 하나를 가리키고, 공개 범위가 누가 쓸 수 있는지 정한다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `code` | VARCHAR(64) | 유일하다. 요청과 화면에서 에이전트를 가리킨다 |
| `name` | VARCHAR(100) | 화면에 보일 이름 |
| `hermes_profile` | VARCHAR(64) | 유일하다. 호스트의 key 파일 이름과 같다 |
| `api_base_url` | VARCHAR(255) | 그 profile 의 API server 주소. `/v1` 앞까지 |
| `provider` | VARCHAR(64) | 실행이 올라타는 credential 의 이름 |
| `model` | VARCHAR(128) | Hermes 에서 마지막으로 읽은 모델 이름 |
| `model_synced_at` | DATETIME(6) NULL | Hermes 에서 모델을 읽은 시각 |
| `cost_mode` | VARCHAR(20) | `SUBSCRIPTION` 또는 `API` |
| `credential_scope` | VARCHAR(20) | `SHARED_HOUSEHOLD` 또는 `DEDICATED` |
| `visibility` | VARCHAR(20) | `PRIVATE` 또는 `FAMILY`. 기본값이 없다 |
| `owner_user_id` | BIGINT NULL | `PRIVATE` 일 때 필요하다 |
| `enabled` | BOOLEAN | 거짓이면 새 실행을 막는다 |

`model` 은 등록할 때와 관리자가 동기화를 요청할 때 Hermes 에서 읽는다.
읽지 못하면 마지막 값을 유지해 기존 실행과 비용 기록을 계속 해석할 수 있게 한다.
공개 범위가 접근 권한을 정하는 이유는
[ADR-007](adr/ADR-007-에이전트가-모델과-도구를-함께-정한다.md)에 있다.

## workspace

작업 영역이다.
안내문의 정본은 다른 저장소에 있고 여기서는 읽기만 한다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `code` | VARCHAR(64) | 유일하다. 요청이 영역을 가리키는 이름 |
| `source_path` | VARCHAR(255) | 마운트 루트 아래의 디렉터리 이름. 절대 경로가 아니다 |
| `visibility` | VARCHAR(20) | `PRIVATE` 또는 `FAMILY`. 기본값이 없다 |
| `owner_user_id` | BIGINT NULL | `PRIVATE` 일 때 필요하다 |
| `enabled` | BOOLEAN | |

공개 범위에 기본값을 두지 않는 이유는 [ADR-006](adr/ADR-006-작업-영역은-읽기-전용-의존이고-공개-범위는-기본값이-없다.md)에 있다.

## conversation

주고받는 하나의 스레드다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `user_id` | BIGINT | 이 대화의 주인. 다른 사용자는 읽지 못한다 |
| `workspace_id` | BIGINT NULL | 첫 메시지가 정한다. 뒤에 바뀌지 않는다 |
| `agent_id` | BIGINT | 첫 메시지가 정한다. 뒤에 바뀌지 않는다 |
| `hermes_session_id` | VARCHAR(128) NULL | 첫 실행이 돌려준 session. 특정 profile 안의 값이다 |
| `title` | VARCHAR(200) | 첫 메시지의 앞부분 |
| `updated_at` | DATETIME(6) | 목록 정렬에 쓴다 |

`hermes_session_id` 가 특정 profile 안의 값이라, 대화의 영역과 에이전트는 중간에 바뀌지 않는다.

## chat_message

대화 안의 한 줄이다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `conversation_id` | BIGINT | |
| `role` | VARCHAR(20) | `USER` 또는 `ASSISTANT` |
| `content` | LONGTEXT | |
| `sender_user_id` | BIGINT NULL | 이 줄을 쓴 사람. `ASSISTANT` 는 비어 있다 |
| `execution_id` | BIGINT NULL | 이 답을 만든 실행. `USER` 는 비어 있다 |

`content` 를 `LONGTEXT` 로 못 박는다.
길이를 주지 않은 `@Lob` 문자열을 Hibernate 가 MySQL 에서 `tinytext` 로 기대해 기동이 실패한다.

`sender_user_id` 는 화면이 보낸 사람 이름을 보이기 위한 것이다.
대화는 여전히 주인 한 사람의 것이고, 여러 사람이 같은 대화를 읽고 쓰는 것은 아직 만들지 않았다.

## agent_execution

에이전트가 한 번 답한 기록이다.
대화 하나에 여러 줄이 달리고, 한 줄이 곧 메시지 하나다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `user_id` | BIGINT | 누가 물었는가 |
| `conversation_id` | BIGINT | |
| `workspace_id` | BIGINT NULL | 어느 영역의 작업이었는가 |
| `agent_id` | BIGINT | 어느 에이전트의 실행이었는가 |
| `profile_name` | VARCHAR(64) | |
| `hermes_run_id` | VARCHAR(128) NULL | |
| `provider`, `model` | VARCHAR | 실행이 돌려준 것이 profile 이름이면 에이전트의 값을 쓴다 |
| `status` | VARCHAR(20) | `SUCCEEDED` 또는 `FAILED` |
| `error_code` | VARCHAR(64) NULL | |
| `input_tokens`, `cached_input_tokens`, `output_tokens`, `total_tokens` | BIGINT NULL | provider 가 알려준 것만 채운다 |
| `latency_ms` | BIGINT | |
| `estimated_cost_micros` | BIGINT NULL | 통화 단위의 100만분의 1. 가격을 찾지 못하면 비어 있다 |
| `cost_currency` | CHAR(3) NULL | |
| `pricing_version` | VARCHAR(32) NULL | 이 금액을 계산한 가격표 |

토큰 수는 실행 한 번의 **합계**다.
실행 안에서 LLM 호출이 여러 번 일어나고 그 내역은 오지 않는다.

금액을 0 으로 채우지 않는다.
0 은 공짜라는 뜻으로 읽히기 때문이다.

## 지울 때

에이전트나 영역을 지우지 않는다.
`enabled` 를 내려 쓰지 않게 한다.
실행 기록이 그것을 가리키고 있고, 기록은 남아야 한다.

사용자를 지우는 흐름은 아직 없다.
