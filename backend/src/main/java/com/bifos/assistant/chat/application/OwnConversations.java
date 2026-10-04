package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.domain.ChatMessage;
import com.bifos.assistant.chat.domain.Conversation;
import com.bifos.assistant.chat.domain.type.MessageRole;
import com.bifos.assistant.chat.infra.ChatMessageRepository;
import com.bifos.assistant.chat.infra.ConversationRepository;
import com.bifos.assistant.shared.auth.CurrentUser;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 요청자의 지우지 않은 대화와 그 대화의 turn 을 누가 시작했는지 읽는다. 먼저 알리기의 판정이 읽는다.
 *
 * <p>{@code attention} 이 이 패키지의 저장소를 바로 import 하지 않도록 읽기 메서드만 둔다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OwnConversations {

    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;

    /**
     * 번호들 가운데 요청자의 것이고 지우지 않은 대화만 번호로 묶어 낸다.
     *
     * <p>빈 {@code in} 절은 데이터베이스마다 다르게 동작하므로 번호가 비면 읽지 않는다.
     */
    public Map<Long, Conversation> activeOf(CurrentUser user, Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return conversations.findAllById(ids).stream()
                .filter(conversation -> user.id().equals(conversation.userId()))
                .filter(conversation -> conversation.deletedAt() == null)
                .collect(Collectors.toMap(Conversation::id, Function.identity()));
    }

    /**
     * 그 시각에 시작한 turn 을 사용자가 보냈는지 본다.
     *
     * <p>그 시각까지 그 대화에 저장된 메시지 가운데 답이 아닌 가장 최근 메시지가 사용자 글이면 참이다. 다시 생성도 든다.
     * 자동 turn 은 그 메시지가 알림 줄이라 거짓이다. 실패한 turn 에는 답 메시지가 없을 수 있어 실행의 시작 시각으로 질문을
     * 찾는다.
     */
    public boolean startedByUser(Long conversationId, Instant startedAt) {
        return messages.findTopByConversationIdAndRoleNotAndCreatedAtLessThanEqualOrderByIdDesc(
                        conversationId, MessageRole.ASSISTANT, startedAt)
                .map(ChatMessage::role)
                .filter(MessageRole.USER::equals)
                .isPresent();
    }
}
