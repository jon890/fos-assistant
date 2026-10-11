# Phase 01. 커밋된 guard 설치와 Python·Spring 왕복

**Execution profile**: deep

## 목표

설치 CAS가 커밋한 맥락을 기존 6인자 bindConnector에 실어 실제 support 소비자까지 잇는다.

**범위 외**: support 제공자 재작성, 금융 정책 개방, 실제 거래, 운영 배포와 외부 설치 원자적 정리다.

## 컨텍스트

**근거 문서**: `docs/features/connector-policy.md`의 「바인딩 지원 확인과 철회」와 「금융 producer의 구현 경계 보완안」, `docs/features/connector.md`의 「guard 설치의 후속 계약 보완안」이다.
support 제공자는 main에 있으며 ConnectorGuardSupport.verify는 HTTP 전후 DB를 읽고 READY·desiredEnabled·revision·현재 SHA를 확인한다.
PENDING 설치 중에는 verified가 될 수 없다. 설치 응답의 pending을 실패나 READY의 필수 조건으로 바꾸지 않는다.
ConnectorBindingAttempt와 ConnectorInstallAttempts는 관측한 437 head에만 있다. 변경 파일의 두 수정 경로는 실제 main 통합 전에는 존재하지 않는다.

## Blocked 조건

- gh로 437의 merged 상태와 merge commit을 다시 조회하고 실제 origin/main의 조상인지 확인한다. head만으로 완료를 인정하지 않는다.
- main에서 ConnectorInstallAttempts.start/begin/complete, ConnectorBindingAttempt, install_attempt_id와 6인자 bindConnector가 확인되지 않으면 PHASE_BLOCKED다.
- 새 main 작업 공간에서 기본 verify_task 0과 독립 계획 검토 승인을 확인한다. 현재 main의 missing-file 오류를 audit·빈 파일·신규 선언으로 숨기지 않는다.
- 공유 파일의 현재 소유자를 확인하고 구현 dispatch를 받은 뒤 시작한다.

## 작업 항목

### 1. 설치 입력과 호출

ConnectorInstallAttempts.begin에서 검증한 manifest.executionGuard와 executionGuardManifestSha256을 읽는다.
선언이 없으면 기존 5인자 설치를 유지한다. 선언이 있는데 invalid이면 금융 guard를 만들지 않고 fail-closed로 처리한다.

기존 ConnectorExecutionGuard.read를 재사용한다. protocol과 manifest SHA를 불변 문자열로 ConnectorBindingAttempt에 캡처한다.
시작 TX의 bindingId·connectionId·connectionRevision과 함께 guard를 만든다. JsonNode나 엔티티를 record에 새로 보관하지 않는다.
ConnectorBindingInstalls.sendAgain은 비legacy이며 유효한 선언이 있는 경우에만 6인자 bindConnector를 부른다.
wire는 v, protocol, bindingId, connectionId, connectionUpdatedAt, manifestSha256으로 고정한다. 숫자 ID는 문자열이다.
attempt UUID, binding revision과 새 프로토콜 키를 보내지 않는다. 선언이 바뀌면 SHA도 새로 캡처하며 과거 verified를 재사용하지 않는다.

TX 밖 설치·상태·probe와 결과 CAS를 그대로 쓴다. 결과 TX는 connection.updatedAt을 바꾸지 않는다.

### 2. 실제 왕복과 경쟁

검사 대상은 `backend/src/test/java/com/bifos/assistant/connector/ConnectorGuardBindingRoundTripTest.java`와 `backend/src/test/java/com/bifos/assistant/connector/ConnectorGuardBindingMysqlRoundTripTest.java`다.
ConnectorGuardBindingRoundTripTest는 기존 공용 HermesConnectorClient mock을 thenAnswer로 테스트 내부 생성 HttpHermesConnectorClient에 위임한다.
새 Spring context 설정이나 @MockitoBean·@TestBean·@DirtiesContext·H2 @DynamicPropertySource를 만들지 않는다.
ConnectorBindingHttpFixture와 guard_plugin_http.py는 실제 Python 설치·상태 처리기와 support 함수를 호출한다. 서비스별 이름은 plugin에 넣지 않는다.
테스트 전체를 TX로 감싸지 않고 가짜 서비스 토큰과 loopback만 쓴다. 외부 주소로 나가는 요청은 실패한다.
설치 callback 시 별도 DB 연결로 새 revision·PENDING·attempt 커밋을 관측하고 support 409/pending을 단언한다.
설치 완료 CAS가 READY를 커밋한 뒤 실제 status의 fresh nonce callback이 verified가 되는 순서를 검증한다.
verified라도 금융 exclude와 RISK_NOT_OPEN이 남고 금융 목적지 POST·DELETE는 0회다. auth POST는 별도 기록한다.

