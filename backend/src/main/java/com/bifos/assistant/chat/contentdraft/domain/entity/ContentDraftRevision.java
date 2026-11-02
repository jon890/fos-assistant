package com.bifos.assistant.chat.contentdraft.domain.entity;

import com.bifos.assistant.chat.contentdraft.domain.type.ContentDraftRevisionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/** 원고의 식별자와 불변 revision 참조를 저장한다. 본문은 암호문만 받는다. */
@Entity
@Table(name = "content_draft_revision")
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContentDraftRevision {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "draft_id", nullable = false)
    private Long draftId;

    @Column(name = "revision", nullable = false)
    private long revision;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ContentDraftRevisionStatus status;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "body", columnDefinition = "LONGTEXT")
    private String body;

    @Column(name = "body_key_id")
    private Long bodyKeyId;

    /** 본문의 사진 블록이 가리키는 첨부다. position 순서이고 사진이 없으면 빈 목록이다. */
    @Convert(converter = ContentDraftAssetsConverter.class)
    @Column(name = "assets_json", nullable = false, columnDefinition = "LONGTEXT")
    private List<ContentDraftAsset> assets;


    public ContentDraftRevision(Long draftId, long revision, Instant now, String body, Long keyId, List<ContentDraftAsset> assets) {
        this.draftId = draftId;
        this.revision = revision;
        this.status = ContentDraftRevisionStatus.VALID;
        this.schemaVersion = 1;
        this.createdAt = now;
        this.body = body;
        this.bodyKeyId = keyId;
        this.assets = List.copyOf(assets);
    }
}
