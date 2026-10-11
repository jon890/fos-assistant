package com.bifos.assistant.chat.domain.entity;

import com.bifos.assistant.chat.domain.type.ContentDraftCreationStatus;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
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

/** 원고 생성의 재시도 식별만 보존하며 본문과 권한 증명은 저장하지 않는다. */
@Entity
@Table(name = "content_draft_creation_request", uniqueConstraints = {
    @UniqueConstraint(name = "uq_content_creation_key", columnNames = "creation_key"),
    @UniqueConstraint(name = "uq_content_creation_identity", columnNames = "identity_key")
})
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContentDraftCreationRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "creation_key", nullable = false, columnDefinition = "BINARY(32)")
    private byte[] creationKey;

    @Column(name = "identity_key", nullable = false, columnDefinition = "BINARY(32)")
    private byte[] identityKey;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "profile_name", nullable = false, columnDefinition = "VARCHAR(255)")
    private String profileName;

    @Column(name = "origin_execution_id", nullable = false)
    private Long originExecutionId;

    @Column(name = "root_execution_id", nullable = false)
    private Long rootExecutionId;

    @Column(name = "root_session_id", nullable = false, columnDefinition = "LONGTEXT")
    private String rootSessionId;

    @Column(name = "request_id", nullable = false, columnDefinition = "CHAR(36)")
    private String requestId;

    @Column(name = "request_hash", nullable = true, columnDefinition = "BINARY(32)")
    private byte[] requestHash;

    @Column(name = "draft_public_id", nullable = true, columnDefinition = "CHAR(36)")
    private String draftPublicId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "VARCHAR(16)")
    private ContentDraftCreationStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public ContentDraftCreationRequest(byte[] creationKey, byte[] identityKey, Long ownerUserId, Long conversationId, String profileName, Long originExecutionId, Long rootExecutionId, String rootSessionId, String requestId, byte[] requestHash, String draftPublicId, Instant createdAt) {
        this.creationKey = creationKey;
        this.identityKey = identityKey;
        this.ownerUserId = ownerUserId;
        this.conversationId = conversationId;
        this.profileName = profileName;
        this.originExecutionId = originExecutionId;
        this.rootExecutionId = rootExecutionId;
        this.rootSessionId = rootSessionId;
        this.requestId = requestId;
        this.requestHash = requestHash;
        this.draftPublicId = draftPublicId;
        this.createdAt = createdAt;
        this.status = ContentDraftCreationStatus.CREATED;
    }

    public void markGone() {
        status = ContentDraftCreationStatus.GONE;
        requestHash = null;
        draftPublicId = null;
    }
}
