# Phase 01. attention 패키지와 읽을 때 계산하는 판정

**Execution profile**: deep

## 목표

새 최상위 패키지 `attention` 을 만들고, 요청자의 원래 기록을 읽어 카드 넷과 항목의 판정을 계산하는 `GET /api/v1/attention` 과 `GET /api/v1/attention/summary` 를 연다.
지금 화면(plan81)과 사이드바 건수가 이 두 경로를 읽는다.

**범위 외**: 숨기기와 미루기, 지표 사건(phase 02), 결과 전달 실패(phase 03), 할 일 후보(plan80), 화면(plan81).

## 컨텍스트

**근거 문서**: `docs/backend/attention.md` 의 「패키지」, 「판정 셋」, 「후보와 trigger」, 「억제 신호」, 「기준값」, 「「왜 보였는가」」, 「API」. `docs/adr/ADR-072-먼저-알리기의-기본값은-알리지-않음이고-control-plane-기록에서-정한-신호만-화면-안에-올린다.md`, `docs/adr/ADR-074-지금-화면은-원래-기록을-읽어-만든-view-이고-정해진-카드-넷만-그린다.md`, `docs/adr/ADR-068-최상위-패키지는-한-방향-층-순서를-따르고-거꾸로-가는-의존은-port-나-이동으로-끊는다.md`.

이 phase 가 다루는 trigger 는 여섯이다. `EXECUTION_FAILED`, `APPROVAL_PENDING`, `MEMORY_PROPOSED`, `DELEGATION_RUNNING`, `DELEGATION_FINISHED`, `CONVERSATION_RECENT`.
억제 신호 1(`HIDDEN`)과 2(`SNOOZED`)는 phase 02 가 표를 만든다. 이 phase 의 판정 함수는 제어 목록을 인자로 받되 서비스는 빈 목록을 넘긴다.

기존 코드에서 읽을 것과 쓸 것이다. 모두 `backend/src/main/java/com/bifos/assistant/` 아래다.

| 무엇 | 어디 |
| --- | --- |
| 층 순서 | `backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageOrder.java` 의 `ORDER`(지금 13개, 맨 위 `connector`). 검사는 `ArchitectureRules.TOP_LEVEL_PACKAGES_FOLLOW_LAYER_ORDER` |
| 순서 개수 단언 | `backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageOrderTest.java` 의 `allowsTopPackageToUseEveryOtherPackage`(`hasSize(12)`, `"connector"`)와 `orderHasThirteenDistinctPackages`(`hasSize(13)`) |
| 현재 사용자 | `shared.auth.CurrentUserProvider.require()`, 관리자는 `requireAdmin()` |
| 실행 줄 | `usage.domain.AgentExecution`, 저장소 `usage.infra.AgentExecutionRepository`. 상태 enum 은 `usage.domain.type.ExecutionStatus`(`RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED`) |
| 대화 | `chat.domain.Conversation`(`publicId`, `title`, `agentId`, `updatedAt`, `deletedAt`), 저장소 `chat.infra.ConversationRepository`. 목록은 `ChatService.conversationsOf(CurrentUser, String cursor, int limit)` 가 이미 지운 대화를 빼고 `updatedAt` 순으로 낸다 |
| 대화 번호를 공개 식별자로 | `chat.application.ConversationPublicIdLookup.activePublicIdsOf(Collection<Long>)`(지운 대화를 뺀다) |
| 에이전트 이름 | `agent.application.AgentService.byIds(Collection<Long>)` → `Map<Long, Agent>`, `Agent.name()` |
| 승인 줄 | `connector.domain.ConnectorAction`(`publicId`, `userId`, `status`, `conversationId`, `createdAt`, `expiresAt`), 저장소 `connector.infra.ConnectorActionRepository`, 상태 `connector.domain.type.ActionStatus.PENDING`. 사람에게 보일 이름은 `ConnectorActionService` 의 private `view(...)` 가 manifest 로 정한다(`ConnectorActionView.title`) |
| Memory 제안 | `memory.domain.Memory`, 저장소 `memory.infra.MemoryRepository`, `memory.domain.type.MemoryScope.USER`, `MemoryStatus.PROPOSED` |
| 지문 | `shared.util.Sha256.hex16(String)` |
| 시간대 | `usage.presentation.UsageController` 의 `HOUSEHOLD_ZONE`(`Asia/Seoul`). 이 phase 는 시간대 계산이 없다 |
| 설정 record | `context.ContextProperties` 처럼 `@Validated @ConfigurationProperties(prefix = …) record`. `AssistantApplication` 의 `@ConfigurationPropertiesScan` 이 찾는다 |
| e2e | `test/e2e/run.ts` 의 `SCENARIOS`, `test/e2e/harness.ts` 의 `call(context, path, {token})`(경로는 `/api/v1` 뒤), `context.hermes.busy()` 는 `clearBusy()` 를 부를 때까지 실행 제출을 429 로 거절해 그 turn 을 `FAILED`(`HERMES_BUSY`)로 남긴다(`test/e2e/scenarios/busy.ts`) |

