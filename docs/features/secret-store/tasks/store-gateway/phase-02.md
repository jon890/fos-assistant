# Phase 02. 브라우저 중계 서명 비밀을 저장소로 옮기고 바인딩을 다시 설치한다

**Execution profile**: standard

## 목표

중계 서명 비밀을 `ServerSecrets` 의 `browser_gateway` 에서 읽고, 서버가 새로 만들고 지우며, 옛 환경 변수 값을 기동 때 한 번 가져온다. 비밀을 바꾼 뒤 브라우저 플러그인 바인딩을 모두 다시 설치하는 메서드를 `connector` 에 더한다.

**범위 외**: 관리자 API 와 화면(phase 03, 04). 다시 설치한 바인딩의 재시작 대기를 푸는 일(기존 「반영 완료」, 연결 반영 자동화).

## 컨텍스트

**요구사항**: REQ9, REQ18

**근거 문서**: `docs/features/secret-store/spec.md` 의 REQ9, REQ18, DECISION16, DECISION20, `docs/features/secret-store/design.md`, `backend/docs/adr/ADR-20261008-browser-gateway-token.md`

- 표식은 `browser.application.BrowserGatewayTokens` 가 만들고 확인한다. 지금은 `LiveProperties<BrowserProperties>` 의 `gatewaySecret()` 로 서명하고 `gatewayEnabled()`(주소와 비밀 둘 다 있음)로 켜짐을 본다. 사용처는 `BrowserGateway`, `ConnectorBindingInstalls.ownerBrowserValue`, `ConnectorConnectionService`, `ConnectorVerifier` 다.
- `BrowserProperties.gatewaySecret` 은 32자 이상 검사를 갖는다. 이 PR 부터 그 값은 옛 값 가져오기에만 쓴다. 설정 이름과 검사는 그대로 둔다(되돌리기와 기존 운영 설정 호환).
- 바인딩 다시 설치의 기존 흐름: `ConnectorConnectionService.apply` 가 연결 주인 잠금 안에서 `bindingService.reinstall(binding, manifest)` 로 `ConnectorBindingAttempt` 를 만들고, `bindingService.start(...)` 가 그 트랜잭션을 커밋한 뒤 `bindingService.execute(attempt)` 를 트랜잭션 밖에서 하나씩 부른다(`check` 의 본보기). 바인딩 저장소는 `ConnectorBindingRepository`, 브라우저를 쓰는 플러그인은 카탈로그 manifest 의 `ownerBrowser()` 다.
- 다시 설치하면 대시보드가 `restart_required` 를 답해 바인딩이 재시작 대기가 된다. 이것은 스펙대로다(DECISION20).
- 가져오기는 `SmartInitializingSingleton` 으로 웹 서버가 요청을 받기 전에 돈다(설계 DECISION7). Flyway 는 그보다 먼저 끝난다.
- 통합 테스트가 `@OverrideProperties` 로 준 `assistant.browser.gateway-secret` 은 phase 01 의 `IntegrationTestIsolation` 이 테스트마다 저장소에 넣고 끝나면 지운다. 그래서 그 설정을 쓰는 기존 테스트는 고치지 않는다.
- 생성자를 직접 부르는 단위 테스트 `browser/application/GatewayRewriterTest.java`, `browser/application/BrowserGatewayTest.java` 는 `BrowserGatewayTokens` 생성자가 바뀌면 컴파일이 깨진다. 가짜 `ServerSecrets`(메모리 Map 구현, `testsupport/InMemoryServerSecrets.java`)를 넘기도록 고친다.
- 플러그인 카탈로그를 읽지 못하면 `ConnectorManifests.read` 가 `CONNECTOR_UNAVAILABLE` 을 던진다.

## 의도 메모

- 옛 비밀을 함께 받지 않는다. 바꾸면 옛 표식은 바로 무효다(스펙 DECISION20).
- 가져오기는 이름의 줄이 있으면(값이 비어 있어도) 건너뛴다(스펙 DECISION16). 가져온 줄의 `updated_by` 는 NULL 이다.
- 새로 만드는 값은 `SecureRandom` 32바이트를 소문자 16진수 64자로 쓴다. 기존 32자 이상 규칙을 만족한다.
- `reinstallBrowserBindings` 는 바인딩 상태를 가리지 않는다. 재시작 대기 바인딩도 옛 표식을 쥐고 있다.

## 작업 항목

### 1. `browser/application/BrowserGatewayTokens.java`

서명 비밀을 `ServerSecrets.read("browser_gateway")` 에서 읽는다. `enabled()` 는 주소 설정과 저장소 값이 모두 있을 때 참이다. 매 호출마다 읽는다(저장소 캐시가 있다). 기존 형식 검사와 비교, 로그 규칙은 그대로다.

### 2. `browser/application/GatewaySecretService.java`

`regenerate(Long adminId)`: 새 값을 만들어 `ServerSecrets.write("browser_gateway", value, Map.of(), adminId)`. `delete(Long adminId)`: `clear`. `status()`: `ServerSecrets.find` 를 `browser.application.dto.GatewaySecretStatus`(값 있음, 앞부분, 마지막 변경 사용자, 시각, 중계 주소 설정 여부)로 낸다. 각 메서드는 자기 트랜잭션이다.

### 3. `browser/application/GatewaySecretImporter.java`

