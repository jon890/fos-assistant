# Phase 01. backend 가 스킬 커맨드를 해석하고 호출 이력을 남긴다

**Execution profile**: deep

## 목표

메시지 내용이 `/<이름>` 으로 시작하고 그 이름이 에이전트의 켜진 스킬이면, Hermes 에 보낼 입력만 「`skill_view` 로 읽고 따르라」로 바꾸고 `COMMAND` 이력을 남긴다. 없는 이름은 Hermes 에 보내지 않고 거절한다. 거절할 때는 대화, 메시지, 실행 어느 것도 만들지 않는다.

스킬 호출 횟수는 실행 수로 센다. 커맨드 한 번에 `COMMAND` 줄과 모델이 읽은 `MODEL` 줄이 함께 생겨도 1회다.

**범위 외**: 입력창 자동완성과 말풍선 표시(phase 02). 이력 이름 규칙 넓히기(phase 03).

## 컨텍스트

**근거 문서**: `docs/adr/ADR-035-대화창의-스킬-커맨드는-control-plane-이-해석해-hermes-에-넘긴다.md`, `docs/code-architecture.md` 의 「스킬 커맨드」 절과 「호출 이력」 절, `docs/flow.md` 의 「스킬 커맨드로 보낼 때」 절, `docs/data-schema.md` 의 `execution_skill_use` 절

- `chat/application/ChatService`
  - `send`, `stream` 은 `route(user, conversationId, text, agentCode, attachmentIds)` 로 `Routed` 를 얻고, 흐름이면 `runFlow`, 아니면 `runTurn` 이다
  - `route` 는 `resolveConversation` 으로 **새 대화(`conversationId == null`)를 먼저 저장**한 뒤 에이전트를 확인한다
  - `runTurn` 은 `saveQuestion(...)` 뒤 `artifacts.agentPreamble(conversation) + attachments.agentInput(conversation.id(), routed.attached(), text)` 로 입력을 만들고 `begin(...)` 이 실행 줄과 `HermesRunCommand` 를 만든다
  - `regenerate` 는 `routeExisting` 뒤 저장된 원문 `question.content()` 를 `runTurn` 에 넘긴다. `saveQuestion` 은 `Regenerate` 이면 아무것도 저장하지 않는다
- 스킬 목록: `skill/application/SkillService.list(CurrentUser, String code)` 가 `SkillList(skills, editable, skillsToolsetEnabled)` 를 준다. 항목 `SkillListItem` 은 `name`, `enabled` 를 갖는다. `SkillService` 는 `@RequiredArgsConstructor` 로 `AgentService`, `SkillStore`, `SkillPublisher`, `SkillUsageQuery` 를 받는다. 저장은 `save`, 지우기는 `delete`, 켜고 끄기는 `toggle` 이다
- 이력: `skill/application/SkillUseRecorder.recordCommand(Long executionId, String skillName)`. 집계는 `skill/infra/ExecutionSkillUseRepository.countByAgent`(지금 `count(u)`)와 `findOccurrencesByUser`, `skill/application/SkillUsageQuery.byAgent`, `byUser`(지금 줄마다 `count++`). `skill/domain/SkillUseOccurrence` 에는 실행 번호가 없다
- 오류 코드: `shared/error/ErrorCode` 에 `SKILL_NOT_FOUND(404)` 가 있다. 커맨드의 없는 이름은 400 이어야 하므로 `SKILL_COMMAND_UNKNOWN(HttpStatus.BAD_REQUEST)` 를 따로 더한다
- 오류 전달: `/chat/messages` 는 HTTP 400 과 `code` 로 준다. `/chat/messages/stream` 은 `chat/presentation/ChatEventStreams` 가 HTTP 200 과 `error` 사건(`code`)으로 준다
- 가짜 Hermes: `test/e2e/fake-hermes.ts` 는 마지막 실행의 입력을 `lastSubmittedInput()` 으로 준다. `run.input === "스킬 읽기 검사"` 일 때 `skill_view` 사건(`preview: "shopping"`)을 낸다(1131행 부근)
- e2e: `test/e2e/scenarios/skills.ts` 는 `dad` 에이전트에 스킬 `weekly-plan`(`NAME`)을 올리고, 「끄면 목록에 꺼진 것으로 보인다」 단계에서 끈 뒤 「지우면…」 단계에서 지운다. `shopping` 은 뒤 단계에서 모델 읽기로 `SKILL_READ_TURNS`(1)회 적힌다. `test/e2e/scenarios/usage-cost.ts` 의 `COMPLETED_TURNS` 가 앞 시나리오의 완료 turn 수를 더해 실행 수와 금액을 단언한다

