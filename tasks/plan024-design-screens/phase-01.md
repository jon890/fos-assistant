# Phase 01. 에이전트와 성격과 기억 화면을 shadcn/ui 부품으로 옮긴다

**Execution profile**: standard

## 목표

에이전트 목록, 성격 편집, 추천 질문 편집, 기억 화면이 `components/ui/` 의 shadcn/ui 부품을 쓰게 한다.
손으로 만든 확인 창 `persona-confirm.tsx` 를 AlertDialog 로 바꾼다.

**범위 외**: 사용량과 실행 나무(phase-02), 관리 화면과 로그인과 옛 부품 삭제(phase-03).
화면 틀과 대화 화면은 앞선 디자인 기반 변경이 이미 옮겼다.

## 컨텍스트

**근거 문서**: `docs/adr/ADR-023-화면-부품은-shadcn-ui-를-저장소에-복사해-쓴다.md`,
`docs/code-architecture.md` 「디렉터리」 「색과 간격은 테마 토큰이 소유한다」 「우리 화면의 정체성」,
`web/AGENTS.md` 「색과 간격은 테마 토큰이 소유한다」.

앞선 디자인 기반 변경이 끝난 상태에서 이 phase 가 돈다. 그 변경이 한 것은 아래와 같다.

- 토큰 이름을 ADR-023 의 「토큰 이름」 표대로 바꿨다. `text-muted` 는 `text-muted-foreground`, `bg-surface` 는 `bg-muted` 다
- `components/ui/` 에 shadcn 의 `button`, `badge`, `skeleton`, `input`, `textarea`, `label`, `dialog`, `alert-dialog`,
  `dropdown-menu`, `sheet`, `tooltip`, `separator` 를 두었다. `lib/utils.ts` 에 `cn` 이 있다
- `icon-button.tsx` 를 지우고 `lucide-react` 를 들였다. 화면 틀(`components/shell/`)과 대화 화면(`components/chat/`)을 옮겼다

**이 phase 가 옮길 파일을 시작할 때 다시 모은다.** 계획을 쓴 뒤 추천 질문 편집과 새 대화 화면이 파일을 더했다.

```bash
# cwd: 저장소 root
ls web/src/components/agent web/src/components/memory web/src/app/agents web/src/app/agents/\[code\] web/src/app/memory
grep -rln '@/components/ui/' web/src/components/agent web/src/components/memory web/src/app/agents web/src/app/memory
```

계획을 쓸 때의 목록은 아래와 같았다. 이보다 많으면 더해진 파일도 같은 규칙으로 옮긴다.

| 파일 | 지금 쓰는 것 |
| --- | --- |
| `web/src/components/agent/persona-confirm.tsx` | `role="dialog"`, `aria-modal="true"`, `aria-labelledby="persona-confirm-title"` 를 손으로 붙인 `<section>`. 바깥은 `fixed inset-0` 덮개 |
| `web/src/components/agent/persona-editor.tsx` | `<textarea>`, 오류 줄 `role="alert"`, `Button` |
| `web/src/components/memory/memory-form.tsx` | `const fieldClass = "mt-1 w-full rounded-md border ..."` 와 `<select name="scope">`, `<input name="title">`, `<textarea name="content">`, `<input type="checkbox" name="alwaysInject">` |
| `web/src/components/memory/memory-item.tsx` | 고치기 폼의 `<textarea name="content">`, `<input name="alwaysInject" type="checkbox">`, 오류 줄 `role="alert"` |
| `web/src/components/memory/memory-list.tsx`, `memory-proposal.tsx` | 항목 목록과 제안 줄. 제안 줄은 `role="alert"` |
| `web/src/app/agents/page.tsx` | 에이전트 목록. 80자를 넘는 className 이 한 곳 있다 |

폼은 모두 **제어하지 않는 폼**이다. `name` 을 붙인 원소를 `FormData` 로 읽는다.

## 의도 메모

- **`<select>` 와 체크박스는 네이티브 원소를 그대로 둔다.** 모양만 공통 부품으로 입힌다.
  기각한 것은 Radix 의 `Select` 와 `Checkbox` 다. 까닭이 둘이다.
  - `FormData` 가 네이티브 원소의 값을 읽는다. Radix 원소는 숨은 입력을 따로 만들어야 값이 실린다
  - 테스트가 `getByLabel("범위").selectOption("USER")` 와 `getByLabel("항상 답에 함께 넣기").check()` 를 쓴다.
    `selectOption` 은 네이티브 `<select>` 에서만 된다
- 네이티브 `<select>` 의 모양은 `web/src/components/ui/native-select.tsx` 한 곳에 둔다.
  shadcn 에 `native-select` 부품이 있으면 그것을 받아 쓰고, 없으면 `input.tsx` 와 같은 테두리와 높이로 직접 둔다.
  이 파일을 phase-02 와 phase-03 도 쓴다
- **접근성 이름을 바꾸지 않는다.** 역할이 바뀌는 곳만 테스트를 고친다. 이 phase 에서 역할이 바뀌는 곳은 성격 저장 확인 창 하나다
- AlertDialog 는 `Esc` 로 닫히면 취소와 같다. 저장하는 중(`busy`)에는 닫히지 않게 `onEscapeKeyDown` 과 `onOpenChange` 에서 막는다

## Blocked 조건

- `web/src/components/ui/alert-dialog.tsx` 가 없다 → `PHASE_BLOCKED: 디자인 기반의 alert-dialog 가 아직 없다`
- `web/src/lib/utils.ts` 에 `cn` 이 없다 → `PHASE_BLOCKED: 디자인 기반의 cn 이 아직 없다`
- `grep -rn 'text-muted\b' web/src` 가 `text-muted-foreground` 가 아닌 줄을 낸다 → `PHASE_BLOCKED: 토큰 이름이 아직 옮겨지지 않았다`

