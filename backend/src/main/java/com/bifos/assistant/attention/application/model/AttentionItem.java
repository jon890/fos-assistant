package com.bifos.assistant.attention.application.model;

import com.bifos.assistant.attention.domain.type.AttentionLevel;
import com.bifos.assistant.attention.domain.type.AttentionTrigger;
import java.time.Instant;
import java.util.UUID;

/**
 * 판정을 지나 카드에 남은 항목 하나다. 해당하지 않는 마지막 세 칸은 null 이다.
 *
 * @param level {@code NOW} 나 {@code LATER}
 * @param channel 늘 {@code IN_APP} 이다
 */
public record AttentionItem(
        String itemKey,
        String stateKey,
        AttentionLevel level,
        AttentionChannel channel,
        AttentionTrigger trigger,
        String title,
        UUID conversationId,
        String agentName,
        Instant at,
        AttentionWhy why,
        AttentionExecutionRef execution,
        UUID actionId,
        AttentionFollowUpRef followUp,
        AttentionReport report) {}
