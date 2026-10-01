# Phase 02. 공통 부품: 의미 색 배지, 알림 상자, 스위치, 비활성 단추, 모서리

**Execution profile**: deep

## 목표

상태를 색과 아이콘으로 구분하는 공통 부품을 만들고, 화면마다 손으로 복사한 알림 상자와 「꺼짐」 글자 단추를 그 부품으로 옮긴다.
다른 화면 작업이 같은 부품을 가져다 쓰게 하려는 것이다.

**범위 외**: 대화의 작업 과정 블록과 문구는 phase 03. 사용량 화면의 역할별 표시는 phase 04. 움직임은 phase 05.

## 컨텍스트

- 부품은 `web/src/components/ui/` 에 있고 변형은 `class-variance-authority` 의 `cva` 로 모은다. `badge.tsx`, `button.tsx` 가 그 패턴이다.
- phase 01 이 `success`, `warning`, `info`, `destructive` 와 각 `-soft`, `primary-soft-foreground`, `foreground-soft`, `radius-*` 토큰을 `globals.css` 에 두었다.
- 폼 원소는 네이티브로 둔다(ADR-023 의 「감당할 것」). 폼이 `FormData` 로 값을 읽고 브라우저 검사가 `selectOption` 과 `check` 를 쓴다. Radix `Switch`, `Select` 로 바꾸지 않는다.
- 화면 문구는 `web/AGENTS.md` 의 「화면 문구」 절을 따른다. 해요체다.

**근거 문서**: `docs/adr/ADR-047-화면-색은-새벽-보라로-바꾸고-강조-색은-누를-것과-고른-것과-초점에만-쓴다.md` 의 「강조 색을 쓰는 곳」, 「의미 색」, 「모서리」 절, `docs/code-architecture.md` 의 「우리 화면의 정체성」 절, `docs/adr/ADR-023-화면-부품은-shadcn-ui-를-저장소에-복사해-쓴다.md`

## 의도 메모

- 알림 상자를 Radix 나 shadcn 의 `Alert` 로 받지 않고 직접 만든다. 필요한 것은 색 넷과 아이콘 하나이고 동작이 없다.
- 스위치는 `<button role="switch">` 로 만든다. 지금 켜고 끄기가 단추의 `onClick` 으로 저장 요청을 보내고 `loading` 을 그리므로, 폼 값이 아니라 동작이다. 체크박스로 바꾸면 저장 중 표시를 붙일 자리가 없다.
- 비활성 단추를 투명도로 그리지 않는다. 강조 색이 바래면 다른 색 단추로 보인다.

## 작업 항목

### 1. `web/src/components/ui/badge.tsx` 의 의미 색 변형

- 변형을 더한다: `success`(`bg-success-soft text-success`), `warning`(`bg-warning-soft text-warning`), `info`(`bg-info-soft text-info`). `destructive` 는 `bg-destructive-soft text-destructive` 로 바꾼다.
- `default` 변형(글자색 테두리와 굵은 글자)을 「눈에 띄어야 하는 상태」 로 쓰던 곳을 의미 색으로 옮긴다.

  | 파일 | 지금 | 바꿀 것 |
  | --- | --- | --- |
  | `web/src/components/usage/execution-card.tsx`, `web/src/components/usage/execution-table.tsx`, `web/src/components/execution/execution-detail.tsx` | 실패 `default`, 그 밖 `outline` | 실패 `destructive`, 성공 `success`, 실행 중 `info`, 취소됨 `outline` |
  | `web/src/components/admin/agent-card.tsx`, `web/src/components/agent/agent-admin-section.tsx`, `web/src/components/admin/person-list.tsx` | 꺼짐 `default`, 사용 중 `outline` | 꺼짐 `warning`, 사용 중과 켜짐 `success` |
  | `web/src/components/agent/agent-skills-section.tsx` 의 읽기 전용 배지 | 켜짐 `default` | 켜짐 `success`, 꺼짐 `outline` |

- `execution-card.tsx` 의 실패 카드 테두리 `ring-foreground` 를 `ring-destructive` 로 바꾼다.
- 배지 앞 아이콘은 넣지 않는다. 배지는 글자가 뜻을 전한다.

### 2. `web/src/components/ui/notice.tsx` 신규

