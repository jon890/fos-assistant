# Phase 02. 요약 구역 조립과 관리자 예산 설정

**Execution profile**: deep

## 목표

매 실행에 요약 줄을 요약 구역으로 넣고, 예산과 기억 전체 상한을 그룹 설정에서 읽는다. 관리자가 그 값을 API 로 바꾼다. 기존 테스트와 회수 측정이 새 구조에서 통과한다.

**범위 외**: 도구 인자(phase 03), 요약 이관과 새 평가 사례(phase 04), 화면(PR2).

## 컨텍스트

**요구사항**: REQ2, REQ3, REQ4

**근거 문서**: `docs/features/memory/spec.md`, `docs/features/memory/design.md` 의 「조립(PR1)」, 「그룹 설정 `MEMORY_CONTEXT`」, 「관리자 API(PR1)」

- 조립은 `backend/src/main/java/com/bifos/assistant/context/application/ContextAssembler.java` 의 `assemble`, `appendFacts`, `factCandidate`, `chooseFacts`, `appendIndex` 다
- 질문 관련 구역은 `context/application/MemorySearchSection.place` 와 `context/application/dto/MemorySearchPlacement` 다
- 설정 record 는 `context/config/ContextProperties.java`, 값은 `application.yml` 과 `src/test/resources/application-test.yml` 의 `assistant.context` 다
- 그룹 설정 읽기와 쓰기는 `user/application/GroupSettingStore.java`, 키 상수는 `user/domain/GroupSetting.java` 다. `agent/application/ToolsetVisibilityService.java` 가 같은 틀이다
- 관리자 API 는 `admin/memory/presentation/AgentMemorySettingController.java` 옆에 둔다. 권한 검사와 응답 모양은 그 컨트롤러와 `AgentMemorySettingTest` 를 따른다
- 테스트에서 조립기를 만드는 자리는 `testsupport/TestContextAssemblers.java` 다

## 의도 메모

- `context` 가 `user` 를 import 하지 않는다. 예산은 `memory` 모듈의 함수형 인터페이스 `MemoryContextBudgets`(`budgetFor(Long groupId)` → `MemoryContextBudget(summaryMaxChars, maxChars)`)로 받는다. 운영 구현은 `MemoryContextSettings` 다
- 테스트는 이 인터페이스를 람다로 넘긴다. `MemoryContextBudget` record 는 0 이상이면 받는다. 범위 검사(1,000 이상 등)는 관리자 저장에서만 한다. 그래서 회수 측정의 `factsOff`(요약 예산 0)와 짧은 상한(250자 등)을 쓰는 기존 테스트가 그대로 돈다
- 깨진 설정 줄은 기본값으로 돌고 경고 로그를 남긴다(스펙 DECISION19)

## 작업 항목

### 1. 예산 설정(memory 모듈)

- `MemoryContextBudget`, `MemoryContextBudgets`, `MemoryContextSettings` 를 만든다. `MEMORY_CONTEXT` 줄 `{"version":1,"summaryMaxChars":...,"maxChars":...}` 을 읽고, 없거나 깨졌으면 기본값 4,000 과 10,000 이다
- 저장 검사: 요약은 1,000부터 20,000까지, 전체는 4,000부터 40,000까지, 요약 ≤ 전체 - 전체 / `index-budget-ratio`. 저장은 `readForUpdate` 뒤 `write`
- `GroupSetting` 에 키 상수 `MEMORY_CONTEXT` 를 더하고 `backend/docs/data-schema.md` 의 group_setting 절에 그 값을 적는다

### 2. 관리자 API

`GET`, `PUT /api/v1/admin/memory-settings`. `ADMIN` 만, 아니면 `FORBIDDEN`. 범위 밖은 `VALIDATION_FAILED`. 응답은 지금 값, 기본값, 허용 범위.

### 3. 조립

