# Phase 05. MCP·승인 카드·재접속 조회 연결

**Execution profile**: standard

## 목표

Hermes가 기존 원고의 snapshot을 제시하고 사용자가 기존 승인 카드에서 저장한 뒤 재접속에서도 같은 job을 확인한다.

**범위 외**: 최초 content_draft_write 생성 정책, 전체 에디터, 자동 UNKNOWN 해소, 사진 read 메타데이터, 운영 계정 검사.

## 컨텍스트

**근거 문서**: `docs/features/content-draft.md`의 「정상 흐름과 API」와 「최소 화면과 완료 판정」, `docs/adr/ADR-20261011-content-snapshot-job.md`.
McpCallerResolver.resolve는 profile 토큰에서 사용자를 꺼내지 않고 서명된 session origin에서 정한다.
McpCallContext의 raw HMAC은 args·시간·nonce를 서명하지 않는다. 인자 검증과 snapshot 중복 방지는 서비스에서 별도로 수행한다.
ContentDraftService의 신규 MCP write/read가 아직 없어도 REST로 만든 원고의 ID로 실행할 수 있다. 최초 MCP 생성의 중복 방지 구현을 이 phase의 선행으로 확대하지 않는다.
approval-list.tsx는 현재 PENDING·EXECUTING·UNKNOWN 카드만 보인다. 원고 job의 완료 결과를 유지하려면 snapshot 링크가 있는 terminal 카드도 보이게 해야 한다.

## Blocked 조건

- PR443 actual main, compiler·snapshot·승인·worker loopback이 준비되지 않았으면 `PHASE_BLOCKED: save contract prerequisites`로 종료한다.
- 확정한 본문 없는 종류·크기·파트 수 진단 정책과 복구용 본문 dump 제외 구현·가림 검증이 끝나지 않았으면 새 본문 transport 활성화와 실사용 저장을 열지 않는다. 사용자 선택은 확정됐고 정책 구현·검증이 남았다.

## 의도 메모

- 도구 사건은 가리되 일반 USER·final·interim을 새 도구 사건으로 바꾸지 않는다.
- 원고 MCP write/read의 최초 생성·dump 계획과 공유 파일이 겹친다. actual main에 들어온 정의를 다시 읽고 최소 guard를 재사용하며 다른 checkout을 고치지 않는다.
- 운영 변경 목표 1200줄 이하인 별도 통합 PR이다. 넘으면 MCP 연결과 UI를 별도 관심사 PR로 나누고 각 PR에 해당 계약 시험을 넣는다.
- 최종 구현 PR에서 이 계획을 지운다. 단계 PR은 해당 설계와 구현을 함께 담고 전체 계획만 PR로 열지 않는다.

## 작업 항목

### 1. MCP와 REST 표면

McpContentDraftSnapshots에 content_draft_preview·content_draft_save 정의와 handler를 둔다. 인자는 각각 draftId·revision·bindingId와 snapshotId뿐이다.
unknown·명시적 null·잘못된 정수·식별자·소유자 지정·파일 경로는 거절한다. VALIDATION_FAILED는 기존 HTTP 200 JSON-RPC -32602 고정 문구로 처리한다.
McpDtos에 record·검증을 두고 McpController는 위임만 추가한다. McpToolCatalog와 생성자·순서 시험을 함께 바꾼다.
origin 현재 검사·등록된 child·CANCELLED 차단·사용자 권한·agent 활성·profile 일치를 호출 전후 별도 읽기 트랜잭션으로 검사한다.
사후 차단은 이미 만든 snapshot/action 행을 rollback하지 않는다. 재호출은 기존 snapshot/action을 찾아 외부 실행을 하지 않는다.
성공은 안전한 DTO를 ExternalData.wrap으로 감싼다. path·token·private compiler payload는 반환하지 않는다.
ContentDraftSnapshotController와 Dtos에 기능 문서의 GET 두 개를 구현한다. BFF는 세션에서 사용자 토큰을 발급하며 요청 body에서 owner를 받지 않는다.
ConnectorActionView·ActionView에는 nullable contentDraft summary를 더하고 기존 일반 action 응답·생성자는 유지한다.
summary는 snapshotId·draftId·revision·원래 conversationId와 안전한 job 상태이며 approvedBy·binding stamp·파일 경로는 일반 화면에 내보내지 않는다.

