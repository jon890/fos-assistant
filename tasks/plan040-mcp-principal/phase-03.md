# Phase 03. 공유 profile 의 사용자 격리를 e2e 로 고정하고 위임 도구 계획을 새 설계에 맞춘다

**Execution profile**: standard

## 목표

실제 대화 turn 을 지나는 e2e 로, 같은 GROUP 에이전트를 쓰는 두 사용자가 `memory_read` 로 자기 Memory 에만 닿는 것을 확인한다.
뒤에 이어 구현할 위임 도구 계획서(`tasks/plan039-agent-tools/`)의 전제와 키 정의를 새 설계에 맞춘다.

**범위 외**: 위임 도구 구현. 실제 Hermes 확인(`docs/hermes/delegation.md` 「공유 profile 에서 두 사용자의 호출을 실제로 확인하는 절차」 를 `fos-home-infra` 쪽에서 돌린다).

## 컨텍스트

- phase 02 가 끝나 있어야 한다. 가짜 Hermes 의 `writeArtifactViaMcp` 가 `_fos_ctx` 를 서명해 붙이고, 토큰은 `{ profileName, label }` 로 발급한다
- 가짜 Hermes(`test/e2e/fake-hermes.ts`)는 run 제출을 받을 때 입력이 `ARTIFACT_WRITE_PROBE` 이면 그 run 안에서 `/mcp` 를 부른다. 같은 자리에 Memory 읽기 검사를 더한다. run 의 `session_id` 는 제출 본문에 있다
- e2e 에이전트 등록은 `test/e2e/scenarios/agent-tools.ts` 의 `/admin/agents` 호출이 본보기다. profile key 준비는 `test/e2e/run.ts` 가 한다
- 시나리오 순서는 `test/e2e/run.ts` 의 목록이 정한다. `memory` 와 `artifact` 다음에 둔다
- 위임 도구 계획서의 옛 전제
  - `tasks/plan039-agent-tools/phase-01.md`: 「`agent_list` 는 토큰의 사용자만 본다. `_fos_ctx` 를 요구하지 않는다」, 「`agent_status` 는 … 묻는 실행이 토큰의 사용자 것이고」, 컨텍스트의 바탕 목록, `AgentDelegationService.list(CurrentUser)`, `status(CurrentUser, McpCallContext, Long)`
  - `tasks/plan039-agent-tools/phase-02.md`: 뿌리 session 과 `tool_call_id` 둘로만 계산하던 `delegation_key` 정의, 「부모는 `DelegationParentResolver` 가 서명한 뿌리 session 으로 찾은 도는 실행이다」, `AgentRunner.run(user, conversation, agent, task, parentExecutionId, rootExecutionId, sessionId, …)` 시그니처, 「자식 session 은 `"fos-" + UUID` 로 새로 정해」, 테스트의 「남의 토큰으로 서명한 호출」
  - `tasks/plan039-agent-tools/phase-03.md`: 「`agent_stop` 의 권한은 … 토큰의 사용자」, e2e 의 「토큰을 발급하고」, 「다른 사용자의 토큰」

**근거 문서**: `docs/adr/ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md`, `docs/flow.md` 의 「MCP 호출의 요청자를 정할 때」 와 「다른 에이전트에게 맡길 때」 절, `docs/code-architecture.md` 의 「MCP 요청자」 절

## 의도 메모

- e2e 는 가짜 Hermes 가 플러그인 역할을 한다. 서명은 phase 02 에서 둔 도우미를 그대로 쓴다
- 사용자가 둘인 검사는 두 turn 을 나란히 보낸다. 가짜 Hermes 가 두 run 의 `session_id` 를 섞지 않는지도 이 검사가 함께 본다
- plan039 는 지우지 않는다. 전제와 정의만 고친다. 고친 뒤에도 phase 01 부터 이어 구현할 수 있어야 한다
- plan039 가 인용하는 이름은 이 plan 이 만든 실제 이름(`McpCallerResolver`, `McpCaller`, `McpPrincipal`, `DelegationKey.of`, `RunSession.fresh()`, `DelegationParentResolver.resolve(String profileName, String rootSessionId)`)과 같아야 한다. 코드에서 읽어 확인하고 적는다

## 작업 항목

### 1. `test/e2e/fake-hermes.ts` 에 Memory 읽기 검사

- `export const MEMORY_READ_PROBE = "MCP Memory 읽기 검사"`. 입력이 `MEMORY_READ_PROBE + " " + <번호>` 이면 그 run 안에서 설정된 토큰으로 `memory_read(id)` 를 `_fos_ctx` 와 함께 부르고, 도구 결과의 text 를 그 run 의 답으로 돌려준다
- `setMemoryReadMcp(endpoint: string, token: string): void` 를 `FakeHermes` 에 더한다

### 2. `test/e2e/scenarios/mcp-principal.ts` 신규와 `test/e2e/run.ts` 등록

