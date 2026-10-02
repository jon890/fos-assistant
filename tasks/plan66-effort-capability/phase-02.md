# Phase 02. web 의 지원 표시와 `none` 선택, 임시 호환 칸 제거

**Execution profile**: standard

## 목표

고급 모델 선택 창과 관리자의 에이전트 기본 모델 폼이 새 `reasoning` 표를 읽는다.
지원 미확인을 확인된 지원처럼 보이지 않게 하고, `none` 은 끄기 지원이 확인된 모델에서만 고르게 한다.
phase 01 이 새로 둔 `reasoning` 표로 옮긴다.

**범위 외**: 입력창의 단계 단추(`빠르게`, `균형`, `깊게`) 화면. 거기에는 새 문구를 더하지 않는다.

## 컨텍스트

- 계약은 `docs/backend/conversation.md` 의 `model-options` 표와 `docs/frontend/chat.md` 의 오류 표(「모델의 reasoning 지원이 `UNKNOWN` 이다」 등 다섯 행)에 적혀 있다. 근거는 `docs/adr/ADR-059-reasoning-effort-의-지원은-확인한-것만-보이고-모르면-미확인으로-둔다.md` 다
- `web/src/components/chat/model-picker.tsx`
  - `ModelOptions.providers[].reasoningCapable: Record<string, boolean>` 타입과 `acceptsEffort(options, picked)` 가 `row?.reasoningCapable[model] ?? true` 로 판정한다
  - `changeOpen` 이 창을 열 때 `draftModel`, `draftEffort` 를 대화의 값으로 채운다. 모델 `NativeSelect` 의 `onChange` 는 `setDraftModel` 만 부른다
  - 목록을 읽지 못하면(`options === null`) `acceptsEffort` 가 참이고 effort 목록은 화면 상수 `REASONING_EFFORTS`(`low` 부터 `max`)다
  - `apply` 가 `effortEnabled && draftEffort !== ""` 일 때만 effort 를 보낸다
- `web/src/lib/model-settings.ts` 의 `ModelCatalog.providers` 에는 `reasoning` 이 없다. `web/src/components/agent/agent-model-default-form.tsx` 의 강도 `NativeSelect` 가 `settings.catalog.reasoningEfforts` 를 그린다
- 화면에 provider id(slug)를 그리지 않는다. 이름이 필요하면 Hermes 가 준 `name` 이나 `@/lib/provider-label` 의 `providerLabel` 을 쓴다. 이 phase 의 문구에는 provider 이름이 필요 없다
- 브라우저 검사 선례는 `test/browser/model-choice.spec.ts` 다(`effortSelect(page)` 는 `getByRole("combobox", { name: "effort", exact: true })`). 지금 그 파일의 726 줄 근처가 `/api/chat/model-options` 응답을 `reasoningCapable` 로 가로챈다. 브라우저 검사의 Control Plane 과 Hermes 대역은 `test/browser/fixtures.ts` 가 띄우고 대역은 phase 01 이 바꾼 `test/e2e/fake-hermes.ts` 다
- 로컬 브라우저 검사는 고친 화면과 관련된 spec 만 돌린다. 코디네이터가 준 대기 스크립트가 있으면 돌리기 전에 실행한다. 전체 브라우저 검사는 로컬에서 돌리지 않는다. PR 의 CI 가 한다

**근거 문서**: `docs/adr/ADR-059-reasoning-effort-의-지원은-확인한-것만-보이고-모르면-미확인으로-둔다.md`, `docs/backend/conversation.md`, `docs/frontend/chat.md`

## 의도 메모

- 지원 미확인에서도 effort 선택은 막지 않는다. 막으면 고를 수 있는 것을 못 고르게 된다
- 「지원 미확인」 문구는 고급 모델 선택 창에만 둔다. 단계 단추 화면은 늘리지 않는다
- 모델을 바꿀 때 이전 모델에서 고른 `none` 을 새 모델이 받지 않으면 창의 임시 선택만 「기본」 으로 비운다. 저장된 대화의 값은 사용자가 적용하기 전에는 바꾸지 않는다
- `none` 의 선택지 이름은 「끄기」 로 보인다. 단추 라벨(`modelChoiceLabel`)은 대화에 적힌 값 그대로 `none` 을 보인다

