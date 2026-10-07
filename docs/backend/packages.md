# backend 패키지

Control Plane 의 패키지마다 맡는 책임과 패키지 사이의 방향 규칙을 갖는다.
한 번의 대화가 지나는 길과 가격표를 읽는 방법도 이 파일이 갖는다.
방향 규칙은 `ArchitectureRules.java` 가 강제하고, 이 파일은 규칙마다 그 검사의 이름을 적는다.

## 패키지와 책임

도메인별로 나누고 각 도메인 안은 `presentation` 에서 `application` 을 거쳐 `infra` 와 `domain` 으로 흐른다.
`presentation` 은 `infra` 를 바로 쓰지 않는다.
검사: `ArchitectureRules.LAYER_DIRECTION`

| 패키지 | 책임 |
| --- | --- |
| `shared/auth` | 토큰 검사와 현재 사용자, profile 토큰 필터의 타입, 토큰의 주소를 현재 사용자로 바꾸는 port |
| `shared/error` | 오류 코드와 응답 형태 |
| `shared/config` | 시계, 스케줄링, 보안 필터 설정, 실행 중에 쓰는 설정을 읽는 `LiveProperties`([ADR-20261007 / live-properties](../adr/ADR-20261007-live-properties.md)). 그 빈을 만드는 `LivePropertiesConfig` 는 기능 패키지의 설정을 가져오므로 루트 패키지에 둔다 |
| `shared/util` | 외부 서비스의 글을 감싸는 함수와 문자열 지문 |
| `shared/concurrent` | 요청 밖 작업을 띄우는 `BackgroundTasks`. 직접 가상 스레드를 띄우지 않는 까닭은 [ADR-20261007 / background-tasks](../adr/ADR-20261007-background-tasks.md) |
| `shared/domain/type` | 모든 패키지가 권한 판정에 읽는 역할 값 |
| `user` | 사용자와 첫 로그인 처리 |
| `browser` | 사용자마다 하나씩 두는 브라우저의 상태와 전이, 브라우저 proxy 로 컨테이너 켜기와 끄기, 자동 중지와 상태 맞추기([`user-browser.md`](user-browser.md)) |
| `model` | 모델 선택을 담는 값과 모델 단계 값. 서비스는 아직 `chat` 에 있다 |
| `agent` | 에이전트 등록과 사용자의 만들기·지우기, 공개 범위, Hermes profile 연결, 페르소나, 도구, 에이전트가 받는 Memory collection |
| `hermes` | Runs API 호출과 profile key 조회, 대시보드 호출 |
| `chat` | 대화, 메시지, 한 번의 실행 흐름, 대화의 모델 선택, 추천 질문 생성, 흐름의 계약과 등록 |
| `usage` | 실행 기록, 실행 사건, 비용 환산, 사용량 조회, 사용자 실행 한도([`execution-limit.md`](execution-limit.md)) |
| `feedback` | 제안에 대한 사용자 반응과 실행 결과의 사건 저장, 대화 삭제와 보관 기간의 정리, 반응 읽기 규칙([`decision-feedback.md`](decision-feedback.md)). 위 패키지가 부르기만 하고 이 패키지는 그 패키지를 모른다 |
| `memory` | 개인과 그룹 공용 Memory, 제안과 승인, 판 기록, 그룹의 collection 목록, 에이전트의 실행에 보이는 항목 판정, 문서 쓰기와 고치기, 서비스 토큰, 다른 서비스의 문서 읽기, 기존 개인 지식의 들이기 |
| `context` | 실행에 넣을 `instructions` 조립과 문맥 묶음의 항목 모델 |
| `mcp` | Memory 본문 조회, 결과물 쓰기와 할 일 제안 도구의 인자 검사, 장기 토큰 인증과 profile 묶기, 요청자 판정 |
| `people` | 로그인 허용 목록과 사람을 더하는 흐름, 첫 로그인에 그 사람의 에이전트 만들기 |
| `orchestration` | 흐름의 구현과 자식 실행, MCP `agent_*` 위임의 시작과 조회와 중지, 하위 에이전트 session 등록 |
| `skill` | 올린 스킬의 읽기와 쓰기, 버전 디렉터리, Hermes 에 게시, 스킬 목록과 호출 이력 조회 |
| `connector` | 커넥터 카탈로그, 사용자별 연결, 에이전트에 연결을 붙이는 바인딩, 커넥터 도구 호출의 판정과 기록 |
| `task` | 예약 작업과 시각, 발화 기록, 발화기와 예약 turn 시작([`task.md`](task.md)) |
| `notification` | 사용자에게 대화 밖에서 알리는 줄의 저장과 읽음 표시, 사용자 단위 SSE, 오래된 줄 정리([`notification.md`](notification.md)) |
| `followup` | 할 일의 저장과 상태 전이, 사람이 쓰는 API, 에이전트의 제안 저장([`follow-up.md`](follow-up.md)) |
| `proactive` | 먼저 살펴보기의 시작 전 점검, 점검 대화의 살펴보기 turn, 상한, 결과 계약의 검사와 그리기, 문제 후보의 검사와 저장, 살펴보기 트리 판정([`proactive-check.md`](proactive-check.md)), 판단 피드백의 replay 읽기 모델 |
| `attention` | 먼저 알리기의 판정과 지금 화면이 읽는 카드. 다른 패키지의 기록을 읽기만 한다([`attention.md`](attention.md)) |

