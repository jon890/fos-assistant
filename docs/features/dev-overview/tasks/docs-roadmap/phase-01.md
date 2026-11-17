# Phase 01. 이미지에 담긴 개발 문서를 읽는 서버 모듈

**Execution profile**: deep

## 목표

`DEV_DOCS_ROOT` 아래 문서 폴더를 읽어 묶음 넷의 목록, 경로 대조, 링크 풀기, 로드맵 절 자르기, 커밋 값을 내는 서버 전용 모듈을 만든다. 화면(phase 2, 3)은 이 모듈만 부른다.

**범위 외**: 화면과 부품(phase 2), mermaid(phase 3), Dockerfile 과 문서 정리(phase 4).

## 컨텍스트

**요구사항**: REQ2(커밋 값과 GitHub 주소), REQ3(목록과 찾기 규칙), REQ4(링크 풀기), REQ6(로드맵 절), REQ9(목록에 든 파일만 연다)

**근거 문서**: `docs/features/dev-overview/spec.md`, `docs/features/dev-overview/design.md` 의 「인터페이스와 데이터」, `web/docs/adr/ADR-20261117-dev-docs-image.md`

- 실행 중 환경 변수를 읽는 패턴은 `web/src/lib/app-name.ts` 를 따른다. `NEXT_PUBLIC_` 이름을 쓰지 않는다.
- `web/src` 에서 `node:fs` 를 쓰는 첫 모듈이다. 화면 부품(`"use client"`)에서 import 하지 않는다. 서버 컴포넌트와 이 모듈의 테스트만 쓴다.
- `node --test` 는 `@/` 경로 별칭을 풀지 못한다. `web/src/lib/dev-docs/*` 끼리는 `web/src/lib/delegation.ts` 처럼 상대 경로에 `.ts` 확장자를 붙여 import 한다. `filter.ts` 와 `links.ts` 는 `catalog.ts` 에서 `import type` 만 쓴다.
- 단위 테스트 모양은 `web/test/unit/app-name.test.ts` 처럼 `node:test` 와 `node:assert/strict` 다. `pnpm test:node` 가 `web/test/unit/**/*.test.ts` 를 돈다.

## 의도 메모

- 문서 위치는 `DEV_DOCS_ROOT` 로만 정한다. `process.cwd()` 나 `import.meta` 위치로 짐작하지 않는다. standalone 서버가 시작할 때 작업 폴더를 바꾸고, 동적 경로가 Next.js 파일 추적을 넓혀 standalone 결과가 커진다(설계 「문서를 담는 자리」).
- 본문은 메모리에 두지 않는다. 목록(경로, 이름, 묶음, 상태)만 프로세스마다 한 번 만든다. `NODE_ENV !== "production"` 이면 매번 만든다.
- 경로는 퍼센트 인코딩을 풀고 POSIX 정규화한 뒤 목록 키와 대조한다. 목록에 없으면 열지 않는다. `..`, 절대 경로, 인코딩된 `%2e%2e` 도 목록에 없으므로 같은 결과다.

## 작업 항목

### 1. `web/src/lib/dev-docs/source.ts`

- `devDocsRoot(): string | null`: `DEV_DOCS_ROOT` 를 trim 해 돌려준다. 비었으면 `null`.
- `sourceCommit(): string | null`: `SOURCE_COMMIT` 이 `/^[0-9a-f]{40}$/` 면 그 값, 아니면 `null`.
- `REPO_URL = "https://github.com/jon890/fos-assistant"`.
- `githubFileUrl(repoPath: string, commit: string | null, hash?: string)`: `<REPO_URL>/blob/<commit ?? "main">/<경로를 segment 마다 encodeURIComponent>` 에 `#hash`.
- `githubCommitUrl(commit: string)`.

### 2. `web/src/lib/dev-docs/catalog.ts`

타입:

