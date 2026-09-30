# Phase 01. 옛 MCP 토큰 경로와 agent_token.user_id 를 지운다

**Execution profile**: deep

## 목표

profile 이 빈 옛 MCP 토큰을 사용자로 돌리던 경로와 그 설정과 `agent_token.user_id` 칸을 지운다.
토큰은 profile 만 증명하고, 사용자가 걸린 모든 MCP 호출의 `McpCaller` 는 `user`, `originExecution`, `context` 를 늘 갖는다.
`agent_delegate` 가 이 불변식 위에서 부모와 실행 나무를 정하므로 그 전에 끝낸다.

**범위 외**: `agent_delegate`, `agent_stop`. 운영 환경 변수 `ASSISTANT_MCP_LEGACY_USER_TOKENS` 를 지우는 일은 `fos-home-infra` 가 배포 뒤에 한다. 이 저장소에는 그 절차를 적지 않는다.

## 컨텍스트

운영의 모든 MCP 토큰이 profile 에 묶였고 옛 토큰 경고 로그가 0 건인 것을 확인했다(2026-09-30). 이 phase 는 그 전제 위에서 코드를 지운다.

지금 코드의 옛 경로는 이렇다. 구현 전에 각 파일을 읽어 이 모양이 맞는지 본다.

| 자리 | 지금 모양 |
| --- | --- |
| `backend/src/main/resources/application.yml` | `assistant.mcp.legacy-user-tokens: ${ASSISTANT_MCP_LEGACY_USER_TOKENS:false}` 와 그 위 주석. `assistant.mcp` 아래 다른 키는 없다 |
| `mcp.application.McpProperties` | `@ConfigurationProperties(prefix = "assistant.mcp") record McpProperties(boolean legacyUserTokens)`. `AssistantApplication` 의 `@ConfigurationPropertiesScan` 이 찾는다. 이 설정만 담는다 |
| `mcp.application.McpPrincipal` | `record McpPrincipal(Long tokenId, String profileName, Long legacyUserId, String tokenHash)` 와 `bound()`, `toString()` |
| `mcp.domain.AgentToken` | `@Column(name = "user_id") private Long userId`, `legacyUserId()`, `bindProfile(String)` |
| `mcp.application.AgentTokenService` | `McpProperties`, `AppUserRepository` 를 받는다. `bindProfile(Long, String)`, `list()` 가 `List<TokenWithUser>` 를 돌려주며 옛 토큰에만 메일을 붙인다. `authenticate(String)` 가 profile 이 빈 토큰을 설정에 따라 사용자로 돌린다 |
| `mcp.application.TokenWithUser` | `record TokenWithUser(AgentToken token, String userEmail)`. 목록에만 쓴다 |
| `mcp.presentation.AgentTokenAdminController` | `PUT /api/v1/admin/agent-tokens/{id}/profile` 의 `bindProfile` |
| `mcp.presentation.AgentTokenDtos` | `BindProfileRequest`, `TokenResponse(Long id, String profileName, String userEmail, String label, Instant createdAt, Instant lastUsedAt, Instant revokedAt)` 와 `from(TokenWithUser)` |
| `mcp.application.McpCallerResolver` | `resolve` 가 `!principal.bound()` 이면 옛 토큰의 사용자로 `new McpCaller(user, null, null)` 을 만든다. `resolveWithOrigin` 이 그런 호출을 거절한다. `AppUserRepository` 로 origin 실행의 사용자를 읽는 `findUser` 는 계속 쓴다 |
| `mcp.application.McpCaller` | `record McpCaller(CurrentUser user, AgentExecution originExecution, McpCallContext context)`. `executionId()` 가 origin 이 없으면 null 을 돌려준다 |
| `mcp.presentation.McpController` | `private record Tool(boolean requiresOrigin, ToolHandler handler)`. `agent_list`, `agent_status` 만 `true` 이고 `call` 이 그 값으로 `resolveWithOrigin` 과 `resolve` 를 고른다 |
| `mcp.presentation.SubagentSessionController` | `if (!mcp.bound()) throw reject("profile 이 빈 옛 토큰이다");` |
| `orchestration.application.AgentDelegationService` | `list`, `status` 가 `requireOrigin(caller)` 로 세 값이 비었는지 다시 본다 |

