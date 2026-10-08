package com.bifos.assistant.proactive.application.model;

import com.bifos.assistant.feedback.application.model.FeedbackLabel;
import com.bifos.assistant.feedback.domain.type.FeedbackActor;
import com.bifos.assistant.feedback.domain.type.FeedbackEventType;
import com.bifos.assistant.feedback.domain.type.FeedbackSubjectType;
import com.bifos.assistant.proactive.domain.type.AutonomyExecutionStatus;
import com.bifos.assistant.proactive.domain.type.AutonomyLevel;
import com.bifos.assistant.proactive.domain.type.AutonomyReason;
import com.bifos.assistant.proactive.domain.type.CheckOutcome;
import com.bifos.assistant.proactive.domain.type.CheckStatus;
import com.bifos.assistant.proactive.domain.type.CheckTrigger;
import com.bifos.assistant.proactive.domain.type.DecisionAxis;
import com.bifos.assistant.proactive.domain.type.DecisionConfidence;
import com.bifos.assistant.proactive.domain.type.DecisionLevel;
import com.bifos.assistant.proactive.domain.type.DecisionOutcome;
import com.bifos.assistant.proactive.domain.type.LoopRunStatus;
import com.bifos.assistant.proactive.domain.type.LoopSkippedReason;
import com.bifos.assistant.proactive.domain.type.ProblemDropReason;
import com.bifos.assistant.proactive.domain.type.ProblemStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 판단 피드백의 offline replay 읽기 모델이다. 상황, 후보, 판단, 정책, 사용자 반응, 실행 결과를 결정 하나로 묶는다. 칸의 뜻은 {@code
 * docs/backend/decision-feedback.md} 의 「replay 읽기 모델」 이 갖는다.
 *
 * <p>모델이 쓴 글(후보의 문제와 행동, 축의 설명), 할 일 제목, Memory 본문, 커넥터 인자와 결과는 싣지 않는다. 번호, 열쇠, 해시, 판단 값만
 * 싣는다.
 *
 * @param version 이 모양의 버전
 * @param labelVersion 반응 읽기 규칙의 버전
 */
public record DecisionFeedbackExport(
        int version, int labelVersion, Instant from, Instant to, List<DecisionRecord> records) {

    public static final int VERSION = 2;

    /**
     * 결정 하나다. 살펴보기에서 나온 것이면 열쇠가 {@code check:<번호>} 이고, 아니면 그 제안의 열쇠다.
     *
     * @param situation 살펴보기에서 나오지 않았으면 null
     */
    public record DecisionRecord(
            String decisionKey,
            Situation situation,
            List<Candidate> candidates,
            List<Judgment> judgments,
            List<Policy> policies,
            List<Subject> subjects) {}

    /**
     * 살펴보기 한 번. 보고를 남기지 않은 성공은 침묵이다.
     *
     * @param loop 그 살펴보기를 이은 매일 루프 시도. 시도가 없으면 null
     */
    public record Situation(
            Long checkId,
            Long agentId,
            CheckTrigger trigger,
            CheckStatus status,
            CheckOutcome outcome,
            boolean reportSurfaced,
            Instant startedAt,
            Instant finishedAt,
            Loop loop) {}

    /**
     * 매일 루프 시도 한 번. 글과 원문, provider 이름은 싣지 않는다. provider 는 같은 결정의 판단이 갖는다.
     *
     * @param skippedReason {@code SKIPPED} 가 아니면 null
     * @param errorCode {@code FAILED} 가 아니면 null
     * @param evaluationId 이 시도가 만든 평가. 평가 전에 끝났거나 기동 때 닫았으면 null
     */
    public record Loop(
            Long runId,
            LoopRunStatus status,
            LoopSkippedReason skippedReason,
            String errorCode,
            Long evaluationId,
            Instant createdAt,
            Instant finishedAt) {}

    /** 문제 후보 하나. 글 대신 열쇠와 정형 값만 싣는다. */
    public record Candidate(
            Long candidateId,
            ProblemStatus status,
            ProblemDropReason dropReason,
            String problemKey,
            String actionType,
            String sideEffect,
            String confidence,
            List<String> evidenceTopicKeys,
            Instant evidenceCheckedAt) {}

    /** 가치 평가 한 번. 축 설명은 싣지 않는다. */
    public record Judgment(
            Long evaluationId,
            Long replayOfId,
            DecisionOutcome outcome,
            int stateVersion,
            Instant asOf,
            Instant createdAt,
            String adapter,
            String adapterVersion,
            String requestedModel,
            String actualModel,
            List<Long> orderedCandidateIds,
            List<CandidateAxes> axes) {}

    /** 후보 하나의 축별 선택과 확신. */
    public record CandidateAxes(
            Long candidateId,
            Map<DecisionAxis, DecisionLevel> choices,
            Map<DecisionAxis, DecisionConfidence> confidences,
            DecisionConfidence confidence) {}

    /** 행동 정책의 판정 하나. */
    public record Policy(
            Long decisionId,
            Long evaluationId,
            Long candidateId,
            AutonomyLevel level,
            List<AutonomyReason> reasons,
            int policyVersion,
            AutonomyExecutionStatus executionStatus,
            Long executionCheckId,
            Instant createdAt) {}

    /**
     * 사용자에게 보인 제안 하나와 그 사건, 읽은 반응.
     *
     * @param wantsNow 「지금 이 제안을 원했는가」 의 표본 값. null 이면 표본이 아니다
     */
    public record Subject(
            String subjectKey,
            FeedbackSubjectType type,
            List<Event> events,
            FeedbackLabel label,
            Boolean wantsNow,
            boolean edited,
            FeedbackEventType outcome,
            boolean persistentPreference) {}

    /** 사건 하나. */
    public record Event(
            FeedbackEventType type,
            FeedbackActor actor,
            Instant at,
            String subjectVersion,
            String reasonCode,
            List<String> changedFields) {}
}
