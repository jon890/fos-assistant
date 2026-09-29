# Phase 02. MCP 토큰은 profile 을 증명하고 요청자는 부모 실행에서 정한다

**Execution profile**: deep

## 목표

`agent_token` 이 사용자 대신 profile 을 증명하게 하고, `memory_read` 와 `artifact_write` 의 요청자를 서명한 `_fos_ctx` 로 찾은 부모 실행의 사용자로 바꾼다.
같은 GROUP profile 을 사용자 A 와 B 가 함께 써도 각자의 Memory 와 대화에만 닿는 것을 `/mcp` 를 지나는 검사로 고정한다.

**범위 외**: `agent_*` 도구. 옛 설정과 `agent_token.user_id` 칸 삭제(다음 변경). 운영 토큰에 profile 을 채우는 일(`fos-home-infra`). 실제 Hermes 확인.

## 컨텍스트

- phase 01 이 끝나 있어야 한다. `RunSession` 이 있고 흐름의 하위 실행 줄에 `fos-` session 이 적힌다
- 지금 모양
  - `AgentTokenService.authenticate(String raw)` 가 `CurrentUser` 를 돌려주고 `AgentTokenAuthenticationFilter` 가 그것을 인증 주체로 둔다. 필터는 `TOKEN_HASH_ATTRIBUTE` 에 토큰 해시를 싣는다
  - `McpController` 는 `CurrentUserProvider.require()` 로 사용자를 얻어 `McpToolService.readMemory(CurrentUser, Long)`, `writeArtifact(CurrentUser, ArtifactWriteRequest)` 를 부른다. `withoutCallContext` 가 `_fos_ctx` 를 떼어 버린다
  - `McpCallContext.verify(String toolName, JsonNode fosCtx, String tokenHash)` 는 서명을 확인하고 실패하면 `ErrorCode.MCP_CALL_CONTEXT_INVALID` 로 던진다. 서명할 글과 key 는 **바꾸지 않는다**. 운영에 배치 중인 플러그인이 그 계약으로 서명한다
  - `DelegationParentResolver.resolve(CurrentUser user, String rootSessionId)` 는 `AgentExecutionRepository.findTop2ByHermesSessionIdAndStatusAndUserId` 를 쓴다
  - `AgentToken` 은 `userId`(NOT NULL), `tokenHash`, `label` 과 시각 셋. `AgentToken.issue(Long userId, ...)` 로 만든다. 마이그레이션은 `V8__agent_token.sql`, 마지막 번호는 `V28`
  - 관리 API 는 `AgentTokenAdminController`(`/api/v1/admin/agent-tokens`)와 `AgentTokenDtos`. 발급 본문이 `{ userEmail, label }` 이다. 웹 화면은 이 API 를 쓰지 않는다
  - profile 이름 규칙은 `hermes.HermesProfileName.isValid`
  - `CurrentUser` 는 `new CurrentUser(user.id(), user.email(), user.displayName(), user.groupId(), user.role())` 로 만든다
- 설정 클래스 본보기: `memory.application.MemoryProposalProperties`
- 마이그레이션 검사 본보기: `usage/ExecutionDelegationColumnsMigrationTest`(모든 마이그레이션을 H2 MySQL 모드로 돌린다)
- `/mcp` 검사 본보기: `mcp/McpMemoryToolTest`, `mcp/McpArtifactWriteToolTest`(실제 포트로 HTTP 호출). 서명 기대값은 `docs/hermes/delegation.md` 「`_fos_ctx` 계약」 의 표와 `mcp/application/McpCallContextTest`
- e2e 에서 MCP 를 쓰는 곳은 `test/e2e/scenarios/memory.ts` 의 「MCP 토큰은 발급된 사용자만 정하고…」 단계와 `test/e2e/scenarios/artifact.ts` 의 「MCP 도구가 같은 turn 의…」 단계다. 가짜 Hermes 는 `test/e2e/fake-hermes.ts` 의 `writeArtifactViaMcp` 가 run 을 제출받은 뒤 `/mcp` 를 부른다. 그 run 의 `session_id` 는 제출 본문에 있다