- `ContextProperties` 에서 `maxChars`, `factsMaxChars`, `factsItemMaxChars` 를 지우고 두 yml 의 키도 지운다
- `ContextAssembler` 는 `MemoryContextBudgets` 를 받고 요청자 그룹의 예산을 쓴다. 그룹이 없으면 기본값이다
- 요약 구역 후보: 색인 후보 가운데 `USER`, `MEMORY`, 풀림, `hasSummary()`. 한 줄은 `- [번호] 제목: 요약`(`MemoryItems.oneLine`). 항목 body 는 요약이다. 규칙 이름 `facts-recent-v2`
- 질문 관련 구역 후보에서 요약 기억을 빼지 않는다. 요약 구역에 든 번호는 색인에 다시 놓지 않는다(`place` 에 그 번호 집합을 넘긴다)
- 넘친 요약은 색인 줄로 가고 선택 근거 `summary-over-budget-v1` 을 붙인다. 넘친 번호 집합을 `MemorySearchPlacement` 까지 넘겨 `appendIndex` 가 점수 근거로 덮지 않게 한다. `ContextSelection` Javadoc 목록에 두 이름을 더한다
- 요약 구역 머리 글 `FACTS_HEADER` 를 설계 「지침(PR1)」 대로 바꾼다

### 4. 기존 테스트 옮기기

- `new ContextProperties(...)` 를 부르는 `ContextAssemblerTest`, `ContextAssemblerQuestionTest`, `ContextAssemblerHybridTest`, `MemoryFreshnessTest`, `ContextPropertiesTest`, `McpMemorySearchToolTest`, `MemoryRecallEvalTest`, `MemoryEmbeddingEvalTest`, `MemoryJudgeEvalTest`, `TestContextAssemblers` 를 새 생성자와 예산 람다로 옮긴다
- 짧은 기억이 `MEMORY_FACTS` 로 실리기를 기대하던 테스트(`ChatServiceTest` 의 개인 사실 검사, `ContextAssemblerQuestionTest` 의 해당 검사)는 그 기억에 요약을 적어 같은 뜻을 지킨다
- `MemoryEvalDataset` 이 항목의 `summary` 를 받고, `MemoryEvalFixture` 가 요약이 없는 항목은 본문 200자 이하일 때 요약 = 본문으로 넣는다(이관 규칙과 같다). 회수 측정 기준 파일 `memory-eval/baseline.json` 을 다시 만들고, 이전 값과 새 값을 PR 본문에 나란히 적는다

### 5. 테스트

- `ContextAssemblerTest`: 요약 줄 모양, 요약 없는 짧은 기억은 색인, 받지 않는 collection 제외, `ALWAYS`·`ARCHIVE` 기억은 요약 구역에 없다, 질문에 맞는 요약 기억의 상세가 질문 관련 구역에 함께 든다, 예산 넘침의 건너뛰기와 `summary-over-budget-v1`(질문이 있어도 유지), 그룹 설정을 바꾸면 다음 조립의 예산이 바뀐다
- `MemorySettingAdminTest`(새 파일, `memory` 테스트 폴더): 권한, 범위 밖 거절, 요약 > 전체의 3/4 거절, 줄 없는 그룹의 기본값, 깨진 JSON 의 기본값

## 검증

```bash
cd backend && HEAVY_SLOTS=2 orch slot heavy -- ./gradlew test --tests '*ContextAssembler*' --tests '*ContextPropertiesTest' --tests '*MemoryFreshnessTest' --tests '*McpMemorySearchToolTest' --tests '*MemorySettingAdminTest' --tests '*MemoryRecallEvalTest' --tests '*ChatServiceTest'
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck archTest
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryContextSettings.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryContextBudgets.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/memory/application/dto/MemoryContextBudget.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/user/domain/GroupSetting.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/admin/memory/presentation/MemorySettingAdminController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/admin/memory/presentation/MemoryAdminDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/context/application/ContextAssembler.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/context/application/MemorySearchSection.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/context/application/dto/MemorySearchPlacement.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/context/application/dto/ContextSelection.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/context/config/ContextProperties.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/resources/application-test.yml` | 수정 |
| `backend/docs/data-schema.md` | 수정 |
| `backend/src/test/java/com/bifos/assistant/testsupport/TestContextAssemblers.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/ContextAssemblerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/application/ContextAssemblerQuestionTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/ContextAssemblerHybridTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/MemoryFreshnessTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/ContextPropertiesTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpMemorySearchToolTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ChatServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryRecallEvalTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryEmbeddingEvalTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryJudgeEvalTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryEvalDataset.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/eval/MemoryEvalFixture.java` | 수정 |
| `backend/src/test/resources/memory-eval/baseline.json` | 수정 |
| `backend/src/test/java/com/bifos/assistant/memory/MemorySettingAdminTest.java` | 신규 |
