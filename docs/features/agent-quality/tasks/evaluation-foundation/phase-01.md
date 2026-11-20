# Phase 01. 저장 기반: app_user.evaluation 과 quality_run

**Execution profile**: standard

## 목표

평가 전용 사용자 표시 칸과 확인 한 번을 담는 `quality_run` 표, 그 엔티티와 저장소, 새 최상위 패키지 `quality` 의 자리를 만든다. 뒤 phase 가 이 위에서 돈다.

**범위 외**: 평가 사용자 만들기(phase 2), 판정(phase 3), 실행기와 확인 API(뒤 PR).

## 컨텍스트

**요구사항**: REQ1, REQ2

**근거 문서**: `docs/features/agent-quality/design.md` 의 「저장: `quality_run`」, 「평가 전용 사용자」, `backend/docs/data-schema.md` 의 「테이블을 추가하는 기준」, 「마이그레이션 작성 규칙」

- `quality_run` 은 2026-11-01 「테이블을 늘리지 않는다」 의 예외로 사용자가 승인했다(설계 DECISION1). 예외 근거를 data-schema 에 적는다.
- 마이그레이션 버전은 직접 정하지 않는다. `orch queue reserve --module backend --name app_user_evaluation_quality_run` 의 `RESERVED` 경로로 만든다(공통 지시서의 migration 규칙). DDL 만 담는다. 모든 표와 칸에 한국어 COMMENT 를 단다(rule-50, data-schema 「테이블과 컬럼에 한국어 COMMENT를 단다」). 정렬 규칙은 `utf8mb4_0900_ai_ci`.
- `app_user.evaluation` 은 참/거짓 하나라 본체 컬럼이다. `AppUser` 엔티티는 이 칸을 넣기와 고치기에서 빼고 전용 `UPDATE`(`AppUserRepository` 의 `@Modifying` 쿼리)로만 바꾼다. 읽기는 엔티티 매핑(`insertable=false, updatable=false`)으로 한다. 같은 방식의 선례: `artifact_auto_vault`, `purchase_limit_*` 컬럼.
- 새 표는 사용자 삭제 경로 분류(`backend/src/test/resources/purge/tables.tsv`, `fixture.sql`)에 들어야 `PurgeClassificationTest` 가 통과한다. `quality_run.requested_by_user_id` 는 사용자를 지울 때 NULL 로 비운다(줄은 그룹의 기록이라 남긴다).
- 최상위 패키지 층 순서 `backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageOrder.java` 의 목록에서 `workspace` 와 `admin` 사이에 `quality` 를 넣는다. 같은 폴더의 `TopLevelPackageOrderTest.java` 가 목록 크기(22, 23)를 세므로 23, 24 로 고치고 `@DisplayName` 의 「스물셋」 도 고친다. 이 테스트는 `architecture` 태그가 없어 `archTest` 로 돌지 않으므로 검증 명령에 따로 넣는다.
- `backend/docs/code-architecture.md` 의 「`workspace` 는 맨 위다」 와 「`admin` 은 `workspace` 위, 맨 위다」 문장을 `quality` 가 그 위라는 뜻으로 고치고 `quality` 의 문단(부르는 패키지, 아무도 import 하지 않음)을 더한다.
- 삭제 경로 분류 `tables.tsv` 의 줄: `quality_run|GLOBAL|id|KEEP|KEEP|KEEP|그룹의 확인 기록이다. requested_by_user_id 는 FK ON DELETE SET NULL 로 사용자를 지우면 비워진다.`(GLOBAL 표의 user 칸은 KEEP 만 받는다, `PurgeTable`). `fixture.sql` 의 줄은 `requested_by_user_id` 를 NULL 로 넣어 KEEP 비교가 깨지지 않게 하고, `public_id` 는 같은 파일의 다른 줄처럼 행마다 다른 값으로 쓴다.

## 의도 메모

- `quality_run` 에 답 본문, 질문 본문, 도구 인자를 담는 칸을 만들지 않는다. `result_json` 은 고정 코드만 담는다.
- `task_run` 이나 `decision_feedback_event` 를 늘려 쓰는 안은 설계 DECISION1 에서 기각했다.

## 작업 항목

### 1. 마이그레이션 `backend/src/main/resources/db/migration/V20261105074424__app_user_evaluation_quality_run.sql`

- `ALTER TABLE app_user ADD COLUMN evaluation BOOLEAN NOT NULL DEFAULT FALSE COMMENT '평가 전용 사용자인가'`
- `CREATE TABLE quality_run`: `id BIGINT AUTO_INCREMENT`, `public_id BINARY(16) NOT NULL UNIQUE`, `group_id BIGINT NOT NULL`, `kind VARCHAR(32) NOT NULL`, `trigger_type VARCHAR(32) NOT NULL`, `status VARCHAR(16) NOT NULL`, `started_at DATETIME(6) NOT NULL`, `finished_at DATETIME(6) NULL`, `item_count INT NOT NULL DEFAULT 0`, `model_call_count INT NOT NULL DEFAULT 0`, `elapsed_ms BIGINT NULL`, `result_json JSON NULL`, `requested_by_user_id BIGINT NULL`, `created_at DATETIME(6) NOT NULL`, 색인 `(group_id, kind, started_at)`, `requested_by_user_id` 외래 키 `ON DELETE SET NULL`. 칸마다 COMMENT.

### 2. `quality` 패키지의 도메인과 저장소

