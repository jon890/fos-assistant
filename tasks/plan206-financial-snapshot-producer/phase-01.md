# Phase 01. 운영 비활성 금융 snapshot producer

**Execution profile**: deep

## 목표

기존 snapshot·ticket·claim 소비자에 실제 application producer를 연결하고 테스트 내부 호출로 왕복을 검증한다.

**범위 외**: 공개 hook·approve 연결, 금융 도구 노출, 새 API·featureflag·wire 필드, 운영 배포·실거래와 UNKNOWN 명시적 새 요청이다.

## 컨텍스트

**근거 문서**: `docs/features/connector-policy.md`의 「금융 producer의 구현 경계 보완안」, 「원문과 scope」, 「일회성 실행 권한」과 「원문 해시와 중복 의도 키」다.
capture와 issue 및 executeApproved는 현재 운영 caller가 없다. claim와 support는 이미 있는 실제 서비스를 재사용한다.
ToolPolicyDecision과 ConnectorActionApproval.redecide는 FINANCIAL을 RISK_NOT_OPEN으로 막는다. 공개 경로를 열린 것처럼 보고하지 않는다.
코디네이터는 실제 application 규칙 한 구현과 테스트 내부 호출부 연결만 승인했다. 금융 허용을 흉내 내는 별도 정책 구현과 검증용 HTTP API는 금지다.

## Blocked 조건

437, guard 설치와 일반 금융 모듈이 실제 main에 들어와 있어야 한다. PR head의 코드만으로 착수 조건을 통과하지 않는다.
새 main 작업 공간에서 missing symbol 확인, 기본 verify_task 0과 독립 계획 검토 반영 확인 뒤 별도 구현 dispatch로 시작한다.
기본 검사의 0은 현재 소비자와 수정 대상의 존재 확인이다. 설치·일반 금융 모듈의 실제 main 통합 조건을 대신하지 않는다.
새 application 클래스는 아래 계획에서 만드는 식별자다. 이미 있는 서비스라고 가정하지 않는다.
독립 리뷰는 dormant 코드의 유지 비용과 공개 우회 API 위험을 따로 판정한다. 우회가 필요한 구현이면 착수 전에 코디네이터에 보고한다.

## 작업 항목

### 1. 실제 업무 구현과 호출 경계

connector.application 패키지에 package-private ConnectorFinancialProducer와 ConnectorFinancialApproval을 만들고 기존 application/model에는 필요한 불변 반환 타입만 둔다.
Spring component로 생성하되 controller·MCP hook·공개 approve·스케줄러가 호출하지 않는다. 테스트를 같은 application 패키지에 두어 실제 업무 메서드를 호출한다.
새 public 테스트 우회 메서드, fake 금융 policy, allow boolean과 검증 전용 endpoint를 만들지 않는다.
신규 내부 함수 request는 기존 판정과 같은 String profileName/rootSessionId/sessionId/toolCallId/hermesTool/toolName/argsJson을 받고 ConnectorPolicyAnswer를 반환한다.
신규 내부 approve는 CurrentUser와 UUID actionId만 받고 ConnectorActionView를 반환한다. 이는 예정 시그니처이며 현재 main에 있는 함수 이름이 아니다.
producer는 session 실행과 요청자의 바인딩을 실제 조회로 선택하고 owner·profile·READ_ONLY 실행 금지·guard FINANCIAL/always 선언을 검증한다.
업무 규칙은 이 구현 한 곳에 있으며 테스트가 NEEDS_APPROVAL 결과나 실행 상태를 대신 주입하지 않는다.
ToolPolicyDecision은 계속 RISK_NOT_OPEN을 반환한다. producer의 실제 금융 승인 규칙을 기존 공개 판정에 연결하는 작업은 보류한다.

### 2. prepare와 저장

