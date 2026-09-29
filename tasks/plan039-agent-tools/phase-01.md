# Phase 01. `agent_list` 와 `agent_status` 를 연다

**Execution profile**: standard

## 목표

Hermes 가 부를 수 있는 에이전트를 알고(`agent_list`), 맡긴 실행의 진행과 결과를 묻는(`agent_status`) 읽기 도구 둘을 연다.
읽기 쪽을 먼저 열어 권한 경계와 응답 모양을 굳힌 뒤 phase 02 에서 실행을 시작한다.

**범위 외**: `agent_delegate`(phase 02), `agent_stop`(phase 03).

## 컨텍스트

- plan038 이 끝나 있어야 한다. `McpCallContext`(서명 검증), `DelegationParentResolver`(도는 부모 찾기), `agent_execution` 의 `hermes_session_id`, `delegation_key`, `output_text` 칸이 있다. 없으면 `PHASE_BLOCKED: plan038 이 main 에 없다` 로 멈춘다
- MCP 도구 규격은 `McpToolService.tools()` 가 돌려주는 목록이고, 결과는 `McpToolService` 의 `result(text, isError)` 모양(`content` 에 text 하나, `isError`)이다. `McpController.call` 이 도구 이름으로 나눈다
- 에이전트 목록은 `AgentService.readableBy(CurrentUser)`(켜져 있고 요청자가 읽을 수 있는 것)로 얻는다. 에이전트의 소개는 `Agent.tagline()`
- 실행 나무의 뿌리는 `rootExecutionId` 가 있으면 그것, 없으면 자기 번호다(`ChildExecutionRunner.rootOf` 와 같은 규칙)

**근거 문서**: `docs/adr/ADR-017-무엇을-할지는-hermes-가-정하고-control-plane-은-경계만-갖는다.md` 의 「도구 넷과 한도」 절, `docs/flow.md` 의 「다른 에이전트에게 맡길 때」 절, `docs/code-architecture.md` 의 「다른 에이전트에게 맡기기」 절

## 의도 메모

- `agent_list` 는 토큰의 사용자만 본다. `_fos_ctx` 를 요구하지 않는다. profile, 주소, 모델, 공개 범위는 싣지 않는다. `code`, `name`, `tagline` 만
- `agent_status` 는 `_fos_ctx` 를 요구한다. 부르는 쪽의 부모 실행을 찾고, 묻는 실행이 **토큰의 사용자 것**이고 **`delegation_key` 가 있고**(위임으로 만든 실행) **그 뿌리가 부르는 쪽의 뿌리와 같을 때**만 답한다. 셋 중 하나라도 아니면 없는 실행과 같은 응답을 준다
- 응답은 모델이 읽기 쉬운 JSON 글 하나다. `SUCCEEDED` 면 `output_text`, `FAILED` 면 `error_code`, `CANCELLED` 면 `output_text` 가 있으면 싣는다. 내부 칸(profile, run id, 토큰 수)과 예외 문구는 싣지 않는다
- 인자는 `execution_id` 정수 하나다. 다른 키(`_fos_ctx` 제외)가 오면 인자 오류

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentDelegationService.java`

- `list(CurrentUser)`: 위 세 값의 목록
- `status(CurrentUser, McpCallContext, Long executionId)`: 위 규칙. 상태 값은 `ExecutionStatus` 이름 그대로(`RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED`)
- 조회에 필요한 질의가 `AgentExecutionRepository` 에 없으면 더한다

### 2. MCP 도구 규격과 경로

- `McpToolService.tools()` 에 `agent_list`(인자 없음), `agent_status`(`execution_id` 정수) 규격과 한국어 설명을 더한다. 설명에는 `agent_delegate` 로 받은 번호를 넣는다는 것만 적는다
- `McpToolService` 에 두 도구의 결과를 만드는 메서드를 더한다. 실패는 정해 둔 한국어 문구와 짧은 코드(예: `NOT_FOUND`, `INVALID_CONTEXT`)만 싣는다
- `McpController.call` 에 두 이름을 더한다. plan038 phase 02 에서 떼어 둔 `_fos_ctx` 를 `McpCallContext` 로 검증해 넘긴다(검증에 쓸 토큰 해시는 요청 속성). 인자 모양 검사는 컨트롤러가, 판정은 서비스가 한다

### 3. 테스트 `backend/src/test/java/com/bifos/assistant/mcp/McpAgentToolsTest.java`

`McpMemoryToolTest` 의 방식(토큰 발급, `/mcp` 호출)을 따른다. 서명 도우미는 plan038 의 `McpCallContextTest` 것과 같은 계약으로 만든다.

- `agent_list`: 요청자가 쓸 수 있는 켜진 에이전트만 오고, 남의 비공개 에이전트와 꺼진 에이전트는 오지 않는다. profile 이름이 응답 글 어디에도 없다
- `agent_status`: 같은 뿌리의 위임 실행은 상태와 답을 받는다. 남의 실행, 다른 뿌리의 실행, 위임이 아닌 실행(대화 turn, Memory 제안), 없는 번호는 모두 같은 응답이다
- 서명이 없거나 틀린 `agent_status` 는 거절된다. 모델이 흉내 낸 `_fos_ctx`(다른 토큰으로 서명)도 거절된다
- `FAILED` 실행은 `error_code` 를, `SUCCEEDED` 는 `output` 을 싣는다

## 검증

AGENTS.md 「확인」 절을 적힌 순서대로 돌린다.

```bash
cd backend && ./gradlew test
cd web && pnpm typecheck && pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/orchestration/application/AgentDelegationService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/usage/infra/AgentExecutionRepository.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpAgentToolsTest.java` | 신규 |
