package com.bifos.assistant.chat.contentdraft.domain.entity;

import com.bifos.assistant.shared.domain.CreatedAtEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/** 원고의 식별자와 불변 revision 참조를 저장한다. 본문은 암호문만 받는다. */
@Entity
@Table(name = "content_draft")
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContentDraft extends CreatedAtEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, columnDefinition = "CHAR(36)")
    private String publicId;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "current_revision", nullable = false)
    private long currentRevision;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;


    public ContentDraft(String publicId, Long conversationId, Long ownerUserId, Instant expiresAt) {
        this.publicId = publicId;
        this.conversationId = conversationId;
        this.ownerUserId = ownerUserId;
        this.currentRevision = 1;
        this.expiresAt = expiresAt;
    }
}
