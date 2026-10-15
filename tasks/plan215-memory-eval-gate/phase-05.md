# Phase 05. 월 단위 답 판정 하네스의 틀

**Execution profile**: deep

## 목표

실린 기억이 답에 반영됐는지를 모델로 재는 하네스를 만든다. 사례마다 기억을 실은 답과 기억 없이 한 답을 받고, 판정 모델에 사례의 「예, 아니오」 질문을 물어 기대 답과 맞는지 센다.
CI 에서는 대역 모델로만 테스트하고, 실제 호출은 `./gradlew memoryJudgeEval` 을 설정과 함께 부를 때만 한다. 설정이 없으면 돌지 않는다.

**범위 외**: 실제 판정 모델과 월 예산을 고르는 것(사용자가 정한다). 판정기와 사람의 일치도 측정. 실제 provider 호출. 판정 결과를 게이트로 거는 것.

## 컨텍스트

- 사례 묶음과 `judge` 필드는 phase 01 이 만들었다. `MemoryEvalDataset.Case.judge()` 가 `List<JudgeCheck>` 이고 `JudgeCheck(String id, String question, boolean expect)` 다.
- 사례를 테이블에 넣고 에이전트를 만드는 코드는 지금 `backend/src/test/java/com/bifos/assistant/context/eval/MemoryRecallEvalTest.java` 안의 `seed`, `fillerOf`, `memoryOf`, `createAgents`, `userOf`, `groupIdOf`, `firstUserIdOfGroup`, `tearDown` 이다. 두 테스트가 함께 쓰도록 옮긴다.
- `MemoryEvalDataset` 과 그 안의 record 는 package-private 이다. 새 클래스도 같은 패키지 `com.bifos.assistant.context.eval` 에 둔다.
- 운영 기본값 조립은 주입받은 `ContextProperties` 로 만든 `ContextAssembler` 다. 공통 답변 지침을 더하는 것은 `ContextAssembler.withResponseInstructions(AssembledContext)` 다. 기억 없는 답은 `withResponseInstructions(AssembledContext.empty())` 의 글을 쓴다.
- HTTP 호출은 `RestClient.builder()` 로 만들고 timeout 을 준다(`backend/AGENTS.md` 의 「기술 주의점」). JSON 은 Jackson 3(`tools.jackson`)이다.
- JDK `HttpServer` 로 HTTP 대역을 띄우는 본보기는 `backend/src/test/java/com/bifos/assistant/agent/AgentEndpointProbeTest.java` 다.
- 태그로 테스트를 고르는 Gradle 태스크의 본보기는 `backend/build.gradle.kts` 의 `archTest` 와 `mysqlMigrationTest` 다. `tasks.test` 는 지금 `excludeTags("mysql")` 이다.

**근거 문서**: `backend/docs/adr/ADR-20261015-memory-eval-gate.md`, `docs/features/memory.md` 의 「답 판정」

## 의도 메모

- Hermes 를 거치지 않고 OpenAI 호환 chat API 를 직접 부른다. Hermes 의 시스템 지시는 재현하지 않는다. 기억이 있을 때와 없을 때의 차이를 보는 것이 목적이다.
- 판정은 자유 서술 점수가 아니라 첫 줄의 `YES` 나 `NO` 다. 그 밖의 답은 판정 불가로 세고 다시 묻지 않는다. 다시 물으면 예산이 흔들린다.
- 예산은 호출 수 상한이다. 사례 하나에 드는 호출(답 둘과 판정 2 × 질문 수)이 남은 예산보다 많으면 그 사례부터 멈추고 보고서에 예산 소진을 적는다. 사례를 반쯤 재지 않는다.
- key 는 환경 변수에서만 읽고 보고서와 로그에 내지 않는다.
- 판정 지침 글에 버전 상수를 둔다. 지침을 바꾸면 버전을 올려 이전 결과와 견주지 않게 한다.

## 작업 항목

