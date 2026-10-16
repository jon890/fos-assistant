## ADR-20261016 / jpa-auditing-base: 저장 시각은 공통 base에서 기록하고 업무 시각은 명시적으로 전달한다

- **status**: `accepted`
- Date: 2026-10-16

### 맥락

현재 JPA auditing은 `MemoryCollection.createdAt` 하나에만 적용한다.
`Agent`, `AppUser`, `AllowedPerson`에는 생성 시각만 있어 설정의 마지막 변경 시각을 알 수 없다.
반면 대화의 활동, 커넥터의 유효성 비교, revision과 큐의 시각은 저장 callback 시각과 뜻이 다르다.
필드 이름만 보고 auditing을 적용하면 기존 동작이 달라진다.

### 결정

기존 `shared.config.JpaAuditingConfig`의 `Clock` 기반 `DateTimeProvider`를 재사용한다.
provider는 `Instant`를 `ChronoUnit.MICROS`로 자른다. 새 의존성과 시계, `AuditorAware`는 추가하지 않는다.
공통 base는 `shared.domain`에 두며 id, 권한, 사용자와 행위자를 갖지 않는다.

| base | 저장 속성 | 적용 엔티티 |
| --- | --- | --- |
| `CreatedAtEntity` | `@CreatedDate createdAt`, `created_at NOT NULL`, `updatable=false` | 아래 전수 표에서 `C`로 판정한 엔티티 |
| `AuditedEntity extends CreatedAtEntity` | 위 생성 시각과 `@LastModifiedDate updatedAt` | 아래 전수 표에서 `C+U`로 판정한 엔티티 |

두 base는 `@MappedSuperclass`이며 `CreatedAtEntity`에 둔 `AuditingEntityListener`를 상속한다.
getter는 기존 관례대로 `createdAt()`과 `updatedAt()`이다. 새 객체는 저장 전 감사 시각이 NULL이다.
새 행의 생성 시각과 C+U 대상의 변경 시각은 callback에서 채운다. 변경 시각은 PreUpdate 시각이며 commit 완료 시각이 아니다.
실제 속성 변경이 없는 flush, 조회, 기존 값과 같은 설정 저장에서는 변경 시각을 움직이지 않는다.
rollback한 행과 변경은 DB에 남지 않는다. 기존 행의 생성 시각은 dirty update와 detach/merge에서도 보존한다.

대상 factory와 변경 메서드에서 저장 시각만을 위한 `Instant now` 인자를 제거한다.
`Agent.deletedAt`, `AllowedPerson.lastLoginAt` 같은 업무 시각은 기존 인자 전달과 갱신 경로를 유지한다.
저장 전에 감사 시각이 필요했던 호출자는 저장 결과를 사용한다. 같은 인자로 업무 시각도 계산하면 그 인자는 남긴다.
기존 NOT NULL인 변경 시각은 `@AttributeOverride`로 NOT NULL 매핑을 유지한다.
이번에 추가하는 세 변경 시각만 NULL을 허용한다. 생성 전용 base를 쓰는 불변 기록에는 변경 시각을 추가하지 않는다.

### created_at 46개 엔티티의 전수 판정

기억 단계 1과 2, 사용자 금융 상한 이관을 반영한 운영 엔티티 46개를 생성 시각과 변경 시각으로 나눠 판정한다.
`C`는 순수 저장 생성 시각만 base로 옮기고, `C+U`는 순수 저장 생성·변경 시각을 모두 옮긴다.
`제외`는 createdAt 자체가 업무 시각이므로 어느 base도 상속하지 않는다.
판정 결과는 생성 전용 25개, 생성·변경 8개, 제외 13개이며 auditing 대상은 33개다.
기존 46개에서 ServiceToken과 FinancialLimitChange는 삭제됐고 MemoryEmbedding이 추가됐다. 기억 단계 2가 MemoryLink를 더했다.
변경 시각이 없던 엔티티에 새 updated_at을 추가하는 대상은 Agent/AppUser/AllowedPerson 세 개뿐이다.
「없음」은 UPDATE bulk가 없다는 뜻이며 DELETE bulk는 callback 대상이 아니다.

