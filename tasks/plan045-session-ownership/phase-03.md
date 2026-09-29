# Phase 03. MCP 호출의 요청자를 등록으로 먼저 정한다

**Execution profile**: deep

## 목표

`SessionOwnerResolver` 를 만들고 `McpCallerResolver` 가 그것을 쓰게 한다.
부모 FOS 실행이 끝난 뒤에도 등록된 하위 에이전트의 `memory_read`, `artifact_write` 가 origin 실행의 사용자로 돈다. 등록이 없는 하위 에이전트 session 의 호출은 거절한다.

**범위 외**: 등록을 적는 쪽(phase 02 에서 끝났다), HTTP 등록 경로(phase 04), 가짜 Hermes 검사(phase 05).

## 컨텍스트

- 판정 순서는 `docs/adr/ADR-037-hermes-하위-에이전트-session-의-주인은-만들-때-등록한-줄로-정한다.md` 의 「판정 순서」 가 정한다
- 지금 판정: `backend/src/main/java/com/bifos/assistant/mcp/application/McpCallerResolver.java` 의 `resolve(McpPrincipal, String toolName, JsonNode fosCtx)` 가 `McpCallContext.verify` 뒤 `DelegationParentResolver.resolve(principal.profileName(), context.rootSessionId())` 를 부르고, 그 실행의 `userId()` 로 사용자를 읽는다
- `McpCallContext` 는 `record McpCallContext(String rootSessionId, String sessionId, String toolCallId)` 다
- phase 02 가 만든 것: `orchestration.infra.HermesSessionBindingRepository.findByProfileNameAndSessionId`, `orchestration.domain.HermesSessionBinding`(`rootSessionId()`, `originExecutionId()`), `orchestration.application.SubagentSessionRegistrar.register(profile, parentRoot, parent, child)`, 검사 도우미 `McpCallSigner` 의 `public` `running`, `save`, `clearRuns`
- `McpCallSigner.context(rawToken, toolName, rootSessionId, sessionId, toolCallId)` 는 package-private 이다. 이 phase 의 HTTP 검사는 모두 같은 `mcp` 패키지에 있어 그대로 쓴다
- 기존 HTTP 검사: `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryToolTest.java`, `McpArtifactWriteToolTest.java`, `McpPrincipalTest.java`. `McpPrincipalTest` 의 `요청자를_정하지_못한_이유가_무엇이든_응답_본문은_바이트까지_같다` 는 거절 이유마다 응답 본문이 같은지 본다

**근거 문서**: `docs/adr/ADR-037-hermes-하위-에이전트-session-의-주인은-만들-때-등록한-줄로-정한다.md`, `docs/flow.md` 의 「MCP 호출의 요청자를 정할 때」 절, `docs/code-architecture.md` 의 「MCP 요청자」 절

## 의도 메모

- **origin 실행의 상태를 판정에 쓰지 않는다.** 등록이 있으면 `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED` 모두 통과한다. 최근 실행이나 최근 turn 을 고르는 질의를 만들지 않는다
- **등록이 없고 `sessionId` 가 `rootSessionId` 와 다르면 거절한다.** 등록이 빠진 하위 에이전트와 `compression.in_place: false` 로 교체된 최상위 session 을 나눌 수 없어 추측하지 않는다(ADR-037 「감당할 것」). hook 은 동기라 정상이면 등록이 먼저 끝나고, 늦거나 빠진 등록은 거절로 끝난다
- 등록의 `rootSessionId` 가 서명한 뿌리와 다르면 거절한다
- 판정 실패는 모두 지금과 같은 `MCP_CALL_CONTEXT_INVALID` 하나다. 밖에서 거절 이유를 나누지 못한다. 이유는 로그에만 남기고 사용자 번호와 session 값은 적지 않는다(`DelegationParentResolver.reject` 와 같은 모양)
- `orchestration` 이 `mcp` 를 import 하지 않는다. `SessionOwnerResolver` 는 문자열 셋을 받는다

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/orchestration/application/SessionOwnerResolver.java`

`@Service`, `@Transactional(readOnly = true)`. `AgentExecution resolve(String profileName, String rootSessionId, String sessionId)`.

1. 셋 중 하나라도 null 이거나 비었으면 거절
2. `findByProfileNameAndSessionId(profileName, sessionId)` 가 있으면: 그 줄의 `rootSessionId()` 가 인자와 다르면 거절. 같으면 `AgentExecutionRepository.findById(originExecutionId)` 를 돌려준다. 없으면 거절
3. 없고 `sessionId.equals(rootSessionId)` 면 `DelegationParentResolver.resolve(profileName, rootSessionId)` 를 돌려준다
4. 그 밖은 거절. 로그 이유에 `SUBAGENT_SESSION_UNREGISTERED` 를 붙여 로그에서 찾게 한다

거절은 `ApiException(ErrorCode.MCP_CALL_CONTEXT_INVALID, "call context is invalid")` 이고 이유는 `log.warn("MCP 호출의 origin 실행을 정하지 못했다 profile={} reason={}")` 로만 남긴다.

### 2. `backend/src/main/java/com/bifos/assistant/mcp/application/McpCallerResolver.java`

`DelegationParentResolver` 대신 `SessionOwnerResolver` 를 주입하고 `owners.resolve(principal.profileName(), context.rootSessionId(), context.sessionId())` 로 origin 실행을 얻는다. 그 실행의 `userId()` 로 사용자를 읽는 것과 옛 토큰 경로는 그대로다. 클래스 Javadoc 의 「부모 실행 찾기」 를 「origin 실행 찾기(ADR-037)」 로 고친다.

### 3. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/orchestration/SessionOwnerResolverTest.java`

