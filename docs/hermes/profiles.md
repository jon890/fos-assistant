# profile 생성

도구 없는 시스템 판단 profile은 [가치 평가](../backend/value-evaluation.md)의 준비 상태 계약을 따른다.
`GET /api/profiles/{name}/decision-readiness`는 Control Plane 관리 토큰으로 그 profile의 준비 여부만 읽는다.

## profile 을 HTTP 로 만드는 길

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

### 기계용 인증 자리가 따로 있다

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

### plugin 이 token provider 를 붙일 수 있다

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

### 경로는 문자열이 정확히 같아야 한다

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

### plugin 이 대시보드에 자기 경로를 더하는 hook 은 없다

`ctx` 의 등록 메서드 어디에도 대시보드 라우트를 더하는 것이 없다.
API server 쪽에는 `register_platform_handler("api_server", factory)` 가 있지만
대시보드 웹서버에는 대응하는 자리가 없다.
그래서 plugin 이 할 수 있는 것은 이미 있는 경로를 기계에게 여는 것까지다.
기존 대시보드 미들웨어를 감싸 생성 처리 앞뒤에 코드를 두는 방법은 별개이며,
아래 「profile 을 만드는 요청 안에서 도구 설정을 검증한다」 에 그 근거를 적었다.

### `SOUL.md` 를 읽고 쓰는 두 경로

v0.21.0 의 `hermes_cli/web_routers/profiles.py` 와 `hermes_cli/web_models.py` 를 읽어 확인했다.

| 경로 | 본문 | 응답 |
| --- | --- | --- |
| `GET /api/profiles/{name}/soul` | 없다 | `content` 와 `exists` |
| `PUT /api/profiles/{name}/soul` | `content` 하나. 문자열이고 필수다 | `ok` |

`GET` 은 파일이 없으면 `content` 를 빈 문자열로 두고 `exists` 를 거짓으로 돌려준다.
읽다가 실패하면 500 이고, 그때는 빈 본문이 아니라 오류가 온다.

`PUT` 은 받은 `content` 로 파일 전체를 바꾼다. 일부를 고치는 것이 아니다.
임시 파일에 쓰고 fsync 한 뒤 원본 자리에 옮기므로, 쓰다가 끊겨도 앞 내용이 반쯤 남지 않는다.

**읽는 경로가 있다.** 이 저장소가 앞서 「쓰는 경로만 있다」 로 적어 그 위에 설계를 세운 적이 있다.
근거로 삼은 것이 코드가 아니라 이 문서의 표였고, 그 표에 `GET` 이 빠져 있었다.

