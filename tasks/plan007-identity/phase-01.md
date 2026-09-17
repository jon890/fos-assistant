# Phase 01. 글꼴과 브랜드 색을 들인다

**Execution profile**: standard

## 목표

Pretendard 를 우리가 들고 배포하고, 테라코타 브랜드 색 계열을 토큰으로 더한다.
그 색을 쓰는 단추 조각을 만들어 화면마다 같은 단추가 나오게 한다.

지금 화면이 평범해 보이는 원인이 둘이다.
**색 다섯 개가 모두 회색 계열이라 시선이 멈추는 곳이 없다.**
그리고 `globals.css` 의 `font-family` 가 `system-ui, -apple-system, "Pretendard"` 순서라
Pretendard 가 `system-ui` 뒤에 있어 한 번도 쓰이지 않고, 파일도 불러오지 않는다.

**범위 외**

- 대화 화면의 배치는 phase-02 가 한다.
- 로그인 화면과 머리는 phase-03 이 한다.
- 새 아이콘 그림을 그리지 않는다. 글자와 기본 도형으로 만든다.

## 컨텍스트

웹은 Next.js 16 과 React 19 를 쓰고 Tailwind v4 로 꾸민다.
**Tailwind v4 는 `tailwind.config.js` 를 쓰지 않는다.** CSS 안의 `@theme` 에 토큰을 선언한다.

지금 `globals.css` 가 이렇게 되어 있다.

- `@custom-variant dark (&:where(.dark, .dark *))` 로 `dark:` 를 클래스에 붙인다
- `@theme inline` 에 `--color-*` 를 선언하고 `:root` 와 `.dark` 에 실제 값을 둔다
- 색은 `background`, `foreground`, `muted`, `surface`, `border` 다섯과 코드용 여섯이다

따를 패턴을 경로로 짚는다.

| 무엇 | 경로 |
| --- | --- |
| 토큰 선언 | `web/src/app/globals.css` |
| 바깥 틀 | `web/src/app/layout.tsx` |
| 지금 있는 단추 조각 | `web/src/components/ui/icon-button.tsx` |
| 지금 있는 표시 조각 | `web/src/components/ui/badge.tsx` |

**근거 문서**: `docs/code-architecture.md` 의 「우리 화면의 정체성」 절

## 의도 메모

- 브랜드 색을 본문 글자에 쓰지 않는다. 누를 수 있는 것과 읽는 것이 섞인다.
- 회색 단계를 하나 더한다. 대화 목록 칸과 본문의 배경이 갈려야 열이 구분된다.
  지금 `surface` 하나로 말풍선과 칸을 함께 쓰고 있어 둘을 나눌 수 없다.
- 어두운 화면의 브랜드 색을 밝은 화면 값과 같게 두지 않는다.
  어두운 배경에서 `#B05A3C` 는 탁해 보인다. 밝은 쪽으로 올린 값을 따로 정한다.
- 글꼴을 CDN 에서 받지 않는다. 가족만 쓰는 화면이라 화면을 열 때마다 외부로 요청이 나가는 것을 피한다.
- 글꼴 때문에 첫 화면의 글자가 나중에 밀리지 않아야 한다. `next/font` 가 그 처리를 한다.
- 단추를 화면마다 따로 만들지 않는다. 지금 `rounded-md border` 를 손으로 적은 곳이 여러 군데다.

## 작업 항목

### 1. Pretendard 를 들인다

```bash
# cwd: web
pnpm add pretendard
```

`web/src/app/layout.tsx` 에서 `next/font/local` 로 불러온다.

- `node_modules/pretendard` 안의 가변 woff2 를 쓴다. 정확한 경로는 그 패키지를 열어 확인한다.
- `display: "swap"` 을 준다.
- `variable` 로 CSS 변수를 받아 `html` 에 붙인다.
- `globals.css` 의 `font-family` 가 그 변수를 첫 자리에 쓴다.
  지금처럼 `system-ui` 를 앞에 두면 Pretendard 가 쓰이지 않는다.

가변 woff2 가 없으면 굵기 400 과 600 두 개만 불러온다.
굵기를 전부 불러오지 않는다. 쓰지 않는 파일이 배포에 들어간다.

### 2. 브랜드 색 토큰을 더한다

