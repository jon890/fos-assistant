# Phase 06. skill, usage 이동

**Execution profile**: standard

## 목표

`skill` 과 `usage` 를 하위 기능으로 나눈다. 순서는 ADR 의 표를 따른다.

**범위 외**: 이동 표에 없는 클래스와, 이동으로 새로 생기는 코드 변경(승격 외).

## 컨텍스트

- 공통 절차는 `move-procedure.md` 다. 순서 1부터 10까지 모두 따른다.
- 결정은 `backend/docs/adr/ADR-20261015-package-layout.md` 다. 역할 패키지의 뜻은 그 「결정」 표가, 하위 기능의 순서와 뜻은 「하위 기능」 표가 갖는다.
- 표의 「옛 패키지」 는 이 PR 시작 시점의 위치다. 앞선 이동 PR 이 같은 클래스의 위치를 바꾸지 않았다는 전제다.
- 표의 † 는 `application` 직하에 남는 public 비빈 클래스다. 기준 파일(`APPLICATION_ROOT_HOLDS_ONLY_BEANS`)에 얼린 채로 둔다.
- 추정 줄 수는 앞선 이동이 끝난 상태를 가정한다.

## 이동 PR 6-1: skill 의 세 하위 기능

- 옮기는 클래스 49개. 추정 변경은 운영 코드 약 290줄이다(상한 1500).

### 이동 표

| 새 패키지 | 옛 패키지 | 클래스 |
| --- | --- | --- |
| `skill.library.application` | `skill.application` | NewSkillRules, PreviousSkillVersions, ProfileSkillFilesAdapter, SkillCommandCatalog, SkillInputRules, SkillService |
| `skill.library.application.dto` | `skill.application` | SkillDetail, SkillFileInfo, SkillFileInput, SkillFrontmatter, SkillList, SkillListItem, SkillPackagePreview, SkillPackageProblem, SkillSource, SkillsChanged |
| `skill.library.config` | `skill.infra` | SkillProperties |
| `skill.library.domain` | `skill.domain` | SkillBundle, SkillFile |
| `skill.library.infra` | `skill.infra` | PreviousSkill, PreviousSkillStore, SkillFilePaths, SkillFiles, SkillPublisher, SkillStore |
| `skill.library.presentation` | `skill.presentation` | SkillController, SkillDtos |
| `skill.package.application` | `skill.application` | SkillAgentNotice†, SkillPackageCheck, SkillPackageDiff, SkillPackageService, SkillPackageZip |
| `skill.package.application.dto` | `skill.application` | CheckedSkillPackage, ReceivedSkillPackage, SkillPackageEntry, SkillPackageFile |
| `skill.package.application.dto` | `skill.application.model` | SkillPackageChange, SkillPackageReason |
| `skill.package.presentation` | `skill.presentation` | SkillPackageController |
| `skill.usage.application` | `skill.application` | SkillUsageQuery, SkillUseRecorder |
| `skill.usage.application.dto` | `skill.application` | SkillUsageSummary, UserSkillUsage |
| `skill.usage.application.port` | `skill.application` | ActiveConversationPublicIds |
| `skill.usage.domain` | `skill.domain` | ExecutionSkillUse, SkillUseCount, SkillUseOccurrence |
| `skill.usage.domain.type` | `skill.domain.type` | SkillUseSource |
| `skill.usage.infra` | `skill.infra` | ExecutionSkillUseRepository |

### 승격 (package-private → public)

| 클래스 | 새 패키지 | 쓰는 곳 |
| --- | --- | --- |
| `SkillInputRules` | `skill.library.application` | SkillPackageDiff |

### 문자열 전체 이름

| 파일 | 가리키는 클래스 |
| --- | --- |
| `ExecutionSkillUseRepository` | SkillUseCount, SkillUseOccurrence |
| `PreviousSkillVersions` | SkillService |

### 규칙 파일의 import

`architecture` 테스트가 import 하는 클래스 가운데 이 PR 이 옮기는 것: `SkillUseSource`.

### 기준 파일에 더하는 줄

`SUB_FEATURES_FOLLOW_ORDER` 에 더한다. 이 줄들은 이동이 끝난 뒤에도 남는다.

| 기능 | 아래 하위 기능 | 쓰는 클래스 | 위 하위 기능 | 쓰이는 클래스 |
| --- | --- | --- | --- | --- |
| `skill` | `library` | `SkillPackagePreview` | `package` | `SkillPackageFile` |
| `skill` | `library` | `SkillPackageProblem` | `package` | `SkillPackageReason` |

`LAYER_DIRECTION` 에 더한다. 짝이 되는 클래스가 아직 옛 위치에 있어서 생기는 줄이다.

| 쓰는 클래스 | 쓰이는 클래스 | 사라지는 때 |
| --- | --- | --- |
| `CheckedSkillPackage` | `SkillFilePaths` | 남는다 |

`APPLICATION_ROOT_HOLDS_ONLY_BEANS` 에 더한다. package-private 이던 비빈 도우미가 public 이 되거나 `application` 직하로 처음 오는 클래스다. 이 줄들은 이동이 끝난 뒤에도 남는다.

`SkillInputRules`


### 이 PR 의 메모

