# Phase 03. `connector-oauth` 경로와 서비스 HTTP

**Execution profile**: deep

## 목표

Control Plane 이 서비스 토큰으로 부르는 `connector-oauth` 경로 여덟 개를 연다. 로그인 주소 만들기, 코드 교환, 갱신, 취소를 제공자 표대로 서비스에 보내고 결과를 `outcome` 다섯 낱말로 답한다.

**범위 외**: Control Plane 의 호출 쪽(뒤 계획), 실행기(phase 04).

## 컨텍스트

**요구사항**: REQ6

**근거 문서**: `docs/features/connector-oauth/design.md` 의 「대시보드 plugin 경로(서비스 토큰)」, 「취소 범위」, 「흐름」 의 PKCE, `docs/adr/ADR-20261120-connector-oauth.md`

- 경로 분기는 `hermes/plugins/dashboard-profile-api/routes.py` 의 `token_auth_middleware` 다. Control Plane 토큰 검사는 `VAULT_ROUTES` 분기와 같은 `seam.authenticate_token` 을 쓴다
- 응답 모양은 `_rejected`, `JSONResponse` 를 쓰는 `connector_vault.py` 를 따른다
- 경로 목록 표는 `hermes/plugins/dashboard-profile-api/__init__.py` 머리 설명이 갖는다
- 서비스 HTTP 는 표준 라이브러리 `urllib.request` 로 하고 이벤트 루프를 막지 않게 `asyncio.to_thread` 로 부른다. 새 의존을 더하지 않는다

## 의도 메모

- client secret 은 요청 본문으로만 받고 파일과 로그에 쓰지 않는다. 서비스 응답 원문과 토큰도 로그에 쓰지 않는다. 로그에는 플러그인 id, 경로, `outcome` 만 둔다
- 갱신은 보관 파일 잠금 안에서 서비스 호출부터 OAuth 파일 쓰기까지 한다. 바뀐 refresh token 을 쓰지 못하면 access token 을 주지 않고 `unavailable` 이다
- GitHub `install` 로그인 주소에는 PKCE challenge 를 넣지 않는다. 받을 자리가 없다. `authorize` 와 Google 에는 넣는다
- Google discard 는 서비스 취소를 하지 않는다. Google 은 토큰 하나 취소로 그 앱의 허락 전체가 사라진다(설계 「취소 범위」)

## 작업 항목

### 1. `hermes/plugins/dashboard-profile-api/connector_oauth.py` 신규

경로 처리와 서비스 호출을 둔다. 제공자별 차이는 `connector_oauth_providers.py` 의 표에서 읽는다.

| 경로 | 처리 |
| --- | --- |
| `POST /api/connector-oauth/authorize-url` | 본문 `{connector, mode, client_id, app_slug?, redirect_uri, state, code_challenge?}`. 카탈로그의 `oauth` 가 없으면 404. GitHub `install` 은 설치 화면에 `state` 를, `authorize` 는 허락 화면에 `client_id`, `redirect_uri`, `state`, `code_challenge`, `code_challenge_method=S256` 을 붙인다. Google 은 `scope`(선언 scope 를 공백으로 이음), `access_type=offline`, `prompt=consent`, `include_granted_scopes=false`, `response_type=code` 를 더한다. `{"url"}` 을 답한다 |
| `POST /api/connector-oauth/begin` | 본문 `{vault, connector, values}`. `_vault_values(manifest, values, oauth=True)` 로 검사하고 `_begin` 을 부른다. `{"nonce"}` |
| `POST /api/connector-oauth/exchange` | 본문 `{vault, connector, nonce, client_id, client_secret, code, code_verifier?, redirect_uri, token_url}`. 토큰 주소에 코드를 보낸다. Google 은 응답 `scope` 가 선언 scope 를 모두 담지 않으면 `scope_denied` 이고 파일을 쓰지 않는다. 성공하면 `_fill_pending` 으로 refresh token, 받은 scope, `token_url` 을 쓰고 `{"outcome": "ok", "access_token", "expires_at"}` |
| `POST /api/connector-oauth/commit` | 본문 `{vault, nonce}`. `_commit`. nonce 가 다르면 409 |
| `POST /api/connector-oauth/discard` | 본문 `{vault, connector, nonce, client_id, client_secret, access_token}`. GitHub 은 그 access token 하나를 취소(`DELETE /applications/{client_id}/token`, 기본 인증)하고, 결과와 상관없이 `_discard` |
| `POST /api/connector-oauth/refresh` | 본문 `{vault, connector, client_id, client_secret}`. OAuth 파일이 없으면 `{"outcome": "missing"}`. 있으면 refresh token 으로 갱신하고, 응답에 새 refresh token 이 있으면 OAuth 파일에 쓴 뒤 `{"outcome": "ok", "access_token", "expires_at"}` |
| `POST /api/connector-oauth/revoke` | 본문 `{vault, connector, client_id, client_secret, access_token?}`. GitHub 은 access token 이 없으면 먼저 갱신해 받고 `DELETE /applications/{client_id}/grant` 를 보낸다. Google 은 refresh token 으로 취소 주소를 부른다. 파일은 지우지 않는다 |
| `POST /api/connector-oauth/token-url` | 본문 `{vault, token_url}`. OAuth 파일의 `token_url` 만 바꾼다 |

