# Phase 03. 점검 대화의 발견 반응 단추

**Execution profile**: standard

## 목표

점검 대화의 살펴보기 답 아래에 「새로 알릴 것」 발견마다 제목과 「받아들임」, 「나중에」, 「관심 없음」 단추를 둔다.

**범위 외**: backend(phase 01, 02). 지금 화면(`/now`)은 바꾸지 않는다.

## 컨텍스트

**근거 문서**: `docs/frontend/chat.md` 의 「점검 대화」 와 「발견 반응」, `docs/backend/proactive-check.md` 의 「발견 반응」.

- 같은 자리 방식의 본보기는 기억 기록이다: `web/src/lib/memory-capture-api.ts`, `web/src/components/chat/use-memory-captures.ts`, `web/src/components/chat/memory-capture-list.tsx`, `web/src/components/chat/message-list.tsx` 의 `MemoryCaptureList` 자리(답의 `turn.executionId` 와 같은 것만 그린다), `web/src/components/chat/conversation-session-view.tsx` 의 `memoryCaptures` 연결.
- 서버 라우트 본보기: `web/src/app/api/chat/conversations/[conversationId]/memory-captures/route.ts`, `web/src/app/api/memory-captures/[id]/undo/route.ts`. 브라우저는 Control Plane 을 직접 부르지 않는다(`web/CLAUDE.md`).
- 점검 대화 여부는 `currentConversation?.purpose === "CHECK"` 다(`conversation-session-view.tsx`).
- 응답 모양: `{ dismissWindowDays: number, findings: { id, checkId, executionId, area, topicKey, title, reaction: "ACCEPTED" | "POSTPONED" | "DISMISSED" | null }[] }`.

## 의도 메모

- 화면 문구는 해요체다. 제목은 질문형으로 쓰지 않는다.
- 색은 테마 토큰과 기존 `Button` 변형만 쓴다. 눌린 단추는 `aria-pressed="true"` 와 `variant="default"`, 나머지는 `variant="outline"`.
- 읽지 못해도 대화를 막지 않는다. 앞 목록을 둔다.

## 작업 항목

### 1. `web/src/lib/check-finding-api.ts` 신규

`CheckFinding`, `FindingReaction` 타입과 `readCheckFindings(conversationId)`, `reactToFinding(id, reaction)`. 결과 모양은 `memory-capture-api.ts` 의 `{ ok, data } | { ok: false, message }` 를 따른다.

### 2. 서버 라우트 둘 신규

- `web/src/app/api/chat/conversations/[conversationId]/check-findings/route.ts`: `GET`. `isConversationId` 로 검사하고 `/api/v1/chat/conversations/{id}/check-findings` 를 부른다.
- `web/src/app/api/check-findings/[id]/reaction/route.ts`: `PUT`. 숫자 번호만 받고 본문의 `reaction` 을 그대로 넘긴다. 204 는 204 로 돌려준다.

### 3. `web/src/components/chat/use-check-findings.ts` 신규

`useMemoryCaptures` 와 같은 모양. `enabled` 가 거짓이면 부르지 않고 빈 목록이다. `changed()` 가 다시 읽는다.

### 4. `web/src/components/chat/check-finding-reactions.tsx` 신규

`CheckFindingReactions({ findings, dismissWindowDays, onChanged })`. `findings` 가 비면 아무것도 그리지 않는다.
`<section aria-label="발견 반응" data-testid="check-findings">` 안에 발견마다 제목과 단추 셋(「받아들임」, 「나중에」, 「관심 없음」). 누르는 동안 그 줄의 단추를 끄고, 실패하면 「반응을 남기지 못했어요. 잠시 뒤 다시 눌러 주세요.」 를 그 줄 아래에 보인다. 목록 아래에 「관심 없음을 고른 주제는 {dismissWindowDays}일 동안 다시 알리지 않아요.」.

### 5. `message-list.tsx`, `conversation-session-view.tsx`

`MessageList` 에 `checkFindings`, `dismissWindowDays`, `onCheckFindingsChanged` 를 더하고 `MemoryCaptureList` 자리 위에 같은 조건(답, 숫자 `executionId`)으로 그 실행의 발견만 그린다. 화면 갱신 키(`contentVersion`)에 발견 반응도 넣는다.
`conversation-session-view.tsx` 가 `purpose === "CHECK"` 일 때 `useCheckFindings` 를 켠다. 이력이 바뀌면(`turns.length`) 다시 읽는다.

### 6. 이 phase 를 검증하는 `test/browser/proactive-check.spec.ts`

새 시험 「점검 대화의 발견에 관심 없음을 누르면 눌린 상태가 남고 다시 열어도 그대로다」: `prepare(page)` 뒤 에이전트 화면에서 「지금 살펴보기」 를 눌러 점검 대화로 가고, `check-findings` 안의 「관심 없음」 을 `clickAndWaitForResponse` 로 눌러 PUT 204 를 기다린 뒤 `aria-pressed="true"` 를 단언한다. 새로 고친 뒤에도 같은지 보고, 안내 문구의 `일 동안 다시 알리지 않아요` 를 본다. `finally` 에서 `restore(page)`.

## 검증

```bash
cd web && pnpm lint && pnpm format:check && pnpm typecheck
cd web && pnpm test:browser proactive-check.spec.ts
```

기대값: 모두 통과.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/lib/check-finding-api.ts` | 신규 |
| `web/src/app/api/chat/conversations/[conversationId]/check-findings/route.ts` | 신규 |
| `web/src/app/api/check-findings/[id]/reaction/route.ts` | 신규 |
| `web/src/components/chat/use-check-findings.ts` | 신규 |
| `web/src/components/chat/check-finding-reactions.tsx` | 신규 |
| `web/src/components/chat/message-list.tsx` | 수정 |
| `web/src/components/chat/conversation-session-view.tsx` | 수정 |
| `test/browser/proactive-check.spec.ts` | 수정 |
