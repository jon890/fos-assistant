# Runs API

우리가 쓰는 것은 Runs API 다.

| 메서드와 경로 | 쓰임 |
| --- | --- |
| `POST /v1/runs` | 실행 제출. `input`, `session_id`, `instructions` 를 받고 `run_id` 와 `status` 를 준다 |
| `GET /v1/runs/{run_id}` | `status`, `session_id`, `model`, `output`, `usage` 를 준다. 언제까지 답하는지는 아래 「실행 조회가 답하는 기간」 을 본다 |
| `GET /v1/runs/{run_id}/events` | SSE. `tool.started`, `tool.completed`, `subagent.start`, `subagent.complete` 와 종료 사건 |
| `POST /v1/runs/{run_id}/stop` | 실행 중단 |
| `POST /v1/runs/{run_id}/steer` | 도는 실행에 지시를 더한다. 아래 「도는 실행에 지시를 더하는 `steer`」 를 본다. 우리는 아직 부르지 않는다 |

**실행을 시작한 뒤 바꿀 수 있는 것은 중단과 `steer` 둘이다.** Control Plane 은 중단만 쓴다.
끝난 위임 결과는 같은 session 에 새 실행을 제출해 전한다([ADR-040](../adr/ADR-040-위임-결과는-control-plane-이-부모-대화의-다음-turn-을-열어-전한다.md)).
응답 중에 사용자가 보낸 메시지도 그 turn 이 끝난 뒤 새 실행으로 보낸다([ADR-048](../adr/ADR-048-응답-중에-보낸-메시지는-control-plane-이-쌓아-두고-다음-turn-으로-합쳐-보낸다.md)).
실행 Graph 는 SSE 를 그대로 받아 그리면 되고, 이를 위해 Hermes 를 고칠 일은 없다.

`instructions` 는 에이전트의 기본 프롬프트를 지우지 않고 그 위에 얹힌다.
Control Plane 이 Memory 를 주입하는 자리가 여기다.

## 도는 실행에 지시를 더하는 `steer`

**이 문서는 2026-10-01 까지 「도는 실행에 메시지를 끼워 넣는 경로는 없다」 고 적었다. 틀린 기술이었다.**
v0.21.5 의 `gateway/platforms/api_server_runs.py` 에 `_handle_steer_run` 이 있다.
[`delegation.md`](delegation.md) 와 [`upgrades.md`](upgrades.md) 는 이미 `steer` 경로와 `run.steered` 사건을 적고 있었다.

| 항목 | 계약 |
| --- | --- |
| 경로 | `POST /v1/runs/{run_id}/steer` |
| 소유자 판정 | 조회, 중단과 같다([`concurrency.md`](concurrency.md)) |
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

## 실행 조회가 답하는 기간

