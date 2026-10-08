# Phase 01. 매일 루프의 표와 사용자 설정

**Execution profile**: deep

## 목표

매일 루프가 쓸 두 표(`proactive_loop_setting`, `proactive_loop_run`)와 설치 설정, 사용자가 에이전트마다 루프를 켜고 끄고 쉬게 하는 API 를 만든다.
루프를 실제로 잇는 이음매는 아직 만들지 않는다.

**범위 외**: `ProactiveLoopCoordinator`, 기동 복구, export 변경(phase 02). 7일 합성 반복(phase 03). 웹 화면과 지금 화면 항목(다음 PR).

## 컨텍스트

**근거 문서**: `docs/backend/proactive-loop.md` 의 「사용자 설정」, 「설정」 절, `docs/backend/schema/proactive.md` 의 「`proactive_loop_setting`」, 「`proactive_loop_run`」 절, `docs/adr/ADR-20261008-daily-loop.md`, `docs/flow.md` 의 오류 코드 표(`PROACTIVE_LOOP_UNAVAILABLE`)

따를 기존 패턴:

- 마이그레이션: `backend/src/main/resources/db/migration/V20261007030326__proactive_autonomy_policy.sql` 의 FK 이름, 색인 이름, `ENGINE`, `CHARSET`, `COLLATE` 모양
- 엔티티: `backend/src/main/java/com/bifos/assistant/proactive/domain/AutonomyPreference.java` (Lombok `@Getter`, `@Accessors(fluent = true)`, `protected` 기본 생성자, 정적 생성 메서드)
- 저장되는 enum 은 `proactive.domain.type` 에 둔다(`backend/CLAUDE.md` 「enum 은 저장 여부로 둘 곳을 정한다」)
- 설치 설정: `backend/src/main/java/com/bifos/assistant/proactive/application/AutonomyProperties.java` 와 `backend/src/main/java/com/bifos/assistant/LivePropertiesConfig.java` 의 `autonomyPropertiesLive`. `@ConfigurationPropertiesScan` 이 record 를 찾는다
- 권한: `backend/src/main/java/com/bifos/assistant/task/application/ProactiveScheduleService.java` 의 `get`, `update` 가 쓰는 `AgentService.requireReadable(CurrentUser, String)`, `AgentService.requireStartable(CurrentUser, String)`
- 컨트롤러와 DTO: `backend/src/main/java/com/bifos/assistant/proactive/presentation/ProactiveCheckController.java`, 요청과 응답 record 는 `ProactiveCheckDtos.java` 에만 둔다(`ArchitectureRules.CONTROLLERS_HAVE_NO_NESTED_RECORDS`)
- 통합 시험: `@BackendIntegrationTest`, `@OverrideProperties("...")`, `TestClock`. 본보기는 `backend/src/test/java/com/bifos/assistant/proactive/AutonomyPolicyServiceTest.java`. 시험이 만든 줄은 `@AfterEach` 에서 `JdbcTemplate` 으로 지운다

## 의도 메모

- 설정 표는 `(user_id, agent_id)` 유일 제약에 대리 키 `id` 를 둔다. 복합 키 엔티티를 만들지 않으려는 선택이다
- `snoozedUntil` 이 지난 시각이면 비운 것과 같다. 30일보다 먼 시각은 거절한다. 사용자가 끝없이 쉬게 하면 끄기와 구분이 없어진다
- 설치 설정이 꺼져 있어도 끄기와 쉬기는 받는다. 켜기만 막는다
- 시도 줄 엔티티와 저장소는 이 phase 에서 만들되 쓰는 곳은 phase 02 다. 마이그레이션을 한 파일로 두기 위해서다

## 작업 항목

### 1. `backend/src/main/resources/db/migration/V20261008061000__proactive_loop.sql`

`proactive_loop_setting` 과 `proactive_loop_run` 을 만든다. 칸, 타입, NULL 여부, FK 와 삭제 동작은 `docs/backend/schema/proactive.md` 의 두 절과 같게 한다.

- `proactive_loop_setting`: `id` PK AUTO_INCREMENT, `UNIQUE KEY uk_proactive_loop_setting_user_agent (user_id, agent_id)`, FK `app_user(id)` 와 `agent(id)` 모두 `ON DELETE CASCADE`
- `proactive_loop_run`: `UNIQUE KEY uk_proactive_loop_run_source (source_check_id)`, `KEY idx_proactive_loop_run_user_created (user_id, created_at)`, `KEY idx_proactive_loop_run_status (status)`, FK `app_user` CASCADE, `proactive_check` CASCADE, `proactive_value_evaluation` `ON DELETE SET NULL`

