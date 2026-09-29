# Phase 02. 하위 에이전트 session 등록 표와 origin 실행 판정

**Execution profile**: deep

## 목표

`hermes_session_binding` 표와 그것을 쓰는 두 서비스를 만든다. 하나는 등록 한 줄을 적고(`SubagentSessionRegistrar`), 하나는 MCP 호출의 origin 실행을 정한다(`SessionOwnerResolver`).
`McpCallerResolver` 가 `SessionOwnerResolver` 를 쓰게 해, 부모 FOS 실행이 끝난 뒤에도 등록된 하위 에이전트의 `memory_read`, `artifact_write` 가 origin 실행의 사용자로 돈다.

**범위 외**: HTTP 등록 경로와 그 서명 확인(phase 03), 가짜 Hermes 검사(phase 04). 하위 에이전트 몫의 `agent_execution` 줄은 만들지 않는다. `agent_*` 도구는 이 plan 밖이다.

## 컨텍스트

- 판정 순서와 등록할 때 부모 푸는 순서는 `docs/adr/ADR-037-hermes-하위-에이전트-session-의-주인은-만들-때-등록한-줄로-정한다.md` 의 「판정 순서」, 「등록할 때 부모 풀기」 가 정한다. 이 phase 는 그 두 절을 그대로 코드로 옮긴다
- 표의 칸과 제약은 `docs/data-schema.md` 의 「hermes_session_binding」 절이 정한다. 외래 키를 두지 않는다
- 지금 판정: `backend/src/main/java/com/bifos/assistant/mcp/application/McpCallerResolver.java` 의 `resolve(McpPrincipal, String toolName, JsonNode fosCtx)` 가 `McpCallContext.verify` 뒤 `DelegationParentResolver.resolve(principal.profileName(), context.rootSessionId())` 를 부른다
- `backend/src/main/java/com/bifos/assistant/orchestration/application/DelegationParentResolver.java` 의 `resolve(String profileName, String rootSessionId)` 는 `(hermes_session_id = root, status = RUNNING, profile_name)` 줄이 정확히 하나일 때 그 `AgentExecution` 을 돌려주고, 아니면 `ApiException(ErrorCode.MCP_CALL_CONTEXT_INVALID, "call context is invalid")` 를 던진다. 이 클래스는 바꾸지 않고 그대로 부른다
- `McpCallContext` 는 `record McpCallContext(String rootSessionId, String sessionId, String toolCallId)` 이다
- `AgentExecution` 은 `usage.domain` 에 있고 `@Getter` 라 `id()`, `userId()`, `profileName()`, `hermesSessionId()`, `status()` 를 읽는다. 저장소는 `usage.infra.AgentExecutionRepository`
- 오류 코드: `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java`. `MCP_CALL_CONTEXT_INVALID(HttpStatus.FORBIDDEN)` 가 있다. 이 phase 에서 `SESSION_BINDING_REJECTED(HttpStatus.FORBIDDEN)`, `SESSION_BINDING_CONFLICT(HttpStatus.CONFLICT)` 를 더한다. 응답 모양은 `GlobalExceptionHandler.handleApi` 의 `{code, message}` 다
- 마이그레이션 번호는 **V30** 이다. 다른 plan 이 V31 부터 쓴다. 마이그레이션은 H2 MySQL 모드에서도 돈다(`backend/AGENTS.md` 「엔티티와 마이그레이션은 따로 논다」)
- 검사 도우미 `backend/src/test/java/com/bifos/assistant/mcp/McpCallSigner.java` 의 `clearRuns(JdbcTemplate, List<String>)` 는 그 profile 의 `agent_execution` 줄을 지운다. 등록 줄도 같은 profile 로 지워야 다른 검사의 `UNIQUE (profile_name, session_id)` 와 판정에 걸리지 않는다

**근거 문서**: `docs/adr/ADR-037-hermes-하위-에이전트-session-의-주인은-만들-때-등록한-줄로-정한다.md`, `docs/data-schema.md` 의 「hermes_session_binding」 절, `docs/flow.md` 의 「MCP 호출의 요청자를 정할 때」 와 그 아래 「하위 에이전트 session 을 등록할 때」 절, `docs/code-architecture.md` 의 「MCP 요청자」 절

