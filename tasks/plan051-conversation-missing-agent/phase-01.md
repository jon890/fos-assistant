# Phase 01. 에이전트 행이 없어도 목록이 실패하지 않게 한다

**Execution profile**: standard

## 목표

대화와 실행이 가리키는 에이전트 행이 없을 때 목록 경로는 그 줄의 에이전트 칸만 비우고 나머지 줄을 돌려준다.
한 대화에 보내는 경로는 지금처럼 `AGENT_NOT_FOUND` 로 답하되, `agent_id` 가 NULL 이어도 500 이 되지 않게 한다.

**범위 외**: 스키마 변경(FK 추가 포함), 행이 사라진 원인 조사, 화면 변경(phase 02).

## 컨텍스트

- `AgentService.requireById(Long)` 는 `agents.findById(id)` 로 읽고 없으면 `AGENT_NOT_FOUND` 를 던진다. 지운 에이전트(`deleted_at` 있음)도 돌려준다. `id` 가 null 이면 Spring Data 가 `IllegalArgumentException` 계열을 던져 500 이 된다
- `requireById` 를 부르는 곳은 다섯이다
  - `chat/presentation/ChatController.viewOf`: 대화 목록(`GET /api/v1/chat/conversations`)이 대화마다 부른다. 이름 바꾸기와 모델 고르기도 이 메서드로 한 줄을 돌려준다
  - `chat/application/ChatService.route`: 이어 쓰는 대화에 보낼 때
  - `chat/application/ChatService.routeExisting`: 다시 생성할 때
  - `chat/application/ChatService.stop`: 뿌리 turn 을 멈추며 도는 자식 실행의 run 을 함께 멈출 때, 자식마다 부른다
  - `usage/application/ExecutionTreeService.node`: 실행 나무의 노드마다 부른다. `agentId` 가 null 이면 이미 에이전트 없이 그린다
  - `usage/presentation/UsageController.myExecutions`: 내 실행 기록(`GET /api/v1/usage/executions`)이 줄마다 부른다
- `skill/application/SkillUsageQuery.byUser` 는 `findAllById` 로 한 번에 읽고 없는 에이전트의 묶음을 빼고 있다. 고치지 않는다
- 응답 레코드: `ChatDtos.ConversationView(id, title, agentCode, agentName, ...)`, `UsageDtos.ExecutionView.from(execution, agent, ...)` 는 `agent.code()`, `agent.name()` 을 바로 읽는다
- 테스트에서 에이전트 행을 없애려면 `AgentRepository.deleteById` 를 쓴다. `conversation.agent_id` 와 `agent_execution.agent_id` 에 FK 가 없어 지워진다

**근거 문서**: `docs/code-architecture.md` 의 「에이전트 만들기와 지우기」 절(에이전트 행이 없을 때의 표), `docs/flow.md` 의 에이전트 만들기 「갈리는 지점」 표, `docs/data-schema.md` 의 「conversation」 절

## 의도 메모

- 목록은 줄을 빼지 않는다. 대화를 빼면 사용자가 그 대화를 열지도 지우지도 못한다. 실행 기록을 빼면 쓴 돈이 목록에서 사라진다
- 목록 경로는 에이전트를 줄마다 읽지 않는다. `AgentService.byIds(Collection<Long>)` 가 null 을 빼고 `findAllById` 로 한 번에 읽어 `Map<Long, Agent>` 를 돌려준다. 빈 목록이면 질의하지 않는다
- 한 번에 한 에이전트를 읽는 곳은 `AgentService.findById(Long)` 이 `Optional<Agent>` 를 돌려준다. `id` 가 null 이면 빈 값이다. `requireById` 는 이 메서드 위에서 `AGENT_NOT_FOUND` 를 던지게 바꿔 null 도 같은 오류가 되게 한다
- `ChatService.stop` 은 에이전트를 찾지 못한 자식을 `log.warn` 으로 실행 번호와 에이전트 번호를 남기고 건너뛴다. 뿌리 turn 의 중지를 오류로 끝내면 이미 켠 중지 표시와 어긋난다
- `ExecutionTreeService.node` 는 `findById` 로 바꿔 없으면 에이전트 칸을 null 로 둔다. 나무의 노드 수만큼 읽는 것은 지금과 같고, 이 phase 에서 한 번에 읽도록 바꾸지 않는다

