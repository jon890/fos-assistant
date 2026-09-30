# Phase 03. Checkstyle 로 작은 코드 규칙 묶음을 건다

**Execution profile**: standard

## 목표

쓰지 않는 import, `*` import, 빈 catch, 이름 규칙 같은 작은 규칙을 Checkstyle 설정으로 둔다.
지금 있는 위반은 기준 파일에 두고 새 위반만 실패시킨다.

**범위 외**: 줄 길이, 들여쓰기, 공백, import 순서처럼 포매터가 정하는 것(phase 04). `qualityCheck` 태스크(phase 05).

## 컨텍스트

- Gradle 내장 `checkstyle` 플러그인을 쓴다. 태스크는 `checkstyleMain`, `checkstyleTest` 다. 이 둘은 `./gradlew test` 에 걸리지 않는다
- JDK 21 에서 Checkstyle 14.3.0 으로 아래 후보를 돌려 2026-09-30 에 측정한 기존 위반이다. 테스트 이름은 phase 02 에서 영문으로 옮겼으므로 테스트의 `MethodName` 위반은 0건이어야 한다
  - `UnusedImports`: main 3건(`hermes/HttpHermesToolsetClient.java`, `memory/presentation/MemoryController.java`, `usage/presentation/UsageDtos.java`), test 4건
  - `ConstantName`: main 44건. 43건이 `private static final Logger log` 이고 1건이 `mcp/application/McpToolService.java` 의 `json`
  - `EmptyCatchBlock`: main 2건, 모두 `chat/infra/ArtifactSourceFetcher.java`
  - `TypeName`: main 2건. Flyway 가 요구하는 `db/migration/V23__ConversationPublicId.java`, `V35__DropAgentTokenUserId.java`
  - `OneStatementPerLine`: main 58건, test 23건. `try { ... } catch (...) { ... }` 를 한 줄에 쓴 것들이다
  - 그 밖의 후보는 0건
- 설정 파일 위치는 Gradle 기본값 `backend/config/checkstyle/` 이다. Gradle 이 `config_loc` 속성으로 그 디렉터리를 넘긴다

**근거 문서**: `docs/adr/ADR-040-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md`, `backend/AGENTS.md` 의 「주석」 절(한국어 Javadoc)

## 의도 메모

- **시작 전에 `origin/main` 을 합친다.** `git fetch origin && git merge --no-edit origin/main`. 기준은 합친 코드로 만든다
넣는 규칙과 까닭이다.

| 규칙 | 까닭 |
| --- | --- |
| `UnusedImports`, `AvoidStarImport`, `RedundantImport`, `IllegalImport` | 읽는 사람이 실제 의존을 import 줄에서 바로 본다 |
| `EmptyCatchBlock` (`exceptionVariableName` 이 `^(ignored\|expected)$` 이면 허용) | 예외를 삼키는 자리를 이름으로 드러내게 한다. 이 저장소는 이미 `ignored` 를 쓴다 |
| `EmptyStatement`, `EqualsHashCode`, `StringLiteralEquality`, `FallThrough`, `MissingOverride` | 결함으로 이어지는 모양이다 |
| `SimplifyBooleanExpression`, `SimplifyBooleanReturn`, `ModifierOrder`, `UpperEll`, `ArrayTypeStyle` | 같은 뜻을 한 모양으로 쓴다 |
| `PackageName`, `TypeName`, `MethodName`, `MemberName`, `ParameterName`, `LocalVariableName`, `LocalFinalVariableName`, `StaticVariableName`, `LambdaParameterName`, `RecordComponentName`, `ClassTypeParameterName`, `MethodTypeParameterName` | 기본 패턴 그대로 쓴다 |
| `ConstantName` (패턴 `^(log\|[A-Z][A-Z0-9]*(_[A-Z0-9]+)*)$`) | 로거 이름 `log` 는 저장소의 관례라 허용한다 |

빼는 규칙과 까닭이다.

| 규칙 | 까닭 |
| --- | --- |
| `LineLength`, `Indentation`, `WhitespaceAround`, `CustomImportOrder`, `OneStatementPerLine` 같은 배치 규칙 | 포매터가 정한다. 둘이 같은 것을 다르게 판정하면 고칠 수 없는 위반이 생긴다 |
| `JavadocMethod`, `JavadocType`, `MissingJavadocMethod` 같은 Javadoc 규칙 | 주석은 한국어로 필요한 곳에만 쓴다(「주석」 절). 모든 메서드에 요구하면 뜻 없는 주석이 늘어난다 |
| `MagicNumber` | 테스트와 설정 기본값에서 대부분 오탐이다 |
| `FinalParameters`, `HiddenField` | 생성자 주입과 record 가 이름을 같게 쓰는 것이 이 저장소의 모양이다 |
| `DesignForExtension` | Spring 빈과 싸운다 |

