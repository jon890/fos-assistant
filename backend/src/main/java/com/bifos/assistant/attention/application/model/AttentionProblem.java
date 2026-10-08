package com.bifos.assistant.attention.application.model;

/**
 * 먼저 다룰 문제 항목이 싣는 매일 루프의 판정이다. 문제 글은 항목의 {@code title} 이다. 두 글 모두 모델이 쓴 것이라 평문으로 그린다.
 *
 * @param decisionId 판정 번호. 반응 경로가 쓴다
 * @param level {@code SURFACE} 나 {@code ASK_APPROVAL}
 * @param action 제안한 다음 행동 글. 비었을 수 있다
 */
public record AttentionProblem(Long decisionId, String level, String action) {}
