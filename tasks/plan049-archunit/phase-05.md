# Phase 05. web 에 eslint 를 두고 `scripts/quality.sh` 로 한 번에 검사하고 고친다

**Execution profile**: standard

## 목표

web 에 eslint 를 실제로 설치해 `pnpm lint` 가 돌게 한다. 기존 위반은 기준 파일에 둔다.
backend 의 품질 검사를 Gradle 태스크 `qualityCheck` 하나로 묶는다.
`scripts/quality.sh check` 와 `scripts/quality.sh fix` 를 진입점으로 두고, CI 와 AGENTS.md 「확인」 에 `check` 를 더한다.

**범위 외**: Prettier, 새 lint 규칙 작성, 기존 위반 수정.

## 컨텍스트

- `web/package.json` 에 `"lint": "eslint"` 스크립트만 있고 eslint 패키지와 설정 파일이 없다. 지금 `pnpm lint` 는 명령을 찾지 못해 실패한다
- next 는 `16.0.10` 이다. `eslint-config-next@16.0.10` 은 `eslint >=9` 를 요구하고 `typescript-eslint`, `eslint-plugin-react`, `eslint-plugin-react-hooks`, `@next/eslint-plugin-next` 등을 가져온다
- eslint 는 9.39.x 줄을 쓴다. 10 은 위 플러그인들이 아직 전제하지 않는다. ESLint 9.24 부터 기존 위반을 `eslint-suppressions.json` 에 두는 bulk suppressions 가 있다(`--suppress-all`, `--suppress-rule`, `--prune-suppressions`)
- Node 는 `22.18.0`, pnpm 은 `10.22.0` 이다(`web/package.json`, `.github/workflows/ci.yml`)
- backend 태스크는 앞 phase 가 만들었다. `archTest`(phase 01), `checkstyleMain`, `checkstyleTest`(phase 03), `spotlessCheck`, `spotlessApply`(phase 04). ArchUnit 기준을 줄이는 속성은 `-Parchunit.freeze.store.default.allowStoreUpdate=true` 다(`backend/AGENTS.md` 「구조 규칙」)
- CI 는 `.github/workflows/ci.yml` 이다. 모든 job 이 `actions/checkout` 을 `persist-credentials: false` 로 쓰고 깊이를 정하지 않는다(기본 1). ratchet 에는 `origin/main` 이력이 필요하다
- AGENTS.md 「확인」 절에 명령 여섯 줄이 있고, 그 아래 문장이 「위 여섯 검사」 와 CI job 이름 `backend`, `web`, `e2e`, `unit`, `public-safe` 를 적는다

**근거 문서**: `docs/adr/ADR-040-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md`

## 의도 메모

- 코디네이터가 2026-09-30 에 eslint 설치와 bulk suppressions 방식을 정했다. 넣은 규칙 묶음, 기준에 든 위반 수, 기준 갱신 방법을 `web/AGENTS.md` 와 PR 본문에 적는다
- `check` 는 파일을 바꾸지 않는다. 종료 코드로 알린다. backend 와 web 을 모두 돌린 뒤 하나라도 실패했으면 0 이 아닌 값으로 끝난다
- `fix` 는 사람이 판단하지 않아도 되는 것만 고친다. Spotless 포맷, `eslint --fix`, 고친 위반을 기준에서 빼기(ArchUnit 기준 줄이기, `eslint --prune-suppressions`)다. 새 위반을 기준에 더하지 않는다. 고친 뒤 `check` 를 돌려 남은 위반을 「사람이 판단할 위반」 으로 보인다
- Checkstyle 기준은 스스로 줄지 않는다. `fix` 는 Checkstyle 기준을 고치지 않는다

## 작업 항목

### 1. web 의 eslint

- `web/package.json` 의 `devDependencies` 에 `eslint` 를 `9.39.5`, `eslint-config-next` 를 `16.0.10` 으로 정확한 버전(`^` 없이) 더한다. `pnpm install` 로 `web/pnpm-lock.yaml` 을 갱신한다
- `web/eslint.config.mjs` (신규): `eslint/config` 의 `defineConfig`, `globalIgnores` 로 `eslint-config-next/core-web-vitals` 와 `eslint-config-next/typescript` 를 펼치고, `.next/**`, `out/**`, `build/**`, `next-env.d.ts` 를 무시한다. 머리에 한국어 주석으로 규칙 이유와 기준 갱신 방법이 `web/AGENTS.md` 에 있다고 적는다
- 기존 위반을 기준에 둔다

  ```bash
  # cwd: web/
  pnpm exec eslint --suppress-all
  pnpm lint   # 0 으로 끝난다
  ```

  `web/eslint-suppressions.json` 을 커밋한다. 규칙별 위반 수를 센다. 위반이 0건이라 파일이 생기지 않으면 그 사실을 보고하고 변경 파일 표에서 뺀다
- `pnpm typecheck` 와 `pnpm build` 가 그대로 통과하는지 본다. `pnpm build` 에 필요한 환경 변수는 `web/AGENTS.md` 에 있다

### 2. `backend/build.gradle.kts` 의 `qualityCheck`

- `tasks.register("qualityCheck")`: `group = "verification"`, 설명은 한국어. `dependsOn("archTest", "checkstyleMain", "checkstyleTest", "spotlessCheck")`

### 3. `scripts/quality.sh` (신규, 실행 권한)

