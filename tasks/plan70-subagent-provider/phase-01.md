# Phase 01. 대시보드 plugin 이 자식 session 의 provider 를 읽어 준다

**Execution profile**: standard

## 목표

`hermes/plugins/dashboard-profile-api/__init__.py` 에 `GET /api/profiles/<이름>/sessions/<session id>/provider` 를 연다.
Hermes 의 API server 가 session 응답에 provider 를 싣지 않아, Control Plane 이 native 자식의 금액을 환산하지 못하기 때문이다.

**범위 외**: Control Plane 이 이 경로를 부르는 코드(phase 02), 가짜 Hermes 와 e2e(phase 03), 운영 배포.

## 컨텍스트

같은 파일의 `GET /api/profiles/<이름>/model-defaults` 가 본보기다. 아래 셋을 그대로 따른다.

- 경로 정규식 상수 `MODEL_DEFAULTS_RE`
- 응답을 만드는 동기 함수 `_model_defaults_response(name)`
- `_install_gate` 안 `token_auth_middleware` 의 첫 분기. `seam.authenticate_token(request)` 로 토큰을 확인하고, 아니면 `_rejected("Control Plane 토큰이 필요하다", 401)`, 맞으면 `asyncio.to_thread` 로 응답 함수를 부른다

profile 디렉터리는 `hermes_cli.profiles` 의 `get_profile_dir(name)` 과 `profile_exists(name)` 로 얻는다. 둘은 이미 이 파일이 쓴다.
검사 `hermes/tests/test_dashboard_profile_api.py` 의 `ProfileApiRouteTest` 는 이 둘을 임시 디렉터리로 바꿔 끼운다(`cls.profiles.get_profile_dir = lambda name: cls.root / name`).
같은 클래스의 `test_model_defaults_*` 셋이 요청을 보내는 방법(`self.request(경로, "GET", token="valid", full_response=True)`)의 본보기다.

**근거 문서**: `hermes/README.md` 의 「자식 session 의 provider」, `docs/adr/ADR-063-native-하위-에이전트의-provider-는-대시보드-plugin-이-session-저장소에서-읽어-준다.md`, `docs/hermes/delegation.md` 의 「자식 session 의 provider 는 저장소에만 있다」

## 의도 메모

- Hermes 의 `SessionDB` 나 `hermes_cli.web_server_sessions` 를 import 하지 않는다. 그 읽기 전용 열기는 스키마가 낡았으면 쓰기 연결을 연다. Python 표준 `sqlite3` 만 쓴다
- 응답에 `provider` 와 `model` 말고 다른 칸을 넣지 않는다
- 예외 본문과 SQL 을 응답이나 로그에 싣지 않는다. 로그는 `_model_defaults_response` 처럼 고정 문장 한 줄이다
- 운영 경로나 실제 profile 이름을 코드와 검사에 적지 않는다

## 작업 항목

### 1. `hermes/plugins/dashboard-profile-api/__init__.py` 에 경로를 더한다

- 상수를 더한다
  - `SESSION_PROVIDER_RE = re.compile(r"^/api/profiles/([^/]+)/sessions/([^/]+)/provider$")`
  - `SESSION_ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,128}$")`
  - `SESSION_DB_FILE = "state.db"`, `SESSION_DB_TIMEOUT_SECONDS = 2`
- 함수 `_session_provider_response(name, session_id)` 를 `_model_defaults_response` 아래에 더한다. 순서는 아래와 같다
  1. `name` 이 `PROFILE_NAME_RE` 에 맞지 않거나 `session_id` 가 `SESSION_ID_RE` 에 맞지 않으면 `_rejected(..., 400)`
  2. `profile_exists(name)` 이 거짓이면 `_rejected("없는 profile 이다", 404)`
  3. `get_profile_dir(name) / SESSION_DB_FILE` 이 파일이 아니면 `_rejected("없는 session 이다", 404)`
  4. `sqlite3.connect(경로.resolve().as_uri() + "?mode=ro", uri=True, timeout=SESSION_DB_TIMEOUT_SECONDS)` 로 연다. 연결은 `finally` 에서 닫는다
  5. `SELECT source, model, billing_provider FROM sessions WHERE id = ?` 한 줄을 읽는다. 줄이 없거나 `source` 가 `subagent` 가 아니면 404
  6. `SELECT COUNT(*) FROM (SELECT DISTINCT model, billing_provider FROM session_model_usage WHERE session_id = ? AND task = '')` 를 읽는다
  7. `provider` 는 `billing_provider` 가 비어 있지 않은 문자열이고 6 의 값이 1 이하일 때만 그 값이고, 아니면 `None` 이다. `model` 은 비어 있지 않은 문자열이면 그 값이고 아니면 `None` 이다
  8. `JSONResponse({"provider": provider, "model": model}, status_code=200)`
  9. 4 부터 6 사이의 어떤 예외든 `logger.warning` 고정 문장 한 줄을 남기고 `_rejected("session 저장소를 읽지 못했다", 503)`
