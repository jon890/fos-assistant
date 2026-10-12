# Phase 03. 기존 승인 줄과 예약 job의 권한 경계

**Execution profile**: deep

**상태**: 구현했다. 실제 main에 맞춰 바꾼 점은 아래 「실제 main 대조」와 `docs/features/content-draft.md`의 「승인 snapshot과 저장 job」이 갖는다.

## 실제 main 대조

- `hermes/connectors/naver-blog/connector.json`의 `save_draft`는 바꾸지 않았다. `grant:false`를 넣으면 기존 DSL 저장의 상시 허락이 거둬지고 긴 본문 인자가 가림 거절된다. 원고 승인 줄은 `save_draft` 위험도로 판정하되 승인 방식 `always`로 저장해 상시 허락을 닫는다.
- 허가는 `ContentDraftJobs.authorize` 대신 `ContentDraftJobAuthorizer.authorize`가 맡고, 잠금 순서는 `ContentDraftJobLocks`가 승인과 함께 쓴다.
- `approvePrepared`는 `ConnectorActionView` 대신 끝난 `ConnectorAction`을 돌려주고, 판단 피드백·사건·화면 값은 기존 `ConnectorActionApproval`이 만든다.
- 시작 전송은 `ContentDraftJobStarter` port다. 구현 빈이 없거나 catalog가 start·status를 선언하지 않으면 승인은 실행 없이 거절한다.
- job 목록은 snapshot 번호 대신 job 번호 역순이다. main의 job 표에 owner·대화 칸과 `(owner_user_id,conversation_id,id)` 색인이 있다.
- 오래된 EXECUTING 정리(5분)가 승인 줄을 UNKNOWN으로 바꿔도 job과 claim은 그대로 쓸 수 있다. claim은 job의 DISPATCHED·SAVING·UNKNOWN에서만 받는다.
- 검사는 chat 검사 패키지의 `ContentDraftJobsTest`와 MySQL 하위 클래스 `ContentDraftClaimMysqlTest`, `connector.ContentDraftSaveTicketTest`, `architecture.ContentDraftSnapshotArchitectureTest`에 있다.

## 목표

승인한 snapshot에 실제 worker용 jobId를 한 번 예약하고 현재 권한을 worker가 재확인하는 계약을 만든다.

**범위 외**: 실제 worker 시작 활성화, 금융 승인 변경, 최초 원고 생성 재전송, 저장 재시도, 새 workflow queue.

## 컨텍스트

**근거 문서**: `docs/features/content-draft.md`의 「승인·권한 철회와 경합의 경계」와 「job 상태와 재접속」, `backend/docs/data-schema.md`의 snapshot·job 표.
ConnectorActionApproval.approve는 PENDING을 EXECUTING으로 커밋한 뒤 connector.execute를 호출한다.
ConnectorActionLifecycle.markInterrupted·markStale은 UNKNOWN으로 끝내며 rejectPendingFor는 EXECUTING일 때 binding 변경을 거절한다.
원고 경로의 외부 실행은 connector.application.ContentDraftSaveApproval에 위임하고 금융 ConnectorExecutionTicket.issue를 호출하지 않는다.

## Blocked 조건

- PR443 actual main과 snapshot 표·compiler가 준비되지 않으면 `PHASE_BLOCKED: snapshot prerequisites`로 종료한다.
- worker start capability는 이번 단계에서 열지 않는다. approve 통합은 미지원이면 실행 없이 거절하는 상태로 커밋한다.

## 의도 메모

- 확정한 최초 MCP 생성의 중복 방지 구현은 이 save 키와 별개다. 신규 save 중복 키는 `v1-content-save`와 owner·snapshotId로 분리한다.
- 사용자 행을 먼저 잠그므로 기존 binding 해제와 잠금 역순을 만들지 않는다.
- 운영 변경 목표 1100줄 이하인 별도 승인 PR이다. 경계가 넘으면 ticket 인증과 승인 통합을 별도 PR로 나누고 각 PR에 시험을 둔다.

