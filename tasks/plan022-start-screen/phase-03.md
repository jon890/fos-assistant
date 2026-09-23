# Phase 03. 새 대화 화면에 에이전트 카드와 추천 질문과 @ 고르기를 둔다

**Execution profile**: deep

## 목표

`/` 의 새 대화 화면을 인사와 에이전트 카드와 가운데 입력창과 추천 질문으로 채운다.
입력창에 `@` 를 치면 에이전트를 이름으로 골라 대화를 시작할 수 있게 한다.
어느 에이전트가 무엇을 하는지 모르는 채 빈 입력창 앞에 서지 않게 하기 위해서다.

**범위 외**: 소개와 추천 질문의 저장과 편집은 앞 phase 가 했다.
대화가 시작된 뒤의 화면(작업 과정, 중지, 다시 생성, 메시지 동작)은 바꾸지 않는다.
이미 시작한 대화에서 에이전트를 바꾸는 기능은 만들지 않는다.

## 컨텍스트

**이 phase 는 대화 화면 개선의 앞선 작업이 모두 들어온 코드 위에서 돈다.** 아래가 이미 있어야 한다.

- `/` 가 새 대화 화면이고 `/c/[conversationId]` 가 대화 하나다. `web/src/app/c/[conversationId]/page.tsx` 가 있다
- `web/src/components/shell/` 이 사이드바와 대화 목록 context 를 갖는다
- 첫 메시지를 보내면 `started` 사건의 대화 번호로 주소가 `/c/{id}` 로 바뀐다
- 스트리밍 경로의 사건 계약은 `docs/code-architecture.md` 의 「화면으로 보내는 사건」 표와 같다

대화 화면의 부품 구성은 앞선 작업이 바꿨으므로 **이 문서가 파일 안의 줄을 짚지 않는다.**
`web/src/components/chat-panel.tsx` 와 `web/src/components/chat/` 을 먼저 열어
대화 번호가 없고 메시지가 없을 때 무엇을 그리는지, 보내기를 어느 함수가 하는지 찾는다.
이 문서가 `send` 라고 부르는 것은 그 함수다.

그 밖에 쓰는 것이다.

- 에이전트 목록은 브라우저가 `GET /api/agents` 로 읽는다. 서버 라우트는 `web/src/app/api/agents/route.ts` 다.
  한 줄의 형식은 `web/src/lib/agent.ts` 의 `AgentView` 이고 앞 phase 가 `tagline` 과 `starterPrompts` 를 더했다.
  지금 `chat-panel.tsx` 는 같은 모양을 `type Agent = { code; name; model; visibility }` 로 따로 선언해 쓴다.
  남아 있으면 `AgentView` 로 바꾼다.
- 사용자 이름은 `web/src/lib/me.ts` 의 `readMe()` 가 주는 `Me.displayName` 이다. 서버 컴포넌트에서만 부른다.
- 입력창은 `web/src/components/chat/composer.tsx` 다.
  한글 조합 중 Enter 를 보내기로 읽지 않으려고 `composing` ref 와 `event.nativeEvent.isComposing` 을 함께 본다.
  `@` 고르기도 같은 판정을 따른다.
- 색과 간격은 테마 토큰으로만 쓴다. `web/AGENTS.md` 의 「색과 간격은 테마 토큰이 소유한다」 절이 규칙이다.
- 브라우저 검사의 도우미는 `test/browser/fixtures.ts` 에 있다.
  `setAgentVisibility` 가 Control Plane 을 JWT 로 직접 부르는 선례다.
  `page.route` 로 서버 라우트 응답을 바꾸는 선례는 `test/browser/execution-tree.spec.ts` 에 있다.
  검사는 `workers: 1` 로 한 번에 하나씩 돈다.
- 테스트 계정이 쓰는 에이전트는 `fixtures.ts` 의 `seedAgents` 가 만든다.
  목록은 `code` 차례로 오므로 첫 에이전트는 `browser`(「브라우저 비서」)다.

**근거 문서**: `docs/flow.md` 의 「새 대화 화면」 과 「화면 틀」 절,
`docs/code-architecture.md` 의 「우리 화면의 정체성」 절의 대화 화면 요소 표와 「소개와 추천 질문」 절

