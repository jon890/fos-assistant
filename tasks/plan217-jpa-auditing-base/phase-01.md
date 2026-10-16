# Phase 01. 저장 시각을 공통 auditing으로 옮긴다

**Execution profile**: deep

## 목표

기존 created_at을 가진 46개 엔티티 중 순수 저장 시각을 가진 33개를 공통 base로 기록하고, 업무 시각과 과거 행의 NULL을 보존한다.

**범위 외**: 행위자 auditing, created_at이 없는 21개 엔티티의 새 감사 칸, 제외한 업무 시각의 자동화, 금융 상한 구조 변경, 운영 접근과 배포다.

## 컨텍스트

**근거 문서**: `backend/docs/adr/ADR-20261016-jpa-auditing-base.md`가 대상과 제외 목록, bulk 책임과 NULL 정책을 갖는다.
현재 `backend/docs/code-architecture.md`의 「JPA 생성 시각」과 `backend/AGENTS.md`는 MemoryCollection 하나로 제한한다.
`backend/src/main/java/com/bifos/assistant/shared/config/JpaAuditingConfig.java`의 Clock/MICROS provider를 재사용한다.
`MemoryCollectionAuditingTest`와 MySQL 하위 클래스는 실제 callback·복합 ID merge·rollback의 기준이다.

## Blocked 조건

- 코디네이터의 「구현 시작」 회신 전에는 코드·스키마·검사를 변경하지 않는다.
- 회신 뒤 최신 main을 merge한다. #551의 금융 상한이 AppUser로 옮겨진 결과와 겹치는 엔티티 변경을 먼저 확인한다.
- 운영 코드 1500줄 상한을 넘으면 자동 예외 라벨 없이 코디네이터에게 분할 판단을 요청한다.

## 의도 메모

- 새 의존성, 시계, AuditorAware를 만들지 않는다. base에 id·권한·행위자를 넣지 않는다.
- 기존 행의 updatedAt은 모르는 과거 값이므로 NULL을 유지한다. 마이그레이션 DML과 NOT NULL 강화는 없다.
- UI·HTTP 계약·제품 범위는 바뀌지 않아 PRD와 기능 흐름 문서는 수정하지 않는다.
- rollback과 동시 요청은 기존 트랜잭션·잠금 경계를 사용한다. no-op은 감사 시각을 갱신하지 않는다.
- ADR 전수 표의 생성 전용 27개, 생성·변경 6개, 제외 13개를 각각 구현한다. 생성 시각만 순수하면 업무 updatedAt은 엔티티에 남긴다.
- 기존 U가 NOT NULL인 AttentionControlEntry/Task/TaskTrigger는 AttributeOverride로 매핑을 유지한다. 새 세 U만 nullable이다.
- 불변 기록에도 순수 저장 C는 base로 옮기되 U는 만들지 않는다. 재설계 예정 proactive도 기존 순수 C는 이동하고 새 칸은 만들지 않는다.

## 작업 항목

### 1. 기존 동작의 회귀를 고정한다

`MemoryCollectionAuditingTest`를 먼저 실행한다. `JpaAuditingPlacementTest`는 실제 annotation을 컴파일한 fixture로 ADR의 33개 엔티티와 두 base의 허용 선언, 누락·중복·오상속·업무 필드·getter의 auditing을 검사하도록 기존 placement 검사를 옮겨 확장한다.
생성 전용 27개가 AuditedEntity를 상속해도 실패해야 한다. C+U 대상은 Agent/AppUser/AllowedPerson/AttentionControlEntry/Task/TaskTrigger 여섯 개다.
created_at이 있는 46개 중 전수 표에서 빠진 엔티티가 있으면 실패시켜 표 갱신을 요구한다.
제외 13개 C와 Conversation.updatedAt, 두 커넥터의 updatedAt, occurredAt, changedAt, revision과 큐의 claim·started·finished에 annotation을 추가하면 실패하는 사례를 둔다.
기존 감사 규칙의 설정·provider 수, Clock 인자, Auditable 상속 금지를 빠뜨리지 않는다.

### 2. base와 세 감사 칸을 적용한다

`shared.domain.CreatedAtEntity`와 `AuditedEntity`를 ADR대로 만든다.
ADR 전수 표의 27개는 생성 전용, 여섯 개는 생성·변경 base를 상속하고 중복 필드를 지운다.
순수 저장 시각만을 위한 factory·변경 메서드 인자와 그 호출부를 함께 고친다. 업무 시각도 계산하는 now 인자는 제거하지 않는다.
Conversation/ConnectorConnection/ConnectorBinding/Memory/ResultDelivery/UserBrowser의 수동 업무 updatedAt은 남긴다.
MemoryCollection/AgentMemoryCollection의 신규 복합 ID save/merge는 반환 managed 객체를 사용한다. 기존 관계의 민감 허용 변경은 현재 행을 수정하고 C를 보존한다.
ExecutionContextSource의 Persistable·isNew·PostLoad/PostPersist를 유지한다. 같은 position의 새 삽입은 기존처럼 PK 오류로 거절한다.
FinancialLimitChange의 지정 actionId와 재시도는 기존 행 재사용으로 처리한다. C가 NULL인 새 객체로 기존 ID를 merge하는 경로는 만들지 않는다.
새 SQL 파일 이름의 버전은 작성 순간 UTC 시각으로 정하고 `__add_entity_audit_updated_at.sql`로 끝낸다.
기존 마이그레이션은 고치지 않는다. 추가 SQL은 ADR의 세 nullable DATETIME(6) 칸만 가진다.
AppUserRepository.updateModelDefaultTier는 시각 인자를 받고 실제 값이 달라질 때 updatedAt과 값을 함께 갱신한다.
ModelTierService가 기존 Clock의 MICROS 값을 넘기게 한다. 갱신 수 0은 사용자 존재 여부로 no-op과 사용자 없음 오류를 구분한다.
AllowedPerson의 로그인 bulk는 updatedAt을 건드리지 않는다.
TaskRepository의 대화 연결 bulk는 기존처럼 U를 명시하며 인자를 MICROS로 맞춘다.
ADR의 나머지 bulk는 업무 필드만 갱신하고 C를 보존한다. stale 영속성 문맥을 flush해 bulk 값을 덮어쓰는 경로를 회귀로 막는다.

