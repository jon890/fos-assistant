# Phase 01. 서버에서 읽는 화면마다 뼈대를 둔다

**Execution profile**: standard

## 목표

화면을 옮기는 즉시 그 화면 모양의 뼈대가 본문 자리를 채우게 한다.
지금은 서버가 데이터를 다 읽을 때까지 이전 화면이 멈춘 채 남아, 누른 것이 먹혔는지 알 수 없다.

**범위 외**: 사이드바의 누른 줄 표시는 phase-02 가 한다. 단추 안의 회전 표시는 shadcn 단추를 들이는 디자인 기반 계획이 한다.

## 컨텍스트

**근거 문서**: `docs/flow.md` 「기다리는 동안 보이는 것」, `docs/code-architecture.md` 「디렉터리」 의 `loading.tsx` 규칙.

`web/src/app/` 의 화면은 모두 서버 컴포넌트다. 아래 여섯 화면은 `page.tsx` 안에서 `callControlPlane` 을 `await` 로 다 읽은 뒤에 그린다.
`loading.tsx` 가 한 곳도 없다. Next 는 같은 경로에 `loading.tsx` 가 있으면 그것을 Suspense 의 대신 화면으로 먼저 흘려 보낸다.

뼈대를 둘 경로는 여덟이다.
그 가운데 여섯은 `page.tsx` 가 `callControlPlane` 을 부른다. 구현 전에 아래로 다시 세어 빠진 경로가 없는지 본다.

```bash
# cwd: 저장소 root
grep -rl 'callControlPlane' web/src/app --include=page.tsx
```

나머지 둘(`/c/{id}`, `/executions/{id}`)은 `page.tsx` 가 `auth()` 만 부르고 데이터는 브라우저의 부품이 읽는다.
그래도 이동하는 동안 서버 왕복이 있어 이전 화면이 멈춘 채 남으므로 뼈대를 둔다(`docs/flow.md` 「화면 경로마다 `loading.tsx` 를 둔다」).

`width` 는 그 화면이 실제로 그리는 바깥 틀의 `max-w-*` 다. 괄호 안이 그 틀이 있는 파일이다.

| 경로 | `page.tsx` | `shape` | `width` | 설명 줄 | 뼈대 모양 |
| --- | --- | --- | --- | --- | --- |
| `/agents` | `web/src/app/agents/page.tsx` | `cards` | `2xl` (page.tsx) | 없음 | 제목과 카드 목록 |
| `/agents/{code}` | `web/src/app/agents/[code]/page.tsx` | `editor` | `2xl` (persona-editor.tsx) | 있음 | 제목과 긴 입력칸 |
| `/memory` | `web/src/app/memory/page.tsx` | `list` | `4xl` (memory-list.tsx) | 있음 | 제목, 입력 폼, 목록 |
| `/usage` | `web/src/app/usage/page.tsx` | `table` | `5xl` (page.tsx) | 있음 | 제목, 합계 칸, 표 |
| `/executions/{id}` | `web/src/app/executions/[id]/page.tsx` | `tree` | `5xl` (page.tsx) | 없음 | 요약과 들여쓴 줄 |
| `/admin/agents` | `web/src/app/admin/agents/page.tsx` | `cards` | `4xl` (agent-admin-panel.tsx) | 있음 | 제목, 입력 폼, 카드 목록 |
| `/admin/people` | `web/src/app/admin/people/page.tsx` | `list` | `4xl` (people-admin-panel.tsx) | 있음 | 제목, 입력 폼, 목록 |
| `/c/{id}` | `web/src/app/c/[conversationId]/page.tsx` | `chat` | `3xl` (message-list.tsx) | 없음 | 메시지 뼈대 두 줄과 입력창 자리 |

`/executions/{id}` 는 뼈대 뒤에 `ExecutionDetail` 의 「불러오는 중이다.」 가 한 번 더 보인다. 그 부품은 이 phase 에서 고치지 않는다.

`/` 에는 두지 않는다. 로그인 확인 말고는 서버에서 읽는 데이터가 없다. `/signin` 도 같다.

뼈대 조각은 `web/src/components/ui/skeleton.tsx` 의 `Skeleton` 을 쓴다. `aria-hidden` 이고 `motion-reduce` 에서 멈춘다.

## 의도 메모

