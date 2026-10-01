# Phase 08. 승인 엔진: 승인 줄, 승인과 거절, 실행, 상시 허락, 만료

**Execution profile**: deep

## 목표

판정이 「승인 필요」 인 호출을 막고 인자와 함께 `PENDING` 으로 저장한다. 주인이 승인하면 저장한 인자로 한 번만 실행하고, 거절하거나 24시간이 지나면 실행하지 않는다.
상시 허락을 준 도구는 그 기간 동안 바로 통과한다.
지금은 `NEEDS_APPROVAL` 이 `passed=false` 로 막히고 줄만 남는다. 이 phase 는 그 분기에 승인 줄 저장을 잇는다.

**범위 외**: 결과를 대화에 전하는 것과 `approval` 사건(phase 09), 화면(phase 10).

## 컨텍스트

- phase 04 가 만든 것: `connector/application/ConnectorPolicyService.decide(...)`, `connector/domain/ConnectorAction`, `ToolPolicyDecision`, `ActionStatus`, `ConnectorActionRepository`, `ConnectorPolicyAnswer(boolean allowed, String message, UUID actionId)`. 지금은 `NEEDS_APPROVAL` 을 `passed` 거짓으로 막고 `granted` 에 늘 false 를 넘긴다
- phase 07 이 만든 것: 대시보드 `POST /api/connectors/{id}/execute`, 본문 `{profile, hermes_tool, args}`, 200 `{ok, result}` 나 `{ok: false, error}`, 시간 초과와 실행 여부를 모르는 실패는 504
- 대시보드 클라이언트는 `hermes/HermesConnectorClient.java` 와 `HttpHermesConnectorClient.java` 다. `call(...)` 이 `CallResult` 를 돌려주는 방식을 본보기로 삼는다. `RestClient.builder()` 로 만들고 timeout 을 준다(`backend/AGENTS.md`)
- 사용자 행 잠금과 외부 호출을 나누는 본보기는 `ConnectorConnectionService.register`(트랜잭션 밖 확인 호출 뒤 `TransactionTemplate`)다
- `@Scheduled` 의 본보기는 `chat/application/AttachmentCleaner.java` 다. 일정 메서드는 시각만 만들어 `Instant` 를 받는 public 본체에 넘기고, cron 은 설정에서 받고 테스트 설정은 `"-"` 로 끈다
- 기동 정리의 본보기는 `usage/application/OrphanedExecutionSweeper.java`(`@EventListener(ApplicationReadyEvent.class) @Order(0)`)다
- 컨트롤러와 DTO 관례: `connector/presentation/ConnectorConnectionController.java`, `ConnectionDtos.java`. 로그인 사용자는 기존 컨트롤러가 `CurrentUser` 를 얻는 방식을 따른다
- 대화 공개 식별자로 대화를 찾는 것은 `chat/infra/ConversationRepository.findByPublicIdAndUserIdAndDeletedAtIsNull(UUID, Long)` 다. `connector` 가 `chat` 을 import 하면 순환이 생기는지 `./gradlew archTest` 로 본다. 생기면 대화 번호를 푸는 일을 `chat` 쪽 컨트롤러에 두고 `connector` 서비스는 내부 번호를 받는다

**근거 문서**: `docs/connectors.md` 의 「승인」, `docs/data-schema.md` 의 「connector_action」, 「connector_tool_grant」, `docs/flow.md` 의 「승인이 필요한 호출」, `docs/adr/ADR-050-커넥터-쓰기는-control-plane-이-승인-줄을-저장하고-승인한-인자로-한-번만-실행한다.md`

## 의도 메모