## 작업 항목

### 1. `model-picker.tsx`

- 타입: `providers[].reasoning: Record<string, { support: Support; disable: Support }>` 로 바꾸고 `reasoningCapable` 을 지운다. `type Support = "SUPPORTED" | "UNSUPPORTED" | "UNKNOWN"` 은 이 파일에 둔다
- `capabilityOf(options, picked)` 를 둔다. `acceptsEffort` 를 대체한다. 기본 모델이면 `options.defaultProvider`, `options.defaultModel` 로 찾는다. 목록이 없거나 항목이 없으면 `{ support: "UNKNOWN", disable: "UNKNOWN" }` 다
- `effortEnabled` 는 `support !== "UNSUPPORTED"`(목록이 없으면 참)다. 지금 동작과 같다
- effort 선택지는 `efforts` 에 더해 `capability.disable === "SUPPORTED"` 이고 `effortEnabled` 일 때만 맨 앞에 `none` 을 더한다. 보이는 글자는 「끄기」 다. 대화에 적힌 effort 를 목록에 없을 때 남기는 기존 코드(`model-picker.tsx` 의 `efforts`)는 `none` 을 더한 뒤의 목록으로 판정한다. 그래서 `none` 이 겹쳐 둘로 보이지 않고, 저장된 `none` 이 새 규칙에 걸려도 창이 값을 잃지 않는다. 남긴 `none` 도 「끄기」 로 보인다
- `support === "UNKNOWN"` 이고 목록을 읽은 상태이고 `effortEnabled` 이면 effort 칸 아래에 `data-testid="effort-support-unknown"` 문구 「이 모델의 effort 지원을 확인하지 못했어요. 골라도 모델이 무시할 수 있어요.」 를 `text-xs text-muted-foreground` 로 보인다. 목록을 읽지 못했을 때는 보이지 않는다
- 모델 `NativeSelect` 의 `onChange` 에서 새 모델의 `capabilityOf` 를 구해, 현재 `draftEffort` 가 `none` 이고 새 모델의 `disable` 이 `SUPPORTED` 가 아니면 `setDraftEffort("")` 를 함께 부른다
- `apply` 는 `none` 을 새로 고른 경우에만 검사한다. 모델을 바꿨거나 `draftEffort` 가 저장된 값과 다르고, 선택 모델의 `disable` 이 `SUPPORTED` 가 아니면 effort 를 null 로 보낸다. 바꾼 것 없이 적용하는 경우(저장된 값과 같은 선택)나 목록을 읽지 못한 경우에는 저장된 `none` 을 지우지 않는다. `sameChoice` 는 effort 를 바꾸기 전의 값으로 먼저 판정한다

### 2. `model-settings.ts` 와 `agent-model-default-form.tsx`

- `ModelCatalog.providers[]` 에 `reasoning: Record<string, { support: string; disable: string }>` 를 더한다. `EMPTY_CATALOG` 는 그대로다
- 폼의 강도 `NativeSelect` 는 `profile 값` 다음에 `none` 을 「끄기」 로 더한다. 고른 모델(비웠으면 `settings.catalog.defaultModel`)의 `disable` 이 `SUPPORTED` 일 때만이다. 이미 저장된 강도가 선택지에 없으면 그 값도 둔다
- 모델 `onChange` 에서 새 모델이 `none` 을 받지 않는데 `draftEffort` 가 `none` 이면 `draftEffort` 를 `""` 로 비운다
- 서버가 `none` 을 거절하면(`VALIDATION_FAILED`) 폼이 이미 보이는 저장 실패 알림을 그대로 쓴다