## 의도 메모

- **origin 실행의 상태를 판정에 쓰지 않는다.** 등록이 있으면 `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED` 모두 통과한다. 최근 실행이나 최근 turn 을 고르는 질의를 만들지 않는다
- **등록이 없고 `sessionId != rootSessionId` 면 거절한다.** 등록이 빠진 하위 에이전트와 `compression.in_place: false` 로 교체된 최상위 session 을 나눌 수 없어 추측하지 않는다(ADR-037 「감당할 것」)
- 등록의 `root_session_id` 가 서명한 뿌리와 다르면 거절한다
- 판정 실패는 모두 지금과 같은 `MCP_CALL_CONTEXT_INVALID` 하나다. 이유는 로그에만 남기고 사용자 번호와 session 값은 적지 않는다(`DelegationParentResolver.reject` 와 같은 모양)
- 등록 실패는 `SESSION_BINDING_REJECTED` 하나로 묶는다. 다른 origin 으로 이미 등록된 경우만 `SESSION_BINDING_CONFLICT` 다. 이유는 로그에만 남긴다
- `child_session_id` 가 `parent_root_session_id` 와 같거나, 그 profile 의 `agent_execution.hermes_session_id` 로 이미 쓰이면 등록을 거절한다. 최상위 session 에 등록이 생기면 판정이 등록을 먼저 보므로 뒤 turn 의 호출이 앞 turn 에 묶인다
- `child_session_id` 와 `parent_session_id` 가 같으면 거절한다
- 같은 origin 의 재등록은 줄을 새로 만들지 않고 `EXISTS` 다. 두 요청이 동시에 와서 유일 제약에 걸리면 먼저 저장된 줄을 다시 읽어 같은 규칙으로 판정한다. `TransactionTemplate` 트랜잭션 하나에서 `saveAndFlush` 하고, `DataIntegrityViolationException` 이면 그 트랜잭션 밖에서 다시 읽는다. `TransactionTemplate` 으로 트랜잭션을 짧게 나누는 선례는 `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` 다. 메서드 전체에 `@Transactional` 을 두면 유일 제약 위반이 그 트랜잭션을 롤백 전용으로 만들어 다시 읽지 못한다
- 새 표를 JVM 메모리 Map 으로 흉내 내지 않는다. 판정은 요청마다 저장소를 읽는다. Control Plane 이 다시 떠도 같은 결과가 나와야 한다
- `orchestration` 이 `mcp` 를 import 하지 않는다. `SessionOwnerResolver` 는 문자열 셋을 받는다

## 작업 항목

### 1. `backend/src/main/resources/db/migration/V30__hermes_session_binding.sql`

첫 줄에 ADR-037 을 가리키는 주석을 두고 표를 만든다.

```sql
CREATE TABLE hermes_session_binding (
    id BIGINT NOT NULL AUTO_INCREMENT,
    profile_name VARCHAR(64) NOT NULL,
    session_id VARCHAR(128) NOT NULL,
    user_id BIGINT NOT NULL,
    origin_execution_id BIGINT NOT NULL,
    root_session_id VARCHAR(128) NOT NULL,
    parent_session_id VARCHAR(128) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_hermes_session_binding_session UNIQUE (profile_name, session_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
```

`ENGINE` 과 `CHARSET` 문법은 `V1__control_plane_core.sql` 과 같다. origin 실행으로 찾는 질의가 아직 없어 그 색인은 두지 않는다.

### 2. `backend/src/main/java/com/bifos/assistant/orchestration/domain/HermesSessionBinding.java`

`@Entity @Table(name = "hermes_session_binding")`. 칸은 위 SQL 과 같다. `@Column` 의 `name`, `nullable = false`, `length` 를 SQL 과 맞춘다(엔티티로 테스트 스키마가 생긴다).

