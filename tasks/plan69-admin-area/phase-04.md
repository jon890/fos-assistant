# Phase 04. 사용량과 실행 상세를 옮기고 일반 화면의 관리자 표시를 끈다

**Execution profile**: standard

## 목표

금액, 모델, 토큰이 보이는 사용량과 실행 상세를 `/admin/usage` 와 `/admin/executions/{id}` 로 옮긴다.
일반 화면(`/usage`, `/executions/{id}`, 대화 화면)은 `ADMIN` 에게도 `MEMBER` 역할 사용자와 같은 것을 그린다.

**범위 외**: 그룹 모델 설정(phase 05). backend.

## 컨텍스트

- `web/src/app/usage/page.tsx` 가 `readMe()` 로 `isAdmin` 을 정해 탭, 요약, 사용량 내역, 실행 기록에 넘긴다
- `web/src/components/usage/usage-tabs.tsx` 의 탭 링크는 `/usage` 로 고정돼 있다
- `web/src/components/usage/execution-table.tsx` 와 `execution-card.tsx` 의 실행 링크는 `/executions/{id}` 로 고정돼 있다
- `web/src/components/execution/execution-detail.tsx` 는 `useShellIsAdmin()` 으로 내부 값을 그리고, 없는 실행이면 `router.replace("/usage")` 한다
- 대화 화면에서 `useShellIsAdmin()` 을 읽는 자리: `web/src/components/chat-panel.tsx`, `web/src/components/chat/activity/activity-panel.tsx`, `web/src/components/chat/activity/activity-timeline.tsx`
- phase 02 가 `useAdminView()`(역할이 `ADMIN` 이고 경로가 `/admin` 아래일 때 참)를 만들었다

**근거 문서**: `docs/frontend/structure.md` 의 「관리자 영역」 과 「사용량 화면의 탭」 절, `docs/frontend/activity.md`, `web/AGENTS.md` 의 「화면 문구」

## 의도 메모

- `useShellIsAdmin()` 을 지우고 모든 사용처를 `useAdminView()` 로 바꾼다. 역할만 보는 hook 을 남기면 일반 화면에 관리자 표시가 다시 생긴다
- 사용량 화면을 두 벌로 복사하지 않는다. 지금 `usage/page.tsx` 의 본문을 서버 부품 하나로 빼고 두 경로가 `admin` 과 `basePath` 를 넘긴다
- 대화 화면의 `isAdmin` prop 들은 `useAdminView()` 가 일반 화면에서 거짓이므로 저절로 꺼진다. prop 을 받는 자리까지 지울지는 코드가 단순해지는 쪽으로 정한다
- 모델이 바뀌었다는 줄은 저절로 꺼지지 않는다. `web/src/components/chat/activity/activity-state.ts` 가 `switched` 와 `PROVIDER_SWITCHED` 사건으로 「여기부터 …로 실행해요」 줄을 역할을 보지 않고 만들고, `ADMIN` 은 그 사건을 그대로 받는다. 아래 3번이 끈다

## 작업 항목

### 1. `web/src/components/usage/usage-screen.tsx` (신규)

서버 부품 `UsageScreen({ admin, basePath, searchParams })`. 지금 `usage/page.tsx` 의 읽기와 그리기를 옮긴다.
`admin` 이 거짓이면 나눠 보기 두 조회를 하지 않는다. 제목은 `admin` 이면 「사용량과 비용」, 아니면 「사용량」 이다.

### 2. 경로

- `web/src/app/usage/page.tsx`: 세션을 확인하고 `UsageScreen` 을 `admin={false}`, `basePath="/usage"` 로 그린다. `readMe()` 를 부르지 않는다
- `web/src/app/admin/usage/page.tsx` (신규): `admin={true}`, `basePath="/admin/usage"`
- `web/src/app/admin/usage/loading.tsx` (신규): `web/src/app/usage/loading.tsx` 와 같은 뼈대
- `web/src/app/admin/executions/[id]/page.tsx` (신규): `web/src/app/executions/[id]/page.tsx` 와 같고, 숫자가 아닌 id 는 `/admin/usage` 로 넘긴다
- `web/src/app/admin/executions/[id]/loading.tsx` (신규): `web/src/app/executions/[id]/loading.tsx` 와 같은 뼈대

### 3. 부품

