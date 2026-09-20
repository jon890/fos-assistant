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

### 모델은 실행마다 정한다

`POST /v1/runs` 는 요청 본문의 `provider` 와 `model` 을 그 실행에만 적용한다.
profile 의 `config.yaml` 이 정한 것은 기본값일 뿐이다.

**둘을 함께 보내야 한다.**
`provider` 만 주고 `model` 을 빼면 Hermes 가 config 의 모델 문자열을 새 provider 에 그대로
넘겨 `No LLM provider configured` 로 끝난다.

provider 해석이 실패하면 이전 provider 가 남고 모델만 요청 값으로 바뀌는 중간 상태가 된다.
그 상태에서 오는 404 는 provider 가 아니라 모델을 찾지 못한 것이다.

### 조회 응답의 `model` 은 실제로 돈 모델이 아니다

**`GET /v1/runs/{run_id}` 의 `model` 은 우리가 보낸 값을 되돌려 줄 뿐이다.**
Hermes 안에서 다른 모델로 넘어가도 이 값은 바뀌지 않는다.

실제로 돈 모델은 `GET /api/sessions/{session_id}` 의 `model` 이 담는다.
fallback 으로 넘어간 뒤의 모델까지 그쪽에 들어 있다.

`POST /api/sessions/{id}/chat` 은 응답에 `runtime` 을 담아 요청한 것과 실제로 돈 것을
한 응답에서 대조할 수 있다. 그 블록은 `/v1/runs` 에는 없다.

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

### profile 접두

`gateway.multiplex_profiles` 를 켜면 listener 하나가 `/p/<profile>/...` 로 모든 profile 을 받는다.

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

### 동시 실행 한도는 listener 단위다

`gateway.api_server.max_concurrent_runs` 가 동시에 도는 실행을 제한한다.
그 값은 listener 주인 profile 의 설정에서 한 번 읽고, listener 가 하나면 한도도 하나다.

**공유 listener 로 옮기면 모든 profile 이 그 한도를 나눠 쓴다.**
넘으면 429 와 `rate_limit_exceeded` 가 온다.

한도를 끄면 실행이 메모리 한도까지 쌓이고, 그때 죽는 것은 프로세스 하나라
모든 profile 이 함께 끊긴다. 유한한 값을 두는 편이 낫다.

`gateway.max_concurrent_sessions` 는 다른 것이다.
그쪽은 platform 대화 turn 을 제한하고 `/v1/runs` 에는 걸리지 않는다.

### 한도 위에 thread pool 이 하나 더 있다

`max_concurrent_runs` 를 통과한 실행은 곧바로 도는 것이 아니라 thread pool 을 한 번 더 지난다.

```python
result, usage = await asyncio.get_running_loop().run_in_executor(None, _run_sync)
```

`None` 은 그 event loop 의 기본 executor 를 쓰라는 뜻이다.
Hermes 는 그 executor 를 만들지도 크기를 정하지도 않으므로 Python 의 기본값이 그대로 쓰인다.
기본값은 `min(32, os.cpu_count() + 4)` 다.

**`os.cpu_count()` 는 컨테이너에 준 CPU 한도가 아니라 호스트의 코어 수를 본다.**
그래서 pool 크기는 컨테이너 설정으로 바꿀 수 없고, 호스트를 옮기면 값이 달라진다.

이 크기는 CPU 를 쓰는 일을 가정한 값이다.
실행이 자리를 잡고 있는 시간의 대부분은 LLM 응답을 기다리는 시간이고 그동안 CPU 를 쓰지 않는다.

### 한도를 pool 보다 크게 두면 429 가 아니라 기다린다

429 판정은 요청을 받는 자리에서 하고, pool 에 넘기는 것은 그보다 뒤다.
그래서 `max_concurrent_runs` 가 pool 크기보다 크면 넘친 실행도 `202` 로 받아들여지고,
pool 앞에서 순서를 기다린다.

pool 16, 한도 40 인 gateway 에 실행 24개를 동시에 보내 측정했다.

| 무엇 | 결과 |
| --- | --- |
| 응답 코드 | 24개 전부 202. 429 는 없었다 |
| 제출 응답 시간 | 최대 0.07초 |
| 실제로 시작한 시각 | 16개는 즉시, 나머지 8개는 30.0초 뒤 |

