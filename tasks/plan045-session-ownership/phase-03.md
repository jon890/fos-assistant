# Phase 03. 하위 에이전트 session 등록 경로

**Execution profile**: deep

## 목표

profile 플러그인이 `subagent_start` hook 에서 부를 내부 경로 `POST /internal/hermes/session-bindings/subagent` 를 연다.
MCP 토큰으로 인증하고, 본문 서명을 확인한 뒤 phase 02 의 `SubagentSessionRegistrar` 를 부른다.

**범위 외**: 플러그인 쪽 hook(비공개 저장소 `fos-home-infra`), 가짜 Hermes 검사(phase 04). 이 경로를 MCP 도구로 내지 않는다.

## 컨텍스트

- 계약(경로, 인증, 본문 칸, 서명, 응답과 오류 코드, 고정 서명 값)은 `docs/hermes/delegation.md` 의 「하위 에이전트 session 등록 계약」 이 정한다. 이 phase 는 그 표를 그대로 구현한다
- 인증 필터 `backend/src/main/java/com/bifos/assistant/mcp/infra/AgentTokenAuthenticationFilter.java` 는 `shouldNotFilter` 에서 `request.getServletPath()` 가 `/mcp` 가 아니면 건너뛴다. `Origin` 헤더가 있으면 403, `Bearer` 가 없으면 401, 인증하면 `McpPrincipal` 을 인증 주체로 두고 `TOKEN_HASH_ATTRIBUTE` 요청 속성을 넣는다
- 사용자 JWT 필터 `backend/src/main/java/com/bifos/assistant/shared/auth/ControlPlaneJwtFilter.java` 는 `UNFILTERED_PATHS = Set.of("/mcp", "/api/v1/signin/allowed")` 를 `request.getRequestURI()` 로 건너뛴다. 새 경로를 여기 더하지 않으면 JWT 필터가 Bearer 를 사용자 토큰으로 읽는다
- `backend/src/main/java/com/bifos/assistant/shared/config/SecurityConfig.java` 는 `anyRequest().authenticated()` 이고 토큰 필터를 JWT 필터 앞에 둔다
- 서명 확인의 본보기는 `backend/src/main/java/com/bifos/assistant/mcp/application/McpCallContext.java` 의 `verify` 다. `v` 는 JSON 정수 1 만, `sig` 는 `^[0-9a-f]{64}$` 만, 비교는 `MessageDigest.isEqual`, key 는 토큰 해시 문자열의 UTF-8 바이트다
- 컨트롤러의 요청과 응답 모양은 그 패키지의 `*Dtos.java` 하나에 모은다(`backend/AGENTS.md` 「데이터 클래스는 컨트롤러 안에 두지 않는다」). `mcp/presentation` 에는 `McpDtos.java` 가 있다
- 오류 응답은 `ApiException` 을 던지면 `GlobalExceptionHandler.handleApi` 가 `{code, message}` 와 `ErrorCode` 의 상태로 바꾼다
- `SubagentSessionRegistrar.register(profileName, parentRootSessionId, parentSessionId, childSessionId)` 와 `SubagentRegistrationResult { CREATED, EXISTS }`, `ErrorCode.SESSION_BINDING_REJECTED`(403), `SESSION_BINDING_CONFLICT`(409) 는 phase 02 가 만들었다

**근거 문서**: `docs/hermes/delegation.md` 의 「하위 에이전트 session 등록 계약」 절, `docs/adr/ADR-037-hermes-하위-에이전트-session-의-주인은-만들-때-등록한-줄로-정한다.md`, `docs/code-architecture.md` 의 「MCP 요청자」 절, `docs/flow.md` 의 「하위 에이전트 session 을 등록할 때」 절

## 의도 메모

- 본문은 `JsonNode` 로 받아 직접 검사한다. Jackson 의 record 바인딩은 `v: "1"` 이나 `1.0` 을 정수로 바꿔 받을 수 있다. 본문 모양이 틀린 것도 서명이 틀린 것과 같은 `SESSION_BINDING_REJECTED` 다. 이유는 로그에만 남긴다
- `child_subagent_id`, `parent_subagent_id` 는 문자열이나 `null` 이면 받고 저장하지 않는다. 다른 타입이면 거절한다. 모르는 키는 무시한다(플러그인이 먼저 칸을 더해도 깨지지 않게)
- 인증 주체가 `McpPrincipal` 이 아니면(사용자 JWT) `SESSION_BINDING_REJECTED` 로 거절한다. profile 이 빈 옛 토큰도 거절한다. 옛 토큰에는 profile 이 없어 등록을 profile 로 묶을 수 없다
- 토큰, `sig`, 본문 원문을 로그에 남기지 않는다
- 서명할 글의 첫 줄이 `v1-subagent` 다. `_fos_ctx` 의 첫 줄 `v1` 다음은 도구 이름이라, 도구 이름이 `v1-subagent` 인 도구가 없는 한 두 서명이 섞이지 않는다

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/mcp/application/SubagentRegistration.java`

`record SubagentRegistration(String parentRootSessionId, String parentSessionId, String childSessionId)`.

`static SubagentRegistration verify(JsonNode body, String tokenHash)`:

- `tokenHash` 가 비었거나 `body` 가 객체가 아니면 거절
- `v` 가 정수 1 이 아니면 거절. `McpCallContext` 의 `isVersionOne` 과 `hmac` 을 package-private 으로 열어 함께 쓴다. 같은 규칙을 두 번 쓰지 않는다
- `parent_session_id`, `parent_root_session_id`, `child_session_id`, `sig` 가 비지 않은 문자열이 아니면 거절. `sig` 가 소문자 16진수 64자가 아니면 거절
- `child_subagent_id`, `parent_subagent_id` 가 있으면 문자열이나 `null` 이어야 한다
- 서명할 글 `String.join("\n", "v1-subagent", parentRootSessionId, parentSessionId, childSessionId)` 의 HMAC-SHA256 을 `MessageDigest.isEqual` 로 견준다

거절은 `ApiException(ErrorCode.SESSION_BINDING_REJECTED, "session binding is rejected")` 하나이고 이유는 `log.warn` 으로만 남긴다.

### 2. `backend/src/main/java/com/bifos/assistant/mcp/presentation/SubagentSessionController.java` 와 응답 모양

`@RestController`. `@PostMapping("/internal/hermes/session-bindings/subagent")`.

- 인자: `@AuthenticationPrincipal Object principal`(또는 `Authentication`), `@RequestBody JsonNode body`
- 주체가 `McpPrincipal` 이 아니거나 `bound()` 가 거짓이면 `SESSION_BINDING_REJECTED`
- `SubagentRegistration.verify(body, principal.tokenHash())` 뒤 `registrar.register(principal.profileName(), …)`
- `CREATED` 면 `201` 과 `{"result": "created"}`, `EXISTS` 면 `200` 과 `{"result": "exists"}`

응답 record 는 `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpDtos.java` 에 `SubagentRegistrationResponse(String result)` 로 더한다.

### 3. 필터 두 곳에 경로를 더한다

- `AgentTokenAuthenticationFilter.shouldNotFilter`: `/mcp` 와 `/internal/hermes/session-bindings/subagent` 둘 다 거른다. 경로 상수를 한 곳에 둔다(예: `AGENT_TOKEN_PATHS`)
- `ControlPlaneJwtFilter.UNFILTERED_PATHS` 에 같은 경로를 더하고 Javadoc 의 「`/mcp` 는 장기 토큰을 쓰는 다른 인증 경계다」 에 등록 경로를 함께 적는다

### 4. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/mcp/application/SubagentRegistrationTest.java`

