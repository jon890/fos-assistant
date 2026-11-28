# 서비스 개념 기술 설계

스펙: [`spec.md`](spec.md). 이 설계는 PR1(REQ1-REQ6), PR2(REQ7-REQ10), PR3(REQ11-REQ14), PR4(REQ15-REQ16)의 구조를 정한다.

바뀐 결정(2026-11-23): [비서 중심 대화 스펙](../assistant-hub/spec.md) 의 DECISION9 가 REQ4 와 DECISION8 을 바꿨다. 아래 REQ4 줄, 개념 목록 표의 에이전트 고르기 줄, 링크가 새 탭에서 열린다는 줄의 `/new?agent=<code>` 안내는 지금 규칙이 아니다. 「블로그 비서로 바꿔 줘」 에 링크를 붙이지 않고 비서가 그 에이전트에게 맡겨 처리한다고 답한다.

## 요구사항 연결

| ID | 해결 방법 |
| --- | --- |
| REQ1 | `context` 모듈에 개념 목록 지침 `ServiceConceptsInstructions.SECTION` 을 두고 `ContextAssembler.withResponseInstructions` 가 모든 범위(`CONVERSATION`, `SCHEDULED`, `PROACTIVE`)의 지시문에 넣는다. 대화 turn, 예약 작업, 먼저 살펴보기, 맡긴 실행이 모두 이 메서드를 지난다. 지금의 파일 공간 단락(`ArtifactService.FILE_SPACE_RULES`)은 그대로 둔다 |
| REQ2 | 개념 목록의 모델 단계 줄이 「바꾸지 않고 입력창의 단추를 안내한다」 를 말한다. 모델 단계를 바꾸는 도구는 만들지 않는다 |
| REQ3 | 개념 목록에서 아직 도구가 없는 개념(예약 작업, 할 일 만들기·끝내기, 기억 지우기)의 줄이 「했다고 말하지 않고 그 화면 링크를 붙인다」 를 말한다. PR2 부터 PR4 가 그 줄을 직접 처리의 말로 바꾼다 |
| REQ4 | 대화 turn 에는 이미 맡길 수 있는 에이전트 구역(`DelegationTargetsInstructions`)이 `[code] 이름` 을 넣는다. 개념 목록의 에이전트 고르기 줄이 그 code 로 `/new?agent=<code>` 를 만들라고 하고, 구역이 없는 실행은 `agent_list` 로 찾으라고 한다. 두 목록 모두 요청자가 쓸 수 있는 에이전트만 담는다 |
| REQ5 | Control Plane MCP 에 읽기 도구 `connector_list` 를 더한다. 요청자가 볼 수 있는 플러그인마다 id, 이름, 내 연결 상태, 지금 에이전트에 붙은 상태, 화면 링크를 돌려준다. `mcp` 는 `connector` 를 import 하지 않으므로 `mcp.application.port.ConnectorListing` 을 두고 `connector.connection` 이 구현한다(DECISION5). 링크의 `?agent=` 는 Control Plane 이 DECISION22 대로 정한다. 개념 목록의 연결 줄이 이 도구를 부르라고 한다 |
| REQ6 | `test/model-eval/concepts/` 에 문항, 판정, 실행 명령을 둔다. Control Plane 클라이언트는 `test/model-eval/control-plane.ts` 를 함께 쓴다. 판정은 순수 함수이고 단위 테스트가 CI 의 저장소 테스트로 돈다 |
| REQ7 | PR2. 아래 「PR2 예약 작업 도구」. `mcp` 의 `task_create` 가 port `TaskRequests` 로 부르고 `task` 의 `TaskAgentRequests` 가 `TaskService.create` 와 같은 검사로 만든다 |
| REQ8 | PR2. `task_list`, `task_update`, `task_set_state` 가 같은 port 로 부른다. 대상은 `task` 인자 하나(번호나 이름)로 찾고, 여럿이면 후보만 돌려준다 |
| REQ9 | PR2. `task_delete` 는 지우지 않고 `connector_action` 에 Control Plane 승인 줄을 만든다(`ControlPlaneApprovals`, `connector.action`). 승인하면 port `ControlPlaneActionRunner` 를 `task` 가 구현해 지운다 |
| REQ10 | PR2. 먼저 살펴보기에서 받는 조건은 `McpController` 가 정한다. 맡긴 실행의 대화는 origin 실행에서 정하고, 에이전트는 기본 비서다 |
| REQ11 | PR3. 아래 「PR3 할 일과 기억 도구」. `follow_up_propose` 가 `requested` 인자와 실행 출처(`McpFollowUpTools.userDirected`)로 바로 챙기는 줄(`OPEN`)과 제안(`PROPOSED`)을 나눈다. 바로 만드는 일은 `followup` 의 `FollowUpAgentRequests` 가 한다 |
| REQ12 | PR3. `follow_up_list` 가 챙기는 할 일을 주고 `follow_up_done` 이 `follow_up` 인자 하나(번호나 제목)로 찾아 끝낸다. 바로 끝내는 조건은 REQ11 과 같은 판정이다 |
| REQ13 | PR3. `memory_forget` 은 지우지 않고 PR2 의 Control Plane 승인 줄을 만든다. `mcp` 의 port `MemoryForgetRequests` 를 `connector.action` 이 구현하고, 승인하면 `MemoryForgetRunner` 가 `memory` 의 `MemoryForgetting` 으로 지운다 |
| REQ14 | PR3. 개념 목록의 기억 줄과 할 일 줄이 화면을 설명하고 새 도구를 가리킨다. 평가 세트에 직접 처리 문항을 더한다 |
| REQ15 | PR4. 아래 「PR4 연결 붙이기 승인 카드」. `connector_attach` 는 붙이지 않고 PR2 의 Control Plane 승인 줄을 만든다. `mcp` 의 port `ConnectorAttachRequests` 를 `connector.action` 이 구현하고, 승인하면 `ConnectorAttachRunner` 가 화면과 같은 `ConnectorBindingService.bind` 를 부른다 |
| REQ16 | PR4. `ConnectorAttachRequester` 가 카드를 띄우기 전에 플러그인, 에이전트의 주인과 공개 범위, 연결 상태를 보고 카드 없이 까닭과 링크를 돌려준다. 승인할 때는 화면의 붙이기 검사가 한 번 더 본다 |
| REQ17 | 각 PR 의 도구가 `McpCaller.user()` 만으로 대상을 찾고, 실패는 `isError` 결과와 까닭으로 돌려준다. 지금 `file_space_put` 의 실패 결과 모양을 따른다 |
| REQ18 | REQ17 행과 같다 |

## 책임과 경계

