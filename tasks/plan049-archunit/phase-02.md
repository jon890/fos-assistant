# Phase 02. 한국어 테스트 메서드를 영문 이름과 `@DisplayName` 으로 옮긴다

**Execution profile**: standard

## 목표

한국어 문장으로 된 테스트 메서드 이름을 영문 camelCase 로 바꾸고, 원래 문장은 `@DisplayName` 에 옮긴다.
그래야 phase 03 의 메서드 이름 규칙을 테스트에도 예외 없이 건다. 테스트 보고서에는 지금처럼 한국어 문장이 보인다.
테스트에 `@DisplayName` 이 붙어 있는지는 ArchUnit 규칙으로 검사한다.

**범위 외**: 테스트 동작 변경, 테스트 클래스 이름, 포맷(phase 04). 이 phase 는 이름과 어노테이션만 바꾼다.

## 컨텍스트

- 2026-09-30 기준으로 `backend/src/test/java` 의 95개 파일에 한국어 이름의 `void` 메서드가 727개 있다. `@DisplayName` 은 한 곳도 없다. `@ParameterizedTest` 가 2곳 있다. `@Nested` 와 한국어 클래스 이름은 없다
- 한국어 이름은 `@Test` 메서드뿐 아니라 `@BeforeEach` 같은 준비 메서드(`준비한다`, `비운다`)와 보조 메서드에도 있다
- `docs/`, `AGENTS.md`, `scripts/`, `.github/` 는 테스트 메서드 이름을 인용하지 않는다(`grep` 으로 확인했다)
- phase 01 이 `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` 와 `ArchitectureRulesTest.java`, 기준 디렉터리 `backend/config/archunit/store/` 를 만들었다. 기준 파일 갱신 방법은 `backend/AGENTS.md` 「구조 규칙」 절에 있다
- 나란히 도는 다른 브랜치가 테스트 파일을 고치고 있다. 이 phase 를 머지하면 그쪽이 충돌을 푼다. 사용자가 그 비용을 알고 한 번에 옮기는 쪽을 골랐다

**근거 문서**: `docs/adr/ADR-040-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md` 의 「대안 기각」 에서 테스트 메서드 이름 항목

## 의도 메모

- `@DisplayName` 의 글은 **원래 메서드 이름의 `_` 를 공백으로 바꾼 것**이다. 문장을 새로 쓰거나 다듬지 않는다. 기계로 대조할 수 있어야 옮기다 뜻이 바뀌지 않았는지 확인할 수 있다
- 영문 이름은 무엇을 확인하는지 동사로 시작하는 camelCase 로 짓는다. 예: `그룹과_개인_항목을_층_순서대로_본문까지_넣는다` → `assemblesGroupAndUserItemsInLayerOrder`. 한 클래스 안에서 이름이 겹치지 않게 한다. 길이는 60자 안팎을 넘기지 않는다
- 준비와 정리 메서드(`@BeforeEach`, `@AfterEach`, `@BeforeAll`, `@AfterAll`)는 `setUp`, `tearDown` 처럼 관례 이름을 쓰고 `@DisplayName` 을 달지 않는다. 한 클래스에 여럿이면 하는 일을 이름에 담는다
- 보조 메서드는 영문 camelCase 로 바꾸고 `@DisplayName` 을 달지 않는다. 호출하는 곳도 함께 바꾼다
- 테스트 클래스에 `@DisplayName` 을 새로 달지 않는다. 클래스 이름이 이미 영문이고, 문장을 새로 지으면 위 대조가 성립하지 않는다
- 파일 수가 많다. 패키지별로 나눠 여러 사람이 나란히 옮겨도 된다. 한 파일을 두 사람이 고치지 않게 나눈다

## 작업 항목

### 1. 옮기기 전 목록을 저장소 밖에 남긴다

