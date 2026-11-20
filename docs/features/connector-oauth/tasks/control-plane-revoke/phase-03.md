# Phase 03. 관리자가 서명 비밀을 새로 만든다

**Execution profile**: deep

## 목표

관리자가 단추 연결의 서명 비밀(`connector_oauth_signing`)을 새로 만들면 모든 단추 연결의 OAuth 파일에 새 통로 주소를 다시 쓴다(설계 「서버 비밀값 줄」). 비밀이 새었을 때 옛 표식을 모두 무효로 만드는 길이다.

**범위 외**: 서버 비밀값 화면의 줄과 확인 창(웹 PR).

## 컨텍스트

경로는 `backend/src/main/java/com/bifos/assistant/` 기준이다.

- `connector/oauth/application/ConnectorOAuthSigner` 가 서명 비밀을 처음 쓸 때 만들고(`secret()`), `currentUrl(연결 번호)` 로 본 연결 통로 주소를 만든다. 비밀은 매번 `ServerSecrets.read` 로 읽어 바꾸면 바로 반영된다.
- 대시보드의 주소 바꾸기는 `hermes/application/port/HermesConnectorOAuthClient.replaceTokenUrl(vault, tokenUrl)` 이고 OAuth 파일의 `token_url` 만 바꾼다. 파일이 없으면 `MISSING` 이다.
- 단추 연결의 돌아오기는 사용자 행 잠금 안에서 `signer.currentUrl` 로 주소를 만들어 commit 한다(`ConnectorOAuthFlows.exchanged` 의 `connectionService.store`). 주소 다시 쓰기도 같은 잠금(`ConnectorBindingService.lockOwner`) 안에서 해야 옛 비밀로 만든 주소가 나중에 덮지 않는다.
- 선례는 브라우저 중계 서명 비밀이다: `browser/application/GatewaySecretService.regenerate`, `admin/secret/presentation/ServerSecretAdminController`. 관리자 경로는 기능의 application 만 부른다(`admin/secret/presentation/ConnectorOAuthAppAdminController` 가 `ConnectorOAuthApps` 를 부르는 것과 같다).
- 통로 주소는 실행기 밖 파일에 있어 바인딩 재설치와 재시작이 필요 없다. 떠 있는 플러그인 프로세스는 다음 프로세스부터 새 주소를 받고 그 사이 옛 표식은 401 이다.

**요구사항**: REQ6

**근거 문서**: `docs/features/connector-oauth/design.md` 의 「서버 비밀값 줄」 과 「토큰 통로 표식」, `docs/features/secret-store/spec.md`(범용 쓰기 경로를 두지 않는다)

## 의도 메모

- 대상 후보는 상태로 고르지 않고 모든 연결 행의 번호다(`connections.findAll()`). 처음 연결하는 흐름의 행은 commit 전까지 `DISCONNECTED` 라, 상태로 먼저 고르면 옛 비밀로 주소를 만든 흐름이 목록 조회 뒤에 커밋돼 빠진다. 번호마다 주인 행을 잠근 뒤 다시 읽어 그때 해제되지 않은 단추 연결인지 본다. 잠금이 그 흐름의 커밋을 기다리므로 빠지는 연결이 없다. 가족 규모라 행 수가 적다.
- 결과가 `OK` 가 아니거나 예외면 그 연결을 실패로 세고 다음으로 간다. 비밀은 이미 바뀌었으므로 예외로 끝내지 않는다. 실패한 연결은 「새로 만들기」 를 다시 누르거나 그 연결을 다시 연결해 고친다.
- 진행 중이던 연결 흐름(상태 값, 대기 표식)은 옛 비밀로 서명돼 실패한다. 사용자가 다시 시도한다.

## 작업 항목

### 1. `connector/oauth/application/ConnectorOAuthSigner.java` 에 `regenerate(Long adminId)`

`secret()` 과 같은 잠금(`synchronized (this)`) 안에서 32바이트 무작위 값을 16진수로 써 넣는다. KEK 가 없으면 `ServerSecrets.write` 의 `SECRET_STORE_UNAVAILABLE` 이 그대로 나간다.

### 2. `connector/oauth/application/ConnectorOAuthSigning.java` (신규, `@Service`)

