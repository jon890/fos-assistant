# Phase 02. 관리자 엔드포인트 선행 분리

**Execution profile**: standard

## 목표

이동 PR 이 이동만 하도록, 사용자 API 와 관리자 API 가 섞인 컨트롤러를 먼저 나누고 관리자 컨트롤러가 쓰는 요청과 응답 모양을 admin 전용 `*Dtos` 로 가른다. 기능 패키지 안에서 제자리로 한다.
경로와 응답 JSON 은 바뀌지 않는다.

**범위 외**: `admin` 패키지로 옮기기(phase 03).

이 phase 는 PR 하나(2-1)다.

## 컨텍스트

- 결정은 `backend/docs/adr/ADR-20261015-package-layout.md` 의 「admin」 절이 갖는다.
- `CurrentUserProvider.requireAdmin` 을 부르는 클래스는 컨트롤러 17개다. 그 가운데 셋이 사용자 경로와 관리자 경로를 한 클래스에 두고 있다.
- 관리자 컨트롤러 13개가 같은 패키지의 `*Dtos` 를 가져다 쓴다. `admin` 은 기능의 `presentation` 을 쓰지 않으므로, 옮기기 전에 갈라 둬야 한다.
- 서비스 안의 관리자 확인(`AgentService`, `ConnectorConnectionService` 의 private 도우미)은 이번에 건드리지 않는다. 방어적 확인으로 남긴다.
- 관리자 컨트롤러의 경로가 모두 `/api/v1/admin/` 아래인 것은 아니다. `UsageController` 의 축별 집계는 `/api/v1/usage/breakdown` 이고 웹이 그 경로를 부른다. 경로는 바꾸지 않는다.

## 의도 메모

- 관리자 컨트롤러를 새로 만들 때 메서드 본문은 옮기기만 하고 첫 줄의 `requireAdmin` 을 유지한다.
- 같은 record 를 사용자 컨트롤러도 쓰면 admin 쪽에 같은 모양으로 새로 둔다(복사). 두 API 의 계약이 따로 바뀔 수 있어서다. 옮긴 record 를 쓰는 곳이 원본에 남지 않으면 원본을 지운다.
- 이 phase 는 동작을 바꾸지 않는 분리라 기존 컨트롤러 테스트가 그대로 통과해야 한다. 테스트를 새 컨트롤러 이름으로 나누되 단언은 바꾸지 않는다.

## 작업 항목

### 1. 섞인 컨트롤러 셋을 나눈다

| 컨트롤러 | 그대로 남는 경로 | 새 관리자 컨트롤러 | 새 컨트롤러의 경로 |
| --- | --- | --- | --- |
| `AgentToolController` | `GET`, `PUT /api/v1/agents/{code}/tools` | `AgentToolAdminController` (`agent.presentation`) | `GET`, `PUT /api/v1/admin/agents/{code}/tools` |
| `ToolsetRequestController` | `GET`, `POST /api/v1/agents/{code}/tool-requests`, `GET /api/v1/tool-requests/{id}`, `POST /api/v1/tool-requests/{id}/cancel` | `ToolsetRequestAdminController` (`agent.presentation`) | `GET /api/v1/admin/agents/{code}/tool-requests`, `GET`, `POST /api/v1/admin/tool-requests/{id}` |
| `UsageController` | 실행 목록, 실행 트리, 월 비용, 내 스킬 사용 | `UsageBreakdownAdminController` (`usage.presentation`) | `GET /api/v1/usage/breakdown` |

### 2. admin 전용 `*Dtos` 를 가른다

관리자 컨트롤러마다 쓰는 record 를 같은 패키지의 새 `*AdminDtos` 로 옮긴다. 새 컨트롤러 셋(작업 항목 1)도 같다. 같은 패키지에 있는 컨트롤러가 둘 이상이면 한 파일에 모으고, 패키지가 다르면 파일을 나눈다(`LatencyAdminDtos` 는 `chat.presentation`, `UsageBreakdownAdminDtos` 는 `usage.presentation`).

