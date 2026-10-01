# Phase 04. 판정 함수, `connector_action`, 내부 판정 경로

**Execution profile**: deep

## 목표

hook 이 부를 `POST /internal/hermes/connector-policy` 를 만든다. Control Plane 이 호출마다 판정하고 `connector_action` 에 한 줄을 남긴다.
이 phase 에서는 판정이 「승인 필요」 여도 통과로 답하고 기록만 남긴다.

**범위 외**: hook 쪽(phase 05), 승인 줄 저장과 `block`(phase 08), 상시 허락(phase 08).

## 컨텍스트

- 본보기는 `backend/src/main/java/com/bifos/assistant/mcp/presentation/SubagentSessionController.java` 와 `mcp/application/SubagentRegistration.java` 다. 본문을 문자열로 받아 `JsonNode` 로 읽고, `@AuthenticationPrincipal Object principal` 이 `McpPrincipal` 인지 보고, 서명을 확인한다. 거절 까닭은 로그에만 남기고 밖에는 같은 코드와 문구를 낸다
- profile 토큰 인증 경로는 두 곳의 집합에 정확한 경로로 들어 있어야 한다. `mcp/infra/AgentTokenAuthenticationFilter.java` 의 `AGENT_TOKEN_PATHS`(`getServletPath()` 로 견줌)와 `shared/auth/ControlPlaneJwtFilter.java` 의 `UNFILTERED_PATHS`(`getRequestURI()` 로 견줌)다. 경로 변수를 쓰지 않는다
- 서명 헬퍼는 `mcp/application/McpCallContext.java` 의 package-private `hmac(String tokenHash, String text)`, `SIGNATURE`, `isVersionOne(JsonNode)` 다. key 는 토큰 SHA-256 의 소문자 16진수 문자열의 UTF-8 바이트다
- 실행 찾기는 `orchestration/application/SessionOwnerResolver.java` 의 `AgentExecution resolve(String profileName, String rootSessionId, String sessionId)` 다. 찾지 못하면 `ApiException(ErrorCode.MCP_CALL_CONTEXT_INVALID, ...)` 를 던진다. 중지한 실행도 거절한다
- `usage/domain/AgentExecution.java` 의 접근자는 `id()`, `userId()`, `agentId()`, `conversationId()` 다. `conversationId` 는 내부 번호이고 비어 있을 수 있다
- 같은 호출을 알아보는 키의 본보기는 `usage/domain/DelegationKey.java` 다
- 공개 식별자의 본보기는 `chat/domain/Conversation.java` 의 `publicId`(`@UuidGenerator(style = VERSION_7)`, `BINARY(16)`)다
- 끝단 테스트의 본보기는 `backend/src/test/java/com/bifos/assistant/mcp/SubagentSessionEndpointTest.java`(실제 HTTP, `AgentTokenService.issue(...).rawToken()`)와 서명 도우미 `mcp/McpCallSigner.java` 다
- phase 03 이 `ToolRisk`, `ToolApproval`, `ToolPolicy`, `ConnectorToolPolicies`, `ConnectorManifest.schema()`, `ConnectorManifest.tools()` 를 만들었다

**근거 문서**: `docs/connectors.md` 의 「도구 호출 판정」, `docs/data-schema.md` 의 「connector_action」, `docs/flow.md` 의 「커넥터 도구를 부를 때」, `docs/code-architecture.md` 의 `connector` 클래스 표, `docs/adr/ADR-047-커넥터-도구-호출은-profile-plugin-의-hook-이-control-plane-에-물어-판정한다.md`

## 의도 메모

