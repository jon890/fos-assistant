# Phase 02. `_fos_ctx` 를 검증하고 도는 부모 실행을 찾는다

**Execution profile**: deep

## 목표

MCP 요청의 도구 인자에 profile 플러그인이 덮어쓴 `_fos_ctx` 를 읽고 서명을 확인한 뒤, 서명한 뿌리 session 을 가진 **도는 실행**을 부모로 찾는 경계를 만든다.
기존 도구 `memory_read`, `artifact_write` 는 `_fos_ctx` 가 와도 버리고 그대로 동작한다.

**범위 외**: `agent_*` 도구 자체와 위임(plan039). 플러그인 배치(`fos-home-infra`).

## 컨텍스트

- 계약은 `docs/hermes/delegation.md` 「부모 실행을 잇는 방법」 의 「`_fos_ctx` 계약」 이 정본이다. 키는 `v`(1), `session_id`, `root_session_id`, `tool_call_id`, `sig`. 서명은 HMAC-SHA256, key 는 **그 profile 의 MCP 토큰을 SHA-256 한 소문자 16진수 문자열**, 서명할 글은 `v1`, 도구 이름, `root_session_id`, `session_id`, `tool_call_id` 를 이 순서로 `\n` 하나로 이은 것
- 계약 문서가 정하지 않았던 인코딩은 코디네이터와 이렇게 정했다. 플러그인 쪽 지시문도 같다
  - HMAC key 는 64자 소문자 16진수 문자열의 **UTF-8 바이트**다. 16진수를 풀어 낸 32바이트가 아니다. 서명할 글도 UTF-8 이다
  - `v` 는 JSON 숫자 `1` 이다. 문자열 `"1"` 은 거절한다
  - `sig` 는 소문자 16진수 64자만 받는다. 대문자가 섞이면 거절한다
  - 서명할 글에 도구 인자는 넣지 않는다
- 서버는 토큰 원문을 갖지 않는다. `AgentToken.tokenHash` 가 `AgentTokenService.hash(raw)`(SHA-256 소문자 16진수)다. `AgentTokenAuthenticationFilter.doFilterInternal` 이 Bearer 원문으로 `tokens.authenticate(raw)` 를 부르고 `CurrentUser` 만 보안 문맥에 넣는다
- `McpController.call` 은 도구 이름으로 나눠 `readMemory`, `writeArtifact` 를 부른다. `writeArtifact` 는 `onlyArtifactFields` 로 모르는 키를 거절하므로 지금은 `_fos_ctx` 가 오면 실패한다
- phase 01 로 실행 줄에 `hermes_session_id` 가 있고 `(hermes_session_id, status)` 색인이 있다

**근거 문서**: `docs/adr/ADR-031-mcp-호출의-부모-실행은-profile-플러그인이-서명한-뿌리-session-으로-잇는다.md`, `docs/hermes/delegation.md` 의 「부모 실행을 잇는 방법」 절, `docs/flow.md` 의 「다른 에이전트에게 맡길 때」 절

## 의도 메모

- 서명 비교는 상수 시간 비교(`MessageDigest.isEqual`)로 한다
- 실패는 모두 `ErrorCode.MCP_CALL_CONTEXT_INVALID(HttpStatus.FORBIDDEN)` 하나다. JSON-RPC 오류로 바꾸는 일은 이 코드를 쓰는 plan039 의 도구가 한다
- 부모 찾기는 `status = RUNNING` 이고 `hermes_session_id = root_session_id` 이며 `user_id` 가 토큰의 사용자인 줄이 **정확히 하나**일 때만 성공한다. 없거나 둘 이상이면 같은 실패로 끝낸다. 가장 최근 줄을 고르지 않는다
- 실패는 외부에 이유를 나누지 않는다(서명 없음, 틀림, 부모 없음이 같은 응답). 서버 로그에는 이유를 남기되 토큰과 서명 값은 적지 않는다
- `_fos_ctx` 를 버리는 처리는 도구 이름 분기 전에 한 번 한다. `artifact_write` 의 입력 규격(`additionalProperties: false`)은 그대로 둔다. Hermes 는 hook 이 더한 키를 규격으로 검증하지 않는다(실측)
- 이 phase 의 결과는 plan039 의 `AgentDelegationService` 가 부른다. 이 phase 에서는 호출하는 도구가 없으므로 단위 테스트와 통합 테스트로 검증한다

## 작업 항목

### 1. 토큰 해시를 요청에 싣기

`AgentTokenAuthenticationFilter` 가 인증에 성공하면 `AgentTokenService.hash(raw)` 값을 요청 속성으로 둔다. 속성 이름은 필터의 상수 `TOKEN_HASH_ATTRIBUTE` 다. 원문은 어디에도 두지 않는다.

### 2. `backend/src/main/java/com/bifos/assistant/mcp/application/McpCallContext.java`

`_fos_ctx` 를 읽는 값 객체와 검증기. 도구 이름, `_fos_ctx` JSON, 토큰 해시를 받아 서명을 확인하고 `rootSessionId`, `sessionId`, `toolCallId` 를 돌려준다. `_fos_ctx` 가 없거나 객체가 아니거나, 키가 빠졌거나 문자열이 아니거나 비었거나, `v` 가 숫자 1 이 아니거나, `sig` 가 소문자 16진수 64자가 아니거나 틀리면 `ApiException(ErrorCode.MCP_CALL_CONTEXT_INVALID, ...)` 로 실패한다. `ErrorCode` 에 `MCP_CALL_CONTEXT_INVALID(HttpStatus.FORBIDDEN)` 을 더한다.

