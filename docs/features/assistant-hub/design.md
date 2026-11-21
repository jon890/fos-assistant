# 비서 중심 대화 기술 설계

스펙은 [`spec.md`](spec.md)(확인 2026-11-21)다. PR1 은 이 문서대로 구현한다. PR2, PR3 은 모듈과 경계만 정하고, 그 PR 을 시작할 때 코드 경로를 다시 읽어 이 문서의 해당 절을 고친다.

## 요구사항 연결

| ID | 해결 방법 |
| --- | --- |
| REQ1 | `McpToolService.delegate` 가 실행 번호를 받은 뒤, 대화 turn 의 루트가 바로 맡긴 위임이면 새 `DelegationWaiter` 가 남은 예산만큼 `RunningDelegation.awaitEnded` 를 기다린다. 끝났으면 `agent_status` 와 같은 모양(`statusOf`)으로 결과를 넣고 `markResultDelivered` 를 적어 자동 답이 열리지 않게 한다 |
| REQ2 | 예산 안에 끝나지 않으면 `RUNNING` 과 「짧게 답을 끝내고 결과를 짐작하지 않는다」 지시 한 줄을 넣는다. 결과는 지금 깨우기(ADR-040)가 그대로 전한다. 상한은 `assistant.delegation.delegate-wait-max`(기본 2분, 0 이면 기다리지 않음) |
| REQ3 | `AgentRunner` 가 맡긴 실행도 Hermes 사건 스트림을 열어 도구 사건을 그 자식 실행 줄에 적는다(ADR-090 과 같은 경로). 트리와 `uses` 는 `root_execution_id` 로 자식을 이미 읽어 그대로 보인다. 도는 동안의 줄은 자식 사건을 Spring 사건으로 내고 `chat` 이 대화 단위 SSE 의 새 사건 `delegation_activity` 로 보낸다 |
| REQ4 | (PR2) 새 대화 만들기와 보내기가 대화의 에이전트를 요청자의 기본 비서로 정한다(`PrimaryAgents`). 웹은 `/new` 의 이름표 목록, `?agent=`, 「대화하기」 단추를 지운다 |
| REQ5 | (PR2) 대화 응답에 「이어 쓸 수 있는가」 를 내고, 보내기·다시 생성·결과 다시 전달이 기본 비서가 아닌 대화면 거절한다. 자동 turn 과 예약 turn 은 막지 않는다 |
| REQ6 | (PR2) `TurnIntent.Mention` 경로와 `delegateTo` 를 지우고 거절한다. 지난 맡김을 그리는 읽기 경로(`MentionLines`)는 남긴다. 지시문 구역 「맡길 수 있는 에이전트」 에 이름을 말하면 맡긴다는 줄을 더한다 |
| REQ7 | (PR2) 작업 만들기와 고치기(화면, `task_` 도구)가 에이전트를 기본 비서로 정한다. 멈추기·다시 켜기는 에이전트를 건드리지 않는다 |
| REQ8 | (PR2) `ServiceConceptsInstructions` 의 에이전트 바꾸기 안내를 맡기기로 바꾸고 평가 세트 문항을 고친다 |
| REQ9 | (PR3) 붙이기 경로(`AgentConnectionController`, 연결 뒤 고르기, `connector_attach` 승인 카드)가 기본 비서를 대상에서 뺀다. 카드는 `SpecialistAgentCreator` 로 새 전문 에이전트를 만들어 붙인다 |
| REQ10 | (PR3) `/` 목록 API 가 맡길 수 있는 전문 에이전트의 켜진 스킬을 함께 준다. 보내기에 「에이전트 · 스킬」 을 받으면 Control Plane 이 `ChildExecutionRunner` 로 그 에이전트에 스킬 커맨드를 맡기는 자식 실행을 만들고, 대화 turn 은 열지 않는다. 결과는 지금 깨우기로 전한다. 예약 작업 발화도 같은 판정을 쓴다 |

## 책임과 경계

