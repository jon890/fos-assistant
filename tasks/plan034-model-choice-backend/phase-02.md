# Phase 02. 대화가 모델과 effort 를 저장하고 돌려준다

**Execution profile**: deep

## 목표

대화마다 모델과 reasoning effort 를 저장하는 칸과 그 값을 바꾸는 경로 `PUT /api/v1/chat/conversations/{conversationId}/model` 을 둔다.
대화 한 줄의 응답이 고른 값을 싣는다. 흐름 에이전트도 첫 메시지 전에 빈 대화를 만들어 모델을 고를 수 있다.

**범위 외**: 실행이 이 값을 쓰는 것과 넘김을 없애는 것은 phase 03 이다. 이 phase 는 turn 의 동작을 바꾸지 않는다. `ChatService` 의 turn, `AgentRunner`, `MemoryProposer`, `ExecutionRecorder` 의 인자는 그대로다. 모델 목록 경로는 phase 04, 모델을 고르는 화면은 plan036 이다.

## 컨텍스트

**근거 문서**: `docs/flow.md` 「모델을 고를 때」 의 「갈리는 지점」, `docs/data-schema.md` 「conversation」 과 「agent_execution」, `docs/code-architecture.md` 「대화」 의 「경로」 와 「대화의 모델 선택」, `docs/adr/ADR-030-모델과-effort-는-대화가-고르고-기본값은-hermes-profile-이-갖는다.md`

- 대화 이름 바꾸기는 `ChatService.rename` 이 `ConversationRepository.renameIfActive` 로 칸만 고친다. **turn 이 끝날 때 대화를 통째로 저장하지 않는다**(`docs/code-architecture.md` 「대화」). 모델 선택도 같은 방식의 갱신 질의로 쓴다
- 마지막 migration 은 `V25__group_rename.sql` 이다. 새 파일은 `V26` 이다. 이미 적용된 migration 은 고치지 않는다(`backend/AGENTS.md`)
- `ChatService.startEmpty(user, agentCode)` 는 `!agent.acceptsAttachments()` 인 에이전트(흐름이 있는 에이전트)를 `VALIDATION_FAILED` 로 거절한다. `docs/flow.md` 「모델을 고를 때」 는 대화가 없으면 이 길로 빈 대화를 만든 뒤 모델을 고른다고 적었다. 사진은 보내기 경로(`ChatService` 의 `withAttachments && !agent.acceptsAttachments()` 검사)가 따로 막는다
- 사진 올리기(`AttachmentService.upload`)는 에이전트를 보지 않는다. 흐름 에이전트의 빈 대화에 사진을 올리면 올라가고, 보내기에서 거절된다. 보내지 않은 첨부는 보관 기간이 지나면 지워지는 기존 규칙을 따른다
- 엔티티를 바꾸면 migration 도 함께 바꾼다. 테스트는 엔티티로 스키마를 만들고 운영은 Flyway 스키마를 검증하므로 어긋나도 테스트는 통과한다(`backend/AGENTS.md` 「엔티티와 마이그레이션은 따로 논다」)

## 의도 메모

- 선택을 바꿔도 `updated_at` 은 건드리지 않는다. 대화 목록의 순서는 주고받은 시각으로 정한다
- 고른 모델이 Hermes 목록에 있는지는 확인하지 않는다. 나중에 목록에서 빠져도 대화에 적힌 값을 그대로 보낸다(`docs/flow.md` 「갈리는 지점」)
- `agent_execution.reasoning_effort` 칸과 엔티티 필드는 이 phase 에서 migration 과 함께 만든다. 값을 적는 것은 phase 03 이다
- 배포 뒤 확인할 것: 기동 로그에서 `Schema validation` 이 실패하지 않는다. 확인 방법은 `fos-home-infra` 가 갖는다

## 작업 항목

### 1. migration `backend/src/main/resources/db/migration/V26__conversation_model_choice.sql`

