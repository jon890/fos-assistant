# Phase 09. chat 이동

**Execution profile**: standard

## 목표

`chat` 을 열한 하위 기능으로 나눈다. `conversation` 이 가장 아래이고 `purge` 가 가장 위다. 마지막 PR 에서 `chat` 의 루트 역할 패키지가 모두 비어야 한다.

**범위 외**: 이동 표에 없는 클래스와, 이동으로 새로 생기는 코드 변경(승격 외).

## 컨텍스트

- 공통 절차는 `move-procedure.md` 다. 순서 1부터 10까지 모두 따른다.
- 결정은 `backend/docs/adr/ADR-20261015-package-layout.md` 다. 역할 패키지의 뜻은 그 「결정」 표가, 하위 기능의 순서와 뜻은 「하위 기능」 표가 갖는다.
- 표의 「옛 패키지」 는 이 PR 시작 시점의 위치다. 앞선 이동 PR 이 같은 클래스의 위치를 바꾸지 않았다는 전제다.
- 표의 † 는 `application` 직하에 남는 public 비빈 클래스다. 기준 파일(`APPLICATION_ROOT_HOLDS_ONLY_BEANS`)에 얼린 채로 둔다.
- 추정 줄 수는 앞선 이동이 끝난 상태를 가정한다.

## 이동 PR 9-1: chat.conversation, modelchoice, delivery, starter

- 옮기는 클래스 78개. 추정 변경은 운영 코드 약 730줄이다(상한 1500).

### 이동 표

| 새 패키지 | 옛 패키지 | 클래스 |
| --- | --- | --- |
| `chat.conversation.application` | `chat.application` | CheckConversations, ConversationAccess, ConversationPublicIdLookup, ConversationSessions, ConversationWriter, FeedbackConversationLookup, FirstResponseLatencyService, MemoryUseService, SourceReadSummaries, UserConversationActivity |
| `chat.conversation.application.dto` | `chat.application` | ConversationCursor, ConversationPage, MemoryUse, SourceReadSummary |
| `chat.conversation.application.dto` | `chat.application.model` | LatencyRow, LatencyStat, LatencySummary, OpenedCheck, TaskLabel |
| `chat.conversation.application.port` | `chat.application` | ConversationTaskLabels, ScheduledTurnExecutions |
| `chat.conversation.domain` | `chat.domain` | ChatMessage, ChatPendingMessage, Conversation, ExecutionQuestion, MessageContentOpener, RunSession, TurnTiming, UserLastMessage |
| `chat.conversation.domain.type` | `chat.domain.type` | ConversationPurpose, MessageRole, ModelSelectionMode |
| `chat.conversation.infra` | `chat.infra` | ChatMessageContents, ChatMessageLoadListener, ChatMessageRepository, ChatMessageWrites, ChatMessageWritesImpl, ChatPendingMessageRepository, ConversationRepository, ExecutionQuestionRepository |
| `chat.delivery.application` | `chat.application` | ResultDeliveryRecorder, ResultDeliveryRecovery |
| `chat.delivery.application.dto` | `chat.application.model` | AutoTurnDelivery, AutoTurnResult, DeliveryInput, DeliveryItemRef, DeliveryState, FailedDelivery, ResultDeliveryStart |
| `chat.delivery.application.port` | `chat.application` | AutoTurnResultSource |
| `chat.delivery.domain` | `chat.domain` | ResultDelivery, ResultDeliveryAttempt, ResultDeliveryItem |
| `chat.delivery.domain.type` | `chat.domain.type` | DeliveryAttemptStatus, DeliveryStatus |
| `chat.delivery.infra` | `chat.infra` | ResultDeliveryAttemptRepository, ResultDeliveryItemRepository, ResultDeliveryRepository |
| `chat.modelchoice.application` | `chat.application` | AgentModelDefaultService, ModelOptionsService, ModelTierService, ModelVisibilityService |
| `chat.modelchoice.application.dto` | `chat.application` | AgentModelSettings, GroupModelTiers, HiddenModels, ModelOptions, ModelTierOptions, ResolvedModelTier |
| `chat.modelchoice.domain` | `chat.domain` | ModelHidden, ModelTierDefinition, ModelTierGroupSetting |
| `chat.modelchoice.infra` | `chat.infra` | ModelHiddenRepository, ModelTierDefinitionRepository, ModelTierGroupSettingRepository |
| `chat.starter.application` | `chat.application` | StarterSuggestionService |
| `chat.starter.application.dto` | `chat.application` | StarterStatus, StarterSuggestions |
| `chat.starter.config` | `chat.application` | StarterProperties |

