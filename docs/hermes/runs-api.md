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

v0.21.5 의 `gateway/platforms/api_server_runs.py` 에 `_handle_steer_run` 이 있다.

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

v0.21.5 의 [`gateway/platforms/api_server_runs.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/gateway/platforms/api_server_runs.py) 와 `api_server.py` 를 소스로 읽었다. 운영 Hermes 에서 왕복으로 확인하지는 않았다.

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

## `/v1/runs` 에 사진을 싣는 법

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
| `detail` | Responses 만 옮기고 Anthropic, Gemini 는 무시한다. Control Plane 은 보내지 않는다 |

Control Plane 이 이 모양으로 사진을 싣는 결정은 [ADR-20261009 / native-image-input](../adr/ADR-20261009-native-image-input.md) 에 있다.

**가짜 Hermes 도 목록 `input` 을 받는다.** 마지막 항목의 첫 글 파트를 입력 글로 읽고, 이미지 파트를 그 앞의 이름표 글과 함께 따로 기억한다.

### 운영에서 확인할 것

배포한 뒤 사진을 붙여 한 번 보내 본다. 실제 명령은 `fos-home-infra` 가 갖는다.

- 답이 사진 내용을 말하고, 그 실행의 도구 사건에 `vision_analyze` 가 없다
- 그 실행에 413 이나 `invalid_image_url` 같은 오류가 없다
- 다음 메시지에서 지난 사진을 물으면 에이전트가 `vision_analyze` 로 사본 파일을 본다
- 11장 이상을 보내면 11번째부터 사본 파일을 `vision_analyze` 로 보고, 잘림 오류가 없다

## 이미지 파일은 `vision_analyze` 로 본다

`read_file` 이 이미지 확장자를 만나면 내용을 돌려주지 않고 `vision_analyze` 를 쓰라는 안내를 낸다.
입력에 싣지 못한 사진과 지난 메시지의 사진은 이 도구로 원본 옆의 줄인 사본(`{첨부 번호}.small.jpg`)을 본다. 사본은 대개 1MB 아래다.

실행 공간(Docker)의 파일은 컨테이너 안에서 `head -c <50MB+1> < 경로 | base64` 로 읽는다(`tools/image_source.py`).
운영에서 3MB 를 넘는 사진이 가끔 「image file is truncated」 로 실패했다. Pillow 가 디코딩하다 바이트가 모자란 것이다.
어디서 잘리는지는 확인하지 못했다. 파이프의 종료 코드가 마지막 명령의 것이라 읽기 오류가 가려진다.

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

**v0.21.5 에서 달라진 두 가지**(운영에서 확인):

- `GET /api/sessions/{session_id}` 는 행을 `{"object": "session", "session": {...}}` 로 감싸고, `model` 은 `session` 안에 있다. provider 칸은 응답에 없다. Hermes 저장소에는 `billing_provider` 로 남는다. 저장소의 그 칸은 [`delegation.md`](delegation.md) 의 「자식 session 의 provider 는 저장소에만 있다」 가 적는다
- `GET /v1/runs/{run_id}` 는 끝난 실행에 `runtime: {"provider", "model", "route_source"}` 를 싣는다. fallback 으로 넘어간 경우도 실제로 돈 값이다. 근거는 [v0.21.5 `gateway/platforms/api_server_runs.py`](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/gateway/platforms/api_server_runs.py) 의 `_served_runtime` 과 `api_server.py` 의 `_sanitize_runtime_metadata` 다

그래서 Control Plane 은 실행의 `runtime` 에 provider 와 모델이 둘 다 있으면 그 짝을 실행 기록에 적는다.
둘 중 하나라도 없으면 세션 조회, 실행의 `runtime`, 대화가 고른 값 순서로 먼저 있는 것을 칸마다 따로 채운다.

`POST /api/sessions/{id}/chat` 은 응답에 `runtime` 을 담아 요청한 것과 실제로 돈 것을
한 응답에서 대조할 수 있다. v0.21.3 까지는 그 블록이 `/v1/runs` 에 없었다. v0.21.5 는 위 「v0.21.5 에서 달라진 두 가지」 처럼 실행 조회에도 싣는다.

`/v1/capabilities` 와 `/v1/models` 도 실제로 쓴 provider 모델을 주지 않는다.
실제 모델을 알아야 하면 `/api/model/options` 가 그 profile 의 것을 답한다.

## `/api/model/options` 는 provider 와 모델 목록을 함께 준다

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
| `tool.started` | `tool` 에 도구 이름, `preview` 에 주요 인자 하나의 값. 아래 「붙은 커넥터 서버의 도구 사건」 을 본다 |
| `tool.completed` | `tool`, `duration` 초, `error` 참거짓. v0.21.5 는 `preview` 에 결과를 더한다 |
| `subagent.start`, `subagent.complete` | [「자식 토큰을 SSE 로 받을 수 있다」](delegation.md#자식-토큰을-sse-로-받을-수-있다) 의 식별자와 작업·사용량 필드 |
| `reasoning.available` | `text` 에 그때까지의 답 전체 |
| `run.completed` | `output` 과 `usage` |
| `run.failed`, `run.cancelled` | 끝 |

`data:` 줄의 JSON 안에 `event` 가 들어 있다. SSE 의 `event:` 줄로 오지 않는다.
10초마다 `: keepalive` 주석이 온다.

**가짜 Hermes 를 이 형태로 맞춰 둔다.**
어긋나면 테스트는 통과하는데 운영에서 조각이 흐르지 않는다.

### 붙은 커넥터 서버의 도구 사건

붙은 커넥터 서버의 MCP 도구도 내장 도구와 같은 길로 사건을 보낸다.
v0.21.5 의 `agent/tool_executor.py` 가 도구 하나를 실행하기 전에 `tool.started`, 끝난 뒤에 `tool.completed` 를 `tool_progress_callback` 으로 부르고,
`gateway/platforms/api_server_runs.py` 가 그것을 위 표의 사건으로 바꿔 보낸다.
도구 종류에 따라 다르게 보내는 곳은 없다.

| 칸 | 값 |
| --- | --- |
| `tool` | 등록 이름 `mcp__<서버>__<도구>`. 줄이는 규칙은 [`connector-policy.md`](connector-policy.md) 의 「MCP 도구의 등록 이름」 이 갖는다 |
| `tool.started` 의 `preview` | 주요 인자 하나의 값. 도구마다 정한 인자가 있고, 모르는 도구는 `query`, `text`, `command`, `path`, `name`, `prompt`, `code`, `goal` 중 처음 있는 인자다. 그것이 없으면 null 이다. 비밀값을 가리지 않고 `tool_preview_length` 로만 자른다 |
| `tool.completed` 의 `preview` | 비밀값을 가리고 500자로 자른 결과. 외부 서비스의 글이 실린다 |
| 모든 사건의 `run_id`, `timestamp` | 있다 |
| 정책 hook 이 막은 호출 | 시작도 완료도 오지 않는다 |

그 결과 글이 실행 기록과 화면에 남지 않게 Control Plane 이 붙은 서버의 도구 내용을 통째로 가린다([ADR-083](../adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md)).
도구 이름, 걸린 시간, 실패 여부만 남는다.

**사건은 우리가 `GET /v1/runs/{run_id}/events` 를 열어야 받는다.**
Hermes 는 도구를 실행했다는 로그를 남기지만 사건 스트림은 구독자가 있을 때만 읽힌다.
Control Plane 은 한 번에 받는 경로에서도 스트림을 연다([ADR-090](../adr/ADR-090-한-번에-받는-경로도-hermes-사건-스트림을-열어-도구-사건을-남긴다.md)).

가짜 Hermes 는 허용된 커넥터 도구 호출을 위 모양(`run_id`, `timestamp`, 시작의 인자 `preview`, 완료의 결과 `preview`)으로 흘린다.

## session 을 지우는 경로

v2026.9.24 소스에서 확인했다. API server 가 `DELETE /api/sessions/{session_id}` 를 연다(`gateway/platforms/api_server.py` 의 `_handle_delete_session`).
`/p/<profile>/` 접두로 부르면 그 profile 의 `state.db` 에서 지운다.

| 무엇 | 동작 |
| --- | --- |
| 응답 | 200 `{"object": "hermes.session.deleted", "id": ..., "deleted": true}`. 모르는 session 은 404 `session_not_found` 다 |
| 함께 지우는 것 | 그 session 의 메시지와 위임으로 만든 자식 session(`delegate_task`)이 한 쓰기 트랜잭션에서 지워진다(`hermes_state_sessions.py` 의 `delete_session`) |
| 남는 것 | 압축과 분기로 이어진 자식 session 은 지우지 않고 부모 칸만 비운다. `sessions_dir` 없이 부르므로 `sessions/` 아래 기록 파일과 요청 덤프(`request_dump_<session>_*.json`)도 남는다 |
| 인증 | 그 profile 의 key 를 `Authorization: Bearer` 로 보낸다. 실행 경로와 같다 |

Control Plane 은 지운 대화를 정리할 때 이 경로를 부른다. 근거는 [ADR-20261008 / conversation-purge](../adr/ADR-20261008-conversation-purge.md) 에 있다.