**근거 문서**: `docs/adr/ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md`, `docs/flow.md` 의 「MCP 호출의 요청자를 정할 때」 절, `docs/code-architecture.md` 의 「MCP 요청자」 절, `docs/data-schema.md` 의 「agent_token」 절, `docs/hermes/delegation.md` 의 「`_fos_ctx` 계약」 절

## 의도 메모

- 사용자를 토큰에서 꺼내는 길은 옛 토큰의 전환용 하나만 남긴다. profile 이 묶인 토큰에는 어떤 경우에도 `user_id` 를 읽지 않는다
- 부모 실행은 profile 과 뿌리 session 과 `RUNNING` 으로만 찾는다. 사용자로 먼저 거르지 않는다. 그 profile 의 가장 최근 실행을 고르지 않는다
- 거절은 서명 오류, profile 불일치, 부모 없음, 부모 둘 이상, 사용자 없음을 밖에서 나누지 않는다. 도구 결과는 `isError: true` 와 `호출 맥락을 확인할 수 없습니다. 새 대화에서 다시 시도해 주세요.` 하나다. 이유는 로그에만 남기고 토큰 해시와 `sig` 는 적지 않는다
- 도는 실행이 하나도 없으면 로그에 `DELEGATION_CONTEXT_UNAVAILABLE` 을 함께 남긴다. 옛 대화의 압축 교체를 찾는 표시다
- 판정 순서는 셋이다. `params.name` 이 문자열이고 `params.arguments` 가 객체인지 먼저 본다(아니면 기존처럼 `-32602`). 그 다음 `McpCallerResolver` 로 요청자를 정한다. 도구별 인자 검사(`memory_read` 의 `id`, `artifact_write` 의 칸)는 그 뒤다. 순서를 하나로 두면 모든 도구가 같은 길을 쓴다
- 기존 정책은 그대로다. `memory_read` 는 볼 수 없는 항목과 없는 항목을 같은 응답으로 숨기고, `artifact_write` 는 같은 사용자의 다른 대화에 쓰는 것을 허용한다
- 옛 토큰 판정은 인증에서 한다. 설정이 거짓이면 401 이다. 참이면 `McpPrincipal` 의 profile 이 비고 `legacyUserId` 가 찬다
- 옛 토큰을 재현하는 검사는 운영 코드에 옛 발급 경로를 남기지 말고 `JdbcTemplate` 로 행을 넣는다

## Blocked 조건

- `McpCallContext` 의 서명할 글이나 key 를 바꿔야 할 것 같으면 바꾸지 말고 `PHASE_BLOCKED: _fos_ctx 서명 계약 변경이 필요하다` 로 멈춘다

## 작업 항목

### 1. `backend/src/main/resources/db/migration/V29__agent_token_profile.sql` 신규

```sql
ALTER TABLE agent_token ADD COLUMN profile_name VARCHAR(64) NULL;
ALTER TABLE agent_token MODIFY COLUMN user_id BIGINT NULL;
```

머리 주석에 ADR-032, 값을 채우지 않는 까닭(운영 값은 관리 API 로 채운다)을 적는다. 운영 값은 어디에도 적지 않는다.

### 2. `AgentToken`

- `@Column(name = "user_id")` 에서 `nullable = false` 를 뺀다. 접근자 이름은 `legacyUserId()` 로 바꾼다
- `@Column(name = "profile_name", length = 64) private String profileName;` 과 `profileName()`
- `static AgentToken issueFor(String profileName, String tokenHash, String label)`. 옛 `issue(Long userId, ...)` 는 지운다
- `void bindProfile(String profileName)`. 이미 profile 이 있거나 폐기된 토큰이면 `ApiException(VALIDATION_FAILED, ...)`

### 3. `mcp.application` 의 새 타입