## 의도 메모

- **판정을 순수 함수로 둔다.** 후보, 제어, 지금 시각, 설정을 받아 카드 넷을 내는 `AttentionJudge` 하나에 억제, 중복, `NOW`, 순서, 상한을 모은다. 기록을 읽는 쪽과 나누면 판정 표를 단위 테스트 하나로 본다
- **카드마다 기록 읽기를 따로 한다.** 한 source 의 조회가 예외를 던지면 그 source 가 든 카드만 `UNAVAILABLE` 이다. 추정한 항목을 넣지 않는다
- 다른 패키지의 기록은 그 패키지 `application` 의 읽기 메서드로 읽는다. 그래야 `attention` 이 그 패키지의 저장 모델을 몰라도 된다. 실행 줄, 승인 줄, Memory 는 엔티티를 받아도 되지만 저장소는 import 하지 않는다
- 기각: 판정 결과를 표에 저장해 두는 방식. ADR-072 의 「대안 기각」 첫 줄이다
- 기각: `GET /api/v1/attention/summary` 가 전체 판정을 하지 않고 건수만 따로 세는 방식. 판정 규칙이 두 곳으로 갈라진다. 같은 `AttentionService` 를 부르고 `nowCount` 만 낸다
- 오류 코드를 응답에 싣지 않는다. 실패의 종류는 `signals` 의 `NOT_RETRIED` 하나로만 말한다

## 작업 항목

### 1. 층 순서에 `attention` 을 맨 위로 더한다

- `TopLevelPackageOrder.ORDER` 끝(`"connector"` 뒤)에 `"attention"` 을 더한다
- `TopLevelPackageOrderTest`:
  - `allowsTopPackageToUseEveryOtherPackage` 의 `hasSize(12)` 를 `hasSize(13)` 으로, `violations("connector", others)` 를 `violations("attention", others)` 로
  - `orderHasThirteenDistinctPackages` 를 `orderHasFourteenDistinctPackages` 로 이름을 바꾸고 `@DisplayName` 을 「층 순서는 겹치는 이름 없이 열넷이다」 로, 두 `hasSize(13)` 을 `hasSize(14)` 로
- `docs/backend/packages.md`:
  - 「패키지와 책임」 표 끝에 `| \`attention\` | 먼저 알리기의 판정과 지금 화면이 읽는 카드. 다른 패키지의 기록을 읽기만 한다 |` 를 더한다
  - 「최상위 패키지의 층 순서」 의 「자리 1 이 맨 아래이고 13 이 맨 위다」 를 「14 가 맨 위다」 로, 표에 `| 14 | \`attention\` |` 를 더한다

### 2. 저장하는 enum 과 화면용 값

`attention.domain.type` (phase 02 의 `attention_event` 가 저장한다):

- `AttentionTrigger`: `EXECUTION_FAILED`, `DELIVERY_FAILED`, `APPROVAL_PENDING`, `MEMORY_PROPOSED`, `FOLLOW_UP_PROPOSED`, `FOLLOW_UP_OPEN`, `DELEGATION_RUNNING`, `DELEGATION_FINISHED`, `CONVERSATION_RECENT`. 이 phase 가 만들지 않는 셋(`DELIVERY_FAILED`, `FOLLOW_UP_*`)도 이름을 먼저 둔다. 저장 값이 바뀌지 않게 하려는 것이다
- `AttentionLevel`: `NOW`, `LATER`, `SUPPRESSED`

`attention.application.model` (저장하지 않는다):