운영 코드를 부르지 않고 고정 값으로 확인한다. 토큰 원문 `test-mcp-token-0001` 의 해시는 `AgentTokenService.hash` 로 얻는다.

- 정상: `docs/hermes/delegation.md` 「하위 에이전트 session 등록 계약」 의 최상위 자식 값과 기대 `sig` `ba540b481d830453accd812c8610be8d9467a532d5ff3db891514dcfe3d0726b`, 중첩 자식 값과 `5479a21f26ddeb337754d4fd86dd3a0e36e0ef1f6ff2cc879c0fd07da5d84485` 가 통과하고 세 session 값을 돌려준다
- 실패: `sig` 대문자, 한 글자 바꾼 `sig`, `v: "1"`, `v: 1.0`, 칸 하나 없음, `child_subagent_id` 가 숫자, 같은 값을 `_fos_ctx` 규칙(`v1`, 도구 이름 …)으로 서명한 `sig` 는 모두 `SESSION_BINDING_REJECTED`

### 5. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/mcp/SubagentSessionEndpointTest.java`

`McpMemoryToolTest` 처럼 `RANDOM_PORT` 와 `HttpClient` 로 실제 필터를 지난다. 서명은 검사 안에서 따로 계산한다(운영 코드로 서명하지 않는다). `McpCallSigner` 에 등록 서명 도우미 `subagentBody(rawToken, parentRoot, parent, child)` 를 더해 쓴다.

| 경우 | 기대 |
| --- | --- |
| 도는 실행 아래 최상위 자식 등록 | `201`, `{"result":"created"}`, 줄 하나 |
| 같은 본문 다시 | `200`, `{"result":"exists"}`, 줄 하나(9번) |
| 같은 자식을 다른 뿌리의 도는 실행 아래로 | `409`, `code` 가 `SESSION_BINDING_CONFLICT`, origin 그대로(10번) |
| 서명 틀림, 모양 틀림 | `403`, `SESSION_BINDING_REJECTED` |
| profile A 토큰으로 profile B 실행의 뿌리 아래 등록(8번) | `403`, `SESSION_BINDING_REJECTED`. 줄이 없다 |
| profile 이 빈 옛 토큰(`McpCallSigner.insertLegacyToken`, `legacy-user-tokens` 가 참인 설정에서도) | `403` |
| 토큰 없음, 모르는 토큰, 폐기한 토큰 | `401` |
| 사용자 JWT 로 부른다 | 등록되지 않는다(`401` 이나 `403`). 줄이 없다 |
| `Origin` 헤더가 있다 | `403` |
| 등록한 뒤 부모 실행을 `SUCCEEDED` 로 바꾸고 그 자식 session 으로 서명한 `memory_read` 를 `/mcp` 로 부른다 | 부모 사용자의 본문(1번의 HTTP 판) |
| `tools/list` 결과 | 등록 경로나 그 이름이 도구로 나오지 않는다 |

옛 토큰 검사는 `McpLegacyTokenTest` 가 설정을 켜는 방식을 따른다.

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.mcp.application.SubagentRegistrationTest' --tests 'com.bifos.assistant.mcp.SubagentSessionEndpointTest' --tests 'com.bifos.assistant.mcp.McpMemoryToolTest'
cd backend && ./gradlew test
grep -rn 'sig\b\|tokenHash' backend/src/main/java/com/bifos/assistant/mcp/presentation/SubagentSessionController.java | grep -i 'log\.'   # 결과가 없어야 한다
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/mcp/application/SubagentRegistration.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpCallContext.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/SubagentSessionController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/infra/AgentTokenAuthenticationFilter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/auth/ControlPlaneJwtFilter.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpCallSigner.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/application/SubagentRegistrationTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/mcp/SubagentSessionEndpointTest.java` | 신규 |
