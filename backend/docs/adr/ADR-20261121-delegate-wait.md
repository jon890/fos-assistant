## ADR-20261121 / delegate-wait: 대화 turn 이 맡긴 일은 Control Plane 이 정한 시간까지 기다려 같은 답에 돌려준다

- **status**: `accepted`
- Date: 2026-11-21
- **decision-type**: `architecture`
- **현재 규칙의 원본**: [비서 중심 대화 스펙](../../../docs/features/assistant-hub/spec.md)(DECISION1, DECISION11, DECISION13, DECISION14, DECISION19, DECISION20), 구조는 같은 폴더 [`design.md`](../../../docs/features/assistant-hub/design.md) 의 「PR1 `agent_delegate` 의 기다리기」, 값은 `DelegationProperties` 의 `delegate-wait-max`
- [ADR-017](../../../docs/adr/ADR-017-무엇을-할지는-hermes-가-정하고-control-plane-은-경계만-갖는다.md) 의 「`agent_delegate` 는 기다리지 않는다」 와 [ADR-040](ADR-040-위임-결과는-control-plane-이-부모-대화의-다음-turn-을-열어-전한다.md) 의 「부모는 맡긴 뒤 기다리지 않는다」 를 대화 turn 의 루트가 바로 맡긴 위임에서 대체한다. 손자 위임과 상한을 넘긴 결과는 ADR-040 그대로다.

### 결정

**대화 turn 의 루트 실행이 `agent_delegate` 를 부르면, Control Plane 이 그 도구 호출 안에서 결과를 기다린다.**
한 답에서 기다리는 시간은 그 루트가 처음 기다리기 시작한 때부터 흐른 시간으로 세고 상한은 설정 값이다.
상한 안에 끝나면(실패와 취소 포함) 결과를 도구 결과로 돌려주고 전했다고 적는다. 그 결과로는 자동 답을 열지 않는다.
넘으면 `RUNNING` 과 「짧게 답을 끝내라」 는 지시를 돌려주고, 결과는 ADR-040 대로 다음 답이 전한다. 상한이 0 이면 기다리지 않는다.

### 맥락

2026-11-21 운영에서 비서가 쇼핑 담당에게 맡기면 「맡겼어」 답과 결과로 연 자동 답이 따로 생기고 내용이 겹쳤다.
사용자는 늘 비서와만 대화하기로 했고(같은 스펙 DECISION3), 맡기는 일이 늘면 이 나뉨이 더 자주 보인다.
기다리는 길은 먼저 살펴보기에만 있었다(ADR-080 의 `agent_status` `wait_seconds`). Hermes 의 MCP 도구 호출 제한은 기본 300초다(`hermes/docs/hermes-contract.md`).

### 대안 기각

- **모델이 `agent_status` 의 `wait_seconds` 로 기다리게 한다**: 모델이 부르지 않으면 답이 그대로 나뉜다. 기다림을 정해진 동작으로 두지 못한다
- **맡기는 답을 지시문으로 한 줄로 줄이기만 한다**: 답이 둘인 것은 그대로이고 지시를 늘 지킨다고 보장하지 못한다
- **화면에서 두 답을 합쳐 보인다**: 저장과 화면이 함께 바뀌고, 결과 답이 오기 전의 화면이 비게 된다
- **맡김마다 상한을 따로 준다**: 맡김이 여럿이면 사용자가 그 배수만큼 기다린다

### 결과

- 얻는 것: 짧은 맡김은 답 하나로 끝난다. 결과 전달 규칙(ADR-040, ADR-075)은 상한을 넘긴 맡김에만 쓰인다
- 감당할 것: 기다리는 동안 MCP 요청 스레드 하나와 사용자 turn 자리를 쥔다. 동시 수는 루트당과 전체 위임 한도가 묶는다. 루트별 예산은 프로세스 메모리에 있어 서버가 하나여야 한다. 상한을 Hermes 도구 호출 제한보다 길게 두면 도구가 끊긴다
- 확인 방법: 기다림과 예산의 단위 테스트, 자동 답이 생기지 않는 e2e, 배포 뒤 왕복(스펙 「배포 뒤 왕복 확인」)
