package com.bifos.assistant.proactive.check.application.dto;

import com.bifos.assistant.proactive.check.domain.type.CheckOutcome;
import java.util.List;

/**
 * 살펴보기 답 끝의 {@code <fos-check-result>} 블록을 읽은 결과다. 칸의 뜻과 상한은 {@code docs/features/proactive.md} 의
 * 「결과 계약」 이 갖는다.
 *
 * <p>모델이 쓴 글이라 신뢰하지 않는다. 시각과 신선도는 글 그대로 두고 {@code FindingJudgement} 가 판정에서 읽는다.
 * 읽지 못한 값 때문에 블록 전체가 실패하지 않게 하기 위해서다.
 *
 * @param outcome {@link CheckOutcome#FINDINGS} 나 {@link CheckOutcome#NOTHING_NEW} 만 온다
 * @param problemCandidates 버전 3의 문제 후보다. 버전 1과 2는 비어 있다
 * @param suggestions 제안 항목이다. 기능 스위치가 켜졌을 때만 저장 경로가 읽는다. 버전과 상관없이 없으면 비어 있다
 */
public record CheckResultBlock(
    int version,
    CheckOutcome outcome,
    String summary,
    List<Finding> findings,
    List<String> questions,
    List<String> followUpCandidates,
    List<String> sourceFailures,
    ReportDraft report,
    List<ProblemCandidate> problemCandidates,
    List<Suggestion> suggestions) {

    /** 제안 항목이 없는 블록이다. */
    public CheckResultBlock(
        int version,
        CheckOutcome outcome,
        String summary,
        List<Finding> findings,
        List<String> questions,
        List<String> followUpCandidates,
        List<String> sourceFailures,
        ReportDraft report,
        List<ProblemCandidate> problemCandidates) {
        this(
            version,
            outcome,
            summary,
            findings,
            questions,
            followUpCandidates,
            sourceFailures,
            report,
            problemCandidates,
            List.of());
    }


    /** 발견 하나다. */
    public record Finding(
        String area,
        String topicKey,
        String title,
        String sourceUrl,
        String checkedAt,
        String publishedAt,
        String freshness,
        String whyItMatters,
        List<String> facts,
        List<String> inferences,
        List<String> unknowns,
        Next next,
        String changeSinceLast) {
    }

    /** 발견의 다음 행동이나 논의할 질문이다. {@code type} 은 {@code ACTION} 이나 {@code QUESTION} 이다. */
    public record Next(String type, String text) {
    }

    /**
     * 버전 3의 문제 후보 하나다. 칸의 뜻은 {@code docs/features/proactive.md} 의 「문제 후보」 가 갖는다.
     *
     * @param evidence 근거가 된 같은 블록 발견의 주제 키다
     */
    public record ProblemCandidate(
        String problemKey,
        String problem,
        String relatedGoal,
        List<String> evidence,
        Next proposedAction,
        String confidence,
        String expectedBenefit,
        String sideEffect,
        String risk,
        String changeSinceLast) {
    }

    /**
     * 모델이 낸 제안 항목 하나다. 서버는 소유와 크기만 검사하고 {@code detailJson} 의 구조를 보지 않는다.
     *
     * @param id 모델이 준 항목 식별자. 없으면 비어 있고 저장할 때 블록 안의 순번을 쓴다
     * @param detailJson 모델이 낸 자유 JSON 을 글로 옮긴 것. 없으면 비어 있다
     */
    public record Suggestion(String id, String title, String detailJson) {
    }

    /** 결과 블록 v2에서 모델이 제안한 보고 글이다. 근거와 승인 항목은 Control Plane 이 채운다. */
    public record ReportDraft(List<String> changed, List<String> done, List<String> next) {
    }
}
