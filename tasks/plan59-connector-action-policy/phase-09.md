# Phase 09. 승인 결과를 요청이 나온 대화에 전한다

**Execution profile**: deep

## 목표

승인 요청이 생기면 그 대화에 `approval` 사건을 낸다. 실행 결과가 나오면 알림 줄을 남기고 자동 turn 으로 모델에게 전한다. 거절과 만료는 알림 줄만 남긴다.
ADR-040 의 깨우기를 승인 결과로 넓힌다.

**범위 외**: 화면(phase 10).

## 컨텍스트

- 깨우기는 `chat/application/DelegationWakeService.java` 다. `tryWake(Long conversationId)` 가 `executions.findUndeliveredResults(conversationId)` 로 전할 위임 결과를 찾고, 연속 상한(`conversation.autoTurnCount() >= properties.maxAutoTurns()`)과 잠금(`turns.open`)을 지나 가상 스레드에서 `chat.runDelegationResults(owner, conversationId, handle, onEvent)` 를 부른다. turn 이 닫힐 때(`turns.addCloseListener`)와 기동할 때(`wakeAfterStartup`, `@Order(10)`)도 `tryWake` 를 부른다
- `chat/application/ChatService.java` 의 `runDelegationResults` 가 결과를 다시 읽어 `delegationInput` 으로 Hermes 입력을 만들고 `TurnIntent.DelegationResults(ids, notice)` 로 `runTurn` 을 돌린다. `saveQuestion` 이 한 트랜잭션에서 알림 줄 저장(`ChatMessage.fromSystem`), `markResultDelivered`, `incrementAutoTurns` 를 하고 `ChatEvent.system(...)` 을 낸다
- `chat/application/TurnIntent.java` 는 sealed 이고 `Fresh`, `Regenerate`, `DelegationResults(List<Long> executionIds, String notice)` 셋이다. `DELEGATION_RESULTS_INSTRUCTION` 이 모델 지침이다
- 사건은 `chat/application/ChatEvent.java` 의 record 하나이고 종류는 `type` 문자열이다. `ConversationEventHub.publish(Long conversationId, ChatEvent event)` 가 대화 단위 SSE 로 보낸다. 한도 알림을 남기는 본보기는 `DelegationWakeService` 의 `noticeLimitReached` 다
- phase 08 이 `ConnectorActionChanged(Long conversationId, UUID actionId)` 사건을 낸다. 승인 줄 생성, 승인 뒤 결과, 거절, 만료에서 나온다. `ConnectorAction.markDelivered(Instant)` 와 `resultDeliveredAt` 이 있다
- 레이어 규칙: `chat → connector` 간선이 순환을 만들지 않는지 `./gradlew archTest` 로 본다. `connector` 는 `chat` 을 import 하지 않는다
- 테스트 본보기는 `backend/src/test/java/com/bifos/assistant/chat/DelegationWakeServiceTest.java`(`@SpringBootTest(properties = "assistant.delegation-wake.enabled=true")`, `@Import(ChatServiceTest.StubRuntime.class)`)다

**근거 문서**: `docs/connectors.md` 의 「승인」, `docs/flow.md` 의 「승인이 필요한 호출」 과 「위임 결과가 도착했을 때」, `docs/adr/ADR-040-위임-결과는-control-plane-이-부모-대화의-다음-turn-을-열어-전한다.md`, `docs/adr/ADR-048-커넥터-쓰기는-control-plane-이-승인-줄을-저장하고-승인한-인자로-한-번만-실행한다.md`

## 의도 메모

- 위임 결과와 승인 결과를 한 번의 자동 turn 에 모아 전한다. 따로 열면 연속 상한을 두 배로 쓴다
- 거절과 만료는 자동 turn 을 열지 않는다. 알림 줄만 남기고 `markDelivered` 한다. 다음 질문 때 모델이 이력으로 본다
- 승인 결과의 본문은 Hermes 입력에만 넣는다. 알림 줄에는 도구의 사람 말과 상태만 쓴다
- `assistant.delegation-wake.enabled` 가 false 이면 자동 turn 은 열지 않되 알림 줄과 `approval` 사건은 낸다

