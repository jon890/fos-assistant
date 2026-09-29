# Phase 04. 가짜 Hermes 로 부모가 끝난 뒤의 하위 에이전트 호출을 확인한다

**Execution profile**: standard

## 목표

`test/e2e` 에 시나리오 `native-delegation-mcp` 를 더한다. 실제 대화 turn 안에서 가짜 Hermes 가 플러그인처럼 하위 에이전트 session 을 등록하고, 부모 run 이 끝난 뒤 그 session 으로 `memory_read` 를 불러 origin 사용자의 Memory 에만 닿는지 본다. 두 사용자의 하위 에이전트를 함께 확인한다.

**범위 외**: 실제 Hermes 와 실제 플러그인 확인. 그 절차는 `docs/hermes/delegation.md` 의 「하위 에이전트를 실제로 확인하는 절차」 에 있고 `fos-home-infra` 가 돌린다.

## 컨텍스트

- 시나리오는 `test/e2e/scenarios/` 에 하나씩 두고 `test/e2e/run.ts` 의 `SCENARIOS` 배열이 차례로 돌린다. profile 별 API key 와 스킬 목록은 `run.ts` 의 `profileKeys` 자리(`[MCP_PRINCIPAL_PROFILE]: PROFILE_KEY` 가 있는 곳)와 그 아래 스킬 목록, 그리고 profile 목록을 도는 `for (const profileName of [...])` 에 함께 더한다
- 본보기는 `test/e2e/scenarios/mcp-principal.ts` 다. GROUP 에이전트를 새 profile 로 등록하고, 그 profile 에 묶은 토큰을 발급해 `context.hermes.setMemoryReadMcp(endpoint, token)` 로 가짜 Hermes 에 주고, 두 사용자가 `/chat/messages` 로 검사 글을 보낸다. 정리는 `finally` 에서 한다
- 가짜 Hermes `test/e2e/fake-hermes.ts` 는 run 을 받을 때 입력이 `MEMORY_READ_PROBE` 로 시작하면 `readMemoryViaMcp(memoryId, submitted.session_id)` 로 `_fos_ctx` 를 서명해 `/mcp` 를 부른다. 서명은 `test/e2e/mcp-context.ts` 의 `signedCallContext(token, toolName, rootSessionId, sessionId, toolCallId)` 다
- 등록 계약(경로, 본문, 서명, 응답)은 `docs/hermes/delegation.md` 의 「하위 에이전트 session 등록 계약」 이 정한다. 경로는 `/internal/hermes/session-bindings/subagent`, 서명할 글은 `v1-subagent`, 뿌리, 부모, 자식 session 을 `\n` 로 이은 것이다
- 대화 turn 의 뿌리 session 은 Control Plane 이 보낸 `session_id` 다(새 대화의 첫 turn 이면 `fos-<uuid>`)

**근거 문서**: `docs/hermes/delegation.md` 의 「하위 에이전트는 부모 run 보다 오래 산다」, 「하위 에이전트 session 등록 계약」 절, `docs/flow.md` 의 「하위 에이전트 session 을 등록할 때」 절, `docs/adr/ADR-037-hermes-하위-에이전트-session-의-주인은-만들-때-등록한-줄로-정한다.md`

## 의도 메모

- 가짜 Hermes 는 실제 Hermes 처럼 **부모 run 안에서, 응답하기 전에** 등록을 끝낸다. 실제 hook 이 동기이기 때문이다. 등록 응답이 2xx 가 아니면 run 을 실패로 두지 않고 기록만 한다(실제 Hermes 도 hook 예외를 삼킨다). 시나리오는 그 기록으로 실패를 알아낸다
- 하위 에이전트의 호출은 **부모 run 이 끝나고 `/chat/messages` 가 응답한 뒤** 시나리오가 부른다. 그래야 「부모가 끝난 뒤」 를 확인한다
- 등록 서명은 `mcp-context.ts` 에 새 함수로 두고 운영 코드를 부르지 않는다
- 자식 session 은 `native-<uuid>` 처럼 Control Plane 이 정하지 않은 값으로 둔다

## 작업 항목

### 1. `test/e2e/mcp-context.ts` 에 등록 본문을 만드는 함수

`signedSubagentRegistration(token, parentRootSessionId, parentSessionId, childSessionId)` 가 `{ v: 1, parent_session_id, parent_root_session_id, child_session_id, child_subagent_id: null, parent_subagent_id: null, sig }` 를 돌려준다. key 는 `signedCallContext` 와 같고 서명할 글만 다르다. 파일 머리 주석에 두 계약의 자리를 함께 적는다.

