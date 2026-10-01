package com.bifos.assistant.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * turn 이 도는 동안 사용자가 보낸 메시지다(ADR-048).
 *
 * <p>보내기 전까지만 있는 행이다. 다음 turn 으로 보내면 지우고, 보낸 글은 {@code chat_message} 에 남는다.
 */
@Entity
@Table(name = "chat_pending_message")
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatPendingMessage {
    /** 쌓인 글을 한 turn 의 본문으로 이을 때 사이에 넣는 빈 줄이다. */
    public static final String SEPARATOR = "\n\n";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    // 길이를 주지 않은 @Lob 문자열은 MySQL 에서 tinytext 로 기대돼 기동 검증이 실패하므로 못 박는다.
    @Column(name = "content", nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    /** 참이면 이 대화의 대기 줄을 자동으로 보내지 않는다. */
    @Column(name = "held", nullable = false)
    private boolean held;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    private ChatPendingMessage(Long conversationId, Long userId, String content, boolean held, Instant createdAt) {
        this.conversationId = conversationId;
        this.userId = userId;
        this.content = content;
        this.held = held;
        this.createdAt = createdAt;
    }

    public static ChatPendingMessage queued(
            Long conversationId, Long userId, String content, boolean held, Instant createdAt) {
        return new ChatPendingMessage(conversationId, userId, content, held, createdAt);
    }

    /** 받은 순서대로 글을 {@link #SEPARATOR} 로 잇는다. */
    public static String merged(List<ChatPendingMessage> rows) {
        return rows.stream().map(ChatPendingMessage::content).collect(Collectors.joining(SEPARATOR));
    }

    /**
     * {@code rows} 를 이은 글에 {@code added} 를 더 이었을 때의 길이다.
     *
     * <p>{@code added} 가 null 이면 {@code rows} 만 센다. {@code rows} 가 비어 있으면 사이에 넣을 것이 없어
     * {@code added} 의 길이만 센다.
     */
    public static int mergedLength(List<ChatPendingMessage> rows, String added) {
        int length = merged(rows).length();
        if (added == null) {
            return length;
        }
        if (!rows.isEmpty()) {
            length += SEPARATOR.length();
        }
        return length + added.length();
    }
}
