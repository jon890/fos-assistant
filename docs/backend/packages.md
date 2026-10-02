# backend 패키지

## backend 패키지

`connector`는 커넥터 카탈로그와 사용자별 연결의 등록, 확인, 해제와 비밀값을 제외한 상태를 소유한다.
특정 서비스의 이름, 주소, env 이름, 토큰 형식을 코드에 두지 않는다. 모두 대시보드 plugin 이 내는 manifest 에서 온다([ADR-043](../adr/ADR-043-커넥터는-plugin-의-connector-json-으로-선언하고-control-plane-은-범용-흐름만-갖는다.md)).
카탈로그, 도구 호출, 설치, env, MCP probe 는 `hermes`의 `HermesConnectorClient` 가 HTTP로 호출한다.
`connector.application` 의 `ConnectorCallLimiter` 가 선택지 조회, 등록, 연결 확인을 사용자별로 제한한다. 한도는 `ConnectorProperties`(`assistant.connector`)가 갖고 상태는 JVM 메모리에 둔다. Control Plane 이 한 대라는 전제다.
커넥터 에이전트의 실행에는 Memory 문맥을 주지 않는다. `ChatService` 와 `AgentRunner` 가 `Agent.connectorManaged()` 를 보고 빈 문맥으로 돌린다. `AgentMemoryCollectionService` 도 커넥터 에이전트에 받는 collection 을 주지 않는다. `McpCallerResolver` 는 origin 실행의 에이전트가 커넥터 에이전트이면 Control Plane MCP 호출을 거절한다([ADR-045](../adr/ADR-045-커넥터-에이전트는-자기-mcp-서버만-받고-memory-와-control-plane-도구를-받지-않는다.md)).
웹은 `components/connector`와 `app/connections`, `app/connections/[id]`, 대응 서버 라우트가 맡는다. 입력 칸은 manifest 의 `fields` 로 그린다.
`test/unit/connector-neutral.test.ts` 가 `backend/src/main` 과 `web/src` 와 `hermes/plugins` 에 특정 서비스 이름이 들어오지 않았는지 본다.
예외는 셋이다. 옛 표를 만든 V36 과 그 행을 옮기는 V38 은 이관 기록이라 이름을 갖는다. `web/src/app/connections/accountbook/page.tsx` 는 전용 화면이 있던 옛 주소를 새 연결 화면으로 넘기려고 커넥터 번호를 갖는다. 이 페이지는 옛 주소로 들어오는 사용자가 없어지면 지운다.
계약은 [커넥터 연결](../connectors.md)에 있다.

`connector` 는 커넥터 도구 호출의 판정과 그 기록도 소유한다([ADR-049](../adr/ADR-049-커넥터-도구-호출은-profile-plugin-의-hook-이-control-plane-에-물어-판정한다.md)).

| 클래스 | 하는 일 |
| --- | --- |
| `connector.presentation.ConnectorPolicyController` | `POST /internal/hermes/connector-policy`. profile 토큰으로 인증한 요청을 받아 서명을 확인하고 판정을 돌려준다 |
| `connector.application.ConnectorPolicyRequest` | 요청 본문과 서명 검증. `_fos_ctx` 와 같은 key 를 쓰고 HMAC 은 `mcp.application.McpCallContext` 의 것을 부른다 |
| `connector.application.ConnectorPolicyService` | 실행과 연결과 카탈로그를 찾아 판정하고 `connector_action` 한 줄을 남긴다 |
| `connector.application.ConnectorCatalogCache` | 판정 경로가 쓰는 카탈로그를 60초 동안 메모리에 둔다. 화면 경로는 쓰지 않는다 |
| `connector.domain.ToolPolicyDecision` | 판정 함수. Hermes 와 DB 를 모른다 |
| `connector.domain.ConnectorAction` | 판정 한 줄과 승인 줄. 승인 상태 전이를 갖는다 |

