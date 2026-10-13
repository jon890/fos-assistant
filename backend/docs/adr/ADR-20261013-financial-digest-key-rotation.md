## ADR-20261013 / financial-digest-key-rotation: 금융 해시 key 를 바꾸면 이전 key 의 열린 줄이 끝날 때까지 금융 관문을 닫는다

- **status**: `accepted`
- Date: 2026-10-13

### 맥락

금융 줄의 `request_key` 와 해시 칸은 운영 비밀 key 파일로 만든 HMAC 이다(`FinancialDigest`).
key 를 바꾸면 같은 사용자, 커넥터, 계좌, 주문도 다른 `request_key` 가 된다.
같은 의도 차단(`ConnectorFinancialConflicts`)은 `request_key` 가 같은 줄만 찾으므로, 이전 key 로 저장한 `UNKNOWN` 을 놓친다.
`UNKNOWN` 은 결과를 모르는 주문이고 저절로 끝나지 않는다. 놓치면 이미 나갔을 수 있는 주문을 다시 보낼 수 있다.

### 검토한 선택지

| 선택지 | 장점 | 단점 |
| --- | --- | --- |
| 이전 key 도 함께 읽어 모든 key 로 `request_key` 를 계산해 대조한다 | key 를 바꾼 뒤에도 주문을 받는다 | 이전 key 를 계속 보관해야 하고, 하나라도 빠지면 다시 놓친다. key 를 바꾸는 까닭이 유출이면 유출된 key 를 계속 쓴다 |
| 해시 값에 key 식별자를 넣고, 이전 key 로 만든 열린 줄이 남으면 관문을 닫는다 | 이전 key 를 보관하지 않는다. 놓치는 경우가 생기면 주문이 아니라 거절로 끝난다 | key 를 바꾼 뒤 이전 key 의 `UNKNOWN` 이 남아 있으면 운영자가 정리할 때까지 금융이 닫힌다 |

### 결정

둘째 선택지를 고른다. 금융은 주문을 놓치는 쪽보다 닫히는 쪽이 안전하고, key 교체는 드문 운영 작업이다.

- 해시 값은 `k1`, key 식별자 8글자, HMAC-SHA256 앞 27바이트의 16진수로 64글자다.
- key 식별자는 key 로 고정 문자열을 HMAC 한 앞 4바이트다. key 를 드러내지 않는다.
- `ConnectorFinancialGate.requireOpen()` 은 지금 protocol(`approval-claim-v2`)이 아니거나 `request_key` 가 지금 key 식별자로 시작하지 않는 `PENDING`, `EXECUTING`, `UNKNOWN` 줄이 있으면 닫는다.
- 끝난 줄(`SUCCEEDED`, `FAILED`, `REJECTED` 같은 상태)은 보지 않는다. 보면 key 를 바꾼 뒤 재기동할 때마다 닫힌다.
- 준비, 승인, claim 이 모두 이 검사를 지나므로, 이전 key 로 만든 대기 중인 승인과 아직 소비하지 않은 ticket 도 금융 전송 없이 거절된다.
- 하루 합계는 같은 날 승인으로 넘어간 줄(`EXECUTING`, `SUCCEEDED`, `UNKNOWN`)을 key 와 protocol 에 상관없이 센다. `approval-claim-v1` 줄은 평문 SHA-256 으로, 지금 key 의 줄은 keyed hash 로 원문을 대조한다. 이전 key 의 줄은 해시를 다시 계산할 수 없어, 줄과 칸과 사용자를 AAD 로 묶은 암호문을 열어 금액을 읽는다. 빼면 key 를 바꾼 날 상한을 넘고, 닫으면 그날 새 주문이 모두 막힌다.

key 를 바꾸기 전에 열린 금융 줄이 없는지 확인한다.
남은 `UNKNOWN` 은 토스증권에서 결과를 확인한 뒤 정리한다. 확인하고 정리하는 방법은 `fos-home-infra` 가 갖는다.

### 결과

- key 식별자가 4바이트라 서로 다른 두 key 의 식별자가 우연히 같을 확률은 2^-32 다. 그때는 이 검사가 교체를 알아채지 못한다. 감수한다.
- HMAC 을 27바이트(216비트)로 줄여도 DB 만 읽은 사람이 인자 후보를 대입해 맞혀 보는 것을 막는 데는 충분하다.
- 회귀는 `ConnectorConditionalSnapshotTest` 의 key 교체 검사가 지킨다. 이전 key 의 `UNKNOWN` 이 남으면 닫히고, 그 줄이 끝나면 다시 열린다.