- 생성: `static HermesSessionBinding of(String profileName, String sessionId, AgentExecution origin, String rootSessionId, String parentSessionId)`. `userId` 와 `originExecutionId` 는 `origin` 에서 옮겨 적고 `createdAt` 은 `Instant.now()`
- 읽기: `id()`, `profileName()`, `sessionId()`, `userId()`, `originExecutionId()`, `rootSessionId()`, `parentSessionId()`, `createdAt()`. setter 는 두지 않는다. 한 번 적은 줄은 바꾸지 않는다
- 클래스 Javadoc 에 「최상위 session 은 여기 적지 않는다. 대화 session 은 여러 turn 이 이어 쓴다」 를 적는다

### 3. `backend/src/main/java/com/bifos/assistant/orchestration/infra/HermesSessionBindingRepository.java`

`JpaRepository<HermesSessionBinding, Long>` 에 `Optional<HermesSessionBinding> findByProfileNameAndSessionId(String profileName, String sessionId)` 하나를 둔다.

`backend/src/main/java/com/bifos/assistant/usage/infra/AgentExecutionRepository.java` 에 `boolean existsByProfileNameAndHermesSessionId(String profileName, String hermesSessionId)` 를 더한다. 등록할 때 `child_session_id` 가 실행 줄의 session 인지 본다.

### 4. `backend/src/main/java/com/bifos/assistant/orchestration/application/SessionOwnerResolver.java`

`@Service`. `AgentExecution resolve(String profileName, String rootSessionId, String sessionId)`.

1. 셋 중 하나라도 null 이거나 비었으면 거절
2. `findByProfileNameAndSessionId(profileName, sessionId)` 가 있으면: 그 줄의 `rootSessionId` 가 인자와 다르면 거절. 같으면 `AgentExecutionRepository.findById(originExecutionId)` 를 돌려준다. 없으면 거절
3. 없고 `sessionId.equals(rootSessionId)` 면 `DelegationParentResolver.resolve(profileName, rootSessionId)` 를 돌려준다
4. 그 밖은 거절

거절은 `ApiException(ErrorCode.MCP_CALL_CONTEXT_INVALID, "call context is invalid")` 이고 이유는 `log.warn("MCP 호출의 origin 실행을 정하지 못했다 profile={} reason={}")` 로만 남긴다. 등록 없는 하위 에이전트는 이유에 `SUBAGENT_SESSION_UNREGISTERED` 를 붙여 로그에서 찾게 한다.
`@Transactional(readOnly = true)`.

### 5. `backend/src/main/java/com/bifos/assistant/orchestration/application/SubagentSessionRegistrar.java` 와 결과 타입

결과 타입은 파일 하나로 뺀다: `backend/src/main/java/com/bifos/assistant/orchestration/application/SubagentRegistrationResult.java` 의 `enum SubagentRegistrationResult { CREATED, EXISTS }`.

`@Service`. `SubagentRegistrationResult register(String profileName, String parentRootSessionId, String parentSessionId, String childSessionId)`.

1. 넷 중 하나라도 비었거나, `childSessionId` 가 `parentSessionId` 나 `parentRootSessionId` 와 같거나, `existsByProfileNameAndHermesSessionId(profileName, childSessionId)` 면 거절
2. 부모 풀기: `findByProfileNameAndSessionId(profileName, parentSessionId)` 가 있으면 그 `rootSessionId` 가 `parentRootSessionId` 와 같은지 보고(다르면 거절) 그 `originExecutionId` 의 실행을 origin 으로 쓴다. 없으면 `DelegationParentResolver.resolve(profileName, parentRootSessionId)` 가 origin 이다. 그것이 `MCP_CALL_CONTEXT_INVALID` 를 던지면 거절로 바꾼다
3. `findByProfileNameAndSessionId(profileName, childSessionId)` 가 있으면: `originExecutionId` 가 같으면 `EXISTS`, 다르면 `SESSION_BINDING_CONFLICT`
4. 없으면 `HermesSessionBinding.of(profileName, childSessionId, origin, parentRootSessionId, parentSessionId)` 를 `saveAndFlush` 하고 `CREATED`. 유일 제약에 걸리면 3 을 다시 한다