- `token_auth_middleware` 의 `defaults_match` 분기 바로 아래에 같은 모양의 분기를 더한다. `method == "GET"` 이고 `SESSION_PROVIDER_RE` 가 맞을 때만 탄다
- `register` 의 `opened` 에 `opened["/api/profiles/<이름>/sessions/<session id>/provider"] = ["GET"]` 를 더한다
- 모듈 docstring 의 「여는 것」 표에 한 줄을 더한다. `| \`GET /api/profiles/<이름>/sessions/<session id>/provider\` | 그 profile 의 자식 session 한 줄에서 provider 와 모델만 읽는다 |`

### 2. `hermes/tests/test_dashboard_profile_api.py` 에 검사를 더한다

`ProfileApiRouteTest` 에 더한다. 검사마다 `self.root / "owner" / "state.db"` 를 `sqlite3` 로 만들어 아래 두 표를 둔다.

```sql
CREATE TABLE sessions (id TEXT PRIMARY KEY, source TEXT, model TEXT, billing_provider TEXT, system_prompt TEXT);
CREATE TABLE session_model_usage (session_id TEXT, model TEXT, billing_provider TEXT, task TEXT);
```

| 검사 | 입력 | 기대 |
| --- | --- | --- |
| provider 와 모델만 돌려준다 | `source='subagent'`, `model='m1'`, `billing_provider='p1'`, `system_prompt='must-not-return'`, usage 한 줄 `('m1','p1','')` | 200, 본문이 정확히 `{"provider": "p1", "model": "m1"}` |
| usage 줄이 없어도 돌려준다 | 위에서 usage 줄 없음 | 200, `provider` 가 `p1` |
| 보조 호출의 짝은 세지 않는다 | usage 에 `('m1','p1','')` 와 `('m9','p9','title')` | 200, `provider` 가 `p1` |
| 짝이 둘이면 provider 를 주지 않는다 | usage 에 `('m1','p1','')` 와 `('m2','p2','')` | 200, `provider` 가 `None`, `model` 이 `m1` |
| provider 가 빈 줄 | `billing_provider` 가 `NULL` 과 `''` 각각 | 200, `provider` 가 `None` |
| 자식이 아닌 session | `source='api_server'` | 404 |
| 없는 session, 없는 profile, `state.db` 가 없는 profile | | 404 |
| session 번호 형식 | `a.b`, 129자 | 400 |
| 표가 없는 저장소 | `sessions` 만 있고 `session_model_usage` 가 없다 | 503 |
| 토큰 | `cookie=True`, `token="invalid"` | 401 |
| 저장소를 바꾸지 않는다 | 요청 전후 `state.db` 의 바이트가 같다 | 같다 |
| 기본 profile | `self.make_profile("default")` 뒤 같은 표를 만든다 | 200 |

## 검증

```bash
# cwd: 저장소 root
python3 -m unittest discover -s hermes/tests
scripts/check-public-safe.sh
! git grep -n "web_server_sessions\|SessionDB" -- hermes/plugins/dashboard-profile-api/__init__.py
```

첫 명령은 실패가 0 이어야 한다. `hermes/tests` 아래 `test_*.py` 를 모두 찾으므로 위 검사를 포함한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/dashboard-profile-api/__init__.py` | 수정 |
| `hermes/tests/test_dashboard_profile_api.py` | 수정 |
