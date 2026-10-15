# Phase 04. 상한 화면과 승인 카드 표시

**Execution profile**: standard

## 목표

「외부 서비스 연결」 의 금융 연결 화면에서 상한과 오늘 쓴 금액을 보고 한 번 더 확인한 뒤 바꾼다. 주문 승인 카드는 오늘 남은 한도를, 상한 변경 카드는 바뀌는 값과 최대치를 보인다.

**범위 외**: backend 와 MCP 도구(phase 01~03).

## 컨텍스트

- 연결 화면은 `web/src/components/connector/connector-connection-panel.tsx` 다. 파일 길이 기준에 가까우니 새 절은 별도 컴포넌트 파일로 두고 패널에는 몇 줄만 더한다. 금융 연결인지는 `connector.tools` 에 위험도 `FINANCIAL` 도구가 있는가로 가린다.
- 화면 코드와 문구에 서비스 이름을 적지 않는다(`test/unit/connector-neutral.test.ts`).
- 서버 라우트는 `web/src/app/api/connector-grants/route.ts` 처럼 Control Plane 을 부르고 응답 계약의 필드만 복사한다. 복사 도우미는 `web/src/lib/connector-action-route.ts` 의 `connectorActionCall` 과 `safeFinancial` 이 본보기다.
- 카드 요약은 `web/src/components/chat/financial-summary.tsx` 와 `web/src/lib/financial-summary.ts`, 목록과 「모두 승인」 묶음은 `web/src/components/chat/approval-list.tsx` 다. 금융 줄은 묶지 않는다.
- 오류 문구는 `web/src/lib/connector-action.ts` 의 `CONNECTOR_ACTION_ERROR_MESSAGES` 가 갖고, 여기 있는 코드만 브라우저로 넘어간다.
- 도구 진행 표시는 `web/src/lib/tool-label.ts` 가 갖는다.
- 화면 문구는 해요체다(`web/AGENTS.md` 의 「화면 문구」). 확인 창 제목만 질문형이다.
- backend API 는 phase 02 의 `GET /api/v1/financial-limits`, `PUT /api/v1/financial-limits/{name}` 이고, 목록 응답은 phase 02 의 `financial.limits`, phase 03 의 `limitChange` 를 싣는다.

**근거 문서**: `docs/adr/ADR-20261015-financial-user-limits.md`, `docs/features/tossinvest-connector.md` 의 「주문 상한」

## 의도 메모

- 화면에서 올리는 것은 계좌 주인의 직접 조작이라 승인 카드를 만들지 않고, 저장 전에 확인 창을 한 번 띄운다. 낮추기도 같은 확인 창을 쓴다.
- 최대치를 넘는 값은 입력 단계에서 막고, 서버가 `FINANCIAL_LIMIT_ABOVE_MAXIMUM` 을 내도 같은 문구를 보인다.
- 상한 변경 카드는 금융 줄처럼 「모두 승인」 에 넣지 않는다. 상시 허락과 대화 허락 선택지도 그리지 않는다.
- 모양이 틀린 요약은 지금 금융 요약처럼 그 줄만 승인할 수 없게 낮춘다.

## 작업 항목

### 1. 상한 API 서버 라우트와 타입

- `web/src/lib/financial-limits.ts` 신규: 응답 타입 `FinancialLimits`(`available`, `limits[]`: `name`, `currency`, `value`, `maximum`, `custom`, `spentToday`, `remainingToday`), 다섯 이름의 한국어 이름표(「한 건 원화 금액」 등), 브라우저 쪽 `fetchFinancialLimits()` 와 `saveFinancialLimit(name, value)`.
- `web/src/lib/financial-limits-route.ts` 신규: Control Plane 응답에서 계약의 필드만 복사하는 `safeFinancialLimits`. 이름은 다섯 값만, 금액은 `^[0-9]{1,14}(\.[0-9]{1,6})?$` 만 받는다. 모양이 틀리면 던진다.
- `web/src/app/api/financial-limits/route.ts` 신규 `GET`, `web/src/app/api/financial-limits/[name]/route.ts` 신규 `PUT`. 이름이 다섯 값이 아니거나 값 모양이 틀리면 Control Plane 을 부르지 않고 400 이다.

### 2. 연결 화면의 상한 절

- `web/src/components/connector/financial-limits.tsx` 신규 `FinancialLimitsSection`. 종류마다 지금 값, 최대치, 직접 정했는지를 보이고, 오늘 쓴 금액과 남은 한도를 통화별로 보인다. 값을 고치고 저장하면 `ConfirmDialog` 로 「주문 상한을 바꿀까요?」 를 묻고, 확인하면 저장한다. 「최대치로 되돌리기」 는 `value: null` 을 보낸다. `available=false` 면 절을 그리지 않는다.
- `connector-connection-panel.tsx` 는 연결이 있고 위험도 `FINANCIAL` 도구가 있을 때 이 절을 그린다.

