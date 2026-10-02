# Phase 01. 도구 선언의 `grant` 로 상시 허락을 닫는다

**Execution profile**: standard

## 목표

`connector.json` 의 도구 선언에 `"grant": false` 를 두면 그 도구는 호출마다 승인을 받고 상시 허락을 만들 수 없다.
메일 보내기처럼 계정 밖으로 나가는 도구에 30일짜리 허락이 생기지 않게 한다.

**범위 외**: Gmail 커넥터(phase 02), 공통 계약 검사(phase 03). DB 마이그레이션은 없다.

## 컨텍스트

- 결정은 `docs/adr/ADR-060-외부로-나가는-도구는-상시-허락을-닫는-선언을-둔다.md`, 계약은 `docs/backend/connector-tool-policy.md` 의 「도구 정책」 과 「도구 호출 판정」, `docs/connectors.md` 의 「Control Plane API」 와 「승인 줄과 경로」 에 있다. **이 phase 는 그 문서를 고치지 않는다.**
- 대시보드 plugin: `hermes/plugins/dashboard-profile-api/__init__.py` 의 `_connector_tools(declared, verify_tool, option_tools, mcp_server)` 가 도구 선언을 검증해 `{이름: {"risk", "approval", "title"}}` 를 낸다. 지금은 `set(declared_tool) - {"risk", "approval", "title"}` 이 비지 않으면 거절한다. 그 결과가 `_load_connector` 의 반환값 `"tools"` 로 가고 카탈로그 응답에 그대로 실린다. `tools.exclude` 는 `policy["approval"] == "always"` 로만 계산한다. 이것은 바꾸지 않는다
- Control Plane 이 카탈로그를 읽는 곳: `backend/src/main/java/com/bifos/assistant/hermes/HttpHermesConnectorClient.java` 의 `tools(JsonNode declared)` 가 `new ConnectorTool(entry.getKey(), text(policy, "risk"), text(policy, "approval"), text(policy, "title"))` 를 만든다. dto 는 `backend/src/main/java/com/bifos/assistant/hermes/dto/ConnectorTool.java` 의 `record ConnectorTool(String name, String risk, String approval, String title)` 다
- 정책 읽기: `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorToolPolicies.java` 의 `policy(ConnectorTool)` 가 `new ToolPolicy(risk, approval, tool.title())` 를 만들고 `summaries(manifest)` 가 `new ConnectorToolSummary(tool.name(), policy.title(), policy.risk(), policy.approval())` 를 낸다. `ToolPolicy` 는 `connector/domain/ToolPolicy.java` 의 `record ToolPolicy(ToolRisk risk, ToolApproval approval, String title)` 다
- 판정: `connector/domain/ToolPolicyDecision.java` 의 `decide(...)` 가 `policy.approval() == ToolApproval.NONE || (policy.approval() == ToolApproval.REQUIRED && granted)` 이면 허용한다. `declared` 가 빈 `schema: 1` 호출은 `new ToolPolicy(ToolRisk.WRITE, ToolApproval.REQUIRED, null)` 로 읽는다
- 승인: `connector/application/ConnectorActionService.java` 의 `beginApproval(user, actionId, grant)` 가 `grant != null && !action.grantAllowed()` 이면 `grantNotAllowed()` 를 던지고, 다시 판정한 뒤 `decision.get().approval() != ToolApproval.REQUIRED` 이면 또 던진다. `redecide(connection, manifest, action)` 가 지금 정책으로 판정한다
- 승인 줄 응답: `connector/application/model/ConnectorActionView.java` 의 `from(ConnectorAction action, String declaredTitle)` 가 `action.grantAllowed()` 를 그대로 싣는다. 부르는 곳은 `ConnectorActionService` 의 `listForConversation`, `undelivered`, `view` 셋이고 모두 `ConnectorToolPolicies.find(manifest, action.toolName()).map(ToolPolicy::title).orElse(null)` 을 넘긴다. `ConnectorAction.grantAllowed()` 는 `approvalMode == ToolApproval.REQUIRED && toolName != null` 이다
- 화면에 내는 도구: `connector/application/model/ConnectorToolSummary.java` 와 `connector/presentation/ConnectionDtos.java` 의 `record ConnectorToolView(String name, String title, String risk, String approval)`
- 웹: `web/src/lib/connection.ts` 의 `ConnectorTool` 타입과 `toolPolicyLabel(tool)`, `web/src/lib/connection-route.ts` 의 `safeTools(value)`. 도구 목록을 그리는 곳은 `web/src/components/connector/connector-tools.tsx` 다. 승인 카드(`web/src/components/chat/approval-card.tsx`)는 이미 `action.grantAllowed` 로 「승인하고 묻지 않기」 를 그린다. 카드는 고치지 않는다
- e2e 의 Hermes 대역: `test/e2e/fake-hermes.ts` 의 카탈로그 `tools` 와 `test/e2e/scenarios/connector.ts` 의 `type ToolView`