- `McpProperties`: `@ConfigurationProperties(prefix = "assistant.mcp")`, `boolean legacyUserTokens`. `application.yml` 의 `assistant:` 아래에 `mcp: legacy-user-tokens: ${ASSISTANT_MCP_LEGACY_USER_TOKENS:false}` 와 전환용이라는 주석
- `McpPrincipal(Long tokenId, String profileName, Long legacyUserId, String tokenHash)` 와 `boolean bound()`(profile 이 있으면 참)
- `McpCaller(CurrentUser user, AgentExecution parent, McpCallContext context)`. 옛 토큰이면 뒤의 둘이 null
- `McpCallerResolver.resolve(McpPrincipal principal, String toolName, JsonNode fosCtx)`
  - 묶인 토큰: `McpCallContext.verify(toolName, fosCtx, principal.tokenHash())` → `DelegationParentResolver.resolve(principal.profileName(), context.rootSessionId())` → `AppUserRepository.findById(parent.userId())` 로 `CurrentUser`
  - 옛 토큰: `legacyUserId` 의 사용자. `_fos_ctx` 는 보지 않는다
  - 어느 단계가 실패해도 `ApiException(MCP_CALL_CONTEXT_INVALID)`

### 4. `AgentTokenService`

- `IssuedToken issue(String profileName, String label)`: `HermesProfileName.isValid` 가 아니면 `VALIDATION_FAILED`
- `AgentToken bindProfile(Long id, String profileName)`: 같은 규칙, 없는 토큰은 기존 `revoke` 와 같은 오류
- `McpPrincipal authenticate(String raw)`: 폐기된 토큰과 없는 토큰은 기존처럼 `UNAUTHENTICATED`. profile 이 빈 토큰은 설정이 거짓이면 `UNAUTHENTICATED`, 참이면 사용자를 확인하고 `log.warn("profile 이 묶이지 않은 옛 MCP 토큰이 쓰였다 tokenId={}", ...)`. 마지막 사용 시각은 전처럼 갱신한다
- `list()` 는 profile 과 옛 토큰의 사용자 메일을 함께 돌려준다. 사용자를 한 번에 읽을 때 `legacyUserId` 가 null 인 토큰은 번호 목록에서 뺀다(`findAllById` 에 null 을 넘기지 않는다). `TokenWithUser` 의 메일은 옛 토큰만 채우고 아니면 null. `IssuedToken` 은 메일 칸을 없애고 profile 을 싣는다

### 5. `AgentTokenAuthenticationFilter`

인증 주체를 `McpPrincipal` 로 둔다. 권한 목록은 `ROLE_MCP` 하나. `TOKEN_HASH_ATTRIBUTE` 는 그대로 싣는다.

### 6. `DelegationParentResolver`

- `AgentExecution resolve(String profileName, String rootSessionId)`
- `AgentExecutionRepository` 의 `findTop2ByHermesSessionIdAndStatusAndUserId` 를 `findTop2ByHermesSessionIdAndStatusAndProfileName` 으로 바꾸고, 실패 이유를 나누려고 `boolean existsByHermesSessionIdAndStatus(String, ExecutionStatus)` 를 더한다
- 하나면 돌려준다. 없을 때 같은 뿌리로 도는 다른 profile 의 실행이 있으면 로그 이유는 「profile 이 다르다」, 아예 없으면 「도는 실행이 없다 DELEGATION_CONTEXT_UNAVAILABLE」, 둘이면 「도는 실행이 둘 이상이다」. 로그에 profile 이름을 적는다. 사용자 번호는 적지 않는다

### 7. `McpController` 와 `McpToolService`

- `handle` 이 `@AuthenticationPrincipal McpPrincipal principal` 을 받는다. `CurrentUserProvider` 의존을 뺀다
- `memory_read`, `artifact_write` 는 원래 인자의 `_fos_ctx` 로 `McpCallerResolver.resolve` 를 먼저 부르고, 실패하면 `McpToolService.invalidContext()` 를 결과로 돌려준다. 그 뒤 `withoutCallContext` 한 인자로 기존 검사를 한다
- `McpToolService.invalidContext()` 는 `result("호출 맥락을 확인할 수 없습니다. 새 대화에서 다시 시도해 주세요.", true)`
- `readMemory(McpCaller caller, Long id)`, `writeArtifact(McpCaller caller, ArtifactWriteRequest request)` 로 바꾼다. 번호 둘을 나란히 받지 않는다. 로그는 `memory read userId={} memoryId={} executionId={}` 이고 `writeArtifact` 의 경고 로그에도 `executionId` 를 더한다. 옛 토큰이면 실행 번호는 null 로 적힌다

