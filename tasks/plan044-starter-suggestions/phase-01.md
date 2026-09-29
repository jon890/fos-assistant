# Phase 01. backend 가 추천 질문을 만들어 메모리에 두고 사람이 적던 추천과 소개를 없앤다

**Execution profile**: deep

## 목표

`GET /api/v1/agents/{code}/starters` 가 그 사용자와 그 에이전트에 맞춰 모델이 만든 추천을 돌려준다.
추천은 backend 메모리에만 두고, 없으면 읽을 때, 오래됐으면 대화를 마쳤을 때 백그라운드로 다시 만든다.
사람이 적던 `agent_starter_prompt` 와 `agent.tagline`, 그것을 쓰던 `PUT` 경로를 없앤다.

**범위 외**: 화면(phase 02). 스킬 커맨드 이름을 추천에 섞는 것(추천 실행이 그 에이전트 profile 로 돌아 Hermes 가 켜진 스킬 색인을 이미 입력에 싣는다. 따로 넣지 않는다).

## 컨텍스트

**근거 문서**: `docs/adr/ADR-036-추천-질문은-사용자의-대화-이력으로-모델이-만들고-메모리에만-둔다.md`, `docs/code-architecture.md` 의 「추천 질문」 절, `docs/flow.md` 의 「새 대화 화면」 과 「추천을 만들 때」 절, `docs/data-schema.md` 의 「agent_execution」, 「agent」 절

- 대화 뒤에 Hermes 실행을 하나 더 돌리는 틀은 `backend/src/main/java/com/bifos/assistant/memory/application/MemoryProposer.java` 에 있다. `ExecutionRecorder.start` 로 실행 줄을 만들고 `HermesRunsClient.submit`, `awaitCompletion` 후 `executions.complete` 또는 `fail` 을 부른다. 같은 순서를 따른다. 다만 그 클래스는 `ChatService.finish` 안에서 동기로 불린다. 추천은 **따로 돌린다**
- 추천 실행은 그 에이전트의 profile 로 돈다. 그래서 Hermes 가 그 profile 의 성격(SOUL)과 켜진 도구, 스킬 색인을 이미 입력에 싣는다. 성격이나 도구를 우리가 읽어 넣지 않는다
- `ExecutionRecorder.base(user, conversation, agent)` 는 `conversation.id()` 를 쓴다. 대화 없는 실행을 위해 `conversation` 이 null 이면 `conversationId` 를 비우게 한다. `AgentExecution.conversationId` 의 `@Column(nullable = false)` 도 푼다. `UsageController` 는 이미 `conversationId()` 가 null 인 경우를 다룬다
- 실행 줄은 `parent_execution_id`, `root_execution_id` 를 비운다. `hermes_session_id` 도 비운다(대화 session 이 없다)
- 대화: `Conversation.agentId`, `Conversation.userId`, `deletedAt`. 메시지: `ChatMessage` 의 `conversationId`, `role`(`MessageRole.USER`, `ASSISTANT`), `content`, `id`
- 기존 추천 경로: `StarterService`(`read`, `write`, `promptsOf`), `StarterSnapshot`, `AgentStarterController`(`GET`, `PUT /api/v1/agents/{code}/starters`), `AgentController.readable()` 이 `starters.promptsOf(list)` 로 목록에 추천을 싣는다. `AgentDtos` 의 `StartersView`, `WriteStartersRequest`, `AgentView`(`tagline`, `starterPrompts`)
- 한 줄 소개: `Agent.tagline`, `Agent.changeTagline`, `Agent.tagline()`
- 가짜 Hermes 는 `test/e2e/fake-hermes.ts` 의 `specialOutputFor(input)` 이 입력의 표지를 보고 답을 고른다. backend 단위 테스트는 `MemoryProposerTest` 가 Hermes 를 어떻게 바꿔 끼우는지 따른다

## 의도 메모

- **추천을 화면 요청 안에서 만들지 않는다.** 모델 호출이 몇 초 걸린다. 없으면 `GENERATING` 을 곧바로 돌려주고 화면이 다시 읽는다
- **같은 `(userId, agentId)` 의 만들기는 하나만 돈다.** 진행 중 표시를 `ConcurrentHashMap` 으로 두고 끝나면 지운다
- **실패하면 이전 추천을 그대로 둔다.** 이전이 없으면 `NONE`
- **이력은 그 사용자의 대화만 읽는다.** 가족용 에이전트에서도 `userId` 로 거른다. 지운 대화(`deletedAt`)는 뺀다
- 추천 입력의 첫 줄에 `StarterSuggestionService.PROMPT_MARK`(`[추천 질문 만들기]`) 를 둔다. 가짜 Hermes 가 이 표지로 답을 고른다
- 모델 답은 JSON 문자열 배열만 받는다. 파싱 실패, 빈 배열은 실패로 본다. 넷을 넘으면 앞의 넷, 한 줄 120자를 넘는 것은 버린다
- 캐시는 재시작하면 빈다. 데이터베이스에 두지 않는다(ADR-036)