## 의도 메모

- 판별은 `^/([a-z0-9][a-z0-9-]{0,63})(\s|$)` 다. `/usr/bin` 처럼 이름 뒤가 공백이 아니면 커맨드가 아니다. 나머지 글은 이름 뒤 공백 하나를 뺀 뒤 `strip()` 한 것이다
- **대화를 만들기 전에 판별한다.** 새 대화는 `agentCode` 로 찾은 에이전트, 기존 대화는 그 대화의 에이전트로 본다. 없는 이름이면 대화도 메시지도 실행도 만들지 않고 거절한다. 에이전트가 지워졌거나 꺼졌으면 지금처럼 그 오류가 먼저다
- 저장하는 메시지와 새 대화 제목은 사용자가 친 글 그대로다. 바꾸는 것은 Hermes 에 보내는 입력뿐이다
- 바꾼 입력: 「사용자가 `<이름>` 스킬을 호출했다. `skill_view(name="<이름>")` 로 스킬을 읽고 그 절차대로 다음을 한다: <나머지 글>」. 나머지 글이 비면 「스킬의 절차를 처음부터 진행한다」. 사진 자리 덧붙이기와 결과물 안내(`agentPreamble`)는 지금과 같게 이 문장에 붙인다
- 흐름이 붙은 에이전트(`routed.flow() != null`, `Agent.acceptsAttachments() == false`)는 해석하지 않고 글 그대로 보낸다
- **`skills` toolset 이 꺼진 에이전트는 켜진 이름이 없는 것으로 본다**(ADR-035 「감당할 것」). `SkillList.skillsToolsetEnabled` 가 false 이면 `enabledNames` 가 빈 집합이고, 커맨드는 `SKILL_COMMAND_UNKNOWN` 이다
- 스킬 목록을 읽다 Hermes 가 실패하면 그 예외를 그대로 올린다. 이름을 확인하지 못한 커맨드를 보내지 않는다
- 스킬 목록은 에이전트 번호마다 30초 캐시한다. 스킬 저장, 지우기, 켜고 끄기가 Hermes 반영에 성공한 뒤 그 에이전트의 캐시를 비운다. 도구(toolset) 변경은 캐시를 비우지 않는다. 30초 뒤 반영된다
- **빈 순환 의존을 만들지 않는다.** `SkillCommandCatalog` 가 `SkillService` 를 받고, `SkillService` 는 catalog 를 받지 않는다. `SkillService` 는 `ApplicationEventPublisher` 로 `SkillsChanged(Long agentId)` 를 내고, catalog 가 `@EventListener` 로 받아 비운다
- **호출 횟수는 실행 수로 센다.** `countByAgent` 는 `count(distinct u.executionId)` 다. `byUser` 는 같은 에이전트와 이름에서 같은 실행 번호를 한 번만 센다. 줄은 두 출처 모두 남긴다
- 실제 Hermes 는 바꾼 입력을 받으면 `skill_view` 를 부른다. 가짜 Hermes 도 입력에 `skill_view(name="<이름>")` 이 있으면 그 이름으로 `skill_view` 사건을 내게 해 실제와 같게 한다

## Blocked 조건

