# Phase 02. 화면의 목록 새로 읽기, 세로 목록, 예약 작업 화면과 문서

**Execution profile**: standard

## 목표

관리자 「모델」 화면에 「목록 새로 읽기」 를 두고, 입력창의 모델 단계 선택을 세로 목록으로 바꿔 줄마다 모델과 강도를 보이며, 예약 작업 화면의 선택지에도 같은 값을 붙인다. 바뀐 동작을 기능 문서에 적는다.

**범위 외**: Control Plane 변경(phase 01 에서 끝났다). 새 모델 알림(뒤 PR).

## 컨텍스트

- 관리자 화면은 `ModelAdminPanel` 이 두 절을 그린다. 「그룹 모델 설정」 은 `GroupTierSettings` 가 스스로 단계 정의와 그룹 목록을 읽고, 「모델 숨김」 은 부모가 읽은 설정을 `ModelHiddenForm` 에 준다.
- 입력창의 선택은 `ModelTierPicker` 다. radio 묶음 `TierSlider` 가 가로 슬라이더를 그리고, `choose` 가 저장과 겹친 저장을 처리한다.
- 예약 작업 화면 `TaskForm` 은 `MODEL_TIER_LABELS` 의 다섯 단계를 고정 문구로 그린다. 에이전트 목록은 `fetchChatAgents` 로 이미 읽는다.
- 브라우저의 요청은 `web/src/app/api/` 의 서버 라우트를 거친다(`docs/code-rules/rule-55.md`). 기존 `web/src/app/api/admin/model-catalog/route.ts` 가 본보기다.
- phase 01 이 만든 것: `POST /api/v1/admin/model-catalog/refresh` 가 `{status, refreshedAt}` 을 주고, `GET /api/v1/chat/model-tiers` 가 `MEMBER` 역할에게도 `model` 과 `reasoningEffort` 를 준다.

**요구사항**: REQ1, REQ2, REQ3, REQ4, REQ5

**근거 문서**: `docs/features/model-usage/spec.md`, `docs/features/model-usage/design.md` 의 「다시 읽기 경로」(화면의 처리 표 둘), 「세로 목록」, 「문구 함수」, 「예약 작업 화면」

## 의도 메모

- 세로 목록은 radio 묶음을 유지한다. 메뉴 부품으로 바꾸지 않는다(설계 DECISION5).
- 「목록 새로 읽기」 는 두 절 위에 하나만 둔다(설계 DECISION2).
- 다시 읽은 뒤에도 저장 전에 고친 단계 선택과 숨김 체크를 지우지 않는다. 숨김 폼을 다시 만들지 않는다(`docs/code-rules/rule-69.md`).
- `REUSED` 안내는 「방금 읽었다」 고 말하지 않는다. 30초 안에는 새로 묻지 않는다고 말한다.
- 줄에 모델을 더해도 radio 의 접근성 이름은 단계 이름 그대로 둔다. 기존 테스트가 `getByRole("radio", { name })` 으로 고른다. 모델 줄은 `aria-describedby` 로 붙인다.
- 화면 문구는 `docs/code-rules/rule-58.md` 의 말투와 제품 용어를 따른다.

## 작업 항목

### 1. 서버 라우트와 lib 함수

- `web/src/app/api/admin/model-catalog/refresh/route.ts`(신규): `POST` 를 Control Plane 의 같은 경로로 넘긴다.
- `web/src/lib/model-settings.ts`: 다시 읽기를 부르는 함수(`status`, `refreshedAt` 을 돌려주고 요청 실패는 실패 결과)와 숨김 후보만 다시 읽는 함수를 더한다.
- `web/src/lib/model-tiers.ts`: `tierModelLine(tier)` 를 더한다. `<모델> · <강도>`, 강도가 비면 모델만, 모델이 비면 null 이다.

### 2. `ModelAdminPanel` 과 `GroupTierSettings`

