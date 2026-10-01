## ADR-047: 커넥터 도구 호출은 profile plugin 의 hook 이 Control Plane 에 물어 판정한다

- **status**: `accepted`
- Date: 2026-10-01
- [ADR-043](ADR-043-커넥터는-plugin-의-connector-json-으로-선언하고-control-plane-은-범용-흐름만-갖는다.md) 의 manifest 에 도구 정책을 더한다. 승인 뒤의 실행은 [ADR-048](ADR-048-커넥터-쓰기는-control-plane-이-승인-줄을-저장하고-승인한-인자로-한-번만-실행한다.md) 이 정한다.

### 결정

**커넥터 MCP 도구를 부를 수 있는지는 Control Plane 이 정하고, 그 판정을 `fos-ctx` plugin 의 `pre_tool_call` hook 이 호출마다 받아 온다.**

| 항목 | 정한 것 |
| --- | --- |
| 정책의 출처 | `connector.json` 의 `schema: 2` 가 도구마다 `risk` 와 `approval` 을 선언한다. 위험도마다 하한이 있고 하한보다 느슨한 manifest 는 카탈로그에 나오지 않는다 |
| 판정하는 곳 | Control Plane 의 순수 함수 하나. 모델의 인자와 서버의 `readOnlyHint` 는 판정을 바꾸지 못한다 |
| 강제하는 곳 | 연결용 profile 의 `fos-ctx` hook. 연결용 profile 의 MCP 도구 호출마다 Control Plane 에 묻는다 |
| 기본값 | 거절이다. 판정을 받지 못했거나, 응답을 읽지 못했거나, 예외가 났거나, 실행 맥락이 없으면 막는다 |
| 막는 방법 | 늘 글이 있는 `block` 이다. Hermes 는 글이 없는 `block` 과 `None` 을 통과로 읽는다 |
| 기다리는 시간 | hook 의 HTTP 요청은 3초 안에 끝낸다. 승인을 hook 안에서 기다리지 않는다 |
| 이름 대응 | Hermes 가 등록하는 도구 이름과 MCP 서버의 원래 도구 이름을 설치할 때 파일로 적어 둔다. hook 은 그 파일에서 찾고, 접두사를 잘라 원래 이름을 짐작하지 않는다 |
| hook 이 꺼진 profile | 연결 확인이 그 profile 의 plugin 과 대응 파일을 보고, 맞지 않으면 `READY` 로 두지 않는다 |
| `approval: always` 인 도구 | 설치가 서버 정의의 `tools.exclude` 에 넣어 모델에게 보이지 않게 한다 |
| 다른 호출 경로 | plugin 의 직접 MCP 호출(`call_mcp`)과 실행 맥락이 없는 코드 실행 경로는 연결용 profile 에서 열지 않는다 |
| Hermes 의 `approve` | FOS 의 필수 승인 대신 쓰지 않는다 |
| `DESTRUCTIVE`, `FINANCIAL` | 선언은 받되 호출은 거절한다. 여는 것은 아래 「감당할 것」 의 wrapper 뒤다 |

`schema: 1` manifest 는 계속 받는다. 확인 도구와 선택지 도구만 `READ` 로 읽고 나머지 도구는 `WRITE` 와 `required` 로 읽는다.

### 맥락

커넥터 MCP 도구 호출은 Hermes gateway 가 커넥터 MCP 자식 프로세스에 직접 보낸다. Control Plane 은 그 경로에 없다.
그래서 연결용 profile 의 모델은 그 서버의 도구를 모두 받았고, 쓰기 도구도 사람의 확인 없이 실행됐다.
`readOnlyHint` 제한은 연결 화면의 선택지와 확인 호출에만 걸려 있었다.

Hermes `v2026.9.24` 에서 확인한 hook 의 동작이 이 결정을 제약한다. 자세한 것은 [`hermes/connector-policy.md`](../hermes/connector-policy.md) 에 있다.

