# Phase 01. 가계부 연결용 profile 의 API 도구 목록 줄이기

**Execution profile**: standard

## 목표

가계부 연결용 profile 의 `platform_toolsets.api_server` 를 Control Plane 이 직접 줄여,
연결 확인과 관리자 반영 완료가 READY 로 넘어가게 한다.

**범위 외**: 일반 에이전트의 관리자 등급 도구가 `agent.disabled_toolsets` 때문에 켜지지 않는 문제.
인프라 plugin 과 설정 틀은 바꾸지 않는다.

## 컨텍스트

`AccountbookConnectionService.check` 와 `confirmApplied` 는
`toolsets.readEnabled(...)` 가 빈 목록이어야 READY 로 바꾼다.
새 profile 의 설정 틀이 `api_server` 에 `delegation` 을 넣고, 가계부 설치는 `accountbook` 을 더할 뿐이라
`readEnabled` 가 늘 `["delegation"]` 을 돌려준다. 그래서 모든 연결이 `PENDING` 에 머문다.

목록 쓰기는 이미 있는 `HermesToolsetClient.writeApiServer(profileName, toolsets)` 를 쓴다.
`AgentToolService.write` 가 같은 메서드를 쓰지만 연결용 에이전트를 막으므로 그 서비스를 거치지 않는다.
대시보드 plugin 은 목록에 `fos-assistant` 가 있고 `memory` 가 없으면 받는다.
`accountbook` 은 그 profile 에 MCP 서버로 등록돼 있을 때만 알려진 이름이다.

**근거 문서**: `docs/connectors.md` 의 「설치와 실패 처리」 절, `docs/flow.md` 의 「가계부 연결」 절

## 의도 메모

- 확인 경로에서 늘 쓰지 않고, 켜진 내장 도구가 보일 때만 쓴다. 설정 파일을 매번 바꾸지 않기 위해서다.
- 확인 경로는 설치의 `enabled` 와 `configured` 가 모두 참일 때만 쓴다. 그 전에는 `accountbook` 이 알려진 이름이 아니다.
- 등록 경로에서 `["fos-assistant"]` 만 쓰는 까닭: 다시 등록할 때 설치가 이미 되어 있어도 plugin 설치가 `accountbook` 을 다시 더한다.
- 인프라 설정 틀에서 `delegation` 을 빼는 대안은 버렸다. 일반 에이전트의 위임까지 막는다.

## 작업 항목

### 1. `backend/src/main/java/com/bifos/assistant/connector/application/AccountbookConnectionService.java` — 목록 쓰기

- 상수를 둔다. `private static final String CONNECTOR_SERVER = "accountbook";`
  `fos-assistant` 는 `AgentToolPolicy.CONTROL_PLANE_MCP` 를 쓴다.
- `register` 의 `try` 첫 줄에서 `toolsets.writeApiServer(profile, List.of(AgentToolPolicy.CONTROL_PLANE_MCP))` 를 부른다.
  실패하면 기존 `catch` 가 `PENDING` 과 `ConnectorOperationFailure` 로 처리한다.
- private 메서드 `List<String> narrowedEnabled(Agent agent)` 를 더한다.
  `readEnabled` 가 비어 있지 않으면 `writeApiServer(profile, List.of(CONTROL_PLANE_MCP, CONNECTOR_SERVER))` 를 부르고 다시 읽은 목록을 돌려준다.
- `check` 와 `confirmApplied` 의 `readEnabled(...).isEmpty()` 를 `narrowedEnabled(...).isEmpty()` 로 바꾼다.
  두 곳 모두 `state.enabled()` 와 `state.configured()` 가 참인 뒤에만 부르도록 순서를 둔다.
  `confirmApplied` 는 조건식을 나눠 enabled 와 configured 를 먼저 검사한다.

### 2. `backend/src/test/java/com/bifos/assistant/connector/AccountbookConnectionServiceTest.java` — 검증

- 등록하면 `writeApiServer(profile, ["fos-assistant"])` 가 `putConnector` 보다 먼저 불린다. `InOrder` 로 본다.
- `readEnabled` 가 처음 `["delegation"]`, 다음 `[]` 를 돌려주면 `check` 가 `["fos-assistant", "accountbook"]` 을 쓰고 READY 가 된다.
- `readEnabled` 가 쓴 뒤에도 `["delegation"]` 이면 `PENDING` 이다.
- 설치가 `configured=false` 이면 `writeApiServer` 를 확인 경로에서 부르지 않는다.

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
cd web && pnpm typecheck && pnpm build
cd web && pnpm test:browser
node test/e2e/run.ts
node --test 'test/unit/**/*.test.ts'
scripts/check-public-safe.sh
grep -n "narrowedEnabled" backend/src/main/java/com/bifos/assistant/connector/application/AccountbookConnectionService.java
```

`grep` 은 정의 하나와 호출 둘, 모두 세 줄이 나와야 한다.

모두 통과하면 `tasks/plan030-connector-toolsets/index.json` 의 `status` 를 `completed` 로 바꾼다.

## Critical Files

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/connector/application/AccountbookConnectionService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/AccountbookConnectionServiceTest.java` | 수정 |
