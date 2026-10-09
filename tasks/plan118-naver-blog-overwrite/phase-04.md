# Phase 04. 덮어쓰기 편집기 흐름

**Execution profile**: deep

## 목표

그 글을 불러와 지문과 바뀌는 내용을 대조하고, 원본 사본을 새 임시저장 글로 남긴 뒤, 고친 문서와 발행 설정을 넣고 저장 단추를 눌러 같은 글이 고쳐졌는지 확인하는 `runOverwrite` 를 만든다.

**범위 외**: MCP 도구 등록과 작업 프로세스 연결(phase 05). 새 사진, 스티커, 지도 넣기(이 계획 밖).

## 컨텍스트

코드는 `hermes/connectors/naver-blog/` 에 있다. `bun` 이 PATH 에 있어야 한다(없으면 `export PATH="$HOME/.bun/bin:$PATH"`).

**근거 문서**: `hermes/connectors/naver-blog/README.md` 의 「임시저장 글 덮어쓰기」 의 단계 표와 `status`, `error.code` 표. `hermes/docs/adr/ADR-20261009-naver-blog-overwrite.md`. 이 phase 의 단계 이름, 결과 칸, 오류 코드는 그 표와 글자까지 같아야 한다.

따를 본보기는 `src/editor/run.ts` 의 `runDraft` 다. 탭을 열고, 단계마다 `enter(stage)` 로 `onStage` 를 부르고 중단 신호를 보고, 끝나면 연 탭을 닫는다.
쓸 수 있는 것:

| 이름 | 자리 | 하는 일 |
| --- | --- | --- |
| `openWriteTab(cdpUrl, blogId, options)`, `closeTab` | `src/editor/page.ts` | 글쓰기 탭을 열고 닫는다 |
| `open(page)` | `src/editor/open.ts` | 글쓰기 화면을 기다리고 「작성 중인 글」 알림을 「취소」 로 닫는다 |
| `tempDrafts(page, blogId)` | `src/editor/drafts.ts` | 목록 응답의 `[{draft_id, title, saved_at}]`. `title` 은 자르지 않는다. 앞의 200개까지다 |
| `loadDraft(page, blogId, draftId)` | `src/editor/drafts.ts` | 목록에서 그 글을 불러온다. 없으면 `ToolError("NAVER_BLOG_DRAFT_NOT_FOUND")` |
| `editorDocument(page)` | `src/editor/drafts.ts` | `getDocumentData()` 결과 |
| `readSettings(page)` | `src/editor/settings.ts` | `{category, tags}` |
| `settings(page, input, draftHash)` | `src/editor/settings.ts` | 카테고리를 고르고 `input.tags` 를 넣는다. 있는 태그를 지우지는 않는다 |
| `documentToDraft`, `draftToDocument` | `src/document.ts` | 문서와 글 사이를 바꾼다(phase 03) |
| `draftRevision`, `draftChanges` | `src/changes.ts` | 지문과 바뀌는 내용 |
| `EditorError`, `EditorErrorCode` | `src/editor/editor-error.ts` | 편집기 실패(phase 02 에서 옮김) |

`src/editor/settings.ts` 는 382줄이고 400줄이 상한이다. 그 안의 `openSettings`, `closeSettings`, `settingsState` 는 `export` 만 붙여 쓴다. 새 코드는 새 파일에 둔다.
임시저장 수는 목록 응답의 `result.totalCount` 로 센다. 목록은 200개에서 잘리고, 저장 단추 옆의 수(`saveCount`)는 그 탭이 저장할 때만 새로 읽혀 다른 탭의 저장을 반영하지 않는다.

## 의도 메모

- 사본 저장은 덮어쓰기의 저장이 아니다. `onStage("save_clicking")` 은 원래 글의 저장 단추를 누르기 직전에 한 번만 부른다. 작업 프로세스는 그 알림으로 `save_clicked` 를 기록하고, 그 뒤 실패를 `unknown` 으로 본다
- 태그는 차례가 바뀌어도 같은 글로 본다. 칩을 지우고 더하면 차례가 요청과 달라질 수 있다. 저장 전 확인과 결과에서 태그는 집합으로 견준다
- 사본을 만든 뒤 난 실패는 오류의 `extra` 에 `backup_draft_id` 를 싣는다. 사용자가 사본을 찾게 하려는 것이다
- 대역은 탭마다 편집기를 따로 두지 않는다. 사본 탭의 일을 모두 끝내고 닫은 뒤 원래 탭으로 돌아오는 차례를 지키면 대역에서도 맞게 돈다. 사본 탭은 원래 탭의 카테고리와 태그를 물려받으므로 대역의 태그 입력은 이미 있는 태그를 다시 더하지 않는다
- 다시 읽은 본문의 기존 구성요소 번호는 문서 차례로 다시 매겨진다(`hermes/connectors/naver-blog/README.md` 의 「고친 글의 모양」). 그래서 저장 전 확인은 요청한 본문이 아니라 만든 문서를 `documentToDraft` 로 읽은 것과 견준다

