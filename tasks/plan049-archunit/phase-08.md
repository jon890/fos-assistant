# Phase 08. `scripts/quality.sh` 로 한 번에 검사하고 고치며 CI 와 AGENTS.md 에 잇는다

**Execution profile**: standard

## 목표

backend 의 품질 검사를 Gradle 태스크 `qualityCheck` 하나로 묶는다.
`scripts/quality.sh check` 와 `scripts/quality.sh fix` 를 진입점으로 두고, 경고 목록을 따로 보인다.
CI 와 AGENTS.md 「확인」 에 `check` 를 더한다.

**범위 외**: 새 규칙, 기존 위반 수정.

## 컨텍스트

- backend 태스크는 앞 phase 가 만들었다. `archTest`(phase 01), `checkstyleMain`, `checkstyleTest`(phase 04), `spotlessCheck`, `spotlessApply`(phase 05), `rewriteChanged`(phase 06). ArchUnit 기준을 줄이는 속성은 `-Parchunit.freeze.store.default.allowStoreUpdate=true` 다(`backend/AGENTS.md` 「구조 규칙」)
- web 스크립트는 phase 07 이 만들었다. `pnpm lint`, `pnpm format:check`, `pnpm format:changed`. `eslint --fix --prune-suppressions` 는 한 번에 준다. ESLint 9.39.5 는 쓰지 않는 기준 항목이 남으면 2 로 끝나기 때문이다
- 경고로 두어 실패시키지 않는 규칙: Checkstyle `FileLength`, `MethodLength`, `requiredArgsConstructor`(phase 04), eslint `max-lines`, `max-lines-per-function`(phase 07). Checkstyle 보고서는 `backend/build/reports/checkstyle/main.xml` 에 있다
- Node 는 `22.18.0`, pnpm 은 `10.22.0` 이다(`web/package.json`, `.github/workflows/ci.yml`)
- CI 는 `.github/workflows/ci.yml` 이다. 모든 job 이 `actions/checkout` 을 `persist-credentials: false` 로 쓰고 깊이를 정하지 않는다(기본 1). ratchet 과 바뀐 파일 목록에는 `origin/main` 이력이 필요하다
- AGENTS.md 「확인」 절에 명령 여섯 줄이 있고, 그 아래 문장이 「위 여섯 검사」 와 CI job 이름 `backend`, `web`, `e2e`, `unit`, `public-safe` 를 적는다

**근거 문서**: `docs/adr/ADR-040-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md`

## 의도 메모

- **시작 전에 `origin/main` 을 합친다.** `git fetch origin && git merge --no-edit origin/main`
- 합친 main 코드가 새 ArchUnit 간선 위반, 새 Checkstyle 위반, 새 eslint 위반, 새 한국어 테스트 이름을 들여오면 앞 phase 의 방법으로 처리하고 이 phase 의 커밋에 넣는다. 그 변경이 있으면 회신의 「특이사항」 에 파일 목록과 무엇을 했는지 적는다
- `check` 는 파일을 바꾸지 않는다. 종료 코드로 알린다. backend 와 web 을 모두 돌린 뒤 하나라도 실패했으면 1 로 끝난다
- `check` 는 끝에 「경고 목록(실패 아님)」 을 따로 보인다. 넘은 파일과 메서드, 줄 수다. 뒤의 패키지 분리 계획이 쪼갤 후보로 쓴다
- `fix` 는 사람이 판단하지 않아도 되는 것만 고친다. 순서는 OpenRewrite(`rewriteChanged`), Spotless(`spotlessApply`), ArchUnit 기준 줄이기, `eslint --fix --prune-suppressions`, Prettier(`format:changed`)다. 새 위반을 기준에 더하지 않는다. 앞 단계가 실패해도 다음 단계로 가고, 끝에 `check` 를 돌려 남은 위반을 「사람이 판단할 위반」 으로 보인다
- Checkstyle 기준은 스스로 줄지 않는다. `fix` 는 Checkstyle 기준을 고치지 않는다
- 경고 목록을 뽑는 일은 `scripts/quality-warnings.mjs` 하나에 둔다. 셸에 XML 과 JSON 을 푸는 heredoc 을 두지 않는다

## 작업 항목

### 1. `backend/build.gradle.kts` 의 `qualityCheck`

