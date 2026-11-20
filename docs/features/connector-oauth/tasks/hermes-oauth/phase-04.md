# Phase 04. 실행기와 직접 실행의 토큰 통로 env

**Execution profile**: deep

## 목표

OAuth 를 선언한 플러그인의 프로세스가 `token_env` 로 토큰 통로 주소를 받는다. 주소는 OAuth 파일에서 오고 서버 정의와 `config.yaml` 에는 없다. 갱신으로 OAuth 파일이 바뀌어도 서버 정의와 리비전은 그대로다.

**범위 외**: 통로 주소를 만드는 Control Plane(뒤 계획).

## 컨텍스트

**요구사항**: REQ6, 그리고 REQ4 의 Hermes 쪽(대기 자리로 부르는 확인 도구)

**근거 문서**: `docs/features/connector-oauth/design.md` 의 「실행기와 직접 실행」, `docs/adr/ADR-20261120-connector-oauth.md`, `docs/code-architecture.md` 의 「플러그인 비밀값을 두는 곳」

- 실행기 정의를 만드는 곳은 `hermes/plugins/dashboard-profile-api/connector_launch.py` 의 `_launcher_server` 이고, 해석은 `_launcher_parts`, `_launcher_inner`, `_launcher_current` 다. 지금 머리 인자는 `<보관 파일 id> <플러그인 id> <칸 key>=<env>...` 이다
- 실행기는 `connector_launcher.py` 의 `_parse_args`, `_read_values`, `_child_env` 다. plugin 패키지 없이 `connector_vault_format.py` 와 `connector_vault_key.py` 만 읽는다(`_modules`)
- 승인한 쓰기와 금융 준비는 `connector_run.py` 의 `_connector_execute_request` 가 `connector_launch.py` 의 `_binding_env` 로 env 를 만든다
- 확인과 선택지 호출은 `connector_run.py` 의 `_connector_call_request` 다. 본문은 `values` 나 `vault` 가운데 하나와 `owner_browser` 를 받는다
- 설치 때 칸 검사는 `connector_install.py` 가 `connector_vault.py` 의 `_vault_values` 를 다시 부른다

## 의도 메모

- `@oauth=<env>` 인자는 연결 방식과 상관없이 OAuth 를 선언한 플러그인의 정의에 늘 둔다. 방식을 바꿔도 정의가 그대로다. 대신 이미 바인딩이 있는 플러그인에 선언을 더하면 그 바인딩들이 한 번 재시작 대기가 된다(설계 「실패와 일관성」 의 배포 순서)
- 실행기가 OAuth 파일을 풀 때도 plugin 패키지를 읽지 않는다. 풀기 함수는 phase 02 가 `connector_vault_format.py` 에 둔다
- OAuth 파일이 없거나 풀지 못하면 env 를 빈 값으로 두고 서버는 뜬다. 붙여 넣기 연결은 이 경우다

## 작업 항목

### 1. `hermes/plugins/dashboard-profile-api/connector_launch.py` 수정

- `_launcher_server`: `manifest["oauth"]` 가 있으면 머리 인자 끝에 `@oauth=<token_env>` 를 더하고 정의 env 의 `token_env` 를 빈 값 `""` 으로 둔다(주인 env 와 같은 방식). `${이름}` 을 남기면 profile `.env` 에 값이 없을 때 그 글자가 프로세스에 간다. `oauth` 선언이 틀려 null 이 된 플러그인도 `token_env` 가 서버 env 에 있으면 빈 값으로 둔다
- `_launcher_parts`: `@` 로 시작하는 인자를 칸과 따로 읽어 `"oauth_env"` 로 돌려준다. 두 번 나오면 None
- `_launcher_inner`: `oauth_env` 를 `${이름}` 참조로 env 에 넣는다. 펼친 모양이 `.mcp.json` 과 같아 `connector_manifest.py` 의 `_server_matches` 가 지금 규칙대로 비교한다
- `_server_matches`(`connector_manifest.py`): 옛 정의(실행기가 아닌 정의)에서 `token_env` 값은 빈 값이나 `${이름}` 을 받는다
- `_launcher_current`: `oauth_env` 가 manifest 의 `token_env` 와 같아야 참이다
- `_binding_env`: 실행기 정의면 OAuth 파일의 `token_url` 을 `token_env` 에 넣는다. 없으면 빈 값

### 2. `hermes/plugins/dashboard-profile-api/connector_launcher.py` 수정

`_parse_args` 가 `@oauth=<env>` 를 받는다. 있으면 OAuth 파일(`<보관 파일 id>.oauth`)을 풀어 `token_url` 을 자식 env 에 넣는다. 파일이 없으면 빈 값이다.

### 3. 실행기의 OAuth 파일 풀기

phase 02 가 `connector_vault_format.py` 에 둔 `_parse_oauth`, `_unseal_oauth` 를 실행기의 `_modules` 로 읽은 모듈에서 부른다. 새 모듈을 더하지 않는다.

### 4. `hermes/plugins/dashboard-profile-api/connector_run.py` 수정

