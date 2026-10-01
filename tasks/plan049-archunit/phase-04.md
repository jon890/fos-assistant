# Phase 04. Checkstyle 로 코드 규칙과 서식 규칙을 건다

**Execution profile**: standard

## 목표

쓰지 않는 import, 전체 이름(FQN) 참조, 빈 catch, 이름 규칙, 서식(메서드 사이 빈 줄, 한 줄 한 문장, 중괄호), 소스에서만 판정할 수 있는 Lombok 사용 규칙을 Checkstyle 설정으로 둔다.
길이와 생성자 주입은 경고로 두어 빌드를 실패시키지 않는다.
지금 있는 위반은 기준 파일에 두고 새 위반만 실패시킨다. 코드는 고치지 않는다.

**범위 외**: 줄 길이, 들여쓰기, 공백, import 순서(phase 05 포매터). 자동 수정(phase 06 OpenRewrite). `qualityCheck` 와 `scripts/quality.sh`(phase 08). 기존 위반 수정(뒤의 패키지 분리 계획).

## 컨텍스트

- Gradle 내장 `checkstyle` 플러그인을 쓴다. 태스크는 `checkstyleMain`, `checkstyleTest` 다. 이 둘은 `./gradlew test` 에 걸리지 않는다
- 설정 파일 위치는 Gradle 기본값 `backend/config/checkstyle/` 이다. Gradle 이 `config_loc` 속성으로 그 디렉터리를 넘긴다
- 테스트 메서드 이름은 phase 02 에서 영문 camelCase 와 `@DisplayName` 으로 옮겼다. 테스트의 `MethodName` 위반은 0건이어야 한다
- 2026-09-30 에 JDK 21, Checkstyle 14.3.0 으로 측정한 기존 위반(phase 02 전)
  - `UnusedImports`: main 3건, test 4건
  - `ConstantName`: main 44건. 43건이 `private static final Logger log`, 1건이 `mcp/application/McpToolService.java` 의 `json`
  - `EmptyCatchBlock`: main 2건, 모두 `chat/infra/ArtifactSourceFetcher.java`
  - `TypeName`: main 2건. Flyway 가 요구하는 `db/migration/V23__ConversationPublicId.java`, `V35__DropAgentTokenUserId.java`
  - `OneStatementPerLine`: main 58건, test 23건
- 코디네이터가 main 에서 집계한 값: 전체 이름 참조 약 92곳(테스트 포함), 한 줄로 몰아 쓴 메서드 69개(11파일), 중괄호 없는 제어문 84곳, 직접 만든 로거 43파일, `@RequiredArgsConstructor` 없이 생성자를 쓴 빈 27파일, 직접 쓴 private 빈 생성자 약 16곳
- 엔티티(`@Entity`) 가운데 `Agent`, `Memory`, `AgentExecution`, `AgentToken` 등은 Lombok `@Getter` 와 손으로 쓴 record 모양 접근자(`public String code() { return code; }`)가 함께 있다

**근거 문서**: `docs/adr/ADR-042-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md`, `backend/AGENTS.md` 의 「주석」 절(한국어 Javadoc)

## 의도 메모

- **시작 전에 `origin/main` 을 합친다.** `git fetch origin && git merge --no-edit origin/main`
- 합친 main 코드가 새 ArchUnit 간선 위반, 새 한국어 테스트 이름을 들여오면 앞 phase 의 방법으로 처리하고 이 phase 의 커밋에 넣는다. 그 변경이 있으면 회신의 「특이사항」 에 파일 목록과 무엇을 했는지 적는다. 새 위반을 다시 얼릴 때는 그 규칙 하나만 대상으로 삼는다
- 설계상 예외와 기존 위반을 **다른 파일**에 둔다. 설계상 예외는 앞으로도 허용하는 것이고, 기존 위반은 줄여 갈 목록이다. 기준마다 GitHub 이슈가 있다(team-lead 가 PR 전에 연다)
- 기존 위반 기준은 `(파일, 규칙)` 단위다. 줄 번호로 두면 파일을 고칠 때마다 기준이 어긋난다. 그 파일의 같은 규칙 위반은 새로 생겨도 잡지 못한다. 그 한계를 `backend/AGENTS.md` 에 적는다
- 정규식으로 만든 규칙은 `id` 를 붙이고 기준과 억제는 `id` 로 건다. `checks` 로 걸면 같은 종류의 정규식 규칙이 모두 억제된다
- **경고는 실패시키지 않는다.** `severity="warning"` 인 규칙은 `maxWarnings` 에 걸리지 않게 둔다. phase 08 의 `scripts/quality.sh check` 가 경고 목록을 따로 보인다

