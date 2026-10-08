# Phase 03. 중계의 WebSocket

**Execution profile**: deep

## 목표

`/internal/browser-gateway/<접근 표식>/devtools/<page|browser>/<번호>` 의 WebSocket 을 주인의 브라우저 같은 경로에 잇는다.
메시지는 조각째 그대로 넘겨, 사진 바이트가 든 큰 CDP 메시지도 버퍼 상한에 걸리지 않게 한다.

**범위 외**: HTTP 창구(phase 02), 커넥터 설치에 주소를 싣는 일(phase 04).

## 컨텍스트

- phase 01 의 `browser.application.BrowserGateway`: `open(String token)` 이 `GatewayTarget(userId, browserId, cdp)` 를 주거나 `ApiException` 을 던진다. `hold(target)` 은 자동 중지를 막는 `BrowserUsageHandle`, `touch(target)` 은 활동 기록이다
- phase 02 의 `BrowserGatewayController` 가 `ApiException` 코드를 상태로 바꾸는 표(404, 503, 502)를 handshake 도 같게 쓴다. 표는 그 컨트롤러의 private 함수에 있으면 `browser.presentation` 안의 패키지 범위 클래스로 옮겨 함께 쓴다
- Chrome 쪽 WebSocket 은 `backend/src/main/java/com/bifos/assistant/browser/infra/WebSocketCdpConnector.java` 처럼 JDK `java.net.http.WebSocket` 으로 연다(연결 10초, `Origin` 없음). 시험의 가짜 WebSocket 서버는 `backend/src/test/java/com/bifos/assistant/browser/infra/WebSocketCdpConnectorTest.java` 의 `ServerSocket` 방식을 따른다
- 네이버 블로그 커넥터는 사진 한 장(최대 20MiB)을 base64 로 바꿔 CDP 메시지 하나에 싣는다(`hermes/connectors/naver-blog/src/editor/photos.ts`). 메시지 하나가 수천만 글자가 될 수 있다. 메시지를 모아서 넘기면 세션마다 그만한 버퍼가 필요하다
- Spring WebSocket 처리기가 `supportsPartialMessages()` 를 참으로 내면 Tomcat 은 기본 버퍼(8192 글자)가 찰 때마다 조각을 넘긴다. Spring 의 `TextMessage(CharSequence, boolean isLast)` 와 JDK 의 `WebSocket#sendText(CharSequence, boolean last)`, `Listener#onText(..., boolean last)` 가 조각을 그대로 주고받는다
- `ServletServerContainerFactoryBean` 은 쓰지 않는다. `ServerContainer` 가 없는 MOCK 웹 환경의 `@SpringBootTest`(MySQL 검사)가 기동에서 실패한다
- 의존성은 `backend/gradle/libs.versions.toml` 의 `spring-boot-starters` 묶음이다. 서버 WebSocket 에 `spring-boot-starter-websocket` 을 더한다(버전은 Boot BOM 이 정한다)
- phase 02 의 컨트롤러가 `/internal/browser-gateway/{token}/**` 에 빈 404 받기 매핑을 두되 `headers = "!Upgrade"` 로 upgrade 요청은 받지 않는다. 그래서 handshake 는 WebSocket 처리기 매핑으로 간다. 이 phase 의 통합 시험이 그것을 확인한다
- 공유 시험 컨텍스트의 `backend/src/test/java/com/bifos/assistant/testsupport/FakeBrowserRuntime.java` 는 닿지 않는 고정 CDP 주소를 준다. 그래서 통합 시험은 진짜 `WebSocketCdpRelayConnector` 대신 `backend/src/test/java/com/bifos/assistant/testsupport/IntegrationTestDoubles.java` 에 더하는 `@Primary` 가짜 `CdpRelayConnector` 를 쓴다. 검사 클래스의 `@MockitoBean` 은 `ArchitectureRules.TESTS_DO_NOT_SPLIT_CONTEXT` 가 금지한다
- 시험 준비는 `@BackendIntegrationTest` 와 `@OverrideProperties` 를 쓴다. `@WebMvcTest`, `@AutoConfigureMockMvc` 는 금지다(`ArchitectureRules.TESTS_DO_NOT_SPLIT_CONTEXT`). 실제 서버 포트는 `@LocalServerPort`(선례 `backend/src/test/java/com/bifos/assistant/connector/ConnectorPolicyEndpointTest.java`)