불변 입력 맥락을 읽고 DB TX 밖에서 기존 HermesConnectorClient.prepare를 한 번 호출한다. 기존 원문 args와 해시를 그대로 쓴다.
기존 ConnectorExecutionGuard.read와 ConnectorExecutionSnapshot.validate/capture를 재사용한다.
prepare 결과에서 기존 scope_fields의 공개 값으로 scope를 만든다. 모델에게 binding·profile·scope 소유권을 고르게 하지 않는다.
사용자 행을 먼저 잠근 저장 TX에서 현재 owner·profile·READY·desiredEnabled·두 revision·현재 manifest SHA·scope를 재검증한다.
실제 NEEDS_APPROVAL/FINANCIAL/ALWAYS action의 PENDING 저장과 capture 반환 행 저장을 같은 TX로 처리한다. notification도 같은 TX, 사건은 커밋 뒤다.
기존 TextCipher의 keyId·AAD·암호화와 snapshot 원문 해시를 보존한다. ticket·nonce·credentials는 DB·로그에 남기지 않는다.

ConnectorFinancialConflicts는 신규로 만든다. 기존 findByRequestKeyOrderByActionIdAsc를 사용하고 사용자 잠금 아래 동일 의도의 PENDING·EXECUTING·UNKNOWN을 차단한다.
자기 action은 제외하며 FAILED·REJECTED·EXPIRED의 현재 모델 의미를 보존한다. clientOrderId 변경으로 UNKNOWN을 우회하지 않는다.
이 중복 검사 helper만 public application component로 두어 기존 execution 하위 패키지의 claim에서도 같은 구현을 호출한다.

### 3. 승인과 실행

ConnectorFinancialApproval은 실제 CurrentUser 소유권을 확인하고 grant 입력을 받지 않는다. 매번 별도 승인이며 상시 허락은 만들거나 읽지 않는다.
TX 밖 fresh catalog 확인 후 사용자→action→execution 잠금 순서로 만료·소유권·바인딩·연결·manifest SHA·revision·중복 의도를 다시 검증한다.
검증한 summary와 원문을 사용한다. 승인 때 prepare나 UUID를 다시 만들지 않는다. snapshot 없는 금융 줄은 거절한다.
EXECUTING을 커밋하고 TX 밖에서 기존 ConnectorExecutionTicket.issue를 한 번 부른다.
ticket과 snapshot의 executionArgsJson·executionArgsSha256으로 기존 ConnectorApprovedExecution을 만들고 executeApproved를 한 번 호출한다.
ticket은 60초와 승인 만료 중 빠른 쪽, execute HTTP 읽기 제한은 75초다. 서로 바꾸지 않는다.

기존 ConnectorExecutionClaims.claim의 일회 소비·시간·scope·owner·철회 검사를 재사용한다.
현재 claim에는 동일 의도 검사가 없다. consumeTicket 직전에 사용자 잠금 아래 신규 ConnectorFinancialConflicts 검사를 더한다.
ConnectorExecutionClaimTest의 직접 생성자 세 곳을 새 의존성에 맞추고 기존 발급·소비·실패·재생성 회귀를 실행한다. 공개 claim의 인증과 wire는 바꾸지 않는다.

snapshot에는 manifest SHA 저장 칸이 없다. 새 칸을 가정하지 않고, prepare 전후 fresh catalog SHA 대조와 상태의 verified guard SHA 대조를 사용한다.
승인에서도 현재 상태의 guard를 새 카탈로그와 대조한다. manifest 변경 뒤 resync가 바인딩 revision을 바꾸면 기존 snapshot은 거절된다.
ticket/claim의 현재 카탈로그 의미 검증과 Python의 support·SHA 재대조를 함께 보존한다. SHA만으로 저장된 승인의 권한을 대신하지 않는다.

명백한 미전송/공식 거절은 FAILED, 실행 결과 불명은 UNKNOWN, 성공은 SUCCEEDED와 새 orderId다.
결과 TX는 현재 EXECUTING에만 정산하고 기동 정리가 끝낸 줄을 덮지 않는다. 기존 결과 신호와 피드백 형식도 보존한다.
불확실한 응답과 프로세스 중단 뒤 자동 재실행을 하지 않는다. ticket 재발급도 하지 않는다.
UNKNOWN 명시적 새 요청과 supersedes_unknown_action_id 활성화는 범위 밖이다. 현재 컬럼은 NULL로 남긴다.

