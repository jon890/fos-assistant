package com.bifos.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.bifos.assistant.chat.domain.entity.ContentDraftJob;
import com.bifos.assistant.chat.domain.type.ContentDraftJobStatus;
import com.bifos.assistant.connector.application.ContentDraftJobs;
import com.bifos.assistant.connector.domain.type.ActionStatus;
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
