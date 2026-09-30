# 구조

## 경계

```
브라우저
  └ Next.js (web/)            세션은 여기까지만 산다
      └ 서버 라우트에서 짧은 수명의 토큰을 발급해 호출
          └ Spring Boot (backend/)   Control Plane
              └ Hermes API server    /p/<profile>/v1/runs
```

브라우저는 Control Plane 토큰을 갖지 않는다.
Next.js 서버 라우트가 세션에서 메일 주소를 꺼내 매 요청마다 토큰을 새로 만든다.
그래서 사용자가 요청 본문을 고쳐 남의 자료를 달라고 할 수 없다.

**위 그림은 요청이 나가는 쪽만 그린 것이다.**
Hermes 가 Control Plane 을 부르는 반대 방향도 있고 토큰이 서로 다르다.
그 두 방향은 [`flow.md`](flow.md) 의 「두 방향과 두 토큰」 절이 그림으로 갖는다.

## backend 패키지

도메인별로 나누고 각 도메인 안은 `presentation` 에서 `application`, `domain`, `infra` 로만 흐른다.

| 패키지 | 책임 |
| --- | --- |
| `shared/auth` | 토큰 검사와 현재 사용자 |
| `shared/error` | 오류 코드와 응답 형태 |
| `user` | 사용자과 첫 로그인 처리 |
| `agent` | 에이전트 등록과 사용자의 만들기·지우기, 공개 범위, Hermes profile 연결, 페르소나, 도구, 추천 질문 생성 |
| `hermes` | Runs API 호출과 profile key 조회, 대시보드 호출 |
| `chat` | 대화, 메시지, 한 번의 실행 흐름, 대화의 모델 선택 |
| `usage` | 실행 기록, 실행 사건, 비용 환산, 사용량 조회 |
| `memory` | 개인과 그룹 공용 Memory, 제안과 승인 |
| `context` | 실행에 넣을 `instructions` 조립 |
| `mcp` | Memory 본문 조회, 결과물 쓰기 도구의 인자 검사, 장기 토큰 인증과 profile 묶기, 요청자 판정 |
| `people` | 로그인 허용 목록과 사람을 더하는 흐름 |
| `skill` | 올린 스킬의 읽기와 쓰기, 버전 디렉터리, Hermes 에 게시, 스킬 목록과 호출 이력 조회 |

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
   요청자가 볼 수 있는 Memory 만 고른다.
4. `ExecutionRecorder` 가 `RUNNING` 상태로 실행 한 줄을 먼저 만든다.
   조립한 글자 수를 `context_chars` 에, 대화에서 고른 effort 를 `reasoning_effort` 에 적는다.
5. `HermesProfileKeyStore` 가 그 profile 이름의 key 파일을 읽는다. 없으면 거기서 끝난다.
6. `HttpHermesRunsClient` 가 실행을 제출한다. 대화에서 고른 모델과 effort 가 있으면 싣고, 없으면 빼서 profile 의 기본값으로 돌게 한다. 받은 `run_id` 를 그 자리에서 실행 줄에 적는다.
7. 스트림으로 오는 사건을 화면으로 중계하면서 `execution_event` 로도 옮겨 적는다.
8. 실행이 끝나면 `CostEstimator` 가 토큰을 models.dev 가격표로 환산한다.
9. `ExecutionRecorder` 가 4번에서 만든 줄을 `SUCCEEDED` 로 갱신한다.
   토큰, 소요 시간, 환산 금액이 이때 채워진다.
10. 실패해도 그 줄은 남는다. `FAILED` 로 갱신되고 금액만 비어 있다.

`instructions` 를 조립하는 순서는 아래와 같다.

| 순서 | 담는 것 |
| --- | --- |
| 1 | 그룹 공용 Memory 중 `ACCEPTED` 이고 항상 주입하는 본문 |
| 2 | 요청자 개인 Memory 중 `ACCEPTED` 이고 항상 주입하는 본문 |
| 3 | 나머지 접근 가능한 항목의 제목과 번호 색인 |
| 4 | 묻는 형식 안내(`chat/application/AskFormat`). 사용자가 직접 답하는 대화 실행에만 붙는다 |
| 5 | 이 실행에만 필요한 문맥. 다시 생성이면 그 지시 |

1 부터 3 까지가 Memory 의 글자 상한 안에서 고르는 몫이고, 4 는 상한과 따로 붙는다.
묻는 형식은 `web/src/lib/ask.ts` 가 카드로 읽는다. 둘이 같은 형식을 말해야 한다.

다른 사용자의 개인 Memory 는 고르는 단계에서 빠진다.
문자열을 만든 뒤에 지우는 것이 아니라 애초에 넣지 않는다.

환산은 이 자리에서 한 번만 하고 쓴 가격표를 함께 적는다.
조회할 때 다시 계산하면 가격이 바뀔 때 지난달 합계가 따라 움직인다.
근거는 [`adr/ADR-004-구독제에서도-api-가격으로-환산해-보인다.md`](adr/ADR-004-구독제에서도-api-가격으로-환산해-보인다.md) 에 있다.

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

## Memory

Memory 는 에이전트가 실행할 때 `instructions` 로 받는 사실이다.
단일 소스는 Control Plane 데이터베이스이고, Hermes 의 내장 memory 는 쓰지 않는다.

- 범위는 `USER` 와 `GROUP` 둘뿐이고 등록할 때 반드시 명시한다.
- 에이전트가 제안하면 `PROPOSED` 로 들어오고, 사람이 받아들여야 `ACCEPTED` 가 된다.
  주입되는 것은 `ACCEPTED` 뿐이다.
- `ContextAssembler` 가 요청자의 `USER` 항목과 요청자가 속한 그룹의 `GROUP` 항목만 골라 조립한다.
  다른 사용자의 개인 항목은 고르는 단계에서 빠진다.
- `always_inject` 가 참이면 본문을 싣고, 거짓이면 제목과 번호만 색인에 싣는다.
- 색인의 본문은 `memory_read` MCP 도구로 읽는다.
  요청자는 장기 토큰이 아니라 서명한 `_fos_ctx` 로 찾은 origin 실행의 사용자다(「MCP 요청자」). 요청 본문은 사용자를 바꾸지 못한다.
  Control Plane 은 접근할 수 없는 항목과 없는 항목을 같은 응답으로 숨긴다.
- 조립한 글자 수를 실행의 `context_chars` 에 남긴다.
  주입할 양이 실제로 문제가 되는 시점을 숫자로 판단하기 위해서다.
- 항목 하나가 남은 자리에 들어가지 않으면 그 항목만 빼고 다음 항목을 계속 담는다.
  넘친 항목을 잘라서 싣지는 않는다. 잘린 사실은 틀린 사실이 될 수 있다.
- 색인 층에 쓸 자리를 먼저 떼어 두고 항상 층을 담는다.
  몫은 `assistant.context.index-budget-ratio` 가 정하고 기본값은 4 다.
  색인이 빠지면 `memory_read` 로 읽을 번호도 사라져 에이전트가 나머지 Memory 에 닿을 길이 없어진다.
- 빠진 항목 수를 실행의 `context_omitted_items` 에 남기고, `/memory` 목록의 그 항목에 표시를 단다.
  대화 화면에는 끼우지 않는다.

근거는 [`adr/ADR-003-memory-권한은-주입으로-강제한다.md`](adr/ADR-003-memory-권한은-주입으로-강제한다.md) 와
[`adr/ADR-012-memory-는-사람이-승인한-것만-남는다.md`](adr/ADR-012-memory-는-사람이-승인한-것만-남는다.md) 에 있다.
층을 나누는 근거는
[`adr/ADR-015-memory-는-층을-나눠-싣는다.md`](adr/ADR-015-memory-는-층을-나눠-싣는다.md) 에 있다.

## 페르소나

에이전트의 성격이다. 본문은 그 profile 의 `SOUL.md` 가 갖고 이 저장소는 화면만 준다.
근거는 [`adr/ADR-019-페르소나는-hermes-가-갖고-control-plane-은-화면만-준다.md`](adr/ADR-019-페르소나는-hermes-가-갖고-control-plane-은-화면만-준다.md) 에 있다.

- **본문을 데이터베이스에 두지 않는다.** 읽을 때도 쓸 때도 Hermes 대시보드를 부른다.
- 정본이 하나라 어긋날 것이 없다. 반영 상태도 다시 반영하는 경로도 두지 않는다.
- 쓰기 직전에 한 번 더 읽어, 화면이 받아 간 본문과 같을 때만 쓴다.
  화면이 돌려보내는 것은 본문이 아니라 그 해시다. 8000자를 되보내지 않기 위해서다.
  다르면 그 사이 다른 사람이 고친 것이고 거절한다.
- **앞뒤 공백을 떼고 저장한다.** 떼고 나서 비면 거절한다. 빈 `SOUL.md` 를 쓰면 성격이 지워진다.
- 본문 상한은 8000자다. 매 실행의 고정 프롬프트에 들어가므로 길이가 곧 비용이다.

### 누가 고칠 수 있나

| 무엇 | 누구 |
| --- | --- |
| 읽기 | 그 에이전트를 쓸 수 있는 사람. 목록에 보이는 것과 같은 기준이다 |
| 쓰기 | 그 에이전트의 주인, 그리고 `ADMIN` |

**주인은 공개해도 주인이다.** 그룹에 공개한 에이전트도 만든 사람이 계속 고친다.
주인이 비어 있는 에이전트(이 규칙 전에 운영에서 등록한 그룹 공개 에이전트)는 `ADMIN` 만 고친다.
근거는 [ADR-033](adr/ADR-033-사용자가-에이전트를-만들고-공개해도-만든-사람이-관리한다.md) 에 있다.

판정은 `AgentService.isEditableBy` 하나다. 성격, 도구, 스킬, 공개 범위, 지우기가 모두 이것을 부른다.

**볼 수 없는 에이전트는 없는 에이전트와 같은 응답을 준다.**
`code` 를 훑어 남의 에이전트가 있는지 알아낼 수 없게 한다.

### 경로

| 경로 | 하는 일 |
| --- | --- |
| `GET /api/v1/agents/{code}/persona` | 지금 본문과 그 해시와 고칠 수 있는지와 본문 상한 |
| `PUT /api/v1/agents/{code}/persona` | 본문을 쓴다. 화면이 받아 간 본문의 해시를 함께 받는다 |

**해시를 함께 받는 이유는 두 사람이 같은 에이전트를 고칠 수 있기 때문이다.**
`SOUL.md` 에는 판 번호가 없어서 값으로 달라진 것을 알 수 없다.
쓰기 직전에 다시 읽어 그 해시와 다르면 거절하고, 화면이 새 본문을 다시 읽는다.

본문 대신 해시를 주고받는다. 상한이 8000자라 본문을 되보내면 그만큼이 요청에 실린다.

### 추천 질문

새 대화 화면에 보이는 추천 질문 넷까지다. 사람이 적지 않고 모델이 만든다.
근거는 [ADR-036](adr/ADR-036-추천-질문은-사용자의-대화-이력으로-모델이-만들고-메모리에만-둔다.md) 에 있다.

- **`(사용자, 에이전트)` 마다 다르다.** 그 사용자의 최근 대화 첫 질문들을 모델이 요약한다. 이력이 없으면 그 에이전트의 성격과 켜진 도구와 스킬 이름으로 할 수 있는 일을 만든다
- **backend 메모리에만 둔다.** 재시작하면 비고 다시 만든다
- **만드는 때는 둘이다.** 추천이 없을 때 새 대화 화면이 읽으면 만들기를 시작한다. 있으면 그 사용자가 그 에이전트와 대화를 마쳤을 때 만든 지 `assistant.starters.refresh-after`(기본 24시간)보다 오래됐으면 다시 만든다
- 같은 키의 만들기는 하나만 돈다. 실패하면 이전 추천을 그대로 둔다. 실패하면 `assistant.starters.retry-after-failure`(기본 10분) 동안 그 키를 다시 만들지 않는다
- 만들기는 그 에이전트의 profile 로 Hermes 실행 하나를 돌리고 실행 줄에 남긴다. turn 을 마치는 흐름을 기다리게 하지 않고 따로 돈다

| 경로 | 하는 일 |
| --- | --- |
| `GET /api/v1/agents/{code}/starters` | `{ "prompts": [...], "status": "READY" \| "GENERATING" \| "NONE" }`. `GENERATING` 이면 화면이 2초 간격으로 세 번까지 다시 읽는다 |

한 줄 소개와 사람이 적던 추천 질문, 그것을 쓰던 `PUT` 경로와 에이전트 목록 응답의 `tagline`, `starterPrompts` 는 없앴다.

### 어느 클래스가 무엇을 하나

| 무엇 | 어디 |
| --- | --- |
| 누가 고칠 수 있는지 판정하고 부르는 순서를 정한다 | `agent/application` |
| `GET` 과 `PUT /api/profiles/{이름}/soul` 호출 | `hermes` |

