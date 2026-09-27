# Phase 02. 다른 창에서 도는 turn 을 보이고 끝나면 이력을 다시 읽는다

**Execution profile**: standard

## 목표

대화를 열 때 phase-01 의 경로로 도는 turn 을 묻는다.
돌고 있으면 기다리는 표시와 작업 과정을 보이고, 끝나면 저장된 이력을 다시 읽는다.
이 plan 의 마지막 phase 다.

**범위 외**: 답 조각을 다른 창에서 흘려 보이는 것. 스트리밍은 보낸 창에만 간다.

## 컨텍스트

**근거 문서**: `docs/flow.md` 「다른 창에서 답하는 중일 때」 의 흐름도와 상황 표, 「중지할 때」, 「기다리는 동안 보이는 것」.
`docs/code-architecture.md` 「대화」 의 「경로」 표에 응답 모양 `{ running, executionId, startedAt }` 이 있다.

phase-01 이 `GET /api/v1/chat/conversations/{conversationId}/running` 을 더했다.

계획을 쓸 때의 코드다. 디자인 계획이 끝난 뒤라 줄 번호와 className 은 달라져 있다. 시작할 때 다시 연다.

| 위치 | 지금 모양 |
| --- | --- |
| `web/src/app/api/chat/conversations/[conversationId]/messages/route.ts` | 번호를 `/^\d+$/` 로 보고 `callControlPlane` 으로 넘기는 서버 라우트. 새 라우트의 본보기다 |
| `web/src/components/chat-panel.tsx` | `useEffect(..., [initialConversationId])` 가 대화를 열 때 상태를 비우고 `selectionVersion` 을 올린 뒤 이력을 읽는다. `startNewConversation()` 도 `selectionVersion` 을 올린다. 늦게 온 응답은 `selectionVersion.current === version` 으로 버린다 |
| 같은 파일 | 도는 turn 의 상태는 `sending`, `activity`(`ActivityState`), `currentExecutionId`(ref), `executionId`(state), `stopRequested` 다. `stop()` 은 `currentExecutionId.current` 로 중지를 보낸다. `refreshMessages(id, version)` 이 이력을 다시 읽는다 |
| 같은 파일 | `<Composer running={sending} canStop={executionId !== null && !stopRequested} onStop=...>` |
| `web/src/components/chat/message-list.tsx` | `sending` 이고 작업 과정 항목이 없으면 `WaitingIndicator` 를 그린다 |
| `web/src/components/chat/activity/activity-state.ts` | `fromTree(tree)` 가 실행 나무를 `ActivityItem[]` 로 바꾼다. 마지막 줄에서 `running` 인 항목을 모두 `stopped` 로 바꾼다. 끝난 답을 다시 볼 때를 위한 것이다 |
| `web/src/components/chat/activity/activity-block.tsx` | `/api/usage/executions/${executionId}/tree` 를 `cache: "no-store"` 로 읽는다. 나무를 읽는 본보기다 |
| `test/browser/fixtures.ts` | 가짜 Hermes 조작 `holdNextRun()` 과 `releaseHeldRun()` |

## 의도 메모

- **보는 창과 보낸 창을 구분한다.** 보낸 창은 자기 스트림으로 끝을 알므로 이 조회를 하지 않는다.
  대화를 열 때 한 번 묻고, 도는 turn 이 있을 때만 되풀이한다
- 보는 창의 상태는 기존 상태를 그대로 쓴다. `sending=true`, `activity` 의 시작 시각은 받은 `startedAt` 이다, `currentExecutionId` 와 `executionId` 에 받은 번호를 넣는다.
  그러면 기다리는 표시와 중지 단추가 보낸 창과 같이 동작한다
- 보는 중이라는 것은 별도 상태 하나로 둔다. 입력창 위에 「다른 창에서 답하는 중」 을 보인다.
  입력창은 `running` 으로 이미 잠긴다
- **`fromTree` 에 도는 중인 나무를 위한 선택지를 더한다.** 지금은 `running` 을 `stopped` 로 바꾼다.
  보는 창은 끝나지 않은 나무를 읽으므로 그 바꿈을 건너뛰어야 한다. 기존 호출의 결과는 바뀌지 않아야 한다
