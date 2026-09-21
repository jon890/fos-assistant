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

#### 그 칸이 어디서 오는가

`gateway/platforms/api_server.py` 의 `_resolve_model_name` 이 정하고,
그 결과를 platform 을 만들 때 한 번 담는다. 요청마다 다시 정하지 않는다.

우선순위가 셋이다.

1. 설정의 `model_name` 또는 환경의 `API_SERVER_MODEL_NAME`
2. 그때 활성인 profile 의 이름
3. 어느 것도 없으면 `hermes-agent`

| 요청 | 응답의 `model` |
| --- | --- |
| `model` 을 준 요청 | 준 문자열을 그대로 돌려준다 |
| `model` 을 주지 않은 요청 | listener 를 만들 때 정해진 이름 하나 |

`/v1/capabilities` 가 알리는 것도 같은 값이다. **두 칸은 같은 출처다.**

**한 listener 가 접두로 여러 profile 을 서비스해도 이 값은 접두를 따라가지 않는다.**
platform 을 만들 때 listener 주인의 범위에서 한 번 정해지기 때문이다.
주인에게 `API_SERVER_MODEL_NAME` 이 없으면 셋째 단계인 `hermes-agent` 가 나온다.

실제 모델을 알아야 하면 `/api/model/options` 가 그 profile 의 것을 답한다.

### 승인 방식 `smart` 는 추론 모델에서 `manual` 과 같아진다

`approvals.mode` 가 `smart` 이면 위험한 모양으로 분류된 명령마다
보조 LLM 에 한 번 물어 `APPROVE`, `DENY`, `ESCALATE` 가운데 하나를 받는다.
`tools/approval.py` 의 `_smart_approve` 가 그 호출을 갖는다.

**그 호출이 `max_tokens=16` 으로 걸려 있다.**
받은 내용을 대문자로 바꿔 세 낱말과 정확히 비교하고, 어느 것과도 맞지 않으면 `escalate` 로 읽는다.

추론 모델은 답을 내기 전에 생각을 먼저 내보낸다.
그 문장이 16 토큰을 넘으면 거기서 잘리고, 잘린 문장은 세 낱말 어느 것과도 맞지 않는다.
**그래서 모든 판정이 `escalate` 가 된다.**

`nvidia/nemotron-3-super-120b-a12b` 로 실측했다. 받은 내용이 이것이다.

```text
We need to decide: The user gave a command: python3 -c print
```

위험한 모양으로 분류된 명령 여덟 가지를 넣어 모두 `escalate` 를 받았다.
`python3 -c "print(1+1)"` 처럼 무해한 것도, `curl ... | sh` 처럼 실제로 위험한 것도 같았다.
요청이 모델을 OpenAI codex 계열로 덮어쓰면 같은 명령이 `approve` 로 나온다.

#### 알아채기 어려운 이유

오류가 아니다. 예외도 로그의 실패 표시도 나지 않는다.
`escalate` 는 「사람에게 물어라」라는 정상 판정이고, `smart` 는 그때 `manual` 과 같은 길로 간다.
**설정에는 `smart` 라고 적혀 있으므로 설정만 읽어서는 알 수 없다.**

보조 LLM 호출 자체가 실패할 때도 같은 모양이 된다.
`_smart_approve` 의 예외 처리가 `escalate` 를 돌려주기 때문이다.
그쪽은 경고 한 줄을 남기지만, 토큰이 잘리는 쪽은 그 줄도 남기지 않는다.

#### 부르는 쪽이 보는 것

사람이 승인할 자리가 없는 경로에서는 실행이 `waiting_for_approval` 로 멈춘다.
`approvals.timeout` 이 지나면 그 명령이 거절되고, 에이전트는 다른 길을 찾아 실행을 마친다.

**그래서 최종 상태가 `failed` 가 아니라 `completed` 다.**
호출한 쪽은 성공으로 받지만, 실제로는 에이전트가 하려던 것을 하지 못하고 우회한 결과다.
실행 시간이 `approvals.timeout` 만큼 길어지는 것이 유일하게 겉으로 드러나는 신호다.

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

