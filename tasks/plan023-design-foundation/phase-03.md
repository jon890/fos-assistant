# Phase 03. 화면 틀과 대화 화면의 서랍, 메뉴, 확인 창을 Radix 부품으로 옮긴다

**Execution profile**: deep

## 목표

화면 틀과 대화 화면에서 손으로 만든 서랍, 대화 메뉴, 확인 창, 좁은 폭의 작업 과정 패널을 앞 phase 가 받은 Radix 부품으로 바꾼다.
포커스 가두기, `Esc`, 바깥 클릭, `aria` 를 우리가 다시 만들지 않게 하는 것이 목적이다.
`components/shell/`, `components/chat/`, `components/chat-panel.tsx` 의 80자 넘는 className 을 부품의 변형으로 모은다.

**범위 외**: 에이전트, 성격, 기억, 사용량, 실행 나무, 관리, 로그인 화면(뒤 계획). 그 화면의 확인 창 둘(`agent/persona-confirm.tsx`, `admin/visibility-confirm.tsx`)도 뒤 계획이 옮긴다.

## 컨텍스트

**근거 문서**: `docs/adr/ADR-023-화면-부품은-shadcn-ui-를-저장소에-복사해-쓴다.md` 의 「결과」,
`docs/flow.md` 「화면 틀」 의 `Esc` 차례와 「입력창은 사용자가 대화를 바꿀 때만 새로 만든다」, 「대화 목록」, 「작업 과정」 의 「작업 과정 패널」.

### 먼저 지금 모양을 읽는다

계획을 세울 때(main `a5d48f9`) 읽은 모양이다. 중지와 새 대화 화면이 그 뒤에 들어왔으므로 구현 전에 다시 연다.

```bash
# cwd: 저장소 root
ls web/src/components/shell web/src/components/chat web/src/components/chat/activity
grep -rn 'Escape\|keydown\|showModal\|<dialog\|role="menu"\|role="dialog"\|aria-modal\|addEventListener' web/src/components/shell web/src/components/chat web/src/components/chat-panel.tsx
grep -rhoE 'className="[^"]{80,}"' web/src/components/shell web/src/components/chat web/src/components/chat-panel.tsx | wc -l
```

| 파일 | 지금 |
| --- | --- |
| `shell/app-shell.tsx` | `md` 미만 서랍. `drawerOpen` 이 참이면 `body` 의 스크롤을 막고, `window` 의 캡처 단계에서 `Esc` 를 받아 닫는다. 안에 메뉴나 이름 입력칸이나 열린 `dialog` 가 있으면 건너뛴다. 뒤 덮개 단추의 이름은 「사이드바 닫기」 |
| `shell/conversation-nav.tsx` | 줄마다 `openMenu` 로 여는 `role="menu"`(이름 「{제목} 메뉴」). 바깥 클릭과 캡처 단계 `Esc` 를 직접 처리하고 닫으면 메뉴 단추로 포커스를 돌린다. 지우기 확인은 `<dialog aria-label="대화 지우기">` 를 `showModal()` 로 연다. 오류 글자와 지우기 단추가 `danger` 를 썼다(앞 phase 가 `destructive` 로 옮겼다) |
| `chat/activity/activity-panel.tsx` | `aside role="complementary" aria-label="작업 과정"`. `md` 미만은 화면 전체를 덮고, `md`~`lg` 는 겹치고, `lg` 이상은 대화 옆에 붙는다. 닫기 단추 이름은 「작업 과정 닫기」 |
| `chat-panel.tsx` | `window` 의 버블 단계 `Esc` 처리기 하나. `event.isComposing` 이나 `event.defaultPrevented` 면 건너뛴다. 패널이 열려 있으면 닫는다. 중지가 그 뒤에 더해졌다 |

`Composer` 는 `chat-panel.tsx` 안에서 `<Composer key={composerGeneration}>` 으로 그려지고, `ActivityPanel` 은 그 옆의 형제다.

### 테스트가 기대는 것

| 무엇 | 어디 |
| --- | --- |
| `getByRole("complementary", { name: "사이드바" })` | `chat.spec.ts`, `chat-attachment.spec.ts`, `shell.spec.ts` |
| 서랍이 닫혔는지를 `boundingBox()` 의 `x` 가 0보다 작은지로 본다 | `shell.spec.ts` 등 여러 곳. `grep -rn boundingBox test/browser` 로 찾는다 |
| `getByRole("menuitem", { name: "이름 바꾸기" })`, `"지우기"` | `shell.spec.ts`, `activity-panel.spec.ts` |
| `getByRole("dialog", { name: "대화 지우기" })` | `shell.spec.ts` |
| 「서랍의 Esc 는 대화 화면의 버블 처리기보다 먼저 돈다」 는 `Esc` 가 `window` 의 버블 처리기에 **닿지 않았는지**를 본다 | `shell.spec.ts` |
| `getByRole("button", { name: "작업 과정 닫기" })` | `activity-panel.spec.ts` |

