# 문맥 묶음

Control Plane 이 여러 기록에서 모은 문맥의 항목 모델, source 마다의 판정, Hermes 에 넘기는 형식, 로그와 저장 규칙을 갖는다.
결정은 [ADR-071](../../backend/docs/adr/ADR-071-여러-출처의-문맥은-항목마다-출처와-권한과-신선도를-지닌-묶음으로-조립한다.md) 에 있다.

Memory 의 층과 예산과 `memory_read` 는 [`memory.md`](memory.md) 가 그대로 갖는다. 이 문서는 그 위에 얹는 항목 모델만 갖는다.

## 항목의 칸

칸 이름은 `ContextItem` 이, 값과 그 뜻은 각 enum(`ContextSource`, `ContextTrust`, `ContextFreshness`, `ContextBodyMode`)이 갖는다. 칸마다의 결정은 ADR-071 의 결정 표가 갖는다.
코드만 읽어서는 알기 어려운 것은 아래다.

- `ref` 는 원래 기록의 참조이고 `<표 이름>:<번호나 공개 식별자>` 형식이다. 예: `memory:<번호>`, `execution:<번호>`, `connector_action:<공개 식별자>`, `follow_up:<공개 식별자>`, `conversation:<공개 식별자>`
- `scope` 는 원래 기록의 범위다. 범위가 없는 기록은 `USER` 다
- `sensitivity` 는 원래 기록의 값이다. 칸이 없는 기록은 아래 「참여하는 source」 표의 값이다
- `conflictsWith` 는 대부분 비어 있다. 아래 「충돌 표시」 의 경우에만 찬다

**항목 타입의 `toString` 은 `source` 와 `ref` 만 낸다.** Java record 의 기본 `toString` 은 모든 칸을 내므로, 항목이나 묶음을 로그에 넘기면 본문이 그대로 남는다.
`AssembledContext` 도 같은 이유로 `toString` 이 `instructions` 를 내지 않고 글자 수와 항목 수만 낸다.

## 참여하는 source

| `source` | 원래 기록 | 판정 | `sensitivity` | `trust` | `bodyMode` | 쓰는 곳 |
| --- | --- | --- | --- | --- | --- | --- |
| `MEMORY_ALWAYS` | `memory` 의 `ALWAYS` | ADR-053 의 세 조건 | 원래 값. `SENSITIVE` 는 이 층에 오지 못한다 | `USER_APPROVED` | `INLINE` | 대화 turn 의 `instructions` |
| `MEMORY_FACTS` | `memory` 의 `SEARCH` 가운데 짧은 개인 항목 | ADR-053 의 세 조건과 [`memory.md`](memory.md) 의 「개인 사실 구역」 후보 조건 | `NORMAL` 만 | `USER_APPROVED` | `INLINE` | 대화 turn 의 `instructions` |
| `MEMORY_INDEX` | `memory` 의 `SEARCH` | ADR-053 의 세 조건 | 원래 값 | `USER_APPROVED` | `TITLE_ONLY` | 대화 turn 의 `instructions` |
| `MEMORY_READ` | `memory_read` 가 본문을 내 준 항목 | `memory_read` 의 판정([`memory.md`](memory.md) 의 「본문 읽기가 갈리는 지점」) | 묶음 항목이 아니다 | 묶음 항목이 아니다 | `INLINE` | 조립이 아니라 도구 처리가 `execution_context_source` 에만 덧붙인다(「본문을 읽으면 남는 기록」) |
| `DELEGATION_RESULT` | 끝난 위임 실행의 `output_text` | 그 대화의 주인. `AgentExecutionRepository.findUndeliveredResults` 의 조건대로 `SUCCEEDED` 나 `FAILED` 이고 아직 전하지 않았으며 부모가 루트 turn 인 위임만 | `SENSITIVE` | 옛 커넥터 에이전트의 답이면 `EXTERNAL`, 아니면 `AGENT` | `INLINE` | 자동 turn 의 `input` |
| `CONNECTOR_RESULT` | `connector_action` 의 `result_text` | 그 대화의 주인 | `SENSITIVE` | `EXTERNAL` | `INLINE`. `UNKNOWN` 이면 `OMITTED` | 자동 turn 의 `input` |
| `EXECUTION_STATE` | `agent_execution` 의 상태와 시각 | 실행 줄의 `user_id` | `NORMAL` | `CONTROL_PLANE` | 본문이 없다 | 지금 화면과 먼저 알리기 |
| `FOLLOW_UP` | `follow_up` | 주인 | `SENSITIVE` | 사람이 받아들였으면 `USER_APPROVED`, 제안이면 `AGENT` | `TITLE_ONLY` | 지금 화면과 먼저 알리기 |