## 작업 항목

### 1. `connector` 쪽 조회

`ConnectorActionService` 에 더한다.

```java
/** 그 대화에서 결과를 아직 전하지 않은 승인 줄이다. 상태가 SUCCEEDED, FAILED, UNKNOWN 인 것만. 만든 순. */
List<ConnectorActionResult> undeliveredResults(Long conversationId);
/** 그 대화에서 알림 줄만 남길 줄이다. 상태가 REJECTED, EXPIRED 이고 아직 전하지 않은 것. */
List<ConnectorActionResult> undeliveredClosures(Long conversationId);
void markDelivered(List<UUID> actionIds, Instant now);
List<Long> conversationsWithUndelivered();
```

`connector/application/model/ConnectorActionResult.java`: `record ConnectorActionResult(UUID actionId, String title, ActionStatus status, String errorCode, String resultText)`.
`markDelivered` 는 `resultDeliveredAt is null` 인 줄만 바꾼다.

### 2. `chat` 쪽 연결

`chat/application/ConnectorActionListener.java`(신규, `@Component`): `@EventListener` 로 `ConnectorActionChanged` 를 받는다.

1. `hub.publish(conversationId, ChatEvent.approval(conversationPublicId, actionId))` 를 낸다
2. `undeliveredClosures` 가 있으면 줄마다 알림 줄을 저장하고 `ChatEvent.system(...)` 을 낸 뒤 `markDelivered` 한다
3. `wake.tryWake(conversationId)` 를 부른다

알림 줄의 글이다. `<제목>` 은 `ConnectorActionResult.title` 이다.

| 상태 | 글 |
| --- | --- |
| `REJECTED` | `<제목> 요청을 거절했어요` |
| `EXPIRED` | `<제목> 요청이 승인 없이 만료됐어요` |
| `SUCCEEDED` 하나 | `승인한 <제목> 실행이 끝났어요` |
| `FAILED` 하나 | `승인한 <제목> 실행이 실패했어요` |
| `UNKNOWN` 하나 | `승인한 <제목> 을 실행했는지 알 수 없어요. 그 서비스에서 확인해 주세요` |
| 결과 여럿 | `승인한 동작 N개의 결과가 도착했어요` |

`ChatEvent` 에 `static ChatEvent approval(UUID conversationId, UUID actionId)` 를 더한다. `type` 은 `"approval"`, 번호는 기존 칸 `detail` 에 글로 넣는다. `forViewer` 가 `tool` 사건의 `detail` 만 가리는지 확인하고 `approval` 의 `detail` 은 그대로 나가게 둔다.

### 3. 깨우기 넓히기

- `DelegationWakeService.tryWake` 가 「전할 것이 있는가」 를 위임 결과와 `actions.undeliveredResults(conversationId)` 둘로 본다. 둘 다 비면 끝낸다
- `wakeAfterStartup` 이 `actions.conversationsWithUndelivered()` 의 대화도 훑는다. 기동 정리(`ConnectorActionSweeper`, `@Order(5)`)가 `UNKNOWN` 으로 바꾼 줄이 여기서 전해진다
- `ChatService.runDelegationResults` 가 승인 결과도 다시 읽어 입력에 더한다. 위임 결과가 없고 승인 결과만 있어도 돈다
  - 입력의 승인 단락은 `"승인한 동작의 결과가 도착했다."` 뒤에 결과마다 `[동작: <제목>, 요청 번호: <actionId>, 상태: <S>(, 오류: <code>)]` 와 `resultText` 를 잇는다. `UNKNOWN` 은 `실행 여부를 알 수 없다. 다시 실행하지 말고 사용자에게 확인을 부탁한다.` 를 본문으로 쓴다
  - 알림 줄은 위임 알림과 승인 알림을 `\n` 없이 한 문장씩, 위임 먼저 승인 뒤의 순서로 각각 한 줄씩 남긴다. 줄마다 `ChatEvent.system` 을 낸다
