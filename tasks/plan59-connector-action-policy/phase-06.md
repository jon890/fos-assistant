# Phase 06. 연결 화면이 도구와 위험도, 선언하지 않은 도구를 보인다

**Execution profile**: standard

## 목표

연결 화면에서 그 커넥터의 도구마다 위험도와 승인 방식을 보이고, 선언하지 않은 도구가 있으면 본인과 관리자에게 알린다.
사용자가 연결하기 전에 이 에이전트가 무엇을 바로 하고 무엇을 물어보는지 알아야 하기 때문이다.

**범위 외**: 승인 카드(phase 10).

## 컨텍스트

- 타입은 `web/src/lib/connection.ts` 의 `ConnectorSummary`, `ConnectorConnection`, `AdminConnection` 이다. 서버 라우트는 `web/src/lib/connection-route.ts` 의 `safeSummary`, `safeConnection`, `safeAdmin` 이 계약의 칸만 복사한다. 새 칸은 두 곳을 함께 고쳐야 브라우저까지 간다
- 화면은 `web/src/components/connector/connector-connection-panel.tsx`(`ConnectorConnectionPanel({ id })`)와 `connector-admin-panel.tsx`(`ConnectorAdminPanel({ connectors })`)다
- phase 03 이 Control Plane 응답에 `tools: [{name, title, risk, approval}]`(enum 이름 그대로. `READ`, `NONE` 등)와 `undeclaredTools` 를 더했다
- 부품은 `web/src/components/ui/` 의 `Badge`(variant `default`, `secondary`, `destructive`, `outline`), `Card` 다. 색 토큰은 `web/src/app/globals.css` 가 갖고 경고색 토큰은 없다
- `web/AGENTS.md`: 인라인 `style` 금지, 해요체, 부품에서 전역 `fetch` 금지, 내부 용어(Hermes, profile)를 사용자 문구에 쓰지 않는다
- 브라우저 검사 `test/browser/connector-connection.spec.ts` 는 `page.route` 로 web API 를 대역한다. 고정 데이터는 `demoConnector`, `disconnected`, `pending`, `ready` 다

**근거 문서**: `docs/connectors.md` 의 「도구 정책」, 「선언하지 않은 도구」, 「Control Plane API」, `docs/code-architecture.md` 의 「web 화면 구조」

## 의도 메모

- 새 색 토큰을 만들지 않는다. 위험도는 글과 `Badge` 의 기존 variant 로 나눈다. 새 시각 방향은 따로 진행 중이다
- `DESTRUCTIVE` 와 `FINANCIAL` 도구는 「아직 쓸 수 없어요」 로 보인다. 호출이 늘 거절되기 때문이다
- `schema: 1` 커넥터는 `tools` 가 비어 있다. 이때는 도구 목록 대신 한 줄 안내를 보인다

## 작업 항목

### 1. 타입과 서버 라우트

`web/src/lib/connection.ts`:

```ts
export type ToolRisk = "READ" | "SENSITIVE" | "WRITE" | "DESTRUCTIVE" | "FINANCIAL";
export type ToolApproval = "NONE" | "REQUIRED" | "ALWAYS";
export type ConnectorTool = { name: string; title: string | null; risk: ToolRisk; approval: ToolApproval };
```

- `ConnectorSummary` 에 `tools: ConnectorTool[]`, `ConnectorConnection` 과 `AdminConnection` 에 `undeclaredTools: number` 를 더한다
- `toolPolicyLabel(tool: ConnectorTool): string` 을 더한다

| 조건 | 글 |
| --- | --- |
| `risk` 가 `DESTRUCTIVE` 나 `FINANCIAL` | `아직 쓸 수 없어요` |
| `approval` 이 `NONE` | `바로 실행해요` |
| 그 밖 | `실행 전에 물어봐요` |

- `toolRiskLabel(risk: ToolRisk): string`: `조회`, `민감한 조회`, `쓰기`, `되돌리기 어려운 쓰기`, `결제`

`web/src/lib/connection-route.ts`:

- `safeSummary` 가 `tools` 를 복사한다. 배열이 아니면 빈 배열. 항목은 `name` 이 문자열, `risk` 와 `approval` 이 위 값일 때만 남기고 `title` 은 문자열이 아니면 null
- `safeConnection`, `safeAdmin` 이 `undeclaredTools` 를 복사한다. 0 이상의 정수가 아니면 0

### 2. 화면

`web/src/components/connector/connector-tools.tsx`(신규): `export function ConnectorTools({ tools }: { tools: ConnectorTool[] })`.