## 내장 delegation 이 실제로 하는 것

2026년 9월 18일에 v0.21.0 배포본에서 측정했다.
운영 profile 을 건드리지 않으려고 컨테이너 안에 격리된 `HERMES_HOME` 을 따로 만들고
그 아래 측정용 profile 로만 실행했다. 측정이 끝난 뒤 만든 것을 모두 지웠다.

`delegate_task` 가 그 도구다. 부모 실행이 이 도구를 부르면 Hermes 가 자식 agent 를 만든다.

### 부모의 `instructions` 는 자식에게 가지 않는다

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

privacy 로는 이쪽이 안전하다. 부모에 넣은 개인 Memory 가 자식으로 새지 않는다.
대신 자식에게 무언가를 알려야 하면 goal 본문에 직접 적어야 하고,
그 본문은 Control Plane 이 무엇을 담을지 정해야 한다.

### 자식은 한 단계 더 자식을 만든다

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

### 자식 토큰을 SSE 로 받을 수 있다

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
그 응답의 `parent_session_id` 가 부모 실행을 가리킨다.

```json
{"id": "20260918_103840_ac00e1", "source": "subagent",
 "input_tokens": 9193, "output_tokens": 79, "cache_read_tokens": 960,
 "reasoning_tokens": 75, "estimated_cost_usd": 0.0054140625,
 "parent_session_id": "run_e4f5549bd5b04545985670d3457d830c"}
```

Runs API 의 `usage` 에는 cache 칸이 없지만 이 경로에는 있다.

### 다만 동기 위임일 때만 받는다

`delegate_task` 의 `background` 가 참이면 자식이 백그라운드로 돌고
**부모 실행이 자식보다 먼저 끝난다.** 그 시점에 SSE 가 닫혀 `subagent.complete` 를 놓친다.
실측으로 `run.completed` 뒤에 스트림이 닫혔고 자식 사건은 오지 않았다.

`background=false` 로 시킨 실행에서는 `subagent.start` 와 `subagent.complete` 가 모두 왔다.

**실행의 자식 목록을 주는 API 는 없다.**
`GET /api/sessions` 는 `parent_session_id` 질의 인자를 무시하고
`source` 가 `subagent` 인 session 을 목록에서 제외한다.
`/v1/runs/{id}/subagents` 같은 경로도 없다. 404 다.

그러므로 자식 사용량을 남기려면 **SSE 를 끝까지 받아야 하고 위임이 동기여야 한다.**
둘 중 하나가 빠지면 그만큼이 기록에서 사라진다.

### 자식의 모델은 부모의 것이 아니다

부모를 `openai/gpt-oss-20b` 로 돌린 실행에서 자식이 `z-ai/glm-5.2` 로 돌았다.
자식 모델은 `delegation.provider` 와 `delegation.model` 이 정하고,
비어 있으면 부모가 아니라 별도 기본값으로 떨어진다.
실행별 비용을 보려면 이 값을 명시해야 한다.

## Control Plane 의 MCP 도구로 다른 실행을 부를 때

`memory_read` 를 두고 있는 그 자리에 실행을 시작하는 도구를 하나 더 두는 구조를
같은 날 측정했다. 측정용 MCP 서버를 하나 띄워 Hermes 가 그것을 부르게 하고,
그 서버가 다시 Runs API 를 부르게 했다.

### 도구 호출에는 실행을 가리키는 값이 없다

`tools/call` 요청이 들고 온 것은 우리가 설정에 적은 `Authorization` 헤더와
MCP 규약 헤더뿐이었다. `params._meta` 는 빈 객체였다.

```json
{"name": "echo_context", "arguments": {"note": "hello"}, "_meta": {}}
```

**도구가 아는 것은 어느 profile 이 불렀는지까지다.** 어느 실행에서 왔는지는 오지 않는다.
그러므로 이 경로로 만든 실행을 부모 `agent_execution` 에 잇는 값은 우리가 만들어야 한다.

### 제한 시간은 우리가 정하지만 상한은 있다