`agent_token` 은 V8 이 `user_id BIGINT NOT NULL` 로 만들었고 V29 가 NULL 을 허용하게 바꿨다. `user_id` 에 걸린 FK 와 색인은 없다(V8, V29 를 읽어 확인했다). 운영 스키마 판은 V34 다.

마이그레이션 검사(`*MigrationTest`)는 모든 마이그레이션을 H2 의 MySQL 모드에서 돌린다. 테스트의 스키마는 엔티티로 만들고(`ddl-auto: create-drop`), 운영과 `test/e2e` 는 Flyway 가 만든 스키마를 엔티티로 검증한다(`ddl-auto: validate`). `test/e2e` 도 H2 메모리 DB 로 Flyway 를 돌리므로 V35 와 스키마 검증을 함께 지난다.

**근거 문서**: `docs/adr/ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md` 의 「옛 토큰에서 옮겨 가는 길」, `docs/adr/ADR-037-hermes-하위-에이전트-session-의-주인은-만들-때-등록한-줄로-정한다.md` 의 「이 결정 뒤에 할 일」, `docs/flow.md` 의 「MCP 호출의 요청자를 정할 때」 아래 「갈리는 지점」, `docs/data-schema.md` 의 「agent_token」, `docs/code-architecture.md` 의 「MCP 요청자」 와 「토큰 관리 경로」, `docs/hermes/delegation.md` 의 「모든 토큰이 profile 에 묶여 있다」. 이 문서들은 이미 이 phase 뒤의 모양으로 고쳐져 있다. 코드를 그 문서에 맞춘다.

## 의도 메모

- **불변식은 타입이 지킨다.** `McpCaller` 의 compact constructor 가 세 값 중 하나라도 null 이면 `NullPointerException` 을 던진다. `McpPrincipal` 도 `profileName`, `tokenHash` 가 null 이면 던진다. 그래서 `AgentDelegationService.requireOrigin` 같은 호출자 쪽 재확인은 지운다. 호출자마다 null 을 다시 보면 한 곳이 빠질 때 조용히 옛 동작으로 돈다
- **`resolve` 와 `resolveWithOrigin` 을 `resolve` 하나로 합친다.** 옛 토큰이 없으면 둘의 결과가 같다. `McpController.Tool` 의 `requiresOrigin` 도 필요 없어져 `handlers` 를 `Map<String, ToolHandler>` 로 바꾼다
- **`PUT /api/v1/admin/agent-tokens/{id}/profile` 을 지운다.** V35 가 profile 이 빈 폐기 안 된 토큰이 있으면 실패하므로, 배포된 뒤에는 묶을 수 있는 토큰(profile 이 비고 폐기 안 됨)이 없다. 남기면 늘 `VALIDATION_FAILED` 만 돌려주는 경로가 된다. `AgentToken.bindProfile`, `AgentTokenService.bindProfile`, `BindProfileRequest` 도 함께 지운다
- **목록의 `userEmail` 을 지운다.** 옛 토큰에만 채우던 값이고 `user_id` 칸이 사라진다. `TokenWithUser` 를 지우고 `list()` 는 `List<AgentToken>` 을 돌려준다. 웹 화면은 이 목록을 쓰지 않는다(`web/src` 에 `agent-tokens` 가 없다)
- **V35 는 Java 마이그레이션이다.** 칸을 지우기 전에 `profile_name IS NULL AND revoked_at IS NULL` 인 줄을 세고, 한 줄이라도 있으면 아무 DDL 도 하지 않고 예외를 던진다. SQL 만으로는 H2 와 MySQL 에서 함께 「조건이 맞으면 실패」 를 쓸 수 없다. `V23__ConversationPublicId` 가 같은 까닭으로 Java 로 쓴 본보기다
  - 기각: 기동 검사(ApplicationRunner). Flyway 가 먼저 돌아 칸을 지운 뒤에야 검사가 돌아, 그 토큰이 누구의 것이었는지 잃은 뒤 알게 된다
  - 기각: `profile_name` 을 NOT NULL 로 바꾸기. 폐기된 옛 토큰이 이력으로 NULL 을 가질 수 있고, 그 줄 때문에 배포가 실패한다. 폐기된 줄을 지우는 것은 이력을 버리는 일이라 하지 않는다. 폐기 안 된 줄만 V35 가 막고, 그 뒤로 profile 없이 줄을 만드는 코드는 없다
  - MySQL 은 DDL 을 트랜잭션으로 되돌리지 못해 실패한 V35 가 schema history 에 실패로 남는다. 검사가 DDL 전에 던지므로 스키마는 바뀌지 않는다. 그 기록을 정리하는 운영 절차는 `fos-home-infra` 가 갖는다