### 3. `backend/src/main/java/com/bifos/assistant/orchestration/application/DelegationParentResolver.java`

`CurrentUser` 와 `rootSessionId` 로 도는 부모 실행을 찾는다. `AgentExecutionRepository` 에 `hermes_session_id`, `status`, `user_id` 로 찾는 질의를 더한다. 없거나 둘 이상이면 2 와 같은 `MCP_CALL_CONTEXT_INVALID` 로 실패한다(외부에서 구분되지 않게).

### 4. `McpController` 에서 `_fos_ctx` 버리기

`call` 에서 도구 이름으로 나누기 전에 `arguments` 에서 `_fos_ctx` 를 뗀 인자를 만들어 도구로 넘긴다. 떼는 일은 private 메서드 하나로 둔다. 받은 인자 노드는 바꾸지 않고 복사본에서 뗀다. `memory_read`, `artifact_write` 는 뗀 인자로 지금과 같이 검증한다. 뗀 `_fos_ctx` 를 `agent_*` 도구에 넘기는 일은 plan039 가 한다. 이 phase 는 넘기지 않는다.

### 5. docs

- `docs/hermes/delegation.md` 「`_fos_ctx` 계약」 에 위 「컨텍스트」 의 인코딩 네 줄과 아래 test vector 를 더한다. 기존 문장은 바꾸지 않는다
- `docs/code-architecture.md` 「어느 클래스가 무엇을 하나」 표에 `orchestration.application.DelegationParentResolver`(서명한 뿌리 session 과 토큰의 사용자로 도는 부모 실행 하나를 찾는다. 없거나 둘 이상이면 같은 실패) 줄을 더하고, `AgentDelegationService` 줄의 「부모 찾기」 가 이 클래스를 쓴다는 것을 적는다

test vector 다. Python `hmac` 으로 계산한 값이며 구현과 무관한 기대값이다.

| 항목 | 값 |
| --- | --- |
| 토큰 원문 | `test-mcp-token-0001` |
| key(토큰의 SHA-256 소문자 16진수) | `41ed73a34f34174ba0b6ded1b16cf4a085b6da45df0f711ccbeaf2a2bbc2a2ac` |
| 도구 이름 | `agent_delegate` |
| `root_session_id` | `fos-00000000-0000-4000-8000-000000000001` |
| `session_id` | `하위-세션-1` |
| `tool_call_id` | `call_0001` |
| 기대 `sig` | `b28a128dbb642aba7a8b4c35dcb237e2feb5a452305ec275909c32a00ae1b25b` |
| 같은 칸에 도구 이름만 `agent_status` 일 때 | `62109c6c99e7ed4638e4343f1e5b6b22a3f55dd974560916149986866c253236` |

### 6. 테스트

- `McpCallContextTest`
  - 위 test vector 가 그대로 통과한다(기대 `sig` 를 문자열로 박는다. 테스트 안의 도우미로 만들지 않는다). 돌려준 세 칸이 입력과 같다
  - `agent_status` 로 검증하면 `agent_delegate` 의 `sig` 는 거절된다(도구 이름이 서명에 들어가는지)
  - 한 글자 바꾼 `sig`, 대문자로 바꾼 `sig`, `_fos_ctx` 없음, 키 하나 누락, `v` 가 2, `v` 가 문자열 `"1"` 을 모두 거절한다
  - 거절은 모두 같은 `ErrorCode.MCP_CALL_CONTEXT_INVALID` 인지 단언한다
- `DelegationParentResolverTest`: 도는 부모 하나면 찾음, 끝난 실행이면 실패, 남의 사용자 실행이면 실패, 같은 session 의 도는 줄이 둘이면 실패. 실패는 모두 `MCP_CALL_CONTEXT_INVALID` 인지 단언한다
- `McpMemoryToolTest`, `McpArtifactWriteToolTest` 에 `_fos_ctx` 를 붙인 호출이 지금과 같이 동작하는 경우를 하나씩 더한다
- `McpMemoryToolTest` 에 인증된 요청의 `TOKEN_HASH_ATTRIBUTE` 속성이 `AgentTokenService.hash(토큰 원문)` 과 같은지 MockMvc `request().attribute(...)` 로 단언하는 경우를 더한다

## 검증

AGENTS.md 「확인」 절을 적힌 순서대로 돌린다.

```bash
cd backend && ./gradlew test
cd web && pnpm typecheck && pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
```

완료 처리: 검증이 모두 통과하면 `tasks/plan038-delegation-context/index.json` 의 `status` 를 `completed` 로 바꿔 이 phase 커밋에 담는다. 그 뒤 PR 의 마지막 커밋으로 `tasks/plan038-delegation-context/` 를 지운다(`docs(docs): 구현이 끝난 부모 실행 잇기 계획서를 지운다`).

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/mcp/infra/AgentTokenAuthenticationFilter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpCallContext.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/DelegationParentResolver.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/infra/AgentExecutionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/application/McpCallContextTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/orchestration/DelegationParentResolverTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryToolTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpArtifactWriteToolTest.java` | 수정 |
| `docs/hermes/delegation.md` | 수정 |
| `docs/code-architecture.md` | 수정 |
| `tasks/plan038-delegation-context/index.json` | 수정 |
