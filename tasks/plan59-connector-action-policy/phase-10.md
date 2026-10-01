# Phase 10. 대화의 승인 카드와 상시 허락 화면

**Execution profile**: standard

## 목표

승인 요청이 생긴 대화에 카드를 보이고, 주인이 인자를 읽고 승인하거나 거절한다. 승인하면서 그 도구를 정한 기간 동안 묻지 않게 할 수 있고, 준 허락을 연결 화면에서 거둔다.

**범위 외**: 새 시각 방향. 지금 토큰과 부품으로 단순하게 만든다.

## 컨텍스트

- 대화 화면은 `web/src/components/chat-panel.tsx` 의 `ChatPanel` 이다. `MessageList` 와 `Composer` 를 그린다. 대화 단위 SSE 는 `applyConversationEvent` 가 받고 `system` 사건을 알림 줄로 더한다. `belongsToObservedTurn` 이 폴링 중인 turn 의 사건을 버리되 `system` 은 늘 받는다. 이 파일은 이미 1400줄이므로 새 로직은 따로 둔다
- 사건 타입은 `web/src/lib/chat-event.ts` 의 `ChatEvent.type` 이다. phase 09 가 `type: "approval"`, `detail: <actionId>` 사건을 낸다
- 대화 안 카드의 본보기는 `web/src/components/chat/ask-card.tsx` 다. 겉은 `rounded-lg border border-border bg-muted/40 p-4`, 단추는 `Button size="sm"` 이다
- Control Plane 호출은 `web/src/lib/control-plane.ts` 의 `callControlPlane<T>(path, { method, body })`, 서버 라우트의 본문 읽기는 `web/src/lib/json-body.ts` 의 `readJsonBody`, 대화 번호 검사는 `@/lib/conversation-id` 의 `isConversationId` 다. 브라우저 쪽 호출 함수의 본보기는 `web/src/lib/connection.ts` 의 `call<T>` 다. 부품에서 전역 `fetch` 를 쓰지 않는다
- phase 08 의 경로: `GET /api/v1/chat/conversations/{conversationId}/connector-actions`, `POST /api/v1/connector-actions/{actionId}/approve`(`{grant}`), `POST /api/v1/connector-actions/{actionId}/reject`, `GET /api/v1/connector-grants`, `DELETE /api/v1/connector-grants/{grantId}`. 응답 칸은 `actionId, connectorId, toolName, title, risk, status, argsJson, resultText, errorCode, createdAt, expiresAt, grantAllowed`
- 브라우저 검사의 본보기: `test/browser/chat-delegation-wake.spec.ts`(`routeEvents` 로 SSE 를 대역), `test/browser/ask-card.spec.ts`
- `web/AGENTS.md`: 해요체, 인라인 `style` 금지, 내부 용어를 사용자 문구에 쓰지 않는다

**근거 문서**: `docs/connectors.md` 의 「승인」, `docs/flow.md` 의 「승인이 필요한 호출」, `docs/adr/ADR-048-커넥터-쓰기는-control-plane-이-승인-줄을-저장하고-승인한-인자로-한-번만-실행한다.md`, `docs/adr/ADR-009-에이전트의-답은-신뢰하지-않는-글로-그린다.md`

## 의도 메모

- 카드는 Control Plane 응답으로만 그린다. 모델의 답에 든 글로 만들지 않는다
- 인자는 마크다운으로 그리지 않는다. `<pre>` 안의 글로 보인다. 인자에 든 글이 화면을 꾸미지 못하게 한다
- 카드는 입력창 위에 모아 둔다. 메시지 사이에 끼우면 이력을 다시 읽을 때 자리를 정할 근거(메시지 번호)가 없다
- 끝난 승인 줄은 카드로 보이지 않는다. 결과는 알림 줄과 답으로 이미 대화에 있다. `UNKNOWN` 만 예외로 한 번 더 보인다

## 작업 항목

### 1. 서버 라우트와 호출 함수

