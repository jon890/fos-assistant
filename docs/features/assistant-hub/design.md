# 비서 중심 대화 기술 설계

스펙은 [`spec.md`](spec.md)(확인 2026-11-21)다. PR1 은 이 문서대로 구현했다. PR2, PR3 은 그 PR 을 시작할 때 코드 경로를 다시 읽어 이 문서의 해당 절을 고쳤다.

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
| REQ9 | (PR3) 화면의 붙이기(`AgentConnectionController`)가 기본 비서를 거절하고, 연결 뒤 고르기와 에이전트 상세가 기본 비서에 붙이기를 보이지 않는다. `connector_attach` 승인 카드는 늘 새 전문 에이전트(`SpecialistAgentCreator`)를 만들어 붙이고, 그 연결이 붙은 전문 에이전트가 있으면 카드 없이 그 에이전트를 알려 맡기게 한다. 아래 「PR3」 |
| REQ10 | (PR3) 새 목록 API 가 맡길 수 있는 전문 에이전트의 켜진 스킬을 주고, 웹이 `/` 목록에 비서 스킬과 함께 「에이전트 · 스킬」 로 보인다. 고르고 보내면 새 보내기 API 가 질문을 저장하고 그 에이전트의 실행을 대화 turn 없이 띄운다(`SkillDelegations`). 결과는 지금 깨우기(ADR-040)가 결과 도착 줄과 비서 답으로 전한다. 예약 작업 지시가 `/에이전트 이름 · 스킬` 로 시작하면 같은 경로로 맡긴다. 아래 「PR3」 |

## 책임과 경계

| 모듈 | 이번에 갖는 것 |
| --- | --- |
| `orchestration` | 맡긴 실행의 기다리기와 그 예산(REQ1, REQ2), 맡긴 실행의 사건 스트림(REQ3), 스킬 맡기기의 목록, 보내기 API, 실행(REQ10) |
| `mcp` | `agent_delegate` 결과의 모양과 지시 한 줄, 도구 설명(REQ1, REQ2) |
| `chat` | 맡긴 실행 사건을 대화 SSE 로 보내기(REQ3), 새 대화의 에이전트와 읽기 전용 판정(REQ4, REQ5), `@` 지우기(REQ6), 스킬 맡기기의 질문 저장과 결과 전달 조회(REQ10) |
| `task`, `connector` | 예약 작업의 에이전트(REQ7), 붙이기 대상(REQ9) |
| `web` | 도는 답의 작업 과정에 자식 도구 줄 넣기(REQ3), 입구 화면(REQ4-REQ6), `/` 목록(REQ10) |

의존 방향은 지금 그대로다. `orchestration` 은 `ChatService` 를 부르지 않고 Spring 사건만 낸다(`ArchitectureRules.ORCHESTRATION_DOES_NOT_CALL_CHAT_SERVICE`). 자식 사건의 중계도 `DelegationStarted` 와 같은 꼴의 사건으로 한다. 스킬 맡기기의 질문 저장만 `chat.nextturn` 의 좁은 서비스(`SkillDelegationQuestions`)를 부른다. `chat` 은 `orchestration` 보다 아래 층이라 보내기 API 를 `chat` 에 둘 수 없다.

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

### PR2 입구를 비서로 모으기

운영 코드가 PR 하나의 상한을 넘어 둘로 나눈다(DECISION5). 앞 PR 은 새 대화와 `@`, 「대화하기」, 지시문과 평가 세트(REQ4, REQ6, REQ8)이고, 뒤 PR 은 읽기 전용 대화와 예약 작업(REQ5, REQ7)이다. 앞 PR 만 배포된 동안 지난 전문 에이전트 대화는 지금처럼 이어 쓸 수 있다.