- `web/src/components/usage/usage-tabs.tsx`: `basePath` 를 받아 링크를 만든다
- `web/src/components/usage/execution-list.tsx`, `execution-table.tsx`, `execution-card.tsx`: `isAdmin` 이 참이면 실행 링크가 `/admin/executions/{id}`, 아니면 `/executions/{id}` 다
- `web/src/components/execution/execution-detail.tsx`: `useAdminView()` 를 쓴다. 없는 실행이면 관리자 영역에서는 `/admin/usage`, 아니면 `/usage` 로 간다
- `web/src/components/chat-panel.tsx`, `web/src/components/chat/activity/activity-panel.tsx`, `web/src/components/chat/activity/activity-timeline.tsx`: `useAdminView()` 로 바꾼다
- 모델이 바뀌었다는 줄: `activity-state.ts` 의 줄을 만드는 함수가 `showSwitched: boolean` 을 받아 거짓이면 그 줄을 만들지 않는다. 부르는 쪽(`chat-panel.tsx` 와 작업 과정 패널)이 `useAdminView()` 의 값을 넘긴다. 실행 상세(`/admin/executions/{id}`)의 `PROVIDER_SWITCHED` 줄은 그대로 그린다
- `test/unit/activity-state.test.ts`: `showSwitched` 가 거짓이면 전환 사건이 줄을 만들지 않고, 참이면 만든다
- `test/unit/loading-routes.test.ts`: `ROUTE_FRAMES` 의 `usage` 를 `"components/usage/usage-screen.tsx"` 로 바꾸고 `"admin/usage": "components/usage/usage-screen.tsx"`, `"admin/executions/[id]": "app/admin/executions/[id]/page.tsx"` 를 더한다
- `web/src/components/shell/app-shell.tsx`: `useShellIsAdmin` 을 지운다. 아래가 아무것도 내지 않아야 한다

```bash
grep -rn "useShellIsAdmin" web/src test
```

### 4. 이 phase 를 검증하는 브라우저 검사

`test/browser/admin-area.spec.ts` 에 더한다.

- `ADMIN` 의 `/usage` 에 금액과 「설정별 사용량」 탭과 「사용량 내역」 이 없고 실행 건수가 있다
- `ADMIN` 의 `/admin/usage` 에 금액과 탭 넷이 있고, 탭을 누르면 주소가 `/admin/usage?tab=...` 이다. 실행 한 줄을 누르면 `/admin/executions/{id}` 로 가고 모델과 토큰이 보인다
- `ADMIN` 의 `/executions/{id}` 에 모델과 토큰과 「원본 보기」 가 없다
- `ADMIN` 의 대화 화면 작업 과정에 `activity-raw` 가 없다
- `MEMBER` 가 `/admin/usage` 를 열면 `/` 로 넘어간다

기존 검사에서 `ADMIN` 이 `/usage`, `/executions/{id}`, 대화 화면에서 내부 값을 본다고 단언하는 자리를 `/admin/usage`, `/admin/executions/{id}` 로 옮기거나, 대화 화면의 것은 「보이지 않는다」 로 바꾼다.
대상: `test/browser/usage.spec.ts`, `usage-breakdown.spec.ts`, `execution-tree.spec.ts`, `tool-detail-redaction.spec.ts`, `activity-panel.spec.ts`, `model-choice.spec.ts`, `loading.spec.ts` 가운데 실제로 걸리는 것.

## 검증

```bash
# cwd: 저장소 root
(cd web && pnpm typecheck && pnpm lint)
! grep -rn "useShellIsAdmin" web/src test
(cd web && pnpm test:browser admin-area.spec.ts usage.spec.ts usage-breakdown.spec.ts execution-tree.spec.ts tool-detail-redaction.spec.ts activity-panel.spec.ts model-choice.spec.ts loading.spec.ts chat.spec.ts)
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
```

모두 종료 코드 0 이다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/components/usage/usage-screen.tsx` | 신규 |
| `web/src/app/usage/page.tsx` | 수정 |
| `web/src/app/admin/usage/page.tsx` | 신규 |
| `web/src/app/admin/usage/loading.tsx` | 신규 |
| `web/src/app/admin/executions/[id]/page.tsx` | 신규 |
| `web/src/app/admin/executions/[id]/loading.tsx` | 신규 |
| `web/src/components/usage/usage-tabs.tsx` | 수정 |
| `web/src/components/usage/execution-list.tsx` | 수정 |
| `web/src/components/usage/execution-table.tsx` | 수정 |
| `web/src/components/usage/execution-card.tsx` | 수정 |
| `web/src/components/execution/execution-detail.tsx` | 수정 |
| `web/src/components/chat-panel.tsx` | 수정 |
| `web/src/components/chat/activity/activity-panel.tsx` | 수정 |
| `web/src/components/chat/activity/activity-timeline.tsx` | 수정 |
| `web/src/components/shell/app-shell.tsx` | 수정 |
| `web/src/components/chat/activity/activity-state.ts` | 수정 |
| `test/unit/activity-state.test.ts` | 수정 |
| `test/unit/loading-routes.test.ts` | 수정 |
| `test/browser/admin-area.spec.ts` | 수정 |
| `test/browser/usage.spec.ts` | 수정 |
| `test/browser/usage-breakdown.spec.ts` | 수정 |
| `test/browser/execution-tree.spec.ts` | 수정 |
| `test/browser/tool-detail-redaction.spec.ts` | 수정 |
| `test/browser/activity-panel.spec.ts` | 수정 |
| `test/browser/loading.spec.ts` | 수정 |
