package com.bifos.assistant.connector.application.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 답을 기다리는 승인 줄 하나다. 먼저 알리기의 판정이 읽는다.
 *
 * <p>인자와 결과 글은 담지 않는다. 판정은 사람에게 보일 이름과 시각만 쓴다.
 *
 * @param actionId 승인 줄의 공개 식별자
 * @param title 사람에게 보일 이름. 승인 카드와 같은 규칙이다
 * @param agentId 그 줄을 만든 에이전트의 번호
 * @param conversationId 그 줄이 속한 대화의 번호. 대화 없이 돈 실행의 줄은 비어 있다
 * @param createdAt 줄을 만든 시각
 * @param expiresAt 승인을 기다리는 기한
 */
public record PendingApproval(
        UUID actionId, String title, Long agentId, Long conversationId, Instant createdAt, Instant expiresAt) {}
