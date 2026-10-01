# Phase 03. 스위치, 비활성 단추, 모서리, 고르기 칸

**Execution profile**: deep

## 목표

「꺼짐」 글자 단추를 스위치로 바꾸고, 비활성 단추가 바랜 강조 색이 되는 문제를 고치고, 토큰 밖 모서리 값을 정리한다.
조작 요소가 한 가지 모양 규칙을 따르게 하려는 것이다.

**범위 외**: `Badge` 와 `Notice` 는 phase 02 가 끝냈다. 대화의 작업 과정은 phase 04. 움직임은 phase 06.

## 컨텍스트

- 부품은 `web/src/components/ui/` 에 있고 변형은 `cva` 로 모은다. `button.tsx` 가 그 패턴이다.
- phase 01 이 `radius-sm`(0.25rem), `radius-md`(0.75rem), `radius-lg`(1rem), `radius-xl`(1.25rem), `radius-2xl`(1.5rem) 와 `duration-fast` 를 `web/src/app/globals.css` 에 두었다.
- 폼 원소는 네이티브로 둔다(ADR-023 의 「감당할 것」). Radix `Switch`, `Select` 로 바꾸지 않는다.
- 도구와 스킬의 켜고 끄기는 폼 값이 아니다. 단추의 `onClick` 이 저장 요청을 보내고 `loading` 을 그린다.

**근거 문서**: `docs/adr/ADR-047-화면-색은-새벽-보라로-바꾸고-강조-색은-누를-것과-고른-것과-초점에만-쓴다.md` 의 「강조 색을 쓰는 곳」, 「모서리」 절, `docs/flow.md` 의 「기다리는 동안 보이는 것」 절, `docs/adr/ADR-023-화면-부품은-shadcn-ui-를-저장소에-복사해-쓴다.md`

## 의도 메모

- 스위치는 `<button role="switch">` 로 만든다. 체크박스로 바꾸면 저장 중 표시를 붙일 자리가 없다.
- 비활성 단추를 투명도로 그리지 않는다. 강조 색이 바래면 다른 색 단추로 보인다.

## 작업 항목

### 1. `web/src/components/ui/switch.tsx` 신규

```tsx
type SwitchProps = Omit<React.ComponentProps<"button">, "onChange"> & {
  checked: boolean; onCheckedChange(next: boolean): void; loading?: boolean;
};
export function Switch(props: SwitchProps)
```

- `<button type="button" role="switch" aria-checked={checked} aria-busy={loading || undefined}>` 이다. 누르면 `onCheckedChange(!checked)` 를 부른다. `disabled` 이거나 `loading` 이면 누를 수 없다.
- 모양: 폭 40px, 높이 24px 의 알약 길. 켜지면 `bg-primary`, 꺼지면 `bg-input`. 손잡이는 20px 원 `bg-card` 이고 켜지면 16px 오른쪽으로 옮긴다(`translate-x-4`). `transition-colors` 와 손잡이의 `transition-transform` 을 둔다. 길이는 `duration-fast` 다.
- 초점: `focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring`.
- 비활성: `disabled:cursor-not-allowed disabled:opacity-60`. 스위치는 켜짐과 꺼짐의 색이 이미 달라 투명도를 써도 뜻이 흐려지지 않는다.
- `loading` 이면 손잡이 안에 `LoaderCircle`(`size-3 animate-spin motion-reduce:animate-none`)을 그리고 `<span className="sr-only">저장 중</span>` 을 둔다. 길과 손잡이의 색은 그대로다. 「지금 보내는 중」 이 「누를 수 없음」 과 달라 보여야 한다(`docs/flow.md` 의 「기다리는 동안 보이는 것」).
- 접근성 이름은 부르는 쪽이 `aria-label` 로 준다.

옮길 곳:

| 파일 | 지금 | 바꿀 것 |
| --- | --- | --- |
| `web/src/components/agent/agent-tools-section.tsx` 의 줄마다 있는 `Button` (`aria-pressed`, 「켜짐」/「꺼짐」) | 글자 단추 | `<Switch checked={tool.enabled} loading={pendingToolName === tool.name} disabled={disabled} title={reason} aria-label={`${tool.label} 도구`} onCheckedChange={() => toggle(tool)} />` |
| `web/src/components/agent/agent-skills-section.tsx` 의 `editable` 일 때 `Button` | 글자 단추 | `<Switch checked={skill.enabled} loading={pendingName === skill.name && confirming === null} disabled={busy} aria-label={`${skill.name} 스킬`} onCheckedChange={() => void toggle(skill)} />` |