- **새 대화의 에이전트**: `chat.conversation.application` 의 새 `ConversationEntry` 가 요청자의 기본 비서(`PrimaryAgents.of`)를 정한다. 새 대화를 만드는 두 길(`ChatTurnRouting.route` 의 새 대화, `ChatConversationManagement.startEmpty`)이 이것만 쓴다. 요청의 `agentCode` 가 비었으면 기본 비서이고, 기본 비서와 다른 코드면 `VALIDATION_FAILED` 다. 기본 비서가 없으면 `AGENT_NOT_FOUND`, 꺼졌으면 `AGENT_DISABLED` 다. 웹이 먼저 바뀌어도 서버가 막는다.
- **읽기 전용**(뒤 PR): `ConversationEntry` 의 `writable` 이 대화의 에이전트가 지금 요청자의 기본 비서인지 본다(스펙 DECISION22). 점검 대화(`purpose = CHECK`)는 늘 참이다(DECISION16). 목록처럼 여러 줄을 볼 때는 기본 비서를 한 번만 읽는 판정(`writableFor(user)`)을 쓴다. 사람이 쓰는 길만 `requireWritable` 로 막고 거절은 `VALIDATION_FAILED` 다.
  - 보내기와 흐르는 보내기: `ChatTurnRouting.routeSend` 가 이어 쓰는 대화의 주인과 지움을 확인한 바로 뒤, 에이전트 판정과 스킬 목록 조회보다 먼저 본다. `/이름` 도 평문과 같은 오류로 거절하고 Hermes 를 부르지 않는다. 지운 에이전트의 대화는 건너뛰어 `AGENT_NOT_FOUND` 로 둔다. `route` 는 예약 turn, 먼저 살펴보기, 대기 메시지 실행, 결과 전달이 함께 쓰므로 거기서 막지 않는다.
  - 다시 생성과 결과 다시 전달: `ChatController` 가 SSE 를 열기 전에 본다. `ChatService` 가 파일 길이 상한(500줄)에 닿아 있다.
  - 대기 메시지 넣기와 끼우기, 바로 보내기: `PendingMessageController` 가 본다. 이미 쌓인 대기 줄의 실행과 취소는 막지 않는다.
  - 대화 응답(`ConversationView`)에 `writable` 을 더한다. 목록, 한 줄 읽기, 이름 바꾸기, 모델 단계 고르기, 대화방(`RoomController`, 늘 참)이 모두 낸다.
  - 자동 결과 전달 turn, 예약 turn, 먼저 살펴보기는 막지 않는다. 그 대화에 맡겨 둔 일의 결과와 그 에이전트의 예약 작업 결과는 지금처럼 쌓인다.
