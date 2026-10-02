# kanban

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

2026-09-18 에 `v2026.8.31` 배포본을 격리 환경에 띄워 측정했다.

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
2026-09-18 조사 당시 최신 릴리스 `v2026.9.14` 를 받아 같은 스키마를 확인했다. 그대로였다.
그 릴리스의 API server 에도 kanban 경로가 없다.
2026-09-18 조사 당시 배포본은 `v2026.8.31` 이었다.

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