McpContentDraftSnapshots는 신규 chat ContentDraftSaveRequests를 주입하고 save 요청을 port로 보낸다. chat controller는 ContentDraftJobQueries를 주입하고 jobs.refresh/list를 부른다. 정확한 생성자·반환 record는 기능 문서의 port 표를 따른다.
실제 main의 McpCurrentCallerGuard 존재를 먼저 확인한다. canonical MCP 설계에만 있는 미래 producer를 가짜 재사용하지 않고 부재면 최소 guard 이동과 구현·관찰 회귀를 이 phase에 포함한다.
generic approve는 기존 endpoint·경로를 보존한다. EXECUTING snapshot의 실제 binding 해제 성공을 가정하지 않으며 기존 거절과 실제 사용자/agent 철회 경로를 시험한다.
원래 대화의 신규 GET content-draft-jobs는 jobs.list로 bounded 20개+cursor를 반환한다. snapshot 공개 UUID cursor는 owner 범위로만 해석한다. 새 body transport나 원격 status를 목록에서 보내지 않는다.

### 2. 가림과 최소 화면

HermesRunEventStream에서 classify 이후 root/data의 tool·tool_name·toolName·name 별칭을 모두 검사한다.
정확한 새 두 도구 이름과 정확한 __ suffix만 가리고 preview·detail·result·delta·text·output 경로를 검사한다.
fos-ctx hooks REQUIRED_TOOLS와 README·__init__ 설명·MCP 기능 목록을 함께 맞춘다.
ApprovalCard에 ContentDraftSaveCard를 붙인다. 승인 전 snapshot GET으로 canonical preview와 포함·빠진 사진 ID를 표시하고 사진 링크는 기존 owner ACL 경로를 쓴다.
snapshot summary가 있는 terminal 카드는 ApprovalList의 bounded 원고 job 목록과 다음 페이지에서도 재발견하며 승인·모두 승인·grant 단추를 만들지 않는다.
job GET은 화면이 열린 동안만 짧게 폴링하고 terminal 뒤 멈춘다. 재접속·대화 이동·SSE 중복 수신에서는 GET만 보낸다.
STALE·삭제·권한 없음에는 본문과 승인 단추를 지우고 안전한 안내만 표시한다. UNKNOWN에는 다시 저장 단추가 없다.
미지원 transport는 저장할 수 없다는 안내로 끝내고 legacy DSL 저장을 자동 호출하지 않는다.

### 3. 같은 phase의 계약·UI 테스트

`backend/src/test/java/com/bifos/assistant/mcp/McpContentDraftSnapshotsTest.java`와 기존 McpToolCatalogTest·McpCallerInvariantTest는 schemas·도구 순서·서명 필수·불변 허용 집합을 확인한다.
`backend/src/test/java/com/bifos/assistant/chat/ContentDraftSnapshotControllerTest.java`는 GET 두 경로의 owner ACL·stale·내부 값 비노출을 확인한다.
`backend/src/test/java/com/bifos/assistant/hermes/ToolDetailEventStreamTest.java`와 `backend/src/test/java/com/bifos/assistant/chat/ToolDetailStreamTest.java`는 실제 tool.started·tool.completed error=true/false, 별칭 충돌·비표준 이름 없는 사건, root/data 여섯 출력 필드에서 본문 가림을 확인한다.
`hermes/tests/test_fos_ctx.py`는 서명 실패와 등록된 child의 새 도구 호출을 확인한다.
FakeHermes의 신규 snapshot route는 실제 `/api/connectors/{id}/snapshot` body·상태 code·envelope를 사용한다. 기존 `{saved:true}`를 네이버 job 완료 대역으로 쓰지 않는다.
content-snapshot-save 시나리오는 REST 원고 준비, preview·승인·동시 재호출·응답 유실·restart·FAILED·UNKNOWN·terminal·실제 권한 철회·EXECUTING binding 해제 거절을 확인해 start 1회 이하와 같은 jobId를 단언한다.
`test/browser/content-snapshot-save.spec.ts`는 mobile·desktop에서 승인 preview revision, 문구 수정 후 STALE, 미수정 hash 일치, terminal 유지, 재접속 GET-only, 타 사용자 404와 UNKNOWN 재저장 단추 없음, 포함·빠진 ID를 확인한다.
connector-card.spec.ts와 기존 approval 화면의 일반 승인·거절·모두 승인·grant 동작은 유지한다.
본문·경로·token 표식을 HTTP 오류·MCP 사건·저장 로그·dump에 심고 하나라도 나오면 활성화 검증이 실패해야 한다.

일반 action 21개가 뒤따른 terminal 원고와 원고 job 21개가 뒤따른 오래된 링크를 재접속/다음 페이지에서 찾는다. 일반 목록 최근 20 제한은 유지하고 원고 목록은 LIMIT 21·owner/conversation 색인으로 조회한다.
consumer 변경은 CP DTO→BFF GET 목록→web lib→ApprovalList 순서다. nextBeforeSnapshotId null·타 owner cursor·삭제된 대화·빈 목록·중복 actionId·중복 SSE도 검증한다.
canonical MCP의 guard 이동 시험은 기존 관찰 오류 변환·usage/metadata/reasoning 계약을 보존한다. 본문 가림 때문에 일반 USER/final/interim을 삭제하지 않는다.