| 테이블 / 엔티티 | 판정과 근거 | 남기는 업무 시각 / bulk 경로 |
| --- | --- | --- |
| `agent` / `Agent` | C+U: 등록·설정 행의 저장 시각 | deletedAt은 명시 전달. 새 U는 과거 NULL |
| `agent_memory_collection` / `AgentMemoryCollection` | C: 권한 관계 행의 최초 저장. 변경 사건은 별도 change 표 | 복합 ID. 민감 허용 변경은 기존 managed 행을 수정. 삭제 뒤 재부여는 새 C |
| `allowed_person` / `AllowedPerson` | C+U: 허용 설정의 저장 시각 | lastLoginAt은 로그인 bulk만 갱신. 새 U는 과거 NULL |
| `app_user` / `AppUser` | C+U: 사용자 설정의 저장 시각 | 모델 기본값 bulk는 U 명시. 새 U는 과거 NULL |
| `attention_control` / `AttentionControlEntry` | C+U: 현재 숨기기·미루기 설정 행의 저장 시각. 실제 반응 사건은 AttentionEvent가 소유 | untilAt은 명시 전달. UPDATE bulk 없음 |
| `attention_event` / `AttentionEvent` | 제외: SHOWN과 ACTIONED의 발생 시각. AttentionMetricsService가 두 C의 차이로 반응 시간을 계산 | C를 Clock 인자로 유지 |
| `chat_artifact` / `ChatArtifact` | C: 결과물 행의 저장 시각. 목록·오래된 행 정리는 저장 순서를 사용 | deletedAt 및 삭제 bulk 유지 |
| `chat_attachment` / `ChatAttachment` | C: 업로드 메타데이터 행의 저장 시각 | expiresAt/deletionRequestedAt/deletedAt 및 삭제 요청 bulk 유지 |
| `chat_message` / `ChatMessage` | 제외: 메시지가 오간 시각. ChatMessageRepository가 마지막 사용자 발언과 특정 시각 뒤 메시지를 조회 | C와 메시지 생성 인자 유지. 본문 암호화 bulk도 C를 바꾸지 않음 |
| `chat_pending_message` / `ChatPendingMessage` | 제외: 실행 전에 받은 메시지의 큐 수락 시각 | held bulk 및 수락 C 유지 |
| `connector_action` / `ConnectorAction` | 제외: 승인 요청의 발생 시각. ApprovalCandidates가 출처 시각으로 사용 | expiresAt/decidedAt/executedAt/resultDeliveredAt도 명시 전달 |
| `connector_action_execution` / `ConnectorActionExecution` | C: ticket와 실행 snapshot 행의 저장 시각. 실행·소비 시각은 별도 필드 | connectionUpdatedAt/bindingUpdatedAt/ticketExpiresAt/consumedAt 유지 |
| `connector_connection` / `ConnectorConnection` | C: 연결 행의 최초 저장만 자동화 | U는 금융 ticket·원고 snapshot·설치 비교 기준으로 명시 전달 |
| `agent_connector_binding` / `ConnectorBinding` | C: 붙인 관계 행의 최초 저장만 자동화 | U와 restartRequiredSince/checkedAt/applyDueAt은 명시 전달 |
| `connector_conversation_grant` / `ConnectorConversationGrant` | C: 대화 허락 행의 저장 시각 | expiresAt/revokedAt 및 권한 철회 bulk 유지 |
| `connector_tool_grant` / `ConnectorToolGrant` | C: 도구 허락 행의 저장 시각 | expiresAt/revokedAt 유지 |
| `content_draft` / `ContentDraft` | C: 원고 본체 행의 저장 시각. 본문 시점은 revision이 소유 | currentRevision/expiresAt 조건부 bulk 유지 |
| `content_draft_creation_request` / `ContentDraftCreationRequest` | C: 재시도 식별을 위한 요청 행의 저장 시각 | 불변 본문·요청 지문 유지. UPDATE bulk 없음 |
| `content_draft_job` / `ContentDraftJob` | 제외: 임시저장 job의 큐 등록 시각 | 승인·확인·startClaimedAt/saveClaimedAt과 claim bulk 유지 |
| `content_draft_revision` / `ContentDraftRevision` | 제외: 본문 revision의 생성 시각. ContentDraftReferences가 revision 시각을 반환 | 만료·파기 상태 bulk는 revision C를 보존 |
| `content_draft_snapshot` / `ContentDraftSnapshot` | C: 확정 snapshot 행의 저장 시각 | connectionUpdatedAt/bindingUpdatedAt/expiresAt과 지문은 업무 값으로 보존 |
| `conversation` / `Conversation` | C: 대화 행의 최초 저장만 자동화 | U는 활동·목록 정렬 시각. rename/touch/delete bulk 인자 유지 |
| `execution_context_source` / `ExecutionContextSource` | C: 문맥 참조 행의 저장 시각. freshness는 별도 snapshot 속성 | 복합 ID와 Persistable의 insert 우선·중복 PK 거절 유지 |
| `execution_question` / `ExecutionQuestion` | C: 실행 안의 질문 식별 행 저장 시각 | UPDATE bulk 없음 |
| `follow_up` / `FollowUp` | 제외: 할 일 제안 시각. FollowUpAttentionSource가 C와 U로 제안·최신 상태의 신호를 구성 | U와 dueAt/acceptedAt/closedAt도 명시 전달 |
| `hermes_session_binding` / `HermesSessionBinding` | C: session과 최초 실행의 불변 대응 행 저장 시각 | session 재등록은 기존 행 재사용. UPDATE bulk 없음 |
| `media_observation` / `MediaObservation` | 제외: 관찰 revision 시각. MediaObservationService가 C에서 15분 유효기간을 계산 | C와 expiresAt 유지 |
| `memory` / `Memory` | C: 문서 본체의 최초 저장만 자동화 | U/acceptedAt은 문서 변경·수락의 업무 시각. revision.changedAt은 별도로 보존 |
| `memory_capture` / `MemoryCapture` | C: 기억하기·되돌리기 참조 행의 저장 시각. 되돌림 비교는 baseRevision이 담당 | undoneAt은 명시 전달 |
| `memory_embedding` / `MemoryEmbedding` | C+U: 벡터 행의 저장·교체 시각. 문서 revision과 지문은 별도 속성 | memoryId 지정 ID. 기존 행을 읽어 replace하고 신규 ID만 save/merge |
| `memory_link` / `MemoryLink` | C+U: 연결 행의 저장·상태 변경 시각. 대체 판정은 status 가 소유 | revokedAt은 끊거나 거절한 업무 시각으로 명시 전달. UPDATE bulk 없음 |
| `memory_collection` / `MemoryCollection` | C: collection 행의 최초 저장. 현재 auditing 대상 | 복합 ID saveAll의 merge 반환값 사용 |
| `notification` / `Notification` | C: 알림 원장 행의 저장 시각. 커서와 오래된 행 정리는 이 저장 순서를 사용 | readAt 및 읽음 bulk 유지. 원인의 발생 시각을 대체하지 않음 |
| `proactive_autonomy_decision` / `AutonomyDecision` | 제외: 평가와 판정의 동일 배치 시각. AutonomyPolicyService가 평가 C를 전달 | C 명시 전달. 재설계와 무관하게 현재 업무 계약 보존 |
| `proactive_check_finding` / `ProactiveCheckFinding` | C: 살펴보기 발견 기록의 저장 시각. 관측 시점은 checkedAt이 담당 | checkedAt 명시 전달. 기존 C만 이동하고 새 칸 없음 |
| `proactive_check_problem` / `ProactiveCheckProblem` | C: 문제 후보 기록의 저장 시각. 근거 시점은 evidenceCheckedAt이 담당 | evidenceCheckedAt 명시 전달. 기존 C만 이동하고 새 칸 없음 |
| `proactive_loop_run` / `ProactiveLoopRun` | 제외: 루프 시작 시각을 C에 기록하며 finishedAt과 실행 수명을 이룸 | 수명·재시도 시각 유지 |
| `proactive_value_evaluation` / `ValueEvaluation` | 제외: 같은 평가 배치의 동일 시각. ValueEvaluationOverviews가 C의 동등성으로 최신 묶음을 선택 | 한 배치의 같은 Clock 인자 유지 |
| `result_delivery` / `ResultDelivery` | C: 결과 전달 묶음 행의 저장 시각 | U는 전달 상태의 업무 시각. 상태·attempt bulk 인자 유지 |
| `subagent_usage_job` / `SubagentUsageJob` | 제외: 사용량 대조 큐의 수락 시각. C+120초로 실패 조건을 계산 | nextAttemptAt/expiresAt/recordedAt 유지 |
| `task` / `Task` | C+U: 예약 작업 설정 행의 저장 시각. 실행 수명은 TaskRun이 담당 | archivedAt은 명시 전달. 대화 연결 bulk는 U 명시 |
| `task_run` / `TaskRun` | 제외: 실행 큐 수락 시각. TaskRunStarter가 C+startTimeout으로 시작 제한을 판정 | scheduledFor/startedAt/finishedAt 유지 |
| `task_trigger` / `TaskTrigger` | C+U: 스케줄 설정 행의 저장 시각. 실제 예정·발화 시각은 별도 필드 | fireAt/nextFireAt/lastFiredAt은 명시 전달 |
| `user_browser` / `UserBrowser` | C: 브라우저 설정 행의 최초 저장만 자동화 | U는 UserBrowserSweeper의 경쟁 변경·정리 판단 기준. lastActiveAt/startedAt도 유지 |
| `user_data_key` / `UserDataKey` | C: 감싼 key 행의 최초 저장 | rewrappedAt은 명시 전달 |
| `agent_token` / `AgentToken` | C: 에이전트 토큰 행의 최초 저장 | lastUsedAt/revokedAt은 명시 전달 |

