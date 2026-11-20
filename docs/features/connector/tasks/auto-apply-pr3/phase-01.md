# Phase 01. 바인딩의 대기 시각과 관리자 알림 한 번 보내기

**Execution profile**: standard

## 목표

자동 반영이 실패했거나 대기가 생긴 뒤 24시간 안에 `READY` 가 되지 않은 바인딩에만 그룹 관리자 알림을 한 번 보낸다.
자동으로 끝날 대기(껐다 켜기, 재시작 대기, 반영 예정)에는 바로 알리지 않는다.

**범위 외**: 운영 조회와 연결 응답의 실패 칸(phase 02), 화면 문구(phase 03).

## 컨텍스트

**요구사항**: REQ8

**근거 문서**: `docs/features/connector/spec.md`(DECISION8, DECISION17), `docs/features/connector/design.md` 의 「Control Plane: 바인딩 칸과 단계」, 「Control Plane: 알림과 연결 응답(PR3)」

- 바인딩 엔티티는 `backend/src/main/java/com/bifos/assistant/connector/connection/domain/ConnectorBinding.java` 다. PR1 이 `cycle_phase`, `cycle_at`, `apply_failed_at` 을 더했다(`V20261105074428__connector_binding_cycle.sql` 이 마이그레이션 본보기).
- 지금 관리자 알림은 정의 어긋남 점검(`ConnectorBindingApplier.resyncDrifted` 의 `notifyAdmins`)만 보내고, 재시작 대기에도 보낸다. 실패 표시(`markApplyFailed`)를 적는 곳은 `ConnectorBindingInstalls.record`, `ConnectorBindingApplier`(반영 예정 확인의 카탈로그 실패, 정의 어긋남의 연속 상한과 카탈로그 실패), `ConnectorBindingCycles` 다.
- 사람이 시작한 설치는 `ConnectorInstallAttempts.begin` 의 `!automatic` 경로(`resumeAfterFailure`)다.
- 예약 작업은 `ConnectorBindingApplier.runScheduled` 가 30초마다 차례로 부른다.

## 의도 메모

- 실패 알림을 결과 트랜잭션 뒤에 바로 보내지 않고, 30초 주기의 알림 단계 하나가 실패 표시와 24시간 경과를 함께 본다. 실패 표시를 적는 곳이 다섯 군데라 커밋 뒤 콜백을 곳곳에 두는 것보다 한 단계가 `admin_notified_at` 으로 한 번만 보내기를 지키기 쉽다. 알림이 최대 30초 늦는 것은 관리자에게 차이가 없다. 이 차이는 설계 문서 「알림과 연결 응답」 에 고쳐 적는다.
- `waiting_since` 는 결과 적용 뒤 바인딩이 `PENDING` 이고 대기 까닭(재시작 대기, 껐다 켜기 단계, 반영 예정, 실패 표시) 하나라도 있으면 비어 있을 때만 적는다. `ready()` 와 사람이 시작한 설치가 `waiting_since` 와 `admin_notified_at` 을 함께 비운다.
- 알림 단계는 바인딩마다 트랜잭션을 열고 연결 사용자 행, 에이전트 행 차례로 잠근 뒤 다시 읽는다. 다른 예약 작업과 같은 순서라 결과 트랜잭션이 커밋한 상태를 옛 값으로 덮지 않는다. 알림은 바인딩마다 그룹 관리자에게 한 건이다.
- 앞 마이그레이션 전부터 `PENDING` 이던 줄은 `waiting_since` 가 비어 알림과 단추를 받지 못한다. DML 파일 하나가 `COALESCE(restart_required_since, cycle_at, updated_at)` 로 채운다. 배포 직후 24시간이 넘은 옛 대기와 PR1 이 남긴 실패 줄에 알림이 한 번 간다.
- 정의 어긋남 점검의 그룹별 알림(`AdminTodo`, `Drifted.adminTodo`)은 지운다. 실패면 실패 표시로, 대기면 24시간으로 같은 알림 단계가 맡는다.

## 작업 항목

### 1. `backend/src/main/resources/db/migration/V20261105074437__connector_binding_waiting.sql`

`agent_connector_binding` 에 `waiting_since DATETIME(6) NULL`, `admin_notified_at DATETIME(6) NULL` 을 COMMENT 와 함께 더한다. 번호는 `orch queue reserve` 로 받은 것이다.