```ts
type DocGroupKey = "product" | "features" | "decisions" | "rules";
type DocEntry = { path: string; title: string; fileStem: string; status?: string; children?: DocEntry[] };
type DocSection = { label: string; entries: DocEntry[]; archived?: DocEntry[] }; // 「결정」 의 나눔(공통, backend, web, hermes)
type DocGroup = { key: DocGroupKey; label: string; sections: DocSection[] };
type Catalog = { groups: DocGroup[]; paths: Set<string> };
```

- `buildCatalog(root: string): Promise<Catalog>`: 설계의 「목록」 표대로 만든다.
  - 제품: `docs/prd.md`, `docs/code-architecture.md`, `backend/docs/code-architecture.md`, `web/docs/code-architecture.md`, `hermes/docs/code-architecture.md`, `backend/docs/data-schema.md`, `hermes/docs/hermes-contract.md` 가운데 있는 것.
  - 기능: `docs/features/*.md` 를 이름 순. 같은 이름 폴더에 `spec.md`, `design.md` 가 있으면 `children`. `tasks/` 는 읽지 않는다.
  - 결정: 나눔 `공통 docs/adr`, `backend`, `web`, `hermes`. 나눔의 첫 줄은 그 `INDEX.md`. 그 아래는 INDEX 의 표 줄 가운데 첫 칸 링크 대상의 파일 이름(basename)이 `ADR-` 로 시작하고 `.md` 로 끝나는 줄을 순서대로 읽는다. 보관 표의 `archive/ADR-...md` 도 같은 판정으로 받고, 경로는 INDEX 의 폴더 기준으로 푼다. `## 보관` 제목 뒤의 표 줄은 `archived`. 이름은 제목 칸, `status` 는 마지막 칸에서 링크와 백틱을 벗긴 뒤 첫 `.` 앞 글자. 링크 대상 파일이 없으면 그 줄을 빼고 `console.warn` 한 줄.
  - 코드 규칙: `docs/code-rules/INDEX.md` 다음 `rule-N.md` 를 번호 순.
  - 이름은 문서의 첫 `# ` 줄(앞의 `## ` 형식 ADR 제목은 `## ADR-...: ` 머리를 벗긴 제목). 제목이 없으면 파일 이름.
- `getCatalog(): Promise<Catalog | null>`: `devDocsRoot()` 가 없거나 `docs/` 가 없으면 `null`. production 에서는 첫 결과를 모듈 변수에 둔다.
- `filterCatalog(catalog, query)`: 브라우저 부품도 쓸 수 있게 `fs` 를 import 하지 않는 `web/src/lib/dev-docs/filter.ts` 에 둔다. 맞춤 대상은 `title` 과 `fileStem`(확장자 뺀 파일 이름). 기능의 `children`(스펙, 설계)은 `title` 만 맞춘다. 자식만 맞으면 그 기능 줄을 `expanded: true` 로 남긴다. 대소문자를 가리지 않는다. 빈 query 는 그대로.

### 3. `web/src/lib/dev-docs/read.ts`

- `readDoc(repoPath: string): Promise<{ path: string; markdown: string } | null>`: `normalizeRepoPath` 로 푼 경로가 `catalog.paths` 에 없으면 `null`. 있으면 `<root>/<path>` 를 utf8 로 읽는다. 읽기 실패는 던진다(화면이 「불러오지 못했어요」 로 바꾼다).
- `normalizeRepoPath(raw: string | string[]): string | null`: 배열이면 `/` 로 잇고, `decodeURIComponent` 실패면 `null`, `path.posix.normalize`, 앞 `/` 나 `..` 로 시작하면 `null`.
- `readRoadmap(): Promise<string | null>`: `docs/prd.md` 의 `## 로드맵` 줄부터 다음 `## ` 줄 앞까지. 코드 펜스 안의 `## ` 은 경계로 보지 않는다. 절이 없으면 `null`.

### 4. `web/src/lib/dev-docs/links.ts` (fs 없음, 브라우저 부품도 import)