| 모듈 | 책임 |
| --- | --- |
| `context` | 개념 목록 지침의 글과, 그것을 모든 실행의 지시문에 넣는 일. 지금 답변 형식, 도구 호출, 검색, 기억 지침을 넣는 자리와 같다 |
| `mcp` | `connector_list` 의 인자 검사, 요청자 판정, 결과 모양. 다른 Control Plane MCP 도구와 같은 경로(`McpController` → `McpToolService`)다. 플러그인 목록은 port `ConnectorListing` 으로 읽는다 |
| `connector.connection` | 플러그인 목록과 내 연결 상태의 원본. `ConnectorListing` 의 구현이 `ConnectorConnectionService.catalog` 를 읽어 결과 칸으로 바꾼다. 새 쓰기는 없다. `FinancialLimitRequests` 를 `connector` 가 구현하는 것과 같은 방식이다 |
| `test/model-eval/concepts` | 실제 모델로 돌리는 평가. 운영 Control Plane API 만 부르고 저장소 코드에 의존하지 않는다 |
| `test/repo` | 개념 목록의 화면 주소와 도구 이름이 실제 화면 경로와 도구 목록에 있는지 보는 저장소 테스트(DECISION18) |
| `mcp`(PR2) | 예약 작업 도구의 인자 검사와 결과 글. port `TaskRequests` 로 부른다 |
| `task`(PR2) | `TaskRequests` 와 `ControlPlaneActionRunner` 의 구현. 대상 찾기, 기본값, 하루 발화 수 검사, 지우기 실행 |
| `mcp`(PR3) | 할 일을 바로 바꿔도 되는 실행인지의 판정, 할 일 목록·끝내기와 기억 지우기 도구의 인자 검사와 결과 글 |
| `followup`(PR3) | 바로 챙기는 줄 만들기, 한 실행의 상한, 끝낼 대상 찾기(`FollowUpAgentRequests`) |
| `memory`(PR3) | 지울 수 있는 기억의 판정과 지우기(`MemoryForgetting`). 화면의 지우기와 같은 권한이다 |
| `connector.action`(PR3) | 기억 지우기 승인 줄의 요청(`MemoryForgetRequester`)과 승인 뒤 실행(`MemoryForgetRunner`). `memory` 는 `connector` 보다 아래 층이라 승인 줄을 직접 만들지 못한다 |
| `mcp`(PR4) | `connector_attach` 의 인자 검사와 결과 글. port `ConnectorAttachRequests` 로 부른다 |
| `connector.action`(PR4) | 붙이기 승인 줄의 요청(`ConnectorAttachRequester`)과 승인 뒤 실행(`ConnectorAttachRunner`). 붙이기 자체는 `connector.connection` 의 `ConnectorBindingService.bind` 가 화면과 같게 한다 |
| `connector.action`(PR2) | Control Plane 승인 줄(`ControlPlaneActions`, `ControlPlaneApprovals`)의 저장, 카드 표시 값, 승인과 결과 알림 줄. 지금 승인 줄과 같은 표와 화면을 쓴다. `connector.approval` 의 공개 승인 API 가 이 줄을 먼저 가른다 |

지시문은 실행 입력의 앞 단락(`ArtifactService.agentPreamble`)이 아니라 `instructions` 에 둔다.
앞 단락은 대화 turn 만 지나고 맡긴 실행은 지나지 않는다. `instructions` 는 모든 실행이 지난다. 목록은 고정 지침(답변 형식, 도구 호출) 바로 뒤, 범위마다 다른 절과 사람마다 다른 기억 글 앞에 둔다. 그래야 실행마다 같은 앞부분이 길어져 모델 제공사의 프롬프트 캐시에 함께 든다.

**스펙이 말하는 「실행 입력」 은 Hermes 요청 본문의 `instructions` 값이다.** 검증은 그 값을 본다.
[`hermes-contract.md`](../../../hermes/docs/hermes-contract.md) 의 「부모의 `instructions` 는 자식에게 가지 않는다」 는 Hermes 가 넘기지 않는다는 뜻이다. 맡긴 실행은 Control Plane 이 `AgentRunner` 에서 따로 조립하므로 같은 목록을 받는다.

## 인터페이스와 데이터

### 개념 목록 지침

머리 줄 `# 이 서비스의 기능` 아래에 개념마다 한 줄을 둔다. 줄의 꼴은 「이름(사용자가 부르는 말): 화면 주소. 할 일」 이다. 글은 `ServiceConceptsInstructions` 가 갖고, 아래는 PR1 의 뜻이다.

| 개념 | 주소 | PR1 의 할 일 |
| --- | --- | --- |
| 파일 공간(내 파일, 파일공간) | `/files` | 사용자에게 준 파일을 모아 두는 화면이다. 입력에 「[파일 공간]」 단락이 있으면 그 규칙을 따른다. 그 단락은 대화 turn 의 입력에만 있다(`ArtifactService.agentPreamble`) |
| 예약 작업(매일, 매주, 정해진 시각에 해 줘) | `/tasks`, `/tasks/new` | 만들었다고 말하지 않고 `/tasks/new` 링크와 만드는 방법을 알린다 |
| 기억(기억해, 잊어 줘) | `/memory` | 남기는 것은 기억 지침을 따른다. 지우기와 보기는 `/memory` 링크를 붙인다 |
| 할 일과 지금 볼 것(할 일로 적어 둬, 끝냈어) | `/now` | 할 일 제안 도구가 있으면 제안하고, 받아들이기와 끝내기는 `/now` 에서 한다고 알린다 |
| 외부 서비스 연결(구글 캘린더 연결해 줘) | `/connections` | `connector_list` 를 불러 그 플러그인의 `link` 를 붙인다. 없으면 없다고 하고 `/connections` 를 붙인다. 연결했다고 말하지 않는다 |
| 모델 단계(더 좋은 모델로, 빠르게) | 없음 | 바꾸지 않는다. 입력창의 모델 단계 단추에서 고르면 다음 답부터 그 단계라고 알린다 |
| 에이전트 고르기(○○ 비서로 바꿔 줘) | 없음 | 대화의 에이전트는 바뀌지 않는다. `@` 로 부르거나 `/new?agent=<code>` 에서 고르라고 알린다 |

마지막 줄에 「사용자가 서비스의 기능을 부탁했는데 그 기능의 도구가 없으면 했다고 말하지 않는다」 를 둔다.
사용량은 넣지 않는다(DECISION9).

### `connector_list`

| 항목 | 값 |
| --- | --- |
| 인자 | 없음. `_fos_ctx` 를 뗀 뒤 키가 남으면 인자 오류다(`agent_list` 와 같다) |
| 결과 | JSON 배열. 항목마다 `id`, `title`, `connected`(내 연결 상태가 `READY` 인가. 확인 중인 `PENDING` 은 아직 연결되지 않은 것으로 본다), `thisAgent`, `link`. `thisAgent` 는 이 실행의 에이전트에 붙은 상태로 `none`(안 붙음), `ready`(바인딩이 `READY` 이고 재시작 대기가 아님, 지금 쓸 수 있다), `pending`(붙었지만 반영이나 관리자 확인을 기다린다) 중 하나다. 연결 화면의 「붙음」 과 「붙이는 중」 과 같은 나눔이다([`connector.md`](../connector.md)) |
| `link` | `/connections/{id}`. 이 실행의 에이전트가 요청자가 주인인 비공개 에이전트면 `?agent=<code>` 를 붙인다(DECISION22) |
| 넣지 않는 것 | 설명, 입력 칸, 도구 목록, 다른 에이전트의 바인딩, 사용 불가 항목(카탈로그에서 빠진 연결) |
| 먼저 살펴보기 | 읽기 도구라 `CHECK_TREE_TOOLS` 에 넣는다 |
| 원본 | `ConnectorConnectionService.catalog(user)`. 이 실행의 에이전트는 `McpCaller.originExecution()` 의 에이전트다 |

