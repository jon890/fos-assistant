package com.bifos.assistant.chat.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/** 원고의 식별자와 불변 revision 참조를 저장한다. 본문은 암호문만 받는다. */
@Entity
@Table(name = "content_draft_asset")
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContentDraftAsset {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "revision_id", nullable = false)
    private Long revisionId;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "attachment_id", nullable = false)
    private Long attachmentId;

    @Column(name = "source_fingerprint", nullable = false, columnDefinition = "CHAR(64)")
    private String sourceFingerprint;

    @Column(name = "observation_id")
    private Long observationId;

    @Column(name = "observation_revision")
    private Long observationRevision;

    public ContentDraftAsset(Long revisionId, int position, Long attachmentId, String fingerprint, Long observationId, Long observationRevision) {
        this.revisionId = revisionId;
        this.position = position;
        this.attachmentId = attachmentId;
        this.sourceFingerprint = fingerprint;
        this.observationId = observationId;
        this.observationRevision = observationRevision;
    }
}