「등록·설정」이라는 주제만으로 업무 시각으로 분류하지 않는다.
일반 변경 이력인 U는 auditing으로 옮기고, 발생 시점의 차이·동등성·유효성·시작 제한을 판단하는 시각만 명시 전달로 남긴다.
생성 C만 옮기는 엔티티의 업무 U는 base에 숨기지 않고 해당 엔티티의 기존 필드에 남긴다.

### 복합 키와 지정 ID

`MemoryCollection`, `AgentMemoryCollection`, `ExecutionContextSource`는 복합 ID,
`MemoryEmbedding`은 memoryId를 직접 지정한다. base는 ID와 Persistable 정책을 소유하지 않는다.
새 복합 ID를 save/merge할 때는 반환된 managed 객체를 사용하고, 기존 DB 객체의 detach/merge에서는 C를 보존한다.
`AgentMemoryCollectionService.replace`는 현재 행을 먼저 읽어 민감 허용만 고치므로 C를 유지한다.
삭제한 권한을 다시 붙이면 새 행의 C가 생긴다. `ExecutionContextSource`의 isNew와 PostLoad/PostPersist 정책을 유지해 같은 position의 재삽입을 PK 오류로 거절한다.
기존 ID에 C가 NULL인 새 factory 객체를 merge해 덮어쓰는 경로는 허용하지 않는다.
멱등 재시도는 기존 행을 읽어 반환하며, 불변 기록에 다른 값의 같은 ID를 넣는 요청은 거절한다.
`updatable=false`만으로 merge 결과의 메모리 값까지 안전하다고 보지 않는다.

