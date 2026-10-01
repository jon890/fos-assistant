# Phase 01. ArchUnit 으로 패키지 구조 규칙을 얼려 둔다

**Execution profile**: deep

## 목표

패키지 순환, 층 방향, 문서에 문장으로만 있던 경계를 ArchUnit 규칙으로 옮긴다.
지금 있는 위반은 저장소에 커밋한 기준 파일에 얼려 두고 새 위반만 `./gradlew test` 에서 실패시킨다.

**범위 외**: 테스트 메서드 이름(phase 02), Checkstyle(phase 03), 포맷(phase 04), `qualityCheck` 태스크와 `scripts/quality.sh`(phase 05).
이 phase 는 기존 위반을 고치지 않는다. 기존 위반을 줄이는 일은 기준마다 연 GitHub 이슈가 맡는다(ADR-041).

## 컨텍스트

- `backend/` 는 Gradle Kotlin DSL 이고 버전은 `backend/gradle/libs.versions.toml` 이 갖는다. `gradlew` 는 `backend/` 안에 있다
- JUnit 은 `spring-boot-starter-test` 가 가져오는 Jupiter 를 쓴다. `tasks.test { useJUnitPlatform() }` 이 이미 있다
- 최상위 패키지는 `com.bifos.assistant` 아래 12개다. `agent`, `chat`, `context`, `hermes`, `mcp`, `memory`, `orchestration`, `people`, `shared`, `skill`, `usage`, `user`. `com.bifos.assistant.AssistantApplication` 은 패키지 뿌리에 바로 있다. Flyway Java 마이그레이션은 `db.migration` 에 있어 규칙 대상이 아니다
- 층 패키지는 도메인 안의 `presentation`, `application`, `domain`, `infra` 다. `hermes`, `context`, `shared` 는 층 패키지가 없다
- 2026-09-30 에 `origin/main` 을 합친 코드에서 import 로 측정한 값이다. 바이트코드로 보면 조금 더 나올 수 있다
  - `shared` 를 뺀 최상위 패키지 사이의 간선은 48개이고 **모두** 순환에 속한다
  - `shared` 가 다른 최상위 패키지를 쓴다 3건. `shared.auth.ControlPlaneJwtFilter`, `shared.auth.CurrentUser` 가 `user` 를, `shared.config.SecurityConfig` 가 `mcp.infra.AgentTokenAuthenticationFilter` 를 쓴다
  - `infra` 가 같은 도메인의 `application` 을 쓴다 8건. `skill.infra.SkillStore`, `chat.infra.ArtifactStore`, `chat.infra.AttachmentStore`, `chat.infra.ArtifactSourceFetcher`, `mcp.infra.AgentTokenAuthenticationFilter`
  - `domain` 이 `application` 과 `infra` 를 쓴다 4건. 모두 `user.domain.UserProvisioningService`
  - `orchestration` → `mcp` 는 0건이다. PR #56 이 끊었다
- `mcp.application.AgentTokenService` 가 `hermes.HermesProfileName` 을 쓴다. 이름 규칙 값이라 허용한다. `mcp` 가 Hermes 를 부르는 클라이언트를 쓰는 곳은 없다
- ArchUnit 1.5.1 의 기준 저장소 동작(critic 이 소스로 확인했다)
  - `allowStoreCreation` 은 `stored.rules` 파일이 없을 때만 본다
  - 새 규칙을 기준에 적는 것도, 고친 위반을 기준에서 빼는 것도 저장이다. `allowStoreUpdate=false` 면 둘 다 `StoreUpdateFailedException` 으로 실패한다
  - 그래서 기준에 든 위반을 고치거나 그 클래스 이름을 바꾸면 평소의 `./gradlew test` 가 실패한다. 기준을 줄이는 커밋을 함께 넣으라는 신호로 쓴다

**근거 문서**: `docs/adr/ADR-041-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md`, `docs/code-architecture.md` 의 「backend 패키지」, 「다른 에이전트에게 맡기기」, 「중지」, 「사용자를 더할 때」 절, `backend/AGENTS.md` 의 「패키지 배치」 와 「기술 주의점」 절

## 의도 메모