### 2. `ConnectorBinding` 의 칸과 메서드

- 칸 `waitingSince`, `adminNotifiedAt`.
- `trackWaiting(Instant now)`: `PENDING` 이고 대기 까닭이 있으며 `waitingSince` 가 비었으면 적는다.
- `ready()` 와 새 `restartWaiting(Instant now)`(사람이 시작한 설치가 부름)가 두 칸을 비운다.
- `adminNotified(Instant now)`.

### 3. 대기 시각을 적는 자리

`ConnectorBindingInstalls.record(binding, attempt, result)` 끝에서 `trackWaiting(now)` 를 부르고, `markApplyFailed` 가 대기 시각이 비었으면 함께 적는다. 사람이 시작한 설치가 부르는 `resumeAfterFailure` 가 두 칸을 비운다.

### 4. 알림 단계 `ConnectorBindingAlerts`(신규, `connector/connection/application`)

- 저장소 `findAdminAlertDue(now, before)`: `admin_notified_at is null` 이고 `status = PENDING` 이며 `apply_failed_at is not null` 이거나 `waiting_since <= before` 인 바인딩의 번호, 연결 사용자 번호를 읽는다.
- 그룹마다 트랜잭션 하나에서 바인딩을 다시 읽어 조건이 그대로인 것만 `adminNotified(now)` 로 적고, 차단되지 않은 관리자마다 `CONNECTOR_REINSTALLED` 알림 한 건을 남긴다. 본문은 실패 수와 24시간 남은 수를 나눠 「연결 반영 확인」 에서 「다시 확인」 을 누르라고 쓴다.
- 기준 시간은 `ConnectorBindingProperties.adminAlertAfter`(기본 24h)로 둔다.
- `ConnectorBindingApplier.runScheduled` 끝에서 부른다. 정의 어긋남 점검의 알림 코드는 지운다.

### 5. 테스트

- 신규 `backend/src/test/java/com/bifos/assistant/connector/ConnectorBindingAlertTest.java`: 고정 시계로 24시간 직전과 직후, 단계가 바뀌어도 `waiting_since` 가 이어지는지, 두 번 돌려도 한 건, 실패 표시가 있는 바인딩에 바로 한 건, 사람이 시작한 설치 뒤 다시 실패하면 새로 한 건, `READY` 가 되면 칸이 비는지.
- `ConnectorBindingDriftTest` 의 알림 단언을 고친다. 재시작 대기로 다시 설치한 경우는 알림이 없고, 연속 상한으로 실패 표시가 남은 경우는 알림 단계가 한 건을 보낸다.

## 검증

```bash
cd backend && ./gradlew test --tests '*ConnectorBindingAlertTest' --tests '*ConnectorBindingDriftTest' --tests '*ConnectorBindingCycleTest' --tests '*ConnectorGatewayRestartTest' --tests '*ConnectorBindingPropertiesBindingTest'
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V20261105074437__connector_binding_waiting.sql` | 신규 |
| `backend/src/main/resources/db/migration/V20261105074438__connector_binding_waiting_backfill.sql` | 신규 (기존 PENDING 줄의 대기 시각) |
| `backend/src/main/resources/application.yml` | 수정 (알림 기준 시간) |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorBindingCycleTest.java` | 수정 (대기 시각 이어짐, 알림 없음) |
| `backend/src/main/java/com/bifos/assistant/connector/connection/domain/ConnectorBinding.java` | 수정 (칸과 메서드) |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/ConnectorBindingInstalls.java` | 수정 (대기 시각) |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/ConnectorBindingCycles.java` | 수정 (대기 시각) |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/ConnectorInstallAttempts.java` | 수정 (사람 설치가 대기 시각을 비움) |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/ConnectorBindingApplier.java` | 수정 (어긋남 알림 제거, 알림 단계 호출) |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/ConnectorBindingAlerts.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/infra/ConnectorBindingRepository.java` | 수정 (알림 대상 조회) |
| `backend/src/main/java/com/bifos/assistant/connector/connection/config/ConnectorBindingProperties.java` | 수정 (알림 기준 시간) |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorBindingAlertTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorBindingDriftTest.java` | 수정 (알림 단언) |
| `backend/docs/data-schema.md` | 수정 (새 칸) |
