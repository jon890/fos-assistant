# Phase 02. 에이전트 화면에서 소개와 추천 질문을 고친다

**Execution profile**: standard

## 목표

`/agents/{code}` 의 성격 편집 아래에 소개 한 줄과 추천 질문 넷까지를 고치는 절을 둔다.
새 대화 화면에 보일 것을 사용자가 홈서버를 만지지 않고 쓰게 하기 위해서다.

**범위 외**: 새 대화 화면에 보이는 것은 다음 phase 가 맡는다.
성격 편집기의 동작은 바꾸지 않는다.

## 컨텍스트

- 백엔드 경로는 앞 phase 가 만들었다.
  `GET` 과 `PUT /api/v1/agents/{code}/starters` 가 `{ tagline, starterPrompts, editable, maxPrompts }` 를 돌려주고,
  `PUT` 본문은 `{ tagline, starterPrompts }` 다.
  `GET /api/v1/agents` 의 한 줄에 `tagline` 과 `starterPrompts` 가 더해졌다.
- 서버 라우트의 선례는 `web/src/app/api/agents/[code]/persona/route.ts` 다.
  `CODE` 정규식으로 코드를 거르고 `callControlPlane` 으로 부르고 오류를 `{ code, message }` 와 상태 코드로 옮긴다.
  브라우저가 Control Plane 을 직접 부르지 않는다. `web/AGENTS.md` 의 「브라우저는 Control Plane 토큰을 갖지 않는다」 절이 그 규칙이다.
- 화면의 선례는 `web/src/app/agents/[code]/page.tsx` 와 `web/src/components/agent/persona-editor.tsx` 다.
  페이지가 서버에서 `callControlPlane<AgentView[]>("/api/v1/agents")` 와 성격을 함께 읽고
  `PersonaEditor` 에 `code`, `name`, `initialPersona` 를 넘긴다.
  `PersonaEditor` 는 `editable` 이 거짓이면 읽기 전용으로 그리고 저장 결과를 「저장되었습니다」 로 알린다.
- 형식은 `web/src/lib/agent.ts` 에 모은다. `AgentView` 와 `PersonaView` 가 거기 있다.
- 오류 문구는 `web/src/components/error-message.ts` 의 `describeError(code, message)` 로 옮긴다.
- 색과 간격은 테마 토큰으로만 쓴다. `grep -rn 'style={{' web/src/` 가 내는 줄은 모두 왜 인라인인지 적은 주석이 달린 예외여야 한다.
  값이 이어지는 수라서 클래스로 만들 수 없는 것만 예외다. 근거는 `web/AGENTS.md` 의 「색과 간격은 테마 토큰이 소유한다」 절이다.
- 브라우저 검사 선례는 `test/browser/persona.spec.ts` 다.
  `PERSONA_AGENT_CODE`(「성격 비서」, 주인이 테스트 계정)와
  `PERSONA_EMPTY_AGENT_CODE`(끝까지 아무도 쓰지 않는 에이전트)와
  `setAgentVisibility`, `setSession` 이 `test/browser/fixtures.ts` 에 있다.

**근거 문서**: `docs/code-architecture.md` 의 「페르소나」 절 아래 「누가 고칠 수 있나」 와 「소개와 추천 질문」,
`docs/data-schema.md` 의 「agent_starter_prompt」 절

## 의도 메모

- 성격처럼 확인 창을 띄우지 않는다. 성격은 모든 실행의 고정 프롬프트에 들어가 되돌리기 어렵지만,
  소개와 추천 질문은 화면에만 보인다.
- 빈 칸을 거르는 판단은 서버가 갖는다. 화면은 받은 그대로 보내고 저장된 결과로 칸을 다시 채운다.
  둘이 따로 거르면 규칙이 어긋난다.
- 추천 질문 칸은 언제나 `maxPrompts` 개를 그린다. 줄을 더하고 빼는 단추를 두지 않는다. 넷뿐이다.
- 새 에이전트 목록 형식을 쓰는 다른 화면은 지금 없다. `AgentView` 에 칸을 더해도 기존 호출이 깨지지 않는다.

## 작업 항목

### 1. `web/src/lib/agent.ts`

- `AgentView` 에 `tagline: string | null;` 과 `starterPrompts: string[];` 를 더한다.
- `export type StartersView = { tagline: string | null; starterPrompts: string[]; editable: boolean; maxPrompts: number };`
  한국어 JSDoc 한 줄을 붙인다.

### 2. `web/src/app/api/agents/[code]/starters/route.ts` 신규

`persona/route.ts` 와 같은 모양의 `GET` 과 `PUT` 이다. Control Plane 경로는 `/api/v1/agents/${code}/starters`.
`CODE` 정규식은 그 파일의 것을 복사하지 말고 `web/src/lib/agent.ts` 로 옮겨 `export const AGENT_CODE_PATTERN` 으로 두고
두 라우트가 함께 쓴다.

