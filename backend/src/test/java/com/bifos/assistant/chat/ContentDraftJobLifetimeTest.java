package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.chat.application.model.ContentDraftJobView;
import com.bifos.assistant.chat.domain.entity.ContentDraftJob;
import com.bifos.assistant.chat.domain.type.ContentDraftJobStatus;
import com.bifos.assistant.connector.application.ContentDraftJobs;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.hermes.ContentDraftJobNotFound;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 원고 승인 줄과 job 이 진행 중 상태로 영구히 남지 않는지 확인한다. 승인 줄의 수명은 job 정리가 끝낸다. */
class ContentDraftJobLifetimeTest extends ContentDraftJobFixture {
    @Autowired
    ContentDraftJobs contentJobs;


    @Test
    @DisplayName("원고 승인 줄은 오래된 실행 정리와 기동 정리가 UNKNOWN으로 바꾸지 않는다")
    void keepsContentActionOutOfGenericExecutionSweeps() {
        var actionId = accepted();
        Instant later = clock.instant().plus(Duration.ofHours(1));
        assertThat(approvals.markStale(later, later)).isZero();
        assertThat(approvals.markInterrupted(later)).isZero();
        assertThat(actionRows.findByPublicId(actionId).orElseThrow().status()).isEqualTo(ActionStatus.EXECUTING);
    }


    @Test
    @DisplayName("기한이 지난 job은 한 번 조회해 끝난 결과면 그 결과로 승인 줄까지 끝낸다")
    void resolvesOverdueJobFromWorkerBeforeClosing() {
        var actionId = accepted();
        when(compiler.status(any(), any(), any())).thenAnswer(call -> statusAnswer(call.getArgument(2), "succeeded", true));
        assertThat(contentJobs.closeOverdue(clock.instant().plus(Duration.ofHours(1)))).isZero();
        assertThat(jobRows.findAll().getFirst().status()).isEqualTo(ContentDraftJobStatus.SUCCEEDED);
        assertThat(actionRows.findByPublicId(actionId).orElseThrow().status()).isEqualTo(ActionStatus.SUCCEEDED);
    }


    @Test
    @DisplayName("start claim 없이 기한이 지난 job은 조회하지 못해도 FAILED로 닫고 승인 줄도 FAILED다")
    void failsOverdueJobWithoutStartClaim() {
        var actionId = accepted();
        unreachable();
        assertThat(contentJobs.closeOverdue(clock.instant().plus(Duration.ofHours(1)))).isOne();
        var job = jobRows.findAll().getFirst();
        assertThat(job.status()).isEqualTo(ContentDraftJobStatus.FAILED);
        assertThat(job.errorCode()).isEqualTo(ContentDraftJob.EXPIRED);
        assertThat(actionRows.findByPublicId(actionId).orElseThrow().status()).isEqualTo(ActionStatus.FAILED);
    }


    @Test
    @DisplayName("start claim 뒤 기한이 지난 job은 UNKNOWN으로 닫고 승인 줄도 UNKNOWN이며 늦은 worker는 claim을 받지 못한다")
    void closesClaimedOverdueJobAsUnknown() {
        var actionId = accepted();
        String ticket = starter.started().getLast().ticket();
        authorizer.authorize(ticket, "start", null);
        unreachable();
        assertThat(contentJobs.closeOverdue(clock.instant().plus(Duration.ofHours(1)))).isOne();
        assertThat(jobRows.findAll().getFirst().status()).isEqualTo(ContentDraftJobStatus.UNKNOWN);
        assertThat(actionRows.findByPublicId(actionId).orElseThrow().status()).isEqualTo(ActionStatus.UNKNOWN);
        assertThat(contentJobs.closeOverdue(clock.instant().plus(Duration.ofHours(1)))).isZero();
    }


    @Test
    @DisplayName("예약 커밋 뒤 시작 전송 전에 끊겨 DISPATCHED로 남은 job도 기한 정리가 FAILED로 닫는다")
    void closesDispatchedJobLeftByControlPlaneRestart() {
        var actionId = accepted();
        jdbc.update("update content_draft_job set status = 'DISPATCHED'");
        unreachable();
        assertThat(contentJobs.closeOverdue(clock.instant().minus(Duration.ofHours(1)))).isZero();
        assertThat(jobRows.findAll().getFirst().status()).isEqualTo(ContentDraftJobStatus.DISPATCHED);
        assertThat(contentJobs.closeOverdue(clock.instant().plus(Duration.ofHours(1)))).isOne();
        assertThat(jobRows.findAll().getFirst().status()).isEqualTo(ContentDraftJobStatus.FAILED);
        assertThat(actionRows.findByPublicId(actionId).orElseThrow().status()).isEqualTo(ActionStatus.FAILED);
        assertThat(starter.started()).hasSize(1);
    }


    @Test
    @DisplayName("worker가 not_found로 답하면 start claim 없는 job은 FAILED로 닫고 승인 줄도 FAILED다")
    void failsMissingJobWithoutStartClaim() {
        var actionId = accepted();
        when(compiler.status(any(), any(), any())).thenThrow(new ContentDraftJobNotFound());
        assertThat(refresh().status()).isEqualTo(ContentDraftJobStatus.FAILED);
        assertThat(jobRows.findAll().getFirst().errorCode()).isEqualTo(ContentDraftJob.NOT_FOUND);
        assertThat(actionRows.findByPublicId(actionId).orElseThrow().status()).isEqualTo(ActionStatus.FAILED);
        forbidden(() -> authorizer.authorize(starter.started().getLast().ticket(), "start", null));
    }