`person-list.tsx` 와 에이전트 관리의 켜고 끄기는 확인 창을 거치는 단추라 그대로 둔다.

`test/browser/agent-tools.spec.ts`(39~191줄)와 `test/browser/skills.spec.ts`(377~398줄)가 이 단추를 `getByRole("button", { name: "켜짐" })`, `{ name: "꺼짐" }`, `{ name: "저장 중" }` 으로 찾는다. 아래로 바꾼다.

| 지금 | 바꿀 것 |
| --- | --- |
| 줄 안의 `getByRole("button", { name: "꺼짐" })` 를 누른다 | 그 줄의 `getByRole("switch")` 를 누른다 |
| `getByRole("button", { name: "켜짐" })` 이 보인다 | 그 줄의 `getByRole("switch")` 가 `toHaveAttribute("aria-checked", "true")` |
| `getByRole("button", { name: "꺼짐" })` 이 보인다 | `toHaveAttribute("aria-checked", "false")` |
| `getByRole("button", { name: "저장 중" })` 이 보인다 | 그 줄의 `getByRole("switch")` 가 `toHaveAttribute("aria-busy", "true")` |
| 단추가 잠겼다(`toBeDisabled`) | 스위치가 `toBeDisabled()` |

검사가 확인하던 동작(누르면 저장, 확인 창, 잠김)은 그대로 단언한다. `test/browser/admin.spec.ts` 와 `test/browser/people.spec.ts` 는 배지 글자만 보므로 고치지 않는다.

### 2. `web/src/components/ui/button.tsx` 의 비활성과 눌림

- 공통 클래스의 `disabled:opacity-50` 을 뺀다. 변형마다 비활성 모양을 둔다.
  - `default`, `destructive`, `secondary`: `disabled:bg-muted disabled:text-muted-foreground`
  - `outline`, `ghost`, `link`: `disabled:text-muted-foreground`. `outline` 은 `disabled:border-border` 도 둔다
- `loading` 일 때는 원래 색을 지킨다. 「지금 보내는 중」 과 「누를 수 없음」 이 달라 보여야 한다(`docs/flow.md` 의 「기다리는 동안 보이는 것」). `busy` 일 때 `data-loading` 속성을 붙이고, 비활성 모양을 `disabled:not-data-loading:` 조건으로 건다.
- `transition-all` 을 `transition-[color,background-color,border-color,transform]` 으로 바꾼다. `active:not-aria-[haspopup]:translate-y-px` 를 `active:not-aria-[haspopup]:scale-[0.97]` 로 바꾼다. 길이는 `duration-fast` 다.
- `outline` 변형의 테두리는 `border-border` 그대로다.
- 크기 변형의 `rounded-[min(var(--radius-md),10px)]`, `rounded-[min(var(--radius-md),12px)]`, `in-data-[slot=button-group]:rounded-lg` 를 `rounded-md` 로 맞춘다.
- `badge.tsx` 의 `transition-all` 도 `transition-colors` 로 바꾼다.
- `web/src/components/chat/composer.tsx` 의 보내기 단추가 비활성일 때 `muted` 바탕으로 보이는지 확인한다. 그 단추에 따로 적은 `disabled:` 클래스가 있으면 뺀다.

### 3. 모서리 정리

토큰 밖의 모서리 값을 ADR-047 「모서리」 표의 뜻에 맞게 바꾼다.

| 무엇 | 클래스 |
| --- | --- |
| 대화 입력창(`composer.tsx`), 그 뼈대(`page-skeleton.tsx`) | `rounded-2xl` |
| 내 말풍선(`message-bubble.tsx`) | `rounded-xl rounded-br-sm`. 시안의 말꼬리 모양이다 |
| `Card`(`card.tsx`), 대화상자(`dialog.tsx`, `alert-dialog.tsx`), 메뉴(`dropdown-menu.tsx`), 에이전트 카드(`agent-picker.tsx`, `app/agents/page.tsx`), 연결 카드(`connector-catalog.tsx`), 묻는 카드(`ask-card.tsx`), 작업 과정 블록(`activity-block.tsx`), 멘션과 스킬 메뉴(`agent-mention.tsx`, `skill-command-menu.tsx`)의 바깥 | `rounded-lg` |
| 메뉴 안의 줄, 실행 카드의 링크 초점 테두리 | `rounded-md` |
| 툴팁 화살표(`tooltip.tsx` 의 `rounded-[2px]`) | `rounded-sm` |