### 8. 관리 API

- `AgentTokenDtos.IssueRequest(@NotBlank @Size(max = 64) String profileName, @NotBlank @Size(max = 100) String label)`
- `AgentTokenDtos.BindProfileRequest(@NotBlank @Size(max = 64) String profileName)`
- 응답 `IssuedTokenResponse(id, profileName, label, createdAt, token)`, `TokenResponse(id, profileName, userEmail, label, createdAt, lastUsedAt, revokedAt)`. `userEmail` 은 옛 토큰만 채우고 아니면 null
- `AgentTokenAdminController` 에 `@PutMapping("/{id}/profile")`. 관리자만

### 9. 백엔드 테스트

`backend/src/test/java/com/bifos/assistant/mcp/McpPrincipalTest.java` 신규. 실제 포트의 `/mcp` 를 부른다. 서명 도우미는 테스트 안에 둔다(`HmacSHA256`, key 는 토큰 SHA-256 소문자 16진수 문자열의 UTF-8, 글은 `v1\n<tool>\n<root>\n<session>\n<tool_call_id>`).

준비: 사용자 A, B(같은 그룹), 공유 profile `shared-group` 에 묶은 토큰, 다른 profile `private-a` 에 묶은 토큰. A 와 B 에게 색인 USER Memory 하나씩, 각자의 대화 하나씩.

- 실행 줄은 `orchestration/DelegationParentResolverTest.save` 를 본보기로 `AgentExecution.builder()` 에 `userId`, `conversationId`, `profileName`, `hermesSessionId`, `costMode`, `status(RUNNING)`, `startedAt` 을 넣어 만든다
- 뿌리 session 은 테스트마다 `"fos-" + UUID.randomUUID()` 로 새로 만든다. 상수를 쓰지 않는다
- 테스트 클래스들은 H2 하나를 함께 쓰고 사용자만 지우면 실행 줄이 남는다. `@BeforeEach` 에서 그 테스트가 쓰는 profile(`shared-group`, `private-a`)의 실행 줄을 먼저 지운다. 부모를 사용자로 거르지 않으므로 남은 `RUNNING` 줄이 「둘 이상」 으로 걸린다
- `McpMemoryToolTest` 와 `McpArtifactWriteToolTest` 도 자기 profile 이름을 따로 쓰고 같은 방식으로 준비한다

요구 문서 번호를 그대로 쓴다. 9 와 10(위임 키)은 phase 01 의 `DelegationKeyTest` 가 맡는다. 13 은 더한 것이다.