- `TurnIntent.DelegationResults` 에 `List<UUID> actionIds` 를 더한다. `saveQuestion` 이 같은 트랜잭션에서 `actions.markDelivered(actionIds, now)` 를 부른다
- 모델 지침 `DELEGATION_RESULTS_INSTRUCTION` 에 승인 결과에 대한 문장을 더한다: 승인한 동작은 이미 실행됐으므로 같은 도구를 다시 부르지 않는다
- `rememberFailureIfUndelivered` 같은 실패 뒤 처리가 위임 결과만 보면 승인 결과도 보게 고친다

### 4. 이 phase 를 검증하는 테스트

`backend/src/test/java/com/bifos/assistant/chat/ConnectorActionDeliveryTest.java`(신규, `DelegationWakeServiceTest` 의 기반):

| 상황 | 기대 |
| --- | --- |
| 승인 줄이 생긴다 | 그 대화의 hub 에 `type: "approval"` 사건, `detail` 이 그 번호 |
| 승인해 `SUCCEEDED` | 알림 줄 `승인한 <제목> 실행이 끝났어요`, 자동 turn 의 답이 대화에 남음, Hermes 입력에 결과 본문과 요청 번호, 줄의 `result_delivered_at` 채움, `autoTurnCount` 1 |
| `UNKNOWN` | 알림 줄이 「알 수 없어요」, 자동 turn 이 열림, 입력에 「다시 실행하지 말고」 |
| 거절 | 알림 줄 하나, 자동 turn 없음(Hermes 제출 수가 늘지 않음), `result_delivered_at` 채움 |
| 만료(`expire(now)`) | 알림 줄 하나, 자동 turn 없음 |
| 그 대화의 turn 이 도는 중에 승인 | 결과가 쌓였다가 turn 이 닫힌 뒤 전해짐 |
| 위임 결과와 승인 결과가 함께 | 자동 turn 한 번, 알림 줄 둘, 입력에 두 단락 |
| 연속 상한에 닿음 | 자동 turn 없이 한도 알림 |
| 기동 훑기(`wakeAfterStartup`) | 전하지 않은 승인 결과가 있는 대화를 깨움 |
| 대화가 없는 승인 줄(`conversationId` null) | 아무 사건도 없고 예외도 없음 |
| `assistant.delegation-wake.enabled=false` | `approval` 사건과 거절 알림 줄은 나오고 자동 turn 은 없음 |

`DelegationWakeServiceTest` 와 `ChatServiceTest` 의 기존 테스트가 그대로 통과해야 한다. `TurnIntent.DelegationResults` 생성자 호출을 새 칸에 맞춘다.

### 5. e2e

`test/e2e/scenarios/connector-policy.ts` 에 더한다. 승인한 뒤 그 대화의 메시지 목록에 `SYSTEM` 알림 줄과 그 뒤의 `ASSISTANT` 답이 생길 때까지 기다린다(`delegation` 시나리오의 `within` 방식). 거절한 요청은 알림 줄만 있고 가짜 Hermes 의 `submitCount()` 가 늘지 않는다.
e2e 의 Control Plane 이 깨우기를 켜고 뜨는지 `test/e2e/run.ts` 의 환경 변수를 읽어 확인한다. 꺼져 있으면 자동 turn 단언을 빼고 알림 줄만 본다.

### 6. `docs/` 갱신

- `docs/code-architecture.md` 의 「위임 결과로 부모 대화를 깨우기」 절에 승인 결과를 함께 전한다는 것과 `ConnectorActionListener` 를 더한다. 「화면으로 보내는 사건」 절의 사건 표에 `approval` 을 더한다
- `docs/flow.md` 의 「위임 결과가 도착했을 때」 에 승인 결과가 같은 turn 에 실린다는 한 줄을 더한다
- `docs/adr/ADR-040-*.md` 는 고치지 않는다. 넓힌 결정은 ADR-048 이 갖는다

## 검토 반영

**이 절이 위의 내용과 다르면 이 절을 따른다.**