### `POST /api/profiles` 가 실제로 만드는 것

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
그 profile 이 쓰는 스킬은 [「스킬을 profile 에 붙이는 방법」](skills.md#스킬을-profile-에-붙이는-방법) 대로 따로 붙인다.

**CLI 의 `--no-alias` 에 해당하는 본문 필드가 없다.**
API 로 만들면 wrapper 가 함께 생긴다.
`DELETE` 로 지우면 wrapper 와 s6 서비스가 함께 사라지는 것까지 확인했다.

`clone_from` 을 주면 원본의 `config.yaml` 과 `.env` 와 `SOUL.md` 와 `skills/` 와
`memories/MEMORY.md` 와 `memories/USER.md` 를 복사한다.
`clone_all` 을 주면 원본 전체를 복사하고 runtime 파일과 단일 사용 OAuth 파일만 뺀다.

### 틀 없이 만든 profile 은 API 도구가 넓게 열린다

2026-09-28 에 v0.21.0 소스로 확인했다.
`hermes_cli/profiles.py` 의 `_seed_model_config` 는 활성 profile 의 `model` 블록만 쓴다.
`platform_toolsets.api_server` 와 `agent.disabled_toolsets` 는 생성되지 않는다.
따라서 [「toolset 의 목록과 접근 범위」](tools-and-skills.md#toolset-의-목록과-접근-범위) 의 `hermes-api-server` 기본 목록이 적용되어
`terminal`, `file`, `memory`, `delegation` 등을 포함한 도구가 열린다.
`no_skills` 는 번들 스킬 심기를 막는 값이며 도구 설정을 제한하는 값은 아니다.
새 profile 을 만들면 key 를 주기 전에 도구 설정을 적용하고 검증해야 한다.

### profile 을 만드는 요청 안에서 도구 설정을 검증한다

2026-09-28 에 v0.21.0 소스로 검토한 방식이다.
**아래는 Hermes 가 제공하는 동작을 조합한 설계이며, 이 조사에서 구현하거나 실행하지 않았다.**
profile 생성 뒤의 공식 plugin hook 은 없다.
대신 기존 `hermes_cli/dashboard_auth/token_auth.py` 의 `token_auth_middleware` 를
모듈 속성으로 감싸면 다음 대시보드 요청부터 기존 생성 처리 앞뒤에 코드를 둘 수 있다.

| 필요한 동작 | Hermes 근거 |
| --- | --- |
| 생성된 profile 판별 | `hermes_cli.profiles.list_profile_names` 의 생성 전후 차이 |
| profile 문맥 선택 | `hermes_constants.set_hermes_home_override` 와 reset. 대시보드 `_profile_scope` 와 같은 방식 |
| 설정 저장 | `hermes_cli.config.save_config`. 원래 `model` 블록을 남기고 나머지 설정에 template 을 적용한다 |
| 실제 API 도구 계산 | `hermes_cli.tools_config._get_platform_tools(config, "api_server")` |
| 실패한 생성 정리 | `hermes_cli.profiles.delete_profile(name, yes=True)` |

처리 순서는 다음과 같다.

1. 생성 전 목록을 읽고 기존 생성 처리기를 호출한다. 400 이상이면 응답을 그대로 돌려준다.
2. 새 이름이 정확히 하나인지 확인한다. 판별하지 못하면 성공으로 보고하지 않는다.
3. 그 profile 문맥에서 설정 template 과 `.no-bundled-skills` 표식을 쓴다.
4. `_get_platform_tools` 로 계산해 금지 도구가 없는지 확인한다.
5. 저장이나 계산이 실패하면 새 profile 을 지우고 실패 응답을 돌려준다.
6. 검증이 끝난 뒤 별도 요청으로 key 를 넣는다.

**clone 없이 만드는 경우 설정 검증 중에는 key 가 없어 공유 listener 의 접두 요청이 거절된다.**
이 순서로 생성과 설정 검증을 같은 요청 안에서 끝낼 수 있다.
이 내부 함수가 업그레이드로 달라지면 생성 성공을 반환하지 않도록 처리해야 한다.
plugin 로딩은 기동 때 이뤄져 plugin 변경에는 대시보드 재시작이 필요하다.
설정 적용만을 위해 gateway 를 다시 띄울 필요는 없다.

표식은 다음 번들 동기화를 막을 뿐 이미 심은 스킬을 지우지 않는다.
처음부터 심지 않으려면 생성 본문에 `no_skills: true` 를 준다.
`skills` toolset 을 닫으면 이미 있는 스킬 색인은 입력에 들어가지 않는다.
이 wrapper 는 `clone_from` 으로 이미 복사한 `.env` 를 되돌리지 않으므로,
clone 의 key 복사 문제까지 해결하는 것으로 해석하면 안 된다.

별도 `PUT /api/config` 로 template 을 쓰는 방식은 두 번째 요청 누락 시 넓은 도구가 남고,
해당 경로를 열면 모든 profile 의 설정 키를 쓸 수 있다.
안전한 profile 을 clone 하는 방식도 원본 `.env` 에 뒤에 추가된 값이 복사될 수 있다.
`pre_tool_call` 의 `{"action": "block"}` 은 실행 시점의 추가 차단에 쓸 수 있지만
모델에 실리는 도구 정의와 입력 비용을 줄이지 않는다.
managed scope 는 프로세스 전체에 적용되므로 profile 별 template 을 대신하지 못한다.
이미 존재하는 listener 주인 profile 의 접두 없는 요청은 생성 wrapper 의 대상이 아니다.
그 범위는 `platform_toolsets.api_server` 또는 별도 실행 차단으로 정해야 한다.

근거는 [v0.21.0 profiles.py](https://github.com/NousResearch/hermes-agent/blob/v2026.8.31/hermes_cli/profiles.py),
[token_auth.py](https://github.com/NousResearch/hermes-agent/blob/v2026.8.31/hermes_cli/dashboard_auth/token_auth.py),
[plugins.py](https://github.com/NousResearch/hermes-agent/blob/v2026.8.31/hermes_cli/plugins.py),
`model_tools.py` 의 `_dispatch_pre_tool_call_hooks` 다.

### `clone_from` 은 원본의 `API_SERVER_KEY` 까지 복사한다

profile 을 하나 만들어 `API_SERVER_KEY` 를 넣고,
그 profile 을 `clone_from` 으로 복제해 확인했다.
두 `.env` 의 `API_SERVER_KEY` 가 같은 값이었고, 그 값 하나로 두 접두가 모두 200 을 돌려줬다.

**그러면 key 가 profile 을 구분하는 값이 아니게 된다.**
사용자를 더할 때 `clone_from` 을 쓰지 않거나, 쓴 직후에 그 profile 의 key 를 새 값으로 덮어야 한다.

### `API_SERVER_KEY` 는 `PUT /api/env` 로 넣는다

본문에 `profile` 과 `key` 와 `value` 를 준다.
쓰기 금지 목록에 `API_SERVER_` 로 시작하는 이름이 없다.
그 목록이 막는 것은 `PATH` 와 `PYTHONPATH` 처럼 하위 프로세스 실행에 영향을 주는 이름과
`HERMES_HOME` 처럼 Hermes 의 위치와 보안 정책을 정하는 이름이다.

`API_SERVER_KEY` 와 `API_SERVER_MODEL_NAME` 을 이 경로로 넣어 실제로 들어가는 것을 확인했다.

### 공유 listener 를 쓰는 profile 에는 listener 설정을 넣지 않는다

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
설정 틀 [`config.yaml.template`](../../hermes/profile-template/config.yaml.template) 이 `platforms.api_server.enabled: false` 를 넣는다.
그래서 Control Plane 이 만든 profile 은 기동할 때 이 경고를 남기지 않는다.

### 넣은 직후 공유 listener 가 답한다

key 를 넣고 gateway 를 다시 띄우지 않은 채로 불렀다.

| 요청 | 응답 |
| --- | --- |
| 방금 넣은 key 로 `/p/<profile>/v1/capabilities` | 200 |
| 틀린 key 로 같은 경로 | 401 |

공유 listener 가 요청마다 profile 디렉터리를 훑기 때문이다.

### 파일로만 되는 것이 남는다

Control Plane 이 읽는 key 파일은 Hermes 밖의 파일이고 Hermes 의 API 가 닿지 않는다.
그 파일을 두는 자리는 `fos-home-infra` 가 소유한다.
`config.yaml` 전체를 우리 템플릿으로 덮는 것은 `PUT /api/config/raw` 가 열려 있지만 확인하지 않았다.

### 대시보드 인증이 켜지는 조건

대시보드가 loopback 이 아닌 주소에 붙거나 `dashboard.public_url` 이 설정돼 있으면 인증이 켜진다.
켜진 상태에서 등록된 auth provider 가 하나도 없으면 대시보드가 기동을 거부한다.
token provider 하나만 있어도 이 조건을 채운다.
측정용 대시보드를 loopback 에 붙이고 `dashboard.public_url` 만 넣어 인증을 켠 채로 확인했다.

## Control Plane 이 부르는 대시보드 plugin 경로

Hermes 대시보드 앞에는 우리 대시보드 plugin 이 있다. plugin 은 이 저장소의 [`hermes/plugins/dashboard-profile-api/`](../../hermes/plugins/dashboard-profile-api/) 에 있고, 서비스 토큰으로 오는 요청을 정해 둔 경로로만 받는다.
2026-09-29 에 정했다. 사용자의 에이전트 만들기와 스킬([ADR-033](../adr/ADR-033-사용자가-에이전트를-만들고-공개해도-만든-사람이-관리한다.md), [ADR-034](../adr/ADR-034-올린-스킬은-control-plane-이-버전-디렉터리에-쓰고-hermes-는-읽기만-한다.md))이 이 계약에 기댄다.
경로마다의 요청과 응답과 인증은 [`hermes/README.md`](../../hermes/README.md) 의 「dashboard-profile-api 가 여는 것」 표가 갖는다. 커넥터 경로를 Control Plane 이 쓰는 방법은 [커넥터 설치](../backend/connector-install.md) 의 「대시보드 plugin 계약」 이 갖는다.
이 절은 그 경로를 지날 때 Hermes 가 어떻게 동작하는지만 적는다.

**틀이 MCP 와 서명 plugin 을 붙인다.** `POST /api/profiles` 본문의 `mcp_servers` 는 쓰지 않는다. plugin 이 처리 뒤에 설정 틀로 `config.yaml` 전체를 다시 써서, 본문으로 넣은 등록이 사라진다.
토큰 값은 틀에 없고 Control Plane 이 `PUT /api/env` 로 넣는다. 토큰을 넣기 전의 MCP 연결 실패가 쌓이면 다시 붙는 간격이 길어지므로 만든 직후에 넣는다.

**`skills.external_dirs` 에 없는 디렉터리를 넣으면 Hermes 는 오류 없이 건너뛴다.** 올린 스킬이 조용히 모두 사라지므로 plugin 이 게시 전에 디렉터리가 있는지 본다.
