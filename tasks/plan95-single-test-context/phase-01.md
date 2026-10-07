# Phase 01. 실행 중에 쓰는 설정을 LiveProperties 로 읽는다

**Execution profile**: deep

## 목표

운영 코드가 아래 설정 record 를 직접 주입받지 않고 `LiveProperties<T>` 에서 쓸 때마다 읽게 한다.
운영 구현은 기동 값을 그대로 돌려주므로 운영 동작은 바뀌지 않는다. 검사가 값을 바꾸는 쪽은 phase 02 가 만든다.

**범위 외**: 검사 쪽 바꾸기 구현과 `@OverrideProperties`(phase 02), 검사 이전(phase 02, 03).

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261007-live-properties.md`, `docs/backend/packages.md` 의 `shared/config` 행.

경로는 모두 `backend/src/main/java/com/bifos/assistant/` 아래다.

| 설정 record | prefix | 사용처 |
| --- | --- | --- |
| `chat/application/DelegationWakeProperties` | `assistant.delegation-wake` | `chat/application/DelegationWakeService` |
| `usage/application/UserExecutionProperties` | `assistant.user-execution` | `usage/application/UserExecutionLimiter` |
| `proactive/application/ProactiveCheckProperties` | `assistant.proactive-check` | `proactive/application/ProactiveCheckReadiness`, `ProactiveCheckGuard`, `ProactiveCheckRun`(Deps), `ProactiveCheckService` |
| `chat/application/StarterProperties` | `assistant.starters` | `chat/application/StarterSuggestionService` |
| `orchestration/application/DelegationProperties` | `assistant.delegation` | `mcp/application/McpToolService`, `connector/application/ConnectorActionService`, `orchestration/application/AgentDelegationService`, `DelegationOutput`. `AgentRunner` 는 Javadoc 에서만 가리키므로 바꾸지 않는다 |
| `proactive/application/AutonomyProperties` | `assistant.autonomy` | `proactive/application/AutonomyPolicyService` |
| `usage/infra/PricingProperties` | `assistant.pricing` | `usage/infra/ModelsDevPriceCatalog` |
| `memory/application/MemoryEncryptionProperties` | `assistant.memory.encryption` | `memory/application/MemoryContentCipher` |
| `chat/application/ModelTierProperties` | `assistant.model-tiers` | `chat/application/ModelTierSeedImporter` |
| `connector/application/ConnectorPolicyProperties` | `assistant.connector.policy` | `connector/application/ConnectorPolicyService`, `ConnectorCatalogCache` |
| `memory/application/MemoryProposalProperties` | `assistant.memory.propose` | `memory/application/MemoryProposer` |
| `hermes/HermesProperties` 의 `runTimeout()`, `pollInterval()` 만 | `hermes` | `chat/application/ChatService`, `RestartReconciler`, `usage/application/UserExecutionLimiter` |

`ProactiveCheckProperties` 의 기동 검사(`requireShorterThan`, 같은 파일 67~72행 부근)는 그대로 기동 때 돈다.
`HermesRunEventStream` 과 `HttpHermesRunsClient` 는 `HermesProperties` 를 그대로 주입받는다(ADR 의 예외).
설정 record 는 `AssistantApplication` 의 `@ConfigurationPropertiesScan` 이 빈으로 만든다.

## 의도 메모

- `LiveProperties` 는 운영에서 값을 바꾸는 메서드를 갖지 않는다. 바꾸는 구현은 검사 쪽에만 둔다.
- 한 요청이나 한 메서드 안에서는 `current()` 를 한 번 읽어 지역 변수로 쥔다. 필드로 쥐지 않는다.
- `AgentDelegationService` 의 서버 전체 동시 위임 한도는 지금 생성자에서 `new Semaphore(properties.maxActive())` 로 만든다(`AgentDelegationService.java` 114행 부근). 이것을 「쓰는 수를 세는 `AtomicInteger` 와 `current().maxActive()` 를 비교해 자리를 얻는다」 로 바꾼다. 얻기는 늘린 뒤 한도를 넘으면 되돌리고 거절한다. 돌려주기는 줄인다. 운영에서 한도가 바뀌지 않으므로 동작은 같다.
- 가격표 파일과 암호화 key 표처럼 생성자에서 계산하던 것은 `current()` 가 이전과 다른 record 를 돌려줄 때만 다시 계산한다(참조 비교). 운영에서는 다시 계산하지 않는다.
- `ModelTierSeedImporter` 는 `ApplicationRunner` 라 기동 때만 돈다. `run` 이 `current()` 를 읽게만 바꾸고 동작은 그대로 둔다.

## 작업 항목

### 1. `shared/config/LiveProperties.java`, `LivePropertiesConfig.java`

```java
public interface LiveProperties<T> {
    /** 지금 쓸 설정이다. 운영에서는 기동 때 바인딩한 값이다. */
    T current();

