# Phase 02. 먼저 알리기 판정에 할 일을 더한다

**Execution profile**: standard

## 목표

지금 화면의 「내 차례」 카드와 건수가 할 일을 읽게 한다. 제안은 `LATER` 로만 보이고, 받아들인 할 일은 기한과 연결한 대화에 도착한 결과로 `NOW` 가 된다.
제안 도구(phase 03)보다 먼저 한다. 지금 화면(plan81 phase 02)이 할 일을 받아들이려면 이 판정이 먼저 있어야 한다.

**범위 외**: 지금 화면의 그리기와 할 일 폼(plan81). 다른 trigger(plan79). 제안 도구와 할 일 문서의 「아직 구현 전」 표시 지우기(phase 03).

## Blocked 조건

- `backend/src/main/java/com/bifos/assistant/attention/application/AttentionCandidates.java` 가 없다 → `PHASE_BLOCKED: plan79 phase 01 의 attention 패키지가 이 브랜치에 없다` 출력 후 종료
- `backend/src/main/java/com/bifos/assistant/attention/domain/AttentionControlEntry.java` 가 없다 → `PHASE_BLOCKED: plan79 phase 02 의 숨기기 표가 이 브랜치에 없다` 출력 후 종료. 아래 테스트 표의 숨김 줄이 그 표와 `AttentionControlService.hide` 로 돈다
- `backend/src/main/java/com/bifos/assistant/followup/application/FollowUpService.java` 가 없다 → `PHASE_BLOCKED: phase 01 이 끝나지 않았다` 출력 후 종료

## 컨텍스트

- 판정 표, 억제 신호, 기준값, 「왜」 칸, 출처 이름은 `docs/backend/attention.md` 가 갖는다. 이 phase 가 구현하는 줄은 그 「후보와 trigger」 표의 `FOLLOW_UP_PROPOSED` 와 `FOLLOW_UP_OPEN` 이다
  - `FOLLOW_UP_PROPOSED`: 카드 `needs_me`, 후보 조건 `PROPOSED`, 늘 `LATER`, 해결 상태는 받아들임과 거절, `itemKey` 는 `follow_up:<공개 식별자>`, `stateKey` 재료는 `updated_at`, 확신도 `MODEL_INFERRED`
  - `FOLLOW_UP_OPEN`: 카드 `needs_me`, 후보 조건 `OPEN`, 기한이 `due-soon` 안이면 `DUE_SOON`, 지났으면 `OVERDUE`, 연결한 대화에 결과가 도착했으면 `LINKED_UPDATE` 로 `NOW`. `waiting` 이 참이면 `WAITING` 신호를 붙인다. `stateKey` 재료는 `updated_at`, 기한 구간(없음, `DUE_SOON`, `OVERDUE`), 연결한 대화의 마지막 결과 전달 시각, 확신도 `USER_CONFIRMED`
  - 「연결한 대화에 결과가 도착했다」 는 그 대화의 맡긴 일(`agent_execution.result_delivered_at`)이나 승인한 동작(`connector_action.result_delivered_at`)의 결과가 할 일의 `accepted_at` 보다 뒤에 전해졌다는 뜻이다. `SYSTEM` 메시지로 판정하지 않는다. 자동 turn 한도 안내(`DelegationWakeService.LIMIT_NOTICE`)와 승인 거절이나 만료 알림(`ConversationNotices.post`)도 `SYSTEM` 메시지라 구분하지 못한다. 사용자가 그 대화에서 주고받는 메시지는 세지 않는다. 대화하는 동안 할 일이 계속 `NOW` 가 되지 않게 하려는 것이다. 사건 표(`attention_event`)는 읽지 않는다
