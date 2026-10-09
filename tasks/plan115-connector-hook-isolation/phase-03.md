# Phase 03. 정의가 어긋난 `READY` 바인딩을 주기적으로 찾아 다시 설치하고 관리자에게 알린다

**Execution profile**: deep

## 목표

manifest 가 바뀐 뒤 설치가 다시 보내지지 않아 설치 상태가 어긋난 `READY` 바인딩을 Control Plane 이 스스로 찾아 반영 맞추기를 돌린다.
다시 맞춘 바인딩에 관리자가 할 일이 남으면 그 그룹 관리자마다 알림 한 건을 남긴다. 알림 본문은 공유 gateway 재시작이 필요한지, 반영 완료만 누르면 되는지를 나눈다.

**범위 외**: 대시보드 판정(phase 01), 반영 맞추기의 까닭과 오류 코드(phase 02, 이 phase 는 `ResyncOutcome` 을 쓴다). 운영 저장소의 배포 스크립트는 바꾸지 않는다.

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261009-connector-install-drift.md`, `docs/backend/connector-install.md` 의 「정의 어긋남 점검」 과 「반영 예정 확인」, `docs/backend/notification.md` 의 「알림 종류」 의 `CONNECTOR_REINSTALLED`, `docs/backend/schema/notification.md`

- 본보기는 `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorBindingApplier.java` 의 `applyDue()` 와 `applyDueLocked(DueBinding)` 이다. 대상 번호를 트랜잭션 밖에서 읽고, 바인딩마다 `TransactionTemplate` 으로 트랜잭션을 열어 `users.findByIdForUpdate`, `agents.findByIdForUpdate` 차례로 잠근 뒤 `bindings.findById` 로 다시 읽는다. 카탈로그는 `ConnectorManifests.find(connector, connectorId)` 로 읽고 반영 맞추기는 `service.resync(binding, manifest, false)` 다
- 대상 번호의 본보기는 `backend/src/main/java/com/bifos/assistant/connector/domain/DueBinding.java` 와 `ConnectorBindingRepository.findApplyDue` 의 JPQL 생성자 식이다. 엔티티 칸: `ConnectorBinding` 의 `id`, `agent`, `connection`, `status`(`BindingStatus`), `Agent` 의 `hermesProfile`, `connectorManaged`, `ConnectorConnection` 의 `userId`, `connectorId`
- 설치 상태는 `HermesConnectorClient.readConnector(profile, connectorId)` 가 `ConnectorState` 로 준다. 반영됐는지는 phase 02 의 `ConnectorBindingInstalls.notInstalledReason(state, legacy)` 로 판정한다(`READY` 면 어긋나지 않음)
- 설정은 `ConnectorBindingProperties`(`assistant.connector.binding`, 지금은 `applyDelay` 하나)와 `backend/src/main/resources/application.yml` 의 `apply-cron`, 검사의 `backend/src/test/resources/application-test.yml` 의 `apply-cron: "-"` 다
- 관리자 알림의 본보기는 `backend/src/main/java/com/bifos/assistant/agent/application/ToolsetRequestService.java` 의 `users.findByGroupIdAndRole(groupId, UserRole.ADMIN)` 과 `access.revoked(admin.email())`(`SignInRevocation`) 거르기, `notifications.notify(userId, kind, title, body, target)` 다. `NotificationService.notify` 는 `Propagation.MANDATORY` 라 트랜잭션 안에서 부른다
- `NotificationTarget(type, publicId)` 는 둘 다 null 이 아니어야 한다. 목록 화면인 `ADMIN_CONNECTIONS` 만 `publicId` 를 비울 수 있게 바꾼다
- 화면: `web/src/lib/notification.ts` 의 `NotificationKind`, `NotificationTargetType`, `notificationHref`. 관리자 연결 화면 주소는 `/admin/connections` 다

## 의도 메모

- 대상을 `READY` 로 한정해 같은 어긋남에 설치를 되풀이해 보내지 않는다. 다시 맞춘 바인딩은 `READY` 가 아니다
- 연속 어긋남 횟수와 다음 점검 위치는 이 컴포넌트의 필드(JVM 메모리)다. `ConnectorCatalogCache` 와 같은 한 대 전제다
- 외부 상태 읽기는 트랜잭션 밖에서 한다. 사용자 행과 에이전트 행 잠금을 쥔 채 바인딩마다 대시보드를 부르지 않는다
- 알림은 한 주기 끝에 별도 트랜잭션에서 만든다. 실패해도 바인딩 상태는 이미 커밋돼 관리자 목록에 보인다. 문서 `notification.md` 가 이 예외를 적는다
- 기각: 운영 동기화 스크립트가 Control Plane 을 부르게 하는 안, 기동 때 한 번만 보는 안(ADR 「대안 기각」)

## 작업 항목

### 1. 설정

- `ConnectorBindingProperties` 에 `@DefaultValue("20") int driftBatch` 를 더하고 1 미만이면 기동을 멈춘다(기존 `applyDelay` 검사와 같은 모양)
- `application.yml` 의 `binding:` 아래에 `drift-cron: "0 */10 * * * *"` 과 주석, `application-test.yml` 에 `drift-cron: "-"` 를 더한다

### 2. 대상 읽기

- `backend/src/main/java/com/bifos/assistant/connector/domain/ReadyBinding.java` 신규 record: `(Long bindingId, Long agentId, Long userId, String profile, String connectorId, boolean legacy)`
- `ConnectorBindingRepository.findReadyAfter(@Param("status") BindingStatus status, @Param("after") Long after, Pageable page)`: `b.status = :status and b.id > :after order by b.id` 를 `ReadyBinding` 생성자 식으로 고른다. `RepositoryQueryMysqlTest` 가 저절로 실행한다

### 3. `ConnectorBindingApplier`

- 필드: `NotificationService notifications`, `SignInRevocation access`, `ConnectorBindingProperties properties`, 메모리 상태 `long driftCursor`, `Map<Long, Integer> driftStreak`
- `@Scheduled(cron = "${assistant.connector.binding.drift-cron}") public void runDriftScheduled()` 가 `resyncDrifted()` 를 부른다
- `public int resyncDrifted()` 는 다시 맞추기를 돌린 바인딩 수를 돌려준다
  1. `findReadyAfter(READY, driftCursor, PageRequest.of(0, driftBatch))`. 받은 수가 상한보다 적으면 다음 위치를 0 으로, 아니면 마지막 번호로 둔다
  2. 바인딩마다 `connector.readConnector(profile, connectorId)` 를 부른다. 예외면 `"connector {} failed at drift-check: {}"` 경고를 남기고 건너뛴다. `notInstalledReason` 이 `READY` 면 그 번호의 연속 횟수를 지우고 건너뛴다
  3. 어긋났으면 `"connector {} drifted: {}"` 로 커넥터 id 와 까닭 이름을 남기고 연속 횟수를 하나 올린다
  4. 바인딩마다 트랜잭션을 열고 `applyDueLocked` 와 같은 차례로 잠근 뒤 바인딩을 다시 읽는다. 없거나 `READY` 가 아니거나 잠금 확인(사용자, 에이전트 주인)이 실패하면 건너뛴다
  5. 연속 횟수가 3 이상이면 설치를 보내지 않고 `binding.pending(now)` 만 저장하고 결과를 `PENDING` 으로 센다. 아니면 카탈로그를 읽고(예외면 `applyDueLocked` 처럼 `PENDING` 으로 두고 경고) `service.resync(binding, manifest, false)` 를 부른다
  6. 결과가 `READY` 나 `APPLY_SCHEDULED` 가 아니면 잠근 사용자의 `groupId()` 아래에 재시작 대기 여부(`binding.restartRequired()`)와 함께 센다
  7. 주기 끝에 센 그룹마다 한 트랜잭션에서 그 그룹의 차단되지 않은 관리자마다 알림을 만든다. 알림 만들기가 실패하면 경고만 남긴다
- 알림 문구
  - 제목: 「연결 설치를 다시 맞췄어요」
  - 재시작 대기가 하나라도 있으면: 「커넥터 정의가 바뀌어 연결 {n}개를 다시 설치했어요. 공유 gateway 를 재시작한 뒤 「연결 반영 확인」에서 반영 완료를 눌러 주세요.」
  - 아니면: 「커넥터 정의가 바뀐 연결 {n}개의 반영을 확인하지 못했어요. 「연결 반영 확인」에서 반영 완료를 눌러 다시 확인해 주세요.」
  - 대상: `new NotificationTarget(NotificationTargetType.ADMIN_CONNECTIONS, null)`
- 클래스 Javadoc 에 정의 어긋남 점검을 더한다

### 4. 알림 종류

- `NotificationKind` 에 `CONNECTOR_REINSTALLED`, `NotificationTargetType` 에 `ADMIN_CONNECTIONS` 를 Javadoc 과 함께 더한다. 칸이 `VARCHAR(32)`, `VARCHAR(20)` 문자열이라 마이그레이션은 없다
- `NotificationTarget` 의 검사를 `type == null || (publicId == null && type != NotificationTargetType.ADMIN_CONNECTIONS)` 로 바꾸고 Javadoc 을 고친다. 엔티티 `Notification` 과 응답 변환이 `publicId` null 을 받는지 확인하고, 받지 못하면 같은 커밋에서 고친다
- `web/src/lib/notification.ts`: 두 union 에 값을 더하고 `notificationHref` 가 `ADMIN_CONNECTIONS` 면 `"/admin/connections"` 를 돌려준다

### 5. 시험

- `backend/src/test/java/com/bifos/assistant/connector/ConnectorBindingDriftTest.java` 신규. 준비는 `ConnectorBindingServiceTest` 의 Spring 시험 구성과 가짜 `HermesConnectorClient` 를 따른다
  - 같은 profile 에 바인딩 둘이 `READY` 이고 하나의 설치만 `configured: false` 일 때 `resyncDrifted()` 가 그 하나에만 설치를 다시 보내고(`bindConnector` 호출 한 번), 그 바인딩은 재시작 대기 `PENDING`, 다른 바인딩은 `READY` 다. 그룹 관리자에게 `CONNECTOR_REINSTALLED` 알림 한 건이 생기고 본문에 「공유 gateway 를 재시작한 뒤」 가 있다. 차단된 관리자와 `MEMBER` 역할 사용자에게는 생기지 않는다
  - 다시 부르면 그 바인딩은 `READY` 가 아니어서 설치를 다시 보내지 않고 알림도 늘지 않는다
  - 다시 보낸 설치가 바뀐 것이 없다고 답하고 probe 가 실패하면 `PENDING` 이고 알림 본문이 반영 완료만 안내한다
  - 다시 보낸 설치가 `reload_pending` 이면 알림을 만들지 않는다
  - 설치 상태 읽기가 예외면 그 바인딩만 건너뛰고 `READY` 로 남는다
  - 다시 맞춰 `READY` 가 된 뒤 또 어긋나기를 세 번 되풀이하면 세 번째는 설치를 보내지 않고 `PENDING` 이다
  - `drift-batch` 를 1 로 둔 시험 구성(`@TestPropertySource`)에서 두 번 부르면 두 바인딩을 차례로 하나씩 본다
- `test/unit/notification.test.ts` 에 `ADMIN_CONNECTIONS` 가 `/admin/connections` 로 가는 시험을 더한다
- `ConnectorPropertiesTest` 에 `drift-batch` 가 0 이면 기동이 멈추는 시험을 더한다(그 파일이 `ConnectorBindingProperties` 를 다루지 않으면 이 항목은 `ConnectorBindingDriftTest` 에서 생성자로 확인한다)

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.connector.*' --tests 'com.bifos.assistant.notification.*' --tests 'com.bifos.assistant.architecture.*'
cd backend && ./gradlew qualityCheck
node --test test/unit/notification.test.ts
pnpm --dir web typecheck
```

모두 종료 코드 0 이어야 한다. `RepositoryQueryMysqlTest` 는 Docker 가 있어야 돈다. 없으면 CI 가 확인한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/connector/domain/ReadyBinding.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/infra/ConnectorBindingRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorBindingApplier.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorBindingProperties.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/notification/domain/type/NotificationKind.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/notification/domain/type/NotificationTargetType.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/notification/domain/NotificationTarget.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/resources/application-test.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorBindingDriftTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorPropertiesTest.java` | 수정 |
| `web/src/lib/notification.ts` | 수정 |
| `test/unit/notification.test.ts` | 수정 |
