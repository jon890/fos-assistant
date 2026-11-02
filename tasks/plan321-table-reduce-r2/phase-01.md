# Phase 01. 사진 관찰 요청을 관찰 줄에 두고 요청 테이블을 코드에서 걷어 낸다

**Execution profile**: standard

**상태**: 완료

## 목표

`media_observation_request` 의 요청 UUID 와 요청 hash 를 `media_observation` 컬럼으로 옮기고, 코드가 그 테이블을 더는 읽고 쓰지 않게 한다. 테이블은 이 PR 의 배포를 확인한 뒤 DROP PR 이 지운다.

**범위 외**: 테이블과 `analysis_key` 의 DROP, 새 칸의 NOT NULL 전환(이 plan 의 `deferred` 의 DROP PR), 뒤 PR 의 묶음, 관찰 MCP 도구와 REST 의 입력과 응답 형식.

## 컨텍스트

**근거 문서**: `backend/docs/adr/ADR-20261010-media-observation-storage.md`, `backend/docs/adr/ADR-20261011-media-observation-cache.md`, `backend/docs/data-schema.md` 의 「media_observation와 media_observation_request」, 「마이그레이션 작성 규칙」, `docs/features/attachment.md` 의 「관찰 저장과 사용자 정정」

- 사용자 결정(2026-11-02)으로 사진 관찰 두 테이블을 하나로 합친다. 대가는 완료 결과 재사용(ADR-20261011)이다. 재사용은 새 요청 UUID 를 이미 있는 관찰에 alias 줄로 잇는 방식이라, alias 줄이 없으면 그 요청의 재시도가 같은 관찰을 돌려줄 수 없다
- 운영 정확한 행 수는 둘 다 0 이다(코디네이터 확인 2026-11-02). 그래도 행이 있을 때 잃지 않게 옮기는 마이그레이션을 둔다
- 0행이어도 같은 PR 에서 DROP 하지 않는다. `ddl-auto: validate` 라서 직전 이미지로 되돌리면 옛 엔티티가 테이블을 찾지 못해 기동하지 못한다(코디네이터 결정 2026-11-02)
- 직전 이미지는 새 칸을 모르고 관찰을 쓴다. 그래서 새 칸은 NULL 을 허용하고, 엔티티에서 뺀 `analysis_key` 도 남긴다
- 관찰은 `MediaObservationService.record` 가 첨부 잠금(`ChatContentMutationCoordinator.run`) 안에서만 저장한다. 요청 UUID 유일은 새 유일 제약 `(attachment_id, request_id)` 가 지킨다. NULL 끼리는 유일 제약에 걸리지 않는다

## 의도 메모

- 기각: 재사용을 남기려고 관찰 줄에 alias 목록 JSON 칸을 두는 안. 불변 관찰 줄을 재사용 때마다 고쳐야 하고, 요청 UUID 유일을 DB 가 지키지 못한다
- 기각: 재사용 hit 에서 alias 를 남기지 않고 응답만 돌려주는 안. 그 요청을 재시도할 때 최신 revision 이 바뀌었으면 409 가 나서 같은 요청이 다른 결과를 낸다
- 이미 적용된 마이그레이션 파일과 `db/comments/V20261016075220.tsv` 는 고치지 않는다
- 정리 분류(`purge/tables.tsv`, `fixture.sql`)의 옛 테이블 줄은 테이블이 남아 있는 동안 둔다

## 작업 항목

### 1. 마이그레이션 두 파일

DDL 과 DML 을 나눈다(`backend/docs/data-schema.md` 「DDL 과 DML 을 한 파일에 섞지 않는다」).

- `backend/src/main/resources/db/migration/V20261102053006__media_observation_request_columns.sql`: `media_observation` 에 `request_id CHAR(36) NULL`, `request_hash CHAR(64) NULL` 을 한국어 COMMENT 와 함께 더하고 `uk_media_observation_request_id (attachment_id, request_id)` 를 더한다
- `backend/src/main/resources/db/migration/V20261102053007__media_observation_request_backfill.sql`: 관찰마다 그 관찰을 가리키는 alias 가운데 `id` 가 가장 작은 줄의 두 값을 상관 서브쿼리로 채운다. 같은 관찰을 가리키는 나머지 alias(재사용 hit)는 옮기지 않는다

### 2. 코드

