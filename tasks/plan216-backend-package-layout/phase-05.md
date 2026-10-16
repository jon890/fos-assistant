# Phase 05. memory, agent, hermes 이동

**Execution profile**: standard

## 목표

나누지 않는 큰 기능 셋을 역할 패키지로 정리한다. `agent` 는 `toolset`, `purge` 하위 패키지를 평평하게 만들고, `hermes` 는 평평한 패키지를 역할로 나눈다.

**범위 외**: 이동 표에 없는 클래스와, 이동으로 새로 생기는 코드 변경(승격 외).

## 컨텍스트

- 공통 절차는 `move-procedure.md` 다. 순서 1부터 10까지 모두 따른다.
- 결정은 `backend/docs/adr/ADR-20261015-package-layout.md` 다. 역할 패키지의 뜻은 그 「결정」 표가, 하위 기능의 순서와 뜻은 「하위 기능」 표가 갖는다.
- 표의 「옛 패키지」 는 이 PR 시작 시점의 위치다. 앞선 이동 PR 이 같은 클래스의 위치를 바꾸지 않았다는 전제다.
- 표의 † 는 `application` 직하에 남는 public 비빈 클래스다. 기준 파일(`APPLICATION_ROOT_HOLDS_ONLY_BEANS`)에 얼린 채로 둔다.
- 추정 줄 수는 앞선 이동이 끝난 상태를 가정한다.

## 이동 PR 5-1: memory, agent

- 옮기는 클래스 54개. 추정 변경은 운영 코드 약 320줄이다(상한 1500).

### 이동 표

| 새 패키지 | 옛 패키지 | 클래스 |
| --- | --- | --- |
| `agent.application` | `agent.application.toolset` | AgentDefaultToolsets, AgentToolService, ToolsetCatalogService, ToolsetRequestService, ToolsetVisibilityService |
| `agent.application` | `agent.purge.application` | AgentPurgeWriter, AgentPurger |
| `agent.application.dto` | `agent.application` | AgentMemoryGrants, PersonaSnapshot |
| `agent.application.dto` | `agent.application.model` | AgentToolView, AgentToolsetsView, EnabledToolsetAgent, ToolsetCatalogView, ToolsetRequestView |
| `agent.application.dto` | `agent.purge.application.model` | AgentPurgeOutcome |
| `agent.application.port` | `agent.application` | AgentConnectorBindings, AgentConnectorDetacher, ProfileProvisioning, ProfileSkillFiles, ReservedProfileNames |
| `agent.application.port` | `agent.purge.application` | AgentPurgeParticipant |
| `memory.application` | `memory.application.embedding` | EmbeddingText†, MemoryEmbeddingRefresher, OpenAiCompatibleEmbeddingClient |
| `memory.application` | `memory.application.relevance` | KeywordScorer, KoreanBigramTokenizer†, MemoryRelevance, MemoryVectorCache, QueryEmbedder |
| `memory.application.dto` | `memory.application.model` | AgentMemoryCountScope, AgentMemoryGrantInput, AgentMemorySetting, AgentMemorySettingChange, AgentMemorySettingCollection, CapturedMemory, MemoryAccess, MemoryRememberOutcome, MemoryRememberRequest, MemoryRememberResult, MemorySearchCursor, MemorySearchItem, MemorySearchPage, MemorySource |
| `memory.application.dto` | `memory.application.relevance` | KeywordScore, RelevanceCandidate, RelevanceResult, RelevanceScore |
| `memory.application.port` | `memory.application` | OmittedMemories |
| `memory.application.port` | `memory.application.embedding` | EmbeddingClient, EmbeddingRejectedException, EmbeddingUnavailableException |
| `memory.config` | `memory.application` | MemoryEncryptionProperties |
| `memory.config` | `memory.application.embedding` | MemoryEmbeddingProperties |
| `memory.config` | `memory.application.relevance` | MemoryRelevanceProperties |

### 승격 (package-private → public)

없다. 컴파일 오류로 더 나오면 `move-procedure.md` 의 순서 3 을 따른다.

