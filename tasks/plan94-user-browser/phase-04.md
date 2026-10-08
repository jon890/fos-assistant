# Phase 04. `owner_browser_env` 배선

**Execution profile**: deep

## 목표

`connector.json` 이 `owner_browser_env` 를 선언하면, 바인딩 설치는 그 바인딩의 중계 주소를, 확인 도구와 선택지 호출은 요청자의 호출 표식 주소를 그 env 에 넣는다.
승인한 실행은 설치한 서버 정의의 값을 쓴다. 카탈로그와 Control Plane API 는 로그인 안내에 쓸 `owner_browser_login_url` 을 낸다.

**범위 외**: 네이버 블로그 커넥터 자체와 웹 화면(phase 05). 중계 자체(phase 01~03).

## 컨텍스트

- 같은 모양의 선례가 `owner_attachments_env` 와 `owner_output_env` 다. 아래 자리를 그 두 칸이 쓰는 그대로 따라간다
  - manifest 검사와 카탈로그 정의: `hermes/plugins/dashboard-profile-api/connector_manifest.py` 의 `owner_attachments_env`, `owner_output_env` 처리와 `_server_matches`
  - 바인딩 설치: `hermes/plugins/dashboard-profile-api/connector_binding.py` 의 `_connector_bind_config`(인자 `owner_output`)
  - 설치 요청 본문: `hermes/plugins/dashboard-profile-api/connector_install.py` 의 허용 키 집합 `{"profile", "plugin", "enabled", "bind", "sandbox_owner"}` 와 바인딩 설치 분기
  - 확인 도구와 승인 실행: `hermes/plugins/dashboard-profile-api/connector_run.py` 의 `_connector_call_request`(본문 키 집합 검사)와 `_connector_execute_request`, `_installed_owner_attachments`
  - 값 모양 정규식: `OWNER_OUTPUT_VALUE_RE` 를 정의한 곳(`git grep -n "OWNER_OUTPUT_VALUE_RE =" -- hermes/plugins`)
- Control Plane: `backend/src/main/java/com/bifos/assistant/hermes/HermesConnectorClient.java` 와 구현 `HttpHermesConnectorClient.java`(`call`, `callWithVault`, `bindConnector`, 카탈로그 읽기 `optionalBoolean(item, "attachments", false)` 근처), DTO `backend/src/main/java/com/bifos/assistant/hermes/dto/ConnectorManifest.java`
- 부르는 쪽: `ConnectorBindingService`(붙이기에서 `bindConnector`), `ConnectorBindingInstalls`(다시 설치), `ConnectorConnectionService`(`call`, `callWithVault`). `git grep -n "bindConnector(\|callWithVault(\|\.call(" -- backend/src/main` 으로 모두 찾는다
- 주소는 phase 01 의 `com.bifos.assistant.browser.application.BrowserGatewayTokens` 가 만든다. `bindingAddress(long bindingId)`, `callAddress(long userId)` 가 `Optional<String>` 이고 중계가 꺼졌으면 빈 값이다. `connector` 는 `browser` 보다 위층이라 import 해도 된다
- 시험의 가짜 Hermes(`test/e2e/fake-hermes/connector-routes.ts`)는 본문 키를 엄격히 보지 않는다. Control Plane 은 manifest 가 `owner_browser_env` 를 선언했을 때만 새 키를 보내므로 다른 커넥터의 요청은 바뀌지 않는다

**근거 문서**: `docs/backend/user-browser.md` 의 「중계」, `docs/adr/ADR-20261008-browser-gateway-token.md`, `docs/connectors.md` 의 「connector.json」 표, `docs/backend/connector-install.md` 의 「바인딩 설치」

## 의도 메모

