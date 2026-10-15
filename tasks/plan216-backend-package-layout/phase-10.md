# Phase 10. 마무리

**Execution profile**: standard

## 목표

모든 이동이 끝난 뒤 기준 파일에 남은 줄이 예상과 같은지 확인하고, 옛 경로가 남은 곳을 정리하고, 이 계획서를 지운다. 남은 줄을 줄이는 일은 이 계획 밖의 후속 작업으로 넘긴다.

**범위 외**: 기준 파일에 남은 줄을 고치는 일.

이 phase 는 PR 하나(10-1)다.

## 컨텍스트

- 앞선 모든 phase 가 머지됐다.
- 문서 개정은 phase 01 이 적용했다. 이 phase 는 이동 중에 낡은 서술이 남았는지만 본다.

## 작업 항목

### 1. 기준 파일의 남은 줄

`backend/config/archunit/store/` 의 규칙별 줄 수가 아래와 같아야 한다. 다르면 이동 PR 중 하나가 기준을 잘못 더했거나 줄이지 않은 것이다.

| 규칙 | 남는 줄 | 내용 |
| --- | --- | --- |
| `APPLICATION_ROOT_HOLDS_ONLY_BEANS` | 30 | public 이면서 빈이 아닌 클래스. 아래 표 |
| `LAYER_DIRECTION` | 2 | `ConnectorExecutionGuardContract`(dto)가 `ConnectorExecutionSnapshot`(application)을, `CheckedSkillPackage`(dto)가 `SkillFilePaths`(infra)를 쓴다 |
| `SUB_FEATURES_FOLLOW_ORDER` | 9 | `skill` 2, `chat` 2, `connector` 4, `proactive` 1 |
| `ADMIN_USES_ONLY_FEATURE_APPLICATION` | 14 | phase 03 의 표 13줄과 `AgentAdminService` 가 port 를 쓰는 1줄 |
| 나머지 규칙 | 0 | 기준 파일에 항목이 없다 |

나머지 규칙의 기준 파일 항목이 비면 `stored.rules` 에서 그 규칙의 줄도 지운다.

### 2. 옛 경로가 남은 곳

아래 검색이 아무것도 내지 않아야 한다. 나오면 새 이름으로 고친다. 그런 다음 `ADMIN_USES_ONLY_FEATURE_APPLICATION` 의 옛 이름 허용(`application.model`, `application.toolset`)을 지운다.

```bash
# cwd: 저장소 root
git grep -nE 'application[./]model|application[./]toolset|application[./]execution|agent[./]purge[./]|agent[./]admin[./]' -- backend docs hermes web test scripts .github
git grep -nE 'com\.bifos\.assistant\.(chat|connector|proactive|usage|skill)\.(application|domain|infra|presentation)\b' -- backend/src
```

`scripts/checks/file-length-baseline.json` 의 경로가 모두 있는 파일을 가리키는지(`node scripts/checks/check-file-length.mjs`)와, `scripts/pr/pr-risk-labels.sh` 의 경로가 새 위치를 맞게 덮는지를 확인한다.

### 3. 후속 작업으로 넘기는 것

기준 파일에 남은 줄을 줄이는 방법의 제안이다. 하나씩 정하고 고친다. 이 계획이 하지 않는다.

| 남은 줄 | 제안 |
| --- | --- |
| `application` 직하의 public 비빈 30개 | 정책과 규칙 함수(`AutonomyPolicy`, `FindingJudgement`, `ProblemJudgement`, `TaskSchedule`, `WorkspacePreviewPolicy`, `FollowUpDueAt`, `FeedbackLabeler`, `ConnectorToolPolicies`, `ConnectorManifests`)는 `domain` 으로 옮긴다. 해시와 키와 변환 함수(`ContentDraftAuthorizationHash`, `ContentDraftSnapshotHash`, `MediaObservationAnalysisKey`, `MpoJpegNormalizer`, `GatewayRewriter`, `AskFormat`, `ResultHeader`, `ContentDraftWorkerStatusDecoder`, `SkillInputRules`, `ConnectorActionDetails`, `ConnectorActionSignals`)는 `domain` 으로 옮기거나 쓰는 빈 하나에 딸린 package-private 으로 낮춘다. 승격하며 public 이 된 뒤 셋은 쓰는 곳이 여러 하위 기능이라 `domain` 이나 `application.dto` 로 옮기는 쪽이 낫다. 값과 예외(`FinancialLimitExceeded`, `TurnHandle`, `TurnRunRef`, `ContextSourceRefs`, `SkillAgentNotice`, `ConnectorExecutionSnapshot`, `MediaPipelineEvents`)는 `application.dto` 나 `domain` 으로 옮긴다. 상태를 가진 것(`TurnSlot`, `BrowserScreenSession`, `ProactiveCheckRun`)은 빈으로 만든다 |
| `connector` 의 순서 위반 4줄 | 바인딩을 떼거나 연결을 끊을 때 열린 승인 줄을 처리하는 호출(`ConnectorBindingService`, `ConnectorConnectionService` → `ConnectorActionService`)은 `connector.connection` 에 port 를 두고 `approval` 이 구현한다. `ConnectorActionDetails` 가 쓰는 `FinancialLimitChanges` 의 상수는 `connector.action` 으로 옮긴다. `ConnectorInstallAttempts` 가 부르는 `ConnectorExecutionGuard` 는 port 로 가른다 |
| `chat` 의 순서 위반 2줄 | `AttachmentCleaner` 가 부르는 `ContentDraftCleaner` 는 `attachment.application.port` 에 정리 참여 인터페이스를 두고 `contentdraft` 가 구현한다(`AgentPurgeParticipant` 와 같은 모양). `MediaPipelineEvents.draftReferenced` 는 `ContentDraftAsset` 대신 첨부 번호와 자리를 받게 한다 |
| `skill` 의 순서 위반 2줄 | `SkillPackagePreview`, `SkillPackageProblem` 이 `package` 의 타입을 쓴다. 두 값을 `package.application.dto` 로 옮기고 `SkillDtos` 가 그것을 쓰게 한다 |
| `proactive` 의 순서 위반 1줄 | `ProactiveLoopCoordinator` 가 읽는 `SurfacedProblems` 의 결과를 `loop` 쪽 port 로 받게 한다 |
| `admin` 의 14줄 | `AgentAdminService` 가 쓰는 저장소와 엔티티와 port 는 `agent` 의 `application` 이 dto 로 내놓게 한다. `*AdminDtos` 가 받는 엔티티와 domain 값(`AllowedPerson`, `AgentToken`, `ModelChoice`, `CostBy*`, `ProactiveCheck`)은 각 기능의 `application.dto` 로 바꾼다 |
| `LAYER_DIRECTION` 2줄 | `ConnectorExecutionGuardContract` 를 `ConnectorExecutionSnapshot` 에 기대지 않게 하거나 두 타입의 자리를 맞춘다. `SkillFilePaths` 가 쓰는 `SkillFiles` 의 함수를 `domain` 으로 올려 `SkillFilePaths` 도 `domain` 에 둔다 |

### 4. 알림 문단과 이 계획서를 지운다

`backend/AGENTS.md` 「패키지 배치」 첫머리의 「이동 진행 중」 알림 문단을 지운다.
`tasks/plan216-backend-package-layout/` 을 이 PR 에서 지운다. 오래 남을 것은 이미 ADR 과 `backend/docs/code-architecture.md` 에 있다.
이동 중에 알게 된 것이 문서에 없으면 지우기 전에 ADR 의 「결과」 에 옮긴다.
