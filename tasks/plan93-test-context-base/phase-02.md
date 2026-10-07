# Phase 02. 공통 기반 주석을 만들고 통합 검사를 옮긴다

**Execution profile**: deep

## 목표

`@BackendIntegrationTest` 와 공통 대역, 격리 확장을 만들고 `@SpringBootTest` 검사를 모두 그 주석으로 옮긴다.
검사가 확인하는 것은 바꾸지 않는다. 이 phase 가 끝나면 전체 검사가 통과하고 컨텍스트 대부분이 기반 하나를 함께 쓴다.

**범위 외**: 이름 있는 변형 주석과 변형 수 줄이기(phase 03), 보존 상한과 측정(phase 04).

## 컨텍스트

**근거 문서**: `docs/adr/ADR-095-backend-통합-검사는-공통-기반-컨텍스트-하나를-함께-쓰고-기동-설정이-다른-검사만-변형을-둔다.md`,
`docs/adr/ADR-096-운영의-백그라운드-작업은-한-빈으로-띄우고-검사는-끝날-때-모두-join-한다.md`, `docs/backend/testing.md`.

브랜치 `test-context-prototype` 의 커밋 하나에 시제품이 있다. 기반 주석, `IntegrationTestDoubles`, `TestClock`, `IntegrationTestIsolation`, 검사 141개의 기계적 이전이 들어 있다.
그 커밋을 가져와 시작한다(`git cherry-pick -n test-context-prototype`). 시제품의 `IntegrationTestIsolation` 은 `TurnCancellation` 의 private 필드를 폴링한다. 이 phase 에서 아래 작업 항목 3 의 join 방식으로 바꾼다.

시제품을 돌려 본 결과 드러난 것:

- `@MockitoSpyBean(types = ...)` 의 빈 이름 해석이 `@ActiveProfiles("test")` 없이 뜨면 저장소 빈을 찾지 못한다. 메타 주석에 `@ActiveProfiles("test")` 가 있어야 한다
- 기반의 spy 인 `SkillStore` 를 `verify(...)` 의 matcher 사이에서 부른 `SkillServiceTest` 가 `InvalidUseOfMatchersException` 으로 실패했다. 값을 지역 변수로 먼저 받는다
- `RegenerateDeletedAttachmentTest` 가 검사 트랜잭션 안에서 `deleteAll` 뒤 같은 code(`dad`)의 에이전트를 넣다가 유일 제약에 걸렸다. 지운 뒤 `flush()` 한다
- `@MockitoBean HermesRunsClient` 를 둔 검사(`ExecutionContextSourceTest`, `ExecutionConversationTest`, `ExecutionLifecycleTest`, `FailedExecutionUsageRoutesTest`, `UsageCostRecordingTest`, `SubagentUsageLedgerTest`)에서는 기반의 `StubHermesRunsClient` 가 mock 으로 바뀌어 없다. 격리 확장은 대역이 없으면 건너뛴다
- 시제품의 1차 전체 검사에서 컨텍스트 기동이 41번이었고(보존 상한 16 에 밀려 다시 뜬 것 포함) 실패 58건이 위 세 원인이었다

## 의도 메모

- 기반의 Mockito mock 은 `HermesRunEventStream`, `HermesToolsetClient`, `HermesConnectorClient`, `HermesSkillClient`, `HermesModelClient`, `HermesDashboardClient` 다.
  기반의 spy 는 `BackendIntegrationTest` 의 `@MockitoSpyBean(types = ...)` 목록이 갖는다. 사용자가 「기반에 모두」 를 골랐다.
- `hermes.run-timeout` 과 살펴보기 `max-duration` 을 `application-test.yml` 에서 올리지 않는다. ADR-095 의 대안 기각을 본다.
- 검사 하나만 쓰는 대역(`PriceCatalog`, `SkillService`, `AgentEndpointProbe`, `HermesProfileProvisioner`, `CheckNotificationPolicy`, `AgentConnectorBindings`, `SkillCommandCatalog` 의 mock, 검사 클래스 안의 `@TestConfiguration`)은 기반에 넣지 않는다.
- `SkillViewStreamTest` 는 실제 `HttpServer` 로 사건 스트림을 읽으므로 기반의 사건 스트림 mock 을 쓸 수 없다. `@SpringBootTest` 로 따로 둔다. MySQL 태그의 기준 클래스(`MysqlMigrationTest`, `RepositoryQueryMysqlTest`, `CollationMixQueryMysqlTest`)도 그대로 둔다.
- 30초 상한은 판정 기준이 아니라 멈춘 작업을 잡는 안전장치다. 기다리는 것은 시간이 아니라 스레드의 끝이다.

## 작업 항목

### 1. `testsupport/BackendIntegrationTest.java`

`@Inherited`, `@SpringBootTest(webEnvironment = RANDOM_PORT)`, `@ActiveProfiles("test")`, `@Import(IntegrationTestDoubles.class)`,
위 mock 여섯 종의 `@MockitoBean(types = ...)`, spy 목록의 `@MockitoSpyBean(types = ...)`, `@ExtendWith(IntegrationTestIsolation.class)` 를 묶는다.
경로는 `backend/src/test/java/com/bifos/assistant/testsupport/` 다. Javadoc 은 `docs/backend/testing.md` 를 가리킨다. 주석의 낱말은 저장소 관례대로 「검사」 를 쓴다.