- 중계 주소가 없으면(중계가 꺼졌다) 설치를 막지 않고 빈 값을 넣는다. 커넥터가 브라우저에 닿지 못한다고 답하고 연결 확인이 실패로 보인다. 첨부 디렉터리처럼 409 로 막으면 중계 설정 전 배포에서 붙이기가 모두 깨진다
- 바인딩 표식은 같은 바인딩에 늘 같아 다시 설치가 서버 정의를 바꾸지 않는다. 시험으로 이것을 확인한다
- 보관 파일을 **읽는** 두 경로(확인 도구 `call` 의 `vault`, 바인딩 설치)는 지금 manifest 에 없는 칸을 버린다. 칸을 뺀 커넥터의 옛 연결이 연결 확인에서 막히지 않게 한다. 연결 확인은 `ConnectorConnectionService` 의 `resync` 가 `ConnectorBindingService` 의 다시 설치(`ConnectorBindingInstalls#sendAgain`)를 부르므로 두 경로를 모두 지난다. 후보 값(`values`)과 보관 파일을 **쓰는** 검사(`connector_vault.py` 의 `_vault_values` 를 `PUT` 보관에 쓰는 자리)는 지금처럼 모르는 칸을 거절한다
- 주소 값은 `^https?://[A-Za-z0-9.-]{1,253}(:[0-9]{1,5})?(/[A-Za-z0-9._~-]{1,128}){1,8}$` 모양만 받는다. 아니면 빈 값이다

## 작업 항목

### 1. 대시보드 plugin

- `connector_manifest.py`
  - `owner_browser_env`: 선택. `OWNER_ATTACHMENTS_ENV_RE` 에 맞고 `BASE_ENV_KEYS`, 칸 env, `operator_env`, `owner_attachments_env`, `owner_output_env` 와 겹치지 않는다. `owner_env` 집합에 더해 서버 env 합 검사에 든다. 오류 문구에 새 이름을 더한다
  - `owner_browser_login_url`: 선택. `owner_browser_env` 가 있을 때만 받는다. `https://` 로 시작하고 512자 이하이며 빈칸과 제어 문자가 없다
  - 반환 사전에 `"owner_browser_env"`, `"owner_browser_login_url"`
  - `_server_matches`: `owner_browser_env` 의 값은 `""` 이거나 위 정규식(`OWNER_BROWSER_VALUE_RE`, 값 모양 정규식 옆에 신규)에 맞으면 같다고 본다
  - 카탈로그 응답(`_connector_catalog_response`)의 항목에 `owner_browser`(boolean)와 `owner_browser_login_url`(문자열이나 null)을 더한다. env 이름은 내지 않는다
- `connector_install.py`: 바인딩 설치가 보관 파일 값을 `_vault_values` 로 검사하는 자리(219줄 근처)에서, 넘기기 전에 지금 manifest 의 `fields` 에 없는 키를 버린다. 남은 값은 지금처럼 검사한다. 허용 키에 `owner_browser` 를 더한다. 문자열이 아니거나 `OWNER_BROWSER_VALUE_RE` 에 맞지 않으면 400. 바인딩 설치 분기에서 `_connector_bind_config(..., owner_browser=값)` 으로 넘긴다. 옛 설치(`bind` 없음)는 그 env 를 빈 값으로 둔다
- `connector_binding.py`: `_connector_bind_config` 에 인자 `owner_browser: str | None = None`. manifest 가 선언했으면 `server["env"][이름]` 에 값이나 빈 값을 넣는다(`owner_output` 과 같은 자리). docstring 에 한 줄
- `connector_run.py`
  - `_connector_call_request`: 본문 키 집합에 `owner_browser` 를 선택으로 받는다(`{"tool","values"}`, `{"tool","vault"}` 각각에 `owner_browser` 가 더해진 것). 선언한 커넥터면 그 값을(모양이 틀리면 빈 값) env 에 넣고, 선언하지 않았으면 무시한다
  - 같은 함수의 `vault` 경로: 보관 파일의 키 가운데 `fields` 에 없는 것은 버린다(`values` 경로는 지금처럼 `invalid_input`)
  - `_connector_execute_request`: `_installed_owner_browser(profile_dir, manifest)`(신규, `_installed_owner_attachments` 와 같은 방식으로 설치한 서버 정의의 값을 읽고 모양이 틀리면 빈 값)를 env 에 넣는다