### 승격 (package-private → public)

| 클래스 | 새 패키지 | 쓰는 곳 |
| --- | --- | --- |
| `ConversationCursor` | `chat.conversation.application.dto` | ChatConversationQueries |

### 문자열 전체 이름

| 파일 | 가리키는 클래스 |
| --- | --- |
| `ChatMessageRepository` | MessageRole, TurnTiming, UserLastMessage |
| `ResultDeliveryAttemptRepository` | DeliveryAttemptStatus |
| `ResultDeliveryRepository` | DeliveryStatus |

### 규칙 파일의 import

`architecture` 테스트가 import 하는 클래스 가운데 이 PR 이 옮기는 것: `ConversationPurpose`, `MessageRole`, `StarterProperties`.

### 기준 파일에 더하는 줄

없다. 이 PR 이 새로 만드는 위반은 없어야 한다.


### 이 PR 의 메모

- `ChatMessageRepository` 의 JPQL 이 `MessageRole`, `TurnTiming`, `UserLastMessage` 를 전체 이름으로 가리킨다. 3곳이다. `ResultDeliveryRepository`, `ResultDeliveryAttemptRepository` 의 JPQL 이 `DeliveryStatus`, `DeliveryAttemptStatus` 를 가리키는 2곳도 이 PR 몫이다.
- `ConversationCursor` 는 package-private 이던 것이라 `chat.turn` 의 `ChatConversationQueries` 가 쓰므로 public 이 된다.
- `ChatPendingMessage` 엔티티와 저장소는 `conversation` 에 둔다. 응답 중 보낸 메시지의 큐는 대화의 상태라서다. 그것을 다루는 서비스는 `nextturn` 에 있다.
- `MessageContentOpener` 는 `ChatMessage` 가 부르는 `domain` 의 port 라 옮기지 않는다.
- `AutoTurnResultSource` 는 `nextturn` 의 `DelegationWakeService` 와 `turn` 의 `ChatDeliveryTurns` 가 구현하는 port 다. `delivery.application.port` 에 둔다.
- `AgentStarterController` 는 `ChatDtos` 를 쓰므로 `chat.turn.presentation` 으로 간다. 표의 `chat.starter` 에는 컨트롤러가 없고, 이 컨트롤러는 마지막 chat 이동(PR 9-4)에서 옮긴다.
- `chat` 의 첫 이동이므로 `SPLIT_FEATURES_HAVE_NO_ROOT_ROLES` 의 `chat` 9줄이 이때부터 줄기 시작한다.

## 이동 PR 9-2: chat.attachment 와 chat.artifact

- 옮기는 클래스 61개. 추정 변경은 운영 코드 약 440줄이다(상한 1500).

### 이동 표

