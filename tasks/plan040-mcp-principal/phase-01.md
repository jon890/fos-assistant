# Phase 01. 보낼 session 과 적을 session 을 값 하나로 묶고 위임 키를 계산한다

**Execution profile**: standard

## 목표

Hermes 에 보낼 session 과 실행 줄에 적을 session 을 `RunSession` 하나로 넘겨, 두 문자열의 순서가 바뀌어도 컴파일되는 자리를 없앤다.
흐름의 하위 실행에도 제출하기 전에 `fos-<uuid>` 를 정해 적는다. 다음 phase 에서 그 실행의 MCP 호출이 부모를 찾게 하기 위해서다.
같은 위임을 두 번 만들지 않는 `delegation_key` 계산 함수를 둔다.

**범위 외**: 토큰과 MCP 요청자 판정(phase 02), 위임 도구 자체(`agent_*`).

## 컨텍스트

- 지금 `AgentRunner.run` 은 오버로드가 다섯이고, 가장 긴 것이 `String sessionId, String recordedSessionId` 를 나란히 받는다. 실제 호출은 두 곳이다
  - `ResearchAndBuildFlow.run` 의 Chief: `sessions.ensure(conversation)` 과 `conversation.executionSessionId()` 를 넘긴다(12인자)
  - `ChildExecutionRunner.run`: session 에 `null` 을 넘긴다(11인자)
- `ChatService.begin` 은 `AgentRunner` 를 쓰지 않고 `sessions.ensure(conversation)` 을 명령에, `conversation.executionSessionId()` 를 `ExecutionRecorder.start` 의 마지막 인자에 넘긴다
- `ConversationSessions.ensure(Conversation)` 은 지금 `String` 을 돌려준다. 새 대화는 `"fos-" + UUID` 를 `assignSessionIfAbsent` 로 저장하고, 경합에서 지면 저장된 값을 다시 읽는다(`adoptSessions`)
- `Conversation.executionSessionId()` 는 `hermesRootSessionId` 가 있으면 그것, 없으면 `hermesSessionId` 다
- `ExecutionRecorder.start(..., Long retryOfExecutionId, String hermesSessionId)` 는 실행 줄에 적을 session 하나만 받는다. 이 시그니처는 바꾸지 않는다
- `MemoryProposer.proposeFrom` 은 session 없이 돈다. 바꾸지 않는다

**근거 문서**: `docs/code-architecture.md` 의 「MCP 요청자」 아래 「실행 줄에 적는 session」 절, `docs/adr/ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md` 의 「`delegation_key`」 절, `docs/data-schema.md` 의 `agent_execution` 의 `hermes_session_id` 와 `delegation_key` 줄

## 의도 메모

- 값 객체는 요구한 최소만 둔다. 부모 번호와 위임 키까지 묶는 큰 객체는 만들지 않는다. 위임 키는 위임 도구가 `ExecutionRecorder.start` 에 넘길 때 붙인다
- `AgentRunner.run` 오버로드는 하나로 줄인다. 쓰지 않는 오버로드를 남기면 다음 사람이 문자열 하나짜리를 골라 같은 실수를 한다
- 하위 실행의 session 은 부모의 것을 잇지 않는다. 새 값이다. 중간 산출물이 대화 session 에 쌓이면 다음 turn 이 그것을 읽기 때문이다(`ChildExecutionRunner` 의 기존 주석)
- `DelegationKey` 는 입력 넷 중 하나라도 비었거나 null 이면 `IllegalArgumentException` 을 던진다. 빈 칸으로 만든 키가 다른 호출과 겹치지 않게 한다

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/orchestration/domain/RunSession.java` 신규

```java
public record RunSession(String runtimeSessionId, String correlationSessionId) {
    public static RunSession fresh();                                   // 둘 다 같은 새 "fos-" + UUID
    public static RunSession ofConversation(String hermesSessionId, String hermesRootSessionId);
    public static String newSessionId();                                 // "fos-" + UUID
}
```

- `ofConversation` 은 `runtimeSessionId = hermesSessionId`, `correlationSessionId = hermesRootSessionId != null ? hermesRootSessionId : hermesSessionId`
- 두 칸의 뜻을 Javadoc 에 한국어로 적는다. runtime 은 Hermes 에 보낼 session, correlation 은 실행 줄의 `hermes_session_id` 에 적어 MCP 호출이 부모 실행을 찾는 값이다

### 2. `ConversationSessions.ensure` 가 `RunSession` 을 돌려준다

- `PREFIX` 와 UUID 생성은 `RunSession.newSessionId()` 를 쓴다
- 돌려주는 값은 `RunSession.ofConversation(conversation.hermesSessionId(), conversation.hermesRootSessionId())`. 경합에서 진 쪽은 `adoptSessions` 뒤의 값으로 만든다
- `Conversation.executionSessionId()` 는 지운다. 판정은 `RunSession.ofConversation` 한 곳에 둔다

### 3. `ChatService.begin` 과 `ResearchAndBuildFlow.run`

- `ChatService.begin`: `RunSession session = sessions.ensure(conversation)`. 명령에는 `session.runtimeSessionId()`, `executions.start` 의 마지막 인자에는 `session.correlationSessionId()`
- `ResearchAndBuildFlow.run` 의 Chief: `sessions.ensure(conversation)` 을 그대로 `runner.run` 에 넘긴다

### 4. `AgentRunner.run` 을 하나로 줄인다

```java
public Run run(CurrentUser user, Conversation conversation, Agent agent, String task,
        Long parentExecutionId, Long rootExecutionId, RunSession session,
        Consumer<AgentExecution> onStarted, BiConsumer<AgentExecution, String> onSubmitted,
        BooleanSupplier cancelled, String instructionAddition)
