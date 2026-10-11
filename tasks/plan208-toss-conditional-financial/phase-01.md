# Phase 01. 조건주문 snapshot의 실제 소비 계약

**Execution profile**: deep

## 목표

실제 Python prepare 소비와 Spring snapshot 저장·읽기가 조건주문 leg를 엄격히 검증하게 한다.

**범위 외**: SDK 금융 전송, 실제 업무 producer 연결, 공개 승인 화면·hook, 운영 등록과 배포다.

## 컨텍스트

**근거 문서**: `docs/features/connector.md`의 「조건주문 REST 쓰기의 구현 보완안」이다.
기준 clean SHA는 3f0f3c4c185ea3e74f5954ef890d78a945b3bfff다. 실제 main을 포함한 새 checkout에서 정의를 다시 대조한다.
connector_guard_validation._prepare_result와 ConnectorExecutionSnapshot.validate/capture/open/requestKey를 직접 읽는다.
대조한 local main은71ab1c685712c1a88478b90c576cff380c59a35f, origin/main은3f0f3c4c185ea3e74f5954ef890d78a945b3bfff다. 둘 다 금융 SDK·조건 조회·dormant producer가 없다. 이 phase의 snapshot 소비자는 존재하므로 현재 실행 가능하다.
일반 정규화 값 변경 결손은 별도 writer가 수정 중이다. 그 source를 동시에 편집하지 않고 반영된 main에서 일반 회귀를 보존한다.
현재 양쪽 SUMMARY는 일반 주문만 받으며 Spring requestKey는 문자열만 받는다. 중첩 leg를 범용 JSON으로 받게 변경하지 않는다.
ConnectorExecutionSnapshot은 현재 497줄이므로 책임 추가 전에 순수 일반 주문 검증을 별도 클래스로 옮겨 파일 상한을 지킨다.

## 의도 메모

- 기존 snapshot의 암호화·AAD·원문 SHA와 requestKey 의미, 일반 주문 vector는 유지한다.
- 새 table·migration·grant·허용 boolean·wire 권한 필드는 없다. marker는 데이터 검증 분기이며 실행 권한이 아니다.
- 이 관심사만 설계·구현·회귀를 한 PR로 묶는다. 이동만 하는 커밋과 조건 동작 변경 커밋은 분리하고 각 커밋이 회귀를 통과한다.

## 작업 항목

### 1. 테스트를 먼저 실패시킨다

신규 `test/resources/conditional-snapshot-v1.json`에 CREATE SINGLE/OCO/OTO, MODIFY 타입 전환·PROFIT_RATE→SINGLE STOP 전체 재설정, CANCEL STOP/PROFIT_RATE·OTO first 완료/second 감시, KR quantity=0.5와 기대 실행 원문·summary·scope·의도 키를 둔다.
양쪽 실제 소비자가 같은 vector를 읽게 하고 조건 정상 입력이 현재 거절되는 red를 확인한다.
추가/중복 키, number decimal, false 외 동의, 금액/timeInForce, leg 방향/가격 누락, 날짜 오류, Cs, marker 변경을 거절하는 vector도 둔다.
null/부재를 바꾼 expected 원주문, 목표수익률을 100으로 나눈 표시, leg 순서 변경, normalization 누락·추가도 거절한다.
공통 fixture는 modelArgsJson/executionArgsJson/summaryJson/scopeJson의 정확한 UTF-8 문자열과 각 SHA, 의도 키 expected를 갖는다. JSON 객체만 저장하고 재직렬화한 값을 원문으로 쓰지 않는다.
CASE-NORM-DECIMAL은002→2/00100.00→100을 수용하고002→200/00100.00→200을 거절한다. CASE-NORM-IMMUTABLE은 enum/date/ID/orderSide 변경과 first/second 교환·배열화를 거절한다.
CASE-MARKET은7개 StockInfo.market과 currency를 문서의 KR/US로 대응해 summary/원주문 동일성을 검사한다. 잘못된 통화 조합과 execution.market=KR/US를 거절한다.
CASE-SCOPE는 문서의 전체 예약 집합이 Python/Spring에서 같은지 검사하며 정확히 account_seq만 받는다. CASE-LIMIT-AAD는16/16/8/2 KiB와 nesting5, 중복/trailing JSON, 다중 byte, keyId/column/action/user AAD 변조를 검사한다.

