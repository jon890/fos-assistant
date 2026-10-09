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
import jakarta.persistence.Transient;
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

    /** 풀지 못한 본문 대신 내는 글이다. 데이터 key 가 없거나 암호문이 다른 줄에서 옮겨 왔을 때다. */
    public static final String UNREADABLE_CONTENT = "읽을 수 없는 메시지입니다.";

    // @Lob 대신 칸 정의를 직접 적는다. 크기 없는 @Lob 문자열은 MySQL 에서 tinytext 로 잡혀 마이그레이션이 만든
    // LONGTEXT 와 달라지고 기동 검증이 실패한다.
    /**
     * 칸에 저장된 글 그대로다. {@link #contentKeyId} 가 있으면 암호문이다(ADR-20261008 / data-encryption).
     *
     * <p>본문을 내는 곳은 {@link #content()} 를 쓴다.
     */
    @Column(name = "content", nullable = false, columnDefinition = "LONGTEXT")
    @Getter
    private String storedContent;

    /** 본문을 암호화한 데이터 key({@code user_data_key.id}). 비어 있으면 {@link #content} 는 평문이다. */
    @Column(name = "content_key_id")
    @Getter
    private Long contentKeyId;

    /** 푼 본문이다. 처음 꺼낼 때 채운다. 저장하지 않는다. */
    @Transient
    private volatile String plain;

    /** 읽어 올 때 붙인 복호화 도구다. 새로 만든 메시지는 비어 있다. */
    @Transient
    private MessageContentOpener opener;

    /** 이 메시지를 쓴 사용자다. 답 메시지는 비어 있다. */
    @Column(name = "sender_user_id")
    @Getter
    private Long senderUserId;

    /** 이 메시지를 만든 실행이다. 사용자 메시지는 비어 있다. */
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
        this.storedContent = content;
        this.senderUserId = senderUserId;
        this.executionId = executionId;
        this.replacesMessageId = replacesMessageId;
        this.createdAt = now;
    }

    /**
     * 본문을 낸다. 암호문이면 처음 부를 때 풀어 둔다. 풀지 못하면 {@link #UNREADABLE_CONTENT} 다.
     *
     * <p>본문을 내는 곳은 모두 이 메서드를 거친다. 저장된 글이 필요하면 {@link #storedContent()} 를 쓴다.
     */
    public String content() {
        if (contentKeyId == null) {
            return storedContent;
        }
        String opened = plain;
        if (opened == null) {
            opened = opener == null ? UNREADABLE_CONTENT : opener.open(this);
            plain = opened;
        }
        return opened;
    }

    public void attachOpener(MessageContentOpener opener) {
        this.opener = opener;
    }

    /**
     * 처음 저장하기 전에 평문을 칸에서 빼 둔다. 칸에는 빈 글이 들어간다.
     *
     * <p>줄 번호가 생긴 뒤에야 AAD 를 만들 수 있어, 빈 글로 넣은 다음 같은 트랜잭션에서 {@link #seal} 로 암호문을 적는다.
     * 데이터베이스에 평문이 한 번도 쓰이지 않는다.
     *
     * @return 뺀 평문
     */
    public String detachPlainForSealing() {
        String detached = storedContent;
        this.plain = detached;
        this.storedContent = "";
        return detached;
    }

    /** 암호문과 그 데이터 key 를 적는다. 푼 본문은 그대로 둔다. */
    public void seal(String sealedContent, Long keyId) {
        this.storedContent = sealedContent;
        this.contentKeyId = keyId;
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
