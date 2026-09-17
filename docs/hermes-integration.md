# Hermes 연동 지점

NousResearch 의 Hermes Agent 를 Agent Runtime 으로 쓴다.
이 문서는 core 를 수정하지 않고 쓸 수 있는 확장 지점을 정리한다.

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
- `configure-member-profile.sh verify` 가 격리 여부를 판정하고, 공유는 명시할 때만 넘어간다.
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

## Runs API

우리가 쓰는 것은 Runs API 다.

| 메서드와 경로 | 쓰임 |
| --- | --- |
| `POST /v1/runs` | 실행 제출. `input`, `session_id`, `instructions` 를 받고 `run_id` 와 `status` 를 준다 |
| `GET /v1/runs/{run_id}` | `status`, `session_id`, `model`, `output`, `usage` 를 준다 |
| `GET /v1/runs/{run_id}/events` | SSE. `tool.started`, `tool.completed`, `subagent.start`, `subagent.complete` 와 종료 사건 |
| `POST /v1/runs/{run_id}/stop` | 실행 중단 |

MVP 는 제출과 조회만 쓴다.
실행 Graph 는 SSE 를 그대로 받아 그리면 되고, 이를 위해 Hermes 를 고칠 일은 없다.

`instructions` 는 에이전트의 기본 프롬프트를 지우지 않고 그 위에 얹힌다.
Control Plane 이 Memory 를 주입하는 자리가 여기다.

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
cached token 과 호출 단위 모델 구분이 필요해지면 그때 plugin 을 만든다.
MVP 는 plugin 없이 설정만으로 성립한다.

이 기계에는 이미 Orca 가 설치한 `orca-status` plugin 이 있다.
그 plugin 이 hook 사건을 HTTP 로 내보내는 구조라서 우리 plugin 을 만들 때 본보기로 쓸 수 있다.

## 스킬을 profile 에 붙이는 방법

**`skill_paths` 와 `external_skill_paths` 는 설정 키가 아니다.**
v0.21.0 소스에 그런 키가 없다. 빌드 스크립트의 지역 변수로만 나온다.

실제로 되는 방법은 둘이다.

| 방법 | 내용 |
| --- | --- |
| `hermes skills trust <경로>` | 그 저장소의 `./.hermes/skills` 와 `./.agents/skills` 를 읽는다 |
| profile 의 `skills/` 에 심볼릭 링크 | 외부 디렉터리를 직접 가리킨다 |

에이전트 정의 저장소의 스킬은 `.claude/skills` 에 있어 `trust` 가 보는 경로가 아니다.
그래서 심볼릭 링크를 쓴다. 구조는 `skills/<범주>/<스킬>` 이다.

```
~/.hermes/profiles/<profile>/skills/career-os/position-recommender
  -> /opt/data/fos-agents/career-os/.claude/skills/position-recommender
```

링크 대상은 **컨테이너 안의 경로**여야 한다. 호스트 경로로 걸면 컨테이너 안에서 끊긴 링크가 된다.
붙인 뒤 gateway 를 다시 띄워야 인식된다. `GET /v1/skills` 로 확인한다.
실측으로 확인했다.

## 다중 에이전트는 kanban 이 이미 갖고 있다

`hermes kanban` 이 profile 을 작업자로 받는 작업 보드다.

> Durable SQLite-backed task board shared across Hermes profiles.
> Tasks are claimed atomically, can depend on other tasks, and are executed by a named profile.

| 필요한 것 | kanban 이 주는 것 |
| --- | --- |
| 작업 의존 그래프 | `kanban link` 로 부모에서 자식으로 |
| 병렬 작업자와 검증자와 종합자 | `kanban swarm` 이 그 형태의 그래프를 만든다 |
| 전문 에이전트 | profile 이 곧 전문 에이전트다 |
| 부모와 자식 실행 기록 | `kanban runs`, `log`, `tail` |

`swarm` 의 인자가 `--worker PROFILE:TITLE` 과 `--verifier PROFILE` 과 `--synthesizer PROFILE` 이다.
즉 등록한 에이전트가 그대로 작업자 후보가 된다.

**다만 HTTP API 가 없다.** `/v1/capabilities` 의 엔드포인트 목록에 kanban 이 없다.
Control Plane 이 쓰려면 CLI 를 부르거나 plugin 으로 도구를 등록해야 한다.
다중 에이전트로 넘어가기 전에 이것부터 정해야 한다.

## 아직 확인하지 못한 것

**subagent 의 토큰이 실행 합계에 포함되는지 모른다.**
Hermes 가 subagent 를 띄웠을 때 그 토큰이 부모 실행의 `usage` 에 더해지는지 확인하지 못했다.
소스에서 집계 지점을 찾지 못했다.

빠진다면 다중 에이전트에서 **비용이 실제보다 작게 보인다.**
지금은 단일 에이전트라 문제가 없다.
확인하는 방법은 subagent 를 쓰는 실행 하나와 쓰지 않는 실행 하나의 토큰을 견주는 것이다.

## 홈서버에서 확인한 것

2026년 9월 17일에 홈서버에서 직접 확인했다.

| 항목 | 결과 |
| --- | --- |
| 버전 | Hermes Agent v0.21.0 (2026.8.31) |
| `run_submission`, `run_status` | true |
| `run_events_sse`, `run_stop` | true |
| 배치 | profile 마다 자기 포트. `brain-api` 가 8644 를 쓴다 |

경로 멀티플렉스가 아니라 포트 분리를 쓰고 있으므로,
Control Plane 은 profile 마다 주소를 따로 갖는다.
그래서 API server 주소를 `hermes.base-url` 이 아니라 에이전트에 둔다.