v0.21.5 의 [`gateway/platforms/api_server_runs.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/gateway/platforms/api_server_runs.py) 와 `api_server.py` 를 2026-10-02 에 소스로 읽었다. 운영 Hermes 에서 왕복으로 확인하지는 않았다.

**실행 상태는 gateway 프로세스의 메모리에 있다.** `_run_statuses` 가 run 번호마다 상태 하나를 갖는다.

| 때 | `GET /v1/runs/{run_id}` |
| --- | --- |
| 도는 중 | 200. `status` 는 `running`, `stopping`, `waiting_for_approval` 가운데 하나다. 종료 상태가 아닌 값은 모두 도는 중으로 읽는다 |
| 끝난 뒤 1시간 안 | 200. 종료 `status` 와 `output`, `usage`, `session_id`, `runtime` 을 그대로 준다. 몇 번을 읽어도 같다 |
| 끝난 뒤 1시간이 지났다 | 404 `run_not_found`. `_sweep_orphaned_runs_once` 가 1분마다 돌며 `_RUN_STATUS_TTL`(3600초)을 넘긴 `completed`, `failed`, `cancelled` 를 지운다 |
| gateway 가 다시 떴다 | 404 `run_not_found`. 메모리가 비었다 |
| 남의 profile 이나 key 로 물었다 | 404. 있는지 알리지 않는다([`concurrency.md`](concurrency.md)) |

**404 로는 「없는 run」 과 「끝난 지 오래된 run」 을 구분하지 못한다.** 어느 쪽이든 다시 물어도 답이 바뀌지 않는다.

**클라이언트가 떠나도 실행은 계속 돈다.** 실행은 gateway 의 task 로 돌고 조회나 사건 스트림 연결에 묶이지 않는다.
사건 스트림의 버퍼만 `_RUN_STREAM_TTL`(300초) 뒤에 치운다. 다시 구독해도 그 전의 조각은 오지 않는다.

gateway 가 내려가며 끊은 실행은 `interrupted` 로 적힌다. 그 상태도 메모리에 있어 다시 뜬 뒤에는 읽지 못한다.

제출할 때 `Idempotency-Key` 머리말을 주면 Hermes 가 그 run 의 상태를 디스크에 남기고, 다시 뜬 뒤에도 조회에 답한다. 도는 중에 gateway 가 죽은 run 은 `interrupted` 로 답한다.
**Control Plane 은 이 머리말을 보내지 않는다.**

Control Plane 이 다시 뜰 때 남은 실행을 이 조회로 다시 정한다([ADR-061](../adr/ADR-061-재기동-때-남은-실행은-hermes-에-물어-정하고-도는-실행에는-다시-붙는다.md)).

**가짜 Hermes 의 실행 조회도 이 모양으로 둔다.** 도는 실행은 `running`, 끝난 실행은 같은 답을 되풀이하고, 지운 run 과 모르는 run 은 404 다.

## `/v1/runs` 는 이미지를 받지 않는다

v0.21.3 에서 확인했다. `content` 에 이미지를 담은 항목을 해석하는 자리가 경로마다 다르다.

| 경로 | 이미지 항목 |
| --- | --- |
| `POST /v1/chat/completions` | 받는다 |
| `POST /v1/responses` | 받는다 |
| `POST /api/sessions/{id}/chat` | 받는다 |
| `POST /v1/runs` | 받지 않는다 |

앞의 셋은 본문을 `{"type": "image_url"}` 모양으로 정규화하는 함수를 지난다.
그 함수는 `http(s)` 주소와 `data:image/...` 두 가지만 받고,
그 밖의 `data:` 와 파일 항목은 400 으로 거절한다.

`/v1/runs` 는 그 함수를 부르지 않는다.
`input` 의 마지막 항목에서 `content` 를 꺼내 그대로 쓴다.
검사하는 자리가 없으므로 이미지 항목을 넣어도 거절되지 않고, 해석된다는 보장도 없다.

**그래서 사진을 대화 본문에 실어 보내는 길이 없다.**
Control Plane 은 `/v1/runs` 를 쓰고, 그것을 버리면 `run_id` 와 사건 스트림과 실행 기록을 함께 버린다.

## 이미지 파일은 `vision_analyze` 로 본다

`read_file` 이 이미지 확장자를 만나면 내용을 돌려주지 않고 `vision_analyze` 를 쓰라는 안내를 낸다.
에이전트가 사진을 보는 길은 대화 본문이 아니라 이 도구다.
그러므로 사진을 에이전트가 닿는 자리에 놓아 두면 모델이 그것을 읽는다.

## 모델은 실행마다 정한다

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

## 조회 응답의 `model` 은 실제로 돈 모델이 아니다

아래는 v0.21.0 과 v0.21.3 의 계약이다.
v0.21.5 에서 추가한 실제 실행 `runtime` 과의 차이는 [「Runs 응답과 사건의 버전 차이」](upgrades.md#runs-응답과-사건의-버전-차이) 에 있다.

**`GET /v1/runs/{run_id}` 의 `model` 은 우리가 보낸 값을 되돌려 줄 뿐이다.**
Hermes 안에서 다른 모델로 넘어가도 이 값은 바뀌지 않는다.

실제로 돈 모델은 `GET /api/sessions/{session_id}` 의 `model` 이 담는다.
fallback 으로 넘어간 뒤의 모델까지 그쪽에 들어 있다.

**v0.21.5 에서 달라진 두 가지**(2026-09-29 운영에서 확인):

- `GET /api/sessions/{session_id}` 는 행을 `{"object": "session", "session": {...}}` 로 감싸고, `model` 은 `session` 안에 있다. provider 칸은 응답에 없다. Hermes 저장소에는 `billing_provider` 로 남는다
- `GET /v1/runs/{run_id}` 는 끝난 실행에 `runtime: {"provider", "model", "route_source"}` 를 싣는다. fallback 으로 넘어간 경우도 실제로 돈 값이다. 근거는 [v0.21.5 `gateway/platforms/api_server_runs.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/gateway/platforms/api_server_runs.py) 의 `_served_runtime` 과 `api_server.py` 의 `_sanitize_runtime_metadata` 다

그래서 Control Plane 은 실행의 `runtime` 에 provider 와 모델이 둘 다 있으면 그 짝을 실행 기록에 적는다.
둘 중 하나라도 없으면 세션 조회, 실행의 `runtime`, 대화가 고른 값 순서로 먼저 있는 것을 칸마다 따로 채운다.
처음에는 세션 응답을 감싸지 않은 모양으로 읽어, 모델을 보내지 않은 실행의 provider, 모델, 금액이 비었다.

`POST /api/sessions/{id}/chat` 은 응답에 `runtime` 을 담아 요청한 것과 실제로 돈 것을
한 응답에서 대조할 수 있다. v0.21.3 까지는 그 블록이 `/v1/runs` 에 없었다. v0.21.5 는 아래처럼 실행 조회에도 싣는다.

### 그 칸이 어디서 오는가

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

**`/v1/capabilities` 와 `/v1/runs` 에서는 한 listener 가 접두로 여러 profile 을
서비스해도 이 값이 접두를 따라가지 않는다.**
platform 을 만들 때 listener 주인의 범위에서 한 번 정해지기 때문이다.
주인에게 `API_SERVER_MODEL_NAME` 이 없으면 셋째 단계인 `hermes-agent` 가 나온다.