결과의 이름(`title`)은 플러그인 선언의 글이다. 에이전트가 고른 글이 아니라 외부 데이터 표지(`<external-data>`)로 감싸지 않는다. 선언은 운영자가 설치한 플러그인의 것이다.

### 평가 세트

| 파일 | 갖는 것 |
| --- | --- |
| `test/model-eval/concepts/questions.json` | 문항마다 개념, 보낼 글, 기대. 기대는 답의 링크(경로와 질의 문자열이 똑같은 `links`, 앞부분이 맞는 `linkPrefixes`, 없어야 할 앞부분 `noLinkPrefixes`), 불러야 할 도구(`tools`), 하나 이상 있어야 할 낱말(`includes`), 없어야 할 낱말(`absent`)이다 |
| `test/model-eval/concepts/score.ts` | 판정 규칙. 네트워크를 쓰지 않는 순수 함수 |
| `test/model-eval/concepts/run.ts` | `send` 는 문항마다 새 대화를 만들어 보낸다. `score` 는 답과 실행 트리를 읽어 판정하고 보고서를 쓴다. 답변 품질 평가의 `run.ts` 와 같은 명령, 환경 변수, 종료 코드 |

문항은 개념마다 둘 이상이고 개인 사정이 없는 일반 문장이다.

PR2 부터 기대에 `instructionIncludes` 가 있다. 답이 링크한 예약 작업(`/tasks/<id>`)의 지시문에 항목마다 `|` 로 나눈 낱말 가운데 하나가 있어야 한다(스펙 DECISION28). `score` 는 판정 뒤 `send` 를 시작한 뒤 만든 예약 작업을 `--keep` 과 상관없이 지운다. 남기면 운영에서 발화해 비용이 든다. 그래서 평가 계정은 평가만 쓴다.

운영에 따라 답이 달라지는 기대값은 문항에 적지 않고 실행할 때 받는다. 문항은 `{otherAgentName}`, `{otherAgentCode}`, `{connectorTitle}`, `{connectorId}` 같은 자리를 쓰고, `send` 와 `score` 가 환경 변수(`FOS_EVAL_OTHER_AGENT_CODE`, `FOS_EVAL_OTHER_AGENT_NAME`, `FOS_EVAL_CONNECTOR_ID`, `FOS_EVAL_CONNECTOR_TITLE`)로 채운다. 값이 비면 그 문항은 돌리지 않고 보고서에 「건너뜀」 으로 적는다.

평가를 돌리는 계정과 에이전트의 전제는 이렇다. 평가 에이전트(`FOS_EVAL_AGENT_CODE`)는 그 계정이 주인인 비공개 에이전트다. 다른 에이전트는 그 계정이 쓸 수 있는 에이전트다. 플러그인은 카탈로그에 있고 그 계정이 아직 연결하지 않은 것이다. 연결 문항의 링크는 `/connections/{connectorId}` 로 시작하는지만 본다.
도구를 불렀는지는 실행 트리 전체의 끝 사건(`TOOL_COMPLETED`)의 도구 이름이 `mcp__fos_assistant__<도구>` 로 끝나는지로 본다.
세트는 문항의 85% 이상(올림)이 통과하면 통과다. 답변 품질 평가의 출처 기준(15/17)과 같은 크기다.

## PR2 예약 작업 도구

### 도구

모두 Control Plane MCP 의 도구이고 결과는 다른 도구처럼 글 하나다. 실패는 `isError` 와 `status`, 까닭 한 줄이다(REQ18). 실패해도 turn 은 계속된다.

| 도구 | 인자 | 하는 일 |
| --- | --- | --- |
| `task_list` | 없음 | 요청자의 일반 예약 작업(`TURN`, 지우지 않은 것)마다 `id`, `title`, `state`, `schedule`(cron 이나 한 번의 시각과 시간대), `next_run`, `notify`, `link` 를 준다. 매일 깨우기(`CHECK`)는 넣지 않는다 |
| `task_create` | `title`, `instruction`, `cron` 이나 `fire_at` 하나, `notify`(선택) | 만든다. 결과는 `id`, `title`, `next_run`, `link`(`/tasks/{id}`) |
| `task_update` | `task`, 고칠 칸(`title`, `instruction`, `cron`/`fire_at`, `notify`) 하나 이상 | 고친다. 모델 단계, 에이전트, 시간대, 대화 방식 인자는 스키마에 없다 |
| `task_set_state` | `task`, `state`(`paused`, `active`) | 멈추거나 다시 켠다 |
| `task_delete` | `task` | 지우기 승인 카드를 띄운다. 지우지 않는다 |

`task` 는 `task_list` 의 `id` 나 작업 이름이다. 번호가 맞으면 그 작업, 아니면 이름이 똑같은(앞뒤 공백과 대소문자 무시) 작업이다. 이름이 같은 작업이 둘 이상이면 `status: ambiguous` 와 후보(`id`, `title`, `next_run`)만 돌려주고 바꾸지 않는다. 없거나 남의 작업이면 `not_found` 다(REQ17).

시각은 사용자가 말한 시간대가 아니라 작업의 시간대(`Asia/Seoul`) 기준이다. `cron` 은 표준 5필드, `fire_at` 은 `2026-11-21T07:00` 꼴의 시간대 없는 시각이다. `next_run` 도 같은 시간대의 글(`2026-11-21 07:00 (Asia/Seoul)`)로 준다.

### 만들 때 정하는 값

| 칸 | 값 |
| --- | --- |
| 주인 | 요청자(`McpCaller.user()`) |
| 에이전트 | 요청자의 기본 비서다. 화면과 같은 `TaskService` 가 정하고, 맡긴 실행도 맡겨진 에이전트가 아니라 기본 비서다([비서 중심 대화 스펙](../assistant-hub/spec.md) 의 REQ7 이 DECISION27 을 바꿨다). 고치기도 기본 비서로 바꾼다. 기본 비서가 없거나 쓸 수 없으면 `agent_unavailable` 이다 |
| 대화 방식 | origin 실행이 맡긴 실행이 아니고 대화가 있으면 `SINGLE` 에 그 대화를 미리 적는다(DECISION29). 발화는 지금의 `SINGLE` 처리대로 그 대화가 지워지지 않았고 에이전트가 같으면 이어 쓴다. 그 대화의 에이전트가 기본 비서가 아니면 첫 발화가 기본 비서의 새 대화를 연다. 맡긴 실행이거나 대화가 없으면 `NEW_PER_RUN` 이다 |
| 시간대, 놓친 실행, 모델 단계 | 화면 기본값(`Asia/Seoul`, `RUN_ONCE`), 모델 단계는 비움 |
| 알림 | 인자가 없으면 화면 기본값(`ALWAYS`) |