`backend/src/test/java` 에서 이름에 ASCII 가 아닌 글자가 든 메서드 선언을 모두 뽑아 `(파일, 메서드 이름, 붙은 어노테이션)` 목록을 저장소 밖 파일로 남긴다.
테스트 결과 XML(`backend/build/test-results/test/*.xml`)의 `tests` 합계도 남긴다. 옮긴 뒤 같은 수여야 한다.

### 2. `backend/src/test/java/com/bifos/assistant/**/*.java` 의 이름을 옮긴다

- `@Test`, `@ParameterizedTest` 메서드: 영문 이름으로 바꾸고 `@DisplayName("<원래 이름의 _ 를 공백으로>")` 을 단다. `org.junit.jupiter.api.DisplayName` 을 import 한다
- 준비, 정리, 보조 메서드: 의도 메모대로 이름만 바꾼다
- 메서드를 이름으로 참조하는 곳(`@MethodSource("...")`, 리플렉션, 문자열로 적은 이름)이 있으면 함께 바꾼다

### 3. `ArchitectureRules.TEST_METHODS_HAVE_DISPLAY_NAME` 을 더한다

- `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` 에 상수를 더한다. `methods().that().areAnnotatedWith(org.junit.jupiter.api.Test.class).or().areAnnotatedWith(org.junit.jupiter.params.ParameterizedTest.class).should().beAnnotatedWith(org.junit.jupiter.api.DisplayName.class)`. `as(...)` 설명은 한국어로 쓴다
- `ArchitectureRulesTest.java` 에 테스트 클래스만 읽는 `static final JavaClasses TESTS = new ClassFileImporter().withImportOption(ImportOption.Predefined.ONLY_INCLUDE_TESTS).importPackages("com.bifos.assistant");` 를 두고, 이 규칙을 `FreezingArchRule.freeze(...).check(TESTS)` 로 검사하는 `@Test` 를 더한다. 이 테스트에도 `@DisplayName` 을 단다
- 새 규칙의 기준 파일을 만든다. 위반은 0건이어야 한다

```bash
# cwd: backend/
./gradlew archTest -Parchunit.freeze.store.default.allowStoreCreation=true -Parchunit.freeze.store.default.allowStoreUpdate=true
```

- `backend/AGENTS.md` 「구조 규칙」 절의 규칙 표에 이 상수를 더한다. 그 절이나 「테스트」 절에 테스트 이름 관례(영문 camelCase 메서드 이름과 한국어 `@DisplayName`, 준비 메서드는 `@DisplayName` 없음)를 적는다

### 4. 옮긴 결과를 대조한다

저장소 밖에 둔 목록으로 아래를 확인한다. 대조 스크립트는 저장소 밖에 둔다.

- `backend/src/test/java` 에 이름에 ASCII 가 아닌 글자가 든 메서드 선언이 0개다
- 작업 항목 1 의 `@Test`/`@ParameterizedTest` 메서드마다, 같은 파일에 `@DisplayName("<원래 이름의 _ 를 공백으로>")` 이 정확히 하나 있다
- `./gradlew test` 의 `tests` 합계가 작업 항목 1 과 같다. 실패와 건너뜀이 늘지 않았다

## 검증

```bash
# cwd: backend/
./gradlew test
./gradlew archTest
grep -rnP '^\s+(public |protected |private |static |final )*[\w<>\[\], ?]+\s+[^\x00-\x7F][^\s(]*\s*\(' src/test/java | wc -l   # 0
git status --short config/archunit   # 기준 파일을 만든 뒤 테스트가 고치지 않았다면 새 규칙 파일 말고는 비어 있다
```

```bash
# cwd: 저장소 root
scripts/check-public-safe.sh
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/test/java/com/bifos/assistant/**/*.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRulesTest.java` | 수정 |
| `backend/config/archunit/store/stored.rules` | 수정 |
| `backend/config/archunit/store/*` | 신규 |
| `backend/AGENTS.md` | 수정 |