## 의도 메모

- `@` 는 새 대화에서만 뜬다. 대화의 에이전트는 첫 메시지가 정하고 `hermes_session_id` 가 그 profile 안의 값이라 바뀌지 않는다.
  대화 중에 다른 에이전트를 부르는 것은 에이전트가 스스로 정한다. 근거는 ADR-017 이다.
- `@이름` 을 보내는 글에 남기지 않는다. 모델이 그 글자를 지시로 읽을 수 있다.
- 추천 질문을 누르면 입력창에 채우지 않고 바로 보낸다. 고칠 것이면 입력창에 직접 쓴다.
- 새 대화에서 에이전트를 고르는 수단은 카드와 `@` 둘이다. 앞서 있던 `<select>` 가 남아 있으면 지운다.
  수단이 셋이면 어느 것이 지금 고른 것인지 두 곳이 어긋날 수 있다.
- 카드가 많으면 가로로 밀어 본다. 줄을 여러 번 바꾸면 입력창이 화면 아래로 밀린다.

## Blocked 조건

- `web/src/app/c/[conversationId]/page.tsx` 가 없다 → `PHASE_BLOCKED: 대화 주소가 아직 없다`
- `web/src/components/shell/` 이 없다 → `PHASE_BLOCKED: 화면 틀이 아직 없다`
- 스트리밍 경로가 `started` 사건을 보내지 않는다 → `PHASE_BLOCKED: 첫 메시지 뒤 주소를 바꿀 사건이 없다`

## 작업 항목

### 1. `web/src/app/page.tsx`

`readMe()` 를 불러 `displayName` 을 새 대화 화면에 넘긴다. 읽지 못하면 `null` 을 넘기고 인사는 「무엇을 도와줄까요」 만 보인다.
로그인 확인과 `redirect("/signin")` 은 그대로 둔다.

### 2. `web/src/components/chat/start-screen.tsx` 신규

```ts
type Props = {
  displayName: string | null;
  agents: AgentView[];
  loading: boolean;
  selectedCode: string;
  onSelect(code: string): void;
  onPrompt(text: string): void;
  composer: React.ReactNode;
};
export function StartScreen(props: Props)
```

위에서 아래로 이 차례다.

1. `h1` 「{displayName}님, 무엇을 도와줄까요」. 대화 화면이 가진 `sr-only` 제목 「대화」 와 겹치면 그것을 이 화면에서는 그리지 않는다
2. `AgentPicker`. 에이전트가 둘 이상일 때만
3. 에이전트가 하나일 때는 카드 대신 그 이름과 소개 한 줄
4. `composer` 로 받은 입력창. 화면 세로 가운데 근처에 둔다
5. 고른 에이전트의 추천 질문 단추들. 누르면 `onPrompt(text)`

| 상태 | 그리는 것 |
| --- | --- |
| `loading` | 카드 자리에 `components/ui/skeleton.tsx` 의 `Skeleton` 셋. 카드와 같은 높이 |
| 에이전트가 없다 | 카드 자리에 「쓸 수 있는 에이전트가 없다. 관리자에게 등록을 요청한다.」. 입력창은 잠근다 |
| 소개나 추천 질문이 없다 | 그 자리를 그리지 않는다 |

### 3. `web/src/components/chat/agent-picker.tsx` 신규

```ts
type Props = { agents: AgentView[]; selectedCode: string; onSelect(code: string): void };
export function AgentPicker(props: Props)
```

- `role="radiogroup"`, `aria-label="에이전트"`.
- 카드 하나가 `button` 이고 `role="radio"`, `aria-checked`. 안에 이름과 소개 한 줄. 소개는 두 줄에서 자른다.
- 고른 카드는 `border-brand` 로 테두리만 바꾼다. 브랜드 색을 글자에 쓰지 않는다.
- 방향키 좌우로 다음 카드에 초점과 고름이 함께 옮겨 간다. 라디오 묶음의 기본 동작이다.
- 가로로 넘치면 그 줄 안에서만 `overflow-x-auto` 로 민다. 페이지가 가로로 밀리면 안 된다.

### 4. `web/src/components/chat/agent-mention.tsx` 신규

입력창 위에 뜨는 고르기 목록이다.