30.0초는 앞선 16개가 LLM 응답을 받아 자리를 비운 시각이다.

**기다리는 실행도 상태 조회가 `running` 으로 답한다.**
`_run_and_close()` 가 pool 에 넘기기 전에 상태를 `running` 으로 올리기 때문이다.
`queued` 상태는 정의돼 있지만 이 대기 구간에서는 드러나지 않는다.

| 시각 | `GET /v1/runs/{id}` 의 상태 분포 |
| --- | --- |
| 6초 | `running` 24 |
| 30초 | `running` 24 |
| 36초 | `completed` 16, `running` 8 |
| 66초 | `completed` 24 |

**사용자가 겪는 것은 실패가 아니라 느림이다.**
호출한 쪽은 자기 실행이 시작조차 하지 않았다는 것을 알 수 없고,
이벤트 스트림에도 그동안 아무것도 오지 않는다.

### pool 은 실행만 쓰는 것이 아니다

`asyncio.to_thread` 도 같은 기본 executor 를 쓴다.
gateway 코드에서 `api_server.py` 가 36곳, `run.py` 가 46곳에서 이것을 부르고,
세션 저장소를 읽고 쓰는 일이 모두 여기 해당한다.

**실행이 pool 을 채우면 세션 저장소를 읽는 요청도 함께 밀린다.**
pool 16 인 gateway 에 실행 24개를 보내고 그동안 두 경로의 응답 시간을 측정했다.

| 경로 | 한가할 때 | 실행 24개가 pool 을 채운 동안 |
| --- | --- | --- |
| `/v1/capabilities` | 1.1 ms | 1.1 ms |
| `/api/sessions` | 11 ms 부터 16 ms | **30,521 ms** |

`/v1/capabilities` 는 pool 을 지나지 않아 영향이 없고,
`/api/sessions` 는 실행 하나가 자리를 비울 때까지 그대로 기다렸다.
30.5초는 그 gateway 의 LLM 응답 시간과 같다.
**운영에서 실행 하나는 이보다 훨씬 오래 자리를 잡는다.**

같은 부하를 pool 64 인 gateway 에 보내면 `/api/sessions` 가 12 ms 부터 15 ms 를 유지했다.

### pool 은 plugin 으로 키울 수 있다

hook 목록에 gateway 가 뜰 때 도는 것은 없다.
대신 plugin 모듈이 gateway 프로세스 안에서 import 되는 것을 쓴다.
`BaseEventLoop.run_in_executor` 를 감싸, 기본 executor 가 처음 필요해지는 순간에
원하는 크기의 것을 먼저 꽂아 넣으면 된다.

```python
original = asyncio.base_events.BaseEventLoop.run_in_executor

def run_in_executor(self, executor, func, *args):
    if executor is None and not installed:
        self.set_default_executor(
            concurrent.futures.ThreadPoolExecutor(max_workers=target))
    return original(self, executor, func, *args)
```

**Hermes core 를 고치지 않으므로 ADR-001 의 제약 안이다.**
plugin 은 `plugins.enabled` 에 이름이 있어야 로드된다. 그 키가 없으면 어떤 사용자 plugin 도 켜지지 않는다.

pool 을 키운 gateway 에서 측정한 값이다. CPU 를 2.0 으로 제한한 컨테이너에서 측정했다.

| pool | 동시에 보낸 실행 | 결과 |
| --- | --- | --- |
| 64 | 32 | 전부 1.1초 안에 시작, 38초에 완료 |
| 64 | 64 | 전부 즉시 시작, 38초에 완료 |
| 160 | 120 | 전부 5초 안에 시작, 42초에 완료 |

**막힌 곳이 pool 뿐이었다는 뜻이다.**

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

### 스레드를 늘리는 비용

스레드 자체는 거의 들지 않는다. 늘어나는 것은 동시에 도는 실행이 쥔 것이다.

| 무엇 | 측정값 |
| --- | --- |
| idle 스레드 하나의 상주 메모리 | 22 kB 부터 32 kB |
| 동시 실행 하나 | 약 3.1 MB |
| 실행 하나가 쓰는 스레드 수 | 약 3개 |
| profile 하나가 처음 실행할 때의 일회성 증가 | 약 50 MB |

pool 을 키우는 것만으로는 메모리가 늘지 않는다. 스레드를 필요할 때 만들기 때문이다.
드는 것은 동시에 도는 실행 수가 정한다.