| 새 패키지 | 옛 패키지 | 클래스 |
| --- | --- | --- |
| `chat.artifact.application` | `chat.application` | ArtifactCleaner, ArtifactService, ArtifactWriteService, ChatArtifactWriter |
| `chat.artifact.application.dto` | `chat.application` | ArtifactFile, ArtifactWriteRequest, ArtifactWriteResult |
| `chat.artifact.config` | `chat.infra` | ArtifactProperties, ArtifactSourceProperties |
| `chat.artifact.domain` | `chat.domain` | ChatArtifact |
| `chat.artifact.infra` | `chat.infra` | ArtifactAtomicMover, ArtifactCleanupProbe, ArtifactFileWriter, ArtifactFoundFile, ArtifactPathPolicy, ArtifactRemoved, ArtifactSourceCancellation, ArtifactSourceDnsResolver, ArtifactSourceFetcher, ArtifactSourceResponse, ArtifactSourceSocketTransport, ArtifactSourceTransport, ArtifactStore, ChatArtifactRepository |
| `chat.artifact.presentation` | `chat.presentation` | ArtifactController |
| `chat.attachment.application` | `chat.application` | AgentImageResizer, AttachmentCleaner, AttachmentImages, AttachmentInspection, AttachmentService, ChatContentMutationCoordinator, MediaObservationAnalysisKey†, MediaObservationCleaner, MediaObservationInputReader, MediaObservationPages, MediaObservationService, MediaPipelineEvents†, MpoJpegNormalizer† |
| `chat.attachment.application.dto` | `chat.application` | AgentInput, AgentPhoto, AttachmentContent, InspectedImage |
| `chat.attachment.application.dto` | `chat.application.model` | ChatContentMutationTarget, MediaObservationInput, MediaObservationPage, MediaObservationView, ObservationProvenance |
| `chat.attachment.config` | `chat.infra` | AttachmentProperties |
| `chat.attachment.domain` | `chat.domain` | ChatAttachment, MediaObservation, MediaObservationRequest |
| `chat.attachment.domain.type` | `chat.domain.type` | ObservationProvenanceKind, ObservationStatus |
| `chat.attachment.infra` | `chat.infra` | AttachmentFingerprint, AttachmentStore, ChatAttachmentRepository, MediaObservationBodies, MediaObservationRepository, MediaObservationRequestRepository |
| `chat.attachment.presentation` | `chat.presentation` | MediaObservationController, MediaObservationDtos |

### 승격 (package-private → public)

없다. 컴파일 오류로 더 나오면 `move-procedure.md` 의 순서 3 을 따른다.

### 문자열 전체 이름

| 파일 | 가리키는 클래스 |
| --- | --- |
| `ArtifactFileWriter` | ArtifactStore |
| `ArtifactPathPolicy` | ArtifactStore |

### 규칙 파일의 import

`architecture` 테스트가 import 하는 클래스 가운데 이 PR 이 옮기는 것: 없다.

### 기준 파일에 더하는 줄

없다. 이 PR 이 새로 만드는 위반은 없어야 한다.


### 이 PR 의 메모

- `ArtifactFileWriter`, `ArtifactPathPolicy` 가 `ArtifactStore` 의 전체 이름을 문자열로 가리킨다. 2곳이다.
- `AttachmentController` 는 `ChatDtos` 를 쓰므로 `chat.turn.presentation` 으로 간다(마지막 chat 이동인 PR 9-4 에서 옮긴다). 표에서 `chat.attachment.presentation` 은 `MediaObservationController` 와 `MediaObservationDtos` 뿐이다.
- `MediaPipelineEvents`, `AgentImageResizer`, `MpoJpegNormalizer` 는 `attachment` 에 둔다. `MediaPipelineEvents` 는 원고 참조 로그(`draftReferenced`)가 `ContentDraftAsset` 을 받아 `contentdraft` 를 쓴다. 이 줄은 `contentdraft` 가 옮겨지는 PR 9-3 에서 기준 파일에 더한다.

## 이동 PR 9-3: chat.contentdraft

- 옮기는 클래스 46개. 추정 변경은 운영 코드 약 380줄이다(상한 1500).

### 이동 표