### 1. 사례 준비 코드를 옮긴다

새 `backend/src/test/java/com/bifos/assistant/context/eval/MemoryEvalFixture.java` 로 위 메서드들을 옮긴다. 생성자로 `MemoryRepository`, `AgentRepository`, `AgentMemoryCollectionRepository`, `JdbcTemplate`, `TestClock` 을 받는다. `MemoryRecallEvalTest` 는 이 객체를 만들어 쓰고 동작은 바꾸지 않는다.

### 2. 모델 호출

- 새 `ChatModel.java`: `String complete(String model, String system, String user)`.
- 새 `OpenAiCompatibleChatModel.java`: `ChatModel` 구현. `POST {baseUrl}/chat/completions` 에 `model`, `messages`(`system`, `user` 두 줄), `max_tokens`, `temperature: 0` 을 보내고 `Authorization: Bearer <key>` 머리말을 단다. `choices[0].message.content` 를 돌려준다. 연결 5초, 읽기 60초 timeout 이다. 2xx 가 아니거나 모양이 다르면 상태 코드만 담은 `IllegalStateException` 을 던진다. 응답 본문과 key 는 예외 메시지에 넣지 않는다.
- 새 `MemoryJudgeSettings.java`: `record MemoryJudgeSettings(String baseUrl, String apiKey, String answerModel, String judgeModel, int maxCalls, int maxOutputTokens)`. `static Optional<MemoryJudgeSettings> fromEnv(Map<String, String> env)` 는 `MEMORY_JUDGE_BASE_URL` 이 없거나 비면 빈 값이다. 있으면 `MEMORY_JUDGE_API_KEY`, `MEMORY_JUDGE_ANSWER_MODEL`, `MEMORY_JUDGE_JUDGE_MODEL`, `MEMORY_JUDGE_MAX_CALLS` 가 모두 있어야 하고 없으면 그 이름을 담아 `IllegalStateException` 이다. `MEMORY_JUDGE_MAX_OUTPUT_TOKENS` 는 없으면 512 다. `toString` 은 key 를 내지 않는다.

### 3. 하네스와 보고서

- 새 `MemoryJudgeHarness.java`: `MemoryJudgeReport run(List<JudgeInput> inputs, ChatModel model, MemoryJudgeSettings settings, int datasetVersion)`. `JudgeInput(String caseId, String question, String withMemory, String withoutMemory, List<MemoryEvalDataset.JudgeCheck> checks)` 를 같은 파일의 중첩 record 로 둔다.
  - 판정이 없는 사례는 건너뛴다
  - 사례마다 답 모델로 `withMemory` 와 `withoutMemory` 를 system 으로 한 답 둘을 받는다
  - 질문마다 판정 모델에 두 답을 각각 묻는다. system 은 상수 `JUDGE_INSTRUCTIONS`(판정 지침 글, 「첫 줄에 YES 나 NO 만 쓴다」 포함)이고 user 는 사용자 질문, 답, 판정 질문을 머리 줄로 나눠 담는다. 상수 `JUDGE_PROMPT_VERSION = "judge-v1"` 을 둔다
  - 첫 줄을 앞뒤 공백을 빼고 대문자로 바꿔 `YES`, `NO` 면 그 값이고 그 밖은 판정 불가다
- 새 `MemoryJudgeReport.java`: 사례와 질문과 모드(`withMemory`, `withoutMemory`)마다 판정(`YES`, `NO`, `INVALID`)과 기대 답 일치 여부, 두 답의 글을 담는다. 요약은 질문 수, 모드별 일치 수, 일치율 차이, 판정 불가 수, 쓴 호출 수, 예산 소진 여부, 답 모델과 판정 모델 이름, 사례 묶음 버전, 판정 지침 버전이다. `markdown()` 과 `json()` 을 내고, `markdown()` 의 첫 줄은 「답 판정: 합성 입력만 썼다. 판정 모델의 판단이고 사람이 확인한 값이 아니다.」 다. key 와 주소는 담지 않는다.

