# Phase 06. 실제 DB producer와 조건주문 왕복

**Execution profile**: deep

## 목표

실제 dormant 업무 producer의 조건 snapshot 저장·승인·일회 claim과 실제 Python·SDK 소비를 H2/MySQL에서 연결한다.

**범위 외**: 운영 등록·공개 hook·approve·카드 화면·일괄 승인·상시 grant·새 권한 flag·UNKNOWN 새 요청·실거래·배포다.

## 컨텍스트

**근거 문서**: `docs/features/tossinvest-connector.md`의 「조건주문 REST 쓰기의 구현 보완안」과 `docs/features/connector-policy.md`의 「금융 실행 내용의 저장과 검증」이다.
#481 로 ConnectorFinancialProducer/ConnectorFinancialApproval/ConnectorFinancialConflicts 가 main 에 있다. #507 로 승인 본문은 `ConnectorActionBodies` 로 암호화하고 claim 미소비 실패는 FAILED 로 끝난다.
실제 producer 는 package-private request와 approve를 테스트 내부에서 호출하며, phase-04 가 공개 진입점을 연결한 뒤에도 조건주문 도구는 운영에 등록하지 않는다.
이 phase는 그 실제 업무 구현을 재사용한다. 착수 시 선행 통합된 조건 snapshot의 검증 분기를 사용하므로 generic JSON producer나 별도 테스트 금융 policy를 추가하지 않는다.
sourceContext라는 현재 없는 DTO를 가정하지 않는다. 준비 전 실제 producer가 복사한 owner/profile/connection/binding/revision/manifest 맥락을 직접 관측한다.

## Blocked 조건

- 커밋된 guard 설치 맥락 전달·일반 금융 모듈·실제 dormant producer와 조건 SDK가 actual main에 있어야 한다. merged 상태, merge SHA 조상 여부와 실제 정의를 확인한다.
- 실제 producer의 request/approve가 조건 summary를 기존 capture/open/ticket/executeApproved로 전달하지 못하면 PHASE_BLOCKED로 실패 식별자를 보고한다. 별도 fake producer로 통과하지 않는다.
- actual main의 기본 verify_task 0, 독립 계획 리뷰 반영과 별도 구현 dispatch를 확인한다.
- Docker/MySQL·실제 Python MCP 환경이 없으면 PHASE_BLOCKED다. H2만 통과하거나 skip한 상태를 완료로 세지 않는다.
- 이 통합의 선행 조건은 SDK·Python 합성 claim 관심사를 막지 않는다.

## 의도 메모

- 외부 금융 목적지만 loopback이며 승인 caller만 테스트에서 연결한다. 실제 공개 승인 API를 통과하는 E2E라고 보고하지 않는다.
- 예상 추가 운영 코드는0줄이며 아직 검증되지 않은 예산 가정이다. producer가 기존 API로 조건을 수용하지 못하면 오류를 보고하고 별도 검토에서 수정 파일·예산을 확정한다.
- 이 관심사도 운영1500줄과 파일 상한을 지킨다. 새 migration과 baseline 증가는 없다.

## 작업 항목

### 1. 실제 producer 테스트와 red-green

신규 `backend/src/test/java/com/bifos/assistant/connector/application/FinancialConditionalProducerRoundTripTest.java`를 connector.application 패키지에 둔다.
실제 service/repository/cipher/request/approve를 사용하고 fake NEEDS_APPROVAL/action status/summary를 주입하지 않는다.
조건 계약을 받지 못하는 일반 주문 환경에서 red를 확인하고 실제 조건 소비 계약·SDK 연결 뒤 green을 확인한다.
이미 조건을 받아 red가 나지 않으면 replay 또는 snapshot 변조 회귀의 실패를 먼저 확인한다.

### 2. prepare·저장·승인·claim 연결

신규 `test/e2e/financial/conditional_plugin_http.py`는 조건 SDK의 테스트 원본 생성 명령을 호출해 임시 connector를 설치한다.
실제 Python loader SHA를 fresh Spring catalog/bind.guard/support에 같이 전달한다. manifest/SHA를 Java·Python에서 다시 만들지 않는다.
기존 공용 HermesConnectorClient mock은 내부에서 만든 실제 HTTP client로 위임한다. 새 Spring context·TestBean·가짜 검증 endpoint를 만들지 않는다.
실제 request의 TX 밖 prepare1회, 사용자 잠금 아래 현재 맥락 재검증과 PENDING+암호화 snapshot+notification의 같은 TX 커밋을 관측한다.
CASE-PREPARE-BUDGET은 실제 Spring prepareClient의 read timeout2초를 사용한다. cold auth/429로 prepare 응답을 지연시키거나1 MiB 초과 응답을 주입하고 저장 action/snapshot/notification0·금융0을 확인한다. 늦은 prepare 결과도 저장하지 않는다.
원래 모델 인자를 유지하고 실행 원문·summary·scope·두 revision을 불변으로 저장한다. 저장 summary의 모든 leg·단위를 실제 open 결과와 대조한다.
approve가 저장 원문을 쓰고 EXECUTING 커밋 뒤 ticket1회·executeApproved1회를 호출하는지 확인한다. 승인 때 prepare·UUID 생성은0회다.
실제 Spring claim HTTP의 서명 인증과 consumeTicket 커밋 뒤에만 SDK가 금융 요청을 보내게 연결한다. 실제 인증·DB 소비는 이 테스트의 책임이다.
DB-INTENT는 clientOrderId만 변경하거나 expected_conditional_order만 변경해도 동일 의도 PENDING/EXECUTING/UNKNOWN이 차단되는지 실제 conflicts 구현으로 확인한다. CREATE AAPL/MSFT는 서로 다른 키여서 차단하지 않고 MODIFY/CANCEL의 다른 대상 ID도 구분한다. 사용자 잠금과 실제 H2/MySQL DB를 쓰며 가짜 conflicts 결과를 주입하지 않는다. supersedes_unknown_action_id는 NULL이다.

