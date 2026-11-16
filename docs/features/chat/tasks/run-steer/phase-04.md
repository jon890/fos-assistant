# Phase 04. turn 이 끝날 때의 정리, 다음 turn, 기동 정리, 다시 생성, 이력

**Execution profile**: standard

## 목표

turn 이 어떻게 끝나든 그 turn 의 말풍선을 정리해 돌아온 글은 바로 다음 차례로, 모르는 글은 멈춘 대기 줄로 되돌린다. 다음 turn 은 말풍선을 새로 만들지 않고, 서버가 다시 떠도 남은 말풍선과 「끼우는 중」 을 정리하며, steer 가 낀 답은 다시 생성하지 않고, 이력이 상태를 싣는다.

**범위 외**: 화면(PR2), 계약 문서(phase 5).

## 컨텍스트

**요구사항**: REQ3, REQ4, REQ7

**근거 문서**: `docs/features/chat/design.md` 의 「turn 이 끝날 때(`settle`)」, 「대기 글로 여는 turn」, 「실패와 일관성」

- `ChatTurnRunner.runTurn`: 결과 판정, 중지(`chatTurnStopper.stoppedTurn`), 예외(`HermesCallFailure`, `HERMES_RUN_TIMEOUT`, 그 밖)가 모두 바깥 `try` 의 `finally` 를 지난다. `existingHandle` 이 있으면 부르는 쪽(`ChatService.stream` 등)이 잠금을 닫는다. 그래서 정리는 `closesHandle` 과 상관없이 그 `finally` 의 맨 앞에서 부른다.
- 다음 turn: `ChatService.runPendingMessages` 가 `queue.merged()` 로 Hermes 입력과 질문 글을 함께 만들고 `TurnIntent.Fresh(ids)` 로 `ChatTurnRouting.saveQuestion` 이 행을 저장한다.
- 다시 생성: `ChatService.regenerate` 가 `ChatTurnRouting.previousQuestion` 으로 질문 행을 고른다. steer 행이 답 바로 앞 `USER` 행이라 그것이 골린다.
- 기동 정리: `chat/recovery/application/RestartReconciler.settleByAsking` 의 `FINISHED`·`NOT_FOUND` 갈래. `onReady`(`@Order(0)`)는 `properties.enabled()` 가 꺼지면 아무것도 하지 않는다.
- 이력 응답: `chat/turn/presentation/ChatDtos.MessageView`.

## 의도 메모

- 포트 `chat/turn/application/port/TurnSteerLedger` 를 이 phase 에서 만들고 phase 3 의 `SteerLedger` 가 구현한다. `SteerLedger` 는 `NextTurnDispatcher` 를 주입하지 않으므로 빈 순환이 없다.
- 정리의 상태 변경·되돌리기·멈춤은 한 트랜잭션이다. 실패하면 경고를 남기고 그 대화 번호를 `SteerLedger` 의 메모리 집합 「정리 못 함」 에 넣는다. `NextTurnDispatcher.tryNext` 가 맨 앞에서 `ledger.resettleIfMarked(conversationId)` 를 부르고, 기동 정리도 같은 일을 한다. 행이 `DELIVERED` 로 남아 다시 찾을 수 있다.
- steer 의 기동 정리는 재시작 정리 설정(`RestartReconciler` 의 `enabled`)에 묶지 않는다. 설정이 꺼져 있어도 남은 글을 잃지 않게 `SteerLedger` 자신의 기동 리스너로 돈다.
- 상한으로 멈춘 살펴보기 turn 은 steer 대상이 아니라 말풍선이 없다.

## 작업 항목

### 1. 포트 `chat/turn/application/port/TurnSteerLedger.java`(신규)와 `SteerSettlement`

```java
void settle(Long rootExecutionId, Long conversationId, SteerSettlement settlement);  // handle 은 구현이 실행 번호로 찾는다(포트가 application 클래스를 받지 않는다)
void carryForward(Long conversationId, List<Long> messageIds);  // saveQuestion 트랜잭션 안
void announce(Conversation conversation, List<Long> messageIds);  // 트랜잭션 뒤 사건
```

`chat/turn/application/dto/SteerSettlement`: `record (Kind kind, String pendingSteer)`, `Kind { COMPLETED, STOPPED, UNKNOWN }`.

### 2. `SteerLedger` 가 포트를 구현

- `settle`: `turns.find(rootExecutionId)` 로 handle 이 있으면 `steerLock` 안에서 `steerSettled=true` 를 세운다. 그 실행의 `DELIVERED` 행을 번호 순서로 읽고 없으면 끝.
  - `COMPLETED` + `pendingSteer` null: 그대로.
  - `COMPLETED` + `pendingSteer`: 행 글을 `\n` 으로 이은 것의 뒤쪽 묶음(마지막 k 개, k 가 큰 것부터) 가운데 이은 글이 `pendingSteer` 와 같은 것을 고르고, 없으면 모두. 고른 행을 `CARRIED_OVER` 로 바꾸고 `prependCarried`. 멈춤은 그대로.
  - `STOPPED`·`UNKNOWN`: 모두 `UNCONFIRMED`, `prependCarried`, `held=true`.
  - 바뀐 행마다 `steer` 사건, 끝에 `pending` 사건. 실패하면 「정리 못 함」 집합.
