# backend 패키지

Control Plane 의 패키지마다 맡는 책임과 패키지 사이의 방향 규칙을 갖는다.
방향 규칙은 `ArchitectureRules.java` 가 강제하고, 이 파일은 규칙마다 그 검사의 이름을 적는다.

## 패키지와 책임

도메인별로 나누고 각 도메인 안은 `presentation` 에서 `application` 을 거쳐 `infra` 와 `domain` 으로 흐른다.
`presentation` 은 `infra` 를 바로 쓰지 않는다.
검사: `ArchitectureRules.LAYER_DIRECTION`

| 패키지 | 책임 |
| --- | --- |
| `shared/auth` | 토큰 검사와 현재 사용자, profile 토큰 필터의 타입, 토큰의 주소를 현재 사용자로 바꾸는 port |
| `shared/error` | 오류 코드와 응답 형태 |
| `shared/config` | 시계, 스케줄링, 보안 필터 설정, 실행 중에 쓰는 설정을 읽는 `LiveProperties`([ADR-20261007 / live-properties](adr/ADR-20261007-live-properties.md)). 그 빈을 만드는 `LivePropertiesConfig` 는 기능 패키지의 설정을 가져오므로 루트 패키지에 둔다 |
| `shared/util` | 외부 서비스의 글을 감싸는 함수와 문자열 지문, 에이전트가 만든 파일에 붙이는 `Content-Security-Policy` 값(`SandboxedContentPolicy`) |
| `shared/concurrent` | 요청 밖 작업을 띄우는 `BackgroundTasks`. 직접 가상 스레드를 띄우지 않는 까닭은 [ADR-20261007 / background-tasks](adr/ADR-20261007-background-tasks.md) |
| `shared/domain/type` | 모든 패키지가 권한 판정에 읽는 역할 값 |
| `user` | 사용자와 첫 로그인 처리 |
| `browser` | 사용자마다 하나씩 두는 브라우저의 상태와 전이, 브라우저 proxy 로 컨테이너 켜기와 끄기, 자동 중지와 상태 맞추기, 브라우저 중계의 접근 표식과 HTTP 창구, WebSocket([`backend/docs/flow.md`](flow.md)) |
| `model` | 모델 선택을 담는 값과 모델 단계 값. 서비스는 아직 `chat` 에 있다 |
| `agent` | 에이전트 등록과 사용자의 만들기·지우기, 공개 범위, Hermes profile 연결, 페르소나, 도구, 에이전트가 받는 Memory collection |
| `hermes` | Runs API 호출과 profile key 조회, 대시보드 호출 |
| `chat` | 대화, 메시지, 한 번의 실행 흐름, 대화의 모델 선택, 추천 질문 생성, 흐름의 계약과 등록 |
| `usage` | 실행 기록, 실행 사건, 비용 환산, 사용량 조회, 사용자 실행 한도([`backend/docs/flow.md`](flow.md)) |
| `feedback` | 제안에 대한 사용자 반응과 실행 결과의 사건 저장, 대화 삭제와 보관 기간의 정리, 반응 읽기 규칙([`backend/docs/flow.md`](flow.md)). 위 패키지가 부르기만 하고 이 패키지는 그 패키지를 모른다 |
| `memory` | 개인과 그룹 공용 Memory, 제안과 승인, 판 기록, 그룹의 collection 목록, 에이전트의 실행에 보이는 항목 판정, 문서 쓰기와 고치기, 서비스 토큰, 다른 서비스의 문서 읽기, 기존 개인 지식의 들이기, 관리자의 에이전트 collection 설정과 빠진 항목 수 |
| `context` | 실행에 넣을 `instructions` 조립과 문맥 묶음의 항목 모델 |
| `mcp` | Memory 본문 조회, 결과물 쓰기와 할 일 제안 도구의 인자 검사, 장기 토큰 인증과 profile 묶기, 요청자 판정 |
| `people` | 로그인 허용 목록과 사람을 더하는 흐름, 첫 로그인에 그 사람의 에이전트 만들기 |
| `orchestration` | 흐름의 구현과 자식 실행, MCP `agent_*` 위임의 시작과 조회와 중지, 하위 에이전트 session 등록 |
| `skill` | 올린 스킬의 읽기와 쓰기, 스킬 zip 묶음의 받기와 검사, 버전 디렉터리, Hermes 에 게시, 스킬 목록과 호출 이력 조회 |
| `connector` | 커넥터 카탈로그, 사용자별 연결, 에이전트에 연결을 붙이는 바인딩, 커넥터 도구 호출의 판정과 기록 |
| `task` | 예약 작업과 시각, 발화 기록, 발화기와 예약 turn 시작([`backend/docs/flow.md`](flow.md)) |
| `notification` | 사용자에게 대화 밖에서 알리는 줄의 저장과 읽음 표시, 사용자 단위 SSE, 오래된 줄 정리([`backend/docs/flow.md`](flow.md)) |
| `followup` | 할 일의 저장과 상태 전이, 사람이 쓰는 API, 에이전트의 제안 저장([`backend/docs/flow.md`](flow.md)) |
| `proactive` | 먼저 살펴보기의 시작 전 점검, 점검 대화의 살펴보기 turn, 상한, 결과 계약의 검사와 그리기, 문제 후보의 검사와 저장, 살펴보기 트리 판정([`backend/docs/flow.md`](flow.md)), 판단 피드백의 replay 읽기 모델, 매일 루프의 이음매와 보일 판정, 판정 반응([`backend/docs/flow.md`](flow.md)) |
| `attention` | 먼저 알리기의 판정과 지금 화면이 읽는 카드. 다른 패키지의 기록을 읽기만 한다([`backend/docs/flow.md`](flow.md)) |
| `workspace` | 사용자 실행 공간의 파일 목록과 본문, 권한 도우미로 지우기, 관리자의 공간별 용량. 아래 「실행 공간 파일」 |

검사: `ArchitectureRules.SHARED_DOES_NOT_DEPEND_ON_DOMAINS`

**`mcp` 는 `orchestration` 을 부르고, `orchestration` 은 `mcp` 를 import 하지 않는다.**
위임 서비스는 `McpCaller` 를 받지 않고 요청자와 origin 실행을 따로 받는다.
두 패키지가 서로를 import 하면 한쪽을 바꿀 때 다른 쪽의 타입을 함께 바꿔야 하기 때문이다.
검사: `ArchitectureRules.ORCHESTRATION_DOES_NOT_DEPEND_ON_MCP`, `ArchitectureRules.TOP_LEVEL_PACKAGES_FREE_OF_CYCLES`

**경로 변수와 요청 인자의 형식이 틀리면 어느 경로든 400 `VALIDATION_FAILED` 다.**
처리는 `shared/error` 의 `GlobalExceptionHandler` 가 갖는다.
요청 본문의 형식 오류는 이 규칙에 걸리지 않고 Control Plane 에서 500 이다. 본문의 대화 식별자는 web 서버 라우트가 먼저 검사해 400 으로 막는다.

