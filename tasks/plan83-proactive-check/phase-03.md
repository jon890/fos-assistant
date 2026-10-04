# Phase 03. 시작 전 점검, 점검 대화, 상태 조회

**Execution profile**: standard

## 목표

에이전트가 살펴보기를 할 수 있는지 판정하고, 사용자와 에이전트의 점검 대화를 찾거나 만들고, 상태 조회 경로 `GET /api/v1/agents/{code}/proactive-check` 를 연다.
대화 응답에 `purpose` 를 실어 화면이 점검 대화를 알아보게 한다.

**범위 외**: 살펴보기를 실제로 돌리는 시작 경로와 turn(phase 04).

## 컨텍스트

**근거 문서**: `docs/backend/proactive-check.md` 의 「진입점」, 「시작 전 점검」, 「점검 대화」, `docs/backend/schema/chat.md` 의 `conversation.purpose`

- 에이전트 판정: `AgentService.requireStartable(CurrentUser user, String code)`(`backend/src/main/java/com/bifos/assistant/agent/application/AgentService.java`). 대화를 시작할 수 없으면 `AGENT_NOT_FOUND` 다
- 켜진 스킬: `SkillCommandCatalog.enabledNames(Agent agent)`(`skill/application/SkillCommandCatalog.java`). 스킬 커맨드와 같은 목록이다
- 켜진 toolset: `HermesToolsetClient.readEnabled(String apiBaseUrl, String profileName)`(`hermes/` 패키지). `AgentToolService.read` 가 쓰는 것과 같다. Control Plane MCP 이름은 `AgentToolPolicy.CONTROL_PLANE_MCP`(`fos-assistant`)다
- 컨트롤러 본보기: `agent/presentation/AgentController.java`. `CurrentUserProvider.require()` 로 사용자를 얻는다. 요청과 응답 모양은 그 패키지의 `*Dtos.java` 하나에 모은다(`backend/AGENTS.md`)
- 대화 응답: `chat/presentation/ChatDtos.java` 의 `ConversationView`. 이것을 만드는 자리를 모두 찾아 `purpose` 를 채운다
- 오류 코드: `shared/error/ErrorCode.java`

## 의도 메모