**`EXECUTION_STATE` 와 `FOLLOW_UP` 은 대화 turn 에 싣지 않는다.** 지금 화면과 먼저 알리기가 「왜 보였는가」 의 `sources` 에 이 `source` 이름과 `ref` 형식을 쓴다. 판정은 `attention` 이 요청자의 기록만 읽는 조회로 하고, 이 문서의 항목 타입을 import 하지 않아도 된다. 대화마다 실으면 할 일이 지식처럼 쓰여 새 Memory 층이 된다([ADR-073](../adr/ADR-073-할-일은-에이전트가-제안하고-사람이-받아들인-것만-챙긴다.md)).

**커넥터의 실시간 데이터는 source 가 아니다.** Control Plane 은 커넥터를 직접 부르지 않는다.
일정 같은 커넥터 데이터는 연결을 붙인 에이전트가 turn 안에서 직접 부른 도구의 결과로 들어온다. 그 결과는 묶음을 거치지 않고 Hermes 의 도구 결과 자리에 놓이며 `fos-ctx` 가 `<external-data>` 로 감싼다([`hermes/plugins/fos-ctx/README.md`](../../hermes/plugins/fos-ctx/README.md)).
묶음에 드는 것은 승인한 호출의 결과(`CONNECTOR_RESULT`)와 남아 있는 옛 커넥터 에이전트의 위임 결과(`DELEGATION_RESULT`)뿐이다.
일정 커넥터가 생기면 그 결과를 읽는 source 를 이 표에 더한다.

**옛 커넥터 에이전트의 실행에는 묶음을 주지 않는다.** `ChatService` 와 `AgentRunner` 가 지금처럼 빈 문맥으로 돌린다([ADR-045](../../backend/docs/adr/ADR-045-커넥터-에이전트는-자기-mcp-서버만-받고-memory-와-control-plane-도구를-받지-않는다.md)). 연결을 붙인 일반 에이전트는 보통 에이전트와 같은 묶음을 받는다.

## 권한과 민감도를 지키는 규칙

ADR-071 의 다섯 규칙을 코드에서 지키는 자리다.

| 규칙 | 지키는 자리 |
| --- | --- |
| source 의 기존 판정을 통과한 것만 든다 | source 마다 기존 조회(`MemoryService.injectableFor`, `AgentExecutionRepository.findUndeliveredResults`, `ConnectorActionService.undeliveredResults`)가 항목을 만든다. 묶음은 판정을 다시 하지 않고 넓히지도 않는다 |
| `SENSITIVE` 본문은 `instructions` 에 싣지 않는다 | `MEMORY_ALWAYS` 와 `MEMORY_FACTS` 은 민감 항목을 받지 못하고, `MEMORY_INDEX` 는 제목만 싣는다. 결과 항목은 `input` 에만 싣는다 |
| `EXTERNAL` 은 감싼다 | `ExternalData.wrap` 하나로 감싼다 |
| `USER` 와 `GROUP` 을 합치지 않는다 | 항목 하나는 원래 기록 하나다. 묶음은 항목을 합치거나 요약하지 않는다 |
| 옛 커넥터 에이전트는 받지 않는다 | `Agent.connectorManaged()` 를 보는 지금의 분기 |

## Hermes 에 넘기는 형식

Runs API 는 `instructions` 와 `input` 두 글을 받는다([`hermes/docs/hermes-contract.md`](../../hermes/docs/hermes-contract.md)).

### 공통 실행 지침

`ContextAssembler.withResponseInstructions` 는 Memory 예산 밖에 답변 형식과 도구 호출 지침을 더한다.
대화, Control Plane 이 시작한 자식 실행, 먼저 살펴보기에 같은 도구 호출 지침을 보낸다.
새 profile 과 기존 profile 모두 다음 실행부터 받는다.

