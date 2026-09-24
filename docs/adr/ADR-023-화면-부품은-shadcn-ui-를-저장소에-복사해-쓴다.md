## ADR-023: 화면 부품은 shadcn/ui 를 저장소에 복사해 쓴다

- **status**: `accepted`
- **결정**: 버튼, 입력, 대화상자, 메뉴, 서랍 같은 화면 부품을 shadcn/ui 에서 가져와 `web/src/components/ui/` 에 소스로 둔다.
  동작과 접근성은 그 밑의 Radix 가 맡고, 모양의 변형은 `class-variance-authority` 로 모은다.
  색 토큰의 이름을 shadcn 의 이름 체계로 옮기고, 값과 인상은 그대로 둔다. 아이콘은 `lucide-react` 로 바꾼다.

### 맥락

화면 부품을 모두 직접 만들었다. 버튼, 뱃지, 뼈대 같은 모양 부품은 문제가 적다.
문제는 동작을 가진 부품이다. 서랍, 대화 메뉴, 확인 창 둘을 손으로 만들었다.

사이드바 화면 틀을 만들 때 리뷰가 그 자리에서 결함을 찾았다.
메뉴가 `Esc` 와 바깥 클릭으로 닫히지 않았고, 서랍의 `Esc` 는 캡처 단계에서 받아야 했다.
포커스를 가두는 것, 닫은 뒤 포커스를 돌려주는 것, `aria` 속성을 맞추는 것을 부품마다 다시 만들고 있다.
중지, 다시 생성, 새 대화 화면이 부품을 더 늘린다.

모양 쪽에도 반복이 있다. className 이 80자를 넘는 곳이 26곳이다. 같은 조합을 여러 파일이 따로 적는다.

### 대안 기각

- **지금처럼 직접 만든다.** 의존성은 늘지 않는다.
  그러나 접근성과 닫기 동작을 부품마다 다시 만들고, 리뷰가 같은 결함을 계속 찾는다.
- **Radix 만 쓰고 모양은 직접 만든다.** 동작 문제는 풀린다.
  className 반복은 그대로이고, 변형을 모으는 규칙을 우리가 새로 정해야 한다. shadcn 이 이미 그 규칙이다.
- **패키지로 설치하는 부품 라이브러리.** 버전을 올릴 때 모양이 함께 바뀌고, 우리 토큰에 맞추려면 덮어써야 한다.
  shadcn 은 소스를 복사하므로 우리가 고친 모양이 남는다.
- **토큰 이름을 그대로 두고 복사한 부품의 클래스를 우리 이름으로 바꾼다.**
  부품을 더하거나 다시 받을 때마다 바꿔야 한다.
  우리 `muted` 는 흐린 글자색이고 shadcn 의 `muted` 는 흐린 배경색이라, 하나라도 놓치면 글자와 배경이 뒤바뀐다.

### 토큰 이름

값은 바꾸지 않는다. 테라코타 브랜드 색, Pretendard, 밝기 모드 둘이 그대로다.

| 지금 | 옮길 이름 | 뜻 |
| --- | --- | --- |
| `background` | `background` | 화면 바탕 |
| `foreground` | `foreground` | 본문 글자 |
| `muted` | `muted-foreground` | 흐린 글자 |
| `surface` | `muted` | 흐린 바탕 |
| `surface-raised` | `accent` | 올라온 바탕, 마우스를 올렸을 때 |
| `border` | `border`, `input` | 테두리, 입력칸 테두리 |
| `brand` | `primary`, `ring` | 브랜드 색, 초점 테두리 |
| `on-brand` | `primary-foreground` | 브랜드 색 위의 글자 |
| `brand-strong` | `primary-strong` | 눌렀을 때. shadcn 에 없는 우리 토큰 |
| `brand-soft` | `primary-soft` | 내 말풍선 바탕. shadcn 에 없는 우리 토큰 |
| 없음 | `card`, `popover` 와 각 `-foreground` | 바탕과 본문 글자의 값을 그대로 쓴다 |
| 없음 | `secondary`, `secondary-foreground` | `accent` 와 같은 값 |
| `danger` (선언 없이 쓰였다) | `destructive`, `destructive-foreground` | 지우기처럼 되돌릴 수 없는 동작과 오류 글자. 새로 정하는 유일한 색 |
| `code-*` | `code-*` | 그대로 |

`text-danger` 와 `bg-danger` 는 코드에 있었지만 `globals.css` 가 그 토큰을 선언하지 않아 색이 빠져 있었다.
옮기면서 `destructive` 로 바꾸고 값을 정한다.

**`muted` 는 뜻이 뒤바뀐다.** 옮길 때 `muted` 를 먼저 `muted-foreground` 로 바꾸고, 그다음 `surface` 를 `muted` 로 바꾼다.
순서가 거꾸로면 흐린 바탕이 흐린 글자가 된다.

### 결과

- 얻는 것:
  - 대화상자, 메뉴, 서랍의 포커스 가두기, `Esc`, 바깥 클릭, `aria` 를 Radix 에서 받는다
  - 새 부품이 필요하면 먼저 shadcn 에서 가져오고 우리 토큰으로 바로 쓴다
  - 모양의 변형이 부품 파일 하나에 모인다
- 감당할 것:
  - 의존성이 는다. `@radix-ui/*`, `class-variance-authority`, `clsx`, `tailwind-merge`, `tw-animate-css`, `lucide-react` 다
  - 복사한 소스는 우리 코드다. shadcn 이 고친 것을 저절로 받지 못한다. 다시 받을 때는 우리가 고친 곳과 대조한다
  - Radix 의 역할이 우리가 직접 붙인 역할과 다를 수 있다. 확인 창은 `dialog` 대신 `alertdialog` 가 된다.
    접근성 이름은 바꾸지 않되, 역할이 바뀐 곳은 브라우저 테스트도 함께 바꾼다
  - 폼의 고르기 칸, 체크박스, 스위치는 Radix 부품으로 바꾸지 않는다. 네이티브 원소에 shadcn 모양만 입힌다.
    폼이 `FormData` 로 값을 읽고, 브라우저 테스트가 네이티브 원소에서만 되는 `selectOption` 과 `check` 를 쓴다
  - 대화 화면의 `Esc` 규칙과 Radix 의 닫기가 한 사건을 두고 만난다.
    Radix 가 닫으며 기본 동작을 막으면 대화 화면의 처리기가 건너뛴다는 것을 테스트로 확인한다
