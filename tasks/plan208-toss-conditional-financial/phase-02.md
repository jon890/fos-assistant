# Phase 02. 금융 실행 kill switch 와 계좌 허용 목록, 주문 상한

**Execution profile**: deep

## 목표

운영 설정 하나로 금융 실행 전체를 끄고 켤 수 있게 하고, 미리 적은 계좌와 상한 안의 주문만 준비, 승인, claim 을 지나게 한다.
기본값은 모두 닫힌 쪽이다. 이 PR 을 머지해도 주문은 열리지 않는다.

**범위 외**: 공개 진입점 연결(phase-04), request_key 와 해시 변경(phase-03), 조건주문(phase-05, 06), 실제 토스 API 호출이다.

## 컨텍스트

**근거 문서**: `docs/features/tossinvest-connector.md` 의 「매매 개방 조건」, `docs/features/connector-policy.md` 의 「운영 비활성 금융 snapshot producer」 다.
2026-10-13 main 에는 금융 전용 설정이 없다. `ConnectorPolicyProperties`(`assistant.connector.policy`)가 커넥터 판정 설정을 갖는다.
기본 꺼짐 설정의 선례는 `ProactiveScheduleProperties`(`assistant.proactive-check.isolated-execution-enabled`)와 `BrowserProperties` 다.
금융 실행은 세 지점을 지난다: `ConnectorFinancialProducer.request`(준비와 저장), `ConnectorFinancialApproval.approve`(승인과 ticket), `ConnectorExecutionClaims`(claim 소비). 셋 다 같은 검사를 거쳐야 한다.
주문 금액과 수량은 저장된 summary 와 실행 원문에 있다. `ConnectorOrderSnapshot.validate` 가 그 모양을 검증한다.
한 건 금액은 지정가면 `quantity × price`, 시장가면 `orderAmount` 다. 시장가 수량 주문은 금액을 알 수 없으므로 상한이 있으면 거절한다.
**금액을 계산하지 못하는 모양은 모두 거절한다.** 위 두 일반 주문 모양만 허용 목록으로 연다.
조건주문 생성과 정정(`first` 가 있는 실행 원문)은 `price` 와 `orderAmount` 가 없고 금액이 leg 에 나뉘며 MARKET 은 주문가가 없다. 금액 규칙이 정해질 때까지 상한 검사에서 닫는다. 없는 칸을 0 으로 읽으면 상한을 지나간다.

## 의도 메모

- 설정 하나가 금융 실행 전체를 막는다. 도구마다 따로 켜는 장치를 만들지 않는다.
- 꺼짐은 금융 전송 0회로 끝난다. 이미 EXECUTING 인 줄은 claim 에서 거절하고 claim 미소비 증명으로 FAILED 가 된다(#507 규칙).
- 상한과 허용 목록은 운영 설정이다. DB 에 넣지 않는다. 비밀값이 아니지만 계좌 식별자는 공개 저장소에 쓰지 않는다. 값은 운영 환경 변수로 받는다.
- 하루 합계는 KST 날짜로, 그 사용자의 SUCCEEDED 와 EXECUTING, UNKNOWN 금융 줄의 금액 합에 새 주문을 더해 계산한다. UNKNOWN 을 빼면 상한을 우회한다.
- 취소(CANCEL)는 새로 쓰는 금액이 없다. 금액 상한과 하루 합계 검사에서 빼고 kill switch 와 계좌 허용 목록만 본다.
  하루 합계는 금액이 있는 생성 줄만 더한다. 취소를 「금액을 알 수 없음」 으로 읽으면 거절되고, 원주문 금액으로 읽으면 같은 금액이 두 번 들어가 「첫 실제 호출 절차」 의 취소가 막힌다.

## 작업 항목

### 1. 테스트를 먼저 실패시킨다

`ConnectorFinancialGateTest` 를 connector.application 패키지에 두고 실제 producer, approval, claim 을 부른다.
꺼짐 기본값에서 준비 0건 저장, 승인 거절, claim 거절과 금융 전송 0회를 확인한다.
허용 목록에 없는 계좌, 한 건 금액 초과, 수량 초과, 하루 합계 초과, 금액을 알 수 없는 시장가 수량 주문, 조건주문 생성과 정정이 준비에서 거절되는지 확인한다.
상한을 그 주문만 겨우 담게 둔 상태에서 같은 날 그 주문의 취소가 통과하고, 취소 줄이 하루 합계에 들어가지 않는지 확인한다.
켬과 허용 계좌, 상한 안의 주문만 기존 왕복 테스트와 같이 통과하는지 확인한다.

### 2. 설정과 검사

신규 `ConnectorFinancialProperties`(`assistant.connector.financial`)를 둔다. 칸은 `enabled`(기본 false), `allowedAccounts`(기본 빈 목록), `maxOrderAmountKrw`, `maxOrderAmountUsd`, `maxOrderQuantity`, `maxDailyAmountKrw`, `maxDailyAmountUsd` 다.
상한 기본값은 0 이며 0 은 「주문 불가」 다. 켤 때 운영자가 값을 넣는다. 음수와 형식 오류는 기동을 멈춘다.
`LiveProperties` 로 읽어 재기동 없이 끌 수 있으면 그 경로를 쓴다. 안 되면 재기동으로 반영하고 그 사실을 「되돌리기」 에 적는다.
신규 `ConnectorFinancialGate` 가 `requireOpen()`, `requireAccount(account)`, `requireWithinLimits(summary, userId, now)` 를 갖는다.
producer 는 prepare 를 부르기 전에 `requireOpen` 과 `requireAccount` 를, 저장 트랜잭션의 사용자 잠금 아래에서 `requireWithinLimits` 를 부른다.
approval 은 잠금 아래에서 셋을 모두 다시 부른다. claim 은 ticket 소비 직전에 `requireOpen` 과 `requireAccount` 를 부른다.
거절 사유는 기존 `ActionDenyReason` 이나 오류 코드에 하나를 더해 모델에게 「다시 부르지 않는다」 를 알린다.
`application.yml` 에 기본값과 주석을 둔다. 운영 값은 `fos-home-infra` 가 갖는다.

### 3. 문서

`docs/features/tossinvest-connector.md` 「매매 개방 조건」 표의 kill switch, 계좌 허용 목록, 주문 상한 줄을 「있음」 으로 바꾸고 설정 이름을 적는다.
`docs/features/connector-policy.md` 의 producer 절에 세 검사 지점을 적는다.

## 검증

```bash
cd backend && ./gradlew test --tests '*ConnectorFinancialGateTest' --tests '*FinancialConnectorProducerTest' --tests '*ConnectorExecutionClaimTest' --tests '*ConnectorConditionalSnapshotTest'
cd backend && ./gradlew qualityCheck
scripts/dev/check-local.sh --fast
```

꺼짐에서 금융 전송 0회와 준비 저장 0건이 테스트로 보여야 완료다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorFinancialProperties.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorFinancialGate.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorFinancialProducer.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorFinancialApproval.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/execution/ConnectorExecutionClaims.java` | 수정 |
| `backend/src/main/resources/application.yml` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/application/ConnectorFinancialGateTest.java` | 신규 |
| `docs/features/tossinvest-connector.md`, `docs/features/connector-policy.md` | 수정 |
