# Phase 02. OAuth 파일, 대기 파일, 보관 파일 잠금

**Execution profile**: deep

## 목표

refresh token 과 토큰 통로 주소를 담는 OAuth 파일과 대기 파일을 보관 파일 옆에 둔다. 보관 파일 id 마다 잠금을 두고, OAuth 연결은 단추로 채우는 칸이 비어도 보관 파일을 쓸 수 있게 한다.

**범위 외**: 서비스 HTTP 와 `connector-oauth` 경로(phase 03), 실행기(phase 04).

## 컨텍스트

**요구사항**: REQ6

**근거 문서**: `docs/features/connector-oauth/design.md` 의 「OAuth 파일」, 「실패와 일관성」, `docs/adr/ADR-20261120-connector-oauth.md`

- 보관 파일 쓰기와 지우기는 `hermes/plugins/dashboard-profile-api/connector_vault.py` 의 `_write_vault`, `_connector_vault_request` 다. 암호화는 `connector_vault_key.py` 의 key, 글 모양은 `connector_vault_format.py` 다
- 보관 파일의 `values` 는 문자열 사전만 받는다(`connector_vault_format.py` 의 `_parse_vault`). OAuth 상태를 거기 넣지 않는다
- 실행기 정의의 리비전은 보관 파일의 inode, 수정 시각, 크기의 해시다(`connector_launch.py` 의 `_vault_revision`). OAuth 파일은 이 해시에 들어가지 않는다
- 보관 파일 요청은 지금 `routes.py` 에서 전역 `PROFILE_WRITE_LOCK` 안에서 돈다
- 칸 검사는 `connector_vault.py` 의 `_vault_values` 다. 필수 칸이 비면 None 이다

## 의도 메모

- 잠금 순서는 보관 파일 잠금이 먼저, 전역 잠금이 나중이다. 갱신은 보관 파일 잠금만 쥐고 서비스를 부른다. 반대로 잡으면 갱신이 서비스를 기다리는 동안 모든 profile 쓰기가 멈춘다
- commit 은 보관 파일을 rename 하지 않고 `_write_vault` 로 다시 쓴다. 같은 값이면 파일이 그대로라 리비전이 바뀌지 않는다
- 해제가 취소를 건너뛰거나 실패해도 refresh token 이 남지 않게 보관 파일 `DELETE` 가 OAuth 파일과 대기 파일까지 지운다

## 작업 항목

### 1. `hermes/plugins/dashboard-profile-api/connector_oauth_store.py` 신규

- 파일 자리: `_vault_dir()` 아래 `<보관 파일 id>.oauth`, `<보관 파일 id>.oauth.pending`, `<보관 파일 id>.pending`(단추 밖 칸의 대기 보관 파일, 보관 파일과 같은 형식)
- OAuth 파일 형식: 보관 파일과 같은 key 로 암호화한 JSON `{provider, refresh_token, granted_scopes, token_url}`, 대기 파일은 여기에 `nonce` 를 더하고 `refresh_token` 이 빌 수 있다. AAD 는 `connector-oauth:<보관 파일 id>:<플러그인 id>` 다. 쓰기는 `connector_vault.py` 의 `_durable_private_write` 를 쓴다
- 함수: `_read_oauth(vault, pending=False)`, `_write_oauth(vault, connector, data, pending=False)`, `_begin(vault, connector, values) -> nonce`(대기 보관 파일과 토큰 없는 대기 OAuth 파일), `_fill_pending(vault, nonce, data)`(nonce 가 다르면 `NonceMismatch`), `_commit(vault, nonce)`(대기 보관 파일 값을 `_write_vault` 로 쓰고 대기 OAuth 파일을 rename), `_discard(vault, nonce)`, `_delete_all(vault)`(보관 파일 빼고 셋을 지움), `_sweep_pending(max_age=600)`
- 보관 파일 id 마다의 잠금: `VAULT_LOCKS: dict[str, asyncio.Lock]` 와 `vault_lock(vault)` 를 둔다

### 2. `hermes/plugins/dashboard-profile-api/connector_vault.py` 수정

- `_vault_values(manifest, values, oauth=False)`: `oauth` 가 참이면 `manifest["oauth"]["fields"]` 의 칸을 필수에서 뺀다
- `PUT` 은 본문에 `oauth`(boolean, 선택)를 받아 위 판정에 넘긴다. `oauth` 가 거짓인 쓰기는 OAuth 파일을 지운다(붙여 넣기로 바꾸기, 스펙 REQ9)
- `DELETE` 는 `_delete_all` 도 부른다
- 기동 때 `_sweep_pending` 을 부른다(기존 기동 이관 `connector_vault_migration.py` 를 부르는 자리 옆)

### 3. `hermes/plugins/dashboard-profile-api/routes.py` 수정

보관 파일 경로(`VAULT_ROUTES`)는 `vault_lock(body 의 vault)` 를 먼저 잡고 `PROFILE_WRITE_LOCK` 을 잡는다. 본문을 두 번 읽지 않도록 `_connector_vault_request` 가 잠금을 받는 모양으로 바꾼다.

### 4. `hermes/tests/test_connector_oauth_store.py` 신규

`hermes/tests/test_connector_vault_encryption.py` 의 `VaultKeyTest` 를 이어받는다.

- begin → fill → commit 뒤 OAuth 파일이 있고 대기 파일 둘이 없다. 같은 칸 값으로 commit 하면 보관 파일의 inode·수정 시각이 그대로다
- 다른 nonce 의 fill 과 commit 은 `NonceMismatch` 이고 본 자리 파일이 그대로다
- 보관 파일 `DELETE` 가 OAuth 파일과 대기 파일을 지운다
- `oauth: true` 인 `PUT` 은 단추 칸이 비어도 받고, `oauth` 없는 `PUT` 은 지금처럼 거절하며 OAuth 파일을 지운다
- 10분 지난 대기 파일만 `_sweep_pending` 이 지운다
- OAuth 파일 원문에 refresh token 평문이 없다(key 를 준 경우)

## 검증

```bash
python3 -m unittest discover -s hermes/tests -p 'test_connector_oauth_store.py'
python3 -m unittest discover -s hermes/tests -p 'test_connector_vault_*.py'
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/dashboard-profile-api/connector_oauth_store.py` | 신규 |
| `hermes/plugins/dashboard-profile-api/connector_vault.py` | 수정 (`oauth` 칸 예외, 붙여 넣기 쓰기와 지우기의 OAuth 파일 정리) |
| `hermes/plugins/dashboard-profile-api/connector_vault_migration.py` | 수정 (기동 때 대기 파일 정리) |
| `hermes/plugins/dashboard-profile-api/routes.py` | 수정 (보관 파일 잠금 순서) |
| `hermes/tests/test_connector_oauth_store.py` | 신규 |