- `authenticate` 는 토큰이 있고, 폐기되지 않았고, `profileName() != null` 일 때만 통과시킨다. profile 이 빈 줄은 경고 로그를 지금 코드의 문구 그대로(`profile 이 묶이지 않은 옛 MCP 토큰을 거절했다 tokenId={}`) 남기고 `UNAUTHENTICATED` 로 거절한다. `markUsed()` 는 통과한 토큰에만 부른다
- 이미 적용된 마이그레이션 파일(V8, V29)은 고치지 않는다. V29 의 주석이 옛 설정을 말해도 둔다

## Blocked 조건

- `backend/src/main/resources/db/migration/` 이나 `backend/src/main/java/db/migration/` 에 V35 가 이미 있다 → `PHASE_BLOCKED: V35 번호를 다른 변경이 이미 썼다`

## 작업 항목

### 1. 설정 제거

- `backend/src/main/resources/application.yml`: `assistant.mcp:` 블록(주석 두 줄과 `legacy-user-tokens` 줄)을 지운다
- `backend/src/main/java/com/bifos/assistant/mcp/application/McpProperties.java` 를 지운다

### 2. 토큰 엔티티와 서비스

- `AgentToken`: `userId` 필드, `legacyUserId()`, `bindProfile(String)` 를 지운다. 클래스 Javadoc 의 `user_id` 문장을 「토큰은 profile 만 증명하고 사용자를 갖지 않는다(ADR-032)」 로 바꾼다. 쓰지 않게 된 `ApiException`, `ErrorCode` import 를 지운다
- `AgentTokenService`:
  - 필드 `users`, `properties` 와 `bindProfile` 을 지운다
  - `list()` 는 `public List<AgentToken> list() { return tokens.findAll(); }`
  - `authenticate(String raw)` 는 폐기 확인 뒤 `if (token.profileName() == null) { log.warn(...); throw UNAUTHENTICATED; }`, `token.markUsed()`, `return new McpPrincipal(token.id(), token.profileName(), tokenHash)`
  - 클래스와 메서드 Javadoc 에서 옛 토큰과 설정을 말하는 문장을 지우고 위 규칙으로 쓴다
- `TokenWithUser.java` 를 지운다
- `McpPrincipal`: `record McpPrincipal(Long tokenId, String profileName, String tokenHash)`. compact constructor 에서 `Objects.requireNonNull(profileName, "profileName")`, `Objects.requireNonNull(tokenHash, "tokenHash")`. `bound()` 를 지우고 `toString()` 은 `"McpPrincipal[tokenId=" + tokenId + ", profileName=" + profileName + "]"`

### 3. 관리 API

- `AgentTokenAdminController`: `bindProfile` 메서드와 `PutMapping`, `TokenWithUser`, `BindProfileRequest` import 를 지운다. `list()` 는 `tokens.list().stream().map(TokenResponse::from).toList()`
- `AgentTokenDtos`: `BindProfileRequest` 를 지운다. `TokenResponse(Long id, String profileName, String label, Instant createdAt, Instant lastUsedAt, Instant revokedAt)` 와 `static TokenResponse from(AgentToken token)`. `TokenWithUser` import 를 `AgentToken` 으로 바꾼다

### 4. 요청자 판정

