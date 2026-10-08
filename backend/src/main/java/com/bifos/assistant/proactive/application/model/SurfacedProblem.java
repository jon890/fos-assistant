package com.bifos.assistant.proactive.application.model;

import com.bifos.assistant.proactive.domain.type.AutonomyLevel;
import java.time.Instant;

/**
 * 매일 루프가 지금 화면에 보일 판정 하나다. 글은 모델이 쓴 것이다.
 *
 * @param decisionId 판정 번호
 * @param level {@code SURFACE} 나 {@code ASK_APPROVAL}
 * @param checkId 원천 살펴보기 번호
 * @param agentId 원천 살펴보기의 에이전트
 * @param conversationId 원천 점검 대화
 * @param problem 문제 글
 * @param action 제안한 다음 행동 글. 비었을 수 있다
 * @param at 판정을 남긴 시각
 */
public record SurfacedProblem(
        Long decisionId,
        AutonomyLevel level,
        Long checkId,
        Long agentId,
        Long conversationId,
        String problem,
        String action,
        Instant at) {}
