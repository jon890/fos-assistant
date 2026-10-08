## ADR-20261008: 커넥터는 연결 하나를 에이전트 하나에만 붙이라고, 실행 공간이 있는 profile 에만 붙이라고 선언할 수 있다

- **status**: `accepted`
- Date: 2026-10-08
- [ADR-083](ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md) 의 「연결은 여러 에이전트에 붙는다」 에 커넥터가 고르는 예외를 둔다. 실행 공간은 [ADR-086](ADR-086-셸과-파일-도구는-사용자별-docker-실행-공간에서만-돈다.md) 의 것이다.

### 결정

**`connector.json` 에 선택 boolean 두 칸을 둔다. 둘 다 없으면 거짓이고 지금 동작과 같다.**

| 칸 | 참일 때 | 판정하는 곳 | 어기면 |
| --- | --- | --- | --- |
| `single_binding` | 사용자의 그 연결은 에이전트 하나에만 붙는다 | Control Plane 의 붙이기. 사용자 행 잠금 안에서 그 연결의 바인딩이 이미 있는지 본다 | `CONNECTOR_SINGLE_BINDING`(409). 바인딩 행이 남지 않는다 |
| `sandbox_required` | 실행 공간 정책에 등록된 profile 에만 붙는다 | 대시보드 plugin 의 바인딩 설치. 붙이기, 연결 확인, 반영 완료가 모두 같은 설치를 지난다 | 설치는 409 `sandbox_unavailable`. 붙이기는 `AGENT_SANDBOX_UNAVAILABLE`(409), 다시 설치하는 경로는 그 바인딩을 `PENDING` 으로 둔다 |

- `single_binding` 은 카탈로그가 그대로 실어 Control Plane 에 준다. 대시보드는 다른 profile 의 바인딩을 보지 않는다. 바인딩의 원장은 Control Plane 이다.
- `sandbox_required` 는 대시보드가 이미 읽는 운영 정책(`profiles`)으로 판정한다. Control Plane 은 profile 이 정책에 있는지 모른다.
- 대시보드의 409 가운데 본문 `code` 가 `sandbox_unavailable` 인 것은 Control Plane 이 `AGENT_SANDBOX_UNAVAILABLE` 로 옮긴다. `owner_attachments_env` 의 정책 없음도 같은 409 라 그 붙이기도 이 코드로 끝난다. 나머지 409 는 지금처럼 `CONNECTOR_BIND_CONFLICT` 다.

### 맥락

토스증권 Open API 는 클라이언트당 유효한 토큰이 하나다. 새로 받으면 직전 토큰이 바로 무효가 된다.
한 연결을 두 에이전트에 붙이면 두 profile 의 MCP 프로세스가 번갈아 토큰을 받아 서로의 호출을 깬다. 화면의 권고만으로는 막지 못한다.

같은 API 의 키에는 권한 범위가 없다. 조회만 하는 커넥터에 넣어도 그 키로 주문할 수 있다.
실행 공간 정책에 등록되지 않은 profile 은 셸이 그 profile 의 `.env` 를 읽는다(ADR-086). 그런 profile 에 붙이면 셸이 돈을 움직일 수 있는 키를 읽는다.
이 둘은 커넥터마다 다르다. Gmail 은 여러 에이전트에 붙여도 되고, 그 키로 돈이 움직이지 않는다.

### 대안 기각

- **커넥터 문서에 권고만 적는다**: 붙이는 사람이 문서를 읽는다는 보장이 없다. 토큰이 서로 깨지는 것은 사용자가 원인을 알기 어려운 간헐 실패로 보인다.
- **대시보드가 모든 profile 의 소유 기록에서 같은 보관 파일을 찾아 `single_binding` 을 막는다**: profile 디렉터리를 모두 읽어야 하고, 떼는 중인 바인딩과 동시에 오면 판정이 흔들린다. 바인딩 원장과 사용자 잠금은 Control Plane 에 있다.
- **Control Plane 이 실행 공간 등록을 판정한다**: 정책 파일은 운영 저장소가 소유하고 대시보드만 읽는다. Control Plane 에 그 조회를 새로 열어야 한다.
- **모든 커넥터에 실행 공간을 요구한다**: 실행 공간이 없는 profile 의 Gmail 바인딩이 모두 끊긴다. 위험은 키의 성격에 따라 다르므로 커넥터가 고른다.
- **`sandbox_required` 를 셸이 켜진 profile 에만 건다**: 셸은 붙인 뒤 관리자가 켤 수 있다. 정책에 등록된 profile 은 그때 셸이 실행 공간에서 돌고, 등록되지 않은 profile 은 그렇지 않다. 붙일 때 셸 상태를 보면 나중에 켠 셸을 놓친다.

### 결과

- 얻는 것:
  - 토큰이 하나뿐인 서비스의 커넥터가 바인딩끼리 토큰을 깨는 일을 붙이는 순간 막는다.
  - 권한 범위가 없는 키를 받는 커넥터가 셸이 `.env` 를 읽는 profile 에 놓이지 않는다. 다시 설치하는 경로도 같은 판정을 지나므로, 정책에서 빠진 profile 의 바인딩은 `PENDING` 이 되어 도구가 막힌다.
  - 사진 첨부를 읽는 커넥터의 「실행 공간 없음」 이 「이름이 겹친다」 로 잘못 보이던 것이 바로 보인다.
- 감당할 것:
  - `sandbox_required` 는 정책 등록만 본다. 정책에 등록하기 전에 셸을 저장한 profile 은 셸을 다시 저장하거나 운영 일괄 반영을 거칠 때까지 셸이 로컬에서 돈다(ADR-086). 이 틈은 운영 절차가 막는다.
  - 붙인 뒤 그 profile 을 실행 공간 정책에서 빼면, 다시 설치할 때 409 를 받아 바인딩이 `PENDING` 이 되지만 이미 복사한 값은 그 profile 의 `.env` 에 남는다. 그 profile 의 셸이 실행 공간 밖에서 돌면 그 값을 읽는다. 운영은 정책에서 profile 을 빼기 전에 `sandbox_required` 커넥터를 뗀다. 거절할 때 값을 지우는 것은 후속이다.
  - 옛 커넥터 에이전트의 설치 경로는 이 칸을 보지 않는다. 지금 코드에는 새 옛 커넥터 에이전트를 만드는 경로가 없다.
  - `single_binding` 커넥터를 다른 에이전트로 옮기려면 먼저 떼고 붙인다. 연결을 해제할 필요는 없다.
  - 이 칸을 모르는 옛 대시보드 plugin 은 `single_binding` 을 카탈로그에 싣지 않아 Control Plane 이 거짓으로 읽는다. plugin 과 Control Plane 을 함께 배포한다.

- **적용 범위**: 대시보드 plugin 의 manifest 검증과 카탈로그, 바인딩 설치, Control Plane 의 카탈로그 읽기와 붙이기, 화면의 오류 문구. 계약은 [커넥터 연결](../connectors.md) 의 「connector.json」 과 「붙이기와 떼기」, [커넥터 설치](../backend/connector-install.md) 가 갖는다.