### 최상위 패키지의 층 순서

최상위 패키지의 순서는 `TopLevelPackageOrder.ORDER` 가 아래에서 위로 적어 갖는다.

위 패키지는 아래 패키지를 쓰고 아래 패키지는 위 패키지를 import 하지 않는다.
거꾸로 써야 하면 아래 패키지에 port 를 두고 위 패키지가 구현한다.
새 최상위 패키지를 만들면 이 순서의 자리를 정하고 `TopLevelPackageOrder.ORDER` 에 넣는다.
`shared` 는 순서 밖이고 어느 패키지도 쓰지 않는다.
`crypto` 는 맨 아래다. 사용자별 데이터 key 와 KEK 를 갖고, 본문을 저장하는 패키지가 `crypto.domain` 의 `TextCipher` 로 암호화한다. 메시지 저장은 `infra` 에서 일어나 port 를 `application` 이 아니라 `domain` 에 둔다([ADR-20261008 / data-encryption](adr/ADR-20261008-data-encryption.md)).
`task` 는 `attention` 바로 아래다. 예약 turn 을 열려고 `chat` 을, 에이전트와 주인을 다시 확인하려고 `agent` 와 `user` 를, 결과를 알리려고 `notification` 을 쓴다. 대화 목록이 작업 이름을 보이려고 `chat` 에 port(`ConversationTaskLabels`)를 두고 `task` 가 구현한다.
`followup` 은 `chat` 바로 위다. 대화 주인을 확인하고 공개 식별자를 얻으려고 `chat` 을 쓰고, 제안 도구(`mcp`)와 먼저 알리기(`attention`)가 `followup` 을 쓴다.
`browser` 는 `user` 바로 위다. 관리자 목록의 사용자 이름을 읽으려고 `user` 를 쓰고, 사용자를 끈 사건(`shared.auth.UserAccessRevoked`)을 받는다. 커넥터 바인딩이 브라우저 중계를 쓰게 되므로 `connector` 보다 아래에 둔다. 중계가 바인딩 표식의 주인을 찾으려고 `browser` 에 port(`BrowserGrantOwners`)를 두고 `connector` 가 구현한다.
`notification` 은 `user` 바로 위다. 알림을 만드는 쪽(`connector`, 그 위의 패키지)이 모두 이 패키지를 부르고, 이 패키지는 알림을 받는 사용자 말고 다른 도메인을 모른다.
`agent` 는 지운 에이전트의 행을 정리할 때 위 패키지 표의 딸린 줄을 직접 지우지 않는다. `agent` 에 port(`AgentPurgeParticipant`)를 두고 `chat`, `proactive`, `connector` 가 구현해, 기다릴지 답하고 자기 표의 줄을 지우거나 비운다([ADR-20261009 / agent-purge](adr/ADR-20261009-agent-purge.md)).
`attention` 은 `workspace` 바로 아래다. 먼저 알리기의 후보를 읽으려고 `usage`, `chat`, `agent`, `memory`, `connector`, `followup` 의 `application` 을 부르고, 어느 패키지도 `attention` 을 import 하지 않는다.
`workspace` 는 맨 위다. 함께 쓰는 에이전트와 관리자 용량의 에이전트 이름을 읽으려고 `agent` 를, 도는 실행 수를 읽으려고 `usage` 를, 관리자 용량의 사용자 이름을 읽으려고 `user` 를 부른다. 어느 패키지도 `workspace` 를 import 하지 않는다.
검사: `ArchitectureRules.TOP_LEVEL_PACKAGES_FOLLOW_LAYER_ORDER`, 근거: ADR-068

### proactive

문제 후보의 가치 평가는 `ValueEvaluationService`와 `ValueEvaluator`가 맡는다.
`DecisionProvider`는 모델과 무관한 판단 port이며 첫 adapter는 도구 없는 시스템 profile에 Hermes Runs로 묻는다.
권한과 행동 정책을 결정하지 않는다([가치 평가](flow.md)).
행동 수준은 모델을 모르는 `AutonomyPolicy` 가 정하고 `AutonomyPolicyService` 가 판정을 남긴다. `EXECUTE` 는 `ProactiveCheckService.startAutonomous` 로 읽기 전용 살펴보기를 시작한다([행동 정책](flow.md)).
매일 깨우기 뒤 두 서비스를 잇는 것은 `ProactiveLoopCoordinator` 다. `ProactiveCheckService` 가 낸 `ProactiveCheckSettled` 사건을 받아 순환 의존 없이 부른다([매일 루프](flow.md)).

`proactive` 는 `followup` 바로 위다. 살펴보기 turn 은 `ChatService.runProactiveCheck` 가 돌리고, 살펴보기만의 일은 `chat` 이 가진 port `CheckTurn` 을 `proactive` 가 구현해 넘긴다.
`chat` 은 `proactive` 를 import 하지 않는다. 기동 정리가 끝낸 살펴보기 turn 의 답을 대화에 남기지 않도록, `chat` 의 port `RecoveredAnswerGuard` 도 `proactive` 가 구현한다. 사용자가 점검 대화를 읽으면 그 대화의 보고를 연 것으로 적도록 `chat` 의 port `CheckReportReads` 도 `proactive` 가 구현한다.
`orchestration`, `mcp`, `connector` 는 `proactive` 보다 위라 `ProactiveCheckGuard` 로 살펴보기 트리인지 묻는다.
살펴보기가 끝나 도는 위임 자식을 멈추는 일은 `proactive` 가 낸 `ProactiveCheckEnded` 사건을 `orchestration` 이 받아 한다.

### connector

`connector`는 커넥터 카탈로그와 사용자별 연결의 등록, 확인, 해제와 비밀값을 제외한 상태를 소유한다.
연결을 에이전트에 붙이고 떼는 바인딩(`agent_connector_binding`)과 관리자 반영 완료도 이 패키지가 갖는다. `ConnectorConnectionService` 가 연결을, `ConnectorBindingService` 가 바인딩을 맡는다([ADR-083](../../docs/adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md)).
특정 서비스의 이름, 주소, env 이름, 토큰 형식을 코드에 두지 않는다. 모두 대시보드 plugin 이 내는 manifest 에서 온다([ADR-043](adr/ADR-043-커넥터는-plugin-의-connector-json-으로-선언하고-control-plane-은-범용-흐름만-갖는다.md)).
카탈로그, 도구 호출, 설치, env, MCP probe 는 `hermes`의 `HermesConnectorClient` 가 HTTP로 호출한다.
`connector.application` 의 `ConnectorCallLimiter` 가 선택지 조회, 등록, 연결 확인을 사용자별로 제한한다. 한도는 `ConnectorProperties`(`assistant.connector`)가 갖고 상태는 JVM 메모리에 둔다. Control Plane 이 한 대라는 전제다.
`agent` 가 바인딩을 알아야 하는 자리는 `agent.application` 에 둔 port 셋으로 부른다. `connector` 가 `agent` 보다 위라서다.

