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
- `test/browser/starters.spec.ts`: 편집기 검사를 지우고 새 대화 화면 검사로 바꾼다. 에이전트를 고르면 추천 자리가 잠깐 비었다가 가짜 Hermes 의 네 추천이 칩으로 보이고, 칩을 누르면 그 글로 보낸다. 에이전트 상세에 「추천 질문」 편집 절이 없다
- `test/browser/start-screen.spec.ts`: `setStarters` 를 쓰던 소개 검사를 지우고, 에이전트가 하나일 때 이름과 추천만 보이는지로 바꾼다

## 검증

```bash
# cwd: 저장소 root
cd web && pnpm typecheck && pnpm build
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
| `tasks/plan044-starter-suggestions/index.json` | 수정 |

마지막 phase 다. 검증이 통과하면 `index.json` 의 `status` 를 `completed` 로 바꿔 이 커밋에 담는다. 그 뒤 PR 의 마지막 커밋으로 `tasks/plan044-starter-suggestions/` 를 지우고, `docs/code-architecture.md` 「아직 만들지 않은 것」 의 추천 질문 줄을 뺀다.