- `skill/application/SkillService` 나 `SkillUseRecorder` 가 없으면 → `PHASE_BLOCKED: 스킬 계획이 먼저 머지되어야 한다`

## 작업 항목

### 1. `chat/application/SkillCommand.java` 신규

- `public record SkillCommand(String name, String rest)`
- `public static Optional<SkillCommand> parse(String text)`: 위 판별식. `null` 이면 빈 값
- `public String hermesInput()`: 위 의도 메모의 문장

### 2. `skill/application/SkillsChanged.java`, `skill/application/SkillCommandCatalog.java` 신규

- `public record SkillsChanged(Long agentId)`
- `SkillCommandCatalog.enabledNames(CurrentUser user, Agent agent)`: `SkillService.list(user, agent.code())` 에서 `skillsToolsetEnabled` 가 true 일 때만 `enabled` 인 이름의 집합. 에이전트 번호로 30초 캐시한다. 시각은 주입한 `java.time.Clock` 으로 재서 테스트가 고정할 수 있게 한다. 저장소에 `Clock` 빈이 없으면 catalog 안에서 기본값 `Clock.systemUTC()` 를 쓰는 생성자를 따로 둔다
- `@EventListener void on(SkillsChanged event)`: 그 에이전트의 캐시를 비운다
- `SkillService`: `save`, `delete`, `toggle` 이 Hermes 반영에 성공한 뒤 `SkillsChanged(agent.id())` 를 낸다

### 3. `ChatService`

- `send`, `stream`: `route` 가 `resolveConversation` 을 부르기 전에 에이전트를 정한다. 새 대화는 `agents.requireStartable(user, agentCode)`, 기존 대화는 `access.requireOwn` 뒤 그 대화의 에이전트다. 지운 에이전트와 꺼진 에이전트 검사를 지금 순서대로 먼저 한 뒤 `SkillCommand.parse(text)` 를 본다. 커맨드이고 흐름이 없으면 이름이 `enabledNames` 에 있는지 보고, 없으면 `ApiException(SKILL_COMMAND_UNKNOWN)`. 그 뒤에 새 대화를 저장한다
- `regenerate`: `routeExisting` 뒤 `question.content()` 로 같은 판별을 한다
- 판별 결과(커맨드 또는 없음)를 `runTurn` 까지 넘긴다. `runTurn` 은 입력 조립에서 `text` 자리에 `command.hermesInput()` 을 넣고, `begin` 뒤 `skillUses.recordCommand(pending.execution().id(), command.name())` 을 부른다. 저장하는 메시지는 `text` 그대로다

### 4. 호출 횟수를 실행 수로 센다

- `ExecutionSkillUseRepository.countByAgent`: `count(distinct u.executionId)`. `max(u.occurredAt)` 은 그대로다
- `SkillUseOccurrence` 에 `executionId` 를 더하고 `findOccurrencesByUser` 가 채운다
- `SkillUsageQuery.byUser`: 묶음마다 이미 센 실행 번호를 기억해 같은 실행을 한 번만 센다
- `docs/data-schema.md` 의 `execution_skill_use` 절에 한 줄을 더한다: 「호출 횟수는 실행 수로 센다. 같은 실행에 `COMMAND` 와 `MODEL` 이 함께 있어도 1회다」

### 5. 가짜 Hermes

- `test/e2e/fake-hermes.ts`: 실행 입력에 `skill_view(name="<이름>")` 이 있으면 도구 사건 자리에서 `skill_view` 의 `tool.started`(`preview: "<이름>"`)와 `tool.completed` 를 낸다. 기존 「스킬 읽기 검사」 분기는 그대로 둔다

### 6. 이 phase 를 검증하는 테스트

- `backend/src/test/java/com/bifos/assistant/chat/application/SkillCommandTest.java` 신규
  - `/shopping 이번 주` 는 이름 `shopping`, 나머지 `이번 주`. 입력에 `skill_view(name="shopping")` 과 `이번 주` 가 있다
  - `/shopping` 만이면 나머지가 비고 입력에 「처음부터」 가 들어간다
  - `/usr/bin 은 뭐야`, `/Shopping`, `// 주석`, `/` 는 커맨드가 아니다. 이름이 65자면 커맨드가 아니다