**근거 문서**: `docs/backend/user-browser.md` 의 「중계」(「WebSocket」), `docs/adr/ADR-20261007-user-browser.md` 의 「중계」

## 의도 메모

- 조각째 잇기로 정했다. 메시지 상한을 크게 올리면 세션마다 수십 MB 를 잡는다. 커넥터가 사진을 쪼개 보내게 바꾸는 안은 중계가 CDP 를 그대로 잇는다는 계약을 커넥터마다 지키게 만든다
- 한 메시지(조각의 합)가 64M 글자를 넘으면 양쪽을 닫는다. 끝없는 메시지를 막는 상한이다
- `Origin` 은 interceptor 가 직접 보고 403 이다. Spring 의 Origin 검사는 `setAllowedOriginPatterns("*")` 로 끄고 그 자리를 interceptor 가 갖는다
- 한쪽으로 가는 조각은 앞 조각의 보내기가 끝난 뒤에 보낸다. 순서가 섞이면 CDP 메시지가 깨진다

## 작업 항목

### 1. 의존성

`backend/gradle/libs.versions.toml` 에 `spring-boot-starter-websocket = { module = "org.springframework.boot:spring-boot-starter-websocket" }` 를 더하고 `spring-boot-starters` 묶음에 넣는다.

### 2. Chrome 쪽 연결

- `browser.domain.CdpRelay`(신규 인터페이스): `void send(String fragment, boolean last)`(앞 보내기가 끝날 때까지 기다린 뒤 보낸다. 실패하면 런타임 예외), `void close()`
- `browser.domain.CdpRelayConnector`(신규 인터페이스): `CdpRelay open(URI cdp, String kind, String id, CdpRelayListener listener)`. `browser.domain.CdpRelayListener`(신규 인터페이스): `void onFragment(String fragment, boolean last)`, `void onClosed()`
- `browser.infra.WebSocketCdpRelayConnector`(신규 `@Component`): `ws://<cdp host:port>/devtools/<kind>/<id>` 로 JDK WebSocket 을 연다. `kind` 는 `page` 나 `browser`, `id` 는 `^[A-Za-z0-9-]{1,128}$` 가 아니면 `IllegalArgumentException`. 받은 조각은 `onFragment` 를 부른 뒤 `request(1)` 한다. 상대가 닫거나 오류면 `onClosed` 를 한 번 부른다

### 3. 받는 쪽

- `browser.presentation.BrowserGatewaySocketConfig`(신규 `@Configuration @EnableWebSocket`, `WebSocketConfigurer`): `registry.addHandler(handler, "/internal/browser-gateway/*/devtools/*/*").addInterceptors(handshake).setAllowedOriginPatterns("*")`
- `browser.presentation.BrowserGatewayHandshake`(신규 `HandshakeInterceptor`): `Origin` 이 있으면 403. 경로에서 표식, 종류(`page`, `browser`), 번호(`^[A-Za-z0-9-]{1,128}$`)를 꺼낸다. 모양이 틀리면 404. `gateway.open(token)` 이 던지면 phase 02 의 표로 상태를 정한다. 통과하면 `GatewayTarget` 과 종류와 번호를 세션 속성에 둔다. 응답 본문은 비운다
- `browser.presentation.BrowserGatewaySocket`(신규 `AbstractWebSocketHandler`):
  - `supportsPartialMessages()` 참
  - `afterConnectionEstablished`: `gateway.hold(target)` 핸들을 쥐고 `CdpRelayConnector#open` 으로 Chrome 에 붙는다. Chrome 에서 온 조각은 세션별 잠금 안에서 `session.sendMessage(new TextMessage(fragment, last))` 로 보낸다. 붙지 못하면 세션을 `CloseStatus.SERVER_ERROR` 로 닫고 핸들을 닫는다
  - `handleTextMessage`: 조각 길이를 메시지마다 더해 64M 글자를 넘으면 양쪽을 닫는다. 아니면 `relay.send(payload, message.isLast())`. 마지막 조각이면 `gateway.touch(target)`
  - 바이너리 메시지는 받으면 닫는다
  - `afterConnectionClosed`, `handleTransportError`, relay 의 `onClosed`: relay 와 세션과 핸들을 닫는다. 두 번 닫혀도 안전하게
