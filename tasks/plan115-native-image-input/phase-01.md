# Phase 01. 가짜 Hermes 가 문자열과 목록 `input` 을 모두 받는다

**Execution profile**: fast

## 목표

가짜 Hermes 의 실행 제출이 `input` 의 목록 모양(user 메시지의 `content` 가 글 파트와 이미지 파트의 목록)을 읽게 한다.
phase 02 뒤로 사진을 붙인 실행의 `input` 이 배열이 된다. 그 전에 가짜 Hermes 가 받아 두어야 각 커밋에서 e2e 와 브라우저 검사가 통과한다.
문자열 `input` 의 동작은 바뀌지 않는다.

**범위 외**: backend 코드와 e2e 시나리오의 사진 단언(phase 02).

## 컨텍스트

**근거 문서**: `docs/hermes/runs-api.md` 의 「`/v1/runs` 에 사진을 싣는 법」 의 가짜 Hermes 문장, `docs/adr/ADR-20261009-native-image-input.md`

- `test/e2e/fake-hermes/run-routes.ts` 가 `POST /p/<profile>/v1/runs` 를 받는다. `submitted` 의 타입이 `input?: string` 이다
- `submitted.input` 을 읽는 곳은 세 곳이다
  - 81행 근처: `splitArtifactPreamble(submitted.input ?? "")`. 이 글로 모든 시나리오 분기를 고른다
  - 88행 근처: `state.lastSubmittedInput = submitted.input`(추천 실행 `starterRun` 이 아닐 때)
  - 173행 근처: `state.proactiveInputs.push({ profile: profile!, input: submitted.input ?? "" })`
- `test/e2e/fake-hermes/state.ts` 가 상태 변수(`lastSubmittedInput`)와 공개 타입 `FakeHermes`(`lastSubmittedInput(): string | undefined`)를 갖고, `test/e2e/fake-hermes.ts` 가 `lastSubmittedInput: () => state.lastSubmittedInput` 으로 내보낸다
- 가짜 Hermes 는 e2e(`test/e2e/run.ts`)와 브라우저 검사(`test/browser/chat-attachment.spec.ts` 등)가 함께 쓴다

## 의도 메모

- 입력 글: `input` 이 문자열이면 그것. 배열이면 마지막 항목의 `content`. 그 `content` 가 문자열이면 그것, 배열이면 `type` 이 `text` 인 첫 파트의 `text`. 어느 것도 없으면 빈 글
- 이름표 글 파트를 이어 붙이지 않는다. 붙이면 `endsWith(text)` 같은 기존 검사와 시나리오 분기가 바뀐다
- 이미지는 `{ label, url }` 목록이다. `type` 이 `image_url` 인 파트마다 `url` 은 `image_url.url`, `label` 은 그 바로 앞 파트가 `text` 면 그 글, 아니면 빈 글이다
- 추천 실행(`starterRun`)은 지금처럼 마지막 제출 기록을 덮지 않는다

## 작업 항목

### 1. `test/e2e/fake-hermes/run-input.ts`(신규)

- 타입 `SubmittedInput = string | { role?: string; content?: string | { type?: string; text?: string; image_url?: { url?: string } }[] }[]`
- `export function submittedText(input: SubmittedInput | undefined): string`
- `export function submittedImages(input: SubmittedInput | undefined): { label: string; url: string }[]`(문자열이나 없음이면 빈 배열)

### 2. `test/e2e/fake-hermes/run-routes.ts`

- `submitted.input` 타입을 `SubmittedInput` 으로 바꾼다
- 위 세 곳 모두 `submittedText(submitted.input)` 결과를 쓴다
- `starterRun` 이 아닐 때 `state.lastSubmittedImages = submittedImages(submitted.input)` 를 넣는다

### 3. `test/e2e/fake-hermes/state.ts`, `test/e2e/fake-hermes.ts`

- 상태에 `lastSubmittedImages: { label: string; url: string }[]`(처음은 빈 배열)를 더한다
- `FakeHermes` 에 `lastSubmittedImages(): readonly { label: string; url: string }[]` 를 더하고 `fake-hermes.ts` 가 내보낸다
- `lastSubmittedInput` 의 주석을 「입력 글. 목록 입력이면 마지막 항목의 첫 글 파트다」 로 고친다

### 4. `test/unit/fake-hermes-run-input.test.ts`(신규)

`node:test` 와 `node:assert/strict` 로 쓴다(`test/unit/fake-hermes-controls.test.ts` 의 모양을 따른다).

- 문자열 입력은 그대로 글이고 이미지는 빈 배열이다
- `[{role:"user", content:[{type:"text",text:"본문"},{type:"text",text:"1번째 사진"},{type:"image_url",image_url:{url:"data:image/jpeg;base64,AAAA"}}]}]` 는 글이 「본문」, 이미지가 `[{label:"1번째 사진", url:"data:image/jpeg;base64,AAAA"}]` 다
- 마지막 항목만 읽는다. 앞 항목의 글은 쓰지 않는다
- `undefined` 와 빈 배열은 빈 글이다

## 검증

```bash
node --test test/unit/fake-hermes-run-input.test.ts
node --test 'test/unit/**/*.test.ts'
cd test && ../web/node_modules/.bin/tsc -p tsconfig.json
node test/e2e/run.ts
pnpm --dir web test:browser chat-attachment
```

단위 시험과 타입 검사, e2e 가 종료 코드 0 이다. 문자열 입력만 오는 지금 backend 로 e2e 전체가 그대로 통과해야 한다.
e2e 와 브라우저 검사는 무겁다. 스폰 프롬프트가 알려 주는 잠금 도우미로 감싸 한 번에 하나만 돌린다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `test/e2e/fake-hermes/run-input.ts` | 신규 |
| `test/e2e/fake-hermes/run-routes.ts` | 수정 |
| `test/e2e/fake-hermes/state.ts` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/unit/fake-hermes-run-input.test.ts` | 신규 |