- **`@` 맡기기**: 보내기 요청의 `delegateTo` 가 있으면 `VALIDATION_FAILED` 다. `TurnIntent.Mention` 과 `ChatTurnRouting.routeMention` 을 지운다. 지난 맡김을 그리는 `MentionLines` 와 다음 turn 입력에 넣는 `MentionInputs` 는 남긴다. [ADR-20261112 / mention-delegation](../../adr/ADR-20261112-mention-delegation.md) 은 새로 맡기는 부분만 대체되어 파일은 옮기지 않고 그 머리와 목록에 적는다(일부만 대체된 ADR 은 옮기지 않는다는 `docs/adr/INDEX.md` 규칙).
- **예약 작업**(뒤 PR): `TaskService` 의 만들기와 고치기가 에이전트를 요청자의 기본 비서(`PrimaryAgents`)로 정한다. 기본 비서가 없으면 `AGENT_NOT_FOUND`, 꺼졌으면 `AGENT_DISABLED` 다. 작업 입력(`TaskInput`, 화면 요청 `TaskRequest`)의 `agentCode` 칸을 지운다. 옛 화면이 보내도 JSON 의 모르는 칸이라 버린다. 화면 API 와 `task_` 도구(`TaskAgentRequests`)와 배포 뒤 확인이 모두 이 길을 쓴다. 멈추기·다시 켜기는 에이전트를 바꾸지 않는다(DECISION23). 고쳐 비서로 바뀐 `SINGLE` 작업은 발화 때 대화의 에이전트가 작업의 에이전트와 다르면 새 대화를 여는 지금 규칙(`TaskRunStarter`)으로 새 비서 대화에 쌓인다. 지시 맨 앞의 `/이름` 은 지금처럼 그 작업의 에이전트(비서)의 스킬로 읽는다. 만들거나 고칠 때 그 이름이 기본 비서의 켜진 스킬이 아니면 저장하지 않고 `SKILL_COMMAND_UNKNOWN` 이다(`SkillCommandCatalog`). 전문 에이전트에만 있던 스킬을 부르는 작업은 시각만 고쳐도 비서로 바뀌어 다음 발화가 실패하기 때문이다. 「에이전트 · 스킬」 꼴은 PR3(REQ10, DECISION27)이다.
- **지시문**: `ServiceConceptsInstructions` 의 에이전트 고르기 줄을 「대화의 에이전트는 바뀌지 않고 그 에이전트의 일은 내가 맡겨 처리한다, 링크를 붙이지 않는다」 로 바꾼다. `DelegationTargetsInstructions` 머리에 「사용자가 아래 에이전트의 이름을 말하며 맡기라고 하면 그 에이전트에게 맡긴다」 를 더한다. 평가 세트(`test/model-eval/concepts/questions.json`)의 바꾸기 문항은 링크가 없는 답을 기대하고, 이름으로 맡기는 문항은 `agent_delegate` 를 기대한다.
- **테스트 준비**: 기본 비서는 허용 목록 줄로만 찾는데 테스트 사용자는 그 줄 없이 만든다. backend 통합 테스트는 `testsupport.TestPrimaryAgents` 로 대화할 에이전트를 그 사용자의 기본 비서로 두고 검사마다 지운다. e2e 와 브라우저 테스트는 test-support API(`/api/v1/test-support/primary-agent`, `/api/v1/test-support/chat/conversations`)로 기본 비서를 정하거나 다른 에이전트와의 지난 대화를 만든다. 다른 에이전트의 실행 자체를 보는 시나리오는 test-support 로 그 에이전트와 대화를 만든다. 지난 대화가 읽기 전용이 된 뒤로는 대화를 만들 때 그 에이전트를 잠시 기본 비서로 두고(`PrimaryAgentTestSupportController.useTemporarily`), e2e 는 시나리오마다, 브라우저 테스트는 검사마다 `/api/v1/test-support/primary-agent/restore` 로 되돌린다. 기본 비서는 지울 수 없으므로(409) 검사가 만든 에이전트를 지우기 전에도 되돌린다. 바꾸기 전의 값은 test-support 가 메모리에만 들고 있어, e2e 가 Control Plane 을 다시 띄울 때는 그 값을 읽어 두었다가 다시 넣는다(`/api/v1/test-support/primary-agent/originals`). 한 profile 은 한 사람의 기본 비서만 될 수 있어(`allowed_person` 의 profile 유일 제약) 같은 GROUP 에이전트로 두 사람이 보내는 시나리오는 차례로 보낸다. 기본 비서에는 화면에서 연결을 붙이지 않으므로(PR3), e2e 의 붙이기 도우미는 붙이기 전에 기본 비서를 되돌리고, 비서에 붙은 연결이 필요한 시나리오는 배포 전부터 붙은 연결을 흉내 내는 `/api/v1/test-support/primary-agent/legacy-connections` 로 붙인다.
- **웹**: 새 대화 화면은 목록의 `primary` 에이전트로 고정하고 이름표 목록과 `@` 고르기를 그리지 않는다. `?agent=` 는 읽지 않는다. 에이전트 상세, 전문 에이전트 만들기 제안, 연결 뒤 고르기의 「대화하기」 를 지운다. 대화 응답의 `writable` 이 거짓이면 입력창 대신 안내 줄과 대화방 링크를 보이고 다시 생성과 다시 전달 단추를 그리지 않는다. 이미 쌓인 대기 메시지는 보이고 취소할 수 있게 두고 「보내기」 만 그리지 않는다. 작업 화면의 에이전트 칸을 지운다.