- 시험: `hermes/tests/test_connector_manifest.py`(선언 받기, 겹침 거절, login_url 이 `http://` 면 거절, env 없이 login_url 만 있으면 거절), `hermes/tests/test_dashboard_profile_api_connector_binding_install.py`(설치한 서버 정의에 주소가 들어간다, 같은 주소로 다시 설치하면 `restart_required` 가 거짓, 주소가 없으면 빈 값, 지금 manifest 에 없는 칸이 남은 보관 파일로도 설치된다), `hermes/tests/test_connector_call.py`(호출 env 에 주소가 들어간다, 보관 파일의 모르는 칸은 버린다), `hermes/tests/test_connector_execute.py`(설치한 값이 실행 env 로 간다). 시험 지원 `hermes/tests/dashboard_profile_api_support.py` 의 `owner_attachments_env` 를 더하는 도우미(489줄 근처)를 따라 `owner_browser_env` 도우미를 더하고, 가짜 커넥터 `hermes/tests/fixtures/demo-connector/server.py` 가 `DEMO_BROWSER_URL` 을 돌려주게 한다

### 2. Control Plane

- `ConnectorManifest`: 칸 `boolean ownerBrowser`, `String ownerBrowserLoginUrl` 을 끝에 더한다. 기존 보조 생성자들은 거짓과 null 로 넘긴다
- `HttpHermesConnectorClient` 카탈로그 읽기: `optionalBoolean(item, "owner_browser", false)` 와 `owner_browser_login_url`(문자열이고 `https://` 로 시작할 때만, 아니면 null)
- `HermesConnectorClient`:
  - `CallResult call(String connectorId, String tool, Map<String, String> values, String ownerBrowser)`, `CallResult callWithVault(String connectorId, String tool, String vault, String ownerBrowser)`. `ownerBrowser` 가 null 이면 본문에 키를 싣지 않는다
  - `InstallResult bindConnector(String profile, String connectorId, String vault, String sandboxOwner, String ownerBrowser)`. null 이면 싣지 않는다
  - 기존 시그니처는 지운다. 호출부와 시험의 가짜 구현(`git grep -n "implements HermesConnectorClient\|HermesConnectorClient()" -- backend/src`)을 함께 고친다
- 부르는 쪽: manifest 가 `ownerBrowser()` 일 때만 주소를 만든다
  - 붙이기와 다시 설치: `tokens.bindingAddress(binding.id()).orElse("")`. `ConnectorBindingInstalls#sendAgain(ConnectorBinding, String, boolean)` 은 manifest 를 받지 않으므로 `ConnectorManifest` 를 받게 바꾸고 `ConnectorBindingService` 의 호출부(`resync` 안, 482줄 근처)가 `declared` 를 넘긴다
  - 연결 등록, 연결 확인, 선택지: `tokens.callAddress(user.id())` 나 그 연결의 `userId()` 로 `.orElse("")`
- API: `GET /api/v1/connectors` 항목에 `ownerBrowserLoginUrl`(문자열이나 null). 응답 DTO 는 `connector/presentation/ConnectionDtos.java` 다
- 시험 준비: 주소가 나오려면 중계가 켜져 있어야 한다. 바인딩 서비스 시험은 `@OverrideProperties` 로 `assistant.browser.gateway-base-url`(`http://cp.example.test/internal/browser-gateway`)과 `assistant.browser.gateway-secret`(32자 이상)을 넣는다. `AgentLifecycleServiceTest`, `AgentVisibilityLockTest` 처럼 `bindConnector` 를 stub 하는 시험(`git grep -ln "bindConnector" -- backend/src/test`)은 새 인자를 `any()` 나 `nullable(String.class)` 로 맞춘다
- 시험: `backend/src/test/java/com/bifos/assistant/hermes/HttpHermesConnectorClientTest.java`(본문에 `owner_browser` 가 실리고 null 이면 키가 없다, 카탈로그의 두 칸을 읽는다), 바인딩 서비스 시험(`git grep -ln "bindConnector" -- backend/src/test` 로 찾은 파일)에 선언한 커넥터는 바인딩 주소를, 선언하지 않은 커넥터는 null 을 넘기는지 하나씩

