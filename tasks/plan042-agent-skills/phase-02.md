# Phase 02. 스킬 호출 이력을 남기고 보인다

**Execution profile**: standard

## 목표

모델이 `skill_view` 로 스킬을 읽으면 호출 이력(`execution_skill_use`, `MODEL`)을 남긴다.
관리하는 사람은 스킬 목록에서 스킬별 호출 수와 마지막 호출을, 각 사용자는 `GET /api/v1/usage/skills` 로 자기 호출을 본다.

**범위 외**: 커맨드 호출(`COMMAND`) 기록(스킬 커맨드 계획), 화면(phase 03).

## 컨텍스트

**근거 문서**: `docs/data-schema.md` 의 「execution_skill_use」 절, `docs/code-architecture.md` 의 「스킬」 절의 「호출 이력」, `docs/adr/ADR-034-올린-스킬은-control-plane-이-버전-디렉터리에-쓰고-hermes-는-읽기만-한다.md` 의 「호출 이력을 누가 보나」, `docs/hermes/tools-and-skills.md` 의 「모델이 스킬을 읽은 것을 아는 법」

- 대화 스트림 사건은 `chat/application/ChatService` 가 `eventRecorder.record(pending.execution(), event, sequence)` 로 만들어 `executionEvents.save(event)` 로 저장한다. `RunEvent` 에 도구 이름과 미리보기가 있다(`hermes/HermesRunEventStream` 가 `tool`, `tool_name` … 과 `preview` 에서 읽는다)
- `skill_view` 의 미리보기는 스킬 이름이거나 `이름 → 파일 경로` 다
- 실행 목록 응답은 `usage/presentation/UsageDtos.ExecutionView`, 경로는 `usage/presentation/UsageController`(`/executions` 등)
- phase 01 의 `SkillService.list` 가 스킬 목록을 준다

## 의도 메모

- 한 실행에서 같은 스킬을 여러 번 읽어도 한 행이다. 유일 제약에 걸리면 조용히 넘긴다
- 이름이 규칙(`[a-z0-9][a-z0-9-]{0,63}`)에 맞지 않으면 버린다. 잘린 미리보기일 수 있다
- 이력 저장이 실패해도 대화 turn 은 실패시키지 않는다. 로그만 남긴다
- 관리하는 사람의 합계는 그 에이전트의 실행 전체에서 센다. 누가 불렀는지는 응답에 넣지 않는다
- `GET /api/v1/usage/skills` 는 요청자의 실행만 센다. `lastConversationId` 는 대화의 공개 식별자(UUID)다. 지운 대화면 null

## 작업 항목

### 1. 마이그레이션 `backend/src/main/resources/db/migration/V31__execution_skill_use.sql`

`db/migration` 의 가장 큰 번호 다음 번호로 만든다(계획 순서대로면 V31). `docs/data-schema.md` 「execution_skill_use」 표 그대로. `(execution_id, skill_name, source)` 유일 제약과 `skill_name` 조회용 인덱스.

### 2. 저장과 기록

- `skill/domain/ExecutionSkillUse.java`, `skill/domain/SkillUseSource.java`(`COMMAND`, `MODEL`), `skill/infra/ExecutionSkillUseRepository.java`
- `skill/application/SkillUseRecorder.java`: `void recordModel(Long executionId, String preview)`, `void recordCommand(Long executionId, String skillName)`. 이름은 미리보기에서 ` → ` 앞까지 자르고 규칙을 본다
- `ChatService`: 스트림 사건을 저장한 뒤 그 사건이 `TOOL_STARTED` 이고 도구 이름이 `skill_view` 면 `recordModel`

### 3. 조회

- `skill/application/SkillUsageQuery.java`: 에이전트별 스킬 합계(`count`, `lastInvokedAt`), 사용자별 호출 목록(에이전트, 스킬, 횟수, 마지막 시각, 마지막 대화)
- `SkillService.list`: 편집자에게만 스킬마다 `usage` 를 채운다
- `usage/presentation/UsageController`: `GET /api/v1/usage/skills`. `ExecutionView` 에 `skillNames`(그 실행에서 쓴 스킬 이름 목록)를 더한다

### 4. 이 phase 를 검증하는 테스트

- `backend/src/test/java/com/bifos/assistant/skill/SkillUseRecorderTest.java` 신규: `이름`, `이름 → references/a.md` 는 기록되고 규칙에 맞지 않는 미리보기는 버린다. 같은 실행에서 두 번 읽어도 한 행이다
- `backend/src/test/java/com/bifos/assistant/skill/SkillUsageQueryTest.java` 신규: 가족용 에이전트를 두 사용자가 쓸 때 편집자 합계는 둘을 합치고, 각 사용자의 `/usage/skills` 는 자기 호출만 보인다. 편집자가 아닌 사용자의 스킬 목록에는 `usage` 가 없다
- `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java`: 스트림에서 `skill_view` 도구 사건(미리보기 `shopping`)이 오면 그 실행에 `MODEL` 이력이 하나 생기는 사례를 더한다
- `test/e2e/fake-hermes.ts`: 입력이 `스킬 읽기 검사` 면 `skill_view` 도구 사건(미리보기 `shopping`)을 흘린다. `test/e2e/scenarios/skills.ts` 에 그 대화 뒤 `/usage/skills` 에 `shopping` 이 1회로 보이는 검사를 더한다

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
| `backend/src/main/resources/db/migration/V31__execution_skill_use.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/domain/ExecutionSkillUse.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/domain/SkillUseSource.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/infra/ExecutionSkillUseRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillUseRecorder.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillUsageQuery.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ChatService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/presentation/UsageController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/presentation/UsageDtos.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/skill/SkillUseRecorderTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/skill/SkillUsageQueryTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/e2e/scenarios/skills.ts` | 수정 |