| 새 패키지 | 옛 패키지 | 클래스 |
| --- | --- | --- |
| `chat.contentdraft.application` | `chat.application` | ContentDraftAuthorizationHash†, ContentDraftCleaner, ContentDraftInputReader, ContentDraftReferences, ContentDraftService, ContentDraftSnapshotHash†, ContentDraftSnapshotInputs, ContentDraftSnapshots |
| `chat.contentdraft.application.dto` | `chat.application.model` | CanonicalDraft, ContentDraftBindingStamp, ContentDraftCreationContext, ContentDraftInput, ContentDraftJobPage, ContentDraftJobView, ContentDraftSaveRequestView, ContentDraftSnapshotInput, ContentDraftSnapshotView, ContentDraftSummary, ContentDraftView |
| `chat.contentdraft.application.port` | `chat.application` | ContentDraftBindingAccess, ContentDraftJobQueries, ContentDraftSaveRequests |
| `chat.contentdraft.domain` | `chat.domain` | ContentDraftBindingChoiceException, ContentDraftException |
| `chat.contentdraft.domain.entity` | `chat.domain.entity` | ContentDraft, ContentDraftAsset, ContentDraftCreationCall, ContentDraftCreationRequest, ContentDraftJob, ContentDraftRevision, ContentDraftSnapshot |
| `chat.contentdraft.domain.type` | `chat.domain.type` | ContentDraftCreationStatus, ContentDraftJobStatus, ContentDraftRevisionStatus |
| `chat.contentdraft.infra` | `chat.infra` | ContentDraftAssetRepository, ContentDraftBodies, ContentDraftCreationCallRepository, ContentDraftCreationRequestRepository, ContentDraftJobRepository, ContentDraftRepository, ContentDraftRevisionRepository, ContentDraftSnapshotRepository |
| `chat.contentdraft.presentation` | `chat.presentation.contentdraft` | ContentDraftController, ContentDraftDtos |
| `chat.contentdraft.presentation` | `chat.presentation.contentsnapshot` | ContentDraftSnapshotController, ContentDraftSnapshotDtos |

### 승격 (package-private → public)

없다. 컴파일 오류로 더 나오면 `move-procedure.md` 의 순서 3 을 따른다.

### 문자열 전체 이름

| 파일 | 가리키는 클래스 |
| --- | --- |
| `ContentDraftBindingAccess` | ContentDraftBindingChoiceException |

### 규칙 파일의 import

`architecture` 테스트가 import 하는 클래스 가운데 이 PR 이 옮기는 것: `ContentDraftJobPage`, `ContentDraftJobQueries`, `ContentDraftJobView`, `ContentDraftSaveRequestView`, `ContentDraftSaveRequests`.

### 기준 파일에 더하는 줄

`SUB_FEATURES_FOLLOW_ORDER` 에 더한다. 이 줄들은 이동이 끝난 뒤에도 남는다.

| 기능 | 아래 하위 기능 | 쓰는 클래스 | 위 하위 기능 | 쓰이는 클래스 |
| --- | --- | --- | --- | --- |
| `chat` | `attachment` | `MediaPipelineEvents` | `contentdraft` | `ContentDraftAsset` |
| `chat` | `attachment` | `AttachmentCleaner` | `contentdraft` | `ContentDraftCleaner` |

이 PR 이 기준 파일에서 지우는 줄(앞 PR 이 더한 것): `PreparedContentDraftApproval`→`ContentDraftBindingStamp`.

### 이 PR 의 메모

- `ContentDraftBindingAccess` 의 문서에 `ContentDraftBindingChoiceException` 의 전체 이름이 있다. 1곳이다.
- `connector.contentdraft` 는 `ContentDraftSnapshotHash` 같은 `chat.contentdraft` 의 타입을 쓴다. `connector` 가 `chat` 위라 올바른 방향이다.
- `AttachmentCleaner` 가 `ContentDraftCleaner` 를 부르는 한 줄과 `MediaPipelineEvents` 가 `ContentDraftAsset` 을 받는 한 줄을 이 PR 이 기준 파일에 더한다. 둘 다 아래 `attachment` 가 위 `contentdraft` 를 쓰는 위반이다.

## 이동 PR 9-4: chat.turn, nextturn, recovery, purge

- 옮기는 클래스 62개. 추정 변경은 운영 코드 약 320줄이다(상한 1500).

### 이동 표

