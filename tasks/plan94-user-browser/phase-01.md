# Phase 01. 중계 설정과 접근 표식, 주인의 브라우저 확보

**Execution profile**: deep

## 목표

중계가 쓸 설정 두 개, 접근 표식을 만들고 확인하는 컴포넌트, 표식에서 주인을 찾아 그 브라우저를 만들고 켜는 서비스를 만든다.
다음 phase 의 HTTP 와 WebSocket 중계가 이 서비스 하나만 부르게 한다.

**범위 외**: 중계의 HTTP 컨트롤러와 WebSocket(phase 02), 커넥터 설치에 표식을 싣는 일(phase 03).

## 컨텍스트

- 사용자 브라우저의 상태 전이는 `backend/src/main/java/com/bifos/assistant/browser/application/UserBrowserService.java` 가 갖는다. `create(Long userId)`, `start(Long userId)`, `touch(Long userId)` 와 `openScreen` 이 켜고 CDP 주소를 얻는 방법(`runtime.cdpAddress(containerId)`)을 그대로 따른다
- 설정은 `backend/src/main/java/com/bifos/assistant/browser/infra/BrowserProperties.java` 의 record 이고 `LiveProperties<BrowserProperties>` 로 읽는다(`properties.current()`). 값은 `backend/src/main/resources/application.yml` 의 `assistant.browser` 아래에 env 로 받는다
- 패키지 층 순서는 `docs/backend/packages.md` 의 「최상위 패키지의 층 순서」 다. `browser`(3) 는 `connector`(18) 를 import 하지 못한다. 그래서 바인딩의 주인을 찾는 일은 `browser` 에 인터페이스로 두고 `connector` 가 구현한다
- 허용 목록은 `com.bifos.assistant.shared.auth.UserAccessPolicy#allowed(Long)` 이 판정한다. `UserBrowserSweeper` 가 같은 것을 쓴다
- 서명 계산은 `com.bifos.assistant.mcp.application.McpCallContext#hmac` 처럼 `javax.crypto.Mac`(`HmacSHA256`)와 `MessageDigest.isEqual` 로 한다. `mcp` 패키지를 import 하지 말고 같은 방식으로 따로 쓴다

**근거 문서**: `docs/backend/user-browser.md` 의 「설정」 과 「중계」, `backend/docs/adr/ADR-20261008-browser-gateway-token.md`

## 의도 메모

- 표식은 표에 저장하지 않는다. 무작위 표식과 해시 표는 다시 설치할 때마다 서버 정의가 바뀌어 gateway 재시작을 부르므로 기각했다
- 두 설정 중 하나라도 비면 중계만 꺼진다. 기동을 멈추지 않는다. 이미 브라우저 기능을 켠 운영에 env 를 더하기 전에 배포해도 서야 한다
- 브라우저가 없으면 만들고 켠다. 동시 수(`max-running`)와 자동 중지는 손대지 않는다. 화면으로 켤 때와 같은 `start` 를 부른다
- 표식, 서명, 비밀값은 어떤 로그와 예외 메시지에도 싣지 않는다

## 작업 항목

### 1. `BrowserProperties` 에 중계 설정 두 개

- record 끝에 `String gatewayBaseUrl`, `String gatewaySecret` 을 더한다. Javadoc `@param` 을 단다
- 생성자 검사: `gatewayBaseUrl` 이 비어 있지 않으면 `http://` 나 `https://` 로 시작하고 `/internal/browser-gateway` 로 끝나야 한다(끝 `/` 없음). `gatewaySecret` 이 비어 있지 않으면 32자 이상. 어긋나면 `IllegalStateException`(값은 메시지에 싣지 않는다)
- `public boolean gatewayEnabled()`: 둘 다 비어 있지 않을 때 참
- `application.yml` 의 `assistant.browser` 에 `gateway-base-url: ${ASSISTANT_BROWSER_GATEWAY_BASE_URL:}`, `gateway-secret: ${ASSISTANT_BROWSER_GATEWAY_SECRET:}` 와 한 줄 주석
- `new BrowserProperties(` 를 부르는 시험 파일(아래 변경 파일 표)에 두 인자(`null, null`)를 더한다. `git grep -n "new BrowserProperties(" -- backend/src` 로 빠진 곳이 없는지 본다

