# Phase 06. 결과 도착 줄의 화면과 기능 문서

**Execution profile**: standard

## 목표

내장 위임 결과의 알림 줄을 대화 에이전트의 이름표와 함께 그리고, 기능 파일에 「Hermes 내장 위임 결과」 절을 더한다.

**범위 외**: 서버 동작(phase 1~5), fos-ctx 의 hook(다음 PR).

## 컨텍스트

**요구사항**: REQ14(스펙 DECISION33 의 줄이 보이고 새로 고쳐도 같다)

**근거 문서**: `docs/features/agent-team/spec.md`(DECISION33), `docs/features/agent-team/design.md` 의 「PR6 내장 위임 결과」 → 「화면」, 「이력 API」, 「결정 기록 연결」

- 이력 API(phase 5)와 대화 단위 SSE 의 `system` 사건(phase 3)이 내장 위임 결과의 알림 줄에 `nativeResult: { status: "SUCCEEDED" | "FAILED" }` 를 싣는다. 다른 줄에는 없거나 null 이다.
- 알림 줄 부품: `web/src/components/chat/system-message.tsx`. 위임 결과 줄(`deliveredResults`)은 `ResultArrivalLines` 로, 그 밖은 저장된 글로 그린다.
- 대화 에이전트의 이름표: `web/src/components/agent/agent-badge.tsx` 의 `AgentBadge`(`data-testid="agent-badge"`)와 `useAnswerAgent()`(대화의 에이전트를 주는 context). 본보기는 `web/src/components/chat/assistant-row.tsx`.
- 타입: `web/src/components/chat/message-types.ts` 의 `Turn`, `web/src/lib/chat-event.ts` 의 사건 타입, `web/src/components/chat/use-conversation-events.ts` 가 `system` 사건을 `Turn` 으로 옮긴다(`deliveredResults` 를 옮기는 자리).
- 브라우저 테스트 본보기: `web/test/browser/chat-delegation-wake.spec.ts`(가짜 API 로 이력과 SSE 를 꾸민다).
- 브라우저 테스트는 worker 지침대로 무거운 검사 대기열로 감싸 돌린다.
- 스펙 DECISION36: 이 PR 이 운영 코드 크기 상한(`scripts/pr/pr-size.mjs`)을 넘으면 이 phase 를 따로 PR 로 나누고 코디네이터에게 알린다. phase 5 까지 커밋한 뒤 `node scripts/pr/pr-size.mjs origin/main` 으로 먼저 잰다.

## 의도 메모

- 글은 서버가 저장한 것을 그대로 쓴다. 화면이 글을 다시 만들지 않는다(설계 「화면」). 상태는 접근성 이름과 테스트 표지에만 쓴다.
- 기한과 중지 알림 줄은 `nativeResult` 가 없어 지금처럼 글만 보인다.
- 「결과 다시 전달」 은 지금 자리 그대로다.

## 작업 항목

### 1. 타입과 사건 옮기기

- `chat-event.ts`: `nativeResult?: { status: "SUCCEEDED" | "FAILED" } | null`.
- `message-types.ts`: `Turn` 에 `nativeResult?: { status: "SUCCEEDED" | "FAILED" }`.
- `use-conversation-events.ts`: `system` 사건의 `nativeResult` 를 `Turn` 에 옮긴다(`deliveredResults` 옆).
- 이력 응답을 `Turn` 으로 옮기는 자리가 칸을 고르면 같은 칸을 더한다(`git grep -n "deliveredResults" web/src`).

### 2. `system-message.tsx`

`turn.nativeResult` 가 있으면 `useAnswerAgent()` 의 에이전트로 `AgentBadge`(작은 크기)를 앞에 두고 저장된 글을 한 줄로 그린다. 줄에 `data-testid="native-result-line"` 과 `data-status={status}` 를 단다. 파일 맨 위 설명에 이 줄을 더한다.

### 3. 기능 파일 `docs/features/agent-team.md`

「대화 안의 협업」 뒤에 「Hermes 내장 위임 결과」 절을 더한다. 사용자가 보는 것만 짧게 적는다: 대화 에이전트가 맡긴 하위 작업이 끝나면 이름표와 함께 결과 줄이 남고 이어 답이 붙는다, 실패 줄, 1시간 기한 알림, 중지한 답의 알림, 설정 `assistant.delegation-wake.enabled` 하나로 함께 끄고 켠다. 근거는 `docs/adr/ADR-20261117-native-delegation-wake.md` 와 설계의 「PR6 내장 위임 결과」 로 링크한다. 기능 파일에서는 `../adr/ADR-20261117-native-delegation-wake.md` 와 `agent-team/design.md` 가 상대 경로다.

### 4. 테스트

`web/test/browser/chat-delegation-wake.spec.ts` 에 두 사례를 더한다.

- 이력의 알림 줄에 `nativeResult: {status: "SUCCEEDED"}` 가 있으면 `native-result-line` 안에 `agent-badge` 와 저장된 글이 보이고, `nativeResult` 가 없는 알림 줄은 이름표 없이 글만 보인다.
- 대화 단위 SSE 의 `system` 사건이 `nativeResult: {status: "FAILED"}` 를 실으면 이력을 다시 읽기 전에도 같은 줄이 `data-status="FAILED"` 로 보인다.

## 검증

```bash
cd web && npm run typecheck
cd web && npm run lint
cd web && npm run format:check
cd web && npx playwright test --config test/browser/playwright.config.ts test/browser/chat-delegation-wake.spec.ts
cd backend && ./gradlew test --tests '*NativeDelegationDeliveryTest' --tests '*NativeResultHistoryTest' --tests '*NativeDelegationSweepTest' --tests '*SubagentSessionEndpointTest'
node --test test/repo/doc-files.test.ts test/repo/doc-links.test.ts test/repo/doc-code-references.test.ts test/repo/agent-badge.test.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/lib/chat-event.ts` | 수정 |
| `web/src/components/chat/message-types.ts` | 수정 |
| `web/src/components/chat/use-conversation-events.ts` | 수정 |
| `web/src/components/chat/system-message.tsx` | 수정 |
| `web/test/browser/chat-delegation-wake.spec.ts` | 수정 |
| `docs/features/agent-team.md` | 수정 |
