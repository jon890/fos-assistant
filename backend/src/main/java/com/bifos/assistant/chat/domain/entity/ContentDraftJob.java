package com.bifos.assistant.chat.domain.entity;

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
import com.bifos.assistant.chat.domain.type.ContentDraftJobStatus;
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


    public ContentDraftJob(Long snapshotId, Long actionId, Long ownerUserId, Long conversationId, Instant createdAt) {
        this.snapshotId = snapshotId;
        this.actionId = actionId;
        this.ownerUserId = ownerUserId;
        this.conversationId = conversationId;
        this.createdAt = createdAt;
        this.status = ContentDraftJobStatus.PREVIEW_READY;
    }
}