### 2. `browser.application.BrowserGatewayTokens`(신규 `@Component`)

- 생성자: `LiveProperties<BrowserProperties> properties`, `Clock clock`
- `Optional<String> bindingAddress(long bindingId)`: 중계가 꺼졌으면 빈 값. 켜져 있으면 `<gatewayBaseUrl>/b<번호>.<서명>`. 서명할 글은 `"v1\nbinding\n" + 번호`
- `Optional<String> callAddress(long userId)`: `<gatewayBaseUrl>/u<번호>.<만료 epoch 초>.<서명>`. 만료는 `clock.instant()` 에서 5분 뒤. 서명할 글은 `"v1\ncall\n" + 번호 + "\n" + 만료`
- `Optional<BrowserGrant> verify(String token)`: 정규식 `^b([1-9][0-9]{0,18})\.([0-9a-f]{64})$` 나 `^u([1-9][0-9]{0,18})\.([1-9][0-9]{0,11})\.([0-9a-f]{64})$` 에 맞고, 서명이 고정 시간 비교로 같고, 호출 표식이면 만료가 지금보다 뒤일 때만 값을 준다. 번호가 `long` 을 넘으면 빈 값. 중계가 꺼졌으면 빈 값
- 서명은 `gatewaySecret` 의 UTF-8 바이트를 key 로 한 HMAC-SHA256 의 소문자 16진수
- `browser.application.model.BrowserGrant` 신규 record: `(Kind kind, long id)`, 안의 enum `Kind { BINDING, CALL }`. `CALL` 의 `id` 는 사용자 번호다

### 3. `browser.application.BrowserGrantOwners`(신규 인터페이스)와 구현

- `Optional<Long> bindingOwner(long bindingId)`: 그 바인딩의 연결 주인(사용자 번호). 없으면 빈 값
- 구현 `connector.application.ConnectorBrowserGrantOwners`(신규 `@Component`): `ConnectorBindingRepository#findById` 로 읽고 `binding.connection().userId()` 를 준다. `@Transactional(readOnly = true)`

### 4. `UserBrowserService#ensureRunning(Long userId)`

- 반환은 신규 `browser.application.model.BrowserEndpoint` record `(Long browserId, URI cdp)`
- 줄이 없으면 `create(userId)` 를 부른다. 그 사이 다른 요청이 만들어 `BROWSER_EXISTS` 가 나면 무시하고 넘어간다
- `start(userId)` 를 부른다. `BROWSER_BUSY` 이면 `properties.current().startTimeout()` 까지 0.5초마다 줄을 다시 읽어 `RUNNING` 이면 넘어가고 `STOPPED` 나 `FAILED` 가 되면 `start` 를 한 번 더 부른다. 시간이 지나면 `BROWSER_BUSY` 를 그대로 던진다. 기다림은 주입한 `Clock` 과 `Thread.sleep` 이 아니라 시험에서 바꿀 수 있는 대기 함수(패키지 범위 필드 `Sleeper` 같은 것)로 한다
- 켠 뒤 `owned(userId).containerId()` 로 `runtime.cdpAddress` 를 읽는다. 없으면 `BROWSER_START_FAILED`
- `requireEnabled()` 를 먼저 부른다

### 5. `browser.application.BrowserGateway`(신규 `@Service`)

