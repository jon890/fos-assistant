# Phase 02. 중계의 HTTP 창구와 보안 설정

**Execution profile**: deep

## 목표

`/internal/browser-gateway/<접근 표식>/` 아래에서 CDP 의 HTTP 창구를 주인의 브라우저로 넘기고, 이 경로가 사용자 JWT 없이 표식으로만 판정되게 한다.

**범위 외**: 표식과 주인 찾기(phase 01 의 `BrowserGateway`), WebSocket 중계(phase 03), 커넥터 설치에 주소를 싣는 일(phase 04).

## 컨텍스트

- phase 01 이 `browser.application.BrowserGateway` 를 만들었다. `open(String token)` 이 `GatewayTarget(userId, browserId, cdp)` 를 주거나 `ApiException` 을 던진다. `touch(target)` 은 활동 기록이다
- CDP HTTP 호출은 `backend/src/main/java/com/bifos/assistant/browser/infra/HttpCdpTargets.java` 처럼 JDK `HttpClient` 로, 컨테이너 IP 주소로, `Origin` 없이 부른다. 응답 본문과 주소를 예외 메시지에 싣지 않는다
- `presentation` 은 `infra` 의 `BrowserProperties` 를 쓰지 못한다(`ArchitectureRules.LAYER_DIRECTION`). 중계 주소의 앞부분은 application 이 넘긴다
- 인증 필터: `backend/src/main/java/com/bifos/assistant/shared/auth/ControlPlaneJwtFilter.java` 의 `UNFILTERED_PATHS` 는 정확히 같은 경로만 건너뛴다. 중계는 하위 경로가 많으므로 접두사 검사를 더한다. `backend/src/main/java/com/bifos/assistant/shared/config/SecurityConfig.java` 에 `permitAll` 을 더한다. 보안 체인의 `shared.auth.AgentTokenFilter` 는 정확한 경로만 보므로 손대지 않는다
- 맞는 매핑이 없는 경로는 `NoResourceFoundException` 이 되어 `shared.error.GlobalExceptionHandler` 가 경로를 `log.error` 에 남기고 500 JSON 을 준다. 중계 아래에는 빈 404 를 주는 받기 매핑을 직접 둔다
- 시험 준비: `@WebMvcTest` 와 `@AutoConfigureMockMvc` 는 `ArchitectureRules.TESTS_DO_NOT_SPLIT_CONTEXT` 가 금지한다. 컨트롤러 시험은 `backend/src/test/java/com/bifos/assistant/browser/presentation/UserBrowserControllerTest.java` 처럼 `@BackendIntegrationTest` 와 `MockMvcBuilders` 를 쓰고, 설정은 `@OverrideProperties` 로 넣는다. 실제 HTTP 시험은 `@LocalServerPort` 를 쓰는 `backend/src/test/java/com/bifos/assistant/connector/ConnectorPolicyEndpointTest.java` 를 따른다

**근거 문서**: `docs/backend/user-browser.md` 의 「중계」(「받는 것」), `docs/adr/ADR-20261007-user-browser.md` 의 「중계」, `docs/adr/ADR-20261008-browser-gateway-token.md`

## 의도 메모

- CDP 메서드는 거르지 않는다(ADR). 대신 HTTP 경로와 `json/new` 의 주소 scheme 은 좁게 받는다
- `Origin` 머리가 있는 요청은 403 이다. 브라우저 페이지가 이 경로를 부르는 길을 막는다
- 오류 응답 본문은 비운다. 커넥터는 상태 코드만 본다. 표식이 든 경로는 어떤 로그에도 남기지 않는다

## 작업 항목

### 1. `browser.domain.CdpGatewayHttp`(신규 인터페이스)와 `browser.infra.HttpCdpGateway`(신규 `@Component`)

- `CdpReply get(URI cdp, String path)`, `CdpReply put(URI cdp, String pathAndQuery)`. `browser.domain.CdpReply` 신규 record `(int status, String contentType, byte[] body)`
- JDK `HttpClient`(연결 5초, 요청 10초). 경로는 호출자가 검사한 것만 온다. 응답 본문은 1MB 까지 읽고 넘으면 예외
- 닿지 못하면 런타임 예외. 메시지에 주소와 본문을 싣지 않는다

### 2. 주소 바꾸기

- `BrowserGatewayTokens#relayBase(String token)`(phase 01 의 클래스에 더한다): 중계가 켜져 있으면 `gatewayBaseUrl` 의 scheme 을 `ws`/`wss` 로 바꾸고 `/<token>` 을 붙인 값. 꺼졌으면 빈 값. `BrowserGateway#relayBase(String token)` 이 그대로 넘긴다
- `browser.application.GatewayRewriter`(신규, 상태 없는 `final` 클래스의 static 함수): `JsonNode rewrite(JsonNode target, String relayBase)`. `webSocketDebuggerUrl` 의 경로가 `^/devtools/(page|browser)/[A-Za-z0-9-]{1,128}$` 이면 `<relayBase>/devtools/<종류>/<번호>` 로 바꾸고, 아니면 그 칸을 뺀다. `devtoolsFrontendUrl`, `devtoolsFrontendUrlCompat` 는 뺀다. 브라우저 대상 번호는 GUID 라 `-` 가 든다

