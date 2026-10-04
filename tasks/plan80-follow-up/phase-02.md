# Phase 02. 먼저 알리기 판정에 할 일을 더한다

**Execution profile**: standard

## 목표

지금 화면의 「내 차례」 카드와 건수가 할 일을 읽게 한다. 제안은 `LATER` 로만 보이고, 받아들인 할 일은 기한과 연결한 대화에 도착한 결과로 `NOW` 가 된다.
제안 도구(phase 03)보다 먼저 한다. 지금 화면(plan81 phase 02)이 할 일을 받아들이려면 이 판정이 먼저 있어야 한다.

**범위 외**: 지금 화면의 그리기와 할 일 폼(plan81). 다른 trigger(plan79). 제안 도구와 「아직 구현 전」 표시 지우기(phase 03).

## Blocked 조건

- `backend/src/main/java/com/bifos/assistant/attention` 디렉터리가 없다 → `PHASE_BLOCKED: plan79 phase 01 의 attention 패키지가 main 에 없다` 출력 후 종료
- `backend/src/main/java/com/bifos/assistant/followup/application/FollowUpService.java` 가 없다 → `PHASE_BLOCKED: phase 01 이 끝나지 않았다` 출력 후 종료

## 컨텍스트

- 판정 표, 억제 신호, 기준값, 「왜」 칸은 `docs/backend/attention.md` 가 갖는다. 이 phase 가 구현하는 줄은 그 「후보와 trigger」 표의 `FOLLOW_UP_PROPOSED` 와 `FOLLOW_UP_OPEN` 이다
  - `FOLLOW_UP_PROPOSED`: 카드 `needs_me`, 후보 조건 `PROPOSED`, 늘 `LATER`, 해결 상태는 받아들임과 거절, `itemKey` 는 `follow_up:<공개 식별자>`, `stateKey` 재료는 `updated_at`, 확신도 `MODEL_INFERRED`
  - `FOLLOW_UP_OPEN`: 카드 `needs_me`, 후보 조건 `OPEN`, 기한이 `due-soon` 안이면 `DUE_SOON`, 지났으면 `OVERDUE`, 연결한 대화에 결과가 도착했으면 `LINKED_UPDATE` 로 `NOW`. `waiting` 이 참이면 `WAITING` 신호를 붙인다. `stateKey` 재료는 `updated_at` 과 연결한 대화의 마지막 결과 전달 시각, 확신도 `USER_CONFIRMED`
  - 「연결한 대화에 결과가 도착했다」 는 그 대화의 맡긴 일(`agent_execution.result_delivered_at`)이나 승인한 동작(`connector_action.result_delivered_at`)의 결과가 할 일의 `accepted_at` 보다 뒤에 전해졌다는 뜻이다. `SYSTEM` 메시지로 판정하지 않는다. 자동 turn 한도 안내(`DelegationWakeService.LIMIT_NOTICE`)와 승인 거절이나 만료 알림(`ConversationNotices.post`)도 `SYSTEM` 메시지라 구분하지 못한다. 사용자가 그 대화에서 주고받는 메시지는 세지 않는다. 대화하는 동안 할 일이 계속 `NOW` 가 되지 않게 하려는 것이다. 사건 표(`attention_event`)는 읽지 않는다
- **plan79 phase 01 이 만든 `attention.application.AttentionCandidates` 인터페이스를 구현한다.** 그 인터페이스는 `Set<CardKey> cards()` 와 `List<AttentionCandidate> read(CurrentUser user, Instant now)` 를 갖는다. `AttentionTrigger` 의 `FOLLOW_UP_PROPOSED` 와 `FOLLOW_UP_OPEN` 은 plan79 phase 01 이 미리 둔다. 원래 기록을 읽지 못하면 예외를 던지고, `AttentionService.view` 가 그 카드를 `UNAVAILABLE` 로 낸다. 구현할 때 실제 코드의 이름을 읽어 맞춘다
- **`attention` 은 다른 패키지의 `infra` 를 바로 import 하지 않는다**(plan79 README 의 「모든 phase 에 걸리는 규칙」). 그래서 읽기 메서드를 각 패키지의 `application` 에 둔다
  - `followup.application.FollowUpService.openAndProposedOf(Long userId)`: phase 01 의 `FollowUpRepository.findByUserIdAndStatusInOrderByIdAsc` 로 `PROPOSED` 와 `OPEN` 을 읽어 `followup.application.model.FollowUpSnapshot`(`publicId`, `conversationId`, `conversationPublicId`, `title`, `dueAt`, `waiting`, `status`, `proposed`, `acceptedAt`, `updatedAt`)으로 낸다
  - `usage.application.ConversationResultDeliveries.lastDeliveredAt(Collection<Long> conversationIds)`(새 `@Service`, `@Transactional(readOnly = true)`): 대화마다 `delegation_key` 가 있는 실행의 가장 늦은 `result_delivered_at` 을 `Map<Long, Instant>` 로 낸다. `AgentExecutionRepository` 에 대화 번호 목록으로 한 번에 읽는 메서드를 더한다
  - `connector.application.ConnectorActionService.lastResultDeliveredAt(Collection<Long> conversationIds)`: 대화마다 가장 늦은 `connector_action.result_delivered_at` 을 `Map<Long, Instant>` 로 낸다. `ConnectorActionRepository` 에 같은 모양의 메서드를 더한다
  - 두 메서드 모두 대화마다 하나씩 읽지 않는다. 새 저장소 메서드는 `RepositoryQueryMysqlTest` 가 실제 MySQL 에서 실행한다