- `MediaObservation`: `requestId`, `requestHash` 필드와 생성자 인자를 더하고 `analysisKey` 매핑을 뺀다. `@Table` 의 `uniqueConstraints` 에 새 유일을 더한다
- `MediaObservationRepository`: `findByAttachmentIdAndRequestId(Long, String)` 를 더한다
- `MediaObservationService.record`: 재시도 조회를 위 메서드로 바꾸고 재사용 분기(`reusable`, `completed`)를 지운다. 새 요청은 언제나 새 revision 을 만든다
- 삭제: `MediaObservationRequest`, `MediaObservationRequestRepository`, `MediaObservationAnalysisKey`
- `backend/config/archunit/store/` 의 `MediaObservationAnalysisKey` 동결 줄을 지운다

### 3. 이 phase 를 검증하는 테스트

- `MediaObservationMigrationTest`(H2)와 그 MySQL 하위 클래스 `MediaObservationMysqlMigrationTest`: 요청 칸 직전 버전까지 적용하고 관찰 둘과 alias 셋(한 관찰에 둘)을 넣은 뒤 끝까지 적용한다. 각 관찰의 `request_id`, `request_hash` 가 가장 이른 alias 값이고, 같은 첨부에 같은 `request_id` 를 두면 실패하며, `request_id` 가 빈 관찰(직전 이미지의 쓰기)은 여럿 넣을 수 있다
- `MediaObservationRequestTest`: 같은 UUID 재시도는 같은 revision 을 돌려주고 다른 본문은 409 다. 같은 분석 조건의 새 UUID 는 새 revision 을 만든다. 같은 첨부의 같은 `request_id` 줄은 DB 가 막는다
- `MediaObservationCacheTest`, `MediaObservationAnalysisKeyTest`: 재사용과 함께 지운다. `MediaObservationLockTest` 는 `MediaObservationRequestTest` 를 상속하고, 같은 조건의 경합도 한 승자임을 본다
- alias 저장소를 쓰던 테스트는 alias 수 대신 관찰 줄 수와 `request_id` 를 본다

### 4. 문서

- 새 ADR `backend/docs/adr/ADR-20261102-media-observation-request-merge.md`: 요청 UUID 를 관찰 줄에 두고 재사용을 내려놓은 결정과, 표를 지우는 순서. ADR-20261011 은 `superseded` 로 바꿔 `backend/docs/adr/archive/` 로 옮기고 `backend/docs/adr/INDEX.md` 의 「보관」 표와 링크를 고친다. ADR-20261010 에 대체된 부분을 적는다
- `backend/docs/data-schema.md`, `backend/docs/code-architecture.md`, `docs/features/attachment.md` 를 맞춘다

## 검증

```bash
cd backend && ./gradlew test --tests '*MediaObservation*' --tests '*ConversationPurgerTest' --tests '*AttachmentDeletionBarrierTest' --tests '*ContentDraftServiceTest' --tests '*Purge*' --tests '*JpaAuditing*'
cd backend && ./gradlew qualityCheck
scripts/checks/check-mysql-migration.sh
```

모두 실패 없이 끝나고, MySQL 검사에서 `MediaObservationMysqlMigrationTest` 와 `RepositoryQueryMysqlTest` 가 통과한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V20261102053006__media_observation_request_columns.sql` | 신규 |
| `backend/src/main/resources/db/migration/V20261102053007__media_observation_request_backfill.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/attachment/domain/MediaObservation.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/attachment/domain/MediaObservationRequest.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/chat/attachment/infra/MediaObservationRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/attachment/infra/MediaObservationRequestRepository.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/chat/attachment/application/MediaObservationService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/attachment/application/MediaObservationAnalysisKey.java` | 삭제 |
| `backend/config/archunit/store/**` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/MediaObservationMigrationTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/MediaObservationRequestTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/MediaObservationCacheTest.java` | 삭제 |
| `backend/src/test/java/com/bifos/assistant/chat/MediaObservationAnalysisKeyTest.java` | 삭제 |
| `backend/src/test/java/com/bifos/assistant/chat/MediaObservationLockTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/MediaObservationServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/MediaObservationBodiesTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/MediaObservationCleanupTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/MediaObservationControllerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ContentDraftServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ConversationPurgerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/AttachmentDeletionBarrierTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpMediaObservationToolTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/RepositoryQueryMysqlTest.java` | 수정 |
| `backend/docs/adr/ADR-20261102-media-observation-request-merge.md` | 신규 |
| `backend/docs/adr/ADR-20261010-media-observation-storage.md` | 수정 |
| `backend/docs/adr/ADR-20261011-media-observation-cache.md` | 삭제 |
| `backend/docs/adr/archive/ADR-20261011-media-observation-cache.md` | 신규 |
| `backend/docs/adr/INDEX.md` | 수정 |
| `backend/docs/data-schema.md` | 수정 |
| `backend/docs/code-architecture.md` | 수정 |
| `docs/features/attachment.md` | 수정 |