| 번호 | 시나리오 | 기대 |
| --- | --- | --- |
| 1 | `private-a` 토큰, A 의 도는 실행(`private-a`), A 의 뿌리로 서명 | A 의 Memory 본문, 자기 대화에 `artifact_write` 성공 |
| 2 | `shared-group` 토큰, A 의 실행(`shared-group`, 뿌리 `fos-A`) | A 의 본문 |
| 3 | 같은 토큰, B 의 실행(`shared-group`, 뿌리 `fos-B`) | B 의 본문 |
| 4 | 2 와 3 의 실행이 함께 `RUNNING` 이고 두 뿌리로 번갈아 부른다. 가상 스레드로 나란히도 한 번 부른다 | 호출마다 자기 실행의 사용자 본문. 섞이지 않는다 |
| 5 | 옛 토큰(`JdbcTemplate` 로 `user_id` = A 인 행을 넣고 `bindProfile` 로 `shared-group` 에 묶음)으로 B 의 뿌리를 서명 | B 의 본문. A 의 것이 아니다 |
| 6 | `private-a` 토큰으로 `shared-group` 실행의 뿌리를 서명 / `shared-group` 토큰으로 `private-a` 실행의 뿌리를 서명 | 둘 다 호출 맥락 오류 결과 |
| 7 | A 의 뿌리로 B 의 Memory 번호 / B 의 뿌리로 A 의 Memory 번호 | 없는 항목과 같은 `Memory 항목을 읽을 수 없습니다.` |
| 8 | A 의 뿌리로 B 의 대화에 `artifact_write` | `isError: true`, `결과물을 저장할 수 없습니다.` A 의 다른 대화에는 성공한다 |
| 11 | 옛 대화: 실행 줄의 `hermesSessionId` 가 뿌리 칸이 빈 대화의 `legacy-session` | 그 값으로 서명한 호출이 그 실행의 사용자로 돈다 |
| 12 | 폐기한 토큰, 모르는 토큰, 헤더 없음 | HTTP 401 |
| 13 | Control Plane 이 시작하지 않은 run: 실행 줄이 없는 뿌리(`cron-session-1`)로 올바르게 서명한 호출 | 호출 맥락 오류 결과. 본문이 없다 |

그 밖에 확인한다.

- 묶인 토큰에서 `_fos_ctx` 없음, `sig` 가 틀림, `v` 가 문자열, 모델이 흉내 낸 서명(다른 토큰의 해시로 서명), 끝난 실행의 뿌리, 같은 뿌리로 도는 실행 둘 → 모두 같은 호출 맥락 오류 결과. 응답 본문이 서로 같다
- 모델이 인자에 `user_id`, `profile` 을 더해도 결과가 바뀌지 않는다(`memory_read` 는 모르는 키를 무시하고, `artifact_write` 는 인자 오류)
- 설정이 거짓일 때 profile 이 빈 옛 토큰은 401
- `backend/src/test/java/com/bifos/assistant/mcp/McpLegacyTokenTest.java` 신규: `@SpringBootTest(properties = "assistant.mcp.legacy-user-tokens=true")`. 옛 토큰은 `_fos_ctx` 없이, 그리고 실행 줄이 없는 뿌리로 서명한 `_fos_ctx` 를 붙여도 그 토큰의 사용자로 읽는다(Control Plane 이 시작하지 않은 run 의 전환 동안 동작). 같은 설정에서도 묶인 토큰은 `_fos_ctx` 가 없으면 거절된다

기존 테스트를 새 모델에 맞춘다.

- `McpMemoryToolTest`: 토큰을 profile 로 발급하고 호출마다 도는 실행과 서명을 붙인다. `_fos_ctx_가_붙어도_버리고_그대로_읽는다` 는 「서명이 맞는 `_fos_ctx` 를 떼고 읽는다」 와 「틀린 `_fos_ctx` 는 거절한다」 로 바꾼다. `인증한_요청에_토큰_원문이_아닌_해시를_속성으로_싣는다` 는 유지한다
- `McpArtifactWriteToolTest`: 같은 방식
- `mcp/application/McpToolServiceTest`: 새 `readMemory` 인자에 맞춘다
- `McpMemoryToolTest.관리자만_토큰을_발급하고_목록을_보고_폐기한다`: 발급 본문을 `{ profileName, label }` 로 바꾸고 셋을 더한다. `MEMBER` 의 `PUT /api/v1/admin/agent-tokens/{id}/profile` 은 거절되고 `ADMIN` 의 것은 성공한다. 목록에 옛 토큰(`JdbcTemplate` 로 넣은 것)과 새 토큰이 섞여 있을 때 한 줄마다 `profileName` 이 맞고 `userEmail` 은 옛 토큰만 채워진다
- `AgentTokenServiceTest`: `issue(profileName, label)`. profile 이름 규칙 위반은 `VALIDATION_FAILED`. `bindProfile` 은 빈 토큰만 묶고 두 번째는 거절. 폐기 토큰은 묶지 못한다
- `orchestration/DelegationParentResolverTest`: `resolve(profileName, root)`. 다른 profile 의 도는 실행은 부모가 되지 못한다. 같은 profile 에서 사용자가 다른 실행 둘이 뿌리만 다르면 각자 찾는다
- `backend/src/test/java/com/bifos/assistant/mcp/AgentTokenProfileMigrationTest.java` 신규: `ExecutionDelegationColumnsMigrationTest` 처럼 새 H2 에 Flyway 를 쓰되 `.target("28")` 로 V28 까지만 올리고 옛 행(`user_id` 가 찬 행)을 넣는다. 그 다음 끝까지 올린다. 옛 행은 `user_id` 가 그대로이고 `profile_name` 이 비어 있다. `profile_name` 칸이 있고 `user_id` 가 빈 새 행을 넣을 수 있다

