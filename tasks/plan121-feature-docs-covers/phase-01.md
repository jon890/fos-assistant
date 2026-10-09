# Phase 01. 기능 파일의 covers 경로를 바꾸고 기능 파일을 고치지 않은 PR 에 경고를 내는 검사를 만든다

**Execution profile**: standard

## 목표

`docs/features/<기능>.md` 머리의 `covers:` 줄이 가리키는 코드 경로를 PR 이 바꿨는데 그 기능 파일은 바꾸지 않았으면, CI 가 경고(`::warning`)만 내게 한다. 실패로 막지 않는다.
문서가 코드와 함께 낡지 않게, 리뷰어와 작성자가 「이 기능의 문서를 볼 차례」 라는 것을 PR 화면에서 알게 하려는 것이다.

**범위 외**: 기능 파일에 `covers:` 줄을 실제로 적는 것과, 모든 기능 파일에 그 줄이 있는지 확인하는 저장소 테스트(phase 02). ADR 문장 갱신(phase 02).

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261009-feature-docs.md` 의 결정 4(기능 파일 머리에 다루는 코드 경로를 `covers:` 로 적고, PR 이 그 경로를 바꾸고 기능 파일을 건드리지 않으면 CI 가 경고만 낸다)

- 같은 모양의 본보기
  - 스크립트: `scripts/check-migration-versions.mjs` 는 base 를 인자로 받아 `git` 으로 비교하고, 순수 판정 함수를 export 해 `test/unit/migration-versions.test.ts` 가 테스트한다.
  - CI: `.github/workflows/ci.yml` 의 `unit` job 이 `fetch-depth: 0` 으로 checkout 하고, 「마이그레이션 버전 검사」 단계가 `MIGRATION_BASE: ${{ github.event.pull_request.base.sha || github.event.before || github.sha }}` 를 넘긴다.
- 테스트는 Node 의 TypeScript 실행(`node --test 'test/unit/**/*.test.ts'`)으로 돈다. `.mjs` 를 `.ts` 테스트에서 import 하는 본보기는 `test/unit/file-length.test.ts` 다.

### `covers:` 줄의 모양

기능 파일의 `#` 제목과 첫 `##` 사이에 한 줄로 둔다. 경로는 저장소 root 기준이고 백틱으로 감싸 쉼표로 잇는다. 디렉터리는 끝에 `/` 를 붙인다.

```markdown
# 커넥터 연결

사용자가 커넥터에 계정을 연결하고 그 연결을 에이전트에 붙이는 기능이다.

covers: `backend/src/main/java/com/bifos/assistant/connector/`, `web/src/components/connector/`, `hermes/connectors/`
```

- 백틱 경로라 `test/unit/doc-code-references.test.ts` 가 그 경로가 저장소에 있는지 이미 확인한다(끝이 `/` 면 디렉터리로 찾는다).
- 한 경로를 여러 기능 파일이 가질 수 있다. 그러면 그 경로를 바꾼 PR 은 그 기능 파일들 가운데 하나도 고치지 않았을 때 각각 경고를 받는다.

## 의도 메모

- 경고만 낸다. 기능 문서를 고칠 일이 없는 리팩터링(이름 바꾸기, 포맷)도 그 경로를 바꾸기 때문이다. 막으면 문서에 의미 없는 줄을 고치게 된다.
- 경고는 기능 파일마다 한 줄이다. 바뀐 파일을 모두 나열하면 PR 화면이 길어지므로 처음 셋과 나머지 개수만 적는다.
- base 를 읽지 못하면(얕은 checkout, 첫 push) 실패하지 않고 그 사실만 알리고 0 으로 끝낸다. 경고 검사가 CI 를 깨면 안 된다.
- 기능 파일 자체를 지우거나 이름을 바꾼 PR 은 그 파일을 「고친」 것으로 본다.

## 작업 항목

### 1. `scripts/check-feature-covers.mjs`

