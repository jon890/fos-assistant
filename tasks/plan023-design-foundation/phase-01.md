# Phase 01. 색 토큰 이름을 shadcn 체계로 옮긴다

**Execution profile**: deep

## 목표

`globals.css` 의 색 토큰 이름을 ADR-023 의 표대로 shadcn 이름으로 바꾸고, `web/src` 와 `test/browser` 가 쓰는 이름을 모두 따라 바꾼다.
**화면은 한 픽셀도 달라지지 않아야 한다.** 값은 그대로이고 이름만 바뀐다.
이 phase 가 끝나야 다음 phase 에서 shadcn 이 복사해 주는 부품을 고치지 않고 쓸 수 있다.

**범위 외**: shadcn 설치와 부품 교체(phase-02), 화면 틀과 대화 화면의 부품 교체(phase-03), 나머지 화면(뒤 계획).

## 컨텍스트

**근거 문서**: `docs/adr/ADR-023-화면-부품은-shadcn-ui-를-저장소에-복사해-쓴다.md` 의 「토큰 이름」 표,
`docs/code-architecture.md` 의 「색과 간격은 테마 토큰이 소유한다」 와 「우리 화면의 정체성」, `web/AGENTS.md` 의 「색과 간격은 테마 토큰이 소유한다」.

### 먼저 지금 모양을 읽는다

이 plan 은 대화 화면 개선(중지, 다시 생성, 새 대화 화면)이 모두 main 에 들어간 뒤에 돈다.
그 변경이 `web/src/components/chat/` 에 파일을 더했다. 아래 명령으로 지금 목록과 옛 토큰을 쓰는 곳을 먼저 만든다.
파일 이름을 기억으로 적지 않는다.

```bash
# cwd: 저장소 root
find web/src -name '*.tsx' -o -name '*.ts' -o -name '*.css' | sort
grep -rnoE '\b[a-z:-]*(bg|text|border|outline|ring|fill|stroke|divide|placeholder|decoration|shadow|from|to|via|caret|accent)-(muted|surface-raised|surface|brand-strong|brand-soft|on-brand|brand|danger)\b' web/src | sort | uniq -c | sort -rn
grep -rn -- '--brand\|--on-brand\|--muted\|--surface\|--danger' web/src test/browser
```

계획을 세울 때(main `a5d48f9`) 센 것이다. 달라져 있으면 지금 센 것을 따른다.

| 옛 클래스 | 곳 |
| --- | --- |
| `text-muted` | 86 |
| `bg-surface` | 35 |
| `bg-surface-raised` | 12 |
| `brand` 계열 전체 | 약 20 |
| `text-danger`, `bg-danger` | 6 |

### 지금 있는 결함 하나

`text-danger` 와 `bg-danger` 를 `shell/conversation-nav.tsx`, `memory/memory-item.tsx`, `usage/execution-table.tsx`, `usage/execution-card.tsx` 가 쓴다.
**그런데 `globals.css` 에 `danger` 토큰이 없다.** 그래서 오류 글자가 본문 색으로, 대화 지우기 단추가 바탕 없이 그려진다.
이 phase 에서 둘을 `destructive` 로 옮긴다. 이것만은 화면이 달라지는 것이 맞다. 아래 「검증」 의 픽셀 비교에서 이 차이만 허용한다.
`conversation-nav.tsx` 의 지우기 단추는 `text-white` 를 쓴다. `text-destructive-foreground` 로 바꾼다.

### 테스트가 옛 이름을 읽는다

| 파일 | 읽는 것 |
| --- | --- |
| `test/browser/identity.spec.ts` | `--brand`, `--on-brand` 를 두 밝기 모드에서 읽어 대비를 잰다. 보내기 단추 바탕이 `--brand` 와 같은지 본다 |
| `test/browser/chat.spec.ts` | 보내기 단추 바탕과 `--brand` 가 `#b05a3c` 인지 본다 |
| `test/browser/nav.spec.ts` | 고른 메뉴의 바탕과 `--brand` 가 `#b05a3c` 인지 본다 |
| `test/browser/theme.spec.ts` | 어두움 모드의 `body` 바탕이 `rgb(16, 18, 22)` 인지 본다. 이름을 읽지 않아 그대로 통과해야 한다 |

