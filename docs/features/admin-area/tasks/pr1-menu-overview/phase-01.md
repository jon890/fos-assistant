# Phase 01. 그룹 전체의 실패 요약과 이번 달 합계 조회

**Execution profile**: standard

## 목표

관리자 요약 화면의 두 카드가 읽을 Control Plane 엔드포인트 둘을 만든다. `GET /api/v1/admin/overview/failures` 와 `GET /api/v1/admin/overview/monthly` 이다.

**범위 외**: 웹 화면과 프록시 라우트(phase 02), 「사용량과 비용」 화면의 범위 변경(스펙 범위 밖).

## 컨텍스트

**요구사항**: REQ4, REQ5

**근거 문서**: `docs/features/admin-area/spec.md`, `docs/features/admin-area/design.md`

- 패키지 규칙: `admin` 은 기능의 `application` 과 `application.dto`(와 `domain.type`)만 부른다(`backend/docs/code-architecture.md` 의 `ADMIN_USES_ONLY_FEATURE_APPLICATION`). `usage` 의 하위 기능 순서는 `pricing, execution, subagent, tree, summary` 이고 아래가 위를 쓰지 못한다(`backend/src/test/java/com/bifos/assistant/architecture/SubFeatureOrder.java`).
- 실패 규칙은 웹 `web/src/lib/execution-status.ts` 의 `executionStatusVariant` 와 같다. 도는 중(`RUNNING`)이 아니고, 상태가 `FAILED` 이거나 `errorCode` 가 있으면 실패다. 세는 단위는 루트 실행(`rootExecutionId is null`)이다.
- 서울 시간대 상수는 `UsageController.HOUSEHOLD_ZONE`, `UsageBreakdownAdminController.HOUSEHOLD_ZONE`, `AgentExecutionRepository.sumByDayBetween` 질의에 따로 있고 주석이 함께 고치라고 적는다. 새 자리를 그 주석에 더한다.
- 월 합계는 `UsageSummaryService.monthly(Long, Instant, Instant)` 가 낸다. 응답 칸은 `UsageDtos.MonthlyCostView.from` 의 `totalExecutions = pricedExecutions + unpricedExecutions` 를 따른다.
- 관리자 컨트롤러의 모양은 `admin/workspace/presentation/WorkspaceAdminController.java`, `admin/usage/presentation/LatencyAdminController.java` 를 따른다. 엔드포인트마다 처음에 `currentUser.requireAdmin()` 을 부른다.

## 의도 메모

- 그룹 월 합계를 새 질의로 쓰지 않는다. 사용자마다 기존 `monthly` 를 불러 더한다. 자식 원장 줄과 사건 관측의 규칙(ADR-062)을 두 벌로 만들지 않으려는 것이다.
- 질의 결과 record 를 `usage.tree` 에 두지 않는다. 저장소가 `usage.execution.infra` 에 있어 하위 기능 순서에 걸린다.
- 응답에 대화 번호, 도구 원본, 문맥 항목을 넣지 않는다. 남의 실행은 세기만 한다(스펙 DECISION10).

## 작업 항목

### 1. `user` 의 그룹 사용자 이름

- `backend/src/main/java/com/bifos/assistant/user/infra/AppUserRepository.java` 에 `List<AppUser> findByGroupIdOrderByIdAsc(Long groupId)` 를 더한다.
- `backend/src/main/java/com/bifos/assistant/user/application/UserDisplayNameService.java` 에 `@Transactional(readOnly = true) public Map<Long, String> namesInGroup(Long groupId)` 를 더한다. 번호 순 `LinkedHashMap`, 값은 `displayName`(없으면 빈 문자열이 아니라 null 을 그대로 둔다). 꺼진 사용자도 넣는다.

### 2. `usage` 의 실패 질의와 요약

