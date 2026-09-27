# Phase 01. 에이전트와 성격과 기억 화면을 shadcn/ui 부품으로 옮긴다

**Execution profile**: standard

## 목표

에이전트 목록, 성격 편집, 추천 질문 편집, 기억 화면이 `components/ui/` 의 shadcn/ui 부품을 쓰게 한다.
손으로 만든 확인 창 `persona-confirm.tsx` 를 AlertDialog 로 바꾼다.

**범위 외**: 사용량과 실행 나무(phase-02), 관리 화면과 로그인과 옛 부품 삭제(phase-03).
화면 틀과 대화 화면은 앞선 디자인 기반 변경이 이미 옮겼다.

## 컨텍스트

**요청을 보내는 단추는 `Button` 의 `loading` 을 쓴다.** `disabled={busy}` 만으로 두지 않는다.
이 phase 가 옮기는 화면에서 `disabled={busy` 처럼 보내는 중에 잠그는 단추를 모두 찾아 `loading={busy}` 와 지금 하는 일을 적은 `loadingText`(「저장 중」, 「지우는 중」 처럼)를 준다.
근거는 `docs/flow.md` 「기다리는 동안 보이는 것」 이다. 브라우저 검사가 단추 이름으로 찾는 곳은 `loadingText` 때문에 보내는 중에 이름이 바뀐다. 누른 뒤의 기대를 이름이 아니라 결과로 보게 고친다.

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

## 구현 전 검토에서 정한 것

계획을 쓴 뒤 코드가 바뀌어 아래 작업 항목과 어긋나는 곳이 있다. **이 절이 아래 작업 항목보다 우선한다.**

- **확인 창의 확인 단추는 `AlertDialogAction` 을 쓰지 않는다.** `AlertDialogAction` 은 `Button asChild` 라 `loading` 이 동작하지 않고, 누르면 Radix 가 곧바로 창을 닫는다.
  `web/src/components/shell/conversation-nav.tsx` 의 대화 지우기 창과 같이 둔다. 「취소」 는 `<AlertDialogCancel asChild><Button variant="outline" disabled={busy}>`, 「저장한다」 는 `loading={busy}` 와 `loadingText="저장 중"` 을 준 일반 `Button` 이다.
  `onOpenChange` 에서 닫힘이 오면 `busy` 가 아닐 때만 `onCancel` 을 부르고, `AlertDialogContent` 의 `onEscapeKeyDown` 에서 `busy` 이면 `preventDefault` 한다.
  `persona-editor.tsx` 의 `save()` 는 확인 창을 먼저 닫으므로 이 화면에서 `busy` 중에 창이 열려 있는 일은 없다. 그래도 부품은 `busy` 를 받는 서명이므로 위 두 줄을 둔다
- **`cn` 은 `import { cn } from "cn"` 으로 가져온다.** `components/ui/` 의 다른 부품과 같다
- **`native-select.tsx` 의 클래스는 `input.tsx` 의 실제 클래스를 옮겨 쓴다.** 바탕은 `bg-transparent`, 초점은 `focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50` 이다. 계획 본문의 `bg-background` 는 틀렸다.
  props 는 `value` 와 `onChange` 를 포함해 모두 그대로 넘긴다. `memory-form.tsx` 의 범위 `<select>` 는 `value` 와 `onChange` 로 제어된다
- **`card.tsx` 는 이 phase 에서 반드시 받는다.** `pnpm dlx shadcn@latest add card` 다. 뒤 phase 가 쓴다. `agents/page.tsx` 에서 Card 를 쓰든 Button variant 를 쓰든 받아 둔다
- **이름표 원소가 없고 `aria-label` 만 있는 입력은 `aria-label` 을 그대로 둔다.** `persona-editor.tsx` 와 `starter-editor.tsx` 가 그렇다. 보이는 `Label` 을 새로 더하지 않는다. 보이는 모양을 새로 정하는 일이라 이 phase 밖이다
- **`<label>` 이 입력을 감싸던 곳은 `htmlFor` 와 `useId` 로 바꾼다.** `Label` 의 기본 클래스가 `flex items-center gap-2` 라 감싼 채 두면 이름과 입력이 옆으로 놓인다.
  이름표와 입력을 `grid gap-1.5` 인 `<div>` 로 묶는다. `memory-item.tsx` 는 항목마다 그려지므로 `useId` 로 id 가 겹치지 않게 한다. 체크박스는 `Label` 이 감싼 채 옆으로 놓여도 된다
- **`rows` 를 준 `Textarea` 에는 `field-sizing-fixed` 를 덧붙인다.** `Textarea` 의 기본 `field-sizing-content` 가 `rows` 를 무시해 16줄 편집창이 작게 시작한다
- **`memory-item.tsx` 에는 `fieldClass` 가 없다.** 인라인 긴 문자열을 `Input` 과 `Textarea` 로 바꾼다.
  Badge 로 바꾸는 것은 지금 상태를 보이는 원소뿐이다. 「항상 답에 함께 넣음」 표시와 `data-testid="memory-omitted"` 가 있으면 그것이다. 새 표시를 더하지 않는다
- **여러 단추가 `busy` 하나를 함께 쓰면 누른 단추에만 `loading` 을 준다.** `memory-proposal.tsx` 의 「받아들이기」 「물리기」 가 그렇다.
  어느 단추를 눌렀는지 기억하는 상태(예: `pending: "accept" | "reject" | null`)를 두고, 누르지 않은 단추는 `disabled` 만 준다. `docs/flow.md` 「기다리는 동안 보이는 것」 이 「그 단추 안에」 라고 적었다.
  요청을 보내지 않는 단추에는 `loading` 을 주지 않는다
- **Blocked 조건 셋째 줄의 grep 은 `grep -rnP '(text|placeholder|decoration)-muted(?![\w-])' web/src` 로 본다.** 이것이 무엇이든 내면 막힌다. 원래 식은 `text-muted-foreground` 에도 맞는다
- **`loading` 을 확인하는 브라우저 검사를 `persona.spec.ts` 에 하나 더한다.** 성격 편집의 「저장」 단추는 지금 `{busy ? "저장 중…" : "저장"}` 으로 글자를 손으로 바꾼다. 이것을 `loading={busy}` 와 `loadingText="저장 중"` 으로 옮긴다.
  검사는 `page.route` 로 `PUT /api/agents/*/persona` 를 붙잡아 둔 채 확인 창에서 「저장한다」 를 누르고, 편집 화면의 단추가 `aria-busy="true"` 이고 「저장 중」 을 보이는지 본다. 요청을 놓아 준 뒤 「저장되었습니다.」 가 보이는지도 본다.
  `test/browser/shell.spec.ts` 의 지우기 창 검사가 요청을 붙잡는 방식의 선례다. `memory.spec.ts` 는 고치지 않는다

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