검사: `ArchitectureRules.SHARED_DOES_NOT_DEPEND_ON_DOMAINS`

**`mcp` 는 `orchestration` 을 부르고, `orchestration` 은 `mcp` 를 import 하지 않는다.**
위임 서비스는 `McpCaller` 를 받지 않고 요청자와 origin 실행을 따로 받는다.
두 패키지가 서로를 import 하면 한쪽을 바꿀 때 다른 쪽의 타입을 함께 바꿔야 하기 때문이다.
검사: `ArchitectureRules.ORCHESTRATION_DOES_NOT_DEPEND_ON_MCP`, `ArchitectureRules.TOP_LEVEL_PACKAGES_FREE_OF_CYCLES`

**경로 변수와 요청 인자의 형식이 틀리면 어느 경로든 400 `VALIDATION_FAILED` 다.**
`shared/error` 의 `GlobalExceptionHandler` 가 `MethodArgumentTypeMismatchException` 을 받는다.
숫자를 받는 자리에 `abc` 가 오거나 UUID 를 받는 자리에 번호가 와도 500 이 아니라 400 이다.
요청 본문의 형식 오류는 이 규칙에 걸리지 않고 Control Plane 에서 500 이다.
본문의 대화 식별자는 web 서버 라우트가 먼저 검사해 400 으로 막는다.

### 최상위 패키지의 층 순서

최상위 패키지는 아래 순서를 따른다. 자리 1 이 맨 아래이고 20 이 맨 위다.

| 자리 | 패키지 |
| --- | --- |
| 1 | `hermes` |
| 2 | `user` |
| 3 | `browser` |
| 4 | `notification` |
| 5 | `model` |
| 6 | `agent` |
| 7 | `skill` |
| 8 | `usage` |
| 9 | `feedback` |
| 10 | `memory` |
| 11 | `context` |
| 12 | `chat` |
| 13 | `followup` |
| 14 | `proactive` |
| 15 | `orchestration` |
| 16 | `mcp` |
| 17 | `people` |
| 18 | `connector` |
| 19 | `task` |
| 20 | `attention` |