검사는 화면의 `TaskService.create` 를 그대로 쓴다(작업 수 상한, 최소 간격, 이름과 지시 길이). 더해서 cron 이 앞으로 7일 가운데 어느 하루(작업 시간대의 달력 하루)라도 `assistant.task.max-runs-per-day` 보다 많이 발화하면 만들지 않고 `daily_limit` 으로 거절한다(DECISION30). 요일이나 날짜를 고른 식이 지금부터 24시간만 세면 빠져나가기 때문이다. 화면은 이 수를 발화할 때만 막지만, 에이전트가 만든 작업이 하루의 반을 건너뛰는 일을 미리 막는다. 고칠 때도 같다.

이름과 시각이 같은 작업이 이미 있으면 새로 만들지 않고 그 작업을 `status: exists` 로 돌려준다. 같은 만들기를 다시 불러도 한 번만 생긴다.

고치기는 저장된 값으로 `TaskInput` 을 채우고 인자로 받은 칸만 바꿔 `TaskService.update` 를 부른다. 에이전트, 모델 단계, 시간대, 대화 방식, 놓친 실행 처리는 저장된 값 그대로다.

### 지우기 승인 카드

새 표를 만들지 않고 플러그인 승인 줄(`connector_action`)에 Control Plane 승인 줄을 둔다. 대화 옆 패널, 건수 한 줄, 승인 요청 알림, 만료, 거절 알림 줄을 지금 흐름 그대로 쓴다. 마이그레이션은 없다.

| 칸 | 값 |
| --- | --- |
| `connector_id` | `fos-assistant`(Control Plane MCP 서버 이름). 카탈로그에 없어 플러그인 도구로 판정되지 않는다 |
| `tool_name`, `hermes_tool` | `task_delete`, `fos_task_delete`. 줄이 Control Plane 승인 줄인지는 세 칸이 모두 맞는지로 본다(`ControlPlaneActions.owns`). Hermes 는 이 등록 이름을 쓰지 않는다 |
| 위험도, 승인 방식 | `WRITE`, `ALWAYS`. 상시 허락과 대화 허락이 없다(DECISION21) |
| 인자 | `{"task_id","task","next_run"}`. 카드는 인자 이름표(`작업 번호`, `작업`, `다음 실행`)로 그린다. 화면은 바꾸지 않는다 |
| `conversation_id` | origin 실행의 대화. 맡긴 실행은 루트의 대화를 그대로 가진다(DECISION27) |
| 같은 요청 | 사용자, origin 실행, 작업 번호의 해시. 끝난 줄이면 그 상태를 알린다 |

승인은 `ConnectorActionService.approve` 가 Control Plane 줄을 상한 변경 줄처럼 먼저 갈라 `ControlPlaneApprovals.approve` 로 보낸다. 사용자 행과 줄을 잠그고 기다리는 줄인지와 기한을 본 뒤, 인자 해시가 맞는 작업 번호로 `ControlPlaneActionRunner`(구현은 `task` 의 `TaskDeleteRunner`)를 부른다. 성공하면 `SUCCEEDED` 와 「「작업 이름」 예약 작업을 지웠어요」, 그 사이 작업이 없어졌으면 `FAILED` 와 까닭이다. 저장소 예외처럼 예상하지 못한 실패는 승인 전체를 되돌리고 카드는 기다리는 채로 남는다.

승인 뒤에는 에이전트를 다시 돌리지 않는다(DECISION21). Control Plane 줄은 실행한 결과로 다음 turn 에 전하지 않고(`undeliveredResults`, `resultsFor` 에서 뺀다), 실행하지 않고 끝난 줄처럼 알림 줄로만 남긴다(`undeliveredClosures` 에 넣고 알림 글은 결과 글이다).

### 먼저 살펴보기와 맡긴 실행

| 도구 | 먼저 살펴보기(사건 살펴보기 포함) |
| --- | --- |
| `task_list` | 읽기라 늘 받는다 |
| `task_delete` | 카드만 띄우므로 늘 받는다(DECISION32) |
| `task_create`, `task_update`, `task_set_state` | 그 살펴보기의 `writes_allowed` 가 참일 때만 받는다. 받는 조건에 점검 대화를 보지 않는다. 만든 작업의 결과는 위 「만들 때 정하는 값」 대로 그 실행의 대화(점검 대화)에 쌓인다 |

예약 작업의 실행과 맡긴 실행은 사람이 보낸 실행과 같다(DECISION10). 맡긴 실행이 만든 작업은 맡겨진 에이전트로 돌고 매번 새 대화다.

## PR3 할 일과 기억 도구

### 할 일 도구

| 도구 | 인자 | 하는 일 |
| --- | --- | --- |
| `follow_up_propose` | `title`, `due_at`(선택), `waiting`(선택), `requested`(선택) | 할 일을 남긴다. `requested` 가 참이고 아래 「바로 바꾸는 실행」 이면 바로 챙기는 줄(`OPEN`)이고, 아니면 지금처럼 제안(`PROPOSED`)이다. 결과 글이 어느 쪽인지 알린다 |
| `follow_up_list` | 없음 | 요청자가 챙기는 할 일(`OPEN`)마다 `id`, `title`, `due_at`, `waiting` 을 준다. 받아들이지 않은 제안은 주지 않는다 |
| `follow_up_done` | `follow_up` | 챙기는 할 일 하나를 끝낸다. 결과에 끝낸 줄의 `title` 과 `due_at` 을 준다. 화면에는 끝낸 할 일을 되돌리는 동작이 없어, 잘못 끝냈다고 하면 에이전트가 그 값으로 다시 적는다(스펙 DECISION34) |

`requested` 는 모델이 「사용자가 이번 메시지에서, 또는 예약 작업의 지시문이 적어 두라고 시켰다」 고 알리는 값이다. 모델의 말만으로 바로 만들지 않고 실행의 출처가 함께 맞아야 한다. 스스로 판단한 후속 작업은 `requested` 없이 불러 제안으로 남긴다(스펙 DECISION5).

**바로 바꾸는 실행**은 `McpFollowUpTools.userDirected` 가 정한다. 만들기와 끝내기가 같은 판정을 쓴다.

| origin 실행 | 판정 |
| --- | --- |
| 사람이 보낸 메시지의 루트 실행이고 그 대화가 바깥 글을 읽지 않았다 | 바로 바꾼다 |
| 사람이 보낸 메시지의 루트 실행이지만 그 대화가 바깥 글을 읽었다 | 제안. 끝내기는 바꾸지 않는다 |
| 예약 작업의 발화로 도는 루트 실행(사람의 질문이 없고 사람이 없는 실행으로 적힌 루트, 점검 대화가 아님) | 바로 바꾼다. 바깥 글을 읽었어도 같다(스펙 DECISION31) |
| 맡긴 실행, 먼저 살펴보기 트리, 점검 대화의 실행, 대화가 없는 실행, 그 밖의 실행 | 제안. 끝내기는 바꾸지 않는다 |

