package com.bifos.assistant.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 대화에 올린 사진 한 장이다. 본문은 파일로 두고 이 행은 그 파일을 가리키기만 한다.
 *
 * <p>행을 지우지 않는다. 파일을 지우면 {@code deletedAt} 을 적어, 지난 대화에 그 자리에 사진이
 * 있었다는 것을 남긴다. 근거는 ADR-020 에 있다.
 */
@Entity
@Table(name = "chat_attachment")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    /** 함께 보낸 메시지. 아직 보내지 않았으면 비어 있다. */
    @Column(name = "message_id")
    private Long messageId;

    @Column(name = "uploaded_by_user_id", nullable = false)
    private Long uploadedByUserId;

    /** 올릴 때의 파일 이름. 화면에 보이기만 하고 디스크 이름으로 쓰지 않는다. */
    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    /** 디스크에 둔 이름. 번호가 생긴 뒤 같은 트랜잭션에서 채운다. */
    @Column(name = "stored_name", length = 255)
    private String storedName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "byte_size", nullable = false)
    private long byteSize;

    /** 이 시각이 지나면 파일을 지운다. 볼 수 있는지는 정하지 않는다. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** 파일을 실제로 지운 시각. 비어 있으면 아직 볼 수 있다. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    private ChatAttachment(
            Long conversationId,
            Long uploadedByUserId,
            String originalName,
            String contentType,
            long byteSize,
            Instant expiresAt) {
        this.conversationId = conversationId;
        this.uploadedByUserId = uploadedByUserId;
        this.originalName = originalName;
        this.contentType = contentType;
        this.byteSize = byteSize;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    /** 아직 메시지에 묶이지 않은 첨부를 만든다. 디스크 이름은 번호를 받은 뒤 채운다. */
    public static ChatAttachment of(
            Long conversationId,
            Long uploadedByUserId,
            String originalName,
            String contentType,
            long byteSize,
            Instant expiresAt) {
        return new ChatAttachment(
                conversationId, uploadedByUserId, originalName, contentType, byteSize, expiresAt);
    }

    public Long id() {
        return id;
    }

    public Long conversationId() {
        return conversationId;
    }

    public Long messageId() {
        return messageId;
    }

    public Long uploadedByUserId() {
        return uploadedByUserId;
    }

    public String originalName() {
        return originalName;
    }

    public String storedName() {
        return storedName;
    }

    public String contentType() {
        return contentType;
    }

    public long byteSize() {
        return byteSize;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant deletedAt() {
        return deletedAt;
    }

    public void nameStoredFile(String storedName) {
        this.storedName = storedName;
    }

    /** 파일을 지웠다고 적는다. 이미 적혀 있으면 처음 시각을 그대로 둔다. */
    public void markDeleted(Instant now) {
        if (this.deletedAt == null) {
            this.deletedAt = now;
        }
    }

    public boolean isVisible() {
        return deletedAt == null;
    }
}