- `CardKey`: `FAILURES`, `NEEDS_ME`, `DELEGATED`, `CONTINUE`. 응답에는 `failures`, `needs_me`, `delegated`, `continue` 로 낸다. 이 선언 순서가 카드의 고정 순서다
- `CardStatus`: `OK`, `UNAVAILABLE`
- `AttentionSignal`: `NOT_RETRIED`, `DELIVERY_NOT_DONE`, `EXPIRES_SOON`, `DUE_SOON`, `OVERDUE`, `LINKED_UPDATE`, `WAITING`, `LONG_RUNNING`
- `AttentionConfidence`: `CONTROL_PLANE`, `USER_CONFIRMED`, `MODEL_INFERRED`
- `AttentionSourceRef(String source, String ref, Instant asOf)`. `source` 는 `EXECUTION_STATE` 같은 문맥 묶음의 source 이름 글이다(`docs/backend/context-bundle.md` 의 「참여하는 source」)
- `AttentionExecutionRef(Long id, String status)`. 맡긴 일 항목의 실행 번호와 상태(`RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED`)
- `AttentionFollowUpRef(UUID id, Instant dueAt, boolean waiting, boolean proposed)`. 할 일 항목의 값. 이 plan 에서는 채우는 후보가 없고 plan80 이 채운다
- `AttentionCandidate(CardKey card, String itemKey, String stateKey, AttentionTrigger trigger, boolean resolved, boolean nowSignal, List<AttentionSignal> signals, AttentionConfidence confidence, String title, UUID conversationId, String agentName, Instant at, List<AttentionSourceRef> sources, AttentionExecutionRef execution, UUID actionId, AttentionFollowUpRef followUp)`. 해당하지 않는 마지막 세 칸은 `null` 이다
- `AttentionControl(CardKey card, String itemKey, String stateKey, Instant snoozedUntil)`. phase 02 가 표에서 읽어 채운다. 숨기기는 `stateKey` 가, 미루기는 `snoozedUntil` 이 채워진다. **제어는 카드마다 따로다.** 같은 `itemKey` 가 두 카드에 쓰여도 한 카드의 제어가 다른 카드에 걸리지 않는다
- `AttentionItem(String itemKey, String stateKey, AttentionLevel level, AttentionTrigger trigger, String title, UUID conversationId, String agentName, Instant at, AttentionWhy why, AttentionExecutionRef execution, UUID actionId, AttentionFollowUpRef followUp)`
- `AttentionWhy(AttentionTrigger trigger, List<AttentionSignal> signals, AttentionConfidence confidence, List<AttentionSourceRef> sources)`
- `AttentionCard(CardKey key, CardStatus status, int nowCount, int moreCount, List<AttentionItem> items)`. `nowCount` 는 상한으로 자르기 전 그 카드의 `NOW` 항목 수다
- `AttentionView(Instant readAt, int nowCount, List<AttentionCard> cards)`. `nowCount` 는 카드 `nowCount` 의 합이다

`stateKey` 는 `Sha256.hex16(<재료 글>)` 이다. 재료는 `docs/backend/attention.md` 「후보와 trigger」 표의 「`stateKey` 의 재료」 칸을 `|` 로 이은 글이다. 예: 실패는 `"EXECUTION_FAILED|" + 마지막 실패 실행 번호`.
`itemKey` 는 같은 표의 `itemKey` 칸 형식이다(`conversation:<UUID>`, `connector_action:<UUID>`, `memory:<번호>`, `execution:<번호>`).

### 3. 설정 `AttentionProperties`

`attention.application.AttentionProperties`, `@ConfigurationProperties(prefix = "assistant.attention")` record.
칸과 기본값(값이 없거나 0 이하면 기본값으로 둔다):

| 칸 | 타입 | 기본값 |
| --- | --- | --- |
| `failureWindow` | `Duration` | 7일 |
| `delegatedWindow` | `Duration` | 24시간 |
| `longRunningAfter` | `Duration` | 30분 |
| `continueCount` | `int` | 5 |
| `maxItemsPerCard` | `int` | 10 |

`backend/src/main/resources/application.yml` 의 `assistant:` 아래에 `attention:` 절을 더하고 칸마다 한국어 주석 한 줄을 단다(`failure-window: 7d` 처럼).
`due-soon`, `snooze-max`, `event-retention` 은 쓰는 phase 가 더한다.

### 4. 다른 패키지에 읽기 메서드를 더한다