- `resolveDocLink(href: string, fromPath: string, known: ReadonlySet<string>, commit: string | null)`: 아래를 돌려준다.
  - `{ kind: "anchor", hash }`: `#절` 만.
  - `{ kind: "internal", href: "/dev-docs/<경로>#절" }`: 상대 링크를 `fromPath` 의 폴더 기준으로 풀어(인코딩 해제, 정규화) `known` 에 있을 때.
  - `{ kind: "github", href }`: 저장소 안이지만 `known` 밖(코드, README, 그림 포함). `githubFileUrl`.
  - `{ kind: "external", href }`: `http:`, `https:`, `mailto:`.
  - 저장소 root 밖으로 나가면 `{ kind: "github", href: REPO_URL }`.
  - `javascript:` 같은 다른 scheme 은 `{ kind: "blocked" }`.
- 이 파일이 `source.ts` 의 `REPO_URL`, `githubFileUrl` 을 쓰므로 `source.ts` 는 `fs` 를 import 하지 않게 둔다.

### 5. 단위 테스트

- `web/test/unit/dev-docs-catalog.test.ts`: `mkdtemp` 로 가짜 트리를 만든다(prd, 기능 둘 중 하나만 폴더와 `tasks/x/phase-01.md` 포함, 루트 INDEX 네 칸 표와 보관 표, backend INDEX 세 칸 표와 한글 파일 이름 ADR, code-rules INDEX 와 rule-2, rule-10). 묶음 순서, 기능 children 과 `tasks` 제외, INDEX 순서와 상태 첫 문장, 보관 나눔, rule 번호 순, 없는 링크의 줄 빠짐을 본다. `filterCatalog` 의 제목, 파일 이름, 자식만 맞을 때 펼침, `spec` 으로 찾으면 기능 줄이 펼쳐지지 않는 것.
- `web/test/unit/dev-docs-read.test.ts`: `DEV_DOCS_ROOT` 를 가짜 트리로 두고 목록 안 경로 읽기, 목록 밖, `../x`, `/etc/passwd`, `%2e%2e/x`, 인코딩된 한글 ADR 경로가 열리는 것, 로드맵 절 자르기(펜스 안 `##` 무시)와 절이 없을 때 `null`. 환경 변수는 `app-name.test.ts` 처럼 끝에 되돌린다.
- `web/test/unit/dev-docs-links.test.ts`: 목록 안 상대 링크와 `#한글절`, 인코딩된 한글 ADR 링크, 목록 밖 `.ts` 파일과 `README.md`, `../../x.png` 그림, root 밖, `https:`, `javascript:`, 커밋이 있을 때와 없을 때의 GitHub 주소.
- `web/test/unit/dev-docs-source.test.ts`: `SOURCE_COMMIT` 40자리, 짧은 값, 대문자나 공백이 섞인 값.

## 검증

```bash
cd web && pnpm typecheck
cd web && pnpm lint
cd web && pnpm format:check
cd web && node --test test/unit/dev-docs-catalog.test.ts test/unit/dev-docs-read.test.ts test/unit/dev-docs-links.test.ts test/unit/dev-docs-source.test.ts
```

모두 종료 코드 0.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/lib/dev-docs/source.ts` | 신규 |
| `web/src/lib/dev-docs/catalog.ts` | 신규 |
| `web/src/lib/dev-docs/filter.ts` | 신규 |
| `web/src/lib/dev-docs/read.ts` | 신규 |
| `web/src/lib/dev-docs/links.ts` | 신규 |
| `web/test/unit/dev-docs-tree.ts` | 신규 (단위 테스트의 가짜 문서 트리) |
| `web/test/unit/dev-docs-catalog.test.ts` | 신규 |
| `web/test/unit/dev-docs-read.test.ts` | 신규 |
| `web/test/unit/dev-docs-links.test.ts` | 신규 |
| `web/test/unit/dev-docs-source.test.ts` | 신규 |
