# Phase 03. 원래 문서와 고친 글로 새 편집기 문서를 만든다

**Execution profile**: standard

## 목표

편집기에 불러온 원래 문서(`getDocumentData()` 결과)와 고친 글의 제목, 본문으로 `setDocumentData()` 에 넣을 새 문서를 만드는 순수 함수를 둔다.
기존 사진, 스티커, 지도는 원래 구성요소 객체를 그대로 옮기고, 바뀌지 않은 문단은 원래 문단 객체를 그대로 써서 꾸밈을 지킨다.

**범위 외**: 편집기에 넣고 저장하는 것(phase 04).

## 컨텍스트

코드는 `hermes/connectors/naver-blog/` 에 있다. `bun` 이 PATH 에 있어야 한다(없으면 `export PATH="$HOME/.bun/bin:$PATH"`).

**근거 문서**: `hermes/connectors/naver-blog/README.md` 의 「임시저장 글 덮어쓰기」 의 `apply` 단계, `hermes/docs/adr/ADR-20261009-naver-blog-overwrite.md` 의 「고치는 방법」, `hermes/docs/adr/ADR-20261009-naver-blog-primitives.md` 의 「맥락」(편집기 문서 모양).

`src/document.ts` 에 있는 것:

- `EditorDocument`, `DocumentComponent` 타입. 문서는 `{documentId, document: {components: [...]}}` 이고 구성요소마다 `"@ctype"` 가 있다
- 제목 구성요소는 `"@ctype": "documentTitle"` 이고 `title` 이 문단 배열이다. 글 구성요소는 `"@ctype": "text"` 이고 `value` 가 문단 배열이다
- 문단은 `{id, nodes: [{id, value, "@ctype": "textNode"}], "@ctype": "paragraph"}` 모양이다(실측)
- `documentToDraft(data)` 가 `{title, body, existing: [{line, index}]}` 를 돌려준다. `existing` 은 기존 구성요소 줄과 그 구성요소의 `components` 차례(0부터)다. 글 문단 하나가 본문 한 줄이다
- `DocumentShapeError`: 문서 모양이 다를 때 던진다

`src/changes.ts` 의 줄 비교(`lineEdits`)는 모듈 안의 함수다. 이 phase 에서 내보내 쓴다.

## 의도 메모

- 문서 전체를 새로 만들지 않고 원래 문서를 복제해 고친다. 루트의 `document.id`, `di`, `version`, `theme` 같은 칸은 뜻을 실측하지 못해 그대로 둔다
- 새 문단과 글 구성요소의 `id` 는 `SE-<UUID>` 로 만든다(실측한 id 모양). `crypto.randomUUID()` 를 쓴다
- 문단을 다시 쓰는 판정은 글자로만 한다. 같은 글자의 문단이 여럿이면 줄 비교가 짝지은 것을 쓴다

## 작업 항목

### 1. `src/changes.ts` 수정

`lineEdits` 를 `export function lineEdits(before: string[], after: string[])` 로 내보낸다. `Edit` 타입도 내보낸다. 동작은 그대로다.

### 2. `src/document.ts` 수정

`export function draftToDocument(original: EditorDocument, title: string, body: string): EditorDocument` 를 더한다.

1. `documentToDraft(original)` 로 원래 본문 줄과 `existing` 을 얻는다. 원래 글 문단 객체를 본문 줄 차례로 모은다(기존 구성요소 줄 자리는 비운다)
2. 고친 본문을 `\n` 으로 나누고 줄 끝 `\r` 을 뗀다. 줄마다 `EXISTING_LINE` 이면 `existing` 에서 같은 `line` 의 구성요소를 찾는다. 없으면 `DocumentShapeError("기존 구성요소 줄이 원래 글에 없다")` 를 던진다. 같은 줄이 두 번이면 같은 오류다
3. `lineEdits(원래 글 줄, 고친 글 줄)` 로 같은 줄을 짝짓는다. 기존 구성요소 줄은 짝 대상에서 빼고 비교한다. 짝지은 글 줄은 원래 문단 객체를 복제해 쓰고, 나머지는 새 문단을 만든다
4. 고친 본문을 앞에서부터 훑어 이어진 글 줄을 글 구성요소 하나(`{id: "SE-…", layout: "default", value: [문단…], "@ctype": "text"}`)로 묶고, 기존 구성요소 줄은 원래 구성요소 객체를 그 자리에 둔다
5. 글 구성요소가 하나도 없으면 빈 문단 하나를 가진 글 구성요소 하나를 맨 끝에 둔다
6. 제목 구성요소는 원래 객체를 복제하고 `title` 을 문단 하나로 바꾼다. 원래 첫 문단의 `id` 와 첫 노드의 `id` 가 있으면 그것을 쓴다
7. 원래 `components` 의 차례대로가 아니라 「제목 구성요소, 4 의 차례」 로 새 `components` 를 만든다. 원래 문서에서 제목, 글이 아닌데 본문에 없는 구성요소는 빠진다
8. `original` 을 바꾸지 않는다(`structuredClone` 으로 복제)

결과를 `documentToDraft` 로 다시 읽으면 제목은 넣은 값과 같다. 본문은 글 줄이 넣은 차례 그대로이고, 기존 구성요소 줄은 `documentToDraft` 가 문서 차례로 번호를 다시 매긴 줄이다. 그래서 다시 읽은 본문은 넣은 본문과 글자로 같지 않을 수 있다. 글 구성요소가 하나도 없어 5 의 빈 글 구성요소를 더했으면 다시 읽은 본문 끝에 빈 줄이 하나 붙는다.

### 3. 이 phase 를 검증하는 시험

`tests/document.test.ts` 에 더한다:

- 정상: 글 셋, 사진 둘(`id` 가 `SE-a`, `SE-b`), 스티커 하나인 문서에서 한 줄을 고치고 사진 둘의 차례를 바꾸고(`[기존 사진 2]` 를 앞에) 스티커 줄을 뺀 본문으로 문서를 만든다. 새 `components` 의 글이 아닌 구성요소 `id` 차례가 `SE-b`, `SE-a` 이고 스티커가 없다. 다시 읽은 본문의 글 줄은 넣은 차례와 같고 기존 줄은 `[기존 사진 1]`, `[기존 사진 2]` 로 다시 매겨진다. 바뀌지 않은 문단은 원래 문단의 `id` 와 꾸밈 칸(시험용으로 노드에 `style` 을 넣어 둔다)을 그대로 갖는다. 원래 문서는 바뀌지 않았다(`toEqual` 로 복제본과 견준다)
- 앞 사진 빼기: `[기존 사진 1]` 을 뺀 본문은 `SE-b` 만 남고, 다시 읽으면 그 줄이 `[기존 사진 1]` 이다
- 실패: 원래에 없는 `[기존 지도 1]` 이나 같은 기존 줄 두 번은 `DocumentShapeError`
- 빈 본문은 빈 문단 하나의 글 구성요소가 된다

`tests/changes.test.ts` 는 그대로 통과해야 한다.

## 검증

```bash
cd hermes/connectors/naver-blog && bun test ./tests/document.test.ts ./tests/changes.test.ts
cd hermes/connectors/naver-blog && bun run typecheck
cd hermes/connectors/naver-blog && bun run build && bun run check:bundle
node scripts/check-file-length.mjs
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/connectors/naver-blog/src/document.ts` | 수정 |
| `hermes/connectors/naver-blog/src/changes.ts` | 수정 |
| `hermes/connectors/naver-blog/tests/document.test.ts` | 수정 |
| `hermes/connectors/naver-blog/dist/naver-blog-mcp.js` | 수정 |