### 3. `web/src/components/agent/starter-editor.tsx` 신규

```ts
type Props = { code: string; name: string; initialStarters: StartersView };
export function StarterEditor({ code, name, initialStarters }: Props)
```

- 제목 「새 대화 화면」, 설명 한 줄 「새 대화에서 이 에이전트를 고르면 보인다」.
- 소개 입력 하나. 접근 가능한 이름 `{name} 소개`. `maxLength={200}`.
- 추천 질문 입력 `maxPrompts` 개. 이름 `추천 질문 1` 부터 `추천 질문 {maxPrompts}`. `maxLength={300}`.
- 저장 단추 「소개와 추천 질문 저장」. 누르면 `PUT /api/agents/{code}/starters` 에 `{ tagline, starterPrompts }` 를 보낸다.
  성공하면 응답으로 칸을 다시 채우고 「소개와 추천 질문이 저장되었습니다.」, 실패하면 `describeError` 문구를 `role="alert"` 로.
  저장 중에는 단추를 잠근다.
- `editable` 이 거짓이면 입력을 모두 `readOnly` 로 두고 저장 단추를 그리지 않는다.
  `PersonaEditor` 가 읽기 전용일 때와 같은 모양이다. 따로 안내 문구를 두지 않는다.

**한 화면에 저장 단추가 둘이 된다.**
Playwright 의 `getByRole("button", { name: "저장" })` 은 이름의 일부만 맞아도 고르므로
`test/browser/persona.spec.ts` 의 그 호출이 두 단추를 함께 잡아 실패한다.
그 파일의 `{ name: "저장" }` 을 모두 `{ name: "저장", exact: true }` 로 바꾼다.
`getByText("저장되었습니다")` 도 같은 이유로 `{ exact: true }` 를 붙여 `"저장되었습니다."` 로 고친다.

### 4. `web/src/app/agents/[code]/page.tsx`

`Promise.all` 에 `callControlPlane<StartersView>(`/api/v1/agents/${code}/starters`)` 를 더한다.
성격이 성공한 경로에서 `PersonaEditor` 아래에 `StarterEditor` 를 그린다.
추천 질문 조회만 실패하면 성격은 그대로 그리고 그 절 자리에 오류 한 줄을 `role="alert"` 로 그린다.

### 5. 이 phase 를 검증하는 `test/browser/starters.spec.ts` 신규

- `PERSONA_AGENT_CODE` 화면에서 소개와 추천 질문 둘을 쓰고 저장하면 「소개와 추천 질문이 저장되었습니다.」 가 보이고,
  새로 고친 뒤에도 칸에 그 값이 있다.
  mobile 과 desktop 두 폭이 차례로 같은 에이전트에 쓴다. 앞 폭이 남긴 값 위에서도 통과하도록 값을 상수로 두고 폭마다 같은 값을 쓴다.
- 가운데 칸을 비우고 저장하면 저장 뒤 칸이 앞으로 당겨져 채워진다. 서버가 빈 줄을 버렸다는 뜻이다.
- `persona.spec.ts` 의 가족 공개 검사처럼 `setAgentVisibility` 로 `PERSONA_FAMILY_AGENT_CODE` 를 `FAMILY` 로 바꾸고
  `MEMBER` 세션으로 열면 저장 단추가 없고 입력이 읽기 전용이다. 끝나면 되돌린다.

## 검증

```bash
# cwd: web/
pnpm typecheck
pnpm test:browser
grep -rn 'style={{' src/
grep -rn '\^\[a-z0-9\]\[a-z0-9-\]{0,63}\$' src/app/api/agents/
```

앞의 둘은 종료 코드 0 이다.
세 번째가 내는 줄은 모두 바로 위에 인라인인 까닭을 적은 주석이 있어야 한다. 이 phase 가 새로 더한 줄은 없어야 한다.
네 번째는 아무것도 내지 않아야 한다.
마지막은 아무것도 내지 않아야 한다. 코드 정규식이 라우트마다 복사되지 않았다는 뜻이다.
`pnpm build` 는 `web/AGENTS.md` 의 「검사」 절이 적은 자리표시자 환경 변수를 주고 돌린다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `web/src/lib/agent.ts` | 수정 |
| `web/src/app/api/agents/[code]/starters/route.ts` | 신규 |
| `web/src/app/api/agents/[code]/persona/route.ts` | 수정 |
| `web/src/components/agent/starter-editor.tsx` | 신규 |
| `web/src/app/agents/[code]/page.tsx` | 수정 |
| `test/browser/starters.spec.ts` | 신규 |
| `test/browser/persona.spec.ts` | 수정 |

끝나면 `tasks/plan022-start-screen/index.json` 의 이 phase 를 `completed` 로 바꾸고 `current_phase` 를 3으로 올린다.