```ts
type Props = { agents: AgentView[]; query: string; activeIndex: number; onPick(code: string): void };
export function AgentMention(props: Props)
export function findMention(value: string, caret: number): { start: number; query: string } | null
export function filterAgents(agents: AgentView[], query: string): AgentView[]
```

- `findMention`: 커서 앞에서 가장 가까운 `@` 를 찾는다. 그 `@` 가 글의 맨 앞이거나 바로 앞이 공백이고,
  `@` 와 커서 사이에 공백이 없을 때만 돌려준다. 이메일 주소 속의 `@` 에서 뜨지 않게 하기 위해서다.
- `filterAgents`: 이름에 `query` 가 들어 있는 것. 대소문자를 가리지 않는다. 빈 `query` 는 전부다.
- 목록은 `role="listbox"`, `aria-label="에이전트 고르기"`. 한 줄이 `role="option"`, `aria-selected`.
  맞는 것이 없으면 목록 안에 「맞는 에이전트가 없다」 한 줄.

### 5. `web/src/components/chat/composer.tsx`

`mention?: { agents: AgentView[]; onPick(code: string): void }` 를 선택 속성으로 더한다.
주지 않으면 지금과 같다. 이미 시작한 대화에서는 주지 않는다.

- 글이나 커서가 바뀔 때마다 `findMention` 을 다시 부른다. 조합 중이어도 거르기는 한다.
- 목록이 떠 있으면 `ArrowUp` 과 `ArrowDown` 이 줄을 옮기고, `Enter` 와 `Tab` 이 고르고, `Escape` 가 닫는다.
  이때 `Enter` 는 보내지 않는다. 조합 중인 `Enter` 는 지금처럼 무시한다.
- 고르면 `mention.onPick(code)` 를 부르고 `@` 부터 커서까지를 글에서 빼고 커서를 그 자리에 둔다.
- `Escape` 는 목록을 닫는 데서 멈춘다. 처리하면 `preventDefault` 와 `stopPropagation` 을 함께 부른다.
  `docs/flow.md` 「화면 틀」 의 규칙대로, `chat-panel.tsx` 의 단일 `Esc` 처리기는 안쪽이 이미 처리한 사건을 건너뛴다.
  목록이 닫혀 있을 때의 `Escape` 는 막지 않는다. 그때는 그 처리기가 패널 닫기나 중지를 한다.
- 입력칸에 `aria-controls` 와 `aria-activedescendant` 를 목록에 맞춘다. 목록이 없으면 두 속성을 뺀다.
- 새 대화에서 자리표시 글은 「@ 로 에이전트를 부른다」 다.

### 6. 대화 화면에서 새 대화 상태를 이 화면으로 바꾼다

대화 번호가 없고 메시지가 없을 때 그리던 것을 `StartScreen` 으로 바꾼다.

- 에이전트 목록을 읽는 곳은 하나로 둔다. 이미 `/api/agents` 를 읽고 있으면 그 결과를 넘긴다.
- 처음 고른 에이전트는 목록의 첫 에이전트다. 지금 동작과 같다.
- 추천 질문을 누르면 그 글과 지금 고른 에이전트로 `send` 를 부른다. 입력창에 쓰던 글은 그대로 둔다.
  `send` 가 입력창의 글만 읽게 되어 있으면 보낼 글을 인자로 받게 바꾼다.
- 첫 메시지를 보내면 `StartScreen` 이 사라지고 입력창이 아래로 간다. 주소가 `/c/{id}` 로 바뀌는 것은 이미 있는 동작이다.
- 새 대화 상태에서 에이전트를 고르는 `<select>` 가 남아 있으면 지운다.
  그것을 쓰던 `test/browser/` 의 검사를 카드 고르기로 바꾼다.
  지금은 `test/browser/flow-progress.spec.ts` 가 `getByRole("combobox").selectOption({ label: ... })` 을 쓴다.
  `page.getByRole("radio", { name: "흐름 비서" }).click()` 으로 바꾼다.

### 7. `test/browser/fixtures.ts`

`setStarters(code: string, tagline: string | null, starterPrompts: string[]): Promise<void>` 를 더한다.
`setAgentVisibility` 와 같이 JWT 를 만들어 `PUT ${CONTROL_PLANE_BASE_URL}/api/v1/agents/${code}/starters` 를 부른다.
실패하면 상태 코드와 본문을 담아 던진다.

