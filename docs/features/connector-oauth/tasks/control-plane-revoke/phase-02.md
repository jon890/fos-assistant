# Phase 02. 해제할 때 서비스 쪽 허락을 취소한다

**Execution profile**: deep

## 목표

단추로 만든 연결을 해제하면 서비스 쪽 허락 취소를 시도하고, 결과와 사용자가 직접 거둘 수 있는 설정 화면 주소를 응답에 담는다. 취소가 실패해도 해제는 끝낸다(스펙 REQ8, DECISION6, DECISION21).

**범위 외**: 안내 문구와 링크를 그리는 화면(웹 PR), 실패한 흐름의 discard(이미 있다).

## 컨텍스트

경로는 `backend/src/main/java/com/bifos/assistant/` 기준이다.

- `connector/connection/application/ConnectorConnectionService.disconnect` 는 바인딩을 모두 떼고(`bindingService.detach`) 보관 파일을 지운 뒤(`connector.deleteVault`, OAuth 파일과 대기 파일도 함께 지워진다) `oauth.forget` 과 `connection.disconnected` 를 부른다. 떼기나 지우기가 실패하면 `PENDING` 으로 두고 `ConnectorOperationFailure` 다.
- 대시보드 취소는 `hermes/application/port/HermesConnectorOAuthClient.revoke(vault, connectorId, credentials, accessToken)` 이고 `Outcome` 을 돌려준다. 파일은 지우지 않는다. GitHub 은 access token 이 없으면 대시보드가 먼저 갱신해 받는다.
- 앱 자격은 `connector/oauth/application/ConnectorOAuthApps.app(manifest)`, 메모리의 access token 은 `ConnectorOAuthTokens.current(연결 번호)` 다.
- 선언(`hermes/application/dto/ConnectorOAuthDeclaration`)이 `appScope`(`PLUGIN`, `SHARED`), `settingsUrl`(허락을 거두는 화면), `installSettingsUrl`(앱 설치를 지우는 화면, GitHub 만)을 갖는다.
- `application` 직하의 public 타입은 Spring 빈이어야 하고 빈 안에 public 중첩 record 를 둘 수 없다. 결과 타입은 `dto` 에 둔다.

**요구사항**: REQ8

**근거 문서**: `docs/features/connector-oauth/spec.md`(REQ8, DECISION6, DECISION21), `docs/features/connector-oauth/design.md` 의 「취소 범위」 와 「실패와 일관성」

## 의도 메모

- 취소는 바인딩을 모두 뗀 뒤, 보관 파일을 지우기 직전에 부른다. 바인딩 떼기가 실패했는데 허락만 사라지는 일을 막는다. 설계 「실패와 일관성」 의 「취소 → 지금 해제」 순서를 이 순서로 고친다.
- 취소 결과가 `OK` 일 때만 `revoked` 다. 그 밖의 결과와 예외, 건너뛴 경우는 모두 `failed` 다. 사용자가 설정 화면에서 직접 확인하게 하는 쪽이 안전하다.
- 보관 파일 지우기가 취소 뒤에 실패하면 연결은 `PENDING` 으로 남고 다시 해제할 수 있다. 그때의 취소는 `failed` 로 끝나 안내와 링크가 보인다.

## 작업 항목

### 1. `connector/connection/application/dto/ConnectionOAuthRevoke.java` (신규)

`record ConnectionOAuthRevoke(Status status, String settingsUrl, String installSettingsUrl)` 와 중첩 enum `Status { REVOKED, FAILED }`. `settingsUrl` 은 `FAILED` 일 때만 있다. `installSettingsUrl` 은 선언에 있으면 두 경우 모두 담는다.

### 2. `connector/connection/application/port/ConnectionOAuth.java` 에 `revoke`

`ConnectionOAuthRevoke revoke(ConnectorConnection connection, Optional<ConnectorManifest> manifest)`. 단추 연결이 아니면 null 이다. 예외를 던지지 않는다.

### 3. `connector/oauth/application/ConnectorOAuthConnections.java` 의 구현

