# 실행 추적 기술 설계

스펙은 [`spec.md`](spec.md) 다. 이 설계는 PR1 부터 PR4 까지의 모듈 경계와 데이터 흐름을 정하고, 자세한 칸과 작업 순서는 PR1 만 정한다.
PR2 부터는 같은 경계를 쓰고, 그 PR 이 바꾸는 결정만 이 문서에서 고친다.

## 요구사항 연결

| ID | 해결 방법 |
| --- | --- |
| REQ1 | `HermesRunEventStream` 이 `tool.started` 와 `tool.completed` 를 `tool_call_id` 로 짝짓고 두 사건 `timestamp` 의 차이를 걸린 시간으로 넣는다. 화면에 흘리는 사건과 저장하는 사건이 같은 값을 받는다. 짝이 없으면 hook 의 `duration_ms` 를 쓰고(DECISION3), 둘 다 없으면 비운다. 실행 트리는 빈 시간을 「모름」 으로 그리고, 시작 줄과 완료 줄을 `toolCallId` 로 짝짓는다 |
| REQ2 | plugin 의 `post_tool_call` 이 실패한 도구의 오류 종류와 오류 글을 보낸다. Control Plane 이 `ToolDetailRedactor.redactError` 로 가린 뒤 200자로 자르고, 실패 표시가 참인 그 도구의 `TOOL_COMPLETED` 줄에 `error_type` 과 `error_summary` 로 적는다. 플러그인 도구, 플러그인 호출 뒤 도구, 실행 기록에 내용을 남기지 않는 도구(`WithheldToolContent`)는 오류 글을 버린다 |
| REQ3 | `ExecutionEventView` 가 `errorType` 과 `errorSummary` 를 `InternalValuePolicy` 가 허락할 때만 넣는다. `durationMs` 와 `failed` 는 지금처럼 모두에게 넣는다 |
| REQ4 | PR3. plugin 이 `post_api_request`, `api_request_error`, `post_auxiliary_call` 을 같은 길로 보내고 Control Plane 이 `MODEL_CALL` 사건 줄로 적는다 |
| REQ5 | PR3. 받은 기록의 session 을 실행으로 푸는 규칙(아래 「session 을 실행으로 푼다」)이 내장 하위 에이전트를 등록한 실행으로 보낸다 |
| REQ6 | plugin 본문에 인자, 결과, 요청·응답 본문 칸을 두지 않는다. 오류 글은 Control Plane 이 가린 뒤에만 저장하고 로그에는 칸 이름과 개수만 남긴다 |
| REQ7 | hook 은 기록을 메모리 대기열에 넣고 바로 돌아간다. 데몬 스레드 하나가 모아 보내고, 실패하면 버린다. 빠진 기록 표시는 PR4 가 `api_call_count` 로 한다 |
| REQ8 | PR3, PR4. `MODEL_CALL` 줄은 `MEMBER` 응답의 사건 목록에서 빠진다 |
| REQ9 | PR4. 관리자 실행 트리가 `MODEL_CALL` 줄을 시각 순서로 끼우고 노드 요약을 화면에서 계산한다 |
| REQ10 | PR3. `MODEL_CALL` 의 토큰은 실행 줄의 합계 칸과 사용량 집계 질의에 들어가지 않는다 |
| REQ11 | PR3. 줄 수 상한은 받는 쪽이 센다. 대화 삭제의 `clearDetailsOf` 가 PR1 부터 `error_summary` 도 비운다 |
| REQ12 | `ToolDetailRedactor` 가 플러그인 호출 뒤의 빈 내용에 길이 문구를 붙이지 않는다. 실행 트리의 도구 줄이 실패 표시와 관리자용 오류 종류를 보인다 |
| REQ13 | PR2. 받는 쪽이 짝을 찾지 못한 도구 기록을 새 도구 줄로 적는다 |

## 책임과 경계

