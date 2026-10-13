# Phase 03. 승인 카드의 금융 요약과 개방 문서

**Execution profile**: standard

## 목표

승인 카드가 금융 줄의 저장 요약과 실제 전송 계좌 순번을 보이고, 요약을 열지 못하면 승인 단추를 그리지 않는다.
기능 문서와 커넥터 규칙 문서를 「매매가 열린 지금」 으로 고치고, 배포 뒤 첫 실제 호출 절차를 소유자 결정에 맞춘다.

**범위 외**: Control Plane 과 커넥터 코드(phase-01, phase-02), 실제 토스 API 호출, 운영 값 입력.

## 컨텍스트

- phase-01 이 승인 줄 응답(`ActionView`)에 `financial` 필드를 더했다. 금융 줄이 아니면 null 이다. 모양은 `backend/src/main/java/com/bifos/assistant/connector/application/model/FinancialActionSummary.java` 다.
  `available` 이 거짓이면 나머지 칸은 null 이고 `normalization` 은 빈 배열이다.
- 화면의 승인 줄 타입은 `web/src/lib/connector-action.ts` 의 `ConnectorAction` 이다.
  서버 라우트 거르개 `web/src/lib/connector-action-route.ts` 의 `safeAction` 은 계약에 있는 칸만 복사한다. 원고 요약은 `safeContentDraft` 가 칸마다 검증하고 모양이 틀리면 고정 오류로 바꾼다.
- 카드는 `web/src/components/chat/approval-card.tsx` 다. 가려진 인자가 있으면 `blocked` 가 참이 되어 승인 단추를 그리지 않고 거절만 남긴다(`HiddenArgsNotice`). 원고 카드(`content-draft-save-card.tsx`)도 snapshot 을 읽지 못하면 승인 단추를 그리지 않는다.
- `web/src/components/chat/approval-list.tsx` 의 `grouped()` 는 같은 도구의 PENDING 줄을 묶는다. `bulkApprovable` 은 이미 `WRITE` 만 받는다.
- 화면 문구는 해요체이고 안내는 `Notice`, 상태는 `Badge` 의미 색이다(`web/AGENTS.md`). 「금액은 관리자 영역에서만」 규칙은 모델 비용을 뜻한다. 사용자가 승인할 주문 금액은 일반 카드에 보인다.
- 계좌는 summary 의 끝 네 자리가 아니라 `financial.accountSeq` 로 보인다. Control Plane 이 끝 네 자리를 계산할 수 없고, 끝 네 자리는 커넥터가 낸 값이라 계좌 확인의 근거가 아니다.

**근거 문서**: `docs/adr/ADR-20261013-financial-order-opening.md`, `docs/features/tossinvest-connector.md` 의 「매매 개방 조건」 과 「첫 실제 호출 절차」, `docs/features/connector-policy.md` 의 「커넥터별 준비, 승인, 실행, 확인」

## 의도 메모

- 금융 카드를 원고 카드처럼 별도 컴포넌트로 나누지 않는다. 승인 요청이 같은 `approveConnectorAction` 이고, 다른 것은 요약 블록과 막는 조건뿐이다. 블록이 커지면 같은 디렉터리의 `financial-summary.tsx` 로 뺀다.
- 문서에 실제 계좌번호, 금액, 종목, 운영 값을 적지 않는다. 첫 실제 호출의 종목과 금액도 적지 않는다.

## 작업 항목

### 1. 화면 타입과 서버 라우트 거르개

`web/src/lib/connector-action.ts`

- `FinancialSummary` 타입을 더한다. 칸은 `available`, `accountSeq`, `operation`, `symbol`, `market`, `currency`, `side`, `quantity`, `orderAmount`, `price`, `orderType`, `timeInForce`, `orderId`, `normalization: {field, before, after}[]` 이다.
- `ConnectorAction` 에 `financial: FinancialSummary | null` 을 더한다.

`web/src/lib/connector-action-route.ts`

