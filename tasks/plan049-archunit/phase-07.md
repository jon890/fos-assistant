# Phase 07. web 에 eslint 규칙과 Prettier 를 둔다

**Execution profile**: standard

## 목표

web 에 eslint 를 실제로 설치해 `pnpm lint` 가 돌게 하고, 조사로 찾은 규칙을 더한다.
Prettier 를 두고 `origin/main` 과의 공통 조상 뒤에 바뀐 파일만 검사하고 고친다.
기존 eslint 위반은 기준 파일에 둔다. 코드는 고치지 않는다.

**범위 외**: `scripts/quality.sh` 와 CI(phase 08). API 응답 도우미와 요청 본문 도우미 같은 새 코드(뒤 계획). 기존 위반 수정.

## 컨텍스트

- `web/package.json` 에 `"lint": "eslint"` 스크립트만 있고 eslint 패키지와 설정 파일이 없다. 지금 `pnpm lint` 는 명령을 찾지 못해 실패한다
- next 는 `16.0.10` 이다. `eslint-config-next@16.0.10` 은 `eslint >=9` 를 요구하고 `typescript-eslint`, `eslint-plugin-react`, `eslint-plugin-react-hooks`, `@next/eslint-plugin-next` 를 가져온다
- eslint 는 9.39.x 줄을 쓴다. 10 은 위 플러그인들이 아직 전제하지 않는다. ESLint 9.24 부터 기존 위반을 `eslint-suppressions.json` 에 두는 bulk suppressions 가 있다(`--suppress-all`, `--suppress-rule`, `--prune-suppressions`). ESLint 9.39.5 는 쓰지 않는 기준 항목이 남으면 2 로 끝난다
- Prettier 는 3.9.9 가 최신이다(2026-09-30)
- `web/tsconfig.json` 의 `paths` 에 `"@/*": ["./src/*"]` 가 있다
- `test/unit/*.test.ts` 는 `node --test` 로 돌고 `../../web/src/...` 를 상대 경로로 import 한다. Node 는 `@/` 를 풀지 못한다. 그래서 이 테스트가 읽는 web 파일과 그 파일이 다시 import 하는 web 파일은 상대 경로 import 를 써야 한다. 지금 `../` 로 import 하는 web 파일은 4개다
- 2026-09-30 측정: `web/src/app/api` 에서 `NextResponse.json({ code` 60곳, `request.json()` 16곳
- 코디네이터가 2026-09-30 에 eslint 설치와 bulk suppressions 방식, 아래 규칙 (1)~(5)와 Prettier 를 정했다

**근거 문서**: `docs/adr/ADR-041-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md`, `web/AGENTS.md`

## 의도 메모

- **시작 전에 `origin/main` 을 합친다.** `git fetch origin && git merge --no-edit origin/main`
- 합친 main 코드가 새 ArchUnit 간선 위반, 새 Checkstyle 위반, 새 한국어 테스트 이름을 들여오면 앞 phase 의 방법으로 처리하고 이 phase 의 커밋에 넣는다. 그 변경이 있으면 회신의 「특이사항」 에 파일 목록과 무엇을 했는지 적는다
- ADR-041 은 처음에 Prettier 를 넣지 않는다고 적었다. 코디네이터 결정으로 넣으므로 ADR-041 의 그 대안 기각 항목을 고친다(작업 항목 5)
- Prettier 설정은 기본값을 쓴다. 널리 쓰는 기본 모양이 좋은 포맷이고, 설정을 늘리면 읽는 사람이 규칙을 다시 배워야 한다. `components/ui` 도 포함한다
- Prettier 는 eslint 와 겹치는 규칙이 없다. `eslint-config-next` 는 서식 규칙을 켜지 않는다. `eslint-config-prettier` 는 더하지 않는다. executor 가 실제로 겹치는 규칙이 없는지 확인한다
- 바뀐 파일만 고르는 방법은 Spotless 와 같다. `git diff --name-only $(git merge-base HEAD origin/main)` 과 추적하지 않는 새 파일 가운데 `web/` 아래 Prettier 가 다루는 확장자다. 이 목록을 뽑는 일은 `web/scripts/changed-files.mjs` 하나에 두고(Node 내장 모듈만 쓴다. CI `unit` job 은 의존을 설치하지 않는다) `format:check` 와 `format:changed` 스크립트가 함께 쓴다. 셸과 Node 에 같은 규칙이 두 벌 생기지 않게 한다
- `eslint.config.mjs` 는 `web/` 에 있으므로 `files` 와 `ignores` glob 은 `src/...` 로 적는다
- 경고 규칙(`max-lines`, `max-lines-per-function`)은 실패시키지 않는다. bulk suppressions 는 error 만 다룬다

