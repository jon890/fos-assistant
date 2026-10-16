## ADR-20261016 / jpa-auditing-base: 저장 시각은 공통 base에서 기록하고 업무 시각은 명시적으로 전달한다

- **status**: `accepted`
- Date: 2026-10-16

### 맥락

현재 JPA auditing은 `MemoryCollection.createdAt` 하나에만 적용한다.
`Agent`, `AppUser`, `AllowedPerson`에는 생성 시각만 있어 설정의 마지막 변경 시각을 알 수 없다.
반면 대화의 활동, 커넥터의 유효성 비교, revision과 큐의 시각은 저장 callback 시각과 뜻이 다르다.
필드 이름만 보고 auditing을 적용하면 기존 동작이 달라진다.

### 결정

기존 `shared.config.JpaAuditingConfig`의 `Clock` 기반 `DateTimeProvider`를 재사용한다.
provider는 `Instant`를 `ChronoUnit.MICROS`로 자른다. 새 의존성과 시계, `AuditorAware`는 추가하지 않는다.
공통 base는 `shared.domain`에 두며 id, 권한, 사용자와 행위자를 갖지 않는다.

| base | 저장 속성 | 적용 엔티티 |
| --- | --- | --- |
| `CreatedAtEntity` | `@CreatedDate createdAt`, `created_at NOT NULL`, `updatable=false` | `MemoryCollection` |
| `AuditedEntity extends CreatedAtEntity` | `@LastModifiedDate updatedAt`, `updated_at NULL` | `Agent`, `AppUser`, `AllowedPerson` |

두 base는 `@MappedSuperclass`이며 `CreatedAtEntity`에 둔 `AuditingEntityListener`를 상속한다.
getter는 기존 관례대로 `createdAt()`과 `updatedAt()`이다. 새 객체는 저장 전 감사 시각이 NULL이다.
새 행은 callback에서 생성·변경 시각을 모두 채운다. 변경 시각은 PreUpdate 시각이며 commit 완료 시각이 아니다.
실제 속성 변경이 없는 flush, 조회, 기존 값과 같은 설정 저장에서는 변경 시각을 움직이지 않는다.
rollback한 행과 변경은 DB에 남지 않는다. 기존 행의 생성 시각은 dirty update와 detach/merge에서도 보존한다.

세 엔티티의 생성 factory에서 저장 시각만을 위한 `Instant now` 인자를 제거한다.
`Agent.deletedAt`, `AllowedPerson.lastLoginAt` 같은 업무 시각은 기존 인자 전달과 갱신 경로를 유지한다.
저장 전에 감사 시각이 필요했던 호출자는 저장 결과를 사용한다. 복합 ID의 `MemoryCollection`은 기존처럼 merge 반환값을 사용한다.

### 적용하지 않는 업무 시각

이 변경은 위 네 엔티티만 auditing 대상으로 삼는다. 다른 엔티티는 기존 시각을 그대로 전달한다.
아래 목록은 이름이 비슷해도 callback으로 바꿀 수 없는 대표 계약이다.
구조 규칙은 허용한 두 base 밖의 필드와 getter에 auditing annotation을 붙이는 경우를 모두 거절한다.

| 대상 | 보존할 뜻 |
| --- | --- |
| `Conversation.createdAt/updatedAt` | 대화 생성·활동과 목록 정렬, 조건부 rename/touch/delete의 시각 |
| `ConnectorConnection.updatedAt`, `ConnectorBinding.updatedAt` | 금융 ticket, 원고 snapshot, 설치 시도와 현재 연결의 유효성 비교 |
| `ConnectorActionExecution`, `ContentDraftSnapshot`의 연결·바인딩 시각 | 발급 당시의 비교 snapshot |
| `Memory.createdAt/updatedAt/acceptedAt`, `MemoryRevision.changedAt` | 업무 변경과 revision 기록의 같은 시각, 수락과 문맥의 최신성 |
| `AgentMemoryCollectionChange.changedAt` | 접근 설정 변경 사건과 그 행위자 |
| `ExecutionEvent.occurredAt`, `FeedbackEvent.occurredAt`, `ExecutionSkillUse.occurredAt` | 사건이 실제 발생한 시각 |
| `ContentDraftRevision.createdAt`, `MediaObservation.createdAt` | revision 생성·분석 조건과 유효기간의 기준 |
| `TaskRun.createdAt/startedAt/finishedAt`, `ContentDraftJob`의 claim 시각, `SubagentUsageJob`의 재시도·기록 시각 | 큐 수락, 시작 제한, 독점 claim과 실행 수명 |
| `AgentExecution`, `ProactiveCheck`, `ResultDeliveryAttempt`의 시작·완료 시각 | 실행과 시도 수명 |
| `ResultDelivery.updatedAt`, `FollowUp.updatedAt` | 결과 전달 상태와 알림 신호의 시점 |
| `UserBrowser.updatedAt/lastActiveAt/startedAt` | 목록을 읽은 뒤 변경됐는지와 정리 조건 |
| `Notification.readAt`, 토큰의 사용·폐기·만료 시각, 삭제·수락·승인 시각 | 각각의 업무 사건과 수명 |