**`agent` 가 순서를 알고 `hermes` 는 부르는 방법만 안다.**
사람을 더할 때 `people` 과 `hermes` 를 나눈 것과 같은 규칙이다.

## 에이전트 도구

에이전트가 쓸 toolset 이다. 목록은 그 profile 설정의 `platform_toolsets.api_server` 가 갖고, 이 저장소는 등급 판정과 화면을 준다.
데이터베이스에 사본을 두지 않는다. 근거는 [`adr/ADR-029-에이전트-도구는-control-plane-이-등급으로-판정하고-hermes-설정-api-로-쓴다.md`](adr/ADR-029-에이전트-도구는-control-plane-이-등급으로-판정하고-hermes-설정-api-로-쓴다.md) 에 있다.

- 등급 표는 코드 한 곳(`agent/domain/AgentToolPolicy`)이 갖는다. 표와 이유는 ADR-029 의 「도구 등급」 이다
- 설정을 쓸 때 대시보드 plugin 은 허용 목록 밖의 이름을 거절한다. Hermes 를 올릴 때 `fos-home-infra` 의 기능 검사는 모든 profile 의 켜진 목록을 허용 목록과 대조한다. 실행마다 검사하지 않는다
- 쓸 때는 `platform_toolsets.api_server` 만 켤 toolset 과 Control Plane MCP `fos-assistant` 로 통째로 쓴다. `agent.disabled_toolsets` 는 모든 platform 에 적용되므로 보내지 않고 기존 값을 둔다
- 쓴 뒤 공유 listener 의 `GET /p/{profile}/v1/toolsets` 로 내장 도구를 다시 읽는다. 이 경로에는 MCP 서버 이름이 없으므로 Control Plane MCP 는 비교하지 않는다. 요청한 내장 도구가 빠지거나 분류된 도구가 예상과 다르면 적용 실패다. 추가로 켜진 미분류 도구는 적용 실패로 세지 않고 `unclassifiedEnabled` 로 알린다
- profile 의 공통 비활성화 목록이 막아 켜지지 않은 toolset 은 `AGENT_TOOLS_NOT_APPLIED` 응답의 `missingToolsets` 로 알린다. 화면은 `GET` 으로 현재 목록을 다시 읽는다
- 이름과 설명은 대시보드 `GET /api/tools/toolsets` 에서 읽는다. 그 응답의 `enabled` 는 CLI 기준이라 쓰지 않는다
- Hermes 가 쓰기 없이 미분류 toolset 을 켤 수 있다. 도구 응답의 `unclassifiedEnabled` 는 listener 에서 켜진 미분류 이름이고, 화면은 관리자에게 알리라는 경고를 보인다
- 셸·파일 계열(`terminal`, `file`, `code_execution`, `browser`, `computer_use`)이 켜진 에이전트는 `PRIVATE` 만 된다. `GROUP` 생성과 수정, 도구 변경 모두에서 최종 listener 주소의 현재 목록을 본다. 꺼진 에이전트의 공개 범위 변경은 검사하지 않고 켤 때 검사한다. 읽지 못하면 변경하지 않는다
- 도구 변경과 에이전트 접근 범위 변경은 같은 에이전트 행의 쓰기 잠금을 잡고 검사한다. 이미 잠겨 있으면 `AGENT_BUSY` 로 곧바로 알린다

| 경로 | 누가 | 무엇 |
| --- | --- | --- |
| `GET /api/v1/agents/{code}/tools` | 그 에이전트의 주인 또는 `ADMIN` | 응답 `{ "toolsets": [...], "unclassifiedEnabled": [...] }`. `toolsets` 는 등급 표에 있는 이름, 설명, 등급, 켜짐과 요청자의 변경 가능 여부다 |
| `PUT /api/v1/agents/{code}/tools` | 주인은 주인 등급, `ADMIN` 은 전부 | 본문 `{ "enabled": ["web", "vision"] }`. 켤 toolset 전체다. 바꿀 수 없는 등급은 지금 값과 같아야 한다. 응답은 `GET` 과 같은 모양이다 |
| `GET`, `PUT /api/v1/admin/agents/{code}/tools` | `ADMIN` | 위와 같은 모양. 다른 사람의 비공개 에이전트는 이 경로로만 다룬다 |

## 에이전트 만들기와 지우기

모든 사용자가 화면에서 자기 에이전트를 만든다. 근거는 [ADR-033](adr/ADR-033-사용자가-에이전트를-만들고-공개해도-만든-사람이-관리한다.md) 에 있다.

| 경로 | 하는 일 | 거절 |
| --- | --- | --- |
| `POST /api/v1/agents` | `{ "name", "visibility"? }` 로 만든다. 공개 범위 기본값은 `PRIVATE`. 201 과 에이전트를 돌려준다 | `VALIDATION_FAILED`, 상한이면 409 `AGENT_LIMIT_REACHED`, profile 을 만들지 못하면 `HERMES_PROVISION_FAILED` |
| `PATCH /api/v1/agents/{code}/visibility` | `{ "visibility" }`. 주인과 `ADMIN` 이 승인 없이 바꾼다 | `FORBIDDEN`, 읽을 수 없거나 지웠으면 `AGENT_NOT_FOUND`, 다른 요청이 그 에이전트를 고치는 중이면 `AGENT_BUSY`, `visibility` 가 비었으면 `VALIDATION_FAILED`, 켜진 에이전트를 그룹 공개로 바꿀 때 셸·파일 toolset 이 켜져 있으면 `AGENT_TOOLS_REQUIRE_PRIVATE`. 꺼진 에이전트는 켤 때 관리자 수정이 검사한다 |
| `DELETE /api/v1/agents/{code}` | 지운다. 204 | `FORBIDDEN`, 읽을 수 없거나 지웠으면 `AGENT_NOT_FOUND`, 고치는 중이면 `AGENT_BUSY`, profile 을 거두지 못하면 그 Hermes 오류 |

공개 범위를 바꿔도 주인은 그대로다.
주인이 비어 있는 옛 그룹 공개 에이전트를 `ADMIN` 이 `PRIVATE` 로 바꾸면 그 `ADMIN` 이 주인이 된다.

**만들기는 한 요청 안에서 끝낸다.** 차례는 아래와 같고, 중간에 실패하면 만든 것을 역순으로 거둔다(`people.application.HermesProfileProvisioner` 와 같은 규칙).
대시보드 plugin 의 계약은 [`hermes/profiles.md`](hermes/profiles.md) 의 「Control Plane 이 부르는 대시보드 plugin 경로」 가 갖는다.

1. 주인의 `app_user` 행을 잠그고 그 사용자의 지우지 않은 에이전트 수가 `assistant.agents.max-per-user`(기본 5)보다 적은지 본다. `ADMIN` 은 세지 않는다
2. `code` 와 profile 이름을 만든다. 둘 다 사용자가 넣은 이름과 무관한 무작위 값이다
3. `POST /api/profiles` 로 profile 을 `no_skills` 로 만든다. plugin 이 이 안에서 안전한 기본 도구, Control Plane MCP 등록, 서명 plugin, 관리 표식을 붙인다
4. 그 profile 에 묶인 MCP 토큰을 발급해 `PUT /api/env` 로 `MCP_FOS_ASSISTANT_API_KEY` 에 넣는다([ADR-032](adr/ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md))
5. `API_SERVER_MODEL_NAME` 과 `API_SERVER_KEY` 를 넣고 key 파일을 쓴다
6. 그 key 로 도구 목록을 읽는다. 셸·파일 등급이 켜져 있으면 plugin 틀이 적용되지 않은 것으로 보고 거두고 `HERMES_PROVISION_FAILED`. 도구 목록에는 MCP 서버 이름이 없어 MCP 등록은 3 이 성공한 것으로 믿는다
7. 에이전트 행을 `profile_managed = true` 로 저장한다

거둘 때는 토큰을 먼저 폐기한다. 토큰 발급과 폐기는 잠금을 쥔 트랜잭션과 떼어 곧바로 커밋한다.
profile 을 만드는 도중의 실패는 key 파일, profile 순으로 모두 시도해 거둔다.
만든 뒤의 실패와 지우기는 profile, key 파일 순으로 거두고, 하나라도 실패하면 거기서 멈춘다.
profile 을 거두지 못하면 에이전트를 지우지 않고 그 오류를 올린다.
새 profile 은 재시작 없이 공유 listener 에서 답한다. MCP 도구는 첫 연결까지 1~2분 걸릴 수 있다.
새 profile 은 가족 공용 credential 로 돈다([ADR-002](adr/ADR-002-profile은-나누고-ai-계정은-가족이-함께-쓴다.md)).

**지우기는 에이전트 행을 지우지 않는다.** `deleted_at` 을 적고 끈다.
`profile_managed` 가 참이면 MCP 토큰을 먼저 폐기하고, `DELETE /api/profiles/<이름>` 과 key 파일, 올린 스킬 디렉터리를 지운다. 거짓이면 profile 을 남긴다.
지운 에이전트의 대화는 읽기만 된다. 새 turn 과 다시 생성은 `AGENT_NOT_FOUND` 다.

| 무엇 | 어디 |
| --- | --- |
| 만들기, 공개 범위, 지우기의 순서 | `agent/application/AgentLifecycleService` |
| profile 을 만들고 거두기 | `people/application/HermesProfileProvisioner` 를 넓혀 쓴다 |
| 대시보드 호출 | `hermes` |

## 스킬

에이전트를 관리하는 사람이 화면에서 스킬을 올리고 고치고 지운다. 승인 절차는 없다.
근거는 [ADR-034](adr/ADR-034-올린-스킬은-control-plane-이-버전-디렉터리에-쓰고-hermes-는-읽기만-한다.md) 에 있다.

**본문은 데이터베이스에 두지 않는다.** Control Plane 이 공유 디렉터리에 쓰고 Hermes 는 읽기만 한다.

```
<ASSISTANT_SKILL_ROOT>/<profile>/<버전>/<스킬>/SKILL.md
                                          references/…
                                          templates/…
```

- 저장할 때마다 그 profile 의 올린 스킬 전체를 새 버전 디렉터리에 쓰고, 그 profile 의 `skills.external_dirs` 를 `ASSISTANT_SKILL_AGENT_ROOT` 아래 새 버전 경로로 바꾼다. 쓰는 도중에는 옛 버전이 쓰인다
- 설정 쓰기가 4xx 로 거절되면 새 디렉터리를 지운다. timeout 과 5xx 는 Hermes 가 이미 반영했을 수 있어 표식 없이 남기고, 다음 게시가 성공한 뒤 그보다 오래된 표식 없는 디렉터리를 지운다. 실패한 저장의 변경은 어느 쪽이든 반영되지 않으므로 다시 저장한다. 표식 있는 옛 버전은 최근 3개만 남긴다
- 게시에 성공하면 그 버전 디렉터리에 표식 파일 `.published` 를 쓴다. 지금 버전은 표식이 있는 가장 새 디렉터리다
- 같은 에이전트의 저장은 기다리는 에이전트 행 잠금으로 한 번에 하나씩 돈다. 잠금부터 표식 쓰기까지 한 트랜잭션이라 그동안 같은 에이전트의 도구와 공개 범위 변경은 `AGENT_BUSY` 다
- 스킬을 저장하면 그 에이전트의 `skills` toolset 을 함께 켠다. 올린 스킬이 있는 동안은 `skills` 를 끄지 못한다
- 마지막 남은 스킬을 지우면 새 버전을 쓰지 않고 빈 `external_dirs` 를 게시한 뒤 그 profile 의 버전 디렉터리를 모두 지운다. `skills` toolset 은 그대로 둔다

| 제한 | 값 |
| --- | --- |
| 이름 | 소문자, 숫자, `-`. 64자까지. `new` 는 새 스킬 화면 경로라 쓸 수 없다. Hermes 기본 스킬과 같으면 `SKILL_NAME_TAKEN` |
| 파일 | `SKILL.md` 와 `references/`, `templates/` 아래 텍스트 파일. 파일 20개까지 |
| 크기 | 파일마다 10만 자, 합계 1 MiB |

| 경로 | 하는 일 |
| --- | --- |
| `GET /api/v1/agents/{code}/skills` | `{ "skills": [{ "name", "description", "source": "UPLOADED" \| "HERMES", "enabled", "usage"? }], "editable", "skillsToolsetEnabled" }`. `usage`(`{count, lastInvokedAt}`)는 관리하는 사람에게만 준다 |
| `GET /api/v1/agents/{code}/skills/{name}` | 관리하는 사람만. 올린 스킬의 `{ "name", "description", "body", "files": [{ "path", "size" }] }`. `body` 는 앞머리를 포함한 `SKILL.md` 원문이고 `size` 는 UTF-8 바이트다 |
| `PUT /api/v1/agents/{code}/skills/{name}` | `{ "skillMd", "files": [{ "path", "content"? }] }` 로 스킬 하나를 통째로 바꾼다. 없으면 만든다. `content` 를 생략한 파일은 지금 버전의 같은 경로 내용을 그대로 둔다 |
| `DELETE /api/v1/agents/{code}/skills/{name}` | 올린 스킬을 지운다 |
| `PUT /api/v1/agents/{code}/skills/{name}/enabled` | 관리하는 사람만. `{ "enabled" }`. 대시보드의 스킬 켜고 끄기를 쓴다 |

