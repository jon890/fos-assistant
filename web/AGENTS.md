# web

화면이다. Next.js 와 Tailwind 를 쓴다.

저장소 전체에 걸리는 규칙은 루트 [`AGENTS.md`](../AGENTS.md) 가 갖는다.
공개 저장소에 무엇을 적지 않는지도 그 문서가 정한다.

## 디렉터리

`app/` 은 경로와 서버에서 읽는 것만 담고, 화면을 이루는 부품은 `components/` 에 둔다.
`components/ui/` 는 어느 화면에도 속하지 않는 조각이고, 그 밖의 파일은 한 화면의 부품이다.

```
src/
  app/                    경로, 서버 컴포넌트, 서버 라우트
  components/
    ui/                   버튼, 입력, 표, 뼈대, 아이콘
    chat/                 대화 화면의 부품
    usage/                사용량 화면의 부품
    execution/            실행 나무 화면의 부품
    admin/                관리 화면의 부품
  lib/                    Control Plane 호출과 형식 변환
```

## 브라우저는 Control Plane 토큰을 갖지 않는다

서버 라우트가 세션에서 메일 주소를 꺼내 매 요청마다 토큰을 새로 만든다.
그래서 사용자가 요청 본문을 고쳐 남의 자료를 달라고 할 수 없다.

새 API 를 부르려면 `app/api/` 아래에 서버 라우트를 하나 만든다.
브라우저가 Control Plane 을 직접 부르게 하지 않는다.

## 색과 간격은 테마 토큰이 소유한다

색과 간격을 `style={{ background: "var(--surface)" }}` 처럼 인라인으로 적지 않는다.
`globals.css` 의 `@theme` 에 토큰을 선언하고 `bg-surface` 나 `ml-3` 같은 Tailwind 클래스로 쓴다.

인라인 스타일에는 `hover:` 와 `md:` 와 `disabled:` 를 붙일 수 없다.
그래서 인라인으로 적은 값 하나가 그 요소의 반응형과 상태 변화를 함께 막는다.
색이든 여백이든 이유가 같다.

**브랜드 색을 본문 글자에 쓰지 않는다.**
읽는 글이 색을 가지면 무엇이 누를 수 있는 것인지 알 수 없게 된다.

고쳤으면 아래가 아무것도 내지 않아야 한다.

```bash
# cwd: 저장소 root
grep -rn 'style={{' web/src/
```

값이 이어지는 수라서 클래스로 만들 수 없는 것만 예외다.
그때는 왜 인라인인지 주석으로 남긴다.

## 화면 밖에서 오는 글은 마크다운으로 읽는다

에이전트의 답은 마크다운이다.
`react-markdown` 과 `remark-gfm` 으로 그린다.

**들어온 HTML 을 그대로 그리지 않는다.**
`react-markdown` 의 기본값이 그렇고 그 기본값을 바꾸지 않는다.
에이전트의 답은 우리가 쓴 글이 아니라 모델이 만든 글이고,
도구가 읽어 온 남의 글이 섞일 수 있다.
근거는 [ADR-009](../docs/adr/ADR-009-에이전트의-답은-신뢰하지-않는-글로-그린다.md) 에 있다.

## 검사

```bash
# cwd: web/
pnpm typecheck
pnpm test:browser
```

**`pnpm build` 는 자리표시자 환경 변수가 있어야 통과한다.**
없으면 `Failed to collect page data` 로 끝나는데, 그것은 코드 결함이 아니다.
`Dockerfile` 이 쓰는 것과 같은 값을 준다.

```bash
# cwd: web/
AUTH_SECRET=build-time-placeholder \
ASSISTANT_JWT_SECRET=build-time-placeholder \
CONTROL_PLANE_BASE_URL=http://build-time-placeholder \
AUTH_GOOGLE_ID=build-time-placeholder \
AUTH_GOOGLE_SECRET=build-time-placeholder \
pnpm build
```

**포트는 다투지 않는다.** 기본값이 비어 있으면 그것을 쓰고, 다른 워크트리가 쥐고 있으면
빈 포트를 받아 쓴다. `test/support/pick-port.ts` 가 그 판단을 갖는다.
고정하고 싶으면 `BROWSER_WEB_PORT` 와 `BROWSER_CONTROL_PLANE_PORT` 를 준다.

`test/browser` 는 웹과 Chromium 을 띄워 화면을 검사한다.
`mobile` 과 `desktop` 두 폭에서 돌고 각각 390px 와 1280px 다.

**운영 코드에 시험용 문을 만들지 않는다.**
로그인은 테스트가 NextAuth 세션 쿠키를 직접 만들어 넣는다.
테스트일 때만 켜지는 우회를 두면 그 문이 운영에도 남는다.