- 실행 호출은 트랜잭션 밖에서 한다. 최대 60초가 걸려 그동안 DB 연결을 쥐지 않는다
- `EXECUTING` 을 커밋한 뒤에 실행을 보낸다. 실행을 보낸 뒤 서버가 죽어도 그 줄이 `PENDING` 으로 돌아가 다시 실행되지 않는다
- 결과를 모르면 `UNKNOWN` 이다. 다시 돌리지 않는다
- 인자 원문과 결과를 로그에 싣지 않는다. 관리자 응답에도 싣지 않는다
- 이 phase 뒤로 `schema: 1` 커넥터의 조회 도구(확인과 선택지 도구 밖)는 승인 대기가 된다. 의도한 동작이다

## 작업 항목

### 1. 승인 줄 만들기 (`ConnectorPolicyService`)

`decision == NEEDS_APPROVAL` 일 때다.

- 같은 `origin_execution_id`, 같은 `hermes_tool`, 같은 `args_sha256` 의 `PENDING` 이 있으면 새 줄을 만들지 않고 그 줄의 번호로 답한다
- 없으면 `passed: false`, `status: PENDING`, `args_json` 원문, `expires_at = now + 24시간` 으로 저장한다. 만료 시간은 `ConnectorPolicyProperties` 에 `approvalTtl`(`@DefaultValue("24h") Duration`)로 둔다
- 답은 `allowed: false`, `actionId: publicId`, 글은 아래다

```
이 동작은 사용자의 승인이 필요하다. 승인 요청 번호는 <publicId> 다. 사용자에게 화면에서 승인해 달라고 알리고, 같은 도구를 다시 부르지 않는다. 승인하면 그대로 실행되고 결과가 이 대화로 온다.
```

- 같은 `dedupe_key` 로 다시 온 요청은 저장된 줄로 같은 답을 만든다. 승인 줄이면 지금 상태와 상관없이 위 글과 그 번호다
- `granted` 는 `connector/infra/ConnectorToolGrantRepository` 로 그 사용자, 커넥터, 원래 도구 이름의 유효한 허락이 있는지 본다. `toolName` 이 null 이면 false 다
- 줄을 저장한 뒤 `ApplicationEventPublisher` 로 `ConnectorActionChanged(Long conversationId, UUID actionId)` 를 낸다(`connector/application/model/`). phase 09 가 듣는다. `conversationId` 가 null 이면 내지 않는다

`ConnectorAction` 에 상태 전이 메서드를 더한다. 틀린 전이는 `IllegalStateException` 이다.

- `beginExecution(Instant now)`: `PENDING → EXECUTING`, `decidedAt`
- `succeed(String resultText, Instant now)`, `fail(String errorCode, String resultText, Instant now)`, `unknown(Instant now)`: `EXECUTING →`, `executedAt`
- `reject(Instant now)`, `expire(Instant now)`: `PENDING →`, `decidedAt`
- `markDelivered(Instant now)`
- `boolean grantAllowed()`: `approvalMode == REQUIRED && toolName != null`

### 2. 상시 허락

`connector/domain/ConnectorToolGrant.java` 와 `backend/src/main/resources/db/migration/V47__connector_tool_grant.sql`. 칸은 `docs/data-schema.md` 의 「connector_tool_grant」 와 같다. 색인 `idx_connector_tool_grant_lookup (user_id, connector_id, tool_name)`, 외래 키 `fk_connector_tool_grant_user`.

`connector/domain/type/GrantPeriod.java`: `HOUR, TODAY, DAYS_30`. `Instant expiresAt(Instant now, ZoneId zone)`. `TODAY` 는 그 시간대의 다음 날 0시다.

`ConnectorToolGrantRepository`:

- `boolean existsActive(Long userId, String connectorId, String toolName, Instant now)`(JPQL, `revokedAt is null and expiresAt > :now`)
- `List<ConnectorToolGrant> findActiveByUserId(Long userId, Instant now)`
- 연결 해제 때 쓸 `findActiveByUserIdAndConnectorId(...)`

### 3. 실행 클라이언트

`HermesConnectorClient` 에 더한다.

