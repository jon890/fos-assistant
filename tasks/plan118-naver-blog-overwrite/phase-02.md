# Phase 02. 편집기 오류 타입을 page.ts 밖으로 옮긴다

**Execution profile**: fast

## 목표

`src/editor/page.ts` 의 `EditorErrorCode` 타입과 `EditorError` 클래스를 새 파일 `src/editor/editor-error.ts` 로 옮긴다. 동작은 바꾸지 않는다.
`page.ts` 는 파일 길이 기준값(511줄)에 묶여 있어, phase 04 가 오류 코드를 더하려면 그 자리를 먼저 비워야 한다.

**범위 외**: 오류 코드를 더하는 것(phase 04).

## 컨텍스트

코드는 `hermes/connectors/naver-blog/` 에 있다. `bun` 이 PATH 에 있어야 한다(없으면 `export PATH="$HOME/.bun/bin:$PATH"`).

**근거 문서**: `hermes/docs/adr/ADR-20261009-naver-blog-overwrite.md` 의 「결정」(덮어쓰기 오류 코드가 편집기 오류에 더해진다), 저장소 루트 `AGENTS.md` 의 「머지는 PR 로 한다」(이동 커밋과 변경 커밋을 나눈다).

`src/editor/page.ts` 의 앞부분에 `export type EditorErrorCode = | "login_required" | … | "save_unconfirmed";` 와 `export class EditorError extends Error { constructor(readonly code: EditorErrorCode, readonly stage: string, message: string, readonly extra: Record<string, unknown> = {}) … }` 가 있다.
`page.ts` 안의 `EditorPage.fail` 과 `openWriteTab` 이 `EditorError` 를 쓴다.
다른 파일은 `./page.ts` 나 `../editor/page.ts`, `./editor/run.ts`(재수출) 에서 가져온다.

파일 길이 기준은 `scripts/file-length-baseline.json` 이 갖는다. 줄이 줄면 `node scripts/check-file-length.mjs` 가 기준값을 낮추라는 알림을 낸다. 알림을 따라 `node scripts/check-file-length.mjs --update` 로 기준값을 낮추고 그 파일도 커밋한다.

## 의도 메모

- 이동만 한다. 저장소 규칙이 리팩터링의 이동 커밋과 동작 변경 커밋을 나누게 한다
- `page.ts` 에서 `export { EditorError, type EditorErrorCode } from "./editor-error.ts";` 로 다시 내보내 기존 import 를 고치지 않는다

## 작업 항목

### 1. `src/editor/editor-error.ts` 신규

`page.ts` 의 `EditorErrorCode` 와 `EditorError` 를 주석까지 그대로 옮긴다.

### 2. `src/editor/page.ts` 수정

옮긴 두 정의를 지우고, `import { EditorError, type EditorErrorCode } from "./editor-error.ts";` 와 위의 다시 내보내기 한 줄을 둔다.

### 3. 이 phase 를 검증하는 `tests/editor-error.test.ts` 신규

- `../src/editor/page.ts` 와 `../src/editor/editor-error.ts` 에서 가져온 `EditorError` 가 같은 클래스이고, `new EditorError("editor_failed", "open", "x", {a: 1})` 의 `code`, `stage`, `message`, `extra`, `name` 이 옮기기 전과 같다(`name` 은 `EditorError`)
- 기존 시험 전체가 그대로 통과한다. 특히 `tests/editor-run.test.ts`, `tests/drafts.test.ts` 가 `EditorError` 의 코드와 단계를 확인한다

## 검증

```bash
cd hermes/connectors/naver-blog && bun test ./tests/editor-error.test.ts
cd hermes/connectors/naver-blog && bun test ./tests
cd hermes/connectors/naver-blog && bun run typecheck
cd hermes/connectors/naver-blog && bun run build && bun run check:bundle
node scripts/check-file-length.mjs --update && node scripts/check-file-length.mjs
```

모두 종료 코드 0 이어야 한다. `page.ts` 의 기준값이 줄어든 것을 `git diff scripts/file-length-baseline.json` 으로 본다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/connectors/naver-blog/src/editor/editor-error.ts` | 신규 |
| `hermes/connectors/naver-blog/src/editor/page.ts` | 수정 |
| `hermes/connectors/naver-blog/tests/editor-error.test.ts` | 신규 |
| `hermes/connectors/naver-blog/dist/naver-blog-mcp.js` | 수정 |
| `scripts/file-length-baseline.json` | 수정 |
