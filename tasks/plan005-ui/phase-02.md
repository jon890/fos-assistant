# Phase 02. 대화 화면을 다시 만든다

**Execution profile**: standard

## 목표

대화 화면이 화면 높이를 채우고 입력창이 아래에 붙게 한다.
좁은 화면에서는 대화 목록을 서랍으로 접는다.
에이전트의 답을 마크다운으로 그리고, 기다리는 종류마다 다른 표시를 보인다.

지금은 문서처럼 아래로 길어지는 구조라, 좁은 화면에서 대화 목록이 대화 위에 쌓여
대화가 여럿이면 입력창이 화면 밖으로 나간다.

**범위 외**

- 사용량 화면과 관리 화면은 phase-03 이 한다.
- 밝기 모드와 테마 토큰은 phase-01 이 이미 했다. 다시 하지 않는다.
- 실행 그래프 화면은 하지 않는다.

## 컨텍스트

**phase-01 이 끝나 있어야 한다.** 색이 Tailwind 클래스로 옮겨져 있어야 반응형을 붙일 수 있다.

**스트리밍이 이미 들어와 있다.** 답이 조각으로 오고 도구 호출 상태가 함께 온다.
그 흐름을 바꾸지 않는다. 이 phase 는 그것을 어떻게 보이느냐만 다룬다.

따를 패턴을 경로로 짚는다.

| 무엇 | 경로 |
| --- | --- |
| 대화 화면 | `web/src/components/chat-panel.tsx` |
| 대화 목록 | `web/src/components/conversation-list.tsx` |
| 스트림을 읽는 쪽 | `web/src/lib/stream.ts` |
| 스트림 중계 라우트 | `web/src/app/api/chat/stream/route.ts` |
| 바깥 틀 | `web/src/app/layout.tsx` |
| 오류 문구 | `web/src/components/error-message.ts` |

**근거 문서**: `docs/flow.md` 의 「화면 배치」 와 「기다리는 동안 보이는 것」 절,
`docs/code-architecture.md` 의 「web 화면 구조」 절,
`docs/adr/ADR-009-에이전트의-답은-신뢰하지-않는-글로-그린다.md`

**ADR-009 를 먼저 읽는다.** 마크다운을 그리는 방식이 거기서 정해진다.

## 의도 메모

- 서랍을 라이브러리로 만들지 않는다. 너비 조건과 `translate-x` 로 충분하다.
- 서랍이 열려 있을 때 뒤 화면이 스크롤되지 않게 한다.
  열어 둔 채 넘기면 뒤가 움직여 어디를 보고 있었는지 잃는다.
- 대화를 고르면 서랍을 닫는다. 고른 뒤에도 열려 있으면 결과를 볼 수 없다.
- 아래로 따라가는 것을 무조건 하지 않는다. 사용자가 위로 올려 읽고 있으면 그 자리를 지킨다.
  아래에서 얼마나 떨어져 있는지로 판단한다. 100px 안쪽이면 따라간다.
- 뼈대는 실제 내용과 같은 높이로 둔다. 다르면 내용이 도착할 때 화면이 튄다.
- 답이 흘러나오는 동안 회전 표시를 함께 두지 않는다.
  글자가 늘어나는 것이 진행이고, 둘을 함께 두면 무엇을 봐야 할지 모른다.
- 입력창은 `textarea` 로 두고 내용에 따라 높이를 늘린다. 다섯 줄에서 멈춘다.
- 모바일에서 입력창 글자 크기를 16px 아래로 두지 않는다.
  iOS Safari 가 그보다 작으면 입력할 때 화면을 확대한다.

## 작업 항목

### 1. 의존을 더한다

```bash
# cwd: web
pnpm add react-markdown remark-gfm shiki
```

### 2. 부품을 나눈다

`web/src/components/chat/` 를 만들고 아래로 나눈다.
지금 `chat-panel.tsx` 한 파일이 하는 일이 너무 많다.

| 파일 | 맡는 것 |
| --- | --- |
| `chat-panel.tsx` | 상태와 호출. 화면을 직접 그리지 않는다 |
| `chat/conversation-list.tsx` | 대화 목록. 지금 파일을 옮긴다 |
| `chat/conversation-drawer.tsx` | 좁은 화면에서 목록을 감싸는 서랍 |
| `chat/message-list.tsx` | 메시지 목록과 아래로 따라가는 처리 |
| `chat/message-bubble.tsx` | 한 줄. 보낸 사람과 본문 |
| `chat/markdown.tsx` | 마크다운 렌더링 |
| `chat/composer.tsx` | 입력창과 보내기 |
| `chat/run-status.tsx` | 도구 호출과 기다림 표시 |

