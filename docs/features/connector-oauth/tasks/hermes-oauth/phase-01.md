# Phase 01. 제공자 표와 manifest 의 `oauth` 선언

**Execution profile**: standard

## 목표

대시보드 plugin 이 `assistant.json` 의 `oauth` 선언을 검사해 카탈로그에 정규화한 값으로 낸다. 틀린 선언은 그 플러그인의 단추만 빼고 입력 칸 연결은 남긴다.

**범위 외**: 교환·갱신·취소 HTTP(phase 03), OAuth 파일(phase 02), 실행기(phase 04), Control Plane 과 웹(뒤 계획).

## 컨텍스트

**요구사항**: REQ1

**근거 문서**: `docs/features/connector-oauth/spec.md`, `docs/features/connector-oauth/design.md` 의 「선언」, `docs/adr/ADR-20261120-connector-oauth.md`

- manifest 검사는 `hermes/plugins/dashboard-profile-api/connector_manifest.py` 의 `_load_connector` 가 한다. 틀리면 `ValueError` 로 플러그인 전체를 카탈로그에서 뺀다
- 틀려도 그 칸만 null 로 내는 본보기는 `icon`, `link` 의 `_appearance_or_none`(같은 파일)이다. `oauth` 도 이 방식으로 다룬다
- 서버 env 합 검사(`set(server["env"]) != field_env | set(operator_env) | owner_env`)에 `oauth.token_env` 를 더해야 한다. env 값은 다른 칸처럼 자기 이름의 참조(`${이름}`)다
- 카탈로그 응답은 `_connector_catalog_response` 가 만든다

## 의도 메모

- 제공자 주소는 플러그인 선언이 아니라 이 파일의 표가 갖는다(ADR-20261120 / connector-oauth). 선언에 주소 칸을 받지 않는다
- `token_env` 가 env 이름으로는 맞는데 `oauth` 의 다른 칸이 틀리면, env 합 검사는 그 이름을 받아 플러그인을 살리고 `oauth` 만 null 로 낸다. 플러그인 전체를 빼면 붙여 넣기 연결까지 끊긴다(스펙 DECISION11)
- 원격 MCP(`type: http`) 플러그인의 `oauth` 는 범위 밖이라 null 과 경고다(스펙 「범위 밖」)

## 작업 항목

### 1. `hermes/plugins/dashboard-profile-api/connector_oauth_providers.py` 신규

제공자 표를 둔다. 표준 라이브러리만 쓴다.

```python
PROVIDERS = {
    "github": {"app_scope": "plugin", "scopes": False, "refresh_rotates": True, ...},
    "google": {"app_scope": "shared", "scopes": True, "refresh_rotates": False, ...},
}
```

- 칸: 앱 줄 단위(`plugin`·`shared`), scope 를 받는지, 로그인 주소(GitHub 은 App 설치 화면 `https://github.com/apps/{slug}/installations/new` 와 허락 화면 `https://github.com/login/oauth/authorize`, Google 은 `https://accounts.google.com/o/oauth2/v2/auth`), 토큰 주소, 취소 방식, 사용자가 허락을 거두는 설정 화면 주소(GitHub `https://github.com/settings/apps/authorizations`, 설치 화면 `https://github.com/settings/installations`, Google `https://myaccount.google.com/permissions`)
- `_normalize_oauth(declared_oauth, fields, remote) -> dict` 를 둔다. 맞으면 `{"provider", "scopes", "fields", "token_env"}`, 틀리면 `ValueError(까닭)` 이다. 규칙은 설계 「선언」 표를 따른다: 모르는 제공자, Google 의 빈 `scopes`, GitHub 의 `scopes`, 빈 `fields`, `fields` 의 key 가 `secret: true` 칸이 아님, `token_env` 가 `ENV_NAME_RE` 가 아니거나 칸·`operator_env`·주인 env 와 겹침, 원격 플러그인

### 2. `hermes/plugins/dashboard-profile-api/connector_manifest.py` 수정

- `_load_connector` 가 `declared.get("oauth")` 를 읽는다. 없으면 `oauth: None`. 있으면 `_normalize_oauth` 를 부르고, `ValueError` 면 경고 로그(`dashboard-profile-api: %s 의 oauth 선언을 쓰지 않는다: %s`)를 남기고 `None` 이다
- env 합 검사에서 `declared["oauth"]["token_env"]` 가 `ENV_NAME_RE` 에 맞는 문자열이면 그 이름을 합에 더한다. 값은 `${이름}` 참조여야 한다
- 정규화한 manifest 에 `"oauth"` 를 둔다
- `_connector_catalog_response` 가 `oauth` 가 있으면 `"oauth": {"provider", "scopes", "fields", "token_env", "app_scope", "settings_url", "install_settings_url"}` 을 응답에 추가한다. 없으면 칸을 추가하지 않는다(옛 Control Plane 호환, rule-66)

### 3. `hermes/connectors/README.md` 수정

「assistant.json」 표에 `oauth` 칸(provider, scopes, fields, token_env)과 뜻을 더한다. 「비밀값과 권한」 에 「단추로 연결하는 플러그인은 `token_env` 의 주소에서 도구를 부를 때마다 토큰을 받는다. 받은 토큰을 파일이나 로그에 쓰지 않는다」 를 더한다. 서버 env 합 문장에 `oauth.token_env` 를 넣는다. 근거로 ADR-20261120 / connector-oauth 를 링크한다.

### 4. `hermes/tests/test_connector_oauth_manifest.py` 신규

`hermes/tests/test_connector_manifest.py` 의 테스트 플러그인 만들기 방식을 따른다.

- 맞는 GitHub 선언과 Google 선언이 카탈로그에 정규화된 `oauth` 로 나온다
- 모르는 제공자, Google 빈 scope, `fields` 에 비밀이 아닌 칸, `token_env` 가 칸 env 와 겹침, 원격 플러그인: 플러그인은 카탈로그에 남고 `oauth` 칸이 없으며 경고 로그가 남는다
- `token_env` 가 서버 env 에 없으면 지금처럼 플러그인이 빠진다
- `oauth` 없는 플러그인의 카탈로그 항목에 `oauth` 칸이 없다

## 검증

```bash
python3 -m unittest discover -s hermes/tests -p 'test_connector_oauth_manifest.py'
python3 -m unittest discover -s hermes/tests -p 'test_connector_manifest.py'
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/dashboard-profile-api/connector_oauth_providers.py` | 신규 |
| `hermes/plugins/dashboard-profile-api/connector_manifest.py` | 수정 (`oauth` 정규화, env 합, 카탈로그) |
| `hermes/connectors/README.md` | 수정 (`oauth` 선언 계약) |
| `hermes/tests/test_connector_oauth_manifest.py` | 신규 |