- 패키지 방향은 `mcp → connector → orchestration, usage, agent, hermes` 다. `connector` 는 `mcp` 를 import 하지 않는다. 서비스는 `McpPrincipal` 대신 profile 이름과 session 값을 받는다. `./gradlew archTest` 가 순환을 잡는다. 순환이 생기면 기준 파일을 고치지 말고 멈춰 보고한다
- 판정 함수는 순수 함수다. DB 와 Hermes 를 모른다. 상시 허락 여부는 boolean 인자로 받고 이 phase 에서는 늘 false 를 넘긴다
- 실행이나 연결을 찾지 못한 호출은 줄을 남기지 않는다. `user_id` 와 `agent_id` 를 채울 수 없다. 로그에 까닭의 종류만 남긴다
- 인자 원문을 이 phase 에서는 저장하지 않는다. `args_sha256` 만 남긴다
- 응답의 `message` 는 모델이 읽는다. 내부 이름(Hermes, profile)과 예외 본문을 넣지 않는다

## 작업 항목

### 1. enum 과 판정 함수 (`connector/domain`)

`connector/domain/type/` 에 둔다.

- `ActionDecision`: `ALLOWED, DENIED, NEEDS_APPROVAL`
- `ActionDenyReason`: `POLICY_UNAVAILABLE, NOT_READY, UNDECLARED, RISK_NOT_OPEN, ARGS_TOO_LARGE`
- `ActionStatus`: `PENDING, EXECUTING, SUCCEEDED, FAILED, UNKNOWN, REJECTED, EXPIRED`(이 phase 에서는 칸의 타입으로만 쓴다)

`connector/domain/ToolPolicyDecision.java`:

```java
public record ToolPolicyDecision(ActionDecision decision, ActionDenyReason denyReason, ToolRisk risk, ToolApproval approval) {
    public static final int MAX_ARGS_BYTES = 16 * 1024;

    /** manifest 를 읽지 못했을 때다. */
    public static ToolPolicyDecision policyUnavailable();

    public static ToolPolicyDecision decide(
            ConnectionStatus connectionStatus, int schema, Optional<ToolPolicy> declared, boolean granted, int argsBytes);
}
```

`decide` 의 순서는 `docs/connectors.md` 의 판정 표와 같다.

1. `connectionStatus != READY` → `DENIED`, `NOT_READY`
2. `declared` 가 비었고 `schema == 2` → `DENIED`, `UNDECLARED`
3. `declared` 가 비었고 `schema == 1` → `ToolPolicy(WRITE, REQUIRED, null)` 로 이어서 판정
4. 위험도가 `DESTRUCTIVE` 나 `FINANCIAL` → `DENIED`, `RISK_NOT_OPEN`
5. `argsBytes > MAX_ARGS_BYTES` → `DENIED`, `ARGS_TOO_LARGE`
6. `approval == NONE` → `ALLOWED`
7. `approval == REQUIRED && granted` → `ALLOWED`
8. 그 밖 → `NEEDS_APPROVAL`

거절이 아닌 결과와 4, 5 번 거절은 `risk` 와 `approval` 을 채운다. 1, 2 번과 `policyUnavailable` 은 둘을 비운다.

### 2. `connector/domain/ConnectorAction.java` 와 리포지토리

칸은 `docs/data-schema.md` 의 「connector_action」 표와 같다. `@Getter @Accessors(fluent = true)`, 표 `connector_action`, 유일 제약 `uk_connector_action_dedupe_key (dedupe_key)`, `uk_connector_action_public_id (public_id)`.

- `publicId` 는 `Conversation.publicId` 와 같은 annotation 을 쓴다
- `argsJson`, `resultText` 는 `@Column(columnDefinition = "MEDIUMTEXT")`
- 팩터리 `static ConnectorAction decided(ConnectorConnection connection, AgentExecution origin, String hermesTool, String toolName, ToolPolicyDecision decision, boolean passed, String dedupeKey, String argsSha256, Instant now)`
- `connector/domain/ConnectorActionKey.java`: `record ConnectorActionKey(String value)`, `static ConnectorActionKey of(String profileName, String rootSessionId, String sessionId, String toolCallId)`. `v1-connector`, 네 값을 `\n` 으로 이어 `shared.util.Sha256` 으로 해시한다. 빈 값은 `IllegalArgumentException`

