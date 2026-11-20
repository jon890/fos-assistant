# Phase 02. 요약 구역 조립과 관리자 예산 설정

**Execution profile**: standard

## 목표

매 실행에 요약 줄을 요약 구역으로 넣고, 예산과 기억 전체 상한을 그룹 설정에서 읽는다. 관리자가 그 값을 API 로 바꾼다.

**범위 외**: 도구 인자(phase 03), 평가 사례와 이관(phase 04), 화면(PR2).

## 컨텍스트

**요구사항**: REQ2, REQ3, REQ4

**근거 문서**: `docs/features/memory/spec.md`, `docs/features/memory/design.md` 의 「조립(PR1)」, 「그룹 설정 `MEMORY_CONTEXT`」, 「관리자 API(PR1)」

- 조립은 `backend/src/main/java/com/bifos/assistant/context/application/ContextAssembler.java` 의 `assemble`, `appendFacts`, `factCandidate`, `chooseFacts`, `appendIndex` 다
- 질문 관련 구역은 `MemorySearchSection.place` 와 `MemorySearchPlacement` 다
- 설정 record 는 `context/config/ContextProperties.java`, 값은 `application.yml` 과 `src/test/resources/application-test.yml` 의 `assistant.context` 다
- 그룹 설정 읽기와 쓰기는 `user/application/GroupSettingStore.java`, 키 상수는 `user/domain/GroupSetting.java`. 도구 숨김의 `agent/application/ToolsetVisibilityService.java` 가 같은 틀이다
- 관리자 API 는 `admin/memory/presentation/AgentMemorySettingController.java` 옆에 둔다

## 의도 메모

- `context` 가 `user` 를 import 하지 않는다. 예산은 `MemoryService`(또는 `memory` 의 `MemoryContextSettings`)를 거쳐 받는다
- 회수 측정의 `factsOff` 모드가 예산 0 을 쓸 수 있도록, 예산을 정하는 자리를 테스트가 바꿀 수 있게 둔다(spy 나 `@OverrideProperties` 가 아닌 빈 하나)
- 깨진 설정 줄은 기본값으로 돌고 경고 로그를 남긴다(스펙 DECISION19)

## 작업 항목

### 1. `MemoryContextSettings`(memory 모듈)

`MEMORY_CONTEXT` 줄을 읽어 `summaryMaxChars`, `maxChars` 를 낸다. 기본 4,000, 10,000. 저장 검사: 요약은 1,000부터 20,000까지, 전체는 4,000부터 40,000까지, 요약 ≤ 전체 - 전체 / `index-budget-ratio`. 저장은 `readForUpdate` 뒤 `write`. 모르는 버전과 깨진 JSON 은 기본값과 경고.

### 2. 관리자 API

`GET`, `PUT /api/v1/admin/memory-settings`. `ADMIN` 만, 아니면 `FORBIDDEN`. 범위 밖은 `VALIDATION_FAILED`. 응답은 지금 값, 기본값, 허용 범위.

### 3. 조립

- `ContextProperties` 에서 `maxChars`, `factsMaxChars`, `factsItemMaxChars` 를 지우고 `application.yml`, `application-test.yml` 의 키도 지운다. `ContextPropertiesTest`, `ContextAssemblerTest`, `McpMemorySearchToolTest`, `MemoryRecallEvalTest` 의 사용처를 새 seam 으로 옮긴다
- 요약 구역 후보: 색인 후보 가운데 `USER`, `MEMORY`, 풀림, `hasSummary()`. 한 줄은 `- [번호] 제목: 요약`(`MemoryItems.oneLine`). 항목 body 는 요약. 규칙 이름 `facts-recent-v2`
- 질문 관련 구역 후보에서 요약 기억을 빼지 않는다. 요약 구역에 든 번호는 색인에 다시 놓지 않는다
- 넘친 요약은 색인 줄로 가고 선택 근거 `summary-over-budget-v1` 을 붙인다. 질문 점수 근거가 있어도 이 근거가 앞선다. `ContextSelection` 의 Javadoc 목록에 두 이름을 더한다
- 요약 구역 머리 글을 바꾼다(design 「지침(PR1)」). 기억 지침 문구는 phase 03 이 바꾼다

### 4. 테스트

- `ContextAssemblerTest`: 요약 줄 모양, 요약 없는 짧은 기억은 색인, collection 필터링, `ALWAYS`/`ARCHIVE` 기억은 요약 구역에 없다, 질문에 맞는 요약 기억의 상세가 질문 관련 구역에 함께 든다, 예산 넘침의 건너뛰기와 `summary-over-budget-v1`, 바꾼 그룹 설정이 다음 조립에 쓰인다
- 관리자 API 테스트(`admin` 쪽 기존 테스트 자리): 권한, 범위, 줄 없는 그룹의 기본값, 깨진 JSON 의 기본값

## 검증

```bash
cd backend && HEAVY_SLOTS=2 orch slot heavy -- ./gradlew test --tests '*ContextAssembler*' --tests '*ContextPropertiesTest' --tests '*McpMemorySearchToolTest' --tests '*MemorySettingAdmin*' --tests '*MemoryRecallEvalTest'
cd backend && ./gradlew checkstyleMain checkstyleTest spotlessCheck archTest
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/memory/application/MemoryContextSettings.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/user/domain/GroupSetting.java` | 수정 (키 상수) |
| `backend/src/main/java/com/bifos/assistant/admin/memory/presentation/MemorySettingAdminController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/admin/memory/presentation/MemoryAdminDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/context/application/ContextAssembler.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/context/application/MemorySearchSection.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/context/application/dto/MemorySearchPlacement.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/context/application/dto/ContextSelection.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/context/config/ContextProperties.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/resources/application-test.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/ContextAssemblerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/context/ContextPropertiesTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/admin/memory/MemorySettingAdminTest.java` | 신규 |
