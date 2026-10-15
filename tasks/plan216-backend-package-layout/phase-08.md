# Phase 08. connector 이동

**Execution profile**: standard

## 목표

`connector` 를 다섯 하위 기능으로 나눈다. `connection` 이 가장 아래이고 `approval` 이 가장 위다.

**범위 외**: 이동 표에 없는 클래스와, 이동으로 새로 생기는 코드 변경(승격 외).

## 컨텍스트

- 공통 절차는 `move-procedure.md` 다. 순서 1부터 10까지 모두 따른다.
- 결정은 `backend/docs/adr/ADR-20261015-package-layout.md` 다. 역할 패키지의 뜻은 그 「결정」 표가, 하위 기능의 순서와 뜻은 「하위 기능」 표가 갖는다.
- 표의 「옛 패키지」 는 이 PR 시작 시점의 위치다. 앞선 이동 PR 이 같은 클래스의 위치를 바꾸지 않았다는 전제다.
- 표의 † 는 `application` 직하에 남는 public 비빈 클래스다. 기준 파일(`APPLICATION_ROOT_HOLDS_ONLY_BEANS`)에 얼린 채로 둔다.
- 추정 줄 수는 앞선 이동이 끝난 상태를 가정한다.

## 이동 PR 8-1: connector.connection

- 옮기는 클래스 47개. 추정 변경은 운영 코드 약 480줄이다(상한 1500).

### 이동 표

| 새 패키지 | 옛 패키지 | 클래스 |
| --- | --- | --- |
| `connector.connection.application` | `connector.application` | ConnectorBindingApplier, ConnectorBindingInstalls, ConnectorBindingLookup, ConnectorBindingService, ConnectorBrowserGrantOwners, ConnectorCallLimiter, ConnectorCatalogCache, ConnectorConnectionService, ConnectorErrors, ConnectorInstallAttempts, ConnectorManifests†, ConnectorToolPolicies†, ConnectorValues |
| `connector.connection.application.dto` | `connector.application.model` | AdminConnectionSnapshot, AgentConnectionView, AgentConnectionsView, BoundAgentSummary, ConnectionSnapshot, ConnectorBindingAttempt, ConnectorFieldSummary, ConnectorInstallResult, ConnectorOperationFailure, ConnectorOption, ConnectorPolicyAnswer, ConnectorSummary, ConnectorToolSummary, ResyncOutcome |
| `connector.connection.config` | `connector.application` | ConnectorBindingProperties, ConnectorPolicyProperties, ConnectorProperties |
| `connector.connection.domain` | `connector.domain` | ConnectionFields, ConnectorBinding, ConnectorConnection, DueBinding, HermesToolName, ReadyBinding, ToolPolicy |
| `connector.connection.domain.type` | `connector.domain.type` | BindingStatus, ConnectionStatus, ToolApproval, ToolRisk |
| `connector.connection.infra` | `connector.infra` | ConnectionFieldsConverter, ConnectorBindingRepository, ConnectorConnectionRepository |
| `connector.connection.presentation` | `connector.presentation` | AgentConnectionController, ConnectionDtos, ConnectorConnectionController |

### 승격 (package-private → public)

없다. 컴파일 오류로 더 나오면 `move-procedure.md` 의 순서 3 을 따른다.

### 문자열 전체 이름

| 파일 | 가리키는 클래스 |
| --- | --- |
| `ConnectorBindingRepository` | DueBinding, ReadyBinding |

### 규칙 파일의 import

`architecture` 테스트가 import 하는 클래스 가운데 이 PR 이 옮기는 것: `BindingStatus`, `ConnectorPolicyProperties`.

### 기준 파일에 더하는 줄

없다. 이 PR 이 새로 만드는 위반은 없어야 한다.


### 이 PR 의 메모

- `ConnectorBindingRepository` 의 JPQL 이 `DueBinding`, `ReadyBinding` 을 전체 이름으로 만든다. 2곳이다.
- `ConnectorManifests`, `ConnectorToolPolicies` 는 public 비빈이라 `connector.connection.application` 에 남는다.
- `ConnectorNormalization` 은 두 스냅샷 클래스(`ConnectorConditionalSnapshot`, `ConnectorOrderSnapshot`)만 쓰고 `ConnectorExecutionSnapshot` 의 static 메서드를 쓰므로 `connector.financial` 몫이다. 이 PR 의 표에 없고 다음 PR 이 옮긴다.

## 이동 PR 8-2: connector.action, approval, financial, contentdraft