### PR3

- **화면의 붙이기**: `AgentConnectionController` 가 붙이기와 목록에서 대상이 기본 비서인지 본다(`ConnectorBindingService` 는 파일 길이 상한, `connector.connection.application` 은 클래스 수 상한에 닿아 있다). 대상이 요청자의 기본 비서(`PrimaryAgents`)면 `PRIMARY_AGENT_NO_CONNECTIONS`(409)로 거절하고 아무것도 바꾸지 않는다. 떼기는 그대로다. `bind` 자체는 막지 않는다. 전문 에이전트 만들기가 실패해 비서에서 뗀 연결을 되돌려 붙이는 길(`SpecialistAgentCreator.withdraw`)이 쓰기 때문이다.
- **에이전트 상세와 연결 뒤 고르기**: 에이전트의 연결 목록 응답(`AgentConnectionsView.blockedReason`)에 기본 비서면 `PRIMARY_AGENT` 를 낸다. 웹은 그 까닭이면 붙이기 단추와 「외부 서비스 연결하기」 를 숨기고 이미 붙은 연결의 떼기만 둔다. 연결 화면의 에이전트 고르기(`agentChoices`)는 목록의 `primary` 에이전트를 후보에서 뺀다.
- **붙이기 승인 카드**: `ConnectorAttachRequester` 는 대화의 에이전트를 보지 않는다. 연결이 준비됐고 그 연결이 붙은 전문 에이전트(기본 비서가 아니고 켜진 에이전트)가 있으면 카드 없이 `specialist_attached` 와 그 에이전트의 코드와 이름을 돌려주고, 모델에게 `agent_delegate` 로 맡기라고 한다. 기본 비서에 붙어 있으면(배포 전부터 붙은 연결) 지금처럼 `already_attached` 와 쓸 수 있는지(`ready`, `pending`)다. 쓸 수 없는 전문 에이전트(꺼졌거나 공개 범위가 바뀐 에이전트)에만 붙어 있으면 카드 없이 `specialist_unavailable` 과 연결 화면 링크다. 그 밖에는 카드를 띄운다. 카드 인자는 서비스, 만들 에이전트 이름(`<서비스 이름> 담당`, 마법사의 기본값과 같다), 도구 요약, 주의, `connector_id`, `agent_name` 이다. 새 에이전트는 셸과 파일 도구가 없어 셸 경고를 붙이지 않는다. 승인 줄의 키는 `<connector_id>:new` 다.
- **카드 승인**: `ConnectorAttachRunner` 가 `SpecialistAgentCreator.create(user, connectorId, introduction, detach=false)` 로 만들고 붙인다. 소개는 이름, 「<서비스> 일을 맡아요」, 기본 아이콘이다(웹 `specialistDraft` 와 같다). 알림 줄은 「「Gmail 담당」 을 만들어 「Gmail」 을 붙였어요」 에 지금의 반영 안내를 잇는다. 배포 전에 뜬 옛 카드(인자에 `agent_code` 만 있음)는 승인해도 붙이지 않고 다시 부탁해 달라고 알린다. 카드 제목은 「새 전문 에이전트를 만들어 붙이기」 다.
- **`/` 목록**: 새 `GET /api/v1/chat/skill-delegations/targets` 가 요청자가 쓸 수 있고 켜진 에이전트(`AgentService.readableBy`, `agent_list` 와 같은 기준) 가운데 기본 비서를 뺀 에이전트마다, 비서의 `/` 목록과 같은 기준의 스킬 이름(화면 스킬 목록에 보이는 켜진 스킬, Hermes 기본 스킬은 빼고 커맨드로 부를 수 있는 이름만)을 준다. 보낼 때 확인은 지금 커맨드처럼 `SkillCommandCatalog.enabledNames` 가 한다. 스킬이 없는 에이전트는 빼고, 목록을 읽지 못한 에이전트도 빼고 경고 로그만 남긴다. 웹은 대화의 에이전트가 기본 비서일 때만 읽어 비서 스킬 뒤에 「에이전트 이름 · 스킬」 로 붙인다. 고르면 입력창에 `/<에이전트 이름> · <스킬> ` 을 넣는다. 보낼 때 글이 목록에 있는 「에이전트 · 스킬」 로 시작하면 그 에이전트 코드로 아래 보내기 API 로 보내고, 아니면 보통 보내기다(같은 이름이 둘이면 그 스킬을 가진 첫 에이전트). 받아들여지면 입력창을 비우고 답이 돌고 있지 않으면 이력을 다시 읽는다.
- **스킬 맡기기 보내기**: 새 `POST /api/v1/chat/skill-delegations` 의 본문은 `conversationId`(없으면 새 대화), `agentCode`, `text` 다. 글은 `/<에이전트 이름> · <스킬> [나머지]` 꼴이어야 하고(`DelegatedSkillCommand`), 에이전트는 `agentCode` 로 정한다(이름이 그사이 바뀌어도 된다). 판정 순서는 대화(주인, 읽기 전용, 없으면 기본 비서로 새 대화), 에이전트(쓸 수 있고 켜짐, 기본 비서 아님), 스킬(그 에이전트의 켜진 스킬), 사용자 자리와 서버 전체 위임 한도다. 다 지나면 질문을 저장하고(`chat.nextturn` 의 `SkillDelegationQuestions`) 그 에이전트의 실행을 가상 스레드로 띄운 뒤, 실행 줄이 생기면 `202` 와 대화 번호, 실행 번호를 돌려준다. 대화 turn 잠금은 잡지 않는다. 그래서 답이 도는 동안에도 보낼 수 있고 보낸 뒤 바로 다음 질문을 받는다(DECISION26). 맡긴 실행은 turn 자리를 쥐지 않으므로 사용자 동시 실행 한도(ADR-069)에서 자식 실행처럼 센다(`ExecutionRecorder.admissionOf`, `countRunningOutsideTurns`). 실행 줄을 만들지 못하면 저장한 질문을 지우고 그 오류(`USER_BUSY` 등)를 돌려준다.
- **맡긴 실행의 모양**: 부모가 없는 실행이고 에이전트가 대화의 에이전트와 다르다. 지난 `@` 맡김과 같은 모양이라 `MentionLines` 가 질문 아래 「나 → 커리어에게 맡김」 줄과 상태를 그대로 그리고, 다시 생성도 막는다. 실행 줄에 `delegation_key`(새 무작위 키)와 `question_message_id` 를 적는다. 지시는 `SkillCommand.hermesInput()`(지금 `/이름` 과 같은 문장)이다. 끝나면 `DelegationFinished` 를 내고, 아직 전하지 않은 결과 조회(`findUndeliveredResults`)가 부모 없는 위임 실행도 함께 읽어 지금 깨우기가 결과 도착 줄과 비서 답을 연다. `MentionInputs` 는 키가 있는 맡김을 다음 turn 입력에 다시 넣지 않는다. 결과가 깨우기로 이미 들어가기 때문이다.
- **예약 작업**: `TaskRunStarter.runTurn` 이 지시가 `DelegatedSkillCommand` 꼴이면 `chat.runScheduledTurn` 대신 `SkillDelegations.runScheduled` 를 부른다. 이것은 시작 알림 줄과 지시를 질문으로 남기고 이름으로 에이전트를 찾아(같은 이름이 둘이면 찾지 못한 것으로 본다) 같은 실행을 그 스레드에서 끝까지 돌린다. 실행은 사람이 없는 실행으로 적는다. 에이전트가 없거나 꺼졌으면(쓸 수 있고 켜진 에이전트에서 찾는다) `AGENT_NOT_FOUND`, 스킬이 켜져 있지 않으면 `SKILL_COMMAND_UNKNOWN` 으로 던져 그 발화가 `FAILED` 로 닫힌다. 성공이면 그 실행 번호와 답으로 발화를 닫고, 발화의 turn 잠금이 풀린 뒤 깨우기가 결과를 비서 답으로 전한다. 작업 화면에는 입력 안내 한 줄을 둔다. 작업을 저장할 때 기본 비서 스킬 확인(`TaskService.requireRunnableCommand`)은 이 꼴을 건너뛴다.
- **글 꼴**: `/` 다음에 에이전트 이름, 공백과 가운뎃점(`·`)과 공백, 스킬 이름이 오고, 그 뒤는 공백이나 끝이다. 이름에는 줄바꿈과 가운뎃점이 없다. 기본 비서의 `/이름` 보다 먼저 본다. `/abc · x` 를 `abc` 스킬로 읽지 않게 하기 위해서다.
## 실패와 일관성

