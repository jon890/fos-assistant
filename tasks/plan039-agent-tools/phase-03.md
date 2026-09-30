# Phase 03. `agent_status` 가 같은 대화의 앞 turn 에서 맡긴 실행도 답한다

**Execution profile**: deep

## 목표

`agent_status` 가 물을 수 있는 범위를 실행 나무에서 같은 대화로 넓힌다.

- 조건: 요청자의 실행이고, `delegation_key` 가 있고, origin 실행에 대화가 있으면 **같은 대화**(`conversation_id` 가 같음)다
- origin 실행에 대화가 없으면 지금처럼 같은 실행 나무(`treeRootId()` 가 같음)로 판정한다
- 어느 쪽이든 아니면 지금과 같은 `NOT_FOUND` 다. 같은 사용자의 다른 대화도 `NOT_FOUND` 다

까닭: 대화의 turn 마다 뿌리 실행이 새로 생긴다. 앞 turn 에서 맡긴 실행을 다음 turn 의 Chief 가 물으면 나무 규칙으로는 `NOT_FOUND` 다. 기다리지 않는 위임은 결과를 뒤 turn 에서 가져오는 것이 기본 흐름이다.

**범위 외**: 위임 시작(phase 04), 중지(phase 05).

## 컨텍스트

- 판정은 `AgentDelegationService.status(CurrentUser, AgentExecution origin, Long)` 한 곳이다(phase 02 뒤의 모양)
- 운영에서는 대화 없는 origin 이 생기지 않는다. 대화 turn 은 늘 대화를 갖고, 흐름 자식과 위임 자식은 부모의 대화를 잇는다. `hermes_session_id` 를 적지 않는 실행(추천 질문, Memory 제안)은 origin 으로 골라지지 않는다. 대화 없는 경우의 나무 규칙은 방어용이다
- **테스트는 반대다.** 기존 `McpAgentToolsTest` 의 origin 은 모두 대화 없이 만들어진다(`McpCallSigner.running(executions, userId, null, …)`). `delegated()` 도 `conversationId` 를 넣지 않는다. 그래서 기존 검사는 고치지 않아도 나무 규칙으로 통과하고 새 규칙은 검증하지 못한다
- `agent_execution.conversation_id` 에는 외래 키가 없어 테스트에서 아무 번호나 넣어도 된다

**근거 문서**: ADR-017 「도구 넷과 한도」 의 `agent_status` 범위(2026-09-30), `docs/flow.md` 「다른 에이전트에게 맡길 때」 의 「갈리는 지점」, `docs/hermes/tools-and-skills.md` 의 `agent_status` 설명. 셋은 이미 새 규칙으로 적혀 있다.

## 작업 항목

### 1. 판정

`AgentDelegationService.status` 의 나무 비교를 위 규칙으로 바꾼다. 판정을 private 메서드 하나(예: `canQuery(user, origin, execution)`)로 두어 phase 05 의 `agent_stop` 이 같은 메서드를 쓰게 한다.

### 2. 테스트 `McpAgentToolsTest`

- 기존 대화 없는 검사(다른 나무 → `NOT_FOUND` 등)는 **대체 규칙(나무)의 검사**로 남긴다. 테스트 이름이나 주석으로 그것이 대화 없는 origin 의 규칙임을 드러낸다
- 새 검사: origin 과 `delegated()` 에 `conversationId` 를 준다
  - 같은 대화, 다른 뿌리(앞 turn)의 위임 실행 → 답한다
  - 다른 대화의 위임 실행(같은 사용자) → `NOT_FOUND`
  - 같은 대화의 위임 아닌 실행(`delegation_key` 없음) → `NOT_FOUND`
  - 같은 대화 번호여도 남의 실행 → `NOT_FOUND`

### 3. 문서

`docs/code-architecture.md` 「다른 에이전트에게 맡기기」 표의 `AgentDelegationService` 줄과 `ChildExecutionRunner` 줄에 적힌 `status` 판정(뿌리 비교)을 새 규칙으로 고친다.

## 검증

```bash
cd backend && ./gradlew test
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentDelegationService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpAgentToolsTest.java` | 수정 |
| `docs/code-architecture.md` | 수정 |