### 3. 문서

- `docs/connectors.md` 의 「connector.json」 표에 `owner_browser_env` 와 `owner_browser_login_url` 줄을 더하고, 서버 env 합 규칙 문장에 이름을 더한다. API 표의 `GET /api/v1/connectors` 결과에 `ownerBrowserLoginUrl` 을 더한다. 「승인 줄과 경로」 의 execute 자식 env 설명(283줄 근처)에 설치한 서버 정의의 중계 주소를 더한다
- `docs/connector-authoring.md` 의 owner env 설명(77줄, 92줄 근처)에 `owner_browser_env` 를 더한다
- `docs/backend/connector-install.md` 의 「바인딩 설치」(73줄 근처)에 `owner_browser` 를 싣는 규칙과 다시 설치가 같은 값을 쓴다는 문장
- `hermes/README.md` 의 `PUT /api/connectors` 와 call, execute 설명(330, 440, 450줄 근처)에 `owner_browser`. call 의 자식 env 설명(440줄)에 호출 표식 주소와 보관 파일의 모르는 칸을 버린다는 문장
- `docs/backend/user-browser.md` 의 「중계」 끝에 「커넥터에 건네기」 소절: 바인딩 설치는 바인딩 표식, 확인 도구와 선택지는 호출 표식 주소를 넣고 위 두 문서를 가리킨다

## 검증

```bash
python3 -m unittest discover -s hermes/tests -p 'test_connector_*.py'
python3 -m unittest discover -s hermes/tests -p 'test_dashboard_profile_api_connector_*.py'
cd backend && ./gradlew test --tests 'com.bifos.assistant.connector.*' --tests 'com.bifos.assistant.hermes.*' --tests 'com.bifos.assistant.agent.*' --tests 'com.bifos.assistant.architecture.*'
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck
```

`hermes/tests` 의 실행 방법이 다르면 `scripts/check-local.sh` 의 hermes 단계가 쓰는 명령을 따른다. 모두 실패 0 이어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/dashboard-profile-api/connector_manifest.py` | 수정 |
| `hermes/plugins/dashboard-profile-api/connector_install.py` | 수정 |
| `hermes/plugins/dashboard-profile-api/connector_binding.py` | 수정 |
| `hermes/plugins/dashboard-profile-api/connector_run.py` | 수정 |
| `hermes/plugins/dashboard-profile-api/*.py` | 수정 |
| `hermes/tests/test_connector_manifest.py` | 수정 |
| `hermes/tests/test_dashboard_profile_api_connector_binding_install.py` | 수정 |
| `hermes/tests/test_connector_call.py` | 수정 |
| `hermes/tests/test_connector_execute.py` | 수정 |
| `hermes/tests/dashboard_profile_api_support.py` | 수정 |
| `hermes/tests/fixtures/demo-connector/server.py` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/dto/ConnectorManifest.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesConnectorClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HttpHermesConnectorClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/*.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/presentation/ConnectionDtos.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/HttpHermesConnectorClientTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/**/*.java` | 수정 |
| `docs/connectors.md` | 수정 |
| `docs/connector-authoring.md` | 수정 |
| `docs/backend/connector-install.md` | 수정 |
| `docs/backend/user-browser.md` | 수정 |
| `hermes/README.md` | 수정 |
