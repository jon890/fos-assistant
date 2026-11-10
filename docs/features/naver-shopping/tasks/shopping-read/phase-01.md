# Phase 01. 사람이 없는 실행이 attended_only 도구를 부르지 못하게 한다

**Execution profile**: standard

## 목표

도구 선언의 범용 칸 `attended_only` 를 대시보드 plugin 부터 Control Plane 판정까지 잇고, turn 을 열 때 실행 줄에 사람이 없는 실행 표식을 남겨 판정이 그 트리의 호출을 거절한다.

**범위 외**: 쇼핑 플러그인 자체(phase 2, 3). 금융 준비 가로채기 앞으로 옮기는 일(PR3).

## 컨텍스트

**요구사항**: REQ6

**근거 문서**: `docs/features/naver-shopping/spec.md`(REQ6, DECISION13, DECISION32), `docs/features/naver-shopping/design.md` 의 「`attended_only`(PR1, REQ6)」 와 「사람이 없는 실행의 표식」

- 도구 칸의 길은 `conversation_grant` 와 같다: `hermes/plugins/dashboard-profile-api/connector_policy.py` → 카탈로그 → `backend/src/main/java/com/bifos/assistant/hermes/infra/ConnectorCatalogTools.java` → `hermes/application/dto/ConnectorTool.java` → `connector/connection/application/ConnectorToolPolicies.java` → `connector/connection/domain/ToolPolicy.java` → `connector/action/domain/ToolPolicyDecision.decide`
- 판정 호출은 `connector/approval/application/ConnectorPolicyService.java` 가 `checkBoundary(origin)` 옆에서 한다
- 실행 줄은 `usage/execution/domain/AgentExecution.java`, 만드는 곳은 `chat/turn/application/ChatTurnLifecycle.begin`(의도 `TurnIntent`)
- connector 는 `task` 를 import 하지 않는다(최상위 패키지 순환 검사)

## 의도 메모

- `task_run.execution_id` 는 turn 이 끝날 때만 채워져 쓰지 않는다
- 칸이 있는데 boolean 이 아니면 참으로 읽는다(`outbound` 와 같은 닫힌 쪽)
- 마이그레이션은 `orch queue reserve` 로 예약한 `V20261105074402__agent_execution_unattended.sql` 을 쓴다. 기준 브랜치를 합친 뒤 다시 예약한다

- Gradle 은 같은 맥의 다른 worker 와 겹치지 않게 공통 지시서의 무거운 검사 대기열(`orch slot heavy --`)로 감싸 돌린다

## 작업 항목

### 1. 대시보드 plugin 의 칸 검증

`connector_policy.py` 의 허용 도구 키에 `attended_only` 를 더하고 boolean 이 아니면 `ValueError`. 정책 dict 와 카탈로그 출력에 싣는다. 테스트은 `hermes/tests/` 의 정책 검증 테스트 옆에 둔다(참, 거짓, 문자열 거절, 없음은 거짓).

### 2. Control Plane 이 칸을 읽는다

`ConnectorCatalogTools` 에 `attendedOnly(JsonNode)`(없으면 null, boolean 이 아니면 참), `ConnectorTool` 에 칸, `ToolPolicy` 에 `attendedOnly` 와 기존 생성자 호환, `ConnectorToolPolicies` 가 옮긴다.

### 3. 실행 줄의 표식과 마이그레이션

`agent_execution.unattended`(boolean, not null, 기본 거짓) 컬럼을 예약한 마이그레이션의 `ADD COLUMN ... COMMENT` 로 더한다. 이미 적용된 `db/comments/*.tsv` 는 Flyway 체크섬에 들어가 고치지 않는다. `TurnIntent.unattended(intent)` 가 `Scheduled`, `ProactiveCheck`, `DelegationResults.fromUnattended` 를 참으로 보고, `ChatTurnLifecycle.begin` 이 실행 줄을 만든 직후 `ExecutionRecorder.markUnattended` 로 적는다. `fromUnattended` 는 `ChatDeliveryTurns.runDelegationResults` 가 전할 위임 결과의 트리 루트로 계산한다(`AgentExecutionRepository.existsByIdInAndUnattendedTrue`). 승인 결과(extras)는 세지 않고, 다시 전달(`retryDelivery`)은 거짓이다.

### 4. 판정

가장 긴 `ToolPolicyDecision.decide` 오버로드에 `boolean unattended` 를 더한 오버로드를 두고, 기존 오버로드는 거짓을 넘긴다(다른 호출자 `ConnectorActionApproval`, `ContentDraftSaveApproval` 은 바꾸지 않는다). `RISK_NOT_OPEN` 뒤, `READ_ONLY_RUN` 앞에서 `attendedOnly` 도구를 `ATTENDED_ONLY` 로 거절한다. `ActionDenyReason` 에 값, `deny_reason` 설명은 같은 마이그레이션의 `MODIFY COLUMN ... COMMENT`. `ConnectorPolicyService` 의 거절 글 「사람이 연 대화에서만 쓸 수 있는 도구입니다.」. 루트 줄은 이미 주입된 `SessionOwnerResolver` 에 `unattendedTree(AgentExecution)` 을 더해 읽는다(루트가 없으면 참).

### 5. 테스트

- `ToolPolicyDecisionTest`: 사람 없음 × `attendedOnly` × 위험도 × 경계 표와 순서
- `ChatTurnQuestionTest`: `TurnIntent.unattended` 의 의도별 값(승인 결과만 전하는 turn 은 거짓)
- `TaskRunStarterTest`: 예약 작업 turn 이 도는 동안 `SessionOwnerResolver.unattendedTree` 가 참이고 `task_run.execution_id` 는 아직 비었다(표식을 fixture 로 미리 채우지 않는다)
- `DelegationWakeServiceTest`: 사람이 연 트리의 결과를 전한 turn 은 거짓, 사람이 없는 트리의 결과를 전한 turn 은 참
- 대시보드 `hermes/tests/test_connector_manifest.py`: 칸이 참일 때만 카탈로그에 싣고, boolean 이 아니면 플러그인을 뺀다

## 검증

```bash
cd backend && ./gradlew test --tests ToolPolicyDecisionTest --tests 'com.bifos.assistant.chat.turn.application.ChatTurnQuestionTest' --tests 'com.bifos.assistant.chat.DelegationWakeServiceTest' --tests 'com.bifos.assistant.task.TaskRunStarterTest' --tests '*ConnectorCatalog*' --tests '*ArchitectureRulesTest'
python3 -m unittest discover -s hermes/tests -p 'test_connector_manifest.py'
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/dashboard-profile-api/connector_policy.py` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/infra/ConnectorCatalogTools.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/application/dto/ConnectorTool.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/domain/ToolPolicy.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/ConnectorToolPolicies.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/action/domain/ToolPolicyDecision.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/action/domain/type/ActionDenyReason.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/approval/application/ConnectorPolicyService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/execution/domain/AgentExecution.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/application/ChatTurnLifecycle.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/application/ChatDeliveryTurns.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/turn/application/dto/TurnIntent.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/execution/application/ExecutionRecorder.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/execution/infra/AgentExecutionRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/SessionOwnerResolver.java` | 수정 |
| `backend/src/main/resources/db/migration/V20261105074402__agent_execution_unattended.sql` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/ToolPolicyDecisionTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/task/TaskRunStarterTest.java` | 수정 (사람이 없는 실행 거절 통합 테스트) |
| `hermes/tests/test_connector_manifest.py` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/turn/application/ChatTurnQuestionTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/DelegationWakeServiceTest.java` | 수정 |
