# Phase 01. 같은 머리 틀과 같은 안내

**Execution profile**: standard

## 목표

대상 화면의 제목 자리, 본문 폭, 주 단추 자리와 빈 목록·실패·읽는 중 안내를 부품 하나씩으로 맞춘다. 화면을 옮겨도 내용이 좌우로 튀지 않게 한다.

**범위 외**: 화면 안쪽 구성을 다시 짜는 일(spec DECISION11), 관리자 영역의 모양, 스킬 편집 화면과 「데이터 보관 안내」.

## 컨텍스트

**요구사항**: REQ19, REQ20

**근거 문서**: `docs/features/app-shell/spec.md`, `docs/features/app-shell/design.md` 의 「같은 머리 틀(PR5)」 과 「빈 목록, 실패, 읽는 중(PR5)」

- 지금 화면마다 바깥 폭이 다르다. `web/src/app/agents/page.tsx` 는 `max-w-2xl`, `web/src/components/task/task-list.tsx` 는 `max-w-3xl`, `web/src/components/memory/memory-list.tsx` 와 `web/src/app/now/page.tsx` 는 `max-w-4xl`, `web/src/components/connector/connector-catalog.tsx` 와 `web/src/app/files/page.tsx`, `web/src/components/dev-docs/shell.tsx` 는 `max-w-6xl`, `web/src/app/browser/page.tsx` 는 폭 제한이 없다.
- 에이전트 상세는 `web/src/components/agent/agent-detail-body.tsx` 가 그리고 관리자 영역(`web/src/app/admin/agents/[code]/page.tsx`)과 함께 쓴다. `persona-editor.tsx` 와 절 부품들이 `mx-auto ... max-w-2xl` 을 각자 갖는다.
- 빈 목록은 `web/src/components/ui/empty-state.tsx` 의 `EmptyState` 를 이미 쓴다. 읽는 중은 「불러오는 중…」 글자인 곳(`connector-catalog.tsx`, `user-browser-panel.tsx`, `my-files-parts.tsx`)과 `Skeleton` 인 곳(`task-list.tsx`, `notification-list.tsx`)이 섞여 있다.
- 서버가 읽는 화면의 뼈대는 `web/src/components/ui/page-skeleton.tsx` 의 `PageSkeleton` 이고 관리자 영역도 쓴다. 관리자 쪽 호출은 고치지 않는다.
- 브라우저 테스트는 `web/test/browser/README.md` 를 먼저 읽는다. 폭은 `mobile`(390px)과 `desktop`(1280px) 두 project 다.

## 의도 메모

- 폭은 `max-w-4xl` 하나다(design DECISION14). 부품에 폭 고르기 prop 을 두지 않는다. 화면마다 폭을 고를 수 있으면 다시 어긋난다.
- 에이전트 상세의 절 부품에서 폭을 빼고 `AgentDetailBody` 가 감싼다. 관리자 영역은 지금 폭(`max-w-2xl`)으로 감싸 모양을 지킨다(design DECISION17).
- 화면 문구와 순서는 바꾸지 않는다. 새로 쓰는 글은 design 의 표에 있는 설명 넷뿐이다.

## 작업 항목

### 1. `web/src/components/ui/page-frame.tsx`, `page-state.tsx`, `web/src/lib/page-head.ts`

`PageFrame({ title, description, action, back, busy, titlePending, children })` 가 design 의 「같은 머리 틀」 표대로 그린다. 자리마다 `data-slot`(`page-frame`, `page-description`, `page-action`, `page-body`)을 둔다. `busy` 면 바깥에 `data-testid="page-skeleton"` 과 `aria-busy` 를 붙인다.
`LoadFailure({ message, onRetry })` 는 `Notice` 오류 상자와 「다시 읽기」 단추이고 `onRetry` 가 없으면 `router.refresh()` 를 부른다. `ListSkeleton({ rows })` 는 `role="status"` 와 이름 「불러오는 중」 을 갖는다.
`page-head.ts` 는 대상 화면의 제목과 설명을 한 객체로 내보낸다.

### 2. 대상 화면에 적용

`/agents`, `/connections`, `/browser`, `/files`, `/dev-docs`, `/tasks`, `/memory`, `/now` 와 상세 화면(`/agents/{code}`, `/tasks/{id}`, `/tasks/new`, `/connections/{id}`, `/files/workspace`)을 `PageFrame` 으로 감싸고 각자의 폭 클래스와 `h1` 을 지운다. `loading.tsx`(`agents`, `agents/[code]`, `memory`, `now`)는 `PageFrame busy` 로 바꾼다. 실패와 읽는 중을 `LoadFailure` 와 `ListSkeleton` 으로 바꾼다. 브라우저가 읽는 화면은 다시 읽는 함수를 `onRetry` 로 넘긴다.

### 3. `web/src/components/shell/settings-shell.tsx`

묶음 목록 폭을 `md:w-64` 로 바꾼다(design DECISION16).

### 4. `web/test/browser/page-frame.spec.ts` 와 기존 테스트

아래 「인수 검증 계획」 의 사례를 쓴다. 옛 폭과 문구를 보는 기존 테스트(`loading.spec.ts` 의 `max-w-2xl`, `connector-connection.spec.ts` 의 「상태 다시 읽기」 등)를 새 모양으로 고친다.