### 적용하지 않는 업무 시각

위 전수 표의 순수 저장 시각을 모두 auditing 대상으로 삼는다.
아래 목록은 이름이 비슷해도 callback으로 바꿀 수 없는 대표 계약이다.
구조 규칙은 허용한 두 base 밖의 필드와 getter에 auditing annotation을 붙이는 경우를 모두 거절한다.

| 대상 | 보존할 뜻 |
| --- | --- |
| `Conversation.updatedAt` | 대화 활동과 목록 정렬, 조건부 rename/touch/delete의 시각 |
| `ConnectorConnection.updatedAt`, `ConnectorBinding.updatedAt` | 금융 ticket, 원고 snapshot, 설치 시도와 현재 연결의 유효성 비교 |
| `ConnectorActionExecution`, `ContentDraftSnapshot`의 연결·바인딩 시각 | 발급 당시의 비교 snapshot |
| `Memory.updatedAt/acceptedAt`, `MemoryRevision.changedAt` | 업무 변경과 revision 기록의 시각, 수락과 문맥의 최신성 |
| `AgentMemoryCollectionChange.changedAt` | 접근 설정 변경 사건과 그 행위자 |
| `ExecutionEvent.occurredAt`, `FeedbackEvent.occurredAt`, `ExecutionSkillUse.occurredAt` | 사건이 실제 발생한 시각 |
| `ContentDraftRevision.createdAt`, `MediaObservation.createdAt` | revision 생성·분석 조건과 유효기간의 기준 |
| `TaskRun.createdAt/startedAt/finishedAt`, `ContentDraftJob.createdAt`과 claim 시각, `SubagentUsageJob.createdAt`과 재시도·기록 시각 | 큐 수락, 시작 제한, 독점 claim과 실행 수명 |
| `AgentExecution`, `ProactiveCheck`, `ResultDeliveryAttempt`의 시작·완료 시각 | 실행과 시도 수명 |
| `ResultDelivery.updatedAt`, `FollowUp.updatedAt` | 결과 전달 상태와 알림 신호의 시점 |
| `UserBrowser.updatedAt/lastActiveAt/startedAt` | 목록을 읽은 뒤 변경됐는지와 정리 조건 |
| `Notification.readAt`, 토큰의 사용·폐기·만료 시각, 삭제·수락·승인 시각 | 각각의 업무 사건과 수명 |
| 전수 표에서 제외한 createdAt 전체 | 메시지·승인·제안·주의 사건, 평가 배치, revision, 큐·실행 수명 |