- `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java`. 스킬 목록은 `@MockitoBean SkillService` 로 바꿔 `list` 가 돌려줄 `SkillList` 를 정한다
  - 켜진 스킬이면 가짜 런타임이 받은 입력이 바뀌고, 저장한 메시지는 원문이며, `COMMAND` 이력이 하나 생긴다
  - 없는 이름이면 `SKILL_COMMAND_UNKNOWN` 이고 새 대화에서 보냈을 때 대화도 메시지도 실행도 생기지 않는다
  - `skillsToolsetEnabled` 가 false 이면 켜진 이름이어도 `SKILL_COMMAND_UNKNOWN` 이다
  - 흐름 에이전트는 입력을 바꾸지 않고 거절하지도 않는다
  - 다시 생성도 바뀐 입력을 보내고 `COMMAND` 이력을 그 실행에 남긴다
  - 캐시: 같은 에이전트로 두 번 보내면 `list` 를 한 번만 읽는다. `SkillsChanged` 를 내면 다음 커맨드가 목록을 다시 읽어, 그사이 끈 스킬의 커맨드가 `SKILL_COMMAND_UNKNOWN` 이 된다
- `backend/src/test/java/com/bifos/assistant/skill/SkillUsageQueryTest.java`: 한 실행에 같은 이름의 `COMMAND` 와 `MODEL` 줄이 있으면 `byAgent` 와 `byUser` 가 1회를 준다. 다른 실행이면 2회다
- `backend/src/test/java/com/bifos/assistant/skill/SkillServiceTest.java`: 저장, 지우기, 켜고 끄기가 성공하면 `SkillsChanged` 를 낸다. Hermes 가 거절해 실패하면 내지 않는다
- `test/e2e/scenarios/skills.ts`: 「끄면 목록에 꺼진 것으로 보인다」 단계 **앞에** 커맨드 단계를 넣는다
  - `weekly-plan` 이 켜진 동안 `/chat/messages/stream` 으로 `/weekly-plan 이번 주` 를 보내면 답이 끝나고, `lastSubmittedInput()` 에 `skill_view(name="weekly-plan")` 이 있다. `/usage/skills` 의 `weekly-plan` 이 1회다(`COMMAND` 와 `MODEL` 줄이 함께 있어도)
  - `/chat/messages` 로 `/nope 해 줘` 를 보내면 400 이고 `code` 가 `SKILL_COMMAND_UNKNOWN` 이다. `/chat/messages/stream` 으로 보내면 첫 사건이 `error`(`code: SKILL_COMMAND_UNKNOWN`)이고 `started` 가 없다. 두 경우 모두 대화 목록 개수가 늘지 않는다
  - 완료 turn 수를 `export const SKILL_COMMAND_TURNS` 로 낸다
- `test/e2e/scenarios/usage-cost.ts`: `COMPLETED_TURNS` 에 `SKILL_COMMAND_TURNS` 를 더한다

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests 'com.bifos.assistant.chat.application.SkillCommandTest' --tests 'com.bifos.assistant.chat.ChatServiceTest' --tests 'com.bifos.assistant.skill.*'
cd backend && ./gradlew test
node test/e2e/run.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/application/SkillCommand.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillsChanged.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillCommandCatalog.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillUsageQuery.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/skill/domain/SkillUseOccurrence.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/skill/infra/ExecutionSkillUseRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/application/SkillCommandTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/skill/SkillCommandCatalogTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/skill/SkillUsageQueryTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/skill/SkillServiceTest.java` | 수정 |
| `docs/data-schema.md` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/e2e/scenarios/skills.ts` | 수정 |
| `test/e2e/scenarios/usage-cost.ts` | 수정 |
