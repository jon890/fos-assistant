# Phase 01. 모든 실행의 공통 지시문에 서비스 개념 목록을 넣는다

**Execution profile**: standard

## 목표

대화 turn, 예약 작업 turn, 먼저 살펴보기 turn, 맡긴 실행의 Hermes 요청 `instructions` 에 서비스 개념 목록이 들어가 에이전트가 기능 이름을 알아듣는다.

**범위 외**: `connector_list` 도구(phase 02), 평가 세트와 화면 주소 저장소 테스트(phase 03). 실행 입력 앞 단락(`ArtifactService.agentPreamble`)과 파일 공간 단락은 바꾸지 않는다(스펙 DECISION20).

## 컨텍스트

**요구사항**: REQ1, REQ2, REQ3, REQ4

**근거 문서**: `docs/features/service-concepts/spec.md`, `docs/features/service-concepts/design.md` 의 「개념 목록 지침」, `docs/features/answer-quality.md` 의 「지침」

- 공통 지시문은 `backend/src/main/java/com/bifos/assistant/context/application/ContextAssembler.java` 의 `withResponseInstructions` 가 만든다. `RESPONSE_INSTRUCTIONS`, `TOOL_CALL_INSTRUCTIONS` 뒤에 범위별 절(`SEARCH_INSTRUCTIONS`, `MEMORY_INSTRUCTIONS`)과 기억 글(`context.instructions()`)이 붙는다
- 이 메서드를 부르는 곳은 `ChatTurnRunner`(대화·예약·살펴보기 turn), `AgentRunner`(맡긴 실행), `ProactiveCheckInput`(살펴보기 지문)이다
- 대화 turn 에는 `DelegationTargetsInstructions` 가 `[code] 이름` 꼴의 맡길 수 있는 에이전트 구역을 붙인다. REQ4 의 링크는 이 code 를 쓴다
- 「지금 볼 것」 은 용어 표의 지금 화면(`/now`)의 화면 이름이다

## 의도 메모

- 목록을 실행 입력 앞 단락에 두는 안은 기각했다. 맡긴 실행이 지나지 않는다(설계 DECISION1)
- 목록 글은 실행마다 같아야 한다. 사용자나 시각에 따라 바뀌는 값을 넣지 않는다. 프롬프트 캐시와 `instructions_hash` 를 지킨다
- 조건을 먼저 쓴다. 조건 없는 지시는 싼 모델이 요청과 상관없이 따른다(`ArtifactService.FILE_SPACE_RULES` 의 주석)
- PR2 부터 PR4 가 각 개념의 줄을 직접 처리의 말로 바꾼다. 줄마다 하나의 상수 조각으로 두면 고치기 쉽다

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/context/application/ServiceConceptsInstructions.java` 신규

`final class` 와 `static final String SECTION` 하나. 머리 줄 `# 이 서비스의 기능` 과 설계 「개념 목록 지침」 표의 일곱 줄, 마지막 「도구가 없으면 했다고 말하지 않는다」 줄. 주소는 서비스 안의 상대 주소만 쓴다(`/files`, `/tasks`, `/tasks/new`, `/memory`, `/now`, `/connections`, `/new?agent=<code>`). 「사용량」 과 `/usage` 를 쓰지 않는다. 클래스 Javadoc 에 스펙 경로와 「줄을 바꾸면 평가 세트를 돌린다」 를 적는다.

### 2. `ContextAssembler.withResponseInstructions` 수정

`RESPONSE_INSTRUCTIONS + SEPARATOR + TOOL_CALL_INSTRUCTIONS` 바로 뒤에 `SEPARATOR + ServiceConceptsInstructions.SECTION` 을 모든 범위에 붙인다. 메서드 Javadoc 을 고친다.

### 3. 단위 테스트 `backend/src/test/java/com/bifos/assistant/context/ServiceConceptsInstructionsTest.java` 신규

세 범위(`CONVERSATION`, `SCHEDULED`, `PROACTIVE`)마다 `withResponseInstructions` 결과에 일곱 개념 이름과 다섯 주소가 있고, 「사용량」 과 `/usage` 가 없으며, 목록이 기억 글보다 앞에 있는지 본다. 모델 단계 줄이 바꾸지 않는다는 말과 입력창 단추 안내를, 에이전트 고르기 줄이 `/new?agent=` 를 담는지 본다. `withResponseInstructions` 를 직접 검사하는 `context/ContextAssemblerTest.java` 의 준비를 따른다.

### 4. 기존 통합 테스트에 단언 더하기

가짜 Hermes(`StubHermesRunsClient`)의 `stub().received()` 로 요청을 읽는 기존 테스트에 「`instructions` 에 `# 이 서비스의 기능` 과 `/tasks/new` 가 있다」 를 더한다. 대화 turn 은 `chat/ChatServiceTest.java`(지시문 길이와 지문 계산 부근, `/usage` 가 없음도 본다), 예약 작업은 `task/TaskRunStarterTest.java`, 먼저 살펴보기는 `proactive/CareerDailyPilotTest.java`, 맡긴 실행은 `orchestration/ChildExecutionRunnerTest.java` 다. 파일 공간 단락이 그대로인지는 기존 `FileSpaceTurnTest` 가 본다.

### 5. 문서

`docs/features/answer-quality.md` 의 「지침」 의 범위 표 위나 아래에 「모든 범위에 서비스 개념 목록(`ServiceConceptsInstructions`)이 든다」 를 적고 원본으로 `docs/features/service-concepts/spec.md` 를 가리킨다. 기능 파일 `docs/features/service-concepts.md` 의 상태 줄과 `covers:` 에 `ServiceConceptsInstructions.java` 를 더한다.

## 검증

```bash
cd backend && ./gradlew test --tests '*ServiceConceptsInstructionsTest' --tests '*ContextAssemblerTest' --tests '*ChatServiceTest' --tests '*TaskRunStarterTest' --tests '*ChildExecutionRunnerTest' --tests '*CareerDailyPilotTest' --tests '*FileSpaceTurnTest' --tests '*ProactiveCheckTurnTest' --tests '*AgentRunnerConnectorContextTest' --tests '*MentionDelegationTest'
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck
node --test test/repo/doc-links.test.ts test/repo/doc-code-references.test.ts scripts/ci/feature-covers.test.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/context/application/ServiceConceptsInstructions.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/context/application/ContextAssembler.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/ServiceConceptsInstructionsTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/task/TaskRunStarterTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/proactive/CareerDailyPilotTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/ChildExecutionRunnerTest.java` | 수정 |
| `docs/features/answer-quality.md` | 수정 |
| `docs/features/service-concepts.md` | 수정 |