- `McpCaller`: compact constructor 에서 세 값을 `Objects.requireNonNull` 로 확인한다. `executionId()` 는 `originExecution.id()`. Javadoc 에서 「옛 토큰이면 null」 을 지우고 「셋은 늘 있다. 사용자가 걸린 MCP 호출은 이 셋 없이 도구에 닿지 못한다」 를 적는다
- `McpCallerResolver`:
  - `resolve(McpPrincipal principal, String toolName, JsonNode fosCtx)` 에서 `!principal.bound()` 분기를 지운다. `principal == null` 거절, `McpCallContext.verify`, `owners.resolve`, origin 실행의 사용자 읽기 순서는 그대로다
  - `resolveWithOrigin` 을 지운다
  - 클래스 Javadoc 에서 옛 토큰 문단을 지운다
- `McpController`: `Tool` record 를 지우고 `handlers` 를 `Map<String, ToolHandler>` 로 바꾼다. `call` 은 `callers.resolve(principal, toolName, fosCtx)` 만 부르고 `handler.handle(caller, id, withoutCallContext(arguments))` 로 넘긴다. `call` 의 Javadoc 에서 「`agent_*` 는 origin 실행이 없는 옛 토큰의 호출도 …」 문장을 지운다
- `SubagentSessionController`: `!mcp.bound()` 검사와 그 주석을 지운다. `principal instanceof McpPrincipal` 검사는 둔다
- `AgentDelegationService`: `requireOrigin` 을 지우고 `list` 는 `agents.readableBy(caller.user())`, `status` 는 `caller.originExecution().treeRootId()` 와 `caller.user().id()` 를 바로 쓴다. 클래스 Javadoc 에서 「origin 실행이 없는 옛 토큰의 호출은 여기 오지 않는다」 는 뜻의 문장을 「`McpCaller` 는 요청자와 origin 실행을 늘 갖는다」 로 바꾼다. `requireOrigin` 의 Javadoc 은 메서드와 함께 사라진다

### 5. V35 마이그레이션

`backend/src/main/java/db/migration/V35__DropAgentTokenUserId.java` 를 `V23__ConversationPublicId` 의 모양(`package db.migration;` 과 `java.sql`, `org.flywaydb.core.api.migration` import)으로 만든다.

```java
public class V35__DropAgentTokenUserId extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        long unbound;
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT COUNT(*) FROM agent_token WHERE profile_name IS NULL AND revoked_at IS NULL")) {
            rows.next();
            unbound = rows.getLong(1);
        }
        if (unbound > 0) {
            throw new IllegalStateException("profile 이 묶이지 않은 폐기 안 된 MCP 토큰이 " + unbound
                    + " 개 있어 agent_token.user_id 를 지우지 않는다. 묶거나 폐기한 뒤 다시 배포한다");
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE agent_token DROP COLUMN user_id");
        }
    }
}
```

클래스 Javadoc 에 왜 칸을 지우는지(토큰은 profile 만 증명한다, ADR-032), 왜 먼저 세는지(누구의 토큰인지 잃기 전에 배포를 멈춘다), 왜 Java 인지(H2 와 MySQL 에서 함께 쓸 조건부 실패가 SQL 에 없다), 폐기된 옛 줄은 남긴다는 것을 한국어로 적는다.

### 6. 테스트 헬퍼

`backend/src/test/java/com/bifos/assistant/mcp/McpCallSigner.java` 의 `insertLegacyToken(JdbcTemplate, Long userId, String rawToken, String label)` 을 `insertUnboundToken(JdbcTemplate jdbc, String rawToken, String label)` 으로 바꾼다. `INSERT INTO agent_token (token_hash, label, created_at) VALUES (?, ?, ?)` 로 profile 이 빈 줄을 넣고 번호를 돌려준다. Javadoc 은 「V35 전에 남았을 수 있는 profile 없는 줄을 재현한다. 운영 코드에는 이런 줄을 만드는 길이 없다」.

### 7. 옛 토큰 테스트를 불변식 테스트로 바꾼다