| 패키지 | 더하는 것 |
| --- | --- |
| `usage` | `AgentExecutionRepository.findUnresolvedFailedTurns(Long userId, Instant since)`: JPQL. `e.userId = :userId and e.conversationId is not null and e.parentExecutionId is null and e.status = FAILED and e.finishedAt >= :since` 이고, 같은 대화에 `id` 가 더 크고 `parentExecutionId is null` 이며 `SUCCEEDED` 인 실행이 없는(`not exists`) 줄을 `id desc` 로 |
| `usage` | `AgentExecutionRepository.findDelegationsForAttention(Long userId, Instant finishedSince)`: `delegationKey is not null` 이고 `userId` 가 같으며 `status = RUNNING` 이거나 `finishedAt >= :finishedSince` 인 줄을 `id desc` 로 |
| `usage` | 새 `usage.application.AttentionExecutionQuery`(`@Service`, `@Transactional(readOnly = true)`): 위 둘을 부르는 `unresolvedFailedTurns(Long userId, Instant since)`, `delegations(Long userId, Instant finishedSince)` |
| `connector` | `ConnectorActionRepository.findByUserIdAndStatusOrderByIdAsc(Long userId, ActionStatus status)` |
| `connector` | 새 `connector.application.model.PendingApproval(UUID actionId, String title, Long conversationId, Instant createdAt, Instant expiresAt)` 와 `ConnectorActionService.pendingApprovalsOf(CurrentUser user)`. 이름은 `listForConversation` 이 쓰는 것과 같은 manifest 경로(`view`)로 정한다. 인자와 결과 글은 담지 않는다 |
| `memory` | `MemoryRepository.findByScopeAndOwnerUserIdAndStatusOrderByIdAsc(MemoryScope scope, Long ownerUserId, MemoryStatus status)` 와 `MemoryService.proposalsOf(CurrentUser user)`(USER 범위의 주인 것, `PROPOSED`) |
| `chat` | `ChatMessageRepository.findTopByConversationIdAndRoleNotAndCreatedAtLessThanEqualOrderByIdDesc(Long conversationId, MessageRole role, Instant createdAt)`(새 파생 쿼리, `RepositoryQueryMysqlTest` 가 실제 MySQL 에서 실행한다). 새 `OwnConversations` 의 `startedByUser(Long conversationId, Instant startedAt)` 가 이 메서드에 `MessageRole.ASSISTANT` 를 넘겨, 찾은 줄의 `role` 이 `USER` 면 참을 낸다. 실패한 turn 에는 답 메시지가 없을 수 있어 실행의 `startedAt` 으로 질문을 찾는다 |
| `chat` | 새 `chat.application.OwnConversations`(`@Service`): `activeOf(CurrentUser user, Collection<Long> ids)` → `Map<Long, Conversation>`. `ConversationRepository.findAllById` 로 읽고 `userId` 가 같고 `deletedAt` 이 비어 있는 것만 남긴다. 빈 번호면 읽지 않는다(`ConversationPublicIdLookup` 과 같은 까닭) |

새 저장소 메서드는 `RepositoryQueryMysqlTest` 가 실제 MySQL 에서 실행한다. 인자 타입 `ActionStatus`, `MemoryScope`, `MemoryStatus`, `ExecutionStatus`, `Instant`, `Long` 을 `RepositoryQuerySweep` 이 만들지 못하면 그 값을 더한다.

### 5. 후보 수집

`attention.application.AttentionCandidates` 인터페이스: `Set<CardKey> cards()`, `List<AttentionCandidate> read(CurrentUser user, Instant now)`.
구현은 `attention.application` 에 하나씩 둔다. plan80 이 할 일 구현을 하나 더한다.