```java
/** 승인한 호출을 한 번 실행한다. 결과를 알 수 없으면 `ConnectorExecutionUnknown` 을 던진다. */
CallResult execute(String profile, String connectorId, String hermesTool, String argsJson);
```

- 본문의 `args` 는 `argsJson` 을 JSON object 로 읽어 넣는다. 저장한 글을 다시 직렬화하므로 바이트가 달라질 수 있다. 「승인한 인자와 실행한 인자가 같다」 는 JSON 값이 같다는 뜻으로 지킨다. 대역 테스트는 값으로 견준다
- 200 은 `call` 과 같은 방식으로 `CallResult` 로 읽는다
- 504, 읽기 시간 초과, 연결이 끊긴 것(`ResourceAccessException`), 5xx, 읽을 수 없는 본문은 `hermes/ConnectorExecutionUnknown`(`RuntimeException`)이다
- 400, 401, 404 는 실행되지 않은 것이다. `CallResult.failure(ConnectorCallError.UNAVAILABLE)` 로 돌려준다
- 이 호출의 읽기 제한은 75초다. 대시보드의 60초보다 길어야 대시보드의 504 를 받는다. `HermesProperties` 에 값을 두지 않고 클라이언트 상수로 둔다

### 4. `connector/application/ConnectorActionService.java`

```java
List<ConnectorActionView> listForConversation(CurrentUser user, Long conversationId);
ConnectorActionView approve(CurrentUser user, UUID actionId, GrantPeriod grant);
ConnectorActionView reject(CurrentUser user, UUID actionId);
List<ConnectorGrantView> grants(CurrentUser user);
void revokeGrant(CurrentUser user, Long grantId);
int expire(Instant now);
int markInterrupted(Instant now);
void rejectPendingFor(ConnectorConnection connection, Instant now);
```

- `ConnectorActionRepository` 에 `Optional<ConnectorAction> findByPublicIdForUpdate(UUID)`(`@Lock(PESSIMISTIC_WRITE)`), `findByConversationIdAndStatus...`, `findByStatusAndExpiresAtBefore(...)`, `findByStatus(...)` 를 더한다
- `approve`:
  1. 트랜잭션 1: 줄을 잠그고 읽는다. 없거나 `userId` 가 다르면 `CONNECTOR_ACTION_NOT_FOUND`. `PENDING` 이 아니면 `CONNECTOR_ACTION_NOT_PENDING`. `expiresAt` 이 지났으면 `expire` 하고 커밋한 뒤 `CONNECTOR_ACTION_NOT_PENDING`. `grant` 가 있는데 `grantAllowed()` 가 거짓이면 `VALIDATION_FAILED`. 연결(`findByUserIdAndConnectorId`)이 `READY` 가 아니면 `reject` 하고 커밋한 뒤 `CONNECTOR_ACTION_NOT_PENDING`. 그 밖은 `beginExecution`, `grant` 가 있으면 허락 줄 저장, 커밋
  2. 트랜잭션 밖: `connector.execute(profile, connectorId, hermesTool, argsJson)`. profile 은 연결의 에이전트에서 꺼낸다
  3. 트랜잭션 2: `ok` 면 `succeed(결과 JSON 글)`, 실패면 `fail(error.word, null)`, `ConnectorExecutionUnknown` 이면 `unknown`. 그 밖의 예외도 `unknown` 이다. 결과 글은 `AgentExecution.outputText` 를 자르는 상한과 같은 값으로 자른다. 그 상한이 어디 있는지 `ChatService` 나 `AgentRunner` 에서 찾아 같은 상수를 쓴다
  4. `ConnectorActionChanged` 를 낸다
