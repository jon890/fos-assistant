# Phase 01. 토큰 묶기가 옛 사용자를 비우고 `McpCaller` 가 origin 실행을 들고 다닌다

**Execution profile**: standard

## 목표

옛 MCP 토큰을 profile 에 묶을 때 `agent_token.user_id` 를 비운다. 옛 판의 서버로 되돌려도 묶인 토큰이 옛 사용자로 돌지 않고 인증에서 거절되게 하기 위해서다.
`McpCaller` 의 두 번째 칸 이름을 `parent` 에서 `originExecution` 으로 바꾼다. 다음 phase 부터 그 실행은 끝난 실행일 수 있어 「도는 부모」 라는 이름이 틀린다.

**범위 외**: 등록 표와 판정 순서(phase 02), 등록 경로(phase 03), 가짜 Hermes 검사(phase 04). 옛 토큰 경로 자체를 지우는 일(`legacy-user-tokens` 설정, `agent_token.user_id` 칸, `McpPrincipal.legacyUserId`)은 이 plan 밖이다.

## 컨텍스트

- `backend/src/main/java/com/bifos/assistant/mcp/domain/AgentToken.java` 의 `bindProfile(String)` 은 지금 `profileName` 만 바꾸고 `userId` 를 남긴다
- `AgentTokenService.bindProfile(Long, String)` 이 그것을 부른다. `AgentTokenService.authenticate` 는 `profileName` 이 있으면 `userId` 를 읽지 않으므로 지금 판에서는 동작이 바뀌지 않는다. 옛 판은 `user_id` 를 읽는다
- `backend/src/main/java/com/bifos/assistant/mcp/application/McpCaller.java` 는 `record McpCaller(CurrentUser user, AgentExecution parent, McpCallContext context)` 이고 `executionId()` 가 `parent.id()` 를 돌려준다
- `new McpCaller(` 를 부르는 곳: `McpCallerResolver`(둘), `backend/src/test/java/com/bifos/assistant/mcp/application/McpToolServiceTest.java`(둘). `.parent()` 를 읽는 곳: `backend/src/test/java/com/bifos/assistant/orchestration/ResearchAndBuildFlowTest.java`

**근거 문서**: `docs/adr/ADR-032-mcp-토큰은-profile-을-증명하고-실제-사용자는-부모-실행에서-정한다.md` 의 「옛 토큰에서 옮겨 가는 길」 3번, `docs/data-schema.md` 의 「agent_token」 절, `docs/code-architecture.md` 의 「MCP 요청자」 절의 `McpCaller` 줄

## 의도 메모

- 이름만 바꾸고 판정은 바꾸지 않는다. 이 phase 에서 `originExecution` 은 여전히 도는 부모 실행이다
- `user_id` 를 비우는 것은 묶기 한 곳이다. 이미 묶인 토큰을 마이그레이션으로 비우지 않는다. 어느 토큰이 묶였는지는 운영 값이 아니지만, 운영 토큰 묶기가 이 PR 배포 뒤에 있어 지금 묶인 운영 토큰이 없다

## 작업 항목

### 1. `AgentToken.bindProfile` 이 `userId` 를 비운다

`backend/src/main/java/com/bifos/assistant/mcp/domain/AgentToken.java` 의 `bindProfile` 에서 `this.profileName = profileName;` 뒤에 `this.userId = null;` 을 더한다. Javadoc 에 까닭(옛 판으로 되돌려도 옛 사용자로 돌지 않는다)을 한 줄 적는다. 클래스 Javadoc 의 「옮겨 가는 동안에만 읽는다」 는 그대로 둔다.

### 2. `McpCaller` 의 칸 이름을 바꾼다

`backend/src/main/java/com/bifos/assistant/mcp/application/McpCaller.java` 를 `record McpCaller(CurrentUser user, AgentExecution originExecution, McpCallContext context)` 로 바꾼다.

- `@param originExecution`: 이 호출의 session 을 낳은 FOS 실행. 끝난 실행일 수 있다. 옛 토큰이면 null
- `executionId()` 는 `originExecution` 의 번호를 돌려준다

`McpCallerResolver.resolve` 의 지역 변수 `parent` 를 `origin` 으로 바꾼다. 생성자 인자 순서는 같아 `McpToolServiceTest` 의 호출부는 바뀌지 않는다.
`ResearchAndBuildFlowTest` 의 `caller.parent()` 세 곳을 `caller.originExecution()` 으로 바꾼다.

### 3. 이 phase 를 검증하는 `backend/src/test/java/com/bifos/assistant/mcp/AgentTokenServiceTest.java`

`옛_토큰은_한_번만_묶이고_묶인_뒤에는_사용자를_읽지_않는다` 에 단언을 더한다.

- 정상: 묶은 뒤 `jdbc.queryForObject("SELECT user_id FROM agent_token WHERE id = ?", Long.class, id)` 가 null 이다. 묶기 전에는 `admin.id()` 였음을 같은 질의로 먼저 확인한다
- 실패: 두 번째 묶기가 `VALIDATION_FAILED` 로 거절된 뒤에도 `user_id` 는 null 로 남는다

## 검증

```bash
cd backend && ./gradlew test --tests 'com.bifos.assistant.mcp.AgentTokenServiceTest' --tests 'com.bifos.assistant.mcp.application.McpToolServiceTest' --tests 'com.bifos.assistant.orchestration.ResearchAndBuildFlowTest'
cd backend && ./gradlew test
grep -rn '\.parent()' backend/src | grep -i 'caller'   # 결과가 없어야 한다
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/mcp/domain/AgentToken.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpCaller.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpCallerResolver.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/AgentTokenServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/orchestration/ResearchAndBuildFlowTest.java` | 수정 |
