# Phase 05. 화면이 provider id 대신 표시 이름을 그린다

**Execution profile**: standard

## 목표

사용자가 화면에서 Hermes 의 provider id 원문을 보지 않게 한다. 표시 이름으로 바꿔 그린다.

**범위 외**: 저장 형식과 API 응답은 바꾸지 않는다. backend 를 고치지 않는다. 모델 이름은 그대로 그린다.
관리자가 단계별 모델을 편집하는 입력 칸(`web/src/components/chat/model-picker.tsx` 의 「모델 제공사」 `Input`)은 id 를 그대로 받는다. 그 칸의 문구는 phase 06 이 맡는다.

## 컨텍스트

provider id 를 그대로 그리는 곳은 아래가 전부다. 모두 `isAdmin` 일 때만 그린다.

| 파일 | 지금 그리는 것 |
| --- | --- |
| `web/src/components/usage/execution-table.tsx` | `{execution.provider ?? "-"}` |
| `web/src/components/usage/execution-card.tsx` | `{execution.provider ?? "-"} / {execution.model ?? "-"}` |
| `web/src/components/execution/execution-detail.tsx` | `[summary.provider, summary.model].filter(Boolean).join(" · ")` |
| `web/src/components/chat/message-bubble.tsx` | `여기부터 {turn.switchedTo}로 실행해요`. `switchedTo` 가 무엇을 담는지는 그 값을 만드는 코드를 읽어 확인한다. provider id 가 섞여 있으면 그 부분만 표시 이름으로 바꾼다 |
| `web/src/components/agent/model-hidden-form.tsx` | 목록에 없는 숨김의 `` `${entry.provider} 전체 숨기기` `` 와 `` `${entry.provider} ${entry.model} 숨기기` `` |

이미 표시 이름을 쓰는 곳은 고치지 않는다. `model-picker.tsx` 와 `agent-model-default-form.tsx` 의 `<optgroup label={row.name}>`, `model-hidden-form.tsx` 의 `ProviderHiddenFields` 가 Hermes 목록의 `name` 을 쓴다.
`web/src/components/execution/execution-node.tsx`, `execution-tree.tsx`, `web/src/components/usage/execution-list.tsx` 는 provider 를 타입과 전달에만 쓴다. 화면에 그리는 줄이 있으면 같은 방법으로 바꾸고 「변경 파일」 밖이면 team-lead 에게 보고한다.

**근거 문서**: `web/AGENTS.md` 의 「화면 문구」 용어 표, `docs/model-tiers.md` 머리말

## 의도 메모

- 실행 기록과 사용량 화면에는 Hermes 목록이 없다. 그래서 id 를 이름으로 바꾸는 작은 표를 화면 쪽에 둔다. 화면마다 목록을 읽어 오지 않는다.
- 모르는 id 를 그대로 그리지 않는다. 「다른 제공사」 로 그린다. 새 provider 가 생기면 표에 한 줄을 더한다.
- 표에는 Hermes 가 공개 문서에서 쓰는 provider slug 만 넣는다. 모델 이름은 넣지 않는다.

## 작업 항목

### 1. `web/src/lib/provider-label.ts` (신규)

```ts
/** provider id 를 화면에 그릴 이름으로 바꾼다. 모르는 id 는 「다른 제공사」 다. 비어 있으면 null 이다. */
export function providerLabel(provider: string | null | undefined): string | null
```

- 표: `openai-codex` 는 「ChatGPT 구독」, `openai` 는 「OpenAI」, `anthropic` 은 「Anthropic」, `openrouter` 는 「OpenRouter」, `nvidia` 는 「NVIDIA」, `google` 과 `gemini` 는 「Google」 이다.
  `test/e2e/fake-hermes.ts` 가 쓰는 provider slug 가 이 표에 없으면 그 slug 도 더한다(그 파일의 목록 응답이 주는 `name` 을 쓴다).
- 앞뒤 공백을 떼고 비어 있으면 null 을 돌려준다.

### 2. 위 표의 다섯 파일

- provider 를 그리는 자리를 `providerLabel(...)` 로 바꾼다. null 이면 지금 `"-"` 를 그리던 자리는 `"-"` 를 그대로 그리고, `filter(Boolean)` 으로 빼던 자리는 그대로 뺀다.
- `model-hidden-form.tsx` 의 목록에 없는 숨김은 `providerLabel(entry.provider)` 로 그린다.

### 3. 이 phase 를 검증하는 테스트

- `test/unit/provider-label.test.ts` (신규, `node --test` 로 돈다. `test/unit/` 의 기존 테스트가 `web/src/lib` 을 import 하는 방식을 따른다):
  아는 id 는 표의 이름, 모르는 id 는 「다른 제공사」, 빈 문자열과 null 은 null 이다.
- `test/browser/execution-tree.spec.ts`: 관리자가 실행 상세를 볼 때 「모델」 칸에 표시 이름과 모델 이름이 보이고 provider id 원문이 보이지 않는다.
  이 파일이 실행 나무 응답을 준비하는 기존 방식을 따른다. 기존 단언이 provider id 원문을 기대하면 표시 이름으로 고친다.
- `test/browser/usage.spec.ts`: 관리자의 실행 목록에 표시 이름이 보이고 provider id 원문이 보이지 않는다. 기존 단언이 id 를 기대하면 고친다.
- `test/browser/` 전체에서 provider id 원문을 화면 글자로 기대하는 단언을 `grep` 으로 찾아 함께 고친다. 찾은 파일이 「변경 파일」 에 없으면 team-lead 에게 보고한다.

## 검증

```bash
# cwd: 저장소 root
cd web && pnpm typecheck && pnpm lint && pnpm format:check
node --test 'test/unit/**/*.test.ts'
cd web && pnpm test:browser execution-tree.spec.ts usage.spec.ts
```

- 모두 종료 코드 0 이다.
- `grep -rnE "\{(execution|summary|entry)\.provider" web/src/components` 가 아무것도 내지 않는다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/lib/provider-label.ts` | 신규 |
| `web/src/components/usage/execution-table.tsx` | 수정 |
| `web/src/components/usage/execution-card.tsx` | 수정 |
| `web/src/components/execution/execution-detail.tsx` | 수정 |
| `web/src/components/chat/message-bubble.tsx` | 수정 |
| `web/src/components/agent/model-hidden-form.tsx` | 수정 |
| `test/unit/provider-label.test.ts` | 신규 |
| `test/browser/execution-tree.spec.ts` | 수정 |
| `test/browser/usage.spec.ts` | 수정 |
