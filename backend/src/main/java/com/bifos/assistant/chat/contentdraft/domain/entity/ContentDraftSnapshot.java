package com.bifos.assistant.chat.contentdraft.domain.entity;

import com.bifos.assistant.shared.domain.CreatedAtEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
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
import java.util.UUID;

/** 본문 대신 이력 참조와 확정 지문만 보존한다. */
@Entity
@Table(name = "content_draft_snapshot", uniqueConstraints = {
    @UniqueConstraint(name = "uk_content_snapshot_public", columnNames = "public_id"),
    @UniqueConstraint(name = "uk_content_snapshot_action", columnNames = "action_id"),
    @UniqueConstraint(name = "uk_content_snapshot_job", columnNames = "job_id"),
    @UniqueConstraint(name = "uk_content_snapshot_revision_binding_hash", columnNames = {"revision_id", "binding_id", "snapshot_hash", "authorization_fingerprint"})
})
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContentDraftSnapshot extends CreatedAtEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID publicId;

    @Column(name = "revision_id", nullable = false, columnDefinition = "BIGINT")
    private Long revisionId;

    @Column(name = "owner_user_id", nullable = false, columnDefinition = "BIGINT")
    private Long ownerUserId;

    @Column(name = "conversation_id", nullable = false, columnDefinition = "BIGINT")
    private Long conversationId;

    @Column(name = "origin_execution_id", nullable = false, columnDefinition = "BIGINT")
    private Long originExecutionId;

    @Column(name = "agent_id", nullable = false, columnDefinition = "BIGINT")
    private Long agentId;

    @Column(name = "connection_id", nullable = false, columnDefinition = "BIGINT")
    private Long connectionId;

    @Column(name = "binding_id", nullable = false, columnDefinition = "BIGINT")
    private Long bindingId;

    @Column(name = "connection_updated_at", nullable = false)
    private Instant connectionUpdatedAt;

    @Column(name = "binding_updated_at", nullable = false)
    private Instant bindingUpdatedAt;

    @Column(name = "policy_fingerprint", nullable = false, columnDefinition = "CHAR(64)")
    private String policyFingerprint;

    @Column(name = "schema_version", nullable = false, columnDefinition = "INT")
    private int schemaVersion;

    @Column(name = "compiler_version", nullable = false, columnDefinition = "VARCHAR(32)")
    private String compilerVersion;

    @Column(name = "payload_fingerprint", nullable = false, columnDefinition = "CHAR(64)")
    private String payloadFingerprint;

    @Column(name = "snapshot_hash", nullable = false, columnDefinition = "CHAR(64)")
    private String snapshotHash;

    @Column(name = "authorization_fingerprint", nullable = false, columnDefinition = "CHAR(64)")
    private String authorizationFingerprint;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** 저장 요청이 만든 job 이다. 저장 요청 전이면 null 이다. */
    @Embedded
    private ContentDraftJob job;


    public ContentDraftSnapshot(UUID publicId, Long revisionId, Long ownerUserId, Long conversationId, Long originExecutionId, Long agentId, Long connectionId, Long bindingId, Instant connectionUpdatedAt, Instant bindingUpdatedAt, String policyFingerprint, int schemaVersion, String compilerVersion, String payloadFingerprint, String snapshotHash, String authorizationFingerprint, Instant expiresAt) {
        this.publicId = publicId;
        this.revisionId = revisionId;
        this.ownerUserId = ownerUserId;
        this.conversationId = conversationId;
        this.originExecutionId = originExecutionId;
        this.agentId = agentId;
        this.connectionId = connectionId;
        this.bindingId = bindingId;
        this.connectionUpdatedAt = connectionUpdatedAt;
        this.bindingUpdatedAt = bindingUpdatedAt;
        this.policyFingerprint = policyFingerprint;
        this.schemaVersion = schemaVersion;
        this.compilerVersion = compilerVersion;
        this.payloadFingerprint = payloadFingerprint;
        this.snapshotHash = snapshotHash;
        this.authorizationFingerprint = authorizationFingerprint;
        this.expiresAt = expiresAt;
    }


    /** 저장 요청의 승인 줄과 함께 job 을 한 번만 만든다. */
    public ContentDraftJob requestJob(Long actionId, Instant at) {
        if (job != null) {
            throw new IllegalStateException("content draft snapshot already has a job");
        }
        this.job = new ContentDraftJob(actionId, at);
        return job;
    }
}