- **`attention.application.AttentionCandidates` 인터페이스를 구현한다.** `Set<CardKey> cards()` 와 `List<AttentionCandidate> read(CurrentUser user, Instant now)` 를 갖는다. `CardKey` 는 `attention.domain.type.CardKey` 다. 원래 기록을 읽지 못하면 예외를 던지고, `AttentionService.view` 가 그 카드를 `UNAVAILABLE` 로 낸다
- 후보 값은 `attention.application.model.AttentionCandidate(CardKey card, String itemKey, String stateKey, AttentionTrigger trigger, boolean resolved, boolean nowSignal, List<AttentionSignal> signals, AttentionConfidence confidence, String title, UUID conversationId, String agentName, Instant at, List<AttentionSourceRef> sources, AttentionExecutionRef execution, UUID actionId, AttentionFollowUpRef followUp)` 다. `AttentionFollowUpRef(UUID id, Instant dueAt, boolean waiting, boolean proposed)` 가 응답의 `followUp` 칸이 된다
- 설정 record 는 `attention.application.AttentionProperties`(`@ConfigurationProperties(prefix = "assistant.attention")`)다. compact 생성자가 0 이하나 빈 값을 기본값으로 바꾼다
- `stateKey` 는 `shared.util.Sha256.hex16(<재료 글>)` 이다. 재료는 `trigger` 이름과 재료 값을 `|` 로 이은 글이고, 시각은 `Instant.toString()` 글이다
- **`attention` 은 다른 패키지의 `infra` 를 import 하지 않는다**(`docs/backend/attention.md` 「패키지」). 그래서 읽기 메서드를 각 패키지의 `application` 에 둔다
  - `followup.application.FollowUpService.openAndProposedOf(Long userId)`: phase 01 의 `FollowUpRepository.findByUserIdAndStatusInOrderByIdAsc` 로 `PROPOSED` 와 `OPEN` 을 읽어 phase 01 의 `followup.application.model.FollowUpSnapshot` 으로 낸다. `conversationPublicId` 는 `ConversationPublicIdLookup.activePublicIdsOf` 로 바꿔 지운 대화는 `null` 이다
  - `usage.application.ConversationResultDeliveries.lastDeliveredAt(Collection<Long> conversationIds)`(새 `@Service`, `@Transactional(readOnly = true)`): 대화마다 `delegation_key` 가 있는 실행의 가장 늦은 `result_delivered_at` 을 `Map<Long, Instant>` 로 낸다. 번호가 비면 읽지 않고 빈 `Map` 을 낸다
  - `connector.application.ConnectorActionService.lastResultDeliveredAt(Collection<Long> conversationIds)`(`@Transactional(readOnly = true)`): 대화마다 가장 늦은 `connector_action.result_delivered_at` 을 `Map<Long, Instant>` 로 낸다. 번호가 비면 읽지 않는다
  - 두 메서드 모두 대화마다 하나씩 읽지 않는다. 저장소의 `select new` 집계 한 번으로 읽는다. 본보기는 `usage.infra.AgentExecutionRepository` 의 `sumCostBetween` 이 `usage.domain.MonthlyCost` record 로 받는 모양이다
- `attention` 은 층 순서의 맨 위라 `followup.application`, `usage.application`, `connector.application` 을 import 해도 된다
- 시계를 옮기는 테스트의 본보기는 `backend/src/test/java/com/bifos/assistant/task/TaskServiceTest.java` 다. `@SpringBootTest`, `@ActiveProfiles("test")`, `@Import(TaskServiceTest.FixedClock.class)` 로 `@TestConfiguration` 의 `@Bean @Primary Clock` 을 넣고, 389줄의 `static final class TestClock extends Clock` 이 `set(Instant)` 로 시각을 옮긴다

**근거 문서**: `docs/backend/attention.md` 의 「후보와 trigger」, 「억제 신호」, 「기준값」, 「왜 보였는가」, `docs/adr/ADR-072-먼저-알리기의-기본값은-알리지-않음이고-control-plane-기록에서-정한-신호만-화면-안에-올린다.md`, `docs/adr/ADR-073-할-일은-에이전트가-제안하고-사람이-받아들인-것만-챙긴다.md`

## 의도 메모

