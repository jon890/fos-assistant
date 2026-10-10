# Hermes 의 동작 계약

이 저장소가 기대는 Hermes 의 동작을 Hermes 표면마다 갖는다. 우리 코드가 아니라 upstream 의 동작이다.
기준 판은 `hermes/tests/hermes_contract.py` 의 `HERMES_VERSION` 이고, 다른 판에서 확인한 대목에만 판을 적는다.
Hermes 내부 지점에 새로 기대면 같은 커밋에서 `hermes_contract.py` 에 선언한다.
우리 plugin 의 계약은 [`hermes/plugins/fos-ctx/README.md`](../plugins/fos-ctx/README.md) 와 [`hermes/plugins/dashboard-profile-api/README.md`](../plugins/dashboard-profile-api/README.md) 가 갖는다.

## 확장 지점

| 문서 | 내용 |
| --- | --- |
| [Runs API](hermes-contract.md) | 실행 요청, 조회 응답과 사건의 모양 |
| [동시 실행](hermes-contract.md) | profile 접두와 실행 한도, thread pool |
| [도구와 스킬](hermes-contract.md) | 도구 설정, Control Plane MCP(`fos-assistant`) 와 스킬 연결 |
| [스킬](hermes-contract.md) | 스킬을 profile 에 붙이는 방법과 올린 스킬의 계약 |
| [profile 생성](hermes-contract.md) | profile 관리 API 와 생성 계약 |
| [위임](hermes-contract.md) | 내장 delegation 과 Control Plane 도구 위임 |
| [`_fos_ctx` 와 session 등록](../plugins/fos-ctx/README.md) | MCP 호출에 실행을 잇는 서명과 하위 에이전트 session 등록 |
| [도구 hook 과 승인](hermes-contract.md) | `pre_tool_call` 로 MCP 도구를 막을 때의 계약, 등록 이름, 내장 승인 |
| [실행 공간](hermes-contract.md) | 셸과 파일 도구를 profile 마다 docker 컨테이너에서 돌리는 계약과 측정 |
| [MCP 프로세스의 환경 값](hermes-contract.md) | MCP 프로세스에 사용자별 환경 값을 전달하는 계약 |
| [버전 변경과 실측](hermes-contract.md) | provider 교체의 계약 시험 범위, 버전별 계약 차이와 확인 결과 |