| port | 하는 일 | 구현 | 부르는 곳 |
| --- | --- | --- | --- |
| `AgentConnectorBindings` | `hasBindings`, `connectorServers`, `connectorToolPrefixes`. 저장한 바인딩만 읽고 대시보드를 부르지 않는다 | `ConnectorBindingLookup` | 공개 범위 변경과 관리자 수정(비공개 유지), 도구 저장과 스킬 게시(붙은 서버 이름을 함께 보낸다), 실행 기록의 도구 내용 가림, 먼저 살펴보기의 시작 전 점검과 지시 |
| `AgentConnectorDetacher` | `detachAll`. 그 에이전트의 바인딩을 모두 뗀다 | `ConnectorBindingService` | 에이전트 지우기 |
| `AgentPurgeParticipant` | 지운 에이전트를 정리할 때 바인딩을 지우고, 연결과 승인 줄의 `agent_id` 를 비운다 | `ConnectorAgentPurge` | 지운 에이전트 정리 |

옛 커넥터 에이전트가 남아 있는 동안 그 실행에는 Memory 문맥을 주지 않는다. `ChatService` 와 `AgentRunner` 가 `Agent.connectorManaged()` 를 보고 빈 문맥으로 돌린다. `AgentMemoryCollectionService` 도 옛 커넥터 에이전트에 받는 collection 을 주지 않는다. `McpCallerResolver` 는 origin 실행의 에이전트가 옛 커넥터 에이전트이면 Control Plane MCP 호출을 거절한다([ADR-045](adr/ADR-045-커넥터-에이전트는-자기-mcp-서버만-받고-memory-와-control-plane-도구를-받지-않는다.md)). 연결을 붙인 일반 에이전트에는 이 경계가 걸리지 않는다.
웹은 `components/connector`와 `app/connections`, `app/connections/[id]`, 대응 서버 라우트가 맡는다. 입력 칸은 manifest 의 `fields` 로 그린다.
특정 서비스 이름이 코드에 들어왔는지는 `test/unit/connector-neutral.test.ts` 가 보고, 예외도 그 시험이 갖는다.
계약은 [커넥터 연결](../../docs/prd.md)에 있다.

`connector` 는 커넥터 도구 호출의 판정과 그 기록도 소유한다([ADR-049](adr/ADR-049-커넥터-도구-호출은-profile-plugin-의-hook-이-control-plane-에-물어-판정한다.md)).

| 클래스 | 하는 일 |
| --- | --- |
| `connector.presentation.ConnectorPolicyController` | `POST /internal/hermes/connector-policy`. profile 토큰으로 인증한 요청을 받아 서명을 확인하고 판정을 돌려준다 |
| `connector.application.ConnectorPolicyRequest` | 요청 본문과 서명 검증. `_fos_ctx` 와 같은 key 를 쓰고 HMAC 은 `mcp.application.McpCallContext` 의 것을 부른다 |
| `connector.application.ConnectorPolicyService` | 실행과, 그 실행의 에이전트에 붙은 바인딩과, 카탈로그를 찾아 판정하고 `connector_action` 한 줄을 남긴다. 승인 줄이면 같은 트랜잭션에서 `APPROVAL_REQUESTED` 알림도 남긴다 |
| `connector.application.ConnectorCatalogCache` | 판정 경로가 쓰는 카탈로그를 60초 동안 메모리에 둔다. 화면 경로는 쓰지 않는다 |
| `connector.domain.ToolPolicyDecision` | 판정 함수. Hermes 와 DB 를 모른다 |
| `connector.domain.ConnectorAction` | 판정 한 줄과 승인 줄. 승인 상태 전이를 갖는다 |

**`attention` 밖의 패키지는 `connector` 를 import 하지 않는다.** `connector` 가 `agent`, `hermes`, `mcp`, `orchestration`, `usage`, `user` 를 부른다. 붙일 때 스킬 이름이 겹치는지 보려고 `skill` 도 부른다. 승인 줄의 대화 권한을 확인하고 알림이 가리킬 대화의 공개 식별자를 찾으려고 `chat` 도 부른다. 승인 요청과 만료의 알림을 같은 트랜잭션에서 만들려고 `notification` 도 부른다. 설치와 확인 호출에 중계 주소를 실으려고 `browser` 의 `BrowserGatewayTokens` 도 부른다. `connector` 가 `mcp` 를 쓰므로 `mcp` 가 `connector` 를 부르면 순환이 된다. `chat` 이 승인 결과를 읽어야 할 때는 `chat` 에 port 를 두고 `connector` 가 구현한다. `attention` 은 `connector` 보다 위라 승인 대기를 읽으려고 `connector.application` 의 읽기 메서드를 부른다.
검사: `ArchitectureRules.TOP_LEVEL_PACKAGES_FREE_OF_CYCLES`

## 한 번의 대화가 지나는 길

1. `ChatController` 가 현재 사용자를 확인한다.
2. `ChatService` 가 요청한 에이전트를 사용자가 쓸 수 있는지 확인하고 `conversation` 에 기록한다.
   이어지는 대화는 요청의 `agentCode` 를 무시하고 처음 기록한 에이전트를 쓴다.
3. `ContextAssembler` 가 이 실행에 넣을 `instructions` 를 조립한다. 순서는 아래 표다.
4. `ExecutionRecorder` 가 `RUNNING` 상태로 실행 한 줄을 먼저 만든다.
5. `HermesProfileKeyStore` 가 그 profile 이름의 key 파일을 읽는다. 없으면 거기서 끝난다.
6. `HttpHermesRunsClient` 가 실행을 제출하고 받은 `run_id` 를 그 자리에서 실행 줄에 적는다. 싣는 모델과 effort 는 [모델 단계와 실행 기록](flow.md) 의 「모델 선택」 이 정한다.
7. 스트림으로 오는 사건을 화면으로 중계하면서 `execution_event` 로도 옮겨 적는다.
8. `CostEstimator` 가 토큰을 models.dev 가격표로 환산한다. 환산은 이 자리에서 한 번만 하고 쓴 가격표를 함께 적는다([ADR-004](adr/ADR-004-구독제에서도-api-가격으로-환산해-보인다.md)).
9. `ExecutionRecorder` 가 4번에서 만든 줄을 끝난 상태로 갱신한다. 실패해도 그 줄은 남는다. 합계 규칙은 [모델 단계와 실행 기록](flow.md) 의 「합계와 완전성」 이 갖는다.

`instructions` 를 조립하는 순서는 아래와 같다.

| 순서 | 담는 것 |
| --- | --- |
| 1 | 공통 답변 지침. 답변 형식과 도구 호출 지침이다(`ContextAssembler.withResponseInstructions`) |
| 2 | 「# 기억」 지침. `memory_remember` 를 받는 실행에만 붙는다 |
| 3 | Memory 문맥. 층과 그 순서는 [`backend/docs/flow.md`](flow.md) 의 「범위와 조립」 이 갖는다 |
| 4 | 묻는 형식 안내(`chat/application/AskFormat`). 사용자가 직접 답하는 대화 실행에만 붙는다 |
| 5 | 이 turn 에만 붙는 지시(`TurnIntent`). 다시 생성, 결과 전달, 예약 작업, 먼저 살펴보기가 저마다 지시를 붙인다 |

