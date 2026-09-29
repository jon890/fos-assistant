# Phase 02. 하위 에이전트 session 등록 표와 등록 서비스

**Execution profile**: deep

## 목표

`hermes_session_binding` 표(V30)와 등록 한 줄을 적는 `SubagentSessionRegistrar` 를 만든다.
부모를 풀어 origin 실행을 정하고, 같은 origin 의 재등록은 그대로 두고, 다른 origin 은 거절한다. 동시에 두 요청이 와도 줄은 하나다.

**범위 외**: MCP 호출의 판정을 등록으로 바꾸는 것(phase 03), HTTP 등록 경로와 서명 확인(phase 04), 가짜 Hermes 검사(phase 05). 이 phase 가 끝나도 MCP 호출의 동작은 바뀌지 않는다. 하위 에이전트 몫의 `agent_execution` 줄은 만들지 않는다.

## 컨텍스트

- 등록할 때 부모 푸는 순서는 `docs/adr/ADR-037-hermes-하위-에이전트-session-의-주인은-만들-때-등록한-줄로-정한다.md` 의 「등록할 때 부모 풀기」 가 정한다
- 표의 칸과 제약은 `docs/data-schema.md` 의 「hermes_session_binding」 절이 정한다. 외래 키를 두지 않는다
- `backend/src/main/java/com/bifos/assistant/orchestration/application/DelegationParentResolver.java` 의 `resolve(String profileName, String rootSessionId)` 는 `(hermes_session_id = root, status = RUNNING, profile_name)` 줄이 정확히 하나일 때 그 `AgentExecution` 을 돌려주고, 아니면 `ApiException(ErrorCode.MCP_CALL_CONTEXT_INVALID, "call context is invalid")` 를 던진다. 이 클래스는 바꾸지 않고 그대로 부른다
- `AgentExecution` 은 `usage.domain` 에 있다. `id()`, `userId()`, `profileName()`, `hermesSessionId()`, `status()` 는 그 클래스에 손으로 쓴 접근자다. 저장소는 `usage.infra.AgentExecutionRepository`
- 오류 코드: `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java`. 응답 모양은 `GlobalExceptionHandler.handleApi` 의 `{code, message}` 다
- 마이그레이션 번호는 **V30** 이다. 다른 plan 이 V31 부터 쓴다
- **테스트 스키마는 엔티티로 만들어진다**(`backend/src/test/resources/application-test.yml` 의 `ddl-auto: create-drop`). 유일 제약을 엔티티에도 적지 않으면 테스트 DB 에 제약이 없다. `backend/AGENTS.md` 「엔티티와 마이그레이션은 따로 논다」. 엔티티의 유일 제약 선례는 `backend/src/main/java/com/bifos/assistant/agent/domain/AgentStarterPrompt.java` 의 `@Table(uniqueConstraints = @UniqueConstraint(...))` 다
- 마이그레이션 검사 선례는 `backend/src/test/java/com/bifos/assistant/mcp/AgentTokenProfileMigrationTest.java` 다. 새 H2 메모리 DB 에 `Flyway.configure().target("28")` 까지 적용한 뒤 전체를 적용한다
- 검사 도우미 `backend/src/test/java/com/bifos/assistant/mcp/McpCallSigner.java` 의 `running`, `save` 는 package-private 이다. `clearRuns(JdbcTemplate, List<String>)` 는 그 profile 의 `agent_execution` 줄만 지운다
- `TransactionTemplate` 으로 트랜잭션을 짧게 나누는 선례는 `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` 다

**근거 문서**: `docs/adr/ADR-037-hermes-하위-에이전트-session-의-주인은-만들-때-등록한-줄로-정한다.md`, `docs/data-schema.md` 의 「hermes_session_binding」 절, `docs/flow.md` 의 「하위 에이전트 session 을 등록할 때」 절

## 의도 메모

- `child_session_id` 가 `parent_root_session_id` 나 `parent_session_id` 와 같거나, 그 profile 의 `agent_execution.hermes_session_id` 로 이미 쓰이면 거절한다. 최상위 session 에 등록이 생기면 phase 03 의 판정이 등록을 먼저 보므로 뒤 turn 의 호출이 앞 turn 에 묶인다
- 부모 session 의 등록이 있으면 그 origin 을 잇는다. 그 등록의 `rootSessionId` 가 `parent_root_session_id` 와 다르거나, 그 `originExecutionId` 의 실행이 없으면 거절한다
- 부모 session 의 등록이 없으면 `DelegationParentResolver.resolve(profileName, parentRootSessionId)` 가 origin 이다. 압축 교체된 최상위 session C 가 만든 자식도 뿌리가 같아 여기서 풀린다
- 등록 실패는 `SESSION_BINDING_REJECTED` 하나로 묶는다. 다른 origin 으로 이미 등록된 경우만 `SESSION_BINDING_CONFLICT` 다. 이유는 로그에만 남기고 session 값은 적지 않는다
- **메서드 전체에 `@Transactional` 을 두지 않는다.** 유일 제약 위반이 그 트랜잭션을 롤백 전용으로 만들어 다시 읽지 못한다. `TransactionTemplate` 트랜잭션 하나에서 `saveAndFlush` 하고, `DataIntegrityViolationException` 이면 그 트랜잭션 밖에서 다시 읽어 같은 규칙(같은 origin 이면 `EXISTS`, 다르면 충돌)으로 판정한다
- 등록은 요청마다 저장소를 읽고 쓴다. JVM 메모리에 두지 않는다
- 동시 등록 검사가 흔들리면 예외 종류를 먼저 본다. H2 가 잠금 대기 시간을 넘기면 `PessimisticLockingFailureException` 계열이 나와 재시도 경로를 타지 않는다