NousResearch 의 Hermes Agent 를 Agent Runtime 으로 쓴다.
이 문서는 core 를 수정하지 않고 쓸 수 있는 확장 지점을 정리한다.
그 확장 지점에 설치하는 plugin 과 profile 틀은 이 저장소의 [`hermes/`](..) 에 있다. 배치는 [`hermes/docs/code-architecture.md`](code-architecture.md) 의 「디렉터리 배치와 배포 순서」 가, 설치 묶음과 운영 값은 [`hermes/README.md`](../README.md) 가 갖는다.
운영값과 실행 절차는 비공개 저장소 `fos-home-infra` 에 둔다.
버전이 붙은 설명은 그 버전에서 확인한 계약이다.
2026-09-28 조사로 확인한 버전 차이는 [「버전을 올릴 때 달라지는 계약」](hermes-contract.md#버전을-올릴-때-달라지는-계약) 에 모았다.

### profile 이 곧 사용자 격리 단위다

Hermes 는 profile 마다 아래를 따로 가진다.

- `config.yaml` 과 `.env`
- `SOUL.md` 로 정하는 에이전트 성격
- `memories/`, `skills/`, session 과 메시지 기록

`config.yaml` 안의 `${VAR}` 는 그 profile 의 `.env` 에서만 값을 찾는다.
셸 환경 변수도, 다른 profile 의 `.env` 도 보지 않는다.

#### OAuth credential 은 여기서 빠진다

AI credential 이 어디 있고 profile 사이에 무엇을 함께 쓰는지는 이 절과 다음 절이 갖는다. 다른 문서는 요약만 두고 여기를 가리킨다.

`.env` 와 달리 OAuth 는 profile 로 갈리지 않는다.
profile 에 `auth.json` 이 없으면 루트의 `~/.hermes/auth.json` 을 읽는다.
v0.21.0 의 `tui_gateway/methods_profiles.py` 주석이 그것을 말한다.

> profile reads fall back to the global store, and token refreshes write THROUGH to it

자기 `auth.json` 이 없는 profile 들은 한 로그인을 함께 쓴다.
그러므로 profile 을 나누는 것만으로 credential 이 갈렸다고 볼 수 없다.

profile 이 자기 credential 을 가지려면 둘 중 하나가 있어야 한다.

- 자기 `auth.json`. `hermes -p <profile> login` 이 만든다
- 자기 `.env` 안의 provider API key. `API_SERVER_KEY` 는 여기 해당하지 않는다

루트 `auth.json` 을 복사하는 방식은 쓰지 않는다.
Hermes 주석에 따르면 복사하면 갱신 토큰이 둘로 갈라지고 한쪽 갱신이 다른 쪽을 무효로 만든다.

지금은 그룹이 구독 하나를 함께 쓰기로 정했다. ADR-002 가 그 결정을 담는다.
그래서 격리를 강제하지 않고, 공유하고 있다는 사실을 데이터와 검사로 드러내기만 한다.

#### 우리가 더하는 것

- 에이전트마다 `credential_scope` 를 적는다. `SHARED_HOUSEHOLD` 는 그룹이 함께 쓰는 AI 계정, `DEDICATED` 는 그 profile 만의 계정이다.
  관리자 API 로 만들 때는 기본값이 없어 만드는 사람이 반드시 고른다. 첫 로그인이 만드는 에이전트는 설정값을 쓰고 기본은 `SHARED_HOUSEHOLD` 다([`docs/features/users.md`](../../docs/features/users.md)). 에이전트 만들기 화면이 만드는 에이전트는 `SHARED_HOUSEHOLD` 로 적는다.
- 운영 저장소의 검사가 격리 여부를 판정하고, 공유는 명시할 때만 넘어간다.
- profile 마다 `fallback_providers` 를 비워 둔다. 한 사람의 요청이 다른 모델로 넘어가지 않는다.
- Control Plane 은 대화를 시작할 때 사용자가 쓸 수 있는 에이전트에서 profile 이름을 꺼낸다.
  이어지는 요청은 에이전트나 profile 을 바꾸지 못한다.

### API server

profile 마다 API server 를 열 수 있다.
설정은 `config.yaml` 이 아니라 profile `.env` 의 환경 변수로 준다.

| 이름 | 설명 |
| --- | --- |
| `API_SERVER_ENABLED` | 기본값은 false |
| `API_SERVER_HOST` | 기본값은 127.0.0.1 |
| `API_SERVER_PORT` | 기본값은 8642 |
| `API_SERVER_KEY` | 필수. Bearer 토큰으로 검사한다 |
| `API_SERVER_MODEL_NAME` | 기본값은 profile 이름 |

`gateway.multiplex_profiles` 를 켜면 한 서버가 `/p/<profile>/v1/...` 경로로 여러 profile 을 받는다.
profile 마다 자기 `API_SERVER_KEY` 가 필요하고, 다른 profile 의 `run_id` 로 조회하면 404 가 온다.
403 이 아니라 404 인 점이 중요하다. 남의 실행이 있는지조차 알려주지 않는다.

### plugin hook

plugin 은 `~/.hermes/plugins/<이름>/` 에 `plugin.yaml` 과 `__init__.py` 를 두고
`register(ctx)` 에서 도구와 hook 을 등록한다.
hook 은 27종이 있고 그중 아래가 우리에게 쓸모 있다.

| hook | 얻는 것 |
| --- | --- |
| `post_llm_call` | LLM 호출 하나 단위의 모델, provider, 토큰 |
| `pre_tool_call`, `post_tool_call` | 도구 호출과 소요 시간 |
| `subagent_start`, `subagent_stop` | subagent 실행 경계 |

Runs API 의 `usage` 는 실행 하나의 합계만 준다.
v0.21.0 과 v0.21.3 은 cached token 을 내보내지 않지만 v0.21.5 는 cache 칸을 더한다.
LLM 호출마다 모델과 사용량을 구분해야 하면 plugin hook 을 쓴다.

### gateway 는 s6 가 감독한다

아래는 v0.21.0 과 v0.21.3 의 동작이다.
v0.21.4 이후의 host singleton 과 multiplex 정책은 [「연동에 영향을 주는 변경」](hermes-contract.md#연동에-영향을-주는-변경) 의 「v0.21.4 이후 gateway」 줄이 갖는다.

이 컨테이너는 listener 주인의 gateway 하나를 돌리고 s6 가 감독한다.
이름이 붙은 profile 은 s6 service 자리를 만들기만 하며, 개별 gateway 를 자동으로 띄우지 않는다.
`systemd` 와 같은 자리이고 컨테이너용으로 훨씬 작다.

`hermes gateway run` 은 s6 에 넘기고 스스로 끝난다.
그래서 이 명령의 종료를 gateway 가 죽은 것으로 읽으면 안 된다.

상태 확인과 다시 시작 같은 운영 명령은 비공개 저장소 `fos-home-infra` 가 갖는다.

`hermes gateway start` 로 띄운 프로세스는 s6 밖에서 돈다.
포트는 응답하지만 컨테이너를 다시 띄우면 사라지고 s6 가 되살리지 않는다.

컨테이너가 다시 뜰 때 gateway 를 함께 띄울지는 저장된 `desired_state` 가 정한다.
multiplex 를 켜면 listener 주인의 gateway 만 이 값에 따라 시작하고,
이름이 붙은 profile 의 s6 service 자리는 만들기만 한다.
한 번 켜 두면 `running` 이 남아 다음 기동에서 자동으로 뜨며,
일부러 멈춘 gateway 는 멈춘 채로 남는다.

## profile 생성

도구 없는 시스템 판단 profile은 [가치 평가](../../docs/features/proactive.md)의 준비 상태 계약을 따른다.
`GET /api/profiles/{name}/decision-readiness`는 Control Plane 관리 토큰으로 그 profile의 준비 여부만 읽는다.

### profile 을 HTTP 로 만드는 길

대시보드 웹서버에 profile 관리 API 가 있다.
v0.21.0 의 `hermes_cli/web_routers/profiles.py` 를 읽고 실제로 불러 확인했다.

| 메서드와 경로 | 하는 일 |
| --- | --- |
| `GET /api/profiles` | profile 목록과 각각의 모델, 스킬 수, gateway 상태 |
| `POST /api/profiles` | profile 을 만든다 |
| `PATCH /api/profiles/{name}` | 이름을 바꾼다 |
| `DELETE /api/profiles/{name}` | profile 과 wrapper 와 gateway 서비스를 지운다 |
| `GET /api/profiles/{name}/soul` | `SOUL.md` 를 읽는다 |
| `PUT /api/profiles/{name}/soul` | `SOUL.md` 를 쓴다 |
| `PUT /api/env` | 본문의 `profile` 이 가리키는 profile 의 `.env` 에 한 줄을 쓴다 |

기본 상태에서 이 경로들은 대시보드의 사람용 로그인 쿠키만 받는다.
쿠키 없이 부르면 401 이 오고 응답의 `reason` 이 `no_cookie` 다.

#### 기계용 인증 자리가 따로 있다

`hermes_cli/dashboard_auth/token_auth.py` 가 그 자리다.
`register_token_route(path)` 로 등록한 경로만 `Authorization: Bearer` 를 받고,
등록하지 않은 경로는 이 미들웨어가 그대로 통과시켜 쿠키 검사로 넘긴다.

**배포본의 core 안에서 이 함수를 부르는 곳이 없다.**
부르는 것은 번들 plugin `plugins/dashboard_auth/drain` 하나뿐이고,
등록하는 경로도 `/api/gateway/drain` 하나다.
그래서 `POST /api/profiles` 는 등록돼 있지 않다.

경로를 등록하면 그 경로의 인증은 토큰만으로 정해진다.
토큰이 맞으면 통과하고, 토큰이 없거나 틀리면 401 이고,
provider 가 검증에 쓰는 저장소에 닿지 못하면 503 이다. 열린 채로 통과하는 분기는 없다.

#### plugin 이 token provider 를 붙일 수 있다

`ctx.register_dashboard_auth_provider(provider)` 가 그 입구다.
provider 는 `DashboardAuthProvider` 를 상속하고 `supports_token` 을 True 로 두고
`verify_token` 을 구현한다. 로그인과 세션에 해당하는 나머지 메서드는
`NotImplementedError` 로 두어도 된다. drain plugin 이 그 형태를 그대로 보여 준다.

provider 는 프로세스 전역 슬롯에 등록되고, profile 별 plugin manager 가 내려가도 남는다.
token provider 가 여럿이면 등록 순서대로 물어보고 하나가 principal 을 돌려주면 거기서 끝난다.

측정용 대시보드를 따로 띄워, 경로 둘을 등록하는 plugin 하나를 붙여 확인했다.

| 요청 | 응답 |
| --- | --- |
| 토큰 없이 `GET /api/profiles` | 401 |
| 틀린 토큰으로 같은 경로 | 401 |
| 맞는 토큰으로 같은 경로 | 200 과 목록 |
| 맞는 토큰으로 `POST /api/profiles` | 200. profile 이 만들어졌다 |
| 맞는 토큰으로 `PUT /api/env` | 200. 그 profile 의 `.env` 에 값이 들어갔다 |
| 맞는 토큰으로 등록하지 않은 `GET /api/sessions` | 401 |

Hermes core 는 한 줄도 고치지 않았다.
plugin 디렉터리 하나와 `plugins.enabled` 한 줄이 전부다.

#### 경로는 문자열이 정확히 같아야 한다

`is_token_route` 는 등록한 집합에 그 경로 문자열이 있는지만 본다.
`/api/profiles/{name}` 처럼 자리표시자를 담은 문자열은 어떤 요청과도 맞지 않는다.

| 경로 | plugin 이 열 수 있는가 |
| --- | --- |
| `/api/profiles` | 등록 한 번으로 열린다. 만들기와 목록 조회가 같은 경로다 |
| `/api/env` | 등록 한 번으로 열린다 |
| `/api/profiles/<이름>` | 그 이름을 알 때 그 이름마다 따로 등록해야 한다 |
| `/api/profiles/<이름>/soul` | 위와 같다 |

맞는 토큰으로 `DELETE /api/profiles/<이름>` 과 `PUT /api/profiles/<이름>/soul` 을 불러
둘 다 401 이 오는 것을 확인했다.
`register_token_route` 자체는 잠금을 걸고 집합에 넣기만 하므로 기동 뒤에도 부를 수 있다.
이름을 아는 시점에 그 이름의 경로를 더 등록하는 것은 막히지 않는다.

#### plugin 이 대시보드에 자기 경로를 더하는 hook 은 없다

`ctx` 의 등록 메서드 어디에도 대시보드 라우트를 더하는 것이 없다.
API server 쪽에는 `register_platform_handler("api_server", factory)` 가 있지만
대시보드 웹서버에는 대응하는 자리가 없다.
그래서 plugin 이 할 수 있는 것은 이미 있는 경로를 기계에게 여는 것까지다.
기존 대시보드 미들웨어를 감싸 생성 처리 앞뒤에 코드를 두는 방법은 별개이며,
아래 「profile 을 만드는 요청 안에서 도구 설정을 검증한다」 에 그 근거를 적었다.

#### `SOUL.md` 를 읽고 쓰는 두 경로

v0.21.0 의 `hermes_cli/web_routers/profiles.py` 와 `hermes_cli/web_models.py` 를 읽어 확인했다.

| 경로 | 본문 | 응답 |
| --- | --- | --- |
| `GET /api/profiles/{name}/soul` | 없다 | `content` 와 `exists` |
| `PUT /api/profiles/{name}/soul` | `content` 하나. 문자열이고 필수다 | `ok` |

`GET` 은 파일이 없으면 `content` 를 빈 문자열로 두고 `exists` 를 거짓으로 돌려준다.
읽다가 실패하면 500 이고, 그때는 빈 본문이 아니라 오류가 온다.

`PUT` 은 받은 `content` 로 파일 전체를 바꾼다. 일부를 고치는 것이 아니다.
임시 파일에 쓰고 fsync 한 뒤 원본 자리에 옮기므로, 쓰다가 끊겨도 앞 내용이 반쯤 남지 않는다.

#### `POST /api/profiles` 가 실제로 만드는 것

본문은 `name` 하나가 필수이고 나머지는 선택이다.
`clone_from`, `clone_all`, `no_skills`, `description`, `provider`, `model`,
`mcp_servers`, `keep_skills`, `hub_skills` 를 받는다.
핸들러는 `hermes_cli/profiles.py` 의 `create_profile` 을 부른다.

`clone_from` 없이 만들면 아래를 한다.

- profile 디렉터리와 하위 디렉터리를 만든다
- `config.yaml` 에 기본 모델 설정을 쓴다
- `.env` 를 주석 세 줄만 담아 mode 600 으로 만든다
- `SOUL.md` 에 기본 내용을 쓴다
- 번들 스킬을 심는다. `no_skills` 를 true 로 주면 건너뛴다
- 이름이 겹치지 않으면 실행 wrapper 를 만든다
- 컨테이너 안에서는 그 profile 의 gateway 를 s6 서비스로 등록한다

**Control Plane 은 `no_skills` 를 true 로 보낸다.**
번들 스킬이 심기고 `skills` toolset 이 열리면 그 설명이 입력에 실린다.
그 profile 이 쓰는 스킬은 [「스킬을 profile 에 붙이는 방법」](hermes-contract.md#스킬을-profile-에-붙이는-방법) 대로 따로 붙인다.

**CLI 의 `--no-alias` 에 해당하는 본문 필드가 없다.**
API 로 만들면 wrapper 가 함께 생긴다.
`DELETE` 로 지우면 wrapper 와 s6 서비스가 함께 사라지는 것까지 확인했다.

`clone_from` 을 주면 원본의 `config.yaml` 과 `.env` 와 `SOUL.md` 와 `skills/` 와
`memories/MEMORY.md` 와 `memories/USER.md` 를 복사한다.
`clone_all` 을 주면 원본 전체를 복사하고 runtime 파일과 단일 사용 OAuth 파일만 뺀다.

#### 틀 없이 만든 profile 은 API 도구가 넓게 열린다

2026-09-28 에 v0.21.0 소스로 확인했다.
`hermes_cli/profiles.py` 의 `_seed_model_config` 는 활성 profile 의 `model` 블록만 쓴다.
`platform_toolsets.api_server` 와 `agent.disabled_toolsets` 는 생성되지 않는다.
따라서 [「toolset 의 목록과 접근 범위」](hermes-contract.md#toolset-의-목록과-접근-범위) 의 `hermes-api-server` 기본 목록이 적용되어
`terminal`, `file`, `memory`, `delegation` 등을 포함한 도구가 열린다.
`no_skills` 는 번들 스킬 심기를 막는 값이며 도구 설정을 제한하는 값은 아니다.
새 profile 을 만들면 key 를 주기 전에 도구 설정을 적용하고 검증해야 한다.

#### profile 을 만드는 요청 안에서 도구 설정을 검증한다

profile 생성 뒤의 공식 plugin hook 은 없다.
그래서 대시보드 plugin `dashboard-profile-api` 는 `hermes_cli/dashboard_auth/token_auth.py` 의 `token_auth_middleware` 를 모듈 속성으로 감싼다.
`web_server.py` 가 요청마다 이 이름을 다시 import 하므로, 감싼 다음 요청부터 토큰으로 부른 `POST /api/profiles` 의 생성 처리 앞뒤에 코드가 붙는다.
이 방식을 유지하는 까닭은 [ADR-088](adr/ADR-088-대시보드-plugin-은-감싸는-경로의-바꿔-끼우기를-두고-기대는-hermes-내부-지점을-계약-시험으로-확인한다.md) 이 갖는다.

| 필요한 동작 | 쓰는 Hermes 지점 |
| --- | --- |
| 생성된 profile 판별 | `hermes_cli.profiles.list_profile_names` 의 생성 전후 차이 |
| profile 문맥 선택 | `hermes_constants.set_hermes_home_override` 와 `reset_hermes_home_override`. 대시보드 `_profile_scope` 와 같은 방식 |
| 설정 저장 | `hermes_cli.config.save_config`. 원래 `model` 블록을 남기고 나머지 설정에 template 을 적용한다 |
| 실제 API 도구 계산 | `hermes_cli.tools_config._get_platform_tools(config, "api_server")` |
| 실패한 생성 정리 | `hermes_cli.profiles.delete_profile(name, yes=True)` |

처리 순서는 다음과 같다.
단계별 세부와 금지 toolset 목록은 `hermes/plugins/dashboard-profile-api/profiles.py` 가 갖는다.

1. 생성 전 목록을 읽는다. 읽지 못하면 생성 처리기를 부르지 않고 500 을 돌려준다.
2. 기존 생성 처리기를 호출한다. 400 이상이면 응답을 그대로 돌려준다.
3. 새 이름이 정확히 하나인지 확인한다.
4. 그 profile 문맥에서 설정 template 과 `.no-bundled-skills` 표식, profile plugin 을 쓴다.
5. `_get_platform_tools` 로 계산해 금지 toolset 이 없는지 확인한 뒤 관리 표식을 쓴다.
6. 3~5 에서 하나라도 실패하면 새로 생긴 profile 을 모두 지우고 500 을 돌려준다.
7. 공유 gateway 에 그 profile 의 plugin 을 다시 읽게 한다. 이것이 실패해도 생성은 성공이다.
8. 검증이 끝난 뒤 Control Plane 이 별도 `PUT /api/env` 요청으로 key 를 넣는다.

**clone 없이 만드는 경우 설정 검증 중에는 key 가 없어 공유 listener 의 접두 요청이 거절된다.**
이 순서로 생성과 설정 검증을 같은 요청 안에서 끝낸다.
위 내부 지점이 업그레이드로 달라지면 계산이 예외를 내고 생성이 거절되므로, 넓게 열린 profile 이 남지 않는다.
그 지점의 이름과 인자는 `hermes/tests/hermes_contract.py` 가 고정 버전 소스와 대조한다.
plugin 로딩은 기동 때 이뤄져 plugin 변경에는 대시보드 재시작이 필요하다.
설정 적용만을 위해 gateway 를 다시 띄울 필요는 없다.

표식은 다음 번들 동기화를 막을 뿐 이미 심은 스킬을 지우지 않는다.
처음부터 심지 않으려면 생성 본문에 `no_skills: true` 를 준다.
`skills` toolset 을 닫으면 이미 있는 스킬 색인은 입력에 들어가지 않는다.
토큰으로 부른 생성은 본문에 `name`, `no_skills`, `description` 만 받고 `clone_from` 을 거절한다.
그래서 아래 「`clone_from` 은 원본의 `API_SERVER_KEY` 까지 복사한다」 의 문제가 이 경로로는 생기지 않는다.

다른 방식은 아래 까닭으로 쓰지 않는다.

- 생성 뒤 별도 `PUT /api/config` 로 template 을 쓴다: 두 번째 요청이 빠지면 넓은 도구가 남는다.
- 안전한 profile 을 clone 한다: 원본 `.env` 에 뒤에 추가된 값까지 복사된다([ADR-018](../../backend/docs/adr/ADR-018-사람을-더하는-것을-control-plane-이-끝낸다.md)).
- `pre_tool_call` 의 `{"action": "block"}`: 실행 시점에 더 막을 수는 있지만 모델에 실리는 도구 정의와 입력 비용을 줄이지 않는다.
- managed scope: 프로세스 전체에 적용되므로 profile 별 template 을 대신하지 못한다.

이미 존재하는 listener 주인 profile 의 접두 없는 요청은 생성 wrapper 의 대상이 아니다.
그 범위는 `platform_toolsets.api_server` 또는 별도 실행 차단으로 정해야 한다.

#### `clone_from` 은 원본의 `API_SERVER_KEY` 까지 복사한다

profile 을 하나 만들어 `API_SERVER_KEY` 를 넣고,
그 profile 을 `clone_from` 으로 복제해 확인했다.
두 `.env` 의 `API_SERVER_KEY` 가 같은 값이었고, 그 값 하나로 두 접두가 모두 200 을 돌려줬다.

**그러면 key 가 profile 을 구분하는 값이 아니게 된다.**
사용자를 더할 때 `clone_from` 을 쓰지 않거나, 쓴 직후에 그 profile 의 key 를 새 값으로 덮어야 한다.

#### `API_SERVER_KEY` 는 `PUT /api/env` 로 넣는다

본문에 `profile` 과 `key` 와 `value` 를 준다.
쓰기 금지 목록에 `API_SERVER_` 로 시작하는 이름이 없다.
그 목록이 막는 것은 `PATH` 와 `PYTHONPATH` 처럼 하위 프로세스 실행에 영향을 주는 이름과
`HERMES_HOME` 처럼 Hermes 의 위치와 보안 정책을 정하는 이름이다.

`API_SERVER_KEY` 와 `API_SERVER_MODEL_NAME` 을 이 경로로 넣어 실제로 들어가는 것을 확인했다.

#### 공유 listener 를 쓰는 profile 에는 listener 설정을 넣지 않는다

`.env` 에는 `API_SERVER_MODEL_NAME` 과 `API_SERVER_KEY` 를 넣고,
`API_SERVER_ENABLED`, `API_SERVER_HOST`, `API_SERVER_PORT` 는 넣지 않는다.
그러나 listener 설정 세 개를 빼는 것만으로는 충분하지 않다.
`API_SERVER_KEY` 가 있으면 API server platform 이 자동으로 켜지기 때문이다.

이름이 붙은 profile 의 `config.yaml` 최상위에 `platforms.api_server.enabled: false` 를 명시한다.
그러지 않으면 gateway 가 뜰 때 `SecondaryPortBindingConfigError` 로 그 profile 의 adapter 를 건너뛴다.
공유 listener 하나가 모든 profile 을 받는데 두 번째 API server platform 도 활성화됐기 때문이다.

건너뛴 것은 기동 로그에 남지만, 그 profile 의 `/p/<profile>/` 라우팅은 정상으로 동작한다.
접두 라우팅은 adapter 목록이 아니라 profile 디렉터리 목록으로 정하기 때문이다.
`platforms.api_server.enabled: false` 로 자동 활성화를 막아도 접두 라우팅은 그대로 동작한다.
profile 을 만드는 쪽이 listener 설정 세 개를 넣지 않는 것은 테스트로 고정돼 있다.
설정 틀 [`config.yaml.template`](../profile-template/config.yaml.template) 이 `platforms.api_server.enabled: false` 를 넣는다.
그래서 Control Plane 이 만든 profile 은 기동할 때 이 경고를 남기지 않는다.

#### 넣은 직후 공유 listener 가 답한다

key 를 넣고 gateway 를 다시 띄우지 않은 채로 불렀다.

| 요청 | 응답 |
| --- | --- |
| 방금 넣은 key 로 `/p/<profile>/v1/capabilities` | 200 |
| 틀린 key 로 같은 경로 | 401 |

공유 listener 가 요청마다 profile 디렉터리를 훑기 때문이다.

#### 파일로만 되는 것이 남는다

Control Plane 이 읽는 key 파일은 Hermes 밖의 파일이고 Hermes 의 API 가 닿지 않는다.
그 파일을 두는 자리는 `fos-home-infra` 가 소유한다.
`config.yaml` 전체를 우리 템플릿으로 덮는 것은 `PUT /api/config/raw` 가 열려 있지만 확인하지 않았다.

#### 대시보드 인증이 켜지는 조건

대시보드가 loopback 이 아닌 주소에 붙거나 `dashboard.public_url` 이 설정돼 있으면 인증이 켜진다.
켜진 상태에서 등록된 auth provider 가 하나도 없으면 대시보드가 기동을 거부한다.
token provider 하나만 있어도 이 조건을 채운다.
측정용 대시보드를 loopback 에 붙이고 `dashboard.public_url` 만 넣어 인증을 켠 채로 확인했다.

### Control Plane 이 부르는 대시보드 plugin 경로

Hermes 대시보드 앞에는 우리 대시보드 plugin 이 있다. plugin 은 이 저장소의 [`hermes/plugins/dashboard-profile-api/`](../plugins/dashboard-profile-api) 에 있고, 서비스 토큰으로 오는 요청을 정해 둔 경로로만 받는다.
2026-09-29 에 정했다. 사용자의 에이전트 만들기와 스킬([ADR-033](../../backend/docs/adr/ADR-033-사용자가-에이전트를-만들고-공개해도-만든-사람이-관리한다.md), [ADR-034](../../backend/docs/adr/ADR-034-올린-스킬은-control-plane-이-버전-디렉터리에-쓰고-hermes-는-읽기만-한다.md))이 이 계약에 기댄다.
경로마다의 요청과 응답과 인증은 [`hermes/plugins/dashboard-profile-api/README.md`](../plugins/dashboard-profile-api/README.md) 의 「dashboard-profile-api 가 여는 것」 표가 갖는다. 커넥터 경로를 Control Plane 이 쓰는 방법은 [커넥터 설치](../../docs/features/connector.md) 의 「대시보드 plugin 계약」 이 갖는다.
이 절은 그 경로를 지날 때 Hermes 가 어떻게 동작하는지만 적는다.

**틀이 MCP 와 서명 plugin 을 붙인다.** `POST /api/profiles` 본문의 `mcp_servers` 는 쓰지 않는다. plugin 이 처리 뒤에 설정 틀로 `config.yaml` 전체를 다시 써서, 본문으로 넣은 등록이 사라진다.
토큰 값은 틀에 없고 Control Plane 이 `PUT /api/env` 로 넣는다. 토큰을 넣기 전의 MCP 연결 실패가 쌓이면 다시 붙는 간격이 길어지므로 만든 직후에 넣는다.

**`skills.external_dirs` 에 없는 디렉터리를 넣으면 Hermes 는 오류 없이 건너뛴다.** 올린 스킬이 조용히 모두 사라지므로 plugin 이 게시 전에 디렉터리가 있는지 본다.

## Runs API

우리가 쓰는 것은 Runs API 다.

| 메서드와 경로 | 쓰임 |
| --- | --- |
| `POST /v1/runs` | 실행 제출. `input`, `session_id`, `instructions` 를 받고 `run_id` 와 `status` 를 준다 |
| `GET /v1/runs/{run_id}` | `status`, `session_id`, `model`, `output`, `usage` 를 준다. 언제까지 답하는지는 아래 「실행 조회가 답하는 기간」 을 본다 |
| `GET /v1/runs/{run_id}/events` | SSE. `tool.started`, `tool.completed`, `subagent.start`, `subagent.complete` 와 종료 사건 |
| `POST /v1/runs/{run_id}/stop` | 실행 중단 |
| `POST /v1/runs/{run_id}/steer` | 도는 실행에 지시를 더한다. 아래 「도는 실행에 지시를 더하는 `steer`」 를 본다. 우리는 아직 부르지 않는다 |

**실행을 시작한 뒤 바꿀 수 있는 것은 중단과 `steer` 둘이다.** Control Plane 은 중단만 쓴다.
끝난 위임 결과는 같은 session 에 새 실행을 제출해 전한다([ADR-040](../../backend/docs/adr/ADR-040-위임-결과는-control-plane-이-부모-대화의-다음-turn-을-열어-전한다.md)).
응답 중에 사용자가 보낸 메시지도 그 turn 이 끝난 뒤 새 실행으로 보낸다([ADR-048](../../docs/adr/ADR-048-응답-중에-보낸-메시지는-control-plane-이-쌓아-두고-다음-turn-으로-합쳐-보낸다.md)).
실행 Graph 는 SSE 를 그대로 받아 그리면 되고, 이를 위해 Hermes 를 고칠 일은 없다.

`instructions` 는 에이전트의 기본 프롬프트를 지우지 않고 그 위에 얹힌다.
Control Plane 이 Memory 를 주입하는 자리가 여기다.

### 도는 실행에 지시를 더하는 `steer`

v0.21.5 의 `gateway/platforms/api_server_runs.py` 에 `_handle_steer_run` 이 있다.

| 항목 | 계약 |
| --- | --- |
| 경로 | `POST /v1/runs/{run_id}/steer` |
| 소유자 판정 | 조회, 중단과 같다([`hermes/docs/hermes-contract.md`](hermes-contract.md)) |
| 본문 | `input`, `message`, `text` 중 처음으로 찬 칸의 글을 읽는다 |
| 받는 때 | 그 run 의 `status` 가 `running` 일 때만. 아니면 409 `run_not_accepting_steer` 다. 중단을 보낸 run 도 이 판정으로 거절한다 |
| 빈 글 | 400 `invalid_steer_input` |
| agent 가 받지 않았다 | 409 `steer_not_accepted` |
| agent 가 예외를 던졌다 | 500 `steer_failed` |
| 성공 | `{ "object": "hermes.run.steer", "run_id", "accepted": true }` 와 `run.steered` 사건 |
| 넣지 못하고 끝났다 | 종료 사건과 조회 응답에 `pending_steer` 로 그 글이 실려 온다. 클라이언트가 다시 보내라는 뜻이다 |

조사에서 소스로 읽은 것이 둘 더 있다. 운영 Hermes 에서 왕복으로 확인하지는 않았다.

- 받은 글은 곧바로 들어가지 않는다. 다음 도구 묶음이 끝난 뒤 user 행으로 들어간다.
- 중단(hard interrupt)으로 끝나면 넣지 못한 글은 버려진다.

**Control Plane 은 아직 `steer` 를 부르지 않는다.**
넣지 못한 글과 버려진 글을 받아 둘 자리가 먼저 있어야 해서 대기열을 먼저 만들었다(ADR-048).
`steer` 를 쓰기 전에 운영 Hermes 에서 `steer` 와 `pending_steer` 의 왕복을 확인한다.
가짜 Hermes 에는 이 경로가 없다.

### 실행 조회가 답하는 기간

v0.21.5 의 [`gateway/platforms/api_server_runs.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/gateway/platforms/api_server_runs.py) 와 `api_server.py` 를 소스로 읽었다. 운영 Hermes 에서 왕복으로 확인하지는 않았다.

**실행 상태는 gateway 프로세스의 메모리에 있다.** `_run_statuses` 가 run 번호마다 상태 하나를 갖는다.

| 때 | `GET /v1/runs/{run_id}` |
| --- | --- |
| 도는 중 | 200. `status` 는 `running`, `stopping`, `waiting_for_approval` 가운데 하나다. 종료 상태가 아닌 값은 모두 도는 중으로 읽는다 |
| 끝난 뒤 1시간 안 | 200. 종료 `status` 와 `output`, `usage`, `session_id`, `runtime` 을 그대로 준다. 몇 번을 읽어도 같다 |
| 끝난 뒤 1시간이 지났다 | 404 `run_not_found`. `_sweep_orphaned_runs_once` 가 1분마다 돌며 `_RUN_STATUS_TTL`(3600초)을 넘긴 `completed`, `failed`, `cancelled` 를 지운다 |
| gateway 가 다시 떴다 | 404 `run_not_found`. 메모리가 비었다 |
| 남의 profile 이나 key 로 물었다 | 404. 있는지 알리지 않는다([`hermes/docs/hermes-contract.md`](hermes-contract.md)) |

**404 로는 「없는 run」 과 「끝난 지 오래된 run」 을 구분하지 못한다.** 어느 쪽이든 다시 물어도 답이 바뀌지 않는다.

**클라이언트가 떠나도 실행은 계속 돈다.** 실행은 gateway 의 task 로 돌고 조회나 사건 스트림 연결에 묶이지 않는다.
사건 스트림의 버퍼만 `_RUN_STREAM_TTL`(300초) 뒤에 치운다. 다시 구독해도 그 전의 조각은 오지 않는다.

gateway 가 내려가며 끊은 실행은 `interrupted` 로 적힌다. 그 상태도 메모리에 있어 다시 뜬 뒤에는 읽지 못한다.

제출할 때 `Idempotency-Key` 머리말을 주면 Hermes 가 그 run 의 상태를 디스크에 남기고, 다시 뜬 뒤에도 조회에 답한다. 도는 중에 gateway 가 죽은 run 은 `interrupted` 로 답한다.
**Control Plane 은 이 머리말을 보내지 않는다.**

Control Plane 이 다시 뜰 때 남은 실행을 이 조회로 다시 정한다([ADR-061](../../backend/docs/adr/ADR-061-재기동-때-남은-실행은-hermes-에-물어-정하고-도는-실행에는-다시-붙는다.md)).

**가짜 Hermes 의 실행 조회도 이 모양으로 둔다.** 도는 실행은 `running`, 끝난 실행은 같은 답을 되풀이하고, 지운 run 과 모르는 run 은 404 다.

### `/v1/runs` 에 사진을 싣는 법

v0.21.5(`v2026.9.24`)의 소스를 읽어 확인했다. 운영 Hermes 에서 왕복으로 확인하는 방법은 아래 「운영에서 확인할 것」 에 있다.

**`input` 의 마지막 항목의 `content` 를 목록으로 보내면 그대로 에이전트에 간다.**
[`api_server_runs.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/gateway/platforms/api_server_runs.py) 의 `_handle_runs` 가 `raw_input[-1].get("content")` 를 꺼내 `run_conversation` 에 넘긴다.
다른 경로가 부르는 정규화 함수(`api_server.py` 의 `_normalize_multimodal_content`)는 부르지 않는다.
그래서 `data:image/` 확인, `file` 파트 거절, 글 64KB 자르기가 이 경로에는 없다. **보내는 쪽이 정규화한 모양(`{"type": "image_url", "image_url": {"url": ...}}`)으로 보낸다.**

```text
"input": [
  {"role": "user", "content": [
    {"type": "text", "text": "<입력 글>"},
    {"type": "text", "text": "1번째 사진"},
    {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,..."}}
  ]}
]
```

| 무엇 | 동작 |
| --- | --- |
| 마지막이 아닌 `input` 항목 | 목록이면 글 파트만 이어 붙이고 이미지는 버린다(`_resolve_conversation_history`). 사진은 마지막 항목에만 싣는다 |
| 요청 본문 `conversation_history` | `str()` 로 바뀐다. 목록을 넣으면 base64 가 글로 모델에 간다. 쓰지 않는다 |
| 요청 본문 상한 | 10MB(`MAX_REQUEST_BYTES`, `client_max_size`). `Content-Length` 로 판정해 넘으면 413 이다 |
| provider 로 가는 모양 | Chat Completions 는 파트를 그대로 보낸다. Responses(codex 계열)는 `input_image` 로, Anthropic 은 base64 이미지 블록으로, Gemini native 는 `inlineData` 로 바꾼다 |
| 모델이 이미지를 받지 않는다 | 요청마다 그 모델의 vision 지원을 판정한다(`image_routing.py` 의 `_lookup_supports_vision`, config 재정의, models.dev 순서). 지원하지 않거나 판정하지 못하면 보조 vision 모델의 설명 글로 바꿔 보낸다(`vision_message_prep.py`) |
| 상류가 이미지를 거절한다 | 오류 문구로 알아보고 그 요청 사본에서 이미지를 뗀 뒤 다시 시도한다(`turn_recovery.py`). 너무 크다는 거절은 줄여 한 번 다시 시도한다 |
| fallback provider 로 넘어갔다 | 위 판정이 넘어간 모델 기준으로 다시 돈다 |
| session 기록 | 이미지 파트는 `[screenshot]` 글로 저장된다(`session_persistence.py` 의 `_durable_content`). 다음 실행의 기록에 이미지가 다시 실리지 않는다 |
| 같은 턴의 도구 루프 | 모델을 다시 부를 때마다 사용자가 올린 이미지가 다시 실린다. 요청에서 빼는 정책은 도구 결과 이미지에만 적용된다 |
| 압축이 그 턴에 돈다 | 원본 파트가 기록에 남을 수 있다. 압축은 가장 최근 이미지 메시지 앞의 이미지를 「[Attached image — stripped after compression]」 으로 바꾼다 |
| API 오류 | 요청 전체를 `request_dump_*.json` 으로 남긴다. base64 가 들어간다 |
| `pre_llm_call`, `post_llm_call` hook | `user_message` 로 목록을 그대로 받는다. 우리 plugin 은 두 hook 을 쓰지 않는다 |
| 사용량 | 이미지 토큰을 따로 나누지 않는다. provider 가 준 입력 토큰에 섞인다 |
| `detail` | Responses 만 옮기고 Anthropic, Gemini 는 무시한다. Control Plane 은 보내지 않는다. openai-codex 의 클라이언트는 `low` 를 지원하지 않는다며 막는다 |
| `http(s)` 이미지 주소 | Hermes 는 받아 provider 에 그대로 넘긴다(Responses 는 `input_image` 의 `image_url`). 그러나 openai-codex 쪽은 2026-06 에 공식 클라이언트가 이 경로를 막았다(openai/codex #29417, #29419). 백엔드가 주소를 받아 가는 경로는 느리고 권장하지 않는다고 적혀 있다. Control Plane 은 `data:` 만 보낸다 |
| 이미지 토큰 | `gpt-6.1-sol` 에서 화소 수에 거의 비례한다(약 850화소에 1토큰, 2026-10-09 측정). 긴 변 1600px 4:3 사진 한 장이 약 2,300 토큰이다. 모델은 32px 패치로 보고 `high`, `auto` 에서 패치 2,500개(긴 변 2048px)까지는 줄이지 않는다 |

Control Plane 이 이 모양으로 사진을 싣는 결정은 [ADR-20261009 / native-image-input](../../backend/docs/adr/ADR-20261009-native-image-input.md) 에 있다.

**가짜 Hermes 도 목록 `input` 을 받는다.** 마지막 항목의 첫 글 파트를 입력 글로 읽고, 이미지 파트를 그 앞의 이름표 글과 함께 따로 기억한다.

#### 운영에서 확인할 것

배포한 뒤 사진을 붙여 한 번 보내 본다. 실제 명령은 `fos-home-infra` 가 갖는다.

- 답이 사진 내용을 말하고, 그 실행의 도구 사건에 `vision_analyze` 가 없다
- 그 실행에 413 이나 `invalid_image_url` 같은 오류가 없다
- 다음 메시지에서 지난 사진을 물으면 에이전트가 `vision_analyze` 로 사본 파일을 본다
- 11장 이상, 30장까지 보내면 모든 사진이 줄인 크기로 입력에 실리고, 그 실행에 413 이 없으며 이번 메시지 사진에 `vision_analyze` 가 없다
- 지난 사진 여러 장을 물으면 `vision_analyze` 를 한 장씩 차례로 부른다

### 이미지 파일은 `vision_analyze` 로 본다

`read_file` 이 이미지 확장자를 만나면 내용을 돌려주지 않고 `vision_analyze` 를 쓰라는 안내를 낸다.
지난 메시지의 사진과 흐름이 붙은 에이전트의 사진은 이 도구로 원본 옆의 줄인 사본(`{첨부 번호}.small.jpg`)을 본다. 사본은 대개 1MB 아래다.

실행 공간(Docker)의 파일은 컨테이너 안에서 `head -c <50MB+1> < 경로 | base64` 로 읽는다(`tools/image_source.py`).
운영에서 3MB 를 넘는 사진이 가끔 「image file is truncated」 로 실패했다. Pillow 가 디코딩하다 바이트가 모자란 것이다.
어디서 잘리는지는 확인하지 못했다. 파이프의 종료 코드가 마지막 명령의 것이라 읽기 오류가 가려진다.
1.2MB에서 3MB 사이의 원본 여덟 장을 동시에 부른 호출이 모두 실패한 적도 있다.
150KB에서 400KB 사이의 사본 아홉 장을 동시에 부른 호출도 여덟 장이 실패했고(끝의 1바이트에서 67바이트를 처리하지 못했다는 오류), 차례로 부른 호출도 실패한 적이 있다.
로컬 Docker 에서 같은 명령을 같은 `DockerEnvironment.execute` 로 0.65MB에서 2.6MB 사이의 파일 여덟 장씩 동시에 640번 읽었을 때는(CPU 부하를 더한 경우 포함) 잘림이 없었다.
끝의 몇 바이트를 잃는 증상이라 Hermes 의 출력 수집, 운영의 Docker 중계, Docker exec 가운데 어느 층인지 따로 조사한다.
그래서 Control Plane 은 이번 메시지의 사진을 모두 입력에 싣고, 지난 사진만 한 장씩 차례로 이 도구로 보게 안내한다.

### 모델은 실행마다 정한다

`POST /v1/runs` 는 요청 본문의 `provider` 와 `model` 을 그 실행에만 적용한다.
profile 의 `config.yaml` 이 정한 것은 기본값일 뿐이다.

**둘을 함께 보내야 한다.**
`provider` 만 주고 `model` 을 빼면 Hermes 가 config 의 모델 문자열을 새 provider 에 그대로
넘겨 `No LLM provider configured` 로 끝난다.

provider 해석이 실패하면 이전 provider 가 남고 모델만 요청 값으로 바뀌는 중간 상태가 된다.
그 상태에서 오는 404 는 provider 가 아니라 모델을 찾지 못한 것이다.

**둘 다 보내지 않으면 그 profile 의 기본값으로 돈다.** 기본값은 `config.yaml` 의 `model.provider` 와 `model.default` 다.
Control Plane 은 사용자가 대화에서 모델을 고르지 않았으면 두 칸을 모두 빼고 보낸다.

**reasoning effort 는 `model_options` 로 그 실행에만 준다.**
본문의 `model_options: {"reasoning": {"effort": "<값>"}}` 을 받는다. 옛 형식 `model_options.reasoning_effort` 도 받는다.
받는 값은 `none`, `minimal`, `low`, `medium`, `high`, `xhigh`, `max`, `ultra` 다. 모르는 값은 오류 없이 버리고 기본값으로 돈다.
모델이 받지 않는 값은 그 provider 의 값으로 맞춘다. 맞추는 규칙은 provider 와 모델마다 다르고 Hermes 가 갖는다.
`model_options` 를 보내지 않으면 그 모델에 정해 둔 설정값을 쓴다. 이것이 「미지정」 이다.

| 보낸 값 | 상류가 해석하는 것 |
| --- | --- |
| 없음(`model_options` 없음, `effort` 없음, 모르는 값) | `reasoning_config` 가 비어 그 모델의 profile 설정값으로 돈다 |
| `none` | `{"enabled": false}`. 이 실행에서 reasoning 을 끈다. 미지정과 다른 의도다 |
| `minimal` 부터 `max` | `{"enabled": true, "effort": "<값>"}` 을 전달한다. provider 가 받지 않는 값은 더 약한 쪽의 가까운 값으로 줄이고, 약한 값이 없으면 가장 약한 값으로 맞춘다 |

`none` 을 받아도 모든 route 가 reasoning 을 끌 수 있는 것은 아니다. 끌 수 없는 provider 는 그 값을 줄이거나 빼고, 오류로 알리지 않는다.
요청이 받아들여졌는지와 provider 가 실제로 무엇을 받았는지는 응답에서 알 수 없다.

위는 v0.21.3(`v2026.9.14`)과 v0.21.5(`v2026.9.24`)의 `_request_reasoning_config` 와 `agent/reasoning_effort.py` 의 `EFFORT_LADDER`, `clamp_effort` 를 읽어 확인했다.
두 판의 해석이 같았다. 실제 Runs API 왕복으로 확인하지는 않았다.
근거는 [v0.21.3 `gateway/platforms/api_server.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/gateway/platforms/api_server.py) 의 `_request_reasoning_config`, `_request_agent_overrides` 와
[v0.21.3 `gateway/platforms/api_server_runs.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/gateway/platforms/api_server_runs.py) 가 그것을 넘기는 자리다. v0.21.5 에서도 같다.

### 조회 응답의 `model` 은 실제로 돈 모델이 아니다

아래는 v0.21.0 과 v0.21.3 의 계약이다.
v0.21.5 에서 추가한 실제 실행 `runtime` 과의 차이는 [「Runs 응답과 사건의 버전 차이」](hermes-contract.md#runs-응답과-사건의-버전-차이) 에 있다.

**`GET /v1/runs/{run_id}` 의 `model` 은 우리가 보낸 값을 되돌려 줄 뿐이다.**
Hermes 안에서 다른 모델로 넘어가도 이 값은 바뀌지 않는다.

실제로 돈 모델은 `GET /api/sessions/{session_id}` 의 `model` 이 담는다.
fallback 으로 넘어간 뒤의 모델까지 그쪽에 들어 있다.

**v0.21.5 에서 달라진 두 가지**(운영에서 확인):

- `GET /api/sessions/{session_id}` 는 행을 `{"object": "session", "session": {...}}` 로 감싸고, `model` 은 `session` 안에 있다. provider 칸은 응답에 없다. Hermes 저장소에는 `billing_provider` 로 남는다. 저장소의 그 칸은 [`hermes/docs/hermes-contract.md`](hermes-contract.md) 의 「자식 session 의 provider 는 저장소에만 있다」 가 적는다
- `GET /v1/runs/{run_id}` 는 끝난 실행에 `runtime: {"provider", "model", "route_source"}` 를 싣는다. fallback 으로 넘어간 경우도 실제로 돈 값이다. 근거는 [v0.21.5 `gateway/platforms/api_server_runs.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/gateway/platforms/api_server_runs.py) 의 `_served_runtime` 과 `api_server.py` 의 `_sanitize_runtime_metadata` 다

그래서 Control Plane 은 실행의 `runtime` 에 provider 와 모델이 둘 다 있으면 그 짝을 실행 기록에 적는다.
둘 중 하나라도 없으면 세션 조회, 실행의 `runtime`, 대화가 고른 값 순서로 먼저 있는 것을 칸마다 따로 채운다.

`POST /api/sessions/{id}/chat` 은 응답에 `runtime` 을 담아 요청한 것과 실제로 돈 것을
한 응답에서 대조할 수 있다. v0.21.3 까지는 그 블록이 `/v1/runs` 에 없었다. v0.21.5 는 위 「v0.21.5 에서 달라진 두 가지」 처럼 실행 조회에도 싣는다.

`/v1/capabilities` 와 `/v1/models` 도 실제로 쓴 provider 모델을 주지 않는다.
실제 모델을 알아야 하면 `/api/model/options` 가 그 profile 의 것을 답한다.

### `/api/model/options` 는 provider 와 모델 목록을 함께 준다

응답의 모양은 아래와 같다.

```text
{
  "provider": "<지금 provider>",
  "model": "<지금 모델>",
  "providers": [
    {
      "slug": "<provider>", "name": "<표시 이름>",
      "is_current": true, "is_user_defined": false,
      "authenticated": true, "source": "hermes",
      "models": ["<모델>", ...], "total_models": <정수>,
      "capabilities": {"<모델>": {"fast": true, "reasoning": true}},
      "featured_models": []
    },
    {
      "slug": "<설정하지 않은 provider>", "authenticated": false, "source": "canonical",
      "models": [], "total_models": 0,
      "auth_type": "api_key", "key_env": "<환경 변수 이름>", "warning": "<설정 안내>"
    }
  ]
}
```

| 칸 | 뜻 |
| --- | --- |
| `provider`, `model` | 그 profile 의 기본값. `config.yaml` 의 `model.provider` 와 `model.default` 다. `provider` 는 Hermes 판에 따라 빠질 수 있다 |
| `providers[].slug` | 실행 요청의 `provider` 에 넣는 값 |
| `providers[].authenticated` | 그 provider 로 실제로 부를 수 있는가 |
| `providers[].models` | 그 provider 로 고를 수 있는 모델 |
| `providers[].is_current` | 기본 provider 인가 |
| `providers[].capabilities.<모델>.reasoning` | 그 모델이 reasoning 을 받는가. 상류 카탈로그가 모르면 상류가 `true` 로 채운다. 그래서 `true` 는 지원이 검증됐다는 뜻이 아니다. 칸이 없을 수도 있다 |
| `providers[].capabilities.<모델>.can_disable_reasoning` | reasoning 을 끌 수 있는가. 모델 카탈로그를 주는 aggregator provider(`nous`, `openrouter`)의 모델에만 있다. 다른 provider 는 칸이 없다 |

모델별로 받는 effort 수준(`supported_efforts`)은 상류가 일부러 내보내지 않는다. 실제 지원보다 적게 알려 주기 때문이다.
그래서 `minimal` 을 받는 모델인지 알려 주는 칸은 없다. v2026.9.24 의 `hermes_cli/inventory.py` 의 `_apply_capabilities` 에서 확인했다.

**설정하지 않은 provider 도 목록에 나온다.**
API server 는 목록을 만들 때 Hermes 가 아는 provider 가운데 빠진 것을 빈 행으로 채운다.
그 행은 `authenticated: false`, `models: []` 이다.
목록에 있다고 부를 수 있는 것은 아니므로 `authenticated` 가 참인 행만 골라야 한다.

`key_env` 는 환경 변수 이름이지 값이 아니다.
`pricing`, `free_tier`, `unavailable_models` 는 provider 에 따라 붙을 수 있다.

목록은 그 profile 의 `config.yaml` 에서 온다.
`model` 절, `providers`, 이전 형식의 `custom_providers`, `model_catalog.excluded_providers` 를 읽는다.
`fallback_providers` 는 목록에 영향을 주지 않는다.
쿼리는 `refresh` 하나만 읽는다.

다른 경로는 이 목록을 대신하지 못한다.
`/v1/models` 는 profile 이름만 준다.
대시보드의 `/api/providers/custom-endpoints` 는 사용자가 더한 endpoint 만 준다.
둘 다 provider 별 모델 목록이 아니다.

### 소진은 본문으로만 알 수 있다

HTTP 상태로는 판정하지 못한다. 제출은 늘 202 이고 조회는 늘 200 이다.

| 상황 | 무엇이 오나 |
| --- | --- |
| 그 provider 의 계정이 전부 막힘 | `status` 가 `failed`, `error` 가 `⚠️ Provider authentication failed:` 로 시작 |
| 모델 이름이 그 provider 에 없음 | `HTTP 404` 로 시작하는 상류 본문 |
| 잔액 부족 | `HTTP 402` 로 시작하는 상류 본문 |

**첫 줄의 접두사만 판정 근거로 쓴다.** Hermes 가 붙이는 고정 문자열이고 한 경로만 탄다.
나머지는 상류 provider 가 보낸 것이라 문구가 바뀔 수 있다.

**한 provider 안에서 계정을 돌려 쓰는 것은 Hermes 가 한다.**
`hermes auth` 가 provider 마다 credential 을 여럿 두고 막힌 것과 남은 시간을 기억한다.
그래서 위 접두사가 오는 시점은 **그 provider 의 계정이 전부 막혔을 때**다.
Control Plane 은 그 위층만 맡는다. credential 을 다루는 코드를 우리가 갖지 않는다.

소진 상태를 HTTP 로 읽는 경로는 없다. `hermes auth list` 는 정확히 알지만 CLI 뿐이다.

### 모델을 바꿔 이어도 맥락이 남는다

같은 `session_id` 로 모델만 바꿔 이어 보내면 앞 turn 의 내용이 그대로 실려 간다.
도구 호출이 있던 turn 이 섞여도 `tool_calls` 와 `tool_call_id` 가 복원된다.
시스템 프롬프트만 새 모델 기준으로 다시 만든다.

깨질 수 있는 자리는 도구 호출 형식이 아니라 Responses 계열의 암호화된 reasoning 조각이다.
Responses 에서 일반 OpenAI 호환 provider 로 갈 때는 그 조각을 걸러 내므로 문제가 없다.
Responses 계열끼리 오갈 때 표시가 없는 옛 조각이 남아 있으면 400 이 날 수 있다.

### 실행 이벤트가 실제로 오는 형태

v0.21.0 의 `gateway/platforms/api_server_runs.py` 가 보내는 것을 실측으로 확인했다.

**사건 이름은 `type` 이 아니라 `event` 다.** 중계하는 쪽이 `type` 을 읽으면 아무것도 받지 못한다.

| `event` | 함께 오는 칸 |
| --- | --- |
| `message.delta` | `delta` 에 답의 조각 |
| `tool.started` | `tool` 에 도구 이름, `preview` 에 주요 인자 하나의 값. 아래 「붙은 커넥터 서버의 도구 사건」 을 본다 |
| `tool.completed` | `tool`, `duration` 초, `error` 참거짓. v0.21.5 는 `preview` 에 결과를 더한다 |
| `subagent.start`, `subagent.complete` | [「자식 토큰을 SSE 로 받을 수 있다」](hermes-contract.md#자식-토큰을-sse-로-받을-수-있다) 의 식별자와 작업·사용량 필드 |
| `reasoning.available` | `text` 에 그때까지의 답 전체 |
| `run.completed` | `output` 과 `usage` |
| `run.failed`, `run.cancelled` | 끝 |
| `run.interrupted` | 끝. v0.21.5 부터 온다. `ChatRunEvents` 는 이것도 끝 사건으로 본다 |

`data:` 줄의 JSON 안에 `event` 가 들어 있다. SSE 의 `event:` 줄로 오지 않는다.
10초마다 `: keepalive` 주석이 온다.

**가짜 Hermes 를 이 형태로 맞춰 둔다.**
어긋나면 테스트는 통과하는데 운영에서 조각이 흐르지 않는다.

#### 붙은 커넥터 서버의 도구 사건

붙은 커넥터 서버의 MCP 도구도 내장 도구와 같은 길로 사건을 보낸다.
v0.21.5 의 `agent/tool_executor.py` 가 도구 하나를 실행하기 전에 `tool.started`, 끝난 뒤에 `tool.completed` 를 `tool_progress_callback` 으로 부르고,
`gateway/platforms/api_server_runs.py` 가 그것을 위 표의 사건으로 바꿔 보낸다.
도구 종류에 따라 다르게 보내는 곳은 없다.

| 칸 | 값 |
| --- | --- |
| `tool` | 등록 이름 `mcp__<서버>__<도구>`. 줄이는 규칙은 [`hermes/docs/hermes-contract.md`](hermes-contract.md) 의 「MCP 도구의 등록 이름」 이 갖는다 |
| `tool.started` 의 `preview` | 주요 인자 하나의 값. 도구마다 정한 인자가 있고, 모르는 도구는 `query`, `text`, `command`, `path`, `name`, `prompt`, `code`, `goal` 중 처음 있는 인자다. 그것이 없으면 null 이다. 비밀값을 가리지 않고 `tool_preview_length` 로만 자른다 |
| `tool.completed` 의 `preview` | 비밀값을 가리고 500자로 자른 결과. 외부 서비스의 글이 실린다 |
| 모든 사건의 `run_id`, `timestamp` | 있다 |
| 정책 hook 이 막은 호출 | 시작도 완료도 오지 않는다 |

그 결과 글이 실행 기록과 화면에 남지 않게 Control Plane 이 붙은 서버의 도구 내용을 통째로 가린다([ADR-083](../../docs/adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md)).
도구 이름, 걸린 시간, 실패 여부만 남는다.

**사건은 우리가 `GET /v1/runs/{run_id}/events` 를 열어야 받는다.**
Hermes 는 도구를 실행했다는 로그를 남기지만 사건 스트림은 구독자가 있을 때만 읽힌다.
Control Plane 은 한 번에 받는 경로에서도 스트림을 연다([ADR-090](../../backend/docs/adr/ADR-090-한-번에-받는-경로도-hermes-사건-스트림을-열어-도구-사건을-남긴다.md)).

가짜 Hermes 는 허용된 커넥터 도구 호출을 위 모양(`run_id`, `timestamp`, 시작의 인자 `preview`, 완료의 결과 `preview`)으로 흘린다.

### session 을 지우는 경로

v2026.9.24 소스에서 확인했다. API server 가 `DELETE /api/sessions/{session_id}` 를 연다(`gateway/platforms/api_server.py` 의 `_handle_delete_session`).
`/p/<profile>/` 접두로 부르면 그 profile 의 `state.db` 에서 지운다.

| 무엇 | 동작 |
| --- | --- |
| 응답 | 200 `{"object": "hermes.session.deleted", "id": ..., "deleted": true}`. 모르는 session 은 404 `session_not_found` 다 |
| 함께 지우는 것 | 그 session 의 메시지와 위임으로 만든 자식 session(`delegate_task`)이 한 쓰기 트랜잭션에서 지워진다(`hermes_state_sessions.py` 의 `delete_session`) |
| 남는 것 | 압축과 분기로 이어진 자식 session 은 지우지 않고 부모 칸만 비운다. `sessions_dir` 없이 부르므로 `sessions/` 아래 기록 파일과 요청 덤프(`request_dump_<session>_*.json`)도 남는다 |
| 인증 | 그 profile 의 key 를 `Authorization: Bearer` 로 보낸다. 실행 경로와 같다 |

Control Plane 은 지운 대화를 정리할 때 이 경로를 부른다. 근거는 [ADR-20261008 / conversation-purge](../../backend/docs/adr/ADR-20261008-conversation-purge.md) 에 있다.

## 동시 실행

### profile 접두

우리가 고정한 v0.21.5 는 v0.21.0 과 아래 두 가지가 다르다.

- multiplex 가 기본으로 켜진다. 명시적 `gateway.multiplex_profiles: false` 도 true 로 고친다. `gateway.standalone` 은 임시 호환 수단이다.
- `gateway.multiplex_profile_allowlist` 는 v0.21.3 에서 제거됐다. 살아 있는 모든 profile 을 접두 아래에 제공한다.

버전별 근거는 [「버전을 올릴 때 달라지는 계약」](hermes-contract.md#버전을-올릴-때-달라지는-계약) 을 따른다.

multiplex 에서는 listener 하나가 `/p/<profile>/...` 로 모든 profile 을 받는다.
default profile 의 listener 에는 각 경로가 접두 없는 형태와 `/p/<profile>` 접두 형태로 함께 등록된다.
`connect()` 가 경로 표를 한 번 순회하며 두 형태를 모두 등록한다.

v0.21.0 은 multiplex 사용 여부를 아래 순서로 정했다.

1. 환경 변수 `GATEWAY_MULTIPLEX_PROFILES`
2. 기본 profile `config.yaml` 의 `gateway.multiplex_profiles`
3. 기본값 `false`

환경 변수에서는 `true`, `1`, `yes`, `on` 을 참으로 읽는다.
빈 문자열이나 알 수 없는 값은 무시하고 `config.yaml` 값을 쓴다.

허용 목록이 없으므로 이름이 유효하고 삭제 표시가 없는 profile 을 모두 제공한다.

**새 profile 은 gateway 를 다시 띄우지 않아도 접두 아래에 열린다.**
접두를 검사하는 middleware 가 요청마다 profile 디렉터리를 다시 훑고 목록을 캐시하지 않기 때문이다.

**접두는 multiplex 를 켜지 않아도 동작한다.**
profile 별 gateway 도 자기 이름의 접두를 통과시키고 남의 이름은 404 로 거절한다.
그래서 공유 listener 를 세우기 전에 주소에 접두만 먼저 붙여 볼 수 있다.

| 요청 | 응답 |
| --- | --- |
| 자기 이름의 접두 | 200 |
| 남의 이름의 접두 | 404 `Unknown or unconfigured profile` |
| 남의 profile 의 key | 401 `gateway_auth_failed` |
| 남의 profile 의 `run_id` 조회 | 404. 존재 여부를 알리지 않는다 |

**listener 를 세우는 것과 밖에서 닿게 하는 것은 다른 일이다.**
바인딩 주소를 따로 정하지 않으면 컨테이너 안에서만 열린다.

#### credential 을 푸는 규칙이 달라진다

multiplex 를 끄면 profile `.env` 에 없는 credential 을 프로세스 환경에서 찾는다.
multiplex 를 켜면 profile `.env` 에 없는 credential 을 프로세스 환경으로 내려가 찾지 않는다.
profile scope 없이 credential 을 읽으려 하면 예외가 난다.

`API_SERVER_KEY` 는 접두로 고른 profile scope 에서 읽는다.
값을 읽지 못하거나 16자보다 짧으면 빈 값이 되고, 그 profile 의 요청을 모두 거절한다.

예외는 둘이다.

- `API_SERVER_ENABLED`, `API_SERVER_HOST`, `API_SERVER_PORT` 는 배포 설정이므로 프로세스 환경에서 읽는다.
  `API_SERVER_KEY` 는 credential 이므로 이 예외에 들어가지 않는다.
- 도구 하위 프로세스의 환경은 프로세스 환경을 바탕으로 만들고 provider credential 만 빼낸다.
  도구가 쓰는 일반 환경 변수는 계속 전달된다.

#### 보조 profile 경고와 실행 범위

이름이 붙은 profile 의 `.env` 에 `API_SERVER_KEY` 가 있으면 Hermes 는 그 profile 의
API server platform 을 자동으로 켠다.
`config.yaml` 에 `api_server` 를 적지 않거나 `API_SERVER_ENABLED`, `API_SERVER_HOST`,
`API_SERVER_PORT` 를 모두 빼도 같다.

multiplex 에서는 `SecondaryPortBindingConfigError` 경고를 남기고 그 profile 의 adapter 를 건너뛴다.
그래도 `/p/<profile>/` 라우팅은 동작한다.
접두 라우팅은 adapter 목록이 아니라 profile 디렉터리 목록으로 정하기 때문이다.

자동 활성화를 막으려면 그 profile 의 `config.yaml` 최상위에 아래처럼 명시한다.

```yaml
platforms:
  api_server:
    enabled: false
```

이 설정을 적용해도 올바른 profile key 로 `/p/<profile>/` 를 호출하면 200,
다른 profile key 로 호출하면 401 이 온다.

`platform_toolsets.api_server` 는 API server 로 들어온 실행에 허용할 도구를 정한다.
이름이 비슷하지만 platform 활성화 설정과는 다르며, 이 항목을 지우면 실행의 도구 범위가 달라진다.

같은 Discord credential 을 여러 profile 에 적으면 나중 profile 의 Discord adapter 는 시작하지 않는다.
listener 주인 profile 의 Discord adapter 는 그대로 동작한다.

multiplex 를 켜면 cron scheduler 가 제공 대상인 모든 profile 을 순회한다.
개별 gateway 를 시작하지 않는 방법으로 어느 profile 의 cron 을 멈출 수 없다.

컨테이너가 기동할 때 profile 마다 s6 service 자리를 다시 만든다.
이 자리는 tmpfs 에 있어 컨테이너가 다시 뜰 때마다 사라진다.
multiplex 를 끄면 default profile 과 이름이 붙은 profile 을 모두 저장된 `desired_state` 에 따라 시작한다.
multiplex 를 켜면 default profile 만 `desired_state` 에 따라 시작하고,
이름이 붙은 profile 의 자리는 만들기만 한다.
다만 운영자가 개별 gateway 를 직접 시작하면 공유 listener 와 함께 돌 수 있고 Hermes 가 이를 막지 않는다.

#### 실행 소유자는 profile 과 key 가 함께 정한다

Hermes 는 실행을 만들 때 아래 값을 줄여 소유자 표에 남긴다.

```text
sha256(profile + "\0" + expected_api_key)
```

요청의 값이 소유자 표와 다르거나 소유자 표가 없으면 실행이 있는지 확인하지 않고 404 를 돌려준다.
조회, 사건 SSE, 중단, 승인과 steer 가 모두 같은 판정을 쓴다.
그래서 다른 profile 은 실행 번호를 알아도 그 실행의 존재 여부를 확인할 수 없다.

#### 공유 listener 가 바꾸는 경계

| 경계 | 정하는 곳 | 공유 listener 의 영향 |
| --- | --- | --- |
| 사용자 | Control Plane 의 실행 주인 | 없다 |
| 쓸 수 있는 에이전트 | Control Plane 의 권한 검사 | 없다 |
| Memory | Control Plane 이 실행마다 조립하는 `instructions` | 없다 |
| AI credential | 접두로 고른 profile scope | 유지된다 |
| API key | 접두로 고른 profile 의 `.env` | 유지된다 |
| 사용량 | Control Plane 의 실행 기록 | 없다 |
| 동시 실행 한도와 event loop | listener 와 프로세스 | 모든 profile 이 함께 쓴다 |

요청 본문은 어느 profile 로 실행할지 정하지 못한다.
Control Plane 이 권한을 확인한 에이전트에서 profile 과 접두와 key 를 꺼내는 규칙은 그대로다.

### 동시 실행 한도는 listener 단위다

`gateway.api_server.max_concurrent_runs` 가 동시에 도는 실행을 제한한다.
그 값은 listener 주인 profile 의 설정에서 한 번 읽고, listener 가 하나면 한도도 하나다.

**공유 listener 로 옮기면 모든 profile 이 그 한도를 나눠 쓴다.**
넘으면 429 와 `rate_limit_exceeded` 가 온다.

한도를 끄면 실행이 메모리 한도까지 쌓이고, 그때 죽는 것은 프로세스 하나라
모든 profile 이 함께 끊긴다. 유한한 값을 두는 편이 낫다.

`gateway.max_concurrent_sessions` 는 다른 것이다.
그쪽은 platform 대화 turn 을 제한하고 `/v1/runs` 에는 걸리지 않는다.

**에이전트를 나눈 한 턴이 같은 순간에 쓰는 자리는 최대 2 이고, 나누지 않은 대화는 1 이다.**
`ResearchAndBuildFlow` 는 한 턴을 Chief, Researcher 와 Engineer, Synthesizer 의 세 단계로 나눈다.
Control Plane 이 앞 단계의 실행이 끝난 뒤에 다음 단계를 띄우므로 단계끼리 겹치지 않는다.
부모가 자식을 기다리는 동안에는 자리를 잡지 않는다.
둘이 겹치는 것은 Researcher 와 Engineer 뿐이다.

### 한도 위에 thread pool 이 하나 더 있다

`max_concurrent_runs` 를 통과한 실행은 곧바로 도는 것이 아니라 thread pool 을 한 번 더 지난다.

```python
result, usage = await asyncio.get_running_loop().run_in_executor(None, _run_sync)
```

`None` 은 그 event loop 의 기본 executor 를 쓰라는 뜻이다.
Hermes 는 그 executor 를 만들지도 크기를 정하지도 않으므로 Python 의 기본값이 그대로 쓰인다.
기본값은 `min(32, os.process_cpu_count() + 4)` 다. Hermes 이미지의 Python 3.13 에서 `ThreadPoolExecutor` 가 이 식을 쓴다.

**`os.process_cpu_count()` 는 CPU affinity 를 보고, 컨테이너의 `cpus` 는 여기에 들어가지 않는다.**
Docker 의 `cpus` 는 affinity 를 줄이지 않고 CPU 시간 할당량만 정한다. 그래서 affinity 는 호스트 코어 수 그대로다.
pool 크기는 컨테이너 설정으로 바꿀 수 없고, 호스트를 옮기면 값이 달라진다.

이 크기는 CPU 를 쓰는 일을 가정한 값이다.
실행이 자리를 잡고 있는 시간의 대부분은 LLM 응답을 기다리는 시간이고 그동안 CPU 를 쓰지 않는다.

### 한도를 pool 보다 크게 두면 429 가 아니라 기다린다

429 판정은 요청을 받는 자리에서 하고, pool 에 넘기는 것은 그보다 뒤다.
그래서 `max_concurrent_runs` 가 pool 크기보다 크면 넘친 실행도 `202` 로 받아들여지고,
pool 앞에서 순서를 기다린다.
pool 크기를 넘은 실행은 앞선 실행이 LLM 응답을 받아 자리를 비운 뒤에야 시작한다.

**기다리는 실행도 상태 조회가 `running` 으로 답한다.**
`_run_and_close()` 가 pool 에 넘기기 전에 상태를 `running` 으로 올리기 때문이다.
`queued` 상태는 정의돼 있지만 이 대기 구간에서는 드러나지 않는다.

**사용자가 겪는 것은 실패가 아니라 느림이다.**
호출한 쪽은 자기 실행이 시작조차 하지 않았다는 것을 알 수 없고,
이벤트 스트림에도 그동안 아무것도 오지 않는다.

### pool 은 실행만 쓰는 것이 아니다

`asyncio.to_thread` 도 같은 기본 executor 를 쓴다.
gateway 코드의 여러 곳이 이것을 부르고, 세션 저장소를 읽고 쓰는 일이 모두 여기 해당한다.

**실행이 pool 을 채우면 세션 저장소를 읽는 요청도 함께 밀린다.**
pool 을 지나는 `/api/sessions` 는 실행 하나가 자리를 비울 때까지 기다리고, pool 을 지나지 않는 `/v1/capabilities` 는 영향이 없다.
측정에서 `/api/sessions` 는 십여 ms 에서 LLM 응답 시간과 같은 30초로 늘었다.
운영에서 실행 하나는 이보다 훨씬 오래 자리를 잡는다.

### pool 은 plugin 으로 키울 수 있다

hook 목록에 gateway 가 뜰 때 도는 것은 없다.
대신 gateway 프로세스 안에서 import 되는 plugin 모듈이 `BaseEventLoop.run_in_executor` 를 감싸,
기본 executor 가 처음 필요해지는 순간에 원하는 크기의 것을 먼저 꽂아 넣으면 된다.

**Hermes core 를 고치지 않으므로 ADR-001 의 제약 안이다.**
plugin 은 `plugins.enabled` 에 이름이 있어야 로드된다. 그 키가 없으면 어떤 사용자 plugin 도 켜지지 않는다.
이 저장소의 `hermes/plugins/` 에는 이 plugin 이 없다.

pool 을 64 로 키우면 실행 64개가 모두 즉시 시작했다.
막힌 곳이 pool 뿐이었다는 뜻이다.

### pool 말고 걸리는 것

| 무엇 | 어느 단위 | 값 | 실행에 걸리는가 |
| --- | --- | --- | --- |
| `gateway.api_server.max_concurrent_runs` | listener | 설정값. 기본 10 | 걸린다. 429 |
| loop 기본 thread pool | 프로세스 | `min(32, cpu + 4)` | 걸린다. 기다린다 |
| `GatewayRunner` 자체 executor | 프로세스 | `max_workers=10` 고정 | 걸리지 않는다. platform turn 전용이다 |
| httpx `max_connections` | client 하나 | 100 | 걸리지 않았다 |
| `gateway.max_concurrent_sessions` | listener | 설정값 | 걸리지 않는다 |

httpx 한도는 client 하나를 기준으로 세므로 listener 전체의 한도가 되지 않는다.
실행 120개가 동시에 220개의 LLM 호출을 냈을 때도 이 한도에 걸리지 않았다.

**profile 단위로 거는 한도는 없다.**
위의 것이 모두 listener 나 프로세스 단위다.
한 profile 이 자리를 다 쓰면 다른 profile 이 그만큼 못 쓴다.

Control Plane 은 이 자리를 사용자마다 나눠 쓰게 한다. 한 사용자가 동시에 맡기는 실행을 사용자 실행 한도로 묶는다([`docs/features/execution.md`](../../docs/features/execution.md)).
그 한도는 Control Plane 이 제출하는 실행만 센다. native 하위 에이전트와 cron 은 세지 않는다.
Control Plane 이 대기 시간을 넘겨 먼저 끝낸 run 은 Hermes 가 끝냈다고 답할 때까지 그 사용자의 자리로 센다.
이 자리는 Control Plane 메모리에만 있어, Control Plane 이 다시 뜨면 그 run 이 Hermes 에서 끝나기 전이라도 세지 않는다.

### 스레드를 늘리는 비용

스레드 자체는 거의 들지 않는다. idle 스레드 하나의 상주 메모리는 수십 kB 다.
늘어나는 것은 동시에 도는 실행이 쥔 것이다.

pool 을 키우는 것만으로는 메모리가 늘지 않는다. 스레드를 필요할 때 만들기 때문이다.
드는 것은 동시에 도는 실행 수가 정한다.

동시 실행 하나는 적어도 약 3.1 MB 를 쓰고, profile 하나가 처음 실행할 때 약 50 MB 가 한 번 는다.
**3.1 MB 는 하한이다.** 스킬만 올리고 MCP 서버와 대화 기록이 없는 profile 에서 측정한 값이다.

### 측정을 되풀이할 때

- 측정용 gateway 는 운영과 다른 `HERMES_HOME` 을 임시 디렉터리에 따로 두고 s6 에 등록하지 않는다. 운영 gateway 의 상태와 섞이면 결과를 믿을 수 없다.
- s6 gateway 가 이미 있으면 `HERMES_HOME` 이 달라도 기동을 거부하므로 `--force` 가 필요하다. `--force` 로 띄운 gateway 를 `kill -9` 로 내리지 않는다. 운영 gateway 가 함께 재시작된 적이 있다. TERM 한 번이면 내려간다.
- 공식 이미지는 root 로 띄운 gateway 를 거부한다. root 가 아닌 사용자로 띄운다.
- LLM 대신 OpenAI 형태로 답하는 stub 을 두고 요청마다 고정 시간(예: 30초)을 붙잡게 한다. 그 경계로 어느 실행이 언제 시작했는지 읽을 수 있다.
- 부하 대역의 `POST /v1/runs` 제출이 동기로 막히면 한도를 채울 만큼 동시에 들어가지 못한다. 운영의 제출은 기다리지 않고 `run_id` 와 `status: started` 를 돌려준다. 이 차이를 맞추기 전에는 429 가 나오지 않거나 조회가 실패한 것을 pool 의 성질로 읽지 않는다.
- 정리 스크립트가 띄운 프로세스 번호(`$!`)가 비면 TERM 을 아무에게도 보내지 못해 gateway 가 고아로 남는다. 명령줄 탐색으로도 내릴 수 있게 한다.

## 위임

### 내장 delegation 이 실제로 하는 것

아래 실측은 v0.21.0 에서 운영 profile 과 격리한 측정용 profile 로 했다.

`delegate_task` 가 그 도구다. 부모 실행이 이 도구를 부르면 Hermes 가 자식 agent 를 만든다.

#### 부모의 `instructions` 는 자식에게 가지 않는다

**Control Plane 이 `instructions` 로 주입한 Memory 는 subagent 로 넘어가지 않는다.**

부모에게 `instructions` 로 `MEMORYMARK-7731` 로 시작하는 줄 하나를 주고
부모와 자식에게 같은 질문을 던졌다.

| 누구에게 물었나 | 답 |
| --- | --- |
| 부모 | `MEMORYMARK-7731: the user is allergic to peanuts.` |
| 자식 | `NOMARK` |

`tools/delegate_tool.py` 의 `_build_child_agent` 가 자식을 `skip_memory=True` 와
`skip_context_files=True` 로 만들고 자식 system prompt 를 goal 로 새로 쓰기 때문이다.
소스와 실행이 같은 결과를 낸다.

자식의 도구는 `_build_child_agent` 에서 부모 toolset 과의 교집합만 받고,
`DELEGATE_BLOCKED_TOOLS` 에 포함된 `memory` 등의 도구를 한 번 더 뺀다.

privacy 로는 이쪽이 안전하다. 부모에 넣은 개인 Memory 가 자식으로 새지 않는다.
대신 자식에게 무언가를 알려야 하면 goal 본문에 직접 적어야 하고,
그 본문은 Control Plane 이 무엇을 담을지 정해야 한다.

#### 자식 도구의 허용 범위

v0.21.0 의 `tools/delegate_tool.py` 의 `_build_child_agent` 를 읽어 확인했다.

| 항목 | 동작 |
| --- | --- |
| toolset | 모델이 지정한 목록도 부모의 `enabled_toolsets` 와 교집합을 만든다. 부모에 없는 `terminal`, `file` 을 받지 못한다 |
| 항상 제외하는 도구 | `DELEGATE_BLOCKED_TOOLS` 의 `delegate_task`, `clarify`, `memory`, `send_message`, `cronjob_manage` |
| orchestrator 예외 | `delegation` 을 다시 넣는다. 위임 시작에는 부모의 `delegation` 이 필요하고, 깊이는 `delegation.max_spawn_depth` 가 제한한다 |
| `disabled_toolsets` | 부모 목록을 물려준다. orchestrator 는 여기서 `delegation` 만 뺀다 |
| MCP | `delegation.inherit_mcp_toolsets` 가 참이면 부모 MCP toolset 을 유지한다. 기본값은 참이다 |
| 기억·문맥 파일 | `skip_memory=True`, `skip_context_files=True` 로 자식을 만든다 |
| 위험한 명령 | `delegation.subagent_auto_approve` 가 거짓이면 자식 스레드에 자동 거절 콜백을 건다. gateway 세션은 승인 큐를 쓴다는 주석이 있다 |

API server 의 `_create_agent` 는 `disabled_toolsets` 를 따로 넘기지 않지만,
이미 제외한 도구 목록을 `enabled_toolsets` 로 주므로 자식의 교집합도 같은 범위를 지킨다.
부모의 `approvals.mode: off` 가 자식에게 그대로 적용되는지와 자식 스킬 색인 구성은
이 조사에서 확인하지 못했다.
자식 system prompt 는 `_build_child_system_prompt` 가 별도로 만든다.

근거는 [v0.21.0 delegate_tool.py](https://github.com/NousResearch/hermes-agent/blob/v2026.8.31/tools/delegate_tool.py) 다.

#### 자식은 한 단계 더 자식을 만든다

**ADR-016 이 적은 「leaf 한 단계만 된다」 는 이 버전에서 맞지 않는다.**

배포 설정과 같은 `max_spawn_depth: 1` 과 `orchestrator_enabled: true` 로 두고
부모에게 `role=orchestrator` 로 자식을 만들게 한 뒤 그 자식이 다시 위임하도록 시켰다.
손자가 실제로 떴고 SSE 에 아래가 찍혔다.

```json
{"event": "subagent.start", "subagent_id": "sa-0-4cac90e1",
 "parent_id": "sa-0-58f24d06", "depth": 1,
 "child_session_id": "20260918_104940_bf9c03"}
```

자식이 `depth: 0`, 손자가 `depth: 1` 이고 `parent_id` 가 둘을 잇는다.
`max_spawn_depth` 는 상한이 없는 설정 값이라 더 깊게도 열 수 있다.

#### 자식 토큰을 SSE 로 받을 수 있다

**ADR-016 이 「자식 토큰을 잃는다」 고 적은 근거가 이 버전에서는 바뀐다.**
부모 Runs API 의 `usage` 에 자식이 더해지지 않는 것은 그대로다.
다만 자식의 사용량이 사건으로 따로 나온다.

| 사건 | 함께 오는 칸 |
| --- | --- |
| `subagent.start` | `subagent_id`, `child_session_id`, `delegation_id`, `parent_id`, `depth`, `model`, `goal`, `task_index`, `task_count` |
| `subagent.complete` | 위의 것 전부와 `status`, `summary`, `duration_seconds`, `input_tokens`, `output_tokens`, `reasoning_tokens`, `api_calls`, `cost_usd`, `files_read`, `files_written` |

실측한 `subagent.complete` 하나다.

```json
{"status": "completed", "duration_seconds": 0.93,
 "input_tokens": 10337, "output_tokens": 68,
 "reasoning_tokens": 0, "api_calls": 1, "cost_usd": 0.0,
 "child_session_id": "20260918_104444_6c460e"}
```

**`cost_usd` 를 비용 근거로 쓰지 않는다.** 가격표가 없는 provider 에서는 0 으로 온다.
위 실행도 토큰은 맞게 왔지만 `cost_usd` 가 0 이었다. 토큰을 저장하고 비용은 우리가 환산한다.

`child_session_id` 로 `GET /api/sessions/{id}` 를 부르면 cache 토큰까지 나온다.
응답은 `object=hermes.session` 과 중첩된 `session` 객체이고, 아래는 그 안의 필드다.
`session.parent_session_id` 가 부모 session 을 가리킨다.
Runs API 가 처음 만든 session 은 첫 실행의 `run_id` 를 쓸 수 있지만,
이어지는 실행의 번호와 부모 session 번호를 같은 것으로 취급하면 안 된다.

```json
{"id": "20260918_103840_ac00e1", "source": "subagent",
 "input_tokens": 9193, "output_tokens": 79, "cache_read_tokens": 960,
 "reasoning_tokens": 75, "estimated_cost_usd": 0.0054140625,
 "parent_session_id": "run_e4f5549bd5b04545985670d3457d830c"}
```

v0.21.0 과 v0.21.3 Runs API 의 `usage` 에는 cache 칸이 없지만 이 경로에는 있다.

#### 최상위 위임의 완료 사건은 부모 스트림으로 받지 못할 수 있다

`delegate_task` 는 `background` 인자를 무시하며, 그 인자는 도구 스키마에서도 빠져 있다.
최상위 위임은 항상 비동기로 돌고 **부모 실행이 자식보다 먼저 끝난다.**
그 시점에 SSE 가 닫혀 `subagent.start` 는 받지만 `subagent.complete` 와 자식 토큰은 받지 못한다.
`background=false` 로 시켜도 동기 위임을 보장할 수 없다.

실행의 자식 목록을 돌려주는 Runs 경로는 없다.
자식 session 의 조회 형식과 사용량 보완 방법은 아래 「자식 session 으로 결과와 토큰을 보완한다」 를 따른다.

그러므로 부모 SSE 를 끝까지 받아도 자식 사용량이 모두 기록된다고 보장할 수 없다.
완료 사건을 받지 못한 자식의 결과와 토큰은 모르는 값으로 남긴다.
부모가 끝났다는 이유로 자식이 중지됐다고 판정하거나 토큰을 0 으로 채우지 않는다.

#### API 위임 결과는 delivery 기록으로 남는다

v0.21.0 배포본과 v0.21.3, v0.21.5 소스를 대조했다.
**API 비동기 위임 완료는 부모의 새 모델 turn 이나 새 run 을 자동으로 만들지 않는다.**
`APIServerAdapter.supports_async_delivery` 는 거짓이다.
완료 결과는 `_inject_watch_notification` 에서 `gateway/wake.py` 의
`persist_delegation_delivery` 로 가며, 부모 session 에 `role=user`,
`display_kind=async_delegation_complete` 인 메시지를 한 번 기록한다.
실제 사용자의 새 질문과 구분해야 하는 delivery 기록이다.

다음 클라이언트 turn 이 server history 를 읽을 때 이 결과를 문맥으로 사용한다.
일반 백그라운드 작업 알림의 `_self_post_chat_completion` 과 다른 분기다.
제품에서 부모의 이어 답을 원하면 Control Plane 이 새 run 을 명시적으로 제출하고 추적해야 한다.
upstream 이슈에서 자동 후속 답을 관찰했다는 기록만으로 API 경로도 그렇다고 판정하지 않는다.

v0.21.3 과 v0.21.5 에서 비동기 위임 여부는 session history 를 어떻게 쓰는지도 따른다.
Runs 가 server history 를 다시 읽는 session 을 만들면 `session_history_delivery` 를 전달한다.
`_resolve_async_wake_sid` 는 그 권한이 있는 raw session 에서 detached 결과를 허용한다.
caller 가 history 를 직접 주거나 response chain snapshot 을 쓰는 요청,
finite single-query 요청은 동기로 돌아갈 수 있다.
내부 변수 이름의 wake 는 API 모델 자동 실행을 뜻하지 않는다.

근거는 [v0.21.3 wake.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/gateway/wake.py),
[delegate_tool_dispatch.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/tools/delegate_tool_dispatch.py),
[API 위임 계약 테스트](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/tests/gateway/test_api_delegation_delivery_contract.py) 다.
조사에서 upstream 테스트를 읽었지만 실행하지는 않았다.

#### 자식 session 으로 결과와 토큰을 보완한다

v0.21.0 배포본에서 원인을 조사하고 v0.21.3 과 v0.21.5 의 조회 형식도 확인했다.
부모 run 이 끝나면 종료 sentinel 이 SSE 를 닫고 queue 를 제거한다.
뒤늦은 자식 callback 이 원래 스트림을 복구하거나 완료 사건을 재생하는 API 는 없다.
완료한 run 조회 응답에 자식 결과를 덧붙이는 계약도 확인하지 못했다.
소비자는 별도 session 조회로 완료 표시와 사용량을 보완해야 한다.

| 경로 | 얻는 것과 한계 |
| --- | --- |
| `GET /api/sessions/{id}` | 중첩 `session` 의 모델, 부모, 종료 이유, 입력·출력·cache 토큰. `provider` 는 safe key 에 없다 |
| `GET /api/sessions/{id}/messages` | 자식 답 또는 부모에게 전달된 위임 결과. `display_kind` 는 나오지만 `display_metadata` 는 나오지 않는다 |
| `GET /api/sessions` | v0.21.3·v0.21.5 는 `include_children=true` 와 `source=subagent` 를 지원한다. 기본은 자식을 제외한다. 목록은 `object=list`, `data`, `limit`, `offset`, `has_more` 다 |
| 내부 위임 영구 기록 | `async_delegation.py` 의 `get_durable_delegation` 에 상태, 작업별 결과, 모델, 토큰과 소요 시간이 있다. 공개 HTTP endpoint 는 아니다 |
| 자식 로그·manifest | 답 일부와 종료 상태가 있으나 보관 기간과 글자 제한이 있어 영구 기록을 대신하지 못한다 |

메시지 목록은 `object=list`, 실제 continuation 의 `session_id`, `data`, `pagination` 이다.
기본 최신 500개이며 `order`, `limit`, `offset` 을 받는다.
부모 메시지의 delivery 식별자로 중복 수입을 막을 수 있지만,
메시지 API 만으로 작업별 위임 식별자와 토큰을 모두 얻을 수 있다고 가정하면 안 된다.
자식 session 의 `agent_close` 만으로 작업 성공을 단정하지 않는다.

**완료 사건의 입력 토큰과 session 입력 토큰은 합산 기준이 다르다.**
`delegate_tool.py` 의 `_run_single_child` 는 `session_prompt_tokens` 를 사건의 `input_tokens` 로 보낸다.
session 은 cache 를 뺀 `input_tokens` 와 cache read·write 를 따로 누적한다.
사건의 입력 토큰과 맞추려면 session 의 입력, cache read, cache write 를 합산한다.
비용 계산에서는 cache 토큰에 일반 입력 단가를 일괄 적용하지 않는다.
부모 usage 에 자식 usage 가 포함되지 않으므로 부모와 각 자식의 기록을 중복 없이 더한다.
조회하지 못한 사용량은 모르는 값으로 남긴다.

**종료된 자식 session 은 다시 조회해도 토큰과 종료 시각이 같다.**
실행 중인 session 의 값 변화는 확인하지 않았다.

근거는 [v0.21.3 session 직렬화](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/gateway/platforms/api_server.py) 의
`_session_response`, `_message_response`, `_handle_list_sessions` 와
`agent/conversation_loop.py`, `agent/turn_finalizer.py` 의 토큰 누적이다.

#### 자식 session 의 provider 는 저장소에만 있다

v0.21.5(`v2026.9.24`)의 소스로 확인했다.

| 확인한 것 | 근거 |
| --- | --- |
| API server 의 session 응답은 정해 둔 칸만 내보내고 그 목록에 provider 가 없다. 단건 조회와 목록이 같은 함수를 쓴다 | [`gateway/platforms/api_server.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/gateway/platforms/api_server.py) 의 `_session_response` 의 `safe_keys` |
| session 저장소의 `sessions` 표에 `billing_provider` 칸이 있다. profile 마다 저장소가 따로 있다 | [`hermes_state_common.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/hermes_state_common.py) 의 `SCHEMA_SQL` |
| session 을 만들 때 요청한 경로가 먼저 적힌다. 그 경로가 실패하고 fallback 이 성공하면, 처음으로 사용량이 잡힌 호출의 모델과 provider 로 그 줄을 고친다. 그 뒤에는 줄을 바꾸지 않는다 | [`hermes_state_usage.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/hermes_state_usage.py) 의 `update_token_counts` 의 `first_accounted_route` |
| 호출마다의 모델과 provider 는 `session_model_usage` 표에 짝으로 쌓인다. 본 대화의 호출은 `task` 가 빈 줄이고, 보조 호출은 `task` 에 이름이 있다 | 같은 파일의 `_record_model_usage` 와 [`hermes_state_sessions.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/hermes_state_sessions.py) 의 `get_recent_session_model_route` |
| `subagent_stop` hook 은 부모와 자식의 session 번호, 역할, 요약, 상태, 도구 이력, 소요 시간을 받는다. provider 는 받지 않는다 | [`tools/delegate_tool_results.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/delegate_tool_results.py) 의 `_fire_subagent_stop_hooks` |

**`sessions` 한 줄은 모델과 provider 의 짝을 하나만 담는다.**
자식이 도는 중에 모델이나 provider 가 바뀌면 그 줄의 값은 처음 것이고, 바뀐 뒤의 호출은 `session_model_usage` 에만 남는다.
그래서 한 줄의 값으로 금액을 환산하려면 `session_model_usage` 에서 `task` 가 빈 줄의 짝이 하나뿐인지 함께 본다.

운영 집계에서는 끝난 자식 줄 모두 `billing_provider` 가 채워져 있었고, 도중에 provider 가 바뀐 줄은 없었다.

대시보드도 이 저장소를 읽는다. 대시보드는 gateway 와 다른 프로세스이고 profile 마다 저장소 파일을 따로 연다.
근거는 [`hermes_cli/web_server_sessions.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/hermes_cli/web_server_sessions.py) 의 `_open_session_db_for_profile` 이다.
이 함수의 읽기 전용 열기는 저장소가 비었거나 스키마가 낡았으면 쓰기 연결을 한 번 열어 고친다.

Control Plane 은 이 값을 대시보드 plugin 의 읽기 경로로 받는다([ADR-067](../../backend/docs/adr/ADR-067-native-하위-에이전트의-provider-는-대시보드-plugin-이-session-저장소에서-읽어-준다.md)).
plugin 은 Hermes 의 저장소 클래스를 쓰지 않고 SQLite 의 읽기 전용 방식으로 파일을 직접 연다.
**Hermes 버전을 올릴 때 위 표의 표 이름과 칸 이름을 다시 확인한다.**

#### 자식의 모델은 부모의 것이 아니다

자식 모델은 `delegation.provider` 와 `delegation.model` 이 정하고,
비어 있으면 부모가 아니라 별도 기본값으로 떨어진다.
실행별 비용을 보려면 이 값을 명시해야 한다.

#### 제한 시간은 우리가 정하지만 상한은 있다

| 항목 | 값 |
| --- | --- |
| 호출 하나의 제한 시간 | 기본 300초 |
| 서버마다 바꾸는 자리 | 그 서버 설정의 `timeout` |
| 전역으로 바꾸는 자리 | `timeouts.mcp.tool_call` |
| 연결 제한 시간 | 기본 60초 |

제한 시간을 넘긴 호출은 `MCP call failed: TimeoutError` 도구 결과로 돌아오고, 실행 자체는 정상 종료한다.

**실행을 기다리는 도구는 이 시간 안에 끝나야 한다.**
오래 걸리는 작업을 그 안에서 기다리면 제한 시간에 걸린다.
아래 「기다리는 도구는 실제로 끊긴다」 를 본다.

#### 재귀를 막는 자리가 Hermes 에 없다

도구 안에서 다시 Runs API 를 부르게 하면 깊이가 4까지 올라가도 Hermes 는 개입하지 않았다.

Hermes 는 그 도구가 자기를 다시 부른다는 것을 알지 못한다.
도구 호출은 그저 외부 HTTP 호출이다.
**멈추는 자리를 Control Plane 이 가져야 한다.** 실행 트리의 깊이를 우리가 세고 우리가 거절한다.

#### 기다리는 도구는 실제로 끊긴다

위 재귀 측정에서 아래 실행을 기다리던 루트 실행의 도구 호출이 제한 시간에 끊겼다.
**끊긴 뒤에도 그 아래 실행들은 계속 돌아 완료로 끝났다.**
도구 호출이 끊기는 것과 그 도구가 시작한 실행이 멈추는 것은 별개다.

겹쳐 도는 실행은 한 gateway 와 provider 에 몰린다.
`gateway.api_server.max_concurrent_runs` 의 기본값이 10 이고 넘으면 429 를 준다.

그래서 이 구조를 쓴다면 도구가 실행이 끝날 때까지 기다리게 만들지 않는다.
실행 번호를 바로 돌려주고 진행은 따로 묻는 형태여야 한다.

#### 취소가 아래로 내려가지 않는다

`POST /v1/runs/{run_id}/stop` 은 동작한다.
`{"status": "stopping"}` 을 주고 잠시 뒤 조회하면 `cancelled` 다.

**그러나 그 실행의 도구가 시작한 실행은 멈추지 않는다.**
루트 실행을 취소한 뒤에도 그 도구가 만든 아래 실행이 완료로 끝나고
다시 그 아래를 시작하는 것을 실측했다.
취소를 아래로 전파하는 것도 Control Plane 의 몫이다.

모델이 부른 `delegate_task` 자식도 같다. [`hermes/plugins/fos-ctx/README.md`](../plugins/fos-ctx/README.md#하위-에이전트는-부모-run-보다-오래-산다) 의 「하위 에이전트는 부모 run 보다 오래 산다」 대로 background 자식은 부모 run 에서 떨어져 나가 따로 돈다.
중지한 뒤 자식이 실제로 계속 도는지는 실행으로 확인하지 않았다. 아래는 소스로 확인한 것이다.

##### native 하위 에이전트를 멈추는 길

v0.21.5(`v2026.9.24`)의 소스를 읽어 확인했다.

| 확인한 것 | 근거 |
| --- | --- |
| `POST /v1/runs/{run_id}/stop` 은 부모 agent 에 hard interrupt 를 걸고, 그 interrupt 는 부모에 붙은 자식에게만 내려간다. 동기 위임 자식(오케스트레이터 자식이 부른 깊이 1 이상의 위임)은 붙어 있어 함께 멈춘다 | [`agent/interrupt_control.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/agent/interrupt_control.py) 의 `interrupt`, [`tools/delegate_tool_child_run.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/delegate_tool_child_run.py) 의 `_attach_child` |
| background 자식은 dispatch 직전에 부모에서 떼어지고 부모의 interrupt 를 따르지 않게 돈다. 그래서 부모 run 의 중지가 닿지 않는다 | [`tools/delegate_tool_dispatch.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/delegate_tool_dispatch.py) 의 `_dispatch_background`(`_detach_child`, `honor_parent_interrupt=False`) |
| API server 에 background 자식을 멈추는 HTTP 경로가 없다. run 경로는 `events`, `approval`, `steer`, `stop` 뿐이다. 대시보드에도 없다 | [`gateway/platforms/api_server_runs.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/gateway/platforms/api_server_runs.py) 의 `_http_routes` |
| `subagent_stop` hook 은 자식이 끝난 뒤 오는 알림이다. 반환값은 버려져 자식을 멈추는 수단이 아니다 | [`tools/delegate_tool_results.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/delegate_tool_results.py) 의 `_fire_subagent_stop_hooks` |
| `pre_tool_call` hook 이 `{"action": "block"}` 을 돌려주면 도구 호출 한 번을 거절한다. 자식 실행을 끝내지는 않는다 | [`hermes_cli/plugins.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/hermes_cli/plugins.py) 의 `_get_pre_tool_call_directive_details` |
| background 자식을 멈추는 함수는 `tools.async_delegation.interrupt_for_session(parent_session_id=...)` 이다. 대화형 gateway 의 `/stop` 이 이것을 부르고, API server 는 부르지 않는다. 공개 plugin API 가 아니다 | [`tools/async_delegation.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/async_delegation.py) |
| turn 이 끝나면 `on_session_end` hook 이 `session_id`, `interrupted`, `turn_exit_reason` 을 받는다. `/v1/runs/{run_id}/stop` 으로 멈춘 turn 은 `interrupted_by_user` 다 | [`agent/turn_finalizer.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/agent/turn_finalizer.py), [`agent/turn_iteration_prep.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/agent/turn_iteration_prep.py) |

**그래서 core 를 고치지 않고 멈출 수 있는 길은 하나이고 조건이 붙는다.**
profile 플러그인이 `on_session_end` 에서 `interrupted` 이고 `turn_exit_reason` 이 `interrupted_by_user` 일 때 `interrupt_for_session(parent_session_id=session_id)` 를 부르는 것이다.
gateway 의 `/stop` 과 같은 함수이고, 부모 session 이 정확히 같은 자식만 멈춘다. Control Plane 이 대화마다 고유한 session 을 보내므로 다른 사용자의 자식을 멈추는 길은 없다.

이 길의 한계다.

- **부모 run 이 이미 끝났으면 멈추지 못한다.** 중지가 아무것도 하지 않고 hook 도 다시 오지 않는다. background 자식은 대개 이 경우다
- 공개 API 가 아닌 내부 함수에 기댄다. Hermes 버전을 올릴 때마다 함수 이름과 인자를 다시 확인해야 한다
- 압축으로 turn 중간에 session 이 바뀌면 값이 맞지 않아 자식을 놓친다. 엉뚱한 자식을 멈추지는 않는다
- 한 프로세스가 여러 profile 을 multiplex 하면 기록이 공유된다. 격리는 session 이 고유한 것에만 기댄다

**이 저장소는 이 길을 구현하지 않는다.** 플러그인은 이 저장소의 `hermes/plugins/fos-ctx/` 가 갖고, 이 길은 그 플러그인의 후속 작업 후보다.
그 전까지 Control Plane 이 막는 것은 아래 표와 같다. 멈춘 turn 의 자식이 사용자의 권한을 쓰는 길은 이미 막혀 있다([ADR-037](../../backend/docs/adr/ADR-037-hermes-하위-에이전트-session-의-주인은-만들-때-등록한-줄로-정한다.md)).

그래서 Control Plane 은 지금 이만큼 막는다.

| 무엇 | 지금 |
| --- | --- |
| 자식의 Control Plane MCP 호출(`memory_read`, `artifact_write`, `agent_list`, `agent_delegate`, `agent_status`, `agent_stop`, `follow_up_propose`, `memory_remember`) | origin 실행이나 그 루트 실행이 `CANCELLED` 면 거절한다([ADR-037](../../backend/docs/adr/ADR-037-hermes-하위-에이전트-session-의-주인은-만들-때-등록한-줄로-정한다.md)). 다른 거절과 같은 도구 결과다 |
| 자식의 Hermes 자체 도구(웹 검색, 터미널 등) | 막지 못한다 |
| 자식 run 자체 | 멈추지 못한다. 동기 위임 자식만 부모 run 의 중지와 함께 멈춘다. background 자식을 멈추는 길은 위 「native 하위 에이전트를 멈추는 길」 에 있고 구현하지 않았다 |

#### 취소한 실행의 조회 응답

v0.21.0 에서 실제 중지를 한 번 왕복시켜 확인했다.
v0.21.5 의 중단·미완료 응답 차이는 [「Runs 응답과 사건의 버전 차이」](hermes-contract.md#runs-응답과-사건의-버전-차이) 에 있다.

| 무엇 | 실측 |
| --- | --- |
| `GET /v1/runs/{run_id}` 의 `status` | `cancelled` |
| 그 응답의 `output` | 비어 있다 |
| 그 응답의 `usage` | 비어 있다. 그래서 중지한 실행의 토큰 기록도 빈다 |
| 그 응답의 `session_id` | 그 대화의 session 이다. 중지해도 바뀌지 않는다 |
| 사건 스트림 | 중지를 보낸 뒤 10초 안에 닫혔다 |

**멈춘 자리까지의 답은 조회 응답에서 얻을 수 없다.** 스트림으로 받은 조각을 모아 둔 것만이 그 답이다.
[ADR-021](../../backend/docs/adr/ADR-021-중지한-답은-멈춘-자리까지-남긴다.md) 이 뒤받침으로 둔 경로가 실제로 쓰이는 경로다.

**새 대화의 session 은 Control Plane 이 첫 turn 전에 정한 `fos-<uuid>` 다.**
뒤의 turn 은 같은 값을 이어 쓰고, 첫 turn 을 중지해도 그 값이 그대로다([ADR-031](../../backend/docs/adr/ADR-031-mcp-호출의-부모-실행은-profile-플러그인이-서명한-루트-session-으로-잇는다.md)).
중지한 turn 뒤에도 session 이 같아서 다음 turn 은 중지 전의 맥락을 이어 받는다.

중지한 실행은 그 session 으로 실제로 돈 모델을 읽지 못했다. Control Plane 은 그때 요청에 보낸 모델을 적는다.

## 도구 hook 과 승인

`pre_tool_call` hook 으로 MCP 도구 호출을 막을 때 Hermes 가 지키는 것과 지키지 않는 것이다.
2026-10-01 에 `v2026.9.24`(제품 판 `0.21.5`)의 소스를 읽고, 격리한 환경에서 stdio 대역 MCP 서버로 실행해 확인했다.
이 계약 위에 세운 결정은 [ADR-049](../../backend/docs/adr/ADR-049-커넥터-도구-호출은-profile-plugin-의-hook-이-control-plane-에-물어-판정한다.md) 과 [ADR-050](../../backend/docs/adr/ADR-050-커넥터-쓰기는-control-plane-이-승인-줄을-저장하고-승인한-인자로-한-번만-실행한다.md) 에 있다.

실제 모델 turn, native `delegate_task` 의 전체 왕복, 공유 gateway 의 전체 HTTP 왕복은 실행하지 않았다. 그 셋은 소스로만 확인했다.

### MCP 도구의 등록 이름

Hermes 는 MCP 도구를 `mcp__<서버>__<도구>` 로 등록한다.

- 서버 이름과 도구 이름에서 `[A-Za-z0-9_]` 밖의 글자를 모두 `_` 로 바꾼다. `-` 도 바뀐다
- 이은 이름이 64자를 넘으면 앞 55자에 `_` 와 그 이름 전체를 SHA-256 한 16진수의 앞 8자를 붙인다

**등록 이름에서 원래 이름을 되찾을 수 없다.** 글자를 바꾸고 줄이므로 서로 다른 도구가 같은 등록 이름이 될 수 있다.
그래서 원래 이름에서 등록 이름을 계산해 대응을 적어 두고, 등록 이름으로 그 표를 찾는다.

근거: [`tools/mcp_tool_schema.py` 의 `mcp_prefixed_tool_name`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/mcp_tool_schema.py#L150)

### hook 이 받는 것

`pre_tool_call` 은 `tool_name`, `args`, `task_id`, `session_id`, `tool_call_id` 를 받는다. `turn_id`, `api_request_id`, `middleware_trace` 도 온다.
`args` 는 JSON 문자열이 아니라 `dict` 다. callback 에 `**kwargs` 를 두면 칸이 늘어도 깨지지 않는다.

| 호출 경로 | hook 이 받는 이름과 인자 | 확인 |
| --- | --- | --- |
| 모델이 MCP 도구를 직접 부른다 | 등록 이름과 그 도구의 인자 | 실행 |
| 중계 도구 `tool_call` 로 MCP 도구 하나를 부른다 | 바깥 이름이 아니라 안쪽 등록 이름과 `arguments` | 실행 |
| `tool_search`, `tool_describe` | 그 이름 그대로. MCP `tools/call` 은 일어나지 않는다 | 소스 |
| native `delegate_task` 의 자식 | 자식도 같은 executor 를 쓴다. `session_id` 는 자식의 것이다 | 소스 |
| 공유 gateway 의 `/v1/runs` | 요청 profile 의 plugin manager 를 고른다 | 소스, profile 둘의 hook 문맥 실행 |
| `execute_code` 안의 도구 호출 | hook 을 거친다. 넘어오는 식별자는 `task_id` 뿐이다 | 소스 |
| plugin 의 `PluginContext.call_mcp` | **hook 을 거치지 않는다.** 서버 정의의 `mcp_allowlist` 만 본다 | 소스 |

중계 한 건에 hook 이 두 번 걸리지 않는다. executor 가 중계를 먼저 풀고 hook 을 적용한 뒤 dispatcher 에는 hook 을 건너뛰라고 넘긴다.

근거: [executor 의 중계 해석](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/agent/tool_executor.py#L392),
[dispatcher 의 중계 처리](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/model_tools.py#L707),
[plugin 의 직접 MCP 호출](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/hermes_cli/plugins.py#L509),
[코드 실행 RPC](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/code_execution_rpc.py#L28)

### `tool_call` 의 단건 제한

`v2026.9.24` 의 `tool_call` 은 `calls` 배열을 받고, 각 항목은 `name` 과 `arguments` 를 갖는다.
로컬 MCP 도구(`mcp__...`)를 부를 때는 배열에 정확히 한 항목만 넣는다.
같은 서버의 읽기 도구라도 여러 개를 묶지 않고 도구마다 별도 `tool_call` 로 부른다.
로컬 도구가 섞인 다건 배열은 실제 도구 실행 전에 `exactly one entry for local tools` 오류로 거절된다.

`connectors__` 이름을 쓰는 HTTP 원격 도구 서버의 호출만 묶을 수 있다.
MCP 연결 전송 방식이 HTTP 라도 Hermes 에 로컬 도구로 등록된 `mcp__...` 는 단건 규칙을 따른다.
두 이름을 한 배열로 섞을 수도 없다.

근거는 [호출 스키마](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/tool_search.py#L310-L334),
[중계 검증](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/tool_search.py#L543-L572)이다.
Control Plane 이 이 제한을 공통 실행 지침에 싣는 범위는 [문맥 묶음](../../docs/features/memory.md#공통-실행-지침)이 갖는다.

### 막히는 것과 통과하는 것

같은 stdio MCP 서버에서 실행으로 확인했다.

| hook 의 결과 | MCP 요청 | 모델이 받는 도구 결과 |
| --- | --- | --- |
| `{"action": "block", "message": "<글>"}` | 없다 | `{"error": "<글>"}` |
| `None` | 나간다 | 서버의 결과 |
| `{"action": "block"}` (글 없음) | **나간다** | 서버의 결과. 유효한 차단이 아니라 무시된다 |
| callback 이 예외를 던진다 | 없다 | callback 이름, 예외 종류와 본문 일부가 든 오류 |
| callback 이 제한 시간을 넘긴다 | 없다 | 시간 초과 오류 |
| hook dispatcher 자체가 예외를 낸다 | **나간다** | 서버의 결과. 바깥 처리가 예외를 삼킨다 |

**hook 은 모든 실패에서 닫히지 않는다.**
callback 안의 예외는 호출을 막지만, plugin 을 읽지 못했거나 dispatcher 가 실패하면 호출이 판정 없이 나간다.

`block` 은 도구 한 번의 오류 결과다. run 을 실패로 바꾸지 않고 자식을 끝내지도 않는다. 모델은 그 결과를 읽고 turn 을 이어 간다.

예외의 본문 일부가 모델에게 가므로 callback 은 비밀값이 섞일 수 있는 예외를 그대로 던지지 않는다. 잡아서 정해 둔 글로 막는다.

### 제한 시간

| 설정 또는 자원 | 뜻 |
| --- | --- |
| `plugins.hook_callback_timeout` | callback 하나의 제한. 기본 30초, 최대 600초. 0 은 제한 없음이다 |
| callback 여럿 | 차례로 돈다. 30초가 hook 전체의 상한은 아니다 |
| 제한을 넘긴 callback | 스레드를 강제로 끝내지 않는다. 그 안의 HTTP 요청은 계속 갈 수 있다 |
| 넘긴 뒤 | 같은 manager 의 callback 을 기본 60초 동안 억제하고 그동안 사전 hook 은 막는다 |
| run executor | API run 은 공유 thread pool 의 자리를 쥔다. hook 이 오래 기다리면 pool 과 run 한도가 준다 |

제한을 넘긴 뒤에도 요청이 서버에 닿을 수 있으므로, hook 이 부르는 쪽은 같은 호출이 두 번 와도 줄이 하나여야 한다.
profile 둘의 callback 을 동시에 돌렸을 때 한쪽이 0.4초 걸리는 동안 다른 쪽은 기다리지 않았다.

근거: [callback 차단과 시간 제한](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/hermes_cli/plugins_dispatch.py#L209),
[dispatcher 바깥 예외](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/model_tools.py#L765),
[executor 바깥 예외](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/agent/tool_executor.py#L651)

### 도구 결과를 바꾸는 hook

`transform_tool_result` hook 은 도구 결과가 모델의 문맥에 들어가기 전에 그 글을 바꾼다.
2026-10-05 에 같은 판의 소스로 확인했다. 실행으로는 확인하지 않았다.
바인딩 profile 의 `fos-ctx` 가 이 hook 으로 커넥터 도구 결과를 `<external-data>` 로 감싼다([ADR-083](../../docs/adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md)).

| 확인한 것 | 내용 |
| --- | --- |
| 도는 때 | `post_tool_call` 뒤, 결과가 문맥에 들어가기 전이다 |
| 받는 것 | `tool_name`, `args`, `result`, `task_id`, `session_id`, `tool_call_id`, `turn_id`, `api_request_id`, `duration_ms`, `status`, `error_type`, `error_message` |
| 결과를 바꾸는 값 | callback 이 돌려준 글 가운데 처음 것이 결과가 된다. 글이 아닌 값과 `None` 은 무시한다 |
| 실패 | 닫히지 않는다. callback 이 예외를 던지거나 제한 시간을 넘기면 그 callback 을 건너뛰고 원래 결과가 간다 |
| `pre_tool_call` 이 막은 호출 | 이 hook 에 닿지 않는다. dispatcher 와 executor 가 막은 결과를 그 앞에서 돌려준다 |
| 도구가 예외를 던졌다 | 이 hook 에 닿지 않는다. Hermes 가 만든 오류 글이 그대로 간다 |
| 중계 도구 `tool_call` | dispatcher 가 안쪽 등록 이름으로 다시 들어가므로 hook 도 안쪽 이름을 받는다 |

**MCP 결과에는 Hermes 가 따로 `<untrusted_tool_result>` 감싸기를 한다.**
이 hook 이 돌고 난 뒤, 결과를 대화 메시지로 만들 때 이름이 `mcp_` 로 시작하는 도구의 글에 건다.
32자보다 짧은 글은 감싸지 않는다. 본문 안의 `untrusted_tool_result` 표시는 대소문자에 상관없이 `untrusted-tool-result` 로 바꾼다.
그래서 바인딩 profile 의 커넥터 도구 결과는 `<external-data>` 가 안쪽, `<untrusted_tool_result>` 가 바깥쪽이다. hook 이 실패해도 바깥쪽은 남는다.

근거: [`_apply_transform_tool_result_hook`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/model_tools.py#L848),
[dispatcher 가 막은 호출을 돌려주는 자리](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/model_tools.py#L940),
[executor 가 막은 호출에 hook 을 걸지 않는 자리](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/agent/tool_executor.py#L1753),
[hook 의 제한 시간 목록](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/hermes_cli/plugins_dispatch.py#L42),
[`_maybe_wrap_untrusted`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/agent/tool_dispatch_helpers.py#L515),
[감싸는 도구와 32자 하한](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/agent/tool_dispatch_helpers.py#L436)

### 도구를 모델에게서 빼는 설정

`mcp_servers.<서버>.tools.include` 와 `tools.exclude` 가 있다. 접두사가 붙기 전의 원래 도구 이름과 glob 을 받는다.

| 설정 | `read_item`, `write_item` 의 등록 |
| --- | --- |
| `include: []` | 둘 다 등록하지 않는다 |
| `exclude: ["write*"]` | `read_item` 만 등록한다 |
| `include` 와 `exclude` 에 같은 이름 | `include` 가 이긴다 |

이 설정은 모델에 등록할 도구만 줄인다. MCP 서버의 `tools/call` 권한은 바꾸지 않는다.

근거: [도구 필터](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/mcp_tool_registration.py#L210)

### 내장 승인

hook 이 `{"action": "approve", "message": "<사유>", "rule_key": "<키>"}` 를 돌려주면 Hermes 가 그 도구를 사람 승인으로 넘긴다.

| 확인 | 결과 |
| --- | --- |
| 승인 대기 | `approval.request` 사건이 나오고 run 이 `waiting_for_approval` 이 된다 |
| 한 번 승인 | 원래 호출이 나간다 |
| 거절, 시간 초과 | 도구 결과가 차단 글이 된다. run 은 실패가 아니다 |
| `approvals.mode: off` | **같은 hook 이 사람 없이 통과한다** |
| 기다리는 시간 | `approvals.timeout`, 기본 300초 |

- 승인 함수는 도구 이름, plugin 의 글, 승인 키만 받는다. **도구 인자를 받지 않는다**
- session 승인과 영구 승인이 `rule_key` 단위로 쌓인다. 키를 주지 않으면 도구 이름이 키다
- 시간을 넘기면 그 도구는 거절되고 다시 실행되지 않는다

그래서 내장 승인으로는 「승인한 인자 그대로 한 번만 실행한다」 를 보장하지 못한다.

근거: [approve directive](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/hermes_cli/plugins.py#L1934),
[임의 도구 승인 함수](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/approval.py#L1095),
[승인 우회와 저장된 승인](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/approval.py#L951)

### 배포한 뒤 확인할 것

아직 확인하지 못했다. 실행 방법은 운영 저장소가 갖는다.

- 떠 있는 공유 gateway 가 profile 의 `fos-ctx` 를 새 판으로 바꾼 뒤 재시작 없이 새 코드를 읽는지. `__init__.py`뿐 아니라 `connector_policy`와 `hooks` 등 하위 모듈도 새 판인지 확인한다. 파일 비교로 답하는 `policy_hook`만으로는 실행 중인 모듈의 판을 증명하지 못한다
- native 자식과 공유 gateway 에서 실제 커넥터 호출이 hook 을 거치는지. 대역 서버의 호출 기록과 `connector_action` 을 견준다
- Control Plane 이 내려가 있을 때 연결을 붙인 에이전트의 커넥터 도구가 막히는지
- 바인딩 profile 에서 커넥터 도구의 결과가 `<external-data>` 안에 있고 그 바깥을 `<untrusted_tool_result>` 가 감싸는지. 실행 기록의 도구 내용으로 본다
- 대시보드 plugin 을 올린 뒤 카탈로그에 커넥터가 그대로 있는지. 스킬 본문 검증이 새로 생겨, 전에는 나오던 커넥터가 빠질 수 있다
- Control Plane 을 옛 판으로 되돌렸다가 다시 올렸으면 사진을 받는 연결을 한 번 연결 확인한다. 옛 판은 `vision` 을 선언 밖의 도구로 보고 목록에서 뺀다
- 떠 있는 공유 gateway 가 바뀐 `SOUL.md` 를 재시작 없이 다음 실행부터 읽는지. 읽지 않으면 지침 갱신에도 재시작과 관리자 반영 완료가 필요하다
- `file` toolset 없이 `vision` 만 켠 에이전트에서 `vision_analyze` 가 입력에 싣지 못한 사진의 사본 경로를 읽는지. 읽지 못하면 지난 메시지의 사진을 에이전트가 보지 못한다

### 승인 방식 `smart` 는 추론 모델에서 `manual` 과 같아진다

`approvals.mode` 가 `smart` 이면 위험한 모양으로 분류된 명령마다
보조 LLM 에 한 번 물어 `APPROVE`, `DENY`, `ESCALATE` 가운데 하나를 받는다.
`tools/approval.py` 의 `_smart_approve` 가 그 호출을 갖는다.

**그 호출이 `max_tokens=16` 으로 걸려 있다.**
받은 내용을 대문자로 바꿔 세 낱말과 정확히 비교하고, 어느 것과도 맞지 않으면 `escalate` 로 읽는다.
추론 모델은 생각을 먼저 내보내므로 16 토큰에서 잘린 답이 세 낱말 어느 것과도 맞지 않는다.
**그래서 추론 모델에서는 무해한 명령까지 모든 판정이 `escalate` 가 되어 `manual` 과 같은 길로 간다.**
생각을 먼저 내보내지 않는 모델에서는 같은 명령이 `approve` 로 나왔다.

오류로 드러나지 않는다.
토큰이 잘린 쪽은 경고 줄도 남기지 않는다.
보조 LLM 호출이 실패할 때도 예외 처리가 `escalate` 를 돌려주며, 그쪽은 경고 한 줄을 남긴다.
**설정에는 `smart` 라고 적혀 있으므로 설정만 읽어서는 알 수 없다.**

사람이 승인할 자리가 없는 경로에서는 실행이 `waiting_for_approval` 로 멈춘다.
`approvals.timeout` 이 지나면 그 명령이 거절되고, 에이전트는 다른 길을 찾아 실행을 마친다.
**그래서 최종 상태가 `failed` 가 아니라 `completed` 다.**
실행 시간이 `approvals.timeout` 만큼 길어지는 것이 겉으로 드러나는 유일한 신호다.

#### 같은 부류의 계약 둘

**`command_allowlist` 는 프로세스 전역이고 import 시점에 한 번만 읽는다.**
`tools/approval.py` 가 모듈을 읽을 때 한 번 불러 결과를 프로세스 전역 집합에 담는다.
실행마다 다시 읽지 않는다.

한 프로세스가 여러 profile 을 서비스하는 구성이면,
그 프로세스의 Hermes home 이 아닌 profile 의 설정에 적은 항목은 실리지 않고,
그 home 의 설정에 적은 항목은 모든 profile 에 적용된다.
**적어 두어도 아무 일도 일어나지 않으므로 설정을 읽어서는 어느 쪽인지 알 수 없다.**

반면 `approvals.mode` 와 `approvals.deny` 는 판정할 때마다 그 실행의 profile 설정을 다시 읽는다.
값을 바꾸면 프로세스를 다시 띄우지 않아도 반영된다.

**`approvals.mode` 의 값 `off` 는 따옴표가 없으면 YAML 이 거짓으로 읽는다.**
Hermes 가 그 거짓을 다시 `off` 로 되돌려 주므로 결과는 같다.
받는 값은 `manual`, `smart`, `off` 셋뿐이고, 그 밖의 문자열은 경고를 남기고 `manual` 이 된다.

## MCP 프로세스의 환경 값

2026-09-30에 Hermes `v2026.9.14`의 소스를 읽어 판정했다.
운영에서 서로 다른 사용자 토큰으로 실행한 결과는 이 조사에 포함하지 않는다.

### profile별 토큰 전달

공유 gateway의 `_profile_runtime_scope`는 profile의 `.env`를 읽어 secret scope에 넣는다.
프로세스 전체의 `os.environ`을 사용자별 토큰으로 바꾸지 않는다.
[gateway의 profile 문맥](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/gateway/run.py)과
[secret scope의 파일 읽기](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/agent/secret_scope.py)가 근거다.

MCP 설정의 `${VAR}` 참조는 `_interpolate_env_vars`가 현재 profile의 `get_secret`으로 펼친다.
`_build_safe_env`는 일반 profile 비밀값을 자식 프로세스에 모두 물려주지 않는다.
MCP 서버 설정의 `env`에 명시한 값이 자식 프로세스에 전달된다.
[환경 값 처리](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/tools/mcp_tool_config.py)와
[`StdioServerParameters` 생성](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/tools/mcp_tool_transport.py)이 근거다.

따라서 커넥터의 토큰을 profile `.env`에 저장하는 것과 함께,
MCP 서버의 `env`에 그 이름의 `${이름}` 참조가 있어야 한다. 가계부라면 `env.ACCOUNTBOOK_API_TOKEN`에 `${ACCOUNTBOOK_API_TOKEN}` 이다.
토큰 원문을 `.mcp.json`이나 `config.yaml`에 쓰지 않는다.
사용자가 넣는 다른 칸(`connector.json` 의 `fields[].env`)도 같은 방식으로 전달한다.
운영자가 주는 값(`operator_env`)은 profile `.env` 를 거치지 않는다. 대시보드 plugin 이 설치할 때 운영 목록의 값을 서버 정의의 `env` 에 직접 넣는다([커넥터 설치](../../docs/features/connector.md)).
운영자가 주는 비밀은 이 방식으로 넘기지 못한다. `${이름}` 은 그 profile 의 secret scope 에서만 풀리고, 공유 gateway 는 scope 에 없는 이름을 프로세스 env 에서 찾지 않는다(v0.21.5 의 `agent/secret_scope.py` `get_secret`). 그래서 `operator_secrets` 는 지원하지 않는다([ADR-046](../../backend/docs/adr/ADR-046-운영-비밀은-operator-env-와-다른-칸으로-선언하고-자식-mcp-프로세스에만-넣는다.md)).
선택 값을 쓰지 않을 때에는 MCP 설정의 해당 env 항목을 빼거나 값으로 빈 문자열을 직접 쓴다.
profile `.env`의 빈 값을 참조하면 보간 함수가 `${VAR}` 원문을 남긴다.
다른 profile의 값을 기본값으로 쓰지 않는다.

새 profile의 MCP 발견은 profile 문맥을 복사해 별도 실행 스레드로 전달한다.
같은 서버 이름을 여러 profile이 쓰더라도 토큰을 펼치는 문맥이 유지된다.
[새 profile의 MCP 발견](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/gateway/run_profile_reconcile.py)이 근거다.
이 판정은 profile별 설정과 명시적인 환경 변수 참조를 전제로 한다.

### 설정 저장과 gateway 연결 확인

`GET /v1/toolsets`는 내장 toolset의 켜짐 상태와 도구 이름을 반환한다.
MCP 서버의 실제 등록 도구 목록을 열거하지 않는다.
이 태그의 API server에는 `GET /v1/tools` 경로가 없다.
[API server 경로와 `_handle_toolsets`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/gateway/platforms/api_server.py)를 읽어 확인했다.

대시보드 `POST /api/mcp/servers/{name}/test`는 해당 profile의 비밀값으로 별도 연결을 만들고,
도구 목록을 읽은 뒤 연결을 닫는다.
성공은 서버 실행과 환경 값 전달을 확인하지만, 공유 gateway의 연결이 갱신됐다는 뜻은 아니다.
`GET /api/mcp/servers`도 저장된 설정의 요약이다.
[MCP 대시보드 API](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/web_routers/mcp.py)가 근거다.

probe 성공과 설치 응답의 재시작 불필요를 연결 준비 판정에 쓸 수 있다.
이 판정은 공유 gateway의 요청 profile에서 실제로 등록된 도구를 확인한 결과와 다르다.
실제 가계부 도구와 Control Plane MCP 도구를 호출하는 운영 확인이 따로 필요하다.
gateway 재시작은 다른 profile의 실행에도 영향을 주므로 요청 처리에서 자동 실행하지 않는다.
운영 연결 확인과 필요한 재시작은 `fos-home-infra`가 맡는다.

MCP 프로세스의 환경 값은 실행할 때 복사된다.
profile `.env`의 토큰을 교체하거나 지워도 이미 떠 있는 프로세스의 이전 토큰은 남는다.
MCP 설정 감시는 `config.yaml`의 수정 시각과 크기를 보며 `.env` 변경을 보지 않는다.
같은 이름의 연결은 다시 사용하므로 단순 설정 조회나 별도 probe로 토큰 교체를 보장하지 못한다.
[MCP 설정 감시](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/gateway/run_profile_reconcile.py)와
[기존 연결 재사용](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/tools/mcp_tool_discovery.py)이 근거다.
토큰 교체와 해제는 해당 profile MCP 연결을 명시적으로 종료한 뒤 다시 발견하거나 gateway를 재시작해야 한다.

공유 gateway는 60초마다 profile마다 `mcp_servers`의 이름과 살아 있는 연결을 맞춘다.
`config.yaml`에 새 이름을 더하면 다음 맞추기 주기가 그 서버를 연결하고, 이름을 빼면 그 서버만 끊는다.
같은 이름의 정의나 `.env` 값을 바꾼 것은 이름이 같아 다시 연결하지 않는다. 이 교체는 여전히 재시작을 기다린다.
뗀 서버 기록에 남은 이름을 다시 붙여도 gateway 가 옛 연결을 쥐고 있을 수 있다. 정의와 값이 같아도 `restart_required` 를 참으로 답하고 재시작을 기다린다.
바인딩 설치는 이 차이로 `reload_pending`과 `restart_required`를 나눠 답한다([ADR-20261007 / connector-live-reload](../../docs/adr/ADR-20261007-connector-live-reload.md)).
재시작이 필요한 동안에는 에이전트를 활성화하지 않는다.

### 환경 항목 제거

Hermes의 `DELETE /api/env`는 본문 `{ "profile": "<profile>", "key": "ACCOUNTBOOK_API_TOKEN" }`으로
해당 profile의 환경 항목을 제거한다.
없는 항목은 404다.
`PUT /api/env`에 빈 값을 넣는 것과 항목 제거를 구분한다.
[환경 API](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/web_routers/config_env.py)와
[요청 모델](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/web_models.py)이 근거다.
기계용 인증에서 이 메서드와 커넥터 칸 key를 허용하는 정책은 `fos-home-infra`가 소유한다.

## 실행 공간

2026-10-05 에 운영과 같은 이미지(v0.21.5, `v2026.9.24`)를 격리 환경에 띄워 측정했다.
결정은 [ADR-086](../../docs/adr/ADR-086-셸과-파일-도구는-사용자별-docker-실행-공간에서만-돈다.md) 가 갖는다.
측정은 도구를 모델 없이 직접 불렀다. gateway 가 profile 하나의 turn 을 묶는 함수(`gateway/run.py` 의 `_profile_runtime_scope`)로 profile 을 묶고 그 안에서 `registry.dispatch` 로 도구를 불렀다.
합성 비밀 파일만 썼다. 운영 Hermes 와 운영 DB 는 건드리지 않았다.

### profile 별 적용

대시보드 plugin 은 유효한 `FOS_ASSISTANT_SANDBOX` 정책의 `profiles` 에 등록된 profile 만 docker 실행 공간으로 보낸다.
미등록 profile 은 셸 도구 저장을 허용하고 local 로 둔다. 정책 자체가 없거나 잘못됐으면 저장을 거절한다.
도구 저장 때 backend 가 바뀌므로 정책만 수정해서 이미 저장된 profile 의 실행 공간이 바뀌지는 않는다.
정책에서 profile 을 빼면 다음 셸 저장에서 local 로 돌아간다.
정책 형식과 env, 망, 마운트 검증은 [`hermes/README.md`](../README.md)의 「셸 실행 공간」이 갖는다.

token 파일을 실행 공간에 읽기 전용으로 붙인 profile 의 셸은 그 token 으로 Backend 의 모든 API 를 부를 수 있다.
읽기 전용 마운트는 token 유출이나 API 쓰기를 막지 않는다.

**Hermes 예약 작업은 기본 profile 의 설정과 cron 저장소를 따른다.** Control Plane 예약 작업으로 옮긴다([ADR-20261008 / cron-to-task](../../docs/adr/ADR-20261008-cron-to-task.md)).
named profile 저장은 기본 profile 설정과 cron 저장소를 바꾸지 않는다.
예약 작업 이름이나 지시문에 역할 이름을 넣어도 실행 profile 은 바뀌지 않는다.
script 를 지정한 예약 작업의 subprocess 는 Hermes 프로세스에서 돈다. terminal backend 를 바꾸는 것으로 격리되지 않는다.
Control Plane 예약 작업과 매일 깨우기는 각 에이전트의 profile 을 쓰는 별도 기능이다.

### 무엇이 실행 공간 안에서 도는가

`terminal.backend` 를 `docker` 로 두면 아래가 컨테이너 안에서 돈다.

| 도구 | 어디서 도나 | 근거 |
| --- | --- | --- |
| `terminal`, `process_manage` | 컨테이너. 명령마다 컨테이너 안에서 bash 를 띄운다 | `tools/environments/docker.py` |
| `read_file`, `write_file`, `patch`, `search_files` | 컨테이너. `_get_file_ops` 가 terminal 과 같은 환경을 받아 `ShellFileOperations` 로 읽고 쓴다 | `tools/file_tools.py` 의 `_get_file_ops` |
| `execute_code` | 컨테이너 안의 원격 커널. 스크립트가 부르는 도구는 Hermes 로 돌아와 다시 판정된다 | `tools/code_execution_tool.py` 의 `SANDBOX_ALLOWED_TOOLS` |
| `delegate_task` 의 자식 | 자식 실행은 Hermes 안에서 돌고, 자식의 셸과 파일 도구는 부모와 같은 컨테이너를 쓴다 | `tools/terminal_tool.py` 의 `_resolve_container_task_id` |
| MCP 서버(stdio) | **Hermes 프로세스의 자식.** 컨테이너 밖이다 | `tools/mcp_tool_transport.py` |

`execute_code` 의 RPC 로 다시 Hermes 에서 도는 도구는 `web_search`, `web_extract`, `read_file`, `write_file`, `search_files`, `patch`, `terminal` 이다.
웹 도구는 Hermes 쪽에서 주소 검사를 거치고, 나머지는 다시 컨테이너로 간다.

**스크립트는 커넥터 MCP 도구를 부르지 못한다.** RPC 로 부를 수 있는 도구는 위 일곱 개(`SANDBOX_ALLOWED_TOOLS`)와 그 profile 에 켜진 도구의 교집합이고, 교집합이 비면 일곱 개 전부다.
스텁 모듈(`hermes_tools.py`)이 그 교집합만 만들고, RPC 처리기(`tools/code_execution_rpc.py` 의 `_handle_rpc_request`)가 목록 밖 이름을 거절한다. docker 실행 공간의 원격 커널(`tools/code_kernel_remote.py`)도 같은 목록이다.
목록은 모듈 상수라 core 를 고치지 않고는 늘지 않는다. 커넥터 데이터를 계산하는 길은 [ADR-20261008 / connector-output-files](adr/ADR-20261008-connector-output-files.md) 가 정한다.

커넥터 MCP 서버가 컨테이너 밖에서 도는 것이 비밀 격리의 근거다.
커넥터 토큰은 MCP 프로세스의 환경에만 있고, 셸은 그 프로세스와 profile 파일을 보지 못한다.

### profile 마다 다른 설정을 읽는다

공유 listener 는 여러 profile 을 한 프로세스에서 돌린다.
`_profile_runtime_scope` 가 turn 마다 그 profile 의 `terminal.*` 전체를 ContextVar 에 넣는다(`tools/terminal_scope.py`).
그동안 `TERMINAL_*` 읽기는 그 값에서만 찾고 프로세스 환경을 보지 않는다.
설정 파일을 읽지 못하면 거절 scope 가 들어가 셸 실행이 실패한다. 다른 profile 의 설정으로 넘어가지 않는다.

값의 출처 순서는 기본값, profile `.env` 의 `TERMINAL_*`, profile `config.yaml` 의 `terminal:` 이다. 뒤가 이긴다.

| `terminal.*` | 뜻 | 기본값 |
| --- | --- | --- |
| `backend` | `local`, `docker`, `ssh` 등 | `local` |
| `docker_image` | 컨테이너 이미지. `execute_code` 를 쓰려면 python3 가 있어야 한다 | Hermes 기본 이미지 |
| `container_persistent` | 켜면 profile 하나에 오래 가는 컨테이너 하나 | true |
| `docker_shared_container_key` | 같은 값을 둔 profile 들이 컨테이너 하나를 함께 쓴다 | 없음 |
| `docker_volumes` | 더 붙일 `-v` 목록. `:/workspace` 를 주면 기본 workspace 대신 그것을 쓴다 | 없음 |
| `docker_network` | false 면 `--network=none` | true |
| `docker_extra_args` | `docker run` 끝에 붙는다. `--network=<이름>` 으로 망을 고른다 | 없음 |
| `container_cpu`, `container_memory` | `--cpus`, `--memory`(MB) | 1, 5120 |
| `docker_forward_env`, `docker_env` | 컨테이너에 넣을 환경 변수. 운영자가 명시한 값이라 차단 목록을 거치지 않는다 | 없음 |
| `credential_files`, `env_passthrough` | profile 파일과 환경 값을 컨테이너에 넣는 목록 | 없음 |

`cwd` 를 `/workspace` 로 두면 Hermes 가 호스트 쪽에 그 경로가 없다는 경고(`TERMINAL_CWD does not exist`)를 남기지만 컨테이너 안의 작업 디렉터리는 `/workspace` 다.
`TERMINAL_SANDBOX_DIR` 는 프로세스 환경에서 읽는다. 없으면 그 profile 의 `<HERMES_HOME>/sandboxes` 다.

### 컨테이너

#### 이름과 재사용

| 칸 | 값 |
| --- | --- |
| 이름 | `hermes-<무작위 8자>` |
| label `hermes-profile` | profile 이름. 공유 키를 쓰면 `<키>-<sha256 12자>` |
| label `hermes-task-id` | `profile_<이름>` 또는 `shared_<키>` |
| label `hermes-egress` | egress proxy 설정의 지문. 바뀌면 새 컨테이너를 만든다 |

**프로세스가 다시 떠도 label 로 같은 컨테이너를 찾아 쓴다.** 멈춘 컨테이너는 `docker start` 로 다시 띄운다.
측정에서 멈춘 컨테이너가 다음 호출에 3.3초 만에 다시 떴고 workspace 파일이 남아 있었다.

**마운트를 바꿔도 같은 label 의 컨테이너를 다시 쓴다.** 프로세스 안의 캐시는 지워진 컨테이너를 옛 run 인자로 다시 만든다(`_find_reusable_container`, `_recreate_container`).
그래서 설정이 바뀌면 키를 바꿔야 새 컨테이너가 생긴다.
plugin 은 `docker_shared_container_key` 를 profile, 주인, 설정 지문으로 만들어 이 키를 바꾼다. 키가 바뀌면 label 과 캐시 키와 `/root` 디렉터리가 모두 새것이 된다.
Hermes 의 `hermes-profile` label 은 공유 키를 쓸 때 profile 이름을 그대로 담지 않는다.
plugin 은 정책에 등록된 profile 이름으로 `--label=fos-sandbox-profile=<profile>` 을 추가한다.
proxy 검사와 유휴 정리, 복구는 이 label 로 정책의 profile 을 식별하며 API 요청에서 임의 label 을 받지 않는다.
이전 키의 컨테이너와 `/root` 디렉터리는 남는다. 정리는 운영이 한다.

#### 수명

**`container_persistent` 가 켜져 있으면 Hermes 는 컨테이너를 멈추지 않는다.**
유휴 정리(`terminal.lifetime_seconds`, 기본 300초)는 프로세스 안의 연결만 놓는다(`DockerEnvironment.cleanup`).
기동 때 도는 정리기(`reap_orphan_containers`)는 **멈춘** 컨테이너 가운데 `2 × lifetime_seconds` 보다 오래된 것만 지운다.
그래서 한 번 셸을 쓴 profile 의 컨테이너는 사람이 멈출 때까지 떠 있다.

**도구 시간 초과는 컨테이너 안의 프로세스를 끝내지 않았다.**
`search_files` 로 `/` 전체를 찾게 하자 61초에 `search_timeout` 으로 답했지만 `grep` 은 컨테이너 안에서 계속 돌며 CPU 1개를 다 썼다.
`--cpus` 와 `--pids-limit 256` 이 그 상한이다.

#### 보안 기본값

측정한 컨테이너의 `docker inspect` 다.

| 칸 | 값 |
| --- | --- |
| capability | `ALL` 을 빼고 `DAC_OVERRIDE`, `CHOWN`, `FOWNER`, `SETUID`, `SETGID` 만 더한다 |
| `security-opt` | `no-new-privileges` |
| `pids-limit` | 256 |
| `privileged` | false |
| 사용자 | root(컨테이너 안). `docker_run_as_host_user` 로 바꿀 수 있다 |
| tmpfs | `/tmp` 512m nosuid, `/var/tmp` 256m noexec, `/run` 64m noexec |
| init | `--init` |

#### 마운트

| 컨테이너 경로 | 원본 | 쓰기 |
| --- | --- | --- |
| `/root` | `<sandbox dir>/docker/<task id>/home` | 쓴다 |
| `/workspace` | `<sandbox dir>/docker/<task id>/workspace`. `docker_volumes` 에 `:/workspace` 가 있으면 그것 | 쓴다 |
| `/root/.hermes/skills` | 그 profile 의 `skills/` | 읽기 전용 |
| `/root/.hermes/external_skills/<n>` | `skills.external_dirs` | 읽기 전용 |
| `<FOS_ASSISTANT_SKILL_AGENT_ROOT>/<profile>`(Hermes 와 같은 경로) | 정책에 `skill_root` 가 있고 그 profile 의 스킬 디렉터리가 있을 때 plugin 이 붙인다. 그 profile 의 모든 스킬 버전이다. scripts 가 있는지는 보지 않으므로, `skill_root` 를 넣은 뒤에는 스킬 디렉터리가 있는 profile 마다 다음 셸 설정 쓰기에서 컨테이너 키가 한 번 바뀐다 | 읽기 전용 |
| `/root/.hermes/cache/*`, `images`, `attachments` | 그 profile 의 media cache | 읽기 전용 |
| `/root/.hermes/<상대 경로>` | `terminal.credential_files` 와 스킬이 선언한 `required_credential_files` | 읽기 전용 |
| `<connector_output_root>/users/<sha256(주인)>/<profile>`(같은 경로) | 정책에 그 키가 있을 때 plugin 이 붙인다. 커넥터가 계산할 목록을 쓴다 | 읽기 전용 |
| `docker_volumes` 의 나머지 | 운영자가 정한 것 | 적힌 대로 |

`/workspace` 와 `/root` 밖의 파일 시스템은 이미지의 것이고 컨테이너를 지우면 사라진다.

**`external_skills/<n>` 는 컨테이너를 만들 때의 버전 디렉터리다.**
Hermes 는 컨테이너를 만들 때만 `skills.external_dirs` 를 붙이고(`tools/environments/docker.py` 의 `_readonly_skill_mount_args`), 컨테이너 키의 지문에는 스킬 경로가 없다.
그래서 스킬을 다시 게시해도 같은 컨테이너는 옛 버전을 보고, 그 버전이 정리되면 빈 디렉터리를 본다.
또 `skill_view` 가 모델에게 주는 `skill_dir` 는 Hermes 쪽 경로다(`tools/skills_tool.py`). 실행 공간에는 그 경로가 없다.
올린 스킬의 스크립트는 이 마운트에 기대지 않고, plugin 이 profile 스킬 디렉터리를 Hermes 와 같은 경로에 붙인 것으로 돈다([ADR-20261009 / skill-package](../../docs/adr/ADR-20261009-skill-package.md)).
`skill_view` 의 `linked_files` 는 `scripts/` 바로 아래의 `*.py`, `*.sh`, `*.bash`, `*.js`, `*.ts`, `*.rb` 만 보인다. 다른 파일도 경로로 부르면 돈다.

**마운트 원본 경로는 Docker daemon 쪽 경로로 해석된다.**
Hermes 가 컨테이너 안에서 Docker 를 부르면 `-v` 의 원본은 Hermes 컨테이너의 경로가 아니라 Docker 호스트의 경로다.
그래서 sandbox dir, `docker_volumes` 의 원본, 스킬과 media cache 경로가 **Docker 호스트와 Hermes 컨테이너에서 같은 경로**여야 한다.
경로가 다르면 빈 디렉터리가 붙거나 엉뚱한 호스트 경로가 붙는다.

#### 공유 키와 사용자별 볼륨

사용자 한 명의 여러 profile 이 파일 공간을 함께 쓰는 길은 둘이다. 둘 다 측정했다.

| 방식 | 컨테이너 | 스킬 마운트 | 다른 사용자 |
| --- | --- | --- | --- |
| `docker_shared_container_key` 를 같게 둔다 | 사용자당 하나 | **컨테이너를 처음 만든 profile 의 것만** 붙는다. 다른 profile 의 스킬은 그 컨테이너에서 보이지 않는다 | 다른 키의 컨테이너라 보이지 않는다 |
| profile 마다 컨테이너, `docker_volumes` 로 사용자 디렉터리를 `/workspace` 에 붙인다 | profile 당 하나 | profile 마다 자기 것 | 다른 디렉터리라 보이지 않는다 |

공유 키는 첫 profile 의 `terminal.*` 와 마운트가 그 컨테이너의 수명 동안 이긴다.
두 번째 방식은 `/root` 가 profile 마다 따로라서 셸 이력과 설치한 사용자 패키지는 나뉘고 `/workspace` 만 함께 쓴다.

### 큰 도구 결과의 저장

Hermes 는 큰 도구 결과를 모델에 그대로 넣지 않고 파일로 저장한 뒤 앞부분 1,500자와 경로를 돌려준다(`tools/tool_result_storage.py`).

| 항목 | 값 |
| --- | --- |
| 기준 | 도구 결과 하나가 100,000자를 넘을 때. 이름이 `mcp_` 로 시작하는 MCP 도구는 50,000자(`tool_budget.mcp_result_size_chars` 로 바꾼다). 한 turn 의 도구 결과 합이 200,000자를 넘으면 큰 것부터 저장한다 |
| 저장 위치 | 그 profile 의 `cache/spillover`. 24시간 지난 파일을 지운다 |
| 실행 공간에서 | `cache/spillover` 는 `/root/.hermes/cache/spillover` 에 읽기 전용으로 붙는다. 그 turn 에 실행 공간 연결이 있으면 그 경로를 돌려준다 |
| 연결이 없을 때 | **Hermes 쪽 경로를 돌려준다.** 셸이나 `execute_code` 를 아직 부르지 않은 turn 에서 목록을 먼저 부르면 실행 공간에서 열리지 않는 경로가 나온다 |

그래서 계산할 데이터를 실행 공간에 넘기는 길로 이 저장에 기대지 않는다.
미리보기만 본 모델이 본문을 읽으려면 `read_file` 이 있어야 한다. 파일 도구를 잠근 에이전트는 저장된 본문에 닿지 못한다.

### 파일 도구의 쓰기 경로 검사

**`HERMES_WRITE_SAFE_ROOT` 는 docker 백엔드에서도 경로 문자열로 검사한다.**
이미지 기본값이 Hermes 데이터 경로 하나라서, 그대로 두면 `write_file` 이 `/workspace/...` 를 「outside HERMES_WRITE_SAFE_ROOT」 로 거절했다.
값에 `/workspace` 를 더하자 쓰기가 됐다. 이 값은 프로세스 환경이라 모든 profile 에 같이 적용된다.

`read_file` 은 `.env`, `auth.json` 같은 이름을 경로 문자열로 먼저 거절한다(`agent/file_safety.py`).
이 검사는 Hermes 스스로 「방어를 한 겹 더할 뿐 경계가 아니다」 라고 적는다. 경계는 컨테이너다.

### `execute_code` 의 승인 판정

`execute_code` 는 자식 프로세스를 띄우기 전에 `tools/approval.py` 의 `check_execute_code_guard` 를 거친다.
스크립트 안의 `subprocess` 는 셸 위험 명령 판정을 거치지 않기 때문에 스크립트 전체를 한 번에 판정한다.
아래 순서로 처음 걸리는 줄이 결과를 정한다.

| 순서 | 조건 | 결과 |
| --- | --- | --- |
| 1 | `terminal.backend` 가 `docker` 이고 실행 공간에 호스트 경로 마운트가 없다 | 승인 |
| 2 | `approvals.mode` 가 `off` 이거나 yolo 다 | 승인 |
| 3 | 사람이 없는 문맥이다. `-q`, cron, 그리고 `webhook`, `msgraph_webhook`, `api_server` 플랫폼이다 | 그 문맥의 모드로 바로 정한다. `deny` 면 거절, `approve` 면 승인 |
| 4 | gateway 나 ask 문맥이다 | 사람에게 묻는다 |
| 5 | 그 밖 | 승인 |

**우리 실행 공간은 1번을 받지 못한다.**
`tools/terminal_tool.py` 의 `_docker_has_host_access` 는 `docker_volumes` 에 `/` 나 `~` 로 시작하는 원본이 하나라도 있으면 참이다.
plugin 이 쓰는 `/workspace` 부터 호스트 경로다.

**API 서버 경로는 3번에서 끝난다.**
모드는 `approvals.unattended_mode` 이고 기본값은 `deny` 다.
3번이 4번보다 앞이라, API 서버의 승인 다리(`/v1/runs/{id}/approval`)가 있어도 `execute_code` 는 승인 카드로 가지 않는다.
그래서 plugin 은 docker 실행 공간을 쓰는 profile 에 `approve` 를 쓴다([ADR-20261008 / execute-code-unattended](adr/ADR-20261008-execute-code-unattended.md)).

**같은 모드가 셸 위험 명령과 plugin 승인 요청에는 닿지 않는다.**
`check_all_command_guards` 와 `_run_approval_gate` 는 CLI, gateway, ask 문맥이 모두 아닐 때만 사람이 없는 문맥의 모드를 본다.
gateway 의 `start_gateway()` 가 `HERMES_EXEC_ASK=1` 을 프로세스 환경에 넣으므로 API 서버 실행은 ask 문맥이고, 두 판정은 승인 카드로 간다.
이 두 지점은 계약 시험(`hermes/tests/test_hermes_contract.py`)이 확인한다.

모드는 판정할 때마다 그 실행의 profile 설정에서 읽는다(`tools/approval_context.py` 의 `_binary_approval_mode`).
값을 바꾸면 gateway 를 다시 띄우지 않아도 다음 호출부터 반영된다.
받는 값은 `approve`, `off`, `allow`, `yes` 가 승인이고 그 밖은 모두 거절이다.

### 측정 결과

profile 셋을 썼다. 사용자 A 의 profile 둘, 사용자 B 의 profile 하나다.
모든 profile 의 `.env`, 커넥터 값 파일, 커넥터 코드 디렉터리, 루트 설정과 OAuth 파일에 서로 다른 합성 값을 두었다.

| 시험 | 결과 |
| --- | --- |
| `terminal` 의 호스트 이름 | 사용자 A 의 두 profile 은 같은 컨테이너(공유 키), B 는 다른 컨테이너 |
| `ls`, `cat` 로 Hermes 데이터 경로 | 없는 경로 |
| 컨테이너 환경 변수의 합성 값 | 없음 |
| `read_file` 로 다른 profile 의 `.env`, 루트 `.env`, OAuth 파일 | 이름 검사로 거절 |
| `read_file` 로 커넥터 값 파일, 커넥터 코드, 루트 설정 | 「File not found」 |
| `patch` 로 Hermes 루트 설정 | 읽지 못함 |
| `search_files` 로 `/workspace`, `/root` 의 합성 값 | 0건 |
| `execute_code` 로 다른 profile 의 `.env` 열기 | `FileNotFoundError` |
| `write_file` 로 읽기 전용 스킬 마운트 | 거절 |
| 사용자 A 의 두 profile 이 쓴 `/workspace` 파일 | 서로 보인다 |
| 사용자 B 의 `/workspace` | A 의 파일이 없다 |
| 사용자별 볼륨 방식에서 같은 시험 | 위와 같다. 컨테이너는 profile 마다 하나 |
| 대시보드 plugin 이 쓰는 `terminal:` 블록을 둔 뒤 실제 `PUT /api/config` 처리기로 도구 목록을 저장 | 블록이 남는다. 값이 빈 `docker_env: {}` 만 지워지고 기본값이 같아 동작은 같다. 그래서 plugin 은 빈 env 에서 `docker_env` 칸을 넣지 않고 지문을 계산한다 |
| 그 블록으로 셸과 `write_file` | `/workspace` 에서 돈다. 읽기 전용으로 붙인 경로는 읽히고 쓰기는 「Read-only file system」 이다. 전용 망에 붙는다 |
| 한 프로세스에서 `docker_volumes` 의 `/workspace` 원본만 바꾸고 셸을 다시 부른다 | **옛 컨테이너와 옛 원본이 그대로 쓰였다.** 바꾸기 전 디렉터리의 파일이 보였다 |
| 같은 시험에서 `docker_shared_container_key` 도 함께 바꾼다 | 같은 프로세스에서 새 컨테이너가 생기고 새 원본만 보였다 |

### 같은 프로세스에 남는 구멍

docker 백엔드는 셸과 파일 도구만 옮긴다. 아래는 Hermes 프로세스 안에서 돈다.

| 도구나 경로 | 남는 것 | 지금 상태 |
| --- | --- | --- |
| 스킬 앞머리의 `required_environment_variables`(`setup.collect_secrets`, `prerequisites.env_vars` 포함) | 스킬을 읽으면 그 이름의 값을 profile `.env` 에서 꺼내 컨테이너 환경에 넣는다. **측정에서 Control Plane MCP 토큰과 커넥터 토큰이 컨테이너 환경에 들어왔다.** `API_SERVER_KEY` 같은 provider 차단 목록만 막힌다 | 올린 스킬은 Control Plane 이, 커넥터 스킬은 대시보드 plugin 의 manifest 검증이 이 칸을 거절한다 |
| 스킬 앞머리의 `required_credential_files` | profile 디렉터리 안의 파일을 컨테이너 `/root/.hermes/` 아래에 붙인다. `.env` 와 `auth.json` 은 거절하지만 **커넥터 값 파일(`.fos-connectors.json`)은 붙었다** | 위와 같다 |
| `skill_manage` | profile 의 스킬 디렉터리에 쓴다 | `fos-ctx` 의 `pre_tool_call` 이 막는다(ADR-034) |
| `skill_view` 의 `` !`cmd` `` 전처리 | Hermes 호스트에서 명령을 돌린다 | `skills.inline_shell` 기본값 false 로 꺼져 있다 |
| `browser_*` | Hermes 컨테이너의 브라우저가 돈다 | `file://` 은 막혔다(측정). toolset 은 profile 틀에서 꺼져 있다 |
| `vision_analyze`, `image_generate` | 호스트 경로는 그 profile 의 media cache 안만 읽고, 나머지는 컨테이너 안에서 읽는다 | 첨부 경로를 컨테이너에 붙여야 사진을 읽는다 |
| `text_to_speech` 의 `output_path` | 호스트 경로에 쓴다. `HERMES_WRITE_SAFE_ROOT` 가 상한이다 | |
| `MEDIA:` 태그 | OpenAI 호환 경로는 그림 파일을 data URL 로 바꿔 싣는다 | `/v1/runs` 는 이 변환을 하지 않는다 |
| 파일 도구의 존재 확인 | `os.path.exists` 같은 호출 몇 개가 호스트 경로를 본다. 내용은 읽지 않는다 | 남는다 |
| `cronjob` 의 `workdir` | 호스트 경로의 문맥 파일을 읽는다(소스로만 판정) | toolset 은 꺼져 있다 |

### 네트워크

#### 닿는 곳

| 망 | 다른 컨테이너 | 인터넷 |
| --- | --- | --- |
| 기본 bridge | 같은 bridge 의 컨테이너에 닿았다 | 닿는다 |
| `docker_extra_args: ["--network=<전용 망>"]` | 다른 망의 컨테이너에 닿지 않았다 | 닿는다 |
| `docker_network: false` | 없음 | 없음 |

**전용 망을 두지 않으면 셸이 같은 bridge 의 내부 서비스에 닿는다.**
Docker 호스트와 같은 LAN 의 주소는 망 종류와 관계없이 기본 route 로 나간다. 막으려면 호스트 방화벽이 필요하다.

#### 남길 수 있는 기록

| 수단 | 남는 것 | 비용 |
| --- | --- | --- |
| Control Plane 의 실행 기록 | 셸 명령 원문과 도구 결과(ADR-038 에 따라 관리자에게만) | 이미 있다 |
| 전용 망과 호스트의 연결 추적 | 출발 컨테이너 주소, 목적지 주소와 포트, 시각 | 운영 설정. 내용과 도메인은 남지 않는다 |
| 컨테이너의 DNS 를 기록하는 resolver 로 돌린다 | 질의한 도메인 | 운영 설정 |
| egress proxy(아래) | 요청마다 host, method, path, 상태, 거절 사유 | TLS 를 풀어 보므로 경로까지 남는다 |

#### egress proxy(iron-proxy)를 켜면

Hermes 의 `proxy.enabled` 는 `iron-proxy` 를 Hermes 쪽에 띄우고 컨테이너에 `HTTPS_PROXY` 와 CA 를 넣는다.
**허용 목록 방식이다.** 기본 목록은 AI provider 주소 몇 개뿐이다.

| 측정 | 결과 |
| --- | --- |
| 기본 목록에서 `pypi.org`, `files.pythonhosted.org`, `registry.npmjs.org`, `example.com`, `github.com` | 모두 403(`rejected_by: allowlist`) |
| 기본 목록에서 `pip download` | 「No matching distribution」 로 실패, 약 7.7초 |
| 목록에 pypi 와 npm 을 더한 뒤 `pip download`(5회) | 성공. 첫 회 647ms, 나머지 376ms 에서 392ms. proxy 없이 307ms 에서 388ms |
| 목록에 없는 웹 주소 | 403 |
| 목록에 있지만 proxy 토큰이 없는 provider 요청 | 403 |
| 프록시 환경을 무시하고 소켓을 직접 연다 | **연결된다.** proxy 는 환경 변수를 따르는 프로그램만 거른다 |

**설치 위치의 가정이 우리 구성과 다르다.**
Hermes 는 컨테이너에 `--add-host host.docker.internal:host-gateway` 를 넣고 proxy 를 그 주소로 가리킨다. proxy 가 Docker 호스트에서 돈다는 가정이다.
Hermes 가 컨테이너 안에서 돌면 proxy 는 Hermes 컨테이너의 loopback 에 떠서 실행 공간에서 닿지 않았다(Connection refused).
측정은 컨테이너 안에서 proxy 주소를 직접 주어 했다.

**egress 를 켜면 Docker 접근을 TCP 로 두지 못한다.**
Hermes 는 `docker` CLI 를 부를 때도 proxy 환경 변수를 넣는다. `DOCKER_HOST=tcp://...` 이면 CLI 가 그 요청을 proxy 로 보내 「Cannot connect to the Docker daemon」 으로 실패했다. unix socket 은 영향이 없다.

proxy 의 기록은 `iron-proxy.log` 에 요청마다 한 줄이다. 별도 audit 파일 자리는 있지만 v0.39 는 쓰지 않는다.

### Docker 를 다루는 길

실행 공간을 쓰려면 Hermes 가 컨테이너를 만들 수 있어야 한다.
Hermes 는 Docker 를 컨테이너 생성 요청 본문을 검사하는 socket proxy 로만 다룬다([ADR-086](../../docs/adr/ADR-086-셸과-파일-도구는-사용자별-docker-실행-공간에서만-돈다.md)).

Hermes 가 쓰는 Docker API 는 `version`, `info`, `ps`, `inspect`, `image inspect`, `create`, `run`, `start`, `exec`, `rm` 이다. volume 과 network API 는 쓰지 않는다.

| 길 | 측정 | 판정 |
| --- | --- | --- |
| socket 을 Hermes 에 직접 붙인다 | 동작한다 | Hermes 프로세스나 그 자식(커넥터 MCP 서버 포함)이 뚫리면 호스트 root 와 같다 |
| 경로와 메서드만 거르는 socket proxy(containers, exec, images, info, version 만 연다) | 실행 공간이 동작한다. volume 과 network 목록은 403 | **`--privileged -v /:/host` 컨테이너 생성이 통과했다.** 호스트 파일 시스템이 보였다 |
| 컨테이너 생성 요청 본문을 검사하는 proxy | 이 저장소에서 측정하지 않았다 | 채택했다. 아래 조건을 본문에서 강제한다 |

본문 검사 proxy 가 거절할 것은 이렇다.

- `Privileged`, `CapAdd` 의 허용 목록 밖, `Devices`, `PidMode`/`IpcMode`/`NetworkMode` 의 `host`, `SecurityOpt` 의 완화
- 허용한 이미지가 아닌 것
- 실행 공간 루트와 운영자가 정한 읽기 전용 경로 밖의 bind
- 허용한 망이 아닌 것
- `hermes-agent=1` label 이 없는 컨테이너에 대한 `exec`, `start`, `rm`

이 proxy 는 운영 저장소가 소유한다.

### 자원

첫 셸 호출은 컨테이너 생성까지 포함해 2~4초, 이어지는 호출은 1초 안쪽이고, 유휴 컨테이너는 메모리를 15 MiB 넘게 쓰지 않았다(4 CPU, 8 GiB VM 측정).
컨테이너는 profile 마다 하나(사용자별 볼륨 방식) 또는 사용자마다 하나(공유 키)다. 셸을 한 번도 쓰지 않은 profile 은 만들지 않는다.
디스크 상한(`container_disk`)은 storage driver 가 XFS pquota 를 지원할 때만 걸린다.

### 근거

- [tools/code_execution_tool.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/code_execution_tool.py)
- [tools/code_execution_rpc.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/code_execution_rpc.py)
- [tools/approval.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/approval.py)
- [tools/approval_context.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/approval_context.py)
- [tools/tool_result_storage.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/tool_result_storage.py)
- [tools/budget_config.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/budget_config.py)
- [tools/terminal_tool.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/terminal_tool.py)
- [tools/terminal_scope.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/terminal_scope.py)
- [tools/environments/docker.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/environments/docker.py)
- [tools/environments/docker_egress.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/environments/docker_egress.py)
- [tools/credential_files.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/credential_files.py)
- [tools/environments/remote_common.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/environments/remote_common.py)
- [tools/skills_tool_setup.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/skills_tool_setup.py)
- [tools/image_source.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/image_source.py)
- [agent/proxy_sources/iron_proxy.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/agent/proxy_sources/iron_proxy.py)
- [agent/file_safety.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/agent/file_safety.py)
- [gateway/run.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/gateway/run.py)

## 스킬

Hermes 가 스킬을 어디서 읽고 입력에 얼마나 싣는지, API server 가 스킬 커맨드를 어떻게 다루는지를 갖는다.
Control Plane 이 올린 스킬을 저장하고 게시하는 규칙은 [`docs/features/agent-skill.md`](../../docs/features/agent-skill.md) 가 갖는다.

### 스킬을 profile 에 붙이는 방법

**정식 설정 키는 `skills.external_dirs` 다.**
`hermes_cli/config_defaults.py` 에 있고 기본값은 빈 목록이다.
`skill_paths` 와 `external_skill_paths` 라는 이름의 키는 없다.

스킬이 실리는 경로가 셋이다.

| 방법 | 내용 |
| --- | --- |
| `config.yaml` 의 `skills.external_dirs` | 디렉터리 목록을 그대로 읽는다 |
| `hermes skills trust <경로>` | 그 저장소의 `./.hermes/skills` 와 `./.agents/skills` 를 읽는다 |
| profile 의 `skills/` 에 심볼릭 링크 | 외부 디렉터리를 직접 가리킨다 |

스킬 설명이 매 대화마다 입력으로 함께 실린다.
그러므로 에이전트마다 그 에이전트가 쓰는 스킬만 붙인다.

#### 스킬의 출처와 색인 입력

2026-09-28 에 v0.21.0 소스와 색인 계산으로 확인했다.

| 출처 | 연결·제외 단위 |
| --- | --- |
| profile 의 `skills/` | 범주 아래 스킬 디렉터리와 링크를 읽는다. `skills.disabled` 로 제외한다 |
| Hermes 번들 | profile 생성과 `tools/skills_sync.py` 의 기동 동기화가 심는다. `.no-bundled-skills` 는 일반 번들을 막지만 필수 스킬은 남긴다 |
| `skills.external_dirs` | 연결 디렉터리 아래 `SKILL.md` 를 읽는다. 경로를 빼거나 `skills.disabled` 로 제외한다 |
| 신뢰한 저장소 | `./.hermes/skills`, `./.agents/skills` 를 읽는다. 신뢰를 거두면 제외된다 |
| hub 설치 | 스킬 하나를 설치·제거하거나 비활성화한다 |

같은 이름이면 신뢰한 저장소, profile 로컬, 외부 디렉터리 순으로 앞의 것을 쓴다.
`agent/system_prompt.py` 는 실행 도구에 `skills_list`, `skill_view`, `skill_manage` 중
하나라도 있을 때만 `build_skills_system_prompt` 를 부른다.
**`skills` toolset 을 닫으면 스킬 파일이 있어도 색인이 입력에 들어가지 않는다.**

색인은 고정 안내문, 범주별 한 줄, 스킬별 이름과 설명 한 줄로 구성된다.
설명은 `SKILL_PROMPT_DESC_LIMIT` 인 60자에서 자른다.
본문은 `skill_view` 로 읽을 때 들어가고, 조건부 스킬은 `_skill_should_show` 가
도구와 platform 으로 거른다.

스킬 하나는 API 호출 한 번의 입력에 대략 20~45 토큰(`o200k_base` 기준)을 더하고, 실행 안의 API 호출마다 다시 실린다.

**`skills` 는 읽기 전용 toolset 이 아니다.**
`skill_manage` 는 profile 로컬 스킬을 만들고 고친다.
외부 디렉터리가 쓰기 가능하면 사용자가 지시한 외부 스킬 수정도 가능하다.
`tools/skill_manager_tool.py` 의 외부 스킬 보호는 백그라운드 curator 의 쓰기만 막는다.

근거는 [v0.21.0 system_prompt.py](https://github.com/NousResearch/hermes-agent/blob/v2026.8.31/agent/system_prompt.py),
[skill_utils.py](https://github.com/NousResearch/hermes-agent/blob/v2026.8.31/agent/skill_utils.py),
[skill_manager_tool.py](https://github.com/NousResearch/hermes-agent/blob/v2026.8.31/tools/skill_manager_tool.py) 다.

#### `skill_manage` 만 빼는 설정

v0.21.5 소스로 확인했다. **`skill_view` 를 두고 `skill_manage` 만 도구 목록에서 빼는 공식 설정은 없다.**

| 방법 | 결과 |
| --- | --- |
| `agent.disabled_toolsets` | toolset 이름만 받는다. `skill_manage` 하나만 든 toolset 은 없고, 도구 이름을 넣으면 모르는 이름으로 무시한다. `skills` 나 옛 이름 `skills_tools` 를 넣으면 `skill_view` 와 색인까지 빠진다 |
| `platform_toolsets.api_server` | 같은 toolset 이름 목록이라 같은 결과다 |
| plugin 의 `registry.deregister` | 자기가 등록하지 않은 도구는 `plugins.entries.<plugin>.allow_tool_override: true` 가 있어야 해제된다. profile 범위 plugin 은 전역 도구를 해제하지 못하고, 전역 plugin 이 해제하면 같은 프로세스의 모든 profile 에 걸린다 |
| 한 번 묻고 끝나는 실행의 도구 숨김 | `agent/oneshot_footprint.py` 는 `hermes chat -q` 같은 한 번 실행에서만 `skill_manage` 를 숨긴다. API server 실행은 해당하지 않는다 |

도구를 빼도 안내문은 남는다.
`agent/prompt_builder.py` 의 색인 안내문은 `skill_manage` 가 있든 없든
「If a skill has issues, fix it with skill_manage(action='patch').」 와
「After difficult/iterative tasks, offer to save as a skill.」 를 싣는다.
`skill_manage` 가 있을 때만 붙는 것은 `SKILLS_GUIDANCE` 와 memory 안내의 `skill_manage` 문장이다.

그래서 Control Plane 은 실행 입력 앞 단락으로 모델에게 쓰지 말라고 알리고, 서명 plugin 이 호출을 막는다.
근거는 [toolsets.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/toolsets.py),
[model_tools.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/model_tools.py) 의 `_apply_toolset_selection`,
[tools/registry.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/registry.py) 의 `deregister`,
[agent/system_prompt.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/agent/system_prompt.py) 의 `_tool_guidance_block`,
[agent/prompt_builder.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/agent/prompt_builder.py) 다.

#### 입력 비용은 스킬 색인보다 도구 정의가 더 크다

스킬 수를 줄이는 것보다 도구를 잠그는 것이 입력 토큰을 훨씬 많이 줄인다.
한 profile 에서 스킬을 97개에서 6개로 줄이면 입력이 17% 줄었고, 도구를 모두 잠그면 도구 안내까지 빠져 65.5% 줄었다.
비용을 줄이려면 도구 설정을 함께 본다.

v0.21.0 과 v0.21.3 Runs API 의 `usage` 에는 cache 항목이 없다.
`input_tokens`, `output_tokens`, `total_tokens` 뿐이다.
`cached_input_tokens` 가 실행 기록에서 비어 있는 것은
prompt cache 가 붙지 않아서가 아니라 이 API 가 보고하지 않기 때문이다.
v0.21.5 부터는 `usage` 에 cache 칸이 오고 `HttpHermesRunsClient` 가 `cache_read_tokens` 를 읽는다.

이미 연결한 스킬의 본문과 색인 변경이 언제 적용되는지는 [`hermes/docs/hermes-contract.md`](hermes-contract.md#변경이-적용되는-시점) 의 「변경이 적용되는 시점」 이 갖는다.

### 스킬 커맨드와 API server

2026-09-29 에 v0.21.5(태그 `v2026.9.24`) 소스로 확인했다. 링크 접두사는 `https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/` 이다.

#### CLI 와 gateway 는 `/<스킬>` 을 스킬 호출로 바꾼다

- gateway 는 내장 명령과 plugin 명령을 먼저 본 뒤 `_hm_skill_slash_rewrite`(`gateway/run_inbound.py`)가 `/<스킬> 할 일` 을 스킬 호출 메시지로 **통째로 바꿔** 일반 메시지로 넘긴다. 등록되지 않은 이름은 모델에 보내지 않고 `Unknown command` 로 답한다
- CLI(`cli.py` 의 `_run_skill_slash_command`)와 TUI 도 같은 builder 를 쓴다
- 메시지는 `agent/skill_commands.py` 의 `build_skill_invocation_message` 가 만든다. 「사용자가 이 스킬을 호출했다」는 첫 줄, 전처리한 `SKILL.md` 본문, 스킬 디렉터리, 보조 파일 목록과 `skill_view(name, file_path)` 로 읽으라는 안내, 사용자가 붙인 할 일 순이다
- 본문은 `skill_view` 를 **Python 함수로 직접** 불러 읽는다. 도구 호출을 거치지 않으므로 `skills` toolset 이 닫혀 있어도 동작한다
- 슬래시 이름은 소문자로 바꾸고 공백과 `_` 를 `-` 로 바꾼 것이다(`slugify_skill_name`). 내장 명령과 겹치면 자동 등록하지 않는다
- 전역 `skills.disabled` 와 `skills.platform_disabled.<platform>` 에 있는 스킬은 막힌다. 색인에서 숨기는 조건부 스킬 조건(`requires_toolsets` 등)은 슬래시 경로에 적용되지 않는다

#### API server 는 `/` 를 해석하지 않는다

- `/v1/runs` 는 `input` 을 그대로 `agent.run_conversation(user_message=...)` 에 넘긴다(`gateway/platforms/api_server_runs.py` 의 `_handle_runs`, `_run_agent_sync`). `/v1/chat/completions` 도 마지막 user 메시지를 그대로 쓴다
- API server 는 gateway 의 명령 처리와 `pre_gateway_dispatch` hook 을 거치지 않는다. `/<스킬> 할 일` 이 평문으로 모델에 간다
- API server 실행은 `platform="api_server"` 로 묶여 `skill_view` 와 색인이 `skills.platform_disabled.api_server` 를 적용받는다

그래서 웹 입력창의 스킬 커맨드는 Control Plane 이 해석한다. 결정은 [ADR-035](../../backend/docs/adr/ADR-035-대화창의-스킬-커맨드는-control-plane-이-해석해-hermes-에-넘긴다.md) 에 있다.
Control Plane 이 「`skill_view` 로 읽고 따르라」는 입력으로 바꿔 보내면 아래 조건이 걸린다.

| 조건 | 까닭 |
| --- | --- |
| `skills` toolset 이 켜져 있어야 한다 | 닫히면 `skill_view` 가 도구 목록에 없어 호출이 오류 결과가 된다 |
| 스킬이 전역이나 `api_server` 로 꺼져 있지 않아야 한다 | `skill_view` 가 `Skill 'x' is disabled` 로 거절한다 |
| 색인에서 숨은 조건부 스킬도 이름을 알면 읽힌다 | `skill_view` 는 조건부 표시 조건을 보지 않는다. OS 가 맞지 않는 것만 막는다 |

모델이 도구를 부르지 않는 일이 실제로 보이면, profile 플러그인의 `pre_llm_call` 이 돌려주는 `{"context": ...}` 로 `build_skill_invocation_message` 결과를 붙이는 방법이 있다. `pre_llm_call` 은 API server 실행에서도 불린다(`agent/turn_context.py`).

#### 모델이 스킬을 읽은 것을 아는 법

`skill_view` 도구 호출의 `tool.started` 사건 `preview` 는 스킬 이름이다. 참고 파일을 읽으면 `이름 → 파일 경로` 모양이다(`agent/display.py` 의 `_preview_skill_view`).
이름 규칙에 맞지 않으면 버린다.

미리보기는 길이 상한에서 잘릴 수 있다. v0.21.5 소스로 확인했다.

- `_preview_skill_view` 는 미리보기를 만든 뒤 `_tail_trunc` 로 자른다
- `_tail_trunc` 는 상한을 넘으면 앞에서 상한보다 3자 적게 남기고 끝에 `...` 을 붙인다. 상한이 3 이하이면 점만 남긴다
- 상한은 설정 `display.tool_preview_length` 이고 기본값은 0 이다. 0 이면 자르지 않는다

이름 규칙이 점을 받으므로 이름 중간에서 잘린 값도 규칙에는 맞는다.
그래서 이름 부분이 `...` 로 끝나면 잘린 것으로 보고 버린다.
파일 경로 쪽만 잘린 `이름 → 경로...` 는 이름이 온전하므로 받는다.
Control Plane 은 도구 내용을 가리기 전에 이 미리보기에서 이름을 꺼낸다([Runs API 계약](../../docs/features/execution.md#도구-내용-가리기)).

#### 스킬 목록을 얻는 곳

| 경로 | 결과 |
| --- | --- |
| API server `GET /v1/skills` | v0.21.5 에서 늘 500. `_handle_skills` 가 `_find_all_skills` 에 없는 인자 `include_editorial` 을 넘겨 `TypeError` 가 난다. 테스트는 그 함수를 mock 으로 바꿔 두어 잡지 못한다 |
| 대시보드 `GET /api/skills?profile=` | 이름, 설명, 켜짐, 출처(`provenance`), 사용 횟수(`usage`)를 준다. `enabled` 는 전역 `disabled` 만 보고 `platform_disabled` 는 보지 않는다 |
| 대시보드 `GET /api/skills/content?name=&profile=` | `SKILL.md` 원문 |

슬래시로 부를 수 있는 이름의 목록을 주는 HTTP 경로는 없다. 이름 규칙대로 계산한다.

## 도구와 스킬의 설정 API

### 도구와 스킬과 승인 설정을 HTTP 로 쓰는 길

#### 대시보드 설정 API

대시보드 웹서버는 `_profile_scope(profile)` 안에서 profile 의 설정을 읽고 쓴다.
아래 경로는 API server 의 `/v1/runs` 와 다른 서버가 처리한다.

| 경로 | 동작 | 근거 함수 |
| --- | --- | --- |
| `GET /api/config/raw` | `config.yaml` 원문을 읽는다 | `hermes_cli/web_server.py` 의 `get_config_raw` |
| `GET /api/config` | 환경 변수 참조를 펼친 설정을 읽는다 | 같은 파일의 `get_config`, `load_config` |
| `PUT /api/config` | 받은 값을 디스크 원문에 병합한다. 쓸 수 있는 키를 제한하지 않는다 | 같은 파일의 `update_config`, `hermes_cli/config.py` 의 `_deep_merge` |
| `PUT /api/config/raw` | 받은 YAML 로 설정 파일 전체를 바꾼다 | `update_config_raw` |
| `GET /api/tools/toolsets` | toolset 의 이름, 설명, 도구 목록을 읽는다 | `hermes_cli/web_routers/tools.py` 의 `get_toolsets` |
| `PUT /api/tools/toolsets/{name}` | 보통 `platform_toolsets.cli` 를 바꾼다. Discord 전용은 `discord` 에 저장한다 | 같은 파일의 `toggle_toolset`, `_toolset_configuration_platform` |
| `GET /api/skills` | 스킬 목록, 켜짐 여부와 출처를 읽는다 | `hermes_cli/web_routers/skills.py` 의 `get_skills` |
| `PUT /api/skills/toggle` | `skills.disabled` 에 이름을 넣거나 뺀다 | 같은 파일의 `toggle_skill`, `save_disabled_skills` |
| `POST /api/skills`, `PUT /api/skills/content` | profile 로컬 스킬을 만들거나 본문을 바꾼다 | 같은 파일의 `create_skill`, `update_skill_content` |
| `/api/mcp/servers` 계열 | `mcp_servers` 를 조회, 추가하고 `enabled` 를 바꾼다 | `hermes_cli/web_routers/mcp.py` |

`PUT /api/config` 는 객체를 재귀로 병합하고 목록은 받은 값으로 통째로 바꾼다.
따라서 `platform_toolsets.api_server`, `agent.disabled_toolsets`, `skills.external_dirs` 를
각각 원하는 목록으로 쓸 수 있다.
병합의 바탕은 `read_raw_config()` 의 원문이라 `${VAR}` 참조는 그대로 남는다.
반면 `GET /api/config` 는 `_expand_env_vars` 가 펼친 비밀값도 응답에 싣는다.
기계용 설정 조회에는 `GET /api/config/raw` 를 쓰되 원문도 민감한 설정으로 취급한다.

**대시보드 toolset 토글은 API 실행의 도구를 바꾸지 않는다.**
API 실행은 `platform_toolsets.api_server` 를 읽는다.
`GET /api/tools/toolsets` 는 목록 원본으로 쓸 수 있지만, 토글 응답의 `enabled` 는 CLI 기준이다.
스킬 토글의 `skills.disabled` 는 그 profile 의 모든 platform 에 적용된다.
platform 별 `skills.platform_disabled.<platform>` 은 이 토글 경로로 쓰지 않는다.
필수 스킬 `hermes-agent` 는 `agent/skill_utils.py` 의 `ESSENTIAL_SKILLS` 로 보호되어 끌 수 없다.

기계용 인증은 [「profile 을 HTTP 로 만드는 길」](hermes-contract.md#profile-을-http-로-만드는-길) 의 token provider 를 쓴다.
`register_token_route` 는 메서드를 보지 않고 경로 문자열만 맞춘다.
본문이나 query 로 profile 을 고르는 설정 경로를 열면 모든 profile 에 열린다.
`PUT /api/config` 에서 `model`, `approvals`, `mcp_servers`, `terminal`, `memory` 등의
키를 골라 허용하는 기능은 Hermes 에 없다.
plugin 이 본문을 먼저 읽은 뒤 처리기가 다시 읽는 방식은 v0.21.3 에서 동작한다. [「설정 API와 profile 경계」](#설정-api와-profile-경계) 를 본다.

근거는 [v0.21.0 web_server.py](https://github.com/NousResearch/hermes-agent/blob/v2026.8.31/hermes_cli/web_server.py),
[tools router](https://github.com/NousResearch/hermes-agent/blob/v2026.8.31/hermes_cli/web_routers/tools.py),
[skills router](https://github.com/NousResearch/hermes-agent/blob/v2026.8.31/hermes_cli/web_routers/skills.py) 다.

#### 변경이 적용되는 시점

| 바꾸는 것 | 적용 시점 | 근거 |
| --- | --- | --- |
| `platform_toolsets.api_server`, `agent.disabled_toolsets` | 다음 실행. 재시작이 필요 없다 | `_create_agent` 가 실행마다 새 agent 를 만들고 `_load_gateway_config` 가 profile 설정을 읽는다 |
| 도구 정의 | 다음 실행 | `model_tools.get_tool_definitions` 캐시 키에 toolset 목록과 설정 파일 지문이 들어 있다 |
| `skills.external_dirs`, `skills.disabled` | 다음 실행 | 외부 경로 캐시가 config mtime 을 보고 스킬 색인 캐시 키에 경로와 비활성화 목록이 들어 있다 |
| 연결된 디렉터리 안의 스킬 추가·삭제, 색인 설명 변경 | gateway 재시작 뒤 | `agent/prompt_builder.py` 의 `_SKILLS_PROMPT_CACHE` 키에 디렉터리 내용이 없다 |
| 대시보드로 스킬 생성·수정 | gateway 색인은 재시작 뒤 | `create_skill` 이 비우는 캐시는 대시보드 프로세스에만 있다 |
| `SKILL.md` 본문 | 다음 `skill_view`. 재시작이 필요 없다 | `skill_view` 가 파일을 직접 읽는다 |
| `mcp_servers` 추가 | gateway 의 MCP 설정 맞추기 작업의 다음 주기. 재시작이 필요 없다 | `gateway/run_profile_reconcile.py` 의 `reconcile_mcp_servers_with_config`. 기대는 지점은 `hermes_contract.py` 의 `LIVE_RELOAD` 가 확인한다([ADR-20261007 / connector-live-reload](../../docs/adr/ADR-20261007-connector-live-reload.md)) |
| 이미 연결된 MCP 서버의 허용 목록 | 다음 실행 | `_get_platform_tools` 가 실행마다 교집합을 만든다 |
| `approvals.mode`, `deny`, `timeout`, `cron_mode`, `unattended_mode`, `single_query_mode` | 다음 명령 판정 | `tools/approval.py` 의 읽기 함수가 `load_config_readonly()` 를 부른다 |
| `delegation.subagent_auto_approve` | 다음 위임 | `tools/delegate_tool.py` 의 `_get_subagent_approval_callback` |
| `command_allowlist` | 실행마다 다시 읽지 않는다 | `load_permanent_allowlist` 가 import 때 `_permanent_approved` 를 만든다 |

진행 중인 실행은 이미 만든 agent 의 도구 목록을 계속 쓴다.
`approvals.deny` 는 mode 와 yolo 판정보다 먼저 검사한다.
`approvals.mode` 를 바꾸며 보내는 `session.info` 는 대시보드 자기 profile 의 대화에만 간다.
API 경로에서 새 승인 설정을 읽는 것과는 별개다.

공유 listener 의 요청 문맥은 `gateway/run.py` 의 `_profile_runtime_scope` 가 만들고,
contextvar 를 통해 작업 스레드로 전달된다.
도구·스킬·승인 설정은 요청 profile 의 파일을 읽지만 다음 항목은 프로세스가 공유한다.

| 공유 항목 | 영향 |
| --- | --- |
| `command_allowlist` | listener 주인 profile 의 값이 모든 profile 에 적용된다. 보조 profile 의 값은 읽히지 않는다 |
| MCP 연결 | 기동 때 한 번 연결하고 profile 별 허용 목록과 교집합을 만든다 |
| 스킬 색인 캐시 | profile 별 키를 쓰지만 디렉터리 내용 변경은 알아채지 못한다 |
| gateway 재시작 | 그 listener 가 제공하는 모든 profile 의 실행에 영향을 준다 |

#### toolset 의 목록과 접근 범위

v0.21.0 의 `toolsets.py` 의 `TOOLSETS` 와
`hermes_cli/tools_config.py` 의 `CONFIGURABLE_TOOLSETS` 를 대조한 결과다.
**profile 분리는 셸이나 파일 도구의 파일 접근을 격리하지 않는다.**
terminal backend 가 `local` 이면 이 도구는 같은 컨테이너 안의 다른 profile 파일에도 닿는다.
`terminal`, `file`, `code_execution` 을 docker 실행 공간으로 옮기는 계약과 남는 구멍은 [실행 공간](hermes-contract.md) 이 갖는다.

| toolset | 대표 도구 | 접근 범위 | 설정 목록 |
| --- | --- | --- | --- |
| `terminal` | `terminal`, `process_manage` | 셸이 닿는 파일, 네트워크와 프로세스 | 있다 |
| `file` | `read_file`, `write_file`, `patch`, `search_files` | 컨테이너 파일 시스템 읽기·쓰기 | 있다 |
| `code_execution` | `execute_code` | Python 에서 열린 다른 도구를 호출한다 | 있다 |
| `browser` | `browser_navigate`, `browser_exec`, `web_search` 등 | 브라우저 조작, 페이지 스크립트와 외부 웹 | 있다 |
| `computer_use` | `computer_use` | 데스크톱 화면과 입력 | 있다 |
| `web`, `x_search` | `web_search`, `web_extract`, `x_search` | 외부 검색·추출 API. `x_search` 는 기본 꺼짐 | 있다 |
| `memory`, `session_search` | 같은 이름의 도구 | profile 기억 읽기·쓰기와 지난 대화 검색 | 있다 |
| `skills` | `skills_list`, `skill_view`, `skill_manage` | 스킬 읽기·쓰기. 외부 스킬도 쓰기 가능하면 수정할 수 있다 | 있다 |
| `cronjob`, `delegation` | `cronjob_manage`, `delegate_task` | 무인 예약 실행과 자식 agent 생성 | 있다 |
| `image_gen`, `video_gen` | `image_generate`, `video_generate`, `xai_video_edit`, `xai_video_extend` | 외부 유료 생성 API. `video_gen` 은 기본 꺼짐 | 있다 |
| `tts`, `stt` | `text_to_speech`. `stt` 는 도구가 없다 | 음성 합성·인식 API. 인식은 `stt.enabled` 로 켠다 | 있다 |
| `vision`, `video` | `vision_analyze`, `video_analyze` | 이미지·영상 분석. `video` 는 기본 꺼짐 | 있다 |
| `todo`, `clarify` | `todo_list`, `clarify` | 실행 안의 할 일과 사용자 질문 | 있다 |
| `context_engine` | 엔진이 정한다 | 기본 엔진이 아닐 때 도구가 생긴다 | 있다 |
| `homeassistant`, `spotify` | `ha_*`, `spotify_*` | 스마트홈 기기와 Spotify 계정. 기본 꺼짐 | 있다 |
| `discord`, `discord_admin` | 같은 이름의 도구 | Discord 읽기·참여·관리. 기본 꺼짐, Discord 전용 | 있다 |
| `yuanbao` | `yb_*` | Yuanbao 메시지 | 있다 |
| `kanban` | `kanban_*` | 디스패처가 띄운 작업자의 작업판 | 없다 |
| `a2a`, `google_meet` | plugin 이 정한다 | plugin toolset. `a2a` 는 기본 꺼짐 | 없다 |
| `project`, `desktop_ui`, `feishu_*`, `bot_room` | GUI 또는 해당 platform 전용 | API 경로와 관계없다 | 없다 |

`_get_platform_tools` 는 모든 활성화 규칙을 적용한 뒤 `agent.disabled_toolsets` 를 뺀다.
없는 비활성화 이름은 집합에서 빠질 것이 없어 오류 없이 무시된다.
허용 목록의 잘못된 이름은 목록 전체가 무효일 때만 마지막 검사에서 알린다.

`platform_toolsets.api_server` 에 이름을 명시해도 그 목록만 켜지는 것은 아니다.

- `_enable_recently_shipped_toolsets` 가 새 toolset 을 더할 수 있다. v0.21.0 의 추가 목록은 비어 있다.
  `known_builtin_toolsets` 에 적힌 이름은 이미 확인하고 거절한 것으로 본다.
- `known_plugin_toolsets` 에 없는 plugin toolset 은 기본 꺼짐 목록을 제외하고 켜진다.
- MCP 서버 이름을 하나도 지정하지 않으면 등록된 MCP 서버가 모두 통과한다.
  전부 막으려면 `no_mcp` 를 둔다.

허용 목록 키를 없애면 `hermes-api-server` 복합 toolset 을 쓴다.
v0.21.0 에서 기본 꺼짐 목록을 뺀 결과는 다음과 같다.

```text
browser, code_execution, cronjob, delegation, file, image_gen, memory,
session_search, skills, terminal, todo, vision, web
```

managed scope 의 `apply_managed_overlay` 는 설정 leaf 마다 사용자 값보다 우선한다.
목록은 통째로 바뀌고 managed scope 는 프로세스에 하나다.
여기에 `agent.disabled_toolsets` 를 두면 모든 profile 에 같은 목록이 적용되어
profile 별 목록을 대신한다.

근거는 [v0.21.0 tools_config.py](https://github.com/NousResearch/hermes-agent/blob/v2026.8.31/hermes_cli/tools_config.py),
[toolsets.py](https://github.com/NousResearch/hermes-agent/blob/v2026.8.31/toolsets.py),
[managed_scope.py](https://github.com/NousResearch/hermes-agent/blob/v2026.8.31/hermes_cli/managed_scope.py) 다.

#### 설정 API와 profile 경계

`hermes_cli.web_routers.config_env.update_config`는 `ConfigUpdate`의 `config`를 기존 원문에 재귀 병합한다.
목록은 받은 목록으로 바뀌며, `model`, `approvals`, `mcp_servers` 같은 다른 키도 막지 않는다.
본문에 `profile`이 있으면 query의 `profile`보다 먼저 선택한다.
`_profile_scope`는 선택한 profile의 설정과 스킬 경로를 가리키지만 요청자가 그 profile의 주인인지는 판단하지 않는다.
`token_auth_middleware`도 등록된 경로의 토큰만 인증하고 본문을 제한하지 않는다.
근거는 [설정 처리기](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/web_routers/config_env.py), [profile 범위](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/web_server_profiles.py), [토큰 인증](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/dashboard_auth/token_auth.py)이다.

plugin 미들웨어가 `await request.json()`으로 본문을 먼저 읽어도 처리기는 같은 본문을 다시 읽어 저장한다.
따라서 plugin은 JSON 객체의 최상위 키와 `config` 아래 키를 정확히 검사하고, query와 본문의 profile이 모두 토큰에 묶인 대상과 같은지 확인해야 한다.

현재와 같은 단일 서비스 토큰에는 profile 신원이 들어 있지 않다.
그 토큰으로 요청한 서로 다른 사용자 사이의 profile 경계를 plugin만으로 증명하려면 profile별 토큰 또는 서버가 검증하는 profile 신원값이 추가로 필요하다.
Control Plane이 주인을 검사해 정확한 profile만 보낼 수는 있지만, 공유 토큰 자체가 유출되면 다른 profile을 지정할 수 있다.
`GET /api/config`는 환경 변수 참조를 펼친 설정을 돌려줄 수 있으므로 도구 화면의 조회 경로로 열지 않는다.

도구 선택값을 저장할 때는 제품이 허용한 이름만 받아 목록 전체를 계산하고, `memory`를 항상 제거해야 한다.
Control Plane MCP(`fos-assistant`)의 서버 이름은 API 허용 목록에 계속 남겨야 한다. 연결을 붙인 에이전트의 profile 도 같고, 그 목록에는 붙인 커넥터의 서버 이름이 함께 있다. 도구 저장은 그 이름을 함께 보내고, 대시보드 plugin 은 그 이름이 빠진 목록을 거절한다([`docs/features/connector.md`](../../docs/features/connector.md) 의 「바인딩 설치」).
남아 있는 옛 커넥터 에이전트의 profile 은 예외다. 그 목록은 설치한 커넥터의 MCP 서버 이름과 그 커넥터의 manifest 가 선언한 읽기 전용 이미지 도구(`vision`)만 갖고 대시보드 plugin 의 설치가 쓴다([ADR-045](../../backend/docs/adr/ADR-045-커넥터-에이전트는-자기-mcp-서버만-받고-memory-와-control-plane-도구를-받지-않는다.md)).
등록된 MCP 서버 이름이 하나도 없는 목록은 모든 활성 MCP 서버를 통과시킬 수 있으므로, 알 수 없는 이름만 남은 목록을 허용해서는 안 된다.
`_get_platform_tools`로 저장 뒤 실제 목록을 계산해 허용 목록과 대조한다.
새 plugin toolset은 저장 목록에 없어도 자동으로 켜질 수 있고, `agent.disabled_toolsets`는 마지막에 적용되므로 둘 다 확인해야 한다.
근거는 [도구 설정 계산](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/tools_config.py)이다.

#### 도구 목록 조회와 버전 차이

`GET /api/tools/toolsets`는 `name`, `label`, `description`, `tools`, `enabled`, `configured`, `platform`을 돌려준다.
격리 컨테이너에서 29개 항목을 받았다.
`enabled`는 `_toolset_configuration_platform`을 따른다. 대부분의 도구에서는 `cli`라 API 실행의 켜짐 상태와 다를 수 있다.
v0.21.3의 API server는 별도 `GET /v1/toolsets`를 제공하며 같은 설명과 도구 목록에 **`api_server` 기준 `enabled`**를 붙인다.
**두 경로의 응답 모양이 다르다.** 대시보드 경로는 항목 배열을 그대로 돌려주고, `GET /v1/toolsets`는 `{"object": "list", "platform": "api_server", "data": [...]}`로 감싼다.
이 목록에는 MCP 서버 이름이 없다. v0.21.3의 실제 응답 29개 항목에도 기억 MCP 이름이 없었고, 이 경로의 구현도 내장 도구 목록을 반환한다.
따라서 Control Plane MCP 서버 `fos-assistant`는 설정에 넣되, 저장 뒤 이 경로로 다시 읽은 결과와 비교하지 않는다.
근거는 [대시보드 도구 경로](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/web_routers/tools.py), [API server 도구 경로](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/gateway/platforms/api_server.py)다.

v0.21.0과 비교하면 v0.21.3의 설정 가능한 목록에 `connections`와 `kanban`이 들어왔다.
`kanban`은 기본 꺼짐이다.
아무 목록도 저장하지 않은 API server의 기본 계산에는 v0.21.0의 목록에 없던 `connections`가 들어간다.
새 profile에 허용 목록 키를 빠뜨리면 도구가 넓게 열리는 이유다.
근거는 [v0.21.0 도구 목록](https://github.com/NousResearch/hermes-agent/blob/v2026.8.31/hermes_cli/tools_config.py), [v0.21.3 도구 목록](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/tools_config.py), [v0.21.3 릴리스](https://github.com/NousResearch/hermes-agent/releases/tag/v2026.9.14)다.

#### 지난 대화 검색의 범위

`session_search` 는 profile 하나에 갇히지 않는다.

| 호출 | 읽는 범위 |
| --- | --- |
| `profile` 인자 없음 | 지금 profile 의 `state.db` 에 있는 모든 대화. API, Discord, CLI 를 가리지 않고, 그룹 공개 에이전트로 다른 사용자가 나눈 대화도 들어 있다 |
| `profile` 인자 있음 | 그 이름의 profile 의 `state.db` 를 읽기 전용으로 연다. profile 이 있는지만 확인한다 |

`kanban`, `subagent`, `tool` 에서 시작한 세션만 목록에서 뺀다.
근거는 [v0.21.3 `tools/session_search_tool.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/tools/session_search_tool.py) 의 `_resolve_profile_db`, `_dispatch` 와 도구 설명의 `profile` 항목이다.
그래서 이 도구는 관리자 등급이고 켜진 에이전트는 비공개로만 둔다([ADR-029](../../backend/docs/adr/ADR-029-에이전트-도구는-control-plane-이-등급으로-판정하고-hermes-설정-api-로-쓴다.md)).

#### 스킬 파일과 색인 적용 시점

대시보드의 `POST /api/skills`와 `PUT /api/skills/content`는 `SKILL.md` 하나만 만들거나 바꾼다.
생성에는 YAML frontmatter의 `name`, `description`, 비어 있지 않은 본문이 필요하다.
스킬 이름과 범주 이름은 최대 64자이며 소문자, 숫자, 점, 밑줄, 붙임표를 쓴다.
새 스킬의 설명은 색인 예산에 맞춰 60자 이하여야 하고 `SKILL.md`는 최대 100,000자다.

v0.21.5 의 `tools/skill_manager_tool.py` 의 `_validate_frontmatter(content, new_skill)` 를 읽고 확인한 검사다.

| 검사 | 언제 | 세는 법 |
| --- | --- | --- |
| 설명 60자(`SKILL_PROMPT_DESC_LIMIT`) | 새 스킬(`create`)만. 고칠 때는 보지 않는다 | `len(desc.strip().strip("'\""))`. 앞뒤 공백과 앞뒤 따옴표를 뺀 Python 문자 수, 곧 code point 수 |
| 설명 1024자(`MAX_DESCRIPTION_LENGTH`) | 늘 | 앞뒤를 빼지 않은 문자 수 |
| 앞머리 뒤 본문 | 늘 | 닫는 `---` 줄 뒤가 공백뿐이면 「SKILL.md must have content after the frontmatter」 로 거절한다 |

이름 64자(`MAX_NAME_LENGTH`)와 설명 1024자는 `tools/skills_tool_plugin.py` 에도 같은 값으로 있다.
색인은 `agent/skill_utils.py` 의 `extract_skill_description` 이 같은 방법으로 앞뒤를 뺀 설명을 60자에서 잘라 57자에 `...` 을 붙인다.
Control Plane 이 저장 규칙을 이 검사에 맞추는 까닭은 [ADR-034](../../backend/docs/adr/ADR-034-올린-스킬은-control-plane-이-버전-디렉터리에-쓰고-hermes-는-읽기만-한다.md) 의 「저장할 수 있는 스킬은 Hermes 가 제대로 고를 수 있는 스킬이다」 에 있다.
근거는 [v0.21.5 skill_manager_tool.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/skill_manager_tool.py), [skill_utils.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/agent/skill_utils.py), [skills_tool_plugin.py](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/skills_tool_plugin.py) 다.
이 두 HTTP 경로에는 `references/`, `templates/`, `assets/`, `scripts/` 파일 인자가 없다.
`skill_manage(write_file)`는 해당 네 하위 디렉터리의 텍스트 파일을 다루며 파일당 1 MiB와 100,000자 제한이 있지만, 대시보드 HTTP 경로로 노출되지 않았다.
근거는 [스킬 HTTP 경로](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/web_routers/skills.py), [스킬 쓰기와 검증](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/tools/skill_manager_tool.py)이다.

`DELETE /api/skills`는 없다. 스킬을 빼려면 `skills.external_dirs`에서 경로를 뺀다.

대시보드가 스킬을 만들면 자기 프로세스의 색인 캐시만 비운다.
공유 gateway의 `build_skills_system_prompt` 메모리 캐시 키에는 스킬 디렉터리 경로와 비활성화 목록은 있지만 디렉터리 내용은 없다.
격리 컨테이너에서 별도 프로세스의 `POST /api/skills`가 200으로 파일을 만든 뒤에도 이미 색인을 계산한 프로세스의 다음 색인에는 새 스킬이 없었다.
별도 프로세스의 `PUT /api/skills/content`로 설명을 고쳐도 기존 설명이 남았다.
반면 `skills.external_dirs`에 새로운 버전 경로를 더하자 같은 프로세스의 다음 색인에 새 스킬이 나타났다.
`PUT /api/skills/toggle`로 `skills.disabled`를 바꾸면 캐시 키가 달라져 다음 색인에서 빠졌다.
`/reload-skills`는 명령 목록을 다시 훑지만 시스템 프롬프트 캐시는 비우지 않는다.
근거는 [색인 캐시](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/agent/prompt_builder.py), [스킬 경로 계산](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/agent/skill_utils.py), [다시 읽기 명령](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/gateway/slash_commands.py)이다.

따라서 스킬 묶음은 profile 전용의 새 버전 디렉터리에 전부 올린 뒤 검사하고, 마지막 `PUT /api/config`에서 해당 디렉터리만 `skills.external_dirs`에 게시하는 편이 적용 시점을 확실하게 만든다.
수정도 기존 경로를 덮어쓰지 않고 새 버전을 게시한다.
profile 로컬 스킬이 같은 이름이면 외부 스킬보다 먼저 선택되므로 이 경로와 `POST /api/skills`를 같은 이름에 섞지 않는다.
실행 중인 요청은 이미 만든 프롬프트를 계속 쓸 수 있다.
`skill_view`는 파일을 직접 읽지만 색인 갱신을 대신하지 않는다.

#### profile 생성과 Control Plane MCP

`POST /api/profiles`가 받는 이름은 정규화 뒤 `[a-z0-9][a-z0-9_-]{0,63}`에 맞아야 하며 예약 이름은 거절한다.
생성 함수에는 profile 개수 상한이 없다. 서비스 자체의 자원 한도는 별도로 정해야 한다.
`no_skills: true`는 번들 스킬 심기를 건너뛰고, clone 옵션과 함께 쓸 수 없다.
clone은 설정과 `.env`를 복사하므로 사용자별 profile을 만들 때 쓰지 않는다.
`API_SERVER_KEY`를 `PUT /api/env`로 넣으면 공유 listener는 다음 요청부터 새 key를 읽는다.
v0.21.3 공유 gateway는 profile 추가를 감지해 adapter를 추가하며, 새 profile에는 MCP 발견도 시도한다.
근거는 [profile 생성](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/profiles.py), [생성 HTTP 경로](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/web_routers/profiles.py), [공유 gateway의 profile 감지](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/gateway/run_profile_reconcile.py)다.

생성 HTTP 본문에는 `mcp_servers`도 있으나, 생성 처리기는 profile을 게시하고 gateway에 알린 다음 MCP 설정을 best effort로 쓴다.
MCP 설정 쓰기 실패가 profile 생성 실패로 바뀌지 않는다.
따라서 이 인자만으로 새 profile의 Control Plane MCP 연결을 원자적으로 보장할 수 없다.
기존 profile의 `POST /api/mcp/servers`도 설정 저장이지 gateway 연결 완료가 아니다.
공유 gateway에는 profile 대화의 `/reload-mcp`가 있으며 재시작 없이 MCP를 다시 발견하지만, 기본적으로 확인 절차를 거치고 이 기능을 직접 호출하는 전용 HTTP 경로는 찾지 못했다.
새 profile의 자동 발견 또는 이 명령을 쓸 수 없는 경우에는 공유 gateway 재시작이 확실한 적용 경로다.
근거는 [MCP 설정 API](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/web_routers/mcp.py), [MCP 재발견](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/gateway/run_turn.py)이다.

공유 gateway의 housekeeping은 60초마다 served profile마다 `mcp_servers`의 이름과 살아 있는 연결을 맞춘다.
새 이름은 연결하고 빠진 이름은 끊는다. 이름만 비교하므로 같은 이름의 정의나 env 값이 바뀐 것은 다시 연결하지 않는다.
스킬 색인 캐시 키에는 profile 스킬 디렉터리의 내용이 없고 `skills.disabled`가 들어 있다.
그래서 바인딩 설치는 스킬 파일을 바꿀 때 `skills.disabled`의 색인 표식(`fos-skill-index-` 앞머리)을 새 값으로 바꿔 그 profile의 다음 실행이 색인을 새로 만들게 한다.
판정과 근거는 [ADR-20261007 / connector-live-reload](../../docs/adr/ADR-20261007-connector-live-reload.md)가 갖는다.

## Hermes 를 올릴 때

### provider 를 바꿀 때 확인할 계약

provider 를 바꿔도 Control Plane 은 profile 바인딩, 요청한 모델과 effort, 실제 실행 경로와 사용량을 같은 뜻으로 다룬다.
지원 여부와 credential 선택, 토큰 보고 방식은 provider 에 따라 달라질 수 있다.
[ADR-060](../../docs/adr/ADR-060-reasoning-effort-의-지원은-확인한-것만-보이고-모르면-미확인으로-둔다.md),
[ADR-067](../../backend/docs/adr/ADR-067-native-하위-에이전트의-provider-는-대시보드-plugin-이-session-저장소에서-읽어-준다.md),
[ADR-088](adr/ADR-088-대시보드-plugin-은-감싸는-경로의-바꿔-끼우기를-두고-기대는-hermes-내부-지점을-계약-시험으로-확인한다.md)과 현재 구현을 기준으로 아래 범위를 확인한다.

#### 가짜 Hermes 로 확인할 수 있는 것

여기서 가짜 Hermes 는 HTTP 응답 fixture, backend 의 stub 과 mock, 가짜 Hermes 모듈과 임시 SQLite 저장소를 포함한다.
시험 이름은 `backend/src/test/java/com/bifos/assistant/` 와 `hermes/tests/` 아래의 파일을 가리킨다.
응답을 어떻게 읽고 요청을 어떻게 보내는지 확인하는 범위이며, 실제 provider 의 지원과 청구 방식을 증명하지 않는다.

| 지점 | 유지할 계약 | 시험 |
| --- | --- | --- |
| 모델 목록과 reasoning 지원 | 인증된 provider 의 모델만 고른다. 같은 모델 이름이어도 provider 별 capability 를 따로 읽는다. boolean 이 아닌 값과 빠진 칸은 `UNKNOWN` 이며 provider 이름으로 메우지 않는다 | `hermes/HermesModelCatalogTest` |
| reasoning 끄기 | 미지정과 `none` 을 구분한다. `none` 은 끄기 지원이 `SUPPORTED` 이고 reasoning 이 `UNSUPPORTED` 가 아닐 때만 고른다. 요청 effort 를 적용값으로 바꿔 기록하지 않는다 | `chat/ModelOptionsServiceTest`, `chat/ModelSelectionTest`, `hermes/HermesRunRequestTest` |
| 실행별 모델 선택 | provider 와 모델을 함께 보내거나 함께 뺀다. provider 를 바꿔도 모델과 effort 를 그대로 보내며, profile 기본값을 쓸 때는 모델 선택 칸을 뺀다. 실패했다고 Control Plane 이 다른 provider 로 다시 제출하지 않는다 | `hermes/HermesRunRequestTest`, `chat/ModelSelectionTest` |
| 단계와 에이전트 기본 모델 | 모델 단계의 provider 가 비면 요청 profile 의 카탈로그로 정한다. 단계의 모델이 그 카탈로그에 없거나 선택한 모델이 숨겨졌으면 다른 provider 로 대체하지 않고 거절한다. 대화 선택, 에이전트 기본 모델, profile 기본값의 우선순위를 유지한다 | `chat/ModelTierServiceTest`, `chat/ModelVisibilityTest`; 선택 규칙은 [모델 단계](../../docs/features/schedule.md) |
| 요청 경로와 실제 경로 | 실행 조회의 요청 모델과 실제 `runtime` 을 구분한다. Hermes 가 알려 준 실제 provider 와 모델로 실행을 기록한다. 기본값과 요청값으로 실제 경로를 추정하지 않는다 | `hermes/HermesRuntimeReadTest`, `chat/ModelSelectionTest` |
| profile 인증 경계 | provider 교체로 API 인증 key 를 바꾸지 않는다. 요청자의 바인딩에서 profile 을 정하고 key 가 없으면 다른 profile 의 key 를 빌리지 않는다. 모델 목록 cache 도 profile 별로 나눈다 | `hermes/HermesRunRequestTest`, `hermes/HermesProfileKeyStoreTest`, `chat/ChatServiceTest`, `chat/ModelOptionsServiceTest` |
| credential 쓰기 경계 | 대시보드의 환경 쓰기는 허용한 Control Plane 칸만 받는다. 커넥터 연결 해제로 모델 credential 을 지우지 않는다. `credential_scope` 는 공유 여부의 선언이며 실제 OAuth 격리를 강제하는 값이 아니다 | `test_dashboard_profile_api_env.py`; 선언의 뜻은 [credential 경계](hermes-contract.md#oauth-credential-은-여기서-빠진다) |
| 루트 usage 읽기 | 입력·출력·cache 칸의 알려진 별칭을 같은 토큰 수로 읽는다. 보고하지 않은 칸은 0 으로 채우지 않는다. total 이 없고 입력·출력이 모두 있으면 합을 구한다. cache 를 입력에 임의로 더하지 않는다 | `hermes/HermesUsageParsingTest` |
| 가격과 비용 모드 | provider 와 모델의 짝으로 가격을 찾는다. 같은 모델이어도 provider 별 가격을 적용하고 가격 미확인은 금액을 비운다. 입력에 포함된 cache 는 한 번만 센다. 구독은 실제 청구액을 비우고 API 는 환산액과 같은 값으로 기록한다 | `usage/CostEstimatorTest`, `usage/UsageCostRecordingTest` |
| native 자식 provider | session 응답의 provider 를 우선하고 없으면 plugin 을 조회한다. 부모 provider 를 빌리지 않는다. 주 호출이 같은 모델에서 provider 만 바꿔도 복수 경로로 보고 provider 를 비운다. 같은 짝의 반복 호출과 보조 호출은 교체로 세지 않는다. 읽기는 저장소를 바꾸지 않는다 | `hermes/SubagentProviderClientTest`, `usage/application/SubagentUsageReconcilerTest`, `test_dashboard_profile_api_session.py` |
| native 자식의 미확인과 합계 | session 의 입력에 cache 읽기·쓰기를 더해 run 과 같은 포함 입력으로 환산한다. 필요한 토큰을 모르면 금액을 비운다. 조회 불가 재시도는 종료 뒤 10분까지이며 이후 `PROVIDER_UNKNOWN` 으로 남긴다. usage·가격 미확인을 구분한다. 부모와 자식 사용량을 중복 없이 더하며 재조회가 원장을 다시 더하지 않는다 | `usage/application/SubagentUsageReconcilerTest`, `usage/application/SubagentUsageLedgerTest`, `usage/UsageBreakdownTest` |

#### 운영 왕복이 필요한 것

아래는 확인 대상만 적는다. 실행 절차와 계정·환경 값은 `fos-home-infra` 가 소유한다.
provider 교체 전후에 해당하는 항목을 확인하고, 지원하지 않거나 관측할 수 없는 항목은 그 이유를 남긴다.

| 지점 | 확인 대상 |
| --- | --- |
| 실제 모델 선택 | 새 provider 의 인증 상태와 모델 목록이 해당 profile 의 계정과 맞고, 실행별 선택과 profile 기본값이 실제 `runtime` 의 provider·모델로 이어지는가 |
| 같은 session 의 provider 교체 | provider 를 바꾼 다음 turn 에서 앞 대화와 도구 호출 이력이 유지되는가. Responses 계열의 암호화된 reasoning 조각을 새 경로가 처리하거나 걸러 내는가. 실제 계정에서만 보이는 이력 호환 오류가 없는가([session 연속성](hermes-contract.md#모델을-바꿔-이어도-맥락이-남는다)) |
| 실제 reasoning 적용 | Hermes 가 알린 지원값과 실제 provider 가 받는 값이 맞는가. 미지정, `none`, 지원하는 effort 의 생략·축소·변환이 Hermes 계약과 맞는가. 실행 기록의 effort 는 요청값이라는 구분이 유지되는가 |
| AI credential 선택과 갱신 | API server key 와 AI provider credential 이 다른 역할을 유지하는가. `SHARED_HOUSEHOLD` 의 공유와 `DEDICATED` 의 전용 계정이 실제 인증·갱신에서도 선언과 맞는가. profile 의 OAuth 가 없을 때 루트 저장소를 쓰는 경로와 API key 선택이 예상과 맞는가 |
| 실패와 fallback | credential 이 없거나 만료되거나 계정이 막혔을 때 다른 profile 의 계정이나 다른 provider 로 넘어가지 않는가. profile 의 `fallback_providers` 가 비어 있고, Hermes 내부 경로 변경이 있으면 실제 경로가 정확히 기록되는가 |
| 실제 usage 의미 | 입력 토큰에 cache 읽기·쓰기가 포함되는가, 출력에 reasoning 토큰이 포함되는가, 누락과 0 이 구분되는가. cache 단가와 context 구간이 실제 보고 방식에 맞는가. 에이전트의 `cost_mode` 가 새 provider 계정의 구독·종량 경로와 맞는가. 알려진 별칭을 파싱하는 시험만으로 입력 포함 관계까지 확인했다고 보지 않는다 |
| 부모와 native 자식 | 부모 run usage 에 native 자식 토큰이 포함되지 않는가. 자식 session 의 입력은 cache 읽기·쓰기를 제외한 값이고 run 의 입력은 포함한 값인가. 자식 session 의 최종 카운터와 provider 가 실제 실행과 맞는가. `sessions` 와 `session_model_usage` 가 실제 주 호출 경로를 기록하고 보조 호출을 구분하는가 |
| plugin 과 Hermes 내부 지점 | 배포한 소스의 이름·시그니처·저장소 schema 가 선언과 맞으며, 등록된 미들웨어와 profile 범위가 실제 HTTP 요청에서도 동작하는가. provider 조회가 읽기 전용이고 권한 밖 session 을 돌려주지 않는가 |

ADR-088 의 `test_hermes_contract.py` 는 상류 소스에서 이름·시그니처·schema 와 일부 응답 모양을 확인하는 별도 검사다.
가짜 Hermes 의 동작 시험과 운영 왕복 사이에서 구조 변경을 먼저 잡지만, 같은 이름의 함수가 다르게 동작하거나 상류 tag 와 배포 이미지가 다른 것은 왕복 확인으로 판단한다.
실제 usage 가 위 환산 가정과 다르면 provider 이름별 보정값을 추측해 넣지 않고, 확인한 계약과 그에 맞는 fixture·처리를 함께 고친다.

### 버전을 올릴 때 달라지는 계약

2026-09-28 에 v0.21.3 과 v0.21.5 의 태그 소스와 변경 이력을 대조했다.
v0.21.3 은 `v2026.9.14`, commit `345cd2b057a452236de401d3534b8502a7465e8d` 이다.
조사 당시 최신 안정판 v0.21.5 는 `v2026.9.24`, commit `f97608f178d1ffeca59860195ab7da295f7c8e5f` 이다.
최신판 표시는 조회 날짜의 결과이며 계속 최신이라고 가정하지 않는다.
소스 호환 판정과 실제 배포 검증은 구분한다. 운영 절차는 `fos-home-infra` 에 둔다.

#### 연동에 영향을 주는 변경

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
[ADR-086](../../docs/adr/ADR-086-셸과-파일-도구는-사용자별-docker-실행-공간에서만-돈다.md) 는 docker backend 의 아래 동작에 기댄다.
하나라도 바뀌면 셸 계열 도구가 다시 Hermes 컨테이너로 새거나 실행 공간이 동작하지 않는다.
[실행 공간](hermes-contract.md) 의 「측정 결과」 시험을 새 이미지에서 다시 돌린다.

- 공유 listener 가 turn 마다 profile 의 `terminal.*` 를 scope 로 묶는다(`_profile_runtime_scope`, `install_and_reset_profile_terminal_scope`)
- `_get_file_ops` 와 `execute_code` 가 terminal 과 같은 환경을 쓴다
- `docker_volumes`, `docker_extra_args`, `env_passthrough`, `credential_files` 의 이름과 뜻
- 스킬 앞머리에서 환경 값과 파일을 실행 공간에 넣는 칸의 이름(`tools/skills_tool_setup.py` 의 `_get_required_environment_variables`, `required_credential_files`). 칸이 늘면 올린 스킬 저장 검사(`SkillFrontmatter`)와 대시보드 plugin 의 커넥터 스킬 검사(`_skill_name`)에 함께 더한다
- `HERMES_WRITE_SAFE_ROOT` 를 docker backend 의 파일 쓰기에도 경로 문자열로 적용한다
- `execute_code` 가 RPC 로 부를 수 있는 도구(`SANDBOX_ALLOWED_TOOLS`)에 MCP 도구가 없다. 생기면 [ADR-20261008 / connector-output-files](adr/ADR-20261008-connector-output-files.md) 를 다시 검토한다
- gateway 가 `HERMES_EXEC_ASK` 를 켜고, 셸 위험 명령과 plugin 승인 요청이 ask 문맥에서 `approvals.unattended_mode` 를 보지 않는다. 계약 시험이 확인한다. 바뀌면 docker profile 의 `unattended_mode: approve` 가 그 둘까지 승인 없이 열므로 [ADR-20261008 / execute-code-unattended](adr/ADR-20261008-execute-code-unattended.md) 를 다시 본다

**올린 스킬과 이름이 겹치는 스킬이 새로 생기지 않았는지 본다.**
Hermes 를 올리면 번들 스킬이 늘 수 있다. 같은 이름이면 profile 로컬 스킬이 외부 디렉터리의 올린 스킬보다 먼저 선택되어,
화면에 보이는 스킬과 실제로 도는 스킬이 달라진다.
업그레이드와 배포 확인은 profile 마다 올린 스킬 이름과 Hermes 가 더 앞서 고르는 스킬 이름이 겹치지 않는지 보고, 겹치면 배포를 멈춘다.
그 검사의 절차는 `fos-home-infra` 가 갖는다. 까닭은 [ADR-034](../../backend/docs/adr/ADR-034-올린-스킬은-control-plane-이-버전-디렉터리에-쓰고-hermes-는-읽기만-한다.md) 의 「Hermes 를 올릴 때 이름 충돌을 본다」 에 있다.

**올린 Hermes 이미지의 `mcp` SDK 버전을 본다.**
대시보드 plugin 의 커넥터 도구 호출은 Hermes 가 설치한 `mcp` Python SDK 를 쓰고, 지원 범위는 `mcp>=2.0,<3` 이다([커넥터 설치](../../docs/features/connector.md) 의 「MCP SDK 계약」).
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

#### 설정 migration 과 DB migration 의 범위

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

#### Runs 응답과 사건의 버전 차이

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

### 대시보드 plugin 이 기대는 내부 지점

`dashboard-profile-api` 는 공개 확장점이 아닌 Hermes 내부 지점에 기댄다.
감싸는 미들웨어, import 하는 내부 함수, session 저장소의 칸, 감싸는 대시보드 경로가 여기 해당한다.
목록은 [`hermes/tests/hermes_contract.py`](../tests/hermes_contract.py) 가 갖고, 왜 공개 확장점으로 바꾸지 못하는지는 [ADR-088](adr/ADR-088-대시보드-plugin-은-감싸는-경로의-바꿔-끼우기를-두고-기대는-hermes-내부-지점을-계약-시험으로-확인한다.md) 이 갖는다.

Hermes 를 올리기 전에 새 판의 tag 로 `scripts/check-hermes-contract.sh <tag>` 를 돌린다.
실패한 항목을 plugin 에서 고치고, `HERMES_VERSION` 을 새 tag 로 바꾼 PR 의 CI 가 통과한 뒤 이미지를 올린다.

#### MCP 설정 맞추기와 스킬 색인 캐시 키

바인딩 설치가 재시작 없이 반영되는 것은 두 내부 동작에 기댄다([ADR-20261007 / connector-live-reload](../../docs/adr/ADR-20261007-connector-live-reload.md)).

- 공유 gateway 의 housekeeping 에 MCP 설정 맞추기 작업이 있고, 그 작업이 profile 마다 `reconcile_mcp_servers_with_config` 를 부른다
- 스킬 색인 캐시 키에 `skills.disabled` 에서 읽은 이름이 들어 있다. plugin 이 바꾼 색인 표식이 그 profile 의 색인을 새로 만들게 한다

[`hermes/tests/hermes_contract.py`](../tests/hermes_contract.py) 의 `LIVE_RELOAD` 가 이 지점을 선언하고, `HermesSourceTest.test_live_reload_points` 가 소스에서 확인한다.
실패하면 붙인 커넥터가 재시작 전까지 보이지 않는데 Control Plane 은 반영됐다고 판정할 수 있다. ADR 의 판정을 다시 본다.

### 커넥터 정책이 기대는 계약

Hermes 를 올릴 때 아래가 그대로인지 본다. 하나라도 달라지면 커넥터 도구의 판정이 비켜 갈 수 있다.
계약의 내용은 [도구 hook 과 승인](hermes-contract.md) 에 있다.

| 계약 | 달라지면 |
| --- | --- |
| MCP 도구의 등록 이름 규칙(`mcp_prefixed_tool_name`) | 대시보드 plugin 의 `_hermes_tool_name` 을 같은 규칙으로 고친다. 다르면 hook 이 도구를 대응 파일에서 찾지 못해 모두 막는다 |
| 글이 있는 `block` 이 MCP 요청을 막는다 | hook 으로 강제할 수 없다. wrapper 가 필요하다 |
| 중계 도구 `tool_call` 이 hook 에 안쪽 등록 이름을 준다 | 중계 도구를 커넥터를 설치한 profile 에서 막는다 |
| `mcp_servers.<서버>.tools.exclude` | `approval: always` 인 도구가 모델에게 보인다. 호출은 여전히 Control Plane 이 거절한다 |
| `PluginContext.call_mcp` 가 `mcp_allowlist` 없는 서버를 부르지 못한다 | plugin 의 직접 호출이 판정 없이 나간다 |
| `transform_tool_result` hook 은 처음 돌려준 글이 결과를 바꾸고, hook 이 실패하면 원래 결과가 가며, 판정이 막은 호출에는 닿지 않는다 | 바인딩 profile 의 커넥터 결과가 `<external-data>` 없이 들어가거나, 판정이 막은 안내 글까지 외부 글로 감싸져 모델이 승인을 기다리라는 안내를 따르지 않을 수 있다. 결과 hook 이 도는 자리를 다시 확인하고 `fos-ctx` 를 고친다 |