- `McpLegacyTokenTest.java`, `SubagentSessionLegacyTokenTest.java` 를 지운다. 둘 다 `assistant.mcp.legacy-user-tokens=true` 로 옛 경로가 도는 것을 확인하던 검사다
- 새 `backend/src/test/java/com/bifos/assistant/mcp/McpCallerInvariantTest.java` (`@SpringBootTest(webEnvironment = RANDOM_PORT)`, `@ActiveProfiles("test")`). `McpLegacyTokenTest` 의 `toolCall`, `send`, `body` 도우미 모양을 따른다. 그 파일을 지우기 전에 읽어 둔다
  - profile 이 빈 토큰(`insertUnboundToken`)으로 `tools/list` 와 네 도구(`memory_read`, `artifact_write`, `agent_list`, `agent_status`)를 `_fos_ctx` 없이, 그리고 그 토큰으로 서명한 `_fos_ctx`(`McpCallSigner.context(token, 도구 이름, "cron-session-1")`) 를 붙여 부른다. 모두 HTTP 401 이고, 그 줄의 `last_used_at` 은 null 로 남는다
  - 같은 profile 이 빈 토큰으로 `POST /internal/hermes/session-bindings/subagent` 를 `McpCallSigner.subagentBody` 로 부르면 401 이다. 요청 모양은 `SubagentSessionEndpointTest.register` 를 따른다
  - profile 에 묶인 토큰(`tokens.issue(...)`)으로 네 도구를 `_fos_ctx` 없이 부르면 모두 HTTP 200 에 `isError: true` 와 「호출 맥락을 확인할 수 없습니다. 새 대화에서 다시 시도해 주세요.」 다. `agent_status` 인자는 `{"execution_id": 1}`, `artifact_write` 인자는 빈 객체로 둔다. 요청자 판정이 인자 검사보다 먼저이므로 인자 모양과 무관하게 같은 결과다
- 새 `backend/src/test/java/com/bifos/assistant/mcp/application/McpCallerTest.java` (단위 검사)
  - `new McpCaller(null, execution, context)`, `new McpCaller(user, null, context)`, `new McpCaller(user, execution, null)` 이 모두 `NullPointerException` 이다. `execution` 은 `mock(AgentExecution.class)` 다
  - 세 값이 있으면 `executionId()` 가 `execution.id()` 다
  - `new McpPrincipal(1L, null, "hash")` 와 `new McpPrincipal(1L, "p", null)` 이 `NullPointerException` 이고, `toString()` 에 토큰 해시가 없다

### 8. 기존 테스트 고치기

- `AgentTokenServiceTest`:
  - `bindProfile` 을 부르는 네 검사(`옛_토큰은_한_번만_묶이고_…`, `새로_발급한_토큰도_…`, `폐기한_옛_토큰은_묶지_못한다`, `묶을_profile_이름도_…`)를 지운다
  - `saved.legacyUserId()`, `principal.legacyUserId()` 단언을 지운다
  - `설정이_거짓이면_profile_이_빈_옛_토큰은_인증하지_않는다` 를 `profile_이_빈_토큰은_인증하지_않고_사용_시각을_남기지_않는다` 로 바꾸고 `insertUnboundToken(jdbc, raw, "unbound")` 를 쓴다
  - 클래스 Javadoc 에서 「옛 토큰만 한 번 묶이는지」 를 지운다. `admin` 과 `users` 를 더 쓰지 않으면 지운다
