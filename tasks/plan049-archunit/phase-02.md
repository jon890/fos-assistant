# Phase 02. 한국어 테스트 메서드를 영문 이름과 `@DisplayName` 으로 옮긴다

**Execution profile**: standard

## 목표

한국어 문장으로 된 테스트 메서드 이름을 영문 camelCase 로 바꾸고, 원래 문장은 `@DisplayName` 에 옮긴다.
그래야 phase 03 의 메서드 이름 규칙을 테스트에도 예외 없이 건다. 테스트 보고서에는 지금처럼 한국어 문장이 보인다.
테스트에 `@DisplayName` 이 붙어 있는지는 ArchUnit 규칙으로 검사한다.

**범위 외**: 테스트 동작 변경, 테스트 클래스 이름, 포맷(phase 04). 이 phase 는 이름과 어노테이션만 바꾼다.

## 컨텍스트

- 2026-09-30 에 `origin/main` 을 합친 코드에서 `backend/src/test/java` 의 한국어가 든 메서드 이름은 약 887개다(`@Test` 약 810개, `@BeforeEach` 53개, `@AfterEach` 4개). `scope가_없으면_...`, `MEMBER_역할은_...` 처럼 ASCII 로 시작하는 이름도 약 150개 있다. 정확한 수는 작업 항목 1 이 다시 측정한다. `@DisplayName` 은 한 곳도 없다. `@ParameterizedTest` 가 2곳 있다. `@Nested` 와 한국어 클래스 이름은 없다
- 한국어 이름은 `@Test` 메서드뿐 아니라 `@BeforeEach` 같은 준비 메서드(`준비한다`, `비운다`)와 보조 메서드에도 있다
- `docs/`, `AGENTS.md`, `scripts/`, `.github/` 는 테스트 메서드 이름을 인용하지 않는다(`grep` 으로 확인했다)
- phase 01 이 `backend/src/test/java/com/bifos/assistant/architecture/ArchitectureRules.java` 와 `ArchitectureRulesTest.java`, 기준 디렉터리 `backend/config/archunit/store/` 를 만들었다. 기준 파일 갱신 방법은 `backend/AGENTS.md` 「구조 규칙」 절에 있다
- 나란히 도는 다른 브랜치가 테스트 파일을 고치고 있다. 이 phase 를 머지하면 그쪽이 충돌을 푼다. 사용자가 그 비용을 알고 한 번에 옮기는 쪽을 골랐다

**근거 문서**: `docs/adr/ADR-040-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md` 의 「대안 기각」 에서 테스트 메서드 이름 항목

## 의도 메모

- **시작 전에 `origin/main` 을 합친다.** `git fetch origin && git merge --no-edit origin/main`. 합친 뒤 들어온 한국어 테스트도 이 phase 에서 옮긴다
- 합친 main 코드가 새 ArchUnit 간선 위반, 새 Checkstyle 위반, 새 한국어 테스트 이름을 들여오면 앞 phase 의 방법으로 처리하고 이 phase 의 커밋에 넣는다. 그 변경이 있으면 회신의 「특이사항」 에 파일 목록과 무엇을 했는지 적는다. 새 위반을 다시 얼릴 때는 그 규칙 하나만 대상으로 삼는다
- `@DisplayName` 의 글은 **원래 메서드 이름의 `_` 를 공백으로 바꾼 것**이다. 문장을 새로 쓰거나 다듬지 않는다. 기계로 대조할 수 있어야 옮기다 뜻이 바뀌지 않았는지 확인할 수 있다
- 영문 이름은 무엇을 확인하는지 동사로 시작하는 camelCase 로 짓는다. 예: `그룹과_개인_항목을_층_순서대로_본문까지_넣는다` → `assemblesGroupAndUserItemsInLayerOrder`. 한 클래스 안에서 이름이 겹치지 않게 한다. 길이는 60자 안팎을 넘기지 않는다
- 준비와 정리 메서드(`@BeforeEach`, `@AfterEach`, `@BeforeAll`, `@AfterAll`)는 `setUp`, `tearDown` 처럼 관례 이름을 쓰고 `@DisplayName` 을 달지 않는다. 한 클래스에 여럿이면 하는 일을 이름에 담는다
- 보조 메서드는 영문 camelCase 로 바꾸고 `@DisplayName` 을 달지 않는다. 호출하는 곳도 함께 바꾼다
- 테스트 클래스에 `@DisplayName` 을 새로 달지 않는다. 클래스 이름이 이미 영문이고, 문장을 새로 지으면 위 대조가 성립하지 않는다
- 파일 수가 많아도 **한 executor 가 패키지 이름 순으로 차례로 옮긴다.** 패키지 하나를 끝낼 때마다 `./gradlew compileTestJava` 로 컴파일을 확인한다. 중간에 멈추면 어느 패키지까지 끝났는지 보고해 같은 executor 가 이어 간다

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
- 새 규칙의 기준 파일을 만든다. `stored.rules` 가 이미 있으므로 `allowStoreUpdate` 만 켠다. **새 규칙의 기준 파일이 비어 있지 않으면 멈춘다.** 옮기지 못한 테스트가 기준으로 얼려진 것이다. 그 테스트를 옮긴 뒤 `./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true` 를 다시 돌려, 옮긴 만큼 기준에서 빠져 파일이 비는지 확인한다. 기준 파일은 지우지 않는다. `stored.rules` 에 규칙과 파일의 짝이 남아 있어 파일만 지우면 `StoreReadException` 이 난다

```bash
# cwd: backend/
./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true
git add config/archunit
```

- 규칙이 실제로 실패하는지 본다. 한 테스트의 `@DisplayName` 을 잠시 지우고 `./gradlew archTest --rerun` 이 `TEST_METHODS_HAVE_DISPLAY_NAME` 으로 실패하는지 본 뒤 `git checkout -- <그 파일>` 이 아니라 지운 줄을 다시 넣어 되돌린다(그 파일은 이 phase 에서 이미 고쳤다). 출력은 저장소 밖에 저장한다

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
./gradlew archTest --rerun
# 이름에 ASCII 가 아닌 글자가 든 메서드 선언이 남으면 그 줄을 내고 1 로 끝난다
find src/test/java -name '*.java' -print0 | xargs -0 perl -ne '$f=1, print "$ARGV:$.: $_" if /^\s+(?:(?:public|protected|private|static|final|synchronized)\s+)*[\w<>\[\],.? ]+\s+[A-Za-z0-9_\$]*[^\x00-\x7F][^\s(]*\s*\(/; close ARGV if eof; END { exit($f ? 1 : 0) }'
git diff --exit-code config/archunit   # 기준 파일을 git add 한 뒤 테스트가 고치지 않았다
```

- `tests` 합계가 작업 항목 1 과 같고 실패와 건너뜀이 늘지 않았다
- 작업 항목 3 의 실패 확인과 작업 항목 4 의 대조 결과가 저장소 밖 파일에 있다

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