`SmartInitializingSingleton`. 옛 값은 `LiveProperties<BrowserProperties>` 의 `current().gatewaySecret()` 로 읽는다(테스트가 설정을 바꿔 볼 수 있다). `ServerSecrets.exists("browser_gateway")` 가 거짓이고 옛 값이 비어 있지 않고 `ServerSecrets.available()` 이면 그 값을 쓰고 `browser gateway secret imported` 를 info 로 남긴다. 실패하면 예외 클래스 이름만 경고하고 기동을 이어 간다.

### 4. `connector/connection/application/ConnectorBindingService.java` 와 `connector/connection/application/dto/BrowserBindingReinstall.java`

`public BrowserBindingReinstall reinstallBrowserBindings()`: 카탈로그에서 `ownerBrowser()` 인 커넥터 id 를 읽는다. 카탈로그를 읽지 못하면 예외를 던지지 않고 `catalogUnavailable` 참인 결과를 돌려준다. 대상 바인딩 번호를 모은 뒤 **바인딩마다** 따로 트랜잭션을 연다: 연결 주인 사용자 잠금을 잡고 바인딩을 다시 읽어(없으면 건너뜀) 기존 `reinstall` 로 attempt 를 만들어 커밋하고, 트랜잭션 밖에서 `execute` 한다. `attempts.begin` 의 예외(`AGENT_NOT_FOUND`, `AGENT_BUSY` 등)와 `execute` 의 `CALL_FAILED` 는 그 바인딩의 실패로 세고 다음으로 간다. 결과 dto `BrowserBindingReinstall(int total, int restartPending, int failed, boolean catalogUnavailable)`. `OptionalInt countBrowserBindings()` 는 확인 창용 수이고 카탈로그를 읽지 못하면 비어 있다. 메서드에 `@Transactional` 을 붙이지 않는다(외부 호출을 트랜잭션 밖에서 한다). 필요한 조회를 `ConnectorBindingRepository` 에 더한다.

### 5. `backend/src/main/resources/application.yml` 과 `backend/docs/adr/ADR-20261008-browser-gateway-token.md` 와 `docs/features/user-browser.md`

`gateway-secret` 주석을 「옛 값 가져오기에만 쓴다. 값은 관리자 화면의 서버 비밀값이 갖는다」 로 고친다. ADR 은 고치지 않고(결정 기록), `user-browser.md` 의 설정 설명과 「중계」 절에 비밀이 저장소에 있고 관리자 화면에서 새로 만든다는 것과 ADR-20261120 / server-secret 링크를 적는다. ADR-20261008 / browser-gateway-token 의 「비밀」 행은 결정 당시 값이라 그대로 두고, 루트 `docs/adr/INDEX.md` 대신 `backend/docs/adr/INDEX.md` 의 그 ADR 상태 칸에 「비밀을 두는 곳은 ADR-20261120 / server-secret 이 바꾼다」 를 더한다.

### 6. 이 phase 를 검증하는 테스트

- `browser/application/BrowserGatewayTokensTest.java` 수정: 저장소 값으로 서명, 값을 바꾸면 재기동 없이 새 표식, 옛 비밀 표식 거절, 값이 없으면 꺼짐.
- `browser/application/GatewaySecretServiceTest.java` 신규: 만들기 뒤 64자 16진수와 앞부분, 지우기 뒤 꺼짐.
- `browser/application/GatewaySecretImporterTest.java` 신규: 줄 없음 → 가져옴, 줄 있음 → 건너뜀, 지운 줄 → 건너뜀, KEK 없음 → 경고만.
- `connector/ConnectorBrowserBindingReinstallTest.java` 신규: 가짜 대시보드(`ConnectorBindingHttpFixture` 를 따른다)로 브라우저 플러그인 바인딩 둘(`READY` 하나, 재시작 대기 하나)과 브라우저 아닌 바인딩 하나. 앞의 둘만 다시 설치 요청이 가고 재시작 대기로 남는다. 한쪽 호출이 실패하면 `failed` 1 이고 다른 쪽이 진행된다. 카탈로그 읽기가 실패하면 `catalogUnavailable` 참이고 예외가 나지 않는다.
- `browser/application/GatewayRewriterTest.java`, `browser/application/BrowserGatewayTest.java`: 가짜 `ServerSecrets` 로 생성한다.
- `testsupport/InMemoryServerSecrets.java` 신규: 단위 테스트용 메모리 구현.

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.browser.*' --tests 'com.bifos.assistant.connector.ConnectorBrowserBindingReinstallTest' --tests 'com.bifos.assistant.connector.ConnectorBindingGatewayOffTest' --tests 'com.bifos.assistant.connector.ConnectorBindingServiceTest' --tests 'com.bifos.assistant.connector.ConnectorConnectionServiceTest' --tests 'com.bifos.assistant.browser.presentation.BrowserGatewaySocketIntegrationTest'
cd backend && ./gradlew archTest checkstyleMain checkstyleTest spotlessCheck
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/browser/application/BrowserGatewayTokens.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/browser/application/GatewaySecretService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/application/GatewaySecretImporter.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/application/dto/GatewaySecretStatus.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/ConnectorBindingService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/dto/BrowserBindingReinstall.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/infra/ConnectorBindingRepository.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/docs/adr/INDEX.md` | 수정 |
| `docs/features/user-browser.md` | 수정 |
| `backend/src/test/java/com/bifos/assistant/browser/application/BrowserGatewayTokensTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/browser/application/GatewaySecretServiceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/browser/application/GatewaySecretImporterTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorBrowserBindingReinstallTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/browser/application/GatewayRewriterTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/browser/application/BrowserGatewayTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/testsupport/InMemoryServerSecrets.java` | 신규 |