| 자리 | 책임 |
| --- | --- |
| `hermes/plugins/fos-ctx/run_trace.py` (새 파일) | hook 인자에서 숫자와 오류 글만 뽑아 기록을 만들고, 대기열과 보내는 스레드를 갖는다. profile 맥락이 있는 hook 안에서 토큰과 `state.db` 경로를 기록에 담는다. 보내는 스레드는 맥락을 물려받지 못해서다 |
| `mcp.presentation.RunTraceController` | 새 내부 경로 `/internal/hermes/run-trace`. MCP 토큰 인증과 본문 읽기만 한다. 다른 내부 경로(`SubagentSessionController`)와 같은 자리다 |
| `mcp.application.dto.RunTraceBatch` | 본문 검증과 기록마다 서명 확인. 실패한 기록만 버린다. 경로는 `AgentTokenAuthenticationFilter` 와 `ControlPlaneJwtFilter` 두 목록에 함께 더한다 |
| `orchestration.application.RunTraceIntake` | session 을 실행으로 풀고, 그 실행의 가림 범위로 오류 글을 가린 뒤 기록 저장에 넘긴다. session 등록과 도는 실행을 아는 패키지가 `orchestration` 이라 여기 둔다 |
| `usage.execution.application.ToolTraceLedger` | 메모리의 짝짓기 장부. 같은 도구 호출의 사건 줄과 hook 기록이 어느 쪽이 먼저 와도 한 줄에 모이게 한다 |
| `usage.execution.application.ToolTraceWriter` | 장부를 보고 hook 기록을 완료 줄에 옮긴다. 실패 규칙과 이름 확인을 갖는다 |
| `usage.execution.application.ExecutionEventRecorder` | `TOOL_COMPLETED` 를 만들 때 기다리던 기록을 채운다. 사건을 저장하는 두 자리(`ChatRunEvents.store`, `ChildRunEvents.store`)가 저장 직후 `saved` 를 부른다 |
| `hermes.infra.HermesRunEventStream` | `tool_call_id` 와 `timestamp` 를 읽고 걸린 시간을 계산한다. 끝나기 전에 같은 번호가 다시 시작되면 그 번호의 시간을 비운다 |
| `hermes.application.dto.HermesRunEvent`, `runtime.application.dto.RunEvent` | `toolCallId` 칸을 더한다 |
| `runtime.domain.WithheldToolContent` | 실행 기록에 내용을 남기지 않는 도구의 이름(사진·OCR, 원고, Memory, 할 일). 사건 스트림과 오류 요약이 같은 목록을 쓴다 |
| `usage.tree` | 응답에 `errorType`, `errorSummary` 를 역할에 맞춰 넣고 짝짓기용 `toolCallId` 를 넣는다 |
| `web/src/components/execution` | 「모름」, 실패 표시, 관리자용 오류 종류와 요약, 호출 번호로 짝짓기 |

의존 방향은 `mcp → orchestration → usage → runtime` 이고 층 순서(`TopLevelPackageOrder`)를 그대로 따른다.
새 테이블과 새 서비스는 없다. Hermes core 는 고치지 않는다.

### session 을 실행으로 푼다

`EventToolGuard` 와 같은 순서다. 등록된 하위 에이전트 session(`hermes_session_binding`)이면 그 origin 실행이고, 아니면 서명한 루트 session 과 그 profile 로 **도구가 시작한 시각에 돌던** 실행이 하나일 때 그 실행이다(`AgentExecutionRepository.findRunningAt`).
도는 실행만 찾지 않는다. 대화의 turn 은 같은 루트 session 을 쓰므로, 실행이 끝난 직후 도착한 기록이 다음 turn 에 붙거나 사라진다. 시각은 1초의 시계 차이를 감안하고, turn 경계에 걸려 후보가 둘이면 버린다.
둘 이상이거나 없으면 버린다. 남의 실행에 붙이지 않는 것이 버리는 것보다 중요하다(ADR-032 와 같은 까닭).
PR1 은 등록된 하위 에이전트 session 의 기록과 `tool_call_id` 없는 기록(코드 실행 안의 호출)을 받기만 하고 버린다. 두 기록은 PR2 가 새 도구 줄로 쓴다.