- `safeFinancial` 을 더해 `safeAction` 에서 부른다. 계약 밖 칸은 버린다.
- `available` 이 참일 때 `accountSeq` 는 `^[1-9][0-9]{0,18}$`, `currency` 는 `^[A-Z]{3}$`, `side` 는 `BUY`/`SELL`, `orderType` 은 `LIMIT`/`MARKET`, `operation` 은 `CREATE`/`CANCEL`, 수량과 금액과 가격은 null 이거나 `^[0-9]+(\.[0-9]+)?$` 인 30자 이하 문자열이어야 한다. 아니면 던져 고정 오류로 바꾼다.

### 2. 승인 카드의 금융 요약

`web/src/components/chat/approval-card.tsx`

- `action.financial` 이 있으면 인자 표 대신 요약 블록(`data-testid="approval-financial"`)을 그린다.
  - 「계좌 순번 {accountSeq}」
  - 동작: `CREATE` 는 「주문」, `CANCEL` 은 「주문 취소」. 취소면 「주문 번호 {orderId}」 를 함께 보인다
  - 종목 `symbol`(`market`), 방향 「매수」/「매도」, 주문 유형 「지정가」/「시장가」
  - 수량이 있으면 「수량 {quantity}주」, 금액이 있으면 「금액 {orderAmount} {currency}」, 가격이 있으면 「가격 {price} {currency}」
  - `normalization` 이 있으면 「입력을 이렇게 바꿨어요」 아래에 「{field}: {before ?? '없음'} → {after}」 를 줄마다 보인다
- `blocked` 에 「PENDING 이고 `risk === "FINANCIAL"` 이며, `action.financial` 이 null 이거나 `available` 이 거짓」 을 더한다. 옛 서버이거나 응답 경로에서 요약이 빠져도 인자만 보고 승인하지 못하게 한다. 그때 `Notice variant="warning"`(`data-testid="approval-financial-unavailable"`)로 「주문 내용을 열지 못해 승인할 수 없어요. 거절만 할 수 있어요.」 를 보이고 인자 표는 그리지 않는다.
- PENDING 이 아닌 금융 줄(EXECUTING, UNKNOWN, 끝난 줄)은 `available` 이 참일 때만 요약 블록을 그린다. 거짓이거나 null 이면 요약 블록도 안내도 인자 표도 그리지 않고 상태 표시만 둔다.
- 금융 줄에는 상시 허락과 대화 허락 선택지를 그리지 않는다. 지금도 `grantAllowed` 와 `conversationGrantAllowed` 가 거짓이면 그리지 않으니 그 값에 기댄다.
- 글은 모두 글자 그대로 그린다. HTML 로 해석하지 않는다.

`web/src/components/chat/approval-list.tsx` 의 `grouped()` 는 `action.financial` 이 있거나 `risk === "FINANCIAL"` 인 줄을 묶지 않고 한 장씩 둔다.

### 3. 이 phase 를 검증하는 화면 테스트

- `test/web/connector-action-route.test.ts`: 정상 금융 요약이 칸 그대로 지나가는지, 계약 밖 칸이 버려지는지, `accountSeq` 가 숫자가 아니거나 `side` 가 모르는 값이면 고정 오류인지.
- `test/browser/approval-card.spec.ts`: 픽스처 `action(overrides)` 로
  - 금융 줄이 계좌 순번, 종목, 방향, 금액과 통화를 보이고 승인 단추가 있다
  - `available: false` 인 금융 줄과, `risk: "FINANCIAL"` 인데 `financial: null` 인 줄은 둘 다 승인 단추가 없고 거절만 있으며 안내가 보인다
  - 같은 도구의 금융 PENDING 줄 둘이 묶이지 않고 두 장으로 보인다

### 4. 기능 문서를 지금 동작으로 고친다

`docs/features/tossinvest-connector.md`