    @Test
    @DisplayName("start claim 뒤 not_found는 UNKNOWN으로 확정하고 다시 조회하지 않는다")
    void settlesClaimedMissingJobAsUnknownWithoutRequery() {
        var actionId = accepted();
        authorizer.authorize(starter.started().getLast().ticket(), "start", null);
        when(compiler.status(any(), any(), any())).thenThrow(new ContentDraftJobNotFound());
        assertThat(refresh().status()).isEqualTo(ContentDraftJobStatus.UNKNOWN);
        assertThat(actionRows.findByPublicId(actionId).orElseThrow().status()).isEqualTo(ActionStatus.UNKNOWN);
        assertThat(refresh().status()).isEqualTo(ContentDraftJobStatus.UNKNOWN);
        verify(compiler, times(1)).status(any(), any(), any());
    }


    @Test
    @DisplayName("시작 응답을 잃은 job에 worker가 not_found로 답하면 시작하지 않은 것이므로 FAILED다")
    void failsLostStartThatWorkerNeverReceived() {
        starter.answer(call -> {
            throw new IllegalStateException("응답 유실");
        });
        UUID snapshotId = preview();
        var request = saves.requestSave(owner, origin.id(), snapshotId);
        assertThat(contentApproval.approve(owner, request.actionId(), null).status()).isEqualTo(ActionStatus.UNKNOWN);
        when(compiler.status(any(), any(), any())).thenThrow(new ContentDraftJobNotFound());
        assertThat(refresh().status()).isEqualTo(ContentDraftJobStatus.FAILED);
        assertThat(actionRows.findByPublicId(request.actionId()).orElseThrow().status()).isEqualTo(ActionStatus.UNKNOWN);
    }


    @Test
    @DisplayName("시작 전송 직후의 DISPATCHED job은 not_found를 일시 장애로 보고 닫지 않는다")
    void keepsFreshDispatchedJobOnNotFound() {
        accepted();
        jdbc.update("update content_draft_job set status = 'DISPATCHED', approved_at = ?", java.sql.Timestamp.from(clock.instant()));
        when(compiler.status(any(), any(), any())).thenThrow(new ContentDraftJobNotFound());
        code(this::refresh, ErrorCode.CONTENT_DRAFT_JOB_UNAVAILABLE);
        assertThat(jobRows.findAll().getFirst().status()).isEqualTo(ContentDraftJobStatus.DISPATCHED);
        clock.advance(Duration.ofMinutes(3));
        assertThat(refresh().status()).isEqualTo(ContentDraftJobStatus.FAILED);
    }


    @Test
    @DisplayName("바인딩 버전이 바뀌면 옛 stamp로 조회하지 않고 start claim 없는 job을 FAILED로 닫는다")
    void failsUnclaimedJobAfterBindingVersionChange() {
        var actionId = accepted();
        touchBinding();
        assertThat(refresh().status()).isEqualTo(ContentDraftJobStatus.FAILED);
        assertThat(jobRows.findAll().getFirst().errorCode()).isEqualTo(ContentDraftJob.BINDING_CHANGED);
        assertThat(actionRows.findByPublicId(actionId).orElseThrow().status()).isEqualTo(ActionStatus.FAILED);
        verify(compiler, never()).status(any(), any(), any());
        forbidden(() -> authorizer.authorize(starter.started().getLast().ticket(), "start", null));
    }


    @Test
    @DisplayName("start claim 뒤 바인딩 버전이 바뀌면 UNKNOWN으로 닫고 다시 조회하지 않으며 저장 허가도 거절한다")
    void closesClaimedJobAsUnknownAfterBindingVersionChange() {
        var actionId = accepted();
        String ticket = starter.started().getLast().ticket();
        authorizer.authorize(ticket, "start", null);
        touchBinding();
        assertThat(refresh().status()).isEqualTo(ContentDraftJobStatus.UNKNOWN);
        assertThat(actionRows.findByPublicId(actionId).orElseThrow().status()).isEqualTo(ActionStatus.UNKNOWN);
        assertThat(refresh().status()).isEqualTo(ContentDraftJobStatus.UNKNOWN);
        verify(compiler, never()).status(any(), any(), any());
        forbidden(() -> authorizer.authorize(ticket, "save", null));
        assertThat(jobRows.findAll().getFirst().saveClaimedAt()).isNull();
    }


    /** 연결 확인이나 재반영이 바인딩의 updatedAt 을 올린 것과 같다. */
    private void touchBinding() {
        jdbc.update("update agent_connector_binding set updated_at = ? where id = ?",
            java.sql.Timestamp.from(clock.instant().plusSeconds(60)), binding.id());
    }


    private ContentDraftJobView refresh() {
        var job = jobRows.findAll().getFirst();
        var snapshot = snapshotRows.findById(job.snapshotId()).orElseThrow();
        return jobQueries.refresh(owner, conversation.id(), draftId, snapshot.publicId());
    }


    private static void forbidden(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.FORBIDDEN));
    }


    /** 시작을 접수한 job 이다. 승인 줄은 EXECUTING, job 은 SAVING 이다. */
    private UUID accepted() {
        UUID snapshotId = preview();
        var request = saves.requestSave(owner, origin.id(), snapshotId);
        assertThat(contentApproval.approve(owner, request.actionId(), null).status()).isEqualTo(ActionStatus.EXECUTING);
        assertThat(jobRows.findAll().getFirst().status()).isEqualTo(ContentDraftJobStatus.SAVING);
        return request.actionId();
    }


    private void unreachable() {
        when(compiler.status(any(), any(), any())).thenThrow(new ApiException(ErrorCode.CONTENT_DRAFT_JOB_UNAVAILABLE, "unavailable"));
    }
}
