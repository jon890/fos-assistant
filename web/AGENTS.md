# web

화면이다. Next.js 와 Tailwind 를 쓴다.

화면 문서는 [`../docs/README.md`](../docs/README.md) 의 frontend 표에 있다.

## 브라우저는 Control Plane 토큰을 갖지 않는다

서버 라우트가 세션에서 메일 주소를 꺼내 매 요청마다 토큰을 새로 만든다.
그래서 사용자가 요청 본문을 고쳐 남의 자료를 달라고 할 수 없다.

새 API 를 부르려면 `app/api/` 아래에 서버 라우트를 하나 만든다.
브라우저가 Control Plane 을 직접 부르게 하지 않는다.

## 색과 간격은 테마 토큰이 소유한다

색과 간격을 `style={{ background: "var(--muted)" }}` 처럼 인라인으로 적지 않는다.
`globals.css` 의 `@theme` 에 토큰을 선언하고 `bg-muted` 나 `ml-3` 같은 Tailwind 클래스로 쓴다.

인라인 스타일에는 `hover:` 와 `md:` 와 `disabled:` 를 붙일 수 없다.

**강조 색(`primary`)은 주 단추, 지금 고른 것, 초점 테두리에만 쓴다.**
링크와 숫자는 글자색으로, 상태는 의미 색(`success`, `warning`, `info`, `destructive`)으로 그린다.
값과 쓰는 곳의 표는 [ADR-051](../docs/adr/ADR-051-화면-색은-새벽-보라로-바꾸고-강조-색은-누를-것과-고른-것과-초점에만-쓴다.md) 이 갖는다.

- 색을 `#` 값이나 `bg-white`, `bg-black` 으로 적지 않는다. 토큰이 없으면 `globals.css` 에 더한다.
  토큰으로 바꿀 수 없는 곳만 `artifact-panel.tsx` 의 `bg-white` 처럼 그 줄 위에 까닭을 주석으로 남긴다
- 모서리에 `rounded-3xl` 과 임의 값(`rounded-[…]`)을 쓰지 않는다. `test/unit/design-tokens.test.ts` 가 확인한다
- 안내와 오류 상자는 `components/ui/notice.tsx` 의 `Notice` 로, 상태 배지는 `Badge` 의 의미 색 변형으로 그린다
- 움직임의 길이와 곡선은 토큰(`duration-fast`, `duration-base`, `duration-slow`, `ease-out`, `ease-spring`)만 쓴다

고쳤으면 아래가 아무것도 내지 않아야 한다.

```bash
# cwd: 저장소 root
grep -rn 'style={{' web/src/
```

값이 이어지는 수라서 클래스로 만들 수 없는 것만 예외다.
그때는 왜 인라인인지 주석으로 남긴다.

## 화면 밖에서 오는 글은 마크다운으로 읽는다

**들어온 HTML 을 그대로 그리지 않는다.**
`react-markdown` 의 기본값이 그렇고 그 기본값을 바꾸지 않는다.
근거는 [ADR-009](../docs/adr/ADR-009-에이전트의-답은-신뢰하지-않는-글로-그린다.md) 에 있다.

## 화면 문구

사용자에게 보이는 한국어 문구는 아래를 따른다.
화면 글자, 버튼, 빈 상태, 확인 창, 오류 안내(`src/components/error-message.ts` 와 `src/app/api/**` 가 돌려주는 문구), `aria-label`, `title`, 페이지 `metadata` 가 모두 해당한다.
코드 주석과 docs 는 저장소 규칙(평서체)을 따르므로 여기 해당하지 않는다.

- **해요체로 쓴다.** 「아직 대화가 없어요.」, 「대화 이름을 바꾸지 못했어요.」. 한 문구 안에서 다른 문체와 섞지 않는다
- 비서가 사용자에게 하는 말은 높임을 쓴다. 「무엇을 도와드릴까요?」
- 사용자가 할 일은 사용자가 주어로 읽히게 쓴다. 「잠시 뒤 다시 보내 주세요.」. 「다시 보낸다」 는 비서가 스스로 하는 일로 읽힌다
- 일반 사용자에게 보이는 오류는 할 일만 알린다. Hermes, profile, API key 같은 내부 원인은 로그와 관리 화면에 남긴다
- 모델 이름, 토큰 수, 금액, 오류 코드, 에이전트 코드 같은 내부 값은 관리자 영역(`/admin` 아래)에서만 그린다. 그릴지는 `useAdminView()` 로 읽는다. 역할이 `ADMIN` 이어도 일반 화면에서는 거짓이다
- 관리 동작과 관리자 전용 표시는 [`../docs/frontend/structure.md`](../docs/frontend/structure.md) 의 「관리자 영역」 절을 따른다
- 버튼과 제목은 명사구나 짧은 동사구로 쓰고 질문형 제목을 쓰지 않는다. 확인 창 제목은 예외다. 「토큰을 폐기할까요?」 처럼 묻는다

화면에서 쓰는 말이다. docs 와 코드 식별자는 원래 이름을 그대로 쓴다.