- `MODEL_INFERRED` 인 제안은 다른 억제 신호에 걸리지 않아도 `NOW` 가 되지 못한다. 잘못 뽑은 후보가 건수에 들지 않게 하려는 것이다(ADR-073 의 4번)
- 판정은 할 일을 고치지 않는다. 받아들이기와 끝냄은 phase 01 의 API 로만 한다
- 응답에 할 일 제목을 싣는 것은 `title` 칸 하나다. 로그에는 싣지 않는다
- 연결한 대화가 지워졌으면 그 할 일은 남기되 `conversationId` 를 `null` 로 내고 `LINKED_UPDATE` 를 보지 않는다. 지운 대화의 결과로 할 일을 `NOW` 로 올리지 않는다

## 작업 항목

### 1. 설정 `due-soon`

- `AttentionProperties` 에 `dueSoon`(`Duration`, 기본 24시간) 칸을 더한다. 0 이하나 빈 값이면 compact 생성자가 기본값으로 둔다
- `backend/src/main/resources/application.yml` 의 `assistant.attention` 에 `due-soon: 24h` 와 「기한이 이만큼 남으면 할 일이 지금 볼 것이 된다」 주석을 더한다

### 2. 다른 패키지의 읽기 메서드

| 패키지 | 더하는 것 |
| --- | --- |
| `usage` | 새 projection `usage.domain.ConversationDelivery(Long conversationId, Instant deliveredAt)` |
| `usage` | `AgentExecutionRepository.findLastDeliveredByConversation(@Param("conversationIds") Collection<Long> conversationIds)` → `List<ConversationDelivery>`. JPQL `select new com.bifos.assistant.usage.domain.ConversationDelivery(e.conversationId, max(e.resultDeliveredAt)) from AgentExecution e where e.conversationId in :conversationIds and e.delegationKey is not null and e.resultDeliveredAt is not null group by e.conversationId` |
| `usage` | 새 `usage.application.ConversationResultDeliveries`(`@Service`, `@Transactional(readOnly = true)`)의 `Map<Long, Instant> lastDeliveredAt(Collection<Long> conversationIds)` |
| `connector` | 새 projection `connector.domain.ActionDelivery(Long conversationId, Instant deliveredAt)` |
| `connector` | `ConnectorActionRepository.findLastDeliveredByConversation(@Param("conversationIds") Collection<Long> conversationIds)` → `List<ActionDelivery>`. JPQL `select new com.bifos.assistant.connector.domain.ActionDelivery(a.conversationId, max(a.resultDeliveredAt)) from ConnectorAction a where a.conversationId in :conversationIds and a.resultDeliveredAt is not null group by a.conversationId` |
| `connector` | `ConnectorActionService.lastResultDeliveredAt(Collection<Long> conversationIds)` → `Map<Long, Instant>` |
| `followup` | `FollowUpService.openAndProposedOf(Long userId)` → `List<FollowUpSnapshot>`(`@Transactional(readOnly = true)`) |

새 저장소 메서드는 `RepositoryQueryMysqlTest` 가 실제 MySQL 에서 실행한다. `Collection` 인자는 `RepositoryQuerySweep` 이 이미 만든다.

### 3. 할 일 source

`backend/src/main/java/com/bifos/assistant/attention/application/FollowUpAttentionSource.java` 를 새로 만든다(`@Component`). `AttentionCandidates` 를 구현하고 `cards()` 는 `Set.of(CardKey.NEEDS_ME)` 다.

- `FollowUpService.openAndProposedOf(user.id())` 로 그 사용자의 `PROPOSED` 와 `OPEN` 을 읽는다
- `conversationPublicId` 가 있는 줄의 `conversationId` 만 모아 `ConversationResultDeliveries.lastDeliveredAt` 과 `ConnectorActionService.lastResultDeliveredAt` 을 한 번씩 부른다. 두 시각 가운데 늦은 것을 그 대화의 마지막 결과 전달 시각으로 쓴다
- 기한 구간: `dueAt` 이 없으면 `NONE`, `dueAt` 이 `now` 보다 앞이면 `OVERDUE`, `now + dueSoon` 안이면 `DUE_SOON`, 그 밖은 `NONE`
- `PROPOSED`:
  - `trigger` `FOLLOW_UP_PROPOSED`, `nowSignal` 거짓, `signals` 는 `waiting` 이 참이면 `[WAITING]` 이고 아니면 빈 목록, `confidence` `MODEL_INFERRED`
  - `stateKey` 재료 글은 `"FOLLOW_UP_PROPOSED|" + updatedAt`
  - `at` 은 `createdAt`
