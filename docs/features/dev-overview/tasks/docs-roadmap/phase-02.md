# Phase 02. 「개발 문서」 화면과 목록, 본문

**Execution profile**: standard

## 목표

로그인한 사용자가 사이드바 「고급」 의 「개발 문서」 로 `/dev-docs` 를 열어 로드맵과 문서 목록을 보고, 문서를 읽기 전용 Markdown 으로 읽는다.

**범위 외**: mermaid 블록은 이 phase 에서 일반 코드 블록으로 보인다(phase 3). Dockerfile, 브라우저 테스트, 문서 정리(phase 4).

## 컨텍스트

**요구사항**: REQ1, REQ2, REQ3, REQ4, REQ6

**근거 문서**: `docs/features/dev-overview/spec.md` 의 REQ1~REQ6, DECISION11~DECISION14, `docs/features/dev-overview/design.md` 의 「책임과 경계」, 「주소와 링크」, 「로드맵」

- 서버 페이지의 로그인 확인은 `web/src/app/files/page.tsx` 를 따른다(`auth()` 가 없으면 `redirect("/signin")`). 역할은 보지 않는다.
- 사이드바 링크는 `web/src/components/shell/main-nav.tsx` 의 `ADVANCED_LINKS` 다. 아이콘은 `lucide-react` 의 `BookOpen`.
- Markdown 의 표, 목록, 코드 블록 class 는 `web/src/components/chat/markdown.tsx` 와 같게 둔다. 그 파일은 고치지 않는다(설계 DECISION3).
- 문서 목록과 본문 안 링크는 수가 정해지지 않은 목록이라 `next/link` 에 `prefetch={false}` 를 준다([rule-61](../../../../code-rules/rule-61.md)).
- 동적 라우트는 이 저장소처럼 `params: Promise<{ ... }>` 를 받아 `await params` 로 꺼낸다(`web/src/app/agents/[code]/skills/[name]/page.tsx`).
- 안내 상자는 `@/components/ui/notice` 의 `Notice` 다.
- 화면 문구는 해요체다([rule-58](../../../../code-rules/rule-58.md)). Markdown 은 react-markdown 의 기본값(HTML 을 그리지 않음, 기본 `urlTransform`)을 바꾸지 않는다([rule-57](../../../../code-rules/rule-57.md)).

## 의도 메모

- 목록과 본문은 서버 컴포넌트에서 phase 1 모듈로 읽는다. 브라우저가 부르는 API 경로를 만들지 않는다(설계 「책임과 경계」).
- 찾기는 목록 부품 안의 상태로 브라우저에서 맞는 줄만 남긴다. 서버 목록을 props 로 한 번 넘긴다.
- 제목 id 는 `github-slugger` 를 쓰고 문서 하나를 그릴 때마다 새 `GithubSlugger` 로 시작한다. 다른 문서의 `#한글절` 로 오면 브라우저에서 `decodeURIComponent(location.hash)` 로 id 를 찾아 내린다.

## 작업 항목

### 1. 의존성

`web/` 에서 `pnpm add github-slugger` 로 `package.json` 과 `pnpm-lock.yaml` 을 함께 고친다.

### 2. `web/src/app/dev-docs/page.tsx`, `web/src/app/dev-docs/[...path]/page.tsx`

- 둘 다 `auth()` 로 로그인을 확인한다. `metadata.title` 은 「개발 문서」.
- `getCatalog()` 가 `null` 이면 「개발 문서를 담지 않고 띄운 서버예요」 를 `<Notice variant="warning">` 로 보인다.
- 머리: 제목 「개발 문서」, 커밋 표시(`sourceCommit()` 이 있으면 앞 7자리 링크 → `githubCommitUrl`, 없으면 「커밋 정보 없음」). 고치는 단추는 없다.
- 본문 영역 옆(넓은 화면) 또는 위(좁은 화면)에 `DevDocsCatalog`.
- `/dev-docs`: `readRoadmap()` 을 `DevDocMarkdown` 으로 그린다(`fromPath` 는 `docs/prd.md`). `null` 이면 「로드맵이 아직 없어요」 를 `<Notice variant="neutral">` 로.
- `/dev-docs/[...path]`: props 는 `{ params: Promise<{ path: string[] }> }` 이고 `const { path } = await params` 로 꺼낸다. `readDoc(path)` 가 `null` 이면 `notFound()`. 읽기에서 던지면 「문서를 불러오지 못했어요」 를 `<Notice variant="error" role="alert">` 로. 문서 위에 「GitHub 에서 보기」 링크(`githubFileUrl(path, commit)`, 새 탭).

### 3. `web/src/components/dev-docs/catalog.tsx` (`"use client"`)