- 뼈대를 화면마다 따로 그리지 않고 공통 부품 하나에 모양 몇 가지를 둔다. 새 화면이 생겨도 그 부품을 고르기만 하면 된다
- 뼈대의 최대 폭과 제목 자리는 그 `page.tsx` 의 바깥 틀과 같게 둔다. 다르면 내용이 올 때 화면이 옆으로 튄다
- 뼈대에는 `aria-busy="true"` 만 두고 live 영역을 두지 않는다. 옮기는 중이라는 안내는 사이드바의 live 영역 하나가 맡는다. 둘 다 live 로 두면 낭독기가 두 안내를 겹쳐 읽는다. 새로 끼워 넣은 live 영역은 읽히지 않을 때가 많고, `aria-busy` 인 live 영역은 busy 가 풀릴 때까지 읽히지 않으므로 늘 DOM 에 있는 사이드바 영역을 남긴다. 조각은 `Skeleton` 이 이미 `aria-hidden` 이다
- 뼈대 검사는 브라우저 쪽에서 응답 전체를 늦추는 방식으로 하지 않는다. 검사는 `pnpm dev` 로 돌고 개발 모드는 링크를 미리 읽지 않아, 뼈대가 서버에서 흘러와야 보인다. 서버를 실제로 늦춰야 한다
- 색과 간격은 지금 토큰 이름을 쓴다. 토큰 이름을 바꾸는 디자인 기반 계획이 뒤에 이 파일들도 함께 바꾼다

## Blocked 조건

- `web/src/app` 아래에 `loading.tsx` 가 이미 있으면 그 파일을 읽고 이 phase 와 겹치는지 본다. 모양이 다르면 `PHASE_BLOCKED: 이미 있는 loading.tsx 와 겹친다`

## 작업 항목

### 1. `web/src/components/ui/page-skeleton.tsx` 신규

```ts
export type PageSkeletonShape = "cards" | "list" | "table" | "editor" | "tree" | "chat";
export type PageSkeletonWidth = "2xl" | "3xl" | "4xl" | "5xl";
export function PageSkeleton(props: { shape: PageSkeletonShape; width: PageSkeletonWidth; title?: boolean; description?: boolean })
```

반환 타입을 `JSX.Element` 로 적지 않는다. 설치된 `@types/react` 에 전역 `JSX` 가 없어 `pnpm typecheck` 가 실패한다.

- 바깥은 `<div data-testid="page-skeleton" aria-busy="true" className="mx-auto w-full max-w-2xl">` 이다. `width` 값에 따라 `max-w-2xl`, `max-w-3xl`, `max-w-4xl`, `max-w-5xl` 을 쓴다. 클래스 이름은 Tailwind 가 찾을 수 있게 문자열 그대로 둔다
- `title` 이 참이면 맨 위에 제목 자리 하나를 둔다. 높이는 `text-xl` 제목 한 줄, 아래 여백은 그 화면의 `h1` 과 같게 한다
- `description` 이 참이면 제목 아래에 설명 문단 자리 하나(`max-w-2xl`, 한 줄 높이, `mb-6`)를 둔다. 이때 제목 아래 여백은 `mb-2` 다
- `cards` 는 `width` 가 `4xl` 일 때 카드 목록 위에 입력 폼 자리 하나를 둔다. `/admin/agents` 의 `AgentForm` 자리다
- 모양마다 줄 수와 높이는 실제 화면의 한 줄 높이를 열어 보고 맞춘다. 예를 들어 `cards` 는 에이전트 카드 한 장 높이의 뼈대 셋, `table` 은 합계 칸 하나와 표 줄 다섯이다
- `chat` 은 `web/src/components/chat-panel.tsx` 의 세로 배치를 따른다. 전체 높이의 세로 `flex` 로 두고, 맨 위에 에이전트 줄 높이의 머리 자리(`border-b pb-3`), 가운데에 `message-list.tsx` 가 메시지를 읽는 동안 그리는 뼈대 두 줄과 같은 높이와 간격, 맨 아래에 붙은 입력창 높이의 자리를 둔다. 폭은 `max-w-3xl` 이다. `chat-panel.tsx` 는 고치지 않는다

### 2. 경로마다 `loading.tsx`

위 표의 여덟 경로에 `loading.tsx` 를 둔다. 모두 같은 꼴이다.