### 4. 테스트 내부 통합과 공개 경로 차단 회귀

검사 대상은 `backend/src/test/java/com/bifos/assistant/connector/application/FinancialConnectorProducerTest.java`, `backend/src/test/java/com/bifos/assistant/connector/application/FinancialConnectorProducerRoundTripTest.java`, `backend/src/test/java/com/bifos/assistant/connector/application/FinancialConnectorProducerMysqlRoundTripTest.java`다.
기존 `backend/src/test/java/com/bifos/assistant/connector/ConnectorExecutionClaimTest.java`는 신규 claim 충돌 검사와 생성자 호환을 검증한다.
FinancialConnectorProducerTest는 실제 service·repositories·cipher로 준비 실패 rollback, PENDING+snapshot 원자성, 중복 승인·동일 의도 경쟁을 검증한다.
FinancialConnectorProducerRoundTripTest는 실제 application prepare→저장→승인→ticket, 실제 Spring support/claim HTTP, Python 처리기, 새로 bundle한 금융 모듈 SDK와 loopback 목적지를 잇는다.
승인 호출부만 테스트가 연결한다. 원래 공개 approve API를 통과하는 E2E라고 표현하지 않는다.
실제 DB snapshot/ticket 발급과 Spring claim→Python→SDK→mock 금융 POST 1회, replay 추가 POST 0회는 이 단계의 필수 완료 조건이다.
일반 금융 모듈 단계의 claim loopback 대역 시험을 실제 claim 증거로 대체하지 않는다.

producer_plugin_http.py는 일반 금융 모듈의 tests/fixtures/trading-entry.ts 생성 명령을 호출하여 /tmp 아래 테스트 connector 원본을 받는다.
manifest를 Python이나 Java에서 다시 작성하지 않는다. READ prepare·FINANCIAL/always·guard·scope_fields·SDK entry는 테스트 사본에만 있다.
Python의 실제 connector_manifest._load_connector가 계산한 execution_guard_manifest_sha256을 받아 실제 Spring catalog fixture,
bind.guard와 support에 동일하게 연결한다. SDK는 그 원본의 bundle을 실행하며 SHA 상수나 가짜 loader로 연결을 대신하지 않는다.
manifest 변조와 resync 경쟁에서는 이 실제 연결이 거절되는지 금융 POST 0회로 확인한다.
공용 HermesConnectorClient mock은 테스트 안에서 만든 실제 HTTP client로 위임한다. 다른 Spring context를 만들지 않는다.
MySQL 하위 클래스는 mysql 태그와 공용 MysqlTestDatabase 패턴으로 같은 시험을 상속한다.
누락·추가 키·숫자 타입·원문 SHA·scope/env·revision·manifest 변경·owner/profile 변경·철회·ticket 만료·claim replay는 금융 POST/DELETE 0회다.
정상 mock 승인별 POST 1회, 중복 승인 0회 추가, 429/5xx/timeout/불량2xx의 DB UNKNOWN과 자동 요청 재전송 0회를 목적지에서 센다.
auth POST와 금융 POST/DELETE, Spring 승인 호출 수를 분리한다. 일반 cancel은 POST {}이며 DELETE 0회다.
동일 내용의 새 clientOrderId를 준비해도 UNKNOWN 충돌은 차단된다. claim 전 경쟁과 결과 저장 실패도 검증한다.
공개 hook 정책과 기존 approve 경로는 금융을 계속 차단하며 snapshot 없는 금융 줄은 실행하지 않는다. DESTRUCTIVE도 차단한다.
plugin exclude와 운영 Toss server의 금융 목록 0개를 검사한다. 보안 egress 검사가 실제 목적지 주소 접근을 실패시킨다.

## 책임별 검증 증거

