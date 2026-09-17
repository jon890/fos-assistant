package com.bifos.assistant.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "chat_message")
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private MessageRole role;

    // Declared outright rather than with @Lob: Hibernate maps an unsized @Lob String to tinytext on
    // MySQL, which does not match the LONGTEXT the migration creates, and startup validation fails.
    @Column(name = "content", nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    /** The user who wrote this message. Null for assistant messages. */
    @Column(name = "sender_user_id")
    private Long senderUserId;

    /** The execution that produced this message. Null for user messages. */
    @Column(name = "execution_id")
    private Long executionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ChatMessage() {
    }

    private ChatMessage(
            Long conversationId, MessageRole role, String content, Long senderUserId, Long executionId) {
        this.conversationId = conversationId;
        this.role = role;
        this.content = content;
        this.senderUserId = senderUserId;
        this.executionId = executionId;
        this.createdAt = Instant.now();
    }

    public static ChatMessage fromUser(Long conversationId, Long senderUserId, String content) {
        return new ChatMessage(conversationId, MessageRole.USER, content, senderUserId, null);
    }

    public static ChatMessage fromAssistant(Long conversationId, String content, Long executionId) {
        return new ChatMessage(conversationId, MessageRole.ASSISTANT, content, null, executionId);
    }

    public Long id() {
        return id;
    }

    public Long conversationId() {
        return conversationId;
    }

    public MessageRole role() {
        return role;
    }

    public String content() {
        return content;
    }

    public Long senderUserId() {
        return senderUserId;
    }

    public Long executionId() {
        return executionId;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