### 3. web 이 `reasoningCapable` 을 읽지 않게 한다

- `grep -rn "reasoningCapable" web test/browser` 가 비어야 한다. `test/browser/model-choice.spec.ts`, `test/browser/agent-model.spec.ts` 의 응답 가로채기에 `reasoning` 표를 넣는다(타입이 `reasoning` 을 필수로 두므로 빠지면 폼이 TypeError 로 깨진다)
- backend 의 `ProviderView.reasoningCapable` 은 옛 web 호환용이라 이번에 지우지 않는다. 다음 배포에서 지운다

### 4. 브라우저 검사 `test/browser/model-choice.spec.ts` 와 `test/browser/agent-model.spec.ts`

- 726 줄 근처의 응답 가로채기를 `reasoning: { "example-model": { support: "SUPPORTED", disable: "UNKNOWN" } }` 로 바꾼다
- 새 시험(phase 01 의 대역 표를 쓴다)
  1. `none` 은 끄기 지원이 `SUPPORTED` 인 기본 모델에서 선택지에 있고, `example-fast`(`disable` 미확인)와 `example-deep`(`UNSUPPORTED`)로 바꾸면 사라진다
  2. 기본 모델에서 `none` 을 골라 둔 채 `example-deep` 으로 모델을 바꾸면 effort 칸이 「기본」 으로 돌아가고, 적용하면 `reasoningEffort` 가 null 로 저장된다
  3. `example-balanced`(`support` 미확인)에서는 effort 칸이 켜져 있고 `effort-support-unknown` 문구가 보인다. `example-model-mini`(`UNSUPPORTED`)에서는 effort 칸이 꺼져 있고 그 문구가 없다
  4. 기본 모델에서 `none` 을 적용하고 보내면 대역이 받은 effort(`lastSubmittedRuntime().reasoningEffort`)가 `none` 이고, 「기본」 으로 되돌려 보내면 `undefined` 다
  5. 저장된 `none` 이 있는 대화에서 창을 열면 선택지에 `none` 이 겹쳐 하나만 있고, 바꾼 것 없이 적용해도 `none` 이 지워지지 않는다
  6. `agent-model.spec.ts`: 관리자 폼에서 끄기 지원이 `SUPPORTED` 인 모델에서만 「끄기」 가 보이고, 그 상태로 `none` 을 고른 뒤 지원하지 않는 모델로 바꾸면 강도가 `profile 값` 으로 돌아가며, 서버가 `none` 을 거절하면 저장 실패 알림이 보인다. 이 파일의 응답 가로채기(222 줄 근처)에도 `reasoning` 표를 넣는다
- 입력창의 단계 단추 화면에는 `effort-support-unknown` 이 없다는 것을 첫 시험 중 한 곳에서 확인한다

### 5. 문서

- `docs/adr/INDEX.md` 의 ADR-059 행에서 「아직 구현 전이다」 를 지운다. ADR-059 파일에는 구현 전이라는 말이 없다

## 검증

```bash
# cwd: 저장소 root. 환경 변수는 web/AGENTS.md 「검사」 와 scripts/check-local.sh 가 쓰는 값을 그대로 준다
cd web && pnpm typecheck && pnpm build
cd web && pnpm test:browser model-choice
cd web && pnpm test:browser agent-model
cd backend && ./gradlew test
node test/e2e/run.ts
node --test 'test/unit/**/*.test.ts'
scripts/quality.sh check
scripts/check-public-safe.sh
```

기대값: 모두 종료 코드 0. 전체 브라우저 검사는 돌리지 않는다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `web/src/components/chat/model-picker.tsx` | 수정 |
| `web/src/lib/model-settings.ts` | 수정 |
| `web/src/components/agent/agent-model-default-form.tsx` | 수정 |
| `test/browser/model-choice.spec.ts` | 수정 |
| `test/browser/agent-model.spec.ts` | 수정 |
| `docs/adr/INDEX.md` | 수정 |