bounded latch로 두 번째 register, check, disconnect, 접근 철회와 owner/profile 변경을 첫 응답보다 먼저 완료한다.
늦은 성공·실패와 중복 응답은 CAS에서 버린다. 옛 backend 401/403, 옛 plugin 404, guard 누락, SHA·nonce 불일치와 재시작도 검증한다.
READ/WRITE READY 계약을 보존한다. 지원 상태를 READY로 승격시키는 별도 정책을 만들지 않는다.
MySQL 하위 클래스는 ConnectorExecutionClaimMysqlTest의 mysql 태그·공용 설정 패턴을 따르고 같은 시험을 상속한다.

### 3. 외부 orphan의 한계

detach 뒤 앞선 bind가 늦게 완료하면 DB 행 없이 외부 설치가 남을 수 있다.
정책 hook과 support·claim은 현재 바인딩 부재로 차단한다. 무조건 unbind를 보내면 새 바인딩까지 지우므로 추가하지 않는다.
조건부 설치·삭제 식별자 계약은 별도 후속이며 이번 wire를 늘리지 않는다. connector.md의 기존 CAS 절을 보존하고 이 한계를 함께 기록한다.

## 책임별 검증 증거

| 책임 | 조건 | 관측값 |
| --- | --- | --- |
| 준비 | 시작 TX가 attempt·revision을 커밋하고 HTTP는 TX 밖에서 호출한다 | 별도 DB 연결에서 PENDING과 커밋된 입력을 확인한다 |
| 저장 | 현재 attempt만 결과 CAS를 적용한다 | READY 커밋 뒤 fresh nonce에서 verified가 된다. 늦은·중복 응답은 폐기된다 |
| 승인 | 설치 성공은 금융 권한을 열지 않는다 | RISK_NOT_OPEN과 금융 exclude가 남는다 |
| claim | 바인딩 부재·철회·옛 맥락을 허용하지 않는다 | support·claim 거절과 READ/WRITE 회귀 결과를 확인한다 |
| 전송 | 이 단계는 설치 왕복만 검증한다 | 금융 POST·DELETE 0회이며 auth POST는 별도로 센다 |

## 검증

```bash
cd backend && ./gradlew test --tests '*ConnectorGuardBindingRoundTripTest' --tests '*ConnectorGuardSupportTest' --tests '*ConnectorExecutionClaimTest' --tests '*ToolPolicyDecisionTest' --tests '*ConnectorConnectionServiceLockTest' --tests '*ConnectorBindingServiceLockTest'
scripts/check-mysql-migration.sh --tests '*ConnectorGuardBindingMysqlRoundTripTest' --tests '*ConnectorConnectionServiceMysqlLockTest'
PYTHONPATH=hermes/tests python3 -m unittest test_connector_guard_binding test_dashboard_profile_api_connector_binding_refresh
node --test test/unit/connector-neutral.test.ts
node scripts/check-file-length.mjs
cd backend && ./gradlew qualityCheck
```

종료 코드 0과 H2/MySQL 양쪽의 새 시험 실행 건수를 확인한다.
Docker·Python·MCP 실행 환경이 없으면 PHASE_BLOCKED이며 skip을 성공으로 세지 않는다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/connector/application/model/ConnectorBindingAttempt.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorInstallAttempts.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorBindingInstalls.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorGuardBindingRoundTripTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorGuardBindingMysqlRoundTripTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorBindingHttpFixture.java` | 신규 |
| `test/e2e/financial/guard_plugin_http.py` | 신규 |
| `docs/features/connector.md` | 수정 |
| `docs/features/connector-policy.md` | 수정 |

## 문서 소유권과 통합

이 구현자는 connector.md의 guard 설치 절과 connector-policy.md의 설치·지원 확인 절만 소유한다.
일반 금융 모듈 절과 snapshot producer 절은 상대 구현자가 소유하며 삭제하지 않는다. 최종 문서 통합자는 메인 코디네이터다.
독립 checkout에서 구현하고 먼저 머지한 PR 뒤 일반 merge로 새 main을 포함한다. rebase와 force push는 하지 않는다.
공용 문서를 합친 뒤 doc-files 시험과 한국어·길이·공개 정보 검사를 다시 실행한다.

## 예산과 완료

운영 코드 변경 합계 목표는 250줄이며 PR 상한은 1500줄이다. Java 파일은 500줄 이하, 기존 baseline은 늘리지 않는다.
ConnectionService·BindingService에 새 책임을 넣지 않는다. 신규 constructor 인자는 record 생성부와 관련 시험을 같은 커밋에서 맞춘다.
구현 PR에서 보완안을 현재 계약으로 갱신하고 끝난 계획을 지운다. 독립 리뷰와 CI가 통과해도 운영 개방과 실제 거래는 하지 않는다.