### 짝짓기 장부

`ToolTraceLedger` 는 `(실행 번호, tool_call_id)` 를 열쇠로 두 표를 갖는다. 모든 메서드는 같은 잠금 안에서 돈다.

| 표 | 값 | 채우는 때 |
| --- | --- | --- |
| 기다리는 기록 | 가린 hook 기록 | 사건 줄이 아직 저장되지 않았을 때 hook 이 도착 |
| 저장된 줄 | `TOOL_COMPLETED` 의 사건 번호 | 사건 줄을 저장한 직후 |

| 순서 | 동작 |
| --- | --- |
| hook 이 먼저 | 기다리는 기록에 넣는다. `TOOL_COMPLETED` 를 만들 때 꺼내 칸을 채운다 |
| hook 이 만들기와 저장 사이 | 기다리는 기록에 넣는다. 저장 직후의 알림이 꺼내 그 줄을 고친다 |
| hook 이 나중 | 저장된 줄 표에서 사건 번호를 찾아 그 줄을 고친다 |

같은 실행에서 번호가 다시 쓰이면(llama.cpp 는 모든 호출에 같은 번호를 쓴다) 그 번호를 「모호함」 표에 두고 기록을 버린다. 옮길 때는 사건 줄과 도구 이름이 같은지 본다. 다만 Tool Search 의 `tool_call` 은 사건이 다리 이름을, hook 이 실제 이름을 보내 이름을 보지 않는다.
오류 종류와 요약은 사건의 실패 표시가 참인 줄에만 옮긴다. 사건의 `error` 와 hook 의 `status` 는 실패를 다르게 판정할 수 있고(guardrail 거절, 경고가 섞인 성공), 실패 표시가 없는 줄에 오류가 붙어 보이면 헷갈린다. plugin 은 `status` 가 `ok` 가 아닌 기록(`error`, `blocked`, `timeout`, `cancelled`)을 실패로 보낸다.
항목은 10분 뒤 버리고 표마다 5,000개를 넘으면 오래된 것부터 버린다. Control Plane 이 다시 뜨면 장부가 빈다. 그 사이 Hermes 실행은 계속 돌고 plugin 도 계속 보내지만, 사건 스트림이 끊겨 짝지을 완료 줄이 생기지 않으므로 PR1 에서는 기록이 장부에서 만료될 뿐이다. 늦게 온 기록을 새 줄로 쓰는 PR2 는 이 경우를 다시 본다.
DB 의 `uk_execution_event_seq` 와 실행마다 메모리에 있는 사건 번호를 건드리지 않으려고 PR1 은 새 줄을 넣지 않고 있는 줄을 고치기만 한다.

### PR2 부터의 경계

| PR | 같은 경계에 더하는 것 |
| --- | --- |
| PR1 화면 | PR1 의 운영 코드가 PR 크기 상한의 80% 를 넘어 화면을 따로 낸다. 실행 트리가 빈 시간을 「모름」 으로, 실패 줄을 「실패」 로 그리고, 관리자에게 오류 종류와 요약을 보인다. 시작 줄과 완료 줄은 응답의 `toolCallId` 로 짝짓는다. Control Plane 응답은 PR1 이 이미 준다 |
| PR2 | 실행의 사건 번호를 한 곳에서 내는 `ExecutionEventSequence` 를 `usage.execution.application` 에 두고 대화 turn, 맡긴 실행, 받는 쪽이 함께 쓴다. 짝을 못 찾은 기록은 사건 스트림이 끝날 때와 늦게 온 때 새 도구 줄로 적는다. 늦게 넣은 줄은 사건 번호가 시각 순서와 어긋나므로 화면은 시각으로 끼운다. 도는 동안 받은 줄은 `ChatRunEvents` 가 대화 스트림에 끝난 도구 사건으로 흘리고, 대화 스트림의 도구 사건에도 호출 번호를 더해 작업 과정 블록이 이름 순서가 아니라 번호로 짝짓게 한다 |
| PR3 | 같은 경로에 `kind: "model"` 기록을 더한다. `execution_event` 에 사건 종류 `MODEL_CALL` 과 숫자 칸을 더하고, 칸 수가 많으면 남은 숫자는 JSON 칸 하나에 둔다. 실행마다 500줄을 세는 것은 받는 쪽이다 |
| PR4 | `ExecutionTreeService` 가 관리자에게만 `MODEL_CALL` 을 넣고, 화면이 줄 끼우기와 노드 요약을 계산한다 |