**3.1 MB 는 하한이다.** 스킬만 올리고 MCP 서버와 대화 기록이 없는 profile 에서 측정한 값이다.

## Memory MCP

제목만 `instructions` 에 실린 Memory 본문은 Control Plane 의 MCP 서버에서 읽는다.

| 항목 | 계약 |
| --- | --- |
| 서버 이름 | `fos-assistant-memory` |
| 경로 | `/mcp` |
| 프로토콜 | Streamable HTTP `2025-03-26` |
| 인증 | profile마다 다른 Bearer 토큰 |
| 도구 | `memory_read` |

토큰이 요청자를 정한다. 요청 본문에 사용자 번호를 넣어도 사용자를 바꿀 수 없다.
Control Plane 은 그 사용자가 볼 수 있고 승인됐으며 항상 주입하지 않는 항목만 응답한다.
실제 MCP 서버 등록과 토큰 전달은 비공개 저장소 `fos-home-infra`가 맡는다.

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

**다만 스킬이 입력 비용의 대부분은 아니다.**
같은 문장을 `career` profile 에 보내 스킬 97개일 때와 6개일 때를 측정했다.

| 스킬 수 | 입력 토큰 |
| --- | --- |
| 97 | 14,949 |
| 6 | 12,388 |

줄어든 것이 2,561 토큰으로 17% 였다.

### 도구 정의가 더 크다

같은 방법으로 도구 범위를 바꿔 가며 측정한 것이다.
바이트는 `hermes prompt-size --platform api_server --json` 이 보고한 값이고,
이 명령은 API 를 부르지 않고 계산한다.

| 구성 | 도구 수 | 도구 정의 바이트 | 입력 토큰 |
| --- | --- | --- | --- |
| 전부 열림 | 18 | 31,147 | 12,388 |
| terminal·file·skills·memory·web | 14 | 20,364 | 10,180 |
| terminal·file·skills·memory | 12 | 18,454 | 9,792 |
| 전부 잠금 | 0 | 2 | 4,278 |

도구를 전부 잠그면 65.5% 가 줄어든다.
이 값은 도구 정의 JSON 만이 아니라 system prompt 의 도구 안내까지 함께 줄어든 결과다.
system prompt 가 22,799 글자에서 13,123 글자로 줄었다.

`career` 에서 실제로 뺄 수 있는 것은 2,596 토큰, 21% 다.
`career-os` 스킬이 `bun` 을 부르므로 `terminal` 이, 파일을 읽으므로 `file` 이 필요하다.
`sync-profile` 이 쓰는 브라우저는 Hermes 의 browser toolset 이 아니라
외부 스크립트이고 `terminal` 로 돌리므로 그 toolset 은 빼도 된다.

비용을 줄이려면 스킬만 보지 말고 도구 설정을 함께 본다.

### 실행 한 번의 비용은 이보다 훨씬 크다

위 숫자는 짧은 대화 한 번의 고정 비용이다.
실제 작업을 시키면 그 고정 비용이 턴마다 다시 실린다.

`career` 에 포지션 추천을 한 번 시킨 실측이다.

| 항목 | 값 |
| --- | --- |
| 입력 | 3,460,816 토큰 |
| 출력 | 15,301 토큰 |
| `gpt-5.6-sol` 공개 가격 환산 | 14.15달러 |

짧은 대화 한 번의 279배다. 그 실행은 활성 공고 122건을 비교했다.
`career` 의 `max_turns` 는 60 이다.

API server 의 `usage` 에는 cache 항목이 없다.
`prompt_tokens` 와 `completion_tokens` 와 `total_tokens` 뿐이다.
`cached_input_tokens` 가 실행 기록에서 비어 있는 것은
prompt cache 가 붙지 않아서가 아니라 이 API 가 보고하지 않기 때문이다.

에이전트 정의 저장소의 스킬은 `.claude/skills` 에 있어 `trust` 가 보는 경로가 아니다.
그래서 심볼릭 링크를 쓴다. 구조는 `skills/<범주>/<스킬>` 이다.

```
~/.hermes/profiles/<profile>/skills/<범주>/<스킬>
  -> <컨테이너에 마운트된 에이전트 정의 저장소>/<범주>/.claude/skills/<스킬>
```