## Blocked 조건

- `git fetch origin main && git ls-tree --name-only origin/main backend/src/main/resources/db/migration/` 에 `V31__` 이상의 파일이 있으면 `PHASE_BLOCKED: main 에 V31 이상 마이그레이션이 먼저 들어가 V30 이 순서 밖이 된다` 를 출력하고 멈춘다. Flyway 의 순서 밖 적용이 꺼져 있어 운영 기동이 실패한다

## 작업 항목

### 1. `backend/src/main/resources/db/migration/V30__hermes_session_binding.sql`

첫 줄에 ADR-037 을 가리키는 주석을 두고 표를 만든다. `ENGINE` 과 `CHARSET` 문법은 `V1__control_plane_core.sql` 과 같다.

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

### 2. `backend/src/main/java/com/bifos/assistant/orchestration/domain/HermesSessionBinding.java`

`@Entity`, `@Table(name = "hermes_session_binding", uniqueConstraints = @UniqueConstraint(name = "uk_hermes_session_binding_session", columnNames = {"profile_name", "session_id"}))`. `@Column` 의 `name`, `nullable = false`, `length` 를 SQL 과 맞춘다.

- 생성: `static HermesSessionBinding of(String profileName, String sessionId, AgentExecution origin, String rootSessionId, String parentSessionId)`. `userId` 와 `originExecutionId` 는 `origin` 에서 옮겨 적고 `createdAt` 은 `Instant.now()`
- 읽기: `id()`, `profileName()`, `sessionId()`, `userId()`, `originExecutionId()`, `rootSessionId()`, `parentSessionId()`, `createdAt()`. setter 는 두지 않는다
- 클래스 Javadoc 에 「한 번 적은 줄은 바꾸지 않는다. 최상위 session 은 여기 적지 않는다. 대화 session 은 여러 turn 이 이어 쓴다」 를 적는다

### 3. 저장소

- `backend/src/main/java/com/bifos/assistant/orchestration/infra/HermesSessionBindingRepository.java`: `JpaRepository<HermesSessionBinding, Long>` 에 `Optional<HermesSessionBinding> findByProfileNameAndSessionId(String profileName, String sessionId)`
- `backend/src/main/java/com/bifos/assistant/usage/infra/AgentExecutionRepository.java`: `boolean existsByProfileNameAndHermesSessionId(String profileName, String hermesSessionId)`

### 4. `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java`

`MCP_CALL_CONTEXT_INVALID` 아래에 둘을 더하고 Javadoc 을 붙인다.

- `SESSION_BINDING_REJECTED(HttpStatus.FORBIDDEN)`: 하위 에이전트 session 을 등록할 수 없다. 서명, 부모, session 값 중 무엇이 틀렸는지 밖에 알리지 않는다
- `SESSION_BINDING_CONFLICT(HttpStatus.CONFLICT)`: 그 session 이 다른 origin 으로 이미 등록돼 있다. 덮어쓰지 않는다

### 5. `SubagentSessionRegistrar` 와 결과 타입

- `backend/src/main/java/com/bifos/assistant/orchestration/application/SubagentRegistrationResult.java`: `enum SubagentRegistrationResult { CREATED, EXISTS }`
- `backend/src/main/java/com/bifos/assistant/orchestration/application/SubagentSessionRegistrar.java`: `@Service`. `SubagentRegistrationResult register(String profileName, String parentRootSessionId, String parentSessionId, String childSessionId)`

순서:

1. 넷 중 하나라도 null 이거나 비었거나, `childSessionId` 가 `parentSessionId` 나 `parentRootSessionId` 와 같거나, `existsByProfileNameAndHermesSessionId(profileName, childSessionId)` 면 거절
2. 부모 풀기(위 의도 메모). `DelegationParentResolver` 가 던진 `MCP_CALL_CONTEXT_INVALID` 는 거절로 바꾼다
3. `findByProfileNameAndSessionId(profileName, childSessionId)` 가 있으면 `originExecutionId` 가 같으면 `EXISTS`, 다르면 충돌
4. 없으면 `TransactionTemplate` 안에서 `HermesSessionBinding.of(...)` 를 `saveAndFlush` 하고 `CREATED`. `DataIntegrityViolationException` 이면 3 을 다시 한다