- `tools` 가 비었으면 `<p data-testid="connector-tools-empty">` 로 `이 연결은 조회를 뺀 모든 동작을 실행 전에 물어봐요.` 를 보인다
- 아니면 `<ul data-testid="connector-tools">` 에 도구마다 `<li data-testid="connector-tool">` 를 둔다. 왼쪽에 `title ?? name`, 오른쪽에 `Badge` 둘이다. 위험도 글은 `variant="outline"`, 정책 글은 `NONE` 이면 `secondary`, `DESTRUCTIVE`/`FINANCIAL` 이면 `destructive`, 그 밖은 `default`
- 좁은 화면에서 넘치지 않게 `flex-wrap` 과 `min-w-0` 을 쓴다

`ConnectorConnectionPanel` 의 입력 칸 아래에 제목 `할 수 있는 일` 과 `<ConnectorTools tools={connector.tools} />` 를 둔다.
연결 상태의 `undeclaredTools` 가 0 보다 크면 `role="status"` 안내 `이 서비스가 알려 주지 않은 도구 N개는 쓰지 않아요.` 를 `data-testid="connection-undeclared"` 로 보인다.

`ConnectorAdminPanel` 은 지금 `restartRequired || status === "PENDING"` 인 연결만 보인다. `undeclaredTools > 0` 인 연결도 목록에 넣고, 그 줄에 `선언하지 않은 도구 N개` 를 `Badge variant="destructive"` 와 `data-testid="admin-undeclared"` 로 보인다. 반영 완료 단추는 기존 조건일 때만 보인다.

### 3. 이 phase 를 검증하는 `test/browser/connector-connection.spec.ts`

고정 데이터를 고친다. `demoConnector` 에 `tools`(`list_scopes` READ/NONE, `write_note` WRITE/REQUIRED title `메모 쓰기`, `purge_notes` DESTRUCTIVE/ALWAYS)를, `disconnected`, `pending`, `ready` 와 관리자 목록 항목에 `undeclaredTools: 0` 을 더한다.

| 테스트 | 기대 |
| --- | --- |
| 연결 화면을 연다 | `connector-tool` 이 셋. `메모 쓰기` 줄에 `쓰기` 와 `실행 전에 물어봐요`, `list_scopes` 줄에 `바로 실행해요`, `purge_notes` 줄에 `아직 쓸 수 없어요` |
| `tools: []` 인 커넥터 | `connector-tools-empty` 가 보인다 |
| `ready` 에 `undeclaredTools: 2` | `connection-undeclared` 에 `2개` |
| 관리자 목록에 `READY`, `undeclaredTools: 1` 인 연결 | `admin-undeclared` 가 보이고 반영 완료 단추는 없다 |
| 390px 폭 | `document.documentElement.scrollWidth <= window.innerWidth` |

web API 가 `tools` 를 주지 않은 응답(옛 모양)에도 화면이 깨지지 않는지 `tools` 없는 데이터로 한 번 본다. 이때 `connector-tools-empty` 가 보인다.

## 검토 반영

**이 절이 위의 내용과 다르면 이 절을 따른다.**

- `ConnectorAdminPanel` 에서 `READY` 이면서 `undeclaredTools > 0` 인 줄의 상태 글은 `connectionStatusLabel` 이 내는 `연결됨` 이다. 그 줄에는 단추를 두지 않는다. 지금 코드는 `PENDING` 이 아니면 다른 글을 보이므로 조건을 `restartRequired`, `PENDING`, 그 밖의 셋으로 나눈다

## 검증

브라우저 검사는 한 번에 하나만 돈다. 돌리기 전에 코디네이터가 준 대기 스크립트를 먼저 실행한다(경로는 실행 지시가 준다).

```bash
# cwd: 저장소 root
pnpm --dir web typecheck
pnpm --dir web lint
pnpm --dir web format:check
(cd web && pnpm test:browser connector-connection.spec.ts)
node --test 'test/unit/**/*.test.ts'
! grep -rn 'style={{' web/src/components/connector/
```

- 모두 종료 코드 0. `lint` 가 새 위반을 내면 `eslint-suppressions.json` 에 더하지 않고 고친다

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/lib/connection.ts` | 수정 |
| `web/src/lib/connection-route.ts` | 수정 |
| `web/src/components/connector/connector-tools.tsx` | 신규 |
| `web/src/components/connector/connector-connection-panel.tsx` | 수정 |
| `web/src/components/connector/connector-admin-panel.tsx` | 수정 |
| `test/browser/connector-connection.spec.ts` | 수정 |