MySQL 과 H2 MySQL 모드에 함께 있는 문법만 쓴다.

### 2. 엔티티와 enum

- `backend/src/main/java/com/bifos/assistant/proactive/domain/type/LoopRunStatus.java`: `RUNNING`, `DECIDED`, `SKIPPED`, `FAILED`
- `backend/src/main/java/com/bifos/assistant/proactive/domain/type/LoopSkippedReason.java`: `SNOOZED`, `NO_CANDIDATE`, `DAILY_LIMIT`
- `backend/src/main/java/com/bifos/assistant/proactive/domain/ProactiveLoopSetting.java`: `@Table(name = "proactive_loop_setting")`. 정적 `of(Long userId, Long agentId, boolean enabled, Instant snoozedUntil, Instant now)`, `change(boolean enabled, Instant snoozedUntil, Instant now)`, `boolean snoozedAt(Instant now)`(`snoozedUntil != null && snoozedUntil.isAfter(now)`)
- `backend/src/main/java/com/bifos/assistant/proactive/domain/ProactiveLoopRun.java`: `@Table(name = "proactive_loop_run")`. 상태와 까닭은 `@Enumerated(EnumType.STRING)`. 정적 `running(Long userId, Long sourceCheckId, Instant now)`, `skipped(Long userId, Long sourceCheckId, LoopSkippedReason reason, Instant now)`(`finished_at` 도 채운다). 메서드 `decided(Long evaluationId, Instant now)`, `failed(String errorCode, Long evaluationId, Instant now)`

### 3. 저장소

- `backend/src/main/java/com/bifos/assistant/proactive/infra/ProactiveLoopSettingRepository.java`: `Optional<ProactiveLoopSetting> findByUserIdAndAgentId(Long userId, Long agentId)`, 그리고 `@Lock(LockModeType.PESSIMISTIC_WRITE)` 를 단 `List<ProactiveLoopSetting> findByUserIdOrderByIdAsc(Long userId)`
- `backend/src/main/java/com/bifos/assistant/proactive/infra/ProactiveLoopRunRepository.java`: `Optional<ProactiveLoopRun> findBySourceCheckId(Long sourceCheckId)`, `long countByUserIdAndStatusNotAndCreatedAtAfter(Long userId, LoopRunStatus status, Instant after)`, `List<ProactiveLoopRun> findByStatus(LoopRunStatus status)`, `List<ProactiveLoopRun> findBySourceCheckIdIn(Collection<Long> sourceCheckIds)`

`RepositoryQueryMysqlTest` 가 새 메서드를 스스로 실행한다. 인자 타입이 없다고 실패하면 `backend/src/test/java/com/bifos/assistant/testsupport/RepositoryQuerySweep.java` 에 그 타입의 값을 더한다.

### 4. 설치 설정

- `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveLoopProperties.java`: `@ConfigurationProperties(prefix = "assistant.proactive-loop")`, `@Validated` record `(@DefaultValue("false") boolean enabled, @DefaultValue("hermes") String provider, @DefaultValue("1") int maxRunsPerDay)`. 생성자에서 `provider` 가 비었거나 `maxRunsPerDay < 1` 이면 `IllegalArgumentException`
- `backend/src/main/java/com/bifos/assistant/LivePropertiesConfig.java`: `LiveProperties<ProactiveLoopProperties> proactiveLoopPropertiesLive(ProactiveLoopProperties value)` 빈을 `autonomyPropertiesLive` 와 같은 모양으로 더한다
- `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java`: `LIVE_SETTINGS` 목록에 `ProactiveLoopProperties.class` 를 더한다

### 5. 오류 코드

`backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` 에 `PROACTIVE_LOOP_UNAVAILABLE(HttpStatus.CONFLICT)` 를 `PROACTIVE_CHECK_UNAVAILABLE` 바로 아래에 더한다.

### 6. 설정 서비스

