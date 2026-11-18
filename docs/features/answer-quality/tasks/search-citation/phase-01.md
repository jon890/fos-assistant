# Phase 01. 대화 실행의 지침에 「최신 정보와 출처」 절과 날짜 줄을 넣는다

**Execution profile**: standard

## 목표

Control Plane 이 실행마다 넣는 지침에 검색·출처 절과 오늘 날짜를 더한다. 운영에서 질문 101개 중 검색을 부른 것이 2개였고 지침에 검색과 출처, 날짜가 없었다.

**범위 외**: 평가 스크립트(phase 02), profile 틀과 문서(phase 03), 화면, 검색 서비스 값, 「깊이 조사」.

## 컨텍스트

- 공통 답변 지침은 `backend/src/main/java/com/bifos/assistant/context/application/ContextAssembler.java` 의 `withResponseInstructions(AssembledContext, boolean remembers)` 가 조립한다. 호출처는 셋이다.
  - `chat/turn/application/ChatTurnRunner.java`: `withResponseInstructions(context, !proactiveCheck)`
  - `orchestration/application/AgentRunner.java`: `withResponseInstructions(context, rememberAllowed)`. `rememberAllowed` 는 살펴보기 트리가 아닐 때 참이다(`proactiveChecks.existsByRootExecutionId(rootExecutionId)`)
  - `proactive/check/application/ProactiveCheckInput.java` 의 `memorySignal`: 인자 하나짜리 `withResponseInstructions(context)` 로 지문을 다시 계산해 지난 살펴보기의 `instructionsHash` 와 비교한다
- turn 전용 지시는 지문(`ExecutionContextSnapshot` 의 `instructionsHash`)을 뜬 뒤에 붙는다. 대화 turn 은 `chat/turn/application/ChatTurnLifecycle.java` 의 `TurnIntent.appendTo(AskFormat.appendTo(context.instructions()), intent)`, 자식 실행은 `AgentRunner` 의 `appendInstruction(context.instructions(), instructionAddition)` 이다
- turn 의 종류는 `chat/turn/application/dto/TurnIntent.java` 의 `Fresh`, `Regenerate`, `Mention`, `DelegationResults`(`fromUnattended` 값을 갖는다), `Scheduled`, `ProactiveCheck` 다
- 사람이 없는 트리(예약 작업, 먼저 살펴보기)는 `usage/execution/infra/AgentExecutionRepository.countUnattendedIn(ids)` 로 센다. `orchestration/application/SessionOwnerResolver.unattendedTree` 가 같은 조회를 쓴다
- 한국 시간은 `ZoneId.of("Asia/Seoul")` 로 쓴다(`context/application/ResultHeader.java`). 시각은 주입받은 `Clock` 으로 읽는다(rule-19)
- Gradle 테스트는 같은 머신의 다른 worker 와 자리를 나눠 쓴다. 지시서 공통 규칙의 무거운 검사 대기열(`HEAVY_SLOTS=2 orch slot heavy -- ...`)로 감싸 돌린다

**요구사항**: REQ1, REQ2, REQ3, REQ4

**근거 문서**: `docs/features/answer-quality/spec.md`, `docs/features/answer-quality/design.md`(「실행 종류」, 「지침 절」, DECISION1~DECISION3, DECISION7)

## 의도 메모

- 에이전트에 `web` 이 켜졌는지로 절을 나눠 넣지 않는다. 켜진 toolset 은 Hermes 만 알고 turn 마다 읽으면 답이 늦어진다(스펙 DECISION14). 그래서 글이 「`web_search` 도구가 있으면」 과 「없으면」 을 함께 말한다
- 날짜는 공통 지침에 넣지 않는다. 넣으면 `instructions_hash` 가 날마다 바뀐다(설계 DECISION2)
- 절의 뜻은 설계 「지침 절」 의 일곱 항목이다. 문구는 다듬어도 되고 뜻을 빼지 않는다
- `ProactiveCheckInput` 에 살펴보기 종류를 넘기지 않으면 지문이 달라져 「문맥이 바뀜」 신호가 늘 켜진다

## 작업 항목

### 1. `ContextAssembler` 가 실행 종류를 받는다

