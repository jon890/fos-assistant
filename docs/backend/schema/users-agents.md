# 사용자와 에이전트

사용자와 로그인 허용 목록, 에이전트, 모델 단계와 숨김, MCP 토큰을 저장하는 표의 칸과 제약을 갖는다.

## app_user

사용자이다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | BIGINT | |
| `email` | VARCHAR(320) | 유일하다. 로그인한 Google 계정 |
| `display_name` | VARCHAR(100) | 화면에 보일 이름 |
| `group_id` | BIGINT | 사용자가 속한 그룹. 지금은 그룹이 하나뿐이다 |
| `role` | VARCHAR(20) | `ADMIN` 또는 `MEMBER`. 첫 사용자가 `ADMIN` 이 된다 |
| `model_default_tier` | VARCHAR(16) NULL | 사용자의 기본 모델 단계. 비어 있으면 그룹 기본값을 따른다 |

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
| `last_login_at` | DATETIME(6) NULL | 웹 로그인 성공 이벤트를 마지막으로 기록한 서버 시각. 판정과 일반 요청은 갱신하지 않는다. 기존 기록은 복원하지 않는다 |

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
| `api_base_url` | VARCHAR(255) | 그 profile 의 API server 주소. `/v1` 앞까지. 공유 listener 에서도 profile 마다 접두가 다르고 profile 마다 다른 노드를 가리킬 수 있어야 해서, 설정 `hermes.base-url` 하나가 아니라 에이전트마다 둔다 |
| `cost_mode` | VARCHAR(20) | `SUBSCRIPTION` 또는 `API` |
| `credential_scope` | VARCHAR(20) | `SHARED_HOUSEHOLD` 또는 `DEDICATED` |
| `visibility` | VARCHAR(20) | `PRIVATE` 또는 `GROUP`. 기본값이 없다 |
| `owner_user_id` | BIGINT NULL | 주인. 사용자가 만든 에이전트는 만든 사람이다. `PRIVATE` 일 때 필요하고, `GROUP` 으로 공개해도 지우지 않는다 |
| `enabled` | BOOLEAN | 거짓이면 새 실행을 막는다 |
| `profile_managed` | BOOLEAN | 참이면 Control Plane 이 이 에이전트의 profile 을 만들었다. 에이전트를 지울 때 profile 까지 지우는 것은 이 값이 참일 때뿐이다. 기본 거짓 |
| `deleted_at` | DATETIME(6) NULL | 지운 시각. 적히면 목록과 새 대화에서 빠지고 그 에이전트의 대화는 읽기만 된다 |
| `flow` | VARCHAR(64) NULL | 이 에이전트를 묶어 둔 다중 에이전트 흐름의 이름. 비어 있으면 Hermes 를 한 번 부른다 |
| `connector_managed` | BOOLEAN | 참이면 바인딩이 생기기 전에 커넥터를 연결할 때 만든 옛 커넥터 에이전트다. 지금은 새로 참이 되지 않고, 남은 에이전트는 사용자가 옮긴 뒤 지운다([ADR-083](../../adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md)). 기본 거짓 |
| `proactive_check_writes_allowed` | BOOLEAN NOT NULL DEFAULT FALSE | 「먼저 살펴보기에 쓰기 도구 허용」. 관리자만 바꾼다. 켜면 그 에이전트의 먼저 살펴보기가 쓰기 toolset 과 결과물 쓰기를 쓰고 커넥터 쓰기를 승인 카드로 보낸다([ADR-082](../../adr/ADR-082-먼저-살펴보기의-쓰기-도구는-관리자가-에이전트마다-켜고-커넥터-쓰기는-승인-카드로-보낸다.md)) |
| `connector_attachments` | BOOLEAN | 참이면 이 옛 커넥터 에이전트가 사진을 받는다. 커넥터가 선언한 toolset 이 실제로 켜진 것을 확인했을 때만 참이다. 기본 거짓 |
| `default_model_provider` | VARCHAR(64) NULL | 기본 provider. `default_model` 과 함께 채우거나 함께 비운다 |
| `default_model` | VARCHAR(128) NULL | 기본 모델. 대화가 모델을 고르지 않았을 때 Hermes 에 명시해 보낸다 |
| `default_reasoning_effort` | VARCHAR(16) NULL | 기본 effort. `none`, `low` 부터 `max` 까지다. 모델 없이 이 값만 둘 수 있다 |

