# Phase 02. 스킬 호출 이력을 남기고 보인다

**Execution profile**: deep

## 목표

모델이 `skill_view` 로 스킬을 읽으면 호출 이력(`execution_skill_use`, `MODEL`)을 남긴다.
관리하는 사람은 스킬 목록에서 스킬별 호출 수와 마지막 호출을, 각 사용자는 `GET /api/v1/usage/skills` 로 자기 호출을 본다.

**범위 외**: 커맨드 호출(`COMMAND`)을 turn 에서 기록하는 자리(스킬 커맨드 계획. 기록 메서드만 이 phase 가 만든다), 화면(phase 03, 04).

## 컨텍스트

**근거 문서**: `docs/data-schema.md` 의 「execution_skill_use」 절, `docs/code-architecture.md` 의 「스킬」 절의 「호출 이력」, `docs/adr/ADR-034-올린-스킬은-control-plane-이-버전-디렉터리에-쓰고-hermes-는-읽기만-한다.md` 의 「호출 이력을 누가 보나」, `docs/hermes/tools-and-skills.md` 의 「모델이 스킬을 읽은 것을 아는 법」

- Hermes 사건을 우리 사건으로 옮기는 곳은 `usage/application/ExecutionEventRecorder.record(AgentExecution, RunEvent, int)` 하나다. `ChatService`, `orchestration/application/AgentRunner`, `ResearchAndBuildFlow`, `memory/application/MemoryProposer` 가 모두 이것을 부른다. docs 는 `MODEL` 이력을 `usage` 가 적는다고 정한다. 그래서 기록은 이 메서드에서 부른다. 네 경로의 실행이 모두 기록된다
- `RunEvent` 의 도구 이름은 `toolName()`, 미리보기는 `detail()` 이다(`hermes/HermesRunEventStream` 이 `preview`, `detail`, `result` 순으로 읽어 채운다)
- `skill_view` 의 미리보기는 스킬 이름이거나 `이름 → 파일 경로` 다
- `ExecutionEventRecorderTest` 는 `new ExecutionEventRecorder()` 로 만든다. 생성자가 바뀌면 함께 고친다
- 실행 목록 응답은 `usage/presentation/UsageDtos.ExecutionView`, 경로는 `usage/presentation/UsageController`(`/executions` 등)
- phase 01 의 `SkillService.list` 가 스킬 목록을 주고, `SkillListItem.usage`(`SkillUsageSummary`)는 지금 늘 null 이다

## 의도 메모

- 한 실행에서 같은 스킬을 여러 번 읽어도 한 행이다
- **중복과 저장 실패는 이렇게 다룬다.** `SkillUseRecorder` 가 `TransactionTemplate` 의 `PROPAGATION_REQUIRES_NEW` 안에서 `(execution_id, skill_name, source)` 가 있는지 먼저 보고 없으면 넣는다. 동시에 넣어 `DataIntegrityViolationException` 이 나면 잡아 `debug` 로그만 남긴다. 그 밖의 예외도 잡아 `warn` 로그를 남긴다. 어느 경우도 호출한 쪽으로 던지지 않는다. 이력 저장이 실패해도 대화 turn 은 실패하지 않는다
- 이름이 규칙(`[a-z0-9][a-z0-9-]{0,63}`)에 맞지 않으면 버린다. 잘린 미리보기일 수 있다
- 관리하는 사람의 합계는 그 에이전트의 실행 전체에서 센다. 누가 불렀는지는 응답에 넣지 않는다
- `GET /api/v1/usage/skills` 는 요청자의 실행만 센다. `lastConversationId` 는 대화의 공개 식별자(UUID)다. 지운 대화면 null
- `ExecutionView.skillNames` 는 한 페이지의 실행 번호들로 한 번에 읽는다. 줄마다 질의하지 않는다

## 작업 항목

### 1. 마이그레이션 `backend/src/main/resources/db/migration/V32__execution_skill_use.sql`

번호는 V32 다. 여러 계획을 나란히 구현해 번호를 미리 나눴다. `docs/data-schema.md` 「execution_skill_use」 표 그대로. `(execution_id, skill_name, source)` 유일 제약과 `skill_name` 조회용 인덱스. MySQL 과 H2 에 함께 있는 문법만 쓴다(`backend/AGENTS.md`).

### 2. 저장과 기록