- **시작 전에 `origin/main` 을 합친다.** `git fetch origin && git merge --no-edit origin/main`. 기준 파일은 합친 코드로 만든다
- **순환 규칙은 패키지 간선 단위다(사용자 결정 2026-09-30).** 순환에 속한 최상위 패키지 간선 `A -> B` 하나가 위반 하나다. 이미 있는 간선 위에 클래스 의존을 더하는 것은 통과하고, 순환을 늘리는 새 간선만 실패한다. 클래스 쌍 단위로 얼리면 패키지를 넘는 새 의존이 모두 실패한다. 지금 패키지가 모두 한 순환 묶음이기 때문이다
- **`shared` 는 순환 그래프에서 뺀다.** 모든 도메인이 쓰는 기반 패키지로 본다. 대신 `SHARED_DOES_NOT_DEPEND_ON_DOMAINS` 가 `shared` 에서 나가는 의존을 따로 막는다
- **순환 규칙은 ArchUnit 의 `slices().should().beFreeOfCycles()` 를 쓰지 않는다.** 기본 설정은 순환을 100개까지만 찾는다. 이 저장소처럼 촘촘한 그래프에서는 어느 순환이 먼저 잡히는지에 따라 얼린 목록이 달라질 수 있다. 같은 입력에 같은 결과를 내도록 직접 쓴 조건을 쓴다(작업 항목 2)
- **ArchUnit 의 JUnit 엔진(`archunit-junit5`)을 쓰지 않는다.** 코어 `com.tngtech.archunit:archunit` 에 Jupiter `@Test` 로 `rule.check(classes)` 를 부른다. Spring Boot 가 고른 JUnit 판과 ArchUnit 엔진의 판이 어긋날 걱정이 없다
- **모든 규칙을 `FreezingArchRule.freeze(...)` 로 감싼다.** 지금 위반이 0건인 규칙도 같다. 규칙마다 기준 파일이 생기고 새 위반만 실패한다
- **기준 파일은 읽기 전용이 기본이다.** `freeze.store.default.allowStoreUpdate=false` 로 둔다. 평소의 테스트는 기준 파일을 쓰지 않고, 기준과 실제가 어긋나면 실패한다. 쓰기는 Gradle 속성으로만 켠다
- **다시 얼릴 때는 규칙 하나만 대상으로 삼는다.** `-Parchunit.freeze.refreeze=true` 는 그 실행이 검사하는 모든 규칙을 다시 얼린다. `--tests` 로 그 규칙의 테스트 메서드 하나만 고른다. 그러지 않으면 다른 규칙의 새 위반까지 조용히 기준에 들어간다
- 규칙의 `as(...)` 설명이 기준 파일의 열쇠다. 설명을 바꾸면 기준이 새 규칙으로 옮겨지지 않는다. 설명을 바꿀 때는 그 규칙을 다시 얼린다
- 층 규칙은 문서 문장 「`presentation` 에서 `application`, `domain`, `infra` 로만 흐른다」 를 옮긴다. 아래 층이 위 층을 쓰는 것만 막는다. `presentation` 이 `infra` 의 저장소를 바로 쓰는 것은 막지 않는다
- 규칙 이름은 `ArchitectureRules` 의 상수 이름이다. 문서는 `ArchitectureRules.LAYER_DIRECTION` 처럼 그 이름을 가리킨다

## 작업 항목

### 1. 의존과 Gradle 설정