### 문자열 전체 이름

없다.

### 규칙 파일의 import

`architecture` 테스트가 import 하는 클래스 가운데 이 PR 이 옮기는 것: `AgentProperties`, `AgentVisibility`, `CostMode`, `CredentialScope`, `MemoryEncryptionProperties`.

### 기준 파일에 더하는 줄

`ADMIN_USES_ONLY_FEATURE_APPLICATION` 에 더한다.

| `admin` 클래스 | 쓰는 클래스 | 사라지는 때 |
| --- | --- | --- |
| `AgentAdminService` | `AgentConnectorBindings` | 남는다 |


### 이 PR 의 메모

- `memory.domain.entity` 와 `memory.domain.type` 은 옮기지 않는다. 엔티티 배치 규칙(`ENTITIES_IN_OWNED_ENTITY_PACKAGE`, `EMBEDDED_IDS_WITH_ENTITIES`)과 `MemoryPlacementTest` 는 그대로 통과해야 한다.
- `MemoryService` 의 private `requireAdmin` 은 그룹 공용 Memory 의 쓰기 권한 판정이다. 이름도 위치도 바꾸지 않는다. `MemoryRepository` 의 JPQL 은 `memory.domain` 을 가리키는데 옮기지 않으므로 고칠 것이 없다.
- 설정 값 셋(`MemoryEncryptionProperties`, `MemoryEmbeddingProperties`, `MemoryRelevanceProperties`)은 `memory.config` 로 간다.
- `memory` 의 `application.embedding` 과 `application.relevance` 하위 패키지는 없애고 평평하게 둔다. 인터페이스(`EmbeddingClient`)와 그 예외 둘은 `application.port`, 값은 `application.dto`, 나머지는 `application` 이다. `EmbeddingText` 와 `KoreanBigramTokenizer` 는 public 비빈이라 `application` 직하에 남아 기준 파일의 줄이 된다.
- 기억 단계의 후속 작업이 `memory` 를 계속 고치고 있으면 이 표는 PR 시점에 다시 뽑는다(`move-procedure.md` 의 「시작 조건」).
- `agent` 의 `application.toolset`, `purge`, `admin`(phase 03 이 이미 비웠다) 하위 패키지는 없애고 평평하게 둔다. 서비스는 모두 `agent.application` 에 둔다.
- `AGENT_PURGE_CONTRACT_PLACEMENT` 와 `AgentPurgerPlacementTest` 의 기대 전체 이름을 고친다. 대상 선택 조건(`agentPurgeRoles` 가 쓰는 `.agent.purge.` 경계와 `application.model` 리터럴)도 새 구조로 다시 쓴다: `agent.purge.application.AgentPurger` 는 `agent.application.AgentPurger`, `AgentPurgeWriter` 는 `agent.application.AgentPurgeWriter`, `AgentPurgeParticipant` 는 `agent.application.port.AgentPurgeParticipant`, `AgentPurgeOutcome` 은 `agent.application.dto.AgentPurgeOutcome` 이다. fixture 의 `agent.purge.*` 문자열도 새 이름에 맞춘다.
- `agent.config` 는 옮기지 않는다. `FEATURE_CONFIG_PLACEMENT` 와 `AgentPropertiesPlacementTest` 는 그대로다.
- `agent.application.port` 의 `AgentConnectorBindings`, `AgentConnectorDetacher`, `ProfileProvisioning`, `ProfileSkillFiles`, `ReservedProfileNames` 는 구현이 `connector`, `people`, `skill` 에 있다. 구현 쪽 import 가 함께 바뀐다.
- `ToolsetAdminController` 는 phase 03 이 `admin.toolset` 으로 옮겼고, 그것이 부르는 `ToolsetCatalogService`, `ToolsetVisibilityService` 가 이 PR 에서 `agent.application` 으로 간다. phase 03 이 더한 두 줄이 사라진다.

## 이동 PR 5-2: hermes

- 옮기는 클래스 64개. 추정 변경은 운영 코드 약 580줄이다(상한 1500).

### 이동 표

