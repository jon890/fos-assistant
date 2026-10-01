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
    ui/                   shadcn/ui 에서 받은 부품과 우리가 만든 조각
    chat/                 대화 화면의 부품
    usage/                사용량 화면의 부품
    execution/            실행 나무 화면의 부품
    agent/                에이전트와 성격 화면의 부품
    admin/                관리 화면의 부품
  lib/                    Control Plane 호출과 형식 변환
```

## 브라우저는 Control Plane 토큰을 갖지 않는다

서버 라우트가 세션에서 메일 주소를 꺼내 매 요청마다 토큰을 새로 만든다.
그래서 사용자가 요청 본문을 고쳐 남의 자료를 달라고 할 수 없다.

새 API 를 부르려면 `app/api/` 아래에 서버 라우트를 하나 만든다.
브라우저가 Control Plane 을 직접 부르게 하지 않는다.

## 색과 간격은 테마 토큰이 소유한다

색과 간격을 `style={{ background: "var(--muted)" }}` 처럼 인라인으로 적지 않는다.
`globals.css` 의 `@theme` 에 토큰을 선언하고 `bg-muted` 나 `ml-3` 같은 Tailwind 클래스로 쓴다.

인라인 스타일에는 `hover:` 와 `md:` 와 `disabled:` 를 붙일 수 없다.
그래서 인라인으로 적은 값 하나가 그 요소의 반응형과 상태 변화를 함께 막는다.
색이든 여백이든 이유가 같다.

**강조 색(`primary`)은 주 단추, 지금 고른 것, 초점 테두리에만 쓴다.**
링크와 숫자는 글자색으로, 상태는 의미 색(`success`, `warning`, `info`, `destructive`)으로 그린다.
읽는 글이 강조 색을 가지면 무엇이 누를 수 있는 것인지 알 수 없게 된다.
값과 쓰는 곳의 표는 [ADR-047](../docs/adr/ADR-047-화면-색은-새벽-보라로-바꾸고-강조-색은-누를-것과-고른-것과-초점에만-쓴다.md) 이 갖는다.

- 색을 `#` 값이나 `bg-white`, `bg-black` 으로 적지 않는다. 토큰이 없으면 `globals.css` 에 더한다
- 모서리는 `rounded-sm`, `rounded-md`, `rounded-lg`, `rounded-xl`, `rounded-2xl`, `rounded-full` 만 쓴다
- 안내와 오류 상자는 `components/ui/notice.tsx` 의 `Notice` 로, 상태 배지는 `Badge` 의 의미 색 변형으로 그린다
- 움직임의 길이와 곡선은 토큰(`duration-fast`, `duration-base`, `duration-slow`, `ease-out`, `ease-spring`)만 쓴다.
  줄인 움직임 설정은 `globals.css` 의 블록 하나가 정하므로 부품에 `motion-reduce:` 를 적지 않는다. 끝없이 도는 회전 표시와 뼈대만 `motion-reduce:animate-none` 을 적고, 그 표시를 문장으로 바꿔 보일 때만 `motion-reduce:hidden`, `motion-reduce:flex`, `motion-reduce:inline` 을 적는다

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

## 화면 문구

사용자에게 보이는 한국어 문구는 아래를 따른다.
화면 글자, 버튼, 빈 상태, 확인 창, 오류 안내(`src/components/error-message.ts` 와 `src/app/api/**` 가 돌려주는 문구), `aria-label`, `title`, 페이지 `metadata` 가 모두 해당한다.
코드 주석과 docs 는 저장소 규칙(평서체)을 따르므로 여기 해당하지 않는다.

- **해요체로 쓴다.** 「아직 대화가 없어요.」, 「대화 이름을 바꾸지 못했어요.」. 평서체(-다)와 합쇼체(-니다)를 쓰지 않고, 한 문구 안에서 섞지 않는다
- 비서가 사용자에게 하는 말은 높임을 쓴다. 「무엇을 도와드릴까요?」
- 사용자가 할 일은 사용자가 주어로 읽히게 쓴다. 「잠시 뒤 다시 보내 주세요.」. 「다시 보낸다」 는 비서가 스스로 하는 일로 읽힌다
- 일반 사용자에게 보이는 오류는 할 일만 알린다. Hermes, profile, API key 같은 내부 원인은 로그와 관리 화면에 남긴다
- 모델 이름, 토큰 수, 금액, 오류 코드, 에이전트 코드 같은 내부 값은 `ADMIN` 에게만 그린다. 역할은 `useShellIsAdmin()` 으로 읽는다
- 버튼과 제목은 명사구나 짧은 동사구로 쓰고 질문형 제목을 쓰지 않는다

