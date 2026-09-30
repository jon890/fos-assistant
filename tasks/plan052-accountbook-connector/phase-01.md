# Phase 01. 사용자별 가계부 연결을 저장하고 Hermes에 반영한다

**Execution profile**: deep

## 목표

토큰 원문을 DB에 두지 않고 사용자별 가계부 profile을 등록, 확인, 해제한다.
실패나 재시작 대기 상태에서는 에이전트를 실행하지 못한다.
**범위 외**: 인프라 plugin 구현, 공개 주소와 운영 배포, 웹 화면.

## 컨텍스트

**근거 문서**: `docs/connectors.md`, `docs/data-schema.md`의 `accountbook_connection`,
`docs/hermes/mcp-profile-credentials.md`, `docs/code-architecture.md`의 backend 패키지.
`AgentLifecycleService.create(CurrentUser,String,AgentVisibility)`가 안전한 profile을 만들고 상한을 검사한다.
`HermesProfileProvisioner`는 Control Plane MCP 토큰과 profile key를 함께 만든다.
`AppUserRepository.findByIdForUpdate`로 사용자 행을 잠근다.
`Agent.changeAccess`로 비활성화하고 `AgentService.isEditableBy`로 편집을 막는다.
Spring Boot 4의 Jackson은 `tools.jackson`이다.

## 의도 메모

- 인프라가 manifest를 읽는다. Control Plane은 임의 서버 정의나 경로를 보내지 않는다.
- POST 요청의 token 문자열 표현을 가리고 외부 예외 본문과 cause를 로그에 남기지 않는다.
- 단일 사용자 행 잠금 트랜잭션에서 기존 생성기를 REQUIRED로 호출한다.
- 외부 env/install/probe 실패만 cause 없는 `ConnectorOperationFailure`로 바꾸고 `noRollbackFor`로 PENDING과 비활성화를 커밋한다. 이 예외는 controller까지 그대로 전파한다.
- 생성기 실패와 DB 실패는 잡지 않는다. rollback-only가 된 트랜잭션을 정상 저장처럼 취급하지 않는다.
- DB 커밋 실패 때 외부 변경이 남는 한계는 docs에 명시한다. 다시 등록하거나 해제해 상태를 맞춘다.
- `register(CurrentUser,String,String)`, `check(CurrentUser)`, `disconnect(CurrentUser)`에 `@Transactional(noRollbackFor=ConnectorOperationFailure.class)`를 붙인다. `read(CurrentUser)`는 읽기 전용이다.
- `ConnectorOperationFailure`는 `ApiException`을 상속하며 고정 메시지와 `CONNECTOR_OPERATION_FAILED`만 받고 cause를 갖지 않는다.
- 토큰 검증은 등록 시만 한다. check는 원문 토큰을 다시 읽지 않는다.
- READY는 설치 enabled, restart_required=false, probe ok와 도구 목록, 내장 도구 금지 확인이 모두 참일 때만 된다.
- 알려진 accountbook 서버의 probe `tools`가 비어 있지 않고 각 name이 비어 있지 않은 문자열인지 확인한다. 개별 도구 이름은 코드에 고정하지 않는 것으로 코디네이터가 승인했다.
- `HermesToolsetClient.readEnabled`는 MCP를 열거하지 않으므로 결과가 빈 목록이어야 내장 도구가 모두 닫힌 것으로 본다. `fos-assistant` 서버 보존은 신뢰된 설치기 계약이며 운영에서 실제 호출한다.
- 해제는 토큰 삭제와 plugin disabled 저장을 마치고 DISCONNECTED가 된다. 실패는 PENDING과 비활성화를 남기며 다시 해제할 수 있다.
- 확인만으로 저장된 재시작 대기를 지우지 않는다. ADMIN 반영 완료는 같은 그룹의 대상 사용자를 잠그고 설치·probe·내장 도구를 재검사한다. 해제 연결은 disabled만 검사하고 DISCONNECTED를 유지한다.
- desired_enabled는 env/install 전 단계 성공 후 활성화 후보가 되었는지를 뜻한다. 등록·교체 시작은 false+disabled+PENDING, 외부 반영 모두 성공 뒤 true다. 해제 시작도 false이고 false 상태는 check/confirm에서 READY가 되지 않는다.
- 저장된 restartRequired와 모든 외부 응답 restart_required를 논리 OR로 누적하며 중간 실패에서도 보존한다. 일반 check는 disabled를 확인한 뒤 DISCONNECTED로 바꿀 수 있으나 대기 값은 유지한다.

## 작업 항목

### 1. backend 연결 도메인과 저장

`AccountbookConnection`에 docs 필드를 저장한다. 사용자 기본키와 에이전트 유니크/FK를 둔다.
`Agent.connectorManaged`와 marker 메서드를 추가한다.
일반 에이전트 편집, 도구 변경, admin update를 막고 일반 삭제와 공개는 `isEditableBy`가 막는다.
profile 이름은 요청에서 받지 않고 바인딩 에이전트에서만 읽는다.

### 2. 등록과 확인, 해제

