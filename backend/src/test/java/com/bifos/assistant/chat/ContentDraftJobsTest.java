package com.bifos.assistant.chat;

import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.agent.admin.application.model.AgentUpdateCommand;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.util.Date;
import java.nio.charset.StandardCharsets;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.dao.TransientDataAccessException;
import com.bifos.assistant.agent.domain.type.AgentVisibility;
import com.bifos.assistant.chat.application.model.ContentDraftSaveRequestView;
import com.bifos.assistant.chat.domain.type.ContentDraftJobStatus;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.connector.domain.type.GrantPeriod;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/** 원고 저장 요청, 승인과 jobId 예약, worker 허가를 실제 Spring 트랜잭션과 저장소로 확인한다. */
class ContentDraftJobsTest extends ContentDraftJobFixture {
    @Test
    @DisplayName("늦은 terminal 조회는 UNKNOWN job을 해소하고 UNKNOWN 승인 줄과 시작 횟수는 보존한다")
    void resolvesUnknownJobWithoutRestartingUnknownAction() {
        starter.answer(call -> {
            throw new IllegalStateException("응답 유실");
        });
        UUID snapshotId = preview();
        var request = saves.requestSave(owner, origin.id(), snapshotId);
        contentApproval.approve(owner, request.actionId(), null);
        when(compiler.status(any(), any(), any())).thenAnswer(call -> statusAnswer(call.getArgument(2), "succeeded", true));
        var view = jobQueries.refresh(owner, conversation.id(), draftId, snapshotId);
        assertThat(view.status()).isEqualTo(ContentDraftJobStatus.SUCCEEDED);
        assertThat(view.saveClicked()).isTrue();
        assertThat(actionRows.findByPublicId(request.actionId()).orElseThrow().status()).isEqualTo(ActionStatus.UNKNOWN);
        assertThat(starter.started()).hasSize(1);
        assertThat(jobQueries.refresh(owner, conversation.id(), draftId, snapshotId).status()).isEqualTo(ContentDraftJobStatus.SUCCEEDED);
        Mockito.verify(compiler, Mockito.times(1)).status(any(), any(), any());
    }


    @Test
    @DisplayName("정상 접수 뒤 조회가 succeeded를 읽으면 job과 EXECUTING 승인 줄이 SUCCEEDED가 된다")
    void succeedsActionAfterAcceptedStartAndSucceededStatus() {
        UUID snapshotId = preview();
        var request = saves.requestSave(owner, origin.id(), snapshotId);
        assertThat(contentApproval.approve(owner, request.actionId(), null).status()).isEqualTo(ActionStatus.EXECUTING);
        when(compiler.status(any(), any(), any())).thenAnswer(call -> statusAnswer(call.getArgument(2), "succeeded", true));
        assertThat(jobQueries.refresh(owner, conversation.id(), draftId, snapshotId).status()).isEqualTo(ContentDraftJobStatus.SUCCEEDED);
        assertThat(actionRows.findByPublicId(request.actionId()).orElseThrow().status()).isEqualTo(ActionStatus.SUCCEEDED);
        assertThat(starter.started()).hasSize(1);
    }


    @Test
    @DisplayName("정상 접수 뒤 클릭 전 failed를 읽으면 job과 EXECUTING 승인 줄이 FAILED가 된다")
    void failsActionAfterAcceptedStartAndFailedStatusBeforeClick() {
        UUID snapshotId = preview();
        var request = saves.requestSave(owner, origin.id(), snapshotId);
        assertThat(contentApproval.approve(owner, request.actionId(), null).status()).isEqualTo(ActionStatus.EXECUTING);
        when(compiler.status(any(), any(), any())).thenAnswer(call -> statusAnswer(call.getArgument(2), "failed", false));
        assertThat(jobQueries.refresh(owner, conversation.id(), draftId, snapshotId).status()).isEqualTo(ContentDraftJobStatus.FAILED);
        assertThat(actionRows.findByPublicId(request.actionId()).orElseThrow().status()).isEqualTo(ActionStatus.FAILED);
    }


