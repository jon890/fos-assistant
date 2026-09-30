# backend

Control Plane 이다. Spring Boot 4 와 MySQL 8.4 를 쓴다.

저장소 전체에 걸리는 규칙은 루트 [`AGENTS.md`](../AGENTS.md) 가 갖는다.
공개 저장소에 무엇을 적지 않는지도 그 문서가 정한다.

## 패키지 배치

도메인별로 나누고 각 도메인 안은 `presentation` 에서 `application` 을 거쳐 `infra` 와 `domain` 으로 흐른다.
`presentation` 은 `infra` 를 바로 쓰지 않는다. 컨트롤러가 저장소를 바로 쓰면 권한 확인과 트랜잭션 경계를 서비스가 갖지 못한다.
검사: `ArchitectureRules.LAYER_DIRECTION`
자세한 것은 [`docs/code-architecture.md`](../docs/code-architecture.md) 에 있다.

### 데이터 클래스는 컨트롤러 안에 두지 않는다

**`presentation` 의 요청과 응답 모양은 그 패키지의 `*Dtos.java` 하나에 모은다.**
컨트롤러 파일에는 경로와 권한과 흐름만 남긴다.
record 가 컨트롤러 안에 있으면 그 파일이 길어지고, 같은 모양을 다른 컨트롤러가 쓸 때
컨트롤러를 import 하게 된다.
검사: `ArchitectureRules.CONTROLLERS_HAVE_NO_NESTED_RECORDS`

한 패키지에 컨트롤러가 여럿이어도 `*Dtos.java` 는 하나다.
`AgentController` 와 `AgentAdminController` 가 `AgentDtos` 를 함께 쓴다.

`application` 과 `domain` 은 타입 하나에 파일 하나다.
서비스가 돌려주는 결과 타입도 그 서비스 안에 두지 않고 따로 뺀다.

예외는 **그 타입 밖에서 쓰이지 않는 값**이다.
`ModelPrice.ContextTier` 처럼 바깥 record 의 일부인 것은 그 안에 둔다.

## 기술 주의점

- **Spring Boot 4 는 Jackson 3 을 쓴다.**
  `com.fasterxml.jackson` 이 아니라 `tools.jackson` 을 import 한다.
  검사: `ArchitectureRules.NO_JACKSON_2_DATABIND`
- **`RestClient.Builder` 는 자동 구성되지 않는다.**
  `RestClient.builder()` 로 직접 만들고 timeout 을 준다.
- **`src/test/resources/application-test.yml` 은 test profile 전용이다.**
  `application.yml` 이라는 이름으로 두면 `smokeRun` 이 실제 설정 대신 이 파일을 읽는다.

## 구조 규칙

패키지 구조 규칙은 `src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` 가 갖는다.
`./gradlew test` 가 다른 테스트와 함께 검사하고, `./gradlew archTest` 는 구조 규칙만 검사한다.
규칙을 도구 설정으로 두는 근거는 [ADR-040](../docs/adr/ADR-040-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md) 에 있다.

