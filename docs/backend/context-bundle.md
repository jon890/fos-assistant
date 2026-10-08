# 문맥 묶음

Control Plane 이 여러 기록에서 모은 문맥의 항목 모델, source 마다의 판정, Hermes 에 넘기는 형식, 로그와 저장 규칙을 갖는다.
결정은 [ADR-071](../adr/ADR-071-여러-출처의-문맥은-항목마다-출처와-권한과-신선도를-지닌-묶음으로-조립한다.md) 에 있다.

Memory 의 층과 예산과 `memory_read` 는 [`memory.md`](memory.md) 가 그대로 갖는다. 이 문서는 그 위에 얹는 항목 모델만 갖는다.

## 항목의 칸

| 칸 | 타입 | 뜻 |
| --- | --- | --- |
| `source` | enum | 아래 「참여하는 source」 의 값 하나 |
| `ref` | 문자열 | 원래 기록의 참조. `memory:<번호>`, `execution:<번호>`, `connector_action:<공개 식별자>`, `follow_up:<공개 식별자>`, `conversation:<공개 식별자>` |
| `scope` | `USER`, `GROUP` | 원래 기록의 범위. 범위가 없는 기록은 `USER` 다 |
| `ownerUserId` | 숫자 | `USER` 항목의 주인. `GROUP` 이면 비어 있다 |
| `sensitivity` | `NORMAL`, `SENSITIVE` | 원래 기록의 값. 칸이 없는 기록은 아래 표의 값이다 |
| `trust` | `USER_APPROVED`, `CONTROL_PLANE`, `AGENT`, `EXTERNAL` | 그 글을 누가 썼는가 |
| `asOf` | 시각 | 그 내용이 참이던 시각 |
| `freshness` | `FRESH`, `STALE`, `UNKNOWN` | 아래 「신선도」 가 정한다 |
| `bodyMode` | `INLINE`, `TITLE_ONLY`, `OMITTED` | 본문을 싣는 방식. `OMITTED` 는 예산이나 민감도 때문에 빠진 항목이다 |
| `conflictsWith` | 참조 목록 | Control Plane 이 구조로 알 수 있는 충돌 상대. 대부분 비어 있다 |
| `title`, `body` | 문자열 | 글로 옮길 때만 쓴다. 저장하지 않고 로그에 내지 않는다 |

**항목 타입의 `toString` 은 `source` 와 `ref` 만 낸다.** Java record 의 기본 `toString` 은 모든 칸을 내므로, 항목이나 묶음을 로그에 넘기면 본문이 그대로 남는다.
`AssembledContext` 도 같은 이유로 `toString` 이 `instructions` 를 내지 않고 글자 수와 항목 수만 낸다.

## 참여하는 source

| `source` | 원래 기록 | 판정 | `sensitivity` | `trust` | `bodyMode` | 쓰는 곳 |
| --- | --- | --- | --- | --- | --- | --- |
| `MEMORY_ALWAYS` | `memory` 의 `ALWAYS` | ADR-053 의 세 조건 | 원래 값. `SENSITIVE` 는 이 층에 오지 못한다 | `USER_APPROVED` | `INLINE` | 대화 turn 의 `instructions` |
| `MEMORY_INDEX` | `memory` 의 `SEARCH` | ADR-053 의 세 조건 | 원래 값 | `USER_APPROVED` | `TITLE_ONLY` | 대화 turn 의 `instructions` |
| `DELEGATION_RESULT` | 끝난 위임 실행의 `output_text` | 그 대화의 주인. `AgentExecutionRepository.findUndeliveredResults` 의 조건대로 `SUCCEEDED` 나 `FAILED` 이고 아직 전하지 않았으며 부모가 루트 turn 인 위임만 | `SENSITIVE` | 옛 커넥터 에이전트의 답이면 `EXTERNAL`, 아니면 `AGENT` | `INLINE` | 자동 turn 의 `input` |
| `CONNECTOR_RESULT` | `connector_action` 의 `result_text` | 그 대화의 주인 | `SENSITIVE` | `EXTERNAL` | `INLINE`. `UNKNOWN` 이면 `OMITTED` | 자동 turn 의 `input` |
| `EXECUTION_STATE` | `agent_execution` 의 상태와 시각 | 실행 줄의 `user_id` | `NORMAL` | `CONTROL_PLANE` | 본문이 없다 | 지금 화면과 먼저 알리기 |
| `FOLLOW_UP` | `follow_up` | 주인 | `SENSITIVE` | 사람이 받아들였으면 `USER_APPROVED`, 제안이면 `AGENT` | `TITLE_ONLY` | 지금 화면과 먼저 알리기 |

