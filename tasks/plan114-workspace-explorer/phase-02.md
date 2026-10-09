# Phase 02. 사이드바 「고급」 과 파일 공간 화면에서 보고 내려받는다

**Execution profile**: deep

## 목표

사이드바에 「고급」 묶음을 두고 그 아래 「파일 공간」 과 「내 브라우저」 를 둔다.
`/files` 화면에서 phase 01 의 세 경로로 목록을 보고, 글과 CSV 표와 사진과 HTML 을 미리 보고, 내려받는다.

**범위 외**: 지우기 단추와 확인 창(phase 04), 관리자 화면(phase 04), backend(phase 01, phase 03).

## 컨텍스트

**근거 문서**: 이 phase 의 첫 작업이 적용하는 `docs/frontend/structure.md` 의 「파일 공간」 과 화면 표의 `/files`, `/browser` 줄, `docs/frontend/shell.md` 의 「고급」 묶음과 아이콘 표.
API 와 머리글은 `docs/code-architecture.md` 의 「실행 공간 파일」, 흐름은 `docs/flow.md` 의 「파일 공간을 열 때」 가 갖는다.

따를 기존 패턴:

- 서버 라우트: `web/src/app/api/browser/route.ts`(`callControlPlane`, `errorResponse`), 본문을 그대로 옮기는 라우트는 `web/src/app/api/chat/conversations/[conversationId]/files/[...path]/route.ts`(`forwardControlPlane`, 옮길 머리글 목록, 빈 조각과 `..` 의 400).
- 화면: `web/src/app/browser/page.tsx` 는 서버에서 세션만 보고 상태는 브라우저에서 읽는다.
- 옆 패널과 시트: `web/src/components/chat/artifact/artifact-panel.tsx` 의 `useMediaQuery("(min-width: 1024px)")`, `Sheet`, `FRAME_SANDBOX`, 흰 바탕 iframe 의 주석.
- 메뉴: `web/src/components/shell/main-nav.tsx` 의 `LINKS` 와 `NavPending`.
- 브라우저 검사: `test/browser/user-browser.spec.ts` 가 `page.route` 로 `/api/browser` 를 가짜로 답한다. 두 폭(`mobile`, `desktop`)에서 돈다.
- 순수 함수 검사: `test/unit/*.test.ts` 는 `node --test` 로 web 파일을 직접 읽는다. 그 파일은 상대 경로 import 를 쓰고 `web/eslint.config.mjs` 의 `NODE_TEST_READ_FILES` 에 든다(`web/AGENTS.md` 「상대 경로 import 예외」).

`test/browser/shell.spec.ts` 의 「사이드바는 일반 화면에 있고 로그인 화면에는 없다」 는 주요 화면 메뉴의 링크를 정확히 다섯으로 단언한다. 이 phase 가 링크를 더하므로 그 단언을 고친다.

## 의도 메모