바깥 글은 `memory_remember` 의 바로 저장 판정이 쓰는 도구 목록(`McpMemoryRemember.INTERNAL_TOOLS`)에 `follow_up_list` 와 `follow_up_done` 을 더해 대화 전체를 본다. 두 도구를 더하지 않으면 목록을 읽은 대화에서 「그거 끝냈어」 를 끝내지 못한다. 기억의 바로 저장 판정에는 두 도구를 더하지 않는다. 예약 작업의 실행이 바깥 글을 읽고 적은 제목일 수 있고, 스펙이 그 판정을 바꾸지 않는다고 정했다(스펙 DECISION5). 예약 작업의 맡긴 실행이 끝나 그 결과를 전하는 turn 도 같은 표시라 예약 작업의 실행으로 본다.

바로 만드는 줄은 제안의 억제 규칙(거절한 제목, 대화의 제안 상한)을 쓰지 않는다. 같은 제목의 제안이 있으면 새로 만들지 않고 그 제안을 받아들이고, 이미 챙기는 줄이면 그대로 둔다. 한 실행이 바로 만드는 줄은 `FollowUpAgentRequests.MAX_DIRECT_PER_EXECUTION` 까지다. 건수 확인과 삽입은 사용자 줄을 잠가 차례로 하므로 나란히 불러도 넘지 않는다. 같은 제목의 제안은 잠가 읽은 뒤 받아들여, 그 사이 화면에서 커밋한 기한 변경이나 거절을 덮지 않는다. 바깥 글에 숨은 지시가 할 일을 끝없이 만들지 못하게 한다. 만든 실행은 제안처럼 `proposed_by_execution_id` 에 남긴다.

`follow_up` 은 `follow_up_list` 의 `id` 나 제목이다. 번호가 맞으면 그 줄, 아니면 제목이 같은(공백과 대소문자 무시) 줄, 그것도 없으면 제목에 그 글이 든 줄이다. 열린 할 일은 사용자마다 제목이 하나뿐이라, 후보가 여럿인 경우는 제목의 일부가 여러 줄에 든 때다. 그때는 `status: ambiguous` 와 후보(`id`, `title`, `due_at`)만 돌려주고 바꾸지 않는다. 없거나 남의 줄이거나 아직 받아들이지 않은 제안이면 `not_found` 다(REQ17). 바로 바꾸는 실행이 아니면 `not_directed` 이고 사용자에게 끝났는지 물으라는 글이다(스펙 DECISION24).

할 일 제목은 모델이 쓴 글일 수 있어 목록과 후보를 바깥 글 표지로 감싼다. 세 도구의 인자와 결과는 실행 사건에 남기지 않는다(`HermesRunEventStream`).

### 기억 지우기 승인 카드

`memory_forget` 의 인자는 `memory_id` 하나다. 번호는 지시문의 요약 구역과 색인, `memory_search` 결과에 있다. 대상은 종류가 `MEMORY` 이고 상태가 `ACCEPTED`(알고 있는 것)나 `PROPOSED`(검토할 기억)인 항목이다. 문서와 출처 원문, 거절한 항목, 남의 항목은 없는 기억과 같은 `not_found` 다(스펙 DECISION25). 그룹 기억을 `ADMIN` 이 아닌 사용자가 청하면 카드 없이 `forbidden` 이다.

승인 줄은 PR2 의 Control Plane 승인 줄과 같다(위 「지우기 승인 카드」). 다른 값만 적는다.

| 칸 | 값 |
| --- | --- |
| `tool_name`, `hermes_tool` | `memory_forget`, `fos_memory_forget` |
| 인자 | `{"memory","content","scope","memory_id","revision"}`. 카드는 이름표(`기억`, `내용`, `범위`, `기억 번호`, `버전`)로 그린다. 내용은 `MemoryForgetRequester.CONTENT_PREVIEW` 자까지 보인다 |
| 같은 요청 | 사용자, origin 실행, 기억 번호와 버전의 해시 |

승인하면 `MemoryForgetRunner` 가 줄을 잠가 다시 읽고 권한을 한 번 더 본 뒤 화면의 지우기와 같게 지운다(마지막 값을 버전으로 남긴다). 알림 줄은 「「제목」 기억을 지웠어요」 다. 그 사이 없어졌거나 권한이 바뀌었으면 `FAILED` 와 까닭이다. 카드를 띄운 뒤 그 기억을 고쳤으면(버전이 다르면) 지우지 않고 다시 요청하라는 `FAILED` 다. 사용자가 카드에서 본 내용과 지울 내용이 달라지기 때문이다.

카드에 보인 제목과 내용 앞부분은 승인 줄의 인자로 남아 그 대화를 정리할 때 함께 지워진다. 지금 승인 카드처럼 key 꼴의 긴 글은 카드에서 가려진다.

도구 결과에는 기억의 제목과 내용을 넣지 않는다. 그 기억을 읽을 권한이 없는 에이전트가 번호만으로 불러도 글은 카드로 사용자에게만 보인다. 번호를 모르는 기억은 에이전트가 `/memory` 링크로 안내한다.

### 먼저 살펴보기에서 받는 PR3 도구

| 도구 | 먼저 살펴보기(사건 살펴보기 포함) |
| --- | --- |
| `follow_up_propose`, `follow_up_list` | 늘 받는다. 살펴보기 트리의 할 일은 `requested` 와 관계없이 제안이다 |
| `memory_forget` | 카드만 띄우므로 늘 받는다(스펙 DECISION32) |
| `follow_up_done` | 바로 바꾸는 도구라 `writes_allowed` 가 참일 때만 받는다(스펙 REQ10). 받더라도 살펴보기 트리는 바로 바꾸는 실행이 아니라 상태를 바꾸지 않는다 |

## PR4 연결 붙이기 승인 카드

**이 절의 대상과 차례는 [비서 중심 대화 설계](../assistant-hub/design.md) 의 「PR3」 이 바꿨다.** 카드는 새 전문 에이전트를 만들어 붙이고(`SpecialistAgentCreator`), 그 연결이 붙은 전문 에이전트가 있으면 카드 없이 `specialist_attached` 다. 아래 `not_owner`, `agent_not_private`, `single_binding` 차례와 셸 경고는 없어졌고, `already_attached` 는 기본 비서에 붙은 경우에만, 쓸 수 없는 전문 에이전트에만 붙은 경우는 `specialist_unavailable` 이다. `not_connected` 링크에는 `?agent=` 가 없다. 승인 줄과 승인 뒤 세 걸음은 그대로다.

### 도구