### 8. `docs/code-architecture.md`

「아직 만들지 않은 것」 절의 「에이전트 소개와 추천 질문, 새 대화 화면」 줄을 뺀다.
그 위의 「대화 화면 개선」 목록에 남은 줄이 없으면 목록 전체를 뺀다.

### 9. 이 phase 를 검증하는 `test/browser/start-screen.spec.ts` 신규

`browser` 에이전트의 추천 질문은 이 파일만 쓴다. 앞 phase 의 검사는 「성격 비서」 를 쓴다.

- `setStarters("browser", "브라우저 검사용 비서다", ["첫 추천 질문이다"])` 뒤에 `/` 를 열면
  「님, 무엇을 도와줄까요」 가 든 제목과 `radio` 「브라우저 비서」 의 `aria-checked="true"` 와 「첫 추천 질문이다」 단추가 보인다.
- 그 단추를 누르면 사용자 메시지 「첫 추천 질문이다」 가 보이고 주소가 `/\/c\/\d+$/` 이 된다.
- `/` 에서 「흐름 비서」 카드를 누르면 「첫 추천 질문이다」 가 사라진다.
- 입력창에 `@흐름` 을 치면 `listbox` 「에이전트 고르기」 에 「흐름 비서」 가 뜨고, `Enter` 를 누르면
  「흐름 비서」 카드가 골라지고 입력창이 비고 메시지가 나가지 않는다.
- `@없는이름` 을 치면 「맞는 에이전트가 없다」 가 보인다.
- `me@example.com` 처럼 공백 없이 붙은 `@` 에서는 목록이 뜨지 않는다.
- `page.route("**/api/agents", ...)` 로 `[]` 를 돌려주면 「쓸 수 있는 에이전트가 없다」 가 보이고 입력창이 잠긴다.
- 같은 방법으로 한 줄만 돌려주면 `radiogroup` 이 없고 그 에이전트의 이름이 보인다.
- 두 폭 모두 `document.documentElement.scrollWidth <= document.documentElement.clientWidth` 이다.

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
cd web && pnpm typecheck
cd web && pnpm test:browser
node test/e2e/run.ts
scripts/check-public-safe.sh
grep -rn 'style={{' web/src/
grep -rn 'combobox").selectOption' test/browser/
```

각 줄을 저장소 root 에서 따로 돌린다. 앞의 다섯은 종료 코드 0 이고 적힌 순서대로 돌린다.
`style={{` grep 이 내는 줄은 모두 바로 위에 인라인인 까닭을 적은 주석이 있어야 하고, 이 phase 가 새로 더한 줄은 없어야 한다.
근거는 `web/AGENTS.md` 의 「색과 간격은 테마 토큰이 소유한다」 절이다.
`combobox").selectOption` grep 은 아무것도 내지 않아야 한다. 에이전트를 `<select>` 로 고르던 곳이 모두 카드 고르기로 바뀌었다는 뜻이다.
`memory.spec.ts` 의 `getByLabel("범위")` 와 `usage-breakdown.spec.ts` 의 `breakdown-axis` 는 에이전트와 상관없는 `select` 라 건드리지 않는다.
`pnpm build` 는 `web/AGENTS.md` 의 「검사」 절이 적은 자리표시자 환경 변수를 주고 돌려 종료 코드 0 을 본다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `web/src/app/page.tsx` | 수정 |
| `web/src/components/chat/start-screen.tsx` | 신규 |
| `web/src/components/chat/agent-picker.tsx` | 신규 |
| `web/src/components/chat/agent-mention.tsx` | 신규 |
| `web/src/components/chat/composer.tsx` | 수정 |
| `web/src/components/chat-panel.tsx` | 수정 |
| `test/browser/fixtures.ts` | 수정 |
| `test/browser/start-screen.spec.ts` | 신규 |
| `test/browser/flow-progress.spec.ts` | 수정 |
| `docs/code-architecture.md` | 수정 |

끝나면 `tasks/plan022-start-screen/index.json` 의 이 phase 를 `completed` 로, plan 의 `status` 를 `completed` 로 바꾼다.
