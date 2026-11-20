# Phase 01. 표식, 토큰 통로, 갱신과 끊김 알림

**Execution profile**: standard

## 목표

단추로 만든 연결의 플러그인이 표식 주소에서 지금 유효한 access token 을 받게 하고, Control Plane 이 만료 전에 갱신하며 끊김을 알린다.

**범위 외**: 연결 시작과 돌아오기, 「연결 다시 확인」 과 해제의 OAuth 갈래(뒤 phase), 서명 비밀 「새로 만들기」, 웹.

## 컨텍스트

**요구사항**: REQ6, REQ7

**근거 문서**: `docs/features/connector-oauth/spec.md`, `docs/features/connector-oauth/design.md` 의 「Control Plane API」, 「갱신 주기」, 「끊김과 알림」, `hermes/plugins/dashboard-profile-api/README.md` 의 `connector-oauth` 경로.

- 대시보드 호출은 `hermes/application/port/HermesConnectorOAuthClient` 가 이미 갖는다. 응답 분류는 `ConnectorOAuth.Outcome` 이다.
- HMAC 표식의 선례는 `browser/application/BrowserGatewayTokens` 다. 서명 비밀은 `ServerSecrets` 의 `connector_oauth_signing` 줄에 두고 처음 쓸 때 만든다.
- 하위 기능 순서는 `connection` 아래, `oauth` 위다. `oauth` 가 `connection` 을 쓰고 거꾸로는 쓰지 못한다. 새 클래스는 `connector.oauth` 에 둔다. `connector.connection.application` 은 클래스 수 상한에 있다.
- 관리자 알림의 선례는 `connector/connection/application/ConnectorBindingAlerts` 다. `NotificationService.notify` 는 트랜잭션 안에서만 부른다.
- 예약 작업의 선례는 `ConnectorBindingApplier.runDriftScheduled` 다. cron 설정을 두고 테스트 설정에서는 `-` 로 끈다.

## 의도 메모

- access token 은 메모리에만 둔다. DB, 로그, 예외 메시지, `toString` 에 쓰지 않는다.
- 연결 실패와 `unavailable` 은 상태를 바꾸지 않는다. `grant_revoked` 만 다시 연결 필요로 바꾼다(스펙 DECISION13).
- 갱신이 `missing` 이면 붙여 넣기로 바뀐 것이라 메모리 칸만 지운다.
- 대시보드 호출이 예외로 끝나면 `unavailable` 로 다루고 로그에는 연결 번호와 예외 종류만 남긴다. 예약 갱신에서도 까닭이 로그에 남아야 한다.
- 관리자 알림은 같은 원인의 읽지 않은 알림이 있으면 만들지 않는다. `NotificationService.notifyOnce` 는 읽은 알림도 있는 것으로 보므로 쓰지 않는다.

## 작업 항목

### 1. `ConnectorOAuthSigner`

`connector_oauth_signing` 줄을 읽고 없으면 32바이트 무작위 값을 16진수로 만들어 쓴다(같은 JVM 에서 직렬화, 쓴 뒤 다시 읽는다). 저장소를 쓸 수 없으면 비어 있다.
통로 주소의 앞부분은 `BrowserProperties.gatewayBaseUrl` 의 끝 `/internal/browser-gateway` 를 `/internal/connector-token` 으로 바꾼 값이다. 비어 있으면 통로가 꺼진 것이다.
본 연결 표식 `k<연결 번호>.<HMAC>` 과 대기 표식 `p<연결 번호>.<nonce>.<HMAC>` 을 만들고 확인한다. 서명할 글은 `v1`, 종류, 번호, (대기면) `nonce` 를 줄바꿈으로 이은 것이다. 비교는 고정 시간 비교다.
상태 값(`v1.<사용자 번호>.<플러그인 id>.<만료 epoch 초>.<무작위 16바이트>.<HMAC>`)과 PKCE verifier 만들기도 이 클래스에 둔다. 서명할 글에 돌아오는 주소와 `nonce` 를 넣는다.

### 2. `ConnectorOAuthTokens` 와 `ConnectorOAuthAlerts`