## 작업 항목

### 1. 승인 요청과 원고 분기

ContentDraftJobs·ContentDraftSaveApproval·ContentDraftSaveTicket과 authorize controller는 connector가 소유한다.
connector는 하위 chat의 snapshot·원고 서비스를 부르며 chat은 connector 또는 mcp를 import하지 않는다.
MCP handler가 검증한 CurrentUser와 originExecutionId만 전달하고 McpCaller 타입은 mcp에 남긴다.
권한 검사에 필요한 읽기·원본 확인은 chat.application.ContentDraftSnapshots.requireCurrentSnapshotParticipant로 묶어 노출한다. 이 메서드는 ID·stamp·서버 입력 DTO만 반환하고 ConnectorAction을 받지 않는다.

ContentDraftJobs.requestSave(CurrentUser user, Long originExecutionId, UUID snapshotId)는 origin·owner·conversation·binding을 검사하고 기존 ConnectorAction.awaitApproval에 `{snapshotId}`만 저장한다.
기존 save_draft 정책을 현재 catalog에서 평가하고 원고 요청은 grant를 금지한다. connector.json의 save_draft에 grant:false와 identifiers:["snapshotId"]를 명시한다.
snapshotId가 가림 때문에 hiddenArgs로 판정되지 않게 하되 일반 DSL 인자의 가림은 유지한다. 상시 허락으로 승인 없이 우회하지 못하게 한다.
원고 snapshot/job 링크가 있는 action만 ConnectorActionApproval의 TX 밖 select·prepare 후 새 ContentDraftSaveApproval.approvePrepared로 위임한다. 기존 beginApproval 트랜잭션 안에서는 coordinator/read를 호출하지 않는다. 모든 일반 action은 기존 코드로 처리한다.
같은 snapshot의 PENDING·DISPATCHED·terminal·UNKNOWN 요청은 기존 action/job 응답을 반환하고 새로운 승인·job을 만들지 않는다.
승인 lock 안에서 현재 revision·기한·사진 원본 지문·사용자·agent·binding 버전·policy를 검사한다.
승인 실패는 기존 refused action과 안전한 errorCode를 보존하고 외부 호출을 하지 않는다.
성공은 approvedBy·approvedAt·jobId를 채우고 DISPATCHED를 커밋한다. start는 커밋 뒤 최대 한 번이며 성공 응답은 아직 SUCCEEDED가 아니다.
등록 응답 유실·DB 기록 실패·restart는 UNKNOWN이다. 동일 snapshot 재승인과 outbox 재전송은 만들지 않는다.

### 2. ticket과 현재 권한 확인

ContentDraftSaveTicket은 근거 문서의 별도 HMAC domain과 최대 660초 payload를 구현한다. 기존 금융 secret 파생 방식만 참고하고 금융 상태·codec은 바꾸지 않는다.
ContentDraftJobAuthorizeController와 ContentDraftJobAuthorizeDtos는 정확한 내부 POST만 처리한다.
SecurityConfig와 ControlPlaneJwtFilter를 함께 바꾸되 controller의 ticket 인증은 생략하지 않는다.
ContentDraftJobs.authorize(String ticket, String stage, Integer position)는 기능 문서의 사용자→대화→첨부→허용 목록→agent→origin/루트 execution→action→snapshot→job 순서로 잠근 뒤 참가용 검사와 현재 권한·원본 확인을 수행한다.
start·save claim은 각각 startClaimedAt·saveClaimedAt의 NULL→시각 CAS 한 번만 허용한다. 응답 유실 시 두 번째 claim과 새 ticket 재발급은 거절한다.
claim보다 먼저 수정·삭제·철회가 커밋되면 거절하며, claim 뒤 변경이 원격 클릭을 취소한다고 설명하지 않는다.
ticket이나 본문을 DB에 저장하지 않는다. ticket 문자열 표현·오류·로그·요청 dump가 노출되지 않게 시험한다.

