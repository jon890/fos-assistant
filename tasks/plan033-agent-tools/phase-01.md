# Phase 01. 도구 등급과 도구 API 를 만든다

**Execution profile**: deep

## 목표

에이전트의 toolset 을 읽고 바꾸는 Control Plane API 를 만든다. 누가 무엇을 켤 수 있는지는 등급으로 판정하고, 목록은 Hermes 공식 설정 API 로 쓴다.
사용자가 자기 에이전트의 도구를 화면에서 고르게 하려는 것이다.

**범위 외**: 화면은 phase 02 다. 스킬 올리기와 사용자가 에이전트를 만드는 일은 다른 계획이다. fos-home-infra 의 대시보드 plugin 변경은 그 저장소의 PR 이 맡는다(아래 Blocked 조건).

## 컨텍스트

**근거 문서**: `docs/adr/ADR-029-에이전트-도구는-control-plane-이-등급으로-판정하고-hermes-설정-api-로-쓴다.md`, `docs/code-architecture.md` 「에이전트 도구」, `docs/flow.md` 「에이전트 도구를 고를 때」, `docs/hermes/tools-and-skills.md` 「v0.21.3 에서 확인한 쓰기 경로」

지금 모양(구현 전에 다시 읽는다):

| 자리 | 지금 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesDashboardClient.java` | `createProfile`, `putEnv`, `deleteProfile`, `readSoul`, `putSoul`. 구현은 `HttpHermesDashboardClient` 가 `HermesProperties.dashboardBaseUrl()` 과 `dashboardToken()` 으로 `Authorization: Bearer` 를 붙인다 |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesModelClient.java` | `readModel(apiBaseUrl, profileName)` 이 `HermesProfileKeyStore.resolve(profileName)` 의 key 로 `{apiBaseUrl}/api/model/options` 를 부른다. listener 를 부르는 본보기다 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentService.java` | `requireReadable(CurrentUser, code)`, `isEditableBy(CurrentUser, Agent)` |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentAdminController.java` | `PATCH /api/v1/admin/agents/{code}` 가 `agent.changeAccess(enabled, visibility, ownerId)` 로 공개 범위를 바꾼다 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | `VALIDATION_FAILED`, `FORBIDDEN`, `AGENT_NOT_FOUND`, `HERMES_UNAVAILABLE` 등 |
| 검사 본보기 | `backend/src/test/java/com/bifos/assistant/hermes/HermesDashboardRequestTest.java` 가 `com.sun.net.httpserver.HttpServer` 로 대시보드를 흉내 낸다 |
| e2e | `test/e2e/fake-hermes.ts` 가 `/api/profiles`, `/api/env` 등을 흉내 낸다 |

대시보드 plugin 이 여는 요청 모양(fos-home-infra PR 과 맞춘 계약이다):

```json
PUT /api/config
{ "profile": "<profile>", "config": {
    "platform_toolsets": { "api_server": ["web", "vision", "fos-assistant-memory"] },
    "agent": { "disabled_toolsets": ["memory"] } } }
```

plugin 은 다른 키, query 와 본문의 profile 불일치, `memory` 포함, `fos-assistant-memory` 누락, 모르는 이름을 거절한다. `GET /api/tools/toolsets` 도 연다.

## 의도 메모

- 등급 표는 `AgentToolPolicy` 한 곳에 둔다. 표는 ADR-029 「도구 등급」 과 같다. 표에 없는 toolset 은 목록에 보이지 않고 늘 꺼 둔다
- 도구 선택을 데이터베이스에 저장하지 않는다. 읽을 때와 쓸 때 Hermes 에서 읽는다(ADR-029)
- `PUT` 본문의 `enabled` 는 켤 toolset 전체다. 요청자가 바꿀 수 없는 등급의 toolset 은 지금 켜짐과 같아야 한다. 다르면 `FORBIDDEN`
- 셸·파일 계열(`terminal`, `file`, `code_execution`, `browser`, `computer_use`)은 `GROUP` 에이전트에 켤 수 없다. 공개 범위를 `GROUP` 으로 바꿀 때 그 계열이 켜져 있으면 거절한다. 새 오류 코드 `AGENT_TOOLS_REQUIRE_PRIVATE` 를 둔다(409)
- 쓴 뒤 다시 읽은 목록이 보낸 것과 다르면 새 오류 코드 `AGENT_TOOLS_NOT_APPLIED` 로 알린다(502). 되돌리려 하지 않는다. 화면이 다시 읽은 목록을 보인다
- 대시보드나 listener 를 부르지 못하면 `HERMES_UNAVAILABLE`
- 에이전트의 listener 주소는 `Agent.apiBaseUrl()` 이다. `{apiBaseUrl}/v1/toolsets` 를 그 profile 의 key 로 부른다