`connector/infra/ConnectorActionRepository.java`: `Optional<ConnectorAction> findByDedupeKey(String dedupeKey)`.
`ConnectorConnectionRepository` 에 `Optional<ConnectorConnection> findByAgentId(Long agentId)` 를 더한다. `agent` 는 `@OneToOne` 이므로 Spring Data 의 속성 경로 이름(`findByAgent_Id` 가 필요한지)을 엔티티를 읽고 맞춘다.

`backend/src/main/resources/db/migration/V42__connector_action.sql` 로 표를 만든다. 관례는 `V38__connector_connection.sql` 을 따른다(`ENGINE = InnoDB DEFAULT CHARSET = utf8mb4`, `DATETIME(6)`). 외래 키는 `fk_connector_action_user`, `fk_connector_action_agent` 둘이다. 색인은 `idx_connector_action_conversation_status (conversation_id, status)` 와 `idx_connector_action_user_created (user_id, created_at)` 다.

### 3. 카탈로그 캐시

`connector/application/ConnectorPolicyProperties.java`: `@Validated @ConfigurationProperties(prefix = "assistant.connector.policy") record ConnectorPolicyProperties(@DefaultValue("60s") Duration catalogTtl)`. 0 이하이면 compact 생성자에서 `IllegalStateException` 이다.
`backend/src/main/resources/application.yml` 의 `assistant` 아래에 `connector.policy.catalog-ttl: 60s` 를 한국어 주석과 함께 더한다.

`connector/application/ConnectorCatalogCache.java`(`@Component`):

- `Optional<ConnectorManifest> find(String connectorId)`. 마지막으로 읽은 지 `catalogTtl` 이 지났으면 `HermesConnectorClient.readCatalog()` 를 다시 읽는다. 읽다가 예외가 나면 그 예외를 그대로 던지고 옛 값을 쓰지 않는다
- 걸러 내는 조건은 `ConnectorConnectionService.readCatalog()` 와 같아야 한다. 그 조건을 `connector/application/ConnectorManifests.java` 의 package-private `static boolean accepted(ConnectorManifest manifest)` 로 빼고 두 곳이 함께 부른다
- 시각은 `Clock` 으로 읽는다. 동시에 여럿이 다시 읽어도 틀리지 않게 `synchronized` 로 둔다. 판정 경로만 쓴다

### 4. `connector/application/ConnectorPolicyService.java`

```java
public ConnectorPolicyAnswer decide(
        String profileName, String rootSessionId, String sessionId, String toolCallId,
        String hermesTool, String toolName, String argsJson)
```

`connector/application/model/ConnectorPolicyAnswer.java`: `record ConnectorPolicyAnswer(boolean allowed, String message, UUID actionId)`.

순서다.

1. `ConnectorActionKey.of(...)` 로 키를 만들고 `findByDedupeKey` 로 찾는다. 있으면 그 줄의 `passed` 와 판정으로 답을 다시 만든다
2. `SessionOwnerResolver.resolve(profileName, rootSessionId, sessionId)`. `ApiException` 이면 `CONTEXT_MESSAGE` 로 막고 줄을 남기지 않는다
3. `connections.findByAgentId(origin.agentId())`. 없거나 `connection.userId()` 가 `origin.userId()` 와 다르거나 그 에이전트의 profile 이름이 `profileName` 과 다르면 `CONTEXT_MESSAGE` 로 막고 줄을 남기지 않는다. 에이전트의 profile 이름 접근자는 `agent/domain/Agent.java` 를 읽고 맞춘다
4. `catalog.find(connection.connectorId())`. 예외이거나 비었으면 `ToolPolicyDecision.policyUnavailable()`
5. 있으면 `ToolPolicyDecision.decide(connection.status(), manifest.schema(), ConnectorToolPolicies.find(manifest, toolName), false, argsJson 의 UTF-8 바이트 수)`
6. `passed = decision != DENIED`. 줄을 저장한다. 저장은 `TransactionTemplate` 안에서 한다. `DataIntegrityViolationException` 이면 `findByDedupeKey` 로 다시 읽어 1번처럼 답한다
7. 답을 만든다. `passed` 가 참이면 `allowed: true`, 빈 `message`. 거짓이면 까닭마다 아래 글이다