- `OPEN`:
  - `trigger` `FOLLOW_UP_OPEN`, `confidence` `USER_CONFIRMED`
  - 신호는 기한 구간이 `DUE_SOON` 이면 `DUE_SOON`, `OVERDUE` 면 `OVERDUE`. 마지막 결과 전달 시각이 `acceptedAt` 보다 뒤면 `LINKED_UPDATE`. `waiting` 이 참이면 `WAITING`. 목록 순서는 `OVERDUE`, `DUE_SOON`, `LINKED_UPDATE`, `WAITING` 이다(화면의 「이유 문구」 가 위쪽 줄을 고른다)
  - `OVERDUE`, `DUE_SOON`, `LINKED_UPDATE` 가운데 하나라도 있으면 `nowSignal` 참, 없으면 거짓
  - `stateKey` 재료 글은 `"FOLLOW_UP_OPEN|" + updatedAt + "|" + 기한 구간 이름 + "|" + (마지막 결과 전달 시각이 없으면 "0", 있으면 그 시각)`. 기한 구간을 넣어야 `LATER` 일 때 숨긴 할 일이 기한이 다가오면 다시 보인다
  - `at` 은 `updatedAt`. `LINKED_UPDATE` 면 `updatedAt` 과 마지막 결과 전달 시각 가운데 늦은 것
- 두 상태 모두 `itemKey` 는 `"follow_up:" + publicId`, `title` 은 할 일 제목, `conversationId` 는 `conversationPublicId`(지운 대화면 `null`), `agentName` 은 `null`, `resolved` 는 거짓이다
- `sources` 는 `[{ source: "FOLLOW_UP", ref: "follow_up:<공개 식별자>", asOf: updatedAt }]` 하나다
- 후보의 `followUp` 칸을 `AttentionFollowUpRef(publicId, dueAt, waiting, proposed)` 로 채운다. `execution` 과 `actionId` 는 `null` 이다
- 숨기기와 미루기, 중복 억제는 `AttentionJudge` 의 공통 단계가 한다. 이 source 는 후보와 신호만 낸다

### 4. 문서

- `docs/backend/attention.md` 머리의 「**할 일 후보(`FOLLOW_UP_PROPOSED`, `FOLLOW_UP_OPEN`)와 `due-soon` 은 아직 구현 전이다.** 구현한 PR 이 이 단락을 지운다.」 단락을 지운다
- `docs/backend/attention.md` 「후보와 trigger」 표 아래의 「연결한 대화에 결과가 도착했다」 문단 끝에 「연결한 대화를 지웠으면 그 할 일은 남고 `conversationId` 는 `null` 이며 결과 도착을 보지 않는다.」 를 더한다
- `docs/README.md` 의 `backend/attention.md` 줄 끝의 「할 일 후보는 아직 구현 전이다」 를 지운다
- `docs/backend/packages.md` 의 `attention` 이 부르는 패키지 문장(「`usage`, `chat`, `agent`, `memory`, `connector` 의 `application` 을 부르고」)에 `followup` 을 더한다
- `docs/prd.md` 「범위와 확인 방법」 표의 ADR-072 줄(「지금 봐야 할 것만 화면 안에서 먼저 알린다」) 확인 칸에서 「오래 도는 맡긴 일」 뒤에 「, 기한이 다가온 할 일」 을 다시 더한다

