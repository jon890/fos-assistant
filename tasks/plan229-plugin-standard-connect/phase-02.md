# Phase 02. 화면이 schema 3 연결과 그 승인 카드를 보인다

**Execution profile**: standard

## 목표

외부 서비스 연결 화면이 `assistant.json` 없는 플러그인을 다른 커넥터처럼 보이고 등록하게 한다.
승인 카드는 선언한 제목 대신 도구의 원래 이름을 보이고, 상시 허락은 읽기 표시가 있는 도구에서만 고를 수 있다.

**범위 외**: 「커넥터」 를 「플러그인」 으로 바꾸는 이름 변경. 원격 MCP ADR 의 3단계다.

## 컨텍스트

**근거 문서**: `hermes/docs/adr/ADR-20261101-plugin-without-assistant-json.md`, `docs/features/connector.md` 의 「화면」, `docs/features/connector-policy.md` 의 「화면」

- 연결 화면은 `web/src/app/connections/` 와 `web/src/components/connector*/` 가 갖는다. 관리자 목록은 `web/src/app/admin/connections/` 다
- 카드의 도구 목록은 Control Plane 의 `ConnectorToolSummary` 에서 온다. schema 3 은 도구 선언이 없어 비어 있다
- 승인 카드의 도구 이름은 선언의 `title` 이고, 없으면 「이름 없는 동작」 이다

## 작업 항목

### 1. 연결 화면

schema 3 커넥터의 카드에 「모든 도구를 쓸 때마다 확인합니다」 안내를 보인다. 칸의 표시 이름이 env 이름이면 그대로 보이되, 모든 칸을 가리는 입력으로 받는다.
도구 목록 대신 마지막 연결 확인에서 받은 도구 수와 읽기 표시가 있는 도구 수를 보인다. 값은 phase 01 의 연결 응답에 더한다.

### 2. 승인 카드

선언 제목이 없고 원래 도구 이름이 확인된 줄은 그 이름을 보인다. 상시 허락 선택은 `grantable` 이 참일 때만 보인다(지금과 같다).

### 3. 이 phase 를 검증하는 테스트

- `test/web/` 의 연결 화면 테스트: schema 3 카드의 안내와 도구 수
- `test/browser/approval-card.spec.ts`: 제목 없는 줄이 원래 이름을 보이고, 읽기 표시가 없는 도구에는 상시 허락이 없다

## 검증

```bash
cd web && npm run test:node
cd web && npm run test:browser -- approval-card
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/app/connections/` 의 카드와 상세 | 수정 |
| `web/src/components/connector/connector-catalog.tsx`, `connector-tools.tsx`, 승인 카드 | 수정 |
| `docs/features/connector.md`, `docs/features/connector-policy.md` 의 「화면」 | 수정 |
