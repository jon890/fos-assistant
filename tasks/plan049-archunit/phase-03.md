# Phase 03. ArchUnit 규칙을 더하고 층 규칙에서 `presentation` 이 `infra` 를 쓰는 것을 막는다

**Execution profile**: standard

## 목표

코디네이터가 2026-09-30 에 더한 구조 규칙 가운데 바이트코드로 판정할 수 있는 것을 `ArchitectureRules` 에 더한다.
`LAYER_DIRECTION` 을 고쳐 `presentation` 이 `infra` 를 바로 쓰지 못하게 한다.
지금 있는 위반은 모두 기준에 얼린다. 코드는 고치지 않는다.

**범위 외**: 소스를 봐야 판정할 수 있는 규칙(로거, 엔티티 접근자, private 빈 생성자, 전체 이름, 생성자 주입)은 Checkstyle 이 맡는다(phase 04). 위반을 옮기고 고치는 일은 뒤의 패키지 분리 계획이 한다.

## 컨텍스트

- phase 01 이 `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java`, `ArchitectureRulesTest.java`, `TopLevelPackageCycles.java` 와 기준 디렉터리 `backend/config/archunit/store/` 를 만들었다. phase 02 가 `TEST_METHODS_HAVE_DISPLAY_NAME` 과 테스트 클래스만 읽는 `TESTS` 를 더했다. 기준 갱신 방법은 `backend/AGENTS.md` 「구조 규칙」 절에 있다
- 지금 `LAYER_DIRECTION` 은 `whereLayer("infra").mayOnlyBeAccessedByLayers("presentation", "application")` 이다
- 코디네이터가 main 에서 집계한 지금 위반
  - `@Transactional` 이 `application` 밖에 있다: 컨트롤러 4곳(`AgentAdminController`, `AgentToolController` 등), 저장소 6곳
  - `Instant.now()` 직접 호출 39곳
  - `MessageDigest.getInstance` 를 `shared.util.Sha256` 밖에서 부르는 곳 4곳
  - `@ConfigurationProperties` 클래스 가운데 `@Validated` 가 붙은 것은 0곳이다
  - `presentation` 이 `infra` 를 바로 쓰는 컨트롤러 5개
  - 서비스 안에 공개된 중첩 타입 7개: `AgentToolService.ToolView`, `AgentToolService.ToolsetsView`, `AgentRunner.Run`, `DelegationResult.Failure`, `ArtifactStore.FoundFile`, `ArtifactStore.Removed`, `ArtifactSourceFetcher.Response`
- 엔티티의 `@Enumerated` 필드 타입은 지금 여러 패키지에 흩어져 있다. `..domain.type..` 패키지는 아직 없다

**근거 문서**: `docs/adr/ADR-040-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md`, `backend/AGENTS.md` 「구조 규칙」, `docs/code-architecture.md` 「backend 패키지」

## 의도 메모

- **시작 전에 `origin/main` 을 합친다.** `git fetch origin && git merge --no-edit origin/main`
- 합친 main 코드가 새 ArchUnit 간선 위반, 새 한국어 테스트 이름을 들여오면 앞 phase 의 방법으로 처리하고 이 phase 의 커밋에 넣는다. 그 변경이 있으면 회신의 「특이사항」 에 파일 목록과 무엇을 했는지 적는다. 새 위반을 다시 얼릴 때는 그 규칙 하나만 대상으로 삼는다
- 규칙마다 까닭을 `backend/AGENTS.md` 에 적는다. 까닭은 아래 표의 「까닭」 칸이다
- 모든 새 규칙은 `MAIN` 에만 건다. 테스트는 시각을 고정하려고 `Instant.now()` 를 쓰는 등 사정이 다르다
- **`LAYER_DIRECTION` 을 고친 뒤에는 그 규칙 하나만 다시 얼린다.** `as(...)` 설명은 바꾸지 않는다. 설명이 기준 파일의 열쇠라, 바꾸면 기준이 옮겨지지 않고 옛 파일이 남는다. `--tests '*ArchitectureRulesTest.<그 메서드>'` 로 골라 `-Parchunit.freeze.refreeze=true -Parchunit.freeze.store.default.allowStoreUpdate=true` 로 다시 얼린다. 그 규칙의 기준 파일(`stored.rules` 에서 설명으로 찾는다)이 수정된다
- 로거 규칙은 여기 두지 않는다. Lombok `@Slf4j` 가 만든 로거도 바이트코드에서는 `LoggerFactory.getLogger` 호출이라 손으로 쓴 것과 구별되지 않는다

## 작업 항목

### 1. `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` 에 규칙을 더한다

각 상수에 한국어 Javadoc 과 한국어 `as(...)` 설명을 단다. `that()` 대상이 없을 수 있는 규칙에는 `allowEmptyShould(true)` 를 붙인다.