    @Test
    @DisplayName("조회 timeout과 stamp 변조는 안전한 오류로 끝나고 기존 job 상태를 FAILED로 바꾸지 않는다")
    void preservesJobOnLookupFailureAndStampTampering() {
        UUID snapshotId = preview();
        var request = saves.requestSave(owner, origin.id(), snapshotId);
        contentApproval.approve(owner, request.actionId(), null);
        when(compiler.status(any(), any(), any())).thenThrow(new ApiException(ErrorCode.CONTENT_DRAFT_JOB_UNAVAILABLE, "unavailable"));
        code(() -> jobQueries.refresh(owner, conversation.id(), draftId, snapshotId), ErrorCode.CONTENT_DRAFT_JOB_UNAVAILABLE);
        doAnswer(call -> statusAnswer(call.getArgument(2), "succeeded", true).put("job_id", UUID.randomUUID().toString()))
            .when(compiler).status(any(), any(), any());
        code(() -> jobQueries.refresh(owner, conversation.id(), draftId, snapshotId), ErrorCode.CONTENT_DRAFT_JOB_UNAVAILABLE);
        assertThat(jobRows.findAll().getFirst().status()).isEqualTo(ContentDraftJobStatus.SAVING);
        assertThat(starter.started()).hasSize(1);
    }


    @Test
    @DisplayName("조회 도중 사용자 권한이 철회되면 원격 terminal을 기록하지 않는다")
    void rejectsRemoteTerminalAfterRevocation() {
        UUID snapshotId = preview();
        var request = saves.requestSave(owner, origin.id(), snapshotId);
        contentApproval.approve(owner, request.actionId(), null);
        when(compiler.status(any(), any(), any())).thenAnswer(call -> {
            personAccess.setEnabled(person.id(), false);
            return statusAnswer(call.getArgument(2), "succeeded", true);
        });
        assertThat(jobQueries.refresh(owner, conversation.id(), draftId, snapshotId).status()).isEqualTo(ContentDraftJobStatus.SAVING);
        assertThat(jobRows.findAll().getFirst().status()).isEqualTo(ContentDraftJobStatus.SAVING);
    }