로컬 MCP 도구는 같은 서버의 읽기 도구라도 각각 호출하도록 안내한다.
Hermes 의 단건 제한과 원격 도구 구분은 [도구 hook 과 승인](../../hermes/docs/hermes-contract.md#tool_call-의-단건-제한)이 갖는다.

이 안내는 묶음 실행 기능이나 서버의 권한 검사를 바꾸지 않는다.
모델이 안내를 따르는지는 배포 뒤 실제 실행의 호출과 거절 기록으로 확인한다.
Hermes 의 네이티브 `delegate_task` 자식은 부모의 실행 지침을 자동 상속하지 않아 이 안내의 적용을 보장하지 않는다.
Control Plane 의 자식 실행과 구분한다. 근거는 [자식 agent 생성](https://github.com/NousResearch/hermes-agent/blob/v2026.9.24/tools/delegate_tool.py#L202-L249)이다.

### 항목의 형식

**Memory 항목의 글은 바꾸지 않는다.** 머리말이 이미 출처(그룹과 묻는 사람)를 말하고, 색인의 `[번호]` 가 `memory_read` 의 입력이자 참조다.
그래서 Memory 를 묶음으로 옮겨도, 아래 「충돌 표시」 가 붙는 경우를 빼면 `instructions_hash` 가 바뀌지 않는다.

**결과 항목에는 출처 머리줄을 붙인다.** 자동 turn 과 다시 전달이 같은 형식을 쓴다.
머리줄의 칸과 시각 형식, `FAILED` 일 때 붙는 오류 칸과 복구 안내, 신선도 안내 줄은 `ResultHeader` 가 갖는다.
도구의 원래 이름과 승인 줄의 공개 식별자는 머리줄에 싣지 않는다.
사용자가 결과를 다시 전달하는 turn(`ChatService.retryDelivery`)도 같은 형식의 머리줄을 쓰고, 신선도는 다시 전하는 시각으로 판정한다.

결과를 전하는 두 지시가 끝에 함께 붙이는 지시 글은 `TurnIntent.RESULT_HANDLING_RULES` 가 갖는다.
출처가 다른 내용이 어긋나면 하나를 고르지 않고 두 출처와 시각을 함께 말하게 한다. 어느 쪽을 믿는지의 차례는 `ContextTrust` 의 값 순서이고, 그 결정은 ADR-071 의 결정 마지막 문단이 갖는다.

## 신선도

| `source` | `asOf` | `FRESH` | `STALE` |
| --- | --- | --- | --- |
| `MEMORY_ALWAYS`, `MEMORY_FACTS`, `MEMORY_INDEX` | `updated_at` | 묶음을 만든 시각과의 차이가 collection의 기준 기간 안 | 기준 기간보다 오래됨 |
| `DELEGATION_RESULT` | `finished_at` | 묶음을 만든 시각과의 차이가 `assistant.context.result-stale-after` 안 | 그보다 오래됨 |
| `CONNECTOR_RESULT` | `executed_at` | 위와 같다 | 위와 같다 |
| `EXECUTION_STATE` | 읽은 시각 | 늘 | 없다 |
| `FOLLOW_UP` | `updated_at` | 늘 | 없다. 기한이 지난 것은 먼저 알리기가 따로 본다 |

Memory 의 기준 기간은 `assistant.context.memory-stale-after` 이고, `assistant.context.memory-collection-stale-after` 에 collection 별 기준을 적으면 그것을 먼저 쓴다.
기본값과 경계 값의 처리는 `ContextProperties` 가 갖는다. 기준이 0 이하이거나 `updated_at` 이 없으면 `UNKNOWN` 이다.

Memory는 실행 조립 시각 하나로 항상 층, 색인 층, 예산 때문에 생략한 항목을 모두 판정한다.
신선도는 내용이 여전히 참이라는 보증이 아니며, 사용자 승인(`trust`)과 별개다.
낡았다는 이유로 항목을 빼거나 Memory를 고치지 않는다. 주입 글과 그 지문도 바꾸지 않는다.
판정 값은 문맥 묶음과 `execution_context_source.freshness`에 남는다. 이미 저장한 실행 기록은 다시 판정하지 않는다.

`asOf` 가 비어 있으면 `UNKNOWN` 이다. 결과 머리줄에 「끝난 시각: 모름」 을 적는다.
결과는 보통 몇 초 안에 전해져 `STALE` 이 되지 않는다. 결과 전달을 다시 하는 경로가 몇 시간 뒤에 전할 때 `STALE` 이 붙는다.

## 충돌 표시

Control Plane 이 구조로 알 수 있는 충돌만 `conflictsWith` 에 적는다.

같은 `collection` 과 `document_key` 의 `USER` 문서와 `GROUP` 문서가 둘 다 색인에 오르면, 두 색인 줄 끝에 서로를 가리키는 표시를 붙인다.
한 자동 turn 에 같은 위임 실행이나 같은 승인 줄은 두 번 들어오지 않는다. 결과마다 전했다는 표시가 한 번만 붙는다.

충돌 표시는 색인 줄의 길이에 든다. 색인 몫과 Memory 목록 화면이 「길어서 답에 포함되지 않음」 을 다는 판정도 실제로 보내는 글과 같은 길이로 센다.

뜻이 어긋나는 것(기억은 「회의는 화요일」 인데 결과는 「수요일로 옮겼다」)은 Control Plane 이 찾지 않는다. 그 까닭은 ADR-071 의 대안 기각이 갖는다.

## 로그와 저장

- **로그에는 사용자 번호, 실행 번호, 항목의 `source` 와 `ref`, 개수, 글자 수만 낸다.** 제목과 본문은 내지 않는다
- **묶음의 원문은 저장하지 않는다.** 실행 기록에는 지금처럼 `context_chars`, `context_omitted_items`, `instructions_hash` 를 남긴다
- **실행마다 실은 항목의 참조를 남긴다.** `execution_context_source` 표다([`backend/docs/data-schema.md`](../../backend/docs/data-schema.md) 의 「execution_context_source」). 제목과 본문은 남기지 않는다
- **도구 사건에 Memory 본문을 남기지 않는다.** Hermes 의 `tool.completed` 사건은 결과를 싣지 않는다([`hermes/docs/hermes-contract.md`](../../hermes/docs/hermes-contract.md) 의 「실행 이벤트가 실제로 오는 형태」). Hermes 가 뒤에 `result` 를 싣기 시작해도, `memory_read` 사건의 `detail` 은 `tool.started` 의 `preview`(인자)만 쓴다

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