```tsx
type NoticeProps = React.ComponentProps<"div"> & { variant?: "info" | "success" | "warning" | "error" | "neutral" };
export function Notice({ variant = "neutral", className, children, ...props }: NoticeProps)
```

- 모양: `flex items-start gap-2 rounded-md px-3 py-2 text-sm`. 변형마다 `bg-{색}-soft text-{색}` 이고 `neutral` 은 `bg-muted text-foreground` 다. `error` 는 `destructive` 토큰을 쓴다.
- 앞에 `lucide-react` 아이콘을 `aria-hidden` 으로 둔다: `info` 는 `Info`, `success` 는 `CircleCheck`, `warning` 은 `TriangleAlert`, `error` 는 `CircleX`. `neutral` 은 아이콘이 없다.
- `role`, `data-testid` 를 포함한 나머지 속성은 그대로 넘긴다. 부르는 쪽이 `role="alert"` 를 정한다.
- `data-slot="notice"` 와 `data-variant` 를 붙인다.
- 본문은 `<div className="min-w-0 flex-1">` 로 감싼다. 안에 단추가 들어오는 곳이 있다.

`rounded-md bg-muted p-3 text-sm` 이나 `rounded-md bg-muted px-3 py-2 text-sm` 로 손으로 그린 안내와 오류 상자를 `Notice` 로 옮긴다.
아래 명령으로 후보를 찾고, 하나씩 열어 뜻에 맞는 변형을 고른다. 코드 블록(`markdown.tsx`), 링크 단추(`chat-panel.tsx` 의 「새 대화」 `Link`), 목록 줄처럼 알림이 아닌 것은 옮기지 않는다.

```bash
# cwd: 저장소 root
grep -rnE 'rounded-md (border border-border )?bg-muted (p|px)-' web/src
```

| 뜻 | 변형 |
| --- | --- |
| 요청이 실패했다, 저장하지 못했다 (`error` 상태 값을 그리는 곳, `role="alert"`) | `error` |
| 조심해야 한다, 켤 수 없다, 표에 없는 도구가 켜져 있다 | `warning` |
| 설명과 안내 | `info` |
| 저장했다, 연결됐다 | `success` |
| 뜻이 정해지지 않는 회색 상자 | `neutral` |

`text-sm text-destructive` 글자만으로 오류를 그리는 곳(`execution-detail.tsx` 의 「실행 정보를 불러오지 못했어요」, `model-picker.tsx` 의 `model-picker-error`, `agent-tools-section.tsx` 의 「이 도구를 켜지 못했어요」)은 줄 안의 짧은 글이라 그대로 둔다.
`web/src/components/chat/message-list.tsx` 의 `turn-error` 는 `<li>` 안에 `Notice variant="error"` 를 둔다. `data-testid="turn-error"` 는 `<li>` 에 남긴다. 「다시 시도」 는 `Button variant="link" size="xs"` 로 바꾼다. `data-testid="turn-error-retry"` 를 지킨다.
같은 파일의 `no-answer` 줄의 「다시 시도」 도 같은 단추로 바꾼다.

### 3. `web/src/components/ui/switch.tsx` 신규

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
- 접근성 이름은 부르는 쪽이 `aria-label` 로 준다.

옮길 곳:

| 파일 | 지금 | 바꿀 것 |
| --- | --- | --- |
| `web/src/components/agent/agent-tools-section.tsx` 의 줄마다 있는 `Button` (`aria-pressed`, 「켜짐」/「꺼짐」) | 글자 단추 | `<Switch checked={tool.enabled} loading={pendingToolName === tool.name} disabled={disabled} title={reason} aria-label={`${tool.label} 도구`} onCheckedChange={() => toggle(tool)} />` |
| `web/src/components/agent/agent-skills-section.tsx` 의 `editable` 일 때 `Button` | 글자 단추 | `<Switch checked={skill.enabled} loading={pendingName === skill.name && confirming === null} disabled={busy} aria-label={`${skill.name} 스킬`} onCheckedChange={() => void toggle(skill)} />` |