- props: `groups`(phase 1 의 `DocGroup[]`), `currentPath?: string`.
- 찾기 칸(`aria-label="문서 찾기"`, placeholder 「문서 이름」)과 `filterCatalog` 결과를 묶음 제목(제품, 기능, 결정, 코드 규칙), 결정의 나눔(공통, backend, web, hermes)과 「보관」, 기능 줄의 펼침(`<details>`)으로 그린다. 결정 줄 옆에 `status` 를 작은 글로.
- 링크는 `next/link` 로 `/dev-docs/<path>`, `prefetch={false}`. 지금 문서는 `aria-current="page"`.
- 찾기 결과가 없으면 「맞는 문서가 없어요」.

### 4. `web/src/components/dev-docs/markdown.tsx` (`"use client"`)

- props: `markdown`, `fromPath`, `known: string[]`, `commit: string | null`.
- `ReactMarkdown` + `remarkGfm`. `components`:
  - `a`: `resolveDocLink` 결과대로. `internal` 은 `next/link`(`prefetch={false}`), `anchor` 는 같은 문서 `#`, `github` 과 `external` 은 `target="_blank" rel="noopener noreferrer"`, `blocked` 는 링크 없이 글자만.
  - `img`: 그리지 않고 「그림: <alt>」 링크로 바꾼다(주소는 `resolveDocLink` 결과).
  - `h1`~`h6`: 문서마다 새 `GithubSlugger` 의 `slug(제목 글자)` 를 `id` 로.
  - 표, 목록, 문단, 코드 블록은 채팅 `Markdown` 과 같은 class. 코드 블록은 `<pre><code>` 로 두고 문법 강조는 하지 않는다.
- 첫 그리기 뒤 `location.hash` 가 있으면 풀어서 그 id 로 `scrollIntoView`.

### 5. `web/src/lib/dev-docs/headings.ts` 와 단위 테스트

- `createHeadingIds()`: 문서 하나에 쓸 함수를 돌려준다. 호출마다 새 `GithubSlugger` 로 `(text: string) => string` 을 만든다. `markdown.tsx` 의 제목 부품은 이 함수를 쓴다(react 노드에서 글자를 모으는 `headingText(children)` 도 여기 둔다).
- `web/test/unit/dev-docs-headings.test.ts`: 「사람이 도와야 하는 지점」 같은 한글 제목이 GitHub 와 같은 slug(`사람이-도와야-하는-지점`)가 되고, 같은 제목 두 번째는 `-1` 이 붙으며, 새로 만든 함수는 다시 `-1` 없이 시작한다.

### 6. `web/src/components/shell/main-nav.tsx`

- `ADVANCED_LINKS` 에 `{ href: "/dev-docs", label: "개발 문서", icon: BookOpen }` 을 끝에 더한다.
- 위 주석의 「고급」 정의를 「매일 쓰지 않는 일반 화면」 으로 고친다(스펙 DECISION11).

## 검증

```bash
cd web && pnpm typecheck
cd web && pnpm lint
cd web && pnpm format:check
cd web && node --test test/unit/dev-docs-headings.test.ts
cd web && pnpm test:node
cd web && AUTH_SECRET=build-time-placeholder ASSISTANT_JWT_SECRET=build-time-placeholder CONTROL_PLANE_BASE_URL=http://build-time-placeholder AUTH_GOOGLE_ID=build-time-placeholder AUTH_GOOGLE_SECRET=build-time-placeholder pnpm build 2>&1 | tee "$TMPDIR/dev-docs-build.log"
cd web && ! grep -qi "tracing of the whole project" "$TMPDIR/dev-docs-build.log"
cd web && du -sk .next/standalone
```

모두 종료 코드 0. 마지막 값(KB)을 phase 시작 전 같은 명령으로 잰 값과 비교해 문서나 web 소스 폴더 크기만큼(수 MB) 늘지 않았는지 커밋 메시지 본문에 적는다. 시작 전 값은 이 phase 의 첫 작업 전에 같은 빌드 명령과 `du -sk .next/standalone` 으로 잰다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/package.json` | 수정 (github-slugger) |
| `web/pnpm-lock.yaml` | 수정 |
| `web/src/app/dev-docs/page.tsx` | 신규 |
| `web/src/app/dev-docs/[...path]/page.tsx` | 신규 |
| `web/src/components/dev-docs/catalog.tsx` | 신규 |
| `web/src/components/dev-docs/markdown.tsx` | 신규 |
| `web/src/components/dev-docs/shell.tsx` | 신규 (머리의 커밋과 목록, 본문 자리) |
| `web/src/lib/dev-docs/catalog.ts` | 수정 (문서 경로에 `turbopackIgnore`, 빌드 결과가 web 폴더 전체를 담지 않게) |
| `web/src/lib/dev-docs/read.ts` | 수정 (같은 경로 함수) |
| `web/src/components/shell/main-nav.tsx` | 수정 (「개발 문서」 링크, 「고급」 주석) |
| `web/src/lib/dev-docs/headings.ts` | 신규 |
| `web/test/unit/dev-docs-headings.test.ts` | 신규 |