**`EXECUTION_STATE` 와 `FOLLOW_UP` 은 대화 turn 에 싣지 않는다.** 지금 화면과 먼저 알리기가 「왜 보였는가」 의 `sources` 에 이 `source` 이름과 `ref` 형식을 쓴다. 판정은 `attention` 이 요청자의 기록만 읽는 조회로 하고, 이 문서의 항목 타입을 import 하지 않아도 된다. 대화마다 실으면 할 일이 지식처럼 쓰여 새 Memory 층이 된다([ADR-073](../adr/ADR-073-할-일은-에이전트가-제안하고-사람이-받아들인-것만-챙긴다.md)).

**커넥터의 실시간 데이터는 source 가 아니다.** Control Plane 은 커넥터를 직접 부르지 않는다.
일정 같은 커넥터 데이터는 연결을 붙인 에이전트가 turn 안에서 직접 부른 도구의 결과로 들어온다. 그 결과는 묶음을 거치지 않고 Hermes 의 도구 결과 자리에 놓이며 `fos-ctx` 가 `<external-data>` 로 감싼다([`../hermes/fos-ctx.md`](../hermes/fos-ctx.md)).
묶음에 드는 것은 승인한 호출의 결과(`CONNECTOR_RESULT`)와 남아 있는 옛 커넥터 에이전트의 위임 결과(`DELEGATION_RESULT`)뿐이다.
일정 커넥터가 생기면 그 결과를 읽는 source 를 이 표에 더한다.

**옛 커넥터 에이전트의 실행에는 묶음을 주지 않는다.** `ChatService` 와 `AgentRunner` 가 지금처럼 빈 문맥으로 돌린다([ADR-045](../adr/ADR-045-커넥터-에이전트는-자기-mcp-서버만-받고-memory-와-control-plane-도구를-받지-않는다.md)). 연결을 붙인 일반 에이전트는 보통 에이전트와 같은 묶음을 받는다.

## 권한과 민감도를 지키는 규칙

ADR-071 의 다섯 규칙을 코드에서 지키는 자리다.

| 규칙 | 지키는 자리 |
| --- | --- |
| source 의 기존 판정을 통과한 것만 든다 | source 마다 기존 조회(`MemoryService.injectableFor`, `AgentExecutionRepository.findUndeliveredResults`, `ConnectorActionService.undeliveredResults`)가 항목을 만든다. 묶음은 판정을 다시 하지 않고 넓히지도 않는다 |
| `SENSITIVE` 본문은 `instructions` 에 싣지 않는다 | `MEMORY_ALWAYS` 는 민감 항목을 받지 못하고, `MEMORY_INDEX` 는 제목만 싣는다. 결과 항목은 `input` 에만 싣는다 |
| `EXTERNAL` 은 감싼다 | `ExternalData.wrap` 하나로 감싼다 |
| `USER` 와 `GROUP` 을 합치지 않는다 | 항목 하나는 원래 기록 하나다. 묶음은 항목을 합치거나 요약하지 않는다 |
| 옛 커넥터 에이전트는 받지 않는다 | `Agent.connectorManaged()` 를 보는 지금의 분기 |

## Hermes 에 넘기는 형식

Runs API 는 `instructions` 와 `input` 두 글을 받는다([`../hermes/runs-api.md`](../hermes/runs-api.md)).

### 공통 실행 지침

`ContextAssembler.withResponseInstructions` 는 Memory 예산 밖에 답변 형식과 도구 호출 지침을 더한다.
대화, Control Plane 이 시작한 자식 실행, 먼저 살펴보기에 같은 도구 호출 지침을 보낸다.
새 profile 과 기존 profile 모두 다음 실행부터 받는다.

Hermes 의 `tool_call` 로 로컬 MCP 도구(`mcp__...`)를 부를 때 `tools` 배열에는 항목 하나만 넣는다.
같은 서버의 읽기 도구라도 여러 개를 한 배열로 보내지 않고 도구마다 별도 호출한다.
이는 Hermes 의 단건 제한에 맞추는 안내다. HTTP 원격 도구 서버의 묶음 규칙은 그 서버의 계약을 따른다.
MCP 연결 전송 방식이 HTTP 라도 Hermes 에 로컬 도구로 등록된 `mcp__...` 는 이 단건 규칙을 따른다.