- `McpPrincipalTest`: `옛_사용자로_발급한_토큰이라도_묶인_뒤에는_토큰의_사용자를_보지_않는다` 와 `설정이_거짓이면_profile_이_빈_옛_토큰은_401_이다` 를 지운다. 앞의 것은 묶기가 없어졌고 뒤의 것은 `McpCallerInvariantTest` 가 넓혀 확인한다. 쓰지 않게 된 import 를 지운다
- `SubagentSessionEndpointTest`: `기본_설정에서_profile_이_빈_옛_토큰은_인증에서_401_이다` 를 지운다. `McpCallerInvariantTest` 가 확인한다
- `McpMemoryToolTest.관리자만_토큰을_발급하고_목록을_보고_폐기한다`: `legacyId` 줄 삽입, 옛 줄 단언, `PUT …/profile` 단언을 지운다. 목록 길이는 `hasSize(2)`(이 검사의 `dadToken` 과 새로 발급한 토큰)다. 목록의 각 줄에 `userEmail` 키가 없음을 `has("userEmail")` 이 거짓인 것으로 단언한다
- `McpToolServiceTest`: `옛_토큰의_호출은_실행_번호를_null_로_남긴다` 를 지운다
- `AgentTokenProfileMigrationTest`: 두 번째 `Flyway.configure()...migrate()` 에 `.target("34")` 를 더한다. 이 검사는 V29 뒤의 `user_id` 를 읽고 폐기 안 된 profile 없는 줄을 넣으므로, 최신까지 가면 V35 가 실패한다. 클래스 Javadoc 에 그 까닭을 한 줄 더한다
- 새 `backend/src/test/java/com/bifos/assistant/mcp/AgentTokenUserIdDropMigrationTest.java`. `AgentTokenProfileMigrationTest` 의 H2 URL 과 Flyway 설정 모양을 따른다
  - 정상: `target("34")` 까지 올린 뒤 profile 이 묶인 줄 하나와 profile 이 비고 `revoked_at` 이 채워진 줄 하나를 넣는다. 최신까지 올리면 성공하고, 두 줄이 남으며, `INFORMATION_SCHEMA.COLUMNS` 에 `agent_token` 의 `user_id` 칸이 없다(H2 는 이름을 대문자로 둘 수 있으니 `UPPER(TABLE_NAME) = 'AGENT_TOKEN' AND UPPER(COLUMN_NAME) = 'USER_ID'` 로 센다)
  - 실패: `target("34")` 까지 올린 뒤 profile 이 비고 폐기 안 된 줄을 넣는다. 최신까지 올리면 `FlywayException` 이 나고, 메시지 사슬에 「profile 이 묶이지 않은 폐기 안 된 MCP 토큰이 1 개」 가 들어 있으며, `user_id` 칸이 그대로 있다

## 검증

```bash
# cwd: backend/
./gradlew test --tests 'com.bifos.assistant.mcp.*' --tests 'com.bifos.assistant.orchestration.*'
```

기대: 실패 0. `McpCallerInvariantTest`, `McpCallerTest`, `AgentTokenUserIdDropMigrationTest`, `AgentTokenProfileMigrationTest`, `AgentTokenServiceTest`, `McpMemoryToolTest`, `McpPrincipalTest`, `SubagentSessionEndpointTest`, `McpToolServiceTest` 가 이 범위에 든다.

그 뒤 저장소 root 의 `AGENTS.md` 「확인」 여섯 명령을 적힌 순서대로 모두 돌린다.

```bash
cd backend && ./gradlew test
cd web && pnpm typecheck && pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
```

`pnpm build` 가 요구하는 자리표시자 환경 변수는 `web/AGENTS.md` 에 있다. 새 워크트리면 `web` 에서 `pnpm install --frozen-lockfile` 을 먼저 한다.

옛 경로가 남지 않았는지 본다. 아래 명령은 출력이 없어야 한다(`db/migration/V29__agent_token_profile.sql` 의 주석은 고치지 않으므로 뺀다).

```bash
# cwd: 저장소 root
git grep -n -E 'legacyUserTokens|legacy-user-tokens|LEGACY_USER_TOKENS|legacyUserId|resolveWithOrigin|TokenWithUser|BindProfileRequest|requiresOrigin|insertLegacyToken|McpProperties' -- backend/src ':!backend/src/main/resources/db/migration/V29__agent_token_profile.sql'
git grep -n 'bound()' -- backend/src/main/java/com/bifos/assistant/mcp
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpProperties.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/TokenWithUser.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/AgentTokenService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpPrincipal.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpCaller.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpCallerResolver.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/domain/AgentToken.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/AgentTokenAdminController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/AgentTokenDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/SubagentSessionController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentDelegationService.java` | 수정 |
| `backend/src/main/java/db/migration/V35__DropAgentTokenUserId.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpCallSigner.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpLegacyTokenTest.java` | 삭제 |
| `backend/src/test/java/com/bifos/assistant/mcp/SubagentSessionLegacyTokenTest.java` | 삭제 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpCallerInvariantTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/mcp/application/McpCallerTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/mcp/AgentTokenUserIdDropMigrationTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/mcp/AgentTokenProfileMigrationTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/AgentTokenServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpPrincipalTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/SubagentSessionEndpointTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryToolTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/application/McpToolServiceTest.java` | 수정 |