- 14행의 「이 커넥터는 주문하지 않는다」 를 「승인한 일반 주문 한 건씩 넣거나 취소한다. 정정과 조건주문은 하지 않는다」 로 고친다.
- 「도구와 정책」 의 도구 수와 표에 `prepare_trade`(READ/none), `create_order`, `cancel_order`(FINANCIAL/always)를 더한다. 모델은 준비 도구만 보고, Control Plane 이 그 호출을 금융 준비로 넘긴다는 흐름을 한 단락으로 적는다.
- `connector_guard.py` 해시 단락(「이 커넥터는 현재 `execution_guard`가 없고 …」)을 지금 선언으로 고친다.
- 「서버와 검사」 의 「나머지 6개 금융 쓰기 operation은 구현·등록하지 않았으며」 를 「일반 주문 생성과 취소 둘을 등록했고 정정과 조건주문 쓰기 넷은 등록하지 않았다」 로 고친다. 「토스증권 REST 전체 지원」 의 같은 문장과 「공식 operation과 producer」 표의 `createOrder`, `cancelOrder` 상태도 고친다.
- 「보안」 의 「돈이 움직이는 도구가 없다」 와 「쓰는 도구가 없다」 를 고친다. 속은 모델이 할 수 있는 것은 승인 요청을 만드는 것이고, 실행은 사람이 카드에서 계좌와 내용을 보고 승인한 한 건뿐이라는 것을 적는다.
- 「매매 개방 조건」: 첫 단락을 「소유자가 2026-10-13 에 개방을 승인했고 개방 PR 이 머지됐다」 로 고치고, 표의 「공개 진입점 연결」 과 「승인 카드 표시」 를 「있음」 으로, 「계좌 허용 목록」 의 지금 칸에 `<계좌 순번>:<사용자 메일>` 형식과 한 계좌 한 사용자를 적는다. 「주문 상한」 칸에 USD 금액 주문은 USD 상한과만 견주고 환율로 바꾸지 않는다는 것을 적는다.
- 「첫 실제 호출 절차」 를 둘로 나눈다.
  1. 소수점 매수: 미국 종목 하나를 소액의 `MARKET` 금액 주문으로 매수한다. 정규장 시작 뒤부터 종료 1시간 전 사이에 넣는다. 즉시 체결될 수 있어 이 주문으로는 취소를 확인하지 않는다
  2. 취소: 소유자가 정한 체결되지 않을 지정가 주문 하나를 넣고 바로 취소한다
  배포 직후 단계에 「외부 서비스 연결 화면에서 토스 연결을 다시 동기화해 바인딩이 새 manifest 의 보호 선언을 받게 한다. 하지 않으면 준비가 모두 거절되고 `financial gate:` 로그에는 그 까닭이 나오지 않는다」 를 둔다.
  관문을 여는 단계 앞에 「금융 해시 key 를 바꾸거나 새로 둘 때는 열린 금융 줄(PENDING, EXECUTING, UNKNOWN)이 없는지 먼저 확인한다」 를 둔다. 확인 방법은 `fos-home-infra` 를 가리킨다. 승인 카드에서 보는 것은 계좌 순번, 종목, 방향, 금액이나 수량과 가격이다.
- 「되돌리기」 에 허용 목록에서 그 항목을 빼면 그 사용자의 그 계좌만 닫힌다는 것을 더한다.
- 「일반 금융 모듈」 의 「운영 `server.ts`는 이 등록 함수를 호출하지 않는다 …」 단락을 지금 동작(운영 서버는 생성과 취소만 등록, `modify_order` 는 테스트 entry 만)으로 고친다.
- 「계획은 `tasks/plan208-toss-conditional-financial/` 이 갖는다」 줄은 지운다. 문서는 계획서를 가리키지 않는다(루트 `AGENTS.md` 「tasks 는 구현 문서만 담는다」). 근거가 필요하면 ADR-20261013 / financial-order-opening 을 가리킨다.
- 「매매 개방 조건」 아래에 카드의 계좌 순번은 사용자에게 익숙한 값이 아니어서, 허용 목록에 그 순번을 적은 운영자와 연결 화면의 계좌 선택지로 대조한다는 한계를 한 줄로 적는다.

`docs/features/connector-policy.md`