`connector_attach` 의 인자는 `connector` 하나다. `connector_list` 의 `id` 다. 붙일 에이전트는 인자로 받지 않고 origin 실행의 에이전트다. 맡긴 실행이면 맡겨진 에이전트이고 카드는 루트 실행의 대화에 뜬다(스펙 DECISION27).

카드를 띄우기 전에 `ConnectorAttachRequester` 가 아래 차례로 본다. 카드를 띄우지 못하면 `isError` 와 `status`, 까닭, 안내할 `link` 를 돌려준다(스펙 REQ16, REQ18).

| 차례 | 경우 | `status` | `link` |
| --- | --- | --- | --- |
| 1 | 요청자의 카탈로그에 그 플러그인이 없다 | `not_found` | `/connections` |
| 2 | 이 실행의 에이전트가 요청자의 것이 아니다(남의 에이전트, 주인 없는 에이전트) | `not_owner` | `/connections/{id}` |
| 3 | 요청자의 에이전트지만 그룹에 공개했다 | `agent_not_private` | `/connections/{id}` |
| 4 | 내 연결이 `READY` 가 아니다 | `not_connected` | `/connections/{id}?agent=<code>` |
| 5 | 이미 이 에이전트에 붙어 있다 | `already_attached`. 오류가 아니다. `thisAgent` 로 지금 쓸 수 있는지(`ready`, `pending`)를 함께 준다 | 없음 |
| 6 | `single_binding` 플러그인이고 그 연결이 이미 다른 에이전트에 붙어 있다 | `single_binding` | `/connections/{id}` |
| 7 | 그 밖 | `approval_requested` | 없음 |

같은 실행이 같은 플러그인으로 다시 불렀는데 앞 카드가 이미 끝났으면 `closed` 와 그 상태를, 요청하다 예외가 나면 `unavailable` 을 돌려준다. 둘 다 `isError` 다.

결과 글에는 플러그인의 이름을 넣지 않는다. 판정은 `connector_list` 와 같은 카탈로그(`ConnectorConnectionService.catalog`)로 한다.

### 승인 줄

PR2 의 Control Plane 승인 줄과 같다(위 「지우기 승인 카드」). 다른 값만 적는다.

| 칸 | 값 |
| --- | --- |
| `tool_name`, `hermes_tool` | `connector_attach`, `fos_connector_attach`. 카드 제목은 「이 에이전트에 붙이기」 다 |
| 인자 | `{"connector","agent","tools","caution","connector_id","agent_code"}`. 카드는 이름표(`서비스`, `에이전트`, `쓸 수 있게 되는 도구`, `주의`, `서비스 번호`, `에이전트 번호`)로 그린다 |
| `tools` | 연결 카드와 같은 한 줄이다(「도구 3개 · 조회 1 · 쓰기 2」). 위험도의 차례와 이름은 화면의 `toolRiskCounts` 와 같다 |
| `caution` | 화면의 붙이기 확인 창과 같은 안내다. 그 에이전트에 셸, 파일, 코드 실행 도구가 켜졌거나 도구를 읽지 못했으면 그 경고를 덧붙인다([`connector.md`](../connector.md) 의 「에이전트 사용 시작 안내」) |
| 같은 요청 | 사용자, origin 실행, 플러그인 번호와 에이전트 번호의 해시 |

### 승인 뒤 붙이기

**붙이기는 승인 트랜잭션 밖에서 돈다.** 지우기 두 가지는 승인 줄을 잠근 트랜잭션 안에서 끝나지만, 붙이기는 Hermes 대시보드에 설치를 보내고 `ConnectorInstallAttempts` 가 바깥 트랜잭션을 허용하지 않는다. 그래서 실행기가 `ControlPlaneActionRunner.outsideTransaction()` 으로 알리면 `ControlPlaneApprovals.approve` 가 세 걸음으로 나눈다.

1. 사용자 행과 승인 줄을 잠가 기다리는 줄인지와 기한을 보고 `EXECUTING` 으로 커밋한다
2. 트랜잭션 밖에서 `ConnectorAttachRunner` 가 `ConnectorBindingService.bind` 를 부른다. 주인, 비공개, 연결 준비, `single_binding`, 스킬 이름 겹침은 화면과 같은 검사가 승인하는 때에 다시 본다
3. 승인 줄을 다시 잠가 아직 `EXECUTING` 이면 `SUCCEEDED` 나 `FAILED` 와 알림 글을 적는다

2에서 서버가 내려가면 줄이 `EXECUTING` 으로 남고, 지금의 정리(`markInterrupted`, `markStale`)가 결과를 모르는 줄(`UNKNOWN`)로 끝낸다. 그 줄의 알림 줄은 「「이 에이전트에 붙이기」 요청의 결과를 확인하지 못했어요. 외부 서비스 연결 화면에서 상태를 확인해 주세요」 다. 다시 실행하지 않는다.

알림 줄은 화면의 사용 시점 안내(`boundReadiness`)와 같은 나눔이다(스펙 DECISION26).

| 붙인 뒤 상태 | 알림 줄 |
| --- | --- |
| 재시작 대기 | 「서비스」 를 「에이전트」 에 붙였어요. 관리자가 반영을 확인하면 쓸 수 있어요 |
| `READY` 이고 재시작 대기가 아니다 | 「서비스」 를 「에이전트」 에 붙였어요. 바로 쓸 수 있어요 |
| 나머지 | 「서비스」 를 「에이전트」 에 붙였어요. 몇 분 안에 쓸 수 있어요 |
| 붙이기가 거절됐다 | 붙이지 못했다는 말과 화면의 붙이기 오류와 같은 까닭 |
| 설치 호출이 실패했다(`CONNECTOR_OPERATION_FAILED`) | 붙이는 중이라는 말과 외부 서비스 연결 화면에서 상태를 확인하라는 안내. 바인딩이 `PENDING` 으로 남았을 수 있다 |

### 먼저 살펴보기에서 받는 PR4 도구

`connector_attach` 는 카드만 띄우므로 늘 받는다(스펙 DECISION32). 살펴보기의 에이전트가 요청자의 비공개 에이전트가 아니면 위 표의 2나 3으로 끝난다.

## 실패와 일관성

- 개념 목록은 고정된 글이라 실패할 곳이 없다. 글의 길이는 실행 기록의 문맥 길이(`AssembledContext.chars`)에 든다
- `connector_list` 가 카탈로그를 읽다 실패하면 MCP 오류가 아니라 실패 결과(`isError`)와 「목록을 읽지 못했다. `/connections` 를 안내한다」 를 돌려준다. turn 은 계속된다(REQ18 의 원칙)
- 배포 순서는 없다. Control Plane 만 바뀌고 Hermes 의 MCP 등록은 서버 하나라 도구가 늘어도 등록을 고치지 않는다
- 평가는 CI 와 배포에서 돌지 않는다. 판정의 단위 테스트만 CI 에서 돈다
- 배포 직후 첫 먼저 살펴보기는 지침 지문(`instructions_hash`)이 바뀌어 기억 신호를 한 번 「바뀜」 으로 본다(`ProactiveCheckInput`). 지침을 바꾸는 모든 배포와 같고 한 번뿐이라 막지 않는다
- 대화 화면의 링크는 서비스 안 주소여도 새 탭에서 열린다(`web/src/components/chat/markdown.tsx`). `/new?agent=` 도 새 탭에서 그 에이전트가 골라진 새 대화를 연다. 화면은 바꾸지 않는다

