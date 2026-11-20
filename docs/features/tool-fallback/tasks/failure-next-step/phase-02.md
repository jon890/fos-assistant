# Phase 02. 플러그인 호출을 막는 판정 글이 끝 줄 하나로 끝난다

**Execution profile**: standard

## 목표

Control Plane 이 플러그인 도구 호출을 막고 fos-ctx 에 돌려주는 판정 글이 「다음 방법: …」 이나 「사용자에게 바로 알린다.」 로 끝나게 한다. backend 의 실패 글 전체를 모아 끝 줄을 보는 테스트 하나를 둔다.

**범위 외**: 판정 응답의 JSON 모양과 `ConnectorPolicyAnswer` 등 응답 record 의 칸. `[승인 대기]` 로 시작하는 글(`ConnectorApprovalReply.APPROVAL_MESSAGE`, `ConnectorFinancialProducer.APPROVAL_PENDING_MESSAGE`, `ConnectorFinancialIntake.INTENT_PENDING_MESSAGE`)은 실패가 아니라 그대로 둔다. 승인한 실행의 복구 안내(`ConnectorActionResultSource`, ADR-092)도 그대로다.

## 컨텍스트

phase 01 이 `shared/util/ToolFailureText` 를 만들었다. `alternative(failure, next)`, `askUser(failure)`, `text()`, `endLine()`, `NEXT_PREFIX`, `ASK_USER` 가 있다.
판정 글은 `String` 상수로 있고 `new ConnectorPolicyAnswer(false, <글>, …)`, `ToolGuardResponse.block(<글>)`, `EventToolGuard.blocked(profile, <글>, 로그 까닭)` 에 들어간다. fos-ctx 는 그 글을 그대로 모델에게 돌려준다(`hermes/plugins/fos-ctx/connector_policy.py`, `tool_guard.py`).
`blockedWithoutRecord(<로그 까닭>)` 의 인자는 로그용이라 바꾸지 않는다. 모델에게 가는 글은 그 안의 `CONTEXT_MESSAGE` 다.

**요구사항**: REQ2, REQ3

**근거 문서**: `docs/features/tool-fallback/spec.md` 의 REQ2, REQ3, DECISION2, DECISION9, DECISION10, `docs/features/tool-fallback/design.md` 의 「바꾸는 실패 글」 의 `ConnectorPolicyService`, `ConnectorFinancialIntake`, `ConnectorFinancialProducer`, `ConnectorApprovalReply`, `ToolGuardController`, `EventToolGuard` 행과 DECISION2

## 의도 메모

- 상수를 `ToolFailureText` 로 바꾸고 응답을 만들 때 `.text()` 로 꺼낸다. 응답 record 를 `ToolFailureText` 로 바꾸지 않는다. 승인 대기 글도 같은 칸에 들어가기 때문이다
- 지금 글의 「다시 부르지 않는다」, 「외부 서비스 연결에서 연결을 확인하라고 알린다」 같은 안내는 지우지 않고 끝 줄 앞에 둔다(스펙 REQ2)
- 보안·정책 막힘(허락 안 된 도구, 아직 열리지 않은 도구)은 다른 도구로 같은 대상을 노리게 하는 다음 방법을 쓰지 않는다(스펙 DECISION9)

## 작업 항목

### 1. `connector/approval/application/ConnectorPolicyService.java`

이 파일은 496줄이고 파일 길이 상한은 500줄이다(`scripts/checks/check-file-length.mjs`). 상수는 한 줄씩 유지하고, 넘으면 글 상수를 같은 패키지의 작은 클래스로 옮긴다.


- `POLICY_UNAVAILABLE_MESSAGE`: `alternative(지금 글에서 「잠시 뒤 다시 시도하라고 사용자에게 알린다」 를 뺀 앞부분, "다른 도구가 있으면 그것으로 해 보고, 없으면 잠시 뒤 다시 해 달라고 알린다")`(스펙 DECISION10)
- `ARGS_TOO_LARGE_MESSAGE`: `alternative("인자가 너무 커서 실행하지 않았다.", "인자를 나눠서 다시 부른다")`
- `CONTEXT_MESSAGE`, `NOT_READY_MESSAGE`, `BINDING_PENDING_MESSAGE`, `BINDING_RESTART_MESSAGE`, `UNDECLARED_MESSAGE`, `RISK_NOT_OPEN_MESSAGE`, `READ_ONLY_RUN_MESSAGE`, `ATTENDED_ONLY_MESSAGE`: `askUser(지금 글)`
- `message(ActionDenyReason)` 과 바인딩 글을 고르는 메서드가 `ToolFailureText` 를 돌려주고, `ConnectorPolicyAnswer` 를 만드는 곳에서 `.text()` 를 쓴다

### 2. `ConnectorFinancialIntake.java`, `ConnectorFinancialProducer.java`, `ConnectorApprovalReply.java`

- `FINANCIAL_UNAVAILABLE_MESSAGE`, `INTENT_PENDING_ELSEWHERE_MESSAGE`, `INTENT_BUSY_MESSAGE`: `askUser(지금 글)`
- `limitMessage`, `purchaseLimitMessage` 는 `ToolFailureText` 를 돌려주고 `askUser` 로 만든다(결제와 구매 상한)
- `rejectedMessage` 는 `INTENT_PENDING_MESSAGE`(승인 대기)와 섞인 `switch` 라 `String` 을 돌려주고, 실패 갈래만 `.text()` 로 꺼낸다
- `ConnectorFinancialProducer.APPROVAL_WITHOUT_CONVERSATION_MESSAGE`, `APPROVAL_SETTLED_MESSAGE`: `askUser(지금 글)`. `APPROVAL_PENDING_MESSAGE` 는 그대로
- `ConnectorApprovalReply.APPROVAL_WITHOUT_CONVERSATION_MESSAGE`: `%s` 를 담은 `askUser(지금 글)` 의 `ToolFailureText` 상수로 두고, 쓸 때 `String.format(APPROVAL_WITHOUT_CONVERSATION_MESSAGE.text(), id)` 로 만든다. `APPROVAL_MESSAGE` 는 그대로