| 안에서 부르는 이름 | 화면에서 쓰는 말 |
| --- | --- |
| Memory | 기억. 제안된 것은 「검토할 기억」, 범위는 「그룹이 함께 아는 것」 과 「나에 대해 아는 것」 |
| `DOCUMENT` | 문서. collection 은 「영역」, 민감 항목은 배지 「민감」 과 체크박스 문구 「민감한 내용이에요」, 판은 「N번째 판」 |
| 서비스 토큰 | 토큰. 절 제목은 「외부 서비스 연결」 |
| 들이기, import | 가져오기. 절 제목은 「기존 기록 가져오기」. `NEW` 는 「새로 가져와요」, `DUPLICATE` 는 「이미 가져왔어요」, `CONFLICT` 는 「같은 이름이 있어요」, `REJECTED` 는 「가져올 수 없어요」 |
| 실행 트리 | 작업 과정 |
| 하위 에이전트 | 도우미 |
| 설정 지문 | 설정별 사용량. 비교는 「설정 차이」, 값은 「설정 구분값」 |
| provider | 모델 제공사. 표시 규칙은 [`../docs/model-tiers.md`](../docs/model-tiers.md) 가 갖는다 |
| Hermes API 주소 | 에이전트 연결 주소 |
| credential 범위 | AI 계정 사용 범위 |
| Hermes profile | profile (관리자 영역에서만) |

## lint 와 포맷

화면 코드의 규칙은 문장이 아니라 `web/eslint.config.mjs` 가 갖는다.
도구 버전은 `web/package.json` 이 정한다.
규칙을 도구 설정으로 두는 근거는 [ADR-042](../docs/adr/ADR-042-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md) 에 있다.

### 기준 파일

지금 있는 error 위반은 `web/eslint-suppressions.json`(eslint 의 bulk suppressions)에 두고 새 위반만 실패시킨다.

**기준에 든 위반은 허용이 아니라 줄여 갈 목록이다.** 위반을 고치면 기준도 같은 커밋에서 줄인다.

| 언제 | 어떻게 |
| --- | --- |
| 기준에 든 위반을 고쳤다 | `pnpm lint` 가 「쓰지 않는 기준이 남았다」 며 2 로 끝난다. `pnpm exec eslint --prune-suppressions` 로 그 줄을 빼고 같은 커밋에 넣는다. `scripts/quality.sh fix` 도 이 명령을 돌린다 |
| 새 위반이 생겼다 | 고친다 |
| 꼭 받아들여야 한다 | `pnpm exec eslint --suppress-rule <규칙> <파일>` 로 더한다. 까닭을 커밋 메시지와 PR 본문에 적는다 |
| 규칙을 새로 더했다 | `pnpm exec eslint --suppress-all` 로 기존 위반을 기준에 둔다. 그 규칙의 위반 수를 커밋 메시지에 적는다 |

### 상대 경로 import 예외

`test/unit/*.test.ts` 가 `node --test` 로 읽는 web 파일과, 그 파일이 런타임에 import 하는 web 파일은 상대 경로 import 를 쓴다.
타입만 가져오는 `import type` 은 실행할 때 지워지므로 `@/` 를 써도 된다.

이 파일들은 `eslint.config.mjs` 의 `NODE_TEST_READ_FILES` 에 있고 `../*` 규칙에서 빠진다.
단위 테스트가 새 web 파일을 읽으면 그 파일의 `../` import 를 따라가 상대 경로로 import 하는 파일을 이 목록에 더한다.
그 파일이 다시 import 하는 파일도 상대 경로를 써야 한다.

### 포맷

**Prettier 는 저장소 전체를 검사하지 않고 `origin/main` 과의 공통 조상 뒤에 바뀐 파일만 검사한다.**
파일을 처음 고치는 PR 은 그 파일 전체가 포맷된다. 기능 변경과 포맷을 다른 커밋으로 나눈다.

## 검사

```bash
# cwd: web/
pnpm lint
pnpm format:check
pnpm typecheck
# 로컬에서는 고친 화면과 관련된 spec 만 돌린다. 전체는 PR 의 CI 가 돌린다
pnpm test:browser <spec 이름 일부>
```

**`pnpm build` 는 자리표시자 환경 변수가 있어야 통과한다.**
없으면 `Failed to collect page data` 로 끝나는데, 그것은 코드 결함이 아니다.
값은 `web/Dockerfile` 의 build 단계가 갖는다.

**브라우저 검사는 운영 이미지처럼 자리표시자로 빌드한 서버를 띄우고, 실행할 때 값을 준다.**
그래서 빌드할 때 환경 변수 값을 읽어 굳히는 코드를 쓰지 않는다.

화면을 고치며 같은 검사를 되풀이할 때는 개발 서버를 띄운다.

```bash
# cwd: web/
BROWSER_WEB_SERVER=dev pnpm test:browser
```

개발 서버는 화면에 보이는 링크를 미리 읽지 않는다.
빌드한 서버는 `prefetch={false}` 가 아닌 링크를 미리 읽고, 그 결과에 `loading.tsx` 화면이 있으면 누르자마자 그 화면으로 옮긴다.
그래서 `useLinkStatus` 의 이동 표시는 목적지를 아직 받지 못했을 때만 켜진다.
이 차이에 걸리는 검사는 빌드한 서버에서 확인한다.

