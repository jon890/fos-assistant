# 사용자와 에이전트

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
| `default_model_provider` | VARCHAR(64) NULL | 기본 provider. `default_model` 과 함께 채우거나 함께 비운다 |
| `default_model` | VARCHAR(128) NULL | 기본 모델. 대화가 모델을 고르지 않았을 때 Hermes 에 명시해 보낸다 |
| `default_reasoning_effort` | VARCHAR(16) NULL | 기본 effort. 모델 없이 이 값만 둘 수 있다 |

**모델과 effort 는 대화가 고르고, 고르지 않으면 에이전트 기본 모델로 돈다.** 세 칸이 모두 비면 그 profile 의 값으로 돈다. 막힌 계정을 쉬게 하는 것은 Hermes 가 한다.
V50 이 세 칸을 더했다. 값은 관리자가 화면에서 정하고 마이그레이션은 넣지 않는다.
근거는 [ADR-054](../../adr/ADR-054-에이전트-기본-모델과-모델-숨김은-control-plane-db-가-갖는다.md) 에 있다.
예전의 `provider`, `model`, `model_synced_at` 칸과 에이전트별 모델 목록 표 `agent_model_option`, 막힌 provider 를 기억하던 표 `provider_state` 는 V27 이 지웠다.
대화가 모델을 고르게 한 근거는 [ADR-030](../../adr/ADR-030-모델과-effort-는-대화가-고르고-기본값은-hermes-profile-이-갖는다.md) 에 있다.
공개 범위가 접근 권한을 정하는 이유는
[ADR-007](../../adr/ADR-007-에이전트가-모델과-도구를-함께-정한다.md)에 있다.

**주인과 공개 범위는 따로다.** 그룹에 공개해도 만든 사람이 계속 관리한다.
이 결정 전에 운영에서 등록한 `GROUP` 에이전트는 주인이 비어 있어 `ADMIN` 만 관리한다.
근거는 [ADR-033](../../adr/ADR-033-사용자가-에이전트를-만들고-공개해도-만든-사람이-관리한다.md) 에 있다.

한 줄 소개 칸 `tagline` 과 추천 질문 표 `agent_starter_prompt` 는 없앴다.
추천 질문은 데이터베이스에 두지 않고 backend 메모리에만 둔다.
근거는 [ADR-036](../../adr/ADR-036-추천-질문은-사용자의-대화-이력으로-모델이-만들고-메모리에만-둔다.md) 에 있다.

## agent_token

Hermes 가 Control Plane 의 MCP 도구를 부를 때 쓰는 장기 토큰이다.
토큰 원문은 발급 응답에서 한 번만 내고 데이터베이스에는 SHA-256 해시만 저장한다.

**토큰은 어느 profile 이 부르는지만 증명한다. 사용자를 정하지 않는다.**
요청자는 서명한 `_fos_ctx` 로 찾은 origin 실행의 `user_id` 다.
근거는 [ADR-032](../../adr/ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md) 에 있다.

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