- `export function parseCovers(markdown: string): string[]`: 첫 `##` 앞에서 `covers:` 로 시작하는 줄을 찾아 백틱 안의 경로를 순서대로 낸다. 줄이 없으면 빈 배열이다. 코드 펜스 안은 보지 않는다.
- `export function coveredBy(file: string, path: string): boolean`: `path` 가 `/` 로 끝나면 `file.startsWith(path)`, 아니면 `file === path` 다.
- `export function staleFeatures(changed: string[], features: Map<string, string[]>): Array<{ feature: string; files: string[] }>`: 바뀐 파일 가운데 그 기능의 covers 에 드는 것이 있고, 그 기능 파일은 `changed` 에 없는 기능만 낸다. 기능 파일 이름 순서로 낸다.
- 실행: `node scripts/check-feature-covers.mjs <base> [head]`. `git diff --name-only <base>...<head>` 로 바뀐 파일을 얻고(head 기본값 `HEAD`), `docs/features/*.md` 를 읽어 판정한다. 기능마다 `::warning file=<기능 파일>::<문장>` 한 줄을 출력하고, `GITHUB_STEP_SUMMARY` 가 있으면 같은 내용을 덧붙인다. 항상 종료 코드 0 이다. git 이 실패하면 `알림:` 한 줄을 내고 0 으로 끝낸다.
- 경고 문장 예: `이 PR 이 covers 경로의 파일 N개(a, b, c 외 M개)를 바꿨는데 이 기능 파일은 바꾸지 않았다. 흐름이나 갈리는 지점이 바뀌었으면 이 파일도 고친다.`
- 머리 주석에 근거 ADR(`ADR-20261009 / feature-docs`)을 적는다. 400줄 한도(`scripts/` 의 `.mjs`) 안이다.

### 2. `.github/workflows/ci.yml`

`unit` job 의 「마이그레이션 버전 검사」 뒤에 단계를 더한다.

```yaml
      - name: 기능 문서 covers 검사
        if: github.event_name == 'pull_request'
        env:
          FEATURE_BASE: ${{ github.event.pull_request.base.sha }}
        run: node scripts/check-feature-covers.mjs "$FEATURE_BASE"
```

### 3. 이 phase 를 검증하는 테스트 `test/unit/feature-covers.test.ts`

- `parseCovers`: 제목, 한 문장, `covers:` 줄이 있는 입력에서 경로 셋을 순서대로 낸다. `##` 뒤에 있는 `covers:` 와 코드 펜스 안의 `covers:` 는 무시한다. 줄이 없으면 빈 배열이다.
- `coveredBy`: 디렉터리 경로는 그 아래 파일과 맞고 이름이 같은 앞부분만 겹치는 형제(`connector/` 와 `connector-x/`)와는 맞지 않는다. 파일 경로는 같은 파일만 맞는다.
- `staleFeatures`: (1) covers 아래 파일만 바뀌면 그 기능이 나온다. (2) 기능 파일도 함께 바뀌면 나오지 않는다. (3) covers 밖 파일만 바뀌면 나오지 않는다. (4) 두 기능이 같은 경로를 가지면 둘 다 나온다.
- 스크립트를 임시 git 저장소(`mkdtemp` 아래 `git init`)에서 돌려 경고 줄이 나오고 종료 코드가 0 인지 본다. 없는 base 를 주면 `알림:` 과 함께 0 이다.

## 검증

```bash
node --test test/unit/feature-covers.test.ts
node --test 'test/unit/**/*.test.ts'
node scripts/check-feature-covers.mjs origin/main
node scripts/check-file-length.mjs
```

- 첫 두 줄은 종료 코드 0 이다.
- 셋째 줄은 종료 코드 0 이다. 아직 기능 파일에 `covers:` 가 없으므로 경고가 없다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `scripts/check-feature-covers.mjs` | 신규 |
| `test/unit/feature-covers.test.ts` | 신규 |
| `.github/workflows/ci.yml` | 수정 |