| 까닭 | 글 |
| --- | --- |
| 줄을 남기지 않는 거절(`CONTEXT_MESSAGE`) | `이 도구 호출의 실행 맥락을 확인하지 못해 실행하지 않았다.` |
| `POLICY_UNAVAILABLE` | `이 도구의 사용 정책을 지금 확인하지 못해 실행하지 않았다. 잠시 뒤 다시 시도하라고 사용자에게 알린다.` |
| `NOT_READY` | `이 연결이 준비되지 않아 실행하지 않았다. 사용자에게 연결 화면에서 연결을 확인하라고 알린다.` |
| `UNDECLARED` | `이 도구는 사용이 허락되지 않아 실행하지 않았다. 다시 부르지 않는다.` |
| `RISK_NOT_OPEN` | `이 도구는 아직 열리지 않아 실행하지 않았다. 다시 부르지 않는다.` |
| `ARGS_TOO_LARGE` | `인자가 너무 커서 실행하지 않았다. 나눠서 요청한다.` |

`actionId` 는 이 phase 에서 늘 null 이다.

### 5. 요청 검증과 컨트롤러 (`mcp`)

`ErrorCode` 에 `CONNECTOR_POLICY_REJECTED(HttpStatus.FORBIDDEN)` 를 Javadoc(뜻과 ADR-047)과 함께 더한다.

`mcp/application/ConnectorPolicyRequest.java`:

```java
public record ConnectorPolicyRequest(
        String rootSessionId, String sessionId, String toolCallId, String hermesTool, String tool, String argsJson) {
    public static ConnectorPolicyRequest verify(JsonNode body, String tokenHash);
}
```

- `v` 는 `McpCallContext.isVersionOne`. `root_session_id`, `session_id`, `tool_call_id`, `hermes_tool`, `args_json` 은 비지 않은 문자열(각 128자, 128자, 128자, 128자까지. `args_json` 은 길이 제한 없이 받되 64KB 를 넘으면 거절). `tool` 은 문자열이나 JSON null. `sig` 는 `McpCallContext.SIGNATURE`
- `args_json` 은 JSON object 로 읽혀야 한다. 아니면 거절
- 서명할 글은 `v1-connector-policy`, `hermes_tool`, `root_session_id`, `session_id`, `tool_call_id`, `Sha256` 으로 해시한 `args_json` 의 16진수를 `\n` 으로 이은 것이다. 비교는 `MessageDigest.isEqual`
- 실패는 까닭을 `log.warn` 에만 남기고 `ApiException(ErrorCode.CONNECTOR_POLICY_REJECTED, "connector policy request is rejected")` 를 던진다

`mcp/presentation/ConnectorPolicyController.java`: `@PostMapping("/internal/hermes/connector-policy")`. 본문을 `@RequestBody(required = false) String` 으로 받는다. `McpPrincipal` 이 아니면 같은 거절이다. 응답은 `McpDtos` 에 더한 `ConnectorPolicyResponse(String decision, String message, @JsonProperty("action_id") String actionId)` 다. `decision` 은 `allow` 나 `block` 이다. JSON 키 이름이 `action_id` 로 나가는지 테스트로 확인한다. 이 저장소는 Jackson 3(`tools.jackson`)을 쓴다.

`AGENT_TOKEN_PATHS` 와 `UNFILTERED_PATHS` 에 `/internal/hermes/connector-policy` 를 더한다.

