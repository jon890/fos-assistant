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

## 품질 검사

구조 규칙, 코드 규칙, 포맷은 `./gradlew qualityCheck` 하나가 묶어 검사한다. 파일을 바꾸지 않는다.
backend 와 web 을 함께 검사하려면 저장소 root 에서 `scripts/quality.sh check` 를 쓴다.
기계가 고칠 수 있는 위반은 `scripts/quality.sh fix` 가 고친다.

**기준 파일에 든 위반은 허용이 아니라 줄여 갈 목록이다.**
기준마다 연 GitHub 이슈가 있다. 「구조 규칙」 과 「코드 규칙」 의 기준 파일이 모두 그렇다.
`check` 는 끝에 「경고 목록(실패 아님)」 을 낸다. 파일과 메서드 길이를 넘은 곳이고 쪼갤 후보다.

## 구조 규칙

패키지 구조 규칙은 `src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` 가 갖는다.
`./gradlew test` 가 다른 테스트와 함께 검사하고, `./gradlew archTest` 는 구조 규칙만 검사한다.
규칙을 도구 설정으로 두는 근거는 [ADR-041](../docs/adr/ADR-041-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md) 에 있다.

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
| 기준에 든 위반을 고쳤다 | 평소 테스트가 `StoreUpdateFailedException: Updating frozen violations is disabled` 로 실패한다. `./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true` 로 기준에서 빼고 그 변경을 같은 커밋에 넣는다. `scripts/quality.sh fix` 도 같은 명령으로 기준을 줄인다 |
| 기준에 든 클래스의 이름만 바꿨다 | 같은 예외로 실패한다. 아래 「새 위반을 받아들이거나 다시 얼린다」 를 따른다 |
| 규칙을 새로 더했다 | `./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true` 로 그 규칙의 기준 파일을 만든다. `allowStoreCreation` 은 `stored.rules` 가 없을 때만 쓴다 |
| 새 위반을 받아들이거나 다시 얼린다 | `./gradlew archTest --rerun --tests '*ArchitectureRulesTest.<메서드>' -Parchunit.freeze.refreeze=true -Parchunit.freeze.store.default.allowStoreUpdate=true`. 까닭을 커밋 메시지와 PR 본문에 적는다 |

**다시 얼릴 때는 `--tests` 로 규칙 하나만 대상으로 삼는다.**
`refreeze` 는 그 실행이 검사하는 모든 규칙을 다시 얼린다.
`--tests` 를 빼면 다른 규칙의 새 위반까지 조용히 기준에 들어간다.

### 코드로 옮기지 않은 문장

「`application` 과 `domain` 은 타입 하나에 파일 하나다」 와 「서비스가 돌려주는 결과 타입도 그 서비스 안에 두지 않는다」 는 규칙으로 옮기지 않았다.
예외인 「그 타입 밖에서 쓰이지 않는 값」 을 기계로 판정할 수 없기 때문이다. 리뷰에서 본다.

## 코드 규칙

코드 모양 규칙은 Checkstyle 14.3.0 이 검사한다. Gradle 내장 `checkstyle` 플러그인을 쓴다.
`./gradlew test` 에는 걸리지 않으므로 따로 돌린다.

```bash
# cwd: backend/
./gradlew checkstyleMain checkstyleTest
```

error 는 태스크를 실패시키고, warning 은 실패시키지 않고 보고서에만 남긴다.
보고서는 `build/reports/checkstyle/main.xml`, `test.xml` 이다.
규칙을 도구 설정으로 두는 근거는 [ADR-041](../docs/adr/ADR-041-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md) 에 있다.

### 설정 파일

`config/checkstyle/` 에 셋이 있다. Gradle 이 이 디렉터리를 `config_loc` 으로 넘긴다.

| 파일 | 역할 |
| --- | --- |
| `checkstyle.xml` | 규칙 |
| `suppressions.xml` | 설계상 예외. 앞으로도 허용하는 것이다 |
| `baseline.xml` | 기존 위반. 줄여 가는 목록이다 |