- 두 절 위에 「목록 새로 읽기」 단추(`data-testid="model-catalog-refresh"`)와 안내 자리(`data-testid="model-catalog-refresh-notice"`)를 둔다. 읽는 동안 단추를 막고 「읽는 중」 을 보인다(`Button` 의 `loading`, `loadingText`).
- 응답 처리는 설계 「다시 읽기 경로」 의 화면 표를 따른다. `FAILED` 와 요청 실패는 조회를 다시 하지 않는다.
- 안내 문구:

  | 경우 | 문구 |
  | --- | --- |
  | `REFRESHED` | 모델 목록을 다시 읽었어요. |
  | `REUSED` | 30초 안에는 새로 묻지 않아요. 잠시 뒤 다시 눌러 주세요. |
  | `FAILED` 이거나 요청 실패 | 모델 목록을 다시 읽지 못했어요. 잠시 뒤 다시 눌러 주세요. (`role="alert"`) |
  | 다시 읽은 뒤 목록이나 후보 조회가 실패 | 다시 읽은 목록을 불러오지 못했어요. 보이던 목록을 그대로 둬요. (`role="alert"`) |

- `refreshedAt` 이 있으면 안내 아래에 「마지막으로 다시 읽은 시각 <시각>」 을 보인다(`data-testid="model-catalog-refreshed-at"`). 시각은 `web/src/lib/format.ts` 의 `formatFullTime` 으로 만든다. null 이면 그리지 않는다.
- 성공하면 숨김 후보만 다시 읽어 `ModelHiddenForm` 에 준다. 폼은 그린 채로 둔다. 후보 조회가 실패하거나 목록 없이 오면 앞 후보를 두고 실패 안내를 보인다.
- `GroupTierSettings` 는 부모가 올린 다시 읽기 횟수를 prop 으로 받아 그룹 목록만 다시 읽는다. 받은 목록이 null 이면 앞 목록을 둔다. 고치던 단계 선택은 그대로다.

### 3. `ModelTierPicker` 를 세로 목록으로 바꾼다

- `TierSlider` 를 세로 radio 목록으로 바꾸고 이름도 맞춘다. 줄은 단계 이름, 그 아래 `tierModelLine` 의 값, 오른쪽의 체크 표시다. 둘째 줄은 한 줄로 두고 넘치면 줄임표로 끝낸다.
- radio 의 `data-testid="model-tier-<단계>"` 는 유지한다. 팝오버의 `data-testid` 는 `model-tier-list` 로 바꾼다.
- 닫힌 단추, 팝오버의 제목과 안내 문구, `choose` 의 저장 흐름은 그대로다.
- 「칸 뒤의 provider 와 모델, effort 는 역할과 상관없이 그리지 않는다」 는 주석을 새 동작으로 고친다.

### 4. `TaskForm` 의 선택지 문구

- 에이전트 목록이 오면 첫 에이전트 코드로 `getModelTiers` 를 한 번 읽는다. 목록이 비었으면 읽지 않는다. 화면을 떠난 뒤 온 응답은 버린다.
- 채운 단계는 `<단계 이름> · <모델> · <강도>`, 응답에 없는 단계와 조회 실패는 단계 이름만 보인다. 「기본값」 선택지와 저장 값은 그대로다.

### 5. 브라우저 테스트

spec 은 실제 Control Plane 과 가짜 Hermes 위에서 돌고, 사례마다 `page.route` 로 `/api/...` 응답을 바꾼다. `fixtures.ts` 와 `helpers.ts` 는 고치지 않는다.

- `web/test/browser/model-choice.spec.ts`:
  - 슬라이더를 전제한 사례의 이름과 선택자(`model-tier-slider` → `model-tier-list`), 화면 캡처 이름을 고친다.
  - 「칸 뒤의 모델은 그리지 않는다」 를 보던 단언(`not.toContainText("example-")`)을 뒤집어 줄마다 `<모델> · <강도>` 가 보이는 것을 본다. 같은 사례에서 닫힌 단추가 단계 이름만 보이는 것과 provider 이름(`openai-codex`)이 목록에 없는 것도 본다.
  - `namesOnly` 는 새 `MEMBER` 모양(`provider` 만 null)으로 고치고 이름도 맞춘다. 세 값이 모두 빈 응답(옛 Control Plane)은 둘째 줄을 그리지 않는 사례 하나로 남긴다.
  - 새 사례: 고른 줄의 체크 표시, 강도가 빈 단계는 모델만 보이는 것, 폭 360px 에서 다섯 단계 이름이 잘리지 않는 것(이름 요소의 `scrollWidth` 가 `clientWidth` 를 넘지 않는다).