화면에서 쓰는 말이다. docs 와 코드 식별자는 원래 이름을 그대로 쓴다.

| 안에서 부르는 이름 | 화면에서 쓰는 말 |
| --- | --- |
| Memory | 기억. 제안된 것은 「검토할 기억」, 범위는 「그룹이 함께 아는 것」 과 「나에 대해 아는 것」 |
| 실행 나무 | 작업 과정 |
| 하위 에이전트 | 도우미 |
| 설정 지문 | 설정별 사용량. 비교는 「설정 차이」, 값은 「설정 구분값」 |
| provider | 모델 제공사 |
| Hermes API 주소 | 에이전트 연결 주소 |
| credential 범위 | AI 계정 사용 범위 |
| Hermes profile | profile (관리 화면에서만) |

## lint 와 포맷

화면 코드의 규칙은 문장이 아니라 `web/eslint.config.mjs` 가 갖는다.
도구는 eslint 9.39.5 와 `eslint-config-next` 16.0.10, Prettier 3.9.9 이고 버전을 정확한 값으로 고정한다.
규칙을 도구 설정으로 두는 근거는 [ADR-042](../docs/adr/ADR-042-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md) 에 있다.

```bash
# cwd: web/
pnpm lint
pnpm format:check
pnpm format:changed
```

backend 와 함께 한 번에 검사하고 고치려면 저장소 root 에서 `scripts/quality.sh check` 와 `scripts/quality.sh fix` 를 쓴다.
`fix` 는 `eslint --fix --prune-suppressions` 와 `pnpm format:changed` 를 돌리고 새 위반은 기준에 더하지 않는다.

| 규칙 | 대상 | 심각도 | 까닭 |
| --- | --- | --- | --- |
| `eslint-config-next/core-web-vitals`, `eslint-config-next/typescript` | 전체 | 설정대로 | Next.js 와 React 의 결함 모양 |
| `no-restricted-syntax`: `NextResponse.json({ code, ... })`, `request.json()`(이름이 `request` 나 `req` 인 것) | `src/app/api/**` | error | 오류 응답 모양과 요청 본문 검사를 한곳의 도우미로 모은다. 라우트가 직접 만들면 모양이 라우트마다 달라진다 |
| `no-restricted-globals`: `fetch` | `src/components/**`, `src/app/**/*.tsx` | error | 화면은 `lib/` 의 호출 함수를 거친다. 화면이 주소와 오류 처리를 각자 다시 쓰지 않게 한다 |
| `max-lines` 400, `max-lines-per-function` 150. 빈 줄과 주석은 세지 않는다 | `src/**` | warning | 쪼갤 후보 목록이다. 실패시키지 않는다 |
| `no-restricted-imports`: `../*` | `src/**` | error | `@/` 를 쓰면 파일을 옮겨도 import 가 깨지지 않는다 |

`fetch` 가 꼭 필요한 화면 파일이 생기면 `eslint.config.mjs` 의 그 규칙 블록에 `ignores` 로 파일과 까닭을 적는다.

### 기준 파일

지금 있는 error 위반은 `web/eslint-suppressions.json` 에 두고 새 위반만 실패시킨다.
eslint 의 bulk suppressions 이고, 파일과 규칙마다 위반 수를 적는다.
warning 은 기준에 넣지 않는다.

**기준에 든 위반은 허용이 아니라 줄여 갈 목록이다.**
기준마다 연 GitHub 이슈가 있다. 위반을 고치면 기준도 같은 커밋에서 줄인다.

| 언제 | 어떻게 |
| --- | --- |
| 기준에 든 위반을 고쳤다 | `pnpm lint` 가 「쓰지 않는 기준이 남았다」 며 2 로 끝난다. `pnpm exec eslint --prune-suppressions` 로 그 줄을 빼고 같은 커밋에 넣는다 |
| 새 위반이 생겼다 | 기준에 더하지 않고 고친다 |
| 꼭 받아들여야 한다 | `pnpm exec eslint --suppress-rule <규칙> <파일>` 로 더한다. 까닭을 커밋 메시지와 PR 본문에 적는다 |
| 규칙을 새로 더했다 | `pnpm exec eslint --suppress-all` 로 기존 위반을 기준에 둔다. 그 규칙의 위반 수를 커밋 메시지에 적는다 |

`eslint-suppressions.json` 은 `.prettierignore` 에 있다. eslint 는 이 파일을 끝 줄바꿈 없이 쓰고 Prettier 는 줄바꿈을 붙여, 두 도구가 번갈아 고치기 때문이다.

### 상대 경로 import 예외