## 작업 항목

### 1. `src/editor/editor-error.ts` 수정

`EditorErrorCode` 에 `"draft_not_found"`, `"draft_changed"`, `"changes_mismatch"`, `"component_not_found"`, `"backup_failed"` 를 더한다.

### 2. `src/editor/settings.ts` 와 `src/editor/drafts.ts` 수정

- `settings.ts`: `openSettings`, `closeSettings`, `settingsState` 앞에 `export` 를 붙인다. 다른 줄은 바꾸지 않는다
- `drafts.ts`: `export async function tempDraftCount(page, blogId): Promise<number>` 를 더한다. `tempDrafts` 와 같은 응답을 받아 `result.totalCount` 가 수이면 그것을, 아니면 `tempPostList` 의 길이를 돌려준다. 응답을 읽는 부분은 `tempDrafts` 와 함께 쓰는 내부 함수로 꺼낸다

### 3. `src/editor/overwrite.ts` 신규

```ts
export type OverwriteInput = {
  draft_id: string; revision: string; changes: string;
  title: string; category: string; tags: string[]; body: string;
};
export type OverwriteStage =
  "open" | "load" | "verify" | "backup" | "apply" | "settings" | "save" | "save_clicking" | "state";
export type OverwriteResult = {
  draft_id: string; backup_draft_id: string; backup_title: string;
  saved_before: number; saved_after: number; state: { revision: string } | null;
};
export const BACKUP_PREFIX = "[덮어쓰기 전 원본] ";
export async function runOverwrite(
  env: Env, input: OverwriteInput, onStage: (stage: OverwriteStage) => unknown,
  signal: AbortSignal, options?: Omit<EditorOptions, "signal">,
): Promise<OverwriteResult>
```

차례:

1. `open`: `readConnection(env)` 로 주소와 아이디를 받고 탭 A 를 열어 `open(pageA)`
2. `load`: `loadDraft(pageA, blogId, input.draft_id)`. `ToolError` 의 `NAVER_BLOG_DRAFT_NOT_FOUND` 는 `EditorError("draft_not_found")` 로 바꾼다
3. `verify`: `original = editorDocument(pageA)`, `content = documentToDraft(original)`, `{category, tags} = readSettings(pageA)`.
   지금 글 `current = {title, category, tags, body}` 의 `draftRevision` 이 `input.revision` 과 다르면 `draft_changed`. `draftChanges(current, 고친 글)` 이 `input.changes` 와 다르면 `changes_mismatch`.
   `draftToDocument(original, input.title, input.body)` 가 `DocumentShapeError` 를 던지면 `component_not_found`. 만든 문서는 5 에서 쓴다
4. `backup`: `before = tempDrafts(pageA, blogId)` 와 `savedBefore = tempDraftCount(pageA, blogId)` 를 읽는다. `before` 에서 원래 글의 제목과 `saved_at` 을 기억한다.
   사본 제목은 `BACKUP_PREFIX + content.title` 을 100자로 자른 것이다.
   사본 문서는 `structuredClone(original)` 에서 `documentId` 를 `""` 로, 제목 구성요소의 `title` 을 사본 제목 문단 하나로 바꾼 것이다. 다른 구성요소는 건드리지 않는다(`withTitle(doc, title)` 도우미).
   탭 B 를 열어 `open(pageB)` 하고 `setDocument(pageB, 사본 문서)` 로 넣는다.
   `documentToDraft(editorDocument(pageB))` 의 제목과 본문이 사본 제목과 원래 본문(`content.body`)인지 본다.
   `settings(pageB, {title: 사본 제목, category, tags, body: content.body}, <임의 해시>)` 로 원래 카테고리와 태그를 넣는다.
   `clickSave(pageB)` 로 저장 단추를 누르고, `page.times.saveSeconds` 동안 `tempDrafts(pageB, blogId)` 를 다시 읽는다. 원래 글의 제목이나 `saved_at` 이 `before` 와 달라지거나, `before` 에 없던 글이 하나 생기고 그 제목이 사본 제목이면 기다림을 끝낸다.
   목록에서 원래 글을 찾지 못하면(200개 경계로 밀려난 경우) 바뀐 것으로 보지 않고 새 사본만 본다.
   기다림이 끝나면 원래 글의 변경을 먼저 본다. 바뀌었으면 `backup_failed` 이고 `extra.original_changed` 가 참이다. 이때 새 사본 글이 보였으면 `extra.backup_draft_id` 도 싣는다.
   원래 글이 그대로이고 새 사본 글이 없으면 `backup_failed`(extra 없음)다. 탭 B 를 닫는다
