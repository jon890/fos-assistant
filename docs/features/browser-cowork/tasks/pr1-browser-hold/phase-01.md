# Phase 01. 사람을 기다리는 동안 자동 중지를 30분까지 미룬다

**Execution profile**: standard

## 목표

그 사용자에게 열린 화면 넘기기나 브라우저 플러그인의 기다리는 승인이 있으면, 자동 중지가 10분이 아니라 마지막 활동에서 30분이 지나야 브라우저를 끈다.

**범위 외**: 사람 기다림 승인(PR4)과 사람 잠금(PR3)은 이 포트에 뒤 PR 이 더한다. 승인 때 켜기는 phase 02.

## 컨텍스트

**요구사항**: REQ1

**근거 문서**: `docs/features/browser-cowork/spec.md`, `docs/features/browser-cowork/design.md` 의 「PR1 사람을 기다리는 동안 브라우저 유지(REQ1)」, `docs/features/user-browser.md` 의 「상태 전이」

- 자동 중지는 `backend/src/main/java/com/bifos/assistant/browser/application/UserBrowserSweeper.java` 의 `stopIdle` 이다. 후보는 `UserBrowserRepository.findByStatusAndLastActiveAtBefore(RUNNING, cutoff)` 이고, `BrowserUsage.inUse` 면 건너뛰며 `UserBrowserService.stopIfIdle(id, cutoff)` 가 다시 읽고 끈다.
- `browser` 는 `connector` 보다 아래다. 기존 포트 `browser/application/port/BrowserGrantOwners.java` 와 그 구현 `connector/connection/application/ConnectorBrowserGrantOwners.java` 의 모양을 따른다.
- 판정 줄은 `connector/action/domain/ConnectorAction.java`(칸 `userId`, `conversationId`, `connectorId`, `status`, `expiresAt`, 내장 `handoff` 의 `need`, `at`, `closedAt`)다. 열린 넘기기 조건은 `connector/handoff/infra/ConnectorHandoffRepository.java` 의 `handoff.need is not null and handoff.closedAt is null` 과 같게 쓴다.
- manifest 읽기는 `connector/connection/application/ConnectorCatalogCache#find`(빈 값과 예외는 읽지 못한 것으로 본다), 브라우저 플러그인 여부는 `ConnectorManifest#ownerBrowser()` 다.
- 하위 기능 순서(rule-76)에서 `action` 은 `approval`, `contentdraft`, `handoff` 보다 아래다. 구현은 `connector/action/application` 에 둔다.

## 의도 메모