`person-list.tsx` 와 에이전트 관리의 켜고 끄기는 확인 창을 거치는 단추라 그대로 둔다.
`test/browser/agent-tools.spec.ts`, `test/browser/skills.spec.ts`, `test/browser/admin.spec.ts` 가 이 단추를 「켜짐」, 「꺼짐」 글자와 `aria-pressed` 로 찾는다. `getByRole("switch", { name: ... })` 와 `toHaveAttribute("aria-checked", "true")` 로 고친다. 검사가 확인하던 동작(누르면 저장, 확인 창, 잠김)은 그대로 단언한다.

### 4. `web/src/components/ui/button.tsx` 의 비활성과 눌림

- 공통 클래스의 `disabled:opacity-50` 을 뺀다. 변형마다 비활성 모양을 둔다.
  - `default`, `destructive`, `secondary`: `disabled:bg-muted disabled:text-muted-foreground`
  - `outline`, `ghost`, `link`: `disabled:text-muted-foreground`. `outline` 은 `disabled:border-border` 도 둔다
- `loading` 일 때는 원래 색을 지킨다. 「지금 보내는 중」 과 「누를 수 없음」 이 달라 보여야 한다(`docs/flow.md` 의 「기다리는 동안 보이는 것」). `busy` 일 때 `data-loading` 속성을 붙이고, 비활성 모양을 `disabled:not-data-loading:` 조건으로 건다.
- `transition-all` 을 `transition-[color,background-color,border-color,transform]` 으로 바꾼다. `active:not-aria-[haspopup]:translate-y-px` 를 `active:not-aria-[haspopup]:scale-[0.97]` 로 바꾼다. 길이는 `duration-fast` 다.
- `outline` 변형의 테두리는 `border-border` 그대로다.
- 크기 변형의 `rounded-[min(var(--radius-md),10px)]`, `rounded-[min(var(--radius-md),12px)]`, `in-data-[slot=button-group]:rounded-lg` 를 `rounded-md` 로 맞춘다.
- `badge.tsx` 의 `transition-all` 도 `transition-colors` 로 바꾼다.
- `web/src/components/chat/composer.tsx` 의 보내기 단추가 비활성일 때 `muted` 바탕으로 보이는지 확인한다. 그 단추에 따로 적은 `disabled:` 클래스가 있으면 뺀다.

### 5. 모서리 정리

토큰 밖의 모서리 값을 ADR-047 「모서리」 표의 뜻에 맞게 바꾼다.

| 무엇 | 클래스 |
| --- | --- |
| 대화 입력창(`composer.tsx`), 그 뼈대(`page-skeleton.tsx`) | `rounded-2xl` |
| 내 말풍선(`message-bubble.tsx`) | `rounded-xl rounded-br-sm`. 시안의 말꼬리 모양이다 |
| `Card`(`card.tsx`), 대화상자(`dialog.tsx`, `alert-dialog.tsx`), 메뉴(`dropdown-menu.tsx`), 에이전트 카드(`agent-picker.tsx`, `app/agents/page.tsx`), 연결 카드(`connector-catalog.tsx`), 묻는 카드(`ask-card.tsx`), 작업 과정 블록(`activity-block.tsx`), 멘션과 스킬 메뉴(`agent-mention.tsx`, `skill-command-menu.tsx`)의 바깥 | `rounded-lg` |
| 메뉴 안의 줄, 실행 카드의 링크 초점 테두리 | `rounded-md` |

`rounded-3xl` 과 `rounded-[...]` 가 `web/src` 에 남지 않아야 한다. `test/unit/design-tokens.test.ts` 에 이 둘이 없다는 검사를 더한다(`web/src` 의 `.tsx` 와 `.ts` 를 읽는다).

### 6. `NativeSelect` 와 사이드바 검색칸

- `web/src/components/ui/native-select.tsx`: `appearance-none` 으로 브라우저 화살표를 가리고, `<span className="relative">` 로 감싸 오른쪽에 `ChevronDown` 아이콘(`pointer-events-none absolute`, `text-muted-foreground`)을 둔다. `className` 은 지금처럼 `<select>` 에 준다. 오른쪽 안쪽 여백을 아이콘만큼 늘린다. 감싼 요소는 `block w-full` 이어야 지금 폭이 그대로다. 주석의 「펼침 화살표는 브라우저의 것을 그대로 둔다」 를 고친다.
- `web/src/components/shell/sidebar.tsx` 의 맨 `<input>` 검색칸을 `Input` 부품으로 바꾼다. `ref`(`searchRef`), 접근성 이름, 단축키 동작을 지킨다. 바탕은 `bg-background` 로 둔다.