- `context/application/dto/` 에 실행 종류 enum(`InstructionScope` 같은 이름, 값은 대화·예약·살펴보기 셋)을 둔다
- `withResponseInstructions(AssembledContext, boolean)` 를 종류를 받는 메서드로 바꾼다. 대화는 기억 지침과 새 절, 예약은 기억 지침만, 살펴보기는 둘 다 넣지 않는다. 예약과 살펴보기의 결과 글은 지금과 글자 하나 다르지 않아야 한다
- 인자 하나짜리 `withResponseInstructions(context)` 는 지우고 `ProactiveCheckInput` 이 살펴보기 종류를 넘기게 한다
- 새 절은 `ContextAssembler` 의 상수로 두고 `TOOL_CALL_INSTRUCTIONS` 뒤, 기억 지침 앞이나 뒤 중 한 곳에 고정한다. 담을 뜻:
  - `web_search` 도구가 있으면 가격, 재고, 영업시간, 일정, 뉴스, 시세, 제품 사양과 출시, 제도와 법처럼 시간이 지나면 바뀌는 사실은 기억으로 답하지 않고 검색해 확인한다
  - 일반 상식, 글쓰기, 사용자의 개인 사정만으로 답할 수 있는 질문은 검색하지 않는다
  - 비교나 계획처럼 한 번 검색으로 모자란 질문은 여러 번 찾고 서로 맞춰 본다
  - 검색으로 확인한 문장 옆에 마크다운 링크를 달고 답 끝에 「출처」 목록(제목과 링크)을 둔다. 목록에는 `web_search` 와 `web_extract` 가 돌려준 주소만 쓰고 지어내지 않는다. 검색하지 않았으면 목록을 두지 않는다
  - 검색이 실패했거나 결과가 없거나 `web_search` 도구가 없는데 바뀌는 사실을 물으면 답 첫머리에 검색으로 확인하지 못했다는 것과 아는 정보의 기준 시점을 밝히고 아는 만큼 답한다
  - 플러그인이나 브라우저 같은 다른 도구로 이번 실행에서 확인한 사실에는 위 말을 붙이지 않는다
  - 조사를 하위 작업에 맡기면 찾은 주소를 함께 돌려받아 출처로 쓴다

### 2. 호출처가 종류를 정한다

- `ChatTurnRunner`: `ProactiveCheck` 는 살펴보기, `Scheduled` 와 `fromUnattended` 가 참인 `DelegationResults` 는 예약, 나머지는 대화
- `AgentRunner`: 살펴보기 트리는 살펴보기, 루트 실행이 사람이 없는 실행이면 예약, 나머지는 대화. 예약 트리 판정은 `AgentExecutionRepository.countUnattendedIn(List.of(rootExecutionId)) > 0` 이다. `AgentRunner` 에 그 저장소를 주입한다. `rootExecutionId` 가 없거나 루트 줄을 찾지 못하면 대화로 본다(`SessionOwnerResolver.unattendedTree` 는 못 찾으면 참이지만 여기서는 지금 동작을 지킨다)
- `AgentRunner` 의 생성자를 직접 부르는 테스트(`AgentRunnerConnectorContextTest`, `AgentRunnerSubmitFailureTest`)의 생성자 인자와 `withResponseInstructions` stub·verify 를 새 시그니처에 맞춘다
- 종류를 정하는 코드는 호출처마다 작은 메서드 하나로 두고 `ContextAssembler` 는 종류만 받는다

### 3. 날짜 줄

- `context/application` 에 날짜 줄을 만드는 작은 클래스(`TurnDate` 같은 이름)를 둔다. `Clock` 을 받아 `오늘은 2026년 11월 18일 수요일이다(한국 시간).` 꼴의 한 줄을 만든다
- 종류가 대화일 때만 `ChatTurnLifecycle` 과 `AgentRunner` 가 turn 전용 지시 끝에 이 줄을 붙인다. 붙는 차례는 `AskFormat`, turn 지시(`TurnIntent.appendTo`), 날짜 줄이다
- `ChatTurnLifecycle.begin` 은 이미 `TurnIntent intent` 를 받는다. `TurnIntent` 에 intent 의 실행 종류를 돌려주는 정적 메서드를 두고 `ChatTurnRunner` 와 `ChatTurnLifecycle` 이 함께 쓴다. `begin` 의 시그니처는 바꾸지 않는다
- `AgentRunner` 는 `instructionAddition` 이 null 이어도 종류가 대화면 날짜 줄을 붙인다(`ChildExecutionRunner` 는 늘 null 을 넘긴다)
- 지문과 글자 수(`ExecutionContextSnapshot`)를 뜨는 자리는 건드리지 않는다