- 넘기기는 대화가 있고 이번 켜기(`user_browser.started_at`) 이후에 생긴 것만 센다. 닫는 길이 사람 turn 뿐이라 대화 밖 넘기기와 버려진 카드가 영영 열려 있어, 빼지 않으면 기다림이 영구히 참이 된다.
- 카탈로그를 읽지 못한 기다리는 승인은 기다림으로 센다(설계 DECISION4).
- 포트가 예외를 던지면 그 줄은 끄지 않고 건너뛴다.
- 30분 값은 설정 `assistant.browser.wait-hold-timeout`(기본 `30m`)이고 `idle-timeout` 이상이어야 한다.

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/browser/application/port/BrowserWaitDemand.java`(신규)

`boolean waiting(long userId, Instant since)` 하나. Javadoc 에 뜻(열린 넘기기, 브라우저 플러그인의 기다리는 승인)과 구현 자리를 적는다.

### 2. `BrowserProperties` 와 `application.yml`

- 레코드에 `Duration waitHoldTimeout` 을 `screenTimeout` 뒤에 더한다. 컴팩트 생성자에서 양수이고 `idleTimeout` 이상인지 확인하고, 아니면 `assistant.browser.wait-hold-timeout` 이름으로 `IllegalStateException`. `@param` 주석을 더한다.
- `backend/src/main/resources/application.yml` 의 `assistant.browser` 에 `wait-hold-timeout: ${ASSISTANT_BROWSER_WAIT_HOLD_TIMEOUT:30m}` 과 한국어 주석을 `idle-timeout` 아래에 둔다.
- `UserBrowserService` 는 499줄이라 파일 길이 상한(rule-44, 500줄)에 닿는다. 메서드를 더하지 않는다. 점검이 `LiveProperties<BrowserProperties>` 를 주입받아 `current().waitHoldTimeout()` 을 읽는다.
- `new BrowserProperties(` 를 쓰는 테스트(`grep -rl "new BrowserProperties(" backend/src/test`)에 새 인자를 넣는다. 기본은 `Duration.ofMinutes(30)`.

### 3. `UserBrowserSweeper.stopIdle`

`BrowserWaitDemand` 와 `LiveProperties<BrowserProperties>` 를 주입받는다. 후보마다 `usage.inUse` 다음에 `waiting(browser.userId(), browser.startedAt())` 을 묻는다(`startedAt` 이 null 이면 `Instant.EPOCH`). 참이면 `now - waitHoldTimeout` 을 기준으로 삼아 `lastActiveAt` 이 그보다 뒤면 건너뛰고, 아니면 그 기준으로 `stopIfIdle` 한다. 거짓이면 지금처럼 10분 기준. 포트 예외는 경고 로그(예외 종류만) 뒤 건너뛴다. `UserBrowser` 는 lombok `@Getter @Accessors(fluent = true)` 라 `startedAt()` 이 이미 있다.

### 4. `connector/action/infra/ConnectorActionRepository.java` 쿼리 둘

- `boolean existsOpenHandoffSince(Long userId, Instant since)`: `userId` 같고 `conversationId is not null`, `handoff.need is not null`, `handoff.closedAt is null`, `handoff.at >= since`.
- `List<String> findPendingConnectorIds(Long userId, Instant now)`: `status = PENDING` 이고 `expiresAt > now` 인 줄의 `distinct connectorId`.

### 5. `connector/action/application/ConnectorBrowserWaitDemand.java`(신규)

`BrowserWaitDemand` 구현 `@Component`. 열린 넘기기가 있으면 참. 아니면 기다리는 승인의 플러그인마다 `ConnectorCatalogCache#find` 로 보고, 비었거나 `ownerBrowser()` 가 참이면 참. 시각은 주입한 `Clock`.

### 6. 문서

- `docs/features/user-browser.md` 「상태 전이」 의 자동 중지 행에 기다림이 있으면 `wait-hold-timeout` 까지 미루는 규칙과 기다림의 뜻을 적고 `docs/features/browser-cowork/spec.md` 의 REQ1 을 가리킨다.
- `docs/adr/ADR-20261007-user-browser.md` 「결정」 표의 자동 중지 행에 「사람을 기다리는 동안은 마지막 활동에서 30분(2026-11-16 개정)」 을 더하고 browser-cowork 스펙을 링크한다.

### 7. 테스트

- `backend/src/test/java/com/bifos/assistant/browser/application/UserBrowserSweeperTest.java`: 기다림 참 + 15분 유휴는 끄지 않음, 기다림 참 + 31분 유휴는 끔, 기다림 거짓 + 11분 유휴는 끔, 포트 예외면 끄지 않음.
- `backend/src/test/java/com/bifos/assistant/browser/config/BrowserPropertiesTest.java`: `wait-hold-timeout` 이 `idle-timeout` 보다 작으면 실패.
- `backend/src/test/java/com/bifos/assistant/connector/ConnectorBrowserWaitDemandTest.java`(신규, `testsupport/BackendIntegrationTest` 통합 H2. `ownerBrowser` manifest 를 심는 방식은 `connector/approval/application/CheckoutApprovalTest.java` 를 따른다): 열린 넘기기 참, 닫힌 넘기기·대화 없는 넘기기·`since` 전 넘기기·남의 넘기기 거짓, 브라우저 플러그인의 기다리는 승인 참, 카탈로그를 읽지 못한 기다리는 승인 참, 아닌 플러그인·시간이 지난 승인·남의 승인 거짓.

## 검증

```bash
cd backend && ./gradlew test --tests '*UserBrowserSweeperTest' --tests '*BrowserPropertiesTest' --tests '*ConnectorBrowserWaitDemandTest'
cd backend && ./gradlew archTest checkstyleMain checkstyleTest
```

Gradle 은 공통 지시서의 무거운 검사 대기열(`orch slot heavy`)로 감싸 돌린다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/browser/application/port/BrowserWaitDemand.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/browser/config/BrowserProperties.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/browser/application/UserBrowserSweeper.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/action/infra/ConnectorActionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/action/application/ConnectorBrowserWaitDemand.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/browser/config/BrowserPropertiesTest.java` | 수정 (생성자 인자) |
| `backend/src/test/java/com/bifos/assistant/browser/application/UserBrowserServiceTest.java` | 수정 (생성자 인자) |
| `backend/src/test/java/com/bifos/assistant/browser/application/UserBrowserSweeperTest.java` | 수정 (생성자 인자) |
| `backend/src/test/java/com/bifos/assistant/browser/application/GatewayRewriterTest.java` | 수정 (생성자 인자) |
| `backend/src/test/java/com/bifos/assistant/browser/application/BrowserGatewayTokensTest.java` | 수정 (생성자 인자) |
| `backend/src/test/java/com/bifos/assistant/browser/application/BrowserGatewayTest.java` | 수정 (생성자 인자) |
| `backend/src/test/java/com/bifos/assistant/browser/application/BrowserScreensTest.java` | 수정 (생성자 인자) |
| `backend/src/test/java/com/bifos/assistant/browser/infra/DockerProxyBrowserRuntimeTest.java` | 수정 (생성자 인자) |
| `backend/src/test/java/com/bifos/assistant/browser/infra/DockerProxyBrowserRuntimeHttpTest.java` | 수정 (생성자 인자) |
| `backend/src/test/java/com/bifos/assistant/browser/infra/FileBrowserProfileStoreTest.java` | 수정 (생성자 인자) |
| `backend/src/test/java/com/bifos/assistant/browser/presentation/UserBrowserControllerTest.java` | 수정 (생성자 인자) |
| `backend/src/test/java/com/bifos/assistant/browser/presentation/UserBrowserScreenControllerTest.java` | 수정 (생성자 인자) |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorBrowserWaitDemandTest.java` | 신규 |
| `docs/features/user-browser.md` | 수정 |
| `docs/adr/ADR-20261007-user-browser.md` | 수정 |
