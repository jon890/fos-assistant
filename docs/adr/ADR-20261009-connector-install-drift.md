## ADR-20261009: 서버 정의의 `tools` 어긋남은 그 커넥터만 막고, Control Plane 이 어긋난 바인딩을 찾아 다시 설치한다

- **status**: `accepted`
- Date: 2026-10-09
- [ADR-083](ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md) 의 바인딩 판정과 [ADR-20261007 / connector-live-reload](ADR-20261007-connector-live-reload.md) 의 반영 예정 확인을 보완한다.

### 결정

**커넥터 하나의 manifest 가 바뀌어 생긴 어긋남은 그 커넥터 항목만 `configured: false` 로 둔다.**

| 어긋남 | 판정 |
| --- | --- |
| 서버 정의의 `tools.exclude` 가 지금 manifest 로 계산한 것과 다르다 | 그 커넥터 항목만 `configured: false` |
| 이름 대응 파일의 한 서버 항목의 `tools` 가 계산한 것과 다르다. 도구를 더하거나 빼거나 이름을 바꾼 경우다 | 그 서버의 커넥터 항목만 `configured: false` |
| 이름 대응 파일의 서버 목록, 서버의 `connector` 나 `prefix`, `v`, `isolated` 가 계산한 것과 다르거나 읽지 못한다 | profile 단위 `policy_hook` 거짓 |

profile 단위 `policy_hook` 은 `fos-ctx` 설정과 파일, 뗀 서버 기록, 이름 대응 파일의 모양만 본다.

**Control Plane 은 `READY` 바인딩의 설치 상태를 주기적으로 읽고, 어긋났으면 설치를 다시 보낸다.**

| 무엇 | 어떻게 |
| --- | --- |
| 주기 | `assistant.connector.binding.drift-cron`. 기본 10분 |
| 대상 | `READY` 바인딩만. 한 주기에 `assistant.connector.binding.drift-batch`(기본 20)개까지 번호 순으로 읽고, 다음 주기는 그 뒤부터 읽는다 |
| 어긋남 | 다시 읽은 설치가 반영 완료 판정(켜짐, `configured`, `policy_hook`, 바인딩 방식)을 통과하지 못한다 |
| 다시 맞추기 | 연결 확인과 같은 반영 맞추기를 한 번 돌린다. 서버 정의가 바뀌므로 대개 재시작 대기가 된다 |
| 반복 막기 | 다시 맞춘 바인딩은 `READY` 가 아니므로 다음 주기의 대상이 아니다. 다시 맞춰 `READY` 가 됐는데 또 어긋나면 세고, 연속 3번째에는 설치를 보내지 않고 `PENDING` 으로만 둔다 |
| 알림 | 다시 맞춘 뒤 관리자가 할 일이 남은 바인딩이 있으면 그 그룹 관리자마다 알림 한 건을 남긴다. 재시작이 필요한지, 반영 완료만 누르면 되는지를 본문이 나눈다 |

**관리자 반영 완료가 `READY` 를 확인하지 못하면 까닭별 오류 코드로 끝낸다.**
외부 호출 실패만 `CONNECTOR_OPERATION_FAILED`(502)이고, 설치 상태가 맞지 않으면 `CONNECTOR_INSTALL_MISMATCH`, probe 가 도구를 확인하지 못하면 `CONNECTOR_TOOLS_UNVERIFIED`, 반영 예정이면 `CONNECTOR_APPLY_SCHEDULED`, 다시 재시작이 필요하면 `CONNECTOR_RESTART_AGAIN`, 카탈로그에 없으면 `CONNECTOR_NOT_FOUND` 다.

### 맥락

가계부 커넥터의 manifest 에서 `approval: always` 도구가 바뀐 뒤 그 바인딩의 설치가 다시 보내지지 않았다.
서버 정의의 `tools.exclude` 가 낡자 대시보드가 그 profile 의 `policy_hook` 을 거짓으로 답했고, 같은 profile 에 붙은 토스증권 바인딩까지 `READY` 가 되지 못했다.
관리자 반영 완료는 이 상태를 외부 호출 실패와 같은 502 로 끝내고 로그를 남기지 않아 원인을 찾는 데 오래 걸렸다.