`components/conversation-list.tsx` 는 `chat/` 아래로 옮긴다.

`web/src/components/ui/` 에 화면에 매이지 않는 것을 둔다.

| 파일 | 맡는 것 |
| --- | --- |
| `ui/skeleton.tsx` | 뼈대 한 줄 |
| `ui/icon-button.tsx` | 아이콘만 있는 단추 |

### 3. 화면 높이를 채운다

`web/src/app/layout.tsx` 와 대화 화면을 고친다.

- 머리는 높이가 고정되고 그 아래가 남은 높이를 모두 쓴다.
- `min-h-screen` 대신 `h-dvh` 를 쓴다. 모바일 주소창이 접힐 때 `100vh` 는 실제 높이와 어긋난다.
- 대화 화면 안에서 메시지 영역만 스크롤한다. 문서 전체가 스크롤되지 않는다.
- 사용량과 관리 화면은 지금처럼 문서 스크롤을 쓴다. 이 둘은 위에서 아래로 읽는 화면이다.

### 4. 서랍을 만든다

`chat/conversation-drawer.tsx` 를 만든다.

- `md` 이상에서는 서랍이 아니라 왼쪽 고정 칸으로 보인다.
- `md` 미만에서는 화면 밖에 있다가 열면 왼쪽에서 밀려 들어온다.
- 열려 있으면 뒤를 덮는 어두운 막을 둔다. 막을 누르면 닫힌다.
- `Escape` 로도 닫힌다.
- 열려 있는 동안 뒤 화면의 스크롤을 막는다.
- 대화를 고르면 닫힌다.

머리에 여는 단추를 둔다. `md` 이상에서는 보이지 않는다.

### 5. 마크다운을 그린다

`chat/markdown.tsx` 를 만든다.

- `react-markdown` 과 `remark-gfm` 을 쓴다.
- **`rehype-raw` 를 쓰지 않는다. `dangerouslySetInnerHTML` 을 쓰지 않는다.**
  근거는 ADR-009 에 있다.
- 링크는 새 탭으로 열고 `rel="noopener noreferrer"` 를 붙인다.
- 표는 가로로 넘칠 때 그 표만 밀리게 한다. 화면 전체가 밀리지 않는다.
- 코드 블록은 `shiki` 로 문법을 강조한다.
  **`shiki` 를 첫 화면에 함께 싣지 않는다.** 코드 블록이 실제로 나올 때 불러온다.
  불러오는 동안에는 강조 없이 고정폭 글자로 보인다.
  싣는 언어를 정해 둔다. 통째로 싣지 않는다.
- 사용자가 쓴 줄은 마크다운으로 그리지 않는다. 쓴 그대로 보인다.
  `*` 를 쓴 것이 기울임으로 바뀌면 쓴 사람이 놀란다.

### 6. 메시지 목록이 아래로 따라간다

`chat/message-list.tsx` 를 만든다.

- 메시지가 늘면 아래로 내린다.
- 사용자가 아래에서 100px 넘게 올라가 있으면 내리지 않는다.
- 내리지 않았으면 `새 메시지` 단추를 띄운다. 누르면 내려간다.
- 스트리밍으로 글자가 늘어날 때도 같은 규칙을 쓴다.

### 7. 기다림을 종류마다 다르게 보인다

| 기다리는 것 | 보이는 것 |
| --- | --- |
| 대화 목록을 처음 읽는다 | 목록 자리에 뼈대 세 줄 |
| 고른 대화의 메시지를 읽는다 | 메시지 자리에 뼈대 두 줄 |
| 답을 기다린다 | 비서 줄에 점 세 개가 차례로 밝아진다 |
| 답이 흘러나온다 | 글자가 그대로 쌓인다. 따로 표시하지 않는다 |
| 도구를 부르는 중이다 | 답 위에 그 도구 이름을 한 줄로 |

`chat/run-status.tsx` 가 마지막 둘을 맡는다.
스트림의 `tool` 사건에서 이름을 꺼낸다.

`prefers-reduced-motion` 을 켠 사용자에게는 점이 움직이지 않는다.
대신 글자로 알린다.

### 8. 입력창을 고친다

`chat/composer.tsx` 를 만든다.