### 7. 검사

- `test/browser/design-tokens.spec.ts` 에 더한다.
  - 사용량 실행 기록(`/usage?tab=executions`)에서 성공 배지의 글자색이 `--success`, 바탕이 `--success-soft` 다. 검사 대역이 만든 성공 실행 하나를 쓴다. 기존 `usage.spec.ts` 가 실행을 만드는 방법을 따른다.
  - 비활성 보내기 단추(빈 입력)의 바탕이 `--muted`, 글자색이 `--muted-foreground` 이고 `opacity` 가 `1` 이다.
  - `[data-slot="notice"][data-variant="error"]` 의 바탕이 `--destructive-soft` 다. `chat.spec.ts` 가 `turn-error` 를 만드는 방법(대역이 붐빔을 돌려주는 에이전트)을 따른다.
- 스위치: `test/browser/agent-tools.spec.ts` 에 「스위치를 Space 로 켜고 끈다」 를 더한다. `role="switch"` 에 초점을 두고 Space 를 눌러 `aria-checked` 가 바뀌는 것을 본다.
- 기존 검사가 「꺼짐」, 「켜짐」 글자를 배지에서 찾는 곳(`people.spec.ts`, `admin.spec.ts`)은 글자가 그대로라 고칠 필요가 없다. 돌려서 확인한다.

## 검증

```bash
# cwd: 저장소 root
node --test test/unit/design-tokens.test.ts
! grep -rnE 'rounded-3xl|rounded-\[' web/src
! grep -rnE 'disabled:opacity-50' web/src/components/ui/button.tsx
cd web && pnpm typecheck && pnpm lint
cd web && pnpm test:browser test/browser/design-tokens.spec.ts test/browser/agent-tools.spec.ts test/browser/skills.spec.ts test/browser/admin.spec.ts test/browser/people.spec.ts test/browser/usage.spec.ts test/browser/chat.spec.ts test/browser/shell.spec.ts test/browser/memory.spec.ts test/browser/connector-connection.spec.ts test/browser/persona.spec.ts
```

기대값: 모두 종료 코드 0.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/components/ui/notice.tsx` | 신규 |
| `web/src/components/ui/switch.tsx` | 신규 |
| `web/src/components/ui/badge.tsx` | 수정 |
| `web/src/components/ui/button.tsx` | 수정 |
| `web/src/components/ui/card.tsx` | 수정 |
| `web/src/components/ui/dialog.tsx` | 수정 |
| `web/src/components/ui/alert-dialog.tsx` | 수정 |
| `web/src/components/ui/dropdown-menu.tsx` | 수정 |
| `web/src/components/ui/native-select.tsx` | 수정 |
| `web/src/components/ui/page-skeleton.tsx` | 수정 |
| `web/src/components/shell/sidebar.tsx` | 수정 |
| `web/src/components/chat-panel.tsx` | 수정 |
| `web/src/components/chat/*.tsx` | 수정 |
| `web/src/components/chat/activity/activity-block.tsx` | 수정 |
| `web/src/components/agent/*.tsx` | 수정 |
| `web/src/components/admin/*.tsx` | 수정 |
| `web/src/components/connector/*.tsx` | 수정 |
| `web/src/components/memory/*.tsx` | 수정 |
| `web/src/components/usage/*.tsx` | 수정 |
| `web/src/components/execution/execution-detail.tsx` | 수정 |
| `web/src/app/admin/people/people-admin-panel.tsx` | 수정 |
| `web/src/app/agents/page.tsx` | 수정 |
| `web/src/app/agents/[code]/page.tsx` | 수정 |
| `web/src/app/agents/[code]/skills/[name]/page.tsx` | 수정 |
| `test/unit/design-tokens.test.ts` | 수정 |
| `test/browser/design-tokens.spec.ts` | 수정 |
| `test/browser/agent-tools.spec.ts` | 수정 |
| `test/browser/skills.spec.ts` | 수정 |
| `test/browser/admin.spec.ts` | 수정 |