| 책임 | 조건 | 관측값 |
| --- | --- | --- |
| 준비 | 실제 Python 처리기와 금융 SDK가 같은 테스트 원본을 사용한다 | 실제 loader SHA가 catalog·bind·support에 같고 prepare가 한 번 실행된다 |
| 저장 | 사용자 잠금 아래 현재 맥락을 재검증한다 | PENDING과 암호화 snapshot의 원자성, 실패 rollback과 동일 의도 차단을 확인한다 |
| 승인 | 실제 업무 규칙이 EXECUTING을 먼저 커밋한다 | ticket 1회·executeApproved 1회이며 원문과 UUID를 다시 만들지 않는다 |
| claim | 실제 DB 발급 ticket을 Spring HTTP에서 영구 소비한다 | Spring→Python→SDK→mock POST 1회와 replay 추가 POST 0회를 H2/MySQL에서 확인한다 |
| 전송 | 목적지에서 결과와 재전송을 센다 | SUCCEEDED·FAILED·UNKNOWN DB 상태, UNKNOWN 재전송 0회와 auth 별도 counter를 확인한다 |

## 검증

```bash
cd backend && ./gradlew test --tests '*FinancialConnectorProducerTest' --tests '*FinancialConnectorProducerRoundTripTest' --tests '*ConnectorExecutionClaimTest' --tests '*ConnectorExecutionClaimMvcTest' --tests '*ToolPolicyDecisionTest'
scripts/check-mysql-migration.sh --tests '*FinancialConnectorProducerMysqlRoundTripTest' --tests '*RepositoryQueryMysqlTest'
cd hermes/connectors/tossinvest && bun run typecheck
cd hermes/connectors/tossinvest && bun run build
cd hermes/connectors/tossinvest && bun run check:bundle
cd hermes/connectors/tossinvest && bun test ./tests
PYTHONPATH=hermes/tests python3 -m unittest test_connector_guard_binding test_connectors_contract
cd backend && ./gradlew qualityCheck
node scripts/check-file-length.mjs
```

종료 코드 0과 새 H2/MySQL 시험 실행 건수를 확인한다. Docker·Python·MCP 실행 부재는 PHASE_BLOCKED다.
이 결과는 application 통합과 mock 금융 목적지까지의 증거이며 공개 승인 E2E·운영 활성화·실거래 완료 증거가 아니다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorFinancialProducer.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorFinancialApproval.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorFinancialConflicts.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/execution/ConnectorExecutionClaims.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorExecutionClaimTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/application/FinancialConnectorProducerTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/application/FinancialConnectorProducerRoundTripTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/application/FinancialConnectorProducerMysqlRoundTripTest.java` | 신규 |
| `test/e2e/financial/producer_plugin_http.py` | 신규 |
| `test/e2e/financial/trading_http.ts` | 신규 |
| `docs/features/connector-policy.md` | 수정 |

## 문서 소유권과 통합

이 구현자는 connector-policy.md의 금융 snapshot producer 절만 소유한다. 설치·지원 확인과 일반 금융 모듈의 상대 절을 삭제하지 않는다.
최종 문서 통합자는 메인 코디네이터다. 독립 checkout에서 구현하며 앞선 PR이 머지되면 일반 merge로 새 main을 포함한다.
rebase와 force push는 하지 않는다. 공용 문서 통합 뒤 doc-files 시험과 한국어·길이·공개 정보 검사를 다시 실행한다.

## 예산과 완료

운영 코드 변경 목표는 900줄이며 PR 상한은 1500줄이다. 신규 Java는 각 500줄 이하이며 기존 baseline 증가나 새 migration은 없다.
ConnectorPolicyService 499줄과 ConnectorExecutionSnapshot 497줄에 책임을 더하지 않는다. consumer를 복사하지 않는다.
설계·구현·회귀를 한 관심사 PR로 검토한다. 공개 연결 시 승인 카드 summary 표시·일괄 승인 제외와 보안 리뷰가 추가로 필요하며 이번 변경에는 넣지 않는다.