`rounded-3xl` 과 `rounded-[...]` 가 `web/src` 에 남지 않아야 한다. 

### 4. `NativeSelect` 와 사이드바 검색칸

- `web/src/components/ui/native-select.tsx`: `appearance-none` 으로 브라우저 화살표를 가리고, `<span className="relative">` 로 감싸 오른쪽에 `ChevronDown` 아이콘(`pointer-events-none absolute`, `text-muted-foreground`)을 둔다. `className` 은 지금처럼 `<select>` 에 준다. 오른쪽 안쪽 여백을 아이콘만큼 늘린다. 감싼 요소는 `block w-full` 이어야 지금 폭이 그대로다. 주석의 「펼침 화살표는 브라우저의 것을 그대로 둔다」 를 고친다.
- `web/src/components/shell/sidebar.tsx` 의 맨 `<input>` 검색칸을 `Input` 부품으로 바꾼다. `ref`(`searchRef`), 접근성 이름, 단축키 동작을 지킨다. 바탕은 `bg-background` 로 둔다.

### 5. 검사

- `test/browser/design-tokens.spec.ts`: 비활성 보내기 단추(빈 입력)의 바탕이 `--muted`, 글자색이 `--muted-foreground` 이고 `opacity` 가 `1` 이다.
- `test/browser/agent-tools.spec.ts` 에 「스위치를 Space 로 켜고 끈다」 를 더한다. `role="switch"` 에 초점을 두고 Space 를 눌러 `aria-checked` 가 바뀌는 것을 본다. 잠긴 스위치(`disabled` 인 것)는 눌러도 `aria-checked` 가 바뀌지 않는다는 단언도 둔다.
- `test/unit/design-tokens.test.ts`: `web/src` 의 `.tsx` 와 `.ts` 에 `rounded-3xl` 과 `rounded-[` 가 없다.

## 검증

```bash
# cwd: 저장소 root
node --test test/unit/design-tokens.test.ts
! grep -rnE 'rounded-3xl|rounded-\[' web/src
! grep -rnE 'disabled:opacity-50' web/src/components/ui/button.tsx
cd web && pnpm typecheck && pnpm lint
cd web && pnpm test:browser test/browser/design-tokens.spec.ts test/browser/agent-tools.spec.ts test/browser/skills.spec.ts test/browser/admin.spec.ts test/browser/people.spec.ts test/browser/shell.spec.ts test/browser/chat.spec.ts test/browser/memory.spec.ts test/browser/model-choice.spec.ts test/browser/start-screen.spec.ts test/browser/loading.spec.ts
```

기대값: 모두 종료 코드 0.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/components/ui/switch.tsx` | 신규 |
| `web/src/components/ui/button.tsx` | 수정 |
| `web/src/components/ui/badge.tsx` | 수정 |
| `web/src/components/ui/card.tsx` | 수정 |
| `web/src/components/ui/dialog.tsx` | 수정 |
| `web/src/components/ui/alert-dialog.tsx` | 수정 |
| `web/src/components/ui/dropdown-menu.tsx` | 수정 |
| `web/src/components/ui/tooltip.tsx` | 수정 |
| `web/src/components/ui/native-select.tsx` | 수정 |
| `web/src/components/ui/page-skeleton.tsx` | 수정 |
| `web/src/components/shell/sidebar.tsx` | 수정 |
| `web/src/components/chat/*.tsx` | 수정 |
| `web/src/components/chat/activity/activity-block.tsx` | 수정 |
| `web/src/components/agent/agent-tools-section.tsx` | 수정 |
| `web/src/components/agent/agent-skills-section.tsx` | 수정 |
| `web/src/components/connector/connector-catalog.tsx` | 수정 |
| `web/src/components/usage/execution-card.tsx` | 수정 |
| `web/src/app/agents/page.tsx` | 수정 |
| `test/unit/design-tokens.test.ts` | 수정 |
| `test/browser/design-tokens.spec.ts` | 수정 |
| `test/browser/agent-tools.spec.ts` | 수정 |
| `test/browser/skills.spec.ts` | 수정 |
| `test/browser/chat.spec.ts` | 수정 |
| `web/src/components/chat/activity/activity-panel.tsx` | 수정 |
| `web/src/components/chat/artifact/artifact-panel.tsx` | 수정 |