3 만 Memory 의 글자 상한 안에서 고르는 몫이고, 나머지는 상한과 따로 붙는다.
묻는 형식은 `web/src/lib/ask.ts` 가 카드로 읽는다. 둘이 같은 형식을 말해야 한다.

3 에 어느 항목이 드는지는 [`backend/docs/flow.md`](flow.md) 의 「에이전트의 실행에 보이는 항목」 이 갖는다.

## 가격표

`usage/infra/ModelsDevPriceCatalog` 가 models.dev 카탈로그를 읽어 메모리에 둔다.
파일이 바뀌면 재기동 없이 다시 읽는다. 새 파일이 읽히지 않거나 가격이 하나도 없으면 이전 가격을 계속 쓴다.

Hermes profile 디렉터리를 그대로 붙이지 않는다.
그 디렉터리에는 `.env` 와 `auth.json` 이 함께 있어서 credential 까지 컨테이너에 들어간다.
배포할 때 카탈로그 파일만 중립 경로로 복사하고 그쪽을 읽기 전용으로 붙인다.

카탈로그가 없거나 읽히지 않아도 기동은 계속하고 금액만 비워 둔다.

## 합계 질의는 다른 도메인의 엔티티를 조인해도 된다

`usage` 의 축별 합계가 에이전트 이름을 얻으려고 `agent` 의 엔티티를 JPQL 로 조인한다.
줄마다 에이전트를 다시 찾으면 축 하나에 질의가 실행 수만큼 늘어나기 때문이다.

층 흐름 규칙은 한 도메인 **안**의 방향만 정한다. 도메인끼리의 조인을 금지하지 않는다.
읽기만 하는 합계 질의에 한해 이 결합을 허용한다.
쓰는 쪽은 그렇지 않다. 다른 도메인의 상태를 바꿀 때는 그 도메인의 서비스를 부른다.

## 통합 검사

왜 컨텍스트를 함께 쓰는지는 [ADR-20261007 / test-context-base](adr/ADR-20261007-test-context-base.md) 가 갖는다.

### 기반 주석

Spring 컨텍스트가 필요한 검사는 `@SpringBootTest` 대신 `@BackendIntegrationTest` 를 단다.
주석과 대역은 `backend/src/test/java/com/bifos/assistant/testsupport/` 에 있다.

```java
@BackendIntegrationTest
class ConversationPagingTest {
    @Autowired
    ChatService chat;
}
```

기반이 주는 대역과 mock, spy 는 `BackendIntegrationTest` 의 주석 선언과 `IntegrationTestDoubles` 가 갖는다.
MySQL 태그 검사의 기준 클래스만 `@SpringBootTest` 로 따로 둔다.

**검사 클래스에 컨텍스트 키를 바꾸는 선언을 두지 않는다.** `@MockitoBean`, `@MockitoSpyBean`, `@Import`, `@TestPropertySource`, `@DynamicPropertySource`, 중첩 `@TestConfiguration`, 슬라이스 주석 등이다. 전체 목록은 구조 규칙 `ArchitectureRules.TESTS_DO_NOT_SPLIT_CONTEXT` 가 갖는다. `@Tag("mysql")` 검사는 예외다.
하나라도 두면 Spring 이 컨텍스트를 새로 띄운다. 구조 규칙이 막는다.
- 기반에 있는 타입은 `@Autowired` 로 받는다.
- 설정 값을 바꿔야 하면 아래 「설정 바꾸기」 를 쓴다.
- mock 이 필요하던 빈은 기반의 spy 로 올리고 `doReturn(...).when(spy)` 로 바꾼다. 정하지 않은 메서드는 실제로 돈다.
- 검사만 쓰는 대역 빈이 필요하면 꺼 두면 아무것도 하지 않는 대역으로 기반에 올리고 그 검사가 켠다. 공통 확장이 검사 뒤에 끈다.

### 설정 바꾸기

운영 코드는 실행 중에 쓰는 설정을 `LiveProperties<T>` 에서 읽는다([ADR-20261007 / live-properties](adr/ADR-20261007-live-properties.md)).
검사는 `@OverrideProperties` 로 그 값을 바꾼다. 값의 모양은 `application.yml` 의 키와 같다.

```java
@BackendIntegrationTest
@OverrideProperties({"assistant.user-execution.max-running=2", "assistant.user-execution.background-reserve=1"})
class UserExecutionLimitBackgroundTest { ... }
```

- 공통 확장이 검사마다 시작 전에 적용하고, 끝난 뒤 기동 값으로 되돌린다. 컨텍스트는 새로 뜨지 않는다.
- 바꾼 값도 운영 record 의 생성자를 거쳐 같은 검증을 받는다.
- `LiveProperties` 로 읽지 않는 설정을 적으면 검사가 실패한다. 그 값을 바꾸려면 먼저 운영 사용처를 `LiveProperties` 로 옮긴다.
  prefix 아래에 있어도 설정 record 의 칸이 아닌 키(`assistant.browser.sweep-interval` 처럼 `@Scheduled` 가 기동 때 읽는 값)도 실패한다.
- 여럿이 쓰는 묶음은 이름 있는 주석으로 둔다. `@OverrideProperties` 를 메타 주석으로 갖는다.

이름 있는 주석은 `testsupport/` 에 있고, 무엇을 바꾸는지는 각 주석의 선언이 갖는다.

### 검사 사이에 남기지 않는 것

컨텍스트와 H2 DB 를 앞뒤 검사와 함께 쓴다.

- **검사가 띄운 백그라운드 작업은 그 검사 안에서 끝난다.**
  운영 코드는 요청 밖 작업을 `BackgroundTasks` 로 띄운다([ADR-20261007 / background-tasks](adr/ADR-20261007-background-tasks.md)).
  기반은 그 자리에 띄운 스레드를 쥐는 `TrackingBackgroundTasks` 를 넣고, 공통 확장 `IntegrationTestIsolation` 이 검사가 끝날 때 모두 join 한다.
  30초 상한은 멈춘 작업을 잡는 데만 쓴다. 넘으면 남은 스레드 이름과 함께 그 검사가 실패한다.
  turn 을 붙잡는 대역(`holdSubmits()` 등)을 쓴 검사는 끝나기 전에 푼다.
  검사 안에서 작업 결과를 단언할 때는 그 검사가 기다릴 조건을 직접 기다린다. 공통 확장의 join 은 검사가 끝난 뒤에 돈다.
- **앞 검사가 남긴 줄에 기대지 않는다.** 사용자와 에이전트는 검사마다 새로 만든다.
  고정 code 를 쓰는 검사가 검사 트랜잭션 안에서 지우고 다시 넣으면, Hibernate 가 넣기를 먼저 내보내 유일 제약에 걸린다. 지운 뒤 `flush()` 한다.