`_connector_call_request` 본문 모양 검사에 `oauth`(`"current"` 나 `"pending"`)를 더한다. `oauth` 는 `vault` 와만 함께 받고 `values` 와 함께면 400 이다. `current` 면 본 자리 보관 파일과 OAuth 파일의 `token_url` 을, `pending` 이면 대기 보관 파일의 칸 값과 대기 OAuth 파일의 `token_url` 을 `token_env` 로 넣는다. `pending` 은 본 자리 보관 파일이 없어도 된다. OAuth 를 선언하지 않은 플러그인에 `oauth` 를 주면 400 이다.

### 5. `hermes/plugins/dashboard-profile-api/connector_install.py` 수정

설치 때 칸 검사에서 그 연결에 OAuth 파일이 있으면 `oauth=True` 로 `_vault_values` 를 부른다. 설치는 지금처럼 `PROFILE_WRITE_LOCK` 만 잡고 `vault_lock` 을 잡지 않는다. 본 보관 파일을 쓰는 쪽이 보관 파일 잠금 다음 전역 잠금을 잡으므로 설치가 읽는 동안 파일이 바뀌지 않는다(설계 「OAuth 파일」).

### 6. `hermes/plugins/dashboard-profile-api/README.md` 수정

`call`·`list` 본문의 `oauth`, 실행기 인자 `@oauth`, 정의 env 의 빈 `token_env` 를 더한다.

### 7. `docs/code-architecture.md` 수정

「플러그인 비밀값을 두는 곳」 표에 OAuth 파일(refresh token 과 통로 주소, 해제 때 지워짐)과 access token(Control Plane 메모리와 통로 응답, 뒤 계획) 줄을 더한다. 근거로 ADR-20261120 / connector-oauth 를 링크한다.

### 8. `hermes/tests/test_connector_oauth_flow.py` 신규

대시보드 경로를 HTTP 로 끝까지 한 번에 돈다. 가짜 GitHub 서버와 OAuth 선언 테스트 플러그인을 쓴다.

`authorize-url` → `begin`(단추 밖 칸) → `exchange` → `call`(`oauth: "pending"`, 확인 도구가 받은 env 를 파일로 냄) → `commit` → 바인딩 설치 → 실행기 자식 env → 보관 파일 `PUT`(붙여 넣기로 바꿈, OAuth 파일이 사라짐) → 단추로 다시 연결 → 보관 파일 `DELETE`(OAuth 파일까지 사라짐).
각 단계 뒤 파일과 env 를 확인한다. 확인 도구와 실행기 자식이 받은 `token_env` 가 각각 대기 주소와 본 주소다.

### 9. `hermes/tests/test_connector_oauth_launch.py` 신규

`hermes/tests/test_connector_launcher.py` 의 env 를 파일로 내는 스크립트 방식을 따른다.

- OAuth 선언 플러그인을 붙이면 정의 인자에 `@oauth=` 가 있고 정의 env 에 통로 주소 값이 없다
- OAuth 파일이 있으면 실행기 자식 env 의 `token_env` 가 `token_url` 이고, 없으면 빈 값이다
- OAuth 파일의 refresh token 을 바꾼 뒤 다시 설치하면 `restart_required` 가 아니다
- `@oauth` 인자가 있는 정의를 `_launcher_current` 가 낡았다고 보지 않는다
- 직접 실행(`execute`)의 자식 env 에 `token_url` 이 들어간다
- `oauth` 선언이 틀려 null 인 플러그인의 정의 env 에서 `token_env` 가 빈 값이다
- `call` 에 `oauth` 와 `values` 를 함께 주면 400 이다
- `call` 에 `oauth: "pending"` 을 주면 대기 보관 파일 값과 대기 통로 주소로 돌고, 본 자리 보관 파일이 없어도 된다
- `config.yaml` 과 소유 기록에 통로 주소가 없다

## 검증

```bash
python3 -m unittest discover -s hermes/tests -p 'test_connector_oauth_launch.py'
python3 -m unittest discover -s hermes/tests -p 'test_connector_oauth_flow.py'
python3 -m unittest discover -s hermes/tests -p 'test_connector_launcher.py'
python3 -m unittest discover -s hermes/tests -p 'test_connector_call.py'
python3 -m unittest discover -s hermes/tests -p 'test_connector_execute.py'
python3 -m unittest discover -s hermes/tests
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/dashboard-profile-api/connector_launch.py` | 수정 (`@oauth` 인자, `_binding_env`) |
| `hermes/plugins/dashboard-profile-api/connector_launcher.py` | 수정 (OAuth 파일의 통로 주소) |
| `hermes/plugins/dashboard-profile-api/connector_manifest.py` | 수정 (`_server_matches` 의 `token_env`) |
| `hermes/plugins/dashboard-profile-api/README.md` | 수정 (`call` 의 `oauth`, 실행기 인자) |
| `hermes/plugins/dashboard-profile-api/connector_run.py` | 수정 (`call` 의 `oauth`) |
| `hermes/plugins/dashboard-profile-api/connector_install.py` | 수정 (OAuth 칸 예외) |
| `docs/code-architecture.md` | 수정 (OAuth 파일의 자리) |
| `hermes/tests/test_connector_oauth_launch.py` | 신규 |
| `hermes/tests/test_connector_oauth_flow.py` | 신규 |