## Blocked 조건

- `db/migration` 에 `conversation_id` 를 비워도 되게 바꾸는 것과 같은 변경이 이미 있으면 → `PHASE_BLOCKED: 실행 줄의 conversation_id 변경이 이미 있다`

## 작업 항목

### 1. 마이그레이션 `backend/src/main/resources/db/migration/V{다음}__starter_suggestions.sql`

번호는 V33 이다. 여러 계획을 나란히 구현해 번호를 미리 나눴다. 여러 칸을 한 문장으로 바꾸는 문법이 MySQL 과 H2 MySQL 모드에서 달라 문장마다 따로 쓴다(`V28__execution_session_and_delegation.sql` 의 주석과 같다).

- `ALTER TABLE agent_execution MODIFY COLUMN conversation_id BIGINT NULL;`
- `DROP TABLE agent_starter_prompt;`
- `ALTER TABLE agent DROP COLUMN tagline;`

### 2. 실행 기록이 대화 없는 실행을 받는다

- `usage/domain/AgentExecution.java`: `conversationId` 의 `nullable = false` 를 푼다
- `usage/application/ExecutionRecorder.java`: `base` 가 `conversation == null` 이면 `conversationId(null)`. 대화 없는 실행을 여는 `startDetached(CurrentUser user, Agent agent)` 를 더한다. 부모, 뿌리, session, 모델 선택은 모두 null 이고 문맥 글자 수는 0 이다

### 3. `agent/application/StarterSuggestionService.java` 신규

- 설정 `agent/application/StarterProperties.java`(`@ConfigurationProperties("assistant.starters")`): `enabled`(기본 true), `refreshAfter`(`Duration`, 기본 `PT24H`), `historyConversations`(기본 20). `application.yml` 에 `assistant.starters` 를 더한다
- 캐시 키 `record Key(Long userId, Long agentId)`, 값 `record Entry(List<String> prompts, Instant generatedAt)`
- `StarterSuggestions read(CurrentUser user, String code)`: `AgentService.requireReadable` 로 에이전트를 찾는다. 캐시에 있으면 `READY` 와 추천. 없으면 만들기를 시작하고 `GENERATING` 과 빈 목록. 이미 만드는 중이면 `GENERATING`. 설정 `enabled` 가 거짓이면 `NONE`
- `void refreshIfStale(CurrentUser user, Agent agent)`: 캐시 값이 `refreshAfter` 보다 오래됐거나 없으면 만들기를 시작한다. 예외를 밖으로 내지 않는다
- 만들기는 전용 실행기(virtual thread)에서 돈다. 순서:
  1. 그 사용자의 그 에이전트 대화 가운데 지우지 않은 것을 최근 순으로 `historyConversations` 개까지 찾고 각 대화의 첫 `USER` 메시지 내용을 모은다
  2. 입력을 만든다. 첫 줄 `PROMPT_MARK`. 이력이 있으면 「사용자가 이 에이전트에게 최근에 처음 꺼낸 말들이다. 자주 하는 일을 묶어 다음에 바로 보낼 만한 요청 넷을 만든다」와 목록, 없으면 「너의 성격과 쓸 수 있는 도구와 스킬로 이 사용자가 처음 해 볼 만한 요청 넷을 만든다」. 둘 다 「도구를 부르지 말고 JSON 문자열 배열 하나만 답한다」
  3. `startDetached` 로 실행 줄을 열고 `HermesRunCommand(agent.hermesProfile(), agent.apiBaseUrl(), input, null, null, null, null, null)` 을 제출해 끝을 기다린다
  4. 성공이면 파싱해 캐시에 넣고 `executions.complete`, 실패면 `executions.fail` 하고 캐시를 그대로 둔다
- 필요한 조회를 저장소에 더한다: `ConversationRepository` 에 사용자와 에이전트로 지우지 않은 대화를 `updatedAt` 내림차순으로 읽는 메서드(개수는 `Pageable` 로), `ChatMessageRepository` 에 대화의 첫 `USER` 메시지를 읽는 메서드

