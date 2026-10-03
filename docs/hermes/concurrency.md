# 동시 실행

## profile 접두

아래 설정 우선순위와 허용 목록은 v0.21.0 의 동작이다.
v0.21.3 은 `gateway.multiplex_profile_allowlist` 를 제거했고,
v0.21.4 이후에는 multiplex 기본값과 gateway 실행 방식도 바뀐다.
버전별 차이는 [「버전을 올릴 때 달라지는 계약」](upgrades.md#버전을-올릴-때-달라지는-계약) 을 따른다.

`gateway.multiplex_profiles` 를 켜면 listener 하나가 `/p/<profile>/...` 로 모든 profile 을 받는다.
default profile 의 listener 에는 각 경로가 접두 없는 형태와 `/p/<profile>` 접두 형태로 함께 등록된다.
`connect()` 가 경로 표를 한 번 순회하며 두 형태를 모두 등록한다.

multiplex 사용 여부는 아래 순서로 정한다.

1. 환경 변수 `GATEWAY_MULTIPLEX_PROFILES`
2. 기본 profile `config.yaml` 의 `gateway.multiplex_profiles`
3. 기본값 `false`

환경 변수에서는 `true`, `1`, `yes`, `on` 을 참으로 읽는다.
빈 문자열이나 알 수 없는 값은 무시하고 `config.yaml` 값을 쓴다.

`gateway.multiplex_profile_allowlist` 를 적지 않으면 이름이 유효하고 삭제 표시가 없는 profile 을 모두 제공한다.
빈 목록을 적으면 기본 profile 만 제공하고, 이름을 적으면 그 profile 만 제공한다.
허용 목록을 쓰면 profile 을 만들 때마다 목록도 고쳐야 한다.

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

### credential 을 푸는 규칙이 달라진다

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

### 보조 profile 경고와 실행 범위

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

### 실행 소유자는 profile 과 key 가 함께 정한다

Hermes 는 실행을 만들 때 아래 값을 줄여 소유자 표에 남긴다.

```text
sha256(profile + "\0" + expected_api_key)
```

요청의 값이 소유자 표와 다르거나 소유자 표가 없으면 실행이 있는지 확인하지 않고 404 를 돌려준다.
조회, 사건 SSE, 중단, 승인과 steer 가 모두 같은 판정을 쓴다.
그래서 다른 profile 은 실행 번호를 알아도 그 실행의 존재 여부를 확인할 수 없다.

### 공유 listener 가 바꾸는 경계

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

## 동시 실행 한도는 listener 단위다

`gateway.api_server.max_concurrent_runs` 가 동시에 도는 실행을 제한한다.
그 값은 listener 주인 profile 의 설정에서 한 번 읽고, listener 가 하나면 한도도 하나다.

**공유 listener 로 옮기면 모든 profile 이 그 한도를 나눠 쓴다.**
넘으면 429 와 `rate_limit_exceeded` 가 온다.

한도를 끄면 실행이 메모리 한도까지 쌓이고, 그때 죽는 것은 프로세스 하나라
모든 profile 이 함께 끊긴다. 유한한 값을 두는 편이 낫다.

`gateway.max_concurrent_sessions` 는 다른 것이다.
그쪽은 platform 대화 turn 을 제한하고 `/v1/runs` 에는 걸리지 않는다.

## 한도 위에 thread pool 이 하나 더 있다

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

## 한도를 pool 보다 크게 두면 429 가 아니라 기다린다

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

## pool 은 실행만 쓰는 것이 아니다

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

## pool 은 plugin 으로 키울 수 있다

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

## pool 말고 걸리는 것

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

Control Plane 은 이 자리를 사용자마다 나눠 쓰게 한다. 한 사용자가 동시에 맡기는 실행을 사용자 실행 한도로 묶는다([`backend/execution-limit.md`](../backend/execution-limit.md)).
그 한도는 Control Plane 이 제출하는 실행만 센다. native 하위 에이전트와 cron 은 세지 않는다.
Control Plane 이 대기 시간을 넘겨 먼저 끝낸 run 은 Hermes 가 끝냈다고 답할 때까지 그 사용자의 자리로 센다.
이 자리는 Control Plane 메모리에만 있어, Control Plane 이 다시 뜨면 그 run 이 Hermes 에서 끝나기 전이라도 세지 않는다.

## 스레드를 늘리는 비용

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
