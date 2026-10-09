# Phase 01. 고친 글 검사와 바뀌는 내용 미리보기

**Execution profile**: standard

## 목표

`render_draft` 가 `base`(원래 글의 네 칸)를 받으면 고친 글을 덮어쓰기 규칙으로 검사하고, 바뀌는 내용 글(`changes`)과 원래 글의 지문(`base_revision`)을 돌려주게 한다.
승인 카드에 올릴 바뀌는 내용을 모델이 아니라 커넥터가 만들게 하려는 것이다.

**범위 외**: `overwrite_draft` 도구와 작업(phase 04, 05), 편집기 조작(phase 04).

## 컨텍스트

코드는 `hermes/connectors/naver-blog/` 에 있다. Bun 1.3.14 이상으로 돈다. `bun` 이 PATH 에 있어야 한다(없으면 `export PATH="$HOME/.bun/bin:$PATH"`).

**근거 문서**: `hermes/connectors/naver-blog/README.md` 의 「임시저장 글 덮어쓰기」 절의 「고친 글의 모양」 과 「미리보기와 바뀌는 내용」, `hermes/docs/adr/ADR-20261009-naver-blog-overwrite.md`.

이미 있는 것:

- `src/changes.ts`: `DraftContent` 타입, `draftRevision(content)`, `draftChanges(before, after)`. 이 브랜치에 있고 `tests/changes.test.ts` 가 시험한다. 그대로 쓴다
- `src/document.ts`: `EXISTING_LINE` 정규식(`[기존 사진 1]` 같은 줄)
- `src/draft.ts`: `draftShape`, `DraftInput`, `parseBody(body)`(줄마다 `text`, `image`, `sticker`, `map` 블록), `validateDraft(input)`. `validateDraft` 는 기존 구성요소 줄이 있으면 「… 같은 기존 구성요소 줄은 새 글에 넣을 수 없습니다」 문장을 낸다
- `src/render.ts`: `renderShape`, `RenderInput`, `renderDraft(input, attachmentDir)`, `previewHtml(...)`, `escapeHtml`. `render_draft` 도구는 `src/server.ts` 가 `renderShape` 로 등록한다

파일 길이: `hermes/connectors/*/src/` 의 `.ts` 는 400줄이 상한이다(`scripts/check-file-length.mjs`). `src/draft.ts` 는 334줄이라 새 검사는 새 파일에 둔다.

## 의도 메모

- 덮어쓰기 검사를 `validateDraft` 에 플래그로 넣지 않는다. 새 글 검사의 문장과 사진 확인이 섞여 읽기 어려워진다
- `changes` 가 빈 문자열이면 바뀐 것이 없는 것이다. 덮어쓸 까닭이 없어 `problems` 로 알린다

## 작업 항목

### 1. `src/overwrite-draft.ts` 신규

- `export const overwriteContentShape`: zod 칸 넷. `title`, `category`, `tags`, `body` 는 `draftShape` 의 같은 칸을 그대로 쓴다
- `export type OverwriteContent = DraftContent`(`src/changes.ts`)
- `export function validateOverwrite(input: OverwriteContent, base?: OverwriteContent): string[]` 가 어긋난 자리를 문장으로 돌려준다
  - 길이와 태그 검사: `validateDraft({ ...input, body: <기존 구성요소 줄과 사진, 스티커, 지도 줄(`parseBody` 의 `image`, `sticker`, `map` 블록)을 모두 빈 줄로 바꾼 본문> })` 의 문장을 그대로 쓴다. 구성요소 줄을 비워 두므로 사진 디렉터리 문장과 사진 파일 이름 문장은 나오지 않는다
  - 카테고리나 태그의 앞뒤에 공백이 있으면 「카테고리와 태그 앞뒤에 공백을 두지 않습니다.」 한 문장. 편집기는 공백을 떼고 넣어 저장 전 확인이 어긋난다
  - 새 구성요소 줄: `parseBody(input.body)` 에 `image`, `sticker`, `map` 블록이 있으면 「덮어쓰기에는 새 사진, 스티커, 지도를 넣지 않습니다. 새 글로 저장하거나 네이버에서 직접 넣어 주세요.」 한 문장
  - 같은 기존 구성요소 줄이 두 번이면 「<줄> 은 한 번만 둡니다.」
  - `base` 가 있으면: 기존 구성요소 줄마다 `base.body` 의 줄에 같은 줄이 없으면 「<줄> 은 원래 글에 없습니다.」. `draftChanges(base, input)` 이 빈 문자열이면 「바뀐 것이 없습니다.」
- 줄을 견줄 때 줄 끝 `\r` 하나는 뗀다(`parseBody` 와 같다)
- `export const CHANGES_MAX = 100_000`: 바뀌는 내용 글의 최대 글자 수

