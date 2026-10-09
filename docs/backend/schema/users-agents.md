# 사용자와 에이전트

사용자와 로그인 허용 목록, 에이전트, 모델 단계와 숨김, 도구 사용 요청, 토큰을 저장하는 표에서 코드만으로 알 수 없는 것을 적는다.
칸과 타입, 색인은 마이그레이션이, 상태 값은 엔티티의 enum 이 갖는다.

## app_user

실제로 들어온 적이 있는 사용자다.

- 그룹의 첫 사용자가 `ADMIN` 이 되고 그 뒤로는 `MEMBER` 다
- `model_default_tier` 가 비면 그룹 기본값을 따른다

## allowed_person

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

### 비어 있으면 아무도 들어오지 못한다

이 표가 로그인의 유일한 근거다.
**넣는 절차는 비공개 저장소가 소유한다.** 이 저장소에 주소를 적지 않는다.

## agent

사용자가 대화를 시작할 때 고르는 실행 단위다.
에이전트 하나가 Hermes profile 하나를 가리키고, 공개 범위가 누가 쓸 수 있는지 정한다.
공개 범위가 접근 권한을 정하는 까닭은 [ADR-007](../../../backend/docs/adr/ADR-007-에이전트가-모델과-도구를-함께-정한다.md) 에 있다.

- `hermes_profile` 은 호스트의 key 파일 이름과 같다
- `api_base_url` 은 그 profile 의 API server 주소이고 `/v1` 앞까지다. 공유 listener 에서도 profile 마다 접두가 다르고 profile 마다 다른 노드를 가리킬 수 있어야 해서, 설정 `hermes.base-url` 하나가 아니라 에이전트마다 둔다
- `visibility` 에는 기본값이 없다. 만들 때 늘 정한다
- `owner_user_id` 는 주인이다. `PRIVATE` 일 때 필요하고, `GROUP` 으로 공개해도 지우지 않는다
- `profile_managed` 가 참이면 Control Plane 이 이 에이전트의 profile 을 만들었다. 에이전트를 지울 때 profile 까지 지우는 것은 이 값이 참일 때뿐이다
- `deleted_at` 이 적히면 목록과 새 대화에서 빠지고 그 에이전트의 대화는 읽기만 된다
- `flow` 는 이 에이전트를 묶어 둔 다중 에이전트 흐름의 이름이다. 비어 있으면 Hermes 를 한 번 부른다
- `connector_managed` 가 참이면 바인딩이 생기기 전에 커넥터를 연결할 때 만든 옛 커넥터 에이전트다. 지금은 새로 참이 되지 않고, 남은 에이전트는 사용자가 옮긴 뒤 지운다([ADR-083](../../adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md))
- `connector_attachments` 가 참이면 그 옛 커넥터 에이전트가 사진을 받는다. 커넥터가 선언한 toolset 이 실제로 켜진 것을 확인했을 때만 참이다
- `proactive_check_writes_allowed` 는 「먼저 살펴보기에 쓰기 도구 허용」 이다. 관리자만 바꾼다. 켜면 그 에이전트의 먼저 살펴보기가 쓰기 toolset 과 결과물 쓰기를 쓰고 커넥터 쓰기를 승인 카드로 보낸다([ADR-082](../../adr/ADR-082-먼저-살펴보기의-쓰기-도구는-관리자가-에이전트마다-켜고-커넥터-쓰기는-승인-카드로-보낸다.md))
- `default_model_provider` 와 `default_model` 은 함께 채우거나 함께 비운다. `default_reasoning_effort` 는 모델 없이 혼자 둘 수 있다

**모델과 effort 는 대화가 고르고, 고르지 않으면 에이전트 기본 모델로 돈다.** 기본 모델 세 칸이 모두 비면 그 profile 의 값으로 돈다. 막힌 계정을 쉬게 하는 것은 Hermes 가 한다.
근거는 [ADR-030](../../../backend/docs/adr/ADR-030-모델과-effort-는-대화가-고르고-기본값은-hermes-profile-이-갖는다.md) 과 [ADR-054](../../../backend/docs/adr/ADR-054-에이전트-기본-모델과-모델-숨김은-control-plane-db-가-갖는다.md) 에 있다.

**주인과 공개 범위는 따로다.** 그룹에 공개해도 만든 사람이 계속 관리한다.
이 결정 전에 운영에서 등록한 `GROUP` 에이전트는 주인이 비어 있어 `ADMIN` 만 관리한다.
근거는 [ADR-033](../../../backend/docs/adr/ADR-033-사용자가-에이전트를-만들고-공개해도-만든-사람이-관리한다.md) 에 있다.

추천 질문은 데이터베이스에 두지 않고 backend 메모리에만 둔다. 없앤 칸과 표는 [ADR-036](../../../backend/docs/adr/ADR-036-추천-질문은-사용자의-대화-이력으로-모델이-만들고-메모리에만-둔다.md) 이 갖는다.

## model_tier_definition

그룹이 정한 모델 단계의 mapping 이다. 선택의 우선순위와 초기값은 [모델 단계와 실행 기록](../../model-tiers.md) 이 정한다.

- `provider` 가 비면 요청한 에이전트의 기본 provider 로 해석한다
- `model` 이 비면 그 단계는 에이전트 기본 모델로 돈다
- `(group_id, tier)` 가 유일하다

