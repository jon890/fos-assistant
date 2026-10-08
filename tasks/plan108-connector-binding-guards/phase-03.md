# Phase 03. 붙이기 화면이 두 거절을 사람 말로 보인다

**Execution profile**: fast

## 목표

붙이기가 `CONNECTOR_SINGLE_BINDING` 이나 `AGENT_SANDBOX_UNAVAILABLE` 로 끝났을 때 화면이 그 까닭을 보인다.
지금은 두 코드 모두 문구 표에 없어 「요청을 처리하지 못했어요.」 로 보인다.

**범위 외**: Control Plane 과 대시보드 plugin(phase 01, 02).

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261008-connector-binding-guards.md`, `docs/connectors.md`, `docs/backend/connector-install.md`

- 거절 표: `docs/connectors.md` 의 「붙이기와 떼기」
- 문구 표는 `web/src/lib/connection.ts` 의 `CONNECTION_ERROR_MESSAGES` 이고 `connectionErrorMessage(code)` 가 읽는다. 브라우저와 서버 라우트가 함께 쓴다. Control Plane 의 오류 원문은 쓰지 않는다
- 단위 시험은 `node --test` 로 `test/unit/*.test.ts` 를 돈다. `web/src/lib/connection.ts` 를 직접 import 하는 본보기는 `test/unit/connector-card.test.ts` 다

## 의도 메모

- `CONNECTOR_BIND_CONFLICT` 의 지금 문구(「이 에이전트의 다른 연결이나 스킬과 이름이 겹쳐요.」)는 바꾸지 않는다. 실행 공간 거절이 그 코드에서 빠졌으므로 문구가 이제 맞다

## 작업 항목

### 1. `web/src/lib/connection.ts`

`CONNECTION_ERROR_MESSAGES` 에 두 줄을 더한다.

```ts
CONNECTOR_SINGLE_BINDING:
  "이 연결은 다른 에이전트에 붙어 있어요. 그 에이전트에서 뗀 뒤 붙여 주세요.",
AGENT_SANDBOX_UNAVAILABLE:
  "격리된 실행 공간이 준비된 에이전트에만 붙일 수 있어요. 관리자에게 알려 주세요.",
```

### 2. 새 시험 `test/unit/connection-error-message.test.ts`

- `connectionErrorMessage("CONNECTOR_SINGLE_BINDING")` 와 `connectionErrorMessage("AGENT_SANDBOX_UNAVAILABLE")` 가 위 문구를 돌려준다
- 표에 없는 코드는 「요청을 처리하지 못했어요.」 다

## 검증

```bash
node --test test/unit/connection-error-message.test.ts test/unit/connector-card.test.ts
pnpm --dir web typecheck
```

둘 다 실패 없이 끝난다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/lib/connection.ts` | 수정 |
| `test/unit/connection-error-message.test.ts` | 신규 |