### 3. 저장과 마이그레이션을 검사한다

`JpaAuditingTest`는 고정 시계 `.123456789`를 사용해 33개 대상 모두 새 행의 C가 `.123456`인지 ORM/JDBC로 재조회한다.
기존 각 엔티티의 실제 factory를 사용하는 JpaAuditingEntityFixtures로 유효한 선행 행과 객체를 만든다. callback을 모방한 mock 시각으로 통과시키지 않는다.
Agent 설정 변경, AppUser rename과 모델 기본값 bulk, AllowedPerson disable/enable, AttentionControlEntry hide/snooze, Task 설정, TaskTrigger 일정 변경을 저장하고 C 보존·U 이동을 검증한다.
같은 값·no-op flush·조회·로그인 bulk에서 updatedAt 보존, 기존 NULL 행의 첫 변경, detached merge와 rollback을 확인한다.
생성 전용 27개는 dirty update·detach/merge·bulk 후 C를 보존하고, 스키마에 뜻밖의 U가 생기지 않아야 한다.
MemoryCollection/AgentMemoryCollection의 신규 save/merge·기존 detached merge·권한 재부여와 ExecutionContextSource의 중복 PK 거절을 별도로 확인한다.
FinancialLimitChange의 지정 ID 신규 merge 반환값과 기존 요청 재조회 C를 확인한다. 불변 행에 불필요한 U가 생기지 않아야 한다.
별도 업무 시계를 전달한 Conversation/커넥터 U, revision, 사건, 큐 시각이 auditing 시계와 달라도 그대로 저장되는지 검증한다.
`JpaAuditingMysqlTest`는 같은 검사를 실제 Flyway MySQL 스키마에서 실행한다.
`JpaAuditingMigrationTest`는 전체 Flyway 적용과 추가 직전 버전에 행을 넣은 뒤 업그레이드하는 경로에서 새 칸 NULL과 기존 값 보존을 검사한다.
SQL은 DDL만 추가하므로 MySQL DML 전용 검사는 필요 없고, 실제 MySQL callback 검사와 MysqlMigrationTest의 validate로 매핑을 확인한다.
공용 테스트 컨텍스트·TestClock을 사용하고 새 Spring 컨텍스트와 시계 빈을 만들지 않는다.

### 4. 책임 문서를 구현에 맞추고 계획서를 지운다

backend의 architecture·schema·AGENTS에 실제 base 목록, 새 NULL의 뜻, bulk 예외와 실행할 검사를 반영한다.
오래 남는 결정은 ADR에 두고, 구현과 검증이 끝나면 이 계획 디렉터리를 구현 PR에서 지운다.
기능 커밋 뒤 Spotless가 만든 포맷만 별도 커밋으로 분리한다.

## 검증

```bash
cd backend && ./gradlew test --tests '*MemoryCollectionAuditingTest' --tests '*JpaAuditingTest' --tests '*JpaAuditingMigrationTest'
cd backend && ./gradlew archTest
scripts/checks/check-mysql-migration.sh --tests '*JpaAuditingMysqlTest' --tests '*MemoryCollectionAuditingMysqlTest' --tests '*MysqlMigrationTest'
scripts/dev/check-local.sh --fast
```

check-local의 --fast는 공개 정보·파일 길이·마이그레이션 버전·웹 typecheck와 단위 검사·backend 품질 검사를 실행하고 첫 실패를 0이 아닌 종료 코드로 반환한다.
MySQL 스크립트는 mysql 태그를 포함하는 mysqlMigrationTest에 필터를 전달한다. 실행 건수와 skip 여부도 확인한다.
무거운 전체 backend 회귀는 Draft CI가 담당한다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/shared/domain/CreatedAtEntity.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/shared/domain/AuditedEntity.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/**/*.java` | 수정 (ADR의 33개 엔티티와 factory·변경 메서드 호출부, ModelTierService 및 bulk 시각 인자) |
| `backend/src/main/resources/db/migration/V*__add_entity_audit_updated_at.sql` | 신규 |
| `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/architecture/MemoryCollectionAuditingPlacementTest.java` | 삭제 |
| `backend/src/test/java/com/bifos/assistant/architecture/JpaAuditingPlacementTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/shared/JpaAuditingTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/shared/JpaAuditingMysqlTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/shared/JpaAuditingMigrationTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/shared/JpaAuditingEntityFixtures.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/**/*.java` | 수정 (factory와 변경 메서드를 쓰는 검사의 인자를 맞춘다) |
| `backend/docs/code-architecture.md` | 수정 |
| `backend/docs/data-schema.md` | 수정 |
| `backend/AGENTS.md` | 수정 |
| `backend/docs/adr/ADR-20261016-jpa-auditing-base.md` | 수정 (구현에서 확정한 경계 반영) |
