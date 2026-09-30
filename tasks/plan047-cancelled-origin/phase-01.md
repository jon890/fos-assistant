# Phase 01. 취소한 origin 의 하위 에이전트 MCP 호출을 거절한다

**Execution profile**: standard

## 목표

`SessionOwnerResolver.resolve` 의 등록 분기에서 origin 실행이 `CANCELLED` 면 다른 거절과 같은 `MCP_CALL_CONTEXT_INVALID` 로 거절한다.
사용자가 turn 을 중지한 뒤에도 Hermes background 하위 에이전트가 그 사용자의 권한으로 `memory_read`, `artifact_write` 를 쓰는 것을 막는다.

**범위 외**: 가짜 Hermes e2e(phase 02). 하위 에이전트를 실제로 멈추는 것. `FAILED`(`ORPHANED` 포함) origin 의 정책 변경. 등록 경로(`SubagentSessionRegistrar`)의 변경. 스키마 변경. `agent_*` 도구.

## 컨텍스트

- `backend/src/main/java/com/bifos/assistant/orchestration/application/SessionOwnerResolver.java` 의 `resolve(String profileName, String rootSessionId, String sessionId)` 가 판정한다
  - 등록(`HermesSessionBindingRepository.findByProfileNameAndSessionId`)이 있으면 뿌리를 견준 뒤 `executions.findById(binding.originExecutionId())` 를 그대로 돌려준다. 지금은 상태를 보지 않는다
  - 등록이 없고 session 이 뿌리와 같으면 `DelegationParentResolver.resolve` 가 `RUNNING` 만 찾는다. 취소한 최상위 실행은 여기서 이미 막힌다. 이 분기는 바꾸지 않는다
  - 거절은 `private static ApiException reject(String profileName, String reason)` 하나로 만든다. 로그 `MCP 호출의 origin 실행을 정하지 못했다 profile={} reason={}` 를 남기고 `new ApiException(ErrorCode.MCP_CALL_CONTEXT_INVALID, "call context is invalid")` 를 돌려준다
- 상태는 `com.bifos.assistant.usage.domain.ExecutionStatus` 의 `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED` 다. `AgentExecution.status()` 로 읽는다
- 대화 중지(`ChatService.cancel`)와 흐름 중지(`AgentRunner`, `ResearchAndBuildFlow`)가 모두 `ExecutionRecorder.cancel` 로 `CANCELLED` 를 적는다
- 요청자 판정은 `McpCallerResolver.resolve` 가 `SessionOwnerResolver.resolve` 를 불러 한다. MCP 도구 결과의 거절 문구는 `McpToolService.invalidContext()` 이고 `isError: true` 에 「호출 맥락을 확인할 수 없습니다. 새 대화에서 다시 시도해 주세요.」 다
- 테스트 본보기
  - `backend/src/test/java/com/bifos/assistant/orchestration/SessionOwnerResolverTest.java`: `register(root, parent)` 로 등록하고 `setStatus(execution, status)` 로 상태를 바꾼다. `assertRejected(profile, root, session)` 이 `MCP_CALL_CONTEXT_INVALID` 를 확인한다. 번호 주석(`// 1`) 은 PR #48 리뷰의 필수 검사 번호다
  - `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryToolTest.java` 의 `등록한_하위_에이전트는_부모_실행이_끝난_뒤에도_그_사용자로_읽고_등록이_없으면_거절한다`: `registrar.register(PROFILE, dadRoot, dadRoot, registered)`, `subagentRead(session, memoryId)`, `json.valueToTree(toolService.invalidContext())` 로 견준다
  - `backend/src/test/java/com/bifos/assistant/mcp/McpArtifactWriteToolTest.java` 의 `등록한_하위_에이전트는_부모_실행이_끝난_뒤에도_그_사용자의_대화에만_쓴다`: `subagentWrite(session, conversation, path)`, `store.resolveInside(conversationId, path)`

**근거 문서**: `docs/adr/ADR-037-hermes-하위-에이전트-session-의-주인은-만들-때-등록한-줄로-정한다.md` 의 「결정」 과 「판정 순서」, `docs/flow.md` 「MCP 호출의 요청자를 정할 때」 의 「갈리는 지점」, `docs/hermes/delegation.md` 「취소가 아래로 내려가지 않는다」

## 의도 메모