- `reject`: 줄을 잠그고 `PENDING` 이면 `reject`, 사건을 낸다
- `expire(now)`: `PENDING` 이고 `expiresAt < now` 인 줄마다 `expire` 하고 사건을 낸다. 한 줄이 실패해도 나머지를 계속한다. 건수를 돌려준다
- `markInterrupted(now)`: `EXECUTING` 인 줄을 모두 `unknown` 으로 바꾼다
- `rejectPendingFor`: 그 연결의 사용자와 커넥터의 `PENDING` 을 모두 `reject` 하고 유효한 허락에 `revokedAt` 을 적는다. `ConnectorConnectionService.disconnect` 가 부른다
- `ErrorCode` 에 `CONNECTOR_ACTION_NOT_FOUND(NOT_FOUND)`, `CONNECTOR_ACTION_NOT_PENDING(CONFLICT)` 를 Javadoc 과 함께 더한다

`connector/application/model/ConnectorActionView.java`: `record ConnectorActionView(UUID actionId, String connectorId, String toolName, String title, ToolRisk risk, ActionStatus status, String argsJson, String resultText, String errorCode, Instant createdAt, Instant expiresAt, boolean grantAllowed)`. `title` 은 카탈로그 캐시에서 그 도구의 `title` 을 읽고 없으면 `toolName`, 그것도 없으면 `hermesTool` 이다. 캐시를 읽지 못해도 목록은 나와야 한다.
`ConnectorGrantView(Long grantId, String connectorId, String toolName, Instant expiresAt)`.

### 5. 일정과 기동 정리

- `connector/application/ConnectorActionExpirer.java`: `@Scheduled(cron = "${assistant.connector.policy.expire-cron}")` 가 `service.expire(Instant.now(clock))` 를 부른다. `application.yml` 에 `expire-cron: "0 * * * * *"`, `application-test.yml` 에 `"-"`
- `connector/application/ConnectorActionSweeper.java`: `@EventListener(ApplicationReadyEvent.class) @Order(5)` 가 `markInterrupted` 를 부른다. `OrphanedExecutionSweeper`(0) 뒤, 깨우기 기동 훑기(10) 앞이다

### 6. 경로

`connector/presentation/ConnectorActionController.java`, DTO 는 `ConnectorActionDtos.java`.

| 경로 | 본문 | 응답 |
| --- | --- | --- |
| `GET /api/v1/chat/conversations/{conversationId}/connector-actions` | 없음 | `[ConnectorActionView]`. `PENDING` 전부와 끝난 것 최근 20개, 만든 순 |
| `POST /api/v1/connector-actions/{actionId}/approve` | `{grant}` (`null`, `HOUR`, `TODAY`, `DAYS_30`) | `ConnectorActionView` |
| `POST /api/v1/connector-actions/{actionId}/reject` | 없음 | `ConnectorActionView` |
| `GET /api/v1/connector-grants` | 없음 | `[ConnectorGrantView]` |
| `DELETE /api/v1/connector-grants/{grantId}` | 없음 | 204 |

- `{conversationId}` 는 대화의 공개 식별자(UUID)다. 그 사용자의 대화가 아니면 기존 대화 경로와 같은 오류다
- 남의 허락을 거두려 하면 `FORBIDDEN` 이 아니라 404 `CONNECTOR_ACTION_NOT_FOUND` 다. 있는지를 알리지 않는다

### 7. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/connector/ConnectorActionServiceTest.java`(신규, `@SpringBootTest`, `@MockitoBean HermesConnectorClient`):

