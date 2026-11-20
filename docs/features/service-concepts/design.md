# 서비스 개념 기술 설계

스펙: [`spec.md`](spec.md). 이 설계는 PR1(REQ1-REQ6)의 구조를 정하고, PR2 부터 PR4 가 같은 자리에 무엇을 더하는지 경계만 적는다. 뒤 PR 의 도구 인자와 승인 카드 흐름은 그 PR 을 시작할 때 이 문서에 절을 더한다.

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
| REQ10 | PR2. 먼저 살펴보기에서 받는 조건은 `McpController` 가 정한다. 맡긴 실행의 대화와 에이전트는 origin 실행에서 정한다 |
| REQ11 | PR3. `follow_up_propose` 의 상태 판정에 「사용자가 시켰음」 과 실행 출처(사람 turn, 예약 작업, 살펴보기, 맡김)를 넣고 끝내기 도구를 더한다. 기억 지우기는 PR2 의 승인 카드 흐름을 쓴다. 판정 자리는 PR3 을 시작할 때 절을 더한다 |
| REQ12 | REQ11 행의 PR3 자리와 같다 |
| REQ13 | REQ11 행의 PR3 자리와 같다 |
| REQ14 | REQ11 행의 PR3 자리와 같다 |
| REQ15 | PR4. PR1 의 `connector_list` 결과와 `ConnectorBindingService` 의 붙이기를 승인 카드 뒤에서 부른다. 비공개·주인 판정은 지금 화면의 붙이기 검사를 그대로 쓴다 |
| REQ16 | REQ15 행의 PR4 자리와 같다 |
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
| 에이전트 | origin 실행의 에이전트. 맡긴 실행이면 맡겨진 에이전트다(DECISION27). 요청자가 대화를 시작할 수 없는 에이전트면 화면과 같이 거절한다 |
| 대화 방식 | origin 실행이 맡긴 실행이 아니고 대화가 있으면 `SINGLE` 에 그 대화를 미리 적는다(DECISION29). 발화는 지금의 `SINGLE` 처리대로 그 대화가 지워지지 않았고 에이전트가 같으면 이어 쓴다. 맡긴 실행이거나 대화가 없으면 `NEW_PER_RUN` 이다 |
| 시간대, 놓친 실행, 모델 단계 | 화면 기본값(`Asia/Seoul`, `RUN_ONCE`), 모델 단계는 비움 |
| 알림 | 인자가 없으면 화면 기본값(`ALWAYS`) |

검사는 화면의 `TaskService.create` 를 그대로 쓴다(작업 수 상한, 최소 간격, 이름과 지시 길이). 더해서 cron 이 지금부터 24시간 안에 `assistant.task.max-runs-per-day` 보다 많이 발화하면 만들지 않고 `daily_limit` 으로 거절한다(DECISION30). 화면은 이 수를 발화할 때만 막지만, 에이전트가 만든 작업이 하루의 반을 건너뛰는 일을 미리 막는다. 고칠 때도 같다.

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

승인은 `ConnectorActionService.approve` 가 Control Plane 줄을 상한 변경 줄처럼 먼저 갈라 `ControlPlaneApprovals.approve` 로 보낸다. 사용자 행과 줄을 잠그고 기다리는 줄인지와 기한을 본 뒤, 인자 해시가 맞는 작업 번호로 `ControlPlaneActionRunner`(구현은 `task` 의 `TaskDeleteRunner`)를 부른다. 성공하면 `SUCCEEDED` 와 「「작업 이름」 예약 작업을 지웠어요」, 그 사이 작업이 없어졌으면 `FAILED` 와 까닭이다.

승인 뒤에는 에이전트를 다시 돌리지 않는다(DECISION21). Control Plane 줄은 실행한 결과로 다음 turn 에 전하지 않고(`undeliveredResults`, `resultsFor` 에서 뺀다), 실행하지 않고 끝난 줄처럼 알림 줄로만 남긴다(`undeliveredClosures` 에 넣고 알림 글은 결과 글이다).

### 먼저 살펴보기와 맡긴 실행

| 도구 | 먼저 살펴보기(사건 살펴보기 포함) |
| --- | --- |
| `task_list` | 읽기라 늘 받는다 |
| `task_delete` | 카드만 띄우므로 늘 받는다(DECISION32) |
| `task_create`, `task_update`, `task_set_state` | 그 살펴보기의 `writes_allowed` 가 참일 때만 받는다. 받는 조건에 점검 대화를 보지 않는다. 만든 작업의 결과는 위 「만들 때 정하는 값」 대로 그 실행의 대화(점검 대화)에 쌓인다 |

예약 작업의 실행과 맡긴 실행은 사람이 보낸 실행과 같다(DECISION10). 맡긴 실행이 만든 작업은 맡겨진 에이전트로 돌고 매번 새 대화다.

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
| REQ2-REQ4 | 지시문의 글은 단위 테스트, 모델이 따르는지는 평가 세트 |
| REQ5 | `McpController` 웹 테스트가 인자 오류, 결과 모양, 다른 사용자의 연결이 보이지 않음, 비공개 에이전트의 `?agent=`, 그룹 공개 에이전트의 링크, 먼저 살펴보기 트리에서 받는 것을 본다 |
| REQ6 | 판정의 단위 테스트와 문항 형식 테스트가 CI 에서 돈다. 실제 모델 결과는 아래 「평가를 돌리는 때」 |
| REQ7-REQ10(PR2) | `TaskAgentRequests` 통합 테스트가 기본값(에이전트, 비운 모델 단계, `SINGLE` 대화, 맡긴 실행의 `NEW_PER_RUN`), 하루 발화 수 거절, 이름이 겹칠 때의 후보, 남의 작업, 매일 깨우기 제외를 본다. `ControlPlaneApprovals` 통합 테스트가 카드 줄, 승인 뒤 지움과 알림 줄, 거절, 다음 turn 으로 전하지 않음을 본다. `McpController` 웹 테스트가 인자 스키마, 실패 결과, 먼저 살펴보기의 받는 조건을 본다. 지시문 요소는 평가 세트의 `instructionIncludes` 가 본다 |
| DECISION18 | 저장소 테스트가 `ServiceConceptsInstructions` 의 주소마다 `web/src/app` 아래 경로가, 도구 이름마다 `McpToolCatalog` 의 이름이 있는지 본다. 질의 문자열(`?agent=`)은 떼고, `{id}` 나 `<code>` 같은 틀 자리는 동적 경로 폴더(`[id]`)와 맞춘다 |

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