| 항목 | 값 |
| --- | --- |
| 호출 하나의 제한 시간 | 기본 300초 |
| 서버마다 바꾸는 자리 | 그 서버 설정의 `timeout` |
| 전역으로 바꾸는 자리 | `timeouts.mcp.tool_call` |
| 연결 제한 시간 | 기본 60초 |

제한 시간을 20초로 줄이고 30초 걸리는 도구를 부르자 아래가 도구 결과로 돌아왔고
실행 자체는 정상 종료했다.

```text
MCP call failed: TimeoutError: MCP call timed out after 20.0s (configured timeout: 20.0s)
```

**실행을 기다리는 도구는 이 시간 안에 끝나야 한다.**
오래 걸리는 작업을 그 안에서 기다리면 제한 시간에 걸린다.
아래 「기다리는 도구는 실제로 끊긴다」 가 그 실측이다.

### 재귀를 막는 자리가 Hermes 에 없다

도구 안에서 다시 Runs API 를 부르게 하고 깊이를 하나씩 올렸다.
깊이 2, 3, 4 가 모두 실행됐고 5가 시작되려 할 때 **측정용 서버가 스스로 끊었다.**
Hermes 는 한 번도 개입하지 않았다.

Hermes 는 그 도구가 자기를 다시 부른다는 것을 알지 못한다.
도구 호출은 그저 외부 HTTP 호출이다.
**멈추는 자리를 Control Plane 이 가져야 한다.** 실행 나무의 깊이를 우리가 세고 우리가 거절한다.

### 기다리는 도구는 실제로 끊긴다

위 재귀 측정에서 제한 시간을 120초로 두었는데, 뿌리 실행의 도구 호출이 그 시간에 걸렸다.
아래가 뿌리 실행의 대화에 남은 도구 결과다.

```text
{"error": "MCP call failed: TimeoutError: MCP call timed out after 120.0s ..."}
```

**끊긴 뒤에도 그 아래 실행들은 계속 돌았다.** 깊이 2와 3과 4가 모두 완료로 끝났다.
도구 호출이 끊기는 것과 그 도구가 시작한 실행이 멈추는 것은 별개다.

뿌리 실행은 그 뒤 `Service temporarily overloaded` 로 실패했다.
겹쳐 도는 실행이 쌓여 한 gateway 와 provider 에 몰린 결과다.
`gateway.api_server.max_concurrent_runs` 의 기본값이 10 이고 넘으면 429 를 준다.

그래서 이 구조를 쓴다면 도구가 실행이 끝날 때까지 기다리게 만들지 않는다.
실행 번호를 바로 돌려주고 진행은 따로 묻는 형태여야 한다.

### 취소가 아래로 내려가지 않는다

`POST /v1/runs/{run_id}/stop` 은 동작한다.
`{"status": "stopping"}` 을 주고 잠시 뒤 조회하면 `cancelled` 다.

**그러나 그 실행의 도구가 시작한 실행은 멈추지 않는다.**
뿌리 실행을 취소한 뒤에도 그 도구가 만든 아래 실행이 완료로 끝나고
다시 그 아래를 시작하는 것을 실측했다.
취소를 아래로 전파하는 것도 Control Plane 의 몫이다.

### 긴 결과는 잘리지 않고 파일로 빠진다

20만 자를 돌려주는 도구를 불렀다.
전체가 spillover 파일로 저장되고 대화에는 2천 자 남짓만 들어갔다.

```text
This tool result was too large (200,014 characters, 195.3 KB).
Full output saved to: <spillover 파일>
Use the read_file tool with offset and limit to access specific ...
```

| 구간 | 무슨 일이 일어나나 |
| --- | --- |
| 약 5만 자 이하 | 그대로 대화에 들어간다 |
| 약 5만 자부터 200만 자까지 | 전체를 파일로 저장하고 대화에는 미리보기만 넣는다 |
| 200만 자 초과 | 앞 40%와 뒤 60%만 남기고 잘라 낸다 |

**미리보기만 본 모델이 본문을 읽으려면 `read_file` 을 쓸 수 있어야 한다.**
파일 도구를 잠근 에이전트는 그 본문에 닿지 못한다.