**모델과 effort 는 대화가 고르고, 고르지 않으면 에이전트 기본 모델로 돈다.** 세 칸이 모두 비면 그 profile 의 값으로 돈다. 막힌 계정을 쉬게 하는 것은 Hermes 가 한다.
V52 가 세 칸을 더했다. 값은 관리자가 화면에서 정하고 마이그레이션은 넣지 않는다.
근거는 [ADR-054](../../../backend/docs/adr/ADR-054-에이전트-기본-모델과-모델-숨김은-control-plane-db-가-갖는다.md) 에 있다.
예전의 `provider`, `model`, `model_synced_at` 칸과 에이전트별 모델 목록 표 `agent_model_option`, 막힌 provider 를 기억하던 표 `provider_state` 는 V27 이 지웠다.
대화가 모델을 고르게 한 근거는 [ADR-030](../../../backend/docs/adr/ADR-030-모델과-effort-는-대화가-고르고-기본값은-hermes-profile-이-갖는다.md) 에 있다.
공개 범위가 접근 권한을 정하는 이유는
[ADR-007](../../../backend/docs/adr/ADR-007-에이전트가-모델과-도구를-함께-정한다.md)에 있다.

**주인과 공개 범위는 따로다.** 그룹에 공개해도 만든 사람이 계속 관리한다.
이 결정 전에 운영에서 등록한 `GROUP` 에이전트는 주인이 비어 있어 `ADMIN` 만 관리한다.
근거는 [ADR-033](../../../backend/docs/adr/ADR-033-사용자가-에이전트를-만들고-공개해도-만든-사람이-관리한다.md) 에 있다.

한 줄 소개 칸 `tagline` 과 추천 질문 표 `agent_starter_prompt` 는 없앴다.
추천 질문은 데이터베이스에 두지 않고 backend 메모리에만 둔다.
근거는 [ADR-036](../../../backend/docs/adr/ADR-036-추천-질문은-사용자의-대화-이력으로-모델이-만들고-메모리에만-둔다.md) 에 있다.

## model_tier_definition

그룹이 정한 모델 단계의 mapping 이다. 선택의 우선순위와 초기값은 [모델 단계와 실행 기록](../../model-tiers.md) 이 정한다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | BIGINT | |
| `group_id` | BIGINT | 그룹 번호 |
| `tier` | VARCHAR(16) | 단계 코드 |
| `provider` | VARCHAR(64) NULL | 비어 있으면 요청한 에이전트의 기본 provider 로 해석한다 |
| `model` | VARCHAR(128) NULL | 비어 있으면 그 단계는 에이전트 기본 모델로 돈다 |
| `reasoning_effort` | VARCHAR(16) NULL | |

`(group_id, tier)` 가 유일하다.

## model_tier_group_setting

그룹의 기본 모델 단계다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `group_id` | BIGINT | 기본 키 |
| `default_tier` | VARCHAR(16) NULL | 비어 있으면 그룹 기본 단계가 없다 |

## model_hidden

그룹이 숨긴 provider 와 모델이다. 숨긴 것만 적는다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | BIGINT | |
| `group_id` | BIGINT | 그룹 번호 |
| `provider` | VARCHAR(64) | |
| `model` | VARCHAR(128) | 빈 문자열이면 그 provider 전체를 숨긴 것이다. NULL 은 유일 제약이 겹침을 막지 못해 쓰지 않는다 |

`(group_id, provider, model)` 이 유일하다.

## toolset_hidden

그룹이 일반 에이전트 도구 화면에서 숨긴 toolset이다. 행이 없으면 모두 보인다.
활성 도구는 Hermes profile이 갖고 이 표는 활성 상태를 저장하거나 바꾸지 않는다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | BIGINT | 기본 키 |
| `group_id` | BIGINT | 그룹 번호 |
| `name` | VARCHAR(64) | 등급 표에 있는 toolset 이름 |

`(group_id, name)`이 유일하다. FK는 없다. 새 설치에서 숨김 행을 만들지 않는다.

## agent_toolset_request