- `backend/src/main/java/com/bifos/assistant/quality/domain/QualityRun.java`: 엔티티. 상태 전이 메서드 `finish(QualityRunStatus, List<FeatureResult>, int modelCalls, Instant now)` 는 `RUNNING` 에서만 바꾼다.
- `quality/domain/type/QualityRunKind.java`(`DEPLOY_CHECK`), `QualityRunTrigger.java`(`PIPELINE`, `ADMIN`), `QualityRunStatus.java`(`RUNNING`, `PASSED`, `FAILED`), `QualityFeature.java`(일곱 기능: `SEARCH`, `DELEGATION`, `FILE_PUT`, `IMAGE`, `MEMORY_READ`, `TASK_FIRE`, `PHOTO_INSPECT`), `FeatureStatus.java`(`PASSED`, `FAILED`, `TIMED_OUT`).
- `quality/domain/FeatureResult.java`: `result_json` 항목 record(`feature`, `status`, `reasonCode`, `elapsedMs`, `conversationPublicId`, `executionIds`). JSON 변환기는 기존 JSON 칸 변환기 패턴(`feedback/domain/MaintenanceDetailConverter.java`)을 따른다.
- `quality/infra/QualityRunRepository.java`: `findFirstByGroupIdAndKindAndStatus...`, `findByPublicId`, 상태가 `RUNNING` 인 줄 목록.

### 3. `app_user.evaluation`

- `backend/src/main/java/com/bifos/assistant/user/domain/AppUser.java`: 읽기 전용 매핑 `evaluation()`. 테스트가 엔티티로 만든 스키마(create-drop)로 돌므로 `@Column(nullable = false, insertable = false, updatable = false)` 와 `@ColumnDefault("false")` 를 단다.
- `backend/src/main/java/com/bifos/assistant/user/infra/AppUserRepository.java`: `markEvaluation(Long id)` 전용 `UPDATE`(일반 감사 `updated_at` 을 함께 적는다), `findFirstByGroupIdAndEvaluationTrue(Long groupId)`.

### 4. 문서와 구조 검사

- `backend/docs/data-schema.md`: `app_user` 절에 「평가 전용 사용자 칸」, 새 「품질 확인 표」 절에 `quality_run`(칸, 보관하지 않고 지우지 않음, 예외 근거). 삭제 경로 분류 절에 새 표를 더한다.
- `backend/docs/code-architecture.md`: 최상위 패키지 책임 표에 `quality` 줄(`docs/features/agent-quality.md` 링크).
- `TopLevelPackageOrder.java` 목록에 `quality`.
- `backend/src/test/resources/purge/tables.tsv`, `backend/src/test/resources/purge/fixture.sql`: 새 표 분류와 예시 줄.

### 5. 이 phase 를 검증하는 테스트

- `backend/src/test/java/com/bifos/assistant/quality/domain/QualityRunTest.java`: `RUNNING` 에서 `finish` 가 결과와 시각을 적고, 끝난 줄은 다시 바뀌지 않는다.
- `backend/src/test/java/com/bifos/assistant/user/infra/AppUserEvaluationTest.java`(`testsupport/BackendIntegrationTest` 를 상속하는 통합 테스트. 저장소 전용 슬라이스 테스트는 쓰지 않는다): `markEvaluation` 뒤 다시 읽으면 참이고, 먼저 읽어 둔 엔티티를 저장해도 값이 덮이지 않는다. 표시하지 않은 사용자는 거짓이다.

## 검증

```bash
cd backend && ./gradlew test --tests '*QualityRunTest' --tests '*AppUserEvaluationTest' --tests '*PurgeClassificationTest' --tests '*SchemaCommentsMigrationTest' --tests '*TopLevelPackageOrderTest' --tests '*JpaAuditing*'
cd backend && ./gradlew archTest
node --test test/repo/doc-links.test.ts test/repo/doc-files.test.ts test/repo/migration-collation.test.ts
```

실제 MySQL 검사(`MysqlMigrationTest` 의 COMMENT 검사, `PurgeCoverageMysqlTest`, `RepositoryQueryMysqlTest`)는 같은 맥에서 여러 worker 가 돌 때 로컬에서 돌리지 않고 Draft CI 의 `backend-mysql` 결과로 본다(인수 검증 계획 참조).

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V20261105074424__app_user_evaluation_quality_run.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/quality/domain/QualityRun.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/quality/domain/FeatureResult.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/quality/domain/FeatureResultsConverter.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/quality/domain/type/QualityRunKind.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/quality/domain/type/QualityRunTrigger.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/quality/domain/type/QualityRunStatus.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/quality/domain/type/QualityFeature.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/quality/domain/type/FeatureStatus.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/quality/infra/QualityRunRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/user/domain/AppUser.java` | 수정 (evaluation 읽기 매핑) |
| `backend/src/main/java/com/bifos/assistant/user/infra/AppUserRepository.java` | 수정 (전용 UPDATE, 조회) |
| `backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageOrder.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageOrderTest.java` | 수정 |
| `backend/src/test/resources/purge/tables.tsv` | 수정 |
| `backend/src/test/resources/purge/fixture.sql` | 수정 |
| `backend/docs/data-schema.md` | 수정 |
| `backend/docs/code-architecture.md` | 수정 |
| `backend/src/test/java/com/bifos/assistant/architecture/JpaAuditingRules.java` | 수정 (quality_run 의 created_at 판정) |
| `backend/docs/adr/ADR-20261016-jpa-auditing-base.md` | 수정 (판정 표에 quality_run) |
| `backend/src/test/java/com/bifos/assistant/shared/JpaAuditingEntityFixtures.java` | 수정 (감사 왕복 검사에 QualityRun) |
| `backend/src/test/java/com/bifos/assistant/quality/domain/QualityRunTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/user/infra/AppUserEvaluationTest.java` | 신규 |