넣는 규칙과 까닭이다.

| 규칙 | 심각도 | 까닭 |
| --- | --- | --- |
| `UnusedImports`, `AvoidStarImport`, `RedundantImport`, `IllegalImport` | error | 읽는 사람이 실제 의존을 import 줄에서 바로 본다 |
| 전체 이름 참조 금지. `RegexpSinglelineJava`, `id="fullyQualifiedName"`, `ignoreComments=true` | error | 코드 안에 `java.util.concurrent.TimeoutException` 같은 이름을 쓰지 않고 import 한다. `import` 와 `package` 줄은 뺀다. 형식 예: `^(?!\s*(import\|package)\s).*\b(java\|javax\|jakarta\|org\|com\|io\|tools\|lombok)\.([a-z_][a-z0-9_]*\.)+[A-Z]` 이다. executor 가 문자열 안의 글과 어노테이션 인자에서 잘못 잡는지 보고 다듬는다. 같은 단순 이름의 두 타입을 한 파일에서 써서 import 로 가를 수 없을 때는 `SuppressWithNearbyCommentFilter` 로 `// 전체 이름 허용: <까닭>` 주석이 있는 줄만 허용한다 |
| `EmptyCatchBlock` (`exceptionVariableName` 이 `^(ignored\|expected)$` 이면 허용) | error | 예외를 삼키는 자리를 이름으로 드러내게 한다 |
| `EmptyStatement`, `EqualsHashCode`, `StringLiteralEquality`, `FallThrough`, `MissingOverride` | error | 결함으로 이어지는 모양이다 |
| `SimplifyBooleanExpression`, `SimplifyBooleanReturn`, `ModifierOrder`, `UpperEll`, `ArrayTypeStyle` | error | 같은 뜻을 한 모양으로 쓴다 |
| `PackageName`, `TypeName`, `MethodName`, `MemberName`, `ParameterName`, `LocalVariableName`, `LocalFinalVariableName`, `StaticVariableName`, `LambdaParameterName`, `RecordComponentName`, `ClassTypeParameterName`, `MethodTypeParameterName` | error | 기본 패턴 그대로 쓴다 |
| `ConstantName` (패턴 `^(log\|[A-Z][A-Z0-9]*(_[A-Z0-9]+)*)$`) | error | 로거 이름 `log` 는 Lombok `@Slf4j` 가 만드는 이름이다 |
| `EmptyLineSeparator` (`tokens` 는 `METHOD_DEF`, `CTOR_DEF`, `allowMultipleEmptyLines=true`) | error | 메서드와 생성자 사이에 빈 줄이 한 줄 이상 있어야 읽는 단위가 보인다. 인터페이스의 추상 메서드 선언 사이도 잡는다(지금 4파일 약 13곳). 이것은 OpenRewrite `BlankLines` 가 고치지 않을 수 있어 자동으로 고치지 않는 목록에 든다 |
| `OneStatementPerLine` | error | 한 줄에 한 문장 |
| `LeftCurly`(`eol`), `RightCurly` 둘. `alone` 은 `METHOD_DEF`, `CTOR_DEF`, `CLASS_DEF`, `LITERAL_FOR`, `LITERAL_WHILE`, `STATIC_INIT`, `INSTANCE_INIT`. `same` 은 `LITERAL_TRY`, `LITERAL_CATCH`, `LITERAL_FINALLY`, `LITERAL_IF`, `LITERAL_ELSE`, `LITERAL_DO` | error | `void a() { return; }` 처럼 본문을 한 줄에 몰아 쓰지 않는다. 제어문에 `alone` 을 걸면 `} else {` 와 `} catch (...) {` 가 위반이 되어 포매터와 어긋난다. executor 가 record 와 빈 본문, 람다가 잘못 걸리지 않는지 본다 |
| `NeedBraces` | error | 제어문은 한 문장이어도 중괄호를 쓴다 |
| 직접 만든 로거 금지. `RegexpSinglelineJava`, `id="lombokLogger"`, 형식 `LoggerFactory\s*\.\s*getLogger`, `ignoreComments=true` | error | 로거는 Lombok `@Slf4j` 로 둔다. 바이트코드로는 구별되지 않아 소스에서 본다 |
| 직접 쓴 private 빈 생성자 금지. `RegexpMultiline`, `id="privateEmptyConstructor"`, 형식 예 `private\s+[A-Z]\w*\s*\(\s*\)\s*\{\s*\}` | error | 인스턴스를 만들지 않는 클래스는 `@NoArgsConstructor(access = AccessLevel.PRIVATE)` 로 쓴다 |
| 엔티티의 손 접근자 금지. `RegexpMultiline`, `id="entityHandwrittenAccessor"`. `@Entity` 가 있는 파일에서 필드를 그대로 돌려주는 인자 없는 `public` 메서드(`public X code() { return code; }`, `this.` 포함, 여러 줄 포함)를 잡는다. 형식은 `\A(?=[\s\S]*@Entity\b)` 로 시작해 한 파일에 한 번 잡는 모양을 권한다 | error | 엔티티 접근자는 Lombok `@Getter` 와 `@Accessors(fluent = true)` 로 `code()` 모양을 만든다. 값을 꺼내는 메서드가 두 벌이 되지 않게 하고 record 와 같은 호출 모양을 지킨다 |
| `FileLength` (`max=500`) | warning | 쪼갤 후보 목록이다. 빈 줄과 주석을 포함한 전체 줄 수다(Checkstyle 이 이것만 센다) |
| `MethodLength` (`max=60`, `countEmpty=false`) | warning | 쪼갤 후보 목록이다. 빈 줄과 한 줄 주석은 세지 않는다 |
| 생성자 주입은 `@RequiredArgsConstructor`. `RegexpMultiline`, `id="requiredArgsConstructor"`. `@Service`, `@Component`, `@Repository`, `@RestController`, `@Controller`, `@Configuration` 이 붙고 `@RequiredArgsConstructor` 가 없는 파일에서 인자가 있는 생성자를 잡는다 | warning | 기본은 `@RequiredArgsConstructor` 다. 테스트 전용 생성자, 설정값 가공처럼 까닭이 있는 경우가 섞여 있어 목록으로만 보인다. 자동 수정하지 않는다 |

