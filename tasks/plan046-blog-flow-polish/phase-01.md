# Phase 01. backend 가 도구 명령 원문을 관리자에게만 보낸다

**Execution profile**: standard

## 목표

도구 사건의 `detail` 을 `ADMIN` 역할에게만 응답에 싣는다. `MEMBER` 역할에게는 `web_search` 와 `vision_analyze` 만 싣고 나머지 도구는 `null` 로 보낸다.
대화 스트림의 `tool` 사건과 실행 나무 조회 두 경로가 같은 판정을 쓴다. 저장은 바꾸지 않는다.

**범위 외**: 도구 이름을 사람 말로 옮기는 것(phase 04). 스키마 변경은 없다.

## 컨텍스트

**근거 문서**: `docs/adr/ADR-038-도구의-명령-원문은-관리자에게만-보내고-사용자에게는-사람-말로-보인다.md`,
`docs/code-architecture.md` 의 「도구 `detail` 을 싣는 대상」 절, `docs/flow.md` 의 「도구를 보이는 말」 절

지금 모양이다. 구현 전에 각 파일을 연다.

- `backend/src/main/java/com/bifos/assistant/shared/auth/CurrentUser.java`: `record CurrentUser(Long id, String email, String displayName, Long groupId, UserRole role)` 와 `isAdmin()`
- `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionEventView.java`: `static ExecutionEventView from(ExecutionEvent event)` 가 `event.detail()` 을 그대로 옮긴다
- `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionTreeService.java`: `of(CurrentUser user, Long executionId)` 가 `node(Branch, Map)` 에서 `ExecutionEventView::from` 으로 사건을 옮긴다. `user` 는 주인 확인에만 쓰인다
- `backend/src/main/java/com/bifos/assistant/usage/domain/ExecutionEventType.java`: `isTool()` 은 `TOOL_STARTED`, `TOOL_COMPLETED` 에서 참이다
- `backend/src/main/java/com/bifos/assistant/chat/application/ChatEvent.java`: 19칸 record. `tool(toolName, detail, phase, durationMs, failed)` 가 `type` 을 `"tool"` 로 만든다
- `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatController.java`: `stream(...)` 과 `regenerate(...)` 가 `streams.open(event -> chat.stream(..., event))` 와 `streams.open(event -> chat.regenerate(user, id, event))` 로 소비자를 넘긴다. 람다 인자 `event` 는 실제로는 `Consumer<ChatEvent>` 다
- 테스트 본보기: `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` 의 `hermesStreams(RunEvent...)` 가 Hermes 사건을 흘리고, `backend/src/test/java/com/bifos/assistant/chat/ConversationPublicIdTest.java` 의 `streamed(RequestBuilder)` 와 `signedIn(CurrentUser)` 가 MockMvc 로 SSE 를 끝까지 읽는다. `backend/src/test/java/com/bifos/assistant/usage/ExecutionTreeServiceTest.java` 는 `UserRole.ADMIN` 인 주인으로 나무를 읽는다

## 의도 메모