## 검증 전략

| 요구사항 | 경계와 환경 |
| --- | --- |
| REQ1 | `ContextAssembler` 단위 테스트가 세 범위의 지시문에 개념 이름과 다섯 주소가 있고 「사용량」, `/usage` 가 없는지, 목록이 기억 글 앞에 있는지 본다. 대화 turn, 예약 작업 turn, 먼저 살펴보기 turn 은 가짜 Hermes 가 받은 요청의 `instructions` 를 보는 통합 테스트로, 맡긴 실행은 `AgentRunner` 경로의 같은 테스트로 본다(`FileSpaceTurnTest` 와 같은 방식). 파일 공간 스펙 REQ7 의 기존 테스트가 그대로 통과한다 |
| REQ2-REQ4 | 지시문의 글은 단위 테스트, 모델이 따르는지는 평가 세트. REQ2 의 모델 단계는 DECISION35 로 평가 세트에서 빼고 지시문 단위 테스트만 본다 |
| REQ5 | `McpController` 웹 테스트가 인자 오류, 결과 모양, 다른 사용자의 연결이 보이지 않음, 비공개 에이전트의 `?agent=`, 그룹 공개 에이전트의 링크, 먼저 살펴보기 트리에서 받는 것을 본다 |
| REQ6 | 판정의 단위 테스트와 문항 형식 테스트가 CI 에서 돈다. 실제 모델 결과는 아래 「평가를 돌리는 때」 |
| REQ7-REQ10(PR2) | `TaskAgentRequests` 통합 테스트가 기본값(에이전트, 비운 모델 단계, `SINGLE` 대화, 맡긴 실행의 `NEW_PER_RUN`), 하루 발화 수 거절, 이름이 겹칠 때의 후보, 남의 작업, 매일 깨우기 제외를 본다. `ControlPlaneApprovals` 통합 테스트가 카드 줄, 승인 뒤 지움과 알림 줄, 거절, 다음 turn 으로 전하지 않음을 본다. `McpController` 웹 테스트가 인자 스키마, 실패 결과, 먼저 살펴보기의 받는 조건을 본다. 지시문 요소는 평가 세트의 `instructionIncludes` 가 본다 |
| REQ11-REQ14(PR3) | `McpFollowUpDirectToolTest` 가 HTTP 경계에서 실행 출처마다의 상태(사람이 보낸 메시지, 바깥 글을 읽은 대화, 예약 작업의 실행, 맡긴 실행, 살펴보기 트리), 제안 받아들이기, 끝내기와 후보, 남의 할 일을 본다. `McpMemoryForgetToolTest` 가 카드 줄과 내용, 승인 뒤 지움과 알림 줄, 거절, 그룹 기억의 권한, 남의 기억과 문서, 살펴보기에서 받는 것을 본다. 모델이 따르는지는 평가 세트의 할 일과 기억 문항이 본다 |
| REQ15-REQ16(PR4) | `McpConnectorAttachToolTest` 가 HTTP 경계에서 카드 줄과 내용(서비스, 도구 수, 주의), 승인 뒤 바인딩과 사용 시점 알림 줄, 거절, 그룹 공개 에이전트와 남의 에이전트, 준비되지 않은 연결의 링크, 이미 붙은 연결, 없는 플러그인, 승인하는 때의 재검사, 남의 연결, 끊긴 줄의 알림 줄, 살펴보기에서 받는 것을 본다. Hermes 대시보드의 커넥터 경로만 대역이다. 모델이 따르는지는 평가 세트의 붙이기 문항이 본다. 평가 계정은 그 플러그인을 연결하지 않은 것이 전제라, 문항은 붙였다고 말하지 않고 연결 화면 링크를 붙이는지만 본다 |
| DECISION18 | 저장소 테스트가 `ServiceConceptsInstructions` 의 주소마다 `web/src/app` 아래 경로가, 도구 이름마다 `mcp/application` 의 Java 파일이 선언한 도구 이름(목록 정의의 이름과 도구 클래스의 이름 상수)에 있는지 본다. 질의 문자열(`?agent=`)은 떼고, `{id}` 나 `<code>` 같은 틀 자리는 동적 경로 폴더(`[id]`)와 맞춘다 |

**평가를 돌리는 때.** 평가는 운영 Control Plane 과 실제 모델을 거친다. 바뀐 지침은 배포한 뒤에야 운영에 있으므로 PR 이 머지되기 전에는 바뀐 지침으로 돌릴 수 없다. 구현자는 실제 provider 와 운영에 닿지 않는다. 그래서 PR 에는 평가 틀과 문항을 넣는다. 머지 전에 코디네이터가 이 브랜치의 문항과 실행기로 지금 운영(바뀌기 전 지침)을 돌려 기준선을 PR 본문에 남기고, 머지·배포 뒤 다시 돌려 기능 완료 증거와 PR 댓글에 남긴다(스펙 DECISION33).

## 결정

