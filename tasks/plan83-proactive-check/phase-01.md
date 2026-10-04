# Phase 01. 살펴보기의 표와 엔티티

**Execution profile**: deep

## 목표

먼저 살펴보기를 저장할 표 둘과 점검 대화를 가리는 칸을 만들고, 그 엔티티와 저장소와 설정을 둔다.
뒤 phase 가 모두 이 표에 기댄다.

**범위 외**: 살펴보기를 시작하거나 돌리는 코드(phase 03, 04), 결과 검사(phase 02).

## 컨텍스트

**근거 문서**: `docs/backend/schema/proactive.md`, `docs/backend/schema/chat.md` 의 `conversation.purpose` 줄, `docs/backend/proactive-check.md` 의 「설정」, `docs/backend/packages.md` 의 「최상위 패키지의 층 순서」 와 「proactive」, `docs/adr/ADR-077-먼저-살펴보기는-점검-대화의-turn-하나로-돌고-읽기-경계를-control-plane-이-강제한다.md`

- 마이그레이션 규칙은 `docs/backend/schema/README.md` 의 「마이그레이션 작성 규칙」 이 갖는다. 새 표는 `ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci` 를 적는다. 이 파일은 DDL 만 담는다.
- Flyway 번호는 지금 main 의 다음 번호인 V67 이 아니라 V68 을 쓴다. V67 과 V68 은 다른 계획(#167 의 결과 전달, `tasks/plan79-attention-surfacing`, `tasks/plan80-follow-up`)도 쓴다. **머지 직전에 main 의 가장 큰 번호에 1 을 더한 번호로 옮기고, 그보다 낮은 빈 번호를 남기지 않는다.** 운영에 V68 이 적용된 뒤 낮은 번호가 들어오면 Flyway 가 기동을 멈춘다(`out-of-order` 거짓). 일회용 DB 를 쓰는 `scripts/check-mysql-migration.sh` 는 이 문제를 잡지 못한다.
- 이 브랜치는 #163, #164 가 머지된 main 위에 있다. 층 순서에 `notification` 이 이미 있다.
- 이미 적용된 마이그레이션 파일은 고치지 않는다.
- 엔티티 본보기: `backend/src/main/java/com/bifos/assistant/chat/domain/Conversation.java`(정적 팩터리, `@Column` 이름), `backend/src/main/java/com/bifos/assistant/usage/domain/AgentExecution.java`.
- 저장되는 enum 은 `<기능>.domain.type` 에 둔다(`backend/AGENTS.md` 「enum 은 저장 여부로 둘 곳을 정한다」).
- 설정 본보기: `backend/src/main/java/com/bifos/assistant/orchestration/application/DelegationProperties.java` 와 `application.yml` 의 `assistant.delegation`.

## 의도 메모

- `trigger` 는 MySQL 예약어라 칸 이름을 `trigger_type` 으로 둔다.
- 발견의 이유, 사실, 추정은 표에 두지 않는다. 메시지에만 남긴다(`schema/proactive.md`).
- `proactive` 패키지는 층 순서에서 `chat` 바로 위다. `orchestration`, `mcp`, `connector` 가 이 패키지를 쓸 수 있어야 한다.

## 작업 항목

### 1. `backend/src/main/resources/db/migration/V68__proactive_check.sql`

- `ALTER TABLE conversation ADD COLUMN purpose VARCHAR(16) NOT NULL DEFAULT 'CHAT'`
- 색인 `CREATE INDEX idx_conversation_user_agent_purpose ON conversation (user_id, agent_id, purpose)`
- `CREATE TABLE proactive_check` — 칸은 `schema/proactive.md` 의 표 그대로. `id BIGINT AUTO_INCREMENT PRIMARY KEY`, FK 넷(`user_id` → `app_user(id)`, `agent_id` → `agent(id)`, `conversation_id` → `conversation(id)`, `root_execution_id` → `agent_execution(id)`), `root_execution_id` UNIQUE, 색인 `(user_id, agent_id, started_at)` 과 `(conversation_id, hermes_root_session_id)`
- `CREATE TABLE proactive_check_finding` — `check_id` FK `proactive_check(id) ON DELETE CASCADE`, `topic_key VARCHAR(120) NULL` 을 포함한 칸은 `schema/proactive.md` 그대로, 색인 `(conversation_id, kind, created_at)`
- 두 표 모두 `ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci`

### 2. 대화의 `purpose`

- `backend/src/main/java/com/bifos/assistant/chat/domain/type/ConversationPurpose.java` 신규: `CHAT`, `CHECK`
- `Conversation.java`: `@Enumerated(EnumType.STRING) @Column(name = "purpose", nullable = false, length = 16) private ConversationPurpose purpose` 를 더한다. 기존 생성자는 `CHAT` 을 넣는다. 정적 팩터리 `startedForCheck(Long userId, String title, Long agentId, Instant now)` 를 더한다(`purpose = CHECK`). 접근자는 그 파일의 기존 방식(Lombok)을 따른다
- `backend/src/main/java/com/bifos/assistant/chat/infra/ConversationRepository.java`: `Optional<Conversation> findFirstByUserIdAndAgentIdAndPurposeAndDeletedAtIsNullOrderByIdDesc(Long userId, Long agentId, ConversationPurpose purpose)` 를 더한다

### 3. `proactive` 패키지의 엔티티와 저장소

`backend/src/main/java/com/bifos/assistant/proactive/` 아래에 둔다.

- `domain/type/CheckTrigger.java`: `MANUAL`, `SCHEDULED`
- `domain/type/CheckStatus.java`: `RUNNING`, `SUCCEEDED`, `FAILED`, `STOPPED`
- `domain/type/CheckOutcome.java`: `FINDINGS`, `NOTHING_NEW`, `INVALID_RESULT`
- `domain/type/FindingKind.java`: `NEW`, `REFERENCE`
- `domain/type/FindingReason.java`: `NO_SOURCE`, `NOT_CHECKED_NOW`, `CLOSED`, `STALE`, `FRESHNESS_UNKNOWN`, `INCOMPLETE`, `REPEATED`
- `domain/ProactiveCheck.java`: 표 `proactive_check`. 정적 팩터리 `started(Long userId, Long agentId, Long conversationId, CheckTrigger trigger, Instant now)`(status `RUNNING`). 상태 바꾸기 메서드 `attachRoot(Long rootExecutionId, String hermesRootSessionId)`, `succeed(CheckOutcome outcome, int newFindings, int referenceFindings, int toolCalls, int delegations, Instant now)`, `stop(String errorCode, int toolCalls, int delegations, Instant now)`, `fail(String errorCode, int toolCalls, int delegations, Instant now)`. 도구 호출 수는 실행 중에 엔티티에서 세지 않는다. 스트림 스레드와 작업 스레드가 같은 엔티티를 고치지 않게, 실행 중의 셈은 호출하는 쪽이 들고 끝날 때 넘긴다
- `domain/ProactiveCheckFinding.java`: 표 `proactive_check_finding`. 정적 팩터리 `of(Long checkId, Long conversationId, FindingKind kind, FindingReason reason, String area, String topicKey, String title, String sourceUrl, Instant checkedAt, Instant now)`. `area` 는 40자, `topicKey` 와 `title` 은 120자, `sourceUrl` 은 2000자를 넘으면 잘라 넣는다
- `infra/ProactiveCheckRepository.java`:
  - `Optional<ProactiveCheck> findFirstByUserIdAndAgentIdOrderByIdDesc(Long userId, Long agentId)`
  - `boolean existsByRootExecutionId(Long rootExecutionId)`
  - `Optional<ProactiveCheck> findByRootExecutionId(Long rootExecutionId)`
  - `long countByConversationIdAndHermesRootSessionId(Long conversationId, String hermesRootSessionId)`
- `infra/ProactiveCheckFindingRepository.java`:
  - `List<ProactiveCheckFinding> findByConversationIdAndKindAndCreatedAtAfterOrderByIdDesc(Long conversationId, FindingKind kind, Instant after, Pageable page)` — 입력에 실을 최근 발견
  - `List<ProactiveCheckFinding> findByConversationIdAndKindAndCreatedAtAfter(Long conversationId, FindingKind kind, Instant after)` — 되풀이 판정에 쓸 이미 알린 묶음
- `ProactiveCheckRepository` 에 `Optional<ProactiveCheck> findFirstByConversationIdAndStatusNotOrderByIdDesc(Long conversationId, CheckStatus status)` 도 더한다. 변화 신호의 「지난 살펴보기」 를 읽는다

### 4. 설정 `ProactiveCheckProperties`

- `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveCheckProperties.java`: `@ConfigurationProperties("assistant.proactive-check")` record. 칸과 기본값과 검사는 `docs/backend/proactive-check.md` 의 「설정」 표 그대로다(`enabled`, `maxDuration`, `maxToolCalls`, `maxDelegations`, `sessionMaxChecks`, `digestWindow`, `digestMaxItems`)
- `maxDuration` 이 `hermes.run-timeout`(`HermesProperties`) 보다 작지 않으면 기동에서 멈춘다. 검사는 그 설정을 읽는 `@Configuration` 이나 생성자에서 한다. 본보기는 `DelegationProperties` 의 기동 검사다
- `application.yml` 의 `assistant:` 아래에 `proactive-check:` 블록과 주석을 더한다
- `backend/src/test/resources/application-test.yml` 은 `hermes.run-timeout: 1s` 다. 그대로면 기본 `max-duration`(4분)이 기동 검사에 걸려 모든 `@SpringBootTest` 가 뜨지 않는다. 그 파일의 `assistant:` 아래에 `proactive-check.max-duration: 500ms` 를 더한다. 살펴보기 turn 을 실제로 돌리는 뒤 phase 의 시험은 `@SpringBootTest(properties = ...)` 로 두 값을 함께 올린다

### 5. 층 순서

- `backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageOrder.java` 의 `ORDER` 에 `"proactive"` 를 `"chat"` 과 `"orchestration"` 사이에 넣는다. 열다섯이 된다
- `backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageOrderTest.java` 의 개수 단언을 고친다. `allowsTopPackageToUseEveryOtherPackage` 의 `hasSize(13)` 은 14, `orderHasFourteenDistinctPackages` 의 두 `hasSize(14)` 는 15 다. 메서드 이름을 `orderHasFifteenDistinctPackages`, `@DisplayName` 을 「층 순서는 겹치는 이름 없이 열다섯이다」 로 바꾼다

- `backend/src/test/java/com/bifos/assistant/architecture/StoredEnumNamesTest.java` 의 저장 enum 고정 목록에 새로 저장하는 enum 여섯(`ConversationPurpose`, `CheckTrigger`, `CheckStatus`, `CheckOutcome`, `FindingKind`, `FindingReason`)을 더한다

### 6. 이 phase 를 검증하는 시험

- `backend/src/test/java/com/bifos/assistant/proactive/ProactiveCheckMigrationTest.java` 신규: H2 에서 Flyway 가 마지막 마이그레이션까지 적용되고(번호를 시험에 적지 않는다), 기존 대화 줄의 `purpose` 가 `CHAT` 이며, `proactive_check` 줄을 저장하고 `root_execution_id` 가 같은 두 줄은 유일 제약에 걸리는지 본다. 본보기는 `chat/DelegationWakeMigrationTest.java`
- `backend/src/test/java/com/bifos/assistant/proactive/ProactiveCheckRepositoryTest.java` 신규: 점검 대화 찾기(`findFirstByUserIdAndAgentIdAndPurposeAndDeletedAtIsNullOrderByIdDesc`)가 지운 대화와 `CHAT` 대화와 다른 사용자의 대화를 고르지 않는지, `countByConversationIdAndHermesRootSessionId` 가 session 별로 세는지, 발견 읽기가 `created_at` 범위와 `kind` 로 거르고 `topic_key` 를 돌려주는지 본다
- `backend/src/test/java/com/bifos/assistant/proactive/ProactiveCheckPropertiesTest.java` 신규: `max-duration` 이 `hermes.run-timeout` 이상이면 기동이 실패하고, 기본값으로는 뜨는지 본다. 본보기는 `orchestration/DelegationPropertiesTest.java`

## 검증

```bash
# cwd: backend/
./gradlew test --tests 'com.bifos.assistant.proactive.*' --tests 'com.bifos.assistant.architecture.*'
./gradlew test
./gradlew checkstyleMain checkstyleTest
```

```bash
# cwd: 저장소 root
scripts/check-mysql-migration.sh
node --test test/unit/migration-collation.test.ts
```

기대값: 모두 종료 코드 0. `RepositoryQueryMysqlTest` 가 새 저장소 메서드를 실제 MySQL 에서 실행해 통과한다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/resources/db/migration/V68__proactive_check.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/type/ConversationPurpose.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/Conversation.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ConversationRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/domain/type/CheckTrigger.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/domain/type/CheckStatus.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/domain/type/CheckOutcome.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/domain/type/FindingKind.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/domain/type/FindingReason.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/domain/ProactiveCheck.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/domain/ProactiveCheckFinding.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/infra/ProactiveCheckRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/infra/ProactiveCheckFindingRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveCheckProperties.java` | 신규 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/resources/application-test.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageOrderTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/architecture/StoredEnumNamesTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageOrder.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/proactive/ProactiveCheckMigrationTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/proactive/ProactiveCheckRepositoryTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/proactive/ProactiveCheckPropertiesTest.java` | 신규 |