| 상황 | 기대 |
| --- | --- |
| `WRITE` 도구의 정책 요청 | 답이 `block`, `actionId` 있음, 줄이 `PENDING`, `args_json` 이 보낸 글자 그대로, `expires_at` 이 24시간 뒤. `execute` 는 불리지 않음 |
| 같은 실행이 같은 도구와 같은 인자를 다른 `tool_call_id` 로 | 줄이 하나, 같은 번호 |
| 승인 | `execute` 가 저장한 인자와 값이 같은 인자로 한 번 불림. 줄이 `SUCCEEDED` 와 결과 |
| 같은 승인을 두 번 | 둘째가 `CONNECTOR_ACTION_NOT_PENDING`. `execute` 는 한 번 |
| 동시에 두 스레드가 승인 | `execute` 가 한 번 |
| `execute` 가 `ConnectorExecutionUnknown` | `UNKNOWN`. 다시 승인해도 실행하지 않음 |
| `execute` 가 실패 결과 | `FAILED` 와 `errorCode` |
| 거절 | `REJECTED`. `execute` 없음 |
| 다른 사용자가 승인, 관리자가 승인 | `CONNECTOR_ACTION_NOT_FOUND` |
| 만료 시각이 지난 줄을 `expire(now)` | `EXPIRED`. 그 뒤 승인은 `CONNECTOR_ACTION_NOT_PENDING` |
| `EXECUTING` 인 줄에 `markInterrupted` | `UNKNOWN` |
| `grant: HOUR` 로 승인한 뒤 같은 도구의 새 호출 | 답이 `allow`, 줄이 `ALLOWED` |
| 허락 기간이 지난 뒤 | 다시 `PENDING` |
| `ALWAYS` 도구에 `grant` | `VALIDATION_FAILED`, 줄은 `PENDING` 그대로 |
| 허락을 거둔 뒤 | 다시 `PENDING` |
| 승인할 때 연결이 `PENDING` | 줄이 `REJECTED`, `execute` 없음 |
| 연결 해제 | 그 연결의 `PENDING` 이 `REJECTED`, 허락이 거두어짐 |

`backend/src/test/java/com/bifos/assistant/connector/GrantPeriodTest.java`(신규): 세 기간의 만료 시각. `TODAY` 는 고정 시간대에서 다음 날 0시.
`backend/src/test/java/com/bifos/assistant/connector/ConnectorActionControllerTest.java`(신규, `standaloneSetup`): 다섯 경로의 응답 모양과 `grant` 가 모르는 글일 때 400.
`backend/src/test/java/com/bifos/assistant/hermes/HttpHermesConnectorClientTest.java`: `execute` 의 200 성공, 200 실패, 504 가 `ConnectorExecutionUnknown`, 400 이 실패 결과.
`backend/src/test/java/com/bifos/assistant/mcp/ConnectorPolicyEndpointTest.java`: `WRITE` 도구의 기대를 `block` 과 `action_id` 로 고친다.

### 8. e2e

`test/e2e/fake-hermes.ts` 에 `POST /api/connectors/{id}/execute` 를 더한다. `connectorToolCalls` 에 `{ profile, hermesTool, argsJson, via: "execute" }` 를 더하고 `{ ok: true, result: { saved: true } }` 를 답한다. hook 경로의 기록에는 `via: "hook"` 을 붙인다.
`test/e2e/scenarios/connector-policy.ts` 의 `write_note` 기대를 고친다: 출력에 `block`, `connectorToolCalls()` 에 없음. 승인 API 를 부른 뒤 `via: "execute"` 가 하나이고 인자의 JSON 값이 보낸 것과 같다. 두 번째 승인은 409.

### 9. `docs/` 대조

`docs/connectors.md` 의 「승인」 절 첫머리에 있는 「이 절의 동작은 승인 엔진이 들어올 때 켜진다.」 로 시작하는 세 줄을 지운다. `docs/data-schema.md` 의 「승인 엔진이 켜지기 전에는」 줄과 「승인 엔진과 함께 들어온다」 문장, `docs/flow.md` 의 「승인 엔진 전에는 allow, 뒤에는」 을 지금 동작으로 고친다. 그 밖에 구현이 문서와 다르면 멈추고 보고한다.

## 검토 반영

**이 절이 위의 내용과 다르면 이 절을 따른다.**

