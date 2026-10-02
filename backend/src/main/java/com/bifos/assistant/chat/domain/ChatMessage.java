package com.bifos.assistant.chat.domain;

import com.bifos.assistant.chat.domain.type.MessageRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.experimental.Accessors;

@Entity
@Table(name = "chat_message")
@Accessors(fluent = true)
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Getter
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    @Getter
    private Long conversationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    @Getter
    private MessageRole role;

    // Declared outright rather than with @Lob: Hibernate maps an unsized @Lob String to tinytext on
    // MySQL, which does not match the LONGTEXT the migration creates, and startup validation fails.
    @Column(name = "content", nullable = false, columnDefinition = "LONGTEXT")
    @Getter
    private String content;

    /** The user who wrote this message. Null for assistant messages. */
    @Column(name = "sender_user_id")
    @Getter
    private Long senderUserId;

    /** The execution that produced this message. Null for user messages. */
    @Column(name = "execution_id")
    @Getter
    private Long executionId;

    /** 이 메시지가 대신한 바로 앞 판의 메시지 번호다. */
    @Column(name = "replaces_message_id")
    @Getter
    private Long replacesMessageId;

    @Column(name = "created_at", nullable = false)
    @Getter
    private Instant createdAt;

    protected ChatMessage() {}

    private ChatMessage(
            Long conversationId,
            MessageRole role,
            String content,
            Long senderUserId,
            Long executionId,
            Long replacesMessageId,
            Instant now) {
        this.conversationId = conversationId;
        this.role = role;
        this.content = content;
        this.senderUserId = senderUserId;
        this.executionId = executionId;
        this.replacesMessageId = replacesMessageId;
        this.createdAt = now;
    }

    public static ChatMessage fromUser(Long conversationId, Long senderUserId, String content, Instant now) {
        return new ChatMessage(conversationId, MessageRole.USER, content, senderUserId, null, null, now);
    }

    public static ChatMessage fromAssistant(Long conversationId, String content, Long executionId, Instant now) {
        return new ChatMessage(conversationId, MessageRole.ASSISTANT, content, null, executionId, null, now);
    }

    /** Control Plane 이 적는 메시지다. 보낸 사용자도, 만든 실행도, 대신한 메시지도 없다. */
    public static ChatMessage fromSystem(Long conversationId, String content, Instant now) {
        return new ChatMessage(conversationId, MessageRole.SYSTEM, content, null, null, null, now);
    }

    public static ChatMessage regeneratedAnswer(
            Long conversationId, String content, Long executionId, Long replacesMessageId, Instant now) {
        return new ChatMessage(
                conversationId, MessageRole.ASSISTANT, content, null, executionId, replacesMessageId, now);
    }
}