- 옮기는 클래스 107개. 추정 변경은 운영 코드 약 750줄이다(상한 1500).

### 이동 표

| 새 패키지 | 옛 패키지 | 클래스 |
| --- | --- | --- |
| `connector.action.application` | `connector.application` | ConnectorActionBodyBackfill, ConnectorActionDetails, ConnectorActionSignals |
| `connector.action.application.dto` | `connector.application.model` | ConnectorActionChanged, ConnectorActionResult, ConnectorActionView, PendingApproval |
| `connector.action.domain` | `connector.domain` | ActionDelivery, ConnectorAction, ConnectorActionBodyOpener, ConnectorActionExecution, ConnectorActionKey, ConnectorConversationGrant, ConnectorToolGrant, ToolPolicyDecision |
| `connector.action.domain.type` | `connector.domain.type` | ActionDecision, ActionDenyReason, ActionStatus, GrantPeriod |
| `connector.action.infra` | `connector.infra` | ConnectorActionBodies, ConnectorActionExecutionRepository, ConnectorActionLoadListener, ConnectorActionRepository, ConnectorConversationGrantRepository, ConnectorToolGrantRepository |
| `connector.approval.application` | `connector.application` | ConnectorActionApproval, ConnectorActionExpirer, ConnectorActionLifecycle, ConnectorActionListener, ConnectorActionResultSource, ConnectorActionService, ConnectorActionSweeper, ConnectorAgentPurge, ConnectorApprovalReply, ConnectorCallHistory, ConnectorCheckReportApprovals, ConnectorFinancialApproval, ConnectorFinancialIntake, ConnectorFinancialOutcome, ConnectorFinancialProducer, ConnectorFinancialSummaries, ConnectorGrantLookup, ConnectorPolicyService, ConnectorUnknownResends, FinancialLimitChanges |
| `connector.approval.application.dto` | `connector.application` | ConnectorPolicyRequest |
| `connector.approval.application.dto` | `connector.application.model` | ConnectorConversationGrantView, ConnectorGrantView, FinancialActionSummary, FinancialLimitChangeSummary |
| `connector.approval.presentation` | `connector.presentation` | ConnectorActionController, ConnectorActionDtos, ConnectorPolicyController |
| `connector.contentdraft.application` | `connector.application` | ConnectorContentDraftBindings, ContentDraftJobAuthorizer, ContentDraftJobExpirer, ContentDraftJobLocks, ContentDraftJobs, ContentDraftRefusal, ContentDraftSaveApproval, ContentDraftSaveTicket, ContentDraftWorkerStatusDecoder†, HttpContentDraftJobStarter |
| `connector.contentdraft.application.dto` | `connector.application` | ContentDraftLockedJob |
| `connector.contentdraft.application.dto` | `connector.application.model` | ContentDraftActionSummary, ContentDraftSaveTicketPayload, ContentDraftWorkerStatus, PreparedContentDraftApproval |
| `connector.contentdraft.application.port` | `connector.application` | ContentDraftJobStarter |
| `connector.contentdraft.presentation` | `connector.presentation.contentjob` | ContentDraftJobAuthorizeController, ContentDraftJobAuthorizeDtos |
| `connector.financial.application` | `connector.application` | ConnectorConditionalSnapshot, ConnectorExecutionSnapshot†, ConnectorFinancialConflicts, ConnectorFinancialGate, ConnectorNormalization, ConnectorOrderSnapshot, FinancialDigest, FinancialLimitExceeded†, FinancialLimitSettings, FinancialOrderLimits |
| `connector.financial.application` | `connector.application.execution` | ConnectorExecutionClaimCodec, ConnectorExecutionClaims, ConnectorExecutionCurrent, ConnectorExecutionGuard, ConnectorExecutionTicket, ConnectorGuardSupport |
| `connector.financial.application.dto` | `connector.application.model` | ConnectorExecutionCatalog, ConnectorExecutionClaimInput, ConnectorExecutionClaimResult, ConnectorExecutionGuardContract, ConnectorExecutionReadContext, ConnectorExecutionTicketPayload, FinancialLimitStatus, FinancialLimitValues, FinancialLimitsView |
| `connector.financial.config` | `connector.application` | ConnectorFinancialProperties |
| `connector.financial.domain` | `connector.domain` | FinancialLimitChange, FinancialOrderLimit |
| `connector.financial.domain.type` | `connector.domain.type` | FinancialLimitKind |
| `connector.financial.infra` | `connector.infra` | FinancialLimitChangeRepository, FinancialOrderLimitRepository |
| `connector.financial.presentation` | `connector.presentation.execution` | ConnectorExecutionClaimController, ConnectorExecutionClaimDtos, ConnectorGuardSupportController |
| `connector.financial.presentation` | `connector.presentation.financial` | FinancialLimitController, FinancialLimitDtos |

