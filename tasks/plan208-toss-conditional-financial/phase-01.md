# Phase 01. 조건주문 snapshot의 실제 소비 계약

**Execution profile**: deep

## 목표

실제 Python prepare 소비와 Spring snapshot 저장·읽기가 조건주문 leg를 엄격히 검증하게 한다.

**범위 외**: SDK 금융 전송, 실제 업무 producer 연결, 공개 승인 화면·hook, 운영 등록과 배포다.

## 컨텍스트

**근거 문서**: `docs/features/connector.md`의 「조건주문 REST 쓰기의 구현 보완안」이다.
기준 clean SHA는 3f0f3c4c185ea3e74f5954ef890d78a945b3bfff다. 실제 main을 포함한 새 checkout에서 정의를 다시 대조한다.
connector_guard_validation._prepare_result와 ConnectorExecutionSnapshot.validate/capture/open/requestKey를 직접 읽는다.
현재 양쪽 SUMMARY는 일반 주문만 받으며 Spring requestKey는 문자열만 받는다. 중첩 leg를 범용 JSON으로 받게 변경하지 않는다.
ConnectorExecutionSnapshot은 현재 497줄이므로 책임 추가 전에 순수 일반 주문 검증을 별도 클래스로 옮겨 파일 상한을 지킨다.

## 의도 메모

- 기존 snapshot의 암호화·AAD·원문 SHA와 requestKey 의미, 일반 주문 vector는 유지한다.
- 새 table·migration·grant·허용 boolean·wire 권한 필드는 없다. marker는 데이터 검증 분기이며 실행 권한이 아니다.
- 이 관심사만 설계·구현·회귀를 한 PR로 묶는다. 이동만 하는 커밋과 조건 동작 변경 커밋은 분리하고 각 커밋이 회귀를 통과한다.

## 작업 항목

### 1. 테스트를 먼저 실패시킨다

신규 `test/resources/conditional-snapshot-v1.json`에 CREATE SINGLE/OCO/OTO, MODIFY 타입 전환, CANCEL STOP/PROFIT_RATE와 기대 실행 원문·summary·scope·의도 키를 둔다.
양쪽 실제 소비자가 같은 vector를 읽게 하고 조건 정상 입력이 현재 거절되는 red를 확인한다.
추가/중복 키, number decimal, false 외 동의, 금액/timeInForce, leg 방향/가격 누락, 날짜 오류, Cs, marker 변경을 거절하는 vector도 둔다.
null/부재를 바꾼 expected 원주문, 목표수익률을 100으로 나눈 표시, leg 순서 변경, normalization 누락·추가도 거절한다.

### 2. 엄격한 조건 분기와 원문 저장

connector_conditional_validation.py에 validate_conditional_prepare(payload, original_args, operation, scope)를 새로 정의한다.
_prepare_result는 외피·scope/env 검사를 공통으로 유지하고 summary.orderKind가 CONDITIONAL일 때만 위 함수를 호출한다.
정확한 키·타입·LIMIT/MARKET·SINGLE/OCO/OTO·normalization 규칙은 근거 문서대로 구현한다. CANCEL의 공식 조회 leg는 STOP/PROFIT_RATE 모두 보존한다.
connector_guard.RESERVED에 conditionalOrderId/type/expireDate/first/second/expected_conditional_order/targetProfitRate를 더한다.
ConnectorOrderSnapshot은 기존 일반 주문의 순수 검증 로직만 이동한 신규 클래스다. 저장·암호화 책임을 옮기지 않는다.
ConnectorConditionalSnapshot은 신규 순수 검증 클래스이며 validate와 requestKey를 제공한다. mutable JsonNode를 외부 맥락 DTO로 보관하지 않는다.
ConnectorExecutionSnapshot은 공통 JSON 상한/원문 보존/암호화와 capture/open을 유지하고 검증·의도 키만 정확한 분기에 위임한다.
requestKey는 문서의 fos-conditional-intent-v1과 고정 경로·길이 접두사를 사용한다. clientOrderId 변경과 현재 상태 변화로 동일 의도를 우회하지 못한다.
claim codec와 DTO의 ticket/tool/argsSha256/scope 및 응답 네 칸은 바꾸지 않는다.

### 3. 실제 Python·Spring 테스트와 일반 금융 회귀

`hermes/tests/test_connector_conditional_validation.py`는 가짜 검증기 없이 실제 _prepare_result를 호출한다.
`backend/src/test/java/com/bifos/assistant/connector/ConnectorConditionalSnapshotTest.java`는 실제 validate/capture/open과 cipher를 사용한다.
정상 저장의 원문 SHA·암복호화, AAD 오류·다른 key ID, 깊이5/바이트 상한, 저장 summary 변조와 requestKey를 검사한다.
기존 ConnectorActionExecutionTest와 connector guard display/transport/result를 그대로 실행해 일반 금융 계약이 보존되는지 확인한다.
sourceContext라는 기존 정의는 현재 main에 없다. 신규 권한 DTO를 가정하거나 producer 테스트를 범용 JSON fake로 통과시키지 않는다.

## 검증

```bash
PYTHONPATH=hermes/tests python3 -m unittest test_connector_conditional_validation test_connector_guard_display test_connector_guard_transport test_connector_guard_result
cd backend && ./gradlew test --tests '*ConnectorConditionalSnapshotTest' --tests '*ConnectorActionExecutionTest' --tests '*ConnectorExecutionClaimTest' --tests '*ConnectorExecutionClaimMvcTest'
cd backend && ./gradlew qualityCheck
node scripts/check-file-length.mjs
node --test test/unit/doc-files.test.ts test/unit/doc-links.test.ts
```

red의 실패와 green의 실행 건수를 기록한다. Python·Spring이 같은 vector를 소비하고 일반 주문 의도 키가 바뀌지 않아야 완료다.
운영 변경은 이동·삭제를 합쳐 1400줄 이내로 예상하며 1500줄 상한을 넘기지 않는다. Java 500줄/Python 400줄 상한과 baseline을 지킨다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `hermes/plugins/dashboard-profile-api/connector_guard_validation.py` | 수정 |
| `hermes/plugins/dashboard-profile-api/connector_guard.py` | 수정 |
| `hermes/plugins/dashboard-profile-api/connector_conditional_validation.py` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorExecutionSnapshot.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorOrderSnapshot.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorConditionalSnapshot.java` | 신규 |
| `test/resources/conditional-snapshot-v1.json` | 신규 |
| `hermes/tests/test_connector_conditional_validation.py` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorConditionalSnapshotTest.java` | 신규 |
| `docs/features/connector.md` | 수정 |
