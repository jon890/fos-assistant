# Phase 04. 살펴보기 turn 과 시작 경로

**Execution profile**: deep

## 목표

`POST /api/v1/agents/{code}/proactive-check/runs` 로 살펴보기를 시작하고, 점검 대화에 turn 하나를 돌려 검사한 결과를 대화에 남긴다.
분야 지침(`proactive-check` 스킬)을 읽으라는 입력과 Control Plane 지시를 싣고, 답 조각을 흘리지 않고, 결과 블록을 검사해 그린다.

**범위 외**: 시간과 도구 호출 상한으로 멈추기, 끝날 때 위임 결과 정리, 멈췄을 때의 대기 메시지(phase 05). 커넥터와 MCP 와 위임의 읽기 경계(phase 06).

## 컨텍스트

**근거 문서**: `docs/backend/proactive-check.md` 의 「한 번의 살펴보기」, 「실행에 싣는 것」, 「Control Plane 지시」, 「대화에 남는 것」, 「session 을 바꾸는 기준」, 「시작 응답」, `docs/backend/packages.md` 의 「proactive」, `docs/backend/execution-limit.md` 의 「세는 실행」 의 먼저 살펴보기 줄

- 사용자 질문 없이 turn 을 여는 본보기: `chat/application/DelegationWakeService.java` 의 `tryWake` 와 `runAutoTurn`(잠금을 잡고 가상 스레드에서 돌리고 `finally` 에서 `turns.close`), `chat/application/ChatService.java` 의 `runDelegationResults`
- `ChatService.runTurn`, `saveQuestion`, `begin`, `forward`, `finish`, `cancel`, `answerMessage` 가 이 phase 가 고칠 자리다. 모두 `ChatService.java` 안의 private 메서드다
- `TurnIntent`(`chat/application/TurnIntent.java`)는 sealed interface 다. 새 종류를 더하면 `instructionFor` 와 `saveQuestion` 의 분기를 함께 고친다
- 알림 줄 저장: `ChatMessage.fromSystem(conversationId, text, at)`, 사건 `ChatEvent.system(publicId, messageId, text)`. turn 밖의 알림 줄은 `ConversationNotices.post(conversationId, text)`
- 대화 단위 SSE: `ConversationEventHub.publish(conversationId, event)`
- session: `ConversationSessions.ensure`(`chat/application/ConversationSessions.java`), `RunSession.newSessionId()`, `Conversation.assignNewSession`. 조건부 update 는 `ConversationWriter` 에 둔다
- 외부 글 감싸기: `shared/util/ExternalData.wrap`
- 근거 문서의 「살펴보기가 읽는 맥락」 이 입력에 실을 변화 신호와 최근 발견을 정한다
- 결과 전달을 고치는 다른 작업(#167, `TurnIntent.DelegationResults` 의 모양과 `saveQuestion`, `finish`, `cancel` 의 전달 기록)이 이 phase 보다 먼저 main 에 들어오면, 이 phase 를 시작하기 전에 main 을 합치고 그 바뀐 모양 위에 살펴보기 분기를 더한다
- 시험 본보기: `chat/DelegationWakeServiceTest.java`(`@SpringBootTest`, `@Import(ChatServiceTest.StubRuntime.class)`), `hermes/StubHermesRunsClient.java` 의 `willAnswer`, `received`

## 의도 메모

- `chat` 은 `proactive` 를 import 하지 않는다. 살펴보기만의 일은 `chat` 의 port `CheckTurn` 으로 받는다.
- 답 조각을 흘리지 않는다. 결과 블록의 JSON 이 섞인 글이라 그대로 보이면 읽을 수 없다. 도구와 하위 에이전트 사건은 흘린다.
- 살펴보기 turn 은 Memory 제안과 추천 질문 갱신을 띄우지 않는다. `auto_turn_count` 를 바꾸지 않고 제목도 채우지 않는다.
- 멈춘 살펴보기는 그때까지의 답을 남기지 않는다. 검사하지 않은 글이 대화에 남지 않게 하기 위해서다.
- 시작 요청은 잠금을 잡은 뒤 곧바로 202 를 돌려준다. 자리와 잠금 거절은 202 전에 409 로 나간다.
- 살펴보기를 위해 Hermes 의 toolset 설정을 바꾸지 않는다(ADR-077 대안 기각).

## 작업 항목

### 1. `chat` 의 port 와 turn 종류

- `backend/src/main/java/com/bifos/assistant/chat/application/CheckTurn.java` 신규 인터페이스:
  - `String instructions()` — `instructions` 끝에 붙일 Control Plane 지시
  - `String input()` — Hermes 입력
  - `String startNotice()` — 시작 알림 줄의 글
  - `boolean renewSession()` — 이번에 새 session 으로 시작할지
  - `void started(Long executionId, String hermesRootSessionId)` — 실행 줄이 생긴 직후
  - `void toolStarted(Long executionId)` — 이 turn 의 `tool.started` 마다. 막히지 않게 곧바로 돌아온다
  - `CheckAnswer answer(Long executionId, String output)` — 성공한 답을 대화에 남길 글로 바꾼다
  - `String stoppedNotice()` — 멈췄을 때의 알림 줄 글
- `chat/application/model/CheckAnswer.java`: record `CheckAnswer(String text, boolean notice)`. `notice` 가 참이면 `SYSTEM` 알림 줄로, 거짓이면 실행 번호가 붙은 `ASSISTANT` 답으로 남긴다
- `TurnIntent.ProactiveCheck(CheckTurn check)` 를 더한다. `instructionFor` 는 `check.instructions()` 를 돌려준다

### 2. `ChatService.runProactiveCheck`

`public void runProactiveCheck(CurrentUser owner, Long conversationId, TurnHandle handle, CheckTurn check, Consumer<ChatEvent> onEvent)`

- 부르는 쪽이 그 대화의 잠금을 이미 잡았다. `runDelegationResults` 와 같은 모양이다
- `check.input()` 은 한 번만 불러 지역 변수에 담고 `route` 와 `runTurn` 에 같은 값을 넘긴다. 부를 때마다 시각과 DB 를 다시 읽기 때문이다. `ProactiveCheckRun` 도 처음 만든 값을 들고 있다가 다시 불리면 그 값을 돌려준다
- `route(owner, conversationId, input, null, List.of())` 로 정한다. 흐름이 붙었으면 `CONVERSATION_BUSY` 대신 `ApiException(ErrorCode.PROACTIVE_CHECK_UNAVAILABLE, ...)` 를 던진다
- `check.renewSession()` 이 참이면 `ConversationSessions.renew(conversation)` 를 먼저 부른다
- `runTurn(owner, routed, input, new TurnIntent.ProactiveCheck(check), onEvent, true, handle)` 을 부르고 `done` 이나 `stopped` 를 보낸다
- `runTurn` 과 그 아래에서 `ProactiveCheck` 일 때만 다르게 한다
  - `saveQuestion`: 한 트랜잭션에서 `check.startNotice()` 로 `SYSTEM` 줄 하나를 저장하고 `ChatEvent.system` 을 보낸다. 제목과 `auto_turn_count` 와 대기 행은 건드리지 않는다
  - 실행 줄을 만든 뒤(`begin` 과 `turns.rekey` 다음) `check.started(executionId, conversation.hermesRootSessionId())`
  - `forward`: `message.delta` 는 `pending.streamed()` 에만 쌓고 `onEvent` 로 보내지 않는다. `tool.started` 는 지금처럼 보내고 `check.toolStarted(executionId)` 를 부른다
  - `finish`: `check.answer(executionId, output)` 의 글을 저장한다. `notice` 면 `ChatMessage.fromSystem` 으로 저장하고 `ChatEvent.system` 을 보낸다. `memoryProposer.proposeFrom` 과 `starterSuggestions.refreshIfStale` 을 부르지 않는다
  - `cancel`: 쌓인 답을 저장하지 않고 `check.stoppedNotice()` 를 `SYSTEM` 줄로 저장하고 `ChatEvent.system` 을 보낸다. 돌려주는 `ChatTurn` 의 `messageId` 는 null 이다
- 실패는 예외로 올린다. 실패 알림 줄은 부르는 쪽(`ProactiveCheckService`)이 남긴다

### 3. `ConversationSessions.renew`

- `public RunSession renew(Conversation conversation)`: `RunSession.newSessionId()` 로 새 값을 정해 `ConversationWriter` 의 새 메서드 `replaceSessions(Long conversationId, String sessionId)` 로 두 session 칸을 함께 바꾸고 `conversation.assignNewSession` 을 부른다. `updatedAt` 은 바꾸지 않는다
- `ConversationWriter` 는 메서드마다 `ConversationRepository` 의 `@Modifying` 쿼리 하나를 감싼다. `ConversationRepository.replaceSessions(@Param("id") Long id, @Param("sessionId") String sessionId)` 를 `update Conversation c set c.hermesSessionId = :sessionId, c.hermesRootSessionId = :sessionId where c.id = :id` 로 더한다

### 4. `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveCheckRun.java`

`CheckTurn` 의 구현이다. 살펴보기 한 번마다 만든다(빈이 아니다).

- `static final String INSTRUCTIONS`: 문서 「Control Plane 지시」 의 규칙 전부와 결과 블록의 칸 이름, 허용 값, 상한을 한국어로 적는다. 칸 이름은 `docs/backend/proactive-check.md` 의 「결과 계약」 표와 같아야 한다
- `input()`: 차례로 아래 단락이다
  1. 「먼저 살펴보기를 시작한다. `skill_view(name="proactive-check")` 로 지침을 읽고 그 절차대로 살펴본다.」
  2. 지금 시각(ISO-8601, UTC)
  3. 변화 신호: 지난 살펴보기 시각(없으면 「처음」), 그 뒤 사용자가 점검 대화에 보낸 메시지 수, Memory 문맥이 지난 살펴보기와 같은지(같음, 바뀜, 모름)
  4. 최근에 알린 발견 목록을 `ExternalData.wrap` 으로 감싼 단락. 발견이 없으면 「최근에 알린 발견이 없다.」
- 지난 살펴보기는 `ProactiveCheckRepository.findFirstByConversationIdAndStatusNotOrderByIdDesc(conversationId, RUNNING)` 다. 메시지 수는 `ChatMessageRepository` 에 새로 더하는 `long countByConversationIdAndRoleAndCreatedAtAfter(Long conversationId, MessageRole role, Instant after)` 로 `USER` 를 센다. Memory 문맥 비교는 지난 살펴보기의 루트 실행 줄의 `instructions_hash` 와 이번 문맥의 해시를 견준다. 실행 줄의 값은 `ChatService.runTurn` 이 `ContextAssembler.assemble` 과 `withResponseInstructions` 로 조립한 `AssembledContext.instructionsHash()` 다. 그래서 이번 값도 입력을 만들기 전에 같은 두 메서드로 조립해 구한다. 어느 쪽이든 값이 없으면 「모름」 이다
- 최근에 알린 발견은 `ProactiveCheckFindingRepository.findByConversationIdAndKindAndCreatedAtAfterOrderByIdDesc(conversationId, NEW, now - digestWindow, PageRequest.ofSize(digestMaxItems))` 로 읽어 `- [area] topicKey · title · sourceUrl · 확인 yyyy-MM-dd · 그 뒤 사용자 메시지 N개` 로 적는다. 메시지 수는 그 발견을 낸 살펴보기가 끝난 뒤부터 지금까지의 `USER` 메시지 수다(`countByConversationIdAndRoleAndCreatedAtAfter(conversationId, USER, 그 살펴보기의 finishedAt)`). 같은 살펴보기의 발견은 한 번만 센다
- 되풀이 판정에 쓸 이미 알린 묶음은 `findByConversationIdAndKindAndCreatedAtAfter(conversationId, NEW, now - digestWindow)` 로 읽어 `AnnouncedKey` 집합으로 만든다
- `started`: `ProactiveCheck.attachRoot` 로 루트 번호와 루트 session 을 적어 저장한다
- `toolStarted`: `AtomicInteger` 로 센다. 엔티티는 끝날 때 그 값으로 한 번 적는다. 상한 판정은 phase 05 가 더한다
- `answer`: `CheckResultParser.parse` → 실패면 `INVALID_RESULT` 와 「살펴봤지만 결과를 정리하지 못했어요」 알림. `NOTHING_NEW` 이고 발견이 없으면 「살펴봤지만 새로 알릴 것이 없어요」 알림. 그 밖에는 `FindingJudgement.judge(finding, checkStartedAt, now, alreadyAnnounced)` 와 `CheckAnswerRenderer.render` 의 글을 답으로 돌려주고, 발견마다 `topicKey` 를 담아 `ProactiveCheckFinding` 을 저장한다. 결과와 셈은 이 객체에 들고 있다가 끝날 때 적는다
- `stoppedNotice`: 이 phase 에서는 「살펴보기를 멈췄어요」 만 돌려준다
- 알림 줄의 글은 문서 「대화에 남는 것」 표 그대로다. 상수로 둔다

### 5. `ProactiveCheckService.start` 와 시작 경로

- `public UUID start(CurrentUser user, String agentCode, CheckTrigger trigger)`:
  1. `requireStartable`. 꺼진 에이전트는 `AGENT_DISABLED`
  2. `ProactiveCheckReadiness.check` 가 막으면 `ApiException(PROACTIVE_CHECK_UNAVAILABLE, ...)`
  3. `CheckConversations.findOrCreate`
  4. `TurnCancellation.open(user.id(), conversationId)`. `USER_BUSY` 이고 이번에 만든 대화면 `CheckConversations.deleteCreated` 로 지우고 다시 던진다. `CONVERSATION_BUSY` 는 그대로 던진다
  5. `ProactiveCheck.started` 를 저장한다. session 을 바꿀지는 `countByConversationIdAndHermesRootSessionId(conversationId, conversation.hermesRootSessionId()) >= sessionMaxChecks` 로 정한다. 루트 session 이 비어 있으면 바꾸지 않는다
  6. 가상 스레드 `proactive-check-<conversationId>` 에서 `ChatService.runProactiveCheck` 를 부르고, 사건은 `ConversationEventHub.publish` 로 보낸다. 스레드를 띄우지 못하면 줄을 `FAILED` 로 적고 잠금을 푼 뒤 예외를 던진다
  7. 점검 대화의 공개 식별자를 돌려준다
- 가상 스레드 안: 정상으로 끝나면 `ProactiveCheckRun` 이 든 결과로 줄을 `SUCCEEDED` 나 `STOPPED` 로 적는다. 예외면 `FAILED` 와 오류 코드(`ApiException` 이면 그 코드, 아니면 `INTERNAL_ERROR`)를 적고, `ConversationNotices.post` 로 「살펴보기를 끝내지 못했어요. 잠시 뒤 다시 눌러 주세요」 를 남기고, `ChatEvent.error` 를 보낸다. `finally` 에서 잠금을 푼다
- `ProactiveCheckController` 에 `POST /api/v1/agents/{code}/proactive-check/runs` 를 더한다. 202 와 `ProactiveCheckDtos.StartedResponse(UUID conversationId)` 를 준다. `trigger` 는 `MANUAL` 이다

### 6. 이 phase 를 검증하는 시험

`backend/src/test/java/com/bifos/assistant/proactive/` 아래에 둔다. `HermesToolsetClient` 와 켜진 스킬 목록은 대역으로 둔다. 모든 데이터는 합성이다.
시험은 `@SpringBootTest(properties = {"hermes.run-timeout=30s", "assistant.proactive-check.max-duration=20s"})` 처럼 두 값을 함께 올린다. test profile 의 `hermes.run-timeout` 은 1초다.

- `ProactiveCheckTurnTest.java`(`@SpringBootTest`, `ChatServiceTest.StubRuntime`):
  - 결과 블록이 든 답이면 점검 대화에 시작 알림 줄과 실행 번호가 붙은 `ASSISTANT` 답이 남고, 답의 글이 그려진 글이며 JSON 이 없다. `proactive_check` 가 `SUCCEEDED`, `FINDINGS`, 발견 수와 함께 남고 `proactive_check_finding` 줄이 생긴다
  - `NOTHING_NEW` 면 알림 줄 「살펴봤지만 새로 알릴 것이 없어요」 만 남는다
  - 블록이 없으면 「결과를 정리하지 못했어요」 와 `INVALID_RESULT`
  - Hermes 에 보낸 명령(`StubHermesRunsClient.received()`)의 `instructions` 가 Control Plane 지시를 담고, 입력이 `skill_view(name="proactive-check")` 문장과 `<external-data>` 로 감싼 최근 발견을 담는다
  - Memory 제안이 생기지 않고 `auto_turn_count` 와 제목이 그대로다
  - 두 번째 살펴보기의 입력에 첫 살펴보기의 발견(주제 키, 주소)과 그 뒤 사용자 메시지 수, 변화 신호(지난 살펴보기 시각, 메시지 수, Memory 같음)가 실린다. 첫 살펴보기와 같은 주제 키와 주소를 `changeSinceLast` 없이 다시 내면 「이미 알린 것이에요」 참고로 그려진다
  - `@MockitoBean HermesRunEventStream` 으로 `message.delta` 와 `tool.started` 를 흘리면 `ConversationEventHub` 로 나간 사건에 `tool` 은 있고 `delta` 는 없다. 본보기는 `chat/ToolDetailStreamTest.java`
  - 같은 session 으로 `session-max-checks` 번 돈 뒤의 살펴보기는 새 루트 session 으로 보낸다(시험에서는 그 값을 2 로 둔다)
  - 실행이 실패하면 `FAILED`, 실패 알림 줄, 잠금이 풀려 다음 살펴보기를 시작할 수 있다
- `ProactiveCheckStartTest.java`(HTTP):
  - 202 와 점검 대화 식별자. 두 번째 시작은 같은 대화를 쓴다
  - 막는 까닭이 있으면 409 `PROACTIVE_CHECK_UNAVAILABLE` 이고 대화를 만들지 않는다
  - 사용자 자리가 없으면 409 `USER_BUSY` 이고 새로 만든 점검 대화가 남지 않는다
  - 점검 대화에 도는 turn 이 있으면 409 `CONVERSATION_BUSY`
  - 다른 사용자의 비공개 에이전트는 404

## 검증

```bash
# cwd: backend/
./gradlew test --tests 'com.bifos.assistant.proactive.*' --tests 'com.bifos.assistant.chat.*'
./gradlew test
./gradlew checkstyleMain checkstyleTest
```

```bash
# cwd: 저장소 root
scripts/check-mysql-migration.sh
```

기대값: 모두 종료 코드 0. 기존 `chat` 시험이 그대로 통과해 보통 turn 과 자동 turn 의 동작이 바뀌지 않았음을 보인다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/chat/application/CheckTurn.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/model/CheckAnswer.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/TurnIntent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ConversationSessions.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ConversationWriter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ConversationRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ChatMessageRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveCheckRun.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveCheckService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/presentation/ProactiveCheckController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/proactive/presentation/ProactiveCheckDtos.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/proactive/ProactiveCheckTurnTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/proactive/ProactiveCheckStartTest.java` | 신규 |
