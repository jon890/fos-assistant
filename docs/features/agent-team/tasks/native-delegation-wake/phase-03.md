# Phase 03. 자동 turn 에 내장 위임 결과를 넣는다

**Execution profile**: standard

## 목표

끝 소식으로 적은 결과를 ADR-040 의 자동 turn 에 넣는 `NativeDelegationResultSource` 를 만들고, 결과 도착 줄과 출처를 잇는 자리(`notice_message_id`)와 상태 칸을 자동 turn 경로에 더한다.

**범위 외**: 기한과 중지 알림(phase 4), 이력 API 의 `nativeResult` 와 대화 정리(phase 5), 화면(phase 6).

## 컨텍스트

**요구사항**: REQ10(결과로 자동 답을 연다), REQ11(한 호출의 자식은 한 번에), REQ13(대기 글이 먼저이고 이미 읽은 결과는 열지 않는다)

**근거 문서**: `docs/features/agent-team/design.md` 의 「PR6 내장 위임 결과」 → 「자동 turn 에 넣는 결과」, 「이미 읽은 결과」, 「깨우는 때」, 설계 DECISION36~DECISION41, `docs/features/agent-team/spec.md`(DECISION29~DECISION33), ADR-040, ADR-075, `docs/adr/ADR-20261117-native-delegation-wake.md`

- 포트: `chat/delivery/application/port/AutoTurnResultSource.java`. 지금 구현은 `connector/approval/application/ConnectorActionResultSource.java` 하나이고 이 phase 의 구현이 둘째다. 테스트 대역 `testsupport/TestAutoTurnResultSource.java` 도 구현한다.
- `chat/delivery/application/dto/AutoTurnResult.java` 는 `(key, notice, input, item)` record 이고 3인자 생성자가 있다. 생성 호출이 8곳이다(`git grep -n "AutoTurnResult("`).
- `chat/turn/application/ChatDeliveryTurns.java`:
  - `runDelegationResults` 가 위임 결과 알림 줄 하나(있으면 맨 앞)와 출처마다 결과마다 알림 줄을 이 순서로 `notices` 에 쌓는다.
  - `recordDelivery` 가 한 트랜잭션에서 알림 줄 저장, `markDelivered`, 묶음 저장을 한다.
  - `fromUnattended(results)` 는 위임 결과만 본다.
- `chat/turn/application/dto/ChatEvent.java` 는 480줄이고 Java 파일 상한은 500줄이다(`scripts/checks/check-file-length.mjs`). `new ChatEvent(` 호출 17곳이 모두 이 파일 안에 있다.
- 입력 머리줄: `context.application.ResultHeader.render(...)`, `freshnessOf(...)`, 외부 글 감싸기 `shared.util.ExternalData.wrap(...)`. `ConnectorActionResultSource.input` 이 본보기다.
- `AgentExecution` 의 `unattended()` 접근자는 있지만 `markUnattended` 뒤 읽어 둔 엔티티 값이 낡을 수 있다. `AgentExecutionRepository.countUnattendedIn(ids)` 로 읽는다(`ChatDeliveryTurns.fromUnattended` 와 같다).
- `AgentExecutionRepository.java` 는 498줄이라 질의를 더하면 500줄 상한을 넘는다. 새 질의는 새 저장소 `usage/execution/infra/NativeResultReadChecks.java` 에 둔다. 같은 이유로 따로 둔 `ConversationSessionExecutions.java` 가 본보기다.
- 다음 turn 순서는 `NextTurnDispatcher.tryNext`(대기 글 먼저, 그다음 `DelegationWakeService.tryWake`)이고 바꾸지 않는다.
- 통합 테스트 본보기: `backend/src/test/java/com/bifos/assistant/chat/ConnectorActionDeliveryTest.java`(`@BackendIntegrationTest`, `@DelegationWakeEnabled`, 가짜 Hermes 의 입력 확인).
- 테스트 명령은 worker 지침대로 무거운 검사 대기열로 감싸 돌린다.

## 의도 메모

- 「이미 읽은 결과」 는 칸에 적지 않고 질의 조건으로 뺀다(설계 DECISION37, DECISION41). 시각 덧셈을 SQL 에 두지 않는다. H2 와 MySQL 의 함수가 달라서다. 후보를 읽은 뒤 결과마다 Java 에서 `stopped_at + 30초` 를 계산해 실행 저장소에 묻는다.
- 끝난 지 5초가 안 된 결과는 어느 계기에서도 내지 않는다. 한 호출의 끝 소식이 모두 닿기 전에 다른 계기가 깨워 한 호출이 두 답으로 나뉘는 것을 막는다(설계 「깨우는 때」).
- 결과 글은 Control Plane 이 저장한 것을 입력에 넣는다. Hermes 이력의 결과 줄에 기대지 않는다(설계 DECISION38).
- 문맥 항목(`ContextItem`)은 만들지 않는다. 3인자 생성자의 결과처럼 실행에 참조를 남기지 않는다.