- `tasks.register("qualityCheck")`: `group = "verification"`, 설명은 한국어. `dependsOn("archTest", "checkstyleMain", "checkstyleTest", "spotlessCheck")`

### 2. `scripts/quality-warnings.mjs` (신규)

- 기본 입력은 Checkstyle XML 보고서 `backend/build/reports/checkstyle/main.xml` 과 eslint JSON `web/build/eslint-report.json` 이다. 앞의 것에서 severity 가 `warning` 인 항목을, 뒤의 것에서 경고(`severity` 1) 항목을 읽어 규칙별로 `파일:줄 메시지` 를 낸다. 경로는 저장소 root 기준으로 줄인다
- 입력이 없으면 그 도구는 「보고서 없음」 으로 한 줄 낸다. 이 스크립트는 늘 0 으로 끝난다
- Node 내장 모듈만 쓴다

### 3. `scripts/quality.sh` (신규, 실행 권한)

- `#!/usr/bin/env bash`, `set -euo pipefail`. 저장소 root 는 스크립트 위치에서 구한다
- 인자 `check` 또는 `fix`. 그 밖이면 사용법을 표준 오류에 내고 2 로 끝난다
- `web/node_modules` 가 없으면 `cd web && pnpm install --frozen-lockfile` 을 먼저 하라고 알리고 1 로 끝난다
- `check`
  1. `(cd backend && ./gradlew qualityCheck)`
  2. `(cd web && pnpm lint --format json --output-file build/eslint-report.json)` 한 번으로 검사하고 경고 입력도 만든다. 사람이 읽을 요약은 이 JSON 에서 `quality-warnings.mjs` 가 낸다. 이어서 `(cd web && pnpm format:check)`. `web/build/` 가 `.gitignore` 에 없으면 더한다
  3. 둘 다 돌린 뒤 결과를 한 줄씩 요약한다
  4. `node scripts/quality-warnings.mjs` 로 경고 목록을 낸다
  5. 1, 2 가운데 하나라도 실패했으면 1 로 끝난다
- `fix`
  1. `(cd backend && ./gradlew rewriteChanged)`
  2. `(cd backend && ./gradlew spotlessApply archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true)`
  3. `(cd web && pnpm exec eslint --fix --prune-suppressions)`
  4. `(cd web && pnpm format:changed)`
  5. 1~4 의 종료 코드는 받아 두기만 하고 멈추지 않는다(`set -e` 아래에서 `|| status=$?` 처럼 받는다)
  6. `check` 를 돌린다. 실패하면 「사람이 판단할 위반」 이라고 알리고 그 종료 코드로 끝난다
- 주석과 출력 문구는 한국어로 쓴다

### 4. `test/unit/quality-script.test.ts` (신규)

`node:test` 와 `node:assert/strict` 로 인자 처리와 경고 목록을 검사한다. `test/unit/design-tokens.test.ts` 처럼 `import.meta.dirname` 으로 경로를 구한다.

- `spawnSync("bash", [scripts/quality.sh])` 가 인자 없이 2 로 끝나고 표준 오류에 `check` 와 `fix` 가 든 사용법이 나온다. 모르는 인자(`lint`)도 같다
- 두 스크립트 파일에 실행 권한이 있거나(`quality.sh`) Node 로 부를 수 있다(`quality-warnings.mjs`)
- `quality-warnings.mjs` 에 저장소 밖 임시 디렉터리의 Checkstyle XML(경고 하나, error 하나)과 eslint JSON(경고 하나, error 하나)을 넘겨 경고 둘만 나오고 error 는 나오지 않는다. check 가 error 목록을 따로 보이지 않아도 되는 것은 `pnpm lint` 의 종료 코드와 Gradle 출력이 error 를 알리기 때문이다. 입력 경로를 인자나 환경 변수로 바꿀 수 있게 만든다
- Gradle 과 pnpm 을 부르는 경로는 작업 항목 7 이 실제 명령으로 확인한다. 그 경로는 느리고 설치된 도구에 걸려 단위 테스트에 두지 않는다

### 5. `.github/workflows/ci.yml` 에 `quality` job

