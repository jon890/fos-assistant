# Phase 02. Control Plane 이 실행 공간 주인을 보내고 스킬의 비밀 요청 칸을 거절한다

**Execution profile**: standard

## 목표

도구 저장 때 `sandbox_owner` 를 보내고, plugin 의 409 `sandbox_unavailable` 을 `AGENT_SANDBOX_UNAVAILABLE` 로 알린다. 올린 스킬 앞머리의 비밀 요청 칸을 거절한다. 가짜 Hermes 와 e2e 가 이 계약을 따른다.

**범위 외**: plugin(phase 01), 화면 문구(phase 03).

## 컨텍스트

**근거 문서**: `docs/backend/agent.md` 의 「에이전트 도구를 고를 때」, `docs/backend/skill.md`, `docs/adr/ADR-084-셸과-파일-도구는-사용자별-docker-실행-공간에서만-돈다.md`

- 흐름과 갈리는 지점: `docs/backend/agent.md` 의 「에이전트 도구」, 「에이전트 도구를 고를 때」, 「도구 변경이 갈리는 지점」
- 스킬 규칙: `docs/backend/skill.md` 의 저장 규칙 표(「앞머리의 비밀 요청 칸」)와 실패 표
- plugin 계약: `hermes/README.md` 의 `PUT /api/config` (도구) 줄. `sandbox_owner` 는 `^[a-z][a-z0-9-]{0,63}$`
- 결정: `docs/adr/ADR-084-셸과-파일-도구는-사용자별-docker-실행-공간에서만-돈다.md`
- 지금 코드: `backend/src/main/java/com/bifos/assistant/hermes/HermesToolsetClient.java` 의 `writeApiServer(String profileName, List<String> toolsets)`, 구현 `HttpHermesToolsetClient`, 부르는 곳 `agent/application/AgentToolService.write`. 4xx 구분은 `HermesCallFailure.ofDistinguishingRejection` 과 `HermesRequestRejected` 가 이미 있다. 오류 코드는 `shared/error/ErrorCode.java`(`AGENT_TOOLS_REQUIRE_PRIVATE(HttpStatus.CONFLICT)` 옆).
- `Agent` 는 `@Accessors(fluent = true)` 라 `agent.id()`, `agent.ownerUserId()`(nullable) 다.
- 스킬 앞머리 파서: `skill/application/SkillFrontmatter.parse`. 저장 검사: `SkillService.requireSkillMd`. 파서는 옛 스킬 설명을 읽는 자리에서도 쓰이므로 파서가 거절하지 않고 저장 검사가 거절한다.
- 가짜 Hermes: `test/e2e/fake-hermes.ts` 의 `PUT /api/config` 처리(`exactKeys` 검사). 시험용 경로 상수는 `TEST_HOLD_NEXT_CONFIG_PATH` 처럼 `/__test/...` 로 둔다. e2e 시나리오: `test/e2e/scenarios/agent-tools.ts`.

## 의도 메모

- 주인 키를 사용자 번호로 쓴다. 저장소 밖으로 나가지 않는 서버 쪽 경로 이름이다. 주인이 없는 에이전트는 `a<에이전트 번호>` 로 그 에이전트만의 공간을 쓴다.
- 409 의 본문에 `sandbox_unavailable` 이 있을 때만 새 코드로 옮긴다. 다른 4xx 는 지금처럼 `HERMES_UNAVAILABLE` 이다.

## 작업 항목

### 1. `HermesToolsetClient`, `HttpHermesToolsetClient`

- `void writeApiServer(String profileName, List<String> toolsets, String sandboxOwner)` 로 바꾼다. 본문은 `{"profile", "config": {"platform_toolsets": {"api_server": [...]}}, "sandbox_owner"}`.
- `RestClientResponseException` 이 409 이고 응답 본문에 `sandbox_unavailable` 이 있으면 `new ApiException(ErrorCode.AGENT_SANDBOX_UNAVAILABLE, "the isolated shell workspace is not configured")`.

### 2. `ErrorCode`

