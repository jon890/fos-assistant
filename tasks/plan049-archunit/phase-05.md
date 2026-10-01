# Phase 05. Spotless 와 palantir-java-format 으로 바뀐 파일만 포맷한다

**Execution profile**: standard

## 목표

Java 포맷을 Spotless 설정으로 정하고, `origin/main` 에서 바뀐 파일만 검사하고 고친다.
이 브랜치가 앞 phase 에서 바꾼 Java 파일을 이 phase 에서 포맷해, 포맷 변경이 한 커밋에 모이게 한다.

**범위 외**: 저장소 전체 포맷, OpenRewrite 자동 수정(phase 06), web 포맷(phase 07), `qualityCheck` 와 `scripts/quality.sh`, CI(phase 08).

## 컨텍스트

- 포매터는 palantir-java-format 2.100.0 이다. 선택 까닭은 ADR-041 「대안 기각」 에 있다. 들여쓰기 4칸, 한 줄 120자다
- 2026-09-30 에 같은 코드로 측정했다. 저장소 전체에 적용하면 palantir 는 337개 중 258개 파일, google 기본 모양은 337개, AOSP 모양은 307개 파일이 바뀐다
- Spotless Gradle 플러그인 8.10.3. `ratchetFrom("origin/main")` 이면 `origin/main` 과 내용이 다른 파일만 검사한다
- 이 phase 를 돌리는 작업 공간은 git linked worktree 일 수 있다. Spotless 의 ratchet 은 JGit 으로 저장소를 연다
- 앞 phase 들이 바꾼 Java 파일은 `git diff --name-only origin/main -- 'backend/src/**/*.java'` 로 본다. phase 02 가 테스트 파일 약 110개를 바꿨으므로 그 파일들도 이번에 포맷된다
- phase 04 의 Checkstyle 은 줄 길이와 들여쓰기를 판정하지 않는다. 포맷 뒤에도 error 0건으로 통과해야 한다. palantir 가 한 줄 본문을 여러 줄로 나누는지 확인해 phase 04 의 `LeftCurly`, `RightCurly`, `OneStatementPerLine` 과 어긋나지 않는지 본다. 어긋나면(포맷한 결과가 Checkstyle 에 걸리면) 멈추고 보고한다

**근거 문서**: `docs/adr/ADR-041-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md`

## 의도 메모

- **시작 전에 `origin/main` 을 합친다.** `git fetch origin && git merge --no-edit origin/main`. ratchet 은 합친 코드와 `origin/main` 의 공통 조상을 비교한다
- 합친 main 코드가 새 ArchUnit 간선 위반, 새 Checkstyle 위반, 새 한국어 테스트 이름을 들여오면 앞 phase 의 방법으로 처리하고 이 phase 의 커밋에 넣는다. 그 변경이 있으면 회신의 「특이사항」 에 파일 목록과 무엇을 했는지 적는다. 새 위반을 다시 얼릴 때는 그 규칙 하나만 대상으로 삼는다
- `ratchetFrom` 은 사용자가 고른 방식이다. 저장소 전체를 한 번에 포맷하지 않는다
- 포매터가 import 순서와 쓰지 않는 import 도 정리한다. `removeUnusedImports()` 를 따로 걸지 않는다(palantir 가 한다). 실제로 그런지 작업 항목 3 에서 본다
- Javadoc 본문 포맷은 켜지 않는다(`formatJavadoc(false)`, 기본값). 한국어 Javadoc 의 줄바꿈을 바꾸지 않는다
- Spotless 8.10.3 이 쓰는 spotless-lib-extra 의 `GitRatchet` 은 `RevFilter.MERGE_BASE` 로 **`HEAD` 와 `origin/main` 의 공통 조상**과 작업 트리를 비교하고, `commondir` 로 linked worktree 를 연다(critic 이 소스로 확인했다). 그래서 로컬 `origin/main` 이 앞서 나가도 이 브랜치가 고치지 않은 파일은 잡히지 않는다. 다만 `git fetch` 를 오래 하지 않아 공통 조상이 옛 커밋이면 그 뒤 main 에 들어온 파일은 비교 대상이 아니다

