# Phase 04. 작은 기능 이동

**Execution profile**: standard

## 목표

하위 기능으로 나누지 않는 작은 기능 열네 개를 역할 패키지(`application`, `application.dto`, `application.port`, `config`)로 정리한다. `application.model` 은 `application.dto` 로, 인터페이스는 `application.port` 로, `@ConfigurationProperties` 는 `config` 로 간다.

**범위 외**: 이동 표에 없는 클래스와, 이동으로 새로 생기는 코드 변경(승격 외).

## 컨텍스트

- 공통 절차는 `move-procedure.md` 다. 순서 1부터 10까지 모두 따른다.
- 결정은 `backend/docs/adr/ADR-20261015-package-layout.md` 다. 역할 패키지의 뜻은 그 「결정」 표가, 하위 기능의 순서와 뜻은 「하위 기능」 표가 갖는다.
- 표의 「옛 패키지」 는 이 PR 시작 시점의 위치다. 앞선 이동 PR 이 같은 클래스의 위치를 바꾸지 않았다는 전제다.
- 표의 † 는 `application` 직하에 남는 public 비빈 클래스다. 기준 파일(`APPLICATION_ROOT_HOLDS_ONLY_BEANS`)에 얼린 채로 둔다.
- 추정 줄 수는 앞선 이동이 끝난 상태를 가정한다.

## 이동 PR 4-1: user, crypto, followup, notification, model, context, feedback, people, task, workspace

- 옮기는 클래스 48개. 추정 변경은 운영 코드 약 330줄이다(상한 1500).

### 이동 표

| 새 패키지 | 옛 패키지 | 클래스 |
| --- | --- | --- |
| `context.application` | `context` | ContextAssembler, ContextBudgetWriter, MemoryItems, MemorySearchSection, ResultHeader† |
| `context.application.dto` | `context` | AssembledContext, ContextBodyMode, ContextBundle, ContextFreshness, ContextItem, ContextSelection, ContextSource, ContextTrust, MemorySearchPlacement |
| `context.config` | `context` | ContextProperties, MemorySearchSectionProperties |
| `crypto.config` | `crypto.infra` | DataEncryptionProperties |
| `feedback.application.dto` | `feedback.application.model` | FeedbackEntry, FeedbackLabel, SubjectLabel |
| `feedback.application.port` | `feedback.application` | FeedbackConversations |
| `feedback.config` | `feedback.application` | DecisionFeedbackProperties |
| `followup.application.dto` | `followup.application.model` | FollowUpPatch, FollowUpProposalOutcome, FollowUpSnapshot, NewFollowUp |
| `notification.application.dto` | `notification.application` | NotificationCursor, NotificationEvent, NotificationPage |
| `notification.config` | `notification.application` | NotificationProperties |
| `people.application.dto` | `people.application.model` | PersonAccess |
| `task.application.dto` | `task.application.model` | ProactiveSchedule, ScheduleInput, TaskDetail, TaskInput, TaskRunDetail |
| `task.config` | `task.application` | ProactiveScheduleProperties, TaskProperties |
| `user.application.port` | `user.application` | FirstSignInListener, SignInRevocation |
| `workspace.application.dto` | `workspace.application.model` | WorkspaceAgent, WorkspaceFile, WorkspacePreview, WorkspaceSpaceKind, WorkspaceSpaceUsage, WorkspaceStatus, WorkspaceUsageReport |
| `workspace.config` | `workspace.infra` | WorkspaceProperties |

### 승격 (package-private → public)

| 클래스 | 새 패키지 | 쓰는 곳 |
| --- | --- | --- |
| `NotificationCursor` | `notification.application.dto` | NotificationService |

### 문자열 전체 이름

없다.

### 규칙 파일의 import

`architecture` 테스트가 import 하는 클래스 가운데 이 PR 이 옮기는 것: `FeedbackActor`, `FeedbackEventType`, `FeedbackSubjectType`, `ModelTier`, `WorkspaceProperties`.

### 기준 파일에 더하는 줄

`LAYER_DIRECTION` 에 더한다. 짝이 되는 클래스가 아직 옛 위치에 있어서 생기는 줄이다.

| 쓰는 클래스 | 쓰이는 클래스 | 사라지는 때 |
| --- | --- | --- |
| `ProactiveSchedule` | `CheckBlocker` | PR 7-1 |

`APPLICATION_ROOT_HOLDS_ONLY_BEANS` 에 더한다. package-private 이던 비빈 도우미가 public 이 되거나 `application` 직하로 처음 오는 클래스다. 이 줄들은 이동이 끝난 뒤에도 남는다.

`ResultHeader`


### 이 PR 의 메모