거절은 `ApiException(ErrorCode.SESSION_BINDING_REJECTED, "session binding is rejected")`, 충돌은 `ApiException(ErrorCode.SESSION_BINDING_CONFLICT, "session binding conflicts")`. 거절 로그는 `log.warn("하위 에이전트 session 을 등록하지 못했다 profile={} reason={}")`, 성공 로그는 `log.info("하위 에이전트 session 을 등록했다 profile={} originExecutionId={} result={}")`. session 값은 적지 않는다.

### 6. 검사 도우미 `backend/src/test/java/com/bifos/assistant/mcp/McpCallSigner.java`

- `running`, `save` 를 `public static` 으로 연다. 다른 패키지 검사가 실행 줄을 만든다
- `clearRuns` 를 `public static` 으로 열고, 같은 profile 의 `hermes_session_binding` 줄을 먼저 지운다

### 7. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/orchestration/SubagentSessionRegistrarTest.java`

`@SpringBootTest @ActiveProfiles("test")`. profile 둘(`registrar-a`, `registrar-b`)의 실행 줄과 등록 줄을 `@BeforeEach` 에서 `McpCallSigner.clearRuns` 로 지우고 사용자 둘(아빠, 아이)을 만든다. 실행 줄은 `McpCallSigner.running` / `McpCallSigner.save` 로 만들고 상태는 `jdbc.update("UPDATE agent_execution SET status = ? WHERE id = ?", ...)` 로 바꾼다. 하위 에이전트 session 은 `"하위-" + UUID` 로 둔다. 줄은 `HermesSessionBindingRepository` 로 읽는다.

리뷰가 요구한 필수 테스트 번호를 테스트 이름 옆 주석으로 적는다.

| 번호 | 경우 | 기대 |
| --- | --- | --- |
| - | 아빠 #100 `RUNNING`(뿌리 R) 아래 S1 | `CREATED`, 줄의 origin #100, 사용자 아빠, 뿌리 R, 부모 R |
| 3 | S1 등록 뒤 #100 을 `SUCCEEDED` 로 바꾸고 S1 을 부모로 S2 | `CREATED`, S2 의 origin #100, 사용자 아빠 |
| 4 | `registrar-b` 의 FOS 실행 #200(자기 뿌리) 아래 S3 을 `registrar-b` 로 | S3 의 origin #200 |
| 8 | `registrar-a` 의 S1 을 부모로 `registrar-b` 가 등록. `registrar-a` 의 #100 뿌리 아래 `registrar-b` 가 등록 | 둘 다 `SESSION_BINDING_REJECTED`, 줄이 생기지 않는다 |
| 9 | 같은 네 값으로 두 번 | `CREATED` 다음 `EXISTS`, 줄 하나 |
| 9 | 같은 네 값을 가상 스레드 8개가 `CountDownLatch` 로 동시에 | `CREATED` 하나, 나머지 `EXISTS`, 줄 하나 |
| 10 | S1 을 #100 으로 등록한 뒤, 다른 뿌리 R2 의 도는 #102 아래로 같은 S1 | `SESSION_BINDING_CONFLICT`, 줄의 origin 은 #100 그대로 |
| - | 압축 교체된 최상위 session C 를 부모로(`parentSessionId = C`, `parentRootSessionId = R`, #100 도는 중) | `CREATED`, origin #100 |
| - | `childSessionId` 가 R 이거나, `registrar-a` 의 다른 실행 줄의 `hermes_session_id` | `SESSION_BINDING_REJECTED` |
| - | 부모 session 에 등록이 없고 뿌리로 도는 실행도 없다(#100 이 `SUCCEEDED`) | `SESSION_BINDING_REJECTED` |
| - | 부모 등록의 뿌리가 `parentRootSessionId` 와 다르다 | `SESSION_BINDING_REJECTED` |
| - | 부모 등록은 있는데 그 origin 실행 줄이 없다(SQL 로 지운다) | `SESSION_BINDING_REJECTED` |

### 8. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/orchestration/HermesSessionBindingMigrationTest.java`

`AgentTokenProfileMigrationTest` 처럼 새 H2 메모리 DB(`MODE=MySQL`)에 V29 까지(`target("29")`) 적용한 뒤 전체를 적용한다.

- 정상: `hermes_session_binding` 에 한 줄이 들어간다. 다른 profile 의 같은 `session_id` 도 들어간다
- 실패: 같은 `(profile_name, session_id)` 의 두 번째 INSERT 가 `SQLException` 으로 실패한다

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.orchestration.SubagentSessionRegistrarTest' --tests 'com.bifos.assistant.orchestration.HermesSessionBindingMigrationTest' --tests 'com.bifos.assistant.orchestration.DelegationParentResolverTest'
cd backend && ./gradlew test
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V30__hermes_session_binding.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/domain/HermesSessionBinding.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/infra/HermesSessionBindingRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/SubagentSessionRegistrar.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/SubagentRegistrationResult.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/infra/AgentExecutionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpCallSigner.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/SubagentSessionRegistrarTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/orchestration/HermesSessionBindingMigrationTest.java` | 신규 |
