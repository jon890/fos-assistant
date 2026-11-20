# Phase 02. 연결 시작과 돌아오기

**Execution profile**: standard

## 목표

로그인한 사용자가 단추 연결을 시작해 로그인 주소를 받고, 돌아온 요청으로 연결을 만든다.

**범위 외**: 「연결 다시 확인」 의 강제 갱신과 해제 때 서비스 쪽 허락 취소(다음 PR), 웹.

## 컨텍스트

**요구사항**: REQ3, REQ4, REQ5, REQ9, REQ11

**근거 문서**: `docs/features/connector-oauth/spec.md`, `docs/features/connector-oauth/design.md` 의 「Control Plane API」, 「흐름」, 「실패와 일관성」.

- 지금 등록은 `ConnectorConnectionService.register` 와 `apply` 다. `apply` 가 사용자 행 잠금 안에서 보관 파일을 쓰고 연결을 `READY` 로 두고 붙은 바인딩을 다시 설치한다. 단추 연결은 보관 파일 쓰기 자리에 대시보드 commit 을 넣어 같은 길을 쓴다.
- `connector.connection` 은 `connector.oauth` 를 부르지 못한다. 카탈로그의 `ready` 처럼 연결 쪽이 OAuth 상태를 물어야 하는 곳은 `connector/connection/application/port/ConnectionOAuth` 인터페이스를 두고 `connector.oauth` 가 구현한다.
- `ConnectorConnectionService.java` 는 481줄이고 상한은 500줄이다. 먼저 겹치는 도우미를 걷어 자리를 만든다: private `warn` 은 같은 본문의 `ConnectorVerifier.warn` 을 쓰고, `now()` 와 `findManifest` 는 호출 자리에 풀어 쓴다. OAuth 의 일은 `connector.oauth` 에 둔다.
- 실제 경로 접두사는 `/api/v1` 이다. 설계의 경로 표를 `/api/v1/connections/...` 로 고친다.

## 의도 메모

- 시작은 보관 파일 이름이 필요해 연결 행이 없으면 `DISCONNECTED` 상태의 행을 만든다. 그만둔 흐름이 화면에 연결로 보이지 않는다.
- 로그인 주소의 방식은 앱 줄에 App 이름(slug)이 있으면 `install`(PKCE 없음), 없으면 `authorize`(PKCE)다. Control Plane 은 제공자 이름으로 갈라 쓰지 않는다.
- 흐름 기억(`nonce`, 연결 번호, 저장할 칸, 돌아오는 주소, PKCE 여부)은 상태 값의 무작위 값을 key 로 메모리에 10분 둔다. 꺼내 쓰면 지워 한 번만 받는다. 서명이 맞은 뒤에만 지운다.
- commit 이 409 면 나중 흐름이 이긴 것이라 연결 상태를 바꾸지 않는다. 그 밖의 commit 실패는 지금 등록의 보관 파일 쓰기 실패와 같이 `PENDING` 을 남긴다.
- 실패한 흐름의 discard 는 실패해도 결과를 바꾸지 않는다. 로그에는 단계와 예외 종류만 남긴다.
- 결과는 HTTP 200 의 `outcome` 으로 답한다. 웹의 돌아오는 화면이 한 모양으로 그린다(스펙 DECISION20).

## 작업 항목

### 1. `ConnectionOAuth` port 와 연결 쪽 변경

`ConnectionOAuth` 에 `ready(ConnectorManifest)` 와 `forget(ConnectorConnection)` 을 둔다. `ConnectorConnectionService.catalog` 가 `ConnectorOAuthSummary.from(선언, ready)` 로 `ready` 를 채운다.
`ConnectorValues.validated` 에 연결 방식을 받는 갈래를 더한다. 그 칸은 값이 오면 거절하고 필수 검사에서 뺀다.
`ConnectorConnectionService.registerOAuth(user, manifest, fields, commit)` 를 더한다. `apply` 의 보관 파일 쓰기를 인자로 받게 바꿔 붙여 넣기 등록과 단추 연결이 함께 쓴다. 붙여 넣기 등록은 보관 파일을 쓴 뒤 `forget` 을 불러 메모리 칸과 끊김 알림을 정리한다.
`ConnectionSnapshot` 과 `ConnectionView` 에 `auth`(`method`, `provider`, `reconnectRequired`)를 더한다.
다시 연결 필요 표시는 `ConnectorConnection.connected` 가 새 `auth` 로 덮어 지운다.
`ConnectorVerifier` 를 Spring 빈으로 바꿔 열고(`application` 직하의 public 타입은 빈이어야 한다) `vault` 가 `VaultAccess` 를 받게 한다. 결과 record 는 `dto/ConnectorVerified` 로 옮긴다. 단추 밖 칸 값의 검사와 저장할 모양도 이 빈의 `oauthValues`, `oauthFields` 로 연다. `ConnectorValues` 는 package-private 으로 둔다. 돌아오기의 확인도 이 경로를 써서 사용자 브라우저 중계 주소, 예외의 `unavailable` 바꾸기, 확인 도구가 없을 때의 처리가 등록과 같다.
`apply` 는 `ConnectorOAuthFlowConflict` 를 잡지 않고 그대로 내보내 트랜잭션을 되돌린다.

