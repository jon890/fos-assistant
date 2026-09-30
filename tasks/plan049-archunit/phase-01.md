# Phase 01. ArchUnit 으로 패키지 구조 규칙을 얼려 둔다

**Execution profile**: deep

## 목표

패키지 순환, 층 방향, 문서에 문장으로만 있던 경계를 ArchUnit 규칙으로 옮긴다.
지금 있는 위반은 저장소에 커밋한 기준 파일에 얼려 두고 새 위반만 `./gradlew test` 에서 실패시킨다.

**범위 외**: 테스트 메서드 이름(phase 02), Checkstyle(phase 03), 포맷(phase 04), `qualityCheck` 태스크와 `scripts/quality.sh`(phase 05).
이 phase 는 기존 위반을 고치지 않는다.

## 컨텍스트

- `backend/` 는 Gradle Kotlin DSL 이고 버전은 `backend/gradle/libs.versions.toml` 이 갖는다. `gradlew` 는 `backend/` 안에 있다
- JUnit 은 `spring-boot-starter-test` 가 가져오는 Jupiter 를 쓴다. `tasks.test { useJUnitPlatform() }` 이 이미 있다
- 최상위 패키지는 `com.bifos.assistant` 아래 `agent`, `chat`, `context`, `hermes`, `mcp`, `memory`, `orchestration`, `people`, `shared`, `skill`, `usage`, `user` 다. `com.bifos.assistant.AssistantApplication` 은 패키지 뿌리에 바로 있다. Flyway Java 마이그레이션은 `db.migration` 에 있어 규칙 대상이 아니다
- 층 패키지는 도메인 안의 `presentation`, `application`, `domain`, `infra` 다. `hermes`, `context`, `shared` 는 층 패키지가 없다
- 2026-09-30 에 import 로 측정한 기존 위반(바이트코드로 보면 조금 더 나올 수 있다)
  - 서로를 import 하는 최상위 패키지 쌍이 15개다. 예: `agent`↔`chat`, `agent`↔`skill`, `chat`↔`orchestration`, `shared`↔`user`, `shared`↔`mcp`
  - `infra` 가 같은 도메인의 `application` 을 쓴다 8건. `skill.infra.SkillStore`, `chat.infra.ArtifactStore`, `chat.infra.AttachmentStore`, `chat.infra.ArtifactSourceFetcher`, `mcp.infra.AgentTokenAuthenticationFilter`
  - `domain` 이 `application` 과 `infra` 를 쓴다 4건. 모두 `user.domain.UserProvisioningService`
  - `orchestration` → `mcp` 는 0건이다. PR #56 이 끊었다
- `mcp.application.AgentTokenService` 가 `hermes.HermesProfileName` 을 쓴다. 이름 규칙 값이라 허용한다. `mcp` 가 Hermes 를 부르는 클라이언트를 쓰는 곳은 없다

**근거 문서**: `docs/adr/ADR-040-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md`, `docs/code-architecture.md` 의 「backend 패키지」, 「다른 에이전트에게 맡기기」, 「중지」, 「사용자를 더할 때」 절, `backend/AGENTS.md` 의 「패키지 배치」 와 「기술 주의점」 절

## 의도 메모

