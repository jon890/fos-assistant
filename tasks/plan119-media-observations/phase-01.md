# Phase 01. 첨부 영속 삭제 장벽

**Execution profile**: deep

## 목표

첨부 삭제 요청을 파일 작업보다 먼저 커밋하고 실패와 재시작에도 원본 접근을 차단한다.
삭제 완료 시각은 실제 파일 삭제가 끝난 뒤에만 기록한다.

**범위 외**: 관찰 저장과 요청 매핑, REST/MCP 노출, 원고, preview, 승인, UI와 운영 접근.

## 컨텍스트

코디네이터가 승인한 저장 단계의 첫 작동 관심사다.
기준 main은 `729115a400dd00ec6ffaacb4943941fee76ae8e7`이며 독립 격리 작업 공간에서만 고친다.
불변 원본 사본은 수정하지 않는다.
관찰 초안은 저장소에서 무시되는 작업 기록에 파일별 SHA로 보존한다.
이 단계가 main에 머지된 뒤 관찰 저장과 요청 매핑을 별도 신규 마이그레이션과 계획으로 진행한다.

**근거 문서**: `docs/features/attachment.md`, `backend/docs/data-schema.md`.
구현 전에 설치된 planning 번들의 기본 검사가 종료 0이어야 한다.

```bash
# cwd: 저장소 root
python3 "$SKILL_DIR/scripts/verify_task.py" plan119-media-observations
```

## Blocked 조건

기본 계획 검사 실패, 승인된 삭제 의미의 변경, 변경 파일 밖의 필수 수정 또는 운영 코드 상한 초과면 코디네이터에게 보고한다.
검사기를 수정하거나 빈 타입과 미구현 표로 실패를 숨기지 않는다.

## 작업 항목

### 1. 구현과 같은 변경의 회귀

1. 사용자, 대화, 첨부 ID 순서로 잠그는 READ_COMMITTED 새 트랜잭션 진입점을 만든다. 기존 트랜잭션에서 진입하면 거절한다.
2. nullable 삭제 요청 시각과 요청 후보 색인을 새 DDL에 추가한다. 기존 migration은 수정하지 않는다.
3. 최초 요청을 사전 커밋하고 파일 작업은 트랜잭션 밖에서 수행한다. 성공 뒤 별도 진입점에서 완료 시각을 기록한다. 실패와 재시작에서 요청 시각을 유지한다.
4. 첨부 cleaner가 기동과 기존 주기에 삭제 요청 또는 만료 후보를 대상별로 재검사하여 재시도한다. 없는 파일은 멱등 성공이다.
5. 원본 읽기, inspect의 두 overload, overview와 validate의 최신 SQL 검사, 메시지 연결, 미전송 개수와 실행 입력을 차단한다. decode 전후 검사, 사진 전체 순번과 크기 복원을 유지한다.
6. purge의 session과 파일 삭제 전, 최종 DB 정리 전 모두 같은 진입점으로 재검사한다. writer는 MANDATORY 참가자로 바꾸고 다른 트랜잭션의 단독 호출도 거절한다.
7. 정상 삭제, 파일 작업 전후와 완료 커밋 전 종료, 실패와 재시작, 사용자 잠금, 다중 사용자 분리, expiry와 purge 경쟁을 H2 및 실제 MySQL에서 검증한다. 기존 native inspect 회귀도 실행한다.

### 2. 테스트 파일과 판정

- `backend/src/test/java/com/bifos/assistant/chat/AttachmentDeletionBarrierTest.java`: 파일 작업 전후의 차단, 사용자 잠금, 만료·삭제·purge 경쟁과 재시작 복구를 단언한다.
- `backend/src/test/java/com/bifos/assistant/chat/AttachmentDeletionRequestMigrationTest.java`: 실제 DDL로 요청과 완료 시각의 구분을 단언한다.
- `backend/src/test/java/com/bifos/assistant/RepositoryQueryMysqlTest.java`: 동일 장벽 회귀를 실제 MySQL에서 실행한다.
- `backend/src/test/java/com/bifos/assistant/CollationMixQueryMysqlTest.java`: V57 검사에서 현재 첨부 기동 복구만 대역으로 두고 정렬 오류 검출은 유지한다.
- `backend/src/test/java/com/bifos/assistant/chat/AttachmentServiceTest.java`: 메시지 연결·미전송 개수와 반복 사용자 삭제의 결과를 단언한다.
- `backend/src/test/java/com/bifos/assistant/chat/AttachmentCleanerTest.java`: 파일 삭제 실패가 요청 상태를 보존하는지 단언한다.
- `backend/src/test/java/com/bifos/assistant/chat/AttachmentCleanerClockTest.java`: 실제 constructor 연결과 clock 기준 정리를 단언한다.
- `backend/src/test/java/com/bifos/assistant/chat/ConversationPurgerTest.java`: session 실패에서 요청 상태와 원본을 보존하는지 단언한다.
- `backend/src/test/java/com/bifos/assistant/chat/application/AttachmentInspectAccessTest.java`: decode 이후의 최신 SQL 차단을 단언한다.

## 검증

backend와 MySQL 검사는 코디네이터의 heavy-lock을 통해 순차 실행한다.
실제 MySQL 검사를 실행하지 못하면 완료로 보고하지 않는다.

```bash
# cwd: 저장소 root
(cd backend && ./gradlew test --tests '*AttachmentServiceTest' --tests '*AttachmentCleanerTest' --tests '*AttachmentCleanerClockTest' --tests '*ConversationPurgerTest' --tests '*AttachmentInspectAccessTest' --tests '*AttachmentDeletionBarrier*' --tests '*AttachmentDeletionRequestMigrationTest')
(cd backend && ./gradlew qualityCheck)
scripts/check-mysql-migration.sh
```

모든 명령의 종료 코드는 0이어야 한다.
문서, 한국어, diff, 파일 길이와 규모 검사도 통과한다.
full check-local, commit, push, PR, merge와 배포는 코디네이터가 맡는다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatContentMutationCoordinator.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/model/ChatContentMutationTarget.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/ChatAttachment.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ChatAttachmentRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/AttachmentService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/AttachmentCleaner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ConversationPurger.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ConversationPurgeWriter.java` | 수정 |
| `backend/src/main/resources/db/migration/V20261010004638__attachment_deletion_request.sql` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/AttachmentServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/AttachmentCleanerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/AttachmentCleanerClockTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ConversationPurgerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/application/AttachmentInspectAccessTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/AttachmentDeletionBarrierTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/AttachmentDeletionRequestMigrationTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/RepositoryQueryMysqlTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/CollationMixQueryMysqlTest.java` | 수정 |
| `docs/features/attachment.md` | 수정 |
| `backend/docs/data-schema.md` | 수정 |
| `backend/docs/adr/ADR-20261010-attachment-deletion-request.md` | 신규 |
| `backend/docs/adr/INDEX.md` | 수정 |