- `ConnectorOAuthSigningStatus status()`: 줄의 입력 여부, 마지막으로 바꾼 사람과 시각, 통로 주소를 만들 수 있는가(`signer.available()`), 해제되지 않은 단추 연결 수
- `ConnectorOAuthSigningRewrite regenerate(Long adminId)`: 비밀을 새로 만든 뒤 모든 연결 번호를 읽고, 번호마다 트랜잭션을 열어 주인 행을 잠그고 연결을 다시 읽는다. 그때 해제되지 않은 단추 연결이면 `replaceTokenUrl(connection.vault(), signer.currentUrl(id))` 를 부르고 아니면 건너뛴다. 다시 쓰려 한 수와 실패 수를 돌려준다. `status()` 의 연결 수는 같은 조건(해제되지 않은 단추 연결)으로 센다
- 이 클래스의 메서드에 `@Transactional` 을 붙이지 않는다. 비밀 쓰기가 반영된 뒤에 연결마다 따로 잠근다

dto 는 `connector/oauth/application/dto/ConnectorOAuthSigningStatus.java`, `ConnectorOAuthSigningRewrite.java` 다.

### 3. `admin/secret/presentation/ConnectorOAuthSigningAdminController.java` (신규)

- `GET /api/v1/admin/secrets/connector-oauth/signing`: 상태
- `POST /api/v1/admin/secrets/connector-oauth/signing/regenerate`: 새로 만들고 바뀐 상태와 다시 쓴 결과(`total`, `failed`)를 답한다

둘 다 `currentUser.requireAdmin()` 이다. 응답 모양은 컨트롤러마다 Dtos 파일을 두는 선례대로 `admin/secret/presentation/ConnectorOAuthSigningAdminDtos.java`(신규)에 둔다. 응답에 비밀 원문과 통로 주소는 없다.

### 4. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/admin/secret/ConnectorOAuthSigningAdminControllerTest.java`(신규, `ConnectorOAuthAppAdminControllerTest` 의 구성을 따른다)

- 관리자의 새로 만들기: 단추 연결마다 `replaceTokenUrl` 이 새 주소로 불리고 그 주소로 토큰 통로(`GET /internal/connector-token/{표식}`, `ConnectorTokenController` 를 MockMvc 로)를 부르면 토큰을 받는다. 새로 만들기 전의 주소는 401 이다. 붙여 넣은 연결과 해제한 연결(단추 연결을 시작만 한 `DISCONNECTED` 예약 행 포함)에는 부르지 않는다
- 한 연결의 주소 쓰기가 예외거나 `MISSING` 이면 `failed` 에 세고 나머지는 쓴다
- 상태 읽기: 입력 여부와 단추 연결 수가 맞고 응답에 원문이 없다
- `MEMBER` 역할은 두 경로 모두 403 이다

`backend/src/test/java/com/bifos/assistant/connector/ConnectorOAuthSignerTest.java` 에 `regenerate` 뒤 옛 상태 값과 표식이 거절되는 사례를 더한다.

### 5. 문서

- `docs/features/connector.md` 의 「관리자 OAuth 앱 설정」 에 두 경로와 응답을 적는다
- `docs/features/connector-oauth/design.md` 의 「서버 비밀값 줄」 에 경로 이름과 실패한 연결의 처리를 적는다
- `docs/features/connector-oauth.md` 의 현재 상태 문장을 Control Plane 이 들어온 지금에 맞추고 `covers` 에 `backend/src/main/java/com/bifos/assistant/connector/oauth/` 를 더한다

## 검증

```bash
cd backend && ./gradlew test --tests '*ConnectorOAuthSigningAdminControllerTest' --tests '*ConnectorOAuthSignerTest' --tests '*ConnectorOAuthAppAdminControllerTest'
cd backend && ./gradlew qualityCheck
node --test test/repo/doc-links.test.ts test/repo/doc-files.test.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/connector/oauth/application/ConnectorOAuthSigner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/oauth/application/ConnectorOAuthSigning.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/oauth/application/dto/ConnectorOAuthSigningStatus.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/oauth/application/dto/ConnectorOAuthSigningRewrite.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/admin/secret/presentation/ConnectorOAuthSigningAdminController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/admin/secret/presentation/ConnectorOAuthSigningAdminDtos.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/admin/secret/ConnectorOAuthSigningAdminControllerTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorOAuthSignerTest.java` | 수정 |
| `docs/features/connector.md` | 수정 |
| `docs/features/connector-oauth/design.md` | 수정 |
| `docs/features/connector-oauth.md` | 수정 |