### 3. `browser.presentation.BrowserGatewayController`(신규 `@RestController`)

경로와 하는 일은 근거 문서 「받는 것」 의 표를 따른다. 모두 `/internal/browser-gateway/{token}` 아래다.

- `GET json/version`, `GET json`, `GET json/list`, `PUT json/new`, `GET json/close/{id}`, `GET json/activate/{id}`
- 그 밖의 `/internal/browser-gateway/{token}/**` 는 모든 메서드에 빈 404. 이 받기 매핑에는 `headers = "!Upgrade"` 를 단다. `RequestMappingHandlerMapping`(order 0)이 WebSocket 처리기 매핑(order 1)보다 먼저 보므로, 달지 않으면 다음 phase 의 WebSocket handshake 를 이 매핑이 가로챈다
- 처음에 `Origin` 머리가 있으면 403. 그다음 `gateway.open(token)`
- `ApiException` 의 코드를 상태로 바꾼다: `BROWSER_NOT_FOUND` 404, `BROWSER_DISABLED`·`BROWSER_CAPACITY`·`BROWSER_BUSY` 503, `BROWSER_START_FAILED`·`BROWSER_STOP_FAILED` 502. 그 밖의 런타임 예외(Chrome 이 닿지 않음)는 502. 본문은 비운다. 컨트롤러 안의 `try`/`catch` 로 해서 전역 오류 처리기를 타지 않게 한다. 경고 로그에는 예외 종류만 남긴다
- `json/new` 의 쿼리는 `request.getQueryString()` 원문이다. `URLDecoder` 로 푼 주소가 `http://`, `https://` 로 시작하거나 `about:blank` 일 때만 넘기고, 넘길 때는 원문 쿼리를 그대로 붙인다. 아니면 400
- `json/close/{id}`, `json/activate/{id}` 의 `{id}` 는 `^[A-Za-z0-9]+$` 만 받는다(탭 번호). 아니면 404. Chrome 의 글을 그대로 준다
- JSON 응답은 `GatewayRewriter` 로 바꾼다. 목록이면 줄마다
- 요청이 끝나면 `gateway.touch(target)`

### 4. 보안 설정

- `SecurityConfig`: `.requestMatchers("/internal/browser-gateway/**").permitAll()` 과 그 까닭 주석(표식이 인증이다. `BrowserGateway` 가 확인한다)
- `ControlPlaneJwtFilter#shouldNotFilter`: 경로가 `/internal/browser-gateway/` 로 시작하면 건너뛴다. 클래스 Javadoc 의 경로 설명에 더한다

### 5. 시험

- `backend/src/test/java/com/bifos/assistant/browser/application/GatewayRewriterTest.java`: 페이지 주소와 GUID 모양 브라우저 주소(`/devtools/browser/0a1b2c3d-1111-2222-3333-444455556666`)가 바뀐다, 경로 모양이 다르면 칸을 뺀다, `https` 기반은 `wss` 가 된다, `devtoolsFrontendUrl` 이 빠진다
- `backend/src/test/java/com/bifos/assistant/browser/presentation/BrowserGatewayControllerTest.java`: `UserBrowserControllerTest` 169~189줄처럼 `MockMvcBuilders.standaloneSetup(new BrowserGatewayController(mock(BrowserGateway.class), 가짜 CdpGatewayHttp))` 로 만든다. 검사 클래스의 `@MockitoBean` 필드는 금지다(`ArchitectureRules`). `json/version` 이 중계 주소로 바뀐다, `Origin` 이 있으면 403, `BROWSER_NOT_FOUND` 는 빈 본문 404, `BROWSER_CAPACITY` 는 503, `json/new?file:///etc/passwd` 는 400, `json/close/a.b` 는 404, 매핑에 없는 `json/protocol` 은 빈 404, `Upgrade` 머리가 있는 요청은 받기 매핑에 걸리지 않는다(`MvcResult#getHandler()` 가 null 인지로 단언한다)
- `backend/src/test/java/com/bifos/assistant/browser/presentation/BrowserGatewaySecurityTest.java`: `@BackendIntegrationTest` 의 실제 서버에 `@LocalServerPort` 로 JWT 없이 `GET /internal/browser-gateway/b1.<0 64자>/json/version` 을 보내 401 이 아니라 중계의 판정(시험 설정에서 중계가 꺼져 있으면 503)을 받는지 본다

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.browser.*' --tests 'com.bifos.assistant.architecture.*' --tests 'com.bifos.assistant.shared.*'
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck
scripts/check-mysql-migration.sh
```

첫 명령과 마지막 명령이 실패 0 이어야 한다. 마지막 명령은 Docker 가 있어야 돈다. 없으면 그 사실을 보고한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/browser/domain/CdpGatewayHttp.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/domain/CdpReply.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/infra/HttpCdpGateway.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/application/GatewayRewriter.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/application/BrowserGatewayTokens.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/browser/application/BrowserGateway.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/browser/presentation/BrowserGatewayController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/shared/config/SecurityConfig.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/auth/ControlPlaneJwtFilter.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/browser/application/GatewayRewriterTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/browser/presentation/BrowserGatewayControllerTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/browser/presentation/BrowserGatewaySecurityTest.java` | 신규 |