- `web/test/browser/admin-area.spec.ts`: `model-tier-slider` 선택자를 `model-tier-list` 로 고친다.
- `web/test/browser/admin-model-tiers.spec.ts`: 다시 읽기 경로와 목록 조회를 `page.route` 로 바꿔 아래를 본다.
  - 누르면 선택지에 새 모델이 생기고 `REFRESHED` 안내와 시각이 보이는 것. 응답을 늦춰 그동안 단추가 막힌 것.
  - `FAILED` 뒤 선택지가 그대로이고 실패 안내가 보이며 목록 조회를 다시 하지 않는 것. 요청 자체가 500 일 때도 같다.
  - `REUSED` 안내와 시각. `refreshedAt` 이 null 이면 시각이 없는 것.
  - 다시 읽은 뒤 저장 전에 고친 단계 선택이 남는 것.
- `web/test/browser/admin-model-hidden.spec.ts`: 다시 읽은 뒤 숨김 후보가 바뀌고 저장 전 체크가 남는 것. 후보 조회가 실패하면 앞 후보가 남고 안내가 보이는 것.
- `web/test/browser/tasks.spec.ts`: 채운 단계, 비운 단계, 「기본값」 의 선택지 문구. 기존 사례의 `selectOption({ label: "Medium" })` 은 문구가 바뀌어 깨지므로 값(`"MEDIUM"`)으로 고르게 고친다.

### 6. 문서

- `docs/features/model-usage.md`: 「요구」 와 「모델을 고를 때」 의 슬라이더와 「사용자 화면에는 모델 이름이 없다」 를 새 동작으로 고치고, 「그룹 모델 목록」 에 목록 새로 읽기와 결과 셋을 적는다. `MEMBER` 역할의 응답 설명(「API 계약」)도 고친다. covers 줄에 예약 작업 화면은 더하지 않는다. 그 화면은 `schedule.md` 가 갖는다.
- `docs/features/chat.md`: 「역할에 따라 응답에서 빼는 값」 표의 단계 목록 줄을 `provider` 만으로 고친다.
- `docs/prd.md`: 「모델 단추의 슬라이더로」 를 세로 목록과 모델 표시로 고친다.
- `docs/features/schedule.md`: 「모델 단계」 에 선택지가 모델과 강도를 함께 보인다는 것을 적는다.
- `hermes/docs/hermes-contract.md`: Codex 모델 목록 절에 v0.23.2 가 gpt-6.2 계열의 강도 `max` 를 `xhigh` 로 낮춰 보낸다는 것을 적는다(스펙 DECISION17). 근거는 `agent/reasoning_effort.py` 의 `codex_supported_efforts` 다.

## 검증

```bash
cd web && pnpm lint && pnpm format:check && pnpm typecheck && pnpm test:node
cd web && pnpm test:browser test/browser/model-choice.spec.ts test/browser/admin-model-tiers.spec.ts test/browser/admin-model-hidden.spec.ts test/browser/tasks.spec.ts test/browser/admin-area.spec.ts
scripts/dev/check-local.sh --fast
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/app/api/admin/model-catalog/refresh/route.ts` | 신규 |
| `web/src/lib/model-settings.ts` | 수정 |
| `web/src/lib/model-tiers.ts` | 수정 |
| `web/src/components/admin/model-admin-panel.tsx` | 수정 |
| `web/src/components/admin/group-tier-settings.tsx` | 수정 |
| `web/src/components/chat/model-picker.tsx` | 수정 |
| `web/src/components/task/task-form.tsx` | 수정 |
| `web/test/browser/model-choice.spec.ts` | 수정 |
| `web/test/browser/admin-model-tiers.spec.ts` | 수정 |
| `web/test/browser/admin-model-hidden.spec.ts` | 수정 |
| `web/test/browser/tasks.spec.ts` | 수정 |
| `web/test/browser/admin-area.spec.ts` | 수정 |
| `docs/features/model-usage.md` | 수정 |
| `docs/features/chat.md` | 수정 |
| `docs/prd.md` | 수정 |
| `docs/features/schedule.md` | 수정 |
| `hermes/docs/hermes-contract.md` | 수정 |
