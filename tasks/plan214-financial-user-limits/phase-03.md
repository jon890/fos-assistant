# Phase 03. 대화로 상한을 바꾸는 MCP 도구와 상한 변경 승인

**Execution profile**: deep

## 목표

에이전트가 `order_limit_read` 로 상한을 읽고 `order_limit_change` 로 바꾼다. 낮추기는 바로, 올리기는 승인 줄을 만들어 계좌 주인이 승인해야 반영된다. 에이전트가 승인 없이 상한을 올리는 길이 없음을 테스트로 보인다.

**범위 외**: 화면(phase 04). 상한 변경 카드를 그리는 것도 phase 04 다. 이 phase 는 응답의 `limitChange` 요약까지 낸다.

## 컨텍스트

- 본보기는 원고 저장이다. `mcp/application/McpContentDraftSnapshots.java` 의 `content_draft_save` 가 port `chat/application/ContentDraftSaveRequests.java` 를 부르고, 구현 `connector/application/ContentDraftJobs.java` 의 `requestSave` 가 `ConnectorAction.decided` 로 PENDING 승인 줄과 딸린 줄(`content_draft_job`)을 만든다.
- `mcp` 는 층 순서에서 `connector` 아래라 `connector` 를 import 하지 못한다(`backend/docs/code-architecture.md` 의 「최상위 패키지의 층 순서」). port 를 `mcp/application/` 에 두고 `connector` 가 구현한다.
- MCP 도구는 `mcp/presentation/McpController.java` 의 `handlers` 맵과 `mcp/application/McpToolCatalog.java` 의 정의 목록에 함께 등록한다. 살펴보기 트리의 호출은 `CHECK_TREE_TOOLS` 밖이면 `notAllowedInCheck` 로 막힌다. 새 도구를 그 목록에 넣지 않는다.
- 모든 Control Plane MCP 호출은 `_fos_ctx` 서명이 있어야 한다. 서명하지 못하면 Hermes 쪽에서도 막도록 `hermes/plugins/fos-ctx/hooks.py` 의 `REQUIRED_TOOLS` 에 새 도구를 넣는다.
- 공개 승인 API 는 `connector/application/ConnectorActionService.java` 의 `approve(user, actionId, grant, conversationGrant)` 가 위험도 `FINANCIAL` 줄을 `ConnectorFinancialApproval` 로 보낸다. 상한 변경 줄은 그보다 먼저 가른다.
- 승인 목록 응답은 `connector/presentation/ConnectorActionController.java` 가 `ActionView.from(view, contentDraft, financial)` 로 만든다.
- 승인 알림 제목과 알림, 화면 사건은 `ConnectorActionSignals` 의 `notifyRequested` 와 `publishAfterCommit` 를 쓴다. 알림 해소는 `ConnectorActionChanged` 사건을 받은 `ConnectorActionListener` 가 한다.
- phase 01, 02 의 `FinancialOrderLimits`, `FinancialLimitKind`, `FinancialLimitValues` 를 쓴다.

**근거 문서**: `docs/adr/ADR-20261015-financial-user-limits.md` 의 「대화로 올리는 요청은 승인 줄(`connector_action`)로 만든다」, `docs/features/tossinvest-connector.md` 의 「바꾸는 길」, `backend/docs/data-schema.md` 의 「financial_limit_change」, `docs/features/mcp.md` 의 「Control Plane MCP」

## 의도 메모

- 상한 변경 줄의 위험도를 `FINANCIAL` 로 두지 않는다. 관문의 하루 합계가 위험도 `FINANCIAL` 줄을 모두 열어 금액을 세므로, 실행 내용이 없는 줄이 섞이면 모든 주문이 닫힌다.
- 위험도 `WRITE`, 승인 방식 `always` 라 상시 허락과 대화 허락이 없다. 승인 API 는 허락 입력을 `VALIDATION_FAILED` 로 거절한다.
- 같은 실행에서 같은 종류와 값을 다시 요청하면 같은 승인 줄을 돌려준다. 다른 값의 요청은 줄을 따로 만든다. 승인하는 대로 반영된다.
- 승인은 그 순간의 최대치로 다시 본다. 넘으면 반영하지 않고 실패(`FINANCIAL_LIMIT_ABOVE_MAXIMUM`)로 끝낸다.
- 반영은 사용자 행을 잠근 뒤 한다. 주문 판정과 차례로 일어난다.

## 작업 항목

### 1. 마이그레이션 `backend/src/main/resources/db/migration/V20261015104355__financial_limit_change.sql`

`financial_limit_change` 테이블을 만든다. 컬럼과 제약은 `backend/docs/data-schema.md` 의 「financial_limit_change」 와 같다. `action_id` FK 는 `ON DELETE CASCADE` 다.

### 2. 엔티티와 저장소

- `backend/src/main/java/com/bifos/assistant/connector/domain/FinancialLimitChange.java`: `@Entity @Table(name = "financial_limit_change")`, `actionId` 가 `@Id`, `limitKind` 는 `@Enumerated(EnumType.STRING)`.
- `backend/src/main/java/com/bifos/assistant/connector/infra/FinancialLimitChangeRepository.java`

