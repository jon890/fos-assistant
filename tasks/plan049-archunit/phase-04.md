# Phase 04. Spotless 와 palantir-java-format 으로 바뀐 파일만 포맷한다

**Execution profile**: standard

## 목표

Java 포맷을 Spotless 설정으로 정하고, `origin/main` 에서 바뀐 파일만 검사하고 고친다.
이 브랜치가 앞 phase 에서 바꾼 Java 파일을 이 phase 에서 포맷해, 포맷 변경이 한 커밋에 모이게 한다.

**범위 외**: 저장소 전체 포맷, web 포맷, `qualityCheck` 와 `scripts/quality.sh`, CI(phase 05).

## 컨텍스트

- 포매터는 palantir-java-format 2.100.0 이다. 선택 까닭은 ADR-040 「대안 기각」 에 있다. 들여쓰기 4칸, 한 줄 120자다
- 2026-09-30 에 같은 코드로 측정했다. 저장소 전체에 적용하면 palantir 는 337개 중 258개 파일, google 기본 모양은 337개, AOSP 모양은 307개 파일이 바뀐다
- Spotless Gradle 플러그인 8.10.3. `ratchetFrom("origin/main")` 이면 `origin/main` 과 내용이 다른 파일만 검사한다
- 이 phase 를 돌리는 작업 공간은 git linked worktree 일 수 있다. Spotless 의 ratchet 은 JGit 으로 저장소를 연다
- 앞 phase 들이 바꾼 Java 파일은 `git diff --name-only origin/main -- 'backend/src/**/*.java'` 로 본다. phase 02 가 테스트 파일 95개를 바꿨으므로 그 파일들도 이번에 포맷된다
- phase 03 의 Checkstyle 은 포맷을 판정하지 않는다. 포맷 뒤에도 통과해야 한다

**근거 문서**: `docs/adr/ADR-040-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md`

## 의도 메모

- `ratchetFrom` 은 사용자가 고른 방식이다. 저장소 전체를 한 번에 포맷하지 않는다
- 포매터가 import 순서와 쓰지 않는 import 도 정리한다. `removeUnusedImports()` 를 따로 걸지 않는다(palantir 가 한다). 실제로 그런지 작업 항목 3 에서 본다
- Javadoc 본문 포맷은 켜지 않는다(`formatJavadoc(false)`, 기본값). 한국어 Javadoc 의 줄바꿈을 바꾸지 않는다
- `ratchetFrom` 의 기준이 `origin/main` 의 끝인지 두 브랜치의 공통 조상인지를 이 phase 에서 확인하고 문서에 적는다. 끝이라면, 로컬 `origin/main` 이 앞서 나갔을 때 이 브랜치가 고치지 않은 파일이 잡힐 수 있다. 그때 할 일(`origin/main` 을 합친다)을 적는다

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

  위에 한국어 주석으로 ratchet 이 무엇을 뜻하는지와 ADR-040 을 적는다

### 2. 이 브랜치가 바꾼 파일을 포맷한다

```bash
# cwd: backend/
./gradlew spotlessApply
```

- 바뀐 파일이 `git diff --name-only origin/main` 의 Java 파일 안에만 있는지 본다. 밖의 파일이 바뀌면 ratchet 이 동작하지 않은 것이다. 되돌리고 원인을 찾는다
- 포맷만 바뀌었는지 본다. `./gradlew test` 의 `tests` 합계가 phase 02 끝과 같다

### 3. 동작을 확인한다

출력은 저장소 밖에 저장한다. 되돌린 뒤 `git status --short` 가 이 phase 의 변경만 남았는지 본다.

- 이 브랜치에서 이미 바뀐 Java 파일 하나의 들여쓰기를 일부러 흐트러뜨린다. `./gradlew spotlessCheck` 가 실패하고 `./gradlew spotlessApply` 가 되돌리는지 본다
- 이 브랜치가 바꾸지 않은 Java 파일 하나에 쓰지 않는 import 한 줄과 흐트러진 들여쓰기를 넣는다. `spotlessCheck` 가 그 파일을 잡고 `spotlessApply` 가 **그 파일 전체**를 포맷하는지 본다. 그 뒤 `git checkout -- <그 파일>` 로 되돌린다
- 바꾸지 않은 파일은 120자를 넘는 줄이 있어도 `spotlessCheck` 가 잡지 않는지 본다
- `ratchetFrom` 의 기준이 끝인지 공통 조상인지 확인한다. 예: 저장소 밖 임시 clone 에서 `origin/main` 을 앞으로 옮긴 뒤 결과를 본다

### 4. `backend/AGENTS.md` 에 「포맷」 절을 더한다

- 도구, 포매터, 버전, 들여쓰기 4칸과 120자
- `ratchetFrom("origin/main")` 이라 바뀐 파일만 검사하고, 파일을 처음 고치면 그 파일 전체가 포맷된다는 것
- **기능 변경과 포맷을 다른 커밋으로 나눈다.** 먼저 기능을 고치고 커밋한 뒤 `./gradlew spotlessApply` 결과를 따로 커밋한다
- 로컬 `origin/main` 이 오래되면 결과가 달라진다. `git fetch origin` 뒤에 돌린다. 작업 항목 3 에서 확인한 기준(끝 또는 공통 조상)에 따라 할 일을 적는다
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