### 4. 테스트

- `backend/src/test/java/com/bifos/assistant/context/ContextAssemblerTest.java`: 종류마다 절과 기억 지침이 들고 빠지는지. 「바꾸기 전과 같다」 는 이렇게 본다: 대화 글에서 새 절과 구분자를 뺀 글이 예약 글과 같고, 예약 글에서 기억 지침과 구분자를 뺀 글이 살펴보기 글과 같다
- 날짜 줄 클래스의 단위 테스트: 고정한 `Clock` 으로 글과 요일, 자정 앞뒤의 한국 날짜
- `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java`(가짜 Hermes 가 받은 `instructions` 를 보는 대화와 예약 turn 테스트가 있다)에 더한다: 대화 turn 의 `instructions` 에 절과 날짜 줄이 들고, 날짜만 다른 두 시각에 돈 실행의 `instructionsHash` 가 같다. 예약 turn 의 `instructions` 에는 절과 날짜 줄이 없다. 지문 비교는 같은 질문 글과, 신선도 경계에 걸리지 않는 Memory(또는 Memory 없음)로 고정한다. Memory 신선도가 시각에 따라 달라지기 때문이다
- 먼저 살펴보기 turn 을 도는 기존 테스트(`backend/src/test/java/com/bifos/assistant/proactive/` 아래에서 Hermes 가 받은 `instructions` 를 보는 것)에 절과 날짜 줄이 없음을 더한다
- `AgentRunner` 를 거치는 기존 테스트(`backend/src/test/java/com/bifos/assistant/orchestration/` 아래)에 더한다: 대화 트리의 자식에는 절과 날짜 줄이 들고 살펴보기 트리와 예약 트리의 자식에는 없다
- 먼저 살펴보기의 `memorySignal` 을 보는 기존 테스트가 그대로 통과한다(같은 문맥이면 「같음」)

## 검증

```bash
cd backend && ./gradlew test --tests '*ContextAssembler*' --tests '*TurnDate*' --tests '*ChatServiceTest' --tests 'com.bifos.assistant.orchestration.*' --tests 'com.bifos.assistant.proactive.*' --tests 'com.bifos.assistant.chat.turn.*' --tests '*FinancialOrderOpeningTest'
cd backend && ./gradlew archTest checkstyleMain checkstyleTest spotlessCheck
```

기대값: 모두 통과. 날짜 줄 클래스 이름을 다르게 지었으면 `*TurnDate*` 를 그 이름으로 바꾼다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/context/application/ContextAssembler.java` | 수정 (절 상수, 종류를 받는 조립) |
| `backend/src/main/java/com/bifos/assistant/context/application/dto/InstructionScope.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/context/application/TurnDate.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/application/ChatTurnRunner.java` | 수정 (종류 정하기) |
| `backend/src/main/java/com/bifos/assistant/chat/turn/application/ChatTurnLifecycle.java` | 수정 (날짜 줄 붙이기) |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentRunner.java` | 수정 (종류 정하기, 날짜 줄 붙이기) |
| `backend/src/main/java/com/bifos/assistant/proactive/check/application/ProactiveCheckInput.java` | 수정 (살펴보기 종류 넘기기) |
| `backend/src/test/java/com/bifos/assistant/context/ContextAssemblerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/application/TurnDateTest.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/application/dto/TurnIntent.java` | 수정 (intent 의 실행 종류) |
| `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` | 수정 (절, 날짜 줄, 지문) |
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryJudgeEvalTest.java` | 수정 (바뀐 메서드 호출) |
| `backend/src/test/java/com/bifos/assistant/connector/approval/application/FinancialOrderOpeningTest.java` | 수정 (바뀐 메서드 호출) |
| `backend/src/test/java/com/bifos/assistant/orchestration/AgentRunnerConnectorContextTest.java` | 수정 (바뀐 메서드 호출, 자식의 절과 날짜 줄) |
| `backend/src/test/java/com/bifos/assistant/orchestration/AgentRunnerSubmitFailureTest.java` | 수정 (바뀐 메서드 호출) |