MCP 결과는 `<untrusted_tool_result>` 로 감싸여 들어간다.
그 안의 내용을 지시가 아니라 데이터로 다루라는 안내가 함께 붙는다.

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

**다만 Runs API 에는 kanban 이 없다.** `/v1/capabilities` 의 엔드포인트 목록에 없다.
HTTP 로 부를 길이 아주 없는 것은 아니고, 어디에 열려 있고 무엇을 막지 못하는지는
아래 「kanban 을 HTTP 로 부르는 길」 이 갖는다.

## kanban 을 HTTP 로 부르는 길

같은 날 같은 격리 환경에서 측정했다.

### 대시보드 웹서버에는 kanban API 가 있다

배포본에 kanban 대시보드 plugin 이 들어 있고 그 plugin 이 HTTP 경로 47개를 연다.
경로는 `/api/plugins/kanban/...` 이고 보드 조회, 작업 생성과 수정, 링크,
dispatch, 실행 조회, 담당자 목록, 분해 요청이 모두 들어 있다.

측정용 대시보드를 하나 띄워 실제로 불렀다.

| 요청 | 응답 |
| --- | --- |
| 토큰 없이 `/api/plugins/kanban/board` | 401 |
| 토큰과 함께 같은 경로 | 200 과 보드 전체 |
| 토큰과 함께 `POST /api/plugins/kanban/tasks` | 201 상당. 작업이 만들어졌다 |

**이 인증은 사용자를 구분하지 못한다.**
프로세스마다 하나씩 만들어지는 세션 토큰이고 그 토큰 하나가 보드 전체를 연다.
profile 별 구분이 없고 `API_SERVER_KEY` 와도 다른 값이다.

`PUT /api/plugins/kanban/orchestration` 은 Hermes 루트의 `config.yaml` 을 고친다.
보드를 읽게 열어 준 토큰이 설정 파일까지 연다.

### API server 에는 없지만 plugin 이 더할 수 있다

API server 에 kanban 경로는 없다. 넷 다 404 였다.

plugin 은 `ctx.register_platform_handler("api_server", factory)` 로 경로를 더할 수 있다.
그 factory 가 API server 의 aiohttp 애플리케이션을 그대로 받는다.
측정용 plugin 을 하나 만들어 보드를 돌려주는 경로를 붙였고 실제로 동작했다.

**다만 그 경로는 기본적으로 인증을 거치지 않는다.**
API server 의 인증은 미들웨어가 아니라 핸들러마다 `_check_auth` 를 부르는 방식이다.
그래서 plugin 이 부르지 않으면 열려 있다.

| plugin 경로 | 키 없이 | 틀린 키 | 맞는 키 |
| --- | --- | --- | --- |
| `_check_auth` 를 부르지 않음 | 200 | 200 | 200 |
| `_check_auth` 를 부름 | 401 | 401 | 200 |

`_check_auth` 는 공개된 계약이 아니라 adapter 의 내부 메서드다.
버전이 오르면 사라질 수 있는 자리에 인증을 얹는 셈이다.

**`/p/<profile>/` 접두도 붙지 않는다.** 그 경로로 부르면 404 다.
Hermes 는 자기가 등록한 경로에만 접두 사본을 만든다.
공유 gateway 로 옮기는 길과 맞지 않는다.

### 실행을 이을 수 있지만 정상 종료일 때만 된다

worker 는 Runs API 실행이 아니라 `hermes -p <profile> chat -q` 하위 프로세스다.
그래서 우리가 아는 실행 번호가 처음부터 없다.

이을 수 있는 값은 `task_runs.metadata` 의 `worker_session_id` 하나다.

```json
{"id": 5, "profile": "kbw", "status": "done", "outcome": "completed",
 "metadata": {"worker_session_id": "20260918_101428_9e11e7"}}
```

그 번호로 session 을 조회하면 사용량이 나온다.