**다른 패키지는 `connector` 를 import 하지 않는다.** `connector` 가 `agent`, `hermes`, `mcp`, `orchestration`, `usage`, `user` 를 부른다. 승인 줄의 대화 권한을 확인하려고 `chat` 도 부른다. `agent` 가 `people` 을 거쳐 `mcp` 를 쓰므로 `mcp` 가 `connector` 를 부르면 순환이 된다. `chat` 이 승인 결과를 읽어야 할 때는 `chat` 에 port 를 두고 `connector` 가 구현한다.
검사: `ArchitectureRules.TOP_LEVEL_PACKAGES_FREE_OF_CYCLES`

도메인별로 나누고 각 도메인 안은 `presentation` 에서 `application` 을 거쳐 `infra` 와 `domain` 으로 흐른다.
`presentation` 은 `infra` 를 바로 쓰지 않는다.
검사: `ArchitectureRules.LAYER_DIRECTION`

| 패키지 | 책임 |
| --- | --- |
| `shared/auth` | 토큰 검사와 현재 사용자 |
| `shared/error` | 오류 코드와 응답 형태 |
| `user` | 사용자과 첫 로그인 처리 |
| `agent` | 에이전트 등록과 사용자의 만들기·지우기, 공개 범위, Hermes profile 연결, 페르소나, 도구, 추천 질문 생성, 에이전트가 받는 Memory collection |
| `hermes` | Runs API 호출과 profile key 조회, 대시보드 호출 |
| `chat` | 대화, 메시지, 한 번의 실행 흐름, 대화의 모델 선택 |
| `usage` | 실행 기록, 실행 사건, 비용 환산, 사용량 조회 |
| `memory` | 개인과 그룹 공용 Memory, 제안과 승인, 판 기록, 그룹의 collection 목록, 에이전트의 실행에 보이는 항목 판정 |
| `context` | 실행에 넣을 `instructions` 조립 |
| `mcp` | Memory 본문 조회, 결과물 쓰기 도구의 인자 검사, 장기 토큰 인증과 profile 묶기, 요청자 판정 |
| `people` | 로그인 허용 목록과 사람을 더하는 흐름 |
| `orchestration` | 흐름과 자식 실행, MCP `agent_*` 위임의 시작과 조회와 중지, 하위 에이전트 session 등록 |
| `skill` | 올린 스킬의 읽기와 쓰기, 버전 디렉터리, Hermes 에 게시, 스킬 목록과 호출 이력 조회 |

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

## 한 번의 대화가 지나는 길

1. `ChatController` 가 현재 사용자를 확인한다.
2. `ChatService` 가 요청한 에이전트를 사용자가 쓸 수 있는지 확인하고 `conversation` 에 기록한다.
   이어지는 대화는 요청의 `agentCode` 를 무시하고 처음 기록한 에이전트를 쓴다.
3. `ContextAssembler` 가 이 실행에 넣을 `instructions` 를 조립한다.
   요청자가 볼 수 있고 그 에이전트가 받는 collection 의 Memory만 고르고, Memory 예산 밖에서 공통 표 지침을 추가한다.
4. `ExecutionRecorder` 가 `RUNNING` 상태로 실행 한 줄을 먼저 만든다.
   조립한 글자 수를 `context_chars` 에, 대화에서 고른 effort 를 `reasoning_effort` 에 적는다.
5. `HermesProfileKeyStore` 가 그 profile 이름의 key 파일을 읽는다. 없으면 거기서 끝난다.
6. `HttpHermesRunsClient` 가 실행을 제출한다. 대화에서 고른 모델과 effort 가 있으면 싣고, 없으면 에이전트 기본 모델을 싣는다. 그것도 없으면 빼서 profile 의 값으로 돌게 한다. 받은 `run_id` 를 그 자리에서 실행 줄에 적는다.
7. 스트림으로 오는 사건을 화면으로 중계하면서 `execution_event` 로도 옮겨 적는다.
8. 실행이 끝나면 `CostEstimator` 가 토큰을 models.dev 가격표로 환산한다.
9. `ExecutionRecorder` 가 4번에서 만든 줄을 `SUCCEEDED` 로 갱신한다.
   토큰, 소요 시간, 환산 금액이 이때 채워진다.
10. 실패해도 그 줄은 남는다. `FAILED` 로 갱신되고 금액만 비어 있다.

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