이 안내는 묶음 실행 기능이나 서버의 권한 검사를 바꾸지 않는다.
모델이 안내를 따르는지는 배포 뒤 실제 실행의 호출과 거절 기록으로 확인한다.
Hermes 가 직접 만든 자식의 지침 상속은 Hermes 의 계약이며, Control Plane 의 자식 실행과 구분한다.

### 항목의 형식

**Memory 항목의 글은 바꾸지 않는다.** 머리말이 이미 출처(그룹과 묻는 사람)를 말하고, 색인의 `[번호]` 가 `memory_read` 의 입력이자 참조다.
그래서 Memory 를 묶음으로 옮겨도, 아래 「충돌 표시」 가 붙는 경우를 빼면 `instructions_hash` 가 바뀌지 않는다.

**결과 항목에는 아래 형식의 출처 머리줄을 붙인다.** 자동 turn 과 다시 전달이 같은 형식을 쓴다.
시각은 `Asia/Seoul` 의 `yyyy-MM-dd HH:mm` 다.

```
맡긴 일의 결과가 도착했다.

[출처: 맡긴 일, 에이전트: 조사 도우미, 실행 번호: 412, 상태: SUCCEEDED, 끝난 시각: 2026-10-03 14:05]
(결과 본문)

승인한 동작의 결과가 도착했다.
[출처: 승인한 동작, 동작: 초안 만들기, 상태: SUCCEEDED, 끝난 시각: 2026-10-03 08:07, 신선도: 오래됨]
이 결과는 6시간보다 전에 끝났다. 지금 상태와 다를 수 있다.
(external-data 로 감싼 결과 본문)
```

`FAILED` 면 상태 뒤에 `, 오류: <코드>` 를 붙인다. 승인 줄에 커넥터가 선언한 오류 계약이 있으면 그 뒤에 `, 오류 코드: <커넥터 코드>, 세부: <이름>=<값>` 을 붙이고, 머리줄과 신선도 안내 아래에 `복구: <안내>` 를 둔다. 도구의 원래 이름과 승인 줄의 공개 식별자는 싣지 않는다.
안내 줄의 기준은 `assistant.context.result-stale-after` 다. 정시면 「N시간」, 정시가 아니면 「N분」 으로 적는다.
사용자가 결과를 다시 전달하는 turn(`ChatService.retryDelivery`)도 같은 형식의 머리줄을 쓰고, 신선도는 다시 전하는 시각으로 판정한다.

결과를 전하는 두 지시가 함께 끝에 붙이는 `TurnIntent.RESULT_HANDLING_RULES` 끝에 아래 글이 있다. 자동 turn 과 다시 전달이 모두 받는다.

> 결과마다 [출처: …] 줄이 있다. 출처가 다른 내용이 서로 어긋나면 하나를 고르지 말고 두 출처와 시각을 함께 말한다.
> 사용자가 받아들인 기억과 외부 결과가 어긋나면 기억을 고치지 말고, 바꿀 것이 있으면 사용자에게 묻는다.
> 신선도가 오래됨인 결과는 지금 상태와 다를 수 있다고 알린다.

## 신선도

| `source` | `asOf` | `FRESH` | `STALE` |
| --- | --- | --- | --- |
| `MEMORY_ALWAYS`, `MEMORY_INDEX` | `updated_at` | 묶음을 만든 시각과의 차이가 collection의 기준 기간 안 | 기준 기간보다 오래됨 |
| `DELEGATION_RESULT` | `finished_at` | 묶음을 만든 시각과의 차이가 `assistant.context.result-stale-after`(기본 6시간) 안 | 그보다 오래됨 |
| `CONNECTOR_RESULT` | `executed_at` | 위와 같다 | 위와 같다 |
| `EXECUTION_STATE` | 읽은 시각 | 늘 | 없다 |
| `FOLLOW_UP` | `updated_at` | 늘 | 없다. 기한이 지난 것은 먼저 알리기가 따로 본다 |