- **순환 규칙은 ArchUnit 의 `slices().should().beFreeOfCycles()` 를 쓰지 않는다.** 기본 설정은 순환을 100개까지만 찾는다. 이 저장소처럼 촘촘한 그래프에서는 어느 순환이 먼저 잡히는지에 따라 얼린 목록이 달라질 수 있다. 같은 입력에 같은 결과를 내도록 직접 쓴 조건을 쓴다(작업 항목 2)
- **ArchUnit 의 JUnit 엔진(`archunit-junit5`)을 쓰지 않는다.** 코어 `com.tngtech.archunit:archunit` 에 Jupiter `@Test` 로 `rule.check(classes)` 를 부른다. Spring Boot 가 고른 JUnit 판과 ArchUnit 엔진의 판이 어긋날 걱정이 없다
- **모든 규칙을 `FreezingArchRule.freeze(...)` 로 감싼다.** 지금 위반이 0건인 규칙도 같다. 규칙마다 기준 파일이 생기고 새 위반만 실패한다
- **기준 파일은 읽기 전용이 기본이다.** `freeze.store.default.allowStoreUpdate=false` 로 두어 평소의 `./gradlew test` 가 기준 파일을 고치지 않는다. 고친 위반을 기준에서 빼거나 새 위반을 받아들일 때만 Gradle 속성으로 쓰기를 켠다(작업 항목 1, 5)
- 규칙의 `as(...)` 설명이 기준 파일의 열쇠다. 설명을 바꾸면 기준이 새 규칙으로 옮겨지지 않는다. 설명을 바꿀 때는 그 규칙을 다시 얼린다
- 층 규칙은 문서 문장 「`presentation` 에서 `application`, `domain`, `infra` 로만 흐른다」 를 그대로 옮긴다. 아래 층이 위 층을 쓰는 것만 막는다. `presentation` 이 `infra` 의 저장소를 바로 쓰는 것은 막지 않는다. 층 사이 순서는 `presentation` → `application` → `infra` → `domain` 이다
- 규칙 이름은 `ArchitectureRules` 의 상수 이름이다. 문서는 `ArchitectureRules.LAYER_DIRECTION` 처럼 그 이름을 가리킨다

## 작업 항목

### 1. 의존과 Gradle 설정

- `backend/gradle/libs.versions.toml`: `[versions]` 에 `archunit = "1.5.1"`, `[libraries]` 에 `archunit = { module = "com.tngtech.archunit:archunit", version.ref = "archunit" }`
- `backend/build.gradle.kts`
  - `testImplementation(libs.archunit)`
  - 모든 `Test` 태스크에 Gradle 속성 셋을 같은 이름의 JVM 시스템 속성으로 넘긴다. 속성이 없으면 넘기지 않는다. 이름은 `archunit.freeze.refreeze`, `archunit.freeze.store.default.allowStoreCreation`, `archunit.freeze.store.default.allowStoreUpdate` 다. ArchUnit 은 `archunit.` 으로 시작하는 시스템 속성으로 `archunit.properties` 값을 바꿔 쓴다. 속성 값이 태스크 입력에 들어가야 캐시가 옛 결과를 내지 않는다(`systemProperty` 로 넘기면 입력이 된다)
  - `archTest` 태스크를 등록한다. `Test` 타입, `group = "verification"`, `testClassesDirs = sourceSets.test.output.classesDirs`, `classpath = sourceSets.test.runtimeClasspath`, `useJUnitPlatform { includeTags("architecture") }`. `./gradlew test` 는 태그를 거르지 않으므로 규칙 테스트를 그대로 포함한다
- `backend/src/test/resources/archunit.properties` (신규)

  ```properties
  freeze.store.default.path=config/archunit/store
  freeze.store.default.allowStoreCreation=false
  freeze.store.default.allowStoreUpdate=false
  freeze.refreeze=false
  ```

  경로는 Gradle 테스트의 작업 디렉터리인 `backend/` 기준이다. 파일 첫 줄에 한국어 주석(`#`)으로 이 파일이 무엇이고 갱신 방법이 `backend/AGENTS.md` 에 있다고 적는다

### 2. `backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageCycles.java` (신규)

`ArchCondition<JavaClass>` 를 상속한 조건이다.

