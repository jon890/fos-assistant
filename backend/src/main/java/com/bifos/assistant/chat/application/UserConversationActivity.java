package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.infra.ChatMessageRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 사용자가 보낸 가장 최근 대화 시각을 한 번에 읽는다. */
@Service
@RequiredArgsConstructor
public class UserConversationActivity {

    private final ChatMessageRepository messages;

    @Transactional(readOnly = true)
    public Map<Long, Instant> lastMessageAt(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return messages.findLastUserMessages(userIds).stream()
                .collect(Collectors.toMap(message -> message.userId(), message -> message.at()));
    }
}