| web 라우트 | Control Plane |
| --- | --- |
| `web/src/app/api/chat/conversations/[conversationId]/connector-actions/route.ts` GET | `/api/v1/chat/conversations/{id}/connector-actions` |
| `web/src/app/api/connector-actions/[actionId]/approve/route.ts` POST | `/api/v1/connector-actions/{id}/approve` |
| `web/src/app/api/connector-actions/[actionId]/reject/route.ts` POST | `/api/v1/connector-actions/{id}/reject` |
| `web/src/app/api/connector-grants/route.ts` GET | `/api/v1/connector-grants` |
| `web/src/app/api/connector-grants/[grantId]/route.ts` DELETE | `/api/v1/connector-grants/{id}` |

- `actionId` 는 UUID 모양, `grantId` 는 양의 정수인지 라우트가 먼저 본다. 아니면 400 `VALIDATION_FAILED`
- `approve` 의 본문은 `{ grant }` 이고 `grant` 는 `null`, `"HOUR"`, `"TODAY"`, `"DAYS_30"` 만 넘긴다
- 응답은 계약의 칸만 복사한다(`connection-route.ts` 의 `safe*` 방식). 모르는 오류 코드는 `INTERNAL_ERROR` 로 바꾸고 원문 메시지를 버린다

`web/src/lib/connector-action.ts`(신규):

```ts
export type ConnectorActionStatus = "PENDING" | "EXECUTING" | "SUCCEEDED" | "FAILED" | "UNKNOWN" | "REJECTED" | "EXPIRED";
export type GrantPeriod = "HOUR" | "TODAY" | "DAYS_30";
export type ConnectorAction = { actionId: string; connectorId: string; toolName: string | null; title: string; risk: ToolRisk | null;
  status: ConnectorActionStatus; argsJson: string | null; resultText: string | null; errorCode: string | null;
  createdAt: string; expiresAt: string | null; grantAllowed: boolean };
export type ConnectorGrant = { grantId: number; connectorId: string; toolName: string; expiresAt: string };
```

함수 `readConnectorActions(conversationId)`, `approveConnectorAction(actionId, grant)`, `rejectConnectorAction(actionId)`, `readConnectorGrants()`, `revokeConnectorGrant(grantId)`, `prettyArgs(argsJson: string | null): string`(JSON 으로 읽히면 2칸 들여쓰기, 아니면 원문), `connectorActionErrorMessage(code)`.

| 코드 | 글 |
| --- | --- |
| `CONNECTOR_ACTION_NOT_PENDING` | `이미 처리된 요청이에요.` |
| `CONNECTOR_ACTION_NOT_FOUND` | `요청을 찾지 못했어요.` |
| 그 밖 | `요청을 처리하지 못했어요. 잠시 뒤 다시 시도해 주세요.` |

### 2. 카드

`web/src/components/chat/approval-card.tsx`(신규): `export function ApprovalCard({ action, onChanged }: { action: ConnectorAction; onChanged(next: ConnectorAction): void })`.

- 겉은 `<section data-testid="approval-card" data-status={action.status}>` 와 `ask-card` 와 같은 클래스다
- 제목 줄에 `action.title`, 그 옆에 위험도 `Badge variant="outline"`(`toolRiskLabel`. `risk` 가 null 이면 `쓰기`)
- 안내 글 `에이전트가 이 동작을 하려고 해요. 내용을 확인해 주세요.`
- 인자는 `<pre data-testid="approval-args">` 에 `prettyArgs(action.argsJson)`. 길면 카드 안에서 세로로 스크롤한다(`max-h-48 overflow-auto`). 가로로 넘치지 않게 `whitespace-pre-wrap break-words`
- `PENDING` 일 때 단추 셋이다
  - `승인`(`data-testid="approval-approve"`): `approveConnectorAction(actionId, null)`
  - `거절`(`variant="outline"`, `data-testid="approval-reject"`)
  - `grantAllowed` 가 참이면 `승인하고 묻지 않기`(`variant="ghost"`, `data-testid="approval-grant"`)가 `DropdownMenu` 를 열고 항목 `1시간 동안`, `오늘 하루`, `30일 동안` 이 각각 `HOUR`, `TODAY`, `DAYS_30` 으로 승인한다
