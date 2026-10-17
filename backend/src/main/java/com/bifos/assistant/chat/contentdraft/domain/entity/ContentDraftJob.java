package com.bifos.assistant.chat.contentdraft.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import com.bifos.assistant.chat.contentdraft.domain.type.ContentDraftJobStatus;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;

/** 본문 대신 이력 참조와 확정 지문만 보존한다. */
@Entity
@Table(name = "content_draft_job", uniqueConstraints = {
    @UniqueConstraint(name = "uk_content_job_snapshot", columnNames = "snapshot_id"),
    @UniqueConstraint(name = "uk_content_job_action", columnNames = "action_id"),
    @UniqueConstraint(name = "uk_content_job_id", columnNames = "job_id")
})
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContentDraftJob {
    /** ticket 기한과 worker 실행 상한이 지나도록 끝나지 않아 Control Plane 이 닫았다. */
    public static final String EXPIRED = "CONTENT_DRAFT_JOB_EXPIRED";
    /** worker 가 그 stamp 의 job 을 모른다. 상태 파일이 없거나 정리됐다. */
    public static final String NOT_FOUND = "CONTENT_DRAFT_JOB_NOT_FOUND";
    /** 연결이나 바인딩 버전이 바뀌어 옛 stamp 로 결과를 확인하지 못했다. */
    public static final String BINDING_CHANGED = "CONTENT_DRAFT_BINDING_CHANGED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "snapshot_id", nullable = false, columnDefinition = "BIGINT")
    private Long snapshotId;

    @Column(name = "action_id", nullable = false, columnDefinition = "BIGINT")
    private Long actionId;

    @Column(name = "owner_user_id", nullable = false, columnDefinition = "BIGINT")
    private Long ownerUserId;

    @Column(name = "conversation_id", nullable = false, columnDefinition = "BIGINT")
    private Long conversationId;

    @Column(name = "job_id", nullable = true, columnDefinition = "CHAR(36)")
    private String jobId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "VARCHAR(32)")
    private ContentDraftJobStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "approved_by", nullable = true, columnDefinition = "BIGINT")
    private Long approvedBy;

    @Column(name = "approved_at", nullable = true)
    private Instant approvedAt;

    @Column(name = "last_checked_at", nullable = true)
    private Instant lastCheckedAt;

    @Column(name = "start_claimed_at", nullable = true)
    private Instant startClaimedAt;

    @Column(name = "save_claimed_at", nullable = true)
    private Instant saveClaimedAt;

    @Column(name = "stage", nullable = true, columnDefinition = "VARCHAR(32)")
    private String stage;

    @Column(name = "save_clicked", nullable = false, columnDefinition = "BOOLEAN")
    private boolean saveClicked;

    @Column(name = "error_code", nullable = true, columnDefinition = "VARCHAR(64)")
    private String errorCode;

    /** worker 가 알린 실패 사유의 고정 코드다. error_code 와 같은 모양만 담는다. */
    @Column(name = "error_reason", nullable = true, columnDefinition = "VARCHAR(64)")
    private String errorReason;


    public ContentDraftJob(Long snapshotId, Long actionId, Long ownerUserId, Long conversationId, Instant createdAt) {
        this.snapshotId = snapshotId;
        this.actionId = actionId;
        this.ownerUserId = ownerUserId;
        this.conversationId = conversationId;
        this.createdAt = createdAt;
        this.status = ContentDraftJobStatus.PREVIEW_READY;
    }


    /** 승인과 함께 실제 worker가 쓸 jobId를 한 번만 예약한다. */
    public void approve(Long approvedBy, Instant at, String jobId) {
        require(status == ContentDraftJobStatus.PREVIEW_READY && this.jobId == null);
        this.approvedBy = approvedBy;
        this.approvedAt = at;
        this.jobId = jobId;
        this.status = ContentDraftJobStatus.DISPATCHED;
    }


    /** 실행하지 않고 끝난 snapshot이다. 새 preview가 필요하다. */
    public void stale(String errorCode) {
        require(status == ContentDraftJobStatus.PREVIEW_READY);
        this.status = ContentDraftJobStatus.STALE;
        this.errorCode = errorCode;
    }


    /** worker가 예약 job을 접수했다. 저장 성공이 아니다. */
    public void accepted(Instant at) {
        if (status == ContentDraftJobStatus.DISPATCHED) {
            this.status = ContentDraftJobStatus.SAVING;
        }
        this.lastCheckedAt = at;
    }


    /** 저장 클릭 전 실패다. 클릭 뒤의 실패는 결과를 모르는 것으로 둔다. */
    public void fail(String errorCode, Instant at) {
        requireRunning();
        this.status = saveClicked || saveClaimedAt != null ? ContentDraftJobStatus.UNKNOWN : ContentDraftJobStatus.FAILED;
        this.errorCode = errorCode;
        this.errorReason = null;
        this.lastCheckedAt = at;
    }


    /** 전송 여부나 결과를 모른다. 다시 시작하지 않고 같은 job만 조회한다. */
    public void unknown(Instant at) {
        requireRunning();
        this.status = ContentDraftJobStatus.UNKNOWN;
        this.lastCheckedAt = at;
    }


    /**
     * Control Plane 이 더 기다리지 않고 job 을 닫는다. start claim 이 없으면 worker 가 브라우저를 열지 않았으므로 FAILED, 있으면
     * 결과를 모르는 UNKNOWN 이다. 늦게 온 worker 는 claimStart 의 허용 상태에 FAILED 가 없어 시작하지 못한다.
     */
    public void close(String errorCode, Instant at) {
        require(running() || status == ContentDraftJobStatus.UNKNOWN);
        this.status = startClaimedAt == null ? ContentDraftJobStatus.FAILED : ContentDraftJobStatus.UNKNOWN;
        this.errorCode = errorCode;
        this.errorReason = null;
        this.lastCheckedAt = at;
    }


    /** worker 의 답을 더 기다릴 job 이다. Control Plane 이 닫은 UNKNOWN 은 다시 조회하지 않는다. */
    public boolean awaitingResult() {
        return running() || status == ContentDraftJobStatus.UNKNOWN && !EXPIRED.equals(errorCode) && !NOT_FOUND.equals(errorCode)
            && !BINDING_CHANGED.equals(errorCode);
    }


    public boolean running() {
        return status == ContentDraftJobStatus.DISPATCHED || status == ContentDraftJobStatus.SAVING;
    }


    /** 같은 job의 늦은 terminal로 UNKNOWN을 해소하되 확정한 terminal은 덮지 않는다. */
    public void observed(ContentDraftJobStatus observed, String stage, boolean clicked, String code, String reason, Instant at) {
        if (!running() && status != ContentDraftJobStatus.UNKNOWN) {
            return;
        }
        this.stage = stage;
        this.saveClicked = saveClicked || clicked;
        this.lastCheckedAt = at;
        this.errorCode = code;
        this.errorReason = reason;
        if (observed == ContentDraftJobStatus.FAILED && (saveClicked || saveClaimedAt != null)) {
            this.status = ContentDraftJobStatus.UNKNOWN;
        } else if (status != ContentDraftJobStatus.UNKNOWN || observed != ContentDraftJobStatus.SAVING) {
            this.status = observed;
        }
    }


    private void requireRunning() {
        require(running());
    }


    private static void require(boolean condition) {
        if (!condition) {
            throw new IllegalStateException("content draft job state does not allow this transition");
        }
    }
}
