# 버전 변경과 실측

## provider 를 바꿀 때 확인할 계약

provider 를 바꿔도 Control Plane 은 profile 바인딩, 요청한 모델과 effort, 실제 실행 경로와 사용량을 같은 뜻으로 다룬다.
지원 여부와 credential 선택, 토큰 보고 방식은 provider 에 따라 달라질 수 있다.
[ADR-060](../adr/ADR-060-reasoning-effort-의-지원은-확인한-것만-보이고-모르면-미확인으로-둔다.md),
[ADR-067](../../backend/docs/adr/ADR-067-native-하위-에이전트의-provider-는-대시보드-plugin-이-session-저장소에서-읽어-준다.md),
[ADR-088](../../hermes/docs/adr/ADR-088-대시보드-plugin-은-감싸는-경로의-바꿔-끼우기를-두고-기대는-hermes-내부-지점을-계약-시험으로-확인한다.md)과 현재 구현을 기준으로 아래 범위를 확인한다.

### 가짜 Hermes 로 확인할 수 있는 것

여기서 가짜 Hermes 는 HTTP 응답 fixture, backend 의 stub 과 mock, 가짜 Hermes 모듈과 임시 SQLite 저장소를 포함한다.
시험 이름은 `backend/src/test/java/com/bifos/assistant/` 와 `hermes/tests/` 아래의 파일을 가리킨다.
응답을 어떻게 읽고 요청을 어떻게 보내는지 확인하는 범위이며, 실제 provider 의 지원과 청구 방식을 증명하지 않는다.