설계상 예외와 기존 위반은 다른 파일에 둔다. 앞의 것은 남고 뒤의 것은 없어져야 하기 때문이다.

### 넣은 규칙

| 규칙 | 심각도 | 까닭 |
| --- | --- | --- |
| `UnusedImports`, `AvoidStarImport`, `RedundantImport`, `IllegalImport` | error | 읽는 사람이 실제 의존을 import 줄에서 바로 본다 |
| 전체 이름 참조 금지 (`fullyQualifiedName`) | error | 코드 안에 `java.util.concurrent.TimeoutException` 같은 이름을 쓰지 않고 import 한다. `import` 와 `package` 줄, 문자열 안의 글, 주석은 뺀다 |
| `EmptyCatchBlock` (예외 변수 이름이 `ignored` 나 `expected` 면 허용) | error | 예외를 삼키는 자리를 이름으로 드러낸다 |
| `EmptyStatement`, `EqualsHashCode`, `StringLiteralEquality`, `FallThrough`, `MissingOverride` | error | 결함으로 이어지는 모양이다 |
| `SimplifyBooleanExpression`, `SimplifyBooleanReturn`, `ModifierOrder`, `UpperEll`, `ArrayTypeStyle` | error | 같은 뜻을 한 모양으로 쓴다 |
| `PackageName`, `TypeName`, `MethodName`, `MemberName`, `ParameterName`, `LocalVariableName`, `LocalFinalVariableName`, `StaticVariableName`, `LambdaParameterName`, `RecordComponentName`, `ClassTypeParameterName`, `MethodTypeParameterName` | error | 기본 패턴 그대로 쓴다 |
| `ConstantName` (`log` 와 대문자 상수 이름) | error | 로거 이름 `log` 는 Lombok `@Slf4j` 가 만드는 이름이다 |
| `EmptyLineSeparator` (메서드와 생성자) | error | 메서드와 생성자 사이에 빈 줄이 한 줄 이상 있어야 읽는 단위가 보인다. 인터페이스의 추상 메서드 선언 사이도 잡는다. 자동으로 고치지 않는 목록에 든다 |
| `OneStatementPerLine` | error | 한 줄에 한 문장 |
| `LeftCurly` (`eol`), `RightCurly` 둘 | error | `void a() { return; }` 처럼 본문을 한 줄에 몰아 쓰지 않는다. `RightCurly` 는 `rightCurlyAlone` (메서드, 생성자, 클래스, `for`, `while`, 초기화 블록) 과 `rightCurlySame` (`try`, `catch`, `finally`, `if`, `else`, `do`) 로 나눈다. 제어문에 `alone` 계열을 걸면 `} else {` 가 위반이 되어 포매터와 어긋나기 때문이다. `rightCurlyAlone` 의 옵션은 `alone_or_singleline` 이다. 포매터가 빈 본문을 `private X() {}` 처럼 한 줄에 쓰므로 `alone` 은 포매터와 어긋난다. 한 줄 본문 `{ return x; }` 은 `LeftCurly` 가 잡는다. `LeftCurly` 는 람다를 대상에서 뺀다. 시험 대역의 `-> { throw ...; }` 한 줄이 흔하다 |
| `NeedBraces` | error | 제어문은 한 문장이어도 중괄호를 쓴다 |
| 직접 만든 로거 금지 (`lombokLogger`) | error | 로거는 Lombok `@Slf4j` 로 둔다. 바이트코드로는 구별되지 않아 소스에서 본다. `Logger x = LoggerFactory.getLogger(...)` 형태의 선언을 잡고, 시험이 로그를 붙잡으려고 `(Logger) LoggerFactory.getLogger(...)` 로 꺼내는 것은 잡지 않는다 |
| 직접 쓴 private 빈 생성자 금지 (`privateEmptyConstructor`) | error | 인스턴스를 만들지 않는 클래스는 `@NoArgsConstructor(access = AccessLevel.PRIVATE)` 로 쓴다 |
| 엔티티의 손 접근자 금지 (`entityHandwrittenAccessor`) | error | `@Entity` 가 있는 파일에서 필드를 그대로 돌려주는 인자 없는 `public` 메서드를 잡는다. 엔티티 접근자는 Lombok `@Getter` 와 `@Accessors(fluent = true)` 로 `code()` 모양을 만든다. 값을 꺼내는 메서드가 두 벌이 되지 않게 하고 record 와 같은 호출 모양을 지킨다. 파일마다 한 번만 보고한다 |
| `FileLength` (500줄) | warning | 쪼갤 후보 목록이다. 빈 줄과 주석을 포함한 전체 줄 수다 |
| `MethodLength` (60줄, 빈 줄과 한 줄 주석은 세지 않는다) | warning | 쪼갤 후보 목록이다 |
| 생성자 주입은 `@RequiredArgsConstructor` (`requiredArgsConstructor`) | warning | `@Service`, `@Component`, `@Repository`, `@RestController`, `@Controller`, `@Configuration` 이 붙고 `@RequiredArgsConstructor` 가 없는 파일에서 인자가 있는 생성자를 잡는다. 테스트 전용 생성자, 설정값 가공처럼 까닭이 있는 경우가 섞여 있어 목록으로만 보인다. 자동으로 고치지 않는다 |