`ConnectorOAuthTokens` 는 연결 번호마다 현재 칸, `연결 번호 + nonce` 마다 대기 칸을 메모리에 둔다. `refresh(연결 번호)` 는 연결 번호마다 한 번에 하나만 돌고, 기다린 요청은 앞 갱신이 넣은 칸을 그대로 쓴다.
갱신은 연결과 카탈로그 선언, 앱 자격(`ConnectorOAuthApps`)을 읽고 `HermesConnectorOAuthClient.refresh` 를 부른다. 결과별 처리는 설계 「끊김과 알림」 표와 같다.
`grant_revoked` 는 새 트랜잭션에서 사용자 행을 잠그고 연결을 다시 읽어 `ConnectorConnection.reconnectRequired(now)` 로 바꾼다. 다시 읽은 연결이 `DISCONNECTED` 거나 단추 연결이 아니거나 이미 표시가 있으면 건너뛴다. `READY` 와 확인 실패로 `PENDING` 인 연결 모두에 표시를 붙인다. 바뀐 그때만 사용자에게 `CONNECTOR_RECONNECT_REQUIRED` 알림(갈 곳 `CONNECTIONS`)을 남긴다.
`app_rejected` 는 연결 주인 그룹의 차단되지 않은 관리자마다 `CONNECTOR_OAUTH_APP_REJECTED` 알림(갈 곳 `ADMIN_SECRETS`)을 남긴다. 원인 번호는 설계의 이름 UUID 다.
`ConnectorOAuthApps.save` 는 저장한 앱 줄의 관리자 알림을 읽음으로 바꾼다. `save` 에는 트랜잭션이 없으므로 `ConnectorOAuthAlerts` 가 트랜잭션을 열고, 받는 사람을 가리지 않고 읽음으로 바꾸는 `NotificationService.resolveForAll` 을 더한다. 앱 줄을 비우는 `clear` 는 고친 것이 아니라 알림을 그대로 둔다.

### 3. 토큰 통로와 예약 갱신

`GET /internal/connector-token/{표식}` 을 `connector/oauth/presentation/ConnectorTokenController` 에 둔다. `SecurityConfig` 의 `permitAll` 과 `ControlPlaneJwtFilter.shouldNotFilter` 에 그 접두사를 더한다. 응답은 `{access_token, expires_at}` 이고 `Cache-Control: no-store` 다.
상태 코드는 설계 「토큰 통로 표식」 과 같다: 표식이 틀리면 401, 연결이 없거나 해제됐거나 단추 연결이 아니거나 갱신이 `missing` 이면 404, 다시 연결 필요면 409, 갱신이 `unavailable` 이나 `app_rejected` 면 503.
현재 칸이 없거나 만료까지 1분이 남지 않았으면 그 자리에서 갱신한다.
`ConnectorOAuthTokens.refreshDue` 는 `assistant.connector.oauth.refresh-cron`(10분마다)으로 `findByStatus(READY)` 로 읽은 연결 가운데 단추 연결을 메모리에서 골라(`auth` 는 JSON 칸이라 쿼리로 고르지 못한다) 현재 칸이 없거나 15분 안에 만료되는 연결을 갱신한다. 한 연결의 실패는 다음 연결을 막지 않는다.

### 4. 이 phase 를 검증하는 테스트

`ConnectorOAuthSignerTest`: 표식과 상태 값의 왕복, 번호를 바꾼 표식, 다른 비밀로 서명한 표식, 만료된 상태 값, 통로 주소 만들기.
`ConnectorOAuthTokenTest`(스프링 통합, `HermesConnectorOAuthClient` 와 `HermesConnectorClient` 는 `@MockitoBean`): 통로의 상태 코드 전부, 응답에 토큰이 있고 연결 행에 없는 것, 동시 요청 두 개에 갱신 한 번, 예약 갱신의 대상 고르기, 결과별 연결 상태와 알림 수(두 번 돌려도 하나), 앱 줄 저장 뒤 관리자 알림 읽음.

## 검증

```bash
cd backend && HEAVY_SLOTS=2 orch slot heavy -- ./gradlew test --tests '*ConnectorOAuthSignerTest' --tests '*ConnectorOAuthTokenTest' --tests '*ConnectorOAuthAppAdminControllerTest' --tests '*ConnectorConnectionTest' --tests '*ArchitectureRulesTest'
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/connector/oauth/application/ConnectorOAuthSigner.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/oauth/application/ConnectorOAuthTokens.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/oauth/application/ConnectorOAuthAlerts.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/oauth/application/dto/ConnectorOAuthApp.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/oauth/application/dto/ConnectorOAuthToken.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/oauth/application/dto/ConnectorOAuthMark.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/oauth/application/dto/ConnectorOAuthRefresh.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/oauth/application/dto/ConnectorOAuthState.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/oauth/presentation/ConnectorOAuthDtos.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/oauth/presentation/ConnectorTokenController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/oauth/application/ConnectorOAuthPassage.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/testsupport/BackendIntegrationTest.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/oauth/application/ConnectorOAuthApps.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/domain/ConnectorConnection.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/infra/ConnectorConnectionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/notification/domain/type/NotificationKind.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/notification/domain/type/NotificationTargetType.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/notification/application/NotificationService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/notification/infra/NotificationRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/config/SecurityConfig.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/auth/ControlPlaneJwtFilter.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/resources/application-test.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorOAuthSignerTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorOAuthTokenTest.java` | 신규 |
| `backend/docs/data-schema.md` | 수정 |
| `docs/features/attention.md` | 수정 |