### 4. 실제 실행용 테스트와 Gradle 태스크

- 새 `MemoryJudgeEvalTest.java`: `@Tag("model-eval")`, `@BackendIntegrationTest`. `MemoryJudgeSettings.fromEnv(System.getenv())` 가 비면 `Assumptions.assumeTrue` 로 건너뛴다. 판정이 있는 사례마다 `MemoryEvalFixture` 로 넣고 `factsOn` 조립에 공통 답변 지침을 더한 글과 기억 없는 글을 만들어 하네스를 `OpenAiCompatibleChatModel` 로 돌린다. 보고서를 `build/reports/memory-judge/report.md`, `report.json` 에 쓰고 표준 출력에 markdown 을 낸다. 품질 단언은 두지 않는다.
- `backend/build.gradle.kts`: `tasks.test` 의 `excludeTags` 에 `model-eval` 을 더한다. `tasks.register<Test>("memoryJudgeEval")` 을 더한다. `group = "verification"`, 설명은 「합성 사례로 기억이 답에 반영되는지 모델로 판정한다. 설정이 없으면 건너뛴다.」, `includeTags("model-eval")`, `maxHeapSize = "1g"`, `outputs.upToDateWhen { false }` 다.

### 5. 이 phase 를 검증하는 `MemoryJudgeHarnessTest.java`

Spring 없이 도는 테스트다. 태그를 달지 않아 `./gradlew test` 에서 돈다.

- 대역 `ChatModel` 이 받은 (model, system, user) 를 기록한다. 판정이 둘인 사례 하나에서 답 호출 2번, 판정 호출 4번이고, 답 호출의 system 이 `withMemory` 와 `withoutMemory` 다.
- 판정 대역이 `YES`, ` no `, `모르겠다` 를 내면 각각 `YES`, `NO`, `INVALID` 로 센다.
- `maxCalls` 가 사례 하나 반 만큼이면 두 번째 사례를 재지 않고 예산 소진이 참이며 쓴 호출 수가 상한을 넘지 않는다.
- `judge` 가 빈 사례는 호출하지 않는다.
- `MemoryJudgeSettings.fromEnv` 가 주소가 없으면 빈 값, 주소만 있으면 빠진 이름을 담은 예외, 다 있으면 값이고 `toString` 에 key 가 없다.
- `OpenAiCompatibleChatModel` 을 JDK `HttpServer` 대역에 붙여, 요청 경로가 `/chat/completions` 이고 `Authorization` 머리말과 본문의 `model`, `messages` 가 맞으며 `choices[0].message.content` 를 돌려주는지 본다. 대역이 500 을 내면 예외이고 메시지에 key 가 없다.

## 검증

```bash
cd backend && ./gradlew test --tests '*MemoryJudgeHarnessTest' --tests '*MemoryRecallEvalTest'
cd backend && ./gradlew memoryJudgeEval
cd backend && ./gradlew archTest checkstyleTest spotlessCheck
```

기대값: 첫 명령이 통과한다. 두 번째 명령은 `MEMORY_JUDGE_BASE_URL` 이 없는 환경에서 `MemoryJudgeEvalTest` 를 건너뛰고 성공으로 끝나며 어떤 HTTP 호출도 하지 않는다. 이 phase 에서 실제 provider 주소를 넣고 돌리지 않는다.
포맷이 어긋나면 기능 커밋 뒤에 `./gradlew spotlessApply` 결과를 따로 커밋한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryEvalFixture.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryRecallEvalTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/eval/ChatModel.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/context/eval/OpenAiCompatibleChatModel.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryJudgeSettings.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryJudgeHarness.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryJudgeReport.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryJudgeEvalTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryJudgeHarnessTest.java` | 신규 |
| `backend/build.gradle.kts` | 수정 |
