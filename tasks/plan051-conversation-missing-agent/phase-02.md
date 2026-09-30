# Phase 02. 화면이 이름 없는 에이전트를 「지운 에이전트」 로 그린다

**Execution profile**: standard

## 목표

phase 01 뒤로 대화 목록과 실행 기록의 `agentCode`, `agentName` 이 null 일 수 있다.
화면의 타입을 그에 맞추고 null 이름을 「지운 에이전트」 로 그린다.
에이전트가 없는 대화를 열면 다른 에이전트의 모델 목록, 사진 단추, 스킬 목록이 보이지 않게 한다.

**범위 외**: 지운 에이전트(`deleted_at` 있음)의 대화 표시 방식 변경. 실행 나무(`execution/execution-node.tsx`, `execution/execution-detail.tsx`)는 이미 null 을 받아 `실행 #번호` 로 그리므로 고치지 않는다.

## 컨텍스트

- `web/src/components/shell/conversations-provider.tsx` 의 `Conversation` 타입이 `agentCode: string`, `agentName: string` 이다
- `web/src/components/chat-panel.tsx`
  - 에이전트 목록을 읽으면 `setAgentCode((current) => current || data[0]?.code || "")` 가 돈다(177행 부근). 고른 대화의 `agentCode` 를 넣는 effect(385행 부근)도 있다. 대화 목록이 먼저 읽힌 채 대화를 열면 빈 코드가 첫 에이전트의 코드로 채워진다
  - `currentAgent = agents.find((agent) => agent.code === agentCode)` 가 모델 고르기(`Composer` 의 `agentCode`), 사진 단추(`acceptsAttachments`), 스킬 목록(`commandAgentCode`), 스킬 칩(`skillCommandChips`), 추천 질문을 정한다
  - 셸 제목 `selectedAgent` 는 `currentConversation?.agentName ?? agents.find(...)?.name` 이다(976행 부근)
  - 머리줄은 「에이전트」 옆에 `{currentAgent?.name ?? "등록된 에이전트가 없어요"}` 를 그린다(1026행 부근)
- `web/src/components/chat/composer.tsx` 는 `agentCode.length === 0` 이면 모델 고르기를 끈다(671행 부근)
- `web/src/components/usage/execution-list.tsx` 의 `UsageExecution` 타입이 `agentCode: string`, `agentName: string` 이고, `execution-card.tsx` 와 `execution-table.tsx` 가 `execution.agentName` 을 제목과 `aria-label` 에, `execution.agentCode` 를 작은 글로 그린다
- 브라우저 검사는 `page.route` 로 응답을 바꿔 끼울 수 있다. `test/browser/agent-lifecycle.spec.ts` 가 본보기다. 대화와 실행 기록은 `test/browser/chat.spec.ts`, `test/browser/usage.spec.ts` 가 다룬다

**근거 문서**: `docs/code-architecture.md` 의 「에이전트 만들기와 지우기」 절(에이전트 행이 없을 때의 표와 그 아래 문단), `docs/flow.md` 의 에이전트 만들기 「갈리는 지점」 표

## 의도 메모

- 「지운 에이전트」 글은 한 곳에 둔다. `web/src/lib/format.ts` 에 `agentLabel(name: string | null): string` 을 더한다. 이 파일이 이미 화면 표시용 함수(`subagentLabel` 등)를 모으고 다른 모듈을 불러오지 않아 `test/unit/` 이 바로 불러올 수 있다
- `chat-panel` 은 상태 경쟁을 고치지 않고 파생 값으로 막는다. `const agentMissing = currentConversation !== undefined && currentConversation.agentCode === null` 을 두고
  - 고른 대화를 넣는 effect 는 `selected.agentCode ?? ""` 로 넣는다
  - `currentAgent` 는 `agentMissing` 이면 `undefined` 다. 사진 단추, 스킬 목록, 스킬 칩, 추천 질문이 모두 꺼진다
  - `Composer` 의 `agentCode` 는 `agentMissing` 이면 `""` 다. 모델 고르기가 꺼진다. 보내기는 서버가 `AGENT_NOT_FOUND` 로 거절하고 지금의 오류 문구가 뜬다. 지운 에이전트의 대화에 보낼 때와 같다
  - 셸 제목은 `currentConversation ? agentLabel(currentConversation.agentName) : agents.find((agent) => agent.code === agentCode)?.name`
  - 머리줄은 `agentMissing` 이면 `agentLabel(null)`, 아니면 지금 그대로다