`FileLength`, `MethodLength`, `requiredArgsConstructor` 는 테스트 코드에 걸지 않는다.
테스트는 시나리오가 길고 생성자 주입 규칙과 관계가 없다.

**정규식 규칙은 `id` 를 붙여 기준과 억제를 `id` 로 건다.**
`checks` 로 걸면 같은 종류의 정규식 규칙이 모두 억제된다.

### 뺀 규칙

| 규칙 | 까닭 |
| --- | --- |
| `LineLength`, `Indentation`, `WhitespaceAround`, `CustomImportOrder` | 포매터가 정한다. 둘이 같은 것을 다르게 판정하면 고칠 수 없는 위반이 생긴다 |
| `JavadocMethod`, `JavadocType`, `MissingJavadocMethod` 같은 Javadoc 규칙 | 주석은 한국어로 필요한 곳에만 쓴다(아래 「주석」 절). 모든 메서드에 요구하면 뜻 없는 주석이 늘어난다 |
| `MagicNumber` | 테스트와 설정 기본값에서 대부분 오탐이다 |
| `FinalParameters`, `HiddenField` | 생성자 주입과 record 가 이름을 같게 쓰는 것이 이 저장소의 모양이다 |
| `DesignForExtension` | Spring 빈과 싸운다 |

### 전체 이름을 꼭 써야 할 때

같은 단순 이름의 두 타입을 한 파일에서 써서 import 로 나눌 수 없을 때만 전체 이름을 쓴다.
그 줄 끝에 `// 전체 이름 허용: <까닭>` 주석을 달면 그 줄만 통과한다.

```java
java.util.Date legacy = new java.util.Date(); // 전체 이름 허용: 같은 파일이 java.sql.Date 를 import 한다
```

JPQL 문자열 안의 전체 이름은 규칙 대상이 아니지만, 텍스트 블록은 줄 단위로 읽으면 문자열인지 알 수 없다.
`AgentExecutionRepository` 와 `ExecutionSkillUseRepository` 는 생성자 식이 전체 이름을 요구해 `suppressions.xml` 에 예외로 두었다.
Flyway 가 요구하는 `V<번호>__<이름>` 클래스의 `TypeName` 도 같은 파일에 예외로 둔다.

### 기준 파일

지금 있는 위반은 `config/checkstyle/baseline.xml` 에 `(파일, 규칙)` 한 쌍마다 한 줄로 두고 새 위반만 실패시킨다.
**기준에 든 위반은 허용이 아니라 줄여 갈 목록이다.** 기준마다 연 GitHub 이슈가 있다.

