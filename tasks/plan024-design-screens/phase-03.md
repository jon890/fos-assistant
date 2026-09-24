# Phase 03. 관리 화면과 로그인을 옮기고 쓰지 않게 된 옛 부품을 지운다

**Execution profile**: standard

## 목표

에이전트 관리, 사람 관리, 로그인 화면이 shadcn/ui 부품을 쓰게 하고, 손으로 만든 확인 창 `visibility-confirm.tsx` 를 AlertDialog 로 바꾼다.
그다음 `web/src/components/` 전체에서 긴 className 을 부품과 variant 로 모으고, 쓰이지 않게 된 옛 부품을 지우고, 문서를 마무리한다.
이 plan 의 마지막 phase 다.

**범위 외**: 에이전트와 기억 화면(phase-01), 사용량과 실행 나무(phase-02).

## 컨텍스트

**근거 문서**: `docs/adr/ADR-023-화면-부품은-shadcn-ui-를-저장소에-복사해-쓴다.md`,
`docs/code-architecture.md` 「디렉터리」 「아직 만들지 않은 것」, `web/AGENTS.md` 「색과 간격은 테마 토큰이 소유한다」.

phase-01 이 `components/ui/native-select.tsx` 와 `card.tsx` 를, phase-02 가 `table.tsx` 를 두었다.

**시작할 때 옮길 파일을 다시 모은다.**

```bash
# cwd: 저장소 root
ls web/src/components/admin web/src/app/admin/agents web/src/app/admin/people web/src/app/signin web/src/components/ui
grep -rnoE '<(select|input|textarea|table)\b|role="dialog"|aria-modal' web/src/components/admin web/src/app/admin web/src/app/signin
```

계획을 쓸 때의 모양이다.

| 파일 | 지금 쓰는 것 |
| --- | --- |
| `components/admin/visibility-confirm.tsx` | `role="dialog"`, `aria-modal="true"`, `aria-labelledby="visibility-confirm-title"` 를 손으로 붙인 `<section>`. 제목 「{agent.name} 에이전트를 가족에게 공개할까요?」, 단추 「취소」 「가족 공개」 |
| `components/admin/agent-form.tsx` | `const fieldClass = "mt-1 w-full rounded-md border ..."`, `<input name="code">` 등 여섯, `<select name="costMode">`, `<select name="credentialScope">`, `<select name="visibility">` |
| `components/admin/person-form.tsx` | 같은 `fieldClass`, `<input name="email" type="email">`, `<input name="displayName">`, Hermes profile 입력 |
| `components/admin/person-list.tsx` | `<table aria-label="더해진 사람">` |
| `components/admin/agent-card.tsx` | Hermes API 주소 `<input>` 과 「주소 저장」, 오류 줄 `role="alert"`. 80자를 넘는 className 한 곳 |
| `components/admin/agent-model-list.tsx` | 줄마다 `aria-label={\`${index + 1}순위 provider\`}` 와 `aria-label={\`${index + 1}순위 모델\`}` 인 `<input>` 둘. 80자를 넘는 className 한 곳 |
| `components/admin/agent-list.tsx` | 에이전트 카드 목록 |
| `app/admin/people/people-admin-panel.tsx` | 오류 줄 `role="alert"` |
| `app/signin/page.tsx` | 서버 액션 폼 안의 `<Button type="submit" variant="primary">`. 80자를 넘는 className 두 곳 |

관리 폼도 **제어하지 않는 폼**이다. `name` 을 붙인 원소를 `FormData` 로 읽는다.

## 의도 메모

- **`<select>` 는 네이티브 원소를 둔다.** phase-01 의 `NativeSelect` 로 모양만 입힌다. `FormData` 가 값을 읽기 때문이다.
  Radix 의 `Select`, `Checkbox`, `Switch` 는 쓰지 않는다. 지금 관리 화면에 켜고 끄는 입력이 있으면 그것도 네이티브 체크박스로 둔다
- **접근성 이름을 바꾸지 않는다.** `aria-label` 「1순위 provider」, 표 이름 「더해진 사람」, 단추 이름 「가족 공개로 변경」 「모델 추가」 「모델 목록 저장」 「주소 저장」 「더하기」 「{이름} 사용 중지」 「{이름} 다시 허용」 을 그대로 둔다
- 역할이 바뀌는 곳은 공개 확인 창 하나다. `admin.spec.ts` 만 고친다
- 로그인 화면의 단추는 서버 액션 폼의 제출 단추다. `Button` 을 `asChild` 없이 `type="submit"` 으로 둔다
- **긴 className 을 0 으로 만드는 것이 목표지만 억지로 쪼개지 않는다.** 한 번만 쓰는 배치(그리드 열 정의처럼)는 남겨도 된다.
  남긴 곳마다 그 줄 위에 한 줄 주석으로 까닭을 적는다. 두 번 이상 나오는 조합은 부품이나 variant 로 모은다

## Blocked 조건

- `web/src/components/ui/native-select.tsx` 나 `table.tsx` 가 없다 → `PHASE_BLOCKED: 앞 phase 가 끝나지 않았다`

## 작업 항목

### 1. `components/admin/visibility-confirm.tsx` 를 AlertDialog 로

phase-01 의 `persona-confirm.tsx` 와 같은 방식이다. props 서명 `{ agent, busy, onCancel, onConfirm }` 과 제목, 본문, 단추 이름을 바꾸지 않는다.
`busy` 인 동안 `Esc` 와 바깥 클릭으로 닫히지 않게 막는다. 손으로 붙인 `role`, `aria-modal`, `aria-labelledby` 를 지운다.

### 2. 관리 폼