## 인터페이스와 데이터

### plugin 에서 Control Plane 으로

| 칸 | 값 |
| --- | --- |
| 주소 | `FOS_CTX_POLICY_URL` 의 마지막 경로 조각을 `run-trace` 로 바꾼 것(`/internal/hermes/run-trace`). 운영 값을 새로 두지 않는다 |
| 인증 | `Authorization: Bearer <그 profile 의 MCP 토큰>` |
| 본문 | `{"v": 1, "records": [기록...]}`. 한 번에 100개까지 |
| 기록 | `kind`(`"tool"`), `session_id`, `root_session_id`, `tool_call_id`(없으면 빈 글), `tool_name`, `duration_ms`, `status`, `error_type`, `error_message`, `ended_at`(초 단위 실수), `sig` |
| 서명할 글 | `v1-run-trace`, `kind`, 루트 session, session, `tool_call_id` 를 `\n` 으로 이은 UTF-8. key 는 `_fos_ctx` 와 같다 |
| 답 | 202 와 `{"accepted": n}`. 기록 하나가 틀려도 나머지는 받는다 |

`error_message` 는 실패한 기록에만 넣고 plugin 이 가림 규칙의 입력 상한보다 한 글자 긴 65,537자까지 자른다(스펙 DECISION13). Control Plane 은 상한을 넘은 글을 `[긴 도구 내용 가림]` 으로 통째로 가린다. 앞부분만 잘라 가리면 잘린 끝의 비밀값 조각이 토큰 모양 검사를 피할 수 있어서다. 인자, 결과, 미들웨어 이력은 넣지 않는다.
Hermes 는 profile 여럿을 한 프로세스에서 돌린다(multiplex). 보내는 데몬 스레드는 profile 맥락을 물려받지 못해 토큰과 `state.db` 를 바로 읽지 못한다. 그래서 hook 이 토큰과 `state.db` 경로를 기록에 담고, 보내는 스레드는 토큰마다 묶어 보낸다. 루트 session 은 보내는 스레드가 담긴 경로의 `state.db` 에서 찾는다. hook 스레드는 sqlite 를 열지 않는다. 프로세스 번호가 바뀌면(fork) 스레드를 다시 띄운다.
한 요청의 오류 글 합은 256KB 를 넘지 않게 나눠 보낸다. 프록시의 본문 상한에 걸려 묶음을 통째로 잃지 않게 한다.

### 저장

`execution_event` 에 칸 셋을 더한다(마이그레이션 `V20261105074444__execution_event_tool_trace.sql`).

| 칸 | 값 |
| --- | --- |
| `tool_call_id VARCHAR(128) NULL` | 도구 사건의 `tool_call_id`. PR2 가 짝을 다시 찾을 때 쓴다 |
| `error_type VARCHAR(64) NULL` | hook 이 준 오류 종류. 받은 값을 64자로만 자른다(스펙 DECISION17) |
| `error_summary VARCHAR(255) NULL` | 가린 뒤 200자로 자른 오류 글. 대화를 지우면 비운다 |

