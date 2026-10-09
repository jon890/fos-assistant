# backend 통합 검사

Spring 컨텍스트를 띄우는 backend 검사를 쓰는 방법이다.
왜 컨텍스트를 함께 쓰는지는 [ADR-20261007 / test-context-base](../../backend/docs/adr/ADR-20261007-test-context-base.md) 가 갖는다.

## 기반 주석

Spring 컨텍스트가 필요한 검사는 `@SpringBootTest` 대신 `@BackendIntegrationTest` 를 단다.
주석과 대역은 `backend/src/test/java/com/bifos/assistant/testsupport/` 에 있다.

```java
@BackendIntegrationTest
class ConversationPagingTest {
    @Autowired
    ChatService chat;
}
```

기반이 주는 것은 아래와 같다. mock 과 spy 목록은 `BackendIntegrationTest` 의 주석 선언이, 대역 빈 목록은 `IntegrationTestDoubles` 가 갖는다.

| 무엇 | 검사에서 받는 법 | 검사마다 되돌리는 것 |
| --- | --- | --- |
| 웹 환경 `RANDOM_PORT`, profile `test` | `@LocalServerPort` | |
| Hermes 실행 대역 `StubHermesRunsClient` | `@Autowired StubHermesRunsClient` 나 `HermesRunsClient` | 공통 확장이 `reset()` 한다 |
| 백그라운드 작업 실행기 `TrackingBackgroundTasks`. 운영의 `BackgroundTasks` 자리에 들어간다 | 직접 쓰지 않는다 | 공통 확장이 띄운 작업을 모두 join 한다 |
| 시험 시계 `TestClock`. 운영의 `Clock` 빈 자리에 들어간다 | `@Autowired TestClock clock` 뒤 `clock.set(...)`, `clock.advance(...)` | 공통 확장이 실제 시각으로 되돌린다 |
| Hermes 사건 스트림, toolset, 커넥터, 스킬, 모델, 대시보드 클라이언트의 Mockito mock | `@Autowired` 로 받아 `when(...)` 으로 정한다 | Spring 이 초기화한다 |
| 운영 빈 몇 개의 Mockito spy | `@Autowired` 로 받아 `doReturn(...)`, `verify(...)` 를 쓴다 | Spring 이 초기화한다 |
| 스케줄러 `CapturingTaskScheduler`. 자동 설정 스케줄러 자리에 들어가 `@Scheduled` 실행도 맡는다 | `capture()` 로 켜면 예약을 실행하지 않고 모은다. `drain()` 으로 꺼낸다 | 공통 확장이 `reset()` 해 다시 실제로 예약한다 |
| 꺼 둔 대역(후보 출처, 권한 회수 실패, 결과 출처, 깨우기 재시도, 커넥터 변경 기록) | `@Autowired` 로 받아 켠다 | 공통 확장이 끈다 |
| 먼저 살펴보기 평가의 판단 provider(`fixture-` id 6개) | `@Autowired List<ReplayDecisionProvider>` | 되돌리지 않는다. 운영은 provider 를 id 로만 골라 이 id 를 부르지 않는 검사에 영향이 없다 |
| 브라우저 proxy 대역 `FakeBrowserRuntime` 과 늘 답하는 CDP 대역. 운영 코드는 브라우저 기능이 켜져 있을 때만 부른다 | `@OverrideProperties("assistant.browser.enabled=true")` 로 기능을 켜고 `@Autowired FakeBrowserRuntime` 으로 받는다 | 공통 확장이 `reset()` 한다 |

MySQL 태그 검사의 기준 클래스만 `@SpringBootTest` 로 따로 둔다.

**검사 클래스에 컨텍스트 키를 바꾸는 선언을 두지 않는다.** `@MockitoBean`, `@MockitoSpyBean`, `@Import`, `@TestPropertySource`, `@DynamicPropertySource`, 중첩 `@TestConfiguration`, 슬라이스 주석 등이다. 전체 목록은 구조 규칙 `ArchitectureRules.TESTS_DO_NOT_SPLIT_CONTEXT` 가 갖는다. `@Tag("mysql")` 검사는 예외다.
하나라도 두면 Spring 이 컨텍스트를 새로 띄운다. 구조 규칙이 막는다.
- 기반에 있는 타입은 `@Autowired` 로 받는다.
- 설정 값을 바꿔야 하면 아래 「설정 바꾸기」 를 쓴다.
- mock 이 필요하던 빈은 기반의 spy 로 올리고 `doReturn(...).when(spy)` 로 바꾼다. 정하지 않은 메서드는 실제로 돈다.
- 검사만 쓰는 대역 빈이 필요하면 꺼 두면 아무것도 하지 않는 대역으로 기반에 올리고 그 검사가 켠다. 공통 확장이 검사 뒤에 끈다.

## 설정 바꾸기

운영 코드는 실행 중에 쓰는 설정을 `LiveProperties<T>` 에서 읽는다([ADR-20261007 / live-properties](../../backend/docs/adr/ADR-20261007-live-properties.md)).
검사는 `@OverrideProperties` 로 그 값을 바꾼다. 값의 모양은 `application.yml` 의 키와 같다.