- `FileLength`, `MethodLength`, `requiredArgsConstructor` 는 테스트 코드에 걸지 않는다. 테스트는 시나리오가 길고 생성자 주입 규칙과 관계가 없다. `suppressions.xml` 의 설계상 예외로 둔다

빼는 규칙과 까닭이다.

| 규칙 | 까닭 |
| --- | --- |
| `LineLength`, `Indentation`, `WhitespaceAround`, `CustomImportOrder` | 포매터가 정한다. 둘이 같은 것을 다르게 판정하면 고칠 수 없는 위반이 생긴다 |
| `JavadocMethod`, `JavadocType`, `MissingJavadocMethod` 같은 Javadoc 규칙 | 주석은 한국어로 필요한 곳에만 쓴다(「주석」 절). 모든 메서드에 요구하면 뜻 없는 주석이 늘어난다 |
| `MagicNumber` | 테스트와 설정 기본값에서 대부분 오탐이다 |
| `FinalParameters`, `HiddenField` | 생성자 주입과 record 가 이름을 같게 쓰는 것이 이 저장소의 모양이다 |
| `DesignForExtension` | Spring 빈과 싸운다 |

## 작업 항목

### 1. `backend/gradle/libs.versions.toml`, `backend/build.gradle.kts`

- `[versions]` 에 `checkstyle = "14.3.0"`
- `plugins` 에 `checkstyle`
- `checkstyle { toolVersion = libs.versions.checkstyle.get(); isIgnoreFailures = false; maxWarnings = Int.MAX_VALUE }`. error 는 실패, warning 은 보고만 한다
- `tasks.withType<Checkstyle>().configureEach { reports { xml.required = true; html.required = false } }`

### 2. `backend/config/checkstyle/checkstyle.xml` (신규)

- `Checker` 에 `charset` `UTF-8`, 기본 `severity` `error`
- 의도 메모의 「넣는 규칙」 을 둔다. `RegexpMultiline`, `FileLength` 는 `Checker` 아래, 나머지는 `TreeWalker` 아래다. `SuppressWithNearbyCommentFilter` 는 `TreeWalker` 아래에 둔다
- `SuppressionFilter` 둘: `${config_loc}/suppressions.xml`(설계상 예외), `${config_loc}/baseline.xml`(기존 위반). 두 파일 모두 `optional` 을 `false` 로 둔다
- 파일 머리에 한국어 XML 주석으로 이 파일이 무엇이고 규칙 이유는 `backend/AGENTS.md` 에 있다고 적는다

### 3. `backend/config/checkstyle/suppressions.xml` (신규)

- `db/migration/V\d+__\w+\.java` 에 `TypeName` 을 끈다(`checks="(^|\.)TypeName(Check)?$"`). Flyway 가 이 이름을 요구한다
- `src[\\/]test[\\/]` 에 `FileLength`, `MethodLength`, `id="requiredArgsConstructor"` 를 끈다