## 의도 메모

- **접근성 이름을 바꾸지 않는다.** 「사이드바」, 「사이드바 열기」, 「사이드바 닫기」, 「{제목} 메뉴」, 「이름 바꾸기」, 「지우기」, 「대화 지우기」, 「작업 과정」, 「작업 과정 닫기」 가 그대로여야 한다.
  역할이 바뀌는 곳만 테스트를 바꾼다.
- **Radix 가 `Esc` 로 닫을 때 기본 동작을 막는지 가정하지 않는다. 테스트로 확인한다.**
  `docs/flow.md` 「화면 틀」 의 차례에서 2번(안쪽이 이미 처리했으면 아무것도 하지 않는다)은 `chat-panel.tsx` 처리기가 `event.defaultPrevented` 를 보는 것으로 지켜진다.
  Radix 가 막지 않으면 각 부품의 `onEscapeKeyDown` 에서 `event.preventDefault()` 를 부른다. 막으면 부르지 않는다. 어느 쪽인지 보고에 적는다.
- 「버블 처리기에 닿지 않는다」 는 지금 구현의 성질이지 규칙이 아니다. 규칙은 "안쪽이 처리한 `Esc` 로 패널이 닫히거나 답이 멈추지 않는다" 다. 테스트를 그 효과로 바꾼다.
- **입력창의 부모를 바꾸지 않는다.** `Composer` 를 Sheet 나 다른 감싸는 요소 안으로 옮기지 않는다. 부모가 바뀌면 입력창이 새로 만들어져 올려 둔 사진이 지워진다(`docs/flow.md` 「화면 틀」).
- 작업 과정 패널은 `lg` 이상에서 모달이 아니다. 대화 옆에 붙어 대화를 계속 쓸 수 있다. 그래서 Sheet 는 `lg` 미만에서만 쓴다.
  한 화면에 둘을 함께 그리지 않고, 폭을 보는 hook 으로 하나만 그린다. 첫 그림에서 폭을 모르는 동안은 옆에 붙는 모양으로 그린다.
- 기각: 사이드바 전체를 shadcn 의 `Sidebar` 부품으로 바꾸기. 접기, 서랍, 단축키, 저장된 접힘 상태를 이미 우리 규칙으로 만들었고, 그 부품은 자기 규칙을 함께 가져온다. 서랍만 Sheet 로 바꾼다.

## Blocked 조건

- `web/src/components/ui/sheet.tsx`, `dropdown-menu.tsx`, `alert-dialog.tsx`, `tooltip.tsx` 가 없다 → `PHASE_BLOCKED: 앞 phase 의 부품이 아직 없다`

## 작업 항목

### 1. 좁은 폭의 서랍을 Sheet 로 바꾼다 (`shell/app-shell.tsx`)

- `md` 미만의 `aside` 와 뒤 덮개 단추를 `Sheet` 하나로 바꾼다. `side="left"`, 너비는 지금과 같은 `w-72`
- `SheetContent` 안에 `<aside aria-label="사이드바">` 를 두고 그 안에 지금 `Sidebar` 를 그린다. 역할과 이름을 지키기 위해서다
- `SheetTitle` 을 `sr-only` 로 「사이드바」 로 둔다. Radix 가 제목 없는 대화상자를 경고하기 때문이다
- `md` 이상의 붙박이 사이드바는 지금처럼 `aside` 로 둔다. 같은 `Sidebar` 를 두 곳에 그리되 폭에 따라 하나만 보인다. 검색칸 `searchRef` 는 보이는 쪽에 붙인다
- `drawerOpen` 상태, 경로가 바뀌면 닫는 것, 단축키로 여는 것은 그대로 둔다
- `body` 스크롤 막기, 캡처 단계 `Esc` 처리기, 뒤 덮개 단추를 지운다. Sheet 가 한다
- 서랍을 여는 단추(「사이드바 열기」)는 `SheetTrigger` 로 두지 않고 지금 단추가 `setDrawerOpen(true)` 를 부르게 둔다. 단축키도 같은 상태를 쓰기 때문이다

### 2. 대화 메뉴를 DropdownMenu 로 바꾼다 (`shell/conversation-nav.tsx`)

