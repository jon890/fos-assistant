# Phase 01. 정리 예약 칸과 예약, 취소 API

**Execution profile**: standard

## 목표

관리자가 꺼진 사용자에게 「정리 예약」 을 걸고 취소할 수 있게 한다. 이 단계는 아무것도 지우지 않는다.

**범위 외**: 실제 삭제와 정리 작업(phase 02), 화면. 미리보기(`GET /api/v1/admin/people/{id}/purge-preview`)는 이미 있다.

## 컨텍스트

**근거 문서**: `backend/docs/adr/ADR-20261101-user-purge.md`, `backend/docs/data-schema.md` 의 `allowed_person` 절, `docs/features/users.md`

- 예약 상태는 `allowed_person.purge_scheduled_at`(`DATETIME(6) NULL`) 한 칸이다. 새 테이블을 만들지 않는다
- 엔티티는 `backend/src/main/java/com/bifos/assistant/people/domain/AllowedPerson.java`, 켜고 끄는 서비스는 `backend/src/main/java/com/bifos/assistant/people/application/PersonAccessService.java` 의 `setEnabled`
- 컨트롤러는 `backend/src/main/java/com/bifos/assistant/admin/people/presentation/PeopleAdminController.java`
- 꺼진 사용자만 예약할 수 있다. 이미 예약했으면 시각을 바꾸지 않는다

## 작업 항목

### 1. 마이그레이션

`backend/src/main/resources/db/migration/V<UTC 시각>__allowed_person_purge_scheduled.sql` 에 칸을 더한다. 시각은 `date -u +%Y%m%d%H%M%S` 로 얻는다. 칸 주석은 `backend/src/main/resources/db/comments/` 의 규칙을 따른다.

```sql
ALTER TABLE allowed_person ADD COLUMN purge_scheduled_at DATETIME(6) NULL COMMENT '정리를 예약한 시각. 비어 있으면 예약이 없다. 이 시각에서 30일이 지나면 정리 작업이 사용자 데이터를 지운다';
```

### 2. 도메인과 서비스

- `AllowedPerson` 에 `schedulePurge(Instant now)`(켜져 있으면 `IllegalStateException`, 이미 예약했으면 그대로)와 `cancelPurge()` 를 둔다. `enable()` 이 예약을 함께 비운다
- `PersonAccessService` 에 `schedulePurge(Long id)`, `cancelPurge(Long id)` 를 둔다. `setEnabled` 와 같은 순서로 잠근다. 켜져 있는 사람에 예약을 걸면 `ErrorCode` 의 기존 충돌 코드를 쓴다

### 3. API

- `POST /api/v1/admin/people/{id}/purge-schedule`, `DELETE /api/v1/admin/people/{id}/purge-schedule`. 둘 다 `currentUser.requireAdmin()` 이 먼저다
- 응답의 `PersonView` 에 `purgeScheduledAt` 을 더한다

### 4. 테스트 파일 `PersonPurgeScheduleTest`

- 꺼진 사람에 예약하면 시각이 적히고 다시 켜면 비워진다
- 켜진 사람에 예약하면 거절한다
- 이미 예약한 사람에 다시 예약해도 시각이 그대로다
- `MEMBER` 는 둘 다 거절한다

## 검증

```bash
cd backend && ./gradlew test --tests '*PersonPurgeScheduleTest' --tests '*PersonAccessRollbackTest'
cd backend && ./gradlew qualityCheck
scripts/checks/check-mysql-migration.sh
```

모두 실패 없이 끝난다. 마지막 명령은 Docker 가 있어야 돈다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V*__allowed_person_purge_scheduled.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/people/domain/AllowedPerson.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/people/application/PersonAccessService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/admin/people/presentation/PeopleAdminController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/admin/people/presentation/PeopleAdminDtos.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/people/PersonPurgeScheduleTest.java` | 신규 |
| `backend/docs/data-schema.md` | 수정 |
| `docs/features/users.md` | 수정 |