- 3초마다 도는 turn 과 나무를 묻는다. `document.hidden` 인 동안은 쉬고, 보이게 되면 곧바로 한 번 묻는다
- 받은 번호가 이전과 다르면 provider 를 넘어간 것이다. 새 번호로 나무를 읽고 중지 대상도 바꾼다
- `running=false` 가 오면 조회를 멈추고 보는 상태를 풀고 `refreshMessages` 를 부른다
- 조회가 세 번 이어 실패하면 보는 상태를 풀고 이력을 다시 읽는다. 오류 문구는 띄우지 않는다
- **조회를 멈추는 자리는 셋이다.** `selectionVersion` 이 바뀔 때, 컴포넌트가 사라질 때, 끝났을 때다.
  타이머는 effect 의 정리 함수에서 지운다. 늦게 온 응답은 `selectionVersion` 으로 버린다
- 보는 창에서 중지를 누르면 기존 `stop()` 을 그대로 쓴다. 끝나는 것은 다음 조회가 안다

## Blocked 조건

- phase-01 의 경로가 없다. `grep -n '/running' backend/src/main/java/com/bifos/assistant/chat/presentation/ChatController.java` 가 아무것도 내지 않는다 → `PHASE_BLOCKED: phase-01 이 끝나지 않았다`

## 작업 항목

### 1. 서버 라우트

`web/src/app/api/chat/conversations/[conversationId]/running/route.ts` 를 `messages/route.ts` 와 같은 모양으로 둔다.

### 2. `fromTree` 의 선택지

두 번째 인자로 끝나지 않은 나무임을 알린다. 그때는 `running` 항목을 그대로 둔다.
`activity-block.tsx` 의 기존 호출은 인자 없이 두어 결과가 같다.

### 3. `chat-panel.tsx` 의 보는 창

- 대화를 여는 effect 에서 이력을 읽은 뒤 도는 turn 을 묻는다
- 돌고 있으면 위 의도 메모대로 상태를 채우고 되풀이 조회를 시작한다
- `Composer` 위에 「다른 창에서 답하는 중」 을 보인다. `data-testid="observing-notice"` 를 붙인다

### 4. 문서

`docs/flow.md` 「다른 창에서 답하는 중일 때」 와 구현이 다르면 문서를 구현에 맞춘다. 다르게 만든 까닭을 커밋 메시지에 적는다.

### 5. 이 phase 를 검증하는 테스트

`test/browser/` 에 새 spec 을 둔다. 한 사용자로 두 page 를 연다.

| 경우 | 기대 |
| --- | --- |
| 첫 page 에서 `holdNextRun()` 뒤 보내고, 둘째 page 로 같은 대화를 연다 | 둘째 page 에 `observing-notice` 와 기다리는 표시. 보내기 단추 대신 중지 단추 |
| 그 상태에서 `releaseHeldRun()` | 둘째 page 에 답이 나타나고 `observing-notice` 가 사라진다 |
| 둘째 page 에서 중지를 누른다 | 두 page 모두 이력에서 「중지됨」 |
| 도는 turn 이 없는 대화를 연다 | `observing-notice` 가 없다. running 조회가 한 번만 간다 |
| 보는 중에 다른 대화로 옮긴다 | 옮긴 뒤 running 조회가 더 가지 않는다 |

조회 횟수는 `page.on("request")` 로 `/running` 요청을 센다.
3초 주기를 기다리는 검사는 `expect.poll` 로 조건을 기다리고 고정 대기를 쓰지 않는다.

`fromTree` 의 선택지는 `test/unit/` 에 검사를 더한다. 선택지가 없으면 `running` 이 `stopped` 가 되고, 있으면 그대로다.

## 검증

```bash
# cwd: 저장소 root
grep -rn "observing-notice" web/src test/browser
node --test 'test/unit/**/*.test.ts'
```

첫째는 화면과 spec 에서 모두 나와야 한다.

AGENTS.md 의 「확인」 절 명령을 적힌 순서대로 모두 돌린다. 이 plan 의 마지막 phase 다.
`pnpm build` 는 `web/AGENTS.md` 의 자리표시자 환경 변수가 필요하다.

새 spec 은 모바일과 데스크톱 폭에서 모두 통과해야 한다.

끝나면 `tasks/plan026-running-other-tab/index.json` 의 이 phase 를 `completed` 로, plan 의 `status` 를 `completed` 로 바꾼다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `web/src/app/api/chat/conversations/[conversationId]/running/route.ts` | 추가 |
| `web/src/components/chat-panel.tsx` | 수정 |
| `web/src/components/chat/activity/activity-state.ts` | 수정 |
| `docs/flow.md` | 구현과 다르면 수정 |
| `test/browser/` 의 새 spec | 추가 |
| `test/unit/` 의 `fromTree` 검사 | 추가 |
