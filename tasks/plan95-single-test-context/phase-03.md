# Phase 03. 검사 하나만 쓰는 mock 과 대역을 기반으로 올리고 클래스 안 선언을 막는다

**Execution profile**: deep

## 목표

검사 클래스 안의 `@MockitoBean`, `@MockitoSpyBean`, `@Import` 를 없애 컨텍스트를 기반 하나로 모은다.
그 뒤로 검사 클래스가 컨텍스트를 나누는 선언을 다시 두지 못하게 구조 규칙으로 막는다. 검사가 확인하는 것은 바꾸지 않는다.

**범위 외**: 측정과 보존 상한(phase 04).

## 컨텍스트

**근거 문서**: `docs/backend/testing.md` 「기반 주석」, `docs/adr/ADR-20261007-test-context-base.md`.

경로는 `backend/src/test/java/com/bifos/assistant/` 아래다. 지금 남은 선언은 아래다. 작업 전에 `git grep -n "@MockitoBean\|@MockitoSpyBean\|^@Import" -- backend/src/test` 로 다시 확인한다.

| 검사 | 선언 | 처리 |
| --- | --- | --- |
| `agent/AgentLifecycleFlagsTest` | `@MockitoBean AgentEndpointProbe` | 기반 spy |
| `chat/ChatServiceTest` | `@MockitoBean SkillService`, `@Import(SkillCatalogClock)` | `SkillService` 기반 spy. 스킬 캐시 시계는 아래 항목 2 |
| `chat/StarterSuggestionServiceTest`, `memory/MemoryProposerTest`, `usage/FailedExecutionUsageRoutesTest` | `@MockitoBean PriceCatalog` | 기반 spy |
| `connector/ApprovalNotificationTest` | `@MockitoBean CheckNotificationPolicy`, `@Import(ConnectorPolicyTestDoubles)` | spy, 아래 항목 2 |
| `connector/ConnectorActionServiceTest`, `ConnectorPolicyEndpointTest` | `@Import(ConnectorPolicyTestDoubles)` | 아래 항목 2 |
| `people/PersonRegistrarTest` | `@MockitoBean HermesProfileProvisioner` | 기반 spy |
| `proactive/ProactiveCheckTurnTest`, `CareerDailyPilotTest` | `@MockitoBean AgentConnectorBindings` | 기반 spy |
| `proactive/AutonomyPolicyServiceTest` | `@MockitoSpyBean AutonomyDecisionRepository`, `@MockitoBean ProactiveCheckService` | 둘 다 기반 spy |
| `task/ProactiveScheduleTransactionTest` | `@MockitoBean SkillCommandCatalog` | 기반 spy |
| `usage/ExecutionContextSourceTest`, `ExecutionConversationTest`, `ExecutionLifecycleTest`, `FailedExecutionUsageRoutesTest`, `UsageCostRecordingTest`, `usage/application/SubagentUsageLedgerTest` | `@MockitoBean HermesRunsClient` | 기반의 `StubHermesRunsClient` 를 spy 로 두고 `doReturn` 으로 바꾸거나, 대역 API(`willReturn` 등)로 바꾼다 |
| `attention/AttentionServiceTest`, `AttentionControlServiceTest` | `@Import(AttentionTestCandidates)` | 대역을 기반으로 옮긴다. 꺼 두면 후보를 내지 않는다 |
| `people/PersonAccessRollbackTest` | `@Import(FailingRevoker)` | 꺼 둔 대역으로 기반에 |
| `chat/ResultDeliveryRecordTest`, `ResultDeliveryRetryTest` | `@Import(ResultDeliveryRecordTest.TestResults)` | 빈 상태면 결과를 내지 않는 대역으로 기반에 |
| `chat/DelegationWakeUserLimitTest`, `ResultDeliveryRetryUserLimitTest` | `@Import(CapturingSchedulerConfig, RetryThreadsConfig)` | 아래 항목 3 |
| `chat/ModelTierFallbackIntegrationTest`, `ModelTierSeedImportIntegrationTest` | 기동 때 가져오기를 보는 `@TestPropertySource` | 아래 항목 4 |
| `hermes/SkillViewStreamTest` | `@SpringBootTest` | 실제 스트림을 검사 안에서 직접 만든다. `@BackendIntegrationTest` 로 바꾼다 |

## 의도 메모