| 구현 | 카드 | 읽는 것 | 후보 |
| --- | --- | --- | --- |
| `FailedTurnCandidates` | `FAILURES` | `AttentionExecutionQuery.unresolvedFailedTurns(user.id(), now - failureWindow)`, `OwnConversations.activeOf`, `OwnConversations.startedByUser`, `AgentService.byIds` | 사용자가 보낸 turn 의 실패만 고른다(`startedByUser` 가 참). 자동 turn 의 실패는 `DELIVERY_FAILED`(phase 03)가 맡는다. 대화마다 가장 최근 실패 하나. 지운 대화는 뺀다. `trigger` `EXECUTION_FAILED`, `signals` `[NOT_RETRIED]`, `nowSignal` 참, `at` 은 그 실행의 `finishedAt`, `title` 은 대화 제목, `sources` 는 `EXECUTION_STATE`, `execution:<번호>` |
| `ApprovalCandidates` | `NEEDS_ME` | `ConnectorActionService.pendingApprovalsOf(user)`, `OwnConversations.activeOf` | `expiresAt` 이 `now` 뒤인 줄. `trigger` `APPROVAL_PENDING`, `nowSignal` 참, 남은 시간이 6시간 이하면 `signals` 에 `EXPIRES_SOON`. `title` 은 동작 이름. `actionId` 는 승인 줄의 공개 식별자. `stateKey` 재료는 `PENDING` |
| `MemoryProposalCandidates` | `NEEDS_ME` | `MemoryService.proposalsOf(user)` | `trigger` `MEMORY_PROPOSED`, `confidence` `MODEL_INFERRED`, `nowSignal` 거짓, `title` 은 Memory 제목. `stateKey` 재료는 `revision` |
| `DelegationCandidates` | `DELEGATED` | `AttentionExecutionQuery.delegations(user.id(), now - delegatedWindow)`, `OwnConversations.activeOf`, `AgentService.byIds` | `RUNNING` 은 `DELEGATION_RUNNING`, 시작한 지 `longRunningAfter` 를 넘으면 `nowSignal` 참과 `LONG_RUNNING`. 끝난 것은 `DELEGATION_FINISHED`. `title` 은 대화 제목, `agentName` 은 맡은 에이전트 이름. `execution` 은 `{ 실행 번호, status }`. 대화가 없거나 지워졌으면 뺀다 |
| `RecentConversationCandidates` | `CONTINUE` | `ChatService.conversationsOf(user, null, continueCount + maxItemsPerCard)` | 대화마다 `CONVERSATION_RECENT`, `at` 은 `updatedAt` |

`EXPIRES_SOON` 의 6시간은 상수다(`docs/backend/attention.md` 의 `signals` 표).

### 6. 판정 `AttentionJudge`

`attention.application.AttentionJudge` 는 상태가 없는 클래스다. 메서드 `List<AttentionCard> judge(Map<CardKey, List<AttentionCandidate>> candidates, Set<CardKey> unavailable, List<AttentionControl> controls, Instant now, int maxItemsPerCard, int continueCount)`.

1. 후보마다 `docs/backend/attention.md` 의 「억제 신호」 를 위에서부터 본다. `HIDDEN`(같은 카드, 같은 `itemKey`, 같은 `stateKey` 의 숨기기), `SNOOZED`(같은 카드의 그 `itemKey` 에 건 `snoozedUntil` 이 `now` 뒤), `RESOLVED`(`resolved` 참), `DUPLICATE`(같은 `itemKey` 가 고정 순서로 앞선 카드에 이미 남았다)
2. 남은 후보는 `nowSignal` 이 참이고 `confidence` 가 `MODEL_INFERRED` 가 아니면 `NOW`, 아니면 `LATER` 다
3. 카드 안은 `NOW` 먼저, 그다음 `at` 이 최근인 순이다. 카드의 상한은 `CONTINUE` 가 `continueCount`, 나머지가 `maxItemsPerCard` 다. 넘는 항목은 빼고 그 수를 `moreCount` 로 센다
4. 카드 순서는 `NOW` 항목이 하나라도 있는 카드가 먼저이고, 같은 무리 안에서는 `CardKey` 선언 순서다. `unavailable` 의 카드는 항목 없이 `UNAVAILABLE` 로 낸다
5. 비어 있는 카드도 낸다. 카드는 늘 넷이다

`TOO_OLD` 는 각 후보 수집의 조회 기간이 이미 막는다.

### 7. `AttentionService` 와 컨트롤러

- `attention.application.AttentionService`:
  - `view(CurrentUser user)`: 모든 `AttentionCandidates` 를 부르고, 하나가 `RuntimeException` 을 던지면 그 구현의 `cards()` 를 `unavailable` 에 넣고 `log.warn("attention source failed userId={} cards={}", ...)` 만 남긴다. 제어는 이 phase 에서 빈 목록이다. `nowCount` 는 상한으로 잘리기 전 `NOW` 항목 수다
  - `summary(CurrentUser user)`: `view` 와 같은 계산을 하고 `nowCount` 만 쓴다
