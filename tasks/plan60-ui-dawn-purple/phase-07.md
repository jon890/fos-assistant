# Phase 07. 화면 전환

**Execution profile**: deep

## 목표

화면을 옮길 때 들어오는 본문이 200ms 동안 8px 오르며 나타나게 한다. Next 의 실험 기능 View Transitions 를 쓰되 설정 하나로 끌 수 있게 한다.

**범위 외**: 대화 화면 안의 움직임은 phase 06 이 끝냈다. 테마 바꿈의 crossfade 는 하지 않는다.

## 컨텍스트

- Next.js 16.0.10 이고 `web/next.config.ts` 에 `experimental` 설정이 없다. `experimental.viewTransition` 을 켜면 `import { ViewTransition } from "react"` 를 쓸 수 있다. App Router 는 Next 가 함께 싣는 React 를 쓰므로 `web/node_modules/react` 에 그 export 가 없어도 된다. 타입은 `@types/react` 가 준다.
- 화면 틀은 `web/src/components/shell/app-shell.tsx` 다. `children` 을 감싼 본문 자리가 있다.
- 대화 화면의 주소는 `/` (새 대화)와 `/chat/{id}` 다. `web/src/components/chat-panel.tsx` 는 첫 메시지를 보낼 때 `history.replaceState` 로 `/` 를 `/chat/{id}` 로 바꾸고, Next 가 그것을 `usePathname` 에 반영한다. 같은 `ChatPanel` 이 살아 있어야 흐르던 스트림과 메시지 상태가 남는다.
- phase 01 이 줄인 움직임 블록을, phase 06 이 `animate-screen-in` 을 `web/src/app/globals.css` 에 두었다.
- 브라우저 검사의 웹 서버는 `test/browser/web-server.ts` 가 빌드하고 띄운다. 빌드에 줄 환경 변수를 그 파일이 정한다.

**근거 문서**: `docs/adr/ADR-047-화면-색은-새벽-보라로-바꾸고-강조-색은-누를-것과-고른-것과-초점에만-쓴다.md` 의 「움직임」 절과 「결과」 의 「감당할 것」, `docs/flow.md` 의 「기다리는 동안 보이는 것」 절

## 의도 메모

- 대화 화면은 전환 대상에서 뺀다. 사이드바에서 대화를 오갈 때 입력창이 깜빡이면 안 되고, `/` 에서 `/chat/{id}` 로 주소가 바뀔 때 `ChatPanel` 이 다시 만들어지면 흐르던 답이 사라진다.
- 끌 수 있어야 한다. 실험 기능이라 Next 를 올릴 때 깨질 수 있다.
- 켠 채로 전체 브라우저 검사가 통과하면 기본값은 켬이다. 통과하지 못하면 검사를 고쳐 맞추지 않고 기본값을 끔으로 둔다.

## 작업 항목

### 1. `web/src/components/shell/screen-key.ts` 신규

```ts
/** 화면 전환의 단위다. 대화 화면은 주소가 바뀌어도 같은 화면으로 본다. */
export function screenKey(pathname: string): string
/** 빌드 때 굳는 값이다. `off` 가 아니면 켠다. */
export function viewTransitionEnabled(value: string | undefined): boolean
```

- `screenKey`: `pathname === "/"` 이거나 `/chat/` 로 시작하면 `"chat"`, 그 밖에는 `pathname` 그대로.
- `viewTransitionEnabled`: `value !== "off"`.
- 다른 모듈을 import 하지 않는다. 단위 테스트가 `node --test` 로 직접 읽는다.

### 2. `web/next.config.ts`

`experimental: { viewTransition: process.env.NEXT_PUBLIC_VIEW_TRANSITION !== "off" }` 를 둔다. 새 주석은 한국어로 쓴다. 있던 주석은 건드리지 않는다.

### 3. `web/src/components/shell/screen-transition.tsx` 신규

```tsx
"use client";
export function ScreenTransition({ children }: { children: React.ReactNode })
```

- `usePathname()` 과 `screenKey` 로 key 를 정한다. 켬 여부는 `viewTransitionEnabled(process.env.NEXT_PUBLIC_VIEW_TRANSITION)` 이다.
- key 가 `"chat"` 이면 어느 쪽이든 `children` 을 그대로 돌려준다. 감싸지 않고 움직임도 주지 않는다. 감싸개의 key 가 바뀌지 않으므로 `ChatPanel` 이 살아 있다.
- 켰을 때: `<ViewTransition key={key} enter="screen" exit="screen" default="none">` 로 `children` 을 감싼다.
- 껐을 때: `<div key={key} className="animate-screen-in ...">` 로 감싼다. 그 `div` 는 `app-shell.tsx` 의 본문 자리가 자식에게 기대하는 높이와 스크롤 클래스를 그대로 가져야 한다. `app-shell.tsx` 의 본문 감싸개를 읽고, 자식이 `h-full` 이나 `flex-1 min-h-0` 에 기대면 같은 클래스를 준다.
- `web/src/components/shell/app-shell.tsx` 의 본문 자리에서 `children` 을 `ScreenTransition` 으로 감싼다.