| ID | 결정 | 답 | 누가 | 근거 |
| --- | --- | --- | --- | --- |
| DECISION1 | 개념 목록을 두는 자리 | 실행 입력의 앞 단락이 아니라 공통 지시문(`withResponseInstructions`) | 작성자 | 맡긴 실행까지 모든 실행이 지나는 곳은 여기 하나다. 글이 실행마다 같아 프롬프트 캐시에 든다 |
| DECISION2 | 플러그인 목록을 주는 방법 | 매 실행 지시문이 아니라 읽기 도구 `connector_list` | 작성자 | 목록은 사용자마다 다르고 연결을 부탁할 때만 필요하다. 매번 넣으면 스펙 DECISION11 의 짧은 목록과 어긋난다 |
| DECISION3 | 평가 결과를 남기는 때 | 스펙 DECISION33 을 따른다. 구현자는 문항 경로와 실행 인자를 코디네이터에게 넘기고 코디네이터가 운영에서 돌린다 | 작성자 | 구현자는 운영과 실제 provider 에 닿지 않는다 |
| DECISION4 | 평가 세트의 통과 기준 | 문항의 85% 이상(올림) | 작성자 | 답변 품질 평가의 출처 기준(15/17)과 같은 크기다. 문항별 통과 여부는 보고서에 모두 남는다 |
| DECISION5 | `connector_list` 의 모듈 경계 | `mcp.application.port.ConnectorListing` 을 두고 `connector.connection` 이 구현한다 | 작성자 | `mcp` 가 `connector` 를 부르면 순환이다([`code-architecture.md`](../../../backend/docs/code-architecture.md) 의 「`attention` 밖의 패키지는 `connector` 를 import 하지 않는다」). `FinancialLimitRequests` 와 같은 방식이다 |
| DECISION6 | 스펙 DECISION11(이름만 매번, 규칙은 도구 설명)의 ADR | 남기지 않는다. 현재 원본은 [`answer-quality.md`](../answer-quality.md) 의 「지침」 표다 | 작성자 | 지침 글은 되돌리기 쉽고 범위마다 넣는 것의 원본이 이미 그 표다. 새 정책이 아니라 그 표에 행을 더하는 일이다 |
| DECISION7 | PR1 이 함께 고칠 문서 | `answer-quality.md` 의 「지침」(모든 범위에 개념 목록), `mcp.md` 의 도구 표와 먼저 살펴보기에서 받는 도구, `backend/docs/code-architecture.md` 의 port 설명, 기능 파일 `service-concepts.md` | 작성자 | 구현이 바꾸는 규칙의 원본 문서다 |
| DECISION8 | 지우기 승인 줄을 두는 곳 | 새 표 없이 `connector_action` 에 Control Plane 승인 줄(`connector_id` 가 `fos-assistant`)로 둔다 | 작성자 | 사용자 결정(2026-11-01, 표를 늘리지 않는다). 카드 자리, 알림, 만료, 거절 알림 줄을 지금 흐름 그대로 쓴다. 주문 상한 변경 줄과 같은 방식이고, PR3 의 기억 지우기와 PR4 의 붙이기가 같은 자리를 쓴다 |
| DECISION9 | 승인 뒤 결과를 전하는 길 | 알림 줄만 남기고 다음 turn 으로 전하지 않는다 | 작성자 | 스펙 DECISION21. 결과가 정해진 동작이라 에이전트를 다시 돌릴 까닭이 없다 |
| DECISION10 | 하루 발화 수를 넘는 cron | 만들 때와 고칠 때 거절한다 | 작성자 | 스펙 REQ7 의 「넘으면 만들지 않고 까닭을 알린다」. 화면은 발화할 때 건너뛰지만, 에이전트가 만든 작업이 매일 반을 건너뛰면 사용자가 모른다. 화면의 검사는 바꾸지 않는다 |
| DECISION11 | 대상을 찾는 인자 | `task` 하나에 번호나 이름을 받는다 | 작성자 | 모델이 목록을 먼저 읽지 않아도 이름으로 부를 수 있다. 이름이 겹치면 후보만 돌려줘 REQ8 의 되묻기를 지킨다 |
| DECISION12 | 멈추기와 다시 켜기의 도구 | `task_set_state` 하나에 `state` 를 받는다 | 작성자 | 도구 정의는 매 실행 입력에 들어 글이 준다. 둘은 같은 대상과 같은 결과 모양이다 |
| DECISION13 | 바로 하는 쓰기와 승인 카드의 범위, 사람이 없는 실행 | [ADR-20261120 / agent-service-writes](../../../backend/docs/adr/ADR-20261120-agent-service-writes.md) 에 남긴다 | 작성자 | 스펙 「결정 기록 연결」 이 이 선택을 ADR 로 남기라고 정했다 |
| DECISION14 | 「사용자가 시켰음」 을 아는 방법 | 모델의 `requested` 인자와 실행 출처 판정을 함께 쓴다 | 작성자 | 시킨 것인지 스스로 판단한 것인지는 모델만 안다. 모델의 말만 믿으면 바깥 글이 시킨 할 일도 바로 생기므로 Control Plane 이 출처로 한 번 더 막는다. `memory_remember` 의 바로 저장 판정과 같은 방식이다 |
| DECISION15 | 바로 만드는 도구 | 새 도구를 두지 않고 `follow_up_propose` 에 인자를 더한다 | 작성자 | 도구 정의는 매 실행 입력에 든다. 만들기 도구가 둘이면 모델이 고르다 틀리고, 출처가 맞지 않을 때 제안으로 내리는 길도 한 도구 안에 있어야 한다 |
| DECISION16 | 끝낼 대상을 찾는 인자 | `follow_up` 하나에 번호나 제목을 받고 부분 일치까지 본다 | 작성자 | PR2 의 `task` 인자와 같은 모양이다. 열린 할 일은 제목이 겹치지 않아 같은 제목으로는 후보가 생기지 않으므로, 사용자가 줄여 말한 제목이 여러 줄에 걸리는 경우를 후보로 돌려준다 |
| DECISION17 | 기억을 가리키는 인자 | 번호(`memory_id`)만 받고 결과에 제목과 내용을 넣지 않는다 | 작성자 | 제목으로 찾아 후보를 돌려주면 그 collection 을 읽지 못하는 에이전트에게 기억 제목이 넘어간다. Memory 접근 권한은 Control Plane 이 정한다는 규칙을 지킨다 |
| DECISION18 | 기억 지우기 승인 줄을 만드는 곳 | `connector.action` 이 `mcp` 의 port 를 구현한다 | 작성자 | `memory` 와 `mcp` 는 `connector` 보다 아래 층이라 승인 줄을 직접 만들지 못한다. `connector` 는 이미 `memory` 를 쓴다 |
| DECISION19 | 살펴보기에서 `follow_up_done` | 쓰기를 켠 살펴보기만 받되 상태는 바꾸지 않는다 | 작성자 | 스펙 REQ10 은 바로 바꾸는 도구를 쓰기 스위치로 받게 하고, REQ12 는 사람이 시키지 않은 실행이 끝내지 못하게 한다. 둘을 모두 지킨다 |
| DECISION20 | 붙일 에이전트를 정하는 방법 | 인자로 받지 않고 origin 실행의 에이전트로 정한다 | 작성자 | 스펙 REQ15 가 「지금 에이전트」 에 붙인다고 정했다. 모델이 다른 에이전트의 code 를 넣어 권한을 넓히는 길을 두지 않는다. 다른 에이전트에 붙이는 일은 연결 화면이 한다 |
| DECISION21 | 승인한 붙이기를 실행하는 자리 | 승인 줄을 `EXECUTING` 으로 커밋한 뒤 트랜잭션 밖에서 화면과 같은 `ConnectorBindingService.bind` 를 부른다 | 작성자 | 붙이기는 외부 설치 호출을 보내고 시작 상태를 먼저 커밋해야 한다(`ConnectorInstallAttempts`). 승인 트랜잭션 안에서 부르면 사용자 행 잠금을 쥔 채 외부 호출을 기다린다. 버린 안은 붙이기 로직을 승인 트랜잭션용으로 따로 만드는 것이다. 검사와 실패 처리가 화면과 달라진다 |
| DECISION22 | 붙이기 카드의 주의 줄 | 화면의 붙이기 확인 창과 같은 안내를 카드에 넣는다 | 작성자 | 스펙 REQ15 의 「지금 화면의 붙이기와 같게」. 화면은 붙이기 전에 그 안내를 보이고, 대화에서는 승인 카드가 그 확인 창의 자리다 |
