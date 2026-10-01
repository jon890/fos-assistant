# Hermes 연동 지점

| 문서 | 내용 |
| --- | --- |
| [Runs API](runs-api.md) | 실행 요청, 조회 응답과 사건의 모양 |
| [동시 실행](concurrency.md) | profile 접두와 실행 한도, thread pool |
| [도구와 스킬](tools-and-skills.md) | 도구 설정, Control Plane MCP(`fos-assistant`) 와 스킬 연결 |
| [profile 생성](profiles.md) | profile 관리 API 와 생성 계약 |
| [위임](delegation.md) | 내장 delegation 과 Control Plane 도구 위임 |
| [도구 hook 과 승인](connector-policy.md) | `pre_tool_call` 로 MCP 도구를 막을 때의 계약, 등록 이름, 내장 승인 |
| [kanban](kanban.md) | 다중 에이전트 kanban 과 HTTP 호출 |
| [버전 변경과 실측](upgrades.md) | 버전별 계약 차이와 확인 결과 |


NousResearch 의 Hermes Agent 를 Agent Runtime 으로 쓴다.
이 문서는 core 를 수정하지 않고 쓸 수 있는 확장 지점을 정리한다.
그 확장 지점에 설치하는 plugin 과 profile 틀은 이 저장소의 [`hermes/`](../../hermes/) 에 있다. 배치와 설치 묶음은 [`code-architecture.md`](../code-architecture.md) 의 「Hermes 쪽 코드」 절이 갖는다.
운영값과 실행 절차는 비공개 저장소 `fos-home-infra` 에 둔다.
버전이 붙은 설명은 그 버전에서 확인한 계약이다.
2026-09-28 조사로 확인한 버전 차이는 [「버전을 올릴 때 달라지는 계약」](upgrades.md#버전을-올릴-때-달라지는-계약) 에 모았다.

## profile 이 곧 사용자 격리 단위다

Hermes 는 profile 마다 아래를 따로 가진다.

- `config.yaml` 과 `.env`
- `SOUL.md` 로 정하는 에이전트 성격
- `memories/`, `skills/`, session 과 메시지 기록

`config.yaml` 안의 `${VAR}` 는 그 profile 의 `.env` 에서만 값을 찾는다.
셸 환경 변수도, 다른 profile 의 `.env` 도 보지 않는다.

### OAuth credential 은 여기서 빠진다

`.env` 와 달리 OAuth 는 profile 로 갈리지 않는다.
profile 에 `auth.json` 이 없으면 루트의 `~/.hermes/auth.json` 을 읽는다.
v0.21.0 의 `tui_gateway/methods_profiles.py` 주석이 그것을 말한다.

> profile reads fall back to the global store, and token refreshes write THROUGH to it

홈서버의 profile 넷은 모두 자기 `auth.json` 이 없어 한 로그인을 함께 쓰고 있다.
그러므로 profile 을 나누는 것만으로 credential 이 갈렸다고 볼 수 없다.

profile 이 자기 credential 을 가지려면 둘 중 하나가 있어야 한다.

- 자기 `auth.json`. `hermes -p <member> login` 이 만든다
- 자기 `.env` 안의 provider API key. `API_SERVER_KEY` 는 여기 해당하지 않는다

루트 `auth.json` 을 복사하는 방식은 쓰지 않는다.
Hermes 주석에 따르면 복사하면 갱신 토큰이 둘로 갈라지고 한쪽 갱신이 다른 쪽을 무효로 만든다.

지금은 가족이 구독 하나를 함께 쓰기로 정했다. ADR-002 가 그 결정을 담는다.
그래서 격리를 강제하지 않고, 공유하고 있다는 사실을 데이터와 검사로 드러내기만 한다.

### 우리가 더하는 것

- 에이전트마다 `credential_scope` 를 적는다. 기본값이 없어 만드는 사람이 반드시 고른다.
- `configure-default-profile.sh verify` 가 격리 여부를 판정하고, 공유는 명시할 때만 넘어간다.
- profile 마다 `fallback_providers` 를 비워 둔다. 한 사람의 요청이 다른 모델로 넘어가지 않는다.
- Control Plane 은 대화를 시작할 때 사용자가 쓸 수 있는 에이전트에서 profile 이름을 꺼낸다.
  이어지는 요청은 에이전트나 profile 을 바꾸지 못한다.

## API server

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

## plugin hook

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
MVP 는 plugin 없이 설정만으로 성립한다.

이 기계에는 이미 Orca 가 설치한 `orca-status` plugin 이 있다.
그 plugin 이 hook 사건을 HTTP 로 내보내는 구조라서 우리 plugin 을 만들 때 본보기로 쓸 수 있다.

## gateway 는 s6 가 감독한다

아래는 v0.21.0 과 v0.21.3 의 동작이다.
v0.21.4 이후의 host singleton 과 multiplex 정책은 다음 절에서 구분한다.

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