### 5. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/attention/FollowUpAttentionSourceTest.java`(`@SpringBootTest`, `@ActiveProfiles("test")`, `@Import(FollowUpAttentionSourceTest.FixedClock.class)`. `TaskServiceTest` 의 `TestClock` 과 같은 모양을 이 파일 안에 둔다. 할 일은 `FollowUpRepository` 로 `FollowUp.proposed(...)` 와 `FollowUp.opened(...)` 를 저장한다. 판정은 `AttentionService.view` 로 본다)

| 입력 | 기대 |
| --- | --- |
| `FollowUp.proposed(...)` 로 저장한 `PROPOSED` 하나 | `needs_me` 에 `LATER`, `trigger` 가 `FOLLOW_UP_PROPOSED`, `nowCount` 가 늘지 않는다 |
| 기한이 10시간 뒤인 `OPEN` | `NOW`, 신호 `DUE_SOON` |
| 기한이 지난 `OPEN` | `NOW`, 신호 `OVERDUE` |
| 기한이 없고 `waiting` 이 참인 `OPEN` | `LATER`, 신호 `WAITING` |
| 연결한 대화의 위임 실행 `result_delivered_at` 이 `accepted_at` 뒤인 `OPEN` | `NOW`, 신호 `LINKED_UPDATE` |
| 연결한 대화의 승인 줄 `result_delivered_at` 이 `accepted_at` 뒤인 `OPEN` | `NOW`, 신호 `LINKED_UPDATE` |
| 연결한 대화에 `accepted_at` 뒤의 `USER`, `ASSISTANT` 메시지와 승인 만료 알림(`SYSTEM`)만 있는 `OPEN`(기한 없음) | `LATER`. 신호에 `LINKED_UPDATE` 가 없다 |
| 연결한 대화를 지운 `OPEN`(그 대화에 `accepted_at` 뒤의 결과 전달이 있다) | `LATER`, `conversationId` 가 `null`, 신호에 `LINKED_UPDATE` 가 없다 |
| 그 항목을 `AttentionControlService.hide(user, CardKey.NEEDS_ME, itemKey, stateKey)` 로 숨김 | 응답에 없다. 그 대화에 메시지가 더 와도 나오지 않는다. 새 결과가 전해지면 다시 나온다 |
| 기한이 10시간 뒤인 `OPEN` 의 응답 | `followUp` 칸이 `{ id, dueAt, waiting: false, proposed: false }` 다 |
| 기한이 3일 뒤인 `OPEN` 을 `LATER` 일 때 숨긴 뒤 `TestClock.set` 으로 기한 10시간 전으로 옮김 | 다시 보이고 `NOW`, 신호 `DUE_SOON` |
| `DONE` 이 된 할 일 | 응답에 없다 |
| 아이의 요청 | 아빠의 할 일이 없다 |

## 검증

```bash
# cwd: backend/
./gradlew test --tests 'com.bifos.assistant.attention.*' --tests 'com.bifos.assistant.followup.*'
./gradlew test
./gradlew checkstyleMain checkstyleTest
./gradlew spotlessCheck
```

```bash
# cwd: 저장소 root
scripts/check-mysql-migration.sh
node test/e2e/run.ts
node --test test/unit/doc-references.test.ts
scripts/check-public-safe.sh
scripts/quality.sh check
! grep -n "아직 구현 전" docs/backend/attention.md
```

기대값: 명령이 모두 종료 코드 0 이다. `scripts/check-mysql-migration.sh` 의 `RepositoryQueryMysqlTest` 가 두 집계 쿼리를 실제 MySQL 에서 실행한다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/attention/application/FollowUpAttentionSource.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/attention/application/AttentionProperties.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/attention/FollowUpAttentionSourceTest.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/followup/application/FollowUpService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/ConversationDelivery.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ConversationResultDeliveries.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/infra/AgentExecutionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/domain/ActionDelivery.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorActionService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/infra/ConnectorActionRepository.java` | 수정 |
| `docs/backend/attention.md` | 수정 |
| `docs/README.md` | 수정 |
| `docs/prd.md` | 수정 |
| `docs/backend/packages.md` | 수정 |