위 패키지는 아래 패키지를 쓰고 아래 패키지는 위 패키지를 import 하지 않는다.
거꾸로 써야 하면 아래 패키지에 port 를 두고 위 패키지가 구현한다.
새 최상위 패키지를 만들면 이 순서의 자리를 정하고 `TopLevelPackageOrder.ORDER` 에 넣는다.
`shared` 는 순서 밖이고 어느 패키지도 쓰지 않는다.
`task` 는 `attention` 바로 아래다. 예약 turn 을 열려고 `chat` 을, 에이전트와 주인을 다시 확인하려고 `agent` 와 `user` 를, 결과를 알리려고 `notification` 을 쓴다. 대화 목록이 작업 이름을 보이려고 `chat` 에 port(`ConversationTaskLabels`)를 두고 `task` 가 구현한다.
`followup` 은 `chat` 바로 위다. 대화 주인을 확인하고 공개 식별자를 얻으려고 `chat` 을 쓰고, 제안 도구(`mcp`)와 먼저 알리기(`attention`)가 `followup` 을 쓴다.
`browser` 는 `user` 바로 위다. 관리자 목록의 사용자 이름을 읽으려고 `user` 를 쓰고, 사용자를 끈 사건(`shared.auth.UserAccessRevoked`)을 받는다. 커넥터 바인딩이 브라우저 중계를 쓰게 되므로 `connector` 보다 아래에 둔다.
`notification` 은 `user` 바로 위다. 알림을 만드는 쪽(`connector`, 그 위의 패키지)이 모두 이 패키지를 부르고, 이 패키지는 알림을 받는 사용자 말고 다른 도메인을 모른다.
`attention` 은 맨 위다. 먼저 알리기의 후보를 읽으려고 `usage`, `chat`, `agent`, `memory`, `connector`, `followup` 의 `application` 을 부르고, 어느 패키지도 `attention` 을 import 하지 않는다.
검사: `ArchitectureRules.TOP_LEVEL_PACKAGES_FOLLOW_LAYER_ORDER`, 근거: ADR-068

### proactive

문제 후보의 가치 평가는 `ValueEvaluationService`와 `ValueEvaluator`가 맡는다.
`DecisionProvider`는 모델과 무관한 판단 port이며 첫 adapter는 도구 없는 시스템 profile에 Hermes Runs로 묻는다.
권한과 행동 정책을 결정하지 않는다([가치 평가](value-evaluation.md)).
행동 수준은 모델을 모르는 `AutonomyPolicy` 가 정하고 `AutonomyPolicyService` 가 판정을 남긴다. `EXECUTE` 는 `ProactiveCheckService.startAutonomous` 로 읽기 전용 살펴보기를 시작한다([행동 정책](autonomy-policy.md)).

`proactive` 는 `followup` 바로 위다. 살펴보기 turn 은 `ChatService.runProactiveCheck` 가 돌리고, 살펴보기만의 일은 `chat` 이 가진 port `CheckTurn` 을 `proactive` 가 구현해 넘긴다.
`chat` 은 `proactive` 를 import 하지 않는다. 기동 정리가 끝낸 살펴보기 turn 의 답을 대화에 남기지 않도록, `chat` 의 port `RecoveredAnswerGuard` 도 `proactive` 가 구현한다. 사용자가 점검 대화를 읽으면 그 대화의 보고를 연 것으로 적도록 `chat` 의 port `CheckReportReads` 도 `proactive` 가 구현한다.
`orchestration`, `mcp`, `connector` 는 `proactive` 보다 위라 `ProactiveCheckGuard` 로 살펴보기 트리인지 묻는다.
살펴보기가 끝나 도는 위임 자식을 멈추는 일은 `proactive` 가 낸 `ProactiveCheckEnded` 사건을 `orchestration` 이 받아 한다.

### connector