## 작업 항목

### 1. `AutoTurnResult` 에 두 칸

- `boolean unattended`, `String noticeStatus` 를 끝에 더한다. 지금 3인자, 4인자 생성자는 `false`, `null` 을 넘기도록 남긴다. `toString()` 에 두 칸을 더한다(글은 넣지 않는다).

### 2. `AutoTurnResultSource.noticesSaved`

`default void noticesSaved(Map<String, Long> messageIdByKey) {}`. Javadoc: 첫 시도의 알림 줄을 저장한 같은 트랜잭션에서 결과 key 마다 그 줄 번호로 부른다. 다시 전달에서는 부르지 않는다.

### 3. `ChatDeliveryTurns`

- `fromUnattended` 가 위임 결과에 더해 `extras` 가운데 `unattended()` 가 참인 것이 있으면 참이다.
- `recordDelivery` 에 인자 `List<AutoTurnResult> extras` 를 더한다(호출부는 `runDelegationResults` 하나다). 트랜잭션 안에서, 알림 줄을 저장한 뒤 출처마다 `key → 그 결과의 알림 줄 번호` 맵을 만들어 `source.noticesSaved(map)` 을 부른다. 줄 번호는 `notices` 를 쌓은 순서(위임 결과 줄이 있으면 첫 줄, 그 뒤 출처 순서, 결과 순서)로 찾는다. 이 순서를 바꾸지 않는다.
- 결과 알림 줄의 `system` 사건에 그 결과의 `noticeStatus` 를 싣는다(아래 4).

### 4. `ChatEvent` 에 `nativeResult`

- 새 record `chat/turn/application/dto/NativeResultView.java`(`String status`)를 만들고 `ChatEvent` 끝에 `NativeResultView nativeResult` 칸을 더한다.
- 4인자 `system(conversationId, messageId, content, deliveredResults)` 를 5인자 `system(..., deliveredResults, nativeResult)` 로 바꾸고, 그 호출부(`ChatDeliveryTurns`)를 고친다. 3인자 `system` 은 그대로다. 새 팩토리를 더하지 않는다. 파일이 500줄을 넘지 않게 하려는 것이다.
- 끝나고 `node scripts/checks/check-file-length.mjs` 가 통과해야 한다.

### 5. 새 `orchestration/application/NativeDelegationResultSource.java`

`AutoTurnResultSource` 구현. `source()` 는 `"NATIVE_DELEGATION"`.

- `undelivered(conversationId)`: 그 대화 실행을 origin 으로 둔 등록 줄 가운데 아래를 모두 맞는 줄을 `stopped_at` 순으로 낸다.
  - `wake_eligible`, `stopped_at` 있음, `result_delivered_at` 없음
  - `stopped_at <= now - 5초`
  - origin 실행의 상태가 `ExecutionStatus.CANCELLED` 가 아니다
  - 이미 읽지 않았다: 그 대화에 「부모 없음, 에이전트가 대화의 에이전트, `hermes_run_id` 있음, `started_at > stopped_at + 30초`」 인 실행이 없다. 이 존재 질의는 새 `NativeResultReadChecks` 에 둔다.
- 결과 하나의 모양:
  - `key`: 등록 줄 번호 글
  - `notice`: `SUCCEEDED` 면 「하위 작업이 끝났어요 · {goal}」, `FAILED` 면 「하위 작업을 하지 못했어요 · {goal}」. goal 은 `goal_preview` 를 연 글이다. 없거나 열지 못하면 「 · 」 뒤를 뺀다.
  - `input`: 첫 줄 「Hermes 하위 작업의 결과가 도착했다.」, 그 아래 `ResultHeader.render("Hermes 하위 작업", [맡긴 일, 상태], stoppedAt, freshness, staleAfter)`, 「같은 결과가 Hermes 대화 이력에도 있다. 이미 정리했으면 다시 길게 정리하지 않는다.」, 결과 글이 있으면 `ExternalData.wrap(결과 글)`.
  - `unattended`: origin 실행이 사람이 없는 실행이면 참
  - `noticeStatus`: `result_status` 이름