- **기반의 spy 는 `verify(...)` 의 matcher 사이에서 부르지 않는다.** Mockito 가 그 호출을 matcher 를 쓰는 호출로 읽는다. 값을 먼저 지역 변수로 받는다.
- **검사가 바꾼 static 상태는 `@BeforeEach` 에서 되돌린다.**
- **운영 빈이 JVM 메모리에 두는 캐시는 다음 검사에 남는다.** 보관 시간을 test profile 에서 짧게 두거나, 그 캐시가 시험 시계를 쓰게 해 검사가 시간을 옮긴다. 검사가 private 필드를 바꿔 비우지 않는다.
  - 커넥터 카탈로그 캐시는 카탈로그 하나를 열쇠 없이 들고 있어 실제 시각으로 판정한다. 시험 시계를 쓰면 시계를 과거로 멈추는 검사에서 앞 검사의 카탈로그가 보관 시간 안으로 들어온다. test profile 의 보관 시간과 실패 기억 시간을 1ms 로 두고, 지나가게 하려는 검사는 2ms 를 기다린다.
  - 스킬 커맨드 캐시는 `Clock` 빈을 받아 검사에서 `TestClock` 을 쓴다. 열쇠가 에이전트 번호이고 검사마다 에이전트를 새로 만들어, 시계를 과거로 멈춰도 앞 검사의 목록을 받지 않는다.

## 코드 품질 검사

기준 파일을 갱신하거나 규칙을 뺄 때 읽는 절차를 갖는다.

### 구조 규칙의 기준 파일

`backend/config/archunit/store/` 의 기준 파일이 지금 있는 위반을 얼려 둔다. 기준의 뜻은 [ADR-042](../../docs/adr/ADR-042-코드-품질-규칙은-도구-설정이-갖고-기존-위반은-기준-파일에-둔다.md) 가 갖는다.
`stored.rules` 가 규칙의 `as(...)` 설명과 기준 파일 이름을 잇는다.
설명을 바꾸면 기준이 새 규칙으로 옮겨지지 않으므로, 설명을 바꿀 때는 그 규칙을 다시 얼린다.

위반을 고치면 그 기준 파일도 같은 커밋에서 줄인다.
지금은 구조 규칙의 위반을 모두 고쳐 기준 파일이 비어 있다. 파일은 새 위반을 받아들여야 할 때를 위해 남긴다.

평소의 테스트는 기준 파일을 쓰지 않고, 기준과 실제가 어긋나면 실패한다.
쓰기는 Gradle 속성으로만 켠다. 명령은 모두 `backend/` 에서 돈다.

| 언제 | 어떻게 |
| --- | --- |
| 기준에 든 위반을 고쳤다 | 평소 테스트가 `StoreUpdateFailedException: Updating frozen violations is disabled` 로 실패한다. `./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true` 로 기준에서 빼고 그 변경을 같은 커밋에 넣는다. `scripts/quality.sh fix` 도 같은 명령으로 기준을 줄인다 |
| 기준에 든 클래스의 이름만 바꿨다 | 같은 예외로 실패한다. 아래 「새 위반을 받아들이거나 다시 얼린다」 를 따른다 |
| 규칙을 새로 더했다 | `./gradlew archTest --rerun -Parchunit.freeze.store.default.allowStoreUpdate=true` 로 그 규칙의 기준 파일을 만든다. `allowStoreCreation` 은 `stored.rules` 가 없을 때만 쓴다 |
| 새 위반을 받아들이거나 다시 얼린다 | `./gradlew archTest --rerun --tests '*ArchitectureRulesTest.<메서드>' -Parchunit.freeze.refreeze=true -Parchunit.freeze.store.default.allowStoreUpdate=true`. 까닭을 커밋 메시지와 PR 본문에 적는다 |

**다시 얼릴 때는 `--tests` 로 규칙 하나만 대상으로 삼는다.**
`refreeze` 는 그 실행이 검사하는 모든 규칙을 다시 얼린다.
`--tests` 를 빼면 다른 규칙의 새 위반까지 조용히 기준에 들어간다.

### 뺀 규칙

| 규칙 | 까닭 |
| --- | --- |
| `LineLength`, `Indentation`, `WhitespaceAround`, `CustomImportOrder` | 포매터가 정한다. 둘이 같은 것을 다르게 판정하면 고칠 수 없는 위반이 생긴다 |
| `JavadocMethod`, `JavadocType`, `MissingJavadocMethod` 같은 Javadoc 규칙 | 주석은 한국어로 필요한 곳에만 쓴다(`backend/AGENTS.md` 의 「주석」 절). 모든 메서드에 요구하면 뜻 없는 주석이 늘어난다 |
| `MagicNumber` | 테스트와 설정 기본값에서 대부분 오탐이다 |
| `FinalParameters`, `HiddenField` | 생성자 주입과 record 가 이름을 같게 쓰는 것이 이 저장소의 모양이다 |
| `DesignForExtension` | Spring 빈과 싸운다 |

### 코드 규칙의 기준 파일

지금 있는 위반은 `config/checkstyle/baseline.xml` 에 `(파일, 규칙)` 한 쌍마다 한 줄로 둔다.
지금은 error 위반을 모두 고쳐 `baseline.xml` 이 비어 있다. 파일은 새 위반을 받아들여야 할 때를 위해 남긴다.

| 언제 | 어떻게 |
| --- | --- |
| 기준에 든 위반을 고쳤다 | `baseline.xml` 에서 그 줄을 지운다. Checkstyle 은 쓰지 않는 기준 줄을 알리지 않으므로 고친 사람이 직접 지운다. 같은 커밋에 넣는다 |
| 새 위반이 생겼다 | 기준에 더하지 않고 고친다 |
| 꼭 받아들여야 한다 | 까닭을 커밋 메시지와 PR 본문에 적고 그 줄을 더한다 |
| 규칙을 새로 더했다 | 기준을 비운 뒤 `build.gradle.kts` 의 `isIgnoreFailures` 를 잠시 `true` 로 두고 `./gradlew checkstyleMain checkstyleTest` 를 돌린다. 보고서에서 severity 가 error 인 위반의 `(파일, 규칙)` 을 뽑아 줄을 만들고 `isIgnoreFailures` 를 `false` 로 되돌린다. 뽑는 스크립트는 저장소에 두지 않는다 |

줄의 모양이다. 경로 구분자는 `[\\/]` 로 쓰고, 파일 경로 순으로 둔다.

```xml
<suppress checks="(^|\.)NeedBraces(Check)?$" files="src[\\/]main[\\/]java[\\/]...[\\/]AgentService\.java$"/>
<suppress id="lombokLogger" files="src[\\/]main[\\/]java[\\/]...[\\/]ChatService\.java$"/>
```