`connector`는 커넥터 카탈로그와 사용자별 연결의 등록, 확인, 해제와 비밀값을 제외한 상태를 소유한다.
연결을 에이전트에 붙이고 떼는 바인딩(`agent_connector_binding`)과 관리자 반영 완료도 이 패키지가 갖는다. `ConnectorConnectionService` 가 연결을, `ConnectorBindingService` 가 바인딩을 맡는다([ADR-083](../adr/ADR-083-커넥터는-사용자가-한-번-연결하고-자기-에이전트에-여럿-붙여-그-에이전트가-도구를-직접-부른다.md)).
특정 서비스의 이름, 주소, env 이름, 토큰 형식을 코드에 두지 않는다. 모두 대시보드 plugin 이 내는 manifest 에서 온다([ADR-043](../adr/ADR-043-커넥터는-plugin-의-connector-json-으로-선언하고-control-plane-은-범용-흐름만-갖는다.md)).
카탈로그, 도구 호출, 설치, env, MCP probe 는 `hermes`의 `HermesConnectorClient` 가 HTTP로 호출한다.
`connector.application` 의 `ConnectorCallLimiter` 가 선택지 조회, 등록, 연결 확인을 사용자별로 제한한다. 한도는 `ConnectorProperties`(`assistant.connector`)가 갖고 상태는 JVM 메모리에 둔다. Control Plane 이 한 대라는 전제다.
`agent` 가 바인딩을 알아야 하는 자리는 `agent.application` 에 둔 port 둘로 부른다. `connector` 가 `agent` 보다 위라서다.

| port | 하는 일 | 구현 | 부르는 곳 |
| --- | --- | --- | --- |
| `AgentConnectorBindings` | `hasBindings`, `connectorServers`, `connectorToolPrefixes`. 저장한 바인딩만 읽고 대시보드를 부르지 않는다 | `ConnectorBindingLookup` | 공개 범위 변경과 관리자 수정(비공개 유지), 도구 저장과 스킬 게시(붙은 서버 이름을 함께 보낸다), 실행 기록의 도구 내용 가림, 먼저 살펴보기의 시작 전 점검과 지시 |
| `AgentConnectorDetacher` | `detachAll`. 그 에이전트의 바인딩을 모두 뗀다 | `ConnectorBindingService` | 에이전트 지우기 |

옛 커넥터 에이전트가 남아 있는 동안 그 실행에는 Memory 문맥을 주지 않는다. `ChatService` 와 `AgentRunner` 가 `Agent.connectorManaged()` 를 보고 빈 문맥으로 돌린다. `AgentMemoryCollectionService` 도 옛 커넥터 에이전트에 받는 collection 을 주지 않는다. `McpCallerResolver` 는 origin 실행의 에이전트가 옛 커넥터 에이전트이면 Control Plane MCP 호출을 거절한다([ADR-045](../adr/ADR-045-커넥터-에이전트는-자기-mcp-서버만-받고-memory-와-control-plane-도구를-받지-않는다.md)). 연결을 붙인 일반 에이전트에는 이 경계가 걸리지 않는다.
웹은 `components/connector`와 `app/connections`, `app/connections/[id]`, 대응 서버 라우트가 맡는다. 입력 칸은 manifest 의 `fields` 로 그린다.
`test/unit/connector-neutral.test.ts` 가 `backend/src/main` 과 `web/src` 와 `hermes/plugins` 에 특정 서비스 이름이 들어오지 않았는지 본다.
예외는 셋이다. 옛 표를 만든 V36 과 그 행을 옮기는 V38 은 이관 기록이라 이름을 갖는다. `web/src/app/connections/accountbook/page.tsx` 는 전용 화면이 있던 옛 주소를 새 연결 화면으로 넘기려고 커넥터 번호를 갖는다. 이 페이지는 옛 주소로 들어오는 사용자가 없어지면 지운다.
계약은 [커넥터 연결](../connectors.md)에 있다.

`connector` 는 커넥터 도구 호출의 판정과 그 기록도 소유한다([ADR-049](../adr/ADR-049-커넥터-도구-호출은-profile-plugin-의-hook-이-control-plane-에-물어-판정한다.md)).