- 최상위 패키지는 `com.bifos.assistant.<이름>` 의 `<이름>` 이다. 뿌리에 바로 있는 클래스와 `com.bifos.assistant` 밖의 클래스는 보지 않는다
- `init(Collection<JavaClass> allObjectsToTest)` 에서 모든 클래스의 `getDirectDependenciesFromSelf()` 로 최상위 패키지 사이의 방향 그래프를 만들고, 패키지마다 닿을 수 있는 패키지 집합을 구한다(패키지가 13개라 Floyd–Warshall 이면 충분하다)
- `check(JavaClass item, ConditionEvents events)`: `item` 의 패키지를 A 라 하고, `item` 이 의존하는 다른 최상위 패키지 B 의 클래스마다, B 에서 A 로 닿을 수 있으면 위반이다. 위반은 **(출발 클래스, 도착 클래스) 쌍마다 하나**다. 같은 쌍의 의존이 여럿이어도 한 번만 낸다
- 위반 문구는 `"<A> -> <B> 순환: <출발 클래스 전체 이름> -> <도착 클래스 전체 이름>"` 이다. 줄 번호를 넣지 않는다. 같은 입력에 같은 문구가 나와야 기준 파일이 흔들리지 않는다
- 한 클래스 안에서 위반을 낼 때 도착 클래스 이름 순으로 정렬해 낸다
- 클래스 Javadoc 에 이 조건을 직접 쓴 까닭(내장 순환 검사의 탐지 상한 때문에 얼린 목록이 흔들릴 수 있다)을 한국어로 적는다

### 3. `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` (신규)

`public static final ArchRule` 상수로 규칙을 모은다. 각 상수에 한국어 Javadoc 으로 근거 문서의 절을 적고, `as(...)` 설명도 한국어로 쓴다.

| 상수 | 규칙 | 근거 |
| --- | --- | --- |
| `TOP_LEVEL_PACKAGES_FREE_OF_CYCLES` | `classes().that().resideInAPackage("com.bifos.assistant..").should(new TopLevelPackageCycles())` | 사용자 요청. `docs/code-architecture.md` 「backend 패키지」 의 `mcp`/`orchestration` 문단 |
| `LAYER_DIRECTION` | `layeredArchitecture().consideringOnlyDependenciesInLayers()` 로 `presentation`, `application`, `infra`, `domain` 층을 `com.bifos.assistant.*.<층>..` 로 정한다. `presentation` 은 어느 층도 쓰지 않는다. `application` 은 `presentation` 만 쓴다. `infra` 는 `presentation` 과 `application` 만 쓴다. `domain` 은 세 층이 쓴다 | `backend/AGENTS.md` 「패키지 배치」, `docs/code-architecture.md` 「backend 패키지」 |
| `DOMAIN_DOES_NOT_DEPEND_ON_WEB` | `com.bifos.assistant.*.domain..` 의 클래스가 `org.springframework.web..`, `org.springframework.http..`, `jakarta.servlet..`, `com.bifos.assistant.*.presentation..` 에 의존하지 않는다 | 같은 곳 |
| `ORCHESTRATION_DOES_NOT_DEPEND_ON_MCP` | `com.bifos.assistant.orchestration..` 이 `com.bifos.assistant.mcp..` 에 의존하지 않는다 | `docs/code-architecture.md` 「backend 패키지」 의 「`mcp` 는 `orchestration` 을 부르고…」 |
| `MCP_DOES_NOT_CALL_HERMES` | `com.bifos.assistant.mcp..` 이 `com.bifos.assistant.hermes..` 가운데 단순 이름이 `Client` 로 끝나는 타입, `HermesRunEventStream`, `HermesProfileKeyStore` 에 의존하지 않는다 | 「다른 에이전트에게 맡기기」 의 「MCP 쪽은 Hermes 를 부르지 않는다」 |
| `ORCHESTRATION_DOES_NOT_CALL_CHAT_SERVICE` | `com.bifos.assistant.orchestration..` 이 `com.bifos.assistant.chat.application.ChatService` 에 의존하지 않는다 | 「중지」 의 「`ChatService` 와 흐름이 서로를 부르지 않게」 |
| `HERMES_DOES_NOT_DEPEND_ON_PEOPLE` | `com.bifos.assistant.hermes..` 이 `com.bifos.assistant.people..` 에 의존하지 않는다 | 「사용자를 더할 때」 의 「`hermes` 는 부르는 방법만 알고 순서를 모른다」 |
| `NO_JACKSON_2_DATABIND` | 어떤 클래스도 `com.fasterxml.jackson.core..`, `com.fasterxml.jackson.databind..` 에 의존하지 않는다. `com.fasterxml.jackson.annotation..` 은 Jackson 3 도 쓰므로 허용한다 | `backend/AGENTS.md` 「기술 주의점」 의 Jackson 3 |
| `CONTROLLERS_HAVE_NO_NESTED_RECORDS` | 중첩된 record 의 바깥 클래스 단순 이름이 `Controller` 로 끝나면 위반이다. `classes().that().areRecords().and().areNestedClasses().should(<바깥 클래스가 컨트롤러가 아니다>)` | `backend/AGENTS.md` 「데이터 클래스는 컨트롤러 안에 두지 않는다」 |

