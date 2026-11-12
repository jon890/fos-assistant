# Phase 01. 화면 부품의 알림 속성, 주소 변화 판정, 알림 갈 곳

**Execution profile**: standard

## 목표

패널이 쓸 바닥을 만든다. `BrowserScreen` 이 탭 목록과 「다시 열기」 를 밖에 넘길 수 있게 하고, 주소 변화 판정과 알림 갈 곳을 순수 함수로 둔다.

**범위 외**: 패널, 훅, 카드 변경은 phase 02 다. `/browser` 화면의 동작은 바꾸지 않는다.

## 컨텍스트

**요구사항**: REQ4, REQ5

**근거 문서**: `docs/features/user-browser/spec.md`, `docs/features/user-browser/design.md` 의 「주소 변화 판정」, 「알림 갈 곳」, 「옆 패널」

- `web/src/components/browser/use-screen-stream.ts` 의 `onEvent` 가 `name === "tabs"` 분기에서 `setTabs` 한다. `tabs` 상태는 처음 `[]` 이고 `reopen()` 이 `[]` 로 비운다. 알림은 이 분기에서만 한다
- 연결 effect 는 의존 목록에 `onOpen` 을 둔다. 새 속성은 ref 로 읽어 의존 목록에 넣지 않는다(rule-69, `docs/code-rules/rule-69.md`)
- `ScreenTab` 타입은 `web/src/components/browser/browser-screen-toolbar.tsx` 에 있다(`id`, `url`, `title`, `active`)

## 의도 메모

- 패널 전용 화면 부품을 만들지 않는다. 입력 변환과 확인 창을 두 벌로 두게 된다(설계 DECISION2)
- `onReopen` 이 없으면 지금처럼 안에서 다시 잇는다. `/browser` 는 넘기지 않는다

## 작업 항목

### 1. `web/src/components/browser/use-screen-stream.ts`

- 인자에 `onTabs?: (tabs: ScreenTab[]) => void` 를 더한다. `useRef` 에 최신 값을 두고(렌더마다 `ref.current = onTabs`), `tabs` 사건 분기에서 `setTabs(next)` 뒤 `onTabsRef.current?.(next)` 를 부른다
- 의존 목록은 바꾸지 않는다

### 2. `web/src/components/browser/browser-screen.tsx`

- 속성 `onTabs?` 와 `onReopen?: () => void` 를 더한다. `onTabs` 는 `useScreenStream` 에 넘긴다
- 「다시 열기」 단추는 `onReopen` 이 있으면 그것을, 없으면 지금의 `reopen` 을 부른다

### 3. `web/src/lib/handoff.ts`

- `type HandoffWatch = { phase: "waiting" } | { phase: "watching"; base: string } | { phase: "off" }`
- `watchHandoffTab(state, tab: string, tabs: readonly { id: string; url: string }[]): { state: HandoffWatch; changed: boolean }` 을 설계의 표대로. 주소 비교는 `#` 뒤를 뺀 값이다(`withoutHash(url)`)
- `rebaseHandoffWatch(state, tab, tabs): HandoffWatch` 는 「아직이에요」 다. 넘긴 탭이 있으면 그 주소로 `watching`, 없으면 `off`. 마지막 `tabs` 목록은 부르는 쪽이 들고 있다
- `newHandoffs(seen: ReadonlySet<string>, handoffs: readonly Handoff[]): Handoff[]` 는 `seen` 에 없는 줄을 받은 순서(최신 먼저) 그대로 돌려준다

### 4. `web/src/lib/notification.ts`

- `notificationHref` 에서 `view.kind === "HANDOFF_REQUESTED"` 이고 갈 곳이 대화면 `/chat/<id>?handoff=open`. 상수 `HANDOFF_ENTRY_PARAM = "handoff"`, 값 `"open"` 을 같은 파일에 두고 phase 02 가 쓴다

### 5. 단위 테스트

- `web/test/unit/handoff.test.ts`(있는 파일에 더한다): `watchHandoffTab` 의 표 다섯 줄, `#` 조각만 다른 주소는 바뀜이 아님, `off` 는 그대로, `rebaseHandoffWatch` 의 두 갈래, `newHandoffs`
- `web/test/unit/notification.test.ts`: `HANDOFF_REQUESTED` 는 `?handoff=open`, 같은 대화 갈 곳의 다른 종류는 그대로

## 검증

```bash
cd web && pnpm lint && pnpm format:check && pnpm typecheck
cd web && node --test test/unit/handoff.test.ts test/unit/notification.test.ts test/unit/browser-screen-input.test.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/components/browser/use-screen-stream.ts` | 수정 (`onTabs`) |
| `web/src/components/browser/browser-screen.tsx` | 수정 (`onTabs`, `onReopen`) |
| `web/src/lib/handoff.ts` | 수정 (판정 함수) |
| `web/src/lib/notification.ts` | 수정 (`?handoff=open`) |
| `web/test/unit/handoff.test.ts` | 수정 |
| `web/test/unit/notification.test.ts` | 수정 |
