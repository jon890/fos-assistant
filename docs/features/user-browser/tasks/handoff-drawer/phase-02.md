# Phase 02. 넘기기 훅과 옆 패널, 카드와 대화 화면 잇기, 문서

**Execution profile**: standard

## 목표

카드의 「내 브라우저 열기」 가 대화 위 옆 패널을 열고, 새 카드와 알림 진입에서 저절로 열며, 패널 안에서 「다 했어요」 와 주소 변화 확인을 하게 한다.

**범위 외**: Control Plane, fos-ctx, 플러그인, 승인 카드, `/browser` 화면.

## 컨텍스트

**요구사항**: REQ1, REQ2, REQ3, REQ4, REQ5, REQ6

**근거 문서**: `docs/features/user-browser/spec.md`, `docs/features/user-browser/design.md`, `web/docs/adr/ADR-20261112-handoff-drawer.md`

- 옆 패널 상태: `web/src/components/chat/conversation-session-types.ts` 의 `SidePanelTarget`, `use-conversation-session-state.ts` 의 `panelTarget`. Esc 닫기는 `use-conversation-effects.ts`, turn 끝의 작업 과정 갱신은 `use-conversation-history.ts` 의 `settleFinishedActivity` 다(`handoff` 종류는 그대로 둔다)
- 모양은 `web/src/components/chat/artifact/artifact-panel.tsx` 를 따른다. `useMediaQuery("(min-width: 1024px)")` 가 `false` 가 아니면 `aside`, 거짓이면 `Sheet`/`SheetContent`(`web/src/components/ui/sheet.tsx`)
- 카드 목록은 `approvalRefresh` 로 다시 읽는다. 넘기기를 적고 닫을 때 `approval` 사건이 오므로 새 다시 읽기를 더하지 않는다(설계 「넘기기 훅」)
- `web/src/components/chat/conversation-session-view.tsx` 는 395줄이고 웹 소스의 파일 길이 상한은 400 이다(`scripts/checks/check-file-length.mjs`). 이 파일에는 훅 호출 한 줄과 그 결과(카드 목록, 패널)를 넣는 자리만 둔다
- `useSearchParams`, `useRouter`, `usePathname` 은 `next/navigation` 에서 온다. `web/src/components/chat-panel.tsx` 가 이미 `useSearchParams` 를 쓴다

## 의도 메모

- 새 카드 판정은 서버 시각과 브라우저 시계를 비교하지 않는다(설계 DECISION3)
- 패널 닫기는 함수형 갱신으로 넘기기 패널일 때만 한다. 다른 패널을 닫지 않는다
- `onOpen` 은 모듈 상수, 나머지 콜백은 ref 로. 답 조각마다 다시 그려져도 화면 연결이 다시 열리면 안 된다

## 작업 항목

### 1. `web/src/components/chat/conversation-session-types.ts`

`SidePanelTarget` 에 `| { kind: "handoff"; handoff: Handoff }` 를 더한다.

### 2. `web/src/components/chat/use-conversation-handoffs.tsx`(새)

설계 「넘기기 훅」 대로. 인자: `conversationId`, `refreshKey`, `panelTarget`, `setPanelTarget`, `submit(text)`. 돌려주는 것: `list`(카드 목록 `ReactNode`)와 `panel`(`panelTarget.kind === "handoff"` 일 때의 `HandoffPanel`, 아니면 `null`).

- 읽기와 실패 처리는 지금 `HandoffList` 의 effect 를 옮긴다
- 대화마다 `seen`(처음 성공한 읽기의 번호), 감춘 번호를 둔다. 대화가 바뀌면 비운다
- 성공한 읽기마다 이 순서로 정한다. ① 알림 진입이 기다리고 있으면 첫 줄로 연다. ② 아니면 `newHandoffs(seen, list)` 의 첫 줄로 연다. ③ `seen` 에 모두 더한다. ④ 패널의 넘기기가 목록(감춘 것 제외)에 없으면 함수형 갱신으로 닫는다
- 알림 진입: `useSearchParams().get(HANDOFF_ENTRY_PARAM) === "open"` 이 참이 될 때 기다림으로 표시한다. 이미 성공한 읽기가 있으면 바로 처리한다. 처리하면 `router.replace(pathname)` 로 값을 지운다
- `done(handoff)`: 감춘 번호에 더하고 `submit(HANDOFF_TEXT[need].done)`, 패널이 그 넘기기면 닫는다

### 3. `web/src/components/chat/handoff-panel.tsx`(새)