- 줄 메뉴 단추를 `DropdownMenuTrigger` 로, 항목을 `DropdownMenuItem` 으로 바꾼다. 메뉴 이름 「{제목} 메뉴」 는 `DropdownMenuContent` 의 `aria-label` 로 옮긴다
- `openMenu` 상태, `menuButtons` ref, 바깥 클릭과 `Esc` 처리, 포커스 되돌리기를 지운다. DropdownMenu 가 한다
- 「이름 바꾸기」 를 고르면 지금처럼 그 줄이 입력칸이 된다. 입력칸의 `Esc` 처리(전파를 막는 것)는 그대로 둔다
- 메뉴 단추가 넓은 화면에서 마우스를 올릴 때만 보이고 좁은 화면에서 늘 보이는 규칙(`docs/flow.md` 「대화 목록」)은 그대로 둔다

### 3. 대화 지우기 확인을 AlertDialog 로 바꾼다

- `<dialog>` 와 `showModal()`, `onCancel`, `onKeyDown` 을 지우고 `AlertDialog` 로 바꾼다. 제목은 `AlertDialogTitle` 「대화 지우기」
- 본문 문장과 단추 이름 「취소」, 「지우기」 는 그대로다. 「지우기」 는 `buttonVariants({ variant: "destructive" })` 로 그린다
- 지우는 요청이 도는 동안 두 단추를 잠근다. 실패하면 창을 닫지 않고 오류를 창 안에 보인다. 지금 동작이 다르면 지금 동작을 따른다

### 4. 좁은 폭의 작업 과정 패널을 Sheet 로 바꾼다 (`chat/activity/activity-panel.tsx`)

- `lg` 이상은 지금의 `aside role="complementary" aria-label="작업 과정"` 을 그대로 둔다
- `lg` 미만은 `Sheet` 의 `side="right"` 로 그린다. `md`~`lg` 에서는 너비 `w-96`, `md` 미만은 화면 전체다. `SheetTitle` 은 보이는 제목 「작업 과정」 이다
- 닫기 단추 이름 「작업 과정 닫기」 를 지킨다. Sheet 가 넣는 닫기 단추와 겹치면 하나만 남긴다
- 패널을 닫는 `Esc` 는 `lg` 미만에서 Sheet 가 한다. `chat-panel.tsx` 처리기의 패널 닫기(3단계)는 `lg` 이상에서만 일한다. Sheet 가 닫으며 기본 동작을 막으면 저절로 그렇게 된다

### 5. `chat-panel.tsx` 의 `Esc` 처리기를 확인한다

- 처리기는 지금처럼 하나다. 차례도 그대로다(조합 중, 이미 처리됨, 패널, 중지)
- 앞 항목의 부품들이 `Esc` 로 닫을 때 `event.defaultPrevented` 가 참이 되는지 아래 테스트로 확인한다. 거짓이면 그 부품의 `onEscapeKeyDown` 에서 막는다

### 6. 아이콘 단추에 Tooltip 을 단다

`shell/`, `chat/`, `chat-panel.tsx` 에서 아이콘만 있는 단추(사이드바 펴기와 열기, 새 대화, 보내기, 중지, 첨부, 복사, 다시 생성, 판 넘기기, 작업 과정 닫기 등)에 `Tooltip` 을 단다.
풀이 글은 `aria-label` 과 같다. `title` 속성은 지운다. 화면 틀 맨 위에 `TooltipProvider` 를 하나 둔다.

### 7. 긴 className 을 변형으로 모은다

`shell/`, `chat/`, `chat-panel.tsx` 의 80자 넘는 className 을 본다. 계획 때 17곳이었다.

- 단추 모양이면 `Button` 의 `variant` 와 `size` 로 바꾼다
- 같은 조합이 두 곳 이상이면 그 폴더에 `cva` 로 변형을 하나 둔다. 한 곳뿐이면 `cn()` 으로 줄을 나눠 읽히게만 한다
- 배치(`flex`, `grid`, 폭, 반응형)는 변형으로 옮기지 않는다. 한 화면의 배치는 그 화면이 갖는다

### 8. 테스트를 바꾼다

- 서랍이 닫혔는지를 `boundingBox()` 의 `x` 로 보던 곳을 `toBeHidden()` 으로 바꾼다. 닫힌 Sheet 는 요소가 없기 때문이다. `md` 이상의 붙박이 사이드바를 보는 검사는 그대로 둔다
- `getByRole("dialog", { name: "대화 지우기" })` 를 `getByRole("alertdialog", { name: "대화 지우기" })` 로 바꾼다
- 「서랍의 Esc 는 대화 화면의 버블 처리기보다 먼저 돈다」 를 효과를 보는 검사로 바꾼다: 답을 만드는 중에 서랍을 열고 `Esc` 를 누르면 서랍만 닫히고 답은 멈추지 않는다

