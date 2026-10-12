# Phase 03. 금융 request_key 의 계좌 범위와 keyed hash (#507 후속)

**Execution profile**: deep

## 목표

같은 계좌의 같은 주문 의도는 연결을 다시 등록해도 같은 request_key 가 되게 하고,
금융 줄의 해시 칸을 서버 비밀로 keyed hash 해 DB 만 읽은 사람이 인자를 대입해 맞혀 보지 못하게 한다.
**매매 개방 전 필수다.** phase-04 는 이 단계가 머지된 뒤에만 시작한다.

**범위 외**: 금융이 아닌 승인 줄의 `args_sha256`, kill switch 와 상한(phase-02), 공개 진입점(phase-04)이다.

## 컨텍스트

**근거 문서**: `docs/features/tossinvest-connector.md` 의 「매매 개방 조건」, `docs/features/connector-policy.md` 의 「운영 비활성 금융 snapshot producer」 다.
#507 PR 본문이 남긴 후속 두 가지다: 연결 재등록의 request_key 우회(계좌 식별 범위와 기존 해시 호환), key 없는 해시의 인자 대입 추측 방어.
2026-10-13 main 의 일반 주문 키는 `ConnectorOrderSnapshot.requestKey`, 조건주문 키는 `ConnectorConditionalSnapshot.requestKey` 가 만들고 둘 다 userId 와 connectionId 를 넣는다.
연결을 해제하면 행을 지우지 않고 값만 비우며, 다시 등록하면 UNIQUE(user_id, connector_id) 의 같은 행을 쓴다(`ConnectorConnection`, `ConnectorConnectionService`). 그래서 지금 경로로는 connectionId 가 바뀌지 않는다.
그래도 계좌를 바꿔 다시 등록하거나 연결 행을 지우는 경로가 생기면 키가 갈린다. 키의 뜻을 「이 사용자가 이 커넥터의 이 계좌에 내는 이 주문」 으로 고정한다.
`Sha256.hex` 는 key 없는 SHA-256 앞 16바이트다. 대입 추측이 가능한 칸은 다음과 같다.

| 칸 | 위험 |
| --- | --- |
| `connector_action.args_sha256` (금융 줄) | 모델 인자 원문의 해시 |
| `connector_action_execution.execution_args_sha256` | 실행 원문의 해시 |
| `connector_action_execution.scope_sha256` | `{"account_seq":…}` 하나라 후보가 매우 적다 |
| `connector_action_execution.request_key` | 의도 경로의 해시 |
| `decision_feedback_event.subject_version` | 금융 줄의 `args_sha256` 을 그대로 복사한다 |

## 의도 메모

- 키 공식은 버전 접두로 가른다. 일반 주문은 `fos-financial-intent-v2`, 조건주문은 `fos-conditional-intent-v2` 다. 경로에서 connectionId 를 빼고 connectorId 를 넣는다. account_seq 는 이미 있다.
- keyed hash 는 HMAC-SHA256 앞 16바이트로 해 칸 길이를 바꾸지 않는다. 마이그레이션은 없다.
- key 는 비밀값이므로 DB 에 넣지 않는다. 운영 비밀 파일에서 환경 변수로 받는다. 없으면 phase-02 의 gate 가 금융을 열지 않는다.
- 기존 줄과의 호환은 행 단위 버전 칸으로 가른다. `ConnectorExecutionSnapshot.PROTOCOL` 을 `approval-claim-v2` 로 올리고 manifest `execution_guard.protocol` 도 같이 올린다.
  v1 줄은 `open` 의 protocol 대조에서 자동으로 거절된다. gate 는 `protocol = 'approval-claim-v1'` 이면서 PENDING, EXECUTING, UNKNOWN 인 줄이 있을 때만 닫는다.
  「금융 줄이 하나라도 있으면 닫는다」 로 두면 첫 실제 호출이 만든 v2 줄 때문에 그 뒤 재기동마다 gate 가 닫힌다. request_key 의 버전 접두는 해시 입력 안에 있어 저장된 값으로는 버전을 알 수 없다.
  남은 v1 줄을 새 공식으로 다시 계산하지 않는다. 운영에는 금융 caller 가 없어 v1 줄은 0개여야 한다.
- 조건주문의 공통 vector(`test/resources/conditional-snapshot-v1.json`)의 의도 키 기대값은 v2 로 다시 만들고 Python 과 Spring 이 같은 값을 내는지 확인한다.

## 작업 항목

### 1. 테스트를 먼저 실패시킨다

연결을 해제하고 다시 등록한 뒤, 그리고 같은 계좌를 다른 connectionId 로 둔 뒤 같은 의도의 준비가 기존 UNKNOWN 에 막히는지 확인한다.
DB 에 저장된 네 칸이 key 없는 SHA 로 다시 계산되지 않는지, key 를 바꾸면 값이 달라지는지 확인한다.
열린 v1 줄이 있으면 gate 가 닫히고, v2 줄이 SUCCEEDED 나 UNKNOWN 으로 남아 있어도 재기동 뒤 gate 가 열리는지 확인한다.

### 2. 구현

신규 `FinancialDigest`(connector.application)가 `digest(purpose, bytes)` 를 갖고 HMAC key 를 phase-02 가 둔 `ConnectorFinancialProperties.digestKeyFile` 에서 받는다. gate 는 key 를 읽지 못하면 이미 닫혀 있다. purpose 는 칸 이름이다.
`ConnectorOrderSnapshot.requestKey` 와 `ConnectorConditionalSnapshot.requestKey` 를 v2 경로로 바꾸고 최종 해시를 `FinancialDigest` 로 낸다.
producer 가 금융 줄의 `args_sha256` 을, `ConnectorExecutionSnapshot.capture` 가 실행 원문과 scope 해시를 `FinancialDigest` 로 만든다. `open` 은 같은 방식으로 다시 계산해 대조한다.
Python 쪽은 해시를 계산하지 않는다. 조건주문 의도 키를 Python 이 대조하는 곳이 있으면 Spring 이 낸 값만 비교하게 한다.

### 3. 문서

「매매 개방 조건」 표의 두 줄을 「있음」 으로 바꾸고, `connector-policy.md` 의 request_key 남은 문제 문장을 지운다.

## 검증

```bash
cd backend && ./gradlew test --tests '*ConnectorConditionalSnapshotTest' --tests '*FinancialConnectorProducerTest' --tests '*ConnectorExecutionClaimTest' --tests '*ConnectorActionExecutionTest'
PYTHONPATH=hermes/tests python3 -m unittest test_connector_conditional_validation
cd backend && ./gradlew qualityCheck
scripts/dev/check-local.sh --fast
```

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/connector/application/FinancialDigest.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorOrderSnapshot.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorConditionalSnapshot.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorExecutionSnapshot.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorFinancialProducer.java` | 수정 |
| `test/resources/conditional-snapshot-v1.json` | 의도 키 기대값 갱신 |
| `docs/features/tossinvest-connector.md`, `docs/features/connector-policy.md` | 수정 |