### 10. e2e

- `test/e2e/mcp-context.ts` 신규: `export function signedCallContext(token: string, toolName: string, rootSessionId: string, sessionId: string, toolCallId: string)` 가 `{ v: 1, root_session_id, session_id, tool_call_id, sig }` 를 돌려준다(`node:crypto`, 위 계약). 가짜 Hermes 와 시나리오가 함께 쓴다
- `test/e2e/fake-hermes.ts`: `writeArtifactViaMcp` 가 profile 플러그인처럼 `signedCallContext` 로 `_fos_ctx` 를 붙인다. `root_session_id` 와 `session_id` 는 제출 본문의 `session_id`, `tool_call_id` 는 호출마다 새 값
- `test/e2e/scenarios/artifact.ts`: 토큰을 `{ profileName: DAD_BINDING.profileName, label }` 로 발급한다(`DAD_BINDING` 은 `scenarios/binding.ts`)
- `test/e2e/scenarios/memory.ts`: 「MCP 토큰은 발급된 사용자만 정하고…」 단계를 「profile 토큰만으로는 누구의 본문도 읽지 못한다」 로 바꾼다. 아이 profile 과 아빠 profile 에 묶은 토큰을 발급하고, `_fos_ctx` 없이, 그리고 도는 실행이 없는 뿌리로 서명해 `memory_read` 를 부르면 둘 다 호출 맥락 오류 결과이고 본문이 없다. 사용자별 격리의 e2e 는 phase 03 이 둔다

## 검증

```bash
# cwd: backend/
./gradlew test --tests '*McpPrincipalTest' --tests '*McpLegacyTokenTest' --tests '*McpMemoryToolTest' --tests '*McpArtifactWriteToolTest' --tests '*AgentTokenServiceTest' --tests '*DelegationParentResolverTest' --tests '*AgentTokenProfileMigrationTest' --tests '*McpCallContextTest' --tests '*McpToolServiceTest'
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

아래가 모두 아무것도 찾지 않는다.

```bash
grep -rn "findTop2ByHermesSessionIdAndStatusAndUserId" backend/src/main/java/com/bifos/assistant/mcp backend/src/main/java/com/bifos/assistant/orchestration backend/src/main/java/com/bifos/assistant/usage
grep -rn "currentUser.require()" backend/src/main/java/com/bifos/assistant/mcp
grep -rn "userEmail:" test/e2e/scenarios/memory.ts test/e2e/scenarios/artifact.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V29__agent_token_profile.sql` | 신규 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/domain/AgentToken.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpProperties.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpPrincipal.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpCaller.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpCallerResolver.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/AgentTokenService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/IssuedToken.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/TokenWithUser.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/infra/AgentTokenAuthenticationFilter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/AgentTokenAdminController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/AgentTokenDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/DelegationParentResolver.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/infra/AgentExecutionRepository.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpPrincipalTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpLegacyTokenTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/mcp/AgentTokenProfileMigrationTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryToolTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpArtifactWriteToolTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/AgentTokenServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/application/McpToolServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/DelegationParentResolverTest.java` | 수정 |
| `test/e2e/mcp-context.ts` | 신규 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/e2e/scenarios/artifact.ts` | 수정 |
| `test/e2e/scenarios/memory.ts` | 수정 |