- 점검 대화를 찾고 만드는 것은 사용자와 에이전트마다 JVM 잠금 하나 안에서 한다. 단추를 두 번 눌러도 대화가 둘 생기지 않게 하기 위해서다. 서버 한 대 전제다(`docs/backend/execution-limit.md` 「서버 한 대 전제」 와 같다).
- 새 점검 대화를 만들기 전에 `UserExecutionLimiter.hasTurnRoom(userId)` 를 본다. `ChatService.route` 와 같은 까닭이다. 자리가 없으면 대화를 만들지 않고 `USER_BUSY` 다.
- 상태 조회는 Hermes 를 부른다(toolset 읽기). 실패하면 그 예외를 그대로 올린다. 화면은 절 안에 실패 문구를 그린다.

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveCheckReadiness.java`

- `static final Set<String> ALLOWED_TOOLSETS = Set.of("web", "vision", "todo", "skills", AgentToolPolicy.CONTROL_PLANE_MCP)`
- `static final String SKILL_NAME = "proactive-check"`
- `CheckReadiness check(Agent agent)`: 문서 「시작 전 점검」 의 까닭을 차례로 모은다. `DISABLED`(`ProactiveCheckProperties.enabled()` 거짓), `AGENT_NOT_SUPPORTED`(`agent.connectorManaged()` 이거나 `agent.flow()` 가 비지 않았다), `SKILL_MISSING`, `TOOLSETS_NOT_ALLOWED`(허용 목록 밖 이름들을 정렬해 싣는다). `AGENT_NOT_SUPPORTED` 이면 Hermes 를 부르지 않는다
- `proactive/application/model/CheckReadiness.java`: record `CheckReadiness(List<CheckBlocker> blockers)` 와 `boolean available()`
- `proactive/application/model/CheckBlocker.java`: record `CheckBlocker(CheckBlockerCode code, List<String> toolsets)`
- `proactive/application/model/CheckBlockerCode.java`: `DISABLED`, `AGENT_NOT_SUPPORTED`, `SKILL_MISSING`, `TOOLSETS_NOT_ALLOWED`

### 2. `backend/src/main/java/com/bifos/assistant/chat/application/CheckConversations.java`

`chat` 이 점검 대화를 소유한다. `proactive` 가 부른다.

- `Optional<Conversation> find(Long userId, Long agentId)`: `ConversationRepository.findFirstByUserIdAndAgentIdAndPurposeAndDeletedAtIsNullOrderByIdDesc(..., CHECK)`
- `OpenedCheck findOrCreate(CurrentUser user, Agent agent)`: 사용자와 에이전트마다의 잠금 안에서 찾고, 없으면 자리를 본 뒤 `Conversation.startedForCheck(user.id(), "먼저 살펴보기 · " + agent.name(), agent.id(), now)` 로 만든다. 제목은 200자를 넘으면 자른다
- `chat/application/model/OpenedCheck.java`: record `OpenedCheck(Conversation conversation, boolean created)`
- `void deleteCreated(Long conversationId)`: 시작이 거절돼 방금 만든 점검 대화를 지운다. `ChatService.deleteCreatedConversation` 과 같이 실패하면 경고 로그만 남긴다

### 3. `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveCheckService.java`

이 phase 에서는 상태 조회만 만든다. 시작은 phase 04 가 더한다.

- `CheckStatusView status(CurrentUser user, String agentCode)`: `requireStartable`, 준비 판정, 점검 대화의 공개 식별자, `ProactiveCheckRepository.findFirstByUserIdAndAgentIdOrderByIdDesc` 의 마지막 살펴보기를 담는다
- `proactive/application/model/CheckStatusView.java`: record `CheckStatusView(CheckReadiness readiness, UUID conversationId, ProactiveCheck lastCheck)`

### 4. `backend/src/main/java/com/bifos/assistant/proactive/presentation/ProactiveCheckController.java` 와 `ProactiveCheckDtos.java`

- `GET /api/v1/agents/{code}/proactive-check` → `ProactiveCheckDtos.StatusResponse(boolean available, List<BlockerView> blockers, UUID conversationId, LastCheckView lastCheck)`
- `BlockerView(String code, List<String> toolsets)`, `LastCheckView(String status, String outcome, Instant startedAt, Instant finishedAt)`. 점검 대화나 마지막 살펴보기가 없으면 null
- 응답에 실행 번호, profile, 오류 코드 원문, 토큰, 금액을 싣지 않는다

### 5. 오류 코드와 대화 응답

- `ErrorCode.PROACTIVE_CHECK_UNAVAILABLE(HttpStatus.CONFLICT)` 를 Javadoc 한 줄과 함께 더한다. 이 phase 에서는 쓰지 않아도 phase 04 가 쓴다
- `ChatDtos.ConversationView` 에 `ConversationPurpose purpose` 를 더하고 만드는 자리를 모두 고친다
- 웹의 대화 타입은 phase 07 이 고친다. 응답에 칸이 하나 늘 뿐이라 지금 웹은 깨지지 않는다

### 6. 이 phase 를 검증하는 시험

`backend/src/test/java/com/bifos/assistant/proactive/` 아래에 둔다. `HermesToolsetClient` 는 `@MockitoBean` 으로 바꾼다. 본보기는 `agent/AgentToolServiceTest.java`.

- `ProactiveCheckReadinessTest.java`: 허용 toolset 과 스킬이 있으면 통과, `delegation` 과 `terminal` 이 켜져 있으면 `TOOLSETS_NOT_ALLOWED` 에 두 이름, 스킬이 없으면 `SKILL_MISSING`, 커넥터 에이전트는 Hermes 를 부르지 않고 `AGENT_NOT_SUPPORTED`, 설정을 끄면 `DISABLED`
- `ProactiveCheckStatusTest.java`: 상태 조회 HTTP 시험. 다른 사용자의 비공개 에이전트는 404 `AGENT_NOT_FOUND`, 점검 대화가 없으면 `conversationId` 가 null, 있으면 그 사용자의 것만 나온다(같은 그룹 공개 에이전트를 다른 사용자가 조회해도 남의 점검 대화가 보이지 않는다)
- `CheckConversationsTest.java`: 없으면 만들고 있으면 같은 것을 돌려준다. 지운 점검 대화는 다시 쓰지 않고 새로 만든다. 두 스레드가 동시에 불러도 하나만 생긴다. 자리가 없으면 만들지 않고 `USER_BUSY`
- `chat/ConversationPagingTest.java` 를 고친다: 목록 응답의 `purpose` 가 `CHAT` 인지 하나 단언한다

## 검증

```bash
# cwd: backend/
./gradlew test --tests 'com.bifos.assistant.proactive.*' --tests 'com.bifos.assistant.chat.ConversationPagingTest'
./gradlew test
./gradlew checkstyleMain checkstyleTest
```

기대값: 모두 종료 코드 0.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveCheckReadiness.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/model/CheckReadiness.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/model/CheckBlocker.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/model/CheckBlockerCode.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/model/CheckStatusView.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/application/ProactiveCheckService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/presentation/ProactiveCheckController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/proactive/presentation/ProactiveCheckDtos.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/CheckConversations.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/model/OpenedCheck.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/proactive/ProactiveCheckReadinessTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/proactive/ProactiveCheckStatusTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/proactive/CheckConversationsTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/ConversationPagingTest.java` | 수정 |
