# Phase 06. OpenRewrite 로 바뀐 파일의 규칙 위반을 자동으로 고친다

**Execution profile**: standard

## 목표

Checkstyle 규칙 가운데 기계적으로 고칠 수 있는 것을 OpenRewrite 레시피로 고치는 Gradle 태스크를 둔다.
Spotless 처럼 `origin/main` 과의 공통 조상 뒤에 바뀐 파일에만 결과를 남긴다.
이 phase 는 태스크와 범위 제한을 만들고 확인한다. 기존 위반을 고치지 않는다.

**범위 외**: `scripts/quality.sh` 에 연결(phase 08). 기존 위반 수정(뒤의 패키지 분리 계획). 레시피가 없는 규칙의 자동 수정.

## 컨텍스트

- OpenRewrite Gradle 플러그인 `org.openrewrite.rewrite` 7.41.0 과 `org.openrewrite.recipe:rewrite-recipe-bom` 3.38.0 을 쓴다. 2026-09-30 에 Maven Central 과 Gradle 플러그인 포털에서 확인한 최신이다
- 켜는 레시피 넷이다. 모두 받은 jar 에 클래스가 있는 것을 확인했다
  - `org.openrewrite.java.ShortenFullyQualifiedTypeReferences` (`rewrite-java`). phase 04 의 `fullyQualifiedName`
  - `org.openrewrite.staticanalysis.NeedBraces` (`rewrite-static-analysis`). phase 04 의 `NeedBraces`
  - `org.openrewrite.java.format.BlankLines` (`rewrite-java`). phase 04 의 `EmptyLineSeparator`
  - `org.openrewrite.java.migrate.lombok.log.UseSlf4j` (`rewrite-migrate-java`). phase 04 의 `lombokLogger`
- 레시피가 없어 자동으로 고치지 않는 규칙: private 빈 생성자(`privateEmptyConstructor`), 엔티티 손 접근자, 생성자 주입. 목록으로만 보인다
- 한 줄로 몰아 쓴 본문은 phase 05 의 palantir 포매터가 여러 줄로 나눈다. phase 05 가 그것을 확인했다
- Spotless 는 `ratchetFrom("origin/main")` 으로 `HEAD` 와 `origin/main` 의 공통 조상 뒤에 바뀐 파일만 다룬다. OpenRewrite 플러그인에는 그런 설정이 없다
- 순서는 OpenRewrite 다음 Spotless 다. OpenRewrite 가 바꾼 모양을 포매터가 정리한다

**근거 문서**: `docs/adr/ADR-040-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md`, `backend/AGENTS.md` 「코드 규칙」

## 의도 메모

- **시작 전에 `origin/main` 을 합친다.** `git fetch origin && git merge --no-edit origin/main`
- 합친 main 코드가 새 ArchUnit 간선 위반, 새 Checkstyle 위반, 새 한국어 테스트 이름을 들여오면 앞 phase 의 방법으로 처리하고 이 phase 의 커밋에 넣는다. 그 변경이 있으면 회신의 「특이사항」 에 파일 목록과 무엇을 했는지 적는다. 새 위반을 다시 얼릴 때는 그 규칙 하나만 대상으로 삼는다
- **범위 밖 파일은 실행 전 내용으로 되돌린다.** 범위는 `git diff --name-only $(git merge-base HEAD origin/main)` 의 Java 파일과 추적하지 않는 새 Java 파일이다. `rewriteRun` 이 저장소 전체를 바꾸게 두고, 실행 전에 저장소 밖에 떠 둔 범위 밖 파일의 내용으로 되돌리는 방식을 권한다. executor 가 더 단순하고 같은 결과를 내는 방법을 찾으면 그것을 쓰고 까닭을 회신에 적는다
- **범위 밖 파일에 사람이 고치던 변경이 있으면 건드리지 않는다.** 되돌리는 것은 실행 전 내용이다. `git checkout` 으로 `HEAD` 내용을 덮지 않는다
- 이 범위 제한은 Gradle 태스크 하나(`rewriteChanged`)에 담는다. `scripts/quality.sh fix` 는 그 태스크를 부르기만 한다. 셸과 Gradle 에 같은 규칙이 두 벌 생기지 않게 한다. Gradle 태스크로 담기 어렵다면 `scripts/rewrite-changed.sh` 로 두고 까닭을 회신에 적는다
- `rewriteDryRun` 은 검사에 쓰지 않는다. 검사는 Checkstyle 이 한다. OpenRewrite 는 고치는 데만 쓴다
- 다른 레시피는 켜지 않는다