실행 정의(command, args, env)가 어긋난 항목은 이미 그 항목만 `configured: false` 로 가뒀다. `tools` 어긋남만 profile 전체로 번졌다.
설치를 다시 보내면 낡은 정의를 덮어쓰지만, 다시 보내는 것은 사용자의 연결 확인이나 관리자 반영 완료뿐이었다. manifest 가 바뀌어도 아무도 누르지 않으면 어긋남이 남는다.

### 보안 경계

`tools` 가 어긋난 서버의 `approval: always` 도구는 공유 gateway 를 재시작하기 전까지 모델에 등록된 채일 수 있다.
그 호출은 두 겹으로 막힌다.

- `fos-ctx` hook 은 대응에 있는 서버의 접두사로 그 커넥터의 도구 호출을 알아보고 Control Plane 에 묻는다. 대응의 `tools` 가 낡아 도구 이름을 찾지 못해도 접두사로 서버를 잡아 원래 이름 없이 묻는다. Control Plane 은 `schema: 2` 커넥터면 선언 없는 도구로 거절하고, `schema: 1` 이면 기본 정책(`WRITE`, `required`)으로 승인 줄을 만들어 막는다
- Control Plane 은 바인딩이 `READY` 가 아니면 도구 호출을 `NOT_READY` 로 막는다. `tools` 가 어긋난 바인딩은 `configured: false` 라 다음 확인부터 `READY` 가 아니다. `approval: always` 도구도 같다(`ConnectorPolicyEndpointTest`)

옛 판정도 이 바인딩을 다음 확인 전까지 `READY` 로 두었으므로 이 결정이 새 틈을 열지 않는다.
대응 파일에서 서버가 빠지면 바인딩 profile 의 hook 이 그 서버의 호출을 판정 없이 통과시킨다. 그래서 서버 목록과 접두사의 어긋남은 계속 profile 단위로 막는다.
profile 단위로 막던 것은 같은 profile 의 다른 커넥터까지 쓸 수 없게 할 뿐 그 서버의 도구를 gateway 에서 빼지 못했다.

### 대안 기각

- **profile 단위 판정을 그대로 두고 화면에 까닭만 보인다**: 커넥터 하나의 manifest 변경이 같은 profile 의 모든 바인딩을 계속 막는다. 사용자는 고칠 방법이 없다.
- **manifest 가 바뀌면 운영 동기화 스크립트가 Control Plane 을 불러 다시 설치하게 한다**: 운영 절차에 한 단계가 늘고 빠뜨리면 같은 일이 난다. 배포만 하고 동기화를 하지 않은 경우도 잡지 못한다.
- **Control Plane 이 기동할 때 한 번만 점검한다**: Hermes 쪽 커넥터 동기화는 Control Plane 을 다시 띄우지 않는다.
- **대시보드가 어긋난 항목을 스스로 다시 설치한다**: 재시작 대기와 관리자 확인은 Control Plane 의 바인딩 상태가 갖는다. 대시보드가 설치를 바꾸면 Control Plane 은 재시작이 필요하다는 것을 모른다.

### 결과

- 얻는 것:
  - 커넥터 하나의 정의 어긋남이 같은 profile 의 다른 바인딩을 막지 않는다.
  - manifest 가 바뀐 뒤 10분 안에 Control Plane 이 어긋난 바인딩을 다시 설치하고 관리자에게 할 일을 알린다.
  - 반영 완료가 실패한 까닭이 화면과 로그에 남는다.
- 감당할 것:
  - 점검 한 주기가 바인딩 수만큼(상한 20) 대시보드 `GET /api/connectors` 를 부른다.
  - 다시 맞추기 횟수와 점검 위치는 JVM 메모리에 둔다. Control Plane 이 한 대라는 전제다. 다시 띄우면 처음부터 센다.
  - manifest 변경이 서버 정의를 바꾸면 그 바인딩은 관리자가 공유 gateway 를 재시작할 때까지 쓸 수 없다.
  - Control Plane 의 예약 작업은 스레드 하나에서 차례로 돈다. 대시보드가 느리면 점검 한 주기가 다른 예약 작업을 응답 시간 한도의 바인딩 수 배만큼 늦출 수 있다. 상한 20 으로 그 크기를 묶는다.
  - 대시보드 plugin 을 먼저 배포한다. 옛 대시보드와 새 Control Plane 이 함께 돌면 `tools` 어긋남이 profile 단위 `policy_hook` 거짓으로 보여 같은 profile 의 바인딩이 모두 다시 설치된다.
