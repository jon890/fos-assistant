# Phase 01. 입력창 옆 단추로 대화의 모델과 effort 를 고른다

**Execution profile**: standard

## 목표

대화 화면의 입력창 옆에 지금 모델과 effort 를 보이는 단추를 두고, 누르면 고르는 창을 연다.
고른 값은 그 대화에 저장되어 다음 보내기부터 쓰인다.

**범위 외**: 사용량 화면의 effort 는 phase 02 다.

## 컨텍스트

**근거 문서**: `docs/flow.md` 「모델을 고를 때」 와 「갈리는 지점」, 「새 대화 화면」, `docs/code-architecture.md` 「대화의 모델 선택」

- backend 경로는 이미 있다. `GET /api/v1/chat/model-options?agentCode=`, `PUT /api/v1/chat/conversations/{id}/model`, `ConversationView` 의 `provider`, `model`, `reasoningEffort` 칸, web 서버 라우트 `web/src/app/api/chat/conversations/[conversationId]/model/route.ts` 가 있다. 구현 전에 지금 모양을 읽는다
- model-options 응답은 `{ defaultProvider, defaultModel, providers: [{ provider, name, models, reasoningCapable }], reasoningEfforts }` 다. `reasoningCapable` 은 모델 이름을 열쇠로 한 참거짓 표이고 값이 없는 모델은 참으로 본다. 목록을 읽지 못하면 `HERMES_UNAVAILABLE` 이다
- 입력창은 `web/src/components/chat/composer.tsx` 이고 `web/src/components/chat-panel.tsx` 가 `<Composer key={composerGeneration} ...>` 로 그린다. 대화가 아직 없을 때 사진을 먼저 올리면 `ensureConversationId()` 가 빈 대화를 만들고 그 안에서 `onConversationCreated` 를 부른다. 모델을 먼저 고를 때도 이 함수를 쓴다
- 대화 목록 타입은 `web/src/components/shell/conversations-provider.tsx` 의 `Conversation` 이다. 이름 바꾸기는 `rename` 이 응답으로 그 줄을 바꾼다
- 브라우저는 Control Plane 토큰을 갖지 않는다. 새 서버 라우트는 `web/src/lib/control-plane.ts` 의 `callControlPlane` 을 쓴다(`web/AGENTS.md`)
- 부품은 `web/src/components/ui/` 의 `dropdown-menu.tsx`, `dialog.tsx`, `sheet.tsx`, `native-select.tsx` 가 있다. 화면 문구는 해요체다(`web/AGENTS.md` 「화면 문구」). 화면에서 provider 는 「모델 제공사」 라 부른다
- 가짜 Hermes(`test/e2e/fake-hermes.ts`)의 모델 목록은 provider `openai-codex` 하나에 `example-model`(기본, reasoning 참)과 `example-model-mini`(reasoning 거짓)다. 마지막 실행 요청의 provider, 모델, effort 는 `lastSubmittedRuntime()` 에 있지만 브라우저 검사의 `FakeHermesControl`(`test/browser/fixtures.ts`)에는 아직 없다. 브라우저 검사는 `workers: 1` 로 차례로 돈다
- Control Plane 은 목록을 profile 마다 10분 들고 있어서, 가짜 Hermes 를 실패시켜도 앞선 검사가 읽은 목록이 돌아온다

## 의도 메모

- **목록은 창을 열 때 읽는다(docs 순서).** 그래서 창을 열기 전 단추는 기본 모델 이름을 알 수 없다
- 단추 글자는 대화에 적힌 값만으로 정한다
  - 셋 다 비었다: 「기본」
  - 모델은 비었고 effort 만 있다: 「기본 · high」
  - 모델이 있다: 「example-model · high」, effort 가 비었으면 「example-model」
- 창 맨 위 항목은 「기본 (example-model)」 처럼 기본 모델 이름을 붙인다. `defaultModel` 이 null 이거나 목록을 읽지 못했으면 「기본」 이다. 그 아래에 provider 의 `name` 을 머리글로 모델을 나열한다
- effort 는 「기본, low, medium, high, xhigh, max」 에서 고른다. 고른 모델의 `reasoningCapable` 이 거짓이면 effort 를 「기본」 으로 두고 고르지 못하게 한다
- 모델이 「기본」 이면 `defaultModel` 의 `reasoningCapable` 을 따른다. 그 값을 찾지 못하거나 목록을 읽지 못했으면 effort 를 고를 수 있다. backend 가 모르는 모델을 참으로 보는 것과 맞춘다
- 목록을 읽지 못하면 창 안에 「모델 목록을 불러오지 못했어요. 기본 모델로는 계속 보낼 수 있어요.」 를 보이고 모델은 「기본」 만 고를 수 있게 한다
- 답이 도는 중에도 바꿀 수 있다. 바꾼 값은 다음 보내기부터다
- 저장하는 동안 단추는 고른 값을 먼저 보인다. 저장이 실패하면 이전 값으로 돌아가고 오류 안내를 보인다. 오류 글자는 `muted-foreground` 나 `destructive` 를 쓰고 `danger` 토큰은 쓰지 않는다
- **단추 자리는 입력 알약(`composer-shell`) 아래 한 줄**이다. 알약 안에 넣으면 390px 에서 입력칸이 좁아진다. 긴 모델 이름은 한 줄로 줄여(`truncate`) 가로로 넘치지 않게 한다
- 단추는 `agentCode` 가 비었거나 에이전트가 없을 때 막는다. `agentCode` 가 빈 채로 빈 대화를 만들면 POST 가 실패한다
- **첨부와 입력창의 불변 조건을 지킨다**
  - `chat-panel` 은 `<Composer key={composerGeneration}>` 을 유지한다. `conversationId` 를 key 로 쓰지 않는다
  - `Composer` 가 unmount 될 때 올려 둔 첨부를 서버에서 지우는 정리를 건드리지 않는다
  - `await ensureConversationId()` 뒤에는 `handleFiles` 처럼 `mountedRef.current` 를 확인한 다음 PUT 과 `onModelChoiceSaved` 를 부른다