- 생성자: `BrowserGatewayTokens tokens`, `BrowserGrantOwners owners`, `UserAccessPolicy access`, `UserBrowserService browsers`, `BrowserUsage usage`
- `GatewayTarget open(String token)`: 아래 순서로 판정하고, 통과하면 `browsers.touch(userId)` 뒤 `GatewayTarget(Long userId, Long browserId, URI cdp)`(신규 `browser.application.model.GatewayTarget` record)를 준다
  1. 중계가 꺼졌으면 `ApiException(BROWSER_DISABLED)`. 기능이 꺼진 것도 같다(`browsers.enabled()`)
  2. `tokens.verify` 가 빈 값이면 `ApiException(BROWSER_NOT_FOUND)`
  3. `BINDING` 이면 `owners.bindingOwner(id)`, `CALL` 이면 `id` 가 주인이다. 주인이 없거나 `access.allowed(userId)` 가 거짓이면 `BROWSER_NOT_FOUND`
  4. `browsers.ensureRunning(userId)`. 그 예외는 그대로 던진다
- `BrowserUsageHandle hold(GatewayTarget target)`: `usage.open(target.browserId())` 를 그대로 준다. WebSocket 이 쓴다
- `void touch(GatewayTarget target)`: `browsers.touch(target.userId())`
- 거절은 `log.info` 에 까닭의 종류(`malformed`, `owner_missing`, `revoked`)만 남긴다

### 6. 시험

- `backend/src/test/java/com/bifos/assistant/browser/application/BrowserGatewayTokensTest.java`: 같은 바인딩 번호는 같은 주소, 다른 비밀은 다른 서명, 서명 한 글자를 바꾸면 빈 값, 앞자리 0(`b01.`)과 대문자 16진수는 빈 값, 호출 표식이 만료 1초 뒤에는 빈 값, 중계가 꺼졌으면 주소와 확인이 모두 빈 값
- `backend/src/test/java/com/bifos/assistant/browser/application/BrowserGatewayTest.java`: 바인딩 표식이 주인의 브라우저를 켜고 `GatewayTarget` 을 준다, 주인이 허용 목록에서 꺼졌으면 `BROWSER_NOT_FOUND`, 바인딩이 없으면 `BROWSER_NOT_FOUND`, 중계가 꺼졌으면 `BROWSER_DISABLED`. `UserBrowserService` 는 mock 으로 둔다
- `backend/src/test/java/com/bifos/assistant/browser/application/UserBrowserServiceTest.java` 에 `ensureRunning`: 줄이 없으면 만들고 켠다, `BROWSER_BUSY` 뒤 `RUNNING` 이 되면 주소를 준다, 동시 수가 차면 `BROWSER_CAPACITY` 를 그대로 던진다. 이 파일의 기존 가짜 런타임과 저장소 준비를 따른다
- `backend/src/test/java/com/bifos/assistant/browser/infra/BrowserPropertiesTest.java`(없으면 신규): 주소가 `/internal/browser-gateway` 로 끝나지 않으면 기동 실패, 비밀이 31자면 실패, 둘 다 비면 `gatewayEnabled()` 거짓

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.browser.*' --tests 'com.bifos.assistant.architecture.*'
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck
git grep -n "new BrowserProperties(" -- backend/src
```

마지막 명령의 줄마다 인자가 18개인지 눈으로 본다. 첫 명령이 실패 0 이어야 한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/browser/infra/BrowserProperties.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/main/java/com/bifos/assistant/browser/application/BrowserGatewayTokens.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/application/BrowserGrantOwners.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/application/BrowserGateway.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/application/model/BrowserGrant.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/application/model/BrowserEndpoint.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/application/model/GatewayTarget.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/application/UserBrowserService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorBrowserGrantOwners.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/browser/application/BrowserGatewayTokensTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/browser/application/BrowserGatewayTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/browser/application/UserBrowserServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/browser/infra/BrowserPropertiesTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/browser/application/BrowserScreensTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/browser/application/UserBrowserSweeperTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/browser/infra/DockerProxyBrowserRuntimeHttpTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/browser/infra/DockerProxyBrowserRuntimeTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/browser/infra/FileBrowserProfileStoreTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/browser/presentation/UserBrowserControllerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/browser/presentation/UserBrowserScreenControllerTest.java` | 수정 |