### 3. 승인 카드

- `web/src/lib/financial-summary.ts` 와 `web/src/lib/connector-action-route.ts` 의 `safeFinancial` 에 `limits`(`currency`, `orderLimit`, `quantityLimit`, `dailyLimit`, `spentToday`, `remainingToday`)를 더한다. null 은 null 로 둔다.
- `web/src/components/chat/financial-summary.tsx` 는 `limits` 가 있으면 「오늘 남은 한도」 와 하루 상한, 한 건 상한을 보이고, 주문 금액이 남은 한도나 한 건 상한을 넘으면 어느 상한을 넘는지 경고로 보인다. 승인 단추는 그대로 두고 서버가 판정한다.
- `web/src/lib/connector-action.ts` 의 줄 타입에 `limitChange` 를 더하고, `connector-action-route.ts` 에 `safeLimitChange` 를 둔다.
- `web/src/components/chat/limit-change-summary.tsx` 신규: 「{종류 이름} {previous} → {requested} {통화}」, 최대치, 지금 상한을 보인다. 모양이 틀리면 그 줄을 승인할 수 없게 낮춘다.
- `approval-card.tsx` 가 `limitChange` 가 있는 줄에 이 요약을 그리고, `approval-list.tsx` 는 그 줄을 「모두 승인」 묶음에서 뺀다.
- `CONNECTOR_ACTION_ERROR_MESSAGES` 에 `FINANCIAL_LIMIT_EXCEEDED`(「주문 상한에 걸려 승인하지 못했어요. 카드에서 남은 한도를 확인해 주세요.」)와 `FINANCIAL_LIMIT_ABOVE_MAXIMUM`(「최대치보다 큰 값은 정할 수 없어요.」)을 더한다.
- `web/src/lib/tool-label.ts` 에 `order_limit_read`(「주문 상한을 읽고 있어요」/「주문 상한을 읽었어요」), `order_limit_change`(「주문 상한 변경을 요청하고 있어요」/「주문 상한 변경을 요청했어요」)를 더한다. 바꾼 것처럼 읽히지 않게 쓴다.

### 4. 이 phase 를 검증하는 테스트

- `test/web/financial-limits-route.test.ts` 신규: 계약 밖 필드를 버리고, 틀린 이름과 값은 Control Plane 을 부르지 않고 400 이다.
- `test/web/connector-action-route.test.ts` 수정: `financial.limits` 와 `limitChange` 를 복사하고, 모양이 틀리면 그 줄만 승인할 수 없게 낮춘다.
- `test/unit/tool-label.test.ts` 수정: 두 도구의 표시가 「요청했어요」 이고 「바꿨어요」 가 아니다.
- `test/browser/approval-card.spec.ts` 수정: 주문 카드가 오늘 남은 한도를 보이고, 상한 변경 카드는 바뀌는 값을 보이며 「모두 승인」 에 들지 않는다.
- `test/browser/connector-connection.spec.ts` 수정: 금융 연결 화면에서 상한을 바꾸면 확인 창이 뜨고, 확인해야 PUT 이 나간다. 최대치보다 큰 값은 저장 단추가 막힌다.

## 검증

```bash
cd web && pnpm lint && pnpm format:check && pnpm typecheck
cd web && node --test ../test/web/financial-limits-route.test.ts ../test/web/connector-action-route.test.ts
node --test test/unit/tool-label.test.ts test/unit/connector-neutral.test.ts
cd web && pnpm test:browser test/browser/approval-card.spec.ts test/browser/connector-connection.spec.ts
scripts/dev/check-local.sh --fast
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/lib/financial-limits.ts` | 신규 |
| `web/src/lib/financial-limits-route.ts` | 신규 |
| `web/src/app/api/financial-limits/route.ts` | 신규 |
| `web/src/app/api/financial-limits/[name]/route.ts` | 신규 |
| `web/src/components/connector/financial-limits.tsx` | 신규 |
| `web/src/components/connector/connector-connection-panel.tsx` | 수정 |
| `web/src/lib/financial-summary.ts` | 수정 |
| `web/src/lib/connector-action-route.ts` | 수정 |
| `web/src/lib/connector-action.ts` | 수정 |
| `web/src/lib/tool-label.ts` | 수정 |
| `web/src/components/chat/financial-summary.tsx` | 수정 |
| `web/src/components/chat/limit-change-summary.tsx` | 신규 |
| `web/src/components/chat/approval-card.tsx` | 수정 |
| `web/src/components/chat/approval-list.tsx` | 수정 |
| `test/web/financial-limits-route.test.ts` | 신규 |
| `test/web/connector-action-route.test.ts` | 수정 |
| `test/unit/tool-label.test.ts` | 수정 |
| `test/browser/approval-card.spec.ts` | 수정 |
| `test/browser/connector-connection.spec.ts` | 수정 |