- 정책은 넷이다. `RUNNING` 허용, `SUCCEEDED` 허용, `FAILED` 는 지금처럼 허용, `CANCELLED` 거절
- `FAILED` 를 막지 않는 까닭: Control Plane 이 다시 떠 도는 실행을 `ORPHANED` 로 끝낸 경우가 `FAILED` 에 들어 있다. 사용자가 멈추려 한 것이 아니다
- 모델에게 취소를 알리지 않는다. 밖으로는 다른 거절과 같은 도구 결과다. 로그 이유에만 `ORIGIN_CANCELLED` 표시를 붙인다(`docs/flow.md` 가 그 표시를 적었다)
- 등록 경로는 바꾸지 않는다. 취소한 origin 의 자식도 등록은 받고, 호출이 판정에서 거절된다. 등록에서 막으면 이미 등록된 자식은 그대로 통과하므로 호출마다 판정하는 쪽이 맞다
- 판정 지점을 `McpCallerResolver` 로 올리지 않는다. 등록 분기만의 규칙이고, 최상위 분기는 이미 `RUNNING` 만 받는다

## 작업 항목

### 1. `SessionOwnerResolver.resolve` 의 등록 분기

origin 실행을 읽은 뒤 상태를 본다.

```java
AgentExecution origin = executions.findById(binding.get().originExecutionId())
        .orElseThrow(() -> reject(profileName, "등록의 origin 실행이 없다"));
// 중지해도 Hermes 하위 에이전트는 계속 돌 수 있다. 사용자가 멈춘 turn 의 권한을 그 자식이 쓰지 못하게 한다.
if (origin.status() == ExecutionStatus.CANCELLED) {
    throw reject(profileName, "사용자가 중지한 origin 실행이다 ORIGIN_CANCELLED");
}
return origin;
```

클래스 Javadoc 의 「origin 실행이 이미 끝났어도 그대로 쓴다」 문장을 「origin 실행이 끝났어도 쓰되, 사용자가 중지해 `CANCELLED` 로 끝났으면 거절한다」 로 바꾸고 까닭 한 문장을 붙인다.

### 2. `SessionOwnerResolverTest`

- `등록한_하위_에이전트는_origin_실행이_취소되면_거절한다`: 부모 `RUNNING` 에서 `register(root, root)` 로 자식 등록, 그때 `owners.resolve` 가 `dadRun` 을 준다. `setStatus(dadRun, CANCELLED)` 뒤 `assertRejected(PROFILE, root, s1)`
- `하위_에이전트의_하위_에이전트도_origin_실행이_취소되면_거절한다`: `s1`, `s2`(부모 `s1`) 등록 뒤 취소, `s2` 거절
- `origin_실행이_실패로_끝나도_등록한_하위_에이전트는_그대로_정한다`: `setStatus(dadRun, FAILED)` 뒤 `assertOrigin(owners.resolve(PROFILE, root, s1), dadRun, dad)`. 지금 동작을 고정한다
- 기존 `// 1`, `// 3`, `// 5`, `// 6`, `// 7` 검사는 그대로 통과해야 한다

### 3. `McpMemoryToolTest`

`등록한_하위_에이전트는_부모_실행이_취소되면_읽지_못한다`: 부모 `RUNNING` 에서 `registrar.register(PROFILE, dadRoot, dadRoot, child)`, 그때 `subagentRead(child, id)` 가 본문을 준다. 부모를 `CANCELLED` 로 바꾸면 결과가 `json.valueToTree(toolService.invalidContext())` 와 같고 본문 글자가 들어 있지 않다.

### 4. `McpArtifactWriteToolTest`

`등록한_하위_에이전트는_부모_실행이_취소되면_쓰지_못한다`: 부모 `RUNNING` 에서 등록, 부모를 `CANCELLED` 로 바꾼 뒤 `subagentWrite(child, own, path)` 의 결과가 `isError: true` 와 「호출 맥락을 확인할 수 없습니다. 새 대화에서 다시 시도해 주세요.」 이고 `store.resolveInside(own.id(), path)` 가 비었다. 경로는 기존 검사처럼 `UUID` 로 새로 만든다.

기존 `등록한_하위_에이전트는_부모_실행이_끝난_뒤에도_그_사용자의_대화에만_쓴다`(`SUCCEEDED`) 는 그대로 통과해야 한다.

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.orchestration.SessionOwnerResolverTest' --tests 'com.bifos.assistant.mcp.McpMemoryToolTest' --tests 'com.bifos.assistant.mcp.McpArtifactWriteToolTest'
cd backend && ./gradlew test
```

기대: 새 검사 다섯과 기존 검사가 모두 통과한다. `SubagentSessionRegistrarTest`, `SubagentSessionEndpointTest`, `DelegationParentResolverTest` 도 전체 실행에서 통과한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/orchestration/application/SessionOwnerResolver.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/SessionOwnerResolverTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryToolTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpArtifactWriteToolTest.java` | 수정 |