### 6. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/connector/ToolPolicyDecisionTest.java`(신규, Spring 없이): 작업 항목 1 의 여덟 갈래를 하나씩. `schema: 1` 에 선언 없는 도구가 `NEEDS_APPROVAL` 과 `WRITE` 인 것, `ALWAYS` 는 `granted` 가 참이어도 `NEEDS_APPROVAL` 인 것을 포함한다.

`backend/src/test/java/com/bifos/assistant/mcp/application/ConnectorPolicyRequestTest.java`(신규, Spring 없이): 고정한 token 해시와 값으로 계산한 서명 벡터 하나를 상수로 두고 통과를 본다. 같은 벡터를 phase 05 의 Python 테스트가 쓴다. 벡터 값은 이 테스트의 상수 이름 `VECTOR_*` 로 둔다. `args_json` 한 글자를 바꾸면 거절, `sig` 를 바꾸면 거절, `args_json` 이 배열이면 거절, `tool` 이 null 이면 통과.

`backend/src/test/java/com/bifos/assistant/mcp/ConnectorPolicyEndpointTest.java`(신규, `SubagentSessionEndpointTest` 와 같은 기반). `@MockitoBean HermesConnectorClient` 로 카탈로그를 물린다. 연결과 `RUNNING` 실행은 리포지토리로 직접 만든다. `McpCallSigner` 에 `policyBody(String rawToken, String hermesTool, String tool, String rootSessionId, String sessionId, String toolCallId, String argsJson)` 를 더한다.

| 상황 | 기대 |
| --- | --- |
| `READY` 연결, `READ` 도구 | 200 `allow`, 줄 하나(`ALLOWED`, `passed` 참, `args_json` 빔, `args_sha256` 채움) |
| `WRITE` 도구 | 200 `allow`, 줄의 `decision` 이 `NEEDS_APPROVAL`, `passed` 참, `status` 빔 |
| `schema: 2` 에 `tool` 이 null | 200 `block`, 줄의 `deny_reason` 이 `UNDECLARED`, `tool_name` 빔, `hermes_tool` 채움 |
| `DESTRUCTIVE` 도구 | `block`, `RISK_NOT_OPEN` |
| 연결이 `PENDING` | `block`, `NOT_READY` |
| 카탈로그 읽기가 예외 | `block`, `POLICY_UNAVAILABLE` |
| 같은 요청을 두 번 | 응답이 같고 줄이 하나 |
| 모르는 session | `block`, 줄 없음 |
| 서명 변조 | 403 `CONNECTOR_POLICY_REJECTED` |
| 토큰 없음 | 401 |
| 다른 profile 의 토큰으로 서명 | `block`, 줄 없음(그 profile 에서 그 session 을 찾지 못한다) |
| 응답 JSON | 키가 `decision`, `message`, `action_id` |

`backend/src/test/java/com/bifos/assistant/connector/ConnectorCatalogCacheTest.java`(신규): 고정 `Clock` 으로 TTL 안에서는 한 번만 읽고 지나면 다시 읽는다. 읽기 예외가 그대로 나온다.

`backend/src/test/java/com/bifos/assistant/connector/ConnectorActionMigrationTest.java`(신규): `ConnectorConnectionMigrationTest` 의 방식으로 V42 까지 올리고 `dedupe_key` 가 겹치는 줄이 거절되는지 본다.

### 7. `docs/` 대조

구현한 칸, 경로, 글이 `docs/connectors.md` 의 「도구 호출 판정」 과 `docs/data-schema.md` 의 「connector_action」 과 같은지 본다. 다르면 멈추고 보고한다.

## 검토 반영

**이 절이 위의 내용과 다르면 이 절을 따른다.**