- `backend/src/main/java/com/bifos/assistant/proactive/application/model/LoopSettingView.java`: record `(boolean available, boolean enabled, Instant snoozedUntil)`. `snoozedUntil` 은 지금보다 뒤일 때만 싣고 아니면 null
- `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveLoopSettingService.java`
  - `LoopSettingView get(CurrentUser user, String agentCode)`: `agents.requireReadable` 뒤 줄을 읽는다. 줄이 없으면 `enabled = false`
  - `LoopSettingView update(CurrentUser user, String agentCode, boolean enabled, Instant snoozedUntil)`: 켜기면 `requireStartable`, 아니면 `requireReadable`. 켜기인데 `ProactiveLoopProperties.enabled` 가 거짓이면 `ApiException(PROACTIVE_LOOP_UNAVAILABLE)`. `snoozedUntil` 이 지금부터 30일 뒤보다 늦으면 `ApiException(VALIDATION_FAILED)`, 지금 이전이면 null 로 저장한다. 줄이 없으면 만들고 있으면 `change`. 한 트랜잭션이다
  - 시계는 주입한 `Clock` 을 쓴다

### 7. 컨트롤러와 DTO

- `backend/src/main/java/com/bifos/assistant/proactive/presentation/ProactiveCheckDtos.java`: `LoopSettingBody(@NotNull Boolean enabled, Instant snoozedUntil)`, `LoopSettingResponse(boolean available, boolean enabled, Instant snoozedUntil)` 와 `static LoopSettingResponse from(LoopSettingView)`
- `backend/src/main/java/com/bifos/assistant/proactive/presentation/ProactiveLoopController.java`: `@RequestMapping("/api/v1/agents/{code}/proactive-check/loop")`, `@GetMapping` 과 `@PutMapping`(`@Valid @RequestBody LoopSettingBody`). 요청자는 `CurrentUserProvider.require()`

### 8. 이 phase 를 검증하는 시험

설치 설정은 시험 클래스 단위로만 바꿀 수 있다(`@OverrideProperties` 는 클래스에 달고, `@Nested` 는 바깥 클래스의 값을 이어받지 않는다). 그래서 설정 조합마다 클래스를 나눈다. 서비스를 직접 부른다.

- `backend/src/test/java/com/bifos/assistant/proactive/ProactiveLoopSettingTest.java` (`@BackendIntegrationTest`, `@OverrideProperties("assistant.proactive-loop.enabled=true")`)
  - 줄이 없으면 `enabled = false`, `available = true` 다
  - 켜고 쉬기를 저장하면 같은 값을 다시 읽는다
  - 31일 뒤 `snoozedUntil` 은 `VALIDATION_FAILED`, 지난 시각은 `snoozedUntil = null` 로 저장된다
  - 다른 사용자가 읽을 수 없는 에이전트는 `requireReadable` 의 오류다
- `backend/src/test/java/com/bifos/assistant/proactive/ProactiveLoopSettingDisabledTest.java` (`@BackendIntegrationTest`, 설정 덮어쓰기 없음. 설치 설정 기본값 `false`)
  - `available = false` 다
  - 켜기는 `PROACTIVE_LOOP_UNAVAILABLE`, 끄기와 쉬기는 통과한다
- `backend/src/test/java/com/bifos/assistant/proactive/ProactiveLoopMigrationTest.java`: 같은 패키지의 `ValueEvaluationMigrationTest.java` 와 같은 방식으로 새 마이그레이션을 적용하고, 같은 `(user_id, agent_id)` 두 줄과 같은 `source_check_id` 두 줄이 유일 제약에 걸리는지 본다

## 검증

```bash
(cd backend && ./gradlew test --tests '*ProactiveLoopSettingTest' --tests '*ProactiveLoopSettingDisabledTest' --tests '*ProactiveLoopMigrationTest' --tests 'com.bifos.assistant.architecture.*')
(cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck)
node scripts/check-migration-versions.mjs
```

`ProactiveLoopSettingTest`, `ProactiveLoopSettingDisabledTest`, `ProactiveLoopMigrationTest` 가 통과하고, 구조 규칙의 `LIVE_SETTINGS` 검사가 새 설정을 받아들인다.
Docker 가 있으면 `scripts/check-mysql-migration.sh` 로 엔티티와 Flyway 스키마가 맞는지도 본다. 없으면 CI 의 backend job 이 본다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V20261008061000__proactive_loop.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/domain/type/LoopRunStatus.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/domain/type/LoopSkippedReason.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/domain/ProactiveLoopSetting.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/domain/ProactiveLoopRun.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/infra/ProactiveLoopSettingRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/infra/ProactiveLoopRunRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveLoopProperties.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveLoopSettingService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/model/LoopSettingView.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/presentation/ProactiveLoopController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/presentation/ProactiveCheckDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/LivePropertiesConfig.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/proactive/ProactiveLoopSettingTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/proactive/ProactiveLoopSettingDisabledTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/proactive/ProactiveLoopMigrationTest.java` | 신규 |
