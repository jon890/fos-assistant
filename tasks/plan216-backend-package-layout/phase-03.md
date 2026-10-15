# Phase 03. 관리자 컨트롤러와 서비스를 admin 으로 옮긴다

**Execution profile**: standard

## 목표

관리자 전용 컨트롤러와 `AgentAdminService` 를 `admin.<하위 기능>` 으로 옮긴다. 이동만 한다. 이 PR 이 머지되면 `CurrentUserProvider.requireAdmin` 을 부르는 곳이 `admin` 안에만 남는다.

**범위 외**: 기능 서비스의 방어적 관리자 확인 제거, `admin` 이 쓰는 저장소와 엔티티와 domain 값의 정리(기준 파일에 남긴다).

## 컨텍스트

- 공통 절차는 `move-procedure.md` 다.
- 결정은 `backend/docs/adr/ADR-20261015-package-layout.md` 의 「admin」 절이다.
- phase 02 가 끝나 있다. 관리자 컨트롤러는 모두 admin 전용 `*AdminDtos` 를 쓰고, 섞인 컨트롤러가 없다.
- 이 PR 의 추정 변경은 클래스 약 30개, 운영 코드 약 100줄이다. phase 02 의 새 클래스를 포함한다.

## 의도 메모

- `admin` 은 최상위 순서의 맨 위다(phase 01 이 `TopLevelPackageOrder` 에 더했다). 어느 기능도 `admin` 을 import 하지 않는다.
- `admin` 은 `application`(필요하면 `application.dto`)과 `presentation` 만 갖는다. `AgentAdminService` 는 `admin.agent.application` 에 둔다.
- `AgentAdminService` 는 지금 `AgentRepository`, `AppUserRepository`, `Agent` 엔티티를 쓴다. `*AdminDtos` 의 변환 메서드는 엔티티와 domain 값을 받는다. 이동만 하므로 그대로 두고 기준 파일에 얼린다.

## 작업 항목

### 1. 이동 표

phase 02 가 만든 새 컨트롤러와 `*AdminDtos` 도 같은 하위 기능으로 간다. 아래 표는 현재 있는 클래스이고, 새로 생긴 것은 표 아래에 적었다.

| 새 패키지 | 옛 패키지 | 클래스 |
| --- | --- | --- |
| `admin.agent.application` | `agent.admin.application` | AgentAdminService |
| `admin.agent.application.dto` | `agent.admin.application.model` | AgentCreateCommand, AgentUpdateCommand |
| `admin.agent.presentation` | `agent.admin.presentation` | AgentAdminController, AgentAdminDtos |
| `admin.attention.presentation` | `attention.presentation` | AttentionAdminController |
| `admin.browser.presentation` | `browser.presentation` | UserBrowserAdminController |
| `admin.connector.presentation` | `connector.presentation` | AdminAgentConnectionController, ConnectorConnectionAdminController |
| `admin.mcp.presentation` | `mcp.presentation` | AgentTokenAdminController |
| `admin.memory.presentation` | `memory.presentation` | AgentMemorySettingController |
| `admin.model.presentation` | `chat.presentation` | ModelAdminController |
| `admin.people.presentation` | `people.presentation` | PeopleAdminController |
| `admin.proactive.presentation` | `proactive.presentation` | ValueEvaluationAdminController |
| `admin.skill.presentation` | `skill.presentation` | SkillAdminController |
| `admin.toolset.presentation` | `agent.presentation` | ToolsetAdminController |
| `admin.usage.presentation` | `chat.presentation` | LatencyAdminController |
| `admin.workspace.presentation` | `workspace.presentation` | WorkspaceAdminController |

phase 02 가 만든 클래스는 다음처럼 옮긴다.

| 새 패키지 | 클래스 |
| --- | --- |
| `admin.toolset.presentation` | `AgentToolAdminController`, `ToolsetRequestAdminController`, `ToolsetAdminDtos` |
| `admin.usage.presentation` | `UsageBreakdownAdminController`, `UsageBreakdownAdminDtos`, `LatencyAdminDtos` |
| `admin.attention.presentation` | `AttentionAdminDtos` |
| `admin.browser.presentation` | `UserBrowserAdminDtos` |
| `admin.model.presentation` | `ModelAdminDtos` |
| `admin.connector.presentation` | `ConnectionAdminDtos` |
| `admin.mcp.presentation` | `AgentTokenAdminDtos` |
| `admin.memory.presentation` | `MemoryAdminDtos` |
| `admin.people.presentation` | `PeopleAdminDtos` |
| `admin.proactive.presentation` | `ProactiveAdminDtos` |
| `admin.skill.presentation` | `SkillAdminDtos` |
| `admin.workspace.presentation` | `WorkspaceAdminDtos` |

