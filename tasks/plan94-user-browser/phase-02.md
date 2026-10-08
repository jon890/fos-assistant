# Phase 02. 중계의 HTTP 와 WebSocket

**Execution profile**: deep

## 목표

`/internal/browser-gateway/<접근 표식>/` 아래에서 CDP 의 HTTP 창구와 WebSocket 을 주인의 브라우저로 넘긴다.
커넥터가 이 주소를 Chrome 의 CDP 주소처럼 부를 수 있게 한다.

**범위 외**: 표식과 주인 찾기(phase 01 의 `BrowserGateway`), 커넥터 설치에 주소를 싣는 일(phase 03).

## 컨텍스트

- phase 01 이 `browser.application.BrowserGateway` 를 만들었다. `open(String token)` 이 `GatewayTarget(userId, browserId, cdp)` 를 주거나 `ApiException` 을 던진다. `hold(target)` 은 자동 중지를 막는 핸들, `touch(target)` 은 활동 기록이다
- CDP HTTP 호출은 `backend/src/main/java/com/bifos/assistant/browser/infra/HttpCdpTargets.java` 처럼 JDK `HttpClient` 로, 컨테이너 IP 주소로, `Origin` 없이 부른다. 응답 본문과 주소를 예외 메시지에 싣지 않는다
- Chrome 쪽 WebSocket 은 `backend/src/main/java/com/bifos/assistant/browser/infra/WebSocketCdpConnector.java` 처럼 JDK `java.net.http.WebSocket` 으로 연다. 조각난 메시지를 모으는 방법과 4M 글자 상한을 그 파일에서 따른다
- 인증 필터: `backend/src/main/java/com/bifos/assistant/shared/auth/ControlPlaneJwtFilter.java` 의 `UNFILTERED_PATHS` 는 정확히 같은 경로만 건너뛴다. 중계는 하위 경로가 많으므로 접두사 검사를 더한다. `backend/src/main/java/com/bifos/assistant/shared/config/SecurityConfig.java` 에 `permitAll` 을 더한다
- `AgentTokenAuthenticationFilter` 는 `/internal/hermes/*` 정확한 경로만 받으므로 손대지 않는다
- 웹의 서버 라우트(`web/src/app/api/`)는 이 경로를 넘기지 않는다. 손대지 않는다
- 의존성은 `backend/gradle/libs.versions.toml` 의 `spring-boot-starters` 묶음이다. 서버 WebSocket 에 `spring-boot-starter-websocket` 을 더한다(버전은 Boot BOM 이 정한다)

**근거 문서**: `docs/backend/user-browser.md` 의 「중계」(「받는 것」, 「WebSocket」), `docs/adr/ADR-20261007-user-browser.md` 의 「중계」, `docs/adr/ADR-20261008-browser-gateway-token.md`

## 의도 메모

- CDP 메서드는 거르지 않는다(ADR). 커넥터는 검토한 코드다. 대신 HTTP 경로와 `json/new` 의 주소 scheme 은 좁게 받는다
- `Origin` 머리가 있는 요청은 HTTP 와 WebSocket 모두 403 이다. 브라우저 페이지가 이 경로를 부르는 길을 막는다. Spring WebSocket 의 기본 Origin 검사에 기대지 않고 직접 본다
- 오류 응답 본문은 비운다. 커넥터는 상태 코드만 본다
- 표식은 열 때만 본다. 열린 WebSocket 은 닫힐 때까지 간다

## 작업 항목

### 1. 의존성

`backend/gradle/libs.versions.toml` 에 `spring-boot-starter-websocket = { module = "org.springframework.boot:spring-boot-starter-websocket" }` 를 더하고 `spring-boot-starters` 묶음에 넣는다.

### 2. `browser.infra.HttpCdpGateway`(신규 `@Component`)와 `browser.domain.CdpGatewayHttp`(신규 인터페이스)

- `CdpGatewayHttp`: `CdpReply get(URI cdp, String path)`, `CdpReply put(URI cdp, String pathAndQuery)`. `browser.domain.CdpReply` 신규 record `(int status, String contentType, byte[] body)`
- 구현은 JDK `HttpClient`(연결 5초, 요청 10초). 경로는 호출자가 검사한 것만 온다. 응답 본문은 1MB 까지 읽고 넘으면 예외
- 닿지 못하면 런타임 예외. 메시지에 주소와 본문을 싣지 않는다

