package com.bifos.assistant.usage.application;

import com.bifos.assistant.usage.domain.ConversationDelivery;
import com.bifos.assistant.usage.infra.AgentExecutionRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대화마다 위임 실행의 결과가 언제 전해졌는지 낸다.
 *
 * <p>{@code attention} 이 이 패키지의 저장소를 바로 import 하지 않도록 읽기 메서드만 둔다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConversationResultDeliveries {

    private final AgentExecutionRepository executions;

    /**
     * 대화마다 위임 실행의 결과를 가장 늦게 전한 시각이다. 전한 실행이 없는 대화는 빠진다.
     *
     * <p>대화마다 읽지 않고 집계 한 번으로 읽는다. 번호가 비면 읽지 않는다.
     */
    public Map<Long, Instant> lastDeliveredAt(Collection<Long> conversationIds) {
        if (conversationIds.isEmpty()) {
            return Map.of();
        }
        return executions.findLastDeliveredByConversation(conversationIds).stream()
                .collect(Collectors.toMap(ConversationDelivery::conversationId, ConversationDelivery::deliveredAt));
    }
}