- `backend/gradle/libs.versions.toml`: `[versions]` 에 `archunit = "1.5.1"`, `[libraries]` 에 `archunit = { module = "com.tngtech.archunit:archunit", version.ref = "archunit" }`
- `backend/build.gradle.kts`
  - `testImplementation(libs.archunit)`
  - 모든 `Test` 태스크(`tasks.withType<Test>().configureEach`)에 아래 둘을 둔다
    - Gradle 속성 셋을 같은 이름의 JVM 시스템 속성으로 넘긴다. 속성이 없으면 넘기지 않는다. 이름은 `archunit.freeze.refreeze`, `archunit.freeze.store.default.allowStoreCreation`, `archunit.freeze.store.default.allowStoreUpdate` 다. ArchUnit 은 `archunit.` 으로 시작하는 시스템 속성으로 `archunit.properties` 값을 바꿔 쓴다. `systemProperty` 로 넘긴 값은 태스크 입력이 된다
    - `inputs.files(fileTree("config/archunit/store")).withPropertyName("archunitStore").withPathSensitivity(PathSensitivity.RELATIVE)`. 기준 파일만 바뀌어도(`git pull` 등) 테스트가 옛 결과로 건너뛰지 않는다. `inputs.dir(...)` 는 `optional()` 을 붙여도 디렉터리가 없으면 태스크 검증이 실패해 기준을 처음 만들 수 없다(Gradle 9.5.0 에서 확인했다)
  - `archTest` 태스크를 등록한다. `Test` 타입, `group = "verification"`, `testClassesDirs = sourceSets.test.get().output.classesDirs`, `classpath = sourceSets.test.get().runtimeClasspath`, `useJUnitPlatform { includeTags("architecture") }`. `./gradlew test` 는 태그를 거르지 않으므로 규칙 테스트를 그대로 포함한다
- `backend/src/test/resources/archunit.properties` (신규)

  ```properties
  freeze.store.default.path=config/archunit/store
  freeze.store.default.allowStoreCreation=false
  freeze.store.default.allowStoreUpdate=false
  freeze.refreeze=false
  ```

  경로는 Gradle 테스트의 작업 디렉터리인 `backend/` 기준이다. 파일 첫 줄에 한국어 주석(`#`)으로 이 파일이 무엇이고 갱신 방법이 `backend/AGENTS.md` 「구조 규칙」 에 있다고 적는다

### 2. `backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageCycles.java` (신규)

`ArchCondition<JavaClass>` 를 상속한 조건이다.

- 최상위 패키지는 `com.bifos.assistant.<이름>` 의 `<이름>` 이다. 뿌리에 바로 있는 클래스, `com.bifos.assistant` 밖의 클래스, `shared` 는 그래프에 넣지 않는다
- `init(Collection<JavaClass> allObjectsToTest)` 에서 모든 클래스의 `getDirectDependenciesFromSelf()` 로 최상위 패키지 사이의 방향 그래프를 만들고, 패키지마다 닿을 수 있는 패키지 집합을 구한다(노드가 11개라 Floyd–Warshall 이면 충분하다).
- 위반은 **간선마다 하나**다. B 에서 A 로 닿을 수 있는 간선 `A -> B` 가 위반이다. 위반 문구는 `"<A> -> <B> 는 순환에 속한다"` 다. 문구가 기준 파일의 열쇠이므로 클래스 이름이나 줄 번호처럼 간선과 무관하게 바뀌는 값을 넣지 않는다
- 간선 위반은 그 간선의 출발 패키지에 속한 클래스 하나에서만 낸다. 조건이 클래스마다 불리므로, 출발 패키지에서 이름 순으로 첫 클래스일 때 그 패키지의 위반 간선을 도착 패키지 이름 순으로 모두 낸다. 같은 입력에 같은 순서와 같은 문구가 나와야 기준 파일이 흔들리지 않는다
- 클래스 Javadoc 에 이 조건을 직접 쓴 까닭(간선 단위, 내장 순환 검사의 탐지 상한, `shared` 제외)을 한국어로 적는다

### 3. `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` (신규)

`public static final ArchRule` 상수로 규칙을 모은다. 각 상수에 한국어 Javadoc 으로 근거 문서의 절을 적고, `as(...)` 설명도 한국어로 쓴다.

