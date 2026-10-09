# Phase 02. 정리 작업이 지운 지 7일이 지난 에이전트를 지운다

**Execution profile**: deep

## 목표

`AgentPurger` 가 매분 지운 지 `assistant.agents.purge-after` 가 지난 에이전트를 골라, 관리형 profile 을 한 번 더 거두고 phase 01 의 `AgentPurgeWriter` 로 행을 지운다.
실패한 에이전트는 기다리는 간격을 늘리며 다음 차례에 다시 본다.

**범위 외**: 딸린 줄을 지우는 트랜잭션은 phase 01 이 만들었다. 화면 표기는 phase 03 이다.

## 컨텍스트

**근거 문서**: `backend/docs/adr/ADR-20261009-agent-purge.md`, `backend/docs/flow.md` 의 「지운 에이전트 정리」 흐름도와 「갈리는 지점」 표.

- 본보기: `backend/src/main/java/com/bifos/assistant/chat/application/ConversationPurger.java`. `@Scheduled(cron = "${assistant.chat.purge-cron}")` 의 `runScheduled()` 가 `purgeDue(clock.instant())` 를 부르고, 실패한 번호를 `Map<Long, Backoff>` 에 두고 1분에서 한 시간까지 두 배씩 늘리며, 다섯 번째 잇단 실패에 error 로그를 남긴다. 한 차례 시간 상한은 20초다. 같은 모양을 따른다.
- phase 01 이 만든 것: `agent/application/AgentPurgeWriter.purge(Long agentId, Instant cutoff)` 가 `agent/application/model/AgentPurgeOutcome`(`PURGED`, `WAITING`, `GONE`)을 돌려준다. port `agent/application/AgentPurgeParticipant` 의 `blocksPurge(Long agentId)` 는 정리 트랜잭션 안에서만 부를 수 있다(참여자가 `Propagation.MANDATORY`).
- profile 거두기는 port `agent/application/ProfileProvisioning.deprovision(String profileName)` 다. 구현 `people/application/HermesProfileProvisioner` 가 토큰 폐기, `HermesDashboardClient.deleteProfile`(404 는 정상), key 파일 `deleteIfExists` 를 한다. 여러 번 불러도 된다.
- 스킬 디렉터리는 `agent/application/ProfileSkillFiles.deleteAll(String profile)` 이다. 실패해도 정리를 멈추지 않고 warn 로그 `지운 에이전트의 스킬 디렉터리를 지우지 못했다 agentId={}` 만 남긴다. `AgentLifecycleService.removeSkillDirectory` 와 달리 profile 이름은 적지 않는다.
- 설정: `agent/application/AgentProperties` 는 `@ConfigurationProperties(prefix = "assistant.agents")` 인 record `AgentProperties(Integer maxPerUser)` 이고 compact 생성자가 기본값을 채운다. `new AgentProperties(` 를 부르는 코드는 지금 없다(`git grep` 으로 다시 확인한다).
- 시험 컨텍스트: `@BackendIntegrationTest` 에서 `HermesDashboardClient` 는 `@MockitoBean` 이다(`testsupport/BackendIntegrationTest.java`). 시험이 `@Autowired HermesDashboardClient dashboard` 로 받아 `verify` 와 `doThrow` 를 쓴다.

## 의도 메모

- profile 거두기는 트랜잭션 밖에서 하고, 그 앞에 기다릴지를 읽기 트랜잭션으로 먼저 본다. 기다리는 에이전트마다 매분 대시보드를 부르지 않게 한다. 기다릴지는 쓰기 트랜잭션에서 한 번 더 본다.
- 관리형이 아닌 profile 은 Hermes 를 부르지 않는다. 운영에서 만든 profile 이다.
- 로그에는 에이전트 번호와 수만 적는다. 이름과 profile 이름을 적지 않는다.

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/agent/application/AgentProperties.java` 수정

record 를 `AgentProperties(Integer maxPerUser, Duration purgeAfter)` 로 넓힌다. compact 생성자에서 `purgeAfter` 가 null 이면 `Duration.ofDays(7)`. `@param purgeAfter` Javadoc: 지운 뒤 행을 실제로 지우기까지 기다리는 기간.

`backend/src/main/resources/application.yml` 의 `assistant.agents` 아래에 더한다.

```yaml
    # 지운 에이전트의 행을 실제로 지우기까지 기다리는 기간(ADR-20261009 / agent-purge)
    purge-after: 7d
    # 지운 에이전트를 정리하는 시각(초 포함 6필드). 실패한 에이전트는 다음 차례에 다시 본다
    purge-cron: "40 * * * * *"