- mock 에서 spy 로 바꾸면 정하지 않은 메서드가 null 대신 실제로 돈다. 검사가 그 차이에 기대는지 하나씩 본다. 기대면 그 메서드를 `doReturn`/`doNothing` 으로 정해 지금 의미를 지킨다.
- 정하지 않은 메서드가 실제로 돌 때 부작용이 큰 곳은 검사 전에 정한다.
  - `AutonomyPolicyServiceTest` 의 `ProactiveCheckService`: 실제 살펴보기가 시작된다. 검사가 부르는 시작 메서드를 `doReturn`/`doNothing` 으로 정한다
  - `PersonRegistrarTest` 의 `HermesProfileProvisioner`: 실제 profile 을 만든다. 검사가 부르는 만들기 메서드를 정한다
  - `AgentLifecycleFlagsTest` 의 `AgentEndpointProbe`: 실제 HTTP 를 확인한다. 확인 메서드를 정한다
  - `ProactiveCheckTurnTest`, `CareerDailyPilotTest` 의 `AgentConnectorBindings`: mock 은 빈 목록을 줬다. 검사가 부르는 조회 메서드가 빈 목록을 돌려주게 정한다
- spy 를 정할 때는 `when(spy.x())` 대신 `doReturn(...).when(spy).x()` 를 쓴다. `when` 은 실제 메서드를 한 번 부른다.
- 기반으로 올리는 대역 설정은 `testsupport/` 로 옮긴다. `@TestConfiguration` 을 떼고 `IntegrationTestDoubles` 의 빈으로 합쳐도 된다. 지금 testsupport 밖에 있는 것은 `attention/AttentionTestCandidates`, `connector/ConnectorPolicyTestDoubles`, `ResultDeliveryRecordTest.TestResults`, `PersonAccessRollbackTest.FailingRevoker`, `DelegationWakeUserLimitTest` 의 두 설정이다.
- 기반으로 올리는 대역은 기본이 꺼짐이고, 꺼진 상태에서 운영 동작에 아무 영향이 없어야 한다. 켜는 검사는 그 대역을 `@Autowired` 로 받아 켜고, 공통 확장이 검사 뒤에 끈다(대역마다 `reset()` 을 두고 `IntegrationTestIsolation` 이 부른다).
- `ConnectorPolicyTestDoubles` 는 커넥터 카탈로그 캐시에 시각을 옮길 수 있는 시계를 넣는다. 운영 `ConnectorCatalogCache` 와 `SkillCommandCatalog` 의 `@Autowired` 생성자가 `Clock` 빈을 받게 하면 기반의 `TestClock` 이 들어간다(운영의 `Clock` 빈은 `Clock.systemUTC()` 라 동작이 같다). 그 뒤 검사는 `TestClock.advance` 로 시간을 옮긴다. 이 변경은 운영 코드 두 곳이다.

## 작업 항목

### 1. 기반 spy 를 늘린다

`BackendIntegrationTest` 의 `@MockitoSpyBean(types = ...)` 에 위 표의 「기반 spy」 타입과 `StubHermesRunsClient`(필요하면)를 더한다. 각 검사의 필드를 `@Autowired` 로 바꾸고 `when` 을 `doReturn` 으로 바꾼다.

### 2. 커넥터 카탈로그 캐시와 스킬 캐시의 시계

구현 중 정정: 커넥터 카탈로그 캐시는 시험 시계를 받지 않는다. 시계를 과거로 멈추는 검사가 있으면 앞 검사의 카탈로그가 보관 시간 안으로 들어와 새기 때문이다. 카탈로그는 test profile 의 짧은 보관 시간(1ms)과 실패 기억 시간(1ms)으로 실제 시각에 따라 지나고, `expireCatalog()` 는 그 시간이 지나기를 기다린다. 아래 문단에서 `ConnectorCatalogCache` 에 관한 것은 이 정정이 앞선다.

`backend/src/main/java/com/bifos/assistant/connector/application/ConnectorCatalogCache.java` 와 `backend/src/main/java/com/bifos/assistant/skill/application/SkillCommandCatalog.java` 의 `@Autowired` 생성자가 `Clock` 을 받게 한다.
`ConnectorPolicyTestDoubles` 의 캐시 대체를 지우고, `ChangeRecorder` 는 꺼 둔 대역으로 기반에 올린다. `expireCatalog()` 는 `TestClock.advance(TTL + 1초)` 로 바꾼다.
`ChatServiceTest.SkillCatalogClock` 을 지우고 `SKILL_CLOCK.advance` 를 `TestClock.advance` 로 바꾼다. 그 검사 안에서 다른 시간 계산이 바뀌지 않는지 본다.
`application-test.yml` 의 `catalog-ttl: 1ms` 는 남긴다(시간을 옮기지 않는 다른 검사가 앞 검사의 카탈로그를 보지 않게).
시계를 고정하는 검사(`AttentionServiceTest` 등 `clock.set` 을 쓰는 검사)에서는 `TestClock` 이 멈춰 있어 `1ms` 도 지나지 않는다. 그 검사들이 검사 도중 카탈로그나 스킬 목록이 바뀌는 것을 보지 않는지 확인하고, 보면 그 지점에서 `clock.advance` 를 부른다.