### 3. 하위 port와 writer 잠금

신규 chat.application.ContentDraftSaveRequests·ContentDraftJobQueries와 세 반환 record의 정확한 계약·생성자는 기능 문서의 「승인 요청과 조회 port」를 따른다.
ContentDraftJobs가 두 port를 구현한다. 이 단계의 list/refresh는 CP 보관 상태만 반환하고 다음 worker 단계에서 refresh 원격 조회를 연결한다. list는 snapshot 저장소에 owner/conversation/id 범위와 LIMIT 21 쿼리를 추가해 구현한다.
ContentDraftSaveApproval의 `select(CurrentUser user, UUID actionId)`, `prepare(CurrentUser user, UUID actionId)`, `approvePrepared(CurrentUser user, PreparedContentDraftApproval prepared)`를 구현한다.
select는 owner 범위 snapshot/job 링크를 찾는다. prepare는 catalog HTTP·immutable stamp를 준비하고, approvePrepared는 coordinator 단일 진입 후 링크·PENDING·정책·현재 DB 버전을 재검사한다. 일반 approve/grant는 기존 경로다.
ContentDraftJobs 생성자에는 ContentDraftSnapshots·ChatContentMutationCoordinator·ContentDraftJobRepository·ContentDraftSnapshotRepository·ConnectorActionRepository·ContentDraftBindingAccess·UserAccessPolicy·AgentRepository·AgentExecutionRepository·Clock을 주입한다. transport와 ticket은 별도 협력 객체로 주입하며 mcp/presentation 타입을 쓰지 않는다.
authorize는 기존 AgentExecutionRepository.lockById로 origin와 treeRootId를 중복 제거·오름차순 잠근다. CANCELLED UPDATE와 동일 행 잠금이므로 기존 ExecutionRecorder.cancel overload를 변경하거나 사용자 잠금을 그 안에 추가하지 않는다.
`claimStart(Long jobRowId, Instant at)`·`claimSave(Long jobRowId, Instant at)`는 job 저장소의 NULL 조건 UPDATE 결과 1만 수락한다. 잠금 후 현재 권한 읽기와 CAS 커밋을 분리하지 않는다.
`UserAccessPolicy.allowedForUpdate(Long userId)`는 새 MANDATORY 참가 메서드이며 AllowedUserAccessPolicy가 구현한다. AllowedPersonRepository에 findByIdForUpdate·findByEmailForUpdate를 추가한다.
PersonAccessService.setEnabled는 사용자 ID들을 먼저 오름차순 잠그고 person 행을 잠가 메일·현재 상태를 재검사한 뒤 기존 동기 폐기 사건을 낸다. AgentAdminService/Lifecycle 기존 agent 잠금과 공유하며 agent 뒤 사용자 쓰기 잠금을 추가하지 않는다.
잠금 전에 읽은 entity는 최신 locking scalar/refresh로 갱신한다. 실행을 잠근 기존 RecoveredRunRecorder/SubagentUsageReconciler에 역순 사용자 잠금을 넣지 않는다.
binding 해제·계정 변경은 EXECUTING 거절을 보존한다. 새 snapshot 해제 API/식별 port/Lifecycle 완화는 만들지 않는다. 실제 API 성공 철회는 사용자 끄기/agent 비활성, 실제 취소는 CANCELLED 커밋으로 검증한다.

### 4. 같은 phase의 테스트


`backend/src/test/java/com/bifos/assistant/connector/ContentDraftJobsTest.java`는 같은 snapshot 동시 requestSave·approve에 action 1개·jobId 1개·start 호출 최대 1회를 검사한다.
FAILED·UNKNOWN·완료 후 재호출에도 start 0회 추가이며 사진 삭제·binding 해제·현재 revision 변경은 새 실행 0회다.
`backend/src/test/java/com/bifos/assistant/connector/ContentDraftSaveTicketTest.java`는 다른 job·owner·binding·hash·만료·잘못된 서명·금융 ticket과 사용자 JWT를 거절한다.
`backend/src/test/java/com/bifos/assistant/connector/ContentDraftJobAuthorizeControllerTest.java`는 permitAll 경로라도 위조 ticket이 401이며 권한 철회·두 start/save claim·응답 유실이 fail-closed인지 검사한다.
기존 ConnectorActionServiceTest와 ConnectorActionControllerTest에서 일반 도구의 승인·거절·grant·동시성 회귀를 유지한다.