0. 시나리오 파일이 `export const MCP_PRINCIPAL_PROFILE = "mcp-shared-group"` 을 둔다. `test/e2e/run.ts` 의 `writeProfileKeys` 목록과 `startFakeHermes` 의 key 표에 이 상수를 더한다. `agent.hermes_profile` 은 유일해서 새 GROUP 에이전트에 새 profile 이 필요하고, key 가 없으면 실행 제출이 실패한다
1. 관리자가 GROUP 에이전트 하나를 `MCP_PRINCIPAL_PROFILE` 로 등록한다(켜진 상태, `visibility: "GROUP"`, `ownerEmail: null`)
2. 그 profile 에 묶은 토큰을 발급하고 `setMemoryReadMcp` 에 준다
3. 아빠와 아이가 각자 제목만 싣는 USER Memory 를 하나씩 만든다
4. 아빠와 아이가 **나란히** 그 에이전트로 새 대화를 보낸다. 입력은 각자 자기 Memory 번호의 검사 글이다. 두 답에 각자 자기 본문만 있다
5. 아빠가 아이의 번호로, 아이가 아빠의 번호로 보낸다. 답이 `Memory 항목을 읽을 수 없습니다.` 이고 상대 본문이 없다
6. 아빠와 아이가 각자 `GET /api/v1/usage/executions` 를 부르면 자기 turn 의 실행 줄만 보인다. 상대 turn 의 실행 번호가 목록에 없다
7. 정리: 토큰 폐기, Memory 삭제, 에이전트 끄기

### 3. `tasks/plan039-agent-tools/` 의 전제와 정의

- `phase-01.md`
  - 바탕 목록에 `McpCallerResolver`, `McpCaller`, `McpPrincipal`, `DelegationKey`, `RunSession` 을 더하고 PHASE_BLOCKED 조건도 맞춘다
  - `agent_list` 도 `_fos_ctx` 를 요구한다. 요청자는 `McpCallerResolver` 가 정한 부모 실행의 사용자다
  - `agent_status` 의 「토큰의 사용자 것」 을 「요청자(부모 실행의 사용자) 것」 으로
  - 서비스 시그니처를 `list(McpCaller)`, `status(McpCaller, Long executionId)` 로. 부모 실행과 확인한 맥락은 `McpCaller` 에 있다
  - 테스트는 `McpPrincipalTest` 의 서명 도우미와 준비 방식을 따른다. 「다른 profile 의 토큰으로 서명한 호출은 거절」 을 더한다
- `phase-02.md`
  - `delegation_key` 는 `DelegationKey.of(부모 실행의 profileName, rootSessionId, sessionId, toolCallId)`(ADR-032 「`delegation_key`」)
  - 부모는 `McpCallerResolver` 가 찾은 `McpCaller.parent()`. `DelegationParentResolver` 를 다시 부르지 않는다
  - `AgentRunner.run` 시그니처를 `RunSession` 을 받는 하나로 고치고, 자식 session 은 `RunSession.fresh()` 를 쓴다
  - 작업 항목 2 의 「오버로드를 더하거나, 두 값을 담는 작은 값 객체를 받는 오버로드 하나를 더한다. 기존 호출은 바꾸지 않는다」 를 「단일 `AgentRunner.run` 에 `DelegationKey delegationKey` 인자를 더하고 기존 호출은 null 을 넘긴다. `ExecutionRecorder.start` 도 `DelegationKey` 를 받아 처음 만들 때 적는다」 로 바꾼다
  - 작업 항목 3 의 `delegate(..., String sessionId, String delegationKey, ...)` 를 `delegate(..., RunSession session, DelegationKey delegationKey, ...)` 로 바꾼다
  - 「남의 토큰으로 서명한 호출」 을 「다른 profile 의 토큰으로 서명한 호출」 로, 「같은 `tool_call_id` 두 번」 옆에 「다른 session 의 같은 `tool_call_id` 는 실행 둘」 을 더한다
- `phase-03.md`
  - `agent_stop` 의 권한을 요청자(부모 실행의 사용자) 기준으로
  - e2e 의 토큰 발급을 `{ profileName, label }` 로, 「다른 사용자의 토큰」 을 「다른 profile 의 토큰」 으로
- 세 파일 모두 `verify_task.py` 를 통과해야 한다

### 4. 완료 마킹

`tasks/plan040-mcp-principal/index.json` 의 `status` 를 `completed`, `current_phase` 를 3 으로 바꾼다.

## 검증

```bash
node test/e2e/run.ts
```

로그에 `mcp-principal` 시나리오의 단계가 모두 통과로 찍힌다.

```bash
# cwd: 저장소 root
python3 /Users/nhn/personal/fos-skills/planning/scripts/verify_task.py plan039-agent-tools
grep -n "토큰의 사용자\|SHA-256(\`root_session_id\`\|오버로드를 더하거나\|String sessionId, String delegationKey" tasks/plan039-agent-tools/*.md
```

`verify_task.py` 가 종료 코드 0 이고 grep 이 아무것도 찾지 않는다.

그 다음 AGENTS.md 「확인」 절을 적힌 순서대로 돌린다.

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
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/e2e/scenarios/mcp-principal.ts` | 신규 |
| `test/e2e/run.ts` | 수정 |
| `tasks/plan039-agent-tools/phase-01.md` | 수정 |
| `tasks/plan039-agent-tools/phase-02.md` | 수정 |
| `tasks/plan039-agent-tools/phase-03.md` | 수정 |
| `tasks/plan040-mcp-principal/index.json` | 수정 |