### 2. `ConnectorOAuthFlows` 와 컨트롤러

`POST /api/v1/connections/{connectorId}/oauth/start`(본문 `redirectUri`, `values`)와 `POST /api/v1/connections/oauth/complete`(본문 `state`, `code`, `error`, `setupAction`)를 `connector/oauth/presentation/ConnectorOAuthController` 에 둔다.
시작: 선언과 앱 값, 통로 주소가 없으면 409 `CONNECTOR_OAUTH_NOT_CONFIGURED`. 돌아오는 주소는 `http(s)` 절대 주소만 받는다. 칸 값을 검사하고 연결 행을 준비한 뒤 begin, 상태 값, authorize-url 순서로 부른다.
돌아오기의 결과(`outcome`): `CONNECTED`, `REAUTHORIZE`(로그인 주소를 함께 답함), `DENIED`, `INVALID_STATE`, `SCOPE_DENIED`, `APP_REJECTED`, `VERIFY_FAILED`(확인 도구의 오류 코드를 `reason` 에), `SUPERSEDED`, `UNAVAILABLE`.
순서는 설계 「돌아오기의 순서」 와 같다. 교환이 `ok` 면 대기 칸에 토큰을 두고 `ConnectorVerifier.vault(..., VaultAccess.PENDING)` 으로 확인 도구를 부른다. 통과하면 `registerOAuth` 안에서 commit 하고 대기 칸을 현재 칸으로 옮기며 끊김 알림을 읽음으로 바꾼다.
`app_rejected` 는 `ConnectorOAuthAlerts` 로 관리자에게 알린다.

### 3. 이 phase 를 검증하는 테스트

`ConnectorOAuthFlowTest`(스프링 통합, MockMvc): 정상 연결과 대시보드 호출 순서, `MEMBER` 역할 사용자, 붙여 넣은 연결에서 단추로 바꾸기, 인수 검증 계획의 REQ5 실패 경로 전부, `setupAction=update` 의 `REAUTHORIZE`, commit 409(기존 연결 상태가 그대로), 다시 연결 필요인 연결을 다시 연결하면 `READY` 이고 끊김 알림이 읽음인 것, 정상 연결의 로그 출력(`OutputCaptureExtension`)에 access token 과 client secret 이 없는 것, 앱 값이 없을 때의 409 와 카탈로그 `ready`.

### 4. 확인과 해제의 연결 고리

단추 연결의 `check` 는 `VaultAccess.CURRENT` 로 확인 도구를 부른다. `disconnect` 는 연결을 `DISCONNECTED` 로 바꾸기 전에 `ConnectionOAuth.forget` 을 불러 메모리 칸과 끊김 알림을 정리한다. `ConnectorOAuthFlowTest` 가 확인 도구의 인자와 해제 뒤 통로의 404 를 본다.

## 검증

```bash
cd backend && HEAVY_SLOTS=2 orch slot heavy -- ./gradlew test --tests '*ConnectorOAuthFlowTest' --tests '*ConnectorConnectionControllerTest' --tests '*ConnectorConnectionServiceTest' --tests '*ConnectorListingsTest' --tests '*ArchitectureRulesTest'
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/port/ConnectionOAuth.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/oauth/application/ConnectorOAuthConnections.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/oauth/application/ConnectorOAuthFlows.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/oauth/application/dto/ConnectorOAuthCompletion.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/oauth/presentation/ConnectorOAuthController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/oauth/presentation/ConnectorOAuthDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/ConnectorConnectionService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/ConnectorValues.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/dto/ConnectorOAuthSummary.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/dto/ConnectionSnapshot.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/presentation/ConnectionDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/domain/ConnectorConnection.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorOAuthFlowTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorConnectionServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorConnectionServiceLockTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorGuardBindingRoundTripTest.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/ConnectorVerifier.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/dto/ConnectorVerified.java` | 신규 |
| `docs/features/connector.md` | 수정 |
| `docs/features/connector-oauth/design.md` | 수정 |