`AccountbookConnectionService`로 docs API와 상태 저장을 구현한다.
`AccountbookProperties`는 `ACCOUNTBOOK_API_BASE_URL`을 환경 변수로 받고 기본값은 비운다.
token은 `fab_`와 base64url 43자이며 familyUuid는 UUID 형식이다.
새 오류는 `ACCOUNTBOOK_TOKEN_REJECTED`(400), `ACCOUNTBOOK_FAMILY_FORBIDDEN`(403), `ACCOUNTBOOK_UNAVAILABLE`(503), `CONNECTOR_OPERATION_FAILED`(502)다.
`AccountbookTokenVerifier`와 HTTP 구현은 `/families`를 호출해 유효성과 가족 권한을 확인한다.
HTTP 제한 시간을 주고 redirect를 따르지 않으며 실제 주소는 코드에 쓰지 않는다.
부재 설정은 기능만 503으로 닫고 기존 서버 기동은 유지한다.

### 3. Hermes 포트

`HermesConnectorClient`와 HTTP 구현은 다음을 호출한다.
`PUT /api/connectors`의 본문은 profile, plugin= fos-accountbook, enabled이고
응답은 profile, plugin, enabled, restart_required다.
`GET /api/connectors?profile=`로 상태를 다시 읽고
GET은 profile과 connectors 배열이며 각 항목은 plugin, enabled, configured다. 미설치는 false/false이고 restart_required는 없다.
`POST /api/mcp/servers/accountbook/test?profile=`의 ok와 tools 배열을 검사한다.
`DELETE /api/env`로 ACCOUNTBOOK_API_TOKEN을 실제 제거하며 404는 이미 제거된 것으로 본다.
가족 미선택으로 교체하거나 해제할 때 ACCOUNTBOOK_FAMILY_UUID도 DELETE하고 404는 성공으로 본다.
해제는 token 삭제, family 삭제, plugin disabled 순서로 하고 모든 실패에서 에이전트는 꺼진 채 남는다.
기존 `HermesDashboardClient.putEnv`의 예외 로그가 응답 본문을 노출할 수 있으므로
새 토큰 쓰기는 새 클라이언트에서 외부 exception/cause 없이 고정 오류로 처리한다.
토큰 교체 시 restart_required를 존중한다.
env PUT/DELETE 응답의 restart_required도 모아 저장한다.
ADMIN 목록과 반영 완료 경로는 docs의 확정 계약을 따른다. 목록에는 다른 사용자 토큰 앞부분을 넣지 않는다.

### 4. backend 테스트

`AccountbookConnectionServiceTest`는 두 사용자 격리, token/family 검증 실패, READY/PENDING,
재시작 대기, 토큰 교체 실패 후 비활성화, 해제 실패 재시도와 원문 미저장을 확인한다.
외부 실패 예외 뒤 새 트랜잭션에서 PENDING과 disabled를 다시 읽어 실제 커밋을 확인한다.
생성기 실패는 전용 예외로 바뀌지 않고 agent/connection 저장이 rollback되어 재시도되는지 확인한다.
`HttpHermesConnectorClientTest`는 정확한 요청과 malformed 응답, 원문 포함 오류의 로그/응답 차단을 확인한다.
`HttpAccountbookTokenVerifierTest`는 family 권한, redirect 거절, 인증 오류와 timeout을 확인한다.
`AccountbookConnectionControllerTest`는 인증과 요청 profile 주입 차단, prefix만 반환을 확인한다.
`AccountbookConnectionMigrationTest`는 전체 Flyway migration 실행과 필드/FK를 확인한다.
ADMIN 목록/confirm의 MEMBER 거절과 다른 그룹 차단, 목록/confirm에서 prefix·family 부재를 확인한다.
check의 저장된 restart 대기 보존, pending 반영 완료의 READY 전이, 해제 완료의 DISCONNECTED 유지,
해제 실패와 토큰 교체 중간 실패가 confirm으로 READY가 되지 않는 것을 테스트한다.

## 검증

```bash
cd backend && ./gradlew test
```

모든 기존 검사와 새 테스트가 통과한다. raw token과 token hash 컬럼은 없어야 한다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/connector/domain/AccountbookConnection.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/domain/ConnectionStatus.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/infra/AccountbookConnectionRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/infra/HttpAccountbookTokenVerifier.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/AccountbookProperties.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/AccountbookTokenVerifier.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectionSnapshot.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/AdminConnectionSnapshot.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorOperationFailure.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/AccountbookConnectionService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/presentation/ConnectionDtos.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/presentation/AccountbookConnectionController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/presentation/AccountbookConnectionAdminController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesConnectorClient.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/HttpHermesConnectorClient.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/domain/Agent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentToolService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentAdminController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/main/resources/db/migration/V36__accountbook_connection.sql` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/AccountbookConnectionServiceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/AccountbookConnectionControllerTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/AccountbookConnectionMigrationTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/HttpAccountbookTokenVerifierTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/hermes/HttpHermesConnectorClientTest.java` | 신규 |