**줄 수가 정해지지 않은 목록의 링크는 `prefetch={false}` 를 준다.**
기본값으로 두면 빌드한 서버가 보이는 링크마다 화면을 미리 읽어, 한 화면에서 요청이 수십 개 한꺼번에 나간다.
`test/browser/conversation-requests.spec.ts` 가 대화가 많은 사이드바의 미리 읽기가 0건인지 센다.

사용량 탭도 `prefetch={false}`를 쓴다. 탭마다 집계를 다시 읽으므로 고른 탭만 요청한다.
`usage.spec.ts`가 탭 미리 읽기 없이 주소와 선택 표시가 함께 바뀌는지 확인한다.

브라우저 검사의 포트를 고정하고 싶으면 `BROWSER_WEB_PORT` 와 `BROWSER_CONTROL_PLANE_PORT` 를 준다.

### 브라우저 시험의 독립성과 대기

CI shard마다 웹 서버, Control Plane, H2 DB와 임시 파일을 따로 만든다.
한 shard 안에서는 `workers: 1`을 유지한다. 서버 상태를 바꾸는 검사를 병렬로 돌리지 않는다.

- 다른 시험이 만든 대화나 할 일을 다음 시험의 준비 데이터로 쓰지 않는다.
  사용자 데이터는 `isolatedMember` fixture나 `helpers.ts`의 `isolatedUser`로 나눈다.
  실행, 폭, 시험과 반복 번호가 주소에 반영된다. 준비한 에이전트는 시험마다 지운다.
- 공통 fixture는 시험 전후에 대역의 장애, provider 차단과 보류 설정을 초기화한다.
  profile과 실행 기록은 지우지 않으므로 공유 에이전트의 설정을 고쳤으면 시험이 원래 값으로 돌린다.
- 문서 이동 뒤에는 스트리밍 HTML 조각의 배치와 `main`의 `aria-busy="false"`를 기다린다.
  SSR에 그려진 단추가 보인다는 사실만으로 이벤트가 붙었다고 판단하지 않는다.
  상태 변경 요청은 `clickAndWaitForResponse`로 응답 본문 수신까지 확인하고, 이어서 바뀐 화면을 단언한다.
  외부 스크립트를 막고 SSR 첫 그림을 확인하는 시험만 `waitForShellReady: false`를 명시한다.
  그 시험에서는 아직 준비 중인 `main`과 첫 그림의 색을 확인한다.
- SSE는 끝나지 않을 수 있으므로 전역 `networkidle`을 준비 조건으로 쓰지 않는다.
- 비동기 서버 작업은 해당 API의 완료 상태를 폴링한다. 고정 시간만 기다리지 않는다.
  폴링 간격 자체를 검사할 때는 `page.clock`으로 시간을 진행하고 요청 횟수와 화면 상태를 함께 본다.
- 날짜만 보는 대역 화면은 `fixBrowserTime`으로 시각을 고정한다. 서버와 왕복하는 시험은 서버 시각을 기준으로 데이터를 만든다.
  월별 집계의 준비 데이터는 러너의 시간대와 관계없이 `Asia/Seoul` 달력으로 만든다.
- 일반 동작 시험은 움직임 줄이기를 켠다. 움직임 자체를 검사하는 spec은 `reducedMotion: "no-preference"`를 명시한다.
  짧은 움직임은 끝난 뒤 계산값을 읽지 않고 시작 사건을 기록해 검증한다.
  색 전환은 움직임 줄이기에서도 남을 수 있으므로 모드를 바꾼 뒤 최종 CSS 토큰 색까지 기다린다.

CI는 전체 shard 검사 뒤 새 spec과 수정 spec을 재시도 없이 3회 반복한다.
공통 fixture, 프레임워크 설정이나 화면 틀을 바꾸거나 매일 실행할 때는 `browser-stability.mjs`의 공유 상태 회귀 묶음도 반복한다.
한 번이라도 실패하면 `browser-mobile` 또는 `browser-desktop` 필수 검사가 실패한다.
로컬에서도 관련 spec에 `--repeat-each=3 --retries=0`을 주어 반복과 두 폭을 확인한다.

App Router는 Next.js가 묶어 넣은 React를 쓴다. `package.json`의 React만 올렸다고 런타임 결함이 고쳐졌다고 판단하지 않는다.
서버가 답했는데 화면 갱신이 멎으면 빌드한 서버에서 재현하고, 응답 도착과 화면 반영을 나눠 확인한다.
trace의 헤더 수신 시간이나 검사 종료 때의 `ERR_ABORTED`만으로 서버 응답이 끊겼다고 결론 내리지 않는다.
프레임워크를 고쳤으면 기본 화면 전환을 켠 상태에서 반복 검사를 통과시킨다.

**운영 코드에 시험용 문을 만들지 않는다.**
로그인은 테스트가 NextAuth 세션 쿠키를 직접 만들어 넣는다.
테스트일 때만 켜지는 우회를 두면 그 문이 운영에도 남는다.