`@SpringBootTest @ActiveProfiles("test")`. profile 둘(`session-owner`, `session-owner-other`)을 `McpCallSigner.clearRuns` 로 지우고 사용자 둘(아빠, 아이)을 만든다. 등록은 `SubagentSessionRegistrar` 로 만들고, 7번만 SQL 로 넣는다.

| 번호 | 경우 | 기대 |
| --- | --- | --- |
| 1 | 아빠 #100 `RUNNING`(뿌리 R) → S1 등록 → #100 `SUCCEEDED` → `resolve(profile, R, S1)` | #100 |
| 3 | S1 아래 S2 등록 → #100 을 끝낸 뒤 `resolve(profile, R, S2)` | #100 |
| 5 | S1 등록 → #100 끝남 → 같은 뿌리 R 로 #105 `RUNNING` → S1 판정 | #100. 같은 때 `resolve(profile, R, R)` 은 #105 |
| 6 | 같은 profile 에서 아빠 #100(뿌리 RA) → SA, 아이 #101(뿌리 RB) → SB. 둘 다 끝낸 뒤 판정 | SA 는 #100(아빠), SB 는 #101(아이) |
| 7 | 등록 줄을 `jdbc.update("INSERT INTO hermes_session_binding ...")` 로 넣고 origin 을 `FAILED`(`error_code = ORPHANED`) 로 둔 뒤 판정 | origin 실행. 앞 프로세스가 적은 줄만으로 판정한다 |
| 8 | `session-owner` 의 S1 을 `session-owner-other` 로 판정 | 거절 |
| 11 | 등록 없는 S9 를 뿌리 R 로 판정한다. #100 이 `RUNNING` 이어도 | 거절 |

그 밖에 이 phase 가 다루는 경우다.

- 등록 없이 `sessionId == rootSessionId` 면 지금처럼 도는 실행을 찾는다. 도는 실행이 없으면 거절
- 압축 교체된 최상위 session C(`sessionId = C`, `rootSessionId = R`)로 판정하면 #100 이 돌아도 거절. 같은 C 를 부모로 등록한 S1 의 판정은 #100
- 등록의 뿌리와 서명한 뿌리가 다르면 거절

### 4. HTTP `/mcp` 로 확인하는 검사

- `McpMemoryToolTest.java`: 아빠의 도는 실행 아래 S1 을 `SubagentSessionRegistrar` 로 등록하고 그 실행을 `SUCCEEDED` 로 바꾼 뒤, `McpCallSigner.context(dadToken, "memory_read", dadRoot, S1, "call_" + UUID)` 로 서명한 `memory_read` 가 아빠의 본문을 돌려준다(1번). 등록하지 않은 S2 로 서명하면 `invalidContext()` 결과다(11번)
- `McpArtifactWriteToolTest.java`: 같은 준비에서 S1 이 아빠의 대화에 쓰면 성공하고, 새로 만든 아이의 대화에 쓰면 쓰지 않고 오류다(2번)
- `McpPrincipalTest.java`:
  - 공유 profile 에서 아빠와 아이의 부모가 모두 끝난 뒤 SA 는 아빠 Memory 만, SB 는 아이 Memory 만 읽는다(6번 HTTP 판)
  - `요청자를_정하지_못한_이유가_무엇이든_응답_본문은_바이트까지_같다` 에 두 경우를 더한다. 등록 없는 하위 session 으로 서명한 호출, 등록의 뿌리와 다른 뿌리로 서명한 호출이다

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.orchestration.SessionOwnerResolverTest' --tests 'com.bifos.assistant.mcp.McpMemoryToolTest' --tests 'com.bifos.assistant.mcp.McpArtifactWriteToolTest' --tests 'com.bifos.assistant.mcp.McpPrincipalTest' --tests 'com.bifos.assistant.orchestration.DelegationParentResolverTest'
cd backend && ./gradlew test
grep -n 'findTop\|OrderBy\|status()' backend/src/main/java/com/bifos/assistant/orchestration/application/SessionOwnerResolver.java   # 결과가 없어야 한다. 상태와 최근 줄로 고르지 않는다
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/orchestration/application/SessionOwnerResolver.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpCallerResolver.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/SessionOwnerResolverTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryToolTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpArtifactWriteToolTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpPrincipalTest.java` | 수정 |