| 언제 | 어떻게 |
| --- | --- |
| 기준에 든 위반을 고쳤다 | `baseline.xml` 에서 그 줄을 지운다. Checkstyle 은 쓰지 않는 기준 줄을 알리지 않으므로 고친 사람이 직접 지운다. 같은 커밋에 넣는다 |
| 새 위반이 생겼다 | 기준에 더하지 않고 고친다 |
| 꼭 받아들여야 한다 | 까닭을 커밋 메시지와 PR 본문에 적고 그 줄을 더한다 |
| 규칙을 새로 더했다 | 기준을 비운 뒤 `build.gradle.kts` 의 `isIgnoreFailures` 를 잠시 `true` 로 두고 `./gradlew checkstyleMain checkstyleTest` 를 돌린다. 보고서에서 severity 가 error 인 위반의 `(파일, 규칙)` 을 뽑아 줄을 만들고 `isIgnoreFailures` 를 `false` 로 되돌린다. 뽑는 스크립트는 저장소에 두지 않는다 |

줄의 모양이다. 경로 구분자는 `[\\/]` 로 쓰고, 파일 경로 순으로 둔다.

```xml
<suppress checks="(^|\.)NeedBraces(Check)?$" files="src[\\/]main[\\/]java[\\/]...[\\/]AgentService\.java$"/>
<suppress id="lombokLogger" files="src[\\/]main[\\/]java[\\/]...[\\/]ChatService\.java$"/>
```

- 내장 규칙은 `checks` 로 걸고 끝을 `(Check)?$` 로 고정한다. 고정하지 않으면 `ParameterName` 이 `LambdaParameterName` 까지 억제한다
- 정규식 규칙과 `id` 를 붙인 규칙(`RightCurly` 둘)은 `id` 로 건다. `checks` 로 걸면 같은 종류의 규칙이 모두 억제된다. `RightCurly` 를 `checks` 로 걸면 `rightCurlyAlone` 과 `rightCurlySame` 이 함께 꺼진다
- warning 규칙은 기준에 넣지 않는다

**기준은 `(파일, 규칙)` 단위라 한계가 있다.**
줄 번호로 두면 파일을 고칠 때마다 기준이 어긋나기 때문이다.
그 대신 기준에 든 파일에 같은 규칙의 위반이 새로 생겨도 잡지 못한다.
그 파일을 고칠 때는 그 규칙의 위반을 모두 고치고 기준 줄을 지우는 것을 원칙으로 한다.

### 자동으로 고치기

위 규칙 가운데 기계적으로 고칠 수 있는 넷은 OpenRewrite 레시피가 고친다.
검사는 Checkstyle 이 하고, OpenRewrite 는 고치는 데만 쓴다.

```bash
# cwd: backend/
./gradlew rewriteChanged
./gradlew spotlessApply
```

| Checkstyle 규칙 | 레시피 |
| --- | --- |
| 전체 이름 참조 금지 (`fullyQualifiedName`) | `org.openrewrite.java.ShortenFullyQualifiedTypeReferences` |
| `NeedBraces` | `org.openrewrite.staticanalysis.NeedBraces` |
| `EmptyLineSeparator` (메서드와 생성자) | `org.openrewrite.java.format.BlankLines` |
| 직접 만든 로거 금지 (`lombokLogger`) | `org.openrewrite.java.migrate.lombok.log.UseSlf4j` |

아래는 레시피가 없어 자동으로 고치지 않는다. Checkstyle 보고서를 보고 손으로 고친다.

- 직접 쓴 private 빈 생성자 (`privateEmptyConstructor`)
- 엔티티의 손 접근자 (`entityHandwrittenAccessor`)
- 생성자 주입 (`requiredArgsConstructor`)
- 인터페이스 추상 메서드 선언 사이의 빈 줄. `BlankLines` 의 기본 모양이 0줄이다