- **방향은 `connector → chat` 하나다. `chat` 은 `connector` 를 import 하지 않는다.** 위 작업 항목 2, 3 의 배치를 아래로 바꾼다
  - `chat/application/AutoTurnResultSource.java`(신규, 인터페이스): `List<AutoTurnResult> undelivered(Long conversationId)`, `void markDelivered(List<String> keys, Instant now)`, `List<Long> conversationsWithUndelivered()`
  - `chat/application/model/AutoTurnResult.java`(신규): `record AutoTurnResult(String key, String notice, String input)`. `notice` 는 알림 줄 글, `input` 은 Hermes 입력에 넣을 단락이다
  - `chat/application/ConversationNotices.java`(신규, `@Service`): `void post(Long conversationId, String text)`. `ChatMessage.fromSystem` 을 저장하고 `ChatEvent.system` 을 hub 에 낸다. 대화가 없거나 지워졌으면 아무것도 하지 않는다
  - `DelegationWakeService` 와 `ChatService` 는 `List<AutoTurnResultSource>` 를 주입받는다. 구현이 하나도 없어도 돈다. 위임 결과가 없어도 source 의 결과가 있으면 깨운다. `wakeAfterStartup` 은 source 들의 `conversationsWithUndelivered()` 도 훑는다
  - `connector/application/ConnectorActionResultSource.java`(신규, `@Component`, `AutoTurnResultSource` 구현): `SUCCEEDED`, `FAILED`, `UNKNOWN` 이고 전하지 않은 승인 줄을 낸다. `key` 는 `actionId` 의 글이다. `notice` 와 `input` 의 글은 위 작업 항목의 표와 단락 모양을 그대로 쓴다. 결과가 여럿일 때 하나로 줄이는 것은 하지 않는다. 줄마다 알림 줄 하나다
  - `connector/application/ConnectorActionListener.java`(신규, `@Component`. `chat` 이 아니라 `connector` 에 둔다): `ConnectorActionChanged` 를 받아 `ConversationEventHub.publish(conversationId, ChatEvent.approval(...))`, 거절과 만료의 알림 줄은 `ConversationNotices.post` 뒤 `markDelivered`, 끝으로 `DelegationWakeService.tryWake(conversationId)`. `approval` 사건의 대화 공개 식별자는 `ConversationLookup` 에 `Optional<UUID> publicIdOf(Long conversationId)` 를 더해 읽는다
- **알림 줄은 여럿이다.** `TurnIntent.DelegationResults` 를 `DelegationResults(List<Long> executionIds, List<String> notices, List<AutoTurnDelivery> deliveries)` 로 바꾼다. `chat/application/model/AutoTurnDelivery.java`(신규): `record AutoTurnDelivery(AutoTurnResultSource source, List<String> keys)`. `saveQuestion` 은 `notices` 마다 알림 줄을 저장하고 사건을 내고, 같은 트랜잭션에서 `delivery.source().markDelivered(delivery.keys(), now)` 를 부른다. 위임 결과가 없으면 위임 알림 줄을 만들지 않는다
- e2e 의 Control Plane 은 깨우기를 켠 채 뜬다(`application.yml` 기본값). 자동 turn 단언을 그대로 둔다
- 테스트 표의 「결과 여럿 → `승인한 동작 N개의 결과가 도착했어요`」 는 뺀다. 줄마다 알림 줄 하나다

## 검증

```bash
# cwd: 저장소 root
(cd backend && ./gradlew test --tests '*ConnectorActionDeliveryTest' --tests '*DelegationWakeServiceTest' --tests '*ChatServiceTest')
(cd backend && ./gradlew test)
(cd backend && ./gradlew qualityCheck)
node test/e2e/run.ts
scripts/check-public-safe.sh
```

- 모두 종료 코드 0

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorActionService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/model/ConnectorActionResult.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/infra/ConnectorActionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorActionListener.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorActionResultSource.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/AutoTurnResultSource.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ConversationNotices.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ConversationLookup.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/model/AutoTurnResult.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/model/AutoTurnDelivery.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatEvent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/DelegationWakeService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/TurnIntent.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ConnectorActionDeliveryTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/DelegationWakeServiceTest.java` | 수정 |
| `test/e2e/scenarios/connector-policy.ts` | 수정 |
| `docs/code-architecture.md` | 수정 |
| `docs/flow.md` | 수정 |