색인은 더하지 않는다. PR1 은 장부의 사건 번호로 줄을 고치고 `tool_call_id` 로 찾지 않는다.
원본 스키마는 [`backend/docs/data-schema.md`](../../../backend/docs/data-schema.md) 의 「execution_event」 다.

### 응답

`ExecutionEventView` 에 `errorType`, `errorSummary`, `toolCallId` 를 더한다. 앞의 둘은 `InternalValuePolicy` 를 따르므로 관리자는 관리자 실행 상세뿐 아니라 자기 대화의 「자세히 보기」 에서도 본다(ADR-063 과 같은 규칙). `toolCallId` 는 Hermes 가 붙인 불투명한 번호라 모두에게 넣고, 화면이 같은 이름의 병렬 도구를 바른 짝으로 묶는 데만 쓴다.
대화 스트림(`ChatEvent.tool`)은 바꾸지 않는다. 대화의 작업 과정은 지금처럼 걸린 시간과 실패만 그린다. 같은 이름의 병렬 도구가 순서를 바꿔 끝나면 작업 과정 블록의 시간이 서로 바뀌어 보일 수 있다. 대화 스트림에 호출 번호를 더하는 일은 PR2 가 한다.

## 실패와 일관성

- hook 은 대기열(1,000개)에 넣고 바로 돌아간다. 가득 차면 버리고 버린 수만 센다. 보내는 스레드는 0.5초 모아 한 번에 보내고 제한 시간은 3초, 다시 보내지 않는다. 실패는 종류(`HTTP 404`, `URLError`)만 로그에 남긴다.
- 운영은 plugin 을 먼저 올린다(`hermes/docs/code-architecture.md`). 그 사이 옛 Control Plane 은 404 를 돌려주고 plugin 은 기록을 버린다. 실행에는 영향이 없다.
- Control Plane 은 기록 하나의 저장 실패를 그 기록에서 멈추고 실행 기록과 대화를 건드리지 않는다.
- 걸린 시간은 사건 시각 차이를 먼저 쓴다. 줄을 고칠 때 `duration_ms` 는 비어 있을 때만 채운다. 사건 시각 차이에는 `pre_tool_call` 의 Control Plane 판정 왕복이 들어가고, hook 의 `duration_ms` 는 판정 뒤 도구 실행만 잰다. 화면의 시간은 사용자가 기다린 시간에 가까운 앞의 값이다.
- 대화를 지운 직후 늦게 온 기록이 오류 요약을 다시 쓸 수 있다. 장부의 10분 만료 안에서만 생기고, 요약은 이미 가린 값이다.
- 오류 글의 UUID 번호표는 사건 스트림의 번호표와 따로 매긴다. 같은 UUID 가 도구 내용과 오류 요약에서 다른 번호로 보일 수 있지만, 원문은 어느 쪽에도 남지 않는다.
- 플러그인 호출 여부는 도구가 시작한 시각(`ended_at - duration_ms`)으로 실행 트리 전체의 플러그인 정책 기록에서 확인한다. 사건 스트림이 쓰는 `ExecutionConnectorCalls.calledBefore` 와 같다. 조회가 실패하면 오류 글을 버린다.

## 검증 전략