| 상수 | 규칙 | 근거 |
| --- | --- | --- |
| `TOP_LEVEL_PACKAGES_FREE_OF_CYCLES` | `classes().that().resideInAPackage("com.bifos.assistant..").should(new TopLevelPackageCycles())` | 사용자 요청. `docs/code-architecture.md` 「backend 패키지」 의 `mcp`/`orchestration` 문단 |
| `SHARED_DOES_NOT_DEPEND_ON_DOMAINS` | `noClasses().that().resideInAPackage("com.bifos.assistant.shared..").should().dependOnClassesThat().resideInAnyPackage(<shared 를 뺀 최상위 패키지 11개>..)` | `docs/code-architecture.md` 「backend 패키지」 의 `shared/auth`, `shared/error` 책임 |
| `LAYER_DIRECTION` | 아래 코드 그대로 | `backend/AGENTS.md` 「패키지 배치」, `docs/code-architecture.md` 「backend 패키지」 |
| `DOMAIN_DOES_NOT_DEPEND_ON_WEB` | `noClasses().that().resideInAPackage("com.bifos.assistant.*.domain..").should().dependOnClassesThat().resideInAnyPackage("org.springframework.web..", "org.springframework.http..", "jakarta.servlet..", "com.bifos.assistant.*.presentation..")` | 같은 곳 |
| `ORCHESTRATION_DOES_NOT_DEPEND_ON_MCP` | `com.bifos.assistant.orchestration..` 이 `com.bifos.assistant.mcp..` 에 의존하지 않는다 | `docs/code-architecture.md` 「backend 패키지」 의 「`mcp` 는 `orchestration` 을 부르고…」 |
| `MCP_DOES_NOT_CALL_HERMES` | `com.bifos.assistant.mcp..` 이 `com.bifos.assistant.hermes..` 가운데 단순 이름이 `Client` 로 끝나는 타입, `HermesRunEventStream`, `HermesProfileKeyStore` 에 의존하지 않는다 | 「다른 에이전트에게 맡기기」 의 「MCP 쪽은 Hermes 를 부르지 않는다」 |
| `ORCHESTRATION_DOES_NOT_CALL_CHAT_SERVICE` | `com.bifos.assistant.orchestration..` 이 `com.bifos.assistant.chat.application.ChatService` 에 의존하지 않는다 | 「중지」 의 「`ChatService` 와 흐름이 서로를 부르지 않게」 |
| `HERMES_DOES_NOT_DEPEND_ON_PEOPLE` | `com.bifos.assistant.hermes..` 이 `com.bifos.assistant.people..` 에 의존하지 않는다 | 「사용자를 더할 때」 의 「`hermes` 는 부르는 방법만 알고 순서를 모른다」 |
| `NO_JACKSON_2_DATABIND` | 어떤 클래스도 `com.fasterxml.jackson.core..`, `com.fasterxml.jackson.databind..` 에 의존하지 않는다. `com.fasterxml.jackson.annotation..` 은 Jackson 3 도 쓰므로 허용한다 | `backend/AGENTS.md` 「기술 주의점」 의 Jackson 3 |
| `CONTROLLERS_HAVE_NO_NESTED_RECORDS` | `classes().that().areRecords().and().areNestedClasses().should(<바깥 클래스의 단순 이름이 Controller 로 끝나지 않는다>)` | `backend/AGENTS.md` 「데이터 클래스는 컨트롤러 안에 두지 않는다」 |

`LAYER_DIRECTION` 은 이 호출 그대로 쓴다. `whereLayer(X).mayOnlyBeAccessedByLayers(Y)` 는 「X 를 쓸 수 있는 것은 Y 뿐이다」 는 뜻이다.

```java
layeredArchitecture().consideringOnlyDependenciesInLayers()
        .layer("presentation").definedBy("com.bifos.assistant.*.presentation..")
        .layer("application").definedBy("com.bifos.assistant.*.application..")
        .layer("infra").definedBy("com.bifos.assistant.*.infra..")
        .layer("domain").definedBy("com.bifos.assistant.*.domain..")
        .whereLayer("presentation").mayNotBeAccessedByAnyLayer()
        .whereLayer("application").mayOnlyBeAccessedByLayers("presentation")
        .whereLayer("infra").mayOnlyBeAccessedByLayers("presentation", "application")
        .whereLayer("domain").mayOnlyBeAccessedByLayers("presentation", "application", "infra")
```

- `that()` 에 걸리는 클래스가 없을 수 있는 규칙에는 `allowEmptyShould(true)` 를 붙인다. ArchUnit 은 기본으로 빈 대상을 실패로 본다
- 코드로 옮기지 않는 문장은 작업 항목 6 에서 `backend/AGENTS.md` 에 까닭과 함께 적는다. 「서비스가 돌려주는 결과 타입을 서비스 안에 두지 않는다」 는 예외(「그 타입 밖에서 쓰이지 않는 값」)를 기계로 판정할 수 없어 옮기지 않는다

