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
`check` 는 끝에 「경고 목록(실패 아님)」 을 낸다. 실패시키지 않는 규칙에 걸린 곳이다.
파일과 메서드 길이, `requiredArgsConstructor`, eslint 경고가 여기 나오고, 모두 고칠 후보다.

## 구조 규칙

패키지 구조 규칙은 `src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` 가 갖는다.
`./gradlew test` 가 다른 테스트와 함께 검사하고, `./gradlew archTest` 는 구조 규칙만 검사한다.
규칙을 도구 설정으로 두는 근거는 [ADR-042](../docs/adr/ADR-042-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md) 에 있다.

규칙의 이름과 뜻은 `ArchitectureRules.java` 의 Javadoc 이 갖는다.

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

기준 파일을 갱신하거나 규칙을 뺄 때는 [`../docs/backend/quality.md`](../docs/backend/quality.md) 를 읽는다.

### 코드로 옮기지 않은 문장

「`application` 과 `domain` 은 타입 하나에 파일 하나다」 는 규칙으로 옮기지 않았다.
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
규칙을 도구 설정으로 두는 근거는 [ADR-042](../docs/adr/ADR-042-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md) 에 있다.

### 설정 파일

`config/checkstyle/` 에 셋이 있다. Gradle 이 이 디렉터리를 `config_loc` 으로 넘긴다.

| 파일 | 역할 |
| --- | --- |
| `checkstyle.xml` | 규칙 |
| `suppressions.xml` | 설계상 예외. 앞으로도 허용하는 것이다 |
| `baseline.xml` | 받아들인 위반. 지금은 비어 있다 |

설계상 예외와 기존 위반은 다른 파일에 둔다. 앞의 것은 남고 뒤의 것은 없어져야 하기 때문이다.

### 넣은 규칙

규칙과 까닭은 `checkstyle.xml` 의 주석이 갖는다.

### 기준 파일과 뺀 규칙

기준 파일을 갱신하거나 규칙을 뺄 때는 [`../docs/backend/quality.md`](../docs/backend/quality.md) 를 읽는다.

### 전체 이름을 꼭 써야 할 때

같은 단순 이름의 두 타입을 한 파일에서 써서 import 로 나눌 수 없을 때만 전체 이름을 쓴다.
그 줄 끝에 `// 전체 이름 허용: <까닭>` 주석을 달면 그 줄만 통과한다.

```java
java.util.Date legacy = new java.util.Date(); // 전체 이름 허용: 같은 파일이 java.sql.Date 를 import 한다
```

JPQL 문자열 안의 전체 이름은 규칙 대상이 아니다.

### 자동으로 고치기

Checkstyle 규칙 가운데 기계적으로 고칠 수 있는 넷은 OpenRewrite 레시피가 고친다.
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

**`./gradlew rewriteRun` 을 직접 부르지 않는다.** 저장소 전체를 고치므로 `rewriteChanged` 없이 부르면 태스크가 거절한다.

`spotlessApply` 를 뒤에 돌리는 까닭은 레시피가 바꾼 모양을 포매터가 정리하기 때문이다.
`UseSlf4j` 가 남긴 쓰지 않는 `Logger` import 도 이때 지워진다.

- `build.gradle.kts` 는 레시피 대상에서 뺐다. Java 레시피가 Kotlin 스크립트를 읽다가 멈춘다
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
선택 까닭은 [ADR-042](../docs/adr/ADR-042-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md) 의 「대안 기각」 에 있다.

## 엔티티와 마이그레이션은 따로 논다

테스트는 엔티티로 스키마를 만들고 운영은 Flyway 가 만든 스키마를 검증한다.
**그래서 둘이 어긋나도 테스트는 통과한다.**

엔티티를 바꾸면 마이그레이션도 함께 바꾼다. 둘이 어긋나면 운영 기동이 실패한다.
배포 확인은 운영 저장소가 갖는다.
실제로 `@Lob` 이 붙은 문자열이 MySQL 에서 `tinytext` 로 기대돼 기동에 실패한 적이 있다.
길이를 주지 않은 `@Lob` 문자열을 쓰지 말고 `columnDefinition` 으로 못 박는다.

**다만 마이그레이션 검사는 모든 마이그레이션을 H2 의 MySQL 모드에서 돌린다.**
`GroupRenameMigrationTest` 같은 `*MigrationTest` 가 그렇다.
그래서 마이그레이션 SQL 은 MySQL 과 H2 에 함께 있는 함수만 쓴다.
`RANDOM_BYTES` 처럼 H2 에 없는 함수가 필요하면 `db.migration` 패키지에 Java 마이그레이션으로 쓴다.
`V23__ConversationPublicId` 가 그 본보기다.

이미 적용된 마이그레이션 파일을 고치지 않는다. Flyway 의 검사가 실패한다.
새 번호로 파일을 하나 더 만든다.

**H2 가 통과해도 MySQL 에서 실패할 수 있다.**
정렬 규칙이 다른 두 표의 문자열 칸을 비교한 마이그레이션이 운영에서만 실패한 적이 있다.
DDL 과 DML 을 나누는 규칙, 정렬 규칙, 실제 MySQL 검사는
[`../docs/backend/schema/README.md`](../docs/backend/schema/README.md) 의 「마이그레이션 작성 규칙」 이 갖는다.
마이그레이션을 고쳤으면 저장소 root 에서 `scripts/check-mysql-migration.sh` 를 돌린다. Docker 가 있어야 한다.

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