## 작업 항목

### 1. `AgentService`

`findById(Long)` 와 `byIds(Collection<Long>)` 를 더하고 `requireById` 를 `findById` 위로 옮긴다. `requireById` 의 Javadoc 에 id 가 null 이어도 `AGENT_NOT_FOUND` 라고 적는다.

### 2. 대화 목록과 한 줄 응답

- `ChatController.conversations()` 는 목록의 `agentId` 로 `byIds` 를 한 번 부르고 줄마다 그 맵에서 꺼낸다
- `viewOf` 는 에이전트가 없으면 `agentCode`, `agentName` 을 null 로 낸다. 이름 바꾸기와 모델 고르기도 같은 `viewOf` 를 쓴다(한 건이면 `findById`)
- `ConversationView` 의 두 칸이 null 일 수 있다고 레코드 위 주석에 적는다

### 3. 보내기와 다시 생성

`ChatService.route` 와 `routeExisting` 은 `requireById` 를 그대로 쓴다. 1번의 변경으로 `agent_id` 가 null 인 대화도 `AGENT_NOT_FOUND` 가 된다. 코드 변경이 없으면 이 파일은 3번에서 고치지 않는다.

### 4. 중지

`ChatService.stop` 의 자식 순회에서 `findById` 로 읽고 없으면 로그를 남기고 `continue` 한다.

### 5. 사용량

- `UsageController.myExecutions` 는 `byIds` 로 한 번에 읽는다
- `UsageDtos.ExecutionView.from` 은 에이전트가 null 이면 두 칸을 null 로 낸다
- `ExecutionTreeService.node` 는 `findById` 로 읽는다

### 6. 테스트

- `backend/src/test/java/com/bifos/assistant/chat/ConversationMissingAgentTest.java`(신규): `ConversationPublicIdTest` 처럼 `ChatController` 를 MockMvc 로 세운다
  - 두 에이전트로 대화를 하나씩 만들고 한 에이전트 행을 `deleteById` 로 없앤다. `GET /api/v1/chat/conversations` 가 200 이고 두 대화가 모두 오며, 행이 없는 쪽의 `agentCode`, `agentName` 이 null 이고 다른 쪽은 그대로다
  - 행이 없는 대화의 이름을 바꾸면 200 이고 돌려준 줄의 `agentCode` 가 null 이다
  - 행이 없는 대화에 `POST /api/v1/chat/messages` 로 보내면 404 `AGENT_NOT_FOUND` 다
  - 행이 없는 대화의 메시지 목록(`GET .../messages`)은 200 이다
- `backend/src/test/java/com/bifos/assistant/usage/UsageControllerTest.java`: 실행 둘 중 하나의 에이전트 행을 없애도 `myExecutions` 가 두 줄을 내고, 없는 쪽의 `agentCode`, `agentName` 이 null 이다. 이 테스트 파일이 컨트롤러를 부르는 방식을 따른다
- `backend/src/test/java/com/bifos/assistant/usage/ExecutionTreeServiceTest.java`: 자식 실행의 에이전트 행을 없애도 나무가 나오고 그 노드의 에이전트 칸이 null 이다
- `ChatService.stop` 의 건너뛰기는 새 테스트를 더하지 않는다. 자식이 있는 중지는 `ResearchAndBuildFlowTest` 가 이미 돌린다

## 검증

AGENTS.md 「확인」 절을 적힌 순서대로 돌린다. 이 phase 에서는 backend 만 바뀌므로 먼저 아래를 통과시킨다.

```bash
cd backend && ./gradlew test
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/presentation/UsageController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/presentation/UsageDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionTreeService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ConversationMissingAgentTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/usage/UsageControllerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/ExecutionTreeServiceTest.java` | 수정 |
| `tasks/plan051-conversation-missing-agent/index.json` | 수정 |