### 4. `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRulesTest.java` (신규)

- 클래스에 `@Tag("architecture")`
- `static final JavaClasses MAIN = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.bifos.assistant");`
- 규칙마다 `@Test` 메서드 하나. 메서드 이름은 영문 camelCase, `@DisplayName` 에 한국어 문장을 단다. 본문은 `FreezingArchRule.freeze(ArchitectureRules.<상수>).check(MAIN);`

### 5. 기준 파일을 만든다

```bash
# cwd: backend/
./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreCreation=true -Parchunit.freeze.store.default.allowStoreUpdate=true
git add config/archunit
```

- `backend/config/archunit/store/` 에 `stored.rules` 와 규칙마다 파일 하나가 생긴다
- 기대하는 규칙별 기준 수(import 측정이라 조금 다를 수 있다)
  - `TOP_LEVEL_PACKAGES_FREE_OF_CYCLES` 약 48, `SHARED_DOES_NOT_DEPEND_ON_DOMAINS` 약 3건의 의존, `LAYER_DIRECTION` 약 12
  - `DOMAIN_DOES_NOT_DEPEND_ON_WEB`, `ORCHESTRATION_DOES_NOT_DEPEND_ON_MCP`, `MCP_DOES_NOT_CALL_HERMES`, `ORCHESTRATION_DOES_NOT_CALL_CHAT_SERVICE`, `HERMES_DOES_NOT_DEPEND_ON_PEOPLE`, `NO_JACKSON_2_DATABIND`, `CONTROLLERS_HAVE_NO_NESTED_RECORDS` 는 0
  - 크게 다르면 규칙을 다시 읽는다. 특히 `LAYER_DIRECTION` 이 수백 건이면 쓰는 쪽과 쓰이는 쪽을 뒤집은 것이다
- **같은 결과가 나오는지 확인한다.** 기준 디렉터리를 저장소 밖으로 복사하고 지운 뒤 같은 명령(`--rerun` 포함)으로 다시 만든다. 규칙별 파일 내용을 정렬해 비교하면 같아야 한다. 파일 이름은 새로 정해질 수 있으므로 `stored.rules` 의 규칙 설명으로 짝을 짓는다. 다르면 `PHASE_BLOCKED: 얼린 위반 목록이 실행마다 다르다` 로 멈춘다
- 다시 만든 것을 `git add` 한 뒤 속성 없이 `./gradlew archTest --rerun` 을 두 번 돌리고 `git diff --exit-code config/archunit` 가 0 으로 끝나는지 본다
- 규칙별 기준 위반 수를 센다. 보고와 PR 본문에 쓴다

### 6. 문서

- `backend/AGENTS.md` 에 「구조 규칙」 절을 더한다
  - 규칙은 `ArchitectureRules` 가 갖고 `./gradlew test` 와 `./gradlew archTest` 가 검사한다
  - 규칙 표: 상수 이름과 한 줄 뜻. 순환 규칙이 간선 단위이고 `shared` 를 뺀다는 것
  - 기준 파일 위치 `backend/config/archunit/store/`
  - **기준에 든 위반은 허용이 아니라 줄여 갈 목록이다.** 기준마다 GitHub 이슈가 있다. 이슈 번호는 PR 을 열기 전에 team-lead 가 채우므로, 이 phase 에서는 「기준마다 연 이슈」 라고만 적고 번호 칸을 비워 두지 않는다
  - 갱신 방법. 명령은 모두 `backend/` 에서 돈다
    - **위반을 고쳤을 때.** 평소 테스트가 `StoreUpdateFailedException` 으로 실패한다. `./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true` 로 기준에서 빼고 그 변경을 같은 커밋에 넣는다. 기준에 든 클래스의 이름만 바꿔도 같은 실패가 나고, 그때는 아래 「다시 얼릴 때」 를 따른다
    - **규칙을 새로 더했을 때.** `-Parchunit.freeze.store.default.allowStoreUpdate=true` 로 그 규칙의 기준 파일을 만든다. `allowStoreCreation` 은 `stored.rules` 가 없을 때만 쓴다
    - **새 위반을 받아들여야 할 때와 다시 얼릴 때.** `./gradlew archTest --rerun --tests '*ArchitectureRulesTest.<메서드>' -Parchunit.freeze.refreeze=true -Parchunit.freeze.store.default.allowStoreUpdate=true`. 규칙 하나만 대상으로 삼는다. 까닭을 커밋 메시지와 PR 본문에 적는다
  - 코드로 옮기지 않은 문장과 까닭
  - 「패키지 배치」 절의 층 문장과 「데이터 클래스는 컨트롤러 안에 두지 않는다」, 「기술 주의점」 의 Jackson 문장 끝에 그 규칙 이름을 붙인다