- `attention.presentation.AttentionController`(`@RequestMapping("/api/v1/attention")`): `GET ""` → `AttentionDtos.ViewResponse`, `GET "/summary"` → `AttentionDtos.SummaryResponse(int nowCount)`
- `attention.presentation.AttentionDtos`: `ViewResponse(Instant readAt, int nowCount, List<CardView> cards)`, `CardView(String key, String status, int nowCount, int moreCount, List<ItemView> items)`, `ItemView(String itemKey, String stateKey, String attention, String title, UUID conversationId, String agentName, Instant at, WhyView why, ExecutionView execution, UUID actionId, FollowUpView followUp)`, `ExecutionView(Long id, String status)`, `FollowUpView(UUID id, Instant dueAt, boolean waiting, boolean proposed)`. 해당하지 않는 칸은 `null` 로 낸다. 화면이 `itemKey` 를 잘라 식별자를 얻지 않게 하려는 것이다, `WhyView(String trigger, List<String> signals, String confidence, List<SourceView> sources)`, `SourceView(String source, String ref, Instant asOf)`. 응답 모양은 `docs/backend/attention.md` 「API」 의 JSON 과 응답 칸 표와 같다. 오류 코드, 모델, 금액 칸을 두지 않는다

### 8. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/attention/AttentionJudgeTest.java` (Spring 없이):

| 입력 | 기대 |
| --- | --- |
| 실패 후보 하나와 같은 대화의 최근 대화 후보 하나 | 실패 카드에만 남고 이어서 하기에서는 `DUPLICATE` 로 빠진다. 실패 항목은 `NOW` |
| `MODEL_INFERRED` 이고 `nowSignal` 참인 후보 | `LATER` |
| `resolved` 참인 후보 | 응답에 없다 |
| 같은 카드, 같은 `itemKey` 와 `stateKey` 를 숨긴 제어 | 빠진다. `stateKey` 가 다르면 다시 보인다 |
| `FAILURES` 에 건 숨기기 제어와 같은 `itemKey` 의 `CONTINUE` 후보 | 실패 카드에서는 빠지고, 이어서 하기 카드에는 그 대화가 보인다. 이어서 하기에 건 제어가 없으면 실패 카드의 숨기기가 이어서 하기에 걸리지 않는다 |
| `snoozedUntil` 이 지금 뒤인 제어와 앞인 제어 | 앞의 것만 빠진다 |
| 맡긴 일 카드에만 `NOW` 가 있다 | 카드 순서가 `DELEGATED`, `FAILURES`, `NEEDS_ME`, `CONTINUE` |
| 한 카드에 후보 12개, 상한 10 | 항목 10개, `moreCount` 2 |
| 한 카드에 `NOW` 후보 12개, 상한 10 | 항목 10개, 카드 `nowCount` 12, 맨 위 `nowCount` 는 카드 `nowCount` 의 합 |
| `CONTINUE` 후보 8개, `continueCount` 5 | 항목 5개, `moreCount` 3 |
| `unavailable` 에 `NEEDS_ME` | 그 카드만 `UNAVAILABLE`, 나머지는 그대로 |

`backend/src/test/java/com/bifos/assistant/attention/AttentionServiceTest.java` (`@SpringBootTest`, `@ActiveProfiles("test")`, 실행 줄을 직접 저장하는 방식은 `backend/src/test/java/com/bifos/assistant/usage/ExecutionLifecycleTest.java` 를 따른다):

| 입력 | 기대 |
| --- | --- |
| 한 사용자의 대화에 `FAILED` 루트 실행 하나 | `failures` 에 `conversation:<UUID>` 항목, `why.signals` 가 `["NOT_RETRIED"]`, `nowCount` 1 |
| 같은 대화에 그 뒤 `SUCCEEDED` 루트 실행이 생긴다 | 실패 항목이 없다 |
| 자동 turn 의 `FAILED` 루트 실행. 그 실행이 시작되기 전 마지막 메시지가 `SYSTEM` 이다 | 실패 항목이 없다 |
| 다시 생성의 `FAILED` 루트 실행. 앞선 메시지가 `ASSISTANT` 와 그 앞의 `USER` 다 | 실패 항목이 있다 |
| 다른 사용자의 `FAILED` 실행 | 응답에 없다 |
| `finishedAt` 이 8일 전인 실패 | 응답에 없다 |
| `PENDING` 승인 줄, `expiresAt` 이 3시간 뒤 | `needs_me` 에 `connector_action:<UUID>`, `EXPIRES_SOON`, `actionId` 가 그 공개 식별자 |
| 40분 전에 시작한 `RUNNING` 위임 실행 | `delegated` 에 `NOW`, `LONG_RUNNING`, `execution` 이 `{ id, status: "RUNNING" }`, `followUp` 은 `null` |
| `AttentionCandidates` 하나를 예외를 던지는 대역으로 바꾼 문맥 | 그 카드만 `UNAVAILABLE` 이고 응답은 200 이다 |
| 응답 JSON 을 문자열로 | `errorCode`, `model`, `estimatedCostMicros` 가 없다 |