- `conversation` 에 `model_provider VARCHAR(64) NULL`, `model VARCHAR(128) NULL`, `reasoning_effort VARCHAR(16) NULL`
- `agent_execution` 에 `reasoning_effort VARCHAR(16) NULL`
- MySQL 과 H2 MySQL 모드에 함께 있는 문법만 쓴다

### 2. `backend/src/main/java/com/bifos/assistant/chat/domain/ModelChoice.java`

record `ModelChoice(String provider, String model, String reasoningEffort)`.
- 생성할 때 앞뒤 공백을 떼고 빈 문자열은 null 로 둔다
- provider 와 모델은 함께 채우거나 함께 비운다. 하나만 오면 `ApiException(ErrorCode.VALIDATION_FAILED)`
- effort 는 null 이거나 `low`, `medium`, `high`, `xhigh`, `max` 중 하나. 아니면 `VALIDATION_FAILED`. 이 목록은 `public static final List<String> REASONING_EFFORTS` 로 두고 phase 04 가 꺼내 쓴다
- `static ModelChoice defaults()` 는 셋 다 null, `boolean usesDefaultModel()` 은 모델이 비었는가

### 3. `Conversation` 과 `ConversationRepository`

`backend/src/main/java/com/bifos/assistant/chat/domain/Conversation.java` 에 필드 셋을 더한다. 열 이름과 길이는 `docs/data-schema.md` 「conversation」 을 따른다.
- `@Column(name = "model_provider", length = 64) private String modelProvider;`
- `@Column(name = "model", length = 128) private String model;`
- `@Column(name = "reasoning_effort", length = 16) private String reasoningEffort;`
- `public ModelChoice modelChoice()` 는 세 값으로 `ModelChoice` 를 만들어 돌려준다

`backend/src/main/java/com/bifos/assistant/chat/infra/ConversationRepository.java` 에 `int chooseModelIfActive(Long id, Long userId, String provider, String model, String reasoningEffort)` 갱신 질의를 `renameIfActive` 와 같은 모양(`@Modifying(flushAutomatically = true, clearAutomatically = true)`, `@Transactional`, `deletedAt is null` 조건)으로 더한다. `updatedAt` 은 건드리지 않는다.

### 4. `AgentExecution` 의 effort 칸

`backend/src/main/java/com/bifos/assistant/usage/domain/AgentExecution.java` 에 `@Column(name = "reasoning_effort", length = 16) private String reasoningEffort` 와 builder 의 `reasoningEffort(String)`, 읽기 메서드 `reasoningEffort()` 를 더한다. 이 phase 에서는 아무도 값을 넣지 않는다.

### 5. `ChatService` 의 선택 변경과 빈 대화

- `public Conversation chooseModel(CurrentUser user, Long conversationId, ModelChoice choice)` 를 `rename` 과 같은 순서로 둔다. 주인 확인, `chooseModelIfActive`, 0 줄이면 `CONVERSATION_NOT_FOUND`, 다시 읽어 돌려준다
- `startEmpty` 의 `acceptsAttachments` 조건을 뺀다. 에이전트를 쓸 수 있는지(`requireStartableAgent`)만 본다. Javadoc 을 「사진을 먼저 올리거나 첫 메시지 전에 모델을 고르려면 대화가 먼저 있어야 한다」 로 고친다

### 6. 경로 `PUT /api/v1/chat/conversations/{conversationId}/model`

- `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatDtos.java` 에 `ChooseModelRequest(String provider, String model, String reasoningEffort)` 와 `ModelChoice toChoice()`
- `ConversationView` 에 `provider`, `model`, `reasoningEffort` 칸을 더한다. 고르지 않았으면 셋 다 null 이다. 목록과 이름 바꾸기 응답도 같은 모양이 된다
- `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatController.java` 에 경로를 더하고 이름 바꾸기 경로처럼 `access.requireOwnId` 로 주인을 확인한다. 바뀐 대화 한 줄(`ConversationView`)을 돌려준다

### 7. web 서버 라우트