- `that()` 에 걸리는 클래스가 없을 수 있는 규칙에는 `allowEmptyShould(true)` 를 붙인다. ArchUnit 은 기본으로 빈 대상을 실패로 본다
- 코드로 옮기지 않는 문장과 그 까닭은 작업 항목 6 에서 `backend/AGENTS.md` 에 적는다. 「서비스가 돌려주는 결과 타입을 서비스 안에 두지 않는다」 는 예외(「그 타입 밖에서 쓰이지 않는 값」)를 기계로 판정할 수 없어 옮기지 않는다

### 4. `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRulesTest.java` (신규)

- 클래스에 `@Tag("architecture")`
- `static final JavaClasses MAIN = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.bifos.assistant");`
- 규칙마다 `@Test` 메서드 하나. 메서드 이름은 영문 camelCase, `@DisplayName` 에 한국어 문장을 단다. 본문은 `FreezingArchRule.freeze(ArchitectureRules.<상수>).check(MAIN);`

### 5. 기준 파일을 만든다

```bash
# cwd: backend/
./gradlew archTest -Parchunit.freeze.store.default.allowStoreCreation=true -Parchunit.freeze.store.default.allowStoreUpdate=true
```

- `backend/config/archunit/store/` 에 `stored.rules` 와 규칙마다 파일 하나가 생긴다. 모두 커밋한다
- **같은 결과가 나오는지 확인한다.** 기준 디렉터리를 저장소 밖으로 복사하고 지운 뒤 같은 명령으로 다시 만든다. 두 번의 규칙별 파일 내용을 정렬해 비교하면 같아야 한다(파일 이름은 규칙마다 새로 정해질 수 있으므로 `stored.rules` 의 규칙 설명으로 짝을 짓는다). 다르면 `PHASE_BLOCKED: 얼린 위반 목록이 실행마다 다르다` 로 멈춘다
- 그 뒤 속성 없이 `./gradlew archTest` 를 두 번 돌려 `git status --short backend/config` 가 비어 있는지 본다
- 규칙별 기준 위반 수를 센다. 보고와 PR 본문에 쓴다

### 6. 문서

- `backend/AGENTS.md` 에 「구조 규칙」 절을 더한다
  - 규칙은 `ArchitectureRules` 가 갖고 `./gradlew test` 와 `./gradlew archTest` 가 검사한다
  - 규칙 표: 상수 이름과 한 줄 뜻
  - 기준 파일 위치 `backend/config/archunit/store/`
  - 갱신 방법 셋. 위반을 고쳤으면 `./gradlew archTest -Parchunit.freeze.store.default.allowStoreUpdate=true` 로 기준에서 뺀다(평소 테스트는 기준을 고치지 않는다). 새 위반을 받아들여야 하면 `-Parchunit.freeze.refreeze=true` 와 `allowStoreUpdate=true` 를 함께 주고, 까닭을 커밋 메시지와 PR 본문에 적는다. 규칙을 새로 더하면 `allowStoreCreation=true` 로 기준 파일을 만든다
  - 기준에 든 위반은 허용이 아니라 되돌릴 목록이다
  - 코드로 옮기지 않은 문장과 까닭
  - 「패키지 배치」 절의 층 문장과 「데이터 클래스는 컨트롤러 안에 두지 않는다」, 「기술 주의점」 의 Jackson 문장 끝에 그 규칙 이름을 붙인다