- 「고급」 은 링크가 아니라 묶음의 이름이다. `role="group"` 과 `aria-labelledby` 로 묶는다. 접고 펴지 않는다.
- `/connections` 위쪽의 「내 브라우저」 링크는 지우지 않는다. 진행 중인 사용성 묶음(#341 F)이 그 링크에 안내를 더한다.
- 미리보기 종류는 확장자와 목록의 `size` 로 화면이 먼저 정한다. 상한을 넘으면 요청하지 않는다. 서버도 같은 상한으로 413 을 낸다.
- HTML 은 결과물 패널과 같은 `sandbox` 값을 쓴다. 값을 새로 정하지 않고 `FRAME_SANDBOX` 를 내보내 함께 쓴다.
- CSV 는 직접 짠 작은 파서로 읽는다. 새 의존을 넣지 않는다. RFC 4180 의 따옴표와 이중 따옴표, 따옴표 안 줄바꿈만 다룬다.
- 글 본문은 `<pre>` 의 글자로만 그린다. 마크다운으로 그리지 않는다.
- 목록의 링크는 `prefetch={false}` 다(`web/AGENTS.md`). 디렉터리 이동과 파일 선택은 주소의 `?path=` 와 `?file=` 을 바꾼다. 뒤로 가기가 그대로 돈다.
- 기각: 목록을 서버 컴포넌트에서 읽기. 실패와 다시 읽기를 브라우저에서 다루는 기존 화면(`/browser`)과 맞춘다.

## 작업 항목

### 1. 화면 문서 적용

```bash
git apply tasks/plan114-workspace-explorer/docs-stage2.patch
```

`docs/frontend/structure.md` 와 `docs/frontend/shell.md` 가 바뀐다. 이 phase 의 구현은 그 문서와 같아야 한다.

### 2. 서버 라우트 셋

- `web/src/app/api/workspace/route.ts`: `GET` 이 `callControlPlane("/api/v1/workspace")` 를 옮긴다.
- `web/src/app/api/workspace/entries/route.ts`: `GET` 이 요청의 `path` 인자를 `encodeURIComponent` 로 싸서 `/api/v1/workspace/entries?path=` 로 옮긴다. 인자가 없으면 붙이지 않는다.
- `web/src/app/api/workspace/files/[...path]/route.ts`: 결과물 파일 라우트를 본뜬다. 빈 조각과 `..` 는 400 `VALIDATION_FAILED`. 조각마다 `encodeURIComponent` 해서 `/api/v1/workspace/files/...` 로 `forwardControlPlane` 한다. 요청의 `download=1` 만 옮긴다.
  옮길 응답 머리글은 `content-type`, `content-disposition`, `content-security-policy`, `x-content-type-options`, `cache-control`, `content-length` 여섯뿐이다. 실패 응답은 JSON 오류로 옮긴다.

### 3. `web/src/lib/workspace-file.ts` 신규(순수 함수, 상대 경로 import)

```ts
export type WorkspaceEntryKind = "DIRECTORY" | "FILE" | "LINK" | "OTHER";
export type PreviewKind = "text" | "table" | "image" | "html" | "none";
export function previewKind(name: string, size: number | null): PreviewKind;
export function fileUrl(path: string, download?: boolean): string; // `/api/workspace/files/<조각마다 인코딩>`
export function joinPath(dir: string, name: string): string;
export function crumbs(path: string): { name: string; path: string }[]; // 맨 앞은 { name: "파일 공간", path: "" }
export function parseDelimited(text: string, delimiter: "," | "\t", maxRows: number): { rows: string[][]; truncated: boolean };
export function formatSize(bytes: number): string; // "512 B", "1.5 KB", "3.2 MB"
```

`previewKind` 의 확장자 목록과 상한은 `docs/code-architecture.md` 「본문 머리글」 첫 표와 같다. `csv` 는 `table`, `tsv` 도 `table`, `svg` 는 `text` 다.

### 4. `web/src/lib/workspace-api.ts` 신규

응답 타입(`WorkspaceStatus`, `WorkspaceListing`, `WorkspaceEntry`)과 `fetchWorkspaceStatus()`, `fetchWorkspaceEntries(path)` 를 둔다. `browser-api.ts` 처럼 부르는 쪽이 실패를 문구로 바꾼다.

### 5. 화면 부품 `web/src/components/workspace/`

- `workspace-explorer.tsx`(클라이언트): `useSearchParams` 의 `path`, `file` 로 상태를 정한다. 상태와 목록을 읽고 머리, 경로 줄, 목록, 미리보기를 그린다. 상태 표(`available` 거짓, 비었음, 404, 그 밖의 실패)는 `docs/frontend/structure.md` 「파일 공간」 의 문구를 쓴다. 문구는 해요체다.
- `workspace-entry-list.tsx`: 목록 표. 줄마다 `lucide-react` 아이콘(`Folder`, `File`, `Link2`, `FileQuestion`), 이름, 크기(`formatSize`), 바뀐 시각, 「내려받기」 링크(`fileUrl(path, true)`). `readable` 이 거짓이면 「읽을 수 없음」, `LINK` 는 「링크」 를 붙이고 누르지 못한다. `truncated` 면 끝에 「1,000개까지만 보여요」.
- `workspace-preview.tsx`: 넓은 화면은 목록 옆 `aside`, 좁은 화면은 `Sheet`. 머리에 이름, 「내려받기」, 「닫기」. 본문은 `previewKind` 로 고른다. 글은 `fetch(fileUrl(path))` 로 받아 NUL 이 있으면 「글 파일이 아니에요」. 표는 `parseDelimited(text, ..., 1000)` 을 `csv-table.tsx` 로 그린다. 사진은 `<img>`, HTML 은 `sandbox={FRAME_SANDBOX}` 의 iframe 에 흰 바탕(결과물 패널과 같은 주석을 단다). `none` 은 「미리보기가 없어요」 와 내려받기.
- `csv-table.tsx`: `components/ui/table.tsx` 로 그린다. 첫 줄이 머리다.
- `web/src/components/chat/artifact/artifact-panel.tsx` 의 `FRAME_SANDBOX` 를 `export` 한다. 값은 바꾸지 않는다.

### 6. `web/src/app/files/page.tsx` 신규

`/browser` 와 같이 세션이 없으면 `/signin` 으로 보낸다. `metadata.title` 은 「파일 공간」. 제목 「파일 공간」 아래에 `WorkspaceExplorer` 를 `Suspense` 로 감싸 둔다(`useSearchParams`). 폭은 미리보기 패널이 붙도록 `max-w-6xl` 로 둔다.

### 7. `web/src/components/shell/main-nav.tsx`

`LINKS` 뒤에 「고급」 묶음을 그린다. 묶음 제목은 작은 글자의 `text-muted-foreground` 이고, 링크 둘은 기존 링크와 같은 모양에 들여쓰기 한 칸이다.

```ts
const ADVANCED_LINKS = [
  { href: "/files", label: "파일 공간", icon: FolderOpen },
  { href: "/browser", label: "내 브라우저", icon: Globe },
] as const;
```

### 8. `web/eslint.config.mjs`

`NODE_TEST_READ_FILES` 에 `src/lib/workspace-file.ts` 를 더한다. 목록의 경로 모양은 그 파일의 기존 줄을 따른다.

### 9. 검사

`test/unit/workspace-file.test.ts`

- `previewKind`: `a.csv`, `a.TSV` 는 `table`, `a.html` 은 `html`, `a.svg` 와 `Makefile` 과 `.env` 는 `text`, `a.png` 는 `image`, `a.bin` 은 `none`, 1 MiB 를 넘는 `a.txt` 와 20 MiB 를 넘는 `a.png` 는 `none`.
- `fileUrl("보고서/1월 결과.csv", true)` 가 조각마다 인코딩되고 `?download=1` 로 끝난다.
- `crumbs("a/b")` 가 세 조각이다.
- `parseDelimited`: `"a,\"b,c\"\n1,\"x\ny\"\n"` 가 두 줄 두 칸이고 따옴표 안 쉼표와 줄바꿈을 지킨다. `""` 가 `"` 하나가 된다. `maxRows` 를 넘으면 `truncated`.

`test/browser/workspace-files.spec.ts`(`page.route` 로 `/api/workspace`, `/api/workspace/entries`, `/api/workspace/files/**` 를 가짜로 답한다)

- 사이드바의 「고급」 묶음에서 「파일 공간」 을 누르면 `/files` 로 가고, 머리에 함께 쓰는 에이전트 이름과 그룹 공개 안내가 보인다.
- 디렉터리를 누르면 경로 줄과 목록이 바뀌고, 경로 줄의 「파일 공간」 으로 돌아온다.
- `a.txt` 를 누르면 미리보기에 글이 보이고, `b.csv` 는 표의 머리와 칸이 보이고, `c.html` 은 `sandbox` 가 `FRAME_SANDBOX` 값인 iframe 이다. `d.bin` 은 「미리보기가 없어요」.
- 「내려받기」 링크의 `href` 가 `download=1` 로 끝난다.
- 링크 줄은 「링크」 표시가 있고 눌러도 미리보기가 열리지 않는다.
- `available: false` 면 「파일 공간을 쓸 수 없어요. 관리자에게 알려 주세요.」, 목록 404 면 「찾을 수 없어요. 지워졌을 수 있어요.」 와 맨 위로 가기.
- 지우기 단추가 없다(phase 04 전).

`test/browser/shell.spec.ts`: 주요 화면 메뉴 단언을 `[/^에이전트/, /^외부 서비스 연결/, /^예약 작업/, /^기억/, /^사용량/, /^파일 공간/, /^내 브라우저/]` 로 바꾸고, 「고급」 묶음이 `group` 역할로 보이는지 한 줄 더한다.

## 검증

```bash
node --test test/unit/workspace-file.test.ts
cd web && pnpm lint && pnpm format:check && pnpm typecheck
cd web && pnpm test:browser workspace-files.spec.ts shell.spec.ts --repeat-each=3 --retries=0
scripts/check-local.sh workspace-files.spec.ts shell.spec.ts
grep -rn 'style={{' web/src/components/workspace/
```

- 첫 넷이 통과하고 브라우저 검사는 `mobile` 과 `desktop` 두 폭에서 3회씩 통과한다.
- 마지막 줄은 아무것도 내지 않는다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `docs/frontend/structure.md` | 수정 |
| `docs/frontend/shell.md` | 수정 |
| `web/src/app/api/workspace/route.ts` | 신규 |
| `web/src/app/api/workspace/entries/route.ts` | 신규 |
| `web/src/app/api/workspace/files/[...path]/route.ts` | 신규 |
| `web/src/lib/workspace-file.ts` | 신규 |
| `web/src/lib/workspace-api.ts` | 신규 |
| `web/src/components/workspace/workspace-explorer.tsx` | 신규 |
| `web/src/components/workspace/workspace-entry-list.tsx` | 신규 |
| `web/src/components/workspace/workspace-preview.tsx` | 신규 |
| `web/src/components/workspace/csv-table.tsx` | 신규 |
| `web/src/components/chat/artifact/artifact-panel.tsx` | 수정 |
| `web/src/app/files/page.tsx` | 신규 |
| `web/src/components/shell/main-nav.tsx` | 수정 |
| `web/eslint.config.mjs` | 수정 |
| `test/unit/workspace-file.test.ts` | 신규 |
| `test/browser/workspace-files.spec.ts` | 신규 |
| `test/browser/shell.spec.ts` | 수정 |