- `#!/usr/bin/env bash`, `set -euo pipefail`. 저장소 root 는 스크립트 위치에서 구한다
- 인자 `check` 또는 `fix`. 그 밖이면 사용법을 내고 2 로 끝난다
- `web/node_modules` 가 없으면 `cd web && pnpm install --frozen-lockfile` 을 먼저 하라고 알리고 1 로 끝난다
- `check`
  1. `(cd backend && ./gradlew qualityCheck)`
  2. `(cd web && pnpm lint)`
  3. 둘 다 돌린 뒤 결과를 한 줄씩 요약하고, 하나라도 실패했으면 1 로 끝난다
- `fix`
  1. `(cd backend && ./gradlew spotlessApply archTest -Parchunit.freeze.store.default.allowStoreUpdate=true)`
  2. `(cd web && pnpm exec eslint --fix && pnpm exec eslint --prune-suppressions)`
  3. `check` 를 돌린다. 실패하면 「사람이 판단할 위반」 이라고 알리고 그 종료 코드로 끝난다
- 주석과 출력 문구는 한국어로 쓴다

### 4. `test/unit/quality-script.test.ts` (신규)

`node:test` 와 `node:assert/strict` 로 `scripts/quality.sh` 의 인자 처리를 검사한다. `test/unit/design-tokens.test.ts` 처럼 저장소 root 의 파일을 읽는 테스트의 경로 구하는 방식을 따른다. `node:child_process` 의 `spawnSync("bash", [스크립트 경로, ...인자])` 로 부른다.

- 인자가 없으면 2 로 끝나고 표준 오류에 `check` 와 `fix` 가 든 사용법이 나온다
- 모르는 인자(`lint`)도 같다
- 스크립트 파일에 실행 권한이 있다(`fs.statSync(...).mode & 0o111`)

Gradle 과 pnpm 을 부르는 경로는 작업 항목 7 이 실제 명령으로 확인한다. 그 경로는 느리고 설치된 도구에 걸려 단위 테스트에 두지 않는다.

### 5. `.github/workflows/ci.yml` 에 `quality` job

- 이름 `quality`, `runs-on: ubuntu-latest`, `timeout-minutes: 15`
- `actions/checkout` 은 다른 job 과 같은 고정 SHA 에 `persist-credentials: false`, `fetch-depth: 0`. 그 위에 한국어 주석으로 ratchet 이 `origin/main` 이력을 쓴다고 적는다
- `setup-java`(temurin 21), `setup-gradle`, `pnpm/action-setup`(10.22.0, `package_json_file: web/package.json`), `setup-node`(22.18.0, pnpm 캐시)를 다른 job 과 같은 SHA 로 쓴다
- `cd web && pnpm install --frozen-lockfile` 뒤에 `scripts/quality.sh check`
- PR 의 merge ref 에서 `origin/main` 이 있는지 확인한다. `actions/checkout` 이 `fetch-depth: 0` 이면 원격 브랜치를 모두 받는다

### 6. 문서

- `AGENTS.md` 「확인」 의 명령 목록 끝에 `scripts/quality.sh check` 를 더한다. 「위 여섯 검사」 를 새 개수로 고치고, 「머지는 PR 로 한다」 의 CI job 목록에 `quality` 를 더한다. `quality.sh fix` 가 무엇을 고치는지 한 줄 적고 자세한 것은 `backend/AGENTS.md` 와 `web/AGENTS.md` 를 가리킨다
- `backend/AGENTS.md`: 「구조 규칙」, 「코드 규칙」, 「포맷」 절 앞에 한 절을 두어 `./gradlew qualityCheck` 가 셋을 묶는다는 것과 `scripts/quality.sh` 를 가리킨다
- `web/AGENTS.md` 에 「lint」 절: 도구와 버전, 규칙 묶음(`core-web-vitals`, `typescript`), 기준 파일, 갱신 방법(고치면 `pnpm exec eslint --prune-suppressions`, 새 위반은 고친다, 꼭 받아들여야 하면 `--suppress-rule <규칙>` 과 까닭), `fix` 가 하는 일, Prettier 를 쓰지 않는다는 것

### 7. 동작을 확인한다

출력은 저장소 밖에 저장한다. 되돌린 뒤 `git status --short` 가 이 phase 의 변경만 남았는지 본다.

- `scripts/quality.sh check` 를 돌리기 전과 뒤의 `git status --porcelain` 이 같다. 종료 코드 0
- web 의 `.tsx` 파일 하나에 `eslint --fix` 로 고칠 수 있는 위반(예: `prefer-const` 에 걸리는 `let`)과 고칠 수 없는 위반을 하나씩 넣는다. backend 의 이 브랜치가 바꾼 Java 파일 하나의 들여쓰기를 흐트러뜨린다
  - `check` 가 1 로 끝나고 세 위반을 모두 보인다
  - `fix` 가 고칠 수 있는 둘을 고치고, 고칠 수 없는 하나만 「사람이 판단할 위반」 으로 보인다
  - `fix` 뒤 `web/eslint-suppressions.json` 이 늘지 않았다(항목 수를 비교한다)
- 넣은 것을 되돌리고 `check` 가 다시 0 으로 끝난다
- 기준에 든 eslint 위반 하나를 고쳤을 때 `pnpm lint` 가 쓰지 않는 기준을 알리는지, `fix` 가 그 줄을 빼는지 본다. 확인 뒤 되돌린다

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
| `web/package.json` | 수정 |
| `web/pnpm-lock.yaml` | 수정 |
| `web/eslint.config.mjs` | 신규 |
| `web/eslint-suppressions.json` | 신규 |
| `backend/build.gradle.kts` | 수정 |
| `scripts/quality.sh` | 신규 |
| `test/unit/quality-script.test.ts` | 신규 |
| `.github/workflows/ci.yml` | 수정 |
| `AGENTS.md` | 수정 |
| `backend/AGENTS.md` | 수정 |
| `web/AGENTS.md` | 수정 |