### 2. `src/render.ts` 수정

- `renderShape` 에 `base: z.object(overwriteContentShape).optional()` 을 더한다. 설명: 「덮어쓸 때 read_draft 가 준 원래 글의 title, category, tags, body. 주면 changes 와 base_revision 을 함께 돌려준다」
- `RenderInput` 에 `base?: OverwriteContent` 를 더한다
- `RenderResult` 의 성공 모양에 `changes?: string`, `base_revision?: string` 을 더한다
- `renderDraft` 에서 `input.base` 가 있으면:
  - `validateRenderOptions(input)` 와 `validateOverwrite(input, input.base)` 의 문장을 모은다. `validateDraft` 와 `checkPhotoFiles` 는 부르지 않는다
  - `kind` 가 `package` 면 「덮어쓰기 미리보기는 kind preview 만 받습니다.」 문장을 더한다
  - `photo_dir` 이 있으면 「덮어쓰기에는 photo_dir 을 주지 않습니다.」 문장을 더한다
  - 만든 `changes` 의 `changes.length`(UTF-16 길이, phase 05 의 zod 검사와 같은 셈)가 `CHANGES_MAX` 를 넘으면 「바뀌는 내용이 너무 깁니다. 나눠 고쳐 주세요.」 문장을 더한다
  - 문장이 있으면 지금처럼 `{problems, html: null, assets: []}`
  - 없으면 `previewHtml` 결과에 `changes: draftChanges(input.base, input)` 와 `base_revision: draftRevision(input.base)` 를 더해 돌려준다
- `previewHtml` 에 선택 인자 `changes?: string` 을 더한다. 있으면 `<article>` 앞에 `<section class="changes"><h2>바뀌는 내용</h2><pre>…</pre></section>` 을 넣는다(`escapeHtml` 로 감싼다). `PREVIEW_STYLE` 에 `.changes` 의 테두리와 `pre{white-space:pre-wrap}` 을 더한다
- `previewHtml` 의 `text` 블록이 `EXISTING_LINE` 에 맞으면 `<p class="placeholder">기존 사진 1</p>` 처럼 대괄호를 뗀 이름표로 그린다

### 3. 이 phase 를 검증하는 시험

`tests/overwrite-draft.test.ts` 신규:

- 정상: 본문 한 줄과 태그 하나를 고치고 `[기존 사진 1]` 의 차례를 바꾼 글은 문장이 없다
- 실패: `[사진 1: 101.jpg]` 가 든 글은 `problems` 가 새 구성요소 문장 하나뿐이다(사진 디렉터리 문장이 섞이지 않는다). 앞에 공백이 있는 태그 `" 저녁"` 은 공백 문장, `[기존 사진 1]` 을 두 번 쓴 글은 한 번만 문장, `base` 에 없는 `[기존 지도 2]` 는 원래 글에 없다는 문장, `base` 와 같은 글은 「바뀐 것이 없습니다.」
- 101자 제목은 `validateDraft` 와 같은 제목 문장이 하나 나온다

`tests/render.test.ts` 에 더한다:

- `base` 를 준 호출은 `changes` 가 `draftChanges(base, 고친 글)` 과 같고 `base_revision` 이 `draftRevision(base)` 와 같으며 `html` 에 「바뀌는 내용」 과 `기존 사진 1` 이름표가 있다
- `base` 와 `kind: "package"` 를 함께 주거나 `base` 와 `photo_dir` 을 함께 주면 `html` 이 `null` 이고 `problems` 가 있다
- `base` 없이 기존 구성요소 줄을 주면 지금처럼 새 글 문장으로 거절한다(회귀)

## 검증

```bash
cd hermes/connectors/naver-blog && bun test ./tests/overwrite-draft.test.ts ./tests/render.test.ts ./tests/changes.test.ts
cd hermes/connectors/naver-blog && bun run typecheck
cd hermes/connectors/naver-blog && bun run build && bun run check:bundle
node scripts/check-file-length.mjs
```

모두 종료 코드 0 이어야 한다. `bun run build` 가 `dist/naver-blog-mcp.js` 를 다시 만들므로 그 파일도 커밋에 넣는다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/connectors/naver-blog/src/overwrite-draft.ts` | 신규 |
| `hermes/connectors/naver-blog/src/render.ts` | 수정 |
| `hermes/connectors/naver-blog/tests/overwrite-draft.test.ts` | 신규 |
| `hermes/connectors/naver-blog/tests/render.test.ts` | 수정 |
| `hermes/connectors/naver-blog/dist/naver-blog-mcp.js` | 수정 |