| 지점 | 유지할 계약 | 시험 |
| --- | --- | --- |
| 모델 목록과 reasoning 지원 | 인증된 provider 의 모델만 고른다. 같은 모델 이름이어도 provider 별 capability 를 따로 읽는다. boolean 이 아닌 값과 빠진 칸은 `UNKNOWN` 이며 provider 이름으로 메우지 않는다 | `hermes/HermesModelCatalogTest` |
| reasoning 끄기 | 미지정과 `none` 을 구분한다. `none` 은 끄기 지원이 `SUPPORTED` 이고 reasoning 이 `UNSUPPORTED` 가 아닐 때만 고른다. 요청 effort 를 적용값으로 바꿔 기록하지 않는다 | `chat/ModelOptionsServiceTest`, `chat/ModelSelectionTest`, `hermes/HermesRunRequestTest` |
| 실행별 모델 선택 | provider 와 모델을 함께 보내거나 함께 뺀다. provider 를 바꿔도 모델과 effort 를 그대로 보내며, profile 기본값을 쓸 때는 모델 선택 칸을 뺀다. 실패했다고 Control Plane 이 다른 provider 로 다시 제출하지 않는다 | `hermes/HermesRunRequestTest`, `chat/ModelSelectionTest` |
| 단계와 에이전트 기본 모델 | 모델 단계의 provider 가 비면 요청 profile 의 카탈로그로 정한다. 단계의 모델이 그 카탈로그에 없거나 선택한 모델이 숨겨졌으면 다른 provider 로 대체하지 않고 거절한다. 대화 선택, 에이전트 기본 모델, profile 기본값의 우선순위를 유지한다 | `chat/ModelTierServiceTest`, `chat/ModelVisibilityTest`; 선택 규칙은 [모델 단계](../model-tiers.md) |
| 요청 경로와 실제 경로 | 실행 조회의 요청 모델과 실제 `runtime` 을 구분한다. Hermes 가 알려 준 실제 provider 와 모델로 실행을 기록한다. 기본값과 요청값으로 실제 경로를 추정하지 않는다 | `hermes/HermesRuntimeReadTest`, `chat/ModelSelectionTest` |
| profile 인증 경계 | provider 교체로 API 인증 key 를 바꾸지 않는다. 요청자의 바인딩에서 profile 을 정하고 key 가 없으면 다른 profile 의 key 를 빌리지 않는다. 모델 목록 cache 도 profile 별로 나눈다 | `hermes/HermesRunRequestTest`, `hermes/HermesProfileKeyStoreTest`, `chat/ChatServiceTest`, `chat/ModelOptionsServiceTest` |
| credential 쓰기 경계 | 대시보드의 환경 쓰기는 허용한 Control Plane 칸만 받는다. 커넥터 연결 해제로 모델 credential 을 지우지 않는다. `credential_scope` 는 공유 여부의 선언이며 실제 OAuth 격리를 강제하는 값이 아니다 | `test_dashboard_profile_api_env.py`; 선언의 뜻은 [credential 경계](README.md#oauth-credential-은-여기서-빠진다) |
| 루트 usage 읽기 | 입력·출력·cache 칸의 알려진 별칭을 같은 토큰 수로 읽는다. 보고하지 않은 칸은 0 으로 채우지 않는다. total 이 없고 입력·출력이 모두 있으면 합을 구한다. cache 를 입력에 임의로 더하지 않는다 | `hermes/HermesUsageParsingTest` |
| 가격과 비용 모드 | provider 와 모델의 짝으로 가격을 찾는다. 같은 모델이어도 provider 별 가격을 적용하고 가격 미확인은 금액을 비운다. 입력에 포함된 cache 는 한 번만 센다. 구독은 실제 청구액을 비우고 API 는 환산액과 같은 값으로 기록한다 | `usage/CostEstimatorTest`, `usage/UsageCostRecordingTest` |
| native 자식 provider | session 응답의 provider 를 우선하고 없으면 plugin 을 조회한다. 부모 provider 를 빌리지 않는다. 주 호출이 같은 모델에서 provider 만 바꿔도 복수 경로로 보고 provider 를 비운다. 같은 짝의 반복 호출과 보조 호출은 교체로 세지 않는다. 읽기는 저장소를 바꾸지 않는다 | `hermes/SubagentProviderClientTest`, `usage/application/SubagentUsageReconcilerTest`, `test_dashboard_profile_api_session.py` |
| native 자식의 미확인과 합계 | session 의 입력에 cache 읽기·쓰기를 더해 run 과 같은 포함 입력으로 환산한다. 필요한 토큰을 모르면 금액을 비운다. 조회 불가 재시도는 종료 뒤 10분까지이며 이후 `PROVIDER_UNKNOWN` 으로 남긴다. usage·가격 미확인을 구분한다. 부모와 자식 사용량을 중복 없이 더하며 재조회가 원장을 다시 더하지 않는다 | `usage/application/SubagentUsageReconcilerTest`, `usage/application/SubagentUsageLedgerTest`, `usage/UsageBreakdownTest` |

### 운영 왕복이 필요한 것

아래는 확인 대상만 적는다. 실행 절차와 계정·환경 값은 `fos-home-infra` 가 소유한다.
provider 교체 전후에 해당하는 항목을 확인하고, 지원하지 않거나 관측할 수 없는 항목은 그 이유를 남긴다.

| 지점 | 확인 대상 |
| --- | --- |
| 실제 모델 선택 | 새 provider 의 인증 상태와 모델 목록이 해당 profile 의 계정과 맞고, 실행별 선택과 profile 기본값이 실제 `runtime` 의 provider·모델로 이어지는가 |
| 같은 session 의 provider 교체 | provider 를 바꾼 다음 turn 에서 앞 대화와 도구 호출 이력이 유지되는가. Responses 계열의 암호화된 reasoning 조각을 새 경로가 처리하거나 걸러 내는가. 실제 계정에서만 보이는 이력 호환 오류가 없는가([session 연속성](runs-api.md#모델을-바꿔-이어도-맥락이-남는다)) |
| 실제 reasoning 적용 | Hermes 가 알린 지원값과 실제 provider 가 받는 값이 맞는가. 미지정, `none`, 지원하는 effort 의 생략·축소·변환이 Hermes 계약과 맞는가. 실행 기록의 effort 는 요청값이라는 구분이 유지되는가 |
| AI credential 선택과 갱신 | API server key 와 AI provider credential 이 다른 역할을 유지하는가. `SHARED_HOUSEHOLD` 의 공유와 `DEDICATED` 의 전용 계정이 실제 인증·갱신에서도 선언과 맞는가. profile 의 OAuth 가 없을 때 루트 저장소를 쓰는 경로와 API key 선택이 예상과 맞는가 |
| 실패와 fallback | credential 이 없거나 만료되거나 계정이 막혔을 때 다른 profile 의 계정이나 다른 provider 로 넘어가지 않는가. profile 의 `fallback_providers` 가 비어 있고, Hermes 내부 경로 변경이 있으면 실제 경로가 정확히 기록되는가 |
| 실제 usage 의미 | 입력 토큰에 cache 읽기·쓰기가 포함되는가, 출력에 reasoning 토큰이 포함되는가, 누락과 0 이 구분되는가. cache 단가와 context 구간이 실제 보고 방식에 맞는가. 에이전트의 `cost_mode` 가 새 provider 계정의 구독·종량 경로와 맞는가. 알려진 별칭을 파싱하는 시험만으로 입력 포함 관계까지 확인했다고 보지 않는다 |
| 부모와 native 자식 | 부모 run usage 에 native 자식 토큰이 포함되지 않는가. 자식 session 의 입력은 cache 읽기·쓰기를 제외한 값이고 run 의 입력은 포함한 값인가. 자식 session 의 최종 카운터와 provider 가 실제 실행과 맞는가. `sessions` 와 `session_model_usage` 가 실제 주 호출 경로를 기록하고 보조 호출을 구분하는가 |
| plugin 과 Hermes 내부 지점 | 배포한 소스의 이름·시그니처·저장소 schema 가 선언과 맞으며, 등록된 미들웨어와 profile 범위가 실제 HTTP 요청에서도 동작하는가. provider 조회가 읽기 전용이고 권한 밖 session 을 돌려주지 않는가 |

ADR-088 의 `test_hermes_contract.py` 는 상류 소스에서 이름·시그니처·schema 와 일부 응답 모양을 확인하는 별도 검사다.
가짜 Hermes 의 동작 시험과 운영 왕복 사이에서 구조 변경을 먼저 잡지만, 같은 이름의 함수가 다르게 동작하거나 상류 tag 와 배포 이미지가 다른 것은 왕복 확인으로 판단한다.
실제 usage 가 위 환산 가정과 다르면 provider 이름별 보정값을 추측해 넣지 않고, 확인한 계약과 그에 맞는 fixture·처리를 함께 고친다.

## 버전을 올릴 때 달라지는 계약

2026-09-28 에 v0.21.3 과 v0.21.5 의 태그 소스와 변경 이력을 대조했다.
v0.21.3 은 `v2026.9.14`, commit `345cd2b057a452236de401d3534b8502a7465e8d` 이다.
조사 당시 최신 안정판 v0.21.5 는 `v2026.9.24`, commit `f97608f178d1ffeca59860195ab7da295f7c8e5f` 이다.
최신판 표시는 조회 날짜의 결과이며 계속 최신이라고 가정하지 않는다.
소스 호환 판정과 실제 배포 검증은 구분한다. 운영 절차는 `fos-home-infra` 에 둔다.

### 연동에 영향을 주는 변경

| 버전·대상 | 변경과 영향 | 근거 |
| --- | --- | --- |
| v0.21.1 내부 모듈 | 큰 파일을 분리했다. 내부 함수를 import 하는 plugin 은 모듈 위치를 확인해야 한다 | [release note](https://github.com/NousResearch/hermes-agent/releases/tag/v2026.9.7) |
| v0.21.2 DB | 중복 writer, 정상 DB 손상 오판, profile DB 혼선과 불필요한 기동 write lock 을 고쳤다 | [release note](https://github.com/NousResearch/hermes-agent/releases/tag/v2026.9.11), `SessionDB`, `hermes_state_registry.acquire` |
| v0.21.3 완료 전달 | 공유 profile 문맥 누락을 고쳤다. v0.21.0 의 완료 watcher 는 요청 profile 문맥을 잃고 listener 주인 profile 의 DB 에서 부모를 찾아, 없으면 완료 결과를 버렸다. v0.21.3 은 완료 사건의 profile 문맥에서 부모 판정과 결과 주입을 하고 보조 profile 의 미전달 기록도 복구한다. API 완료는 여전히 delivery 기록이며 자동 모델 실행이 아니다 | [commit `c632437c3bb3`](https://github.com/NousResearch/hermes-agent/commit/c632437c3bb3fcd19e755882ce346b270ef3d151) |
| v0.21.3 공유 제공 | `gateway.multiplex_profile_allowlist` 를 제거했다. 살아 있는 모든 profile 을 공유 제공한다 | [commit `9848e22ed659`](https://github.com/NousResearch/hermes-agent/commit/9848e22ed659d2e90ff3126f4dfcf78d9028efeb), `config_migrations` v43 |
| v0.21.3 cron·curator | `cron.model_drift_guard` 를 없애고 생성 당시 모델로 cron 을 실행한다. curator 의 이전 기본 기간을 stale 30→14일, archive 90→30일로 바꾼다. 명시한 다른 값은 보존한다 | `config_migrations` v42·v44, [commit `be2f7e9c3616`](https://github.com/NousResearch/hermes-agent/commit/be2f7e9c3616) |
| v0.21.3 DB schema | 29→30 migration 과 자식 transcript 의 trigram 검색 제외가 있다. 첫 DB open 이 index·DDL 을 바꿀 수 있다 | [commit `2b55ded1ac5f`](https://github.com/NousResearch/hermes-agent/commit/2b55ded1ac5f3b41cdc580974e745631dac1bb53), `hermes_state_schema` 의 `_init_schema`, `_reconcile_columns` |
| v0.21.3 DB 연결 | writer registry·읽기 전용 handle 을 보완했다. cross-VM 파일 시스템에서는 WAL 을 거절하거나 DELETE 모드를 쓴다 | [release note](https://github.com/NousResearch/hermes-agent/releases/tag/v2026.9.14), `hermes_state_wal._enable_wal` |
| v0.21.4 이후 gateway | multiplex 기본값과 host singleton 이 들어왔다. v0.21.5 는 명시적 `multiplex_profiles: false` 도 true 로 고친다. `gateway.standalone` 은 임시 호환 수단이다 | [v0.21.4](https://github.com/NousResearch/hermes-agent/releases/tag/v2026.9.21), [v0.21.5 정책](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/hermes_cli/gateway_multiplex_mode.py) |
| v0.21.5 설정 | config version 46. 일부 명시적 도구 목록에 `connections` 를 추가하고 MCP `disabled` 를 `enabled: false` 로 바꾼다. version 없는 설정에는 legacy key 단계만 적용한다 | [config_migrations.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/hermes_cli/config_migrations.py) 의 `_migrate_to_45`, `_migrate_to_46`, `LEGACY_KEY_STEPS` |

설정 migration 은 명시적 허용 목록도 바꿀 수 있다.
`connections` 를 비활성화했거나 이미 제공받고 제외한 기록이 있으면 추가하지 않는다.
DB migration 은 누락 column 과 잘못된 PK 도 조정하므로 이미지 버전만 되돌려도
데이터가 이전 형태로 복구된다고 보장할 수 없다.

v0.21.3 과 v0.21.5 에서 `_get_platform_tools(config, platform, include_default_mcp_servers=...)`,
`save_config(..., strip_defaults=False)`, profile 목록·삭제 함수와 home override 함수를 유지한다.
token provider 등록, profile 생성의 `name`, 환경 쓰기의 `profile/key/value`, SOUL 의 `content` 도 유지한다.
`platform_toolsets.api_server`, `agent.disabled_toolsets`, `approvals.*`, `skills.external_dirs` 의
관련 코드 경로도 남아 있다.
함수 이름과 인자 유지가 plugin 의 실제 HTTP 동작 검증을 대신하지 않는다.

**셸 계열 도구의 실행 공간 계약이 그대로인지 본다.**
[ADR-086](../adr/ADR-086-셸과-파일-도구는-사용자별-docker-실행-공간에서만-돈다.md) 는 docker backend 의 아래 동작에 기댄다.
하나라도 바뀌면 셸 계열 도구가 다시 Hermes 컨테이너로 새거나 실행 공간이 동작하지 않는다.
[실행 공간](sandbox.md) 의 「측정 결과」 시험을 새 이미지에서 다시 돌린다.

- 공유 listener 가 turn 마다 profile 의 `terminal.*` 를 scope 로 묶는다(`_profile_runtime_scope`, `install_and_reset_profile_terminal_scope`)
- `_get_file_ops` 와 `execute_code` 가 terminal 과 같은 환경을 쓴다
- `docker_volumes`, `docker_extra_args`, `env_passthrough`, `credential_files` 의 이름과 뜻
- 스킬 앞머리에서 환경 값과 파일을 실행 공간에 넣는 칸의 이름(`tools/skills_tool_setup.py` 의 `_get_required_environment_variables`, `required_credential_files`). 칸이 늘면 올린 스킬 저장 검사(`SkillFrontmatter`)와 대시보드 plugin 의 커넥터 스킬 검사(`_skill_name`)에 함께 더한다
- `HERMES_WRITE_SAFE_ROOT` 를 docker backend 의 파일 쓰기에도 경로 문자열로 적용한다
- `execute_code` 가 RPC 로 부를 수 있는 도구(`SANDBOX_ALLOWED_TOOLS`)에 MCP 도구가 없다. 생기면 [ADR-20261008 / connector-output-files](../../hermes/docs/adr/ADR-20261008-connector-output-files.md) 를 다시 검토한다
- gateway 가 `HERMES_EXEC_ASK` 를 켜고, 셸 위험 명령과 plugin 승인 요청이 ask 문맥에서 `approvals.unattended_mode` 를 보지 않는다. 계약 시험이 확인한다. 바뀌면 docker profile 의 `unattended_mode: approve` 가 그 둘까지 승인 없이 열므로 [ADR-20261008 / execute-code-unattended](../../hermes/docs/adr/ADR-20261008-execute-code-unattended.md) 를 다시 본다

**올린 스킬과 이름이 겹치는 스킬이 새로 생기지 않았는지 본다.**
Hermes 를 올리면 번들 스킬이 늘 수 있다. 같은 이름이면 profile 로컬 스킬이 외부 디렉터리의 올린 스킬보다 먼저 선택되어,
화면에 보이는 스킬과 실제로 도는 스킬이 달라진다.
업그레이드와 배포 확인은 profile 마다 올린 스킬 이름과 Hermes 가 더 앞서 고르는 스킬 이름이 겹치지 않는지 보고, 겹치면 배포를 멈춘다.
그 검사의 절차는 `fos-home-infra` 가 갖는다. 까닭은 [ADR-034](../../backend/docs/adr/ADR-034-올린-스킬은-control-plane-이-버전-디렉터리에-쓰고-hermes-는-읽기만-한다.md) 의 「Hermes 를 올릴 때 이름 충돌을 본다」 에 있다.

**올린 Hermes 이미지의 `mcp` SDK 버전을 본다.**
대시보드 plugin 의 커넥터 도구 호출은 Hermes 가 설치한 `mcp` Python SDK 를 쓰고, 지원 범위는 `mcp>=2.0,<3` 이다([커넥터 설치](../backend/connector-install.md) 의 「MCP SDK 계약」).
Hermes 는 `mcp` 를 정확한 판 하나로 고정한다. v0.19.0 은 1.26.0, v0.20.0 부터 v0.20.2 까지는 1.28.1, v0.20.3 부터 v0.21.5 까지는 2.0.0 이다. 그래서 판은 이미지를 올릴 때만 바뀐다.
1.x 는 속성 이름이 camelCase(`readOnlyHint`, `structuredContent`, `isError`)라 plugin 의 모든 커넥터 도구 호출이 `unavailable` 이 된다. 2.0.0, 2.0.1, 2.1.1, 2.2.0 에서는 plugin 의 호출 순서가 같게 동작함을 2026-10-01 에 확인했다.
올릴 때 확인할 것은 셋이다.

- 새 이미지의 `mcp` 판이 `2.` 으로 시작한다
- `mcp_types.ToolAnnotations` 에 `read_only_hint` 가, `mcp_types.CallToolResult` 에 `structured_content` 와 `is_error` 가 있다
- `from mcp import ClientSession, StdioServerParameters` 와 `from mcp.client.stdio import stdio_client` 가 된다

대시보드의 MCP probe 는 Hermes 자신의 코드로 돌아 SDK 판과 무관하게 도구 수를 낸다. probe 가 통과해도 plugin 의 `call` 경로가 맞다는 증거가 아니다.
판이 어긋나면 plugin 이 올라올 때 판과 까닭을 로그 한 줄로 남긴다. 확인 절차와 live 검사는 `fos-home-infra` 가 갖는다.

근거는 [v0.21.3 tools_config.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/tools_config.py),
[config.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/config.py),
[profiles.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/profiles.py),
[hermes_constants.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_constants.py) 다.

### 설정 migration 과 DB migration 의 범위

2026-09-28 의 v0.21.3 격리 검증에서 확인했다.
Docker 이미지의 `docker/stage2-hook.sh` 는 루트 `HERMES_HOME` 의 config 만 자동 migration 한다.
**이름 붙은 profile 의 `config.yaml` 은 자동 migration 되지 않아 `_config_version` 이 그대로 남는다.**
그 profile 에 옛 기본값을 명시한 curator 기간 등의 설정은 새 기본값으로 바뀌지 않는다.
이름 붙은 profile 의 session DB 는 공식 `SessionDB` 를 처음 열 때 schema 29→30 으로
지연 migration 된다. config 와 DB 의 적용 시점이 다르다.

공식 진단 API `POST /api/ops/config-migrate` 에는 profile 선택자가 없다.
이 경로만으로 이름 붙은 profile 을 골라 migration 할 수 없다.
이미지를 올렸다는 사실만으로 모든 profile 의 저장 설정이 갱신됐다고 판정하면 안 된다.

근거는 [v0.21.3 stage2-hook.sh](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/docker/stage2-hook.sh),
[status.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/web_routers/status.py) 의
config migration 처리기와 `SessionDB` 의 schema 초기화다.

### Runs 응답과 사건의 버전 차이

| 계약 | v0.21.3 | v0.21.5 |
| --- | --- | --- |
| 생성·조회 | 생성은 202 와 `run_id/status`. 조회는 `run_id/status/session_id/model/output/error/usage/last_event` 등의 flat object | 기본 필드에 실제 실행 `runtime`, 중단·미완료 정보를 더한다 |
| SSE envelope | JSON 최상위의 `event`, `run_id`, `timestamp` | 유지한다 |
| 자식 사건 | `subagent_id`, `child_session_id`, `delegation_id`, `task_index`, `parent_id`, `depth`, `model`, `status`, 토큰 등 값이 있는 필드 | 기존 필드를 유지한다. 모든 필드가 항상 오는 것은 아니다 |
| 도구 완료 | `tool`, `duration`, `error` | 비밀값을 제거하고 길이를 제한한 `preview` 를 더한다 |
| 메시지·종료 사건 | `message.delta`, `reasoning.available`, `approval.request`, `run.steered`, `run.completed`, `run.failed`, `run.cancelled` | `message.interim`, `run.interrupted` 를 더한다 |
| run 사용량 | `input_tokens`, `output_tokens`, `total_tokens` | `cache_read_tokens`, `cache_write_tokens` 를 더한다. input 은 cache 를 포함한 전체 prompt 토큰이다 |
| stop | 활동 중이면 `stopping` 과 hard interrupt 요청. 최종 상태는 별도 조회 | 같은 비동기 중단 계약을 유지한다 |

소비자는 v0.21.5 의 `interrupted` 도 종료 상태로 처리해야 한다.
run 입력 토큰에 cache read·write 를 다시 더하면 중복 합산이 된다.
우리 backend 는 `cache_read_tokens` 를 캐시 입력으로 읽고, 입력 단가는 `input_tokens` 에서 그것을 뺀 나머지에만 매긴다. v0.21.3 의 run usage 에는 캐시 칸이 없어 그 전 실행의 캐시 입력은 비어 있다.
session 상세의 비캐시 입력 토큰과 혼동하지 않는다.
근거는 [v0.21.3 api_server_runs.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/gateway/platforms/api_server_runs.py) 와
[v0.21.5 api_server_runs.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/gateway/platforms/api_server_runs.py) 의
`terminal_run_status`, `_execute_run`, `_mark_shutdown_interrupted_runs` 다.

## 대시보드 plugin 이 기대는 내부 지점

`dashboard-profile-api` 는 공개 확장점이 아닌 Hermes 내부 지점에 기댄다.
감싸는 미들웨어, import 하는 내부 함수, session 저장소의 칸, 감싸는 대시보드 경로가 여기 해당한다.
목록은 [`hermes/tests/hermes_contract.py`](../../hermes/tests/hermes_contract.py) 가 갖고, 왜 공개 확장점으로 바꾸지 못하는지는 [ADR-088](../../hermes/docs/adr/ADR-088-대시보드-plugin-은-감싸는-경로의-바꿔-끼우기를-두고-기대는-hermes-내부-지점을-계약-시험으로-확인한다.md) 이 갖는다.

Hermes 를 올리기 전에 새 판의 tag 로 `scripts/check-hermes-contract.sh <tag>` 를 돌린다.
실패한 항목을 plugin 에서 고치고, `HERMES_VERSION` 을 새 tag 로 바꾼 PR 의 CI 가 통과한 뒤 이미지를 올린다.

### MCP 설정 맞추기와 스킬 색인 캐시 키

바인딩 설치가 재시작 없이 반영되는 것은 두 내부 동작에 기댄다([ADR-20261007 / connector-live-reload](../adr/ADR-20261007-connector-live-reload.md)).

- 공유 gateway 의 housekeeping 에 MCP 설정 맞추기 작업이 있고, 그 작업이 profile 마다 `reconcile_mcp_servers_with_config` 를 부른다
- 스킬 색인 캐시 키에 `skills.disabled` 에서 읽은 이름이 들어 있다. plugin 이 바꾼 색인 표식이 그 profile 의 색인을 새로 만들게 한다

[`hermes/tests/hermes_contract.py`](../../hermes/tests/hermes_contract.py) 의 `LIVE_RELOAD` 가 이 지점을 선언하고, `HermesSourceTest.test_live_reload_points` 가 소스에서 확인한다.
실패하면 붙인 커넥터가 재시작 전까지 보이지 않는데 Control Plane 은 반영됐다고 판정할 수 있다. ADR 의 판정을 다시 본다.

## 커넥터 정책이 기대는 계약

Hermes 를 올릴 때 아래가 그대로인지 본다. 하나라도 달라지면 커넥터 도구의 판정이 비켜 갈 수 있다.
계약의 내용은 [도구 hook 과 승인](connector-policy.md) 에 있다.

| 계약 | 달라지면 |
| --- | --- |
| MCP 도구의 등록 이름 규칙(`mcp_prefixed_tool_name`) | 대시보드 plugin 의 `_hermes_tool_name` 을 같은 규칙으로 고친다. 다르면 hook 이 도구를 대응 파일에서 찾지 못해 모두 막는다 |
| 글이 있는 `block` 이 MCP 요청을 막는다 | hook 으로 강제할 수 없다. wrapper 가 필요하다 |
| 중계 도구 `tool_call` 이 hook 에 안쪽 등록 이름을 준다 | 중계 도구를 커넥터를 설치한 profile 에서 막는다 |
| `mcp_servers.<서버>.tools.exclude` | `approval: always` 인 도구가 모델에게 보인다. 호출은 여전히 Control Plane 이 거절한다 |
| `PluginContext.call_mcp` 가 `mcp_allowlist` 없는 서버를 부르지 못한다 | plugin 의 직접 호출이 판정 없이 나간다 |
| `transform_tool_result` hook 은 처음 돌려준 글이 결과를 바꾸고, hook 이 실패하면 원래 결과가 가며, 판정이 막은 호출에는 닿지 않는다 | 바인딩 profile 의 커넥터 결과가 `<external-data>` 없이 들어가거나, 판정이 막은 안내 글까지 외부 글로 감싸져 모델이 승인을 기다리라는 안내를 따르지 않을 수 있다. 결과 hook 이 도는 자리를 다시 확인하고 `fos-ctx` 를 고친다 |