### 3. port 와 MCP 도구

- `backend/src/main/java/com/bifos/assistant/mcp/application/FinancialLimitRequests.java` 신규 interface
  - `Map<String, Object> read(CurrentUser user)`
  - `Map<String, Object> change(CurrentUser user, Long originExecutionId, String name, String value)`
- `backend/src/main/java/com/bifos/assistant/mcp/application/McpFinancialLimitTools.java` 신규: `READ = "order_limit_read"`, `CHANGE = "order_limit_change"`, 두 정의와 port 호출.
  - `order_limit_change` 의 입력은 `name`(다섯 이름의 enum)과 `value`(`^[0-9]{1,14}(\.[0-9]{1,6})?$` 문자열)이다.
  - 설명에 「낮추기는 바로 반영한다. 올리기는 승인 카드를 만들 뿐 반영하지 않는다. 사용자가 카드에서 승인해야 바뀐다. 최대치를 넘으면 거절한다」 를 적는다.
- `McpController` 의 `handlers` 와 `McpToolCatalog` 의 목록에 두 도구를 더한다. 인자 읽기 record 는 `mcp/presentation/McpDtos.java` 에 둔다.

### 4. `backend/src/main/java/com/bifos/assistant/connector/application/FinancialLimitChanges.java` 신규

`FinancialLimitRequests` 를 구현한다.

- `read`: phase 02 의 `FinancialOrderLimits.view` 를 MCP 결과 맵으로 옮긴다.
- `change`: 이름과 값을 검사하고 사용자 행을 잠근 뒤 적용 상한과 최대치를 읽는다.

  | 경우 | 결과 `status` | 하는 일 |
  | --- | --- | --- |
  | 허용 목록에 그 사용자의 계좌가 없다 | `unavailable` | 없음 |
  | 값이 최대치보다 크다 | `above_maximum` | 없음. `maximum` 을 싣는다 |
  | 값이 지금 적용 상한 이하다 | `applied` | `financial_order_limit` 에 바로 쓴다 |
  | 그 밖(올리기) | `approval_requested` | PENDING 승인 줄과 `financial_limit_change` 를 만든다. `actionId`, `previous`, `requested`, `maximum` 을 싣는다 |
  | 부른 에이전트에 금융 보호를 선언한 연결의 바인딩이 정확히 하나가 아니다 | `no_financial_connection` | 없음 |

  - 승인 줄은 `ConnectorAction.decided(connection, agentId, origin, "fos_order_limit_change", "order_limit_change", new ToolPolicyDecision(ActionDecision.NEEDS_APPROVAL, null, ToolRisk.WRITE, ToolApproval.ALWAYS), false, dedupeKey, argsSha256, now)` 로 만든다. dedupe key 는 `Sha256.hex("fos-order-limit:" + userId + ":" + originExecutionId + ":" + kind + ":" + value)` 이고, 같은 key 의 줄이 있으면 그 줄을 돌려준다.
  - 금융 보호를 선언한 연결은 카탈로그 선언(`ConnectorManifest.executionGuard()`)에 `prepare_tool` 이 있는 커넥터다. 서비스 이름으로 고르지 않는다.
  - 인자는 `{"name": ..., "value": ...}` 를 `ConnectorActionBodies.sealArgs` 로 봉인한다. 대화가 있으면 `ConnectorActionSignals.notifyRequested(action, "주문 상한 변경")` 와 `publishAfterCommit` 를 부른다.
- `boolean owns(ConnectorAction action)`: 그 줄의 `financial_limit_change` 가 있는가.
- `ConnectorActionView approve(CurrentUser user, UUID actionId)`: 사용자 행, 승인 줄, 딸린 줄 순서로 잠근다. PENDING 이고 만료 전이어야 한다. 요청 값이 지금 최대치보다 크면 `ConnectorActionBodies.fail(action, "FINANCIAL_LIMIT_ABOVE_MAXIMUM", ...)`, 아니면 `beginExecution` 뒤 반영하고 `ConnectorActionBodies.succeed(action, "{종류 이름} 상한을 {값} 으로 바꿨다", now)`. 끝나면 `publishAfterCommit`.
- `Optional<FinancialLimitChangeSummary> summaryOf(CurrentUser user, UUID actionId)` 와 여러 줄의 `summaries`: `name`, `currency`, `previous`, `requested`, `maximum`(지금 최대치), `current`(지금 적용 상한). `backend/src/main/java/com/bifos/assistant/connector/application/model/FinancialLimitChangeSummary.java` 신규 record.

### 5. 승인 API 와 목록 응답