```tsx
import { PageSkeleton } from "@/components/ui/page-skeleton";

export default function Loading() {
  return <PageSkeleton shape="cards" width="2xl" title />;
}
```

`shape`, `width`, `description` 은 위 경로 표의 값을 쓴다. `title` 은 `/c/{id}` 와 `/executions/{id}` 말고는 참이다.

### 3. 가짜 Hermes 에 `SOUL.md` 응답을 붙잡아 두는 제어

`test/e2e/fake-hermes.ts` 의 `hold-next-run` 과 같은 모양으로 둘을 더한다.

- `POST /__test/hold-next-soul`: 다음 `GET /api/profiles/{이름}/soul` 응답을 붙잡는다
- `POST /__test/release-held-soul`: 붙잡은 응답을 보낸다. 붙잡은 응답이 없어도 대기 표시를 끄고 204 를 준다. 클릭 전에 실패한 검사가 남긴 표시 때문에 `persona.spec.ts` 의 저장 흐름이 멈추지 않게 한다

`test/browser/fixtures.ts` 의 `FakeHermesControl` 에 `holdNextSoul()` 과 `releaseHeldSoul()` 을 더한다. 지금 `holdNextRun` 이 부르는 방식과 같다.
Control Plane 의 `GET /api/v1/agents/{code}/persona` 는 부를 때마다 Hermes 대시보드에서 `SOUL.md` 를 읽는다. 그래서 이 제어로 성격 화면의 서버를 실제로 늦출 수 있다.

### 4. 이 phase 를 검증하는 테스트

`test/browser/loading.spec.ts` 신규. 두 폭에서 돈다.

| 경우 | 기대 |
| --- | --- |
| `hermes.holdNextSoul()` 뒤 `/agents` 에서 성격 에이전트 카드를 눌러 `/agents/{code}` 로 간다 | `page-skeleton` 이 보이고, 이전 화면의 제목 「에이전트」 가 사라진다. `releaseHeldSoul()` 뒤 뼈대가 사라지고 성격 편집칸이 보인다 |
| 위와 같은 이동에서 뼈대의 폭 | 뼈대의 `boundingBox().width` 와 내용이 온 뒤 편집칸 바깥 틀의 폭이 같다 |

**끝나면 반드시 `releaseHeldSoul()` 을 부른다.** 실패해도 풀리게 `finally` 에 둔다. 다음 검사가 붙잡힌 응답에 걸린다.

`test/unit/loading-routes.test.ts` 신규. `node:test` 로 두 가지를 본다.

- `web/src/app` 을 훑어 `callControlPlane` 을 부르는 `page.tsx` 마다 같은 디렉터리에 `loading.tsx` 가 있다. 새 화면이 뼈대 없이 생기는 것을 이 검사가 막는다
- 위 경로 표의 여덟 경로마다 `loading.tsx` 가 있고, 그 파일의 `width` 값이 표의 값과 같다. 폭이 틀리면 내용이 올 때 화면이 옆으로 튄다

## 검증

```bash
# cwd: 저장소 root
node --test 'test/unit/**/*.test.ts'
grep -rn 'style={{' web/src/components/ui/page-skeleton.tsx web/src/app
```

`grep` 은 주석이 달린 예외 말고는 아무것도 내지 않아야 한다.

```bash
# cwd: web/
pnpm typecheck
pnpm test:browser
```

여덟 화면이 바뀌므로 브라우저 검사는 전체를 돌린다.

`tasks/plan025-loading-states/index.json` 의 이 phase 를 `completed` 로 바꾸고 `current_phase` 를 2로 올린다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `web/src/components/ui/page-skeleton.tsx` | 신규 |
| `web/src/app/agents/loading.tsx` | 신규 |
| `web/src/app/agents/[code]/loading.tsx` | 신규 |
| `web/src/app/memory/loading.tsx` | 신규 |
| `web/src/app/usage/loading.tsx` | 신규 |
| `web/src/app/executions/[id]/loading.tsx` | 신규 |
| `web/src/app/admin/agents/loading.tsx` | 신규 |
| `web/src/app/admin/people/loading.tsx` | 신규 |
| `web/src/app/c/[conversationId]/loading.tsx` | 신규 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/browser/fixtures.ts` | 수정 |
| `test/browser/loading.spec.ts` | 신규 |
| `test/unit/loading-routes.test.ts` | 신규 |