| 상수 | 규칙 | 까닭 |
| --- | --- | --- |
| `TRANSACTIONAL_ONLY_IN_APPLICATION` | `..application..` 밖의 클래스와 그 메서드에 `org.springframework.transaction.annotation.Transactional`, `jakarta.transaction.Transactional` 이 없다. 클래스 규칙과 메서드 규칙을 `CompositeArchRule.of(...).and(...)` 로 묶어 상수 하나로 둔다 | 트랜잭션 경계는 유스케이스를 아는 층이 정한다. 컨트롤러와 저장소에 두면 경계가 둘로 갈린다 |
| `NO_DIRECT_INSTANT_NOW` | `noClasses().should().callMethod(Instant.class, "now")` | 시각을 주입받아야 테스트가 시각을 고정한다. 뒤 계획에서 `Clock` 빈을 둔다 |
| `MESSAGE_DIGEST_ONLY_IN_SHA256` | `com.bifos.assistant.shared.util.Sha256` 이 아닌 클래스가 `MessageDigest.getInstance(String)` 을 부르지 않는다 | 해시 구현을 한 곳에 둔다 |
| `CONFIGURATION_PROPERTIES_ARE_VALIDATED` | `@ConfigurationProperties` 가 붙은 클래스는 `org.springframework.validation.annotation.Validated` 도 붙는다 | 잘못된 설정은 기동에서 멈춘다 |
| `SERVICES_DO_NOT_EXPOSE_NESTED_TYPES` | 중첩 클래스 가운데 익명과 지역 클래스를 뺀 것의 바깥 클래스가 `@Service`, `@Component`, `@Repository` 이거나 `..infra..` 에 있으면, 그 중첩 타입은 `private` 이다 | 서비스가 돌려주는 모델을 서비스 파일 밖으로 뺀다(`backend/AGENTS.md` 「데이터 클래스는 컨트롤러 안에 두지 않는다」 와 같은 까닭). 캐시 키 같은 구현 세부는 `private` 으로 둔다 |
| `ENUMERATED_FIELDS_USE_DOMAIN_TYPE` | `@Entity` 클래스에 선언된 `@Enumerated` 필드의 타입이 `..domain.type..` 에 있다 | 저장되는 값은 바꾸면 마이그레이션을 판단해야 한다. 한곳에 모아 보이게 한다 |
| `DOMAIN_TYPE_DEPENDS_ON_NOTHING_ABOVE` | `..domain.type..` 의 클래스가 `..application..`, `..infra..`, `..presentation..` 에 의존하지 않는다 | 저장되는 enum 은 가장 아래 층이다 |

`ENUMERATED_FIELDS_USE_DOMAIN_TYPE` 은 `fields().that().areAnnotatedWith(Enumerated.class).and().areDeclaredInClassesThat().areAnnotatedWith(Entity.class).should().haveRawType(JavaClass.Predicates.resideInAPackage("..domain.type.."))` 모양이다. 저장되지 않는 서비스 결과와 화면용 enum 은 `<기능>.application.model` 에 두고, `ErrorCode` 는 `shared.error` 에 그대로 둔다. 이 둘은 규칙으로 검사하지 않고 `backend/AGENTS.md` 에 적는다.

### 2. `LAYER_DIRECTION` 을 고친다

- `whereLayer("infra").mayOnlyBeAccessedByLayers("application")` 으로 바꾼다. 나머지 층 설정은 그대로다
- 까닭: 컨트롤러가 저장소를 바로 쓰면 권한 확인과 트랜잭션 경계를 서비스가 갖지 못한다
- 의도 메모대로 이 규칙 하나만 다시 얼린다. 기준 줄이 컨트롤러 5개 몫만큼 늘어난다
- `backend/AGENTS.md` 「패키지 배치」 와 `docs/code-architecture.md` 「backend 패키지」 의 층 문장을 「`presentation` 은 `application` 을 거쳐 `infra` 에 닿는다」 는 뜻이 드러나게 고친다

### 3. `ArchitectureRulesTest.java` 에 테스트를 더한다

규칙마다 `@Test` 와 한국어 `@DisplayName` 하나. 본문은 `FreezingArchRule.freeze(ArchitectureRules.<상수>).check(MAIN);`

### 4. 기준을 만든다

```bash
# cwd: backend/
./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true
git add config/archunit
```

- 규칙별 기준 수를 센다. 코디네이터 집계와 크게 다르면 규칙을 다시 읽는다
- 속성 없이 `./gradlew archTest --rerun` 을 두 번 돌려 `git diff --exit-code config/archunit` 가 0 인지 본다

### 5. 규칙이 실제로 실패하는지 본다

출력은 저장소 밖에 저장한다. 되돌릴 때는 `git checkout -- <그 파일>` 만 쓴다.

- 기준에 없는 서비스 클래스 하나에 `Instant.now()` 호출을 넣어 `NO_DIRECT_INSTANT_NOW` 가 실패하는지 본다
- 기준에 없는 `@Service` 클래스에 `public record Probe() {}` 를 넣어 `SERVICES_DO_NOT_EXPOSE_NESTED_TYPES` 가 실패하고, `private record` 로 바꾸면 통과하는지 본다
- 기준에 없는 컨트롤러가 `infra` 의 저장소를 필드로 받게 해 `LAYER_DIRECTION` 이 실패하는지 본다

### 6. `backend/AGENTS.md` 「구조 규칙」 절

- 규칙 표에 새 상수와 까닭을 더한다
- enum 위치 규칙(저장되는 enum 은 `<기능>.domain.type`, 저장되지 않는 것은 `<기능>.application.model`, `ErrorCode` 는 `shared.error`)을 적는다

## 검증

```bash
# cwd: backend/
./gradlew archTest --rerun
./gradlew test
git diff --exit-code config/archunit   # 기준 파일을 git add 한 뒤 테스트가 고치지 않았다
```

```bash
# cwd: 저장소 root
scripts/check-public-safe.sh
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRulesTest.java` | 수정 |
| `backend/config/archunit/store/stored.rules` | 수정 |
| `backend/config/archunit/store/*` | 신규 |
| `backend/config/archunit/store/1cceeea4-f3c2-4415-b0dc-2b2f8d185226` | 수정 |
| `backend/AGENTS.md` | 수정 |
| `docs/code-architecture.md` | 수정 |
