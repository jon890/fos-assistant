# Phase 02. 새 대화 화면이 만든 추천을 읽고 추천 편집기와 한 줄 소개를 없앤다

**Execution profile**: standard

## 목표

새 대화 화면이 고른 에이전트의 추천을 `GET /api/v1/agents/{code}/starters` 에서 읽는다. `GENERATING` 이면 잠시 뒤 다시 읽는다.
에이전트 상세의 추천 질문 편집기와 한 줄 소개를 화면에서 모두 없앤다.

**범위 외**: backend(phase 01). 스킬 절과 사용량 탭(다른 계획).

## 컨텍스트

**근거 문서**: `docs/flow.md` 의 「새 대화 화면」 과 「추천을 만들 때」 절, `docs/code-architecture.md` 의 「추천 질문」, 「에이전트 화면」 절, `docs/adr/ADR-036-추천-질문은-사용자의-대화-이력으로-모델이-만들고-메모리에만-둔다.md`

- phase 01 뒤 backend 응답: `GET /api/v1/agents/{code}/starters` → `{ "prompts": string[], "status": "READY" | "GENERATING" | "NONE" }`. `PUT` 은 없다. 에이전트 목록 응답에 `tagline`, `starterPrompts` 가 없다
- `web/src/lib/agent.ts`: `AgentView` 의 `tagline`, `starterPrompts`, 그리고 `StartersView`
- `web/src/components/chat-panel.tsx`: 새 대화 화면에서 `<StarterPrompts prompts={currentAgent?.starterPrompts ?? []} … />` 로 목록 응답의 추천을 그린다
- `web/src/components/chat/start-screen.tsx`: `StartScreenHeader` 가 에이전트가 하나일 때 `only.tagline` 을 그린다. `StarterPrompts` 가 칩을 그린다
- `web/src/components/chat/agent-picker.tsx`, `web/src/components/chat/agent-mention.tsx`: `agent.tagline` 을 그린다
- `web/src/components/agent/agent-detail-body.tsx`: `StarterEditor` 를 그리고 `initialStarters` 를 받는다. `web/src/app/agents/[code]/page.tsx` 가 `StartersView` 를 읽어 넘긴다
- `web/src/app/api/agents/[code]/starters/route.ts`: `GET` 과 `PUT` 을 Control Plane 으로 넘긴다
- 브라우저 검사는 `test/browser/` 이고 가짜 Hermes(`test/e2e/fake-hermes.ts`)를 쓴다. phase 01 이 `[추천 질문 만들기]` 입력에 네 추천을 답하게 했다. `test/browser/fixtures.ts` 의 `setStarters` 는 `PUT` 을 부른다

## 의도 메모

- 다시 읽기는 `GENERATING` 일 때만, 2초 간격으로 최대 3번이다. 그래도 `GENERATING` 이면 자리를 비워 둔다. 무한히 읽지 않는다
- 에이전트를 바꾸면 이전 에이전트의 다시 읽기를 멈춘다. 늦게 온 응답이 다른 에이전트의 추천을 그리지 않게 요청 순번으로 거른다(`conversations-provider` 가 쓰는 순번 방식과 같다)
- 추천은 화면이 에이전트를 고를 때 읽는다. 목록 응답에 기대지 않는다
- **브라우저 검사는 backend 하나를 모든 spec 과 두 project 가 함께 쓴다.** 앞선 spec 이 `/` 를 열면 그 사용자와 에이전트의 추천이 이미 캐시에 있다. 그래서 「비었다가 채워진다」 와 다시 읽기 횟수, 순번 거르기는 `page.route("**/api/agents/*/starters")` 로 응답을 정해 검사한다. 가짜 Hermes 를 거치는 실제 경로는 칩이 네 추천으로 보이는지만 본다
- 추천을 만드는 실행도 실행 줄과 사용량에 남는다. 사용량 화면 검사가 실행 수를 세면 그 영향을 확인한다

## 작업 항목

### 1. 타입과 서버 라우트

- `web/src/lib/agent.ts`: `AgentView` 에서 `tagline`, `starterPrompts` 를 빼고 `StartersView` 를 `{ prompts: string[]; status: "READY" | "GENERATING" | "NONE" }` 로 바꾼다
- `web/src/app/api/agents/[code]/starters/route.ts`: `PUT` 을 지운다

### 2. 새 대화 화면