### 승격 (package-private → public)

| 클래스 | 새 패키지 | 쓰는 곳 |
| --- | --- | --- |
| `ConnectorActionSignals` | `connector.action.application` | ConnectorActionApproval, ConnectorActionLifecycle, ConnectorActionService, ConnectorFinancialApproval, ConnectorFinancialProducer, ConnectorPolicyService, ContentDraftSaveApproval, FinancialLimitChanges |
| `ConnectorActionDetails` | `connector.action.application` | ConnectorActionApproval, ConnectorActionLifecycle, ConnectorActionService, ConnectorFinancialApproval, ContentDraftJobLocks, ContentDraftSaveApproval, FinancialLimitChanges |
| `ContentDraftLockedJob` | `connector.contentdraft.application.dto` | ContentDraftJobLocks |

### 문자열 전체 이름

없다.

### 규칙 파일의 import

`architecture` 테스트가 import 하는 클래스 가운데 이 PR 이 옮기는 것: `ContentDraftJobs`.

### 기준 파일에 더하는 줄

`SUB_FEATURES_FOLLOW_ORDER` 에 더한다. 이 줄들은 이동이 끝난 뒤에도 남는다.

| 기능 | 아래 하위 기능 | 쓰는 클래스 | 위 하위 기능 | 쓰이는 클래스 |
| --- | --- | --- | --- | --- |
| `connector` | `connection` | `ConnectorBindingService` | `approval` | `ConnectorActionService` |
| `connector` | `action` | `ConnectorActionDetails` | `approval` | `FinancialLimitChanges` |
| `connector` | `connection` | `ConnectorInstallAttempts` | `financial` | `ConnectorExecutionGuard` |
| `connector` | `connection` | `ConnectorConnectionService` | `approval` | `ConnectorActionService` |

`LAYER_DIRECTION` 에 더한다. 짝이 되는 클래스가 아직 옛 위치에 있어서 생기는 줄이다.

| 쓰는 클래스 | 쓰이는 클래스 | 사라지는 때 |
| --- | --- | --- |
| `ConnectorExecutionGuardContract` | `ConnectorExecutionSnapshot` | 남는다 |
| `PreparedContentDraftApproval` | `ContentDraftBindingStamp` | PR 9-3 |

`APPLICATION_ROOT_HOLDS_ONLY_BEANS` 에 더한다. package-private 이던 비빈 도우미가 public 이 되거나 `application` 직하로 처음 오는 클래스다. 이 줄들은 이동이 끝난 뒤에도 남는다.

`ConnectorActionDetails`, `ConnectorActionSignals`


### 이 PR 의 메모

- `ConnectorActionDetails`, `ConnectorActionSignals` 는 `action` 에 두고 `approval`, `contentdraft` 가 쓴다. package-private 이던 것이라 public 이 된다.
- `ConnectorActionDetails` 가 `FinancialLimitChanges`(`approval`)의 상수를 쓰는 한 줄은 아래가 위를 쓰는 위반이라 기준 파일에 더한다. 상수를 `connector.action` 으로 옮기면 사라지지만 이 PR 이 하지 않는다.
- `ConnectorBindingService` 와 `ConnectorConnectionService` 가 `ConnectorActionService`(`approval`)를 부른다. 두 줄을 기준 파일에 더한다. 바인딩을 떼거나 연결을 끊을 때 열린 승인 줄을 처리하는 호출이다.
- `ConnectorInstallAttempts`(`connection`)가 `ConnectorExecutionGuard`(`financial`)를 부른다. 한 줄을 기준 파일에 더한다. 설치 확인이 실행 보증의 매니페스트 검사를 부르는 호출이다.
- `ConnectorExecutionSnapshot` 은 public 비빈이라 `connector.financial.application` 에 남는다. 이 값을 쓰는 `ConnectorExecutionGuardContract`(dto)가 `LAYER_DIRECTION` 기준 파일의 한 줄이 된다.
- `ContentDraftSnapshotHash` 는 `chat.contentdraft` 에 있다. `connector.contentdraft` 가 그것을 쓰는 것은 위 기능이 아래 기능을 쓰는 올바른 방향이다. `ContentDraftSnapshotHash` 는 public 비빈이라 `chat` 의 PR 에서 옮겨질 때까지 옛 위치를 가리킨다.