마운트 경로는 `fos-home-infra` 가 정한다.

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

## 홈서버에서 확인한 것

2026년 9월 17일에 홈서버에서 직접 확인했다.

| 항목 | 결과 |
| --- | --- |
| 버전 | Hermes Agent v0.21.0 (2026.8.31) |
| `run_submission`, `run_status` | true |
| `run_events_sse`, `run_stop` | true |
| 배치 | 경로 멀티플렉스가 아니라 profile 마다 자기 포트를 쓴다 |
| subagent 토큰 | 부모 실행의 `usage` 에 포함되지 않는다 |

**subagent 토큰이 부모에 포함되지 않으므로 실행 줄을 전부 더해야 실제 사용량이 나온다.**
subagent 를 쓴 실행과 쓰지 않은 실행의 토큰을 견줘 확인했고,
근거는 [ADR-016](adr/ADR-016-다중-에이전트-조율은-control-plane이-맡는다.md) 의 「자식 토큰 실측」 절에 있다.
부모 usage 만 저장하면 그만큼이 기록에서 빠진다.

경로 멀티플렉스가 아니라 포트 분리를 쓰고 있으므로,
Control Plane 은 profile 마다 주소를 따로 갖는다.
그래서 API server 주소를 `hermes.base-url` 이 아니라 에이전트에 둔다.

## gateway 는 s6 가 감독한다

이 컨테이너는 profile 마다 gateway 를 하나씩 돌리고 그것을 s6 가 감독한다.
`systemd` 와 같은 자리이고 컨테이너용으로 훨씬 작다.

`hermes gateway run` 은 s6 에 넘기고 스스로 끝난다.
그래서 이 명령의 종료를 gateway 가 죽은 것으로 읽으면 안 된다.

| 명령 | 하는 일 |
| --- | --- |
| `s6-svstat /run/service/gateway-<profile>` | 돌고 있는지와 언제부터인지 |
| `s6-svc -r /run/service/gateway-<profile>` | 다시 띄운다 |
| `s6-svc -u /run/service/gateway-<profile>` | 띄운다 |

이 명령들은 `PATH` 에 없다. `/command` 를 앞에 붙여야 한다.

`hermes gateway start` 로 띄운 프로세스는 s6 밖에서 돈다.
포트는 응답하지만 컨테이너를 다시 띄우면 사라지고 s6 가 되살리지 않는다.
실측으로 `career` 를 그렇게 띄웠다가 감독 아래로 옮겼다.

컨테이너가 다시 뜰 때 그 profile 의 gateway 가 함께 뜨는지는
`~/.hermes/profiles/<profile>/gateway_state.json` 의 `desired_state` 가 정한다.

한 번 켜 두면 그 파일이 `running` 으로 남아 다음 기동에서 자동으로 뜬다.
일부러 멈춘 gateway 는 멈춘 채로 남는다. 켜고 끈 것을 컨테이너 기동이 뒤집지 않는다.

`career` 를 붙인 뒤 그 파일에 `desired_state` 가 `running` 이고
`api_server` 가 `connected` 인 것을 확인했다.

## 실행 이벤트가 실제로 오는 형태

v0.21.0 의 `gateway/platforms/api_server_runs.py` 가 보내는 것을 실측으로 확인했다.

**사건 이름은 `type` 이 아니라 `event` 다.** 중계하는 쪽이 `type` 을 읽으면 아무것도 받지 못한다.

| `event` | 함께 오는 칸 |
| --- | --- |
| `message.delta` | `delta` 에 답의 조각 |
| `tool.started` | `tool` 에 도구 이름, `preview` 에 인자 앞부분 |
| `tool.completed` | `tool`, `duration` 초, `error` 참거짓 |
| `subagent.start`, `subagent.complete` | `preview` |
| `reasoning.available` | `text` 에 그때까지의 답 전체 |
| `run.completed` | `output` 과 `usage` |
| `run.failed`, `run.cancelled` | 끝 |

`data:` 줄의 JSON 안에 `event` 가 들어 있다. SSE 의 `event:` 줄로 오지 않는다.
10초마다 `: keepalive` 주석이 온다.

**가짜 Hermes 를 이 형태로 맞춰 둔다.**
어긋나면 테스트는 통과하는데 운영에서 조각이 흐르지 않는다. 실측으로 그렇게 한 번 놓쳤다.