- `docs/code-architecture.md` 에서 규칙으로 옮긴 문장 뒤에 규칙 이름을 붙인다. 형식은 「검사: `ArchitectureRules.<상수>`」 한 줄이다
  - 「backend 패키지」 의 층 문장: `LAYER_DIRECTION`
  - 같은 절의 `mcp`/`orchestration` 문단: `ORCHESTRATION_DOES_NOT_DEPEND_ON_MCP`, `TOP_LEVEL_PACKAGES_FREE_OF_CYCLES`
  - 같은 절의 패키지 표 아래: `SHARED_DOES_NOT_DEPEND_ON_DOMAINS`
  - 「다른 에이전트에게 맡기기」 의 「MCP 쪽은 Hermes 를 부르지 않는다」: `MCP_DOES_NOT_CALL_HERMES`
  - 「중지」 의 「`ChatService` 와 흐름이 서로를 부르지 않게」: `ORCHESTRATION_DOES_NOT_CALL_CHAT_SERVICE`
  - 「사용자를 더할 때」 의 「`hermes` 는 부르는 방법만 알고」: `HERMES_DOES_NOT_DEPEND_ON_PEOPLE`

### 7. 규칙이 실제로 실패하는지 본다

기록만 남기고 되돌린다. 출력은 저장소 밖에 저장한다. Gradle 은 작업 트리를 컴파일하므로 커밋하지 않고 확인한다.

1. 새 순환 간선: `backend/src/main/java/com/bifos/assistant/context/ContextAssembler.java` 에 `com.bifos.assistant.usage.domain.ExecutionStatus` 를 실제로 쓰는 코드를 넣는다(예: `private static final Object CYCLE_PROBE = ExecutionStatus.RUNNING;`). import 만 하면 바이트코드에 의존이 남지 않는다. 먼저 `context -> usage` 간선이 기준에 없는지 기준 파일에서 확인한다. 있으면 기준에 없는 다른 간선을 고른다. `./gradlew archTest --rerun` 이 `TOP_LEVEL_PACKAGES_FREE_OF_CYCLES` 로 실패하며 그 간선이 새 위반으로 나오는지 본다
2. 이미 있는 간선 위의 새 의존은 통과한다: 기준에 있는 간선(예: `chat -> agent`) 위에 새 클래스 의존 하나를 넣고 `TOP_LEVEL_PACKAGES_FREE_OF_CYCLES` 가 통과하는지 본다
3. 문서 경계: `orchestration` 의 한 클래스가 `com.bifos.assistant.mcp.application.McpCaller` 를 쓰게 해 `ORCHESTRATION_DOES_NOT_DEPEND_ON_MCP` 가 실패하는지 본다
4. 기준을 고친 경우: 기준에 든 `LAYER_DIRECTION` 위반 하나를 잠시 없애 `./gradlew archTest --rerun` 이 `StoreUpdateFailedException` 으로 실패하는지 본다. 이 메시지를 작업 항목 6 의 문서에 적는다
5. 넣은 것을 모두 `git checkout -- <그 파일>` 로 되돌리고 `git status --short` 에 그 파일이 남지 않았는지 확인한다

## 검증

```bash
# cwd: backend/
./gradlew archTest --rerun
./gradlew test
git diff --exit-code config/archunit   # 기준 파일을 git add 한 뒤 테스트가 고치지 않았다
```

- `ArchitectureRulesTest` 의 모든 테스트가 통과한다
- 작업 항목 5 의 같은 결과 확인과 작업 항목 7 의 출력이 저장소 밖 파일에 있다

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