```json
{"id": "20260918_101428_9e11e7", "source": "kanban",
 "input_tokens": 49548, "output_tokens": 212,
 "estimated_cost_usd": 0.0015140000000000002, "api_call_count": 3}
```

**그런데 이 값은 worker 가 스스로 적는다.**
`kanban_complete` 와 `kanban_block` 만 적고, 그것도 자기 작업일 때만 적는다.
worker 가 죽거나 시간 초과로 끊기면 `metadata` 가 비어 있다.
실측한 실패 실행 둘이 모두 그랬다.

`tasks.session_id` 는 작업을 **만든** 쪽의 session 이다. worker 의 것이 아니다.
`agent_execution` 을 가리키는 칸은 어디에도 없다.

### 사용량 칸은 지금도 없다

`task_runs` 에 토큰과 비용 칸이 없다.
공개 저장소의 최신 릴리스 `v2026.9.14` 를 받아 같은 스키마를 확인했다. 그대로였다.
그 릴리스의 API server 에도 kanban 경로가 없다.
배포본은 `v2026.8.31` 이다.

### `tenant` 는 아무것도 막지 않는다

`tenant` 는 자유 문자열 칸이고 조회할 때 쓰는 선택 인자다.
강제되는 자리가 없다. 작업을 만드는 쪽이 값을 정하고,
worker 에게는 환경 변수로 전달돼 안내문에 적힐 뿐이다.

토큰 하나로 아래를 그대로 했다.

| 한 일 | 결과 |
| --- | --- |
| 다른 profile 을 담당자로 지정 | 만들어졌다 |
| `tenant` 에 임의의 값을 지정 | 그대로 저장됐다 |
| 인자 없이 보드 조회 | 모든 tenant 의 작업이 함께 나왔다 |

보드는 Hermes 루트 하나를 profile 들이 함께 쓴다.
**Task 본문에 넣지 않고 그 사용자의 Memory 를 주입하는 길은 없다.**
worker 가 받는 것은 작업 본문과 몇 가지 환경 변수뿐이고,
`instructions` 에 해당하는 입구가 kanban 경로에는 없다.

## profile 을 HTTP 로 만드는 길

대시보드 웹서버에 profile 관리 API 가 있다.
v0.21.0 의 `hermes_cli/web_routers/profiles.py` 를 읽고 실제로 불러 확인했다.

| 메서드와 경로 | 하는 일 |
| --- | --- |
| `GET /api/profiles` | profile 목록과 각각의 모델, 스킬 수, gateway 상태 |
| `POST /api/profiles` | profile 을 만든다 |
| `PATCH /api/profiles/{name}` | 이름을 바꾼다 |
| `DELETE /api/profiles/{name}` | profile 과 wrapper 와 gateway 서비스를 지운다 |
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

**CLI 의 `--no-alias` 에 해당하는 본문 필드가 없다.**
API 로 만들면 wrapper 가 함께 생긴다.
`DELETE` 로 지우면 wrapper 와 s6 서비스가 함께 사라지는 것까지 확인했다.

`clone_from` 을 주면 원본의 `config.yaml` 과 `.env` 와 `SOUL.md` 와 `skills/` 와
`memories/MEMORY.md` 와 `memories/USER.md` 를 복사한다.
`clone_all` 을 주면 원본 전체를 복사하고 runtime 파일과 단일 사용 OAuth 파일만 뺀다.

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

`.env` 에 넣는 것은 `API_SERVER_MODEL_NAME` 과 `API_SERVER_KEY` 둘뿐이다.

`API_SERVER_ENABLED` 와 `API_SERVER_HOST` 와 `API_SERVER_PORT` 를 함께 적으면
gateway 가 뜰 때 `SecondaryPortBindingConfigError` 로 그 profile 을 건너뛴다.
공유 listener 하나가 모든 profile 을 받는 구성에서 그 셋은 두 번째 listener 를 세우라는 뜻이 되기 때문이다.

건너뛴 것은 기동 로그에 남고, 그 profile 은 접두를 붙여 불러도 답하지 않는다.
profile 을 만드는 쪽이 그 셋을 넣지 않는 것을 테스트로 고정한다.

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
