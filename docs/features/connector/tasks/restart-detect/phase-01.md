# Phase 01. 대시보드 plugin 의 gateway 기동 시각 조회

**Execution profile**: standard

## 목표

대시보드 plugin 이 토큰으로 부르는 `GET /api/gateway` 를 열어 공유 gateway 의 기동 시각(epoch 초)이나 `null` 을 답한다.
Control Plane 이 재시작을 알아채는 유일한 표식이다(설계 DECISION3).

**범위 외**: Control Plane 의 호출과 판단(phase 02, 03).

## 컨텍스트

**요구사항**: REQ3, REQ4

**근거 문서**: `docs/features/connector/spec.md`, `docs/features/connector/design.md` 의 「대시보드 plugin: gateway 기동 시각(PR2)」

- 토큰 경로는 `hermes/plugins/dashboard-profile-api/routes.py` 의 `token_auth_middleware` 가 직접 판정한다. `MODEL_DEFAULTS_RE` 분기처럼 토큰을 확인하고 401, 맞으면 처리 함수를 `asyncio.to_thread` 로 부른다. 쓰기 잠금(`PROFILE_WRITE_LOCK`)은 잡지 않는다.
- Hermes v0.23.2 의 `gateway.status.get_running_pid(pid_path=None, *, cleanup_stale=True)` 는 pid 를 읽는다. 인자 없이 부르면 낡은 pid 파일을 지울 수 있으므로 `get_running_pid(<기본 root>/gateway.pid, cleanup_stale=False)` 로만 부른다. 기본 root 는 `hermes_constants.get_default_hermes_root()` 다(이미 계약에 있다).
- Hermes 의 대시보드에는 `/api/gateway/start` 같은 경로가 있지만 정확히 `GET /api/gateway` 는 없다. 정확히 맞는 경로와 메서드만 가로챈다.
- 새 내부 지점은 rule-64 대로 `hermes/tests/hermes_contract.py` 의 `SYMBOLS` 에 같은 커밋에서 선언한다.
- 검사의 가짜 Hermes 는 `hermes/tests/dashboard_profile_api_support.py` 의 `setUpClass` 가 `sys.modules` 에 넣는다. `gateway.status` 모듈이 없으므로 더한다.

## 의도 메모

- 시작 시각은 Hermes 함수가 아니라 `/proc/<pid>/stat` 의 22번째 칸(시작 tick), `os.sysconf("SC_CLK_TCK")`, `/proc/stat` 의 `btime` 으로 구한다. comm 칸에 공백과 괄호가 들어갈 수 있으므로 마지막 `)` 뒤를 나눠 읽는다.
- 예외 본문과 경로는 로그에 남기지 않는다. 실패는 `null` 이고, 로그는 Control Plane 이 상태가 바뀔 때만 남긴다.

## 작업 항목

### 1. `hermes/plugins/dashboard-profile-api/gateway_status.py` 신규

- `GATEWAY_PATH = "/api/gateway"`
- `_gateway_started_at(proc_root: pathlib.Path = pathlib.Path("/proc")) -> Optional[int]`: pid 를 위 부름으로 읽고 `/proc` 세 값으로 `int(btime + start_ticks / ticks)` 를 돌려준다. pid 가 없거나 어느 읽기든 실패하면 None.
- `_gateway_response()`: `JSONResponse({"started_at": <값|None>}, status_code=200)`.

### 2. `hermes/plugins/dashboard-profile-api/routes.py` 의 분기

`token_auth_middleware` 앞쪽(`defaults_match` 분기 옆)에 `path == GATEWAY_PATH and method == "GET"` 분기를 더한다. 토큰이 아니면 `_rejected("Control Plane 토큰이 필요하다", 401)`.

### 3. `hermes/tests/hermes_contract.py`

`("gateway.status", "get_running_pid"): Call(positional=1, keywords=("cleanup_stale",), returns="Optional[int]")` 를 더한다. `returns` 는 Hermes 소스의 annotation 문자열과 같아야 한다.

### 4. `hermes/tests/dashboard_profile_api_support.py` 와 `hermes/tests/test_dashboard_profile_api_gateway.py`(신규)

support 에 `gateway.status` 가짜 모듈과 기본 `get_running_pid`(None 을 돌려줌)를 더한다. 새 테스트가 본다.

- 가짜 pid 와 임시 `/proc` 트리(`<pid>/stat`, `stat`)로 기동 시각이 `btime + tick/CLK_TCK` 의 정수인지, comm 에 `) (` 가 든 줄도 읽는지
- `get_running_pid` 가 `<기본 root>/gateway.pid` 와 `cleanup_stale=False` 로 불렸는지
- pid 가 None 이거나 `/proc` 파일이 없으면 `started_at: null` 이고 200 인지
- 토큰이 없으면 401, `POST /api/gateway` 와 `/api/gateway/start` 는 가로채지 않는지

## 검증

```bash
cd hermes/tests && python3 -m unittest test_dashboard_profile_api_gateway test_hermes_contract test_dashboard_profile_api_routes
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/dashboard-profile-api/gateway_status.py` | 신규 |
| `hermes/plugins/dashboard-profile-api/routes.py` | 수정 (토큰 분기) |
| `hermes/plugins/dashboard-profile-api/README.md` | 수정 (경로 표에 한 줄) |
| `hermes/tests/hermes_contract.py` | 수정 (내부 지점 선언) |
| `hermes/tests/dashboard_profile_api_support.py` | 수정 (가짜 `gateway.status`) |
| `hermes/tests/test_dashboard_profile_api_gateway.py` | 신규 |