신규 `ContentDraftClaimMysqlTest`에 @Tag("mysql")을 붙이고 검사를 만든다. 이 검사는 실제 ExecutionRecorder.cancel과 authorize를 별도 스레드·트랜잭션·장벽으로 실행한다. cancel 선커밋/claim 선커밋/UPDATE 보유·rollback, origin=루트·서로 다른 ID를 모두 검사한다.
실제 PersonAccessService.setEnabled·AgentAdminService.update와 claim의 양쪽 커밋 순서도 시험한다. fake 두 번째 SELECT로 대체하지 않는다.
ContentDraftSnapshotArchitectureTest는 신규 class 존재·반환 타입·raw ORDER/cycles를 검사한다. frozen 통과만으로 새 port 검증을 대신하지 않는다.
AgentConnectionController/ConnectorConnectionController 실제 API로 승인 뒤 unbind/disconnect/계정 변경 거절·stamp 보존을 확인하고 PENDING 해제 성공 회귀를 유지한다.

## 검증

```bash
# cwd: 저장소 root
set -e
(cd backend && ./gradlew test --tests '*ContentDraftJobsTest' --tests '*ContentDraftSaveTicketTest' --tests '*ContentDraftJobAuthorizeControllerTest' --tests '*ConnectorActionServiceTest' --tests '*ConnectorActionControllerTest' --tests '*AgentConnectionControllerTest' --tests '*ConnectorConnectionControllerTest' --tests '*ContentDraftSnapshotArchitectureTest' --tests '*PersonAccessRollbackTest')
scripts/check-mysql-migration.sh
(cd backend && ./gradlew qualityCheck)
git diff --check
```

운영 목표·hard cap·공유 파일 여유는 phase01의 「운영 변경 예산과 파일 상한」을 따른다. 이 표의 새 소비자도 비용에 포함하고 상한을 넘으면 설계와 구현을 함께 나눈다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/connector/application/ContentDraftJobs.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ContentDraftSaveApproval.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ContentDraftSaveTicket.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/presentation/contentjob/ContentDraftJobAuthorizeController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/presentation/contentjob/ContentDraftJobAuthorizeDtos.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorActionApproval.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorActionService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/config/SecurityConfig.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/shared/auth/ControlPlaneJwtFilter.java` | 수정 |
| `hermes/connectors/naver-blog/connector.json` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ContentDraftJobsTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/ContentDraftSaveTicketTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/ContentDraftJobAuthorizeControllerTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorActionServiceTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorActionControllerTest.java` | 수정 |
| `docs/features/content-draft.md` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ContentDraftSaveRequests.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/ContentDraftJobQueries.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/model/ContentDraftSaveRequestView.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/model/ContentDraftJobView.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/application/model/ContentDraftJobPage.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/model/PreparedContentDraftApproval.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/shared/auth/UserAccessPolicy.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/people/application/AllowedUserAccessPolicy.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/people/application/PersonAccessService.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/people/infra/AllowedPersonRepository.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ContentDraftJobRepository.java` | 수정 (phase02 선행) |
| `backend/src/test/java/com/bifos/assistant/connector/ContentDraftClaimMysqlTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/architecture/ContentDraftSnapshotArchitectureTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/connector/ConnectorConnectionControllerTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/connector/AgentConnectionControllerTest.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ContentDraftSnapshotRepository.java` | 수정 (phase02 선행) |
| `backend/src/test/java/com/bifos/assistant/people/PersonAccessRollbackTest.java` | 수정 |
