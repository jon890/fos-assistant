package com.bifos.assistant.chat.infra;

import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.MessageContentOpener;
import com.bifos.assistant.crypto.domain.SealedText;
import com.bifos.assistant.crypto.domain.TextCipher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 메시지 본문을 저장할 때 암호화하고 읽을 때 푼다(ADR-20261008 / data-encryption).
 *
 * <p>AAD 는 {@code chat_message:<메시지 번호>:conversation:<대화 번호>:user:<대화 주인>} 이다. 데이터베이스에서 암호문을 다른
 * 줄로 옮기거나 메시지의 대화 번호나 대화의 주인을 바꾸면 풀리지 않고 {@link ChatMessage#UNREADABLE_CONTENT} 가 보인다.
 *
 * <p>대화 주인은 대화마다 읽어 30초 동안 둔다. 한 대화의 메시지 여러 개를 풀 때 같은 조회를 되풀이하지 않는다. 데이터베이스에서
 * 주인을 바꾸면 늦어도 30초 뒤부터 그 대화의 본문이 풀리지 않는다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatMessageContents implements MessageContentOpener {

    /** 읽어 둔 대화 주인 수의 상한. 넘으면 비우고 다시 읽는다. */
    private static final int OWNER_CACHE_LIMIT = 10_000;

    private static final Duration OWNER_TTL = Duration.ofSeconds(30);

    private final TextCipher cipher;
    private final ConversationRepository conversations;
    private final Clock clock;
    private final ConcurrentMap<Long, Owner> owners = new ConcurrentHashMap<>();

    /**
     * 처음 저장할 메시지의 본문을 암호화한다. 줄 번호가 있어야 하므로 {@code persist} 뒤에 부른다.
     *
     * @param plain {@link ChatMessage#detachPlainForSealing()} 로 뺀 평문
     */
    public void seal(ChatMessage message, Long ownerUserId, String plain) {
        SealedText sealed = cipher.seal(ownerUserId, aad(message, ownerUserId), plain)
                .orElseThrow(() -> new IllegalStateException("message encryption is not enabled"));
        message.seal(sealed.content(), sealed.keyId());
    }

    /** 새 메시지를 암호화해 저장하는가. 거짓이면 평문으로 저장한다 */
    public boolean sealing() {
        return cipher.enabled();
    }

    @Override
    public String open(ChatMessage message) {
        Optional<Long> owner = ownerOf(message.conversationId());
        if (owner.isEmpty()) {
            log.warn("주인 없는 대화의 메시지라 풀지 않는다 messageId={}", message.id());
            return ChatMessage.UNREADABLE_CONTENT;
        }
        return cipher.open(message.contentKeyId(), owner.get(), aad(message, owner.get()), message.storedContent())
                .orElseGet(() -> {
                    log.warn("메시지 본문을 풀지 못했다 messageId={} conversationId={}", message.id(), message.conversationId());
                    return ChatMessage.UNREADABLE_CONTENT;
                });
    }

    private Optional<Long> ownerOf(Long conversationId) {
        Instant now = clock.instant();
        Owner cached = owners.get(conversationId);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return Optional.of(cached.userId());
        }
        Optional<Long> read = conversations.findOwnerId(conversationId);
        if (owners.size() >= OWNER_CACHE_LIMIT) {
            owners.clear();
        }
        read.ifPresent(owner -> owners.put(conversationId, new Owner(owner, now.plus(OWNER_TTL))));
        return read;
    }

    /** 읽어 둔 대화 주인과 버릴 시각이다. */
    private record Owner(Long userId, Instant expiresAt) {}

    private static String aad(ChatMessage message, Long ownerUserId) {
        return "chat_message:" + message.id() + ":conversation:" + message.conversationId() + ":user:" + ownerUserId;
    }
}