- 기다리는 동안 사용자가 중지하면 지금 중지가 루트 turn 에 붙은 자식을 멈춘다(`docs/features/chat.md` 「중지」). `awaitEnded` 는 자식이 끝나면 풀리고, 결과는 `CANCELLED` 로 돌아간다. turn 은 중지로 끝난다.
- 자식이 끝난 순간 `DelegationFinished` 로 깨우기가 돌아도, 부모 turn 이 대화 잠금을 쥐고 있어 열지 않고 turn 이 닫힐 때 다시 본다. 그 전에 도구가 `markResultDelivered` 를 적으므로 같은 결과로 자동 답이 생기지 않는다. 도구가 적기 전에 서버가 내려가면 기동 훑기가 지금처럼 자동 답을 연다. 결과를 잃지 않는다.
- 기다리는 동안 MCP 요청 스레드 하나를 쥔다. Hermes 의 MCP 도구 호출 제한은 기본 300초이고 우리 profile 틀은 바꾸지 않는다([`hermes/docs/hermes-contract.md`](../../../hermes/docs/hermes-contract.md) 「제한 시간은 우리가 정하지만 상한은 있다」). 2분은 그 안이다. 동시 기다림의 수는 루트당 동시 위임 한도와 전체 한도가 묶는다.
- **같은 profile 의 호출이 멈춘다.** Hermes 는 profile 마다 Control Plane MCP 연결 하나를 잠금(`_rpc_lock`)으로 묶어 호출을 하나씩 보낸다. 기다리는 동안 같은 사용자의 비서로 도는 다른 실행의 Control Plane 도구 호출이 멈추고, 기다림이 줄을 서면 뒤 호출이 300초 제한에 걸릴 수 있다. 사용자가 이 위험을 받아들였다(스펙 DECISION28). 맡은 실행이 같은 profile 이면 그 실행이 끝날 수 없으므로 `DelegationWaiter` 가 기다리지 않는다.
- 사건 스트림이 끊기거나 늦게 와도 자식의 끝은 `awaitCompletion` 이 정한다. 끝난 뒤 온 사건은 버린다(ADR-090 과 같다).
- 배포 순서: PR1 은 Control Plane 과 웹만 바꾼다. 웹이 먼저 떠도 `delegation_activity` 를 받지 않을 뿐이고, Control Plane 이 먼저 떠도 웹이 모르는 사건을 버린다.
- 배포 순서(PR2 앞 PR): 웹과 Control Plane 을 함께 배포한다. Control Plane 만 먼저 뜨거나 옛 화면 탭이 열린 채로 남으면, 옛 웹은 새로 고치기 전까지 새 대화를 목록의 첫 에이전트로 보낸다. 그 에이전트가 기본 비서가 아니면 `VALIDATION_FAILED` 로 거절되고, 옛 `@` 맡김 보내기도 거절된다. 저장되는 것은 없고 입력한 글은 입력창에 돌아온다.
- 배포 순서(PR2 뒤 PR): 웹과 Control Plane 을 함께 배포한다. 옛 화면 탭에서 비서가 아닌 대화에 보내면 `VALIDATION_FAILED` 로 거절되고 글은 입력창에 돌아온다. 옛 작업 화면이 보내는 `agentCode` 는 버리고 기본 비서로 저장한다.