목록은 대시보드 `GET /api/skills?profile=` 에서 읽는다. 켜고 끄기는 전역 토글만 쓰고 `skills.platform_disabled.api_server` 는 쓰지 않는다([`hermes/tools-and-skills.md`](hermes/tools-and-skills.md) 의 「스킬 커맨드와 API server」).
출처는 Hermes 가 올린 스킬과 모델이 만든 로컬 스킬을 모두 `agent` 로 주므로 쓰지 않는다. 올린 스킬 이름이 `UPLOADED`, 나머지가 `HERMES` 다.
올린 스킬 이름은 지금 버전과, 지금 버전보다 새로 쓰였지만 표식이 없는 버전에 있는 이름이다. 표식 없는 버전은 게시가 timeout 이나 5xx 로 끝난 것이라 Hermes 가 이미 가리키고 있을 수 있다.
그 이름은 목록에서 올린 스킬로 보이고, 원문 읽기와 같은 이름으로 다시 저장하기와 지우기가 된다. 다시 저장할 때 본문을 생략한 파일은 그 버전의 내용을 쓴다. 지우면 지금 버전을 다시 게시해 Hermes 가 그 버전에서 벗어난다.
지금 버전에 있는데 대시보드 목록에 없는 스킬도 올린 것으로 넣고 켜진 것으로 보인다. 게시 직후 색인 전이거나 Hermes 가 건너뛴 스킬도 화면에서 지울 수 있어야 하기 때문이다.
목록은 그 에이전트를 쓸 수 있는 사람이 본다. 올린 스킬의 원문 읽기, 쓰기, 지우기, 켜고 끄기는 관리하는 사람만 하고, 아니면 `FORBIDDEN` 이다.

### 스킬 커맨드

입력창 맨 앞의 `/<이름>` 을 Control Plane 이 해석한다. 근거는 [ADR-035](adr/ADR-035-대화창의-스킬-커맨드는-control-plane-이-해석해-hermes-에-넘긴다.md) 에 있다.

- 메시지 내용이 `^/[a-z0-9][a-z0-9-]*` 다음에 공백이나 끝이 오는 모양일 때만 커맨드다. 새 요청 칸은 없다
- 이름이 그 에이전트의 켜진 스킬 목록에 있으면 Hermes 에 보낼 입력만 「사용자가 이 스킬을 호출했다. `skill_view` 로 읽고 그 절차대로 다음을 하라」로 바꾼다. 저장하는 메시지는 사용자가 친 글 그대로다
- 없으면 Hermes 에 보내지 않고 400 `SKILL_COMMAND_UNKNOWN` 다
- 목록은 에이전트마다 짧게 캐시한다
- 흐름이 붙은 에이전트에서는 커맨드를 해석하지 않고 글 그대로 보낸다. 입력창도 `/` 목록을 띄우지 않는다

### 호출 이력

`execution_skill_use` 한 표에 둔다([`data-schema.md`](data-schema.md)).

| 출처 | 적는 곳 |
| --- | --- |
| `COMMAND` | 커맨드로 turn 을 시작할 때 `chat` 이 적는다 |
| `MODEL` | 실행 사건에서 `skill_view` 도구 호출을 받을 때 스킬 이름이 실려 있으면 `usage` 가 적는다. 대화 turn 의 실행만 기록된다. 위임과 흐름의 하위 실행은 Hermes 사건을 옮기지 않아 기록되지 않는다 |

| 경로 | 하는 일 |
| --- | --- |
| `GET /api/v1/usage/skills` | 요청자 자신의 호출만. `[{ "agentCode", "agentName", "skillName", "count", "lastInvokedAt", "lastConversationId" }]` |

관리하는 사람은 스킬 목록의 `usage` 로 합계만 보고, 누가 어느 대화에서 불렀는지는 보지 않는다.

| 무엇 | 어디 |
| --- | --- |
| 권한 판정과 저장 순서 | `skill/application/SkillService` |
| 호출 이력 적기와 읽기 | `skill/application/SkillUseRecorder`, `skill/application/SkillUsageQuery` |
| 버전 디렉터리 쓰기와 지우기 | `skill/infra/SkillStore` |
| `external_dirs` 게시와 대시보드 스킬 목록 | `skill/infra/SkillPublisher`, 호출은 `hermes` |
| 커맨드 판별과 입력 바꾸기 | `chat/application/SkillCommand` |

## 사진 첨부

대화에 올린 사진이다. 본문은 공유 디렉터리에 두고 데이터베이스에는 그것을 가리키는 행만 둔다.
근거는 [`adr/ADR-020-사진은-공유-디렉터리에-두고-에이전트가-파일로-읽는다.md`](adr/ADR-020-사진은-공유-디렉터리에-두고-에이전트가-파일로-읽는다.md) 에 있다.

- **디렉터리 뿌리를 코드에 박지 않는다.** 설정으로 받는다. 붙이는 일은 `fos-home-infra` 가 소유한다.
  기본값이 없다. Control Plane 이 쓰는 뿌리와 에이전트가 읽는 뿌리 둘 가운데 하나라도 비어 있으면 기동을 멈춘다.
- 뿌리 설정은 둘이다. `root` 는 Control Plane 이 쓰는 경로이고 `agent-root` 는 같은 디렉터리를 Hermes 컨테이너에서 보는 경로다.
  실행 입력에는 `agent-root` 를 적는다. 둘 다 기본값이 없어 비면 기동이 실패한다.
- 대화 하나가 디렉터리 하나다. 이름은 대화 번호다.
- 파일 이름은 `{첨부 번호}.{확장자}` 다. 올릴 때의 이름을 파일 이름으로 쓰지 않는다.
- **행을 지우지 않는다.** 파일을 지우고 `deleted_at` 을 적는다.
- 받는 형식은 `image/jpeg`, `image/png`, `image/gif`, `image/webp` 넷이다. HEIC 는 받지 않는다.
- multipart 상한은 한 장 11MB, 요청 12MB 다. 서비스 상한 10MB 를 조금 넘는 것은 `VALIDATION_FAILED` 로 거절한다.
  Tomcat 의 `max-swallow-size` 를 16MB 로 둔다. 요청 상한을 넘은 본문이 기본값 2MB 보다 크면
  Tomcat 이 400 을 쓴 뒤 연결을 끊어 클라이언트가 응답을 받지 못하기 때문이다. 16MB 를 넘으면 여전히 끊긴다.
- 상한은 한 번 보낼 때 10장, 한 장 10MB 다. 장수는 아직 메시지에 묶이지 않은 첨부만 센다. 보관 기간은 30일이다.

### 누가 볼 수 있나

그 대화의 주인만이다. 첨부에 직접 닿는 경로를 두지 않고 대화를 통해서만 닿는다.
**남의 대화의 첨부는 없는 것과 같은 응답을 준다.**

### 경로

| 경로 | 하는 일 |
| --- | --- |
| `POST /api/v1/chat/conversations/{id}/attachments` | 사진을 올린다. 첨부 번호를 돌려준다 |
| `GET /api/v1/chat/conversations/{id}/attachments/{attachmentId}` | 그 사진의 본문. 지워졌으면 410 |
| `DELETE /api/v1/chat/conversations/{id}/attachments/{attachmentId}` | 먼저 지운다 |

`POST /api/v1/chat/messages` 가 첨부 번호 목록을 함께 받는다.

새 대화에서 첫 사진을 올리려면 대화의 공개 식별자가 먼저 있어야 한다.
`POST /api/v1/chat/conversations` 가 제목이 빈 대화를 만들고, 제목은 첫 메시지가 정한다.

### 에이전트에게 알리는 법

**사용자가 쓴 메시지를 고치지 않는다.** `chat_message.content` 는 그대로 둔다.
Hermes 에 보내는 `input` 에만 사진이 놓인 자리와 파일 이름을 덧붙인다.

지난 대화를 다시 읽을 때 사람이 쓴 것과 우리가 덧붙인 것이 섞이지 않게 한다.

### 어느 클래스가 무엇을 하나

| 무엇 | 어디 |
| --- | --- |
| 첨부를 받고 판정하고 행을 만든다 | `chat/application` |
| 파일을 디스크에 두고 지운다 | `chat/infra` |
| 보관 기간이 지난 것을 지운다 | `chat/application` 의 일정 실행 |

**파일을 다루는 것이 한 곳이다.** 경로를 만드는 규칙이 흩어지면 지우는 쪽이 놓친다.

## 결과물 파일

에이전트가 turn 안에 만든 HTML 과 그것이 부르는 사진이다. 본문은 공유 디렉터리에 두고 데이터베이스에는 HTML 을 가리키는 행만 둔다.
근거는 [`adr/ADR-027-에이전트가-만든-html-은-대화별-폴더에-두고-스크립트-없이-보인다.md`](adr/ADR-027-에이전트가-만든-html-은-대화별-폴더에-두고-스크립트-없이-보인다.md) 에 있다.

- 뿌리 설정은 사진 첨부와 같은 모양으로 둘이다. `assistant.artifact.root` 는 Control Plane 이 보는 경로, `assistant.artifact.agent-root` 는 같은 디렉터리를 Hermes 컨테이너에서 보는 경로다.
  둘 다 기본값이 없어 비면 기동이 실패한다. 붙이는 일은 `fos-home-infra` 가 소유한다
- 대화 하나가 폴더 하나다. 이름은 대화 번호다. 폴더는 Control Plane 이 turn 을 시작할 때 만든다
- `artifact_write` 도구가 있는 profile 은 MCP 로 Control Plane 에 쓰기를 요청한다. 그 도구가 없고 파일 도구가 있는 profile 은 Hermes 가 대화 폴더에 직접 쓴다.
  Control Plane 은 대화 주인을 확인하고 저장하며, 읽기와 보관 기간 정리도 맡는다
- 보관 기간은 30일이다. 대화 폴더 단위로 센다. 폴더에서 가장 늦게 바뀐 파일이 30일을 넘기면 그 폴더의 파일을 함께 지운다.
  파일마다 세면 다음 turn 이 HTML 만 고쳤을 때 그 HTML 이 부르는 옛 사진이 먼저 지워진다
- 지우는 단위는 폴더 안의 파일 하나다. 사진 첨부처럼 행 단위로 지우지 않는다. 지운 HTML 의 `chat_artifact` 행에는 `deleted_at` 을 적고, 같은 파일이 여러 답에 묶였으면 그 행 모두에 적는다. 빈 폴더는 남는다
- 파일을 줄 때는 행을 보지 않는다. 대화 폴더 안에 있고 확장자가 허용되면 준다. HTML 이 부르는 사진은 행이 없다. 행은 없는 파일이 410 인지 404 인지 구분할 때만 본다
- 파일을 읽는 경로에는 크기 상한을 두지 않고 스트림으로 준다. MCP 로 쓰는 파일은 5MB 로 제한한다

### 에이전트에게 알리는 법

실행 입력의 맨 앞에 한 단락을 붙인다. 사용자가 쓴 메시지는 고치지 않는다.

```text
[결과물 폴더]
{agent-root}/{대화 번호}
대화 식별자: {publicId}
이 폴더는 사용자가 HTML 페이지나 이미지 같은 파일을 만들어 달라고 할 때만 쓴다. 그런 요청이 없으면 파일을 만들지 않고 답을 글로만 한다.
artifact_write 도구가 있으면 그것으로 저장한다. conversation_id 에 이 대화 식별자를 넣고 path 는 상대 경로로 쓴다.
artifact_write 도구가 없고 파일 도구가 있으면 위 폴더에 결과물 파일을 직접 쓴다.
artifact_write 에서는 HTML 과 CSS 는 content, 이미지는 source_url 을 쓴다. 둘 중 하나만 넣는다. 파일 하나는 5MB 까지다.
HTML 이 사진을 부를 때는 이 폴더 안의 상대 경로를 쓴다.
이 폴더 경로와 파일 경로를 답에 쓰지 않는다. 만든 결과물은 답 아래에 자동으로 붙는다.
```

사진 첨부의 단락이 있으면 그 앞에 둔다. 매 turn 붙인다. 흐름으로 돈 turn 은 하위 실행의 입력 맨 앞에도 같은 단락을 붙인다. Chief 는 나눌 요청 본문 안에서 이 단락을 받는다. 한 줄이 늘어 입력이 조금 커지지만,
에이전트가 이번 turn 에 파일을 만들지 미리 알 수 없다.