```

`backend/src/test/resources/application-test.yml` 의 `assistant:` 아래에 `agents:` 와 `purge-cron: "-"` 를 더하고, 검사는 메서드를 직접 부른다는 주석을 단다. 이미 `assistant.agents` 절이 있으면 그 안에 더한다.

### 2. `backend/src/main/java/com/bifos/assistant/agent/infra/AgentRepository.java` 수정

```java
@Query("""
        select a.id from Agent a
         where a.deletedAt is not null and a.deletedAt <= :cutoff and a.id not in :skipped
         order by a.deletedAt asc, a.id asc
        """)
List<Long> findPurgeCandidates(
        @Param("cutoff") Instant cutoff, @Param("skipped") Collection<Long> skipped, Pageable page);
```

`skipped` 가 비면 안 되므로 부르는 쪽이 기다리는 에이전트가 없을 때 `List.of(-1L)` 을 넘긴다(`ConversationPurger.NONE_SKIPPED` 와 같다). 에이전트 표는 작아 색인을 더하지 않는다.

### 3. `backend/src/main/java/com/bifos/assistant/agent/application/AgentPurgeWriter.java` 수정

```java
/** 그 에이전트를 지금 지울 수 있는가. 지운 지 cutoff 앞이고 기다리라는 참여자가 없어야 한다. 잠그지 않는다. */
@Transactional(readOnly = true)
public boolean ready(Long agentId, Instant cutoff)
```

`agents.findById` 로 읽어 `purge` 의 1, 2 단계와 같은 조건을 본다.

### 4. `backend/src/main/java/com/bifos/assistant/agent/application/AgentPurger.java` 신규

`@Component`, `@Slf4j`. 필드: `AgentRepository agents`, `AgentPurgeWriter writer`, `ProfileProvisioning provisioner`, `ProfileSkillFiles skillFiles`, `AgentProperties properties`, `Clock clock`, `Map<Long, Backoff> backoffs`(`ConcurrentHashMap`).
상수: `BATCH = 20`, `TICK_BUDGET = Duration.ofSeconds(20)`, `ALERT_ATTEMPTS = 5`, `FIRST_BACKOFF = Duration.ofMinutes(1)`, `MAX_BACKOFF = Duration.ofHours(1)`.

```java
@Scheduled(cron = "${assistant.agents.purge-cron}")
public void runScheduled()

/** @return 이번 차례에 지운 에이전트 수 */
public int purgeDue(Instant now)
```

`purgeDue` 의 차례:

1. `cutoff = now.minus(properties.purgeAfter())`. 기다리는 간격 안의 번호를 빼고 `agents.findPurgeCandidates(cutoff, skipped, PageRequest.of(0, BATCH))`.
2. 번호마다(시간 상한을 넘으면 멈춘다):
   - `writer.ready(id, cutoff)` 가 거짓이면 `waiting` 을 센다.
   - `agents.findById(id)` 의 `profileManaged()` 가 참이면 `provisioner.deprovision(hermesProfile())` 와 스킬 디렉터리 지우기.
   - `writer.purge(id, cutoff)` 가 `PURGED` 면 `purged` 를 세고 그 번호의 간격을 지운다. `WAITING` 이면 `waiting`, `GONE` 이면 센 것 없이 넘어간다.
   - `RuntimeException` 이면 `failed` 를 세고 간격을 늘린다. warn 로그 `지운 에이전트를 정리하지 못했다 agentId={} attempts={} error={} code={}`. 다섯 번째면 같은 내용을 error 로. `code` 는 `ApiException` 이면 `api.code().name()`, 아니면 `-`.
3. `purged`, `failed` 가 하나라도 있으면 info 로그 `지운 에이전트 정리 purged={} waiting={} failed={}`.

클래스 Javadoc 에 ADR-20261009 / agent-purge 와 차례를 적는다. 기다리는 간격은 메모리에만 둔다는 것도 적는다.

### 5. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/agent/AgentPurgerTest.java` 신규

