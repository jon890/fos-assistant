# Phase 02. 사이드바에서 누른 줄에 옮기는 중 표시를 붙인다

**Execution profile**: standard

## 목표

사이드바에서 화면이나 대화를 누르면, 그 링크의 이동이 끝나지 않은 동안 누른 줄 끝에 작은 회전 표시가 붙게 한다.
뼈대가 본문을 바꾸지만, 사이드바에서도 어느 것을 눌렀는지 보여야 누른 것이 먹혔다고 믿는다.

**범위 외**: 뼈대는 phase-01 이 했다. 단추 안의 회전 표시는 디자인 기반 계획이 한다.

## 컨텍스트

**근거 문서**: `docs/flow.md` 「기다리는 동안 보이는 것」.

Next 16 의 `next/link` 가 `useLinkStatus()` 를 준다. `<Link>` 의 **자식 컴포넌트** 안에서 불러야 하고, 그 링크의 이동이 끝나기 전까지 `pending` 이 참이다.
설치된 판에 있는 것을 `web/node_modules/next/dist/client/app-dir/link.d.ts` 에서 확인했다.

경로마다 `loading.tsx` 가 있으므로 `pending` 은 서버가 뼈대를 보내기 시작하면 거짓이 된다. 데이터를 다 읽을 때까지 기다리지 않는다.
운영 빌드는 링크가 뼈대까지 미리 읽어 두므로, 미리 읽기가 끝나지 않은 링크에서만 표시가 보인다.

사이드바 링크는 두 파일에 있다.

| 파일 | 링크 |
| --- | --- |
| `web/src/components/shell/main-nav.tsx` | 에이전트, 기억, 사용량, 관리 둘 |
| `web/src/components/shell/conversation-nav.tsx` | 대화 한 줄마다 `/c/{id}` |

`conversation-nav.tsx` 의 대화 링크는 이미 같은 대화면 `preventDefault` 한다. 그 경우에는 이동이 없으니 표시도 없어야 한다.

## 의도 메모

- 표시를 링크 글자 뒤에 붙인다. 글자를 바꾸거나 줄 높이를 바꾸지 않는다. 줄이 흔들리면 누른 자리를 놓친다
- 회전 표시는 지금 글자와 같은 색의 작은 원 테두리로 그린다. 아이콘 라이브러리를 들이는 것은 디자인 기반 계획이고, 그 계획이 이것도 `lucide-react` 의 `Loader2` 로 바꾼다
- 이 phase 의 검사는 서버가 아니라 브라우저에서 목적지로 가는 RSC 요청을 붙잡아 늦춘다. 사이드바 링크의 목적지에는 가짜 Hermes 로 늦출 수 있는 화면이 없고, 서버를 늦춰도 뼈대가 오는 순간 `pending` 이 끝나기 때문이다. 「브라우저 쪽에서 늦추지 않는다」 는 뼈대 검사에만 적용한다
- 화면 낭독기에는 `sr-only` 로 「옮기는 중」 을 붙인다. 링크 이름이 바뀌면 테스트의 `getByRole("link", { name })` 이 깨지므로, 이름에 들어가지 않게 `aria-hidden` 인 표시와 `role="status"` 인 안내를 링크 밖에 둔다

## 작업 항목

### 1. `web/src/components/shell/nav-pending.tsx` 신규

```tsx
"use client";
export function NavPending()
```

반환 타입을 `JSX.Element` 로 적지 않는다. 설치된 `@types/react` 에 전역 `JSX` 가 없어 `pnpm typecheck` 가 실패한다.

`useLinkStatus()` 의 `pending` 이 참이면 `<span aria-hidden="true" data-testid="nav-pending" className="ml-2 inline-block h-3 w-3 animate-spin rounded-full border-2 border-muted border-t-transparent motion-reduce:animate-none" />` 를 그린다. 거짓이면 `null`.

낭독기용 안내는 사이드바에 하나만 둔다. `web/src/components/shell/sidebar.tsx` 에 `role="status"` `aria-live="polite"` 인 `sr-only` 영역을 두고, `NavPending` 이 `pending` 이 참이 될 때 그 영역에 「옮기는 중」 을 적게 한다. `pending` 이 거짓이 되거나 `NavPending` 이 사라지면 그 영역을 비운다.
전달은 `main-nav.tsx` 의 `memory-proposal-count` 처럼 `window` 의 `CustomEvent` 로 한다. `ConversationsProvider` 는 고치지 않는 `app-shell.tsx` 가 올리므로 context 를 늘리지 않는다.

### 2. 링크 두 곳에 붙인다

`main-nav.tsx` 와 `conversation-nav.tsx` 의 `<Link>` 자식 끝에 `<NavPending />` 을 둔다. 대화 줄은 제목이 `truncate` 이므로 표시가 잘리지 않게 제목과 표시를 나란히 두는 틀을 쓴다.

### 3. 이 phase 를 검증하는 테스트

`test/browser/loading.spec.ts` 에 더한다.

| 경우 | 기대 |
| --- | --- |
| 사이드바 「사용량」 으로 가는 RSC 요청(`RSC: 1` 헤더 또는 `_rsc` 쿼리)을 `page.route` 로 붙잡고 그 링크를 누른다 | 붙잡은 동안 누른 줄 안에 `nav-pending` 이 보이고, 사이드바의 `role="status"` 영역에 「옮기는 중」 이 있다. 풀면 둘 다 사라진다 |
| 지금 열린 대화 줄을 다시 누른다 | `nav-pending` 이 보이지 않고, 주소가 그대로이며, 그 대화로 가는 RSC 요청이 나가지 않는다 |
| 링크 이름 | `getByRole("link", { name: "사용량" })` 처럼 지금 테스트가 쓰는 이름이 그대로 맞는다. 「에이전트」 는 「에이전트 관리」 와 겹치므로 `exact: true` 로 찾는다 |

붙잡은 요청은 실패해도 풀리게 `finally` 에서 보낸다.

좁은 폭에서는 사이드바가 서랍이다. 서랍을 연 뒤 누르면 서랍이 닫혀 `nav-pending` 은 보지 않는다. 서랍은 화면 밖으로 밀려나도 DOM 에 남으므로, 사이드바 `role="status"` 영역의 「옮기는 중」 만 본다. 붙잡은 동안에는 뼈대도 그 응답에 실려 오므로 `page-skeleton` 은 보지 않는다.

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