### 4. 경로와 기존 코드 정리

- `StarterService`, `StarterSnapshot`, `AgentStarterPrompt`, `AgentStarterPromptRepository` 를 지운다
- `AgentStarterController`: `GET` 만 남기고 `StarterSuggestionService.read` 를 부른다. 응답 `StartersView(List<String> prompts, String status)`. `PUT` 을 지운다
- `AgentDtos`: `WriteStartersRequest` 를 지우고 `AgentView` 에서 `tagline`, `starterPrompts` 를 뺀다. `StartersView` 를 위 모양으로 바꾼다
- `AgentController.readable()`: 추천을 싣지 않는다. `StarterService` 의존을 뺀다
- `Agent`: `tagline` 칸과 `tagline()`, `changeTagline` 을 지운다
- `chat/application/ChatService.finish`: `memoryProposer.proposeFrom(...)` 다음에 `starterSuggestions.refreshIfStale(pending.user(), pending.agent())` 를 부른다

### 5. 이 phase 를 검증하는 테스트

- `backend/src/test/java/com/bifos/assistant/agent/StarterSuggestionServiceTest.java` 신규. Hermes 는 `MemoryProposerTest` 와 같은 방법으로 바꿔 끼운다
  - 캐시가 비면 `GENERATING` 을 받고, 만들기가 끝난 뒤 다시 읽으면 `READY` 와 모델이 답한 넷
  - 이력이 있으면 Hermes 에 보낸 입력에 그 사용자 대화들의 첫 질문이 들어 있고, 다른 사용자의 같은 에이전트 대화 첫 질문은 없다
  - 이력이 없으면 입력이 「처음 해 볼 만한」 문구를 담는다
  - 모델 답이 JSON 이 아니면 `NONE`, 이전 추천이 있었으면 그것이 남는다
  - 같은 키로 두 번 읽어도 Hermes 제출은 한 번이다
  - `refreshIfStale` 은 `refreshAfter` 안이면 제출하지 않고, 지나면 제출한다
  - 추천 실행 줄의 `conversationId` 가 null 이고 상태가 끝에 `SUCCEEDED` 다
- `AgentStarterControllerTest`: `GET` 응답 모양으로 바꾸고 `PUT` 검사를 지운다. 볼 수 없는 에이전트는 `AGENT_NOT_FOUND` 인 것은 남긴다
- `StarterServiceTest` 를 지운다
- `test/e2e/fake-hermes.ts`: `specialOutputFor` 에서 입력이 `[추천 질문 만들기]` 로 시작하면 `["이번 주 일정 정리해 줘","장보기 목록 만들어 줘","오늘 날씨 알려 줘","가계부 요약해 줘"]` 를 답한다
- `test/e2e/scenarios/starters.ts` 를 새 계약으로 다시 쓴다
  - `dad` 가 `GET /agents/dad/starters` 를 읽으면 처음에는 `GENERATING`, 1초 간격으로 다시 읽어 5초 안에 `READY` 와 위 넷이 온다
  - `PUT /agents/dad/starters` 는 405 나 404 로 거절된다
  - `GET /agents` 목록 응답에 `tagline`, `starterPrompts` 가 없다
  - `kid` 가 볼 수 없는 에이전트의 추천은 `AGENT_NOT_FOUND`
- `ChatAttachmentTurnTest` 의 `AgentController` 생성과 `StarterService` 주입을 새 생성자에 맞춘다

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
cd backend && ./gradlew test --tests 'com.bifos.assistant.agent.StarterSuggestionServiceTest' --tests 'com.bifos.assistant.agent.AgentStarterControllerTest'
node test/e2e/run.ts
grep -rn "tagline\|AgentStarterPrompt\|StarterService\b\|starterPrompts" backend/src/main  # 결과 없음
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V33__starter_suggestions.sql` | 신규 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/AgentExecution.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionRecorder.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/application/StarterSuggestionService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/application/StarterProperties.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/application/StarterService.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/agent/application/StarterSnapshot.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/agent/domain/AgentStarterPrompt.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/agent/infra/AgentStarterPromptRepository.java` | 삭제 |
| `backend/src/main/java/com/bifos/assistant/agent/domain/Agent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentStarterController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ConversationRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ChatMessageRepository.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/StarterSuggestionServiceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentStarterControllerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/StarterServiceTest.java` | 삭제 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatAttachmentTurnTest.java` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/e2e/scenarios/starters.ts` | 수정 |
