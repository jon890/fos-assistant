# Phase 01. backend 가 스킬 커맨드를 해석하고 호출 이력을 남긴다

**Execution profile**: standard

## 목표

메시지 내용이 `/<이름>` 으로 시작하고 그 이름이 에이전트의 켜진 스킬이면, Hermes 에 보낼 입력만 「`skill_view` 로 읽고 따르라」로 바꾸고 `COMMAND` 이력을 남긴다. 없는 이름은 Hermes 에 보내지 않고 거절한다.

**범위 외**: 입력창 자동완성과 말풍선 표시(phase 02).

## 컨텍스트

**근거 문서**: `docs/adr/ADR-035-대화창의-스킬-커맨드는-control-plane-이-해석해-hermes-에-넘긴다.md`, `docs/code-architecture.md` 의 「스킬 커맨드」 절, `docs/flow.md` 의 「스킬 커맨드로 보낼 때」 절

- `chat/application/ChatService`: 일반 turn 은 `saveQuestion(user, conversation, text, attachmentIds, intent)` 뒤 `artifacts.agentPreamble(conversation) + attachments.agentInput(conversation.id(), routed.attached(), text)` 로 입력을 만들고 `begin(...)` 이 실행 줄과 `HermesRunCommand` 를 만든다. 흐름 turn 은 따로 `routed.flow().run(...)` 으로 간다. 다시 생성(`regenerate`)도 같은 입력 조립을 거친다
- 스킬 목록: 스킬 계획의 `skill/application/SkillService.list(CurrentUser, String code)`(`name`, `enabled`). 이력: `skill/application/SkillUseRecorder.recordCommand(Long executionId, String skillName)`
- 오류 코드: `ErrorCode.SKILL_NOT_FOUND`(스킬 계획이 404 로 더한다). 커맨드의 없는 이름은 400 이어야 하므로 `SKILL_COMMAND_UNKNOWN(HttpStatus.BAD_REQUEST)` 를 따로 더한다
- 가짜 Hermes: `test/e2e/fake-hermes.ts` 는 마지막 실행의 입력을 기록한다(`lastSubmitted…`). e2e 시나리오는 `test/e2e/scenarios/skills.ts`

## 의도 메모

- 판별은 `^/([a-z0-9][a-z0-9-]{0,63})(\s|$)` 다. `/usr/bin` 처럼 이름 뒤가 공백이 아니면 커맨드가 아니다
- **질문을 저장하기 전에 판별한다.** 없는 이름이면 메시지를 저장하지 않고 거절한다
- 저장하는 메시지는 사용자가 친 글 그대로다. 바꾸는 것은 Hermes 에 보내는 입력뿐이다
- 바꾼 입력: 「사용자가 `<이름>` 스킬을 호출했다. `skill_view(name="<이름>")` 로 스킬을 읽고 그 절차대로 다음을 한다: <나머지 글>」. 나머지 글이 비면 「스킬의 절차를 처음부터 진행한다」
- 흐름이 붙은 에이전트는 해석하지 않는다
- 스킬 목록은 에이전트마다 30초 캐시한다. 스킬 저장과 지우기, 켜고 끄기가 그 에이전트의 캐시를 비운다

## Blocked 조건

- `skill/application/SkillService` 나 `SkillUseRecorder` 가 없으면 → `PHASE_BLOCKED: 스킬 계획이 먼저 머지되어야 한다`

## 작업 항목

### 1. `chat/application/SkillCommand.java` 신규

- `static Optional<SkillCommand> parse(String text)`: `record SkillCommand(String name, String rest)`
- `String hermesInput()`: 위 의도 메모의 문장

### 2. `skill/application/SkillCommandCatalog.java` 신규

- `Set<String> enabledNames(CurrentUser user, Agent agent)`: `SkillService.list` 의 켜진 이름, 30초 캐시
- `void evict(Long agentId)`: `SkillService` 의 저장, 지우기, 켜고 끄기가 부른다

### 3. `ChatService`

- 일반 turn 과 다시 생성에서 `saveQuestion` 전에 `SkillCommand.parse(text)`. 커맨드이고 흐름이 없으면 이름이 `enabledNames` 에 있는지 본다. 없으면 `SKILL_COMMAND_UNKNOWN`
- 입력 조립에서 `text` 자리에 `command.hermesInput()` 을 넣는다
- `begin` 뒤 `skillUses.recordCommand(pending.execution().id(), command.name())`

### 4. 이 phase 를 검증하는 테스트

- `backend/src/test/java/com/bifos/assistant/chat/SkillCommandTest.java` 신규: `/shopping 이번 주` 는 이름 `shopping`, 나머지 `이번 주`. `/shopping` 만이면 나머지가 비고 입력에 「처음부터」 가 들어간다. `/usr/bin 은 뭐야`, `/Shopping`, `// 주석` 은 커맨드가 아니다
- `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java`: 켜진 스킬이면 Hermes 입력이 바뀌고 저장한 메시지는 원문이며 `COMMAND` 이력이 하나 생긴다. 없는 이름이면 `SKILL_COMMAND_UNKNOWN` 이고 메시지도 실행도 생기지 않는다. 흐름 에이전트는 바꾸지 않는다. 다시 생성도 같은 입력을 보낸다
- `test/e2e/scenarios/skills.ts`: 올린 스킬 `shopping` 으로 `/shopping 목록` 을 보내면 가짜 Hermes 가 받은 입력에 `skill_view(name="shopping")` 이 있고 `/usage/skills` 에 1회가 보인다. `/nope 해 줘` 는 400

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests 'com.bifos.assistant.chat.SkillCommandTest' --tests 'com.bifos.assistant.chat.ChatServiceTest'
cd backend && ./gradlew test
node test/e2e/run.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/chat/application/SkillCommand.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillCommandCatalog.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/SkillCommandTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` | 수정 |
| `test/e2e/scenarios/skills.ts` | 수정 |