- 표식과 경로는 로그에 싣지 않는다. 닫는 까닭은 종류만 남긴다

### 4. 시험

- `backend/src/test/java/com/bifos/assistant/browser/infra/WebSocketCdpRelayConnectorTest.java`: 가짜 WebSocket 서버로 조각 둘로 보낸 메시지가 순서대로 `onFragment` 에 오고 마지막 조각만 `last` 다, 서버가 닫으면 `onClosed` 가 한 번, GUID 모양 번호를 받는다, 번호에 `/` 가 있으면 `IllegalArgumentException`
- `backend/src/test/java/com/bifos/assistant/browser/presentation/BrowserGatewaySocketTest.java`: 처리기를 직접 부른다. 가짜 relay 로 조각이 그대로 오가는지, 세션이 닫히면 relay 와 핸들이 닫히는지, 조각의 합이 상한을 넘으면 양쪽이 닫히는지
- `backend/src/test/java/com/bifos/assistant/testsupport/IntegrationTestDoubles.java`: `@Bean @Primary` 로 `EchoCdpRelayConnector`(신규 시험 지원 클래스 `backend/src/test/java/com/bifos/assistant/testsupport/EchoCdpRelayConnector.java`)를 더한다. 받은 조각을 그대로 `onFragment` 로 돌려주고, 연 횟수와 받은 조각을 기록하며, 시험이 기록을 비울 수 있다. 공유 컨텍스트라 다른 시험에 영향이 없게 상태는 시험마다 비운다
- `backend/src/test/java/com/bifos/assistant/browser/presentation/BrowserGatewaySocketIntegrationTest.java`: `@BackendIntegrationTest` 의 실제 서버(`@LocalServerPort`)에서 `@OverrideProperties` 로 중계를 켠다(`assistant.browser.enabled=true` 와 그때 필요한 값, 기반 주소, 32자 이상 비밀). 허용된 사용자를 만드는 방법은 `backend/src/test/java/com/bifos/assistant/browser/application/UserBrowserAccessRevokedTest.java` 를 따른다. 표식은 그 사용자의 호출 표식(`BrowserGatewayTokens#callAddress`)이다. JDK WebSocket 으로 `ws://localhost:<port>/internal/browser-gateway/<표식>/devtools/browser/<GUID>` 에 붙어 10만 글자 메시지가 온전히 되돌아오는지, `Origin` 을 실은 연결은 403 으로 거절되는지, 틀린 표식은 404 인지, 같은 devtools 경로의 일반 GET(upgrade 없음)은 빈 404 인지 본다

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
| `backend/gradle/libs.versions.toml` | 수정 |
| `backend/src/main/java/com/bifos/assistant/browser/domain/CdpRelay.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/domain/CdpRelayConnector.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/domain/CdpRelayListener.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/infra/WebSocketCdpRelayConnector.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/presentation/BrowserGatewaySocketConfig.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/presentation/BrowserGatewayHandshake.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/presentation/BrowserGatewaySocket.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/presentation/*.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/browser/infra/WebSocketCdpRelayConnectorTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/browser/presentation/BrowserGatewaySocketTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/browser/presentation/BrowserGatewaySocketIntegrationTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/testsupport/IntegrationTestDoubles.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/testsupport/EchoCdpRelayConnector.java` | 신규 |