에이전트 주인의 관리자 등급 도구 사용 요청과 결정 이력이다.
도구의 활성 여부는 Hermes가 갖고 이 표는 요청 결과를 기록한다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | BIGINT | 내부 기본 키 |
| `public_id` | BINARY(16) | API와 화면이 쓰는 UUID. 유일하다 |
| `group_id` | BIGINT | 요청 당시 그룹 |
| `agent_id` | BIGINT | 요청한 에이전트 |
| `requester_user_id` | BIGINT | 요청 당시 주인 |
| `toolset` | VARCHAR(64) | 요청한 ADMIN 등급 도구 |
| `status` | VARCHAR(20) | PENDING, APPROVED, REJECTED, CANCELLED, EXPIRED |
| `pending_slot` | INT NULL | PENDING일 때 1, 끝났으면 NULL |
| `requested_at` | TIMESTAMP(6) | 요청 시각 |
| `decided_at` | TIMESTAMP(6) NULL | 승인, 거절, 취소 또는 만료 시각 |
| `decided_by_user_id` | BIGINT NULL | 결정한 관리자. 요청자가 취소하면 NULL |
| `reason` | VARCHAR(200) NULL | 거절 사유 한 줄 또는 조건 변경으로 만료된 사유 |

`(group_id, agent_id, requester_user_id, toolset, pending_slot)`이 유일하다.
끝난 요청의 NULL 슬롯은 서로 겹쳐도 저장되므로 여러 번의 요청 이력이 남는다.
CHECK 제약은 상태와 슬롯을 함께 검사한다.
`agent_id`는 `agent.id`를, 요청자와 결정 관리자는 `app_user.id`를 FK로 가리킨다.
에이전트를 지워도 행과 요청 이력을 남긴다.

## agent_token

Hermes 가 Control Plane 의 MCP 도구를 부를 때 쓰는 장기 토큰이다.
토큰 원문은 발급 응답에서 한 번만 내고 데이터베이스에는 SHA-256 해시만 저장한다.

**토큰은 어느 profile 이 부르는지만 증명한다. 사용자를 정하지 않는다.**
요청자는 서명한 `_fos_ctx` 로 찾은 origin 실행의 `user_id` 다.
근거는 [ADR-032](../../../backend/docs/adr/ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md) 에 있다.

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

**토큰에는 사용자 칸이 없다.** 사용자 기준으로 발급하던 옛 토큰의 `user_id` 칸은 운영의 토큰을 모두 profile 에 묶은 뒤 V35 가 지웠다.
폐기된 옛 토큰은 이력으로 남기므로 `profile_name` 은 NULL 을 그대로 받는다.

## service_token

다른 서비스가 사용자의 Memory 문서를 읽을 때 쓰는 토큰이다. 근거는 [ADR-056](../../../backend/docs/adr/ADR-056-다른-서비스는-사용자에-묶인-서비스-토큰으로-문서를-읽기만-한다.md) 에 있다.
원문은 발급 응답에서 한 번만 내고 데이터베이스에는 SHA-256 해시만 저장한다.

**`agent_token` 과 달리 사용자 한 사람에 묶인다.** 그 사용자 본인만 발급하고 폐기한다. 관리자도 다른 사용자의 토큰을 발급하거나 폐기하지 못한다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `id` | BIGINT | 기본 키 |
| `user_id` | BIGINT | `app_user.id`. 이 토큰이 읽을 수 있는 문서의 주인이다 |
| `token_hash` | VARCHAR(64) | 토큰 원문의 SHA-256 해시. 원문은 저장하지 않는다. 유일하다 |
| `label` | VARCHAR(100) | 사용자가 토큰 용도를 구분하는 이름 |
| `created_at` | DATETIME(6) | 발급 시각 |
| `expires_at` | DATETIME(6) | 만료 시각. 늘 있다. 발급할 때 1일에서 365일 사이로 정하고 고치지 않는다 |
| `last_used_at` | DATETIME(6) NULL | 마지막으로 읽은 시각. 거절한 요청은 적지 않는다 |
| `revoked_at` | DATETIME(6) NULL | 폐기 시각. 행은 삭제하지 않는다 |

허용 목록에서 사용자를 끄면 그 사용자의 폐기되지 않은 토큰에 모두 `revoked_at` 을 적는다. 다시 켜도 지우지 않는다.
인증할 때도 주인이 지금 허용 목록에 켜져 있는지 본다. 폐기가 한 번 실패해도 끈 사용자의 토큰이 통하지 않게 하기 위해서다.

## service_token_collection

서비스 토큰이 받는 collection 하나가 한 줄이다. `agent_memory_collection` 과 같은 뜻이고 같은 세 조건으로 판정한다.

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `token_id` | BIGINT | `service_token.id`. `collection` 과 함께 기본 키다 |
| `collection` | VARCHAR(64) | 받는 collection 의 key |
| `allow_sensitive` | BOOLEAN | 참이면 이 collection 의 `SENSITIVE` 문서까지 읽는다. 기본값은 `FALSE` |