### 9. 이 phase 를 검증하는 테스트

`test/browser/shell.spec.ts` 와 `activity-panel.spec.ts` 에 아래를 더한다. 답을 만드는 중인 상태는 대역 Hermes 가 답을 붙잡는 기존 방법(`fixtures.ts`)을 쓴다.

| 경우 | 기대 |
| --- | --- |
| 답을 만드는 중에 대화 메뉴를 열고 `Esc` | 메뉴만 닫히고 포커스가 메뉴 단추로 돌아온다. 답은 계속 흐른다 |
| 답을 만드는 중에 지우기 확인 창을 열고 `Esc` | 창만 닫힌다. 답은 계속 흐른다 |
| `mobile` 폭에서 작업 과정 패널을 열고 `Esc` | 패널만 닫힌다. 답은 계속 흐른다 |
| `desktop` 폭에서 작업 과정 패널을 열고 `Esc` | 패널이 닫힌다. 한 번 더 누르면 답이 멈춘다 |
| 메뉴 바깥을 누른다 | 메뉴가 닫힌다 |
| 서랍 안에서 `Tab` 을 거듭 누른다 | 포커스가 서랍 밖으로 나가지 않는다 |
| 사진을 올린 뒤 서랍을 열고 닫는다 | 올린 사진의 미리보기가 그대로다 |

## 검증

```bash
# cwd: 저장소 root
grep -rn 'showModal\|<dialog\|role="menu"\|aria-modal\|document.body.style.overflow' web/src/components/shell web/src/components/chat web/src/components/chat-panel.tsx
grep -rn 'addEventListener("keydown"' web/src/components/shell web/src/components/chat web/src/components/chat-panel.tsx
grep -rn ' title=' web/src/components/shell web/src/components/chat
grep -rhoE 'className="[^"]{80,}"' web/src/components/shell web/src/components/chat web/src/components/chat-panel.tsx | wc -l
grep -rn 'style={{' web/src/
scripts/check-public-safe.sh
```

첫 `grep` 과 셋째 `grep` 은 아무것도 내지 않아야 한다.
둘째는 `chat-panel.tsx` 의 처리기 하나와 `shell/use-shortcuts.ts` 의 단축키만 나와야 한다. `use-shortcuts.ts` 는 이 phase 의 대상이 아니다.
넷째 수는 줄어야 하고, 남은 것은 배치만 담은 것이어야 한다. 남은 수와 까닭을 보고에 적는다.

```bash
# cwd: web/
pnpm typecheck
pnpm test:browser shell.spec.ts activity-panel.spec.ts chat.spec.ts chat-attachment.spec.ts
pnpm test:browser
```

그다음 AGENTS.md 「확인」 절의 명령을 적힌 순서대로 모두 돌린다.

문서를 고친다.

- `docs/code-architecture.md` 「아직 만들지 않은 것」 의 화면 부품 줄을 "나머지 화면(에이전트, 성격, 기억, 사용량, 실행 나무, 관리, 로그인)의 부품 교체" 만 남게 고친다
- 같은 문서 「디렉터리」 의 "옮기기 전까지는 지금 부품을 쓴다" 는 이 phase 에서 지우지 않는다. 나머지 화면을 옮기는 계획이 끝날 때 지운다
- `docs/flow.md` 「화면 틀」 의 `Esc` 차례 2번에 한 줄을 더한다: Radix 부품이 `Esc` 로 닫으면 기본 동작이 막혀 대화 화면의 처리기가 건너뛴다. 막지 않는 부품이면 `onEscapeKeyDown` 에서 막는다

끝나면 `tasks/plan023-design-foundation/index.json` 의 이 phase 를 `completed` 로, plan 의 `status` 를 `completed` 로 바꾼다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `web/src/components/shell/app-shell.tsx` | 수정 |
| `web/src/components/shell/conversation-nav.tsx` | 수정 |
| `web/src/components/shell/sidebar.tsx` | 수정 |
| `web/src/components/chat/activity/activity-panel.tsx` | 수정 |
| `web/src/components/chat-panel.tsx` | 수정 |
| `web/src/components/chat/*.tsx` | 수정. 아이콘 단추의 Tooltip 과 긴 className |
| `test/browser/shell.spec.ts` | 수정 |
| `test/browser/activity-panel.spec.ts` | 수정 |
| `test/browser/chat.spec.ts`, `chat-attachment.spec.ts` | 수정. 서랍이 닫혔는지 보는 곳 |
| `docs/code-architecture.md` | 수정 |
| `docs/flow.md` | 수정 |
