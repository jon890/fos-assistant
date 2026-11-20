# Phase 01. 「연결 다시 확인」 이 먼저 갱신한다

**Execution profile**: deep

## 목표

단추 연결의 「연결 다시 확인」 이 확인 도구를 부르기 전에 토큰을 한 번 강제로 갱신한다. 다시 연결 필요인 연결이 서비스 쪽에서 풀렸으면 이 단추로 되살아나고, 서비스가 거절하면 확인 도구를 부르지 않고 끝낸다(스펙 DECISION19).

**범위 외**: 해제 때 취소(phase 02), 서명 비밀 새로 만들기(phase 03), 웹 화면.

## 컨텍스트

경로는 `backend/src/main/java/com/bifos/assistant/` 기준이다.

- `connector/connection/application/ConnectorConnectionService.check` 는 앞 트랜잭션(`prepare`) → 트랜잭션 밖 확인 도구(`ConnectorVerifier.vault(…, VaultAccess.CURRENT)`) → 뒤 트랜잭션(`recordCheck`) 순서다. 뒤 트랜잭션은 `prepare` 가 읽은 `updatedAt` 을 revision 으로 대조한다.
- 연결 쪽은 port `connector/connection/application/port/ConnectionOAuth` 로만 단추 연결의 일을 맡긴다. 구현은 `connector/oauth/application/ConnectorOAuthConnections` 다.
- `connector/oauth/application/ConnectorOAuthTokens.refresh(연결 번호, null)` 이 늘 새로 받는다. `GRANT_REVOKED` 면 그 안에서 `ConnectorOAuthAlerts.reconnectRequired` 가 연결을 `PENDING` 과 다시 연결 필요로 바꾸고 알림을 남긴다. `APP_REJECTED` 는 관리자 알림을 남긴다.
- 토큰 통로(`ConnectorOAuthPassage`)는 `reconnectRequired` 인 연결에 409 를 답한다. 그래서 갱신이 `OK` 면 확인 도구 전에 표시를 지워야 확인 도구가 토큰을 받는다.
- `ConnectorConnectionService.java` 는 496줄이고 파일 길이 상한이 500줄이다. `check` 의 임시 변수를 줄여 늘어난 만큼 덜어 낸다. `connection.application` 직하 클래스 수도 상한(25, rule-75)이라 새 클래스를 그 자리에 두지 않는다.

**요구사항**: REQ7

**근거 문서**: `docs/features/connector-oauth/spec.md`(DECISION13, DECISION19), `docs/features/connector-oauth/design.md` 의 「흐름」 끝과 「끊김과 알림」

## 의도 메모

- 갱신 결과는 새 타입 대신 이미 있는 공통 어휘 `hermes/application/dto/ConnectorCallError` 로 돌려준다. 연결 쪽이 `ConnectorErrors.of` 로 오류 코드를 만든다.
- 강제 갱신은 확인 도구를 부를 때만 한다(보관 파일이 있고 카탈로그에 있는 플러그인). 카탈로그에서 빠진 플러그인의 다시 확인은 지금처럼 오류 없이 `PENDING` 으로 끝난다.
- 표시를 지울 때 `updatedAt` 을 바꾸지 않는다. 바꾸면 뒤 트랜잭션이 revision 이 다르다고 보고 확인 결과를 버린다.
- 갱신이 `MISSING`(OAuth 파일이 없음)이면 막지 않고 확인 도구로 넘긴다. 확인 도구의 실패가 연결을 `PENDING` 으로 남겨 다시 연결하게 한다.

## 작업 항목

### 1. `connector/connection/application/port/ConnectionOAuth.java` 에 `refreshForCheck`

`Optional<ConnectorCallError> refreshForCheck(long connectionId)`. 비어 있으면 확인 도구를 불러도 된다. 트랜잭션 밖에서 부른다.

### 2. `connector/oauth/application/ConnectorOAuthConnections.java` 의 구현

`tokens.refresh(connectionId, null)` 의 결과로 나눈다.

| 결과 | 돌려주는 값 | 그 밖에 하는 일 |
| --- | --- | --- |
| `OK` | 비어 있음 | `alerts.recovered(connectionId)` |
| `MISSING` | 비어 있음 | 없음 |
| `GRANT_REVOKED` | `CREDENTIAL_REJECTED` | 없음(갱신이 이미 표시와 알림을 남겼다) |
| 그 밖(`APP_REJECTED`, `UNAVAILABLE`) | `UNAVAILABLE` | 없음 |