### 3. 스케줄러 대역

`DelegationWakeUserLimitTest.CapturingSchedulerConfig` 는 `TaskScheduler` 를 바꿔 끼운다. 운영 코드에는 `TaskScheduler` 빈이 없고 Spring Boot 자동 설정의 `ThreadPoolTaskScheduler` 를 쓴다. 그 자동 설정은 다른 스케줄러 빈이 있으면 만들어지지 않는다. 그래서 기반 스케줄러는 `ThreadPoolTaskScheduler` 를 상속하고, 꺼져 있으면 `super.schedule(...)` 로 실제로 예약하며 켜면 예약을 붙잡는다. 이 빈이 `@Scheduled` 실행도 맡는다. `RetryThreads` 도 꺼 둔 대역으로 기반에 둔다. 두 검사가 켠다.

### 4. 모델 tier 검사

두 검사는 기동 때 `ModelTierSeedImporter.run` 이 한 일을 본다. `@OverrideProperties` 로 값을 바꾼 뒤 `ModelTierSeedImporter.run(...)` 을 검사 안에서 직접 부르고 같은 단언을 한다. 앞 검사가 남긴 tier 정의 줄을 검사 전에 지운다.

### 5. 구조 규칙

`ArchitectureRules` 에 `TESTS` 대상 규칙을 하나 더한다. `testsupport` 밖의 검사 클래스는 아래를 갖지 않는다.
- `@MockitoBean`, `@MockitoSpyBean` 필드와 클래스에 붙은 `@MockitoBean`, `@MockitoSpyBean`
- `@Import`, `@TestPropertySource`, `@DynamicPropertySource`, `@SpringBootTest`, `@ContextConfiguration`, `@ActiveProfiles`, `@DirtiesContext`
- `@TestConfiguration` 클래스(검사 클래스 안의 중첩 클래스 포함). Spring Boot 는 `@Import` 없이도 중첩 `@TestConfiguration` 을 찾아 컨텍스트를 나눈다. 예외는 MySQL 태그 검사(`@Tag("mysql")` 이거나 이름에 `Mysql` 이 든 클래스)다.
`ArchitectureRulesTest` 에서 `TESTS` 로 검사하고 기준 파일은 비어 있다.

### 6. 이 phase 를 검증하는 검사

위 구조 규칙이 검증이다. 규칙이 실제로 잡는지 확인하려고 임시 검사 클래스에 `@MockitoBean` 필드와 중첩 `@TestConfiguration` 을 두어 규칙이 위반을 보고하는 것을 본 뒤 지운다(커밋하지 않는다). 옮긴 검사는 기존 단언이 그대로 통과해야 한다.

## 검증

```bash
# cwd: 저장소 root
(cd backend && ./gradlew test --tests '*ArchitectureRulesTest' --tests '*Attention*' --tests '*Connector*' --tests '*ModelTier*' --tests '*SkillViewStreamTest' --tests '*ChatServiceTest' --tests '*Usage*' --tests '*Execution*')
(cd backend && ./gradlew test)
(cd backend && ./gradlew qualityCheck)
scripts/quality.sh check
git grep -ln "@MockitoBean\|@MockitoSpyBean\|^@Import\|^@SpringBootTest" -- backend/src/test
```

- 모두 종료 코드 0
- 마지막 줄의 결과가 `testsupport/` 아래 파일과 MySQL 기준 클래스(`MysqlMigrationTest`, `RepositoryQueryMysqlTest`, `CollationMixQueryMysqlTest`)뿐이다

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillCommandCatalog.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/testsupport/*.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/**/*.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` | 수정 |
| `backend/src/test/resources/application-test.yml` | 수정 |
| `backend/config/archunit/store/stored.rules` | 수정 |
| `backend/config/archunit/store/*` | 신규 |
