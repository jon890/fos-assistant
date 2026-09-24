# 저장 모델

MySQL 8.4 에 둔다.
마이그레이션은 `backend/src/main/resources/db/migration/` 이 소유하고 이 문서는 뜻을 적는다.

비밀값은 어느 표에도 넣지 않는다.
AI credential 은 Hermes profile 의 `.env` 에, profile 의 API server key 는 홈서버의 파일에 있다.

## app_user

사용자이다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | BIGINT | |
| `email` | VARCHAR(320) | 유일하다. 로그인한 Google 계정 |
| `display_name` | VARCHAR(100) | 화면에 보일 이름 |
| `family_id` | BIGINT | 지금은 한 가구뿐이다 |
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
| `provider` | VARCHAR(64) | 실행이 올라타는 credential 의 이름 |
| `model` | VARCHAR(128) | Hermes 에서 마지막으로 읽은 모델 이름 |
| `model_synced_at` | DATETIME(6) NULL | Hermes 에서 모델을 읽은 시각 |
| `cost_mode` | VARCHAR(20) | `SUBSCRIPTION` 또는 `API` |
| `credential_scope` | VARCHAR(20) | `SHARED_HOUSEHOLD` 또는 `DEDICATED` |
| `visibility` | VARCHAR(20) | `PRIVATE` 또는 `FAMILY`. 기본값이 없다 |
| `owner_user_id` | BIGINT NULL | `PRIVATE` 일 때 필요하다 |
| `enabled` | BOOLEAN | 거짓이면 새 실행을 막는다 |
| `tagline` | VARCHAR(200) NULL | 새 대화 화면에 보일 한 줄 소개 |

`model` 은 등록할 때와 관리자가 동기화를 요청할 때 Hermes 에서 읽는다.
읽지 못하면 마지막 값을 유지해 기존 실행과 비용 기록을 계속 해석할 수 있게 한다.
공개 범위가 접근 권한을 정하는 이유는
[ADR-007](adr/ADR-007-에이전트가-모델과-도구를-함께-정한다.md)에 있다.

## conversation

주고받는 하나의 스레드다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `user_id` | BIGINT | 이 대화의 주인. 다른 사용자는 읽지 못한다 |
| `agent_id` | BIGINT | 대화를 만들 때 정한다. 뒤에 바뀌지 않는다 |
| `hermes_session_id` | VARCHAR(128) NULL | 첫 실행이 돌려준 session. 특정 profile 안의 값이다 |
| `title` | VARCHAR(200) | 첫 메시지의 앞부분. 사진을 먼저 올리려고 만든 대화는 첫 메시지 전까지 비어 있다 |
| `updated_at` | DATETIME(6) | 목록 정렬에 쓴다 |
| `deleted_at` | DATETIME(6) NULL | 사용자가 지운 시각. 채워지면 목록과 조회와 보내기에서 없는 대화와 같다 |

`hermes_session_id` 가 특정 profile 안의 값이라, 대화의 에이전트는 중간에 바뀌지 않는다.

`title` 은 사용자가 고칠 수 있다. 앞뒤 공백을 떼고 1자에서 200자까지 받는다.

작업 영역(workspace)은 제거됐다.
근거는 [ADR-010](adr/ADR-010-작업-영역을-제거하고-에이전트가-그-자리를-갖는다.md)에 있다.

## chat_message

대화 안의 한 줄이다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `conversation_id` | BIGINT | |
| `role` | VARCHAR(20) | `USER` 또는 `ASSISTANT` |
| `content` | LONGTEXT | |
| `sender_user_id` | BIGINT NULL | 이 줄을 쓴 사람. `ASSISTANT` 는 비어 있다 |
| `execution_id` | BIGINT NULL | 이 답을 만든 실행. `USER` 는 비어 있다 |
| `replaces_message_id` | BIGINT NULL | 이 메시지가 새 판으로 대신하는 이전 메시지. 같은 대화, 같은 `role` 이다 |

