# Phase 02. 상세 화면에 관리 절을 옮긴다

**Execution profile**: standard

## 목표

`/agents/{code}` 가 그 에이전트의 모든 설정을 갖게 한다. `ADMIN` 에게 관리 절(사용 여부, 공개 범위, Hermes 주소, 모델 다시 읽기와 모델 목록)을 보인다.
나중에 도구 선택도 이 자리에 붙는다.

**범위 외**: backend 권한과 `/api/v1/**` 는 바꾸지 않는다. 도구 선택과 추천 질문 자동 생성은 다른 계획이다.

## 컨텍스트

**근거 문서**: `docs/code-architecture.md` 의 「에이전트 화면」 절과 「페르소나」 의 「누가 고칠 수 있나」

지금 모양(구현 전에 다시 읽는다):

| 자리 | 지금 |
| --- | --- |
| `web/src/app/agents/[code]/page.tsx` | `/api/v1/agents`, `/api/v1/agents/{code}/persona`, `/api/v1/agents/{code}/starters` 를 함께 읽고 `PersonaEditor`, `StarterEditor` 를 그린다. persona 가 실패하면 오류 문구만 보인다 |
| `web/src/components/admin/agent-card.tsx` | `AgentAction = "private" \| "group" \| "enabled" \| "address" \| "sync" \| "models"`. 공개 범위, 사용 여부, 주소 입력, 모델 다시 읽기, 상세 링크 단추 |
| `web/src/components/admin/agent-model-list.tsx`, `visibility-confirm.tsx` | 모델 목록 편집, 그룹 공개 확인 창 |
| `agent-admin-panel.tsx` 의 `update`, `changeApiBaseUrl`, `syncModel`, `saveModels`, `confirmGroupVisibility` | `/api/admin/agents/{code}`, `/sync-model`, `/models` 를 부른다 |
| backend `AgentService.requireReadable` | 쓸 수 있는 사람만 읽는다. `ADMIN` 이라도 남의 비공개 에이전트의 persona 와 starters 는 `AGENT_NOT_FOUND` 다 |

## 의도 메모

- **`ADMIN` 이 다른 사람의 비공개 에이전트 상세를 열면 관리 절만 보인다**(사용자 결정). 성격 자리에는 「이 에이전트의 성격은 주인만 볼 수 있어요.」 같은 안내를 둔다. 에이전트 이름과 관리 정보는 `/api/v1/admin/agents` 에서 읽는다
- 관리 절은 `ADMIN` 에게만 그리고, 그 동작은 지금 관리 화면의 함수를 옮겨 쓴다. 그룹 공개로 바꾸는 것은 지금처럼 확인 창을 거친다
- 다른 사람의 비공개 에이전트에서 사용 여부나 Hermes 주소를 바꾸거나 비공개를 유지하는 수정에는 `ownerEmail: null` 을 보내 기존 `ownerUserId` 를 보존한다. 그룹 공개에서 비공개로 바꿀 때만 요청자 이메일을 보낸다. 지금 관리 화면의 함수를 그대로 옮기면 타인의 에이전트 주인이 요청자로 바뀌므로 이 부분은 고친다
- 목록 카드에 남아 있던 관리 단추는 이 phase 에서 지우고, 카드는 상세로 가는 링크와 표시만 남긴다
- 새 한국어 문구는 해요체

## 작업 항목

### 1. 상세 화면에 관리 절을 둔다

`web/src/app/agents/[code]/page.tsx` 가 `ADMIN` 이면 새 `web/src/components/agent/agent-admin-section.tsx` 로 `/api/v1/admin/agents` 에서 그 `code` 의 `AdminAgent` 를 찾아 관리 절(클라이언트 컴포넌트)을 그린다. persona 가 `AGENT_NOT_FOUND` 이고 관리 목록에 그 에이전트가 있으면 안내와 관리 절만 그린다. 둘 다 없으면 지금처럼 오류 문구다

### 2. 목록 카드의 관리 단추를 지운다

`agent-card.tsx` 와 `agent-list.tsx` 에서 관리 동작을 상세로 옮긴 뒤 카드에는 이름, 모델, 표시, 상세 링크만 남긴다. `agent-admin-panel.tsx` 는 등록, 막힌 provider 알림, 목록 상태와 등록 뒤 재조회만 맡도록 줄이고 상세로 옮긴 관리 함수와 action prop 을 지운다. 쓰이지 않게 된 파일은 지운다

### 3. 이 phase 를 검증하는 브라우저 검사

`test/browser/admin.spec.ts` 의 공개 범위, 주소, 모델 검사를 상세 화면으로 옮기고, 사용 여부와 모델 다시 읽기 검사를 새로 추가한다. `identity.spec.ts`, `persona.spec.ts`, `starters.spec.ts` 가 옛 화면을 가정한 곳을 고친다.
- 정상: `ADMIN` 이 상세에서 「그룹 공개로 변경」 → 확인 창 → 배지가 「그룹 공개」 가 된다. 모델 다시 읽기가 된다
- 실패 쪽: `ADMIN` 이 다른 사람의 비공개 에이전트 상세를 열면 성격 편집기가 없고 주인만 볼 수 있다는 안내와 관리 절이 있다. `MEMBER` 는 상세에 관리 절이 없다
- 소유권과 실패: 다른 사람의 비공개 에이전트 주소나 사용 여부를 바꾼 뒤에도 `ownerUserId` 가 그대로다. 관리 요청이 실패하면 오류를 보여주고 기존 상태를 유지한다

## 검증

`AGENTS.md` 「확인」 절의 여섯 명령을 적힌 순서대로 모두 돌린다. 새 워크트리라 `web` 에서 `pnpm install --frozen-lockfile` 이 먼저 필요하다.

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
cd web && pnpm typecheck
cd web && AUTH_SECRET=build-time-placeholder ASSISTANT_JWT_SECRET=build-time-placeholder CONTROL_PLANE_BASE_URL=http://build-time-placeholder AUTH_GOOGLE_ID=build-time-placeholder AUTH_GOOGLE_SECRET=build-time-placeholder pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
grep -rn 'admin/agents' web/src
```

- 여섯 명령이 통과한다
- 마지막 grep 은 `web/src/app/admin/agents/page.tsx` 의 넘기기와 `web/src/app/api/admin/agents/**` 경로만 가리킨다. 브라우저 검사의 관리 API 요청은 그대로 둔다
- 모두 통과하면 `tasks/plan031-agents-menu-merge/index.json` 의 `status` 를 `completed` 로, `current_phase` 를 `2` 로 바꾼다

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `web/src/app/agents/[[]code]/page.tsx` | 수정 |
| `web/src/components/agent/agent-admin-section.tsx` | 신규 |
| `web/src/components/admin/agent-card.tsx` | 수정 |
| `web/src/components/admin/agent-list.tsx` | 수정 |
| `web/src/app/admin/agents/agent-admin-panel.tsx` | 수정 |
| `test/browser/admin.spec.ts` | 수정 |
| `test/browser/identity.spec.ts` | 수정 |
| `test/browser/persona.spec.ts` | 수정 |
| `test/browser/starters.spec.ts` | 수정 |
| `tasks/plan031-agents-menu-merge/index.json` | 수정 |