### 경로마다 다르다

세 경로가 같은 값을 주지 않는다.

| 경로 | 무엇을 주는가 |
| --- | --- |
| `/v1/capabilities` | platform 을 만들 때 담아 둔 이름 하나 |
| `/v1/runs` 응답 | 요청이 준 문자열. 주지 않았으면 위와 같은 이름 |
| `/v1/models` | 접두가 있으면 그 자리에서 다시 정한다 |

`_handle_models` 만 이름 정하는 함수를 다시 부른다.
앞의 둘은 platform 을 만들 때 담아 둔 값을 그대로 쓴다.

```python
model_name = (
    self._resolve_model_name("")
    if _api_request_profile.get()
    else self._model_name
)
```

**그래서 접두를 따라가는 것은 `/v1/models` 뿐이다.**

**다시 부를 때 넘기는 첫 인자가 비어 있다.**
우선순위 1 인 `API_SERVER_MODEL_NAME` 을 건너뛰고 2 인 활성 profile 이름으로 간다.
그러므로 `/v1/models` 가 주는 것은 그 profile 의 `API_SERVER_MODEL_NAME` 이 아니라
그 profile 의 **이름**이다.
두 값을 같게 적어 둔 배포에서는 구별되지 않으므로 관측만으로는 판정할 수 없다.

**어느 경로도 실제로 쓴 provider 모델을 주지 않는다.**
접두를 따라가는 경로가 주는 것도 profile 이름이지 모델 이름이 아니다.

실제 모델을 알아야 하면 `/api/model/options` 가 그 profile 의 것을 답한다.

## `/api/model/options` 는 provider 와 모델 목록을 함께 준다

2026-09-24 에 `openai-codex` 를 쓰는 profile 두 곳에서 불러 확인했다.
두 응답의 모양이 같았다.

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
`pricing`, `free_tier`, `unavailable_models` 는 provider 에 따라 붙을 수 있지만 이번 두 응답에는 없었다.

목록은 그 profile 의 `config.yaml` 에서 온다.
`model` 절, `providers`, 이전 형식의 `custom_providers`, `model_catalog.excluded_providers` 를 읽는다.
`fallback_providers` 는 목록에 영향을 주지 않는다.
쿼리는 `refresh` 하나만 읽는다.

다른 경로는 이 목록을 대신하지 못한다.
`/v1/models` 는 위에 적었듯 profile 이름만 준다.
대시보드의 `/api/providers/custom-endpoints` 는 사용자가 더한 endpoint 만 준다.
둘 다 provider 별 모델 목록이 아니다.

## 소진은 본문으로만 알 수 있다

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

## 모델을 바꿔 이어도 맥락이 남는다

같은 `session_id` 로 모델만 바꿔 이어 보내면 앞 turn 의 내용이 그대로 실려 간다.
도구 호출이 있던 turn 이 섞여도 `tool_calls` 와 `tool_call_id` 가 복원된다.
시스템 프롬프트만 새 모델 기준으로 다시 만든다.

깨질 수 있는 자리는 도구 호출 형식이 아니라 Responses 계열의 암호화된 reasoning 조각이다.
Responses 에서 일반 OpenAI 호환 provider 로 갈 때는 그 조각을 걸러 내므로 문제가 없다.
Responses 계열끼리 오갈 때 표시가 없는 옛 조각이 남아 있으면 400 이 날 수 있다.

## 실행 이벤트가 실제로 오는 형태

v0.21.0 의 `gateway/platforms/api_server_runs.py` 가 보내는 것을 실측으로 확인했다.

**사건 이름은 `type` 이 아니라 `event` 다.** 중계하는 쪽이 `type` 을 읽으면 아무것도 받지 못한다.

| `event` | 함께 오는 칸 |
| --- | --- |
| `message.delta` | `delta` 에 답의 조각 |
| `tool.started` | `tool` 에 도구 이름, `preview` 에 인자 앞부분 |
| `tool.completed` | `tool`, `duration` 초, `error` 참거짓 |
| `subagent.start`, `subagent.complete` | [「자식 토큰을 SSE 로 받을 수 있다」](delegation.md#자식-토큰을-sse-로-받을-수-있다) 의 식별자와 작업·사용량 필드 |
| `reasoning.available` | `text` 에 그때까지의 답 전체 |
| `run.completed` | `output` 과 `usage` |
| `run.failed`, `run.cancelled` | 끝 |

`data:` 줄의 JSON 안에 `event` 가 들어 있다. SSE 의 `event:` 줄로 오지 않는다.
10초마다 `: keepalive` 주석이 온다.

**가짜 Hermes 를 이 형태로 맞춰 둔다.**
어긋나면 테스트는 통과하는데 운영에서 조각이 흐르지 않는다. 실측으로 그렇게 한 번 놓쳤다.
