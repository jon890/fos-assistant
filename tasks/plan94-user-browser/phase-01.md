# Phase 01. CDP 세션 클라이언트와 세션 이어가기 프로필

**Execution profile**: deep

## 목표

Control Plane 이 사용자 브라우저의 탭 하나에 CDP WebSocket 으로 붙어 명령을 보내고 사건을 받는 클라이언트를 만든다. 프로필을 만들 때 Chrome 이 이전 세션(세션 쿠키 포함)을 이어서 열도록 설정을 써 둔다.

**범위 외**: 화면 SSE 와 입력 API(phase 02), 웹 화면(단계 2b), 커넥터 중계(단계 3).

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261007-user-browser.md`, `docs/backend/user-browser.md`, `tasks/plan94-user-browser/README.md` 의 「단계 2: 로그인 화면」

- 브라우저 컨테이너의 CDP 주소는 `BrowserRuntime.cdpAddress(containerId)` 가 준다(`http://<컨테이너 IP>:<포트>`). 운영 이미지는 그 포트에서 작은 TCP 중계가 Chrome 의 loopback CDP 로 잇고, Control Plane 의 주소에서 오는 연결만 받는다
- Chrome 은 `Host` 가 IP 나 `localhost` 일 때만 받는다. 컨테이너 IP 를 그대로 쓰면 통과한다. JDK `HttpClient` 와 `java.net.http.WebSocket` 은 `Host` 를 바꾸지 못하지만 IP 주소라 괜찮다. `Origin` 은 보내지 않는다
- 새 의존성을 들이지 않는다. JDK 21 의 `java.net.http.WebSocket` 과 Jackson 3(`tools.jackson`) 을 쓴다
- 층 규칙: port 는 `browser.domain`, 구현은 `browser.infra`(단계 1 의 `BrowserRuntime`, `DockerProxyBrowserRuntime` 본보기). infra 는 application 을 import 하지 못한다(`ArchitectureRules.LAYER_DIRECTION`)
- 프로필 디렉터리는 `FileBrowserProfileStore` 가 만든다. Chrome 은 `<프로필>/Default/Preferences` JSON 을 읽는다

## 의도 메모

- 네이버 QR 로그인은 세션 쿠키만 준다(2026-10-08 실측). 단계 1 은 끌 때 컨테이너를 지우므로 세션 쿠키가 다음 기동에 남지 않는다. Chrome 의 「이전 세션 이어서 열기」(`session.restore_on_startup = 1`)를 켜면 정상 종료한 뒤 다음 기동에서 세션 쿠키가 돌아온다. 끄기는 이미 `stop?t=10`(SIGTERM 뒤 10초 대기)이라 정상 종료다. 운영의 QR 로그인 → 끄기 → 켜기 왕복으로 확인한다. 실패하면 아이디·비밀번호와 「로그인 상태 유지」 안내로 바꾼다
- 같은 실측: headless 에서 QR 로그인 주소로 바로 가면 막히고, 일반 로그인 화면에서 QR 로 바꾸면 통과한다. 그래서 화면의 시작 주소는 호출자가 고르고(phase 02), 서비스 이름과 주소는 커넥터가 단계 3 에서 준다. 커넥터 밖 코드에 서비스 이름을 쓰지 않는다(`test/unit/connector-neutral.test.ts`)
- CDP 명령은 id 로 응답을 짝짓고 10초 안에 답이 없으면 실패로 끝낸다. 연결이 끊기면 기다리던 명령을 모두 실패로 끝내고 닫힘을 알린다

## 작업 항목

### 1. 세션 이어가기 설정

`FileBrowserProfileStore.ensure` 가 디렉터리를 만든 뒤 `Default/Preferences` 가 없으면 `{"session":{"restore_on_startup":1}}` 를 쓴다(권한 600). 이미 있으면 건드리지 않는다. 링크를 따라가지 않는다.
시험: `FileBrowserProfileStoreTest` 에 새 프로필에 그 파일이 생기고, 있던 파일은 그대로인 경우를 더한다.

### 2. CDP 클라이언트

- `browser.domain.CdpTargets`(port): `list(URI cdp) → List<CdpTarget>`(page 만, `id`, `title`, `url`), `create(URI cdp, String url) → CdpTarget`(`PUT /json/new?<url>`), `activate(URI cdp, String id)`
- `browser.domain.CdpConnector`(port): `connect(URI cdp, String targetId, Consumer<CdpEvent> events, Runnable closed) → CdpConnection`. `CdpConnection` 은 `send(String method, Map<String,Object> params) → CompletableFuture<JsonNode>` 와 `close()`
- `browser.infra.HttpCdpTargets`, `browser.infra.WebSocketCdpConnector`: 위 구현. WebSocket 주소는 `ws://<cdp 의 host:port>/devtools/page/<id>` 로 만든다(Chrome 이 준 주소의 host 를 믿지 않는다). 대상 id 는 `[A-Za-z0-9]+` 만 받는다
- 메시지 조각(`last == false`)을 모아 한 JSON 으로 읽는다. screencast 프레임은 수백 KB 다

### 3. 시험

- `backend/src/test/java/com/bifos/assistant/browser/infra/WebSocketCdpConnectorTest.java`: JDK `HttpServer` 로는 WebSocket 을 받을 수 없으니 시험 안에 작은 가짜 CDP WebSocket 서버(소켓으로 핸드셰이크와 텍스트 프레임만)를 둔다. 명령 응답 짝짓기, 사건 전달, 조각난 큰 메시지, 시간 초과, 끊김 때 기다리던 명령 실패를 확인한다
- `backend/src/test/java/com/bifos/assistant/browser/infra/HttpCdpTargetsTest.java`: JDK `HttpServer` 로 `/json/list`(page 와 그 밖 섞어서), `/json/new`(PUT), 이상한 id 거절

## 검증

같은 Mac 의 다른 워커와 겹치지 않게 gradle 명령은 `.omc/scripts/heavy-lock` 으로 감싸 돌린다(저장소 밖 도구라 아래에는 명령만 적는다).

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.browser.*' --tests 'com.bifos.assistant.architecture.*'
cd backend && ./gradlew qualityCheck
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/browser/infra/FileBrowserProfileStore.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/browser/domain/CdpTargets.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/domain/CdpTarget.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/domain/CdpConnector.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/domain/CdpConnection.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/domain/CdpEvent.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/infra/HttpCdpTargets.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/infra/WebSocketCdpConnector.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/browser/infra/FileBrowserProfileStoreTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/browser/infra/WebSocketCdpConnectorTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/browser/infra/HttpCdpTargetsTest.java` | 신규 |
| `docs/backend/user-browser.md` | 수정 |