| 규칙 | 뜻 |
| --- | --- |
| `TOP_LEVEL_PACKAGES_FREE_OF_CYCLES` | 최상위 패키지 사이의 간선이 순환에 속하지 않는다 |
| `SHARED_DOES_NOT_DEPEND_ON_DOMAINS` | `shared` 는 다른 최상위 패키지를 쓰지 않는다 |
| `LAYER_DIRECTION` | 아래 층이 위 층을 쓰지 않는다. `presentation` 은 `infra` 를 바로 쓰지 않고 `application` 을 거친다. 컨트롤러가 저장소를 바로 쓰면 권한 확인과 트랜잭션 경계를 서비스가 갖지 못한다 |
| `DOMAIN_DOES_NOT_DEPEND_ON_WEB` | `domain` 은 Spring Web, HTTP, Servlet 타입과 `presentation` 을 쓰지 않는다 |
| `ORCHESTRATION_DOES_NOT_DEPEND_ON_MCP` | `orchestration` 은 `mcp` 를 쓰지 않는다 |
| `MCP_DOES_NOT_CALL_HERMES` | `mcp` 는 Hermes 를 부르는 타입을 쓰지 않는다. 이름이 `Client` 로 끝나는 타입과 `HermesRunEventStream`, `HermesProfileKeyStore` 가 대상이다. `HermesProfileName` 같은 이름 규칙 값은 쓴다 |
| `ORCHESTRATION_DOES_NOT_CALL_CHAT_SERVICE` | `orchestration` 은 `ChatService` 를 쓰지 않는다 |
| `HERMES_DOES_NOT_DEPEND_ON_PEOPLE` | `hermes` 는 `people` 을 쓰지 않는다 |
| `NO_JACKSON_2_DATABIND` | Jackson 2 의 `core` 와 `databind` 를 쓰지 않는다. `com.fasterxml.jackson.annotation` 은 Jackson 3 도 쓰므로 허용한다 |
| `CONTROLLERS_HAVE_NO_NESTED_RECORDS` | 컨트롤러 안에 record 를 두지 않는다 |
| `TEST_METHODS_HAVE_DISPLAY_NAME` | `@Test` 와 `@ParameterizedTest` 메서드에는 `@DisplayName` 이 붙는다. 테스트 클래스만 읽는다 |
| `TRANSACTIONAL_ONLY_IN_APPLICATION` | `application` 밖의 클래스와 메서드에는 Spring 과 Jakarta 의 `@Transactional` 을 붙이지 않는다. 트랜잭션 경계는 유스케이스를 아는 층이 정한다. 컨트롤러와 저장소에 두면 경계가 둘로 갈린다 |
| `NO_DIRECT_INSTANT_NOW` | `Instant.now()` 를 직접 부르지 않는다. 시각을 주입받아야 테스트가 시각을 고정한다. `Clock` 을 받는 `Instant.now(Clock)` 은 허용한다 |
| `MESSAGE_DIGEST_ONLY_IN_SHA256` | `MessageDigest.getInstance` 는 `shared.util.Sha256` 만 부른다. 해시 구현을 한 곳에 둔다 |
| `CONFIGURATION_PROPERTIES_ARE_VALIDATED` | `@ConfigurationProperties` 클래스에는 `@Validated` 도 붙인다. 잘못된 설정은 기동에서 멈춘다 |
| `SERVICES_DO_NOT_EXPOSE_NESTED_TYPES` | `@Service`, `@Component`, `@Repository` 클래스와 `infra` 안의 클래스에 든 중첩 타입은 `private` 이다. 서비스가 돌려주는 모델은 서비스 파일 밖으로 뺀다. 캐시 키 같은 구현 세부는 `private` 으로 둔다. 익명 클래스와 지역 클래스는 대상이 아니다 |
| `ENUMERATED_FIELDS_USE_DOMAIN_TYPE` | `@Entity` 의 `@Enumerated` 필드 타입은 `<기능>.domain.type` 에 있다. 저장되는 값은 바꾸면 마이그레이션을 판단해야 하므로 한곳에 모아 보이게 한다 |
| `DOMAIN_TYPE_DEPENDS_ON_NOTHING_ABOVE` | `domain.type` 의 클래스는 `application`, `infra`, `presentation` 을 쓰지 않는다. 저장되는 enum 은 가장 아래 층이다 |

**순환 규칙은 패키지 간선 하나를 위반 하나로 센다.**
`B` 에서 `A` 로 돌아올 수 있을 때 간선 `A -> B` 가 위반이다.
이미 있는 간선 위에 클래스 의존을 더하는 것은 통과하고, 순환을 늘리는 새 간선만 실패한다.
`shared` 는 모든 도메인이 쓰는 기반 패키지라 이 그래프에서 빼고, `SHARED_DOES_NOT_DEPEND_ON_DOMAINS` 가 따로 막는다.
규칙은 컴파일한 클래스를 읽는다. 쓰지 않는 import 는 간선이 되지 않는다.

### enum 은 저장 여부로 둘 곳을 정한다

| 종류 | 위치 |
| --- | --- |
| 엔티티에 `@Enumerated` 로 저장되는 enum | `<기능>.domain.type` |
| 저장되지 않는 서비스 결과와 화면용 enum | `<기능>.application.model` |
| `ErrorCode` | `shared.error` 에 그대로 둔다 |

앞의 둘 가운데 저장되는 쪽만 `ENUMERATED_FIELDS_USE_DOMAIN_TYPE` 이 검사한다.
저장되지 않는 enum 과 `ErrorCode` 는 규칙으로 검사하지 않는다. 리뷰에서 본다.

### 기준 파일

지금 있는 위반은 `backend/config/archunit/store/` 의 기준 파일에 얼려 두고 새 위반만 실패시킨다.
`stored.rules` 가 규칙의 `as(...)` 설명과 기준 파일 이름을 잇는다.
설명을 바꾸면 기준이 새 규칙으로 옮겨지지 않으므로, 설명을 바꿀 때는 그 규칙을 다시 얼린다.

**기준에 든 위반은 허용이 아니라 줄여 갈 목록이다.**
기준마다 연 GitHub 이슈가 있다. 위반을 고치면 그 기준 파일도 같은 커밋에서 줄인다.

평소의 테스트는 기준 파일을 쓰지 않고, 기준과 실제가 어긋나면 실패한다.
쓰기는 Gradle 속성으로만 켠다. 명령은 모두 `backend/` 에서 돈다.

| 언제 | 어떻게 |
| --- | --- |
| 기준에 든 위반을 고쳤다 | 평소 테스트가 `StoreUpdateFailedException: Updating frozen violations is disabled` 로 실패한다. `./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true` 로 기준에서 빼고 그 변경을 같은 커밋에 넣는다 |
| 기준에 든 클래스의 이름만 바꿨다 | 같은 예외로 실패한다. 아래 「새 위반을 받아들이거나 다시 얼린다」 를 따른다 |
| 규칙을 새로 더했다 | `./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true` 로 그 규칙의 기준 파일을 만든다. `allowStoreCreation` 은 `stored.rules` 가 없을 때만 쓴다 |
| 새 위반을 받아들이거나 다시 얼린다 | `./gradlew archTest --rerun --tests '*ArchitectureRulesTest.<메서드>' -Parchunit.freeze.refreeze=true -Parchunit.freeze.store.default.allowStoreUpdate=true`. 까닭을 커밋 메시지와 PR 본문에 적는다 |