### 3. `connector/oauth/application/ConnectorOAuthAlerts.java` 에 `recovered(long connectionId)`

자기 트랜잭션에서 연결을 읽고 주인 행을 잠근 뒤(`users.findByIdForUpdate`) 다시 읽는다. 단추 연결이고 `reconnectRequired` 면 `connection.reconnectResolved()` 를 부르고 끊김 알림을 읽음으로 바꾼다(`notifications.resolve`, `reconnected` 와 같은 원인 번호). 아니면 아무것도 하지 않는다.

### 4. `connector/connection/domain/ConnectorConnection.java` 에 `reconnectResolved()`

`fields.auth` 의 `reconnectRequired` 만 거짓으로 바꾼다. 상태와 `updatedAt` 은 그대로 둔다.

### 5. `connector/connection/application/ConnectorConnectionService.java` 의 `check`

`Prepared` 에 연결 번호를 더한다. 확인 도구를 부르는 갈래에서 `prepared.oauth() != null` 이면 `oauth.refreshForCheck` 를 먼저 부르고, 값이 있으면 `ConnectorErrors.of` 의 오류를 던진다(연결 상태를 더 바꾸지 않는다). 임시 변수 여섯(`verifyFailure`, `verified`, `listedTools` 와 람다용 사본 셋)을 한 번만 대입하는 `ConnectorVerified result` 하나로 줄인다. 확인 도구를 부르지 않은 경우는 `result` 를 null 로 두고, `CheckResult` 를 만들 때 `verified = result != null && result.failure() == null`, 나머지 둘은 null 로 푼다(지금의 `verified=false, failure=null` 과 같다). 이 정리로 파일을 490줄 아래로 두어 phase 02 가 더할 자리를 남긴다.

### 6. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/connector/ConnectorOAuthCheckTest.java`

`ConnectorOAuthTokenTest` 의 구성(`@BackendIntegrationTest`, 대역 `HermesConnectorClient`·`HermesConnectorOAuthClient`, 검사마다 다른 그룹)을 따른다. 확인 도구는 5인자 `callWithVault(…, VaultAccess.CURRENT)` 로 잡는다.

- 갱신 `OK`: 갱신이 확인 도구보다 먼저 불리고 연결이 `READY` 다
- 다시 연결 필요인 연결에 갱신 `OK`: 표시가 지워지고 끊김 알림이 읽음이며 `READY` 다
- `GRANT_REVOKED`: `CONNECTOR_CREDENTIAL_REJECTED`, 확인 도구는 불리지 않고 연결은 `PENDING` 과 다시 연결 필요, 알림 하나
- `UNAVAILABLE` 과 `APP_REJECTED`: `CONNECTOR_UNAVAILABLE`, 확인 도구는 불리지 않고 연결은 `READY` 그대로. `APP_REJECTED` 는 관리자 알림 하나
- 붙여 넣은 연결: 갱신을 부르지 않는다

### 7. 문서

- `docs/features/connector-oauth/design.md` 의 「연결 다시 확인」 문단을 지금 동작으로 고친다(「다음 Control Plane PR 에서 넣는다」 를 지운다. `app_rejected`, `unavailable` 의 처리를 더한다)
- `docs/features/connector.md` 의 「단추 연결(OAuth)」 에 다시 확인의 갱신과 오류 코드를 적는다

## 검증

```bash
cd backend && ./gradlew test --tests '*ConnectorOAuthCheckTest' --tests '*ConnectorConnectionServiceTest' --tests '*ConnectorOAuthTokenTest'
cd backend && ./gradlew qualityCheck
node --test test/repo/doc-links.test.ts test/repo/doc-files.test.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/port/ConnectionOAuth.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/ConnectorConnectionService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/domain/ConnectorConnection.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/oauth/application/ConnectorOAuthConnections.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/oauth/application/ConnectorOAuthAlerts.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorOAuthCheckTest.java` | 신규 |
| `docs/features/connector-oauth/design.md` | 수정 |
| `docs/features/connector.md` | 수정 |