- 내장 규칙은 `checks` 로 걸고 끝을 `(Check)?$` 로 고정한다. 고정하지 않으면 `ParameterName` 이 `LambdaParameterName` 까지 억제한다
- 정규식 규칙과 `id` 를 붙인 규칙(`RightCurly` 둘)은 `id` 로 건다. `checks` 로 걸면 같은 종류의 규칙이 모두 억제된다. `RightCurly` 를 `checks` 로 걸면 `rightCurlyAlone` 과 `rightCurlySame` 이 함께 꺼진다
- warning 규칙은 기준에 넣지 않는다

**기준은 `(파일, 규칙)` 단위라 한계가 있다.**
줄 번호로 두면 파일을 고칠 때마다 기준이 어긋나기 때문이다.
그 대신 기준에 든 파일에 같은 규칙의 위반이 새로 생겨도 잡지 못한다.
그 파일을 고칠 때는 그 규칙의 위반을 모두 고치고 기준 줄을 지우는 것을 원칙으로 한다.

### 파일 길이 기준 목록

`scripts/check-file-length.mjs`가 언어 공통 파일 길이 규칙을 소유한다.
빈 줄과 주석을 포함한 전체 줄 수를 세며 마지막 개행은 빈 줄로 더하지 않는다.
`scripts/quality.sh check`, `scripts/check-local.sh`, CI의 `quality` 단계에서 검사한다.

상한과 검사 범위, 제외하는 시험 경로와 생성물은 `scripts/check-file-length.mjs` 가 갖는다.
데이터 표는 `scripts/file-length-baseline.json`의 `exclusions`에 경로와 까닭을 명시한다.

기존 긴 파일은 같은 파일의 `files`에 현재 줄 수를 기준값으로 둔다.
기준 목록에 없는 파일이 상한을 넘거나, 목록에 든 파일이 기준값보다 커지면 실패한다.
줄어들거나 삭제된 파일은 통과시키고 기준값을 낮추라는 안내만 낸다.
병렬로 파일을 나누는 PR들이 기준 파일을 동시에 고쳐 충돌하지 않도록 갱신은 강제하지 않는다.

```bash
# cwd: 저장소 root
node scripts/check-file-length.mjs
node scripts/check-file-length.mjs --update
```

`--update`는 기준값을 실제 줄 수로 낮춘다. 상한 이하가 됐거나 삭제된 파일은 목록에서 뺀다.
새 항목을 추가하거나 기준값을 올리지 않으며, 위반이 있으면 기준 파일을 바꾸지 않는다.
갱신한 기준 파일은 같은 커밋에 넣는다. 새 코드는 기준 목록에 추가하지 않고 나눈다.
Checkstyle의 `FileLength`와 ESLint의 `max-lines`는 이 검사로 대체한다.
메서드와 함수 길이 경고는 그대로 유지한다.

## 실행 공간 파일

사용자가 「파일 공간」 화면에서 자기 실행 공간 `/workspace` 를 보는 길이다.
결정은 [ADR-20261009 / workspace-explorer](../../docs/adr/ADR-20261009-workspace-explorer.md), 실행 공간의 모양은 [`hermes/docs/hermes-contract.md`](../../hermes/docs/hermes-contract.md) 의 「실행 공간」 이 갖는다.
코드는 `backend` 의 최상위 패키지 `workspace` 에 둔다. 다른 패키지는 이 패키지를 쓰지 않는다.

### 설정

| 키 | 환경 변수 | 비었을 때 |
| --- | --- | --- |
| `assistant.sandbox-workspace.root` | `ASSISTANT_SANDBOX_WORKSPACE_ROOT` | 기동한다. 상태 조회와 관리자 용량은 `available: false` 이고, 그 밖의 경로가 503 `WORKSPACE_UNAVAILABLE` 이다 |
| `assistant.sandbox-workspace.delete-socket` | `ASSISTANT_SANDBOX_WORKSPACE_DELETE_SOCKET` | 기동한다. 지우기가 `WORKSPACE_DELETE_UNAVAILABLE` 이고 상태 조회는 `deletable: false` 다 |

`root` 는 실행 공간 정책의 `workspace_root` 와 같은 디렉터리를 Control Plane 에서 본 경로다. 읽기 전용으로 붙인다.
붙이는 일은 `fos-home-infra` 가 한다. 루트가 링크가 아닌 디렉터리가 아니어도 `WORKSPACE_UNAVAILABLE` 이다.

### 경로 규칙

요청자의 디렉터리는 `<root>/u<사용자 번호>` 하나다. 요청은 주인을 정하지 못한다.
요청의 `path` 는 그 디렉터리 안의 상대 경로이고 `/` 로 조각을 나눈다. 빈 값은 그 디렉터리 자체다.

| 거절하는 것 | 응답 |
| --- | --- |
| 빈 조각, `.`, `..`, NUL, 제어 문자, 맨 앞의 `/` | 400 `VALIDATION_FAILED` |
| 전체 4,096 바이트, 조각 하나 255 바이트, 조각 64개를 넘는다 | 400 `VALIDATION_FAILED` |
| 중간 조각이 디렉터리가 아니거나 심볼릭 링크다 | 404 `WORKSPACE_ENTRY_NOT_FOUND` |
| 없는 경로 | 404 `WORKSPACE_ENTRY_NOT_FOUND` |

중간 조각은 링크를 따라가지 않고 연다. glibc 위의 JDK 는 `SecureDirectoryStream` 으로 조각마다 디렉터리 핸들을 열어, 판정한 뒤 다른 것으로 바뀐 경로를 따라가지 않는다.
그 기능이 없으면 대체 경로를 탄다. 운영 이미지(`eclipse-temurin:21-jre-alpine`)는 musl 이라 이쪽이고, 개발 기계(macOS)도 그렇다. 대체 경로는 경고를 한 번 남긴다.
대체 경로는 조각마다 링크인지 본 뒤 경로로 열고, 연 뒤에 다시 본다. 중간 조각이 모두 링크가 아닌 디렉터리인지, 실제 경로가 사용자 디렉터리 아래인지, 마지막 조각을 열기 전에 본 파일과 연 뒤 다시 본 파일이 같은지 확인하고 어긋나면 404 다.
하드 링크 수와 읽기 권한은 두 경로 모두 경로로 다시 본다. 본문은 위 방법으로 연 것만 준다.
하드 링크 수(`unix:nlink`)를 읽지 못하는 파일 시스템이면 1 로 본다.
이 사후 확인은 확인과 열기 사이의 틈을 줄일 뿐 없애지 못한다. glibc 이미지로 옮겨 대체 경로 없이 꺼지게(fail-closed) 하는 일은 이슈 #359 가 갖는다([ADR-20261009 / workspace-explorer](../../docs/adr/ADR-20261009-workspace-explorer.md) 의 「결과」).

목록의 한 줄은 아래 종류 가운데 하나다.