### 2. `test/e2e/fake-hermes.ts`

- `export const SUBAGENT_MEMORY_PROBE = "MCP 하위 에이전트 검사";` 를 더한다
- run 입력이 `SUBAGENT_MEMORY_PROBE` 로 시작하면: 자식 session `native-<uuid>` 를 만들고, `memoryReadMcp` 의 토큰으로 `POST <memoryReadMcp.endpoint 에서 /mcp 를 뗀 주소>/internal/hermes/session-bindings/subagent` 에 부모 = 뿌리 = 제출받은 `session_id` 로 등록한다. 응답 상태를 자식 session 과 함께 기록한다. run 의 답은 `하위 에이전트 session: <자식 session>` 한 줄이다
- 공개 메서드를 더한다
  - `subagentRegistrations(): readonly { childSessionId: string; rootSessionId: string; status: number }[]`
  - `readMemoryAsSubagent(childSessionId: string, memoryId: number): Promise<string>`: 기록에서 그 자식의 뿌리를 찾아 `signedCallContext(token, "memory_read", root, childSessionId, call_<uuid>)` 로 서명해 `/mcp` 를 부르고 도구 결과의 text 를 돌려준다
  - `readMemoryAsUnregisteredSubagent(rootSessionId: string, memoryId: number): Promise<string>`: 등록하지 않은 `native-<uuid>` 로 같은 호출을 한다
- `FakeHermes` 타입에 세 메서드를 적는다

### 3. `test/e2e/scenarios/native-delegation-mcp.ts`

`export const NATIVE_DELEGATION_PROFILE = "native-delegation-group";` 와 `nativeDelegationScenario`(`name: "부모가 끝난 뒤의 하위 에이전트 MCP"`)를 둔다. `mcp-principal.ts` 처럼 GROUP 에이전트와 묶인 토큰을 준비하고 `finally` 에서 정리한다.

1. 아빠와 아이가 각자 제목만 싣는 USER Memory 를 만든다
2. 두 사용자가 나란히 `SUBAGENT_MEMORY_PROBE` 를 보낸다. 두 응답이 모두 온 뒤(부모 run 과 turn 이 끝난 뒤) 등록 기록이 둘이고 상태가 모두 `201` 이다. 두 자식의 뿌리가 서로 다르다
3. 두 turn 의 실행이 `/usage/executions` 에서 끝난 상태다(부모가 끝났다)
4. 아빠 turn 의 자식이 아빠 Memory 를 읽으면 본문이 오고, 아이 Memory 를 읽으면 「Memory 항목을 읽을 수 없습니다.」 다. 아이 turn 의 자식도 거꾸로 같다
5. 아빠의 같은 대화에서 다음 turn 을 하나 더 보낸다. 그 뒤에도 아빠 자식은 아빠 Memory 를 읽는다
6. 등록하지 않은 자식 session 으로 아빠의 뿌리에 서명한 호출은 「호출 맥락을 확인할 수 없습니다」 로 시작하는 결과다(fail-closed)

### 4. `test/e2e/run.ts`

`NATIVE_DELEGATION_PROFILE` 을 `mcp-principal` 과 같은 자리(profile key, 스킬 목록, profile 목록)에 더하고, `SCENARIOS` 에서 `mcpPrincipalScenario` 다음에 `nativeDelegationScenario` 를 둔다.

## 검증

AGENTS.md 「확인」 절의 명령을 적힌 순서대로 돌린다. `test/e2e` 는 앞선 `gradlew test` 가 남긴 데이터에 걸릴 수 있어 건너뛰지 않는다.

```bash
cd backend && ./gradlew test
cd web && pnpm install --frozen-lockfile && pnpm typecheck && pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
```

`node test/e2e/run.ts` 출력에 「부모가 끝난 뒤의 하위 에이전트 MCP」 시나리오 이름과 통과가 보여야 한다.
브라우저 검사는 한 번에 하나만 돌린다. 돌리기 전에 `ps -ax | grep -E "playwright test|standalone/server.js"` 로 다른 검사가 없는지 본다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `test/e2e/mcp-context.ts` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/e2e/scenarios/native-delegation-mcp.ts` | 신규 |
| `test/e2e/run.ts` | 수정 |