## Blocked 조건

- linked worktree 에서 `./gradlew spotlessCheck` 가 저장소를 열지 못한다 → `PHASE_BLOCKED: Spotless ratchet 이 linked worktree 에서 git 저장소를 열지 못한다` 와 전체 오류를 남기고 멈춘다

## 작업 항목

### 1. `backend/gradle/libs.versions.toml`, `backend/build.gradle.kts`

- `[versions]` 에 `spotless = "8.10.3"`, `palantir-java-format = "2.100.0"`
- `[plugins]` 에 `spotless = { id = "com.diffplug.spotless", version.ref = "spotless" }`
- `plugins` 에 `alias(libs.plugins.spotless)`
- 설정

  ```kotlin
  spotless {
      ratchetFrom("origin/main")
      java {
          target("src/main/java/**/*.java", "src/test/java/**/*.java")
          palantirJavaFormat(libs.versions.palantir.java.format.get())
          trimTrailingWhitespace()
          endWithNewline()
      }
  }
  ```

  위에 한국어 주석으로 ratchet 이 무엇을 뜻하는지와 ADR-041 을 적는다

### 2. 이 브랜치가 바꾼 파일을 포맷한다

```bash
# cwd: backend/
./gradlew spotlessApply
```

- 바뀐 파일이 `git diff --name-only origin/main` 의 Java 파일 안에만 있는지 본다. 밖의 파일이 바뀌면 ratchet 이 동작하지 않은 것이다. 되돌리고 원인을 찾는다
- 포맷만 바뀌었는지 본다. `./gradlew test` 의 `tests` 합계가 phase 04 끝과 같다

### 3. 동작을 확인한다

출력은 저장소 밖에 저장한다. 되돌린 뒤 `git status --short` 가 이 phase 의 변경만 남았는지 본다.

- 이 브랜치에서 이미 바뀐 Java 파일 하나의 들여쓰기를 일부러 흐트러뜨린다. `./gradlew spotlessCheck` 가 실패하고 `./gradlew spotlessApply` 가 되돌리는지 본다
- 이 브랜치가 바꾸지 않은 Java 파일 하나에 쓰지 않는 import 한 줄과 흐트러진 들여쓰기를 넣는다. `spotlessCheck` 가 그 파일을 잡고 `spotlessApply` 가 **그 파일 전체**를 포맷하는지 본다. 그 뒤 `git checkout -- <그 파일>` 로 되돌린다
- 바꾸지 않은 파일은 120자를 넘는 줄이 있어도 `spotlessCheck` 가 잡지 않는지 본다

### 4. `backend/AGENTS.md` 에 「포맷」 절을 더한다

- 도구, 포매터, 버전, 들여쓰기 4칸과 120자
- `ratchetFrom("origin/main")` 이라 바뀐 파일만 검사하고, 파일을 처음 고치면 그 파일 전체가 포맷된다는 것
- **기능 변경과 포맷을 다른 커밋으로 나눈다.** 먼저 기능을 고치고 커밋한 뒤 `./gradlew spotlessApply` 결과를 따로 커밋한다
- 비교 기준은 `HEAD` 와 `origin/main` 의 공통 조상이다. `git fetch origin` 뒤에 돌린다
- 한글 한 글자를 한 칸으로 센다는 것
- 명령 `./gradlew spotlessCheck`, `./gradlew spotlessApply`

## 검증

```bash
# cwd: backend/
./gradlew spotlessCheck
./gradlew checkstyleMain checkstyleTest
./gradlew test
```

- 셋 모두 통과한다
- 작업 항목 3 의 출력이 저장소 밖 파일에 있다

```bash
# cwd: 저장소 root
scripts/check-public-safe.sh
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/gradle/libs.versions.toml` | 수정 |
| `backend/build.gradle.kts` | 수정 |
| `backend/src/test/java/com/bifos/assistant/**/*.java` | 수정 |
| `backend/AGENTS.md` | 수정 |