거절은 `ApiException(ErrorCode.SESSION_BINDING_REJECTED, "session binding is rejected")`, 충돌은 `ApiException(ErrorCode.SESSION_BINDING_CONFLICT, "session binding conflicts")`. 로그는 profile 과 이유와 origin 번호만 남긴다. 성공도 `log.info("하위 에이전트 session 을 등록했다 profile={} originExecutionId={} result={}")` 로 남긴다. session 값은 적지 않는다.

### 6. `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java`

`MCP_CALL_CONTEXT_INVALID` 아래에 둘을 더하고 Javadoc 을 붙인다.

- `SESSION_BINDING_REJECTED(HttpStatus.FORBIDDEN)`: 하위 에이전트 session 을 등록할 수 없다. 서명, 부모, session 값 중 무엇이 틀렸는지 밖에 알리지 않는다
- `SESSION_BINDING_CONFLICT(HttpStatus.CONFLICT)`: 그 session 이 다른 origin 으로 이미 등록돼 있다. 덮어쓰지 않는다

### 7. `backend/src/main/java/com/bifos/assistant/mcp/application/McpCallerResolver.java`

`DelegationParentResolver` 대신 `SessionOwnerResolver` 를 주입하고 `owners.resolve(principal.profileName(), context.rootSessionId(), context.sessionId())` 로 origin 실행을 얻는다. 그 실행의 `userId()` 로 사용자를 읽는 것과 옛 토큰 경로는 그대로다. 클래스 Javadoc 의 「부모 실행 찾기」 를 「origin 실행 찾기(ADR-037)」 로 고친다.

### 8. 검사 도우미 `backend/src/test/java/com/bifos/assistant/mcp/McpCallSigner.java`

- `clearRuns` 가 같은 profile 의 `hermes_session_binding` 줄을 먼저 지운다
- `context(rawToken, toolName, rootSessionId, sessionId, toolCallId)` 를 `public static` 으로 연다. 하위 에이전트 session 으로 서명할 때 다른 패키지 검사가 쓴다

### 9. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/orchestration/SessionOwnershipTest.java`

`@SpringBootTest @ActiveProfiles("test")`. `DelegationParentResolverTest` 처럼 이 검사의 profile 둘(`session-owner`, `session-owner-other`)의 실행 줄과 등록 줄을 `@BeforeEach` 에서 SQL 로 지우고 사용자 둘(아빠, 아이)을 만든다. 실행 줄은 `McpCallSigner.running` / `McpCallSigner.save` 로 만들고, 상태는 `jdbc.update("UPDATE agent_execution SET status = ? WHERE id = ?", ...)` 로 바꾼다. 하위 에이전트 session 은 `"하위-" + UUID` 처럼 Hermes 가 정한 값처럼 둔다.

리뷰가 요구한 번호를 테스트 이름 옆 주석으로 적는다.

| 번호 | 테스트 | 기대 |
| --- | --- | --- |
| 1 | 부모 #100 `RUNNING` → S1 등록 → #100 을 `SUCCEEDED` 로 → `SessionOwnerResolver.resolve(profile, R, S1)` | #100, 사용자 아빠 |
| 3 | S1 등록 뒤 S1 을 부모로 S2 등록(`parentSessionId = S1`). #100 을 끝낸 뒤 S2 판정 | #100, 아빠 |
| 4 | 다른 profile 의 FOS 실행 #200(`session-owner-other`, 자기 뿌리 `fos-…`) 아래 S3 등록 | S3 의 origin 은 #200. #100 이 아니다 |
| 5 | S1 등록 → #100 끝남 → 같은 뿌리 R 로 #105 `RUNNING` → S1 판정 | #100. 그리고 R 자체의 판정은 #105 |
| 6 | 같은 profile 에서 아빠 #100(뿌리 RA) → SA, 아이 #101(뿌리 RB) → SB 등록. 둘 다 끝낸 뒤 판정 | SA 는 아빠, SB 는 아이 |
| 7 | 등록 줄을 서비스가 아니라 `jdbc.update("INSERT INTO hermes_session_binding ...")` 로 넣고 origin 을 `FAILED`(`ORPHANED`) 로 둔 뒤 판정 | origin 실행과 그 사용자. 앞 프로세스가 적은 줄만으로 판정한다 |
| 8 | 아빠 profile 로 등록한 S1 을 다른 profile 로 판정. 다른 profile 로 그 부모 아래 등록 | 둘 다 거절 |
| 9 | 같은 네 값으로 두 번 등록 | `CREATED` 다음 `EXISTS`, 줄은 하나 |
| 10 | S1 을 #100 으로 등록한 뒤, 다른 뿌리 R2 의 도는 #102 아래로 같은 S1 을 등록 | `SESSION_BINDING_CONFLICT`. 줄의 origin 은 #100 그대로 |
| 11 | 등록 없는 S9 를 뿌리 R 로 판정한다. #100 이 `RUNNING` 이어도 | 거절(`MCP_CALL_CONTEXT_INVALID`). hook 은 동기라 등록이 먼저 끝나고, 늦거나 빠진 등록은 추측하지 않고 거절한다 |