| 관리자 컨트롤러 | 옮길 record | 새 파일 | 공용(복사) |
| --- | --- | --- | --- |
| `ToolsetAdminController`, `AgentToolAdminController`, `ToolsetRequestAdminController` | `AgentDtos` 의 `CatalogToolsetView`, `HiddenToolsetsRequest`, `DecideToolsetRequest`, `ToolsetsView`, `ToolsetView`, `UpdateToolsetsRequest`, `ToolsetRequestResponse` | `ToolsetAdminDtos` | 뒤의 넷(`ToolsetsView`, `ToolsetView`, `UpdateToolsetsRequest`, `ToolsetRequestResponse`)은 사용자 컨트롤러도 쓰므로 복사한다. 앞의 셋은 관리자 전용이라 옮긴다 |
| `AttentionAdminController` | `AttentionDtos` 의 `MetricRow`, `MetricsResponse` | `AttentionAdminDtos` | 없음 |
| `UserBrowserAdminController` | `UserBrowserDtos` 의 `AdminBrowserView` | `UserBrowserAdminDtos` | 없음 |
| `ModelAdminController` | `ChatDtos` 의 `AgentModelDefaultView`, `AgentModelSettingsView`, `ChooseModelRequest`, `HiddenModelsView` | `ModelAdminDtos` | `ChooseModelRequest` 는 `ChatController` 도 쓰므로 복사한다 |
| `LatencyAdminController` | `ChatDtos` 의 `LatencySummaryView` | `LatencyAdminDtos` (`chat.presentation`) | 없음 |
| `UsageBreakdownAdminController` | `UsageDtos` 의 `BreakdownView`, `BreakdownRow` | `UsageBreakdownAdminDtos` (`usage.presentation`) | 없음 |
| `ConnectorConnectionAdminController`, `AdminAgentConnectionController` | `ConnectionDtos` 의 `AdminConnectionView`, `AgentConnectionResponse`, `ConfirmRequest` | `ConnectionAdminDtos` | `AgentConnectionResponse` 는 `AgentConnectionController` 도 쓰므로 복사한다 |
| `AgentTokenAdminController` | `AgentTokenDtos` 전체 | 파일 이름을 `AgentTokenAdminDtos` 로 바꾼다 | 없음 |
| `AgentMemorySettingController` | `MemoryDtos` 의 `AgentMemoryGrantBody`, `AgentMemorySettingView`, `ReplaceAgentMemoryRequest` | `MemoryAdminDtos` | 없음 |
| `PeopleAdminController` | `PeopleDtos` 의 `CreatePersonRequest`, `PersonView`, `UpdatePersonRequest` | `PeopleAdminDtos` | 없음 |
| `ValueEvaluationAdminController` | `ProactiveCheckDtos` 의 `EvaluationOverviewResponse`, `EvaluationRequest` | `ProactiveAdminDtos` | `EvaluationRequest` 는 `ValueEvaluationController` 도 쓰므로 복사한다 |
| `SkillAdminController` | `SkillDtos` 의 `SkillListView`, `ToggleSkillRequest` | `SkillAdminDtos` | 둘 다 `SkillController` 도 쓰므로 복사한다 |
| `WorkspaceAdminController` | `WorkspaceDtos` 의 `AdminUsageView` | `WorkspaceAdminDtos` | 없음 |

`AgentAdminController` 는 이미 `AgentAdminDtos` 를 갖는다. 다시 가르지 않는다.
`CONTROLLERS_HAVE_NO_NESTED_RECORDS` 와 「`*Dtos.java` 는 패키지마다 하나」 규칙은 유지한다. 한 패키지에 `*AdminDtos` 와 `*Dtos` 가 함께 있는 것은 허용한다.

### 3. 기준 파일

`REQUIRE_ADMIN_ONLY_IN_ADMIN` 의 줄은 늘지 않는다. 옛 세 컨트롤러에서 관리자 메서드의 줄이 사라지고 같은 호출이 새 세 컨트롤러(`AgentToolAdminController`, `ToolsetRequestAdminController`, `UsageBreakdownAdminController`)의 줄로 나타난다. 기준 대조가 단순 클래스 이름이라 새 이름의 줄은 기준에 없으므로, 이 PR 이 그 줄들로 바꿔 얼린다. 방법은 `move-procedure.md` 순서 7 이고 규칙 하나만 `--tests` 로 한정한다.
`ADMIN_USES_ONLY_FEATURE_APPLICATION` 은 아직 `admin` 클래스가 없어 줄이 없다.

### 4. 이 phase 를 검증하는 테스트

- 기존 컨트롤러 테스트가 새 컨트롤러 이름으로 나뉘어 통과한다. 사용자 경로는 관리자가 아니어도 열리고, 관리자 경로는 `MEMBER` 에게 `FORBIDDEN` 이다.
- `node --test test/unit/doc-code-references.test.ts` 가 통과한다. API 경로 문서와 코드의 매핑이 그대로 맞아야 한다.
- 응답 JSON 이 같다. 관리자 컨트롤러마다 나누기 전후 응답의 필드 이름과 순서가 같은 단언을 둔다.