| 모듈 | 이번에 갖는 것 |
| --- | --- |
| `orchestration` | 맡긴 실행의 기다리기와 그 예산(REQ1, REQ2), 맡긴 실행의 사건 스트림(REQ3), 스킬 맡기기 실행(REQ10) |
| `mcp` | `agent_delegate` 결과의 모양과 지시 한 줄, 도구 설명(REQ1, REQ2) |
| `chat` | 맡긴 실행 사건을 대화 SSE 로 보내기(REQ3), 새 대화의 에이전트와 읽기 전용 판정(REQ4, REQ5), `@` 지우기(REQ6) |
| `task`, `connector` | 예약 작업의 에이전트(REQ7), 붙이기 대상(REQ9) |
| `web` | 도는 답의 작업 과정에 자식 도구 줄 넣기(REQ3), 입구 화면(REQ4-REQ6), `/` 목록(REQ10) |

의존 방향은 지금 그대로다. `orchestration` 은 `chat` 의 서비스를 부르지 않고 Spring 사건만 낸다(`ArchitectureRules.ORCHESTRATION_DOES_NOT_CALL_CHAT_SERVICE`). 자식 사건의 중계도 `DelegationStarted` 와 같은 꼴의 사건으로 한다.

## 인터페이스와 데이터

### PR1 `agent_delegate` 의 기다리기

- 대상(스펙 DECISION11): origin 이 대화 turn 의 루트 실행(`parentExecutionId == null`)이고 먼저 살펴보기 트리가 아니다. 사용자 질문, 대기 줄, 자동 turn, 예약 turn 이 모두 루트다. 손자 위임은 지금처럼 바로 돌려준다.
- 예산(스펙 DECISION13, DECISION20): 루트 실행마다 첫 기다림을 시작한 시각을 `DelegationWaiter` 의 메모리 맵에 둔다. 마감은 그 시각 더하기 상한이다. Hermes 는 우리 MCP 도구를 한 번에 하나씩 부르므로 같은 답의 맡김은 차례로 같은 마감까지 기다린다. 항목은 마감에서 상한만큼 더 지나면 다음 접근 때 지운다. 서버 하나 전제는 루트 잠금과 같다.
- 중지: 기다리는 동안 루트 turn 의 중지 표시(`TurnCancellation`)가 서면 기다림을 곧바로 푼다. 전달 표시는 루트 turn 이 아직 돌고 중지가 확정되지 않았을 때만 적는다. 아니면 적지 않고 `RUNNING` 이나 끝난 상태만 돌려주어 결과를 지금 깨우기(ADR-040)와 중지 뒤 규칙에 맡긴다. 기다림은 중지 표시와 자식의 끝 가운데 먼저 오는 것으로 풀리도록 짧은 간격으로 나눠 기다린다.
- 결과 모양: 끝났으면 `agent_status` 와 같은 `execution_id`, `status`, `output`(연결이 붙은 에이전트면 `<external-data>` 로 감쌈), `error_code` 다. `FAILED`, `CANCELLED` 도 끝난 결과다(스펙 DECISION19). `RUNNING` 이면 `execution_id`, `status` 와 `note` 한 줄 「아직 끝나지 않았다. 결과는 끝나면 다음 답으로 자동 전달된다. 이번 답은 누구에게 무엇을 맡겼는지 한 줄로 끝내고 결과를 짐작해 쓰지 않는다」 를 넣는다.
- 전달 표시: 끝난 결과를 돌려줄 때 `markResultDelivered` 를 적는다. `agent_status` 가 이미 쓰는 규칙이다(`McpToolService.markDeliveredIfFinished`).
- 도구 설명(`McpToolCatalog`): `agent_delegate` 에 「대화에서는 정한 시간까지 결과를 기다려 함께 돌려준다」 를 더한다. 상한 값은 설정에서 읽어 넣는다.
- 설정: `DelegationProperties.delegateWaitMax`(`assistant.delegation.delegate-wait-max`, 기본 `2m`). 0 은 허용하고 음수는 기동 실패다. `LiveProperties` 라 다시 띄우지 않고 바꾼다.

### PR1 맡긴 실행의 사건