- 이름 `quality`, `runs-on: ubuntu-latest`, `timeout-minutes: 20`
- `actions/checkout` 은 다른 job 과 같은 고정 SHA 에 `persist-credentials: false`, `fetch-depth: 0`. 그 위에 한국어 주석으로 바뀐 파일만 검사하는 도구가 `origin/main` 이력을 쓴다고 적는다
- `setup-java`(temurin 21), `setup-gradle`, `pnpm/action-setup`(10.22.0, `package_json_file: web/package.json`), `setup-node`(22.18.0, pnpm 캐시)를 다른 job 과 같은 SHA 로 쓴다
- `cd web && pnpm install --frozen-lockfile` 뒤에 `scripts/quality.sh check`
- merge ref 에서 `origin/main` 이 있는지는 PR 을 연 뒤 team-lead 가 확인한다. 회신의 「미검증」 에 적는다

### 6. 문서

- `AGENTS.md` 「확인」 의 명령 목록 끝에 `scripts/quality.sh check` 를 더한다. 「위 여섯 검사」 를 새 개수로 고치고, 「머지는 PR 로 한다」 의 CI job 목록에 `quality` 를 더한다. `quality.sh fix` 가 무엇을 고치는지 한 줄 적고 자세한 것은 `backend/AGENTS.md` 와 `web/AGENTS.md` 를 가리킨다
- `backend/AGENTS.md`: 「구조 규칙」, 「코드 규칙」, 「포맷」 절 앞에 한 절을 두어 `./gradlew qualityCheck` 가 셋을 묶는다는 것과 `scripts/quality.sh` 를 가리킨다. 기준에 든 위반은 줄여 갈 목록이고 기준마다 GitHub 이슈가 있다는 것을 적는다. 「구조 규칙」 의 「위반을 고쳤을 때」 에 `scripts/quality.sh fix` 도 기준을 줄인다고 더한다
- `web/AGENTS.md` 「lint 와 포맷」 절에 `scripts/quality.sh` 를 가리키는 한 줄을 더한다

### 7. 동작을 확인한다

출력은 저장소 밖에 저장한다. 확인에 쓴 변경은 끝에 되돌리고 `git status --short` 에 이 phase 의 변경만 남았는지 본다.

- `scripts/quality.sh check` 를 돌리기 전과 뒤의 `git status --porcelain` 이 같다. 종료 코드 0. 경고 목록이 나온다
- 넣을 것
  - web: `eslint --fix` 로 고칠 수 있는 위반 하나(예: `prefer-const` 에 걸리는 `let`)와 고칠 수 없는 위반 하나(예: phase 07 의 `fetch` 금지)를 기준에 없는 이 브랜치가 바꾼 파일에 넣는다. 그 파일의 들여쓰기도 흐트러뜨린다
  - backend: 이 브랜치가 바꾼 Java 파일 하나에 전체 이름 참조 하나와 흐트러진 들여쓰기를 넣는다. 고칠 수 없는 위반 하나(예: `Instant.now()` 호출)를 기준에 없는 main 파일에 넣는다
- `check` 가 1 로 끝나고 넣은 위반을 모두 보인다
- `fix` 가 고칠 수 있는 것(전체 이름, 들여쓰기, `prefer-const`, web 들여쓰기)을 고치고, 고칠 수 없는 둘만 「사람이 판단할 위반」 으로 보인다
- `fix` 뒤 `web/eslint-suppressions.json` 이 늘지 않았다(항목 수를 비교한다). `backend/config/archunit/store` 와 `backend/config/checkstyle/baseline.xml` 이 늘지 않았다
- 넣은 것을 되돌리고 `check` 가 다시 0 으로 끝난다

## 검증

AGENTS.md 「확인」 절을 적힌 순서대로 모두 돌린다. 새 브랜치라면 `cd web && pnpm install --frozen-lockfile` 을 먼저 한다.
`pnpm build` 의 환경 변수는 `web/AGENTS.md` 의 자리표시자 값을 쓴다.
브라우저 검사는 한 번에 하나만 돈다. 돌리기 전에 다른 브라우저 검사가 끝났는지 확인한다.

```bash
cd backend && ./gradlew test
cd web && pnpm typecheck && pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
scripts/quality.sh check
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/build.gradle.kts` | 수정 |
| `scripts/quality.sh` | 신규 |
| `scripts/quality-warnings.mjs` | 신규 |
| `test/unit/quality-script.test.ts` | 신규 |
| `.github/workflows/ci.yml` | 수정 |
| `.gitignore` | 수정 |
| `AGENTS.md` | 수정 |
| `backend/AGENTS.md` | 수정 |
| `web/AGENTS.md` | 수정 |
