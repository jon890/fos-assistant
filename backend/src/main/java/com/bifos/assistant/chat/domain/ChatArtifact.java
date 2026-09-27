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
 * 에이전트가 한 turn 안에 대화의 결과물 폴더에 만들거나 고친 HTML 파일 하나다.
 *
 * <p>본문은 파일로 두고 이 행은 그 파일을 가리키기만 한다. 행을 지우지 않는다. 보관 기간이 지나 파일을 지우면
 * {@code deletedAt} 을 적어, 지난 대화에 그 자리에 파일이 있었다는 것을 남긴다. 근거는 ADR-027 에 있다.
 */
@Entity
@Table(name = "chat_artifact")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatArtifact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 어느 대화의 폴더인가. 폴더 이름이기도 하다. */
    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    /** 이 파일을 만든 turn 의 답 메시지. */
    @Column(name = "message_id", nullable = false)
    private Long messageId;

    /** 대화 폴더 안의 상대 경로. {@code /} 로 나눈다. */
    @Column(name = "path", nullable = false, length = 500)
    private String path;

    /** 찾았을 때의 크기. */
    @Column(name = "byte_size", nullable = false)
    private long byteSize;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 보관 기간이 지나 파일을 지운 시각. 비어 있으면 아직 파일이 있다. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    private ChatArtifact(Long conversationId, Long messageId, String path, long byteSize) {
        this.conversationId = conversationId;
        this.messageId = messageId;
        this.path = path;
        this.byteSize = byteSize;
        this.createdAt = Instant.now();
    }

    public static ChatArtifact of(Long conversationId, Long messageId, String path, long byteSize) {
        return new ChatArtifact(conversationId, messageId, path, byteSize);
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

    public String path() {
        return path;
    }

    public long byteSize() {
        return byteSize;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant deletedAt() {
        return deletedAt;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