- **`connector` 가 `chat` 을 부른다. 반대는 없다.** 대화 공개 식별자를 내부 번호로 푸는 일은 `chat/application/ConversationLookup.java`(신규, `@Service`)에 `Long requireOwnedId(CurrentUser user, UUID conversationId)` 로 둔다. `ConversationRepository.findByPublicIdAndUserIdAndDeletedAtIsNull` 을 쓰고, 없으면 기존 대화 경로가 내는 것과 같은 오류를 낸다. `ConnectorActionController` 는 이 서비스를 부른다
- **사건은 커밋한 뒤에 낸다.** `ConnectorActionChanged` 는 `TransactionTemplate.execute` 가 돌아온 뒤에 `publishEvent` 한다. `@Transactional` 메서드 안에서 내야 하면 `TransactionSynchronizationManager.registerSynchronization` 의 `afterCommit` 을 쓴다
- 결과 글의 상한은 상수가 아니다. `orchestration/application/DelegationProperties.outputMaxChars()` 를 주입받아 쓴다
- **값을 다시 등록할 때도 정리한다.** `ConnectorConnectionService.apply` 가 `beginRegister` 하는 자리에서 `rejectPendingFor(connection, now)` 를 부른다. 다른 계정으로 바꾼 뒤 앞선 계정에 한 승인이 실행되지 않게 한다. 테스트 한 줄을 더한다
- **승인할 때 정책을 다시 읽는다.** `approve` 의 트랜잭션 1 에서 `ConnectorCatalogCache.find` 로 manifest 를 읽어 `ToolPolicyDecision.decide(...)` 를 `granted: false` 로 다시 돌린다. 결과가 `DENIED` 이거나 카탈로그를 읽지 못하면 `reject` 하고 커밋한 뒤 `CONNECTOR_ACTION_NOT_PENDING` 이다. 테스트 두 줄을 더한다(도구가 선언에서 빠짐, 위험도가 `DESTRUCTIVE` 로 바뀜)
- phase 04 가 컨트롤러와 요청 검증을 `connector` 에 뒀다. 끝단 테스트는 `backend/src/test/java/com/bifos/assistant/connector/ConnectorPolicyEndpointTest.java` 다

- `ConnectorPolicyService` 의 클래스 Javadoc 에서 「줄을 남기지 않는」 까닭을 두 경우로 나눠 적는다. 실행이나 연결을 찾지 못한 호출은 줄에 적을 사용자와 에이전트를 알 수 없어서이고, 같은 키로 다른 도구나 인자를 보낸 호출은 키가 유니크라 새 줄을 만들 수 없어서다

## 검증

```bash
# cwd: 저장소 root
(cd backend && ./gradlew test --tests '*ConnectorAction*' --tests '*GrantPeriodTest' --tests '*HttpHermesConnectorClientTest' --tests '*ConnectorPolicyEndpointTest')
(cd backend && ./gradlew test)
(cd backend && ./gradlew qualityCheck)
node test/e2e/run.ts
scripts/check-public-safe.sh
```

- 모두 종료 코드 0

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorPolicyService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorPolicyProperties.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorActionService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorActionExpirer.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorActionSweeper.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorConnectionService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/model/ConnectorActionView.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/model/ConnectorGrantView.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/model/ConnectorActionChanged.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/domain/ConnectorAction.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/domain/ConnectorToolGrant.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/domain/type/GrantPeriod.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/infra/ConnectorActionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/infra/ConnectorToolGrantRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/presentation/ConnectorActionController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/presentation/ConnectorActionDtos.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesConnectorClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HttpHermesConnectorClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/ConnectorExecutionUnknown.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/main/resources/db/migration/V47__connector_tool_grant.sql` | 신규 |
| `backend/src/test/resources/application-test.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorActionServiceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorActionControllerTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/GrantPeriodTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/hermes/HttpHermesConnectorClientTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorPolicyEndpointTest.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ConversationLookup.java` | 신규 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/e2e/scenarios/connector-policy.ts` | 수정 |
| `docs/connectors.md` | 수정 |
| `docs/data-schema.md` | 수정 |
| `docs/flow.md` | 수정 |
