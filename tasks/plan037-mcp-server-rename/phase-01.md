# Phase 01. Control Plane MCP 서버 이름을 fos-assistant 로 바꾼다

**Execution profile**: standard

## 목표

backend 가 Hermes 에 쓰는 MCP 서버 이름과 MCP `initialize` 가 알리는 서버 이름을 `fos-assistant-memory` 에서 `fos-assistant` 로 바꾼다.
Hermes 는 MCP 도구 이름 앞에 서버 이름을 붙여, 결과물 쓰기가 `mcp_fos_assistant_memory_artifact_write` 로 보였다.

**범위 외**: 홈서버 Hermes 의 MCP 등록 이름, profile 의 `platform_toolsets.api_server` 목록, 대시보드 plugin 의 이름 검사는 `fos-home-infra` 가 바꾼다. 이 저장소에는 그 절차를 적지 않는다. 배포 순서는 코디네이터가 맞춘다.

## 컨텍스트

- 서버 이름은 두 곳에서 쓰인다.
  - `backend/src/main/java/com/bifos/assistant/agent/domain/AgentToolPolicy.java` 의 상수 `MEMORY_MCP`(`"fos-assistant-memory"`). 도구 저장 때 `requestedForWrite` 가 `platform_toolsets.api_server` 목록 끝에 늘 더하고(`result.add(MEMORY_MCP)`), `AgentToolService` 가 다시 읽은 목록과 견줄 때와 미분류 목록을 만들 때 걸러 낸다
  - `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpController.java` 의 `initialize` 응답 `serverInfo.name`
- `platform_toolsets.api_server` 의 MCP 이름은 허용 목록이다. 등록되지 않은 이름만 남으면 Hermes 가 전역 MCP 서버를 모두 켠다. 그래서 운영 전환은 `fos-home-infra` 가 등록과 목록을 바꾼 직후 이 변경을 배포하는 순서로 한다. 이 phase 는 코드와 테스트만 바꾼다

**근거 문서**: `docs/hermes/tools-and-skills.md` 의 「Control Plane MCP」 절, `docs/adr/ADR-028-결과물은-사용자의-대화-폴더에-mcp-도구로-쓴다.md`

## 의도 메모

- 상수 이름도 내용에 맞게 `CONTROL_PLANE_MCP` 로 바꾼다. Memory 만 담는 서버가 아니다. `MEMORY`(내장 memory toolset, 항상 막음)와 헷갈리지 않게 한다
- 옛 이름을 함께 받는 호환 코드는 넣지 않는다. `/v1/toolsets` 는 MCP 이름을 돌려주지 않고(`docs/hermes/tools-and-skills.md` 「도구 목록 조회와 버전 차이」), backend 는 쓸 때 새 이름만 쓴다. 옛 이름과 새 이름을 함께 받는 일은 `fos-home-infra` 의 plugin 이 전환 동안 맡는다
- 기능 이름인 `memory_read`, `artifact_write` 도구 이름과 `McpMemoryToolTest` 같은 기존 클래스 이름은 바꾸지 않는다. 이 phase 는 서버 이름만 다룬다

## 작업 항목

### 1. `AgentToolPolicy.java`

- `public static final String MEMORY_MCP = "fos-assistant-memory";` 를 `public static final String CONTROL_PLANE_MCP = "fos-assistant";` 로 바꾼다. 한국어 Javadoc 한 줄: Control Plane 이 여는 MCP 서버의 Hermes 등록 이름이며, 도구 저장 때 허용 목록에 늘 남긴다
- 이 파일 안의 `MEMORY_MCP` 참조(`requestedForWrite` 의 거절 검사와 `result.add`)를 새 상수로 바꾼다

### 2. `AgentToolService.java`

- `AgentToolPolicy.MEMORY_MCP` 참조 두 곳(다시 읽은 목록과 견줄 `desiredBuiltin` 을 만들 때, `response` 의 미분류 거르기)을 `CONTROL_PLANE_MCP` 로 바꾼다

### 3. `McpController.java`

- `initialize` 응답의 `serverInfo.name` 을 `"fos-assistant"` 로 바꾼다. 문자열을 두 번 적지 말고 `AgentToolPolicy.CONTROL_PLANE_MCP` 를 쓴다(패키지 경계상 `mcp` 가 `agent.domain` 을 부르는 것이 다른 코드와 어긋나면 상수를 그대로 복제하지 말고 코디네이터에게 알린다)

### 4. backend 테스트

- `backend/src/test/java/com/bifos/assistant/agent/AgentToolPolicyTest.java`: `AgentToolPolicy.MEMORY_MCP` 를 `CONTROL_PLANE_MCP` 로
- `backend/src/test/java/com/bifos/assistant/agent/AgentToolServiceTest.java`: `List.of("web", "fos-assistant-memory")` 를 `"fos-assistant"` 로
- `backend/src/test/java/com/bifos/assistant/hermes/HermesToolsetRequestTest.java`: 두 곳의 `"fos-assistant-memory"` 를 `"fos-assistant"` 로
- `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryToolTest.java`: `serverInfo.name` 기대값을 `"fos-assistant"` 로

### 5. 가짜 Hermes 와 e2e, 브라우저 검사

- `test/e2e/fake-hermes.ts`: `const MEMORY_MCP = "fos-assistant-memory";` 를 `const CONTROL_PLANE_MCP = "fos-assistant";` 로 바꾸고 참조(기본 toolset 목록, `PUT /api/config` 본문 검사)를 바꾼다
- `test/e2e/run.ts`: `startFakeHermes` 에 넘기는 두 profile 의 초기 목록 `["fos-assistant-memory"]` 를 `["fos-assistant"]` 로
- `test/e2e/scenarios/agent-tools.ts`: `platform_toolsets.api_server` 의 `"fos-assistant-memory"` 와 「MCP 서버 이름이 내장 목록에 없다」 단언의 이름을 `"fos-assistant"` 로
- `test/browser/fixtures.ts`: `api_server: ["fos-assistant-memory"]` 를 `["fos-assistant"]` 로

### 6. 남은 이름 확인

`git grep -n "fos-assistant-memory" -- backend web test` 가 아무것도 내지 않아야 한다. `docs/` 에는 옛 이름의 이력 한 줄만 남는다(이미 고쳐 두었다).

### 7. 완료 처리

- 모든 검증이 통과하면 `tasks/plan037-mcp-server-rename/index.json` 의 `status` 를 `completed` 로 바꿔 이 phase 커밋에 담는다
- 그 뒤 PR 의 마지막 커밋으로 `tasks/plan037-mcp-server-rename/` 디렉터리를 지운다(`docs(docs): 구현이 끝난 MCP 서버 이름 계획서를 지운다`). 오래 남을 내용은 이미 `docs/` 에 있다

## 검증

AGENTS.md 「확인」 절을 적힌 순서대로 모두 돌린다. 새 워크트리면 `web` 에서 `pnpm install --frozen-lockfile` 이 먼저 필요하다.

```bash
cd backend && ./gradlew test
cd web && pnpm typecheck && pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
git grep -n "fos-assistant-memory" -- backend web test   # 출력 없음
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/agent/domain/AgentToolPolicy.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentToolService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpController.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentToolPolicyTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentToolServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/HermesToolsetRequestTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpMemoryToolTest.java` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/e2e/run.ts` | 수정 |
| `test/e2e/scenarios/agent-tools.ts` | 수정 |
| `test/browser/fixtures.ts` | 수정 |
| `tasks/plan037-mcp-server-rename/index.json` | 수정 |