`web/src/app/api/chat/conversations/[conversationId]/model/route.ts`: `PUT` 을 Control Plane `PUT /api/v1/chat/conversations/{id}/model` 로 넘긴다. 같은 폴더의 `web/src/app/api/chat/conversations/[conversationId]/route.ts` 의 `PATCH` 가 본보기다(대화 주소 검사와 오류 응답). 이 phase 에서는 화면이 부르지 않는다. phase 03 의 브라우저 검사와 plan036 의 화면이 쓴다.

### 8. docs 두 줄

`docs/flow.md` 「모델을 고를 때」 의 시퀀스 그림에서 `B->>W: 빈 대화를 만든다(사진을 먼저 올릴 때와 같은 길)` 를 흐름 에이전트도 같은 길이라는 것이 드러나게 고친다. 예: `빈 대화를 만든다(사진을 먼저 올릴 때와 같은 길, 흐름 에이전트도 같다)`. 같은 문서 「사진을 올려 보낼 때」 의 「갈리는 지점」 표에서 `흐름이 붙은 에이전트 | 대화를 만들 때와 보낼 때 거절한다. …` 줄을 `보낼 때 거절한다. 빈 대화는 만들 수 있다(모델을 먼저 고를 때). 흐름의 입력에는 사진 자리를 덧붙이지 않는다. 화면은 사진 단추를 두지 않는다` 로 고친다. 이 두 줄 밖의 docs 는 고치지 않는다.

### 9. 이 phase 를 검증하는 backend 테스트

`backend/src/test/java/com/bifos/assistant/chat/ConversationModelChoiceTest.java` 를 새로 둔다. 같은 폴더 `ConversationManageTest` 의 준비 방식을 따른다.
- `chooseModel` 로 provider, 모델, effort 를 고르면 대화를 다시 읽었을 때 `modelChoice()` 가 그 값이다. `updatedAt` 이 바뀌지 않는다
- 모두 비워 보내면 기본값(셋 다 null)으로 돌아간다
- provider 만 주거나 모르는 effort(`"extreme"`)를 주면 `VALIDATION_FAILED` 이고 저장된 값이 그대로다
- 앞뒤 공백을 준 값은 떼고 저장한다
- 남의 대화와 지운 대화는 `CONVERSATION_NOT_FOUND`
- 흐름이 있는 에이전트로 `startEmpty` 하면 빈 대화가 생긴다. 그 대화에 사진을 붙여 보내면 지금처럼 거절된다

`ChatDtos` 의 `ConversationView` 가 바뀌어 깨지는 테스트가 있으면 새 칸을 넣어 맞춘다. `backend/src/test/java/com/bifos/assistant/chat/EmptyConversationTest.java` 의 `흐름이_붙은_에이전트에는_빈_대화를_만들지_않는다` 는 「흐름이 붙은 에이전트도 빈 대화를 만든다」 로 바꾼다. 빈 대화가 하나 생기고 제목이 비어 있음을 단언한다.
H2 로 모든 migration 을 도는 `*MigrationTest` 가 V26 을 함께 통과해야 한다.

## 검증

`AGENTS.md` 「확인」 절의 여섯 명령을 적힌 순서대로 모두 돌린다. 첫 줄은 이 phase 의 테스트만 먼저 돌리는 것이다.

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests '*ConversationModelChoiceTest' --tests '*EmptyConversationTest' --tests '*MigrationTest'
cd backend && ./gradlew test
cd web && pnpm typecheck
cd web && AUTH_SECRET=build-time-placeholder ASSISTANT_JWT_SECRET=build-time-placeholder CONTROL_PLANE_BASE_URL=http://build-time-placeholder AUTH_GOOGLE_ID=build-time-placeholder AUTH_GOOGLE_SECRET=build-time-placeholder pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
```

- 모두 통과한다

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/resources/db/migration/V26__conversation_model_choice.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/ModelChoice.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/domain/Conversation.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ConversationRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/ChatDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/domain/AgentExecution.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ConversationModelChoiceTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/EmptyConversationTest.java` | 수정 |
| `web/src/app/api/chat/conversations/[[]conversationId]/model/route.ts` | 신규 |
| `docs/flow.md` | 수정 |