- 머리: 카드 제목(`HANDOFF_TEXT[need].title`), 「새 창으로 열기」(`handoffBrowserHref`, `target="_blank"`, 누르면 패널 닫기), 닫기 단추(`TooltipButton`, 이름 「내 브라우저 닫기」)
- 본문: `BrowserScreen`(`startUrl={null}`, `startTab={handoff.tab}`, `onOpen={NOOP}`, `onClose` 는 패널 닫기, `onTabs`, `onReopen`). `onReopen` 은 화면 `key` 를 올려 새로 만든다. 주소 판정 상태와 마지막 탭 목록은 패널이 들고 있어 다시 만들어도 남는다
- 아래: 확인 줄(`changed` 일 때 「화면이 바뀌었어요. 다 마치셨나요?」, 「다 했어요, 이어서 해 주세요」, 「아직이에요」)과 「다 했어요」
- `Sheet` 쪽 `SheetContent` 의 `onEscapeKeyDown`: 대상이 화면 글자 칸(`aria-label="화면에 글자 넣기"`)이거나 크게 보기 중인 화면 안이면 `preventDefault`
- 넓은 쪽 `aside` 는 결과물 패널과 같은 폭(`w-[min(48rem,50vw)]`), `data-testid="handoff-panel"`

### 4. `web/src/components/chat/handoff-list.tsx`

읽기와 상태를 훅으로 옮기고 그리기만 남긴다. 속성은 `handoffs`, `onOpen(handoff)`, `onDone(handoff)`. 「내 브라우저 열기」 는 링크가 아니라 단추다. 머리 주석의 「새 창」 을 고친다.

### 5. `web/src/components/chat/conversation-session-view.tsx`

지금 `<HandoffList ... />` 자리를 훅이 준 `list` 로, 옆 패널 분기 끝에 `panel` 을 둔다. 파일이 400줄을 넘지 않게 한다.

### 6. 브라우저 테스트

- `web/test/browser/fake-screen.ts`(새): `user-browser-screen.spec.ts` 의 `fakeScreen` 과 그 타입, SSE 글 만드는 함수를 옮긴다. 이동만 하는 커밋으로 먼저 나누고 `user-browser-screen.spec.ts` 는 import 한다
- `web/test/browser/handoff-card.spec.ts`: 설계 「검증 전략」 의 REQ1~REQ6 사례. 넘기기 목록은 지금처럼 `page.route` 로 대역하고, 새 카드는 대역 목록을 바꾼 뒤 `approval` 사건 대신 목록을 다시 읽게 하는 동작(대화 사건)이 필요하면 `test/browser/README.md` 를 읽고 기존 대역 방식을 따른다. rule-62(테스트 전용 우회 금지)를 지킨다

### 7. 문서

- `docs/features/user-browser.md`: 「대화 위 화면」 절(패널 모양, 저절로 여는 때, 「다 했어요」, 주소 변화 확인, 알림 진입, 닫힘). 「로그인 화면」 의 웹 약속 가운데 「화면 넘기기 카드가 여는 `/browser?tab=<id>`」 줄을 「패널의 「새 창으로 열기」 가 여는」 으로 고친다. 머리의 `covers:` 에 `web/src/components/chat/handoff-panel.tsx` 와 `use-conversation-handoffs.tsx` 를 더한다
- `docs/features/naver-shopping.md` 「화면 넘기기」 의 「카드의 「내 브라우저 열기」 가 그 탭을 고른 채 「내 브라우저」 를 열고」 를 대화 위 패널로 고친다
- 이 계획 폴더(`docs/features/user-browser/tasks/handoff-drawer/`)는 구현 PR 에서 지운다(AGENTS.md 「tasks 는 구현 문서만 담는다」)

## 검증

```bash
cd web && pnpm lint && pnpm format:check && pnpm typecheck
cd web && pnpm test:node
cd web && pnpm test:browser test/browser/handoff-card.spec.ts test/browser/user-browser-screen.spec.ts
node scripts/checks/check-file-length.mjs
node --test test/repo/doc-files.test.ts test/repo/doc-links.test.ts test/repo/doc-references.test.ts test/repo/doc-code-references.test.ts test/repo/adr-index.test.ts
```

브라우저 테스트는 무거운 검사 대기열(`orch slot heavy`)로 감싸 돌린다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/components/chat/conversation-session-types.ts` | 수정 |
| `web/src/components/chat/use-conversation-handoffs.tsx` | 신규 |
| `web/src/components/chat/handoff-panel.tsx` | 신규 |
| `web/src/components/chat/handoff-list.tsx` | 수정 |
| `web/src/components/chat/conversation-session-view.tsx` | 수정 |
| `web/test/browser/fake-screen.ts` | 신규 (이동) |
| `web/test/browser/user-browser-screen.spec.ts` | 수정 (import) |
| `web/test/browser/handoff-card.spec.ts` | 수정 |
| `docs/features/user-browser.md` | 수정 |
| `docs/features/naver-shopping.md` | 수정 |