### 3. `browser.presentation.BrowserGatewayController`(신규 `@RestController`)

경로와 하는 일은 근거 문서 「받는 것」 의 표를 그대로 따른다. 모두 `/internal/browser-gateway/{token}` 아래다.

- `GET json/version`, `GET json`, `GET json/list`, `PUT json/new`, `GET json/close/{id}`, `GET json/activate/{id}`
- 처음에 `Origin` 머리가 있으면 403. 그다음 `gateway.open(token)`
- `ApiException` 의 코드를 상태로 바꾼다: `BROWSER_NOT_FOUND` 404, `BROWSER_DISABLED`·`BROWSER_CAPACITY`·`BROWSER_BUSY` 503, `BROWSER_START_FAILED`·`BROWSER_STOP_FAILED` 502. 그 밖의 런타임 예외(Chrome 이 닿지 않음)는 502. 본문은 비운다. 이 변환은 컨트롤러 안의 `try`/`catch` 로 해서 전역 오류 처리기의 JSON 본문을 타지 않게 한다
- `json/new` 의 쿼리는 `request.getQueryString()` 원문이다. `URLDecoder` 로 푼 주소가 `http://`, `https://` 로 시작하거나 `about:blank` 일 때만 넘기고, 넘길 때는 원문 쿼리를 그대로 붙인다. 아니면 400
- `{id}` 는 `^[A-Za-z0-9]+$` 만 받는다. 아니면 404
- JSON 응답의 `webSocketDebuggerUrl` 을 `ws(s)://<gateway-base-url 의 host:port><base 경로>/<token>/devtools/<종류>/<번호>` 로 바꾼다. 종류와 번호는 Chrome 이 준 주소의 경로(`/devtools/page/<번호>` 나 `/devtools/browser/<번호>`)에서 꺼낸다. 모양이 다르면 그 칸을 뺀다. `devtoolsFrontendUrl` 과 `devtoolsFrontendUrlCompat` 는 뺀다. 이 변환은 `browser.application.GatewayRewriter`(신규, 순수 함수)에 둔다
- 요청이 끝나면 `gateway.touch(target)`
- `json/close` 와 `json/activate` 는 Chrome 의 글을 그대로 준다

### 4. WebSocket 중계

- `browser.presentation.BrowserGatewaySocketConfig`(신규 `@Configuration @EnableWebSocket`, `WebSocketConfigurer`): `registry.addHandler(handler, "/internal/browser-gateway/*/devtools/*/*").addInterceptors(interceptor).setAllowedOriginPatterns("*")`. Origin 검사는 interceptor 가 직접 한다. `ServletServerContainerFactoryBean` 의 `maxTextMessageBufferSize` 를 16MiB 로 둔다
- `browser.presentation.BrowserGatewayHandshake`(신규 `HandshakeInterceptor`): `Origin` 이 있으면 403 으로 거절, 경로에서 표식과 종류(`page`, `browser`)와 번호(`^[A-Za-z0-9]+$`)를 꺼내 `gateway.open` 을 부르고 실패면 위 표의 상태로 거절. 통과하면 `GatewayTarget` 과 종류와 번호를 세션 속성에 둔다
- `browser.presentation.BrowserGatewaySocket`(신규 `TextWebSocketHandler`):
  - `afterConnectionEstablished`: `gateway.hold(target)` 핸들을 세션 속성에 두고, `browser.domain.CdpRelayConnector`(신규 인터페이스)의 `CdpRelay open(URI cdp, String kind, String id, Consumer<String> fromChrome, Runnable closed)` 로 Chrome 에 붙는다. 붙지 못하면 세션을 `CloseStatus.SERVER_ERROR` 로 닫고 핸들을 닫는다
  - `handleTextMessage`: 4M 글자를 넘으면 양쪽을 닫는다. 아니면 `relay.send(text)` 하고 `gateway.touch(target)`
  - Chrome 에서 온 글은 세션별 잠금으로 하나씩 `session.sendMessage` 한다. 실패하면 양쪽을 닫는다
  - `afterConnectionClosed` 와 `handleTransportError`: relay 를 닫고 핸들을 닫는다. 두 번 닫혀도 안전하게
  - 바이너리 메시지는 받지 않는다(`supportsPartialMessages` 거짓, 바이너리는 닫기)
