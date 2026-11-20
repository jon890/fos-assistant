# Phase 01. 관리자 OAuth 앱 설정과 카탈로그 선언

**Execution profile**: standard

## 목표

관리자 OAuth 앱 설정과 카탈로그 선언을 구현하고 해당 경계에서 테스트한다.

**범위 외**: 계정 연결 시작·완료와 실제 갱신, 알림, 연결 해제 흐름, 웹과 운영 앱 등록.

## 컨텍스트

**요구사항**: REQ1, REQ2, REQ3

**근거 문서**: `docs/features/connector-oauth/spec.md`, `docs/features/connector-oauth/design.md`, `hermes/plugins/dashboard-profile-api/README.md`

## 의도 메모

- 두 번째 Control Plane PR에서 시작 API와 토큰 통로를 함께 연다.
- 외부 토큰과 코드, client secret 원문을 로그와 사용자 응답에 쓰지 않는다.

## 작업 항목

### 1. 카탈로그의 선택 선언

`ConnectorCatalogDecoder`는 검증된 OAuth 제공자, 앱 범위, 단추가 채우는 비밀 칸, 설정 주소를 읽는다. 없거나 잘못된 OAuth 선언은 그 칸만 비운다. `ConnectionDtos.ConnectorView`까지 전달하되 시작·완료 API와 토큰 통로가 배포되기 전에는 `ready=false`로 응답한다. 카탈로그가 내는 수동 입력 칸과 기존 호출자는 보존한다.

### 2. 명시적인 관리자 경로

`ConnectorOAuthApps`는 `ServerSecrets`를 써서 플러그인별 앱과 공유 앱을 저장한다. client secret은 암호화하고 client ID와 App 이름은 메타데이터에 쓴다. 같은 앱의 저장·삭제는 한 JVM에서 직렬화한다. `/api/v1/admin/secrets/connector-oauth/apps`로 목록을 읽고 그 하위 플러그인 id 경로로 앱을 저장하거나 비운다. 카탈로그가 OAuth를 선언한 대상만 받는다. 관리자 응답에는 비밀값 앞부분과 공개 메타데이터만 넣는다.

### 3. 관리자·카탈로그 경계 테스트

`ConnectorOAuthAppAdminControllerTest`로 앱 범위와 암호 저장, 관리자 권한, 비설정과 삭제 응답을 확인한다. `HttpHermesConnectorClientTest`로 잘못된 선언이 있는 카탈로그에도 입력 필드와 정상 OAuth 선언이 유지되는지 확인한다. `ConnectorConnectionControllerTest`로 일반 카탈로그 응답에 필요한 공개 칸만 나가고 연결 쓰기 단추는 꺼져 있는지 확인한다.

## 검증

```bash
cd backend && HEAVY_SLOTS=2 orch slot heavy -- ./gradlew test --tests '*ConnectorOAuthAppAdminControllerTest' --tests '*HttpHermesConnectorClientTest' --tests '*ConnectorConnectionControllerTest'
```

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/hermes/application/dto/ConnectorOAuthDeclaration.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/ConnectorOAuthApps.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/dto/ConnectorOAuthAppStatus.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/dto/ConnectorOAuthSummary.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/admin/secret/presentation/ConnectorOAuthAppAdminController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/admin/secret/presentation/ConnectorOAuthAppAdminDtos.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/application/dto/ConnectorManifest.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/infra/ConnectorCatalogDecoder.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/ConnectorConnectionService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/dto/ConnectorSummary.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/presentation/ConnectionDtos.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/admin/secret/ConnectorOAuthAppAdminControllerTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/hermes/HttpHermesConnectorClientTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorConnectionControllerTest.java` | 수정 |