- `carryForward`: 그 행들을 `CARRIED_OVER` 로 바꾸고 `steer` 사건.
- `resettleIfMarked(conversationId)`: 집합에 있으면 `resettleFinished(conversationId)`.
- `resettleFinished(conversationId)`: 그 대화에서 실행이 이미 끝났는데(실행 줄 상태가 `RUNNING` 이 아님) `DELIVERED` 로 남은 행을 실행마다 `UNKNOWN` 으로 정리한다.
- `resolveStaleSteering(conversationId)`: 남은 `steering` 글을 phase 3 의 `unknown` 과 같이 처리한다(실행 번호는 그 대화의 마지막 루트 실행).
- `@EventListener(ApplicationReadyEvent.class) @Order(-1) onStartup()`: 대기 줄에 `steering` 글이 있는 대화와 `DELIVERED` 행이 있는 대화를 찾아 위 둘을 부른다. 한 대화가 실패해도 다음 대화를 이어 간다.

### 3. `ChatTurnRunner`

`runTurn` 바깥 `finally` 맨 앞에서 `steerLedger.settle(executionId, conversationId, settlement)` 를 부른다. 결과가 있으면 완료·실패는 `COMPLETED(result.pendingSteer())`, 취소·중지 확인은 `STOPPED`. 결과 없이 예외면 `UNKNOWN`. 실행 줄을 만들기 전에 끝났으면 부르지 않는다. 정리의 예외를 삼켜 원래 예외를 가리지 않는다.

### 4. 다음 turn

- `TurnIntent.Fresh` 에 `List<Long> carriedMessageIds` 와 `String questionText` 를 더한다. 기존 한 인자 생성자는 빈 목록과 null(= 입력 글 그대로)을 넘겨 `MentionInputs`, `ChatTurnQuestionTest`, `TurnIntentByUserTest` 는 고치지 않는다.
- `runPendingMessages`: Hermes 입력은 `queue.merged()`, `Fresh(ids, queue.carriedMessageIds(), queue.questionText())`.
- `saveQuestion`: `carriedMessageIds` 가 있으면 같은 트랜잭션에서 `steerLedger.carryForward` 를 부른다. `questionText` 가 비어 있으면 새 행을 만들지 않고 대기 글만 빼고 첫 말풍선 번호를 돌려준다. 비어 있지 않으면 그 글로 새 행을 만든다. 자동 turn 수 초기화와 목록 보이기는 지금처럼 한다. `user` 사건은 새 행이 있을 때만.

### 5. `NextTurnDispatcher.tryNext`

맨 앞에서 `steerLedger.resettleIfMarked(conversationId)`.

### 6. 다시 생성

`regenerate` 가 고른 `question.steerState() != null` 이면 `MESSAGE_NOT_LATEST`("the latest answer has a steered message")로 거절한다.

### 7. 기동 정리와 재시작 정리

`RestartReconciler` 는 파일 길이 기준에 닿아 고치지 않는다. 재시작 정리가 줄을 적는 `RecoveredRunRecorder.settle`·`failWithout` 이 루트 줄(`row.id().equals(row.treeRootId())`)을 정하면 `FINISHED` 는 결과 상태로(`cancelled` 면 `STOPPED`, 아니면 `COMPLETED(pendingSteer)`), `NOT_FOUND`·상한 초과는 `UNKNOWN` 으로 `steerLedger.settle(rootExecutionId, conversationId, ...)` 를 부른다.

### 8. 이력

`MessageView` 에 `SteerView steer`(`state`, `child`). steer 행이 아니면 null.

### 9. 시험 `backend/src/test/java/com/bifos/assistant/chat/SteerSettleTest.java`(신규)

통합 시험(기존 대기열 통합 시험의 틀, `StubHermesRunsClient`):
- 완료 + `pending_steer` 가 마지막 글 하나 → 그 행만 `CARRIED_OVER`, 다음 turn 이 열리고 새 `USER` 행이 없으며 다음 실행의 질문 번호가 그 행.
- 완료 + `pending_steer` 가 두 글을 이은 것 → 둘 다. 어느 묶음과도 다름 → 모두.
- 완료 + `pending_steer` 없음 → `DELIVERED` 유지, 다음 turn 없음.
- 중지 → `UNCONFIRMED`, 멈춘 줄에 말풍선 번호 글. 「보내기」(release) → `CARRIED_OVER`, 새 행 없음. 취소 → 행은 `UNCONFIRMED` 그대로.
- 결과 조회가 404·시간 초과로 던짐 → `UNCONFIRMED`·held.
- steer 응답을 기다리는 중 turn 이 끝남(`beforeSteer` 로 끝냄) → 받은 행도 정리. 정리 뒤 온 steer → 호출 없이 대기 칸.
- 기동 정리: 남은 `steering` 글 → `UNCONFIRMED`·held. 끝난 실행의 `DELIVERED` 행 → `UNCONFIRMED`. 재시작 정리가 `FINISHED` + `pending_steer` → `CARRIED_OVER`.
- 정리 실패를 흉내 냄 → 다음 `tryNext` 가 다시 정리.
- 다시 생성 거절.
- 기존 대기열·중지 시험이 그대로 통과(REQ8).

## 검증

```bash
cd backend && HEAVY_SLOTS=2 orch slot heavy -- ./gradlew test --tests '*SteerSettleTest' --tests '*SteerServiceTest' --tests '*Pending*' --tests '*Regenerate*' --tests '*RestartReconciler*' --tests '*ChatTurnQuestionTest' --tests '*TurnIntentByUserTest' --tests '*ArchitectureRules*'
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/turn/application/port/TurnSteerLedger.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/application/dto/SteerSettlement.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/nextturn/application/SteerLedger.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/nextturn/application/NextTurnDispatcher.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/application/ChatTurnRunner.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/application/ChatTurnRouting.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/application/dto/TurnIntent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/presentation/ChatDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/presentation/ChatController.java` | 수정(이력의 steer 칸) |
| `backend/src/main/java/com/bifos/assistant/chat/recovery/application/RecoveredRunRecorder.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/conversation/infra/ChatMessageRepository.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/SteerSettleTest.java` | 신규 |
