# Phase 01. `default_tool_policy: ask` 로 선언하지 않은 도구를 호출마다 묻는다

**Execution profile**: standard

## 목표

`assistant.json` 의 `schema: 2` 가 `default_tool_policy: ask` 를 선언하면, `tools` 에 없는 그 서버의 도구를 거절하지 않고 `schema: 3` 의 도구처럼 판정한다.
선언한 도구는 지금처럼 그 선언으로 판정한다.

**범위 외**: 다른 기본값(예: 선언하지 않은 도구를 읽기로 보는 것). 사람이 보지 않는 기본값은 열지 않는다.

## 컨텍스트

**근거 문서**: `hermes/docs/adr/ADR-20261101-plugin-without-assistant-json.md` 의 「남은 단계」, `docs/features/connector-policy.md` 의 「도구 정책」 과 「선언하지 않은 도구」

- `default_tool_policy` 는 지금 `deny` 만 받는다. 검증은 `hermes/plugins/dashboard-profile-api/connector_policy.py` 의 `_connector_tools` 가 한다
- 카탈로그는 이 칸을 내지 않는다. Control Plane 의 `ConnectorToolPolicies.undeclared` 는 schema 2 에서 선언하지 않은 도구를 센다
- 판정은 `ToolPolicyDecision.decide` 다. schema 3 의 선언 없는 도구 규칙(plan229 가 연 읽기 표시 상시 허락 포함)을 그대로 쓴다

## 의도 메모

- 카탈로그에 `default_tool_policy` 를 싣는다. 이 칸을 모르는 옛 Control Plane 은 선언하지 않은 도구를 지금처럼 거절하므로 느슨해지지 않는다
- `ask` 인 커넥터의 선언하지 않은 도구는 연결 상태의 「선언하지 않은 도구」 수에서 뺀다. 거절되지 않기 때문이다

## 작업 항목

### 1. 선언과 카탈로그

`_connector_tools` 가 `ask` 를 받고, 카탈로그 응답에 `default_tool_policy` 를 싣는다.

### 2. 판정

`ConnectorCatalogDecoder` 가 그 칸을 읽고, `decide` 가 `ask` 인 커넥터의 선언 없는 도구를 schema 3 과 같게 판정한다.

### 3. 이 phase 를 검증하는 테스트

- `hermes/tests/test_connector_manifest.py`: `ask` 가 카탈로그에 실리고 다른 값은 빠진다
- `backend/src/test/java/com/bifos/assistant/connector/StandardPluginPolicyTest.java`: `ask` 커넥터의 선언한 READ 도구는 승인 없이, 선언하지 않은 도구는 승인 필요다. 칸이 없는 카탈로그는 지금처럼 거절한다

## 검증

```bash
python3 -m unittest discover -s hermes/tests -p 'test_connector_manifest.py'
cd backend && ./gradlew test --tests '*StandardPluginPolicyTest' --tests '*ConnectorToolPoliciesTest'
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/dashboard-profile-api/connector_policy.py`, `connector_manifest.py` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/infra/ConnectorCatalogDecoder.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/action/domain/ToolPolicyDecision.java` | 수정 |
| `hermes/connectors/README.md` 의 「도구 정책」, `docs/features/connector-policy.md` | 수정 |