조건주문 금액 규칙을 phase-02 gate 에 더한다. leg 마다 `quantity × max(orderPrice, triggerPrice)` 를 한 건 금액으로 보고 OTO 는 두 leg 중 큰 값을 쓴다. MARKET 조건주문은 주문가가 없어 계속 거절한다.
하루 합계에는 생성만 그 금액으로 넣고 정정은 바뀐 뒤 금액으로 바꿔 센다. 이 규칙이 들어오기 전까지 gate 는 조건 생성과 정정을 닫는다.

### 3. 수신·실패 주입·MySQL 회귀

정상 CREATE/MODIFY 금융 POST1, CANCEL DELETE1, replay 추가 전송0회를 목적지에서 센다. auth·GET·claim counter는 구분한다.
MODIFY 새 ID, CREATE optional/null clientOrderId, CANCEL 빈204를 실제 결과 DB 상태와 대조한다.
원문/summary 변조·추가 키·숫자 decimal·scope/env·owner/profile/revision/manifest 변경·철회·ticket 만료·claim replay는 금융0회다.
현재 원주문의 leg 상태·targetProfitRate·triggeredOrderId·expireDate 부재가 바뀌면 금융0회다. OTO first 완료/second 감시 CANCEL과 PROFIT_RATE→STOP MODIFY, KR decimal 수량은 새 prepare 당시의 지원 vector로 왕복한다.
429/5xx/끊김/timeout/불량2xx는 DB UNKNOWN, 공식 명백 거절은 FAILED, 정상은 SUCCEEDED다. UNKNOWN 뒤 ticket/UUID 교체와 추가 전송0회를 확인한다.
동일 의도 경쟁, claim 직전 철회, 금융 성공 뒤 결과 저장 실패와 기동 정리 경쟁을 bounded latch로 주입한다.
늦은 결과가 정산된 줄을 바꾸지 않고 프로세스 재기동이 재실행하지 않는지 확인한다.
신규 `backend/src/test/java/com/bifos/assistant/connector/application/FinancialConditionalProducerMysqlRoundTripTest.java`는 기존 ConnectorExecutionClaimMysqlTest의 mysql 태그·공용 설정으로 같은 테스트를 상속한다.
운영 금융 목록0개·exclude·RISK_NOT_OPEN/noGrant와 일반 금융/READ 회귀를 보존한다.

## 검증

```bash
cd backend && ./gradlew test --tests '*FinancialConditionalProducerRoundTripTest' --tests '*ConnectorActionExecutionTest' --tests '*ConnectorExecutionClaimTest' --tests '*ConnectorExecutionClaimMvcTest' --tests '*ToolPolicyDecisionTest'
scripts/checks/check-mysql-migration.sh --tests '*FinancialConditionalProducerMysqlRoundTripTest'
cd hermes/connectors/tossinvest && bun test ./tests
PYTHONPATH=hermes/tests python3 -m unittest test_connector_conditional_sdk test_connector_conditional_validation test_connector_guard_binding test_connectors_contract
cd backend && ./gradlew qualityCheck
node scripts/checks/check-file-length.mjs
```

H2/MySQL에서 신설 테스트가 실행되고 실제 DB producer→Python→SDK→목적지와 replay0회가 확인되어야 완료다. 합성 claim 증거와 구분해 보고한다.
scripts/checks/check-mysql-migration.sh는 set -Eeuo pipefail로 실패를 전달하며 모든 인자를 mysqlMigrationTest에 넘긴다. backend/build.gradle.kts의 이 task는 mysql 태그를 포함한다. 신규 MySQL 클래스에는 같은 태그를 붙이며 필터와 실제 실행 건수를 함께 확인한다.
테스트가 기본 업무 API로 통과하면 운영 변경 없이 이 관심사를 끝낸다. 표의 신규 테스트는 Java500/Python400줄 이하로 배분하고, 실제 선행 코드가 조건을 거절하면 운영0줄 가정을 폐기해 실패 파일·수정 예산을 별도 검토한다. 없는 producer를 이 phase에서 fake로 채우지 않는다. 운영 허가와 실제 거래는 완료 조건이 아니다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/test/java/com/bifos/assistant/connector/application/FinancialConditionalProducerRoundTripTest.java` | 신규 (480줄 이내) |
| `backend/src/test/java/com/bifos/assistant/connector/application/FinancialConditionalProducerMysqlRoundTripTest.java` | 신규 (80줄 이내) |
| `test/e2e/financial/conditional_plugin_http.py` | 신규 (360줄 이내) |
| `docs/features/tossinvest-connector.md` | 수정 |
