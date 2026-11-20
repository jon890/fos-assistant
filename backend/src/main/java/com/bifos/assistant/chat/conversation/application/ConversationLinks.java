package com.bifos.assistant.chat.conversation.application;

import com.bifos.assistant.chat.conversation.domain.ChatMessage;
import com.bifos.assistant.chat.conversation.domain.type.MessageRole;
import com.bifos.assistant.chat.conversation.infra.ChatMessageRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.net.URI;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 모델이 가리킨 링크가 요청자가 그 대화에 보낸 메시지에 있는지 확인한다(ADR-20261120 / link-image-source, 스펙 generated-image DECISION43).
 *
 * <p>같은 주소로 보는 범위와 메시지의 낱말을 주소로 읽는 규칙은 {@link LinkTarget} 이 갖는다. 메시지를 공백으로 나눈 낱말 하나가 주소 하나다.
 */
@Service
@RequiredArgsConstructor
public class ConversationLinks {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final ChatMessageRepository messages;


    /**
     * @param link 모델이 {@code link:} 뒤에 적은 주소
     * @return 대화에 있으면 받을 주소. {@code https://}, 소문자 호스트, ASCII 밖 문자를 퍼센트 인코딩한 꼴이고 조각은 뺀다. 없으면 빈 값이다
     */
    @Transactional(readOnly = true)
    public Optional<URI> findSentLink(CurrentUser user, Long conversationId, String link) {
        Optional<LinkTarget> target = LinkTarget.of(link);
        if (target.isEmpty()) {
            return Optional.empty();
        }
        for (ChatMessage message : messages.findByConversationIdOrderByIdAsc(conversationId)) {
            if (message.role() != MessageRole.USER || !user.id().equals(message.senderUserId())) {
                continue;
            }
            String content = message.content();
            if (content == null || ChatMessage.UNREADABLE_CONTENT.equals(content)) {
                continue;
            }
            for (String word : WHITESPACE.split(content)) {
                if (target.get().matches(word)) {
                    return target.get().fetchUri();
                }
            }
        }
        return Optional.empty();
    }
}