## model_tier_group_setting

그룹의 기본 모델 단계다. `default_tier` 가 비면 그룹 기본 단계가 없다.

## model_hidden

그룹이 숨긴 provider 와 모델이다. 숨긴 것만 적는다.

- `model` 이 빈 문자열이면 그 provider 전체를 숨긴 것이다. NULL 은 유일 제약이 겹침을 막지 못해 쓰지 않는다
- `(group_id, provider, model)` 이 유일하다

## toolset_hidden

그룹이 일반 에이전트 도구 화면에서 숨긴 toolset 이다. 행이 없으면 모두 보인다.
활성 도구는 Hermes profile 이 갖고 이 표는 활성 상태를 저장하거나 바꾸지 않는다([ADR-20261008 / tool-catalog-visibility](../../adr/ADR-20261008-tool-catalog-visibility.md)).

- `(group_id, name)` 이 유일하다. FK 는 없다. 새 설치에서 숨김 행을 만들지 않는다

## agent_toolset_request

에이전트 주인의 관리자 등급 도구 사용 요청과 결정 이력이다.
도구의 활성 여부는 Hermes 가 갖고 이 표는 요청 결과를 기록한다. 근거는 [ADR-20261009 / tool-request-flow](../../adr/ADR-20261009-tool-request-flow.md) 다.

- `group_id` 와 `requester_user_id` 는 요청 당시의 그룹과 주인이다. 그 뒤 주인이 바뀌어도 고치지 않는다
- `decided_by_user_id` 는 결정한 관리자다. 요청자가 취소하면 비운다
- `reason` 은 거절 사유 한 줄이거나 조건이 바뀌어 만료된 사유다
- **대기 요청을 하나만 두려고 `pending_slot` 을 쓴다.** 대기 중에만 1 이고 끝나면 NULL 이다. `(group_id, agent_id, requester_user_id, toolset, pending_slot)` 이 유일하므로 대기 요청은 겹치지 못하고, 끝난 요청의 NULL 은 서로 겹쳐도 저장되어 이력이 남는다. CHECK 제약이 상태와 슬롯을 함께 검사한다
- `agent_id` 는 `agent` 를, 요청자와 결정한 관리자는 `app_user` 를 FK 로 가리킨다. 에이전트를 지워도 행과 요청 이력을 남긴다

## agent_token

Hermes 가 Control Plane 의 MCP 도구를 부를 때 쓰는 장기 토큰이다.
토큰 원문은 발급 응답에서 한 번만 내고 데이터베이스에는 SHA-256 해시만 저장한다.

**토큰은 어느 profile 이 부르는지만 증명한다. 사용자를 정하지 않는다.**
요청자는 서명한 `_fos_ctx` 로 찾은 origin 실행의 `user_id` 다.
근거는 [ADR-032](../../../backend/docs/adr/ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md) 에 있다.

- 폐기되지 않은 토큰은 `profile_name` 이 늘 채워져 있다. 비어 있는 줄은 profile 을 묶기 전에 폐기된 옛 토큰뿐이고, 인증에서 거절한다. 그 이력을 남기려고 NULL 을 받는다
- 한 profile 에 토큰이 여럿일 수 있다. 바꿔 끼우는 동안 옛 토큰과 새 토큰이 함께 쓰인다. 그래서 `profile_name` 에 유일 제약을 두지 않는다
- 토큰 하나는 profile 하나에만 묶인다. 한 번 묶은 profile 은 바꾸지 않는다. 다른 profile 에 쓰려면 새로 발급한다
- 폐기해도 행을 지우지 않고 `revoked_at` 을 적는다

## service_token

다른 서비스가 사용자의 Memory 문서를 읽을 때 쓰는 토큰이다. 근거는 [ADR-056](../../../backend/docs/adr/ADR-056-다른-서비스는-사용자에-묶인-서비스-토큰으로-문서를-읽기만-한다.md) 에 있다.
원문은 발급 응답에서 한 번만 내고 데이터베이스에는 SHA-256 해시만 저장한다.

**`agent_token` 과 달리 사용자 한 사람에 묶인다.** 그 사용자 본인만 발급하고 폐기한다. 관리자도 다른 사용자의 토큰을 발급하거나 폐기하지 못한다.

- `expires_at` 은 늘 있다. 발급할 때 정하고 고치지 않는다. 받을 수 있는 범위는 `ServiceTokenService` 가 갖는다
- `last_used_at` 은 거절한 요청에서는 적지 않는다
- 폐기해도 행을 지우지 않고 `revoked_at` 을 적는다

허용 목록에서 사용자를 끄면 그 사용자의 폐기되지 않은 토큰에 모두 `revoked_at` 을 적는다. 다시 켜도 지우지 않는다.
인증할 때도 주인이 지금 허용 목록에 켜져 있는지 본다. 폐기가 한 번 실패해도 끈 사용자의 토큰이 통하지 않게 하기 위해서다.

## service_token_collection

서비스 토큰이 받는 collection 하나가 한 줄이다. `agent_memory_collection` 과 같은 뜻이고 같은 세 조건으로 판정한다.
`allow_sensitive` 가 참이면 그 collection 의 `SENSITIVE` 문서까지 읽는다.