    @Test
    @DisplayName("다른 사용자의 저장 요청·승인·조회는 404이고 기존 행과 시작 횟수는 바뀌지 않는다")
    void rejectsForeignAccessWithoutChangingRows() throws Exception {
        UUID snapshotId = preview();
        var request = saves.requestSave(owner, origin.id(), snapshotId);
        var other = user();
        code(() -> saves.requestSave(other, origin.id(), snapshotId), ErrorCode.CONVERSATION_NOT_FOUND);
        code(() -> contentApproval.approve(other, request.actionId(), null), ErrorCode.CONNECTOR_ACTION_NOT_FOUND);
        code(() -> approvals.approve(other, request.actionId(), null), ErrorCode.CONNECTOR_ACTION_NOT_FOUND);
        code(() -> jobQueries.refresh(other, conversation.id(), draftId, snapshotId), ErrorCode.CONVERSATION_NOT_FOUND);
        code(() -> jobQueries.list(other, conversation.id(), null, 20), ErrorCode.CONVERSATION_NOT_FOUND);
        code(() -> jobQueries.list(other, conversation.id(), snapshotId, 20), ErrorCode.CONVERSATION_NOT_FOUND);
        Instant now = Instant.now();
        String token = Jwts.builder().subject(other.email()).issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(600)))
            .signWith(Keys.hmacShaKeyFor("test-secret-test-secret-test-secret-test-secret"
                .getBytes(StandardCharsets.UTF_8)))
            .compact();
        var http = HttpRequest.newBuilder(URI.create("http://localhost:" + port
            + "/api/v1/connector-actions/" + request.actionId() + "/approve"))
            .header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.noBody()).build();
        assertThat(HttpClient.newHttpClient().send(http, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
        assertThat(actionRows.count()).isEqualTo(1);
        assertThat(actionRows.findAll().getFirst().status()).isEqualTo(ActionStatus.PENDING);
        assertThat(jobRows.count()).isEqualTo(1);
        assertThat(jobRows.findAll().getFirst().status()).isEqualTo(ContentDraftJobStatus.PREVIEW_READY);
        assertThat(jobRows.findAll().getFirst().jobId()).isNull();
        assertThat(starter.started()).isEmpty();
    }


    @Test
    @DisplayName("같은 snapshot의 반복·동시 저장 요청은 상시 허락 없는 승인 줄 하나와 job 하나만 만든다")
    void createsOneApprovalLinePerSnapshotForRepeatedAndConcurrentRequests() throws Exception {
        UUID snapshotId = preview();
        var results = new ArrayList<ContentDraftSaveRequestView>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var pending = List.of(1, 2, 3).stream().map(i -> pool.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return saves.requestSave(owner, origin.id(), snapshotId);
            })).toList();
            start.countDown();
            for (var future : pending) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
        }
        assertThat(results).extracting(ContentDraftSaveRequestView::actionId).containsOnly(results.getFirst().actionId());
        assertThat(results).allSatisfy(view -> {
            assertThat(view.jobId()).isNull();
            assertThat(view.status()).isEqualTo(ContentDraftJobStatus.PREVIEW_READY);
        });
        assertThat(actionRows.count()).isEqualTo(1);
        assertThat(jobRows.count()).isEqualTo(1);
        var action = actionRows.findAll().getFirst();
        assertThat(action.status()).isEqualTo(ActionStatus.PENDING);
        assertThat(action.toolName()).isEqualTo("save_draft");
        assertThat(action.argsJson()).isEqualTo("{\"snapshotId\":\"" + snapshotId + "\"}");
        var card = approvals.listForConversation(owner, conversation.id()).getFirst();
        assertThat(card.grantAllowed()).isFalse();
        assertThat(card.hiddenArgs()).isFalse();
        assertThat(saves.requestSave(owner, origin.id(), snapshotId).actionId()).isEqualTo(results.getFirst().actionId());
        code(() -> contentApproval.approve(owner, action.publicId(), GrantPeriod.TODAY), ErrorCode.VALIDATION_FAILED);
        assertThat(actionRows.findAll().getFirst().status()).isEqualTo(ActionStatus.PENDING);
    }


    @Test
    @DisplayName("원고 승인 줄이 일반 승인 경로로 들어오면 선언 없는 도구로 보고 실행 없이 거절한다")
    void refusesContentLineOnGenericApprovalWithoutExecution() {
        // 일반 승인 경로는 시스템 시계로 기한을 본다. 기한 만료가 아니라 선언 판정으로 거절되는지 보려고 시계를 맞춘다.
        clock.set(Instant.now());
        var request = saves.requestSave(owner, origin.id(), preview());
        var view = approvals.approve(owner, request.actionId(), null);
        assertThat(view.status()).isEqualTo(ActionStatus.REJECTED);
        assertThat(view.errorCode()).isEqualTo("not_executable");
        verify(catalog, never()).execute(any(), any(), any(), any());
        assertThat(starter.started()).isEmpty();
    }


    @Test
    @DisplayName("catalog의 일시 장애는 승인과 job을 대기 상태로 보존한다")
    void preservesPendingApprovalDuringCatalogFailure() {
        var request = saves.requestSave(owner, origin.id(), preview());
        catalog(true);
        when(catalog.readCatalog()).thenThrow(new TransientDataAccessResourceException("synthetic"));
        assertThatThrownBy(() -> contentApproval.approve(owner, request.actionId(), null))
            .isInstanceOf(TransientDataAccessException.class);
        assertThat(actionRows.findAll().getFirst().status()).isEqualTo(ActionStatus.PENDING);
        assertThat(jobRows.findAll().getFirst().status()).isEqualTo(ContentDraftJobStatus.PREVIEW_READY);
        assertThat(starter.started()).isEmpty();
    }


    @Test
    @DisplayName("catalog가 시작을 선언하지 않으면 승인 줄을 실행 없이 거절하고 snapshot은 STALE이다")
    void refusesWithoutStartWhenCapabilityIsNotDeclared() {
        catalog(false);
        UUID snapshotId = preview();
        var request = saves.requestSave(owner, origin.id(), snapshotId);
        var view = contentApproval.approve(owner, request.actionId(), null);
        assertThat(view.status()).isEqualTo(ActionStatus.REJECTED);
        assertThat(view.errorCode()).isEqualTo("not_executable");
        assertThat(starter.started()).isEmpty();
        var job = jobRows.findAll().getFirst();
        assertThat(job.status()).isEqualTo(ContentDraftJobStatus.STALE);
        assertThat(job.jobId()).isNull();
        assertThat(saves.requestSave(owner, origin.id(), snapshotId).status()).isEqualTo(ContentDraftJobStatus.STALE);
        assertThat(actionRows.count()).isEqualTo(1);
    }


    @Test
    @DisplayName("동시 승인은 jobId 하나를 예약하고 시작을 한 번만 보내며 재요청은 같은 job을 돌려준다")
    void reservesOneJobAndStartsOnceUnderConcurrentApproval() throws Exception {
        UUID snapshotId = preview();
        var request = saves.requestSave(owner, origin.id(), snapshotId);
        var outcomes = new ArrayList<String>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var pending = List.of(1, 2).stream().map(i -> pool.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                try {
                    return contentApproval.approve(owner, request.actionId(), null).status().name();
                } catch (ApiException ex) {
                    return ex.code().name();
                }
            })).toList();
            start.countDown();
            for (var future : pending) {
                outcomes.add(future.get(20, TimeUnit.SECONDS));
            }
        }
        assertThat(outcomes).containsExactlyInAnyOrder("EXECUTING", "CONNECTOR_ACTION_NOT_PENDING");
        assertThat(starter.started()).hasSize(1);
        var job = jobRows.findAll().getFirst();
        assertThat(job.status()).isEqualTo(ContentDraftJobStatus.SAVING);
        assertThat(job.jobId()).isEqualTo(starter.started().getFirst().jobId().toString());
        assertThat(job.approvedBy()).isEqualTo(owner.id());
        var snapshot = starter.started().getFirst().snapshot();
        assertThat(snapshot.path("snapshotHash").stringValue()).isEqualTo(snapshotRows.findAll().getFirst().snapshotHash());
        assertThat(starter.started().getFirst().profile()).isEqualTo(binding.agent().hermesProfile());
        var again = saves.requestSave(owner, origin.id(), snapshotId);
        assertThat(again.jobId().toString()).isEqualTo(job.jobId());
        when(compiler.status(any(), any(), any())).thenAnswer(call -> statusAnswer(call.getArgument(2), "running", false));
        assertThat(jobQueries.refresh(owner, conversation.id(), draftId, snapshotId).status()).isEqualTo(ContentDraftJobStatus.SAVING);
        code(() -> contentApproval.approve(owner, request.actionId(), null), ErrorCode.CONNECTOR_ACTION_NOT_PENDING);
        assertThat(starter.started()).hasSize(1);
        // 실행 중인 원고 저장 줄이 있는 동안 기존 연결 해제는 거절하고 바인딩 판을 그대로 둔다.
        var before = bindings.findById(binding.id()).orElseThrow().updatedAt();
        code(() -> bindingService.unbind(owner, binding.agent().code(), CONNECTOR), ErrorCode.CONNECTOR_ACTION_EXECUTING);
        assertThat(bindings.findById(binding.id()).orElseThrow().updatedAt()).isEqualTo(before);
    }


    @Test
    @DisplayName("시작 응답을 잃으면 UNKNOWN으로 두고 다시 요청·승인해도 시작을 더 보내지 않는다")
    void keepsUnknownWithoutRestartWhenStartAnswerIsLost() {
        starter.answer(call -> {
            throw new IllegalStateException("synthetic timeout");
        });
        UUID snapshotId = preview();
        var request = saves.requestSave(owner, origin.id(), snapshotId);
        assertThat(contentApproval.approve(owner, request.actionId(), null).status()).isEqualTo(ActionStatus.UNKNOWN);
        assertThat(jobRows.findAll().getFirst().status()).isEqualTo(ContentDraftJobStatus.UNKNOWN);
        assertThat(saves.requestSave(owner, origin.id(), snapshotId).status()).isEqualTo(ContentDraftJobStatus.UNKNOWN);
        code(() -> contentApproval.approve(owner, request.actionId(), null), ErrorCode.CONNECTOR_ACTION_NOT_PENDING);
        assertThat(starter.started()).hasSize(1);
        assertThat(actionRows.count()).isEqualTo(1);
    }


    @Test
    @DisplayName("worker가 시작 전에 거절하면 FAILED이고 같은 snapshot으로 다시 시작하지 않는다")
    void failsWithoutRestartWhenWorkerRejectsBeforeStart() {
        starter.answer(call -> Optional.of("NAVER_BLOG_BUSY"));
        UUID snapshotId = preview();
        var request = saves.requestSave(owner, origin.id(), snapshotId);
        assertThat(contentApproval.approve(owner, request.actionId(), null).status()).isEqualTo(ActionStatus.FAILED);
        var job = jobRows.findAll().getFirst();
        assertThat(job.status()).isEqualTo(ContentDraftJobStatus.FAILED);
        assertThat(job.errorCode()).isEqualTo("NAVER_BLOG_BUSY");
        assertThat(saves.requestSave(owner, origin.id(), snapshotId).status()).isEqualTo(ContentDraftJobStatus.FAILED);
        assertThat(starter.started()).hasSize(1);
    }


    @Test
    @DisplayName("요청 뒤 원고 revision이 바뀌거나 사진이 삭제되면 승인은 실행 없이 STALE로 끝난다")
    void refusesApprovalAfterRevisionChangeOrPhotoDeletion() {
        UUID snapshotId = preview();
        var request = saves.requestSave(owner, origin.id(), snapshotId);
        drafts.revise(owner, conversation.id(), draftId, 1, inputDraft("수정", true));
        assertThat(contentApproval.approve(owner, request.actionId(), null).status()).isEqualTo(ActionStatus.REJECTED);
        assertThat(jobRows.findAll().getFirst().status()).isEqualTo(ContentDraftJobStatus.STALE);
        assertThat(jobRows.findAll().getFirst().errorCode()).isEqualTo("CONTENT_DRAFT_STALE");
        jobRows.deleteAll();
        snapshotRows.deleteAll();
        actionRows.deleteAll();
        UUID second = snapshots.preview(owner, conversation.id(), draftId, 2, binding.id(), origin.id()).snapshotId();
        var next = saves.requestSave(owner, origin.id(), second);
        attachmentCleaner.delete(photo, NOW);
        assertThat(contentApproval.approve(owner, next.actionId(), null).status()).isEqualTo(ActionStatus.REJECTED);
        assertThat(starter.started()).isEmpty();
    }


    @Test
    @DisplayName("worker 허가는 시작과 저장을 각각 한 번만 주고 manifest 밖 사진 위치를 거절한다")
    void authorizesStartPhotoAndSaveExactlyOnce() {
        String ticket = approved();
        forbidden(() -> authorizer.authorize(ticket, "save", null));
        forbidden(() -> authorizer.authorize(ticket, "photo", 0));
        authorizer.authorize(ticket, "start", null);
        forbidden(() -> authorizer.authorize(ticket, "start", null));
        authorizer.authorize(ticket, "photo", 0);
        authorizer.authorize(ticket, "photo", 1);
        forbidden(() -> authorizer.authorize(ticket, "photo", 2));
        authorizer.authorize(ticket, "save", null);
        forbidden(() -> authorizer.authorize(ticket, "save", null));
        forbidden(() -> authorizer.authorize(ticket, "photo", 0));
        var job = jobRows.findAll().getFirst();
        assertThat(job.startClaimedAt()).isNotNull();
        assertThat(job.saveClaimedAt()).isNotNull();
        code(() -> authorizer.authorize(ticket, "photo", null), ErrorCode.VALIDATION_FAILED);
        code(() -> authorizer.authorize(ticket, "save", 0), ErrorCode.VALIDATION_FAILED);
        code(() -> authorizer.authorize(ticket + "x", "save", null), ErrorCode.UNAUTHENTICATED);
    }


    @Test
    @DisplayName("사용자 끄기·에이전트 비활성·origin 취소가 먼저 커밋되면 저장 허가는 거절되고 claim은 비어 있다")
    void rejectsSaveAfterRevocationCommittedFirst() {
        String ticket = approved();
        authorizer.authorize(ticket, "start", null);
        personAccess.setEnabled(person.id(), false);
        forbidden(() -> authorizer.authorize(ticket, "save", null));
        personAccess.setEnabled(person.id(), true);
        setAgentEnabled(false);
        forbidden(() -> authorizer.authorize(ticket, "save", null));
        setAgentEnabled(true);
        recorder.cancel(executions.findById(origin.id()).orElseThrow());
        forbidden(() -> authorizer.authorize(ticket, "save", null));
        assertThat(jobRows.findAll().getFirst().saveClaimedAt()).isNull();
    }


    @Test
    @DisplayName("관리자 비활성이 에이전트 잠금을 쥐면 저장 허가는 커밋을 기다린 뒤 거절한다")
    void waitsForAgentAdminRevocation() throws Exception {
        String ticket = approved();
        authorizer.authorize(ticket, "start", null);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                setAgentEnabled(false);
                agents.flush();
                held.countDown();
                await(release);
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var claim = pool.submit(() -> outcome(() -> authorizer.authorize(ticket, "save", null)));
            try {
                Thread.sleep(300);
                assertThat(claim.isDone()).isFalse();
            } finally {
                release.countDown();
            }
            writer.get(10, TimeUnit.SECONDS);
            assertThat(claim.get(10, TimeUnit.SECONDS)).isEqualTo("FORBIDDEN");
        }
        assertThat(jobRows.findAll().getFirst().saveClaimedAt()).isNull();
    }


    private void setAgentEnabled(boolean enabled) {
        agentAdmin.update(binding.agent().code(), new AgentUpdateCommand(
            enabled, AgentVisibility.PRIVATE, null, null, null));
    }


    @Test
    @DisplayName("실행 취소가 행 잠금을 쥔 동안 저장 허가는 기다렸다가 커밋을 보고 거절하고 rollback이면 허가한다")
    void waitsForHeldCancelThenFollowsItsOutcome() throws Exception {
        String ticket = approved();
        authorizer.authorize(ticket, "start", null);
        assertThat(heldCancel(ticket, false)).isEqualTo("FORBIDDEN");
        jdbc.update("update agent_execution set status='RUNNING', finished_at=null where id=?", origin.id());
        assertThat(heldCancel(ticket, true)).isEqualTo("ALLOWED");
        assertThat(jobRows.findAll().getFirst().saveClaimedAt()).isNotNull();
    }


    @Test
    @DisplayName("사용자 끄기가 허용 목록 행을 쥔 동안 저장 허가는 기다렸다가 끄기 커밋을 보고 거절한다")
    void waitsForHeldDisableThenRejects() throws Exception {
        String ticket = approved();
        authorizer.authorize(ticket, "start", null);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                personAccess.setEnabled(person.id(), false);
                people.flush();
                held.countDown();
                await(release);
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var claim = pool.submit(() -> outcome(() -> authorizer.authorize(ticket, "save", null)));
            Thread.sleep(300);
            assertThat(claim.isDone()).isFalse();
            release.countDown();
            writer.get(10, TimeUnit.SECONDS);
            assertThat(claim.get(10, TimeUnit.SECONDS)).isEqualTo("FORBIDDEN");
        }
        assertThat(jobRows.findAll().getFirst().saveClaimedAt()).isNull();
    }


    @Test
    @DisplayName("실제 HTTP는 JWT 없이 정확한 경로에 닿고 위조 ticket은 401, 유효 ticket은 허가만 돌려준다")
    void authorizesOverHttpWithoutUserJwt() throws Exception {
        String ticket = approved();
        var forged = post("{\"ticket\":\"" + ticket.substring(0, ticket.length() - 2) + "AA\",\"stage\":\"start\"}");
        assertThat(forged.statusCode()).isEqualTo(401);
        assertThat(forged.body()).doesNotContain(ticket, "비밀");
        assertThat(post("{\"ticket\":\"" + ticket + "\",\"stage\":\"start\",\"x\":1}").statusCode()).isEqualTo(400);
        var allowed = post("{\"ticket\":\"" + ticket + "\",\"stage\":\"start\"}");
        assertThat(allowed.statusCode()).isEqualTo(200);
        assertThat(json.readTree(allowed.body())).isEqualTo(json.readTree("{\"allowed\":true}"));
        var again = post("{\"ticket\":\"" + ticket + "\",\"stage\":\"start\"}");
        assertThat(again.statusCode()).isEqualTo(403);
        assertThat(again.body()).doesNotContain(ticket, "비밀");
    }


    @Test
    @DisplayName("원래 대화의 job 목록은 cursor로 한 페이지씩 보관 상태만 돌려준다")
    void pagesStoredJobsWithSnapshotCursor() {
        UUID snapshotId = preview();
        saves.requestSave(owner, origin.id(), snapshotId);
        var page = jobQueries.list(owner, conversation.id(), null, 20);
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.snapshotId()).isEqualTo(snapshotId);
            assertThat(item.draftId()).isEqualTo(draftId);
            assertThat(item.revision()).isEqualTo(1);
        });
        assertThat(page.nextBeforeSnapshotId()).isNull();
        assertThat(jobQueries.list(owner, conversation.id(), snapshotId, 20).items()).isEmpty();
        code(() -> jobQueries.list(owner, conversation.id(), UUID.randomUUID(), 20), ErrorCode.CONVERSATION_NOT_FOUND);
        code(() -> jobQueries.list(owner, conversation.id(), null, 21), ErrorCode.VALIDATION_FAILED);
        code(() -> jobQueries.refresh(owner, conversation.id(), UUID.randomUUID(), snapshotId), ErrorCode.CONVERSATION_NOT_FOUND);
    }


    private String approved() {
        UUID snapshotId = preview();
        var request = saves.requestSave(owner, origin.id(), snapshotId);
        assertThat(contentApproval.approve(owner, request.actionId(), null).status()).isEqualTo(ActionStatus.EXECUTING);
        return starter.started().getLast().ticket();
    }


    private String heldCancel(String ticket, boolean rollback) throws Exception {
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                recorder.cancel(executions.findById(origin.id()).orElseThrow());
                executions.flush();
                held.countDown();
                await(release);
                if (rollback) {
                    status.setRollbackOnly();
                }
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var claim = pool.submit(() -> outcome(() -> authorizer.authorize(ticket, "save", null)));
            Thread.sleep(300);
            assertThat(claim.isDone()).isFalse();
            release.countDown();
            writer.get(10, TimeUnit.SECONDS);
            return claim.get(10, TimeUnit.SECONDS);
        }
    }


    private HttpResponse<String> post(String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/content-draft-jobs/authorize"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }


    private static String outcome(Runnable action) {
        try {
            action.run();
            return "ALLOWED";
        } catch (ApiException ex) {
            return ex.code().name();
        }
    }


    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }


    private static void forbidden(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.FORBIDDEN));
    }
}