### 2. 규칙이 가리키는 전체 이름

- `AGENT_ADMIN_APPLICATION_PLACEMENT`, `AGENT_ADMIN_HTTP_CONTRACT` 와 `AgentAdminServicePlacementTest` 의 기대 전체 이름을 `admin.agent.application.AgentAdminService`, `admin.agent.application.dto.AgentCreateCommand`, `admin.agent.application.dto.AgentUpdateCommand`, `admin.agent.presentation.AgentAdminController`, `admin.agent.presentation.AgentAdminDtos` 로 고친다. 옛 위치로 되돌리는 fixture(`ROOT + ".agent.application.AgentAdminService"` 같은 것)도 새 기대에 맞춘다.
- `AGENT_ADMIN_HTTP_CONTRACT` 의 `requireAdmin` 검사는 `ADMIN_ENDPOINTS_REQUIRE_ADMIN` 이 모든 `admin` 컨트롤러에 대해 하므로 이 규칙에서 그 부분을 지운다. 이 규칙들의 대상 선택 조건(`agentAdminRoles`, `adminHttpRoles` 가 쓰는 `.agent.admin.` 경계와 `application.model` 리터럴)도 `admin.agent` 와 `application.dto` 로 다시 쓴다. 기대 전체 이름만 고치면 조건이 아무것도 고르지 못한다.

### 3. 기준 파일

- 줄어든다: `REQUIRE_ADMIN_ONLY_IN_ADMIN` 은 0이 되어 기준 파일 항목이 비면 지운다.
- **더한다(이 목록만)**: `ADMIN_USES_ONLY_FEATURE_APPLICATION` 13줄이다. 모두 끝까지 남는다. 코드 대조로 확인한 줄이고, phase 02 가 만든 `*AdminDtos` 의 변환 메서드가 쓰는 타입(아래 표의 `*AdminDtos` 줄)은 phase 02 PR 이 실제 모양을 확정한 뒤 이 표를 고친다.
- 옛 이름 `application.model` 과 `application.toolset` 의 타입을 쓰는 줄은 규칙이 허용하므로 없다. 기능이 옮겨지면 새 이름이 되어 그대로 허용이다.

| `admin` 클래스 | 쓰는 것 | 있는 곳 |
| --- | --- | --- |
| `AgentAdminService` | `AgentRepository` | `agent.infra` |
| `AgentAdminService` | `AppUserRepository` | `user.infra` |
| `AgentAdminService` | `Agent` | `agent.domain` |
| `AgentAdminDtos` | `Agent` | `agent.domain` |
| `PeopleAdminController` | `AllowedPerson` | `people.domain` |
| `PeopleAdminDtos` | `AllowedPerson` | `people.domain` |
| `AgentTokenAdminDtos` | `AgentToken` | `mcp.domain` |
| `ModelAdminDtos` | `ModelChoice` | `model.domain` |
| `UsageBreakdownAdminDtos` | `CostByAgent` | `usage.domain`(옮긴 뒤 `usage.execution.domain`) |
| `UsageBreakdownAdminDtos` | `CostByDay` | 위와 같다 |
| `UsageBreakdownAdminDtos` | `CostByFingerprint` | 위와 같다 |
| `UsageBreakdownAdminDtos` | `CostByModel` | 위와 같다 |
| `ProactiveAdminDtos` | `ProactiveCheck` | `proactive.domain`(옮긴 뒤 `proactive.check.domain`) |

PR 5-1 이 `AgentAdminService` 가 `AgentConnectorBindings`(`agent.application.port`)를 쓰는 한 줄을 더한다. port 는 `application` 이 아니기 때문이다. 합쳐 14줄이 이동이 끝난 뒤에도 남는다.

### 4. 이 phase 를 검증하는 테스트

- `./gradlew archTest` 가 통과한다. `ADMIN_PACKAGE_SHAPE`, `FEATURES_DO_NOT_DEPEND_ON_ADMIN`, `ADMIN_ENDPOINTS_REQUIRE_ADMIN` 은 기준 파일 없이 통과한다.
- 관리자 컨트롤러 테스트가 통과한다. 경로와 응답은 바뀌지 않는다.
- `scripts/pr/pr-risk-labels.sh` 의 보안 패턴 `mcp/*` 가 옮긴 `admin/mcp/*` 도 덮게 고친다. 다른 라벨의 경로는 이 PR 에서 바꾸지 않는다. `people/application/SignInPolicy.java` 와 `hermes/*` 는 이후 phase 에서도 같은 경로에 남는다.