- 부르는 동안 단추를 `loading` 으로 막는다. 실패하면 카드 안 `role="alert"` 에 `connectorActionErrorMessage` 를 보인다. `CONNECTOR_ACTION_NOT_PENDING` 이면 목록을 다시 읽게 `onChanged` 를 부른다
- `EXECUTING` 이면 `실행하는 중이에요` 를 `role="status"` 로 보이고 단추를 없앤다
- `UNKNOWN` 이면 `실행했는지 알 수 없어요. 그 서비스에서 확인해 주세요. 다시 실행하지 않아요.` 를 보이고 단추를 없앤다

`web/src/components/chat/approval-list.tsx`(신규): `export function ApprovalList({ conversationId, refreshKey }: { conversationId: string | null; refreshKey: number })`.

- `conversationId` 나 `refreshKey` 가 바뀌면 `readConnectorActions` 로 다시 읽는다. 대화가 없으면 아무것도 그리지 않는다
- `PENDING`, `EXECUTING`, `UNKNOWN` 인 줄만 카드로 그린다. 하나도 없으면 아무것도 그리지 않는다(빈 상태 글 없음)
- 읽기가 실패하면 조용히 비운다. 대화를 막지 않는다
- 겉은 `<div data-testid="approval-list" className="flex flex-col gap-3 px-4">`

### 3. `ChatPanel` 연결

- `web/src/lib/chat-event.ts` 의 `ChatEvent.type` 에 `"approval"` 을 더한다
- `chat-panel.tsx`: 상태 `approvalRefresh`(number)를 두고 `applyConversationEvent` 가 `approval` 사건과 `system` 사건을 받을 때 1 올린다. `belongsToObservedTurn` 이 `approval` 을 `system` 처럼 늘 받게 한다. `applyTurnEvent` 는 `approval` 을 무시한다
- `Composer` 바로 위에 `<ApprovalList conversationId={...} refreshKey={approvalRefresh} />` 를 둔다. 이 파일에 더하는 줄은 이것들뿐이다

### 4. 연결 화면의 상시 허락

`web/src/components/connector/connector-grants.tsx`(신규): `export function ConnectorGrants({ connectorId }: { connectorId: string })`. `readConnectorGrants()` 에서 그 커넥터의 것만 보인다.

- 없으면 아무것도 그리지 않는다
- 제목 `묻지 않고 실행하는 동작`, 줄마다 도구 이름, `<만료 시각>까지`, 단추 `다시 묻기`(`data-testid="grant-revoke"`)
- `ConnectorConnectionPanel` 의 도구 목록 아래에 둔다

### 5. 이 phase 를 검증하는 `test/browser/approval-card.spec.ts`(신규)

대화는 `page.request.post("/api/chat", ...)` 로 실제로 만든다. 승인 줄은 backend 가 브라우저 검사에서 만들 수 없으므로 `page.route` 로 web API 를 대역한다. SSE 는 `chat-delegation-wake.spec.ts` 의 `routeEvents` 방식으로 `approval` 사건을 준다.

| 테스트 | 기대 |
| --- | --- |
| 승인 줄이 없는 대화 | `approval-list` 가 없다 |
| `PENDING` 줄 하나 | 카드에 제목, `쓰기`, 인자의 키와 값이 보인다 |
| 인자에 `<b>굵게</b>` 와 `**굵게**` | 글자 그대로 보이고 `b`, `strong` 요소가 없다 |
| 승인을 누른다 | `approve` 요청의 본문이 `{"grant":null}`. 응답이 `SUCCEEDED` 면 카드가 사라진다 |
| `승인하고 묻지 않기` 에서 `오늘 하루` | 본문이 `{"grant":"TODAY"}` |
| `grantAllowed: false` | `approval-grant` 가 없다 |
| 거절을 누른다 | `reject` 요청이 가고 카드가 사라진다 |
| 승인이 409 `CONNECTOR_ACTION_NOT_PENDING` | `이미 처리된 요청이에요.` 가 보이고 목록을 다시 읽는다 |
| `UNKNOWN` 줄 | `실행했는지 알 수 없어요` 가 보이고 단추가 없다 |
| 열린 대화에 `approval` 사건이 온다 | 목록을 다시 읽어 카드가 나타난다 |
| 390px 폭, 긴 인자 | `document.documentElement.scrollWidth <= window.innerWidth` |