`content` 를 `LONGTEXT` 로 못 박는다.
길이를 주지 않은 `@Lob` 문자열을 Hibernate 가 MySQL 에서 `tinytext` 로 기대해 기동이 실패한다.

`replaces_message_id` 는 다시 생성과 수정이 채운다.
다시 생성한 답은 이전 답을, 고친 사용자 메시지는 고치기 전 메시지를 가리킨다.
고친 메시지 뒤에 온 답은 새 turn 의 첫 답이라 비어 있다.
**이전 판을 지우지 않는다.** 화면이 넘겨 볼 수 있어야 하고, 그 판을 만든 실행이 그것을 가리킨다.
근거는 [ADR-022](adr/ADR-022-다시-생성과-수정은-같은-session-에-판으로-쌓는다.md) 에 있다.

중지한 실행의 답도 한 줄로 남는다.
근거는 [ADR-021](adr/ADR-021-중지한-답은-멈춘-자리까지-남긴다.md) 에 있다.

`sender_user_id` 는 화면이 보낸 사람 이름을 보이기 위한 것이다.
대화는 여전히 주인 한 사람의 것이고, 여러 사람이 같은 대화를 읽고 쓰는 것은 아직 만들지 않았다.

## agent_execution

에이전트가 한 번 답한 기록이다.
대화 하나에 여러 줄이 달리고, 한 줄이 곧 메시지 하나다.

**이 줄은 실행이 끝난 뒤가 아니라 시작할 때 만들어진다.**
근거는 [ADR-011](adr/ADR-011-실행은-시작할-때-기록하고-끝날-때-갱신한다.md)에 있다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `user_id` | BIGINT | 누가 물었는가 |
| `conversation_id` | BIGINT | |
| `agent_id` | BIGINT | 어느 에이전트의 실행이었는가 |
| `parent_execution_id` | BIGINT NULL | 이 실행을 부른 실행. 사용자가 부른 것이면 비어 있다 |
| `root_execution_id` | BIGINT NULL | 이 실행이 속한 나무의 뿌리. 뿌리 자신은 비어 있다 |
| `profile_name` | VARCHAR(64) | |
| `hermes_run_id` | VARCHAR(128) NULL | 실행을 제출한 직후에 적는다 |
| `provider`, `model` | VARCHAR | 실행이 돌려준 것이 profile 이름이면 에이전트의 값을 쓴다 |
| `status` | VARCHAR(20) | `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED` |
| `error_code` | VARCHAR(64) NULL | |
| `input_tokens`, `cached_input_tokens`, `output_tokens`, `total_tokens` | BIGINT NULL | provider 가 알려준 것만 채운다 |
| `context_chars` | BIGINT NULL | 이 실행의 `instructions` 로 넣은 글자 수 |
| `runtime_fingerprint` | VARCHAR(64) NULL | 실행 당시 Hermes 의 고정 프롬프트 구성을 가리키는 지문. 그 값을 주는 HTTP 경로가 아직 없어 지금은 항상 비어 있고, 그동안 사용량 화면의 지문 축은 빈 목록을 돌려준다 |
| `instructions_hash` | VARCHAR(64) NULL | `instructions` 의 SHA-256 앞 16바이트를 16진수로 적은 값. 본문은 개인 Memory 를 담고 있어 저장하지 않는다. 넣은 문맥이 없으면 비어 있다 |
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

`CANCELLED` 는 상태값으로만 둔다. 지금은 실행을 취소하는 경로가 없다.

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
| `scope` | VARCHAR(20) | `USER` 또는 `FAMILY`. 기본값이 없다 |
| `owner_user_id` | BIGINT NULL | `USER` 일 때 필요하다. 그 사람만 본다 |
| `family_id` | BIGINT NULL | `FAMILY` 일 때 필요하다 |
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