### 5. 문서

`web/docs/code-architecture.md` 의 「설정 틀」 과 부품을 두는 자리, `docs/features/app-shell.md` 의 구현된 흐름을 고친다. 이 계획 폴더를 지운다.

## 인수 검증 계획

| 요구사항 | 준비 조건 | 실행 절차 | 기대 결과 | 실행 환경 | 실행 담당 | 선행 조건 | 시점 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| REQ19 | 로그인한 사용자 | `web/test/browser/page-frame.spec.ts` 가 대상 화면 여덟을 차례로 열어 `h1` 의 왼쪽 끝, `page-body` 의 폭, `page-action` 의 오른쪽 끝을 잰다 | 같은 틀 안에서 제목의 왼쪽 끝이 같고 본문 폭이 모두 같다. 주 단추의 오른쪽 끝이 본문의 오른쪽 끝과 같다. 두 폭 모두에서 제목과 단추의 상자가 겹치지 않는다 | 브라우저 테스트(빌드한 서버, 가짜 Hermes, `mobile` 과 `desktop`) | worker | 없음 | PR 전 |
| REQ19 | 에이전트, 예약 작업, 연결 하나씩 | 같은 spec 이 상세 화면 셋을 열어 뒤로 가기 링크와 `h1` 의 자리를 잰다 | 뒤로 가기와 제목의 왼쪽 끝이 목록 화면의 제목과 같고 본문 폭이 같다. 뒤로 가기를 누르면 목록 화면이 열린다 | 같음 | worker | 없음 | PR 전 |
| REQ20 | 빈 계정, 요청 가로채기 | 같은 spec 이 화면마다 빈 목록, 실패 응답, 늦은 응답을 만든다 | 빈 목록은 점선 상자의 안내, 실패는 `load-failure` 와 「다시 읽기」(누르면 목록이 다시 읽힌다), 읽는 중은 제목이 보인 채 `list-skeleton` 이나 `page-skeleton` 이 보인다 | 같음 | worker | 없음 | PR 전 |
| REQ19, REQ20 | 배포한 서비스 | 휴대폰과 PC 에서 설정, 예약 작업, 기억, 지금 볼 것 화면을 차례로 연다 | 화면이 같은 틀로 보인다 | 배포한 서비스 | 코디네이터 | 배포 | PR 뒤 |

## 검증

```bash
cd web && pnpm lint && pnpm format:check && pnpm typecheck && pnpm test:node
cd web && pnpm test:browser page-frame.spec.ts loading.spec.ts connector-connection.spec.ts settings.spec.ts tasks.spec.ts now.spec.ts notifications.spec.ts
scripts/dev/check-local.sh --fast
```

## 변경 파일

에이전트 상세의 절 부품(`web/src/components/agent/agent-*-section.tsx`)은 폭 클래스만 뺀다.

| 파일 | 변경 |
|---|---|
| `web/src/components/ui/page-frame.tsx` | 신규 |
| `web/src/components/ui/page-state.tsx` | 신규 |
| `web/src/lib/page-head.ts` | 신규 |
| `web/test/browser/page-frame.spec.ts` | 신규 |
| `web/src/app/agents/page.tsx` | 수정 |
| `web/src/app/agents/loading.tsx` | 수정 |
| `web/src/app/agents/[code]/loading.tsx` | 수정 |
| `web/src/components/agent/agent-detail-body.tsx` | 수정 |
| `web/src/components/agent/agent-detail-loader.tsx` | 수정 |
| `web/src/components/agent/persona-editor.tsx` | 수정 |
| `web/src/app/connections/page.tsx` | 수정 |
| `web/src/components/connector/connector-catalog.tsx` | 수정 |
| `web/src/components/connector/connector-connection-panel.tsx` | 수정 |
| `web/src/app/browser/page.tsx` | 수정 |
| `web/src/components/browser/user-browser-panel.tsx` | 수정 |
| `web/src/app/files/page.tsx` | 수정 |
| `web/src/app/files/workspace/page.tsx` | 수정 |
| `web/src/components/files/my-files-parts.tsx` | 수정 |
| `web/src/components/dev-docs/shell.tsx` | 수정 |
| `web/src/app/dev-docs/page.tsx` | 수정 |
| `web/src/app/dev-docs/[...path]/page.tsx` | 수정 |
| `web/src/components/task/task-list.tsx` | 수정 |
| `web/src/components/task/task-detail.tsx` | 수정 |
| `web/src/app/tasks/new/page.tsx` | 수정 |
| `web/src/components/memory/memory-list.tsx` | 수정 |
| `web/src/app/memory/page.tsx` | 수정 |
| `web/src/app/memory/loading.tsx` | 수정 |
| `web/src/app/now/page.tsx` | 수정 |
| `web/src/app/now/loading.tsx` | 수정 |
| `web/src/components/now/now-screen.tsx` | 수정 |
| `web/src/components/notification/notification-list.tsx` | 수정 |
| `web/src/components/shell/settings-shell.tsx` | 수정 |
| `web/test/browser/loading.spec.ts` | 수정 |
| `web/test/browser/connector-connection.spec.ts` | 수정 |
| `web/docs/code-architecture.md` | 수정 |
| `docs/features/app-shell.md` | 수정 |