- `skill` 은 세 하위 기능이 서로 얽혀 한 PR 에서 옮긴다.
- `SkillFilePaths` 는 `skill.library.infra` 에 둔다. 순수 경로 규칙이지만 `infra` 의 `SkillFiles` 를 static import 하므로 `domain` 으로 보내면 `domain` 이 `infra` 를 쓰게 된다. 이 클래스를 `CheckedSkillPackage`(dto)가 쓰므로 `LAYER_DIRECTION` 의 줄이 하나 남는다. `skill.usage` 의 `ActiveConversationPublicIds` 는 `chat` 이 구현하는 port 다.
- `PreviousSkillVersions` 의 `@Slf4j(topic = ...)` 가 `SkillService` 의 전체 이름을 적는다. 로그 이름이 바뀌면 로그 검색이 끊기므로 새 전체 이름으로 고치되, 로그 설정이 이 이름에 의존하는 곳이 없는지 확인한다.

## 이동 PR 6-2: usage 의 다섯 하위 기능

- 옮기는 클래스 68개. 추정 변경은 운영 코드 약 710줄이다(상한 1500).

### 이동 표

| 새 패키지 | 옛 패키지 | 클래스 |
| --- | --- | --- |
| `usage.execution.application` | `usage.application` | AttentionExecutionQuery, ConversationResultDeliveries, ExecutionAgentQuery, ExecutionCompletionRecorder, ExecutionContextSourceWriter, ExecutionDeliveryWriter, ExecutionEventRecorder, ExecutionMemoryRefs, ExecutionRecorder, TurnSlot†, UserExecutionLimiter |
| `usage.execution.application.dto` | `usage.application` | ContextSourceRef, ExecutionContextSnapshot, ExecutionMemoryRef |
| `usage.execution.application.dto` | `usage.application.model` | ExecutionAdmission, MemoryUseVia |
| `usage.execution.application.port` | `usage.application` | ConversationPublicIds, ExecutionConnectorCalls |
| `usage.execution.config` | `usage.application` | UserExecutionProperties |
| `usage.execution.domain` | `usage.application` | InternalValuePolicy, ToolDetailPolicy |
| `usage.execution.domain` | `usage.domain` | AgentExecution, ConversationDelivery, CostByAgent, CostByDay, CostByFingerprint, CostByModel, DelegationKey, ExecutionAgentRef, ExecutionContextSource, ExecutionContextSourceId, ExecutionConversation, ExecutionEvent, ExecutionSessionRef, MonthlyCost, MonthlyCostDetail |
| `usage.execution.domain.type` | `usage.domain.type` | EventObservation, ExecutionEventType, ExecutionStatus, ReasoningEffortSource |
| `usage.execution.infra` | `usage.infra` | AgentExecutionRepository, ExecutionContextSourceRepository, ExecutionEventRepository |
| `usage.pricing.application` | `usage.application` | CostEstimator |
| `usage.pricing.config` | `usage.infra` | PricingProperties |
| `usage.pricing.domain` | `usage.domain` | CatalogPrice, EstimatedCost, ExecutionCost, ModelPrice, PriceCatalog |
| `usage.pricing.infra` | `usage.infra` | ModelsDevPriceCatalog |
| `usage.subagent.application` | `usage.application` | ConversationExecutionPurge, SubagentUsageReconciler |
| `usage.subagent.domain` | `usage.domain` | SubagentLedgerRow, SubagentUsageJob |
| `usage.subagent.infra` | `usage.infra` | SubagentUsageJobRepository |
| `usage.summary.application` | `usage.application` | UsageSummaryService |
| `usage.summary.application.dto` | `usage.application` | BreakdownLine, MonthlyUsageSummary |
| `usage.summary.presentation` | `usage.presentation` | UsageController, UsageDtos |
| `usage.tree.application` | `usage.application` | ExecutionTreeService, RootExecutionQuery |
| `usage.tree.application.dto` | `usage.application` | ExecutionCursor, ExecutionEventView, ExecutionNode, ExecutionTree, RootExecutionPage |

### 승격 (package-private → public)

| 클래스 | 새 패키지 | 쓰는 곳 |
| --- | --- | --- |
| `ExecutionCursor` | `usage.tree.application.dto` | RootExecutionQuery |

### 문자열 전체 이름

| 파일 | 가리키는 클래스 |
| --- | --- |
| `AgentExecutionRepository` | ConversationDelivery, CostByAgent, CostByDay, CostByFingerprint, CostByModel, ExecutionAgentRef, ExecutionSessionRef, ExecutionStatus, MonthlyCost, MonthlyCostDetail |

### 규칙 파일의 import

`architecture` 테스트가 import 하는 클래스 가운데 이 PR 이 옮기는 것: `ExecutionEventType`, `ExecutionStatus`, `PricingProperties`, `UserExecutionProperties`.

### 기준 파일에 더하는 줄

없다. 이 PR 이 새로 만드는 위반은 없어야 한다.


### 이 PR 의 메모

- `AgentExecutionRepository` 의 JPQL 이 `new com.bifos.assistant.usage...` 로 반환 record 를 만든다. 10곳이다. `RepositoryQueryMysqlTest` 로 확인한다.
- `InternalValuePolicy`, `ToolDetailPolicy` 는 `usage.execution.domain` 으로 간다. `ChatEvent`, `ExecutionEventView` 같은 값이 부르는 정책 함수라서다. `TurnSlot` 은 public 비빈으로 `usage.execution.application` 에 남는다.
- `AgentExecution`(엔티티)이 `TokenUsage`(hermes 값)를 쓴다. 이 값은 `hermes` 를 옮긴 PR 이 `hermes.domain` 으로 옮겨 두었다.
- `usage.pricing.config` 의 `PricingProperties` 는 `infra` 에 있던 것이다.
- `ExecutionEventView`(`tree` 의 값)가 정책 함수를 쓰는 한 줄과 `ExecutionNode` 가 `ContextSourceRef` 를 쓰는 한 줄은 같은 PR 안에서 짝이 함께 옮겨지므로 임시 줄이 생기지 않는다.