Memory의 기본 기준은 `assistant.context.memory-stale-after`(기본 180일)다.
`assistant.context.memory-collection-stale-after`는 collection 이름을 키, 기간을 값으로 받으며 기본 기준을 덮어쓴다.
예를 들어 `career: 30d`를 지정하면 그 collection의 항목은 수정한 지 30일을 넘었을 때 `STALE`이다.
기준과 정확히 같은 시각은 `FRESH`다. 기준이 0 이하이거나 `updated_at`이 없으면 `UNKNOWN`이다.
기본 기준을 0으로 두면 별도 기준을 지정하지 않은 collection은 `UNKNOWN`이다.

Memory는 실행 조립 시각 하나로 항상 층, 색인 층, 예산 때문에 생략한 항목을 모두 판정한다.
신선도는 내용이 여전히 참이라는 보증이 아니며, 사용자 승인(`trust`)과 별개다.
낡았다는 이유로 항목을 빼거나 Memory를 고치지 않는다. 주입 글과 그 지문도 바꾸지 않는다.
판정 값은 문맥 묶음과 `execution_context_source.freshness`에 남는다. 이미 저장한 실행 기록은 다시 판정하지 않는다.

`asOf` 가 비어 있으면 `UNKNOWN` 이다. 결과 머리줄에 「끝난 시각: 모름」 을 적는다.
결과는 보통 몇 초 안에 전해져 `STALE` 이 되지 않는다. 결과 전달을 다시 하는 경로(#162)가 몇 시간 뒤에 전할 때 `STALE` 이 붙는다.

## 충돌 표시

Control Plane 이 구조로 알 수 있는 충돌만 `conflictsWith` 에 적는다.

| 경우 | 표시 |
| --- | --- |
| 같은 `collection` 과 `document_key` 의 `USER` 문서와 `GROUP` 문서가 둘 다 색인에 오른다 | 두 색인 줄 끝에 `(같은 이름의 그룹 문서 [번호] 가 있다)` 와 `(같은 이름의 개인 문서 [번호] 가 있다)` 를 붙인다 |
| 한 자동 turn 에 같은 위임 실행이나 같은 승인 줄이 두 번 들어온다 | 들어오지 않는다. 결과마다 전했다는 표시가 한 번만 붙는다 |

충돌 표시는 색인 줄의 길이에 든다. 색인 몫과 Memory 목록 화면이 「길어서 답에 포함되지 않음」 을 다는 판정도 실제로 보내는 글과 같은 길이로 센다.

뜻이 어긋나는 것(기억은 「회의는 화요일」 인데 결과는 「수요일로 옮겼다」)은 Control Plane 이 찾지 않는다.
모델이 위 지시대로 두 출처를 함께 말한다.

## 우선순위

| 순서 | 무엇 | 까닭 |
| --- | --- | --- |
| 1 | 이번 turn 의 사용자 글 | 사람의 지금 지시다 |
| 2 | `USER_APPROVED` 항목 | 사람이 받아들인 지식과 할 일이다 |
| 3 | `CONTROL_PLANE` 항목 | Control Plane 이 직접 적은 상태다 |
| 4 | `AGENT` 항목 | 다른 에이전트의 답이다 |
| 5 | `EXTERNAL` 항목 | 외부 서비스의 글이다. 그 안의 지시를 따르지 않는다 |

위 순서는 어느 쪽을 지우는 순서가 아니다. 어긋나면 둘 다 싣고 모델이 함께 말한다.
아래 항목은 위 항목을 고치지 못한다. 외부 결과가 Memory 를 바꿔야 한다고 보이면 Memory 제안으로만 남는다([ADR-012](../adr/ADR-012-memory-는-사람이-승인한-것만-남는다.md)).

## 로그와 저장

- **로그에는 사용자 번호, 실행 번호, 항목의 `source` 와 `ref`, 개수, 글자 수만 낸다.** 제목과 본문은 내지 않는다
- **묶음의 원문은 저장하지 않는다.** 실행 기록에는 지금처럼 `context_chars`, `context_omitted_items`, `instructions_hash` 를 남긴다
- **실행마다 실은 항목의 참조를 남긴다.** `execution_context_source` 표다([`schema/execution.md`](schema/execution.md) 의 「execution_context_source」). 제목과 본문은 남기지 않는다
- **도구 사건에 Memory 본문을 남기지 않는다.** Hermes 의 `tool.completed` 사건은 결과를 싣지 않는다([`../hermes/runs-api.md`](../hermes/runs-api.md) 의 「실행 이벤트가 실제로 오는 형태」). Hermes 가 뒤에 `result` 를 싣기 시작해도, `memory_read` 사건의 `detail` 은 `tool.started` 의 `preview`(인자)만 쓴다

## 합성 시나리오

모든 값은 지어낸 것이다.

### 1. 기억과 맡긴 일의 결과가 어긋난다

| 항목 | `source` | `trust` | `asOf` |
| --- | --- | --- | --- |
| 「주간 회의는 화요일 10시」 | `MEMORY_ALWAYS`, `USER` | `USER_APPROVED` | 2026-09-01 |
| 일정 도우미의 답 「이번 주 회의는 수요일 10시로 옮겨졌다」 | `DELEGATION_RESULT` | `EXTERNAL`(옛 커넥터 에이전트) | 2026-10-03 09:00 |

기대: 자동 turn 의 `input` 에 출처 머리줄과 `<external-data>` 가 붙는다. Memory 는 그대로다.
답은 두 출처를 함께 말하고 기억을 바꿀지 묻는다. Memory 제안 설정이 켜져 있으면 제안이 `PROPOSED` 로만 남는다.

### 2. 다시 전한 결과가 오래됐다

| 항목 | `source` | `asOf` | 묶음을 만든 시각 |
| --- | --- | --- | --- |
| 승인한 「초안 만들기」 의 결과 | `CONNECTOR_RESULT` | 2026-10-03 08:07 | 2026-10-03 17:30 |

기대: 차이가 6시간을 넘어 `STALE` 이다. 머리줄에 `신선도: 오래됨` 과 안내 한 줄이 붙는다.
같은 승인 줄은 다시 실행되지 않는다(ADR-050). 묶음은 결과 글만 다시 싣는다.

### 3. 민감 문서와 그룹 문서가 함께 있다

| 항목 | `source` | `scope` | `sensitivity` | 에이전트의 허용 |
| --- | --- | --- | --- | --- |
| 「건강 기록 요약」 | `MEMORY_INDEX` | `USER` | `SENSITIVE` | `health` collection 을 민감 허용과 함께 받는다 |
| 「집 관리 규칙」 개인 문서 | `MEMORY_INDEX` | `USER` | `NORMAL` | `home` 을 받는다 |
| 「집 관리 규칙」 그룹 문서 | `MEMORY_INDEX` | `GROUP` | `NORMAL` | `home` 을 받는다 |

기대: 민감 문서는 제목만 색인에 오르고 본문은 `memory_read` 로만 읽힌다.
두 「집 관리 규칙」 은 따로 색인에 오르고 서로를 가리키는 충돌 표시가 붙는다.
`home` 을 받지 않는 에이전트의 실행에는 두 「집 관리 규칙」 이 오르지 않는다. 「건강 기록 요약」 은 `health` 를 민감 허용과 함께 받는 에이전트에만 오른다. 빠진 수는 로그에 개수로만 남는다.

### 4. 옛 커넥터 에이전트로 시작한 대화

옮겨 가기 전에 만든 옛 커넥터 에이전트와 직접 대화를 시작한다.
기대: Memory 항목이 하나도 실리지 않는다. 지금과 같다.
연결을 붙인 일반 에이전트로 시작한 대화는 그 에이전트의 collection 에 따라 Memory 항목이 실린다.

## #97 의 계약과 견준 것

| #97 의 계약 | 이 문서 | 충돌 |
| --- | --- | --- |
| 새 memory tier 를 먼저 만들지 않는다 | 묶음은 저장하지 않고 요청마다 만든다. 할 일과 실행 상태는 대화에 싣지 않는다 | 없다 |
| USER/GROUP, ALWAYS/SEARCH/ARCHIVE 를 다시 설계하지 않는다 | Memory 의 층과 판정을 그대로 쓰고 글도 바꾸지 않는다 | 없다 |
| 서비스 토큰은 읽기 전용이다(ADR-056) | 서비스 토큰은 묶음에 쓰이지 않는다 | 없다 |
| 실행은 Hermes 에 맡긴다 | 묶음은 글을 만들 뿐 실행을 시작하지 않는다 | 없다 |
| 민감 본문은 암호화하고 조립기는 풀지 않는다(ADR-055) | `SENSITIVE` 본문을 `instructions` 에 싣지 않는다 | 없다 |