## 작업 항목

### 1. `web/src/components/ui/native-select.tsx` 신규

네이티브 `<select>` 를 감싼다. props 는 `React.ComponentProps<"select">` 를 그대로 받고 `className` 을 `cn` 으로 합친다.
테두리는 `border-input`, 바탕은 `bg-background`, 초점은 `focus-visible:ring-ring` 으로 `input.tsx` 와 맞춘다.

### 2. `web/src/components/agent/persona-confirm.tsx` 를 AlertDialog 로

- `AlertDialog`, `AlertDialogContent`, `AlertDialogHeader`, `AlertDialogTitle`, `AlertDialogDescription`,
  `AlertDialogFooter`, `AlertDialogCancel`, `AlertDialogAction` 으로 다시 짓는다. props 서명 `{ agentName, busy, onCancel, onConfirm }` 은 그대로 둔다
- 제목 글 「{agentName}의 성격을 저장할까요?」, 본문, 단추 이름 「취소」 「저장한다」 를 바꾸지 않는다
- 부르는 쪽이 이 부품을 조건부로 그리면 `open` 을 참으로 넘기고, `onOpenChange(false)` 가 오면 `busy` 가 아닐 때만 `onCancel` 을 부른다
- `role="dialog"`, `aria-modal`, `persona-confirm-title` 같은 손으로 붙인 속성을 지운다

### 3. `web/src/components/agent/persona-editor.tsx`

- `<textarea>` 를 `Textarea` 로, 이름표를 `Label` 로 바꾼다. 접근성 이름 「성격 비서 성격」 처럼 지금 테스트가 찾는 이름을 그대로 둔다
- 80자를 넘는 className 을 `Textarea` 의 기본 모양과 짧은 덧붙임으로 줄인다

### 4. 추천 질문 편집 부품

새 대화 화면을 만든 변경이 `components/agent/` 에 추천 질문 편집 부품을 더했다. 위 목록 명령으로 찾는다.
입력은 `Input` 과 `Label`, 저장 단추는 `Button` 이다. 단추 이름 「소개와 추천 질문 저장」 을 바꾸지 않는다.

### 5. 기억 화면

- `memory-form.tsx` 와 `memory-item.tsx` 의 `fieldClass` 를 지운다. `<input>` 은 `Input`, `<textarea>` 는 `Textarea`,
  `<select name="scope">` 는 `NativeSelect` 로 바꾼다. 체크박스는 네이티브 `<input type="checkbox">` 를 두고 `accent-primary` 만 입힌다
- 이름표를 `Label` 로 바꾸되 `getByLabel` 이 찾는 글 「범위」 「제목」 「내용」 「항상 답에 함께 넣기」 를 바꾸지 않는다
- 범위와 상태 표시는 `Badge` 로, 「받아들이기」 「물리기」 「고치기」 「지우기」 「저장」 은 `Button` 의 variant 로 둔다.
  되돌릴 수 없는 「지우기」 는 `variant="destructive"` 다

### 6. `web/src/app/agents/page.tsx`

80자를 넘는 className 한 곳을 `Card` 나 `Button` 의 variant 로 옮긴다. `card.tsx` 가 없으면 `pnpm dlx shadcn@latest add card` 로 받는다.

### 7. 이 phase 를 검증하는 테스트

`test/browser/persona.spec.ts` 에서 역할만 고친다.

| 줄 | 지금 | 바꿀 것 |
| --- | --- | --- |
| 24, 45 | `page.getByRole("dialog", { name: "성격 비서의 성격을 저장할까요?" })` | `getByRole("alertdialog", { ... })`. 이름은 그대로 |

같은 파일에 한 경우를 더한다. 확인 창이 열린 채 `Esc` 를 누르면 창이 닫히고 본문이 저장되지 않는다.

`test/browser/memory.spec.ts` 는 고치지 않는다. 그대로 통과해야 네이티브 원소를 지킨 것이다.

## 검증

```bash
# cwd: 저장소 root
grep -rn 'role="dialog"\|aria-modal' web/src/components/agent web/src/components/memory
grep -rn 'fieldClass' web/src/components/memory
grep -rn 'style={{' web/src/components/agent web/src/components/memory
scripts/check-public-safe.sh
```

세 `grep` 은 아무것도 내지 않아야 한다. `style={{` 는 까닭을 주석으로 단 예외만 남을 수 있다.

```bash
# cwd: web/
pnpm typecheck
pnpm test:browser persona.spec.ts memory.spec.ts
```

끝나면 `tasks/plan024-design-screens/index.json` 의 이 phase 를 `completed` 로 바꾸고 `current_phase` 를 2로 올린다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `web/src/components/ui/native-select.tsx` | 신규 |
| `web/src/components/ui/card.tsx` | 신규 (없을 때) |
| `web/src/components/agent/persona-confirm.tsx` | 수정 |
| `web/src/components/agent/persona-editor.tsx` | 수정 |
| `web/src/components/agent/` 의 추천 질문 편집 부품 | 수정 |
| `web/src/components/memory/memory-form.tsx` | 수정 |
| `web/src/components/memory/memory-item.tsx` | 수정 |
| `web/src/components/memory/memory-list.tsx` | 수정 |
| `web/src/components/memory/memory-proposal.tsx` | 수정 |
| `web/src/app/agents/page.tsx` | 수정 |
| `test/browser/persona.spec.ts` | 수정 |