- `markDelivered(keys, now)`: `result_delivered_at IS NULL` 인 그 줄들만 적고 바꾼 수를 돌려준다. 숫자로 읽지 못하는 key 는 뺀다.
- `resultsFor(conversationId, userId, keys)`: 그 대화, 그 사용자, `stopped_at` 이 있는 줄만 key 순서로 낸다. 같은 함수로 notice 와 input 을 만든다.
- `conversationsWithUndelivered()`: `undelivered` 와 같은 조건의 대화 번호들.
- `noticesSaved(map)`: `notice_message_id IS NULL` 인 줄만 적는다.
- 저장소 질의는 `HermesSessionBindingRepository` 에 더한다. MySQL 쿼리 규칙(`docs/code-rules/rule-51.md`)을 읽고 따른다.

### 6. 테스트

- `backend/src/test/java/com/bifos/assistant/chat/AutoTurnResultTest.java`: 새 칸의 기본값과 `toString()` 에 글이 없음.
- 새 `backend/src/test/java/com/bifos/assistant/orchestration/NativeDelegationResultSourceTest.java`(`@BackendIntegrationTest`, 줄은 JDBC 로 준비, 시계는 `testsupport/TestClock`):
  - 끝난 지 3초인 결과는 빠지고 10초인 결과는 든다.
  - 대화 에이전트로 제출된 루트 실행이 끝 소식 31초 뒤 시작했으면 빠진다. 10초 뒤면 든다. `@` 맡김(다른 에이전트) 실행이나 `hermes_run_id` 없는 실행은 31초 뒤여도 든다.
  - origin 이 `CANCELLED` 면 빠진다. 깨울 자식이 아니면 빠진다.
  - `markDelivered` 가 두 번째 부름에서 0 을 돌려준다. `resultsFor` 는 다른 사용자의 key 를 뺀다. `noticesSaved` 는 처음 값만 남긴다.
  - 실패 결과의 notice 가 「하위 작업을 하지 못했어요 · 」 로 시작하고, goal 이 없으면 「 · 」 가 없다.
- 새 `backend/src/test/java/com/bifos/assistant/chat/NativeDelegationDeliveryTest.java`(`@BackendIntegrationTest`, `@DelegationWakeEnabled`): 등록 줄을 만들고 `stopped_at` 을 10초 전으로 둔 뒤 `DelegationFinished(대화, null)` 을 낸다.
  - 자동 turn 하나가 열리고 가짜 Hermes 입력에 결과 글이 `<external-data>` 안에 있다. 알림 줄이 남고 `notice_message_id` 가 그 줄이다. 대화 단위 사건(`ConversationEventHub` 구독)의 그 알림 줄 `system` 사건에 `nativeResult.status == "SUCCEEDED"` 가 있다(REQ10).
  - 실패 상태의 결과면 알림 줄이 「하위 작업을 하지 못했어요 · 」 로 시작하고 자동 turn 이 열려 입력의 상태가 `FAILED` 다(REQ12).
  - 대화의 자동 turn 수가 상한(`max-auto-turns`)에 닿아 있으면 자동 turn 이 열리지 않고 한도 알림 줄만 남는다(REQ11).
  - 같은 origin 의 자식 둘이 끝났으면 turn 은 하나이고 알림 줄은 둘이다(REQ11).
  - 도는 turn 이 있는 동안 끝난 결과는 그 turn 이 닫힌 뒤 한 번에 전해진다(REQ11).
  - 대기 글이 있으면 대기 글 turn 이 먼저 돈다(REQ13).
  - 끝 소식 31초 뒤 시작한 사용자 turn 이 있으면 자동 turn 과 알림 줄이 없다(REQ13).
  - 예약 작업 대화(사람이 없는 실행)의 결과로 연 자동 turn 은 사람이 없는 실행으로 적힌다.

## 검증

```bash
cd backend && ./gradlew test --tests '*AutoTurnResultTest' --tests '*NativeDelegationResultSourceTest' --tests '*NativeDelegationDeliveryTest' --tests '*ConnectorActionDeliveryTest' --tests '*ResultDeliveryRecordTest' --tests '*ConnectorDeliveryRetryTest' --tests '*DelegationWakeServiceTest' --tests '*DelegationLinesTest'
cd backend && ./gradlew archTest checkstyleMain checkstyleTest spotlessCheck
node scripts/checks/check-file-length.mjs
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/delivery/application/dto/AutoTurnResult.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/delivery/application/port/AutoTurnResultSource.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/application/ChatDeliveryTurns.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/application/dto/ChatEvent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/application/dto/NativeResultView.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/NativeDelegationResultSource.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/orchestration/infra/HermesSessionBindingRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/execution/infra/NativeResultReadChecks.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/AutoTurnResultTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/NativeDelegationResultSourceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/NativeDelegationDeliveryTest.java` | 신규 |