**다시 얼릴 때는 `--tests` 로 규칙 하나만 대상으로 삼는다.**
`refreeze` 는 그 실행이 검사하는 모든 규칙을 다시 얼린다.
`--tests` 를 빼면 다른 규칙의 새 위반까지 조용히 기준에 들어간다.

### 코드로 옮기지 않은 문장

「`application` 과 `domain` 은 타입 하나에 파일 하나다」 와 「서비스가 돌려주는 결과 타입도 그 서비스 안에 두지 않는다」 는 규칙으로 옮기지 않았다.
예외인 「그 타입 밖에서 쓰이지 않는 값」 을 기계로 판정할 수 없기 때문이다. 리뷰에서 본다.

## 엔티티와 마이그레이션은 따로 논다

테스트는 엔티티로 스키마를 만들고 운영은 Flyway 가 만든 스키마를 검증한다.
**그래서 둘이 어긋나도 테스트는 통과한다.**

엔티티를 바꾸면 마이그레이션도 함께 바꾸고, 배포 로그에서 `Schema validation` 을 확인한다.
실제로 `@Lob` 이 붙은 문자열이 MySQL 에서 `tinytext` 로 기대돼 기동에 실패한 적이 있다.
길이를 주지 않은 `@Lob` 문자열을 쓰지 말고 `columnDefinition` 으로 못 박는다.

**다만 마이그레이션 검사는 모든 마이그레이션을 H2 의 MySQL 모드에서 돌린다.**
`GroupRenameMigrationTest` 같은 `*MigrationTest` 가 그렇다.
그래서 마이그레이션 SQL 은 MySQL 과 H2 에 함께 있는 함수만 쓴다.
`RANDOM_BYTES` 처럼 H2 에 없는 함수가 필요하면 `db.migration` 패키지에 Java 마이그레이션으로 쓴다.
`V23__ConversationPublicId` 가 그 본보기다.

이미 적용된 마이그레이션 파일을 고치지 않는다. Flyway 의 검사가 실패한다.
새 번호로 파일을 하나 더 만든다.

## 실행 기록

`agent_execution` 한 줄은 실행이 끝난 뒤가 아니라 **시작할 때** 만들어진다.
근거는 [ADR-011](../docs/adr/ADR-011-실행은-시작할-때-기록하고-끝날-때-갱신한다.md) 에 있다.

`RUNNING` 인 줄을 다루는 자리가 둘이다.

| 무엇 | 어떻게 |
| --- | --- |
| 사용량 목록 | 「도는 중」으로 보인다. 소요 시간과 금액은 비운다 |
| 월 비용 합계 | 뺀다. 빼지 않으면 「가격을 찾지 못한 실행」 으로 세어진다 |

금액을 0 으로 채우지 않는다. 0 은 공짜라는 뜻으로 읽힌다.

## 테스트

```bash
# cwd: backend/
./gradlew test
```

**`gradlew` 는 이 디렉터리 안에 있다.** 저장소 루트에서 `./gradlew` 를 부르면 없다.

`test/e2e` 는 저장소 루트에서 돌리고, 앞선 실행이 남긴 데이터에 걸린다.
`gradlew test` 를 건너뛰고 `run.ts` 만 돌리면
`this agent code is already used` 로 실패할 수 있다.

### 테스트 이름

**메서드 이름은 영문 camelCase 로 짓고, 한국어 문장은 `@DisplayName` 에 쓴다.**
테스트 보고서에는 `@DisplayName` 의 문장이 보이고, 메서드 이름은 코드에서 무엇을 확인하는지 드러낸다.
이름은 동사로 시작한다. 예: `assemblesGroupAndUserItemsInLayerOrder`.
검사: `ArchitectureRules.TEST_METHODS_HAVE_DISPLAY_NAME`

- `@Test` 와 `@ParameterizedTest` 메서드에 `@DisplayName` 을 단다.
- `@BeforeEach` 같은 준비 메서드와 정리 메서드는 `setUp`, `tearDown` 관례 이름을 쓰고 `@DisplayName` 을 달지 않는다.
  한 클래스에 여럿이면 하는 일을 이름에 담는다.
- 보조 메서드는 영문 camelCase 로 짓고 `@DisplayName` 을 달지 않는다.
- 테스트 클래스에는 `@DisplayName` 을 달지 않는다. 클래스 이름이 이미 영문이다.

## 주석

주석과 Javadoc 을 한국어로 쓴다.
코드 식별자, 타입, 라이브러리 이름, 명령, 경로는 원문 그대로 둔다.

영어로 남아 있던 주석은 그 파일을 고칠 때 함께 옮긴다.
한 번에 전부 옮기려고 별도 커밋을 만들지 않는다.
읽는 사람이 diff 에서 무엇이 바뀌었는지 놓친다.