- 「도구 호출 판정」 절(`ConnectorPolicyService` 의 Javadoc 이 판정 순서의 소유자로 가리키는 절)에, 커넥터의 `execution_guard.prepare_tool` 을 부른 호출은 판정 전에 금융 준비로 넘기고, 같은 dedupe key 의 금융 줄이면 이르게 돌려주지 않으며, 살펴보기 트리 안이면 넘기지 않는다는 분기를 더한다. 절 이름이 다르면 `grep -n '판정' docs/features/connector-policy.md` 로 그 절을 찾는다.
- 「승인 줄과 경로」 절에, 공개 승인 API 가 위험도 `FINANCIAL` 인 줄을 금융 승인으로 보내고 주인 확인 뒤 허락 입력을 거절한다는 분기를 더한다.
- 「커넥터별 준비, 승인, 실행, 확인」 표의 토스 열: 「지금 운영」 을 열림(관문의 운영 값이 채워졌을 때)으로, 「승인하는 사람」 칸의 「카드의 summary 와 계좌 표시는 아직 없다」 를 계좌 순번과 요약을 보이고 열지 못하면 승인하지 못한다로 고친다.
- Go/No-Go 의 토스 두 항목을 체크한다.
- 「금융 실행 내용의 저장과 검증」 첫 단락의 「현재 `FINANCIAL`과 `DESTRUCTIVE` 호출은 계속 `RISK_NOT_OPEN`으로 거절하며 … 실제 주문 실행은 아직 구현하지 않았다」 를, 실행 도구 직접 호출은 계속 거절하고 준비 도구 호출만 금융 준비로 넘긴다로 고친다.
- 「운영 비활성 금융 snapshot producer」 절 근처의 「controller, MCP hook, 공개 approve, 스케줄러에는 연결하지 않는다」 와 「공개 연결과 승인 카드 summary 표시·일괄 승인 제외는 … 보류한다」 를 지금 연결로 고친다. 스케줄러에는 여전히 연결하지 않는다.

`hermes/connectors/README.md`

- 「`DESTRUCTIVE` 와 `FINANCIAL` 은 선언하지 않는다」 를 「`DESTRUCTIVE` 는 선언하지 않는다. `FINANCIAL` 은 `execution_guard` 의 `operations` 에 든 도구만 선언한다」 로 고치고 근거로 ADR-20261013 / financial-order-opening 을 가리킨다. 검사 표의 「`DESTRUCTIVE` 와 `FINANCIAL` 선언이 없다」 줄도 같이 고친다.

`hermes/docs/code-architecture.md` 의 커넥터 표에서 `tossinvest` 줄의 「주문하지 않는다」 를 「승인받은 일반 주문을 넣거나 취소한다. 정정과 조건주문은 하지 않는다」 로 고친다.

`hermes/docs/hermes-contract.md` 의 「이 소비자는 금융 도구의 exclude를 풀지 않는다. … 금융 호출이 계속 차단된다」 단락을, exclude 는 그대로 두고 모델은 준비 도구만 부르며 실행은 승인 뒤 claim 으로만 한다로 고친다.

## 검증

```bash
cd web && pnpm install --frozen-lockfile && pnpm typecheck && pnpm lint && pnpm format:check
cd web && node --test ../test/web/connector-action-route.test.ts && pnpm test:node
cd web && pnpm test:browser ../test/browser/approval-card.spec.ts
node --test 'test/unit/**/*.test.ts'
```

모두 0 으로 끝나야 한다. 브라우저 테스트가 환경 때문에 돌지 못하면 그 출력과 까닭을 보고에 남긴다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `web/src/lib/connector-action.ts` | 수정 |
| `web/src/lib/connector-action-route.ts` | 수정 |
| `web/src/components/chat/approval-card.tsx` | 수정 |
| `web/src/components/chat/approval-list.tsx` | 수정 |
| `test/web/connector-action-route.test.ts` | 수정 |
| `test/browser/approval-card.spec.ts` | 수정 |
| `docs/features/tossinvest-connector.md` | 수정 |
| `docs/features/connector-policy.md` | 수정 |
| `hermes/connectors/README.md` | 수정 |
| `hermes/docs/hermes-contract.md` | 수정 |
| `hermes/docs/code-architecture.md` | 수정 |