`ArtifactService.agentPreamble(Conversation conversation)` 은 폴더를 만드는 내부 번호와
도구에 넘길 공개 UUID 를 같은 대화에서 가져온다.
`ChatService` 의 일반 실행과 흐름 실행, `ResearchAndBuildFlow` 의 하위 실행이 같은 단락을 받는다.
사용자 메시지의 저장 본문에는 이 단락을 넣지 않는다.

**답에 적힌 폴더 경로를 Control Plane 이 고치지 않는다.**
단락이 경로를 답에 쓰지 말라고 이르고, 그래도 적힌 경로는 그대로 저장하고 보인다.
turn 이 끝날 때 답 본문의 폴더 절대 경로를 결과물 열기 링크나 파일 이름으로 바꾸는 방안을 검토했고 하지 않았다.

- 답은 흘러나오는 동안 이미 화면에 보인다. 끝난 뒤에 바꾸면 사용자가 본 글과 저장된 글이 달라진다.
  저장은 실행 결과로 한다는 [ADR-008](adr/ADR-008-스트리밍은-보여주기용이고-저장은-실행-결과로-한다.md) 과도 어긋난다
- 운영에서 답에 적힌 경로의 절반은 에이전트 작업 공간의 상대 경로였다. 결과물 폴더가 아니어서 바꿀 대상을 찾지 못한다
- 에이전트의 스킬과 스크립트가 경로를 출력하지 않게 고치는 것이 먼저다. 그쪽은 에이전트 저장소가 맡는다

단락을 바꾼 뒤에도 답에 폴더 경로가 계속 적히면 다시 검토한다.

### MCP 로 쓰는 자리

**주인 확인과 저장은 `chat`, MCP 응답과 도구별 인자 검사는 `mcp` 가 맡는다.**
`mcp/application` 의 `McpToolService` 가 `chat/application` 의 `ArtifactWriteService` 를 부른다.
`chat` 은 MCP 프로토콜을 알지 않는다.
관련 타입의 책임은 아래와 같다.

| 타입 | 책임 |
| --- | --- |
| `mcp/presentation/McpDtos` | `memory_read` 와 `artifact_write` 의 요청 형태. 데이터 record 를 컨트롤러 안에 두지 않는다 |
| `mcp/presentation/McpController` | 도구 이름에 따라 인자를 검사하고 JSON-RPC 오류로 바꾼다 |
| `mcp/application/McpToolService` | 도구 목록과 MCP `content`, `isError` 결과를 만든다 |
| `chat/application/ArtifactWriteRequest`, `ArtifactWriteResult` | 각각 UUID, 상대 경로와 입력 방식, 저장된 경로와 바이트 수를 전달한다 |
| `chat/application/ArtifactWriteService` | `ConversationAccess.requireOwn` 으로 주인을 확인한 뒤 본문 또는 내려받은 이미지를 저장한다 |
| `chat/application/ArtifactSourceProperties` | 허용 호스트와 연결, 읽기, 호출 전체 제한 시간을 받는다 |
| `chat/infra/ArtifactStore` | 쓰기용 경로 판정, 부모 폴더 생성, 임시 파일 저장과 교체를 기존 경로 규칙과 함께 갖는다 |
| `chat/infra/ArtifactSourceFetcher` | URL 과 DNS 를 검사하고 검증한 IP 에 HTTPS 로 연결해 제한된 이미지 본문만 반환한다 |

`ArtifactWriteService.write(CurrentUser, ArtifactWriteRequest)` 는 대화 주인을 확인하기 전에는
폴더 생성이나 URL 조회를 하지 않는다.
`ArtifactStore.ensureFolder` 가 실패를 경고 로그로만 남기므로 쓰기 경로는 실제 폴더 생성 여부를 확인하고 오류로 돌려준다.
`resolveInside` 는 기존 파일을 읽는 용도로 유지하고 쓰기용 판정은 같은 클래스에 둔다.
부모 생성 전후의 실제 경로, 대화 폴더 자체의 링크, 최종 대상의 링크를 검사한다.
같은 폴더에 임시 파일을 완성한 뒤 교체하고 실패하면 임시 파일을 지운다.

