# backend

Control Plane 이다. Spring Boot 4 와 MySQL 8.4 를 쓴다.

저장소 전체에 걸리는 규칙은 루트 [`AGENTS.md`](../AGENTS.md) 가 갖는다.
공개 저장소에 무엇을 적지 않는지도 그 문서가 정한다.

- 패키지와 경계: [`../docs/backend/packages.md`](../docs/backend/packages.md)
- 표와 칸: [`../docs/backend/schema/README.md`](../docs/backend/schema/README.md)
- Hermes 호출: [`../docs/hermes/README.md`](../docs/hermes/README.md)
- 그 밖의 주제: [`../docs/README.md`](../docs/README.md) 의 backend 표

## 패키지 배치

층 방향은 [`../docs/backend/packages.md`](../docs/backend/packages.md) 가 갖는다.
검사: `ArchitectureRules.LAYER_DIRECTION`

### 데이터 클래스는 컨트롤러 안에 두지 않는다

**`presentation` 의 요청과 응답 모양은 그 패키지의 `*Dtos.java` 하나에 모은다.**
컨트롤러 파일에는 경로와 권한과 흐름만 남긴다.
record 가 컨트롤러 안에 있으면 그 파일이 길어지고, 같은 모양을 다른 컨트롤러가 쓸 때
컨트롤러를 import 하게 된다.
검사: `ArchitectureRules.CONTROLLERS_HAVE_NO_NESTED_RECORDS`

한 패키지에 컨트롤러가 여럿이어도 `*Dtos.java` 는 하나다.
`AgentController` 와 `AgentAdminController` 가 `AgentDtos` 를 함께 쓴다.

`application` 과 `domain` 은 타입 하나에 파일 하나다.

예외는 **그 타입 밖에서 쓰이지 않는 값**이다.
`ModelPrice.ContextTier` 처럼 바깥 record 의 일부인 것은 그 안에 둔다.
이 예외는 기계로 판정할 수 없어 리뷰에서 본다.

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
`scripts/quality.sh check` 끝의 「경고 목록(실패 아님)」 은 실패시키지 않지만 모두 고칠 후보다.

기준 파일을 갱신하거나 규칙을 뺄 때는 [`../docs/backend/quality.md`](../docs/backend/quality.md) 를 읽는다.

## 구조 규칙

패키지 구조 규칙은 `src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` 가 갖는다.
`./gradlew test` 가 다른 테스트와 함께 검사하고, `./gradlew archTest` 는 구조 규칙만 검사한다.
규칙을 도구 설정으로 두는 근거는 [ADR-042](../docs/adr/ADR-042-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md) 에 있다.

규칙의 이름과 뜻은 `ArchitectureRules.java` 의 Javadoc 이 갖는다.
순환과 층 순서 규칙이 위반을 세는 방식은 `TopLevelPackageCycles`, `TopLevelPackageOrder` 의 Javadoc 이 갖고,
층 순서는 [`../docs/backend/packages.md`](../docs/backend/packages.md) 의 「최상위 패키지의 층 순서」 가 갖는다.

### enum 은 저장 여부로 둘 곳을 정한다

| 종류 | 위치 |
| --- | --- |
| 엔티티에 `@Enumerated` 로 저장되는 enum | `<기능>.domain.type` |
| 저장되지 않는 서비스 결과와 화면용 enum | `<기능>.application.model` |
| `ErrorCode` | `shared.error` 에 그대로 둔다 |

저장되지 않는 enum 과 `ErrorCode` 는 리뷰에서 본다.

## 코드 규칙

코드 모양 규칙은 Gradle 내장 `checkstyle` 플러그인의 Checkstyle 이 확인한다. 규칙과 까닭은 `config/checkstyle/checkstyle.xml` 의 주석이 갖는다.
`./gradlew test` 에는 걸리지 않으므로 따로 돌린다.

```bash
# cwd: backend/
./gradlew checkstyleMain checkstyleTest
```

### 자동으로 고치기

기계적으로 고칠 수 있는 Checkstyle 위반은 OpenRewrite 레시피와 포매터가 이 순서로 고친다.

```bash
# cwd: backend/
./gradlew rewriteChanged
./gradlew spotlessApply
```

인터페이스 추상 메서드 선언 사이의 빈 줄은 레시피가 고치지 않는다. `BlankLines` 의 기본 모양이 0줄이라 손으로 고친다.
`rewriteChanged` 는 `origin/main` 과의 공통 조상 뒤에 바뀐 파일만 고치므로 `git fetch origin` 뒤에 돌린다.
daemon 이 `rewriteRun` 도중 멈추면 범위 밖 파일을 되돌리는 단계도 돌지 못한다. 바꾸기 전 내용을 떠 둔 디렉터리는 `rewriteChanged:` 로 시작하는 로그 줄에 있다.

## 포맷

Java 포맷은 Spotless 의 palantir-java-format 이 정한다. 한 줄은 120자이고 한글 한 글자도 한 칸으로 센다.

```bash
# cwd: backend/
./gradlew spotlessCheck
./gradlew spotlessApply
```

**파일을 처음 고치면 그 파일 전체가 포맷된다.** 고친 줄만 바뀌지 않는다.
비교 기준이 `origin/main` 과의 공통 조상이므로 `git fetch origin` 뒤에 돌린다.
Javadoc 본문은 포맷하지 않는다. 한국어 Javadoc 의 줄바꿈이 바뀌지 않는다.

