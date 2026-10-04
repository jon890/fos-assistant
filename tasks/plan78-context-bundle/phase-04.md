# Phase 04. 결과 항목의 출처 머리줄과 신선도, 참조 저장

**Execution profile**: deep

## 목표

자동 turn 이 전하는 위임 결과와 승인한 동작의 결과를 문맥 묶음 항목(`DELEGATION_RESULT`, `CONNECTOR_RESULT`)으로 만들고, `input` 에 출처 머리줄과 끝난 시각과 신선도를 싣는다.
오래 지나 다시 전한 결과가 지금 상태처럼 읽히지 않게 한다. 결과 항목의 참조도 phase 03 이 만든 `execution_context_source` 에 남긴다. 이 phase 로 ADR-071 의 구현이 끝난다.

**범위 외**: 전달 상태와 다시 전달을 고르는 규칙(#162), Memory 항목(phase 02), 참조 표와 화면(phase 03). 다시 전달하는 turn 도 같은 머리줄과 항목을 쓰는 것은 이 phase 에 든다. `STALE` 은 사실상 다시 전달할 때만 생기기 때문이다.

## 선행 조건

#162 는 PR #167 로 main 에 들어왔다. 이 phase 는 그 뒤의 코드를 기준으로 쓴다.

## 컨텍스트

- 두 전달 경로: `ChatService.runDelegationResults`(자동 turn)와 `ChatService.retryDelivery`(사용자가 다시 전달, `retryInput` 이 저장된 결과를 다시 읽는다)가 모두 private static `deliveryInput(List<AgentExecution>, Map<Long, Agent>, List<AutoTurnResult>)` 로 입력을 만든다. 위임 결과 단락이 먼저이고 그 밖의 결과(`AutoTurnResult.input()`)를 빈 줄로 잇는다. 두 경로 모두 `runDeliveryTurn(..., new TurnIntent.DelegationResults(attemptId, retry), ...)` 로 turn 을 돈다
- 다시 전달은 그 밖의 결과를 `AutoTurnResultSource.resultsFor(conversationId, userId, keys)` 로 다시 읽는다. 처음 전달은 `undelivered(conversationId)` 다
- 위임 결과의 글: `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` 의 private static `delegationInput(List<AgentExecution>, Map<Long, Agent>)`. 첫 줄 `맡긴 일의 결과가 도착했다.` 뒤로 결과마다 `\n\n[에이전트: <이름>, 실행 번호: <번호>, 상태: <상태>]`(`FAILED` 면 `, 오류: <코드>`)와 본문을 잇는다. 커넥터 에이전트의 답(`isExternalResult`)은 `ExternalData.wrap` 으로 감싼다
- 승인한 동작의 글: `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorActionResultSource.java` 의 private static `input(ConnectorActionResult)`. 첫 줄 `승인한 동작의 결과가 도착했다.` 뒤에 `[동작: <제목>, 상태: <상태>]` 와 `ExternalData.wrap(resultText)` 이고, `UNKNOWN` 이면 본문 대신 `UNKNOWN_INPUT` 이다
- `ConnectorActionResult` 는 `connector/application/model/ConnectorActionResult.java` 의 `record(UUID actionId, String title, String tool, ActionStatus status, String errorCode, String resultText)` 이고 `ConnectorActionService.resultOf(ConnectorAction, Map)` 한 곳에서만 만든다. `undelivered`, `undeliveredClosures`, `resultsFor` 가 그것을 쓴다. 승인 줄의 실행 시각은 `ConnectorAction.executedAt()` 이다
- 위임 실행의 끝난 시각은 `AgentExecution.finishedAt()` 이다
- 결과 turn 의 지시: `chat/application/TurnIntent.java` 의 `DELEGATION_RESULTS_INSTRUCTION`(자동 turn)과 `DELIVERY_RETRY_INSTRUCTION`(다시 전달)이 둘 다 `RESULT_HANDLING_RULES` 로 끝난다. `TurnIntent.DelegationResults` 는 `record DelegationResults(Long attemptId, boolean retry)` 다
- 문맥 설정: `context/ContextProperties.java` 의 `record ContextProperties(long maxChars, int indexBudgetRatio)`, 접두사 `assistant.context`. `backend/src/main/resources/application.yml` 과 `backend/src/test/resources/application-test.yml` 에 `context:` 가 있다
- 시계는 주입받은 `Clock` 을 쓴다. `ChatService` 는 이미 `clock` 을 받는다
- `AutoTurnResult(String key, String notice, String input)` 의 3인자 생성자를 쓰는 테스트: `backend/src/test/java/com/bifos/assistant/chat/ResultDeliveryRecordTest.java`, `backend/src/test/java/com/bifos/assistant/chat/ResultDeliveryRetryTest.java`
- 지금 글을 단언하는 곳: `backend/src/test/java/com/bifos/assistant/chat/DelegationWakeServiceTest.java`(`[에이전트: 조사원, 실행 번호: …` 여러 곳), `backend/src/test/java/com/bifos/assistant/chat/ConnectorActionDeliveryTest.java`(`[동작: 이름 없는 동작, …` 와 위임 머리줄), `test/e2e/scenarios/connector-policy.ts`(`[동작: 메모 쓰기, 상태: SUCCEEDED]`)

**근거 문서**: `docs/backend/context-bundle.md` 의 「Hermes 에 넘기는 형식」, 「신선도」, 「우선순위」, `docs/adr/ADR-071-여러-출처의-문맥은-항목마다-출처와-권한과-신선도를-지닌-묶음으로-조립한다.md`

## 의도 메모

- 도구의 원래 이름과 승인 줄의 공개 식별자는 지금처럼 싣지 않는다. 모델이 답에 옮겨 화면에 내부 값이 나오지 않게 하려는 것이다
- 시각은 머리줄에 `Asia/Seoul` 의 `yyyy-MM-dd HH:mm` 으로 적는다. 모델이 사용자에게 말할 시각이라 가족이 사는 곳의 시각이다
- 승인한 동작만 오는 자동 turn 에서도 첫 줄 `승인한 동작의 결과가 도착했다.` 를 남긴다. 위임 결과와 함께 올 때도 그 단락의 첫 줄로 남는다
- 결과는 보통 몇 초 안에 전해져 `STALE` 이 되지 않는다. 신선도는 다시 전달(`retryDelivery`)이 몇 시간 뒤에 전할 때 쓰인다. 그래서 두 경로가 같은 함수로 머리줄과 항목을 만든다

## 작업 항목

### 1. `ContextProperties` 와 설정

record 에 `Duration resultStaleAfter` 를 더한다. 비어 있거나 0 이하면 6시간이다.
`application.yml` 의 `assistant.context` 에 `result-stale-after: 6h` 를 더한다. 테스트 설정은 기본값을 쓴다.

### 2. `backend/src/main/java/com/bifos/assistant/context/ResultHeader.java` (신규)

결과 항목 하나의 머리줄을 만드는 정적 함수를 둔다.

- `ContextFreshness freshnessOf(Instant asOf, Instant now, Duration staleAfter)`: `asOf` 가 비면 `UNKNOWN`, `now - asOf` 가 `staleAfter` 보다 크면 `STALE`, 아니면 `FRESH`
- `String render(String sourceLabel, List<String> fields, Instant asOf, ContextFreshness freshness, Duration staleAfter)`: `[출처: <sourceLabel>, <fields 를 ", " 로 이은 것>, 끝난 시각: <시각 또는 모름>]`. `STALE` 이면 `]` 앞에 `, 신선도: 오래됨` 을 넣고 다음 줄에 `이 결과는 <staleAfter 시간 수>시간보다 전에 끝났다. 지금 상태와 다를 수 있다.` 를 붙인다

### 3. `ChatService.delegationInput`

결과마다 `DELEGATION_RESULT` 항목을 만든다. `ref=execution:<번호>`, `scope=USER`, `ownerUserId` 는 대화 주인, `trust` 는 커넥터 에이전트의 답이면 `EXTERNAL` 아니면 `AGENT`, `sensitivity=SENSITIVE`, `asOf=finishedAt()`, `freshness=ResultHeader.freshnessOf(asOf, now, staleAfter)`, `bodyMode=INLINE`, `title=null`, `body=null`(본문은 `input` 에만 싣고 항목에 두지 않는다), `conflictsWith` 는 빈 목록.
머리줄은 `ResultHeader.render("맡긴 일", ["에이전트: <이름>", "실행 번호: <번호>", "상태: <상태>"(+ "오류: <코드>")], …)` 로 만든다. 본문과 감싸기는 지금과 같다.
`delegationInput` 과 `deliveryInput` 이 `static` 이면 `clock.instant()` 와 설정값을 인자로 받게 바꾼다.
`deliveryInput` 은 글과 항목을 함께 낸다(예: `chat/application/model/DeliveryInput.java` 의 `record DeliveryInput(String input, List<ContextItem> items)`). 항목 순서는 글에 실은 순서(위임 결과, 그 밖의 결과)다. `runDelegationResults` 와 `retryInput` 이 모두 이것을 쓴다.

### 4. `ConnectorActionResult`, `ConnectorActionService`, `ConnectorActionResultSource`

- `ConnectorActionResult` 에 `Instant executedAt` 을 더하고 `ConnectorActionService.resultOf` 가 `action.executedAt()` 을 넣는다
- `ConnectorActionResultSource` 가 `Clock` 과 `ContextProperties` 를 받는다. `input` 은 첫 줄 `승인한 동작의 결과가 도착했다.` 뒤에 `ResultHeader.render("승인한 동작", ["동작: <제목>", "상태: <상태>"(+ "오류: <코드>")], executedAt, …)` 을 쓴다. `UNKNOWN` 이면 지금처럼 본문 대신 `UNKNOWN_INPUT` 이다

### 5. 항목을 turn 까지 나르기

- `chat/application/model/AutoTurnResult.java` 의 record 에 네 번째 칸 `ContextItem item` 을 더한다. 3인자 생성자는 `item=null` 로 남기고, `item` 이 `null` 인 결과는 참조를 남기지 않는다. `ConnectorActionResultSource.undelivered` 와 `resultsFor` 가 모두 `CONNECTOR_RESULT` 항목(`ref=connector_action:<공개 식별자>`, `scope=USER`, `ownerUserId` 는 승인 줄의 주인, `trust=EXTERNAL`, `sensitivity=SENSITIVE`, `asOf=executedAt`, `freshness=ResultHeader.freshnessOf(...)`, `bodyMode` 는 `UNKNOWN` 이면 `OMITTED` 아니면 `INLINE`, `title=null`, `body=null`)을 채운다
- `TurnIntent.DelegationResults` 에 세 번째 칸 `List<ContextItem> items` 를 더한다. `ChatService.runDelegationResults` 와 `ChatService.retryDelivery` 가 `deliveryInput` 이 낸 항목(위임 결과 항목, 그 밖의 결과 항목 순서)을 넣는다
- `ChatService.runTurn` 이 스냅숏에 넣는 참조 목록에, turn 이 `TurnIntent.DelegationResults` 면 Memory 항목 뒤로 그 `items()` 를 `ContextSourceRefs` 로 옮겨 잇는다. 커넥터 에이전트(`AssembledContext.empty()`)의 자동 turn 은 결과 항목만 남는다

### 6. `TurnIntent.RESULT_HANDLING_RULES`

`docs/backend/context-bundle.md` 「Hermes 에 넘기는 형식」 의 세 문장을 `RESULT_HANDLING_RULES` 끝에 붙인다. 그래서 자동 turn(`DELEGATION_RESULTS_INSTRUCTION`)과 다시 전달(`DELIVERY_RETRY_INSTRUCTION`)이 모두 받는다.
`docs/backend/context-bundle.md` 의 「자동 turn 의 지시(`TurnIntent.DELEGATION_RESULTS_INSTRUCTION`)에 아래 글을 더한다.」 문장을 「결과를 전하는 두 지시가 함께 끝에 붙이는 `TurnIntent.RESULT_HANDLING_RULES` 에 아래 글을 더한다. 자동 turn 과 다시 전달이 모두 받는다.」 로 고친다. 이 문서의 결과 머리줄 설명도 다시 전달하는 turn 이 같은 형식을 쓴다는 것을 한 줄 적는다.

### 7. 문서의 「아직 구현 전」 표시를 지운다

- `docs/adr/ADR-071-여러-출처의-문맥은-항목마다-출처와-권한과-신선도를-지닌-묶음으로-조립한다.md` 의 `status` 를 `` `accepted` `` 로
- `docs/adr/INDEX.md` 의 ADR-071 줄 상태를 `Accepted` 로
- `docs/backend/context-bundle.md` 의 「**아직 구현 전이다.** …」 단락을 지운다
- `docs/README.md` 의 `backend/context-bundle.md` 줄 끝 「아직 구현 전이다」 를 지운다
- `docs/code-architecture.md` 「아직 만들지 않은 것」 의 문맥 묶음 줄을 지운다
- `docs/backend/packages.md` 의 `context` 줄을 「실행에 넣을 `instructions` 조립과 문맥 묶음의 항목 모델」 로 고친다

### 8. 이 phase 를 검증하는 테스트

`DelegationWakeServiceTest` 와 `ConnectorActionDeliveryTest` 의 머리줄 단언을 새 형식으로 고친다. 두 테스트는 고정 `Clock` 을 쓰지 않으므로 `finishedAt` 과 `executedAt` 을 직접 넣고 기대 시각은 그 값을 `Asia/Seoul` 의 `yyyy-MM-dd HH:mm` 으로 바꿔 계산한다.
`ResultDeliveryRecordTest` 와 `ResultDeliveryRetryTest` 의 `AutoTurnResult` 3인자 생성자는 남긴 생성자로 그대로 컴파일되는지 본다.
신선도와 머리줄의 경계(`STALE`, `UNKNOWN`, 6시간 경계)는 `backend/src/test/java/com/bifos/assistant/context/ResultHeaderTest.java`(신규, 단위 테스트)가 고정 시각으로 본다.
새로 더할 경우:

| 입력 | 기대 |
| --- | --- |
| 10분 전에 끝난 위임 결과 | `[출처: 맡긴 일, 에이전트: 조사원, 실행 번호: <번호>, 상태: SUCCEEDED, 끝난 시각: <시각>]` 이고 「오래됨」 이 없다 |
| 7시간 전에 실행한 승인 결과 | 머리줄에 `, 신선도: 오래됨` 과 안내 줄이 붙는다 |
| `executedAt` 이 빈 승인 결과 | `끝난 시각: 모름` |
| 자동 turn 의 `instructions` | 더한 세 문장을 담는다 |
| 위임 결과 하나와 승인 결과 하나가 함께 온 자동 turn(`ConnectorActionDeliveryTest`) | 그 자동 turn 의 실행에 `execution_context_source` 줄이 Memory 항목 뒤로 `DELEGATION_RESULT execution:<번호>`, `CONNECTOR_RESULT connector_action:<공개 식별자>` 순서로 남는다 |
| 7시간 전에 끝난 위임 결과를 다시 전달한다(`ResultDeliveryRetryTest`) | 머리줄에 `신선도: 오래됨` 과 안내 줄이 붙고, `instructions` 에 더한 세 문장이 있고, 그 turn 의 실행에 `DELEGATION_RESULT execution:<번호>` 참조가 `freshness=STALE` 로 남는다 |

`test/e2e/scenarios/connector-policy.ts` 의 `[동작: 메모 쓰기, 상태: SUCCEEDED]` 단언을 `[출처: 승인한 동작, 동작: 메모 쓰기, 상태: SUCCEEDED` 로 시작하는지로 고친다.

## 검증

```bash
# cwd: backend/
./gradlew test --tests 'com.bifos.assistant.chat.DelegationWakeServiceTest' --tests 'com.bifos.assistant.chat.ConnectorActionDeliveryTest' --tests 'com.bifos.assistant.chat.ResultDeliveryRetryTest' --tests 'com.bifos.assistant.chat.ResultDeliveryRecordTest' --tests 'com.bifos.assistant.context.ResultHeaderTest' --tests 'com.bifos.assistant.architecture.*'
./gradlew test
./gradlew checkstyleMain checkstyleTest
```

```bash
# cwd: 저장소 root
node test/e2e/run.ts
```

기대값: 모두 종료 코드 0.
`grep -rn '아직 구현 전' docs/backend/context-bundle.md docs/adr/ADR-071-*.md` 의 출력이 비어 있다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/context/ContextProperties.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/context/ResultHeader.java` | 신규 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/TurnIntent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/model/AutoTurnResult.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/model/DeliveryInput.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/model/ConnectorActionResult.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorActionService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorActionResultSource.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/DelegationWakeServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ConnectorActionDeliveryTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ResultDeliveryRetryTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/ResultHeaderTest.java` | 신규 |
| `test/e2e/scenarios/connector-policy.ts` | 수정 |
| `docs/adr/ADR-071-여러-출처의-문맥은-항목마다-출처와-권한과-신선도를-지닌-묶음으로-조립한다.md` | 수정 |
| `docs/adr/INDEX.md` | 수정 |
| `docs/backend/context-bundle.md` | 수정 |
| `docs/README.md` | 수정 |
| `docs/code-architecture.md` | 수정 |
| `docs/backend/packages.md` | 수정 |