## 의도 메모

- **`muted` 는 뜻이 뒤바뀐다.** 지금 `muted` 는 흐린 글자이고 옮긴 뒤 `muted` 는 흐린 바탕이다.
  반드시 `text-muted` 를 `text-muted-foreground` 로 먼저 모두 바꾸고, 그다음 `surface` 를 `muted` 로 바꾼다.
  거꾸로 하면 흐린 바탕과 흐린 글자가 한 이름으로 섞여 되돌릴 수 없다.
- 한 번에 정규식 하나로 바꾸지 않는다. 아래 차례대로 한 단계씩 바꾸고 단계마다 `git diff --stat` 을 본다.
- 값은 옮기지 않는다. shadcn 은 OKLCH 를 쓰지만 지금 16진수 값을 그대로 둔다. 인상이 그대로여야 한다.
- `brand-strong` 과 `brand-soft` 는 shadcn 에 없는 우리 토큰이다. `primary-strong`, `primary-soft` 로 이름만 맞춘다.
- 기각: 옛 이름을 새 이름의 별칭으로 함께 남기기. 두 이름이 한동안 섞이면 무엇을 써야 하는지 다시 흔들린다.

## Blocked 조건

- `docs/code-architecture.md` 「아직 만들지 않은 것」 에 「대화 화면 개선」 줄이 남아 있다
  → `PHASE_BLOCKED: 대화 화면 개선이 아직 main 에 다 들어오지 않았다`

## 작업 항목

### 1. 바꾸기 전 화면을 찍어 둔다

이름을 바꾸기 **전에** 임시 검사 파일 `test/browser/zz-visual-baseline.spec.ts` 를 만든다. **커밋하지 않는다.**

- 두 폭(`mobile`, `desktop`)과 두 밝기 모드(`localStorage` 의 `theme` 을 `light`, `dark`)로 아래 화면을 찍어 `toHaveScreenshot` 에 넘긴다
  - `/`, 메시지가 있는 `/c/{id}`, `/agents`, `/memory`, `/usage`, `/admin/agents`
  - 대화를 만드는 방법과 관리자 로그인은 `test/browser/fixtures.ts` 가 가진 것을 쓴다
- 시각이나 번호처럼 매번 바뀌는 글자는 `mask` 로 가린다. 스크롤과 애니메이션은 `animations: "disabled"` 로 멈춘다
- `pnpm test:browser zz-visual-baseline.spec.ts --update-snapshots` 로 기준 그림을 만든다

### 2. `web/src/app/globals.css` 를 새 이름으로 바꾼다

`@theme inline` 의 `--color-*` 와 `:root`, `.dark` 의 변수를 ADR-023 의 표대로 바꾼다.

| `:root` 와 `.dark` 의 변수 | 값 |
| --- | --- |
| `--background`, `--foreground` | 그대로 |
| `--muted-foreground` | 지금 `--muted` 의 값 |
| `--muted` | 지금 `--surface` 의 값 |
| `--accent` | 지금 `--surface-raised` 의 값 |
| `--accent-foreground`, `--secondary-foreground`, `--card-foreground`, `--popover-foreground` | `--foreground` 와 같은 값 |
| `--secondary` | `--accent` 와 같은 값 |
| `--card`, `--popover` | `--background` 와 같은 값 |
| `--border`, `--input` | 지금 `--border` 의 값 |
| `--primary`, `--ring` | 지금 `--brand` 의 값 |
| `--primary-foreground` | 지금 `--on-brand` 의 값 |
| `--primary-strong` | 지금 `--brand-strong` 의 값 |
| `--primary-soft` | 지금 `--brand-soft` 의 값 |
| `--destructive`, `--destructive-foreground` | 새로 정한다. 아래 조건 |
| `--code-*` | 그대로 |