- **패키지 방향을 뒤집는다. 다른 패키지는 `connector` 를 import 하지 않는다.** `connector → agent → people → mcp` 가 이미 있어 `mcp → connector` 는 순환이다. 컨트롤러와 요청 검증을 `connector` 에 둔다
  - `connector/presentation/ConnectorPolicyController.java`, 응답 record 는 `connector/presentation/ConnectionDtos.java` 에 `ConnectorPolicyResponse` 로 더한다(패키지에 `*Dtos.java` 는 하나다)
  - `connector/application/ConnectorPolicyRequest.java`. `mcp.application.McpPrincipal` 과 `McpCallContext` 를 import 한다
  - `McpCallContext` 의 `hmac`, `SIGNATURE`, `isVersionOne` 을 `public` 으로 연다. Javadoc 에 커넥터 정책 요청이 같은 key 를 쓴다고 한 줄 적는다
  - `mcp` 아래에는 새 파일을 만들지 않는다. 테스트도 `backend/src/test/java/com/bifos/assistant/connector/` 에 둔다. 테스트의 서명 도우미 `mcp/McpCallSigner.java` 는 그대로 고쳐 쓴다
  - `./gradlew archTest` 가 새 위반을 내면 기준 파일을 고치지 않고 멈춰 보고한다
- **hook 이 보낸 `tool` 을 그대로 믿지 않는다.** `connector/domain/HermesToolName.java` 에 `static String of(String server, String tool)` 을 둔다. 규칙은 `docs/hermes/connector-policy.md` 의 「MCP 도구의 등록 이름」 이고 대시보드 plugin 의 `_hermes_tool_name` 과 같은 값을 내야 한다. `ConnectorPolicyService` 는 manifest 를 읽은 뒤 `tool` 이 null 이 아니고 `HermesToolName.of(manifest.mcpServer(), tool)` 이 `hermesTool` 과 다르면 `tool` 을 null 로 읽는다
  - `ConnectorPolicyRequest.verify` 는 `tool` 이 문자열일 때 `^[A-Za-z0-9_.-]{1,128}$` 가 아니면 거절한다
  - `backend/src/test/java/com/bifos/assistant/connector/HermesToolNameTest.java`(신규): `("policy-probe", "write_item")` 이 `mcp__policy_probe__write_item`, `("demo", "a.b")` 가 `mcp__demo__a_b`, 64자를 넘는 입력은 길이 64 이고 `hermes/tests/test_connector_manifest.py` 의 같은 입력과 같은 값(그 값을 Python 으로 계산해 두 테스트에 상수로 둔다)
  - 끝단 테스트에 더한다: `tool` 이 `list_scopes` 인데 `hermes_tool` 이 `mcp__demo__write_note` 이면 `schema: 2` 에서 `UNDECLARED` 로 막힌다

## 검증

```bash
# cwd: 저장소 root
(cd backend && ./gradlew test --tests '*ToolPolicyDecisionTest' --tests '*ConnectorPolicyRequestTest' --tests '*ConnectorPolicyEndpointTest' --tests '*ConnectorCatalogCacheTest' --tests '*ConnectorActionMigrationTest' --tests '*HermesToolNameTest')
(cd backend && ./gradlew test)
(cd backend && ./gradlew qualityCheck)
scripts/check-public-safe.sh
```

- 모두 종료 코드 0. `qualityCheck` 가 새 위반을 내면 기준 파일에 더하지 않고 고친다

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/connector/domain/type/ActionDecision.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/domain/type/ActionDenyReason.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/domain/type/ActionStatus.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/domain/ToolPolicyDecision.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/domain/ConnectorAction.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/domain/ConnectorActionKey.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/infra/ConnectorActionRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/infra/ConnectorConnectionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorPolicyProperties.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorCatalogCache.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorManifests.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorPolicyService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorConnectionService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/model/ConnectorPolicyAnswer.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorPolicyRequest.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/presentation/ConnectorPolicyController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/presentation/ConnectionDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/domain/HermesToolName.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpCallContext.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/infra/AgentTokenAuthenticationFilter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/auth/ControlPlaneJwtFilter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/main/resources/db/migration/V42__connector_action.sql` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/ToolPolicyDecisionTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorCatalogCacheTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorActionMigrationTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorPolicyRequestTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorPolicyEndpointTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/HermesToolNameTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpCallSigner.java` | 수정 |