| 경우 | 대시보드 취소 | 결과 |
| --- | --- | --- |
| 단추 연결이 아니다 | 부르지 않음 | null |
| 카탈로그에서 빠졌거나 선언에 `oauth` 가 없다 | 부르지 않음 | `FAILED`, 주소 없음 |
| 앱 자격이 없다 | 부르지 않음 | `FAILED` 와 주소 |
| 공유 앱(`SHARED`)이고 같은 사용자의 다른 `READY` 단추 연결이 같은 제공자를 쓴다 | 부르지 않음 | `FAILED` 와 주소 |
| 그 밖 | 메모리의 access token(없으면 null)과 함께 부른다 | `OK` 면 `REVOKED`, 다른 결과와 예외는 `FAILED` |

예외는 단계와 플러그인 번호, 예외 종류만 경고 로그로 남긴다.

### 4. `connector/connection/application/ConnectorConnectionService.java` 의 `disconnect`

바인딩 떼기 반복문 뒤, `step = STEP_VAULT` 앞에서 `oauth.revoke(connection, manifest)` 를 부르고 결과를 응답 스냅샷에 담는다. 늘어나는 줄은 import 하나와 호출, 반환 줄까지 5줄 안팎이고 phase 01 이 `check` 를 줄여 남긴 자리에 들어간다. 커밋 전에 파일이 500줄 이하인지 본다(`node scripts/checks/check-file-length.mjs`).

### 5. 응답: `connector/connection/application/dto/ConnectionSnapshot.java`, `connector/connection/presentation/ConnectionDtos.java`

`ConnectionSnapshot` 에 `oauthRevoke`(없으면 null)를 더하고 `withOAuthRevoke` 로 채운다. `ConnectionView` 에 `oauthRevoke: {status, settingsUrl, installSettingsUrl}` 를 더한다. `status` 는 `revoked` 나 `failed` 다. 해제 밖의 응답과 붙여 넣은 연결은 null 이다.

### 6. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/connector/ConnectorOAuthRevokeTest.java`

`ConnectorOAuthTokenTest` 의 구성을 따른다.

- 취소 `OK`: 연결이 `DISCONNECTED`, 결과 `REVOKED` 와 앱 설치 화면 주소, 취소가 `deleteVault` 보다 먼저 불리고 메모리의 access token 이 함께 간다
- 취소 `UNAVAILABLE` 과 예외: 연결이 `DISCONNECTED`, 결과 `FAILED` 와 제공자 설정 주소
- 공유 앱에 다른 `READY` 단추 연결이 있다: 취소를 부르지 않고 `FAILED`. 그 연결이 없으면 부른다
- 카탈로그에서 빠진 플러그인: 취소를 부르지 않고 `FAILED`, 연결은 `DISCONNECTED`
- 바인딩 떼기가 실패한다: 취소를 부르지 않는다
- 붙여 넣은 연결: 취소를 부르지 않고 결과가 null 이다

`backend/src/test/java/com/bifos/assistant/connector/ConnectorConnectionControllerTest.java` 에 해제 응답의 `oauthRevoke` 모양을 더한다.

### 7. 문서

- `docs/features/connector-oauth/design.md` 의 「실패와 일관성」 해제 줄의 순서를 고치고, REQ8 줄과 「취소 범위」 에 응답 모양을 맞춘다
- `docs/features/connector.md` 의 「단추 연결(OAuth)」 에 해제 응답의 `oauthRevoke` 를 적는다

## 검증

```bash
cd backend && ./gradlew test --tests '*ConnectorOAuthRevokeTest' --tests '*ConnectorConnectionControllerTest' --tests '*ConnectorConnectionServiceTest'
cd backend && ./gradlew qualityCheck
node --test test/repo/doc-links.test.ts test/repo/doc-files.test.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/dto/ConnectionOAuthRevoke.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/port/ConnectionOAuth.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/ConnectorConnectionService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/dto/ConnectionSnapshot.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/presentation/ConnectionDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/oauth/application/ConnectorOAuthConnections.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorOAuthRevokeTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorConnectionControllerTest.java` | 수정 |
| `docs/features/connector-oauth/design.md` | 수정 |
| `docs/features/connector.md` | 수정 |
