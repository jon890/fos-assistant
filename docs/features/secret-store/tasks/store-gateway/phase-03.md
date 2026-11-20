# Phase 03. 관리자 「서버 비밀값」 API

**Execution profile**: standard

## 목표

`ADMIN` 만 부르는 `/api/v1/admin/secrets` 를 만들어 화면이 값의 상태를 읽고 중계 서명 비밀을 새로 만들고 지우게 한다. 응답에는 원문이 없다.

**범위 외**: 화면(phase 04). VAPID, profile key, provider key 줄(뒤 PR). 이 PR 의 목록에는 중계 서명 비밀 한 줄만 있다.

## 컨텍스트

**요구사항**: REQ1, REQ2, REQ3, REQ18

**근거 문서**: `docs/features/secret-store/spec.md` 의 REQ1-REQ3, REQ18, DECISION4, DECISION20, `docs/features/secret-store/design.md` 의 「Control Plane API」

- 관리자 컨트롤러 본보기: `admin/browser/presentation/UserBrowserAdminController.java`(메서드마다 `currentUser.requireAdmin()`, 기능 서비스와 `UserDisplayNameService` 로 이름을 붙인 view record).
- `admin` 은 `application`, `application.dto`, `presentation` 만 갖고 기능의 `application` 과 dto 만 부른다(`backend/docs/code-architecture.md` 「패키지 배치 규칙」, `ADMIN_USES_ONLY_FEATURE_APPLICATION`). 그래서 `crypto.domain.ServerSecrets` 를 직접 부르지 않는다.
- 다시 만들기의 흐름은 `browser` 의 값 바꾸기 뒤 `connector` 의 바인딩 다시 설치다. `browser` 가 `connector` 보다 아래층이라 둘을 잇는 일은 `admin.secret.application` 이 한다.
- KEK 가 없으면 `GatewaySecretService.regenerate` 가 `SECRET_STORE_UNAVAILABLE`(409)을 던진다. 응답에 `storeAvailable` 을 함께 내 화면이 미리 막는다.

## 의도 메모

- 값마다 다른 동작(만들기, 넣기, 공급자 목록)이 뒤 PR 에서 늘어난다. 목록 응답은 값 종류마다 칸을 두는 record 로 하고 일반 「이름→값」 쓰기 API 는 두지 않는다. 원문을 받는 범용 경로를 만들지 않으려는 것이다.
- 확인 창 문구에 쓸 바인딩 수를 `GET` 이 함께 낸다.

## 작업 항목

### 1. `admin/secret/application/ServerSecretAdminService.java` 와 `admin/secret/application/dto/ServerSecretOverview.java`

`overview()`: `ServerSecretService.storeAvailable()`, `GatewaySecretService.status()`, `ConnectorBrowserBindings.count()`(비어 있을 수 있음)를 묶는다. Hermes 가 내려가도 화면이 열린다. `regenerateGateway(Long adminId)`: `GatewaySecretService.regenerate` 뒤 `ConnectorBrowserBindings.reinstallAll()` 를 부르고 그 결과를 돌려준다. 값 쓰기가 실패하면 다시 설치를 부르지 않는다. 값이 바뀐 뒤 다시 설치가 카탈로그를 읽지 못해도 오류 응답이 아니라 200 과 `catalogUnavailable` 참으로 돌려준다(값은 이미 바뀌었다). `deleteGateway(Long adminId)`: `GatewaySecretService.delete`.

### 2. `crypto/application/ServerSecretService.java`

`storeAvailable()`: `ServerSecrets.available()`. 뒤 PR 의 목록 조회가 이 서비스에 붙는다.

### 3. `admin/secret/presentation/ServerSecretAdminController.java` 와 `admin/secret/presentation/ServerSecretAdminDtos.java`

`GET /api/v1/admin/secrets` → `{ storeAvailable, browserGateway: { set, prefix, updatedBy: { id, name } | null, updatedAt, gatewayUrlConfigured, bindingCount: number | null } }`. `POST /api/v1/admin/secrets/browser-gateway/regenerate` → 갱신된 같은 모양과 `reinstall: { total, restartPending, failed, catalogUnavailable }`. `DELETE /api/v1/admin/secrets/browser-gateway` → 204. 이름은 `UserDisplayNameService` 로 붙인다.

### 4. 이 phase 를 검증하는 `admin/secret/ServerSecretAdminControllerTest.java`

MockMvc 통합 테스트(관리자 컨트롤러 테스트의 기존 방식). `MEMBER` 는 세 경로 모두 403 이고 줄이 바뀌지 않음, `ADMIN` 의 다시 만들기 뒤 `set` 참과 앞부분과 이름, 응답 본문 문자열에 저장된 원문이 없음(테스트가 저장소에서 원문을 읽어 응답 문자열과 대조), 지우기 뒤 `set` 거짓, 카탈로그 읽기 실패 때 `GET` 이 200 에 `bindingCount` null.
`admin/secret/ServerSecretAdminKekMissingTest.java`: KEK 는 공유 컨텍스트에서 끌 수 없으므로 `@MockitoBean GatewaySecretService` 가 `SECRET_STORE_UNAVAILABLE` 을 던지게 하고 다시 만들기 응답이 409 와 그 코드이며 다시 설치가 불리지 않음을 본다(새 컨텍스트가 뜨는 비용을 받아들인다). 줄이 생기지 않는 것은 phase 01 의 단위 테스트가 본다.

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.admin.secret.*'
cd backend && ./gradlew archTest checkstyleMain checkstyleTest spotlessCheck qualityCheck
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/admin/secret/application/ServerSecretAdminService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/admin/secret/application/dto/ServerSecretOverview.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/admin/secret/presentation/ServerSecretAdminController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/admin/secret/presentation/ServerSecretAdminDtos.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/crypto/application/ServerSecretService.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/admin/secret/ServerSecretAdminControllerTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/admin/secret/ServerSecretAdminKekMissingTest.java` | 신규 |
