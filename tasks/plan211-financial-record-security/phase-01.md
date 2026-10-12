# Phase 01. 소비하지 않은 금융 ticket 의 줄을 결과 불명 대신 실패로 끝낸다

**Execution profile**: deep

## 목표

금융 승인 줄을 `EXECUTING` 에서 끝내는 세 자리가 실행 줄의 `consumed_at` 을 잠근 채 보고, 비어 있으면 `FAILED`, 차 있으면 `UNKNOWN` 으로 끝낸다.
execute 의 오류 상세 없는 `unavailable` 실패(대시보드의 `400`, `401`, `404`, `409` 포함)도 `consumed_at` 이 차 있으면 `UNKNOWN` 으로 남긴다.

**범위 외**: 인자와 결과의 암호화(phase 02), request_key 의 연결 번호 문제, `UNKNOWN` 의 명시적 새 요청, 공개 승인 카드와 hook 개방. 거래는 계속 `RISK_NOT_OPEN` 이다.

## 컨텍스트

**근거 문서**: `docs/adr/ADR-20261012-financial-unsent-proof.md`(판정 표와 증명 사슬), `docs/adr/ADR-20261010-financial-execution-guard.md`, `docs/features/connector-policy.md` 의 「운영 비활성 금융 snapshot producer」

- 끝내는 세 자리
  - `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorActionLifecycle.java` 의 `markInterrupted`(기동 정리, `ConnectorActionSweeper` 가 `ApplicationReadyEvent` 에서 부른다)와 `markStale`
  - `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorFinancialApproval.java` 의 결과 기록 트랜잭션(`executed == null` 이면 `unknown`)
- claim 은 `ConnectorExecutionClaims.claim` 이 사용자, 승인 줄, 실행 줄 순서로 잠그고 `ConnectorExecutionTicket.requireExecutable`(상태 `EXECUTING`)을 본 뒤 `consumeTicket` 한다. 발급(`issue`)도 같은 순서와 같은 상태 검사를 한다
- 기동 정리는 웹 서버가 뜬 뒤 돌므로 그 사이에 Hermes 의 claim 이 들어올 수 있다. 지금 `markInterrupted` 는 잠금 없이 한 트랜잭션에서 `findByStatus` 로 고친다
- 대시보드의 `409` 는 `connector_run.py` 에서 자식을 띄우기 전의 보호 맥락 실패에만 쓴다. Java 는 `HttpHermesConnectorGuard.execute` 가 `400`, `401`, `404`, `execution` 이 있는 `409` 를 `CallResult.failure(UNAVAILABLE)` 로 바꾼다

## 의도 메모

- 판정 함수는 하나다. `connector.application` 에 package-private `ConnectorFinancialOutcome`(가칭)을 두고, 이미 잠근 승인 줄과 실행 줄, 결과(`CallResult` 또는 null)를 받아 상태를 고친다. 세 자리가 모두 이것을 부른다
- 금융 줄인지는 실행 줄(`connector_action_execution`)이 있는지로 안다. 없으면 지금처럼 `unknown` 이다
- 잠금 순서는 사용자, 승인 줄, 실행 줄이다. 기동 정리도 줄마다 새 트랜잭션에서 이 순서로 잡는다. 줄 하나가 실패해도 나머지를 계속하고, 실패한 줄은 다음 기동이나 `markStale` 이 다시 본다
- `FAILED` 의 `error_code` 는 `unavailable` 이고 `result_text` 는 null 이다. 보내지 않은 것이므로 판단 피드백은 `EXECUTION_FAILED` 로 남긴다
- 성공을 받았는데 `consumed_at` 이 비어 있으면 `SUCCEEDED` 로 적고 action 번호만 담아 경고한다. 성공을 실패로 바꾸지 않는다
- 승인 결과 트랜잭션은 지금 사용자와 승인 줄만 잠근다. 실행 줄 잠금을 더한다

## 작업 항목

1. `ConnectorFinancialOutcome` 을 더하고 ADR 의 판정 표를 그대로 구현한다
2. `ConnectorFinancialApproval` 의 결과 기록이 실행 줄을 잠그고 이 함수를 부르게 한다
3. `ConnectorActionLifecycle.markInterrupted` 와 `markStale` 을 줄마다 사용자, 승인 줄, 실행 줄 순서로 잠그는 트랜잭션으로 바꾸고, 금융 줄은 이 함수로 끝낸다. 금융이 아닌 줄의 동작은 그대로다
4. `docs/features/connector-policy.md` 의 「운영 비활성 금융 snapshot producer」 의 남은 문제 목록에서 둘째와 넷째 줄을 지우고 ADR 을 가리킨다. 결과 저장 규칙 문장(「명백한 미전송·공식 거절은 FAILED …」)에 `consumed_at` 판정을 더한다
5. `backend/docs/data-schema.md` 의 「connector_action_execution」 에 `consumed_at` 이 끝낸 상태를 정한다는 한 줄을 더한다

## 테스트

`backend/src/test/java/com/bifos/assistant/connector/` 아래, `FinancialConnectorProducerTest` 와 같은 패키지 접근 방식을 쓴다. 실제 Toss API 는 부르지 않는다.

- 판정 표의 여섯 줄을 각각 확인한다. 기동 정리, `markStale`, 승인 결과 기록 세 자리 모두에 대해 `consumed_at` 빔과 참을 나눈다
- ticket 을 발급하지 않은 줄, 발급했지만 claim 하지 않은 줄이 기동 정리 뒤 `FAILED` 이고 같은 의도의 새 준비를 막지 않는다
- 기동 정리 뒤 그 줄의 원래 ticket 으로 claim 하면 거절되고 금융 POST 가 0회다(mock 목적지의 호출 수)
- claim 과 기동 정리가 겹친다. claim 이 먼저 커밋하면 `UNKNOWN`, 정리가 먼저 커밋하면 claim 이 거절되고 `FAILED` 다. `CountDownLatch` 로 두 순서를 모두 만든다
- 기동 정리를 두 번 돌려도 상태와 알림이 한 번만 바뀐다
- execute 가 `409` 를 돌려줬는데 `consumed_at` 이 차 있으면 `UNKNOWN` 이다
- MySQL 에서 잠금 순서를 확인하는 경우는 `FinancialConnectorProducerMysqlRoundTripTest` 에 claim 과 정리의 겹침 하나를 더한다

## 검증

```bash
cd backend && ./gradlew test --tests '*FinancialConnectorProducer*' --tests '*ConnectorExecutionClaim*' --tests '*ConnectorActionLifecycle*' --tests '*ConnectorActionSweeper*'
scripts/check-mysql-migration.sh --tests '*FinancialConnectorProducerMysqlRoundTripTest'
scripts/check-local.sh --fast
```

`scripts/` 아래 경로가 바뀌었으면 main 의 경로를 따른다. 셋 다 실패 없이 끝난다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorFinancialOutcome.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorFinancialApproval.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorActionLifecycle.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/FinancialUnsentOutcomeTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/FinancialConnectorProducerMysqlRoundTripTest.java` | 수정 |
| `docs/adr/ADR-20261012-financial-unsent-proof.md`, `docs/adr/INDEX.md` | 신규, 수정 |
| `docs/features/connector-policy.md`, `backend/docs/data-schema.md` | 수정 |