- `backend/src/main/java/com/bifos/assistant/usage/execution/domain/FailedExecutionRow.java`(record): `Long id, Long userId, String agentName, Instant startedAt, String errorCode`.
- `backend/src/main/java/com/bifos/assistant/usage/execution/infra/FailedRootExecutionRepository.java`(`Repository<AgentExecution, Long>`)를 새로 두고 둘을 더한다. `AgentExecutionRepository` 는 파일 길이 상한(500줄)에 닿아 있어 더하지 않는다. 조건은 같다: `e.userId in :userIds and e.rootExecutionId is null and e.startedAt >= :since and e.status <> RUNNING and (e.status = FAILED or e.errorCode is not null)`.
  - `long countFailedRootsSince(Collection<Long> userIds, Instant since)`
  - `List<FailedExecutionRow> findFailedRootsSince(Collection<Long> userIds, Instant since, Pageable pageable)`: `select new ...FailedExecutionRow(e.id, e.userId, a.name, e.startedAt, e.errorCode) from AgentExecution e left join Agent a on a.id = e.agentId where ... order by e.startedAt desc, e.id desc`. 조인의 선례는 같은 파일의 `sumByAgentBetween` 이다.
- `backend/src/main/java/com/bifos/assistant/usage/tree/application/dto/FailedRoot.java`(record, 칸은 위와 같다)와 `RecentFailures.java`(record `long total, List<FailedRoot> recent`).
- `RootExecutionQuery` 에 `@Transactional(readOnly = true) public RecentFailures recentFailures(Collection<Long> userIds, Instant since, int limit)` 를 더한다. `userIds` 가 비면 질의하지 않고 `new RecentFailures(0, List.of())`.

### 3. `usage` 의 사용자 여럿 월 합계

- `UsageSummaryService` 에 `public MonthlyUsageSummary monthly(Collection<Long> userIds, Instant from, Instant to)` 를 더한다. 사용자마다 기존 `monthly` 를 불러 칸마다 더한다. 비면 모두 0.

### 4. `admin.usage` 의 서비스와 컨트롤러

- `backend/src/main/java/com/bifos/assistant/admin/usage/application/dto/OverviewFailures.java`(record `Instant since, long total, List<Line> recent`, 안의 record `Line(Long id, String userName, String agentName, Instant startedAt, String errorCode, boolean mine)`)와 `OverviewMonthly.java`(record `String month, long totalExecutions, long subscriptionExecutions, long actualCostMicros, boolean incomplete`). 서비스는 이 둘을 돌려준다. presentation 의 dto 를 돌려주면 application 이 presentation 에 기댄다.
- `backend/src/main/java/com/bifos/assistant/admin/usage/application/AdminOverviewService.java`: `CurrentUser` 를 받아 `namesInGroup(admin.groupId())` 로 사용자 번호를 고른다. 시각은 `shared/config/ClockConfig.java` 의 `Clock` 빈 하나를 주입받아 쓴다(통합 테스트는 `testsupport/TestClock` 의 `set` 으로 고정한다).
  - `failures(CurrentUser admin)`: `since = LocalDate.now(clock, Asia/Seoul).minusDays(6).atStartOfDay(Asia/Seoul).toInstant()`. `recentFailures(ids, since, 3)` 의 줄마다 사용자 이름과 `mine = userId.equals(admin.id())` 를 붙인다.
  - `monthly(CurrentUser admin)`: 달의 경계는 `UsageController.thisMonthCost` 와 같은 계산이되 `YearMonth.now(clock.withZone(Asia/Seoul))` 로 주입한 시계를 쓴다. 그룹 합계를 구해 `incomplete = unpricedExecutions + pendingSubagents + unconfirmedSubagents + unpricedSubagents + observationIncompleteExecutions > 0` 을 계산한다.
- `backend/src/main/java/com/bifos/assistant/admin/usage/presentation/AdminOverviewController.java`(`@RequestMapping("/api/v1/admin/overview")`)와 `AdminOverviewDtos.java`:
  - `GET /failures` → `{since, total, recent: [{id, userName, agentName, startedAt, errorCode, mine}]}`
  - `GET /monthly` → `{month, currency: "USD", totalExecutions, subscriptionExecutions, actualCostMicros, incomplete}`
- 세 서울 시간대 자리의 주석에 `AdminOverviewService` 를 더하고, `AdminOverviewService` 에도 같은 주석을 단다.
- 두 엔드포인트의 응답 모양은 설계(`docs/features/admin-area/design.md` 의 「인터페이스와 데이터」)가 갖는다. `backend/docs/code-architecture.md` 의 API 표는 파일 공간 경로만 다루므로 더하지 않는다.

### 5. 테스트