- `textarea` 를 쓰고 내용에 따라 높이를 늘린다. 다섯 줄에서 멈추고 그 뒤로는 그 안에서 스크롤한다.
- `Enter` 로 보내고 `Shift+Enter` 로 줄을 바꾼다.
- 한글을 조합하는 중에는 `Enter` 로 보내지 않는다.
  `compositionstart` 와 `compositionend` 로 판단한다.
  이것을 하지 않으면 한글을 입력하다 첫 글자만 보내진다.
- 보내는 동안 잠그고, 실패하면 쓴 문장을 되돌린다. 지금 동작을 유지한다.
- 글자 크기를 16px 이상으로 둔다.

### 9. 에이전트와 작업 영역 고르는 자리를 정리한다

지금은 `label` 두 개가 세로로 쌓여 자리를 많이 쓴다.

- 메시지 영역 위에 한 줄로 둔다.
- 대화가 시작되어 고정된 뒤에는 고른 값만 보이고 고를 수 없다. 지금 동작을 유지한다.
- 좁은 화면에서 이름이 길면 줄이고 `title` 로 전체를 보인다.

### 10. 이 phase 를 검증하는 브라우저 테스트

`test/browser/chat.spec.ts` 를 만든다.
하네스는 phase-01 이 만든 `test/browser/fixtures.ts` 를 쓴다.

`mobile` 폭에서 확인하는 것

- 대화를 열 개 만들어도 입력창이 화면 안에 있다.
- 서랍을 열면 목록이 보이고, 대화를 고르면 닫힌다.
- 막을 누르면 닫힌다.

`desktop` 폭에서 확인하는 것

- 목록이 서랍이 아니라 왼쪽에 늘 보인다.
- 여는 단추가 보이지 않는다.

두 폭에서 함께 확인하는 것

- 표가 들어간 답이 `table` 로 그려진다.
- 답에 `script` 가 들어와도 실행되지 않는다.
  가짜 Hermes 가 `<script>` 가 든 답을 주도록 하고, 그것이 글자로 보이는지 본다.
- 위로 올려 둔 상태에서 답이 와도 스크롤 위치가 유지되고 `새 메시지` 가 보인다.

## 검증

```bash
# cwd: 저장소 root
cd web && pnpm typecheck
cd web && pnpm build
```

빌드는 자리표시자 환경 변수가 필요하다. `web/Dockerfile` 이 쓰는 것과 같다.

```bash
# cwd: 저장소 root
node test/e2e/run.ts
```

화면을 바꾸는 phase 라 e2e 가 새로 늘지 않는다. 기존 시나리오가 그대로 통과해야 한다.

```bash
# cwd: 저장소 root
grep -rn "dangerouslySetInnerHTML\|rehype-raw" web/src/ && echo "실패: 들어온 HTML 을 그리고 있다" || echo "통과"
```

```bash
# cwd: 저장소 root
grep -rn "min-h-screen\|100vh" web/src/ && echo "확인: 모바일 높이가 어긋날 수 있다" || echo "통과"
```

```bash
# cwd: 저장소 root
cd web && pnpm test:browser
```

`mobile` 과 `desktop` 두 폭에서 모두 통과해야 한다.

브라우저 테스트로 확인하지 못하는 것이 둘 있다. 직접 보고 결과를 보고에 적는다.

- 한글을 입력하다 `Enter` 를 눌러도 첫 글자만 보내지지 않는다.
  Playwright 의 입력은 한글 조합 사건을 실제 입력기처럼 만들지 못한다.
- 코드 블록의 문법 강조가 나중에 도착해 글자가 흔들리지 않는다.

## Critical Files

| 파일 | 변경 |
| --- | --- |
| `web/package.json` | 수정. `react-markdown`, `remark-gfm`, `shiki` |
| `web/src/components/chat/` | 신규. 여덟 파일 |
| `web/src/components/ui/skeleton.tsx` | 신규 |
| `web/src/components/ui/icon-button.tsx` | 신규 |
| `web/src/components/chat-panel.tsx` | 수정 |
| `web/src/components/conversation-list.tsx` | 이동 |
| `web/src/app/layout.tsx` | 수정 |
| `test/browser/chat.spec.ts` | 신규 |
| `test/e2e/fake-hermes.ts` | 수정. `script` 가 든 답을 주는 경우를 더한다 |

## 끝낸 뒤

`tasks/plan005-ui/index.json` 의 이 phase 를 `completed` 로 바꾸고
`current_phase` 를 다음 번호로 올린다.
