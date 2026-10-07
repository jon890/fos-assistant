# backend 통합 검사

Spring 컨텍스트를 띄우는 backend 검사를 쓰는 방법이다.
왜 컨텍스트를 함께 쓰는지는 [ADR-095](../adr/ADR-095-backend-통합-검사는-공통-기반-컨텍스트-하나를-함께-쓰고-기동-설정이-다른-검사만-변형을-둔다.md) 가 갖는다.

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

기반이 주는 것은 아래와 같다. 목록 자체는 `BackendIntegrationTest` 의 주석 선언이 갖는다.

| 무엇 | 검사에서 받는 법 | 검사마다 되돌리는 것 |
| --- | --- | --- |
| 웹 환경 `RANDOM_PORT`, profile `test` | `@LocalServerPort` | |
| Hermes 실행 대역 `StubHermesRunsClient` | `@Autowired StubHermesRunsClient` 나 `HermesRunsClient` | 공통 확장이 `reset()` 한다 |
| 백그라운드 작업 실행기 `TrackingBackgroundTasks`. 운영의 `BackgroundTasks` 자리에 들어간다 | 직접 쓰지 않는다 | 공통 확장이 띄운 작업을 모두 join 한다 |
| 시험 시계 `TestClock`. 운영의 `Clock` 빈 자리에 들어간다 | `@Autowired TestClock clock` 뒤 `clock.set(...)`, `clock.advance(...)` | 공통 확장이 실제 시각으로 되돌린다 |
| Hermes 사건 스트림, toolset, 커넥터, 스킬, 모델, 대시보드 클라이언트의 Mockito mock | `@Autowired` 로 받아 `when(...)` 으로 정한다 | Spring 이 초기화한다 |
| 운영 빈 몇 개의 Mockito spy | `@Autowired` 로 받아 `doReturn(...)`, `verify(...)` 를 쓴다 | Spring 이 초기화한다 |

**검사 안에 `@MockitoBean`, `@MockitoSpyBean`, `@Import`, `@DynamicPropertySource` 를 더하면 컨텍스트가 하나 늘어난다.**
기반에 있는 타입은 `@Autowired` 로 받는다.
기반에 없는 빈을 바꿔야 하면 아래 「변형」 을 먼저 본다.

## 변형

기동할 때 읽는 설정이 달라야 하는 검사만 컨텍스트를 따로 둔다.
값 묶음이 같은 검사끼리는 컨텍스트를 함께 쓰므로, 여럿이 쓰는 묶음은 이름 있는 주석으로 둔다.

| 주석 | 바꾸는 설정 | 쓰는 검사 |
| --- | --- | --- |
| `@DelegationWakeEnabled` | 위임 결과로 다음 turn 을 여는 깨우기를 켠다 | 깨우기와 결과 전달 |
| `@SmallExecutionLimit` | 사용자 동시 실행 한도를 2 로 둔다 | 한도에 닿는 경로 |
| `@MemoryEncryptionDisabled` | 민감 memory 암호화 key 를 비운다 | 암호화가 꺼진 동작 |
| `@SamplePriceCatalog` | 가격표를 `pricing/models-dev-sample.json` 으로 둔다 | 금액이 적히는 경로 |

변형 주석은 `@BackendIntegrationTest` 와 함께 단다.
한 검사만 쓰는 값은 `@TestPropertySource(properties = ...)` 로 둔다.

## 검사 사이에 남기지 않는 것

컨텍스트와 H2 DB 를 앞뒤 검사와 함께 쓴다.

- **검사가 띄운 백그라운드 작업은 그 검사 안에서 끝난다.**
  운영 코드는 요청 밖 작업을 `BackgroundTasks` 로 띄운다([ADR-096](../adr/ADR-096-운영의-백그라운드-작업은-한-빈으로-띄우고-검사는-끝날-때-모두-join-한다.md)).
  기반은 그 자리에 띄운 스레드를 쥐는 `TrackingBackgroundTasks` 를 넣고, 공통 확장 `IntegrationTestIsolation` 이 검사가 끝날 때 모두 join 한다.
  30초 상한은 멈춘 작업을 잡는 데만 쓴다. 넘으면 남은 스레드 이름과 함께 그 검사가 실패한다.
  turn 을 붙잡는 대역(`holdSubmits()` 등)을 쓴 검사는 끝나기 전에 푼다.
  검사 안에서 작업 결과를 단언할 때는 그 검사가 기다릴 조건을 직접 기다린다. 공통 확장의 join 은 검사가 끝난 뒤에 돈다.
- **앞 검사가 남긴 줄에 기대지 않는다.** 사용자와 에이전트는 검사마다 새로 만든다.
  고정 code 를 쓰는 검사가 검사 트랜잭션 안에서 지우고 다시 넣으면, Hibernate 가 넣기를 먼저 내보내 유일 제약에 걸린다. 지운 뒤 `flush()` 한다.
- **기반의 spy 는 `verify(...)` 의 matcher 사이에서 부르지 않는다.** Mockito 가 그 호출을 matcher 를 쓰는 호출로 읽는다. 값을 먼저 지역 변수로 받는다.
- **검사가 바꾼 static 상태는 `@BeforeEach` 에서 되돌린다.**
- **운영 빈이 JVM 메모리에 두는 캐시는 test profile 에서 보관 시간을 짧게 둔다.** 예: 커넥터 카탈로그 캐시. 검사가 private 필드를 바꿔 비우지 않는다.

## 보존 상한과 측정

Spring 은 띄운 컨텍스트를 상한까지 보존하고, 넘으면 가장 오래 쓰지 않은 것을 닫는다.
상한은 `backend/build.gradle.kts` 의 `spring.test.context.cache.maxSize` 이고, 그 값의 근거는 그 주석이 갖는다.

변형을 더하거나 기반을 바꾼 뒤에는 컨텍스트 수를 다시 본다.
전체 검사 뒤 `backend/build/test-results/test/*.xml` 에서 `HikariPool-N` 의 가장 큰 N 이 띄운 컨텍스트 수다.
그 수가 상한을 넘으면 밀려난 컨텍스트를 다시 띄우고 있다.