## 검증

```bash
# cwd: 저장소 root
set -e
(cd backend && ./gradlew test --tests '*McpContentDraftSnapshotsTest' --tests '*McpToolCatalogTest' --tests '*McpCallerInvariantTest' --tests '*ContentDraftSnapshotControllerTest' --tests '*ToolDetailEventStreamTest' --tests '*ToolDetailStreamTest' --tests '*HermesRunEventStreamTest' --tests '*McpMediaObservationToolTest')
python3 -m unittest discover -s hermes/tests -p 'test_fos_ctx.py'
node test/e2e/run.ts
(cd web && pnpm typecheck && pnpm lint)
(cd web && pnpm test:browser content-snapshot-save.spec.ts connector-card.spec.ts --repeat-each=3 --retries=0)
scripts/check-local.sh content-snapshot-save connector-card
```

check-local.sh는 Backend·MySQL·BFF·build·관련 browser·전체 E2E·unit·Hermes·public-safe·quality를 검사하고 첫 실패로 종료한다.
이 phase의 새 fixture·scenario가 run.ts에 등록되어 실행되는지 확인한다. 시나리오의 start 수와 실제 worker loopback 증거를 별도로 남긴다.

운영 목표·hard cap·공유 파일 여유는 phase01의 「운영 변경 예산과 파일 상한」을 따른다. 이 표의 새 소비자도 비용에 포함하고 상한을 넘으면 설계와 구현을 함께 나눈다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpContentDraftSnapshots.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpToolCatalog.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpController.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/presentation/McpDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/contentsnapshot/ContentDraftSnapshotController.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/chat/presentation/contentsnapshot/ContentDraftSnapshotDtos.java` | 신규 |
| `backend/src/main/java/com/bifos/assistant/connector/application/model/ConnectorActionView.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/application/ConnectorActionDetails.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/connector/presentation/ConnectorActionDtos.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/hermes/HermesRunEventStream.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpContentDraftSnapshotsTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/mcp/application/McpToolCatalogTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpCallerInvariantTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ContentDraftSnapshotControllerTest.java` | 신규 |
| `backend/src/test/java/com/bifos/assistant/hermes/ToolDetailEventStreamTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/chat/ToolDetailStreamTest.java` | 수정 |
| `hermes/plugins/fos-ctx/hooks.py` | 수정 |
| `hermes/plugins/fos-ctx/__init__.py` | 수정 |
| `hermes/plugins/fos-ctx/README.md` | 수정 |
| `hermes/tests/test_fos_ctx.py` | 수정 |
| `web/src/lib/connector-action.ts` | 수정 |
| `web/src/lib/content-draft-snapshot.ts` | 신규 |
| `web/src/components/chat/content-draft-save-card.tsx` | 신규 |
| `web/src/components/chat/approval-card.tsx` | 수정 |
| `web/src/components/chat/approval-list.tsx` | 수정 |
| `web/src/app/api/chat/conversations/[conversationId]/content-drafts/[draftId]/snapshots/[snapshotId]/route.ts` | 신규 |
| `web/src/app/api/chat/conversations/[conversationId]/content-drafts/[draftId]/snapshots/[snapshotId]/job/route.ts` | 신규 |
| `test/e2e/fake-hermes/content-snapshot-routes.ts` | 신규 |
| `test/e2e/fake-hermes/connector-routes.ts` | 수정 |
| `test/e2e/fake-hermes/state.ts` | 수정 |
| `test/e2e/scenarios/content-snapshot-save.ts` | 신규 |
| `test/e2e/run.ts` | 수정 |
| `test/browser/content-snapshot-save.spec.ts` | 신규 |
| `test/browser/connector-card.spec.ts` | 수정 |
| `docs/features/content-draft.md` | 수정 |
| `docs/features/mcp.md` | 수정 |
| `docs/features/connector-policy.md` | 수정 |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpCurrentCallerGuard.java` | 신규 (actual main 부재 시; 존재하면 재사용) |
| `backend/src/main/java/com/bifos/assistant/mcp/application/McpMediaObservationTools.java` | 수정 (guard 이동 시) |
| `backend/src/main/java/com/bifos/assistant/connector/application/ContentDraftJobs.java` | 수정 |
| `backend/src/main/java/com/bifos/assistant/chat/infra/ContentDraftSnapshotRepository.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/mcp/McpMediaObservationToolTest.java` | 수정 |
| `backend/src/test/java/com/bifos/assistant/hermes/HermesRunEventStreamTest.java` | 수정 |
| `web/src/app/api/chat/conversations/[conversationId]/content-draft-jobs/route.ts` | 신규 |
