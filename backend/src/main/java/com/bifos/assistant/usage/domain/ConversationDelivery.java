package com.bifos.assistant.usage.domain;

import java.time.Instant;

/**
 * 한 대화에서 위임 실행의 결과를 가장 늦게 전한 시각이다.
 *
 * @param conversationId 대화 번호
 * @param deliveredAt 그 대화의 위임 실행 가운데 가장 늦은 {@code result_delivered_at}
 */
public record ConversationDelivery(Long conversationId, Instant deliveredAt) {}