- 설계상 예외와 기존 위반을 **다른 파일**에 둔다. 설계상 예외는 앞으로도 허용하는 것이고, 기존 위반은 고쳐서 줄일 목록이다
- 기준에 든 위반은 줄여 갈 목록이다. 기준마다 GitHub 이슈가 있다(team-lead 가 PR 전에 연다)
- 기존 위반 기준은 `(파일, 규칙)` 단위다. 줄 번호로 두면 파일을 고칠 때마다 기준이 어긋난다. 그 파일의 같은 규칙 위반은 새로 생겨도 잡지 못한다. 그 한계를 `backend/AGENTS.md` 에 적는다

## 작업 항목

### 1. `backend/gradle/libs.versions.toml`, `backend/build.gradle.kts`

- `[versions]` 에 `checkstyle = "14.3.0"`
- `plugins` 에 `checkstyle`
- `checkstyle { toolVersion = libs.versions.checkstyle.get(); maxWarnings = 0; isIgnoreFailures = false }`
- `tasks.withType<Checkstyle>().configureEach { reports { xml.required = true; html.required = false } }`

### 2. `backend/config/checkstyle/checkstyle.xml` (신규)

- `Checker` 에 `charset` `UTF-8`, `severity` `error`
- 의도 메모의 「넣는 규칙」 을 `TreeWalker` 아래에 둔다
- `SuppressionFilter` 둘: `${config_loc}/suppressions.xml`(설계상 예외), `${config_loc}/baseline.xml`(기존 위반). 두 파일 모두 `optional` 을 `false` 로 둔다
- 파일 머리에 한국어 XML 주석으로 이 파일이 무엇이고 규칙 이유는 `backend/AGENTS.md` 에 있다고 적는다

### 3. `backend/config/checkstyle/suppressions.xml` (신규)

- `db/migration/V\d+__\w+\.java` 에 `TypeName` 을 끈다(`checks="(^|\.)TypeName(Check)?$"`). Flyway 가 이 이름을 요구한다

### 4. `backend/config/checkstyle/baseline.xml` (신규)

- `baseline.xml` 을 빈 `<suppressions/>` 로 두고 `isIgnoreFailures = true` 로 잠시 돌린다. XML 보고서(`backend/build/reports/checkstyle/main.xml`, `test.xml`)에서 `(파일, 규칙)` 을 뽑는다. 뽑은 뒤 `isIgnoreFailures` 를 `false` 로 되돌린다
- 한 줄에 하나씩 `<suppress checks="(^|\.)<규칙 이름>(Check)?$" files="<src 아래 상대 경로를 정규식으로>"/>`. Checkstyle 은 `checks` 를 검사 이름에 정규식 find 로 맞추므로 끝을 고정하지 않으면 `ParameterName` 이 `LambdaParameterName` 까지 억제한다. 경로 구분자는 `[\\/]` 로 쓴다. 파일 경로 순으로 정렬한다
- 보고서를 뽑고 기준을 만드는 스크립트는 저장소 밖에 둔다. 갱신 방법은 문서에 명령으로 적는다(작업 항목 5)
- 기준에 든 `(파일, 규칙)` 수와 위반 수를 센다. 보고와 PR 본문에 쓴다

### 5. `backend/AGENTS.md` 에 「코드 규칙」 절을 더한다

- 도구와 버전, 설정 파일 셋의 역할
- 의도 메모의 두 표(넣은 규칙, 뺀 규칙)를 그대로 옮긴다
- 기준 갱신 방법. 위반을 고치면 `baseline.xml` 에서 그 줄을 지운다(Checkstyle 은 쓰지 않는 기준 줄을 알리지 않는다). 새 위반은 기준에 더하지 않고 고친다. 꼭 받아들여야 하면 까닭을 커밋 메시지와 PR 본문에 적고 그 줄을 더한다
- `(파일, 규칙)` 기준의 한계
- 실행 명령 `./gradlew checkstyleMain checkstyleTest`

### 6. 규칙이 실제로 실패하는지 본다

기록만 남기고 되돌린다. 출력은 저장소 밖에 저장한다.

- 기준에 없는 main 파일 하나에 `import java.util.*;` 를 더해 `./gradlew checkstyleMain` 이 `AvoidStarImport` 로 실패하는지 본다
- 기준에 없는 test 파일 하나의 테스트 메서드 이름을 `한국어_이름` 으로 바꿔 `./gradlew checkstyleTest` 가 `MethodName` 으로 실패하는지 본다
- 둘을 되돌리고 `git status --short` 에 남지 않았는지 확인한다

## 검증

```bash
# cwd: backend/
./gradlew checkstyleMain checkstyleTest
./gradlew test
```

- 두 Checkstyle 태스크가 위반 0건으로 통과한다
- 작업 항목 6 의 두 실패 출력이 저장소 밖 파일에 있다

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