- `AgentRunner.run` 은 지금 `submit` 뒤 `awaitCompletion` 만 한다. 대화 turn 의 한 번에 받는 경로(`ChatRunEvents`)처럼 제출 뒤 사건 스트림을 열어 그 자식 실행 줄에 적는다. 스트림을 열지 못해도 실행은 이어 가고 사건만 없다(ADR-090 과 같다).
- 순서 번호: 지금 `AgentRunner` 는 시작 1, 끝 2 를 직접 넣는다. 스트림 사건과 겹치면 `(execution_id, sequence)` 유일 제약에 걸려 사건이 조용히 사라진다. 실행마다 counter 하나를 두고 시작, 스트림 사건, 끝을 모두 그 counter 로 적는다(`ChatRunEvents` 의 `pending.counter()` 와 같은 꼴).
- 가리기: 기록기가 아니라 스트림을 열 때 넘기는 `ToolDetailScope` 가 연결 도구 내용을 가린다. 자식 스트림에도 그 에이전트의 연결 도구 접두사와 트리 이력을 넘긴다(대화 turn 과 같은 값, ADR-047, ADR-083).
- 관측 상태: 제출 전에 관측 시작을, 스트림이 끝날 때 관측 끝을 적는다(`beginEventObservation`, `finishEventObservation`). 스트림 대기 상한은 `hermes.run-timeout` 이고, 실행이 끝나면 스트림을 닫는다.
- 사건마다 `DelegatedRunActivity(conversationId, rootExecutionId, executionId, event)` Spring 사건을 낸다. `chat` 의 리스너는 대화 단위 SSE(`ConversationEventHub`)로 `delegation_activity` 사건을 publish 만 한다. 보는 사람마다의 가리기는 구독 쪽 `ChatEvent.forViewer` 가 하므로, 그 메서드에 `delegation_activity` 의 도구 칸 가리기(`ToolDetailPolicy`)와 내부 값 빼기를 더한다. 칸은 자식 실행 번호, 맡은 에이전트 이름표, 도구 사건 하나(대화 스트림의 `tool` 사건과 같은 칸)다.
- 웹: 도는 답의 작업 과정 상태에 자식 실행 번호를 열쇠로 「도우미」 줄을 만들고 그 아래에 도구 줄을 넣는다. 끝난 뒤나 새로 고친 뒤는 지금처럼 트리(`fromTree`)가 같은 줄을 만든다. `fromTree` 는 이미 자식 노드의 사건을 도우미 줄과 도구 줄로 그린다.
- 맡겨 둔 도우미: 답이 끝날 때 아직 도는 맡긴 실행의 도우미 줄은 지금 규칙대로면 「결과를 받지 못함」 이 되고 접힌 줄이 「끝까지 하지 못했어요」 로 바뀐다. 맡긴 실행(자식 실행 번호를 열쇠로 둔 줄)은 새 상태 「맡겨 둠」 으로 남기고 답의 실패 판정에서 뺀다. 트리(`fromTree`)도 위임 키가 있는 도는 자식을 같은 상태로 그린다. 문장은 「끝나면 이어서 알려 드려요」 이고 `docs/features/execution.md` 의 하위 에이전트 상태 표에 더한다.
- 새 표와 칸은 없다. 사건은 기존 `execution_event` 에 자식 실행 번호로 쌓인다.

### PR2, PR3

- 새 대화: `POST /conversations` 와 보내기의 에이전트 입력을 받지 않거나 무시하지 않고, 기본 비서가 아니면 `VALIDATION_FAILED` 로 거절한다. 웹이 먼저 바뀌어도 서버가 막는다.
- 읽기 전용: 대화 응답에 `writable` 을 더한다. 판정은 `PrimaryAgents` 로 지금 기본 비서인지다(스펙 DECISION22). 점검 대화(`purpose = CHECK`)는 판정 밖이다.
- 스킬 맡기기: 보내기 요청에 `skillAgent`(에이전트 코드)와 스킬 커맨드를 받는다. 칸 이름과 예약 작업 지시의 글 꼴은 PR3 설계에서 정한다.
- [ADR-20261112 / mention-delegation](../../adr/ADR-20261112-mention-delegation.md) 은 PR2 가 대체한다. 그 PR 에서 status 를 바꾸고 `archive/` 로 옮긴다.

## 실패와 일관성

