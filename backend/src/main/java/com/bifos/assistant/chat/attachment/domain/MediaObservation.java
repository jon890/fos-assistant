package com.bifos.assistant.chat.attachment.domain;

import com.bifos.assistant.chat.attachment.domain.type.ObservationProvenanceKind;
import com.bifos.assistant.chat.attachment.domain.type.ObservationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** 관찰은 수정하지 않고 저장 요청마다 새 revision을 만든다. 본문은 같은 트랜잭션에서 암호화한 뒤에만 커밋한다. */
@Entity
@Table(
    name = "media_observation",
    uniqueConstraints = {
        @UniqueConstraint(columnNames = {"attachment_id", "revision"}),
        @UniqueConstraint(columnNames = {"attachment_id", "id"}),
        @UniqueConstraint(columnNames = {"attachment_id", "request_id"})
    })
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MediaObservation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "attachment_id", nullable = false)
    private Long attachmentId;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(nullable = false)
    private long revision;

    @Column(name = "source_fingerprint", nullable = false, columnDefinition = "CHAR(64)")
    private String sourceFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ObservationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "provenance_kind", nullable = false, length = 32)
    private ObservationProvenanceKind provenanceKind;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Column(name = "prompt_version", nullable = false, length = 64)
    private String promptVersion;

    @Column(name = "origin_execution_id")
    private Long originExecutionId;

    @Column(length = 128)
    private String provider;

    @Column(name = "provider_version", length = 128)
    private String providerVersion;

    @Column(length = 128)
    private String model;

    @Column(name = "model_version", length = 128)
    private String modelVersion;

    /** 이 revision 을 만든 저장 요청의 UUID 다. 같은 첨부 안에서 유일하다. 새 줄은 늘 채운다. */
    @Column(name = "request_id", nullable = false, columnDefinition = "CHAR(36)")
    private String requestId;

    /** 그 요청 내용의 hash 다. 같은 UUID 재시도의 내용이 같은지 본다. */
    @Column(name = "request_hash", nullable = false, columnDefinition = "CHAR(64)")
    private String requestHash;

    @Column(columnDefinition = "LONGTEXT")
    private String body;

    @Column(name = "body_key_id")
    private Long bodyKeyId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attachment_id", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ChatAttachment attachment;


    public MediaObservation(
        ChatAttachment attachment,
        long revision,
        String fingerprint,
        ObservationStatus status,
        ObservationProvenanceKind kind,
        int schemaVersion,
        String promptVersion,
        Long executionId,
        String provider,
        String providerVersion,
        String model,
        String modelVersion,
        UUID requestId,
        String requestHash,
        Instant now) {
        this.attachmentId = attachment.id();
        this.conversationId = attachment.conversationId();
        this.ownerUserId = attachment.uploadedByUserId();
        this.revision = revision;
        this.sourceFingerprint = fingerprint;
        this.status = status;
        this.provenanceKind = kind;
        this.schemaVersion = schemaVersion;
        this.promptVersion = promptVersion;
        this.originExecutionId = executionId;
        this.provider = provider;
        this.providerVersion = providerVersion;
        this.model = model;
        this.modelVersion = modelVersion;
        this.requestId = requestId.toString();
        this.requestHash = requestHash;
        this.createdAt = now;
        this.expiresAt = attachment.expiresAt();
    }


    public void seal(String body, Long keyId) {
        this.body = body;
        this.bodyKeyId = keyId;
    }
}