`globals.css` 에 아래를 더한다.

| 토큰 | 밝은 화면 | 어두운 화면 |
| --- | --- | --- |
| `--brand` | `#B05A3C` | 밝은 쪽으로 올린 값을 정한다 |
| `--brand-strong` | `#8F4530` | 같은 규칙으로 정한다 |
| `--brand-soft` | `#F5E6E0` | 어두운 배경에 맞는 값을 정한다 |
| `--on-brand` | `#FFFFFF` | 대비를 보고 정한다 |
| `--surface-raised` | 대화 목록 칸의 배경 | 같음 |

어두운 화면의 값은 실제로 보고 정한다.
밝은 화면 값을 그대로 쓰면 탁하다.

`--brand` 위에 `--on-brand` 를 얹은 글자의 명암 대비가 4.5:1 이상이어야 한다.
계산해서 보고에 적는다.

### 3. 단추 조각을 만든다

`web/src/components/ui/button.tsx` 를 만든다.

| 종류 | 모양 |
| --- | --- |
| `primary` | 브랜드 색으로 꽉 찬 배경, `on-brand` 글자 |
| `secondary` | 테두리와 본문 글자 |
| `ghost` | 배경과 테두리 없이 글자만 |

- `hover:` 와 `active:` 와 `disabled:` 를 모두 붙인다.
- 크기는 `sm` 과 `md` 둘이다.
- `type` 기본값을 `button` 으로 둔다. 폼 안에서 뜻하지 않게 보내지는 것을 막는다.

지금 `rounded-md border px-4 py-2` 를 손으로 적은 자리를 이 조각으로 바꾼다.
찾는 방법은 아래와 같다.

```bash
# cwd: 저장소 root
grep -rn "rounded-md border" web/src/ | grep -i button
```

**대화 화면의 보내기 단추는 건드리지 않는다.** phase-02 가 다시 만든다.

### 4. 모서리와 여백 규칙을 정한다

`globals.css` 에 토큰으로 둔다.

- 모서리는 셋으로 제한한다. 작은 것, 보통, 알약이다.
- 지금 화면에 `rounded-md` 와 `rounded-lg` 가 섞여 있다. 하나로 모은다.

무엇을 어디에 쓰는지 `globals.css` 의 한국어 주석으로 남긴다.

### 5. 이 phase 를 검증하는 브라우저 테스트

`test/browser/identity.spec.ts` 를 만든다.
하네스는 `test/browser/fixtures.ts` 를 쓴다.

- `html` 의 `font-family` 첫 항목이 Pretendard 계열이다.
- 밝은 화면과 어두운 화면에서 `--brand` 값이 서로 다르다.
- `primary` 단추의 배경이 브랜드 색이고 투명이 아니다.
- 글꼴 파일을 외부 주소에서 받지 않는다.
  `page.route` 로 우리 주소가 아닌 글꼴 요청을 잡아 하나도 없는지 본다.

마지막 항목이 중요하다. CDN 으로 되돌아가면 이 테스트가 막는다.

## 검증

```bash
# cwd: 저장소 root
cd web && pnpm typecheck
cd web && pnpm build
```

빌드는 자리표시자 환경 변수가 필요하다. `web/Dockerfile` 이 쓰는 것과 같다.

```bash
# cwd: 저장소 root
cd web && pnpm test:browser
node test/e2e/run.ts
```

```bash
# cwd: 저장소 root
grep -rn "cdn.jsdelivr\|fonts.googleapis\|fonts.gstatic" web/src/ && echo "실패: 글꼴을 외부에서 받는다" || echo "통과"
```

```bash
# cwd: 저장소 root
grep -rn 'style={{' web/src/ | grep -iE 'background|color|border' && echo "실패: 색 인라인 스타일" || echo "통과"
```

## Critical Files

| 파일 | 변경 |
| --- | --- |
| `web/package.json` | 수정. `pretendard` |
| `web/src/app/globals.css` | 수정 |
| `web/src/app/layout.tsx` | 수정 |
| `web/src/components/ui/button.tsx` | 신규 |
| `test/browser/identity.spec.ts` | 신규 |

## 끝낸 뒤

`tasks/plan007-identity/index.json` 의 이 phase 를 `completed` 로 바꾸고
`current_phase` 를 다음 번호로 올린다.