## 검증 전략

| ID | 경계 | 환경 |
| --- | --- | --- |
| REQ1, REQ2 | `DelegationWaiter` 의 기다림과 예산 단위 테스트(가짜 시계, 끝남·실패·마감·중지·같은 profile), 실제 `/mcp` 경계에서 결과와 전달 표시를 보는 `McpDelegateWaitTest`, 전한 결과로 자동 답을 열지 않는 기존 `DelegationWakeServiceTest`. e2e 는 상한 0 으로 돌아 기다림 사례가 없다 | backend 테스트 |
| REQ2 | 상한 0 이면 지금과 같은 결과 | backend 테스트 |
| REQ3 | 자식 실행에 도구 사건이 저장되고 트리 API 에 보이는 e2e, `delegation_activity` 의 가리기 단위 테스트, 도는 답의 블록에 도우미 아래 도구 줄이 보이는 브라우저 테스트 | `test/e2e/`, `web/test/browser/` |
| REQ1-REQ3 | 배포 뒤 왕복(스펙 「배포 뒤 왕복 확인」) | 운영, 평가 계정 |
| REQ4 | 새 대화와 빈 대화가 늘 기본 비서로 생기고 다른 코드는 거절하는 통합 테스트(`ConversationEntryTest`), `/new` 와 `?agent=` 가 비서만 보이는 브라우저 테스트 | backend 테스트, `web/test/browser/` |
| REQ6 | `delegateTo` 거절(`MentionDelegationTest`), `@` 고르기가 없는 브라우저 테스트 | backend 테스트, `web/test/browser/` |
| REQ6, REQ8 | 평가 세트의 데이터와 채점 테스트, 배포 뒤 운영 모델로 서비스 개념 묶음 | `test/model-eval/`, 운영 평가 계정 |
| REQ5 | 사람이 쓰는 길의 거절과 자동·예약 turn 이 막히지 않는 통합 테스트, 대화 응답의 `writable`, 읽기 전용 대화를 여는 브라우저 테스트 | backend 테스트, `web/test/browser/` |
| REQ7 | 화면 API 와 `task_` 도구로 만들고 고치면 기본 비서이고 멈추기·다시 켜기는 그대로인 통합 테스트, 작업 화면에 에이전트 칸이 없는 브라우저 테스트 | backend 테스트, `web/test/browser/` |
| REQ9 | 기본 비서에 붙이기를 거절하고 떼기는 되는 통합 테스트, 승인 카드가 새 전문 에이전트를 만들어 붙이고 이미 붙은 전문 에이전트가 있으면 카드 없이 알리는 테스트(`McpConnectorAttachToolTest`, `ConnectorAttachRunnerTest`, `AgentConnectionControllerTest`), 연결 화면 고르기와 비서 상세에 붙이기가 없는 브라우저 테스트 | backend 테스트, `web/test/browser/` |
| REQ10 | 목록 API 가 기본 비서, 꺼진 에이전트, Hermes 기본 스킬을 빼는 테스트, 보내기가 질문과 부모 없는 위임 실행을 만들고 turn 을 열지 않으며 결과가 깨우기로 전해지는 통합 테스트(`SkillDelegationTest`), 판정마다의 거절, 예약 작업의 `/이름 · 스킬` 발화와 실패(`TaskRunStarterTest`), `/` 목록에서 골라 보내면 맡긴 줄과 결과 답이 생기는 브라우저 테스트(실제 Control Plane 과 가짜 Hermes) | backend 테스트, `web/test/browser/` |
| REQ10 | 배포 뒤 왕복(스펙 「배포 뒤 왕복 확인」 의 PR3) | 운영, 평가 계정 |