`@BackendIntegrationTest`. `@Autowired` 로 `AgentPurger`, `AgentRepository`, `AppUserRepository`, `ConversationRepository`, `JdbcTemplate`, `HermesDashboardClient`(mock) 를 받는다.
에이전트는 검사마다 새로 만들고 `Agent.markManagedProfile()` 로 관리형을 정한다. profile 이름과 code 는 `purge-` 와 소문자 무작위 값을 이어 만든다(`HermesProfileName` 규칙 `[a-z0-9][a-z0-9-]{0,63}` 에 맞아야 key 파일 지우기가 `VALIDATION_FAILED` 를 내지 않는다). `deleted_at` 은 `Agent.markDeleted(instant)` 뒤 `agents.saveAndFlush` 로 적는다. `JdbcTemplate` 으로 적으면 JVM 시간대가 섞인다.
`purgeDue` 의 반환값은 정확한 수로 단언하지 않는다. 다른 검사가 남긴 2000년대의 지운 에이전트가 함께 지워진다. 에이전트마다 행이 남았는지로 본다.
`NOW = 2000-01-20T00:00:00Z` 로 두고 지운 시각을 그 앞으로 정해, 다른 검사가 실제 시각으로 지운 에이전트가 후보에 들지 않게 한다.

- 「지운 지 7일이 지난 관리형 에이전트는 profile 을 거두고 행을 지운다」: `deleted_at = NOW - 8일`. `purgeDue(NOW)` 가 1 이상, 행이 없고, `verify(dashboard).deleteProfile(그 profile)`.
- 「7일이 지나지 않은 에이전트는 남긴다」: `deleted_at = NOW - 6일`. 행이 남고 `deleteProfile` 을 부르지 않는다.
- 「지우지 않은 에이전트는 건드리지 않는다」: `deleted_at` 이 null. 행이 남는다.
- 「관리형이 아닌 에이전트는 Hermes 를 부르지 않고 지운다」: 행이 없고 `verify(dashboard, never()).deleteProfile(그 profile)`.
- 「지웠지만 정리되지 않은 대화가 있으면 Hermes 를 부르지 않고 미룬다」: 그 에이전트의 대화를 만들어 `deleted_at` 만 적는다. 행이 남고 `deleteProfile` 을 부르지 않는다.
- 「profile 거두기가 실패하면 행을 남기고 간격이 지난 뒤 다시 지운다」: `doThrow(new ApiException(ErrorCode.HERMES_UNAVAILABLE, "down"))` 로 실패시킨다(`ErrorCode` 에 있는 Hermes 실패 코드를 하나 고른다). `purgeDue(NOW)` 뒤 행이 남는다. `purgeDue(NOW.plusSeconds(30))` 는 그 에이전트를 보지 않는다(`deleteProfile` 호출 수가 1 그대로). 실패를 풀고 `purgeDue(NOW.plusSeconds(61))` 뒤 행이 없다.

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.agent.AgentPurgerTest' --tests 'com.bifos.assistant.agent.AgentPurgeWriterTest' --tests 'com.bifos.assistant.architecture.*'
cd backend && ./gradlew spotlessCheck checkstyleMain checkstyleTest
.omc/scripts/heavy-lock scripts/check-mysql-migration.sh --tests 'com.bifos.assistant.RepositoryQueryMysqlTest'
```

- 마지막 줄: 새 저장소 메서드 `findPurgeCandidates` 를 `RepositoryQueryMysqlTest` 가 Docker 의 MySQL 8.4 에서 실행한다. 종료 코드 0.
- 구조 규칙에 `ConfigurationProperties 클래스에는 Validated 가 붙는다` 가 있다. `AgentProperties` 는 이미 붙어 있다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentProperties.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentPurger.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentPurgeWriter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/infra/AgentRepository.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/resources/application-test.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentPurgerTest.java` | 신규 |