`test/browser/connector-connection.spec.ts` 에 더한다: 허락이 하나 있으면 `묻지 않고 실행하는 동작` 과 `grant-revoke` 가 보이고, 누르면 `DELETE` 요청이 가고 줄이 사라진다. 허락이 없으면 제목이 없다.

### 6. `docs/` 갱신

- `docs/prd.md` 의 「범위와 확인 방법」 표 끝에 한 줄을 더한다: `| 외부 서비스에 쓰는 일은 사용자가 승인한 것만 실행한다 | 승인이 필요한 도구를 에이전트가 불러도 그 서비스에 요청이 가지 않고, 승인하면 승인한 인자 그대로 한 번만 실행된다. 도구 호출마다 판정이 남는다 |`
- `docs/code-architecture.md` 의 「web 화면 구조」 의 디렉터리 설명에 `approval-card`, `approval-list`, `connector-grants` 를 더한다
- `docs/flow.md` 의 「승인이 필요한 호출」 에 화면 상태 한 줄씩(카드가 어디 뜨는지, 승인한 뒤 무엇이 보이는지)을 더한다

## 검토 반영

**이 절이 위의 내용과 다르면 이 절을 따른다.**

- `docs/prd.md` 에 더하는 줄의 둘째 칸은 `승인이 필요한 도구를 에이전트가 불러도 그 서비스에 요청이 가지 않고, 승인하면 승인한 인자 그대로 한 번만 실행된다` 까지다. 같은 인자의 `PENDING` 이 있어 새 줄을 만들지 않은 호출은 줄이 남지 않으므로 「호출마다 판정이 남는다」 를 적지 않는다

## 검증

브라우저 검사는 한 번에 하나만 돈다. 돌리기 전에 코디네이터가 준 대기 스크립트를 먼저 실행한다(경로는 실행 지시가 준다).

```bash
# cwd: 저장소 root
pnpm --dir web typecheck
pnpm --dir web lint
pnpm --dir web format:check
(cd web && pnpm test:browser approval-card.spec.ts)
(cd web && pnpm test:browser connector-connection.spec.ts)
node --test 'test/unit/**/*.test.ts'
! grep -rn 'style={{' web/src/components/chat/approval-card.tsx web/src/components/chat/approval-list.tsx web/src/components/connector/connector-grants.tsx
scripts/check-public-safe.sh
```

- 모두 종료 코드 0

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/app/api/chat/conversations/[conversationId]/connector-actions/route.ts` | 신규 |
| `web/src/app/api/connector-actions/[actionId]/approve/route.ts` | 신규 |
| `web/src/app/api/connector-actions/[actionId]/reject/route.ts` | 신규 |
| `web/src/app/api/connector-grants/route.ts` | 신규 |
| `web/src/app/api/connector-grants/[grantId]/route.ts` | 신규 |
| `web/src/lib/connector-action.ts` | 신규 |
| `web/src/lib/chat-event.ts` | 수정 |
| `web/src/components/chat/approval-card.tsx` | 신규 |
| `web/src/components/chat/approval-list.tsx` | 신규 |
| `web/src/components/chat-panel.tsx` | 수정 |
| `web/src/components/connector/connector-grants.tsx` | 신규 |
| `web/src/components/connector/connector-connection-panel.tsx` | 수정 |
| `test/browser/approval-card.spec.ts` | 신규 |
| `test/browser/connector-connection.spec.ts` | 수정 |
| `docs/prd.md` | 수정 |
| `docs/code-architecture.md` | 수정 |
| `docs/flow.md` | 수정 |