| 클래스 | 하는 일 |
| --- | --- |
| `connector.presentation.ConnectorPolicyController` | `POST /internal/hermes/connector-policy`. profile 토큰으로 인증한 요청을 받아 서명을 확인하고 판정을 돌려준다 |
| `connector.application.ConnectorPolicyRequest` | 요청 본문과 서명 검증. `_fos_ctx` 와 같은 key 를 쓰고 HMAC 은 `mcp.application.McpCallContext` 의 것을 부른다 |
| `connector.application.ConnectorPolicyService` | 실행과, 그 실행의 에이전트에 붙은 바인딩과, 카탈로그를 찾아 판정하고 `connector_action` 한 줄을 남긴다. 승인 줄이면 같은 트랜잭션에서 `APPROVAL_REQUESTED` 알림도 남긴다 |
| `connector.application.ConnectorCatalogCache` | 판정 경로가 쓰는 카탈로그를 60초 동안 메모리에 둔다. 화면 경로는 쓰지 않는다 |
| `connector.domain.ToolPolicyDecision` | 판정 함수. Hermes 와 DB 를 모른다 |
| `connector.domain.ConnectorAction` | 판정 한 줄과 승인 줄. 승인 상태 전이를 갖는다 |

**`attention` 밖의 패키지는 `connector` 를 import 하지 않는다.** `connector` 가 `agent`, `hermes`, `mcp`, `orchestration`, `usage`, `user` 를 부른다. 붙일 때 스킬 이름이 겹치는지 보려고 `skill` 도 부른다. 승인 줄의 대화 권한을 확인하고 알림이 가리킬 대화의 공개 식별자를 찾으려고 `chat` 도 부른다. 승인 요청과 만료의 알림을 같은 트랜잭션에서 만들려고 `notification` 도 부른다. `connector` 가 `mcp` 를 쓰므로 `mcp` 가 `connector` 를 부르면 순환이 된다. `chat` 이 승인 결과를 읽어야 할 때는 `chat` 에 port 를 두고 `connector` 가 구현한다. `attention` 은 층 순서의 맨 위라 승인 대기를 읽으려고 `connector.application` 의 읽기 메서드를 부른다.
검사: `ArchitectureRules.TOP_LEVEL_PACKAGES_FREE_OF_CYCLES`

## 한 번의 대화가 지나는 길

1. `ChatController` 가 현재 사용자를 확인한다.
2. `ChatService` 가 요청한 에이전트를 사용자가 쓸 수 있는지 확인하고 `conversation` 에 기록한다.
   이어지는 대화는 요청의 `agentCode` 를 무시하고 처음 기록한 에이전트를 쓴다.
3. `ContextAssembler` 가 이 실행에 넣을 `instructions` 를 조립한다.
   요청자가 볼 수 있고 그 에이전트가 받는 collection 의 Memory만 고르고, Memory 예산 밖에서 공통 표 지침을 추가한다.
4. `ExecutionRecorder` 가 `RUNNING` 상태로 실행 한 줄을 먼저 만든다.
   조립한 글자 수를 `context_chars` 에, 대화에서 고른 effort 를 `reasoning_effort` 에 적는다.
5. `HermesProfileKeyStore` 가 그 profile 이름의 key 파일을 읽는다. 없으면 거기서 끝난다.
6. `HttpHermesRunsClient` 가 실행을 제출한다. 어느 모델과 effort 를 싣는지는 [모델 단계와 실행 기록](../model-tiers.md) 의 「모델 선택」 이 정한 순서를 따른다. 실을 모델이 없으면 빼서 profile 의 값으로 돌게 한다. 그룹에 숨김이 있으면 그 전에 profile 의 기본 모델을 숨김과 견준다. 받은 `run_id` 를 그 자리에서 실행 줄에 적는다.
7. 스트림으로 오는 사건을 화면으로 중계하면서 `execution_event` 로도 옮겨 적는다.
8. 실행이 끝나면 `CostEstimator` 가 토큰을 models.dev 가격표로 환산한다.
9. `ExecutionRecorder` 가 4번에서 만든 줄을 `SUCCEEDED` 로 갱신한다.
   토큰, 소요 시간, 환산 금액이 이때 채워진다.