**근거 문서**: `docs/adr/ADR-060-외부로-나가는-도구는-상시-허락을-닫는-선언을-둔다.md`, `docs/backend/connector-tool-policy.md` 의 「도구 정책」, `docs/connectors.md` 의 「승인 줄과 경로」

## 의도 메모

- `approval: always` 의 뜻을 바꾸지 않는다. `always` 는 설치가 모델에게서 빼는 도구이고 웹이 「아직 쓸 수 없어요」 로 보인다
- 승인 줄에 `grant` 를 저장하지 않는다. 마이그레이션을 피하고, 선언이 바뀌면 바로 따른다. 읽을 때와 승인할 때의 카탈로그로 본다. 카탈로그를 읽지 못하면 줄 수 없는 것으로 낸다
- Control Plane 은 boolean 이 아닌 `grant` 를 거절하지 않고 거짓(닫힘)으로 읽는다. 형식 검증은 대시보드 plugin 이 한다
- 이미 있는 상시 허락 줄을 지우지 않는다. 판정이 보지 않는다

## 작업 항목

### 1. `hermes/plugins/dashboard-profile-api/__init__.py` 의 `_connector_tools`

- 도구 선언이 가질 수 있는 키에 `grant` 를 더한다
- `grant` 가 있으면 `type(...) is bool` 이어야 한다. 아니면 `ValueError("grant 는 true 나 false 다")`
- `grant` 를 선언했는데 `approval`(기본값을 채운 값)이 `required` 가 아니면 `ValueError("grant 는 approval 이 required 인 도구에만 선언한다")`
- 정책 값에 `"grant"` 를 더한다. `approval == "required"` 이고 선언이 `false` 가 아닐 때만 `True` 다
- `schema: 1` 이 내는 값에도 `"grant": False` 를 넣는다(승인이 `none` 이다)
- 이 dict 를 쓰는 다른 곳(`tools.exclude` 계산, 이름 대응 파일, 소유 기록 비교)이 `grant` 때문에 달라지지 않는지 읽고 확인한다. 달라지는 곳이 있으면 그 자리가 `risk`, `approval`, `title` 만 보게 둔다
- 함수 docstring 의 반환 모양을 고친다

### 2. `hermes/tests/test_connector_manifest.py`

같은 파일의 기존 `schema: 2` 검사 방식을 따른다.

- `"grant": false` 를 선언한 `WRITE` 도구가 카탈로그에 `grant: False`, `approval: "required"` 로 나오고 설치한 서버 정의의 `tools.exclude` 에 없다
- 선언하지 않은 `WRITE` 도구는 `grant: True`, `READ` 도구는 `grant: False` 다
- `grant` 가 문자열이면, `READ`(`none`) 도구에 `grant` 를 선언하면, `always` 도구에 선언하면 그 커넥터가 카탈로그에서 빠진다

### 3. backend

- `ConnectorTool` 에 `Boolean grant` 를 더한다. 응답에 없으면 `null` 이다. Javadoc 을 고친다
- `HttpHermesConnectorClient.tools` 가 `grant` 를 읽는다. 칸이 없으면 `null`, boolean 이면 그 값, 그 밖의 모양이면 `Boolean.FALSE`
- `ToolPolicy` 에 `boolean grantable` 을 더한다. `ConnectorToolPolicies.policy` 가 `approval == REQUIRED && !Boolean.FALSE.equals(tool.grant())` 로 채운다
- `ToolPolicyDecision.decide` 의 허용 조건을 `NONE` 이거나 `REQUIRED && granted && policy.grantable()` 로 바꾼다. `schema: 1` 의 선언 없는 도구에 쓰는 기본 정책은 `grantable` 을 참으로 둔다(지금 동작 그대로)
- `ConnectorToolSummary` 와 `ConnectionDtos.ConnectorToolView` 에 `boolean grant` 를 더하고 `policy.grantable()` 을 싣는다
- `ConnectorActionView.from` 이 선언한 정책을 받게 바꾼다: `from(ConnectorAction action, Optional<ToolPolicy> declared)`. 제목은 `declared.map(ToolPolicy::title)`, `grantAllowed` 는 `action.grantAllowed() && declared.map(ToolPolicy::grantable).orElse(false)` 다. 부르는 세 곳을 고친다
- `beginApproval` 의 두 번째 검사를 「지금 정책이 `REQUIRED` 이고 `grantable`」 로 바꾼다. 지금 정책은 `ConnectorToolPolicies.find(manifest, action.toolName())` 로 읽는다. 아니면 `grantNotAllowed()` 다. 승인 줄은 `PENDING` 으로 남는다(지금 동작 그대로)
- `ToolPolicy` 생성자를 쓰는 다른 곳과 `ConnectorTool` 생성자를 쓰는 검사 코드(`backend/src/test/java/com/bifos/assistant/connector/` 아래)를 모두 고친다. `grep -rn "new ConnectorTool(\|new ToolPolicy(" backend/src` 로 찾는다