5. `apply`: `expected = documentToDraft(3 에서 만든 문서)` 를 기억하고 `setDocument(pageA, 3 에서 만든 문서)`. 다시 읽은 문서의 `documentId` 가 `input.draft_id` 이고 `documentToDraft` 의 제목과 본문이 `expected` 와 같은지 본다. 아니면 `editor_failed`
6. `settings`: `removeTags(pageA, 지울 태그)` 로 고친 글에 없는 태그의 칩을 지운다. 그 뒤 `settings(pageA, {…고친 글, tags: 새로 더할 태그}, <임의 해시>)`
7. `save`: 편집기 문서를 다시 읽은 제목과 본문이 5 의 `expected` 와 같고, `readSettings` 의 카테고리가 `input.category` 와 같고 태그 집합이 `input.tags` 의 집합과 같은지 본다. 아니면 `editor_failed`.
   `targetBefore = tempDrafts` 에서 그 글의 `saved_at` 을 읽는다. `await onStage("save_clicking")` 뒤 `clickSave(pageA)`.
   그 뒤 실패는 모두 `EditorError("save_unconfirmed")` 다. `saveSeconds` 동안 목록을 다시 읽어 그 글의 `saved_at` 이 바뀌고 `tempDraftCount` 가 `savedBefore + 1`(사본 하나)과 같으면 성공이다
8. `state`: 편집기 네 칸의 `draftRevision` 을 `{revision}` 으로 읽는다. 실패하면 `null`
9. 4 의 사본 확인 뒤에 난 예외는 모두 `extra` 에 `backup_draft_id` 를 실어 다시 던진다. `EditorError` 는 같은 코드와 단계로, 그 밖의 예외는 `EditorError("editor_failed", 지금 단계, "사본을 만든 뒤 멈췄다")` 로 바꾼다. 중단(`aborted`)도 같은 코드와 메시지를 지키고 `extra` 만 더한다
10. `finally` 에서 연 탭을 모두 닫는다(`page.close()` 와 `closeTab`)

같은 파일의 도우미:

- `setDocument(page, doc)`: 아래 모양의 식 하나를 `page.js` 로 돌린다. 대역이 이 모양으로 문서를 꺼낸다.
  `(() => { const doc = <JSON.stringify(JSON.stringify(doc))>; const editors = window.SmartEditor && window.SmartEditor._editors; const editor = editors && Object.values(editors)[0]; if (!editor || typeof editor.setDocumentData !== 'function') return false; editor.setDocumentData(JSON.parse(doc)); return true; })()`
  `false` 면 `editor_failed`
- `clickSave(page)`: `buttonFinder("button", "저장")` 을 `page.mouseClick` 으로 누른다. 못 찾으면 `false`
- `removeTags(page, tags)`: `openSettings`, 태그마다 `[...document.querySelectorAll('span[id^="tag-item-"][aria-label]')].find(e => e.getAttribute('aria-label') === <태그 JSON>)?.querySelector('button')` 를 `mouseClick` 하고 `settingsState(page)` 의 `tags` 에서 그 태그가 빠질 때까지 `waitUntil`, 끝나면 `closeSettings`. 칩이나 단추를 못 찾거나 빠지지 않으면 `editor_failed`
- `withTitle(doc, title)`: 복제한 문서의 제목 구성요소 `title` 을 문단 하나(원래 첫 문단과 첫 노드의 `id` 를 쓴다)로 바꾼다

도우미 넷(`setDocument`, `clickSave`, `removeTags`, `withTitle`)은 `src/editor/overwrite-page.ts` 에 두고 `overwrite.ts` 가 가져다 쓴다. 두 파일 모두 400줄 상한 안에 둔다.

결과는 `{draft_id, backup_draft_id, backup_title, saved_before: savedBefore + 1, saved_after: 7 에서 읽은 tempDraftCount, state}` 다. 덮어쓰기는 수를 바꾸지 않으므로 두 수가 같다.

### 4. `tests/fake-cdp.ts` 수정

`FakeEditor` 에 더한다. 기존 시험의 동작은 바꾸지 않는다.