그 밖에 이 phase 가 다루는 실패다.

- 등록 없는 호출에서 `sessionId == rootSessionId` 면 지금처럼 도는 실행을 찾는다. 도는 실행이 없으면 거절
- `compression.in_place: false` 를 흉내 내어 교체된 최상위 session C(`parent_session_id` 사슬의 처음은 R)로 판정하면 거절. 같은 C 를 부모로 등록한 S1(`parentSessionId = C`, `parentRootSessionId = R`, #100 도는 중)은 `CREATED` 이고 S1 판정은 #100
- 등록의 뿌리와 서명한 뿌리가 다르면 거절
- `childSessionId` 가 R 이거나 그 profile 의 실행 줄 session 이면 `SESSION_BINDING_REJECTED`
- 부모 session 에 등록이 없고 뿌리의 도는 실행도 없으면 `SESSION_BINDING_REJECTED`

### 10. HTTP `/mcp` 로 확인하는 테스트

- `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryToolTest.java`: 아빠의 도는 실행 아래 S1 을 `SubagentSessionRegistrar` 로 등록하고 그 실행을 `SUCCEEDED` 로 바꾼 뒤, `McpCallSigner.context(dadToken, "memory_read", dadRoot, S1, …)` 로 서명한 `memory_read` 가 아빠의 본문을 돌려준다(1번). 같은 호출을 등록하지 않은 S2 로 서명하면 `invalidContext()` 결과다(11번)
- `backend/src/test/java/com/bifos/assistant/mcp/McpArtifactWriteToolTest.java`: 같은 준비에서 S1 이 아빠의 대화에 쓰면 성공하고, 다른 사용자(새로 만든 아이)의 대화에 쓰면 쓰지 않고 오류다(2번)
- `backend/src/test/java/com/bifos/assistant/mcp/McpPrincipalTest.java` 의 공유 profile 검사 방식으로: 아빠와 아이의 부모가 모두 끝난 뒤 SA 는 아빠 Memory 만, SB 는 아이 Memory 만 읽는다(6번 HTTP 판)

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.orchestration.SessionOwnershipTest' --tests 'com.bifos.assistant.mcp.McpMemoryToolTest' --tests 'com.bifos.assistant.mcp.McpArtifactWriteToolTest' --tests 'com.bifos.assistant.mcp.McpPrincipalTest' --tests 'com.bifos.assistant.orchestration.DelegationParentResolverTest'
cd backend && ./gradlew test
grep -rn 'SUCCEEDED\|findTop\|OrderBy' backend/src/main/java/com/bifos/assistant/orchestration/application/SessionOwnerResolver.java   # 결과가 없어야 한다. 상태와 최근 줄로 고르지 않는다
```

`./gradlew test` 는 `*MigrationTest` 로 V30 을 H2 MySQL 모드에서도 돌린다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V30__hermes_session_binding.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/domain/HermesSessionBinding.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/infra/HermesSessionBindingRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/SessionOwnerResolver.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/SubagentSessionRegistrar.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/SubagentRegistrationResult.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/infra/AgentExecutionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpCallerResolver.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpCallSigner.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/SessionOwnershipTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryToolTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpArtifactWriteToolTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpPrincipalTest.java` | 수정 |