넣는 규칙과 까닭이다.

| 규칙 | 설정 | 심각도 | 까닭 |
| --- | --- | --- | --- |
| `eslint-config-next/core-web-vitals`, `eslint-config-next/typescript` | 그대로 | 설정대로 | Next.js 와 React 의 결함 모양 |
| (1) API 라우트의 응답과 본문 | `src/app/api/**` 에 `no-restricted-syntax` 로 `NextResponse.json` 의 첫 인자가 `code` 속성을 가진 객체인 호출, `request.json()` 호출(`req.json()` 처럼 이름이 달라도 `Request` 의 `.json()` 이면 좋지만 정확히 판정할 수 없으면 이름 `request`, `req` 만)을 막는다 | error | 오류 응답 모양과 요청 본문 검사를 한곳의 도우미로 모은다. 도우미는 뒤 계획에서 만든다 |
| (2) 화면 코드의 전역 `fetch` | `src/components/**`, `src/app/**/*.tsx` 에 `no-restricted-globals` 로 `fetch` 를 막는다. `src/lib/stream.ts` 와 업로드처럼 예외가 필요한 파일은 설정의 `ignores` 목록에 까닭 주석과 함께 둔다 | error | 화면은 `lib/` 의 호출 함수를 거친다 |
| (3) 파일과 함수 길이 | `max-lines` 400, `max-lines-per-function` 150. 빈 줄과 주석은 세지 않는다(`skipBlankLines`, `skipComments`) | warning | 쪼갤 후보 목록이다 |
| (4) 상위 경로 import 금지 | `no-restricted-imports` 의 `patterns` 로 `../*` 를 막고 `@/` 를 쓰게 한다. 컨텍스트의 `node --test` 가 읽는 파일과 그 파일이 import 하는 파일은 `ignores` 에 둔다. 그 목록은 executor 가 `test/unit/*.test.ts` 의 import 를 따라가 구한다 | error | 파일을 옮겨도 import 가 깨지지 않는다 |
| (5) Prettier | 3.9.9, 기본 설정. `.prettierignore` 에 `.next`, `out`, `build`, `next-env.d.ts`, `pnpm-lock.yaml`, `eslint-suppressions.json`. 마지막 것은 ESLint 가 끝 줄바꿈 없이 쓰고 Prettier 는 붙여, 두 도구가 번갈아 고친다 | 바뀐 파일만 검사 | 화면 코드의 서식을 한 모양으로 둔다 |

## 작업 항목

### 1. eslint

- `web/package.json` 의 `devDependencies` 에 `eslint` 를 `9.39.5`, `eslint-config-next` 를 `16.0.10`, `prettier` 를 `3.9.9` 로 정확한 버전(`^` 없이) 더한다. `pnpm install` 로 `web/pnpm-lock.yaml` 을 갱신한다
- `web/eslint.config.mjs` (신규): `eslint/config` 의 `defineConfig`, `globalIgnores` 로 `eslint-config-next/core-web-vitals` 와 `eslint-config-next/typescript` 를 펼치고, `.next/**`, `out/**`, `build/**`, `next-env.d.ts` 를 무시한다. 의도 메모의 규칙 (1)~(4)를 더한다. 규칙마다 한국어 주석으로 까닭을 적는다. 머리 주석에 기준 갱신 방법이 `web/AGENTS.md` 에 있다고 적는다
- 기존 위반을 기준에 둔다

  ```bash
  # cwd: web/
  pnpm exec eslint --suppress-all
  pnpm lint   # 0 으로 끝난다. 경고는 나올 수 있다
  ```

  `web/eslint-suppressions.json` 을 커밋한다. 규칙별 위반 수를 센다. 경고 규칙의 넘은 파일과 함수 목록을 따로 남긴다

### 2. Prettier