```

- `executions.start(..., null, session.correlationSessionId())`, `HermesRunCommand` 의 session 은 `session.runtimeSessionId()`
- 나머지 오버로드는 지운다. 클래스와 메서드 Javadoc 의 session 설명을 새 인자에 맞게 고친다

### 5. `ChildExecutionRunner.run`

- `runner.run(..., RunSession.fresh(), execution -> {}, onSubmitted, cancelled, instructionAddition)`
- 클래스 Javadoc 의 「자식은 부모의 Hermes session 을 잇지 않는다」 를 「새 `fos-<uuid>` 를 정해 보내고 실행 줄에 적는다」 로 고친다

### 6. `backend/src/main/java/com/bifos/assistant/orchestration/domain/DelegationKey.java` 신규

```java
public final class DelegationKey {
    public static String of(String profileName, String rootSessionId, String sessionId, String toolCallId);
}
```

- `"v1"`, `profileName`, `rootSessionId`, `sessionId`, `toolCallId` 를 이 순서로 `"\n"` 하나로 이은 UTF-8 의 SHA-256 소문자 16진수 64자
- 지금은 이것을 부르는 코드가 없다. 위임 도구가 쓴다고 Javadoc 에 적는다

### 7. 이 phase 를 검증하는 테스트

- `backend/src/test/java/com/bifos/assistant/orchestration/RunSessionTest.java` 신규(단위)
  - 새 대화(`ofConversation("fos-a", "fos-a")`)는 두 값이 같다
  - 압축 교체 뒤(`ofConversation("fos-b", "fos-a")`)는 runtime 만 `fos-b` 이고 correlation 은 뿌리 `fos-a`
  - 뿌리가 빈 옛 대화(`ofConversation("legacy-session", null)`)는 두 값이 모두 `legacy-session`
  - `fresh()` 는 `fos-` 로 시작하고 두 값이 같으며 두 번 부르면 다르다
- `backend/src/test/java/com/bifos/assistant/orchestration/DelegationKeyTest.java` 신규(단위)
  - 같은 다섯 칸의 재시도는 같은 키. 소문자 16진수 64자
  - root 와 profile 이 같고 session 만 다르면 다른 키
  - root 만 다르면 다른 키
  - profile 만 다르면 다른 키
  - 기대값 하나를 테스트 안에서 `MessageDigest` 로 따로 계산해 맞춘다(구현과 무관한 계산으로 정의를 고정)
  - null 이나 빈 칸은 `IllegalArgumentException`
- `backend/src/test/java/com/bifos/assistant/chat/ConversationSessionTest.java` 수정: `executionSessionId()` 를 쓰던 옛 대화 검사는 `ensure` 가 돌려준 `RunSession` 의 두 값이 `legacy-session` 인지로 바꾼다. 새 대화는 두 값이 같고 `fos-` 로 시작하는지 본다
- `backend/src/test/java/com/bifos/assistant/orchestration/ChildExecutionRunnerTest.java` 수정: 자식 명령의 `sessionId()` 가 null 이 아니라 `fos-` 로 시작하고, 자식 실행 줄의 `hermesSessionId()` 가 그 값과 같다
- `backend/src/test/java/com/bifos/assistant/orchestration/ResearchAndBuildFlowTest.java` 수정: `Chief_실행_줄에는_대화의_뿌리_session이_적히고_하위_실행_줄은_비어_있다` 를 하위 실행 줄 셋이 각자 `fos-` 로 시작하는 서로 다른 값이고 뿌리와 다르며, 그 값이 각 하위 명령의 `sessionId()` 와 같다는 검사로 바꾼다. 테스트 이름도 그 뜻으로 바꾼다

## 검증

```bash
# cwd: backend/
./gradlew test --tests '*RunSessionTest' --tests '*DelegationKeyTest' --tests '*ConversationSessionTest' --tests '*ChildExecutionRunnerTest' --tests '*ResearchAndBuildFlowTest' --tests '*ChatServiceTest'
```

그 다음 AGENTS.md 「확인」 절을 적힌 순서대로 돌린다.

```bash
cd backend && ./gradlew test
cd web && pnpm typecheck && pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
```

`grep -rn "recordedSessionId\|executionSessionId" backend/src` 가 아무것도 찾지 않는다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/orchestration/domain/RunSession.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/domain/DelegationKey.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ConversationSessions.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/Conversation.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentRunner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/ChildExecutionRunner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/ResearchAndBuildFlow.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/RunSessionTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/orchestration/DelegationKeyTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/ConversationSessionTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/ChildExecutionRunnerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/ResearchAndBuildFlowTest.java` | 수정 |