- `ConnectorActionService.approve(user, actionId, grant, conversationGrant)`: 위험도 `FINANCIAL` 분기보다 먼저, `FinancialLimitChanges.owns` 면 주인 확인, 허락 입력 거절(`VALIDATION_FAILED`) 뒤 `FinancialLimitChanges.approve` 로 보낸다.
- `ConnectorActionDtos.ActionView` 에 `FinancialLimitChangeSummary limitChange` 를 끝에 더하고, `ConnectorActionController` 의 목록, 승인, 거절 응답이 채운다. `ConnectorActionDetails.view` 는 상한 변경 줄의 제목을 「주문 상한 변경」 으로 둔다.
- 골든 fixture `test/resources/connector-argument-masked-action.json` 에 `"limitChange":null` 이 생긴다. `FOS_EXPORT_ARGUMENT_DTO=true` 로 `ConnectorArgumentLabelsPipelineTest` 를 돌려 다시 뽑는다.

### 6. Hermes 와 문서

- `hermes/plugins/fos-ctx/hooks.py` 의 `REQUIRED_TOOLS` 에 `order_limit_read`, `order_limit_change` 를 더하고, 같은 목록을 적은 `hermes/plugins/fos-ctx/__init__.py` 의 docstring, `hermes/plugins/fos-ctx/README.md` 의 세 곳, `hermes/tests/test_fos_ctx.py` 의 두 목록에도 더한다.
- `hermes/connectors/tossinvest/skills/tossinvest/SKILL.md` 에 상한 안내를 더한다: 주문이 상한에 걸리면 답에 실린 상한과 남은 한도를 사용자에게 알리고, 바꾸려면 `order_limit_change` 를 쓴다. 올리기는 카드 승인 전에는 반영되지 않았다고 말한다.
- `docs/features/connector-policy.md` 의 공개 승인 API 설명에 「상한 변경 줄은 금융 주문 승인보다 먼저 상한 변경 승인으로 보낸다」 를 더한다.

### 7. 이 phase 를 검증하는 테스트

- `backend/src/test/java/com/bifos/assistant/connector/application/FinancialLimitChangesTest.java` 신규
  - 낮추기는 승인 줄 없이 바로 반영된다(`applied`)
  - 올리기는 PENDING 승인 줄과 딸린 줄만 만들고 적용 상한은 그대로다(`approval_requested`)
  - 최대치를 넘는 값은 줄 없이 `above_maximum` 과 최대치를 낸다
  - 같은 실행의 같은 요청은 같은 `actionId` 다
  - 승인하면 반영되고 줄은 SUCCEEDED 다. 거절하거나 만료되면 반영되지 않는다
  - 요청 뒤 최대치가 내려가 넘게 되면 승인이 반영하지 않고 줄은 FAILED 다
  - 승인 API 에 상시 허락이나 대화 허락을 주면 `VALIDATION_FAILED` 이고 반영되지 않는다
  - 일반 예약 작업 실행이 부른 올리기도 승인 줄만 만든다
- `backend/src/test/java/com/bifos/assistant/mcp/McpFinancialLimitToolsTest.java` 신규
  - 두 도구가 `tools/list` 에 있다
  - 살펴보기 트리(매일 깨우기 포함)의 `order_limit_change` 호출은 막히고 아무것도 바뀌지 않는다
  - 서명 없는 호출은 거절된다
- `backend/src/test/java/com/bifos/assistant/connector/application/ConnectorArgumentLabelsPipelineTest.java`: 다시 뽑은 fixture 로 통과한다
- `hermes/tests/test_fos_ctx.py`: 서명하지 못하면 두 도구가 막힌다

## 검증

```bash
cd backend && ./gradlew test --tests '*FinancialLimitChangesTest' --tests '*McpFinancialLimitToolsTest' --tests '*ConnectorArgumentLabelsPipelineTest' --tests '*Financial*' --tests 'com.bifos.assistant.mcp.*'
cd backend && ./gradlew archTest spotlessCheck checkstyleMain checkstyleTest
node scripts/checks/check-migration-versions.mjs
cd hermes && python3 -m pytest tests/test_fos_ctx.py -q
cd web && node --test ../test/web/connector-action-route.test.ts
scripts/dev/check-local.sh --fast
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/resources/db/migration/V20261015104355__financial_limit_change.sql` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/domain/FinancialLimitChange.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/infra/FinancialLimitChangeRepository.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/FinancialLimitRequests.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpFinancialLimitTools.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolCatalog.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/FinancialLimitChanges.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/model/FinancialLimitChangeSummary.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorActionService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorActionDetails.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/presentation/ConnectorActionDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/presentation/ConnectorActionController.java` | 수정 |
| `test/resources/connector-argument-masked-action.json` | 수정 |
| `hermes/plugins/fos-ctx/hooks.py` | 수정 |
| `hermes/plugins/fos-ctx/__init__.py` | 수정 |
| `hermes/plugins/fos-ctx/README.md` | 수정 |
| `hermes/tests/test_fos_ctx.py` | 수정 |
| `hermes/connectors/tossinvest/skills/tossinvest/SKILL.md` | 수정 |
| `docs/features/connector-policy.md` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/application/FinancialLimitChangesTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpFinancialLimitToolsTest.java` | 신규 |