**기능 변경과 포맷은 다른 커밋으로 나눈다.**
먼저 기능을 고쳐 커밋하고, 그 뒤 `./gradlew spotlessApply` 결과를 따로 커밋한다.
한 커밋에 섞으면 리뷰에서 기능 변경이 포맷 변경에 묻힌다.
선택 까닭은 [ADR-042](../docs/adr/ADR-042-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md) 의 대안 기각 항목에 있다.

## 엔티티와 마이그레이션은 따로 논다

`./gradlew test` 는 엔티티로 스키마를 만들고 운영은 Flyway 가 만든 스키마를 검증한다.
**그래서 둘이 어긋나도 `./gradlew test` 는 통과한다.**
그 어긋남은 `scripts/check-mysql-migration.sh` 의 `MysqlMigrationTest` 가 Flyway 로 만든 스키마를 `ddl-auto: validate` 로 확인한다.

엔티티를 바꾸면 마이그레이션도 함께 바꾼다. 둘이 어긋나면 운영 기동이 실패한다.
`@Lob` 문자열은 `columnDefinition` 으로 길이를 정한다.

새 파일의 버전은 UTC 작성 시각 14자리다: `V<YYYYMMDDHHMMSS>__<설명>.sql`.
기존 숫자 버전은 그대로 두고, 합칠 때 main 의 다음 번호로 옮기지 않는다.
운영과 시험은 `spring.flyway.out-of-order=true` 를 쓴다.
서로 의존하는 마이그레이션은 한 PR 에 두고 버전 순서로 적용되게 한다.
`scripts/check-migration-versions.mjs` 가 main 대비 새 파일의 형식, 버전 중복, 미래 시각을 검사한다.

**다만 마이그레이션 검사는 모든 마이그레이션을 H2 의 MySQL 모드에서 돌린다.**
`GroupRenameMigrationTest` 같은 `*MigrationTest` 가 그렇다.
그래서 마이그레이션 SQL 은 MySQL 과 H2 에 함께 있는 함수만 쓴다.
`RANDOM_BYTES` 처럼 H2 에 없는 함수가 필요하면 `db.migration` 패키지에 Java 마이그레이션으로 쓴다.
`V23__ConversationPublicId` 가 그 본보기다.

H2 가 통과해도 MySQL 에서 실패할 수 있다. DDL 과 DML 을 나누는 규칙, 정렬 규칙, 실제 MySQL 검사는
[`../docs/backend/schema/README.md`](../docs/backend/schema/README.md) 의 「마이그레이션 작성 규칙」 이 갖는다.
실제 MySQL 검사는 Docker 가 있어야 돈다.

## 저장소 쿼리는 실제 MySQL 에서도 실행한다

다른 테스트는 H2 에서 돌아 MySQL 만 거절하는 쿼리를 통과시킨다.

`RepositoryQueryMysqlTest` 가 Flyway 로 만든 실제 MySQL 스키마에서 모든 저장소 인터페이스가 선언한 메서드를 한 번씩 실행한다.

- **저장소에 메서드를 더하면 따로 할 일이 없다.** 검사가 저장소 빈을 스스로 찾는다
- 인자를 만들지 못하는 타입이 나오면 검사가 실패한다. 건너뛰게 하지 말고 `RepositoryQuerySweep` 에 그 타입의 값을 더한다
- **저장소 인터페이스 밖에서 쿼리를 만들면 그 쿼리를 `RepositoryQueryMysqlTest` 에 직접 더한다.**
  `Specification`, `EntityManager`, `JdbcTemplate` 이 그렇다. 지금은 `MemoryQueries` 의 조건 셋이 그렇게 들어 있다

## 실행 기록

`agent_execution` 의 `RUNNING` 줄을 목록과 비용 합계에서 다루는 방법은 [ADR-011](docs/adr/ADR-011-실행은-시작할-때-기록하고-끝날-때-갱신한다.md) 과 [ADR-004](docs/adr/ADR-004-구독제에서도-api-가격으로-환산해-보인다.md) 가 갖는다.

## 테스트

**`gradlew` 는 `backend/` 안에 있다.** 저장소 루트에서 `./gradlew` 를 부르면 없다.

### 테스트 이름

**한국어 문장은 `@DisplayName` 에 쓴다.** 메서드 이름의 모양은 Checkstyle 의 `MethodName` 이 확인한다.
테스트 보고서에는 `@DisplayName` 의 문장이 보이고, 메서드 이름은 코드에서 무엇을 확인하는지 드러낸다.
이름은 동사로 시작한다. 예: `assemblesGroupAndUserItemsInLayerOrder`.
검사: `ArchitectureRules.TEST_METHODS_HAVE_DISPLAY_NAME`

- `@Test` 와 `@ParameterizedTest` 메서드에 `@DisplayName` 을 단다.
- `@BeforeEach` 같은 준비 메서드와 정리 메서드는 `setUp`, `tearDown` 관례 이름을 쓰고 `@DisplayName` 을 달지 않는다.
  한 클래스에 여럿이면 하는 일을 이름에 담는다.
- 보조 메서드는 영문 camelCase 로 짓고 `@DisplayName` 을 달지 않는다.
- 테스트 클래스에는 `@DisplayName` 을 달지 않는다. 클래스 이름이 이미 영문이다.

## 주석

주석 언어는 루트 [`AGENTS.md`](../AGENTS.md) 의 「코드 주석은 한국어로 쓴다」 가 정한다.

영어로 남아 있던 주석은 그 파일을 고칠 때 함께 옮긴다.
한 번에 전부 옮기려고 별도 커밋을 만들지 않는다.
읽는 사람이 diff 에서 무엇이 바뀌었는지 놓친다.