- 기다리는 동안 사용자가 중지하면 지금 중지가 루트 turn 에 붙은 자식을 멈춘다(`docs/features/chat.md` 「중지」). `awaitEnded` 는 자식이 끝나면 풀리고, 결과는 `CANCELLED` 로 돌아간다. turn 은 중지로 끝난다.
- 자식이 끝난 순간 `DelegationFinished` 로 깨우기가 돌아도, 부모 turn 이 대화 잠금을 쥐고 있어 열지 않고 turn 이 닫힐 때 다시 본다. 그 전에 도구가 `markResultDelivered` 를 적으므로 같은 결과로 자동 답이 생기지 않는다. 도구가 적기 전에 서버가 내려가면 기동 훑기가 지금처럼 자동 답을 연다. 결과를 잃지 않는다.
- 기다리는 동안 MCP 요청 스레드 하나를 쥔다. Hermes 의 MCP 도구 호출 제한은 기본 300초이고 우리 profile 틀은 바꾸지 않는다([`hermes/docs/hermes-contract.md`](../../../hermes/docs/hermes-contract.md) 「제한 시간은 우리가 정하지만 상한은 있다」). 2분은 그 안이다. 동시 기다림의 수는 루트당 동시 위임 한도와 전체 한도가 묶는다.
- 사건 스트림이 끊기거나 늦게 와도 자식의 끝은 `awaitCompletion` 이 정한다. 끝난 뒤 온 사건은 버린다(ADR-090 과 같다).
- 배포 순서: PR1 은 Control Plane 과 웹만 바꾼다. 웹이 먼저 떠도 `delegation_activity` 를 받지 않을 뿐이고, Control Plane 이 먼저 떠도 웹이 모르는 사건을 버린다.

## 검증 전략

| ID | 경계 | 환경 |
| --- | --- | --- |
| REQ1, REQ2 | `AgentDelegationService` 기다리기와 예산의 단위 테스트(가짜 시계, 끝남·실패·마감·두 맡김), MCP 도구 결과와 전달 표시의 통합 테스트, 자동 답이 생기지 않는 e2e | backend 테스트, `test/e2e/` 가짜 Hermes |
| REQ2 | 상한 0 이면 지금과 같은 결과 | backend 테스트 |
| REQ3 | 자식 실행에 도구 사건이 저장되고 트리 API 에 보이는 e2e, `delegation_activity` 의 가리기 단위 테스트, 도는 답의 블록에 도우미 아래 도구 줄이 보이는 브라우저 테스트 | `test/e2e/`, `web/test/browser/` |
| REQ1-REQ3 | 배포 뒤 왕복(스펙 「배포 뒤 왕복 확인」) | 운영, 평가 계정 |
| REQ4-REQ10 | PR2, PR3 설계 때 정한다 | |

## 결정

| ID | 결정 | 답 | 누가 | 근거 |
| --- | --- | --- | --- | --- |
| DECISION1 | 기다리는 자리 | `agent_delegate` 도구 안에서 Control Plane 이 기다린다. 모델에게 `agent_status` 의 `wait_seconds` 를 부르게 하지 않는다 | 작성자 | 모델이 부르지 않으면 답이 나뉜다. 기다림을 정해진 동작으로 둬야 스펙 REQ1 이 늘 성립한다 |
| DECISION2 | 예산을 두는 곳 | 루트 실행별 메모리 맵 | 작성자 | 표를 늘리지 않는다. 루트 잠금과 중지 표시도 서버 하나를 전제로 메모리에 있다 |
| DECISION3 | 자식 사건의 실시간 중계 | 새 SSE 사건 `delegation_activity` 를 대화 단위 SSE 로 보낸다. 웹이 트리를 주기적으로 읽지 않는다 | 작성자 | 도는 답은 흘러온 사건으로 그리는 지금 규칙(`docs/features/execution.md` 「작업 과정 패널」)과 맞다. 맡긴 줄도 같은 길(`DelegationLineNotifier`)로 간다 |
| DECISION4 | 장기 결정 기록 | [ADR-20261121 / delegate-wait](../../../backend/docs/adr/ADR-20261121-delegate-wait.md) 를 남기고 ADR-017 「`agent_delegate` 는 기다리지 않는다」 와 ADR-040 「부모는 맡긴 뒤 기다리지 않는다」 를 대화 turn 에서 대체한다 | 작성자 | 되돌리면 도구 계약과 결과 전달 규칙을 함께 바꿔야 한다 |

## 결정 기록 연결

| 결정 | 종류 | ADR | 현재 규칙의 원본 |
| --- | --- | --- | --- |
| DECISION1, DECISION2 | architecture | [ADR-20261121 / delegate-wait](../../../backend/docs/adr/ADR-20261121-delegate-wait.md) | `AgentDelegationService`, `DelegationProperties`, `docs/features/agent-skill.md` 「다른 에이전트에게 맡기기」 |