- `browser.infra.WebSocketCdpRelayConnector`(신규 `@Component`): `ws://<cdp host:port>/devtools/<kind>/<id>` 로 JDK WebSocket 을 열고(`Origin` 없음, 연결 10초), 조각을 모아 완성된 글만 `fromChrome` 에 넘긴다. 4M 글자를 넘으면 닫는다. 상대가 닫거나 오류면 `closed` 를 한 번 부른다. `CdpRelay` 는 `send(String)` 과 `close()` 를 갖는 신규 인터페이스(`browser.domain.CdpRelay`)다

### 5. 보안 설정

- `SecurityConfig`: `.requestMatchers("/internal/browser-gateway/**").permitAll()` 과 그 까닭 주석(표식이 인증이다. `BrowserGateway` 가 확인한다)
- `ControlPlaneJwtFilter#shouldNotFilter`: 경로가 `/internal/browser-gateway/` 로 시작하면 건너뛴다. 클래스 Javadoc 의 경로 목록에 더한다

### 6. 시험

- `backend/src/test/java/com/bifos/assistant/browser/presentation/BrowserGatewayControllerTest.java`: `@WebMvcTest` 나 이 패키지의 `UserBrowserControllerTest` 와 같은 준비로, `BrowserGateway` 와 `CdpGatewayHttp` 를 가짜로 둔다. `json/version` 의 `webSocketDebuggerUrl` 이 중계 주소로 바뀐다, `json/list` 에서 `devtoolsFrontendUrl` 이 빠진다, `Origin` 이 있으면 403, `BROWSER_NOT_FOUND` 는 빈 본문 404, `BROWSER_CAPACITY` 는 503, `json/new?file:///etc/passwd` 는 400, `json/close/a.b` 는 404
- `backend/src/test/java/com/bifos/assistant/browser/application/GatewayRewriterTest.java`: 경로가 `/devtools/page/<번호>` 가 아니면 칸을 뺀다, `https` 기반 주소는 `wss` 가 된다
- `backend/src/test/java/com/bifos/assistant/browser/presentation/BrowserGatewaySocketTest.java`: 처리기를 직접 부르는 단위 시험이다. 가짜 `CdpRelayConnector` 로 받은 글이 relay 로 가고 relay 의 글이 세션으로 오는지, 세션이 닫히면 relay 와 `BrowserUsage` 핸들이 닫히는지, 4M 글자를 넘는 글이면 양쪽이 닫히는지 본다
- `backend/src/test/java/com/bifos/assistant/browser/infra/WebSocketCdpRelayConnectorTest.java`: `WebSocketCdpConnectorTest` 의 `ServerSocket` 가짜 WebSocket 서버를 따라, 글이 양쪽으로 가고 서버가 닫으면 `closed` 가 한 번 불리는지 본다
- `backend/src/test/java/com/bifos/assistant/browser/presentation/BrowserGatewaySecurityTest.java`: `@SpringBootTest` 에 `@AutoConfigureMockMvc` 로, JWT 없이 `GET /internal/browser-gateway/b1.<64자 0>/json/version` 이 401 이 아니라 중계의 판정(중계가 꺼진 시험 설정이면 503)을 받는지 본다. 이 저장소의 기존 `@SpringBootTest` 시험이 쓰는 profile 과 준비를 따른다

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.browser.*' --tests 'com.bifos.assistant.architecture.*' --tests 'com.bifos.assistant.shared.*'
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck
```

첫 명령이 실패 0 이어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/gradle/libs.versions.toml` | 수정 |
| `backend/src/main/java/com/bifos/assistant/browser/domain/CdpGatewayHttp.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/domain/CdpReply.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/domain/CdpRelay.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/domain/CdpRelayConnector.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/infra/HttpCdpGateway.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/infra/WebSocketCdpRelayConnector.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/application/GatewayRewriter.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/presentation/BrowserGatewayController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/presentation/BrowserGatewaySocketConfig.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/presentation/BrowserGatewayHandshake.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/presentation/BrowserGatewaySocket.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/shared/config/SecurityConfig.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/auth/ControlPlaneJwtFilter.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/browser/presentation/BrowserGatewayControllerTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/browser/application/GatewayRewriterTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/browser/presentation/BrowserGatewaySocketTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/browser/infra/WebSocketCdpRelayConnectorTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/browser/presentation/BrowserGatewaySecurityTest.java` | 신규 |