| 새 패키지 | 옛 패키지 | 클래스 |
| --- | --- | --- |
| `hermes.application.dto` | `hermes` | HermesProfileName, HermesSkillName |
| `hermes.application.dto` | `hermes.dto` | CallResult, ConnectorAppearance, ConnectorApprovedExecution, ConnectorArgumentLabels, ConnectorCallError, ConnectorErrorDetail, ConnectorField, ConnectorFieldOptions, ConnectorManifest, ConnectorRecovery, ConnectorSnapshotDeclaration, ConnectorTool, ConnectorToolDescription, ConnectorUnknownDetail, ConnectorUnknownErrors, ContentDraftWorkerStamp, HermesImage, HermesModelCatalog, HermesRunCommand, HermesRunLookup, HermesRunResult, ProfileModelDefaults, ReasoningCapability, RunEvent, SessionRuntime, SoulDocument, SubagentProviderLookup |
| `hermes.application.port` | `hermes` | ConnectorExecutionUnknown, ConnectorInstallConflict, ConnectorProfileRejected, ConnectorSandboxUnavailable, ContentDraftJobNotFound, HermesCallFailure, HermesConnectorClient, HermesDashboardClient, HermesRequestRejected, HermesRunsClient, HermesSkillClient, HermesToolsetClient |
| `hermes.config` | `hermes` | HermesProperties |
| `hermes.domain` | `hermes` | ToolDetailRedactor, ToolDetailScope |
| `hermes.domain` | `hermes.dto` | SubagentSessionUsage, TokenUsage |
| `hermes.infra` | `hermes` | CappedSnapshotBody, ConnectorAppearances, ConnectorCatalogDecoder, ConnectorCatalogTools, DecisionProfileReadinessClient, HermesModelClient, HermesProfileKeyStore, HermesRunEventStream, HttpContentDraftSnapshotClient, HttpHermesConnectorClient, HttpHermesConnectorGuard, HttpHermesDashboardClient, HttpHermesRunsClient, HttpHermesSkillClient, HttpHermesToolsetClient, ProfileModelDefaultsClient, SandboxAttachmentDirectory, SubagentProviderClient |

### 승격 (package-private → public)

| 클래스 | 새 패키지 | 쓰는 곳 |
| --- | --- | --- |
| `HermesCallFailure` | `hermes.application.port` | HermesRunEventStream, HttpHermesDashboardClient, HttpHermesRunsClient, HttpHermesSkillClient, HttpHermesToolsetClient |

### 문자열 전체 이름

없다.

### 규칙 파일의 import

`architecture` 테스트가 import 하는 클래스 가운데 이 PR 이 옮기는 것: `HermesProperties`.

### 기준 파일에 더하는 줄

없다. 이 PR 이 새로 만드는 위반은 없어야 한다.


### 이 PR 의 메모

- `hermes` 는 평평한 패키지라 인터페이스는 `hermes.application.port`, 그 예외(`HermesCallFailure`, `HermesRequestRejected` 등)도 `port`, 값(`dto` 의 record 와 `HermesProfileName`, `HermesSkillName`)은 `hermes.application.dto`, 구현(`Http*`, 스트림, 저장소)은 `hermes.infra`, `HermesProperties` 는 `hermes.config` 로 간다.
- `ToolDetailRedactor`, `ToolDetailScope`, `TokenUsage`, `SubagentSessionUsage` 는 `usage` 의 엔티티가 부르는 순수 값과 함수라 `hermes.domain` 에 둔다.
- `HermesRunEventStream` 은 `@Service` 지만 `hermes.infra` 로 간다. 인터페이스가 아니라 구현이고 Hermes 의 HTTP 를 읽는 어댑터다.
- `ArchitectureRules.java` 가 `HermesProperties` 를 import 한다. `HERMES_TIMEOUTS_ONLY_THROUGH_LIVE_PROPERTIES` 가 읽는 이름도 확인한다.
- `scripts/pr/pr-risk-labels.sh` 의 `hermes/*` 패턴이 그대로 맞는다.