- `docs/code-architecture.md` 에서 규칙으로 옮긴 문장 뒤에 규칙 이름을 붙인다. 「backend 패키지」 의 층 문장(`LAYER_DIRECTION`)과 `mcp`/`orchestration` 문단(`ORCHESTRATION_DOES_NOT_DEPEND_ON_MCP`, `TOP_LEVEL_PACKAGES_FREE_OF_CYCLES`), 「다른 에이전트에게 맡기기」 의 「MCP 쪽은 Hermes 를 부르지 않는다」(`MCP_DOES_NOT_CALL_HERMES`), 「중지」 의 「`ChatService` 와 흐름이 서로를 부르지 않게」(`ORCHESTRATION_DOES_NOT_CALL_CHAT_SERVICE`), 「사용자를 더할 때」 의 「`hermes` 는 부르는 방법만 알고」(`HERMES_DOES_NOT_DEPEND_ON_PEOPLE`). 형식은 「검사: `ArchitectureRules.<상수>`」 한 줄이다

### 7. 규칙이 실제로 실패하는지 본다

기록만 남기고 최종 브랜치에는 남기지 않는다. 출력은 저장소 밖에 저장한다.

1. 새 순환: `backend/src/main/java/com/bifos/assistant/context/ContextAssembler.java` 에 `com.bifos.assistant.usage.domain.ExecutionStatus` 를 실제로 쓰는 코드를 넣는다(예: `private static final Object CYCLE_PROBE = ExecutionStatus.RUNNING;`). import 만 하면 바이트코드에 의존이 남지 않는다. `usage` 는 `chat` 을 거쳐 `context` 에 닿으므로 `context -> usage` 는 새 순환이다. 임시 커밋을 만들고 `./gradlew archTest` 가 `TOP_LEVEL_PACKAGES_FREE_OF_CYCLES` 로 실패하며 `ContextAssembler -> ...ExecutionStatus` 가 새 위반으로 나오는지 본다
2. 문서 경계: 같은 방식으로 `orchestration` 의 한 클래스가 `com.bifos.assistant.mcp.application.McpCaller` 를 쓰게 해 `ORCHESTRATION_DOES_NOT_DEPEND_ON_MCP` 가 실패하는지 본다
3. 두 임시 커밋을 `git reset --hard <phase 시작 커밋>` 으로 되돌리고 `git log` 에 남지 않았는지 확인한다

## 검증

```bash
# cwd: backend/
./gradlew archTest
./gradlew test
git status --short config/archunit   # 테스트가 기준 파일을 고치지 않았다면 비어 있다
```

- `ArchitectureRulesTest` 의 모든 테스트가 통과한다
- 작업 항목 5 의 같은 결과 확인과 작업 항목 7 의 두 실패 출력이 저장소 밖 파일에 있다

AGENTS.md 「확인」 절 전체는 마지막 phase 에서 돌린다. 이 phase 는 위 명령과 아래를 돌린다.

```bash
# cwd: 저장소 root
scripts/check-public-safe.sh
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/gradle/libs.versions.toml` | 수정 |
| `backend/build.gradle.kts` | 수정 |
| `backend/src/test/resources/archunit.properties` | 신규 |
| `backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageCycles.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRulesTest.java` | 신규 |
| `backend/config/archunit/store/stored.rules` | 신규 |
| `backend/config/archunit/store/*` | 신규 |
| `backend/AGENTS.md` | 수정 |
| `docs/code-architecture.md` | 수정 |
