# Phase 02. Hermes HTTP 계약과 지난 연결 호환

**Execution profile**: standard

## 목표

Hermes HTTP 계약과 지난 연결 호환을 구현하고 해당 경계에서 테스트한다.

**범위 외**: 계정 연결 시작·완료와 실제 갱신, 알림, 연결 해제 흐름, 웹과 운영 앱 등록.

## 컨텍스트

**요구사항**: REQ4, REQ5, REQ6, REQ8, REQ9

**근거 문서**: `docs/features/connector-oauth/spec.md`, `docs/features/connector-oauth/design.md`, `hermes/plugins/dashboard-profile-api/README.md`

## 의도 메모

- 두 번째 Control Plane PR에서 시작 API와 토큰 통로를 함께 연다.
- 외부 토큰과 코드, client secret 원문을 로그와 사용자 응답에 쓰지 않는다.

## 작업 항목

### 1. Hermes OAuth 통신

`HermesConnectorOAuthClient`와 구현체는 대시보드 README의 `/api/connector-oauth/*` 계약을 쓴다. 호출별 응답을 파싱하고 흐름 충돌(HTTP 409), 일시 장애와 실제 공급자 응답을 구분한다. 외부 응답 원문이나 비밀값은 예외에 담지 않는다. `call`과 `list`의 보관 파일 경로는 `oauth: pending`과 `oauth: current`를 선택해서 보낼 수 있도록 한다. 기존 수동 호출 본문은 유지한다.

### 2. 저장 JSON 호환

`ConnectionFieldsConverter`와 `ConnectionFields`가 OAuth의 시작 방식과 다시 연결 필요 표시를 읽고 쓴다. 옛 행에 auth가 없으면 수동으로 읽고, 수동 값은 기존 JSON 크기 한도에서 그대로 저장할 수 있게 한다. 스키마 설명에서 새 JSON 칸을 확인할 수 있어야 한다.

### 3. 공유 JSON과 기존 연결 테스트

`HttpHermesConnectorOAuthClientTest`에서 Hermes 공유 JSON으로 교환과 갱신, `missing` 응답을 대조한다. 409, 5xx, 알 수 없는 응답과 필수 칸 오류를 확인한다. 확인 도구와 목록 호출의 OAuth 모드는 `HttpHermesConnectorClientTest`가 본다. `ConnectorConnectionTest`에서 이전의 수동 JSON, OAuth의 저장과 다시 읽기, 해제 뒤 수동 상태와 최대 길이의 수동 호환을 검증한다.

## 검증

```bash
cd backend && HEAVY_SLOTS=2 orch slot heavy -- ./gradlew test --tests '*HttpHermesConnectorOAuthClientTest' --tests '*HttpHermesConnectorClientTest' --tests '*ConnectorConnectionTest'
```

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/hermes/application/port/HermesConnectorOAuthClient.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/application/port/ConnectorOAuthFlowConflict.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/application/dto/ConnectorOAuth.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/infra/HttpHermesConnectorOAuthClient.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/domain/ConnectionAuth.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/application/port/HermesConnectorClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/infra/HttpHermesConnectorClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/infra/HermesConnectorBodies.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/domain/ConnectionFields.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/connection/infra/ConnectionFieldsConverter.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/HttpHermesConnectorOAuthClientTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/hermes/HttpHermesConnectorClientTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorConnectionTest.java` | 수정 |
| `backend/docs/data-schema.md` | 수정 |
| `docs/features/connector.md` | 수정 |
