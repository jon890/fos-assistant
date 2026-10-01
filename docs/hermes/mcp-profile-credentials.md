# MCP 프로세스에 사용자별 환경 값을 전달하는 계약

2026-09-30에 Hermes `v2026.9.14`의 소스를 읽어 판정했다.
운영에서 서로 다른 사용자 토큰으로 실행한 결과는 이 조사에 포함하지 않는다.

## profile별 토큰 전달

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
운영자가 주는 값(`operator_env`)은 profile `.env` 를 거치지 않는다. 대시보드 plugin 이 설치할 때 운영 목록의 값을 서버 정의의 `env` 에 직접 넣는다([커넥터 연결](../connectors.md)).
운영자가 주는 비밀은 이 방식으로 넘기지 못한다. `${이름}` 은 그 profile 의 secret scope 에서만 풀리고, 공유 gateway 는 scope 에 없는 이름을 프로세스 env 에서 찾지 않는다(v0.21.5 의 `agent/secret_scope.py` `get_secret`). 그래서 `operator_secrets` 는 지원하지 않는다([ADR-046](../adr/ADR-046-운영-비밀은-operator-env-와-다른-칸으로-선언하고-자식-mcp-프로세스에만-넣는다.md)).
선택 값을 쓰지 않을 때에는 MCP 설정의 해당 env 항목을 빼거나 값으로 빈 문자열을 직접 쓴다.
profile `.env`의 빈 값을 참조하면 보간 함수가 `${VAR}` 원문을 남긴다.
다른 profile의 값을 기본값으로 쓰지 않는다.

새 profile의 MCP 발견은 profile 문맥을 복사해 별도 실행 스레드로 전달한다.
같은 서버 이름을 여러 profile이 쓰더라도 토큰을 펼치는 문맥이 유지된다.
[새 profile의 MCP 발견](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/gateway/run_profile_reconcile.py)이 근거다.
이 판정은 profile별 설정과 명시적인 환경 변수 참조를 전제로 한다.

## 설정 저장과 gateway 연결 확인

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
재시작이 필요한 동안에는 에이전트를 활성화하지 않는다.

## 환경 항목 제거

Hermes의 `DELETE /api/env`는 본문 `{ "profile": "<profile>", "key": "ACCOUNTBOOK_API_TOKEN" }`으로
해당 profile의 환경 항목을 제거한다.
없는 항목은 404다.
`PUT /api/env`에 빈 값을 넣는 것과 항목 제거를 구분한다.
[환경 API](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/web_routers/config_env.py)와
[요청 모델](https://github.com/NousResearch/hermes-agent/blob/v2026.9.14/hermes_cli/web_models.py)이 근거다.
기계용 인증에서 이 메서드와 커넥터 칸 key를 허용하는 정책은 `fos-home-infra`가 소유한다.