### 3. `mcp/presentation/ToolGuardController.java`, `orchestration/application/EventToolGuard.java`

- `ToolGuardController.FAILED_MESSAGE`: `POLICY_UNAVAILABLE_MESSAGE` 와 같은 꼴의 다음 방법(스펙 DECISION10)
- `EventToolGuard.EVENT_MESSAGE`: `alternative("이번 실행은 사람이 보지 않는 새 사건 살펴보기라 이 도구를 쓸 수 없다. 다시 부르지 않는다.", "붙은 플러그인의 읽기 도구로 살펴본다")`
- `EventToolGuard.IMAGE_MESSAGE`, `UNRESOLVED_MESSAGE`: `askUser(지금 글)`. `blocked(…)` 가 `ToolFailureText` 를 받게 한다

### 4. 테스트

- `backend/src/test/java/com/bifos/assistant/mcp/ToolFailureCoverageTest.java`(신규, 순수 단위, Spring 없음). 이름 목록에 든 클래스(`ConnectorPolicyService`, `ConnectorApprovalReply`, `ConnectorFinancialIntake`, `ConnectorFinancialProducer`, `ToolGuardController`, `EventToolGuard`, `McpToolService`, `McpConnectorListTool`, 그리고 `mcp.presentation.McpRpc`)의 static 필드를 reflection 으로 읽는다
  - 이름이 `_MESSAGE` 로 끝나는 `String` 필드는 `[승인 대기]` 로 시작해야 한다. 아니면 실패한다(끝 줄 없는 판정 글이 다시 `String` 으로 들어온 것)
  - `ToolFailureText` 필드는 `endLine()` 이 `NEXT_PREFIX` 로 시작하거나 `ASK_USER` 와 같다
  - `limitMessage`, `purchaseLimitMessage` 를 표본 예외로 불러 `ASK_USER` 로 끝나는지 본다(같은 패키지가 아니면 reflection 으로 부른다)
  - 「사진을 직접 올려 달라고 한다」 처럼 사용자에게 넘기라고만 하는 글이 `NEXT_PREFIX` 없이 끝나는 것이 없음을 위 두 조건이 함께 보장한다
- `ConnectorPolicyEndpointTest` 와 `ToolGuardControllerTest` 의 막힘 사례마다 응답 `message` 의 마지막 줄이 `NEXT_PREFIX` 로 시작하거나 `ASK_USER` 와 같은지 본다. `[승인 대기]` 사례는 글이 그대로인지 본다. 이름이 `_MESSAGE` 로 끝나지 않는 글도 이 사례가 잡는다
- `test/e2e/scenarios/connector-binding.ts` 의 고정 글(`BINDING_CHECK`, `BINDING_PENDING`, `BINDING_RESTART`, `NO_CONTEXT`)을 새 판정 글로 맞춘다. PR CI 는 E2E 를 기본으로 건너뛰므로 PR 에 `검사:전체` 라벨을 붙여 PR 에서 돌린다(라벨은 PR 을 연 쪽이 붙인다)
- 기존 테스트의 기대 글을 맞춘다. 상수를 직접 비교하던 곳은 `.text()` 를 붙인다. 대상은 `grep -rlE "_MESSAGE|확인하지 못해 막았다|연결이 준비되지 않아" backend/src/test/java/com/bifos/assistant/connector backend/src/test/java/com/bifos/assistant/orchestration backend/src/test/java/com/bifos/assistant/mcp` 로 찾는다. `ConnectorPolicyEndpointTest`, `ToolGuardControllerTest`, `FinancialPrepareReplyTest`, `FinancialOrderOpeningTest`, `CheckoutApprovalTest`, `AttendedOnlyPrepareTest`, `ConnectorActionServiceTest`, `ConnectorBindingDriftTest`, `PurchaseLimitControllerTest` 가 든다

## 검증

```bash
cd backend && HEAVY_SLOTS=2 orch slot heavy -- ./gradlew test --tests '*ToolFailureCoverageTest' --tests 'com.bifos.assistant.connector.*' --tests '*ToolGuard*' --tests 'com.bifos.assistant.mcp.*' --tests '*ArchitectureRules*'
```

기대값: 모두 통과한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/connector/approval/application/ConnectorPolicyService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/approval/application/ConnectorFinancialIntake.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/approval/application/ConnectorFinancialProducer.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/approval/application/ConnectorApprovalReply.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/ToolGuardController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/orchestration/application/EventToolGuard.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/ToolFailureCoverageTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorPolicyEndpointTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/ToolGuardControllerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/approval/application/FinancialPrepareReplyTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/approval/application/FinancialOrderOpeningTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/approval/application/CheckoutApprovalTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/approval/application/AttendedOnlyPrepareTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorActionServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorBindingDriftTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/PurchaseLimitControllerTest.java` | 수정 |
| `test/e2e/scenarios/connector-binding.ts` | 수정 |