## 결정

| ID | 결정 | 답 | 누가 | 근거 |
| --- | --- | --- | --- | --- |
| DECISION1 | 기다리는 자리 | `agent_delegate` 도구 안에서 Control Plane 이 기다린다. 모델에게 `agent_status` 의 `wait_seconds` 를 부르게 하지 않는다 | 작성자 | 모델이 부르지 않으면 답이 나뉜다. 기다림을 정해진 동작으로 둬야 스펙 REQ1 이 늘 성립한다 |
| DECISION2 | 예산을 두는 곳 | 루트 실행별 메모리 맵 | 작성자 | 표를 늘리지 않는다. 루트 잠금과 중지 표시도 서버 하나를 전제로 메모리에 있다 |
| DECISION3 | 자식 사건의 실시간 중계 | 새 SSE 사건 `delegation_activity` 를 대화 단위 SSE 로 보낸다. 웹이 트리를 주기적으로 읽지 않는다 | 작성자 | 도는 답은 흘러온 사건으로 그리는 지금 규칙(`docs/features/execution.md` 「작업 과정 패널」)과 맞다. 맡긴 줄도 같은 길(`DelegationLineNotifier`)로 간다 |
| DECISION4 | 장기 결정 기록 | [ADR-20261121 / delegate-wait](../../../backend/docs/adr/ADR-20261121-delegate-wait.md) 를 남기고 ADR-017 「`agent_delegate` 는 기다리지 않는다」 와 ADR-040 「부모는 맡긴 뒤 기다리지 않는다」 를 대화 turn 에서 대체한다 | 작성자 | 되돌리면 도구 계약과 결과 전달 규칙을 함께 바꿔야 한다 |
| DECISION5 | PR2 나눔 | 앞 PR 은 REQ4, REQ6, REQ8, 뒤 PR 은 REQ5, REQ7 | 작성자 | 계획의 운영 코드 추정이 상한을 넘었다. 새 대화와 `@` 는 서버와 화면이 함께 바뀌어야 끊기지 않고, 읽기 전용과 예약 작업은 앞 PR 없이도 서로 독립이다 |
| DECISION6 | 스킬 맡기기 실행의 부모 | 부모 없는 실행(지난 `@` 맡김과 같은 모양)에 위임 키를 적는다. 대화 turn 을 열거나 가짜 루트 실행을 만들지 않는다 | 작성자 | 스펙의 「나 → 커리어에게 맡김」 줄을 `MentionLines` 가 이미 그린다. 결과 전달은 위임 키로 고르는 지금 조회에 부모 없는 실행만 더하면 된다. 가짜 루트는 실행 기록과 사용량에 빈 실행을 남긴다 |
| DECISION7 | 스킬 맡기기를 보내는 API | 보내기(`/chat/messages`)에 칸을 더하지 않고 새 API 를 둔다 | 작성자 | 보내기는 turn 을 여는 길이고 응답이 답이다. 이 길은 turn 을 열지 않고 `orchestration` 이 갖는다. `chat` 은 `orchestration` 을 부를 수 없다(층 순서) |
| DECISION8 | 예약 작업 지시의 꼴 | `/<에이전트 이름> · <스킬>`. 목록에 보이는 글 그대로이고 지금 `/이름` 을 넓힌 것이다. 같은 이름의 에이전트가 둘이면 발화가 실패한다 | 작성자 | 사용자는 에이전트 코드를 모른다. 스펙 DECISION27 이 화면에 고르기 없이 글자 그대로 읽게 했다 |
| DECISION9 | 답이 도는 동안의 스킬 맡기기 | 대기 줄에 넣지 않고 바로 맡긴다 | 작성자 | 맡기기는 turn 잠금이 없어도 되고, 대기 줄은 비서 turn 의 글만 다룬다. 답 순서가 섞이는 것은 스펙 DECISION26 이 받아들였다 |

## 결정 기록 연결

| 결정 | 종류 | ADR | 현재 규칙의 원본 |
| --- | --- | --- | --- |
| DECISION1, DECISION2 | architecture | [ADR-20261121 / delegate-wait](../../../backend/docs/adr/ADR-20261121-delegate-wait.md) | `AgentDelegationService`, `DelegationProperties`, `docs/features/agent-skill.md` 「다른 에이전트에게 맡기기」 |