`McpController` 의 `tools/call` 은 도구별로 인자를 검사한다.
`memory_read` 의 정수 `id` 입력과 오류 계약은 유지한다.
요청자는 아래 「MCP 요청자」 의 `McpCallerResolver` 가 origin 실행에서 정한 `CurrentUser` 다. 토큰이 사용자를 정하지 않는다.
인자와 응답, SSRF 조건은 [`tools-and-skills.md`](hermes/tools-and-skills.md#결과물-쓰기-도구) 가 정한다.
같은 사용자의 다른 대화에 쓸 때 답에 묶이는 시점과 실패 분기는
[`flow.md`](flow.md#결과물을-mcp-로-쓸-때) 에 있다.

### 답에 묶는 법

**모델이 답에 적은 경로를 읽지 않는다.** turn 이 끝나면 폴더를 훑는다.

- 그 turn 이 시작한 뒤에 바뀐 `.html` 파일을 찾는다. 하위 폴더까지 본다. 읽지 못한 폴더는 건너뛰고 나머지를 묶는다
- 파일의 마지막 수정 시각을 Control Plane 의 시계로 잡은 turn 시작 시각과 견준다. Hermes 와 Control Plane 이 같은 기계의 로컬 디스크를 함께 쓴다는 전제다. 다른 기계나 네트워크 파일 시스템에 두면 두 시계가 어긋난 만큼 결과물이 빠질 수 있다
- 찾은 파일마다 `chat_artifact` 행을 그 turn 의 답 메시지에 묶어 만든다
- 답 메시지가 없는 turn(빈 답으로 중지)은 묶지 않는다
- 흐름으로 돈 turn 도 같다. 폴더는 대화마다 하나이므로 Chief 와 하위 에이전트가 같은 폴더를 쓴다
- 훑다가 실패해도 turn 은 실패하지 않는다. 경고 로그만 남긴다

### 경로

| 경로 | 하는 일 |
| --- | --- |
| `GET /api/v1/chat/conversations/{id}/files/**` | 대화 폴더 안의 파일 본문 |

`{id}` 는 대화의 공개 식별자이고 그 뒤가 폴더 안의 상대 경로다. 주인만 받는다.

| 판정 | 응답 |
| --- | --- |
| 남의 대화, 없는 대화 | `CONVERSATION_NOT_FOUND` |
| 확장자가 `html`, `css`, `png`, `jpg`, `jpeg`, `gif`, `webp` 가 아니다 | 404 `ARTIFACT_NOT_FOUND` |
| 심볼릭 링크를 따라간 실제 경로가 대화 폴더 밖이다 | 404 `ARTIFACT_NOT_FOUND` |
| 경로에 `..` 처럼 정규화되지 않은 조각이 있다 | 컨트롤러에 닿기 전에 Spring Security 가 403 으로 거절한다. web 서버 라우트는 빈 조각과 `..` 를 400 `VALIDATION_FAILED` 로 먼저 막는다 |
| 파일이 없다. 그 경로의 `chat_artifact` 행이 지워졌다고 적혀 있다 | 410 `ARTIFACT_GONE` |
| 파일이 없다. 행도 없다 | 404 `ARTIFACT_NOT_FOUND` |

응답 머리글은 모든 파일에 같다.

| 머리글 | 값 |
| --- | --- |
| `Content-Type` | 링크를 따라간 실제 파일의 확장자로 정한다. HTML 은 `text/html; charset=utf-8` |
| `X-Content-Type-Options` | `nosniff` |
| `Content-Security-Policy` | `sandbox allow-same-origin allow-popups allow-popups-to-escape-sandbox; default-src 'none'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; base-uri 'none'; form-action 'none'` |
| `Cache-Control` | `private, no-cache` |
| `ETag` | `W/"{바이트 수 16진수}-{마지막 수정 시각 밀리초 16진수}"`. 약한 검증자다 |
| `Last-Modified` | 파일의 마지막 수정 시각 |

web 의 서버 라우트는 이 머리글을 그대로 옮긴다. 옮기지 않으면 주소를 직접 열었을 때 스크립트가 돈다.

**다시 열 때는 304 로 끝낸다.** `no-cache` 는 저장하지 말라는 뜻이 아니라 쓰기 전에 매번 확인하라는 뜻이다.
브라우저가 `If-None-Match` 나 `If-Modified-Since` 를 보내면 파일이 그대로일 때 본문 없이 304 로 답한다.
에이전트가 같은 경로의 HTML 을 고치면 바이트 수나 수정 시각이 바뀌어 다음 열기에 새 본문을 받는다.

- 주인 확인과 경로 판정을 먼저 한다. 남의 대화에 맞는 `ETag` 를 보내도 304 가 아니라 `CONVERSATION_NOT_FOUND` 다
- `If-None-Match` 가 있으면 그것만 본다. `*` 이거나 목록의 값 하나가 `W/` 를 뗀 채 같으면 304 다
- `If-None-Match` 가 없고 `If-Modified-Since` 가 있으면 초 단위로 견준다. 수정 시각이 그 시각보다 늦지 않으면 304 다
- 304 에도 위 표의 머리글을 모두 붙인다. `Content-Type` 과 `Content-Length` 는 뺀다
- 304 로 답할 때는 파일을 열지 않는다

web 의 서버 라우트는 브라우저의 `If-None-Match` 와 `If-Modified-Since` 를 Control Plane 에 옮기고,
응답의 `ETag` 와 `Last-Modified` 를 브라우저로 옮기고, 304 를 오류로 바꾸지 않고 본문 없이 그대로 돌려준다.

2026-09-29 운영에서 원본 해상도 사진 12장, 약 28MB 가 든 결과물을 네 번 열 때마다 모두 200 으로 전체를 다시 받았다.

### 메시지 한 줄의 `artifacts`

`GET .../messages` 의 한 줄에 `artifacts` 를 더한다. `[{ "path", "byteSize", "deleted" }]` 이고 없으면 빈 배열이다.
`deleted` 는 `chat_artifact.deleted_at` 이 채워졌는지다.

### 어느 클래스가 무엇을 하나

| 무엇 | 어디 |
| --- | --- |
| 폴더를 만들고 훑고 파일을 읽고 지운다 | `chat/infra` 의 `ArtifactStore` |
| turn 이 끝나면 행을 만들고, 파일을 줄 때 판정한다 | `chat/application` 의 `ArtifactService` |
| 보관 기간이 지난 것을 지운다 | `chat/application` 의 `ArtifactCleaner`. 첨부의 정리와 같은 시각에 돈다 |

**경로를 만드는 규칙이 `ArtifactStore` 한 곳에 있다.** 대화 폴더 밖인지 판정하는 것도 거기서 한다.

## MCP 요청자

Control Plane MCP 의 토큰은 profile 만 증명하고, 사용자가 걸린 도구의 요청자는 서명한 `_fos_ctx` 로 찾은 **origin 실행**의 사용자다.
origin 실행은 하위 에이전트 session 이면 만들 때 등록한 실행이고, 최상위 session 이면 그 뿌리 session 으로 도는 실행이다.
결정은 [ADR-032](adr/ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md) 와 [ADR-037](adr/ADR-037-hermes-하위-에이전트-session-의-주인은-만들-때-등록한-줄로-정한다.md), 흐름과 갈리는 지점은 [`flow.md`](flow.md#mcp-호출의-요청자를-정할-때) 에 있다.

### 어느 클래스가 무엇을 하나

| 자리 | 하는 일 |
| --- | --- |
| `mcp.infra.AgentTokenAuthenticationFilter` | `/mcp` 와 `/internal/hermes/session-bindings/subagent` 요청의 토큰을 인증해 `McpPrincipal` 을 인증 주체로 둔다. `CurrentUser` 를 두지 않는다. 서명 검증에 쓸 토큰 해시를 요청 속성으로도 넘긴다 |
| `mcp.application.AgentTokenService` | 발급, profile 묶기, 목록, 폐기, 인증. profile 이 빈 옛 토큰은 `McpProperties.legacyUserTokens` 가 참일 때만 인증한다 |
| `mcp.application.McpPrincipal` | 인증 결과. 토큰 번호, profile, 옛 토큰의 사용자 번호, 토큰 해시. profile 이 비었으면 옛 토큰이다 |
| `mcp.application.McpProperties` | `assistant.mcp` 설정. `legacy-user-tokens`(기본 거짓) |
| `mcp.application.McpCallerResolver` | 묶인 토큰이면 `_fos_ctx` 서명 확인, origin 실행 찾기, 그 실행의 사용자 읽기를 차례로 한다. 옛 토큰이면 그 토큰의 사용자를 쓴다. 실패는 모두 `MCP_CALL_CONTEXT_INVALID` 다 |
| `mcp.application.McpCaller` | 판정 결과. 요청자 `user`, origin 실행 `originExecution`, 확인한 `context`. origin 실행은 끝난 실행일 수 있다. 옛 토큰이면 뒤의 둘이 비었다 |
| `mcp.application.McpCallContext` | `_fos_ctx` 를 읽고 서명을 확인한다. 모델이 준 다른 인자는 보지 않는다 |
| `mcp.application.SubagentRegistration` | 등록 본문을 읽고 서명을 확인한다. 서명할 글의 첫 줄이 `v1-subagent` 다 |
| `mcp.presentation.SubagentSessionController` | `POST /internal/hermes/session-bindings/subagent`. 인증 주체가 묶인 `McpPrincipal` 인지 보고 등록을 부른다 |
| `orchestration.application.SessionOwnerResolver` | profile, 서명한 뿌리 session, 그 호출의 session 으로 origin 실행을 정한다. 등록을 먼저 보고, 없으면 session 이 뿌리와 같을 때만 `DelegationParentResolver` 를 부른다 |
| `orchestration.application.DelegationParentResolver` | profile 과 서명한 뿌리 session 으로 도는 실행 하나를 찾는다. 최상위 session 의 판정과 최상위 자식의 등록이 쓴다. 사용자로 먼저 거르지 않는다. 없거나 둘 이상이거나 profile 이 다르면 같은 실패 |
| `orchestration.application.SubagentSessionRegistrar` | 등록 하나를 적는다. 부모를 풀고, 같은 origin 의 재등록은 그대로 두고, 다른 origin 은 거절한다 |
| `orchestration.domain.HermesSessionBinding`, `orchestration.infra.HermesSessionBindingRepository` | 하위 에이전트 session 등록 줄과 그 저장소 |

`McpController` 는 `params.name` 이 문자열이고 `params.arguments` 가 객체인지 본 뒤 `McpCallerResolver` 를 부른다. 도구별 인자 검사는 그 뒤에 하고, 그 `McpCaller` 로 `McpToolService` 를 부른다.
판정이 실패하면 `McpToolService.invalidContext()` 의 같은 도구 결과를 돌려준다.
`memory_read` 와 `artifact_write` 가 이 길을 쓰고, 앞으로의 `agent_*` 도 같은 길을 쓴다.

등록 경로는 MCP 도구가 아니다. `tools/list` 에 나오지 않고 `McpController` 를 지나지 않는다.
`ControlPlaneJwtFilter` 는 이 경로를 `/mcp` 처럼 건너뛴다. 사용자 JWT 로 부르면 `AgentTokenAuthenticationFilter` 가 토큰으로 인증하지 못해 401 이다. 컨트롤러의 `McpPrincipal` 확인은 그 뒤의 방어 검사다.

### 토큰 관리 경로

관리자만 부른다.

| 경로 | 하는 일 |
| --- | --- |
| `POST /api/v1/admin/agent-tokens` | 본문 `{ "profileName", "label" }`. profile 에 묶인 새 토큰을 발급하고 원문을 한 번만 돌려준다. 사용자로 발급하는 길은 없다 |
| `PUT /api/v1/admin/agent-tokens/{id}/profile` | 본문 `{ "profileName" }`. profile 이 빈 옛 토큰을 그 profile 에 묶고 `user_id` 를 비운다. 이미 묶였거나 폐기된 토큰은 `VALIDATION_FAILED` |
| `GET /api/v1/admin/agent-tokens` | 목록. 한 줄에 `id`, `profileName`, `userEmail`(옛 토큰만), `label`, 발급과 마지막 사용과 폐기 시각 |
| `DELETE /api/v1/admin/agent-tokens/{id}` | 폐기한다. 행은 남는다 |

profile 이름은 `HermesProfileName` 의 규칙을 따른다. 그 profile 에 에이전트가 있는지는 보지 않는다. profile 을 먼저 만들고 에이전트를 나중에 붙이는 순서가 있어서다.

### 실행 줄에 적는 session

Hermes 에 보낼 session 과 실행 줄에 적을 session 은 뜻이 다르다. 둘을 `orchestration.domain.RunSession` 하나로 넘긴다.
문자열 둘을 나란히 받으면 순서를 바꿔도 컴파일되기 때문이다.

| 만드는 자리 | `runtimeSessionId`(보낼 session) | `correlationSessionId`(실행 줄에 적을 session) |
| --- | --- | --- |
| `ConversationSessions.ensure` 가 대화 turn 에 | 대화의 `hermes_session_id` | 대화의 `hermes_root_session_id`, 비었으면 보낼 session |
| `RunSession.fresh()` 가 흐름의 하위 실행과 위임 자식에 | 새 `fos-<uuid>` | 같은 값 |

`ChatService` 와 `ResearchAndBuildFlow` 의 Chief 는 `ensure` 의 값을, `ChildExecutionRunner` 는 `fresh()` 의 값을 `AgentRunner.run` 에 넘긴다.
Memory 제안은 session 을 적지 않는다.

## 다른 에이전트에게 맡기기

**아직 구현하지 않았다.** 이 절은 위임 도구를 열 때의 설계다. 지금 있는 것은 「MCP 요청자」 의 판정과 하위 에이전트 session 등록, `DelegationKey`, 실행 줄의 session 칸이다. 구현하면 이 절이 현재 동작이 된다.
하위 에이전트가 `agent_delegate` 를 부르면 새 FOS 자식의 `parent_execution_id` 는 그 하위 에이전트의 origin 실행이다. 하위 에이전트 몫의 실행 줄은 만들지 않는다.

Hermes 가 Control Plane MCP 의 `agent_*` 도구로 다른 에이전트를 부른다. Control Plane 은 무엇을 할지 정하지 않고 경계만 검사한다.
결정은 [ADR-017](adr/ADR-017-무엇을-할지는-hermes-가-정하고-control-plane-은-경계만-갖는다.md) , [ADR-031](adr/ADR-031-mcp-호출의-부모-실행은-profile-플러그인이-서명한-뿌리-session-으로-잇는다.md), [ADR-032](adr/ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md), 흐름은 [`flow.md`](flow.md#다른-에이전트에게-맡길-때) 에 있다.

### 어느 클래스가 무엇을 하나

| 자리 | 하는 일 |
| --- | --- |
| `mcp.presentation.McpController` | 도구 이름과 인자 모양만 본다. 요청자는 「MCP 요청자」 의 `McpCallerResolver` 가 정한다 |
| `mcp.application.McpToolService` | 도구 결과를 MCP 모양으로 만든다. 예외 문구를 그대로 내보내지 않는다 |
| `orchestration.application.AgentDelegationService` | 부모는 `McpCaller.originExecution()` 이다. 깊이와 동시 한도, 같은 호출 확인, 위임 시작, 상태, 중지 |
| `usage.domain.DelegationKey` | 같은 위임을 두 번 만들지 않는 키. `agent_execution.delegation_key` 칸의 값이라 `usage` 에 둔다. 문자열이 아니라 record 라 다른 문자열 인자와 자리를 바꿔 넘기지 못한다. 정의는 ADR-032 의 「`delegation_key`」 |
| `orchestration.application.DelegationProperties` | `assistant.delegation` 설정. 깊이, 뿌리당 동시 자식, 전체 동시 위임, 제출 대기 시간 |
| `orchestration.application.ChildExecutionRunner` | 자식 실행을 여는 유일한 자리. 에이전트 확인과 부모, 뿌리 번호를 정하고 `RunSession.fresh()` 로 새 session 을 정한다 |
| `orchestration.application.AgentRunner` | Memory 다시 조립, 모델 선택, 실행 줄, 제출, 완료 기록. 흐름과 위임이 함께 쓴다 |

**MCP 쪽은 Hermes 를 부르지 않는다.** 실행을 시작하고 멈추는 것은 `orchestration` 이 기존 `AgentRunner` 와 `HermesRunsClient` 로 한다.

### 기다리지 않는 위임

`agent_delegate` 는 제출까지만 기다리고 실행 번호를 돌려준다.
실행은 가상 스레드 하나에서 `AgentRunner.run` 으로 끝까지 돌고, 끝나면 답을 그 실행 줄의 `output_text` 에 적는다.
동시에 도는 위임은 뿌리당 한도와 전체 한도로 묶는다. 트랜잭션 안에서 Hermes 를 부르지 않는다.

### 깊이와 동시 한도

깊이는 부모의 `parent_execution_id` 를 따라 올라가 센다. 사용자가 부른 실행이 0 이다.
`ChildExecutionRunner` 가 깊이 1 로 막던 규칙은 이 설정값으로 바뀐다.
뿌리당 동시 자식은 같은 `root_execution_id` 아래 `delegation_key` 가 있는 도는 실행의 수로 센다. Memory 제안처럼 위임이 아닌 자식은 세지 않는다.

### `ResearchAndBuildFlow`

넓히지 않는다. 지우는 조건은 [ADR-017](adr/ADR-017-무엇을-할지는-hermes-가-정하고-control-plane-은-경계만-갖는다.md#researchandbuildflow-의-자리) 에 있다.

## 대화

`chat` 패키지가 대화와 메시지를 갖는다.
한 번의 대화가 지나는 길은 위의 「한 번의 대화가 지나는 길」 절이, 화면 흐름은
[`flow.md`](flow.md) 의 「대화 이력」 부터 「새 대화 화면」 까지의 절이 갖는다.

### 경로

| 경로 | 하는 일 |
| --- | --- |
| `GET /api/v1/chat/conversations` | 내 대화 목록. 지운 대화는 빠진다 |
| `PATCH /api/v1/chat/conversations/{id}` | 이름을 바꾼다. 본문 `{ "title": "..." }`. 바뀐 대화 한 줄을 돌려준다 |
| `PUT /api/v1/chat/conversations/{id}/model` | 대화의 모델과 effort 를 바꾼다. 본문 `{ "provider", "model", "reasoningEffort" }`. 셋 다 null 이면 기본값으로 되돌린다. 바뀐 대화 한 줄을 돌려준다 |
| `GET /api/v1/chat/model-options?agentCode=` | 그 에이전트의 profile 로 고를 수 있는 모델. 요청자가 쓸 수 있는 에이전트만 받는다 |
| `DELETE /api/v1/chat/conversations/{id}` | 목록에서 숨긴다. 204 |
| `GET /api/v1/chat/conversations/{id}/messages` | 메시지 목록. 이전 판도 모두 온다 |
| `POST /api/v1/chat/messages` | 한 번에 받는다 |
| `POST /api/v1/chat/messages/stream` | 사건으로 받는다 |
| `POST /api/v1/chat/conversations/{id}/regenerate/stream` | 마지막 답을 다시 만든다. 본문이 없다 |
| `POST /api/v1/chat/executions/{id}/stop` | 돌고 있는 실행을 멈춘다. 202 와 `{ "status": "stopping" }` |
| `GET /api/v1/chat/conversations/{id}/running` | 이 대화에 지금 도는 turn. `{ "running", "executionId", "startedAt" }`. 돌지 않으면 `running` 이 false 이고 나머지는 null |
| `GET /api/v1/chat/conversations/by-number/{number}` | 옛 주소 `/c/{번호}` 를 넘겨 주려고 번호로 대화를 찾는다. `{ "id": "<공개 식별자>" }` |

지운 대화와 남의 대화는 모든 경로에서 `CONVERSATION_NOT_FOUND` 다. 둘을 가리지 않는다.

### 대화의 모델 선택

대화 한 줄(`ConversationView`)은 `provider`, `model`, `reasoningEffort` 를 싣는다. 고르지 않았으면 셋 다 null 이다.

`GET /api/v1/chat/model-options` 의 응답이다.

| 칸 | 뜻 |
| --- | --- |
| `defaultProvider`, `defaultModel` | 그 profile 의 기본값. Hermes 가 주지 않으면 null |
| `providers[]` | `{ "provider", "name", "models": [...], "reasoningCapable": {...} }`. Hermes 가 `authenticated` 를 참으로 준 provider 만 남긴다. 기본 provider 가 맨 앞에 오고, 모델은 Hermes 가 준 차례 그대로다 |
| `providers[].reasoningCapable` | 모델 이름을 열쇠로 한 참거짓 표. Hermes 의 `capabilities.<모델>.reasoning` 이다. 값이 없는 모델은 참으로 본다. 화면은 거짓인 모델에서 effort 를 고르지 못하게 한다 |
| `reasoningEfforts` | `["low", "medium", "high", "xhigh", "max"]`. 고정이다 |

`hermes/HermesModelClient` 가 `GET {profile}/api/model/options` 를 부르고, `chat/application/ModelOptionsService` 가 profile 마다 10분 들고 있는다.
10분이 지나 다시 읽다 Hermes 가 답하지 못하면 들고 있던 옛 목록을 돌려준다. 그 profile 의 목록을 한 번도 읽지 못했으면 `HERMES_UNAVAILABLE` 이다.

web 은 입력창 아래의 `chat/model-picker.tsx` 로 고른다.
목록은 창을 열 때만 서버 라우트 `GET /api/chat/model-options` 로 읽는다. 그래서 창을 열기 전 단추는 대화에 적힌 값만 보인다.
고른 값은 `PUT /api/chat/conversations/{id}/model` 로 저장하고, 그 응답의 대화 한 줄로 대화 목록의 그 줄만 바꾼다(`conversations-provider` 의 `replace`).
**저장보다 먼저 나간 목록 다시 읽기의 응답은 버린다.** 새 대화에서 고르면 빈 대화를 만들며 목록을 다시 읽는 요청과 저장이 함께 나가, 늦게 온 목록이 저장한 값을 덮을 수 있기 때문이다.
`replace` 는 한 줄만 바꾸므로, 버린 응답이 있으면 목록을 한 번 더 읽어 다른 줄의 제목과 순서를 맞춘다.
기존 대화는 목록에 그 줄이 오기 전까지 단추를 막는다(`Composer` 의 `modelChoiceUnknown`). 적힌 모델을 모르는 채 저장하면 그 모델을 지우기 때문이다.
목록을 저장하지 않는 까닭과 기본값을 Hermes 에 두는 까닭은
[ADR-030](adr/ADR-030-모델과-effort-는-대화가-고르고-기본값은-hermes-profile-이-갖는다.md) 에 있다.

실행을 보낼 때 `chat/domain/ModelChoice` 가 대화에 적힌 세 값을 싣는다. 비어 있으면 `/v1/runs` 에 `provider`, `model` 을 빼고,
effort 가 비어 있으면 `model_options` 를 뺀다. 보내기, 다시 생성, Memory 제안, 흐름의 하위 실행이 모두 같은 값을 쓴다.

**대화 경로의 `{id}` 는 대화의 공개 식별자(UUID)다.** 대화 표의 번호가 아니다.
응답에서 대화를 가리키는 칸도 모두 공개 식별자다. 대화 목록의 `id`, 보내기 응답과 사건의 `conversationId`,
실행 목록의 `conversationId` 가 여기 해당한다. 보내기 요청의 `conversationId` 도 공개 식별자를 받는다.
메시지, 첨부, 실행의 번호는 그대로 숫자다.

컨트롤러가 `ConversationAccess.requireOwnId(user, publicId)` 로 주인을 확인하며 번호로 바꾸고,
`application` 안쪽은 지금처럼 번호를 쓴다. 사건과 응답에 싣는 공개 식별자는 `Conversation.publicId()` 에서 읽는다.
UUID 모양이 아닌 `{id}` 는 400 `VALIDATION_FAILED` 다. 「backend 패키지」 의 형식 오류 규칙이 모든 경로에 걸린다.

`by-number` 경로는 옛 링크가 쓰이지 않게 되면 지운다.
근거는 [ADR-025](adr/ADR-025-대화는-주소에-공개-식별자를-쓰고-번호는-안에만-둔다.md)에 있다.

**turn 이 끝날 때 대화를 통째로 다시 저장하지 않는다.**
지금은 요청 시작에 읽은 `Conversation` 을 끝에서 `save` 한다. 그 사이에 사용자가 이름을 바꾸거나 지우면
옛 값으로 덮여 지운 대화가 되살아난다.
turn 이 바꾸는 칸은 `hermes_session_id` 와 `updated_at` 뿐이므로 그 둘만 고치는 질의로 쓴다.
새 대화의 첫 turn 은 시작할 때 `hermes_session_id` 와 `hermes_root_session_id` 를 비어 있을 때만 채우는 질의로 쓴다. 이 질의는 `updated_at` 을 바꾸지 않는다. 바꾸면 실패한 turn 도 대화를 목록 맨 위로 올린다.
남의 실행과 없는 실행은 `EXECUTION_NOT_FOUND` 다.

### 메시지 한 줄

`GET .../messages` 가 주는 한 줄이다. 이미 있는 칸에 아래를 더한다.

| 칸 | 뜻 |
| --- | --- |
| `status` | 답을 만든 실행의 상태. `SUCCEEDED`, `FAILED`, `CANCELLED`, `RUNNING` 중 하나. 사용자 메시지는 null |
| `replacesMessageId` | 이 메시지가 새 판으로 대신하는 이전 메시지. 없으면 null |
| `activity` | 작업 과정의 요약. `{ toolCount, subagentCount, durationMs }`. 사건이 없는 답과 사용자 메시지는 null |
| `artifacts` | 그 답의 turn 이 만든 결과물 파일. 위 「결과물 파일」 절이 모양을 갖는다 |

`activity` 는 답을 만든 실행과 그 아래 자식 실행의 `execution_event` 를 모두 센다.
예전에 provider 가 막혀 다음 모델로 넘어간 turn 은 막힌 시도가 따로 실행 줄을 갖는다. 요약은 답을 만든 실행만 세고 막힌 시도의 사건은 넣지 않는다. 지금은 Control Plane 이 provider 를 넘기지 않아 새 turn 에는 이런 줄이 생기지 않는다.

| 칸 | 세는 것 |
| --- | --- |
| `toolCount` | `TOOL_STARTED` 수와 `TOOL_COMPLETED` 수 가운데 큰 값. 한쪽이 빠져 와도 줄지 않게 한다 |
| `subagentCount` | `SUBAGENT_STARTED` 사건 수와 자식 실행 수를 더한 값. Memory 제안 실행은 세지 않는다 |
| `durationMs` | 뿌리가 시작한 때부터 나무에서 가장 늦게 끝난 실행이 끝난 때까지. 흐름은 Chief 가 끝난 뒤에도 돈다 |

흐름으로 돈 답은 하위 에이전트 사건 없이 자식 실행만 남는다. 자식 실행을 더하지 않으면 요약이 0 이 되고 블록이 사라진다.
한 하위 에이전트가 사건과 자식 실행 둘 다 남기면 두 번 센다.
그 겹침은 [`flow.md`](flow.md) 의 「실행 하나를 다시 볼 때」 절에서 나무가 이미 받아들인 것과 같다.
대화 하나를 열 때 질의가 답 수만큼 늘지 않도록, 실행 번호 목록으로 한 번에 센다.

### 화면으로 보내는 사건

스트리밍 경로가 보내는 `ChatEvent` 의 `type` 이다.
화면은 이 값만 안다. Hermes 의 원래 사건 이름을 읽지 않는다.

| `type` | 언제 | 싣는 칸 |
| --- | --- | --- |
| `started` | 실행 줄을 만든 직후 | `conversationId`, `executionId` |
| `delta` | 답 조각 | `text` |
| `tool` | 도구가 시작되거나 끝났다 | `toolName`, `detail`, `phase`, `durationMs`, `failed`. `detail` 은 아래 「도구 `detail` 을 싣는 대상」 을 따른다 |
| `subagent` | 하위 에이전트가 시작되거나 끝났다 | `subagentId`, `goal`, `model`, `phase`, `inputTokens`, `outputTokens`, `durationMs`, `failed` |
| `step` | 흐름의 단계가 시작되거나 끝났다 | `stepName`, `stepState` |
| `reset` | 지금까지 흘린 조각을 지우라. provider 를 넘기던 때만 보냈고 지금은 보내지 않는다. 화면은 아직 받는 쪽을 갖고 있다 | |
| `done` | 끝나서 저장했다 | `conversationId`, `messageId`, `executionId` |
| `stopped` | 중지로 끝나서 저장했다 | `conversationId`, `messageId`, `executionId`. 남긴 답이 없으면 `messageId` 가 null |
| `error` | 실패했다 | `code`, `message` |

`phase` 는 `started` 와 `completed` 둘이다.
`subagent` 의 칸은 Hermes 가 실어 보낼 때만 찬다. `goal` 이 비어 오면 Hermes 의 `preview` 를 그 자리에 싣는다.

**`started` 는 흐름으로 도는 turn 에서도 뿌리 실행의 번호를 싣는다.**
중지는 뿌리 번호로 보내고 Control Plane 이 그 아래를 찾아 멈춘다.
화면은 마지막으로 받은 `started` 의 번호를 쓴다.

#### 도구 `detail` 을 싣는 대상

**도구의 명령 원문은 `ADMIN` 역할에게만 보낸다.** 근거는 [ADR-038](adr/ADR-038-도구의-명령-원문은-관리자에게만-보내고-사용자에게는-사람-말로-보인다.md) 에 있다.
저장은 그대로 하고 응답을 만들 때 뺀다.

| 받는 사람 | 도구 | `detail` |
| --- | --- | --- |
| `ADMIN` 역할 | 모든 도구 | 저장된 값 |
| `MEMBER` 역할 | `web_search`, `vision_analyze` | 저장된 값 |
| `MEMBER` 역할 | 그 밖의 도구 | `null` |

두 경로가 같은 판정을 쓴다. `usage/application` 의 `ToolDetailPolicy` 가 판정을 갖는다.

| 경로 | 판정하는 자리 |
| --- | --- |
| 대화 스트림의 `tool` 사건 | `ChatController` 가 `ChatService` 에 넘기는 사건 소비자 |
| `GET /api/v1/usage/executions/{id}/tree` 의 `TOOL_STARTED`, `TOOL_COMPLETED` 사건 | `ExecutionTreeService` 가 `ExecutionEventView` 를 만들 때 |

도구 사건이 아닌 사건의 `detail` 은 모두에게 싣는다. 하위 에이전트의 목표, 실패 코드, 넘어간 모델 이름이다.
판정은 도구 이름 전체로 한다. `mcp__{서버}__web_search` 처럼 다른 MCP 서버가 같은 이름을 붙인 도구는 공개하지 않는다.

### 중지

`chat/application` 의 `TurnCancellation` 이 도는 turn 마다 중지 표시를 하나 갖는다. 뿌리 실행 번호가 열쇠다.
`ChatService` 가 turn 을 시작할 때 등록하고 끝날 때 지운다.
실행 줄을 만들면 열쇠를 그 실행 번호로 옮긴다.
중지 경로가 그 표시를 세우고, 흐름은 자식을 시작하기 전과 합치기 전에 그것을 본다.
같은 대화에 도는 turn 이 있는지도 여기서 본다. 보내기와 다시 생성이 `CONVERSATION_BUSY` 를 판정하는 자리다.
같은 Hermes session 에 두 turn 이 겹쳐 들어가면 어느 답이 어느 질문의 것인지 모델도 모른다.

`ChatService` 와 흐름이 서로를 부르지 않게 표시를 따로 둔다.

| 규칙 | 까닭 |
| --- | --- |
| 등록과 해제는 `ChatService` 만 한다. 흐름 경로도, 한 번에 받는 경로도 등록한다 | 등록되지 않은 turn 은 중지도 `CONVERSATION_BUSY` 도 받지 못한다 |
| 같은 대화에 도는 turn 이 있는지 보는 것과 등록하는 것을 한 번에 한다 | 둘 사이에 다시 생성 두 개가 함께 들어오면 같은 답을 가리키는 판이 둘 생긴다 |
| Hermes 에 제출한 직후 run 번호를 등록한다. `AgentRunner` 는 제출 뒤 부르는 콜백으로 알린다 | 흐름의 Chief 는 `AgentRunner` 안에서 제출된다. 알리지 않으면 Chief 를 멈출 수 없다 |
| run 을 등록할 때 이미 중지 표시가 서 있으면 그 자리에서 중지를 보낸다 | `started` 뒤 제출 전에 들어온 중지가 사라지지 않게 한다 |
| run 마다 중지를 보냈는지 적는다. 다시 부르면 보내지 못한 run 에만 다시 보낸다 | 보내다 실패한 뒤 다시 눌러도 아무 일이 없으면 안 된다 |
| 중지를 보낸 뒤 스트림이 10초 안에 끝나지 않으면 중계 쪽이 스트림을 닫고 상태 조회로 넘어간다 | 취소된 run 의 스트림이 닫히는지 실제 Hermes 로 확인하지 못했다 |

Hermes 에 중지를 보내는 것은 `hermes` 가, 누구의 무엇을 멈출지 정하는 것은 `chat` 이 한다.
뿌리 아래에서 도는 실행은 `root_execution_id` 로 찾는다.

**다른 창이 도는 turn 을 물을 때도 이 표시를 본다.** 실행 줄의 상태로 보지 않는다.
흐름으로 도는 turn 은 Chief 가 끝나면 뿌리 줄이 `SUCCEEDED` 가 되고 그 뒤에 자식이 돈다.
줄 상태로 보면 자식이 도는 동안 「돌지 않는다」고 답하게 된다. 중지 판정과 같은 까닭이다.
`running` 경로는 대화의 표시가 가진 뿌리 실행 번호를 돌려주고, `startedAt` 은 그 실행 줄에서 읽는다.
표시가 있는데 실행 번호가 아직 붙지 않았으면 `running` 은 true 이고 `executionId` 와 `startedAt` 은 null 이다.

**표시는 한 프로세스의 메모리에 있다.** Control Plane 이 하나라서 그것으로 된다.
둘 이상으로 늘리면 이 표시를 데이터베이스로 옮겨야 한다.

## 실행 사건

Hermes 가 스트림으로 보내는 사건을 우리 이름으로 옮겨 `execution_event` 에 적는다.
화면은 우리 이름만 읽고 Hermes 의 원래 이름을 알지 않는다.

- 옮겨 적는 자리가 한 곳이다. Hermes 가 이름을 바꾸면 그 한 곳만 고친다.
- 받은 payload 를 통째로 넣지 않고 필요한 칸만 고른다.
  도구가 읽어 온 문서 전체가 사건에 실려 오는 것을 그대로 저장하지 않기 위해서다.
- 모르는 사건은 버리고 버렸다는 사실만 로그로 남긴다.

실시간 스트리밍은 그대로 둔다.
화면에 흘리는 것과 저장하는 것이 같은 스트림을 두 가지로 쓴다.
저장하는 답은 여전히 실행 결과에서 가져온다.

근거는 [`adr/ADR-013-실행-사건은-우리-모델로-정규화해-저장한다.md`](adr/ADR-013-실행-사건은-우리-모델로-정규화해-저장한다.md) 에 있다.

### 실행의 시작과 끝은 Hermes 사건을 기다리지 않는다

`RUN_STARTED` 와 `RUN_COMPLETED` 와 `RUN_FAILED` 는 Control Plane 이 직접 적는다.
Hermes 도 `run.completed` 를 보내지만 그것을 옮겨 적지 않는다.

- 스트림을 열지 않는 경로에도 실행의 시작과 끝이 남아야 한다.
  옮겨 적는 쪽에만 두면 그 경로가 비고, 양쪽에 두면 스트리밍 경로만 두 줄이 된다.
- 우리가 적는 쪽이 `errorCode` 를 알고 있어 담을 것이 더 많다.

옮겨 적는 것은 도구와 하위 에이전트 사건뿐이다.

**사건 저장이 실패해도 중계와 대화는 그대로 이어진다.**
사건은 관측용이고 그것 때문에 답이 끊기면 안 된다.
옮기지 못한 사건과 저장에 실패한 사건은 순서를 소비하지 않아, 번호가 1부터 빈틈없이 이어진다.

### 나무는 메모리에서 잇는다

실행 하나를 그 사건과 자식 실행까지 묶어 낼 때 데이터베이스에서 재귀로 만들지 않는다.
`root_execution_id` 로 한 번에 읽어 메모리에서 잇는다.
한 실행의 자식 수가 많아질 일이 없고, 재귀 질의는 읽기 어렵다.

어느 실행 번호를 주든 그 실행이 속한 나무의 뿌리부터 낸다.
화면이 자식 실행에서 들어와도 전체를 보게 하기 위해서다.

**순환이 생길 수 있는 자리가 둘이고 대응이 다르다.**
잘못 적힌 `parent_execution_id` 하나로 응답이 끝나지 않을 수 있다.

| 어디 | 무엇을 하나 | 어디에 알리나 |
| --- | --- | --- |
| 뿌리를 찾아 올라가는 길 | 여덟 번에서 멈추고 마지막으로 닿은 실행을 뿌리로 삼는다 | 나무의 `truncated` |
| 자식을 붙여 내려가는 길 | 이미 붙인 실행을 다시 붙이지 않고 여덟째 깊이에서 멈춘다 | 그 노드의 `truncated` |

**`truncated` 가 두 곳에 있고 뜻이 다르다.**
나무의 것은 「이 나무 어딘가를 잘랐다」 이고 노드의 것은 「이 노드 아래를 잘랐다」 이다.
화면이 줄을 그리는 근거는 노드의 값이다. 나무의 값은 잘린 자리를 가리키지 않는다.

뿌리에서 닿지 않는 실행은 나무에 넣지 않고 로그로 남긴다. 데이터가 어긋난 것이다.
상한에서 일부러 자른 가지는 그 로그에서 뺀다. 원인이 달라서다.

남의 실행은 없는 것과 같은 오류로 응답한다.
뿌리를 찾아 올라간 뒤에도 주인을 다시 확인한다.

## web 화면 구조

아래가 화면이고, 나머지는 이 화면들을 이루는 부품이다.

| 경로 | 화면 |
| --- | --- |
| `/` | 새 대화 화면 |
| `/chat/{id}` | 대화 하나. `{id}` 는 대화의 공개 식별자 |
| `/c/{번호}` | 옛 주소. 주인이면 `/chat/{id}` 로, 없거나 남의 대화면 `/` 로 넘긴다. 그 밖의 실패는 오류 화면이다 |
| `/signin` | 로그인 |
| `/usage` | 사용량 |
| `/memory` | 개인과 그룹 공용 Memory |
| `/executions/{id}` | 실행 하나의 도구와 하위 에이전트 나무 |
| `/agents` | 에이전트 목록과 「새 에이전트」. `ADMIN` 에게는 다른 사람의 비공개와 꺼진 에이전트, 운영 profile 등록이 더 보인다 |
| `/agents/{code}` | 에이전트 하나의 설정. 성격, 도구, 스킬, 주인과 `ADMIN` 에게 공개와 삭제, `ADMIN` 에게만 관리 절 |
| `/agents/{code}/skills/new` | 새 스킬. 주인과 `ADMIN` |
| `/agents/{code}/skills/{name}` | 올린 스킬 편집. 주인과 `ADMIN` |
| `/admin/agents` | 옛 주소. `/agents` 로 넘긴다 |

### 에이전트 화면

에이전트의 설정은 메뉴 「에이전트」 한 곳에 모은다.
예전에는 모두가 쓰는 「에이전트」 와 `ADMIN` 만 쓰는 「에이전트 관리」 가 따로 있어 같은 에이전트의 설정이 두 메뉴에 흩어졌다.

| 자리 | 누구에게 | 무엇 |
| --- | --- | --- |
| 목록 | 모두 | 내가 쓸 수 있는 에이전트. 누르면 상세로 간다. 목록 위에 「새 에이전트」 가 있다. 이름과 공개 범위만 받는다 |
| 목록 | `ADMIN` | 위에 더해 다른 사람의 비공개 에이전트와 꺼 둔 에이전트도 보인다. 「다른 사람 것」, 「꺼짐」 표시를 붙인다. 운영에서 만든 profile 을 등록하는 경로도 있다 |
| 상세의 성격 | 그 에이전트를 쓸 수 있는 사람. 고치는 것은 주인과 `ADMIN` | 읽기와 쓰기 권한은 [「페르소나」](#페르소나) 의 「누가 고칠 수 있나」 와 같다 |
| 상세의 도구 | 그 에이전트의 주인과 `ADMIN` | 도구의 켜짐을 보고, 주인은 주인 등급을, `ADMIN` 은 모든 등급을 바꾼다. 다른 사람의 비공개 에이전트는 관리자 경로로 읽고 쓴다 |
| 상세의 스킬 | 그 에이전트를 쓸 수 있는 사람. 고치는 것은 주인과 `ADMIN` | 스킬마다 이름, 설명, 「올린 스킬」 이나 「Hermes 기본」 표시, 켜짐. 관리하는 사람에게는 켜고 끄기, 호출 수와 마지막 호출, 올린 스킬의 편집과 삭제, 「스킬 추가」. 아니면 `/이름` 으로 부를 수 있다는 안내 |
| 스킬 편집 `/agents/{code}/skills/{name}` | 주인과 `ADMIN` | `SKILL.md` 본문과 미리보기, 참고 파일 목록과 올리기. 별도 페이지다 |
| 상세의 공개와 삭제 | 주인과 `ADMIN` | 나만과 그룹 공개를 바꾸는 단추, 확인 창을 거치는 삭제 |
| 상세의 관리 절 | `ADMIN` | 사용 여부, Hermes 주소. 모델은 에이전트가 아니라 대화가 고른다 |

**`ADMIN` 이라도 다른 사람의 비공개 에이전트는 성격을 읽지 못한다.**
그 상세에는 공개와 삭제 절과 관리 절이 보이고, 성격 자리에는 주인만 볼 수 있다는 안내를 둔다.
backend 의 읽기 기준(`AgentService.requireReadable`)은 바꾸지 않는다.
관리 절이 쓰는 `/api/v1/admin/agents` 경로들도 그대로다.

**에이전트 등록과 Hermes 주소 수정은 저장하기 전에 그 주소가 닿는지 본다.**
그 에이전트의 profile key 로 `/v1/capabilities` 를 부른다.
그 profile 의 key 가 없으면 `HERMES_PROFILE_KEY_MISSING`, 주소가 200 으로 답하지 않으면 `VALIDATION_FAILED` 로 거절하고 저장하지 않는다.
틀린 주소나 profile 로 저장하면 그 에이전트의 모든 대화가 실패하고, 화면에는 Hermes 에 닿지 못했다는 것만 보인다.
`AgentEndpointProbe` 가 이 확인을 한다.

### 사용량 화면의 탭

`/usage` 는 탭 넷으로 나눈다. 고른 탭은 주소 `?tab=` 에 남아 새로 고쳐도 같은 탭이 열린다.

| 탭 | 담는 것 |
| --- | --- |
| 요약(`summary`, 기본) | 아래 표의 합계 칸과 「어디에 썼나」 |
| 실행 기록(`executions`) | 아래 표의 「실행 기록」. 줄마다 그 실행에서 쓴 스킬 이름을 작게 붙인다 |
| 스킬(`skills`) | 내가 부른 스킬. 스킬마다 횟수와 마지막 호출, 누르면 그 대화로 간다. `GET /api/v1/usage/skills` 를 읽는다 |
| 입력 지문(`fingerprints`) | 아래 표의 「무엇이 달라졌나」 |

#### 탭 안의 절

각 절은 위 탭 안에 그대로 옮긴다.

| 절 | 무엇을 보이나 | 그리지 않는 때 |
| --- | --- | --- |
| 제목 없는 합계 칸 | 실제로 나간 돈과 API 로 돌렸다면의 두 금액, 실행 수,<br>가격을 찾지 못한 실행 수 (있을 때만) | 합계를 불러오지 못했을 때 |
| 어디에 썼나 | 에이전트, 모델, 날짜, 지문 네 축 중 고른 하나의 합계 | 첫 조회가 실패했을 때.<br>그 자리에 실패 문구만 그린다 |
| 무엇이 달라졌나 | 지문별 실행당 평균 비용 | 견줄 지문이 둘 미만일 때 |
| 실행 기록 | 실행 한 줄씩. 모델과 요청한 effort, 토큰과 문맥 글자 수와 두 금액 | 없다. 기록이 없으면 빈 상태를 그린다 |

위 표는 절 하나씩의 조건이다.
**실행 목록 조회가 실패하면 페이지 전체가 실패 문구 한 줄이 되고 네 절이 함께 사라진다.**

**「어디에 썼나」 는 그 달에 실행이 없어도 절과 축 고르는 단추를 그린다.**
표 자리에만 「기록이 없다」 가 뜬다. 절이 통째로 사라지는 것은 첫 조회가 실패했을 때뿐이다.
그 둘을 같은 모양으로 그리면 읽는 사람이 쓴 것이 없는 것과 못 불러온 것을 구분하지 못한다.

**「무엇이 달라졌나」 는 지금 한 번도 그려지지 않는다.**
`runtime_fingerprint` 를 채우는 경로가 없어 지문 축이 늘 빈 목록이기 때문이다.
결함이 아니라 값이 들어오기 시작할 때 고칠 곳이 없도록 먼저 만들어 둔 것이다.
그 칸을 왜 비워 두는지는 [`data-schema.md`](data-schema.md) 의 「agent_execution」 절이 갖는다.

### 합계 질의는 다른 도메인의 엔티티를 조인해도 된다

`usage` 의 축별 합계가 에이전트 이름을 얻으려고 `agent` 의 엔티티를 JPQL 로 조인한다.
줄마다 에이전트를 다시 찾으면 축 하나에 질의가 실행 수만큼 늘어나기 때문이다.

층 흐름 규칙은 한 도메인 **안**의 방향만 정한다. 도메인끼리의 조인을 금지하지 않는다.
읽기만 하는 합계 질의에 한해 이 결합을 허용한다.
쓰는 쪽은 그렇지 않다. 다른 도메인의 상태를 바꿀 때는 그 도메인의 서비스를 부른다.

### 디렉터리

`app/` 은 경로와 서버에서 읽는 것만 담고, 화면을 이루는 부품은 `components/` 에 둔다.
`components/ui/` 는 어느 화면에도 속하지 않는 조각이고, 그 밖의 파일은 한 화면의 부품이다.

**서버에서 데이터를 읽는 화면 경로는 `loading.tsx` 를 함께 둔다.** 옮기는 동안 보일 뼈대다.
뼈대는 그 화면의 바깥 틀(최대 폭, 제목 자리, 목록이나 표의 줄 높이)을 `page.tsx` 와 같게 둔다.
`/` 에는 두지 않는다. 로그인 확인 말고는 서버에서 읽는 데이터가 없다.

**`components/ui/` 를 shadcn/ui 에서 복사한 부품으로 채우기로 했다.** 근거는
[ADR-023](adr/ADR-023-화면-부품은-shadcn-ui-를-저장소에-복사해-쓴다.md) 에 있다.
대화상자, 메뉴, 서랍, 알림 풍선을 직접 만들지 않는다.

```
web/src/
  app/                    경로, 서버 컴포넌트, 서버 라우트
  components/
    ui/                   shadcn/ui 에서 받은 부품과 우리가 만든 조각
    shell/                모든 화면을 감싸는 사이드바와 대화 목록
    chat/                 대화 화면의 부품
      activity/           작업 과정 블록과 패널
    usage/                사용량 화면의 부품
    execution/            실행 나무 화면의 부품
    agent/                에이전트와 성격 화면의 부품
    admin/                관리 화면의 부품
  lib/                    Control Plane 호출과 형식 변환
```

### 색과 간격은 테마 토큰이 소유한다

색과 간격을 `style={{ background: "var(--muted)" }}` 처럼 인라인으로 적지 않는다.
`globals.css` 의 `@theme` 에 토큰을 선언하고 `bg-muted` 나 `ml-3` 같은 Tailwind 클래스로 쓴다.

인라인 스타일에는 `hover:` 와 `md:` 와 `disabled:` 를 붙일 수 없다.
그래서 인라인으로 적은 값 하나가 그 요소의 반응형과 상태 변화를 함께 막는다.
색이든 여백이든 이유가 같다.

토큰은 `globals.css` 한 곳에서만 선언한다. 밝기 모드도 거기서 갈린다.

토큰 이름은 shadcn 의 이름 체계를 쓴다. 옛 이름과의 대응표와 근거는 [ADR-023](adr/ADR-023-화면-부품은-shadcn-ui-를-저장소에-복사해-쓴다.md) 이 갖는다.

검사 명령과 예외는 [`web/AGENTS.md`](../web/AGENTS.md) 가 갖는다.

### 우리 화면의 정체성

브랜드 색은 테라코타다. 기본값이 `#B05A3C` 다.
집을 연상하게 하는 따뜻한 흙색이고, 집에서 쓰는 비서라는 성격과 맞다.

| 토큰 | 쓰는 곳 |
| --- | --- |
| `primary` | 보내기 단추, 고른 항목, 링크, 상태 표시 |
| `primary-strong` | 눌렀을 때와 마우스를 올렸을 때 |
| `primary-soft` | 내 말풍선 배경 |
| `primary-foreground` | 브랜드 색 위에 얹는 글자 |
| `destructive` | 되돌릴 수 없는 동작의 단추와 오류 글자 |

브랜드 색을 본문 글자에 쓰지 않는다.
읽는 글이 색을 가지면 무엇이 누를 수 있는 것인지 알 수 없게 된다.

글꼴은 Pretendard 다. 한국어 화면에서 인상의 절반을 글꼴이 정한다.

**글꼴 파일을 우리가 들고 배포한다.** 외부 CDN 에서 받지 않는다.
가족만 쓰는 화면이라 화면을 열 때마다 외부에 요청이 나가는 것을 원하지 않는다.
망이 끊긴 집에서도 같은 화면이 나와야 한다.

대화 화면의 구조는 ChatGPT 의 배치를 따른다.

| 요소 | 규칙 |
| --- | --- |
| 화면 틀 | 왼쪽 사이드바 하나. 위쪽 가로 메뉴를 두지 않는다 |
| 대화 열 | 가운데 정렬한 좁은 열. 화면 폭을 다 쓰지 않는다 |
| 내가 보낸 줄 | 오른쪽 정렬 말풍선. 폭은 열의 70% 까지 |
| 비서가 답한 줄 | 열 전체 폭. 배경과 테두리가 없다 |
| 입력창 | 둥근 알약 하나. 그 안 오른쪽에 원형 보내기 단추. 답을 만드는 동안 중지 단추로 바뀐다. 알약 아래 줄에 모델과 effort 를 고르는 단추 하나 |
| 작업 과정 | 답 위에 접힌 블록 하나. 펼치거나 오른쪽 패널로 연다 |
| 메시지 동작 | 답 아래 한 줄. 복사, 다시 생성, 판 넘기기 |
| 새 대화 | 입력창이 가운데. 위에 에이전트 카드, 아래에 추천 질문 |

**사이드바의 대화 목록은 화면 틀이 갖는다.** 대화 화면이 갖지 않는다.
다른 화면에서도 목록이 보여야 하고, 대화 화면은 보낸 뒤 목록에 알리기만 한다.
`components/shell/` 의 목록 context 가 목록을 읽고, 대화 화면이 그 context 의 갱신 함수를 부른다.

비서의 답만 폭을 다 쓰는 이유는 표와 코드 블록이 오기 때문이다.
좁은 말풍선에 넣으면 그 안에서 가로로 밀어야 읽힌다.

### 화면 밖에서 오는 글은 마크다운으로 읽는다

에이전트의 답은 마크다운이다.
`react-markdown` 과 `remark-gfm` 으로 그려 표와 목록과 코드 블록이 제 모양으로 보이게 한다.

**들어온 HTML 을 그대로 그리지 않는다.** `react-markdown` 의 기본값이 그렇고 그 기본값을 바꾸지 않는다.
에이전트의 답은 우리가 쓴 글이 아니라 모델이 만든 글이고, 도구가 읽어 온 남의 글이 섞일 수 있다.

### 화면을 검증하는 방법

테스트는 확인하는 대상을 나눠 둔다.

| 위치 | 확인하는 것 | 띄우는 것 |
| --- | --- | --- |
| `test/e2e/` | Control Plane 의 응답과 권한과 기록 | 가짜 Hermes, 백엔드, 데이터베이스 |
| `test/browser/` | 화면의 배치와 동작 | 위에 더해 웹과 Chromium |
| `test/unit/` | 화면이 쓰는 순수 함수 | 없음 |

브라우저 테스트는 `mobile` 과 `desktop` 두 폭에서 돈다. 각각 390px 와 1280px 다.

**운영 코드에 시험용 문을 만들지 않는다.**
로그인은 테스트가 NextAuth 세션 쿠키를 직접 만들어 넣는다.
테스트일 때만 켜지는 우회를 두면 그 문이 운영에도 남는다.

## 사용자를 더할 때

**관리자가 화면에서 한 번 더하면 끝난다.** 홈서버에 들어가지 않는다.

| 누가 | 무엇을 |
| --- | --- |
| 관리자 | 관리 화면에서 이메일과 이름과 profile 이름을 적는다 |
| Control Plane | 허용 목록에 넣고, Hermes profile 을 만들고, key 와 그 profile 에 묶인 MCP 토큰을 넣는다. 만들기 경로가 에이전트 만들기와 같아 MCP 등록과 서명 plugin 도 함께 붙는다 |
| 그 사람 | 로그인한다. 그때 `app_user` 와 에이전트가 생긴다 |

Google 동의 화면의 테스트 사용자에 주소를 더하는 것만 사람이 따로 한다.
그 화면은 Google 계정 소유자만 고칠 수 있다.

순서와 어긋나는 지점은 [`flow.md`](flow.md) 의 「사람을 더할 때」가 갖는다.
profile 을 사람마다 나누는 근거는
[`adr/ADR-002-profile은-나누고-ai-계정은-가족이-함께-쓴다.md`](adr/ADR-002-profile은-나누고-ai-계정은-가족이-함께-쓴다.md) 에 있다.
Control Plane 이 Hermes 를 고치는 호출을 하게 된 근거는
[`adr/ADR-018-사람을-더하는-것을-control-plane-이-끝낸다.md`](adr/ADR-018-사람을-더하는-것을-control-plane-이-끝낸다.md) 에 있다.

### 어느 패키지가 무엇을 하나

| 패키지 | 더하는 것 |
| --- | --- |
| `people` | 허용 목록, 사람을 더하는 흐름 전체의 조립 |
| `hermes` | 대시보드 호출과 key 파일 쓰기 |
| `user` | 첫 로그인에 에이전트까지 만든다 |
| `agent` | 지금 있는 등록 경로를 그대로 쓴다 |

**`people` 이 순서를 안다.** 허용 목록에 넣고 profile 을 만들고 key 를 넣는 차례와,
중간에 실패했을 때 되돌리는 역순이 그 패키지 하나에 있다.
`hermes` 는 부르는 방법만 알고 순서를 모른다.

### 첫 에이전트의 과금 설정은 `people` 이 갖는다

첫 로그인에 만드는 에이전트의 `cost_mode` 와 `credential_scope` 를 설정에서 읽는다.

| 설정 | 기본값 |
| --- | --- |
| `assistant.people.default-cost-mode` | `SUBSCRIPTION` |
| `assistant.people.default-credential-scope` | `SHARED_HOUSEHOLD` |

`people` 패키지가 갖는다. Hermes 를 부르는 값이 아니라 `hermes` 쪽에 두지 않는다.

두 값이 사람마다 다르지 않은 근거는
[`adr/ADR-002-profile은-나누고-ai-계정은-가족이-함께-쓴다.md`](adr/ADR-002-profile은-나누고-ai-계정은-가족이-함께-쓴다.md) 에 있다.
profile 은 사람마다 나누고 AI 계정은 가족이 함께 쓴다.

에이전트는 실행에 쓸 모델을 갖지 않는다.
첫 로그인에 에이전트를 만들 때도 Hermes 에서 모델을 읽지 않는다.

### key 를 두 곳에 같이 쓴다

같은 값을 Hermes 의 `.env` 와 우리 key 디렉터리에 각각 쓴다.
한쪽만 들어가면 실행할 때 401 이 난다.

`HermesProfileKeyStore` 가 지금 읽기만 한다. 쓰는 경로를 그 옆에 둔다.
**읽는 규칙과 쓰는 규칙이 같은 파일에 있어야 파일 이름 규칙이 갈리지 않는다.**

## 비밀값을 두는 곳

| 값 | 두는 곳 |
| --- | --- |
| 사용자의 AI credential | 그 사람의 Hermes profile `.env` |
| profile 의 API server key | 홈서버의 mode 600 파일. 파일 이름이 profile 이름이다 |
| Hermes 대시보드를 부를 토큰 | Control Plane 의 환경 변수와 그 plugin 의 환경 변수 |
| 웹과 Control Plane 이 나눠 가지는 HMAC 비밀값 | 두 서비스의 환경 변수 |

데이터베이스에는 어떤 비밀값도 넣지 않는다.
`agent` 는 profile 이름과 주소만 적는다.
profile key 와 AI credential 은 계속 홈서버 파일에 둔다.

## 문서

| 문서 | 담는 것 |
| --- | --- |
| [`flow.md`](flow.md) | 화면 전환과 호출 순서 |
| [`data-schema.md`](data-schema.md) | 표와 칸의 뜻 |
| [`hermes/README.md`](hermes/README.md) | Hermes 확장 지점 |
| [`adr/INDEX.md`](adr/INDEX.md) | 되돌리기 어려운 결정 |

## 아직 만들지 않은 것

- Hermes 안의 `delegate_task` 하위 에이전트가 자기 실행 줄을 남기는 경로.
  그 하위 에이전트는 Hermes 안에서만 돌고 사건으로만 보인다.
  우리 실행 줄이 생기는 자식은 `agent_delegate`, 흐름의 하위 실행, Memory 제안이다
- `agent_stop` 이 그 실행 아래의 실행까지 멈추는 것. 지금은 그 실행만 멈춘다
- 사용자 전체의 동시 위임 한도. 지금은 뿌리당 한도와 서버 전체 한도만 있다
- MCP `agent_*` 도구(`agent_list`, `agent_delegate`, `agent_status`, `agent_stop`)와 그것을 처리하는 `AgentDelegationService`, `DelegationProperties`.
  지금은 바탕(「MCP 요청자」 의 판정, `DelegationKey`, 실행 줄의 session 칸)만 있다
- `assistant.mcp.legacy-user-tokens` 설정과 `agent_token.user_id` 칸을 지우는 것. 운영의 모든 토큰이 profile 에 묶인 뒤 지운다([ADR-032](adr/ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md) 의 「옛 토큰에서 옮겨 가는 길」)

- 스킬 커맨드([「스킬 커맨드」](#스킬-커맨드)): `chat/application/SkillCommand`, `SKILL_COMMAND_UNKNOWN`, `COMMAND` 이력 적기, 입력창의 `/` 목록.
  지금은 `SkillUseRecorder.recordCommand` 와 `SkillUseSource.COMMAND` 만 있다

SSE 중계와 스트리밍은 끝났다.
`HermesRunEventStream` 이 받아 `ChatService.stream` 이 화면으로 중계한다.