- `web/.prettierignore` (신규). Prettier 설정 파일은 두지 않는다(기본값). 둘 까닭이 생기면 회신에 적는다
- `web/scripts/changed-files.mjs` (신규): 의도 메모의 범위로 바뀐 파일 목록을 표준 출력에 한 줄씩 낸다. `origin/main` 이 없으면 까닭을 표준 오류에 내고 2 로 끝난다
- `web/package.json` 의 `scripts` 에 `format:check`(바뀐 파일에 `prettier --check`, 파일이 없으면 성공), `format:changed`(바뀐 파일에 `prettier --write`)를 더한다
- 이 브랜치가 바꾼 web 파일(`package.json`, `eslint.config.mjs` 등)을 `pnpm format:changed` 로 포맷한다. `pnpm-lock.yaml` 은 대상이 아니다

### 3. `test/unit/changed-files.test.ts` (신규)

`node:test` 와 `node:assert/strict` 로 `web/scripts/changed-files.mjs` 를 검사한다. `test/unit/design-tokens.test.ts` 처럼 `import.meta.dirname` 으로 경로를 구한다.

- 저장소 밖 임시 디렉터리에 git 저장소를 만들고 `origin/main` 을 흉내 낸 원격 브랜치를 둔다. 공통 조상 뒤에 바꾼 파일과 새 파일만 목록에 나오고, 바꾸지 않은 파일은 나오지 않는다
- `origin/main` 이 없는 저장소에서는 2 로 끝난다
- 임시 디렉터리는 테스트 끝에 지운다

### 4. 동작을 확인한다

출력은 저장소 밖에 저장한다. 확인에 쓴 변경은 끝에 되돌리고 `git status --short` 에 이 phase 의 변경만 남았는지 본다.

- `web/src/app/api` 의 기준에 없는 라우트 하나에 `NextResponse.json({ code: "X" })` 를 넣어 (1) 로 실패하는지 본다
- `web/src/components` 의 기준에 없는 파일 하나에 `fetch("/x")` 를 넣어 (2) 로 실패하는지 본다
- 기준에 없는 web 파일 하나에 `../` import 를 넣어 (4) 로 실패하는지 본다
- 이 브랜치가 바꾸지 않은 web 파일 하나의 들여쓰기를 흐트러뜨려도 `pnpm format:check` 가 통과하고, 이 브랜치가 바꾼 web 파일에 같은 것을 하면 실패하는지 본다
- `pnpm exec eslint --fix --prune-suppressions` 가 기준 파일을 늘리지 않는지 본다. 기준에 든 위반 하나를 고친 뒤 `pnpm lint` 가 쓰지 않는 기준으로 2 를 내고, 위 명령이 그 줄을 빼는지 본다

### 5. 문서

- `web/AGENTS.md` 에 「lint 와 포맷」 절: 도구와 버전, 의도 메모의 규칙 표(까닭 포함), 기준 파일, 갱신 방법(고치면 `pnpm exec eslint --prune-suppressions`, 새 위반은 고친다, 꼭 받아들여야 하면 `--suppress-rule <규칙>` 과 까닭), Prettier 가 바뀐 파일만 다룬다는 것과 `pnpm format:check`, `pnpm format:changed`, `node --test` 가 읽는 파일의 상대 경로 예외와 그 목록을 갱신하는 방법, 기준에 든 위반은 줄여 갈 목록이고 GitHub 이슈가 있다는 것
- `docs/adr/ADR-041-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md`: 결정에 web 포맷(Prettier, 바뀐 파일만)을 더하고, 「web 에 Prettier 를 더한다」 대안 기각 항목을 지운다. 대신 코디네이터 결정으로 넣었다는 것을 맥락에 한 줄 적는다

## 검증

```bash
# cwd: web/
pnpm lint
pnpm format:check
pnpm typecheck
```

```bash
# cwd: 저장소 root
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
```

`pnpm build` 는 phase 08 의 전체 확인에서 돌린다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/package.json` | 수정 |
| `web/pnpm-lock.yaml` | 수정 |
| `web/eslint.config.mjs` | 신규 |
| `web/eslint-suppressions.json` | 신규 |
| `web/.prettierignore` | 신규 |
| `web/scripts/changed-files.mjs` | 신규 |
| `test/unit/changed-files.test.ts` | 신규 |
| `web/AGENTS.md` | 수정 |
| `docs/adr/ADR-041-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md` | 수정 |