`test/unit/*.test.ts` 는 `node --test` 로 돌고 web 파일을 상대 경로로 읽는다.
Node 는 tsconfig 의 `@/` 별칭을 풀지 못한다.
그래서 이 테스트가 읽는 web 파일과, 그 파일이 런타임에 import 하는 web 파일은 상대 경로 import 를 쓴다.
타입만 가져오는 `import type` 은 실행할 때 지워지므로 `@/` 를 써도 된다.

이 파일들은 `eslint.config.mjs` 의 `NODE_TEST_READ_FILES` 에 있고 `../*` 규칙에서 빠진다.
지금 목록은 `src/components/chat/activity/activity-state.ts` 와 `src/components/chat/skill-command.ts` 이다.
단위 테스트가 새 web 파일을 읽으면 그 파일의 `../` import 를 따라가 상대 경로로 import 하는 파일을 이 목록에 더한다.
그 파일이 다시 import 하는 파일도 상대 경로를 써야 한다.

### 포맷

Prettier 는 설정 파일 없이 기본값을 쓴다. `.prettierignore` 가 빌드 결과와 잠금 파일을 뺀다.
**저장소 전체를 검사하지 않고 `origin/main` 과의 공통 조상 뒤에 바뀐 파일만 검사한다.**
여러 브랜치가 같은 파일을 나란히 고치므로 전체를 한 번에 포맷하면 진행 중인 브랜치가 모두 충돌한다.

- `pnpm format:check` 는 바뀐 파일이 Prettier 모양인지 검사한다. 바뀐 파일이 없으면 성공한다
- `pnpm format:changed` 는 바뀐 파일을 고쳐 쓴다
- 바뀐 파일의 목록은 `web/scripts/changed-files.mjs` 하나가 만든다. 추적하지 않는 새 파일도 포함하고 `origin/main` 이 없으면 2 로 끝난다
- 마크다운은 대상이 아니다. Prettier 는 표의 열을 공백으로 맞추는데 이 저장소의 문서 표는 맞추지 않고 쓴다

파일을 처음 고치는 PR 은 그 파일 전체가 포맷된다. 기능 변경과 포맷을 다른 커밋으로 나눈다.

## 검사

```bash
# cwd: web/
pnpm lint
pnpm format:check
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

**브라우저 검사는 빌드한 서버를 띄운다.**
`test/browser/web-server.ts` 가 위와 같은 자리표시자로 `pnpm build` 를 돌린다.
그다음 `Dockerfile` 처럼 `.next/static` 과 `public` 을 standalone 결과에 옮기고 `node .next/standalone/server.js` 를 띄운다.
검사용 환경 변수는 서버를 띄울 때만 준다.
운영 이미지도 자리표시자로 빌드하고 실행할 때 값을 받으므로, 빌드 때 값을 읽어 굳히는 코드가 생기면 검사에서 드러난다.

검사할 때마다 먼저 빌드한다. 이미 있는 빌드가 지금 소스와 같은지 확실히 알 수 없기 때문이다.
수정 시각으로 비교하면 지운 파일과 브랜치 전환을 놓친다. 빌드는 10초 남짓 걸린다.

화면을 고치며 같은 검사를 되풀이할 때는 개발 서버를 띄운다.

```bash
# cwd: web/
BROWSER_WEB_SERVER=dev pnpm test:browser
```

개발 서버는 화면에 보이는 링크를 미리 읽지 않는다.
빌드한 서버는 미리 읽고, 그 결과에 `loading.tsx` 화면이 있으면 누르자마자 그 화면으로 옮긴다.
그래서 `useLinkStatus` 의 이동 표시는 목적지를 아직 받지 못했을 때만 켜진다.
이 차이에 걸리는 검사는 빌드한 서버에서 확인하고, 머지 전 확인도 빌드한 서버로 돌린다.

**포트는 다투지 않는다.** 기본값이 비어 있으면 그것을 쓰고, 다른 워크트리가 쥐고 있으면
빈 포트를 받아 쓴다. `test/support/pick-port.ts` 가 그 판단을 갖는다.
고정하고 싶으면 `BROWSER_WEB_PORT` 와 `BROWSER_CONTROL_PLANE_PORT` 를 준다.

`test/browser` 는 웹과 Chromium 을 띄워 화면을 검사한다.
`mobile` 과 `desktop` 두 폭에서 돌고 각각 390px 와 1280px 다.

**운영 코드에 시험용 문을 만들지 않는다.**
로그인은 테스트가 NextAuth 세션 쿠키를 직접 만들어 넣는다.
테스트일 때만 켜지는 우회를 두면 그 문이 운영에도 남는다.