| 대상 | 경계와 환경 |
| --- | --- |
| REQ1 | `HermesRunEventStreamTest` 에 v0.23.2 모양(`duration` 0, `tool_call_id`, `timestamp`)을 흘려 1.2초가 나오는지. 웹 단위 테스트로 빈 시간이 「모름」 인지 |
| REQ2, REQ6 | `ToolDetailRedactorTest` 의 오류 글 가림과 200자 경계. `RunTraceIntakeTest` 로 실행 풀기와 플러그인 범위. `RunTraceEndpointTest`(실제 HTTP, H2)로 비밀값과 UUID 가 든 오류 글이 가려져 저장되고, 표식 글이 저장값과 로그에 없는지, 끝난 직후 온 기록이 다음 turn 에 붙지 않는지 |
| REQ3 | `ExecutionTreeServiceTest` 로 `MEMBER` 응답의 두 칸이 `null` 인지 |
| REQ7 | plugin 단위 테스트로 hook 이 보내기를 기다리지 않는지, 5초 늦는 서버와 500 에서도 hook 이 바로 돌아오는지 |
| REQ12 | `ToolDetailRedactorTest` 의 빈 내용. 웹 단위 테스트로 실패 줄에 오류 종류가 관리자에게만 보이는지 |
| 장부 | `ToolTraceLedgerTest` 로 세 도착 순서와 만료 |
| 배포 뒤 | 실제 실행 하나에서 도구 시간과 실패 도구의 오류 요약이 관리자 실행 상세에 보이는지(PR 본문의 후속 검증) |

## 결정

| ID | 결정 | 답 | 누가 | 근거 |
| --- | --- | --- | --- | --- |
| DECISION1 | plugin 기록을 받는 인증 | 기존 내부 경로와 같은 MCP 토큰 인증과 HMAC 서명 | 작성자 | 하위 에이전트 등록, 도구 판정과 같은 신뢰 경계라 새 비밀값과 운영 값이 필요 없다 |
| DECISION2 | PR1 의 저장 방식 | 새 사건 줄을 넣지 않고 `TOOL_COMPLETED` 줄을 고친다. 순서는 메모리 장부가 맞춘다 | 작성자 | hook 은 사건 스트림과 다른 스레드로 와서, 새 줄을 넣으면 실행마다 메모리에 있는 사건 번호와 겹친다. 사건 번호를 한 곳에서 내는 일은 새 줄이 꼭 필요한 PR2 가 한다 |
| DECISION3 | 걸린 시간의 우선순위 | 사건 시각 차이를 먼저, hook 값을 다음으로 쓴다 | 작성자 | 스펙 DECISION5. 도는 동안 흘린 값과 저장값이 같아야 한다 |
| DECISION4 | 오류 글을 가리는 때 | 받을 때 가린다. 범위는 그 실행의 붙은 플러그인과 트리의 정책 기록으로 다시 만든다 | 작성자 | 가림 규칙은 `ToolDetailRedactor` 한 곳이 갖는다(ADR-047). 원문을 장부에 들고 있지 않는다 |
| DECISION5 | 받는 서비스의 자리 | `orchestration` 에 둔다 | 작성자 | session 등록과 도는 실행으로 실행을 푸는 규칙이 이미 `orchestration`(`EventToolGuard`)에 있다 |
| DECISION6 | 기록을 붙일 실행을 고르는 때 | 도구가 시작한 시각에 돌던 실행 하나 | 작성자 | 도는 실행만 찾으면 끝난 직후 도착한 기록을 잃거나 같은 대화의 다음 turn 에 붙인다(독립 검토). 후보가 둘이면 버려 남의 실행에 붙이지 않는다 |
| DECISION7 | 오류를 옮길 줄 | 사건의 실패 표시가 참인 완료 줄만 | 작성자 | hook 과 사건은 실패를 다르게 판정할 수 있다. 화면이 실패 표시와 오류 종류를 함께 보이므로 둘이 어긋나지 않게 한다 |
| DECISION8 | 실행 트리의 시작·완료 짝짓기 | 응답에 `toolCallId` 를 넣고 화면이 그것으로 짝짓는다. 없으면 지금처럼 이름 순서 | 작성자 | 걸린 시간이 실제 값으로 바뀌면 같은 이름의 병렬 도구가 바뀐 짝으로 보이는 것이 드러난다 |

장기 결정은 남기지 않는다. 플러그인 내부 경로 계약은 [`hermes/plugins/fos-ctx/README.md`](../../../hermes/plugins/fos-ctx/README.md) 가 원본이고, 이 경로는 기존 계약과 같은 모양을 더한 것이다.