| `kind` | 무엇 | 본문 |
| --- | --- | --- |
| `DIRECTORY` | 디렉터리 | 목록으로 연다 |
| `FILE` | 일반 파일 | 하드 링크가 하나이고 Control Plane 이 읽을 수 있을 때만 준다 |
| `LINK` | 심볼릭 링크. 가리키는 곳을 읽지 않는다 | 주지 않는다 |
| `OTHER` | FIFO, 소켓, 장치 | 주지 않는다 |

`readable` 이 거짓이면 화면은 「읽을 수 없음」 을 보인다. 권한이 없는 파일, 하드 링크가 둘 이상인 파일, `LINK`, `OTHER` 가 그렇다.
`openable` 은 이름이 주소 조각으로 쓸 수 있는지다. `%`, `;`, `\` 가 든 이름은 Control Plane 의 요청 방화벽이 주소에서 거절하므로 미리보기와 내려받기를 열지 않는다. 목록과 지우기는 `path` 인자로 하므로 그런 이름의 디렉터리도 열고 지운다.
그런 디렉터리 안의 파일은 자기 이름이 괜찮아도 주소가 그 디렉터리 이름을 지나므로, 화면이 미리보기와 내려받기를 열지 않는다.

### API

모든 경로는 웹 토큰의 사용자로 판정한다. 웹 서버 라우트는 같은 경로를 `/api/workspace/...` 로 옮긴다.

| 경로 | 하는 일 | 응답 |
| --- | --- | --- |
| `GET /api/v1/workspace` | 공간의 상태 | `{available, deletable, exists, runningExecutions, agents: [{code, name, shared}]}`. `exists` 는 사용자 디렉터리가 있는지다. `agents` 는 요청자가 주인이고 켜져 있으며 지우지 않은 에이전트이고, `shared` 는 그룹에 공개했는지다. `runningExecutions` 는 사용자 실행 한도가 세는 지금 쥔 자리 수다 |
| `GET /api/v1/workspace/entries?path=` | 디렉터리 하나의 목록 | `{path, entries: [{name, kind, size, modifiedAt, readable, openable}], truncated}`. 디렉터리를 읽는 순서로 1,001개까지 읽고, 그 가운데 1,000개를 디렉터리 먼저, 이름 순서로 준다. 1,001번째가 있으면 `truncated` 가 참이고, 그때는 순서상 앞선 항목도 빠질 수 있다. `size` 는 `FILE` 만 채운다. 사용자 디렉터리가 아직 없으면 빈 목록이다 |
| `GET /api/v1/workspace/files/{경로}` | 미리보기 본문 | 아래 「본문 머리글」. 경로의 조각마다 URL 인코딩한다 |
| `GET /api/v1/workspace/files/{경로}?download=1` | 내려받기 | 크기 상한 없이 스트림으로 준다 |
| `DELETE /api/v1/workspace/entries?path=` | 지우기 | 아래 「지우기」 |
| `GET /api/v1/admin/workspaces` | 실행 공간별 용량(사용자, 주인 없는 에이전트). `ADMIN` 만 | 아래 「관리자 용량」 |

본문 경로의 오류는 아래와 같다. 루트 확인(503), 경로 검사(400) 다음에, 미리보기는 확장자(415), 크기(413), 종류(404), 읽기 권한(403) 차례로 판정한다. 내려받기는 종류와 읽기 권한만 본다.

| 판정 | 응답 |
| --- | --- |
| `FILE` 이 아니다 | 404 `WORKSPACE_ENTRY_NOT_FOUND` |
| 읽을 수 없다(권한, 하드 링크) | 403 `WORKSPACE_ENTRY_UNREADABLE` |
| 미리보기를 정하지 않은 확장자 | 415 `WORKSPACE_PREVIEW_UNSUPPORTED` |
| 미리보기 크기를 넘는다 | 413 `WORKSPACE_PREVIEW_TOO_LARGE` |

목록에서 Control Plane 이 읽지 못하는 디렉터리는 403 `WORKSPACE_ENTRY_UNREADABLE` 이다. 예상하지 못한 입출력 오류는 500 `INTERNAL_ERROR` 이고 로그에는 사용자 번호와 예외 종류만 남는다.
응답 본문은 파일을 연 시점의 크기까지만 보낸다. 그 사이 파일이 커져도 `Content-Length` 와 본문이 어긋나지 않는다.

### 본문 머리글

미리보기는 확장자로 형식을 정한다. 파일의 내용으로 형식을 짐작하지 않는다.

| 확장자 | `Content-Type` | 크기 상한 |
| --- | --- | --- |
| `html`, `htm` | `text/html; charset=utf-8` | 5 MiB |
| `png`, `jpg`, `jpeg`, `gif`, `webp` | 그 사진 형식 | 20 MiB |
| `css` | `text/css; charset=utf-8`. HTML 미리보기가 상대 경로로 부르는 스타일이 적용되게 한다 | 1 MiB |
| `csv`, `tsv` 와 아래 글 확장자, 확장자가 없는 이름 | `text/plain; charset=utf-8` | 1 MiB |

글 확장자는 `txt`, `md`, `markdown`, `log`, `json`, `jsonl`, `yaml`, `yml`, `toml`, `ini`, `cfg`, `conf`, `env`, `py`, `js`, `mjs`, `cjs`, `ts`, `tsx`, `jsx`, `java`, `kt`, `go`, `rs`, `rb`, `sh`, `bash`, `zsh`, `sql`, `xml`, `svg`, `scss` 다.
SVG 는 스크립트를 품을 수 있어 사진이 아니라 글로 보인다.
확장자는 이름의 마지막 `.` 뒤를 소문자로 읽는다. `.` 으로 시작하고 다른 `.` 이 없는 이름은 그 뒤 전체가 확장자다. `.env` 는 `env` 라 글이고, `.gitignore` 는 `gitignore` 라 미리보기가 없다. `.` 이 없는 이름(`Makefile`)은 확장자가 없는 이름이다.

| 머리글 | 미리보기 | 내려받기 |
| --- | --- | --- |
| `Content-Type` | 위 표 | `application/octet-stream` |
| `Content-Disposition` | `inline; filename="<ASCII 로 옮긴 이름>"; filename*=UTF-8''<이름>` | `attachment; filename="<ASCII 로 옮긴 이름>"; filename*=UTF-8''<이름>` |
| `Content-Security-Policy` | HTML 은 결과물과 같은 값([`backend/docs/flow.md`](flow.md) 의 「경로(결과물 파일)」). 그 밖은 `sandbox; default-src 'none'` | `sandbox; default-src 'none'` |
| `X-Content-Type-Options` | `nosniff` | `nosniff` |
| `Cache-Control` | `private, no-store` | `private, no-store` |

`Content-Disposition` 은 Spring 의 `ContentDisposition` 에 이름과 UTF-8 을 주어 만든다. ASCII 로 옮긴 이름과 `"`, `\` 의 escape 는 Spring 이 정한다.
web 서버 라우트는 이 다섯 머리글과 `Content-Length` 만 옮긴다.
HTML 이 상대 경로로 부르는 CSS 와 사진은 같은 `files/` 아래 주소라 주인 확인 뒤에 받는다.