- `document: unknown | null` 과 `documentId: string`: 지금 편집기 문서. 목록에서 글을 누르면 `this.loaded`(`loadInstead` 가 있으면 그 글)의 `components` 와 `logNo` 로 채운다. `getDocumentData` 식은 이것을 돌려준다(없으면 지금처럼 `loaded` 기준)
- 목록 응답의 `totalCount` 는 `drafts.length` 다
- `setDocumentData` 식: 식의 `const doc = ` 뒤 첫 JSON 문자열을 `quoted` 로 꺼내 `JSON.parse` 한 문서로 `document`, `documentId` 를 바꾸고 `true` 를 돌려준다
- 저장 단추: `saveIncrements` 가 거짓이면 아무것도 하지 않는다(기존 동작). `document` 가 `null` 이면 지금처럼 `saved` 만 1 늘린다(새 글 시험의 기존 동작). `documentId` 가 `drafts` 의 어느 글과 같으면 그 글의 `components`, `title`(문서 제목 구성요소의 글), `category`, `tags` 를 지금 값으로 바꾸고 `modiDate` 를 1000 늘린다. 수는 그대로다. `ignoreUpdate` 가 참이면 이 고치기만 하지 않는다. `documentId` 가 비었으면 `logNo` 를 새로 정해 같은 칸으로 `drafts` 맨 앞에 넣고 `saved` 를 1 늘린다. `backupUpdatesLoaded` 가 참이면 이때 새 글 대신 `this.loaded` 글을 고친다(사본 저장이 원래 글을 고친 상황)
- 태그 입력의 Enter: 이미 있는 태그면 더하지 않는다
- 태그 칩 지우기: 위 `removeTags` 의 찾는 식을 누르면 그 태그를 `tags` 에서 뺀다. 발행 설정이 열려 있을 때만 된다
- `open` 이 「작성 중인 글」 알림을 닫으면 `document` 를 비운다

### 5. 이 phase 를 검증하는 `tests/overwrite.test.ts` 신규

`tests/drafts.test.ts` 의 `setup` 을 본보기로 쓰되, 대역이 저장으로 `drafts` 를 바꾸므로 시험마다 글 목록을 `structuredClone` 해서 넘긴다. 탭 정리는 「연 주소가 글쓰기 주소 둘이고(실패 시험은 하나일 수 있다), 미리 열어 둔 다른 탭만 남았다」 로 따로 단언한다. 원래 글: 제목, 글 셋, 사진 둘(`id` 가 다른 `image` 구성요소), 태그 `["가상국수", "점심"]`, 카테고리 `가상국수로그`.

- 정상: 본문 한 줄을 고치고 두 사진의 줄 차례를 바꾸고 태그를 `["가상국수", "저녁"]`, 카테고리를 `일상` 으로 바꾼 입력. 결과의 `backup_draft_id` 는 새 글 번호이고, 그 사본의 제목이 `[덮어쓰기 전 원본] <원래 제목>` 이며 사본의 제목 구성요소 밖 `components` 가 원래와 `toEqual` 이고 카테고리와 태그가 원래 것과 같다. 원래 글은 고친 줄을 갖고 두 사진 구성요소의 `id` 차례가 바뀌었으며 태그 집합이 입력과 같다. `saved_before === saved_after`. 단계가 `open, load, verify, backup, apply, settings, save, save_clicking, state` 차례다. 연 탭 둘이 모두 닫혔다
- `revision` 이 다르면 `draft_changed` 이고 `drafts` 의 수와 원래 글이 그대로다
- `changes` 가 다르면 `changes_mismatch`
- 원래에 없는 `[기존 지도 1]` 이면 `component_not_found`
- 앞 사진의 줄을 뺀 입력은 성공하고 원래 글에 뒤 사진 하나만 남는다
- `saveIncrements = false` 면 `backup_failed` 이고 원래 글이 그대로다
- `backupUpdatesLoaded = true` 면 `backup_failed` 이고 `extra.original_changed` 가 참이다
- `ignoreUpdate = true` 면 `save_unconfirmed` 이고 `extra.backup_draft_id` 가 사본 번호다

## 검증

```bash
cd hermes/connectors/naver-blog && bun test ./tests/overwrite.test.ts ./tests/drafts.test.ts ./tests/editor-run.test.ts
cd hermes/connectors/naver-blog && bun test ./tests
cd hermes/connectors/naver-blog && bun run typecheck
cd hermes/connectors/naver-blog && bun run build && bun run check:bundle
node scripts/check-file-length.mjs
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/connectors/naver-blog/src/editor/editor-error.ts` | 수정 |
| `hermes/connectors/naver-blog/src/editor/settings.ts` | 수정 |
| `hermes/connectors/naver-blog/src/editor/drafts.ts` | 수정 |
| `hermes/connectors/naver-blog/src/editor/overwrite.ts` | 신규 |
| `hermes/connectors/naver-blog/src/editor/overwrite-page.ts` | 신규 |
| `hermes/connectors/naver-blog/tests/fake-cdp.ts` | 수정 |
| `hermes/connectors/naver-blog/tests/overwrite.test.ts` | 신규 |
| `hermes/connectors/naver-blog/dist/naver-blog-mcp.js` | 수정 |