그 밖의 순수 저장 시각 후보는 사용처를 확인한 뒤 별도 변경에서 넓힌다.
시각이 없는 하위 행이나 재설계 예정 proactive 표에 새 감사 칸을 일괄 추가하지 않는다.
금융 상한은 사용자 본체로 옮기는 #551의 결과를 사용하며 별도 금융 상한 표에 칸을 더하지 않는다.

### 기존 행과 bulk 갱신

새 Flyway DDL은 `agent.updated_at`, `app_user.updated_at`, `allowed_person.updated_at`을 `DATETIME(6) NULL`로 추가한다.
기존 행은 과거 변경 시점을 모르므로 NULL을 유지한다. 생성 시각이나 배포 시각으로 채우지 않고 NOT NULL도 적용하지 않는다.
새 코드로 첫 변경을 저장하면 그때부터 변경 시각이 생긴다. DB 기본값과 `ON UPDATE`는 사용하지 않는다.

native SQL과 JPQL bulk는 listener를 거치지 않는다.
현재 감사 대상의 예외와 기존 업무 시각 갱신 경로는 다음과 같다.

| 경로 | 시각 책임 |
| --- | --- |
| `AppUserRepository.updateModelDefaultTier` | `ModelTierService`가 기존 `Clock`의 MICROS 시각을 인자로 넘기고 JPQL이 `updatedAt`도 갱신한다. 같은 tier 또는 둘 다 NULL이면 UPDATE하지 않는다 |
| `AllowedPersonRepository.updateLastLoginAtIfNewer` | 로그인 시각만 조건부 갱신한다. 관리 설정 변경 시각인 `updatedAt`은 움직이지 않는다 |
| `ConversationRepository`의 bulk 갱신 | 기존 인자와 조건을 보존한다 |
| `ResultDeliveryRepository`의 상태·attempt 갱신 | 기존 시각 인자를 보존한다 |
| `TaskRepository`의 대화 연결 갱신 | 기존 `updatedAt` 인자를 보존한다 |
| `ContentDraftJobRepository`의 claim 갱신 | 기존 claim 시각과 조건을 보존한다 |

감사 대상의 새 bulk 경로는 같은 트랜잭션에서 업무 값과 감사 시각을 함께 갱신해야 한다.
bulk 뒤에는 기존 영속성 문맥의 오래된 값을 다시 저장해 덮어쓰지 않도록 호출 경계를 확인한다.

### 검사와 문서

`ArchitectureRules.AUDITING_LISTENERS_AND_CLOCK`은 대상 수 1 제한을 네 엔티티와 두 base의 명시적 목록으로 바꾼다.
설정과 provider는 각각 하나만 허용한다. 누락·중복·다른 상속·업무 필드나 getter의 annotation도 실패시킨다.
`Auditable`, `AbstractAuditable`, `@CreatedBy`, `@LastModifiedBy`는 계속 거절한다.
기존 MemoryCollection의 callback·merge·rollback 검사를 유지하고 세 엔티티의 생성·변경·no-op과 bulk 검사를 더한다.
H2와 실제 MySQL에서 ORM 및 JDBC 재조회 값이 MICROS로 같아야 한다.
마이그레이션 검사는 새 DB의 전체 적용과 기존 행이 있는 DB의 업그레이드에서 NULL 보존을 확인한다.

### 검토한 대안과 결과

- 모든 시각을 자동화하면 업무 발생 시점과 저장 시점을 혼동하므로 기각한다.
- 모든 엔티티에 생성·변경 base를 강제하면 불변 사건과 하위 행에 불필요한 칸을 만들므로 기각한다.
- 서비스가 감사 시각까지 계속 전달하면 일반 dirty update의 누락 위험이 남으므로 네 엔티티는 callback으로 옮긴다.
- 일반 저장의 누락은 줄지만 bulk 작성자는 시각을 계속 책임져야 한다. NULL인 과거 변경 시각과 저장 전 객체의 NULL을 호출자가 구분해야 한다.