`AGENT_SANDBOX_UNAVAILABLE(HttpStatus.CONFLICT)` 를 한국어 Javadoc 과 함께 더한다.

### 3. `AgentToolService`

`sandboxOwner(Agent)` : `ownerUserId` 가 있으면 `"u" + ownerUserId`, 없으면 `"a" + id`. `write` 가 그 값을 넘긴다.

### 4. `SkillFrontmatter`, `SkillService`

- `SkillFrontmatter` 에 `boolean requestsSecrets` 를 더한다. 앞머리 최상위 `required_environment_variables`, `required_credential_files` 가 있거나, `setup` 이 map 이고 `collect_secrets` 가 있거나, `prerequisites` 가 map 이고 `env_vars` 가 있으면 true.
- `SkillService.requireSkillMd` 가 true 면 `VALIDATION_FAILED`, 메시지 `"SKILL.md frontmatter must not request environment values or credential files"`.

### 5. 테스트

- `backend/src/test/java/com/bifos/assistant/hermes/HermesToolsetRequestTest.java`: 본문에 `sandbox_owner` 가 실린다. 409 `{"code":"sandbox_unavailable"}` 응답이 `AGENT_SANDBOX_UNAVAILABLE` 이다. 다른 409 는 `HERMES_UNAVAILABLE`.
- `backend/src/test/java/com/bifos/assistant/agent/AgentToolServiceTest.java`, `AgentToolServiceAccessTest.java`: 주인이 있으면 `u<번호>`, 없으면 `a<번호>` 를 넘긴다. 기존 `writeApiServer` 검증을 세 인자로 고친다.
- `backend/src/test/java/com/bifos/assistant/skill/SkillServiceTest.java`, `backend/src/test/java/com/bifos/assistant/connector/ConnectorConnectionServiceTest.java`: `never()` 검증을 세 인자로 고친다.
- `backend/src/test/java/com/bifos/assistant/skill/SkillFrontmatterTest.java`: 네 칸 각각이 `requestsSecrets` true, 없는 앞머리는 false.
- `SkillServiceTest`: 비밀 요청 칸이 있는 `SKILL.md` 저장이 `VALIDATION_FAILED`.

### 6. 가짜 Hermes 와 e2e

- `test/e2e/fake-hermes.ts`: 본문 키에 `sandbox_owner` 를 허용한다. 셸 도구(`terminal`, `file`, `code_execution`)가 목록에 있으면 `sandbox_owner` 가 `^[a-z][a-z0-9-]{0,63}$` 이어야 한다(아니면 400). `POST /__test/sandbox-unavailable` 본문 `{ unavailable: boolean }` 으로 켜면 셸 도구가 있는 저장에 409 `{detail, code: "sandbox_unavailable"}` 를 돌려준다. 마지막으로 받은 `sandbox_owner` 를 `GET /__test/last-sandbox-owner` 로 돌려준다.
- `test/e2e/scenarios/agent-tools.ts`: 관리자가 terminal 을 켠 뒤 마지막 `sandbox_owner` 가 `u` 로 시작한다. 실행 공간을 끈 상태에서 terminal 을 켜면 409 `AGENT_SANDBOX_UNAVAILABLE` 이고 목록이 바뀌지 않는다. 다시 켜 둔다.

## 검증

```bash
# cwd: 저장소 root
(cd backend && ./gradlew test --tests '*HermesToolsetRequestTest' --tests '*AgentToolService*' --tests '*Skill*' --tests '*ConnectorConnectionServiceTest')
(cd backend && ./gradlew test)
(cd backend && ./gradlew qualityCheck)
node test/e2e/run.ts
scripts/quality.sh check
```

## 변경 파일

| 파일 | 변경 |
|---|---|
| `backend/src/main/java/com/bifos/assistant/hermes/HermesToolsetClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HttpHermesToolsetClient.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentToolService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillFrontmatter.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/skill/application/SkillService.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/HermesToolsetRequestTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentToolServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentToolServiceAccessTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/skill/SkillServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/skill/SkillFrontmatterTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorConnectionServiceTest.java` | 수정 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/e2e/scenarios/agent-tools.ts` | 수정 |