### 4. `web/src/app/globals.css`

- `::view-transition-old(.screen)` 은 120ms linear 흐려짐, `::view-transition-new(.screen)` 은 `--duration-base` 와 `--ease-out` 의 8px 오름과 흐려짐.
- 줄인 움직임 블록 안에서 둘 다 100ms 흐려짐만 한다.
- `@keyframes` 는 `@theme` 블록 밖에 둔다.

### 5. `test/browser/web-server.ts`

빌드 명령의 환경에 `NEXT_PUBLIC_VIEW_TRANSITION` 을 `process.env` 에서 그대로 넘긴다. 값이 없으면 넘기지 않는다.

### 6. 검사와 기본값 판정

- `test/unit/screen-key.test.ts` 신규: `screenKey("/")` 와 `screenKey("/chat/abc")` 는 `"chat"`, `screenKey("/usage")` 는 `"/usage"`, `screenKey("/chatter")` 는 `"/chatter"`. `viewTransitionEnabled(undefined)` 는 참, `"off"` 는 거짓, `"on"` 은 참.
- `test/browser/motion.spec.ts` 에 더한다.
  - 사이드바에서 「사용량」 으로 옮긴 뒤 본문 제목이 보이고, 사이드바 요소가 같은 DOM 노드로 남는다(옮기기 전에 붙인 `data-probe` 속성이 남아 있다).
  - **첫 메시지를 보내도 대화가 다시 만들어지지 않는다**: `/` 에서 메시지를 보내고 주소가 `/chat/` 으로 바뀐 뒤에도 답이 끝까지 흘러나와 `assistant-message` 가 보인다. 보내기 전에 입력창 요소에 붙인 `data-probe` 가 남아 있다.
  - 줄인 움직임에서 화면을 옮겨도 본문 제목이 보인다.
- 켠 채로(환경 변수 없이) 전체 브라우저 검사를 돌린다. 모두 통과하면 기본값은 그대로 켬이다.
- 실패하는 검사가 있으면 `NEXT_PUBLIC_VIEW_TRANSITION=off` 로 같은 검사를 다시 돌린다. 끈 쪽에서만 통과하면 `viewTransitionEnabled` 와 `next.config.ts` 의 판정을 `=== "on"` 으로 바꿔 기본값을 끔으로 두고, 단위 테스트의 기대값을 함께 고치고, 무엇이 실패했는지 회신에 적는다. 양쪽 모두 실패하면 화면 전환과 무관한 실패이므로 원인을 고친다.
- 판정한 기본값을 `docs/adr/ADR-047-화면-색은-새벽-보라로-바꾸고-강조-색은-누를-것과-고른-것과-초점에만-쓴다.md` 의 「움직임」 표 아래에 한 줄로 적는다: 기본값과 끄는 방법(`NEXT_PUBLIC_VIEW_TRANSITION` 을 빌드 때 준다).

## 검증

```bash
# cwd: 저장소 root
node --test test/unit/screen-key.test.ts
cd web && pnpm typecheck && pnpm lint
cd web && pnpm test:browser test/browser/motion.spec.ts
cd web && NEXT_PUBLIC_VIEW_TRANSITION=off pnpm test:browser test/browser/motion.spec.ts test/browser/chat.spec.ts test/browser/nav.spec.ts
cd web && pnpm test:browser
```

기대값: 모두 종료 코드 0. 넷째 줄은 끈 경로의 검사이고 마지막 줄은 전체 브라우저 검사다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/components/shell/screen-key.ts` | 신규 |
| `web/src/components/shell/screen-transition.tsx` | 신규 |
| `test/unit/screen-key.test.ts` | 신규 |
| `web/next.config.ts` | 수정 |
| `web/src/components/shell/app-shell.tsx` | 수정 |
| `web/src/app/globals.css` | 수정 |
| `test/browser/web-server.ts` | 수정 |
| `test/browser/motion.spec.ts` | 수정 |
| `docs/adr/ADR-047-화면-색은-새벽-보라로-바꾸고-강조-색은-누를-것과-고른-것과-초점에만-쓴다.md` | 수정 |
