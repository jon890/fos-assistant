## ADR-20261112 / mention-delegation: 사용자가 `@` 로 고른 에이전트에게는 Control Plane 이 바로 맡기고, 그 주고받음을 대화 에이전트의 다음 turn 에 싣는다

- **status**: `accepted`
- Date: 2026-11-12
- **decision-type**: `architecture`
- **areas**: `product, runtime`
- **현재 규칙의 원본**: [에이전트 팀](../features/agent-team.md) 의 「`@` 로 바로 맡기기」, 스펙 [`docs/features/agent-team/spec.md`](../features/agent-team/spec.md) 의 REQ7 과 DECISION9, DECISION20, DECISION21(사용자 확인 2026-11-10), 설계 [`design.md`](../features/agent-team/design.md) 의 「PR4 @ 멘션」
- [ADR-017](ADR-017-무엇을-할지는-hermes-가-정하고-control-plane-은-경계만-갖는다.md) 의 「무엇을 할지는 Hermes 가 정한다」 에 예외 하나를 둔다. 경계와 `agent_*` 도구의 결정은 그대로다.

### 결정

**대화 중 사용자가 입력창의 `@` 로 고른 에이전트에게는 Control Plane 이 모델 판단 없이 바로 맡긴다.** 보내기 요청의 `delegateTo` 가 그 에이전트이고, Control Plane 은 그 에이전트로 대화의 turn 하나를 새 Hermes session 에서 돌린다. 답은 그 대화에 맡은 에이전트의 답으로 남는다.
그 실행은 대화의 루트 실행이고 `delegation_key` 가 없다. 자동 결과 전달(ADR-040)을 타지 않으므로 대화의 에이전트는 그사이 답하지 않는다.
**대화의 에이전트는 다음 자기 turn 입력 앞에서 그 주고받음을 받는다.** 위임 결과 전달과 같은 출처 머리줄을 쓴다.

실행할 에이전트는 요청 본문이 고르지만 요청자가 쓸 수 있고 켜진 에이전트만 받는다(`AgentService.requireStartable`). 새 대화의 `agentCode` 와 같은 판정이라 [rule-47](../code-rules/rule-47.md) 의 「요청자의 바인딩에서만 고른다」 를 지킨다.

### 맥락

- ADR-017 은 무엇을 맡길지를 모델이 정하게 했다. 사용자가 「쇼핑 담당에게」 라고 골랐는데도 비서가 맡기지 않거나 다른 에이전트에게 맡기면 사용자는 고른 대로 되리라 믿지 못한다(스펙 DECISION9).
- 비서를 거치면 모델 실행이 한 번 더 들고 느리다. 사용자는 맡은 에이전트의 답을 그대로 보기를 골랐다(스펙 DECISION20).
- 대화 에이전트의 Hermes session 에는 맡김이 없다. 다음 turn 에 「그중 제일 싼 걸로」 처럼 이어 물으면 비서가 앞의 맡김을 알아야 한다.

### 버린 대안

| 대안 | 버린 까닭 |
| --- | --- |
| 비서에게 「쇼핑 담당에게 맡겨」 라는 지시를 붙여 보낸다 | 맡길지는 여전히 모델이 정한다. 모델 실행이 한 번 더 들고 비서가 결과를 다시 쓴다 |
| `agent_delegate` 의 자식 실행으로 돌린다 | 자식은 부모 실행이 있어야 하고 답을 대화에 남기지 않는다. 결과는 자동 turn 이 비서에게 전해 비서가 끼게 된다 |
| 맡김마다 칸(`chat_message` 의 에이전트, 실행의 표시)을 둔다 | 대화의 에이전트가 바뀌지 않아 「부모가 없고 에이전트가 대화의 에이전트와 다른 실행」 으로 가려진다. 칸을 두면 이관과 옛 줄 채우기가 는다 |
| 주고받음을 매 실행의 instructions 에 싣는다 | instructions 는 실행마다 조립해 session 이력에 남지 않는다. 입력에 실어야 다음 turn 부터 session 이 안다 |

### 감당할 것

- 맡김의 입력에 대화의 앞 줄이 들어간다. 앞 줄은 같은 사용자의 대화이고, 다른 에이전트의 답에 외부 글이 들 수 있어 `<external-data>` 로 감싼다.
- 대화의 에이전트가 답 줄 없이 실패한 turn 에 실은 맡김은 다시 싣지 않는다. 그 입력이 session 에 남았는지 알 수 없다.
- 맡은 에이전트가 다시 맡긴 일의 결과는 지금처럼 대화의 에이전트가 자동 turn 으로 전한다.