```java
@BackendIntegrationTest
@OverrideProperties({"assistant.user-execution.max-running=2", "assistant.user-execution.background-reserve=1"})
class UserExecutionLimitBackgroundTest { ... }
```

- 공통 확장이 검사마다 시작 전에 적용하고, 끝난 뒤 기동 값으로 되돌린다. 컨텍스트는 새로 뜨지 않는다.
- 바꾼 값도 운영 record 의 생성자를 거쳐 같은 검증을 받는다.
- `LiveProperties` 로 읽지 않는 설정을 적으면 검사가 실패한다. 그 값을 바꾸려면 먼저 운영 사용처를 `LiveProperties` 로 옮긴다.
  prefix 아래에 있어도 설정 record 의 칸이 아닌 키(`assistant.browser.sweep-interval` 처럼 `@Scheduled` 가 기동 때 읽는 값)도 실패한다.
- 여럿이 쓰는 묶음은 이름 있는 주석으로 둔다. `@OverrideProperties` 를 메타 주석으로 갖는다.

| 주석 | 바꾸는 설정 |
| --- | --- |
| `@DelegationWakeEnabled` | 위임 결과로 다음 turn 을 여는 깨우기를 켠다 |
| `@SmallExecutionLimit` | 사용자 동시 실행 한도를 2 로 둔다 |
| `@MemoryEncryptionDisabled` | 민감 memory 암호화 key 를 비운다 |
| `@SamplePriceCatalog` | 가격표를 `pricing/models-dev-sample.json` 으로 둔다 |
| `@LongProactiveCheckTimeouts` | Hermes 실행 상한을 30초, 살펴보기 시간 상한을 20초로 늘린다 |
| `@MemoryProposeEnabled` | 대화 뒤 Memory 제안을 켠다 |

## 검사 사이에 남기지 않는 것

컨텍스트와 H2 DB 를 앞뒤 검사와 함께 쓴다.

- **검사가 띄운 백그라운드 작업은 그 검사 안에서 끝난다.**
  운영 코드는 요청 밖 작업을 `BackgroundTasks` 로 띄운다([ADR-20261007 / background-tasks](../../backend/docs/adr/ADR-20261007-background-tasks.md)).
  기반은 그 자리에 띄운 스레드를 쥐는 `TrackingBackgroundTasks` 를 넣고, 공통 확장 `IntegrationTestIsolation` 이 검사가 끝날 때 모두 join 한다.
  30초 상한은 멈춘 작업을 잡는 데만 쓴다. 넘으면 남은 스레드 이름과 함께 그 검사가 실패한다.
  turn 을 붙잡는 대역(`holdSubmits()` 등)을 쓴 검사는 끝나기 전에 푼다.
  검사 안에서 작업 결과를 단언할 때는 그 검사가 기다릴 조건을 직접 기다린다. 공통 확장의 join 은 검사가 끝난 뒤에 돈다.
- **앞 검사가 남긴 줄에 기대지 않는다.** 사용자와 에이전트는 검사마다 새로 만든다.
  고정 code 를 쓰는 검사가 검사 트랜잭션 안에서 지우고 다시 넣으면, Hibernate 가 넣기를 먼저 내보내 유일 제약에 걸린다. 지운 뒤 `flush()` 한다.
- **기반의 spy 는 `verify(...)` 의 matcher 사이에서 부르지 않는다.** Mockito 가 그 호출을 matcher 를 쓰는 호출로 읽는다. 값을 먼저 지역 변수로 받는다.
- **검사가 바꾼 static 상태는 `@BeforeEach` 에서 되돌린다.**
- **운영 빈이 JVM 메모리에 두는 캐시는 다음 검사에 남는다.** 보관 시간을 test profile 에서 짧게 두거나, 그 캐시가 시험 시계를 쓰게 해 검사가 시간을 옮긴다. 검사가 private 필드를 바꿔 비우지 않는다.
  - 커넥터 카탈로그 캐시는 카탈로그 하나를 열쇠 없이 들고 있어 실제 시각으로 판정한다. 시험 시계를 쓰면 시계를 과거로 멈추는 검사에서 앞 검사의 카탈로그가 보관 시간 안으로 들어온다. test profile 의 보관 시간과 실패 기억 시간을 1ms 로 두고, 지나가게 하려는 검사는 2ms 를 기다린다.
  - 스킬 커맨드 캐시는 `Clock` 빈을 받아 검사에서 `TestClock` 을 쓴다. 열쇠가 에이전트 번호이고 검사마다 에이전트를 새로 만들어, 시계를 과거로 멈춰도 앞 검사의 목록을 받지 않는다.

## 컨텍스트 수 확인

`./gradlew test` 가 띄우는 컨텍스트는 3개 이하다.
전체 검사 뒤 `backend/build/test-results/test/*.xml` 에서 `HikariPool-N` 의 가장 큰 N 이 띄운 컨텍스트 수다.
보존 상한 `spring.test.context.cache.maxSize`(`backend/build.gradle.kts`)는 그 수보다 크게 두어 닫히는 컨텍스트가 없게 한다.
