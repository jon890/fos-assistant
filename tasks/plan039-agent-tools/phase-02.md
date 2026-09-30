# Phase 02. 위임 서비스가 MCP 타입을 받지 않게 해 패키지 순환을 끊는다

**Execution profile**: standard

## 목표

`orchestration` 이 `mcp` 를 import 하지 않게 한다. 의존 방향은 `mcp` → `orchestration` 하나다.
**동작을 바꾸지 않는 리팩터링이다.** 기존 테스트의 기대값은 바꾸지 않는다.

**범위 외**: `agent_status` 판정 규칙 변경(phase 03), 새 도구(phase 04, 05).

## 컨텍스트

- 지금 `orchestration.application.AgentDelegationService` 가 `mcp.application.McpCaller` 를 받는다(`list(McpCaller)`, `status(McpCaller, Long)`)
- 반대로 `mcp.application.McpCallerResolver` 는 `orchestration.application.SessionOwnerResolver` 를 부른다. 그래서 두 패키지가 서로를 import 한다
- `orchestration` 이 `mcp` 를 import 하는 곳은 `AgentDelegationService` 하나다
- `McpCaller` 는 `user()`(`CurrentUser`), `originExecution()`(`AgentExecution`), `context()`(`McpCallContext`) 를 늘 갖는다

**근거 문서**: `docs/code-architecture.md` 의 「backend 패키지」 와 「다른 에이전트에게 맡기기」 절. 사용자가 2026-09-30 에 순환을 이 계획의 첫 커밋으로 끊기로 정했다.

## 작업 항목

### 1. `AgentDelegationService` 의 인자

- `list(CurrentUser user)`, `status(CurrentUser user, AgentExecution origin, Long executionId)` 로 바꾼다
- `McpToolService` 가 `caller.user()`, `caller.originExecution()` 을 풀어 넘긴다
- 판정 로직은 바꾸지 않는다

### 2. 문서

- `docs/code-architecture.md` 「backend 패키지」 표 아래에 의존 방향을 적는다: `mcp` 는 `orchestration` 을 부르고, `orchestration` 은 `mcp` 를 import 하지 않는다. 위임 서비스는 요청자와 origin 실행을 따로 받는다. 까닭은 두 패키지가 서로를 import 하면 한쪽을 바꿀 때 다른 쪽의 타입을 함께 바꿔야 하기 때문이다
- 같은 문서 「다른 에이전트에게 맡기기」 표의 `AgentDelegationService` 줄에서 「부모는 `McpCaller.originExecution()` 이다」 를 「`McpToolService` 가 `McpCaller` 에서 풀어 넘긴 요청자와 origin 실행을 받는다」 로 고친다

### 3. 테스트

- `McpToolServiceTest` 등 기존 테스트는 호출 모양만 따라 고친다

## 검증

```bash
grep -rn "import com.bifos.assistant.mcp" backend/src/main/java/com/bifos/assistant/orchestration   # 출력이 없어야 한다
cd backend && ./gradlew test
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentDelegationService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/application/McpToolServiceTest.java` | 수정 |
| `docs/code-architecture.md` | 수정 |