`--destructive` 는 붉은 값이다. 두 밝기 모드 각각에서 `--destructive-foreground` 와의 대비가 4.5 이상이고,
`--destructive` 글자와 `--background` 의 대비도 4.5 이상이어야 한다. 오류 글자(`text-destructive`)로도 쓰기 때문이다.
두 대비를 `identity.spec.ts` 의 `contrast` 함수로 검사에 넣는다.

값이 같은 변수는 `var()` 로 가리키지 않고 값을 그대로 적는다. 어느 것이 어느 것을 가리키는지 따라가지 않아도 되게 한다.

맨 아래 두 줄 주석(모서리와 control 간격)은 그대로 둔다. `--radius-*` 와 `--spacing-control-*` 도 그대로다.

### 3. `web/src` 의 클래스를 차례대로 바꾼다

접두어는 `bg`, `text`, `border`, `outline`, `ring`, `fill`, `stroke`, `divide`, `placeholder`, `decoration`, `shadow`, `from`, `to`, `via`, `caret`, `accent` 모두다.
`hover:`, `focus:`, `focus-visible:`, `active:`, `disabled:`, `group-*:`, `md:`, `dark:` 같은 앞붙이와 `/35` 같은 투명도 뒷붙이는 그대로 둔다.

| 차례 | 옛 이름 | 새 이름 |
| --- | --- | --- |
| 1 | `muted` | `muted-foreground` |
| 2 | `surface-raised` | `accent` |
| 3 | `surface` | `muted` |
| 4 | `brand-strong` | `primary-strong` |
| 5 | `brand-soft` | `primary-soft` |
| 6 | `on-brand` | `primary-foreground` |
| 7 | `brand` | `primary` |
| 8 | `danger` | `destructive` |

1번은 `muted-foreground` 를 다시 건드리지 않게 뒤에 `-` 가 오지 않는 것만 바꾼다.
3번을 하기 전에 1번이 끝났는지 `grep -rnE '\b(text|placeholder|decoration)-muted\b' web/src` 가 비었는지로 본다.
클래스 문자열이 템플릿 문자열이나 `Record` 값 안에 있어도 바꾼다(`ui/button.tsx` 의 `variantClasses` 가 그렇다).

`conversation-nav.tsx` 의 지우기 단추 `text-white` 를 `text-destructive-foreground` 로 바꾼다.
`web/src` 에서 `text-white`, `bg-white`, `#` 로 시작하는 색 값을 `grep` 해 다른 것이 있으면 같은 방법으로 토큰으로 바꾼다.

### 4. 테스트와 문서를 새 이름으로 바꾼다

- `identity.spec.ts`, `chat.spec.ts`, `nav.spec.ts` 가 읽는 `--brand` 를 `--primary` 로, `--on-brand` 를 `--primary-foreground` 로 바꾼다.
  변수 이름(`brand`, `onBrand`)도 새 이름에 맞춘다. 기대값 `#b05a3c` 는 그대로다
- `docs/code-architecture.md` 「색과 간격은 테마 토큰이 소유한다」
  - "옮기기 전까지는 지금 이름을 쓴다" 로 시작하는 두 줄과 "옮기는 변경이 … 함께 고친다" 줄을 지운다
  - 대신 한 줄을 둔다: 토큰 이름은 shadcn 체계이고 대응표와 근거는 ADR-023 이 갖는다
  - 본문 예시 `var(--surface)` 를 `var(--muted)`, `bg-surface` 를 `bg-muted` 로 바꾼다
- 같은 문서 「우리 화면의 정체성」 의 토큰 표에서 `brand` 를 `primary`, `brand-strong` 을 `primary-strong`, `brand-soft` 를 `primary-soft`, `on-brand` 를 `primary-foreground` 로 바꾼다.
  `destructive` 한 줄을 더한다: 되돌릴 수 없는 동작의 단추와 오류 글자