    /** 설정 record 의 타입이다. 검사 쪽 구현이 prefix 를 찾는 데 쓴다. */
    Class<T> type();

    static <T> LiveProperties<T> fixed(Class<T> type, T value) { ... }
}
```

`LivePropertiesConfig` 는 `@Configuration` 이고 위 표의 record 마다 `LiveProperties<그 record>` 빈을 하나씩 만든다. 빈 이름은 `<record 이름 lowerCamel>Live` 다(예: `userExecutionPropertiesLive`).
Javadoc 에 ADR 을 적는다.

### 2. 사용처를 바꾼다

위 표의 사용처가 record 대신 `LiveProperties<record>` 를 주입받고 쓸 때마다 `current()` 를 읽는다.
`ProactiveCheckRun.Deps` 의 설정 칸도 `LiveProperties` 로 바꾼다.
`ModelsDevPriceCatalog` 는 `current().catalogPath()` 가 바뀌면 파일과 스냅숏을 다시 정한다. `MemoryContentCipher` 는 `current()` record 가 바뀌면 key 표를 다시 만든다.
명시 생성자로 대역을 넣는 검사(`ModelsDevPriceCatalog` 의 `Clock` 생성자 등)는 `LiveProperties.fixed(...)` 를 넘기게 고친다.

### 3. 구조 규칙

`backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` 에 규칙 둘을 더한다. `MAIN` 에만 건다.
- 위 표의 record 타입(Hermes 제외)을 생성자 인자나 필드로 갖는 운영 클래스는 `LivePropertiesConfig` 뿐이다.
  예외는 기동 검사 `ProactiveCheckProperties$RunTimeoutCheck`(`ProactiveCheckProperties.java` 71행 부근)다. 이름으로 적는다.
- `HermesProperties.runTimeout()` 과 `pollInterval()` 을 부르는 운영 클래스는 `LivePropertiesConfig` 를 거친 사용처와 `HermesRunEventStream`, `HttpHermesRunsClient`, `ProactiveCheckProperties`(기동 검사)뿐이다. 이 규칙은 「`HermesProperties` 를 필드로 갖고 그 두 메서드를 부르는 클래스」 가 위 예외뿐임을 확인한다.

`ArchitectureRulesTest` 에 기존 모양(`FreezingArchRule.freeze(...).check(MAIN)`)으로 더하고, `docs/backend/quality.md` 「규칙을 새로 더했다」 절차로 빈 기준 파일을 만든다.

### 4. 이 phase 를 검증하는 검사

`backend/src/test/java/com/bifos/assistant/shared/config/LivePropertiesTest.java`: `fixed` 가 같은 값을 돌려주고 `type()` 이 맞다.
`ModelsDevPriceCatalog` 의 기존 단위 검사에 「`current()` 가 다른 경로를 돌려주면 그 파일의 가격을 읽는다」 를 더한다. 같은 record 를 계속 돌려주면 파일을 다시 열지 않는지도 본다.
`backend/src/test/java/com/bifos/assistant/orchestration/AgentDelegationServiceRaceTest.java` 에 동시 위임 한도를 보는 단위 검사를 더한다(`current()` 가 바뀌는 `LiveProperties` 는 람다나 작은 클래스로 만든다): 한도 1 에서 자리를 하나 얻으면 둘째는 거절되고, `current()` 가 한도 2 를 돌려주면 둘째가 얻어진다. 돌려준 뒤에는 다시 얻을 수 있다.

## 검증

```bash
# cwd: 저장소 root
(cd backend && ./gradlew test --tests '*LivePropertiesTest' --tests '*ModelsDevPriceCatalog*' --tests '*MemoryContentCipher*' --tests '*ArchitectureRulesTest')
(cd backend && ./gradlew archTest --rerun)
(cd backend && ./gradlew test)
(cd backend && ./gradlew qualityCheck)
scripts/quality.sh check
```

- 모두 종료 코드 0
- 새 구조 규칙의 기준 파일은 비어 있다

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/shared/config/LiveProperties.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/shared/config/LivePropertiesConfig.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/**/*.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/shared/config/LivePropertiesTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/**/*Test.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` | 수정 |
| `backend/config/archunit/store/stored.rules` | 수정 |
| `backend/config/archunit/store/*` | 신규 |
