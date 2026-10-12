# Phase 04. 일반 주문 개방 연결 (사용자 명시 승인 뒤에만)

**Execution profile**: deep

## 목표

kill switch 가 꺼진 기본 상태를 유지한 채, 켜면 `create_order` 와 `cancel_order` 가 승인 카드에서 한 건씩 실행되도록 공개 진입점을 잇는다.
**이 단계가 매매를 여는 PR 이다.** 소유자가 개방을 명시적으로 승인한 뒤에만 PR 을 연다. 계획 작성자나 구현 워커가 스스로 시작하지 않는다.

**범위 외**: `modify_order`, 조건주문 세 쓰기, 일괄 승인, 상시 허락, UNKNOWN 의 새 요청, 실제 토스 API 호출(배포 뒤 소유자가 한다)이다.

## Blocked 조건

- 금융 관문 `ConnectorFinancialGate`(kill switch, 계좌 허용 목록, 상한)와 phase-03(request_key 계좌 범위, keyed hash)이 main 에 있어야 한다.
- 소유자의 개방 승인 기록이 코디네이터에게 있어야 한다. 없으면 PHASE_BLOCKED 로 멈추고 묻는다.

## 컨텍스트

2026-10-13 main 에서 금융 실행은 다섯 겹으로 막혀 있다.

| 차단 | 위치 | 이 단계에서 |
| --- | --- | --- |
| 판정이 RISK_NOT_OPEN | `ToolPolicyDecision.decide` | kill switch 가 켜져 있고 tossinvest 의 두 도구일 때만 producer 로 보낸다. 꺼지면 지금처럼 RISK_NOT_OPEN 이다 |
| 공개 approve 가 다시 판정해 NOT_EXECUTABLE | `ConnectorActionApproval` 의 재판정, `ConnectorActionController` | 금융 줄은 `ConnectorFinancialApproval` 로 보낸다 |
| plugin 이 FINANCIAL 을 거절하고 always 도구를 모델 목록에서 뺀다 | `connector_prepare.py`, `connector_manifest.py`, `connector_guard.py` | 준비 도구 `prepare_trade` 만 모델에게 보이고, 실행 도구는 승인 뒤 claim 으로만 부른다 |
| manifest 에 금융 도구와 `execution_guard` 가 없다 | `hermes/connectors/tossinvest/connector.json` | `prepare_trade`(READ), `create_order`·`cancel_order`(FINANCIAL/always)와 `execution_guard`(scope `account_seq` → `account`)를 더한다 |
| 운영 서버가 거래 도구를 등록하지 않는다 | `hermes/connectors/tossinvest/src/server.ts` | `registerTradingTools` 를 부르되 `modify_order` 는 등록하지 않는다 |

승인 카드는 모델 인자만 보인다. 저장한 summary(계좌 끝 네 자리, 종목, 방향, 수량, 가격, 정규화)를 카드에 보여야 승인한 내용과 전송 내용이 같은지 사람이 확인할 수 있다.

## 작업 항목

1. kill switch 꺼짐에서 지금과 같은 RISK_NOT_OPEN 과 금융 전송 0회를 확인하는 회귀를 먼저 둔다.
   켜짐이어도 digest key 파일이 없거나, 열린 `approval-claim-v1` 줄이 남아 있으면 같은 결과인지 함께 확인한다. phase-02 와 phase-03 선행을 이 테스트가 지킨다.
2. 판정, 승인 API, plugin, manifest, 서버 등록을 위 표대로 잇는다. 켜짐 분기는 `ConnectorFinancialGate` 를 반드시 지난다.
3. 승인 카드에 summary 를 보이고, summary 를 열지 못하면 승인 단추를 막는다.
   계좌 표시는 summary.account 가 아니라 저장 scope 의 `account_seq` 에서 만든다. 그 값은 연결의 공개 칸 `account` 와 대조되고 phase-02 허용 목록을 지난 실제 전송 계좌다.
   summary.account 는 prepare 가 낸 끝 네 자리라 Control Plane 이 전송 계좌와 대조하지 못한다. 공개 칸은 계좌 순번이라 끝 네 자리를 Control Plane 이 계산할 수도 없다.
   카드는 계좌 순번을 보이고, summary.account 는 계좌 근거로 보이지 않는다. 다른 계좌의 scope 로 만든 summary 가 카드에 같은 계좌로 보이지 않는지 테스트한다.
4. 하루 합계를 계좌 단위로도 묶는다. phase-02 의 합계는 사용자 단위라 같은 계좌를 두 사용자가 각자 연결하면 상한이 사용자 수만큼 늘어난다.
   허용 목록은 계좌 기준이므로, 사용자 합계와 함께 (실행 scope 의 계좌, KST 날짜) 합계도 같은 상한으로 본다. 다른 사용자의 줄은 그 사용자의 데이터 key 로 열어 금액만 읽는다.
   가족이 한 계좌를 함께 연결하지 않기로 소유자가 정하면, 대신 허용 목록 항목에 사용자를 함께 적어 한 계좌를 한 사용자에게만 연다. 개방 승인 때 둘 중 하나를 고른다.
5. loopback 토스 대역으로 준비 → 승인 → claim → 전송 → 결과를 한 번 왕복하고, 재승인과 replay 가 추가 전송 0회인지 센다.
6. `tossinvest-connector.md` 의 「이 커넥터는 주문하지 않는다」, 도구 표, 보안 절을 바꾸고 「매매 개방 조건」 표를 모두 채운다. README 사용자 안내도 함께 고친다.

## 검증

```bash
cd backend && ./gradlew test --tests '*Financial*' --tests '*ConnectorPolicyEndpointTest' --tests '*ConnectorActionExecutionTest'
cd hermes/connectors/tossinvest && bun run typecheck && bun test ./tests
PYTHONPATH=hermes/tests python3 -m unittest test_connector_guard_binding test_connectors_contract
scripts/dev/check-local.sh --fast
```

배포 뒤 첫 실제 호출은 `tossinvest-connector.md` 의 「첫 실제 호출 절차」 를 따른다. 테스트 통과를 실제 거래 성공으로 보고하지 않는다.
