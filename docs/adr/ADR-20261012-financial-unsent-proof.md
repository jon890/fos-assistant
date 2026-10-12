## ADR-20261012 / financial-unsent-proof: 소비하지 않은 금융 ticket 은 미전송의 증거로 보고 결과 불명 대신 실패로 끝낸다

- **status**: `accepted`
- Date: 2026-10-12
- [ADR-20261010 / financial-execution-guard](ADR-20261010-financial-execution-guard.md) 의 일회성 ticket 과 claim 위에 선다.
  UNKNOWN 을 자동으로 다시 보내지 않는다는 [ADR-050](../../backend/docs/adr/ADR-050-커넥터-쓰기는-control-plane-이-승인-줄을-저장하고-승인한-인자로-한-번만-실행한다.md) 의 규칙은 바꾸지 않는다.

### 결정

**금융 승인 줄을 `EXECUTING` 에서 끝낼 때, 실행 줄의 `consumed_at` 이 비어 있으면 `FAILED` 로, 차 있으면 `UNKNOWN` 으로 끝낸다.**
이 판정은 claim 과 같은 잠금 순서(사용자, 승인 줄, 실행 줄) 안에서 한다.

| 끝내는 자리 | `consumed_at` 이 빔 | `consumed_at` 이 참 |
| --- | --- | --- |
| 기동 정리(`markInterrupted`) | `FAILED`, `error_code` 는 `unavailable` | `UNKNOWN` |
| 오래 남은 실행 정리(`markStale`) | 같다 | `UNKNOWN` |
| 금융 승인의 결과 기록, 응답을 잃었다 | 같다 | `UNKNOWN` |
| 금융 승인의 결과 기록, 오류 상세 없이 `unavailable` 실패를 받았다 | `FAILED` | `UNKNOWN` |
| 금융 승인의 결과 기록, 커넥터가 선언한 오류를 받았다 | `FAILED` | `FAILED` |
| 금융 승인의 결과 기록, 성공을 받았다 | 그대로 `SUCCEEDED`. 실행 줄과 어긋나므로 경고를 남긴다 | `SUCCEEDED` |

실행 줄이 없는 승인 줄(금융이 아닌 쓰기)은 지금처럼 `UNKNOWN` 으로 끝낸다.

### 미전송을 증명하는 사슬

1. 커넥터 자식은 금융 POST 앞에 claim 을 한 번 부르고, 성공 응답을 받은 뒤에만 보낸다(`claimApproval`, `hermes/connectors/tossinvest/src/trading-tools.ts`).
2. claim 은 승인 줄이 `EXECUTING` 이고 ticket 이 유효할 때만 `consumed_at` 을 채운다. 채운 트랜잭션을 커밋한 뒤에만 성공을 응답한다.
3. 정리는 같은 잠금을 잡은 뒤 `consumed_at` 을 본다. 비어 있으면 승인 줄을 `EXECUTING` 밖으로 옮겨 커밋한다.
4. 그 뒤의 claim 과 ticket 발급은 `EXECUTING` 이 아니어서 모두 거절된다. 그러므로 이 줄의 금융 POST 는 앞으로도 나가지 않는다.

claim 이 먼저 잠금을 잡았으면 정리는 커밋된 `consumed_at` 을 보고 `UNKNOWN` 으로 끝낸다.
claim 의 커밋은 됐는데 응답을 잃은 경우에도 `consumed_at` 이 차 있어 `UNKNOWN` 이다. 틀려도 결과 불명 쪽으로 틀린다.

ticket 을 발급하지 않은 줄, 발급했지만 자식이 claim 하지 않은 줄, 발급 응답을 잃은 줄이 모두 같은 규칙으로 `FAILED` 가 된다.
ticket 발급 여부는 판정에 쓰지 않는다. 발급만으로는 전송이 일어나지 않기 때문이다.

### 맥락

지금까지는 ticket 발급 전에 프로세스가 멈춘 줄도 `UNKNOWN` 으로 끝냈다.
같은 의도 검사가 `UNKNOWN` 을 영구히 막으므로, 보내지 않은 주문이 같은 주문을 다시 넣는 길을 계속 막았다.
`UNKNOWN` 을 푸는 명시적 새 요청(`supersedes_unknown_action_id`)은 아직 열지 않았다.

대시보드의 execute `409` 를 `FAILED` 로 읽는 것도 같은 문제였다.
plugin 은 `409` 를 자식을 띄우기 전의 보호 맥락 실패에만 쓴다(`connector_run.py`). 그러나 Control Plane 이 그 계약만 믿으면 plugin 이 바뀔 때 전송한 주문을 실패로 적을 수 있다.
`consumed_at` 을 함께 보면 `409` 를 받았는데 claim 이 소비된 줄은 `UNKNOWN` 으로 남는다.

### 대안 기각

- **ticket 발급 여부(`ticket_id`)로 판정한다**: 발급은 했지만 claim 하지 않은 줄을 계속 `UNKNOWN` 으로 둔다. 실제로 전송을 막는 것은 claim 이다.
- **ticket 만료를 기다린 뒤 판정한다**: 정리가 승인 줄을 `EXECUTING` 밖으로 옮기면 claim 은 즉시 막힌다. 기다릴 까닭이 없다.
- **커넥터에 주문 조회를 보내 확인한다**: clientOrderId 로 조회하면 더 많은 줄을 확정할 수 있다. 그러나 정리가 외부 API 를 부르게 되고, 조회 실패와 지연을 다시 판정해야 한다. 이 결정의 범위 밖이다.
- **`UNKNOWN` 을 사용자가 수동으로 풀게 한다**: 보내지 않은 것이 증명되는 줄까지 사람에게 판단을 넘긴다. 증명할 수 없는 줄의 새 요청은 별도로 연다.

### 결과

- 얻는 것:
  - 보내지 않은 금융 줄은 같은 의도를 막지 않는다. 사용자는 새 승인으로 다시 주문할 수 있다.
  - execute 응답 해석이 틀려도 claim 이 소비된 줄은 `FAILED` 가 되지 않는다.
- 감당할 것:
  - 판정은 「자식은 claim 성공 응답 없이 금융 POST 를 보내지 않는다」 는 커넥터 계약에 기댄다. 금융 커넥터를 더할 때 이 계약을 함께 확인한다.
  - claim 이 소비된 줄은 여전히 `UNKNOWN` 이고 같은 의도를 막는다. 그 줄의 새 요청은 이 결정이 열지 않는다.
  - 결과 기록과 정리가 실행 줄까지 잠근다. 잠금 순서를 어기면 claim 과 교착한다.