**OpenRewrite 도 Spotless 처럼 바뀐 파일만 고친다.**
범위는 `HEAD` 와 `origin/main` 의 공통 조상에서 작업 트리까지 바뀐 Java 파일과, git 이 추적하지 않는 새 Java 파일이다.
작업 트리와 비교하므로 커밋하지 않은 편집이 있는 파일도 범위에 들고, 그 편집은 그대로 남는다.
비교 기준이 공통 조상이므로 `git fetch origin` 뒤에 돌린다.

플러그인에는 범위를 정하는 설정이 없어 `rewriteChanged` 가 범위를 맡는다.

- `rewriteRun` 이 파일을 바꾸기 직전에 `backend/` 아래 파일을 저장소 밖 임시 디렉터리에 떠 둔다
- `rewriteRun` 이 끝나면 범위 밖 파일을 떠 둔 내용으로 되돌린다
- `rewriteRun` 이 실패하면 범위 안 파일까지 모두 되돌린다
- 범위 안 파일이 없으면 레시피를 돌리지 않는다

**`./gradlew rewriteRun` 을 직접 부르지 않는다.** 저장소 전체를 고친다.

`spotlessApply` 를 뒤에 돌리는 까닭은 레시피가 바꾼 모양을 포매터가 정리하기 때문이다.
`UseSlf4j` 가 남긴 쓰지 않는 `Logger` import 도 이때 지워진다.

- `build.gradle.kts` 는 레시피 대상에서 뺐다. Java 레시피가 Kotlin 스크립트를 읽다가 멈춘다
- 플러그인은 7.39.0 에 둔다. 7.40.0 과 7.41.0 은 Maven Central 에 없는 `rewrite-bom` 8.91.0 을 가리켜 받지 못한다
- Gradle 기본 메모리(heap 512 MiB, Metaspace 384 MiB)에서는 오래 쓴 daemon 이 `rewriteRun` 도중 Metaspace 부족으로 멈춘 적이 있다.
  그래서 `gradle.properties` 의 `org.gradle.jvmargs` 로 daemon 메모리를 늘렸다. 한 번 돌면 heap 을 600 MB 가까이, Metaspace 를 150 MB 가까이 쓴다
- daemon 이 멈추면 되돌리는 단계도 돌지 못한다. 떠 둔 디렉터리는 `rewriteChanged:` 로 시작하는 로그 줄에 있다

## 포맷

Java 포맷은 Spotless 8.10.3 의 palantir-java-format 2.100.0 이 정한다. 들여쓰기는 4칸이고 한 줄은 120자다.
한글 한 글자도 한 칸으로 센다.
`./gradlew test` 에는 걸리지 않으므로 따로 돌린다.

```bash
# cwd: backend/
./gradlew spotlessCheck
./gradlew spotlessApply
```

`ratchetFrom("origin/main")` 이라 `HEAD` 와 `origin/main` 의 공통 조상에서 바뀐 파일만 검사하고 고친다.
바꾸지 않은 파일은 줄이 120자를 넘어도 잡히지 않는다.
**파일을 처음 고치면 그 파일 전체가 포맷된다.** 고친 줄만 바뀌지 않는다.
비교 기준이 공통 조상이므로 `git fetch origin` 뒤에 돌린다.
쓰지 않는 import 와 import 순서도 포매터가 정리한다.
Javadoc 본문은 포맷하지 않는다. 한국어 Javadoc 의 줄바꿈이 바뀌지 않는다.

**기능 변경과 포맷은 다른 커밋으로 나눈다.**
먼저 기능을 고쳐 커밋하고, 그 뒤 `./gradlew spotlessApply` 결과를 따로 커밋한다.
한 커밋에 섞으면 리뷰에서 기능 변경이 포맷 변경에 묻힌다.
선택 까닭은 [ADR-041](../docs/adr/ADR-041-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md) 의 「대안 기각」 에 있다.

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