- `web/src/components/chat/start-screen.tsx`: `StartScreenHeader` 에서 소개를 지운다. 추천을 읽는 훅 `useStarterSuggestions(code: string | null)` 을 이 파일이나 `web/src/components/chat/use-starter-suggestions.ts` 에 두고 위 의도 메모대로 다시 읽는다
- `web/src/components/chat-panel.tsx`: `StarterPrompts` 에 훅의 추천을 넘긴다
- `web/src/components/chat/agent-picker.tsx`, `web/src/components/chat/agent-mention.tsx`: 소개 줄을 지운다

### 3. 에이전트 상세

- `web/src/components/agent/starter-editor.tsx` 를 지운다
- `web/src/components/agent/agent-detail-body.tsx`, `web/src/app/agents/[code]/page.tsx`: `initialStarters`, `StartersView` 읽기와 편집기 자리를 지운다

### 4. 이 phase 를 검증하는 브라우저 검사

- `test/browser/fixtures.ts`: `setStarters` 를 지운다
- `test/browser/starters.spec.ts`: 편집기 검사를 지우고 새 대화 화면 검사로 바꾼다
  - 가로채지 않고 열면 가짜 Hermes 의 네 추천이 칩으로 보이고, 칩을 누르면 그 글로 보낸다
  - `page.route` 로 첫 응답 `GENERATING`, 다음 응답 `READY` 를 주면 추천 자리가 비었다가 칩이 보인다
  - 세 번 모두 `GENERATING` 이면 자리가 빈 채로 남고, 첫 요청 뒤 다시 읽기가 세 번을 넘지 않는다(네 번째 다시 읽기 요청이 없다)
  - 에이전트 A 의 응답을 늦춰 둔 채 에이전트 B 로 바꾸면, 늦게 온 A 의 추천을 그리지 않고 B 의 추천만 보인다
  - 에이전트 상세에 「추천 질문」 편집 절이 없다
- `test/browser/start-screen.spec.ts`: `beforeEach` 의 `setStarters` 를 지운다. `setStarters` 에 기대던 `PROMPT` 는 가짜 Hermes 가 답하는 넷 가운데 하나로 바꾼다. 소개 검사는 에이전트가 하나일 때 이름과 추천만 보이는지로 바꾼다. 「다른 에이전트 카드를 누르면 그 추천으로 바뀐다」 검사는 가짜 Hermes 가 모든 profile 에 같은 넷을 답하므로 `page.route("**/api/agents/*/starters")` 로 에이전트마다 다른 추천을 주도록 다시 쓴다

### 5. docs

- `docs/flow.md` 「새 대화 화면」 표의 「추천을 만드는 중이다」 줄: 「그 자리를 비워 두고 2초 간격으로 세 번까지 다시 읽는다. 그래도 만드는 중이면 비워 둔다」
- `docs/code-architecture.md` 「추천 질문」 절 표의 `GET` 줄: 「`GENERATING` 이면 화면이 2초 간격으로 세 번까지 다시 읽는다」
- `docs/code-architecture.md` 화면 표의 `/agents/{code}` 줄 「성격, 소개와 추천 질문, 도구」 에서 「소개와 추천 질문」 을 뺀다
- `docs/code-architecture.md` 「아직 만들지 않은 것」 의 「모델이 만드는 추천 질문」 줄을 뺀다

## 검증

```bash
# cwd: 저장소 root
cd web && pnpm typecheck && pnpm build   # web/AGENTS.md 의 자리표시자 환경 변수를 주고 빌드한다
cd web && pnpm test:browser
grep -rn "tagline\|starterPrompts\|StarterEditor\|setStarters" web/src test/browser  # 결과 없음
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/lib/agent.ts` | 수정 |
| `web/src/app/api/agents/[[]code]/starters/route.ts` | 수정 |
| `web/src/components/chat/start-screen.tsx` | 수정 |
| `web/src/components/chat/use-starter-suggestions.ts` | 신규 |
| `web/src/components/chat-panel.tsx` | 수정 |
| `web/src/components/chat/agent-picker.tsx` | 수정 |
| `web/src/components/chat/agent-mention.tsx` | 수정 |
| `web/src/components/agent/starter-editor.tsx` | 삭제 |
| `web/src/components/agent/agent-detail-body.tsx` | 수정 |
| `web/src/app/agents/[[]code]/page.tsx` | 수정 |
| `test/browser/fixtures.ts` | 수정 |
| `test/browser/starters.spec.ts` | 수정 |
| `test/browser/start-screen.spec.ts` | 수정 |
| `docs/flow.md` | 수정 |
| `docs/code-architecture.md` | 수정 |

마지막 phase 다. 검증이 통과한 뒤 PR 의 마지막 커밋으로 `tasks/plan044-starter-suggestions/` 를 지운다.