- 모델이 MCP 도구를 직접 부를 때와 중계 도구 `tool_call` 로 부를 때 hook 은 같은 등록 이름과 안쪽 인자를 받는다. 중계는 판정을 비켜 가지 않는다
- callback 이 예외를 던지거나 시간을 넘기면 호출이 막힌다. 그러나 `None`, 글이 없는 `block`, hook dispatcher 바깥의 예외는 통과한다
- `mcp_servers.<서버>.tools.exclude` 로 도구를 모델에게서 뺄 수 있다
- `{"action": "approve"}` 는 Hermes 의 내장 승인으로 넘긴다. `approvals.mode: off` 와 누적 승인으로 사람 없이 통과하고, 승인 함수가 도구 인자를 받지 않는다

### 대안 기각

- **FOS 가 소유한 MCP wrapper 를 서버 앞에 둔다**: 호출 경로 위에 있어 hook 이 빠져도 닫힌다. 그러나 MCP 의 server 와 client 양쪽을 말하는 프로세스를 새로 만들어야 하고 호출마다 프로세스가 하나 더 든다. hook 은 이미 모든 관리 profile 에 있고 실행 맥락을 찾는 방법도 이미 있다. 되돌릴 수 있는 쓰기까지는 hook 으로 시작하고, 되돌리기 어려운 도구를 열기 전에 wrapper 를 둔다.
- **Hermes 의 `approvals.mode` 나 `approve` directive 로 묻는다**: 정책이 Hermes 설정에 놓여 Control Plane 이 소유하지 못한다. 설정 하나로 승인이 생략되고, 승인한 인자와 실행한 인자가 같다는 것을 보장하지 못한다.
- **쓰기 도구를 profile 에서 빼고 FOS 도구로 요청만 받는다**: 연결용 에이전트는 Control Plane MCP 를 받지 않는다(ADR-045). 요청 도구를 둘 자리가 없고, 서비스마다 다른 도구 모양을 범용 도구 하나에 담기 어렵다.
- **등록 이름의 접두사를 잘라 원래 도구 이름을 얻는다**: Hermes 는 이름의 글자를 `_` 로 바꾸고 64자를 넘으면 해시를 붙여 줄인다. 잘라 낸 글이 원래 이름과 다를 수 있고, 서로 다른 도구가 같은 등록 이름이 될 수 있다.
- **판정을 받지 못하면 통과시킨다**: Control Plane 이 잠깐 느릴 때 쓰기가 승인 없이 나간다. 읽기가 잠깐 막히는 쪽이 낫다.

### 결과

- 얻는 것: 모델이 도구를 부를 수 있다는 것과 사용자 대신 바로 실행할 수 있다는 것이 나뉜다. 커넥터 도구 호출마다 판정이 `connector_action` 에 남는다. manifest 에 없는 도구는 서버가 새로 내놓아도 호출되지 않는다.
- 감당할 것:
  - **hook 은 모든 실패에서 닫히지 않는다.** plugin 을 읽지 못했거나 hook dispatcher 가 실패하면 호출이 판정 없이 나간다. 연결 확인은 확인한 시점의 상태만 본다. 그래서 `DESTRUCTIVE` 와 `FINANCIAL` 은 wrapper 를 두기 전에 열지 않는다.
  - 도구 호출마다 Control Plane 왕복이 하나 든다. Control Plane 이 내려가 있으면 연결용 에이전트의 도구가 모두 막힌다.
  - 등록 이름을 만드는 규칙이 Hermes 의 것과 같아야 한다. Hermes 를 올릴 때 [`hermes/upgrades.md`](../hermes/upgrades.md) 의 목록으로 확인한다.
  - hook 이 부를 주소는 gateway 프로세스의 환경 변수로 준다. 없으면 연결용 profile 의 MCP 도구가 모두 막힌다.
  - 이미 만든 연결용 profile 은 옛 `fos-ctx` 를 갖고 있다. 설치가 새 판으로 바꾸고, 떠 있는 gateway 가 새 판을 읽을 때까지 재시작 대기로 둔다.
  - `schema: 1` 커넥터는 조회 도구도 승인 대상으로 읽힌다. 그 plugin 이 `schema: 2` 로 올릴 때까지다.
