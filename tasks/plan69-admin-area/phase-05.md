# Phase 05. 그룹 모델 설정과 모델 숨김을 /admin/models 로 옮긴다

**Execution profile**: standard

## 목표

대화 입력창의 설정 창 안에 있던 「그룹 모델 설정」 을 관리자 영역의 「모델」 화면으로 옮기고, 같은 화면에서 모델 숨김도 고치게 한다.

**범위 외**: 내 기본 단계와 고급 모델 선택(일반 화면에 남는다). 에이전트 기본 모델(phase 03 이 옮긴 `/admin/agents/{code}` 의 「모델」 절에 남는다). backend.

## 컨텍스트

- `web/src/components/chat/model-picker.tsx` 의 단계 선택 부품이 설정 창 아래에 `state.data?.admin` 일 때 「그룹 모델 설정」 단추와 `Dialog` 를 그린다. 상태는 `groupOpen`, `groupSaveFailed`, `draftTiers`, `groupDefaultTier`, `needsTierSetup`, `hasIncompleteTierMapping` 이고 저장은 `saveGroup()` 이 `saveGroupTiers(draftTiers, groupDefaultTier)`(`web/src/lib/model-tiers.ts`)를 부른다
- 단계 정의와 그룹 기본 단계는 `getModelTiers(agentCode)` 가 읽는다. 조회에 `agentCode` 가 필요한 것은 이 함수뿐이고, 양식은 provider 와 모델과 강도를 글자 입력칸으로 받는다. 어느 에이전트로 읽어도 그룹의 같은 정의가 온다
- 모델 숨김 양식은 `web/src/components/agent/model-hidden-form.tsx` 의 `ModelHiddenForm({ settings, onSaved })` 이고, `settings` 는 `getAgentModelSettings(code)`(`web/src/lib/model-settings.ts`)가 준다. 지금은 `web/src/components/agent/agent-model-section.tsx` 가 에이전트 기본 모델 양식과 함께 그린다
- 관리자 목록은 `GET /api/admin/agents` 다

**근거 문서**: `docs/frontend/structure.md` 의 「관리자 영역」 절, `docs/model-tiers.md`

## 의도 메모

- 그룹 모델 설정의 양식과 검증과 문구는 그대로 옮긴다. 대화상자가 아니라 화면의 절이 된다
- `/admin/models` 는 에이전트를 고르는 칸을 둔다. 그 칸은 「모델 숨김」 절이 보일 모델 목록을 정한다. 목록이 에이전트의 profile 마다 다르기 때문이다. 「그룹 모델 설정」 절은 고른 에이전트의 코드로 조회만 하고 내용은 달라지지 않는다. 두 설정이 그룹 전체에 걸린다는 안내를 한 줄 둔다
- 모델 숨김은 `/admin/agents/{code}` 의 「모델」 절에도 그대로 남긴다. 숨길 대상의 목록이 에이전트마다 달라 그 자리에서도 고친다. 양식은 같은 `ModelHiddenForm` 이다

## 작업 항목

### 1. `web/src/components/admin/group-tier-settings.tsx` (신규)

`GroupTierSettings({ agentCode })`. `model-picker.tsx` 의 그룹 모델 설정 양식(그룹 기본 단계, 단계별 모델, 불완전한 mapping 의 안내, 저장 실패 안내)을 옮긴다.
`agentCode` 가 바뀌면 다시 읽는다. 저장에 성공하면 「저장했어요」 를 `Notice` 로 보인다.

### 2. `web/src/components/admin/model-admin-panel.tsx` (신규), `web/src/app/admin/models/page.tsx` (신규), `web/src/app/admin/models/loading.tsx` (신규)

- `page.tsx` 는 서버에서 `/api/v1/admin/agents` 를 읽어 사용 중인 에이전트의 `code` 와 `name` 을 넘긴다. 403 이면 `/` 로 넘긴다
- `ModelAdminPanel` 은 제목 「모델」, 에이전트를 고르는 `NativeSelect`(라벨 「모델 목록을 읽을 에이전트」), 절 「그룹 모델 설정」(`GroupTierSettings`), 절 「모델 숨김」(`getAgentModelSettings` 로 읽어 `ModelHiddenForm`)을 그린다
- 에이전트가 하나도 없으면 「먼저 에이전트를 등록해 주세요.」 를 보인다

### 3. `web/src/components/chat/model-picker.tsx`

그룹 모델 설정의 단추, 창, 상태, `saveGroup`, 「단계별 모델을 아직 정하지 않았어요」 안내를 지운다. `saveGroupTiers` import 도 지운다. 쓰지 않게 된 import 와 상태가 남지 않게 한다.

### 4. `test/unit/loading-routes.test.ts`

`ROUTE_FRAMES` 에 `"admin/models": "components/admin/model-admin-panel.tsx"` 를 더한다. `model-admin-panel.tsx` 의 바깥 틀 폭과 `admin/models/loading.tsx` 의 `width` 가 같아야 한다.

### 5. 이 phase 를 검증하는 브라우저 검사

`test/browser/admin-area.spec.ts` 에 더한다.

- `ADMIN` 의 대화 화면 설정 창에 「그룹 모델 설정」 단추가 없다
- `/admin/models` 에서 그룹 기본 단계를 바꿔 저장하고 새로 고치면 그 값이 남아 있다
- `/admin/models` 에 모델 숨김 양식이 보인다

기존 검사에서 대화 화면의 「그룹 모델 설정」 을 여는 자리를 `/admin/models` 로 옮긴다. 대상: `test/browser/model-choice.spec.ts`, `agent-model.spec.ts` 가운데 실제로 걸리는 것. 아래로 찾는다.

```bash
grep -rn "그룹 모델 설정" test/browser
```

## 검증

```bash
# cwd: 저장소 root
(cd web && pnpm typecheck && pnpm lint)
node --test 'test/unit/**/*.test.ts'
(cd web && pnpm test:browser admin-area.spec.ts model-choice.spec.ts agent-model.spec.ts)
scripts/check-public-safe.sh
```

모두 종료 코드 0 이다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/components/admin/group-tier-settings.tsx` | 신규 |
| `web/src/components/admin/model-admin-panel.tsx` | 신규 |
| `web/src/app/admin/models/page.tsx` | 신규 |
| `web/src/app/admin/models/loading.tsx` | 신규 |
| `web/src/components/chat/model-picker.tsx` | 수정 |
| `test/unit/loading-routes.test.ts` | 수정 |
| `test/browser/admin-area.spec.ts` | 수정 |
| `test/browser/model-choice.spec.ts` | 수정 |