시각이 없는 하위 행이나 재설계 예정 proactive 표에 새 감사 칸을 일괄 추가하지 않는다.
전수 표 밖에서 created_at이 없는 19개 엔티티는 base를 상속하지 않는다.
금융 상한은 사용자 본체로 옮기는 #551의 결과를 사용하며 별도 금융 상한 표에 칸을 더하지 않는다.

### 기존 행과 bulk 갱신

새 Flyway DDL은 `agent.updated_at`, `app_user.updated_at`, `allowed_person.updated_at`을 `DATETIME(6) NULL`로 추가한다.
기존 행은 과거 변경 시점을 모르므로 NULL을 유지한다. 생성 시각이나 배포 시각으로 채우지 않고 NOT NULL도 적용하지 않는다.
새 코드로 첫 변경을 저장하면 그때부터 변경 시각이 생긴다. DB 기본값과 `ON UPDATE`는 사용하지 않는다.

native SQL과 JPQL bulk는 listener를 거치지 않는다.
현재 감사 대상의 예외와 기존 업무 시각 갱신 경로는 다음과 같다.

| 경로 | 시각 책임 |
| --- | --- |
| `AppUserRepository.updateModelDefaultTier` | `ModelTierService`가 기존 `Clock`의 MICROS 시각을 인자로 넘기고 JPQL이 `updatedAt`도 갱신한다. 같은 tier 또는 둘 다 NULL이면 UPDATE하지 않는다. 갱신 수가 0이면 사용자 존재 여부를 확인해 no-op과 존재하지 않는 사용자를 구분한다 |
| `FinancialOrderLimitRepository.store` | limit_updated_at은 기존 업무 시각으로 남기고 감사 updated_at은 별도 MICROS 인자로 함께 갱신한다 |
| `AllowedPersonRepository.updateLastLoginAtIfNewer` | 로그인 시각만 조건부 갱신한다. 관리 설정 변경 시각인 `updatedAt`은 움직이지 않는다 |
| `ConversationRepository`의 bulk 갱신 | 기존 인자와 조건을 보존한다 |
| `ResultDeliveryRepository`의 상태·attempt 갱신 | 기존 시각 인자를 보존한다 |
| `TaskRepository`의 대화 연결 갱신 | 기존 `updatedAt` 인자를 보존한다 |
| `ContentDraftJobRepository`의 claim 갱신 | 기존 claim 시각과 조건을 보존한다 |
| `ContentDraftRepository`, `ContentDraftRevisionRepository` | 현재 revision·만료·파기 상태를 조건부 갱신한다. 생성 C는 보존한다 |
| `ChatAttachmentRepository`, `ChatPendingMessageRepository` | 삭제 요청·삭제 시각과 held 상태만 갱신한다. 큐 수락 C는 보존한다 |
| `ConnectorConversationGrantRepository`, `AgentTokenRepository` | 허락·토큰의 사용·폐기 시각을 명시적으로 갱신한다. C만 가진 불변 생성 기록에 U를 더하지 않는다 |
| `NotificationRepository`의 읽음 갱신 | readAt만 갱신하고 생성 C는 보존한다 |