- 실행 기록의 카드와 표는 제목과 `aria-label` 에 `agentLabel(execution.agentName)` 을 쓰고, `agentCode` 가 null 이면 작은 글 줄을 그리지 않는다

## 작업 항목

1. `format.ts` 에 `agentLabel` 을 더한다
2. `Conversation` 타입의 두 칸을 `string | null` 로 바꾸고 `chat-panel.tsx` 를 위 의도 메모대로 맞춘다
3. `UsageExecution` 타입의 두 칸을 `string | null` 로 바꾸고 카드와 표를 맞춘다
4. `test/unit/agent-label.test.ts` 를 더한다. 이름이 있으면 그 이름, null 이면 「지운 에이전트」 다. 다른 파일처럼 `../../web/src/lib/format.ts` 를 불러온다
5. `test/browser/missing-agent.spec.ts` 를 더한다. `page.route` 로 응답을 바꿔 끼운다
   - `/api/chat/conversations` 목록에 `agentCode`, `agentName` 이 null 인 대화 한 줄을 넣고 그 대화(`/chat/<id>`)를 연다. 메시지 목록과 도는 turn 응답도 필요한 만큼 바꿔 끼운다. 대화 화면이 읽는 이 셋은 모두 브라우저의 fetch 라 바꿔 끼울 수 있다
   - 「지운 에이전트」 가 보이는 곳은 폭마다 다르다. desktop 은 대화 머리줄에, mobile 은 셸 윗줄의 제목에 보인다. 대화를 열면 머리줄은 mobile 에서 `hidden md:flex` 로 숨는다(`shell/app-shell.tsx` 의 `md:hidden` 윗줄이 대신 제목을 보인다). `testInfo.project.name` 으로 나눈다(`test/browser/usage.spec.ts` 본보기)
   - 두 폭 모두 모델 고르기 단추(`data-testid="model-picker"`)가 꺼져 있고 사진 단추가 없다
   - 실행 기록은 브라우저 검사에 넣지 않는다. 사용량 화면은 서버 컴포넌트(`web/src/app/usage/page.tsx`)가 실행 기록을 읽어 `page.route` 가 닿지 않고, 브라우저 검사 backend 에는 에이전트 행을 없앨 API 가 없다. 카드와 표의 제목은 `agentLabel` 을 쓰므로 4번의 단위 테스트가 그 글을 확인한다
   - 단추와 영역은 같은 화면을 다루는 기존 spec 의 찾는 방법을 따른다

## 검증

AGENTS.md 「확인」 절을 적힌 순서대로 모두 돌린다.
브라우저 검사는 한 번에 하나만 돈다. 돌리기 전에 `ps -ax | grep -E "playwright test|standalone/server.js"` 로 다른 검사가 없는지 보고, 있으면 끝날 때까지 기다린다.

```bash
cd backend && ./gradlew test
cd web && pnpm typecheck && pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
```

완료 처리: 검증이 모두 통과하면 `tasks/plan051-conversation-missing-agent/index.json` 의 `status` 를 `completed` 로 바꿔 이 phase 커밋에 담는다.
그 뒤 PR 의 마지막 커밋으로 `tasks/plan051-conversation-missing-agent/` 를 지운다.
오래 남을 결정은 이미 `docs/code-architecture.md`, `docs/flow.md`, `docs/data-schema.md` 에 있다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/lib/format.ts` | 수정 |
| `web/src/components/shell/conversations-provider.tsx` | 수정 |
| `web/src/components/chat-panel.tsx` | 수정 |
| `web/src/components/usage/execution-list.tsx` | 수정 |
| `web/src/components/usage/execution-card.tsx` | 수정 |
| `web/src/components/usage/execution-table.tsx` | 수정 |
| `test/unit/agent-label.test.ts` | 신규 |
| `test/browser/missing-agent.spec.ts` | 신규 |
| `tasks/plan051-conversation-missing-agent/index.json` | 수정 |