### 4. `backend/config/checkstyle/baseline.xml` (신규)

- `baseline.xml` 을 빈 `<suppressions/>` 로 두고 `isIgnoreFailures = true` 로 잠시 돌린다. XML 보고서(`backend/build/reports/checkstyle/main.xml`, `test.xml`)에서 severity 가 error 인 위반의 `(파일, 규칙)` 을 뽑는다. 뽑은 뒤 `isIgnoreFailures` 를 `false` 로 되돌린다
- 내장 규칙은 한 줄에 하나씩 `<suppress checks="(^|\.)<규칙 이름>(Check)?$" files="<src 아래 상대 경로를 정규식으로>"/>`, 정규식 규칙은 `<suppress id="<id>" files="..."/>` 로 쓴다. `checks` 의 끝을 고정하지 않으면 `ParameterName` 이 `LambdaParameterName` 까지 억제한다. 경로 구분자는 `[\\/]` 로 쓴다. 파일 경로 순으로 정렬한다
- warning 규칙은 기준에 넣지 않는다
- 보고서를 뽑고 기준을 만드는 스크립트는 저장소 밖에 둔다. 갱신 방법은 문서에 적는다(작업 항목 5)
- 기준에 든 `(파일, 규칙)` 수와 위반 수를 규칙별로 센다. warning 규칙은 넘은 파일과 메서드 목록을 따로 남긴다. 둘 다 보고와 PR 본문에 쓴다

### 5. `backend/AGENTS.md` 에 「코드 규칙」 절을 더한다

- 도구와 버전, 설정 파일 셋의 역할
- 의도 메모의 두 표(넣은 규칙과 심각도와 까닭, 뺀 규칙)를 옮긴다
- 전체 이름을 꼭 써야 할 때의 주석 형식
- 기준 갱신 방법. 위반을 고치면 `baseline.xml` 에서 그 줄을 지운다(Checkstyle 은 쓰지 않는 기준 줄을 알리지 않는다). 새 위반은 기준에 더하지 않고 고친다. 꼭 받아들여야 하면 까닭을 커밋 메시지와 PR 본문에 적고 그 줄을 더한다
- `(파일, 규칙)` 기준의 한계
- 기준에 든 위반은 줄여 갈 목록이고 기준마다 GitHub 이슈가 있다
- 실행 명령 `./gradlew checkstyleMain checkstyleTest`

### 6. 규칙이 실제로 실패하는지 본다

기록만 남기고 되돌린다. 되돌릴 때는 `git checkout -- <그 파일>` 만 쓴다. 출력은 저장소 밖에 저장한다.

- 기준에 없는 main 파일 하나에 `import java.util.*;` 를 더해 `AvoidStarImport` 로 실패하는지 본다
- 기준에 없는 main 파일 하나에 `java.util.List<String> probe = java.util.List.of();` 같은 전체 이름 참조를 넣어 `fullyQualifiedName` 으로 실패하고, 같은 줄에 `// 전체 이름 허용: 확인` 을 붙이면 통과하는지 본다
- 기준에 없는 main 파일 하나에 `if (x) return;` 를 넣어 `NeedBraces` 로 실패하는지 본다
- 기준에 없는 main 파일 하나에 `private static final org.slf4j.Logger probe = org.slf4j.LoggerFactory.getLogger(Object.class);` 를 넣어 `lombokLogger` 로 실패하는지 본다
- 기준에 없는 test 파일 하나의 테스트 메서드 이름을 `한국어_이름` 으로 바꿔 `checkstyleTest` 가 `MethodName` 으로 실패하는지 본다
- 경고 규칙 둘 이상이 실제로 경고로 나오고 태스크는 성공하는지 보고서로 확인한다

## 검증

```bash
# cwd: backend/
./gradlew checkstyleMain checkstyleTest
./gradlew test
```

- 두 Checkstyle 태스크가 error 0건으로 통과한다. 경고는 보고서에 남는다
- 작업 항목 6 의 출력이 저장소 밖 파일에 있다

```bash
# cwd: 저장소 root
scripts/check-public-safe.sh
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/gradle/libs.versions.toml` | 수정 |
| `backend/build.gradle.kts` | 수정 |
| `backend/config/checkstyle/checkstyle.xml` | 신규 |
| `backend/config/checkstyle/suppressions.xml` | 신규 |
| `backend/config/checkstyle/baseline.xml` | 신규 |
| `backend/AGENTS.md` | 수정 |
