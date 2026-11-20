# Phase 02. 운영 조회와 연결 응답의 실패 칸

**Execution profile**: standard

## 목표

운영 정기 작업이 공유 gateway 재시작 필요 여부와 진행 중 실행 수를 읽는 조회를 열고, 연결 응답이 자동 반영 실패를 알려 화면이 문구를 고르게 한다.

**범위 외**: 재시작 판단과 6시간 상한(운영 정기 작업, `fos-home-infra`), 화면(phase 03).

## 컨텍스트

**요구사항**: REQ5, REQ9

**근거 문서**: `docs/features/connector/spec.md`(DECISION5, DECISION13, DECISION15), `docs/features/connector/design.md` 의 「바쁜가」, 「Control Plane: 운영 조회(PR3)」, 「Control Plane: 알림과 연결 응답(PR3)」

- 서비스 Bearer 인증의 본보기는 `ConnectorGuardSupport.authenticate`(`MessageDigest.isEqual` 비교)와 `ConnectorGuardSupportController` 다. 경로는 `SecurityConfig` 의 `permitAll` 과 `ControlPlaneJwtFilter.shouldNotFilter` 에 함께 넣는다.
- 진행 중 실행 수는 `UserExecutionLimiter` 의 turn 자리(`turnSlots`), 원격 종료 확인 자리(`remoteEndHolds`), 대화 turn 의 루트가 아닌 `RUNNING` 줄의 합이다. `AgentExecutionRepository` 는 길이 상한(500줄)에 닿아 새 쿼리를 더하지 않고 `findByStatus(RUNNING)` 의 결과에서 루트가 아닌 줄만 센다.
- 연결 응답 DTO 는 `AgentConnectionView`, `BoundAgentSummary`, `AdminConnectionSnapshot` 과 그 presentation 쪽 `ConnectionDtos`, `ConnectionAdminDtos` 다.

## 의도 메모

- 토큰은 `assistant.connector.binding.ops-token`(환경 변수 `CONNECTOR_OPS_TOKEN`)으로 받는다. 비어 있으면 조회는 늘 401 이라 기동을 막지 않는다. 값과 자리는 `fos-home-infra` 가 갖는다.
- 응답의 `restartWaiting` 은 재시작 감지(`findRestartWaiting`)처럼 실패 표시가 없고 껐다 켜기 단계가 아니며 `restart_required_since` 가 있는 재시작 대기 바인딩 수다. 풀리지 않는 줄을 세면 운영 작업이 재시작을 되풀이한다. `oldestWaitingSince` 는 그 가운데 가장 이른 `restart_required_since` 다.
- 진행 중 실행 수는 turn 자리, 원격 종료 확인 자리, `RUNNING` 줄 전부의 합이다. 루트 줄이 turn 자리와 겹쳐 많이 세지만, 많이 세면 재시작이 늦어질 뿐이다.
- 토큰은 `ConnectorOpsProperties`(toString 이 원문을 가린다)에 둔다. `docs/code-architecture.md` 의 「비밀값을 두는 곳」 과 `docs/self-hosting.md` 에 적는다.
- 「다시 확인」 단추를 보일지는 서버가 정해 관리자 목록의 `recheckable` 로 낸다. 24시간 기준이 알림과 같은 설정을 쓰게 하려는 것이다.

## 작업 항목

### 1. `UserExecutionLimiter.runningTotal()`

모든 사용자의 turn 자리, 원격 종료 확인 자리, 루트가 아닌 `RUNNING` 줄(`parentExecutionId != null` 이거나 `conversationId == null`)의 합.

### 2. 운영 조회 `ConnectorRestartOpsController`(신규, `connector/connection/presentation`)와 `ConnectorRestartOps`(신규, application)

`GET /internal/ops/connector-restart`. 응답 `{"restartWaiting", "oldestWaitingSince", "runningExecutions"}`. 저장소 `countRestartWaiting`, `findOldestRestartWaitingSince` 를 더한다.

### 3. 연결 응답의 `applyFailed` 와 `waitingSince`

`AgentConnectionView`, `BoundAgentSummary`, `AdminConnectionSnapshot` 과 응답 record 에 `applyFailed` 를, 관리자 목록에는 `waitingSince` 도 더한다.

### 4. 테스트

- 신규 `backend/src/test/java/com/bifos/assistant/connector/ConnectorRestartOpsTest.java`: 토큰 없음과 틀린 토큰 401, 대기 없음과 있음, 실패와 껐다 켜기 바인딩은 세지 않음, 실행이 돌 때와 안 돌 때의 수.
- 기존 연결 응답 테스트(`AgentConnectionControllerTest`, `ConnectorConnectionControllerTest`, 관리자 목록 테스트)에 `applyFailed` 를 단언한다.

## 검증

```bash
cd backend && ./gradlew test --tests '*ConnectorRestartOpsTest' --tests '*AgentConnectionControllerTest' --tests '*ConnectorConnectionControllerTest' --tests '*UserExecutionLimiter*'
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/usage/execution/application/UserExecutionLimiter.java` | 수정 (전체 실행 수) |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/ConnectorRestartOps.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/presentation/ConnectorRestartOpsController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/infra/ConnectorBindingRepository.java` | 수정 (대기 수 조회) |
| `backend/src/main/java/com/bifos/assistant/connector/connection/config/ConnectorBindingProperties.java` | 수정 (운영 토큰) |
| `backend/src/main/resources/application.yml` | 수정 (운영 토큰 환경 변수) |
| `backend/src/main/java/com/bifos/assistant/shared/config/SecurityConfig.java` | 수정 (경로 허용) |
| `backend/src/main/java/com/bifos/assistant/shared/auth/ControlPlaneJwtFilter.java` | 수정 (경로 제외) |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/dto/AgentConnectionView.java` | 수정 (`applyFailed`) |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/dto/BoundAgentSummary.java` | 수정 (`applyFailed`) |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/dto/AdminConnectionSnapshot.java` | 수정 (`applyFailed`, `waitingSince`) |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/ConnectorBindingService.java` | 수정 (view 생성) |
| `backend/src/main/java/com/bifos/assistant/connector/connection/presentation/ConnectionDtos.java` | 수정 (응답 칸) |
| `backend/src/main/java/com/bifos/assistant/admin/connector/presentation/ConnectionAdminDtos.java` | 수정 (응답 칸) |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorRestartOpsTest.java` | 신규 |
