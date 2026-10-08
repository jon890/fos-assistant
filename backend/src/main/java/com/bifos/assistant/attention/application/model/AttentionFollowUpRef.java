package com.bifos.assistant.attention.application.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 할 일 항목의 값이다.
 *
 * @param id 할 일의 공개 식별자
 * @param dueAt 기한. 없으면 null
 * @param waiting 기다리는 중인가
 * @param proposed 아직 받아들이지 않은 제안인가
 * @param agentProposed 실행 ID로 판정한 에이전트 출처인가
 */
public record AttentionFollowUpRef(UUID id, Instant dueAt, boolean waiting, boolean proposed, boolean agentProposed) {}