- 새 대화 화면에서 모델을 먼저 고르면 빈 대화가 생기고, 사진을 먼저 올릴 때처럼 에이전트 카드와 `@` 가 잠긴다. `docs/flow.md` 「새 대화 화면」 의 잠김 문장에 모델을 먼저 고를 때도 같다고 더한다

## 작업 항목

### 1. 서버 라우트 `web/src/app/api/chat/model-options/route.ts`

`GET` 이 `agentCode` 쿼리를 받아 `encodeURIComponent` 로 `/api/v1/chat/model-options?agentCode=` 에 넘긴다.
`agentCode` 가 없으면 400 `VALIDATION_FAILED` 이고 message 는 「에이전트를 골라 주세요.」 다.

### 2. 고르는 부품 `web/src/components/chat/model-picker.tsx`

- 속성: `agentCode`, `choice: { provider, model, reasoningEffort } | null`, `onChange(choice): Promise<boolean>`, `disabled`
- 위 의도 메모의 단추와 창과 실패 표시를 그린다. `data-testid="model-picker"` 를 단추에 붙인다

### 3. composer 와 chat-panel

- `Composer` 가 `modelChoice` 와 `onModelChoiceSaved(conversation)` 를 받아 알약 아래 줄에 `ModelPicker` 를 그린다
- 고르면 `ensureConversationId()` 로 대화를 확보한 뒤 `PUT /api/chat/conversations/{id}/model` 을 부른다. 대화 생성은 `ensureConversationId` 가 알리므로 `onConversationCreated` 를 다시 부르지 않는다
- `conversations-provider.tsx` 의 `Conversation` 에 `provider`, `model`, `reasoningEffort` 를 더한다(모두 `string | null`). `rename` 과 같은 방식의 `replace(conversation)` 을 두어 PUT 응답으로 그 줄을 바꾸고, 목록에 없으면 앞에 더한다
- `chat-panel.tsx` 는 지금 대화 한 줄의 세 칸을 `modelChoice` 로 넘기고, 저장된 뒤 `replace` 로 목록을 맞춘다. 순서가 보장되지 않는 `refresh` 로 대신하지 않는다
- 새 대화에서 고르면 `onConversationCreated` 의 목록 다시 읽기와 PUT 이 함께 나간다. 늦게 온 목록 응답이 `replace` 한 줄을 덮지 않게 provider 의 `refresh` 에 요청 순번을 두어 옛 응답을 버린다

### 4. 가짜 Hermes 제어

- `test/e2e/fake-hermes.ts` 에 `GET /__test/last-submitted-runtime` 을 두어 `lastSubmittedRuntime` 을 JSON 으로 돌려준다
- `test/browser/fixtures.ts` 의 `FakeHermesControl` 에 `lastSubmittedRuntime()` 을 더한다

### 5. 이 phase 를 검증하는 브라우저 검사 `test/browser/model-choice.spec.ts`

- 새 대화에서 단추가 「기본」 을 보인다. 창을 열면 맨 위가 「기본 (example-model)」 이다. `example-model` 과 `high` 를 고르고 보내면 가짜 Hermes 의 마지막 실행 요청이 provider `openai-codex`, model `example-model`, effort `high` 를 싣는다. `page.reload()` 뒤에도 단추가 「example-model · high」 를 보인다
- 기본값으로 보내면 마지막 실행 요청에 provider 와 model 이 빠진다
- `example-model-mini` 를 고르면 effort 를 고를 수 없다
- 브라우저에서 나가는 `/api/chat/model-options` 요청을 `page.route` 로 502 `{ code: "HERMES_UNAVAILABLE" }` 로 바꾸면 창에 실패 안내가 보이고, 기본값으로 보내기는 된다. Control Plane 의 옛 목록 반환은 backend 의 `ModelOptionsServiceTest` 가 확인한다
- 새 대화에서 모델을 먼저 고르면 에이전트 카드가 잠기고, 그 뒤 사진을 올리고 보내도 된다
- 좁은 폭에서 가로 넘침이 없고 입력칸(`textarea`) 폭이 160px 이상이다

## 검증

`AGENTS.md` 「확인」 절의 여섯 명령을 적힌 순서대로 모두 돌린다. 새 워크트리라 `web` 에서 `pnpm install --frozen-lockfile` 이 먼저 필요하다. 첫 줄은 이 phase 의 검사만 먼저 돌리는 것이다.

```bash
# cwd: 저장소 root
cd web && pnpm test:browser model-choice.spec.ts
cd backend && ./gradlew test
cd web && pnpm typecheck
cd web && AUTH_SECRET=build-time-placeholder ASSISTANT_JWT_SECRET=build-time-placeholder CONTROL_PLANE_BASE_URL=http://build-time-placeholder AUTH_GOOGLE_ID=build-time-placeholder AUTH_GOOGLE_SECRET=build-time-placeholder pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
```

- 모두 통과한다

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `web/src/app/api/chat/model-options/route.ts` | 신규 |
| `web/src/components/chat/model-picker.tsx` | 신규 |
| `web/src/components/chat/composer.tsx` | 수정 |
| `web/src/components/chat-panel.tsx` | 수정 |
| `web/src/components/shell/conversations-provider.tsx` | 수정 |
| `test/browser/model-choice.spec.ts` | 신규 |
| `test/browser/fixtures.ts` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `docs/flow.md` | 수정 |