### 4. backend 검사

- `backend/src/test/java/com/bifos/assistant/connector/ToolPolicyDecisionTest.java`: `grantable` 이 거짓인 `REQUIRED` 정책은 `granted` 가 참이어도 `NEEDS_APPROVAL` 이다. 참이면 `ALLOWED` 다
- `backend/src/test/java/com/bifos/assistant/connector/ConnectorToolPoliciesTest.java`: `grant` 가 `null`, `TRUE`, `FALSE` 일 때의 `grantable` 과 `summaries` 의 `grant`. `none` 도구는 `grant` 가 `TRUE` 여도 `grantable` 이 거짓이다
- `backend/src/test/java/com/bifos/assistant/connector/ConnectorActionServiceTest.java`: `grant: false` 인 도구의 승인 줄은 `grantAllowed` 가 거짓이다. 그 줄에 기간을 실어 승인하면 `VALIDATION_FAILED` 이고 줄은 `PENDING` 이며 실행되지 않고 허락 줄이 생기지 않는다. 기간 없이 승인하면 실행된다
- `backend/src/test/java/com/bifos/assistant/connector/ConnectorPolicyEndpointTest.java`: `grant: false` 인 도구에 유효한 상시 허락 줄이 있어도 판정이 `block` 이고 승인 줄이 생긴다
- 카탈로그 parse 검사가 있는 파일(`grep -rln "HttpHermesConnectorClient" backend/src/test`)에 `grant` 가 없을 때, `false` 일 때, 문자열일 때를 더한다

### 5. 웹

- `web/src/lib/connection.ts`: `ConnectorTool` 에 `grant: boolean` 을 더한다. `toolPolicyLabel` 은 막힌 도구 「아직 쓸 수 없어요」, `NONE` 「바로 실행해요」, `REQUIRED` 이고 `grant` 가 참이면 「실행 전에 물어봐요」, 거짓이면 「실행할 때마다 물어봐요」 다
- `web/src/lib/connection-route.ts` 의 `safeTools`: `grant: item.grant === true`
- `ConnectorTool` 을 만드는 다른 곳(`grep -rn "approval:" web/src test/browser test/unit`)을 고친다
- `test/unit/` 에 `toolPolicyLabel` 의 네 갈래를 보는 검사를 더한다. 같은 디렉터리의 `toolset-label.test.ts` 가 `web/src/lib` 의 함수를 불러 검사하는 선례다. 파일 이름은 `test/unit/connector-tool-label.test.ts`
- `test/browser/connector-connection.spec.ts` 가 도구 목록의 문구를 단언하면 대역의 값에 맞게 고친다

### 6. e2e

- `test/e2e/fake-hermes.ts` 의 카탈로그 `tools` 에 기본값을 채운 `grant` 를 넣는다. 실제 대시보드 plugin 이 내는 모양과 같아야 한다. `write_note` 는 `true`, 나머지는 `false`
- `test/e2e/scenarios/connector.ts` 의 `ToolView` 에 `grant` 를 더하고 `write_note` 의 `grant` 가 참임을 본다

## 검증

```bash
# cwd: 저장소 root
python3 -m unittest discover -s hermes/tests
cd backend && ./gradlew test --tests 'com.bifos.assistant.connector.*' --tests 'com.bifos.assistant.hermes.*' && cd ..
cd backend && ./gradlew test && cd ..
node --test 'test/unit/**/*.test.ts'
node test/e2e/run.ts
cd web && pnpm typecheck && cd ..
scripts/quality.sh check
```

모두 종료 코드 0 이다. `gradlew` 의 위치와 `pnpm` 이 요구하는 환경 변수는 `backend/AGENTS.md` 와 `web/AGENTS.md` 에 있다.
hermes 검사는 `mcp==2.0.0` 과 `PyYAML==6.0.3` 이 있어야 돈다(`scripts/check-local.sh` 의 `check_hermes` 가 맞춰 설치한다).

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/dashboard-profile-api/__init__.py` | 수정 |
| `hermes/tests/test_connector_manifest.py` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/dto/ConnectorTool.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HttpHermesConnectorClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/domain/ToolPolicy.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/domain/ToolPolicyDecision.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorToolPolicies.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorActionService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/model/ConnectorActionView.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/model/ConnectorToolSummary.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/presentation/ConnectionDtos.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/*.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/*.java` | 수정 |
| `web/src/lib/connection.ts` | 수정 |
| `web/src/lib/connection-route.ts` | 수정 |
| `test/unit/connector-tool-label.test.ts` | 신규 |
| `test/browser/connector-connection.spec.ts` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/e2e/scenarios/connector.ts` | 수정 |
