# Phase 02. 화면이 이름 없는 에이전트를 「지운 에이전트」 로 그린다

**Execution profile**: fast

## 목표

phase 01 뒤로 대화 목록과 실행 기록의 `agentCode`, `agentName` 이 null 일 수 있다.
화면의 타입을 그에 맞추고 null 이름을 「지운 에이전트」 로 그린다. 대화 화면이 null 코드로 깨지지 않게 한다.

**범위 외**: 지운 에이전트(`deleted_at` 있음)의 대화 표시 방식 변경. 지금처럼 실제 이름으로 보인다.

## 컨텍스트

- `web/src/components/shell/conversations-provider.tsx` 의 `Conversation` 타입이 `agentCode: string`, `agentName: string` 이다
- `web/src/components/chat-panel.tsx` 가 고른 대화의 `agentCode` 를 `setAgentCode` 로 상태에 넣는다. 상태는 `string` 이고 `agentCode.length` 를 여러 곳이 읽는다(`composer.tsx` 의 모델 고르기 비활성 조건 포함). 빈 글이면 모델 고르기가 꺼지고 보내기는 서버가 `AGENT_NOT_FOUND` 로 거절한다. 지운 에이전트의 대화에 보낼 때와 같은 결과다
- 같은 파일의 `selectedAgent` 가 `currentConversation?.agentName` 을 셸 제목으로 쓴다
- `web/src/components/usage/execution-list.tsx` 의 `UsageExecution` 타입이 `agentCode: string`, `agentName: string` 이고, `execution-card.tsx` 와 `execution-table.tsx` 가 `execution.agentName` 을 제목과 `aria-label` 에, `execution.agentCode` 를 작은 글로 그린다
- 실행 나무(`execution/execution-node.tsx`, `execution/execution-detail.tsx`)는 이미 null 을 받아 다른 값으로 대신한다. 고치지 않는다

**근거 문서**: `docs/code-architecture.md` 의 「에이전트 만들기와 지우기」 절(「화면은 null 이름을 「지운 에이전트」 로 그린다」), `docs/flow.md` 의 에이전트 만들기 「갈리는 지점」 표

## 의도 메모

- 「지운 에이전트」 글은 한 곳에 두고 두 화면이 함께 쓴다. `web/src/lib/format.ts` 에 `agentLabel(name: string | null): string` 을 더한다. 이 파일이 이미 화면 표시용 함수(`subagentLabel` 등)를 모으고 다른 모듈을 불러오지 않아 `test/unit/` 이 바로 불러올 수 있다
- `chat-panel` 은 `selected.agentCode ?? ""` 로 넣는다. 상태 타입은 바꾸지 않는다
- 셸 제목은 대화가 있고 이름이 null 이면 「지운 에이전트」 다
- 실행 기록의 `agentCode` 가 null 이면 작은 글 줄을 그리지 않는다

## 작업 항목

1. `format.ts` 에 `agentLabel` 을 더한다
2. `Conversation` 타입의 두 칸을 `string | null` 로 바꾸고 `chat-panel.tsx` 를 맞춘다
3. `UsageExecution` 타입의 두 칸을 `string | null` 로 바꾸고 카드와 표를 맞춘다
4. `test/unit/agent-label.test.ts` 를 더한다. 이름이 있으면 그 이름, null 이면 「지운 에이전트」 다. 다른 파일처럼 `../../web/src/lib/format.ts` 를 불러온다

## 검증

AGENTS.md 「확인」 절을 적힌 순서대로 모두 돌린다.

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
| `tasks/plan051-conversation-missing-agent/index.json` | 수정 |