- `web/AGENTS.md` 「색과 간격은 테마 토큰이 소유한다」 의 `var(--surface)` 와 `bg-surface` 예시를 같은 방식으로 바꾼다

### 5. 오래 남길 색 검사를 더한다

`test/browser/design-tokens.spec.ts` 를 새로 만든다. 두 폭에서 돈다.

| 확인 | 기대 |
| --- | --- |
| 두 밝기 모드에서 `:root` 의 새 변수 전체가 비어 있지 않다 | 표 2의 변수 이름 전부 |
| 두 밝기 모드에서 옛 변수 `--brand`, `--on-brand`, `--surface`, `--surface-raised` 가 비어 있다 | 옛 이름이 남지 않았다 |
| 밝음에서 사이드바 바탕이 `--muted`, 흐린 글자 하나가 `--muted-foreground` 의 색이다 | 뜻이 뒤바뀌지 않았다 |
| 두 밝기 모드에서 `--destructive` 와 `--destructive-foreground`, `--destructive` 와 `--background` 의 대비 | 4.5 이상 |

### 6. 이 phase 를 검증하는 테스트

1번에서 만든 `zz-visual-baseline.spec.ts` 를 기준 그림을 바꾸지 않고 다시 돌린다.

```bash
# cwd: web/
pnpm test:browser zz-visual-baseline.spec.ts
```

- 다른 그림이 `danger` 를 쓰던 곳 말고는 없어야 한다. 대화 지우기 확인 창, 기억 목록의 오류 줄, 사용량의 문맥 빠짐 표시가 그 곳이다.
  그 셋이 다른 것은 의도한 변화다. 차이 그림을 보고 붉은 글자와 붉은 단추가 된 것인지 확인한다
- 그 밖에 한 픽셀이라도 다르면 이름 대응을 잘못한 것이다. 차이 그림에서 어느 요소인지 찾아 고친다
- 끝나면 `zz-visual-baseline.spec.ts` 와 그것이 만든 스냅샷 디렉터리를 저장소 밖(`/Users/nhn/personal/fos-assistant/.omc/logs/plan023-visual/`)으로 옮긴다. 저장소에 남기지 않는다

## 검증

```bash
# cwd: 저장소 root
grep -rnoE '\b[a-z:-]*(bg|text|border|outline|ring|fill|stroke|divide|placeholder|decoration|shadow|from|to|via|caret|accent)-(surface-raised|surface|brand-strong|brand-soft|on-brand|brand|danger)\b' web/src
grep -rnE '\b(text|placeholder|decoration)-muted\b' web/src
grep -rn -- '--brand\|--on-brand\|--surface\|--danger' web/src test/browser
grep -rn 'text-white\|bg-white' web/src
git status --short test/browser | grep zz-visual
grep -rn 'style={{' web/src/
scripts/check-public-safe.sh
```

`grep` 여섯은 아무것도 내지 않아야 한다. 마지막 `style={{` 는 까닭을 적은 주석이 달린 예외만 나와야 한다.

```bash
# cwd: web/
pnpm typecheck
pnpm test:browser design-tokens.spec.ts identity.spec.ts chat.spec.ts nav.spec.ts theme.spec.ts
```

그다음 AGENTS.md 「확인」 절의 명령을 적힌 순서대로 모두 돌린다.

끝나면 `tasks/plan023-design-foundation/index.json` 의 이 phase 를 `completed` 로 바꾸고 `current_phase` 를 2로 올린다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `web/src/app/globals.css` | 수정 |
| `web/src/**/*.tsx` | 수정. 옛 토큰 클래스를 쓰는 모든 파일 |
| `test/browser/identity.spec.ts` | 수정 |
| `test/browser/chat.spec.ts` | 수정 |
| `test/browser/nav.spec.ts` | 수정 |
| `test/browser/design-tokens.spec.ts` | 신규 |
| `docs/code-architecture.md` | 수정 |
| `web/AGENTS.md` | 수정 |
