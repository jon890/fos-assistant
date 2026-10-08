# Phase 04. 답 아래 「참고한 기억」 화면

**Execution profile**: standard

## 목표

대화의 답 아래에 「참고한 기억 N개」 접힌 줄을 둔다. 펼치면 제목과 출처와 기억 화면 링크가 보인다.
관리자 실행 상세의 출처 라벨에 프로필 구역을 더한다.

**범위 외**: 서버 API(phase 03). 기억 화면(`/memory`) 자체는 바꾸지 않는다.

## 컨텍스트

- 화면 계약은 `docs/frontend/chat.md` 의 「참고한 기억」 이 갖는다. 때마다 그리는 것, 출처 문구(「항상」, 「기억한 사실」, 「찾아 읽음」, 그룹이면 「그룹」), 링크 문구 「기억 화면에서 고치기」 가 거기 있다.
- API 응답 모양은 `docs/backend/memory.md` 의 「답마다 참고한 기억」 의 JSON 예시다.
- 따를 패턴(같은 일을 기억 기록이 이미 한다)
  - 서버 라우트: `web/src/app/api/chat/conversations/[conversationId]/memory-captures/route.ts`
  - API 함수: `web/src/lib/memory-capture-api.ts` 의 `readMemoryCaptures`(`memoryRequest` 사용)
  - 훅: `web/src/components/chat/use-memory-captures.ts`. 다른 대화의 목록을 돌려주지 않고, 읽지 못하면 앞 목록을 둔다
  - 다시 읽는 때: `web/src/components/chat/use-conversation-session-state.ts` 의 `memoryCaptureKey`. 같은 값을 쓴다
  - 전달: `web/src/components/chat/conversation-session-view.tsx` 가 `memoryCaptures` 를 `MessageList` 로 넘긴다
  - 그리기: `web/src/components/chat/message-list.tsx` 의 `MemoryCaptureList` 형제 `<li>`(`turn.role === "ASSISTANT" && typeof turn.executionId === "number"` 일 때, `executionId` 로 거름)
  - 브라우저 시험: `test/browser/memory-capture.spec.ts` 의 `createConversation`, `holdEvents`, `page.route` 로 응답 흉내
- 화면 문구와 색은 `web/CLAUDE.md`(해요체, 테마 토큰, 인라인 스타일 금지)를 따른다. 제목은 평문으로만 그린다(ADR-009). 링크는 `next/link` 에 `prefetch={false}` 를 준다(줄 수가 정해지지 않은 목록의 링크).
- 관리자 실행 상세: `web/src/components/execution/execution-detail.tsx` 의 `CONTEXT_SOURCE_LABELS`.

**근거 문서**: `docs/frontend/chat.md` 의 「참고한 기억」, `docs/backend/memory.md` 의 「답마다 참고한 기억」, `docs/adr/ADR-20261008-memory-profile.md`

## 의도 메모

- 처음에는 접어 둔다. 답마다 목록이 펼쳐져 있으면 대화를 읽기 어렵다.
- 「쓴」 이 아니라 「참고한」 이다. 실렸어도 답에 쓰지 않은 항목이 섞인다.
- 기억 기록 훅과 합치지 않는다. 두 목록은 바뀌는 때와 오류 처리가 다르고, 합치면 한쪽 실패가 다른 쪽을 지운다.

## 작업 항목

### 1. `web/src/app/api/chat/conversations/[conversationId]/memory-uses/route.ts`

`memory-captures/route.ts` 와 같은 모양으로 `/api/v1/chat/conversations/${conversationId}/memory-uses` 를 부른다.

### 2. `web/src/lib/memory-use-api.ts`

타입 `MemoryUse = { executionId: number; memoryId: number; title: string; scope: "USER" | "GROUP"; via: "ALWAYS" | "PROFILE" | "READ" }` 와 `readMemoryUses(conversationId)`. 실패 문구는 「참고한 기억을 읽지 못했어요.」.

### 3. `web/src/components/chat/use-memory-uses.ts`

`useMemoryUses(conversationId, refreshKey)` 가 `{ uses }` 를 돌려준다. `use-memory-captures.ts` 에서 `changed` 를 뺀 모양이다.

### 4. `web/src/components/chat/memory-use-list.tsx`

`MemoryUseList({ uses }: { uses: MemoryUse[] })`. 비었으면 `null`.
`<li data-testid="memory-uses" className="-mt-4 ... pl-10">`(기억 기록 줄과 같은 들여쓰기) 안에 단추 「참고한 기억 N개」(`aria-expanded`, `data-testid="memory-uses-toggle"`). 펼치면 `<ul>` 에 항목마다 제목과 출처 문구, 끝에 `/memory` 링크 「기억 화면에서 고치기」.

### 5. 연결

- `use-conversation-session-state.ts`: `const memoryUses = useMemoryUses(conversationId, memoryCaptureKey);` 를 더하고 돌려준다.
- `conversation-session-view.tsx`: `memoryUses={memoryUses.uses}` 를 `MessageList` 로 넘긴다.
- `message-list.tsx`: prop `memoryUses?: MemoryUse[]`(기본 `[]`)을 받아 `MemoryCaptureList` 바로 뒤에 `<MemoryUseList uses={memoryUses.filter((use) => use.executionId === turn.executionId)} />` 를 둔다. 스크롤 따라가기에 쓰는 `capturesVersion` 처럼 펼친 높이가 바뀌어도 사용자가 연 것이므로 따라가기를 바꾸지 않는다.

### 6. `execution-detail.tsx`

`CONTEXT_SOURCE_LABELS` 에 `MEMORY_PROFILE: "기억(프로필)"` 을 `MEMORY_ALWAYS` 와 `MEMORY_INDEX` 사이에 더한다.

### 7. `test/browser/memory-use.spec.ts`

`memory-capture.spec.ts` 처럼 대화를 만들고 `**/api/chat/conversations/${conversationId}/memory-uses` 를 흉내 낸다.

- 답의 실행 번호로 세 줄(`PROFILE`, `ALWAYS` 그룹, `READ`)을 주면 「참고한 기억 3개」 단추가 보이고 `aria-expanded="false"` 다. 누르면 세 제목과 「기억한 사실」, 「항상」, 「그룹」, 「찾아 읽음」, 「기억 화면에서 고치기」 링크(`href="/memory"`)가 보인다.
- 다른 실행 번호의 줄만 있으면 그 답 아래 `memory-uses` 가 없다.
- 응답이 500 이어도 대화가 보이고 `memory-uses` 가 없다.
- 제목에 `<b>굵게</b>` 를 주면 글자 그대로 보인다.

## 검증

```bash
cd web && pnpm lint && pnpm format:check && pnpm typecheck
cd web && pnpm test:browser memory-use.spec.ts --repeat-each=3 --retries=0
grep -rn 'style={{' web/src/components/chat/memory-use-list.tsx || true
```

기대값: 모두 통과. 마지막 줄은 아무것도 내지 않는다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/app/api/chat/conversations/[conversationId]/memory-uses/route.ts` | 신규 |
| `web/src/lib/memory-use-api.ts` | 신규 |
| `web/src/components/chat/use-memory-uses.ts` | 신규 |
| `web/src/components/chat/memory-use-list.tsx` | 신규 |
| `web/src/components/chat/use-conversation-session-state.ts` | 수정 |
| `web/src/components/chat/conversation-session-view.tsx` | 수정 |
| `web/src/components/chat/message-list.tsx` | 수정 |
| `web/src/components/execution/execution-detail.tsx` | 수정 |
| `test/browser/memory-use.spec.ts` | 신규 |
