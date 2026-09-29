# Phase 01. 화면에서 에이전트 모델 목록, 모델 다시 읽기, 막힌 provider 알림을 지운다

**Execution profile**: standard

## 목표

관리자 화면이 에이전트의 모델 목록과 막힌 provider 를 더 읽지 않게 한다.
backend 의 경로를 지우는 phase 02 가 화면을 깨지 않도록 먼저 떼어 낸다.

**범위 외**: backend 의 경로와 표와 칸은 phase 02 다. 에이전트 등록 양식의 「모델 제공사」 칸은 backend 의 등록 요청이 아직 `provider` 를 요구하므로 phase 02 에서 함께 지운다. 대화의 모델 고르기 화면은 이 plan 이 다루지 않는다.

## 컨텍스트

**근거 문서**: `docs/code-architecture.md` 「에이전트 화면」, `docs/adr/ADR-030-모델과-effort-는-대화가-고르고-기본값은-hermes-profile-이-갖는다.md`

- ADR-030 의 대화별 모델 선택이 main 에 있다. 실행은 더 이상 에이전트의 모델 목록을 읽지 않는다. 시작 전에 `git merge origin/main` 을 하고 지금 모양을 읽는다
- 모델 목록 편집은 `web/src/components/admin/agent-model-list.tsx` 이고, `web/src/components/agent/agent-admin-section.tsx` 가 그것과 「모델 목록 다시 읽기」 단추(`/api/admin/agents/{code}/sync-model`)를 그린다
- 막힌 provider 알림은 `web/src/components/agent/agent-admin-panel.tsx` 가 `/api/admin/providers/blocked` 를 읽어 `data-testid="blocked-providers"` 로 그린다
- 타입은 `web/src/lib/agent.ts` 의 `AgentModel`, `BlockedProvider`, `AdminAgent` 의 `model`, `modelSyncedAt`, `models` 칸이고, 막힌 provider 알림만 쓰는 `formatRemaining` 도 같은 파일에 있다. `AdminAgent.provider` 는 등록 양식이 쓰므로 phase 02 까지 둔다
- 관리자 카드 `web/src/components/admin/agent-card.tsx` 가 `agent.model` 을 한 줄로 보인다
- 브라우저 검사 `test/browser/admin.spec.ts` 의 「모델 목록을 고쳐 저장하면 그 순서로 남는다」, 「막힌 provider 가 없으면 그 줄을 그리지 않고 목록이 가로로 넘치지 않는다」, 「상세 관리 절에서 사용 여부를 바꾸고 모델을 다시 읽는다」 안의 「모델 목록 다시 읽기」 단계, 「관리자 에이전트 목록에서 등록한 에이전트가 보인다」 의 카드 모델 글자 단언과 `test/browser/fixtures.ts` 의 `MODELS_AGENT_CODE` 준비가 이 기능을 쓴다

## 의도 메모

- 관리 절에서 모델 자리를 비워 두지 않는다. 「모델은 대화에서 골라요」 같은 안내도 두지 않는다. 대화 화면의 단추가 스스로 설명한다
- 가로 넘침 검사는 막힌 provider 와 무관하게 남긴다. 이름에서 막힌 provider 를 뺀다

## 작업 항목

### 1. 지울 파일

`web/src/components/admin/agent-model-list.tsx`, `web/src/app/api/admin/agents/[code]/models/route.ts`, `web/src/app/api/admin/agents/[code]/sync-model/route.ts`, `web/src/app/api/admin/providers/blocked/route.ts`

### 2. 고칠 파일

- `agent-admin-section.tsx`: 모델 목록과 다시 읽기 단추, 그 상태와 저장 함수를 지운다
- `agent-admin-panel.tsx`: 막힌 provider 읽기와 알림을 지운다
- `agent-card.tsx`: 모델 한 줄을 지운다
- `web/src/lib/agent.ts`: `AgentModel`, `BlockedProvider`, `formatRemaining`, `AdminAgent.model`, `modelSyncedAt`, `models` 를 지운다

### 3. 이 phase 를 검증하는 브라우저 검사 `test/browser/admin.spec.ts`

- 「모델 목록을 고쳐 저장하면 그 순서로 남는다」 를 지운다
- 막힌 provider 검사를 「에이전트 목록이 가로로 넘치지 않는다」 로 바꾼다
- 「상세 관리 절에서 사용 여부를 바꾸고 모델을 다시 읽는다」 를 「상세 관리 절에서 사용 여부를 바꾼다」 로 이름을 바꾸고 모델 다시 읽기 단계를 지운다. 같은 검사에서 관리 절에 「모델 목록 다시 읽기」 단추와 `agent-model-list` 가 없는 것을 단언한다
- 「관리자 에이전트 목록에서 등록한 에이전트가 보인다」 의 `card.getByText(registered.model …)` 가 보인다는 단언을, 카드에 그 모델 글자가 없다(`toHaveCount(0)`)는 단언으로 바꾼다. 모의 값 `registered.model` 은 이 단언을 위해 남긴다. 등록 양식의 「모델 제공사」 칸 입력은 phase 02 까지 둔다
- 같은 파일의 모의 응답에 `models`, `model`, `modelSyncedAt` 이 있으면 뺀다. 카드에 모델 글자가 없다는 단언에 쓰는 `registered.model` 은 남긴다
- `test/browser/fixtures.ts` 의 `MODELS_AGENT_CODE`, 그 에이전트 준비, `PROFILE_KEYS` 의 `browsermodels` 줄을 함께 지운다
- `test/browser/identity.spec.ts` 가 primary 단추 색을 「모델 목록 저장」 단추로 검사한다. 그 단추가 없어지므로 같은 화면의 기본 단추 「소개와 추천 질문 저장」 으로 바꾼다

## 검증

`AGENTS.md` 「확인」 절의 여섯 명령을 적힌 순서대로 모두 돌린다. 새 워크트리라 `web` 에서 `pnpm install --frozen-lockfile` 이 먼저 필요하다. 첫 줄은 이 phase 의 검사만 먼저 돌리는 것이다.

```bash
# cwd: 저장소 root
cd web && pnpm test:browser admin.spec.ts
cd backend && ./gradlew test
cd web && pnpm typecheck
cd web && AUTH_SECRET=build-time-placeholder ASSISTANT_JWT_SECRET=build-time-placeholder CONTROL_PLANE_BASE_URL=http://build-time-placeholder AUTH_GOOGLE_ID=build-time-placeholder AUTH_GOOGLE_SECRET=build-time-placeholder pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
```

- 모두 통과한다
- `grep -rn "sync-model\|providers/blocked\|AgentModelList\|modelSyncedAt" web/src test/browser` 가 아무것도 내지 않는다

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `web/src/components/admin/agent-model-list.tsx` | 삭제 |
| `web/src/app/api/admin/agents/[[]code]/models/route.ts` | 삭제 |
| `web/src/app/api/admin/agents/[[]code]/sync-model/route.ts` | 삭제 |
| `web/src/app/api/admin/providers/blocked/route.ts` | 삭제 |
| `web/src/components/agent/agent-admin-section.tsx` | 수정 |
| `web/src/components/agent/agent-admin-panel.tsx` | 수정 |
| `web/src/components/admin/agent-card.tsx` | 수정 |
| `web/src/lib/agent.ts` | 수정 |
| `test/browser/admin.spec.ts` | 수정 |
| `test/browser/fixtures.ts` | 수정 |
| `test/browser/identity.spec.ts` | 수정 |