### 2. `testsupport/IntegrationTestDoubles.java`, `TestClock.java`, `TrackingBackgroundTasks.java`

- `StubHermesRunsClient` 를 `@Primary` 빈으로 둔다
- `TestClock` 을 `@Primary` 빈으로 둔다. `Clock` 을 상속하고 `set(Instant)`, `advance(Duration)`, `reset()` 을 갖는다. 정하지 않으면 실제 UTC 시각이다
- `TrackingBackgroundTasks` 를 `@Primary` 빈으로 둔다. `BackgroundTasks` 를 구현하고, 띄우거나 만든 스레드를 모두 쥔다.
  `awaitIdle(Duration limit)` 은 쥔 스레드를 차례로 `join` 하고, join 하는 동안 새로 띄운 스레드도 다시 돈다. 상한을 넘기면 아직 살아 있는 스레드 이름을 담은 `AssertionError` 를 던진다. 끝난 스레드는 목록에서 뺀다

### 3. `testsupport/IntegrationTestIsolation.java`

JUnit `BeforeEachCallback`, `AfterEachCallback` 이다.

- 검사 전: `StubHermesRunsClient` 가 있으면 `reset()`, `TestClock.reset()`
- 검사 후: `TrackingBackgroundTasks.awaitIdle(Duration.ofSeconds(30))` 를 부르고, 성공하든 실패하든 대역과 시계를 되돌린다
- `ReflectionTestUtils` 로 운영 빈의 private 필드를 읽지 않는다

### 4. 검사 이전

`backend/src/test/java/com/bifos/assistant/` 아래 `@SpringBootTest` 검사(위 의도 메모에서 뺀 넷 제외)를 옮긴다.

- `@SpringBootTest`, `@SpringBootTest(webEnvironment = ...)` → `@BackendIntegrationTest`
- `@SpringBootTest(properties = X)` → `@BackendIntegrationTest` 와 `@TestPropertySource(properties = X)`
- `@ActiveProfiles("test")` 를 지운다
- 클래스 안의 `StubRuntime`, `StubHermes` 설정이 `StubHermesRunsClient` 와 사건 스트림 mock 만 만들면 지우고 그 `@Import` 를 뺀다. `ChatServiceTest.StubRuntime` 을 가져다 쓰던 검사도 그 `@Import` 를 뺀다
- `ChatServiceTest` 는 스킬 캐시 시계를 바꾸는 `SkillCommandCatalog` 빈만 남긴 설정(`SkillCatalogClock`)을 `@Import` 한다
- 고정 시계 설정(`FixedClock`)을 둔 검사(`AttentionServiceTest`, `AttentionControlServiceTest`, `AttentionMetricsServiceTest`, `FollowUpAttentionSourceTest`, `FollowUpProposalTest`, `TaskServiceTest`)는 `@Autowired TestClock clock` 으로 바꾸고 `@BeforeEach` 에서 `clock.set(NOW)` 한다. 클래스 안의 `TestClock` 은 지운다. 시계 말고 다른 빈이 남는 설정은 `Candidates` 로 이름을 바꿔 남긴다
- 기반에 있는 타입의 `@MockitoBean`, `@MockitoSpyBean` 필드는 `@Autowired` 로 바꾼다. 기반에 없는 타입은 그대로 둔다
- 검사 안에서 `holdSubmits()` 처럼 작업을 붙잡는 대역을 쓰면 끝나기 전에 풀었는지 본다
- 위 「컨텍스트」 의 세 실패를 고친다

### 5. 이 phase 를 검증하는 검사

`backend/src/test/java/com/bifos/assistant/testsupport/TrackingBackgroundTasksTest.java` 를 만든다.

- 정상: 띄운 스레드 둘이 끝나면 `awaitIdle` 이 돌아오고, 스레드 안에서 다시 띄운 스레드도 기다린다
- 실패: `CountDownLatch` 로 붙잡은 스레드가 있으면 짧은 상한(100ms)으로 부른 `awaitIdle` 이 그 스레드 이름을 담은 `AssertionError` 를 던진다. 끝나면 latch 를 풀어 스레드를 정리한다

## 검증

```bash
# cwd: 저장소 root
(cd backend && ./gradlew test --tests '*TrackingBackgroundTasksTest')
(cd backend && ./gradlew test)
(cd backend && ./gradlew qualityCheck)
scripts/quality.sh check
! git grep -n "ReflectionTestUtils" -- backend/src/test/java/com/bifos/assistant/testsupport
git grep -ln "^@SpringBootTest" -- backend/src/test
```

- 모두 종료 코드 0. `!` 줄은 일치하는 줄이 없어야 한다
- 마지막 줄의 결과가 `SkillViewStreamTest.java`, `MysqlMigrationTest.java`, `RepositoryQueryMysqlTest.java`, `CollationMixQueryMysqlTest.java` 넷뿐이다

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/test/java/com/bifos/assistant/testsupport/BackendIntegrationTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/testsupport/IntegrationTestDoubles.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/testsupport/IntegrationTestIsolation.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/testsupport/TestClock.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/testsupport/TrackingBackgroundTasks.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/testsupport/TrackingBackgroundTasksTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/**/*Test.java` | 수정 |
