package com.bifos.assistant.proactive.application;

import com.bifos.assistant.proactive.application.model.CheckResultBlock.Next;
import com.bifos.assistant.proactive.application.model.CheckResultBlock.ProblemCandidate;
import com.bifos.assistant.proactive.application.model.JudgedFinding;
import com.bifos.assistant.proactive.application.model.JudgedProblem;
import com.bifos.assistant.proactive.domain.ProblemEvidence;
import com.bifos.assistant.proactive.domain.type.FindingKind;
import com.bifos.assistant.proactive.domain.type.FindingReason;
import com.bifos.assistant.proactive.domain.type.ProblemDropReason;
import com.bifos.assistant.proactive.domain.type.ProblemStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 문제 후보마다 받아들일지 버릴지 정한다(ADR-093). 모델이 쓴 값을 믿지 않고 Control Plane 이 결정적으로 확인한다.
 *
 * <p>검사는 {@code reasonOf} 의 순서이고 처음 걸린 까닭 하나만 남긴다. 까닭마다의 조건은 {@link ProblemDropReason} 이, 그 조건을
 * 정한 결정은 ADR-093 의 까닭 표가 갖는다. 우선순위와 권한은 정하지 않는다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ProblemJudgement {

    private static final Set<String> ACTION_TYPES = Set.of("ACTION", "QUESTION");
    private static final Set<String> CONFIDENCES = Set.of("LOW", "MEDIUM", "HIGH");
    private static final Set<String> SIDE_EFFECTS = Set.of("NONE", "INTERNAL", "EXTERNAL");

    /**
     * 같은 블록의 후보를 차례로 검사한다. 발견이 없으면 가리킬 근거가 없으므로 검사하지 않고 빈 목록을 돌려준다.
     *
     * @param findings 같은 블록의 발견을 {@link FindingJudgement} 로 검사한 결과다
     * @param acceptedProblemKeys 같은 점검 대화에서 최근에 받아들인 후보의 정규화한 문제 키다
     * @param openFollowUpTitle 그 사용자에게 같은 제목의 열린 할 일이 있는지 답한다
     */
    public static List<JudgedProblem> judge(
            List<ProblemCandidate> candidates,
            List<JudgedFinding> findings,
            Set<String> acceptedProblemKeys,
            Predicate<String> openFollowUpTitle) {
        if (candidates.isEmpty() || findings.isEmpty()) {
            return List.of();
        }
        Map<String, JudgedFinding> evidenceByTopic = usableEvidence(findings);
        Set<String> acceptedInBlock = new HashSet<>();
        List<JudgedProblem> judged = new ArrayList<>();
        for (ProblemCandidate candidate : candidates) {
            String key = normalizedKey(candidate.problemKey());
            List<ProblemEvidence> evidence = evidenceOf(candidate, evidenceByTopic);
            ProblemDropReason reason =
                    reasonOf(candidate, key, evidence, acceptedInBlock, acceptedProblemKeys, openFollowUpTitle);
            if (reason == null) {
                acceptedInBlock.add(key);
            }
            Instant checkedAt = evidence.stream()
                    .map(ProblemEvidence::checkedAt)
                    .filter(Objects::nonNull)
                    .min(Comparator.naturalOrder())
                    .orElse(null);
            judged.add(new JudgedProblem(
                    candidate,
                    key,
                    reason == null ? ProblemStatus.ACCEPTED : ProblemStatus.DROPPED,
                    reason,
                    evidence,
                    checkedAt));
        }
        return List.copyOf(judged);
    }

    /** 앞뒤 공백을 지우고 소문자로 맞춘다. 비었으면 빈 글이다. */
    public static String normalizedKey(String problemKey) {
        return problemKey == null ? "" : problemKey.strip().toLowerCase(Locale.ROOT);
    }

    private static ProblemDropReason reasonOf(
            ProblemCandidate candidate,
            String key,
            List<ProblemEvidence> evidence,
            Set<String> acceptedInBlock,
            Set<String> acceptedProblemKeys,
            Predicate<String> openFollowUpTitle) {
        if (isIncomplete(candidate, key)) {
            return ProblemDropReason.INCOMPLETE;
        }
        if (isBlank(candidate.relatedGoal())) {
            return ProblemDropReason.NO_GOAL;
        }
        if (evidence.isEmpty()) {
            return ProblemDropReason.NO_EVIDENCE;
        }
        if (acceptedInBlock.contains(key)
                || (acceptedProblemKeys.contains(key) && isBlank(candidate.changeSinceLast()))) {
            return ProblemDropReason.DUPLICATE;
        }
        if (openFollowUpTitle.test(candidate.proposedAction().text())) {
            return ProblemDropReason.EXISTING_FOLLOW_UP;
        }
        return null;
    }

    private static boolean isIncomplete(ProblemCandidate candidate, String key) {
        Next action = candidate.proposedAction();
        return key.isEmpty()
                || isBlank(candidate.problem())
                || isBlank(candidate.expectedBenefit())
                || action == null
                || isBlank(action.text())
                || !ACTION_TYPES.contains(action.type())
                || !CONFIDENCES.contains(candidate.confidence())
                || !SIDE_EFFECTS.contains(candidate.sideEffect());
    }

    /**
     * 근거가 될 수 있는 발견을 주제 키로 찾게 둔다. 「새로 알릴 것」 이거나, 이미 알린 것이라 참고로만 내린 발견이다. 이미 알린 공고라도
     * 마감이 다가오면 문제는 남아 있기 때문이다. 같은 주제 키가 여럿이면 앞의 것을 쓴다.
     */
    private static Map<String, JudgedFinding> usableEvidence(List<JudgedFinding> findings) {
        Map<String, JudgedFinding> byTopic = new LinkedHashMap<>();
        for (JudgedFinding finding : findings) {
            String topicKey =
                    finding.finding() == null ? null : finding.finding().topicKey();
            boolean usable = finding.kind() == FindingKind.NEW || finding.reason() == FindingReason.REPEATED;
            if (usable && !isBlank(topicKey)) {
                byTopic.putIfAbsent(topicKey.strip(), finding);
            }
        }
        return byTopic;
    }

    /** 후보가 가리킨 주제 키 가운데 근거가 될 수 있는 발견의 참조다. 같은 주제 키를 두 번 적어도 한 번만 넣는다. */
    private static List<ProblemEvidence> evidenceOf(ProblemCandidate candidate, Map<String, JudgedFinding> byTopic) {
        if (candidate.evidence() == null) {
            return List.of();
        }
        List<ProblemEvidence> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String topicKey : candidate.evidence()) {
            String key = topicKey == null ? "" : topicKey.strip();
            JudgedFinding finding = byTopic.get(key);
            if (finding != null && seen.add(key)) {
                found.add(new ProblemEvidence(key, finding.sourceUrl(), finding.checkedAt()));
            }
        }
        return List.copyOf(found);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
