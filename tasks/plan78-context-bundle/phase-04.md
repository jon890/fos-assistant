# Phase 04. 결과 항목의 출처 머리줄과 신선도, 참조 저장

**Execution profile**: standard

## 목표

자동 turn 이 전하는 위임 결과와 승인한 동작의 결과를 문맥 묶음 항목(`DELEGATION_RESULT`, `CONNECTOR_RESULT`)으로 만들고, `input` 에 출처 머리줄과 끝난 시각과 신선도를 싣는다.
오래 지나 다시 전한 결과가 지금 상태처럼 읽히지 않게 한다. 결과 항목의 참조도 phase 03 이 만든 `execution_context_source` 에 남긴다. 이 phase 로 ADR-071 의 구현이 끝난다.

**범위 외**: 결과를 다시 전하는 경로와 전달 상태(#162), Memory 항목(phase 02), 참조 표와 화면(phase 03).

## Blocked 조건

- 이 phase 를 맡기는 지시문에 「#162 가 main 에 머지됐다」 와 그 PR 번호가 적혀 있지 않으면 `PHASE_BLOCKED: #162 머지 전이라 결과 전달 경로가 바뀔 수 있다` 를 출력하고 멈춘다
- 머지됐다고 적혀 있으면 `git fetch origin && git merge origin/main` 으로 받은 뒤, 아래 「컨텍스트」 의 메서드 이름이 그대로인지 먼저 본다. `ChatService.delegationInput` 이나 `ConnectorActionResultSource.input` 이 없거나 옮겨졌으면 옮겨진 자리에 같은 일을 하고, 바뀐 이름을 커밋 메시지에 적는다

## 컨텍스트

- 위임 결과의 글: `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` 의 private static `delegationInput(List<AgentExecution>, Map<Long, Agent>)`. 첫 줄 `맡긴 일의 결과가 도착했다.` 뒤로 결과마다 `\n\n[에이전트: <이름>, 실행 번호: <번호>, 상태: <상태>]`(`FAILED` 면 `, 오류: <코드>`)와 본문을 잇는다. 커넥터 에이전트의 답(`isExternalResult`)은 `ExternalData.wrap` 으로 감싼다
- 승인한 동작의 글: `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorActionResultSource.java` 의 private static `input(ConnectorActionResult)`. 첫 줄 `승인한 동작의 결과가 도착했다.` 뒤에 `[동작: <제목>, 상태: <상태>]` 와 `ExternalData.wrap(resultText)` 이고, `UNKNOWN` 이면 본문 대신 `UNKNOWN_INPUT` 이다
- `ConnectorActionResult` 는 `connector/application/model/ConnectorActionResult.java` 의 `record(UUID actionId, String title, String tool, ActionStatus status, String errorCode, String resultText)` 이고 `ConnectorActionService.undelivered` 한 곳에서만 만든다. 승인 줄의 실행 시각은 `ConnectorAction.executedAt()` 이다
- 위임 실행의 끝난 시각은 `AgentExecution.finishedAt()` 이다
- 자동 turn 의 지시: `chat/application/TurnIntent.java` 의 `DELEGATION_RESULTS_INSTRUCTION`
- 문맥 설정: `context/ContextProperties.java` 의 `record ContextProperties(long maxChars, int indexBudgetRatio)`, 접두사 `assistant.context`. `backend/src/main/resources/application.yml` 과 `backend/src/test/resources/application-test.yml` 에 `context:` 가 있다
- 시계는 주입받은 `Clock` 을 쓴다. `ChatService` 는 이미 `clock` 을 받는다
- 지금 글을 단언하는 곳: `backend/src/test/java/com/bifos/assistant/chat/DelegationWakeServiceTest.java`(`[에이전트: 조사원, 실행 번호: …` 여러 곳), `backend/src/test/java/com/bifos/assistant/chat/ConnectorActionDeliveryTest.java`(`[동작: 이름 없는 동작, …` 와 위임 머리줄), `test/e2e/scenarios/connector-policy.ts`(`[동작: 메모 쓰기, 상태: SUCCEEDED]`)

**근거 문서**: `docs/backend/context-bundle.md` 의 「Hermes 에 넘기는 형식」, 「신선도」, 「우선순위」, `docs/adr/ADR-071-여러-출처의-문맥은-항목마다-출처와-권한과-신선도를-지닌-묶음으로-조립한다.md`

## 의도 메모

- 도구의 원래 이름과 승인 줄의 공개 식별자는 지금처럼 싣지 않는다. 모델이 답에 옮겨 화면에 내부 값이 나오지 않게 하려는 것이다
- 시각은 머리줄에 `Asia/Seoul` 의 `yyyy-MM-dd HH:mm` 으로 적는다. 모델이 사용자에게 말할 시각이라 가족이 사는 곳의 시각이다
- 승인한 동작만 오는 자동 turn 에서도 첫 줄 `승인한 동작의 결과가 도착했다.` 를 남긴다. 위임 결과와 함께 올 때도 그 단락의 첫 줄로 남는다
- 결과는 보통 몇 초 안에 전해져 `STALE` 이 되지 않는다. 신선도는 #162 의 다시 전하기가 몇 시간 뒤에 전할 때 쓰인다

## 작업 항목

### 1. `ContextProperties` 와 설정

record 에 `Duration resultStaleAfter` 를 더한다. 비어 있거나 0 이하면 6시간이다.
`application.yml` 의 `assistant.context` 에 `result-stale-after: 6h` 를 더한다. 테스트 설정은 기본값을 쓴다.

### 2. `backend/src/main/java/com/bifos/assistant/context/ResultHeader.java` (신규)

결과 항목 하나의 머리줄을 만드는 정적 함수를 둔다.

- `ContextFreshness freshnessOf(Instant asOf, Instant now, Duration staleAfter)`: `asOf` 가 비면 `UNKNOWN`, `now - asOf` 가 `staleAfter` 보다 크면 `STALE`, 아니면 `FRESH`
- `String render(String sourceLabel, List<String> fields, Instant asOf, ContextFreshness freshness, Duration staleAfter)`: `[출처: <sourceLabel>, <fields 를 ", " 로 이은 것>, 끝난 시각: <시각 또는 모름>]`. `STALE` 이면 `]` 앞에 `, 신선도: 오래됨` 을 넣고 다음 줄에 `이 결과는 <staleAfter 시간 수>시간보다 전에 끝났다. 지금 상태와 다를 수 있다.` 를 붙인다

### 3. `ChatService.delegationInput`

결과마다 `DELEGATION_RESULT` 항목을 만든다. `ref=execution:<번호>`, `trust` 는 커넥터 에이전트의 답이면 `EXTERNAL` 아니면 `AGENT`, `sensitivity=SENSITIVE`, `asOf=finishedAt()`, `bodyMode=INLINE`.
머리줄은 `ResultHeader.render("맡긴 일", ["에이전트: <이름>", "실행 번호: <번호>", "상태: <상태>"(+ "오류: <코드>")], …)` 로 만든다. 본문과 감싸기는 지금과 같다.
`delegationInput` 이 `static` 이면 `clock.instant()` 와 설정값을 인자로 받게 바꾼다.

### 4. `ConnectorActionResult`, `ConnectorActionService`, `ConnectorActionResultSource`

- `ConnectorActionResult` 에 `Instant executedAt` 을 더하고 `ConnectorActionService.undelivered` 가 `action.executedAt()` 을 넣는다
- `ConnectorActionResultSource` 가 `Clock` 과 `ContextProperties` 를 받는다. `input` 은 첫 줄 `승인한 동작의 결과가 도착했다.` 뒤에 `ResultHeader.render("승인한 동작", ["동작: <제목>", "상태: <상태>"(+ "오류: <코드>")], executedAt, …)` 을 쓴다. `UNKNOWN` 이면 지금처럼 본문 대신 `UNKNOWN_INPUT` 이다

### 5. 항목을 turn 까지 나르기

- `chat/application/model/AutoTurnResult.java` 의 record 에 네 번째 칸 `ContextItem item` 을 더한다. `ConnectorActionResultSource.undelivered` 가 `CONNECTOR_RESULT` 항목(`ref=connector_action:<공개 식별자>`, `trust=EXTERNAL`, `sensitivity=SENSITIVE`, `asOf=executedAt`, `UNKNOWN` 이면 `bodyMode=OMITTED`)을 채운다
- `TurnIntent.DelegationResults` 에 `List<ContextItem> items` 를 더한다. `ChatService.runDelegationResults` 가 위임 결과 항목과 다른 source 의 항목을 글에 실은 순서대로 모아 넣는다
- `ChatService.runTurn` 이 스냅숏에 넣는 참조 목록에, turn 이 `TurnIntent.DelegationResults` 면 Memory 항목 뒤로 그 `items()` 를 `ContextSourceRefs` 로 옮겨 잇는다. 커넥터 에이전트(`AssembledContext.empty()`)의 자동 turn 은 결과 항목만 남는다

### 6. `TurnIntent.DELEGATION_RESULTS_INSTRUCTION`

지금 글 뒤에 `docs/backend/context-bundle.md` 「Hermes 에 넘기는 형식」 의 세 문장을 그대로 붙인다.

### 7. 문서의 「아직 구현 전」 표시를 지운다

- `docs/adr/ADR-071-여러-출처의-문맥은-항목마다-출처와-권한과-신선도를-지닌-묶음으로-조립한다.md` 의 `status` 를 `` `accepted` `` 로
- `docs/adr/INDEX.md` 의 ADR-071 줄 상태를 `Accepted` 로
- `docs/backend/context-bundle.md` 의 「**아직 구현 전이다.** …」 단락을 지운다
- `docs/README.md` 의 `backend/context-bundle.md` 줄 끝 「아직 구현 전이다」 를 지운다
- `docs/code-architecture.md` 「아직 만들지 않은 것」 의 문맥 묶음 줄을 지운다
- `docs/backend/packages.md` 의 `context` 줄을 「실행에 넣을 `instructions` 조립과 문맥 묶음의 항목 모델」 로 고친다

### 8. 이 phase 를 검증하는 테스트

`DelegationWakeServiceTest` 와 `ConnectorActionDeliveryTest` 의 머리줄 단언을 새 형식으로 고친다. 고정 `Clock` 으로 끝난 시각을 정해 시각까지 단언한다.
새로 더할 경우:

| 입력 | 기대 |
| --- | --- |
| 10분 전에 끝난 위임 결과 | `[출처: 맡긴 일, 에이전트: 조사원, 실행 번호: <번호>, 상태: SUCCEEDED, 끝난 시각: <시각>]` 이고 「오래됨」 이 없다 |
| 7시간 전에 실행한 승인 결과 | 머리줄에 `, 신선도: 오래됨` 과 안내 줄이 붙는다 |
| `executedAt` 이 빈 승인 결과 | `끝난 시각: 모름` |
| 자동 turn 의 `instructions` | 더한 세 문장을 담는다 |
| 위임 결과 하나와 승인 결과 하나가 함께 온 자동 turn | `TurnIntent.DelegationResults.items()` 가 `DELEGATION_RESULT`, `CONNECTOR_RESULT` 순서이고 `ref` 가 각각 `execution:<번호>`, `connector_action:<공개 식별자>` 다. 그 자동 turn 의 실행에 `execution_context_source` 줄이 Memory 항목 뒤로 같은 순서로 남는다 |

`test/e2e/scenarios/connector-policy.ts` 의 `[동작: 메모 쓰기, 상태: SUCCEEDED]` 단언을 `[출처: 승인한 동작, 동작: 메모 쓰기, 상태: SUCCEEDED` 로 시작하는지로 고친다.

## 검증

```bash
# cwd: backend/
./gradlew test --tests 'com.bifos.assistant.chat.DelegationWakeServiceTest' --tests 'com.bifos.assistant.chat.ConnectorActionDeliveryTest' --tests 'com.bifos.assistant.architecture.*'
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
| `backend/src/main/java/com/bifos/assistant/connector/application/model/ConnectorActionResult.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorActionService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorActionResultSource.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/DelegationWakeServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ConnectorActionDeliveryTest.java` | 수정 |
| `test/e2e/scenarios/connector-policy.ts` | 수정 |
| `docs/adr/ADR-071-여러-출처의-문맥은-항목마다-출처와-권한과-신선도를-지닌-묶음으로-조립한다.md` | 수정 |
| `docs/adr/INDEX.md` | 수정 |
| `docs/backend/context-bundle.md` | 수정 |
| `docs/README.md` | 수정 |
| `docs/code-architecture.md` | 수정 |
| `docs/backend/packages.md` | 수정 |