- `attention` 은 층 순서의 맨 위라 `followup.application`, `usage.application`, `connector.application` 을 import 해도 된다

**근거 문서**: `docs/backend/attention.md` 의 「후보와 trigger」, 「억제 신호」, 「기준값」, 「「왜 보였는가」」, `docs/adr/ADR-072-먼저-알리기의-기본값은-알리지-않음이고-control-plane-기록에서-정한-신호만-화면-안에-올린다.md`, `docs/adr/ADR-073-할-일은-에이전트가-제안하고-사람이-받아들인-것만-챙긴다.md`

## 의도 메모

- `MODEL_INFERRED` 인 제안은 다른 억제 신호에 걸리지 않아도 `NOW` 가 되지 못한다. 잘못 뽑은 후보가 건수에 들지 않게 하려는 것이다(ADR-073 의 4번)
- 판정은 할 일을 고치지 않는다. 받아들이기와 끝냄은 phase 01 의 API 로만 한다
- 응답에 할 일 제목을 싣는 것은 `title` 칸 하나다. 로그에는 싣지 않는다

## 작업 항목

### 1. 할 일 source

`backend/src/main/java/com/bifos/assistant/attention/application/FollowUpAttentionSource.java` 를 새로 만든다. `AttentionCandidates` 를 구현하고 `cards()` 는 `needs_me` 하나다.

- 사용자 한 사람의 `PROPOSED` 와 `OPEN` 을 읽는다
- `FollowUpService.openAndProposedOf`, `ConversationResultDeliveries.lastDeliveredAt`, `ConnectorActionService.lastResultDeliveredAt` 으로 읽는다. 두 결과 전달 시각 가운데 늦은 것을 그 대화의 마지막 결과 전달 시각으로 쓴다
- `PROPOSED` 는 `FOLLOW_UP_PROPOSED`, `LATER`, 확신도 `MODEL_INFERRED`
- `OPEN` 은 `FOLLOW_UP_OPEN`. `due_at` 이 지금부터 `assistant.attention.due-soon` 안이면 `DUE_SOON`, 지금보다 앞이면 `OVERDUE`, 연결한 대화의 마지막 결과 전달 시각이 `accepted_at` 보다 뒤면 `LINKED_UPDATE`. 셋 가운데 하나라도 있으면 `NOW`, 없으면 `LATER`. `waiting` 이 참이면 `WAITING` 을 신호 목록에 더한다
- `stateKey` 는 `updated_at` 과 연결한 대화의 마지막 결과 전달 시각(없으면 0)으로 만든다
- `sources` 는 `{ source: "FOLLOW_UP", ref: "follow_up:<공개 식별자>", asOf: updated_at }` 하나다. 연결한 대화가 있으면 `conversationId` 칸에 그 공개 식별자를 싣는다
- 후보의 `followUp` 칸을 plan79 phase 01 의 `AttentionFollowUpRef(id, dueAt, waiting, proposed)` 로 채운다. 응답의 `followUp` 칸이 된다(`docs/backend/attention.md` 「API」 의 응답 칸 표). `execution` 과 `actionId` 는 `null` 이다
- 숨기기와 미루기, 중복 억제는 plan79 의 공통 단계가 한다. 이 source 는 후보와 신호만 낸다

### 2. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/attention/FollowUpAttentionSourceTest.java`(`@SpringBootTest`, `@ActiveProfiles("test")`, 시각은 고정 `Clock`)

| 입력 | 기대 |
| --- | --- |
| `FollowUp.proposed(...)` 로 저장한 `PROPOSED` 하나 | `GET /api/v1/attention` 의 `needs_me` 에 `LATER`, `trigger` 가 `FOLLOW_UP_PROPOSED`, `nowCount` 가 늘지 않는다 |
| 기한이 10시간 뒤인 `OPEN` | `NOW`, 신호 `DUE_SOON` |
| 기한이 지난 `OPEN` | `NOW`, 신호 `OVERDUE` |
| 기한이 없고 `waiting` 이 참인 `OPEN` | `LATER`, 신호 `WAITING` |
| 연결한 대화의 위임 실행 `result_delivered_at` 이 `accepted_at` 뒤인 `OPEN` | `NOW`, 신호 `LINKED_UPDATE` |
| 연결한 대화의 승인 줄 `result_delivered_at` 이 `accepted_at` 뒤인 `OPEN` | `NOW`, 신호 `LINKED_UPDATE` |
| 연결한 대화에 `accepted_at` 뒤의 `USER`, `ASSISTANT` 메시지와 승인 만료 알림(`SYSTEM`)만 있는 `OPEN`(기한 없음) | `LATER`. 신호에 `LINKED_UPDATE` 가 없다 |
| 그 항목을 같은 `card`, `stateKey` 로 숨김 | 응답에 없다. 그 대화에 메시지가 더 와도 나오지 않는다. 새 결과가 전해지면 다시 나온다 |
| 기한이 10시간 뒤인 `OPEN` 의 응답 | `followUp` 칸이 `{ id, dueAt, waiting: false, proposed: false }` 다 |
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
scripts/check-public-safe.sh
scripts/quality.sh check
```


## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/attention/application/FollowUpAttentionSource.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/attention/FollowUpAttentionSourceTest.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/followup/application/FollowUpService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/followup/application/model/FollowUpSnapshot.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ConversationResultDeliveries.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/usage/infra/AgentExecutionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorActionService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/infra/ConnectorActionRepository.java` | 수정 |
