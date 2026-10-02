# Phase 01. 허락 목록을 읽지 못하면 옛 허락을 지우고 안내한다

**Execution profile**: standard

## 목표

연결 화면의 「묻지 않고 실행하는 동작」 이 서버의 실제 상태와 다르게 남지 않게 한다.
지금은 다시 읽기가 실패하면 옛 허락을 그대로 보인다. 연결을 해제해 서버가 허락을 거둔 뒤에도 화면에 남는다.

**범위 외**: 허락을 거두는 API, 승인 카드, 다음 turn 을 정하는 구조는 바꾸지 않는다. 읽지 못했다고 허락을 거두는 요청을 보내지 않는다.

## 컨텍스트

- `web/src/components/connector/connector-grants.tsx` 의 `ConnectorGrants` 가 `readConnectorGrants()` 로 허락을 읽는다.
  `useEffect` 안의 `if (stale || !result.ok) return;` 이 실패를 버려 옛 `grants` 가 남는다.
- `refreshKey` 는 `web/src/components/connector/connector-connection-panel.tsx` 가 `connection` 을 넘긴다. 연결 해제나 다시 등록 뒤에 바뀐다.
- 다시 읽는 단추는 `web/src/components/chat/approval-list.tsx` 의 `reloads` 상태를 의존 배열에 넣는 방식을 따른다.
- 안내 상자는 `web/src/components/ui/notice.tsx` 의 `Notice` 를 쓴다. 화면 문구는 해요체다(`web/AGENTS.md` 의 「화면 문구」).

**근거 문서**: `docs/flow.md` 의 「커넥터 연결」 절 끝의 허락 표, `docs/connectors.md` 의 상시 허락 응답

## 의도 메모

- 읽는 동안 옛 허락을 먼저 지우지 않는다. 성공하면 곧 바뀌고, 지우면 화면이 깜빡인다. 실패가 확정된 때에만 지운다.
- 읽지 못한 상태에서는 허락이 하나도 없던 연결에도 안내를 보인다. 처음 읽기와 다시 읽기를 같은 원칙으로 다룬다.
- 「다시 묻기」 의 실패 안내(`error` 상태)는 그대로 둔다. 읽기 실패와 다른 상태다.

## 작업 항목

### 1. `web/src/components/connector/connector-grants.tsx`

- 읽기 실패를 뜻하는 상태 `unavailable`(boolean)과 다시 읽기를 일으키는 `reloads`(number)를 더한다. `reloads` 를 `useEffect` 의 의존 배열에 넣는다.
- 읽기 결과를 받았고 `stale` 이 아니면:
  - 성공: `grants` 를 그 연결의 허락으로 바꾸고 `unavailable` 을 거짓으로 한다.
  - 실패: `grants` 를 빈 배열로 바꾸고 `unavailable` 을 참으로 한다.
- 그리는 규칙:
  - `unavailable` 이 참이면 `data-testid="connector-grants"` 인 절에 제목 「묻지 않고 실행하는 동작」 과
    `Notice variant="warning"`, `data-testid="connector-grants-unavailable"`, 문구 「허락 상태를 확인하지 못했어요.」 를 보인다.
    그 아래 `Button size="sm" variant="outline"`, `data-testid="connector-grants-retry"`, 글자 「다시 확인」 을 두고 누르면 `reloads` 를 1 올린다.
  - `unavailable` 이 거짓이고 `grants` 가 비면 지금처럼 `null` 이다.
  - 그 밖은 지금 목록 그대로다.
- 컴포넌트 주석의 「허락이 없거나 읽지 못하면 아무것도 그리지 않는다」 를 새 동작으로 고친다.

### 2. 이 phase 를 검증하는 `test/browser/connector-connection.spec.ts`

기존 「연결을 해제하면 허락 목록을 다시 읽어 거둔 허락이 사라진다」 검사 뒤에 둘을 더한다. 같은 파일의 `DEMO_ID`, `ready`, `disconnected` 를 쓴다.

- 「연결 해제 뒤 허락을 다시 읽지 못하면 옛 허락을 지우고 읽지 못했다고 알린다」:
  허락 하나를 돌려주다가, 연결 해제 뒤에는 `**/api/connector-grants` 가 500 과 `{ code: "INTERNAL_ERROR" }` 를 돌려주게 한다.
  해제 전 `connector-grant` 가 1개, 해제 뒤 `connector-grant` 가 0개이고 `connector-grants-unavailable` 이 「허락 상태를 확인하지 못했어요.」 를 보인다.
  이어 응답을 `[]` 로 바꾸고 `connector-grants-retry` 를 누르면 `connector-grants` 가 0개다.
  그 사이 `DELETE /api/connector-grants/*` 요청이 한 번도 나가지 않았는지 확인한다.
- 「허락을 처음 읽지 못해도 읽지 못했다고 알린다」:
  처음부터 500 을 돌려주면 `connector-grants-unavailable` 이 보이고 `connector-grant` 가 0개다.

## 검증

```bash
# cwd: 저장소 root
cd web && pnpm typecheck && pnpm lint && pnpm format:check
cd web && pnpm test:browser connector-connection
```

- 둘 다 종료 코드 0 이다. 브라우저 검사는 `connector-connection.spec.ts` 의 모든 검사가 모바일과 데스크톱 폭에서 통과한다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/components/connector/connector-grants.tsx` | 수정 |
| `test/browser/connector-connection.spec.ts` | 수정 |
