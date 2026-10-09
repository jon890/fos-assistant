# Phase 02. 가짜 Hermes 가 목록 입력을 받고 e2e 가 실린 사진을 확인한다

**Execution profile**: fast

## 목표

가짜 Hermes 가 `input` 의 목록 모양을 읽고, 사진 첨부 e2e 시나리오가 Hermes 에 사진이 이미지로 실린 것을 확인한다.
phase 01 뒤로 사진을 붙인 실행의 `input` 이 배열이라, 가짜 Hermes 가 이를 문자열로 다루면 e2e 의 입력 검사가 깨진다.

**범위 외**: backend 코드(phase 01). 브라우저 검사와 web 입력창.

## 컨텍스트

**근거 문서**: `docs/hermes/runs-api.md` 의 「`/v1/runs` 에 사진을 싣는 법」(가짜 Hermes 의 동작 문장), `docs/adr/ADR-20261009-native-image-input.md`

- `test/e2e/fake-hermes/run-routes.ts` 가 `POST /p/<profile>/v1/runs` 를 받는다. `submitted` 타입이 `input?: string` 이고 `splitArtifactPreamble(submitted.input ?? "")` 로 글을 쪼갠 뒤 그 글로 모든 시나리오 분기를 고른다. `state.lastSubmittedInput = submitted.input` 으로 원문을 남긴다
- `test/e2e/fake-hermes/state.ts` 가 상태 변수(`lastSubmittedInput`)와 공개 타입 `FakeHermes` 를 갖고, `test/e2e/fake-hermes.ts` 가 `lastSubmittedInput: () => state.lastSubmittedInput` 으로 내보낸다
- `test/e2e/scenarios/chat-attachment.ts` 가 1×1 PNG(`onePixelPng()`)를 올려 보내고 `context.hermes.lastSubmittedInput()` 의 경로와 파일 이름, 끝 글을 확인한다. 1×1 PNG 는 JDK 가 읽으므로 phase 01 뒤 이미지로 실린다

## 의도 메모

- 입력 글은 `input` 이 문자열이면 그것, 배열이면 마지막 항목의 `content` 다. `content` 가 문자열이면 그것, 배열이면 첫 `text` 파트다. 이름표 글 파트를 이어 붙이지 않는다. 붙이면 `endsWith(text)` 같은 기존 검사와 시나리오 분기가 바뀐다
- 이미지는 `{ label, url }` 목록으로 남긴다. `label` 은 그 이미지 바로 앞의 `text` 파트다
- 추천 실행(`starterRun`)은 지금처럼 마지막 제출 기록을 덮지 않는다

## 작업 항목

### 1. `test/e2e/fake-hermes/run-routes.ts`

- `submitted.input` 타입을 `string | { role?: string; content?: string | { type?: string; text?: string; image_url?: { url?: string } }[] }[]` 로 넓힌다
- 위 의도 메모의 규칙으로 입력 글과 이미지 목록을 뽑는 함수 `submittedText`, `submittedImages` 를 이 파일에 두고, `splitArtifactPreamble` 과 `state.lastSubmittedInput` 에는 입력 글을 넘긴다
- `starterRun` 이 아닐 때 `state.lastSubmittedImages` 에 이미지 목록을 넣는다

### 2. `test/e2e/fake-hermes/state.ts`, `test/e2e/fake-hermes.ts`

- 상태에 `lastSubmittedImages: { label: string; url: string }[]`(처음은 빈 배열)를 더한다
- `FakeHermes` 에 `lastSubmittedImages(): readonly { label: string; url: string }[]` 를 더하고 `fake-hermes.ts` 가 내보낸다
- `lastSubmittedInput` 의 주석을 「입력 글. 목록 입력이면 마지막 항목의 첫 글 파트」 로 고친다

### 3. `test/e2e/scenarios/chat-attachment.ts`

- 보낸 뒤 `context.hermes.lastSubmittedImages()` 가 한 장이고, `label` 이 `1번째 사진`, `url` 이 `data:image/jpeg;base64,` 로 시작하는지 확인한다
- 입력 글에 `이 메시지에 이미지로 함께 실은 사진: 1번째 사진.` 이 있는지 확인한다
- 기존 경로, 파일 이름, 끝 글 확인은 그대로 둔다

## 검증

```bash
node test/e2e/run.ts
node --test 'test/unit/**/*.test.ts'
cd test && ../web/node_modules/.bin/tsc -p tsconfig.json
```

e2e 가 「사진 첨부」 시나리오를 포함해 통과하고, 단위 시험과 타입 검사가 종료 코드 0 이다.
e2e 는 backend 를 빌드해 띄우므로 무겁다. 잠금 도우미로 감싸 돌린다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `test/e2e/fake-hermes/run-routes.ts` | 수정 |
| `test/e2e/fake-hermes/state.ts` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/e2e/scenarios/chat-attachment.ts` | 수정 |
