# Phase 02. 의미 색 배지와 알림 상자 부품

**Execution profile**: deep

## 목표

상태를 색과 아이콘으로 구분하는 `Badge` 변형과 `Notice` 부품을 만들고, 화면마다 손으로 복사한 알림 상자를 `Notice` 로 옮긴다.
다른 화면 작업이 같은 부품을 가져다 쓰게 하려는 것이다.

**범위 외**: 스위치, 비활성 단추, 모서리, `NativeSelect` 는 phase 03. 대화의 작업 과정 블록과 문구는 phase 04. 사용량 화면의 역할별 표시는 phase 05. 움직임은 phase 06.

## 컨텍스트

- 부품은 `web/src/components/ui/` 에 있고 변형은 `class-variance-authority` 의 `cva` 로 모은다. `badge.tsx`, `button.tsx` 가 그 패턴이다.
- phase 01 이 `success`, `warning`, `info`, `destructive` 와 각 `-soft`, `primary-soft-foreground`, `foreground-soft`, `radius-*` 토큰을 `globals.css` 에 두었다.
- 폼 원소는 네이티브로 둔다(ADR-023 의 「감당할 것」). 폼이 `FormData` 로 값을 읽고 브라우저 검사가 `selectOption` 과 `check` 를 쓴다. Radix `Switch`, `Select` 로 바꾸지 않는다.
- 화면 문구는 `web/AGENTS.md` 의 「화면 문구」 절을 따른다. 해요체다.

**근거 문서**: `docs/adr/ADR-047-화면-색은-새벽-보라로-바꾸고-강조-색은-누를-것과-고른-것과-초점에만-쓴다.md` 의 「강조 색을 쓰는 곳」, 「의미 색」, 「모서리」 절, `docs/code-architecture.md` 의 「우리 화면의 정체성」 절, `docs/adr/ADR-023-화면-부품은-shadcn-ui-를-저장소에-복사해-쓴다.md`

## 의도 메모

- 알림 상자를 Radix 나 shadcn 의 `Alert` 로 받지 않고 직접 만든다. 필요한 것은 색 넷과 아이콘 하나이고 동작이 없다.
- 변형은 아래 규칙으로 기계적으로 고른다. 줄마다 뜻을 새로 판단하지 않는다.

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

`rounded-md bg-muted p-3 text-sm` 이나 `rounded-md bg-muted px-3 py-2 text-sm` 로 손으로 그린 안내와 오류 상자를 `Notice` 로 옮긴다. 후보는 아래 명령이 낸다.

```bash
# cwd: 저장소 root
grep -rnE 'rounded-md (border border-border )?bg-muted (p|px)-' web/src
```

변형은 아래 순서로 고른다. 위에서 맞으면 아래를 보지 않는다.

| 순서 | 조건 | 변형 |
| --- | --- | --- |
| 1 | 옮기지 않는다: `web/src/components/chat/markdown.tsx` 의 `<pre>`, `web/src/components/chat-panel.tsx` 의 「새 대화」 `Link`, `<li>` 나 `<ul>` 인 목록 줄, `web/src/components/memory/memory-proposal.tsx` 22줄의 `<article>` 카드, `web/src/components/connector/connector-connection-panel.tsx` 242줄 근처의 상태 배지를 담은 줄 | 없음 |
| 2 | `web/src/components/agent/agent-tools-section.tsx` 의 「표에 없는 도구가 켜져 있어요」 | `warning` |
| 3 | `role="alert"` 가 있거나, 그리는 값의 이름이 `error`, `...Error`, `failure`, `message`(실패 응답의 문구) 다 | `error` |
| 4 | 그 밖 | `info` |

`neutral` 은 새 화면이 뜻 없는 회색 상자를 그릴 때를 위한 기본값이다. 이 phase 에서 옮기는 곳에는 쓰지 않는다.

`text-sm text-destructive` 글자만으로 오류를 그리는 곳(`execution-detail.tsx` 의 「실행 정보를 불러오지 못했어요」, `model-picker.tsx` 의 `model-picker-error`, `agent-tools-section.tsx` 의 「이 도구를 켜지 못했어요」)은 줄 안의 짧은 글이라 그대로 둔다.
`web/src/components/chat/message-list.tsx` 의 `turn-error` 는 `<li>` 안에 `Notice variant="error"` 를 둔다(지금은 grep 에 걸리는 `<li>` 지만 알림이므로 옮긴다). `data-testid="turn-error"` 는 `<li>` 에 남긴다. 「다시 시도」 는 `Button variant="link" size="xs"` 로 바꾼다. `data-testid="turn-error-retry"` 를 지킨다.
같은 파일의 `no-answer` 줄의 「다시 시도」 도 같은 단추로 바꾼다.

### 3. 검사

`test/browser/design-tokens.spec.ts` 에 더한다.

- 사용량 실행 기록(`/usage?tab=executions`)에서 성공 배지의 글자색이 `--success`, 바탕이 `--success-soft` 다. `test/browser/usage.spec.ts` 가 실행을 만드는 방법(대화를 하나 보내고 끝나길 기다린다)을 따른다.
- `[data-slot="notice"][data-variant="error"]` 의 바탕이 `--destructive-soft` 이고 글자색이 `--destructive` 다. `turn-error` 는 `test/browser/shell.spec.ts` 196~211줄의 방법(스트림 응답을 가로채 실패 사건을 넣는다)으로 만든다. 붐빔 거절은 `turn-error` 를 만들지 않는다.
- 같은 검사에서 그 상자 안에 `svg` 아이콘이 하나 있다.

## 검증

```bash
# cwd: 저장소 root
node --test test/unit/design-tokens.test.ts
! grep -rnE 'rounded-md bg-muted (p-3|px-3 py-2) text-sm' web/src/components/agent web/src/components/connector web/src/components/admin web/src/app
cd web && pnpm typecheck && pnpm lint
cd web && pnpm test:browser test/browser/design-tokens.spec.ts test/browser/usage.spec.ts test/browser/chat.spec.ts test/browser/shell.spec.ts test/browser/memory.spec.ts test/browser/connector-connection.spec.ts test/browser/persona.spec.ts test/browser/agent-tools.spec.ts test/browser/skills.spec.ts test/browser/admin.spec.ts test/browser/people.spec.ts test/browser/execution-tree.spec.ts
```

기대값: 모두 종료 코드 0.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/components/ui/notice.tsx` | 신규 |
| `web/src/components/ui/badge.tsx` | 수정 |
| `web/src/components/chat-panel.tsx` | 수정 |
| `web/src/components/chat/*.tsx` | 수정 |
| `web/src/components/agent/*.tsx` | 수정 |
| `web/src/components/admin/*.tsx` | 수정 |
| `web/src/components/connector/*.tsx` | 수정 |
| `web/src/components/memory/*.tsx` | 수정 |
| `web/src/components/usage/*.tsx` | 수정 |
| `web/src/components/execution/execution-detail.tsx` | 수정 |
| `web/src/app/admin/people/people-admin-panel.tsx` | 수정 |
| `web/src/app/agents/[code]/page.tsx` | 수정 |
| `web/src/app/agents/[code]/skills/[name]/page.tsx` | 수정 |
| `test/browser/design-tokens.spec.ts` | 수정 |
