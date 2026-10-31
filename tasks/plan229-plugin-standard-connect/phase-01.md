# Phase 01. schema 3 연결을 MCP 도구 목록으로 확인하고 readOnlyHint 도구에만 상시 허락을 연다

**Execution profile**: deep

## 목표

`assistant.json` 이 없는 표준 플러그인(카탈로그 `schema: 3`)의 연결을 등록하고 확인할 수 있게 한다.
확인 도구 대신 그 서버에 붙어 `tools/list` 가 성공하는지로 값을 확인한다.
`readOnlyHint` 가 참인 도구는 첫 호출을 승인받은 뒤 상시 허락을 줄 수 있고, 나머지 도구는 지금처럼 호출마다 승인이다.

**범위 외**: 화면(phase 02), ChatGPT 이식형 묶음, `assistant.json` 의 부분 선언.

## 컨텍스트

**근거 문서**: `hermes/docs/adr/ADR-20261101-plugin-without-assistant-json.md` 의 「결정」 과 「남은 단계」, `docs/features/connector.md` 의 「플러그인 형식과 원격 MCP」, `docs/features/connector-policy.md` 의 「도구 정책」 과 「도구 호출 판정」

- 대시보드 plugin 의 카탈로그는 `hermes/plugins/dashboard-profile-api/connector_manifest.py` 의 `_load_connector` 가 만들고, `assistant.json` 이 없으면 `connector_standard.py` 의 `_standard_declaration` 이 선언을 추론한다
- MCP 서버에 붙는 일은 `connector_mcp.py` 의 `_open_connector_session` 이 로컬과 원격을 함께 맡는다. `_run_connector_tool` 이 이미 `tools/list` 의 `read_only_hint` 를 읽는다
- 지금 Control Plane 은 `ConnectorConnectionService.register` 의 `requireVerifyTool` 과 `verify` 에서 schema 3 을 `CONNECTOR_UNAVAILABLE` 로 거절한다
- 판정은 `ToolPolicyDecision.decide` 가 하고, schema 3 의 선언 없는 도구는 `WRITE`, `required`, 상시 허락 닫힘이다. 상시 허락을 줄 수 있는지 화면에 내는 값은 `ConnectorActionDetails.grantable` 이 계산한다
- 상시 허락은 `connector_tool_grant` 에 둔다. 거두는 정리는 `ADR-065` 의 주기 정리가 한다

## 의도 메모

- 새 테이블을 만들지 않는다. 읽기 표시는 `connector_connection` 에 nullable 텍스트 컬럼 하나(`read_only_tools`, 도구 이름의 JSON 배열)로 둔다. 마이그레이션 버전은 코디네이터의 `reserve-migration` 으로 받는다
- 읽기 표시는 운영 목록의 원격 서버가 바꿀 수 있다. 연결 확인과 승인 실행 때마다 새로 받은 목록으로 덮어쓰고, 빠진 도구의 상시 허락은 그 트랜잭션에서 거둔다
- `destructiveHint`, `openWorldHint` 는 판정에 쓰지 않는다
- 도구 목록 경로는 값을 디스크에 쓰지 않는다. `call` 과 같은 시간 제한과 동시 한도를 쓴다

## 작업 항목

### 1. 대시보드 plugin 의 도구 목록 경로

`POST /api/connectors/<id>/tools` 를 연다. 본문은 `call` 처럼 `values` 나 `vault` 가운데 하나다.
`schema: 3` 커넥터에서만 받고, 붙어서 `tools/list` 를 받으면 `{"ok": true, "tools": [{"name", "read_only": bool}]}` 이다. 붙지 못하면 `call` 과 같은 `unavailable` 이다. 원격 서버가 401 이나 403 을 주면 `credential_rejected` 다.
`execute` 응답에 부른 도구의 `read_only` 를 더한다. 실행 전에 이미 받는 `tools/list` 의 값이다.
경로와 응답은 `hermes/plugins/dashboard-profile-api/README.md` 의 경로 표에 더한다.

### 2. Control Plane 의 등록과 확인

`HermesConnectorClient` 에 도구 목록 호출을 더한다. `register` 와 연결 확인은 schema 3 이면 확인 도구 대신 이 호출로 확인하고 `read_only_tools` 를 적는다. `undeclared_tools` 는 0 이다.

### 3. 상시 허락 판정

`ToolPolicyDecision.decide` 에 그 도구가 읽기 표시 목록에 있는지를 넘긴다. schema 3 이고 목록에 있으면 상시 허락을 열고, 대화 허락은 닫힌 채 둔다.
`ConnectorActionDetails.grantable` 과 승인하면서 허락을 만드는 곳이 같은 값을 쓴다.
승인 실행의 응답이 `read_only: false` 거나 연결 확인의 목록에서 도구가 빠지면 그 연결의 그 도구 허락을 거둔다.

### 4. 이 phase 를 검증하는 테스트

- `hermes/tests/test_connector_standard_plugin.py`: 도구 목록 경로가 fixture 의 `list_notes` 를 `read_only: true`, `delete_note` 를 거짓으로 낸다. schema 2 커넥터에서는 거절한다. 값이 응답과 로그에 없다
- `backend/src/test/java/com/bifos/assistant/connector/StandardPluginPolicyTest.java`: 목록에 있는 도구만 상시 허락으로 허용되고, 목록에서 빠지면 거둔 뒤 승인 필요로 돌아간다
- 등록과 연결 확인의 서비스 테스트: schema 3 은 확인 도구를 부르지 않고 도구 목록을 부른다

## 검증

```bash
python3 -m unittest discover -s hermes/tests -p 'test_connector_standard_plugin.py'
cd backend && ./gradlew test --tests '*StandardPluginPolicyTest' --tests '*ConnectorConnectionServiceTest' --tests '*ToolPolicyDecisionTest'
scripts/checks/check-mysql-migration.sh --tests '*StandardPluginPolicyTest'
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `hermes/plugins/dashboard-profile-api/connector_run.py`, `routes.py`, `connector_mcp.py` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/application/ConnectorConnectionService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/action/domain/ToolPolicyDecision.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/domain/ConnectorConnection.java` | 수정 |
| `backend/src/main/resources/db/migration/` 의 예약 경로 | 신규 |
| `docs/features/connector.md`, `docs/features/connector-policy.md`, `backend/docs/data-schema.md` | 수정 |