`test/e2e/scenarios/attention.ts` (새 시나리오 `attentionScenario`):

- `context.hermes.busy()` 로 dad 의 turn 하나를 `HERMES_BUSY` 로 실패시키고 `finally` 에서 `context.hermes.clearBusy()` 를 부른다(`busy.ts` 와 같은 방법)
- `GET /attention` 의 `failures` 카드에 그 대화의 항목이 `NOW` 로 있고 본문에 `HERMES_BUSY` 가 없다
- 같은 대화에 성공하는 메시지를 다시 보내면 그 항목이 사라진다
- `GET /attention/summary` 의 `nowCount` 가 `GET /attention` 의 `nowCount` 와 같다
- 토큰 없이 부르면 401 이다

`test/e2e/run.ts`: `attentionScenario` 를 import 하고 `SCENARIOS` 에서 `busyScenario` 바로 뒤에 둔다. 앞 줄에 「실패한 turn 을 하나 더 남기므로 사용량 합계를 세는 시나리오 뒤에 둔다」 주석을 단다.

## 검증

```bash
# cwd: backend/
./gradlew test --tests 'com.bifos.assistant.attention.AttentionJudgeTest' --tests 'com.bifos.assistant.attention.AttentionServiceTest' --tests 'com.bifos.assistant.architecture.*'
./gradlew test
./gradlew checkstyleMain checkstyleTest spotlessCheck
```

```bash
# cwd: 저장소 root
node test/e2e/run.ts
scripts/check-mysql-migration.sh
scripts/check-public-safe.sh
grep -rn "errorCode\|estimatedCost" backend/src/main/java/com/bifos/assistant/attention/
```

기대값: 명령이 모두 종료 코드 0 이다. 마지막 `grep` 은 아무것도 내지 않는다.
`scripts/check-mysql-migration.sh` 는 새 저장소 메서드를 `RepositoryQueryMysqlTest` 로 실제 MySQL 에서 실행한다. Docker 가 있어야 한다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageOrder.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/architecture/TopLevelPackageOrderTest.java` | 수정 |
| `docs/backend/packages.md` | 수정 |
| `backend/src/main/java/com/bifos/assistant/attention/domain/type/AttentionTrigger.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/domain/type/AttentionLevel.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/application/model/CardKey.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/application/model/CardStatus.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/application/model/AttentionSignal.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/application/model/AttentionConfidence.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/application/model/AttentionSourceRef.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/application/model/AttentionCandidate.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/application/model/AttentionControl.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/application/model/AttentionItem.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/application/model/AttentionWhy.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/application/model/AttentionCard.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/application/model/AttentionView.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/application/AttentionProperties.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/application/AttentionCandidates.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/application/FailedTurnCandidates.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/application/ApprovalCandidates.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/application/MemoryProposalCandidates.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/application/DelegationCandidates.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/application/RecentConversationCandidates.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/application/AttentionJudge.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/application/AttentionService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/presentation/AttentionController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/presentation/AttentionDtos.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/infra/AgentExecutionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/AttentionExecutionQuery.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/infra/ConnectorActionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/model/PendingApproval.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorActionService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/infra/MemoryRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/OwnConversations.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ChatMessageRepository.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/testsupport/RepositoryQuerySweep.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/attention/AttentionJudgeTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/attention/AttentionServiceTest.java` | 신규 |
| `test/e2e/scenarios/attention.ts` | 신규 |
| `test/e2e/run.ts` | 수정 |

`RepositoryQuerySweep.java` 는 인자 타입을 만들지 못할 때만 고친다. 고치지 않았으면 커밋에서 빠져도 된다.