### 2. 엄격한 조건 분기와 원문 저장

connector_conditional_validation.py에 validate_conditional_prepare(payload, original_args, operation, scope)를 새로 정의한다.
_prepare_result는 외피·scope/env 검사를 공통으로 유지하고 summary.orderKind가 CONDITIONAL일 때만 위 함수를 호출한다.
정확한 키·타입·LIMIT/MARKET·SINGLE/OCO/OTO·normalization 규칙은 근거 문서대로 구현한다. before/after의 모델·실행 대응뿐 아니라 canonical(before)==after를 강제한다. 숫자 값·enum/date/ID/orderSide/leg 배치를 바꾸지 않는다. CANCEL의 공식 조회 leg는 STOP/PROFIT_RATE 모두 보존한다.
connector_guard.RESERVED와 Spring RESERVED_SCOPE_NAMES를 근거 문서의 전체 집합으로 맞춘다. orderKind/triggerPrice/orderPrice/triggeredOrderId/createdAt를 빠뜨리지 않는다. 조건 scope_fields와 scope는 account_seq only다.
ConnectorOrderSnapshot은 기존 일반 주문의 순수 검증 로직만 이동한 신규 클래스다. 저장·암호화 책임을 옮기지 않는다.
ConnectorConditionalSnapshot은 신규 순수 검증 클래스이며 validate와 requestKey를 제공한다. mutable JsonNode를 외부 맥락 DTO로 보관하지 않는다.
ConnectorExecutionSnapshot은 공통 JSON 상한/원문 보존/암호화와 capture/open을 유지하고 검증·의도 키만 정확한 분기에 위임한다.
requestKey는 문서의 fos-conditional-intent-v1과 operation별 고정 경로·UTF-8 byte LP·S/A/N/O 표현을 사용한다. CREATE symbol과 MODIFY/CANCEL conditionalOrderId를 포함하고 KEY-AAPL/MSFT/MODIFY/CANCEL의 expected hash를 직접 검사한다. clientOrderId 변경과 현재 상태 변화로 동일 의도를 우회하지 못한다.
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
규모는 scripts/pr-size.mjs의 production 추가+삭제 합계로 측정한다. 이동270줄은 삭제270+추가270=540줄이며 rename 감지로 총량을 줄였다고 가정하지 않는다.
변경 파일 표의 운영 배분은1310줄, 여유190줄이다. Java500/Python·TS400과 기존 baseline을 지키고 baseline을 늘리지 않는다. 실제 포맷 뒤 numstat와 파일 줄 수로 다시 판정한다.
함수 계약은 `ConnectorOrderSnapshot.validate(input,args,summary,scope,operation):void`, `ConnectorConditionalSnapshot.validate(input,args,summary,scope,operation):void`, `ConnectorConditionalSnapshot.requestKey(args,userId,connectionId,tool,operation):String`이다. input/args/summary/scope는 엄격히 파싱한 JsonNode이며 별도 DB/암호화 책임은 없다.
Python `validate_conditional_prepare(payload,original_args,operation,scope)`는 검증 성공 시 payload를 반환하고 실패 시 기존 ValueError를 던진다. _prepare_result의 공통 외피/env/scope 검사 뒤 호출한다.
KEY-* 및 CASE-*는 신규 Python·Spring 시험의 동일 fixture ID다. 실제 DB 중복 차단은 phase03의 DB-INTENT이며 여기서는 키 계산과 snapshot 검증만 완료했다고 보고한다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `hermes/plugins/dashboard-profile-api/connector_guard_validation.py` | 수정 (추가+삭제30, 최종237줄 이내) |
| `hermes/plugins/dashboard-profile-api/connector_guard.py` | 수정 (추가+삭제40, 최종240줄 이내) |
| `hermes/plugins/dashboard-profile-api/connector_conditional_validation.py` | 신규 (300줄 이내) |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorExecutionSnapshot.java` | 수정 (삭제270+추가40=310, 최종267줄 이내) |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorOrderSnapshot.java` | 신규 (이동270줄 이내) |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorConditionalSnapshot.java` | 신규 (360줄 이내) |
| `test/resources/conditional-snapshot-v1.json` | 신규 |
| `hermes/tests/test_connector_conditional_validation.py` | 신규 (380줄 이내) |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorConditionalSnapshotTest.java` | 신규 (480줄 이내) |
| `docs/features/connector.md` | 수정 |