주입할 때 요청자의 `USER` 항목과 그 가족의 `FAMILY` 항목만 고른다.
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

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `user_id` | BIGINT | 이 토큰이 정하는 사용자 |
| `token_hash` | VARCHAR(64) | 토큰 원문의 SHA-256 해시. 원문은 저장하지 않는다 |
| `label` | VARCHAR(100) | 관리자가 토큰 용도를 구분하는 이름 |
| `created_at` | DATETIME(6) | 발급 시각 |
| `last_used_at` | DATETIME(6) NULL | 마지막 MCP 요청 시각 |
| `revoked_at` | DATETIME(6) NULL | 폐기 시각. 행은 삭제하지 않는다 |

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
| `detail` | VARCHAR(500) NULL | 화면에 한 줄로 보일 만큼만. 하위 에이전트 사건이면 그 목표 |
| `model` | VARCHAR(128) NULL | 하위 에이전트가 돈 모델. 하위 에이전트 사건에만 있다 |
| `input_tokens`, `output_tokens` | BIGINT NULL | 하위 에이전트가 쓴 토큰. `SUBAGENT_COMPLETED` 에만 있다 |
| `occurred_at` | DATETIME(6) | |

`execution_id` 와 `sequence` 를 함께 유일하게 둔다.

**`subagent_name` 은 Hermes 가 이름을 보낼 때만 채운다.**
이름이 없는 사건의 `preview` 에서 이름처럼 보이는 글자를 뽑아 채우지 않는다.
그것이 실제 이름인지 우리가 만든 것인지 구분할 수 없기 때문이다.

**`hermes_session_id` 와 `model` 과 토큰은 Hermes 가 실어 보낼 때만 채운다.**
[`hermes-integration.md`](hermes-integration.md) 의 「자식 토큰을 SSE 로 받을 수 있다」 절이
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

## agent_starter_prompt

새 대화 화면에서 그 에이전트를 골랐을 때 보이는 추천 질문 한 줄이다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `agent_id` | BIGINT | 어느 에이전트의 것인가 |
| `position` | INT | 보이는 차례. 0부터 센다 |
| `text` | VARCHAR(300) | 누르면 그대로 보내는 글 |

`agent_id` 와 `position` 을 함께 유일하게 둔다. 한 에이전트에 넷까지다.
고칠 때는 그 에이전트의 줄을 모두 지우고 새로 넣는다. 차례를 바꾸는 것이 곧 전체를 다시 쓰는 것이다.

에이전트의 한 줄 소개는 `agent.tagline` 이 갖는다. `VARCHAR(200) NULL` 이다.
소개와 추천 질문을 고칠 수 있는 사람은 성격을 고칠 수 있는 사람과 같다.
그 규칙은 [`code-architecture.md`](code-architecture.md) 의 「누가 고칠 수 있나」 절이 갖는다.

## 지울 때

에이전트나 영역을 지우지 않는다.

대화도 지우지 않는다. 사용자가 지우면 `conversation.deleted_at` 을 적고 목록에서 숨긴다.
메시지와 실행 기록과 Hermes session 은 그대로 둔다.
사용량 화면은 지운 대화의 실행도 센다. 돈은 이미 나갔다.
`enabled` 를 내려 쓰지 않게 한다.
실행 기록이 그것을 가리키고 있고, 기록은 남아야 한다.

사용자를 지우는 흐름은 아직 없다.

페르소나는 이 데이터베이스에 없다. 본문은 그 profile 의 `SOUL.md` 가 갖는다.
근거는 [ADR-019](adr/ADR-019-페르소나는-hermes-가-갖고-control-plane-은-화면만-준다.md) 에 있다.

첨부도 행을 지우지 않는다. 파일만 지우고 `deleted_at` 을 적는다.
그 자리에 사진이 있었다는 것이 남아야 지난 대화를 읽을 수 있다.

허용 목록에서 빼는 것도 지우지 않고 `enabled` 를 내린다.
그 사람의 `app_user` 와 실행 기록은 그대로 둔다.
그 사람의 Hermes profile 도 지우지 않는다. 다시 들일 때 그것을 다시 만들지 않아도 된다.