## Blocked 조건

- fos-home-infra 의 plugin PR 이 main 에 없으면 운영에서 동작하지 않는다. 이 phase 는 가짜 대시보드로 검사하므로 막히지 않는다. PR 본문에 「plugin PR 이 먼저 배포되어야 한다」 를 적는다

## 작업 항목

### 1. `AgentToolPolicy` 를 만든다

`backend/src/main/java/com/bifos/assistant/agent/domain/AgentToolPolicy.java`. 등급(`OWNER`, `ADMIN`), 셸·파일 계열 집합, `memory` 금지, `fos-assistant-memory` 상수. 요청 목록과 지금 목록과 요청자로 쓸 목록을 계산하고 규칙을 어기면 예외를 던진다

### 2. Hermes toolset 클라이언트를 만든다

`backend/src/main/java/com/bifos/assistant/hermes/HermesToolsetClient.java`(interface)와 `HttpHermesToolsetClient.java`:
- `readCatalog()`: 대시보드 `GET /api/tools/toolsets` → 이름, 이름표, 설명
- `readEnabled(apiBaseUrl, profileName)`: `{apiBaseUrl}/v1/toolsets` 를 profile key 로 → 켜진 toolset 이름
- `writeApiServer(profileName, List<String> toolsets)`: 위 계약 모양의 `PUT /api/config`

### 3. `AgentToolService` 와 경로를 만든다

`backend/src/main/java/com/bifos/assistant/agent/application/AgentToolService.java` 와 `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentToolController.java`:
- `GET`, `PUT /api/v1/agents/{code}/tools`: `requireReadable` 로 찾는다. 쓰기는 주인 등급은 주인과 `ADMIN`, 관리자 등급은 `ADMIN`
- `GET`, `PUT /api/v1/admin/agents/{code}/tools`: `ADMIN` 만. 읽기 권한 검사 없이 에이전트를 찾는다
- 응답 한 줄: `name`, `label`, `description`, `tier`, `enabled`, `editable`, `requiresPrivate`
- `ErrorCode` 에 `AGENT_TOOLS_REQUIRE_PRIVATE`, `AGENT_TOOLS_NOT_APPLIED` 를 더한다

### 4. 공개 범위를 바꿀 때 셸·파일 계열을 본다

`AgentAdminController.update` 가 `GROUP` 으로 바꿀 때 `readEnabled` 로 지금 목록을 읽어 셸·파일 계열이 있으면 `AGENT_TOOLS_REQUIRE_PRIVATE`. 읽지 못하면 바꾸지 않고 `HERMES_UNAVAILABLE`

### 5. e2e 가짜 Hermes 에 세 경로를 더한다

`test/e2e/fake-hermes.ts` 에 `GET /api/tools/toolsets`, `PUT /api/config`(plugin 규칙과 같게 거절), `GET /p/<profile>/v1/toolsets` 를 더하고 `test/e2e/scenarios/agent-tools.ts` 를 만든다. 주인이 `web` 을 켜고, `terminal` 을 켜려다 거절되고, `ADMIN` 이 `PRIVATE` 에이전트에 `terminal` 을 켜고, 그 에이전트를 `GROUP` 으로 바꾸려다 거절되는 흐름이다

### 6. 이 phase 를 검증하는 backend 검사

- `AgentToolPolicyTest`: 등급별 허용과 거절, `memory` 거절, 기억 MCP 가 늘 들어가는 것, `GROUP` 에 셸·파일 계열 거절
- `HermesToolsetRequestTest`: `HermesDashboardRequestTest` 처럼 가짜 서버로 세 요청의 경로, 머리, 본문 모양을 본다
- `AgentToolServiceTest`: 쓴 뒤 다시 읽은 목록이 다르면 `AGENT_TOOLS_NOT_APPLIED`, 대시보드 실패는 `HERMES_UNAVAILABLE`

## 검증

```bash
# cwd: 저장소 root
cd backend && ./gradlew test
node test/e2e/run.ts
scripts/check-public-safe.sh
```

모두 통과한다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/agent/domain/AgentToolPolicy.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesToolsetClient.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/hermes/HttpHermesToolsetClient.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/application/AgentToolService.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentToolController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/agent/presentation/AgentAdminController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/error/ErrorCode.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentToolPolicyTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/hermes/HermesToolsetRequestTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/agent/AgentToolServiceTest.java` | 신규 |
| `test/e2e/fake-hermes.ts` | 수정 |
| `test/e2e/scenarios/agent-tools.ts` | 신규 |
| `test/e2e/run.ts` | 수정 |