- `model` 은 `model.domain` 과 `model.domain.type` 이 이미 맞는 모양이라 옮길 것이 없다. 표에 없다.
- `context` 는 평평한 패키지라 `context.application`(빈), `context.application.dto`(값), `context.config`(`ContextProperties`, `MemorySearchSectionProperties`)로 나눈다. `ResultHeader` 는 public 비빈이다. `context` 루트에서 `context.application` 으로 오며 처음 `APPLICATION_ROOT_HOLDS_ONLY_BEANS` 줄이 생긴다.
- `user` 의 `FirstSignInListener`, `SignInRevocation` 같은 인터페이스는 `user.application.port` 로 간다. `people` 이 구현하는 port 라 구현 쪽 import 도 바뀐다.
- `crypto` 의 `TextCipher`, `KeyEncryptionKeys` 는 `domain` 의 port 라 옮기지 않는다. `UserDataKeyPlacementTest` 의 기대 전체 이름(`crypto.domain.entity.UserDataKey`)은 그대로다.
- `task` 의 `TaskSchedule`, `workspace` 의 `WorkspacePreviewPolicy` 는 public 비빈이라 `application` 에 남는다.
- `people` 은 phase 03 이 `PeopleAdminController` 를 이미 `admin` 으로 옮겼다. 표의 클래스만 옮긴다. `scripts/pr/pr-risk-labels.sh` 의 `people/application/SignInPolicy.java` 는 같은 경로에 남는다.
- `ProactiveSchedule`(`task` 의 dto)가 `CheckBlocker`(`proactive` 의 옛 `application.model`)를 쓴다. 층 규칙이 옛 `application.model` 을 빈 층으로 보므로 한 줄이 임시로 생기고, `proactive` 를 옮기는 PR 7-1 에서 지운다.

## 이동 PR 4-2: browser, attention, orchestration, mcp

- 옮기는 클래스 40개. 추정 변경은 운영 코드 약 340줄이다(상한 1500).

### 이동 표

| 새 패키지 | 옛 패키지 | 클래스 |
| --- | --- | --- |
| `attention.application.dto` | `attention.application.model` | AttentionCandidate, AttentionCard, AttentionChannel, AttentionConfidence, AttentionControl, AttentionExecutionRef, AttentionFollowUpRef, AttentionItem, AttentionMetric, AttentionProblem, AttentionReport, AttentionSignal, AttentionSnapshot, AttentionSourceRef, AttentionView, AttentionWhy, CardStatus |
| `attention.application.port` | `attention.application` | AttentionCandidates |
| `attention.config` | `attention.application` | AttentionProperties |
| `browser.application.dto` | `browser.application.model` | BrowserEndpoint, BrowserGrant, BrowserScreenInput, GatewayTarget, UserBrowserSnapshot |
| `browser.application.port` | `browser.application` | BrowserGrantOwners |
| `browser.application.port` | `browser.application.model` | BrowserScreenSink, BrowserUsageHandle |
| `browser.config` | `browser.infra` | BrowserProperties |
| `mcp.application.dto` | `mcp.application` | AttachmentInspectRequest, IssuedToken, McpCallContext, McpCaller, McpPrincipal, SubagentRegistration |
| `mcp.application.port` | `mcp.application` | FinancialLimitRequests |
| `orchestration.application.dto` | `orchestration.application` | AgentRun, DelegationResult, DelegationStop, SubagentRegistrationResult |
| `orchestration.config` | `orchestration.application` | DelegationProperties |

### 승격 (package-private → public)

없다. 컴파일 오류로 더 나오면 `move-procedure.md` 의 순서 3 을 따른다.

### 문자열 전체 이름

없다.

### 규칙 파일의 import

`architecture` 테스트가 import 하는 클래스 가운데 이 PR 이 옮기는 것: `BrowserProperties`, `DelegationProperties`.

### 기준 파일에 더하는 줄

없다. 이 PR 이 새로 만드는 위반은 없어야 한다.


### 이 PR 의 메모

- `browser` 의 `BrowserGatewaySocketConfig` 는 `@Configuration` 이지만 웹소켓 핸들러(`presentation`)를 등록하므로 `browser.presentation` 에 남긴다. 규칙 4 는 `@ConfigurationProperties` 만 `config` 로 보낸다.
- `browser` 의 `BrowserScreenSession`, `GatewayRewriter` 는 public 비빈이라 `browser.application` 에 남는다.
- `BrowserProperties` 는 `infra` 에서 `browser.config` 로 간다. `ArchitectureRules.java` 의 import 가 바뀐다.
- `browser.domain` 의 port(`BrowserRuntime` 같은 것)는 옮기지 않는다.
- `mcp` 는 `scripts/pr/pr-risk-labels.sh` 의 보안 패턴 `mcp/*` 가 그대로 맞는다. 경로가 바뀌지 않는다.
- `orchestration` 의 `ResearchAndBuildFlow` 는 정리 PR 이 지웠다. `DelegationRuns`, `Handoff` 같은 package-private 도우미는 `application` 에 남는다.