감사 대상의 새 bulk 경로는 같은 트랜잭션에서 업무 값과 감사 시각을 함께 갱신해야 한다.
bulk 뒤에는 기존 영속성 문맥의 오래된 값을 다시 저장해 덮어쓰지 않도록 호출 경계를 확인한다.

### 검사와 문서

`ArchitectureRules.AUDITING_LISTENERS_AND_CLOCK`은 대상 수 1 제한을 전수 표의 엔티티별 상속 목록과 두 base로 바꾼다.
설정과 provider는 각각 하나만 허용한다. 누락·중복·다른 상속·업무 필드나 getter의 annotation도 실패시킨다.
`Auditable`, `AbstractAuditable`, `@CreatedBy`, `@LastModifiedBy`는 계속 거절한다.
기존 MemoryCollection의 callback·merge·rollback 검사를 유지하고 모든 적용 엔티티의 생성 및 C+U 대상의 변경·no-op과 bulk 검사를 더한다.
전수 표의 46개 createdAt 선택 수를 검사해 누락·추가를 발견하면 표와 판정을 함께 고치게 한다.
생성 전용 엔티티가 AuditedEntity를 상속하거나 불필요한 updated_at을 얻으면 구조·스키마 검사에서 실패한다.
업무 시각 제외 목록은 필드와 getter의 annotation, 상속으로 유입되는 auditing을 모두 거절한다.
H2와 실제 MySQL에서 ORM 및 JDBC 재조회 값이 MICROS로 같아야 한다.
마이그레이션 검사는 새 DB의 전체 적용과 기존 행이 있는 DB의 업그레이드에서 NULL 보존을 확인한다.

### 검토한 대안과 결과

- 모든 시각을 자동화하면 업무 발생 시점과 저장 시점을 혼동하므로 기각한다.
- 모든 엔티티에 생성·변경 base를 강제하면 불변 사건과 하위 행에 불필요한 칸을 만들므로 기각한다.
- 서비스가 감사 시각까지 계속 전달하면 일반 dirty update의 누락 위험이 남으므로 순수 저장 시각은 전부 callback으로 옮긴다.
- 일반 저장의 누락은 줄지만 bulk 작성자는 시각을 계속 책임져야 한다. NULL인 과거 변경 시각과 저장 전 객체의 NULL을 호출자가 구분해야 한다.
