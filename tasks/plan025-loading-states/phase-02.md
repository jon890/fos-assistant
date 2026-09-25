# Phase 02. 사이드바에서 누른 줄에 옮기는 중 표시를 붙인다

**Execution profile**: standard

## 목표

사이드바에서 화면이나 대화를 누르면, 새 화면이 올 때까지 누른 줄 끝에 작은 회전 표시가 붙게 한다.
뼈대가 본문을 바꾸지만, 사이드바에서도 어느 것을 눌렀는지 보여야 누른 것이 먹혔다고 믿는다.

**범위 외**: 뼈대는 phase-01 이 했다. 단추 안의 회전 표시는 디자인 기반 계획이 한다.

## 컨텍스트

**근거 문서**: `docs/flow.md` 「기다리는 동안 보이는 것」.

Next 16 의 `next/link` 가 `useLinkStatus()` 를 준다. `<Link>` 의 **자식 컴포넌트** 안에서 불러야 하고, 그 링크의 이동이 끝나기 전까지 `pending` 이 참이다.
설치된 판에 있는 것을 `web/node_modules/next/dist/client/app-dir/link.d.ts` 에서 확인했다.

사이드바 링크는 두 파일에 있다.

| 파일 | 링크 |
| --- | --- |
| `web/src/components/shell/main-nav.tsx` | 에이전트, 기억, 사용량, 관리 둘 |
| `web/src/components/shell/conversation-nav.tsx` | 대화 한 줄마다 `/c/{id}` |

`conversation-nav.tsx` 의 대화 링크는 이미 같은 대화면 `preventDefault` 한다. 그 경우에는 이동이 없으니 표시도 없어야 한다.

## 의도 메모

- 표시를 링크 글자 뒤에 붙인다. 글자를 바꾸거나 줄 높이를 바꾸지 않는다. 줄이 흔들리면 누른 자리를 놓친다
- 회전 표시는 지금 글자와 같은 색의 작은 원 테두리로 그린다. 아이콘 라이브러리를 들이는 것은 디자인 기반 계획이고, 그 계획이 이것도 `lucide-react` 의 `Loader2` 로 바꾼다
- 화면 낭독기에는 `sr-only` 로 「옮기는 중」 을 붙인다. 링크 이름이 바뀌면 테스트의 `getByRole("link", { name })` 이 깨지므로, 이름에 들어가지 않게 `aria-hidden` 인 표시와 `role="status"` 인 안내를 링크 밖에 둔다

## 작업 항목

### 1. `web/src/components/shell/nav-pending.tsx` 신규

```tsx
"use client";
export function NavPending(): JSX.Element | null
```

`useLinkStatus()` 의 `pending` 이 참이면 `<span aria-hidden="true" data-testid="nav-pending" className="ml-2 inline-block h-3 w-3 animate-spin rounded-full border-2 border-muted border-t-transparent motion-reduce:animate-none" />` 를 그린다. 거짓이면 `null`.

낭독기용 안내는 사이드바에 하나만 둔다. `web/src/components/shell/sidebar.tsx` 에 `role="status"` `aria-live="polite"` 인 `sr-only` 영역을 두고, `NavPending` 이 `pending` 이 참이 될 때 그 영역에 「옮기는 중」 을 적게 한다. 방법은 사이드바가 이미 쓰는 context 나 작은 이벤트 가운데 지금 코드에 맞는 쪽을 고른다.

### 2. 링크 두 곳에 붙인다

`main-nav.tsx` 와 `conversation-nav.tsx` 의 `<Link>` 자식 끝에 `<NavPending />` 을 둔다. 대화 줄은 제목이 `truncate` 이므로 표시가 잘리지 않게 제목과 표시를 나란히 두는 틀을 쓴다.

### 3. 이 phase 를 검증하는 테스트

`test/browser/loading.spec.ts` 에 더한다.

| 경우 | 기대 |
| --- | --- |
| `hermes.holdNextSoul()` 뒤 사이드바의 「에이전트」 로 가서 성격 에이전트를 연다 | 이동이 끝나기 전에 누른 줄 안에 `nav-pending` 이 보인다. 풀면 사라진다 |
| 지금 열린 대화 줄을 다시 누른다 | `nav-pending` 이 보이지 않는다 |
| 링크 이름 | `getByRole("link", { name: "사용량" })` 처럼 지금 테스트가 쓰는 이름이 그대로 맞는다 |

좁은 폭에서는 사이드바가 서랍이다. 서랍을 연 뒤 누르고, 서랍이 닫히기 전의 표시는 보지 않는다. 본문의 `page-skeleton` 만 본다.

## 검증

```bash
# cwd: web/
pnpm typecheck
pnpm test:browser loading.spec.ts nav.spec.ts shell.spec.ts
```

AGENTS.md 「확인」 절의 명령을 적힌 순서대로 모두 돌린다. 이 plan 의 마지막 phase 다.

끝나면 `tasks/plan025-loading-states/index.json` 의 이 phase 를 `completed` 로, plan 의 `status` 를 `completed` 로 바꾼다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `web/src/components/shell/nav-pending.tsx` | 신규 |
| `web/src/components/shell/main-nav.tsx` | 수정 |
| `web/src/components/shell/conversation-nav.tsx` | 수정 |
| `web/src/components/shell/sidebar.tsx` | 수정 |
| `test/browser/loading.spec.ts` | 수정 |