| 새 패키지 | 옛 패키지 | 클래스 |
| --- | --- | --- |
| `chat.nextturn.application` | `chat.application` | DelegationWakeService, NextTurnDispatcher, PendingMessageService |
| `chat.nextturn.application.dto` | `chat.application` | DelegationFinished, WakeRetryDue |
| `chat.nextturn.application.port` | `chat.application` | DelegationOutputClip |
| `chat.nextturn.config` | `chat.application` | DelegationWakeProperties |
| `chat.nextturn.presentation` | `chat.presentation` | PendingMessageController |
| `chat.purge.application` | `chat.application` | ConversationAgentPurge, ConversationPurgeWriter, ConversationPurger |
| `chat.purge.application.port` | `chat.application` | ConversationActionPurge |
| `chat.recovery.application` | `chat.application` | RecoveredConversationLock, RecoveredRunRecorder, RestartReconciler |
| `chat.recovery.application.dto` | `chat.application.model` | RecoveredRunKind |
| `chat.recovery.application.port` | `chat.application` | RecoveredAnswerGuard |
| `chat.recovery.config` | `chat.application` | RestartReconcileProperties |
| `chat.turn.application` | `chat.application` | AskFormat†, ChatConversationManagement, ChatConversationQueries, ChatDeliveryInput, ChatDeliveryTurns, ChatRunEvents, ChatService, ChatTurnCompletion, ChatTurnLifecycle, ChatTurnRouting, ChatTurnRunner, ChatTurnStopper, ContextSourceRefs†, ConversationEventHub, ConversationNotices, OwnConversations, PendingQueueChangedException, TurnCancellation, TurnHandle†, TurnQuestions, TurnRunRef† |
| `chat.turn.application.dto` | `chat.application` | ActivitySummary, ChatEvent, ChatTurn, PendingQueue, PendingTurn, Routed, RunningTurn, SequenceCounter, SkillCommand, TurnClosed, TurnIntent, TurnMark |
| `chat.turn.application.dto` | `chat.application.model` | CheckAnswer |
| `chat.turn.application.port` | `chat.application` | CheckReportReads, CheckTurn |
| `chat.turn.presentation` | `chat.presentation` | AgentStarterController, AttachmentController, ChatController, ChatDtos, ChatEventStreams, ConversationEventController, MemoryCaptureController, MemoryUseController |

### 승격 (package-private → public)

| 클래스 | 새 패키지 | 쓰는 곳 |
| --- | --- | --- |
| `PendingTurn` | `chat.turn.application.dto` | ChatRunEvents, ChatTurnLifecycle, ChatTurnRunner, ChatTurnStopper |
| `SequenceCounter` | `chat.turn.application.dto` | ChatTurnLifecycle |
| `Routed` | `chat.turn.application.dto` | ChatDeliveryTurns, ChatService, ChatTurnLifecycle, ChatTurnRouting, ChatTurnRunner |

### 문자열 전체 이름

없다.

### 규칙 파일의 import

`architecture` 테스트가 import 하는 클래스 가운데 이 PR 이 옮기는 것: `DelegationWakeProperties`.

### 기준 파일에 더하는 줄

없다. 이 PR 이 새로 만드는 위반은 없어야 한다.


### 이 PR 의 메모

- `PendingTurn`, `Routed`, `SequenceCounter` 는 package-private 이던 값이다. 이 PR 이 `turn.application.dto` 로 옮기며 public 으로 올린다.
- `ChatEvent` 는 `usage.execution.domain` 의 `InternalValuePolicy`, `ToolDetailPolicy` 를 부른다. 위 기능이 아래 기능을 부르는 올바른 방향이다.
- `turn` 에는 `ChatService`, `ChatTurnRouting`, `ChatTurnRunner`, `ChatConversationQueries`, `ChatDeliveryTurns` 다섯 클래스가 서로 순환으로 묶여 있다. 나누지 않고 모두 `turn.application` 에 둔다.
- `AttachmentController`, `AgentStarterController`, `MemoryCaptureController`, `MemoryUseController` 는 `ChatDtos` 를 쓰므로 `chat.turn.presentation` 으로 간다.
- `chat` 의 루트 역할 패키지가 모두 비면 `SPLIT_FEATURES_HAVE_NO_ROOT_ROLES` 의 `chat` 9줄이 사라지고 `APPLICATION_PACKAGE_SIZE` 의 `chat` 줄도 사라진다.