10. 실패해도 그 줄은 남는다. 최종 실패 응답을 받으면 `FAILED` 와 오류 코드를 유지하면서
    응답의 토큰과 실제 provider/model 을 보존하고, 성공·취소와 같은 가격표 규칙으로 금액을 환산한다.
    제출 전 실패나 결과 미수신은 이미 적힌 값만 보존한다. 사용량이나 가격을 모르면 금액을 0 으로 채우지 않는다.

성공·취소·실패는 실행 상태이고 자원 소비 여부와 다르다. 월 합계는 `RUNNING` 을 빼고,
상태와 관계없이 환산된 금액을 더한다. 금액이 미확인인 종료 실행은 합계에서 빼고 별도로 센다.
Hermes `delegate_task` 가 만든 native 자식은 실행 줄이 없어 `subagent_usage_job` 줄의 금액을 같은 합계에 더하고,
금액을 확인하지 못한 자식 수를 따로 센다. 규칙은 [모델 단계와 실행 기록](../model-tiers.md) 의 「합계와 완전성」 이 갖는다.
구독 경로의 실제 청구액은 계속 비우고 API 경로만 같은 환산액을 실제 청구액으로 적는다.

`instructions` 를 조립하는 순서는 아래와 같다.

| 순서 | 담는 것 |
| --- | --- |
| 1 | 그룹 공용 Memory 중 `ACCEPTED` 이고 `retrieval` 이 `ALWAYS` 인 본문 |
| 2 | 요청자 개인 Memory 중 `ACCEPTED` 이고 `retrieval` 이 `ALWAYS` 인 본문 |
| 3 | `ACCEPTED` 이고 `retrieval` 이 `SEARCH` 인 항목의 제목과 번호 색인 |
| 4 | 묻는 형식 안내(`chat/application/AskFormat`). 사용자가 직접 답하는 대화 실행에만 붙는다 |
| 5 | 이 실행에만 필요한 문맥. 다시 생성이면 그 지시 |

1 부터 3 까지가 Memory 의 글자 상한 안에서 고르는 몫이고, 4 는 상한과 따로 붙는다.
묻는 형식은 `web/src/lib/ask.ts` 가 카드로 읽는다. 둘이 같은 형식을 말해야 한다.

1 부터 3 까지는 그 에이전트가 받는 collection 의 항목만 담는다. `SENSITIVE` 항목은 그 collection 에서 민감 항목을 허용받은 에이전트에만 담는다.
다른 사용자의 개인 Memory 와 받지 않는 collection 의 항목은 고르는 단계에서 빠진다.
문자열을 만든 뒤에 지우는 것이 아니라 애초에 넣지 않는다.

환산은 이 자리에서 한 번만 하고 쓴 가격표를 함께 적는다.
조회할 때 다시 계산하면 가격이 바뀔 때 지난달 합계가 따라 움직인다.
근거는 [`adr/ADR-004-구독제에서도-api-가격으로-환산해-보인다.md`](../adr/ADR-004-구독제에서도-api-가격으로-환산해-보인다.md) 에 있다.

## 가격표

`usage/infra/ModelsDevPriceCatalog` 가 models.dev 카탈로그를 읽어 메모리에 둔다.
경로는 `ASSISTANT_PRICING_CATALOG` 가 정한다.

**파일이 바뀌면 재기동 없이 다시 읽는다.** 조회할 때 1분에 한 번 파일의 수정 시각을 보고, 바뀌었으면 다시 읽는다.
기동할 때만 읽었을 때는 새로 받은 가격표에 있는 모델의 금액이 재기동 전까지 비어 있었다.
새 파일이 읽히지 않거나 가격이 하나도 없으면 이전 가격을 계속 쓴다.

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