## Blocked 조건

- OpenRewrite 플러그인 7.41.0 이 Gradle 9.5.0 과 JDK 21 에서 `rewriteRun` 을 끝내지 못한다 → `PHASE_BLOCKED: OpenRewrite 가 이 빌드에서 돌지 않는다` 와 전체 오류를 남기고 멈춘다
- `./gradlew test` 가 플러그인을 더한 것만으로 실패하거나 눈에 띄게 느려진다(2배 이상) → 같은 형식으로 멈춘다

## 작업 항목

### 1. `backend/gradle/libs.versions.toml`, `backend/build.gradle.kts`

- `[versions]` 에 `openrewrite-plugin = "7.41.0"`, `openrewrite-recipe-bom = "3.38.0"`
- `[plugins]` 에 `openrewrite = { id = "org.openrewrite.rewrite", version.ref = "openrewrite-plugin" }`
- `[libraries]` 에 `openrewrite-recipe-bom`, `openrewrite-static-analysis`(`org.openrewrite.recipe:rewrite-static-analysis`), `openrewrite-migrate-java`(`org.openrewrite.recipe:rewrite-migrate-java`)
- `plugins` 에 `alias(libs.plugins.openrewrite)`
- `dependencies` 에 `rewrite(platform(libs.openrewrite.recipe.bom))`, `rewrite(libs.openrewrite.static.analysis)`, `rewrite(libs.openrewrite.migrate.java)`
- `rewrite { activeRecipe(...) }` 에 컨텍스트의 네 레시피를 적는다. 위에 한국어 주석으로 레시피와 phase 04 규칙의 짝을 적는다
- `rewriteChanged` 태스크(또는 스크립트). 의도 메모대로 범위 밖 파일을 실행 전 내용으로 되돌린다. 범위 안 파일이 없으면 `rewriteRun` 을 부르지 않고 끝난다

### 2. 동작을 확인한다

출력은 저장소 밖에 저장한다. 확인에 쓴 파일은 끝에 되돌리고 `git status --short` 에 이 phase 의 변경만 남았는지 본다.

- 범위 안 파일(이 브랜치가 이미 바꾼 테스트 파일 하나)에 전체 이름 참조, 중괄호 없는 `if`, 빈 줄 없는 두 메서드를 넣는다. 범위 밖 파일(이 브랜치가 바꾸지 않은 main 파일 하나)에도 같은 것을 넣는다
- `./gradlew rewriteChanged` 뒤에 범위 안 파일은 셋이 고쳐지고, 범위 밖 파일은 넣은 그대로다
- 범위 밖 파일에 넣은 것이 실행 전 내용으로 남았는지 본다. `HEAD` 내용으로 돌아가면 틀린 것이다
- 이어서 `./gradlew spotlessApply` 와 `./gradlew checkstyleTest` 를 돌려 범위 안 파일이 Checkstyle error 0건인지 본다
- 로거 레시피는 `LoggerFactory.getLogger` 로 만든 로거가 있는 범위 안 파일을 하나 만들어 `@Slf4j` 로 바뀌는지 본다. 확인에 쓴 파일은 끝에 지운다

### 3. `backend/AGENTS.md` 「코드 규칙」 절

- 자동으로 고치는 규칙과 레시피의 짝, 자동으로 고치지 않는 규칙 목록
- OpenRewrite 도 바뀐 파일만 고친다는 것과 그 범위(공통 조상 뒤 바뀐 파일과 새 파일)
- 명령 `./gradlew rewriteChanged`. 뒤에 `./gradlew spotlessApply` 를 돌린다

## 검증

```bash
# cwd: backend/
./gradlew test
./gradlew checkstyleMain checkstyleTest spotlessCheck
```

- 작업 항목 2 의 확인 출력이 저장소 밖 파일에 있다
- 이 phase 의 설정을 커밋할 준비가 되면(작업 트리의 `backend/src` 에 다른 변경이 없을 때) `./gradlew rewriteChanged` 를 한 번 돌려 바뀐 파일 목록을 저장소 밖에 남긴다. 이 브랜치가 이미 바꾼 테스트 파일에 기존 위반이 있으면 고쳐진다. **이번 PR 은 기존 위반을 고치지 않으므로** 그 결과는 `git checkout -- backend/src` 로 되돌린다. 목록은 회신에 적는다

```bash
# cwd: 저장소 root
scripts/check-public-safe.sh
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/gradle/libs.versions.toml` | 수정 |
| `backend/build.gradle.kts` | 수정 |
| `backend/AGENTS.md` | 수정 |