- `skill/domain/ExecutionSkillUse.java`, `skill/domain/SkillUseSource.java`(`COMMAND`, `MODEL`), `skill/infra/ExecutionSkillUseRepository.java`
- `skill/application/SkillUseRecorder.java`: `void recordModel(Long executionId, String preview)`, `void recordCommand(Long executionId, String skillName)`. 이름은 미리보기에서 ` → ` 앞까지 자르고 양끝 공백을 뺀 뒤 규칙을 본다
- `ExecutionEventRecorder.record(AgentExecution, RunEvent, int)`: 옮긴 사건이 `TOOL_STARTED` 이고 도구 이름이 `skill_view` 면 `SkillUseRecorder.recordModel(execution.id(), event.detail())`. 클래스 설명의 「저장하지 않는다」 문단에 이 예외를 적는다

### 3. 조회

- `skill/application/SkillUsageQuery.java`, 결과 타입은 파일을 따로 둔다
  - `Map<String, SkillUsageSummary> byAgent(Long agentId)`: 그 에이전트의 실행 전체에서 스킬 이름별 `count`, `lastInvokedAt`
  - `List<UserSkillUsage> byUser(Long userId)`: `UserSkillUsage(String agentCode, String agentName, String skillName, long count, Instant lastInvokedAt, UUID lastConversationId)`. 에이전트와 스킬 이름으로 묶는다. 마지막 호출 시각의 실행이 속한 대화의 공개 식별자를 `lastConversationId` 로 준다
  - `Map<Long, List<String>> skillNamesByExecution(Collection<Long> executionIds)`
- `SkillService.list`: 편집자에게만 스킬마다 `usage` 를 채운다. 호출 이력이 없는 스킬은 `count` 0, `lastInvokedAt` null
- `usage/presentation/UsageController`: `GET /api/v1/usage/skills` 가 `docs/code-architecture.md` 「호출 이력」 의 모양을 돌려준다. `ExecutionView` 에 `skillNames`(그 실행에서 쓴 스킬 이름 목록, 없으면 빈 목록)를 더한다

### 4. 이 phase 를 검증하는 테스트

- `backend/src/test/java/com/bifos/assistant/skill/SkillUseRecorderTest.java` 신규: `이름`, `이름 → references/a.md` 는 기록되고 규칙에 맞지 않는 미리보기(빈 값, 대문자, 65자)는 버린다. 같은 실행에서 두 번 읽어도 한 행이다. 저장소가 예외를 던져도 `recordModel` 은 던지지 않는다
- `backend/src/test/java/com/bifos/assistant/skill/SkillUsageQueryTest.java` 신규: 가족용 에이전트를 두 사용자가 쓸 때 편집자 합계는 둘을 합치고, 각 사용자의 `/usage/skills` 는 자기 호출만 보인다. 편집자가 아닌 사용자의 스킬 목록에는 `usage` 가 없다. 지운 대화의 `lastConversationId` 는 null 이다
- `backend/src/test/java/com/bifos/assistant/usage/ExecutionEventRecorderTest.java`: 생성자를 맞추고, `skill_view` 의 `tool.started` 에서만 기록을 부르는 사례를 더한다
- `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java`: 스트림에서 `skill_view` 도구 사건(미리보기 `shopping`)이 오면 그 실행에 `MODEL` 이력이 하나 생기는 사례를 더한다
- `test/e2e/fake-hermes.ts`: 입력이 `스킬 읽기 검사` 면 `skill_view` 도구 사건(미리보기 `shopping`)을 흘린다. `test/e2e/scenarios/skills.ts` 에 그 대화 뒤 `/usage/skills` 에 `shopping` 이 1회로 보이고, `/usage/executions` 의 그 실행 줄 `skillNames` 에 `shopping` 이 있는 검사를 더한다

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test --tests 'com.bifos.assistant.skill.*'
cd backend && ./gradlew test
node test/e2e/run.ts
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V32__execution_skill_use.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/domain/ExecutionSkillUse.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/domain/SkillUseSource.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/infra/ExecutionSkillUseRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillUseRecorder.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillUsageQuery.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/application/UserSkillUsage.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/application/ExecutionEventRecorder.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/presentation/UsageController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/presentation/UsageDtos.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/skill/SkillUseRecorderTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/skill/SkillUsageQueryTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/usage/ExecutionEventRecorderTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/e2e/scenarios/skills.ts` | 수정 |