`backend/src/test/java/com/bifos/assistant/admin/usage/AdminOverviewTest.java`(`@BackendIntegrationTest`). 실행은 `AgentExecution.builder()` 로 직접 저장한다(`testsupport/UsageTestSupportController.java` 의 모양). 사용자는 `AppUserRepository.save(AppUser.of(...))` 로 그룹 둘에 만든다.

- 실패 조건: `FAILED`, `SUCCEEDED` 인데 `errorCode` 있음, `CANCELLED` 에 `errorCode` 없음(세지 않음), `RUNNING` 에 `errorCode` 있음(세지 않음), 자식 실행 `FAILED`(세지 않음)
- 기간: 고정 `Clock` 으로 서울 날짜 6일 전 0시 직전과 직후
- 범위: 같은 그룹 두 사용자는 세고 다른 그룹은 세지 않는다. `mine` 은 요청한 관리자 실행만 참. 최근 세 건은 시작 시각 내림차순이고 `total` 은 다섯이다
- 월 합계: 두 사용자의 합이 사용자별 `monthly` 의 합과 같다. 한 사용자 그룹에서는 `UsageController.thisMonthCost` 와 같은 세 값. `incomplete` 가 가격 없는 실행 하나로 참이 된다
- 권한과 응답 모양: 별도 파일 `backend/src/test/java/com/bifos/assistant/admin/usage/AdminOverviewRouteTest.java` 가 `admin/model/ModelCatalogRefreshRouteTest.java` 의 standalone MockMvc 와 `signIn` 을 따른다. `MEMBER` 역할은 두 경로 모두 403, 실패 줄에 대화 번호가 없다. `@BackendIntegrationTest` 는 RANDOM_PORT 라 테스트 쪽 SecurityContext 가 요청에 전달되지 않는다
- `incomplete` 의 세 경우: `backend/src/test/java/com/bifos/assistant/admin/usage/AdminOverviewServiceTest.java` 가 `UsageSummaryService` 를 대역으로 두고 가격 없는 실행, 금액 미확인 도우미(대기, 확인 못 함, 가격 없음), 관측 미확인 실행 각각에서 참이고 모두 0 이면 거짓인지 본다

### 6. 브라우저 검사용 준비 엔드포인트

`backend/src/test/java/com/bifos/assistant/testsupport/UsageTestSupportController.java`:

- `SeededAdminUser` 에 선택 칸 `Long groupId` 를 더한다. 비면 지금처럼 부른 사람의 그룹이다. 브라우저 검사가 다른 검사의 실행과 섞이지 않는 그룹을 만들려는 것이다.
- `SeededExecution` 에 선택 칸 `ExecutionStatus status`, `String errorCode` 를 더한다. 비면 지금처럼 `SUCCEEDED` 이고 오류 코드가 없다.

## 검증

```bash
cd backend && HEAVY_SLOTS=2 orch slot heavy -- ./gradlew test --tests 'com.bifos.assistant.admin.usage.*' --tests 'com.bifos.assistant.usage.UsageCostRecordingTest' --tests 'com.bifos.assistant.architecture.*'
cd backend && ./gradlew checkstyleMain checkstyleTest
node --test test/repo/doc-links.test.ts test/repo/doc-code-references.test.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/user/infra/AppUserRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/user/application/UserDisplayNameService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/execution/domain/FailedExecutionRow.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/execution/infra/AgentExecutionRepository.java` | 수정(주석) |
| `backend/src/main/java/com/bifos/assistant/usage/execution/infra/FailedRootExecutionRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/tree/application/dto/FailedRoot.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/tree/application/dto/RecentFailures.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/tree/application/RootExecutionQuery.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/summary/application/UsageSummaryService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/summary/presentation/UsageController.java` | 수정(주석) |
| `backend/src/main/java/com/bifos/assistant/admin/usage/presentation/UsageBreakdownAdminController.java` | 수정(주석) |
| `backend/src/main/java/com/bifos/assistant/admin/usage/application/AdminOverviewService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/admin/usage/presentation/AdminOverviewController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/admin/usage/presentation/AdminOverviewDtos.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/admin/usage/application/dto/OverviewFailures.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/admin/usage/application/dto/OverviewMonthly.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/admin/usage/AdminOverviewTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/admin/usage/AdminOverviewRouteTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/admin/usage/AdminOverviewServiceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/testsupport/UsageTestSupportController.java` | 수정 |
