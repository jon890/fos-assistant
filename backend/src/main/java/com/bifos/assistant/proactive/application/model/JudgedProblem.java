package com.bifos.assistant.proactive.application.model;

import com.bifos.assistant.proactive.domain.ProblemEvidence;
import com.bifos.assistant.proactive.domain.type.ProblemDropReason;
import com.bifos.assistant.proactive.domain.type.ProblemStatus;
import java.time.Instant;
import java.util.List;

/**
 * 검사를 마친 문제 후보다.
 *
 * @param problemKey 앞뒤 공백을 지우고 소문자로 맞춘 문제 키다. 비었으면 빈 글이다
 * @param reason {@link ProblemStatus#DROPPED} 의 까닭이다. 받아들였으면 비어 있다
 * @param evidence 후보가 가리킨 주제 키 가운데 근거가 될 수 있는 발견의 참조다
 * @param evidenceCheckedAt 근거 발견의 확인 시각 가운데 가장 이른 것이다. 근거가 없으면 비어 있다
 */
public record JudgedProblem(
        CheckResultBlock.ProblemCandidate candidate,
        String problemKey,
        ProblemStatus status,
        ProblemDropReason reason,
        List<ProblemEvidence> evidence,
        Instant evidenceCheckedAt) {}