- `agent-form.tsx` 와 `person-form.tsx` 의 `fieldClass` 를 지운다. `<input>` 은 `Input`, `<select>` 는 `NativeSelect`, 이름표는 `Label` 이다
- `agent-card.tsx` 와 `agent-model-list.tsx` 의 입력을 `Input` 으로 바꾸고 `aria-label` 을 그대로 넘긴다
- 「지우기」 처럼 되돌릴 수 없는 단추는 `variant="destructive"`, 「아래로」 같은 작은 조작은 `size="icon"` 과 `lucide-react` 아이콘에 지금 접근성 이름을 `aria-label` 로 둔다
- `person-list.tsx` 의 표를 `Table` 로 바꾼다. `aria-label="더해진 사람"` 을 `Table` 에 넘긴다. 켜짐과 꺼짐 표시는 `Badge` 다

### 3. 로그인 화면

`app/signin/page.tsx` 의 두 긴 className 을 `Card` 와 `Button` 의 variant 로 옮긴다. 「Google 계정으로 로그인」 글은 그대로다.

### 4. 긴 className 을 모은다

전체를 다시 센다.

```bash
# cwd: 저장소 root
grep -rnE 'className="[^"]{80,}"' web/src
grep -rnE 'className=\{`[^`]{80,}`\}' web/src
```

계획을 쓸 때 26곳이었다. 대화 화면과 화면 틀의 몫은 앞선 디자인 기반 변경이 이미 줄였을 것이다. 남은 곳을 위 의도 메모의 규칙으로 처리한다.

### 5. 옛 부품을 지운다

`components/ui/` 에서 shadcn 에서 받지 않았고 어디서도 쓰지 않는 파일을 찾아 지운다.

```bash
# cwd: 저장소 root
for f in web/src/components/ui/*.tsx; do n=$(basename "$f" .tsx); c=$(grep -rln "components/ui/$n\"" web/src | wc -l); echo "$c $n"; done | sort -n
```

0 인 파일이 지울 후보다. `empty-state.tsx`, `stat.tsx`, `theme-toggle.tsx` 처럼 우리가 만든 부품이 아직 쓰이면 남긴다.
그 부품의 색 클래스가 ADR-023 의 토큰 이름인지 본다.

### 6. 문서를 마무리한다

- `docs/code-architecture.md` 「디렉터리」 에서 「옮기기 전까지는 지금 부품을 쓴다.」 문장을 지운다. 그 뒤 문장 「옮긴 뒤에는 대화상자, 메뉴, 서랍, 알림 풍선을 직접 만들지 않는다.」 는 「대화상자, 메뉴, 서랍, 알림 풍선을 직접 만들지 않는다.」 로 고친다
- 같은 문서 「아직 만들지 않은 것」 에서 「화면 부품을 shadcn/ui 로 옮기는 일」 로 시작하는 항목을 지운다.
  앞선 디자인 기반 변경이 그 항목을 이미 줄였으면 남은 부분까지 지운다
- `docs/code-architecture.md` 「디렉터리」 의 트리에서 `ui/` 설명이 「shadcn/ui 에서 받은 부품과 우리가 만든 조각」 을 가리키게 고친다

### 7. 이 phase 를 검증하는 테스트

`test/browser/admin.spec.ts` 에서 역할만 고친다.

| 줄 | 지금 | 바꿀 것 |
| --- | --- | --- |
| 22 | `page.getByRole("dialog", { name: "브라우저 비서 에이전트를 가족에게 공개할까요?" })` | `getByRole("alertdialog", { ... })`. 이름은 그대로 |
| 30 | `page.getByRole("dialog").getByRole("button", { name: "가족 공개" })` | `getByRole("alertdialog")...` |

같은 파일에 한 경우를 더한다. 공개 확인 창이 열린 채 `Esc` 를 누르면 창이 닫히고 공개 범위가 「나만」 으로 남는다.

`people.spec.ts` 는 고치지 않는다. 그대로 통과해야 표와 이름표를 지킨 것이다.

## 검증

```bash
# cwd: 저장소 root
grep -rn 'role="dialog"\|aria-modal' web/src
grep -rn 'fieldClass' web/src
grep -rn 'style={{' web/src
grep -rnE 'className="[^"]{80,}"' web/src
grep -n '옮기기 전까지는 지금 부품을 쓴다' docs/code-architecture.md
```

첫째, 둘째, 다섯째는 아무것도 내지 않아야 한다.
`style={{` 와 긴 className 은 까닭을 주석으로 단 줄만 남을 수 있다. 남은 줄마다 바로 위에 그 주석이 있는지 본다.

AGENTS.md 의 「확인」 절 명령을 적힌 순서대로 모두 돌린다. 이 plan 의 마지막 phase 다.
`pnpm build` 는 `web/AGENTS.md` 의 자리표시자 환경 변수가 필요하다.

끝나면 `tasks/plan024-design-screens/index.json` 의 이 phase 를 `completed` 로, plan 의 `status` 를 `completed` 로 바꾼다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `web/src/components/admin/visibility-confirm.tsx` | 수정 |
| `web/src/components/admin/agent-form.tsx` | 수정 |
| `web/src/components/admin/person-form.tsx` | 수정 |
| `web/src/components/admin/person-list.tsx` | 수정 |
| `web/src/components/admin/agent-card.tsx` | 수정 |
| `web/src/components/admin/agent-model-list.tsx` | 수정 |
| `web/src/components/admin/agent-list.tsx` | 수정 |
| `web/src/app/admin/people/people-admin-panel.tsx` | 수정 |
| `web/src/app/signin/page.tsx` | 수정 |
| `web/src/components/ui/` 의 쓰이지 않는 옛 부품 | 삭제 |
| `web/src/components/` 의 긴 className 이 남은 파일 | 수정 |
| `docs/code-architecture.md` | 수정 |
| `test/browser/admin.spec.ts` | 수정 |