- 화면에서 숨기는 것으로 대신하지 않는다. 응답 JSON 에 값이 남으면 개발자 도구로 보인다
- 공개할 도구를 적는다. 숨길 도구를 적으면 새 도구가 기본으로 보인다
- 도구 이름은 전체로 비교한다. `mcp__{서버}__web_search` 처럼 다른 서버가 붙인 같은 이름은 공개하지 않는다
- 도구 사건이 아닌 사건(하위 에이전트 목표, `RUN_FAILED` 코드, `PROVIDER_SWITCHED` 모델)의 `detail` 은 그대로 둔다
- `ChatService` 는 다른 계획도 함께 고치는 파일이다. 판정을 그 안에 넣지 않고 컨트롤러가 넘기는 소비자에서 한다

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/usage/application/ToolDetailPolicy.java` 신규

- `public final class ToolDetailPolicy`, 생성자를 막는다
- `static final Set<String> PUBLIC_TOOLS = Set.of("web_search", "vision_analyze")`
- `public static boolean visibleTo(CurrentUser viewer, String toolName)`: `viewer.isAdmin()` 이면 참. 아니면 `toolName` 이 `PUBLIC_TOOLS` 에 있을 때만 참. `toolName` 이 `null` 이면 거짓
- Javadoc 에 ADR-038 을 적는다

### 2. `ExecutionEventView.from` 이 보는 사람을 받는다

- 시그니처를 `static ExecutionEventView from(ExecutionEvent event, CurrentUser viewer)` 로 바꾼다
- `event.eventType().isTool() && !ToolDetailPolicy.visibleTo(viewer, event.toolName())` 이면 `detail` 을 `null` 로 싣는다
- `ExecutionTreeService.node(...)` 에 `CurrentUser viewer` 를 넘겨 `event -> ExecutionEventView.from(event, viewer)` 로 옮긴다. 자식 노드에도 같은 `viewer` 를 넘긴다

### 3. 대화 스트림의 `tool` 사건

- `ChatEvent` 에 `public ChatEvent forViewer(CurrentUser viewer)` 를 더한다. `"tool".equals(type)` 이고 `!ToolDetailPolicy.visibleTo(viewer, toolName)` 이면 `detail` 만 `null` 로 바꾼 새 record 를, 아니면 `this` 를 돌려준다
- `ChatController.stream` 과 `ChatController.regenerate` 에서 `streams.open(send -> chat.stream(..., event -> send.accept(event.forViewer(user))))` 모양으로 감싼다. 람다 인자 이름을 `send` 로 바꿔 소비자임이 드러나게 한다

### 4. 이 phase 를 검증하는 테스트

- `backend/src/test/java/com/bifos/assistant/usage/ToolDetailPolicyTest.java` 신규
  - `ADMIN` 은 `terminal` 이 참
  - `MEMBER` 는 `terminal`, `read_file`, `mcp__fos_assistant__artifact_write`, `mcp__other__web_search`, `null` 이 거짓
  - `MEMBER` 는 `web_search`, `vision_analyze` 가 참
- `ExecutionTreeServiceTest` 에 둘을 더한다
  - `TOOL_STARTED` 가 `terminal` 과 `detail` `"python3 run.py"`, `web_search` 와 `"제주 날씨"`, `SUBAGENT_STARTED` 가 `"숙소를 찾는다"` 인 실행을 `MEMBER` 주인으로 읽으면 차례로 `null`, `"제주 날씨"`, `"숙소를 찾는다"` 다
  - 같은 실행을 `ADMIN` 주인으로 읽으면 `"python3 run.py"` 가 그대로다
- `backend/src/test/java/com/bifos/assistant/chat/ToolDetailStreamTest.java` 신규. `ConversationPublicIdTest` 의 MockMvc 와 SSE 읽기, `ChatServiceTest` 의 Hermes 사건 흘리기를 본떠 만든다
  - `MEMBER` 로 보낸 turn 에서 `tool` 사건의 `terminal` 은 `detail` 이 없거나 `null` 이고 `web_search` 는 값이 있다
  - `ADMIN` 으로 보낸 turn 에서는 `terminal` 의 `detail` 이 있다
  - 두 turn 모두 저장된 `execution_event.detail` 은 Hermes 가 보낸 값 그대로다

## 검증

```bash
# cwd: backend/
./gradlew test --tests 'com.bifos.assistant.usage.ToolDetailPolicyTest' --tests 'com.bifos.assistant.usage.ExecutionTreeServiceTest' --tests 'com.bifos.assistant.chat.ToolDetailStreamTest'
./gradlew test
```

모두 통과해야 한다.

```bash
# cwd: 저장소 root. 아무것도 나오지 않아야 한다
grep -rn "ExecutionEventView::from" backend/src/main
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/usage/application/ToolDetailPolicy.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionEventView.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionTreeService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatEvent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatController.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/usage/ToolDetailPolicyTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/usage/ExecutionTreeServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ToolDetailStreamTest.java` | 신규 |