- `outcome` 분류: 서비스가 `invalid_grant`, `bad_refresh_token`, `bad_verification_code` 처럼 사용자 허락 쪽을 거절하면 `grant_revoked`, `invalid_client`, `unauthorized_client`, `incorrect_client_credentials` 처럼 앱 자격을 거절하면 `app_rejected`, 연결 실패·시간 초과·5xx 는 `unavailable`. 정확한 오류 낱말은 제공자 표가 갖는다
- `expires_at` 은 epoch 초다. 서비스가 만료를 주지 않으면 null 이다
- 모든 경로는 `vault_lock(vault)` 안에서 파일을 읽고 쓴다. `PROFILE_WRITE_LOCK` 은 잡지 않는다
- 서비스 호출 시간 제한은 10초다

### 2. `hermes/plugins/dashboard-profile-api/routes.py` 수정

`/api/connector-oauth/` 아래 여덟 경로를 `POST` 로만 받고, Control Plane 토큰을 검사한 뒤 `connector_oauth.py` 로 넘긴다.

### 3. `hermes/plugins/dashboard-profile-api/__init__.py` 수정

머리 설명의 경로 표에 여덟 경로를 한 줄씩 더한다.

### 4. `hermes/plugins/dashboard-profile-api/README.md` 수정

「플러그인」 에 `connector-oauth` 경로의 요청과 응답, `outcome` 낱말, OAuth 파일과 대기 파일의 자리와 지우는 때를 더한다.

### 5. `hermes/tests/fixtures/connector-oauth/` 신규

Control Plane 테스트가 같은 모양을 쓰도록 경로마다 요청과 응답 예시 JSON 을 둔다(`exchange-request.json`, `exchange-response.json`, `refresh-response.json`). 뒤 계획의 Control Plane 가짜 대시보드가 이 파일을 읽는다.

### 6. `hermes/tests/test_connector_oauth_routes.py` 신규

로컬 HTTP 서버로 가짜 GitHub 과 가짜 Google 을 띄우고 제공자 표의 주소를 그 서버로 바꿔 돈다(`hermes/tests/fixtures/conditional_plugin_http.py` 처럼 표준 라이브러리 HTTP 서버).

- authorize-url: GitHub `install` 에 challenge 가 없고 `authorize` 와 Google 에 있다. Google 에 `access_type=offline` 이 있다
- exchange: 성공하면 대기 OAuth 파일에 refresh token 이 있고 응답에 refresh token 이 없다. Google 부분 scope 는 `scope_denied` 이고 대기 OAuth 파일에 토큰이 없다. 다른 nonce 는 409
- refresh: GitHub 이 새 refresh token 을 주면 OAuth 파일이 바뀐다. `invalid_grant` 는 `grant_revoked`, `invalid_client` 는 `app_rejected`, 서버 중단은 `unavailable`
- refresh 중 OAuth 파일 쓰기가 실패하면 `unavailable` 이고 access token 이 없다
- revoke: GitHub 은 access token 이 없으면 갱신 요청이 먼저 간다. Google 은 refresh token 으로 취소 주소를 부른다
- discard: Google 은 서비스 취소 요청이 가지 않는다
- 갱신이 가짜 서비스에서 멈춘 동안 다른 profile 의 보관 파일 `PUT` 이 끝난다(전역 잠금을 잡지 않음)
- Control Plane 토큰 없이 부르면 401
- 로그에 client secret, refresh token, access token 이 없다

## 검증

```bash
python3 -m unittest discover -s hermes/tests -p 'test_connector_oauth_routes.py'
python3 -m unittest discover -s hermes/tests -p 'test_connector_oauth_*.py'
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/dashboard-profile-api/connector_oauth.py` | 신규 |
| `hermes/plugins/dashboard-profile-api/routes.py` | 수정 (`connector-oauth` 분기) |
| `hermes/plugins/dashboard-profile-api/__init__.py` | 수정 (경로 표) |
| `hermes/plugins/dashboard-profile-api/README.md` | 수정 (`connector-oauth` 계약) |
| `hermes/tests/fixtures/connector-oauth/exchange-request.json` | 신규 |
| `hermes/tests/fixtures/connector-oauth/exchange-response.json` | 신규 |
| `hermes/tests/fixtures/connector-oauth/refresh-response.json` | 신규 |
| `hermes/tests/test_connector_oauth_routes.py` | 신규 |