### 로그와 기록

본문은 로그와 실행 기록에 남기지 않는다. 도우미를 부른 지우기마다 사용자 번호, 상대 경로, 종류, 지운 항목 수, 결과를 `INFO` 로그 한 줄로 남긴다. 실패하면 일부가 지워졌을 수 있어 지운 항목 수를 `-` 로 적는다. 경로의 제어 문자는 `\uXXXX` 로 바꿔 로그 줄을 끊지 못하게 한다. 도우미를 부르기 전에 끝난 요청은 남기지 않는다.
목록과 본문의 오류 로그는 사용자 번호와 오류 종류만 남기고 경로를 남기지 않는다.

### 지우기

**지우기는 경로 하나를 받는다.** 빈 경로(사용자 디렉터리 자체)는 400 `VALIDATION_FAILED` 다.
Control Plane 은 경로 규칙을 먼저 검사하고 읽기 마운트에서 그 경로가 있는지 본 뒤 운영의 권한 도우미를 부른다.

| 판정 | 응답 |
| --- | --- |
| 지운다 | 200 `{kind, entries, bytes}`. `entries` 는 지운 항목 수, `bytes` 는 지운 일반 파일의 크기 합이다 |
| 도우미 socket 이 설정되지 않았다 | 503 `WORKSPACE_DELETE_UNAVAILABLE` |
| 없는 경로, 링크를 지나는 경로 | 404 `WORKSPACE_ENTRY_NOT_FOUND` |
| 상위 디렉터리를 Control Plane 이 읽지 못한다 | 403 `WORKSPACE_ENTRY_UNREADABLE`. 도우미를 부르지 않는다 |
| 디렉터리 안의 항목이 10,000 개를 넘는다 | 409 `WORKSPACE_DELETE_TOO_MANY`. 아무것도 지우지 않는다 |
| 도우미가 실패했거나 30초 안에 답하지 않았다 | 502 `WORKSPACE_DELETE_FAILED` |

루트 확인(503 `WORKSPACE_UNAVAILABLE`)과 예상하지 못한 입출력 오류(500)는 위 「API」 절과 같다.

**권한 도우미와의 계약.** 도우미는 `fos-home-infra` 가 만들고 운영한다. 이 저장소는 아래 계약만 갖는다.

- socket 은 unix stream socket 이다. Control Plane 만 열 수 있게 둔다. 망에 열지 않는다
- 요청 하나에 연결 하나다. Control Plane 이 UTF-8 JSON 한 줄을 `\n` 으로 끝내 보내고, 도우미가 JSON 한 줄로 답한 뒤 연결을 닫는다
- 답 한 줄은 64 KiB 를 넘지 않는다. 넘거나 JSON 이 아니면 Control Plane 은 502 `WORKSPACE_DELETE_FAILED` 로 답한다
- 요청은 `{"version": 1, "owner": "u12", "path": "reports/a.csv", "max_entries": 10000}` 이다
- 성공 답은 `{"ok": true, "kind": "FILE", "entries": 1, "bytes": 2048}` 이다. `kind` 는 목록의 `kind` 와 같은 네 값이다
- 실패 답은 `{"ok": false, "code": "<코드>"}` 이다. 코드는 아래 표의 다섯이다

| 코드 | 뜻 | Control Plane 응답 |
| --- | --- | --- |
| `INVALID_REQUEST` | 주인 키나 경로가 규칙에 맞지 않는다 | 502 `WORKSPACE_DELETE_FAILED` |
| `NOT_FOUND` | 없는 경로 | 404 `WORKSPACE_ENTRY_NOT_FOUND` |
| `LINK_IN_PATH` | 중간 조각이 링크이거나 디렉터리가 아니다 | 404 `WORKSPACE_ENTRY_NOT_FOUND` |
| `TOO_MANY_ENTRIES` | 디렉터리 안의 항목이 `max_entries` 를 넘는다. 아무것도 지우지 않았다 | 409 `WORKSPACE_DELETE_TOO_MANY` |
| `FAILED` | 지우다 실패했다. 일부가 지워졌을 수 있다 | 502 `WORKSPACE_DELETE_FAILED` |

도우미가 지킬 것은 아래와 같다.

- 주인 키는 `^[a-z][a-z0-9-]{0,63}$` 이고, 지우는 범위는 `<workspace_root>/<주인 키>` 아래뿐이다. 주인 디렉터리 자체는 지우지 않는다
- 경로 규칙은 위 「경로 규칙」 과 같다
- 주인 디렉터리부터 조각마다 링크를 따라가지 않고 디렉터리 핸들로 연다. 마지막 조각이 링크면 링크만 지운다
- 디렉터리는 먼저 안의 항목을 링크를 따라가지 않고 센다. `max_entries` 를 넘으면 지우지 않고 답한다. 넘지 않으면 안쪽부터 지운다
- 지우는 크기에는 상한을 두지 않는다. 지우는 비용은 항목 수를 따르고 파일 크기를 따르지 않는다
- 요청마다 시각, 주인 키, 경로, 종류, 지운 항목 수, 결과를 감사 기록 한 줄로 남긴다. 파일 본문은 읽지도 남기지도 않는다
- 실행 공간 루트만 쓰기로 붙이고, 지우는 데 필요한 권한만 갖는다

### 관리자 용량

`GET /api/v1/admin/workspaces` 는 `{available, spaces: [{kind, id, name, bytes, entries, partial}]}` 를 준다.
`kind` 는 `USER`(디렉터리 `u<번호>`) 나 `AGENT`(디렉터리 `a<번호>`) 이고 `name` 은 사용자 이름이나 에이전트 이름이다. 이름을 찾지 못하면 `null` 이다.
번호는 0 으로 시작하지 않는다(`u01` 은 세지 않는다). 그 밖의 이름을 가진 디렉터리는 세지 않는다. 파일 이름과 경로는 응답에 없다.

요청할 때 링크를 따라가지 않고 센다. `bytes` 는 일반 파일 크기의 합이다.
공간 하나에 항목 200,000 개, 요청 전체에 30초를 넘기면 거기서 멈추고 `partial` 을 참으로 둔다. 시간은 항목 사이에서 확인하므로 디렉터리 하나를 여는 데 걸린 시간만큼은 넘길 수 있다. 읽지 못한 디렉터리는 항목으로 세고 `partial` 을 참으로 둔다.
공간은 `USER`, `AGENT` 순서와 번호 순서로 센다. 30초가 지난 뒤의 공간은 세지 않고 `bytes` 와 `entries` 를 0, `partial` 을 참으로 두어 응답에 넣는다.
줄은 `bytes` 가 큰 순서다.

루트가 설정되지 않았거나 링크가 아닌 디렉터리가 아니면 200 과 `available` 거짓, 빈 `spaces` 다. 루트 바로 아래를 읽지 못하면 500 `INTERNAL_ERROR` 이고 로그에는 예외 종류만 남는다.
