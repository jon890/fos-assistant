# Phase 03. 연결 다시 확인과 해제 때 취소

**Execution profile**: standard

## 목표

단추 연결의 「연결 다시 확인」 이 갱신을 먼저 하고, 해제가 제공자 허락 취소를 시도한 결과와 설정 주소를 답한다.

**범위 외**: 서명 비밀 「새로 만들기」, 웹.

## 컨텍스트

**요구사항**: REQ7, REQ8, REQ9

**근거 문서**: `docs/features/connector-oauth/spec.md`, `docs/features/connector-oauth/design.md` 의 「흐름」 끝의 「연결 다시 확인」, 「취소 범위」, 「실패와 일관성」 의 「해제」.

- 확인은 `ConnectorConnectionService.check`, 해제는 `disconnect` 다. 둘 다 `ConnectionOAuth` port 로 OAuth 의 일을 부른다.
- 확인 도구 호출은 `ConnectorVerifier.vault` 다. 단추 연결이면 `VaultAccess.CURRENT` 로 부른다.

## 의도 메모

- 확인의 갱신이 `unavailable` 이면 연결 상태를 바꾸지 않고 `CONNECTOR_UNAVAILABLE` 로 끝낸다. `grant_revoked` 면 갱신이 다시 연결 필요 표시를 붙였으므로(`PENDING` 연결에도 붙인다) 확인 도구 없이 `CONNECTOR_CREDENTIAL_REJECTED` 로 끝낸다.
- 갱신이 `ok` 인데 연결에 다시 연결 필요 표시가 있으면 확인 도구 전에 표시를 지우고 끊김 알림을 읽음으로 바꾼다. 서비스가 갱신을 받았으니 끊김이 아니고, 표시가 남으면 통로가 409 라 확인 도구가 토큰을 받지 못한다. 이때 연결의 `updatedAt` 은 바꾸지 않는다. 확인의 뒤 트랜잭션이 revision 으로 대조하기 때문이다. 상태는 확인 도구가 통과해야 `READY` 가 된다.
- 취소는 바인딩을 모두 뗀 뒤 보관 파일을 지우기 직전에 한다. 바인딩 떼기가 실패해 해제가 멈췄을 때 허락만 사라져 끊김 알림이 나가지 않게 한다.
- 취소는 실패해도 해제를 막지 않는다. 예외도 실패로 센다.
- 공유 앱(Google)은 같은 사용자의 다른 `READY` 단추 연결이 같은 제공자를 쓰면 취소를 부르지 않는다. 플러그인마다 앱인지 공유인지는 카탈로그 선언의 `appScope` 로 본다.
- 카탈로그에서 빠진 플러그인의 단추 연결은 앱 자격을 찾지 못해 취소를 건너뛰고 `failed` 로 답한다.

## 작업 항목

### 1. 확인의 OAuth 갈래

`ConnectionOAuth.refreshForCheck(연결 번호)` 를 더한다. `check` 는 단추 연결이면 확인 도구 전에 이것을 부른다. 구현은 `ConnectorOAuthTokens.refresh` 를 강제로 돌리고 결과를 위 오류로 바꾼다.
표시를 지우는 일은 `ConnectorConnection.reconnectCleared()` 가 한다.

### 2. 해제의 취소

`ConnectionOAuth.revoke(연결, 선언)` 을 더하고 `disconnect` 가 보관 파일을 지우기 직전에 부른다. 결과 `ConnectionOAuthRevoke(status, settingsUrl, installSettingsUrl)` 를 `ConnectionSnapshot.oauthRevoke` 와 `ConnectionView.oauthRevoke` 로 답한다. `status` 는 `revoked` 나 `failed` 다. 단추 연결이 아니면 null 이다.
구현은 메모리의 현재 access token 을 함께 넘기고, 끝나면 메모리 칸을 지우고 끊김 알림을 읽음으로 바꾼다.

### 3. 이 phase 를 검증하는 테스트와 문서

`ConnectorOAuthDisconnectTest`(스프링 통합): 인수 검증 계획의 REQ8 세 경우, 확인의 세 결과(`ok` 뒤 확인 도구가 `oauth: current` 로 불림, `grant_revoked`, `unavailable`), 단추 연결에 값을 붙여 넣은 뒤 통로 표식이 거절되는 것.
`docs/features/connector.md` 의 등록·확인·해제 표와 API 절, `docs/code-architecture.md` 의 「플러그인 비밀값을 두는 곳」 에 access token 의 자리를 적는다.

## 검증

```bash
cd backend && HEAVY_SLOTS=2 orch slot heavy -- ./gradlew test --tests '*ConnectorOAuth*' --tests '*ConnectorConnection*' --tests '*ArchitectureRulesTest'
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/dto/ConnectionOAuthRevoke.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/port/ConnectionOAuth.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/ConnectorConnectionService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/oauth/application/ConnectorOAuthAlerts.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/dto/ConnectionSnapshot.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/presentation/ConnectionDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/domain/ConnectorConnection.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/oauth/application/ConnectorOAuthConnections.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorOAuthDisconnectTest.java` | 신규 |
| `docs/features/connector.md` | 수정 |
| `docs/code-architecture.md` | 수정 |
